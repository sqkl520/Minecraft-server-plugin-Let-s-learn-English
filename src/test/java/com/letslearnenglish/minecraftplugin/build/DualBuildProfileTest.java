package com.letslearnenglish.minecraftplugin.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 双构建配置的<b>源码级 lint</b>。
 *
 * <h2>背景</h2>
 * 本项目发布两个 JAR，源码相同、编译目标不同：
 * <table border="1">
 *   <caption>两个产物的支持范围</caption>
 *   <tr><th>产物</th><th>paper-api</th><th>Java</th><th>支持的服务端</th></tr>
 *   <tr><td>{@code Let's Learn English!-<ver>.jar}</td><td>1.21.4</td><td>21</td>
 *       <td>MC 1.21.4 – 1.21.11</td></tr>
 *   <tr><td>{@code Let's Learn English!-<ver>-mc26.jar}</td><td>26.3</td><td>25</td>
 *       <td>MC 26.1+</td></tr>
 * </table>
 *
 * <h2>为什么是 lint 而不是行为测试</h2>
 * pom 的 profile 只在构建期生效，单元测试里没有任何办法「跑一次带 profile 的构建」
 * （那需要另起 Maven 进程 + 另一个 JDK）。而这套配置一旦错，症状全部是**延迟到服主那边**
 * 才暴露的：
 * <ul>
 *   <li>{@code jar.suffix} 忘了改 → 两个包同名，第二次构建覆盖第一次，
 *       发布时只剩一个包，而服主不知道自己拿到的是哪个；</li>
 *   <li>{@code java.version} 忘了改 → 用 Java 21 编 26.x，编译器报「无效的目标发行版」，
 *       构建期能发现（这条还算好）；</li>
 *   <li>{@code plugin.api.version} 忘了改 → 26.x 包带着 {@code api-version: 1.21} 发布，
 *       装到 26.x 上行为未定义；</li>
 *   <li>默认档被误改成 profile → {@code mvn package} 零参数构建突然要求 JDK 25，
 *       CI 和所有开发者的习惯命令全炸。</li>
 * </ul>
 * 这些都没法用运行时断言覆盖，只能读 pom 本身。
 */
@DisplayName("DualBuildProfileTest — 双构建 pom 配置")
class DualBuildProfileTest {

    private static String pom() {
        Path p = Paths.get("pom.xml");
        if (!Files.isRegularFile(p)) {
            fail("找不到 pom.xml：" + p.toAbsolutePath());
        }
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("读取 pom.xml 失败：" + e.getMessage());
            return "";
        }
    }

    /** 取 {@code <id>mc26</id>} 那个 profile 的 XML 片段（到下一个 </profile> 为止）。 */
    private static String mc26Profile() {
        String t = pom();
        int id = t.indexOf("<id>mc26</id>");
        assertTrue(id > 0, "pom 里找不到 <id>mc26</id> 的 profile —— 26.x 那一档构建入口没了");
        int start = t.lastIndexOf("<profile>", id);
        int end = t.indexOf("</profile>", id);
        assertTrue(start > 0 && end > start, "mc26 profile 的 XML 片段不完整");
        return t.substring(start, end);
    }

    @Nested
    @DisplayName("默认档（不带 -P）必须保持 Java 21 / 1.21.4")
    class DefaultProfile {

        @Test
        @DisplayName("默认 java.version=21、paper-api 1.21.4、api-version 1.21、产物名无后缀")
        void defaultsUnchanged() {
            String t = pom();
            assertTrue(t.contains("<java.version>21</java.version>"),
                    "默认 java.version 必须是 21 —— 改成别的会让 `mvn package` 要求更高 JDK，"
                            + "CI 与所有开发者的习惯命令全炸");
            assertTrue(t.contains("<paper.api.version>1.21.4-R0.1-SNAPSHOT</paper.api.version>"),
                    "默认 paper-api 必须是 1.21.4 —— 它是普通包的支持下限");
            assertTrue(t.contains("<plugin.api.version>1.21</plugin.api.version>"),
                    "默认 plugin.api.version 必须是 1.21");
        }

        @Test
        @DisplayName("默认档没有被写成 profile（保证 mvn package 零参数即可用）")
        void notHiddenInProfile() {
            String t = pom();
            // 在 <profiles> 段之前，java.version 必须是 21
            int profilesStart = t.indexOf("<profiles>");
            assertTrue(profilesStart > 0, "pom 里找不到 <profiles> 段");
            String before = t.substring(0, profilesStart);
            assertTrue(before.contains("<java.version>21</java.version>"),
                    "默认 java.version 必须在 <profiles> 之外的 <properties> 里 —— "
                            + "若被挪进某个 profile，`mvn package` 就没有默认值了");
        }
    }

    @Nested
    @DisplayName("mc26 档")
    class Mc26Profile {

        @Test
        @DisplayName("用 26.x 的 stable paper-api 与 Java 25")
        void apiAndJava() {
            String p = mc26Profile();
            assertTrue(p.contains("<paper.api.version>26.3.build.231-stable</paper.api.version>"),
                    "mc26 profile 必须用带构建号的 stable 26.x 版本（SNAPSHOT 不可复现）");
            assertTrue(p.contains("<java.version>25</java.version>"),
                    "mc26 profile 必须把 java.version 设成 25 —— 26.x 的 paper-api 是 major 69，"
                            + "Java 21 编译器读不了，会报「无效的目标发行版」");
        }

        @Test
        @DisplayName("api-version 必须比默认档高，且产物名必须带后缀")
        void apiVersionAndSuffix() {
            String p = mc26Profile();
            assertTrue(p.contains("<plugin.api.version>26.1</plugin.api.version>"),
                    "mc26 profile 的 api-version 必须是 26.x 系列（当前取 26.1，让一个包覆盖整条 26.x 线）。"
                            + "若沿用 1.21，装到 26.x 服务端上行为未定义");
            assertTrue(p.contains("<jar.suffix>-mc26</jar.suffix>"),
                    "mc26 profile 必须给产物加 -mc26 后缀 —— 否则两个包同名，"
                            + "后一次构建会覆盖前一次，发布时只剩一个包");
        }
    }

    @Nested
    @DisplayName("外层的变量接线")
    class Wiring {

        @Test
        @DisplayName("finalName 引用 jar.suffix")
        void finalNameUsesSuffix() {
            assertTrue(pom().contains("<finalName>${project.name}-${project.version}${jar.suffix}</finalName>"),
                    "finalName 必须拼上 ${jar.suffix}，否则 profile 改了后缀也不生效");
        }

        @Test
        @DisplayName("plugin.yml 的 api-version 走资源过滤变量")
        void pluginYmlUsesVariable() {
            Path p = Paths.get("src/main/resources/plugin.yml");
            String t;
            try {
                t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            } catch (IOException e) {
                fail("读取 plugin.yml 失败：" + e.getMessage());
                return;
            }
            assertTrue(t.contains("api-version: \"${plugin.api.version}\""),
                    "plugin.yml 的 api-version 必须写成 ${plugin.api.version}，由 profile 注入 —— "
                            + "写死成 1.21 会让 -mc26 包带着错误的 api-version 发布");
            assertEquals(1, countOccurrences(t, "api-version:"),
                    "plugin.yml 里只应有一处 api-version");
        }

        @Test
        @DisplayName("plugin.yml 仍参与资源过滤（否则 ${...} 不会被替换）")
        void pluginYmlIsFiltered() {
            String t = pom();
            int resStart = t.indexOf("<resources>");
            int resEnd = t.indexOf("</resources>");
            assertTrue(resStart > 0 && resEnd > resStart, "找不到 <resources> 段");
            String res = t.substring(resStart, resEnd);
            assertTrue(res.contains("<filtering>true</filtering>"),
                    "<resources> 里必须有一档 filtering=true 覆盖 plugin.yml");
            assertTrue(res.contains("plugin.yml"),
                    "<resources> 必须包含 plugin.yml，否则 ${plugin.api.version} 原样留在产物里");
        }
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
