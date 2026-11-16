package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置驱动 GUI 槽位的边界校验（R12-slot）。
 *
 * <h2>缺陷本体</h2>
 * {@code ConfigManager#getGuiSlot} 是<b>无校验直读</b>
 * （{@code mainConfig.getInt("gui-layout.<gui>.<item>.slot", defaultSlot)}）。
 * 而 Bukkit 的 {@code Inventory#setItem} 对越界下标既不钳制也不给友好错误：Paper 1.21.4 的
 * {@code CraftInventoryCustom$MinecraftInventory.setItem} → {@code NonNullList.set} →
 * {@code List.set}，越界抛 {@code ArrayIndexOutOfBoundsException}
 * （<b>不是</b> {@code IllegalArgumentException} —— 后者是 {@code createInventory} 校验
 * {@code size % 9} 时抛的，与 setItem 无关）。
 *
 * <p>于是「服主把某个 slot 手滑多打一位」（{@code config.yml} 只写了
 * "0-53，主菜单 0-26"，27 格的 word-learning 写 28 属于"看起来合法"）的后果是：
 * {@code open()} 在 {@code player.openInventory(inv)} <b>之前</b>就抛异常 ——
 * 玩家侧表现是「点了没反应」，服主侧是日志里一条栈。三处 GUI
 * （{@code WordLearningGUI} 27 格 / {@code EnglishSquareGUI} 54+27 格 /
 * {@code LeaderboardGUI} 54 格）都要这道守卫。
 *
 * <h2>为什么这个类必须能真的崩</h2>
 * 如果 {@code Inventory} 只用 Mockito 的默认空实现（{@code setItem} 什么都不做），
 * 那么「校验被删掉」在测试里<b>毫无信号</b> —— 越界写入被 mock 静默吞掉，
 * 用例全绿而生产环境照崩。这正是本仓反复踩过的「假绿」。
 *
 * <p>因此这里用<b>状态化且会越界抛异常的 Inventory 假件</b>（{@link FakeInventory}）：
 * {@code setItem} 复刻 {@code ArrayList.set} 的真实行为 —— 下标 {@code <0} 或
 * {@code >= size} 就抛 {@code ArrayIndexOutOfBoundsException}。去掉守卫 → 用例立刻红，
 * 不是靠断言文案，而是靠「真的崩了」。
 *
 * <h2>为什么断言"写到了哪个槽"而不是"没抛异常"</h2>
 * 「钳到 0」也能不抛异常，但那会把多个项堆进槽位 0 —— 渲染后写覆盖先写、点击
 * if-else 先命中者胜，玩家看到 A 点下去执行 B（{@code MainMenu} 已把这类同槽错位判为
 * 「不可用伪装成可用」）。所以必须钉死<b>回落到了出厂默认槽位</b>，而不只是「没崩」。
 * {@link FakeInventory} 预先把所有格子填成 {@link #SENTINEL}，于是「非 SENTINEL 的格子」
 * 就是 GUI 真正写过的格子集合，可与默认槽位表逐位比对。
 *
 * <h2>纯 JVM 的两个必要拦截</h2>
 * 与 {@code MainMenuSlotConflictTest} / {@code GuiOpenIdentityTest} 同款：
 * {@code Bukkit.createInventory} 用 {@link MockedStatic} 拦截并返回
 * {@link FakeInventory}；{@code new ItemStack(...)} 用 {@link MockedConstruction} 拦截
 * （否则抛 {@code ExceptionInInitializerError}: No RegistryAccess）。
 */
@DisplayName("配置驱动 GUI 槽位边界校验（R12-slot）")
class GuiSlotBoundsTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final String LANG = "zh";

    /** 预填进每个格子的哨兵：非哨兵 = 被 GUI 显式写过。 */
    private static final ItemStack SENTINEL = mock(ItemStack.class);

    // ==================================================================
    // 脚手架
    // ==================================================================

    /**
     * 会越界抛异常的 Inventory 假件。
     *
     * <p>{@code setItem} 的行为逐字复刻 {@code ArrayList.set}（真实路径的终点）：
     * 先做 {@code Objects.checkIndex} 等价的下标判定，越界抛
     * {@link ArrayIndexOutOfBoundsException}。这不是"模拟"，是"同一语义"——
     * 生产环境崩的就是这个异常类型。
     *
     * <p>所有格子预填 {@link #SENTINEL}，因此 {@link #writtenSlots()} 精确等于
     * 「GUI 显式 setItem 过的格子」：{@code GUIUtils.fillEmpty} 只填空槽，看到哨兵就跳过，
     * 不会污染这个集合。
     */
    static final class FakeInventory {
        private final int size;
        private final Map<Integer, ItemStack> contents = new LinkedHashMap<>();

        FakeInventory(int size) {
            this.size = size;
        }

        Inventory build() {
            Inventory inv = mock(Inventory.class);
            when(inv.getSize()).thenReturn(size);
            lenient().when(inv.getItem(anyInt())).thenAnswer(i -> {
                int slot = i.getArgument(0);
                if (slot < 0 || slot >= size) {
                    throw new ArrayIndexOutOfBoundsException("Index " + slot + " out of bounds for length " + size);
                }
                return contents.getOrDefault(slot, SENTINEL);
            });
            lenient().doAnswer(i -> {
                int slot = i.getArgument(0);
                if (slot < 0 || slot >= size) {
                    throw new ArrayIndexOutOfBoundsException("Index " + slot + " out of bounds for length " + size);
                }
                contents.put(slot, i.getArgument(1));
                return null;
            }).when(inv).setItem(anyInt(), any());
            lenient().doAnswer(i -> {
                contents.clear();
                return null;
            }).when(inv).clear();
            return inv;
        }

        /** GUI 显式写过的槽位（不含 fillEmpty 的兜底填充）。 */
        Set<Integer> writtenSlots() {
            return new LinkedHashSet<>(contents.keySet());
        }
    }

    /** 收集 WARNING 的日志探针（本仓每个测试类各带一份，跨包私有工具不可复用）。 */
    static final class Captured implements AutoCloseable {
        final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        final List<String> all = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured(String owner) {
            this.logger = Logger.getLogger(owner + "." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    String msg = record.getMessage() == null ? "" : record.getMessage();
                    all.add(msg);
                    if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                        warnings.add(msg);
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            this.logger.addHandler(handler);
        }

        String warningsText() {
            return String.join("\n", warnings);
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    /** 一次 {@code open()} 的全部可观测结果。 */
    record Result(int createCalls,
                  Set<Integer> writtenSlots,
                  List<String> warnings,
                  Throwable thrown,
                  int inventorySize) {

        String warningsText() {
            return String.join("\n", warnings);
        }
    }

    /**
     * 把 {@code openAction} 跑一遍，收集「建了什么尺寸的界面 / 写了哪些槽 / 打了什么日志」。
     *
     * <p>{@code thrown} 捕获而不逃逸：断言要能区分「安静地回落了」与「真的炸了」，
     * 让异常逃逸会掩盖真正的判据。
     */
    private Result runOpen(Runnable openAction, int expectedSize, Captured logs) {
        FakeInventory fake = new FakeInventory(expectedSize);
        int[] calls = {0};
        int[] size = {-1};
        Throwable thrown = null;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        calls[0]++;
                        size[0] = invocation.getArgument(1);
                        return fake.build();
                    });
            try {
                openAction.run();
            } catch (Throwable t) {
                thrown = t;
            }
        }
        return new Result(calls[0], fake.writtenSlots(), logs.warnings, thrown, size[0]);
    }

    /** 共用 stub：语言、文案、材质回填默认值。 */
    private void stubCommon(LetsLearnEnglish plugin, ConfigManager cm,
                            FileConfiguration messages, Logger logger) {
        when(plugin.getConfigManager()).thenReturn(cm);
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn(LANG);
        when(plugin.getLogger()).thenReturn(logger);
        when(cm.getMessageConfig(anyString())).thenReturn(messages);
        when(messages.getString(anyString())).thenReturn("text");
        // 回填调用方默认实参 = 「管理员没改配置里的 material」。
        lenient().when(cm.getGuiMaterial(anyString(), anyString(), any(org.bukkit.Material.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    /**
     * 槽位 stub：{@code overrides} 里有的键返回覆盖值（可能是非法值，正是被测输入），
     * 其余一律回填调用方默认实参（= 出厂配置）。
     */
    private void stubSlots(ConfigManager cm, Map<String, Integer> overrides) {
        lenient().when(cm.getGuiSlot(anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String gui = invocation.getArgument(0);
                    String key = invocation.getArgument(1);
                    Integer override = overrides.get(gui + "." + key);
                    return override != null ? override : (int) invocation.getArgument(2);
                });
    }

    private static Set<Integer> slots(int... values) {
        Set<Integer> set = new LinkedHashSet<>();
        for (int v : values) {
            set.add(v);
        }
        return set;
    }

    // ==================================================================
    // WordLearningGUI（27 格）
    // ==================================================================

    @Nested
    @DisplayName("WordLearningGUI —— gui-layout.word-learning.*.slot / 27 格")
    class WordLearning {

        /** 7 个功能槽的出厂默认值，与生产代码 RESERVED_SLOT_DEFAULTS 同序。 */
        private static final Set<Integer> DEFAULTS = slots(8, 4, 10, 13, 16, 14, 22);

        private Result open(Map<String, Integer> overrides, Captured logs) {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            ConfigManager cm = mock(ConfigManager.class);
            FileConfiguration messages = mock(FileConfiguration.class);
            Player player = mock(Player.class);
            MessageUtil messageUtil = mock(MessageUtil.class);
            WordLearningManager wlManager = mock(WordLearningManager.class);

            stubCommon(plugin, cm, messages, logs.logger);
            stubSlots(cm, overrides);
            when(plugin.getMessageUtil()).thenReturn(messageUtil);
            when(messageUtil.getPlayerMessage(any(Player.class), anyString())).thenReturn("label");
            when(player.getUniqueId()).thenReturn(PLAYER_ID);
            when(player.isOnline()).thenReturn(true);
            when(plugin.getWordLearningManager()).thenReturn(wlManager);
            when(wlManager.getComboMultiplier(anyInt())).thenReturn(1.0);

            Word word = new Word("apple", "苹果", "noun", "/ˈæp.əl/", "I ate an apple.",
                    List.of(), List.of(), "beginner", 1);
            LearningSession session = new LearningSession(PLAYER_ID, "beginner", "spelling", List.of(word));
            when(wlManager.getQuizState(PLAYER_ID)).thenReturn(new WordLearningManager.QuizState(
                    session, "beginner", "spelling", 20, false, 4));

            return runOpen(() -> new WordLearningGUI(plugin).open(player, "beginner", "spelling"),
                    27, logs);
        }

        @Test
        @DisplayName("配置槽位 99（越界）→ 整组回落默认槽位（连合法的自定义值一并打回）")
        void outOfRangeSlotFallsBackToDefault() {
            try (Captured logs = new Captured("WlOutOfRange")) {
                // 两个键一起改：一个越界（99）、一个合法但非默认（progress 配 20）。
                // 这条组合是「整组回落 vs 逐键回落」的唯一区分点：
                //   整组回落 → progress 也打回默认 4；
                //   逐键回落 → progress 留在 20（且此时 20 与选项首选位重合，会撞出同槽）。
                Result result = open(Map.of(
                        "word-learning.start-button", 99,
                        "word-learning.progress-info", 20), logs);

                assertEquals(null, result.thrown(),
                        "槽位 99 越界时必须被拦下、不得让 setItem 抛出去喵。实际抛出：" + result.thrown());
                assertEquals(1, result.createCalls(), "界面仍须被创建一次喵");
                assertEquals(27, result.inventorySize(), "本菜单固定 27 格喵");
                assertEquals(DEFAULTS, result.writtenSlots(),
                        "越界后必须回落到出厂默认槽位表 {8,4,10,13,16,14,22} 喵 —— "
                                + "实际写入：" + result.writtenSlots() + "。\n"
                                + "这里断言的是**整组**回落：合法的 progress=20 也必须一起打回 4。"
                                + "若是逐键回落，progress 会留在 20，而 20 正是选项首选位之一 —— "
                                + "按钮与选项抢同一格，渲染后写覆盖、点击先命中者胜，玩家看到 A 点到 B。");
            }
        }

        @Test
        @DisplayName("配置槽位为负数 → 同样回落默认槽位")
        void negativeSlotFallsBackToDefault() {
            try (Captured logs = new Captured("WlNegative")) {
                Result result = open(Map.of("word-learning.back-button", -5), logs);

                assertEquals(null, result.thrown(),
                        "负数槽位必须被拦下喵。实际抛出：" + result.thrown());
                assertEquals(DEFAULTS, result.writtenSlots(),
                        "负数越界后同样回落到默认表喵，实际写入：" + result.writtenSlots());
            }
        }

        @Test
        @DisplayName("越界时打 WARNING，且含完整配置路径与实际值")
        void outOfRangeSlotLogsWarningWithPath() {
            try (Captured logs = new Captured("WlWarn")) {
                open(Map.of("word-learning.start-button", 99), logs);

                String warnings = logs.warningsText();
                assertFalse(warnings.isEmpty(),
                        "越界必须打 WARNING 喵 —— 服主唯一的排查线索就是这条日志。实际全部日志："
                                + String.join("\n", logs.all));
                assertTrue(warnings.contains("gui-layout.word-learning.start-button.slot"),
                        "WARNING 必须给出完整配置路径，否则服主不知道该改哪一行喵。实际：\n" + warnings);
                assertTrue(warnings.contains("99"),
                        "WARNING 必须写明实际配置值（99），服主才认得出是自己手滑喵。实际：\n" + warnings);
                assertTrue(warnings.contains("0..26"),
                        "WARNING 必须写明合法区间（0..26，本菜单 27 格）喵。实际：\n" + warnings);
            }
        }

        @Test
        @DisplayName("配置槽位合法 → 照配置渲染（防过度修复）")
        void legalSlotIsHonoured() {
            try (Captured logs = new Captured("WlLegal")) {
                Result result = open(Map.of("word-learning.start-button", 25), logs);

                assertEquals(null, result.thrown(), "合法槽位不得抛异常喵。抛出：" + result.thrown());
                Set<Integer> expected = slots(8, 4, 10, 13, 16, 14, 25);
                assertEquals(expected, result.writtenSlots(),
                        "合法的 25 必须原样生效（默认的 22 不该出现）喵 —— 实际写入："
                                + result.writtenSlots() + "。\n"
                                + "这条防的是「过度修复」：为了不崩而忽略管理员配置，等于把布局功能删了。");
                assertTrue(logs.warnings.isEmpty(),
                        "合法配置不该产生任何 WARNING 喵。实际：" + logs.warningsText());
            }
        }

        /**
         * choice 模式的选项避让必须与渲染侧用同一份（回落后的）槽位表。
         *
         * <p>变异指向：把 {@code choiceOptionSlotsFor(layout)} 改回
         * {@code resolveChoiceOptionSlots(cm)}（重新逐键读原配置）→ 本用例红。
         * 具体表现：配置把 {@code back-button} 配成 18（越界则整组回落，18 不越界，
         * 所以这里改用越界值来触发回落）——回落表说 back 在 8、选项首选 18；而按原配置
         * 避让会以为 18 被占，选项退到 19..22，其中 22 正是 start-button —— 选项盖住按钮。
         */
        @Test
        @DisplayName("选项避让用回落后的槽位表，不能用原配置（否则选项会盖住按钮）")
        void choiceOptionAvoidanceUsesResolvedSlots() {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            ConfigManager cm = mock(ConfigManager.class);
            FileConfiguration messages = mock(FileConfiguration.class);
            Player player = mock(Player.class);
            MessageUtil messageUtil = mock(MessageUtil.class);
            WordLearningManager wlManager = mock(WordLearningManager.class);

            try (Captured logs = new Captured("WlChoiceAvoid")) {
                stubCommon(plugin, cm, messages, logs.logger);
                // start-button 配成 99 → 整组回落；回落表里 18..21 全部空着，
                // 4 个选项应当落在 18/19/20/21，一个都不该落到功能槽上。
                stubSlots(cm, Map.of("word-learning.start-button", 99));
                when(plugin.getMessageUtil()).thenReturn(messageUtil);
                when(messageUtil.getPlayerMessage(any(Player.class), anyString())).thenReturn("label");
                when(player.getUniqueId()).thenReturn(PLAYER_ID);
                when(plugin.getWordLearningManager()).thenReturn(wlManager);
                when(wlManager.getComboMultiplier(anyInt())).thenReturn(1.0);

                List<Word> words = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    words.add(new Word("w" + i, "词" + i, "noun", "", "sentence " + i,
                            List.of(), List.of(), "beginner", 1));
                }
                LearningSession session = new LearningSession(PLAYER_ID, "beginner", "choice", words);
                when(wlManager.getQuizState(PLAYER_ID)).thenReturn(new WordLearningManager.QuizState(
                        session, "beginner", "choice", 20, false, 4));
                when(wlManager.getChoiceOptions(PLAYER_ID)).thenReturn(words);

                Result result = runOpen(
                        () -> new WordLearningGUI(plugin).open(player, "beginner", "choice"),
                        27, logs);

                assertEquals(null, result.thrown(), "choice 渲染不得抛异常喵。抛出：" + result.thrown());
                Set<Integer> written = result.writtenSlots();
                // 功能槽（回落表）+ 4 个选项槽，且两集合不相交。
                Set<Integer> optionCandidates = slots(18, 19, 20, 21);
                for (int optionSlot : optionCandidates) {
                    assertTrue(written.contains(optionSlot),
                            "choice 模式下 4 个选项必须落在 18/19/20/21 喵（回落表里这些位都空着）。"
                                    + "实际写入：" + written);
                }
                assertEquals(11, written.size(),
                        "应是 7 个功能槽 + 4 个选项槽 = 11 个互不重叠的格子喵。实际写入（"
                                + written.size() + " 个）：" + written);
            }
        }
    }

    // ==================================================================
    // EnglishSquareGUI —— 双尺寸（主页 54 / 笑话页 27）
    // ==================================================================

    @Nested
    @DisplayName("EnglishSquareGUI —— english-square.*.slot / 主页 54、笑话页 27")
    class EnglishSquare {

        private LetsLearnEnglish plugin;
        private ConfigManager cm;
        private FileConfiguration messages;
        private Player viewer;
        private PlayerDataStore store;

        private void baseStubs(Captured logs, Map<String, Integer> overrides) {
            plugin = mock(LetsLearnEnglish.class);
            cm = mock(ConfigManager.class);
            messages = mock(FileConfiguration.class);
            viewer = mock(Player.class);
            store = mock(PlayerDataStore.class);

            stubCommon(plugin, cm, messages, logs.logger);
            stubSlots(cm, overrides);
            when(viewer.getUniqueId()).thenReturn(PLAYER_ID);
            when(plugin.getPlayerDataStore()).thenReturn(store);
            when(plugin.getSocialManager()).thenReturn(null);      // social 门关
            when(cm.isLeaderboardEnabled()).thenReturn(true);       // 榜单门开
            // open() 读 progress-system.leaderboard.display-count（空列表下不参与渲染）。
            FileConfiguration mainConfig = mock(FileConfiguration.class);
            when(cm.getMainConfig()).thenReturn(mainConfig);
            when(mainConfig.getInt(anyString(), anyInt())).thenReturn(10);
        }

        /** 空广场（零行）打开主页时的全部 stub —— 与本类被测的槽位守卫无关，只为走通渲染。 */
        private void stubEmptySquare() {
            when(store.countActiveSquareEntries()).thenReturn(0);
            when(store.getActiveSquareEntries(anyInt(), anyInt())).thenReturn(Collections.emptyList());
            PlayerDataStore.PlayerStats stats = mock(PlayerDataStore.PlayerStats.class);
            when(store.getPlayerStats(PLAYER_ID)).thenReturn(stats);
        }

        @Test
        @DisplayName("笑话页（27 格）遇到对主页（54 格）合法的槽位 44 → 回落默认 8，不越界")
        void hiddenPageFallsBackWhenSlotOnlyFitsThe54SlotHome() {
            try (Captured logs = new Captured("SqHidden")) {
                // 44 对主页（54 格）完全合法 —— 把返回键挪到右下角的常见配置。
                baseStubs(logs, Map.of("english-square.back-button", 44));

                Result result = runOpen(
                        () -> new EnglishSquareGUI(plugin).openHidden(viewer, PLAYER_ID), 27, logs);

                assertEquals(null, result.thrown(),
                        "笑话页只有 27 格，槽位 44 必须被拦下喵 —— 否则开一次崩一次，"
                                + "而它是隐身玩家的替代页，普通玩家看不到、服主也无从发现。\n"
                                + "实际抛出：" + result.thrown());
                assertEquals(27, result.inventorySize(), "笑话页固定 27 格喵");
                assertTrue(result.writtenSlots().contains(8),
                        "必须回落到 back-button 的出厂默认值 8 喵。实际写入：" + result.writtenSlots());
                assertFalse(result.writtenSlots().contains(44),
                        "44 绝不能出现在 27 格的笑话页里喵。实际写入：" + result.writtenSlots());
                assertTrue(logs.warningsText().contains("0..26"),
                        "WARNING 必须写明本页的合法区间 0..26（不是主页的 0..53）喵。实际：\n"
                                + logs.warningsText());
            }
        }

        @Test
        @DisplayName("同一个 44 在主页（54 格）上合法 → 原样生效（判据是当前页尺寸，不是常量）")
        void homePageHonoursSlotThatFits54() {
            try (Captured logs = new Captured("SqHome")) {
                baseStubs(logs, Map.of("english-square.back-button", 44));
                stubEmptySquare();

                Result result = runOpen(() -> new EnglishSquareGUI(plugin)
                        .open(viewer, 1, Leaderboard.RankingMode.ALL_PLAYERS), 54, logs);

                assertEquals(null, result.thrown(), "主页 54 格下 44 合法，不得抛异常喵。抛出："
                        + result.thrown());
                assertEquals(54, result.inventorySize(), "主页固定 54 格喵");
                assertTrue(result.writtenSlots().contains(44),
                        "44 在 54 格主页上完全合法，必须原样生效喵 —— 若这里也被回落，"
                                + "说明守卫用了常量 27 而不是当前页尺寸。实际写入：" + result.writtenSlots());
                assertTrue(logs.warnings.isEmpty(),
                        "合法配置不该告警喵。实际：" + logs.warningsText());
            }
        }

        @Test
        @DisplayName("主页槽位 99（对 54 也越界）→ 整组回落，正常打开")
        void homePageFallsBackOnOutOfRange() {
            try (Captured logs = new Captured("SqHomeOob")) {
                baseStubs(logs, Map.of("english-square.prev-page", 99));
                stubEmptySquare();

                Result result = runOpen(() -> new EnglishSquareGUI(plugin)
                        .open(viewer, 1, Leaderboard.RankingMode.ALL_PLAYERS), 54, logs);

                assertEquals(null, result.thrown(),
                        "槽位 99 越界必须被拦下喵。实际抛出：" + result.thrown());
                assertTrue(logs.warningsText().contains("gui-layout.english-square.prev-page.slot"),
                        "WARNING 必须点名具体键喵。实际：\n" + logs.warningsText());
                assertTrue(logs.warningsText().contains("0..53"),
                        "主页的合法区间是 0..53 喵。实际：\n" + logs.warningsText());
                // 整组回落 → 模式按钮回到出厂槽（本用例 social 门关，故只有 2/3/4/5，
                // 没有 feed 的 6），返回键回到 8，自己头像 0、空态 22。
                Set<Integer> expected = slots(0, 2, 3, 4, 5, 8, 22);
                assertEquals(expected, result.writtenSlots(),
                        "整组回落必须把模式按钮打回出厂槽位喵（social 门关时 feed 的 6 不渲染，"
                                + "故本用例期望 " + expected + "）。实际写入：" + result.writtenSlots());
            }
        }

        @Test
        @DisplayName("笑话页槽位合法（8）→ 不告警、正常打开")
        void hiddenPageLegalSlotIsQuiet() {
            try (Captured logs = new Captured("SqHiddenOk")) {
                baseStubs(logs, Map.of());

                Result result = runOpen(
                        () -> new EnglishSquareGUI(plugin).openHidden(viewer, PLAYER_ID), 27, logs);

                assertEquals(null, result.thrown(), "合法配置不得抛异常喵。抛出：" + result.thrown());
                assertTrue(result.writtenSlots().contains(8), "返回键应在出厂槽 8 喵。实际："
                        + result.writtenSlots());
                assertTrue(logs.warnings.isEmpty(), "合法配置不该告警喵。实际：" + logs.warningsText());
            }
        }
    }

    // ==================================================================
    // LeaderboardGUI（54 格）
    // ==================================================================

    @Nested
    @DisplayName("LeaderboardGUI —— leaderboard.*.slot / 54 格")
    class LeaderboardGui {

        /**
         * 空榜（单词榜 tab）下实际会被写的出厂槽位：
         * tab-coins 0 / tab-words 1 / tab-combo 2 / tab-season 占位 3 / back 8 / 空态 22。
         * 注意 season-info(4) 只在赛季 tab 渲染，本用例走默认落点（单词榜）故不在其中。
         */
        private static final Set<Integer> RENDERED_DEFAULTS = slots(0, 1, 2, 3, 8, 22);

        private Result open(Map<String, Integer> overrides, Captured logs) {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            ConfigManager cm = mock(ConfigManager.class);
            FileConfiguration messages = mock(FileConfiguration.class);
            Player player = mock(Player.class);
            ProgressManagerImpl progressManager = mock(ProgressManagerImpl.class);
            Leaderboard leaderboard = mock(Leaderboard.class);

            stubCommon(plugin, cm, messages, logs.logger);
            stubSlots(cm, overrides);
            when(player.getUniqueId()).thenReturn(PLAYER_ID);
            when(plugin.getProgressManager()).thenReturn(progressManager);
            when(progressManager.getLeaderboard()).thenReturn(leaderboard);
            when(leaderboard.getCachedWordsLeaderboard()).thenReturn(Collections.emptyList());
            when(plugin.getSeasonManager()).thenReturn(null);   // 赛季不可用

            return runOpen(() -> new LeaderboardGUI(plugin).open(player), 54, logs);
        }

        @Test
        @DisplayName("槽位 99 → 整组回落默认表（合法的自定义键也一并打回），界面正常打开")
        void outOfRangeSlotFallsBack() {
            try (Captured logs = new Captured("LbOob")) {
                // back-button 越界 99 + tab-combo 合法但非默认 7。
                // 只有「整组回落」才会把 tab-combo 也从 7 打回 2；逐键回落会留下 7。
                Result result = open(Map.of(
                        "leaderboard.back-button", 99,
                        "leaderboard.tab-combo", 7), logs);

                assertEquals(null, result.thrown(),
                        "槽位 99 越界必须被拦下喵。实际抛出：" + result.thrown());
                assertEquals(1, result.createCalls(), "界面仍须被创建一次喵");
                assertEquals(54, result.inventorySize(), "排行榜固定 54 格喵");
                assertEquals(RENDERED_DEFAULTS, result.writtenSlots(),
                        "越界后必须整组回落到出厂表喵 —— tab-coins 0 / tab-words 1 / tab-combo 2 / "
                                + "tab-season 占位 3 / back 8 / 空态 22。\n"
                                + "注意 tab-combo 也必须回到 2（不是留在合法的 7）：四处的槽位策略"
                                + "必须一致，否则「主菜单越界会整组回落、排行榜越界会逐键回落」"
                                + "这种分裂本身就是新的坑。\n"
                                + "实际写入：" + result.writtenSlots());
            }
        }

        @Test
        @DisplayName("槽位为负数 → 同样回落")
        void negativeSlotFallsBack() {
            try (Captured logs = new Captured("LbNeg")) {
                Result result = open(Map.of("leaderboard.season-info", -1), logs);

                assertEquals(null, result.thrown(), "负数槽位必须被拦下喵。抛出：" + result.thrown());
                assertTrue(result.writtenSlots().containsAll(RENDERED_DEFAULTS),
                        "负数越界后同样回落到默认表喵（本 tab 实际渲染的是 "
                                + RENDERED_DEFAULTS + "）。实际写入：" + result.writtenSlots());
            }
        }

        @Test
        @DisplayName("越界打 WARNING，含完整路径与实际值")
        void outOfRangeLogsWarning() {
            try (Captured logs = new Captured("LbWarn")) {
                open(Map.of("leaderboard.tab-words", 54), logs);

                String warnings = logs.warningsText();
                assertFalse(warnings.isEmpty(),
                        "越界必须打 WARNING 喵。实际全部日志：" + String.join("\n", logs.all));
                assertTrue(warnings.contains("gui-layout.leaderboard.tab-words.slot"),
                        "WARNING 必须给出完整配置路径喵。实际：\n" + warnings);
                assertTrue(warnings.contains("54"),
                        "WARNING 必须写明实际值 54（54 格界面的经典手滑值：以为最大是 54）喵。实际：\n"
                                + warnings);
                assertTrue(warnings.contains("0..53"),
                        "WARNING 必须写明合法区间 0..53 喵。实际：\n" + warnings);
            }
        }

        @Test
        @DisplayName("合法槽位 → 照配置渲染（防过度修复）")
        void legalSlotIsHonoured() {
            try (Captured logs = new Captured("LbLegal")) {
                Result result = open(Map.of("leaderboard.back-button", 26), logs);

                assertEquals(null, result.thrown(), "合法槽位不得抛异常喵。抛出：" + result.thrown());
                assertTrue(result.writtenSlots().contains(26),
                        "合法的 26 必须原样生效喵。实际写入：" + result.writtenSlots());
                assertFalse(result.writtenSlots().contains(8),
                        "回落值 8 不该在合法配置下出现喵。实际写入：" + result.writtenSlots());
                assertTrue(logs.warnings.isEmpty(),
                        "合法配置不该告警喵。实际：" + logs.warningsText());
            }
        }
    }

    // ==================================================================
    // 公共方法本身
    // ==================================================================

    @Nested
    @DisplayName("GUIUtils 槽位守卫（四处 GUI 共用的同一份实现）")
    class UtilsGuard {

        @Test
        @DisplayName("isSlotInRange 的两端都是开区间上界")
        void rangeBoundaries() {
            assertFalse(GUIUtils.isSlotInRange(-1, 27), "-1 必须判越界喵");
            assertTrue(GUIUtils.isSlotInRange(0, 27), "0 合法喵");
            assertTrue(GUIUtils.isSlotInRange(26, 27), "26 合法喵（最后一格）");
            assertFalse(GUIUtils.isSlotInRange(27, 27),
                    "27 必须判越界喵 —— 这正是「27 格界面配了 27」的经典手滑");
        }

        @Test
        @DisplayName("readSlot：合法原样返回、越界回落默认值")
        void readSlotHonoursAndFallsBack() {
            ConfigManager cm = mock(ConfigManager.class);
            when(cm.getGuiSlot("demo", "ok", 5)).thenReturn(9);
            when(cm.getGuiSlot("demo", "bad", 5)).thenReturn(99);

            assertEquals(9, GUIUtils.readSlot(cm, null, "demo", "ok", 5, 27));
            assertEquals(5, GUIUtils.readSlot(cm, null, "demo", "bad", 5, 27),
                    "越界必须回落该键自己的默认值（不是 0、不是 size-1）喵");
        }

        @Test
        @DisplayName("readSlots：任一越界即整组回落（不是逐键回落）")
        void readSlotsFallsBackWholeGroup() {
            ConfigManager cm = mock(ConfigManager.class);
            String[] keys = {"a", "b", "c"};
            int[] defaults = {1, 2, 3};
            when(cm.getGuiSlot("demo", "a", 1)).thenReturn(4);
            when(cm.getGuiSlot("demo", "b", 2)).thenReturn(5);
            when(cm.getGuiSlot("demo", "c", 3)).thenReturn(99);   // 只有这一个越界

            int[] resolved = GUIUtils.readSlots(cm, null, "demo", keys, defaults, 27);

            assertEquals(1, resolved[0],
                    "整组回落必须连合法的 a 一起打回默认值 1 喵 —— 实际拿到 " + resolved[0]
                            + "。逐键回落会让 a/b 保持 4/5 而 c 变 3，凑巧撞上时就是同槽错位。");
            assertEquals(3, resolved[2], "越界的 c 回落到默认 3 喵");
        }

        @Test
        @DisplayName("logger 为 null 时也永不抛（纯 JVM 单测的真实形态）")
        void nullLoggerNeverThrows() {
            ConfigManager cm = mock(ConfigManager.class);
            when(cm.getGuiSlot(anyString(), anyString(), anyInt())).thenReturn(99);

            assertDoesNotThrow(
                    () -> GUIUtils.readSlot(cm, null, "demo", "x", 7, 27),
                    "plugin.getLogger() 为 null 时必须安静回落，不能因为写不了日志就崩喵");
            assertDoesNotThrow(
                    () -> GUIUtils.readSlots(cm, null, "demo", new String[] {"x"}, new int[] {7}, 27),
                    "批量版同理喵");
        }
    }
}
