package com.letslearnenglish.minecraftplugin.core.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 「注释在说谎」族的<b>源码级 lint</b>：钉住「MySQL URL 没有 socketTimeout / connectTimeout」
 * 这条已经被证伪的断言不再出现在 {@code AIChatManager} 的注释里。
 *
 * <h2>被钉住的缺陷</h2>
 * <p>{@code AIChatManager} 里有两段注释（开场白异步化的理由、会话结算异步化的缺陷链）
 * 曾断言 {@code DatabaseManager.initializeMySQL} 的 URL <b>缺</b> {@code socketTimeout}
 * / {@code connectTimeout}，据此推出「主线程会被挂到 OS 级 TCP 超时、甚至无限期」。
 * 而那条 URL 上两项<b>都在</b>（30 s / 10 s，见 {@code initializeMySQL} 的取值论证），
 * 结论「必须离开主线程」仍然成立，<b>理由已经换了</b>：超时封的是「单次网络读 /
 * 单次握手」，不是「一次数据库操作」，而且 {@code connectionLock} 是 {@code synchronized}、
 * 不带超时。
 *
 * <p>本仓已经因为「注释声称的行为与代码不符」翻过车（{@code releaseSettlement} 的
 * javadoc 把「他服持有时匹配 0 行」论证成「语义正确」，一个 HIGH 缺陷因此被放过一整轮）。
 * 这类错误不会让任何行为测试变红，只会让下一个读代码的人得出相反的前提 —— 所以只能读源码。
 *
 * <h2>为什么必须<b>反过来</b>剥：只取注释</h2>
 * <p>本仓其它 lint 类先 {@code stripComments} 再匹配代码锚点（防止注释里的词充当假阳性）。
 * 这一类的对象恰好是注释本身，于是需要一个取反的工具：{@code commentTextOf} 只回吐
 * {@code //} 与 {@code /* *}{@code /} 里的字符，字符串字面量一律丢弃 —— 否则 URL 那个
 * <b>字符串</b> {@code "...&socketTimeout="} 会被当成「注释里的证词」，L1 与 L2 互相
 * 顶牛。两条工具各配一条前提用例（{@link ToolPremise}），证明它们真的按预期切分。
 *
 * <h2>四条 lint</h2>
 * <ol>
 *   <li><b>L1（证据）</b> {@code initializeMySQL} 的 URL 确实拼了
 *       {@code connectTimeout=} 与 {@code socketTimeout=}，且两个局部常量就是 10 s / 30 s
 *       —— 这条是 L2 的<b>前提</b>：若哪天超时真的被删了，L2 指控的「说谎」就变回真话，
 *       那时该由 L1 变红来提醒「改注释，别改代码」；</li>
 *   <li><b>L2（不撒谎）</b> {@code AIChatManager} 的注释里<b>零命中</b>「没有 / 缺 / 没设 /
 *       未设 / 不含 / 不存在 + socketTimeout(或 connectTimeout)」这类缺省断言；</li>
 *   <li><b>L3（正向锚定）</b> 凡 {@code AIChatManager} 的注释提到 {@code socketTimeout}，
 *       同一段注释必须带上实际生效值（{@code 30}）—— 只说「有超时」而不给值，下一个人
 *       仍然要回去翻 {@code DatabaseManager}；</li>
 *   <li><b>L4（单位防御）</b> {@code initializeMySQL} 的注释必须保留
 *       {@code initialTimeout} 的单位提醒（秒，不是毫秒，驱动里是
 *       {@code Thread.sleep(initialTimeout * 1000)}）—— 写成毫秒会让每次重连前先睡
 *       33 分钟，比「没有超时」更糟。</li>
 * </ol>
 *
 * <h2>锚内容，不锚行号</h2>
 * <p>全部断言锚在<b>文本内容</b>上，行号一律不出现：本仓文件一天内会被多个并行批次改过
 * 几十次，行号锚会静默失效。反过来，<b>找不到被断言的文件 / 找不到注释</b>也必须红
 * —— 否则重命名或整段删除会让 lint 变成空转（本仓 MEMORY 的「闸门未触及」形态）。
 */
@DisplayName("SocketTimeoutCommentAccuracy — 超时注释不得与代码相反")
class SocketTimeoutCommentAccuracyTest {

    private static final String AI =
            "src/main/java/com/letslearnenglish/minecraftplugin/core/ai/AIChatManager.java";
    private static final String DB =
            "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java";

    /**
     * 「缺省断言」的前缀词。刻意<b>不</b>用一条大正则：本类要能在断言消息里说清
     * 命中了哪一个词。
     *
     * <p><b>刻意不含单字「无」</b>：中文里「无限期」「无关」都以「无」开头，而
     * 「无限期」正是本缺陷原文里描述卡顿的词（「甚至无限期」）。收进来会让未来某次
     * 正常改写（如「把无限期压成 30 秒」）与 socketTimeout 邻近时假红 —— 本仓 MEMORY
     * 记过「lint 锚点过宽反而制造噪音」。下面六个词覆盖了这处缺陷的真实措辞。
     */
    private static final List<String> ABSENCE_WORDS =
            Arrays.asList("没有", "缺", "没设", "未设", "不含", "不存在");

    /** 被断言「缺省」的参数名。 */
    private static final List<String> TIMEOUT_PARAMS =
            Arrays.asList("socketTimeout", "connectTimeout");

    // ------------------------------------------------------------------
    // 工具（本类独立实现；与其它 lint 类零耦合，理由同 IndexAndPragmaLintsTest）
    // ------------------------------------------------------------------

    private static String sourceOf(String relPath) {
        Path p = Paths.get(relPath);
        if (!Files.exists(p)) {
            fail("源码文件不存在：" + p.toAbsolutePath()
                    + "（重命名会让本 lint 变成空转，所以这里必须红）");
        }
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("读取失败：" + p + " -> " + e.getMessage());
        }
    }

    /**
     * 只回吐<b>注释</b>里的字符（{@code //} 行注释与 {@code /* *}{@code /} 块注释），
     * 字符串字面量与文本块一律丢弃 —— 本类的断言对象是注释，不能让 URL 那个字符串
     * 冒充证词。
     *
     * <p>逐字符状态机而非正则：{@code "http://..."} 里的 {@code //} 会骗过正则，
     * 而本仓源码里 URL 字面量很多（{@code initializeMySQL} 就是一条）。
     */
    static String commentTextOf(String src) {
        StringBuilder out = new StringBuilder(src.length() / 4);
        int i = 0;
        int n = src.length();
        boolean inLine = false;      // 正在 // 注释里
        boolean inBlock = false;     // 正在 /* */ 里
        boolean inString = false;
        boolean inTextBlock = false;
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append('\n');   // 保留行边界，便于按「段」归组
                } else {
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
                out.append(c == '\n' ? '\n' : c);
                i++;
                continue;
            }
            if (inTextBlock) {
                if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                    inTextBlock = false;
                    i += 3;
                    continue;
                }
                i++;
                continue;
            }
            if (inString) {
                if (c == '\\' && i + 1 < n) {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
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
            i++;
        }
        return out.toString();
    }

    /**
     * 剥掉注释，保留代码（含字符串字面量）—— 给 L1 用：{@code initializeMySQL} 的 URL
     * 是在<b>代码</b>里拼的，注释里也写着同样的 {@code socketTimeout=} 字样，
     * 不剥的话「URL 里有超时」会被自己的注释证明，等于自证。
     */
    static String codeOnlyOf(String src) {
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

    /** 按空行把注释文本切成「段」，L3 以段为单位检查正向锚定。 */
    private static List<String> blocksOf(String commentText) {
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : commentText.split("\n", -1)) {
            String t = line.trim();
            // javadoc 的 /** 与 */ 也算分隔：它们标记块边界
            boolean boundary = t.isEmpty() || t.equals("*") || t.startsWith("*/") || t.startsWith("/**")
                    || t.startsWith("/*!");
            if (boundary) {
                if (cur.length() > 0) {
                    blocks.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(t).append('\n');
        }
        if (cur.length() > 0) {
            blocks.add(cur.toString());
        }
        return blocks;
    }

    /** 在注释文本里数「缺省断言」命中次数，并回吐可读的命中清单（供断言消息用）。 */
    private static List<String> absenceClaims(String commentText) {
        List<String> hits = new ArrayList<>();
        for (String word : ABSENCE_WORDS) {
            for (String param : TIMEOUT_PARAMS) {
                // 缺省词与参数之间允许极短的间隔（"缺 socketTimeout"、"没有 socketTimeout /
                // connectTimeout"），但不跨行、不跨句 —— 跨句会把「这里没有别的问题……
                // socketTimeout 存在」误判。
                Pattern p = Pattern.compile(Pattern.quote(word) + "[^\\n。；;]{0,8}"
                        + Pattern.quote(param));
                Matcher m = p.matcher(commentText);
                while (m.find()) {
                    hits.add(m.group());
                }
            }
        }
        return hits;
    }

    /** 取 {@code initializeMySQL} 方法体（到下一个同缩进的 {@code } 为止的保守切片）。 */
    private static String initializeMysqlBody(String codeOnly) {
        int at = codeOnly.indexOf("initializeMySQL(FileConfiguration");
        if (at < 0) {
            fail("找不到 initializeMySQL(FileConfiguration ...) —— 重命名/签名变更会让本 lint"
                    + " 变成空转，所以这里必须红");
        }
        int end = codeOnly.indexOf("connection = DriverManager.getConnection", at);
        if (end < 0) {
            fail("initializeMySQL 里找不到 DriverManager.getConnection —— 连接串可能被搬走，"
                    + "本 lint 需要跟着改，不能静默变绿");
        }
        return codeOnly.substring(at, end + 40);
    }

    // ------------------------------------------------------------------
    // 前提：两个工具真的按预期切分（否则下面四条 lint 全是空转）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("前提：commentTextOf / codeOnlyOf 真的按预期切分")
    class ToolPremise {

        @Test
        @DisplayName("commentTextOf 只留注释，字符串里的 socketTimeout= 不得冒充证词")
        void commentTextExcludesStringLiterals() {
            String src = "class X { void m() { String u = \"jdbc:mysql://h/db?a&socketTimeout=\" + 30000;"
                    + " // 这里是真的注释\n int a = 1; /* 块注释 */ } }";
            String comments = commentTextOf(src);
            assertTrue(comments.contains("这里是真的注释"), "应抽到行注释，实际：" + comments);
            assertTrue(comments.contains("块注释"), "应抽到块注释，实际：" + comments);
            assertFalse(comments.contains("jdbc:mysql"), "字符串字面量不得进入注释文本，实际：" + comments);
            assertFalse(comments.contains("int a = 1"), "代码不得进入注释文本，实际：" + comments);
        }

        @Test
        @DisplayName("codeOnlyOf 剥掉注释但保留字符串与代码")
        void codeOnlyKeepsStringsAndCode() {
            String src = "class X { void m() { String u = \"a&socketTimeout=30000\";"
                    + " // 注释里的 socketTimeout 不许自证\n int a = 1; } }";
            String code = codeOnlyOf(src);
            assertTrue(code.contains("socketTimeout=30000"), "字符串里的 URL 参数必须保留，实际：" + code);
            assertTrue(code.contains("int a = 1"), "代码必须保留，实际：" + code);
            assertFalse(code.contains("自证"), "注释必须被剥掉，实际：" + code);
        }

        @Test
        @DisplayName("absenceClaims 抓得到旧措辞，且不误伤「exists」式表述")
        void absenceClaimsDetectsOldWording() {
            // 旧措辞（本缺陷的两处原文，按词拼接以不让本仓再次出现整句假断言）
            assertFalse(absenceClaims("叠加 MySQL URL 缺 socketTimeout").isEmpty(),
                    "「缺 socketTimeout」必须被抓到");
            assertFalse(absenceClaims("URL 没有 socketTimeout / connectTimeout").isEmpty(),
                    "「没有 socketTimeout」必须被抓到");
            assertFalse(absenceClaims("initializeMySQL 没设 socketTimeout").isEmpty(),
                    "「没设 socketTimeout」必须被抓到");
            // 现在的措辞：只陈述存在与不足，不得命中
            assertTrue(absenceClaims("socketTimeout=30s 是存在的，但它不够").isEmpty(),
                    "「存在但不够」不是缺省断言，不得误伤");
            assertTrue(absenceClaims("虽有 socketTimeout=30s / connectTimeout=10s，仍不足").isEmpty(),
                    "「虽有…仍不足」不是缺省断言，不得误伤");
        }
    }

    // ------------------------------------------------------------------
    // L1：证据 —— URL 上真的有超时（L2 的前提）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("L1 initializeMySQL 的 URL 确实设了超时")
    class UrlCarriesTimeouts {

        @Test
        @DisplayName("URL 拼了 connectTimeout= / socketTimeout=，常量就是 10 s / 30 s")
        void urlHasBothTimeouts() {
            String body = initializeMysqlBody(codeOnlyOf(sourceOf(DB)));
            assertTrue(body.contains("\"&connectTimeout=\" + connectTimeoutMs"),
                    "initializeMySQL 的 URL 必须拼 connectTimeout（L2 的证据），实际方法体："
                            + body);
            assertTrue(body.contains("\"&socketTimeout=\" + socketTimeoutMs"),
                    "initializeMySQL 的 URL 必须拼 socketTimeout（L2 的证据），实际方法体："
                            + body);
            assertTrue(body.contains("10_000"), "connectTimeout 常量应为 10_000 ms");
            assertTrue(body.contains("30_000"), "socketTimeout 常量应为 30_000 ms");
        }
    }

    // ------------------------------------------------------------------
    // L2：AIChatManager 的注释不得断言「缺超时」
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("L2 AIChatManager 注释里零「缺省超时」断言")
    class NoAbsenceClaimsInAiChatManager {

        @Test
        @DisplayName("注释中不得出现「没有/缺/没设/未设/不含/不存在 + socketTimeout|connectTimeout」")
        void noAbsenceClaimAboutTimeouts() {
            String comments = commentTextOf(sourceOf(AI));
            assertFalse(comments.trim().isEmpty(),
                    "AIChatManager 的注释文本为空 —— 抽取工具失效会让本 lint 空转");
            List<String> hits = absenceClaims(comments);
            assertTrue(hits.isEmpty(),
                    "AIChatManager 的注释断言 MySQL URL「缺超时」，而 DatabaseManager."
                            + "initializeMySQL 的 URL 上 connectTimeout=10000ms、"
                            + "socketTimeout=30000ms 都在（见 L1）。这类注释会让下一个读代码的人"
                            + "得出相反的前提（以为必须靠线程侧兜底、或以为超时不存在）。"
                            + "请改写成「超时存在，但不够」并给出真正的理由（单次读≠一次操作、"
                            + "connectionLock 无超时）。命中：" + hits);
        }
    }

    // ------------------------------------------------------------------
    // L3：正向锚定 —— 提到 socketTimeout 就得带上生效值
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("L3 提到 socketTimeout 的注释必须带上生效值")
    class MentionsCarryValue {

        @Test
        @DisplayName("AIChatManager 提到 socketTimeout 的每一段注释都含 30（实际秒数）")
        void everyMentionCarriesTheValue() {
            String comments = commentTextOf(sourceOf(AI));
            List<String> mentioning = new ArrayList<>();
            for (String b : blocksOf(comments)) {
                if (b.contains("socketTimeout")) {
                    mentioning.add(b);
                }
            }
            assertFalse(mentioning.isEmpty(),
                    "AIChatManager 的注释已经不再提 socketTimeout —— 若是有意删除这段论证，"
                            + "请同步更新本 lint；否则说明抽取/切段失效，闸门在空转。");
            for (String b : mentioning) {
                assertTrue(b.contains("30"),
                        "提到 socketTimeout 的注释应同时给出实际生效值（30 秒 / 30s），"
                                + "否则读代码的人还得回去翻 DatabaseManager。该段：" + b);
            }
        }
    }

    // ------------------------------------------------------------------
    // L4：initialTimeout 的单位防御（秒 ≠ 毫秒）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("L4 initialTimeout 的「单位是秒」提醒必须保留")
    class InitialTimeoutUnitReminder {

        @Test
        @DisplayName("initializeMySQL 注释含单位提醒与 1000 乘法/33 分钟的后果说明")
        void unitReminderPresent() {
            String comments = commentTextOf(sourceOf(DB));
            boolean hasUnit = comments.contains("单位") && comments.contains("秒");
            boolean hasMultiplication = comments.contains("initialTimeout * 1000");
            boolean hasConsequence = comments.contains("33 分钟");
            assertTrue(hasUnit && hasMultiplication && hasConsequence,
                    "initializeMySQL 必须保留 initialTimeout 的单位防御注释：驱动把它当"
                            + "秒用（connectWithRetries 里 Thread.sleep(initialTimeout * 1000)），"
                            + "写成 2000 会变成每次重连前先睡 33 分钟，比没有超时更糟。"
                            + "现有：hasUnit=" + hasUnit + ", hasMultiplication=" + hasMultiplication
                            + ", hasConsequence=" + hasConsequence);
        }
    }
}
