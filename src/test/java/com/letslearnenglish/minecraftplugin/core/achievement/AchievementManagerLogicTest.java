package com.letslearnenglish.minecraftplugin.core.achievement;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round31：{@code AchievementManager} 的<b>判定逻辑</b>验收。
 *
 * <h2>为什么需要这个类</h2>
 * <p>{@code grep -rl AchievementManager src/test/java/} 命中 13 个测试文件，但 jacoco 报它的
 * <b>行覆盖是 0%</b> —— 那 13 个文件只把它当<b>协作者</b>用（{@code mock(AchievementManager.class)}
 * 7 处、只引用 {@code Achievement.DUEL_FIRST_WIN} 之类的常量、或读它的源码做结构性 lint），
 * 没有一处真的 {@code new AchievementManager(...)} 跑过判定。
 *
 * <p>本类因此<b>只</b>打真实判定：
 * <ul>
 *   <li>{@link AchievementManager#tryUnlock} 的只增不减与 R3 判定顺序；</li>
 *   <li>每一个数值阈值的边界（24/25、99/100、499/500、4999/5000…）；</li>
 *   <li>读失败时 fail-closed（不解锁、不撤销）；</li>
 *   <li>{@link AchievementManager#getProgressSnapshot} 的进度值与目标值。</li>
 * </ul>
 *
 * <h2>夹具策略（为什么不是纯 Mockito）</h2>
 * <p>{@code tryUnlock} 的第一件事是 {@code loadPlayerAchievements} → 仓储 SELECT，而
 * <b>读失败与「没解锁过」是两种语义</b>（A6-1：前者返回 {@code null}），且解锁要真的落库 ——
 * 这两件事用 mock 的 {@code ResultSet} 只能自证桩。故正常路径用<b>真 SQLite</b>
 * （{@code DatabaseManager.initialize()} 自带建表；DROP 表可制造真实 SQLException），
 * 读失败路径用「除 isClosed/close 外一律抛 SQLException」的连接替身（照
 * {@code ResetFailureVisibilityTest} 的既有范式），只有 {@link PlayerDataStore} 与
 * {@link SocialManager} 是 mock。
 *
 * <p>{@code Bukkit} 一律 {@code mockStatic}：通知段读 {@code Bukkit.isPrimaryThread()}，
 * mockStatic 默认 false 会走进 {@code Bukkit.getScheduler().runTask(...)} 并在 null 上 NPE，
 * 故钉成 true；{@code Bukkit.getPlayer} 保持默认 null → 通知段在 {@code player == null}
 * 处静默 return（这正是「玩家离线时只落库不弹提示」的既有语义）。
 */
class AchievementManagerLogicTest {

    /** 测试用的 SQL 表前缀（{@code database.mysql.table-prefix}）。 */
    private static final String PREFIX = "lle_";

    @TempDir
    Path tempDir;

    /**
     * mock 的 Logger：写失败路径（G1 的 DROP TABLE、C1 的不可用连接）会走
     * {@code logger.log(Level.WARNING, ..., e)}，用真 Logger 会把整段堆栈打到控制台，
     * 把 surefire 输出淹掉。这里只需要「日志调用被接受」，不需要真的打印。
     */
    private Logger logger;

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration mainConfig;
    private DatabaseManager db;
    private PlayerDataStore dataStore;
    private SocialManager socialManager;
    private AchievementManager manager;
    private File dbFile;
    private File achConfigFile;
    private UUID player;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        socialManager = mock(SocialManager.class);
        dataStore = mock(PlayerDataStore.class);
        logger = mock(Logger.class);
        player = UUID.randomUUID();

        mainConfig = new YamlConfiguration();
        mainConfig.set("database.type", "sqlite");
        mainConfig.set("database.sqlite.filename", "ach-logic.db");
        mainConfig.set("database.mysql.table-prefix", PREFIX);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(configManager.isAchievementsEnabled()).thenReturn(true);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        // 预先落一个只有注释的配置文件：让 getAchievementConfig() 走「文件存在 → 直接加载」，
        // 而不是「文件不存在 → saveResource（mock 上是 no-op）→ 加载不存在的文件」。
        // 内容用一个 YAML 注释行而不是真空文件（真空文档在部分 SnakeYAML 版本上解析结果不稳）。
        achConfigFile = tempDir.resolve("achievements-config.yml").toFile();
        Files.writeString(achConfigFile.toPath(), "# round31 fixture: 全部取默认值\n");

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        dbFile = tempDir.resolve("ach-logic.db").toFile();
        when(plugin.getDatabaseManager()).thenReturn(db);

        // 默认统计：0 答对 / 0 答错 / 0 会话。需要别的值时用 stubStats(...) 覆盖。
        stubStats(0, 0, 0);

        bukkit = mockStatic(Bukkit.class);
        // 主线程：通知段走内联分支（Bukkit.getPlayer 默认 null → 静默跳过）
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

        manager = new AchievementManager(plugin, dataStore);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    //  夹具工具
    // ==================================================================

    /** 覆盖 {@link PlayerDataStore#getPlayerStats} —— 计数全部走公开的 PlayerStats API。 */
    private void stubStats(int correct, int incorrect, int sessions) {
        PlayerDataStore.PlayerStats stats = new PlayerDataStore.PlayerStats();
        stats.addCorrect(correct);
        stats.addIncorrect(incorrect);
        stats.setSessions(sessions);
        when(dataStore.getPlayerStats(player)).thenReturn(stats);
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private void insertCoins(long amount) throws Exception {
        exec("INSERT INTO " + PREFIX + "player_data (uuid, total_coins_earned, updated_at) VALUES ('"
                + player + "', " + amount + ", 0) "
                + "ON CONFLICT(uuid) DO UPDATE SET total_coins_earned = " + amount);
    }

    private void insertSessionDay(LocalDate date, int reviewWords) throws Exception {
        exec("INSERT INTO " + PREFIX + "player_sessions "
                + "(uuid, session_date, words_count, correct_count, score, mode, review_words) VALUES ('"
                + player + "', '" + date + "', 10, 10, 100, 'normal', " + reviewWords + ") "
                + "ON CONFLICT(uuid, session_date) DO UPDATE SET review_words = " + reviewWords);
    }

    private long achievementRows() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM " + PREFIX + "achievements WHERE uuid = '" + player + "'")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    private long unlockedAtInDb(String achievementId) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT unlocked_at FROM " + PREFIX + "achievements "
                     + "WHERE uuid = '" + player + "' AND achievement_id = '" + achievementId + "'")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    /** 「连接不可用」替身：除 isClosed/close/Object 外一律抛 SQLException（仓储据此返回 null）。 */
    private static Connection unavailableConnection() {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            return Boolean.FALSE;
                        case "close":
                            return null;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "UnavailableConnection";
                        default:
                            throw new java.sql.SQLException(
                                    "Database connection unavailable: " + method.getName());
                    }
                });
    }

    private static int[] progressOf(Map<Achievement, int[]> snapshot, Achievement a) {
        int[] v = snapshot.get(a);
        assertNotNull(v, a + " 应出现在进度快照里（progressSource != NONE）");
        return v;
    }

    // ==================================================================
    //  A. 只增不减 / 重复触发 / R3 判定顺序
    // ==================================================================

    @Nested
    @DisplayName("组 A —— tryUnlock 的幂等与 R3 判定顺序")
    class UnlockOnce {

        /**
         * 删掉 {@code unlocked.contains} 短路 → 第二次调用会真的再走一遍写库与通知。
         * 断言点选 {@code SocialManager.recordAchievement}（解锁副作用的可观测出口），
         * 而不是只看返回值 —— 返回值在「短路被删」的实现里也可能被凑成 false。
         */
        @Test
        @DisplayName("A1：已解锁的再调一次 → 不重复写库、不重复产生动态、不覆盖解锁时间")
        void secondUnlockIsNoOp() throws Exception {
            when(plugin.getSocialManager()).thenReturn(socialManager);

            assertTrue(manager.tryUnlock(player, Achievement.FIRST_WORD), "首次解锁应成功");
            Long firstAt = manager.getUnlockedAt(player, Achievement.FIRST_WORD);
            assertNotNull(firstAt, "解锁时间必须写入 unlockedAtCache（成就墙「解锁于」用它渲染）");
            assertEquals(1, achievementRows(), "首次解锁应恰好写 1 行");

            assertFalse(manager.tryUnlock(player, Achievement.FIRST_WORD),
                    "已解锁再调用必须短路返回 false —— 删掉 contains 短路就会变 true");

            assertEquals(1, achievementRows(), "重复调用不得再写一行（UNIQUE 只是兜底，短路是第一道闸门）");
            verify(socialManager, times(1)).recordAchievement(player, "first_word");
            assertEquals(firstAt, manager.getUnlockedAt(player, Achievement.FIRST_WORD),
                    "重复调用不得改写解锁时间（撞键 no-op + 短路双保险）");
            assertEquals(firstAt.longValue(), unlockedAtInDb("first_word"),
                    "库里的 unlocked_at 必须仍是首次解锁时刻");
        }

        /**
         * R3 判定顺序第 3 步「{@code unlocked.contains} 在 {@link AchievementManager#meetsPrerequisites}
         * 之前」的<b>可观测</b>证据：已解锁玩家重复调用时，前置判定<b>根本不该被求值</b>。
         *
         * <p>顺序反了（前置在前）时返回值完全相同，只有副作用能区分 ——
         * {@code COINS_500} 的前置会去读 {@code getPlayerStats}。
         */
        @Test
        @DisplayName("A2：已解锁玩家的重复调用不得触发前置判定（contains 必须在前置之前）")
        void containsShortCircuitsBeforePrerequisites() {
            stubStats(60, 0, 1);   // 答题 60 ≥ 50 → COINS_500 的前置满足
            assertTrue(manager.tryUnlock(player, Achievement.COINS_500), "前置已满足，应解锁");

            clearInvocations(dataStore);
            assertFalse(manager.tryUnlock(player, Achievement.COINS_500), "重复调用必须返回 false");
            verify(dataStore, never()).getPlayerStats(player);
        }

        @Test
        @DisplayName("A3：并发权威闸门 —— Set.add 的返回值决定成败，第二次必失败")
        void addIsTheAuthority() {
            assertTrue(manager.tryUnlock(player, Achievement.STREAK_3));
            assertFalse(manager.tryUnlock(player, Achievement.STREAK_3));
            assertEquals(1, manager.getUnlockedCount(player));
        }
    }

    // ==================================================================
    //  B. 阈值边界（>= 而不是 >）
    // ==================================================================

    @Nested
    @DisplayName("组 B —— 阈值边界：差一个不解锁，正好解锁")
    class Thresholds {

        @Test
        @DisplayName("B1：WORDS_25 —— 24 不解锁，25 解锁")
        void words25Boundary() {
            stubStats(24, 0, 0);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.WORDS_25),
                    "24 答对不得解锁 WORDS_25");

            stubStats(25, 0, 0);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.WORDS_25),
                    "25 答对必须解锁 —— 阈值是 >=，把 >= 改成 > 这里会红");
        }

        @Test
        @DisplayName("B2：WORDS_100 —— 99 不解锁，100 解锁")
        void words100Boundary() {
            stubStats(99, 0, 0);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.WORDS_100));

            stubStats(100, 0, 0);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.WORDS_100));
        }

        @Test
        @DisplayName("B3：WORDS_500 —— 499 不解锁，500 解锁（前置满足时）")
        void words500Boundary() {
            stubStats(499, 0, 1);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.WORDS_500), "499 不得解锁 WORDS_500");

            stubStats(500, 0, 1);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.WORDS_500),
                    "500 答对 + 答题数 500（≥100）+ 正确率 100%（≥70%）必须解锁");
        }

        @Test
        @DisplayName("B4：WORDS_500 的前置 —— 答对 500 但正确率 <70% 不解锁")
        void words500PrerequisiteBlocks() {
            stubStats(500, 300, 10);   // 共 800 题，正确率 62.5% < 70%
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.WORDS_500),
                    "正确率不足 70% 时不得解锁（前置守卫被删会在这里变绿）");
            assertTrue(manager.hasAchievement(player, Achievement.WORDS_100), "100 答对仍应解锁");
        }

        @Test
        @DisplayName("B5：SESSIONS_10 / SESSIONS_50 —— 9/10、49/50（后者还需 STREAK_10）")
        void sessionBoundaries() {
            stubStats(0, 0, 9);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.SESSIONS_10), "9 场不得解锁");

            stubStats(0, 0, 10);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.SESSIONS_10), "10 场必须解锁");

            stubStats(0, 0, 49);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.SESSIONS_50),
                    "49 场不得解锁；且 STREAK_10 未解锁，前置也不满足");

            stubStats(0, 0, 50);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.SESSIONS_50),
                    "50 场但前置（STREAK_10）未满足 → 不得解锁");

            assertTrue(manager.tryUnlock(player, Achievement.STREAK_10), "手工解锁前置");
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.SESSIONS_50),
                    "前置满足后 50 场必须解锁（把 >= 改成 > 会在这里红）");
        }

        @Test
        @DisplayName("B6：ACCURACY_90 —— 99 题不解锁；89% 不解锁；恰好 90% 解锁")
        void accuracyBoundary() {
            stubStats(90, 9, 5);   // 共 99 题
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.ACCURACY_90), "样本不足 100 题不得解锁");

            stubStats(89, 11, 5);  // 共 100 题，89%
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.ACCURACY_90), "89% 不得解锁");

            stubStats(90, 10, 5);  // 共 100 题，恰好 90%
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.ACCURACY_90),
                    "恰好 90% 必须解锁 —— 整数比较 correct*100 >= total*90 的边界，"
                            + "改成浮点或 > 都会在这里露馅");
        }

        @Test
        @DisplayName("B7：COINS_500 —— 499 不解锁，500 解锁（前置：答题 ≥50）")
        void coins500Boundary() throws Exception {
            stubStats(50, 0, 5);
            insertCoins(499);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.COINS_500), "499 金币不得解锁");

            insertCoins(500);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.COINS_500),
                    "500 金币必须解锁 —— 阈值是 >= 不是 >");
        }

        @Test
        @DisplayName("B8：COINS_500 的前置 —— 金币够但答题 <50 不解锁")
        void coins500PrerequisiteBlocks() throws Exception {
            stubStats(30, 19, 5);   // 共 49 题
            insertCoins(600);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.COINS_500),
                    "答题 49 < 50 → 前置不满足，金币再多也不解锁");
        }

        @Test
        @DisplayName("B9：COINS_5000 —— 4999 不解锁，5000 解锁（前置：WORDS_100 已解锁）")
        void coins5000Boundary() throws Exception {
            stubStats(100, 0, 5);   // 让 WORDS_100 解锁（同时满足 COINS_500 前置）
            insertCoins(4999);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.WORDS_100), "前置 WORDS_100 应已解锁");
            assertFalse(manager.hasAchievement(player, Achievement.COINS_5000), "4999 不得解锁");
            assertTrue(manager.hasAchievement(player, Achievement.COINS_500), "4999 ≥ 500 应解锁 COINS_500");

            insertCoins(5000);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.COINS_5000),
                    "5000 金币必须解锁 —— 阈值是 >= 不是 >");
        }

        @Test
        @DisplayName("B10：COINS_5000 的前置 —— 金币够但 WORDS_100 未解锁则不解锁")
        void coins5000PrerequisiteBlocks() throws Exception {
            stubStats(50, 0, 5);   // 答题 50 → COINS_500 前置满足；答对 50 <100 → WORDS_100 未解锁
            insertCoins(6000);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.COINS_500), "COINS_500 应解锁");
            assertFalse(manager.hasAchievement(player, Achievement.COINS_5000),
                    "WORDS_100 未解锁时 6000 金币也不得解锁 COINS_5000");
        }

        @Test
        @DisplayName("B11：STREAK_3 / STREAK_10 —— 2/3、9/10，且连击峰值必须落盘")
        void streakBoundaries() {
            manager.checkStreakAchievement(player, 2);
            assertFalse(manager.hasAchievement(player, Achievement.STREAK_3), "连击 2 不得解锁");

            manager.checkStreakAchievement(player, 3);
            assertTrue(manager.hasAchievement(player, Achievement.STREAK_3), "连击 3 必须解锁");

            manager.checkStreakAchievement(player, 9);
            assertFalse(manager.hasAchievement(player, Achievement.STREAK_10), "连击 9 不得解锁");

            manager.checkStreakAchievement(player, 10);
            assertTrue(manager.hasAchievement(player, Achievement.STREAK_10), "连击 10 必须解锁");
            // M9：会话连击峰值落盘（只有值为 10 的那次调用匹配这条 verify）
            verify(dataStore).persistLongestStreak(player, 10);
        }

        @Test
        @DisplayName("B12：WRONG_BOOK_CLEARED —— 数量与「已清空」是与关系")
        void wrongBookClearedBoundary() {
            manager.checkWrongBookCleared(player, 4, true);
            assertFalse(manager.hasAchievement(player, Achievement.WRONG_BOOK_CLEARED), "累计 4 个不够");

            manager.checkWrongBookCleared(player, 5, false);
            assertFalse(manager.hasAchievement(player, Achievement.WRONG_BOOK_CLEARED),
                    "累计够了但错题本没清空 → 不得解锁（把 && 改成 || 会在这里变绿）");

            manager.checkWrongBookCleared(player, 5, true);
            assertTrue(manager.hasAchievement(player, Achievement.WRONG_BOOK_CLEARED), "5 个且已清空必须解锁");
        }

        @Test
        @DisplayName("B13：AI_CHAT_10 / AI_CHAT_50 —— 9/10、49/50")
        void aiChatBoundaries() {
            manager.checkAIChatAchievement(player, 9);
            assertFalse(manager.hasAchievement(player, Achievement.AI_CHAT_10), "9 次不得解锁");

            manager.checkAIChatAchievement(player, 10);
            assertTrue(manager.hasAchievement(player, Achievement.AI_CHAT_10), "10 次必须解锁");

            manager.checkAIChatAchievement(player, 49);
            assertFalse(manager.hasAchievement(player, Achievement.AI_CHAT_50), "49 次不得解锁");

            manager.checkAIChatAchievement(player, 50);
            assertTrue(manager.hasAchievement(player, Achievement.AI_CHAT_50), "50 次必须解锁");
        }

        @Test
        @DisplayName("B14：DAILY_STREAK_3 —— 连续 2 天不解锁，3 天解锁（今天计入）")
        void dailyStreakBoundary() throws Exception {
            LocalDate today = LocalDate.now();
            insertSessionDay(today, 0);
            insertSessionDay(today.minusDays(1), 0);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.DAILY_STREAK_3), "连续 2 天不得解锁");

            insertSessionDay(today.minusDays(2), 0);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.DAILY_STREAK_3),
                    "连续 3 天必须解锁（会话先落库后判定的排序保证今天计入）");
            assertFalse(manager.hasAchievement(player, Achievement.DAILY_STREAK_7), "3 天不得解锁 7 天成就");
        }

        @Test
        @DisplayName("B15：DAILY_STREAK_7 —— 连续 7 天解锁，6 天不解锁")
        void dailyStreakSeven() throws Exception {
            LocalDate today = LocalDate.now();
            for (int i = 0; i < 6; i++) {
                insertSessionDay(today.minusDays(i), 0);
            }
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.DAILY_STREAK_7), "连续 6 天不得解锁");

            insertSessionDay(today.minusDays(6), 0);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.DAILY_STREAK_7), "连续 7 天必须解锁");
        }

        @Test
        @DisplayName("B16：连续天数的断点语义 —— 今天缺不打断（i==0 允许缺席）")
        void consecutiveDaysAllowsMissingToday() throws Exception {
            LocalDate today = LocalDate.now();
            insertSessionDay(today.minusDays(1), 0);
            insertSessionDay(today.minusDays(2), 0);
            insertSessionDay(today.minusDays(3), 0);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.DAILY_STREAK_3),
                    "今天还没学不该打断昨天起的 3 连 —— 把 i==0 的特例删掉这里会红");
        }

        @Test
        @DisplayName("B17：昨天缺 → 计数只剩今天 1 天，不攒到 3")
        void consecutiveDaysBreaksAtYesterday() throws Exception {
            LocalDate today = LocalDate.now();
            insertSessionDay(today, 0);
            insertSessionDay(today.minusDays(2), 0);
            insertSessionDay(today.minusDays(3), 0);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.DAILY_STREAK_3),
                    "昨天缺席时「今天 + 前天 + 大前天」绝不能算 3 连（删掉 else if (i>0) break 会变绿）");
        }

        @Test
        @DisplayName("B18：REVIEW_STREAK_7 —— 只数 review_words>0 的日子（今天 0 词不算）")
        void reviewStreakIgnoresZeroReviewDays() throws Exception {
            LocalDate today = LocalDate.now();
            insertSessionDay(today, 0);                           // 今天复习了但 0 词 → 不算复习日
            for (int i = 1; i <= 6; i++) {
                insertSessionDay(today.minusDays(i), 3);
            }
            manager.checkSessionAchievements(player, 0, 0, 0, "review");
            assertFalse(manager.hasAchievement(player, Achievement.REVIEW_STREAK_7),
                    "今天的 review_words=0 不算复习日，最长连续复习日只有 6 天");
            assertTrue(manager.hasAchievement(player, Achievement.DAILY_STREAK_7),
                    "普通连续学习日仍是 7 天 → DAILY_STREAK_7 应解锁（两条判定口径必须分开）");

            insertSessionDay(today, 2);
            manager.checkSessionAchievements(player, 0, 0, 0, "review");
            assertTrue(manager.hasAchievement(player, Achievement.REVIEW_STREAK_7),
                    "今天也答对了复习词 → 连续 7 个复习日，必须解锁");
        }

        @Test
        @DisplayName("B19：STUDY_DAYS_30 —— 29 个不同日子不解锁，30 个解锁（可不连续）")
        void studyDaysBoundary() throws Exception {
            LocalDate today = LocalDate.now();
            for (int i = 0; i < 29; i++) {
                insertSessionDay(today.minusDays(i * 2L), 0);   // 隔天，验证「无需连续」
            }
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.STUDY_DAYS_30), "29 天不得解锁");

            insertSessionDay(today.minusDays(58), 0);
            manager.checkSessionAchievements(player, 0, 0, 0, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.STUDY_DAYS_30),
                    "30 个不同日子必须解锁（COUNT(DISTINCT session_date) 口径，与连续判定不同源）");
        }

        @Test
        @DisplayName("B20：PERFECT_SESSION 与 SPELLING_MASTER 的收紧条件")
        void perfectAndSpellingGates() {
            stubStats(3, 0, 1);
            manager.checkSessionAchievements(player, 3, 0, 3, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.PERFECT_SESSION), "全对应解锁完美会话");

            stubStats(0, 5, 2);
            manager.checkSessionAchievements(player, 0, 5, 5, "spelling");
            assertFalse(manager.hasAchievement(player, Achievement.SPELLING_MASTER),
                    "spelling 模式但一题没拼对 → 不得解锁（只判 mode 会在这里变绿）");

            stubStats(1, 4, 3);
            manager.checkSessionAchievements(player, 1, 4, 5, "spelling");
            assertTrue(manager.hasAchievement(player, Achievement.SPELLING_MASTER), "拼对 1 题即应解锁");
        }

        @Test
        @DisplayName("B21：FIRST_WORD —— 会话内答对 ≥1 题即解锁，不必整场零失误")
        void firstWordNeedsOneCorrect() {
            stubStats(1, 3, 1);
            manager.checkSessionAchievements(player, 1, 3, 4, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.FIRST_WORD),
                    "答对 1 题就该解锁 FIRST_WORD（退回「整场零失误」会在这里红）");
            assertFalse(manager.hasAchievement(player, Achievement.PERFECT_SESSION), "有答错就不是完美会话");

            UUID other = UUID.randomUUID();
            when(dataStore.getPlayerStats(other)).thenReturn(new PlayerDataStore.PlayerStats());
            manager.checkSessionAchievements(other, 0, 4, 4, "normal");
            assertFalse(manager.hasAchievement(other, Achievement.FIRST_WORD), "一题没答对不得解锁");
        }
    }

    // ==================================================================
    //  C. 读失败方向：fail-closed
    // ==================================================================

    @Nested
    @DisplayName("组 C —— 成就表读失败时绝不解锁（fail-closed）")
    class ReadFailure {

        /** 用「除 isClosed/close 外一律抛 SQLException」的替身，让仓储真的走到 {@code return null}。 */
        private AchievementManager failingManager() {
            LetsLearnEnglish failingPlugin = mock(LetsLearnEnglish.class);
            DatabaseManager failingDb = mock(DatabaseManager.class);
            when(failingDb.getConnectionLock()).thenReturn(new Object());
            when(failingDb.getConnection()).thenReturn(unavailableConnection());
            when(failingDb.getTablePrefix()).thenReturn(PREFIX);
            when(failingPlugin.getDatabaseManager()).thenReturn(failingDb);
            when(failingPlugin.getLogger()).thenReturn(logger);
            when(failingPlugin.getConfigManager()).thenReturn(configManager);
            when(failingPlugin.getDataFolder()).thenReturn(tempDir.toFile());
            return new AchievementManager(failingPlugin, dataStore);
        }

        /**
         * 关键回归：A6-1 之前，读失败返回空 Map → 被当成「没解锁过」→ {@code contains} 恒 false
         * → 已解锁成就被<b>重新解锁并重复弹提示</b>。现在的语义是「不知道」→ 不放行。
         *
         * <p>变异：把 {@code tryUnlock} 里 {@code unlocked == null} 的 {@code return false}
         * 换回「建空集继续」，C1 会变红（返回 true）。
         */
        @Test
        @DisplayName("C1：读失败 → tryUnlock 返回 false，且缓存保持未加载")
        void readFailureBlocksUnlock() {
            AchievementManager failing = failingManager();

            assertFalse(failing.tryUnlock(player, Achievement.FIRST_WORD),
                    "读失败时不得解锁 —— 把「不知道」当「没有解锁过」会让已解锁成就被重复解锁");
            assertFalse(failing.hasAchievement(player, Achievement.FIRST_WORD),
                    "缓存未加载时 hasAchievement 必须为 false（重试加载也失败）");
            assertEquals(0, failing.getUnlockedCount(player), "缓存不可用 → 计数为 0（视图层口径）");
            assertTrue(failing.getUnlockedAchievements(player).isEmpty(),
                    "缓存不可用 → 已解锁集合为空，不得伪造");
            assertNull(failing.getUnlockedAt(player, Achievement.FIRST_WORD), "缓存不可用 → 无解锁时间");
        }

        @Test
        @DisplayName("C2：阈值全部爆表也不得放行一支「猜」出来的解锁")
        void readFailureDoesNotUnlockEvenWithPerfectStats() throws Exception {
            stubStats(1000, 0, 100);
            insertCoins(100000);
            AchievementManager failing = failingManager();

            failing.checkSessionAchievements(player, 10, 0, 10, "normal");
            failing.checkStreakAchievement(player, 20);
            failing.checkAIChatAchievement(player, 100);
            failing.checkWrongBookCleared(player, 10, true);

            assertTrue(failing.getUnlockedAchievements(player).isEmpty(),
                    "数据读不到时任何阈值都不该让成就在内存里冒出来");
        }
    }

    // ==================================================================
    //  D. 金币读失败（两态 getCoinsEarned）的方向验证
    // ==================================================================

    @Nested
    @DisplayName("组 D —— getCoinsEarned 的 0 与失败：只延后，绝不撤销")
    class CoinsReadFailureDirection {

        /**
         * 独立复核上一轮的结论：{@code AchievementManager:503} 仍用两态
         * {@code getCoinsEarned}（失败折成 0），而这个 0 只会导致<b>本次不解锁</b>，
         * 不会撤销既有解锁 —— 因为 {@code tryUnlock} 的 {@code unlocked.contains} 短路在前，
         * 且全类没有 {@code unlocked.remove(单条)}。
         */
        @Test
        @DisplayName("D1：金币读失败折成 0 → 已解锁的 COINS_500 保留、未达标的 COINS_5000 不解锁")
        void coinsZeroDoesNotRevoke() throws Exception {
            stubStats(100, 0, 5);
            insertCoins(600);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.COINS_500), "600 金币应解锁 COINS_500");
            long rowsBefore = achievementRows();
            assertTrue(rowsBefore >= 1, "至少应写入 COINS_500 一行");

            // 模拟两态读失败：getCoinsEarned 折成 0（真实实现 catch SQLException 后返回 0）
            DatabaseManager collapsingDb = mock(DatabaseManager.class);
            when(collapsingDb.getCoinsEarned(player)).thenReturn(0L);
            when(plugin.getDatabaseManager()).thenReturn(collapsingDb);

            manager.checkSessionAchievements(player, 1, 0, 1, "normal");

            assertTrue(manager.hasAchievement(player, Achievement.COINS_500),
                    "一次读失败把金币读成 0，绝不能让已解锁的 COINS_500 消失（有撤销路径则定级必须上调）");
            assertFalse(manager.hasAchievement(player, Achievement.COINS_5000),
                    "读失败变成 0 → 本次不解锁 COINS_5000（fail-closed，方向安全）");
            assertEquals(rowsBefore, achievementRows(), "库里的解锁行不得被删");
        }

        @Test
        @DisplayName("D2：金币真值归零也不撤销已解锁的 COINS_500")
        void realZeroDoesNotRevoke() throws Exception {
            stubStats(100, 0, 5);
            insertCoins(500);
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.COINS_500));

            long rowsBefore = achievementRows();
            insertCoins(0);   // 真值归零（例如管理员清账）
            manager.checkSessionAchievements(player, 1, 0, 1, "normal");
            assertTrue(manager.hasAchievement(player, Achievement.COINS_500),
                    "余额归零不得回撤成就 —— 成就语义是「曾经达成」");
            assertEquals(rowsBefore, achievementRows(), "不得删除已写入的解锁行");
        }
    }

    // ==================================================================
    //  E. getProgressSnapshot
    // ==================================================================

    @Nested
    @DisplayName("组 E —— getProgressSnapshot：成就墙进度条的值与目标")
    class ProgressSnapshot {

        private void seedFullData() throws Exception {
            stubStats(42, 8, 7);                       // 共 50 题，正确率 84%
            insertCoins(1234);
            when(dataStore.getConversationCount(player)).thenReturn(31);
            when(dataStore.getWrongBookCleared(player)).thenReturn(2);
            LocalDate today = LocalDate.now();
            insertSessionDay(today, 0);                // 今天：复习 0 词
            insertSessionDay(today.minusDays(1), 4);   // 昨天：复习 4 词
            insertSessionDay(today.minusDays(2), 0);
        }

        @Test
        @DisplayName("E1：每一路进度的 current 与 target 都正确")
        void progressValues() throws Exception {
            seedFullData();
            Map<Achievement, int[]> snap = manager.getProgressSnapshot(player);

            assertEquals(42, progressOf(snap, Achievement.WORDS_25)[0], "current 应是总答对数");
            assertEquals(25, progressOf(snap, Achievement.WORDS_25)[1], "target 取自 enum，不得错位");
            assertEquals(42, progressOf(snap, Achievement.WORDS_100)[0]);
            assertEquals(100, progressOf(snap, Achievement.WORDS_100)[1]);
            assertEquals(42, progressOf(snap, Achievement.WORDS_500)[0]);
            assertEquals(500, progressOf(snap, Achievement.WORDS_500)[1]);

            assertEquals(7, progressOf(snap, Achievement.SESSIONS_10)[0], "会话数取自 totalSessions");
            assertEquals(10, progressOf(snap, Achievement.SESSIONS_10)[1]);
            assertEquals(7, progressOf(snap, Achievement.SESSIONS_50)[0]);
            assertEquals(50, progressOf(snap, Achievement.SESSIONS_50)[1]);

            assertEquals(1234, progressOf(snap, Achievement.COINS_500)[0], "金币取自 total_coins_earned");
            assertEquals(500, progressOf(snap, Achievement.COINS_500)[1]);
            assertEquals(1234, progressOf(snap, Achievement.COINS_5000)[0]);
            assertEquals(5000, progressOf(snap, Achievement.COINS_5000)[1]);

            assertEquals(31, progressOf(snap, Achievement.AI_CHAT_10)[0], "对话数是独立来源，不得与 stats 串台");
            assertEquals(10, progressOf(snap, Achievement.AI_CHAT_10)[1]);
            assertEquals(50, progressOf(snap, Achievement.AI_CHAT_50)[1]);

            assertEquals(84, progressOf(snap, Achievement.ACCURACY_90)[0],
                    "正确率是整数百分比 42*100/50 = 84（与 N3 判定同口径）");
            assertEquals(90, progressOf(snap, Achievement.ACCURACY_90)[1]);

            assertEquals(3, progressOf(snap, Achievement.DAILY_STREAK_3)[0], "今天起连续 3 天");
            assertEquals(3, progressOf(snap, Achievement.DAILY_STREAK_3)[1]);
            assertEquals(3, progressOf(snap, Achievement.DAILY_STREAK_7)[0]);
            assertEquals(7, progressOf(snap, Achievement.DAILY_STREAK_7)[1]);
            assertEquals(1, progressOf(snap, Achievement.REVIEW_STREAK_7)[0],
                    "复习日只数 review_words>0：今天 0 词不计，昨天 4 词算 1 天");
            assertEquals(7, progressOf(snap, Achievement.REVIEW_STREAK_7)[1]);

            assertEquals(3, progressOf(snap, Achievement.STUDY_DAYS_30)[0], "3 个不同日子");
            assertEquals(30, progressOf(snap, Achievement.STUDY_DAYS_30)[1]);

            assertEquals(2, progressOf(snap, Achievement.WRONG_BOOK_CLEARED)[0]);
            assertEquals(5, progressOf(snap, Achievement.WRONG_BOOK_CLEARED)[1]);
        }

        @Test
        @DisplayName("E2：正确率用整数除法向下取整（2/3 必须显示 66，不是 67）")
        void accuracyUsesIntegerDivision() {
            stubStats(2, 1, 1);   // 66.67%
            Map<Achievement, int[]> snap = manager.getProgressSnapshot(player);
            assertEquals(66, progressOf(snap, Achievement.ACCURACY_90)[0],
                    "2*100/3 = 66（截断）。改成 Math.round 会显示 67 —— 与 N3 判定的整数口径不一致");
        }

        @Test
        @DisplayName("E3：没有任何答题样本时正确率为 0（不得除零）")
        void accuracyZeroWhenNoSamples() {
            stubStats(0, 0, 0);
            Map<Achievement, int[]> snap = manager.getProgressSnapshot(player);
            assertEquals(0, progressOf(snap, Achievement.ACCURACY_90)[0], "无样本时正确率必须是 0");
        }

        @Test
        @DisplayName("E4：progressSource=NONE 的成就不出现在快照里")
        void noneSourcesAreAbsent() {
            Map<Achievement, int[]> snap = manager.getProgressSnapshot(player);

            for (Achievement a : Achievement.values()) {
                if (a.getProgressSource() == Achievement.ProgressSource.NONE) {
                    assertFalse(snap.containsKey(a), a + " 的 progressSource 是 NONE，不该有进度项");
                } else {
                    assertTrue(snap.containsKey(a), a + " 有进度源，必须出现在快照里");
                }
            }
            assertEquals(15, snap.size(),
                    "可量化成就当前是 15 个（22 个常量里 7 个 progressSource=NONE 被跳过）；"
                            + "枚举变化时请同步本断言");
        }

        @Test
        @DisplayName("E5：统计源抛异常 → 只该那几项回落 0，其余项照常（每项独立 try/catch）")
        void statsFailureOnlyZeroesThatSource() {
            when(dataStore.getPlayerStats(player)).thenThrow(new RuntimeException("stats db down"));
            when(dataStore.getConversationCount(player)).thenReturn(31);
            when(dataStore.getWrongBookCleared(player)).thenReturn(2);

            Map<Achievement, int[]> snap = manager.getProgressSnapshot(player);

            assertEquals(0, progressOf(snap, Achievement.WORDS_25)[0], "stats 取数失败 → 该来源回落 0");
            assertEquals(0, progressOf(snap, Achievement.SESSIONS_10)[0], "同一来源同样回落 0");
            assertEquals(0, progressOf(snap, Achievement.ACCURACY_90)[0], "正确率一并回落 0");
            assertEquals(31, progressOf(snap, Achievement.AI_CHAT_10)[0],
                    "独立来源不受影响 —— 删掉每项 try/catch 会让整张快照抛出而这里变红");
            assertEquals(2, progressOf(snap, Achievement.WRONG_BOOK_CLEARED)[0], "错题本来源不受影响");
        }

        @Test
        @DisplayName("E6：金币源抛异常 → 只有金币两项回落 0")
        void coinsFailureOnlyZeroesCoins() {
            stubStats(42, 8, 7);
            DatabaseManager throwingDb = mock(DatabaseManager.class);
            when(throwingDb.getCoinsEarned(player)).thenThrow(new RuntimeException("coins db down"));
            when(plugin.getDatabaseManager()).thenReturn(throwingDb);
            when(dataStore.getConversationCount(player)).thenReturn(31);
            when(dataStore.getWrongBookCleared(player)).thenReturn(2);

            Map<Achievement, int[]> snap = manager.getProgressSnapshot(player);

            assertEquals(0, progressOf(snap, Achievement.COINS_500)[0], "金币读失败 → 进度回落 0（不伪造）");
            assertEquals(0, progressOf(snap, Achievement.COINS_5000)[0]);
            assertEquals(42, progressOf(snap, Achievement.WORDS_25)[0], "其他来源必须照常");
        }

        @Test
        @DisplayName("E7：返回值是值快照 —— 每次新分配，可安全跨线程交给渲染段")
        void snapshotIsAValueCopy() {
            stubStats(10, 0, 2);
            Map<Achievement, int[]> first = manager.getProgressSnapshot(player);
            Map<Achievement, int[]> second = manager.getProgressSnapshot(player);

            assertTrue(first instanceof EnumMap<?, ?>,
                    "返回值应是 EnumMap（按 ordinal 排序，墙内顺序稳定）");
            assertNotSame(first, second, "两次调用必须是两个容器");
            assertNotSame(first.get(Achievement.WORDS_25), second.get(Achievement.WORDS_25),
                    "int[] 也必须每次新分配 —— 复用缓存内部数组会让跨线程渲染互相踩值");
        }

        @Test
        @DisplayName("E8：连续天数与不同学习日不同源（不能串台）")
        void dailyStreakVersusStudyDays() throws Exception {
            LocalDate today = LocalDate.now();
            insertSessionDay(today, 0);
            insertSessionDay(today.minusDays(5), 0);
            insertSessionDay(today.minusDays(6), 0);

            Map<Achievement, int[]> snap = manager.getProgressSnapshot(player);
            assertEquals(1, progressOf(snap, Achievement.DAILY_STREAK_3)[0],
                    "连续天数只有今天 1 天（中间断了）");
            assertEquals(3, progressOf(snap, Achievement.STUDY_DAYS_30)[0],
                    "不同学习日是 3 天 —— 与连续天数不同源，串台会在这里露馅");
        }
    }

    // ==================================================================
    //  F. 判定门（模块开关 / 单条启用 / 时间窗）
    // ==================================================================

    @Nested
    @DisplayName("组 F —— 判定门：模块开关、单条 enable、时间窗")
    class ConfigGates {

        @Test
        @DisplayName("F1：进度系统伞开关关闭 → tryUnlock 一律 false，且不写库")
        void moduleGate() throws Exception {
            when(configManager.isAchievementsEnabled()).thenReturn(false);

            assertFalse(manager.tryUnlock(player, Achievement.FIRST_WORD), "模块关闭时不得解锁");
            assertEquals(0, achievementRows(), "模块关闭时不得写库");

            manager.checkSessionAchievements(player, 5, 0, 5, "normal");
            assertFalse(manager.hasAchievement(player, Achievement.FIRST_WORD),
                    "模块关闭时连量化的门槛判定都不该落解锁");
        }

        @Test
        @DisplayName("F2：单条 enabled: false → 该条不解锁，其他照常")
        void perAchievementDisable() throws Exception {
            Files.writeString(achConfigFile.toPath(),
                    "achievements:\n"
                            + "  first_word:\n"
                            + "    enabled: false\n");
            manager.clearCache();

            assertFalse(manager.tryUnlock(player, Achievement.FIRST_WORD),
                    "管理员禁用的成就不得解锁（isAchievementEligible 被绕过会在这里变绿）");
            assertTrue(manager.tryUnlock(player, Achievement.STREAK_3), "未禁用的成就必须照常解锁");
            assertEquals(1, achievementRows(), "被禁用的那条不得写库");
        }

        @Test
        @DisplayName("F3：时间窗已过期 → 不解锁；未启用时间窗 → 照常解锁")
        void timeLimitExpiredWindow() throws Exception {
            Files.writeString(achConfigFile.toPath(),
                    "achievements:\n"
                            + "  first_word:\n"
                            + "    enabled: true\n"
                            + "    time-limit:\n"
                            + "      enabled: true\n"
                            + "      start: \"2020-01-01 00:00\"\n"
                            + "      end: \"2020-12-31 23:59\"\n"
                            + "  streak_3:\n"
                            + "    enabled: true\n"
                            + "    time-limit:\n"
                            + "      enabled: false\n");
            manager.clearCache();

            assertFalse(manager.tryUnlock(player, Achievement.FIRST_WORD), "时间窗（2020）已过期 → 不得解锁");
            assertTrue(manager.tryUnlock(player, Achievement.STREAK_3), "未启用时间窗的成就不受影响");
        }

        @Test
        @DisplayName("F4：时间窗覆盖当前时刻 → 照常解锁（防「时间窗判定恒 false」）")
        void timeLimitActiveWindow() throws Exception {
            Files.writeString(achConfigFile.toPath(),
                    "achievements:\n"
                            + "  first_word:\n"
                            + "    enabled: true\n"
                            + "    time-limit:\n"
                            + "      enabled: true\n"
                            + "      start: \"2020-01-01 00:00\"\n"
                            + "      end: \"2099-12-31 23:59\"\n");
            manager.clearCache();

            assertTrue(manager.tryUnlock(player, Achievement.FIRST_WORD),
                    "窗口覆盖当前时刻必须允许解锁 —— 恒 false 的实现会在这里红");
        }

        @Test
        @DisplayName("F5：时间窗格式非法 → 视为无限制（fail-open，管理员手滑不该停掉成就）")
        void timeLimitParseFailureIsEligible() throws Exception {
            Files.writeString(achConfigFile.toPath(),
                    "achievements:\n"
                            + "  first_word:\n"
                            + "    enabled: true\n"
                            + "    time-limit:\n"
                            + "      enabled: true\n"
                            + "      start: \"not-a-date\"\n"
                            + "      end: \"also-bad\"\n");
            manager.clearCache();

            assertTrue(manager.tryUnlock(player, Achievement.FIRST_WORD),
                    "时间窗解析失败应回落到「不限时间」（与原实现的 catch 回落一致）");
        }

        @Test
        @DisplayName("F6：clearCache 后重新判定 —— 已解锁的仍不解锁（R4-H1 懒加载回填）")
        void clearCacheThenReloadKeepsUnlock() throws Exception {
            when(plugin.getSocialManager()).thenReturn(socialManager);
            assertTrue(manager.tryUnlock(player, Achievement.FIRST_WORD));
            Long firstAt = manager.getUnlockedAt(player, Achievement.FIRST_WORD);

            manager.clearCache();   // 模拟 /lea reload
            assertFalse(manager.tryUnlock(player, Achievement.FIRST_WORD),
                    "reload 后缓存空了但库里还在 → 必须短路返回 false（建空集直接判定才会重复解锁）");
            assertEquals(1, achievementRows());
            verify(socialManager, times(1)).recordAchievement(player, "first_word");
            assertEquals(firstAt, manager.getUnlockedAt(player, Achievement.FIRST_WORD),
                    "重新加载后解锁时间应与库内一致");
        }
    }

    // ==================================================================
    //  G. 待补写重放（DC-02）
    // ==================================================================

    @Nested
    @DisplayName("组 G —— 写库失败 → 待补写在 quit/reload 时幂等重放")
    class PendingUnlockWrites {

        /**
         * 真实触发路径：缓存已就绪（读成功），写库时表不存在（真实 SQLException）。
         * 此时内存态保留、{@code pendingUnlockWrites} 登记，之后的 quit 语义（unloadPlayer）重放。
         *
         * <p>变异：把 {@code flushPendingUnlockWrites} 的失败回填删掉（改成无条件丢弃），
         * 第二次 unload 后库里仍无行 → G1 变红。
         */
        @Test
        @DisplayName("G1：写失败保留待补写，表恢复后 quit 重放成功，且解锁时间不变")
        void writeFailureIsReplayedOnUnload() throws Exception {
            manager.loadPlayerAchievements(player);      // 读成功 → 缓存 = 空集
            assertEquals(0, manager.getUnlockedCount(player));

            exec("DROP TABLE " + PREFIX + "achievements");

            assertTrue(manager.tryUnlock(player, Achievement.FIRST_WORD),
                    "读已成功、内存可写 → 本次解锁对玩家生效（写库失败不阻断解锁链）");
            assertTrue(manager.hasAchievement(player, Achievement.FIRST_WORD), "内存态必须保留");
            Long at = manager.getUnlockedAt(player, Achievement.FIRST_WORD);
            assertNotNull(at);

            // 表仍不存在 → 这次重放必然失败，待补写必须被回填而不是丢弃
            manager.unloadPlayer(player);

            exec("CREATE TABLE " + PREFIX + "achievements ("
                    + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "  uuid VARCHAR(36) NOT NULL,"
                    + "  achievement_id VARCHAR(64) NOT NULL,"
                    + "  unlocked_at BIGINT,"
                    + "  UNIQUE(uuid, achievement_id))");

            manager.unloadPlayer(player);   // 下一次 quit：待补写真正写进库

            assertEquals(1, achievementRows(), "表恢复后的重放必须补上那一行");
            assertEquals(at.longValue(), unlockedAtInDb("first_word"),
                    "重放必须沿用首次解锁时间（UPSERT 撞键不改 unlocked_at）");
        }

        @Test
        @DisplayName("G2：没有待补写时 unloadPlayer 只清缓存，不产生任何写")
        void cleanUnloadWritesNothing() throws Exception {
            manager.loadPlayerAchievements(player);
            manager.unloadPlayer(player);
            assertEquals(0, achievementRows());
            assertEquals(0, manager.getUnlockedCount(player), "缓存已清 → 重新读库（0 行）→ 计数 0");
        }
    }

    // ==================================================================
    //  H. 批量快照（成就墙专用）
    // ==================================================================

    @Nested
    @DisplayName("组 H —— 批量快照：解锁时刻与前置未满足集")
    class BatchSnapshots {

        @Test
        @DisplayName("H1：getUnlockedAtSnapshot 只含真的解锁了的项，且 null/空集合返回空")
        void unlockedAtSnapshot() {
            assertTrue(manager.tryUnlock(player, Achievement.WORDS_25));
            assertTrue(manager.tryUnlock(player, Achievement.SESSIONS_10));

            Map<Achievement, Long> snap = manager.getUnlockedAtSnapshot(player,
                    List.of(Achievement.WORDS_25, Achievement.SESSIONS_10, Achievement.COINS_500));
            assertEquals(2, snap.size(), "只该包含有解锁记录的两项");
            assertNotNull(snap.get(Achievement.WORDS_25));
            assertNotNull(snap.get(Achievement.SESSIONS_10));
            assertNull(snap.get(Achievement.COINS_500), "未解锁的成就不得出现在快照里");

            assertTrue(manager.getUnlockedAtSnapshot(player, null).isEmpty(), "null 集合视作空集");
            assertTrue(manager.getUnlockedAtSnapshot(player, List.of()).isEmpty(), "空集合返回空");
        }

        @Test
        @DisplayName("H2：getPrereqUnmetSnapshot 只列前置未满足的项")
        void prereqUnmetSnapshot() {
            stubStats(50, 0, 5);   // 答题 50 → COINS_500 满足；答对 50 < 100 → COINS_5000 不满足

            Set<Achievement> unmet = manager.getPrereqUnmetSnapshot(player, List.of(
                    Achievement.WORDS_25,     // 无前置
                    Achievement.COINS_500,    // 前置：答题 ≥50 → 满足
                    Achievement.COINS_5000,   // 前置：WORDS_100 → 不满足
                    Achievement.SESSIONS_50   // 前置：STREAK_10 → 不满足
            ));

            assertEquals(EnumSet.of(Achievement.COINS_5000, Achievement.SESSIONS_50), unmet,
                    "未满足集应恰好是这两项 —— 前置判定被复制/漂移会在这里露馅");
            assertTrue(manager.getPrereqUnmetSnapshot(player, null).isEmpty(), "null 视作空集");
        }

        @Test
        @DisplayName("H3：前置满足才解锁；前置回改不撤销既有解锁")
        void prereqUnlockThenStable() {
            stubStats(100, 0, 10);
            assertFalse(manager.tryUnlock(player, Achievement.COINS_5000),
                    "COINS_5000 前置（WORDS_100）未解锁时应拒绝");

            assertTrue(manager.tryUnlock(player, Achievement.WORDS_100));
            assertTrue(manager.tryUnlock(player, Achievement.COINS_5000), "前置满足后应解锁");

            // 前置回改：统计清零（等价于读失败折 0），已解锁结果不得变
            stubStats(0, 0, 0);
            assertTrue(manager.hasAchievement(player, Achievement.COINS_5000),
                    "前置回改不得撤销既有解锁（判定顺序 contains 在前置之前）");
            assertFalse(manager.getPrereqUnmetSnapshot(player, List.of(Achievement.COINS_5000))
                            .contains(Achievement.COINS_5000),
                    "WORDS_100 仍在已解锁集合里，COINS_5000 的前置仍算满足");
        }
    }

    // ==================================================================
    //  I. 通知段线程归属（PF-ACH-02）
    // ==================================================================

    @Nested
    @DisplayName("组 I —— 通知段必须回主线程（本方法可从异步线程到达）")
    class NotificationThreading {

        @Test
        @DisplayName("I1：非主线程调用 tryUnlock → 通知经调度器投递，解锁记录已落库")
        void notificationIsScheduledOnMainThread() throws Exception {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            assertTrue(manager.tryUnlock(player, Achievement.FIRST_WORD), "解锁本身应成功");

            verify(scheduler, times(1)).runTask(eq(plugin), any(Runnable.class));
            assertEquals(1, achievementRows(), "即使通知要排队，解锁记录也必须已经落库");
        }

        @Test
        @DisplayName("I2：插件停用窗口（调度器抛 IllegalStateException）→ 放弃通知但不丢解锁")
        void schedulerClosedDoesNotLoseUnlock() throws Exception {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class)))
                    .thenThrow(new IllegalStateException("Plugin disabled"));

            assertTrue(manager.tryUnlock(player, Achievement.FIRST_WORD),
                    "停用窗口只放弃通知，解锁必须算成功（否则下次会重复触发）");
            assertEquals(1, achievementRows());
        }
    }
}
