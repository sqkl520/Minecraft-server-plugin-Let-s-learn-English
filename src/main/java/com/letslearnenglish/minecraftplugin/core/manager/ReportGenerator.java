package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.data.repository.ReportRepository;
import com.letslearnenglish.minecraftplugin.util.AtomicFileWriter;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>线程模型（plan §3.4）：命令回调（主线程）只做参数解析与 UUID 解析；
 * <strong>全部 DB 查询、文本渲染与文件写入在异步线程</strong>（DatabaseManager 的
 * connectionLock 同步块在异步线程调用安全，见 PlayerDataStore 先例）；聊天反馈回
 * 主线程发送。异步完成时复查
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

    /**
     * UTF-8 BOM（U+FEFF）。
     *
     * <p>FIX 2 起落盘走 {@code AtomicFileWriter#write(Path, Supplier&lt;String&gt;, Logger)}，
     * 它内部是 {@code Files.writeString(..., UTF_8)}。U+FEFF 按 UTF-8 编码正好是
     * {@code EF BB BF}，因此用字符形式拼在正文最前面与原实现的三字节前缀<b>逐字节等价</b>，
     * 不必为此改用 {@code writeStream}（那需要自己拿 {@code OutputStream} 写 BOM，
     * 反而多一对容易写错的字节魔数）。
     *
     * <p>用 {@code (char) 0xFEFF} 而非把 U+FEFF 字面量直接写进源码：那是一个
     * <b>零宽不可见</b>字符，混在编辑器里既看不出来也搜不到，任何一次
     * "删掉行首空白"的重排都可能把它悄悄删掉，而症状只是"报告用旧记事本打开是乱码"。
     */
    private static final char BOM = (char) 0xFEFF;

    private final LetsLearnEnglish plugin;
    private final DatabaseManager databaseManager;
    private final WordManagerImpl wordManager;
    /** R6-A：报告只读查询仓储（SQL 与参数绑定已收口；日期边界换算仍在本类）。 */
    private final ReportRepository reportRepository;

    /**
     * FIX 1（round14 F2）：惰性清理闸 —— 从"每进程一次"改为"每个自然日一次"。
     *
     * <p><b>为什么必须改</b>：原实现是 {@code AtomicBoolean cleanupDone}，语义是
     * "整个 JVM 生命周期内只清理一次"。这对"开一局就关"的单机没问题，但家长报告
     * 的典型宿主是 24/7 服：进程连续跑几周，第一次生成报告之后<b>再也没有任何一次</b>
     * 清理，{@code exports/reports/} 只会增长。更深一层的危害是"磁盘写满"——
     * 盘满之后每一次报告落盘都失败，管理员得到的是 {@code report.failed}，
     * 而唯一能腾地方的那条清理路径恰好已被闸门永久关闭，形成"只能手工删文件"的死结。
     *
     * <p><b>为什么用日期而不是定时器</b>：本类白名单外不新增启动任务（见类 javadoc
     * 的线程模型与"接线位置"约定），清理由生成路径顺带触发；而报告生成本就低频
     * （一天最多几次），"生成时顺手看一眼是不是换了天"比多挂一个 Bukkit 定时任务
     * 更省资源，也不会引入新的关服竞态。
     *
     * <p><b>线程安全</b>：报告全部在异步线程生成，可能并发（两个管理员同时敲
     * {@code /lea report}）。用 {@link AtomicReference} 的 {@code compareAndSet}
     * 保证同一天只有一个线程真正去 list/delete 目录；CAS 失败的线程直接返回，
     * 不重复扫盘。残留的竞态只是"两个线程几乎同时跨日" —— CAS 只有一个成功，
     * 另一个下一天再清，语义正确。
     */
    private final AtomicReference<LocalDate> lastPruneDate = new AtomicReference<>();

    /**
     * FIX 3（round14 F2）：同一秒内多次生成报告的文件名唯一化序号。
     *
     * <p>原文件名只有 {@code HHmmss}（1 秒分辨率）+ 玩家名/日期区间，没有唯一成分：
     * 同一秒内跑两次 {@code /lea report}（控制台脚本、两个管理员、或
     * {@code report all} 紧跟在一份单报之后）会得到<b>完全相同</b>的路径，第二次
     * 原子替换掉第一次，而两条命令都回一句"报告已保存"——第一份报告静默丢失。
     *
     * <p>用 {@link AtomicLong} 而非随机数：序号在一个进程内单调，运维按文件名排序
     * 就是生成先后顺序；同时再叠一段随机后缀，覆盖"重启后序号归零 + 同一秒撞上
     * 重启前那个文件"的极小概率窗口（跨进程唯一性只能靠随机/时钟，序号给不了）。
     */
    private final AtomicLong fileSequence = new AtomicLong();

    /** 构造注入（plan §3.1，仿 DataExporter 位置）；两依赖均只赋值一次、内部原地变更，持引用安全。 */
    public ReportGenerator(LetsLearnEnglish plugin, DatabaseManager databaseManager,
                           WordManagerImpl wordManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.wordManager = wordManager;
        this.reportRepository = new ReportRepository(databaseManager, plugin.getLogger());
    }

    // ============================================================
    //  对外记录类型
    // ============================================================

    /**
     * 在线玩家累计数快照（plan §1.5：与 /le stats 同源）。F3：取数在异步段完成。
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
                              List<WeakWord> weak, CumulativeOut cum, boolean windowEmpty,
                              boolean dataIncomplete) {}

    private record BatchRow(UUID uuid, String name, int days, int answered, int correct, long score) {}

    /** Q9 + 完整性位（round4 F3）：{@code complete=false} = 批量聚合失败/中途失败，
     *  {@code rows} 只是部分行——调用方<b>不得</b>据此宣布"本周期无人学习"。 */
    private record BatchFetch(List<BatchRow> rows, boolean complete) {}

    /**
     * FIX 5（round14 F2）：批量逐人错题的预取结果（一条记录 = 一个人的 Top-N）。
     *
     * <p>{@code complete=false} 来自仓储的 {@code *OrFail} 位：读失败时 {@code words}
     * 只是已累积的部分行，渲染器必须打"数据不完整"标记，不能写成"该生没有错题"。
     */
    private record BatchWeak(List<WeakWord> words, boolean complete) {}

    // ============================================================
    //  入口（主线程调用；方法内部自管异步派发与回主线程反馈）
    // ============================================================

    /**
     * 生成单个玩家的报告（plan §3.4 三段式：主线程解析 → 异步快照求值+查询+渲染+
     * 写文件 → 回主线程发摘要卡）。
     *
     * @param sender     命令执行者（玩家/控制台），用于聊天反馈
     * @param playerId   已解析的目标 UUID（调用方已过三级查找）
     * @param displayName 玩家名回落值（在线为 Bukkit 名；离线为命令参数）
     * @param period     统计周期
     * @param snapshotReady 玩家此刻是否在线；F3：在线快照由本方法在异步段读取，
     *                      {@code false} 时按离线处理（累计数走库内直查）。
     */
    public void generateSingle(CommandSender sender, UUID playerId, String displayName,
                               ReportPeriod period, boolean snapshotReady) {
        ReportSettings settings = captureSettings();
        runAsync(() -> {
            try {
                if (settings.respectPrivacy() && isHiddenInSquare(playerId)) {
                    sendOnMainThread(() -> sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "report.hidden")));
                    return;
                }
                cleanupOldReportsIfDue(settings);
                // F3：快照在此（异步段）读；取不到就退化为库内直查，不让整份报告失败。
                CumulativeSnapshot snap = snapshotReady ? readSnapshot(playerId) : null;
                SingleData data = collectSingle(playerId, displayName, period, settings, snap);
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
                BatchFetch fetch = queryBatchRows(period, settings);
                // round4 F3 / EH-03：先判"库挂了"，再判"没人学习"。顺序不可颠倒——
                // 失败时 rows 可能为空（部分行）或非空（中途失败），两种都不得当真实数据。
                if (!fetch.complete()) {
                    plugin.getLogger().warning("Batch report rows are incomplete for "
                            + period.from() + ".." + period.to()
                            + "; refusing to render a report from partial data.");
                    // FIX 4：这条失败原因原先直接嵌硬编码中文常量。失败原因是<b>聊天</b>，
                    // 走执行者语言（与同方法内其余 report.failed 调用点同口径）。
                    // 取值放在回主线程的 lambda 内：getSenderMessage 对玩家会读玩家语言，
                    // 本类其余聊天取词一律在主线程做（异步段只碰 DB/文件）。
                    sendOnMainThread(() -> sender.sendMessage(plugin.getMessageUtil()
                            .getSenderMessage(sender, "report.failed",
                                    "reason", chatIncompleteNotice(sender))));
                    return;
                }
                List<BatchRow> fetched = fetch.rows();
                final boolean truncated = fetched.size() > settings.batchMax();
                final List<BatchRow> rows = truncated
                        ? new ArrayList<>(fetched.subList(0, settings.batchMax()))
                        : fetched;
                if (rows.isEmpty()) {
                    // 只有 complete=true 且真的 0 行，才能说"没有学习数据"
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
        if (data.windowEmpty() || data.dataIncomplete()) {
            // EH-05：查询失败时不得对家长说"没有学习数据"（事实性谎报）。
            // FIX 4：这一句原本是硬编码中文常量，聊天摘要卡其余每一行都走
            // getSenderMessage（执行者语言），两者语言不一致时同一张卡会中英混排。
            // 聊天路径刻意用 getSenderMessage 而<b>不是</b>正文那套 report.language：
            // 卡片是给"敲命令的人"看的（可能是英文服主），正文是给家长看的（由
            // report.language 定），沿用本类既有的"两种受众两套语言来源"约定。
            sender.sendMessage(data.dataIncomplete()
                    ? ChatColor.RED + chatIncompleteNotice(sender)
                    : plugin.getMessageUtil().getSenderMessage(sender, "report.no-data"));
        }
    }

    // ============================================================
    //  数据采集（全部在异步线程执行；SQL 与参数绑定在 ReportRepository，
    //  每个仓储方法内部自持 connectionLock；日期边界换算仍在本类）
    // ============================================================

    private SingleData collectSingle(UUID playerId, String displayName, ReportPeriod period,
                                     ReportSettings settings, CumulativeSnapshot snapshot) {
        // EH-05：complete 标记原先止步于一行 WARNING，渲染层拿不到 → 失败被写成「无学习活动」
        ReportRepository.DailyRows q1 = reportRepository.queryDailyRows(
                playerId, period.from().toString(), period.to().toString());
        List<DailyRow> daily = new ArrayList<>();
        for (ReportRepository.DailyRow r : q1.rows()) {
            daily.add(new DailyRow(r.date(), r.answered(), r.correct(), r.score()));
        }
        // round4 EH-12-01：原先 dataIncomplete 只采样 Q1，Q2/Q3/Q4/Q5/Q6/Q8 的
        // null/0 回落被当真实数字印给家长（"累计正确词 0"）。现改为按"任一查询失败"
        // 逐位累积（|=，不覆盖），线下任一查询失败都会让整份报告打上不完整标记。
        boolean dataIncomplete = !q1.complete();

        // 终身汇总 + 库内名字（Q2）
        String dbName = null;
        long cumCorrect = 0L;
        long cumSessions = 0L;
        long cumScore = 0L;
        int cumConversations = 0;
        ReportRepository.LifetimeRows lifetime = reportRepository.fetchLifetimeOrFail(playerId);
        dataIncomplete |= !lifetime.complete();
        if (lifetime.row() != null) {
            dbName = lifetime.row().playerName();
            cumCorrect = lifetime.row().totalCorrect();
            cumSessions = lifetime.row().sessions();
            cumScore = lifetime.row().score();
            cumConversations = lifetime.row().conversations();
        }
        // 在线快照覆盖（plan §1.5）。DC-01：零值可能是读失败产物，累计量单调故取较大值。
        if (snapshot != null) {
            cumCorrect = Math.max(cumCorrect, snapshot.totalCorrect());
            cumSessions = Math.max(cumSessions, snapshot.sessions());
            cumScore = Math.max(cumScore, snapshot.score());
            cumConversations = Math.max(cumConversations, snapshot.conversations());
        }

        // 成就：总数 + 窗口内新解锁（Q3；unlocked_at 为毫秒长整）
        long windowStart = period.from().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        long windowEndExclusive = period.to().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        int unlockedDb = 0;
        int recentAchievements = 0;
        ReportRepository.AchievementRows ach =
                reportRepository.countAchievementsOrFail(playerId, windowStart, windowEndExclusive);
        dataIncomplete |= !ach.complete();
        if (ach.counts() != null) {
            unlockedDb = ach.counts().total();
            recentAchievements = ach.counts().recent();
        }
        // DC-01：零值快照不得盖掉库内真实解锁数（成就只增不减）。
        int unlocked = snapshot != null ? Math.max(unlockedDb, snapshot.unlockedCount()) : unlockedDb;

        // AI 对话：窗口内完成的对话会话数（Q4）。R9-H2（读取侧换算）：SQLite 的
        // timestamp 是 CURRENT_TIMESTAMP 写入的 UTC 字符串（P3-12 时区统一只统了
        // Java 侧、未统 DB 默认值——本处收口该裂缝的新缝口，写入侧不改）；边界按
        // "本地日 → UTC 区间 [start, nextDayStart)" 换算后比较。MySQL 侧 TIMESTAMP
        // 列自动按会话时区转换，维持本地字符串边界（含 23:59:59 上界）。
        String lowerBound;
        String upperBound;
        boolean exclusiveUpper;
        if (databaseManager.isSQLite()) {
            DateTimeFormatter utcFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            ZoneId sys = ZoneId.systemDefault();
            lowerBound = period.from().atStartOfDay(sys)
                    .withZoneSameInstant(ZoneOffset.UTC).format(utcFmt);
            upperBound = period.to().plusDays(1).atStartOfDay(sys)
                    .withZoneSameInstant(ZoneOffset.UTC).format(utcFmt);
            exclusiveUpper = true;
        } else {
            lowerBound = period.from() + " 00:00:00";
            upperBound = period.to() + " 23:59:59";
            exclusiveUpper = false;
        }
        // R6-A：SQL 片段选择（< / <=）已收口进仓储的两套完整 SQL；边界换算与
        // isSQLite 判定仍在本类。
        ReportRepository.ConversationCount conv = reportRepository.countAiConversationsOrFail(
                playerId, lowerBound, upperBound, exclusiveUpper);
        dataIncomplete |= !conv.complete();
        int recentConversations = conv.value();

        // 错题本 Top N（Q5，当前状态）
        ReportRepository.WeakWords weakRows = reportRepository.queryWeakWordsOrFail(
                playerId, settings.weakLimit());
        dataIncomplete |= !weakRows.complete();
        List<WeakWord> weak = decorateWeakWords(weakRows.rows());

        // 当前连续签到（Q6）
        ReportRepository.StreakValue streakValue = reportRepository.fetchCheckinStreakOrFail(playerId);
        dataIncomplete |= !streakValue.complete();
        int streak = streakValue.value();

        // 每日挑战完成度（Q8，窗口；完成行数/总行数）
        int challengeDone = 0;
        int challengeTotal = 0;
        ReportRepository.ChallengeRows challenge = reportRepository.countDailyChallengesOrFail(
                playerId, period.from().toString(), period.to().toString());
        dataIncomplete |= !challenge.complete();
        if (challenge.progress() != null) {
            challengeDone = challenge.progress().done();
            challengeTotal = challenge.progress().total();
        }

        // 名字优先级：在线（有快照）= 实时 Bukkit 名；离线 = player_data 的落盘名
        // （命令参数可能大小写不规范）；两者皆缺回落 uuid 前 8 位（既有惯例）。
        String name = snapshot != null
                ? firstNonBlank(displayName, dbName, playerId.toString().substring(0, 8))
                : firstNonBlank(dbName, displayName, playerId.toString().substring(0, 8));
        CumulativeOut cum = new CumulativeOut(cumCorrect, cumSessions, cumScore,
                cumConversations, recentConversations, unlocked, recentAchievements,
                Achievement.values().length, streak, challengeDone, challengeTotal);
        if (!q1.complete()) {
            plugin.getLogger().log(Level.WARNING, "Daily rows for " + playerId
                    + " are incomplete; the report will under-report this window.");
        }
        // EH-05：把查询完整性一并带出去（原先该标记在本方法内就被丢弃）
        return new SingleData(name, period, daily, weak, cum, daily.isEmpty(), dataIncomplete);
    }

    /** 把仓储原始错题行补上内存词库的中文义与难度（已删词回落纯英文）。 */
    private List<WeakWord> decorateWeakWords(List<ReportRepository.WeakWordRow> rows) {
        List<WeakWord> out = new ArrayList<>();
        for (ReportRepository.WeakWordRow row : rows) {
            String word = row.word();
            int wrongCount = row.wrongCount();
            // 词库内存索引（ConcurrentHashMap）只读查询，异步安全（plan §3.4）
            Word entry = wordManager != null
                    ? wordManager.getWord(word) : null;
            String chinese = (entry != null && entry.getChinese() != null
                    && !entry.getChinese().isBlank()) ? entry.getChinese() : null;
            String difficulty = entry != null ? entry.getDifficulty() : null;
            out.add(new WeakWord(word, wrongCount, chinese, difficulty));
        }
        return out;
    }

    /**
     * Q9：批量——窗口内有 player_sessions 行的玩家汇总（按答对数降序）。
     * 传 {@code batchMax + 1} 条探测截断；respect-privacy=true 时按与榜单/广场
     * 同款口径过滤隐身玩家（COALESCE(show_in_square,1)<>0）。
     */
    private BatchFetch queryBatchRows(ReportPeriod period, ReportSettings settings) {
        List<BatchRow> rows = new ArrayList<>();
        // R6-A：SQL 组装（含隐私 LEFT JOIN 分支）已收口进仓储；UUID 解析、脏行
        // 跳过与名字回落仍在本类判定。
        // round4 F3：走 queryBatchStatsOrFail 而不是旧 queryBatchStats——旧签名把
        // complete 位折叠成裸 List，SQL 失败退化成空 list，与"窗口内确实没人学习"
        // 不可区分，最终被渲染成 report.no-data（对家长的事实性谎报）。
        ReportRepository.BatchRows fetched = reportRepository.queryBatchStatsOrFail(
                period.from().toString(), period.to().toString(),
                settings.batchMax() + 1, settings.respectPrivacy());
        for (ReportRepository.BatchStatRow stat : fetched.rows()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(stat.uuid());
            } catch (IllegalArgumentException e) {
                continue; // 脏 uuid 行跳过，不拖垮整批
            }
            String name = stat.playerName();
            if (name == null || name.isBlank()) {
                name = uuid.toString().substring(0, 8);
            }
            rows.add(new BatchRow(uuid, name, stat.activeDays(), stat.answered(),
                    stat.correct(), stat.score()));
        }
        return new BatchFetch(rows, fetched.complete());
    }

    /**
     * 单报隐私检查（respect-privacy=true 时）：读 player_profiles.show_in_square。
     * 无行 = 从未改过开关 = 默认可见（COALESCE(...,1) 口径）。
     */
    private boolean isHiddenInSquare(UUID playerId) {
        return reportRepository.isHiddenInSquare(playerId);
    }

    // ============================================================
    //  文件写入（异步线程）
    // ============================================================

    /**
     * 单报落盘（round14 F2 + FIX 3）。
     *
     * <p>先在内存里把整份正文渲染完，再交给 {@link #writeTextFile(File, String)}：
     * 渲染期异常（{@code RuntimeException}）保持原有语义——冒泡到
     * {@code generateSingle} 的 {@code catch (Exception)} 走 {@code report.failed}，
     * 且此刻磁盘上<b>什么都没写</b>（不会留下半个文件）。
     */
    private ReportResult writeSingleFile(SingleData data, ReportSettings settings) {
        String name = data.playerName().replaceAll("[^A-Za-z0-9_-]", "_");
        String fileName = "report_" + name
                + "_" + data.period().from().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + data.period().to().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "_" + uniqueFileStamp() + ".txt";
        String content = renderSingle(data, settings);
        return writeTextFile(new File(reportsDir(), fileName), content);
    }

    /**
     * 批量报落盘（round14 F2 + FIX 3 + FIX 5）。
     *
     * <p>FIX 5：逐人错题在<b>这里</b>（渲染之前、异步线程、一次性顺序）预取，
     * {@link #renderBatch} 退化为纯函数、零 DB IO。原先的 N+1 是嵌在
     * {@code renderBatch} 的 for 循环里，每行一次 {@code queryWeakWordsOrFail}、
     * 每次都要抢一次 {@code connectionLock}——把写盘前的"最后一步"变成了
     * 最长持锁的一段。
     */
    private ReportResult writeBatchFile(List<BatchRow> rows, boolean truncated, ReportPeriod period,
                                        ReportSettings settings) {
        String fileName = "report_all_"
                + period.from().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + period.to().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "_" + uniqueFileStamp() + ".txt";
        Map<UUID, BatchWeak> weakByPlayer = prefetchBatchWeakWords(rows);
        String content = renderBatch(rows, truncated, period, settings, weakByPlayer);
        return writeTextFile(new File(reportsDir(), fileName), content);
    }

    /**
     * FIX 5（选项 a）：批量逐人错题的预取阶段。
     *
     * <p><b>为什么是"挪出去"而不是"合成一条 SQL"</b>：合成
     * {@code WHERE player_uuid IN (?,?,...)} 需要 {@code ReportRepository} 暴露批量
     * 方法，而该文件不在本批改动范围内（同批另有其他代理持有），四个仓方法
     * 也都各自钉着 {@code complete} 位语义。在只有两个文件的约束下，选 a：
     * 把查询<b>整体</b>从渲染器里搬到这里，渲染器变成纯函数。
     *
     * <p><b>诚实的边界</b>：这一步<b>没有</b>减少查询条数（仍是一人一条，受
     * {@code batch.max-players} 上限约束），它买到的是：渲染期零持锁 IO、
     * 失败标记与数据同时准备好、以及后续若要换单条 SQL 只需改这一个方法。
     *
     * <p>每条都返回记录（失败时 {@code complete=false} 且 {@code words} 为空），
     * 渲染器据此打"数据不完整"标记，绝不把读失败写成"该生没有错题"。
     */
    private Map<UUID, BatchWeak> prefetchBatchWeakWords(List<BatchRow> rows) {
        Map<UUID, BatchWeak> out = new LinkedHashMap<>();
        for (BatchRow row : rows) {
            ReportRepository.WeakWords weakRows =
                    reportRepository.queryWeakWordsOrFail(row.uuid(), BATCH_WEAK_LIMIT);
            out.put(row.uuid(),
                    new BatchWeak(decorateWeakWords(weakRows.rows()), weakRows.complete()));
        }
        return out;
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
     *
     * <p><b>FIX 2（round14 F2）：走 {@link AtomicFileWriter}，不再直写目标文件。</b>
     * 原实现 {@code Files.write(file, out)} 直接覆写目标：磁盘满 / 进程被杀 /
     * I/O 错误时会在 {@code exports/reports/} 里留下<b>半截报告</b>，而调用方
     * 无论如何都返回 {@code success=true}、打一条 {@code Report generated:}、
     * 再对管理员说"报告已保存: xxx"——管理员拿着一份半截 TXT 去给家长看，
     * 根本不知道数字被截断了。
     *
     * <p>现在：写同目录 {@code <target>.tmp} → 原子 rename。失败时目标文件保持原样
     * （新文件名不会与旧文件同名，所以"原样"即"没这个文件"），并且<b>返回值如实
     * 为失败</b>——成功日志与 {@code report.generated} 都不会再出现。
     *
     * <p>BOM 用 {@link #BOM} 常量而不是手工拼字节：{@code AtomicFileWriter}
     * 走 {@code Files.writeString(UTF_8)}，U+FEFF 按 UTF-8 编码正好是 EF BB BF，
     * 与原先的三字节写法逐字节等价（不必为了 BOM 去用 {@code writeStream}）。
     * 行序列同理：{@code Files.writeString} 不做任何换行转换，{@code \n → \r\n}
     * 仍由本方法自己替换，所以 BOM 只需一个前缀字符、不需要再包一层字节数组。
     */
    private ReportResult writeTextFile(File file, String content) {
        String windows = BOM + content.replace("\n", "\r\n");
        boolean written = AtomicFileWriter.write(file.toPath(), () -> windows, plugin.getLogger());
        if (!written) {
            // AtomicFileWriter 自己已记 SEVERE；这里补一条带目标路径的 WARNING，
            // 因为它的日志只有临时文件路径，运维排查时更需要知道"哪份报告没出来"。
            plugin.getLogger().warning("Failed to write report file: " + file.getAbsolutePath());
            return new ReportResult(false, null, "write failed: " + file.getName());
        }
        return new ReportResult(true, file, null);
    }

    /**
     * FIX 1（round14 F2）：按自然日惰性清理。
     *
     * <p>删除 {@code exports/reports/} 下属于本类的产物（{@code report_*.txt} 以及
     * 原子写残留的 {@code report_*.txt.tmp}）中修改时间超过 keep-days 的旧文件；
     * {@code keep-days<=0} 仍表示"清理关闭"（契约不变）。与 {@code cleanupOldChatHistory}
     * 同款产物侧清理，但闸门从"每进程一次"改成"每天一次"——见
     * {@link #lastPruneDate} 的字段注释。
     *
     * <p><b>为什么 keep-days<=0 的早退在 CAS 之前</b>：清理由报告生成顺带触发，
     * 若把"关闭"也写进 {@code lastPruneDate}，服主当天改了配置也仍要等到明天
     * 才第一次清理——早退放在闸门之前才能让配置改动当天生效。
     */
    private void cleanupOldReportsIfDue(ReportSettings settings) {
        if (settings.keepDays() <= 0) {
            return; // 显式关闭：不清理，也不占用当天的闸门
        }
        LocalDate today = LocalDate.now();
        LocalDate last = lastPruneDate.get();
        if (today.equals(last)) {
            return; // 今天已经清过
        }
        if (!lastPruneDate.compareAndSet(last, today)) {
            return; // 另一个异步线程刚跨日领走了本次清理
        }
        File[] files = reportsDir().listFiles(
                (dir, name) -> name.startsWith("report_") && isReportArtifact(name));
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - settings.keepDays() * 86400000L;
        for (File f : files) {
            // 只删"够旧"的：正在写入的 .tmp 是刚创建的，永远远新于 cutoff。
            if (f.lastModified() < cutoff && f.delete()) {
                plugin.getLogger().info("Old report deleted: " + f.getName());
            }
        }
    }

    /**
     * 本类落在 {@code exports/reports/} 的产物名（含原子写的残留临时文件）。
     *
     * <p>FIX 2 引入了新的失败形态：原子写崩在 rename 之前会留下
     * {@code report_xxx.txt.tmp}。若清理只看 {@code .txt}，这些半截文件永不回收，
     * FIX 1 的"防止盘满"就只覆盖了报告本身那一半。因此这里把两种后缀都算产物。
     */
    private static boolean isReportArtifact(String name) {
        if (!name.startsWith("report_")) {
            return false;
        }
        return name.endsWith(".txt") || name.endsWith(".txt" + AtomicFileWriter.TMP_SUFFIX);
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
        if (data.dataIncomplete()) {
            // EH-05：查询失败（含中途）都须留可见标记；中途失败的"数字偏小"更隐蔽，故与下方并列
            // FIX 4：改走 i18n（report.language 口径），不再嵌硬编码中文常量。
            sb.append("  ").append(incompleteNotice(settings)).append('\n');
        }
        if (data.windowEmpty() && !data.dataIncomplete()) {
            // plan §4.2：窗口零活动照常出报告，概览节后加一行提示（文件版模板，
            // 无 & 码/无 prefix——与聊天键 report.no-data 是两个受众的两种形态）。
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

    /**
     * 批量报告正文渲染（FIX 5：<b>纯函数，零 DB IO</b>）。
     *
     * <p>逐人错题由调用方在 {@link #prefetchBatchWeakWords} 里预取后经
     * {@code weakByPlayer} 传入。原先这里在 for 循环内直接调
     * {@code reportRepository.queryWeakWordsOrFail(...)}，即"渲染器持锁查库"——
     * 默认 {@code batch.max-players=50} 时是 50 次串行抢 {@code connectionLock}，
     * 把写盘前的最后一步变成了整条批量流程里最长的一段持锁时间（并拖慢周期性保存）。
     */
    private String renderBatch(List<BatchRow> rows, boolean truncated, ReportPeriod period,
                               ReportSettings settings, Map<UUID, BatchWeak> weakByPlayer) {
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
            // round4 EH-12-01：批量逐人错题同样走完整性位——读失败不能渲染成"该生没有错题"。
            // FIX 5：查询已由 writeBatchFile 预取（见方法 javadoc），这里只读内存 Map。
            // FIX 4：不完整标记走 i18n（report.language 口径）。
            BatchWeak weakEntry = weakByPlayer.get(row.uuid());
            if (weakEntry == null) {
                // 理论上走不到（预取覆盖 rows 的每个 uuid）。真出现说明渲染器与预取
                // 的键集脱钩了——按"不完整"渲染，绝不静默当成"没有错题"。
                weakEntry = new BatchWeak(List.of(), false);
            }
            if (!weakEntry.complete()) {
                sb.append("  ").append(incompleteNotice(settings)).append('\n');
            }
            List<WeakWord> weak = weakEntry.words();
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
        if (data.dataIncomplete()) {
            // EH-05：0 天 + 查询失败 ≠ "没有学习记录"
            // FIX 4：同上，走 report.language 的 i18n 查找。
            lines.add(incompleteNotice(settings));
            return lines;
        }
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

    /**
     * EH-05 + FIX 4（round14 F2）：数据不完整标记 —— 必须永远可见，绝不可回落成
     * no-data-file / advice-none。
     *
     * <p><b>为什么不再用 Java 常量</b>：原实现是硬编码中文 {@code INCOMPLETE_NOTICE}，
     * 而这个模块的正文语言由 {@code report.language} 决定。{@code report} 段的所有
     * 文案（标题、每一条明细、脚注）都走 {@link #t}，唯独这一句是常量 ——
     * {@code report.language: en} 时整份英文报告里会突兀地嵌一段中文，
     * 而它恰恰是全篇最需要被读懂的一句（"这些数字可能不准"）。
     *
     * <p><b>为什么用 {@link #t} 而不是 {@code MessageUtil}</b>：正文渲染的语言来源是
     * {@code report.language}（{@link #captureSettings} 里读的 {@code main} 配置），
     * 而 {@code MessageUtil#getSenderMessage} 用的是"执行者的玩家语言"。两者可以不同
     * （服主自己是 en、报告配成 zh 给家长看），走 MessageUtil 会让同一个文件里出现
     * 两种语言。这里刻意复用渲染器已有的 ({@code ReportSettings}, {@code t}) 这一条
     * 查找路径，不新增第二条。
     *
     * <p>key 缺失时 {@link #t} 会打 WARNING 并返回可见占位 {@code [?report.incomplete-notice]}
     * ——仍然是"永远可见"，不会静默成空行。
     *
     * <p><b>为什么要额外清掉 {@code {prefix}}</b>：这个 key 有两个读者 —— 文件正文
     * （本方法）与聊天摘要卡（走 {@link com.letslearnenglish.minecraftplugin.util.MessageUtil}，
     * 那里会自动把 {@code {prefix}} 填成服务器前缀）。文案作者若按聊天口径把它写成
     * {@code "{prefix}【数据不完整】…"}，正文里就会原样印出 {@code {prefix}} 这串噪音。
     * 正文侧主动清空该占位符，两个读者都能得到正确形态，而<b>不必要求文案作者记住
     * 这个键有两个消费者</b>。
     *
     * <p><b>为什么这里不翻译 {@code &} 颜色码</b>：{@code report.*} 段的文件模板
     * 按约定是纯文本（zh.yml 该段头的注释即如此声明），{@link #t} 对整段的其余键也
     * 从不翻译颜色码。若只对这一句做 {@code translateAlternateColorCodes}，任何合法的
     * 字面 {@code &} 都会被当成颜色码吃掉一个字符（实测 {@code "R&D"} → {@code "R"}，
     * {@code "A&B"} → {@code "A"}），而缺字比方括号占位符更难被发现。要与整段保持
     * 同一条约定，就不要在这里开特例。
     */
    private String incompleteNotice(ReportSettings settings) {
        return t(settings, "report.incomplete-notice").replace("{prefix}", "");
    }

    /**
     * FIX 4 的聊天侧取词：{@code report.incomplete-notice} 按<b>执行者语言</b>解析。
     *
     * <p>与正文侧（{@link #incompleteNotice(ReportSettings)}）分开是刻意的：两者的
     * 语言来源本来就不同（正文 {@code report.language}，聊天执行者语言），共用一个
     * 方法就必须把 {@code CommandSender} 塞进渲染器，渲染器反而被绑到 Bukkit 会话上。
     *
     * <p><b>必须主线程调用</b>：{@code getSenderMessage} 对玩家要读
     * {@code plugin.getPlayerLanguage(player)}。本类所有 {@code sendOnMainThread}
     * lambda 都在主线程执行，因此三个调用点都满足该前提。
     */
    private String chatIncompleteNotice(CommandSender sender) {
        return plugin.getMessageUtil().getSenderMessage(sender, "report.incomplete-notice");
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

    /**
     * FIX 3（round14 F2）：文件名里"人可读时间 + 进程内序号 + 随机段"的唯一戳。
     *
     * <p>原实现只有 {@code HHmmss}：同一秒内的两次生成拿到同一个文件名，
     * 第二次原子替换掉第一次，两条命令却都报成功（第一份报告静默消失）。
     *
     * <p>三段各自的用处：
     * <ul>
     *   <li>{@code HHmmss} —— 运维看文件名就能知道大概什么时候生成的（保持原有的
     *       人可读前缀，不因为修 bug 让运维失去这个信息）；</li>
     *   <li>{@link #fileSequence} —— 进程内单调递增，保证"同一秒内 N 次生成"彼此不同，
     *       且按文件名排序即生成先后顺序；</li>
     *   <li>随机段 —— 序号在重启后归零，若重启前后恰好在同一秒生成过报告，
     *       单靠序号仍会撞名；随机段把这个跨进程窗口也堵掉。</li>
     * </ul>
     *
     * <p>时间戳用 {@code HHmmss} 而非完整日期，是因为文件名里已经带了统计周期
     * （{@code yyyyMMdd-yyyyMMdd}），再叠日期只会把文件名撑得更长；
     * 但 {@code HHmmss} 单独看会跨天重复，所以唯一性完全由后两段保证。
     */
    private String uniqueFileStamp() {
        String time = new SimpleDateFormat("HHmmss").format(new Date());
        long seq = fileSequence.incrementAndGet();
        // 0x10000 上界 ⇒ 固定 4 位十六进制，文件名长度稳定、可被 glob 精确匹配。
        String random = String.format(Locale.ROOT, "%04x",
                ThreadLocalRandom.current().nextInt(0x10000));
        return time + "-" + String.format(Locale.ROOT, "%04d", seq) + "-" + random;
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

    /**
     * D2-5：同型帮助器见 util.SchedulerGuard（语义微差：本处带 fine 日志级别），
     * 本批未迁移。
     */
    private void runAsync(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (IllegalStateException e) {
            plugin.getLogger().fine("Report task skipped: scheduler unavailable (plugin disabling).");
        }
    }

    /** F3：在线快照只许在异步段读（cache miss 时同步查库）；失败退化为 null 走库内直查。 */
    private CumulativeSnapshot readSnapshot(UUID playerId) {
        try {
            PlayerDataStore store = plugin.getPlayerDataStore();
            PlayerDataStore.PlayerStats s = store.getPlayerStats(playerId);
            return new CumulativeSnapshot(s.getTotalCorrect(), s.getTotalSessions(), s.getTotalScore(),
                    store.getConversationCount(playerId),
                    plugin.getAchievementManager().getUnlockedCount(playerId));
        } catch (RuntimeException e) {
            return null;
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
