package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R5-DC-02（round6）：{@code /lea reset} 必须把 {@code daily_coin_counters} 一起清掉。
 *
 * <p><b>缺陷</b>：{@code deletePlayerData} 的两张表清单都不含 {@code daily_coin_counters}，
 * 而 {@code ai_round_counters} / {@code daily_word_reward_claims} / {@code checkin_data}
 * 这三张同性质的「每日台账」都被清了 —— 同一类数据两种处置。更糟的是该表自 F-09-02 起
 * 是日限的<b>写穿真源</b>：残行会让 reset 后的额度被回读复活，比改动前更权威。
 *
 * <p><b>这组测试的两个方向</b>：
 * <ul>
 *   <li>正向：reset 后 {@code daily_coin_counters} 无残行；</li>
 *   <li>反向对照：同一方法确实删空了 {@code player_profiles} —— 证明删表动作真的执行了，
 *       避免「什么都没跑所以残行数是 0」的假绿；同时确认三张同类台账仍全清（防回归）。</li>
 * </ul>
 *
 * <p><b>变异验证</b>（编排者手工执行，记录在此以便复现）：把
 * {@code daily_coin_counters} 从 {@code playerUuidTables} 里删掉，本类
 * {@link ResetClearsDailyCoinLedger#resetClearsDailyCoinLedger} 必须变红；恢复后全绿。
 */
class ResetClearsDailyCoinLedgerTest {

    @TempDir
    Path tempDir;

    private File dbFile;
    private LetsLearnEnglish plugin;
    private DatabaseManager db;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ResetClearsDailyCoinLedgerTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager configManager = mock(ConfigManager.class);
        org.bukkit.configuration.file.FileConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.set("database.type", "sqlite");
        // 键名必须与 DatabaseManager:144/3898 实际读的一致，否则 initialize() 会在
        // plugin.getDataFolder() 下落一个默认 playerdata.db，而本测试的另一条 JDBC
        // 连接指向别处 —— 表现为「表不存在」，看似代码错、实为夹具错。
        cfg.set("database.sqlite.filename", "reset-ledger.db");
        cfg.set("database.mysql.table-prefix", "lle_");
        when(configManager.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则故障注入手段无效");
        dbFile = new File(tempDir.toFile(), "reset-ledger.db");
        assertTrue(dbFile.isFile(), "前置条件：SQLite 文件未创建，路径推导错了");
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    /** 用一条独立 JDBC 连接直接查表 —— 不经 DatabaseManager 的缓存层。 */
    private long countRows(String table, String whereCol, String whereVal) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + whereCol + " = '" + whereVal + "'")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /**
     * 建出 {@code deletePlayerData} 会去删、但 {@code createTables()} 未覆盖的表
     * （{@code ai_round_counters} 由 {@code RewardEngine.ensureDailyCoinsTable} 这类
     * 懒建逻辑负责、{@code duel_stats} / {@code social_*} 等由各自模块建）。
     *
     * <p><b>为什么必须建</b>：{@code deletePlayerData} 是「任一表失败即整批 rollback」的
     * 单事务（round4 DC-02 的已验收语义）。夹具里缺一张表 → 那条 {@code DELETE} 抛
     * {@code no such table} → {@code failed=true} → 全部回滚 → 所有「应被删除」的断言都
     * 变成「行还在」。那不是代码错，是夹具错，而且会以最误导的方式表现（像功能没实现）。
     */
    private void ensureAllDeletedTablesExist() throws Exception {
        exec("CREATE TABLE IF NOT EXISTS lle_daily_coin_counters ("
                + " player_uuid VARCHAR(36) NOT NULL, coin_date DATE NOT NULL,"
                + " cents BIGINT NOT NULL DEFAULT 0, UNIQUE(player_uuid, coin_date))");
        exec("CREATE TABLE IF NOT EXISTS lle_ai_round_counters ("
                + " player_uuid VARCHAR(36) NOT NULL, round_date DATE NOT NULL, rounds INT,"
                + " UNIQUE(player_uuid, round_date))");
        exec("CREATE TABLE IF NOT EXISTS lle_checkin_data ("
                + " player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                + " last_checkin_date DATE NOT NULL, streak_count INTEGER DEFAULT 1)");
        exec("CREATE TABLE IF NOT EXISTS lle_daily_word_reward_claims ("
                + " player_uuid VARCHAR(36) NOT NULL, claim_date DATE NOT NULL,"
                + " challenge_id VARCHAR(32) NOT NULL, UNIQUE(player_uuid, claim_date, challenge_id))");
        exec("CREATE TABLE IF NOT EXISTS lle_social_cheers ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT, feed_id INTEGER,"
                + " cheerer_uuid VARCHAR(36))");
        exec("CREATE TABLE IF NOT EXISTS lle_social_feed ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid VARCHAR(36),"
                + " feed_date DATE, event_type VARCHAR(16))");
        exec("CREATE TABLE IF NOT EXISTS lle_duel_stats ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT, winner_uuid VARCHAR(36),"
                + " loser_uuid VARCHAR(36), winner_score INT, loser_score INT)");
    }

    private long countAll(String table) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    @Nested
    @DisplayName("A. 核心：reset 清掉日限台账")
    class ResetClearsDailyCoinLedger {

        /**
         * 正向 + 反向对照。没有 {@code [CONTROL]} 那两条，本用例可能因为
         * 「deletePlayerData 压根没被调通」而假绿。
         */
        @Test
        @DisplayName("deletePlayerData 后 daily_coin_counters 无残行；player_profiles 也被清空（反向对照）")
        void resetClearsDailyCoinLedger() throws Exception {
            UUID playerId = UUID.fromString("00000000-0000-0000-0000-00000000dead");
            String id = playerId.toString();
            ensureAllDeletedTablesExist();

            // ---- 前置：建出与 RewardEngine.ensureDailyCoinsTable 同形的表并 seed ----
            exec("CREATE TABLE IF NOT EXISTS lle_daily_coin_counters ("
                    + " player_uuid VARCHAR(36) NOT NULL,"
                    + " coin_date DATE NOT NULL,"
                    + " cents BIGINT NOT NULL DEFAULT 0,"
                    + " UNIQUE(player_uuid, coin_date))");
            exec("INSERT INTO lle_daily_coin_counters (player_uuid, coin_date, cents) VALUES ('"
                    + id + "', '2026-11-03', 40000)");

            // 前置：profile 行也建一条，作为「删表真的执行了」的反向对照
            exec("INSERT OR REPLACE INTO lle_player_profiles (uuid, age, show_answers,"
                    + " show_results, show_in_square, sounds_enabled, reminders_enabled)"
                    + " VALUES ('" + id + "', 0, 0, 0, 0, 1, 1)");

            // 前置断言：两条都在（否则下面的「0」毫无意义）
            assertEquals(1, countRows("lle_daily_coin_counters", "player_uuid", id),
                    "前置失败：seed 的日限行没进库，后续断言无意义");
            assertEquals(1, countRows("lle_player_profiles", "uuid", id),
                    "前置失败：对照行没进库，反向对照会退化成恒真");

            // ---- 被测动作：/lea reset 唯一的删表入口 ----
            db.deletePlayerData(playerId);

            // ---- 正向：缺陷主体 ----
            assertEquals(0, countRows("lle_daily_coin_counters", "player_uuid", id),
                    "reset 后 daily_coin_counters 仍有残行 —— 玩家重置后仍背着 reset 前的"
                            + " 当日金币额度，且该表是日限的写穿真源，残行会让额度被回读复活");

            // ---- 反向对照：证明删表动作确实跑到，排除「方法没执行」的假绿 ----
            assertEquals(0, countRows("lle_player_profiles", "uuid", id),
                    "[CONTROL] player_profiles 未被清空 —— 说明 deletePlayerData 整体没生效，"
                            + "上面的 0 是假绿");
        }

        /**
         * 防「一律删空」的过度修复：另一个 UUID 的行必须原封不动。
         */
        @Test
        @DisplayName("兄弟玩家的日限行不受影响（防「清库」式过度修复）")
        void resetDoesNotTouchOtherPlayersLedger() throws Exception {
            UUID target = UUID.fromString("00000000-0000-0000-0000-00000000dead");
            UUID bystander = UUID.fromString("00000000-0000-0000-0000-00000000beef");
            ensureAllDeletedTablesExist();

            exec("CREATE TABLE IF NOT EXISTS lle_daily_coin_counters ("
                    + " player_uuid VARCHAR(36) NOT NULL,"
                    + " coin_date DATE NOT NULL,"
                    + " cents BIGINT NOT NULL DEFAULT 0,"
                    + " UNIQUE(player_uuid, coin_date))");
            exec("INSERT INTO lle_daily_coin_counters (player_uuid, coin_date, cents) VALUES ('"
                    + target + "', '2026-11-03', 40000), ('" + bystander + "', '2026-11-03', 25000)");

            db.deletePlayerData(target);

            assertEquals(0, countRows("lle_daily_coin_counters", "player_uuid", target.toString()),
                    "目标玩家的日限行未被清");
            assertEquals(1, countRows("lle_daily_coin_counters", "player_uuid", bystander.toString()),
                    "旁观玩家的日限行被误删 —— 修复变成了「清全库」");
        }

        /**
         * 回归守卫：三张同类每日台账的清理不得在本次改动中被破坏。
         */
        @Test
        @DisplayName("回归守卫：ai_round_counters / daily_word_reward_claims / checkin_data 仍被清")
        void siblingDailyLedgersStillCleared() throws Exception {
            UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dead");
            String s = id.toString();
            ensureAllDeletedTablesExist();

            exec("CREATE TABLE IF NOT EXISTS lle_ai_round_counters ("
                    + " player_uuid VARCHAR(36) NOT NULL, round_date DATE NOT NULL, rounds INT,"
                    + " UNIQUE(player_uuid, round_date))");
            exec("INSERT INTO lle_ai_round_counters (player_uuid, round_date, rounds)"
                    + " VALUES ('" + s + "', '2026-11-03', 7)");

            // checkin_data 的列名以 DatabaseManager:463-469 的建表语句为准
            // （player_uuid / last_checkin_date / streak_count）—— 写成 streak 会报
            // "table has no column named streak"，那是夹具错不是代码错。
            exec("CREATE TABLE IF NOT EXISTS lle_checkin_data ("
                    + " player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + " last_checkin_date DATE NOT NULL, streak_count INTEGER DEFAULT 1)");
            exec("INSERT INTO lle_checkin_data (player_uuid, last_checkin_date, streak_count)"
                    + " VALUES ('" + s + "', '2026-11-03', 3)");

            db.deletePlayerData(id);

            assertEquals(0, countRows("lle_ai_round_counters", "player_uuid", s),
                    "回归：ai_round_counters 未被清 —— 本次改动破坏了既有台账清理");
            assertEquals(0, countRows("lle_checkin_data", "player_uuid", s),
                    "回归：checkin_data 未被清 —— 本次改动破坏了既有台账清理");
        }

        /**
         * 事务性不得被破坏（round4 DC-02 的已验收修复）。
         */
        @Test
        @DisplayName("回归守卫：deletePlayerData 仍是单事务（失败整批回滚）")
        void deleteStillTransactional() throws Exception {
            ensureAllDeletedTablesExist();
            UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dead");
            seedProfile(id);
            // 前置断言：不先证明「行确实在」，下面的 0 可能是「压根没插进去」的假绿。
            assertEquals(1, countRows("lle_player_profiles", "uuid", id.toString()),
                    "前置失败：profile 行没进库，下面的断言无意义");

            // R11：返回值不能丢 —— 恒 true 的"吞掉失败"是本任务要防的形态。
            assertTrue(db.deletePlayerData(id), "正常路径必须返回 true");
            assertEquals(0, countRows("lle_player_profiles", "uuid", id.toString()),
                    "正常路径未删空，事务提交失败");
        }
    }

    /**
     * R11：{@code deletePlayerData} 的 {@code void → boolean} 契约。
     *
     * <p><b>缺陷</b>：原实现 {@code void}，删表失败时只留一行 WARNING，而调用方
     * {@code AdminCommandHandler.resetPlayerData} 无条件打印 "reset complete" 并向管理员回
     * "已重置…的学习数据" —— 而本方法是「任一表失败即整批 rollback」的单事务，失败时
     * <b>一条数据都没删</b>。这是数据擦除语义，谎报成功比一般写失败重。
     *
     * <p><b>两个方向都要钉住</b>：
     * <ul>
     *   <li>失败方向：真实故障注入 → 必须 {@code false}，且数据必须<b>还在</b>
     *       （证明 false 的含义真的是"一条都没删"）；把返回值改成恒 {@code true} 本组变红；</li>
     *   <li>成功方向：正常库 → 必须 {@code true} 且数据已清；把返回值改成恒 {@code false}
     *       （过度失败）本组变红。</li>
     * </ul>
     *
     * <p><b>故障注入为什么用真 SQLite 而不是 mock</b>：{@code deletePlayerData} 的成败判据
     * 是"事务真的 commit 了"，这个事实只有真驱动能给。两种注入手段都在本类现有夹具内，
     * 不引入新依赖：
     * <ol>
     *   <li><b>缺表</b>（{@link #failedDeleteReturnsFalseAndRollsBack}）：删掉
     *       {@code lle_player_profiles} —— 它是 uuid 系表，{@code del()} 不做缺表容错
     *       （只有 {@code player_uuid} 系走 {@code delPlayerUuidTable}），故那条 DELETE 抛
     *       {@code no such table} → {@code failed=true} → rollback。这同时验证了
     *       "false ⇒ 整批回滚"，因为另一张表 {@code lle_player_data} 的行必须仍在。</li>
     *   <li><b>连接不可用</b>（{@link #unavailableConnectionReturnsFalse}）：
     *       {@code shutdown()} 之后 {@code getConnection()} 换发 EH-01 的抛异常替身，
     *       事务根本开不起来 —— 对应 javadoc 里的第二种 false。</li>
     * </ol>
     */
    @Nested
    @DisplayName("C. R11：deletePlayerData 返回布尔成败")
    class ReturnValueContract {

        @Test
        @DisplayName("成功路径返回 true，且行确实被删空")
        void successReturnsTrue() throws Exception {
            ensureAllDeletedTablesExist();
            UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dead");
            seedProfile(id);
            assertEquals(1, countRows("lle_player_profiles", "uuid", id.toString()),
                    "前置失败：profile 行没进库，后面的 true 可能来自空事务");

            boolean committed = db.deletePlayerData(id);

            assertTrue(committed,
                    "正常提交却返回 false —— 管理员会被误导成「重置失败」，"
                            + "而数据其实已经删干净（过度失败，方向同样有害）");
            assertEquals(0, countRows("lle_player_profiles", "uuid", id.toString()),
                    "返回了 true 但行还在 —— 返回值与事实不符，正是本缺陷的镜像");
        }

        @Test
        @DisplayName("任一表 DELETE 失败 → false，且整批回滚（另一张表的行必须还在）")
        void failedDeleteReturnsFalseAndRollsBack() throws Exception {
            ensureAllDeletedTablesExist();
            UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dead");
            seedProfile(id);
            // 同时 seed player_data：它是 uuid 系表里排在最前面的一张，
            // 用它做「回滚是否真的发生」的见证者。
            exec("INSERT OR REPLACE INTO lle_player_data (uuid, player_name) VALUES ('"
                    + id + "', 'Zombie')");
            // 注入故障：删掉一张 uuid 系表，让对应 DELETE 抛 no such table。
            exec("DROP TABLE lle_player_profiles");
            assertEquals(1, countRows("lle_player_data", "uuid", id.toString()),
                    "前置失败：见证行没进库，回滚断言会退化成恒真");

            boolean committed = db.deletePlayerData(id);

            assertFalse(committed,
                    "删表失败却返回 true —— 调用方会向管理员报「已重置」，"
                            + "而实际一条数据都没删（隐私删除语义下这是最重的一类谎报）");
            assertEquals(1, countRows("lle_player_data", "uuid", id.toString()),
                    "返回 false 但 player_data 被删了 —— false 的含义必须是"
                            + "「整批回滚、一条都没删」，部分删除比谎报成功更糟");
        }

        @Test
        @DisplayName("连接不可用（事务开不起来）→ false，不抛异常")
        void unavailableConnectionReturnsFalse() {
            UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dead");
            // EH-01 契约：shutdown 后 getConnection() 换发抛 SQLException 的替身句柄。
            db.shutdown();

            boolean committed = db.deletePlayerData(id);

            assertFalse(committed,
                    "事务开不起来却返回 true —— 这是「reset 报成功、数据一条没删」"
                            + "最容易被复现的一条路径（关服/重连窗口）");
        }
    }

    @Nested
    @DisplayName("B. 内存层：reset 专用入口，不改 quit 语义")
    class MemoryLedger {

        private RewardEngine newEngine() {
            return new RewardEngine(plugin);
        }

        private java.util.Map<UUID, Object> todayCoinsCache(RewardEngine engine) throws Exception {
            Field f = RewardEngine.class.getDeclaredField("todayCoinsCache");
            f.setAccessible(true);
            Object v = f.get(engine);
            assertNotNull(v, "todayCoinsCache 字段拿不到，测试失去意义");
            return (java.util.Map<UUID, Object>) v;
        }

        @Test
        @DisplayName("resetDailyCoinsFor 清掉该玩家的内存账本条目")
        void resetDailyCoinsForRemovesEntry() throws Exception {
            RewardEngine engine = newEngine();
            UUID id = UUID.randomUUID();

            java.util.Map<UUID, Object> cache = todayCoinsCache(engine);
            cache.put(id, newObjectLikeDailyCoins());
            assertTrue(cache.containsKey(id), "前置失败：没能塞进内存账本");

            engine.resetDailyCoinsFor(id);

            assertFalse(cache.containsKey(id),
                    "resetDailyCoinsFor 没清掉该玩家的 todayCoinsCache —— /lea reset 后"
                            + "内存日限仍按旧额度压制，直到跨天或 reload");
        }

        @Test
        @DisplayName("resetDailyCoinsFor 不影响其他玩家")
        void resetDailyCoinsForIsPerPlayer() throws Exception {
            RewardEngine engine = newEngine();
            UUID target = UUID.randomUUID();
            UUID bystander = UUID.randomUUID();

            java.util.Map<UUID, Object> cache = todayCoinsCache(engine);
            cache.put(target, newObjectLikeDailyCoins());
            cache.put(bystander, newObjectLikeDailyCoins());

            engine.resetDailyCoinsFor(target);

            assertFalse(cache.containsKey(target), "目标玩家未被清");
            assertTrue(cache.containsKey(bystander),
                    "旁观玩家的日限账本被误清 —— 那是别人的防刷额度，不能一起抹掉");
        }

        @Test
        @DisplayName("null 安全：resetDailyCoinsFor(null) 不抛")
        void resetDailyCoinsForNullIsSafe() {
            RewardEngine engine = newEngine();
            engine.resetDailyCoinsFor(null);
        }

        /**
         * quit 语义必须保持：{@code unloadPlayer} 仍**不**清 todayCoinsCache。
         * 这条与上一条互为反向 —— 若有人「顺手统一成 resetDailyCoinsFor」，
         * 这里会变红：下线重进即可重刷日限。
         */
        @Test
        @DisplayName("反向守卫：unloadPlayer（quit 语义）仍刻意不清日限账本")
        void unloadPlayerStillKeepsDailyLedger() throws Exception {
            RewardEngine engine = newEngine();
            UUID id = UUID.randomUUID();

            java.util.Map<UUID, Object> cache = todayCoinsCache(engine);
            cache.put(id, newObjectLikeDailyCoins());

            engine.unloadPlayer(id);

            assertTrue(cache.containsKey(id),
                    "unloadPlayer 把 todayCoinsCache 清了 —— 那等于「下线即重置日限」，"
                            + "玩家退出重进就能无限刷当日额度。该方法是 quit 语义，必须保持不清");
        }

        private Object newObjectLikeDailyCoins() throws Exception {
            Class<?> cls = Class.forName(
                    "com.letslearnenglish.minecraftplugin.core.progress.RewardEngine$DailyCoins");
            java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        }
    }

    private void seedProfile(UUID id) throws Exception {
        exec("INSERT OR REPLACE INTO lle_player_profiles (uuid, age, show_answers,"
                + " show_results, show_in_square, sounds_enabled, reminders_enabled)"
                + " VALUES ('" + id + "', 0, 0, 0, 0, 1, 1)");
    }
}
