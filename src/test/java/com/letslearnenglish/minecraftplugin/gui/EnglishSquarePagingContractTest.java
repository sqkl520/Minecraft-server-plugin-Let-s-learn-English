package com.letslearnenglish.minecraftplugin.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P-SQ-PAGE：广场分页契约的<b>结构 lint</b>（不起 Bukkit、不连库）。
 *
 * <p><b>为什么需要它</b>：这次修的是"主线程一次取全库"。它有三个只在组合时才成立的
 * 不变式，任何一条单独被改回去都不会编译报错、也不会有运行时异常：
 * <ol>
 *   <li><b>GUI 不得再传 LIMIT 无穷大</b>：{@code getActiveSquareEntries(Integer.MAX_VALUE)}
 *       一旦回归，性能缺陷原地复活（1.3 s 主线程冻结），而所有功能测试仍绿；</li>
 *   <li><b>切片 / 全量的下标公式必须成对</b>：GUI 的 {@code renderPlayerHeads} 用
 *       {@code listStart=0} 读切片，监听器就必须用 {@code slotIdx} 反查；两边各改一半
 *       （只改一边）会让"第 2 页点谁都点不中"或"点 A 打开 B"——都没有日志；</li>
 *   <li><b>总页数必须来自 COUNT</b>：若又改回"列表 size"，切片下永远只有 1 页，
 *       表现为"下一页按钮消失"，同样是静默的。</li>
 * </ol>
 *
 * <p><b>为什么是"读源码"而不是行为测试</b>：这三条是跨两个类的<b>协议</b>，
 * 行为测试要么需要真 Bukkit 背包（MockBukkit 不在依赖里），要么得把两个类都 mock 到
 * 面目全非、断言的是 mock 而不是协议。结构 lint 直接钉住"协议两侧的形态"，成本极低
 * 且不会误绿。局限也写明：它证明"形态对"，不证明"数值对"——数值由
 * {@code ActiveSquarePagingTest}（真 SQLite）负责。
 */
class EnglishSquarePagingContractTest {

    private static final Pattern WS = Pattern.compile("\\s+");
    /**
     * 相邻字符串字面量的拼接胶水：{@code " ... " + <换行/缩进> " ... "} → {@code " ...... "}。
     * 只吃掉"引号 + 加号 + 引号"这一小段，不动字面量内部内容。
     */
    private static final Pattern ADJACENT_LITERALS = Pattern.compile("\"\\s*\\+\\s*\"");

    /** 仓库根：从工作目录向上找 {@code src/main/java} 存在的那一层。 */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("src/main/java"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（向上 6 层都没有 src/main/java）");
    }

    private static String read(String relative) throws IOException {
        Path file = repoRoot().resolve(relative);
        assertTrue(Files.isRegularFile(file), "被 lint 的源文件不存在：" + file);
        return stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 去掉行注释与块注释（含 Javadoc）——注释里大量提到这些常量名，不剥会被自己的文档骗绿。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < src.length()) {
                    char d = src.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < src.length()) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String collapse(String src) {
        // SQL 在源码里是多段字符串拼接（{@code "... <> 0 " + "AND ..."}）。先合并相邻
        // 字符串字面量，否则搜 SQL 片段必须逐字复刻换行与 {@code + "..."} 胶水 ——
        // 那种 lint 一改排版就假红，等于逼后人删测试。
        String joined = ADJACENT_LITERALS.matcher(src).replaceAll("");
        return WS.matcher(joined).replaceAll(" ");
    }

    // ------------------------------------------------------------------
    // 用例 1：GUI 不得全量取行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GUI 不再传 Integer.MAX_VALUE 给 getActiveSquareEntries（分页必须走 LIMIT/OFFSET）")
    void guiDoesNotRequestUnboundedRows() throws IOException {
        String gui = collapse(read(
                "src/main/java/com/letslearnenglish/minecraftplugin/gui/EnglishSquareGUI.java"));

        assertFalse(gui.contains("getActiveSquareEntries(Integer.MAX_VALUE")
                        || gui.contains("getActiveSquareEntries( Integer.MAX_VALUE"),
                "EnglishSquareGUI 又在用 Integer.MAX_VALUE 取全量广场行 —— 这是主线程冻结的"
                        + "直接成因（10k 玩家实测 1331.6 ms ≈ 26.6 tick）。分页必须用"
                        + " getActiveSquareEntries(PLAYERS_PER_PAGE, (page-1)*PLAYERS_PER_PAGE)");

        assertTrue(gui.contains("getActiveSquareEntries(PLAYERS_PER_PAGE,")
                        || gui.contains("getActiveSquareEntries( PLAYERS_PER_PAGE,"),
                "EnglishSquareGUI 未按页容量取行：应调用 getActiveSquareEntries(PLAYERS_PER_PAGE, offset)");

        // R2（红队 A2）：光断言「传了 PLAYERS_PER_PAGE」不够 —— offset 若是常量 0，
        // 第 2 页会渲染第 1 页的内容（"翻页即错"），而上面两条断言照样全绿。
        // 必须钉住 offset 表达式里真的含 page。
        assertTrue(gui.contains("getActiveSquareEntries(PLAYERS_PER_PAGE, (page - 1) * PLAYERS_PER_PAGE)"),
                "取页的 offset 必须由页码算出：getActiveSquareEntries(PLAYERS_PER_PAGE, "
                        + "(page - 1) * PLAYERS_PER_PAGE)。若 offset 是常量 0，第 2 页会渲染第 1 页的行 —— "
                        + "没有任何日志，玩家只会觉得「翻页没反应」");

        assertTrue(gui.contains("countActiveSquareEntries()"),
                "EnglishSquareGUI 必须用 countActiveSquareEntries() 算总页数 —— "
                        + "只用本页 28 行算 pageCountOf 会永远得到 1 页（下一页按钮消失）");
    }

    // ------------------------------------------------------------------
    // 用例 2：切片 / 下标公式成对
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GUI 渲染起点按模式分裂，且监听器有对应的 pageSlice 判定（成对改，不许单边）")
    void sliceIndexContractIsPaired() throws IOException {
        String gui = collapse(read(
                "src/main/java/com/letslearnenglish/minecraftplugin/gui/EnglishSquareGUI.java"));
        String listener = collapse(read(
                "src/main/java/com/letslearnenglish/minecraftplugin/listener/SocialListener.java"));

        // GUI 侧：全量模式必须从列表 0 起（切片），否则第 2 页整页空白。
        assertTrue(gui.contains("int listStart = mode == RankingMode.ALL_PLAYERS ? 0 : startIdx;"),
                "EnglishSquareGUI.renderPlayerHeads 的起点不再对 ALL_PLAYERS 归零 —— "
                        + "全量模式快照只有一页(28 行)，从 (page-1)*28 起会渲染出空页");

        // 监听器侧：必须显式判定"切片"，并在切片时用 slotIdx（而不是全局公式）。
        assertTrue(listener.contains("boolean pageSlice"),
                "SocialListener 缺少 pageSlice 判定 —— 无法区分'本页切片'与'全量列表'，"
                        + "第 2 页起头像点击会全部越界静默失效");
        assertTrue(listener.contains("int globalIndex = pageSlice ? slotIdx :"),
                "SocialListener 的下标反算不再按 pageSlice 分裂 —— 切片下加 (page-1)*28 会越界，"
                        + "全量下只用 slotIdx 会点到前 28 名");

        // R2（红队 A2 实测绕过点）：只断言上面那行三元表达式存在是不够的 —— 把
        // `pageSlice = currentMode == RankingMode.ALL_PLAYERS;` 改成 `= false`
        // （恒不切片），三元表达式原样保留，上面两条断言全绿，但分页点击**全线错位**。
        // 所以必须钉住 pageSlice 在两个分支里各被赋成什么值。
        assertTrue(listener.contains("pageSlice = currentMode == RankingMode.ALL_PLAYERS"),
                "复用快照分支必须把 pageSlice 赋成 currentMode == RankingMode.ALL_PLAYERS —— "
                        + "若恒为 false，三元表达式仍在但永远走全量公式，第 2 页起点击全部越界静默失效");
        assertTrue(listener.contains("pageSlice = true"),
                "无快照重建分支必须把 pageSlice 赋成 true —— 该分支的重建结果同样是本页切片，"
                        + "若为 false 会再叠加一次 (page-1)*28 而越界");

        // 切片复用的前提：快照页码必须与屏幕页码比对（陈旧快照会错位）。
        assertTrue(listener.contains("myGui.getCurrentPage() == page"),
                "SocialListener 复用它人快照时没有比对页码 —— ESC 重开/主菜单直达会留下"
                        + "「屏幕第 1 页、快照第 2 页」的陈旧组合，点击会作用到别人身上");
    }

    // ------------------------------------------------------------------
    // 用例 3：SQL 侧必须带 tie-break 与双后端写法
    // ------------------------------------------------------------------

    @Test
    @DisplayName("取行 SQL 带 uuid tie-break 与 LIMIT ? OFFSET ?；计数 SQL 的 WHERE 与取行同口径；禁用全表聚合子查询")
    void sqlHasTieBreakAndMatchingWhere() throws IOException {
        String store = collapse(read(
                "src/main/java/com/letslearnenglish/minecraftplugin/data/PlayerDataStore.java"));

        assertTrue(store.contains("ORDER BY d.total_score DESC, d.uuid ASC LIMIT ? OFFSET ?"),
                "取行 SQL 缺少全序：只按 total_score DESC 时同分玩家在 MySQL 上会跨页重复/消失，"
                        + "必须追加 d.uuid ASC 且使用 LIMIT ? OFFSET ?（禁用 SQLite 专有的 LIMIT n, m）");

        assertFalse(store.contains("LIMIT ?, ?"),
                "出现了 SQLite 专有的逗号式 LIMIT —— MySQL 会语法错误");

        // R2（红队 A1，本轮最重要的回归护栏）：
        // 广场取页 / 计数 **不得** 用 `LEFT JOIN (SELECT uuid, SUM(...) FROM word_progress
        // GROUP BY uuid)` 这种聚合子查询。过滤条件作用在聚合后的值上，SQLite 必须把整张
        // word_progress 物化完才知道哪些行合格，LIMIT 限制不了工作量（EXPLAIN 的
        // MATERIALIZE w + SCAN word_progress）。而广场每次打开要跑 COUNT + 取页两条，
        // 于是全表聚合算两遍 —— 实测比修复前更慢：
        //   200 玩家/4 万行：63.0 ms → 102.1 ms（+62%）
        //   1000 玩家/20 万行：276.8 ms → 465.1 ms（+68%）
        // 这个倒退没有任何功能症状，测试也全绿，只有 EXPLAIN 和计时能看出来。
        // 正确形状是 EXISTS 相关子查询（存在即短路，走 uuid 前缀索引）。
        assertFalse(store.contains("GROUP BY uuid) w ON w.uuid = d.uuid"),
                "广场查询又退回了「LEFT JOIN (SELECT ... FROM word_progress GROUP BY uuid)」"
                        + "聚合子查询 —— 这会把整张 word_progress 表物化两遍（COUNT 一次、取页一次），"
                        + "实测比不分页时还慢 62%~68%。必须用 EXISTS 相关子查询，见 getActiveSquareEntries 的注释");

        // 计数与取行必须共用同一段答题门谓词（含保留字面量）。两条独立的谓词漂移
        // 会让页数与内容不一致：尾页空白 / 最后几页翻不到。
        // 注：PlayerDataStore 里另有 getRankedPlayers 的「只查隐身门」查询，故这里
        // 必须连 EXISTS 答题门一起匹配，才能唯一定位到广场那两条。
        String existsGate = "AND wp.correct_count + wp.incorrect_count > 0";
        List<Integer> hits = occurrences(store, existsGate);
        assertTrue(hits.size() >= 2,
                "EXISTS 答题门只出现 " + hits.size() + " 次（期望 ≥2：取页与计数各一次）。"
                        + "计数与取行是两条 SQL，谓词漂移会让页数与内容不符，且没有任何日志");

        // EXISTS 必须真的挂在 word_progress 上（否则"答过题"判据失效，广场会列出
        // 从没答过题的人 —— 而这恰好是旧行为里的一个明确业务约束）
        assertTrue(store.contains("EXISTS (SELECT 1 FROM " + "\" + tablePrefix + \"word_progress wp"),
                "EXISTS 子查询没有查 word_progress —— 答题门失效，广场会列出没答过题的人");

        // 阶段 2 必须按 uuid 批量聚合（而不是逐人查库 = N+1）
        assertTrue(store.contains("WHERE uuid IN (") || store.contains("uuid IN ("),
                "阶段 2 未按 uuid 批量取统计 —— 逐人查库是 N+1，28 页行就是 28 次往返");

        // 计数方法必须存在且真的用 COUNT(*)
        assertTrue(store.contains("public int countActiveSquareEntries()"),
                "缺少 countActiveSquareEntries() —— GUI 无从得知总页数");
        assertTrue(store.contains("SELECT COUNT(*) AS total"),
                "countActiveSquareEntries 未用 COUNT(*) —— 全量物化行再 size() 等于没修");
    }

    private static List<Integer> occurrences(String haystack, String needle) {
        List<Integer> idx = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return idx;
            idx.add(at);
            from = at + 1;
        }
    }
}
