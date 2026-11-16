package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.manager.AdminCommandHandler;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-18：{@code deleteClass} / {@code createClass} 的「两态折叠 + 无事务」双缺陷。
 *
 * <h2>缺陷 A —— 三条失败折叠成一句「找不到班级」</h2>
 * <p>改造前 {@code DatabaseManager.deleteClass} 返回 {@code boolean}，而 {@code false}
 * 同时表示<b>三件</b>处置完全不同的事：
 * <ol>
 *   <li>班不存在（管理员打错名字：<b>不该</b>查日志）；</li>
 *   <li>删 {@code class_members} 的 SQL 抛了（数据库故障：<b>必须</b>查日志）；</li>
 *   <li>删 {@code classes} 的 SQL 抛了（同上，且改造前成员行已被永久删除）。</li>
 * </ol>
 * 调用点 {@code AdminCommandHandler:950-956} 把三者统一提示成 {@code class.not-found}
 * （"找不到班级"）。数据库故障时管理员会去核对班名 —— <b>方向完全错的谎报</b>，
 * 而真正的现场在那条没人会打开的 WARNING 里。
 *
 * <h2>缺陷 B —— 两条 DELETE 不在事务里</h2>
 * <p>两条语句各自 auto-commit。第二条失败时，成员行<b>已被永久删除</b>、班级行还在 ——
 * 一个空班，回复却是"找不到班级"。同文件的 {@code deletePlayerData} 早就是显式
 * {@code setAutoCommit(false)} + 整批回滚，本方法当时没跟上。
 *
 * <h2>为什么必须用真 SQLite 文件库</h2>
 * <p>"事务真的回滚了"这个事实只有真驱动能给：mock 出来的 Connection 无法区分
 * "提交了"和"回滚了"，也<b>无法</b>表现"A 语句已执行、B 语句失败后 A 被撤销"这条原子性。
 * 写法照 {@code ResetErasesClassCreatorTest} / {@code ResetClearsDailyCoinLedgerTest}。
 *
 * <h2>故障注入手段：真 DDL 触发器（不是 mock）</h2>
 * <p>用 {@code CREATE TRIGGER ... RAISE(ABORT, ...)} 让<b>指定那条</b> DELETE/INSERT 在
 * 真 SQLite 上抛 {@code SQLException}。这比 mock 掉 {@code getConnection} 强得多：
 * 后者会让第一条 DELETE 也失败，于是"第二条失败后成员行还在（回滚生效）"这条
 * <b>最关键的断言永远无法成立</b>（成员行压根没被删过，断言恒真 = 假绿）。
 * 触发器让第一条成功、第二条失败，才真正检验回滚。
 *
 * <h2>四个方向（缺任一条都可能假绿）</h2>
 * <ol>
 *   <li>正常删除 → {@code DELETED}，成员行与班级行都没了；</li>
 *   <li>班不存在 → {@code NOT_FOUND}，不抛异常、不改动任何数据；</li>
 *   <li>SQL 失败 → {@code FAILED}（三种故障注入各一条：删成员失败、删班级失败、
 *       建班 INSERT 失败）；</li>
 *   <li>事务原子性 → 第二条 DELETE 失败时<b>成员行必须还在</b>；</li>
 *   <li>调用侧 → 三种结果给出<b>三条不同</b>的提示键（Mockito 捕获 {@code sendMessage}）。</li>
 * </ol>
 *
 * <h2>变异验证（实测记录，红/绿输出见交付报告）</h2>
 * <ul>
 *   <li>去掉事务（回到两条各自 auto-commit）→ 用例 4 变红；</li>
 *   <li>把三态折回 boolean（{@code FAILED}/{@code NOT_FOUND} 都返回 false 并提示
 *       {@code class.not-found}）→ 用例 3 与 5 变红；</li>
 *   <li>删掉新 i18n 键 {@code class.delete-failed} → 用例 6（zh/en 对称 + 存在性）变红。</li>
 * </ul>
 */
class ClassDeleteTriStateTest {

    @TempDir
    Path tempDir;

    private File dbFile;
    private LetsLearnEnglish plugin;
    private DatabaseManager db;

    private static final String CLASS_NAME = "class_del";

    private static final UUID MEMBER =
            UUID.fromString("00000000-0000-0000-0000-00000000c1a5");

    private static final long NOW = 1_700_000_000_000L;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ClassDeleteTriStateTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        // 键名必须与 DatabaseManager 实际读的一致，否则 initialize() 会在别处落库，
        // 而本测试的另一条 JDBC 连接指向空库 —— 表现为"表不存在"（夹具错，非代码错）。
        cfg.set("database.sqlite.filename", "class-del.db");
        cfg.set("database.mysql.table-prefix", "lle_");
        when(configManager.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则故障注入手段无效");
        dbFile = new File(tempDir.toFile(), "class-del.db");
        assertTrue(dbFile.isFile(), "前置条件：SQLite 文件未创建，路径推导错了");
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

    /** 用一条独立 JDBC 连接执行 DDL/DML —— 不经 DatabaseManager，避免污染被测事务。 */
    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private long countRows(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    private long classRows() throws Exception {
        return countRows("SELECT COUNT(*) FROM lle_classes WHERE class_name = '" + CLASS_NAME + "'");
    }

    private long memberRows() throws Exception {
        return countRows("SELECT COUNT(*) FROM lle_class_members WHERE uuid = '" + MEMBER + "'");
    }

    /** 建一个带 1 名成员的班（走生产写入点，不手写 INSERT）。 */
    private void seedClassWithMember() {
        assertTrue(db.createClass(CLASS_NAME, MEMBER, NOW),
                "前置失败：建班没成功，后续断言全部无意义");
        DatabaseManager.ClassRow row = db.getClassByName(CLASS_NAME);
        assertNotNull(row, "前置失败：按班名查不到班级行");
        assertTrue(db.addClassMember(row.classId(), MEMBER, NOW + 1),
                "前置失败：加成员没成功");
    }

    /**
     * 让<b>某张表的某类语句</b>在真 SQLite 上抛 {@link java.sql.SQLException}。
     *
     * <p>{@code RAISE(ABORT, ...)} 会以 {@code SQLITE_CONSTRAINT} 抛出，驱动如实
     * 转成 {@code SQLException} —— 与"表写坏 / 磁盘满 / 锁超时"在调用方看来是同一类
     * 事实（SQL 没执行成功），因此它对 {@code catch (SQLException)} 的检验是真实的。
     *
     * <p>刻意不用 mock：mock 会让<b>前后两条</b>语句一起失败，于是"第二条失败后
     * 第一条被回滚"这条断言永远无法区分"回滚生效"与"第一条压根没执行"。
     */
    private void installAbortTrigger(String table, String event) throws Exception {
        exec("CREATE TRIGGER boom_" + event.toLowerCase(java.util.Locale.ROOT) + "_"
                + table + " BEFORE " + event + " ON " + table
                + " BEGIN SELECT RAISE(ABORT, 'injected failure for test'); END");
    }

    private void dropTrigger(String table, String event) throws Exception {
        exec("DROP TRIGGER IF EXISTS boom_" + event.toLowerCase(java.util.Locale.ROOT) + "_" + table);
    }

    // ==================================================================
    // A. 正常删除
    // ==================================================================

    @Nested
    @DisplayName("A. 正常删除 → DELETED，两类行都没了")
    class HappyPath {

        @Test
        @DisplayName("有成员的班：DELETED，且班级行与成员行都被删除")
        void deleteWithMembersRemovesBoth() throws Exception {
            seedClassWithMember();
            assertEquals(1, classRows(), "前置：班级行应为 1");
            assertEquals(1, memberRows(), "前置：成员行应为 1");

            DatabaseManager.ClassDeleteResult result = db.deleteClassDetailed(CLASS_NAME);

            assertEquals(DatabaseManager.ClassDeleteResult.DELETED, result,
                    "正常删除必须是 DELETED —— 若这里是 NOT_FOUND，说明三态判定把成功"
                            + "折叠掉了（管理员看到「找不到班级」而班其实删掉了）");
            assertEquals(0, classRows(), "班级行必须真的被删掉");
            assertEquals(0, memberRows(), "成员行必须真的被删掉（无外键，靠本方法连带删）");
        }

        @Test
        @DisplayName("空班（无成员）：仍是 DELETED —— 成员行 0 行不是失败")
        void emptyClassIsStillDeleted() throws Exception {
            assertTrue(db.createClass(CLASS_NAME, MEMBER, NOW), "前置：建班失败");

            assertEquals(DatabaseManager.ClassDeleteResult.DELETED,
                    db.deleteClassDetailed(CLASS_NAME),
                    "空班的成员行影响 0 行是正常结果，不得判成失败");
            assertEquals(0, classRows(), "班级行必须被删掉");
        }

        @Test
        @DisplayName("boolean 退化视图：删除成功 → true（既有调用方语义不变）")
        void legacyBooleanViewStillWorks() throws Exception {
            seedClassWithMember();
            assertTrue(db.deleteClass(CLASS_NAME),
                    "退化视图 true 必须等价于 DELETED —— 否则既有测试/调用方语义被悄悄改了");
        }
    }

    // ==================================================================
    // B. 班不存在 → NOT_FOUND
    // ==================================================================

    @Nested
    @DisplayName("B. 班不存在 → NOT_FOUND（不抛、不改数据）")
    class NotFound {

        @Test
        @DisplayName("库里没有这个班：NOT_FOUND，且不抛异常")
        void missingClassIsNotFound() {
            assertEquals(0, db.listClasses().size(), "前置：库里应无班级");

            assertEquals(DatabaseManager.ClassDeleteResult.NOT_FOUND,
                    db.deleteClassDetailed("no_such_class"),
                    "班不存在必须是 NOT_FOUND（正常结局），不得是 FAILED");
        }

        @Test
        @DisplayName("删过一次再删：第二次 NOT_FOUND（幂等，不是故障）")
        void secondDeleteIsNotFound() throws Exception {
            seedClassWithMember();
            assertEquals(DatabaseManager.ClassDeleteResult.DELETED,
                    db.deleteClassDetailed(CLASS_NAME), "前置：第一次删除应成功");

            assertEquals(DatabaseManager.ClassDeleteResult.NOT_FOUND,
                    db.deleteClassDetailed(CLASS_NAME),
                    "重复删除必须是 NOT_FOUND 而不是 FAILED —— 否则管理员会以为数据库坏了");
        }

        @Test
        @DisplayName("boolean 退化视图：班不存在 → false（与改造前一致）")
        void legacyBooleanViewReturnsFalse() {
            assertFalse(db.deleteClass("no_such_class"),
                    "退化视图的 false 同时覆盖 NOT_FOUND 与 FAILED，这是它被保留的原因，"
                            + "也是它不能用于分支的原因");
        }
    }

    // ==================================================================
    // C. SQL 失败 → FAILED
    // ==================================================================

    @Nested
    @DisplayName("C. SQL 失败 → FAILED（不得说成「找不到班级」）")
    class SqlFailure {

        @Test
        @DisplayName("删成员行失败：FAILED，且班级行未被删除")
        void memberDeleteFailureIsFailed() throws Exception {
            seedClassWithMember();
            installAbortTrigger("lle_class_members", "DELETE");

            DatabaseManager.ClassDeleteResult result = db.deleteClassDetailed(CLASS_NAME);

            assertEquals(DatabaseManager.ClassDeleteResult.FAILED, result,
                    "SQL 抛异常必须是 FAILED。若这里是 NOT_FOUND，管理员会被指向「班名打错了」，"
                            + "而真正的现场在控制台日志里");
            assertEquals(1, classRows(), "第一条语句就失败了，班级行不应被改动");
            assertEquals(1, memberRows(), "回滚生效，成员行必须还在");
        }

        @Test
        @DisplayName("删班级行失败：FAILED（改造前这里会静默留下空班）")
        void classDeleteFailureIsFailed() throws Exception {
            seedClassWithMember();
            installAbortTrigger("lle_classes", "DELETE");

            DatabaseManager.ClassDeleteResult result = db.deleteClassDetailed(CLASS_NAME);

            assertEquals(DatabaseManager.ClassDeleteResult.FAILED, result,
                    "第二条 DELETE 失败也必须是 FAILED，不得折叠成 NOT_FOUND");
        }

        @Test
        @DisplayName("建班 INSERT 失败：createClassDetailed 返回 FAILED，不是 ALREADY_EXISTS")
        void createInsertFailureIsFailedNotDuplicate() throws Exception {
            // BEFORE INSERT 触发器让 INSERT 抛异常；此时库里<b>没有</b>同名行，
            // 故失败后的回查应当看不到行 → FAILED。而改造前这里一律报"已存在同名班级"。
            installAbortTrigger("lle_classes", "INSERT");

            DatabaseManager.ClassCreateResult result =
                    db.createClassDetailed(CLASS_NAME, MEMBER, NOW);

            assertEquals(DatabaseManager.ClassCreateResult.FAILED, result,
                    "INSERT 抛了且回查看不到同名行 → FAILED。"
                            + "折叠成 ALREADY_EXISTS 会让管理员去找一个根本不存在的同名班");
            assertEquals(0, classRows(), "INSERT 失败，不应留下任何班级行");
        }

        @Test
        @DisplayName("建班重名：ALREADY_EXISTS（正常幂等拒绝，不是失败）")
        void createDuplicateIsAlreadyExists() {
            assertEquals(DatabaseManager.ClassCreateResult.CREATED,
                    db.createClassDetailed(CLASS_NAME, MEMBER, NOW), "前置：首次建班应成功");

            assertEquals(DatabaseManager.ClassCreateResult.ALREADY_EXISTS,
                    db.createClassDetailed(CLASS_NAME, MEMBER, NOW + 1),
                    "重名是正常结局，不得报 FAILED —— 否则管理员会去查一个不存在的数据库故障");
        }

        @Test
        @DisplayName("建班成功：CREATED，且 boolean 退化视图为 true")
        void createSuccessIsCreated() {
            assertEquals(DatabaseManager.ClassCreateResult.CREATED,
                    db.createClassDetailed(CLASS_NAME, MEMBER, NOW), "首次建班必须是 CREATED");
            assertTrue(db.createClass("another_class", MEMBER, NOW),
                    "boolean 退化视图 true 必须等价于 CREATED");
        }
    }

    // ==================================================================
    // D. 事务原子性（本类最重要的一条）
    // ==================================================================

    @Nested
    @DisplayName("D. 事务原子性：第二条 DELETE 失败 → 成员行必须还在")
    class TransactionAtomicity {

        /**
         * 这条是整个 R-18 的核心：改造前两条 DELETE 各自 auto-commit，第一条（删成员）
         * 成功后第二条（删班级）失败，成员行<b>已被永久删除</b>、班级行还在 ——
         * 一个空班，而回复是"找不到班级"。
         *
         * <p>本用例若在"去掉事务"的变异下变红，说明它真的在测事务，而不是在测别的什么。
         */
        @Test
        @DisplayName("删班级行失败 → 成员行被回滚回来（不是永久删除）")
        void memberRowsSurviveWhenClassDeleteFails() throws Exception {
            seedClassWithMember();
            assertEquals(1, memberRows(), "前置：成员行应为 1");

            installAbortTrigger("lle_classes", "DELETE");
            assertEquals(DatabaseManager.ClassDeleteResult.FAILED,
                    db.deleteClassDetailed(CLASS_NAME), "前置：本用例要求走到失败分支");

            assertEquals(1, memberRows(),
                    "成员行必须仍在库里 —— 两条 DELETE 现在在同一个事务里，"
                            + "第二条失败即整批 rollback。若这里是 0，说明事务没生效："
                            + "成员被永久删除、班级行还在，留下一个人造空班");
            assertEquals(1, classRows(),
                    "班级行也必须仍在（这才是「什么都没变」）");
        }

        /**
         * 事务收尾必须恢复 {@code autoCommit} —— 否则该连接此后<b>所有</b>语句都落进一个
         * 永不提交的隐式事务里，表现为"插件从此写不进任何数据、且没有任何报错"。
         * 这是 {@code deletePlayerData} 的 {@code finally} 里那层嵌套 try 的由来，
         * 本方法照抄后必须同样被钉住。
         */
        @Test
        @DisplayName("失败后 autoCommit 已恢复：紧接着的建班能正常落库并被别处读到")
        void autoCommitIsRestoredAfterFailure() throws Exception {
            seedClassWithMember();
            installAbortTrigger("lle_classes", "DELETE");
            assertEquals(DatabaseManager.ClassDeleteResult.FAILED,
                    db.deleteClassDetailed(CLASS_NAME), "前置：需要一次失败");
            dropTrigger("lle_classes", "DELETE");

            // 同一个连接、同一个 DatabaseManager：若 autoCommit 没恢复，这次 INSERT
            // 会落在一个永不提交的隐式事务里，下面那条独立 JDBC 连接读不到它。
            assertEquals(DatabaseManager.ClassCreateResult.CREATED,
                    db.createClassDetailed("after_failure", MEMBER, NOW + 2),
                    "失败后建班应正常成功");

            assertEquals(1, countRows(
                            "SELECT COUNT(*) FROM lle_classes WHERE class_name = 'after_failure'"),
                    "失败事务收尾后必须恢复 autoCommit，否则此后所有写入都落进"
                            + "一个永不提交的隐式事务（症状：插件从此写不进数据，且无报错）");
        }

        @Test
        @DisplayName("成功路径同样恢复 autoCommit：删除后建的新班能被独立连接读到")
        void autoCommitIsRestoredAfterSuccess() throws Exception {
            seedClassWithMember();
            assertEquals(DatabaseManager.ClassDeleteResult.DELETED,
                    db.deleteClassDetailed(CLASS_NAME), "前置：删除应成功");

            assertEquals(DatabaseManager.ClassCreateResult.CREATED,
                    db.createClassDetailed("after_success", MEMBER, NOW + 3),
                    "成功路径也恢复了 autoCommit，紧接着的建班应能提交");
            assertEquals(1, countRows(
                            "SELECT COUNT(*) FROM lle_classes WHERE class_name = 'after_success'"),
                    "成功路径的 autoCommit 同样必须恢复");
        }
    }

    // ==================================================================
    // E. 调用侧：三种结果 → 三条不同提示
    // ==================================================================

    /**
     * {@code AdminCommandHandler} 的消息分支。
     *
     * <p>用 {@link MessageUtil} 的 mock 把 i18n key <b>原样吐出来</b>（{@code <key>}）：
     * 断言直接盯 key 的选择，不受 zh/en 文案将来改动影响。若把 handler 改回
     * {@code if (deleteClass(...)) ... else not-found}，{@code FAILED} 那条断言立刻红。
     */
    @Nested
    @DisplayName("E. AdminCommandHandler：三档提示互不相同")
    class HandlerBranches {

        private AdminCommandHandler handler;
        private DatabaseManager dbMock;
        private CommandSender sender;

        @BeforeEach
        void setUpHandler() {
            LetsLearnEnglish handlerPlugin = mock(LetsLearnEnglish.class);
            dbMock = mock(DatabaseManager.class);
            sender = mock(CommandSender.class);

            ConfigManager configManager = mock(ConfigManager.class);
            FileConfiguration cfg = new YamlConfiguration();
            cfg.set("class-system.enabled", true);
            cfg.set("class-system.max-name-length", 32);
            when(configManager.getMainConfig()).thenReturn(cfg);

            MessageUtil messageUtil = mock(MessageUtil.class);
            when(messageUtil.getSenderMessage(any(CommandSender.class), anyString(),
                    any(String[].class))).thenAnswer(inv -> "<" + inv.getArgument(1) + ">");
            when(messageUtil.getSenderMessage(any(CommandSender.class), anyString()))
                    .thenAnswer(inv -> "<" + inv.getArgument(1) + ">");

            // 调度器：异步段与主线程段都<b>内联执行</b>，否则投递永远不会发生
            // （mock 的 scheduler 拿到 Runnable 就丢掉），断言只会看到 0 条消息。
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(scheduler.runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class),
                    any(Runnable.class))).thenAnswer(inv -> {
                        inv.<Runnable>getArgument(1).run();
                        return null;
                    });
            when(scheduler.runTask(any(org.bukkit.plugin.Plugin.class), any(Runnable.class)))
                    .thenAnswer(inv -> {
                        inv.<Runnable>getArgument(1).run();
                        return null;
                    });
            Server server = mock(Server.class);
            when(server.getScheduler()).thenReturn(scheduler);

            when(handlerPlugin.getDatabaseManager()).thenReturn(dbMock);
            when(handlerPlugin.getLogger()).thenReturn(Logger.getLogger("ClassDeleteTriStateTest.handler"));
            when(handlerPlugin.getConfigManager()).thenReturn(configManager);
            when(handlerPlugin.getMessageUtil()).thenReturn(messageUtil);
            when(handlerPlugin.getServer()).thenReturn(server);
            when(handlerPlugin.isEnabled()).thenReturn(true);

            handler = new AdminCommandHandler(handlerPlugin);
        }

        private List<String> deleteMessagesFor(DatabaseManager.ClassDeleteResult result) {
            // 每次调用前清空上一轮的 sendMessage 记录：三个分支共用同一个 sender mock，
            // 不清空会把「上一轮的消息」也算进本轮（表现：第二条断言里出现两条消息）。
            org.mockito.Mockito.clearInvocations(sender);
            when(dbMock.deleteClassDetailed(CLASS_NAME)).thenReturn(result);
            handler.handleClassCommand(sender, new String[]{"class", "delete", CLASS_NAME});
            return capturedMessages();
        }

        private List<String> createdMessagesFor(DatabaseManager.ClassCreateResult result) {
            org.mockito.Mockito.clearInvocations(sender);
            when(dbMock.createClassDetailed(anyString(), any(UUID.class),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(result);
            handler.handleClassCommand(sender, new String[]{"class", "create", CLASS_NAME});
            return capturedMessages();
        }

        private List<String> capturedMessages() {
            org.mockito.ArgumentCaptor<String> captor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(sender, atLeastOnce()).sendMessage(captor.capture());
            return new ArrayList<>(captor.getAllValues());
        }

        @Test
        @DisplayName("DELETED → class.deleted（且不含「找不到」/「失败」）")
        void deletedSendsDeleted() {
            List<String> msgs = deleteMessagesFor(DatabaseManager.ClassDeleteResult.DELETED);
            assertEquals(List.of("<class.deleted>"), msgs,
                    "DELETED 只能发成功提示，实际：" + msgs);
        }

        @Test
        @DisplayName("NOT_FOUND → class.not-found（现有的「找不到班级」）")
        void notFoundSendsNotFound() {
            List<String> msgs = deleteMessagesFor(DatabaseManager.ClassDeleteResult.NOT_FOUND);
            assertEquals(List.of("<class.not-found>"), msgs,
                    "NOT_FOUND 应沿用既有的 class.not-found，实际：" + msgs);
        }

        @Test
        @DisplayName("FAILED → class.delete-failed（不是 class.not-found）")
        void failedSendsDeleteFailed() {
            List<String> msgs = deleteMessagesFor(DatabaseManager.ClassDeleteResult.FAILED);
            assertEquals(List.of("<class.delete-failed>"), msgs,
                    "FAILED 必须发独立的新键 class.delete-failed。"
                            + "若这里是 <class.not-found>，就把「数据库故障」说成了「班名打错」，"
                            + "管理员会去核对班名而不是看日志 —— 这正是本条缺陷的形态");
        }

        @Test
        @DisplayName("三档提示两两不同（回归守卫：防止又折叠回同一句）")
        void threeBranchesAreDistinct() {
            String deleted = deleteMessagesFor(DatabaseManager.ClassDeleteResult.DELETED).toString();
            String notFound = deleteMessagesFor(DatabaseManager.ClassDeleteResult.NOT_FOUND).toString();
            String failed = deleteMessagesFor(DatabaseManager.ClassDeleteResult.FAILED).toString();

            assertFalse(notFound.equals(failed),
                    "NOT_FOUND 与 FAILED 的提示又变成同一句了：三态被折叠回两态");
            assertFalse(deleted.equals(failed), "DELETED 与 FAILED 的提示不得相同");
            assertFalse(deleted.equals(notFound), "DELETED 与 NOT_FOUND 的提示不得相同");
        }

        @Test
        @DisplayName("create 三档：CREATED / ALREADY_EXISTS / FAILED 各一条不同提示")
        void createThreeBranchesAreDistinct() {
            List<String> created = createdMessagesFor(DatabaseManager.ClassCreateResult.CREATED);
            List<String> exists = createdMessagesFor(DatabaseManager.ClassCreateResult.ALREADY_EXISTS);
            List<String> failed = createdMessagesFor(DatabaseManager.ClassCreateResult.FAILED);

            assertEquals(List.of("<class.created>"), created, "CREATED → class.created");
            assertEquals(List.of("<class.name-exists>"), exists,
                    "ALREADY_EXISTS → 既有的 class.name-exists");
            assertEquals(List.of("<class.create-failed>"), failed,
                    "FAILED → 独立新键。折叠成 name-exists 会让管理员去找一个不存在的同名班");
        }
    }

    // ==================================================================
    // F. i18n：新键必须在 zh 与 en 两边都存在且对称
    // ==================================================================

    /**
     * 新键的存在性 + zh/en 对称性（<b>读真实资源文件</b>，不是抄一份清单）。
     *
     * <p>为什么不靠 {@code DiagnoseI18nParityTest} 兜住：那条只管 {@code diagnose.*}
     * 段的键集合，且只从 {@code DiagnoseCommand} 源码里扫 key。{@code class.*} 段的键
     * 拼错/漏加在哪一边都不会有编译错误 —— 运行期只显示 {@code [?class.delete-failed]}，
     * 而那恰恰是"数据库已经故障时"才走的路径，没人会盯着看。
     *
     * <p>断言三件事：
     * <ol>
     *   <li>{@code class.delete-failed} / {@code class.create-failed} 在两个文件里都有；</li>
     *   <li>{@code class.*} 段的叶子键集合两边<b>完全一致</b>（多一边少一边都会红）；</li>
     *   <li>两个新键的文案里都含 {@code {class}} 占位符，且含"日志/console"这类
     *       可执行指引 —— 只说"失败"的文案等于没说。</li>
     * </ol>
     */
    @Nested
    @DisplayName("F. i18n：新键在 zh/en 对称存在，且文案可执行")
    class I18n {

        private FileConfiguration load(String resource) throws Exception {
            ClassLoader cl = MessageUtil.class.getClassLoader();
            InputStream in = cl.getResourceAsStream(resource);
            assertNotNull(in, "bundled resource missing: " + resource);
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        private Set<String> leafKeys(FileConfiguration cfg, String root) {
            Set<String> out = new TreeSet<>();
            for (String key : cfg.getKeys(true)) {
                if (key.startsWith(root + ".") && !cfg.isConfigurationSection(key)) {
                    out.add(key);
                }
            }
            return out;
        }

        @Test
        @DisplayName("class.delete-failed 与 class.create-failed 在 zh 与 en 都存在")
        void newKeysExistInBothLanguages() throws Exception {
            for (String resource : List.of("messages/zh.yml", "messages/en.yml")) {
                FileConfiguration cfg = load(resource);
                for (String key : List.of("class.delete-failed", "class.create-failed")) {
                    assertNotNull(cfg.getString(key),
                            resource + " 缺少键 " + key
                                    + " —— 运行期玩家只会看到 [?class.delete-failed]，"
                                    + "而这恰好只在数据库已故障时才出现，没人会发现");
                }
            }
        }

        @Test
        @DisplayName("class.* 段的叶子键集合在 zh 与 en 完全对称")
        void classKeysAreSymmetric() throws Exception {
            Set<String> zh = leafKeys(load("messages/zh.yml"), "class");
            Set<String> en = leafKeys(load("messages/en.yml"), "class");

            Set<String> zhOnly = new TreeSet<>(zh);
            zhOnly.removeAll(en);
            Set<String> enOnly = new TreeSet<>(en);
            enOnly.removeAll(zh);

            assertTrue(zhOnly.isEmpty(), "仅中文有的键（英文端会显示 [?key]）：" + zhOnly);
            assertTrue(enOnly.isEmpty(), "仅英文有的键（中文端会显示 [?key]）：" + enOnly);
        }

        @Test
        @DisplayName("两条失败文案都带 {class} 占位符与「查日志」指引")
        void failureMessagesAreActionable() throws Exception {
            for (String resource : List.of("messages/zh.yml", "messages/en.yml")) {
                FileConfiguration cfg = load(resource);

                String deleteFailed = cfg.getString("class.delete-failed");
                assertNotNull(deleteFailed, resource + " 缺 class.delete-failed");
                assertTrue(deleteFailed.contains("{class}"),
                        resource + " 的 class.delete-failed 必须带 {class} 占位符（"
                                + "handler 传了 className，漏占位符会静默丢掉班名）：" + deleteFailed);
                assertTrue(deleteFailed.toLowerCase(java.util.Locale.ROOT).contains("log")
                                || deleteFailed.contains("日志"),
                        resource + " 的 class.delete-failed 必须给出「查日志/看控制台」这种"
                                + "可执行指引，不能只说\"失败\"：" + deleteFailed);

                String createFailed = cfg.getString("class.create-failed");
                assertNotNull(createFailed, resource + " 缺 class.create-failed");
                assertTrue(createFailed.contains("{class}"),
                        resource + " 的 class.create-failed 必须带 {class} 占位符：" + createFailed);
                assertTrue(createFailed.toLowerCase(java.util.Locale.ROOT).contains("log")
                                || createFailed.contains("日志"),
                        resource + " 的 class.create-failed 必须给出「查日志/看控制台」指引："
                                + createFailed);
            }
        }

        @Test
        @DisplayName("删班失败文案必须说清「已回滚/没有删掉数据」")
        void deleteFailureStatesRollback() throws Exception {
            String zh = load("messages/zh.yml").getString("class.delete-failed");
            String en = load("messages/en.yml").getString("class.delete-failed");

            assertNotNull(zh);
            assertNotNull(en);
            // 事务化之后这条文案必须告诉管理员"数据没变" —— 否则他会以为成员已被移出
            assertTrue(zh.contains("回滚") || zh.contains("未改动"),
                    "中文删班失败文案必须说明整批已回滚/未改动，否则管理员会以为成员已被移出："
                            + zh);
            assertTrue(en.toLowerCase(java.util.Locale.ROOT).contains("rolled back")
                            || en.toLowerCase(java.util.Locale.ROOT).contains("not"),
                    "英文删班失败文案必须说明整批已回滚，实际：" + en);
        }
    }

    // ==================================================================
    // G. 结构守卫：三态必须仍被调用方使用
    // ==================================================================

    /**
     * {@code AdminCommandHandler} 必须调用 Detailed 版（三态），而不是 boolean 版。
     *
     * <p>行为测试能通过而缺陷复发的情况只有一种：handler 改回
     * {@code if (db.deleteClass(...)) ... else not-found} —— 那时上面 E 组的断言会红，
     * 但若有人同时把 E 组也删掉（它看起来"只是在测文案"），这条结构守卫是最后一道。
     * 直接读源码，与 {@code ResetFailureVisibilityTest.handlerMustCheckDeleteResult} 同法。
     */
    @Nested
    @DisplayName("G. 结构守卫：handler 必须走三态版")
    class StructuralGuard {

        /**
         * 定位仓库内源文件，**不依赖 cwd**。
         *
         * <p>Surefire fork 的 workdir 是模块根，但本项目的自建 runner 可在任意目录启动。
         * 相对路径在两者之间不稳定 —— 一旦 cwd 不对，用例变红却看起来像"结构护栏失败"，
         * 实际是路径问题（本仓多次踩过这类假红）。故从 cwd 逐级上溯，兜底再按本类
         * 字节码位置上溯。写法与 {@code ResetFailureVisibilityTest.locateSource} 同源。
         */
        private java.nio.file.Path locateSource(String relPath) {
            java.nio.file.Path rel = java.nio.file.Path.of(relPath);
            if (java.nio.file.Files.exists(rel)) {
                return rel;
            }
            java.nio.file.Path cwd = java.nio.file.Path.of(
                    System.getProperty("user.dir", "."));
            for (java.nio.file.Path p = cwd; p != null; p = p.getParent()) {
                java.nio.file.Path candidate = p.resolve(rel);
                if (java.nio.file.Files.exists(candidate)) {
                    return candidate;
                }
            }
            try {
                java.nio.file.Path cls = java.nio.file.Path.of(
                        ClassDeleteTriStateTest.class.getProtectionDomain()
                                .getCodeSource().getLocation().toURI());
                for (java.nio.file.Path p = cls; p != null; p = p.getParent()) {
                    java.nio.file.Path candidate = p.resolve(rel);
                    if (java.nio.file.Files.exists(candidate)) {
                        return candidate;
                    }
                }
            } catch (Exception ignored) {
                // 落到下面的断言失败，消息里会带 relPath 与 cwd
            }
            throw new AssertionError("找不到源文件 " + relPath
                    + "（user.dir=" + cwd.toAbsolutePath() + "）");
        }

        private String handlerSource() throws Exception {
            return java.nio.file.Files.readString(locateSource(
                    "src/main/java/com/letslearnenglish/minecraftplugin/core/manager/"
                            + "AdminCommandHandler.java"), StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("handleClassDelete 调用 deleteClassDetailed 并分支到 delete-failed")
        void deleteHandlerUsesTriState() throws Exception {
            String src = handlerSource();

            assertTrue(src.contains("deleteClassDetailed("),
                    "handleClassDelete 没有调用 deleteClassDetailed —— 三态被绕开了");
            assertTrue(src.contains("class.delete-failed"),
                    "handler 里找不到 class.delete-failed —— FAILED 分支没有独立提示，"
                            + "数据库故障又会被说成「找不到班级」");
            // 反向：不得再出现 boolean 形态的删班调用（它表达不出三态）
            assertFalse(src.contains("db.deleteClass("),
                    "handler 里仍有 boolean 形态的 db.deleteClass(...) 调用 —— "
                            + "它把 NOT_FOUND 与 FAILED 折叠成同一个 false，"
                            + "正是本条缺陷的形态");
        }

        @Test
        @DisplayName("handleClassCreate 调用 createClassDetailed 并分支到 create-failed")
        void createHandlerUsesTriState() throws Exception {
            String src = handlerSource();

            assertTrue(src.contains("createClassDetailed("),
                    "handleClassCreate 没有调用 createClassDetailed —— 三态被绕开了");
            assertTrue(src.contains("class.create-failed"),
                    "handler 里找不到 class.create-failed —— FAILED 分支没有独立提示，"
                            + "INSERT 故障又会被称为「已存在同名班级」");
            assertFalse(src.contains("db.createClass("),
                    "handler 里仍有 boolean 形态的 db.createClass(...) 调用 —— "
                            + "它把 ALREADY_EXISTS 与 FAILED 折叠成同一个 false");
        }

        @Test
        @DisplayName("DatabaseManager 里两条 DELETE 已收进显式事务")
        void deleteClassOpensATransaction() throws Exception {
            String src = java.nio.file.Files.readString(locateSource(
                    "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java"),
                    StandardCharsets.UTF_8);

            int method = src.indexOf("public ClassDeleteResult deleteClassDetailed(");
            assertTrue(method > 0, "deleteClassDetailed 不见了（结构守卫失效，请更新锚点）");
            int nextMethod = src.indexOf("public boolean deleteClass(", method);
            assertTrue(nextMethod > method, "找不到 deleteClassDetailed 的方法边界");
            String body = src.substring(method, nextMethod);

            // 两条独立锚点：只查 setAutoCommit 会被 deletePlayerData 等方法的同形代码满足
            assertTrue(body.contains("setAutoCommit(false)"),
                    "deleteClassDetailed 里没有 setAutoCommit(false) —— 两条 DELETE 又回到"
                            + "各自 auto-commit，第二条失败会永久留下一个空班");
            assertTrue(body.contains("rollback()"),
                    "deleteClassDetailed 里没有 rollback() —— 失败时不会撤销已删除的成员行");
            assertTrue(body.contains("setAutoCommit(true)"),
                    "deleteClassDetailed 里没有恢复 setAutoCommit(true) —— 该连接此后所有语句"
                            + "都会落进一个永不提交的隐式事务（插件从此写不进数据且无报错）");
        }
    }
}
