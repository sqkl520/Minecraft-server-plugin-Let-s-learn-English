package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * RT（红队）实测缺口：{@code ProfileGUI} 决斗战绩行（slot 16）的三层守卫 + 生产链接线。
 *
 * <p><b>C-P2-6（红队 C）的重大订正 —— 本类曾是「验证死代码」的假绿</b>：
 * 原实现经反射直调 {@code ProfileGUI#placeDuelRow}。但自 L21 起，生产回填路径
 * （{@code placeProfileData}）是直接 {@code top.setItem(DUEL_SLOT, ...)}，<b>从不调用
 * {@code placeDuelRow}</b> —— 那个方法只剩测试在调。于是把 {@code placeProfileData}
 * 开头的 {@code stillViewing(...)} 整行删掉，本类十几条守卫测试<b>依然全绿</b>：
 * 它们验证的是一个生产永不执行的方法。
 *
 * <p>现在：
 * <ul>
 *   <li>生产实现把渲染收进 {@code applyDuelRow(top, streak, duel, hideResults, msg)}，
 *       {@code placeDuelRow} 已删除；</li>
 *   <li>本类的守卫测试全部改为<b>经 {@code open()} 全链路</b>驱动：真实调用
 *       {@code open → scheduleProfileBackfill → placeProfileData → stillViewing → applyDuelRow}，
 *       断言落在真实生产方法的真实写入上。删掉 {@code placeProfileData} 的
 *       {@code stillViewing} 或把 {@code getHolder() != opened} 改成 {@code equals}，
 *       这里的用例会立刻红。</li>
 * </ul>
 *
 * <p><b>覆盖的三种回退</b>（全部曾是静默的）：
 * <ul>
 *   <li>删掉守卫 1（{@code viewer.isOnline()}）—— 玩家已下线仍往一张关掉的界面写；</li>
 *   <li>把 {@code top.getHolder() != opened} 改成 {@code equals} —— 连点/来回切人时，
 *       同 key 同 payload 的两次打开「值相等」，A 的战绩被写进 B 的界面（隐私事故）；</li>
 *   <li>删掉 {@code open()} 里的异步回填派发 —— 主页永远停在「加载中」。</li>
 * </ul>
 * L3 起拒绝路径还会留下日志（fine / warning 分级），组 C 专门钉这部分。
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
    /** L3：守卫的日志出口。{@code plugin.getLogger()} 在真插件上恒非 null。 */
    private java.util.logging.Logger logger;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        viewer = mock(Player.class);
        configManager = mock(ConfigManager.class);
        messages = mock(FileConfiguration.class);
        dataStore = mock(PlayerDataStore.class);
        logger = mock(java.util.logging.Logger.class);
        // lenient：组 B（open() 生产链）里 duel 关闭的用例根本不进任何守卫，
        // 这条 stub 会变成死代码 —— 它是全类前置条件，不该由 STRICT_STUBS 报红。
        lenient().when(plugin.getLogger()).thenReturn(logger);
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
        when(dataStore.getProfileSettingsReadOnly(TARGET_ID))
                .thenReturn(new PlayerDataStore.ProfileSettings());
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        when(configManager.isAchievementsEnabled()).thenReturn(false);
        // 守卫 1 的默认线上态：在线。放这里（而不是各夹具里）是为了让用例能<b>后</b>
        // 覆盖成 false —— Mockito 后写的 stub 生效，而夹具若也铺 true 就会把用例的
        // 「离线」覆盖回去（本类曾因此让守卫 1 的用例失去判别力）。
        lenient().when(viewer.isOnline()).thenReturn(true);
        // 注意：{@code getPlayerStats} 的 stub 不在这里 —— L21 起它只在异步段被调用，
        // 而 duel 关闭的用例根本不派发异步任务。铺在公共处会变成死代码
        // （STRICT_STUBS 直接红），下放到 {@link #captureProfileOpen}。
    }

    // ------------------------------------------------------------------
    // A. 三层守卫 —— 经 open() 全链路（C-P2-6：不再反射直调已删除的方法）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 A —— 三层守卫：删掉任何一道都必须变红（经 open() 全链路）")
    class ThreeGuards {

        @Test
        @DisplayName("守卫 1：回填时 viewer 已下线 —— slot 16 只有占位（1 次），绝不回填")
        void offlineViewerIsRejectedBeforeAnyInventoryRead() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(true);

            // 关键形状：open() 本身不读 isOnline()（只用于回填守卫），所以这里直接给
            // false 等价于「异步段执行时玩家已下线」。stubOpenBasics 里的 lenient true
            // 会被本行覆盖（Mockito 后写者生效）。
            when(viewer.isOnline()).thenReturn(false);

            OpenCapture capture = captureProfileOpen(true);

            List<ItemStack> slot16 = capture.writes().getOrDefault(DUEL_SLOT, List.of());
            assertEquals(1, slot16.size(),
                    "回填时玩家已下线 → 守卫 1 必须拒绝执行：slot 16 只该有主线程那一次"
                            + "「加载中」占位。写成 2 次说明守卫 1 失效，往一张已关闭的界面写了数据。\n"
                            + "（C-P2-6 起本断言驱动的是生产方法 placeProfileData，而不是死方法"
                            + " placeDuelRow —— 删掉 placeProfileData 开头的 stillViewing 会立刻红。）");
            assertEquals(Material.IRON_SWORD, capture.materialOf(slot16.get(0)),
                    "占位物品应是决斗行样式（IRON_SWORD）");
            // 占位之后 fillEmpty 不会覆盖已非空的槽（它只填空槽），所以 1 次就是真相。
            verify(viewer, never()).getOpenInventory();
        }

        @Test
        @DisplayName("守卫 2：同 key 同 payload 的另一次打开必须在实例层被拒（值相等 != 同一张界面）")
        void sameKeyDifferentHolderInstanceIsRejected() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(true);

            // 回填时玩家看的是「另一张同 key 同 payload 的主页」：holder 值相等但实例不同。
            // 若守卫 2 用 equals，这里 A 的战绩会被写进 B 的界面。
            captureProfileOpenWithSwappedGui(true);

            // 判别力：回填一旦通过守卫就会写进「替身界面」（玩家当前真正看着的那张）。
            // 正确实现下一次写入都没有；若守卫被削弱（改成 equals / 删掉通道），
            // 这里会出现写入 —— 串台事故的直接证据。
            List<ItemStack> slot16 = getSwappedWrites().getOrDefault(DUEL_SLOT, List.of());
            assertTrue(slot16.isEmpty(),
                    "holder 实例不同（同 key 同 payload 的另一次打开）→ 守卫 2 必须拒绝："
                            + "替身界面的 slot 16 不得被写。实际写了 " + slot16.size()
                            + " 次 —— 说明用的是 equals 而不是引用比较，"
                            + "A 的战绩被写进了「值相等」的另一张界面（跨玩家串台）。");
        }

        @Test
        @DisplayName("守卫 2b：界面已被换成别人的主页 —— 不得回填")
        void anotherTargetsGuiIsRejected() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(true);

            captureProfileOpenWithSwappedGui(true, OTHER_ID);

            List<ItemStack> slot16 = getSwappedWrites().getOrDefault(DUEL_SLOT, List.of());
            assertTrue(slot16.isEmpty(),
                    "界面已换成别人的主页 → 不得回填：替身界面的 slot 16 不得被写。"
                            + "实际写了 " + slot16.size() + " 次 = A 的战绩进了 B 的界面（隐私事故）。");
        }

        @Test
        @DisplayName("三重全通过：slot 16 必须被写恰好 2 次（占位 + 回填），且都是 IRON_SWORD")
        void fullyValidatedCallWritesTheRowTwice() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(true);

            OpenCapture capture = captureProfileOpen(true);

            ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
            verify(capture.inventory(), times(2)).setItem(eq(DUEL_SLOT), captor.capture());
            List<ItemStack> written = captor.getAllValues();
            assertEquals(2, written.size());
            assertNotNull(written.get(1), "守卫全通过时必须写入战绩物品，而不是 null");
            assertNotSame(written.get(0), written.get(1), "占位与回填必须是两个物品实例");
            assertEquals(Material.IRON_SWORD, capture.materialOf(written.get(0)),
                    "占位物品应是决斗行样式（IRON_SWORD）");
            assertEquals(Material.IRON_SWORD, capture.materialOf(written.get(1)),
                    "决斗行物品材质必须是 IRON_SWORD（换材质 = 换了展示语义）");
        }
    }

    // ------------------------------------------------------------------
    // C. L3：守卫不再静默 —— 拒绝路径必须留下可诊断日志
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 C —— L3：三条拒绝路径各自留下日志（正常路径不刷 WARNING）")
    class GuardLogging {

        @Test
        @DisplayName("守卫 1（离线）：打 fine，不打 warning —— 关界面/下线是最常见的正常路径")
        void offlineViewerLogsAtFine() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(true);
            when(viewer.isOnline()).thenReturn(false);

            captureProfileOpen(true);

            verify(logger).fine(anyString());
            verify(logger, never()).warning(anyString());
        }

        @Test
        @DisplayName("守卫 2（同一张主页被重开）：fine 级，不是异常")
        void reopenedSameProfileLogsAtFine() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(true);

            captureProfileOpenWithSwappedGui(true);

            verify(logger).fine(anyString());
            verify(logger, never()).warning(anyString());
        }

        @Test
        @DisplayName("守卫 2（界面已换成别人的主页）：WARNING —— 这正是跨玩家串台的形状")
        void anotherTargetsProfileLogsAtWarning() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(true);

            captureProfileOpenWithSwappedGui(true, OTHER_ID);

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(logger).warning(message.capture());
            assertTrue(message.getValue().contains(TARGET_ID.toString()),
                    "日志必须带上期望的目标，否则排查时无法判断串了谁：" + message.getValue());
            assertTrue(message.getValue().contains(OTHER_ID.toString()),
                    "日志必须带上实际界面归属：" + message.getValue());
        }

        @Test
        @DisplayName("三重全通过：不得留下任何 WARNING（否则正常回填会刷满日志）")
        void successfulBackfillDoesNotWarn() {
            stubOpenBasics();
            when(configManager.isDuelEnabled()).thenReturn(true);

            captureProfileOpen(true);

            verify(logger, never()).warning(anyString());
        }

        @Test
        @DisplayName("logger 不可用（纯逻辑环境）不得让守卫抛 NPE")
        void nullLoggerDoesNotBreakGuards() {
            // 少数纯逻辑测试会给 plugin.getLogger() 留 null；守卫的价值是「永不抛」。
            // 本用例经 open() 全链路触发守卫 1 的拒绝路径（logFine），验证那里对
            // null logger 做了判空。
            stubOpenBasics();
            when(plugin.getLogger()).thenReturn(null);
            when(configManager.isDuelEnabled()).thenReturn(true);
            when(viewer.isOnline()).thenReturn(false);

            OpenCapture capture = captureProfileOpen(true);

            // 无异常即通过（assertDoesNotThrow 由调用本身承担）；顺带确认没回填。
            assertEquals(1, capture.writes().getOrDefault(DUEL_SLOT, List.of()).size(),
                    "logger 为 null 时守卫仍必须正常拒绝（slot 16 只有占位）");
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
            assertTrue(capture.streakQueried(),
                    "L4：连胜必须与战绩同批取（getDuelWinStreak），否则 streak 永远显示不出来");
        }

        @Test
        @DisplayName("duel.enabled=false：slot 16 只由 fillEmpty 垫灰玻璃（1 次），且不查任何决斗表")
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
            // L21 起统计/金币/成就仍会派发一个异步任务（同一个 scheduleProfileBackfill），
            // 因此这里<b>不能</b>再断言「调度器零交互」—— 那是 L21 之前的形状。
            // 真正要钉住的是「决斗那两表一次都没查」：模块关着还查 = 纯白烧 IO。
            assertEquals(null, capture.queriedTarget(), "模块关闭时不得查决斗战绩表（白烧 IO）");
            assertFalse(capture.streakQueried(), "模块关闭时不得查连胜");
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
                               UUID queriedTarget, boolean streakQueried) {

        Material materialOf(ItemStack item) {
            return item == null ? null : materials.get(item);
        }
    }
    /** 可变容器：{@code OpenCapture} 是 record（不可变），查询目标需要跨 lambda 回填。 */
    private static final class QueryProbe {
        private UUID value;
        /** L4：连胜那次查询是否真的发生（与战绩查询分开记，便于断言"同批取"）。 */
        private boolean streakQueried;
    }

    // ------------------------------------------------------------------
    // 「界面已换」场景的共用夹具
    // ------------------------------------------------------------------

    /** 最近一次 {@link #captureProfileOpenWithSwappedGui} 的替身界面写入表。 */
    private Map<Integer, List<ItemStack>> swappedWrites;

    private Map<Integer, List<ItemStack>> getSwappedWrites() {
        return swappedWrites == null ? Map.of() : swappedWrites;
    }

    /** 回填时界面已换成「同一个目标的另一张主页」（同 key 同 payload，不同实例）。 */
    private void captureProfileOpenWithSwappedGui(boolean duelEnabled) {
        captureProfileOpenWithSwappedGui(duelEnabled, TARGET_ID);
    }

    /**
     * 在 {@code open()} 全链路里让回填时玩家看的是<b>另一张</b>界面。
     *
     * <p>{@code swappedTarget} 决定那张界面的 holder payload：传 {@code TARGET_ID} 就是
     * 「同一张主页被重开」（守卫 2 正常拒绝、fine 日志）；传别的 UUID 就是
     * 「界面被换成了别人的主页」（守卫 2 WARNING）。
     */
    private void captureProfileOpenWithSwappedGui(boolean duelEnabled, UUID swappedTarget) {
        assertTrue(duelEnabled, "本夹具只用于 duel.enabled=true 的守卫场景");
        when(dataStore.getPlayerStats(TARGET_ID))
                .thenReturn(new PlayerDataStore.PlayerStats());
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(databaseManager.getDuelStats(any(UUID.class)))
                .thenReturn(new DatabaseManager.DuelStats(4, 1));
        when(databaseManager.getDuelWinStreak(any(UUID.class))).thenReturn(0);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });

        // open() 交出的那张界面（回填<b>不该</b>写它）；写入表与替身界面各自独立，
        // 这样「写在哪儿」才是可判定的。
        Map<Integer, List<ItemStack>> ownWrites = new HashMap<>();
        // 替身界面（回填时玩家真正看着的那张）。
        Map<Integer, List<ItemStack>> swapped = new HashMap<>();
        this.swappedWrites = swapped;

        Inventory swappedTop = mock(Inventory.class);
        Map<Integer, ItemStack> swappedSlots = new HashMap<>();
        // lenient：守卫 1 / 2 正常工作时这些交互根本不会发生（这正是被测目标）。
        // 一旦守卫被削弱，它们就会被消费并留下写入证据 —— 所以「未消费」是正确结果，
        // 不该由 STRICT_STUBS 判成多余代码。
        lenient().when(swappedTop.getSize()).thenReturn(PROFILE_SIZE);
        lenient().when(swappedTop.getItem(anyInt()))
                .thenAnswer(i -> swappedSlots.get((Integer) i.getArgument(0)));
        lenient().doAnswer(i -> {
            int slot = i.getArgument(0);
            ItemStack item = i.getArgument(1);
            swappedSlots.put(slot, item);
            swapped.computeIfAbsent(slot, k -> new ArrayList<>()).add(item);
            return null;
        }).when(swappedTop).setItem(anyInt(), any());
        // 替身界面的 holder：与 open() 里那张同 key 同 payload，但<b>实例不同</b>
        // （或 payload 换了人 —— 那正是「界面被换成别人的主页」）。
        InventoryHolder swappedHolder = com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder
                .of(com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey.PROFILE,
                        new com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder
                                .GuiPayload.Target(swappedTarget));
        lenient().when(swappedTop.getHolder()).thenReturn(swappedHolder);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(swappedTop);
        when(viewer.getOpenInventory()).thenReturn(view);

        java.util.concurrent.atomic.AtomicReference<Inventory> created =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> ignored = itemStackMock()) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        Inventory inv = statefulInventory(ownWrites, invocation.getArgument(0));
                        created.set(inv);
                        return inv;
                    });
            new ProfileGUI(plugin).open(viewer, TARGET_ID);
        }

        // 判别力自检：占位确实写进了 open() 交出的那张界面（证明链路真的跑过），
        // 而守卫拒掉了回填 —— 替身界面一次都没被写。
        assertTrue(ownWrites.getOrDefault(DUEL_SLOT, List.of()).size() == 1,
                "前置条件：主线程占位应写进 open() 交出的界面且只写 1 次（实际="
                        + ownWrites.getOrDefault(DUEL_SLOT, List.of()).size() + " → 夹具失效）");
        assertTrue(swapped.isEmpty(),
                "回填不得写进替身界面：守卫必须拦住（实际写了 " + swapped.size() + " 个槽位："
                        + swapped.keySet() + "）");
    }

    // ------------------------------------------------------------------
    // 共用夹具
    // ------------------------------------------------------------------

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
        java.util.concurrent.atomic.AtomicReference<Inventory> createdInventory =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<InventoryHolder> createdHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        Map<Integer, List<ItemStack>> writes = new HashMap<>();
        Map<ItemStack, Material> materials = new IdentityHashMap<>();
        QueryProbe probe = new QueryProbe();

        if (duelEnabled) {
            // L21：统计也走同一个异步任务（scheduleProfileBackfill），所以这两个 stub
            // 只在「真的派发了回填」的用例里才存在。
            when(dataStore.getPlayerStats(TARGET_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());
            DatabaseManager databaseManager = mock(DatabaseManager.class);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getDatabaseManager()).thenReturn(databaseManager);
            when(databaseManager.getDuelStats(any(UUID.class))).thenAnswer(inv -> {
                probe.value = inv.getArgument(0);
                return new DatabaseManager.DuelStats(4, 1);
            });
            // L4：连胜与战绩同批取。显式给 0 —— 本类只关心「有没有按时查、查的是谁」。
            when(databaseManager.getDuelWinStreak(any(UUID.class))).thenAnswer(inv -> {
                probe.streakQueried = true;
                return 0;
            });
            // lenient：这是「回填链路成立」的前置条件，不是被测对象。若守卫 1 被删，
            // 本用例应当只因「slot 16 被写了第 3 次」而红 —— 断言直接指向缺陷本身，
            // 而不是被 STRICT_STUBS 的「stub 变死代码」噪音抢先。
            // 注意：<b>不要</b>在这里再 stub isOnline(true)。夹具在用例之后执行，
            // 后写的 stub 会覆盖用例的 false，守卫 1 的用例当场失去判别力。
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
             MockedConstruction<ItemStack> ignored = itemStackMock(
                     (mockItem, ctx) -> materials.put(mockItem, (Material) ctx.arguments().get(0)))) {

            if (duelEnabled) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            }
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        createdHolder.set(invocation.getArgument(0));
                        Inventory inv = statefulInventory(writes, invocation.getArgument(0));
                        createdInventory.set(inv);
                        if (duelEnabled) {
                            // 只有回填链会读 holder（stillViewing 守卫 2）；模块关闭时
                            // 铺了就是 STRICT_STUBS 下的多余 stub。
                            // lenient：守卫 1 / 守卫 2 的拒绝用例里回填在拿到界面前就
                            // return 了，这两条 stub 会变成「未消费」—— 它们是回填链的
                            // 前置条件，不是被测对象，不该由 STRICT_STUBS 报红。
                            InventoryView view = mock(InventoryView.class);
                            lenient().when(view.getTopInventory()).thenAnswer(i -> createdInventory.get());
                            lenient().when(viewer.getOpenInventory()).thenReturn(view);
                        }
                        return inv;
                    });

            new ProfileGUI(plugin).open(viewer, TARGET_ID);
        }

        verify(viewer, times(1)).openInventory(same(createdInventory.get()));
        return new OpenCapture(createdInventory.get(), writes, materials, scheduler,
                probe.value, probe.streakQueried);
    }

    /**
     * 状态化 Inventory mock：{@code getItem}/{@code setItem} 背后挂一张 Map，且每次写入
     * 都记进 {@code writes}（同一槽位保留写入序列，用来数「占位 / 回填 / fillEmpty」）。
     *
     * <p>{@code getHolder()} 一律返回 {@code holder} —— 生产里 {@code createInventory}
     * 挂的就是同一个实例，守卫 2 的引用比较靠它成立。
     */
    private static Inventory statefulInventory(Map<Integer, List<ItemStack>> writes,
                                               InventoryHolder holder) {
        Inventory inv = mock(Inventory.class);
        Map<Integer, ItemStack> slots = new HashMap<>();
        when(inv.getSize()).thenReturn(PROFILE_SIZE);
        when(inv.getItem(anyInt())).thenAnswer(i -> slots.get((Integer) i.getArgument(0)));
        doAnswer(i -> {
            int slot = i.getArgument(0);
            ItemStack item = i.getArgument(1);
            slots.put(slot, item);
            writes.computeIfAbsent(slot, k -> new ArrayList<>()).add(item);
            return null;
        }).when(inv).setItem(anyInt(), any());
        lenient().when(inv.getHolder()).thenReturn(holder);
        return inv;
    }

    private static MockedConstruction<ItemStack> itemStackMock() {
        return itemStackMock(null);
    }

    /**
     * ItemStack 构造拦截：纯 JVM 无 RegistryAccess，且 {@code clone()} 必须返回自身。
     *
     * <p>{@code onConstruct} 可选：用来记录「每个 ItemStack 是用什么 Material 造的」，
     * 从而区分占位 / 回填（IRON_SWORD）与 {@code fillEmpty} 的灰玻璃。
     */
    private static MockedConstruction<ItemStack> itemStackMock(
            org.mockito.MockedConstruction.MockInitializer<ItemStack> onConstruct) {
        if (onConstruct == null) {
            return mockConstruction(ItemStack.class,
                    withSettings().defaultAnswer(invocation ->
                            "clone".equals(invocation.getMethod().getName())
                                    ? invocation.getMock()
                                    : Answers.RETURNS_DEFAULTS.answer(invocation)));
        }
        return mockConstruction(ItemStack.class,
                withSettings().defaultAnswer(invocation ->
                        "clone".equals(invocation.getMethod().getName())
                                ? invocation.getMock()
                                : Answers.RETURNS_DEFAULTS.answer(invocation)),
                onConstruct);
    }
}
