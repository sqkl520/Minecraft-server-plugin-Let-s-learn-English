package com.letslearnenglish.minecraftplugin.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14：{@code ChatHistoryGUI} 的 lore 换行算法回归测试。
 *
 * <p><b>为什么要单独立一条测试</b>：这段循环是「纯函数式」的，但被埋在 GUI 渲染里、
 * 所在的 {@code ChatHistoryGUI} 行覆盖率长期为 0。它出过两次互相独立的错：
 * <ol>
 *   <li>D2-8 原实现按 UTF-16 <b>码元</b>切窗，emoji 密集文本每窗只走约 20 个真实字符，
 *       行数翻倍 —— 而 Minecraft 的 lore 有行数上限，超出部分客户端直接不渲染，
 *       <b>消息尾部被静默吞掉</b>；</li>
 *   <li>改按码点后，{@code full.codePointCount(pos, full.length())} 被放在循环条件里，
 *       每次调用 O(剩余长度)，整段退化为 O(n²/窗口)（红队实测 32k 码点 20.8ms）。</li>
 * </ol>
 * 两者的失败形态都<b>不报错</b>：第一种是内容丢失，第二种是主线程卡顿，单测里都看不见。
 * 所以这里既钉语义（行数、码点边界、可重建），也钉掉「每轮重算总长」这个形状。
 *
 * <p><b>本测试复刻的是算法，不是调用私有方法</b>：{@code wrapLore} 是 {@code private}，
 * 为测试放开可见性会把生产签名拖进测试关注点。复刻的代价是「改了生产代码而忘了改这里」
 * 会漏报 —— 所以下面 {@link #productionShapeUsesSingleCodePointCount} 直接读源码文本，
 * 断言那段循环里 {@code codePointCount} 不再出现在 {@code while} 条件中。
 */
class ChatHistoryLoreWrapTest {

    /** 与生产代码的 {@code wrapLen} 保持一致（ChatHistoryGUI.java 的局部变量）。 */
    private static final int WRAP_LEN = 40;

    /**
     * 生产算法的逐行复刻。改动生产循环时必须同步此实现，
     * 否则 {@link #productionShapeUsesSingleCodePointCount} 会先失败提醒。
     */
    private static List<String> wrap(String full) {
        List<String> lore = new ArrayList<>();
        int remainingCodePoints = full.codePointCount(0, full.length());
        int pos = 0;
        while (pos < full.length()) {
            int advance = Math.min(WRAP_LEN, remainingCodePoints);
            int end = full.offsetByCodePoints(pos, advance);
            lore.add(full.substring(pos, end));
            remainingCodePoints -= advance;
            pos = end;
        }
        return lore;
    }

    private static int codePoints(String s) {
        return s.codePointCount(0, s.length());
    }

    @Test
    @DisplayName("纯 emoji：100 码点切成 3 行，而不是按码元切成 5 行")
    void pureEmojiUsesCodePoints() {
        // 每个 emoji 是一个代理对 = 2 个 char，100 个 = 200 char / 100 code point
        String full = "😀".repeat(100);
        assertEquals(200, full.length(), "前置：200 个 UTF-16 码元");
        assertEquals(100, codePoints(full), "前置：100 个码点");

        List<String> lines = wrap(full);

        // 按码元切 = ceil(200/40) = 5 行；按码点切 = ceil(100/40) = 3 行
        assertEquals(3, lines.size(), "必须按码点切，否则 emoji 密集文本行数翻倍");
        assertEquals(40, codePoints(lines.get(0)));
        assertEquals(40, codePoints(lines.get(1)));
        assertEquals(20, codePoints(lines.get(2)));
    }

    @Test
    @DisplayName("任何一行都不在代理对中间切断")
    void neverSplitsSurrogatePair() {
        String full = "😀".repeat(50) + "abc" + "😁".repeat(50);
        for (String line : wrap(full)) {
            assertFalse(line.isEmpty(), "不应产生空行");
            assertFalse(Character.isLowSurrogate(line.charAt(0)),
                    "行首不能是 low surrogate（说明上一刀切在代理对中间）");
            assertFalse(Character.isHighSurrogate(line.charAt(line.length() - 1)),
                    "行尾不能是 high surrogate（说明这一刀切在代理对中间）");
        }
    }

    @Test
    @DisplayName("拼回去与原文逐字节相同 —— 一个字都不能丢")
    void reconstructionLossless() {
        String[] cases = {
                "",
                "a",
                "a".repeat(39),
                "a".repeat(40),
                "a".repeat(41),
                // emoji 恰好跨在 40 码点边界上：39 个 ascii + 1 个 emoji
                "a".repeat(39) + "😀",
                // CJK 扩展 B（也是代理对）
                "𠀀".repeat(41),
                "混合 mixed 😀 文本 with ascii",
        };
        for (String full : cases) {
            assertEquals(full, String.join("", wrap(full)),
                    "重建必须无损，输入=" + escape(full));
        }
    }

    @Test
    @DisplayName("空串与不足一行的串")
    void smallInputs() {
        assertEquals(0, wrap("").size());
        assertEquals(1, wrap("a").size());
        assertEquals(1, wrap("a".repeat(40)).size(), "恰好 40 码点应是一行");
        assertEquals(2, wrap("a".repeat(41)).size(), "41 码点应是两行");
        assertEquals(2, wrap("a".repeat(80)).size());
        assertEquals(3, wrap("a".repeat(81)).size());
    }

    /**
     * 结构 lint：生产代码必须只算一次总码点。
     *
     * <p>这是本文件唯一真正防「性能回归」的断言 —— 上面几条只钉语义，把
     * {@code codePointCount(pos, len)} 放回 {@code while} 条件里它们依然全绿
     * （语义正确、只是慢），这正是这条回归当初能溜进来的原因。
     */
    @Test
    @DisplayName("生产循环里 codePointCount 不得出现在 while 条件中（防 O(n²) 复发）")
    void productionShapeUsesSingleCodePointCount() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/gui/ChatHistoryGUI.java");
        assertTrue(java.nio.file.Files.exists(src),
                "测试必须读真实源文件（工作目录应为项目根）: " + src.toAbsolutePath());
        String text = java.nio.file.Files.readString(src);

        // 定位 lore 换行那段循环
        int loop = text.indexOf("int remainingCodePoints = full.codePointCount(0, full.length());");
        assertTrue(loop >= 0,
                "必须预先算一次 full.codePointCount(0, full.length()) —— 逐轮重算是 O(n²)");

        int whilePos = text.indexOf("while (pos < full.length())", loop);
        assertTrue(whilePos >= 0, "应能找到 lore 换行循环");

        // 循环体整体（从 while 到下一个 lore.add(...) 之后的那一行）里不得再出现
        // codePointCount。第一版这条 lint 只检查了 while 的**条件串**，结果「把
        // codePointCount 挪进循环体」的变异体全绿通过 —— 变异验证才发现 lint 定位错了，
        // 因为真正的 O(n²) 形态是 `advance = Math.min(w, full.codePointCount(pos, len))`
        // 写在体内，而 while 条件本身永远是 `pos < full.length()`。
        int bodyEnd = text.indexOf("pos = end;", whilePos);
        assertTrue(bodyEnd > whilePos, "应能找到循环体结尾（pos = end;）");
        String body = text.substring(whilePos, bodyEnd);
        assertFalse(body.contains("codePointCount"),
                "循环体内不得再出现 codePointCount（O(n²) 回归：每轮重算剩余长度）:\n"
                        + body.trim());
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && i < 20; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        if (s.length() > 20) sb.append("...");
        return sb.toString();
    }
}
