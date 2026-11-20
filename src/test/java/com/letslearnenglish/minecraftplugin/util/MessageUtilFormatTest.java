package com.letslearnenglish.minecraftplugin.util;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round 31：{@code MessageUtil} 的<b>行为</b>测试（此前 {@code new MessageUtil(} 在测试里 0 次，
 * 行覆盖 4.3%）。
 *
 * <p>已有的 {@code MessageUtilMissingKeyContractTest} 是<b>源码 lint</b>——它证明「源码里
 * 没有硬编码英文兜底」，但一行格式化逻辑都没执行过。缺键文案对了不代表占位符替换、
 * 前缀解析、null 处理是对的。
 *
 * <p>本类钉住三类东西：
 * <ol>
 *   <li>占位符替换的<b>边界</b>（奇数个 / null 元素 / 缺键）；</li>
 *   <li>两条同名 {@code getMessage} 路径的<b>语言来源不同</b>（全服语言 vs 玩家语言）——
 *       规划报告点名「没有任何测试区分」，这里补上；</li>
 *   <li>同一个类里三条路径对 null 的<b>一致口径</b>（null 值一律跳过，保留占位符原文）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MessageUtilFormatTest {

    /** {@code MISSING_KEY_PLACEHOLDER} 转换后的前缀（{@code &c[?}→{@code §c[?}）。 */
    private static final String MISSING_PREFIX = ChatColor.RED + "[?";

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private Logger logger;
    private Player player;

    /** 全服默认语言的那份 messages（{@code configManager.getMessageConfig()}）。 */
    private FileConfiguration serverZh;
    /** 玩家语言的那份 messages（{@code configManager.getMessageConfig("en")}）。 */
    private FileConfiguration playerEn;
    /** config.yml（前缀来源）。 */
    private FileConfiguration mainConfig;

    private MessageUtil util;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        logger = mock(Logger.class);
        player = mock(Player.class);

        mainConfig = new YamlConfiguration();

        // 两份 messages 各自带前缀，且**故意不同** —— 用来区分「读的是哪一份」。
        serverZh = new YamlConfiguration();
        serverZh.set("prefix", "&8[FILE-ZH] &f");
        serverZh.set("demo.greet", "{prefix}你好 {name}");
        serverZh.set("demo.only", "{prefix}无占位符");

        playerEn = new YamlConfiguration();
        playerEn.set("prefix", "&8[FILE-EN] &f");
        playerEn.set("demo.greet", "{prefix}hello {name}");
        playerEn.set("demo.only", "{prefix}no placeholders");

        // MessageUtil 用的是构造器注入的 configManager 字段，从不调 plugin.getConfigManager()
        // —— 在这里给它打桩只会变成未使用的严格桩（UnnecessaryStubbing）。
        lenient().when(plugin.getLogger()).thenReturn(logger);
        lenient().when(plugin.getPlayerLanguage(player)).thenReturn("en");
        lenient().when(configManager.getMainConfig()).thenReturn(mainConfig);
        lenient().when(configManager.getMessageConfig()).thenReturn(serverZh);
        lenient().when(configManager.getMessageConfig(anyString())).thenReturn(playerEn);
        lenient().when(configManager.getDefaultLanguage()).thenReturn("zh");

        util = new MessageUtil(plugin, configManager);
    }

    /**
     * {@code assertDoesNotThrow} 的<b>显式目标类型</b>包装。
     *
     * <p>直接写 {@code String s = assertDoesNotThrow(() -> f(), "msg")} 会撞上 JUnit 的
     * {@code Executable} / {@code ThrowingSupplier} 双重载（隐式 lambda 只有在有目标类型时
     * 才能决定是 void 还是值兼容）。形参声明为 {@link ThrowingSupplier} 后，调用点的
     * lambda 目标类型唯一，不存在重载歧义。
     */
    private static <T> T noThrow(ThrowingSupplier<T> supplier, String message) {
        return assertDoesNotThrow(supplier, message);
    }

    // ==================================================================
    // 1. 占位符替换：正常 + 奇数个 + null 元素
    // ==================================================================

    @Nested
    @DisplayName("组 A —— 占位符替换的边界")
    class Placeholders {

        @Test
        @DisplayName("正常成对替换 + {prefix} 展开")
        void substitutesPairsAndPrefix() {
            assertEquals(ChatColor.DARK_GRAY + "[FILE-ZH] " + ChatColor.WHITE + "你好 Alice",
                    util.getMessage("demo.greet", "name", "Alice"),
                    "占位符或前缀没替换 —— 这是每一条玩家文案的必经路径");
        }

        @Test
        @DisplayName("奇数量占位符 → 打 WARNING，不越界，其余照常替换")
        void oddPlaceholderCountWarnsAndDoesNotOverrun() {
            // length=3 → 循环条件 i < length-1 = 2，只处理 i=0 一对；i=2 的孤立键被忽略。
            // 旧式写法 for (i = 0; i < length; i += 2) 会在这里读 placeholders[3] → AIOOBE。
            String out = noThrow(
                    () -> util.getMessage("demo.greet", "name", "Alice", "orphan"),
                    "奇数个占位符越界了 —— 循环上界必须是 length - 1");
            assertEquals(ChatColor.DARK_GRAY + "[FILE-ZH] " + ChatColor.WHITE + "你好 Alice", out);
            verify(logger, atLeastOnce()).warning(contains("Odd number of placeholder arguments"));
        }

        @Test
        @DisplayName("单元素占位符（最常见的手滑）不越界")
        void singlePlaceholderDoesNotThrow() {
            // length-1 = 0 → 循环体不执行；告警照打。占位符原文保留。
            String out = noThrow(() -> util.getMessage("demo.greet", "name"),
                    "只传一个占位符参数就崩了 —— 循环上界 length-1 算错");
            assertTrue(out.contains("{name}"),
                    "孤立键无处可配，应原样保留，实际: " + out);
        }

        @Test
        @DisplayName("玩家路径：null 占位符被跳过，其余照常替换，不 NPE")
        void playerPathSkipsNullPlaceholders() {
            // formatMessage L189：if (placeholders[i] != null && placeholders[i+1] != null)
            String out = noThrow(
                    () -> util.getPlayerMessage(player, "demo.greet", "name", null),
                    "null 占位符在玩家路径上 NPE 了 —— 守卫被删");
            assertTrue(out.contains("{name}"),
                    "null 的占位符应保持未替换（原样留着 {name}），而不是崩掉或替换成 \"null\"");
            assertTrue(out.contains("FILE-EN"), "前缀仍须正常展开");
        }

        @Test
        @DisplayName("现状（新发现）：两参重载 getMessage(String,String...) 的 null 也 NPE")
        void twoArgOverloadCurrentlyThrowsNpeOnNullPlaceholder() {
            // 规划报告只点了「formatMessage 跳过 null、Map 重载不跳过」这一对不对称。
            // 实测更糟：同名的 String... 重载（L89-91）同样**没有** null 守卫，
            // 即三条路径里只有 formatMessage / formatSafe 两条防住了，另两条都会 NPE。
            assertThrows(NullPointerException.class,
                    () -> util.getMessage("demo.greet", "name", null),
                    "String... 重载不再 NPE 了 —— 说明有人补了守卫，"
                            + "请把本现状记录改成 assertDoesNotThrow 并同步更新缺陷清单");
        }

        @Test
        @DisplayName("占位符长度 0 / 空数组：正常返回")
        void emptyPlaceholdersIsFine() {
            assertTrue(util.getMessage("demo.greet").contains("FILE-ZH"));
            assertTrue(util.getMessage("demo.greet", new String[0]).contains("FILE-ZH"));
        }
    }

    // ==================================================================
    // 2. Map 重载的 null 行为：null 值已与玩家路径对齐；null 键仍被容忍
    // ==================================================================

    @Nested
    @DisplayName("组 B —— Map 重载对 null 的态度（值已对齐玩家路径，键保持容忍）")
    class MapOverloadNulls {

        @Test
        @DisplayName("正常 Map 替换")
        void mapSubstitutes() {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("name", "Bob");
            assertEquals(ChatColor.DARK_GRAY + "[FILE-ZH] " + ChatColor.WHITE + "你好 Bob",
                    util.getMessage("demo.greet", vars));
        }

        @Test
        @DisplayName("现状：Map 里 null **键** → 不 NPE，静默拼成字面量 {null}")
        void nullMapKeyIsSilentlyTolerated() {
            // 本轮只修 null 值；null 键仍按原样拼成 {null}（无匹配占位符，文案本体不动）。
            Map<String, String> vars = new HashMap<>();
            vars.put(null, "x");

            String out = noThrow(() -> util.getMessage("demo.greet", vars),
                    "null 键竟然也会 NPE —— 与本轮实测不符，请复核");
            assertTrue(out.contains("{name}"), "无关键替换，文案本体应保持不动");
            // 注意：Map 重载**没有**「奇数量占位符」那条 WARNING（它按 entry 遍历，没有奇偶概念）。
            // 两个 getMessage 的告警行为也不对称，这里只如实记录，不加断言以免与实现细节耦合。
        }

        /**
         * <b>已修（Round 31）</b>：{@code getMessage(String, Map)} 对 null 值不再 NPE。
         *
         * <p>同一个类里 {@code formatMessage}（玩家路径）与 {@code formatSafe} 都写了
         * {@code if (placeholders[i] != null && placeholders[i+1] != null)} 跳过 null，
         * 三条路径现在口径一致：null 值跳过该占位符（保留 {@code {name}} 原文）。
         * <b>不得</b>替换成字符串 {@code "null"}（那会把内部状态漏给玩家）。
         */
        @Test
        void nullMapValueMustNotNpe() {
            Map<String, String> vars = new HashMap<>();
            vars.put("name", null);

            String out = noThrow(() -> util.getMessage("demo.greet", vars),
                    "null 值应跳过而非 NPE —— Map 重载的 null 值守卫（continue）被删");
            assertTrue(out.contains("{name}"), "null 值应跳过替换，保留原占位符");
            assertFalse(out.contains("null"),
                    "不得把 null 值渲染成字面量 \"null\"（内部状态会漏给玩家），实际: " + out);
        }
    }

    // ==================================================================
    // 3. 同名方法、不同语言源（规划报告点名「没有测试区分」）
    // ==================================================================

    @Nested
    @DisplayName("组 C —— 两条 getMessage 路径的语言来源不同")
    class LanguageSourceDiverges {

        @Test
        @DisplayName("getMessage(...) 走全服默认语言 zh；getPlayerMessage(...) 走玩家语言 en")
        void serverDefaultVersusPlayerLanguage() {
            String serverSide = util.getMessage("demo.only");
            String playerSide = util.getPlayerMessage(player, "demo.only");

            assertTrue(serverSide.contains("FILE-ZH"),
                    "getMessage(...) 应读全服默认语言那份（zh），实际: " + serverSide);
            assertTrue(playerSide.contains("FILE-EN"),
                    "getPlayerMessage(...) 应读玩家自己那份（en），实际: " + playerSide);
            assertNotEquals(serverSide, playerSide,
                    "两条路径产出了同一串 —— 说明语言来源被合并成同一个了");
        }

        @Test
        @DisplayName("config.yml 的 prefix.<lang> 优先于 yml 自带 prefix，且按各自语言取")
        void configPrefixWinsAndIsChosenByEachLangsOwnLanguage() {
            mainConfig.set("prefix.zh", "&8[CFG-ZH] &f");
            mainConfig.set("prefix.en", "&8[CFG-EN] &f");

            assertTrue(util.getMessage("demo.only").contains("CFG-ZH"),
                    "默认语言 zh 应取 prefix.zh");
            assertTrue(util.getPlayerMessage(player, "demo.only").contains("CFG-EN"),
                    "玩家语言 en 应取 prefix.en —— 取错就是「半英半中」的经典来源");
        }

        @Test
        @DisplayName("前缀键缺失 → 回落 yml 自带 prefix（不是内置默认），不抛")
        void missingPrefixKeyFallsBackToFilePrefix() {
            // mainConfig 里没有 prefix.zh/en，serverZh 自己有 prefix
            assertEquals(ChatColor.DARK_GRAY + "[FILE-ZH] " + ChatColor.WHITE + "无占位符",
                    util.getMessage("demo.only"));
        }

        @Test
        @DisplayName("mainConfig 与语言文件都没有 prefix → 回落内置 DEFAULT_PREFIX，不 NPE")
        void noPrefixAnywhereFallsBackToBuiltIn() {
            // 只把 mainConfig 置 null 是不够的：serverZh 自己带 prefix，
            // resolvePrefix 会先命中语言文件那一层，于是测不到内置兜底。
            // 必须两层都空 —— 这才是「messages/*.yml 被手删了 prefix 行」的真实形态。
            when(configManager.getMainConfig()).thenReturn(null);
            FileConfiguration bareConfig = new YamlConfiguration();
            bareConfig.set("demo.only", "{prefix}bare");
            when(configManager.getMessageConfig()).thenReturn(bareConfig);

            String out = noThrow(() -> util.getMessage("demo.only"),
                    "两层都拿不到前缀时 NPE 了 —— resolvePrefix 的 null 守卫被删");
            assertEquals(ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "English" + ChatColor.DARK_GRAY
                            + "] " + ChatColor.WHITE + "bare", out,
                    "应回落到内置 DEFAULT_PREFIX（&8[&bEnglish&8] &f），实际: " + out);
        }
    }

    // ==================================================================
    // 4. 缺键 / 配置缺失：三条路径都必须可见
    // ==================================================================

    @Nested
    @DisplayName("组 D —— 缺键与配置缺失的可见兜底")
    class MissingKeyVisibility {

        @Test
        @DisplayName("三条路径缺键都返回含 [?key] 的可见占位符")
        void allPathsReturnVisiblePlaceholder() {
            Map<String, String> empty = new HashMap<>();

            String viaVarargs = util.getMessage("no.such.key");
            String viaMap = util.getMessage("no.such.key", empty);
            String viaPlayer = util.getPlayerMessage(player, "no.such.key");
            String viaSafe = util.getMessageSafe("no.such.key");

            for (String out : new String[]{viaVarargs, viaMap, viaPlayer, viaSafe}) {
                assertTrue(out.contains("[?no.such.key]"),
                        "缺键必须把键名漏出来，实际: " + out);
                assertTrue(out.startsWith(MISSING_PREFIX),
                        "缺键占位符应是红色（&c 经转换），实际: " + out);
            }
            // getGuiMsg 不带颜色（由调用方转），但同样可见
            assertEquals("[?no.such.key]", MessageUtil.getGuiMsg(serverZh, "no.such.key"));
        }

        @Test
        @DisplayName("messageConfig 为 null（快照未发布）→ 玩家路径也走可见兜底，不 NPE")
        void nullMessageConfigIsFailVisible() {
            when(configManager.getMessageConfig(anyString())).thenReturn(null);

            String out = noThrow(() -> util.getPlayerMessage(player, "demo.greet"),
                    "messageConfig 为 null 时 NPE 了 —— formatMessage 的守卫（R14-F3 FIX 3）被删");
            assertTrue(out.contains("[?demo.greet]"), "应走可见占位符，实际: " + out);
        }

        @Test
        @DisplayName("缺键的 WARNING 带 (lang=...)：能从日志看出是哪个语言文件缺")
        void missingKeyWarningCarriesLang() {
            util.getPlayerMessage(player, "no.such.key");
            verify(logger, atLeastOnce()).warning(contains("(lang=en)"));
        }
    }

    // ==================================================================
    // 5. Locale：源码 lint（不能靠改 Locale.setDefault 测 —— 会污染同 JVM 的其它测试）
    // ==================================================================

    /**
     * {@code WordLearningManager:840} 的 {@code String.format("%.1f", accuracy·100)}
     * 没有 Locale，在 {@code de}/{@code fr}/{@code tr} 下输出 {@code 85,3} 并直接进玩家结算文案。
     *
     * <p><b>为什么用源码 lint 而不是设 {@code Locale.setDefault(Locale.GERMANY)} 再断言</b>：
     * {@code Locale} 是 JVM 全局态，surefire 单 JVM 跑 1600+ 用例，
     * 一处 {@code setDefault} 泄漏就会让别的用例（含本仓仅有的 2 处既有用法）随机变红，
     * 排查成本远高于收益。而缺陷本身的判据是纯静态的：「有没有把 Locale 传进 format」。
     *
     * <p><b>为什么只挑「会本地化的数值转换」</b>：{@code %s}/{@code %x}/{@code %08x}
     * 不受 locale 影响（{@code PersonalScenarioStore:334} 就是 {@code %08x}，不该被误报）；
     * {@code %d}/{@code %f}/{@code %e}/{@code %g}/`%.Nf` 才会被本地化成逗号小数点或本地数字。
     */
    @Nested
    @DisplayName("组 E —— String.format 的 Locale（源码 lint）")
    class LocaleLint {

        private Path source(String relative) {
            Path p = Path.of("src/main/java/com/letslearnenglish/minecraftplugin/" + relative);
            assertTrue(Files.exists(p),
                    "必须读真实源文件（工作目录应为项目根）: " + p.toAbsolutePath());
            return p;
        }

        /** 判定某行是不是「缺 Locale 且含本地化数值转换」的 String.format。 */
        private boolean isLocaleSensitiveBareFormat(String line) {
            int idx = line.indexOf("String.format(\"");
            if (idx < 0) {
                return false;
            }
            int start = line.indexOf('"', idx) + 1;
            int end = line.indexOf('"', start);
            if (end < 0) {
                return false;
            }
            String fmt = line.substring(start, end);
            // 会随 locale 变化的转换符
            return java.util.regex.Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?([defgDFEG])")
                    .matcher(fmt).find();
        }

        @Test
        @DisplayName("已修：WordLearningManager 里无 Locale 的本地化数值 format 为 0 处")
        void noLocaleSensitiveBareFormatRemainsInWordLearningManager() throws Exception {
            String text = Files.readString(source("core/word/WordLearningManager.java"));

            int hits = 0;
            String hitLine = null;
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                    continue;   // 注释里引用旧代码不算违规
                }
                if (isLocaleSensitiveBareFormat(line)) {
                    hits++;
                    hitLine = line;
                }
            }

            assertEquals(0, hits,
                    "又出现了不带 Locale 的本地化数值 String.format（locale 相关输出会漏进玩家文案）。"
                            + "实际 " + hits + " 处，命中行: " + hitLine);
            assertTrue(text.contains("Locale.ROOT"),
                    "结算文案的 %.1f 应显式传 Locale.ROOT（de/fr/tr 下会打成 85,3）");
        }

        @Test
        @DisplayName("现状：PersonalScenarioStore 的 %08x 不算违规（不本地化，防误报）")
        void hexFormatIsNotFlagged() throws Exception {
            String text = Files.readString(source("core/ai/PersonalScenarioStore.java"));
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.contains("String.format(\"%08x\"")) {
                    assertFalse(isLocaleSensitiveBareFormat(line),
                            "%08x 不随 locale 变化，不该被本 lint 判为违规（否则注释/清单会跑偏）");
                }
            }
            assertTrue(text.contains("%08x"),
                    "PersonalScenarioStore 里的 %08x 不见了 —— 本防误报用例已失效，请复核");
        }

        /**
         * <b>已修（Round 31）</b>：{@code WordLearningManager} 里不得有不带 Locale 的
         * {@code String.format}（本地化数值转换）。
         *
         * <p><b>症状</b>：{@code accuracy = 0.853} → 期望 {@code "85.3"}；
         * 在 {@code de_DE} / {@code fr_FR} / {@code tr_TR} 的 JVM 上得到 {@code "85,3"}，
         * 这个值被拼进 {@code word-learning-gui.session-complete} 直接发给玩家。
         * 服务器 locale 由启动参数决定，服主基本不会去看。
         *
         * <p><b>修法</b>：{@code String.format(Locale.ROOT, "%.1f", ...)}。
         * 本仓 {@code WordManagerImpl:89/221/264/308} 的索引归一已用 {@code Locale.ROOT}，
         * 口径应当统一。
         */
        @Test
        void noLocaleSensitiveBareFormatInWordLearningManager() throws Exception {
            String text = Files.readString(source("core/word/WordLearningManager.java"));
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                    continue;
                }
                assertFalse(isLocaleSensitiveBareFormat(line),
                        "仍有不带 Locale 的本地化数值 String.format（locale 相关输出会漏进玩家文案）: "
                                + line);
            }
        }
    }
}
