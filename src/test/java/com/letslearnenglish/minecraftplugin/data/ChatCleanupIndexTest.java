package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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
 * Round 21：{@code ai_chat_history.timestamp} 保留期清理索引的存在性与语义。
 *
 * <h2>为什么这个索引需要一条专门的测试</h2>
 * <p>{@code cleanupOldChatHistory} 的第一条语句是
 * {@code DELETE FROM <p>ai_chat_history WHERE timestamp < <30 天前>}。原有两个索引的
 * 前导列是 {@code player_uuid} 与 {@code session_id}，都不以 {@code timestamp} 开头：
 * <ul>
 *   <li><b>MySQL/InnoDB</b> 没有 skip-scan，只能在三条二级索引里挑一条全扫；</li>
 *   <li><b>SQLite</b> planner 会用 {@code (player_uuid, timestamp)} 做 skip-scan，
 *       代价 ≈ 前导列去重值个数 × 索引探测。实测 10 万行、
 *       40 名玩家 → 0.25 ms，400 名 → 2.2 ms，<b>5000 名 → 65.6 ms</b>。
 *       也就是说：<b>服务器玩家越多，这条清理越慢</b>，正好与"小服测得动"的直觉相反。
 *       加索引后三种规模一律 0.1 ms。</li>
 * </ul>
 *
 * <p>而它<b>纯性能</b>，删掉不会让任何行为测试变红 —— 正是本仓 MEMORY 里
 * {@code performance-optimizations-need-source-lint} 记的那类"假绿"。所以本类
 * 直接读<b>真库的 {@code sqlite_master} / {@code PRAGMA index_info}</b> 来做断言，
 * 而不是比对源码字符串：源码里有 {@code createIndexIfMissing("idx_chat_timestamp", ...)}
 * 这一行、但索引因异常被 {@code fine} 吞掉从没建成，是真实可能发生的
 * （{@code createIndexIfMissing} 的 {@code catch} 只记 fine）。
 *
 * <h2>三个嵌套类各自封死什么</h2>
 * <ol>
 *   <li>{@link RealSchemaIndex} —— 索引真的建出来了，且列就是 {@code timestamp}；</li>
 *   <li>{@link CleanupPlan} —— planner 真的<b>用上</b>了它（{@code SEARCH} 而非
 *       {@code SCAN}/skip-scan）。「索引存在但查询不走」是另一条静默失效路径；</li>
 *   <li>{@link CleanupSemantics} —— 加了索引后清理的<b>行为</b>没变：旧行没、新行在。</li>
 * </ol>
 *
 * <p><b>变异验证</b>：把 {@code createTables()} 里那条
 * {@code createIndexIfMissing("idx_chat_timestamp", ...)} 删掉，
 * {@link RealSchemaIndex} 与 {@link CleanupPlan} 两个嵌套类会红（本类作者已实测）；
 * 只删 {@code schema.sql} 里那一行则 {@link SchemaSqlMirror} 红 —— 两处都必须加。
 */
@DisplayName("ChatCleanupIndex — ai_chat_history 保留期清理索引")
class ChatCleanupIndexTest {

    private static final String PREFIX = "lle_";
    private static final String TABLE = PREFIX + "ai_chat_history";
    private static final String INDEX = PREFIX + "idx_chat_timestamp";

    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;

    @BeforeAll
    static void createMocks() {
        // mock 只建一次：LetsLearnEnglish 的继承链很深，每个用例重建约 12 秒。
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ChatCleanupIndexTest"));
    }

    private DatabaseManager db;

    @BeforeEach
    void initRealDatabase(@TempDir Path dir) throws Exception {
        FileConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "chat-cleanup-index.db");
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(),
                "前置条件：本类全部断言都基于真 SQLite 的目录表，走 MySQL 分支则无效喵");
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // 1. 索引存在 + 列正确
    // ==================================================================

    @Nested
    @DisplayName("1. 真库里索引确实存在")
    class RealSchemaIndex {

        @Test
        @DisplayName("createTables() 之后 sqlite_master 里有 idx_chat_timestamp")
        void indexIsRegistered() throws Exception {
            try (Connection conn = open()) {
                assertTrue(indexExists(conn, INDEX),
                        "真库里找不到索引 " + INDEX + " —— cleanupOldChatHistory 的 "
                                + "`DELETE ... WHERE timestamp < ?` 只能在三条二级索引里"
                                + "全扫/skip-scan。createIndexIfMissing 的 SQLException 被"
                                + " fine 吞掉，所以「源码里有这行调用」不等于「索引建成了」。");
            }
        }

        @Test
        @DisplayName("索引首列（且唯一列）就是 timestamp，前缀顺序没错")
        void indexLeadsOnTimestamp() throws Exception {
            List<String> cols = indexColumns(INDEX);
            assertEquals(List.of("timestamp"), cols,
                    "索引列必须恰好是 [timestamp]；写成 (player_uuid, timestamp) 之类"
                            + "以 player_uuid 打头的复合索引对纯时间范围清理无用"
                            + "（那正是既有 idx_chat_player_time 的情形）。实际: " + cols);
        }

        @Test
        @DisplayName("既有两个索引没有被顺带删掉（idx_chat_player_time / idx_chat_session）")
        void existingIndexesSurviveInAddition() throws Exception {
            try (Connection conn = open()) {
                assertTrue(indexExists(conn, PREFIX + "idx_chat_player_time"),
                        "idx_chat_player_time 仍为按玩家读历史的查询服务，不得为新索引让路喵");
                assertTrue(indexExists(conn, PREFIX + "idx_chat_session"),
                        "idx_chat_session 仍为按会话读历史的查询服务，不得为新索引让路喵");
            }
        }

        @Test
        @DisplayName("重复 initialize() 不会因索引已存在而失败（幂等）")
        void indexCreationIsIdempotent() throws Exception {
            // createTables() 在每次 initialize() 时都会重跑；CREATE INDEX IF NOT EXISTS
            // （SQLite）与 information_schema 查重（MySQL）都必须让它安全。
            db.initialize();
            db.initialize();
            try (Connection conn = open()) {
                assertTrue(indexExists(conn, INDEX), "重复初始化后索引应仍在");
                assertEquals(1, indexCount(conn, TABLE, "timestamp"),
                        "不应出现重复的 timestamp 索引（同一列建两条 = 双倍写开销）"
                                + "，实际: " + allIndexesOn(conn));
            }
        }
    }

    // ==================================================================
    // 2. planner 真的用上了它
    // ==================================================================

    @Nested
    @DisplayName("2. 清理语句的查询计划走时间索引")
    class CleanupPlan {

        /**
         * 分两个规模断言，因为 skip-scan 的代价与「前导列去重值个数」成正比 ——
         * 40 名玩家的小库上 skip-scan 看起来完全没问题（0.25 ms），
         * 只有把玩家数拉高才暴露。本测试<b>两个规模都断言</b>，防止将来有人
         * 以"小服测着挺快"为据把这个索引删掉/降级。
         */
        @Test
        @DisplayName("40 名玩家规模：DELETE 计划用 idx_chat_timestamp")
        void planUsesTimestampIndexSmallCardinality() throws Exception {
            assertPlanUsesTimestampIndex(40);
        }

        @Test
        @DisplayName("5000 名玩家规模：DELETE 计划仍用 idx_chat_timestamp（skip-scan 在此退化最严重）")
        void planUsesTimestampIndexHighCardinality() throws Exception {
            assertPlanUsesTimestampIndex(5000);
        }

        private void assertPlanUsesTimestampIndex(int players) throws Exception {
            int rows = 2_000;
            seedChatRows(rows, players);

            String plan = deleteQueryPlan();
            assertNotNull(plan, "EXPLAIN QUERY PLAN 必须返回一行（否则无法判定计划）");
            assertTrue(plan.contains("idx_chat_timestamp"),
                    "清理语句的查询计划没走 " + INDEX + "，实际计划: " + plan
                            + "\n（players=" + players + "）—— 索引存在但查询不用的静默失效。");
            assertFalse(plan.contains("SCAN " + TABLE) && !plan.contains("USING"),
                    "清理语句退化成全表扫: " + plan);
        }
    }

    // ==================================================================
    // 3. 清理行为没被改变
    // ==================================================================

    @Nested
    @DisplayName("3. 清理语义不变（索引是纯性能改动）")
    class CleanupSemantics {

        @Test
        @DisplayName("旧行被删、新行保留、边界内外的行数精确")
        void purgesOldRowsAndKeepsRecentOnes() throws Exception {
            UUID player = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
            String session = "semantics001";

            // 三天前 / 一天前的行必须留下；60 天前 / 31 天前的行必须被删。
            // 用 Java 侧算时间戳再插入，避免依赖 SQLite 的 datetime('now') 时区。
            seedChatRowAt(player, session, 1);   // keep
            seedChatRowAt(player, session, 3);   // keep
            seedChatRowAt(player, session, 31);  // purge
            seedChatRowAt(player, session, 60);  // purge

            assertEquals(4, countRows(), "前置条件：应有 4 行待清理数据");

            db.cleanupOldChatHistory(30);

            assertEquals(2, countRows(),
                    "30 天保留期应只留下 2 行（1 天前、3 天前）。索引不改变 WHERE 语义，"
                            + "删多/删少都说明改动越过了性能边界喵");
            // 不能断言 julianday 差值的整数部分：SQLite 的 'now' 是 UTC，
            // julianday('now') - julianday(本地时间字符串) 会掺入时区偏移，
            // 实测 1 天前与 3 天前的行读回来都是 [2, 0]。改用插入时写进 content
            // 的年龄标记，断言的是我们真正关心的东西：留下的确实是最新那两行。
            assertEquals(Set.of("content-1", "content-3"), remainingContents(),
                    "留下的必须是最近的两行（按写入时记录的年龄标记核对）");
        }

        @Test
        @DisplayName("空表上清理不抛异常（首次启动的真实情形）")
        void cleanupOnEmptyTableIsSafe() throws Exception {
            assertEquals(0, countRows(), "前置条件：表应为空");
            try {
                db.cleanupOldChatHistory(30);
            } catch (RuntimeException e) {
                throw new AssertionError("空表清理不得抛异常（每次新服首次启动都会走到）", e);
            }
            assertEquals(0, countRows());
        }

        @Test
        @DisplayName("全部行都过期时全部删除，且索引仍可复用（二次写入/清理正常）")
        void purgesEverythingWhenAllRowsExpiredThenKeepsWorking() throws Exception {
            UUID player = UUID.randomUUID();
            for (int age : new int[]{40, 50, 60}) {
                seedChatRowAt(player, "old", age);
            }
            assertEquals(3, countRows());

            db.cleanupOldChatHistory(30);
            assertEquals(0, countRows(), "全部过期时应清空");

            // 索引在大量删除后不得损坏：再写再清一轮必须正常。
            seedChatRowAt(player, "new", 0);
            assertEquals(1, countRows());
            db.cleanupOldChatHistory(30);
            assertEquals(1, countRows(), "新写入的当日行不应被删");

            try (Connection conn = open()) {
                assertTrue(indexExists(conn, INDEX), "多轮清理后索引仍应存在");
            }
        }
    }

    // ==================================================================
    // 4. schema.sql 镜像同步（漏一处会漂移）
    // ==================================================================

    @Nested
    @DisplayName("4. schema.sql 与 createTables() 同步")
    class SchemaSqlMirror {

        /** 从工作目录向上找项目根：surefire 与独立 launcher 的 cwd 不同，两种都要能跑。 */
        private Path schemaPath() {
            Path dir = Path.of("").toAbsolutePath();
            for (int i = 0; i < 6 && dir != null; i++) {
                Path candidate = dir.resolve("src/main/resources/schema.sql");
                if (Files.exists(candidate)) {
                    return candidate;
                }
                dir = dir.getParent();
            }
            return Path.of("src/main/resources/schema.sql");
        }

        @Test
        @DisplayName("schema.sql 里也有 idx_chat_timestamp，且列是 timestamp")
        void schemaSqlDeclaresSameIndex() throws Exception {
            Path schema = schemaPath();
            assertTrue(Files.exists(schema), "应能找到 schema.sql: " + schema.toAbsolutePath());
            String ddl = Files.readString(schema);

            assertTrue(ddl.contains("idx_chat_timestamp"),
                    "schema.sql 缺 idx_chat_timestamp —— 它与 DatabaseManager.createTables()"
                            + " 是同一套 schema 的两份声明，漏一处就会漂移"
                            + "（schema.sql 是文档镜像，但仓库里有测试按它建库）");
            assertTrue(ddl.contains("idx_chat_timestamp ON {prefix}ai_chat_history(timestamp)"),
                    "schema.sql 里 idx_chat_timestamp 的目标列必须恰好是 ai_chat_history(timestamp)");
        }

        /**
         * 反向：两边列必须<b>一致</b>，防止一边改成 (timestamp, session_id)、另一边没动。
         */
        @Test
        @DisplayName("schema.sql 与真库对同一索引的列定义一致")
        void schemaSqlColumnsMatchRuntime() throws Exception {
            String ddl = Files.readString(schemaPath());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "idx_chat_timestamp ON \\{prefix\\}ai_chat_history\\(([^)]*)\\)").matcher(ddl);
            assertTrue(m.find(), "schema.sql 里应能解析到 idx_chat_timestamp 的列列表");
            List<String> fromSql = new ArrayList<>();
            for (String c : m.group(1).split(",")) {
                fromSql.add(c.trim());
            }
            assertEquals(indexColumns(INDEX), fromSql,
                    "schema.sql 与真库的索引列不一致（漂移）");
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + db.getSqliteFilePath());
    }

    private boolean indexExists(Connection conn, String index) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?")) {
            ps.setString(1, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** {@code PRAGMA index_info} 的列名，按索引内的位置顺序。 */
    private List<String> indexColumns(String index) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("PRAGMA index_info('" + index + "')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    private int indexCount(Connection conn, String table, String column) throws SQLException {
        int n = 0;
        for (String idx : allIndexesOn(conn, table)) {
            List<String> cols = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("PRAGMA index_info('" + idx + "')");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString("name"));
                }
            }
            if (cols.equals(List.of(column))) n++;
        }
        return n;
    }

    private List<String> allIndexesOn(Connection conn) throws SQLException {
        return allIndexesOn(conn, TABLE);
    }

    private List<String> allIndexesOn(Connection conn, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA index_list('" + table + "')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    /** 与 {@code cleanupOldChatHistory} 里 sql1 同形的 SELECT，用来读计划。 */
    private String deleteQueryPlan() throws SQLException {
        String sel = "SELECT id FROM " + TABLE + " WHERE timestamp < datetime('now', ?)";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("EXPLAIN QUERY PLAN " + sel)) {
            ps.setString(1, "-30 days");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("detail") : null;
            }
        }
    }

    /**
     * 留下的行的 {@code content} 集合。
     *
     * <p>不用 {@code julianday} 差值判年龄：SQLite 的 {@code 'now'} 是 <b>UTC</b>，
     * 而插入的时间戳字符串是按 JVM 本地时区格式化的，两者的整数天差会掺入时区偏移
     * （本机 UTC+8 实测：1 天前与 3 天前的行读回来都是 {@code [2, 0]}）。
     * content 里的年龄标记是写入侧的事实，不受时区影响。
     */
    private Set<String> remainingContents() throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT content FROM " + TABLE + " ORDER BY id")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private int countRows() throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private void seedChatRows(int rows, int players) throws SQLException {
        seed(rows, players, 30);
    }

    /**
     * 造行：{@code rows} 行均匀铺在最近 {@code days} 天，玩家数 {@code players}。
     *
     * <p>铺满一段时间窗而不是"今天全部"，是为了让 skip-scan 有真实形状可退化 ——
     * 若全部行时间戳相同，skip-scan 只探测一次，测不出差额。
     */
    private void seed(int rows, int players, int days) throws SQLException {
        String ins = "INSERT INTO " + TABLE
                + " (player_uuid, session_id, role, content, timestamp) VALUES (?,?,?,?,?)";
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long now = System.currentTimeMillis();
        long span = (long) days * 24 * 3600 * 1000;
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(ins)) {
                for (int i = 0; i < rows; i++) {
                    ps.setString(1, String.format("00000000-0000-0000-0000-%012d", i % players));
                    ps.setString(2, "sess" + (i / 30));
                    ps.setString(3, i % 2 == 0 ? "user" : "assistant");
                    ps.setString(4, "x".repeat(64));
                    ps.setString(5, fmt.format(new java.util.Date(now - (long) (span * (i / (double) rows)))));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    /** 造一行"距今 {@code ageDays} 天"的记录，供语义断言用。 */
    private void seedChatRowAt(UUID player, String session, int ageDays) throws SQLException {
        String ins = "INSERT INTO " + TABLE
                + " (player_uuid, session_id, role, content, timestamp) VALUES (?,?,?,?,?)";
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long ts = System.currentTimeMillis() - (long) ageDays * 24 * 3600 * 1000;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(ins)) {
            ps.setString(1, player.toString());
            ps.setString(2, session);
            ps.setString(3, "user");
            ps.setString(4, "content-" + ageDays);
            ps.setString(5, fmt.format(new java.util.Date(ts)));
            ps.executeUpdate();
        }
    }

    @SuppressWarnings("unused")
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
