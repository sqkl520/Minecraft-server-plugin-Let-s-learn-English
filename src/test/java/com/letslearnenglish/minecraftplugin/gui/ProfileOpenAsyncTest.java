package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.onboarding.OnboardingManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * L21：{@code ProfileGUI.open} 的「主线程查库 → 异步回填」改造验收。
 *
 * <p><b>为什么需要这个类</b>：{@code open()} 原先在主线程同步调三个可能查库的方法
 * （{@code getPlayerStats}、{@code getUnlockedCount}、Vault {@code getBalance}），
 * 只有 {@code getDuelStats} 走异步。从榜单/广场点<b>离线玩家</b>主页时缓存必然 miss
 * （{@code PlayerJoinListener:124} 在 quit 时删键），于是主线程要扫
 * {@code word_progress} + 成就表 —— TPS 直接跟着玩家手速走。更糟的是
 * {@code getUnlockedCount} 在 {@code isAchievementsEnabled()} 门之外，模块关着也白查。
 *
 * <p>本类用 {@code verify(..., never())} 把「主线程不得查库」钉成断言：一旦有人把取数
 * 搬回 {@code open()}，无论有没有人加新功能，这条会立刻红。
 *
 * <p><b>脚手架</b>：调度器的 {@code runTaskAsynchronously} / {@code runTask} 都就地
 * 同步执行 —— 整条「异步取数 → 回主线程回填」链在测试里一次展开，无需 sleep。
 * 「只测主线程段」的用例把异步段挂起（{@code deferAsync}），于是「异步段里查了什么」
 * 与「主线程查了什么」可以被分别断言。
 */
@ExtendWith(MockitoExtension.class)
class ProfileOpenAsyncTest {

    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    /** 被查看者（与 viewer 不同 → 走他人主页分支）。 */
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final int PROFILE_SIZE = 27;

    private static final int WORDS_SLOT = 11;
    private static final int ACCURACY_SLOT = 12;
    private static final int SESSIONS_SLOT = 14;
    /** 金币行（ProfileGUI 的 slot 15；仅本人主页展示数值）。 */
    private static final int COINS_SLOT = 15;
    /** 成就墙按钮（ProfileGUI 的 slot 18）。 */
    private static final int ACHIEVEMENTS_SLOT = 18;
    /** 决斗战绩行（ProfileGUI 的 DUEL_SLOT）。 */
    private static final int DUEL_SLOT = 16;

    @Mock
    private LetsLearnEnglish plugin;
    @Mock
    private Player viewer;
    @Mock
    private ConfigManager configManager;
    @Mock
    private FileConfiguration messages;
    @Mock
    private PlayerDataStore dataStore;

    /** 「取数失败」用例要验证告警带栈，需要一个可断言的 logger。 */
    private java.util.logging.Logger logger;

    @BeforeEach
    void setUp() {
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        // T-3（红队验收）：文案必须按 key 返回<b>可区分</b>的串。
        // 旧写法两个重载都恒返回 ""，于是 hiddenValue 与 loadingLine 都是空串，
        // 「隐藏行」与「加载中占位」在断言层面完全同形 —— 把
        // `hideResults ? hiddenValue : loadingLine` 改成恒 loadingLine，测试依然全绿。
        // 现在按 key 分三档：含 "hidden" 的键 → HIDDEN_MARK（隐藏行）、
        // 含 "loading" 的键 → LOADING_MARK（占位行）、其余 → OTHER_MARK（标签/数值）。
        // lenient：本类各用例消费的键集合不同，未消费的 stub 不该报 UnnecessaryStubbing。
        lenient().when(messages.getString(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.contains("hidden")) return "HIDDEN_MARK";
            if (key.contains("loading")) return "LOADING_MARK";
            return "OTHER_MARK";
        });
        lenient().when(messages.getString(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.contains("hidden")) return "HIDDEN_MARK";
            if (key.contains("loading")) return "LOADING_MARK";
            return "OTHER_MARK";
        });
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        // T-2 的本人主页用例查看的是 VIEWER_ID，不读 TARGET_ID 的名字 → lenient。
        lenient().when(dataStore.getPlayerName(TARGET_ID)).thenReturn("TargetPlayer");
        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        // 「取数失败」用例用的可断言 logger；其余用例不消费它，故 lenient。
        logger = mock(java.util.logging.Logger.class);
        lenient().when(plugin.getLogger()).thenReturn(logger);
        // 他人主页的隐私默认值：showResults/showInSquare 均为 true（用例可自行覆盖）。
        lenient().when(dataStore.getProfileSettingsReadOnly(TARGET_ID))
                .thenReturn(new PlayerDataStore.ProfileSettings());
        // 经济未接入：coinsAsync=false，因此主线程不会调用 Bukkit.getOfflinePlayer，
        // 异步段也不会碰 Vault（用例要测金币时自行覆盖 getEconomy）。
        lenient().when(plugin.getEconomy()).thenReturn(null);
    }

    // ------------------------------------------------------------------
    // A. 主线程不查库
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 A —— 主线程零 DB 调用")
    class MainThreadDoesNoIo {

        @Test
        @DisplayName("open() 本身不得调用 getPlayerStats / getUnlockedCount / getDuelStats / getDuelWinStreak")
        void openDoesNotHitDatabaseOnMainThread() {
            when(configManager.isDuelEnabled()).thenReturn(true);
            when(configManager.isAchievementsEnabled()).thenReturn(true);

            // 异步段挂起：于是「没查库」只可能因为主线程段没查，而不是被异步段吸收了。
            Harness harness = new Harness(true);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateAndOpen(bukkit, harness);
                new ProfileGUI(plugin).open(viewer, TARGET_ID);
            }

            assertTrue(harness.asyncDispatched, "异步回填必须被派发（否则数据永远停在「加载中」）");
            assertFalse(harness.asyncRan, "本用例故意挂起异步段，它不该已经跑过");

            verify(dataStore, never()).getPlayerStats(any(UUID.class));
            verify(dataStore, never()).getProfileSettings(any(UUID.class));
            verify(plugin, never()).getDatabaseManager();
            verify(plugin, never()).getAchievementManager();
        }

        /**
         * E-P1-1（红队 E）：onboarding 模块关闭时，打开<b>本人</b>主页不得主线程查库。
         *
         * <p><b>为什么不能只加一句 {@code verify(db, never()).getOnboardingStep(any())}</b>：
         * 本类其它用例把 {@code plugin.getOnboardingManager()} stub 成了 mock，那条断言会
         * 落在 mock 上 —— 「never」恒成立，无论真实现查不查库。这是自证桩式的假绿。
         *
         * <p><b>本用例的形状</b>：装配<b>真实</b>的 {@link OnboardingManager}（只把它依赖的
         * {@code DatabaseManager} 换成 mock），{@code onboarding.enabled=false}，然后走
         * {@code open()} 全链路（本人主页 → isOwn 分支会调 {@code onProfileOpened}）。
         * 判别力：删掉 {@code OnboardingManager.onProfileOpened} 开头的模块门，
         * 真实实现会走到 {@code getStep} → 缓存 miss（本用例没人预热）→
         * {@code db.getOnboardingStep}，本断言立刻红。
         *
         * <p>这也顺带钉住「{@code onProfileOpened} 不做 enabled 门」这个旧约定已被推翻。
         */
        @Test
        @DisplayName("E-P1-1：onboarding.enabled=false 时，本人主页打开不得主线程查 onboarding_step")
        void onboardingDisabledDoesNotQueryStepOnMainThread() {
            when(configManager.isDuelEnabled()).thenReturn(false);
            when(configManager.isAchievementsEnabled()).thenReturn(false);
            when(configManager.isOnboardingEnabled()).thenReturn(false);
            // 本人主页会先走新手引导登记（isOwn 分支）。stubOpenBasics 里的
            // isOnboardingEnabled 是「真实实现」用的；这里断言的是<b>主线程零查询</b>，
            // 异步段被 Harness(true) 挂起，因此 getPlayerStats / isEnabled 等
            // 异步侧 stub 都不会被消费 —— 全部 lenient，避免 STRICT_STUBS 把
            // 「异步段没跑」误报成「多余代码」。
            lenient().when(plugin.isEnabled()).thenReturn(true);
            when(viewer.getName()).thenReturn("Viewer");
            when(dataStore.getPlayerName(VIEWER_ID)).thenReturn("SelfPlayer");
            when(dataStore.getProfileSettingsReadOnly(VIEWER_ID))
                    .thenReturn(new PlayerDataStore.ProfileSettings());
            lenient().when(dataStore.getPlayerStats(VIEWER_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());

            DatabaseManager db = mock(DatabaseManager.class);
            // lenient：本用例把异步段挂起了，正常路径下主线程<b>不该</b>碰 DatabaseManager
            // —— 这条 stub「未被消费」才是正确结果。若模块门被删掉，真实
            // OnboardingManager 会经这里拿到 db 并调 getOnboardingStep，下面的
            // verify(never()) 立刻红。
            lenient().when(plugin.getDatabaseManager()).thenReturn(db);
            // 关键：真实实现，不是 mock —— 否则 never() 断言无判别力（自证桩）。
            // 必须先构造到局部变量再交给 thenReturn：构造函数内部会调
            // plugin.getServer()，把构造写在 when(...) 表达式里会变成
            // 「stubbing 内嵌 stubbing」（Mockito 直接报 UnfinishedStubbing）。
            OnboardingManager realOnboarding = new OnboardingManager(plugin);
            when(plugin.getOnboardingManager()).thenReturn(realOnboarding);

            // 异步段挂起：本用例只关心主线程段。
            Harness harness = new Harness(true);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateAndOpen(bukkit, harness);
                new ProfileGUI(plugin).open(viewer, VIEWER_ID);
            }

            verify(db, never()).getOnboardingStep(any(UUID.class));
            assertTrue(harness.asyncDispatched, "异步回填仍应照常派发（模块门只该拦住 onboarding 查询）");
        }

        @Test
        @DisplayName("成就模块关闭：连异步段都不得调用 getUnlockedCount（L21 的原始 bug）")
        void achievementsDisabledSkipsUnlockedCountEntirely() {
            when(configManager.isDuelEnabled()).thenReturn(false);
            when(configManager.isAchievementsEnabled()).thenReturn(false);
            when(plugin.isEnabled()).thenReturn(true);
            when(dataStore.getPlayerStats(TARGET_ID)).thenReturn(new PlayerDataStore.PlayerStats());

            Harness harness = new Harness(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateAndOpen(bukkit, harness);
                new ProfileGUI(plugin).open(viewer, TARGET_ID);
            }

            verify(plugin, never()).getAchievementManager();
        }
    }

    // ------------------------------------------------------------------
    // B. 异步回填
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 B —— 异步回填统计三卡")
    class Backfill {

        @Test
        @DisplayName("异步回来之后 slot 11/12/14 都被回填（各写 2 次：占位 → 数值）")
        void statsSlotsAreFilledAfterAsyncBackfill() {
            when(configManager.isDuelEnabled()).thenReturn(false);
            when(configManager.isAchievementsEnabled()).thenReturn(false);
            when(plugin.isEnabled()).thenReturn(true);
            PlayerDataStore.PlayerStats stats = new PlayerDataStore.PlayerStats();
            stats.addCorrect(8);
            stats.addIncorrect(2);
            stats.incrementSessions();
            stats.incrementSessions();
            stats.incrementSessions();
            when(dataStore.getPlayerStats(TARGET_ID)).thenReturn(stats);

            Harness harness = new Harness(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateAndOpen(bukkit, harness);
                new ProfileGUI(plugin).open(viewer, TARGET_ID);
            }

            assertNotNull(harness.inventory, "createInventory 未返回界面");
            assertEquals(2, harness.writesTo(WORDS_SLOT),
                    "slot 11 应被写 2 次（主线程「加载中」占位 + 异步回填）；"
                            + "写 1 次 = 回填缺失，写 3 次 = fillEmpty 覆盖了已有物品");
            assertEquals(2, harness.writesTo(ACCURACY_SLOT), "slot 12 同上");
            assertEquals(2, harness.writesTo(SESSIONS_SLOT), "slot 14 同上");
            assertNotNull(harness.inventory.getItem(WORDS_SLOT));
            assertNotNull(harness.inventory.getItem(ACCURACY_SLOT));
            assertNotNull(harness.inventory.getItem(SESSIONS_SLOT));
        }

        @Test
        @DisplayName("异步回来时 holder 已失效（界面被关）：不回填、不抛异常")
        void staleHolderIsRejectedWithoutThrowing() {
            when(configManager.isDuelEnabled()).thenReturn(false);
            when(configManager.isAchievementsEnabled()).thenReturn(false);
            when(plugin.isEnabled()).thenReturn(true);
            when(dataStore.getPlayerStats(TARGET_ID)).thenReturn(new PlayerDataStore.PlayerStats());

            Harness harness = new Harness(false);
            // 回填时玩家看的是另一张界面（holder 不是打开时那个实例）。
            when(viewer.isOnline()).thenReturn(true);
            InventoryView otherView = mock(InventoryView.class);
            Inventory otherTop = mock(Inventory.class);
            when(otherView.getTopInventory()).thenReturn(otherTop);
            when(viewer.getOpenInventory()).thenReturn(otherView);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateAndOpen(bukkit, harness, false /* 不把自己的界面接到 viewer */);
                // 不得抛异常 —— 断言由「调用正常返回」承担。
                new ProfileGUI(plugin).open(viewer, TARGET_ID);
            }

            verify(otherTop, never()).setItem(anyInt(), any());
        }

        @Test
        @DisplayName("隐私隐藏态（他人关 showResults）：统计整段不进异步段，三卡只有隐藏行")
        void hiddenResultsSkipStatsFetch() {
            when(configManager.isDuelEnabled()).thenReturn(false);
            when(configManager.isAchievementsEnabled()).thenReturn(false);
            PlayerDataStore.ProfileSettings hidden = new PlayerDataStore.ProfileSettings();
            hidden.setShowResults(false);
            when(dataStore.getProfileSettingsReadOnly(TARGET_ID)).thenReturn(hidden);

            Harness harness = new Harness(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                stubCreateAndOpen(bukkit, harness);
                new ProfileGUI(plugin).open(viewer, TARGET_ID);
            }

            verify(dataStore, never()).getPlayerStats(any(UUID.class));
            assertFalse(harness.asyncDispatched,
                    "统计/金币/成就/决斗全都不需要取数时，不该派发异步任务（纯白烧 IO）");
            assertEquals(1, harness.writesTo(WORDS_SLOT),
                    "隐藏态 slot 11 只写一次（隐藏行），不得再有回填覆盖");
            assertEquals(1, harness.writesTo(ACCURACY_SLOT));
            assertEquals(1, harness.writesTo(SESSIONS_SLOT));
        }

        @Test
        @DisplayName("L4：决斗模块开启时，连胜与战绩在同一个异步段里一起取")
        void duelStreakIsFetchedTogetherWithStats() {
            when(configManager.isDuelEnabled()).thenReturn(true);
            when(configManager.isAchievementsEnabled()).thenReturn(false);
            when(plugin.isEnabled()).thenReturn(true);
            when(dataStore.getPlayerStats(TARGET_ID)).thenReturn(new PlayerDataStore.PlayerStats());
            DatabaseManager db = mock(DatabaseManager.class);
            when(plugin.getDatabaseManager()).thenReturn(db);
            when(db.getDuelStats(TARGET_ID)).thenReturn(new DatabaseManager.DuelStats(4, 1));
            when(db.getDuelWinStreak(TARGET_ID)).thenReturn(3);

            Harness harness = new Harness(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateAndOpen(bukkit, harness);
                new ProfileGUI(plugin).open(viewer, TARGET_ID);
            }

            // 只断言「问了」：streak 是否显示取决于 i18n 键是否落地（L8 的语义）。
            verify(db, org.mockito.Mockito.times(1)).getDuelWinStreak(TARGET_ID);
            verify(db, org.mockito.Mockito.times(1)).getDuelStats(TARGET_ID);
        }

        @Test
        @DisplayName("取数全部抛异常 → 各字段为 null，保留「加载中」占位而不是伪造 0")
        void fetchFailuresLeavePlaceholdersInsteadOfFakeZeros() {
            // 三路（统计 / 成就 / 决斗）数据源各自独立 try —— 任何一处失败都只该让那一格
            // 停在「加载中」，绝不能写 0（把「不知道」说成「没学过」是谎报），也不能让
            // 整页回填放弃。
            // T-2（红队验收）：本用例<b>只覆盖三路</b>，不是「全部四路」。
            // 他人主页（VIEWER_ID != TARGET_ID）+ getEconomy()==null → coinsAsync=false，
            // 金币分支根本不进，snapshot.coins() 恒 null 且不来自任何异常 —— 原注释写
            // 「全部四路」是假绿。金币那一路由下方
            // {@code coinsFetchFailureKeepsPlaceholderForOwnProfile} 单独覆盖（本人主页）。
            when(configManager.isDuelEnabled()).thenReturn(true);
            when(configManager.isAchievementsEnabled()).thenReturn(true);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(logger);
            // 他人主页 → coinsAsync=false，金币走「已隐藏」分支，不参与本用例。
            when(dataStore.getPlayerStats(TARGET_ID)).thenThrow(new RuntimeException("boom-stats"));
            AchievementManager am = mock(AchievementManager.class);
            when(plugin.getAchievementManager()).thenReturn(am);
            when(am.getUnlockedCount(TARGET_ID)).thenThrow(new RuntimeException("boom-ach"));
            DatabaseManager db = mock(DatabaseManager.class);
            when(plugin.getDatabaseManager()).thenReturn(db);
            when(db.getDuelStats(TARGET_ID)).thenThrow(new RuntimeException("boom-duel"));

            Harness harness = new Harness(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateAndOpen(bukkit, harness);
                new ProfileGUI(plugin).open(viewer, TARGET_ID);
            }

            // 三卡：只有主线程那一次「加载中」占位，异步回来因 stats==null 而跳过。
            // 若 ProfileSnapshot 的 stats 判空被删 → 这里会变成 2 次（或 NPE），断言红。
            assertEquals(1, harness.writesTo(WORDS_SLOT),
                    "统计取数失败时不得回填 —— 应保持「加载中」占位，写 2 次说明挂了 null 伪造值");
            assertEquals(1, harness.writesTo(ACCURACY_SLOT));
            assertEquals(1, harness.writesTo(SESSIONS_SLOT));

            // 成就墙：占位 1 次，unlockedCount==null 时不覆盖。
            assertEquals(1, harness.writesTo(ACHIEVEMENTS_SLOT),
                    "成就数取数失败时不得回填 —— 否则会把「查不到」显示成 0/total");

            // 决斗：占位 1 次，duel==null 时不覆盖。
            assertEquals(1, harness.writesTo(DUEL_SLOT),
                    "决斗取数失败时不得回填 —— 否则会把「查不到」显示成「暂无战绩」");

            // 失败必须带栈告警，而不是静默吞掉（L2 口径）。
            verify(logger, org.mockito.Mockito.atLeastOnce())
                    .log(eq(java.util.logging.Level.WARNING), anyString(), any(Throwable.class));
        }

        /**
         * T-2（红队验收）：金币那一路的失败分支此前<b>从未被测过</b>。
         *
         * <p><b>为什么原用例测不到</b>：金币异步取数的前提是
         * {@code coinsAsync = isOwn && plugin.getEconomy() != null}。原 {@code fetchFailures}
         * 用例是他人主页（{@code VIEWER_ID != TARGET_ID}）、经济也未接入，于是
         * {@code coinsAsync=false} —— 异步段根本不调 {@code economy.getBalance}，
         * {@code snapshot.coins()} 恒 null。断言「slot 15 只写 1 次」照样绿，但绿的原因
         * 是「压根没进这条分支」，不是「异常被正确降级」。
         *
         * <p><b>本用例的正确形状</b>：本人主页（目标就是 viewer 自己的 UUID）
         * + 已接入 Vault，让 {@code coinsAsync=true}；{@code getBalance} 抛 RuntimeException
         * → 异步段 catch → {@code snapshot.coins()==null} → 回填侧跳过 slot 15。
         * 判别力：若有人删掉 {@code coins == null} 判空（或把 catch 改成填 0），
         * slot 15 会被写第 2 次（值是 "0"），断言红。
         */
        @Test
        @DisplayName("本人主页 + Vault getBalance 抛异常 → slot 15 保持占位（不是伪造 0），且确有调用")
        void coinsFetchFailureKeepsPlaceholderForOwnProfile() {
            when(configManager.isDuelEnabled()).thenReturn(false);
            when(configManager.isAchievementsEnabled()).thenReturn(false);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(logger);
            // 本人主页：被查看者就是 viewer 自己（不重打 getUniqueId 的共享 stub）。
            // 本人主页的 title 走 `profile.title`.replace("{player}", viewer.getName())，
            // mock 的 getName() 默认 null 会让 String.replace 抛 NPE —— 必须铺非 null。
            when(viewer.getName()).thenReturn("Viewer");
            when(dataStore.getPlayerName(VIEWER_ID)).thenReturn("SelfPlayer");
            when(dataStore.getProfileSettingsReadOnly(VIEWER_ID))
                    .thenReturn(new PlayerDataStore.ProfileSettings());
            // 本人主页会先走新手引导登记（isOwn 分支），overlay 成 no-op。
            lenient().when(plugin.getOnboardingManager()).thenReturn(mock(OnboardingManager.class));
            when(dataStore.getPlayerStats(VIEWER_ID)).thenReturn(new PlayerDataStore.PlayerStats());

            Economy economy = mock(Economy.class);
            when(plugin.getEconomy()).thenReturn(economy);
            when(economy.getBalance(any(OfflinePlayer.class)))
                    .thenThrow(new RuntimeException("boom-coins"));

            Harness harness = new Harness(false);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                // 金币异步需要在主线程先取 OfflinePlayer（异步线程不碰 Bukkit API）。
                bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class)))
                        .thenReturn(mock(OfflinePlayer.class));
                stubCreateAndOpen(bukkit, harness);
                new ProfileGUI(plugin).open(viewer, VIEWER_ID);
            }

            // 确实进了金币分支（否则本用例又退回「测不到」的假绿形状）。
            verify(economy, times(1)).getBalance(any(OfflinePlayer.class));
            // 取数失败 → 不得回填，slot 15 只有主线程那一次「加载中」占位。
            assertEquals(1, harness.writesTo(COINS_SLOT),
                    "金币取数失败时不得回填 —— 应保持「加载中」占位；写 2 次说明伪造了 0");
            assertEquals("LOADING_MARK", harness.loreOf(COINS_SLOT, 0),
                    "失败态 slot 15 的 lore 必须是「加载中」占位，不得是伪造的 0");
            // 与失败根因对应：金币这一路也要带栈告警。
            verify(logger, org.mockito.Mockito.atLeastOnce())
                    .log(eq(java.util.logging.Level.WARNING), anyString(), any(Throwable.class));
        }
    }

    // ------------------------------------------------------------------
    // C. L22 隐私联动：拿到「最严」副本后，主页渲染必须隐藏结果
    // ------------------------------------------------------------------

    /**
     * L22 的两段是分开验证的，别把它们的责任混在一起：
     * <ul>
     *   <li><b>「cache miss 时返回最严副本」</b>—— 由 {@code ProfileSettingsReadOnlyTest}
     *       直接调用 {@link PlayerDataStore#getProfileSettingsReadOnly} 验证，那里是真守卫
     *       （把实现改回 {@code new ProfileSettings()} 会红）。</li>
     *   <li><b>「拿到最严副本后，主页按隐藏态渲染」</b>—— 就是本用例。这里用 mock 的
     *       {@code dataStore} 直接给出最严副本，测的是 {@code ProfileGUI} 对它的反应：
     *       不查统计、三卡各写 1 次隐藏行。</li>
     * </ul>
     * <p>把两段塞进一个用例会得到「自己 stub、自己断言」的假绿（曾如此）；
     * 拆开后本用例的判别力在于：若 {@code ProfileGUI} 忽略 {@code isShowResults()}
     * 而始终渲染数值行，它会红。
     */
    @Test
    @DisplayName("L22：拿到最严隐私副本（showResults=false）→ 他人主页三卡只写隐藏行，且不查统计")
    void strictestPrivacyCopyRendersHiddenResults() {
        when(configManager.isDuelEnabled()).thenReturn(false);
        when(configManager.isAchievementsEnabled()).thenReturn(false);
        // 直接给最严副本 —— 「cache miss 时是否真的返回它」由 ProfileSettingsReadOnlyTest 负责。
        when(dataStore.getProfileSettingsReadOnly(TARGET_ID))
                .thenReturn(PlayerDataStore.ProfileSettings.unresolvedFallback());
        // 关键：必须让 getPlayerStats 可返回非 null，否则「无视隐私开关」的错误实现
        // 会因为拿到 null 而跳过回填，写出同样是 1 次 —— 断言看似通过，其实没判别力。
        // 铺好之后：正确实现「不查」（verify never + 写 1 次）；错误实现「查了且回填」
        // → 写 2 次且 verify(never) 失败，两条断言都红。
        PlayerDataStore.PlayerStats stats = mock(PlayerDataStore.PlayerStats.class);
        lenient().when(dataStore.getPlayerStats(TARGET_ID)).thenReturn(stats);
        lenient().when(plugin.isEnabled()).thenReturn(true);

        Harness harness = new Harness(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> ignored = itemStackMock()) {
            bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
            stubCreateAndOpen(bukkit, harness);
            new ProfileGUI(plugin).open(viewer, TARGET_ID);
        }

        // hideResults=true → 不查统计、三卡各写 1 次（隐藏行）。
        // 判别力：若 ProfileGUI 无视 showResults 而渲染数值，getPlayerStats 会被调用 → 本断言红。
        verify(dataStore, never()).getPlayerStats(any(UUID.class));
        assertEquals(1, harness.writesTo(WORDS_SLOT));
        assertEquals(1, harness.writesTo(ACCURACY_SLOT));
        assertEquals(1, harness.writesTo(SESSIONS_SLOT));

        // T-3（红队验收）：只数次数看不出「写的是隐藏行还是加载中」。
        // 旧夹具让 hiddenValue 与 loadingLine 都是空串，把
        // `hideResults ? hiddenValue : loadingLine` 改成恒 loadingLine 也全绿。
        // 现在文案按 key 区分（hidden → HIDDEN_MARK，loading → LOADING_MARK），
        // 断言的是<b>内容</b>：三卡 lore 必须是隐藏行、且不得混入加载中占位。
        for (int slot : new int[]{WORDS_SLOT, ACCURACY_SLOT, SESSIONS_SLOT}) {
            assertEquals("HIDDEN_MARK", harness.loreOf(slot, 0),
                    "槽位 " + slot + " 在 hideResults=true 时 lore 必须是隐藏行（HIDDEN_MARK）喵；"
                            + "实际=" + harness.loreOf(slot, 0)
                            + "（若是 LOADING_MARK 说明隐私开关被绕过、走了「加载中」分支）");
        }
    }

    /**
     * 反面：{@code showResults=true} 时三卡必须先写占位、再由异步回填数值（共 2 次），
     * 且确实查了统计。
     *
     * <p>这条与上一条成对，构成「是否尊重隐私开关」的双向锚点 —— 只有隐藏态那一侧时，
     * 一个「永远隐藏」的错误实现（把 showResults 恒当 false）也能通过。
     */
    @Test
    @DisplayName("对照：showResults=true → 三卡写 2 次（占位 + 回填），且确实查了统计")
    void visibleResultsRenderPlaceholderThenValues() {
        when(configManager.isDuelEnabled()).thenReturn(false);
        when(configManager.isAchievementsEnabled()).thenReturn(false);
        when(dataStore.getProfileSettingsReadOnly(TARGET_ID))
                .thenReturn(new PlayerDataStore.ProfileSettings()); // 默认 showResults=true
        PlayerDataStore.PlayerStats stats = mock(PlayerDataStore.PlayerStats.class);
        when(dataStore.getPlayerStats(TARGET_ID)).thenReturn(stats);
        // 异步段首行是 plugin.isEnabled() 守卫；mock 默认 false 会让整段静默返回。
        when(plugin.isEnabled()).thenReturn(true);

        Harness harness = new Harness(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> ignored = itemStackMock()) {
            bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
            stubCreateAndOpen(bukkit, harness);
            new ProfileGUI(plugin).open(viewer, TARGET_ID);
        }

        verify(dataStore, times(1)).getPlayerStats(TARGET_ID);
        assertEquals(2, harness.writesTo(WORDS_SLOT), "占位 1 次 + 回填 1 次");
        assertEquals(2, harness.writesTo(ACCURACY_SLOT));
        assertEquals(2, harness.writesTo(SESSIONS_SLOT));

        // T-3 反向断言：可见态回填后不得出现隐藏行。
        // 若有人把 `hideResults ? hiddenValue : loadingLine` 写成恒 hiddenValue，
        // 上一条（隐藏态必须 HIDDEN_MARK）会绿而这条会红 —— 两条合起来才钉死方向。
        for (int slot : new int[]{WORDS_SLOT, ACCURACY_SLOT, SESSIONS_SLOT}) {
            String first = harness.loreOf(slot, 0);
            assertEquals("OTHER_MARK", first,
                    "槽位 " + slot + " 可见态回填后 lore 应是数值行（OTHER_MARK）喵；实际="
                            + first + "（HIDDEN_MARK = 误按隐藏态渲染）");
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    /**
     * 一次 {@code open()} 的可观测状态：状态化 Inventory + 每槽写入次数 + 异步段执行情况。
     *
     * <p>调度器的两个入口都<b>就地同步</b>执行被提交的 Runnable（除非 {@code deferAsync}），
     * 于是「异步取数 → 回主线程回填」这条链在测试里是一次普通的方法调用。
     */
    private final class Harness {
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final boolean deferAsync;
        private final Map<Integer, Integer> writes = new HashMap<>();
        /**
         * T-3：槽位 → 最后一次写入的物品。只数次数看不出「写的是隐藏行还是加载中」
         * （两者在断言层面曾经完全同形），所以同时留下对象本身供 lore 内容断言。
         */
        private final Map<Integer, ItemStack> items = new HashMap<>();
        private Inventory inventory;
        private boolean asyncDispatched;
        private boolean asyncRan;

        private Harness(boolean deferAsync) {
            this.deferAsync = deferAsync;

            lenient().when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
                    .thenAnswer(inv -> {
                        asyncDispatched = true;
                        if (deferAsync) return null;
                        asyncRan = true;
                        inv.<Runnable>getArgument(1).run();
                        return null;
                    });
            lenient().when(scheduler.runTask(eq(plugin), any(Runnable.class)))
                    .thenAnswer(inv -> {
                        inv.<Runnable>getArgument(1).run();
                        return null;
                    });
        }

        private void record(int slot) {
            writes.merge(slot, 1, Integer::sum);
        }

        private int writesTo(int slot) {
            return writes.getOrDefault(slot, 0);
        }

        /**
         * T-3：读槽位上物品的第 {@code index} 行 lore。
         *
         * <p>{@code ItemStack} 是 {@code mockConstruction} 出来的 mock，生产代码
         * {@code createItem} 走的是「getItemMeta() → meta.setLore(...) → setItemMeta(meta)」，
         * 因此 {@link #itemStackMock()} 给每个 mock ItemStack 配了一个会记录 lore 的
         * mock ItemMeta（见该方法）；
         */
        private String loreOf(int slot, int index) {
            ItemStack item = items.get(slot);
            if (item == null) {
                return null;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return null;
            }
            List<String> lore = meta.getLore();
            return (lore != null && index < lore.size()) ? lore.get(index) : null;
        }
    }

    private void stubCreateAndOpen(MockedStatic<Bukkit> bukkit, Harness harness) {
        stubCreateAndOpen(bukkit, harness, true);
    }

    /**
     * 拦截 {@code Bukkit.createInventory}，返回状态化 Inventory mock，并把每次
     * {@code setItem} 记进 {@link Harness#writes}。
     *
     * @param wireViewer true = 让 {@code viewer.getOpenInventory().getTopInventory()}
     *                   返回这张界面（= 界面仍然开着，回填能过守卫）；false = 不接，
     *                   由用例自己铺「界面已换」的 stub
     */
    private void stubCreateAndOpen(MockedStatic<Bukkit> bukkit, Harness harness,
                                   boolean wireViewer) {
        bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    Inventory inv = mock(Inventory.class);
                    // Invocation 的第 0 个实参就是 ProfileGUI 挂上去的 holder，
                    // 下面要把它作为界面身份交给回填守卫。
                    InventoryHolder holder = invocation.getArgument(0);
                    harness.inventory = inv;
                    Map<Integer, ItemStack> slots = new HashMap<>();
                    when(inv.getSize()).thenReturn(PROFILE_SIZE);
                    when(inv.getItem(anyInt()))
                            .thenAnswer(i -> slots.get((Integer) i.getArgument(0)));
                    doAnswer(i -> {
                        int slot = i.getArgument(0);
                        ItemStack item = i.getArgument(1);
                        slots.put(slot, item);
                        harness.items.put(slot, item);
                        harness.record(slot);
                        return null;
                    }).when(inv).setItem(anyInt(), any());
                    // 回填守卫比对的是「viewer 当前打开界面的 holder」与「打开时持有的 holder」。
                    // 生产里两者是同一个实例（createInventory 挂上去的那个），所以这里必须让
                    // inv.getHolder() 返回同一实例，否则守卫 2 恒失败、回填永远不发生。
                    // 用 lenient：本组里只有真正走到回填的用例会读它，隐私/离线等用例
                    // 在前置判断就返回了 —— 那不是多余代码，是分组共用夹具。
                    lenient().when(inv.getHolder()).thenReturn(holder);
                    if (wireViewer) {
                        // 回填守卫要读 viewer 的当前界面 —— 让它看到的正是这一张。
                        lenient().when(viewer.isOnline()).thenReturn(true);
                        InventoryView view = mock(InventoryView.class);
                        lenient().when(view.getTopInventory()).thenReturn(inv);
                        lenient().when(viewer.getOpenInventory()).thenReturn(view);
                    }
                    return inv;
                });
    }

    /**
     * ItemStack 构造拦截：纯 JVM 无 RegistryAccess，且 {@code clone()} 必须返回自身。
     *
     * <p>T-3：生产代码的 {@code createItem} 走
     * 「{@code getItemMeta()} → {@code meta.setLore(...)} → {@code item.setItemMeta(meta)}」。
     * 纯 mock 的 ItemStack 默认 {@code getItemMeta()} 返回 null，于是 {@code setLore}
     * 永远不被调用，测试无法断言「写进 lore 的到底是隐藏行还是加载中」。
     * 这里为每个 ItemStack mock 配一个会记录 lore 的 ItemMeta mock：
     * 首次 {@code getItemMeta()} 惰性创建（让 createItem 的 null 检查通过），
     * {@code setItemMeta(meta)} 把它挂回对应 ItemStack，{@code getLore()} 回放已写入的列表。
     */
    private static MockedConstruction<ItemStack> itemStackMock() {
        Map<ItemStack, ItemMeta> metas = new IdentityHashMap<>();
        return mockConstruction(ItemStack.class,
                withSettings().defaultAnswer(invocation -> {
                    String method = invocation.getMethod().getName();
                    // getMock() 在 Map 的泛型推导里是 Object（Mockito 的泛型方法无法在此推断），
                    // 显式转型避免 Map<ItemStack, ...> 的泛型不匹配。
                    ItemStack self = (ItemStack) invocation.getMock();
                    if ("clone".equals(method)) {
                        return self;
                    }
                    if ("getItemMeta".equals(method)) {
                        return metas.computeIfAbsent(self, k -> recordingItemMeta());
                    }
                    if ("setItemMeta".equals(method)) {
                        metas.put(self, invocation.getArgument(0));
                        return null;
                    }
                    return Answers.RETURNS_DEFAULTS.answer(invocation);
                }));
    }

    /** lore 可读的 ItemMeta mock（只实现 createItem 用到的那几个方法）。 */
    private static ItemMeta recordingItemMeta() {
        ItemMeta meta = mock(ItemMeta.class);
        AtomicReference<List<String>> lore = new AtomicReference<>();
        lenient().when(meta.getLore()).thenAnswer(i -> lore.get());
        lenient().doAnswer(i -> {
            lore.set(i.getArgument(0));
            return null;
        }).when(meta).setLore(any());
        return meta;
    }
}
