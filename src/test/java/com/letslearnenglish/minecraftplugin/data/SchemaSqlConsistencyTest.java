package com.letslearnenglish.minecraftplugin.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * R14-live：用<b>真实 SQLite</b> 对 {@code schema.sql} 建出的库执行清理语句，
 * 钉住「SQL 文本引用的列名必须真实存在」。
 *
 * <h2>这条测试为什么必须存在（它是真实起服才发现的缺陷）</h2>
 * <p>round14 用 Paper 1.21.4 真实起服时，全新数据库第一次启动就打出一条 WARNING 堆栈：
 * <pre>
 * org.sqlite.SQLiteException: [SQLITE_ERROR] SQL error or missing database
 *     (no such column: claim_date)
 *     at DatabaseManager.cleanupOldChatHistory(DatabaseManager.java:1028)
 * </pre>
 * 原因：{@code schema.sql} 给 {@code daily_word_reward_claims} 建的列叫
 * {@code challenge_date}，而清理语句写的是 {@code WHERE claim_date < ...}。
 * SQLite 在 <b>prepare 阶段</b>就报错，所以这条「清理旧奖励台账」从上线第一天起
 * <b>一次都没成功过</b>，而且每个新库启动都会刷一条假的"数据库故障" ——
 * 运维会以为是数据库坏了。
 *
 * <p><b>为什么 1000+ 个单测全绿却没抓到</b>：
 * <ul>
 *   <li>所有 DB 测试都用 {@code schema.sql} 建表，但<b>没有一条真正执行
 *       {@code cleanupOldChatHistory}</b> —— 它挂在启动异步任务里；</li>
 *   <li>「SQL 文本与 DDL 是否一致」是<b>跨文件</b>约束：SQL 在 Java 字符串里，
 *       DDL 在 resources 里，编译器两边都不看；</li>
 *   <li>mock 一个 {@code DatabaseManager} 只会验证「调用了什么」，不会验证
 *       「这条 SQL 能不能被 SQLite 解析」。</li>
 * </ul>
 *
 * <h2>为什么用真 SQLite 而不是文本比对</h2>
 * <p>文本比对识别不了列别名（{@code MAX(timestamp) AS last_time} 里的
 * {@code last_time} 是合法的）和子查询作用域，会产生大量误报 —— 我写过一个启发式
 * 扫描器，24 个站点报了 11 条，其中 10 条是这种误报。让 SQLite 自己解析是唯一
 * 权威的判据：它说列不存在，就是不存在。
 */
class SchemaSqlConsistencyTest {

    private static final Path SCHEMA = Path.of("src/main/resources/schema.sql");
    /** 测试库表前缀；与生产一致的非空值，便于暴露前缀拼接错误。 */
    private static final String PREFIX = "lle_";

    /**
     * 从<b>生产源码</b>实时提取清理语句，而不是在本测试里抄一份。
     *
     * <p><b>为什么必须这样（wave-3 锐评 GAP-3 的教训）</b>：第一版把两条 SQL 抄成常量。
     * 结果把生产代码里的 `coin_date` 改成 `claim_coin_date` 后，测试<b>仍然全绿</b> ——
     * 因为它验证的是「我抄的那份」，不是「生产实际用的那份」。这种测试不但无效，
     * 还比没测试更危险：它给出「已验证」的假信号。
     *
     * <p>做法：读 {@code DatabaseManager.java} 源文本，定位
     * {@code String sqlN = "DELETE FROM " + tablePrefix + "<table>" + " WHERE <col> < " + coinDateExpr;}
     * 这两处赋值，把字符串字面量与常量拼接还原成可执行 SQL（{@code tablePrefix} 换成测试前缀，
     * {@code coinDateExpr} 换成 SQLite 分支的真实表达式）。
     *
     * <p>还原规则刻意写得很窄：只处理「字面量 + tablePrefix + 字面量 + coinDateExpr」这一种形状。
     * 一旦生产代码结构变了，本方法会<b>断言失败</b>而不是悄悄跳过 —— 让人回来更新提取逻辑，
     * 而不是让测试再次退化成一个永不执行的空壳。
     */
    private static List<String> extractCleanupStatementsFromSource() throws IOException {
        Path src = Path.of("src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java");
        assertTrue(Files.exists(src), "应能找到 DatabaseManager: " + src.toAbsolutePath());
        String text = Files.readString(src);

        // 生产里保留期清理语句实测有三种书写变体（探针打出来的真实形态）：
        //   [" + " WHERE timestamp < " + dateExpr]       ← 表名后先关引号，再单独拼 WHERE
        //   [ WHERE created_at < " + dateExpr]           ← 表名后直接接 WHERE，值在引号外
        //   [ WHERE created_at < ?"]                     ← 阈值绑 long，整体在一对引号里
        // 所以锚定「tablePrefix 之后的整段尾部」再从中抽列名，比逐变体写正则稳。
        String flat = text.replaceAll("\\s*\\n\\s*", " ").replaceAll("\\s+", " ");

        List<String> out = new ArrayList<>();
        Pattern stmt = Pattern.compile(
                "String sql\\w* = \"DELETE FROM \" \\+ tablePrefix \\+ \"(\\w+)([^;]*?);");
        Matcher m = stmt.matcher(flat);
        while (m.find()) {
            String table = m.group(1);
            String tail = m.group(2);
            // 只取保留期清理：形如 "... WHERE <col> < ..."（< 号是关键，等值删除不算）
            Matcher col = Pattern.compile("WHERE (\\w+) <").matcher(tail);
            if (!col.find()) {
                continue; // 等值删除（difficult_words / class_members 之类），不是保留期清理
            }
            out.add("DELETE FROM " + PREFIX + table + " WHERE " + col.group(1) + " < ?");
        }

        assertTrue(!out.isEmpty(),
                "一条清理语句都没从生产源码里提取到 —— 正则失效或代码结构变了，"
                        + "本测试此时已退化为空壳，必须修好");
        assertTrue(out.size() >= 4,
                "提取到的清理语句只有 " + out.size() + " 条，疑似漏抓: " + out);
        return out;
    }

    @Test
    @DisplayName("schema.sql 在 SQLite 上可执行，且清理语句的列名都真实存在")
    void cleanupStatementsParseAgainstRealSchema() throws Exception {
        assertTrue(Files.exists(SCHEMA), "应能找到 schema.sql: " + SCHEMA.toAbsolutePath());
        String ddl = Files.readString(SCHEMA);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            applySchema(conn, ddl);
            // R14（wave-3 锐评 GAP-3）：懒建表不在 schema.sql 里，但**不能因此跳过校验**。
            // 原实现遇到表不存在就 continue，于是 sql5（daily_coin_counters）从没被
            // 任何断言覆盖 —— 把 coin_date 拼错成 claim_coin_date 后三条断言仍全绿。
            // 「测试内部有块永不执行的区域」正是它要防的那类缺陷。
            // 现在把懒建表的 DDL 就地补建（与 RewardEngine 的建表语句保持一致），
            // 让两条清理语句都真正被 SQLite 解析。
            applyLazyTable(conn, "lle_daily_coin_counters",
                    "CREATE TABLE IF NOT EXISTS lle_daily_coin_counters ("
                            + " player_uuid VARCHAR(36) NOT NULL,"
                            + " coin_date DATE NOT NULL,"
                            + " cents BIGINT NOT NULL DEFAULT 0,"
                            + " UNIQUE(player_uuid, coin_date))");

            for (String sql : extractCleanupStatementsFromSource()) {
                String table = tableOf(sql);
                assertTrue(tableExists(conn, table),
                        "清理语句引用的表 " + table + " 必须在上面的建表之后存在，"
                                + "否则本断言形同跳过（GAP-3 的复现条件）");
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, "-30 days");
                    // 不关心删了几行，只关心"能不能被 SQLite 解析"。
                    ps.executeUpdate();
                } catch (SQLException e) {
                    fail("清理语句在真实 schema 上无法执行（列名/表名与 DDL 不一致）:\n  "
                            + sql + "\n  → " + e.getMessage());
                }
            }
        }
    }

    /** 执行一条懒建表的 DDL（表不在 schema.sql 里，由业务代码按需创建）。 */
    private static void applyLazyTable(Connection conn, String table, String ddl) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            fail("懒建表 " + table + " 的 DDL 无法执行（与本测试抄写的定义不一致？）:\n  "
                    + ddl + "\n  → " + e.getMessage());
        }
        assertTrue(tableExists(conn, table), "懒建表 " + table + " 建完仍不存在");
    }

    /** 从 {@code DELETE FROM <table> WHERE ...} 里取表名。 */
    private static String tableOf(String sql) {
        Matcher m = Pattern.compile("DELETE\\s+FROM\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(m.find(), "无法解析表名: " + sql);
        return m.group(1);
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 结构 lint：生产代码里那两条清理语句必须用 DDL 里真实存在的列名。
     *
     * <p>上面那条行为测试证明「按我抄的 SQL 能跑」；这条证明「生产里写的也是这个」。
     * 两条合起来才闭环 —— 少任何一条，都可能出现「测试绿但生产仍错」。
     */
    @Test
    @DisplayName("DatabaseManager 的清理语句用的是 challenge_date / coin_date，不是 claim_date")
    void productionCleanupSqlUsesChallengeDate() throws Exception {
        Path src = Path.of("src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java");
        assertTrue(Files.exists(src), "应能找到 DatabaseManager: " + src.toAbsolutePath());
        String text = Files.readString(src);

        assertTrue(text.contains("daily_word_reward_claims"),
                "应能找到 daily_word_reward_claims 的清理语句");
        assertTrue(text.contains("WHERE challenge_date <"),
                "清理 daily_word_reward_claims 必须用 challenge_date（schema.sql 的真实列名）");

        // 旧缺陷形态：claim_date 这个列在任何表里都不存在。
        assertTrue(!text.contains("WHERE claim_date <"),
                "`claim_date` 在 schema.sql 里不存在 —— SQLite 会在 prepare 阶段抛"
                        + " `no such column: claim_date`，该清理从此永不生效");
    }

    @Test
    @DisplayName("schema.sql 里 daily_word_reward_claims 的列确实是 challenge_date")
    void ddlHasColumn() throws IOException {
        Map<String, Set<String>> tables = parseSchema(Files.readString(SCHEMA));
        Set<String> cols = tables.get("daily_word_reward_claims");
        assertTrue(cols != null, "schema.sql 里应有 daily_word_reward_claims 表: " + tables.keySet());
        assertTrue(cols.contains("challenge_date"),
                "该表应有 challenge_date 列，实际: " + cols);
        assertTrue(!cols.contains("claim_date"),
                "该表**不应**有 claim_date 列（这是原缺陷的来源）");
    }

    // ---- helpers ----

    /**
     * 把 schema.sql 的 {prefix} 占位符替换成测试前缀，并逐条执行。
     *
     * <p><b>顺序是硬要求：先删注释，再按分号切分。</b>反过来的话，注释里出现的分号
     * 会把一条语句劈成两半 —— 本仓 {@code schema.sql} 的 {@code seasons} 表上方就有
     * 一句「{@code season_id derived from ...}」的注释含分号，实测会切出一个
     * {@code season_id derived from} 的假语句，报 {@code near "season_id": syntax error}。
     * 第一版就是这个顺序错的，报了假失败。
     */
    private static void applySchema(Connection conn, String ddl) throws SQLException {
        String sql = stripComments(ddl.replace("{prefix}", PREFIX));
        for (String stmt : sql.split(";")) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            try (Statement st = conn.createStatement()) {
                st.execute(trimmed);
            } catch (SQLException e) {
                fail("schema.sql 语句无法执行:\n  " + trimmed + "\n  → " + e.getMessage());
            }
        }
    }

    /**
     * 去掉 SQL 注释，<b>包括行尾注释</b>。
     *
     * <p>{@code schema.sql} 大量使用「列定义 + 行尾 {@code -- 说明}」的写法，
     * 例如 {@code season_id INT PRIMARY KEY,            -- 1-based}。
     * 第一版只删整行注释，行尾注释跟着进了 SQLite，导致 {@code season_id derived from}
     * 这种被截断的语句 —— 那是测试自身的问题，不是 schema 的问题。
     *
     * <p>这里不追求完整 SQL 词法（不处理引号内的 {@code --}），因为本仓 schema
     * 里没有含双连字符的字符串字面量；真出现时这条 helper 需要升级为词法扫描。
     */
    private static String stripComments(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            int dash = line.indexOf("--");
            String body = dash >= 0 ? line.substring(0, dash) : line;
            if (body.trim().isEmpty()) continue;
            sb.append(body).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, Set<String>> parseSchema(String schema) {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "CREATE TABLE IF NOT EXISTS \\{prefix\\}(\\w+)\\s*\\((.*?)\\n\\);",
                Pattern.DOTALL).matcher(schema);
        while (m.find()) {
            Set<String> cols = new LinkedHashSet<>();
            for (String line : m.group(2).split("\n")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("--")) continue;
                String up = s.toUpperCase();
                if (up.startsWith("UNIQUE") || up.startsWith("PRIMARY") || up.startsWith("FOREIGN")
                        || up.startsWith("INDEX") || up.startsWith("KEY") || up.startsWith("CONSTRAINT")) {
                    continue;
                }
                cols.add(s.split("\\s+")[0].replace(",", "").toLowerCase());
            }
            tables.put(m.group(1).toLowerCase(), cols);
        }
        return tables;
    }

    // 防未使用告警
    @SuppressWarnings("unused")
    private static final List<String> DOC = new ArrayList<>();
}
