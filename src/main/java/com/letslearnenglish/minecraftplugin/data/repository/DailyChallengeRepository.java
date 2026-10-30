package com.letslearnenglish.minecraftplugin.data.repository;

import com.letslearnenglish.minecraftplugin.data.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 仓储：{@code daily_challenges} / {@code daily_challenge_words} 的 JDBC 访问。
 *
 * <p>纯机械搬家自 {@code DailyChallengeManager} 的裸 JDBC（见各方法 Javadoc 的
 * 原出口行号）。此处的唯一职责是 SQL 与参数绑定；<strong>不含任何业务判定</strong>
 * （挑战可用性、进度 clamp、奖励金额、stripe lock 均留在调用方）。</p>
 *
 * <p>R6-E：原 {@code updateProgress} 的裸 UPDATE 已<b>删除</b>——调用方
 * {@code DailyChallengeManager.updateProgress} 在 R7-B 后改走
 * {@link DatabaseManager#advanceChallengeProgress}（单条 CASE UPDATE），该方法是
 * 搬迁遗留的死代码。行为挑战的进度推进现由 DatabaseManager 的合并语句承担。</p>
 *
 * <p><strong>锁纪律</strong>：全部方法统一"<em>锁内取连接</em>"——
 * {@code synchronized (db.getConnectionLock()) { db.getConnection().prepareStatement(...) }}，
 * 避免锁外取引用跨越 {@link DatabaseManager#initialize()} 重连窗口拿到陈旧句柄
 * （R6-L1 / R3-H3 范式）。仓储<strong>只</strong>取 connectionLock；per-player 的
 * stripe lock 永远由调用方在更外层持有（{@code DailyChallengeManager.checkAndAssign}
 * 与 {@code claimReward} 的 L→M 嵌套顺序不得反转，否则与领取路径交叉死锁）。
 * 本类不调用 {@code setAutoCommit}。</p>
 *
 * <p><strong>失败降级</strong>：读失败按原实现降级——{@code findState} 返回 {@code null}、
 * {@code findTodayChallenges} 返回已读到的行（异常在首行前即为空列表）、
 * {@code countUnclaimed} 返回 {@code 0}、{@code isWordCompleted} 返回 {@code false}
 * 且不记日志（原 {@code // ignore}）；{@link #countAssigned} 额外用 {@code -1} 区分
 * "读失败"与"未分配"。写失败只记 WARNING 不抛出——与原 {@code DailyChallengeManager}
 * 的异常语义逐条一致。</p>
 *
 * <p><strong>构造前置条件（R6-E）</strong>：{@link DatabaseManager#initialize()} 必须已执行完毕
 * （{@code LetsLearnEnglish} 在构造 {@code DailyChallengeManager} 之前已完成初始化）。
 * 但表前缀<b>不</b>在构造时缓存，而是每次拼 SQL 时现读
 * {@link DatabaseManager#getTablePrefix()}——{@code tablePrefix} 是可变字段，
 * {@code initialize()} 在重连自愈时会按当前配置重写；构造期快照会让本类与
 * {@code DatabaseManager} 自身的写入路径指向两套不同的表（详见
 * {@code ReportRepository} 类 javadoc 的"生命周期"段）。
 */
public class DailyChallengeRepository {

    /**
     * 今日行为挑战的一行原始状态（{@code daily_challenges} 的读取投影）。
     * 只搬运列值，不做任何 clamp / 枚举解析（那是调用方的业务）。
     *
     * @param challengeId {@code challenge_id} 列原值（调用方再映射到 ChallengeType）
     * @param progress    {@code progress} 列原值（未 clamp）
     * @param completed   {@code completed} 列
     * @param claimed     {@code claimed} 列
     */
    public record ChallengeRow(String challengeId, int progress, boolean completed, boolean claimed) {
    }

    private final DatabaseManager db;
    private final Logger logger;

    /**
     * @param db     数据库管理器（连接、连接锁、方言判定的唯一来源）
     * @param logger 插件 logger（沿用原实现的日志出口与文案，便于运维检索）
     */
    public DailyChallengeRepository(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /**
     * R6-E：现读表前缀，不在构造期快照（见类 javadoc 的"构造前置条件"段）。
     */
    private String prefix() {
        return db.getTablePrefix();
    }

    // ============================================================
    //  Behavioral challenges
    // ============================================================

    /**
     * 今日是否已为该玩家分配过行为挑战（行数）。
     *
     * <p>原 SQL 出处：{@code DailyChallengeManager:169}（{@code doCheckAndAssign} 的
     * {@code SELECT COUNT(*) ... WHERE uuid = ? AND challenge_date = ?}）。</p>
     *
     * @param playerId 玩家
     * @param date     {@code yyyy-MM-dd}
     * @return 行数；<strong>读失败返回 {@code -1}</strong>（原实现在此记 WARNING 后直接
     *         return、不再尝试分配；调用方须以 {@code < 0} 区分"读失败"与"未分配 0"）
     */
    public int countAssigned(UUID playerId, String date) {
        String sql = "SELECT COUNT(*) FROM " + prefix()
                + "daily_challenges WHERE uuid = ? AND challenge_date = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to check daily challenges for " + playerId, e);
                return -1;
            }
        }
        return 0;
    }

    /**
     * 批量分配今日行为挑战（一条 INSERT 语句 + executeBatch）。
     *
     * <p>原 SQL 出处：{@code DailyChallengeManager:235}（{@code doCheckAndAssign} 的
     * 批量 INSERT；方言分支原在 :222）。方言分叉原样保留：</p>
     * <ul>
     *   <li>SQLite：{@code INSERT OR REPLACE}（撞键覆盖）</li>
     *   <li>MySQL：{@code INSERT ... ON DUPLICATE KEY UPDATE challenge_id = challenge_id}
     *       —— F2-01：双路径（join 与 {@code DailyChallengeManager.updateProgress}
     *       的 R-32 补分配）并发时第二次 INSERT
     *       撞主键 {@code (uuid, challenge_date, challenge_id)} 不再抛 SQLException 记
     *       WARNING，而是 no-op 忽略（自赋值为 MySQL 8 合法写法）。撞键时不动既有行，
     *       比 SQLite 分支更保守：不会把已积累的 progress/completed 重置。正常路径有外层
     *       COUNT 预检，本就不会撞键，此保护仅兜住并发窗口。</li>
     * </ul>
     *
     * <p>写失败只记 WARNING，不抛出、不回滚（与原实现一致）。</p>
     *
     * @param playerId     玩家
     * @param date         {@code yyyy-MM-dd}
     * @param challengeIds 本批要分配的 {@code challenge_id}（调用方已按 N 抽样）
     */
    public void assignAll(UUID playerId, String date, List<String> challengeIds) {
        // R6-E：本方法有方言分叉，两套 SQL 必须用同一个前缀快照拼装。
        String tablePrefix = prefix();
        String insertSql = "INSERT OR REPLACE INTO " + tablePrefix
                + "daily_challenges (uuid, challenge_date, challenge_id, progress, completed, claimed) VALUES (?, ?, ?, 0, 0, 0)";
        if (!db.isSQLite()) {
            insertSql = "INSERT INTO " + tablePrefix
                    + "daily_challenges (uuid, challenge_date, challenge_id, progress, completed, claimed) VALUES (?, ?, ?, 0, 0, 0) "
                    + "ON DUPLICATE KEY UPDATE challenge_id = challenge_id";
        }
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(insertSql)) {
                for (String challengeId : challengeIds) {
                    stmt.setString(1, playerId.toString());
                    stmt.setString(2, date);
                    stmt.setString(3, challengeId);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to assign daily challenges for " + playerId, e);
            }
        }
    }

    /**
     * 读取今日行为挑战列表（按 {@code challenge_id} 固定排序）。
     *
     * <p>原 SQL 出处：{@code DailyChallengeManager:331}（{@code getTodayChallenges}）。
     * R-33：{@code ORDER BY challenge_id} 必须保留——展示（DailyChallengeGUI 的
     * CHALLENGE_SLOTS 槽位分配）与领取（MiscGUIListener 按返回列表下标查位映射）两侧
     * 都走此查询，排序固定后下标与展示恒一致，不再因 SQLite 返回顺序不定而领到错项。</p>
     *
     * <p>返回的是未加工的列值（progress 未 clamp，challenge_id 未映射枚举）。</p>
     *
     * @param playerId 玩家
     * @param date     {@code yyyy-MM-dd}
     * @return 行列表；<strong>读失败记 WARNING 并返回已收集到的行</strong>（与原实现
     *         直接返回调用方那个 list 完全一致——异常发生在首行之前时即为空列表）
     */
    public List<ChallengeRow> findTodayChallenges(UUID playerId, String date) {
        String sql = "SELECT challenge_id, progress, completed, claimed FROM " + prefix()
                + "daily_challenges WHERE uuid = ? AND challenge_date = ? ORDER BY challenge_id";
        List<ChallengeRow> rows = new ArrayList<>();
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new ChallengeRow(
                                rs.getString("challenge_id"),
                                rs.getInt("progress"),
                                rs.getBoolean("completed"),
                                rs.getBoolean("claimed")));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to load daily challenges for " + playerId, e);
            }
        }
        return rows;
    }

    /**
     * R-21：把某玩家某日指定挑战中"既未完成也未领取"的行直接标记为 completed+claimed
     * （自动放弃，不发奖励）。SQL 只动 {@code completed = 0 AND claimed = 0} 的行，
     * 已完成未领取（completed=1、claimed=0）的行保留其应得奖励。
     *
     * <p>原 SQL 出处：{@code DailyChallengeManager:382}（{@code degradeStaleChallenges}）。</p>
     *
     * @param playerId    玩家
     * @param date        {@code yyyy-MM-dd}
     * @param challengeId 要降级的挑战 id（调用方传 AI_CHAT_5）
     * @return 受影响行数；<strong>写失败返回 {@code 0}</strong>（记 WARNING，不抛出——
     *         与原实现"异常只 WARNING、不打印 Degraded info"等价）
     */
    public int degradeStale(UUID playerId, String date, String challengeId) {
        String sql = "UPDATE " + prefix()
                + "daily_challenges SET completed = 1, claimed = 1 "
                + "WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? "
                + "AND completed = 0 AND claimed = 0";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.setString(3, challengeId);
                return stmt.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to degrade stale challenges for " + playerId, e);
                return 0;
            }
        }
    }

    /**
     * 读取某挑战当日行的 {progress, completed(0/1), claimed(0/1)}。
     *
     * <p>原 SQL 出处：{@code DailyChallengeManager:471}（{@code loadChallengeState}）。</p>
     *
     * @param playerId    玩家
     * @param date        {@code yyyy-MM-dd}
     * @param challengeId 挑战 id
     * @return 长度 3 的 int 数组；<strong>无行或读失败均返回 {@code null}</strong>
     *         （原实现两者同一返回值，调用方据此触发 R-32 补分配）
     */
    public int[] findState(UUID playerId, String date, String challengeId) {
        String sql = "SELECT progress, completed, claimed FROM " + prefix()
                + "daily_challenges WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.setString(3, challengeId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new int[]{
                                rs.getInt("progress"),
                                rs.getBoolean("completed") ? 1 : 0,
                                rs.getBoolean("claimed") ? 1 : 0
                        };
                    }
                    return null;
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to load daily challenge state for " + playerId, e);
                return null;
            }
        }
    }

    /**
     * 二进制完成（如连击）→ {@code completed = 1}，但仅当尚未领取。
     *
     * <p>原 SQL 出处：{@code DailyChallengeManager:507}（{@code completeChallenge}）。</p>
     *
     * <p>写失败只记 WARNING（与原实现一致）。</p>
     *
     * @param playerId    玩家
     * @param date        {@code yyyy-MM-dd}
     * @param challengeId 挑战 id
     */
    public void complete(UUID playerId, String date, String challengeId) {
        String sql = "UPDATE " + prefix()
                + "daily_challenges SET completed = 1 WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? AND claimed = 0";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.setString(3, challengeId);
                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to complete daily challenge", e);
            }
        }
    }

    /**
     * 标记已领取（带护栏 {@code completed = 1 AND claimed = 0}，幂等）。
     *
     * <p>原 SQL 出处：{@code DailyChallengeManager:556}（{@code markClaimed}）。
     * 调用方必须<strong>先完成 Vault 转账再调用</strong>（M12 deposit-then-mark），
     * 本方法只负责写台账位。</p>
     *
     * @param playerId    玩家
     * @param date        {@code yyyy-MM-dd}
     * @param challengeId 挑战 id
     * @return true 仅当本次真的标记了一行（{@code rows > 0}）；<strong>写失败返回
     *         {@code false}</strong>（记 WARNING，与原实现 {@code return 0} 等价）
     */
    public boolean markClaimed(UUID playerId, String date, String challengeId) {
        String sql = "UPDATE " + prefix()
                + "daily_challenges SET claimed = 1 WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? AND completed = 1 AND claimed = 0";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.setString(3, challengeId);
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to claim daily challenge reward", e);
                return false;
            }
        }
    }

    // ============================================================
    //  Word-bank-based daily challenges
    // ============================================================

    /**
     * 单个挑战单词是否已完成。
     *
     * <p>原 SQL 出处：{@code DailyChallengeManager:863}（{@code checkSingleWordCompleted}）。</p>
     *
     * @param playerId 玩家
     * @param date     {@code yyyy-MM-dd}
     * @param wordId   单词 id
     * @return 完成位；<strong>无行或读失败返回 {@code false} 且不记日志</strong>
     *         （原实现 catch 块即 {@code // ignore}，此处逐字保留静默语义）
     */
    public boolean isWordCompleted(UUID playerId, String date, String wordId) {
        String sql = "SELECT completed FROM " + prefix()
                + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ? AND word_id = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.setString(3, wordId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBoolean("completed");
                    }
                }
            } catch (SQLException e) {
                // ignore
            }
        }
        return false;
    }

    /**
     * 统计某玩家某日"已完成但未领取"的行为挑战数（M8 跨天作废提示用）。
     *
     * <p>原 SQL 出处：{@code DailyChallengeManager:901}（{@code countUnclaimedForDate}）。</p>
     *
     * @param playerId 玩家
     * @param date     {@code yyyy-MM-dd}
     * @return 计数；<strong>读失败返回 {@code 0}</strong>（记 WARNING，与原实现一致）
     */
    public int countUnclaimed(UUID playerId, String date) {
        String sql = "SELECT COUNT(*) FROM " + prefix()
                + "daily_challenges WHERE uuid = ? AND challenge_date = ? AND completed = 1 AND claimed = 0";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to count unclaimed challenges for " + playerId, e);
            }
        }
        return 0;
    }
}
