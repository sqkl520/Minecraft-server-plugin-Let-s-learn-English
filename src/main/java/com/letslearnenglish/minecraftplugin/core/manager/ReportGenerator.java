package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Report Generator — 域 6 家长/老师学习报告（tasks/phase3/plan-reports.md 施工图）。
 *
 * <p>读者是<strong>不上游戏</strong>的家长/老师：产物是强制中文（{@code report.language} 可配）
 * 的纯文本 TXT（UTF-8 + BOM + CRLF，记事本友好、无颜色码），落
 * {@code plugins/LetsLearnEnglish/exports/reports/}。报告文件正文从对应语言的
 * messages/*.yml 读 {@code report.*} 纯文本模板渲染；聊天摘要卡按执行者语言走
 * {@link com.letslearnenglish.minecraftplugin.util.MessageUtil}。
 *
 * <p>线程模型（plan §3.4）：命令回调（主线程）只做参数解析、UUID 解析与在线
 * 快照 {@link CumulativeSnapshot}；<strong>全部 DB 查询、文本渲染与文件写入在异步
 * 线程</strong>（DatabaseManager 的 connectionLock 同步块在异步线程调用安全，见
 * PlayerDataStore 先例）；聊天反馈回主线程发送。异步完成时复查
 * {@code plugin.isEnabled()} 并吞掉调度器关闭竞态（S2 shutdown 守卫同款）。
 *
 * <p>协议化返回（P2-09 教训落地）：内部判定一律走 {@link ReportResult} 字段，
 * 不靠字符串前缀识别成败。
 *
 * <p>隐私边界（plan §1.6 D11 裁决）：默认 {@code report.respect-privacy=false}
 * —— 权限即同意边界，报告属管理/监护通道，不受玩家 showInSquare/showResults
 * 展示开关约束；置 true 时单报对 showInSquare=false 玩家拒发、批量按同款
 * SQL 口径过滤。报告<strong>零网络外发</strong>、不含 AI 对话原文（只含统计数）。
 *
 * <p>已知降级：数据层"新学单词"所需的 {@code word_progress.first_learned_at}
 * （plan §1.4 补充 A）尚未落地，本类不查询该列——"本周新学单词"节整节省略
 * （plan §4.2 降级路径：不回填、不报错），其余节不受影响。
 */
public class ReportGenerator {

    /** 批量逐人小节里错题列表的固定截断（plan §1.7：精简版错题 Top3）。 */
    private static final int BATCH_WEAK_LIMIT = 3;

    /** 分隔线宽度（==/── 的重复次数，纯符号不属文案，不入 i18n）。 */
    private static final String BORDER = "================================================";

    /** 趋势判定阈值：后半窗口正确率与前半差超过 2 个百分点才提示涨/跌。 */
    private static final double TREND_THRESHOLD = 0.02;

    private final LetsLearnEnglish plugin;
    private final DatabaseManager databaseManager;
    private final WordManagerImpl wordManager;

    /**
     * 惰性清理闸：首次生成报告时清理一次过期的 report_*.txt（keep-days）。
     * 不在启动期接线（本类白名单外不新增启动任务），由生成路径顺带触发。
     */
    private final AtomicBoolean cleanupDone = new AtomicBoolean(false);

    /** 构造注入（plan §3.1，仿 DataExporter 位置）；两依赖均只赋值一次、内部原地变更，持引用安全。 */
    public ReportGenerator(LetsLearnEnglish plugin, DatabaseManager databaseManager,
                           WordManagerImpl wordManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.wordManager = wordManager;
    }

    // ============================================================
    //  对外记录类型
    // ============================================================

    /**
     * 在线玩家累计数快照（plan §1.5：主线程命令入口处取"与 /le stats 同源"的
     * 缓存快照，随参数传入异步生成器——异步线程绝不触碰 PlayerDataStore 缓存
     * 的懒加载路径）。离线玩家传 {@code null}，累计数在异步线程读库直查。
     */
    public record CumulativeSnapshot(int totalCorrect, int sessions, long score,
                                     int conversations, int unlockedCount) {}

    /** 生成结果协议对象（plan §3.5：按字段判定，永不靠字符串前缀）。 */
    public record ReportResult(boolean success, File file, String errorMessage) {}

    /**
     * 报告统计周期（plan §1.2）。纯函数、无 Bukkit 依赖。
     * <ul>
     *   <li>{@code week} = 本周一 00:00 ~ 今天（与 LearningCalendarGUI 的
     *       {@code today.with(DayOfWeek.MONDAY)} 周一锚点同口径）</li>
     *   <li>{@code lastweek} = 上周一 ~ 上周日（完整 7 天）</li>
     *   <li>{@code <N>d} = 今天-(N-1) ~ 今天（N = 1..90）</li>
     * </ul>
     */
    public record ReportPeriod(LocalDate from, LocalDate to) {

        /** 解析周期参数；非法输入返回 {@code null}（调用方发 report.period-invalid）。 */
        public static ReportPeriod compute(String spec, LocalDate today) {
            if (spec == null) {
                return null;
            }
            String s = spec.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                return null;
            }
            if ("week".equals(s)) {
                return new ReportPeriod(today.with(DayOfWeek.MONDAY), today);
            }
            if ("lastweek".equals(s)) {
                LocalDate monday = today.with(DayOfWeek.MONDAY).minusWeeks(1);
                return new ReportPeriod(monday, monday.plusDays(6));
            }
            if (s.matches("\\d{1,3}d")) {
                int days = Integer.parseInt(s.substring(0, s.length() - 1));
                if (days < 1 || days > 90) {
                    return null;
                }
                return new ReportPeriod(today.minusDays(days - 1L), today);
            }
            return null;
        }
    }

    // ============================================================
    //  内部记录类型
    // ============================================================

    /**
     * 一次生成所需的配置快照（主线程入口处读好，异步线程不再读配置；
     * messages 引用为语言文件快照——/lea reload 只会替换 ConfigManager 的引用，
     * 持旧引用渲染安全且保证单份报告模板一致）。
     */
    private record ReportSettings(String lang, FileConfiguration messages, FileConfiguration fallbackZh,
                                  boolean respectPrivacy, double minutesPerQuestion,
                                  int keepDays, int weakLimit, int batchMax, String serverName) {}

    private record DailyRow(LocalDate date, int answered, int correct, long score) {}

    private record WeakWord(String word, int wrongCount, String chinese, String difficulty) {}

    private record CumulativeOut(long totalCorrect, long sessions, long score, int conversations,
                                 int recentConversations, int unlocked, int recentAchievements,
                                 int achTotal, int streak, int challengeDone, int challengeTotal) {}

    private record SingleData(String playerName, ReportPeriod period, List<DailyRow> daily,
                              List<WeakWord> weak, CumulativeOut cum, boolean windowEmpty) {}

    private record BatchRow(UUID uuid, String name, int days, int answered, int correct, long score) {}

    // ============================================================
    //  入口（主线程调用；方法内部自管异步派发与回主线程反馈）
    // ============================================================

    /**
     * 生成单个玩家的报告（plan §3.4 三段式：主线程快照 → 异步查询+渲染+写文件
     * → 回主线程发摘要卡）。
     *
     * @param sender     命令执行者（玩家/控制台），用于聊天反馈
     * @param playerId   已解析的目标 UUID（调用方已过三级查找）
     * @param displayName 玩家名回落值（在线为 Bukkit 名；离线为命令参数）
     * @param period     统计周期
     * @param snapshot   在线累计快照；离线传 {@code null}
     */
    public void generateSingle(CommandSender sender, UUID playerId, String displayName,
                               ReportPeriod period, CumulativeSnapshot snapshot) {
        ReportSettings settings = captureSettings();
        runAsync(() -> {
            try {
                if (settings.respectPrivacy() && isHiddenInSquare(playerId)) {
                    sendOnMainThread(() -> sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "report.hidden")));
                    return;
                }
                cleanupOldReportsIfDue(settings);
                SingleData data = collectSingle(playerId, displayName, period, settings, snapshot);
                ReportResult result = writeSingleFile(data, settings);
                if (!result.success()) {
                    String reason = result.errorMessage();
                    sendOnMainThread(() -> sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "report.failed", "reason", reason)));
                    return;
                }
                File file = result.file();
                plugin.getLogger().info("Report generated: " + file.getAbsolutePath());
                sendOnMainThread(() -> sendSingleSummary(sender, data, file));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to generate report for " + playerId, e);
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                sendOnMainThread(() -> sender.sendMessage(plugin.getMessageUtil()
                        .getSenderMessage(sender, "report.failed", "reason", reason)));
            }
        });
    }

    /**
     * 生成全服活跃批量报告（plan §1.7）：一条汇总 SQL 取窗口内活跃玩家
     * （上限 {@code report.batch.max-players}，超出截断并标注），产出一个合并 TXT。
     * 窗口内 0 人时不出文件，改发 report.no-data。
     */
    public void generateBatch(CommandSender sender, ReportPeriod period) {
        ReportSettings settings = captureSettings();
        runAsync(() -> {
            try {
                cleanupOldReportsIfDue(settings);
                List<BatchRow> fetched = queryBatchRows(period, settings);
                final boolean truncated = fetched.size() > settings.batchMax();
                final List<BatchRow> rows = truncated
                        ? new ArrayList<>(fetched.subList(0, settings.batchMax()))
                        : fetched;
                if (rows.isEmpty()) {
                    sendOnMainThread(() -> sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "report.no-data")));
                    return;
                }
                final int count = rows.size();
                sendOnMainThread(() -> sender.sendMessage(plugin.getMessageUtil()
                        .getSenderMessage(sender, "report.batch-start",
                                "count", String.valueOf(count))));

                ReportResult result = writeBatchFile(rows, truncated, period, settings);
                if (!result.success()) {
                    final String reason = result.errorMessage();
                    sendOnMainThread(() -> sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "report.failed", "reason", reason)));
                    return;
                }
                File file = result.file();
                plugin.getLogger().info("Batch report generated: " + file.getAbsolutePath()
                        + " (" + count + " players" + (truncated ? ", truncated" : "") + ")");
                final String relative = "exports/reports/" + file.getName();
                final int max = settings.batchMax();
                sendOnMainThread(() -> {
                    String truncatedNote = truncated
                            ? plugin.getMessageUtil().getSenderMessage(sender,
                                    "report.batch-truncated", "max", String.valueOf(max))
                            : "";
                    sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                            "report.batch-complete",
                            "file", relative,
                            "count", String.valueOf(count),
                            "truncated", truncatedNote));
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to generate batch report", e);
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                sendOnMainThread(() -> sender.sendMessage(plugin.getMessageUtil()
                        .getSenderMessage(sender, "report.failed", "reason", reason)));
            }
        });
    }

    // ============================================================
    //  主线程反馈
    // ============================================================

    /** 单人摘要卡（plan §2.2）：与文件同源同一份数据，数字必然一致。 */
    private void sendSingleSummary(CommandSender sender, SingleData data, File file) {
        String relative = file != null ? "exports/reports/" + file.getName() : "";
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "report.chat-header", "player", data.playerName()));
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "report.chat-overview",
                "from", data.period().from().toString(),
                "to", data.period().to().toString(),
                "days", String.valueOf(data.daily().size()),
                "correct", String.valueOf(sumCorrect(data.daily())),
                "accuracy", accuracyText(sumCorrect(data.daily()), sumAnswered(data.daily()))));
        if (!data.weak().isEmpty()) {
            StringBuilder words = new StringBuilder();
            for (int i = 0; i < data.weak().size() && i < 3; i++) {
                if (i > 0) {
                    words.append(" · ");
                }
                words.append(data.weak().get(i).word());
            }
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                    "report.chat-weak", "words", words.toString()));
        }
        sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                "report.generated", "file", relative));
        if (data.windowEmpty()) {
            sender.sendMessage(plugin.getMessageUtil().getSenderMessage(sender,
                    "report.no-data"));
        }
    }

    // ============================================================
    //  数据采集（全部在异步线程执行；每个查询自持 connectionLock）
    // ============================================================

    private SingleData collectSingle(UUID playerId, String displayName, ReportPeriod period,
                                     ReportSettings settings, CumulativeSnapshot snapshot) {
        String prefix = databaseManager.getTablePrefix();

        List<DailyRow> daily = queryDailyRows(prefix, playerId, period);

        // 终身汇总 + 库内名字（Q2）
        String dbName = null;
        long cumCorrect = 0L;
        long cumSessions = 0L;
        long cumScore = 0L;
        int cumConversations = 0;
        String sql2 = "SELECT player_name, total_words_learned, total_sessions, total_score,"
                + " conversation_count FROM " + prefix + "player_data WHERE uuid = ?";
        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql2)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        dbName = rs.getString("player_name");
                        cumCorrect = rs.getLong("total_words_learned");
                        cumSessions = rs.getLong("total_sessions");
                        cumScore = rs.getLong("total_score");
                        cumConversations = rs.getInt("conversation_count");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to query player_data for report: " + playerId, e);
            }
        }
        // 在线玩家以主线程快照覆盖（与 /le stats 同源；plan §1.5）
        if (snapshot != null) {
            cumCorrect = snapshot.totalCorrect();
            cumSessions = snapshot.sessions();
            cumScore = snapshot.score();
            cumConversations = snapshot.conversations();
        }

        // 成就：总数 + 窗口内新解锁（Q3；unlocked_at 为毫秒长整）
        long windowStart = period.from().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        long windowEndExclusive = period.to().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        int unlockedDb = 0;
        int recentAchievements = 0;
        String sql3 = "SELECT COUNT(*) AS total,"
                + " SUM(CASE WHEN unlocked_at >= ? AND unlocked_at < ? THEN 1 ELSE 0 END) AS recent"
                + " FROM " + prefix + "achievements WHERE uuid = ?";
        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql3)) {
                stmt.setLong(1, windowStart);
                stmt.setLong(2, windowEndExclusive);
                stmt.setString(3, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        unlockedDb = rs.getInt("total");
                        recentAchievements = rs.getInt("recent");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to query achievements for report: " + playerId, e);
            }
        }
        int unlocked = snapshot != null ? snapshot.unlockedCount() : unlockedDb;

        // AI 对话：窗口内完成的对话会话数（Q4）。timestamp 为
        // yyyy-MM-dd HH:mm:ss 文本/时间戳列，用同格式字符串边界比较
        // （SQLite 文本比较与 CURRENT_TIMESTAMP 写入格式一致；MySQL 自动转 TIMESTAMP 比较）。
        int recentConversations = 0;
        String sql4 = "SELECT COUNT(DISTINCT session_id) FROM " + prefix
                + "ai_chat_history WHERE player_uuid = ? AND timestamp >= ? AND timestamp <= ?";
        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql4)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, period.from() + " 00:00:00");
                stmt.setString(3, period.to() + " 23:59:59");
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        recentConversations = rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to query AI chat count for report: " + playerId, e);
            }
        }

        // 错题本 Top N（Q5，当前状态）
        List<WeakWord> weak = queryWeakWords(prefix, playerId, settings.weakLimit());

        // 当前连续签到（Q6）
        int streak = 0;
        String sql6 = "SELECT streak_count FROM " + prefix
                + "checkin_data WHERE player_uuid = ?";
        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql6)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        streak = rs.getInt("streak_count");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to query check-in streak for report: " + playerId, e);
            }
        }

        // 每日挑战完成度（Q8，窗口；完成行数/总行数）
        int challengeDone = 0;
        int challengeTotal = 0;
        String sql8 = "SELECT SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) AS done,"
                + " COUNT(*) AS total FROM " + prefix + "daily_challenges"
                + " WHERE uuid = ? AND challenge_date >= ? AND challenge_date <= ?";
        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql8)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, period.from().toString());
                stmt.setString(3, period.to().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        challengeDone = rs.getInt("done");
                        challengeTotal = rs.getInt("total");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to query daily challenges for report: " + playerId, e);
            }
        }

        // 名字优先级：在线（有快照）= 实时 Bukkit 名；离线 = player_data 的落盘名
        // （命令参数可能大小写不规范）；两者皆缺回落 uuid 前 8 位（既有惯例）。
        String name = snapshot != null
                ? firstNonBlank(displayName, dbName, playerId.toString().substring(0, 8))
                : firstNonBlank(dbName, displayName, playerId.toString().substring(0, 8));
        CumulativeOut cum = new CumulativeOut(cumCorrect, cumSessions, cumScore,
                cumConversations, recentConversations, unlocked, recentAchievements,
                Achievement.values().length, streak, challengeDone, challengeTotal);
        return new SingleData(name, period, daily, weak, cum, daily.isEmpty());
    }

    /** Q1：窗口逐日会话行（UNIQUE(uuid, session_date) 保证一天一行）。 */
    private List<DailyRow> queryDailyRows(String prefix, UUID playerId, ReportPeriod period) {
        List<DailyRow> rows = new ArrayList<>();
        String sql = "SELECT session_date, words_count, correct_count, score FROM " + prefix
                + "player_sessions WHERE uuid = ? AND session_date >= ? AND session_date <= ?"
                + " ORDER BY session_date ASC";
        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, period.from().toString());
                stmt.setString(3, period.to().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new DailyRow(LocalDate.parse(rs.getString("session_date")),
                                rs.getInt("words_count"), rs.getInt("correct_count"),
                                rs.getLong("score")));
                    }
                }
            } catch (SQLException | java.time.format.DateTimeParseException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to query session rows for report: " + playerId, e);
            }
        }
        return rows;
    }

    /** Q5：当前错题本 Top N（按错误次数降序；附内存词库中文义与难度，已删词回落纯英文）。 */
    private List<WeakWord> queryWeakWords(String prefix, UUID playerId, int limit) {
        List<WeakWord> out = new ArrayList<>();
        String sql = "SELECT word, wrong_count FROM " + prefix + "difficult_words"
                + " WHERE player_uuid = ? ORDER BY wrong_count DESC, word ASC LIMIT ?";
        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setInt(2, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String word = rs.getString("word");
                        int wrongCount = rs.getInt("wrong_count");
                        // 词库内存索引（ConcurrentHashMap）只读查询，异步安全（plan §3.4）
                        Word entry = wordManager != null
                                ? wordManager.getWord(word) : null;
                        String chinese = (entry != null && entry.getChinese() != null
                                && !entry.getChinese().isBlank()) ? entry.getChinese() : null;
                        String difficulty = entry != null ? entry.getDifficulty() : null;
                        out.add(new WeakWord(word, wrongCount, chinese, difficulty));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to query weak words for report: " + playerId, e);
            }
        }
        return out;
    }

    /**
     * Q9：批量——窗口内有 player_sessions 行的玩家汇总（按答对数降序）。
     * 传 {@code batchMax + 1} 条探测截断；respect-privacy=true 时按与榜单/广场
     * 同款口径过滤隐身玩家（COALESCE(show_in_square,1)<>0）。
     */
    private List<BatchRow> queryBatchRows(ReportPeriod period, ReportSettings settings) {
        List<BatchRow> rows = new ArrayList<>();
        String prefix = databaseManager.getTablePrefix();
        // MAX(player_name) 包法兼容 MySQL ONLY_FULL_GROUP_BY（沿 R-05/周榜惯例）
        String sql = "SELECT s.uuid AS uuid, MAX(p.player_name) AS player_name,"
                + " COUNT(*) AS active_days,"
                + " COALESCE(SUM(s.words_count), 0) AS total_answered,"
                + " COALESCE(SUM(s.correct_count), 0) AS total_correct,"
                + " COALESCE(SUM(s.score), 0) AS total_score"
                + " FROM " + prefix + "player_sessions s"
                + " LEFT JOIN " + prefix + "player_data p ON p.uuid = s.uuid";
        if (settings.respectPrivacy()) {
            sql += " LEFT JOIN " + prefix + "player_profiles pf ON pf.uuid = s.uuid";
        }
        sql += " WHERE s.session_date >= ? AND s.session_date <= ?";
        if (settings.respectPrivacy()) {
            sql += " AND COALESCE(pf.show_in_square, 1) <> 0";
        }
        sql += " GROUP BY s.uuid ORDER BY total_correct DESC, s.uuid ASC LIMIT ?";

        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
                stmt.setString(1, period.from().toString());
                stmt.setString(2, period.to().toString());
                stmt.setInt(3, settings.batchMax() + 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String uuidStr = rs.getString("uuid");
                        String name = rs.getString("player_name");
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException e) {
                            continue; // 脏 uuid 行跳过，不拖垮整批
                        }
                        if (name == null || name.isBlank()) {
                            name = uuid.toString().substring(0, 8);
                        }
                        rows.add(new BatchRow(uuid, name,
                                rs.getInt("active_days"), rs.getInt("total_answered"),
                                rs.getInt("total_correct"), rs.getLong("total_score")));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query batch report rows", e);
            }
        }
        return rows;
    }

    /**
     * 单报隐私检查（respect-privacy=true 时）：读 player_profiles.show_in_square。
     * 无行 = 从未改过开关 = 默认可见（COALESCE(...,1) 口径）。
     */
    private boolean isHiddenInSquare(UUID playerId) {
        String sql = "SELECT show_in_square FROM "
                + databaseManager.getTablePrefix()
                + "player_profiles WHERE uuid = ?";
        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return !rs.getBoolean("show_in_square");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to check privacy flag for report: " + playerId, e);
            }
        }
        return false;
    }

    // ============================================================
    //  文件写入（异步线程）
    // ============================================================

    private ReportResult writeSingleFile(SingleData data, ReportSettings settings) {
        try {
            File dir = reportsDir();
            String stamp = new SimpleDateFormat("HHmmss").format(new Date());
            String name = data.playerName().replaceAll("[^A-Za-z0-9_-]", "_");
            String fileName = "report_" + name
                    + "_" + data.period().from().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + data.period().to().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "_" + stamp + ".txt";
            File file = new File(dir, fileName);
            writeTextFile(file, renderSingle(data, settings));
            return new ReportResult(true, file, null);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write report file", e);
            return new ReportResult(false, null,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private ReportResult writeBatchFile(List<BatchRow> rows, boolean truncated, ReportPeriod period,
                                        ReportSettings settings) {
        try {
            File dir = reportsDir();
            String stamp = new SimpleDateFormat("HHmmss").format(new Date());
            String fileName = "report_all_"
                    + period.from().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + period.to().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "_" + stamp + ".txt";
            File file = new File(dir, fileName);
            writeTextFile(file, renderBatch(rows, truncated, period, settings));
            return new ReportResult(true, file, null);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write batch report file", e);
            return new ReportResult(false, null,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** exports/reports/ 目录（不存在则创建，plan §1.8 约定 exports/ 家族）。 */
    private File reportsDir() {
        File dir = new File(plugin.getDataFolder(), "exports/reports");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * UTF-8 + BOM + CRLF 落盘（plan §1.8：Windows 记事本友好；BOM 防旧记事本
     * 识别成 ANSI 乱码）。内容按 '\n' 组装，写前统一替换为 '\r\n'。
     */
    private void writeTextFile(File file, String content) throws IOException {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] text = content.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(text, 0, out, bom.length, text.length);
        Files.write(file.toPath(), out);
    }

    /**
     * 惰性清理：整个进程生命周期内首个报告生成时执行一次，删除
     * exports/reports/report_*.txt 中修改时间超过 keep-days 的旧文件
     * （keep-days<=0 不清理；对齐 cleanupOldChatHistory 模式的产物侧版本）。
     */
    private void cleanupOldReportsIfDue(ReportSettings settings) {
        if (settings.keepDays() <= 0 || !cleanupDone.compareAndSet(false, true)) {
            return;
        }
        File[] files = reportsDir().listFiles(
                (dir, name) -> name.startsWith("report_") && name.endsWith(".txt"));
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - settings.keepDays() * 86400000L;
        for (File f : files) {
            if (f.lastModified() < cutoff && f.delete()) {
                plugin.getLogger().info("Old report deleted: " + f.getName());
            }
        }
    }

    // ============================================================
    //  文本渲染（异步线程；正文从 messages/{report.language}.yml 读纯文本模板）
    // ============================================================

    private String renderSingle(SingleData data, ReportSettings settings) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(BORDER).append('\n');
        sb.append("           ").append(t(settings, "report.title")).append('\n');
        sb.append(BORDER).append('\n');
        sb.append(t(settings, "report.meta-player", "player", data.playerName())).append('\n');
        sb.append(t(settings, "report.meta-server", "server", settings.serverName())).append('\n');
        sb.append(t(settings, "report.meta-period",
                "from", data.period().from().toString(),
                "to", data.period().to().toString())).append('\n');
        sb.append(t(settings, "report.meta-generated", "time", generatedAt())).append('\n');

        // 概览
        int answered = sumAnswered(data.daily());
        int correct = sumCorrect(data.daily());
        sb.append('\n').append(t(settings, "report.section-overview")).append('\n');
        if (data.windowEmpty()) {
            // plan §4.2：窗口零活动照常出报告，概览节后加一行提示（文件版模板，
            // 无 & 码/无 prefix——与聊天键 report.no-data 是两个受众的两种形态）
            sb.append("  ").append(t(settings, "report.no-data-file")).append('\n');
        }
        sb.append("  ").append(t(settings, "report.overview-days",
                "days", String.valueOf(data.daily().size()))).append(activeDaySuffix(data, settings)).append('\n');
        sb.append("  ").append(t(settings, "report.overview-correct",
                "correct", String.valueOf(correct))).append('\n');
        sb.append("  ").append(t(settings, "report.overview-accuracy",
                "accuracy", accuracyText(correct, answered),
                "correct", String.valueOf(correct),
                "total", String.valueOf(answered))).append('\n');
        sb.append("  ").append(t(settings, "report.overview-score",
                "score", scoreText(sumScore(data.daily())))).append('\n');
        sb.append("  ").append(t(settings, "report.overview-minutes",
                "minutes", minutesText(answered, settings),
                "seconds", secondsText(settings))).append('\n');

        // 每日明细（窗口为空整节省略；不展示 mode——UPSERT 合并时 mode 列不更新，plan PF-RPT-04）
        if (!data.daily().isEmpty()) {
            sb.append('\n').append(t(settings, "report.section-daily")).append('\n');
            sb.append("  ").append(t(settings, "report.daily-header")).append('\n');
            DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("MM-dd");
            for (DailyRow row : data.daily()) {
                sb.append("  ").append(t(settings, "report.daily-row",
                        "date", row.date().format(dayFmt),
                        "weekday", weekdayName(settings, row.date()),
                        "answered", String.valueOf(row.answered()),
                        "correct", String.valueOf(row.correct()),
                        "accuracy", accuracyText(row.correct(), row.answered()),
                        "score", scoreText(row.score()))).append('\n');
            }
        }

        // 需要加强的单词（当前错题本 Top N；已删词回落纯英文词条，plan §4.2）
        if (!data.weak().isEmpty()) {
            sb.append('\n').append(t(settings, "report.section-weak-words",
                    "k", String.valueOf(data.weak().size()))).append('\n');
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < data.weak().size(); i++) {
                if (i > 0) {
                    items.append("    ");
                }
                WeakWord w = data.weak().get(i);
                items.append(w.chinese() != null
                        ? t(settings, "report.weak-word-item",
                                "word", w.word(), "chinese", w.chinese(),
                                "count", String.valueOf(w.wrongCount()))
                        : t(settings, "report.weak-word-item-no-cn",
                                "word", w.word(), "count", String.valueOf(w.wrongCount())));
            }
            sb.append("  ").append(items).append('\n');
            String focus = dominantDifficulty(data.weak(), settings);
            if (focus != null) {
                sb.append("  ").append(t(settings, "report.weak-focus",
                        "difficulty", focus)).append('\n');
            }
        }

        // 长期积累
        CumulativeOut cum = data.cum();
        sb.append('\n').append(t(settings, "report.section-cumulative")).append('\n');
        sb.append("  ").append(t(settings, "report.cumulative-line",
                "words", String.valueOf(cum.totalCorrect()),
                "sessions", String.valueOf(cum.sessions()))).append('\n');
        sb.append("  ").append(t(settings, "report.cumulative-extra",
                "unlocked", String.valueOf(cum.unlocked()),
                "total", String.valueOf(cum.achTotal()),
                "streak", String.valueOf(cum.streak()))).append('\n');
        if (cum.recentAchievements() > 0) {
            sb.append("  ").append(t(settings, "report.cumulative-achievements-recent",
                    "count", String.valueOf(cum.recentAchievements()))).append('\n');
        }
        sb.append("  ").append(t(settings, "report.cumulative-ai",
                "recent", String.valueOf(cum.recentConversations()),
                "total", String.valueOf(cum.conversations()))).append('\n');
        if (cum.challengeTotal() > 0) {
            sb.append("  ").append(t(settings, "report.cumulative-challenge",
                    "done", String.valueOf(cum.challengeDone()),
                    "all", String.valueOf(cum.challengeTotal()))).append('\n');
        }

        // 给家长的话（规则生成，plan §2.3；无 AI 调用）
        sb.append('\n').append(t(settings, "report.section-advice")).append('\n');
        for (String line : adviceLines(data, settings)) {
            sb.append("  ").append(line).append('\n');
        }

        // 诚实脚注
        sb.append('\n').append(t(settings, "report.section-note")).append('\n');
        sb.append("  ").append(t(settings, "report.note-line",
                "seconds", secondsText(settings))).append('\n');
        sb.append(BORDER).append('\n');
        return sb.toString();
    }

    private String renderBatch(List<BatchRow> rows, boolean truncated, ReportPeriod period,
                               ReportSettings settings) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(BORDER).append('\n');
        sb.append("           ").append(t(settings, "report.batch-title")).append('\n');
        sb.append(BORDER).append('\n');
        sb.append(t(settings, "report.meta-server", "server", settings.serverName())).append('\n');
        sb.append(t(settings, "report.meta-period",
                "from", period.from().toString(),
                "to", period.to().toString())).append('\n');
        sb.append(t(settings, "report.meta-generated", "time", generatedAt())).append('\n');
        String countLine = t(settings, "report.batch-count-line",
                "count", String.valueOf(rows.size()));
        if (truncated) {
            countLine += " " + t(settings, "report.batch-truncated-file",
                    "max", String.valueOf(settings.batchMax()));
        }
        sb.append(countLine).append('\n');

        // 对比总表（按答对数降序，Q9 已排序）
        sb.append('\n').append(t(settings, "report.batch-table-title")).append('\n');
        sb.append("  ").append(t(settings, "report.batch-table-header")).append('\n');
        for (BatchRow row : rows) {
            sb.append("  ").append(t(settings, "report.batch-table-row",
                    "name", row.name(),
                    "days", String.valueOf(row.days()),
                    "correct", String.valueOf(row.correct()),
                    "accuracy", accuracyText(row.correct(), row.answered()),
                    "score", scoreText(row.score()))).append('\n');
        }

        // 逐人小节（精简版：概览 + 错题 Top3，不回放每日明细，plan §1.7）
        String prefix = databaseManager.getTablePrefix();
        for (BatchRow row : rows) {
            sb.append('\n').append(t(settings, "report.batch-student-header",
                    "player", row.name())).append('\n');
            sb.append("  ").append(t(settings, "report.overview-days",
                    "days", String.valueOf(row.days()))).append('\n');
            sb.append("  ").append(t(settings, "report.overview-correct",
                    "correct", String.valueOf(row.correct()))).append('\n');
            sb.append("  ").append(t(settings, "report.overview-accuracy",
                    "accuracy", accuracyText(row.correct(), row.answered()),
                    "correct", String.valueOf(row.correct()),
                    "total", String.valueOf(row.answered()))).append('\n');
            sb.append("  ").append(t(settings, "report.overview-score",
                    "score", scoreText(row.score()))).append('\n');
            List<WeakWord> weak = queryWeakWords(prefix, row.uuid(), BATCH_WEAK_LIMIT);
            if (!weak.isEmpty()) {
                StringBuilder items = new StringBuilder();
                for (int i = 0; i < weak.size(); i++) {
                    if (i > 0) {
                        items.append("    ");
                    }
                    WeakWord w = weak.get(i);
                    items.append(w.chinese() != null
                            ? t(settings, "report.weak-word-item",
                                    "word", w.word(), "chinese", w.chinese(),
                                    "count", String.valueOf(w.wrongCount()))
                            : t(settings, "report.weak-word-item-no-cn",
                                    "word", w.word(), "count", String.valueOf(w.wrongCount())));
                }
                sb.append("  ").append(t(settings, "report.batch-weak-line",
                        "items", items.toString())).append('\n');
            }
        }

        sb.append('\n').append(t(settings, "report.section-note")).append('\n');
        sb.append("  ").append(t(settings, "report.note-line",
                "seconds", secondsText(settings))).append('\n');
        sb.append(BORDER).append('\n');
        return sb.toString();
    }

    /**
     * "给家长的话"规则（plan §2.3，确定性、无 AI 调用）：
     * 学习天数 == 0 → advice-none；否则拼 坚持（>=5 天）/ 正确率趋势（前后半窗口
     * 各 >=2 天才有比较，差 >2 个百分点）/ 错题建议（前 3 个听写）；全不命中 → advice-default。
     */
    private List<String> adviceLines(SingleData data, ReportSettings settings) {
        List<String> lines = new ArrayList<>();
        int days = data.daily().size();
        if (days == 0) {
            lines.add(t(settings, "report.advice-none"));
            return lines;
        }
        if (days >= 5) {
            lines.add(t(settings, "report.advice-good"));
        }
        String trend = trendAdvice(data, settings);
        if (trend != null) {
            lines.add(trend);
        }
        if (!data.weak().isEmpty()) {
            lines.add(t(settings, "report.advice-weak"));
        }
        if (lines.isEmpty()) {
            lines.add(t(settings, "report.advice-default"));
        }
        return lines;
    }

    /** 前半窗口 vs 后半窗口正确率比较；任一侧活跃天数不足 2 天时不提示。 */
    private String trendAdvice(SingleData data, ReportSettings settings) {
        long span = ChronoUnit.DAYS.between(data.period().from(), data.period().to()) + 1;
        LocalDate mid = data.period().from().plusDays(span / 2);
        int firstAnswered = 0;
        int firstCorrect = 0;
        int firstDays = 0;
        int secondAnswered = 0;
        int secondCorrect = 0;
        int secondDays = 0;
        for (DailyRow row : data.daily()) {
            if (row.date().isBefore(mid)) {
                firstAnswered += row.answered();
                firstCorrect += row.correct();
                firstDays++;
            } else {
                secondAnswered += row.answered();
                secondCorrect += row.correct();
                secondDays++;
            }
        }
        if (firstDays < 2 || secondDays < 2 || firstAnswered == 0 || secondAnswered == 0) {
            return null;
        }
        double delta = (double) secondCorrect / secondAnswered - (double) firstCorrect / firstAnswered;
        if (delta > TREND_THRESHOLD) {
            return t(settings, "report.advice-up");
        }
        if (delta < -TREND_THRESHOLD) {
            return t(settings, "report.advice-down");
        }
        return null;
    }

    /** 错题 ≥3 词时归组共同难度（多数档位；自定义档位回落原名）。 */
    private String dominantDifficulty(List<WeakWord> weak, ReportSettings settings) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (WeakWord w : weak) {
            if (w.difficulty() != null && !w.difficulty().isBlank()) {
                tally.merge(w.difficulty(), 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : tally.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (best == null || bestCount < 3) {
            return null;
        }
        String display = lookup(settings, "word.difficulty-" + best);
        return display != null ? display : best;
    }

    /** 概览"学习天数"行后的受学日列表后缀（如 "  (周一、周二)"）。 */
    private String activeDaySuffix(SingleData data, ReportSettings settings) {
        if (data.daily().isEmpty()) {
            return "";
        }
        String sep = "zh".equals(settings.lang()) ? "、" : ", ";
        StringBuilder sb = new StringBuilder("  (");
        for (int i = 0; i < data.daily().size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(weekdayName(settings, data.daily().get(i).date()));
        }
        return sb.append(')').toString();
    }

    private String weekdayName(ReportSettings settings, LocalDate date) {
        String key = switch (date.getDayOfWeek()) {
            case MONDAY -> "report.weekday-mon";
            case TUESDAY -> "report.weekday-tue";
            case WEDNESDAY -> "report.weekday-wed";
            case THURSDAY -> "report.weekday-thu";
            case FRIDAY -> "report.weekday-fri";
            case SATURDAY -> "report.weekday-sat";
            case SUNDAY -> "report.weekday-sun";
        };
        String value = lookup(settings, key);
        return value != null ? value : date.getDayOfWeek().name();
    }

    // ============================================================
    //  模板读取与格式化工具
    // ============================================================

    /**
     * 读报告模板：按 report.language 取纯文本值（不含 & 码）；缺失时回落 zh 文件；
     * 两处都缺打 WARNING 并返回可见占位 [?key]（fail-visible，不静默成空）。
     */
    private String t(ReportSettings settings, String key, String... placeholders) {
        String value = lookup(settings, key);
        if (value == null) {
            plugin.getLogger().warning("Missing report template key: " + key);
            value = "[?" + key + "]";
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return value;
    }

    /** 无日志的模板查询（difficulty 显示名等"可缺省"键用）。 */
    private String lookup(ReportSettings settings, String key) {
        String value = settings.messages() != null ? settings.messages().getString(key) : null;
        if (value == null && settings.fallbackZh() != null) {
            value = settings.fallbackZh().getString(key);
        }
        return value;
    }

    /** 配置快照（主线程入口处调用；含 /le reload 语义——每次生成点读最新值）。 */
    private ReportSettings captureSettings() {
        FileConfiguration main = plugin.getConfigManager().getMainConfig();
        String lang = "en".equalsIgnoreCase(main.getString("report.language", "zh")) ? "en" : "zh";
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        FileConfiguration fallbackZh = "zh".equals(lang)
                ? messages : plugin.getConfigManager().getMessageConfig("zh");
        boolean respectPrivacy = main.getBoolean("report.respect-privacy", false);
        double minutesPerQuestion = main.getDouble("report.minutes-per-question", 0.5);
        if (minutesPerQuestion <= 0) {
            minutesPerQuestion = 0.5; // 防 0/负配置：估时不能变成 0
        }
        int keepDays = main.getInt("report.keep-days", 30);
        int weakLimit = Math.max(1, main.getInt("report.weak-words-limit", 5));
        int batchMax = Math.max(1, main.getInt("report.batch.max-players", 50));
        return new ReportSettings(lang, messages, fallbackZh, respectPrivacy, minutesPerQuestion,
                keepDays, weakLimit, batchMax, resolveServerName());
    }

    /** 服务器名：MOTD（去颜色码）优先，取不到回落插件名（plan §2.5 meta-server）。主线程调用。 */
    private String resolveServerName() {
        try {
            String motd = plugin.getServer().getMotd();
            if (motd != null) {
                String cleaned = ChatColor.stripColor(motd).trim();
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        } catch (Throwable ignored) {
            // 取不到照常回落（仅展示用）
        }
        return plugin.getDescription().getName();
    }

    private String generatedAt() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
    }

    private int sumAnswered(List<DailyRow> rows) {
        int total = 0;
        for (DailyRow row : rows) {
            total += row.answered();
        }
        return total;
    }

    private int sumCorrect(List<DailyRow> rows) {
        int total = 0;
        for (DailyRow row : rows) {
            total += row.correct();
        }
        return total;
    }

    private long sumScore(List<DailyRow> rows) {
        long total = 0L;
        for (DailyRow row : rows) {
            total += row.score();
        }
        return total;
    }

    /** 正确率百分数文本（一位小数；分母 0 记 0.0）。窗口口径 = Σcorrect/Σwords。 */
    private String accuracyText(int correct, int answered) {
        double accuracy = answered > 0 ? (double) correct / answered : 0.0;
        return String.format(Locale.ROOT, "%.1f", accuracy * 100.0);
    }

    /** 得分千分位文本（Locale.ROOT 固定半角逗号分隔）。 */
    private String scoreText(long score) {
        return String.format(Locale.ROOT, "%,d", score);
    }

    /** 估时分钟文本（答题数 × minutes-per-question，四舍五入）。 */
    private String minutesText(int answered, ReportSettings settings) {
        return String.valueOf(Math.round(answered * settings.minutesPerQuestion()));
    }

    /** 每题秒数文本（脚注/概览"按每题 X 秒估算"的 X）。 */
    private String secondsText(ReportSettings settings) {
        return String.valueOf(Math.round(settings.minutesPerQuestion() * 60.0));
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    // ============================================================
    //  线程调度守卫
    // ============================================================

    private void runAsync(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (IllegalStateException e) {
            plugin.getLogger().fine("Report task skipped: scheduler unavailable (plugin disabling).");
        }
    }

    /** 回主线程执行（聊天反馈）；插件已停用/调度器关闭时静默放弃（shutdown 竞态守卫）。 */
    private void sendOnMainThread(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (IllegalStateException e) {
            // 调度器已在停用窗口关闭：放弃本次反馈（已生成的文件保留在磁盘）
        }
    }
}
