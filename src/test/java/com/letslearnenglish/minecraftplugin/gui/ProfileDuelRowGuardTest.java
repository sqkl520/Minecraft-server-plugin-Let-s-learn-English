package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * RT（红队）实测缺口：{@code ProfileGUI} 决斗战绩行（slot 16）的三层守卫 + 生产链接线。
 *
 * <p><b>为什么需要这个类</b>：{@code placeDuelRow} 的三道守卫都是「不满足就静默 return」，
 * 现有测试（{@code GuiOpenIdentityTest}）只覆盖 {@code open()} 交出的 holder，既不碰
 * slot 16，也不碰异步回填。于是下面三种回退全部是<b>静默</b>的：
 * <ul>
 *   <li>删掉 {@code if (!viewer.isOnline()) return;} —— 玩家已下线仍往一张关掉的界面写；</li>
 *   <li>把 {@code top.getHolder() != opened} 改成 {@code equals} —— 连点/来回切人时，
 *       同 key 同 payload 的两次打开「值相等」，A 的战绩被写进 B 的界面（隐私事故）；</li>
 *   <li>删掉 {@code open()} 里的 {@code scheduleDuelRow(...)} 调用 —— 主页永远停在「加载中」。</li>
 * </ul>
 *
 * <p><b>技术前提</b>：
 * <ul>
 *   <li>{@code new ItemStack(Material)} 在纯 JVM 里会抛 {@code ExceptionInInitializerError}
 *       （无 RegistryAccess），故整类用 {@link MockedConstruction} 拦截；构造回调里顺手
 *       记下 Material，用来区分「占位/回填」与 {@code fillEmpty} 的灰玻璃。</li>
 *   <li>{@code Inventory} 必须是<b>状态化</b> mock（{@code getItem}/{@code setItem} 背后挂
 *       一张 Map），否则 {@code GUIUtils.fillEmpty} 读到 null 会以为 27 格全空，
 *       「slot 16 写了几次」就不再是真实渲染次数。</li>
 *   <li>{@code fillEmpty} 对每个空槽调 {@code filler.clone()}；Mockito 默认答返回 null，
 *       会让「灰玻璃占位」这条断言失真。故给构造 mock 一个 defaultAnswer：{@code clone}
 *       返回自身，其余走 Mockito 默认。这是 <b>answer</b> 不是 <b>stubbing</b>，
 *       不参与 STRICT_STUBS 的多余检查。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProfileDuelRowGuardTest {

    /** 打开界面的人。 */
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    /** 战绩归属玩家（与 viewer 不同 → 走「他人主页」分支）。 */
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    /** 第三个人（守卫 3 的「归属已变」）。 */
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

    /** 与 {@code ProfileGUI} 的私有常量同值；刻意在这里重写一遍，值改了测试才会红。 */
    private static final int DUEL_SLOT = 16;
    /** 主页固定 27 格。 */
    private static final int PROFILE_SIZE = 27;

    private LetsLearnEnglish plugin;
    private Player viewer;
    private ConfigManager configManager;
    private FileConfiguration messages;
    private PlayerDataStore dataStore;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        viewer = mock(Player.class);
        configManager = mock(ConfigManager.class);
        messages = mock(FileConfiguration.class);
        dataStore = mock(PlayerDataStore.class);
    }

    /**
     * {@code open()} 全链路所需、且<b>两个</b>{@code open()} 用例都会消费的 stub。
     *
     * <p>{@code isAchievementsEnabled()} 显式 false：这是「成就墙模块关闭」的默认线上态，
     * 同时把 {@code getMainConfig()}（成就墙文案）与 slot 18 的渲染整段短路，让本类只测
     * 决斗行。
     */
    private void stubOpenBasics() {
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        when(messages.getString(anyString())).thenReturn("");
        when(messages.getString(anyString(), anyString())).thenReturn("");
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(dataStore.getPlayerName(TARGET_ID)).thenReturn("TargetPlayer");
        when(dataStore.getPlayerStats(TARGET_ID)).thenReturn(new PlayerDataStore.PlayerStats());
        when(dataStore.getProfileSettingsReadOnly(TARGET_ID))
                .thenReturn(new PlayerDataStore.ProfileSettings());
        when(plugin.getAchievementManager()).thenReturn(mock(AchievementManager.class));
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        when(configManager.isAchievementsEnabled()).thenReturn(false);
    }

    // ------------------------------------------------------------------
    // A. 三层守卫（反射直调 private placeDuelRow）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 A —— 三层守卫：删掉任何一道都必须变红")
    class ThreeGuards {

        /**
         * {@code placeDuelRow} 的实际签名（B3 之后、FIX-H2 之后）：
         * <pre>{@code
         * private void placeDuelRow(Player viewer, UUID target, PluginGuiHolder opened,
         *                           DatabaseManager.DuelStats stats,
         *                           Function<String, String> msg, boolean hideValues)
         * }</pre>
         * 注意：{@code streak} 形参已被 FIX-H2 从调用点摘除（{@code DuelStatsText.render}
         * 仍保留该参数，调用方传 0），因此这里<b>没有</b> {@code int streak}。
         *
         * @return 被测方法实际写入 slot 16 的物品（守卫拦下时为 null）
         */
        @SuppressWarnings("unchecked")
        private ItemStack invokePlaceDuelRow(Player who, UUID target, PluginGuiHolder opened,
                                             boolean hideValues) throws Exception {
            Method m = ProfileGUI.class.getDeclaredMethod("placeDuelRow",
                    Player.class, UUID.class, PluginGuiHolder.class,
                    DatabaseManager.DuelStats.class, Function.class, boolean.class);
            m.setAccessible(true);
            try {
                m.invoke(new ProfileGUI(plugin), who, target, opened,
                        new DatabaseManager.DuelStats(5, 2),
                        (Function<String, String>) key -> "", hideValues);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw (cause instanceof Exception ex) ? ex : e;
            }
            return null;
        }

        /**
         * 一张「已经打开了 {@code top}」的玩家界面。
         *
         * @param top  当前 top inventory
         * @param held 它报告的 holder（守卫 2 比对的实例）
         */
        private void stubOpenInventory(Inventory top, InventoryHolder held) {
            when(top.getHolder()).thenReturn(held);
            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(top);
            when(viewer.getOpenInventory()).thenReturn(view);
        }

        @Test
        @DisplayName("守卫 1：viewer 已下线 —— 连 getOpenInventory 都不该问，更不得写 slot 16")
        void offlineViewerIsRejectedBeforeAnyInventoryRead() throws Exception {
            when(viewer.isOnline()).thenReturn(false);
            PluginGuiHolder opened = PluginGuiHolder.of(GuiViewKey.PROFILE,
                    new PluginGuiHolder.GuiPayload.Target(TARGET_ID));
            // 刻意<b>不</b>铺 getOpenInventory 的 stub：守卫 1 在它之前，铺了就是多余 stub。
            // 正确实现下这里零交互；若守卫 1 被删，getOpenInventory 返回 null 会让后续
            // 守卫 2 的 view==null 分支接住 —— 所以断言必须落在「从未询问」上，而不是
            // 只断言「没写」。
            Inventory top = mock(Inventory.class);

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                invokePlaceDuelRow(viewer, TARGET_ID, opened, false);
            }

            verify(viewer, never()).getOpenInventory();
            verify(top, never()).setItem(eq(DUEL_SLOT), any());
        }

        @Test
        @DisplayName("守卫 2：同 key 同 payload 的另一次打开必须在实例层被拒（值相等不等于同一张界面）")
        void sameKeyDifferentHolderInstanceIsRejected() throws Exception {
            PluginGuiHolder opened = PluginGuiHolder.of(GuiViewKey.PROFILE,
                    new PluginGuiHolder.GuiPayload.Target(TARGET_ID));
            PluginGuiHolder reopened = PluginGuiHolder.of(GuiViewKey.PROFILE,
                    new PluginGuiHolder.GuiPayload.Target(TARGET_ID));
            // 前提：两者互等（值语义）但绝不是同一个对象 —— 正是「连点两次」「A→B→A」的形状
            assertEquals(opened, reopened, "前置条件：holder 是值语义，同 key 同 payload 的两份实例相等");
            assertNotSame(opened, reopened);

            // lenient：这条 stub 是「守卫 2/3/全通过」三组用例的<b>前置条件</b>（玩家在线），
            // 不是被测对象。若守卫 1 被删，本用例仍应只因「真的写了 slot 16」而红，而不是
            // 因为这条 stub 变成死代码 —— 否则变异信号的归因会被 STRICT_STUBS 的噪音污染。
            lenient().when(viewer.isOnline()).thenReturn(true);
            Inventory top = mock(Inventory.class);
            stubOpenInventory(top, reopened);

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                invokePlaceDuelRow(viewer, TARGET_ID, opened, false);
            }

            verify(top, never()).setItem(eq(DUEL_SLOT), any());
        }

        @Test
        @DisplayName("守卫 3：holder 实例对得上、但归属已换成另一个人 —— 仍然不得写")
        void holderWithDifferentTargetIsRejected() throws Exception {
            PluginGuiHolder opened = PluginGuiHolder.of(GuiViewKey.PROFILE,
                    new PluginGuiHolder.GuiPayload.Target(OTHER_ID));
            lenient().when(viewer.isOnline()).thenReturn(true);
            Inventory top = mock(Inventory.class);
            stubOpenInventory(top, opened);

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                // 传参 target=TARGET_ID，payload 却是 OTHER_ID → 第三道守卫
                invokePlaceDuelRow(viewer, TARGET_ID, opened, false);
            }

            verify(top, never()).setItem(eq(DUEL_SLOT), any());
        }

        @Test
        @DisplayName("三重全通过：slot 16 必须被写恰好 1 次，且物品是 IRON_SWORD")
        void fullyValidatedCallWritesTheRowOnce() throws Exception {
            PluginGuiHolder opened = PluginGuiHolder.of(GuiViewKey.PROFILE,
                    new PluginGuiHolder.GuiPayload.Target(TARGET_ID));
            lenient().when(viewer.isOnline()).thenReturn(true);
            Inventory top = mock(Inventory.class);
            stubOpenInventory(top, opened);

            Map<ItemStack, Material> materials = new IdentityHashMap<>();
            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class,
                    (mockItem, ctx) -> materials.put(mockItem, (Material) ctx.arguments().get(0)))) {
                invokePlaceDuelRow(viewer, TARGET_ID, opened, false);
            }

            ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
            verify(top, times(1)).setItem(eq(DUEL_SLOT), captor.capture());
            ItemStack written = captor.getValue();
            assertNotNull(written, "守卫全通过时必须写入战绩物品，而不是 null");
            assertEquals(Material.IRON_SWORD, materials.get(written),
                    "决斗行物品材质必须是 IRON_SWORD（换材质 = 换了展示语义）");
        }
    }

    // ------------------------------------------------------------------
    // B. open() 级：守卫确实挂在生产链上
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 B —— open() 生产链：slot 16 的占位/回填/fillEmpty 三层顺序")
    class OpenPathWiring {

        @Test
        @DisplayName("duel.enabled=true：slot 16 被写 2 次（占位 → 异步回填），两次都是 IRON_SWORD")
        void duelEnabledPlaceholderThenBackfill() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(true);

            OpenCapture capture = captureProfileOpen(true);

            List<ItemStack> slot16 = capture.writes().getOrDefault(DUEL_SLOT, List.of());
            assertEquals(2, slot16.size(),
                    "slot 16 应被写 2 次（主线程「加载中」占位 + 异步回填）；"
                            + "少一次说明占位或整条回填链缺失");
            assertNotSame(slot16.get(0), slot16.get(1), "占位与回填必须是两个物品实例");
            assertEquals(Material.IRON_SWORD, capture.materialOf(slot16.get(0)),
                    "占位物品应是决斗行样式（IRON_SWORD），不是空槽或灰玻璃");
            assertEquals(Material.IRON_SWORD, capture.materialOf(slot16.get(1)),
                    "回填物品应是决斗行样式（IRON_SWORD）");
            assertEquals(TARGET_ID, capture.queriedTarget(),
                    "异步取数必须按被查看者 UUID 查，而不是打开者");
        }

        @Test
        @DisplayName("duel.enabled=false：slot 16 只由 fillEmpty 垫灰玻璃（1 次），且调度器零交互")
        void duelDisabledOnlyFillerAndNoDbRead() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(false);

            OpenCapture capture = captureProfileOpen(false);

            List<ItemStack> slot16 = capture.writes().getOrDefault(DUEL_SLOT, List.of());
            assertEquals(1, slot16.size(),
                    "模块关闭时 slot 16 只能由 fillEmpty 写 1 次；写 2 次 = 守卫没生效，"
                            + "写 0 次 = 裸空槽（shift-click 会塞进玩家物品，关界面即丢）");
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, capture.materialOf(slot16.get(0)),
                    "模块关闭时的 slot 16 必须是 fillEmpty 的灰玻璃");
            verifyNoInteractions(capture.scheduler());
            assertEquals(null, capture.queriedTarget(), "模块关闭时不得发起那次异步 DB 读（白烧 IO）");
        }
    }

    /**
     * {@code open()} 执行结果快照。
     *
     * @param inventory  交给 {@code createInventory} 的（状态化 mock）界面
     * @param writes     每个槽位的写入序列（含同一槽位被多次写）
     * @param materials  ItemStack mock → 构造时的 Material
     * @param scheduler  注入的调度器 mock（断言「零交互」用）
     * @param queriedTarget 异步段实际向 DB 查询的战绩归属；未查为 null
     */
    private record OpenCapture(Inventory inventory, Map<Integer, List<ItemStack>> writes,
                               Map<ItemStack, Material> materials, BukkitScheduler scheduler,
                               UUID queriedTarget) {

        Material materialOf(ItemStack item) {
            return item == null ? null : materials.get(item);
        }
    }

    /** 可变容器：{@code OpenCapture} 是 record（不可变），查询目标需要跨 lambda 回填。 */
    private static final class QueryProbe {
        private UUID value;
    }

    /**
     * 在「静态 Bukkit + ItemStack 构造」双拦截窗口内执行
     * {@code new ProfileGUI(plugin).open(viewer, TARGET_ID)}。
     *
     * <p>调度器 mock 的 {@code runTaskAsynchronously}/{@code runTask} 都把任务<b>就地同步</b>
     * 执行 —— 于是整条「异步取数 → 回主线程回填」链在测试里一次展开，无需 sleep。
     *
     * @param duelEnabled 是否开启决斗模块；决定是否铺「回填所需」的 stub
     * @return 界面写入快照
     */
    private OpenCapture captureProfileOpen(boolean duelEnabled) {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AtomicReference<Inventory> createdInventory = new AtomicReference<>();
        AtomicReference<InventoryHolder> createdHolder = new AtomicReference<>();
        Map<Integer, List<ItemStack>> writes = new HashMap<>();
        Map<ItemStack, Material> materials = new IdentityHashMap<>();
        QueryProbe probe = new QueryProbe();

        if (duelEnabled) {
            DatabaseManager databaseManager = mock(DatabaseManager.class);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getDatabaseManager()).thenReturn(databaseManager);
            when(databaseManager.getDuelStats(any(UUID.class))).thenAnswer(inv -> {
                probe.value = inv.getArgument(0);
                return new DatabaseManager.DuelStats(4, 1);
            });
            // lenient：这是「回填链路成立」的前置条件，不是被测对象。若 placeDuelRow 的
            // 在线守卫被删，本用例应当只因「slot 16 被写了第 3 次」而红 —— 断言直接指向
            // 缺陷本身，而不是被 STRICT_STUBS 的「stub 变死代码」噪音抢先。
            lenient().when(viewer.isOnline()).thenReturn(true);
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
                inv.<Runnable>getArgument(1).run();
                return null;
            });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
                inv.<Runnable>getArgument(1).run();
                return null;
            });
        }

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class,
                     withSettings().defaultAnswer(invocation -> "clone".equals(invocation.getMethod().getName())
                             ? invocation.getMock()
                             : Answers.RETURNS_DEFAULTS.answer(invocation)),
                     (mockItem, ctx) -> materials.put(mockItem, (Material) ctx.arguments().get(0)))) {

            if (duelEnabled) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            }
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        Inventory inv = mock(Inventory.class);
                        createdInventory.set(inv);
                        createdHolder.set(invocation.getArgument(0));
                        Map<Integer, ItemStack> slots = new HashMap<>();
                        when(inv.getSize()).thenReturn(PROFILE_SIZE);
                        when(inv.getItem(anyInt()))
                                .thenAnswer(i -> slots.get((Integer) i.getArgument(0)));
                        doAnswer(i -> {
                            int slot = i.getArgument(0);
                            ItemStack item = i.getArgument(1);
                            slots.put(slot, item);
                            writes.computeIfAbsent(slot, k -> new ArrayList<>()).add(item);
                            return null;
                        }).when(inv).setItem(anyInt(), any());
                        if (duelEnabled) {
                            // 只有回填链会读 holder（placeDuelRow 守卫 2）；模块关闭时
                            // 铺了就是 STRICT_STUBS 下的多余 stub。
                            when(inv.getHolder()).thenAnswer(i -> createdHolder.get());
                            InventoryView view = mock(InventoryView.class);
                            when(view.getTopInventory()).thenAnswer(i -> createdInventory.get());
                            when(viewer.getOpenInventory()).thenReturn(view);
                        }
                        return inv;
                    });

            new ProfileGUI(plugin).open(viewer, TARGET_ID);
        }

        verify(viewer, times(1)).openInventory(same(createdInventory.get()));
        return new OpenCapture(createdInventory.get(), writes, materials, scheduler, probe.value);
    }
}
