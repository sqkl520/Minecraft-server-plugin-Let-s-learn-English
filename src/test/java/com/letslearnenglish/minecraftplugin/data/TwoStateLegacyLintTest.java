package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 三个「已折叠成两态」的遗留读方法的<b>墓碑 lint</b>（不是行为测试）。
 *
 * <h2>这三个方法是什么，为什么必须永久消失</h2>
 * 数据层为同一件事提供了两套读面：<b>三态版</b>（区分「有值 / 表空或未分配 / 读失败」）
 * 与<b>两态便利版</b>（把「读失败」折叠成 {@code 空 List} / {@code false} /
 * {@code null}）。三态版修好了所有调用方，两态版当时为了兼容留在仓库里，javadoc 写着
 * 「只准展示路径使用 / 不得用于判定」。
 *
 * <p><b>javadoc 拦不住人。</b>两态版的返回值形状恰好是判定代码最爱的形状：
 * <ul>
 *   <li>{@code getDailyChallengeWords} → {@code List}：调用方一看就想写
 *       {@code .isEmpty()} 当「今天还没分配」；</li>
 *   <li>{@code isDailyChallengeWordsCompleted} → {@code boolean}：名字里就写着
 *       「是否完成」，调用方一看就想 {@code if (done)}；</li>
 *   <li>{@code minSeasonId} → {@code Integer}：调用方一看就想
 *       {@code if (id == null) // 首次启用}。</li>
 * </ul>
 * 这三个形状每一个都正好把「不知道」伪装成业务事实，缺陷立刻重现：当日挑战集翻倍
 * （奖励不可达）、昨日奖励永久作废、结算 sweep 谎报「首次启用」。
 *
 * <h2>为什么这里是 lint 而不是行为测试</h2>
 * 要防的是<b>将来的某一次编辑</b>，而不是当前的运行时行为。当三个方法<b>不存在</b>时，
 * 误用是编译错误 —— 那比任何断言都硬，所以本轮的首选处置是<b>删除</b>（已执行，
 * 见 {@code DatabaseManager} 里三处 {@code R12-F8} 墓碑注释）。
 *
 * <p>编译器拦不住的只有一件事：<b>有人为了图省事把折叠版加回来</b>。加回来的那一刻
 * 全仓测试仍然全绿（新方法没人调用），下一个人就会用上它。这一类「绿灯下的静默退化」
 * 正是本仓 {@code IndexAndPragmaLintsTest} / {@code PermissionContractLintTest} /
 * {@code ServerToggleLintsTest} 确立的 lint 范畴。
 *
 * <h2>四条 lint</h2>
 * <ol>
 *   <li><b>L1 墓碑</b>：{@code src/main/java} 下不得再出现三个两态方法的声明或调用
 *       （剥注释 + 抹字符串后扫描）；</li>
 *   <li><b>L2 反向守卫</b>：三个<b>三态</b>方法必须仍在 —— 防止「L1 绿了」是通过
 *       把三态版一起删掉达成的（那会让所有调用方编译失败，但也要让 lint 明确说出原因）；</li>
 *   <li><b>L3 折叠点</b>：{@code DailyChallengeManager.dbSaysAllWordsCompleted} 是唯一
 *       把三态折叠回 boolean 的地方，它必须<b>显式</b>比较 {@code COMPLETED}，
 *       且不得写成 {@code != READ_FAILED}（那个方向会把「未完成」读成「已完成」= 多发钱）；</li>
 *   <li><b>L4 反射兜底</b>：字符串字面量里不得出现这三个方法名（{@code getDeclaredMethod}
 *       之类的反射调用会绕过 L1 的调用点匹配）。</li>
 * </ol>
 *
 * <h2>防空转（本类最重要的一条纪律）</h2>
 * L1 的现状是「零命中」，一条只会零命中的断言等价于没有断言。所以 L1 内部先用
 * <b>合成源码</b>验证判定函数两个方向都对（含两态调用 → 必须命中；只在注释里提到 →
 * 必须不命中；三态方法名 → 必须不误命中 {@code getDailyChallengeWordsProgress}），
 * 再把同一函数应用到真实文件。这样「正则被改成恒不命中」和「有人把方法加回来」
 * 都会变红。
 *
 * <p><b>与其它 lint 类零耦合</b>：{@code stripComments} / 文件遍历在此独立实现一遍，
 * 理由同 {@code ServerToggleLintsTest} 的类注释 —— 共用工具会让若干组 lint 同时因一处
 * 语法边缘情况静默失效，而失败消息还指向另一个文件。
 */
@DisplayName("TwoStateLegacyLint — 两态遗留读方法的墓碑")
class TwoStateLegacyLintTest {

    /** 主源码根；lint 只在 {@code src/main/java} 上生效（测试自己调用不算误用）。 */
    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /** 数据层文件（三个方法曾在此声明）。 */
    private static final String DB_MANAGER =
            "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java";

    /** 唯一的折叠点所在文件。 */
    private static final String DAILY_CHALLENGE_MANAGER =
            "src/main/java/com/letslearnenglish/minecraftplugin/core/DailyChallengeManager.java";

    /**
     * 两态方法的调用/声明形态。
     *
     * <p>用 {@code \s*\(} 而不是裸词：{@code getDailyChallengeWordsProgress} 是<b>另一个
     * 仍然合法的方法</b>，裸词匹配会把它一起判红。{@code \b} 保证不从更长的标识符中间开始。
     */
    private static final Pattern TWO_STATE_CALL = Pattern.compile(
            "\\b(getDailyChallengeWords|isDailyChallengeWordsCompleted|minSeasonId)\\s*\\(");

    /** 反射兜底：字符串字面量里出现这三个名字（{@code getDeclaredMethod("minSeasonId")}）。 */
    private static final Pattern TWO_STATE_LITERAL = Pattern.compile(
            "\"(getDailyChallengeWords|isDailyChallengeWordsCompleted|minSeasonId)\"");

    // ==================================================================
    // L1：墓碑
    // ==================================================================

    @Nested
    @DisplayName("L1 —— 两态遗留方法不得存在")
    class L1 {

        /**
         * 判定函数自检（<b>防空转</b>）：合成源码上必须同时满足「命中正确」与「不误命中」。
         *
         * <p>少了这条，把 {@link #twoStateCallsIn} 改成 {@code return List.of()} 之后
         * 下面的真实文件断言仍然全绿 —— 守卫被关掉却毫无症状，这是本仓反复出现的
         * 「假绿五形态」之首。
         */
        @Test
        @DisplayName("判定函数自检：合成源码上命中两态调用、放过三态与注释")
        void detectorSelfCheck() {
            String synthetic = String.join("\n",
                    "class Probe {",
                    "    boolean done = db.isDailyChallengeWordsCompleted(p, d);",
                    "    List<String> w = db.getDailyChallengeWords(p, d);",
                    "    Integer m = db.minSeasonId();",
                    "    // 注释里提到 getDailyChallengeWords 不该算调用",
                    "    /* 块注释里的 minSeasonId( 也不算 */",
                    "    String s = \"isDailyChallengeWordsCompleted\";",
                    "    int[] prog = db.getDailyChallengeWordsProgress(p, d);",
                    "    var tri = db.loadWordChallengeCompletion(p, d);",
                    "    var load = db.loadDailyChallengeWords(p, d);",
                    "    var range = db.loadMinSeasonId();",
                    "}");

            List<String> hits = twoStateCallsIn(synthetic);

            assertEquals(List.of("isDailyChallengeWordsCompleted", "getDailyChallengeWords",
                            "minSeasonId"),
                    hits,
                    "判定函数必须命中三处两态调用，且<b>不得</b>命中 "
                            + "getDailyChallengeWordsProgress（仍合法的另一个方法）与三态版。"
                            + "实际命中：" + hits);

            assertTrue(twoStateCallsIn("// 只有注释：db.minSeasonId()\n").isEmpty(),
                    "剥注释后注释里的方法名不该被当成调用 —— 否则本 lint 会被自己注释反复刷红，"
                            + "而真正的新增调用点淹没在噪音里");
        }

        /** 真实文件：{@code src/main/java} 下不得出现任何两态调用（含声明）。 */
        @Test
        @DisplayName("src/main/java 下没有两态遗留方法的声明或调用")
        void noTwoStateCallsInMainSources() throws IOException {
            List<String> offenders = new ArrayList<>();
            for (Path file : javaSources()) {
                String stripped = stripCommentsAndLiterals(
                        Files.readString(file, StandardCharsets.UTF_8));
                int lineNo = 0;
                for (String line : stripped.split("\n", -1)) {
                    lineNo++;
                    Matcher m = TWO_STATE_CALL.matcher(line);
                    while (m.find()) {
                        offenders.add(relativize(file) + ":" + lineNo + " :: " + m.group(1)
                                + " 调用 → " + line.trim());
                    }
                }
            }

            assertTrue(offenders.isEmpty(),
                    "两态遗留读方法又出现在主源码里了：\n" + String.join("\n", offenders)
                            + "\n\n这三个方法（getDailyChallengeWords / "
                            + "isDailyChallengeWordsCompleted / minSeasonId）把「读失败」"
                            + "折叠成空 List / false / null，与「业务上确实没有」逐字节同形。"
                            + "误用的后果是当日挑战集翻倍导致奖励不可达、昨日奖励永久作废、"
                            + "结算 sweep 谎报「首次启用」。请改用三态版："
                            + "loadDailyChallengeWords / loadWordChallengeCompletion / "
                            + "loadMinSeasonId，并显式写出「未知」怎么办。");
        }

        /** 声明点必须真的不在了（而不是「有声明但没人调用」）。 */
        @Test
        @DisplayName("DatabaseManager 里三个两态方法已删除（不是只删了调用点）")
        void declarationsAreGone() throws IOException {
            String src = stripComments(Files.readString(Paths.get(DB_MANAGER),
                    StandardCharsets.UTF_8));

            for (String name : List.of("getDailyChallengeWords",
                    "isDailyChallengeWordsCompleted", "minSeasonId")) {
                // 只认「带访问修饰符 + 返回类型 + 方法名 + (」的声明形态，且中间<b>不许</b>
                // 出现 `=`：否则 `private final Foo x = db.getMinSeasonId();` 这种
                // 「字段初始化里的调用」会被误判成声明（本 lint 要抓的是声明本身）。
                // 墓碑注释已被 stripCommentsAndLiterals 抹掉，故这里能精确断言。
                Pattern decl = Pattern.compile(
                        "\\b(?:public|protected|private)\\s+[^;{}()=]*?\\b" + name + "\\s*\\(");
                assertFalse(decl.matcher(src).find(),
                        "DatabaseManager 里仍有 " + name + " 的<b>声明</b>。"
                                + "只删调用点不删方法，下一个人照样能把它用起来；"
                                + "本轮处置是彻底删除（R12-F8），墓碑注释保留了删除理由。");
            }
        }
    }

    // ==================================================================
    // L2：反向守卫
    // ==================================================================

    @Nested
    @DisplayName("L2 —— 三态版必须仍在")
    class L2 {

        /**
         * 三态版三个方法必须仍是 {@code DatabaseManager} 的公开方法。
         *
         * <p>反向守卫的意义：L1 与 L2 一起才构成「两态没了、三态还在」。只有 L1 时，
         * 一次「把整个读面删掉」的重构也能让它变绿 —— 那时所有调用方都编译不过，
         * 报错却指向调用方，容易让人以为是调用方写错。
         */
        @Test
        @DisplayName("loadDailyChallengeWords / loadWordChallengeCompletion / loadMinSeasonId 仍在")
        void triStateCounterpartsStillExist() throws IOException {
            String src = stripComments(Files.readString(Paths.get(DB_MANAGER),
                    StandardCharsets.UTF_8));

            for (String signature : List.of(
                    "DailyChallengeWordsLoad loadDailyChallengeWords(",
                    "WordChallengeCompletion loadWordChallengeCompletion(",
                    "SeasonIdRange loadMinSeasonId(")) {
                assertTrue(src.contains(signature),
                        "三态版 " + signature + " 不见了。L1 的「两态已消失」只能在三态"
                                + "仍在的前提下才是有意义的保证 —— 否则整个读面都没了。");
            }
        }

        /** 三态版的消费点必须存在，否则「两态删了」可能是因为根本没人读了。 */
        @Test
        @DisplayName("三个三态方法在主源码里都至少有一个调用点")
        void triStateMethodsAreConsumed() throws IOException {
            String all = allMainSourcesStripped();

            for (String name : List.of("loadDailyChallengeWords",
                    "loadWordChallengeCompletion", "loadMinSeasonId")) {
                assertTrue(Pattern.compile("\\b" + name + "\\s*\\(").matcher(all).find(),
                        "三态方法 " + name + " 在主源码里没有任何调用点。若它真的不再被需要，"
                                + "请连同本 lint 的 L2 一起删除 —— 但不要留下「两态删了、"
                                + "三态也没人用」的中间态。");
            }
        }
    }

    // ==================================================================
    // L3：唯一的折叠点
    // ==================================================================

    @Nested
    @DisplayName("L3 —— 折叠回 boolean 的地方必须显式且方向正确")
    class L3 {

        /**
         * {@code dbSaysAllWordsCompleted} 是两态方法删除后唯一仍把三态折叠成 boolean 的点。
         *
         * <p>两条断言，各防一个方向的错误：
         * <ol>
         *   <li><b>必须</b>显式出现 {@code WordChallengeCompletion.COMPLETED} ——
         *       否则有人可能用「只要不是 READ_FAILED 就算完成」的写法，那会把
         *       {@code NOT_COMPLETED} 也读成「完成了」，直接<b>多发一次钱</b>；</li>
         *   <li><b>不得</b>出现 {@code != ... READ_FAILED} —— 这是上面那种写法的具体形态。
         *       本仓 {@code readWordChallengeCompletionWithRetry} 里有合法的
         *       {@code != READ_FAILED}，但它做的是「返回枚举本身」而不是「折叠成布尔」，
         *       故本断言只在 {@code dbSaysAllWordsCompleted} 的方法体内生效。</li>
         * </ol>
         */
        @Test
        @DisplayName("dbSaysAllWordsCompleted 显式比较 COMPLETED，且无 != READ_FAILED")
        void collapsePointComparesAgainstCompleted() throws IOException {
            String body = methodBody(readStripped(Paths.get(DAILY_CHALLENGE_MANAGER)),
                    "private boolean dbSaysAllWordsCompleted(");

            assertTrue(body.contains("WordChallengeCompletion.COMPLETED"),
                    "dbSaysAllWordsCompleted 里没有显式的 WordChallengeCompletion.COMPLETED 比较。"
                            + "\n三态折叠只能有一个方向是对的：只有 COMPLETED 才算「完成」，"
                            + "NOT_COMPLETED 与 READ_FAILED 都不得放行发奖。"
                            + "\n方法体：\n" + body);

            assertFalse(Pattern.compile("!=\\s*(DatabaseManager\\.)?WordChallengeCompletion\\.READ_FAILED")
                            .matcher(body).find(),
                    "dbSaysAllWordsCompleted 写成「!= READ_FAILED 就算完成」了 —— 那个方向把"
                            + "\nNOT_COMPLETED（业务上确实没做完）也读成「完成」→ 提前发奖 = 多发钱。"
                            + "\n未知的正确处置是「不发奖、保留 pending、下次再复核」，不是「当作完成」。"
                            + "\n方法体：\n" + body);
        }
    }

    // ==================================================================
    // L4：反射兜底
    // ==================================================================

    @Nested
    @DisplayName("L4 —— 字符串字面量里不得出现这三个名字")
    class L4 {

        /**
         * 反射调用（{@code getDeclaredMethod("minSeasonId")}）不会命中 L1 的调用点正则，
         * 所以单独扫字面量。本仓当前无反射调用私有读方法的代码（已 grep 核实），
         * 这条是为了让「将来有人绕路」时也红。
         *
         * <p><b>注意本方法保留字符串字面量</b>（不抹），否则这条断言恒绿。
         */
        @Test
        @DisplayName("没有以字符串字面量形式引用两态方法名")
        void noLiteralReferences() throws IOException {
            List<String> offenders = new ArrayList<>();
            for (Path file : javaSources()) {
                // 这里必须用<b>保留字面量</b>的版本（stripComments），否则扫不到任何字面量。
                String stripped = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                int lineNo = 0;
                for (String line : stripped.split("\n", -1)) {
                    lineNo++;
                    Matcher m = TWO_STATE_LITERAL.matcher(line);
                    while (m.find()) {
                        offenders.add(relativize(file) + ":" + lineNo + " :: " + m.group());
                    }
                }
            }

            assertTrue(offenders.isEmpty(),
                    "发现以字符串字面量引用两态方法名（反射会绕过 L1 的调用点检查）："
                            + offenders + "。请直接用三态版，不要反射。");
        }

        /** 判定函数自检：字面量检测必须真的能命中（防空转）。 */
        @Test
        @DisplayName("字面量判定函数自检：合成源码上命中")
        void literalDetectorSelfCheck() {
            assertTrue(TWO_STATE_LITERAL.matcher(
                            "var m = DatabaseManager.class.getDeclaredMethod(\"minSeasonId\");")
                            .find(),
                    "字面量检测函数已失效（恒不命中），L4 等于没写");
            assertFalse(TWO_STATE_LITERAL.matcher(
                            "var m = DatabaseManager.class.getDeclaredMethod(\"loadMinSeasonId\");")
                            .find(),
                    "字面量检测不得把三态版 loadMinSeasonId 判红 —— 否则守卫生出的是噪音");
        }
    }

    // ==================================================================
    // 辅助（本类独立实现，不与其它 lint 类共用）
    // ==================================================================

    /** 返回被剥注释/抹字面量文本里出现的两态方法<b>名字</b>，按出现顺序（自检用）。 */
    private static List<String> twoStateCallsIn(String source) {
        List<String> names = new ArrayList<>();
        Matcher m = TWO_STATE_CALL.matcher(stripCommentsAndLiterals(source));
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** 枚举 {@code src/main/java} 下全部 {@code .java} 文件。 */
    private static List<Path> javaSources() throws IOException {
        if (!Files.isDirectory(MAIN_JAVA)) {
            fail("找不到 " + MAIN_JAVA.toAbsolutePath() + "（测试必须以仓库根为工作目录）");
        }
        try (Stream<Path> s = Files.walk(MAIN_JAVA)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    /** 主源码全量拼接（剥注释 + 抹字面量），用于「某方法是否还有人调用」这类跨文件判断。 */
    private static String allMainSourcesStripped() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path file : javaSources()) {
            sb.append(stripCommentsAndLiterals(
                    Files.readString(file, StandardCharsets.UTF_8))).append('\n');
        }
        return sb.toString();
    }

    /** 读源码，剥注释 + 抹字面量（找调用点用）。 */
    private static String readStripped(Path file) throws IOException {
        return stripCommentsAndLiterals(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * 取出以 {@code head} 开头的声明所在的<b>方法体</b>（到配对的花括号为止）。
     *
     * <p>括号配对而不是 {@code indexOf("}")}：方法体里有 lambda、匿名类、字符串里的
     * 花括号，取第一个 {@code }} 会截断。本仓 {@code ServerToggleLintsTest} 的 L7
     * 也踩过同一个坑。
     */
    private static String methodBody(String source, String head) {
        int start = source.indexOf(head);
        if (start < 0) {
            fail("源码里找不到方法声明起始串：" + head
                    + "（方法被改名/删除时请同步本 lint，而不是绕过它）");
        }
        int brace = source.indexOf('{', start);
        if (brace < 0) {
            fail("方法声明后找不到方法体起始 '{'：" + head);
        }
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(brace, i + 1);
                }
            }
        }
        fail("方法体花括号未闭合：" + head);
        return "";
    }

    /** 仓库根相对路径，失败消息里更易读。 */
    private static String relativize(Path file) {
        try {
            return Paths.get("").toAbsolutePath().relativize(file.toAbsolutePath()).toString()
                    .replace('\\', '/');
        } catch (RuntimeException e) {
            return file.toString();
        }
    }

    /**
     * 剥掉 {@code //} 与块注释，<b>保留</b>字符串/字符字面量。
     *
     * <p>两个工具的分工必须清楚，否则很容易写出一条恒绿的断言：
     * <ul>
     *   <li>{@link #stripCommentsAndLiterals}：剥注释 <b>且</b> 抹字面量 —— 给 L1/L2/L3
     *       这类「找调用点」用。不剥注释时，本仓大量「这里曾有 {@code getDailyChallengeWords}」
     *       的墓碑注释会被当成调用点；不抹字面量时，字面量里的 {@code //}（URL、
     *       正则）会被误当注释起点，把后面半个文件「吃掉」—— 那个方向的失效是<b>静默漏检</b>。</li>
     *   <li>本方法：只剥注释、保留字面量 —— 给 L4（反射字符串名）用。若这里也抹字面量，
     *       L4 将永远匹配不到，等于没写。</li>
     * </ul>
     *
     * @param source 源码
     * @return 剥掉注释后的文本（长度与偏移完全保持，行号可对齐）
     */
    private static String stripComments(String source) {
        return scan(source, false);
    }

    /**
     * 剥掉注释<b>并</b>把字符串/字符字面量内容抹成空格。
     *
     * @param source 源码
     * @return 剥注释 + 抹字面量后的文本（长度与偏移完全保持，行号可对齐）
     */
    private static String stripCommentsAndLiterals(String source) {
        return scan(source, true);
    }

    /**
     * 单趟扫描器：处理行注释、块注释、文本块、字符串、字符字面量。
     *
     * @param source         源码
     * @param blankLiterals  {@code true} 时把字面量区段也抹成空格
     * @return 处理后的文本
     */
    private static String scan(String source, boolean blankLiterals) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            // 1) 行注释：跳到行尾（保留换行，偏移/行号不错位）
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }

            // 2) 块注释：跳到配对的 */
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j < n && !(source.charAt(j) == '*' && j + 1 < n
                        && source.charAt(j + 1) == '/')) {
                    j++;
                }
                j = (j < n) ? Math.min(n, j + 2) : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            // 3) 文本块 """
            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"'
                    && source.charAt(i + 2) == '"') {
                int j = i + 3;
                while (j + 2 < n
                        && !(source.charAt(j) == '"' && source.charAt(j + 1) == '"'
                        && source.charAt(j + 2) == '"')) {
                    if (source.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = (j + 2 < n) ? j + 3 : n;
                if (blankLiterals) {
                    blank(out, i, j);
                }
                i = j;
                continue;
            }

            // 4) 普通字符串：处理 \\ 转义，遇换行视为未闭合（容错）
            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '"' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                if (blankLiterals) {
                    blank(out, i, j);
                }
                i = j;
                continue;
            }

            // 5) 字符字面量
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '\'' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                if (blankLiterals) {
                    blank(out, i, j);
                }
                i = j;
                continue;
            }

            i++;
        }
        return new String(out);
    }

    /** 把 {@code [from, to)} 内的非换行字符替换为空格（保持偏移与行结构）。 */
    private static void blank(char[] out, int from, int to) {
        int end = Math.min(to, out.length);
        for (int k = Math.max(0, from); k < end; k++) {
            if (out[k] != '\n' && out[k] != '\r') {
                out[k] = ' ';
            }
        }
    }
}
