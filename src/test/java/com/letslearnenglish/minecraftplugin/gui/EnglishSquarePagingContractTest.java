package com.letslearnenglish.minecraftplugin.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * 取某个方法的方法体（含外层花括号），用于把断言<b>限定在被测方法内</b>。
     *
     * <p><b>为什么需要它</b>（unv-tests-logic::F-03）：对整个文件的子串搜索会被
     * <b>别处同形代码</b>满足 —— 广场阶段 2 的 {@code WHERE uuid IN (} 曾被班级榜的
     * {@code AND s.uuid IN (} 兜住，于是删掉广场的批量聚合后断言依然全绿。
     * 把搜索范围收进目标方法体，锚点就只可能由目标方法自己满足。
     *
     * @param signature 方法签名，<b>不含</b>方法体，例如
     *                  {@code "getActiveSquareEntries(int limit, int offset)"}；
     *                  传的是「签名到左花括号之前」的连续文本
     */
    private static String methodBody(String src, String signature) {
        int sigAt = src.indexOf(signature);
        assertTrue(sigAt >= 0,
                "源码里找不到方法签名「" + signature + "」喵 —— 方法被改名/改签名了，"
                        + "请同步本 lint（否则门禁会静默失效，而不是变红）");
        int open = src.indexOf('{', sigAt + signature.length());
        assertTrue(open > 0, "方法「" + signature + "」之后找不到左花括号喵");
        int close = matchingBrace(src, open);
        assertTrue(close > open,
                "方法「" + signature + "」的花括号不配对喵 —— 源码可能被截断");
        return src.substring(open, close + 1);
    }

    /** 从 leftBrace 起做括号配对（跳过字符串/字符字面量），返回匹配的右花括号下标（找不到 -1）。 */
    private static int matchingBrace(String s, int leftBrace) {
        int depth = 0;
        int i = leftBrace;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == quote) break;
                }
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * 取某次方法调用的实参列表（按顶层逗号切分）。
     *
     * <p>用于把「offset 必须由页码算出」这类要求表达成<b>对实参的检查</b>，
     * 而不是对整段文本的逐字复刻 —— 见 unv-tests-logic::F-10。
     */
    private static List<String> callArguments(String src, String methodName) {
        int at = src.indexOf(methodName + "(");
        assertTrue(at >= 0, "源码里找不到调用「" + methodName + "(」喵");
        int open = at + methodName.length();
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = open + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) { args.add(cur.toString().trim()); return args; }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        throw new AssertionError("调用「" + methodName + "(」的右括号不配对喵");
    }

    /**
     * 把「表达式」解析成可核对的形状：若它只是一个局部变量名，则回落取该变量的初始化表达式。
     *
     * <p>这是 F-10 的修复要点：{@code getActiveSquareEntries(PLAYERS_PER_PAGE, offset)}
     * 与 {@code getActiveSquareEntries(PLAYERS_PER_PAGE, (page - 1) * PLAYERS_PER_PAGE)}
     * <b>语义等价</b>，前者是同一次合理重构的结果。把 offset 提取成局部变量不该让 lint 误红。
     */
    private static String resolveExpression(String scope, String expr) {
        String trimmed = expr.trim();
        if (!trimmed.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return trimmed;
        }
        java.util.regex.Matcher m = Pattern.compile(
                "\\b" + Pattern.quote(trimmed) + "\\s*=\\s*([^;]+);").matcher(scope);
        assertTrue(m.find(),
                "offset 实参是局部变量「" + trimmed + "」，但在方法体里找不到它的初始化语句喵 —— "
                        + "无法确认偏移量是由页码算出来的");
        return m.group(1).trim();
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
        //
        // F-10 修复：不再逐字比对整段调用文本。原先的
        //   gui.contains("getActiveSquareEntries(PLAYERS_PER_PAGE, (page - 1) * PLAYERS_PER_PAGE)")
        // 会在「把 offset 提取成局部变量」这种<b>语义等价</b>的重构下误红
        //（`int offset = (page - 1) * PLAYERS_PER_PAGE;` + 传 `offset`），
        // 而误红最自然的处置是删断言 —— 行为等价重构反而降低防护强度。
        // 现在改成对<b>实参</b>做结构判定：实参只有两个，且 offset 求值后必须同时含
        // page 与 PLAYERS_PER_PAGE（容忍空格/括号差异，也容忍局部变量提取）。
        String openBody = methodBody(gui, "void open(Player viewer, int page, RankingMode mode)");
        List<String> args = callArguments(openBody, "getActiveSquareEntries");
        assertEquals(2, args.size(),
                "getActiveSquareEntries 的实参数量不是 2 喵（实际 " + args.size()
                        + "）—— 分页版必须同时传 limit 与 offset");
        assertEquals("PLAYERS_PER_PAGE", args.get(0),
                "第一个实参必须是 PLAYERS_PER_PAGE 喵 —— 传常量会把页容量写死，"
                        + "改 PLAYERS_PER_PAGE 时 offset 与渲染槽位数会不同步");
        String offsetExpr = resolveExpression(openBody, args.get(1));
        String offsetNorm = WS.matcher(offsetExpr).replaceAll(" ").trim();

        // F-06 修复（极性）：原断言是
        //   offsetNorm.contains("page") && offsetNorm.contains("PLAYERS_PER_PAGE")
        // 这是**半边覆盖**：它只能证明「offset 里提到了 page」，证明不了「是第 page 页」。
        // 实测三个极性错误的变异全部通过（3/3 绿）：
        //   · (page - 1) * PLAYERS_PER_PAGE  →  page * PLAYERS_PER_PAGE       （整体多翻一页）
        //   · (page - 1) * PLAYERS_PER_PAGE  →  (1 - page) * PLAYERS_PER_PAGE （页序倒转）
        //   · (page - 1) * PLAYERS_PER_PAGE  →  (page + 1) * PLAYERS_PER_PAGE （跳过页首）
        // 三者的表达式里都含 `page` 与 `PLAYERS_PER_PAGE`，前两条还都含字面量 1。
        // 这种「翻页翻错位、但没有异常也没有日志」是最典型的静默错位，必须按**结构**判定。
        //
        // 现在把表达式归一化后，要求它的**算术形状**恰好是 `(page - 1) * PLAYERS_PER_PAGE`
        // 的等价集合：先剥掉所有空白与外层括号，再按两种允许的书写顺序匹配。
        // 允许集刻意很小 —— 只有「(page-1) 乘 页容量」这一种语义是对的。若把 offset
        // 提取成局部变量，resolveExpression 会回落到初始化表达式，判定口径不变。
        assertTrue(isPageZeroBasedOffset(offsetNorm),
                "取页的 offset 不是「(page - 1) * PLAYERS_PER_PAGE」喵，实际 offset 表达式是「"
                        + offsetNorm + "」。\n"
                        + "本断言按<b>算术形状</b>判定，接受两种等价书写：\n"
                        + "    (page - 1) * PLAYERS_PER_PAGE\n"
                        + "    PLAYERS_PER_PAGE * (page - 1)\n"
                        + "（以及把其中一段提取成局部变量的写法）。\n"
                        + "为什么不能只查「表达式里含 page 与 PLAYERS_PER_PAGE」：\n"
                        + "  · `page * PLAYERS_PER_PAGE` 每页整体后移一页，第 1 页直接是空页；\n"
                        + "  · `(1 - page) * PLAYERS_PER_PAGE` 页序倒转，负 offset 被驱动当 0；\n"
                        + "  · `(page + 1) * PLAYERS_PER_PAGE` 每页跳过首行，合计漏掉 1/页容量。\n"
                        + "三者都含 page 与 PLAYERS_PER_PAGE（前两条还含字面量 1），"
                        + "「只查关键字」的断言对它们全部假绿 —— 而症状是「翻页翻不到人」，"
                        + "没有异常、没有日志，玩家只会觉得「翻页没反应」。\n"
                        + "若确为等价重构（例如 `offset = (page - 1) * PAGE;` 且 PAGE ≡ PLAYERS_PER_PAGE），"
                        + "请把本 lint 的允许集扩到新写法——但**不许**放宽成「含 page 即可」。");

        assertTrue(gui.contains("countActiveSquareEntries()"),
                "EnglishSquareGUI 必须用 countActiveSquareEntries() 算总页数 —— "
                        + "只用本页 28 行算 pageCountOf 会永远得到 1 页（下一页按钮消失）");
    }

    /**
     * F-06：判定 offset 表达式是不是「从 0 开始的第 page 页」这一唯一正确算术形状。
     *
     * <p>做法是把空白与外层括号剥掉后做<b>精确</b>比对，只接受两种书写顺序：
     * <pre>
     *   (page-1)*PLAYERS_PER_PAGE
     *   PLAYERS_PER_PAGE*(page-1)
     * </pre>
     * 不接受 {@code page*PLAYERS_PER_PAGE}（首屏空页）、{@code (1-page)*...}（页序倒转）、
     * {@code (page+1)*...}（漏行）等任何极性/偏移变体。
     *
     * <p>为什么不做更花哨的表达式求值：本项目没有可用的表达式引擎，而「算术符号 + 操作数
     * 顺序」已经是这条契约的全部内容。窄允许集是有意的 —— 误红时由人确认等价并扩集，
     * 比放宽到「含 page 即可」而被三个极性变异同时骗过要好。
     */
    private static boolean isPageZeroBasedOffset(String normalized) {
        String s = normalized.replaceAll("\\s+", "");
        // 剥掉整体外层括号（可能有多层）
        while (s.startsWith("(") && s.endsWith(")") && balancedOuter(s)) {
            s = s.substring(1, s.length() - 1);
        }
        return s.equals("(page-1)*PLAYERS_PER_PAGE")
                || s.equals("PLAYERS_PER_PAGE*(page-1)")
                || s.equals("(page-1)*PLAYERS_PER_PAGE;")
                || s.equals("PLAYERS_PER_PAGE*(page-1);");
    }

    /** 判断 {@code s} 的最外层括号是否恰好包住整个串（用于安全剥括号）。 */
    private static boolean balancedOuter(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i == s.length() - 1;
                }
            }
        }
        return false;
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
        //
        // F-10 修复：原先逐字比对
        //   gui.contains("int listStart = mode == RankingMode.ALL_PLAYERS ? 0 : startIdx;")
        // 会在「把三元表达式提成方法」或「加一对括号」这类等价重构下误红。
        // 现在用正则接受括号差异与额外的全限定前缀，但语义仍然钉死：
        // 条件必须是 mode == ALL_PLAYERS，真分支必须是字面量 0，假分支必须是 startIdx。
        assertTrue(Pattern.compile(
                        "int\\s+listStart\\s*=\\s*\\(?\\s*mode\\s*==\\s*RankingMode\\.ALL_PLAYERS\\s*\\)?"
                                + "\\s*\\?\\s*0\\s*:\\s*startIdx\\s*;")
                        .matcher(gui).find(),
                "EnglishSquareGUI.renderPlayerHeads 的起点不再对 ALL_PLAYERS 归零 —— "
                        + "全量模式快照只有一页(28 行)，从 (page-1)*28 起会渲染出空页。\n"
                        + "期望形如：int listStart = mode == RankingMode.ALL_PLAYERS ? 0 : startIdx;"
                        + "（允许括号/空格差异 —— 那只是排版）");

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
        //
        // F-05 修复：原实现数的是**整份 PlayerDataStore.java** 里的出现次数，断言 `>= 2`。
        // 但同一个门在文件里有 4 处 —— getAuthoritativeRank 的两条 SQL（我的分数 / 我的名次）
        // 也含 `AND wp.correct_count + wp.incorrect_count > 0`。于是**删掉计数侧的门之后
        // 文件里仍有 3 处，>= 2 照样成立**。实测：把 countActiveSquareEntries 的 EXISTS
        // 整段删掉（计数变成"只隐身过滤"），本类 3/3 全绿 —— 而真实后果是
        // 「广场显示 65 人、点进去只有 60 人」，尾页空白且没有任何日志。
        // ActiveSquarePagingTest 能抓到它，但那是真库测试；本条 lint 的职责正是
        // 「不开库也能拦住」，所以必须逐方法精确定位。
        //
        // 现在改为：把搜索范围收进两个**具体方法体**，各自断言门恰好出现 1 次。
        // 删任一侧 → 该方法体里变成 0 次 → 立刻变红。
        String countBody = methodBody(store, "int countActiveSquareEntries()");
        String existsGate = "AND wp.correct_count + wp.incorrect_count > 0";
        assertEquals(1, occurrences(countBody, existsGate).size(),
                "countActiveSquareEntries 的 SQL 里答题门出现 "
                        + occurrences(countBody, existsGate).size() + " 次，期望恰好 1 次喵。\n"
                        + "0 次 = 计数不再过滤「没答过题的人」，页数会比实际内容多 —— "
                        + "广场尾页空白、翻页翻不到人，且没有任何日志。\n"
                        + "注意本断言只在 countActiveSquareEntries() 方法体内搜索："
                        + "getAuthoritativeRank 的两条 SQL 也含同一个门，拿全文件数出现次数"
                        + "会被它们兜住（实测删掉计数侧的门后全文件仍有 3 处，`>= 2` 假绿）。\n"
                        + "方法体原文：\n" + countBody);

        String pageBody = methodBody(store, "List<LeaderboardEntry> getActiveSquareEntries(int limit, int offset)");
        assertEquals(1, occurrences(pageBody, existsGate).size(),
                "getActiveSquareEntries 的取页 SQL 里答题门出现 "
                        + occurrences(pageBody, existsGate).size() + " 次，期望恰好 1 次喵。\n"
                        + "0 次 = 广场会列出从没答过题的人（旧行为里的明确业务约束被破坏）。\n"
                        + "方法体原文：\n" + pageBody);

        // EXISTS 必须真的挂在 word_progress 上（否则"答过题"判据失效）。
        // 同样收进方法体：全文件搜会被 getAuthoritativeRank 兜住。
        assertTrue(pageBody.contains("EXISTS (SELECT 1 FROM \" + tablePrefix + \"word_progress wp"),
                "取页 SQL 的 EXISTS 子查询没有查 word_progress —— 答题门失效，"
                        + "广场会列出没答过题的人");
        assertTrue(countBody.contains("EXISTS (SELECT 1 FROM \" + tablePrefix + \"word_progress wp"),
                "计数 SQL 的 EXISTS 子查询没有查 word_progress —— 计数与取行口径漂移，"
                        + "页数与内容不符");

        // 阶段 2 必须按 uuid 批量聚合（而不是逐人查库 = N+1）
        //
        // F-03 修复：原先的
        //   store.contains("WHERE uuid IN (") || store.contains("uuid IN (")
        // 右分支会被<b>无关方法</b>满足 —— 班级榜 getRankedPlayersForPeriodFiltered 的
        // `"AND s.uuid IN ("` 含子串 `uuid IN (`，于是删掉广场阶段 2 的批量聚合
        // 改回逐人查库后，断言依然全绿，而它是「N+1 回归」的唯一护栏。
        // 现在把搜索范围收进 getActiveSquareEntries(int, int) 的方法体，
        // 并同时断言「该方法体内不得出现逐人查库」—— 两条合起来才能唯一定位 N+1。
        String squareBody = methodBody(store,
                "getActiveSquareEntries(int limit, int offset)");
        assertTrue(squareBody.contains("WHERE uuid IN ("),
                "getActiveSquareEntries 的阶段 2 未按 uuid 批量取统计喵 —— "
                        + "逐人查库是 N+1，28 页行就是 28 次往返。\n"
                        + "（本断言只在本方法体内搜索：别处的班级榜也有 `uuid IN (`，"
                        + "拿全文件搜会被它兜住而假绿）");
        assertTrue(squareBody.contains("GROUP BY uuid"),
                "阶段 2 的批量聚合缺少 GROUP BY uuid 喵 —— 不按玩家分组会让 28 行乌泱泱"
                        + "混成一个总计，每人拿到的正确/错误数会全部相同");
        assertFalse(squareBody.contains("getPlayerStats("),
                "getActiveSquareEntries 的方法体里出现了 getPlayerStats( 喵 —— "
                        + "这正是被替换掉的 N+1 逐人查库形态（每页 28 行 = 28 次往返）。"
                        + "批量 IN 查询是唯一允许的取统计方式");

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
