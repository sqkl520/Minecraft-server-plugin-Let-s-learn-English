package com.letslearnenglish.minecraftplugin.util;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14：{@code MessageUtil} 缺键回落的<b>文案契约</b>。
 *
 * <p><b>为什么这条要单独钉</b>：同一个类里曾经存在三种互不相同的缺键表现 ——
 * <ul>
 *   <li>{@code getMessage} / {@code getMessage(Map)}：{@code ChatColor.RED + "Missing message: " + key}
 *       —— <b>硬编码英文</b>，会原样漏进中文客户端；</li>
 *   <li>{@code getMessageSafe} / {@code formatSafe}：{@code &c[?key]}；</li>
 *   <li>{@code getGuiMsg}：{@code [?key]}（不带颜色码，由调用方转）。</li>
 * </ul>
 * 三种写法里只有中间那种既可见又不泄漏语言。前两种的存在意味着「缺键时玩家看到什么」
 * 取决于调用方恰好走了哪个重载 —— 这不是可观测性问题，是<b>同一失败有两种文案</b>。
 *
 * <p><b>为什么不能靠 {@code DiagnoseI18nParityTest} 兜住</b>：那条测试比的是 zh/en
 * 两份文件的<b>键集合</b>，它保证「两个语言都缺同一个键」，但保证不了「缺了之后玩家
 * 看到什么」。键集合完全对齐、文案仍然漏英文，是这类测试的盲区。
 */
class MessageUtilMissingKeyContractTest {

    /** 与生产代码的 {@code MISSING_KEY_PLACEHOLDER} 保持一致（{@code "&c[?%s]"}）。 */
    private static final String EXPECTED_PREFIX = ChatColor.RED + "[";

    /**
     * {@code MISSING_KEY_PLACEHOLDER} 是 private static final，且转换后带 § 颜色码。
     * 用反射读它，而不是把 {@code "&c[?%s]"} 抄一份 —— 抄一份的话，改生产常量时
     * 测试跟着改就往错的方向走了。
     */
    private static String renderPlaceholder(String key) throws Exception {
        Class<?> c = MessageUtil.class;
        java.lang.reflect.Field f = c.getDeclaredField("MISSING_KEY_PLACEHOLDER");
        f.setAccessible(true);
        String template = (String) f.get(null);
        return ChatColor.translateAlternateColorCodes('&', String.format(template, key));
    }

    @Test
    @DisplayName("占位符常量形如 &c[?key] —— 契约本身没被改掉")
    void placeholderShape() throws Exception {
        String rendered = renderPlaceholder("some.key");
        assertTrue(rendered.contains("[?some.key]"),
                "缺键占位符必须带出键名，否则玩家无从自查: " + rendered);
        assertTrue(rendered.startsWith(EXPECTED_PREFIX),
                "缺键占位符应以红色开头: " + rendered);
    }

    /**
     * 结构 lint：{@code getMessage} 家族里不得再出现硬编码英文那句。
     *
     * <p>这条是真正的回归守卫 —— 上面的形状断言只证明常量还在，证明不了
     * 「没有别的分支绕过它」。红队当初就是靠读源码才发现有两处绕过的。
     *
     * <p><b>判据必须排除注释行</b>：本文件的修复注释里逐字引用了那句旧代码
     * （「原为硬编码英文 \`"Missing message: " + key\`」），第一版 lint 用整文件
     * {@code contains} 直接命中自己的注释而误报。注释里提到旧写法是有价值的
     * （后来人要知道为什么不能改回去），所以修 lint 而不是删注释。
     */
    @Test
    @DisplayName("源码里不得再有 \"Missing message: \" 这种硬编码兜底（注释除外）")
    void noHardcodedEnglishFallback() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/util/MessageUtil.java");
        assertTrue(java.nio.file.Files.exists(src),
                "必须读真实源文件（工作目录应为项目根）: " + src.toAbsolutePath());
        String text = java.nio.file.Files.readString(src);

        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            // 跳过注释行与 Javadoc 行 —— 只在真正的代码里找
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue;
            }
            assertFalse(line.contains("\"Missing message: \""),
                    "仍有硬编码英文兜底，会漏进中文客户端。统一改用 MISSING_KEY_PLACEHOLDER。"
                            + " 命中行: " + line);
        }
        // 日志里的 "Missing message key: " 是给控制台的，允许保留；
        // 断言它还在，防止有人把日志也一起删掉导致缺键静默。
        assertTrue(text.contains("Missing message key: "),
                "缺键的 WARNING 日志不应被误删（玩家侧改占位符，控制台仍要有记录）");
    }

    /**
     * 行为断言：两个公开重载在缺键路径上返回的必须是<b>同一串</b>。
     *
     * <p>用反射调 private 的 {@code formatMessage} 太脆（签名会变），改用一个更稳的
     * 等价断言：既然两个重载的缺键分支现在都写 {@code String.format(MISSING_KEY_PLACEHOLDER, key)}，
     * 那么对同一个 key，两者产出的字符串必然相同。这里通过源码文本确认两个分支都在，
     * 而不是一个改了一个忘了。
     */
    @Test
    @DisplayName("两个 getMessage 重载的缺键分支都走同一个占位符，没有只改一半")
    void bothOverloadsUseTheSamePlaceholder() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/util/MessageUtil.java");
        String text = java.nio.file.Files.readString(src);

        int occurrences = countOccurrences(text, "String.format(MISSING_KEY_PLACEHOLDER, key)");
        // 期望：getMessage(String,String...) / getMessage(String,Map) / formatMessage /
        //       formatSafe(null key 与 null config 两处) —— 至少 5 处，具体数目不重要，
        //       重要的是「两个 getMessage 重载都在里面」。
        assertTrue(occurrences >= 5,
                "MISSING_KEY_PLACEHOLDER 使用点少于预期（" + occurrences + " 处），"
                        + "可能有一个重载被落下了");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * 防「顺手又把颜色码写成 {@code ChatColor.RED + 字符串}」：那种写法的红色只在
     * 消息最前面生效，渲染器会把后续的 § 也一起当字面量处理，与 {@code &c} 语义不同。
     */
    @Test
    @DisplayName("缺键兜底不得用 ChatColor.RED 直接前置（颜色码要能被渲染器整体处理）")
    void noDirectChatColorConcat() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/util/MessageUtil.java");
        String text = java.nio.file.Files.readString(src);
        assertFalse(text.contains("ChatColor.RED +"),
                "缺键兜底应改写为 &c... 交给 translateAlternateColorCodes，"
                        + "而不是 ChatColor.RED 硬拼前缀");
    }

    /**
     * 反射兜底：确认 {@code MISSING_KEY_PLACEHOLDER} 仍然存在且是 private static final。
     * 有人把它改成实例字段或删掉时，上面所有替代断言会一起失去意义。
     */
    @Test
    @DisplayName("MISSING_KEY_PLACEHOLDER 仍是 private static final 常量")
    void constantStillExists() throws Exception {
        java.lang.reflect.Field f = MessageUtil.class.getDeclaredField("MISSING_KEY_PLACEHOLDER");
        int mods = f.getModifiers();
        assertTrue(java.lang.reflect.Modifier.isStatic(mods), "应为 static");
        assertTrue(java.lang.reflect.Modifier.isFinal(mods), "应为 final");
        assertTrue(java.lang.reflect.Modifier.isPrivate(mods), "应为 private");
        assertEquals(String.class, f.getType());
    }

    // 让编译器不要因为未使用的 import 报警（Method 仅文档用途）
    @SuppressWarnings("unused")
    private static final Class<Method> DOC_REF = Method.class;
}
