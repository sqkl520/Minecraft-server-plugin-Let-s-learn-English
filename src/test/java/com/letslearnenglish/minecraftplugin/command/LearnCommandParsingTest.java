package com.letslearnenglish.minecraftplugin.command;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import com.letslearnenglish.minecraftplugin.core.manager.CheckInManager;
import com.letslearnenglish.minecraftplugin.core.onboarding.OnboardingManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.RankingMode;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;
import com.letslearnenglish.minecraftplugin.core.word.ReviewScheduler;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.ChallengeCompleteGUI;
import com.letslearnenglish.minecraftplugin.gui.ChatHistoryGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProfileGUI;
import com.letslearnenglish.minecraftplugin.gui.ScenarioSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.WordBankSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import com.letslearnenglish.minecraftplugin.gui.WrongBookGUI;
import com.letslearnenglish.minecraftplugin.listener.ChatScenarioListener;
import com.letslearnenglish.minecraftplugin.listener.SocialListener;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code LearnCommand}（617 行、覆盖 2%）的<b>参数解析与分派</b>测试。
 *
 * <h2>为什么测这个面</h2>
 * {@code /le} 是玩家最常敲的命令。它的 617 行里绝大部分是 Bukkit 胶水（开 GUI、发消息、
 * 查 Manager），真正会出错、且出错后玩家<b>立刻</b>有感的是三件事：
 * <ol>
 *   <li><b>子命令分派表</b>：{@code SUBCOMMANDS}（tab 补全候选）与 {@code onCommand} 的
 *       switch 是<b>两份独立维护的清单</b>。任一份漂移的结果都是「补全里能看见、敲了却
 *       报未知命令」或反向的幽灵子命令。这里同时钉<b>结构</b>（源码 lint：两份清单集合
 *       相等）与<b>行为</b>（逐个真调 onCommand，断言没有一个落到 default）。</li>
 *   <li><b>参数校验</b>：{@code /le word <难度> <模式>} 的三种拒绝语义
 *       （未知模式 / 已知但被关 / 难度不存在）与 {@code /le duel} 的五个动词。</li>
 *   <li><b>tab 补全 ↔ onCommand 一致性</b>：补全给出的每个 token 都必须能被 onCommand
 *       吃下，否则玩家按 Tab 得到一个「按了会报错」的候选。</li>
 * </ol>
 *
 * <h2>测法：抓「发出去的 i18n key + 占位符实参」而不是抓 GUI</h2>
 * 每个分支的终点要么是 {@code player.sendMessage(MessageUtil.getPlayerMessage(...))}，
 * 要么是一次 Manager 调用。前者用 {@link MessageUtil} 的 Answer 把 <b>key 与占位符实参</b>
 * 记进 {@link #keysSent} / {@link #placeholdersByKey}，后者用 {@code verify(...)}。
 * GUI 一律 {@code mockConstruction} 顶掉 —— 本类不测渲染。
 *
 * <p><b>为什么必须记实参</b>：{@code formatCoins} 那类缺陷的形态是「key 对了、数字错了」
 * （{@code 0.20} 渲染成 {@code 0}）。只断言 key 存在的测试对它完全无感。
 *
 * <h2>本类封死的变异（每条都指向一个具体断言）</h2>
 * <ol>
 *   <li>删掉 switch 里任一 {@code case}（或往 {@code SUBCOMMANDS} 里加一个没有 case 的
 *       token）→ {@link Dispatch#everyAdvertisedSubcommandIsDispatched} 与
 *       {@link SubcommandTableLint} 同时红；</li>
 *   <li>把 {@code args[0].toLowerCase()} 改成 {@code args[0]} → {@link Dispatch#subcommandIsCaseInsensitive} 红；</li>
 *   <li>把 {@code handleSquareCommand} 里 {@code "alltime"} 与 {@code "all"} 两个
 *       {@code RankingMode} 对调 → {@link SquareRouting} 红（这是最经典的错映）；</li>
 *   <li>把 {@code resolveDifficulty} 的 {@code toLowerCase(...).equals(...)} 改成
 *       {@code equals} → {@link WordValidation#difficultyMatchingIsCaseInsensitive} 红；</li>
 *   <li>把 {@code WordModes.isKnownMode} 与 {@code isEnabled} 两条判定合并成一条 →
 *       {@code word.invalid-mode} 与 {@code word.mode-disabled} 两条断言必有一条红；</li>
 *   <li>把 {@code handleDuelCommand} 的 {@code args.length < 2} 改成 {@code < 1} →
 *       {@link DuelRouting} 红（bare {@code /le duel} 会走到取玩家名的分支）；</li>
 *   <li>把 R-40 的「今日已展示」去重删掉 → {@link ChallengeCelebration#secondIdenticalRunDoesNotReopen} 红；</li>
 *   <li>把 {@code formatCoins} 的 {@code %.2f} 分支换成 {@code (long)} 强转 →
 *       {@link CheckinPayout#cappedPayoutReportsCentsNotTruncatedInt} 红；
 *       把整数分支换成 {@code %.2f} →
 *       {@link CheckinPayout#integerPayoutDropsDecimals} 红；</li>
 *   <li>把行为挑战的 {@code Math.min(progress, target)} clamp 删掉 →
 *       {@link ChallengeCelebration#behavioralChallengesRouteThreeStates} 红；</li>
 *   <li>把 {@code handleClassCommand} 的查库提到 {@code runTaskAsynchronously} 之外 →
 *       {@link MainThreadContract#classCommandKeepsIoOffMainThread} 红；</li>
 *   <li>把 {@code handleChallengeCommand} 的四次取数挪回 {@code runTaskAsynchronously}
 *       之前 → {@link MainThreadContract#challengeCommandHasNoMainThreadIo} 红，
 *       且 {@code MainThreadIoContractTest} 同时红（R31 后它已扫 {@code command/}）。</li>
 * </ol>
 *
 * <h2>已知缺陷（{@code @Disabled} 锁定，只报告不修）</h2>
 * 当前<b>零条</b>。D7（多余参数被静默忽略）已修并转为正向契约：
 * {@link WordValidation#extraArgumentsAreNotSilentlyIgnored}、
 * {@link WordValidation#extraArgumentsWinOverSemanticValidation}、
 * {@link DuelRouting#extraArgumentsAreNotSilentlyIgnored}。
 * <p><b>R31 已修</b>：{@code /le challenge} 的主线程同步查库（原 3 条 + 第 4 条
 * {@code getTodayChallenges}）已异步化；{@code command/} 也已加进
 * {@code MainThreadIoContractTest.SCAN_DIRS}，门面方法名同时进了 {@code DB_READ_METHODS}。
 * 本类<b>不改 src/main</b> 的约束随该轮解除（命令层修复就在本轮）。
 */
@DisplayName("LearnCommand — 子命令分派与参数解析")
class LearnCommandParsingTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-4000-8000-00000000c0de");
    private static final UUID BOB_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b0");

    /** 被测源码（相对仓库根；surefire 的工作目录就是项目根）。 */
    private static final Path SOURCE = Path.of(
            "src/main/java/com/letslearnenglish/minecraftplugin/command/LearnCommand.java");
    private static final Path ZH_YML = Path.of("src/main/resources/messages/zh.yml");
    private static final Path EN_YML = Path.of("src/main/resources/messages/en.yml");

    /** 分派表在源码里的字面量 token（{@code SUBCOMMANDS} 那份），由 lint 组读出。 */
    private static List<String> subcommandTokens = List.of();

    // ---- 被测依赖 ----------------------------------------------------
    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration mainConfig;
    private MessageUtil messageUtil;
    private Player player;
    private Server server;
    private Command command;

    private GuiNavigationManager nav;
    private DuelManager duelManager;
    private WordManagerImpl wordManager;
    private ReviewScheduler reviewScheduler;
    private WordLearningManager wordLearningManager;
    private AIChatManager aiChatManager;
    private ChatScenarioListener chatScenarioListener;
    private SocialListener socialListener;
    private DailyChallengeManager dailyChallengeManager;
    private CheckInManager checkInManager;
    private OnboardingManager onboardingManager;
    private SocialManager socialManager;
    private PlayerDataStore playerDataStore;

    /** 本次用例里 LearnCommand 实际取过的 i18n key（顺序 = 发送顺序）。 */
    private final List<String> keysSent = new ArrayList<>();

    /**
     * key → 该 key 收到的占位符实参（{@code name, value, name, value...} 原文）。
     *
     * <p>为什么必须记这个而不是只记 key：{@code R-16} 的缺陷形态是「key 对了、数字错了」
     * —— {@code checkin.success} 照发，只是把 {@code 0.20} 渲染成 {@code 0}。只断言 key
     * 存在的测试对这个变异<b>完全无感</b>（{@code formatCoins} 的 {@code %.2f} 分支被换成
     * {@code (long)} 强转时仍然发同一条 key）。要杀掉它，只能读实参。
     */
    private final java.util.Map<String, String[]> placeholdersByKey = new java.util.LinkedHashMap<>();

    private MockedStatic<Bukkit> bukkit;
    /** {@code Bukkit.getOnlinePlayers()} 的可变替身（见 setUp 里的理由）。 */
    private List<Player> onlinePlayers;
    private MockedConstruction<MainMenu> mainMenuCtor;
    private MockedConstruction<ProfileGUI> profileGuiCtor;
    private MockedConstruction<WordBankSelectGUI> wordBankCtor;
    private MockedConstruction<ScenarioSelectGUI> scenarioSelectCtor;
    private MockedConstruction<ChatHistoryGUI> chatHistoryCtor;
    private MockedConstruction<WrongBookGUI> wrongBookCtor;
    private MockedConstruction<WordLearningGUI> wordLearningCtor;
    private MockedConstruction<ChallengeCompleteGUI> challengeCompleteCtor;

    // ==================================================================
    //  夹具
    // ==================================================================

    @BeforeEach
    void setUp() throws IOException {
        keysSent.clear();
        placeholdersByKey.clear();
        if (subcommandTokens.isEmpty()) {
            subcommandTokens = advertisedSubcommands(readSource());
        }

        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        mainConfig = new YamlConfiguration();
        messageUtil = mock(MessageUtil.class);
        player = mock(Player.class);
        server = mock(Server.class);
        command = mock(Command.class);

        lenient().when(plugin.isFullyEnabled()).thenReturn(true);
        lenient().when(plugin.getConfigManager()).thenReturn(configManager);
        lenient().when(configManager.getMainConfig()).thenReturn(mainConfig);
        lenient().when(plugin.getMessageUtil()).thenReturn(messageUtil);
        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);
        lenient().when(player.getName()).thenReturn("Alice");
        lenient().when(player.isOnline()).thenReturn(true);

        // 核心手法：把 key 本身当返回值记下来。这样「走了哪个分支」是可断言的，
        // 而不依赖 mock 的默认空串（默认空串会让所有 sendMessage 断言互相混淆）。
        lenient().when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    // ⚠️ 可变参数必须走 getRawArguments()：getArguments() 会把 String...
                    // 摊平成 [player, key, "a", "1", ...]，旧的 getArgument(2) 因此既会
                    // 越界（无占位符时长度只有 2）又会 ClassCastException（索引 2 是单个
                    // String）。这正是本类 66~69 个 Error 的唯一根因，详见 placeholdersFrom。
                    String[] placeholders = placeholdersFrom(invocation.getRawArguments(), 2);
                    keysSent.add(key);
                    placeholdersByKey.put(key, placeholders);
                    return "MSG:" + key;
                });
        // 控制台路径走的是非玩家版 getMessage(String, String...)（onCommand 第一行）。
        // 不桩它的话该分支返回 null → sendMessage(null)，断言无从下手。
        // 同 getPlayerMessage：占位符是可变参数，必须走 getRawArguments()，见 placeholdersFrom。
        lenient().when(messageUtil.getMessage(anyString(), any(String[].class)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    keysSent.add(key);
                    // 这个重载的实参表只有一个固定参数（key），其余全是占位符，
                    // 所以取样起点是 1 不是 2。
                    placeholdersByKey.put(key, placeholdersFrom(invocation.getRawArguments(), 1));
                    return "MSG:" + key;
                });

        // LearnCommand#getBehavioralName 是**唯一**绕开 MessageUtil、直接
        // `configManager.getMessageConfig(lang).getString(key)` 的地方，且那行没有
        // null 守卫 —— mock 默认返回 null 会 NPE。给一份空配置（不是 null），
        // 让「缺键回落 key 名」那条 R-86d 契约成为默认行为，需要真文案的用例自己覆盖。
        lenient().when(configManager.getMessageConfig(anyString()))
                .thenReturn(new YamlConfiguration());

        // ---- Manager 全量替身（每个用例只覆盖自己要的那几格） ----
        nav = mock(GuiNavigationManager.class);
        duelManager = mock(DuelManager.class);
        wordManager = mock(WordManagerImpl.class);
        reviewScheduler = mock(ReviewScheduler.class);
        wordLearningManager = mock(WordLearningManager.class);
        aiChatManager = mock(AIChatManager.class);
        chatScenarioListener = mock(ChatScenarioListener.class);
        socialListener = mock(SocialListener.class);
        dailyChallengeManager = mock(DailyChallengeManager.class);
        checkInManager = mock(CheckInManager.class);
        onboardingManager = mock(OnboardingManager.class);
        socialManager = mock(SocialManager.class);
        playerDataStore = mock(PlayerDataStore.class);

        lenient().when(plugin.getGuiNavigationManager()).thenReturn(nav);
        lenient().when(plugin.getDuelManager()).thenReturn(duelManager);
        lenient().when(plugin.getWordManager()).thenReturn(wordManager);
        lenient().when(plugin.getWordLearningManager()).thenReturn(wordLearningManager);
        lenient().when(plugin.getAIChatManager()).thenReturn(aiChatManager);
        lenient().when(plugin.getChatScenarioListener()).thenReturn(chatScenarioListener);
        lenient().when(plugin.getSocialListener()).thenReturn(socialListener);
        lenient().when(plugin.getDailyChallengeManager()).thenReturn(dailyChallengeManager);
        lenient().when(plugin.getCheckInManager()).thenReturn(checkInManager);
        lenient().when(plugin.getOnboardingManager()).thenReturn(onboardingManager);
        lenient().when(plugin.getSocialManager()).thenReturn(socialManager);
        lenient().when(plugin.getPlayerDataStore()).thenReturn(playerDataStore);
        // 赛季默认不可用（null → season.disabled）；需要正向路径的用例自己换。
        lenient().when(plugin.getSeasonManager()).thenReturn(null);

        // 模块门默认全开（与 config.yml 出厂一致）
        lenient().when(configManager.isWordSystemEnabled()).thenReturn(true);
        lenient().when(configManager.isAIChatEnabled()).thenReturn(true);
        lenient().when(configManager.isDuelEnabled()).thenReturn(true);
        lenient().when(configManager.isCheckinEnabled()).thenReturn(true);
        lenient().when(configManager.isDailyChallengesEnabled()).thenReturn(true);
        lenient().when(configManager.isLeaderboardEnabled()).thenReturn(true);

        // 词库 / 复习 / 答题
        lenient().when(wordManager.getAvailableDifficulties())
                .thenReturn(new LinkedHashSet<>(List.of("beginner", "intermediate", "advanced")));
        lenient().when(wordManager.getReviewScheduler()).thenReturn(reviewScheduler);
        lenient().when(reviewScheduler.getDueReviewWords(any(UUID.class))).thenReturn(List.of());
        lenient().when(wordLearningManager.startQuiz(any(Player.class), anyString(), anyString()))
                .thenReturn(mock(LearningSession.class));
        lenient().when(wordLearningManager.startReviewQuiz(any(Player.class))).thenReturn(null);
        lenient().when(wordLearningManager.hasActiveQuiz(any(UUID.class))).thenReturn(false);
        lenient().when(wordLearningManager.isPracticingWord(any(UUID.class))).thenReturn(false);

        // AI / 场景
        lenient().when(aiChatManager.hasActiveConversation(any(Player.class))).thenReturn(false);
        lenient().when(aiChatManager.isAvailable()).thenReturn(true);
        lenient().when(aiChatManager.getScenarioConfigs(any(Player.class)))
                .thenReturn(scenarioMap());
        lenient().when(aiChatManager.getAvailableScenarios(any(Player.class))).thenReturn(List.of());
        lenient().when(aiChatManager.getMaxPromptLength()).thenReturn(1000);
        lenient().when(aiChatManager.getMaxPersonalScenarios()).thenReturn(5);
        lenient().when(chatScenarioListener.isInScenarioMode(any(Player.class))).thenReturn(false);

        // 决斗 / 签到 / 挑战
        lenient().when(duelManager.isInDuel(any(UUID.class))).thenReturn(false);
        lenient().when(dailyChallengeManager.getTodayWordProgress(any(Player.class)))
                .thenReturn(new int[] {0, 0});
        lenient().when(dailyChallengeManager.getTodayWordDetails(any(Player.class)))
                .thenReturn(List.of());
        lenient().when(dailyChallengeManager.getTodayChallenges(any(UUID.class)))
                .thenReturn(List.of());
        lenient().when(checkInManager.checkIn(any(Player.class)))
                .thenReturn(new CheckInManager.CheckInResult(false, 0, 0, false));
        lenient().when(onboardingManager.interceptQuizStart(any(Player.class), anyString()))
                .thenReturn(false);

        // 隐私 / 玩家查询
        lenient().when(playerDataStore.getProfileSettingsReadOnly(any(UUID.class)))
                .thenReturn(new PlayerDataStore.ProfileSettings());
        lenient().when(server.getPlayer(anyString())).thenReturn(null);

        // Bukkit 静态入口：tab 补全用 Bukkit.getOnlinePlayers()（与 AdminCommand 的
        // plugin.getServer() 不同），纯 JVM 下不拦就是 NPE。
        // 用<b>可变列表 + thenAnswer</b> 而不是 thenReturn(固定值)：MockedStatic 上重复
        // `when(...)` 覆盖同一方法虽然可行，但一旦某个用例漏做覆盖，读到的就是上一个用例
        // 的残留（毕业顺序依赖、极难排查）。可变列表让「在线玩家」在任何时刻都可改。
        onlinePlayers = new ArrayList<>(List.of(player));
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(invocation -> onlinePlayers);

        // R31：调度器必须<b>内联执行</b>，不能是"收下 Runnable 就扔掉"的空 mock。
        // handleChallengeCommand 已改成「主线程 → runTaskAsynchronously 取数 → runTask 回主线程渲染」，
        // 空 mock 会让两段 lambda 都不执行：命令返回 true 但一条消息都没发，
        // 于是每一条 /le challenge 断言都会因「keysSent 为空」而假红（实测 8 条）。
        // 内联执行把两段都跑在同一个线程上 —— 对本类要观测的东西（发出哪些 key、
        // 哪些 GUI 被构造）与异步无关，而这正是「行为不变、只换线程」的等价性检查。
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        lenient().doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class), any(Runnable.class));
        lenient().doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runTask(any(org.bukkit.plugin.Plugin.class), any(Runnable.class));
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

        // GUI 一律顶掉：本类不测渲染，任何真构造都会拖进 ItemStack/注册表依赖。
        mainMenuCtor = mockConstruction(MainMenu.class);
        profileGuiCtor = mockConstruction(ProfileGUI.class);
        wordBankCtor = mockConstruction(WordBankSelectGUI.class);
        scenarioSelectCtor = mockConstruction(ScenarioSelectGUI.class);
        chatHistoryCtor = mockConstruction(ChatHistoryGUI.class);
        wrongBookCtor = mockConstruction(WrongBookGUI.class);
        wordLearningCtor = mockConstruction(WordLearningGUI.class);
        challengeCompleteCtor = mockConstruction(ChallengeCompleteGUI.class);

        // 清掉 setUp 里**打桩**产生的调用记录（Mockito 是否把这些计进 verify 属于实现细节，
        // 不该让 30 多条 `verify(..., never())` 的可靠性依赖它）。清记录不动桩，
        // 之后每个用例的 never() 断言就只反映它自己真的做了什么。
        clearInvocations(plugin, configManager, messageUtil, server, player, nav, duelManager,
                wordManager, reviewScheduler, wordLearningManager, aiChatManager,
                chatScenarioListener, socialListener, dailyChallengeManager, checkInManager,
                onboardingManager, socialManager, playerDataStore);
    }

    @AfterEach
    void tearDown() {
        closeQuietly(challengeCompleteCtor);
        closeQuietly(wordLearningCtor);
        closeQuietly(wrongBookCtor);
        closeQuietly(chatHistoryCtor);
        closeQuietly(scenarioSelectCtor);
        closeQuietly(wordBankCtor);
        closeQuietly(profileGuiCtor);
        closeQuietly(mainMenuCtor);
        closeQuietly(bukkit);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // 关闭失败不该把用例结果盖掉
        }
    }

    /**
     * 取 Mockito 回调里的「占位符」实参，即被 mock 方法最后那个
     * {@code String... placeholders} 参数。
     *
     * <p><b>为什么这里必须是 {@code getRawArguments()} 而不是 {@code getArgument(2)}</b>：
     * 被 mock 的签名是 {@code getPlayerMessage(Player, String, String...)} —— 只有 <b>3</b> 个
     * 形参，第 3 个是可变参数数组。但 Mockito 的 {@code getArguments()} 会把可变参数
     * <b>摊平</b>：{@code getPlayerMessage(p, "k", "a", "1", "b", "2")} 得到长度 6 的
     * {@code [p, "k", "a", "1", "b", "2"]}，{@code getPlayerMessage(p, "k")} 只有长度 2。
     * 于是旧的 {@code getArgument(2)} 两种形态都错：
     * <ul>
     *   <li>无占位符（长度 2）→ {@code ArrayIndexOutOfBoundsException: Index 2 out of bounds for length 2}
     *       —— 这就是本类 66~69 个 Error 的唯一根因；</li>
     *   <li>有占位符（长度 &gt; 2）→ 索引 2 是单个 {@code String}，强转 {@code String[]} 直接
     *       {@code ClassCastException}。</li>
     * </ul>
     * {@code getRawArguments()} 保留<b>未摊平</b>的调用形态（长度恒为 3，{@code raw[2]} 就是那个
     * {@code String[]}，无占位符时是空数组），正是原作者的意图。
     *
     * <p><b>为什么带 {@code fixedParams}</b>：{@code getMessage(String, String...)} 只有一个固定
     * 参数，{@code getPlayerMessage} 有两个。把固定参数个数做成入参，两个桩就能共用同一个
     * 取样器，而不必各自复制一份「从第几个开始是占位符」的算术 —— 那正是本次出错的地方。
     *
     * <p>两个防御：可变参数位不是数组时（签名若被改成非可变参数）退回「摊平形态」逐个收；
     * 占位符段长度为奇数时原样交给 {@link #placeholder} 的 {@code i + 1 < length} 守卫处理，
     * 与生产代码「奇数只警告不抛」的行为对齐。
     */
    private static String[] placeholdersFrom(Object[] rawArgs, int fixedParams) {
        if (rawArgs == null || rawArgs.length <= fixedParams) {
            return new String[0];
        }
        if (rawArgs[fixedParams] instanceof String[] arr) {
            return arr;
        }
        // 兜底：万一将来桩改成摊平形态（例如换成 ArgumentCaptor），把固定参数之后的全收下。
        String[] placeholders = new String[rawArgs.length - fixedParams];
        for (int i = fixedParams; i < rawArgs.length; i++) {
            placeholders[i - fixedParams] = rawArgs[i] == null ? null : String.valueOf(rawArgs[i]);
        }
        return placeholders;
    }

    // ==================================================================
    //  调用助手
    // ==================================================================

    private LearnCommand command() {
        return new LearnCommand(plugin);
    }

    /** 主入口。返回 Bukkit 契约要求的 boolean。 */
    private boolean run(String... args) {
        return command().onCommand(player, command, "le", args);
    }

    private List<String> complete(String... args) {
        return command().onTabComplete(player, command, "le", args);
    }

    /** 断言「本次日志里有这条 key」。 */
    private void assertSaid(String key) {
        assertTrue(keysSent.contains(key),
                "期望 LearnCommand 发出 i18n key '" + key + "' 喵，实际依次发出：" + keysSent);
    }

    private void assertNeverSaid(String key) {
        assertFalse(keysSent.contains(key),
                "LearnCommand 不该发出 '" + key + "' 喵，实际依次发出：" + keysSent);
    }

    /**
     * 取某个 key 收到的占位符里 {@code name} 对应的值（找不到返回 null）。
     *
     * <p>这是本类唯一能观测「渲染出的数字」的手段 —— 见 {@link #placeholdersByKey} 的理由。
     */
    private String placeholder(String key, String name) {
        String[] args = placeholdersByKey.get(key);
        if (args == null) {
            return null;
        }
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    /**
     * 造一份「只有给定 key」的场景表。
     *
     * <p>为什么不用双花括号匿名子类（{@code new LinkedHashMap<>() {{ put(...); }}}）：
     * 那会持有外层实例的隐式引用并产生一个额外的匿名类，且 Mockito 打桩时容易踩到
     * 泛型推断的坑。这里一行建图更好读。
     */
    private static LinkedHashMap<String, AIChatManager.ScenarioConfig> scenarioMap(String... keys) {
        LinkedHashMap<String, AIChatManager.ScenarioConfig> map = new LinkedHashMap<>();
        for (String key : keys) {
            map.put(key, null);
        }
        return map;
    }

    // ==================================================================
    //  组 1 —— 分派表完整性
    // ==================================================================

    @Nested
    @DisplayName("组 1 —— 子命令分派")
    class Dispatch {

        /**
         * 分派表的行为侧：{@code SUBCOMMANDS} 里每一个 token 真敲下去都必须被处理，
         * 一个都不许落到 {@code default}（{@code general.unknown-command}）。
         *
         * <p>这是本轮最重要的一条：它把「补全能看见 / 敲了报未知」这种漂移变成构建期红灯，
         * 而 617 行里没有任何别的机制会检查这件事。
         */
        @Test
        @DisplayName("SUBCOMMANDS 里每个 token 都真的被分派（零个落到 unknown-command）")
        void everyAdvertisedSubcommandIsDispatched() {
            assertTrue(subcommandTokens.size() >= 20,
                    "从源码里只读出了 " + subcommandTokens.size() + " 个子命令 token —— "
                            + "advertisedSubcommands 的锚点失效了（本断言先红，免得下面空转）");
            assertEquals(new LinkedHashSet<>(subcommandTokens).size(), subcommandTokens.size(),
                    "SUBCOMMANDS 里有重复 token 喵：" + subcommandTokens);

            List<String> fellThrough = new ArrayList<>();
            for (String token : subcommandTokens) {
                int before = keysSent.size();
                // 注意：这里刻意<b>不</b>写 assertDoesNotThrow(() -> run(token), ...)。
                // run 有返回值，那个 lambda 同时适配 Executable(void) 与 ThrowingSupplier<T>(值)，
                // JUnit 的两个重载会因此产生**歧义**（编译错）。直接调用即可：
                // 抛异常本身就会让用例失败，与 assertDoesNotThrow 的意图一致。
                boolean handled = run(token);
                assertTrue(handled, "/le " + token + " 必须返回 true（否则 Bukkit 会打用法提示）");
                List<String> produced = keysSent.subList(before, keysSent.size());
                if (produced.contains("general.unknown-command")) {
                    fellThrough.add(token);
                }
            }
            assertEquals(List.of(), fellThrough,
                    "这些子命令出现在 tab 补全里却没有对应的 switch 分支喵：" + fellThrough
                            + "\n后果：玩家按 Tab 看得见、真敲却得到「未知命令」。"
                            + "修法：要么补 case，要么从 SUBCOMMANDS 里删掉 —— 两份清单必须同源。");
        }

        /**
         * 反向：不在表里的 token 必须走 default。缺这条的话，一个「default 什么都不做、
         * 也不报错」的实现能让上一条全绿而玩家敲错字时毫无反馈。
         */
        @Test
        @DisplayName("表外 token 走 default 并回 general.unknown-command")
        void unknownTokenReportsUnknownCommand() {
            assertTrue(run("definitely_not_a_subcommand"));
            assertSaid("general.unknown-command");
        }

        /**
         * 子命令大小写不敏感（{@code args[0].toLowerCase()}）。
         *
         * <p>变异指向：删掉那一次 {@code toLowerCase()} → 三条断言全红。Minecraft 的
         * 客户端不会自动小写，玩家敲 {@code /le WORD} 是常态。
         */
        @Test
        @DisplayName("子命令大小写不敏感：/le WoRd /le ChEcKiN /le HELP 都要正常分派")
        void subcommandIsCaseInsensitive() {
            assertTrue(run("WoRd", "beginner"));
            assertNeverSaid("general.unknown-command");
            verify(wordLearningManager).startQuiz(player, "beginner", "choice");

            keysSent.clear();
            assertTrue(run("ChEcKiN"));
            assertNeverSaid("general.unknown-command");
            verify(checkInManager).checkIn(player);

            keysSent.clear();
            assertTrue(run("HeLp"));
            assertSaid("help.header");
        }

        /**
         * 大小写归一化必须发生在<b>查表之前</b>，而不是只对 {@code args[1..]} 做。
         * 上一条已覆盖；这里补一个「混合大小写 + 有参数」的组合，防止实现只在无参分支归一化。
         */
        @Test
        @DisplayName("有参分支同样大小写不敏感：/le SQUARE WeEkLy → WEEKLY")
        void subcommandAndArgumentCaseAreBothNormalized() {
            assertTrue(run("SQUARE", "WeEkLy"));
            verify(socialListener).openSquareWithSnapshot(player, RankingMode.WEEKLY);
        }

        @Test
        @DisplayName("无参 /le 打开主菜单（且不产生任何 unknown-command）")
        void bareCommandOpensMainMenu() {
            assertTrue(run());
            assertEquals(1, mainMenuCtor.constructed().size(),
                    "无参 /le 必须构造并打开 MainMenu");
            assertNeverSaid("general.unknown-command");
        }

        /**
         * 控制台来源 → {@code general.player-only}，且<b>绝不能</b>走到任何 Player/Manager。
         *
         * <p>变异指向：把 {@code sender instanceof Player player} 的模式匹配改成强转 →
         * 控制台执行 {@code /le} 会 ClassCastException（控制台敲错一次命令就刷一段堆栈）。
         * 注意这条走的是非玩家版 {@code getMessage}（不是 {@code getPlayerMessage}），
         * 所以它同时也是「控制台路径没被玩家版桩掩盖」的证据。
         */
        @Test
        @DisplayName("非玩家发送者 → general.player-only，且不触碰任何 Manager")
        void nonPlayerSenderIsRejectedCleanly() {
            org.bukkit.command.CommandSender console =
                    mock(org.bukkit.command.CommandSender.class);

            // 同样不包 assertDoesNotThrow：onCommand 返回 boolean，lambda 会撞上
            // Executable / ThrowingSupplier 的重载歧义（编译错）。抛异常本身就是失败。
            boolean handled = command()
                    .onCommand(console, command, "le", new String[] {"word"});

            assertTrue(handled, "控制台执行也必须返回 true（不落到 Bukkit 用法提示）");
            assertSaid("general.player-only");
            // 非玩家判定必须**早于**子服总开关判定：否则关闭态下控制台敲 /le
            // 会先拿到 server.disabled，而真实原因（控制台不能用这个命令）被掩盖。
            verify(plugin, never()).isFullyEnabled();
            verify(plugin, never()).getWordManager();
            verify(plugin, never()).getWordLearningManager();
        }

        @Test
        @DisplayName("server.enabled=false：任何子命令（含无参）都只回 server.disabled")
        void fullyDisabledServerRejectsEverything() {
            when(plugin.isFullyEnabled()).thenReturn(false);

            assertTrue(run("word"));
            assertTrue(run());
            assertTrue(run("totally_bogus"));

            assertEquals(List.of("server.disabled", "server.disabled", "server.disabled"), keysSent,
                    "关闭态必须在 args 判空与子命令判定**之前**拦下，且只发一条 server.disabled 喵"
                            + "（关闭态下 Manager 全是 null，走进去就是 NPE）");
            assertEquals(0, mainMenuCtor.constructed().size(),
                    "关闭态不许开主菜单（它依赖的 Manager 全为 null）");
        }
    }

    // ==================================================================
    //  组 2 —— square 的模式映射（最经典的错映面）
    // ==================================================================

    @Nested
    @DisplayName("组 2 —— /le square 模式白名单")
    class SquareRouting {

        private RankingMode modeFor(String token) {
            keysSent.clear();
            // 每格都要清掉上一格的调用记录，否则 ArgumentCaptor.getAllValues() 会累积，
            // 「无参传 null」那条会被前一格的 ALL_TIME_COINS 顶掉（假绿）。
            clearInvocations(socialListener);
            if (token == null) {
                run("square");
            } else {
                run("square", token);
            }
            org.mockito.ArgumentCaptor<RankingMode> captor =
                    org.mockito.ArgumentCaptor.forClass(RankingMode.class);
            verify(socialListener).openSquareWithSnapshot(eq(player), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("五个 token 各自映射到不同的 RankingMode（alltime≠all 是最容易对调的一对）")
        void everyTokenMapsToItsOwnMode() {
            assertEquals(RankingMode.FEED, modeFor("feed"));
            assertEquals(RankingMode.WEEKLY, modeFor("weekly"));
            assertEquals(RankingMode.MONTHLY, modeFor("monthly"));
            assertEquals(RankingMode.ALL_TIME_COINS, modeFor("alltime"),
                    "alltime 必须映射到 ALL_TIME_COINS（金币榜）喵 —— 与 ALL_PLAYERS 对调的话，"
                            + "玩家敲 /le square alltime 会看到全员榜");
            assertEquals(RankingMode.ALL_PLAYERS, modeFor("all"));
            // 注意：不写 Set.of(...).size() 之类的断言 —— 五个 enum 常量天然两两不同，
            // 那种断言恒真（自证桩）。真正的判别力全在上面五条映射断言里。
        }

        @Test
        @DisplayName("无参 / 非法值都传 null（由 GUI 读 english-square.default-mode 决定落点）")
        void missingOrIllegalModePassesNull() {
            assertNull(modeFor(null),
                    "无参 /le square 必须传 null 喵：显式传 FEED 会把 GUI 的 default-mode 配置架空");
            assertNull(modeFor("bogus"),
                    "非法模式必须传 null（plan-social §3.4 的默认落点），不是抛错也不是猜一个");
        }

        @Test
        @DisplayName("模式 token 大小写不敏感")
        void modeIsCaseInsensitive() {
            assertEquals(RankingMode.ALL_TIME_COINS, modeFor("AllTime"));
        }
    }

    // ==================================================================
    //  组 3 —— /le word 参数校验
    // ==================================================================

    @Nested
    @DisplayName("组 3 —— /le word 的三种拒绝语义")
    class WordValidation {

        @Test
        @DisplayName("无参：词库选择 GUI（不传难度）")
        void bareWordOpensBankSelector() {
            assertTrue(run("word"));
            assertEquals(1, wordBankCtor.constructed().size());
            verify(wordLearningManager, never()).startQuiz(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("三种模式全关：无参 /le word 直接回 word.no-modes-enabled，不开死界面")
        void allModesDisabledRefusesWithoutOpeningGui() {
            mainConfig.set("word-system.modes.spelling.enabled", false);
            mainConfig.set("word-system.modes.choice.enabled", false);
            mainConfig.set("word-system.modes.fill_blank.enabled", false);

            assertTrue(run("word"));
            assertSaid("word.no-modes-enabled");
            assertEquals(0, wordBankCtor.constructed().size(),
                    "全关时不得开词库选择界面喵 —— 那个界面每次点击都必然被拒（死界面）");
        }

        @Test
        @DisplayName("未知模式 → word.invalid-mode（信息里带 mode 原名）")
        void unknownModeReportsInvalidMode() {
            assertTrue(run("word", "beginner", "telepathy"));
            assertSaid("word.invalid-mode");
            verify(wordLearningManager, never()).startQuiz(any(), anyString(), anyString());
        }

        /**
         * 「未知模式」与「已知但被管理员关闭」是两种必须分开的拒绝语义：
         * 前者玩家该改字，后者玩家该找管理员。合并成一条提示是本类要封的变异之一。
         */
        @Test
        @DisplayName("已知但被关的模式 → word.mode-disabled（不是 invalid-mode）")
        void knownButDisabledModeReportsModeDisabled() {
            mainConfig.set("word-system.modes.spelling.enabled", false);

            assertTrue(run("word", "beginner", "spelling"));
            assertSaid("word.mode-disabled");
            assertNeverSaid("word.invalid-mode");
            verify(wordLearningManager, never()).startQuiz(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("难度不存在 → word.invalid-difficulty，且不开局")
        void unknownDifficultyReportsOptions() {
            assertTrue(run("word", "nonsense"));
            assertSaid("word.invalid-difficulty");
            verify(wordLearningManager, never()).startQuiz(any(), anyString(), anyString());
        }

        /**
         * R-86c：难度匹配是<b>大小写不敏感</b>并解析回词库的<b>规范 key</b>。
         *
         * <p>词库名允许自定义大小写（{@code Beginner}），玩家敲 {@code beginner} 必须命中，
         * 且 {@code startQuiz} 收到的是规范形式 {@code Beginner} —— 两个断言一起才叫「解析回
         * 规范 key」，只断言「不开错」漏掉「把玩家输入原样透传」这一格。
         */
        @Test
        @DisplayName("难度大小写不敏感，且解析回规范 key（Beginner 而不是 beginner）")
        void difficultyMatchingIsCaseInsensitive() {
            when(wordManager.getAvailableDifficulties())
                    .thenReturn(new LinkedHashSet<>(List.of("Beginner", "Advanced")));

            assertTrue(run("word", "beginner"));
            assertNeverSaid("word.invalid-difficulty");
            verify(wordLearningManager).startQuiz(player, "Beginner", "choice");
        }

        @Test
        @DisplayName("默认模式取 firstEnabledMode（出厂 = choice），不是硬编码常量")
        void defaultModeFollowsConfig() {
            assertTrue(run("word", "beginner"));
            verify(wordLearningManager).startQuiz(player, "beginner", "choice");

            keysSent.clear();
            mainConfig.set("word-system.modes.choice.enabled", false);
            assertTrue(run("word", "beginner"));
            verify(wordLearningManager).startQuiz(player, "beginner", "spelling");
        }

        @Test
        @DisplayName("word-system.enabled=false → module.disabled（有参无参都拦）")
        void wordSystemDisabledGatesBothPaths() {
            when(configManager.isWordSystemEnabled()).thenReturn(false);

            assertTrue(run("word"));
            assertTrue(run("word", "beginner"));
            assertEquals(List.of("module.disabled", "module.disabled"), keysSent);
            assertEquals(0, wordBankCtor.constructed().size());
        }

        @Test
        @DisplayName("决斗中不开新答题会话 → duel.finish-duel-first")
        void duelBlocksNewQuiz() {
            when(duelManager.isInDuel(PLAYER_ID)).thenReturn(true);

            assertTrue(run("word", "beginner"));
            assertSaid("duel.finish-duel-first");
            verify(wordLearningManager, never()).startQuiz(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("AI 对话中不开新答题会话 → dialogue.chat-active")
        void activeConversationBlocksNewQuiz() {
            when(aiChatManager.hasActiveConversation(player)).thenReturn(true);

            assertTrue(run("word", "beginner"));
            assertSaid("dialogue.chat-active");
            verify(wordLearningManager, never()).startQuiz(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("开局成功 → word.session-start，难度与模式走 i18n label（不透传标识符）")
        void successfulStartLocalizesIdentifiers() {
            assertTrue(run("word", "beginner", "fill_blank"));

            assertSaid("word.session-start");
            assertSaid("word.difficulty-beginner");
            assertSaid("word.mode-fill_blank");
            assertEquals(1, wordLearningCtor.constructed().size());
        }

        @Test
        @DisplayName("难度存在但无词可学（startQuiz 抛 IllegalStateException）→ word.no-words-available")
        void emptyWordBankReportsNoWordsAvailable() {
            when(wordLearningManager.startQuiz(any(Player.class), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("no words"));

            assertTrue(run("word", "beginner"));
            assertSaid("word.no-words-available");
        }

        /**
         * <b>D7 已修</b>：{@code /le word <难度> <模式> <多余>} 曾静默忽略多余参数。
         *
         * <p>修前 {@code handleWordCommand} 只看 {@code args[1]} / {@code args[2]}，
         * {@code args.length > 3} 时多出来的 token 既不被拒绝也不提示。玩家从别处抄来
         * {@code /le word beginner choice 3} 会以为「3 题」生效了，实际是默认题数。
         *
         * <p>修法：{@code handleWordCommand} 在难度/模式语义校验<b>之前</b>判
         * {@code args.length > 3} → 发 {@code word.extra-arguments}（带 {@code {extra}}
         * 与 {@code {usage}}）并 return，不开始学习会话。
         *
         * <p>本用例钉三件事：① 会话不开（{@code startQuiz} 零调用 + 零 GUI）；② 发的是
         * {@code word.extra-arguments} 而不是别的拒绝 key；③ {@code {extra}} 里是玩家
         * 真敲的那些 token（只断言 key 存在的话，「多敲的词被吞掉但仍报错」也能绿）。
         */
        @Test
        @DisplayName("/le word beginner choice 3 的多余参数应被拒绝并回显，且不开局")
        void extraArgumentsAreNotSilentlyIgnored() {
            assertTrue(run("word", "beginner", "choice", "3"));

            assertSaid("word.extra-arguments");
            verify(wordLearningManager, never()).startQuiz(any(), anyString(), anyString());
            assertEquals(0, wordLearningCtor.constructed().size(),
                    "多余参数时不得打开答题界面喵 —— 玩家会以为新参数生效了");
            assertEquals("3", placeholder("word.extra-arguments", "extra"),
                    "回显的多余参数不对喵 —— {extra} 必须是玩家真敲的那几个 token");
            assertNotNull(placeholder("word.extra-arguments", "usage"),
                    "{usage} 必须由调用方传入具体用法串，不能留空喵");
            assertNeverSaid("word.invalid-difficulty");
            assertNeverSaid("word.invalid-mode");
        }

        /**
         * 顺序契约：多余的 token 是<b>语法</b>错误，必须先于难度/模式的<b>语义</b>校验判定。
         * 反过来的话 {@code /le word xyz choice 3} 会先报 invalid-difficulty，玩家改完难度
         * 又被 extra-arguments 打脸第二次 —— 一次调用暴露一个错误，玩家要试两轮。
         */
        @Test
        @DisplayName("语法错误先于语义错误：/le word xyz choice 3 先报 extra-arguments")
        void extraArgumentsWinOverSemanticValidation() {
            assertTrue(run("word", "xyz", "telepathy", "3"));

            assertSaid("word.extra-arguments");
            assertNeverSaid("word.invalid-difficulty");
            assertNeverSaid("word.invalid-mode");
        }
    }

    // ==================================================================
    //  组 4 —— /le duel 的动词与参数
    // ==================================================================

    @Nested
    @DisplayName("组 4 —— /le duel")
    class DuelRouting {

        @Test
        @DisplayName("dms 不可用 → error.duel-unavailable（包装进 general.error）")
        void missingManagerReportsUnavailable() {
            when(plugin.getDuelManager()).thenReturn(null);

            assertTrue(run("duel", "accept"));
            assertSaid("general.error");
            assertSaid("error.duel-unavailable");
        }

        /** 变异指向：{@code args.length < 2} 改成 {@code < 1} → 这里会 AIOOBE 或走取玩家名分支。 */
        @Test
        @DisplayName("无参 → duel.usage（且绝不触碰 isInDuel / 玩家查询）")
        void bareDuelPrintsUsage() {
            assertTrue(run("duel"));
            assertSaid("duel.usage");
            verify(duelManager, never()).isInDuel(any(UUID.class));
            verify(duelManager, never()).acceptDuel(any(Player.class));
            verify(duelManager, never()).quitDuel(any(Player.class));
        }

        @Test
        @DisplayName("accept → acceptDuel；duel.enabled=false 时先被门拦")
        void acceptHonoursModuleGate() {
            assertTrue(run("duel", "accept"));
            verify(duelManager).acceptDuel(player);

            keysSent.clear();
            when(configManager.isDuelEnabled()).thenReturn(false);
            // 第一段已经调过一次 acceptDuel，verify(never()) 必须先清掉记录，
            // 否则红的原因是「上一段调用过」而不是「门没拦住」。
            clearInvocations(duelManager);
            assertTrue(run("duel", "accept"));
            assertSaid("module.disabled");
            verify(duelManager, never()).acceptDuel(any(Player.class));
        }

        /**
         * MC-2 特殊语义（plan-menu-config §4.2）：{@code quit} / {@code gui} 在
         * <b>已在局中</b>时放行 —— 关开关后进行中的对局仍可退出/重开界面。
         * 这两格是「模块门不是一刀切」的全部证据，缺了就没有任何东西守着它。
         */
        @Test
        @DisplayName("quit：开关关闭但已在局中 → 放行；不在局中 → module.disabled")
        void quitIsAllowedForInProgressDuel() {
            when(configManager.isDuelEnabled()).thenReturn(false);
            when(duelManager.isInDuel(PLAYER_ID)).thenReturn(true);
            assertTrue(run("duel", "quit"));
            verify(duelManager).quitDuel(player);
            assertNeverSaid("module.disabled");

            keysSent.clear();
            // 第一段真的调过一次 quitDuel：不清记录的话，下面的 verify(never()) 会因
            // 「上一段调过」而红 —— 那不是「门没拦住」，归因错了。
            clearInvocations(duelManager);
            when(duelManager.isInDuel(PLAYER_ID)).thenReturn(false);
            assertTrue(run("duel", "quit"));
            assertSaid("module.disabled");
            verify(duelManager, never()).quitDuel(any(Player.class));
        }

        @Test
        @DisplayName("gui：开关关闭但已在局中 → 放行（ESC 后重开界面）")
        void guiIsAllowedForInProgressDuel() {
            when(configManager.isDuelEnabled()).thenReturn(false);
            when(duelManager.isInDuel(PLAYER_ID)).thenReturn(true);

            assertTrue(run("duel", "gui"));
            verify(duelManager).reopenDuelGUI(player);
            assertNeverSaid("module.disabled");
        }

        @Test
        @DisplayName("cancel：无 admin 权限 → general.no-permission，且绝不调到 cancelDuelByAdmin")
        void cancelRequiresAdminPermission() {
            assertTrue(run("duel", "cancel", "Bob"));

            assertSaid("general.no-permission");
            verify(duelManager, never()).cancelDuelByAdmin(any(), anyString());
        }

        @Test
        @DisplayName("cancel：有权限但缺玩家名 → duel.cancel-usage（不把 cancel 当玩家名）")
        void cancelWithoutNamePrintsUsage() {
            when(player.hasPermission("letslearnenglish.admin")).thenReturn(true);

            assertTrue(run("duel", "cancel"));

            assertSaid("duel.cancel-usage");
            verify(duelManager, never()).cancelDuelByAdmin(any(), anyString());
            verify(server, never()).getPlayer(anyString());
        }

        @Test
        @DisplayName("cancel：有权限且有玩家名 → cancelDuelByAdmin")
        void cancelForwardsAdminCall() {
            when(player.hasPermission("letslearnenglish.admin")).thenReturn(true);

            assertTrue(run("duel", "cancel", "Bob"));
            verify(duelManager).cancelDuelByAdmin(player, "Bob");
        }

        /** cancel 刻意<b>不</b>受 duel.enabled 门约束（GM 解散通道，注释里写明）。 */
        @Test
        @DisplayName("cancel 不受 duel.enabled 门约束（GM 解散必须始终可用）")
        void cancelIsNotGatedByDuelEnabled() {
            when(configManager.isDuelEnabled()).thenReturn(false);
            when(player.hasPermission("letslearnenglish.admin")).thenReturn(true);

            assertTrue(run("duel", "cancel", "Bob"));
            assertNeverSaid("module.disabled");
            verify(duelManager).cancelDuelByAdmin(player, "Bob");
        }

        @Test
        @DisplayName("目标离线 → duel.player-offline（不发起邀请）")
        void offlineTargetReportsPlayerOffline() {
            assertTrue(run("duel", "bob"));

            assertSaid("duel.player-offline");
            verify(duelManager, never()).sendDuelRequest(any(), any());
        }

        @Test
        @DisplayName("目标在线 → sendDuelRequest")
        void onlineTargetGetsChallenge() {
            Player bob = mock(Player.class);
            when(bob.getName()).thenReturn("Bob");
            when(bob.getUniqueId()).thenReturn(BOB_ID);
            when(server.getPlayer(anyString())).thenReturn(bob);

            assertTrue(run("duel", "Bob"));
            verify(duelManager).sendDuelRequest(player, bob);
        }

        /** 发起路径恒过 duel.enabled 门（与 quit/gui 的「局中放行」形成对照）。 */
        @Test
        @DisplayName("发起邀请受 duel.enabled 门约束：关闭时 module.disabled，不查在线玩家")
        void challengeIsGatedByDuelEnabled() {
            when(configManager.isDuelEnabled()).thenReturn(false);

            assertTrue(run("duel", "Bob"));
            assertSaid("module.disabled");
            verify(server, never()).getPlayer(anyString());
            verify(duelManager, never()).sendDuelRequest(any(), any());
        }

        /**
         * <b>D7 已修（word 同族）</b>：{@code /le duel accept now} / {@code /le duel Bob extra}
         * 的多余 token 也曾被静默丢弃 —— 玩家以为附加条件生效了，实际只有前两个 token 起作用。
         *
         * <p>合法长度与 {@code onTabComplete} 的补全层数同源：动词式恰为 2 个 token，
         * {@code cancel <玩家名>} 恰为 3 个。三条断言各自钉一个动词族，且都验 zero 动作 ——
         * 只断言 key 存在的话，一个「先执行再报错」的实现也能绿。
         */
        @Test
        @DisplayName("多余参数：accept / 发起 / cancel 三族各回自己的 usage，且不执行任何决斗动作")
        void extraArgumentsAreNotSilentlyIgnored() {
            assertTrue(run("duel", "accept", "now"));
            assertSaid("duel.usage");
            verify(duelManager, never()).acceptDuel(any(Player.class));

            keysSent.clear();
            assertTrue(run("duel", "Bob", "extra"));
            assertSaid("duel.usage");
            verify(server, never()).getPlayer(anyString());
            verify(duelManager, never()).sendDuelRequest(any(), any());

            keysSent.clear();
            when(player.hasPermission("letslearnenglish.admin")).thenReturn(true);
            assertTrue(run("duel", "cancel", "Bob", "extra"));
            assertSaid("duel.cancel-usage");
            verify(duelManager, never()).cancelDuelByAdmin(any(), anyString());
        }
    }

    // ==================================================================
    //  组 5 —— /le checkin 的金额渲染（R-16 谎报面）
    // ==================================================================

    @Nested
    @DisplayName("组 5 —— /le checkin")
    class CheckinPayout {

        @Test
        @DisplayName("模块关闭 → module.disabled，不调 checkIn")
        void gateBeforeManager() {
            when(configManager.isCheckinEnabled()).thenReturn(false);

            assertTrue(run("checkin"));
            assertSaid("module.disabled");
            verify(checkInManager, never()).checkIn(any(Player.class));
        }

        @Test
        @DisplayName("Manager 为 null → error.checkin-unavailable")
        void missingManagerReportsUnavailable() {
            when(plugin.getCheckInManager()).thenReturn(null);

            assertTrue(run("checkin"));
            assertSaid("error.checkin-unavailable");
        }

        @Test
        @DisplayName("今日已签（success=false, storageFailed=false）→ checkin.already")
        void alreadyCheckedIn() {
            assertTrue(run("checkin"));
            assertSaid("checkin.already");
            assertNeverSaid("checkin.storage-failed");
        }

        /** R11-M4：存储失败与「今日已签」同为 success=false，但语义不同（前者要重试）。 */
        @Test
        @DisplayName("存储失败（success=false, storageFailed=true）→ checkin.storage-failed")
        void storageFailedIsDistinguished() {
            when(checkInManager.checkIn(any(Player.class)))
                    .thenReturn(new CheckInManager.CheckInResult(false, 0, 0, false, false, true, 0));

            assertTrue(run("checkin"));
            assertSaid("checkin.storage-failed");
            assertNeverSaid("checkin.already");
        }

        @Test
        @DisplayName("成功 → checkin.success + recordCheckin；断签额外回 streak-broken")
        void successFeedsSocialAndStreakNotice() {
            when(checkInManager.checkIn(any(Player.class)))
                    .thenReturn(new CheckInManager.CheckInResult(true, 4, 30, true));

            assertTrue(run("checkin"));
            assertSaid("checkin.streak-broken");
            assertSaid("checkin.success");
            verify(socialManager).recordCheckin(PLAYER_ID, 4);
            assertNeverSaid("daily.reward-capped");
        }

        /**
         * R-16 残留①：实发 {@code 0.20} 不许被 {@code (int)} 强转渲染成 {@code 0}。
         *
         * <p><b>为什么必须读占位符实参而不是只断言 key 存在</b>：本缺陷的形态是
         * 「key 对了、数字错了」—— {@code checkin.success} 照发，只是把 {@code 0.20}
         * 渲染成 {@code 0}。只查 key 的断言对
         * {@code formatCoins} 的 {@code %.2f} 分支被换成 {@code (long)} 强转<b>完全无感</b>
         * （变异后仍然发同一条 key，仍然走同一条 capped 分支）。
         * 所以这里必须读 {@code {coins}} 的实参原文。
         *
         * <p>三条断言分别封三种变异：
         * <ol>
         *   <li>{@code %.2f} → {@code (long)} 强转：{@code "0.20"} 变 {@code "0"}，第 1 条红；</li>
         *   <li>整数分支写成 {@code %.2f}（100.0 → "100.00"）：第 3 条红；</li>
         *   <li>两个分支接反（整数走 {@code %.2f}、小数走强转）：第 1、3 条同时红。</li>
         * </ol>
         */
        @Test
        @DisplayName("日限截断（实发 0.20 / 应发 5.0）→ reward-capped 的 {paid} 必须是 \"0.20\" 不是 \"0\"")
        void cappedPayoutReportsCentsNotTruncatedInt() {
            when(checkInManager.checkIn(any(Player.class)))
                    .thenReturn(new CheckInManager.CheckInResult(true, 3, 0.20, false, false, false, 5.0));

            assertTrue(run("checkin"));
            assertSaid("checkin.success");
            assertSaid("daily.reward-capped");

            assertEquals("0.20", placeholder("daily.reward-capped", "paid"),
                    "实发额必须渲染成 \"0.20\" 喵。(int) 强转下 {paid} 会变成 \"0\" —— "
                            + "玩家刚真的收到 0.2 金币却读到「获得 0 金币」，正是 R-16 要消灭的谎报。"
                            + "实际实参：" + java.util.Arrays.toString(placeholdersByKey.get("daily.reward-capped")));
            assertEquals("5", placeholder("daily.reward-capped", "nominal"),
                    "应发额 5.0 是整数，必须渲染成 \"5\"（不带小数点）喵。实际实参："
                            + java.util.Arrays.toString(placeholdersByKey.get("daily.reward-capped")));
        }

        /**
         * 整数金额必须渲染成 {@code "30"}（无小数点）。
         *
         * <p>与上一条配对：只钉小数分支的话，「整数分支也走 {@code %.2f}」这个变异
         * 会让玩家看到「获得 30.00 金币」—— 不算谎报但风格不一致，且会把
         * {@code checkin.success} 的中英文案排版挤歪。两条一起才构成
         * 「整数去小数点、非整数保留两位」这份契约的完整证据。
         */
        @Test
        @DisplayName("整数金额 → {coins} 渲染成 \"30\"（去小数点），不是 \"30.00\"")
        void integerPayoutDropsDecimals() {
            when(checkInManager.checkIn(any(Player.class)))
                    .thenReturn(new CheckInManager.CheckInResult(true, 5, 30.0, false, false, false, 30.0));

            assertTrue(run("checkin"));
            assertEquals("30", placeholder("checkin.success", "coins"),
                    "整数金额必须渲染成 \"30\" 喵（formatCoins 的整数分支是 (long) 强转）。"
                            + "实际实参：" + java.util.Arrays.toString(placeholdersByKey.get("checkin.success")));
            assertEquals("5", placeholder("checkin.success", "streak"),
                    "连续天数必须原样透传喵");
        }

        @Test
        @DisplayName("经济未启用（economyDisabled=true）→ 额外一行 checkin.economy-disabled")
        void economyDisabledExplainsZeroCoins() {
            when(checkInManager.checkIn(any(Player.class)))
                    .thenReturn(new CheckInManager.CheckInResult(true, 2, 0, false, true, false, 100));

            assertTrue(run("checkin"));
            assertSaid("checkin.success");
            assertSaid("checkin.economy-disabled");
        }
    }

    // ==================================================================
    //  组 6 —— /le challenge 的庆祝页去重（R-40）
    // ==================================================================

    @Nested
    @DisplayName("组 6 —— /le challenge")
    class ChallengeCelebration {

        private void allDoneAndPaid() {
            when(dailyChallengeManager.getTodayWordProgress(any(Player.class)))
                    .thenReturn(new int[] {2, 2});
            when(dailyChallengeManager.isWordRewardPaidToday(any(UUID.class))).thenReturn(true);
        }

        @Test
        @DisplayName("无挑战 → daily-challenge.no-words，不弹庆祝页")
        void noChallengeToday() {
            assertTrue(run("challenge"));
            assertSaid("daily-challenge.no-words");
            assertEquals(0, challengeCompleteCtor.constructed().size());
        }

        @Test
        @DisplayName("全部完成且已到账 → all-complete-chat + 庆祝页打开一次")
        void fullyDoneOpensCelebration() {
            allDoneAndPaid();

            assertTrue(run("challenge"));
            assertSaid("daily-challenge.all-complete-chat");
            assertEquals(1, challengeCompleteCtor.constructed().size(),
                    "全完成且钱已到账时必须打开庆祝页喵");
        }

        /**
         * R-40 去重：同一玩家同一自然日只自动弹一次。
         *
         * <p>变异指向：删掉 {@code celebrationShownDates} 的比对与 put → 第二次仍然弹页，
         * 玩家每次敲 {@code /le challenge} 都被庆祝页盖住聊天记录。
         */
        @Test
        @DisplayName("第二次执行只刷聊天，不再弹页（celebrate-shown-today）")
        void secondIdenticalRunDoesNotReopen() {
            allDoneAndPaid();
            LearnCommand cmd = command();

            assertTrue(cmd.onCommand(player, command, "le", new String[] {"challenge"}));
            assertEquals(1, challengeCompleteCtor.constructed().size());

            keysSent.clear();
            assertTrue(cmd.onCommand(player, command, "le", new String[] {"challenge"}));

            assertSaid("daily-challenge.celebrate-shown-today");
            assertEquals(1, challengeCompleteCtor.constructed().size(),
                    "同一自然日的第二次执行不许再弹庆祝页喵（R-40 去重）");
        }

        /**
         * 忙态（答题中）只刷聊天不弹页：弹页会盖掉答题 GUI 并打断输入流。
         * {@code inDuel} 与 {@code inQuiz} 各钉一次 —— 两个判据都要真的参与。
         */
        @Test
        @DisplayName("答题中 → celebrate-busy 且不弹页；决斗中 → duel.finish-duel-first")
        void busyStatesSuppressCelebration() {
            allDoneAndPaid();
            when(wordLearningManager.hasActiveQuiz(PLAYER_ID)).thenReturn(true);

            assertTrue(run("challenge"));
            assertSaid("daily-challenge.celebrate-busy");
            assertEquals(0, challengeCompleteCtor.constructed().size());

            keysSent.clear();
            when(wordLearningManager.hasActiveQuiz(PLAYER_ID)).thenReturn(false);
            when(duelManager.isInDuel(PLAYER_ID)).thenReturn(true);

            assertTrue(run("challenge"));
            assertSaid("duel.finish-duel-first");
            assertEquals(0, challengeCompleteCtor.constructed().size());

            keysSent.clear();
            when(duelManager.isInDuel(PLAYER_ID)).thenReturn(false);
            when(wordLearningManager.isPracticingWord(PLAYER_ID)).thenReturn(true);

            assertTrue(run("challenge"));
            assertSaid("daily-challenge.celebrate-busy");
            assertEquals(0, challengeCompleteCtor.constructed().size(),
                    "错题练习中同样算忙态喵（isPracticingWord 必须参与判定）");
        }

        /**
         * 完成但钱未到账、且经济确实可用 → {@code reward-pending}（不庆祝）。
         *
         * <p>必须真的把 {@code getEconomy()} 与 {@code isMoneyEnabled()} 都桩成可用，
         * 否则 {@code isEconomyAvailable()} 走 null 分支，落到 {@code economy-disabled}
         * —— 那样本用例会「因为桩没配」而假红，与「钱待补发」这条语义无关。
         * 两条分支各有一条用例（下一条覆盖 null 经济）。
         */
        @Test
        @DisplayName("完成但钱未到账且经济可用 → reward-pending（不庆祝），且绝不弹页")
        void completedButUnpaidDoesNotCelebrate() {
            when(dailyChallengeManager.getTodayWordProgress(any(Player.class)))
                    .thenReturn(new int[] {2, 2});
            when(dailyChallengeManager.isWordRewardPaidToday(any(UUID.class))).thenReturn(false);
            stubEconomyAvailable(true);

            assertTrue(run("challenge"));
            assertSaid("daily-challenge.reward-pending");
            assertNeverSaid("daily-challenge.economy-disabled");
            assertNeverSaid("daily-challenge.all-complete-chat");
            assertEquals(0, challengeCompleteCtor.constructed().size());
        }

        /**
         * R-44：{@code money.enabled=false} 不是「暂存待补发」—— 钱永远不会来，
         * 所以给 {@code economy-disabled} 而不是承诺一次永远等不到的补发。
         */
        @Test
        @DisplayName("完成但经济未启用 → economy-disabled（不是 reward-pending）")
        void completedButEconomyDisabledExplainsInsteadOfPromising() {
            when(dailyChallengeManager.getTodayWordProgress(any(Player.class)))
                    .thenReturn(new int[] {2, 2});
            when(dailyChallengeManager.isWordRewardPaidToday(any(UUID.class))).thenReturn(false);
            stubEconomyAvailable(false);

            assertTrue(run("challenge"));
            assertSaid("daily-challenge.economy-disabled");
            assertNeverSaid("daily-challenge.reward-pending");
            assertNeverSaid("daily-challenge.all-complete-chat");
        }

        /** 让 {@code isEconomyAvailable()} 走到指定分支（Vault 句柄 + money 开关双条件）。 */
        private void stubEconomyAvailable(boolean moneyEnabled) {
            com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl progress =
                    mock(com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl.class);
            com.letslearnenglish.minecraftplugin.core.progress.RewardEngine engine =
                    mock(com.letslearnenglish.minecraftplugin.core.progress.RewardEngine.class);
            when(engine.isMoneyEnabled()).thenReturn(moneyEnabled);
            when(progress.getRewardEngine()).thenReturn(engine);
            when(plugin.getProgressManager()).thenReturn(progress);
            if (moneyEnabled) {
                when(plugin.getEconomy()).thenReturn(mock(net.milkbowl.vault.economy.Economy.class));
            }
        }

        /**
         * 行为挑战列表：三种状态各自映射到自己的 key，且 {@code {progress}} 按 target 上限 clamp。
         *
         * <p>这一格是 617 行里唯一绕开 {@code MessageUtil}、直接读
         * {@code configManager.getMessageConfig(lang).getString(key)} 的地方
         * （{@code getBehavioralName}）。它的缺键路径有专门的 null 守卫（R-86d），
         * 而那正是「裸 getString 返回 null 让 translateAlternateColorCodes NPE、整段列表中断」
         * 的历史缺陷位置 —— 本用例钉住「菜单真的被读到了、状态 key 真的按三态分流」。
         */
        @Test
        @DisplayName("行为挑战三态各自分流，且 {progress} 按 target clamp（不出现 47/10）")
        void behavioralChallengesRouteThreeStates() {
            // 前置：单词挑战进度必须有 total>0，否则 handleChallengeCommand 在
            // total==0 处提前 return（只发 daily-challenge.no-words），行为挑战段根本走不到。
            when(dailyChallengeManager.getTodayWordProgress(any(Player.class)))
                    .thenReturn(new int[] {1, 2});
            FileConfiguration messages = new YamlConfiguration();
            messages.set("daily.name-answer-10", "&eWORD_MASTER");
            when(configManager.getMessageConfig(anyString())).thenReturn(messages);

            List<DailyChallengeManager.ChallengeProgress> rows = List.of(
                    new DailyChallengeManager.ChallengeProgress(
                            DailyChallengeManager.ChallengeType.ANSWER_10, 8, false, false),
                    new DailyChallengeManager.ChallengeProgress(
                            DailyChallengeManager.ChallengeType.SESSION_3, 3, true, false),
                    new DailyChallengeManager.ChallengeProgress(
                            DailyChallengeManager.ChallengeType.COMBO_5, 5, true, true));
            when(dailyChallengeManager.getTodayChallenges(PLAYER_ID)).thenReturn(rows);

            assertTrue(run("challenge"));

            assertSaid("daily-challenge.behavioral-header");
            assertSaid("daily-challenge.behavioral-progress");
            assertSaid("daily-challenge.behavioral-completed");
            assertSaid("daily-challenge.behavioral-claimed");
            assertEquals("8", placeholder("daily-challenge.behavioral-progress", "progress"),
                    "未完成项的进度必须原样透传喵");

            // clamp：target 上限之外不显示超量（GUI 与聊天同口径）
            List<DailyChallengeManager.ChallengeProgress> overflow = List.of(
                    new DailyChallengeManager.ChallengeProgress(
                            DailyChallengeManager.ChallengeType.ANSWER_10, 47, false, false));
            keysSent.clear();
            placeholdersByKey.clear();
            when(dailyChallengeManager.getTodayChallenges(PLAYER_ID)).thenReturn(overflow);

            assertTrue(run("challenge"));
            assertEquals("10", placeholder("daily-challenge.behavioral-progress", "progress"),
                    "47 必须被 clamp 到 target=10 喵（P2 fan-in：聊天侧与 GUI 展示层同口径，"
                            + "不再出现 \"47/10\" 式超量显示）");
            assertEquals("10", placeholder("daily-challenge.behavioral-progress", "target"));

            // 名字走 raw getString + 颜色翻译：'&e' 必须变成 '§e'
            // （证明 getBehavioralName 真的读了语言文件，而不是回落 key 名）
            assertEquals("§eWORD_MASTER", placeholder("daily-challenge.behavioral-progress", "name"),
                    "行为挑战名必须来自 messages 的 daily.name-* 并做 & 颜色翻译喵。"
                            + "回落到 key 名（daily.name-answer-10）说明读取链断了。");
        }

        /**
         * R-86d：{@code daily.name-*} 缺键时回落到 key 名，<b>不得 NPE</b>。
         *
         * <p>裸 {@code getString} 缺键返回 null，直接喂给
         * {@code ChatColor.translateAlternateColorCodes} 会 NPE，并且异常打断了
         * for 循环 —— 玩家看到的是「行为挑战」标题之后就什么都没有。
         */
        @Test
        @DisplayName("daily.name-* 缺键回落 key 名，不 NPE、不中断列表")
        void behavioralNameFallsBackWithoutNpe() {
            when(dailyChallengeManager.getTodayWordProgress(any(Player.class)))
                    .thenReturn(new int[] {1, 2});
            // 空 messages：所有 daily.name-* 都缺
            when(configManager.getMessageConfig(anyString())).thenReturn(new YamlConfiguration());
            when(dailyChallengeManager.getTodayChallenges(PLAYER_ID)).thenReturn(List.of(
                    new DailyChallengeManager.ChallengeProgress(
                            DailyChallengeManager.ChallengeType.ANSWER_10, 2, false, false)));

            boolean handled = run("challenge");

            assertTrue(handled);
            assertSaid("daily-challenge.behavioral-progress");
            assertEquals("daily.name-answer-10",
                    placeholder("daily-challenge.behavioral-progress", "name"),
                    "缺键时必须回落成 key 名（可见且可定位）喵，不得 NPE 也不得变空字符串");
        }
    }

    // ==================================================================
    //  组 7 —— 其余子命令的路由指纹
    // ==================================================================

    @Nested
    @DisplayName("组 7 —— 其余子命令的路由指纹")
    class OtherRoutes {

        @Test
        @DisplayName("profile：目标离线 → error.player-not-found")
        void profileOfflineTarget() {
            assertTrue(run("profile", "Bob"));
            assertSaid("error.player-not-found");
        }

        @Test
        @DisplayName("profile：目标隐身 → profile.hidden，且不开界面")
        void profileHiddenTarget() {
            Player bob = mock(Player.class);
            when(bob.getName()).thenReturn("Bob");
            when(bob.getUniqueId()).thenReturn(BOB_ID);
            when(server.getPlayer(anyString())).thenReturn(bob);
            PlayerDataStore.ProfileSettings hidden = new PlayerDataStore.ProfileSettings();
            hidden.setShowInSquare(false);
            when(playerDataStore.getProfileSettingsReadOnly(BOB_ID)).thenReturn(hidden);

            assertTrue(run("profile", "Bob"));

            assertSaid("profile.hidden");
            assertEquals(0, profileGuiCtor.constructed().size(),
                    "隐身目标不得开界面喵（R-05）");
        }

        @Test
        @DisplayName("profile：目标是本人 → 即使 showInSquare=false 也照开（本人不该被自己隐身挡住）")
        void profileOwnPageIgnoresPrivacyFlag() {
            Player self = mock(Player.class);
            when(self.getName()).thenReturn("Alice");
            when(self.getUniqueId()).thenReturn(PLAYER_ID);
            when(server.getPlayer(anyString())).thenReturn(self);
            PlayerDataStore.ProfileSettings hidden = new PlayerDataStore.ProfileSettings();
            hidden.setShowInSquare(false);
            when(playerDataStore.getProfileSettingsReadOnly(PLAYER_ID)).thenReturn(hidden);

            assertTrue(run("profile", "Alice"));

            assertNeverSaid("profile.hidden");
            assertEquals(1, profileGuiCtor.constructed().size());
        }

        @Test
        @DisplayName("profile / progress / stats 都压一枚「主菜单」返回帧（R8-M1）")
        void pagedGuiRoutesPushBackFrame() {
            assertTrue(run("profile"));
            assertTrue(run("progress"));
            assertTrue(run("stats"));

            verify(nav, times(3)).pushBack(eq(PLAYER_ID), any(Runnable.class));
            assertEquals(3, profileGuiCtor.constructed().size());
        }

        @Test
        @DisplayName("leaderboard 门关闭 → module.disabled；开启 → 走快照登记入口")
        void leaderboardUsesSnapshotEntry() {
            when(configManager.isLeaderboardEnabled()).thenReturn(false);
            assertTrue(run("leaderboard"));
            assertSaid("module.disabled");
            verify(socialListener, never()).openLeaderboardWithSnapshot(any(Player.class));

            keysSent.clear();
            when(configManager.isLeaderboardEnabled()).thenReturn(true);
            assertTrue(run("leaderboard"));
            verify(socialListener).openLeaderboardWithSnapshot(player);
        }

        @Test
        @DisplayName("season：Manager 为 null → season.disabled")
        void seasonUnavailable() {
            assertTrue(run("season"));
            assertSaid("season.disabled");
        }

        @Test
        @DisplayName("season：可用但榜为空 → title+remaining+unranked+empty")
        void seasonEnabledButEmpty() {
            SeasonManager season = mock(SeasonManager.class);
            when(season.isEnabled()).thenReturn(true);
            when(season.getCurrentTopCached(50)).thenReturn(List.of());
            when(season.getRankOf(PLAYER_ID)).thenReturn(-1);
            when(plugin.getSeasonManager()).thenReturn(season);

            assertTrue(run("season"));

            assertSaid("season.title");
            assertSaid("season.remaining");
            assertSaid("season.unranked");
            assertSaid("season.empty");
            assertNeverSaid("season.your-rank");
        }

        @Test
        @DisplayName("class：class-system.enabled=false（缺键默认 false）→ class.disabled")
        void classDisabledByDefault() {
            assertTrue(run("class"));
            assertSaid("class.disabled");
        }

        @Test
        @DisplayName("class：模块开但赛季不可用 → season.disabled（班级榜按赛季窗口计分）")
        void classRequiresActiveSeason() {
            mainConfig.set("class-system.enabled", true);

            assertTrue(run("class"));
            assertSaid("season.disabled");
            assertNeverSaid("class.disabled");
        }

        @Test
        @DisplayName("review：无到期词 → review.no-words；有到期词 → notification + 开局")
        void reviewBranches() {
            assertTrue(run("review"));
            assertSaid("review.no-words");
            verify(wordLearningManager, never()).startReviewQuiz(any(Player.class));

            keysSent.clear();
            when(reviewScheduler.getDueReviewWords(PLAYER_ID)).thenReturn(List.of("apple", "book"));
            when(wordLearningManager.startReviewQuiz(player)).thenReturn(mock(LearningSession.class));

            assertTrue(run("review"));
            assertSaid("review.notification");
            assertSaid("word.session-start");
            assertSaid("word.mode-review");
        }

        @Test
        @DisplayName("review：有到期词却开不出局 → review.unavailable（不再静默）")
        void reviewUnavailableWhenWordsVanished() {
            when(reviewScheduler.getDueReviewWords(PLAYER_ID)).thenReturn(List.of("gone"));

            assertTrue(run("review"));
            assertSaid("review.unavailable");
        }

        @Test
        @DisplayName("review：决斗中先报 duel.finish-duel-first")
        void reviewBlockedByDuel() {
            when(duelManager.isInDuel(PLAYER_ID)).thenReturn(true);
            when(reviewScheduler.getDueReviewWords(PLAYER_ID)).thenReturn(List.of("apple"));

            assertTrue(run("review"));
            assertSaid("duel.finish-duel-first");
            verify(wordLearningManager, never()).startReviewQuiz(any(Player.class));
        }

        @Test
        @DisplayName("history / wrong 各开自己的界面")
        void historyAndWrongOpenTheirGuis() {
            assertTrue(run("history"));
            assertEquals(1, chatHistoryCtor.constructed().size());

            assertTrue(run("wrong"));
            assertEquals(1, wrongBookCtor.constructed().size());
        }

        @Test
        @DisplayName("wrong / review 都受 word-system 门约束")
        void wordSystemGateCoversWrongAndReview() {
            when(configManager.isWordSystemEnabled()).thenReturn(false);

            assertTrue(run("wrong"));
            assertTrue(run("review"));
            assertEquals(List.of("module.disabled", "module.disabled"), keysSent);
            assertEquals(0, wrongBookCtor.constructed().size());
        }

        @Test
        @DisplayName("guide 委托给 OnboardingManager（命令层不自判）")
        void guideDelegatesToManager() {
            assertTrue(run("guide"));
            verify(onboardingManager).handleGuideCommand(player);
        }

        @Test
        @DisplayName("dialogue：模块关 → module.disabled；无参 → 场景选择 GUI")
        void dialogueGateAndSelector() {
            when(configManager.isAIChatEnabled()).thenReturn(false);
            assertTrue(run("dialogue"));
            assertSaid("module.disabled");
            assertEquals(0, scenarioSelectCtor.constructed().size());

            keysSent.clear();
            when(configManager.isAIChatEnabled()).thenReturn(true);
            assertTrue(run("dialogue"));
            assertEquals(1, scenarioSelectCtor.constructed().size());
        }

        @Test
        @DisplayName("dialogue：已在进行中 → removePlayer（退出而非重开）")
        void dialogueSecondRunExits() {
            when(chatScenarioListener.isInScenarioMode(player)).thenReturn(true);

            assertTrue(run("dialogue", "cafe"));
            verify(chatScenarioListener).removePlayer(player);
            assertEquals(0, scenarioSelectCtor.constructed().size());
        }

        @Test
        @DisplayName("dialogue：未知场景 → dialogue.scenario-not-found（不回显原始 key 当场景名）")
        void dialogueUnknownScenario() {
            assertTrue(run("dialogue", "no_such_scene"));
            assertSaid("dialogue.scenario-not-found");
            verify(aiChatManager, never()).startConversation(any(), anyString());
        }

        @Test
        @DisplayName("dialogue：AI 未配置 → dialogue.ai-not-configured（命令路径也预检）")
        void dialoguePrechecksAiAvailability() {
            when(aiChatManager.getScenarioConfigs(any(Player.class)))
                    .thenReturn(scenarioMap("cafe"));
            when(aiChatManager.isAvailable()).thenReturn(false);

            assertTrue(run("dialogue", "cafe"));
            assertSaid("dialogue.ai-not-configured");
            verify(aiChatManager, never()).startConversation(any(), anyString());
        }

        @Test
        @DisplayName("dialogue：答题中 → dialogue.finish-quiz-first；对话中 → dialogue.chat-active")
        void dialogueMutualExclusion() {
            when(aiChatManager.getScenarioConfigs(any(Player.class)))
                    .thenReturn(scenarioMap("cafe"));
            when(wordLearningManager.hasActiveQuiz(PLAYER_ID)).thenReturn(true);

            assertTrue(run("dialogue", "cafe"));
            assertSaid("dialogue.finish-quiz-first");
            verify(aiChatManager, never()).startConversation(any(), anyString());

            keysSent.clear();
            when(wordLearningManager.hasActiveQuiz(PLAYER_ID)).thenReturn(false);
            when(aiChatManager.hasActiveConversation(player)).thenReturn(true);

            assertTrue(run("dialogue", "cafe"));
            assertSaid("dialogue.chat-active");
            verify(aiChatManager, never()).startConversation(any(), anyString());
        }

        @Test
        @DisplayName("save_scenario：无对话 → save-no-conversation；名字非法 → save-name-invalid")
        void saveScenarioValidation() {
            assertTrue(run("save_scenario", "Good_Name"));
            assertSaid("scenario.save-no-conversation");

            keysSent.clear();
            when(aiChatManager.hasActiveConversation(player)).thenReturn(true);
            when(aiChatManager.getCurrentSystemPrompt(player)).thenReturn("prompt");

            assertTrue(run("save_scenario", "bad name!"));
            assertSaid("scenario.save-name-invalid");

            keysSent.clear();
            assertTrue(run("save_scenario", "x".repeat(33)));
            assertSaid("scenario.save-name-invalid");
        }

        @Test
        @DisplayName("save_scenario：缺名字参数 → general.error(usage-save-scenario)")
        void saveScenarioMissingName() {
            when(aiChatManager.hasActiveConversation(player)).thenReturn(true);

            assertTrue(run("save_scenario"));
            assertSaid("error.usage-save-scenario");
        }

        @Test
        @DisplayName("load_scenario：无权限 → load-no-permission（先于参数校验）")
        void loadScenarioPermissionFirst() {
            assertTrue(run("load_scenario"));
            assertSaid("scenario.load-no-permission");
        }

        @Test
        @DisplayName("load_scenario：非法码 → load-invalid；合法码 → load-success")
        void loadScenarioDecodeBranches() {
            when(player.hasPermission("letslearnenglish.scenario.import")).thenReturn(true);

            assertTrue(run("load_scenario", "not_base64_@@@"));
            assertSaid("scenario.load-invalid");

            keysSent.clear();
            String code = com.letslearnenglish.minecraftplugin.core.ai.ScenarioCodec
                    .encode("MyScene", "a prompt");
            when(aiChatManager.importPersonalScenario(any(Player.class), anyString(), anyString()))
                    .thenReturn(new AIChatManager.ImportResult("personal:myscene", false, false, false));

            assertTrue(run("load_scenario", code));
            // 「已加载」与「已更新」是两条不同的文案：updated=false 走 load-success
            assertSaid("scenario.load-success");
            assertNeverSaid("scenario.load-updated");
        }

        /** 同一条导入路径的另一半：覆盖同名场景时必须是 load-updated（不是 load-success）。 */
        @Test
        @DisplayName("load_scenario：覆盖已有场景 → load-updated（不是 load-success）")
        void loadScenarioUpdateBranch() {
            when(player.hasPermission("letslearnenglish.scenario.import")).thenReturn(true);
            String code = com.letslearnenglish.minecraftplugin.core.ai.ScenarioCodec
                    .encode("MyScene", "a prompt");
            when(aiChatManager.importPersonalScenario(any(Player.class), anyString(), anyString()))
                    .thenReturn(new AIChatManager.ImportResult("personal:myscene", true, false, false));

            assertTrue(run("load_scenario", code));
            assertSaid("scenario.load-updated");
            assertNeverSaid("scenario.load-success");
        }

        @Test
        @DisplayName("load_scenario：达到个人场景上限 → limit-reached（不报成功）")
        void loadScenarioLimitReached() {
            when(player.hasPermission("letslearnenglish.scenario.import")).thenReturn(true);
            String code = com.letslearnenglish.minecraftplugin.core.ai.ScenarioCodec
                    .encode("MyScene", "a prompt");
            when(aiChatManager.importPersonalScenario(any(Player.class), anyString(), anyString()))
                    .thenReturn(new AIChatManager.ImportResult(null, false, false, true));

            assertTrue(run("load_scenario", code));
            assertSaid("scenario.limit-reached");
            assertNeverSaid("scenario.load-success");
        }

        @Test
        @DisplayName("load_scenario：写盘失败 → load-save-failed（不报成功）")
        void loadScenarioSaveFailed() {
            when(player.hasPermission("letslearnenglish.scenario.import")).thenReturn(true);
            String code = com.letslearnenglish.minecraftplugin.core.ai.ScenarioCodec
                    .encode("MyScene", "a prompt");
            when(aiChatManager.importPersonalScenario(any(Player.class), anyString(), anyString()))
                    .thenReturn(new AIChatManager.ImportResult(null, false, true, false));
            // 这条分支会 plugin.getLogger().warning(...) —— 用真实 Logger 而不是 mock：
            // java.util.logging.Logger 虽是普通类可 mock，但真 Logger 更省事且不会引入
            // 「日志调用被打桩吞掉」这种看不见的盲区。
            when(plugin.getLogger())
                    .thenReturn(java.util.logging.Logger.getLogger("LearnCommandParsingTest"));

            assertTrue(run("load_scenario", code));
            assertSaid("scenario.load-save-failed");
            assertNeverSaid("scenario.load-success");
        }
    }

    // ==================================================================
    //  组 8 —— tab 补全 ↔ onCommand 一致性
    // ==================================================================

    @Nested
    @DisplayName("组 8 —— tab 补全必须与 onCommand 同源")
    class TabCompleteConsistency {

        @Test
        @DisplayName("一级补全 = SUBCOMMANDS 全集（且每个都能被 onCommand 吃下）")
        void firstArgCompletionsMatchDispatchTable() {
            List<String> offered = complete("");
            assertEquals(subcommandTokens, offered,
                    "一级补全必须与 SUBCOMMANDS 同序同集合喵（漂移 = 玩家按 Tab 看到会报错的候选）");

            for (String token : offered) {
                int before = keysSent.size();
                run(token);
                assertFalse(keysSent.subList(before, keysSent.size()).contains("general.unknown-command"),
                        "补全给出的 '" + token + "' 被 onCommand 判成未知命令喵");
            }
        }

        @Test
        @DisplayName("一级补全按前缀过滤（/le w → word / wrong；不给全部）")
        void firstArgCompletionsArePrefixFiltered() {
            List<String> offered = complete("w");
            assertTrue(offered.contains("word"), "前缀 'w' 必须给出 word 喵：" + offered);
            assertTrue(offered.contains("wrong"), "前缀 'w' 必须给出 wrong 喵：" + offered);
            assertFalse(offered.contains("checkin"), "前缀 'w' 不该给出 checkin 喵：" + offered);
        }

        /** 补全给出的难度 token 必须都能通过 {@code resolveDifficulty}。 */
        @Test
        @DisplayName("word 第二参数：补全里的每个难度都能真开局")
        void wordDifficultyCompletionsAreAccepted() {
            List<String> offered = complete("word", "");
            assertEquals(List.of("beginner", "intermediate", "advanced"), offered);

            for (String difficulty : offered) {
                keysSent.clear();
                // 每格清一次调用记录：verify(...) 默认 times(1) 会随循环累积，
                // 不清的话第二轮就红（而红的原因与「补全是否被接受」无关）。
                clearInvocations(wordLearningManager);
                run("word", difficulty);
                assertNeverSaid("word.invalid-difficulty");
                verify(wordLearningManager).startQuiz(eq(player), eq(difficulty), anyString());
            }
        }

        /** 补全给出的模式 token 必须都能通过 {@code isKnownMode && isEnabled}。 */
        @Test
        @DisplayName("word 第三参数：补全里的每个模式都能真开局；被关的模式不出现在候选里")
        void wordModeCompletionsAreAccepted() {
            assertEquals(List.of("spelling", "choice", "fill_blank"), complete("word", "beginner", ""));

            for (String mode : complete("word", "beginner", "")) {
                keysSent.clear();
                clearInvocations(wordLearningManager);
                run("word", "beginner", mode);
                assertNeverSaid("word.invalid-mode");
                assertNeverSaid("word.mode-disabled");
                verify(wordLearningManager).startQuiz(player, "beginner", mode);
            }

            // 关掉一个模式：它必须同时从补全里消失（而不是留在候选里按了报错）
            mainConfig.set("word-system.modes.spelling.enabled", false);
            List<String> narrowed = complete("word", "beginner", "");
            assertFalse(narrowed.contains("spelling"),
                    "被管理员关闭的模式必须从补全候选里剔除喵：" + narrowed);
            assertTrue(narrowed.contains("choice"), "其余模式保留喵：" + narrowed);
        }

        /**
         * duel 的动词补全：每个 token 都必须被 onCommand 当<b>动词</b>处理，
         * 而不是「当成玩家名 → 查不到 → duel.player-offline」。
         *
         * <p>变异指向：删掉 {@code else if ("accept".equals(sub))} 之类的分支 → 这里红。
         */
        @Test
        @DisplayName("duel 第二参数：accept/quit/gui 都必须被当作动词（不是玩家名）")
        void duelVerbCompletionsAreConsumedAsVerbs() {
            when(player.hasPermission("letslearnenglish.admin")).thenReturn(false);
            List<String> offered = complete("duel", "");
            assertTrue(offered.containsAll(List.of("accept", "quit", "gui")),
                    "duel 第二参数必须补全三个动词喵：" + offered);
            assertFalse(offered.contains("cancel"),
                    "无 admin 权限者不该看到 cancel 候选喵：" + offered);

            for (String verb : List.of("accept", "quit", "gui")) {
                keysSent.clear();
                run("duel", verb);
                assertNeverSaid("duel.player-offline");
                assertNeverSaid("duel.usage");
            }
            verify(duelManager).acceptDuel(player);
            verify(duelManager).quitDuel(player);
            verify(duelManager).reopenDuelGUI(player);
        }

        @Test
        @DisplayName("duel 第二参数：admin 才看得到 cancel")
        void duelCancelCompletionIsAdminOnly() {
            when(player.hasPermission("letslearnenglish.admin")).thenReturn(true);
            assertTrue(complete("duel", "").contains("cancel"));
            assertTrue(complete("duel", "ca").contains("cancel"));
        }

        @Test
        @DisplayName("duel cancel 第三参数补玩家名；admin 之外为空")
        void duelCancelThirdArgCompletesPlayers() {
            Player bob = mock(Player.class);
            when(bob.getName()).thenReturn("Bob");
            onlinePlayers.add(bob);

            when(player.hasPermission("letslearnenglish.admin")).thenReturn(true);
            List<String> offered = complete("duel", "cancel", "");
            assertTrue(offered.contains("Bob"), "admin 必须能补全在线玩家名喵：" + offered);

            when(player.hasPermission("letslearnenglish.admin")).thenReturn(false);
            assertTrue(complete("duel", "cancel", "").isEmpty(),
                    "无 admin 权限者的 cancel 第三参数不得补全玩家名喵");
        }

        /** square 补全的每个 token 都必须能映射到一个非 null 的 RankingMode。 */
        @Test
        @DisplayName("square 第二参数：每个补全 token 都能映射到非 null 模式")
        void squareCompletionsAllMapToRealModes() {
            List<String> offered = complete("square", "");
            assertEquals(List.of("feed", "weekly", "monthly", "alltime", "all"), offered);

            for (String token : offered) {
                keysSent.clear();
                // 同理：captor + verify(times(1)) 会随循环累积，必须每格清一次。
                clearInvocations(socialListener);
                run("square", token);
                org.mockito.ArgumentCaptor<RankingMode> captor =
                        org.mockito.ArgumentCaptor.forClass(RankingMode.class);
                verify(socialListener).openSquareWithSnapshot(eq(player), captor.capture());
                assertNotNull(captor.getValue(),
                        "补全给出的 '" + token + "' 在 onCommand 里映射到了 null（走默认落点）喵 —— "
                                + "补全与白名单漂移了。");
            }
        }

        @Test
        @DisplayName("关闭态：补全返回空表而不是 null（Bukkit 契约）")
        void disabledServerReturnsEmptyNotNull() {
            when(plugin.isFullyEnabled()).thenReturn(false);

            // 同上的歧义理由：complete(...) 有返回值，不包 assertDoesNotThrow。
            List<String> offered = complete("word", "");
            assertNotNull(offered, "补全不得返回 null 喵（Bukkit 会当成协议错误）");
            assertTrue(offered.isEmpty());
        }

        @Test
        @DisplayName("args 为空数组不崩（不得 ArrayIndexOutOfBounds）")
        void emptyArgsDoNotThrow() {
            List<String> offered = complete(new String[0]);
            assertTrue(offered.isEmpty(), "长度为 0 时没有可补全内容，应返回空表喵");
        }
    }

    // ==================================================================
    //  组 9 —— 源码 lint：两份分派清单必须同源
    // ==================================================================

    @Nested
    @DisplayName("组 9 —— 分派表源码 lint")
    class SubcommandTableLint {

        /**
         * 判定函数自检：先在合成源码上验两个方向，再应用到真实文件 ——
         * 否则「正则不命中」与「真实文件正确」在全绿输出里不可区分。
         */
        @Test
        @DisplayName("抽取器自检：能从合成源码里读出两份清单，且不误吃注释")
        void extractorSelfCheck() {
            String synthetic = String.join("\n",
                    "class Probe {",
                    "  // SUBCOMMANDS 的注释里提到 Arrays.asList(\"ghost\")",
                    "  private static final List<String> SUBCOMMANDS = Arrays.asList(",
                    "          \"menu\", \"word\", \"duel\"",
                    "  );",
                    "  public boolean onCommand(String[] args) {",
                    "    String subCommand = args[0].toLowerCase();",
                    "    /* switch (subCommand) { case \"commented_out\": } */",
                    "    switch (subCommand) {",
                    "      case \"menu\":",
                    "      case \"word\":",
                    "        return true;",
                    "      case \"duel\":",
                    "        return true;",
                    "      default:",
                    "        return true;",
                    "    }",
                    "  }",
                    "}");

            assertEquals(List.of("menu", "word", "duel"), advertisedSubcommands(synthetic),
                    "自检失败：SUBCOMMANDS 抽取器读错了（或把注释里的 ghost 吃了）");
            assertEquals(List.of("menu", "word", "duel"), dispatchedCaseLabels(synthetic),
                    "自检失败：case 标签抽取器把注释里的 commented_out 也算进去了");
        }

        @Test
        @DisplayName("SUBCOMMANDS 与 switch 的 case 集合完全一致（双向）")
        void listAndSwitchAreInSync() throws IOException {
            String source = readSource();
            List<String> advertised = advertisedSubcommands(source);
            List<String> dispatched = dispatchedCaseLabels(source);

            assertTrue(advertised.size() >= 20,
                    "只读出 " + advertised.size() + " 个 SUBCOMMANDS token —— 锚点失效");
            assertTrue(dispatched.size() >= 20,
                    "只读出 " + dispatched.size() + " 个 case 标签 —— 锚点失效（不许空转通过）");

            Set<String> missing = new LinkedHashSet<>(advertised);
            missing.removeAll(dispatched);
            assertEquals(Set.of(), missing,
                    "这些子命令出现在 tab 补全里但没有 switch 分支喵：" + missing);

            Set<String> ghost = new LinkedHashSet<>(dispatched);
            ghost.removeAll(advertised);
            assertEquals(Set.of(), ghost,
                    "这些 switch 分支没有被 SUBCOMMANDS 覆盖喵（玩家敲得通但 Tab 不提示）：" + ghost);

            assertEquals(advertised.size(), dispatched.size(),
                    "两份清单同集合但数量不同 —— 说明有重复 token：" + advertised + " vs " + dispatched);
        }

        @Test
        @DisplayName("默认分支必须报 general.unknown-command（不是静默 return）")
        void defaultBranchReportsUnknownCommand() throws IOException {
            String code = stripComments(readSource());
            int sw = code.indexOf("switch (subCommand)");
            assertTrue(sw >= 0, "找不到 switch (subCommand) 喵 —— 锚点失效");
            int open = code.indexOf('{', sw);
            int close = matchingBrace(code, open);
            assertTrue(close > open, "switch 花括号不配对喵");
            String body = code.substring(open, close);

            int defaultAt = body.lastIndexOf("default:");
            assertTrue(defaultAt >= 0, "switch 里没有 default 分支喵");
            assertTrue(body.substring(defaultAt).contains("general.unknown-command"),
                    "default 分支里找不到 general.unknown-command 喵 —— 敲错子命令会静默无事发生，"
                            + "玩家分不清『没这个命令』和『命令坏了』");
        }
    }

    // ==================================================================
    //  组 10 —— i18n：真跑出来的 key 必须在两份语言文件里都存在
    // ==================================================================

    @Nested
    @DisplayName("组 10 —— 真跑出来的 i18n key 都存在（zh + en）")
    class I18nKeysExist {

        @Test
        @DisplayName("遍历全部分支后收集的 key 全部存在于 zh.yml 与 en.yml")
        void everyKeyCollectedFromRealRunsExistsInBothLanguages() throws IOException {
            collectKeysFromBroadRun();

            assertTrue(keysSent.size() >= 25,
                    "只收集到 " + keysSent.size() + " 个 key —— 覆盖面太窄，本用例会退化成空转："
                            + keysSent);

            FileConfiguration zh = loadYaml(ZH_YML);
            FileConfiguration en = loadYaml(EN_YML);

            List<String> missingZh = new ArrayList<>();
            List<String> missingEn = new ArrayList<>();
            for (String key : new LinkedHashSet<>(keysSent)) {
                if (!zh.isSet(key)) missingZh.add(key);
                if (!en.isSet(key)) missingEn.add(key);
            }
            assertEquals(List.of(), missingZh,
                    "LearnCommand 会取这些 key 但 zh.yml 里没有喵（玩家看到 &c[?key] 占位符）：" + missingZh);
            assertEquals(List.of(), missingEn,
                    "LearnCommand 会取这些 key 但 en.yml 里没有喵（英文服看到 [?key]）：" + missingEn);
        }

        /** 同一条 key 在两份文件里的占位符集合必须一致，否则英文服装不下实参。 */
        @Test
        @DisplayName("收集到的 key 在 zh/en 的 {占位符} 集合一致")
        void placeholdersMatchAcrossLanguages() throws IOException {
            collectKeysFromBroadRun();

            FileConfiguration zh = loadYaml(ZH_YML);
            FileConfiguration en = loadYaml(EN_YML);

            List<String> mismatches = new ArrayList<>();
            for (String key : new LinkedHashSet<>(keysSent)) {
                if (!zh.isSet(key) || !en.isSet(key)) continue;
                Set<String> z = placeholders(zh.getString(key));
                Set<String> e = placeholders(en.getString(key));
                if (!z.equals(e)) {
                    mismatches.add(key + " zh=" + z + " en=" + e);
                }
            }
            assertEquals(List.of(), mismatches, "zh/en 占位符集合不一致喵：" + mismatches);
        }

        /** 把所有分支尽量走一遍，把 key 收集到 {@link #keysSent}。 */
        private void collectKeysFromBroadRun() {
            // 20 个子命令（无参）
            for (String token : subcommandTokens) {
                run(token);
            }
            // 各拒绝/成功分支
            run("totally_bogus");
            run("word", "beginner");
            run("word", "beginner", "choice");
            run("word", "beginner", "fill_blank");   // 第三个模式的 label 也要收到
            run("word", "beginner", "telepathy");
            run("word", "nonsense");
            // D7：多余参数的两条新键（word.extra-arguments / word.usage）也必须真跑出来，
            // 否则组 10 的「key 在两份语言文件里都存在」对它们是空转。
            run("word", "beginner", "choice", "3");
            run("review");
            run("duel");
            run("duel", "cancel", "Bob");           // 无权限 → general.no-permission
            run("duel", "bob");
            run("duel", "accept", "now");           // D7：duel.usage
            run("challenge");
            run("season");
            run("class");
            run("profile", "Bob");
            run("save_scenario");
            run("load_scenario");

            // 赛季正向分支：不桩的话 season.* / leaderboard.* 六个 key 全被跳过，
            // 那批文案就完全没有「真跑出来的 key 存在」这层保护。
            SeasonManager season = mock(SeasonManager.class);
            when(season.isEnabled()).thenReturn(true);
            when(season.getCurrentTopCached(50)).thenReturn(List.of());
            when(season.getRankOf(any(UUID.class))).thenReturn(-1);
            when(plugin.getSeasonManager()).thenReturn(season);
            run("season");
            when(plugin.getSeasonManager()).thenReturn(null);

            when(configManager.isWordSystemEnabled()).thenReturn(false);
            run("word");
            run("wrong");
            when(configManager.isWordSystemEnabled()).thenReturn(true);

            when(reviewScheduler.getDueReviewWords(PLAYER_ID)).thenReturn(List.of("apple"));
            when(wordLearningManager.startReviewQuiz(player)).thenReturn(mock(LearningSession.class));
            run("review");
            // 有到期词但开不出局 → review.unavailable
            when(wordLearningManager.startReviewQuiz(any(Player.class))).thenReturn(null);
            run("review");

            when(checkInManager.checkIn(any(Player.class)))
                    .thenReturn(new CheckInManager.CheckInResult(true, 3, 0.20, true, true, false, 5.0));
            run("checkin");
            // 存储失败分支（与 checkin.already 语义不同，key 也不同）
            when(checkInManager.checkIn(any(Player.class)))
                    .thenReturn(new CheckInManager.CheckInResult(false, 0, 0, false, false, true, 0));
            run("checkin");

            when(configManager.isCheckinEnabled()).thenReturn(false);
            run("checkin");
            when(configManager.isCheckinEnabled()).thenReturn(true);

            when(configManager.isDuelEnabled()).thenReturn(false);
            run("duel", "accept");
            when(configManager.isDuelEnabled()).thenReturn(true);

            // 场景导入/导出两条完整链（含无权限、缺参、非法名、非法码、上限、写盘失败）
            run("load_scenario");
            when(player.hasPermission("letslearnenglish.scenario.import")).thenReturn(true);
            run("load_scenario");
            run("load_scenario", "not_base64_@@@");
            String code = com.letslearnenglish.minecraftplugin.core.ai.ScenarioCodec
                    .encode("MyScene", "prompt");
            when(aiChatManager.importPersonalScenario(any(Player.class), anyString(), anyString()))
                    .thenReturn(new AIChatManager.ImportResult(null, false, false, true));
            run("load_scenario", code);
            when(aiChatManager.importPersonalScenario(any(Player.class), anyString(), anyString()))
                    .thenReturn(new AIChatManager.ImportResult(null, false, true, false));
            when(plugin.getLogger())
                    .thenReturn(java.util.logging.Logger.getLogger("LearnCommandParsingTest"));
            run("load_scenario", code);

            when(aiChatManager.hasActiveConversation(player)).thenReturn(true);
            run("save_scenario");                   // error.usage-save-scenario
            run("save_scenario", "bad name!");      // scenario.save-name-invalid
            when(aiChatManager.getCurrentSystemPrompt(player)).thenReturn("p");
            run("save_scenario", "Good_Name");      // scenario.save-success

            when(configManager.isAIChatEnabled()).thenReturn(false);
            run("dialogue");
            when(configManager.isAIChatEnabled()).thenReturn(true);

            // 隐身目标 → profile.hidden
            Player bob = mock(Player.class);
            when(bob.getName()).thenReturn("Bob");
            when(bob.getUniqueId()).thenReturn(BOB_ID);
            when(server.getPlayer(anyString())).thenReturn(bob);
            PlayerDataStore.ProfileSettings hidden = new PlayerDataStore.ProfileSettings();
            hidden.setShowInSquare(false);
            when(playerDataStore.getProfileSettingsReadOnly(BOB_ID)).thenReturn(hidden);
            run("profile", "Bob");

            // 对话三态：未知场景 / AI 未配置 / 答题中 / 对话中 / 正常开启
            when(aiChatManager.getScenarioConfigs(any(Player.class)))
                    .thenReturn(scenarioMap("cafe"));
            run("dialogue", "no_such_scene");
            when(aiChatManager.isAvailable()).thenReturn(false);
            run("dialogue", "cafe");
            when(aiChatManager.isAvailable()).thenReturn(true);
            when(wordLearningManager.hasActiveQuiz(PLAYER_ID)).thenReturn(true);
            run("dialogue", "cafe");
            when(wordLearningManager.hasActiveQuiz(PLAYER_ID)).thenReturn(false);
            run("dialogue", "cafe");                // 正常开启：started + instructions

            // 对话中开答题 → dialogue.chat-active
            when(aiChatManager.hasActiveConversation(player)).thenReturn(true);
            run("word", "beginner");
            when(aiChatManager.hasActiveConversation(player)).thenReturn(false);

            // 决斗中 → duel.finish-duel-first（word / review / dialogue 三处共用同一键）
            when(duelManager.isInDuel(PLAYER_ID)).thenReturn(true);
            run("word", "beginner");
            when(duelManager.isInDuel(PLAYER_ID)).thenReturn(false);

            // 挑战全完成但经济未开 → daily-challenge.economy-disabled
            when(dailyChallengeManager.getTodayWordProgress(any(Player.class)))
                    .thenReturn(new int[] {2, 2});
            run("challenge");

            // 有行为挑战 → behavioral-* 三态
            when(dailyChallengeManager.getTodayChallenges(PLAYER_ID)).thenReturn(List.of(
                    new DailyChallengeManager.ChallengeProgress(
                            DailyChallengeManager.ChallengeType.ANSWER_10, 3, false, false),
                    new DailyChallengeManager.ChallengeProgress(
                            DailyChallengeManager.ChallengeType.SESSION_3, 3, true, true)));
            run("challenge");
        }

        private Set<String> placeholders(String text) {
            Set<String> out = new java.util.TreeSet<>();
            if (text == null) return out;
            Matcher m = Pattern.compile("\\{([a-z0-9_\\-]+)}").matcher(text);
            while (m.find()) {
                if (!"prefix".equals(m.group(1))) { // MessageUtil 统一填充，不是调用点实参
                    out.add(m.group(1));
                }
            }
            return out;
        }
    }

    // ==================================================================
    //  组 11 —— 主线程契约（正向守卫 + 已知缺陷）
    // ==================================================================

    @Nested
    @DisplayName("组 11 —— 主线程 / 查库契约")
    class MainThreadContract {

        private static final String ASYNC = "runTaskAsynchronously";

        /**
         * 正向守卫（当前绿）：{@code /le class} 的查库必须全部落在
         * {@code runTaskAsynchronously} 的 lambda 里 —— 主线程只做权限/配置判定。
         *
         * <p>变异指向：把 {@code db.getPlayerClassId(uuid)} 提到 lambda 外面
         * （或删掉 lambda 直接同步查）→ 这条红。MySQL 远程库 + 30s socketTimeout
         * 下这类退化会让<b>全服卡死</b>而功能完全正常，是唯一能静态守住的地方
         * （{@code MainThreadIoContractTest} 只扫 {@code gui/} 与 {@code listener/}，
         * 不覆盖 {@code command/}）。
         */
        @Test
        @DisplayName("handleClassCommand：四个查库方法都必须出现在 runTaskAsynchronously 之后")
        void classCommandKeepsIoOffMainThread() throws IOException {
            String body = methodBody(readSource(), "private void handleClassCommand(Player player)");
            assertFalse(body.isEmpty(), "找不到 handleClassCommand 的方法体喵 —— 锚点失效");

            int asyncAt = body.indexOf(ASYNC);
            assertTrue(asyncAt >= 0,
                    "handleClassCommand 里找不到 runTaskAsynchronously 喵 —— 主线程直接查库了");

            for (String dbCall : List.of("getPlayerClassId", "listClasses",
                    "getClassMembers", "getRankedPlayersForPeriodFiltered")) {
                int at = body.indexOf(dbCall);
                assertTrue(at >= 0,
                        "handleClassCommand 里找不到 " + dbCall + " 喵 —— 班级榜的取数链少了这一环");
                assertTrue(at > asyncAt,
                        dbCall + " 出现在 runTaskAsynchronously <b>之前</b>喵 —— 主线程同步查库。"
                                + "远程 MySQL 抖动时全服 TPS 会跟着这个命令走。");
            }
        }

        /**
         * <b>R31 已修</b>：{@code /le challenge} 曾在本<b>主线程</b>同步查库四次。
         *
         * <p>调用链（修前）：{@code getTodayWordProgress} → {@code DatabaseManager
         * .getDailyChallengeWordsProgress}；{@code getTodayWordDetails} →
         * {@code loadDailyChallengeWords}（+ 逐词 {@code isWordCompleted}）；
         * {@code isWordRewardPaidToday} → {@code isDailyWordRewardClaimed}；
         * {@code getTodayChallenges} → {@code repository.findTodayChallenges}（+ 降级写）。
         * 四条都是 JDBC。
         *
         * <p><b>为什么当时 lint 抓不到（两个洞叠加）</b>：
         * <ol>
         *   <li>{@code MainThreadIoContractTest.SCAN_DIRS} 不含 {@code command/} —— 整目录盲区；</li>
         *   <li>即使把 {@code command/} 加进去也仍<b>零命中</b>：命令层调的是
         *       {@code DailyChallengeManager} 的<i>门面</i>方法，这些名字不在
         *       {@code DB_READ_METHODS} 里（R31 实测确认）。</li>
         * </ol>
         * 两个洞本轮都已补：{@code SCAN_DIRS} 加了 {@code command/}，
         * {@code DB_READ_METHODS} 加了四个门面名。
         *
         * <p>本轮修法：四次取数挪进 {@code runTaskAsynchronously}，结果经
         * {@code runTaskSafe} 回主线程由 {@code renderChallenge} 渲染（沿用
         * {@code handleClassCommand} 的形状）。奖励金额与经济可用性读内存，
         * 留在主线程先算好。
         *
         * <p>本用例是<b>源码 lint</b>（与 lint 同源，但独立成条）：它守住的是方法体的形状。
         */
        @Test
        @DisplayName("handleChallengeCommand 不得在主线程同步查库（四次取数全在 runTaskAsynchronously 之后）")
        void challengeCommandHasNoMainThreadIo() throws IOException {
            String body = methodBody(readSource(), "private void handleChallengeCommand(Player player)");
            assertFalse(body.isEmpty(), "找不到 handleChallengeCommand 的方法体喵");

            int asyncAt = body.indexOf("runTaskAsynchronously");
            assertTrue(asyncAt >= 0,
                    "handleChallengeCommand 里找不到 runTaskAsynchronously 喵 —— 主线程又直接查库了");

            for (String dbCall : List.of("getTodayWordProgress", "getTodayWordDetails",
                    "isWordRewardPaidToday", "getTodayChallenges")) {
                int at = body.indexOf(dbCall);
                assertTrue(at >= 0,
                        "handleChallengeCommand 里找不到 " + dbCall + " 喵 —— 取数链少了这一环"
                                + "（那等于挑战页数据永远不回来）");
                assertTrue(at > asyncAt,
                        dbCall + " 出现在 runTaskAsynchronously <b>之前</b>喵 —— 主线程同步查库。"
                                + "远程 MySQL 抖动时全服卡死，而功能表现完全正常。");
            }
        }
    }

    // ==================================================================
    //  源码工具（lint 用）
    // ==================================================================

    private static String readSource() throws IOException {
        assertTrue(Files.isRegularFile(SOURCE),
                "找不到 " + SOURCE.toAbsolutePath() + "（测试必须以仓库根为工作目录）喵");
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    private static FileConfiguration loadYaml(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path),
                "找不到 " + path.toAbsolutePath() + " 喵（测试必须以仓库根为工作目录）");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            // 用 loadFromString 而不是 loadConfiguration(File)：后者在解析失败时走 Bukkit
            // 的 logger，纯 JVM 下 Bukkit.server 为 null → NPE，坏 YAML 会伪装成空指针。
            yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new AssertionError("解析 " + path + " 失败喵（YAML 语法/缩进错误）："
                    + e.getMessage(), e);
        }
        return yaml;
    }

    /** {@code SUBCOMMANDS = Arrays.asList(...)} 里的 token（已剥注释）。 */
    private static List<String> advertisedSubcommands(String rawSource) {
        String code = stripComments(rawSource);
        int field = code.indexOf("SUBCOMMANDS");
        if (field < 0) return List.of();
        int listAt = code.indexOf("Arrays.asList(", field);
        if (listAt < 0) return List.of();
        int open = listAt + "Arrays.asList".length();
        int close = code.indexOf(')', open);
        if (close < 0) return List.of();
        return stringLiterals(code.substring(open, close));
    }

    /** {@code switch (subCommand) { ... }} 里的 {@code case "x":} 标签（已剥注释）。 */
    private static List<String> dispatchedCaseLabels(String rawSource) {
        String code = stripComments(rawSource);
        int sw = code.indexOf("switch (subCommand)");
        if (sw < 0) return List.of();
        int open = code.indexOf('{', sw);
        int close = matchingBrace(code, open);
        if (open < 0 || close < 0) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("case\\s+\"([A-Za-z0-9_]+)\"\\s*:").matcher(code.substring(open, close));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 取一个方法体（含首尾花括号），签名必须与源码逐字一致。 */
    private static String methodBody(String rawSource, String signature) {
        String code = stripComments(rawSource);
        int at = code.indexOf(signature);
        if (at < 0) return "";
        int open = code.indexOf('{', at + signature.length());
        int close = matchingBrace(code, open);
        return (open < 0 || close < 0) ? "" : code.substring(open, close);
    }

    private static List<String> stringLiterals(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static int matchingBrace(String s, int leftBrace) {
        if (leftBrace < 0) return -1;
        int depth = 0;
        for (int i = leftBrace; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 剥掉行注释与块注释（<b>保留字符串字面量</b>，保留换行）。
     *
     * <p>为什么<b>不能</b>连字面量一起剥（与 {@code ServerToggleTest} 的同名工具的关键差别）：
     * 本组的判定对象恰恰是<b>字符串字面量里的 token</b>（{@code SUBCOMMANDS} 的元素、
     * {@code case "menu":} 的标签、{@code "general.unknown-command"}）。连字面量一起剥掉
     * 会让三个 lint 全部零命中 —— 那是「自证桩」的教科书形态：全绿，但什么都没检查。
     *
     * <p>为什么必须剥注释：本仓注释里到处引用被禁/被举例的代码片段（本文件自己的 javadoc
     * 就在这么干），不剥的话第一次命中会是注释里的那一处。
     */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < s.length() && !(s.charAt(i) == '*' && i + 1 < s.length()
                        && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < s.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' || c == '\'') {
                // 字面量整体照搬（含转义），只把它内部的换行保留原样。
                char quote = c;
                out.append(c);
                i++;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    out.append(d == '\n' ? '\n' : d);
                    i++;
                    if (d == '\\' && i < s.length()) {
                        out.append(s.charAt(i) == '\n' ? '\n' : s.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
