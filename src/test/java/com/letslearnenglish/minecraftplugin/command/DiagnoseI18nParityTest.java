package com.letslearnenglish.minecraftplugin.command;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 5：{@code /lea diagnose} 的 i18n 落地护栏。
 *
 * <p><b>为什么需要它：</b>{@code DiagnoseCommand} 的文案从"硬编码英文字符串"
 * 改成"按 key 查 yml"之后，编译器对拼错的 key 一无所知 —— {@code msg.get("db.sqllite")}
 * 会编译通过、运行时打印 {@code [?diagnose.db.sqllite]}，而诊断命令恰恰是服主在
 * "已经有故障"时才会敲的最后一根稻草。所以这里用真实资源文件（JAR 内的
 * {@code messages/zh.yml} 与 {@code messages/en.yml}）钉住三件事：
 *
 * <ol>
 *   <li><b>代码里出现的每个 key 都真的存在于两份文件</b>——用源码扫描拿 key 全集，
 *       比手工维护一份清单更不容易随重构失效；</li>
 *   <li><b>zh/en 键集合完全一致</b>——插件只加载一种语言，缺键的那一边不会报错，
 *       只会静默显示 {@code [?key]}；</li>
 *   <li><b>占位符集合两边一致且代码传参覆盖得到</b>——{@code {port}} 写成 {@code {prot}}
 *       在 YAML 里完全合法，运行时只会留下一对花括号。</li>
 * </ol>
 *
 * <p><b>为什么读源码而不是反射：</b>{@code Msg} 的 key 是运行期字符串拼接
 * （{@code "diagnose." + key}），无法从字节码常量池恢复完整的调用点清单；
 * 源码扫描是唯一能覆盖"代码 → 资源"全链路的静态手段。
 *
 * <p>不触碰 Bukkit 运行时：只用到 {@link YamlConfiguration}（纯内存实现）。
 */
class DiagnoseI18nParityTest {

    private static final String ZH = "messages/zh.yml";
    private static final String EN = "messages/en.yml";
    private static final String SOURCE = "src/main/java/com/letslearnenglish/minecraftplugin/"
            + "command/DiagnoseCommand.java";

    /** YAML 花括号占位符，如 {@code {port}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_\\-]+)}");

    /** {@code {prefix}} 由 MessageUtil / {@link #fromConfig} 自行填充，不算代码实参。 */
    private static final String PREFIX_PLACEHOLDER = "prefix";

    /**
     * 一次 {@code msg.get(...)} 调用点。
     *
     * @param key   {@code diagnose.} 之后的键
     * @param names 代码传入的占位符名（偶数下标实参且为字符串字面量）
     */
    private record CallSite(String key, Set<String> names) {
    }

    /**
     * 解析源码里所有 {@code msg.get(...)} 调用点的字面量实参。
     *
     * <p>不用正则一把梭的原因：实参里有 {@code String.valueOf(port)}、{@code mark(word)}、
     * {@code humanSize(...)}，含括号也含逗号，正则会在第一个非字面量实参处截断
     * （实测：只会抓到 {@code "port"}，后面全丢）。这里按括号深度与引号状态扫一遍
     * 顶层实参，才能拿到完整的 "name", value 成对列表。
     */
    private static List<CallSite> callSites(String source) {
        List<CallSite> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = source.indexOf("msg.get(", from);
            if (start < 0) {
                return out;
            }
            int open = start + "msg.get".length();
            int depth = 0;
            boolean inQuote = false;
            int close = -1;
            for (int i = open; i < source.length(); i++) {
                char c = source.charAt(i);
                if (inQuote) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inQuote = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inQuote = true;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        close = i;
                        break;
                    }
                }
            }
            assertTrue(close > open, "unbalanced msg.get( call near offset " + start);
            List<String> args = splitTopLevel(source.substring(open + 1, close));
            from = close + 1;
            if (args.isEmpty()) {
                continue;
            }
            String literal = literalContent(args.get(0));
            if (literal == null) {
                continue; // 非字面量键（动态拼接）：静态扫描管不到，跳过
            }
            Set<String> names = new LinkedHashSet<>();
            for (int i = 1; i < args.size(); i += 2) {
                String name = literalContent(args.get(i));
                if (name != null) {
                    names.add(name);
                }
            }
            out.add(new CallSite("diagnose." + literal, names));
        }
    }

    /** 按顶层逗号切实参（跳过嵌套括号与字符串内的逗号）。 */
    private static List<String> splitTopLevel(String argList) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < argList.length(); i++) {
            char c = argList.charAt(i);
            if (inQuote) {
                current.append(c);
                if (c == '\\' && i + 1 < argList.length()) {
                    current.append(argList.charAt(++i));
                } else if (c == '"') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"') {
                inQuote = true;
                current.append(c);
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.toString().trim().length() > 0) {
            args.add(current.toString().trim());
        }
        return args;
    }

    /** 若整个实参就是一个字符串字面量，返回其内容；否则返回 null。 */
    private static String literalContent(String arg) {
        String t = arg.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return null;
    }

    // ============================================================
    //  夹具
    // ============================================================

    private static FileConfiguration load(String resource) {
        InputStream in = DiagnoseI18nParityTest.class.getClassLoader()
                .getResourceAsStream(resource);
        assertNotNull(in, "bundled resource missing: " + resource);
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** 叶子键（值不是 section），即真正承载文案的键。 */
    private static Set<String> leafKeys(FileConfiguration cfg, String root) {
        Set<String> out = new TreeSet<>();
        for (String key : cfg.getKeys(true)) {
            if (key.startsWith(root + ".") && !cfg.isConfigurationSection(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private static String readSource() {
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of(SOURCE)), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + SOURCE, e);
        }
    }

    // ============================================================
    //  1) key 集合：代码 ⊆ zh = en
    // ============================================================

    @Test
    @DisplayName("源码里引用的每个 diagnose.* key 都存在于 zh.yml 与 en.yml")
    void everyCodeKeyExistsInBothFiles() {
        String source = readSource();
        FileConfiguration zh = load(ZH);
        FileConfiguration en = load(EN);

        Set<String> used = new LinkedHashSet<>();
        for (CallSite site : callSites(source)) {
            used.add(site.key());
        }

        List<String> missingZh = new ArrayList<>();
        List<String> missingEn = new ArrayList<>();
        for (String key : used) {
            if (!zh.isSet(key)) {
                missingZh.add(key);
            }
            if (!en.isSet(key)) {
                missingEn.add(key);
            }
        }

        // 前置条件：扫描本身必须有效（正则失效会让两个 list 都空着"通过"）。
        assertTrue(used.size() >= 30,
                "source scan found only " + used.size() + " keys — the pattern broke: " + used);
        assertEquals(List.of(), missingZh, "keys missing from zh.yml");
        assertEquals(List.of(), missingEn, "keys missing from en.yml");
    }

    @Test
    @DisplayName("zh.yml 与 en.yml 的 diagnose.* 键集合完全一致")
    void zhAndEnDiagnoseKeysMatch() {
        Set<String> zh = leafKeys(load(ZH), "diagnose");
        Set<String> en = leafKeys(load(EN), "diagnose");

        assertEquals(zh, en, "diagnose.* key sets diverge between zh and en");
        // 规模断言：防止"两边都为空"这种平凡相等。
        assertTrue(zh.size() >= 40, "diagnose.* has only " + zh.size() + " leaf keys");
    }

    // ============================================================
    //  2) 占位符：两边一致 + 代码确实传了
    // ============================================================

    @Test
    @DisplayName("同一 key 的 {占位符} 集合在 zh 与 en 一致")
    void placeholdersMatchAcrossLanguages() {
        FileConfiguration zh = load(ZH);
        FileConfiguration en = load(EN);

        List<String> mismatches = new ArrayList<>();
        for (String key : leafKeys(zh, "diagnose")) {
            Set<String> zhPh = placeholders(zh.getString(key));
            Set<String> enPh = placeholders(en.getString(key));
            if (!zhPh.equals(enPh)) {
                mismatches.add(key + " zh=" + zhPh + " en=" + enPh);
            }
        }
        assertEquals(List.of(), mismatches, "placeholder sets differ between languages");
    }

    @Test
    @DisplayName("代码侧传入的占位符名能被文案模板接住（防 {prot} 类手滑）")
    void codePlaceholdersCoveredByTemplates() {
        String source = readSource();
        FileConfiguration zh = load(ZH);
        FileConfiguration en = load(EN);

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (CallSite site : callSites(source)) {
            for (String name : site.names()) {
                checked++;
                for (FileConfiguration cfg : List.of(zh, en)) {
                    if (!cfg.isSet(site.key())) {
                        continue; // 缺键已由第一个用例报告
                    }
                    Set<String> inText = placeholders(cfg.getString(site.key()));
                    if (!inText.contains(name)) {
                        problems.add(site.key() + " passes {" + name + "} but the text has " + inText);
                    }
                }
            }
        }
        // 前置条件：至少要真的走到带占位符的调用点，否则本用例是空转。
        assertTrue(checked >= 20, "only " + checked + " placeholder sites scanned");
        assertEquals(List.of(), problems, "code/template placeholder mismatch");
    }

    @Test
    @DisplayName("文案模板里的每个 {占位符} 都能被代码的实参填上（防留下裸花括号）")
    void templatePlaceholdersAreAllSupplied() {
        String source = readSource();
        // key → 代码在字面调用点传的占位符名
        java.util.Map<String, Set<String>> passedByKey = new java.util.HashMap<>();
        for (CallSite site : callSites(source)) {
            passedByKey.computeIfAbsent(site.key(), k -> new HashSet<>()).addAll(site.names());
        }

        List<String> problems = new ArrayList<>();
        for (FileConfiguration cfg : List.of(load(ZH), load(EN))) {
            for (String key : leafKeys(cfg, "diagnose")) {
                Set<String> needed = placeholders(cfg.getString(key));
                needed.remove(PREFIX_PLACEHOLDER); // 由解析器统一填充，不是调用点实参
                if (needed.isEmpty()) {
                    continue;
                }
                // prefix.* 由 switch 里的动态 key 取，没有字面调用点，不在此核对。
                if (key.startsWith("diagnose.prefix.")) {
                    continue;
                }
                Set<String> passed = passedByKey.get(key);
                if (passed == null) {
                    problems.add(key + " has " + needed + " but no literal call site was found");
                    continue;
                }
                for (String name : needed) {
                    if (!passed.contains(name)) {
                        problems.add(key + " needs {" + name + "} but code passes " + passed);
                    }
                }
            }
        }
        assertEquals(List.of(), problems, "template placeholders never supplied by code");
    }

    // ============================================================
    //  3) 级别前缀的 ASCII 安全（GBK 控制台下 ✔ ✗ 会变 '?'）
    // ============================================================

    @Test
    @DisplayName("diagnose.prefix.* 保持纯 ASCII（GBK 控制台安全）")
    void severityPrefixesStayAscii() {
        FileConfiguration zh = load(ZH);
        FileConfiguration en = load(EN);
        for (String key : List.of("ok", "warn", "fail", "info")) {
            String full = "diagnose.prefix." + key;
            for (FileConfiguration cfg : List.of(zh, en)) {
                String raw = cfg.getString(full);
                assertNotNull(raw, "missing " + full);
                for (char c : raw.toCharArray()) {
                    // § 是颜色码（translate 之后才会出现），原始 yml 应只有 & 码。
                    assertTrue(c < 0x80,
                            full + " contains a non-ASCII char '" + c
                                    + "' — GBK consoles print it as '?'");
                }
                assertFalse(raw.contains("§"), full + " should use & colour codes");
            }
        }
    }

    // ============================================================
    //  4) admin-help.diagnose 存在且指向 /lea diagnose
    // ============================================================

    @Test
    @DisplayName("admin-help.diagnose 在两份文件里都声明 /lea diagnose [save]")
    void helpLineIsDiscoverable() {
        for (String resource : List.of(ZH, EN)) {
            String text = load(resource).getString("admin-help.diagnose");
            assertNotNull(text, resource + " is missing admin-help.diagnose");
            assertTrue(text.contains("/lea diagnose"),
                    resource + " help line does not mention the command: " + text);
            assertTrue(text.contains("save"),
                    resource + " help line does not mention the save argument: " + text);
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> out = new TreeSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
