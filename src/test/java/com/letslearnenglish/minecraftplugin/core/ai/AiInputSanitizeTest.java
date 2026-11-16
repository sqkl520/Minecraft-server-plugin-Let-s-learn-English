package com.letslearnenglish.minecraftplugin.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code AIChatManager.sanitizeInput} 的两类不变量：<b>行为</b>不变 + <b>正则已预编译</b>。
 *
 * <h2>被修的缺陷（性能）</h2>
 * <p>原实现是
 * {@code input.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "").trim()}。
 * {@link String#replaceAll(String, String)} <b>每次调用都 {@code Pattern.compile} 一次</b>，
 * 而这个方法位于 {@code onPlayerChat → sanitizeInput} 这条<b>每条玩家聊天消息都走一次</b>
 * 的路径上 —— 聊天是高频事件，正则编译是 {@code combo} 频段的固定开销。
 * 仓库里已有正确做法：{@code AnswerChecker.ZERO_WIDTH}、
 * {@code ConversationFeedback.CONTROL_CHARS_REGEX}。这里只是漏改。
 *
 * <h2>为什么必须同时有源码 lint</h2>
 * <p><b>性能改动在单测里不可观测</b> —— 把预编译改回 {@code replaceAll} 字符串形式，
 * 下面所有<b>行为</b>断言照样全绿（两种写法的剥除结果逐字相同）。这是本仓库已经吃过一次的
 * 教训（{@code performance-optimizations-need-source-lint}：删掉索引/PRAGMA 后数百个用例
 * 全绿）。所以真正的判据是<b>源码形状</b>：{@link #sanitizeInputUsesPrecompiledPattern()}
 * 直接扫 {@code AIChatManager.java} 的方法体，断言其中没有字符串字面量形式的
 * {@code replaceAll("...}，且有 {@code Pattern} 预编译常量。
 *
 * <p>该 lint <b>不锚行号</b>，只锚「方法签名 + 该方法体内容」—— 方法上下平移、
 * 类里新增上万行都不影响判定。
 */
@DisplayName("AIChatManager.sanitizeInput — 控制字符剥除行为 + 正则预编译源码 lint")
class AiInputSanitizeTest {

    /** 生产源码根（工作目录 = 项目根）。 */
    private static final Path SOURCE =
            Path.of("src/main/java/com/letslearnenglish/minecraftplugin/core/ai/AIChatManager.java");

    /** 一个未初始化实例：{@code sanitizeInput} 只用 {@code maxInputLength} 这一个字段。 */
    private static AIChatManager manager;

    @BeforeAll
    static void allocateUninitialisedInstance() throws Exception {
        // 不为一个纯字符串函数去搭完整的 Bukkit 插件 mock 树：Objenesis 跳过构造器分配实例，
        // 再把 maxInputLength 显式注入即可（字段初始化器不会跑，故必须显式设）。
        Class<?> objenesis = Class.forName("org.objenesis.ObjenesisStd");
        Object ob = objenesis.getDeclaredConstructor().newInstance();
        manager = (AIChatManager) objenesis.getMethod("newInstance", Class.class)
                .invoke(ob, AIChatManager.class);
        setMaxInputLength(200);
    }

    private static void setMaxInputLength(int value) throws Exception {
        Field f = AIChatManager.class.getDeclaredField("maxInputLength");
        f.setAccessible(true);
        f.setInt(manager, value);
    }

    /** 反射直调生产代码的 private 方法 —— 不复制一份逻辑（复制的测试是自证）。 */
    private static String sanitize(String input) throws Exception {
        Method m = AIChatManager.class.getDeclaredMethod("sanitizeInput", String.class);
        m.setAccessible(true);
        return (String) m.invoke(manager, input);
    }

    // ==================================================================
    // 1. 行为：语义必须与改动前逐字一致
    // ==================================================================

    @Test
    @DisplayName("ASCII 控制字符被剥除（含 NUL、BEL、VT、FF、ESC、US）")
    void stripsControlChars() throws Exception {
        // \x00 NUL, \x07 BEL, \x08 BS, \x0B VT, \x0C FF, \x0E SO, \x1F US
        String dirty = "\u0000he\u0007ll\u0008o\u000B w\u000Co\u000Erl\u001Fd";
        assertEquals("hello world", sanitize(dirty),
                "控制字符必须全部剥掉，只留可见文本");
    }

    @Test
    @DisplayName("合法文本原样保留（不误伤普通标点、数字、非 ASCII）")
    void keepsOrdinaryText() throws Exception {
        for (String clean : new String[]{
                "I like apples.",
                "hello, world! 123",
                "中文也能通过",
                "emoji 😀 不属控制字符",
                "a-b_c'd\"e",
                "tab\tinside"}) {
            assertEquals(clean.trim(), sanitize(clean),
                    "普通文本不得被改动：「" + clean + "」");
        }
    }

    @Test
    @DisplayName("首尾空白被 trim（含控制字符被剥后暴露出来的空白）")
    void trimsWhitespace() throws Exception {
        assertEquals("apple", sanitize("   apple   "));
        assertEquals("apple", sanitize("\u0001  apple  \u0002"),
                "剥掉两侧控制字符后暴露的空白也必须 trim 干净");
        assertEquals("", sanitize("   "), "纯空白应折成空串");
    }

    @Test
    @DisplayName("null / 空串返回空串，不抛")
    void nullAndEmptyAreSafe() throws Exception {
        assertEquals("", sanitize(null));
        assertEquals("", sanitize(""));
    }

    @Test
    @DisplayName("超长输入按 maxInputLength 截断（先截断，再剥除，最后 trim）")
    void truncatesToMaxInputLength() throws Exception {
        try {
            setMaxInputLength(5);
            assertEquals("abcde", sanitize("abcdefghij"),
                    "必须先截断到 maxInputLength 再剥控制字符 —— 顺序反了会让超长内容漏进 API");
            // 截断点恰好落在控制字符之前：5 个字符里含一个控制字符，剥完应只剩 4 个
            assertEquals("abcd", sanitize("abcd\u0007efg"),
                    "控制字符在截断窗口内时必须被剥掉，不得用后续字符回填（即不回填到 5 个）");
            assertEquals(5, sanitize("abcdefghij").length(),
                    "截断后的长度必须正好是 maxInputLength");
        } finally {
            setMaxInputLength(200);
        }
    }

    @Test
    @DisplayName("maxInputLength=0 时不得抛（substring 越界会打断整条聊天路径）")
    void zeroMaxInputLengthIsSafe() throws Exception {
        try {
            setMaxInputLength(0);
            assertEquals("", sanitize("anything"),
                    "maxInputLength=0 时截断到空串，而不是抛 StringIndexOutOfBoundsException");
        } finally {
            setMaxInputLength(200);
        }
        // 注：maxInputLength < 0 时 substring(0, -1) 仍会抛。那是另一处独立缺陷
        // （负的 ai-chat.max-input-length 配置），不属于本次「正则预编译」的范围，
        // 这里刻意不断言现状 —— 断言现状等于把缺陷固化成契约。
    }

    // ==================================================================
    // 2. 源码 lint：正则必须预编译（这是性能改动的唯一可观测判据）
    // ==================================================================

    /**
     * 扫 {@code sanitizeInput} 的<b>方法体</b>，断言：
     * <ol>
     *   <li>方法体内<b>没有</b> {@code .replaceAll("} 这种<b>直挂在变量/字符串上的</b>形式
     *       —— 那是 {@link String#replaceAll(String, String)}，每调用一次编译一次正则；</li>
     *   <li>方法体内确实用了 {@code .matcher(...).replaceAll(...)}（{@link java.util.regex.Matcher}
     *       的路径，正则来自预编译常量）；</li>
     *   <li>类里存在 {@code static final Pattern CONTROL_CHARS = Pattern.compile(...)}。</li>
     * </ol>
     *
     * <p><b>判据必须区分 {@code String.replaceAll} 与 {@code Matcher.replaceAll}</b>：
     * 两者名字相同，只有前者会重新编译正则。所以本 lint 判的是<b>调用链形状</b>
     * {@code <expr>.replaceAll("...}（不含 {@code matcher(}），而不是"方法体里有没有
     * {@code replaceAll(} 这个子串"——后者会把正确的 {@code matcher(...).replaceAll("")}
     * 误判成违规。
     *
     * <p><b>不锚行号、不锚 import 顺序</b>：先按方法签名定位方法体，再在体内判定。
     * 方法在文件里上移下移、类里新增任何代码都不影响本 lint。
     */
    @Test
    @DisplayName("sanitizeInput 用预编译 Pattern，不含每次编译的 String.replaceAll(\"…\")")
    void sanitizeInputUsesPrecompiledPattern() throws IOException {
        assertTrue(Files.isRegularFile(SOURCE),
                "必须能读到生产源码（工作目录应为项目根）: " + SOURCE.toAbsolutePath());
        String text = Files.readString(SOURCE);

        String body = methodBody(text, "sanitizeInput");
        assertFalse(body.isEmpty(), "必须能在源码里定位 sanitizeInput 的方法体");

        // String.replaceAll 的形状： `<某个不是 matcher(...) 的表达式>.replaceAll("`
        // 逐语句判定，避免跨语句误配。
        for (String statement : body.split(";")) {
            String s = statement.replaceAll("\\s+", "");
            int idx = s.indexOf(".replaceAll(\"");
            if (idx < 0) {
                continue;
            }
            assertTrue(s.lastIndexOf(".matcher(", idx) >= 0,
                    "sanitizeInput 里出现了 String.replaceAll(\"…\") —— 每次调用都会"
                            + " Pattern.compile 一次，而本方法是每条玩家聊天消息都走的路径。"
                            + " 违规语句：" + statement.trim());
        }
        assertTrue(body.contains(".matcher("),
                "预编译的收益来自 Pattern.matcher(...).replaceAll(...)：等价的 Matcher 路径，"
                        + " 实际方法体：\n" + body);

        // 常量本身必须真的是 static final Pattern 预编译，而不是同名局部变量。
        Pattern constant = Pattern.compile(
                "static\\s+final\\s+Pattern\\s+CONTROL_CHARS\\s*=\\s*Pattern\\.compile\\(\\s*\"");
        Matcher m = constant.matcher(text);
        assertTrue(m.find(),
                "必须存在 `static final Pattern CONTROL_CHARS = Pattern.compile(\"…\")` 预编译常量"
                        + "（类级常量，只编译一次）");
    }

    /**
     * 第二方向：控制字符的正则<b>内容</b>没被改动（预编译是等价重构，不得顺手改语义）。
     *
     * <p>锚在「常量声明里出现的那串字符类」上，而不是行号：把 pattern 字符串改成别的
     * 码位范围会立刻变红 —— 那会让某些控制字符重新漏进 API 请求体。
     */
    @Test
    @DisplayName("控制字符正则的码位范围未被改动（\\x00-\\x08 剥、\\t 保留等）")
    void controlCharRangeIsUnchanged() throws IOException {
        String text = Files.readString(SOURCE);
        Pattern range = Pattern.compile(
                "Pattern\\.compile\\(\\s*\"\\[\\\\\\\\x00-\\\\\\\\x08\\\\\\\\x0B\\\\\\\\x0C\\\\\\\\x0E-\\\\\\\\x1F\\]\"\\s*\\)");
        assertTrue(range.matcher(text).find(),
                "预编译常量的 pattern 必须仍是原范围 \\x00-\\x08 \\x0B \\x0C \\x0E-\\x1F"
                        + "（刻意保留 \\t \\n \\r，由 trim 处理）");
    }

    /** 按「方法签名 + 花括号配对」截出方法体（不含签名行）。 */
    private static String methodBody(String text, String methodName) {
        int sig = text.indexOf(methodName + "(String input)");
        if (sig < 0) {
            return "";
        }
        int open = text.indexOf('{', sig);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(open + 1, i);
                }
            }
        }
        return "";
    }
}
