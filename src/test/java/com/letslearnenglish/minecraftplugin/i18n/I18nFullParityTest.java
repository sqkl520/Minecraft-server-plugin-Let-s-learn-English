package com.letslearnenglish.minecraftplugin.i18n;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>全量</b> zh/en 键集合与占位符的一致性护栏。
 *
 * <h2>为什么需要这一条（既有三条测试各管一段，都漏了这一片）</h2>
 * <p>仓里已经有三条 i18n 测试，但它们谁都没有做「两份语言文件的<b>完整</b>键集合
 * 双向 diff」：
 *
 * <ul>
 *   <li>{@code command/DiagnoseI18nParityTest} —— 键集合断言带 {@code root} 过滤，
 *       实际只覆盖 {@code diagnose.*}（{@code leafKeys(cfg, "diagnose")}）；</li>
 *   <li>{@code data/ClassDeleteTriStateTest} 的内嵌 {@code I18n} 夹具 —— 同样带 root，
 *       只覆盖 {@code class.*}；</li>
 *   <li>{@code util/MessageUtilMissingKeyContractTest} —— 只管「缺键之后玩家看到什么」
 *       （{@code &c[?key]} 的形状），一个键都不数；</li>
 *   <li>{@code config/LanguageKeyConcatLintTest} —— 是 Java 源码 lint（{@code "prefix." + lang}
 *       有没有归一化），与 yml 内容无关。</li>
 * </ul>
 *
 * <p>于是 {@code word.*}、{@code duel.*}、{@code report.*}、{@code profile.*} 等
 * <b>十几个顶层段共 700 多个叶子键</b>从未被任何测试比对过。这类缺口的表现是
 * 「静默的」：插件按玩家语言只加载<b>一份</b>文件，缺键的那一侧不会抛异常、
 * 不会进日志聚合，只会让玩家看到形如 {@code [?word.no-words-available]} 的裸占位符 ——
 * 而且往往只在「另一种语言」的客户端上复现，服主自己看不到。
 *
 * <h2>为什么必须用真 YAML 解析而不是按行文本比较</h2>
 * <p>键路径是<b>嵌套展开</b>的（{@code class:} 段下的 {@code delete-failed} 是
 * {@code class.delete-failed}），而两个文件的<b>行号并不对齐</b>（中文文件注释更多，
 * {@code report:} 在 zh 是 742 行、在 en 是 733 行）。按行 diff 会把「注释行数不同」
 * 误报成「键集合不同」，且在 {@code key:} 与值被拆行书写时彻底失效。
 *
 * <p>这里刻意与生产代码走同一条加载路径：{@link YamlConfiguration}（纯内存实现，
 * 不需要 Bukkit 运行时），并用<b>显式 UTF-8</b> 读流 —— Windows 平台默认编码是 GBK，
 * 依赖默认值读中文文件会解出乱码（本仓 {@code zh.yml} 全是中文）。
 *
 * <h2>三个已知的「假绿」形态，本文件逐条设了防线</h2>
 * <ol>
 *   <li><b>解析退化成空集</b>：两边都解析出 0 个键，{@code assertEquals} 平凡通过。
 *       对策：规模下限断言（叶子键 ≥ 500）。</li>
 *   <li><b>section 被当成叶子</b>：若把 {@code diagnose.db} 也算成叶子键，两边都多算，
 *       仍然相等 —— 但真实的「叶子被删掉」会被 section 的相等掩盖。
 *       对策：{@link #sectionPathsAreNeverCountedAsLeaves} 用真实文件双向验证
 *       section / 叶子判定确实在工作（已知 section 不在集合内、已知叶子必须在集合内）。</li>
 *   <li><b>差异函数永远返回空</b>：那么主断言的「零差异」毫无意义。
 *       对策：{@link #diffReallyDetectsASingleSidedKey} 与
 *       {@link #placeholderScanReallyDetectsASubstitutedName} 用合成的输入
 *       证明检查器<b>会</b>变红。</li>
 * </ol>
 *
 * <p>注释行不会被解析成键（YAML 语义），这一条由
 * {@link #commentsAreNeverParsedAsKeys} 用内存里的合成 YAML 钉住 —— 不依赖仓库
 * 当前注释内容，因此不会随文案改动而失效。
 *
 * <p><b>本类只报告，不修文案</b>：{@code messages/*.yml} 不归测试管，单边键与
 * 占位符差异列出后交由人工裁决（有些差异是刻意的，例如某语言独有的排版空格）。
 */
class I18nFullParityTest {

    private static final String ZH = "messages/zh.yml";
    private static final String EN = "messages/en.yml";

    /**
     * YAML 文案里的占位符，如 {@code {paid}}、{@code {server_version}}、{@code {score1}}。
     *
     * <p>字符类比 {@code DiagnoseI18nParityTest} 的 {@code [a-z0-9_-]} 放宽到大写与点号：
     * 该测试只扫 {@code diagnose.*}（那里的占位符恰好全小写），而全量扫描要覆盖
     * {@code plugin-info.server-version} 里的 {@code {server_version}}、
     * {@code duel.win} 里的 {@code {score1}} 这类带数字/下划线的名字。
     * 放宽到 {@code [A-Za-z0-9_.-]} 仍足以把 {@code {Prefix}} 与 {@code {prefix}} 区分开。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");

    /** 规模下限：低于此数说明解析路径失效（当前实际约 770+）。刻意留出余量。 */
    private static final int MIN_LEAF_KEYS = 500;

    // ============================================================
    //  加载与解析
    // ============================================================

    /**
     * 从测试 classpath 读内置语言文件 —— 与生产代码同一入口
     * （{@code ConfigManager.readBundledConfig} 也是 {@code getResourceAsStream} +
     * {@code YamlConfiguration.loadConfiguration(Reader)}）。
     *
     * <p>显式 {@link StandardCharsets#UTF_8}：本仓 {@code zh.yml} 是纯中文文案，
     * 用平台默认编码读会得到乱码（Windows 默认 GBK）。
     */
    private static YamlConfiguration loadBundled(String resource) {
        InputStream in = I18nFullParityTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, "测试 classpath 上找不到内置资源: " + resource
                + "（若为 null，说明 target/classes 没被放进测试 classpath，"
                + "本类的所有断言都会失去意义）");
        // 同时管理流与 Reader：Reader 的 close() 会抛 IOException，故下面的 catch 可达。
        try (InputStream stream = in;
             Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("读取内置资源失败: " + resource, e);
        }
    }

    /**
     * 完整键路径集合里的<b>叶子</b>键（即真正承载文案的键），形如 {@code a.b.c}。
     *
     * <p>{@code getKeys(true)} 已经做了嵌套展开，所以 {@code class:} 段下的
     * {@code delete-failed} 自然呈现为 {@code class.delete-failed}；注释被解析器丢弃，
     * 不需要额外过滤。
     *
     * <p>用 {@code isConfigurationSection} 排除 section：{@code diagnose.db:} 后面
     * 没有值、只有子键，它<b>不是</b>叶子。若把 section 混进叶子集合，两边会同时多出
     * 相同的 section 名，"叶子被误删"的一部分就会被掩盖。
     *
     * <p>真正的空值叶子（{@code key:} 且下面没有子键）会被 YAML 解析成 null，
     * 此时 {@code isConfigurationSection} 为 false —— 它确实是叶子，应当计入
     * （占位符提取对 null 退化空集，见 {@link #placeholders(String)}）。
     */
    private static Set<String> leafKeys(ConfigurationSection cfg) {
        Set<String> out = new TreeSet<>();
        for (String key : cfg.getKeys(true)) {
            if (!cfg.isConfigurationSection(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** 叶子键 → 文案原文。只用于占位符比对，不参与键集合判定。 */
    private static Map<String, String> leafTexts(ConfigurationSection cfg) {
        Map<String, String> out = new TreeMap<>();
        for (String key : leafKeys(cfg)) {
            out.put(key, cfg.getString(key));
        }
        return out;
    }

    /**
     * 提取一串文案里的占位符名字集合。
     *
     * <p>null（空值叶子）与非字符串占位符退化为空集而不是抛异常：占位符比对不该
     * 因为某个键恰好是空值/数字而失败，那是键集合测试的职责。
     */
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

    /** {@code a} 里有、{@code b} 里没有的键。产出的 List 直接被 assertEquals 打印出来。 */
    private static Set<String> onlyIn(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    // ============================================================
    //  1) 键集合：完整双向 diff
    // ============================================================

    @Test
    @DisplayName("zh.yml 与 en.yml 的完整叶子键集合双向相等（含全部顶层段，不只 diagnose/class）")
    void leafKeySetsAreIdenticalInBothDirections() {
        Set<String> zh = leafKeys(loadBundled(ZH));
        Set<String> en = leafKeys(loadBundled(EN));

        // 前置条件：解析必须真的读到东西，否则「两边都空」会平凡相等。
        assertTrue(zh.size() >= MIN_LEAF_KEYS,
                "zh.yml 只解析出 " + zh.size() + " 个叶子键（预期 ≥ " + MIN_LEAF_KEYS
                        + "）—— 加载路径或资源位置变了，本断言已失去意义");
        assertTrue(en.size() >= MIN_LEAF_KEYS,
                "en.yml 只解析出 " + en.size() + " 个叶子键（预期 ≥ " + MIN_LEAF_KEYS
                        + "）—— 加载路径或资源位置变了，本断言已失去意义");

        Set<String> zhOnly = onlyIn(zh, en);
        Set<String> enOnly = onlyIn(en, zh);

        // 两个方向分开报：修的时候要知道该往哪边补。
        assertEquals(List.of(), new ArrayList<>(zhOnly),
                "以下键只存在于 zh.yml，英文客户端会看到 [?key] 而不是文案（"
                        + zhOnly.size() + " 个，zh=" + zh.size() + " en=" + en.size() + "）");
        assertEquals(List.of(), new ArrayList<>(enOnly),
                "以下键只存在于 en.yml，中文客户端会看到 [?key] 而不是文案（"
                        + enOnly.size() + " 个，zh=" + zh.size() + " en=" + en.size() + "）");
    }

    // ============================================================
    //  2) 占位符：两边都有的键，占位符名字集合必须一致
    // ============================================================

    /**
     * <b>为什么占位符差异比缺键更隐蔽</b>：键两边都在，所以缺键回落
     * （{@code &c[?key]}）不会触发、也没有 WARNING 日志；玩家只会看到一句
     * 语法正确但夹着字面 {@code {amount}} 的文案，或某个本该有值的位置空白。
     * 这在中文服主的机器上<b>永远复现不了</b>。
     */
    @Test
    @DisplayName("每个两边都有的 key，{占位符} 名字集合在 zh 与 en 完全一致")
    void placeholderSetsMatchForEverySharedKey() {
        Map<String, String> zhText = leafTexts(loadBundled(ZH));
        Map<String, String> enText = leafTexts(loadBundled(EN));

        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        int withPlaceholders = 0;
        for (Map.Entry<String, String> e : zhText.entrySet()) {
            String key = e.getKey();
            if (!enText.containsKey(key)) {
                continue; // 单边键：由上一个用例负责报告，这里不重复刷屏
            }
            checked++;
            Set<String> zhPh = placeholders(e.getValue());
            Set<String> enPh = placeholders(enText.get(key));
            if (!enPh.isEmpty()) {
                withPlaceholders++;
            }
            if (!zhPh.equals(enPh)) {
                mismatches.add(key + "  zh=" + zhPh + "  en=" + enPh);
            }
        }

        // 前置条件一：比对样本量够大（否则等于只测了少数几个键）。
        assertTrue(checked >= MIN_LEAF_KEYS,
                "只比对了 " + checked + " 个共有键，少于预期 —— 键集合已大面积不一致，"
                        + "先修 leafKeySetsAreIdenticalInBothDirections");
        // 前置条件二：确实有一批键带占位符，否则「零不一致」是空转（全都没占位符）。
        assertTrue(withPlaceholders >= 200,
                "只有 " + withPlaceholders + " 个键带占位符 —— 占位符正则可能已失效");

        assertEquals(List.of(), mismatches,
                "以下键的占位符集合两边不一致（共 " + mismatches.size() + " 处）。"
                        + "这类缺陷运行期不报错、不落日志，只在对应语言的客户端上显示成"
                        + "字面花括号或空白：");
    }

    // ============================================================
    //  3) 解析器自检：section 与叶子必须真的被区分开
    // ============================================================

    /**
     * 反向自检：如果 {@link #leafKeys} 退化（例如某天改用按行正则切键），
     * section 名会混进叶子集合，而<b>两个文件会同时混入同样的 section 名</b> ——
     * 主断言依旧相等，缺陷被掩盖。
     *
     * <p>所以这里用真实文件双向验证：已知的 section 必须在集合外，已知的叶子必须在集合内。
     * 用哪几个键不重要，重要的是<b>类别判定在工作</b>。
     */
    @Test
    @DisplayName("section 路径不得被当成叶子键，已知叶子键必须在集合内")
    void sectionPathsAreNeverCountedAsLeaves() {
        YamlConfiguration zh = loadBundled(ZH);
        Set<String> leaves = leafKeys(zh);

        for (String section : List.of(
                "general", "word", "duel", "class", "diagnose",
                "diagnose.db", "diagnose.prefix", "square.feed", "onboarding.book")) {
            assertFalse(leaves.contains(section),
                    section + " 是 section（后面只有子键、没有值），不应出现在叶子键集合里；"
                            + "若它出现了，说明 section / 叶子判定已失效，"
                            + "主用例的『两边相等』会掩盖真正的缺键");
        }

        for (String leaf : List.of(
                "prefix", "config-version", "diagnose.header", "class.delete-failed",
                "square.feed.cheer-count", "onboarding.book.title")) {
            assertTrue(leaves.contains(leaf),
                    leaf + " 是叶子键（承载文案），应在叶子键集合里；"
                            + "缺失说明嵌套展开或过滤条件写错了");
        }
    }

    /**
     * 注释行不得被解析成键。
     *
     * <p>本仓两份语言文件里都有大量注释，且注释里<b>经常出现形似键或占位符的文本</b>
     * （{@code zh.yml} 的 {@code # slice-13-F5 ... {reward} ...}、
     * {@code #  3) 所有占位符都是 {name} 形式}）。若加载方式退化成「按行切 {@code key:}」，
     * 这些注释会变成幽灵键，主断言就会因为「只有 zh 有注释里的键」而误报。
     *
     * <p>这里用<b>内存里构造</b>的 YAML 而不是仓库文件：断言不随文案改动而失效，
     * 也不会因为「当前注释恰好不含 {@code x:} 模式」而变成空转。
     */
    @Test
    @DisplayName("YAML 注释行（含形似 key: value 的注释）不会被解析成键")
    void commentsAreNeverParsedAsKeys() {
        String yaml = String.join("\n",
                "real: \"value\"",
                "# some.key: value",
                "section:",
                "  # another.key: also-value",
                "  # nested.commented: x",
                "  leaf: \"{a}\"");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));

        Set<String> leaves = leafKeys(cfg);
        assertEquals(new TreeSet<>(Set.of("real", "section.leaf")), leaves,
                "注释行被当成了键；本类必须走真 YAML 解析，不能按行切 key:"
                        + "（实际解析结果: " + leaves + "）");
        // 注释里那个占位符也不该被扫到。
        assertEquals(Set.of("a"), placeholders(cfg.getString("section.leaf")),
                "叶子文案里的占位符提取不正确");
        assertFalse(leaves.contains("some.key"), "col 0 的注释被解析成了键");
        assertFalse(leaves.contains("nested.commented"), "嵌套段内的注释被解析成了键");
    }

    // ============================================================
    //  4) 检查器自检：差异函数必须真的会变红
    // ============================================================

    /**
     * 变异自检（键集合方向）：给 {@link #onlyIn} 一个只差一个键的输入对，
     * 它必须精确报出那个键。
     *
     * <p>没有这条，主用例里的 {@code assertEquals(List.of(), ...)} 完全可以是一个
     * 永远返回空集的假检查器，而测试永远绿。
     */
    @Test
    @DisplayName("差异函数真的能报出单边键（证明主用例的『零差异』不是假绿）")
    void diffReallyDetectsASingleSidedKey() {
        Set<String> zh = new TreeSet<>(Set.of("a.one", "a.two", "b.three"));
        Set<String> en = new TreeSet<>(Set.of("a.one", "b.three"));

        assertEquals(List.of("a.two"), new ArrayList<>(onlyIn(zh, en)),
                "差异函数没能报出 zh 独有的 a.two");
        assertEquals(List.of(), new ArrayList<>(onlyIn(en, zh)),
                "反向差异应为空（en 是 zh 的子集）");
        // 对称性：两边互换后报出的应是同形状的双向差异。
        // zh = {a.one, a.two, b.three}，enExtra = {a.one, b.three, c.four} —— 两边<b>互有独有键</b>，
        // 所以双向都应报出一条（zh 独有 a.two、enExtra 独有 c.four）。
        Set<String> enExtra = new TreeSet<>(Set.of("a.one", "b.three", "c.four"));
        assertEquals(List.of("c.four"), new ArrayList<>(onlyIn(enExtra, zh)),
                "差异函数没能报出 enExtra 独有的 c.four");
        assertEquals(List.of("a.two"), new ArrayList<>(onlyIn(zh, enExtra)),
                "差异函数没能报出 zh 独有的 a.two —— 两个方向都必须独立可报，"
                        + "否则主用例的『零差异』可能只证明了一个方向");
    }

    /**
     * 变异自检（占位符方向）：把 {@code {paid}} 换成 {@code {amount}} 必须被判为不一致。
     *
     * <p>这正是 {@code progress.reward-capped} 一类「金额类文案」的典型手滑形态：
     * 中文写「实际发放 {paid}」，英文若照抄成「actually paid {amount}」，
     * YAML 完全合法、键集合完全对齐，只有英文玩家会看到字面 {@code {amount}}。
     */
    @Test
    @DisplayName("占位符扫描真的能发现 {paid} → {amount} 的替换（证明主用例不是假绿）")
    void placeholderScanReallyDetectsASubstitutedName() {
        assertEquals(Set.of("prefix", "paid", "nominal"),
                placeholders("{prefix}cap reached, paid {paid} of {nominal}"),
                "占位符提取漏了名字或多了名字");

        assertNotEquals(placeholders("{prefix}实际发放 {paid} 金币"),
                placeholders("{prefix}actually paid {amount} coins"),
                "{paid} 与 {amount} 必须被判为不同的占位符集合");

        // 退化输入不得抛异常（空值叶子的 getString 返回 null）。
        assertEquals(Set.of(), placeholders(null), "null 文案应退化为空集合而不是 NPE");
        assertEquals(Set.of(), placeholders("no placeholders here"));
        // 边界：空花括号、未闭合花括号都不算占位符（避免把排版符号误报成占位符）。
        assertEquals(Set.of(), placeholders("{} {not-closed {also_not_closed"),
                "空/未闭合花括号不应被识别为占位符");
    }
}
