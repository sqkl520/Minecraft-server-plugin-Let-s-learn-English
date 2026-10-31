package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 排行榜索引与 SQLite PRAGMA 的<b>源码级 lint</b>（不是行为测试）。
 *
 * <h2>为什么这一组必须是 lint</h2>
 * 这四个索引与三条 PRAGMA 的价值全部是「<b>性能</b>」，而性能在单元测试里
 * <b>结构性不可观测</b>：
 * <ul>
 *   <li>测试库是几行的内存 SQLite，缺索引照样 0.1 ms —— 全表扫与索引扫的差距
 *       要在 10 万行 / 100 万行规模才显现（实测 315.77 ms vs 0.65 ms，
 *       1331.6 ms vs 数十 ms）；</li>
 *   <li>{@code initializeSQLite()} 要真实 {@code DriverManager} + 真实数据目录，
 *       在纯 JVM 里要么跑不起来，要么跑了也只在临时文件上建库；</li>
 *   <li>最关键的是：<b>删掉任意一条 {@code createIndexIfMissing} 调用，全仓 497 个
 *       测试全绿</b>（本类作者实测）。插件照常启动，功能全对，只是万人服上
 *       广场一开就卡 1.3 秒 —— 服主只会觉得「这插件有点卡」，不会报 bug。</li>
 * </ul>
 *
 * <p>这正是本项目 MEMORY 记录的「假绿」形态之一：<b>闸门未触及</b>。行为测试
 * 触不到索引，只有读源码能钉住它。
 *
 * <h2>四条 lint</h2>
 * <ol>
 *   <li><b>L1</b> 四个排行榜索引各恰好被注册 1 次，且目标列名正确；</li>
 *   <li><b>L2</b> 索引列名与查询里的 ORDER BY 列对应（防止「索引建在 A 列、
 *       查询按 B 列排序」这种静默无效）；</li>
 *   <li><b>L3</b> 三条 PRAGMA 都存在，且 {@code journal_mode} 用
 *       {@code executeQuery} 读掉结果行（用 {@code executeUpdate} 会抛
 *       "query returns results"，而 PRAGMA 失败被 catch 成 WARNING —— 库照常可用，
 *       只是性能归零，同样是个静默退化）；</li>
 *   <li><b>L4</b> 既有的 {@code idx_player_data_name} 没有被误删。</li>
 * </ol>
 *
 * <p><b>与其它 Lint 类零耦合</b>：{@code stripComments} / {@code sourceOf} 等工具
 * 在此独立实现一遍，理由同 {@code ServerToggleLintsTest} 的类注释 —— 共用工具会
 * 让若干组 lint 同时因一处语法边缘情况静默失效。
 */
@DisplayName("IndexAndPragmaLints — 索引/PRAGMA 源码 lint")
class IndexAndPragmaLintsTest {

    private static final String DB_MANAGER =
            "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java";
    private static final String STORE =
            "src/main/java/com/letslearnenglish/minecraftplugin/data/PlayerDataStore.java";

    // ------------------------------------------------------------------
    // 工具（本类独立实现，不与其他 lint 类共用）
    // ------------------------------------------------------------------

    /** 剥掉 {@code //} 行注释与块注释，避免注释里的示例文本被当成代码锚点。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        boolean inLine = false;
        boolean inBlock = false;
        boolean inString = false;
        boolean inTextBlock = false;
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                i++;
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i += 2;
                    continue;
                }
                if (c == '\n') out.append(c);
                i++;
                continue;
            }
            if (inTextBlock) {
                if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                    inTextBlock = false;
                    i += 3;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            if (inString) {
                if (c == '\\' && i + 1 < n) {
                    out.append(c).append(next);
                    i += 2;
                    continue;
                }
                if (c == '"') inString = false;
                out.append(c);
                i++;
                continue;
            }
            if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                inTextBlock = true;
                i += 3;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '/' && next == '/') {
                inLine = true;
                i += 2;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String sourceOf(String relPath) {
        Path p = Paths.get(relPath);
        if (!Files.exists(p)) {
            fail("源码文件不存在：" + p.toAbsolutePath());
        }
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("读取失败：" + p + " -> " + e.getMessage());
        }
    }

    /**
     * 抽取 {@code createIndexIfMissing("name", "table(col, ...)")} 的 (索引名, 目标) 对。
     *
     * <p>必须做**配对括号**扫描而不是找第一个 {@code )}：目标是
     * {@code "player_data(total_score)"} 这种<b>带括号的字符串</b>，找第一个右括号
     * 会在 {@code player_data(total_score} 处截断，然后与 {@code "player_data(total_score)"}
     * 比较永远不等 —— 本类的第一次实现就栽在这里（4 条 L1 全红但索引其实都在）。
     */
    private static List<String[]> indexCalls(String code) {
        String stripped = stripComments(code);
        List<String[]> found = new ArrayList<>();
        int idx = 0;
        String needle = "createIndexIfMissing(";
        while ((idx = stripped.indexOf(needle, idx)) >= 0) {
            int start = idx + needle.length();
            // 配平括号：从 '(' 之后起数，遇到 ( 加一、) 减一，归零处即调用结束
            int depth = 1;
            int i = start;
            boolean inStr = false;
            while (i < stripped.length() && depth > 0) {
                char c = stripped.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                } else if (c == '"') {
                    inStr = true;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) break;
                }
                i++;
            }
            found.add(new String[] {stripped.substring(start, i).replaceAll("\\s+", " ").trim()});
            idx = i;
        }
        return found;
    }

    /** 某个索引名对应的目标串（去掉名字部分），找不到返回 null。 */
    private static String targetOf(List<String[]> calls, String indexName) {
        for (String[] c : calls) {
            if (c[0].contains("\"" + indexName + "\"")) {
                // 形如: "idx_x", "player_data(total_score)"
                int firstQuoteEnd = c[0].indexOf('"', c[0].indexOf('"') + 1);
                return c[0].substring(firstQuoteEnd + 1);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // L1：四个排行榜索引各恰好注册一次
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("L1 四个排行榜索引各注册一次")
    class LeaderboardIndexesRegistered {

        private List<String[]> calls() {
            return indexCalls(sourceOf(DB_MANAGER));
        }

        private void assertRegisteredOnce(String indexName, String expectedColumn) {
            List<String[]> calls = calls();
            long count = calls.stream().filter(c -> c[0].contains("\"" + indexName + "\"")).count();
            assertEquals(1, count, "索引 " + indexName + " 应当恰好注册 1 次，实际 " + count
                    + " 次。删掉它不会有任何测试变红，但万人服广场会从 0.65 ms 退化成 315 ms。"
                    + "现有调用：" + describe(calls));
            String target = targetOf(calls, indexName);
            assertTrue(target != null && target.contains("player_data(" + expectedColumn + ")"),
                    "索引 " + indexName + " 的目标列应为 player_data(" + expectedColumn
                            + ")，实际目标：" + target + "。全部调用：" + describe(calls));
        }

        private String describe(List<String[]> calls) {
            StringBuilder sb = new StringBuilder("[");
            for (String[] c : calls) sb.append(c[0]).append("; ");
            return sb.append(']').toString();
        }

        @Test
        @DisplayName("idx_player_data_score -> total_score")
        void score() {
            assertRegisteredOnce("idx_player_data_score", "total_score");
        }

        @Test
        @DisplayName("idx_player_data_coins -> total_coins_earned")
        void coins() {
            assertRegisteredOnce("idx_player_data_coins", "total_coins_earned");
        }

        @Test
        @DisplayName("idx_player_data_words -> total_words_learned")
        void words() {
            assertRegisteredOnce("idx_player_data_words", "total_words_learned");
        }

        @Test
        @DisplayName("idx_player_data_streak -> longest_streak")
        void streak() {
            assertRegisteredOnce("idx_player_data_streak", "longest_streak");
        }
    }

    // ------------------------------------------------------------------
    // L2：索引列必须与 ORDER BY 列对应
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("L2 索引列与 ORDER BY 列对应")
    class IndexMatchesOrderBy {

        /**
         * 直接读 schema.sql 里的列名与两个数据类里的 ORDER BY，交叉断言。
         *
         * <p>这一条防的是「索引建在 A 列、查询按 B 列排序」—— 这种错法下
         * L1 全绿、SQL 也能跑，只是索引<b>完全用不上</b>，等于白建。
         *
         * <p><b>排序列分散在两个文件</b>：{@code total_coins_earned} /
         * {@code total_words_learned} / {@code longest_streak} 在 {@code DatabaseManager}，
         * 而 {@code total_score} 在 {@code PlayerDataStore}。只扫一个文件会漏掉另一个
         * （本方法的第一次实现就漏了 total_score）。
         */
        @Test
        @DisplayName("每个榜单 ORDER BY 列都有同名索引")
        void everyOrderByColumnHasIndex() {
            String dbCode = stripComments(sourceOf(DB_MANAGER));
            String storeCode = stripComments(sourceOf(STORE));
            String all = dbCode + "\n" + storeCode;

            String[] known = {"total_score", "total_coins_earned", "total_words_learned", "longest_streak"};
            List<String> orderByCols = new ArrayList<>();
            for (String col : known) {
                if (all.contains("ORDER BY d." + col + " DESC")
                        || all.contains("ORDER BY " + col + " DESC")) {
                    orderByCols.add(col);
                }
            }
            assertEquals(4, orderByCols.size(),
                    "四个榜单排序列应当都能找到（total_score / total_coins_earned / "
                            + "total_words_learned / longest_streak），实际找到 " + orderByCols
                            + "。若查询被改名，本测试与索引都要同步更新。");

            List<String[]> calls = indexCalls(dbCode);
            for (String col : orderByCols) {
                boolean covered = false;
                for (String[] c : calls) {
                    String name = c[0].substring(c[0].indexOf('"') + 1,
                            c[0].indexOf('"', c[0].indexOf('"') + 1));
                    String t = targetOf(calls, name);
                    if (t != null && t.contains("player_data(" + col + ")")) {
                        covered = true;
                        break;
                    }
                }
                assertTrue(covered, "ORDER BY 用了 " + col + " 却没有 player_data(" + col
                        + ") 索引 —— 查询会退化成全表扫 + 临时 B 树排序（实测 315 ms vs 0.65 ms）。");
            }
        }
    }

    // ------------------------------------------------------------------
    // L3：SQLite PRAGMA
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("L3 SQLite PRAGMA 存在且用法正确")
    class SqlitePragmas {

        @Test
        @DisplayName("三条 PRAGMA 都在")
        void allThreePresent() {
            String code = stripComments(sourceOf(DB_MANAGER));
            assertTrue(code.contains("PRAGMA journal_mode=WAL"),
                    "缺少 journal_mode=WAL：默认 rollback journal 下写事务持整库锁，读会排队，"
                            + "写性能差数十倍。");
            assertTrue(code.contains("PRAGMA synchronous=NORMAL"),
                    "缺少 synchronous=NORMAL：WAL 下的推荐搭配，每次提交都 fsync 会让写吞吐掉一个量级。");
            assertTrue(code.contains("PRAGMA busy_timeout=5000"),
                    "缺少 busy_timeout：并发写撞锁会立刻抛 SQLITE_BUSY 而不是等待重试。");
        }

        /**
         * {@code journal_mode} 是唯一会<b>返回结果行</b>的 PRAGMA。
         * 用 {@code executeUpdate} 调它会抛 SQLException（"query returns results"），
         * 而整个 PRAGMA 块被 catch 成 WARNING —— 库照常可用、测试全绿，
         * <b>只是性能优化全部失效</b>。这是本组最隐蔽的一种退化。
         */
        @Test
        @DisplayName("journal_mode 用 executeQuery 读掉结果行")
        void journalModeUsesQuery() {
            String code = stripComments(sourceOf(DB_MANAGER));
            int pragma = code.indexOf("PRAGMA journal_mode=WAL");
            assertTrue(pragma > 0, "journal_mode=WAL 不存在（见上一条测试）");

            // 取该行前后 200 字符窗口，确认拿它的是 executeQuery 而不是 executeUpdate
            int from = Math.max(0, pragma - 200);
            int to = Math.min(code.length(), pragma + 200);
            String window = code.substring(from, to);
            assertTrue(window.contains("executeQuery"),
                    "journal_mode 必须用 executeQuery 调用（它会返回一行结果），"
                            + "用 executeUpdate 会抛 'query returns results' 并被 catch 成 WARNING —— "
                            + "性能优化静默失效。窗口原文：\n" + window);
            assertTrue(!window.contains("executeUpdate(\"PRAGMA journal_mode"),
                    "journal_mode 不能用 executeUpdate 调用。窗口原文：\n" + window);
        }

        /** PRAGMA 失败不得阻断启动（否则一个老库文件就能让插件起不来）。 */
        @Test
        @DisplayName("PRAGMA 失败只记 WARNING 不抛出")
        void pragmaFailureDoesNotAbortStartup() {
            String code = stripComments(sourceOf(DB_MANAGER));
            int pragma = code.indexOf("PRAGMA journal_mode=WAL");
            assertTrue(pragma > 0, "journal_mode=WAL 不存在");

            // 从 PRAGMA 起点往后找最近的 catch 块，并且只取**那一个** catch 体。
            // 范围必须收窄到「配平后的花括号内」：本仓库同一个方法里还有
            // Class.forName 的 throw new SQLException，窗口放宽就会误抓到隔壁的 throw
            // （本类的第一次实现就是这么假红的）。
            int catchIdx = code.indexOf("catch (SQLException e)", pragma);
            assertTrue(catchIdx > 0 && catchIdx - pragma < 3000,
                    "PRAGMA 块附近应有 catch (SQLException) 兜住失败");

            int braceOpen = code.indexOf('{', catchIdx);
            assertTrue(braceOpen > 0, "catch 块应带花括号");
            int depth = 1;
            int i = braceOpen + 1;
            while (i < code.length() && depth > 0) {
                char c = code.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                if (depth == 0) break;
                i++;
            }
            String catchBody = code.substring(braceOpen + 1, i);
            assertTrue(!catchBody.contains("throw "),
                    "PRAGMA 的 catch 体里不得 rethrow：PRAGMA 是性能优化，失败不该让插件起不来。"
                            + "catch 体原文：\n" + catchBody);
        }

        /**
         * PRAGMA 必须挂在**唯一**的 SQLite 连接创建点上（红队 E2）。
         *
         * <p>{@code journal_mode} 持久化在库文件里，但 {@code synchronous} 与
         * {@code busy_timeout} 是**连接级**的——新建连接就得重设。当前
         * {@code getConnection()} → {@code initialize()} → {@code initializeSQLite()}
         * 这条路会在自愈重连时重新执行 PRAGMA，所以没问题；但如果将来有人把连接创建
         * 挪到别处（看起来像个无害的重构），PRAGMA 就会在重连后**静默消失**——
         * 库照常工作、测试全绿，只是 WAL 和写性能都没了。
         *
         * <p>所以这里钉住：{@code jdbc:sqlite:} 在 DatabaseManager 里只允许出现两处——
         * 主连接（就在 {@code initializeSQLite} 内）与备份完整性校验的独立只读连接。
         * 出现第三处即说明有新的连接创建点绕过了 PRAGMA。
         */
        @Test
        @DisplayName("jdbc:sqlite 只出现在主连接与备份校验两处，PRAGMA 不会被绕开")
        void sqliteConnectionsAreAccountedFor() {
            String code = stripComments(sourceOf(DB_MANAGER));
            int count = 0;
            int idx = 0;
            while ((idx = code.indexOf("jdbc:sqlite:", idx)) >= 0) {
                count++;
                idx += "jdbc:sqlite:".length();
            }
            assertEquals(2, count,
                    "DatabaseManager 里 jdbc:sqlite: 应恰好出现 2 次（主连接 + 备份完整性校验的只读连接），"
                            + "实际 " + count + " 次。新增的 SQLite 连接点若没应用 "
                            + "journal_mode/synchronous/busy_timeout，重连后这些 PRAGMA 会静默失效。");

            // 主连接必须落在 initializeSQLite 里（PRAGMA 紧跟其后）
            int initSqlite = code.indexOf("private void initializeSQLite()");
            assertTrue(initSqlite > 0, "initializeSQLite() 不存在");
            int firstJdbc = code.indexOf("jdbc:sqlite:");
            assertTrue(firstJdbc > initSqlite && firstJdbc - initSqlite < 800,
                    "第一个 jdbc:sqlite: 不在 initializeSQLite() 方法体内 —— 主连接被挪走了，"
                            + "PRAGMA 很可能没跟着走");
        }
    }

    // ------------------------------------------------------------------
    // L4：既有索引不得误删
    // ------------------------------------------------------------------

    @Test
    @DisplayName("L4 既有的 idx_player_data_name 未被误删")
    void existingNameIndexSurvives() {
        List<String[]> calls = indexCalls(sourceOf(DB_MANAGER));
        boolean present = calls.stream().anyMatch(c -> c[0].contains("\"idx_player_data_name\""));
        assertTrue(present,
                "idx_player_data_name 是 getKnownPlayerNames / tab 补全用的既有索引，"
                        + "新增四个榜单索引时不得把它去掉。");
    }
}
