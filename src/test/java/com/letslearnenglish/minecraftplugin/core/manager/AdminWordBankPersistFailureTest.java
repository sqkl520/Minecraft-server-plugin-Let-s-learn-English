package com.letslearnenglish.minecraftplugin.core.manager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * 词库导入的「落盘失败」契约：<b>失败绝不允许被说成成功</b>。
 *
 * <h2>这条缺陷是什么</h2>
 * <p>{@code ConfigManager.saveConfig} 原为 {@code void} 并吞掉 {@link IOException}，
 * {@code AdminCommandHandler.saveWordBankToYaml} 又是 {@code void} 且不检查结果，
 * {@code importWordBank} 于是在循环落盘之后<b>无条件</b>打
 * {@code "Admin: Import complete. Imported N words …"} 并发
 * {@code wordbank.import-summary}。磁盘满 / 权限不足 / 文件被占用时：
 *
 * <ul>
 *   <li>内存 {@link WordBank} 已经含新词（{@code targetBank.addWord(...)} 在落盘之前）；</li>
 *   <li>磁盘上的 {@code words/*.yml} 一个字节都没变；</li>
 *   <li>控制台与管理员都收到「导入完成」；</li>
 *   <li>重启后这些「已导入」的词全部消失，而管理员以为自己导成功了。</li>
 * </ul>
 *
 * <h2>为什么失败用例要真造一个 IO 失败，而不是 mock 掉 ConfigManager</h2>
 * <p>用 mock 返回 {@code false} 只能证明「if 分支写对了」，证明不了
 * <b>真实磁盘失败会走到那个分支</b>。这里把 {@code words/} 造成一个普通文件 ——
 * {@code YamlConfiguration.save} 建父目录时抛
 * {@code IOException("Unable to create parent directories of …")}，
 * 与「磁盘满 / 目录被别的东西占住」是同一类真实故障，且不依赖平台权限位。
 * 这样整条链路（真 ConfigManager → 真 save → 真 catch → 真返回值 → 真分支）
 * 都是产品代码，没有替身。
 *
 * <p>另有 {@link SaveConfigReturnValue} 直接钉 {@code saveConfig} 的双向返回值：
 * 成功 {@code true}、失败 {@code false}。这两支是上面端到端用例的地基 ——
 * 若 {@code saveConfig} 恒返回 {@code true}，端到端失败用例会红；若恒返回
 * {@code false}，成功用例会红。
 */
class AdminWordBankPersistFailureTest {

    private static final String DIFFICULTY = "beginner";

    private static final String SOURCE_YML =
            "difficulty: " + DIFFICULTY + "\n"
            + "description: 落盘失败用例词库\n"
            + "words:\n"
            + "  - english: apple\n"
            + "    chinese: pingguo\n"
            + "    difficulty_level: 1\n"
            + "  - english: banana\n"
            + "    chinese: xiangjiao\n"
            + "    difficulty_level: 1\n";

    @TempDir
    Path dataDir;

    // ==================================================================
    //  日志捕获（与 AdminWordBankYamlFieldSetTest 同一套路，独立一份避免耦合）
    // ==================================================================

    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final List<java.util.logging.Level> levels =
                Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("AdminWordBankPersistFailureTest." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    String msg = record.getMessage();
                    messages.add(msg == null ? "" : msg);
                    levels.add(record.getLevel());
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

        boolean anyAtOrAbove(java.util.logging.Level level) {
            synchronized (levels) {
                for (java.util.logging.Level l : levels) {
                    if (l.intValue() >= level.intValue()) {
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
     * 一次「服务器实例」的最小替身：{@code plugin} 是 mock，{@link ConfigManager} 与
     * {@link AdminCommandHandler} 是真对象，{@link WordManagerImpl} 只补它被导入路径
     * 用到的几个查询/写入点。
     *
     * <h2>为什么 {@code indexWord} / {@code registerWordBank} 必须真打桩到本地 map</h2>
     * <p>这两个方法是 mock 对象上的空实现 —— 不打桩的话，{@code importWordBank} 的 commit
     * 阶段「补索引」这件事在测试里<b>完全不可观测</b>，{@link #globalIndex} 会恒空，
     * 于是一条「忘了补索引」的回归可以全绿通过。这里用两个本地 map 承接，让
     * {@code wordManager.getWord(english)} 变成可断言的真实查询。
     *
     * @param makeWordsWritable {@code false} 时故意把 {@code words/} 造成一个普通文件，
     *                          让后续任何 {@code words/*.yml} 落盘都抛 {@link IOException}
     */
    private final class Harness implements AutoCloseable {
        final AdminCommandHandler handler;
        final Map<String, WordBank> banks = new LinkedHashMap<>();
        /** 承接 {@code WordManagerImpl.indexWord} 的全局索引（键 = english.toLowerCase）。 */
        final Map<String, com.letslearnenglish.minecraftplugin.core.word.Word> globalIndex =
                new LinkedHashMap<>();
        final Set<String> available = new LinkedHashSet<>();
        final Captured logs = new Captured();
        final CommandSender sender = mock(CommandSender.class);
        final MessageUtil messageUtil = mock(MessageUtil.class);

        Harness(boolean makeWordsWritable) throws IOException {
            Files.createDirectories(dataDir.resolve("exports"));
            if (makeWordsWritable) {
                Files.createDirectories(dataDir.resolve("words"));
            } else {
                // 普通文件占住 words 这个路径 → save 时无法建父目录 → IOException。
                Files.writeString(dataDir.resolve("words"), "not a directory",
                        StandardCharsets.UTF_8);
            }

            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
            when(plugin.getLogger()).thenReturn(logs.logger);

            ConfigManager configManager = new ConfigManager(plugin);
            when(plugin.getConfigManager()).thenReturn(configManager);

            // 把 getSenderMessage 变成一个「把 i18n key 原样吐出来」的探针：
            // 这样发送给管理员的文案里会带 <wordbank.xxx>，断言直接盯 key 的选择，
            // 不受 zh/en 文案未来改动影响。第三参是变长 String...，用 any(String[].class)
            // 匹配整个可变参数数组（Mockito 2+ 的 varargs 匹配语义）。
            when(messageUtil.getSenderMessage(any(CommandSender.class), anyString(),
                    any(String[].class)))
                    .thenAnswer(inv -> "<" + inv.getArgument(1) + ">");
            when(plugin.getMessageUtil()).thenReturn(messageUtil);

            WordManagerImpl wordManager = mock(WordManagerImpl.class);
            when(wordManager.getWordBank(anyString()))
                    .thenAnswer(inv -> banks.get((String) inv.getArgument(0)));
            when(wordManager.getAvailableDifficulties())
                    .thenAnswer(inv -> new LinkedHashSet<>(available));
            // getWord：与生产实现同口径（键 = toLowerCase(Locale.ROOT)）。
            when(wordManager.getWord(anyString()))
                    .thenAnswer(inv -> globalIndex.get(
                            ((String) inv.getArgument(0)).toLowerCase(java.util.Locale.ROOT)));
            // indexWord：把词写进本地索引，让「commit 阶段补索引」可观测。
            org.mockito.Mockito.doAnswer(inv -> {
                com.letslearnenglish.minecraftplugin.core.word.Word w = inv.getArgument(0);
                globalIndex.put(w.getEnglish().toLowerCase(java.util.Locale.ROOT), w);
                return null;
            }).when(wordManager).indexWord(any());
            // registerWordBank：注册进 banks + available，让新建档位可被后续 getWordBank 查到。
            org.mockito.Mockito.doAnswer(inv -> {
                String d = inv.getArgument(0);
                WordBank b = inv.getArgument(1);
                banks.put(d, b);
                available.add(d);
                return null;
            }).when(wordManager).registerWordBank(anyString(), any());
            when(plugin.getWordManager()).thenReturn(wordManager);

            this.handler = new AdminCommandHandler(plugin);
        }

        void putExport(String fileName, String ymlText) throws IOException {
            Files.writeString(dataDir.resolve("exports").resolve(fileName), ymlText,
                    StandardCharsets.UTF_8);
        }

        void seedBank(String difficulty, String ymlText) throws Exception {
            FileConfiguration cfg = new YamlConfiguration();
            cfg.loadFromString(ymlText);
            banks.put(difficulty, new WordBank(difficulty, cfg));
            available.add(difficulty);
        }

        void importFrom(String fileName) {
            handler.importWordBank(fileName, sender);
        }

        /** 本次导入发给管理员的全部消息（真实 {@code sendMessage} 调用）。 */
        List<String> senderMessages() {
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(sender, atLeastOnce()).sendMessage(captor.capture());
            return captor.getAllValues();
        }

        @Override
        public void close() {
            logs.close();
        }
    }

    // ==================================================================
    //  组 0 —— saveConfig 的返回值必须双向可区分
    // ==================================================================

    @Nested
    @DisplayName("组 0 · ConfigManager.saveConfig 的返回值")
    class SaveConfigReturnValue {

        @Test
        @DisplayName("写成功 → 返回 true")
        void successfulSaveReturnsTrue() throws IOException {
            Files.createDirectories(dataDir.resolve("words"));
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
            when(plugin.getLogger()).thenReturn(Logger.getLogger(
                    "AdminWordBankPersistFailureTest.saveOk." + UUID.randomUUID()));
            ConfigManager cm = new ConfigManager(plugin);

            FileConfiguration cfg = new YamlConfiguration();
            cfg.set("difficulty", DIFFICULTY);

            assertTrue(cm.saveConfig(cfg, "words/" + DIFFICULTY + ".yml"),
                    "落盘成功必须返回 true。若这里是 false，说明返回值被写反了 —— "
                            + "产品侧会把每一次正常导入都当成持久化失败。");
            assertTrue(Files.exists(dataDir.resolve("words").resolve(DIFFICULTY + ".yml")),
                    "返回 true 的前提是文件真的出现在磁盘上");
        }

        @Test
        @DisplayName("写失败（IOException）→ 返回 false，且仍打 SEVERE")
        void failedSaveReturnsFalse() throws IOException {
            // 普通文件占住 words 路径，制造真实 IOException
            Files.writeString(dataDir.resolve("words"), "not a directory",
                    StandardCharsets.UTF_8);
            Captured logs = new Captured();
            try {
                LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
                when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
                when(plugin.getLogger()).thenReturn(logs.logger);
                ConfigManager cm = new ConfigManager(plugin);

                FileConfiguration cfg = new YamlConfiguration();
                cfg.set("difficulty", DIFFICULTY);

                assertFalse(cm.saveConfig(cfg, "words/" + DIFFICULTY + ".yml"),
                        "落盘抛 IOException 时必须返回 false。恒 true 正是原缺陷："
                                + "调用方无法区分「写成功」与「磁盘满/无权限」。");
                assertTrue(logs.anyAtOrAbove(java.util.logging.Level.SEVERE),
                        "失败必须继续留痕（SEVERE），返回 false 不是为了把日志也一起吞掉。"
                                + "实际日志：\n" + String.join("\n", logs.snapshot()));
            } finally {
                logs.close();
            }
        }
    }

    // ==================================================================
    //  组 1 —— importWordBank 落盘失败时不得报成功（缺陷本体）
    // ==================================================================

    @Nested
    @DisplayName("组 1 · importWordBank 落盘失败")
    class ImportPersistFailure {

        @Test
        @DisplayName("落盘失败：不得发 import-summary、不得打 Import complete、必须发失败提示")
        void persistFailureNeverReportsSuccess() throws Exception {
            try (Harness h = new Harness(false)) {
                h.putExport("source.yml", SOURCE_YML);

                h.importFrom("source.yml");

                List<String> sent = h.senderMessages();
                List<String> logs = h.logs.snapshot();

                assertFalse(sent.stream().anyMatch(m -> m.contains("wordbank.import-summary")),
                        "落盘失败时仍发了 wordbank.import-summary（成功语义的总结）。"
                                + "这正是缺陷本体：内存有词、磁盘没写、管理员看到「导入完成」，"
                                + "重启后全部消失。实际发出的消息：" + sent);
                assertFalse(h.logs.anyContains("Import complete"),
                        "落盘失败时控制台仍打了 `Import complete`。实际日志：\n"
                                + String.join("\n", logs));

                assertTrue(sent.stream().anyMatch(m -> m.contains("wordbank.import-persist-failed")),
                        "落盘失败必须给管理员一条明确的失败提示（wordbank.import-persist-failed）。"
                                + "实际发出的消息：" + sent);

                assertTrue(h.logs.anyContains("PERSIST FAILED"),
                        "控制台必须留下能定位的失败日志（含哪个难度档失败）。实际日志：\n"
                                + String.join("\n", logs));
                // 文案必须把「后果」讲清：不是「重启后会丢」（那是旧行为 —— 词确实进了内存），
                // 而是「内存与磁盘都没变，修好后直接重试」。两个方向说反了都是谎报：
                // 说「重启后会丢」会让管理员误以为必须重启，说「已进内存」会让他以为不用重试。
                assertTrue(h.logs.anyContains("NOT be reported as duplicates"),
                        "日志必须告诉管理员「重试不会被报成 duplicate」—— 这是本次重构带来的"
                                + "新能力（旧实现必须重启才能重试）。实际日志：\n"
                                + String.join("\n", logs));
                assertFalse(h.logs.anyContains("LOST after a server restart"),
                        "落盘失败时内存并没有含新词，绝不能说「重启后会丢」（旧实现的文案）。"
                                + "实际日志：\n" + String.join("\n", logs));
                assertTrue(h.logs.anyAtOrAbove(java.util.logging.Level.SEVERE),
                        "持久化失败必须是 SEVERE（不是 INFO/WARNING）。实际级别序列："
                                + h.logs.levels);
            }
        }

        @Test
        @DisplayName("落盘失败：内存不变（活 bank 一个词都不加），且失败提示里点出受影响的难度档")
        void memoryIsUnchangedAndFailedDifficultyIsNamed() throws Exception {
            try (Harness h = new Harness(false)) {
                // 先注册一个既有 beginner 档（只有 apple），源文件要导入 apple + banana。
                // apple 会被跳过（duplicate），banana 是本次真正的新增 —— 于是「内存有没有变」
                // 变成一个精确到单条词的断言，而不是笼统的 getWordCount 对比。
                h.seedBank(DIFFICULTY, "difficulty: " + DIFFICULTY + "\n"
                        + "description: 既有词库\n"
                        + "words:\n  - english: apple\n    chinese: pingguo\n");
                WordBank live = h.banks.get(DIFFICULTY);
                int before = live.getWordCount();

                h.putExport("source.yml", SOURCE_YML);
                h.importFrom("source.yml");

                assertEquals(before, live.getWordCount(),
                        "落盘失败时活 WordBank 的词数必须<b>一个都不变</b>（本次重构的核心契约）。"
                                + "为 N（> 前置值 " + before + "）说明 addWord 又回到落盘之前了 —— "
                                + "那正是「内存与磁盘分叉、重试还被报成 duplicate」的根因。"
                                + "实际词：" + live.getAllWords().stream()
                                        .map(com.letslearnenglish.minecraftplugin.core.word.Word::getEnglish)
                                        .toList());
                assertNull(live.getWord("banana"),
                        "落盘失败时新增词不得出现在内存活词库里");
                assertFalse(Files.exists(dataDir.resolve("words").resolve(DIFFICULTY + ".yml")),
                        "前置条件：本用例中 words 路径被文件占住，落盘本就不可能成功");

                assertTrue(h.logs.anyContains(DIFFICULTY),
                        "失败日志必须点出受影响的难度档名，否则管理员不知道该修哪个文件。"
                                + "实际日志：\n" + String.join("\n", h.logs.snapshot()));
                assertFalse(h.globalIndex.containsKey("banana"),
                        "落盘失败时新增词也不得进 globalWordIndex —— 索引里留一个重启后不存在的词，"
                                + "会让错题本显示一个点不开的条目");
            }
        }

        @Test
        @DisplayName("落盘失败后重试：不再报 duplicate，第二次真的导入成功")
        void retryAfterFailureDoesNotReportDuplicates() throws Exception {
            // 「同一会话内重试会报 skipped N duplicates」是旧实现的明确代价（见旧版
            // importWordBank javadoc 的 "after a restart"）。本用例是这条代价的回归锁：
            // 落盘失败 → 内存不变 → 修好磁盘 → 重试一次必须 Imported 2 words、skipped 0。
            //
            // <b>刻意用「已存在的 beginner 档」而不是「全新难度」</b>：生产路径上
            // beginner/intermediate/advanced 三档在 loadAllWordBanks 之后就一定存在，
            // 所以「活 bank 被提前污染」才是真实形态。全新难度档走的是
            // pending.existing == null 的分支，根本不调 addWord —— 测不出这条分叉。
            try (Harness h = new Harness(false)) {
                h.seedBank(DIFFICULTY, "difficulty: " + DIFFICULTY + "\n"
                        + "description: 既有词库\n"
                        + "words:\n  - english: zeroword\n    chinese: ling\n");
                WordBank live = h.banks.get(DIFFICULTY);

                h.putExport("source.yml", SOURCE_YML);

                h.importFrom("source.yml");

                List<String> firstSent = h.senderMessages();
                assertTrue(firstSent.stream().anyMatch(
                                m -> m.contains("wordbank.import-persist-failed")),
                        "前置条件：第一次导入必须落盘失败。实际发出的消息：" + firstSent);
                assertFalse(Files.exists(dataDir.resolve("words").resolve(DIFFICULTY + ".yml")),
                        "前置条件：第一次导入没有写出文件");
                assertEquals(1, live.getWordCount(),
                        "前置条件：第一次失败后内存必须仍是 1 个词（zeroword）");

                // ---- 管理员修好磁盘（把占位的普通文件换成真目录）后原样重试 ----
                Files.delete(dataDir.resolve("words"));
                Files.createDirectories(dataDir.resolve("words"));

                h.importFrom("source.yml");

                List<String> logs = h.logs.snapshot();
                assertTrue(h.logs.anyContains("Imported 2 words"),
                        "重试必须真正导入 2 个词。若日志里是 `Imported 0 words`，说明第一次失败的词"
                                + "已经留在内存里了（旧实现的根因），重试全被判成 duplicate。"
                                + "实际日志：\n" + String.join("\n", logs));
                assertTrue(h.logs.anyContains("skipped 0 duplicates"),
                        "重试不得报 duplicate —— 第一次失败时一个词都没进内存，第二次看到的"
                                + "应该仍是全新的两个词。实际日志：\n" + String.join("\n", logs));
                assertTrue(Files.exists(dataDir.resolve("words").resolve(DIFFICULTY + ".yml")),
                        "重试成功后文件必须真的写出来");

                assertEquals(3, live.getWordCount(),
                        "重试成功后内存活词库应恰好是 3 个词（zeroword + apple + banana）。"
                                + "为 5 说明第一次失败的词被加了两次；为 1 说明 commit 没执行。"
                                + "实际词：" + live.getAllWords().stream()
                                        .map(com.letslearnenglish.minecraftplugin.core.word.Word::getEnglish)
                                        .toList());
            }
        }
    }

    // ==================================================================
    //  组 2 —— 全部成功时行为保持不变（防过度修复）
    // ==================================================================

    @Nested
    @DisplayName("组 2 · importWordBank 全部成功")
    class ImportAllSucceed {

        @Test
        @DisplayName("落盘成功：照旧打 Import complete、照旧发 import-summary、文件真的写出")
        void successPathUnchanged() throws Exception {
            try (Harness h = new Harness(true)) {
                h.putExport("source.yml", SOURCE_YML);

                h.importFrom("source.yml");

                List<String> sent = h.senderMessages();
                List<String> logs = h.logs.snapshot();

                assertTrue(h.logs.anyContains("Import complete. Imported 2 words"),
                        "全部落盘成功时必须保持原行为：控制台打 `Import complete. Imported 2 words …`。"
                                + "实际日志：\n" + String.join("\n", logs));
                assertTrue(sent.stream().anyMatch(m -> m.contains("wordbank.import-summary")),
                        "全部落盘成功时必须发 wordbank.import-summary。实际发出的消息：" + sent);
                assertFalse(sent.stream().anyMatch(m -> m.contains("wordbank.import-persist-failed")),
                        "成功路径上不该出现失败提示（把成功也报成失败同样是 bug）。"
                                + "实际发出的消息：" + sent);

                Path written = dataDir.resolve("words").resolve(DIFFICULTY + ".yml");
                assertTrue(Files.exists(written), "成功路径必须真的写出 words/" + DIFFICULTY + ".yml");
                FileConfiguration back = new YamlConfiguration();
                back.loadFromString(Files.readString(written, StandardCharsets.UTF_8));
                assertEquals(DIFFICULTY, back.getString("difficulty"),
                        "成功路径的落盘产物不该被这次修复影响（顶层 difficulty 仍在）");
            }
        }

        @Test
        @DisplayName("多档位：一档成功一档失败，仍必须整体报失败")
        void oneFailedBankOutOfTwoStillFailsTheImport() throws Exception {
            // 这一支才是「逐档记录」的真正判据：beginner 能写成功（words/beginner.yml 是普通路径），
            // advanced 写失败（words/advanced.yml 事先被造成一个目录 —— save 时
            // "拒绝访问" → IOException）。若实现只看**最后一个**档位的结果（或只看
            // 「循环有没有抛异常」），advanced 恰好是最后一项时仍会误报成功；
            // 若实现「第一个失败就 break」，则会漏掉后面档位 —— 两者都会被这一支抓住。
            try (Harness h = new Harness(true)) {
                Files.createDirectories(dataDir.resolve("words").resolve("advanced.yml"));
                h.putExport("multi.yml", "words:\n"
                        + "  - english: apple\n    chinese: pingguo\n    difficulty: beginner\n"
                        + "    difficulty_level: 1\n"
                        + "  - english: banana\n    chinese: xiangjiao\n    difficulty: advanced\n"
                        + "    difficulty_level: 1\n");

                // 两档都预先注册既有词库，让「哪一档的内存变了」可观测。
                h.seedBank("beginner", "difficulty: beginner\nwords:\n"
                        + "  - english: seedbegin\n    chinese: zhong\n");
                h.seedBank("advanced", "difficulty: advanced\nwords:\n"
                        + "  - english: seedadv\n    chinese: zhong\n");

                h.importFrom("multi.yml");

                List<String> sent = h.senderMessages();
                List<String> logs = h.logs.snapshot();

                assertTrue(Files.exists(dataDir.resolve("words").resolve("beginner.yml")),
                        "前置条件：beginner 档本应写成功（这一支要的就是「部分成功」）");
                assertFalse(sent.stream().anyMatch(m -> m.contains("wordbank.import-summary")),
                        "有一档落盘失败时，整体不得报成功。实际发出的消息：" + sent);
                assertTrue(h.logs.anyContains("PERSIST FAILED"),
                        "部分失败也必须进失败分支。实际日志：\n" + String.join("\n", logs));
                assertTrue(h.logs.anyContains("advanced"),
                        "失败日志必须点出失败的那一档（advanced）。实际日志：\n"
                                + String.join("\n", logs));

                // ---- 本次重构的核心：部分成功时每档的内存必须与该档的磁盘一致 ----
                WordBank beginner = h.banks.get("beginner");
                WordBank advanced = h.banks.get("advanced");
                assertEquals(2, beginner.getWordCount(),
                        "成功档 beginner 的内存必须含新增词（seedbegin + apple）。"
                                + "实际词：" + beginner.getAllWords().stream()
                                        .map(com.letslearnenglish.minecraftplugin.core.word.Word::getEnglish)
                                        .toList());
                assertNotNull(beginner.getWord("apple"),
                        "成功档的内存必须含 apple（磁盘与内存一致）");
                assertEquals(1, advanced.getWordCount(),
                        "失败档 advanced 的内存必须保持导入前的状态（只有 seedadv），"
                                + "不得含 banana。实际词：" + advanced.getAllWords().stream()
                                        .map(com.letslearnenglish.minecraftplugin.core.word.Word::getEnglish)
                                        .toList());
                assertNull(advanced.getWord("banana"),
                        "失败档的内存不得含 banana（否则又回到「内存有词、磁盘没有」的分叉）");

                // 磁盘侧同口径
                FileConfiguration written = new YamlConfiguration();
                written.loadFromString(Files.readString(
                        dataDir.resolve("words").resolve("beginner.yml"), StandardCharsets.UTF_8));
                assertEquals(2, written.getMapList("words").size(),
                        "成功档的磁盘产物也必须含 2 个词");
            }
        }

        @Test
        @DisplayName("多档位：全部失败时失败日志列出所有档位（逐档记录，不只报一个）")
        void allFailedBanksAreListedIndividually() throws Exception {
            // words 路径整体被普通文件占住 → 两档都写不了。
            // 断言失败列表同时包含两个档位：实现若在第一个失败处 return/break，
            // 日志里就只剩一个档位。
            try (Harness h = new Harness(false)) {
                h.putExport("multi.yml", "words:\n"
                        + "  - english: apple\n    chinese: pingguo\n    difficulty: beginner\n"
                        + "    difficulty_level: 1\n"
                        + "  - english: banana\n    chinese: xiangjiao\n    difficulty: advanced\n"
                        + "    difficulty_level: 1\n");

                h.importFrom("multi.yml");

                List<String> logs = h.logs.snapshot();
                assertTrue(h.logs.anyContains("PERSIST FAILED"), "两档都应失败并进入失败分支。"
                        + "实际日志：\n" + String.join("\n", logs));
                assertTrue(h.logs.anyContains("beginner") && h.logs.anyContains("advanced"),
                        "失败日志必须列出所有失败档位（逐档记录），而不是只报中间某一个。"
                                + "实际日志：\n" + String.join("\n", logs));
            }
        }

        @Test
        @DisplayName("落盘成功：内存与磁盘一致（防过度修复）")
        void successKeepsMemoryAndDiskConsistent() throws Exception {
            try (Harness h = new Harness(true)) {
                h.seedBank(DIFFICULTY, "difficulty: " + DIFFICULTY + "\n"
                        + "description: 既有词库\n"
                        + "words:\n  - english: apple\n    chinese: pingguo\n");
                WordBank live = h.banks.get(DIFFICULTY);

                h.putExport("source.yml", SOURCE_YML);
                h.importFrom("source.yml");

                // 源文件 apple + banana；apple 已存在 → skipped，banana 新增。
                assertEquals(2, live.getWordCount(),
                        "成功路径必须把新增词 commit 进内存（既有 apple + 新增 banana）。"
                                + "若这里是 1，说明 addWord 根本没被调用 —— 修「失败不改内存」时"
                                + "把成功路径也掐断了，这同样是 bug。实际词："
                                + live.getAllWords().stream()
                                        .map(com.letslearnenglish.minecraftplugin.core.word.Word::getEnglish)
                                        .toList());
                assertNotNull(live.getWord("banana"),
                        "成功路径的活词库必须能查到新增词");

                // 磁盘侧同口径：内存里有什么，落盘文件里就必须有什么。
                FileConfiguration written = new YamlConfiguration();
                written.loadFromString(Files.readString(
                        dataDir.resolve("words").resolve(DIFFICULTY + ".yml"), StandardCharsets.UTF_8));
                Set<String> onDisk = new LinkedHashSet<>();
                for (Map<?, ?> entry : written.getMapList("words")) {
                    onDisk.add(String.valueOf(entry.get("english")));
                }
                Set<String> inMemory = new LinkedHashSet<>();
                for (com.letslearnenglish.minecraftplugin.core.word.Word w : live.getAllWords()) {
                    inMemory.add(w.getEnglish());
                }
                assertEquals(onDisk, inMemory,
                        "内存与磁盘的 english 集合必须完全相同（内存=派生视图，磁盘=真相）。"
                                + "内存=" + inMemory + "，磁盘=" + onDisk);
            }
        }

        @Test
        @DisplayName("落盘成功：新增词进 globalWordIndex（getWord 能查到，不只是 addWord 进 bank）")
        void successIndexesNewWordsGlobally() throws Exception {
            // WordManagerImpl.getWord 只查 globalWordIndex，不查各档 WordBank。
            // 消费者：WrongBookGUI / WrongBookListener / ChatFeedbackManager / ReportGenerator。
            // 漏了这一步 = 「导入成功的词学习会话能抽到，但错题本显示不出来」。
            try (Harness h = new Harness(true)) {
                h.putExport("source.yml", SOURCE_YML);
                h.importFrom("source.yml");

                assertTrue(h.globalIndex.containsKey("apple"),
                        "新增词 apple 必须进 globalWordIndex（键 = toLowerCase）。"
                                + "实际索引键：" + h.globalIndex.keySet());
                assertTrue(h.globalIndex.containsKey("banana"),
                        "新增词 banana 必须进 globalWordIndex。实际索引键：" + h.globalIndex.keySet());
            }
        }

        @Test
        @DisplayName("新难度档：落盘成功后才注册进 wordManager，且词可查")
        void newlyCreatedDifficultyIsRegisteredAfterPersist() throws Exception {
            try (Harness h = new Harness(true)) {
                h.putExport("custom.yml", "difficulty: custom-r7\nwords:\n"
                        + "  - english: zzz\n    chinese: ce\n    difficulty_level: 1\n");

                h.importFrom("custom.yml");

                assertTrue(h.available.contains("custom-r7"),
                        "落盘成功后新建难度档必须注册进 wordManager（否则玩家选不到它）");
                assertNotNull(h.banks.get("custom-r7"),
                        "banks 里必须有 custom-r7 这个档位对象");
                assertEquals(1, h.banks.get("custom-r7").getWordCount(),
                        "新建档位的活词库必须含导入的词");
                assertTrue(h.globalIndex.containsKey("zzz"),
                        "新建档位的词也必须进 globalWordIndex");
            }
        }

        @Test
        @DisplayName("新难度档 + 落盘失败：不得注册、不得建索引、不得留内存对象")
        void newlyCreatedDifficultyIsNotRegisteredWhenPersistFails() throws Exception {
            try (Harness h = new Harness(false)) {
                h.putExport("custom.yml", "difficulty: custom-r8\nwords:\n"
                        + "  - english: zzz\n    chinese: ce\n    difficulty_level: 1\n");

                h.importFrom("custom.yml");

                assertFalse(h.available.contains("custom-r8"),
                        "落盘失败时新建难度档绝不能注册 —— 否则 getAvailableDifficulties 里会出现"
                                + "一个磁盘上并不存在的难度，玩家点进去必炸");
                assertNull(h.banks.get("custom-r8"),
                        "落盘失败时不得留下内存词库对象");
                assertFalse(h.globalIndex.containsKey("zzz"),
                        "落盘失败时不得建索引");
            }
        }
    }

    // ==================================================================
    //  组 3 —— saveConfig 必须是原子写（R-11）
    // ==================================================================

    /**
     * <b>为什么这组用例值得单独存在</b>：{@code saveConfig} 原实现是
     * {@code config.save(File)}，它 {@code new FileOutputStream(target)} <b>直接覆写目标文件</b>
     * （字节码已确认：{@code createParentDirs} → {@code saveToString()} → FileOutputStream 写入）。
     * 写到一半失败（磁盘满 / 进程被杀 / I/O 错误）就留下<b>半截 YAML</b>；
     * {@code YamlConfiguration.loadConfiguration} 会吞掉解析异常返回空配置，于是
     * {@code words/*.yml} 整档词库加载不出来，玩家侧表现为「这个难度突然没了」。
     * 修复方式是复用同类的 {@link ConfigManager} 内 {@code writeAtomically}
     * （写 {@code .tmp} → {@code Files.move(ATOMIC_MOVE)}）。
     *
     * <p><b>失败怎么构造</b>：把 {@code <target>.tmp} 这个路径先占成一个<b>空目录</b>。
     * {@code Files.writeString} 打不开目录，抛 {@code AccessDeniedException} ——
     * 真实文件系统拒绝，不是 mock 的异常（实测：空目录能被 {@code deleteIfExists} 清掉，
     * 所以「无残留」断言在这条路径上可满足）。此时目标文件必须一个字节都不变。
     *
     * <p><b>为什么它确实能抓住「改回直写」的回归</b>：直写实现根本不碰 {@code .tmp}，
     * 于是 {@code config.save(target)} 会<b>成功</b>并把目标文件覆盖成新内容，
     * 「目标文件逐字节未变」的断言立刻变红。
     */
    @Nested
    @DisplayName("组 3 · saveConfig 原子写")
    class AtomicSaveConfig {

        private static final String TARGET = "words/" + DIFFICULTY + ".yml";

        /** 目标文件的「好内容」：模拟磁盘上已有的、能正常加载的旧词库。 */
        private static final String GOOD_TARGET =
                "difficulty: " + DIFFICULTY + "\n"
                + "description: 旧词库（必须原样保留）\n"
                + "words:\n"
                + "  - english: apple\n"
                + "    chinese: pingguo\n"
                + "    difficulty_level: 1\n";

        private ConfigManager newManager(Captured logs) {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
            when(plugin.getLogger()).thenReturn(logs.logger);
            return new ConfigManager(plugin);
        }

        private Path tmpOf(Path target) {
            return Path.of(target.toAbsolutePath() + ".tmp");
        }

        @Test
        @DisplayName("写成功：目标文件内容与 saveToString 逐字节一致，且不留 .tmp")
        void successWritesExactContentAndLeavesNoTmp() throws IOException {
            Files.createDirectories(dataDir.resolve("words"));
            try (Captured logs = new Captured()) {
                ConfigManager cm = newManager(logs);

                YamlConfiguration cfg = new YamlConfiguration();
                cfg.set("difficulty", DIFFICULTY);
                cfg.set("description", "新词库");

                assertTrue(cm.saveConfig(cfg, TARGET), "原子写成功时必须返回 true。实际日志：\n"
                        + String.join("\n", logs.snapshot()));

                Path target = dataDir.resolve(TARGET);
                // Bukkit 的 FileConfiguration.save 就是「saveToString() + UTF-8 写出」，
                // 原子写必须产出完全相同的字节 —— 否则「行为不变」这句话不成立。
                assertEquals(cfg.saveToString(),
                        Files.readString(target, StandardCharsets.UTF_8),
                        "原子写产物必须与旧的 config.save(File) 逐字节一致："
                                + "旧实现就是 saveToString() 后以 UTF-8 写出（字节码已确认）");
                assertFalse(Files.exists(tmpOf(target)),
                        "写成功后不得残留 .tmp（残留文件会让数据目录被垃圾塞满，"
                                + "也会让人误以为有未完成的写入）");
            }
        }

        @Test
        @DisplayName("写失败：目标文件逐字节保持原样，且 .tmp 不残留")
        void failureKeepsTargetByteForByteAndCleansTmp() throws IOException {
            Path words = Files.createDirectories(dataDir.resolve("words"));
            Path target = words.resolve(DIFFICULTY + ".yml");
            Files.writeString(target, GOOD_TARGET, StandardCharsets.UTF_8);
            byte[] before = Files.readAllBytes(target);

            // 真实文件系统故障：<target>.tmp 被一个空目录占住 → writeString 抛 AccessDenied。
            // 与「磁盘满 / 权限不足」同一类：临时写入这一步根本进行不下去。
            Path tmp = tmpOf(target);
            Files.createDirectory(tmp);

            try (Captured logs = new Captured()) {
                ConfigManager cm = newManager(logs);

                YamlConfiguration cfg = new YamlConfiguration();
                cfg.set("difficulty", DIFFICULTY);
                cfg.set("description", "这次写不进去");
                cfg.set("words", List.of(Map.of("english", "banana")));

                assertFalse(cm.saveConfig(cfg, TARGET),
                        "临时文件写不进去时必须返回 false。实际日志：\n"
                                + String.join("\n", logs.snapshot()));

                assertArrayEquals(before, Files.readAllBytes(target),
                        "原子写的核心价值：写失败时目标文件必须逐字节保持原样。"
                                + "旧实现（config.save 直写）在这里会把目标文件覆盖成新内容 —— "
                                + "而真实故障（磁盘满 / 进程被杀）下它留下的将是半截 YAML，"
                                + "下次启动 YamlConfiguration 解析失败 → 整档词库加载不出来。"
                                + "实际内容：\n" + Files.readString(target, StandardCharsets.UTF_8));
                assertFalse(Files.exists(tmp),
                        "失败路径必须清理 .tmp（deleteQuietly）。残留的 tmp 会在数据目录里"
                                + "越积越多，且下次同路径写入会被它挡住");
                assertTrue(logs.anyAtOrAbove(java.util.logging.Level.SEVERE),
                        "写失败必须留 SEVERE 痕迹（返回 false 不是把日志也吞掉）。实际日志：\n"
                                + String.join("\n", logs.snapshot()));
            }
        }

        @Test
        @DisplayName("替换阶段失败（目标是非空目录）：目标内容原样保留，.tmp 被清理，且留 SEVERE")
        void failureAtMoveStageAlsoKeepsTarget() throws IOException {
            // 失败注入手法：让 words/<difficulty>.yml 这个「目标路径」本身是一个非空目录。
            //
            // 为什么不用「把目标文件设为只读」（旧写法，在 Linux CI 上会静默失效）：
            //   ATOMIC_MOVE 覆盖只读文件会失败，是 Windows/NTFS 的语义。POSIX 的 rename(2)
            //   只要求对「父目录」有写权限，与目标文件自身的权限位无关 —— Linux 上只读目标
            //   照样被成功替换，saveConfig 返回 true，于是这条用例在 CI 上红。
            //   改成「用普通文件去覆盖非空目录」后两个平台都会拒绝
            //   （Linux: rename → ENOTEMPTY/EISDIR；Windows: MoveFileEx → AccessDenied），
            //   是真实文件系统拒绝，不依赖任何权限位假设。
            //   「非空」是承重的：POSIX 的 rename 允许用文件替换一个**空**目录，删掉下面的
            //   sentinel 文件会让这条用例在 Linux 上变回假绿。不要改回只读文件，也不要用
            //   assumeTrue(isWindows) 跳过（那等于 CI 上不再覆盖这一支）。
            //
            // 为什么这仍然只测 move 阶段：.tmp 路径是 <target>.tmp，是 target 的兄弟
            // （见 tmpOf），不是它的子项；父目录 words/ 正常存在，因此
            // Files.writeString(tmp, ...) 能写成功。失败必定发生在随后的
            // Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)，即
            // AtomicFileWriter.moveIntoPlace 的 catch 分支 —— 与上面那条
            // （.tmp 被空目录占住、writeString 阶段就失败）覆盖的是不同的分支。
            Path words = Files.createDirectories(dataDir.resolve("words"));
            Path target = words.resolve(DIFFICULTY + ".yml");
            Files.createDirectory(target);
            Path sentinel = target.resolve("old-bank.yml");
            Files.writeString(sentinel, GOOD_TARGET, StandardCharsets.UTF_8);
            byte[] before = Files.readAllBytes(sentinel);

            Path tmp = tmpOf(target);
            try (Captured logs = new Captured()) {
                ConfigManager cm = newManager(logs);

                YamlConfiguration cfg = new YamlConfiguration();
                cfg.set("difficulty", DIFFICULTY);
                cfg.set("description", "替换阶段失败");

                assertFalse(cm.saveConfig(cfg, TARGET),
                        "替换失败必须返回 false（不能因为 .tmp 写成功就报成功）。实际日志：\n"
                                + String.join("\n", logs.snapshot()));
                assertTrue(Files.isDirectory(target),
                        "替换阶段失败时目标路径必须保持原样（仍是那个目录）。实际："
                                + (Files.exists(target) ? "存在" : "不存在"));
                try (var entries = Files.list(target)) {
                    assertEquals(1L, entries.count(),
                            "目标目录的内容不得被改动（既没被清空，也没有被塞进 .tmp）");
                }
                assertArrayEquals(before, Files.readAllBytes(sentinel),
                        "替换阶段失败时目标路径下的旧数据也必须逐字节保持原样（ATOMIC_MOVE "
                                + "失败不会半途改动目标）—— 与「目标文件不变」是同一条契约。"
                                + "实际内容：\n" + Files.readString(sentinel, StandardCharsets.UTF_8));
                assertFalse(Files.exists(tmp),
                        "替换失败后 .tmp 必须被清理，否则数据目录会残留一份孤儿配置");
                assertTrue(logs.anyAtOrAbove(java.util.logging.Level.SEVERE),
                        "替换失败必须留 SEVERE 痕迹（返回 false 不是把日志也吞掉）。实际日志：\n"
                                + String.join("\n", logs.snapshot()));
            }
        }

        @Test
        @DisplayName("父目录由 saveConfig 负责创建（Files.writeString 不会建父目录）")
        void parentDirectoryIsCreated() throws IOException {
            // 原实现 config.save(File) 内部 createParentDirs（字节码确认）。改成
            // Files.writeString 之后这个副作用会消失，于是「父目录还不存在」的调用点
            // 会从「能写」变成「写不了」—— 这是本类重构最典型的隐性回归。
            try (Captured logs = new Captured()) {
                ConfigManager cm = newManager(logs);

                YamlConfiguration cfg = new YamlConfiguration();
                cfg.set("difficulty", DIFFICULTY);

                assertTrue(cm.saveConfig(cfg, TARGET),
                        "dataFolder 下连 words/ 都不存在时也必须能写成功（旧行为如此）。"
                                + "实际日志：\n" + String.join("\n", logs.snapshot()));
                assertTrue(Files.exists(dataDir.resolve(TARGET)),
                        "父目录必须被创建，且文件真的落在 words/" + DIFFICULTY + ".yml");
            }
        }

        @Test
        @DisplayName("清理失败必须留下痕迹：.tmp 被非空目录占住 = 该路径永久写不进去")
        void nonEmptyTmpDirectoryIsReportedNotSwallowed() throws IOException {
            // deleteQuietly 原本的注释是「临时文件残留不影响数据安全，下次升级会覆盖同名 .tmp」
            // —— 这句话对「残留的普通文件」成立，对「非空目录」不成立：
            //   · deleteIfExists 抛 DirectoryNotEmptyException（吞掉）
            //   · Files.writeString 对已存在的目录同样失败
            // 于是该目标文件此后每一次写入都失败，而原因只剩一条早被吞掉的异常。
            // 本用例锁住「这个形态至少要留下可诊断的日志」。
            Path words = Files.createDirectories(dataDir.resolve("words"));
            Path target = words.resolve(DIFFICULTY + ".yml");
            Path tmp = tmpOf(target);
            Files.createDirectory(tmp);
            Files.writeString(tmp.resolve("blocker.txt"), "占位", StandardCharsets.UTF_8);

            Logger configLogger = Logger.getLogger(ConfigManager.class.getName());
            List<String> seen = Collections.synchronizedList(new ArrayList<>());
            Handler handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    String m = record.getMessage();
                    seen.add((m == null ? "" : m) + (record.getThrown() == null ? "" : " | "
                            + record.getThrown().getClass().getSimpleName()));
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            configLogger.addHandler(handler);
            try {
                try (Captured logs = new Captured()) {
                    ConfigManager cm = newManager(logs);
                    YamlConfiguration cfg = new YamlConfiguration();
                    cfg.set("difficulty", DIFFICULTY);

                    assertFalse(cm.saveConfig(cfg, TARGET),
                            "临时路径被非空目录占住时写入必须失败（不能假装成功）。实际日志：\n"
                                    + String.join("\n", logs.snapshot()));

                    assertTrue(Files.isDirectory(tmp) && Files.exists(tmp.resolve("blocker.txt")),
                            "前置：那个非空目录确实还在（否则本用例测的场景根本没发生）");

                    synchronized (seen) {
                        boolean reported = seen.stream().anyMatch(m ->
                                m.contains(tmp.toString())
                                        || m.contains("NON-EMPTY")
                                        || m.contains("DirectoryNotEmptyException"));
                        assertTrue(reported,
                                "清理失败必须留下指向具体残留路径的日志。原实现是 `catch (... ignored)`，"
                                        + "运维只能看到「保存失败」，无法知道该去手工删哪个目录。"
                                        + "实际捕获到：" + seen);
                    }
                }
            } finally {
                configLogger.removeHandler(handler);
                // @TempDir 清理前把占位目录还原成可删状态
                try (var walk = Files.walk(tmp)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        }

        /**
         * {@code ATOMIC_MOVE} 退化路径（{@code AtomicMoveNotSupportedException}）：<b>未覆盖</b>。
         *
         * <p>理由（不是偷懒）：该异常只在文件系统不支持原子 rename 时抛出（网络盘 / 某些
         * 容器挂载 / zipfs）。本测试跑在 NTFS 上，NTFS 支持原子 rename；而
         * {@code ConfigManager} 的落盘目标来自真实的 {@link java.io.File}（插件数据目录），
         * 无法指向 zipfs 之类「必定不支持」的路径。用 mock 顶替 {@code Files} 是 static
         * 调用，本仓库没有 mockito-inline。因此这一支只靠代码阅读保证：退化分支里
         * 先普通 move、失败则 deleteQuietly(tmp) 并返回 false —— 与主路径同构。
         *
         * <p>用 {@link Assumptions#abort} 而不是 {@code assertTrue(true)}：后者是一条
         * <b>永远绿的空断言</b>，会把「未覆盖」伪装成「已验证」。
         */
        @Test
        @DisplayName("ATOMIC_MOVE 退化路径：NTFS 上无法构造，显式标记为未覆盖（aborted）")
        void atomicMoveFallbackIsNotCovered() {
            Assumptions.abort("NTFS 支持原子 rename，无法在本机触发 AtomicMoveNotSupportedException；"
                    + "该分支靠代码阅读保证（退化分支与主路径同为「move 失败即 deleteQuietly + false」）");
        }
    }
}
