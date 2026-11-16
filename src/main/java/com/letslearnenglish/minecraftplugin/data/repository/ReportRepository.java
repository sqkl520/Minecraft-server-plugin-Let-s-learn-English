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
 * <p>异常语义：读失败按调用方契约返回默认值并记 WARNING。本类只读，无写路径。
 * <b>"不抛异常"只覆盖 {@link SQLException}</b>：连接层 {@link RuntimeException} 刻意
 * <b>不</b>在此吞——它须冒泡到 {@code report.failed}，否则"库挂了"会被渲染成
 * "该玩家没有学习数据"。
 *
 * <p><b>完整性位（round4 F3 / EH-12-01）</b>：8 个数据查询方法（Q1~Q9）都提供
 * {@code *OrFail} 变体，返回带 {@code complete} 位的记录；旧签名（裸 {@code null} /
 * {@code 0} / 空 list）保留仅为编译期兼容，<b>新调用方一律走 {@code *OrFail}</b>。
 * 理由：把"读失败"折叠成 {@code null}/{@code 0}/空 list 后，下游无法区分"确实没有数据"
 * 与"库挂了"，会把后者渲染成对家长的事实性谎报（"本期间没有学习数据"）。
 *
 * <p>{@link #isHiddenInSquare}（隐私开关读）不走 {@code complete} 位，而是
 * <b>round5 L-01 起 fail-closed</b>：读失败直接抛出，不再 {@code return false}——
 * 它失败时的危险方向与上述相反，是"多报"（把隐身玩家当公开），必须由调用方的
 * 异常分支截住。
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

    /**
     * Q2 + 完整性位（round4 F3 / EH-01）：{@code complete=false} = SQL 失败，
     * {@code row} 为 {@code null} 是"失败"而非"该玩家没有数据"。调用方<b>不得</b>
     * 把失败渲染成 0。
     */
    public record LifetimeRows(LifetimeRow row, boolean complete) {}

    /** Q3：成就总数 + 窗口内新解锁数。聚合恒返回一行，{@code null} 只表示读失败。 */
    public record AchievementCounts(int total, int recent) {}

    /** Q3 + 完整性位：{@code complete=false} = 读失败，{@code counts} 为 {@code null}。 */
    public record AchievementRows(AchievementCounts counts, boolean complete) {}

    /** Q8：窗口内每日挑战完成行数 / 总行数。{@code null} 只表示读失败。 */
    public record ChallengeProgress(int done, int total) {}

    /** Q8 + 完整性位：{@code complete=false} = 读失败。 */
    public record ChallengeRows(ChallengeProgress progress, boolean complete) {}

    /** Q4 + 完整性位：{@code value} 仅当 {@code complete=true} 才是真实计数。 */
    public record ConversationCount(int value, boolean complete) {}

    /** Q6 + 完整性位：{@code value} 仅当 {@code complete=true} 才是真实连续天数。 */
    public record StreakValue(int value, boolean complete) {}

    /** Q1：player_sessions 的逐日行（UNIQUE(uuid, session_date) 保证一天一行）。 */
    public record DailyRow(LocalDate date, int answered, int correct, long score) {}

    /** Q5：difficult_words 原始行（中文义/难度由调用方查内存词库补充）。 */
    public record WeakWordRow(String word, int wrongCount) {}

    /** Q5 + 完整性位：{@code complete=false} = 读失败，{@code rows} 是已累积的部分行。 */
    public record WeakWords(List<WeakWordRow> rows, boolean complete) {}

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
    public LifetimeRows fetchLifetimeOrFail(UUID playerId) {
        String sql = "SELECT player_name, total_words_learned, total_sessions, total_score,"
                + " conversation_count FROM " + prefix() + "player_data WHERE uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new LifetimeRows(new LifetimeRow(
                                rs.getString("player_name"),
                                rs.getLong("total_words_learned"),
                                rs.getLong("total_sessions"),
                                rs.getLong("total_score"),
                                rs.getInt("conversation_count")), true);
                    }
                    // 聚合查询无行 = 该 uuid 从未写过 player_data（真实"没有数据"）
                    return new LifetimeRows(null, true);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query player_data for report: " + playerId, e);
                return new LifetimeRows(null, false); // 失败：下游不得当 0
            }
        }
    }

    /** Q2 旧签名：失败与"无行"折叠为 {@code null}。需要分流者改用 {@link #fetchLifetimeOrFail}。 */
    public LifetimeRow fetchLifetime(UUID playerId) {
        return fetchLifetimeOrFail(playerId).row();
    }

    /**
     * Q3：成就总数 + 窗口内新解锁（{@code unlocked_at} 为毫秒长整）。
     *
     * @param windowStart        窗口起点（含），epoch 毫秒
     * @param windowEndExclusive 窗口终点（不含），epoch 毫秒
     */
    public AchievementRows countAchievementsOrFail(UUID playerId, long windowStart,
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
                        return new AchievementRows(
                                new AchievementCounts(rs.getInt("total"), rs.getInt("recent")), true);
                    }
                    return new AchievementRows(null, true);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query achievements for report: " + playerId, e);
                return new AchievementRows(null, false);
            }
        }
    }

    /** Q3 旧签名：失败与"无行"折叠为 {@code null}。需要分流者改用 {@link #countAchievementsOrFail}。 */
    public AchievementCounts countAchievements(UUID playerId, long windowStart,
                                               long windowEndExclusive) {
        return countAchievementsOrFail(playerId, windowStart, windowEndExclusive).counts();
    }

    /**
     * Q4：窗口内完成的 AI 对话会话数。边界字符串由调用方按后端换算：
     * SQLite 传 UTC 区间并置 {@code exclusiveUpper=true}（{@code < ?}），
     * MySQL 传本地 23:59:59 上界并置 {@code exclusiveUpper=false}（{@code <= ?}）。
     * 两套完整 SQL 由布尔判定选择，不做 SQL 片段拼接。
     */
    public ConversationCount countAiConversationsOrFail(UUID playerId, String lowerBound,
                                                        String upperBound, boolean exclusiveUpper) {
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
                        return new ConversationCount(rs.getInt(1), true);
                    }
                    return new ConversationCount(0, true);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query AI chat count for report: " + playerId, e);
                return new ConversationCount(0, false);
            }
        }
    }

    /** Q4 旧签名：失败折叠为 {@code 0}。需要分流者改用 {@link #countAiConversationsOrFail}。 */
    public int countAiConversations(UUID playerId, String lowerBound, String upperBound,
                                    boolean exclusiveUpper) {
        return countAiConversationsOrFail(playerId, lowerBound, upperBound, exclusiveUpper).value();
    }

    /** Q6：当前连续签到天数（checkin_data.streak_count）。无行或读失败返回 0。 */
    public StreakValue fetchCheckinStreakOrFail(UUID playerId) {
        String sql = "SELECT streak_count FROM " + prefix()
                + "checkin_data WHERE player_uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement stmt = db.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new StreakValue(rs.getInt("streak_count"), true);
                    }
                    return new StreakValue(0, true); // 无行 = 从未签到过，真实 0
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query check-in streak for report: " + playerId, e);
                return new StreakValue(0, false);
            }
        }
    }

    /** Q6 旧签名：失败折叠为 {@code 0}。需要分流者改用 {@link #fetchCheckinStreakOrFail}。 */
    public int fetchCheckinStreak(UUID playerId) {
        return fetchCheckinStreakOrFail(playerId).value();
    }

    /**
     * Q8：窗口内每日挑战完成度（完成行数 / 总行数）。
     *
     * @param dateFrom 窗口起始日（yyyy-MM-dd，含）
     * @param dateTo   窗口结束日（yyyy-MM-dd，含）
     */
    public ChallengeRows countDailyChallengesOrFail(UUID playerId, String dateFrom, String dateTo) {
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
                        return new ChallengeRows(
                                new ChallengeProgress(rs.getInt("done"), rs.getInt("total")), true);
                    }
                    return new ChallengeRows(null, true);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING,
                        "Failed to query daily challenges for report: " + playerId, e);
                return new ChallengeRows(null, false);
            }
        }
    }

    /** Q8 旧签名：失败与"无行"折叠为 {@code null}。需要分流者改用 {@link #countDailyChallengesOrFail}。 */
    public ChallengeProgress countDailyChallenges(UUID playerId, String dateFrom, String dateTo) {
        return countDailyChallengesOrFail(playerId, dateFrom, dateTo).progress();
    }

    // ============================================================
    //  单报列表查询
    // ============================================================

    /**
     * Q1：窗口逐日会话行 + 截断标记（dc-05）。{@code complete=false} 表示 SQL 或行映射阶段失败、
     * {@code rows} 只是已累积的部分行——下游据此区分"窗口内确实只有 N 天"与"第 k+1 天失败"。
     */
    public record DailyRows(List<DailyRow> rows, boolean complete) {}

    public DailyRows queryDailyRows(UUID playerId, String dateFrom, String dateTo) {
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
                return new DailyRows(rows, false); // 部分行：显式标不完整
            }
        }
        return new DailyRows(rows, true);
    }

    /**
     * Q5：当前错题本 Top N（按错误次数降序）。只返回库内原始列，
     * 中文义与难度由调用方查内存词库补充。
     */
    public WeakWords queryWeakWordsOrFail(UUID playerId, int limit) {
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
                return new WeakWords(out, false); // 部分行：下游不得当"没有错题"
            }
        }
        return new WeakWords(out, true);
    }

    /** Q5 旧签名：失败折叠为空 list（与"确实没有错题"不可区分）。需要分流者改用 {@link #queryWeakWordsOrFail}。 */
    public List<WeakWordRow> queryWeakWords(UUID playerId, int limit) {
        return queryWeakWordsOrFail(playerId, limit).rows();
    }

    // ============================================================
    //  批量查询 / 隐私
    // ============================================================

    /** Q9 + 完整性位：{@code complete=false} = 读失败/中途失败，{@code rows} 只是部分行，
     *  调用方<b>不得</b>据此宣布"本周期无人学习"（round4 F3 / EH-03）。 */
    public record BatchRows(List<BatchStatRow> rows, boolean complete) {}

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
    public BatchRows queryBatchStatsOrFail(String dateFrom, String dateTo, int limit,
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
                return new BatchRows(rows, false); // 部分行：下游不得当"无人学习"
            }
        }
        return new BatchRows(rows, true);
    }

    /** Q9 旧签名：失败与空集仍不可区分。<b>round4 F3 起本仓已无生产调用方</b>
     *  （{@code ReportGenerator.queryBatchRows} 改走 {@link #queryBatchStatsOrFail}）；
     *  保留仅为二进制/源码兼容。新调用方一律用 {@code *OrFail}。 */
    public List<BatchStatRow> queryBatchStats(String dateFrom, String dateTo, int limit,
                                              boolean respectPrivacy) {
        return queryBatchStatsOrFail(dateFrom, dateTo, limit, respectPrivacy).rows();
    }

    /**
     * 单报隐私检查：读 player_profiles.show_in_square。
     * 无行 = 从未改过开关 = 默认可见（返回 false）。
     *
     * <p><b>round5 L-01 修正（fail-closed）</b>：SQL 异常<b>不再</b> {@code return false}——
     * 那会让"读不到"与"未隐藏"共用同一返回值，{@code ReportGenerator} 的
     * {@code respectPrivacy() && isHiddenInSquare(...)} 闸门被整段跳过，隐身玩家的数据
     * 照常渲染。隐私方向必须与其余 {@code *OrFail} 相反：读失败要<b>少报</b>，不能多报。
     * 失败抛 {@link IllegalStateException}，由 {@code ReportGenerator.generateSingle}
     * 的 {@code catch (Exception)} 落到 {@code report.failed}（不出报告）。</p>
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
                throw new IllegalStateException(
                        "privacy flag read failed for " + playerId, e);
            }
        }
        return false;
    }
}
