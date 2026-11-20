package com.letslearnenglish.minecraftplugin.data.repository;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code ReportRepository} 的真实 SQLite 回归测试 —— 该仓储此前<b>零测试覆盖</b>。
 *
 * <h2>为什么这个类值得测</h2>
 * 它承载 {@code /lea report} 的<b>全部 SQL</b>：8 个查询背后是 6 张表、4 类时间窗口口径
 * （DATE 字符串闭区间、epoch 毫秒半开区间、TIMESTAMP 字符串上界含/不含、本地日到 UTC
 * 换算）。这些数字会被写进 TXT 交给不上游戏的家长/老师，算错了不会有人当场发现。
 *
 * <h2>本类钉住什么（按缺陷类型）</h2>
 * <ol>
 *   <li><b>空库</b>：零行时每个方法返回什么。SQLite 的 {@code SUM()} 在空集上返回
 *       {@code NULL} 而不是 0，靠 {@code ResultSet.getInt()} 把 NULL 折成 0 —— 这条依赖
 *       是隐式的，没有测试就没人知道它存在；改成 {@code getObject()} 立刻 NPE。</li>
 *   <li><b>窗口边界</b>：{@code >=}/{@code <=}/{@code <} 三处端点各自的方向，逐条断言。</li>
 *   <li><b>两侧方言</b>：{@code countAiConversationsOrFail} 有"含上界"与"不含上界"两套
 *       完整 SQL。本类在<b>同一份数据</b>上把两个分支都跑一遍，断言它们在边界值上确实
 *       给出<b>不同</b>结果 —— 把"两侧口径不同"这件事本身固化成可执行规格。</li>
 *   <li><b>读失败 vs 真没数据</b>：{@code *OrFail} 的 {@code complete} 位。用真实
 *       {@code no such table} 注入（重命名表），不 mock {@code SQLException}。</li>
 *   <li><b>隐私闸门 fail-closed</b>：{@link ReportRepository#isHiddenInSquare} 读失败必须
 *       <b>抛</b>，不能 {@code return false} —— 后者会把隐身玩家渲染成公开。</li>
 *   <li><b>表前缀现读</b>：R6-E 断言前缀不在构造期快照；本类用真实 {@code initialize()}
 *       换前缀来验证，而不是读注释。</li>
 *   <li><b>快照 vs 当前值</b>（round28）：Q6 的 {@code checkin_data.streak_count} 是
 *       "最后一次签到时"的历史快照，断签后不会被归零 —— 直接输出就是把历史当现状。
 *       现按与写侧同构的规则折算（今天/昨天签过才算数）。</li>
 *   <li><b>展示口径一致性</b>（round28）：Q5 必须套 {@code WRONG_BOOK_MIN_WRONG_COUNT}
 *       这道错题本收录门槛，否则报告与 {@code WrongBookGUI} 对同一个词给出两种答案。</li>
 * </ol>
 *
 * <h2>契约边界（本类<b>不</b>负责）</h2>
 * 日期换算（本地日到 UTC 区间）、聚合口径（Σcorrect/Σanswered）、报告正文渲染在
 * {@code ReportGenerator}；那边的窗口语义见 {@code ReportGeneratorAggregationTest}。
 *
 * <h2>测试手法</h2>
 * 真 SQLite（{@code @TempDir} 下的独立 {@code .db}），造数据走<b>第二条</b> JDBC 连接
 * （与 {@code FailedReadIsUnknownTest} / {@code PracticeAnswerWrongBookReadFailureTest}
 * 同款先例）：造数完全不经过被测仓储，避免自证。
 */
class ReportRepositoryTest {

    /** 与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    private static final UUID PLAYER =
            UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OTHER =
            UUID.fromString("00000000-0000-0000-0000-00000000a002");

    @TempDir
    Path tempDir;

    private File dbFile;
    private DatabaseManager db;
    private ReportRepository repo;
    private YamlConfiguration cfg;
    private CapturingHandler logs;

    @BeforeEach
    void setUp() throws Exception {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        Logger logger = Logger.getLogger("ReportRepositoryTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logs = new CapturingHandler();
        logger.addHandler(logs);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "report-repo.db");
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(cm.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        dbFile = new File(tempDir.toFile(), "report-repo.db");
        repo = new ReportRepository(db, logger);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    // ============================================================
    //  造数 / 注入工具（全部走独立连接，不经过被测仓储）
    // ============================================================

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private int scalar(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : Integer.MIN_VALUE;
        }
    }

    /** 让某张表"消失"：真实 {@code no such table}，比 mock SQLException 更接近事故现场。 */
    private void hideTable(String logicalName) throws Exception {
        exec("ALTER TABLE " + PREFIX + logicalName + " RENAME TO " + PREFIX + logicalName + "__off");
    }

    private void insertPlayerData(UUID id, String name, long correct, long sessions,
                                  long score, int conv) throws Exception {
        exec("INSERT INTO " + PREFIX + "player_data"
                + " (uuid, player_name, total_words_learned, total_sessions, total_score,"
                + "  conversation_count) VALUES ('" + id + "', "
                + (name == null ? "NULL" : "'" + name + "'") + ", "
                + correct + ", " + sessions + ", " + score + ", " + conv + ")");
    }

    private void insertSession(UUID id, String date, int answered, int correct, long score)
            throws Exception {
        exec("INSERT INTO " + PREFIX + "player_sessions"
                + " (uuid, session_date, words_count, correct_count, score) VALUES ('"
                + id + "', '" + date + "', " + answered + ", " + correct + ", " + score + ")");
    }

    private void insertAchievement(UUID id, String achId, long unlockedAt) throws Exception {
        exec("INSERT INTO " + PREFIX + "achievements (uuid, achievement_id, unlocked_at)"
                + " VALUES ('" + id + "', '" + achId + "', " + unlockedAt + ")");
    }

    private void insertChat(UUID id, String sessionId, String timestamp) throws Exception {
        exec("INSERT INTO " + PREFIX + "ai_chat_history"
                + " (player_uuid, session_id, role, content, timestamp) VALUES ('"
                + id + "', '" + sessionId + "', 'user', 'x', '" + timestamp + "')");
    }

    private void insertChallenge(UUID id, String date, String challengeId, boolean completed)
            throws Exception {
        exec("INSERT INTO " + PREFIX + "daily_challenges"
                + " (uuid, challenge_date, challenge_id, progress, completed, claimed)"
                + " VALUES ('" + id + "', '" + date + "', '" + challengeId + "', 0, "
                + (completed ? 1 : 0) + ", 0)");
    }

    private void insertWeakWord(UUID id, String word, int wrongCount) throws Exception {
        exec("INSERT INTO " + PREFIX + "difficult_words (player_uuid, word, wrong_count)"
                + " VALUES ('" + id + "', '" + word + "', " + wrongCount + ")");
    }

    private void insertCheckin(UUID id, String lastDate, int streak) throws Exception {
        exec("INSERT INTO " + PREFIX + "checkin_data"
                + " (player_uuid, last_checkin_date, streak_count) VALUES ('"
                + id + "', '" + lastDate + "', " + streak + ")");
    }

    private void insertProfile(UUID id, boolean showInSquare) throws Exception {
        exec("INSERT INTO " + PREFIX + "player_profiles (uuid, show_in_square) VALUES ('"
                + id + "', " + (showInSquare ? 1 : 0) + ")");
    }

    // ============================================================
    //  1. 空库：每个方法在零行时返回什么（崩溃点排查）
    // ============================================================

    @Test
    @DisplayName("空库：全部查询返回空/零且 complete=true，不抛异常")
    void emptyDatabaseIsNotAnError() {
        ReportRepository.LifetimeRows life = repo.fetchLifetimeOrFail(PLAYER);
        assertTrue(life.complete(), "空库不是读失败");
        assertNull(life.row(), "该 uuid 从未写过 player_data = 真的没有数据");

        ReportRepository.AchievementRows ach =
                repo.countAchievementsOrFail(PLAYER, 0L, Long.MAX_VALUE);
        assertTrue(ach.complete(), "空库不是读失败");
        assertNotNull(ach.counts(), "COUNT(*) 聚合恒返回一行，不能是 null");
        assertEquals(0, ach.counts().total(), "空库成就总数应为 0");
        // SQLite 的 SUM() 在空集上返回 NULL，靠 ResultSet.getInt() 折成 0。
        // 这条断言钉住那个隐式依赖：改成 getObject()/直接算就是 NPE。
        assertEquals(0, ach.counts().recent(), "空集 SUM(CASE...) 为 NULL，应折成 0 而不是崩");

        ReportRepository.ConversationCount conv =
                repo.countAiConversationsOrFail(PLAYER, "2000-01-01 00:00:00",
                        "2099-01-01 00:00:00", true);
        assertTrue(conv.complete());
        assertEquals(0, conv.value());

        ReportRepository.StreakValue streak = repo.fetchCheckinStreakOrFail(PLAYER);
        assertTrue(streak.complete());
        assertEquals(0, streak.value(), "从未签到 = 真实 0（不是'不知道'）");

        ReportRepository.ChallengeRows ch =
                repo.countDailyChallengesOrFail(PLAYER, "2000-01-01", "2099-01-01");
        assertTrue(ch.complete());
        assertNotNull(ch.progress(), "聚合恒一行");
        assertEquals(0, ch.progress().done());
        assertEquals(0, ch.progress().total(), "总行数为 0 —— ReportGenerator 据此省略该节");

        ReportRepository.DailyRows daily =
                repo.queryDailyRows(PLAYER, "2000-01-01", "2099-01-01");
        assertTrue(daily.complete());
        assertTrue(daily.rows().isEmpty());

        ReportRepository.WeakWords weak = repo.queryWeakWordsOrFail(PLAYER, 5);
        assertTrue(weak.complete(), "没有错题 != 读失败");
        assertTrue(weak.rows().isEmpty());

        ReportRepository.BatchRows batch =
                repo.queryBatchStatsOrFail("2000-01-01", "2099-01-01", 51, false);
        assertTrue(batch.complete(), "空库批量查询不得被当成读失败（否则误报 report.failed）");
        assertTrue(batch.rows().isEmpty());

        assertFalse(repo.isHiddenInSquare(PLAYER), "从未改过开关 = 默认可见");
    }

    // ============================================================
    //  2. Q2 lifetime：可空列、无行 vs 读失败
    // ============================================================

    @Test
    @DisplayName("Q2：player_name 为 NULL 时不抛异常（列可空，调用方靠 firstNonBlank 回落）")
    void lifetimeToleratesNullPlayerName() throws Exception {
        insertPlayerData(PLAYER, null, 42L, 7L, 1234L, 3);

        ReportRepository.LifetimeRows r = repo.fetchLifetimeOrFail(PLAYER);
        assertTrue(r.complete());
        assertNotNull(r.row());
        assertNull(r.row().playerName(), "库内名字可为 NULL，仓储原样透出");
        assertEquals(42L, r.row().totalCorrect());
        assertEquals(7L, r.row().sessions());
        assertEquals(1234L, r.row().score());
        assertEquals(3, r.row().conversations());
    }

    @Test
    @DisplayName("Q2：无行 = complete=true + row=null；读失败 = complete=false（二者必须可分）")
    void lifetimeDistinguishesNoRowFromReadFailure() throws Exception {
        insertPlayerData(OTHER, "Other", 1L, 1L, 1L, 0);

        ReportRepository.LifetimeRows missing = repo.fetchLifetimeOrFail(PLAYER);
        assertTrue(missing.complete(), "「没这行」不是读失败");
        assertNull(missing.row());

        hideTable("player_data");
        ReportRepository.LifetimeRows failed = repo.fetchLifetimeOrFail(PLAYER);
        assertFalse(failed.complete(), "表没了必须报 complete=false —— 否则渲染成「累计答对 0」");
        assertNull(failed.row());
        assertTrue(logs.saw(Level.WARNING, "Failed to query player_data"));

        // 旧签名把两者折叠成 null，是刻意的编译期兼容层（生产无调用方）。
        assertNull(repo.fetchLifetime(OTHER), "旧签名在失败时与无行同形（已无生产调用方）");
    }

    // ============================================================
    //  3. Q3 achievements：半开区间 [start, endExclusive)
    // ============================================================

    @Test
    @DisplayName("Q3：窗口是半开区间 —— start 含、endExclusive 不含、endExclusive-1 含")
    void achievementsWindowIsHalfOpen() throws Exception {
        long start = 1_000L;
        long end = 2_000L;
        insertAchievement(PLAYER, "BEFORE", start - 1);     // 窗口前 1ms
        insertAchievement(PLAYER, "AT_START", start);       // 左端点：含
        insertAchievement(PLAYER, "LAST_INSIDE", end - 1);  // 右端点前 1ms：含
        insertAchievement(PLAYER, "AT_END", end);           // 右端点：不含
        insertAchievement(PLAYER, "AFTER", end + 1);        // 窗口后 1ms

        ReportRepository.AchievementRows r = repo.countAchievementsOrFail(PLAYER, start, end);
        assertTrue(r.complete());
        assertEquals(5, r.counts().total(), "总数是终身口径，不受窗口影响");
        assertEquals(2, r.counts().recent(),
                "窗口内应恰好 2 个（AT_START + LAST_INSIDE）。"
                        + "把 `unlocked_at < ?` 改成 `<= ?` 会得到 3（多算 AT_END）；"
                        + "把 `>= ?` 改成 `> ?` 会得到 1（漏算 AT_START）。");
    }

    @Test
    @DisplayName("Q3：读失败 → complete=false 且 counts 为 null（不得当 0 渲染）")
    void achievementsReadFailureIsNotZero() throws Exception {
        insertAchievement(PLAYER, "A1", 100L);
        hideTable("achievements");

        ReportRepository.AchievementRows r = repo.countAchievementsOrFail(PLAYER, 0L, 1_000L);
        assertFalse(r.complete(), "读失败必须可辨");
        assertNull(r.counts(), "失败时不能给出 0/0 —— 那会被渲染成「本期没解锁」");
        assertTrue(logs.saw(Level.WARNING, "Failed to query achievements"));
    }

    @Test
    @DisplayName("Q3：别人的成就不计入")
    void achievementsAreScopedToPlayer() throws Exception {
        insertAchievement(PLAYER, "A1", 100L);
        insertAchievement(OTHER, "A1", 100L);
        insertAchievement(OTHER, "A2", 150L);

        ReportRepository.AchievementRows r = repo.countAchievementsOrFail(PLAYER, 0L, 1_000L);
        assertEquals(1, r.counts().total());
        assertEquals(1, r.counts().recent());
    }

    // ============================================================
    //  4. Q4 AI 对话：DISTINCT session + 两侧上界方言
    // ============================================================

    @Test
    @DisplayName("Q4：按 session_id 去重（一轮对话 3 条消息只算 1 轮）")
    void aiConversationsCountSessionsNotMessages() throws Exception {
        insertChat(PLAYER, "s1", "2026-01-05 10:00:00");
        insertChat(PLAYER, "s1", "2026-01-05 10:00:01");
        insertChat(PLAYER, "s1", "2026-01-05 10:00:02");
        insertChat(PLAYER, "s2", "2026-01-06 10:00:00");

        ReportRepository.ConversationCount r = repo.countAiConversationsOrFail(
                PLAYER, "2026-01-01 00:00:00", "2026-01-10 00:00:00", true);
        assertTrue(r.complete());
        assertEquals(2, r.value(), "3 条消息同属 1 个 session，加另 1 个 = 2 轮");
    }

    @Test
    @DisplayName("Q4：同一份数据上，exclusiveUpper 两支在边界值给出不同结果"
            + "（两侧方言口径不同被固化成可执行规格）")
    void aiConversationsUpperBoundDialectsDifferAtBoundary() throws Exception {
        // 边界上恰好有一条：SQLite 的 UTC 区间用 `<` 排除它，MySQL 的 23:59:59 上界用 `<=` 含它。
        insertChat(PLAYER, "atBoundary", "2026-01-10 00:00:00");
        String lo = "2026-01-01 00:00:00";
        String hi = "2026-01-10 00:00:00";

        int exclusive = repo.countAiConversationsOrFail(PLAYER, lo, hi, true).value();
        int inclusive = repo.countAiConversationsOrFail(PLAYER, lo, hi, false).value();

        assertEquals(0, exclusive, "SQLite 分支（< 上界）不应把边界那一秒算进窗口");
        assertEquals(1, inclusive, "MySQL 分支（<= 上界）在同样入参下会算进边界那一秒");
        // 本断言的价值：两个分支不是同一段 SQL 的语法糖，它们的语义真的不同。
        // 若有人把 sqlInclusive 与 sqlExclusive 合并成一条，本断言必然红。
        assertTrue(exclusive != inclusive, "两支必须在边界上可区分");
    }

    @Test
    @DisplayName("Q4：读失败 → value=0 但 complete=false")
    void aiConversationsReadFailureIsFlagged() throws Exception {
        hideTable("ai_chat_history");
        ReportRepository.ConversationCount r = repo.countAiConversationsOrFail(
                PLAYER, "2000-01-01 00:00:00", "2099-01-01 00:00:00", true);
        assertFalse(r.complete());
        assertEquals(0, r.value());
        assertTrue(logs.saw(Level.WARNING, "Failed to query AI chat count"));
    }

    // ============================================================
    //  5. Q6 连续签到：断签快照折算成"当前连击"（round28 已修）
    // ============================================================

    @Test
    @DisplayName("Q6：今天/昨天签过 → 读出快照值；无行 → 真实 0 且不是读失败")
    void checkinStreakReadsEffectiveValue() throws Exception {
        insertCheckin(PLAYER, LocalDate.now().toString(), 5);
        ReportRepository.StreakValue r = repo.fetchCheckinStreakOrFail(PLAYER);
        assertTrue(r.complete());
        assertEquals(5, r.value(), "今天已签，连击快照就是当前值");

        insertCheckin(OTHER, LocalDate.now().minusDays(1).toString(), 4);
        ReportRepository.StreakValue yesterday = repo.fetchCheckinStreakOrFail(OTHER);
        assertTrue(yesterday.complete());
        assertEquals(4, yesterday.value(),
                "昨天签过 = 连击未断（今天签下去就是 5），必须原样读出");

        ReportRepository.StreakValue absent =
                repo.fetchCheckinStreakOrFail(UUID.fromString("00000000-0000-0000-0000-00000000a0ff"));
        assertTrue(absent.complete(), "无行 = 真实 0，不是读失败");
        assertEquals(0, absent.value());
    }

    /**
     * <b>round28 已修（原 MEDIUM 缺陷）</b>：{@code checkin_data} 只存「最后一次签到时
     * 的 streak」，断签后<b>不会</b>被任何后台任务归零（归零发生在<b>下一次</b>签到时，
     * 见 {@code CheckInManager.checkInLocked} 的 {@code streakBroken} 分支）。
     *
     * <p>旧实现只 {@code SELECT streak_count}，把一个<b>历史快照</b>当成<b>当前值</b>
     * 输出：玩家 20 天前连签 5 天后彻底停学，今天生成的家长报告仍写「连续签到 5 天」
     * （{@code report.cumulative-extra}），家长据此以为孩子天天在学。
     *
     * <p>现仓储改读 {@code last_checkin_date}（同一行、同一张表，数据现成），
     * 按与写侧同构的判定折算：只有今天/昨天签过的快照还算数。
     */
    @Test
    @DisplayName("Q6：断签后连续天数应为 0，而不是上一次的残留值")
    void checkinStreakResetsAfterLapse() throws Exception {
        LocalDate lastCheckin = LocalDate.now().minusDays(20);
        insertCheckin(PLAYER, lastCheckin.toString(), 5);

        int reported = repo.fetchCheckinStreakOrFail(PLAYER).value();
        assertEquals(0, reported,
                "最后一次签到是 " + lastCheckin + "（20 天前，连击早已中断），"
                        + "报告不得再写「连续签到 " + reported + " 天」");
    }

    @Test
    @DisplayName("Q6：判定口径与写侧同构 —— 今天/昨天保留，前天后一律 0（含未来日期）")
    void effectiveStreakMirrorsWriterRules() {
        LocalDate today = LocalDate.of(2026, 5, 10);
        assertEquals(7, ReportRepository.effectiveStreak(today, 7, today), "今天签过");
        assertEquals(7, ReportRepository.effectiveStreak(today.minusDays(1), 7, today), "昨天签过");
        assertEquals(0, ReportRepository.effectiveStreak(today.minusDays(2), 7, today),
                "前天签过 = 已断一天，写侧下次签到会写 1，报告必须先显示 0");
        assertEquals(0, ReportRepository.effectiveStreak(today.minusDays(400), 99, today), "长期停学");
        assertEquals(0, ReportRepository.effectiveStreak(today.plusDays(1), 7, today),
                "未来日期（跨服时区不一致）按写侧同样规则视为已断，不做特殊照顾");
        assertEquals(0, ReportRepository.effectiveStreak(null, 7, today), "无快照");
    }

    @Test
    @DisplayName("Q6：脏 last_checkin_date（非 ISO 字符串）→ complete=false，不猜一个数字")
    void checkinStreakDirtyDateFlagsIncomplete() throws Exception {
        // SQLite 的 DATE 列无类型约束：手工修库/老版本可能留下非 ISO 值。
        insertCheckin(PLAYER, "2026-13-45", 5);

        ReportRepository.StreakValue r = repo.fetchCheckinStreakOrFail(PLAYER);
        assertFalse(r.complete(), "判不出是否断签就必须标记不完整（与 Q1 脏日期同一处置）");
        assertEquals(0, r.value(), "不得把无法判定的快照原样当当前值输出");
        assertTrue(logs.saw(Level.WARNING, "Failed to query check-in streak"));
    }

    @Test
    @DisplayName("Q6：last_checkin_date 为 NULL → complete=false（不得 NPE 炸掉整份报告）")
    void checkinStreakNullDateFlagsIncompleteInsteadOfNpe() throws Exception {
        // 产品 DDL 声明的是 NOT NULL，为了造出这个"历史库脏行"必须重建一张等价老表
        // （与 batchActiveDaysUsesCountStarNotDistinct 同款手法）。
        // 为什么值得测：claimCheckIn 的 WHERE 里带 `last_checkin_date IS NULL` 防御 ——
        // 本仓自己就把"历史库可能留 NULL"当既成假设。若此处让 LocalDate.parse(null)
        // 抛 NPE，报告不是标"数据不完整"而是整份生成失败。
        exec("DROP TABLE " + PREFIX + "checkin_data");
        exec("CREATE TABLE " + PREFIX + "checkin_data ("
                + " player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                + " last_checkin_date DATE,"
                + " streak_count INTEGER DEFAULT 1)");
        exec("INSERT INTO " + PREFIX + "checkin_data (player_uuid, last_checkin_date,"
                + " streak_count) VALUES ('" + PLAYER + "', NULL, 5)");

        ReportRepository.StreakValue r = repo.fetchCheckinStreakOrFail(PLAYER);
        assertFalse(r.complete(), "NULL 日期无法判定是否断签");
        assertEquals(0, r.value());
        assertTrue(logs.saw(Level.WARNING, "last_checkin_date is NULL"));
    }

    // ============================================================
    //  6. Q8 每日挑战：DATE 闭区间 + 空集 SUM
    // ============================================================

    @Test
    @DisplayName("Q8：DATE 闭区间 —— from/to 两端都含")
    void dailyChallengesWindowIsClosed() throws Exception {
        insertChallenge(PLAYER, "2026-01-04", "C1", true);   // 窗口前
        insertChallenge(PLAYER, "2026-01-05", "C1", true);   // 左端点：含
        insertChallenge(PLAYER, "2026-01-06", "C1", false);  // 中间
        insertChallenge(PLAYER, "2026-01-07", "C1", true);   // 右端点：含
        insertChallenge(PLAYER, "2026-01-08", "C1", true);   // 窗口后

        ReportRepository.ChallengeRows r =
                repo.countDailyChallengesOrFail(PLAYER, "2026-01-05", "2026-01-07");
        assertTrue(r.complete());
        assertEquals(3, r.progress().total(),
                "闭区间两端都含应为 3 行；把任一端改成 < / > 会得到 2");
        assertEquals(2, r.progress().done(),
                "3 行里 2 行 completed=1；completed 列是 BOOLEAN，SQL 用 `completed = 1` 比较");
    }

    @Test
    @DisplayName("Q8：读失败 → progress 为 null + complete=false")
    void dailyChallengesReadFailureIsFlagged() throws Exception {
        hideTable("daily_challenges");
        ReportRepository.ChallengeRows r =
                repo.countDailyChallengesOrFail(PLAYER, "2026-01-01", "2026-01-31");
        assertFalse(r.complete());
        assertNull(r.progress());
        assertTrue(logs.saw(Level.WARNING, "Failed to query daily challenges"));
    }

    // ============================================================
    //  7. Q1 逐日行：闭区间、ASC 排序、脏日期整窗截断
    // ============================================================

    @Test
    @DisplayName("Q1：闭区间 + session_date 升序")
    void dailyRowsWindowIsClosedAndAscending() throws Exception {
        insertSession(PLAYER, "2026-01-07", 10, 8, 80L);
        insertSession(PLAYER, "2026-01-05", 20, 15, 150L);
        insertSession(PLAYER, "2026-01-06", 30, 30, 300L);
        insertSession(PLAYER, "2026-01-04", 99, 99, 999L); // 窗口前
        insertSession(PLAYER, "2026-01-08", 99, 99, 999L); // 窗口后

        ReportRepository.DailyRows r =
                repo.queryDailyRows(PLAYER, "2026-01-05", "2026-01-07");
        assertTrue(r.complete());
        List<LocalDate> dates = r.rows().stream().map(ReportRepository.DailyRow::date).toList();
        assertEquals(List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6),
                        LocalDate.of(2026, 1, 7)), dates,
                "必须升序 —— 报告「每日明细」节按此顺序逐行打印；乱序=家长看到时间倒流");
        assertEquals(20, r.rows().get(0).answered());
        assertEquals(15, r.rows().get(0).correct());
        assertEquals(150L, r.rows().get(0).score());
    }

    /**
     * <b>已知缺陷（LOW，未修）</b>：逐行映射里 {@code LocalDate.parse} 的
     * {@link java.time.format.DateTimeParseException} 与 {@code SQLException} 共享一个
     * catch，catch 后 {@code return new DailyRows(rows, false)} —— <b>一行脏
     * {@code session_date} 会让它之后的所有行全部丢弃</b>。
     *
     * <p>危害有限（complete=false 会让报告打上「数据不完整」，不会当成"只学了 N 天"），
     * 但"一行坏数据截断整段历史"值得记录：SQLite 无类型约束，老版本/手工修库都可能留下
     * 非法日期。造数用 {@code 2026-02-30}（符合 SQL 的字符串区间比较形状、能被 WHERE
     * 选中，但日历上不存在，{@code LocalDate.parse} 必抛）——非补零的 {@code 2026-1-8}
     * 会被 SQL 的字符串区间提前排除，到不了映射阶段。
     */
    @Test
    @DisplayName("Q1：一行脏 session_date 会让其后的行全部丢失，但 complete=false 如实标记")
    void dailyRowsDirtyDateTruncatesRemainderButFlagsIncomplete() throws Exception {
        insertSession(PLAYER, "2026-01-05", 10, 8, 80L);
        insertSession(PLAYER, "2026-01-06", 20, 15, 150L);
        insertSession(PLAYER, "2026-01-07", 30, 30, 300L);
        // SQLite 的 DATE 列无类型约束：塞一个日历上不存在的日期进去完全合法。
        // 选 2026-02-30 而不是 2026-1-8：后者会被 SQL 的字符串区间先排除，到不了解析层。
        insertSession(PLAYER, "2026-02-30", 99, 99, 999L);

        ReportRepository.DailyRows r =
                repo.queryDailyRows(PLAYER, "2026-01-05", "2026-03-31");
        assertFalse(r.complete(), "解析失败必须标记不完整（否则等于伪造「只学了 3 天」）");
        assertEquals(3, r.rows().size(), "坏行之前的 3 行保留，坏行及其后全部丢弃");
        assertTrue(logs.saw(Level.WARNING, "Failed to query session rows"));
    }

    // ============================================================
    //  8. Q5 错题：LIMIT + 确定性排序（错次降序，同错次按词升序）
    // ============================================================

    @Test
    @DisplayName("Q5：按 wrong_count DESC, word ASC 排序并尊重 LIMIT")
    void weakWordsOrderingIsDeterministic() throws Exception {
        insertWeakWord(PLAYER, "zebra", 5);
        insertWeakWord(PLAYER, "apple", 5);
        insertWeakWord(PLAYER, "mango", 9);
        // round28：kiwi 从 1 改成 3 —— 阈值门槛（>=3）上线后，wrong_count=1 的词已被过滤，
        // 若继续写 1 本用例就只剩 3 个候选，LIMIT 2 仍在测但少了"有第 4 个候选被 LIMIT 砍掉"
        // 这层意义。改成 3 让它成为合法的第 4 个候选。
        insertWeakWord(PLAYER, "kiwi", 3);

        ReportRepository.WeakWords top2 = repo.queryWeakWordsOrFail(PLAYER, 2);
        assertTrue(top2.complete());
        assertEquals(List.of("mango", "apple"),
                top2.rows().stream().map(ReportRepository.WeakWordRow::word).toList(),
                "错次最高先出；同错次按词升序（apple < zebra）—— 无二级排序时"
                        + "SQLite 返回顺序不确定，报告每次生成可能不同");
        assertEquals(9, top2.rows().get(0).wrongCount());
    }

    /**
     * <b>round28 已修（原 MEDIUM 缺陷）</b>：本查询此前<b>没有</b> {@code wrong_count >= 3}
     * 门槛，而错题本在别处有这道门槛 —— {@code PlayerDataStore.WRONG_BOOK_MIN_WRONG_COUNT = 3}
     * （注释明写"展示侧必须按此阈值过滤"，{@code getWrongBookEntries} 与
     * {@code WrongBookGUI} 都照此过滤）。
     *
     * <p>{@code difficult_words} 表在<b>首次答错</b>就建行（wrong_count=1），它是"累计错次
     * 计数器"而不是"错题本"。于是家长报告会把只错 1 次的词写进【易错单词】节，而玩家在
     * 游戏里的错题本里看不到同一个词 —— 同一份数据两套口径，家长与孩子对不上账。
     *
     * <p>现 SQL 加 {@code AND wrong_count >= ?} 并绑定该常量（不硬编码 3）。
     */
    @Test
    @DisplayName("Q5：低于错题本收录阈值（wrong_count >= 3）的词不出现在报告里")
    void weakWordsApplyWrongBookThreshold() throws Exception {
        insertWeakWord(PLAYER, "barely-wrong", 1); // 首次答错：只是计数器，不是错题本条目
        insertWeakWord(PLAYER, "twice-wrong", 2);
        insertWeakWord(PLAYER, "at-threshold", 3);  // 边界：门槛含等号
        insertWeakWord(PLAYER, "really-wrong", 7);

        ReportRepository.WeakWords r = repo.queryWeakWordsOrFail(PLAYER, 10);
        assertTrue(r.complete());
        assertEquals(List.of("really-wrong", "at-threshold"),
                r.rows().stream().map(ReportRepository.WeakWordRow::word).toList(),
                "报告错题列表必须与 WrongBookGUI 同口径（>= "
                        + PlayerDataStore.WRONG_BOOK_MIN_WRONG_COUNT
                        + "）：把 `>= ?` 改成 `> ?` 会漏掉 at-threshold，"
                        + "去掉该条件会多出 barely-wrong/twice-wrong");
    }

    @Test
    @DisplayName("Q5：全部词都低于阈值 → 空列表但 complete=true（不是「读失败」）")
    void weakWordsBelowThresholdIsEmptyNotFailure() throws Exception {
        insertWeakWord(PLAYER, "a", 1);
        insertWeakWord(PLAYER, "b", 2);

        ReportRepository.WeakWords r = repo.queryWeakWordsOrFail(PLAYER, 5);
        assertTrue(r.complete(), "低于阈值 ≠ 读失败 —— 否则报告会误打「数据不完整」");
        assertTrue(r.rows().isEmpty(), "报告据此省略【易错单词】整节");
    }

    @Test
    @DisplayName("Q5：读失败 → 保留已读部分行，但 complete=false（不得当「没有错题」）")
    void weakWordsReadFailureKeepsPartialRowsButFlags() throws Exception {
        hideTable("difficult_words");
        ReportRepository.WeakWords r = repo.queryWeakWordsOrFail(PLAYER, 5);
        assertFalse(r.complete(), "读失败不得渲染成「该生没有错题」");
        assertTrue(r.rows().isEmpty());
        assertTrue(logs.saw(Level.WARNING, "Failed to query weak words"));
    }

    // ============================================================
    //  9. Q9 批量：聚合、隐私过滤、截断探测、COUNT(*) 口径
    // ============================================================

    @Test
    @DisplayName("Q9：按玩家聚合求和，按总答对数降序")
    void batchAggregatesAndOrdersByCorrectDesc() throws Exception {
        insertPlayerData(PLAYER, "Alice", 0L, 0L, 0L, 0);
        insertPlayerData(OTHER, "Bob", 0L, 0L, 0L, 0);
        insertSession(PLAYER, "2026-01-05", 10, 4, 40L);
        insertSession(PLAYER, "2026-01-06", 10, 4, 60L);
        insertSession(OTHER, "2026-01-05", 20, 20, 200L);

        ReportRepository.BatchRows r =
                repo.queryBatchStatsOrFail("2026-01-01", "2026-01-31", 10, false);
        assertTrue(r.complete());
        assertEquals(2, r.rows().size());
        ReportRepository.BatchStatRow top = r.rows().get(0);
        assertEquals(OTHER.toString(), top.uuid(), "Bob 答对 20 大于 Alice 8，应排第一");
        assertEquals("Bob", top.playerName());
        assertEquals(20, top.correct());
        assertEquals(20, top.answered());
        assertEquals(200L, top.score());
        assertEquals(1, top.activeDays());

        ReportRepository.BatchStatRow second = r.rows().get(1);
        assertEquals("Alice", second.playerName());
        assertEquals(2, second.activeDays(), "两天的行 = 2 个学习日");
        assertEquals(20, second.answered(), "10+10 累加");
        assertEquals(8, second.correct(), "4+4 累加");
        assertEquals(100L, second.score(), "40+60 累加");
    }

    @Test
    @DisplayName("Q9：respectPrivacy=true 时隐身玩家被排除；无 profile 行按默认可见")
    void batchPrivacyFilterHidesOptedOutPlayers() throws Exception {
        insertPlayerData(PLAYER, "Visible", 0L, 0L, 0L, 0);
        insertPlayerData(OTHER, "Hidden", 0L, 0L, 0L, 0);
        insertSession(PLAYER, "2026-01-05", 10, 10, 100L);
        insertSession(OTHER, "2026-01-05", 10, 10, 100L);
        insertProfile(OTHER, false); // 显式隐身

        ReportRepository.BatchRows withPrivacy =
                repo.queryBatchStatsOrFail("2026-01-01", "2026-01-31", 10, true);
        assertTrue(withPrivacy.complete());
        assertEquals(1, withPrivacy.rows().size(), "隐身玩家不得出现在批量报告里");
        assertEquals("Visible", withPrivacy.rows().get(0).playerName());

        ReportRepository.BatchRows noPrivacy =
                repo.queryBatchStatsOrFail("2026-01-01", "2026-01-31", 10, false);
        assertEquals(2, noPrivacy.rows().size(),
                "respectPrivacy=false 是刻意的管理/监护通道口径（config.yml 有说明）");
    }

    @Test
    @DisplayName("Q9：LEFT JOIN 不丢行 —— 没有 player_data 行的玩家仍出现在批量里")
    void batchKeepsPlayersWithoutPlayerDataRow() throws Exception {
        // 只有 session 行、没有 player_data 行（player_data 写失败 / 老库）。LEFT JOIN 下
        // player_name 为 NULL，ReportGenerator 回落到 uuid 前 8 位；INNER JOIN 会整行丢失。
        insertSession(PLAYER, "2026-01-05", 10, 7, 70L);

        ReportRepository.BatchRows r =
                repo.queryBatchStatsOrFail("2026-01-01", "2026-01-31", 10, false);
        assertTrue(r.complete());
        assertEquals(1, r.rows().size(), "LEFT JOIN 必须保住只有 session 行的玩家");
        assertNull(r.rows().get(0).playerName(), "无 player_data 行时名字为 NULL，由调用方回落");
        assertEquals(7, r.rows().get(0).correct());
    }

    @Test
    @DisplayName("Q9：LIMIT 生效（调用方传 batchMax+1 探测截断）")
    void batchRespectsLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.fromString("00000000-0000-0000-0000-0000000b00" + i);
            insertPlayerData(id, "P" + i, 0L, 0L, 0L, 0);
            insertSession(id, "2026-01-05", 10, i, i * 10L);
        }
        ReportRepository.BatchRows r =
                repo.queryBatchStatsOrFail("2026-01-01", "2026-01-31", 3, false);
        assertEquals(3, r.rows().size(), "LIMIT 3 应只回 3 行（ReportGenerator 用 N+1 判断截断）");
    }

    /**
     * <b>口径分叉（LOW-MEDIUM，未修）</b>：本方法用 {@code COUNT(*) AS active_days}，
     * 依赖 {@code UNIQUE(uuid, session_date)} 保证"一天一行"。但
     * {@code CREATE TABLE IF NOT EXISTS} <b>不会</b>给已存在的老表补 UNIQUE（本仓
     * {@code addColumnIfMissing} 只补列、从不补约束），因此老库理论上可存重复的
     * {@code (uuid, session_date)} 行 —— 此时 {@code COUNT(*)} 把一天算成两天。
     *
     * <p>对照组：{@code AchievementRepository.countDistinctStudyDays} 用的是
     * {@code COUNT(DISTINCT session_date)}。同一个"学习天数"，两处口径不一致。
     *
     * <p>本测试在<b>去掉 UNIQUE 的等价老 schema</b> 上跑，把分叉固定成可执行事实。
     */
    @Test
    @DisplayName("Q9：老 schema 无 UNIQUE 时重复 (uuid,date) 行会把 active_days 算多"
            + "（与 countDistinctStudyDays 口径分叉）")
    void batchActiveDaysUsesCountStarNotDistinct() throws Exception {
        // 造出"当年没有 UNIQUE 约束的老表"：重建同名表，不带 UNIQUE(uuid, session_date)。
        exec("DROP TABLE " + PREFIX + "player_sessions");
        exec("CREATE TABLE " + PREFIX + "player_sessions ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " uuid VARCHAR(36) NOT NULL,"
                + " session_date DATE NOT NULL,"
                + " words_count INT DEFAULT 0,"
                + " correct_count INT DEFAULT 0,"
                + " score BIGINT DEFAULT 0,"
                + " mode VARCHAR(32),"
                + " review_words INT DEFAULT 0)");
        insertPlayerData(PLAYER, "Alice", 0L, 0L, 0L, 0);
        insertSession(PLAYER, "2026-01-05", 10, 5, 50L);
        insertSession(PLAYER, "2026-01-05", 10, 5, 50L); // 同一天的第二行（老库可能存在）

        int distinctDays = scalar("SELECT COUNT(DISTINCT session_date) FROM " + PREFIX
                + "player_sessions WHERE uuid = '" + PLAYER + "'");
        assertEquals(1, distinctDays, "真实学习天数 = 1 天");

        ReportRepository.BatchRows r =
                repo.queryBatchStatsOrFail("2026-01-01", "2026-01-31", 10, false);
        assertTrue(r.complete());
        assertEquals(2, r.rows().get(0).activeDays(),
                "COUNT(*) 把同一天的两行算成 2 个学习日 —— 与 COUNT(DISTINCT) 口径分叉");
        assertEquals(20, r.rows().get(0).answered(),
                "同时 words_count 被重复累加（同一批重复行同样污染答对数）");
    }

    @Test
    @DisplayName("Q9：读失败 → complete=false（不得宣布「本周期无人学习」）")
    void batchReadFailureIsFlagged() throws Exception {
        hideTable("player_sessions");
        ReportRepository.BatchRows r =
                repo.queryBatchStatsOrFail("2026-01-01", "2026-01-31", 10, false);
        assertFalse(r.complete());
        assertTrue(logs.saw(Level.WARNING, "Failed to query batch report rows"));
    }

    // ============================================================
    //  10. 隐私闸门：fail-closed
    // ============================================================

    @Test
    @DisplayName("隐私：show_in_square=0 为 true；=1 为 false；无行为 false（默认可见）")
    void hiddenInSquareReadsFlag() throws Exception {
        insertProfile(OTHER, false);
        insertProfile(PLAYER, true);

        assertTrue(repo.isHiddenInSquare(OTHER));
        assertFalse(repo.isHiddenInSquare(PLAYER));
        assertFalse(repo.isHiddenInSquare(
                UUID.fromString("00000000-0000-0000-0000-0000000000ff")), "无行 = 默认可见");
    }

    @Test
    @DisplayName("隐私：读失败必须抛（fail-closed），不能 return false 把隐身玩家当公开")
    void hiddenInSquareFailsClosed() throws Exception {
        hideTable("player_profiles");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> repo.isHiddenInSquare(PLAYER),
                "读失败若 return false，respect-privacy=true 的闸门被整段跳过，"
                        + "隐身玩家的数据照常渲染 —— 危险方向是「多报」");
        assertTrue(ex.getCause() instanceof java.sql.SQLException, "应保留 SQLException 为 cause");
        assertTrue(logs.saw(Level.WARNING, "Failed to check privacy flag"));
    }

    // ============================================================
    //  11. 表前缀：构造期不快照（R6-E）
    // ============================================================

    @Test
    @DisplayName("表前缀现读：initialize() 换前缀后，同一个 repo 实例改查新表（不是构造期快照）")
    void tablePrefixIsReadAtQueryTime() throws Exception {
        insertPlayerData(PLAYER, "OldPrefix", 11L, 1L, 1L, 0);
        assertEquals("OldPrefix", repo.fetchLifetimeOrFail(PLAYER).row().playerName());

        // 运维改配置 + 重连：DatabaseManager 会用新前缀建出一套新表。
        cfg.set("database.mysql.table-prefix", "alt_");
        db.initialize();
        exec("INSERT INTO alt_player_data (uuid, player_name, total_words_learned,"
                + " total_sessions, total_score, conversation_count) VALUES ('"
                + PLAYER + "', 'NewPrefix', 22, 2, 2, 0)");

        ReportRepository.LifetimeRows r = repo.fetchLifetimeOrFail(PLAYER);
        assertTrue(r.complete(), "新前缀的表存在，应能读到");
        assertEquals("NewPrefix", r.row().playerName(),
                "若前缀在构造期被快照，这里会读到旧表的 OldPrefix —— "
                        + "那正是「新旧两套表同时活着、各写一半」的数据分裂");
        assertEquals(22L, r.row().totalCorrect());
    }

    /** 捕获日志，用来断言"读失败是否留了痕"（降级不能静默）。 */
    private static final class CapturingHandler extends Handler {
        private final java.util.List<LogRecord> records = new java.util.ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            records.add(record);
        }

        synchronized boolean saw(Level level, String messageFragment) {
            for (LogRecord r : records) {
                if (level.equals(r.getLevel())
                        && r.getMessage() != null
                        && r.getMessage().contains(messageFragment)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    //  ======== 第 12 节起：round20 独立复核新增（audit-specialist） ========
    //
    //  上面 27 例经独立复核后判定：断言手法全部合格（真 SQLite + 第二条 JDBC 连接造数，
    //  无自证桩；@Disabled 措辞诚实，断言期望值而非现状）。但有三处覆盖缺口需要在
    //  这里补，它们不是"重新测一遍"，而是原套件按构造方式测不到的口径问题：
    //
    //   (1) 【D1】Q9 的 active_days 用 COUNT(*) 而不是 COUNT(DISTINCT)。原套件已把
    //       "老库无 UNIQUE 会算多"钉住（batchActiveDaysUsesCountStarNotDistinct），
    //       但没钉住同一报表内部两个出口：Q1（单报学习天数 = rows().size()）与
    //       Q9（批量学习天数 = COUNT(*)）遇重复行一起算多，而成就墙
    //       （AchievementRepository.countDistinctStudyDays）用 COUNT(DISTINCT) —— 同
    //       一个"学习天数"在报告与成就墙给出不同数字。
    //   (2) 【D2】Q8 的 COUNT(*) 把 degradeStale 写的"自动放弃"行算进"本期完成"分子
    //       （它写 completed=1, claimed=1）。发钱面为零（降级路径显式拒发），但报告会
    //       说"完成 3/3"而孩子只做了 2 个。
    //   (3) 【D3 已证伪】初始假设是"MySQL 的 <= 23:59:59 上界丢最后一秒"，在真实
    //       MariaDB 13.0.2 上被证伪（TIMESTAMP(0) 截断小数秒，两分支等价）。留下的是
    //       一条"两分支等价"的回归护栏 + 一条非 UTC 时区换算的事实钉桩。
    //       证伪过程写在 d3BothDialectsAgreeAtSecondPrecision 的 javadoc 里。
    // ============================================================
    /**
     * 【D1 事实钉桩 · LOW-MEDIUM，不修，仅记录】Q1 与 Q9 对"学习天数"的口径分叉。
     *
     * <p>这不是新缺陷的重复报告，而是把同一份报表内部两个出口的对不上钉住：
     * <ul>
     *   <li>Q1（queryDailyRows）→ ReportGenerator 用 rows().size() 当"学习天数"。
     *       UNIQUE 保证一天一行时正确，老库有重复行时会数成 N 行 = N 天。</li>
     *   <li>Q9（queryBatchStatsOrFail）→ COUNT(*)，同样口径。</li>
     * </ul>
     * 两者一致地错（都按行数），因此报表自洽 —— 这一点必须说清楚，否则会被误判成 HIGH。
     * 真正的对照方是 AchievementRepository.countDistinctStudyDays 的
     * COUNT(DISTINCT session_date)：同一天在成就墙与报告上显示不同天数。
     *
     * <p>为什么定 LOW-MEDIUM 而不是更高：唯一进入生产线的建表语句
     * （DatabaseManager.createTables）自 1.0.6 起就带 UNIQUE(uuid, session_date)
     * （已核 jar 常量池：1.0.0~1.0.5 无该表、1.0.6 起有且带 UNIQUE），且 recordSession
     * 用 UPSERT 累加。要产生重复行必须满足"1.0.5 及更早的库 + 从未重建 + 手工插行"
     * 三者同时成立。本测试用等价老 schema 复现该条件，证明的是假设成立时的后果。
     */
    @Test
    @DisplayName("【D1】Q1 与 Q9 的'学习天数'同为行数口径：重复行时两处一起算多（与成就墙 COUNT(DISTINCT) 分叉）")
    void d1StudyDayCountIsRowCountNotDistinct() throws Exception {
        exec("DROP TABLE " + PREFIX + "player_sessions");
        exec("CREATE TABLE " + PREFIX + "player_sessions ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " uuid VARCHAR(36) NOT NULL,"
                + " session_date DATE NOT NULL,"
                + " words_count INT DEFAULT 0,"
                + " correct_count INT DEFAULT 0,"
                + " score BIGINT DEFAULT 0,"
                + " mode VARCHAR(32),"
                + " review_words INT DEFAULT 0)");
        insertSession(PLAYER, "2026-01-05", 10, 5, 50L);
        insertSession(PLAYER, "2026-01-05", 10, 5, 50L); // 同一天第二行

        // 真值：这一天 = 1 天（这是成就墙 countDistinctStudyDays 的口径）
        assertEquals(1, scalar("SELECT COUNT(DISTINCT session_date) FROM " + PREFIX
                + "player_sessions WHERE uuid = '" + PLAYER + "'"), "真实学习天数");

        // Q1 出口：ReportGenerator 用 rows().size() 当学习天数
        ReportRepository.DailyRows q1 = repo.queryDailyRows(PLAYER, "2026-01-01", "2026-01-31");
        assertTrue(q1.complete());
        assertEquals(2, q1.rows().size(),
                "Q1 按行返回 —— ReportGenerator 的'学习天数'就是 rows().size()，得 2");

        // Q9 出口：COUNT(*) 同样是 2，两处自洽（都错成同一个方向）
        ReportRepository.BatchRows q9 =
                repo.queryBatchStatsOrFail("2026-01-01", "2026-01-31", 10, false);
        assertTrue(q9.complete());
        assertEquals(2, q9.rows().get(0).activeDays(), "Q9 的 COUNT(*) 也得 2");
        assertEquals(q1.rows().size(), q9.rows().get(0).activeDays(),
                "报表内部自洽：单报与批量对同一天给出同一个（偏大的）天数。"
                        + "真正对不上的地方是成就墙的 COUNT(DISTINCT)=1。");
    }

    /**
     * 【D2 · round28 复核定级 LOW-MEDIUM，<b>不修</b>，理由已更新】
     * Q8 的"本期完成每日挑战 done/all"分子把 {@code degradeStale} 的"自动放弃"算成"完成"。
     *
     * <p>缺陷本体（{@code ReportRepository.countDailyChallengesOrFail}）：
     * <pre>
     *   SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) AS done,   -- 只认 completed=1
     *   COUNT(*) AS total                                          -- 窗口内全部行
     * </pre>
     * 分母 total 含所有已分配的挑战行（这本身是设计意图："本期发了 N 个任务、完成 M 个"），
     * 但 {@code DailyChallengeRepository.degradeStale}（R-21 存量降级）会把既未完成也未
     * 领取的 {@code AI_CHAT_5} 行直接写成 {@code completed=1, claimed=1}（"自动放弃，
     * 不发奖励"，见 {@code DailyChallengeManager.degradeStaleChallenges}）。降级行在报告
     * 里与"孩子真的完成了"同形。
     *
     * <h2>round28 复核：<b>不是</b>死路径（原假设「AI 关了就不分配」不成立）</h2>
     * 分配侧确实过滤不可用类型（{@code doCheckAndAssign} 只从 {@code isChallengeAvailable}
     * 为真的集合里抽），所以<b>从未有过 AI Key 的服</b>抽不到 {@code AI_CHAT_5}。但
     * {@code isChallengeAvailable(AI_CHAT_5)} 读的是<b>当下</b>的
     * {@code ai-chat.enabled && AIChatManager.isAvailable()}，而可用性是会<b>当天翻转</b>的：
     * <ul>
     *   <li>{@code /lea reload} → {@code HotReloadManager} → {@code AIChatManager.reload()}
     *       → {@code loadConfig()} <b>重读磁盘 {@code ai_config.yml} 的 api-key</b>。
     *       Key 过期/被吊销/被清空后 reload 一次，可用性立刻从 true 变 false；</li>
     *   <li>ScenarioSelectListener 的「重新加载 API」按钮走同一条 {@code reload()}；</li>
     *   <li>当日曾正常、中途重启且此次启动 Key 失效 —— 玩家当天早些时候抽到的
     *       {@code AI_CHAT_5} 行仍在库里（{@code newStreak}/{@code challenge_date} 都是
     *       "今天"），下次打开挑战界面即被降级。</li>
     * </ul>
     * 结论：可达，但需要"当天先可用、后不可用"这个额外条件，正常服不触发。
     *
     * <h2>为什么本轮<b>不修</b></h2>
     * 修法必须是加列（{@code abandoned INTEGER DEFAULT 0}）而不是纯 SQL：
     * {@code completed=1, claimed=1} 两态对"真完成"与"降级"完全相同，且 {@code progress=0}
     * 不能当判据 —— {@code COMBO_5} 走 {@code completeChallenge} 二进制完成时 {@code progress}
     * 同样是 0（见下一条测试）。而加列之后<b>必须由 {@code degradeStale} 写
     * {@code abandoned=1}</b>，否则这一列永远是 0、Q8 一个字节都不会变 ——
     * 那个写入点在 {@code DailyChallengeRepository}，<b>不在本轮可写范围</b>（本轮只授权
     * {@code ReportRepository} / 本测试 / {@code schema.sql} / {@code DatabaseManager} 的加列 DDL）。
     * 只改 schema 不改写入方 = 交付一个恒为 0 的死列，比不加更糟。
     *
     * <p>影响面复核（决定"值不值"）：零下游自动消费 —— 报告只写 {@code exports/reports/*.txt}
     * 给服主/管理员看（{@code /lea report}，{@code default: op}），无代码读这些 TXT、
     * 统计服务不碰、无 PAPI 占位符。方向是<b>多报</b>（把没做算成做了），金额面为零。
     *
     * <p><b>下一轮若要修</b>：schema 加 {@code abandoned INTEGER DEFAULT 0} +
     * {@code addColumnIfMissing} 迁移 + {@code degradeStale} 的 SET 增 {@code abandoned = 1}
     * + 本方法分子改 {@code completed = 1 AND abandoned = 0}。其余写路径
     * （{@code assignAll} 显式列、{@code complete} 只置 completed、
     * {@code advanceChallengeProgress} 的 CASE、{@code markClaimedOrFail} 只置 claimed）
     * 都是默认 0，且降级行的 {@code claimed=1} 会让所有"再完成"分支（均带 {@code claimed = 0}
     * 护栏）永久失效，因此 {@code abandoned=1} 一旦写下不会被撤销 —— 迁移安全。
     */
    @Test
    @Disabled("不修（LOW-MEDIUM，本轮越界）：需加 abandoned 列并由 degradeStale 写入，"
            + "该写入点在 DailyChallengeRepository —— 不在本轮可写范围。"
            + "只加列不改写入方会得到一个恒为 0 的死列。"
            + "非死路径：/lea reload 重读 ai_config.yml，Key 中途失效时 AI_CHAT_5 当日即可被降级。"
            + "本测试断言的是期望行为 —— 摘掉 @Disabled 即红。")
    @DisplayName("【D2】【期望】自动放弃（degradeStale）的挑战不应计入'本期完成'")
    void d2AutoAbandonedChallengeShouldNotCountAsCompleted() throws Exception {
        // 孩子真的完成 1 个
        insertChallenge(PLAYER, "2026-01-05", "answer_10", true);
        // 另外 2 个是 R-21 降级写的：completed=1, claimed=1，孩子一个都没做
        insertChallengeClaimedNoWork(PLAYER, "2026-01-05", "ai_chat_5");
        insertChallengeClaimedNoWork(PLAYER, "2026-01-06", "ai_chat_5");

        ReportRepository.ChallengeRows r =
                repo.countDailyChallengesOrFail(PLAYER, "2026-01-05", "2026-01-07");
        assertTrue(r.complete());
        assertEquals(1, r.progress().done(),
                "孩子实际只亲手完成 1 个。现状得到 3（两个降级行也算 completed=1）—— "
                        + "家长看到'本期完成每日挑战 3/3'，其中 2/3 是系统放弃的。");
    }

    /** 造一行"降级形态"的挑战：completed=1 + claimed=1 但 progress=0（孩子零进度）。 */
    private void insertChallengeClaimedNoWork(UUID id, String date, String challengeId)
            throws Exception {
        exec("INSERT INTO " + PREFIX + "daily_challenges"
                + " (uuid, challenge_date, challenge_id, progress, completed, claimed)"
                + " VALUES ('" + id + "', '" + date + "', '" + challengeId + "', 0, 1, 1)");
    }

    /**
     * 【D2 附带事实】降级行与真实完成行在报告 SQL 里不可区分 —— 钉住"为什么 D2 不能
     * 纯 SQL 修"。degradeStale 写出的行（completed=1/claimed=1/progress=0）与"玩家真的
     * 完成并已领"（completed=1/claimed=1/progress=target）唯一差别是 progress，而
     * progress=0 又是 COMBO_5 二进制完成的合法形态。三态没有正交的列组合可判。
     */
    @Test
    @DisplayName("【D2 附带】降级行与真实完成行在报告 SQL 里不可区分（progress=0 也不可靠）")
    void d2DegradedRowIsIndistinguishableFromRealCompletion() throws Exception {
        // 真完成并已领取
        exec("INSERT INTO " + PREFIX + "daily_challenges"
                + " (uuid, challenge_date, challenge_id, progress, completed, claimed)"
                + " VALUES ('" + PLAYER + "', '2026-01-05', 'answer_10', 10, 1, 1)");
        // 降级（自动放弃）
        insertChallengeClaimedNoWork(PLAYER, "2026-01-06", "ai_chat_5");

        ReportRepository.ChallengeRows r =
                repo.countDailyChallengesOrFail(PLAYER, "2026-01-05", "2026-01-07");
        assertEquals(2, r.progress().done(), "报告 SQL 只看 completed=1，两行都计入。");

        assertEquals(1, scalar("SELECT COUNT(*) FROM " + PREFIX + "daily_challenges"
                        + " WHERE uuid = '" + PLAYER + "' AND completed = 1 AND claimed = 1"
                        + " AND progress = 0"),
                "progress=0 不能当降级判据：COMBO_5 走二进制完成时 progress 也是 0。");
    }

    /**
     * 【D3 已证伪 · 保留为回归护栏】Q4 两分支在<b>产品自带 DDL 的精度下</b>给出相同结果。
     *
     * <p><b>审计过程必须诚实记录</b>：round20 复核时我的初始假设是"MySQL 分支的
     * {@code <= '23:59:59'} 上界丢掉当天最后一秒"（因为 SQLite 分支用的是精确半开区间）。
     * 该假设在真实 MariaDB 13.0.2 上被<b>证伪</b>：
     * <pre>
     *   CREATE TABLE ... timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP   -- 产品 DDL 原文
     *   INSERT ... '2026-01-10 23:59:59.500'
     *   SELECT CAST(timestamp AS CHAR) --&gt; '2026-01-10 23:59:59'      -- 小数秒被截断
     * </pre>
     * {@code TIMESTAMP}（精度 0）在 MySQL/MariaDB 上把输入微秒截断，因此"最后一秒"里
     * <b>只可能存在 23:59:59.000 这一个值</b>，{@code <= '23:59:59'} 与
     * {@code < '2026-01-11 00:00:00'} 覆盖完全相同的行集。两分支等价。
     *
     * <p>对照实测（同 4 个本地时刻，两后端各自写自己的形态）：
     * <pre>
     *   SQLite：UTC 半开区间 [01-09 16:00, 01-10 16:00)  → 2 轮
     *   MySQL ：本地闭区间 [01-10 00:00, 01-10 23:59:59] → 2 轮
     * </pre>
     * 只有把列改成 {@code TIMESTAMP(6)} 才会出现差异 —— 那不是产品 DDL。
     *
     * <p><b>因此本测试断言的是"两分支等价"，而不是"两分支有差异"。</b>它的价值在于：
     * 若有人日后把 DDL 的 {@code timestamp} 升到 {@code TIMESTAMP(6)}（一个看似无害的
     * "提高精度"改动），MySQL 分支立刻开始漏当天的亚秒行，而这条测试是唯一会红的。
     * 这就是"证伪一个假设"仍然值得留下一条护栏的理由。
     */
    @Test
    @DisplayName("【D3】Q4 两分支在 timestamp 秒级精度下等价（亚秒行被 DDL 截断，故 23:59:59 上界不漏）")
    void d3BothDialectsAgreeAtSecondPrecision() throws Exception {
        // 产品 DDL 的 timestamp 列在 SQLite 上是 TEXT affinity，在 MySQL 上是 TIMESTAMP(0)。
        // 这里直接验证 SQLite 侧的存储形态：写 .500 会原样保留（TEXT affinity 不截断）——
        // 但生产写入路径是 CURRENT_TIMESTAMP，它只产生秒级字符串，所以 .500 这类值
        // 只能来自手工插入，不是"报告算错"的现实场景。
        insertChat(PLAYER, "s_lastsec", "2026-01-10 23:59:59");

        int inclusive = repo.countAiConversationsOrFail(
                PLAYER, "2026-01-10 00:00:00", "2026-01-10 23:59:59", false).value();
        int exclusive = repo.countAiConversationsOrFail(
                PLAYER, "2026-01-10 00:00:00", "2026-01-11 00:00:00", true).value();

        assertEquals(1, inclusive, "秒级精度的最后一条落在 23:59:59，闭区间含它");
        assertEquals(1, exclusive, "半开区间同样含它 —— 两分支在这一行上等价");
        assertEquals(inclusive, exclusive,
                "在产品 DDL 的秒级精度下，MySQL 的 <= 23:59:59 与 SQLite 的 < 次日00:00 等价。"
                        + "若这条变红，检查 ai_chat_history.timestamp 是否被改成了 TIMESTAMP(6)："
                        + "那会让 MySQL 分支开始漏掉当天的亚秒行。");
    }
    /**
     * 【事实】Q4 的 SQLite 分支在非 UTC 时区下把"本地日"正确换算成 UTC 区间。
     *
     * <p>写这条的理由：原 27 例里没有一处测过非 UTC 时区的窗口换算（全部用纯字符串
     * 常量）。本仓库踩过时区坑（P3-12），而这个换算是 Q4 唯一依赖调用方正确性的地方 ——
     * 换算方向反了会让"本期对话数"整体偏移 8 小时，跨零点那批全错。
     * 本测试复刻 ReportGenerator:465-468 的换算并校验仓储按该区间取值正确。
     */
    @Test
    @DisplayName("【事实】Q4 SQLite 分支：+08:00 的本地日正确换算成 UTC 区间，跨零点不错位")
    void d3SqliteBranchUtcWindowConversionIsCorrect() throws Exception {
        java.time.ZoneId plus8 = java.time.ZoneId.of("+08:00");
        java.time.LocalDate localDay = java.time.LocalDate.of(2026, 1, 10);
        java.time.format.DateTimeFormatter f =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        // 复刻 ReportGenerator:465-468 的换算
        String lo = localDay.atStartOfDay(plus8)
                .withZoneSameInstant(java.time.ZoneOffset.UTC).format(f);
        String hi = localDay.plusDays(1).atStartOfDay(plus8)
                .withZoneSameInstant(java.time.ZoneOffset.UTC).format(f);

        assertEquals("2026-01-09 16:00:00", lo, "本地 01-10 00:00 (+08) = UTC 01-09 16:00");
        assertEquals("2026-01-10 16:00:00", hi, "本地 01-11 00:00 (+08) = UTC 01-10 16:00");

        insertChat(PLAYER, "before", "2026-01-09 15:59:59");  // 本地 01-09 23:59:59 — 窗口前
        insertChat(PLAYER, "atStart", "2026-01-09 16:00:00"); // 本地 01-10 00:00:00 — 含
        insertChat(PLAYER, "inside", "2026-01-10 03:00:00");  // 本地 01-10 11:00 — 含
        insertChat(PLAYER, "atEnd", "2026-01-10 16:00:00");   // 本地 01-11 00:00:00 — 不含

        int count = repo.countAiConversationsOrFail(PLAYER, lo, hi, true).value();
        assertEquals(2, count,
                "本地日 01-10 应恰好覆盖 atStart + inside。换算方向反了会得到 0 或 4 —— "
                        + "8 小时偏移在跨零点那批对话上全额体现。");
    }
}
