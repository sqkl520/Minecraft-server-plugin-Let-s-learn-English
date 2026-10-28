package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.duel.DuelRoom;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * L11：{@code DuelGUI} 的比分槽位数组与 {@link DuelRoom#WIN_SCORE} 解耦验收。
 *
 * <p><b>为什么需要这个类</b>：原实现把「布局容量」和「胜负判定」当成同一个数 ——
 * 两张槽位数组各 5 项，循环上界写死 {@code DuelRoom.WIN_SCORE}。两者今天都是 5，
 * 于是任何测试都看不出问题；但 {@code WIN_SCORE} 是<b>业务规则</b>（先到几分算赢），
 * 未来调成 6/7 是极自然的需求，而那一刻渲染路径会抛
 * {@code ArrayIndexOutOfBoundsException} —— <b>开界面即崩</b>，而且只有打到第 6 分的
 * 玩家能撞上，本地小局测试根本跑不到。这类「两个常量被当成一个」的耦合不会让任何
 * 现有用例变红，只能靠一条专门钉住「不匹配也不炸」的测试。
 *
 * <p><b>为什么用源码锚点 + 行为断言两条腿</b>：{@code WIN_SCORE} 是
 * {@code public static final int}，编译期内联，反射改它不会影响已编译的循环条件
 * —— 想真的「把常量改成 6 再渲染」只能改生产代码。因此：
 * <ul>
 *   <li>源码锚点钉住「循环上界由 {@code Math.min(WIN_SCORE, slots.length)} 夹住」；</li>
 *   <li>行为断言用「比分 &gt; 槽位容量」的真实对局跑通渲染，覆盖「超容量不越界」。</li>
 * </ul>
 * 两条合起来：无论 WIN_SCORE 变大、还是比分被写脏变大，渲染路径都不会越界。
 *
 * <p><b>技术前提</b>（与 {@code ProfileDuelRowGuardTest} 同款）：
 * <ul>
 *   <li>{@code new ItemStack(Material)} 在纯 JVM 里抛 {@code ExceptionInInitializerError}
 *       （无 RegistryAccess），故整类用 {@link MockedConstruction} 拦截；</li>
 *   <li>{@code Inventory} 用<b>状态化</b> mock（getItem/setItem 背后挂一张 Map），
 *       否则 {@code buildInventory} 开头的「灰玻璃铺满」循环读到 null 会误判；</li>
 *   <li>铺底循环会调 {@code filler.clone()}，Mockito 默认答返回 null —— 给构造 mock
 *       一个 defaultAnswer 让 {@code clone} 返回自身。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DuelGuiSlotLayoutTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    /** 与 {@code DuelGUI.GUI_SIZE} 同值；刻意重写一份，值改了本类才会红。 */
    private static final int GUI_SIZE = 27;

    /** 左侧比分槽位（与 DuelGUI 的 p1Slots 同值，刻意重写以便布局改动时变红）。 */
    private static final int[] LEFT_SCORE_SLOTS = {10, 11, 12, 19, 20};
    /** 右侧比分槽位（p2Slots 同值）。 */
    private static final int[] RIGHT_SCORE_SLOTS = {14, 15, 16, 23, 24};

    @Mock
    private LetsLearnEnglish plugin;
    @Mock
    private ConfigManager configManager;
    @Mock
    private FileConfiguration messages;
    @Mock
    private Player player;

    @BeforeEach
    void setUp() {
        // 三个 GUI 都以 getConfigManager().getMessageConfig(lang) 起手。
        // lenient：本类的源码锚点用例不打开界面，不消费这些 stub。
        lenient().when(plugin.getConfigManager()).thenReturn(configManager);
        lenient().when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        lenient().when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        lenient().when(messages.getString(anyString())).thenReturn("");
        lenient().when(messages.getString(anyString(), anyString())).thenReturn("");
    }

    // ------------------------------------------------------------------
    // A. 布局容量与 WIN_SCORE 解耦（源码锚点）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("L11：两处比分循环的上界必须由 Math.min(WIN_SCORE, 槽位数组长度) 夹住")
    void scoreLoopsAreClampedByArrayLength() {
        String source = readGuiSource();

        // 原缺陷形状：`for (int i = 0; i < DuelRoom.WIN_SCORE; i++)` 直接下标取数组。
        // 只要这种写法还在，把 WIN_SCORE 调到 6 就必然 AIOOBE。
        assertFalse(source.contains("for (int i = 0; i < DuelRoom.WIN_SCORE; i++)"),
                "DuelGUI 里仍有 `for (int i = 0; i < DuelRoom.WIN_SCORE; i++)` 直接下标取 "
                        + "p1Slots/p2Slots 喵。\n"
                        + "这是 L11 的原始缺陷形状：循环上界取「胜负判定常量」，"
                        + "而下标数组长度是「布局容量」。WIN_SCORE 一旦 > 数组长度，"
                        + "渲染路径立刻 ArrayIndexOutOfBoundsException（开界面即崩，"
                        + "且只有打到第 N 分的玩家能撞上）。\n"
                        + "修法：上界改成 Math.min(DuelRoom.WIN_SCORE, slots.length)。");

        assertEquals(2, countOccurrences(source, "Math.min(DuelRoom.WIN_SCORE"),
                "应有两处 Math.min(DuelRoom.WIN_SCORE, xxxSlots.length) —— 左右两侧比分各一处喵。\n"
                        + "少一处说明有一侧仍与 WIN_SCORE 硬耦合，改常量时会在那一侧炸。");
    }

    @Test
    @DisplayName("L11：两个槽位数组的布局容量恰好是 5（改动必须显式）")
    void slotArraysHaveExpectedLayoutCapacity() {
        // 数组是方法局部变量，无法反射读取；改为钉住源码里的字面量。
        // 上面的用例保证「上界跟着数组长度走」，这里保证数组本身没被悄悄缩水
        // （缩水 = 少画一分，且不抛任何异常）。
        String source = readGuiSource();
        assertTrue(source.contains("{10, 11, 12, 19, 20}"),
                "左侧比分槽位表变了喵（期望 {10, 11, 12, 19, 20}）。\n"
                        + "槽位表是玩家可见布局，改动应同时更新 DuelGUI 类注释的 Layout 段"
                        + "与本断言，而不是悄悄换格子。");
        assertTrue(source.contains("{14, 15, 16, 23, 24}"),
                "右侧比分槽位表变了喵（期望 {14, 15, 16, 23, 24}）。同上。");
    }

    // ------------------------------------------------------------------
    // B. 真实渲染：正常容量与超容量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正常对局：左右各 5 个比分槽位都被「比分点」覆盖（不是铺底灰玻璃）")
    void allScoreSlotsAreFilledAtNormalWinScore() {
        // 给 P1 一分、P2 零分：这样左侧至少有 1 个 GREEN_WOOL、右侧全是不得分态的
        // GRAY_STAINED_GLASS。用「槽位被写过」只能证明循环跑了，证明不了「填的是比分点」。
        DuelRoom room = new DuelRoom(P1, "P1", P2, "P2");
        Word word = new Word("hello", "你好", "n.", "/həˈləʊ/", "Hello there.",
                null, null, "easy", 1);
        room.setCurrentWord(word);
        room.submitAnswer(P1, "hello");

        RenderResult result = render(room);

        // 铺底用的是 GRAY_STAINED_GLASS_PANE，而「未得分的比分点」用的是
        // GRAY_STAINED_GLASS（无 _PANE）—— 两者可区分，所以这里能用材质精确断言
        // 「5 个槽位都被比分点覆盖」，而不是「被铺底覆盖」（后者恒为真，是空断言）。
        int expected = Math.min(DuelRoom.WIN_SCORE, LEFT_SCORE_SLOTS.length);
        assertEquals(expected, result.countScoreDots(LEFT_SCORE_SLOTS),
                "左侧 5 个槽位必须全部是比分点（GREEN_WOOL 或 GRAY_STAINED_GLASS），"
                        + "而不是铺底玻璃 —— 后者只能证明 buildInventory 跑过");
        assertEquals(expected, result.countScoreDots(RIGHT_SCORE_SLOTS),
                "右侧 5 个槽位同上");
        assertEquals(1, result.countWithMaterial(LEFT_SCORE_SLOTS, Material.GREEN_WOOL),
                "P1 得 1 分 → 左侧第 1 个比分点必须是 GREEN_WOOL（得分态）");
        assertEquals(0, result.countWithMaterial(RIGHT_SCORE_SLOTS, Material.RED_WOOL),
                "P1 得分不影响 P2 的比分点 —— 右侧不应出现 RED_WOOL（P2 零分）");
        assertEquals(expected - 1,
                result.countWithMaterial(LEFT_SCORE_SLOTS, Material.GRAY_STAINED_GLASS),
                "左侧其余 4 个比分点应是「未得分」的 GRAY_STAINED_GLASS");
    }

    @Test
    @DisplayName("比分大于槽位容量（脏数据 / 未来调高 WIN_SCORE）：不抛异常，超出的分数不写越界槽")
    void scoreBeyondCapacityDoesNotThrow() {
        DuelRoom room = new DuelRoom(P1, "P1", P2, "P2");
        Word word = new Word("hello", "你好", "n.", "/həˈləʊ/", "Hello there.",
                null, null, "easy", 1);
        // 直接推到 7 分：正常规则下到 5 就结束，这里模拟「WIN_SCORE 被调大后打到 7」
        // 以及「比分被外部写脏成 7」两种形状。渲染侧唯一能保证的是不越界。
        for (int i = 0; i < 7; i++) {
            room.setCurrentWord(word);          // 每轮重置 lastCorrectAnswerer
            room.submitAnswer(P1, "hello");     // 答对 → score1++
        }
        assertEquals(7, room.getScore1(), "前置条件：比分确实已超过布局容量 5");

        RenderResult result = render(room);

        // 关键断言：渲染没崩、没有写出 0..26。
        assertTrue(result.writtenSlots().stream().allMatch(s -> s >= 0 && s < GUI_SIZE),
                "所有写入都必须落在 0..26 内，实际写入=" + result.writtenSlots());
        // 超出容量的 2 分只在头像 lore（player-score 的 {score}）里兜底，
        // 比分区仍是布局容量 5 个点 —— 这是「截断显示」的既定行为。
        assertEquals(LEFT_SCORE_SLOTS.length, result.countScoreDots(LEFT_SCORE_SLOTS),
                "布局容量是 5：第 6/7 分没有槽位可画（截断，不越界）");
        // P1 拿到 7 分 → 5 个槽位全部是得分态（GREEN_WOOL），不会出现第 6 格。
        assertEquals(LEFT_SCORE_SLOTS.length,
                result.countWithMaterial(LEFT_SCORE_SLOTS, Material.GREEN_WOOL),
                "P1 的 7 分应把全部 5 个槽位刷成得分态，且不溢出到第 6 格");
    }

    // ------------------------------------------------------------------
    // 渲染脚手架
    // ------------------------------------------------------------------

    /** 在「静态 Bukkit + ItemStack 构造」双拦截窗口内渲染一次 {@code DuelGUI}。 */
    private RenderResult render(DuelRoom room) {
        Map<Integer, ItemStack> slots = new HashMap<>();
        Set<Integer> writes = new HashSet<>();
        Map<ItemStack, Material> materials = new java.util.IdentityHashMap<>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class,
                     withSettings().defaultAnswer(invocation ->
                             "clone".equals(invocation.getMethod().getName())
                                     ? invocation.getMock()
                                     : Answers.RETURNS_DEFAULTS.answer(invocation)),
                     (mockItem, ctx) -> materials.put(mockItem, (Material) ctx.arguments().get(0)))) {
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        Inventory inv = mock(Inventory.class);
                        when(inv.getSize()).thenReturn(GUI_SIZE);
                        // 只 stub setItem：DuelGUI 是纯渲染路径，从不回读 getItem()，
                        // 铺了就是死 stub（STRICT_STUBS 会报红）。
                        doAnswer(i -> {
                            int slot = i.getArgument(0);
                            slots.put(slot, i.getArgument(1));
                            writes.add(slot);
                            return null;
                        }).when(inv).setItem(anyInt(), any());
                        return inv;
                    });

            new DuelGUI(plugin).open(player, room);
        }

        return new RenderResult(slots, writes, materials);
    }

    /**
     * 渲染结果快照：槽位 → 物品 + 全部写入的槽位集合 + 物品 → 构造时的材质。
     *
     * <p>「比分点是否真的画了」不能用 {@code slots.containsKey} 判：铺底循环会先把 27 格
     * 全铺上灰玻璃，比分点再覆盖其中 5 格。故本类用<b>材质</b>作判据 —— 已得分是
     * GREEN_WOOL / RED_WOOL，与铺底的 GRAY_STAINED_GLASS_PANE 可区分。
     */
    private record RenderResult(Map<Integer, ItemStack> slots,
                                Set<Integer> writtenSlots,
                                Map<ItemStack, Material> materials) {

        /** 指定槽位里「用给定材质画的」数量。 */
        int countWithMaterial(int[] candidateSlots, Material expected) {
            int count = 0;
            for (int slot : candidateSlots) {
                ItemStack item = slots.get(slot);
                if (item != null && materials.get(item) == expected) count++;
            }
            return count;
        }

        /**
         * 指定槽位里「确实是比分点」的数量。
         *
         * <p>判据必须排除铺底玻璃：{@code buildInventory} 先把 27 格铺成
         * {@code GRAY_STAINED_GLASS_PANE}，比分点再覆盖。比分点只可能是
         * {@code GREEN_WOOL}（已得分）、{@code RED_WOOL}（对方已得分）或
         * {@code GRAY_STAINED_GLASS}（无 _PANE，未得分）。三者都与铺底不同，
         * 因此这个计数能真正证明「循环画到了这一格」。
         */
        int countScoreDots(int[] candidateSlots) {
            int count = 0;
            for (int slot : candidateSlots) {
                ItemStack item = slots.get(slot);
                if (item == null) continue;
                Material m = materials.get(item);
                if (m == Material.GREEN_WOOL || m == Material.RED_WOOL
                        || m == Material.GRAY_STAINED_GLASS) {
                    count++;
                }
            }
            return count;
        }
    }

    /** 读取 {@code DuelGUI.java} 源码（基于 user.dir，与 B3WiringLintsTest 同口径）。 */
    private static String readGuiSource() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Paths.get(
                            System.getProperty("user.dir"), "src", "main", "java",
                            "com", "letslearnenglish", "minecraftplugin", "gui", "DuelGUI.java"),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取 DuelGUI.java 失败喵：" + e.getMessage(), e);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
