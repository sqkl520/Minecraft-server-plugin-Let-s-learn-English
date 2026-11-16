package com.letslearnenglish.minecraftplugin.core.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /lea importwordbank} 落盘产物（{@code words/<difficulty>.yml}）的 YAML 契约。
 *
 * <h2>这个类盯的是哪条缺陷</h2>
 * <p>slice-10-dc-02（HIGH）：{@code AdminCommandHandler} 里两条「词库 → YAML」路径
 * <b>字段集分叉</b>。{@code exportWordBank} 写了顶层 {@code difficulty}，
 * {@code saveWordBankToYaml}（导入后回写 / 新建词库时回写）没写。后果是一条完全静默的
 * 往返断裂链：
 *
 * <ol>
 *   <li>导入一次 → {@code words/beginner.yml} 失去顶层难度键；</li>
 *   <li>该文件仍能被 {@code WordManagerImpl} 按<b>文件名</b>加载，所以服务器看起来完全正常；</li>
 *   <li>但再导入同一个文件时，{@code importWordBank} 的两级兜底（先每词
 *       {@code difficulty}、再顶层 {@code config.getString("difficulty")}）双双为 null
 *       → 逐词 {@code No difficulty} 跳过 → {@code Imported 0 words, 2 errors}。</li>
 * </ol>
 *
 * <h2>为什么绝大多数用例端到端驱动 {@code importWordBank}</h2>
 * <p>{@code saveWordBankToYaml} 是 private，且它<b>唯一</b>的生产调用点就在
 * {@code importWordBank} 末尾。用反射直调私有方法能测「这一行写没写」，但测不出
 * 「写出来的键能不能被导入侧的兜底链读到」—— 而那才是缺陷的实际表现（键名对、层级错
 * 一样会 0 词成功）。所以组 1~3 的前几支全部走公开入口，断言落在<b>落盘文件</b>与
 * <b>产品日志</b>上，两者都是真代码产出，不是测试自证桩。
 *
 * <p><b>唯一的例外</b>是 {@code topLevelDifficultyComesFromParameterNotFromBank}：
 * 它必须反射直调，因为要验证的是「顶层键取<b>入参</b>而不是 {@code bank.getDifficulty()}」，
 * 而生产路径上这两个值恒等（唯一调用方传的正是构造 bank 用的同一字符串）——
 * 端到端无论怎么喂都区分不出来。变异实测：把赋值改成 {@code bank.getDifficulty()}
 * 后，其余 7 支全绿，只有这一支能杀掉它。
 *
 * <h2>为什么往返那一组要读日志</h2>
 * <p>缺陷态下第二次导入「什么都没写」（0 词成功 → {@code affectedBanks} 为空 → 连
 * {@code saveWordBankToYaml} 都不调用），所以 {@code words/beginner.yml} 在坏态与好态下
 * <b>内容完全相同</b>。文件层面不存在能区分二者的信号，唯一可观测的差别就是产品自己
 * 打的那两行日志（{@code No difficulty for word: …} 与
 * {@code Import complete. Imported N words …}）。断言读的是
 * {@code plugin.getLogger()} 发出的真实 {@link LogRecord}，不是替身。
 */
class AdminWordBankYamlFieldSetTest {

    // ==================================================================
    //  夹具：可跑真 YAML / 真 ConfigManager / 真 AdminCommandHandler
    // ==================================================================

    private static final String DIFFICULTY = "beginner";

    /**
     * 一条「全字段」词条 —— 用来钉「既有字段集一个都不许少」（验收标准 4）。
     *
     * <p>刻意把 {@code exportWordBank} 会写、而 {@code saveWordBankToYaml} 曾经漏写的
     * 键（顶层 {@code difficulty}）与所有可选键一次凑齐：任何一处 put 被删掉，往返后的
     * 取值断言都会变红，而不是靠「字段名还在不在」的源码 lint 去猜。
     */
    private static final String FULL_SOURCE_YML =
            "difficulty: " + DIFFICULTY + "\n"
            + "description: 字段集基准词库\n"
            + "words:\n"
            + "  - english: apple\n"
            + "    chinese: pingguo\n"
            + "    part_of_speech: noun\n"
            + "    phonetic: /ˈæp.əl/\n"
            + "    example_sentence: I ate an apple.\n"
            + "    synonyms:\n"
            + "      - fruit\n"
            + "    antonyms:\n"
            + "      - stone\n"
            + "    difficulty_level: 1\n"
            + "    reward: 2.5\n"
            + "    freq_rank: 530\n"
            + "  - english: banana\n"
            + "    chinese: xiangjiao\n"
            + "    difficulty_level: 1\n";

    @TempDir
    Path dataDir;

    /** 捕获一个 {@link Logger} 发出的全部记录（往返那一组唯一的可观测面）。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            // 每个用例一个独立命名 logger，setUseParentHandlers(false) 让记录只落本 handler，
            // 不污染 surefire 输出、也不受其他用例并发影响。
            this.logger = Logger.getLogger("AdminWordBankYamlFieldSetTest." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    String msg = record.getMessage();
                    messages.add(msg == null ? "" : msg);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
        }

        boolean anyContains(String needle) {
            synchronized (messages) {
                for (String m : messages) {
                    if (m.contains(needle)) {
                        return true;
                    }
                }
            }
            return false;
        }

        List<String> snapshot() {
            synchronized (messages) {
                return new ArrayList<>(messages);
            }
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    /**
     * 一次「服务器实例」的最小替身。
     *
     * <p>{@code plugin} 是 mock（{@link LetsLearnEnglish} 是 final 且其无参构造在纯 JVM 下
     * 会校验 ClassLoader，直接 new 走不通 —— 与 {@code ServerToggleTest} 同一理由）；
     * {@code ConfigManager} 与 {@code AdminCommandHandler} 是<b>真对象</b>；
     * {@code WordManagerImpl} 是 mock（它构造时会去读真实 {@code words/} 目录并注册监听，
     * 与本缺陷无关），只补 {@code getWordBank} / {@code getAvailableDifficulties} 两个查询。
     */
    private static final class Harness {
        final AdminCommandHandler handler;
        final Map<String, WordBank> banks = new LinkedHashMap<>();
        final Set<String> available = new LinkedHashSet<>();
        final Captured logs;
        /** 本实例的数据目录（静态嵌套类不能读外部实例字段，故自带一份）。 */
        final Path dataDir;

        Harness(Path dataDir) throws IOException {
            this.dataDir = dataDir;
            Files.createDirectories(dataDir.resolve("words"));
            Files.createDirectories(dataDir.resolve("exports"));

            this.logs = new Captured();

            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
            when(plugin.getLogger()).thenReturn(logs.logger);

            ConfigManager configManager = new ConfigManager(plugin);
            when(plugin.getConfigManager()).thenReturn(configManager);

            // 消息文案与本缺陷无关（判据在落盘文件与产品日志里），只保证调用不 NPE。
            // 刻意<b>不</b>给 getSenderMessage 打桩：它的第三个参数是变长 String...，
            // 用 any() 打桩很容易因匹配器个数不对在用例准备阶段就抛
            // InvalidUseOfMatchersException；mock 默认返回 null，此时
            // mock(CommandSender).sendMessage(null) 是无副作用的空操作，正好。
            when(plugin.getMessageUtil()).thenReturn(mock(MessageUtil.class));

            WordManagerImpl wordManager = mock(WordManagerImpl.class);
            when(wordManager.getWordBank(anyString()))
                    .thenAnswer(inv -> banks.get((String) inv.getArgument(0)));
            when(wordManager.getAvailableDifficulties())
                    .thenAnswer(inv -> new LinkedHashSet<>(available));
            when(plugin.getWordManager()).thenReturn(wordManager);

            this.handler = new AdminCommandHandler(plugin);
        }

        /** 把某份 YAML 当成「已被 WordManagerImpl 加载好的既有词库」注册进 mock。 */
        void seedBank(String difficulty, String ymlText) throws Exception {
            FileConfiguration cfg = new YamlConfiguration();
            cfg.loadFromString(ymlText);
            banks.put(difficulty, new WordBank(difficulty, cfg));
            available.add(difficulty);
        }

        /** 在 {@code exports/} 下放一个源文件（{@code importWordBank} 的第一查找位置）。 */
        void putExport(String fileName, String ymlText) throws IOException {
            Files.writeString(dataDir.resolve("exports").resolve(fileName), ymlText,
                    StandardCharsets.UTF_8);
        }

        Path writtenBank(String difficulty) {
            return dataDir.resolve("words").resolve(difficulty + ".yml");
        }

        /** 走公开入口导入一次（sender 用 mock：反馈文案不在本类断言范围内）。 */
        void importFrom(String fileName) {
            handler.importWordBank(fileName, mock(CommandSender.class));
        }
    }

    /**
     * 读回落盘 YAML。
     *
     * <p>用 {@code loadFromString} 而不是 {@code loadConfiguration(File)}：后者在文件缺失/解析
     * 失败时会去摸 Bukkit 的 logger，纯 JVM 下会 NPE（{@code WordFreqRankTest} 已踩过并留注释）。
     */
    private static FileConfiguration readYaml(Path file) throws IOException {
        assertTrue(Files.exists(file), "期望落盘文件存在：" + file.toAbsolutePath()
                + "（不存在说明 saveWordBankToYaml 根本没被调用）");
        FileConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail("落盘文件不是合法 YAML：" + file.toAbsolutePath() + " —— " + e);
        }
        return cfg;
    }

    private static List<?> words(FileConfiguration cfg) {
        List<?> list = cfg.getList("words");
        assertNotNull(list, "落盘文件里没有 words 列表");
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> wordEntry(FileConfiguration cfg, String english) {
        for (Object item : words(cfg)) {
            Map<String, Object> map = (Map<String, Object>) item;
            if (english.equals(map.get("english"))) {
                return map;
            }
        }
        fail("落盘文件里找不到词条 " + english);
        return Map.of(); // 不可达
    }

    private static Set<String> keysOf(Map<String, Object> map) {
        return new TreeSet<>(map.keySet());
    }

    // ==================================================================
    //  组 1 —— 落盘必须带顶层 difficulty（缺陷本体，基线必红）
    // ==================================================================

    @Nested
    @DisplayName("组 1 · saveWordBankToYaml 落盘的顶层 difficulty")
    class TopLevelDifficulty {

        @Test
        @DisplayName("导入一次后 words/<d>.yml 带顶层 difficulty，且值与传入难度一致")
        void writtenFileCarriesTopLevelDifficulty() throws Exception {
            Harness h = new Harness(dataDir);
            h.putExport("source.yml", FULL_SOURCE_YML);

            h.importFrom("source.yml");

            Path written = h.writtenBank(DIFFICULTY);
            FileConfiguration back = readYaml(written);

            assertEquals(DIFFICULTY, back.getString("difficulty"),
                    "落盘文件缺顶层 difficulty（或取值不对）。这是 slice-10-dc-02 的本体："
                            + "saveWordBankToYaml 原来只写 description + words[*]，"
                            + "与 exportWordBank:277 的 config.set(\"difficulty\", …) 分叉。"
                            + "缺这个键的后果不是「文件坏了」（它仍能被 WordManagerImpl 按文件名"
                            + "加载），而是再导入一次时两级兜底全 null → Imported 0 words。");
        }

        @Test
        @DisplayName("原始文本里 difficulty 是顶层行（缩进为 0），不是嵌在某个词条下面")
        void difficultyIsTopLevelInRawText() throws Exception {
            Harness h = new Harness(dataDir);
            h.putExport("source.yml", FULL_SOURCE_YML);

            h.importFrom("source.yml");

            String raw = Files.readString(h.writtenBank(DIFFICULTY), StandardCharsets.UTF_8);
            // 顶层键的写法就是行首 "difficulty:"（无缩进）。若有人把它写进 words[*] 的
            // LinkedHashMap 里，这里读到的会是 "  difficulty:"（带缩进）→ 断言变红。
            assertTrue(raw.contains("\ndifficulty: " + DIFFICULTY + "\n")
                            || raw.startsWith("difficulty: " + DIFFICULTY + "\n"),
                    "落盘文本里找不到行首的 `difficulty: " + DIFFICULTY + "`。"
                            + "importWordBank 的兜底链读的是 config.getString(\"difficulty\")"
                            + "（顶层）；写进每个词条里虽然也能被读到，但 1000+ 词的文件会多出"
                            + "1000+ 个重复键，是纯噪音。实际文本：\n" + raw);
        }

        @Test
        @DisplayName("顶层 difficulty 取的是**方法入参**，不是 bank.getDifficulty()")
        void topLevelDifficultyComesFromParameterNotFromBank() throws Exception {
            // 为什么必须钉这一个：acceptance 明确要求「值与该方法收到的 difficulty 参数一致」。
            // 落盘目标文件名是 difficulty + ".yml"，而该文件下次加载时难度又由**文件名**
            // 反推给 new WordBank(name, config)。只有「入参 → 顶层键」这一条赋值，
            // 才能保证 文件内记录的难度 == 文件名 == 下次加载得到的难度 三者恒等。
            //
            // 变异证据：把 config.set("difficulty", difficulty) 改成
            // config.set("difficulty", bank.getDifficulty()) 时，全部走 importWordBank 的
            // 端到端用例都仍是绿的（唯一调用方传的正是构造 bank 用的同一字符串，
            // 两个值在该路径上恒等）—— 于是这里必须直调私有方法、并**故意传一个与
            // bank 内部难度不同的字符串**，才能把这条赋值的方向钉死。
            Harness h = new Harness(dataDir);
            // bank 内部难度是 beginner，这里传 "parameter-wins"：两者刻意不相等。
            h.seedBank(DIFFICULTY, "difficulty: " + DIFFICULTY + "\n"
                    + "description: 参数优先\n"
                    + "words:\n  - english: only\n    chinese: wei\n");
            WordBank bank = h.banks.get(DIFFICULTY);

            java.lang.reflect.Method m = AdminCommandHandler.class.getDeclaredMethod(
                    "saveWordBankToYaml", String.class, WordBank.class);
            m.setAccessible(true);
            m.invoke(h.handler, "parameter-wins", bank);

            FileConfiguration written = readYaml(h.writtenBank("parameter-wins"));
            assertEquals("parameter-wins", written.getString("difficulty"),
                    "顶层 difficulty 必须取方法入参。若这里是 'beginner'，说明写的是 "
                            + "bank.getDifficulty() —— 在存在入参/文件名分叉的调用点上会写出"
                            + "「文件内难度 ≠ 文件名」的错配文件，下次加载时难度被文件名悄悄改写。");
        }

        @Test
        @DisplayName("新建词库（非既有档位）回写时也带顶层 difficulty")
        void newlyCreatedBankAlsoCarriesDifficulty() throws Exception {
            // 源文件难度 "custom-r6"：mock 的 WordManagerImpl 里没有这个档位，会走
            // 「Bank doesn't exist yet — create a word list file for it」分支。
            Harness h = new Harness(dataDir);
            h.putExport("custom.yml", "difficulty: custom-r6\nwords:\n"
                    + "  - english: zzz\n    chinese: ce\n    difficulty_level: 1\n");

            h.importFrom("custom.yml");

            FileConfiguration back = readYaml(h.writtenBank("custom-r6"));
            assertEquals("custom-r6", back.getString("difficulty"),
                    "新建档位走的是同一个 saveWordBankToYaml，必须同样带顶层 difficulty；"
                            + "否则自定义词库建完即无法再次导入");
            assertEquals(1, words(back).size(), "新词条的 words 列表内容不该受影响");
        }
    }

    // ==================================================================
    //  组 2 —— 往返可用（缺陷的真实后果）
    // ==================================================================

    @Nested
    @DisplayName("组 2 · 导入 → 落盘 → 再导入的往返")
    class RoundTrip {

        @Test
        @DisplayName("把落盘文件再导入一次：不得出现 Imported 0 words，也不得报 No difficulty")
        void writtenFileCanBeImportedAgain() throws Exception {
            // ---- 第一趟：导入源文件，生成 words/beginner.yml ----
            Harness first = new Harness(dataDir);
            first.putExport("source.yml", FULL_SOURCE_YML);
            first.importFrom("source.yml");
            assertTrue(Files.exists(first.writtenBank(DIFFICULTY)),
                    "前置失败：第一趟导入没有落盘，后续往返无从谈起");

            // ---- 第二趟：模拟「服务器重启后再导入这个文件」 ----
            // 全新 harness（mock 里没有任何已注册词库），源文件 = 上一趟的落盘产物副本。
            Path roundtripSrc = dataDir.resolve("exports").resolve("roundtrip.yml");
            Files.copy(first.writtenBank(DIFFICULTY), roundtripSrc,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Harness second = new Harness(dataDir);
            second.importFrom("roundtrip.yml");

            List<String> logs = second.logs.snapshot();
            assertFalse(second.logs.anyContains("No difficulty"),
                    "往返导入时出现了 `No difficulty` —— 这正是缺陷的原始签名："
                            + "落盘文件既没有每词 difficulty、也没有顶层 difficulty，"
                            + "importWordBank 的两级兜底全 null，逐词跳过。实际日志：\n"
                            + String.join("\n", logs));
            assertTrue(second.logs.anyContains("Imported 2 words"),
                    "往返导入应当是 `Imported 2 words`（源文件两条词条全部落库）。"
                            + "若日志里是 `Imported 0 words`，说明落盘的难度键仍然读不到。"
                            + "实际日志：\n" + String.join("\n", logs));
            assertTrue(second.logs.anyContains("0 errors"),
                    "往返导入的 errors 计数必须是 0（缺陷态是 2 —— 每个词一条）。"
                            + "实际日志：\n" + String.join("\n", logs));

            // 文件层面：第二趟覆盖写回的仍是同一个档位文件、仍带顶层键。
            FileConfiguration after = readYaml(second.writtenBank(DIFFICULTY));
            assertEquals(DIFFICULTY, after.getString("difficulty"),
                    "往返之后的落盘文件仍须带顶层 difficulty（幂等，不能一趟就退化）");
            assertEquals(2, words(after).size(),
                    "往返后词条数必须保持 2（缺陷态第二轮 0 词成功，文件内容停在旧版本）");
        }

        @Test
        @DisplayName("往返两次仍稳定（连续三轮：键不会在某轮被吃掉）")
        void repeatedRoundTripsStayStable() throws Exception {
            Harness h = new Harness(dataDir);
            h.putExport("source.yml", FULL_SOURCE_YML);
            h.importFrom("source.yml");

            for (int round = 1; round <= 2; round++) {
                Path src = dataDir.resolve("exports")
                        .resolve("roundtrip-" + round + ".yml");
                Files.copy(h.writtenBank(DIFFICULTY), src,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Harness next = new Harness(dataDir);
                next.importFrom(src.getFileName().toString());

                assertFalse(next.logs.anyContains("No difficulty"),
                        "第 " + (round + 1) + " 轮往返出现 `No difficulty`，日志：\n"
                                + String.join("\n", next.logs.snapshot()));
                assertTrue(next.logs.anyContains("Imported 2 words"),
                        "第 " + (round + 1) + " 轮往返的 imported 计数不是 2，日志：\n"
                                + String.join("\n", next.logs.snapshot()));
            }
        }
    }

    // ==================================================================
    //  组 3 —— 两条导出路径的字段集必须一致（缺陷的根因形态）
    // ==================================================================

    @Nested
    @DisplayName("组 3 · 与 exportWordBank 的字段集一致性")
    class ExportParity {

        @Test
        @DisplayName("saveWordBankToYaml 与 exportWordBank 的顶层键集、每词键集、取值全一致")
        void twoExportPathsProduceSameFieldSet() throws Exception {
            Harness h = new Harness(dataDir);
            h.putExport("source.yml", FULL_SOURCE_YML);

            // 路径 A：导入落盘 → words/beginner.yml
            h.importFrom("source.yml");
            Path written = h.writtenBank(DIFFICULTY);

            // 路径 B：把落盘文件当成既有词库注册进去，再走 exportWordBank → exports/*.yml
            h.seedBank(DIFFICULTY, Files.readString(written, StandardCharsets.UTF_8));
            h.handler.exportWordBank(DIFFICULTY, mock(CommandSender.class));

            File exported = newestExport();
            FileConfiguration viaSave = readYaml(written);
            FileConfiguration viaExport = readYaml(exported.toPath());

            assertEquals(new TreeSet<>(viaExport.getKeys(false)),
                    new TreeSet<>(viaSave.getKeys(false)),
                    "两条「词库 → YAML」路径的顶层键集必须一致。分叉正是 slice-10-dc-02 的"
                            + "根因形态：exportWordBank 写了 difficulty，saveWordBankToYaml"
                            + "没写。左边=exportWordBank，右边=saveWordBankToYaml。"
                            + "exported=" + exported.getName());

            assertEquals(viaExport.getString("difficulty"), viaSave.getString("difficulty"),
                    "顶层 difficulty 的取值也必须一致（同一个词库，两条路径不该写出两个难度）");
            assertEquals(viaExport.getString("description"), viaSave.getString("description"),
                    "description 取值必须一致");

            for (String english : List.of("apple", "banana")) {
                assertEquals(keysOf(wordEntry(viaExport, english)),
                        keysOf(wordEntry(viaSave, english)),
                        "词条 " + english + " 的键集在两条导出路径下必须一致"
                                + "（左边=exportWordBank，右边=saveWordBankToYaml）");
            }
        }

        @Test
        @DisplayName("全字段词条往返保真：既有字段集一个都不少、取值不变")
        void fullFieldWordSurvivesRoundTrip() throws Exception {
            Harness h = new Harness(dataDir);
            // 先注册一个「已有 description、但还没有 apple/banana」的 beginner 档：
            // importWordBank 会复用这个既有 bank（不是 new 一个空配置的），于是 description
            // 的保留/丢失变成可观测的。若目标 bank 是新建的，description 恒为空串
            // （源自 new YamlConfiguration()），那是与本缺陷无关的另一条既有行为。
            h.seedBank(DIFFICULTY, "difficulty: " + DIFFICULTY + "\n"
                    + "description: 字段集基准词库\n"
                    + "words:\n"
                    + "  - english: zeroword\n"
                    + "    chinese: ling\n");
            h.putExport("source.yml", FULL_SOURCE_YML);

            h.importFrom("source.yml");

            FileConfiguration back = readYaml(h.writtenBank(DIFFICULTY));
            Map<String, Object> apple = wordEntry(back, "apple");

            // 验收标准 4 的逐键清单：english/chinese/part_of_speech/phonetic/
            // example_sentence/synonyms/antonyms/difficulty_level/reward/freq_rank。
            assertEquals(new TreeSet<>(List.of("english", "chinese", "part_of_speech", "phonetic",
                            "example_sentence", "synonyms", "antonyms", "difficulty_level",
                            "reward", "freq_rank")),
                    keysOf(apple),
                    "词条的键集被改动了。本用例的作用是拦「顺手重写整个 saveWordBankToYaml」"
                            + "这类改动 —— 补 difficulty 是一行 set，不该顺手增删别的键。");

            assertEquals("pingguo", apple.get("chinese"));
            assertEquals("noun", apple.get("part_of_speech"));
            assertEquals("/ˈæp.əl/", apple.get("phonetic"));
            assertEquals("I ate an apple.", apple.get("example_sentence"));
            assertEquals(List.of("fruit"), apple.get("synonyms"));
            assertEquals(List.of("stone"), apple.get("antonyms"));
            assertEquals(1, ((Number) apple.get("difficulty_level")).intValue());
            assertEquals(2.5, ((Number) apple.get("reward")).doubleValue(), 1e-9);
            assertEquals(530, ((Number) apple.get("freq_rank")).intValue());

            // 缺省值不落盘的既有约定不能被顺带破坏：
            // banana 没有 reward / freq_rank / 各种可选串，写出来就是噪音（exportWordBank 同款取舍）。
            assertEquals(new TreeSet<>(List.of("english", "chinese", "difficulty_level")),
                    keysOf(wordEntry(back, "banana")),
                    "缺省字段（reward=0 / freq_rank 未知 / 可选串为 null）必须继续不落盘 —— "
                            + "这是 exportWordBank 与 saveWordBankToYaml 共同的既有取舍");

            assertEquals("字段集基准词库", back.getString("description"),
                    "description 必须照旧落盘（它是缺陷前后都有的键，不该被这次修复影响）");
        }
    }

    /** exports/ 目录下最新（也是唯一）一个导出文件。 */
    private File newestExport() {
        File dir = dataDir.resolve("exports").toFile();
        File[] files = dir.listFiles((d, name) -> name.startsWith(DIFFICULTY + "_export_"));
        assertNotNull(files, "exports/ 目录不可读");
        assertTrue(files.length > 0, "exportWordBank 没有产出任何文件（目录：" + dir + "）");
        File newest = files[0];
        for (File f : files) {
            if (f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest;
    }
}
