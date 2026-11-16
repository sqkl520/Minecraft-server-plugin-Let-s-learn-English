package com.letslearnenglish.minecraftplugin.gui.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14：全仓每一个 {@code Bukkit.createInventory(...)} 都必须挂 {@link PluginGuiHolder}。
 *
 * <h2>为什么这条不变量必须有 lint（而不是靠注释）</h2>
 * <p>{@code GlobalGuiActionGuardListener} 是「防物品注入」的最后一道网，而它的归属判定
 * 完全依赖 {@link GUIUtils#isPluginGUI} → holder。也就是说：
 * <b>一个没挂 holder 的界面，等于完全不受这道网保护</b> —— 玩家 shift-click 就能把背包物品
 * 塞进展示槽，关界面即丢；双击收集还能把展示物扫走（反向失窃）。
 *
 * <p>这类缺陷在本仓<b>已复发三次</b>（见 {@code GlobalGuiActionGuardListener} 类注释的
 * P0-5 / Wave 3 / ProfileGUI 记录），每次都是「新加了一个界面，忘了挂 holder」或
 * 「铺满了以为不需要」。铺满是布局的偶然属性，不是不变量：只要某页因配置关闭、
 * 材料缺失或条件分支少渲染一格，上半区就出现落点。
 *
 * <p>现状（本 lint 落地时实测）：21 处 {@code createInventory} 全部已挂 holder。
 * 这条 lint 的价值不在「现在是对的」，而在<b>第 22 处被加进来时立刻红</b>。
 *
 * <h2>为什么用源码正则而不是反射</h2>
 * <p>{@code createInventory} 是 Bukkit 静态方法，运行期调用需要完整 Inventory 环境；
 * 而这条不变量是<b>源码形状</b>问题（"这个调用点的第一个实参是什么"），静态检查
 * 才是对的层次。配合 {@link GuiViewKeyCoverageTest}（枚举与调用点一一对应）形成
 * 双向约束：加了 enumerate 没接线 → 那条红；加了调用点没挂 holder → 这条红。
 */
class EveryGuiHasHolderLintTest {

    /** 仓库根：测试工作目录是项目根（与其它结构 lint 同约定）。 */
    private static final Path SRC_ROOT =
            Path.of("src/main/java/com/letslearnenglish/minecraftplugin");

    @Test
    @DisplayName("每一处 Bukkit.createInventory(...) 的第一个实参都必须是 PluginGuiHolder")
    void everyCreateInventoryUsesAHolder() throws IOException {
        assertTrue(Files.isDirectory(SRC_ROOT),
                "必须能读到源码树（工作目录应为项目根）: " + SRC_ROOT.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        int total = 0;

        for (Path file : javaSources()) {
            String text = Files.readString(file);
            for (int idx = text.indexOf("Bukkit.createInventory("); idx >= 0;
                 idx = text.indexOf("Bukkit.createInventory(", idx + 1)) {
                // 跳过注释里的提及：GuiViewKey / GlobalGuiActionGuardListener 的 Javadoc
                // 会引用这个 API 名称来解释 holder 机制，那不是调用点。
                // 判据取「该行在 createInventory 之前的内容」是否以 // 或 * 开头。
                if (isInsideComment(text, idx)) {
                    continue;
                }
                total++;
                // 取左括号之后的一小段：第一个实参足够判断，且不会把整个方法体吃进来。
                int argStart = idx + "Bukkit.createInventory(".length();
                int argEnd = Math.min(text.length(), argStart + 200);
                String argHead = text.substring(argStart, argEnd);

                if (!argHead.stripLeading().startsWith("PluginGuiHolder.of(")
                        && !argHead.stripLeading().startsWith("holder,")) {
                    offenders.add(file + " → " + oneLine(argHead, 80));
                }
            }
        }

        assertTrue(total > 0, "一处 createInventory 都没找到 —— 正则或路径失效了，lint 失去意义");
        assertTrue(offenders.isEmpty(),
                "以下 createInventory 调用点没有挂 PluginGuiHolder，等于绕开全局 GUI 物品守卫"
                        + "（shift-click 注入 / 双击反向失窃）:\n  " + String.join("\n  ", offenders));
    }

    /**
     * 该偏移是否落在注释里 —— 取所在行 `createInventory` 之前的部分判断。
     *
     * <p>不需要完整的词法分析：本仓的注释风格只有行注释（{@code //}）与块注释行首
     * （{@code *}）。按行首判断足够，且不会误判「代码行尾的 // 注释」那种情况
     * （那种情况里 createInventory 确实在代码中，应先于 // 出现）。
     */
    private static boolean isInsideComment(String text, int idx) {
        int lineStart = text.lastIndexOf('\n', idx) + 1;
        String prefix = text.substring(lineStart, idx).trim();
        return prefix.startsWith("//") || prefix.startsWith("*") || prefix.startsWith("/*");
    }

    /**
     * 第二个方向：不能有「挂了 holder 但 holder 是 null」的写法。
     *
     * <p>{@code createInventory(null, size, title)} 在 Bukkit 上是合法的 —— 它退化为
     * 一个普通容器，{@link GUIUtils#isPluginGUI} 判不出来，守卫静默失效，
     * <b>且编译、运行、开界面全都不会报错</b>。这是比"忘了挂"更隐蔽的形态。
     */
    @Test
    @DisplayName("不得出现 createInventory(null, ...) —— 那会静默退化成普通容器")
    void noNullHolder() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaSources()) {
            String text = Files.readString(file);
            // 跨行也要抓：createInventory(\n null,
            Matcher m = Pattern.compile("createInventory\\(\\s*null\\s*,").matcher(text);
            while (m.find()) {
                offenders.add(file + " @offset " + m.start());
            }
        }
        assertTrue(offenders.isEmpty(),
                "createInventory(null, ...) 会让界面不在 GlobalGuiActionGuardListener 的保护范围内，"
                        + "且运行时毫无征兆:\n  " + String.join("\n  ", offenders));
    }

    /**
     * 第三个方向：{@link GUIUtils#isPluginGUI} 的判据不得退回标题字符串匹配。
     *
     * <p>标题是玩家语言相关的（{@code getGuiMsg}），且可以被配置改；用它做身份判定
     * 等于「换语言就换身份」。R5-11 / R5-5 已经把全仓的身份判定统一到 holder，
     * 这条钉住它别退回去。
     */
    @Test
    @DisplayName("GUIUtils.isPluginGUI 必须按 holder 判定，不得退回标题匹配")
    void identityIsHolderBased() throws IOException {
        Path guiUtils = SRC_ROOT.resolve("gui/GUIUtils.java");
        assertTrue(Files.exists(guiUtils), "应能找到 GUIUtils: " + guiUtils.toAbsolutePath());
        String text = Files.readString(guiUtils);

        int method = text.indexOf("isPluginGUI(");
        assertTrue(method >= 0, "应能找到 isPluginGUI 方法");
        // 取方法体（到下一个 "private " / "public " 或 800 字符内）
        int bodyLimit = Math.min(text.length(), method + 800);
        String body = text.substring(method, bodyLimit);

        assertTrue(body.contains("PluginGuiHolder") || body.contains("GuiIdentity"),
                "isPluginGUI 必须走 holder / GuiIdentity 判定，实际方法体未提及任何一方");
        assertFalse(body.contains("getTitle()"),
                "isPluginGUI 不得用 getTitle() 做判定 —— 标题随玩家语言变化，"
                        + "用它判身份等于「换语言就换身份」");
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(SRC_ROOT)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }

    private static String oneLine(String s, int max) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
