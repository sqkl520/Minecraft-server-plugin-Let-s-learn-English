package com.letslearnenglish.minecraftplugin.command;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StatsTextFormatter} 的纯函数用例 —— `/le stats &lt;玩家名&gt;` 的战绩文案层。
 *
 * <h2>为什么这一层值得单独测（而不是去测 LearnCommand）</h2>
 * {@link LearnCommand} 的构造依赖 Bukkit（调度器、服务端、一堆 Manager），纯 JVM 起不来；
 * 就算用上 Mockito 顶掉它们，能观测到的也只是「发了哪几个 key」，看不到**渲染出来的数字**。
 * 而这条命令最有价值的两个性质恰好是「数字怎么写」：
 * <ol>
 *   <li><b>正确率的小数点</b>：德语/法语环境默认 Locale 会输出 {@code "75,0%"}，
 *       QQ 侧解析静默得到 0 或 NaN，而这在中文/英文机器上<b>永远复现不了</b>；</li>
 *   <li><b>隐藏态有没有漏数</b>：隐私门一旦退化成「先取数、再隐藏」，界面看起来完全
 *       一样，只有把行文本逐字比对才看得出来。</li>
 * </ol>
 * 所以本类真调生产静态方法（不复制粘贴一份同样的算术），并逐条钉这些渲染结果。
 *
 * <h2>为什么文案取自真实的 zh.yml / en.yml</h2>
 * 用一份手写的 {@code Map} 夹具的话，测试会「因为夹具对了」而绿 —— 真实 yml 里键名写错
 * （{@code command.stats-text.acuracy} 之类）仍然会让玩家看到 {@code [?key]}。
 * 这里从测试 classpath 读内置语言文件（与生产 {@code ConfigManager} 同一条加载路径），
 * 于是「键存在 + 占位符名字对得上 + 渲染出的行长什么样」三件事一次钉住。
 *
 * <h2>本类封死的变异（每条都指向一个具体断言）</h2>
 * <ol>
 *   <li>把 {@code formatAccuracy} 的 {@code Locale.ROOT} 去掉 → {@link Accuracy#localeIndependent}
 *       在德语 Locale 下红；</li>
 *   <li>把除零的 {@code total <= 0} 分支删掉 → {@link Accuracy#zeroAnswersDoesNotThrow} 红（NaN）；</li>
 *   <li>把 {@code (long)} 口径换回 int 相加 → {@link Accuracy#intOverflowIsNotMistakenForNoData} 红；</li>
 *   <li>把 {@link StatsTextFormatter#renderHidden} 改成「先渲染全部行再丢弃」→
 *       {@link PrivacyGate#hiddenOutputCarriesNoNumbers} 红（分数会漏出来）；</li>
 *   <li>给任意一条 yml 文案加回 {@code &6} / {@code §6} → {@link NoColourCodes#noColorCodesInRenderedOutput} 红；</li>
 *   <li>UI 文案漏加 en.yml 一侧 → {@link I18nCoverage#everyFormatterKeyExistsInBothLanguages} 红。</li>
 * </ol>
 */
@DisplayName("StatsTextFormatter —— /le stats <玩家名> 的战绩文案层")
class StatsTextFormatTest {

    private static final Path ZH_YML = Path.of("src/main/resources/messages/zh.yml");
    private static final Path EN_YML = Path.of("src/main/resources/messages/en.yml");

    /** 本层会用到的全部 key（与生产侧常量同源，逐条列出来避免反射）。 */
    private static final List<String> ALL_KEYS = List.of(
            StatsTextFormatter.KEY_HEADER,
            StatsTextFormatter.KEY_SCORE,
            StatsTextFormatter.KEY_CORRECT,
            StatsTextFormatter.KEY_INCORRECT,
            StatsTextFormatter.KEY_ACCURACY,
            StatsTextFormatter.KEY_SESSIONS,
            StatsTextFormatter.KEY_ACHIEVEMENTS,
            StatsTextFormatter.KEY_HIDDEN,
            StatsTextFormatter.KEY_PLAYER_NOT_FOUND,
            StatsTextFormatter.KEY_LOAD_FAILED,
            StatsTextFormatter.KEY_USAGE);

    /**
     * 颜色码判定：Minecraft 的两种写法 {@code §x}（已翻译）与 {@code &x}（配置原文）。
     *
     * <p>字符集按 Bukkit {@code ChatColor} 的合法码取 {@code [0-9a-fk-or]}（大小写不敏感）。
     * 只匹配「码 + 一个合法字符」而不是任意 {@code &} —— 文案里出现 {@code &} 号本身
     * （例如「A & B」）不该被判成颜色码，那会让这条断言变成噪音。
     */
    private static final Pattern COLOR_CODE =
            Pattern.compile("[&§][0-9a-fk-orA-FK-OR]");

    /** 一条正常的战绩（Alice：答对 3 / 答错 1，正确率应为 75.0%）。 */
    private static StatsTextFormatter.StatsSnapshot alice() {
        return new StatsTextFormatter.StatsSnapshot("Alice", 1234L, 3, 1, 7, 5, 22);
    }

    /** 用真实 yml 的一份语言文件构造取值函数（未知 key 返回可见的 {@code [?key]}）。 */
    private static Function<String, String> msgFrom(Path yml) {
        YamlConfiguration cfg = loadYaml(yml);
        return key -> {
            String value = cfg.getString(key);
            return (value != null) ? value : "[?" + key + "]";
        };
    }

    private static Map<String, String> textsFrom(Path yml) {
        YamlConfiguration cfg = loadYaml(yml);
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : ALL_KEYS) {
            out.put(key, cfg.getString(key));
        }
        return out;
    }

    private static YamlConfiguration loadYaml(Path path) {
        assertTrue(Files.isRegularFile(path),
                "找不到 " + path.toAbsolutePath() + " —— 本类必须以仓库根为工作目录跑");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            // 显式 UTF-8：Windows 平台默认编码是 GBK，zh.yml 全是中文。
            yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            throw new AssertionError("解析 " + path + " 失败（YAML 语法/缩进错误）: "
                    + e.getMessage(), e);
        }
        return yaml;
    }

    /** 内置资源（测试 classpath = target/classes，与生产加载入口一致）。 */
    private static YamlConfiguration loadBundled(String resource) {
        InputStream in = StatsTextFormatTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, "测试 classpath 上找不到内置资源: " + resource);
        try (InputStream stream = in;
             Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("读取内置资源失败: " + resource, e);
        }
    }

    // ==================================================================
    //  组 1 —— 正确率格式（本层最容易在别的机器上才炸的地方）
    // ==================================================================

    @Nested
    @DisplayName("组 1 —— 正确率格式")
    class Accuracy {

        /**
         * 基准：3 对 1 错 = 75.0%。
         *
         * <p>同时钉「一位小数」这个精度：写成 {@code %.0f} 会变成 {@code "75%"}，
         * 与 PAPI 的 {@code %lle_accuracy%} 口径不一致（同一个数字两个出口两种写法）。
         */
        @Test
        @DisplayName("3 对 1 错 → \"75.0%\"（与 PAPI 的 %lle_accuracy% 逐字同口径）")
        void basicFormat() {
            assertEquals("75.0%", StatsTextFormatter.formatAccuracy(3, 1));
        }

        /**
         * <b>本类最重要的一条</b>：默认 Locale 换成德语（小数点用逗号）时，输出必须不变。
         *
         * <p>变异指向：把 {@code String.format(Locale.ROOT, ...)} 的 {@code Locale.ROOT} 删掉
         * → 本用例在 {@code Locale.GERMANY} 下得到 {@code "75,0%"} 并红。
         *
         * <p>用 {@code Locale#setDefault} 而不是 {@code String#format(Locale...)} 直调，
         * 是因为生产代码用的是**不带 Locale 重载的** {@code String.format} —— 它读的正是
         * 这个默认值。替它指定 Locale 就等于替它做了修复，测试会变成自证桩。
         */
        @Test
        @DisplayName("Locale.GERMANY 下仍是 \"75.0%\" 而不是 \"75,0%\"")
        void localeIndependent() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY);
                // 前置自检：确认德语下「不带 Locale 的 format」真的会用逗号 ——
                // 若这个前提不成立（未来 JDK 改了行为），下面的断言就失去判别力，
                // 必须先在这里红，而不是让主断言虚假通过。
                assertEquals("75,0", String.format("%.1f", 75.0),
                        "前提不成立：德语 Locale 下 String.format(\"%.1f\", 75.0) 没有输出逗号 —— "
                                + "本用例已失去判别力，需换一种验证方式");

                assertEquals("75.0%", StatsTextFormatter.formatAccuracy(3, 1),
                        "正确率必须显式 Locale.ROOT 喵：德语/法语环境下默认 Locale 会输出 "
                                + "\"75,0%\"（逗号小数点），QQ 侧或第三方插件解析这个数字会静默得到 0 或 NaN。");
                assertEquals("0.0%", StatsTextFormatter.formatAccuracy(0, 0));
                assertEquals("100.0%", StatsTextFormatter.formatAccuracy(9, 0));
            } finally {
                Locale.setDefault(original);
            }
        }

        /**
         * 一题没答：{@code "0.0%"}，且<b>不抛异常</b>。
         *
         * <p>变异指向：删掉 {@code total <= 0} 的提前返回 → {@code 0 / 0} 得到 {@code NaN}，
         * 输出变成 {@code "NaN%"} 并最终显示在 QQ 群里。
         */
        @Test
        @DisplayName("答 0 题 → \"0.0%\"（不抛异常、不出现 NaN）")
        void zeroAnswersDoesNotThrow() {
            assertEquals("0.0%", StatsTextFormatter.formatAccuracy(0, 0));
            assertFalse(StatsTextFormatter.formatAccuracy(0, 0).contains("NaN"),
                    "0/0 直接相除会得到 NaN 喵 —— 新玩家或读失败的空壳会看到 \"NaN%\"");
        }

        /**
         * 脏数据/极端值：{@code correct + incorrect} 以 int 相加会溢出成负数。
         *
         * <p>取 {@code correct == incorrect == Integer.MAX_VALUE}：真实总题数是
         * {@code 2 × MAX}（约 43 亿），正确率应为 {@code 50.0%}；而 int 相加得到
         * {@code -2}，一个「总题数 &lt;= 0 就当没答题」的实现会输出 {@code "0.0%"} ——
         * 把「答过 43 亿题」读成「一题没答」。
         *
         * <p>两个期望值刻意都写全：只断言「不抛异常」抓不住这条（int 溢出不会抛）。
         */
        @Test
        @DisplayName("int 相加会溢出的一对数 → 仍是 50.0%（不是 0.0%）")
        void intOverflowIsNotMistakenForNoData() {
            assertEquals("50.0%", StatsTextFormatter.formatAccuracy(Integer.MAX_VALUE,
                            Integer.MAX_VALUE),
                    "用 long 口径重算总题数喵 —— int 相加会溢出成 -2，"
                            + "「总题数 <= 0 就当没答题」的实现会输出 0.0%，"
                            + "把「答过 43 亿题」读成「一题没答」");
        }

        /** 边界：答错数里出现负数（脏数据）时，结果钳制在 0~100 而不是负数百分比。 */
        @Test
        @DisplayName("负的答错数（脏数据）→ 钳制到 100.0%，不输出负数或超量百分比")
        void negativeIncorrectIsClamped() {
            assertEquals("100.0%", StatsTextFormatter.formatAccuracy(5, -1));
            assertEquals("100.0%", StatsTextFormatter.formatAccuracy(5, 0));
        }
    }

    // ==================================================================
    //  组 2 —— 完整渲染（真实 yml 文案）
    // ==================================================================

    @Nested
    @DisplayName("组 2 —— 完整渲染")
    class Rendering {

        @Test
        @DisplayName("七行齐全：标题 / 总学习分 / 答对 / 答错 / 正确率 / 学习次数 / 成就")
        void allFieldsRendered() {
            List<String> lines = StatsTextFormatter.render(alice(), msgFrom(ZH_YML));

            assertEquals(7, lines.size(),
                    "战绩应有 7 行喵（标题 + 6 个字段），实际：" + lines);
            assertTrue(lines.get(0).contains("Alice"), "标题行必须回显玩家名喵：" + lines.get(0));
            assertTrue(lines.get(1).contains("1234"), "总学习分没渲染喵：" + lines.get(1));
            assertTrue(lines.get(2).contains("3"), "答对数没渲染喵：" + lines.get(2));
            assertTrue(lines.get(3).contains("1"), "答错数没渲染喵：" + lines.get(3));
            assertTrue(lines.get(4).contains("75.0%"),
                    "正确率没渲染成 75.0% 喵：" + lines.get(4));
            assertTrue(lines.get(5).contains("7"), "学习次数没渲染喵：" + lines.get(5));
            assertTrue(lines.get(6).contains("5") && lines.get(6).contains("22"),
                    "成就进度必须同时有解锁数与总数喵：" + lines.get(6));
        }

        /**
         * 中文标签必须真的来自 zh.yml：把 {@code stats-text.score} 的文案改成一句哨兵串，
         * 渲染结果里必须出现那句哨兵。这条证明「标签是读 yml 的，不是代码里拼的」。
         */
        @Test
        @DisplayName("标签取自 i18n 模板（改模板 → 输出随之改变，不是代码里硬编码）")
        void labelsComeFromTemplates() {
            Map<String, String> custom = new LinkedHashMap<>();
            custom.put(StatsTextFormatter.KEY_SCORE, "SENTINEL-SCORE:{score}");

            List<String> lines = StatsTextFormatter.render(alice(), custom::get);
            assertTrue(lines.contains("SENTINEL-SCORE:1234"),
                    "总学习分那行不是按模板渲染的喵（说明代码里硬编码了标签）。实际：" + lines);
        }

        /**
         * 模板缺失（缺键）时整行跳过，而不是往 QQ 群发一条空消息。
         *
         * <p>生产侧 {@code MessageUtil} 缺键会回落 {@code [?key]}（fail-visible），
         * 所以「跳过」只针对**空串**这一种退化 —— 这两者的区别很重要：跳过空串是排版整洁，
         * 而把 {@code [?key]} 也跳掉会让服主看不见自己少配了文案。
         */
        @Test
        @DisplayName("空模板整行跳过；[?key] 之类可诊断占位符则照常输出")
        void emptyTemplateIsSkippedButPlaceholderIsVisible() {
            Map<String, String> sparse = new LinkedHashMap<>();
            sparse.put(StatsTextFormatter.KEY_HEADER, "HEADER:{player}");
            sparse.put(StatsTextFormatter.KEY_SESSIONS, "");
            sparse.put(StatsTextFormatter.KEY_SCORE, "[?command.stats-text.score]");

            List<String> lines = StatsTextFormatter.render(alice(), sparse::get);
            assertEquals(List.of("HEADER:Alice", "[?command.stats-text.score]"), lines,
                    "空模板跳过、可诊断占位符保留喵。实际：" + lines);
        }

        /** {@code null} 战绩按全 0 处理：展示路径不该因一次违约把整条命令打崩。 */
        @Test
        @DisplayName("null 战绩按全 0 渲染，不抛 NPE")
        void nullStatsDegradeToZero() {
            List<String> lines = StatsTextFormatter.render(null, msgFrom(ZH_YML));
            assertFalse(lines.isEmpty());
            assertEquals("0.0%", StatsTextFormatter.formatAccuracy(0, 0));
        }

        /** 返回的列表不可变：调用方若想「就地改两笔」应显式复制，而不是静默污染缓存值。 */
        @Test
        @DisplayName("返回不可变列表")
        void resultIsImmutable() {
            List<String> lines = StatsTextFormatter.render(alice(), msgFrom(ZH_YML));
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> lines.add("extra"));
        }
    }

    // ==================================================================
    //  组 3 —— 隐私门（与 PAPI 侧同口径）
    // ==================================================================

    @Nested
    @DisplayName("组 3 —— 隐私门")
    class PrivacyGate {

        /**
         * <b>本类第二重要的一条</b>：隐藏态的输出里不得出现任何具体数字。
         *
         * <p>「先取数、再隐藏」这种写法在界面上看起来一模一样 —— 只有逐字检查行文本才
         * 抓得住。这里用一份数字非常显眼的快照（1234 / 75.0% / 7 / 5/22），任何一处漏渲染
         * 都会被下面的 contains 判出来。
         */
        @Test
        @DisplayName("showResults=false + 他人查看 → 输出里不出现任何具体分数")
        void hiddenOutputCarriesNoNumbers() {
            List<String> lines = StatsTextFormatter.renderHidden("Bob", msgFrom(ZH_YML));

            assertEquals(1, lines.size(),
                    "隐藏态必须只输出一行「已隐藏」喵，实际：" + lines);
            String line = lines.get(0);
            assertTrue(line.contains("Bob"), "隐藏行应回显目标玩家名喵：" + line);

            for (String forbidden : List.of("1234", "75.0%", "22", "5/22")) {
                assertFalse(line.contains(forbidden),
                        "隐藏态漏出了成绩数字 '" + forbidden + "' 喵：" + line);
            }
            // 更强的口径：整行不得出现任何阿拉伯数字（玩家名后可能带数字，故只查成绩形状）。
            assertFalse(line.matches(".*\\d+\\.\\d+%.*"), "隐藏态不得出现正确率喵：" + line);
        }

        /**
         * 生产调用点必须用 {@code renderHidden} 而不是 {@code render(...)} + 事后丢弃：
         * 两者输出相同，但只有前者在**类型上**拿不到统计数字，从而不可能「先取数再隐藏」。
         *
         * <p>这条是源码 lint（{@code LearnCommand} 在纯 JVM 里起不来），与上面那条行为
         * 断言互补：行为断言钉「渲染结果对不对」，lint 钉「调用方有没有提前取数」。
         */
        @Test
        @DisplayName("源码 lint：LearnCommand 的隐私分支必须在取数之前 return，且用 renderHidden")
        void learnCommandReturnsBeforeLoadingStats() throws IOException {
            Path source = Path.of("src/main/java/com/letslearnenglish/minecraftplugin/command/"
                    + "LearnCommand.java");
            assertTrue(Files.isRegularFile(source),
                    "找不到 " + source.toAbsolutePath() + " —— 本类必须以仓库根为工作目录跑");
            String code = Files.readString(source, StandardCharsets.UTF_8);

            int privacyBlock = code.indexOf("renderHidden(");
            assertTrue(privacyBlock >= 0,
                    "LearnCommand 里找不到 StatsTextFormatter.renderHidden 喵 —— 隐私分支可能被改成"
                            + "「先取 stats 再隐藏」，那样一次改错就会真泄露成绩");

            int statsLoad = code.indexOf("store.getPlayerStats(targetId)");
            assertTrue(statsLoad >= 0, "找不到 getPlayerStats 调用点喵 —— 锚点失效，本断言失去意义");
            assertTrue(privacyBlock < statsLoad,
                    "隐私分支出现在 getPlayerStats <b>之后</b>喵 —— 说明是先取数再隐藏。"
                            + "必须在读任何统计值之前就 return。");
        }
    }

    // ==================================================================
    //  组 4 —— 输出里不得含颜色码（QQ 侧的乱码判据）
    // ==================================================================

    @Nested
    @DisplayName("组 4 —— 纯文本（无颜色码）")
    class NoColourCodes {

        /**
         * 渲染结果里不得出现 {@code §x} / {@code &x}。
         *
         * <p>两个来源都要覆盖：① yml 模板里自己写了颜色码；② 占位符取值里带颜色码
         * （玩家名是 {@code [A-Za-z0-9_]} 所以实际不可能，但把「模板 / 取值」分开断言
         * 才能定位是哪一个出了问题）。
         */
        @Test
        @DisplayName("zh/en 两份渲染结果里都没有 § 或 & 颜色码")
        void noColorCodesInRenderedOutput() {
            for (Path yml : List.of(ZH_YML, EN_YML)) {
                List<String> all = new ArrayList<>();
                all.addAll(StatsTextFormatter.render(alice(), msgFrom(yml)));
                all.addAll(StatsTextFormatter.renderHidden("Bob", msgFrom(yml)));
                for (String line : all) {
                    Matcher m = COLOR_CODE.matcher(line);
                    boolean found = m.find();
                    String hit = found ? m.group() : "";
                    assertFalse(found,
                            yml.getFileName() + " 的战绩文案含颜色码（QQ 侧显示成乱码）喵：'"
                                    + line + "' 命中 '" + hit + "'");
                }
            }
        }

        /**
         * 反向自检：判定器必须真的能抓到颜色码，否则上一条是假绿
         * （一个永远返回 false 的 {@code find()} 也能让上一条通过）。
         */
        @Test
        @DisplayName("自检：颜色码判定器对 §6 / &c 真的会命中")
        void colorDetectorSelfCheck() {
            assertTrue(COLOR_CODE.matcher("§6金币 100").find(), "§6 没被识别成颜色码");
            assertTrue(COLOR_CODE.matcher("&c错误").find(), "&c 没被识别成颜色码");
            assertFalse(COLOR_CODE.matcher("总学习分: 1234").find(),
                    "纯文本被误判成含颜色码 —— 判定器过于激进");
            assertFalse(COLOR_CODE.matcher("A & B").find(),
                    "裸 & 号被误判成颜色码 —— 会让真实文案里含 & 的行误红");
        }
    }

    // ==================================================================
    //  组 5 —— i18n 覆盖（两个文件都必须有，且占位符一致）
    // ==================================================================

    @Nested
    @DisplayName("组 5 —— i18n 覆盖")
    class I18nCoverage {

        /**
         * 本层用到的每个 key 都必须在 zh.yml 与 en.yml 里存在。
         *
         * <p>漏一个的后果是玩家看到 {@code &c[?command.stats-text.score]} —— 而且只在
         * 「另一种语言」的客户端或者控制台（默认语言）上复现。
         *
         * <p>这里同时检查**仓库里的源文件**与**测试 classpath 上的内置副本**：
         * 前者是服主要改的那份，后者是运行时真正加载的那份；两者不一致往往意味着
         * 构建产物没跟着源文件走（本仓 {@code mvn compile} 会拷 resources，正常应当一致）。
         */
        @Test
        @DisplayName("11 个 key 同时存在于 zh.yml 与 en.yml（源文件 + classpath 副本）")
        void everyFormatterKeyExistsInBothLanguages() {
            for (Map.Entry<String, YamlConfiguration> e : Map.of(
                    "zh", loadYaml(ZH_YML),
                    "en", loadYaml(EN_YML),
                    "zh(bundled)", loadBundled("messages/zh.yml"),
                    "en(bundled)", loadBundled("messages/en.yml")).entrySet()) {
                List<String> missing = new ArrayList<>();
                for (String key : ALL_KEYS) {
                    if (!e.getValue().isSet(key)) {
                        missing.add(key);
                    }
                }
                assertEquals(List.of(), missing,
                        e.getKey() + " 里缺这些 key 喵（玩家会看到 [?key]）：" + missing);
            }
        }

        /** 同一个 key 在 zh/en 的占位符集合必须一致，否则一边装不下实参。 */
        @Test
        @DisplayName("zh 与 en 的 {占位符} 集合逐 key 一致")
        void placeholderSetsMatchAcrossLanguages() {
            Map<String, String> zh = textsFrom(ZH_YML);
            Map<String, String> en = textsFrom(EN_YML);

            List<String> mismatches = new ArrayList<>();
            for (String key : ALL_KEYS) {
                Set<String> z = placeholders(zh.get(key));
                Set<String> e = placeholders(en.get(key));
                if (!z.equals(e)) {
                    mismatches.add(key + " zh=" + z + " en=" + e);
                }
            }
            assertEquals(List.of(), mismatches, "zh/en 占位符集合不一致喵：" + mismatches);
        }

        /**
         * 文案里被引用的占位符，必须真的被 {@link StatsTextFormatter} 填过 ——
         * 否则玩家读到一句字面 {@code {score}}。
         *
         * <p>做法：渲染一份数字全都极显眼的快照，逐个检查 yml 里的占位符是否还以
         * 花括号形式残留在输出里。这比「列出期望占位符」更强，因为它自动覆盖了
         * 将来新增的 key。
         */
        @Test
        @DisplayName("真实 yml 的每个占位符都被填掉了（不残留 {xxx}）")
        void everyDeclaredPlaceholderIsSubstituted() {
            StatsTextFormatter.StatsSnapshot loud =
                    new StatsTextFormatter.StatsSnapshot("Zoe", 987654321L, 42, 8, 33, 19, 22);
            for (Path yml : List.of(ZH_YML, EN_YML)) {
                List<String> lines = new ArrayList<>();
                lines.addAll(StatsTextFormatter.render(loud, msgFrom(yml)));
                lines.addAll(StatsTextFormatter.renderHidden("Zoe", msgFrom(yml)));
                for (String line : lines) {
                    Set<String> left = placeholders(line);
                    assertEquals(Set.of(), new LinkedHashSet<>(left),
                            yml.getFileName() + " 渲染后仍残留占位符喵（玩家会读到字面花括号）：'"
                                    + line + "' 残留 " + left);
                }
            }
        }

        /** 自检：占位符提取器真的能读到名字（否则上一条是空转）。 */
        @Test
        @DisplayName("自检：占位符提取器能读出 {player} / {score} / {unlocked}")
        void placeholderExtractorSelfCheck() {
            assertEquals(new TreeSet<>(Set.of("player")),
                    new TreeSet<>(placeholders("===== {player} 的学习战绩 =====")));
            assertEquals(new TreeSet<>(Set.of("unlocked", "total")),
                    new TreeSet<>(placeholders("已解锁成就: {unlocked}/{total}")));
            assertEquals(Set.of(), placeholders("没有占位符的一行"));
            assertNotEquals(placeholders("{score}"), placeholders("{scores}"),
                    "{score} 与 {scores} 必须被判为不同的占位符");
        }

        private Set<String> placeholders(String text) {
            Set<String> out = new TreeSet<>();
            if (text == null) {
                return out;
            }
            Matcher m = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}").matcher(text);
            while (m.find()) {
                out.add(m.group(1));
            }
            return out;
        }
    }

    // ==================================================================
    //  组 6 —— 用法行（多余/缺失参数）
    // ==================================================================

    @Nested
    @DisplayName("组 6 —— 用法行与路由前置")
    class Routing {

        /**
         * 无参 `/le stats` 必须<b>不</b>进纯文本分支（仍是 ProfileGUI）。
         *
         * <p>这条是源码 lint：{@code isTextStatsRequest} 是私有静态方法，纯 JVM 起不来。
         * 它的判据被 cmd switch 与 console 短路分支共用，任一处放宽都会改变行为。
         */
        @Test
        @DisplayName("源码 lint：isTextStatsRequest 要求 args.length >= 2 且 args[0] 是 stats")
        void textStatsRequestRequiresTargetArgument() throws IOException {
            String body = methodBody(Path.of(
                    "src/main/java/com/letslearnenglish/minecraftplugin/command/LearnCommand.java"),
                    "private static boolean isTextStatsRequest(String[] args)");
            assertFalse(body.isEmpty(), "找不到 isTextStatsRequest 的方法体喵 —— 锚点失效");

            assertTrue(body.contains("args.length >= 2"),
                    "纯文本战绩必须要求带目标玩家参数喵（无参形态是 ProfileGUI，不能混）");
            assertTrue(body.contains("\"stats\""),
                    "该方法必须校验子命令是 stats 喵");
        }

        /** 用法文案必须真的存在于两份 yml，且含 {@code /le stats <玩家名>} 形态。 */
        @Test
        @DisplayName("usage 文案在 zh/en 都存在，且描述 /le stats <玩家名>")
        void usageTextMentionsTheSyntax() {
            String zhUsage = loadYaml(ZH_YML).getString(StatsTextFormatter.KEY_USAGE);
            String enUsage = loadYaml(EN_YML).getString(StatsTextFormatter.KEY_USAGE);
            assertNotNull(zhUsage);
            assertNotNull(enUsage);
            assertTrue(zhUsage.contains("/le stats"), "中文用法行没提到 /le stats：" + zhUsage);
            assertTrue(enUsage.contains("/le stats"), "英文用法行没提到 /le stats：" + enUsage);
        }
    }

    /** 取一个方法体（含首尾花括号）：剥注释后按签名定位，花括号配对。 */
    private static String methodBody(Path source, String signature) throws IOException {
        String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));
        int at = code.indexOf(signature);
        if (at < 0) {
            return "";
        }
        int open = code.indexOf('{', at + signature.length());
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        return "";
    }

    /**
     * 剥掉行注释与块注释（保留字符串字面量与换行）。
     *
     * <p>必须剥注释：本仓注释里到处引用被禁/被举例的代码片段（本文件的 javadoc 就在
     * 这么干），不剥的话第一次命中会是注释里的那一处。
     */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < s.length()
                        && !(s.charAt(i) == '*' && i + 1 < s.length() && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < s.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    out.append(d == '\n' ? '\n' : d);
                    i++;
                    if (d == '\\' && i < s.length()) {
                        out.append(s.charAt(i) == '\n' ? '\n' : s.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}