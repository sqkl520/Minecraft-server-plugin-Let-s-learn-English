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
     * <p>失败降级：SQL 异常时记 WARNING 并返回"已读到的部分"（首次失败即空表），
     * 绝不返回 {@code null} —— 调用方据此维持"缓存非 null"不变量。</p>
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
                return result;
            }
        }
        return result;
    }

    /**
     * 写入一条解锁记录（幂等 UPSERT）。
     *
     * <p>原出处：{@code AchievementManager.tryUnlock} :211-231。方言分支原样搬入：
     * SQLite 用 {@code INSERT OR REPLACE}，MySQL 用
     * {@code INSERT ... ON DUPLICATE KEY UPDATE unlocked_at=VALUES(unlocked_at)}
     * （唯一约束 (uuid, achievement_id) 兜底多服并发）。</p>
     *
     * <p>失败降级：只记 WARNING，不抛出 —— 内存已先写入（调用方的判定顺序为
     * "写内存 → 写库"），写库失败不得中断解锁链与后续通知。</p>
     */
    public void upsertUnlock(UUID playerId, String achievementId, long unlockedAt) {
        // R6-E：方言分叉，两套 SQL 用同一个前缀快照拼装。
        String tablePrefix = prefix();
        String sql = "INSERT OR REPLACE INTO "
                + tablePrefix
                + "achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?)";
        if (!db.isSQLite()) {
            sql = "INSERT INTO " + tablePrefix
                    + "achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE unlocked_at=VALUES(unlocked_at)";
        }
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, achievementId);
                stmt.setLong(3, unlockedAt);
                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to save achievement " + achievementId + " for " + playerId
                                + " [sql=upsertUnlock]", e);
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
                return 0;
            }
        }
    }
}
