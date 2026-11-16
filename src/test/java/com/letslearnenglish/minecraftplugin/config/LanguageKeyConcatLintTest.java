package com.letslearnenglish.minecraftplugin.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14（wave-3 锐评 GAP-2）：<b>不得用未归一化的语言值去拼接 i18n 资源键</b>。
 *
 * <h2>这条 lint 防的是什么</h2>
 * <p>语言值有两个来源，都可能不是规范形式：`config.yml` 的 `general.language`
 * （服主手写，`cn`/`ZH`/`zh_CN` 都见过）与 `player_lang.yml`
 * （文件可被手工编辑）。全仓有两个地方把这种原始值<b>直接拼进键名</b>：
 * <pre>
 *   "prefix." + lang            // 取 config.yml 里的 prefix.zh / prefix.en
 * </pre>
 * 一旦 `lang` 是 `cn`，拼出来的是 `prefix.cn` —— 该键不存在 → `getString` 返回 null
 * → 静默回落到语言文件自带的 prefix，**服主在 config.yml 里配的覆盖被无声丢弃**。
 *
 * <p>这正是 `ConfigManager.canonicalLanguage` 存在的理由。本轮改动期间，
 * `DiagnoseCommand.fromConfig` 与 `ProxyMessenger.resolvePrefix` 两处都漏了归一化，
 * 而 `LetsLearnEnglish.buildDailyReminderMessage` 因为走 `getDefaultLanguage()`
 * （内部已归一化）而幸免 —— 三个同类调用点，只有一个碰巧是对的。
 * 这种「同类三处、其中一处正确」的形态靠读代码极易漏，故用 lint 钉住。
 *
 * <h2>为什么是源码 lint 而不是行为测试</h2>
 * <p>行为测试要构造「config.yml 里 prefix.zh 被覆盖 + general.language=cn」的完整配置链，
 * 而那需要真实的 ConfigManager + 文件系统。相比之下这条不变量是**源码形状**问题
 * （"这个字符串拼接的右操作数归一化了吗"），静态检查才是对的层次 ——
 * 且它同样能抓到未来新增的第四处调用点。
 */
class LanguageKeyConcatLintTest {

    private static final Path SRC_ROOT =
            Path.of("src/main/java/com/letslearnenglish/minecraftplugin");

    @Test
    @DisplayName("不得出现 \"prefix.\" + <未归一化变量> 的拼接")
    void noRawLanguageKeyConcat() throws IOException {
        assertTrue(Files.isDirectory(SRC_ROOT),
                "必须能读到源码树（工作目录应为项目根）: " + SRC_ROOT.toAbsolutePath());

        // 归一化后的值在本仓一律命名为 canonical / canonicalLang / defaultLang
        // （后者来自 getDefaultLanguage()，内部已归一化）。
        //
        // 判据演进（两次误报换来的）：
        //   第 1 版：要求"这一行里出现 canonicalLanguage( 调用" → DiagnoseCommand 的调用
        //            跨两行、ProxyMessenger 用变量 canonical，三个**已修好**的位置全误报；
        //   第 2 版：按 " + " 右操作数的标识符判 → DiagnoseCommand 的真实形状是
        //            `main.getString("prefix."` ← 行尾无加号，`+ ConfigManager.canonical...`
        //            在**下一行开头**，右操作数被解析成空串 → 仍误报。
        //   第 3 版（当前）：先把跨行语句**拼平**再判，避开所有换行形状问题。
        java.util.Set<String> SAFE_NAMES = java.util.Set.of(
                "canonical", "canonicalLang", "canonicalLanguage", "defaultLang");

        List<String> offenders = new ArrayList<>();
        int sites = 0;

        for (Path file : javaSources()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                // 跳过注释行 —— 本仓注释里大量引用 `"prefix." + lang` 来讲道理
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                int prefixIdx = line.indexOf("\"prefix.\"");
                if (prefixIdx < 0) {
                    continue;
                }
                sites++;

                // 把本行从 "prefix." 起，连同后续最多两行，拼成一条扁平语句
                StringBuilder sb = new StringBuilder(line.substring(prefixIdx));
                for (int k = 1; k <= 2 && i + k < lines.size(); k++) {
                    sb.append(' ').append(lines.get(i + k).trim());
                }
                String flat = sb.toString();

                boolean ok = flat.contains("canonicalLanguage(")
                        || flat.contains("getDefaultLanguage()");
                if (!ok) {
                    // 退化判据：看 "prefix." 之后第一次 + 后面的标识符
                    int plus = flat.indexOf('+');
                    if (plus >= 0) {
                        String rhs = flat.substring(plus + 1).trim();
                        String ident = rhs.replaceAll("[^A-Za-z0-9_].*$", "");
                        ok = SAFE_NAMES.contains(ident);
                    }
                }
                if (!ok) {
                    offenders.add(file.getFileName() + ":" + (i + 1) + "  " + flat);
                }
            }
        }

        assertTrue(sites > 0,
                "一处 prefix 键拼接都没找到 —— 正则失效或路径变了，本 lint 已失去意义");
        assertTrue(offenders.isEmpty(),
                "以下位置用未归一化的语言值拼接 prefix 键，"
                        + "`general.language: cn` 或 `player_lang.yml` 里的大小写笔误会让 "
                        + "config.yml 的前缀覆盖被静默丢弃。请改用 "
                        + "ConfigManager.canonicalLanguage(lang) 或 getDefaultLanguage():\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * 第二个方向：`getMessageConfig(String)` 的调用方若传原始值，也已在
     * ConfigManager 内部归一化（这是修复点），因此这里只断言
     * **ConfigManager 自己的实现**仍然做了归一化 —— 防止有人把它改回去。
     */
    @Test
    @DisplayName("ConfigManager.getMessageConfig(String) 仍然归一化入参")
    void getMessageConfigStillCanonicalises() throws IOException {
        Path f = SRC_ROOT.resolve("config/ConfigManager.java");
        assertTrue(Files.exists(f), "应能找到 ConfigManager: " + f.toAbsolutePath());
        String text = Files.readString(f);

        int idx = text.indexOf("public FileConfiguration getMessageConfig(String lang)");
        assertTrue(idx >= 0, "应能找到 getMessageConfig(String) 方法");
        int end = Math.min(text.length(), idx + 600);
        String body = text.substring(idx, end);

        assertTrue(body.contains("canonicalLanguage(") || body.contains("isZh("),
                "getMessageConfig(String) 必须归一化入参（canonicalLanguage / isZh），"
                        + "否则 `ZH` 会选错语言文件。实际方法体未提及任何归一化调用");
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(SRC_ROOT)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }
}
