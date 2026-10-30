package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
 * L24 验收：{@link DatabaseManager#runMaintenance()} 的 SQLite 维护语义。
 *
 * <p><b>为什么需要这个类</b>：{@code VACUUM} 是唯一能回收 SQLite 空洞的操作，但它
 * 有几个只在真库上才暴露的坑——① 在事务内调用会直接失败；② 失败必须被吞掉（维护
 * 失败不该影响玩家）；③ 必须只在 SQLite 后端执行（MySQL 无此语法）。这些都不能靠
 * 读代码断言，本类因此<b>同时</b>用真 SQLite（{@link RealSqlite}，临时文件）和
 * mock 连接（{@link Guards}，验证分支与异常口径）两侧夹住。
 *
 * <p><b>为什么能纯 JVM 跑</b>：{@code DatabaseManager} 除 {@code plugin.getLogger()} /
 * {@code plugin.getDataFolder()} / {@code configManager.getMainConfig()} 外不触碰
 * Bukkit 静态方法，因此 {@code mock(LetsLearnEnglish.class)} + 真
 * {@link YamlConfiguration} + {@link TempDir} 就能把真实 SQLite 后端跑起来，
 * 不需要 MockBukkit，也不需要启动服务端。真库驱动来自 test-scope 的
 * {@code org.xerial:sqlite-jdbc}（不进入插件 JAR）。
 */
class SqliteMaintenanceTest {

    /** 维护表名前缀，与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    /**
     * plugin / config 的 mock 在整类生命周期内复用。
     *
     * <p>{@code LetsLearnEnglish} 的继承链很深（{@code JavaPlugin} → {@code PluginBase} →
     * {@code Plugin}/{@code TabExecutor}/...），Mockito 每次 {@code mock()} 都要逐类
     * 重转换，单次约 12 秒。本类 9 个用例若各建一次，光造 mock 就要两分钟；复用后
     * 只付一次。每个用例在各自 {@code @BeforeEach} 里重新打桩即可（后打的桩覆盖前一个）。
     */
    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;

    @BeforeAll
    static void createMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        // 真 Logger：失败路径的 log(WARNING, ..., e) 需要真实例，否则 NPE 会掩盖真实行为。
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SqliteMaintenanceTest"));
    }

    /**
     * 真 SQLite 侧：建库、造空洞、执行维护、验证回收与可用性。
     *
     * <p>断言用 {@code PRAGMA freelist_count}（空闲页数）而不是文件字节数：文件大小
     * 受页对齐/缓存影响会抖动，freelist 才是"空洞是否被回收"的直接信号。
     */
    @Nested
    @DisplayName("真 SQLite（临时文件）")
    class RealSqlite {

        private DatabaseManager databaseManager;

        @BeforeEach
        void initRealDatabase(@TempDir Path tempDir) throws Exception {
            FileConfiguration config = new YamlConfiguration();
            config.set("database.type", "sqlite");
            config.set("database.sqlite.filename", "maintenance-test.db");
            when(configManager.getMainConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

            databaseManager = new DatabaseManager(plugin, configManager);
            databaseManager.initialize();
            assertTrue(databaseManager.isSQLite(), "前置条件：真库必须走 SQLite 分支");
        }

        @AfterEach
        void closeDatabase() {
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
        }

        @Test
        @DisplayName("大量 insert + delete 后 runMaintenance 回收全部空闲页且不抛异常")
        void maintenanceReclaimsFreePages() throws SQLException {
            seedAndDeleteRows(600);

            int freeBefore = freelistCount();
            assertTrue(freeBefore > 0, "前置条件不成立：DELETE 之后应存在空闲页（实际 " + freeBefore + "）");

            boolean executed = assertDoesNotThrow(databaseManager::runMaintenance,
                    "runMaintenance 不允许把异常抛给调用方");
            assertTrue(executed, "维护应报告成功");

            assertEquals(0, freelistCount(),
                    "VACUUM 之后不应再有空闲页（回收失败说明语句没真正执行）");
        }

        @Test
        @DisplayName("维护后库仍可读写（VACUUM 未破坏 schema 或数据）")
        void databaseRemainsReadableAndWritable() throws SQLException {
            seedAndDeleteRows(300);
            databaseManager.runMaintenance();

            UUID uuid = UUID.randomUUID();
            synchronized (databaseManager.getConnectionLock()) {
                Connection conn = databaseManager.getConnection();
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO " + PREFIX + "player_data (uuid, player_name) VALUES (?, ?)")) {
                    insert.setString(1, uuid.toString());
                    insert.setString(2, "AfterVacuum");
                    assertEquals(1, insert.executeUpdate(), "维护后必须仍能写入");
                }
                try (PreparedStatement select = conn.prepareStatement(
                        "SELECT player_name FROM " + PREFIX + "player_data WHERE uuid = ?")) {
                    select.setString(1, uuid.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        assertTrue(rs.next(), "维护后必须仍能读回刚写入的行");
                        assertEquals("AfterVacuum", rs.getString("player_name"));
                    }
                }
            }
        }

        @Test
        @DisplayName("干净库上重复执行维护幂等（第二次仍返回成功、库不受损）")
        void maintenanceIsIdempotentOnCleanDatabase() {
            assertTrue(databaseManager.runMaintenance(), "首次维护应成功");
            assertTrue(databaseManager.runMaintenance(), "干净库上再次维护也应成功（幂等）");
            assertTrue(databaseManager.isConnectionHealthy(), "维护后连接应保持健康");
        }

        /**
         * 真库异常路径：另一个连接持有写事务时 {@code VACUUM} 会拿不到锁。
         *
         * <p>这是"库被占用"的现实形态（管理员手动跑 sqlite3、外部备份工具持有写锁）。
         * 断言三件事：① 不抛异常；② 返回 {@code false}；③ 锁释放后维护能恢复成功
         * ——第三点最重要，它证明前面的失败没有把连接搞坏（autocommit 状态正确）。
         */
        @Test
        @DisplayName("库被其它连接写锁占用时返回 false 不抛异常，锁释放后可恢复")
        void maintenanceFailsGracefullyWhenDatabaseIsLocked() throws Exception {
            seedAndDeleteRows(200);
            // 用被测库自己的路径另开一条连接——必须是同一个文件，才能制造真实锁竞争。
            String url = "jdbc:sqlite:" + databaseManager.getSqliteFilePath();

            try (Connection blocker = DriverManager.getConnection(url)) {
                blocker.setAutoCommit(false);
                try (Statement stmt = blocker.createStatement()) {
                    stmt.executeUpdate("INSERT INTO " + PREFIX + "player_data (uuid, player_name)"
                            + " VALUES ('locker', 'locker')");
                }
                boolean result = assertDoesNotThrow(databaseManager::runMaintenance,
                        "库被占用时维护不得抛异常");
                assertFalse(result, "拿不到锁时应报告未完成");
                blocker.rollback();
            }

            assertTrue(databaseManager.isConnectionHealthy(), "失败后连接必须仍然可用");
            assertTrue(databaseManager.runMaintenance(), "锁释放后维护应能成功（连接状态未被污染）");
        }

        /**
         * 造出"删除后留下的空洞"：向真实业务表写入 {@code rows} 行带 1KB 负载的记录
         * （{@code ai_chat_history.content}）再全部删除。用真表而非临时表，确保 VACUUM
         * 处理的是插件自己的 schema。
         *
         * <p>批量写入包在单个事务里：SQLite 默认每次 executeBatch 都 fsync，600 行逐条
         * 提交会把本测试拖到数十秒；事务化后降到毫秒级。事务结束后恢复 autocommit，
         * 否则 {@code VACUUM} 会因"处于事务中"失败——这本身也是被测语义的一部分。
         */
        private void seedAndDeleteRows(int rows) throws SQLException {
            synchronized (databaseManager.getConnectionLock()) {
                Connection conn = databaseManager.getConnection();
                boolean originalAutoCommit = conn.getAutoCommit();
                try {
                    conn.setAutoCommit(false);
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO " + PREFIX + "ai_chat_history (player_uuid, session_id, role, content)"
                                    + " VALUES (?, ?, ?, ?)")) {
                        String filler = "x".repeat(1024);
                        for (int i = 0; i < rows; i++) {
                            insert.setString(1, UUID.randomUUID().toString());
                            insert.setString(2, "session-" + i);
                            insert.setString(3, "user");
                            insert.setString(4, filler);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    try (Statement delete = conn.createStatement()) {
                        delete.executeUpdate("DELETE FROM " + PREFIX + "ai_chat_history");
                    }
                    conn.commit();
                } finally {
                    conn.setAutoCommit(originalAutoCommit);
                }
            }
        }

        /** 读 {@code PRAGMA freelist_count}：库内空闲页数，VACUUM 的直接观测指标。 */
        private int freelistCount() throws SQLException {
            synchronized (databaseManager.getConnectionLock()) {
                try (PreparedStatement stmt = databaseManager.getConnection()
                        .prepareStatement("PRAGMA freelist_count");
                     ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next(), "PRAGMA freelist_count 应返回一行");
                    return rs.getInt(1);
                }
            }
        }
    }

    /**
     * 分支与异常口径：用 mock 连接精确验证"哪些分支会碰数据库"。
     *
     * <p>这一组不需要真库——它要证明的恰恰是<b>不该</b>执行 SQL 的路径真的没有执行，
     * 以及执行失败时异常不外泄。
     */
    @Nested
    @DisplayName("后端分支与异常口径（mock 连接）")
    class Guards {

        private DatabaseManager databaseManager;
        private Connection connection;

        @BeforeEach
        void setUpMocks() throws Exception {
            databaseManager = new DatabaseManager(plugin, configManager);
            connection = mock(Connection.class);
            setField(databaseManager, "connection", connection);
            setField(databaseManager, "tablePrefix", PREFIX);
        }

        @Test
        @DisplayName("MySQL 后端直接返回 false，且完全不碰连接（MySQL 无 VACUUM）")
        void mysqlBackendSkipsMaintenance() throws Exception {
            setField(databaseManager, "isSQLite", false);

            assertFalse(databaseManager.runMaintenance(), "MySQL 后端应报告未执行维护");
            verify(connection, never()).prepareStatement(anyString());
            verify(connection, never()).createStatement();
        }

        @Test
        @DisplayName("SQLite 分支确实执行 PRAGMA optimize 与 VACUUM")
        void sqliteBranchExecutesOptimizeAndVacuum() throws Exception {
            setField(databaseManager, "isSQLite", true);
            PreparedStatement stmt = mock(PreparedStatement.class);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.prepareStatement(anyString())).thenReturn(stmt);

            assertTrue(databaseManager.runMaintenance());

            verify(connection).prepareStatement("PRAGMA optimize");
            verify(connection).prepareStatement("VACUUM");
        }

        @Test
        @DisplayName("VACUUM 失败时记 WARNING 并返回 false，异常不外泄")
        void sqliteFailureIsSwallowed() throws Exception {
            setField(databaseManager, "isSQLite", true);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.prepareStatement(anyString()))
                    .thenThrow(new SQLException("database is locked"));

            List<LogRecord> warnings = captureWarnings(() -> {
                boolean result = assertDoesNotThrow(databaseManager::runMaintenance,
                        "维护失败不得把 SQLException 抛给调用方");
                assertFalse(result, "失败时应返回 false 供调用方判断");
            });

            assertFalse(warnings.isEmpty(), "失败必须留下 WARNING，否则服主无从知晓整理没做成");
            assertTrue(warnings.stream().anyMatch(r -> r.getThrown() instanceof SQLException),
                    "WARNING 必须带原始异常堆栈（否则排查'为什么没整理成功'只能靠猜）");
        }

        @Test
        @DisplayName("已有维护在跑时第二次调用直接跳过（非阻塞去重）")
        void concurrentMaintenanceIsSkipped() throws Exception {
            setField(databaseManager, "isSQLite", true);
            when(connection.getAutoCommit()).thenReturn(true);

            // 第一次维护卡在 PRAGMA optimize 上，制造"正在执行"的窗口。
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            PreparedStatement stmt = mock(PreparedStatement.class);
            doAnswer(invocation -> {
                entered.countDown();
                release.await();
                return false;
            }).when(stmt).execute();
            when(connection.prepareStatement(anyString())).thenReturn(stmt);

            AtomicBoolean firstResult = new AtomicBoolean();
            Thread worker = new Thread(() -> firstResult.set(databaseManager.runMaintenance()));
            worker.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "前置条件不成立：第一次维护未能进入执行期");
                // 第二次调用必须立刻返回 false —— 它连连接都不该碰（去重闸在加锁之前）。
                assertFalse(databaseManager.runMaintenance(), "并发维护应被跳过");
            } finally {
                release.countDown();
                worker.join(5000);
            }
            assertTrue(firstResult.get(), "第一次维护应正常完成");
            assertFalse(worker.isAlive(), "工作线程应已退出");
        }

        @Test
        @DisplayName("进入前 autocommit 被关闭时强制开启（否则 VACUUM 会失败），且不还原成关闭态")
        void forcesAutoCommitOnWhenItWasOff() throws Exception {
            setField(databaseManager, "isSQLite", true);
            PreparedStatement stmt = mock(PreparedStatement.class);
            // 模拟"别处遗留了一个未提交事务"的缺陷状态。
            when(connection.getAutoCommit()).thenReturn(false);
            when(connection.prepareStatement(anyString())).thenReturn(stmt);

            assertTrue(databaseManager.runMaintenance());

            // 必须先开 autocommit，VACUUM 才合法。
            verify(connection).setAutoCommit(true);
            // 刻意不还原成 false：还原会让后续写入永不提交（静默丢数据）。
            verify(connection, never()).setAutoCommit(false);
        }

        /** 反射写私有字段：本类刻意不引入 setter，避免生产代码为测试开洞。 */
        private void setField(Object target, String name, Object value) throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        /**
         * 捕获 {@code java.util.logging} 的 WARNING 记录。
         *
         * <p>生产代码用的是 {@code Logger.getLogger(<插件名>)} 的真实 {@link Logger}
         * （{@code plugin.getLogger()} 的桩），因此只能挂 handler 抓——这是唯一不改变
         * 被测代码、也不依赖日志框架实现细节的做法。
         */
        private List<LogRecord> captureWarnings(Runnable action) {
            Logger logger = plugin.getLogger();
            List<LogRecord> captured = new ArrayList<>();
            Handler handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    // 只收 WARNING 及以上：本用例关心的是"失败是否被记录"，INFO 的
                    // "维护完成"不该混进来稀释断言。
                    if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                        captured.add(record);
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
            try {
                action.run();
            } finally {
                logger.removeHandler(handler);
            }
            return captured;
        }
    }
}
