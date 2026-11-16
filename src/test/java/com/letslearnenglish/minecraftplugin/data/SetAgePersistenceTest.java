package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;

/**
 * R5-DC-01（round6 二次修复）：{@link PlayerDataStore#setAge(UUID, int)} 的持久化与隐私隔离。
 *
 * <p><b>为什么必须上真 SQLite</b>：本缺陷的全部要害在 SQL 的<b>列粒度</b>上，mock 查不出来。
 * round5 的修复让离线玩家的年龄能落库了（原缺陷消失），但它走的是
 * {@code getProfileSettings}（{@code computeIfAbsent}）+
 * {@code saveProfileSettings}（<b>7 列整对象 UPSERT</b>）：
 * <ol>
 *   <li>离线 UUID 在缓存里凭空多出一条「新玩家默认值」条目（{@code showAnswers=false /
 *       showResults=true / showInSquare=true}）；</li>
 *   <li>该 UUID 随即进入 {@code getActivePlayerIds()}（缓存键 ∪ 在线）；</li>
 *   <li>quit / reload / 关服的补差集落盘对它执行整对象 UPSERT。</li>
 * </ol>
 * 净效果：一个已经把 {@code show_in_square} 关成 0 的玩家，被一次 {@code setAge}
 * <b>永久翻回公开</b>。这条链只有在真库上"写前读、写后读"才看得见 —— 用 mock 连接
 * 只能验证「执行了哪条 SQL 字符串」，而真正要证的是「另外 6 列有没有被动过」。
 *
 * <p><b>纯 JVM 可跑</b>：沿用 {@code ActiveSquarePagingTest} / {@code SqliteMaintenanceTest}
 * 的骨架 —— {@code mock(LetsLearnEnglish)} + 真 {@link YamlConfiguration} + {@link TempDir}
 * 把 {@link DatabaseManager} 的 SQLite 分支跑起来，不需要 MockBukkit。
 * 驱动来自 test-scope 的 {@code org.xerial:sqlite-jdbc}。
 */
class SetAgePersistenceTest {

    /** 与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    /** DB 里已有真实行、且把三个隐私位全关掉的离线玩家。 */
    private static final UUID OFFLINE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    /** 在线且缓存已加载的玩家。 */
    private static final UUID ONLINE = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    /** DB 里没有任何行的真·新玩家。 */
    private static final UUID NEWBIE = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;

    @BeforeAll
    static void createMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        // 真 Logger：钳制告警与失败告警都要真实例，否则 NPE 会掩盖真实行为。
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SetAgePersistenceTest"));
        // 默认「无人在线」：离线/新玩家用例都不该被在线枚举影响。
        Server server = mock(Server.class);
        doReturn(List.of()).when(server).getOnlinePlayers();
        when(plugin.getServer()).thenReturn(server);
    }

    // ------------------------------------------------------------------
    // A. 离线玩家：年龄落库 + 隐私位一位都不许动
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("真 SQLite —— 离线玩家的 setAge 只碰 age 列")
    class OfflinePlayer {

        private DatabaseManager databaseManager;
        private PlayerDataStore store;

        @BeforeEach
        void init(@TempDir Path tempDir) throws Exception {
            FileConfiguration config = new YamlConfiguration();
            config.set("database.type", "sqlite");
            config.set("database.sqlite.filename", "set-age-offline.db");
            when(configManager.getMainConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

            databaseManager = new DatabaseManager(plugin, configManager);
            databaseManager.initialize();
            assertTrue(databaseManager.isSQLite(), "前置条件：真库必须走 SQLite 分支");
            // 前置数据：行存在，年龄 7，三个隐私位全部关掉（0/0/0）。
            seedProfile(databaseManager, OFFLINE, 7, false, false, false);
            store = new PlayerDataStore(plugin, databaseManager);
        }

        @AfterEach
        void close() {
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
        }

        /**
         * 核心缺陷：一次 {@code setAge} 不得让「已关掉的隐私开关」重新变成公开。
         *
         * <p><b>判别力</b>：把 {@code setAge} 改回 {@code getProfileSettings + saveProfileSettings}
         * （round5 的实现），本用例的 showResults / showInSquare 两条立刻变红。
         */
        @Test
        @DisplayName("离线 setAge 后：show_answers / show_results / show_in_square 必须逐位保持 0/0/0")
        void offlineSetAgeKeepsPrivacyBits() throws Exception {
            store.setAge(OFFLINE, 42);

            int[] row = readProfile(databaseManager, OFFLINE);
            assertEquals(42, row[0], "年龄必须落库（原缺陷：只改内存，重启后静默回退）");
            assertEquals(0, row[1],
                    "show_answers 被 setAge 翻成了公开 喵。\n"
                            + "根因：setAge 走 getProfileSettings（computeIfAbsent），离线 UUID 会"
                            + "凭空得到一份「新玩家默认值」条目，随后整对象 UPSERT 把它写回库。"
                            + "隐私位一旦被翻回公开就是不可撤销的信息泄露。");
            assertEquals(0, row[2],
                    "show_results 被 setAge 翻成了公开 —— 这是 round5 修复引入的新缺陷，"
                            + "比「丢年龄」更严重：丢的是便利，泄的是隐私");
            assertEquals(0, row[3],
                    "show_in_square 被 setAge 翻成了公开 —— 玩家主动隐身被一次第三方 API 调用撤销");
        }

        /** 年龄落地必须真的在 DB 里，而不是只在内存：新建 PlayerDataStore 重读一遍。 */
        @Test
        @DisplayName("模拟重启：新建 PlayerDataStore 重读，年龄仍是 42（不依赖任何 sweep 兜底）")
        void ageSurvivesRestartWithoutSweep() {
            store.setAge(OFFLINE, 42);

            // round6 已把 onDisable / autoSaveTask 的「离线补差集落盘」撤掉 ——
            // 本用例因此证明「年龄不依赖 sweep」：这里不做任何 saveProfileSettings 调用。
            PlayerDataStore restarted = new PlayerDataStore(plugin, databaseManager);
            assertTrue(restarted.loadProfileSettings(OFFLINE), "重读应成功（真库里有行）");
            assertEquals(42, restarted.getProfileSettingsReadOnly(OFFLINE).getAge(),
                    "重启后年龄回退 = setAge 没有真正落库，只是改了内存");
        }

        /** 离线 UUID 不得进入 profile 缓存 —— 这正是不产生隐私翻转的结构性原因。 */
        @Test
        @DisplayName("离线 setAge 不建缓存条目（因此不进 getActivePlayerIds 候选集）")
        void offlineSetAgeDoesNotPopulateCache() {
            store.setAge(OFFLINE, 42);

            assertNull(store.getCachedProfileSettings(OFFLINE),
                    "setAge 不得在 profileSettingsCache 里为离线 UUID 建条目 喵。\n"
                            + "该条目会被 getActivePlayerIds() 当成广场候选，并在 reload/关服时"
                            + "被整对象 UPSERT 写回库 —— 隐私位翻转的全部链条都从这里开始。");
            assertTrue(store.getActivePlayerIds().stream().noneMatch(OFFLINE::equals),
                    "离线 UUID 不该出现在 getActivePlayerIds() 里");
        }

        /**
         * 结构 lint：{@code setAge} 绝不能再用 {@code getProfileSettings}。
         *
         * <p>上面三条断言是从"结果"侧判定的；本用例从"调用"侧判定，防的是
         * 「结果碰巧对但路径又走回 computeIfAbsent」这种半修 —— 例如有人为了刷新
         * 缓存里的 age 又调一次 getProfileSettings，而离线条目恰好已被上一轮 sweep 建好，
         * 于是测试仍绿、缺陷回归。
         */
        @Test
        @DisplayName("结构 lint：setAge 不得调用 getProfileSettings（否则 computeIfAbsent 回归）")
        void setAgeNeverCallsComputeIfAbsentPath() {
            PlayerDataStore spied = spy(new PlayerDataStore(plugin, databaseManager));

            spied.setAge(OFFLINE, 42);

            verify(spied, never()).getProfileSettings(any(UUID.class));
        }

        /**
         * 值钳制必须同时影响缓存与库：脏数据（负数 / &gt;150）不得进任何一侧。
         */
        @Test
        @DisplayName("钳制：-5 → 0、999 → 150，且两侧一致（负数不得进库）")
        void clampsAndPersistsClampedValue() throws Exception {
            store.setAge(OFFLINE, -5);
            assertEquals(0, readProfile(databaseManager, OFFLINE)[0],
                    "负数年龄必须被钳成 0 再落库（R-39：脏数据永不落盘）");

            store.setAge(OFFLINE, 999);
            assertEquals(PlayerDataStore.ProfileSettings.MAX_VALID_AGE,
                    readProfile(databaseManager, OFFLINE)[0],
                    "超上限年龄必须被钳成 MAX_VALID_AGE 再落库");
        }
    }

    // ------------------------------------------------------------------
    // B. 在线玩家（缓存已加载）：内存与库同时更新
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("真 SQLite —— 在线玩家的 setAge 缓存与库双写")
    class OnlinePlayer {

        private DatabaseManager databaseManager;
        private PlayerDataStore store;

        @BeforeEach
        void init(@TempDir Path tempDir) throws Exception {
            FileConfiguration config = new YamlConfiguration();
            config.set("database.type", "sqlite");
            config.set("database.sqlite.filename", "set-age-online.db");
            when(configManager.getMainConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

            databaseManager = new DatabaseManager(plugin, configManager);
            databaseManager.initialize();
            seedProfile(databaseManager, ONLINE, 3, false, false, false);
            store = new PlayerDataStore(plugin, databaseManager);
            // 模拟 join 预热：把 DB 真值读进缓存。
            assertTrue(store.loadProfileSettings(ONLINE), "前置条件：在线玩家缓存必须已载入");
        }

        @AfterEach
        void close() {
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
        }

        @Test
        @DisplayName("在线 setAge：缓存立即更新，且落库后重读仍是新值")
        void onlineSetAgeUpdatesCacheAndDb() {
            store.setAge(ONLINE, 55);

            PlayerDataStore.ProfileSettings cached = store.getCachedProfileSettings(ONLINE);
            assertNotNull(cached, "在线玩家缓存条目不该被 setAge 弄丢");
            assertEquals(55, cached.getAge(), "缓存里的年龄必须立即更新（本人在设置页/主页看到的是它）");

            PlayerDataStore restarted = new PlayerDataStore(plugin, databaseManager);
            restarted.loadProfileSettings(ONLINE);
            assertEquals(55, restarted.getProfileSettingsReadOnly(ONLINE).getAge(),
                    "在线路径同样要落库 —— 只改内存会在重启后静默回退");
        }

        @Test
        @DisplayName("在线 setAge 同样不得改动隐私位（缓存命中路径也必须走窄列写）")
        void onlineSetAgeKeepsPrivacyBits() throws Exception {
            store.setAge(ONLINE, 55);

            int[] row = readProfile(databaseManager, ONLINE);
            assertEquals(55, row[0]);
            assertEquals(0, row[1], "show_answers 被翻成公开");
            assertEquals(0, row[2], "show_results 被翻成公开");
            assertEquals(0, row[3], "show_in_square 被翻成公开");
        }
    }

    // ------------------------------------------------------------------
    // C. 真·新玩家（DB 无行）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("真 SQLite —— DB 无行的新玩家")
    class BrandNewPlayer {

        private DatabaseManager databaseManager;

        @BeforeEach
        void init(@TempDir Path tempDir) throws Exception {
            FileConfiguration config = new YamlConfiguration();
            config.set("database.type", "sqlite");
            config.set("database.sqlite.filename", "set-age-newbie.db");
            when(configManager.getMainConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

            databaseManager = new DatabaseManager(plugin, configManager);
            databaseManager.initialize();
        }

        @AfterEach
        void close() {
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
        }

        @Test
        @DisplayName("新玩家 setAge：年龄落库，隐私位取 schema 默认值 0/1/1（不是被 setAge 写出来的）")
        void newPlayerAgePersistsWithSchemaDefaults() throws Exception {
            PlayerDataStore store = new PlayerDataStore(plugin, databaseManager);
            store.setAge(NEWBIE, 19);

            int[] row = readProfile(databaseManager, NEWBIE);
            assertEquals(19, row[0], "新玩家年龄必须落库（否则首次设置就丢）");
            assertEquals(0, row[1], "show_answers 的 schema 默认是 0（D2 隐私优先）");
            assertEquals(1, row[2], "show_results 的 schema 默认是 1（新玩家初始公开）");
            assertEquals(1, row[3], "show_in_square 的 schema 默认是 1（新玩家初始公开）");

            assertNull(store.getCachedProfileSettings(NEWBIE),
                    "新玩家同样不该因 setAge 而进缓存（join 预热才是唯一入口）");
        }
    }

    // ------------------------------------------------------------------
    // D. DB 故障不得穿透
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("DB 故障：setAge 清零容忍")
    class FailureTolerance {

        /**
         * {@code DatabaseManager.getConnection()} 在重连失败时抛的是 {@code RuntimeException}，
         * 不是 {@code SQLException}。它必须被吞掉 —— 否则一次第三方 API 调用会炸在调用方
         * （可能是一个完全无关的插件）的线程里。
         */
        @Test
        @DisplayName("getConnection 抛 RuntimeException 时 setAge 不抛，且失败计入 hasPendingWrites")
        void setAgeToleratesRuntimeException() {
            DatabaseManager db = mock(DatabaseManager.class);
            when(db.getTablePrefix()).thenReturn(PREFIX);
            when(db.getConnectionLock()).thenReturn(new Object());
            when(db.isSQLite()).thenReturn(true);
            when(db.getConnection()).thenThrow(
                    new RuntimeException("Failed to get database connection"));
            Logger logger = mock(Logger.class);
            when(plugin.getLogger()).thenReturn(logger);
            try {
                PlayerDataStore tolerant = new PlayerDataStore(plugin, db);

                assertDoesNotThrow(() -> tolerant.setAge(OFFLINE, 42),
                        "DB 故障必须只丢这一次落盘，不得把异常抛给调用方");

                assertTrue(tolerant.hasPendingWrites(OFFLINE),
                        "写失败必须登记为待写 —— 否则 hot-reload 的 flush 会误报全部落盘成功，"
                                + "而年龄其实没进库");
            } finally {
                // 恢复给其它用例用的真 Logger（createMocks 里那次）。
                when(plugin.getLogger()).thenReturn(Logger.getLogger("SetAgePersistenceTest"));
            }
        }

        /**
         * 第三方插件传 null 是完全可能的输入。它不该以 NPE 的形式炸在调用方线程里 ——
         * 尤其不能炸在 {@code ConcurrentHashMap.get(null)}（旧实现的第一个接触点）或
         * {@code profileSaveFailed.add(null)}（catch 分支里的二次伤害）上：
         * 那两处都是"看不出是参数问题"的失败点。
         */
        @Test
        @DisplayName("null playerId：记 WARNING 后安静返回，不抛 NPE")
        void nullPlayerIdIsRejectedQuietly() {
            Logger logger = mock(Logger.class);
            when(plugin.getLogger()).thenReturn(logger);
            try {
                PlayerDataStore tolerant = new PlayerDataStore(plugin, mock(DatabaseManager.class));

                assertDoesNotThrow(() -> tolerant.setAge(null, 30),
                        "第三方 API 传 null 不得炸在调用方线程里 喵");
                org.mockito.Mockito.verify(logger).warning(anyString());
            } finally {
                when(plugin.getLogger()).thenReturn(Logger.getLogger("SetAgePersistenceTest"));
            }
        }
    }

    // ------------------------------------------------------------------
    // E. 不许动的旧语义：saveProfileSettings 仍是 7 列整对象写入
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("回归守卫 —— saveProfileSettings 的 7 列整对象语义不变")
    class FullRowUpsertUnchanged {

        private DatabaseManager databaseManager;

        @BeforeEach
        void init(@TempDir Path tempDir) throws Exception {
            FileConfiguration config = new YamlConfiguration();
            config.set("database.type", "sqlite");
            config.set("database.sqlite.filename", "set-age-full-row.db");
            when(configManager.getMainConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

            databaseManager = new DatabaseManager(plugin, configManager);
            databaseManager.initialize();
        }

        @AfterEach
        void close() {
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
        }

        /**
         * {@code MiscGUIListener:212} 的设置页依赖「载入完整设置 → 改一列 → 整对象写回」。
         * 收窄或改坏这条全列 UPSERT，玩家点一个开关就会丢掉其余五个开关 ——
         * 因此本用例把"全列都写"钉成契约：一次 saveProfileSettings 后，
         * 三个隐私位、两个偏好位都必须能在库里读回刚改的值。
         */
        @Test
        @DisplayName("saveProfileSettings 仍写全部 7 列（设置页依赖它，不许收窄）")
        void saveProfileSettingsStillWritesAllColumns() throws Exception {
            seedProfile(databaseManager, OFFLINE, 7, false, false, false);
            PlayerDataStore store = new PlayerDataStore(plugin, databaseManager);
            assertTrue(store.loadProfileSettings(OFFLINE));

            PlayerDataStore.ProfileSettings ps = store.getProfileSettings(OFFLINE);
            ps.setShowAnswers(true);
            ps.setShowResults(true);
            ps.setShowInSquare(true);
            ps.setSoundsEnabled(false);
            ps.setRemindersEnabled(false);
            ps.setAge(33);
            store.saveProfileSettings(OFFLINE);

            int[] row = readProfile(databaseManager, OFFLINE);
            assertEquals(33, row[0], "age 列必须被整对象 UPSERT 写入");
            assertEquals(1, row[1], "show_answers 必须被整对象 UPSERT 写入");
            assertEquals(1, row[2], "show_results 必须被整对象 UPSERT 写入");
            assertEquals(1, row[3], "show_in_square 必须被整对象 UPSERT 写入");

            String sql = "SELECT sounds_enabled, reminders_enabled FROM " + PREFIX
                    + "player_profiles WHERE uuid = ?";
            synchronized (databaseManager.getConnectionLock()) {
                try (PreparedStatement st = databaseManager.getConnection().prepareStatement(sql)) {
                    st.setString(1, OFFLINE.toString());
                    try (ResultSet rs = st.executeQuery()) {
                        assertTrue(rs.next(), "行必须存在");
                        assertEquals(0, rs.getInt("sounds_enabled"),
                                "sounds_enabled 必须被整对象 UPSERT 写入（收窄这条 SQL 会让设置页丢开关）");
                        assertEquals(0, rs.getInt("reminders_enabled"),
                                "reminders_enabled 必须被整对象 UPSERT 写入");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void seedProfile(DatabaseManager db, UUID id, int age,
                                    boolean answers, boolean results, boolean inSquare) throws Exception {
        String sql = "INSERT OR REPLACE INTO " + PREFIX
                + "player_profiles (uuid, age, show_answers, show_results, show_in_square,"
                + " sounds_enabled, reminders_enabled) VALUES (?, ?, ?, ?, ?, ?, ?)";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, id.toString());
                ps.setInt(2, age);
                ps.setBoolean(3, answers);
                ps.setBoolean(4, results);
                ps.setBoolean(5, inSquare);
                ps.setBoolean(6, true);
                ps.setBoolean(7, true);
                ps.executeUpdate();
            }
        }
    }

    /** @return {@code [age, show_answers, show_results, show_in_square]}；行不存在时返回 MISSING 哨兵值。 */
    private static int[] readProfile(DatabaseManager db, UUID id) throws Exception {
        String sql = "SELECT age, show_answers, show_results, show_in_square FROM " + PREFIX
                + "player_profiles WHERE uuid = ?";
        synchronized (db.getConnectionLock()) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE,
                                Integer.MIN_VALUE, Integer.MIN_VALUE};
                    }
                    return new int[]{
                            rs.getInt("age"),
                            rs.getInt("show_answers"),
                            rs.getInt("show_results"),
                            rs.getInt("show_in_square")};
                }
            }
        }
    }
}
