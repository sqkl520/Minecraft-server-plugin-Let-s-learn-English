package com.letslearnenglish.minecraftplugin.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R15（wave3 后继审计 · 并发专场）：{@code PlayerDataStore} 缓存层的并发不变量。
 *
 * <h2>为什么这条必须存在</h2>
 * <p>{@code PlayerDataStore} 的缓存被<b>主线程</b>（GUI 渲染、命令）与<b>异步线程</b>
 * （auto-save、ReportGenerator、SeasonManager 结算、TTS）同时读写。它整个类的正确性
 * 建立在一个没有类型系统保护的不变量上：<b>每一个缓存字段都必须是并发容器</b>。
 *
 * <p>把其中一个从 {@code ConcurrentHashMap} 改成 {@code new HashMap<>()} 能通过编译、
 * 通过全部功能测试，只在生产高并发下表现为丢更新或
 * {@code ConcurrentModificationException} —— 这类改动在 review 里看起来无害
 * （"就换个实现类"），所以需要一条机械检查。
 *
 * <h2>检查什么</h2>
 * <ol>
 *   <li>字段声明为 {@code Map} / {@code Set} 的，构造时必须赋一个并发实现
 *       （{@code ConcurrentHashMap} / {@code newKeySet}）—— 不允许裸 {@code HashMap} /
 *       {@code HashSet} / {@code LinkedHashMap}；</li>
 *   <li>嵌套缓存的内层 {@code Map} 同样必须是并发容器（外层并发不代表内层安全）。</li>
 * </ol>
 *
 * <p><b>为什么是源码 lint 而不是并发压测</b>：数据竞争在压测里是概率性的，
 * 跑绿了不能证明没有，跑红了也未必能定位。而「用没用并发容器」是<b>确定性</b>的源码事实。
 */
class PlayerDataStoreCacheConcurrencyLintTest {

    private static final Path SOURCE =
            Path.of("src/main/java/com/letslearnenglish/minecraftplugin/data/PlayerDataStore.java");

    /** 允许的非并发实现（出现即为缺陷）。 */
    private static final List<String> FORBIDDEN = List.of(
            "new HashMap<>", "new HashMap<", "new HashSet<>", "new HashSet<",
            "new LinkedHashMap<>", "new LinkedHashMap<", "new LinkedHashSet<>",
            "new TreeMap<>", "new TreeSet<>", "new ArrayList<>", "new LinkedList<>"
    );

    @Test
    @DisplayName("缓存字段不得用非并发容器实现")
    void noNonConcurrentCacheImplementations() throws IOException {
        assertTrue(Files.exists(SOURCE),
                "必须能读到 PlayerDataStore（工作目录应为项目根）: " + SOURCE.toAbsolutePath());
        String text = Files.readString(SOURCE);

        List<String> offenders = new ArrayList<>();
        List<String> lines = Files.readAllLines(SOURCE);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            // 只看赋值右侧：`xxxCache = new HashMap<>()` 这种
            for (String bad : FORBIDDEN) {
                if (line.contains(bad) && line.contains("=")) {
                    // 允许：局部变量（方法内临时集合）——它们不跨线程。
                    // 判据：左侧是否形如 `this.xxx` 或字段名（含 Cache/Set/Map 且不以大写开头）。
                    String lhs = line.substring(0, line.indexOf('=')).trim();
                    boolean isFieldAssignment = lhs.startsWith("this.")
                            || (lhs.matches("[a-zA-Z_][a-zA-Z0-9_]*")
                                    && (lhs.endsWith("Cache") || lhs.endsWith("Set")
                                        || lhs.endsWith("Players") || lhs.endsWith("dWords")));
                    if (isFieldAssignment) {
                        offenders.add("第 " + (i + 1) + " 行: " + trimmed);
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "以下缓存字段用了非并发容器。PlayerDataStore 的缓存被主线程与异步线程同时读写，"
                        + "换成 HashMap/HashSet 会丢更新或抛 ConcurrentModificationException，"
                        + "而且能通过全部功能测试:\n  " + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("嵌套缓存的内层结构也必须是并发安全的")
    void nestedMapsAreConcurrent() throws IOException {
        String text = Files.readString(SOURCE);

        // 找 `computeIfAbsent(..., k -> new Xxx(...))` 里的实现类
        Pattern p = Pattern.compile(
                "computeIfAbsent\\s*\\([^,]+,\\s*\\w+\\s*->\\s*new\\s+(\\w+)");
        Matcher m = p.matcher(text);
        List<String> bad = new ArrayList<>();
        int total = 0;
        while (m.find()) {
            total++;
            String impl = m.group(1);
            if (isConcurrencySafe(impl, text)) {
                continue;
            }
            bad.add("computeIfAbsent 用了 " + impl
                    + "（既非 Concurrent*/Atomic* 标准并发类，也非内部含并发原语的项目自定义类）");
        }

        assertTrue(total > 0,
                "一个 computeIfAbsent 都没找到 —— 正则失效或代码结构变了，本 lint 已失去意义");
        assertTrue(bad.isEmpty(),
                "嵌套缓存的内层结构必须并发安全（外层 ConcurrentHashMap 不保护内层）:\n  "
                        + String.join("\n  ", bad));
    }

    /**
     * 判断一个类名是否表示并发安全的内层结构。
     *
     * <p>判据演进（三次误报/漏报换来的）：
     * <ol>
     *   <li><b>标准并发类</b>：{@code Concurrent*} / {@code Atomic*} / {@code CopyOnWrite*}。
     *       第一版只认 {@code Concurrent*} 前缀，把 {@code new AtomicLong(...)} 误报 ——
     *       它恰恰是并发计数器；</li>
     *   <li><b>本文件内的自定义类</b>（如 {@code TodayCounter}）：不能简单放行，
     *       必须检查它内部是否真用了并发原语 —— 否则会漏掉「内部是裸字段」的真实缺陷。
     *       这里截出该类声明后的一段源码，要求出现 volatile / Atomic / synchronized / Concurrent；</li>
     *   <li><b>其它</b>：保守判为不安全，要求人来复核。</li>
     * </ol>
     */
    private static boolean isConcurrencySafe(String impl, String allText) {
        if (impl.startsWith("Concurrent") || impl.startsWith("Atomic")
                || impl.startsWith("CopyOnWrite")) {
            return true;
        }
        // 项目内的类（本文件）：截出类体，看有没有并发原语
        Matcher decl = Pattern.compile(
                "(?:static\\s+)?class\\s+" + Pattern.quote(impl) + "\\b").matcher(allText);
        if (!decl.find()) {
            return false; // 不在本文件里 —— 无法证明安全，交给人判断
        }
        int start = decl.start();
        int end = Math.min(allText.length(), start + 4000);
        String body = allText.substring(start, end);
        return body.contains("volatile")
                || body.contains("Atomic")
                || body.contains("synchronized")
                || body.contains("Concurrent");
    }

    /**
     * <b>锁定的具体回归</b>：{@code ProfileSettings} 的字段必须是 volatile。
     *
     * <p>它不是 Map/Set，而是「被放进 ConcurrentHashMap 的可变值对象」——
     * {@code ConcurrentHashMap} 只保证 map 操作的可见性，<b>不保证 value 内部字段</b>。
     * 主线程 {@code setShowInSquare(false)} 之后，异步的广场/报表路径可能仍读到 true，
     * 于是"已隐藏"的玩家仍被展示出去 —— 这正是 D2 隐私优先要防的事，且只在并发下偶发。
     */
    @Test
    @DisplayName("ProfileSettings 的字段必须 volatile（ConcurrentHashMap 不保护 value 内部）")
    void profileSettingsFieldsAreVolatile() throws IOException {
        String text = Files.readString(SOURCE);

        int idx = text.indexOf("public static class ProfileSettings");
        assertTrue(idx >= 0, "应能找到 ProfileSettings 类声明");
        int end = Math.min(text.length(), idx + 4000);
        String body = text.substring(idx, end);

        // 类体里不得出现「裸的」private 原始类型字段（前面没有 volatile）
        Pattern bareField = Pattern.compile(
                "^\\s*private\\s+(?!static|final|volatile)(int|boolean|long|String)\\s+\\w+",
                Pattern.MULTILINE);
        List<String> offenders = new ArrayList<>();
        Matcher m = bareField.matcher(body);
        while (m.find()) {
            offenders.add(m.group().trim());
        }

        assertTrue(offenders.isEmpty(),
                "ProfileSettings 有非 volatile 的可变字段。它被放进 profileSettingsCache 并在"
                        + "主线程与异步线程间传递，ConcurrentHashMap 不保证 value 内部字段的可见性，"
                        + "隐私开关可能出现「已关闭但异步路径仍读到 true」:\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * 反向确认：这份 lint 的<b>前提</b>仍然成立 —— 缓存确实可能跨线程访问。
     *
     * <p>如果哪天 PlayerDataStore 变成纯主线程，这条 lint 就失去意义，
     * 应当被删除而不是留着误导后人。这里锚定「存在异步调用点」这一事实。
     */
    @Test
    @DisplayName("前提检查：类里确实存在异步/跨线程使用痕迹")
    void premiseStillHolds() throws IOException {
        String text = Files.readString(SOURCE);

        boolean hasConcurrency =
                text.contains("ConcurrentHashMap")
                        || text.contains("synchronized")
                        || text.contains("volatile");
        assertTrue(hasConcurrency,
                "PlayerDataStore 已无任何并发原语 —— 若它真的变成纯主线程，"
                        + "请删除本 lint 而不是让它继续空转");
    }
}
