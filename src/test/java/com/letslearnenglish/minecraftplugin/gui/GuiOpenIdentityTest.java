package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.DifficultWord;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 2：GUI 打开路径的「结构身份」端到端验收（R5-0 收口）。
 *
 * <p>阶段 1 的 lint 只能证明「源码里写了 {@code PluginGuiHolder.of(...)}」，证明不了
 * 「这个 holder 真的被交给了 {@code Bukkit.createInventory}」。本类沿真实调用链
 * {@code XxxGUI.open(...) → Bukkit.createInventory(holder, size, title) → player.openInventory}
 * 走一遍，在 {@code createInventory} 的实参上抓 holder，从而把「GUI 类忘了传 holder」
 * 「传了 holder 但 key 写错」「payload 丢了页码/目标/模式」这三类回退全部变成红灯 ——
 * 这些回退正是标题时代 {@code extractPageFromTitle} / {@code extractTargetIdFromTitle}
 * 静默失效的根源。
 *
 * <p><b>为什么必须 mock 静态与构造</b>：
 * <ul>
 *   <li>{@code Bukkit.createInventory(...)} 在纯 JVM 里没有服务端实现，只能
 *       {@link MockedStatic} 拦截；顺便也让 {@code Bukkit.getPlayer/getOfflinePlayer}
 *       返回 null（正是我们要的「玩家不在线」分支）。</li>
 *   <li>{@code new ItemStack(Material.X)} 会触发 {@code ExceptionInInitializerError}
 *       （{@code IllegalStateException: No RegistryAccess implementation found}），
 *       因此整类用 {@link MockedConstruction} 拦截构造；构造出来的 mock 的
 *       {@code getItemMeta()} 返回 null，各 GUI 里 {@code if (meta != null)} 分支
 *       自动跳过，正好不需要 ItemMeta 参与本次断言。</li>
 *   <li>{@code ChatColor.translateAlternateColorCodes} 不 mock —— 它是纯字符串
 *       替换，无注册表依赖，保持真实实现可顺带覆盖 {@code messages} 为 mock 时的空串路径。</li>
 * </ul>
 *
 * <p><b>严格程度（FIX-5 裁决：方案 A —— 拆 stub + STRICT_STUBS）</b>：原类用
 * {@code @MockitoSettings(LENIENT)} 一把糊住，红队实测改回 STRICT_STUBS 后 8 条用例
 * <b>全部</b>因 {@code UnnecessaryStubbingException} 变红（16 处投诉）—— 说明公共
 * {@code setUp} 里大半 stub 从来没被任何一个用例消费。LENIENT 的直接后果是：
 * 某条分支从此不再被任何用例走到，也<b>不会有任何信号</b>。
 *
 * <p>因此这里拆成三层：
 * <ul>
 *   <li>{@link #setUp()} 只保留<b>每个</b>用例都必然消费的 4 条（配置门面 + 语言 + 文案）；</li>
 *   <li>每个 {@code @Nested} 类的 {@code @BeforeEach} 只铺<b>本组</b>共用的 stub；</li>
 *   <li>用例私有 stub 写在用例体内，一眼可见它服务哪条分支。</li>
 * </ul>
 * 于是"某条 stub 变成死代码"会在下一次运行时立刻以 {@code UnnecessaryStubbingException}
 * 暴露，而不是被 LENIENT 静默吞掉。
 */
@ExtendWith(MockitoExtension.class)
class GuiOpenIdentityTest {

    /** 视角玩家 UUID（打开界面的人）。 */
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    /** 被查看玩家 UUID（必须与 {@link #VIEWER_ID} 不同，才能走到「他人主页」分支）。 */
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    /** 错题本每页容量 = {@code WrongBookGUI.WORD_SLOTS.length}（42），第 2 页需要 ≥43 条。 */
    private static final int WRONG_BOOK_ITEMS_PER_PAGE = WrongBookGUI.WORD_SLOTS.length;

    private LetsLearnEnglish plugin;
    private Player viewer;
    private ConfigManager configManager;
    private FileConfiguration messages;
    private FileConfiguration mainConfig;
    private PlayerDataStore dataStore;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        viewer = mock(Player.class);
        configManager = mock(ConfigManager.class);
        messages = mock(FileConfiguration.class);
        mainConfig = mock(FileConfiguration.class);
        dataStore = mock(PlayerDataStore.class);

        // ---- 仅保留"每个用例都必然消费"的 4 条（STRICT_STUBS 下多一条就红）----
        // 4 个 GUI 的 open() 全部以 getConfigManager().getMessageConfig(lang) 起手。
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        // 文案一律给空串：既避免 ChatColor.translateAlternateColorCodes(null) 抛异常
        // （ProfileGUI 用 getString(key, "") 双参重载，mock 默认回 null），
        // 也让断言只依赖 holder 这一结构事实，不被任何文案牵着走。
        // 注意：单参 getString 有 4 个 GUI 都在用；双参重载只有 ProfileGUI 用，
        // 因此那条 stub 下放到 Profile 组。
        when(messages.getString(anyString())).thenReturn("");
    }

    // ------------------------------------------------------------------
    // 抓取工具
    // ------------------------------------------------------------------

    /**
     * 在「静态 Bukkit + ItemStack 构造」都被拦截的窗口内执行 {@code openAction}，
     * 抓取它交给 {@code Bukkit.createInventory(holder, size, title)} 的 holder 与 size，
     * 并断言它<b>确实把这张界面交给了玩家</b>。
     *
     * <p><b>FIX-5（RT-G3 P1）</b>：红队实测删掉 {@code WordBankSelectGUI.open} 末尾的
     * {@code player.openInventory(inv)}，159 个用例<b>全绿</b> —— 本工具只抓
     * {@code createInventory} 的实参，不验证界面真的被打开。原注释自称沿真实调用链
     * {@code → player.openInventory} 走一遍，实际只走到倒数第二步。
     *
     * <p>修法：把 {@code createInventory} 返回的 mock {@link Inventory} 存下来，
     * 再用 {@code same(...)}（引用相等）验证它被交给 {@code viewer.openInventory}。
     * 4 个被测 GUI 的实际调用形式已逐一核对（均为 {@code 打开者.openInventory(inv)}，
     * 构造同一个局部变量 {@code inv}，无分支、无二次包装）：
     * {@code WordBankSelectGUI:84}、{@code WrongBookGUI:219}、{@code ProfileGUI:246/372}、
     * {@code EnglishSquareGUI:338/545} —— 全部形如 {@code player.openInventory(inv)} 或
     * {@code viewer.openInventory(inv)}，因此对 {@code viewer} 的 same 校验覆盖全部 8 条用例。
     *
     * <p>用 {@code same} 而不是 {@code eq}：{@code Inventory} 是 mock，equals 是引用相等，
     * 但显式 same 能表达"必须是同一张界面"，且不依赖接口未来是否被赋予值语义。
     *
     * @param openAction 真正打开 GUI 的调用（{@code () -> new XxxGUI(plugin).open(...)}）
     * @return 捕获结果；若整段调用没有发生 {@code createInventory}，直接失败
     */
    private GuiCapture captureGui(Runnable openAction) {
        AtomicReference<InventoryHolder> capturedHolder = new AtomicReference<>();
        AtomicInteger capturedSize = new AtomicInteger(-1);
        AtomicReference<Inventory> created = new AtomicReference<>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {

            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        capturedHolder.set(invocation.getArgument(0));
                        capturedSize.set(invocation.getArgument(1));
                        Inventory mockInv = mock(Inventory.class);
                        created.set(mockInv);
                        return mockInv;
                    });

            openAction.run();
        }

        assertNotNull(capturedHolder.get(),
                "GUI.open() 从未调用 Bukkit.createInventory —— 打开路径被提前 return 或抛异常");
        assertNotNull(created.get(), "createInventory 未返回界面");
        // FIX-5：只"造界面"不"开界面"同样是玩家侧完全不可见的回退 —— 必须钉死。
        // times(1) 而非 atLeastOnce：重复打开同一张界面会让玩家看到闪烁/重复的关闭回调。
        verify(viewer, times(1)).openInventory(same(created.get()));
        return new GuiCapture(capturedHolder.get(), capturedSize.get());
    }

    /**
     * {@code createInventory} 的实参快照。
     *
     * @param holder 传给 {@code createInventory} 的 holder（阶段 2 的被测对象）
     * @param size   传给 {@code createInventory} 的尺寸
     */
    private record GuiCapture(InventoryHolder holder, int size) {

        /**
         * 断言 holder 是插件结构身份并返回强类型句柄。
         *
         * @return 同一个 holder，已转成 {@link PluginGuiHolder}
         */
        PluginGuiHolder pluginHolder() {
            assertInstanceOf(PluginGuiHolder.class, holder,
                    "createInventory 的 holder 必须是 PluginGuiHolder，否则点击侧退回标题判定");
            return (PluginGuiHolder) holder;
        }
    }

    // ------------------------------------------------------------------
    // 1. WordBankSelectGUI
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("WordBankSelectGUI —— 词库选择页")
    class WordBankSelect {

        /**
         * 本组唯一 collaborator：{@code WordBankSelectGUI} 的构造器与 {@code open()} 都会
         * 调 {@code getWordManager().getAvailableDifficulties()}（构造器存兜底快照，
         * open 时重查）。不铺这条 stub 会在构造阶段就 NPE。
         */
        @BeforeEach
        void stubWordManager() {
            WordManagerImpl wordManager = mock(WordManagerImpl.class);
            when(plugin.getWordManager()).thenReturn(wordManager);
            when(wordManager.getAvailableDifficulties())
                    .thenReturn(new LinkedHashSet<>(List.of("basic", "cet4")));
        }

        @Test
        @DisplayName("防回归：词库页 holder 掉回标题判定 —— key 必须是 WORD_BANK、payload 无上下文、尺寸 27")
        void wordBankSelectOpensWithWordBankHolder() {
            WordBankSelectGUI gui = new WordBankSelectGUI(plugin);
            GuiCapture capture = captureGui(() -> gui.open(viewer));

            PluginGuiHolder holder = capture.pluginHolder();
            assertEquals(GuiViewKey.WORD_BANK, holder.getKey(),
                    "词库页 key 写错会让 MiscGUIListener 的词库点击分支整体失联");
            assertSame(PluginGuiHolder.GuiPayload.NoPayload.INSTANCE, holder.getPayload(),
                    "词库页没有翻页语义，payload 必须是单例 NoPayload");
            assertEquals(27, capture.size(), "词库页尺寸固定 27 格");
        }
    }

    // ------------------------------------------------------------------
    // 2-4. WrongBookGUI
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("WrongBookGUI —— 错题本页码 clamp 与载荷")
    class WrongBook {

        /**
         * 本组共用三条：模块门、数据源门面、打开者 UUID。
         *
         * <p>模块门必须是 true —— 为 false 时 {@code open()} 会在 {@code sendMessage} 后
         * 立刻 return，连 {@code createInventory} 都不发生（本组三条用例的核心断言）。
         * {@code viewer.getUniqueId()} 被 {@code open()} 第一行用于查错题本条目；
         * 不 stub 会拿到 null 并在 {@code getWrongBookEntries(null)} 处偏离真实路径。
         * 数据源本身三条用例各自按场景覆写（同一个 mock，后写覆盖先写）。
         */
        @BeforeEach
        void stubWrongBookDependencies() {
            when(configManager.isWordSystemEnabled()).thenReturn(true);
            when(plugin.getPlayerDataStore()).thenReturn(dataStore);
            when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        }

        @Test
        @DisplayName("防回归：page=0 直写 holder —— GuiPayload.Page 拒绝 <1，必须在建界面前 clamp 到 1")
        void wrongBookClampsPageZeroToOne() {
            when(dataStore.getWrongBookEntries(VIEWER_ID)).thenReturn(Collections.emptyList());

            GuiCapture capture = captureGui(() -> new WrongBookGUI(plugin).open(viewer, 0));

            PluginGuiHolder holder = capture.pluginHolder();
            assertEquals(GuiViewKey.WRONG_BOOK, holder.getKey());
            assertEquals(new PluginGuiHolder.GuiPayload.Page(1), holder.getPayload(),
                    "page=0 未 clamp：Page 构造器会抛 IllegalArgumentException，玩家点不出错题本");
            assertEquals(54, capture.size());
        }

        @Test
        @DisplayName("防回归：越界页码直写 holder —— 空错题本时 page=9 必须 clamp 到唯一一页")
        void wrongBookClampsOversizePageToTotalPages() {
            when(dataStore.getWrongBookEntries(VIEWER_ID)).thenReturn(Collections.emptyList());

            GuiCapture capture = captureGui(() -> new WrongBookGUI(plugin).open(viewer, 9));

            PluginGuiHolder holder = capture.pluginHolder();
            assertEquals(new PluginGuiHolder.GuiPayload.Page(1), holder.getPayload(),
                    "空词表只有 1 页，page=9 未 clamp 会让翻页按钮指向不存在的页");
        }

        @Test
        @DisplayName("防回归：合法页码在 clamp 中被压平 —— 43 条词（2 页）时 page=2 必须原样进 holder")
        void wrongBookPageReachesHolderUnchanged() {
            when(plugin.getWordManager()).thenReturn(mock(WordManagerImpl.class));
            when(dataStore.getWrongBookEntries(VIEWER_ID)).thenReturn(wrongBookEntries(WRONG_BOOK_ITEMS_PER_PAGE + 1));

            GuiCapture capture = captureGui(() -> new WrongBookGUI(plugin).open(viewer, 2));

            PluginGuiHolder holder = capture.pluginHolder();
            assertEquals(new PluginGuiHolder.GuiPayload.Page(2), holder.getPayload(),
                    "第 2 页被 clamp 回第 1 页：翻页后 WrongBookListener 读到旧页码，删词后重开跳回首页");
            assertEquals(GuiViewKey.WRONG_BOOK, holder.getKey());
        }

        /**
         * 构造 n 条错题本条目（每条都满足「wrong_count >= 3 才收录」的语义，值本身不影响渲染）。
         *
         * @param count 条目数
         * @return 可直接作为 {@code getWrongBookEntries} 返回值的列表
         */
        private List<Map.Entry<String, DifficultWord>> wrongBookEntries(int count) {
            List<Map.Entry<String, DifficultWord>> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String word = "wrongword" + i;
                entries.add(Map.entry(word, new DifficultWord(word, 3, 0L)));
            }
            return entries;
        }
    }

    // ------------------------------------------------------------------
    // 5-6. ProfileGUI
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("ProfileGUI —— 主页归属 payload 与设置页 key")
    class Profile {

        /**
         * 本组共用：打开者 UUID + 玩家数据门面。
         *
         * <p>{@code viewer.getUniqueId()} 决定 {@code isOwn}（他人主页分支的前提，
         * 也是设置页 {@code getProfileSettings(viewerId)} 的入参）；
         * {@code getPlayerDataStore()} 是主页/设置页共同的数据源。
         *
         * <p>FIX-5（方案 A）：{@code getMainConfig()} 只在
         * {@code MessageUtil.getPlayerMessage}（成就墙文案）里被读到，而本组
         * {@code isAchievementsEnabled()} 保持默认 false 跳过该分支 —— 因此不铺。
         * 原先它在公共 {@code setUp} 里，8 条用例只有 0 条真正消费它。
         */
        @BeforeEach
        void stubProfileViewer() {
            when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
            when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        }

        @Test
        @DisplayName("防回归：他人主页丢失归属 —— payload 必须是打开时写死的 Target(targetId)，而非从标题反查")
        void profileOpensWithTargetPayload() {
            mockProfileServices();

            GuiCapture capture = captureGui(() -> new ProfileGUI(plugin).open(viewer, TARGET_ID));

            PluginGuiHolder holder = capture.pluginHolder();
            assertEquals(GuiViewKey.PROFILE, holder.getKey());
            PluginGuiHolder.GuiPayload payload = holder.getPayload();
            assertInstanceOf(PluginGuiHolder.GuiPayload.Target.class, payload,
                    "他人主页必须挂 Target 载荷；退回标题反查会让昵称互为前缀时挑错人");
            assertEquals(TARGET_ID, ((PluginGuiHolder.GuiPayload.Target) payload).targetId(),
                    "payload 里的目标 UUID 必须与打开时传入的一致");
            assertEquals(27, capture.size());
        }

        @Test
        @DisplayName("防回归：设置页与主页共用 key —— 两页标题文案不同但 holder key 必须分别是 PROFILE / PROFILE_SETTINGS")
        void profileAndSettingsUseDistinctKeys() {
            mockProfileServices();
            // 设置页独有：ProfileGUI.openSettings 只读自己的隐私设置。
            when(dataStore.getProfileSettings(VIEWER_ID))
                    .thenReturn(new PlayerDataStore.ProfileSettings());

            ProfileGUI gui = new ProfileGUI(plugin);
            GuiCapture profileCapture = captureGui(() -> gui.open(viewer, TARGET_ID));
            GuiCapture settingsCapture = captureGui(() -> gui.openSettings(viewer));

            GuiViewKey profileKey = profileCapture.pluginHolder().getKey();
            GuiViewKey settingsKey = settingsCapture.pluginHolder().getKey();

            assertEquals(GuiViewKey.PROFILE, profileKey);
            assertEquals(GuiViewKey.PROFILE_SETTINGS, settingsKey,
                    "设置页若复用 PROFILE，点击隐私开关会被当成主页交互（关界面即丢设置）");
            assertNotEquals(profileKey, settingsKey,
                    "同一 ProfileGUI 的两页必须拆成两个 key，否则 holder 身份退化成标题判定");
            assertEquals(27, profileCapture.size());
            assertEquals(27, settingsCapture.size());
        }

        /**
         * 他人主页路径所需的全部 collaborator：数据源 + 成就计数。
         *
         * <p>不看自己主页（targetId != viewerId），因此 {@code OnboardingManager} 分支
         * 不会被触碰；{@code getEconomy()} 返回 null 走「无经济」分支；
         * {@code isAchievementsEnabled()} 默认 false 跳过成就墙按钮 —— 这让
         * {@code getMainConfig()} / 双参 {@code messages.getString(key, default)}
         * 两条 stub 也不必铺。
         *
         * <p>FIX-5（方案 A）：这些 stub 只有两条用例消费，因此从公共 {@code setUp}
         * 下放到本 helper。原先把它们铺在 {@code setUp} 里，另外 6 条用例一个字都用不上 ——
         * 那正是 STRICT_STUBS 下 16 处 {@code UnnecessaryStubbingException} 的来源。
         */
        private void mockProfileServices() {
            // ProfileGUI.open() 第 100/101 行用双参 getString(key, "") 读主菜单文案，
            // openSettings() 同款 —— 不铺会拿到 null 并在 translateAlternateColorCodes 里 NPE。
            // 单参 stub 已在公共 setUp（4 个 GUI 都消费），双参只有本组用，故放这里。
            when(messages.getString(anyString(), anyString())).thenReturn("");
            when(dataStore.getPlayerStats(TARGET_ID)).thenReturn(new PlayerDataStore.PlayerStats());
            when(dataStore.getProfileSettingsReadOnly(TARGET_ID))
                    .thenReturn(new PlayerDataStore.ProfileSettings());
            when(plugin.getAchievementManager()).thenReturn(mock(AchievementManager.class));
        }
    }

    // ------------------------------------------------------------------
    // 7-8. EnglishSquareGUI
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("EnglishSquareGUI —— 广场模式载荷与隐身页 key")
    class EnglishSquare {

        /**
         * 本组共用（只有 gui-layout 回落）：{@code open()} 与 {@code openHidden()} 都读
         * {@code getGuiSlot/getGuiMaterial}（模式按钮槽位、返回键、进度指示）。用
         * "回填调用方默认实参"的 answer 一次覆盖全部槽位/材质读取，等价于
         * 「管理员没改配置」。
         *
         * <p>FIX-5（方案 A）：{@code getMainConfig} / {@code getPlayerDataStore} /
         * {@code viewer.getUniqueId()} 只有 {@code open()} 那条用例消费，{@code openHidden}
         * 是纯静态装饰页 —— 它们下放到用例体内，于是"笑话页零查库"这条语义被
         * STRICT_STUBS 顺手钉死（多余 stub 会直接红）。
         */
        @BeforeEach
        void stubGuiLayoutFallbacks() {
            when(configManager.getGuiSlot(anyString(), anyString(), anyInt()))
                    .thenAnswer(invocation -> invocation.getArgument(2));
            when(configManager.getGuiMaterial(anyString(), anyString(), any(Material.class)))
                    .thenAnswer(invocation -> invocation.getArgument(2));
        }

        @Test
        @DisplayName("防回归：广场 payload 只带页码丢掉模式 —— 周榜第 2 页必须携带 SquarePage(2, weekly)")
        void squarePayloadCarriesPageAndModeKey() {
            // 榜单分支：WEEKLY 模式走 ProgressManager → Leaderboard，需要榜单门开启。
            when(configManager.isLeaderboardEnabled()).thenReturn(true);
            // open() 读 progress-system.leaderboard.display-count 与 english-square.default-mode。
            when(configManager.getMainConfig()).thenReturn(mainConfig);
            // 头像区（第 2 页只渲染 2 行）按 targetId 取名；自己头像槽读 getCoinsEarned。
            when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
            when(plugin.getPlayerDataStore()).thenReturn(dataStore);
            when(dataStore.getPlayerName(any())).thenReturn("SomePlayer");

            List<Leaderboard.LeaderboardEntry> entries = leaderboardEntries(30);
            ProgressManagerImpl progressManager = mock(ProgressManagerImpl.class);
            Leaderboard leaderboard = mock(Leaderboard.class);
            when(plugin.getProgressManager()).thenReturn(progressManager);
            when(progressManager.getLeaderboard()).thenReturn(leaderboard);
            when(leaderboard.getTopPlayers(any(), anyInt())).thenReturn(entries);
            // 非 ALL_PLAYERS 模式下自己头像槽读 getCoinsEarned（leaderboard.rank-entry）。
            when(plugin.getDatabaseManager()).thenReturn(mock(DatabaseManager.class));

            GuiCapture capture = captureGui(() -> new EnglishSquareGUI(plugin)
                    .open(viewer, 2, Leaderboard.RankingMode.WEEKLY));

            PluginGuiHolder holder = capture.pluginHolder();
            assertEquals(GuiViewKey.SQUARE, holder.getKey());
            assertEquals(new PluginGuiHolder.GuiPayload.SquarePage(2, "weekly"), holder.getPayload(),
                    "模式键写错：点击侧按 payload 分派榜单，会渲染出与标题不一致的列表");
            assertEquals(54, capture.size());
        }

        @Test
        @DisplayName("防回归：隐身笑话页与 54 格广场标题完全相同 —— 只能靠 SQUARE_HIDDEN + 27 格区分")
        void squareHiddenUsesDistinctKeyAt27() {
            // FIX-5（方案 A）：openHidden 完全不触 PlayerDataStore（纯静态装饰页），
            // 原先这里铺的 getPlayerStats / getPlayerName 是纯多余 stub，
            // STRICT_STUBS 下会被如实投诉 —— 现在不铺，等于顺带钉死"笑话页零查库"。
            GuiCapture capture = captureGui(() -> new EnglishSquareGUI(plugin).openHidden(viewer, TARGET_ID));

            PluginGuiHolder holder = capture.pluginHolder();
            assertEquals(GuiViewKey.SQUARE_HIDDEN, holder.getKey(),
                    "笑话页若复用 SQUARE，点击侧会把它当成分页列表，翻页按钮指向不存在的页");
            assertSame(PluginGuiHolder.GuiPayload.NoPayload.INSTANCE, holder.getPayload());
            assertEquals(27, capture.size(), "笑话页固定 27 格；尺寸是它和 54 格主页的第二个结构差异");
        }

        /**
         * 构造 n 条榜单行；{@code n > 28}（每页容量）时才有第 2 页。
         *
         * @param count 行数
         * @return 可直接作为 {@code getTopPlayers} 返回值的列表
         */
        private List<Leaderboard.LeaderboardEntry> leaderboardEntries(int count) {
            List<Leaderboard.LeaderboardEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Leaderboard.LeaderboardEntry(
                        new UUID(0L, i + 1L), "Player" + i, 100L - i, 10, 1, 3, i + 1));
            }
            return entries;
        }
    }
}
