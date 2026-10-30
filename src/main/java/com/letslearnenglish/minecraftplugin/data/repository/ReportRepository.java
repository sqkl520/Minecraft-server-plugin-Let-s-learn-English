package com.letslearnenglish.minecraftplugin.data.repository;

import com.letslearnenglish.minecraftplugin.data.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Report Generator 的只读查询仓储（域 6 家长/老师学习报告）。
 *
 * <p>本类只搬 SQL 与参数绑定；日期边界/时区换算、业务判定、异常语义仍留在调用方。
 * 具体地：SQLite 的"本地日 → UTC 区间"换算与 MySQL 的 23:59:59 上界仍由
 * {@code ReportGenerator} 判定后以字符串边界传入；在线快照覆盖、隐私开关的
 * 业务含义、脏 UUID 跳过与名字回落等判定仍留在 {@code ReportGenerator}。
 *
 * <p>锁纪律（E-04 正确范式）：所有方法在 {@code synchronized (connectionLock)} 内
 * 取连接并执行语句——连接引用不跨出锁，消除"锁外取连接、锁内执行"的 stale-ref
 * 隐患。方法内部不调用 {@code setAutoCommit}（连接级状态，会破坏外层事务）。
 *
 * <p>异常语义：读失败按调用方既有契约返回默认值（{@code null} / 空列表 / 0 /
 * false）并记 WARNING，绝不向调用方抛异常。本类只读，无任何写路径。
 *
 * <p>线程模型：方法同步阻塞在调用线程；调用方决定线程（{@code ReportGenerator}
 * 全部在异步线程调用），本类不新增线程切换。
 *
 * <p>生命周期（R6-E）：表前缀<b>不</b>在构造时快照，而是每次拼 SQL 时现读
 * {@link DatabaseManager#getTablePrefix()}。理由：{@link DatabaseManager} 的
 * {@code tablePrefix} 不是 final 的，{@code initialize()} 每次重连都按当前配置重读；若本类在构造期快照，
 * 运维改了 {@code database.mysql.table-prefix} 后等重连自愈，{@code initialize()} 会用
 * 新前缀建出一套<b>新的空表</b>，而本类仍在拼旧前缀——读写全落旧表，形成"新旧两套表
 * 同时活着、各写一半"的静默数据分裂。现读与重构前 HEAD 的行为一致。
 * 调用方仍须在 {@code initialize()} 之后构造本类（{@code ReportGenerator} 在插件
 * 启动装配阶段构造，满足该前提）。
 */
public class ReportRepository {

    private final DatabaseManager db;
    private final Logger logger;

    /**
     * @param db     数据库管理器（连接、连接锁、表前缀的唯一来源）
     * @param logger 插件日志器（读失败降级时记 WARNING；调用方传 {@code plugin.getLogger()}）
     */
    public ReportRepository(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /**
     * R6-E：现读表前缀，不在构造期快照。
     *
     * <p>前缀可能被 {@code DatabaseManager.initialize()} 在重连时改写（配置 reload 或
     * 连接自愈），快照会让本类与 {@code DatabaseManager} 自身的建表/写入路径指向两套
     * 不同的表。见类 javadoc 的"生命周期"段。
     */
    private String prefix() {
        return db.getTablePrefix();
    }

    // ============================================================
    //  结果类型（与调用方既有期望形状一致，不做二次转换）
    // ============================================================

    /** Q2：player_data 终身汇总 + 库内名字。无行或读失败返回 {@code null}。 */
    public record LifetimeRow(String playerName, long totalCorrect, long sessions,
                              long score, int conversations) {}

    /** Q3：成就总数 + 窗口内新解锁数。无行或读失败返回 {@code null}。 */
    public record AchievementCounts(int total, int recent) {}

    /** Q8：窗口内每日挑战完成行数 / 总行数。无行或读失败返回 {@code null}。 */
    public record ChallengeProgress(int done, int total) {}

    /** Q1：player_sessions 的逐日行（UNIQUE(uuid, session_date) 保证一天一行）。 */
    public record DailyRow(LocalDate date, int answered, int correct, long score) {}

    /** Q5：difficult_words 原始行（中文义/难度由调用方查内存词库补充）。 */
    public record WeakWordRow(String word, int wrongCount) {}

    /**
     * Q9：player_sessions 批量聚合原始行。uuid / player_name 保持库内原样，
     * UUID 解析、脏行跳过与名字回落由调用方判定。
     */
    public record BatchStatRow(String uuid, String playerName, int activeDays,
                               int answered, int correct, long score) {}

    // ============================================================
    //  单报查询
    // ============================================================

    /** Q2：终身汇总 + 库内名字（player_data 按 uuid）。 */
    public LifetimeRow fetchLifetime(UUID playerId) {
        String sql = "SELECT player_name, total_words_learned, total_sessions, total_score,"
                + " conversation_count FROM " + prefix() + "player_data WHERE uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new LifetimeRow(
                                rs.getString("player_name"),
                                rs.getLong("total_words_learned"),
                                rs.getLong("total_sessions"),
                                rs.getLong("total_score"),
                                rs.getInt("conversation_count"));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query player_data for report: " + playerId, e);
            }
        }
        return null;
    }

    /**
     * Q3：成就总数 + 窗口内新解锁（{@code unlocked_at} 为毫秒长整）。
     *
     * @param windowStart        窗口起点（含），epoch 毫秒
     * @param windowEndExclusive 窗口终点（不含），epoch 毫秒
     */
    public AchievementCounts countAchievements(UUID playerId, long windowStart,
                                               long windowEndExclusive) {
        String sql = "SELECT COUNT(*) AS total,"
                + " SUM(CASE WHEN unlocked_at >= ? AND unlocked_at < ? THEN 1 ELSE 0 END) AS recent"
                + " FROM " + prefix() + "achievements WHERE uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setLong(1, windowStart);
                stmt.setLong(2, windowEndExclusive);
                stmt.setString(3, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new AchievementCounts(rs.getInt("total"), rs.getInt("recent"));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query achievements for report: " + playerId, e);
            }
        }
        return null;
    }

    /**
     * Q4：窗口内完成的 AI 对话会话数。边界字符串由调用方按后端换算：
     * SQLite 传 UTC 区间并置 {@code exclusiveUpper=true}（{@code < ?}），
     * MySQL 传本地 23:59:59 上界并置 {@code exclusiveUpper=false}（{@code <= ?}）。
     * 两套完整 SQL 由布尔判定选择，不做 SQL 片段拼接。
     */
    public int countAiConversations(UUID playerId, String lowerBound, String upperBound,
                                    boolean exclusiveUpper) {
        // R6-E：本方法要拼两套完整 SQL，先取一次局部变量，避免两条语句读到不同的前缀。
        String tablePrefix = prefix();
        String sqlExclusive = "SELECT COUNT(DISTINCT session_id) FROM " + tablePrefix
                + "ai_chat_history WHERE player_uuid = ? AND timestamp >= ? AND timestamp < ?";
        String sqlInclusive = "SELECT COUNT(DISTINCT session_id) FROM " + tablePrefix
                + "ai_chat_history WHERE player_uuid = ? AND timestamp >= ? AND timestamp <= ?";
        String sql = exclusiveUpper ? sqlExclusive : sqlInclusive;
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, lowerBound);
                stmt.setString(3, upperBound);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query AI chat count for report: " + playerId, e);
            }
        }
        return 0;
    }

    /** Q6：当前连续签到天数（checkin_data.streak_count）。无行或读失败返回 0。 */
    public int fetchCheckinStreak(UUID playerId) {
        String sql = "SELECT streak_count FROM " + prefix()
                + "checkin_data WHERE player_uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("streak_count");
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query check-in streak for report: " + playerId, e);
            }
        }
        return 0;
    }

    /**
     * Q8：窗口内每日挑战完成度（完成行数 / 总行数）。
     *
     * @param dateFrom 窗口起始日（yyyy-MM-dd，含）
     * @param dateTo   窗口结束日（yyyy-MM-dd，含）
     */
    public ChallengeProgress countDailyChallenges(UUID playerId, String dateFrom, String dateTo) {
        String sql = "SELECT SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) AS done,"
                + " COUNT(*) AS total FROM " + prefix() + "daily_challenges"
                + " WHERE uuid = ? AND challenge_date >= ? AND challenge_date <= ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, dateFrom);
                stmt.setString(3, dateTo);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new ChallengeProgress(rs.getInt("done"), rs.getInt("total"));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query daily challenges for report: " + playerId, e);
            }
        }
        return null;
    }

    // ============================================================
    //  单报列表查询
    // ============================================================

    /**
     * Q1：窗口逐日会话行。
     *
     * <p>行映射（{@code session_date} 字符串 → {@link LocalDate}）沿用原实现：
     * 解析失败与原 SQL 失败同口径——中止本查询、返回已累积行并记 WARNING
     * （不向调用方抛异常）。
     */
    public List<DailyRow> queryDailyRows(UUID playerId, String dateFrom, String dateTo) {
        List<DailyRow> rows = new ArrayList<>();
        String sql = "SELECT session_date, words_count, correct_count, score FROM " + prefix()
                + "player_sessions WHERE uuid = ? AND session_date >= ? AND session_date <= ?"
                + " ORDER BY session_date ASC";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, dateFrom);
                stmt.setString(3, dateTo);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new DailyRow(LocalDate.parse(rs.getString("session_date")),
                                rs.getInt("words_count"), rs.getInt("correct_count"),
                                rs.getLong("score")));
                    }
                }
            } catch (SQLException | DateTimeParseException e) {
                logger.log(Level.WARNING,
                        "Failed to query session rows for report: " + playerId, e);
            }
        }
        return rows;
    }

    /**
     * Q5：当前错题本 Top N（按错误次数降序）。只返回库内原始列，
     * 中文义与难度由调用方查内存词库补充。
     */
    public List<WeakWordRow> queryWeakWords(UUID playerId, int limit) {
        List<WeakWordRow> out = new ArrayList<>();
        String sql = "SELECT word, wrong_count FROM " + prefix() + "difficult_words"
                + " WHERE player_uuid = ? ORDER BY wrong_count DESC, word ASC LIMIT ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setInt(2, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        out.add(new WeakWordRow(rs.getString("word"), rs.getInt("wrong_count")));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query weak words for report: " + playerId, e);
            }
        }
        return out;
    }

    // ============================================================
    //  批量查询 / 隐私
    // ============================================================

    /**
     * Q9：窗口内有 player_sessions 行的玩家汇总（按答对数降序）。
     *
     * <p>{@code respectPrivacy=true} 时按与榜单/广场同款口径 LEFT JOIN
     * {@code player_profiles} 并过滤 {@code COALESCE(show_in_square,1)<>0}。
     * 传 {@code limit = batchMax + 1} 供调用方探测截断；MAX(player_name) 包法
     * 兼容 MySQL ONLY_FULL_GROUP_BY（沿 R-05/周榜惯例）。
     *
     * @param limit 绑定到 LIMIT 的行数上限（调用方传 batchMax + 1）
     */
    public List<BatchStatRow> queryBatchStats(String dateFrom, String dateTo, int limit,
                                              boolean respectPrivacy) {
        List<BatchStatRow> rows = new ArrayList<>();
        // R6-E：本方法要拼 3~4 处前缀，先取一次局部变量，避免同一 SQL 内读到两个不同的前缀。
        String tablePrefix = prefix();
        String sql = "SELECT s.uuid AS uuid, MAX(p.player_name) AS player_name,"
                + " COUNT(*) AS active_days,"
                + " COALESCE(SUM(s.words_count), 0) AS total_answered,"
                + " COALESCE(SUM(s.correct_count), 0) AS total_correct,"
                + " COALESCE(SUM(s.score), 0) AS total_score"
                + " FROM " + tablePrefix + "player_sessions s"
                + " LEFT JOIN " + tablePrefix + "player_data p ON p.uuid = s.uuid";
        if (respectPrivacy) {
            sql += " LEFT JOIN " + tablePrefix + "player_profiles pf ON pf.uuid = s.uuid";
        }
        sql += " WHERE s.session_date >= ? AND s.session_date <= ?";
        if (respectPrivacy) {
            sql += " AND COALESCE(pf.show_in_square, 1) <> 0";
        }
        sql += " GROUP BY s.uuid ORDER BY total_correct DESC, s.uuid ASC LIMIT ?";

        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, dateFrom);
                stmt.setString(2, dateTo);
                stmt.setInt(3, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new BatchStatRow(
                                rs.getString("uuid"),
                                rs.getString("player_name"),
                                rs.getInt("active_days"),
                                rs.getInt("total_answered"),
                                rs.getInt("total_correct"),
                                rs.getLong("total_score")));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to query batch report rows", e);
            }
        }
        return rows;
    }

    /**
     * 单报隐私检查：读 player_profiles.show_in_square。
     * 无行 = 从未改过开关 = 默认可见（返回 false）。
     */
    public boolean isHiddenInSquare(UUID playerId) {
        String sql = "SELECT show_in_square FROM " + prefix()
                + "player_profiles WHERE uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return !rs.getBoolean("show_in_square");
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to check privacy flag for report: " + playerId, e);
            }
        }
        return false;
    }
}
