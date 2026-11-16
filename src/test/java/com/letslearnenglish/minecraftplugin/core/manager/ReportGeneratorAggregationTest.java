package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code ReportGenerator} 的聚合与渲染测试 —— 该 1321 行类此前<b>零测试覆盖</b>。
 *
 * <h2>为什么必须与 {@code ReportRepositoryTest} 分开</h2>
 * 仓储测的是"每条 SQL 是否取到正确的行"；本类测的是"这些行被算成什么数字、
 * 印成什么文本"。报表最常见的两类事故都在这一层：
 * <ol>
 *   <li><b>口径错</b>：分子分母不在同一个窗口（例如窗口内答对数除以终身答题数）；</li>
 *   <li><b>除零</b>：{@code 0/0} 在 {@code accuracyText} 里变成 {@code NaN%}。</li>
 * </ol>
 *
 * <h2>怎么跑起来（真 SQLite + 真 yml 模板）</h2>
 * 走 {@code /lea report} 的同一条路径：主线程入口 {@code generateSingle} →
 * 异步段（测试里同步执行）→ 真 SQLite 查询 → 真 {@code messages/zh.yml} 渲染 →
 * 落盘到 {@code @TempDir}。断言直接读<b>生成的 TXT</b>，而不是读私有方法 ——
 * 因为家长/老师看到的正是这份 TXT，中间任何一步错位都会体现在文本上。
 *
 * <h2>关键覆盖点</h2>
 * <ul>
 *   <li>{@code ReportPeriod.compute} 的三种口径（week / lastweek / Nd）与非法输入；</li>
 *   <li>窗口聚合：Σanswered / Σcorrect / Σscore 只算窗口内，正确率 = Σcorrect/Σanswered；</li>
 *   <li><b>窗口空 + 分母 0</b>：正确率必须是 {@code 0.0} 而不是 {@code NaN}；</li>
 *   <li>跨年/跨月/闰年边界；</li>
 *   <li>读失败 → 报告正文必须出现「数据不完整」，绝不出现「本期间没有学习数据」。</li>
 * </ul>
 */
class ReportGeneratorAggregationTest {

    private static final String PREFIX = "lle_";
    private static final UUID PLAYER =
            UUID.fromString("00000000-0000-0000-0000-00000000c001");

    @TempDir
    Path tempDir;

    private File dbFile;
    private DatabaseManager db;
    private ReportGenerator generator;
    private YamlConfiguration mainCfg;
    private ConsoleCommandSender sender;
    private AtomicReference<String> sent;

    @BeforeEach
    void setUp() throws Exception {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        Logger logger = Logger.getLogger("ReportGeneratorAggregationTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        mainCfg = new YamlConfiguration();
        mainCfg.set("database.type", "sqlite");
        mainCfg.set("database.sqlite.filename", "report-gen.db");
        mainCfg.set("database.mysql.table-prefix", PREFIX);
        mainCfg.set("report.enabled", true);
        mainCfg.set("report.language", "zh");
        mainCfg.set("report.weak-words-limit", 5);
        mainCfg.set("report.batch.max-players", 50);
        when(cm.getMainConfig()).thenReturn(mainCfg);
        // 真 yml：模板缺一个键就会渲染出 [?key] 占位符，那正是本类要能看见的信号。
        when(cm.getMessageConfig(anyString())).thenAnswer(inv ->
                loadBundled("messages/" + inv.getArgument(0) + ".yml"));
        when(plugin.getConfigManager()).thenReturn(cm);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        dbFile = new File(tempDir.toFile(), "report-gen.db");
        when(plugin.getDatabaseManager()).thenReturn(db);

        // 词库不可用：错题行的 chinese/difficulty 回落到"纯英文"分支（真实降级路径之一）。
        WordManagerImpl wordManager = mock(WordManagerImpl.class);
        when(wordManager.getWord(anyString())).thenReturn(null);
        generator = new ReportGenerator(plugin, db, wordManager);

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        // 异步段就地同步执行：断言要在 generateSingle 返回后立刻读文件。
        when(scheduler.runTaskAsynchronously(any(), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        when(scheduler.runTask(any(), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getMotd()).thenReturn("Test Server");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(desc.getName()).thenReturn("LetsLearnEnglish");
        when(plugin.getDescription()).thenReturn(desc);

        sent = new AtomicReference<>("");
        sender = mock(ConsoleCommandSender.class);
        when(sender.getName()).thenReturn("CONSOLE");
        org.mockito.Mockito.doAnswer(inv -> {
            sent.updateAndGet(prev -> prev + inv.getArgument(0) + "\n");
            return null;
        }).when(sender).sendMessage(anyString());
        // 聊天摘要卡走 MessageUtil：用「把 key 原样吐出来」的探针，便于断言走了哪条分支。
        com.letslearnenglish.minecraftplugin.util.MessageUtil mu =
                mock(com.letslearnenglish.minecraftplugin.util.MessageUtil.class);
        when(mu.getSenderMessage(any(), anyString(), any(String[].class)))
                .thenAnswer(inv -> "MSG:" + inv.getArgument(1));
        when(plugin.getMessageUtil()).thenReturn(mu);
        // 离线目标（snapshotReady=false）：累计数全部走库内直查，测试不依赖 PlayerDataStore。
        when(plugin.getPlayerDataStore()).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    private static FileConfiguration loadBundled(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "bundled resource missing: " + resource);
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private void insertSession(UUID id, String date, int answered, int correct, long score)
            throws Exception {
        exec("INSERT INTO " + PREFIX + "player_sessions"
                + " (uuid, session_date, words_count, correct_count, score) VALUES ('"
                + id + "', '" + date + "', " + answered + ", " + correct + ", " + score + ")");
    }

    private void insertPlayerData(UUID id, String name, long correct, long sessions, long score)
            throws Exception {
        exec("INSERT INTO " + PREFIX + "player_data (uuid, player_name, total_words_learned,"
                + " total_sessions, total_score, conversation_count) VALUES ('" + id + "', '"
                + name + "', " + correct + ", " + sessions + ", " + score + ", 0)");
    }

    /** 跑一次单报并返回落盘的 TXT 全文。 */
    private String generate(ReportGenerator.ReportPeriod period) throws Exception {
        generator.generateSingle(sender, PLAYER, "Tester", period, false);
        File[] files = reportsDir().listFiles((d, n) -> n.endsWith(".txt"));
        assertNotNull(files, "exports/reports 目录不存在");
        assertEquals(1, files.length,
                "应恰好生成 1 份报告，实际 " + files.length + "（聊天回执：" + sent.get().trim() + "）");
        return Files.readString(files[0].toPath(), StandardCharsets.UTF_8);
    }

    private File reportsDir() {
        return new File(tempDir.toFile(), "exports/reports");
    }

    /** 从正文里抓正确率那一行的百分数（如 "正确率：66.7（2/3）"）。 */
    private static String accuracyField(String text) {
        Matcher m = Pattern.compile("正确率：([^（\s]+)").matcher(text);
        assertTrue(m.find(), "正文里找不到正确率行：\n" + text);
        return m.group(1);
    }

    // ============================================================
    //  1. ReportPeriod.compute：纯函数，零依赖，最容易回归的地方
    // ============================================================

    @Test
    @DisplayName("Period：week = 本周一至今天（周一锚点，与 LearningCalendarGUI 同口径）")
    void periodWeekAnchorsOnMonday() {
        // 2026-01-08 是周四。
        LocalDate thursday = LocalDate.of(2026, 1, 8);
        ReportGenerator.ReportPeriod p =
                ReportGenerator.ReportPeriod.compute("week", thursday);
        assertNotNull(p);
        assertEquals(LocalDate.of(2026, 1, 5), p.from(), "应回退到本周一");
        assertEquals(thursday, p.to(), "终点是今天，不是周日");

        // 今天就是周一时，窗口应为单日 [today, today]。
        LocalDate monday = LocalDate.of(2026, 1, 5);
        ReportGenerator.ReportPeriod single =
                ReportGenerator.ReportPeriod.compute("week", monday);
        assertEquals(monday, single.from());
        assertEquals(monday, single.to());
        assertEquals(DayOfWeek.MONDAY, single.from().getDayOfWeek());
    }

    @Test
    @DisplayName("Period：lastweek = 上周一至上上周日（完整 7 天，且不含本周任何一天）")
    void periodLastWeekIsFullSevenDays() {
        LocalDate thursday = LocalDate.of(2026, 1, 8);
        ReportGenerator.ReportPeriod p =
                ReportGenerator.ReportPeriod.compute("lastweek", thursday);
        assertEquals(LocalDate.of(2025, 12, 29), p.from(), "上周一（跨年边界）");
        assertEquals(LocalDate.of(2026, 1, 4), p.to(), "上周日");
        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(p.from(), p.to()) + 1);

        // 本周一跑 lastweek 也要落在上一周，不能滑到本周。
        ReportGenerator.ReportPeriod atMonday = ReportGenerator.ReportPeriod.compute(
                "lastweek", LocalDate.of(2026, 1, 5));
        assertEquals(LocalDate.of(2025, 12, 29), atMonday.from());
        assertEquals(LocalDate.of(2026, 1, 4), atMonday.to());
    }

    @Test
    @DisplayName("Period：<N>d = 今天-(N-1) 至今天（含今天，共 N 天）")
    void periodDaysIsInclusive() {
        LocalDate today = LocalDate.of(2026, 1, 8);
        ReportGenerator.ReportPeriod p =
                ReportGenerator.ReportPeriod.compute("1d", today);
        assertEquals(today, p.from(), "1d 就是今天一天");
        assertEquals(today, p.to());

        ReportGenerator.ReportPeriod week = ReportGenerator.ReportPeriod.compute("7d", today);
        assertEquals(LocalDate.of(2026, 1, 2), week.from(), "7d 应含今天共 7 天");
        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(week.from(), week.to()) + 1);

        ReportGenerator.ReportPeriod max = ReportGenerator.ReportPeriod.compute("90d", today);
        assertEquals(LocalDate.of(2025, 10, 11), max.from(), "90 天是允许的上界");
    }

    @Test
    @DisplayName("Period：非法输入一律 null（0d / 91d / 空 / 乱码 / 大小写与空白归一）")
    void periodRejectsInvalidSpecs() {
        LocalDate today = LocalDate.of(2026, 1, 8);
        assertNull(ReportGenerator.ReportPeriod.compute(null, today));
        assertNull(ReportGenerator.ReportPeriod.compute("", today));
        assertNull(ReportGenerator.ReportPeriod.compute("   ", today));
        assertNull(ReportGenerator.ReportPeriod.compute("0d", today), "0 天下界外");
        assertNull(ReportGenerator.ReportPeriod.compute("91d", today), "91 天上界外");
        assertNull(ReportGenerator.ReportPeriod.compute("-1d", today));
        assertNull(ReportGenerator.ReportPeriod.compute("weekk", today));
        assertNull(ReportGenerator.ReportPeriod.compute("1234d", today), "超过 3 位数字");

        // 归一化：大小写与前后空白都要吃掉。
        assertNotNull(ReportGenerator.ReportPeriod.compute(" WEEK ", today));
        assertNotNull(ReportGenerator.ReportPeriod.compute("LastWeek", today));
        assertEquals(LocalDate.of(2026, 1, 5),
                ReportGenerator.ReportPeriod.compute(" 4D ", today).from());
    }

    // ============================================================
    //  2. 窗口聚合：数字只来自窗口内，且正确率分子分母同窗
    // ============================================================

    @Test
    @DisplayName("聚合：概览只算窗口内 —— 窗口外的行不得进入任何数字")
    void overviewAggregatesOnlyWithinWindow() throws Exception {
        // 窗口 = 2026-01-05 .. 2026-01-07
        insertSession(PLAYER, "2026-01-04", 100, 100, 1000L); // 窗口前，必须被排除
        insertSession(PLAYER, "2026-01-05", 10, 6, 60L);
        insertSession(PLAYER, "2026-01-06", 10, 8, 80L);
        insertSession(PLAYER, "2026-01-07", 10, 4, 40L);
        insertSession(PLAYER, "2026-01-08", 100, 100, 1000L); // 窗口后，必须被排除
        insertPlayerData(PLAYER, "Tester", 500L, 99L, 9999L);

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7)));

        assertTrue(text.contains("学习天数：3 天"),
                "窗口内恰好 3 天；含窗口外会变成 5 天。正文：\n" + text);
        assertTrue(text.contains("答对单词：18 个"),
                "6+8+4 = 18；若把窗口外的 100 算进来会变成 218");
        assertTrue(text.contains("正确率：60.0（18/30）"),
                "正确率 = Σcorrect/Σanswered = 18/30 = 60.0%；"
                        + "若分母用了终身值会变成别的数。实际：" + accuracyField(text));
        assertTrue(text.contains("学习得分：180 分"), "60+80+40 = 180");
        // 每日明细必须逐日一行，且升序。断言只取「每日明细」节之后的部分：
        // 周期行的 "2026-01-05 至 2026-01-07" 也含日期串，混在一起比 indexOf 会误判。
        int detailStart = text.indexOf("【每日明细】");
        assertTrue(detailStart >= 0, "窗口非空时必须渲染每日明细节。正文：\n" + text);
        String detail = text.substring(detailStart);
        assertTrue(detail.contains("01-05"), "明细应含 01-05。明细节：\n" + detail);
        assertTrue(detail.contains("01-06"));
        assertTrue(detail.contains("01-07"));
        assertTrue(detail.indexOf("01-05") < detail.indexOf("01-06"),
                "明细必须升序：01-05 应排在 01-06 之前。明细节：\n" + detail);
        assertTrue(detail.indexOf("01-06") < detail.indexOf("01-07"),
                "明细必须升序：01-06 应排在 01-07 之前。明细节：\n" + detail);
        assertFalse(detail.contains("01-04"), "窗口前的日期不得出现在明细里");
        assertFalse(detail.contains("01-08"), "窗口后的日期不得出现在明细里");
    }
    @Test
    @DisplayName("除零：窗口零行时正确率是 0.0，不是 NaN 也不崩")
    void emptyWindowProducesZeroAccuracyNotNaN() throws Exception {
        insertPlayerData(PLAYER, "Tester", 500L, 99L, 9999L);

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7)));

        // accuracyText 有 answered > 0 守卫；这道断言钉住它不被"优化"掉。
        assertEquals("0.0", accuracyField(text),
                "分母为 0 时必须给 0.0 —— 未守卫的 double 除法会渲染成 NaN，"
                        + "家长会看到「正确率：NaN」。实际正文：\n" + text);
        assertFalse(text.toLowerCase().contains("nan"), "正文里不得出现 NaN");
        assertTrue(text.contains("本期间没有学习数据"),
                "零行且无读失败 → 应给「没有学习数据」提示行");
        assertFalse(text.contains("数据不完整"), "零行不是读失败，不该打不完整标记");
        // 零行时每日明细整节省略；错题节也应为空。
        assertFalse(text.contains("【每日明细】"), "窗口为空时明细节应整节省略");
    }

    @Test
    @DisplayName("除零：有行但 answered=0 时正确率仍是 0.0（不是 NaN）")
    void zeroAnsweredWithRowsProducesZeroAccuracy() throws Exception {
        // words_count=0 的行在真实数据里可能出现（中断局补记时 correct+incorrect==0 不写，
        // 但老库/手工修库可留下这种行）。
        insertSession(PLAYER, "2026-01-05", 0, 0, 0L);

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5)));

        assertEquals("0.0", accuracyField(text),
                "Σanswered=0 时同样必须 0.0。实际正文：\n" + text);
        assertFalse(text.toLowerCase().contains("nan"));
        assertTrue(text.contains("学习天数：1 天"), "有一行就算一天");
    }

    // ============================================================
    //  3. 时间边界：跨年 / 跨月 / 闰年
    // ============================================================

    @Test
    @DisplayName("边界：跨年窗口把 12-31 与 01-01 都算进来")
    void windowCrossingNewYearIncludesBothSides() throws Exception {
        insertSession(PLAYER, "2025-12-31", 10, 5, 50L);
        insertSession(PLAYER, "2026-01-01", 10, 7, 70L);
        insertSession(PLAYER, "2026-01-02", 10, 9, 90L); // 窗口外

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)));

        assertTrue(text.contains("学习天数：2 天"), "跨年两天都要算进来。正文：\n" + text);
        assertTrue(text.contains("答对单词：12 个"), "5+7 = 12");
        assertTrue(text.contains("2025-12-31 至 2026-01-01"),
                "周期行要如实反映跨年区间");
    }

    @Test
    @DisplayName("边界：闰年 2 月 29 日是合法行，且不污染相邻日")
    void leapDayIsHandled() throws Exception {
        insertSession(PLAYER, "2024-02-28", 10, 1, 10L);
        insertSession(PLAYER, "2024-02-29", 10, 2, 20L);
        insertSession(PLAYER, "2024-03-01", 10, 3, 30L);

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2024, 2, 28), LocalDate.of(2024, 2, 29)));

        assertTrue(text.contains("学习天数：2 天"), "闰日必须被正常解析并计入。正文：\n" + text);
        assertTrue(text.contains("答对单词：3 个"), "1+2 = 3");
        assertTrue(text.contains("02-29"), "闰日应出现在每日明细");
        assertFalse(text.contains("03-01"), "3 月 1 日在窗口外");
    }

    @Test
    @DisplayName("边界：90 天窗口（上界）能正常生成")
    void ninetyDayWindowWorks() throws Exception {
        LocalDate today = LocalDate.now();
        insertSession(PLAYER, today.toString(), 10, 10, 100L);
        insertSession(PLAYER, today.minusDays(89).toString(), 10, 5, 50L);

        ReportGenerator.ReportPeriod p =
                ReportGenerator.ReportPeriod.compute("90d", today);
        assertNotNull(p);
        String text = generate(p);
        assertTrue(text.contains("学习天数：2 天"), "90d 应覆盖最远那天的行。正文：\n" + text);
    }

    // ============================================================
    //  4. 读失败：不得把"库挂了"渲染成"没学习"
    // ============================================================

    @Test
    @DisplayName("读失败：正文必须打「数据不完整」，绝不能写「本期间没有学习数据」")
    void readFailureRendersIncompleteNoticeNotNoData() throws Exception {
        exec("ALTER TABLE " + PREFIX + "player_sessions RENAME TO " + PREFIX + "player_sessions__x");

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7)));

        assertTrue(text.contains("数据不完整"),
                "读失败必须在正文留下可见标记。正文：\n" + text);
        assertFalse(text.contains("本期间没有学习数据"),
                "「库挂了」被渲染成「没学习」是对家长的事实性谎报（EH-05 的原始缺陷）");
        assertTrue(sent.get().contains("MSG:report.incomplete-notice"),
                "聊天摘要卡也必须走不完整分支而不是 report.no-data。实际回执：\n" + sent.get());
    }

    @Test
    @DisplayName("读失败：窗口内其实有数据但查询失败时，行数为 0 但必须带不完整标记")
    void readFailureDoesNotSilentlyUnderReport() throws Exception {
        insertSession(PLAYER, "2026-01-05", 10, 10, 100L);
        exec("ALTER TABLE " + PREFIX + "player_sessions RENAME TO " + PREFIX + "player_sessions__y");

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7)));
        assertTrue(text.contains("数据不完整"), "正文：\n" + text);
        assertTrue(text.contains("学习天数：0 天"), "失败时行数为 0（但已标记不完整）");
    }

    // ============================================================
    //  5. 批量：截断、隐私
    // ============================================================

    @Test
    @DisplayName("批量：窗口内 0 人 → 不出文件，只回 report.no-data")
    void batchWithNoPlayersProducesNoFile() {
        generator.generateBatch(sender,
                new ReportGenerator.ReportPeriod(LocalDate.of(2026, 1, 5),
                        LocalDate.of(2026, 1, 7)));

        File[] files = reportsDir().listFiles((d, n) -> n.endsWith(".txt"));
        assertTrue(files == null || files.length == 0, "0 人时不应落盘任何文件");
        assertTrue(sent.get().contains("MSG:report.no-data"),
                "应回 report.no-data。实际回执：\n" + sent.get());
    }

    @Test
    @DisplayName("批量：超过 max-players 时截断并标注（按答对数取前 N）")
    void batchTruncatesAndAnnotates() throws Exception {
        mainCfg.set("report.batch.max-players", 2);
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.fromString("00000000-0000-0000-0000-0000000d00" + i);
            exec("INSERT INTO " + PREFIX + "player_data (uuid, player_name) VALUES ('"
                    + id + "', 'P" + i + "')");
            insertSession(id, "2026-01-05", 10, i * 3 + 1, (i * 3 + 1) * 10L);
        }

        generator.generateBatch(sender, new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7)));

        File[] files = reportsDir().listFiles((d, n) -> n.endsWith(".txt"));
        assertNotNull(files);
        assertEquals(1, files.length);
        String text = Files.readString(files[0].toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("共 2 位学生"), "应只含前 2 位。正文：\n" + text);
        assertTrue(text.contains("仅显示前 2 位"), "必须标注截断，否则服主以为全在这");
        assertTrue(text.contains("P3"), "答对最多的应入选");
        assertTrue(text.contains("P2"));
        assertFalse(text.contains("P0"), "答对最少的应被截断");
    }

    @Test
    @DisplayName("批量：隐私开关为 true 时隐身玩家不进报告")
    void batchRespectsPrivacyWhenEnabled() throws Exception {
        mainCfg.set("report.respect-privacy", true);
        UUID hidden = UUID.fromString("00000000-0000-0000-0000-00000000dead");
        exec("INSERT INTO " + PREFIX + "player_data (uuid, player_name) VALUES ('"
                + PLAYER + "', 'Visible')");
        exec("INSERT INTO " + PREFIX + "player_data (uuid, player_name) VALUES ('"
                + hidden + "', 'Hidden')");
        exec("INSERT INTO " + PREFIX + "player_profiles (uuid, show_in_square) VALUES ('"
                + hidden + "', 0)");
        insertSession(PLAYER, "2026-01-05", 10, 10, 100L);
        insertSession(hidden, "2026-01-05", 10, 10, 100L);

        generator.generateBatch(sender, new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7)));

        File[] files = reportsDir().listFiles((d, n) -> n.endsWith(".txt"));
        assertNotNull(files);
        String text = Files.readString(files[0].toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("Visible"));
        assertFalse(text.contains("Hidden"),
                "respect-privacy=true 时隐身玩家不得出现在批量报告。正文：\n" + text);
    }

    @Test
    @DisplayName("单报：respect-privacy=true 且玩家隐身 → 拒发，不落盘")
    void singleReportRefusedForHiddenPlayer() throws Exception {
        mainCfg.set("report.respect-privacy", true);
        exec("INSERT INTO " + PREFIX + "player_profiles (uuid, show_in_square) VALUES ('"
                + PLAYER + "', 0)");
        insertSession(PLAYER, "2026-01-05", 10, 10, 100L);

        generator.generateSingle(sender, PLAYER, "Tester",
                new ReportGenerator.ReportPeriod(LocalDate.of(2026, 1, 5),
                        LocalDate.of(2026, 1, 7)), false);

        File[] files = reportsDir().listFiles((d, n) -> n.endsWith(".txt"));
        assertTrue(files == null || files.length == 0, "隐身玩家不应生成报告文件");
        assertTrue(sent.get().contains("MSG:report.hidden"),
                "应回 report.hidden。实际回执：\n" + sent.get());
    }

    // ============================================================
    //  6. 趋势建议：前后半窗口比较（TREND_THRESHOLD = 2 个百分点）
    // ============================================================

    @Test
    @DisplayName("趋势：正确率明显上升时出现 advice-up")
    void trendAdviceUpWhenAccuracyImproves() throws Exception {
        // 6 天窗口（01-05..01-10），mid = from + 3 = 01-08。
        // 前半（05,06,07）各答对 2/10 = 20%；后半（08,09,10）各答对 9/10 = 90%。
        for (int d = 5; d <= 7; d++) {
            insertSession(PLAYER, String.format("2026-01-%02d", d), 10, 2, 20L);
        }
        for (int d = 8; d <= 10; d++) {
            insertSession(PLAYER, String.format("2026-01-%02d", d), 10, 9, 90L);
        }

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10)));
        assertTrue(text.contains("正确率在提升"),
                "前半 20% → 后半 90%，差 70 个百分点，必须给上升提示。正文：\n" + text);
    }

    @Test
    @DisplayName("趋势：正确率明显下降时出现 advice-down")
    void trendAdviceDownWhenAccuracyDrops() throws Exception {
        for (int d = 5; d <= 7; d++) {
            insertSession(PLAYER, String.format("2026-01-%02d", d), 10, 9, 90L);
        }
        for (int d = 8; d <= 10; d++) {
            insertSession(PLAYER, String.format("2026-01-%02d", d), 10, 2, 20L);
        }

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10)));
        assertTrue(text.contains("正确率有所下降"), "正文：\n" + text);
    }

    @Test
    @DisplayName("趋势：任一侧活跃天数不足 2 天时不出趋势结论（不拿单点做趋势）")
    void trendAdviceSuppressedWhenSideHasSingleDay() throws Exception {
        // 前半只有 1 天（01-05），后半 3 天 —— firstDays < 2 → 不给趋势。
        insertSession(PLAYER, "2026-01-05", 10, 0, 0L);
        for (int d = 8; d <= 10; d++) {
            insertSession(PLAYER, String.format("2026-01-%02d", d), 10, 10, 100L);
        }

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10)));
        assertFalse(text.contains("正确率在提升"),
                "前半只有 1 个数据点，不足以判定趋势。正文：\n" + text);
        assertFalse(text.contains("正确率有所下降"));
    }

    // ============================================================
    //  7. 累计成果节：库内终身值与 achTotal 的口径
    // ============================================================

    @Test
    @DisplayName("累计：终身值来自 player_data，与窗口数字互不影响")
    void cumulativeUsesLifetimeValuesNotWindowValues() throws Exception {
        insertPlayerData(PLAYER, "Tester", 500L, 42L, 9999L);
        insertSession(PLAYER, "2026-01-05", 10, 6, 60L);

        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5)));

        assertTrue(text.contains("答对单词：6 个"), "概览是窗口口径");
        assertTrue(text.contains("累计答对 500 个单词"),
                "累计节是终身口径（player_data.total_words_learned）。正文：\n" + text);
        assertTrue(text.contains("共完成 42 次学习会话"));
    }

    @Test
    @DisplayName("累计：成就分母恒为枚举总数（22），与配置里启用了几个无关")
    void achievementDenominatorIsEnumSize() throws Exception {
        insertPlayerData(PLAYER, "Tester", 1L, 1L, 1L);
        String text = generate(new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5)));

        // Achievement 枚举当前 22 个。分母写死枚举长度而不是"启用的成就数"——
        // 若该断言变红，说明枚举增删了，请同步确认 i18n 与 ProfileGUI 的分母。
        assertTrue(text.contains("/22 个"),
                "成就分母应为枚举长度 22。正文：\n" + text);
    }

    @Test
    @DisplayName("隐私 fail-closed：respect-privacy=true 且隐私读取失败 → 不出报告（不把隐身玩家当公开）")
    void privacyReadFailureRefusesToEmitReport() throws Exception {
        mainCfg.set("report.respect-privacy", true);
        insertSession(PLAYER, "2026-01-05", 10, 10, 100L);
        // 隐私表消失 → isHiddenInSquare 抛 IllegalStateException（fail-closed）。
        exec("ALTER TABLE " + PREFIX + "player_profiles RENAME TO " + PREFIX + "player_profiles__z");

        generator.generateSingle(sender, PLAYER, "Tester",
                new ReportGenerator.ReportPeriod(LocalDate.of(2026, 1, 5),
                        LocalDate.of(2026, 1, 7)), false);

        File[] files = reportsDir().listFiles((d, n) -> n.endsWith(".txt"));
        assertTrue(files == null || files.length == 0,
                "隐私读失败时绝不能落盘报告 —— 那等于把隐身玩家的数据发出去");
        assertTrue(sent.get().contains("MSG:report.failed"),
                "应走 report.failed（而不是照常生成）。实际回执：\n" + sent.get());
        assertFalse(sent.get().contains("MSG:report.generated"),
                "不得出现「报告已保存」");
    }

    // ============================================================
    //  ======== round20 独立复核新增（audit-specialist） ========
    //
    //  三条缺口，都是原 22 例按构造方式测不到的：
    //   (G1) 窗口边界全部用**常量日期**，从没用过 LocalDate.now() 相对日期 ——
    //        而生产路径的周期永远是相对今天算的（week / lastweek / Nd）。
    //        "30 天窗口到底含不含 30 天前那天"这类上界问题在原套件里没有用例。
    //   (G2) AI 对话窗口的**时区换算**从没在任何时区下测过。d3 那条仓储测试复刻了
    //        换算公式，但公式若在 ReportGenerator 里被改错，它照样绿 —— 那是自证。
    //        本类补一条端到端（设默认时区 +08:00，造 UTC 字符串行，断言正文轮数）。
    //   (G3) 分母为 0 的**批量**路径：单报的 accuracyText 有守卫（原套件已测），
    //        批量表走同一个方法但它是逐人渲染的 user 数据驱动，未测过 answered=0 的行。
    // ============================================================

    /**
     * 【G1】相对日期窗口：30 天周期含"30 天前"那天，不含"31 天前"那天。
     *
     * <p>生产路径上所有周期都从 {@code LocalDate.now()} 反推（{@code ReportPeriod.compute}），
     * 而原 22 例全部用常量日期（2026-01-05 之类）。"边界那天到底含不含"是 SQL 的
     * {@code >=} / {@code <=} 决定的，但只有在<b>相对今天</b>的窗口上才暴露成真实故障：
     * 服用久了，一个 off-by-one 会让家长看到"学习天数 29 天"而孩子明明学了 30 天。
     */
    @Test
    @DisplayName("【G1】30 天相对窗口：含 today-29，不含 today-30（闭区间两端各验一次）")
    void relativeThirtyDayWindowBoundaries() throws Exception {
        LocalDate today = LocalDate.now();
        // 30d = today-29 .. today，共 30 天
        insertSession(PLAYER, today.toString(), 10, 10, 100L);              // 右端点：含
        insertSession(PLAYER, today.minusDays(29).toString(), 10, 5, 50L);  // 左端点：含
        insertSession(PLAYER, today.minusDays(30).toString(), 10, 99, 999L); // 左端点外 1 天：不含
        insertSession(PLAYER, today.minusDays(365).toString(), 10, 99, 999L); // 去年：不含

        ReportGenerator.ReportPeriod p = ReportGenerator.ReportPeriod.compute("30d", today);
        assertNotNull(p);
        assertEquals(today.minusDays(29), p.from());
        assertEquals(today, p.to());

        String text = generate(p);
        assertTrue(text.contains("学习天数：2 天"),
                "30 天窗口应恰好 2 天（today-29 与 today）。"
                        + "把 `session_date >= ?` 改成 `>` 会漏掉 today-29 → 1 天；"
                        + "把 30d 算成 today-30 起步 → 3 天（多算左端点外那天）。正文：\n" + text);
        assertTrue(text.contains("答对单词：15 个"), "10 + 5 = 15；漏算 today-29 会变成 10");
        assertTrue(text.contains("正确率：75.0（15/20）"), "分子分母必须同窗口");
    }

    /**
     * 【G1】1 天周期 = 今天单日：昨天与今天都必须分开算清。
     *
     * <p>{@code 1d} 是 {@code ReportPeriod.compute} 的退化边界（{@code today.minusDays(0)}）。
     * 若实现写成 {@code today.minusDays(days)} 而不是 {@code days-1}，{@code 1d} 就会变成
     * "昨天到今天"两天 —— 家长看到的学习天数直接翻倍。
     */
    @Test
    @DisplayName("【G1】1d 相对窗口：只含今天，昨天不算")
    void relativeOneDayWindowIsTodayOnly() throws Exception {
        LocalDate today = LocalDate.now();
        insertSession(PLAYER, today.toString(), 10, 8, 80L);
        insertSession(PLAYER, today.minusDays(1).toString(), 100, 100, 1000L); // 昨天

        String text = generate(ReportGenerator.ReportPeriod.compute("1d", today));
        assertTrue(text.contains("学习天数：1 天"),
                "1d 只含今天。若实现用 today.minusDays(1) 起算会得到 2 天。正文：\n" + text);
        assertTrue(text.contains("答对单词：8 个"), "8（不是 108）");
    }

    /**
     * 【G2 端到端】AI 对话窗口的时区换算：+08:00 服上，本地日正确落到 UTC 区间。
     *
     * <p>这条是真正守住 {@code ReportGenerator:462-469} 的换算 —— 仓储层那条
     * {@code d3SqliteBranchUtcWindowConversionIsCorrect} 在测试里自己复刻了公式，
     * 若 ReportGenerator 的换算被改错，它仍然绿（那是自证）。这里改成端到端：
     * 设默认时区为 +08:00，往 {@code ai_chat_history} 写 <b>UTC 字符串</b>（与
     * {@code CURRENT_TIMESTAMP} 同口径），断言正文里"本期 N 轮"。
     *
     * <p><b>为什么会错</b>：SQLite 的 {@code timestamp} 是 UTC，本地日边界必须换算。
     * 换算方向反了 / 忘了换算，跨零点那批对话会整体偏移 8 小时。
     *
     * <p><b>构造要点</b>：必须让"不换算（直接用本地字符串比 UTC 列）"与"换算成 UTC
     * 区间"给出<b>不同</b>答案，否则测试是假绿。{@code leak} 行 UTC 01-10 20:00
     * = 本地 01-11 04:00，本地视角在窗外，但裸字符串 "2026-01-10 20:00:00" 落在
     * 本地边界 [01-10 00:00, 01-11 00:00) 内 —— 不换算就会把它错算进 01-10。
     * （首版用 atStart/inside/atEnd 三行构造，实测对
     * {@code withZoneSameInstant(UTC)→withZoneSameInstant(sys)} 这个变异是绿的，
     * 因为那三行的裸字符串与 UTC 区间恰好给出同一计数。已按变异结果重写。）
     */
    @Test
    @DisplayName("【G2】+08:00 时区：本地日 01-10 的 AI 对话窗口正确落到 UTC 区间（跨零点不错位）")
    void timezoneConversionForAiChatWindow() throws Exception {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals("Asia/Shanghai", java.time.ZoneId.systemDefault().getId(),
                    "测试前提：默认时区必须是 +08:00");

            // 本地日 2026-01-10 (+08) == UTC [2026-01-09 16:00, 2026-01-10 16:00)
            insertChatRow(PLAYER, "leak", "2026-01-10 20:00:00");   // 本地 01-11 04:00 — 窗外
            insertChatRow(PLAYER, "inside", "2026-01-10 02:00:00"); // 本地 01-10 10:00 — 窗内
            insertChatRow(PLAYER, "before", "2026-01-09 15:59:59"); // 本地 01-09 23:59:59 — 窗外
            insertChatRow(PLAYER, "atEnd", "2026-01-10 16:00:00");  // 本地 01-11 00:00:00 — 窗外
            insertChatRow(PLAYER, "ancient", "2025-01-10 02:00:00"); // 去年 — 窗外

            String text = generate(new ReportGenerator.ReportPeriod(
                    LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 10)));

            assertTrue(text.contains("本期 1 轮"),
                    "本地日 01-10 只应含 inside 一行（本地 01-10 10:00）。"
                            + "漏掉 UTC 换算（用本地字符串直接比 UTC 列）会把 leak 也算进来 → 2 轮；"
                            + "换算方向反了 → 0 轮。实际正文：\n" + text);
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    /** 往 ai_chat_history 写一行（UTC 字符串，与 CURRENT_TIMESTAMP 同口径）。 */
    private void insertChatRow(UUID id, String sessionId, String utcTimestamp) throws Exception {
        exec("INSERT INTO " + PREFIX + "ai_chat_history"
                + " (player_uuid, session_id, role, content, timestamp) VALUES ('"
                + id + "', '" + sessionId + "', 'user', 'x', '" + utcTimestamp + "')");
    }

    /**
     * 【G3】批量逐人小节里 answered=0 的那一行：正确率必须是 0.0，不是 NaN。
     *
     * <p>单报的零守卫原套件已测（{@code emptyWindowProducesZeroAccuracyNotNaN}），
     * 但批量走的是<b>同一个</b> {@code accuracyText} 却被逐人数据驱动 —— 某一行的
     * {@code Σwords_count = 0}（老库/手工修库留下的空行，或 {@code recordSession} 写
     * 0 题的补记）会让那一行渲染成 NaN，而单报被测的那条路径永远碰不到这个入参。
     */
    @Test
    @DisplayName("【G3】批量逐人行 answered=0 时正确率是 0.0（NaN 只会在这一行出现）")
    void batchRowWithZeroAnsweredRendersZeroAccuracy() throws Exception {
        exec("INSERT INTO " + PREFIX + "player_data (uuid, player_name) VALUES ('"
                + PLAYER + "', 'ZeroRow')");
        insertSession(PLAYER, "2026-01-05", 0, 0, 0L); // 有行但零答题

        generator.generateBatch(sender, new ReportGenerator.ReportPeriod(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7)));

        File[] files = reportsDir().listFiles((d, n) -> n.endsWith(".txt"));
        assertNotNull(files);
        assertEquals(1, files.length);
        String text = Files.readString(files[0].toPath(), StandardCharsets.UTF_8);
        assertFalse(text.toLowerCase().contains("nan"),
                "批量表不得出现 NaN —— 家长会看到「正确率 NaN」。正文：\n" + text);
        assertTrue(text.contains("0.0"), "零答题行的正确率应为 0.0。正文：\n" + text);
        assertTrue(text.contains("ZeroRow"));
    }
}
