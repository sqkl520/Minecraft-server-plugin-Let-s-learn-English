package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager.CheckInClaim;

/**
 * Round 31：从「服主把新 JAR 覆盖旧 JAR、重启服务器」的视角审计<b>存量库升级</b>。
 *
 * <h2>为什么这一条测试必须存在</h2>
 * 本仓有 1600+ 个用例，其中所有数据库用例都走 {@code DatabaseManager.initialize()} ——
 * 而它跑的是 {@code CREATE TABLE IF NOT EXISTS} + {@code addColumnIfMissing} 的<b>同一条</b>
 * 路径。于是「全新安装」与「从旧版本升级」在测试里<b>长得一样</b>：库是空的，
 * CREATE 把每张表按今天的形状建出来，{@code addColumnIfMissing} 全部走
 * 「列已存在 → return false」的快路径。<b>它一次也没有真的执行过 ALTER TABLE。</b>
 *
 * <p>而真实部署的动作恰恰相反：库是旧的，CREATE 全部命中
 * 「表已存在 → 什么都不做」，<b>唯一的 schema 变更通道是 {@code addColumnIfMissing} 的
 * ALTER TABLE</b>。也就是说：全仓唯一在升级路径上真正干活的代码，是测试里覆盖率最低的那一段。
 *
 * <p>{@code SchemaSqlConsistencyTest} 兜不住它 —— 那条测试从 {@code schema.sql} 建库，
 * 同样是「全新安装」形状。它能证明「SQL 引用的列在今天的 DDL 里存在」，
 * 证明不了「旧库里那些列会被补上」。
 *
 * <h2>本类怎么构造「旧库」</h2>
 * {@link #legacyDdl()} 是一份<b>手写</b>的、1.0.6 时代的 DDL 字面量：12 张表、列集取
 * {@code tasks/db-baseline.md} §3 记录的 1.0.7-SNAPSHOT 开工基线（该文件是仓库内的
 * 只读审计产物，逐表逐列记下了当时的形状），并刻意<b>不含</b>此后逐批新增的
 * 5 个列（{@code player_data.onboarding_step} / {@code player_data.wrong_book_cleared} /
 * {@code player_sessions.review_words} / {@code player_profiles.sounds_enabled} /
 * {@code player_profiles.reminders_enabled}）与全部 11 张后加的表。
 *
 * <p><b>刻意手写而不是「从今天的 schema 里减掉 addColumnIfMissing 覆盖的列」</b>：
 * 后者是自证 —— 用迁移清单去生成旧库，再断言迁移清单把旧库补齐，恒真。
 * 手写的那一份与生产代码零耦合，因此「旧库 → 新库」的收敛是<b>实测</b>出来的。
 *
 * <h2>每个用例守的是哪一半</h2>
 * <ul>
 *   <li>{@link SchemaConvergence} —— 升级后的列集合必须<b>覆盖</b>全新安装的列集合
 *       （漏一个 {@code addColumnIfMissing} 就红）；</li>
 *   <li>{@link DataPreservation} —— 旧行不许丢，新列必须拿到声明的默认值；</li>
 *   <li>{@link Idempotency} —— 连续三次启动不得报错、不得重复补列；</li>
 *   <li>{@link NewTables} —— 后加的表在旧库上首次启动就要建出来，并且真能用；</li>
 *   <li>{@link LazyTable} —— 懒建的 {@code daily_coin_counters} 不该被顺手建出来，
 *       且清理者必须容忍它缺席；</li>
 *   <li>{@link BackwardCompatibility} —— 旧版 JAR 的查询语句在升级后的库上仍能 prepare
 *       （降级回旧 JAR 是服主的真实动作）；</li>
 *   <li>{@link IndexFailureTolerance} —— 索引建失败不得阻断启动（这是「索引失败会不会
 *       让插件起不来」的实测答案）；</li>
 *   <li>{@link CheckInSemanticsMigration} —— 签到从裸 UPSERT 改成条件 UPDATE 之后，
 *       升级前「今天已签」的旧行必须仍然被正确识别为 {@code TAKEN}（不得二次发钱）；</li>
 *   <li>{@link ColumnDefaultDrift} —— <b>列在、默认值变过</b>的那一类：
 *       {@code addColumnIfMissing} 只管「列在不在」，改过默认值的列在升级库上<b>永远</b>
 *       停在旧值（实测 {@code player_profiles.show_answers}：全新 0 / 升级库 1）；</li>
 *   <li>{@link LegacySeasonReconciliation} —— 升级前「已抢占、无台账」的赛季必须被一次性
 *       对账扫描看到。</li>
 * </ul>
 */
@DisplayName("Round 31 存量库升级 — 旧 JAR 的库由新 JAR 接管")
class UpgradeMigrationTest {

    private static final String PREFIX = "lle_";
    private static final String LEGACY_DB = "legacy.db";
    private static final String FRESH_DB = "fresh.db";

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

    /**
     * plugin mock 在整类内复用：{@code LetsLearnEnglish} 的继承链很深，单次 {@code mock()}
     * 是十秒级开销（同 {@code NodeIdDerivationTest} / {@code DailyAnswerCountPersistenceTest}
     * 的记载）。每个用例只重打 {@code getDataFolder()} 的桩。
     */
    private static LetsLearnEnglish plugin;

    /** 默认日志器：{@code @BeforeAll} 打进去，个别用例临时换成 {@link Captured} 后要还回来。 */
    private static Logger BASE_LOG;

    /** 本用例开过的库，{@code @AfterEach} 统一关，避免 SQLite 句柄泄漏到下一个用例。 */
    private final List<DatabaseManager> opened = new ArrayList<>();

    @BeforeAll
    static void createPluginMock() {
        plugin = mock(LetsLearnEnglish.class);
        BASE_LOG = Logger.getLogger("UpgradeMigrationTest");
        BASE_LOG.setUseParentHandlers(false);
        when(plugin.getLogger()).thenReturn(BASE_LOG);
    }

    @org.junit.jupiter.api.BeforeEach
    void resetLogger() {
        when(plugin.getLogger()).thenReturn(BASE_LOG);
    }

    @AfterEach
    void closeDatabases() {
        for (DatabaseManager db : opened) {
            try {
                db.shutdown();
            } catch (RuntimeException ignored) {
                // 关库失败不该掩盖断言结果
            }
        }
        opened.clear();
    }

    /**
     * 日志探针：让「静默」（缺一条 WARNING / 多一条 WARNING）成为可断言的<b>事实</b>。
     *
     * <p>手法照 {@code DailyAnswerCountPersistenceTest.Captured}。为什么必须捕获而不是
     * 目视源码：{@code createIndexIfMissing} 的 catch 里写着 {@code fine(...)}，
     * 「写了 fine 所以不静默」与「根本没进 catch 所以什么都没打」在源码上是两种形状，
     * 但在运行时只有一个可观测差异 —— 日志里到底有没有行。
     */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<java.util.logging.Level> levels =
                java.util.Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final java.util.logging.Handler handler;

        Captured() {
            this.logger = Logger.getLogger("UpgradeMigrationCapture." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new java.util.logging.Handler() {
                @Override
                public void publish(java.util.logging.LogRecord record) {
                    messages.add(record.getMessage() == null ? "" : record.getMessage());
                    levels.add(record.getLevel());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
        }

        boolean anyAtOrAbove(java.util.logging.Level level) {
            for (java.util.logging.Level l : levels) {
                if (l.intValue() >= level.intValue()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    // ==================================================================
    // 1. 结构收敛：升级后的库必须覆盖全新安装的列集合
    // ==================================================================

    @Nested
    @DisplayName("1. 结构收敛（旧库 + 迁移 ⊇ 全新安装）")
    class SchemaConvergence {

        /**
         * 变异指向：把任意一条 {@code addColumnIfMissing} 从 {@code createTables()} 删掉
         * → 对应列在升级后的库里缺席 → 本用例红。这条正是「删掉一条迁移，全仓 1600 个
         * 用例仍全绿」的那个空洞。
         */
        @Test
        @DisplayName("每张表的列集合：升级后必须包含全新安装的每一列")
        void upgradedSchemaCoversFreshSchema(@TempDir Path dir) throws Exception {
            Map<String, Set<String>> fresh = columnsOfBooted(dir, FRESH_DB);
            newLegacyDb(dir);
            Map<String, Set<String>> upgraded = columnsOfBooted(dir, LEGACY_DB);

            // 表本身：后加的表必须全部建出来（CREATE TABLE IF NOT EXISTS 在旧库上会执行）
            assertEquals(fresh.keySet(), upgraded.keySet(),
                    "表集合必须一致。缺表 = 那个模块在升级后的服务器上每次调用都撞 "
                            + "`no such table`，而全新安装永远复现不出来。"
                            + "\n  只在全新安装里有: " + minus(fresh.keySet(), upgraded.keySet())
                            + "\n  只在升级库里存在: " + minus(upgraded.keySet(), fresh.keySet()));

            for (Map.Entry<String, Set<String>> e : fresh.entrySet()) {
                String table = e.getKey();
                Set<String> freshCols = e.getValue();
                Set<String> upCols = upgraded.get(table);
                assertNotNull(upCols, "升级后的库缺表 " + table);
                Set<String> missing = minus(freshCols, upCols);
                assertTrue(missing.isEmpty(),
                        "表 " + table + " 升级后缺列 " + missing + " —— 该列在今天的 CREATE TABLE 里"
                                + "存在，说明它是后来新增的，但 createTables() 里<b>没有</b>对应的 "
                                + "addColumnIfMissing。后果：存量服升级后每一句引用该列的 SQL 都在"
                                + "prepare 阶段抛 `no such column`（正是 SchemaSqlConsistencyTest "
                                + "记录过的 R14 缺陷形态），而全新安装的服务器完全正常 —— "
                                + "「开发机删档重装」永远发现不了。"
                                + "\n  升级后实际列: " + upCols);
            }
        }

        @Test
        @DisplayName("后加的 11 张表在旧库上首次启动就存在，且旧库原本只有 12 张")
        void newTablesAreCreatedOnLegacyDatabase(@TempDir Path dir) throws Exception {
            // 先确认「旧库」真的是旧的（否则下面的断言可能因为 DDL 写错而假绿）
            newLegacyDb(dir);
            Map<String, Set<String>> before = columnsOf(dir.resolve(LEGACY_DB));
            assertEquals(12, before.size(),
                    "前置：手写的旧库必须恰好 12 张表（1.0.6 时代的形状）。实际: " + before.keySet());
            assertFalse(before.containsKey(PREFIX + "seasons"), "前置：旧库没有 seasons");
            assertFalse(before.containsKey(PREFIX + "daily_answer_counters"),
                    "前置：旧库没有 daily_answer_counters");

            boot(dir, LEGACY_DB);

            Map<String, Set<String>> after = columnsOf(dir.resolve(LEGACY_DB));
            for (String t : new String[]{"ai_chat_feedback", "daily_word_reward_claims", "ai_round_counters",
                    "daily_answer_counters", "duel_stats", "social_feed", "social_cheers",
                    "seasons", "season_rewards", "classes", "class_members"}) {
                assertTrue(after.containsKey(PREFIX + t),
                        "后加的表 " + t + " 必须在旧库首次启动时建出来 —— 它不在旧库里，"
                                + "只能靠 createTables() 的 CREATE TABLE IF NOT EXISTS。实际表集: "
                                + after.keySet());
            }
        }
    }

    // ==================================================================
    // 2. 数据保留 + 新列默认值
    // ==================================================================

    @Nested
    @DisplayName("2. 数据保留与新列默认值")
    class DataPreservation {

        @Test
        @DisplayName("旧行不丢，且每个新列都拿到声明过的默认值（不是 NULL）")
        void legacyRowsSurviveWithDeclaredDefaults(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            seedLegacyPlayer(dir);

            boot(dir, LEGACY_DB);

            try (Connection c = open(dir, LEGACY_DB)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT total_words_learned, total_sessions, total_score, onboarding_step,"
                                + " wrong_book_cleared, conversation_count FROM " + PREFIX
                                + "player_data WHERE uuid = ?")) {
                    ps.setString(1, PLAYER.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "旧玩家的 player_data 行必须还在（升级绝不能删数据）");
                        assertEquals(420, rs.getInt("total_words_learned"), "旧值必须原样保留");
                        assertEquals(37, rs.getInt("total_sessions"), "旧值必须原样保留");
                        assertEquals(98765L, rs.getLong("total_score"), "旧值必须原样保留");
                        assertEquals(0, rs.getInt("onboarding_step"),
                                "onboarding_step 是升级补的列，声明是 `INT DEFAULT 0` —— "
                                        + "必须真的是 0。为 NULL 时 OnboardingManager 的 "
                                        + "`step == 0` 判定虽然也成立，但其它读侧一律 NPE/0 分叉。");
                        assertEquals(0, rs.getInt("wrong_book_cleared"));
                        assertEquals(0, rs.getInt("conversation_count"));
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT review_words FROM " + PREFIX + "player_sessions WHERE uuid = ?")) {
                    ps.setString(1, PLAYER.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "旧会话行必须还在");
                        assertEquals(0, rs.getInt("review_words"),
                                "player_sessions.review_words 升级补列，声明 `INT DEFAULT 0`");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT age, show_answers, show_results, show_in_square, sounds_enabled,"
                                + " reminders_enabled FROM " + PREFIX + "player_profiles WHERE uuid = ?")) {
                    ps.setString(1, PLAYER.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "旧档案行必须还在");
                        assertEquals(17, rs.getInt("age"), "旧值必须原样保留");
                        assertEquals(0, rs.getInt("show_answers"),
                                "旧行<b>存着</b>的值必须原样保留 —— 这一行是升级前写入的，"
                                        + "列当时就存在，补列逻辑不该碰它。注意这与「新插入行的"
                                        + "默认值」是两件事，见 ColumnDefaultDrift。");
                        assertEquals(1, rs.getInt("show_results"), "旧值必须原样保留");
                        assertEquals(0, rs.getInt("show_in_square"),
                                "旧值必须原样保留 —— 这一列若被补列时的默认值 1 覆盖，"
                                        + "等于把玩家的隐身设置静默改回「公开」（隐私回归）");
                        assertEquals(1, rs.getInt("sounds_enabled"),
                                "sounds_enabled 升级补列默认 1（= 开启）。这是<b>行为变更</b>："
                                        + "老玩家此前根本没有音效功能，升级后立即开始响。可接受，"
                                        + "但 DEPLOYMENT_WIKI 的升级步骤没有提到它。");
                        assertEquals(1, rs.getInt("reminders_enabled"),
                                "reminders_enabled 升级补列默认 1（保持既有行为：老版本本来就会提醒）");
                    }
                }
            }
        }

        @Test
        @DisplayName("旧玩家不存在时不被凭空建行（补列不得顺带插数据）")
        void migrationDoesNotInventRows(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            boot(dir, LEGACY_DB);
            try (Connection c = open(dir, LEGACY_DB);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + PREFIX + "player_data")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "空旧库升级后仍必须是 0 行");
            }
        }

        /**
         * 1.0.0 → 1.0.7 的一段真实断代：{@code total_coins_earned} / {@code total_coins_spent}
         * 是 1.0.6 之后才加的（CHANGELOG「彻底分离学习分数与金币统计」），而
         * {@code createTables()} 至今保留着针对它们的 {@code addColumnIfMissing} ——
         * 那一对调用就是为这段断代留的。
         *
         * <p>本用例把 {@code player_data} 退化成<b>更早</b>的形状（只有 1.0.0 那批列），
         * 验证补列之后金币账不再撞 {@code no such column}。这条是「从 1.0.0 升上来」
         * 这个视角最直接的正面证据。
         */
        @Test
        @DisplayName("1.0.0 形状的 player_data（无 total_coins_*）→ 补列后金币写入可用")
        void oneZeroZeroPlayerDataGainsCoinColumns(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            // 退化成 1.0.0：连 total_coins_earned / total_coins_spent 都没有
            try (Connection c = open(dir, LEGACY_DB); Statement st = c.createStatement()) {
                st.execute("DROP TABLE " + PREFIX + "player_data");
                st.execute("CREATE TABLE " + PREFIX + "player_data ("
                        + " uuid VARCHAR(36) PRIMARY KEY,"
                        + " player_name VARCHAR(32),"
                        + " total_words_learned INT DEFAULT 0,"
                        + " total_sessions INT DEFAULT 0,"
                        + " total_score BIGINT DEFAULT 0,"
                        + " current_streak INT DEFAULT 0,"
                        + " longest_streak INT DEFAULT 0,"
                        + " last_login BIGINT, created_at BIGINT, updated_at BIGINT)");
                st.execute("INSERT INTO " + PREFIX + "player_data"
                        + " (uuid, player_name, total_score) VALUES ('" + PLAYER + "', 'Bob', 500)");
            }

            DatabaseManager db = boot(dir, LEGACY_DB);

            // 真跑一条金币写入（生产路径：recordCoinsEarned → adjustCoins 的 UPSERT）——
            // 不是只看列在不在。返回值就是「这次写库有没有成功」的真源（R11 起有 boolean）。
            assertTrue(db.recordCoinsEarned(PLAYER, 250.0),
                    "金币流水写入必须成功。缺 total_coins_earned 列时 SQLite 在 prepare 阶段就抛 "
                            + "`no such column`，adjustCoins 把它吞成 WARNING 并返回 false —— "
                            + "而玩家照常答题、金币照常「发放」，账本却一条没记。");
            assertEquals(250L, db.getCoinsEarned(PLAYER),
                    "补列后 total_coins_earned 必须可写可读");

            // 旧学习分不得被金币补列逻辑改动 —— 用独立连接直读，不走被测 getter。
            try (Connection c = open(dir, LEGACY_DB);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT total_score, total_coins_earned FROM " + PREFIX
                                 + "player_data WHERE uuid = ?")) {
                ps.setString(1, PLAYER.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "金币写入不该删掉这一行");
                    assertEquals(500L, rs.getLong("total_score"),
                            "旧的学习分不得被金币补列逻辑改动");
                    assertEquals(250L, rs.getLong("total_coins_earned"),
                            "账本真实落库值（不信 getter，直读库）");
                }
            }
        }
    }

    // ==================================================================
    // 3. 幂等：每次启动 createTables() 都会重跑
    // ==================================================================

    @Nested
    @DisplayName("3. addColumnIfMissing 幂等性")
    class Idempotency {

        @Test
        @DisplayName("连续三次启动：不抛异常，且每个补出来的列只存在一份")
        void repeatedStartupsDoNotFailOrDuplicate(@TempDir Path dir) throws Exception {
            // 用带旧 seasons 表的旧库：它是唯一一张「老表 + 后补两列」的表，
            // 也是 ALTER TABLE 最容易被重复执行的那张。
            newLegacyDbWithSeasons(dir);
            seedLegacyPlayer(dir);

            // 每次启动前显式关上一轮（真重启 = 进程退出，句柄释放）。不关的话三个
            // DatabaseManager 同时持同一文件的 WAL 连接，锁竞争会污染「幂等」这个断言。
            boot(dir, LEGACY_DB).shutdown();
            boot(dir, LEGACY_DB).shutdown();
            DatabaseManager third = boot(dir, LEGACY_DB);

            assertTrue(third.isSQLite(), "前置：仍是 SQLite 分支");

            Map<String, Set<String>> cols = columnsOf(dir.resolve(LEGACY_DB));
            // PRAGMA table_info 对同名重复列会返回两行；用 Set 会掩盖重复，故这里直接扫行
            for (String table : new String[]{"player_data", "player_profiles", "player_sessions", "seasons"}) {
                // 重载契约是「数据库文件路径」，不是「目录」：传目录会被拼成
                // jdbc:sqlite:<tempDir> → SQLITE_CANTOPEN_ISDIR。这里必须自己 resolve 出文件名。
                List<String> raw = rawColumnNames(dir.resolve(LEGACY_DB), PREFIX + table);
                assertFalse(raw.isEmpty(), "表 " + table + " 必须有列（不存在时 PRAGMA 返回空）");
                assertEquals(new LinkedHashSet<>(raw).size(), raw.size(),
                        "表 " + table + " 出现了重复列（" + raw + "）—— 说明某次 ALTER TABLE 被重复"
                                + "执行且没被「列已存在」拦住。addColumnIfMissing 的存在性判据必须是"
                                + "元数据查询（SQLite PRAGMA table_info / MySQL information_schema），"
                                + "不是异常消息匹配。");
                assertTrue(cols.containsKey(PREFIX + table), "表 " + table + " 必须存在");
            }
        }

        @Test
        @DisplayName("第二次启动不再消费「刚补上 ledger_pending」标志（一次性语义）")
        void ledgerPendingFlagIsConsumedOnlyOnce(@TempDir Path dir) throws Exception {
            newLegacyDbWithSeasons(dir);
            DatabaseManager first = boot(dir, LEGACY_DB);
            assertTrue(first.consumeSeasonLedgerPendingColumnAdded(),
                    "第一次从无该列的旧库启动 → 必须消费「刚补列」标志（据此做一次性人工对账）");
            first.shutdown(); // 真重启 = 进程退出

            DatabaseManager second = boot(dir, LEGACY_DB);
            assertFalse(second.consumeSeasonLedgerPendingColumnAdded(),
                    "列已存在的那次启动不得再报「刚补列」—— 否则每次重启都刷一遍 SEVERE，"
                            + "运维会把它当噪音屏蔽，真正需要人工对账的那一次反而被忽略");
        }
    }

    // ==================================================================
    // 4. 后加的表真能用（不只是「存在」）
    // ==================================================================

    @Nested
    @DisplayName("4. 新表在旧库上真能用")
    class NewTables {

        @Test
        @DisplayName("daily_answer_counters：升级后首次写入 + 回读成功")
        void dailyAnswerCounterWorksAfterUpgrade(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            DatabaseManager db = boot(dir, LEGACY_DB);

            String today = java.time.LocalDate.now().toString();
            assertEquals(0, db.getDailyAnswerCount(PLAYER, today),
                    "新表在旧库上刚建出来时必须读到 0（不得抛 no such table）");
            db.incrementDailyAnswerCount(PLAYER, today);
            db.incrementDailyAnswerCount(PLAYER, today);
            assertEquals(2, db.getDailyAnswerCount(PLAYER, today),
                    "UPSERT 累加语义（`answers = answers + 1`）在旧库升级上必须成立 —— "
                            + "写成覆盖 1 会让赏金门 word-system.daily-limit 永久失效");
        }

        @Test
        @DisplayName("存量数据不可回填：升级当天计数为 0（把 CHANGELOG 的说法钉成事实）")
        void upgradeDayStartsFromZeroBecauseNothingWasEverPersisted(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            seedLegacyPlayer(dir); // 旧玩家今天已经答了 40 题（旧版本只记在内存里，库里没有痕迹）
            DatabaseManager db = boot(dir, LEGACY_DB);

            assertEquals(0, db.getDailyAnswerCount(PLAYER, java.time.LocalDate.now().toString()),
                    "升级当天新表里必然没有今天的行 —— 这是<b>结构性事实</b>，不是缺陷："
                            + "旧版本把当日答题数只放在内存 ConcurrentHashMap 里，从未落盘，"
                            + "因此没有可回填的真值。CHANGELOG 的「升级当天额度全满」说法准确。");
        }
    }

    // ==================================================================
    // 5. 懒建表
    // ==================================================================

    @Nested
    @DisplayName("5. 懒建的 daily_coin_counters")
    class LazyTable {

        @Test
        @DisplayName("升级后仍不存在（不在 createTables 清单里），且清理者必须容忍缺席")
        void lazyTableIsAbsentAndCleanupToleratesIt(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            DatabaseManager db = boot(dir, LEGACY_DB);

            Map<String, Set<String>> cols = columnsOf(dir.resolve(LEGACY_DB));
            assertFalse(cols.containsKey(PREFIX + "daily_coin_counters"),
                    "daily_coin_counters 由 RewardEngine.ensureDailyCoinsTable 懒建，"
                            + "createTables() 里没有它。若它被顺手加进建表清单，"
                            + "database/DatabaseManager 与 core/progress/RewardEngine 会出现两份 DDL，"
                            + "漂移即静默。");

            // 清理者必须容忍缺席：旧库里插一行过期聊天记录，清理必须跑完并删掉它，
            // 而不是在第一条缺表语句上整体逃逸。
            try (Connection c = open(dir, LEGACY_DB)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + PREFIX + "ai_chat_history"
                                + " (player_uuid, session_id, role, content, timestamp)"
                                + " VALUES (?, ?, ?, ?, datetime('now','-90 days'))")) {
                    ps.setString(1, PLAYER.toString());
                    ps.setString(2, "sess-old");
                    ps.setString(3, "user");
                    ps.setString(4, "old");
                    ps.executeUpdate();
                }
            }

            db.cleanupOldChatHistory(30); // 缺 daily_coin_counters 时不得抛

            try (Connection c = open(dir, LEGACY_DB);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + PREFIX + "ai_chat_history")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1),
                        "缺表只该丢一条日志，<b>不能</b>让排在它前面的清理者一起失效 —— "
                                + "否则「升级后聊天记录永不清理」这件事会静默成立");
            }
        }
    }

    // ==================================================================
    // 6. 降级方向：旧 JAR 的查询在升级后的库上仍能跑
    // ==================================================================

    @Nested
    @DisplayName("6. 降级回旧 JAR（服主的真实救急动作）")
    class BackwardCompatibility {

        /**
         * SQLite 对「多余列/多余表」是容忍的，所以降级在 SQLite 上通常安全。
         * <b>MySQL 不做这个保证</b>，但那条只能真机验，本类只钉 SQLite 侧的基线：
         * 新 JAR 跑过一次之后，旧版 JAR 的每一条读语句必须仍能 prepare 并执行。
         *
         * <p>选取口径：1.0.6 时代各表所有<b>显式列清单</b>的读语句（升级最怕的是列被改名/
         * 删除，而不是新增）。若哪天真删了列，这条会红。
         */
        @Test
        @DisplayName("1.0.6 时代的读语句在升级后的库上仍全部可执行")
        void legacySelectStatementsStillRun(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            seedLegacyPlayer(dir);
            boot(dir, LEGACY_DB); // 新 JAR 跑过一次

            String[] legacySelects = {
                "SELECT total_words_learned, total_sessions, total_score, current_streak,"
                        + " longest_streak FROM " + PREFIX + "player_data WHERE uuid = ?",
                "SELECT uuid, word, mastery, correct_count, incorrect_count, last_review_time"
                        + " FROM " + PREFIX + "word_progress WHERE uuid = ?",
                "SELECT session_date, words_count, correct_count, score, mode"
                        + " FROM " + PREFIX + "player_sessions WHERE uuid = ?",
                "SELECT age, show_answers, show_results, show_in_square"
                        + " FROM " + PREFIX + "player_profiles WHERE uuid = ?",
                "SELECT last_checkin_date, streak_count FROM " + PREFIX
                        + "checkin_data WHERE player_uuid = ?",
                "SELECT word, wrong_count FROM " + PREFIX + "difficult_words WHERE player_uuid = ?",
                "SELECT achievement_id, unlocked_at FROM " + PREFIX
                        + "player_achievements WHERE uuid = ?",
                "SELECT content, role FROM " + PREFIX + "ai_chat_history WHERE player_uuid = ?",
                "SELECT challenge_id, progress, completed, claimed FROM " + PREFIX
                        + "daily_challenges WHERE uuid = ?",
            };
            try (Connection c = open(dir, LEGACY_DB)) {
                for (String sql : legacySelects) {
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, PLAYER.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next(); // 只要不抛即可
                        }
                    } catch (SQLException e) {
                        fail("降级回旧 JAR 后这条 1.0.6 时代的查询跑不了 —— 新版本做了破坏性 schema"
                                + " 变更（改列名 / 删列）：\n  " + sql + "\n  → " + e.getMessage());
                    }
                }
            }
        }
    }

    // ==================================================================
    // 7. 索引建失败：不阻断启动（但会静默失效）
    // ==================================================================

    @Nested
    @DisplayName("7. 索引创建失败的影响面")
    class IndexFailureTolerance {

        /**
         * 真实场景：服主手工建过同名索引、或从别的插件/旧版本继承了同名索引，名字撞车。
         * {@code CREATE INDEX IF NOT EXISTS} 同名即静默跳过（SQLite 语义），于是
         * <b>想要的索引一次都没建出来，而日志里连一条 FINE 都没有</b>
         * （{@code createIndexIfMissing} 的 catch 只在抛异常时才记）。
         *
         * <p>这条用例同时回答两个问题：① 索引失败<b>不会</b>阻断启动（{@code initialize()}
         * 不抛）；② 失败是<b>静默</b>的 —— 运维无从知道那个索引其实不存在。
         */
        @Test
        @DisplayName("同名索引已存在 → 启动照常，但目标索引静默未建（日志里一个字都没有）")
        void nameCollisionSkipsSilentlyWithoutBlockingStartup(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            // 预先占掉这个名字，但建在另一张表上（撞车的最朴素形态）
            try (Connection c = open(dir, LEGACY_DB); Statement st = c.createStatement()) {
                st.execute("CREATE INDEX " + PREFIX + "idx_chat_timestamp"
                        + " ON " + PREFIX + "difficult_words(word)");
            }

            try (Captured logs = new Captured()) {
                when(plugin.getLogger()).thenReturn(logs.logger); // 把日志导进探针
                try {
                    DatabaseManager db = boot(dir, LEGACY_DB); // 不抛 = 索引失败不阻断启动
                    assertTrue(db.isSQLite(), "前置：仍走 SQLite 分支");

                    // 自检：探针必须真的接上了。`initialize()` 成功路径一定会打
                    // "Database initialized (type: sqlite)"。少了这条，下面那句
                    // 「日志里没提到索引名」就可能只是因为探针压根没捕获任何东西 ——
                    // 那是本仓 MEMORY 记的「闸门未触及」假绿形态。
                    boolean sawInitLine = false;
                    for (String m : logs.messages) {
                        if (m != null && m.contains("Database initialized")) {
                            sawInitLine = true;
                        }
                    }
                    assertTrue(sawInitLine,
                            "前置（自检）：日志探针必须捕获到 initialize() 的成功行，"
                                    + "否则下面「没提到索引名」是空断言。实际捕获: " + logs.messages);

                    // 只筛与这个索引名相关的行：initialize() 自己会打 "Database initialized"、
                    // WAL 体检等无关行，不能笼统断言「一条日志都没有」。
                    List<String> aboutThisIndex = new ArrayList<>();
                    for (String m : logs.messages) {
                        if (m != null && m.contains("idx_chat_timestamp")) {
                            aboutThisIndex.add(m);
                        }
                    }
                    assertTrue(aboutThisIndex.isEmpty(),
                            "撞名跳过索引时日志里<b>一个字都不该提到这个索引名</b> —— "
                                    + "`CREATE INDEX IF NOT EXISTS` 同名时是「成功跳过」而非异常，"
                                    + "所以 createIndexIfMissing 的 catch 根本不会进，那条 fine(...) 也"
                                    + "就不会打。运维面对的正是这个形态：索引没建出来，日志干净。"
                                    + "实际命中: " + aboutThisIndex);

                    // levels 探针同样要活着（这是「没有任何 SEVERE 记录」的旁证）
                    assertFalse(logs.anyAtOrAbove(java.util.logging.Level.SEVERE),
                            "撞名不是故障，不该有 SEVERE。实际: " + logs.messages);
                } finally {
                    when(plugin.getLogger()).thenReturn(BASE_LOG);
                }
            }

            // 启动成功但索引不在正确的表上 —— 静默失效
            Map<String, String> idx = indexesOn(dir.resolve(LEGACY_DB));
            String table = idx.get(PREFIX + "idx_chat_timestamp");
            assertNotNull(table, "前置：那个撞名的索引本身仍在");
            assertEquals(PREFIX + "difficult_words", table,
                    "撞名时 `CREATE INDEX IF NOT EXISTS` 会静默跳过，目标索引<b>不会</b>被建到 "
                            + "ai_chat_history 上。这是本轮实测确认的行为：升级路径上索引可能"
                            + "完全没建出来，而 initialize() 一个字都不打（成功路径无日志，"
                            + "catch 只在抛异常时记 FINE）。后果是 cleanupOldChatHistory 在"
                            + "万人服上退化成全扫（实测 0.1 ms → 205.80 ms，见 schema.sql 注释），"
                            + "而服主只会觉得「这插件有点卡」。");
        }

        @Test
        @DisplayName("索引全部在正确的表上（正常路径的基线，防上面那条用例掩盖真问题）")
        void normalUpgradeBuildsEveryIndexOnTheRightTable(@TempDir Path dir) throws Exception {
            boot(dir, FRESH_DB); // 全新安装：索引一定建得出来
            Map<String, String> fresh = indexesOn(dir.resolve(FRESH_DB));

            newLegacyDb(dir);
            boot(dir, LEGACY_DB);
            Map<String, String> upgraded = indexesOn(dir.resolve(LEGACY_DB));

            assertFalse(fresh.isEmpty(), "前置：全新安装必须建出索引，否则本用例没有对照物");
            Set<String> missing = minus(fresh.keySet(), upgraded.keySet());
            assertTrue(missing.isEmpty(),
                    "升级后缺索引 " + missing + " —— createIndexIfMissing 在 MySQL 分支靠 "
                            + "information_schema 查重、SQLite 分支靠 IF NOT EXISTS，两者都幂等；"
                            + "缺说明建索引那一步整体没跑到（例如 createTables 提前抛了）。"
                            + "\n  全新安装有: " + fresh.keySet()
                            + "\n  升级后有: " + upgraded.keySet());
            for (Map.Entry<String, String> e : fresh.entrySet()) {
                assertEquals(e.getValue(), upgraded.get(e.getKey()),
                        "索引 " + e.getKey() + " 建在了错误的表上");
            }
        }
    }

    // ==================================================================
    // 8. 签到：裸 UPSERT → 条件 UPDATE 的语义切换
    // ==================================================================

    @Nested
    @DisplayName("8. checkin_data 条件写入语义（升级前已签的旧行）")
    class CheckInSemanticsMigration {

        @Test
        @DisplayName("旧行日期 = 今天 → TAKEN（不得二次发钱）")
        void legacyRowForTodayYieldsTaken(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            String today = java.time.LocalDate.now().toString();
            try (Connection c = open(dir, LEGACY_DB);
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO " + PREFIX + "checkin_data"
                                 + " (player_uuid, last_checkin_date, streak_count) VALUES (?, ?, ?)")) {
                ps.setString(1, PLAYER.toString());
                ps.setString(2, today);
                ps.setInt(3, 12);
                ps.executeUpdate();
            }

            DatabaseManager db = boot(dir, LEGACY_DB);

            assertEquals(CheckInClaim.TAKEN,
                    db.claimCheckIn(PLAYER, today, 13),
                    "升级前（旧版本裸 UPSERT）玩家今天已经签过，新版本的条件 UPDATE 必须命中 0 行、"
                            + "IGNORE 插入也命中 0 行 → TAKEN。若这里返回 CLAIMED，等于升级当天"
                            + "每个已签到玩家都能再领一份钱（`CheckInManager` 只在 CLAIMED 分支发钱）。");
            assertEquals(12, db.loadCheckInData(PLAYER).data().getStreakCount(),
                    "TAKEN 路径不得改动 streak");
        }

        @Test
        @DisplayName("旧行日期 = 昨天 → CLAIMED 且 streak 递增（正常的次日签到）")
        void legacyRowForYesterdayYieldsClaimed(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            String today = java.time.LocalDate.now().toString();
            try (Connection c = open(dir, LEGACY_DB);
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO " + PREFIX + "checkin_data"
                                 + " (player_uuid, last_checkin_date, streak_count) VALUES (?, ?, ?)")) {
                ps.setString(1, PLAYER.toString());
                ps.setString(2, java.time.LocalDate.now().minusDays(1).toString());
                ps.setInt(3, 12);
                ps.executeUpdate();
            }

            DatabaseManager db = boot(dir, LEGACY_DB);
            assertEquals(CheckInClaim.CLAIMED, db.claimCheckIn(PLAYER, today, 13),
                    "昨天的旧行必须让今天的条件 UPDATE 命中 1 行 → CLAIMED");
            assertEquals(13, db.loadCheckInData(PLAYER).data().getStreakCount(), "streak 应更新为 13");
        }

        @Test
        @DisplayName("旧行 last_checkin_date 为 NULL（历史库可能）→ CLAIMED，不被永久判成已签")
        void legacyNullDateRowStillClaims(@TempDir Path dir) throws Exception {
            newLegacyDb(dir);
            String today = java.time.LocalDate.now().toString();
            // 旧 DDL 里 last_checkin_date 是 NOT NULL，但手工改库/别的插件写过 NULL 是现实形状。
            // 这里直接绕过 NOT NULL 建一个可空的等价表来模拟。
            try (Connection c = open(dir, LEGACY_DB)) {
                try (Statement st = c.createStatement()) {
                    st.execute("DROP TABLE " + PREFIX + "checkin_data");
                    st.execute("CREATE TABLE " + PREFIX + "checkin_data ("
                            + " player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                            + " last_checkin_date DATE, streak_count INTEGER DEFAULT 1)");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + PREFIX + "checkin_data"
                                + " (player_uuid, last_checkin_date, streak_count) VALUES (?, NULL, 3)")) {
                    ps.setString(1, PLAYER.toString());
                    ps.executeUpdate();
                }
            }

            DatabaseManager db = boot(dir, LEGACY_DB);
            assertEquals(CheckInClaim.CLAIMED, db.claimCheckIn(PLAYER, today, 4),
                    "`NULL <> '2026-xx-xx'` 在 SQL 里求值为 NULL（不是 true），故 WHERE 里必须显式带 "
                            + "`last_checkin_date IS NULL`；漏了它 + IGNORE 插入撞主键 → 该玩家被"
                            + "永久判成「今日已签」，从此再也签不上（连续天数中断且无法自愈）。");
        }
    }

    // ==================================================================
    // 10. 列默认值漂移：addColumnIfMissing 结构上修不了的那一类
    // ==================================================================

    @Nested
    @DisplayName("10. 列默认值漂移（列在、默认值变过）")
    class ColumnDefaultDrift {

        /**
         * <b>本类最重要的一条：它记录了一个「结构收敛但语义不收敛」的升级死角。</b>
         *
         * <p>{@code player_profiles.show_answers} 的默认值在 Phase 1 流 E 由 {@code 1}
         * 改为 {@code 0}（D2「隐私优先」决策，CHANGELOG 明确记录）。全新安装的库拿到
         * {@code DEFAULT 0}；<b>存量库拿到的是旧 DDL 里那个 {@code DEFAULT 1}</b> ——
         * {@code addColumnIfMissing} 只在「列不存在」时动作，这条列一直在，于是它<b>永远</b>
         * 补不上。
         *
         * <p>后果是<b>只落在「升级后新插入的行」</b>上：老玩家已有行（升级前写入的值）
         * 不受影响；但升级后第一次进服、第一次保存档案的玩家，会拿到
         * {@code show_answers = 1}（= 同意公开答题结果），而全新安装的同一操作拿到 0
         * （= 不公开）。同一份插件、同一句「隐私优先」，在两种装法上给出相反的默认值。
         *
         * <p>为什么这条至今没被发现：{@code PlayerDataStore.loadProfileSettings} 的
         * {@code else} 分支（DB 无行 = 新玩家）用的是 Java 侧 {@code new ProfileSettings()}
         * 的字段默认值，不读 DDL 默认值；只有 {@code saveProfileSettings} 的
         * {@code INSERT OR REPLACE}（SQLite）在<b>行已存在</b>时的整对象覆盖、以及
         * 任何绕过 Java 默认值直接 INSERT 的路径才会碰到 DDL 默认值。也就是说这是一个
         * 「两条写路径默认值不一致」的潜伏面，而非当前必然触发的缺陷。
         */
        @Test
        @DisplayName("show_answers 的 DDL 默认值：全新安装是 0，升级库仍是旧值 1（升级死角）")
        void showAnswersDefaultDivergesOnUpgradedDatabase(@TempDir Path dir) throws Exception {
            boot(dir, FRESH_DB);
            newLegacyDb(dir);
            boot(dir, LEGACY_DB);

            String freshDefault = ddlOf(dir.resolve(FRESH_DB), PREFIX + "player_profiles", "show_answers");
            String upDefault = ddlOf(dir.resolve(LEGACY_DB), PREFIX + "player_profiles", "show_answers");

            assertEquals("0", freshDefault, "全新安装的 show_answers 默认值必须是 0（D2 隐私优先）");
            assertEquals("1", upDefault,
                    "【升级死角，实测结论】存量库的 show_answers 默认值仍然是旧值 1 —— "
                            + "addColumnIfMissing 只管「列在不在」，列一直在就永远不会改默认值。"
                            + "全新安装拿 0、升级库拿 1，同一句「隐私优先」给出相反结果。"
                            + "这条<b>不是</b>本轮引入的缺陷，也无法靠补列修掉（SQLite 不支持 "
                            + "ALTER COLUMN SET DEFAULT，改默认值要整表重建）；记录在案供运维文档"
                            + "与后续决策用。");
        }

        /**
         * 反向对照：确认上面的「漂移」不是所有列的通病 —— 没改过默认值的列两边一致。
         * 没有这条，「两条都读成 1」也会让上面那条通过。
         */
        @Test
        @DisplayName("反向对照：没改过默认值的列（show_results / show_in_square）两边一致")
        void unchangedDefaultsAreIdenticalBothWays(@TempDir Path dir) throws Exception {
            boot(dir, FRESH_DB);
            newLegacyDb(dir);
            boot(dir, LEGACY_DB);

            for (String col : new String[]{"show_results", "show_in_square", "age"}) {
                assertEquals(ddlOf(dir.resolve(FRESH_DB), PREFIX + "player_profiles", col),
                        ddlOf(dir.resolve(LEGACY_DB), PREFIX + "player_profiles", col),
                        "列 " + col + " 的默认值两边必须一致 —— 它没被改过。不一致说明"
                                + "上面那条「漂移」结论的前提（只漂了 show_answers）不成立，"
                                + "本类需要重新核对旧 DDL。");
            }
        }
    }

    // ==================================================================
    // 9. 一次性人工对账：升级前「已抢占、无台账」的赛季
    // ==================================================================

    @Nested
    @DisplayName("9. seasons 补列与遗留欠账对账")
    class LegacySeasonReconciliation {
        @Test
        @DisplayName("旧库（无 ledger_pending / claim_owner）→ 补列 + 可疑季被扫描出来")
        void legacyClaimedSeasonIsReportedOnce(@TempDir Path dir) throws Exception {
            newLegacyDbWithSeasons(dir);
            // 升级前就已结算、但台账一行都没有（旧版本写失败被吞掉的历史形状）
            try (Connection c = open(dir, LEGACY_DB)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + PREFIX + "seasons"
                                + " (season_id, start_date, end_date, settled_at, top_snapshot)"
                                + " VALUES (?, ?, ?, ?, NULL)")) {
                    ps.setInt(1, 1);
                    ps.setString(2, "2026-01-01");
                    ps.setString(3, "2026-01-30");
                    ps.setLong(4, 1767225600000L);
                    ps.executeUpdate();
                }
            }

            DatabaseManager db = boot(dir, LEGACY_DB);

            // 补出来的两列必须存在且语义正确
            try (Connection c = open(dir, LEGACY_DB);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT settled_at, ledger_pending, claim_owner FROM " + PREFIX
                                 + "seasons WHERE season_id = 1");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1767225600000L, rs.getLong("settled_at"), "settled_at 必须原值保留");
                assertEquals(0, rs.getInt("ledger_pending"),
                        "补列默认值必须是 0 —— 遗留行不能被当成「欠账」自动重结算并<b>补发真钱</b>"
                                + "（那是「多给」方向，不可回收）");
                assertNull(rs.getString("claim_owner"),
                        "claim_owner 补列无默认值 → NULL，语义是「持有者未知」。"
                                + "SeasonManager.rebuildPendingReleases 的 ④ 分支按年龄裁定它");
            }

            List<Integer> suspect = db.findLegacySeasonsWithClaimAndNoLedger();
            assertNotNull(suspect, "读失败不该返回 null 之外的东西给调用方当成「没问题」");
            assertEquals(List.of(1), suspect,
                    "升级前「已抢占但没有台账」的赛季必须被一次性对账扫描列出来 —— 它们没有 "
                            + "ledger_pending 标记（补列时补成 0），欠账扫描（要求标记 <> 0）"
                            + "看不见它们，若这里也看不见，那一季的奖励就<b>永久无人回访</b>。"
                            + "（刻意不自动补发：库里分不清「写失败 / 当时没开奖励 / 全档位为 0」"
                            + "三种历史，自动补会把一个刻意不发奖的赛季补出去。）");
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 起一次「服主的服务器」：桩数据目录 → 造旧库（可选）→ 构造 + 真 initialize()。 */
    private DatabaseManager boot(Path dir, String dbFile) throws Exception {
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", dbFile);
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(configManager.getMainConfig()).thenReturn(cfg);

        DatabaseManager db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：本类全部断言基于真 SQLite");
        opened.add(db);
        return db;
    }

    /**
     * 起一次服（库不存在时由它建出来）→ 用<b>独立只读连接</b>读该库的表/列集合。
     *
     * <p>读侧刻意不走 {@code DatabaseManager}（那正是被测代码）：结构与 {@code PRAGMA
     * table_info} 是引擎的事实，测试必须自己看见。
     */
    private Map<String, Set<String>> columnsOfBooted(Path dir, String dbFile) throws Exception {
        boot(dir, dbFile);
        return columnsOf(dir.resolve(dbFile));
    }

    private Connection open(Path dir, String dbFile) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:sqlite:" + dir.resolve(dbFile).toAbsolutePath());
    }

    /** 表名 → 列名集合（{@code PRAGMA table_info}）。 */
    private Map<String, Set<String>> columnsOf(Path dbFile) throws SQLException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            for (String table : tableNames(c)) {
                // 用 Set 装：本方法只回答「这一列在不在」，重列由 Idempotency 单独查。
                out.put(table, new LinkedHashSet<>(rawColumnNames(c, table)));
            }
        }
        return out;
    }

    private List<String> tableNames(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table'"
                        + " AND name NOT LIKE 'sqlite_%' ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /** 列名按 {@code PRAGMA table_info} 顺序返回（保留重复，供幂等用例查重列）。 */
    private List<String> rawColumnNames(Path dbFile, String table) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            return rawColumnNames(c, table);
        }
    }

    private List<String> rawColumnNames(Connection c, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    /** 索引名 → 所属表名。 */
    private Map<String, String> indexesOn(Path dbFile) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, tbl_name FROM sqlite_master WHERE type='index'"
                             + " AND name NOT LIKE 'sqlite_%' ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    /**
     * 从真库里读某个列的实际 <b>DDL 默认值</b>：{@code PRAGMA table_info} 的 {@code dflt_value}
     * 列（引擎给的原文，如 {@code "0"} / {@code "1"}；无默认值时为 {@code null}）。
     *
     * <p>必须读引擎而不是读 {@code schema.sql} 文本：本类要回答的问题是
     * 「<b>旧库被迁移之后</b>这一列的默认值是什么」，那是引擎里的状态，
     * 不是今天源码里写的字面量。
     */
    private String ddlOf(Path dbFile, String table, String column) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return rs.getString("dflt_value");
                }
            }
            fail("表 " + table + " 里找不到列 " + column + " —— 迁移根本没补上它");
            return null; // 不可达
        }
    }

    private static <T> Set<T> minus(Set<T> a, Set<T> b) {
        Set<T> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    // ---- 旧库构造 ----

    private void newLegacyDb(Path dir) throws SQLException {
        File f = dir.resolve(LEGACY_DB).toFile();
        if (f.isFile() && f.length() > 0) {
            return;
        }
        apply(dir, legacyDdl());
    }

    /** 旧库 + 一张<b>升级前形状</b>的 seasons 表（无 ledger_pending / claim_owner）。 */
    private void newLegacyDbWithSeasons(Path dir) throws SQLException {
        newLegacyDb(dir);
        apply(dir, "CREATE TABLE IF NOT EXISTS " + PREFIX + "seasons ("
                + " season_id INT PRIMARY KEY,"
                + " start_date DATE NOT NULL,"
                + " end_date DATE NOT NULL,"
                + " settled_at BIGINT,"
                + " top_snapshot TEXT)");
    }

    private void apply(Path dir, String ddl) throws SQLException {
        try (Connection c = open(dir, LEGACY_DB); Statement st = c.createStatement()) {
            for (String stmt : ddl.split(";")) {
                String s = stmt.trim();
                if (s.isEmpty()) {
                    continue;
                }
                st.execute(s.replace("{prefix}", PREFIX));
            }
        }
    }

    /** 一份真实的旧玩家数据：420 词 / 37 局 / 98765 分 / 17 岁 / 已隐身。 */
    private void seedLegacyPlayer(Path dir) throws SQLException {
        try (Connection c = open(dir, LEGACY_DB)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + PREFIX + "player_data (uuid, player_name, total_words_learned,"
                            + " total_sessions, total_score, current_streak, longest_streak)"
                            + " VALUES (?, ?, ?, ?, ?, 0, 9)")) {
                ps.setString(1, PLAYER.toString());
                ps.setString(2, "Alice");
                ps.setInt(3, 420);
                ps.setInt(4, 37);
                ps.setLong(5, 98765L);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + PREFIX + "player_profiles (uuid, age, show_answers,"
                            + " show_results, show_in_square) VALUES (?, 17, 0, 1, 0)")) {
                ps.setString(1, PLAYER.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + PREFIX + "player_sessions (uuid, session_date, words_count,"
                            + " correct_count, score, mode) VALUES (?, ?, 10, 8, 120, 'spelling')")) {
                ps.setString(1, PLAYER.toString());
                ps.setString(2, java.time.LocalDate.now().minusDays(1).toString());
                ps.executeUpdate();
            }
        }
    }

    /**
     * 1.0.0 时代的 DDL（手写字面量，与生产代码零耦合）。
     *
     * <p><b>形状来源</b>（两处独立史料互证，不靠推测）：
     * <ul>
     *   <li>{@code tasks/db-baseline.md} §3 —— 该只读审计产物逐表逐列记录了
     *       1.0.7-SNAPSHOT 开工时的 {@code createTables()} 形状；</li>
     *   <li>{@code CHANGELOG.md} —— 每条「新增列 / 改默认值」都点名了是哪一批加的、
     *       改之前是什么。例如 {@code player_profiles.show_answers} 的默认值在
     *       Phase 1 流 E 由 1 改为 0（D2 隐私优先）。</li>
     * </ul>
     *
     * <p>刻意<b>不含</b>此后新增的 5 个列与全部 11 张后加的表 —— 那些正是本类要检验的
     * 迁移目标。{@code show_answers} 的默认值刻意保留旧值 <b>1</b>：那一处不是「缺列」，
     * 而是「列在、默认值变了」，{@code addColumnIfMissing} 结构上修不了它
     * （见 {@code ColumnDefaultDrift}）。
     */
    private static String legacyDdl() {
        // 每条语句独立成串、最后用 ';' 拼起来（apply() 按 ';' 切分）。刻意不写成
        // 一条超长的字符串相加链：那种写法一旦漏掉一个 '+' 就直接是编译错误，
        // 而且很难一眼看出漏在哪。
        String[] drop = {
                "DROP TABLE IF EXISTS {prefix}checkin_data",
                "DROP TABLE IF EXISTS {prefix}daily_challenge_words",
                "DROP TABLE IF EXISTS {prefix}daily_challenges",
                "DROP TABLE IF EXISTS {prefix}player_achievements",
                "DROP TABLE IF EXISTS {prefix}difficult_words",
                "DROP TABLE IF EXISTS {prefix}player_profiles",
                "DROP TABLE IF EXISTS {prefix}player_sessions",
                "DROP TABLE IF EXISTS {prefix}learning_log",
                "DROP TABLE IF EXISTS {prefix}achievements",
                "DROP TABLE IF EXISTS {prefix}word_progress",
                "DROP TABLE IF EXISTS {prefix}player_data",
        };
        String[] legacyTables = {
                "CREATE TABLE {prefix}player_data ("
                        + " uuid VARCHAR(36) PRIMARY KEY,"
                        + " player_name VARCHAR(32),"
                        + " total_words_learned INT DEFAULT 0,"
                        + " total_sessions INT DEFAULT 0,"
                        + " total_score BIGINT DEFAULT 0,"
                        // 刻意不含 total_coins_earned / total_coins_spent / conversation_count /
                        // onboarding_step / wrong_book_cleared —— 5 条 addColumnIfMissing 的目标
                        + " current_streak INT DEFAULT 0,"
                        + " longest_streak INT DEFAULT 0,"
                        + " last_login BIGINT,"
                        + " created_at BIGINT,"
                        + " updated_at BIGINT)",

                "CREATE TABLE {prefix}word_progress ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " uuid VARCHAR(36) NOT NULL,"
                        + " word VARCHAR(64) NOT NULL,"
                        + " mastery DOUBLE DEFAULT 0.0,"
                        + " correct_count INT DEFAULT 0,"
                        + " incorrect_count INT DEFAULT 0,"
                        + " last_review_time BIGINT,"
                        + " review_count INT DEFAULT 0,"
                        + " UNIQUE(uuid, word))",

                "CREATE TABLE {prefix}achievements ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " uuid VARCHAR(36) NOT NULL,"
                        + " achievement_id VARCHAR(64) NOT NULL,"
                        + " unlocked_at BIGINT,"
                        + " UNIQUE(uuid, achievement_id))",

                "CREATE TABLE {prefix}learning_log ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " uuid VARCHAR(36) NOT NULL,"
                        + " session_type VARCHAR(32), difficulty VARCHAR(16),"
                        + " words_count INT, correct_count INT, score INT,"
                        + " started_at BIGINT, ended_at BIGINT)",

                "CREATE TABLE {prefix}player_sessions ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " uuid VARCHAR(36) NOT NULL,"
                        + " session_date DATE NOT NULL,"
                        + " words_count INT DEFAULT 0,"
                        + " correct_count INT DEFAULT 0,"
                        + " score BIGINT DEFAULT 0,"
                        + " mode VARCHAR(32),"
                        // 刻意不含 review_words —— addColumnIfMissing 的目标
                        + " UNIQUE(uuid, session_date))",

                "CREATE TABLE {prefix}player_profiles ("
                        + " uuid VARCHAR(36) PRIMARY KEY,"
                        + " age INT DEFAULT 0,"
                        // 旧默认值 1：Phase 1 流 E（D2 隐私优先）之前就是 1
                        + " show_answers BOOLEAN DEFAULT 1,"
                        + " show_results BOOLEAN DEFAULT 1,"
                        // 刻意不含 sounds_enabled / reminders_enabled —— 2 条 addColumnIfMissing 的目标
                        + " show_in_square BOOLEAN DEFAULT 1)",

                "CREATE TABLE {prefix}player_achievements ("
                        + " uuid VARCHAR(36) NOT NULL,"
                        + " achievement_id VARCHAR(64) NOT NULL,"
                        + " unlocked_at BIGINT NOT NULL,"
                        + " PRIMARY KEY (uuid, achievement_id))",

                "CREATE TABLE {prefix}daily_challenges ("
                        + " uuid VARCHAR(36) NOT NULL,"
                        + " challenge_date DATE NOT NULL,"
                        + " challenge_id VARCHAR(32) NOT NULL,"
                        + " progress INT DEFAULT 0,"
                        + " completed BOOLEAN DEFAULT 0,"
                        + " claimed BOOLEAN DEFAULT 0,"
                        + " PRIMARY KEY (uuid, challenge_date, challenge_id))",

                "CREATE TABLE {prefix}daily_challenge_words ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " player_uuid VARCHAR(36) NOT NULL,"
                        + " challenge_date DATE NOT NULL,"
                        + " word_id VARCHAR(64) NOT NULL,"
                        + " completed BOOLEAN DEFAULT FALSE,"
                        + " completed_at TIMESTAMP NULL,"
                        + " UNIQUE(player_uuid, challenge_date, word_id))",

                "CREATE TABLE {prefix}ai_chat_history ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " player_uuid VARCHAR(36) NOT NULL,"
                        + " session_id VARCHAR(64) NOT NULL,"
                        + " role VARCHAR(16) NOT NULL,"
                        + " content TEXT NOT NULL,"
                        + " timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",

                "CREATE TABLE {prefix}difficult_words ("
                        + " player_uuid VARCHAR(36) NOT NULL,"
                        + " word VARCHAR(100) NOT NULL,"
                        + " wrong_count INTEGER DEFAULT 0,"
                        + " added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + " PRIMARY KEY (player_uuid, word))",

                "CREATE TABLE {prefix}checkin_data ("
                        + " player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                        + " last_checkin_date DATE NOT NULL,"
                        + " streak_count INTEGER DEFAULT 1)",
        };

        StringBuilder all = new StringBuilder();
        for (String d : drop) {
            all.append(d).append(';');
        }
        for (String t : legacyTables) {
            all.append(t).append(';');
        }
        return all.toString();
    }
}
