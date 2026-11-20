package com.letslearnenglish.minecraftplugin.core.onboarding;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import com.letslearnenglish.minecraftplugin.listener.PlayerJoinListener;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 31 轮：「玩家第一次进服的前 5 分钟」—— 新手引导状态机与进服时间线的行为验收。
 *
 * <h2>为什么按「时间线」而不是「按方法」组织</h2>
 * <p>{@code OnboardingManager} 行覆盖 4%，但真正要钉的不是它的每一行，而是
 * <b>进服那一刻的编排顺序</b>与<b>状态机在每个入口上的行为</b>：
 * <pre>
 *   PlayerJoinEvent（主线程）
 *    ├─ loadProfileSettings            ← 同步 SELECT #1（R18-M1 论证必须留）
 *    ├─ 派发异步预热（统计 / 成就）
 *    ├─ cachePlayerName
 *    ├─ 60L 更新提示 / 首进 40L welcome×3
 *    ├─ OnboardingManager.handleJoin   ← 同步 SELECT #2（本类主角）
 *    │   ├─ step=0 + hasPlayedBefore → 静默回填 3（零打扰）
 *    │   ├─ step=0 + 首进 → 写 1 + 发手册 + 40L join-intro
 *    │   └─ step=1/2 → 当日提醒（受玩家侧总开关门）+ 补书
 *    ├─ sendJoinDailyReminderIfEnabled（首进当天抑制）
 *    ├─ duel 离线通知 / social 鼓励 / season 补发
 *    └─ 派发异步每日挑战分配
 * </pre>
 *
 * <h2>本类钉住的三件事</h2>
 * <ol>
 *   <li><b>状态机单调性</b>：{@code advanceStep} 只进不退；四个入口
 *       （handleJoin / interceptQuizStart / onTutorialSessionComplete / onProfileOpened）
 *       各自只认自己那一个 step 值 —— 顺序刚性，无法跳步；</li>
 *   <li><b>存档降级</b>：读失败必须<b>不写缓存</b>且不推进（否则一次 DB 抖动把
 *       在途引导钉死）；越界值（负数 / &gt;3）必须被当成「已完成」而不是「未开始」
 *       —— 宁可漏引导，不可对老号反复打扰；</li>
 *   <li><b>join→quit→join 重新初始化</b>：{@code unloadPlayer} 清两张 map，
 *       重进必须重新走一遍读库分流。</li>
 * </ol>
 *
 * <h2>本类刻意<b>不</b>断言为「正确」的两处（已在轮次报告里记为缺陷）</h2>
 * <ul>
 *   <li>{@link JoinQuitRejoin#relogSameDayRepeatsReminder()} —— 进服提醒的去重 map
 *       在 quit 时被清，同一自然日重登会重复收到提醒；</li>
 *   <li>{@link FirstFiveMinutesTimeline#defectHandleJoinHasNoExceptionBoundary()} ——
 *       {@code handleJoin} 在 {@code onPlayerJoin} 里没有 try/catch，一次异常会静默
 *       跳过其后的每日提醒 / 激励提示 / 赛季补发 / 每日挑战分配。</li>
 * </ul>
 * <p>两处都写成「钉住当前行为」：<b>一旦有人修好，这两条会立刻红，逼着改动者
 * 回来更新说明</b>。这是本仓既有的「残余窗口不掩盖」姿态
 * （见 {@code PlayerJoinAsyncLoadTest#profileLoadFailureStillAllowsPreload}）。
 */
@DisplayName("第31轮：新手引导 —— 前 5 分钟时间线与状态机")
class OnboardingFirstFiveMinutesTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000b0031");

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration mainConfig;
    private DatabaseManager databaseManager;
    private PlayerDataStore dataStore;
    private WordLearningManager wordLearningManager;
    private MessageUtil messageUtil;
    private Player player;
    private PlayerInventory inventory;

    /** 捕获到的 runTaskLater 任务（首进 intro 是 40L 延迟，必须能手动触发）。 */
    private final List<Runnable> delayedTasks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        mainConfig = mock(FileConfiguration.class);
        databaseManager = mock(DatabaseManager.class);
        dataStore = mock(PlayerDataStore.class);
        wordLearningManager = mock(WordLearningManager.class);
        messageUtil = mock(MessageUtil.class);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getWordLearningManager()).thenReturn(wordLearningManager);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        // NamespacedKey(plugin, key) 会读 plugin.getName() 并做小写化 —— 必须给出合法命名空间。
        when(plugin.getName()).thenReturn("letslearnenglish");
        // 构造期 getServer() 为 null → 跳过自注册（纯 JVM 单测的既有姿态）。
        when(plugin.getServer()).thenReturn(null);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("OnboardingTest"));

        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(configManager.getMessageConfig(anyString())).thenReturn(mock(FileConfiguration.class));
        // 默认：引导开启 + 五项子开关取「接线前默认值」（与 config.yml 出厂值一致）。
        when(configManager.isOnboardingEnabled()).thenReturn(true);
        when(mainConfig.getInt(eq("onboarding.session-words"), eq(3))).thenReturn(3);
        when(mainConfig.getBoolean(eq("onboarding.give-guide-book"), eq(true))).thenReturn(true);
        when(mainConfig.getBoolean(eq("onboarding.reminder-on-join"), eq(true))).thenReturn(true);
        when(mainConfig.getBoolean(eq("onboarding.require-profile-step"), eq(true))).thenReturn(true);
        when(mainConfig.getBoolean(eq("onboarding.remove-book-on-complete"), eq(true))).thenReturn(true);

        when(player.getUniqueId()).thenReturn(ID);
        when(player.getName()).thenReturn("Newbie");
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        // 背包查询是引导路径上唯一的集合访问：不给空数组会 NPE（夹具问题，不是产品缺陷）。
        when(inventory.getContents()).thenReturn(new ItemStack[0]);
        when(inventory.getItemInOffHand()).thenReturn(null);
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<Integer, ItemStack>());

        // step=1/2 分支会读玩家侧提醒总开关（handleJoin:153）。缓存 miss 时
        // getProfileSettingsReadOnly 返回 fail-open 副本，但**mock** 默认返回 null ——
        // 不给这份默认桩，所有 step=1/2 用例都会在与引导无关的 NPE 上红。
        when(dataStore.getProfileSettingsReadOnly(ID))
                .thenReturn(new PlayerDataStore.ProfileSettings());

        // 文案：返回 key 本身，于是「发了哪条」可以直接由 player.sendMessage 断言读出。
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        delayedTasks.clear();
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private OnboardingManager newManager() {
        return new OnboardingManager(plugin);
    }

    /** DB 读面打桩：step + 是否读失败。 */
    private void dbSays(int step, boolean readFailed) {
        when(databaseManager.loadOnboardingStep(ID))
                .thenReturn(new DatabaseManager.OnboardingLoad(step, readFailed));
    }

    /** 玩家侧提醒总开关（handleJoin 的 step 1/2 分支会读）。 */
    private void remindersEnabled(boolean enabled) {
        PlayerDataStore.ProfileSettings settings = new PlayerDataStore.ProfileSettings();
        settings.setRemindersEnabled(enabled);
        when(dataStore.getProfileSettingsReadOnly(ID)).thenReturn(settings);
    }

    /**
     * 让 handleJoin 里的 runTaskLater(40L) 可被显式触发（首进 intro），
     * 同时把 getServer() 换成一个可用的 Server（构造期会走 registerEvents）。
     */
    private void installScheduler() {
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.getServer()).thenReturn(server);
        when(scheduler.runTaskLater(any(org.bukkit.plugin.Plugin.class), any(Runnable.class),
                anyLong())).thenAnswer(inv -> {
                    delayedTasks.add(inv.getArgument(1));
                    return null;
                });
    }

    private int lastStepWritten() {
        var invocations = org.mockito.Mockito.mockingDetails(databaseManager).getInvocations()
                .stream()
                .filter(i -> "setOnboardingStep".equals(i.getMethod().getName()))
                .toList();
        assertFalse(invocations.isEmpty(), "前置：本用例应当至少写过一次 step 喵");
        return (Integer) invocations.get(invocations.size() - 1).getArgument(1);
    }

    /** 造一本带插件 PDC 标记的成书（PDC 去重判据的唯一真相源）。 */
    private ItemStack markedGuideBook() {
        ItemStack marked = mock(ItemStack.class);
        when(marked.getType()).thenReturn(Material.WRITTEN_BOOK);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(marked.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);
        return marked;
    }

    // ==================================================================
    // 组 1 —— handleJoin 的四条分支
    // ==================================================================

    @Nested
    @DisplayName("组 1 —— handleJoin 的四条分支")
    class HandleJoinBranches {

        /**
         * 引导关闭 = 零读库、零写库、零输出。
         *
         * <p>判别力：把 enabled 门挪到读库之后，本用例在 {@code never()} 处红 ——
         * 那正是「关掉模块仍在每次进服查一次 onboarding_step」的形态。
         */
        @Test
        @DisplayName("enabled=false → 一次库都不碰（门在读侧之前）")
        void disabledTouchesNothing() {
            when(configManager.isOnboardingEnabled()).thenReturn(false);

            newManager().handleJoin(player);

            verify(databaseManager, never()).loadOnboardingStep(any(UUID.class));
            verify(databaseManager, never()).setOnboardingStep(any(UUID.class), anyInt());
            verify(player, never()).sendMessage(anyString());
        }

        /**
         * 读失败 = 「重试下次进服」，<b>不得</b>折叠成 0。
         *
         * <p>为什么这条最重要：0 + 老号会走「静默回填 3」，于是<b>一次 DB 抖动就把在途
         * 引导写成已完成</b>（R4-07-eh-02 的原始缺陷）。这里把「读失败不推进」钉死：
         * 既不写缓存、也不写库、也不发任何提示。
         */
        @Test
        @DisplayName("读失败 → 不推进、不写库、不打扰（不得折叠成 0）")
        void readFailureDoesNotAdvance() {
            dbSays(0, true);

            OnboardingManager manager = newManager();
            manager.handleJoin(player);

            verify(databaseManager, never()).setOnboardingStep(any(UUID.class), anyInt());
            verify(player, never()).sendMessage(anyString());

            // 第二次读面：读失败绝不进 stepCache，否则后续 advanceStep 的单调门会连
            // 正常推进一起挡掉（javadoc 里 R4-07-eh-02 的那条约定）。
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            assertEquals(OnboardingManager.STEP_IN_PROGRESS, manager.getStep(ID));
        }

        /** 读抛 RuntimeException（连接自愈失败）等价于读失败，不得冒泡。 */
        @Test
        @DisplayName("读抛 RuntimeException → 收口成 WARNING，handleJoin 不抛")
        void readExceptionIsContained() {
            when(databaseManager.loadOnboardingStep(ID))
                    .thenThrow(new RuntimeException("Failed to get database connection"));

            newManager().handleJoin(player); // 不得抛出

            verify(databaseManager, never()).setOnboardingStep(any(UUID.class), anyInt());
        }

        /**
         * 老号（Bukkit 见过）+ step=0 → 静默回填 3，<b>零输出</b>。
         *
         * <p>这是「宁可漏引导，不误伤老号」的落地：插件装上之前就玩过的玩家不会
         * 突然收到一本手册和一串提示。
         */
        @Test
        @DisplayName("step=0 且老号 → 回填 3，零提示零发书")
        void returningPlayerIsBackfilledToComplete() {
            dbSays(0, false);
            when(player.hasPlayedBefore()).thenReturn(true);
            installScheduler();

            newManager().handleJoin(player);

            assertEquals(OnboardingManager.STEP_COMPLETE, lastStepWritten(),
                    "老号必须被静默回填到 3（终态）喵，否则每次进服都要重新引导一个熟手。");
            verify(player, never()).sendMessage(anyString());
            assertTrue(delayedTasks.isEmpty(), "老号不该收到 join-intro 的延迟任务喵");
            verify(inventory, never()).addItem(any(ItemStack[].class));
        }

        /**
         * 首次进服（hasPlayedBefore=false）+ step=0 → 写 1 + 发手册 + 40L intro。
         *
         * <p>三件事必须<b>都</b>发生：少写 1 → 下次进服又走一遍首进分支（重复发书）；
         * 少发书 → join-intro 让玩家右键一本不存在的书；少 intro → 玩家不知道有这本书。
         */
        @Test
        @DisplayName("step=0 且首进 → 写 1 + 发手册 + 40L intro")
        void firstJoinStartsTheGuide() {
            dbSays(0, false);
            when(player.hasPlayedBefore()).thenReturn(false);
            installScheduler();

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                newManager().handleJoin(player);
                assertEquals(OnboardingManager.STEP_IN_PROGRESS, lastStepWritten(),
                        "首进必须写 STEP_IN_PROGRESS(1) 喵。");
                verify(inventory).addItem(any(ItemStack[].class));
                assertEquals(1, delayedTasks.size(), "首进必须排一个 40L 的 join-intro 喵。");
                // 延迟任务里复查 isOnline，离线时不得发（秒退竞态）。
                delayedTasks.forEach(Runnable::run);
                verify(player).sendMessage("onboarding.join-intro");
            }
        }

        /**
         * {@code give-guide-book=false}：只发 intro、不发书。
         *
         * <p>这是配置语义的钉子（该键只管「首进是否发放」，不管补发）。
         * <b>注意</b>：join-intro 的文案仍然宣称「我在你的背包里放了一本」——
         * 这条文案与配置的分叉已在轮次报告里记为缺陷，本类不把它钉成「正确」。
         */
        @Test
        @DisplayName("give-guide-book=false → 不发书，但 intro 照发（文案与配置存在分叉，已记录）")
        void guideBookCanBeDisabled() {
            dbSays(0, false);
            when(player.hasPlayedBefore()).thenReturn(false);
            when(mainConfig.getBoolean(eq("onboarding.give-guide-book"), eq(true))).thenReturn(false);
            installScheduler();

            newManager().handleJoin(player);

            verify(inventory, never()).addItem(any(ItemStack[].class));
            assertEquals(1, delayedTasks.size());
            assertEquals(OnboardingManager.STEP_IN_PROGRESS, lastStepWritten(),
                    "不发书不等于不开启引导喵——step 仍必须推进到 1。");
        }

        /**
         * step=1/2 → 当日提醒 + 补书。
         *
         * <p>补书<b>不</b>受 give-guide-book 门：那个键的语义是「首进是否发放」。
         */
        @Test
        @DisplayName("step=1 → 发当日提醒 + 补书")
        void inProgressGetsReminderAndBook() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            remindersEnabled(true);

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                newManager().handleJoin(player);
            }

            verify(player).sendMessage("onboarding.reminder");
            verify(inventory).addItem(any(ItemStack[].class));
            verify(databaseManager, never()).setOnboardingStep(any(UUID.class), anyInt());
        }

        /**
         * 玩家侧提醒总开关关闭 → 一条提醒都不发，<b>且不占掉当日去重位</b>。
         *
         * <p>为什么「不占位」必须单独钉：门放在 {@code reminderSent.put} 之前才对。
         * 反过来写的话，关掉提醒的玩家今天打开开关也收不到第一条（R1/R4 的原始缺陷）。
         */
        @Test
        @DisplayName("提醒总开关关闭 → 不发也不占当日去重位（同日开回来后仍能收到）")
        void reminderSwitchOffDoesNotConsumeTheDailySlot() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            remindersEnabled(false);

            OnboardingManager manager = newManager();
            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                manager.handleJoin(player);
            }
            verify(player, never()).sendMessage("onboarding.reminder");

            // 同一实例、同一自然日：玩家在设置里把开关打开了，再进服（未 quit，故 cache 仍在）。
            remindersEnabled(true);
            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                manager.handleJoin(player);
            }
            verify(player, times(1)).sendMessage("onboarding.reminder");
        }

        /** 同一会话内重复 handleJoin（reload 重入 / 重复事件）同日只提醒一次。 */
        @Test
        @DisplayName("同会话内重复 handleJoin → 当日只提醒一次")
        void reminderIsOncePerDayWithinSession() {
            dbSays(OnboardingManager.STEP_SESSION_DONE, false);
            remindersEnabled(true);

            OnboardingManager manager = newManager();
            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                manager.handleJoin(player);
                manager.handleJoin(player);
            }

            verify(player, times(1)).sendMessage("onboarding.reminder");
        }

        /** reminder-on-join=false（服务器级闸）→ 不发提醒，但补书照旧。 */
        @Test
        @DisplayName("reminder-on-join=false → 不发提醒，补书照旧")
        void serverWideReminderSwitchOff() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            remindersEnabled(true);
            when(mainConfig.getBoolean(eq("onboarding.reminder-on-join"), eq(true))).thenReturn(false);

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                newManager().handleJoin(player);
            }

            verify(player, never()).sendMessage("onboarding.reminder");
            verify(inventory).addItem(any(ItemStack[].class));
        }
    }

    // ==================================================================
    // 组 2 —— 非法 / 异常存档值（降级）
    // ==================================================================

    @Nested
    @DisplayName("组 2 —— 存档越界值必须朝「已完成」降级")
    class IllegalPersistedValues {

        /**
         * {@code onboarding_step=99}（手改库 / 未来 step 被砍 / 导入脏数据）。
         *
         * <p>期望语义：按「已完成」处理 —— 零输出、不推进、不拦答题。
         * 反过来（当成未初始化）会让这些玩家每次进服都被发书 + 被拦截成 3 词培训，
         * 而且因为 {@code advanceStep} 的单调门（{@code to <= current}），
         * <b>永远修不好</b>：写 1 会被 99 挡掉，写 3 也会被挡掉。
         */
        @Test
        @DisplayName("step=99 → 零输出、不写库、不拦答题")
        void outOfRangeStepIsTreatedAsComplete() {
            dbSays(99, false);
            when(player.hasPlayedBefore()).thenReturn(true);

            OnboardingManager manager = newManager();
            manager.handleJoin(player);

            verify(player, never()).sendMessage(anyString());
            verify(databaseManager, never()).setOnboardingStep(any(UUID.class), anyInt());
            assertFalse(manager.interceptQuizStart(player, "beginner"),
                    "越界 step 不得拦截答题喵。");
        }

        /**
         * <b>单调门在越界值上的副作用（已记录的行为，不是我们想要的，但确实是现在的）。</b>
         *
         * <p>{@code advanceStep(to)} 的判据是 {@code to <= current}，所以 current=99 时
         * 任何正常推进（1/2/3）都被挡掉 —— 玩家即便真的想重新走一遍引导，也不可能。
         * 唯一自救是管理员 {@code /lea reset} 删掉 player_data 行（回到 step=0，
         * 届时又因为 hasPlayedBefore=true 被回填成 3 —— 仍然回不到引导）。
         *
         * <p>本用例钉的是当前行为，用来防止「越界值处理」被改成半修
         * （例如只在读侧钳位、但让写侧仍然放行），从而掩盖真正的设计问题。
         */
        @Test
        @DisplayName("单调门 + 越界值：之后任何推进都不生效（当前行为，已记录）")
        void monotonicGateBlocksRecoveryFromOutOfRangeValue() {
            dbSays(99, false);

            OnboardingManager manager = newManager();
            manager.onTutorialSessionComplete(player);
            manager.onProfileOpened(player);

            verify(databaseManager, never()).setOnboardingStep(any(UUID.class), anyInt());
            assertEquals(99, manager.getStep(ID), "缓存里的值不该被任何降级写回喵。");
        }

        /** 负数（-1）同样按「已完成」处理：不打扰、不拦截。 */
        @Test
        @DisplayName("step=-1 → 零输出、不拦答题")
        void negativeStepIsTreatedAsComplete() {
            dbSays(-1, false);
            when(player.hasPlayedBefore()).thenReturn(true);

            OnboardingManager manager = newManager();
            manager.handleJoin(player);

            verify(player, never()).sendMessage(anyString());
            assertFalse(manager.interceptQuizStart(player, "beginner"));
        }
    }

    // ==================================================================
    // 组 3 —— 状态机流转（四个入口各自的闸）
    // ==================================================================

    @Nested
    @DisplayName("组 3 —— 状态机：顺序刚性，无法跳步")
    class StateMachine {

        /**
         * 只有 step==1 才拦截答题。step=0（读失败/未初始化）、2、3、99 一律放行普通流程。
         *
         * <p>判别力：把判据从 {@code != STEP_IN_PROGRESS} 放宽成 {@code < STEP_COMPLETE}，
         * 所有非 1 的用例会红 —— 那正是「引导完成后仍被塞进 3 词培训」的形态。
         */
        @Test
        @DisplayName("interceptQuizStart 只在 step==1 时接管")
        void onlyInProgressIntercepts() {
            int[] steps = {0, OnboardingManager.STEP_SESSION_DONE,
                    OnboardingManager.STEP_COMPLETE, 99};
            for (int step : steps) {
                reset(databaseManager, wordLearningManager);
                dbSays(step, false);

                assertFalse(newManager().interceptQuizStart(player, "beginner"),
                        "step=" + step + " 不得拦截答题喵。");
                verify(wordLearningManager, never())
                        .startTutorialQuiz(any(Player.class), anyString(), anyInt());
            }
        }

        /** step==1 + 会话开成 → 接管（打开强制 choice 的 GUI 并返回 true）。 */
        @Test
        @DisplayName("step==1 → 接管开局：起 3 词教程 + 开 GUI + 发 session-start")
        void inProgressIntercepts() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            LearningSession session = mock(LearningSession.class);
            when(wordLearningManager.startTutorialQuiz(player, "beginner", 3)).thenReturn(session);

            try (MockedConstruction<WordLearningGUI> gui = mockConstruction(WordLearningGUI.class)) {
                assertTrue(newManager().interceptQuizStart(player, "beginner"));
                assertEquals(1, gui.constructed().size(), "接管成功必须开一张答题界面喵。");
                verify(gui.constructed().get(0)).openWithMessage(player, "beginner", "choice");
            }
            verify(player).sendMessage("onboarding.session-start");
        }

        /**
         * {@code startTutorialQuiz} 返回 null（word-system 关闭 / 已有会话 / 练习中）
         * → 仍返回 true（已接管、不回落），避免调用点再发一次提示 + 开空界面。
         */
        @Test
        @DisplayName("教程开局返回 null → 仍算已接管（不回落普通路径）")
        void nullSessionStillCountsAsHandled() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            when(wordLearningManager.startTutorialQuiz(player, "beginner", 3)).thenReturn(null);

            try (MockedConstruction<WordLearningGUI> gui = mockConstruction(WordLearningGUI.class)) {
                assertTrue(newManager().interceptQuizStart(player, "beginner"),
                        "null 会话必须仍返回 true 喵 —— 否则调用点会再开一次空 GUI。");
                assertTrue(gui.constructed().isEmpty());
            }
        }

        /** 教程开局抛「词库被删 / 无词可学」→ 回落 false，由调用点给标准提示。 */
        @Test
        @DisplayName("教程开局抛 ISE → 回落 false（调用点照常报错）")
        void failedStartFallsBack() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            when(wordLearningManager.startTutorialQuiz(player, "beginner", 3))
                    .thenThrow(new IllegalStateException("No words available"));

            assertFalse(newManager().interceptQuizStart(player, "beginner"));
        }

        /** step==1 → 教程打完进 2（require-profile-step 默认 true）。 */
        @Test
        @DisplayName("教程打完（require-profile-step=true）→ step 1 → 2 + step2-done")
        void tutorialCompleteAdvancesToSessionDone() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);

            newManager().onTutorialSessionComplete(player);

            assertEquals(OnboardingManager.STEP_SESSION_DONE, lastStepWritten());
            verify(player).sendMessage("onboarding.step2-done");
        }

        /** require-profile-step=false → 直达 3（收书 + complete）。 */
        @Test
        @DisplayName("require-profile-step=false → 教程打完直达 3 + complete")
        void tutorialCompleteSkipsProfileStepWhenConfigured() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            when(mainConfig.getBoolean(eq("onboarding.require-profile-step"), eq(true)))
                    .thenReturn(false);

            newManager().onTutorialSessionComplete(player);

            assertEquals(OnboardingManager.STEP_COMPLETE, lastStepWritten());
            verify(player).sendMessage("onboarding.complete");
        }

        /** 重复回调（幂等）：step 已是 2 时再回调不得再写库 / 再发文案。 */
        @Test
        @DisplayName("onTutorialSessionComplete 幂等：step≠1 时零行为")
        void tutorialCompleteIsIdempotent() {
            dbSays(OnboardingManager.STEP_SESSION_DONE, false);

            newManager().onTutorialSessionComplete(player);

            verify(databaseManager, never()).setOnboardingStep(any(UUID.class), anyInt());
            verify(player, never()).sendMessage(anyString());
        }

        /** 只有 step==2 时打开本人主页才收尾（step==1 提前打开不计数 → 无法跳步）。 */
        @Test
        @DisplayName("onProfileOpened 只在 step==2 时收尾；step==1 提前打开不计数")
        void profileOnlyFinishesAtSessionDone() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            newManager().onProfileOpened(player);
            verify(databaseManager, never()).setOnboardingStep(any(UUID.class), anyInt());

            reset(databaseManager);
            dbSays(OnboardingManager.STEP_SESSION_DONE, false);
            // finishGuide(playCompleteSound=true) 会走 SoundManager.playSound 的静态入口，
            // 它读 LetsLearnEnglish.getInstance()。这里把静态单例钉成 null（= 本 JVM 从未
            // onEnable 过），避免别的测试类留下的静态实例把一条无关的 NPE 喂进本用例。
            try (MockedStatic<LetsLearnEnglish> singleton = mockStatic(LetsLearnEnglish.class)) {
                singleton.when(LetsLearnEnglish::getInstance).thenReturn(null);
                newManager().onProfileOpened(player);
            }
            assertEquals(OnboardingManager.STEP_COMPLETE, lastStepWritten());
            verify(player).sendMessage("onboarding.complete");
        }

        /**
         * 引导关闭时打开主页必须<b>零查库</b>（E-P1-1）。
         *
         * <p>为什么：模块关闭时 stepCache 的唯一预热点 handleJoin 第一行就 return、
         * quit 还清空缓存 → getStep 恒 miss → 玩家<b>每次</b>打开本人主页都在主线程跑一次
         * {@code SELECT onboarding_step}，且发生在 createInventory/openInventory 之前。
         */
        @Test
        @DisplayName("enabled=false → onProfileOpened 零查库（E-P1-1）")
        void profileOpenDoesNotQueryWhenDisabled() {
            when(configManager.isOnboardingEnabled()).thenReturn(false);

            newManager().onProfileOpened(player);

            verify(databaseManager, never()).loadOnboardingStep(any(UUID.class));
        }

        /** step=3（终态）→ 任何入口都不再写库、不再输出。 */
        @Test
        @DisplayName("step=3（终态）→ 任何入口都不再写库")
        void completeIsTerminal() {
            dbSays(OnboardingManager.STEP_COMPLETE, false);

            OnboardingManager manager = newManager();
            manager.onTutorialSessionComplete(player);
            manager.onProfileOpened(player);

            verify(databaseManager, never()).setOnboardingStep(any(UUID.class), anyInt());
            verify(player, never()).sendMessage(anyString());
        }
    }

    // ==================================================================
    // 组 4 —— /le guide 命令面
    // ==================================================================

    @Nested
    @DisplayName("组 4 —— /le guide 的三个出口")
    class GuideCommand {

        @Test
        @DisplayName("引导关闭 → guide-disabled，不查库")
        void disabledSaysDisabled() {
            when(configManager.isOnboardingEnabled()).thenReturn(false);

            newManager().handleGuideCommand(player);

            verify(player).sendMessage("onboarding.guide-disabled");
            verify(databaseManager, never()).loadOnboardingStep(any(UUID.class));
        }

        @Test
        @DisplayName("step=3 → guide-done，不发书")
        void completeSaysDone() {
            dbSays(OnboardingManager.STEP_COMPLETE, false);

            newManager().handleGuideCommand(player);

            verify(player).sendMessage("onboarding.guide-done");
            verify(inventory, never()).addItem(any(ItemStack[].class));
        }

        /** step=1 → header + 三行步骤状态 + 补书（announce=true）。 */
        @Test
        @DisplayName("step=1 → header + 三行 pending + 补书")
        void inProgressShowsProgressAndGivesBook() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                newManager().handleGuideCommand(player);
            }

            verify(player).sendMessage("onboarding.guide-header");
            verify(player).sendMessage("onboarding.book.step1-pending");
            verify(player).sendMessage("onboarding.book.step2-pending");
            verify(player).sendMessage("onboarding.book.step3-pending");
            verify(inventory).addItem(any(ItemStack[].class));
        }

        /** step=2 → 前两行变 done（状态由同一个 sessionDone 判据驱动）。 */
        @Test
        @DisplayName("step=2 → 前两行显示 done")
        void sessionDoneShowsBothDone() {
            dbSays(OnboardingManager.STEP_SESSION_DONE, false);

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                newManager().handleGuideCommand(player);
            }

            verify(player).sendMessage("onboarding.book.step1-done");
            verify(player).sendMessage("onboarding.book.step2-done");
            verify(player).sendMessage("onboarding.book.step3-pending");
        }
    }

    // ==================================================================
    // 组 5 —— join → quit → join 重新初始化
    // ==================================================================

    @Nested
    @DisplayName("组 5 —— join→quit→join 重新初始化")
    class JoinQuitRejoin {

        /**
         * unloadPlayer 之后必须<b>重新读库</b>，不能拿上一轮的缓存。
         *
         * <p>判别力：把 {@code unloadPlayer} 里的 {@code stepCache.remove} 删掉，
         * 本用例在「第二次读到 3」处红 —— 玩家在别的子服完成了引导，回到本服
         * 仍然被当成 step=1（跨服共享台账的典型场景）。
         */
        @Test
        @DisplayName("quit 清缓存后重进 → 重新读库（跨服完成能立刻看到）")
        void rejoinRereadsFromDatabase() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);

            OnboardingManager manager = newManager();
            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                manager.handleJoin(player);
            }
            assertEquals(OnboardingManager.STEP_IN_PROGRESS, manager.getStep(ID));
            verify(databaseManager, times(1)).loadOnboardingStep(ID);

            // quit（PlayerJoinListener 的编排点）
            manager.unloadPlayer(ID);

            // 在别的子服/另一段时间完成了引导
            dbSays(OnboardingManager.STEP_COMPLETE, false);

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                manager.handleJoin(player);
            }
            assertEquals(OnboardingManager.STEP_COMPLETE, manager.getStep(ID),
                    "重进必须读到库里的新值喵 —— 缓存的旧值不得跨会话存活。");
            verify(databaseManager, times(2)).loadOnboardingStep(ID);
        }

        /**
         * quit 后缓存清空 → 每次 join 一条 SELECT。
         *
         * <p>这条同时是「join 主线程还剩几条同步 SELECT」的<b>计量</b>：
         * handleJoin 每次会话恰好一条，不多不少。
         */
        @Test
        @DisplayName("每会话恰好一条 SELECT（缓存不跨会话复用）")
        void oneSelectPerSession() {
            dbSays(OnboardingManager.STEP_COMPLETE, false);
            OnboardingManager manager = newManager();

            manager.handleJoin(player);
            manager.unloadPlayer(ID);
            manager.handleJoin(player);

            verify(databaseManager, times(2)).loadOnboardingStep(ID);
        }

        /**
         * <b>已记录的缺陷：同一自然日重登会重复收到进服提醒。</b>
         *
         * <p>现状：{@code reminderSent}（UUID → 最近发送日）在 {@code unloadPlayer} 里被清，
         * 而它的 javadoc 与 {@code handleJoin} 的注释都写着「进服提醒按时自然日去重」——
         * 去重实际只在<b>单次会话内</b>成立。对照本仓另一条同类通道
         * {@code LetsLearnEnglish.joinReminderSent}（每日学习提醒）明确注释
         * 「quit 不清、按日期懒判定」—— 两条提醒的去重寿命不一致。
         *
         * <p>玩家视角：一个引导没走完的新人，网络抖一下 / 换个子服 / 手滑重登，
         * 每次进服都会被再提醒一遍。而这恰恰是「前 5 分钟」里最容易被无限重复的一句话。
         *
         * <p>本用例钉住<b>当前</b>行为（times(2)）。修好之后它会变红 —— 那时请把期望
         * 改成 times(1)，并把本段说明改成「已修复」。
         */
        @Test
        @DisplayName("缺陷：同日重登重复发提醒（去重 map 在 quit 被清，当前行为）")
        void relogSameDayRepeatsReminder() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            remindersEnabled(true);

            OnboardingManager manager = newManager();
            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                manager.handleJoin(player);   // 第 1 次进服
                manager.unloadPlayer(ID);     // quit：reminderSent 被清
                manager.handleJoin(player);   // 同日第 2 次进服
            }

            verify(player, times(2)).sendMessage("onboarding.reminder");
        }

        /** 反向钉子：同一会话内不重登时去重仍生效（证明缺陷只在 quit 边界上）。 */
        @Test
        @DisplayName("对照：不 quit 时同日只发一次（缺陷被定位在 unloadPlayer 边界）")
        void noRelogNoDuplicate() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            remindersEnabled(true);

            OnboardingManager manager = newManager();
            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                manager.handleJoin(player);
                manager.handleJoin(player);
            }

            verify(player, times(1)).sendMessage("onboarding.reminder");
        }

        /** 重进时背包仍有标记书 → 不重复发放（扫背包去重，不依赖任何内存状态）。 */
        @Test
        @DisplayName("重进时已持有手册 → 静默不再发（PDC 去重）")
        void rejoinDoesNotDuplicateBook() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            remindersEnabled(true);
            // markedGuideBook() 自己内部就打了一批 when(...) 桩。若直接把它写在
            // when(inventory.getContents()).thenReturn(...) 的参数表达式里，外层桩尚未闭合
            // 就又开了一个内层桩 —— Mockito 会抛 UnfinishedStubbingException
            // （栈顶会指向 markedGuideBook 里的 when(marked.getType())）。
            // 必须先求出实体，再交给 thenReturn。
            ItemStack existing = markedGuideBook();
            when(inventory.getContents()).thenReturn(new ItemStack[] {existing});

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                newManager().handleJoin(player);
            }

            verify(inventory, never()).addItem(any(ItemStack[].class));
        }

        /** 副手挂着标记书同样算已持有（去重面覆盖副手）。 */
        @Test
        @DisplayName("副手持有手册 → 同样不重复发放")
        void offHandBookCounts() {
            dbSays(OnboardingManager.STEP_IN_PROGRESS, false);
            remindersEnabled(true);
            // 同上：先把打了桩的成书取出来，再喂给 thenReturn。
            ItemStack offHand = markedGuideBook();
            when(inventory.getItemInOffHand()).thenReturn(offHand);

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                newManager().handleJoin(player);
            }

            verify(inventory, never()).addItem(any(ItemStack[].class));
        }
    }

    // ==================================================================
    // 组 6 —— 前 5 分钟时间线（PlayerJoinListener 侧的编排边界）
    // ==================================================================

    @Nested
    @DisplayName("组 6 —— 进服时间线：同步/异步分界与异常边界")
    class FirstFiveMinutesTimeline {

        /**
         * <b>已记录的缺陷：{@code handleJoin} 在 {@code onPlayerJoin} 里没有异常边界。</b>
         *
         * <p>现状：{@code PlayerJoinListener.onPlayerJoin} 里那个
         * {@code try/catch (RuntimeException)} 的 catch 在「预热派发」之后就结束了
         * （注释写着 17-EH-F1：「getConnection() 的 RuntimeException 会被 fireEvent 吞掉，
         * 其后全部编排静默跳过」）。而 {@code handleJoin} 的调用点在这个 try <b>之后</b>。
         *
         * <p>于是引导模块里的任何 RuntimeException 会做两件事：
         * <ol>
         *   <li>逃出 {@code onPlayerJoin}，被 Bukkit 的 {@code fireEvent} 吞掉（只打印）；</li>
         *   <li>静默跳过其后的<b>全部</b>进服编排 —— 每日学习提醒、决斗离线通知、
         *       广场鼓励摘要、赛季奖励补发、每日挑战分配。</li>
         * </ol>
         *
         * <p>后果对玩家是可见的：本次会话<b>没有任何每日挑战</b>（要等下一次进服或答题触发
         * R-32 补分配），赛季欠款也不补（台账留 pending）。而整条被跳过时，控制台只有
         * 一条与真实原因无关的裸堆栈 —— 正是 17-EH-F1 当初要消灭的那种症状，换了个模块复发。
         *
         * <p>本用例钉住<b>当前</b>行为。修法是在 {@code handleJoin} 外面包一层
         * try/catch（收口成 WARNING，与预热段同姿态）；修好后 assertThrows 会红，
         * 请改成「不抛 + 后续编排照常执行」。
         */
        @Test
        @DisplayName("缺陷：handleJoin 抛异常会逃出 join，跳过其后全部编排（当前行为）")
        void defectHandleJoinHasNoExceptionBoundary() {
            SocialManager social = mock(SocialManager.class);
            SeasonManager season = mock(SeasonManager.class);
            AchievementManager achievements = mock(AchievementManager.class);
            DailyChallengeManager challenges = mock(DailyChallengeManager.class);
            OnboardingManager onboarding = mock(OnboardingManager.class);
            when(plugin.getSocialManager()).thenReturn(social);
            when(plugin.getSeasonManager()).thenReturn(season);
            when(plugin.getAchievementManager()).thenReturn(achievements);
            when(plugin.getDailyChallengeManager()).thenReturn(challenges);
            when(plugin.getOnboardingManager()).thenReturn(onboarding);
            when(plugin.getDuelManager()).thenReturn(null);
            when(plugin.isFullyEnabled()).thenReturn(true);
            when(dataStore.loadProfileSettings(ID)).thenReturn(true);
            when(dataStore.getCachedProfileSettings(ID))
                    .thenReturn(new PlayerDataStore.ProfileSettings());
            when(player.hasPlayedBefore()).thenReturn(true);

            Server server = mock(Server.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(server.getScheduler()).thenReturn(scheduler);
            when(plugin.getServer()).thenReturn(server);

            // 收集异步任务，稍后手动跑（每日挑战分配是异步派发的）。
            List<Runnable> async = new ArrayList<>();
            when(scheduler.runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class), any(Runnable.class)))
                    .thenAnswer(inv -> {
                        async.add(inv.getArgument(1));
                        return null;
                    });
            when(scheduler.runTaskLater(any(org.bukkit.plugin.Plugin.class), any(Runnable.class),
                    anyLong())).thenReturn(null);

            doThrow(new RuntimeException("onboarding exploded")).when(onboarding).handleJoin(player);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

                PlayerJoinListener listener = new PlayerJoinListener(plugin);
                PlayerJoinEvent event = new PlayerJoinEvent(player, "Newbie joined");

                assertThrows(RuntimeException.class, () -> listener.onPlayerJoin(event),
                        "当前行为：handleJoin 的异常没有边界，直接逃出 join 喵（这正是被记录的缺陷）。");
            }

            // 其后全部编排被静默跳过 —— 这是缺陷的后果，逐条钉住。
            verify(season, never()).claimPendingRewards(any(Player.class));
            verify(social, never()).notifyPendingCheers(any(Player.class));
            for (Runnable task : async) {
                task.run();
            }
            verify(challenges, never()).checkAndAssign(any(UUID.class));
        }

        /**
         * 时间线的<b>同步/异步分界</b>（源码级断言）。
         *
         * <p>{@code onPlayerJoin} 的主线程段里今天还剩<b>两条</b>同步 SELECT：
         * {@code loadProfileSettings}（R18-M1 论证必须留）与
         * {@code OnboardingManager.handleJoin} 内部的 {@code loadOnboardingStep}。
         * 其余全部是内存读或异步派发。
         *
         * <p>为什么这条也值得钉：R18-M1 把统计/成就搬走时，onboarding 的读是<b>后加的</b>
         * 第三条主线程序列 SELECT，而 {@code MainThreadIoContractTest} 的扫描目录只有
         * {@code gui/} 与 {@code listener/} —— {@code core/onboarding/} 不在其中，
         * 被调方法名 {@code handleJoin} 也不在 {@code DB_READ_METHODS} 里，
         * 所以那条 lint <b>看不见</b>这一跳。本用例把「还剩下什么」显式记下来。
         */
        @Test
        @DisplayName("时间线：主线程段仍有 loadProfileSettings + handleJoin 两条同步读")
        void timelineStillHasTwoSynchronousReads() throws IOException {
            String body = methodBody("listener/PlayerJoinListener.java", "public void onPlayerJoin(");

            int onboardingAt = body.indexOf("getOnboardingManager().handleJoin(");
            assertTrue(onboardingAt > 0,
                    "onPlayerJoin 里找不到 getOnboardingManager().handleJoin( 喵 —— "
                            + "引导接线被删/改名了？请先确认现状再同步本断言。");

            String mainThreadPart = body.substring(0, onboardingAt);
            assertTrue(mainThreadPart.contains("loadProfileSettings("),
                    "同步段必须仍有 loadProfileSettings 喵（提醒开关 fail-open，不能异步）。");

            int asyncAt = body.indexOf("runTaskAsynchronously(");
            assertTrue(asyncAt > 0, "找不到 runTaskAsynchronously( 喵。");
            assertTrue(onboardingAt > asyncAt,
                    "handleJoin 必须在预热异步段之后（同步段）喵 —— 它的输出是给玩家的即时提示。");

            // 每日挑战分配必须是异步的（R13-M1），且排在引导之后。
            int dailyAt = body.indexOf("checkAndAssign(");
            assertTrue(dailyAt > onboardingAt,
                    "每日挑战分配必须排在引导之后喵（教学先于日常任务）。");
        }

        /**
         * 时间线的<b>顺序</b>：首进当天抑制 join 每日提醒（D2 裁决：首屏不发 5 行文案）。
         *
         * <p>这条是纯源码断言：{@code hasPlayedBefore()} 必须在
         * {@code sendJoinDailyReminderIfEnabled} 之前出现，否则首进玩家会同时收到
         * welcome×3 + join-intro + 每日提醒（4~5 行堆叠）。
         */
        @Test
        @DisplayName("时间线：首进当天抑制 join 每日提醒（welcome×3 + intro 已够）")
        void firstJoinSuppressesDailyReminder() throws IOException {
            String body = methodBody("listener/PlayerJoinListener.java", "public void onPlayerJoin(");

            int onboardingAt = body.indexOf("getOnboardingManager().handleJoin(");
            int dailyReminderAt = body.indexOf("sendJoinDailyReminderIfEnabled(");
            assertTrue(dailyReminderAt > onboardingAt,
                    "join 每日提醒排在引导之后喵（引导会先发自己的 intro）。");

            // 锚「最近的那一处」hasPlayedBefore —— 用 lastIndexOf 而不是「前 400 字符里包含」：
            // 首进 welcome 块本身就有一个 if (!hasPlayedBefore())，宽松匹配会被它喂饱，
            // 于是「有人删掉提醒自己的门」也不会红（假绿）。
            int gateAt = body.lastIndexOf("hasPlayedBefore()", dailyReminderAt);
            assertTrue(gateAt > 0,
                    "join 每日提醒必须被 hasPlayedBefore() 门住喵 —— 否则首进当天玩家会收到"
                            + "welcome×3 + join-intro + 每日提醒，首屏 5 行堆叠。");
            assertTrue(dailyReminderAt - gateAt < 200,
                    "最近的 hasPlayedBefore() 离 sendJoinDailyReminderIfEnabled 有 "
                            + (dailyReminderAt - gateAt) + " 个字符，太远 —— 命中的八成是上面"
                            + "welcome 块的门，而不是提醒自己的门喵。请确认提醒确实被首进门住。");
        }
    }

    // ==================================================================
    // 源码读取工具（剥注释 + 按签名截方法体）
    // ==================================================================

    private static String methodBody(String relativePath, String signature) throws IOException {
        String stripped = stripComments(readSource(relativePath));
        int sigIndex = stripped.indexOf(signature);
        if (sigIndex < 0) {
            throw new AssertionError("源码中找不到方法签名「" + signature + "」喵 —— "
                    + "方法可能被改名/改签名，请同步本 lint（否则门禁会静默失效）。");
        }
        if (stripped.indexOf(signature, sigIndex + 1) >= 0) {
            throw new AssertionError("方法签名「" + signature + "」在源码中出现多次，无法唯一定位方法体喵。");
        }
        int bodyStart = stripped.indexOf('{', sigIndex + signature.length());
        if (bodyStart < 0) {
            throw new AssertionError("方法签名「" + signature + "」之后找不到左花括号喵。");
        }
        int close = findBlockEnd(stripped, bodyStart);
        if (close < 0) {
            throw new AssertionError("方法签名「" + signature + "」的花括号没有配对结束喵。");
        }
        return stripped.substring(bodyStart + 1, close);
    }

    private static int findBlockEnd(String s, int openBraceIdx) {
        int depth = 0;
        for (int i = openBraceIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 从 cwd / code-source 各自上溯找仓库根（不依赖 cwd），按相对路径定位源码。 */
    private static String readSource(String relativePath) throws IOException {
        List<Path> starts = new ArrayList<>();
        String cwd = System.getProperty("user.dir");
        if (cwd != null) {
            starts.add(Path.of(cwd).toAbsolutePath());
        }
        try {
            starts.add(Path.of(PlayerJoinListener.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath());
        } catch (Exception ignored) {
            // 只靠 cwd
        }
        for (Path start : starts) {
            for (Path cur = start; cur != null; cur = cur.getParent()) {
                Path direct = cur.resolve("src/main/java/com/letslearnenglish/minecraftplugin")
                        .resolve(relativePath);
                if (Files.isRegularFile(direct)) {
                    return Files.readString(direct, StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("找不到 " + relativePath + "（试过 " + starts + "）喵 —— "
                + "本断言是语义检查，找不到就必须红灯，不能 skip。");
    }

    /**
     * 剥注释 + 抹字符串字面量（保持长度不变，偏移不错位）。
     *
     * <p>不剥的话，本类与生产代码的注释里写满了
     * {@code getOnboardingManager().handleJoin(}、{@code checkAndAssign(} 等字样，
     * 断言会被注释喂饱 —— 那是「假绿」。
     */
    private static String stripComments(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j < n && !(source.charAt(j) == '*' && j + 1 < n
                        && source.charAt(j + 1) == '/')) {
                    j++;
                }
                j = (j < n) ? Math.min(n, j + 2) : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '"' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                blank(out, i, Math.min(j, n));
                i = j;
                continue;
            }

            i++;
        }
        return new String(out);
    }

    private static void blank(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
    }
}
