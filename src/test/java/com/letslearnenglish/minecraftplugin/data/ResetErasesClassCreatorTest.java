package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Round 17：{@code /lea reset} 必须擦掉 {@code classes.created_by} 里的玩家 UUID。
 *
 * <p><b>缺陷</b>：{@code deletePlayerData} 的两张表清单按 {@code uuid} / {@code player_uuid}
 * 两个列名模板删表，而 {@code classes.created_by} 两套都套不上 —— 玩家建班后
 * {@code /lea reset}，其 UUID 仍原样留在 {@code classes} 行上，而 {@code reset} 的语义是
 * "把这个玩家的数据全删干净"。这是数据擦除语义的缺口。
 *
 * <p><b>产品决策：置哨兵而不是删班。</b>{@code classes} 行是共享结构，
 * {@code deleteClass} 会连带删 {@code class_members}；在 reset 里复用它，等于一个人
 * reset 让同班其他玩家集体退班（第三方数据破坏，比"残留一个 UUID"更严重）。故本次改成
 * {@code UPDATE classes SET created_by = <全零 UUID> WHERE created_by = ?}，与"控制台建班"
 * 使用同一个哨兵。管理员要删班有独立入口 {@code /lea class delete}。
 *
 * <p><b>本类的三个方向</b>（缺任一条都可能假绿）：
 * <ol>
 *   <li>正向：玩家建班 → reset → {@code created_by} 不再是该 UUID；</li>
 *   <li>反向对照「0 行匹配不算失败」：控制台建班（全零哨兵）→ reset 该玩家 →
 *       那条行必须<b>完全不受影响</b>，且 reset 仍返回 {@code true}。这条钉住的是最危险的
 *       回归形态 —— 若把"匹配 0 行"判成失败，{@code failed=true} 会整批 rollback，
 *       于是<b>从没建过班的玩家</b>（绝大多数）{@code /lea reset} 永久失效；</li>
 *   <li>回归守卫：{@code class_members} 里<b>旁观玩家</b>的行必须还在（防"改成删班"的
 *       过度修复），且其余表仍被清（防"整批事务搞挂"）。</li>
 * </ol>
 *
 * <p><b>为什么用真 SQLite 文件库</b>：{@code deletePlayerData} 的成败判据是"事务真的
 * commit 了"，这个事实只有真驱动能给；mock 出来的 Connection 无法区分"提交了"和
 * "回滚了"。写法照 {@code ResetClearsDailyCoinLedgerTest} / {@code NodeIdDerivationTest}。
 *
 * <p><b>变异验证</b>（记录在此以便复现，实测均变红）：
 * <ul>
 *   <li>删掉 {@code failed |= !eraseClassCreator(...)} 那一行 → 用例 1 红；</li>
 *   <li>把 {@code eraseClassCreator} 改成"影响 0 行即 return false" → 用例 2 红；</li>
 *   <li>把 {@code eraseClassCreator} 改成 {@code DELETE FROM classes} → 用例 3 红。</li>
 * </ul>
 */
class ResetErasesClassCreatorTest {

    @TempDir
    Path tempDir;

    private File dbFile;
    private LetsLearnEnglish plugin;
    private DatabaseManager db;

    /** 被 reset 的玩家（也是班级创建者）。 */
    private static final UUID CREATOR = UUID.fromString("00000000-0000-0000-0000-00000000dead");
    /** 同班旁观玩家 —— 其成员关系绝不能被 reset 连带删掉。 */
    private static final UUID CLASSMATE = UUID.fromString("00000000-0000-0000-0000-00000000beef");
    /** 控制台建班哨兵（与 AdminCommandHandler 的 class create 一致）。 */
    private static final String CONSOLE_SENTINEL = new UUID(0L, 0L).toString();

    private static final String CLASS_NAME = "class_one";

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ResetErasesClassCreatorTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager configManager = mock(ConfigManager.class);
        org.bukkit.configuration.file.FileConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.set("database.type", "sqlite");
        // 键名必须与 DatabaseManager:144/3898 实际读的一致，否则 initialize() 会在
        // plugin.getDataFolder() 下落一个默认 playerdata.db，而本测试的另一条 JDBC
        // 连接指向别处 —— 表现为「表不存在」，看似代码错、实为夹具错。
        cfg.set("database.sqlite.filename", "reset-classcreator.db");
        cfg.set("database.mysql.table-prefix", "lle_");
        when(configManager.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则故障注入手段无效");
        dbFile = new File(tempDir.toFile(), "reset-classcreator.db");
        assertTrue(dbFile.isFile(), "前置条件：SQLite 文件未创建，路径推导错了");

        seedLazyTables();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 建出 {@code deletePlayerData} 会去删、但 {@code createTables()} 未覆盖的表。
     *
     * <p><b>为什么必须建</b>：{@code deletePlayerData} 是「任一表失败即整批 rollback」的
     * 单事务。夹具里缺一张表 → 那条 {@code DELETE} 抛 {@code no such table} →
     * {@code failed=true} → 全部回滚 → 所有"应被删除"的断言都变成"行还在"。
     * 那不是代码错，是夹具错，而且会以最误导的方式表现（像功能没实现）。
     *
     * <p>实测确认（在真 SQLite 上跑 {@link #eraseFailureRollsBackWholeReset} 等用例）：
     * {@code createTables()} 只建 {@code classes} / {@code class_members} / {@code duel_stats} /
     * {@code social_cheers} / {@code social_feed}，<b>不建</b> {@code daily_coin_counters} /
     * {@code ai_round_counters} / {@code checkin_data} / {@code daily_word_reward_claims}，
     * 故这些必须补。{@code social_feed} 由 {@code createTables()} 建出（带
     * {@code created_at NOT NULL}），<b>不能</b>在这里重复建一份简化版 —— 那样
     * {@code CREATE TABLE IF NOT EXISTS} 不会生效，而 INSERT 会撞上真表的 NOT NULL 约束。
     */
    private void seedLazyTables() throws Exception {
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
    }

    /** 用一条独立 JDBC 连接直接查表 —— 不经 DatabaseManager 的缓存层。 */
    private long countRows(String table, String whereCol, String whereVal) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table
                     + " WHERE " + whereCol + " = '" + whereVal + "'")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    /** 单值查询（取不到返回 null）。 */
    private String queryString(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /** 建一个班并把创建者写进 {@code created_by}（走生产写入点，不手写 INSERT）。 */
    private int createClassOwnedBy(UUID creator) {
        assertTrue(db.createClass(CLASS_NAME, creator, 1_700_000_000_000L),
                "前置失败：建班没成功，后续断言全部无意义");
        return currentClassId();
    }

    /** 当前班级行（null = 班级行已不存在）。 */
    private DatabaseManager.ClassRow currentClass() {
        return db.getClassByName(CLASS_NAME);
    }

    private int currentClassId() {
        DatabaseManager.ClassRow row = currentClass();
        assertTrue(row != null, "前置失败：按班名查不到班级行");
        return row.classId();
    }

    /** 本人 profile 行 —— 作为「删表真的执行了」的反向对照见证者。 */
    private void seedProfile(UUID id) throws Exception {
        exec("INSERT OR REPLACE INTO lle_player_profiles (uuid, age, show_answers,"
                + " show_results, show_in_square, sounds_enabled, reminders_enabled)"
                + " VALUES ('" + id + "', 0, 0, 0, 0, 1, 1)");
    }

    private String createdByValue() throws Exception {
        return queryString("SELECT created_by FROM lle_classes WHERE class_name = '" + CLASS_NAME + "'");
    }

    // ==================================================================
    // A. 正向：玩家建班 → reset → created_by 被擦
    // ==================================================================

    @Nested
    @DisplayName("A. reset 擦掉 classes.created_by 上的玩家 UUID")
    class CreatorIsErased {

        @Test
        @DisplayName("玩家建班 → reset → created_by 不再是该玩家 UUID，且整批返回 true")
        void resetErasesCreatorUuid() throws Exception {
            int classId = createClassOwnedBy(CREATOR);
            seedProfile(CREATOR);
            assertTrue(db.addClassMember(classId, CREATOR, 1_700_000_000_001L),
                    "前置失败：创建者入班没成功");

            // 前置断言：不先证明"UUID 确实写进去了"，下面的"不再是"可能是「压根没写」的假绿。
            assertEquals(CREATOR.toString(), createdByValue(),
                    "前置失败：createClass 没把创建者 UUID 写进 created_by");
            assertEquals(1, countRows("lle_player_profiles", "uuid", CREATOR.toString()),
                    "前置失败：对照行没进库，反向对照会退化成恒真");

            assertTrue(db.deletePlayerData(CREATOR),
                    "reset 返回 false —— 若擦除语句把匹配行数当成败，这里会误报失败");

            assertNotEquals(CREATOR.toString(), createdByValue(),
                    "reset 后 created_by 仍是该玩家 UUID —— /lea reset 的数据擦除语义有缺口："
                            + "玩家的标识符继续留在 classes 行上");
            assertEquals(CONSOLE_SENTINEL, createdByValue(),
                    "created_by 应被置成全零哨兵（与「控制台建班」同一个值），"
                            + "让「没有真实创建者」在库里只有一种表示");

            // [CONTROL] 证明删表动作真的跑到，排除「方法没执行」的假绿
            assertEquals(0, countRows("lle_player_profiles", "uuid", CREATOR.toString()),
                    "[CONTROL] player_profiles 未被清空 —— 说明 deletePlayerData 整体没生效，"
                            + "上面的 assertNotEquals 是假绿");
        }

        @Test
        @DisplayName("班级行本身必须还在（置哨兵 ≠ 删班），class_name / class_id 不变")
        void classRowSurvives() throws Exception {
            int classId = createClassOwnedBy(CREATOR);
            seedProfile(CREATOR);

            assertTrue(db.deletePlayerData(CREATOR), "前置：reset 事务应成功");

            assertEquals(1, countRows("lle_classes", "class_name", CLASS_NAME),
                    "班级行被删了 —— 处置方式应为「置哨兵」，删班会连带毁掉同班其他人的成员关系");
            // class_id 不变是"没被删后重建"的证据（重建会换自增 id）
            DatabaseManager.ClassRow stillThere = currentClass();
            assertTrue(stillThere != null, "reset 后按班名查不到班级 —— 班级行已消失");
            assertEquals(classId, stillThere.classId(),
                    "class_id 变了 —— 说明班级行是先删后建的（那等于删班）");
        }

        @Test
        @DisplayName("只擦本人：另一个玩家建的班 created_by 不受影响")
        void otherCreatorsUntouched() throws Exception {
            // 两个班，分别由两个不同玩家创建
            assertTrue(db.createClass("class_a", CREATOR, 1_700_000_000_000L), "前置：建班 A 失败");
            assertTrue(db.createClass("class_b", CLASSMATE, 1_700_000_000_000L), "前置：建班 B 失败");
            seedProfile(CREATOR);

            assertTrue(db.deletePlayerData(CREATOR), "前置：reset 事务应成功");

            assertEquals(CONSOLE_SENTINEL,
                    queryString("SELECT created_by FROM lle_classes WHERE class_name = 'class_a'"),
                    "目标玩家创建的班未被擦");
            assertEquals(CLASSMATE.toString(),
                    queryString("SELECT created_by FROM lle_classes WHERE class_name = 'class_b'"),
                    "旁观玩家建的班被误擦 —— 修复变成了「清全库的 created_by」");
        }
    }

    // ==================================================================
    // B. 0 行匹配 ≠ 失败（最危险的回归形态）
    // ==================================================================

    @Nested
    @DisplayName("B. 0 行匹配不算失败：没建过班的玩家 reset 必须照常成功")
    class ZeroMatchIsNotFailure {

        @Test
        @DisplayName("控制台建的班（全零哨兵）→ reset 该玩家 → 整行不受影响，且返回 true")
        void consoleClassUntouchedAndResetSucceeds() throws Exception {
            // 控制台建班：created_by = 全零哨兵（AdminCommandHandler:930 的同一写法）
            int classId = createClassOwnedBy(new UUID(0L, 0L));
            seedProfile(CREATOR);
            // 该玩家只是这个班的普通成员，不是创建者
            assertTrue(db.addClassMember(classId, CREATOR, 1_700_000_000_001L), "前置：入班失败");
            assertTrue(db.addClassMember(classId, CLASSMATE, 1_700_000_000_002L), "前置：旁观入班失败");

            assertEquals(CONSOLE_SENTINEL, createdByValue(),
                    "前置失败：控制台建班的哨兵没写对，本用例前提不成立");
            assertEquals(1, countRows("lle_player_profiles", "uuid", CREATOR.toString()),
                    "前置失败：对照行没进库，下面的 0 可能是「压根没插进去」的假绿");

            boolean committed = db.deletePlayerData(CREATOR);

            // 这是本用例的核心：擦除语句匹配 0 行（此玩家没建过班），绝不能被判成失败。
            assertTrue(committed,
                    "擦除语句匹配 0 行被当成失败 → 整批 rollback。后果：**从没建过班的玩家** "
                            + "（绝大多数）执行 /lea reset 会永久失败，管理员收到「重置失败」");
            assertEquals(0, countRows("lle_player_profiles", "uuid", CREATOR.toString()),
                    "返回了 true 但数据没删 —— 0 行匹配被吞进了失败分支（部分删除/静默失败）");

            // 那条控制台建的班必须原封不动
            assertEquals(CONSOLE_SENTINEL, createdByValue(),
                    "控制台建的班被擦成了哨兵 —— 本来就是这个值，说明语句错误地命中了它");
            assertEquals(1, countRows("lle_classes", "class_name", CLASS_NAME),
                    "控制台建的班被删了 —— 那会连带清掉全班成员关系");
        }
    }

    // ==================================================================
    // C. 反向守卫：不得误伤第三人 / 不得把整批事务搞挂
    // ==================================================================

    @Nested
    @DisplayName("C. 反向守卫：不误伤同班旁人、其余表仍被清")
    class NoCollateralDamage {

        @Test
        @DisplayName("创建者 reset 后：class_members 里创建者行被删，旁观成员行必须还在")
        void classmateMembershipSurvives() throws Exception {
            int classId = createClassOwnedBy(CREATOR);
            seedProfile(CREATOR);
            assertTrue(db.addClassMember(classId, CREATOR, 1_700_000_000_001L), "前置：创建者入班失败");
            assertTrue(db.addClassMember(classId, CLASSMATE, 1_700_000_000_002L), "前置：旁观入班失败");
            assertEquals(1, countRows("lle_class_members", "uuid", CREATOR.toString()), "前置：创建者成员行没进库");
            assertEquals(1, countRows("lle_class_members", "uuid", CLASSMATE.toString()), "前置：旁观成员行没进库");

            assertTrue(db.deletePlayerData(CREATOR), "前置：reset 事务应成功");

            assertEquals(0, countRows("lle_class_members", "uuid", CREATOR.toString()),
                    "回归：class_members 里本玩家的行未被清 —— 本次改动破坏了既有清理");
            assertEquals(1, countRows("lle_class_members", "uuid", CLASSMATE.toString()),
                    "旁观玩家的成员关系被删了 —— 这正是「删班」方案的代价：一个人 reset "
                            + "让全班其他人退班。本用例就是阻止把处置方式改成删班的守卫");
            assertEquals(1, countRows("lle_classes", "class_name", CLASS_NAME),
                    "班级行被删 —— 同「删班」过度修复");
        }

        /**
         * 最重要的一条：擦除语句加入事务后，其余表删除仍必须全部生效。
         * 最小改动最容易把「事务的成败判据」弄错（比如用错 del 的语义），
         * 表现为整批静默回滚。
         */
        @Test
        @DisplayName("回归守卫：擦除语句入事务后，其余 22 表删除仍全部生效")
        void restOfResetStillWorks() throws Exception {
            int classId = createClassOwnedBy(CREATOR);
            seedProfile(CREATOR);
            assertTrue(db.addClassMember(classId, CREATOR, 1_700_000_000_001L), "前置：入班失败");

            // 每日台账（懒建表）各 seed 一条 —— 它们都靠 player_uuid 模板删
            exec("INSERT INTO lle_daily_coin_counters (player_uuid, coin_date, cents) VALUES ('"
                    + CREATOR + "', '2026-11-03', 40000)");
            exec("INSERT INTO lle_ai_round_counters (player_uuid, round_date, rounds) VALUES ('"
                    + CREATOR + "', '2026-11-03', 7)");
            exec("INSERT INTO lle_checkin_data (player_uuid, last_checkin_date, streak_count) VALUES ('"
                    + CREATOR + "', '2026-11-03', 3)");
            // uuid 系表：player_data（排在最前的一张）
            exec("INSERT OR REPLACE INTO lle_player_data (uuid, player_name) VALUES ('"
                    + CREATOR + "', 'Zombie')");
            // 显式 DELETE 语句覆盖的第四套列名表
            // social_feed 由真实 createTables() 建出（created_at / updated_at NOT NULL），
            // 故必须补齐这两列 —— 少给会撞 NOT NULL 约束（那是夹具错，不是代码错）。
            exec("INSERT INTO lle_social_feed (player_uuid, feed_date, event_type, created_at, updated_at)"
                    + " VALUES ('" + CREATOR + "', '2026-11-03', 'CHECKIN', 1, 1)");
            // duel_stats 同样由真实 createTables() 建出（duel_date / created_at NOT NULL）
            exec("INSERT INTO lle_duel_stats (winner_uuid, loser_uuid, winner_score, loser_score,"
                    + " duel_date, created_at)"
                    + " VALUES ('" + CREATOR + "', '" + CLASSMATE + "', 10, 3, '2026-11-03', 1)");

            // 前置断言：全部确实进库了（否则下面的 0 是假绿）
            assertEquals(1, countRows("lle_daily_coin_counters", "player_uuid", CREATOR.toString()), "前置：金币台账没进库");
            assertEquals(1, countRows("lle_ai_round_counters", "player_uuid", CREATOR.toString()), "前置：AI 轮次没进库");
            assertEquals(1, countRows("lle_checkin_data", "player_uuid", CREATOR.toString()), "前置：签到没进库");
            assertEquals(1, countRows("lle_player_data", "uuid", CREATOR.toString()), "前置：player_data 没进库");
            assertEquals(1, countRows("lle_social_feed", "player_uuid", CREATOR.toString()), "前置：feed 没进库");
            assertEquals(1, countRows("lle_duel_stats", "winner_uuid", CREATOR.toString()), "前置：duel 没进库");

            assertTrue(db.deletePlayerData(CREATOR), "前置：reset 事务应成功");

            assertEquals(0, countRows("lle_daily_coin_counters", "player_uuid", CREATOR.toString()),
                    "金币台账未被清 —— 擦除语句把整批事务搞挂了");
            assertEquals(0, countRows("lle_ai_round_counters", "player_uuid", CREATOR.toString()),
                    "AI 轮次未被清 —— 擦除语句把整批事务搞挂了");
            assertEquals(0, countRows("lle_checkin_data", "player_uuid", CREATOR.toString()),
                    "签到未被清 —— 擦除语句把整批事务搞挂了");
            assertEquals(0, countRows("lle_player_data", "uuid", CREATOR.toString()),
                    "player_data 未被清 —— 擦除语句把整批事务搞挂了");
            assertEquals(0, countRows("lle_social_feed", "player_uuid", CREATOR.toString()),
                    "social_feed 未被清 —— 擦除语句把整批事务搞挂了");
            assertEquals(0, countRows("lle_duel_stats", "winner_uuid", CREATOR.toString()),
                    "duel_stats 未被清 —— 擦除语句把整批事务搞挂了");
        }
    }

    // ==================================================================
    // D. 事务原子性：擦除语句失败必须整批回滚（不是 auto-commit）
    // ==================================================================

    @Nested
    @DisplayName("D. 原子性：擦除语句在同一个事务内")
    class TransactionAtomicity {

        /**
         * 故障注入手段：把 {@code classes} 表删掉，让新增的那条 UPDATE 抛
         * {@code no such table}。
         *
         * <p><b>能证明什么、不能证明什么</b>：它钉住"擦除语句失败 ⇒ 整批回滚"
         * （即 {@code failed} 标记确实接上了），但不能单独区分"在事务内"与
         * "auto-commit 但先执行"—— 后者在这种情况下数据同样还在。真正的"在事务内"
         * 证据是 {@link #resetClearsBeforeEraseIsRolledBackWithDeletes}：
         * 用一个<b>已提交的</b>前置 UPDATE 证明擦除语句确实执行到过、再让后面的一张表
         * 失败，此时擦除的效果必须<b>消失</b>（autocommit 下它会永久留下）。
         */
        @Test
        @DisplayName("擦除语句失败 → 整批回滚，一行都没删")
        void eraseFailureRollsBackWholeReset() throws Exception {
            int classId = createClassOwnedBy(CREATOR);
            seedProfile(CREATOR);
            assertTrue(db.addClassMember(classId, CREATOR, 1_700_000_000_001L), "前置：入班失败");
            exec("INSERT OR REPLACE INTO lle_player_data (uuid, player_name) VALUES ('"
                    + CREATOR + "', 'Zombie')");
            assertEquals(1, countRows("lle_player_profiles", "uuid", CREATOR.toString()),
                    "前置失败：见证行没进库，回滚断言会退化成恒真");

            // 注入故障：擦除语句的目标表消失 → UPDATE 抛 no such table
            exec("DROP TABLE lle_classes");

            assertFalse(db.deletePlayerData(CREATOR),
                    "擦除语句失败却返回 true —— 调用方会向管理员报「已重置」，"
                            + "而实际一条数据都没删（隐私删除语义下这是最重的一类谎报）");
            assertEquals(1, countRows("lle_player_profiles", "uuid", CREATOR.toString()),
                    "返回 false 但 player_profiles 被删了 —— false 的含义必须是"
                            + "「整批回滚、一条都没删」，部分删除比谎报成功更糟");
            assertEquals(1, countRows("lle_player_data", "uuid", CREATOR.toString()),
                    "返回 false 但 player_data 被删了 —— 整批回滚没有生效");
        }

        /**
         * 证明"擦除与其余删除同生共死"——即擦除不是跑在 auto-commit 上。
         *
         * <p><b>为什么单靠 D1 不够</b>：D1 让擦除语句<b>自己</b>失败，数据在两种写法下
         * 都还在（auto-commit 的 UPDATE 抛错也不会落盘），故 D1 无法区分
         * "在事务内"与"auto-commit"。真正的区分点是：<b>擦除成功执行之后</b>，
         * 若同批的其他删除失败，擦除的效果必须一起消失。
         *
         * <p>本用例的注入点是 {@code player_profiles} —— 它是 uuid 系，
         * {@code del()} 不做缺表容错，故那张表的 DELETE 会抛 {@code no such table}。
         * 正确实现下擦除语句排在它之后，因此<b>根本不会被执行</b>，{@code created_by}
         * 保持原值。若有人把擦除改到方法开头并独立提交（这是"最小改动"最容易被改成
         * 的样子），它就会在故障发生前永久落盘 —— 本用例会看到"整批回滚了、
         * 但 created_by 已被改掉"这个残缺局面而变红。
         */
        @Test
        @DisplayName("同批删除失败 → 擦除效果一起回滚，created_by 保持原值")
        void resetClearsBeforeEraseIsRolledBackWithDeletes() throws Exception {
            int classId = createClassOwnedBy(CREATOR);
            exec("INSERT OR REPLACE INTO lle_player_data (uuid, player_name) VALUES ('"
                    + CREATOR + "', 'Zombie')");
            assertTrue(db.addClassMember(classId, CREATOR, 1_700_000_000_001L), "前置：入班失败");
            assertEquals(CREATOR.toString(), createdByValue(), "前置：created_by 没写对");

            // 注入故障：删掉一张 uuid 系表（del() 不做缺表容错）→ 那条 DELETE 抛错
            exec("DROP TABLE lle_player_profiles");

            assertFalse(db.deletePlayerData(CREATOR), "前置：注入故障后应返回 false");
            assertEquals(CREATOR.toString(), createdByValue(),
                    "整批回滚了，但 created_by 已被改掉 —— 说明擦除语句跑在自己的连接/"
                            + "auto-commit 上，与其余删除不在同一事务里");
            assertEquals(1, countRows("lle_player_data", "uuid", CREATOR.toString()),
                    "相邻见证者：player_data 的行必须还在（证明整批真的回滚了）");
        }
    }
}
