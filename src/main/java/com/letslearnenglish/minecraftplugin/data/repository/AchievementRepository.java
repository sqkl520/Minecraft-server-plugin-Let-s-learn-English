package com.letslearnenglish.minecraftplugin.data.repository;

import com.letslearnenglish.minecraftplugin.data.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Achievement 持久化仓储 —— 承接原 {@code AchievementManager} 内的 4 处裸 JDBC
 * （纯机械搬家，SQL 逐字保留，仅补日志）。
 *
 * <p>边界：本类只做"表 + JDBC"，不持有成就缓存、不做解锁判定、不发通知；
 * 那些仍归 {@code AchievementManager}。</p>
 *
 * <p><b>锁纪律</b>：所有方法一律"锁内取连接"——
 * {@code synchronized (db.getConnectionLock())} 包住 {@code db.getConnection()} 与
 * 语句执行，与 {@link DatabaseManager#getConnectionLock()} javadoc 的迁移约定一致；
 * 本类不调用 {@code setAutoCommit}，不改变连接的事务状态。</p>
 *
 * <p><b>线程契约</b>：全部方法都是同步阻塞方法，线程模型由调用方决定
 * （现有调用点既有主线程——进服加载 / 答对路径 / 会话结算，也有异步——
 * checkHelpAchievement 派发的判定链），本类不自行派发调度。</p>
 *
 * <p><b>失败降级</b>：读方法失败返回"已读到的部分"（可能为空）而非抛异常，
 * 与搬迁前一致。<b>日志口径按原实现逐方法保留</b>（R6-E 修正）：
 * {@link #findUnlocked} 与 {@link #upsertUnlock} 记 WARNING（原实现即如此）；
 * {@link #findRecentDays} 与 {@link #countDistinctStudyDays} <b>静默</b>返回
 * （原实现 catch 块无日志，且这两条在每次会话结算/成就墙打开时都跑，不能刷屏）。
 * 写方法失败绝不抛出（否则会中断成就解锁链）。</p>
 *
 * <p><b>round5 L-01</b>：{@link #findUnlocked} 的调用链（{@code onPlayerChat → showSessionComplete
 * → checkSessionAchievements}）全段无 catch，读失败抛出会中断会话结算——故失败方向统一为
 * "少报"。部分 Map 的污染改在写侧消除：{@link #upsertUnlock} 撞键 no-op，不覆写
 * {@code unlocked_at}（{@code ReportRepository} 的窗口统计依赖它）。</p>
 *
 * <p><b>上述"绝不抛"只覆盖 {@link java.sql.SQLException}</b>：{@link DatabaseManager#getConnection()}
 * 在连接不可用/初始化失败时抛未检查的 {@link RuntimeException}，它不是 SQLException，
 * 会穿透本类全部 catch。<b>这是刻意的</b>——连接层故障必须冒泡，不能变成本类的静默空值。</p>
 */
public class AchievementRepository {

    private final DatabaseManager db;
    private final Logger logger;

    /**
     * @param db     数据库管理器（提供表前缀、方言判定、共享连接与连接锁）
     * @param logger 插件日志器（失败降级时记 WARNING；调用方传 {@code plugin.getLogger()}）
     */
    public AchievementRepository(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /**
     * R6-E：现读表前缀，不在构造期快照。
     *
     * <p>前缀可能被 {@code DatabaseManager.initialize()} 在重连时改写，构造期快照会让
     * 本类与 {@code DatabaseManager} 自身的建表/写入路径指向两套不同的表（详见
     * {@code ReportRepository} 类 javadoc 的"生命周期"段）。</p>
     */
    private String prefix() {
        return db.getTablePrefix();
    }

    /**
     * 供调用方传入 {@link #findRecentDays(UUID, String)} 的"全部会话日"SQL 片段。
     * 保持"调用方传 SQL、仓储只绑参"的原设计（不拆成两个方法）。
     *
     * <p>R6-E：不在构造期预拼——前缀是运行期可变值，必须现拼，否则与 {@code prefix()}
     * 的现读纪律自相矛盾（重连换前缀后本方法会返回指向旧表的 SQL）。</p>
     */
    public String sessionDaysSql() {
        return "SELECT session_date FROM " + prefix()
                + "player_sessions WHERE uuid = ? ORDER BY session_date DESC LIMIT 40";
    }

    /**
     * 供调用方传入 {@link #findRecentDays(UUID, String)} 的"复习日"SQL 片段。
     *
     * <p>R6-E：同 {@link #sessionDaysSql()}，现拼不预拼。</p>
     */
    public String reviewDaysSql() {
        return "SELECT session_date FROM " + prefix()
                + "player_sessions WHERE uuid = ? AND review_words > 0 "
                + "ORDER BY session_date DESC LIMIT 40";
    }

    /**
     * 读取玩家已解锁成就（achievement_id → unlocked_at 毫秒）。
     *
     * <p>原出处：{@code AchievementManager.loadPlayerAchievements} :79-96
     * （{@code SELECT achievement_id, unlocked_at FROM <prefix>achievements WHERE uuid = ?}）。</p>
     *
     * <p><b>失败降级（round5 L-01）</b>：SQL 异常时记 WARNING 并返回"已读到的部分"，
     * 绝不返回 {@code null}（维持调用方"缓存非 null"不变量）。<b>刻意不抛</b>：调用链
     * 全段无 catch，抛出会中断会话结算。部分 Map 被当权威缓存的后果已由
     * {@link #upsertUnlock} 的"撞键 no-op"消除，仅剩重复解锁通知（体验问题，非数据损失）。</p>
     */
    /**
     * 读该玩家已解锁的成就。
     *
     * <p><b>slice-12-logic-L-01（round1 遗留 MEDIUM，round10 裁决实施 A6-1）：
     * 读失败返回 {@code null}，与「确实没有解锁任何成就」（空 Map）严格区分。</b>
     *
     * <p>原实现读失败时返回**空 Map**，与「新玩家」同形。上游
     * {@code AchievementManager.loadPlayerAchievements} 无条件把它落进缓存，随后
     * {@code tryUnlock} 的 {@code contains} 恒为 false → 已解锁的成就被**重新解锁**、
     * **重新弹通知**，动态计数也随之虚增。
     *
     * <p>改为 fail-closed：{@code null} 表示「不知道」，调用方必须据此**不落缓存**、
     * **不放行解锁**。真值仍在库里，下次会话结算会重新触发 {@code tryUnlock}，
     * 因此**不会丢失解锁**，只是延后到能读到库的那一刻。
     *
     * @return 已解锁映射；{@code null} = 读失败（不是「没有成就」）
     */
    public Map<String, Long> findUnlocked(UUID playerId) {
        Map<String, Long> result = new LinkedHashMap<>();
        String sql = "SELECT achievement_id, unlocked_at FROM "
                + prefix() + "achievements WHERE uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("achievement_id"), rs.getLong("unlocked_at"));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to load achievements for " + playerId
                                + " [sql=findUnlocked]", e);
                // A6-1：返回 null（「不知道」），不是空 Map（「没有」）。
                // 调用方据此不落缓存、不放行解锁 —— 否则已解锁的成就会被重新解锁并重复通知。
                return null;
            }
        }
        return result;
    }

    /**
     * 写入一条解锁记录（幂等 UPSERT）。
     *
     * <p>原出处：{@code AchievementManager.tryUnlock} :211-231。方言分支原样搬入：
     * SQLite 用 {@code INSERT OR IGNORE}，MySQL 用
     * {@code INSERT ... ON DUPLICATE KEY UPDATE achievement_id=achievement_id}（自赋值 no-op，
     * 与 {@code DailyChallengeRepository.assignAll} 同款写法），唯一约束 (uuid, achievement_id)
     * 兜底多服并发。<b>round5 L-01 修正</b>：撞键时<b>不再覆写</b> {@code unlocked_at}——
     * 原 {@code INSERT OR REPLACE} / {@code UPDATE unlocked_at=VALUES(...)} 会把首次解锁时间
     * 改写为当前时刻，污染 {@code ReportRepository} 的"本期间新解锁 N 个"窗口统计。
     * 首次解锁时间是事实，重放（{@code pendingUnlockWrites}）与重复写入都必须保留它。</p>
     *
     * <p>失败降级：只记 WARNING，不抛出（仅限 {@link java.sql.SQLException}，见类 javadoc）—— 内存已先写入（调用方的判定顺序为
     * "写内存 → 写库"），写库失败不得中断解锁链与后续通知。</p>
     *
     * <p><b>R4-DC-02（round6）：返回 boolean 成败。</b>原实现返回 {@code void}，而
     * {@code AchievementManager.flushPendingUnlockWrites} 只能 {@code catch (RuntimeException)}
     * —— 本方法的 {@code catch (SQLException)} 把失败在仓储内部消化掉了，那个 catch
     * <b>永远不会被 SQL 写失败触发</b>。于是「待补写」被 {@code remove} 走却不会回填，
     * 一次 DB 抖动就让该玩家<b>全部</b>待补写成就静默丢弃，与修复前的最终后果完全相同。
     *
     * <p>返回 {@code false} 表示「这次没写进去，调用方应保留待补写记录」。
     * 撞键 no-op 是<b>成功</b>（行已在库里，语义已满足），返回 {@code true}。
     *
     * @return true = 已写入或已存在；false = 写库失败（调用方应重试）
     */
    public boolean upsertUnlock(UUID playerId, String achievementId, long unlockedAt) {
        // R6-E：方言分叉，两套 SQL 用同一个前缀快照拼装。
        // round5 L-01：撞键必须 no-op（保留原始 unlocked_at），不得覆写时间戳。
        String tablePrefix = prefix();
        String sql = "INSERT OR IGNORE INTO "
                + tablePrefix
                + "achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?)";
        if (!db.isSQLite()) {
            sql = "INSERT INTO " + tablePrefix
                    + "achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE achievement_id=achievement_id";
        }
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, achievementId);
                stmt.setLong(3, unlockedAt);
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to save achievement " + achievementId + " for " + playerId
                                + " [sql=upsertUnlock]", e);
                return false;
            }
        }
    }

    /**
     * 执行调用方给定的"最近会话日"查询，返回 {@code session_date} 列表（保持 SQL 的
     * DESC 顺序）。
     *
     * <p>原出处：{@code AchievementManager.fetchDaySet} :527-543，供
     * {@code fetchSessionDays}（全部会话）与 {@code fetchReviewDays}
     * （{@code review_words > 0}）共用。SQL 由调用方传入、仓储只绑定 1 个 uuid 参数
     * （保持原设计，不在此二次拼接任何输入）。</p>
     *
     * <p><b>失败降级（R6-E 修正）</b>：SQL 异常时<strong>静默</strong>返回"已读到的部分"
     * （首次失败即空列表），<strong>不记日志</strong>。本方法原实现的 catch 块是空块
     * （{@code AchievementManager.fetchDaySet} :527-543 的 {@code catch (SQLException e)
     * { return days; }}），搬迁时被误升成 WARNING——必须还原：本方法经
     * {@code checkDailyStreak} / {@code checkReviewStreak} / {@code checkStudyDays}
     * 在<strong>每次会话结算</strong>都会跑，DB 抖动时每次结算最多 2 条带完整堆栈的
     * WARNING，多人同时结算即刷屏。</p>
     */
    public List<String> findRecentDays(UUID playerId, String daySql) {
        List<String> days = new ArrayList<>();
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(daySql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        days.add(rs.getString("session_date"));
                    }
                }
            } catch (SQLException e) {
                // Silently return partial days on error (原实现语义，勿加日志——见 javadoc)
                // 刻意不接 RuntimeException：连接层故障必须冒泡（否则等于把故障变静默）。
                return days;
            }
        }
        return days;
    }

    /**
     * 统计玩家不同学习日总数（{@code COUNT(DISTINCT session_date)}）。
     *
     * <p>原出处：{@code AchievementManager.countDistinctStudyDays} :564-579。</p>
     *
     * <p><b>失败降级（R6-E 修正）</b>：SQL 异常时<strong>静默</strong>返回 0，
     * <strong>不记日志</strong>——原实现 catch 块即 {@code return 0;}，无任何日志。
     * 本方法经 {@code checkStudyDays} 在每次会话结算、经 {@code getProgressSnapshot}
     * 在每次打开成就墙时都会跑，记 WARNING 会刷屏。</p>
     */
    public int countDistinctStudyDays(UUID playerId) {
        String sql = "SELECT COUNT(DISTINCT session_date) FROM "
                + prefix()
                + "player_sessions WHERE uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                // Silently return 0 on error (原实现语义，勿加日志——见 javadoc)
                // 刻意不接 RuntimeException：连接层故障必须冒泡。
                return 0;
            }
        }
    }
}
