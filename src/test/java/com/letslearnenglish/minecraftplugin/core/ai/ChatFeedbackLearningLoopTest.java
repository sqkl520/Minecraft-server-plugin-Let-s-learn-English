package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.ReviewScheduler;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RT（红队）实测缺口：{@code ChatFeedbackManager} 的「AI 纠错回接学习闭环」接线点。
 *
 * <p><b>为什么需要这个类</b>：现有测试里 {@code ChatFeedbackManager.applyToLearningLoop}
 * 整段被 {@code try/catch(Exception)} 包着 —— 删掉 {@code applyToLearningLoop(...)} 调用、
 * 把 {@code scheduleReview(..., true)} 的 {@code true} 改成 {@code false}、或让
 * {@code persistOnQuit} 不再落库，都只会让「不抛异常」和「点评文案照常」保持为真，
 * <b>没有任何红灯</b>。本类因此只断言<b>副作用</b>（错题本写入 / 复习排程分流 /
 * 目标词快照 / 落库行 / prompt 内容），不满足于「调用没抛异常」。
 *
 * <p><b>技术前提</b>（侦察阶段已逐条实测）：
 * <ul>
 *   <li>{@link ChatFeedbackManager} 构造器纯赋值，可直接 {@code new}；</li>
 *   <li>{@code getMainConfig()} 必须是<b>真</b> {@link YamlConfiguration} —— mock 的
 *       {@code getInt(key, def)} 会返回 0（默认值不生效），把阈值/展示上限读成 0；</li>
 *   <li>{@code deliverEnd} 读 {@code Bukkit.isPrimaryThread()}（bytecode 为
 *       {@code getstatic server} + {@code Server.isPrimaryThread()}）；mockStatic 默认 false
 *       会走进 {@code Bukkit.getScheduler().runTask(...)} 分支并在静态 mock 的 null 上 NPE，
 *       故本类一律在 {@link MockedStatic} 窗口内把它钉成 true；</li>
 *   <li>{@code persistOnQuit} 不碰 Bukkit 静态，<b>不需要</b> mockStatic。</li>
 * </ul>
 *
 * <p><b>STRICT_STUBS 分层纪律</b>（照 {@code GuiOpenIdentityTest} 的裁决）：公共
 * {@code setUp} 只建 mock（建 mock 不是 stub，不触发 UnnecessaryStubbing）；stub 按
 * {@code @Nested} 组下发，每条都由本组用例真实消费 —— 于是「某条 stub 变成死代码」
 * 会在下一次运行时立刻以 {@code UnnecessaryStubbingException} 暴露，而不是被 LENIENT 吞掉。
 */
@ExtendWith(MockitoExtension.class)
class ChatFeedbackLearningLoopTest {

    /** 会话所属玩家。 */
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    /** 会话 id（落库主键的一半）。 */
    private static final String SESSION_ID = "sess0001";

    private LetsLearnEnglish plugin;
    private AIChatManager aiChatManager;
    private PlayerDataStore dataStore;
    private WordManagerImpl wordManager;
    private ReviewScheduler reviewScheduler;
    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private MessageUtil messageUtil;
    private Player player;
    private Logger logger;

    /** 真配置：{@code getInt(key, def)} / {@code getBoolean(key, def)} 的默认值必须真的生效。 */
    private FileConfiguration mainConfig;

    private ChatFeedbackManager feedbackManager;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        aiChatManager = mock(AIChatManager.class);
        dataStore = mock(PlayerDataStore.class);
        wordManager = mock(WordManagerImpl.class);
        reviewScheduler = mock(ReviewScheduler.class);
        databaseManager = mock(DatabaseManager.class);
        configManager = mock(ConfigManager.class);
        messageUtil = mock(MessageUtil.class);
        player = mock(Player.class);
        logger = Logger.getLogger("ChatFeedbackLearningLoopTest");
        mainConfig = new YamlConfiguration();
        feedbackManager = new ChatFeedbackManager(plugin, aiChatManager);
    }

    // ------------------------------------------------------------------
    // 脚手架（全部显式、全部被本组用例消费）
    // ------------------------------------------------------------------

    /**
     * {@code deliverEnd} → {@code deliverEndMain} 的展示链最小依赖。
     *
     * <p>L1 之后这里必须铺 {@code getDatabaseManager()}：{@code writeTask} 的守卫已从
     * {@code plugin.isEnabled()} 改成「本类的 accepting/draining 双闸」，因此写任务不再在
     * 未启用态短路 —— 不铺 DB 时它会 NPE（被 catch 吞掉，但日志噪音 + 断言不再聚焦）。
     *
     * <p>用 {@code lenient()}：写任务在 commonPool 上异步执行，「是否已被消费」由调度时刻
     * 决定，不是本类用例的断言对象。STRICT_STUBS 若在这里报多余 stub，只会是时序抖动，
     * 而不是「测试写了死代码」。
     */
    private void stubDeliverBasics() {
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("msg");
        lenient().when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        // R11：upsertChatFeedback 已改为返回 boolean，mock 默认值是 false —— 不铺这条，
        // 本类的「成功落库」用例会静默走进失败补偿分支（登记一条永不重放的待补写）。
        // 用 lenient：写任务在 commonPool 上异步，是否已被消费由调度时刻决定。
        lenient().when(databaseManager.upsertChatFeedback(
                        any(), anyString(), anyString(), anyString(),
                        anyInt(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(true);
    }

    /** 玩家在线 + UUID（mock 的 {@code isOnline()} 默认是 false，不 stub 会静默早退成假绿）。 */
    private void stubOnlinePlayer() {
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);
    }

    /**
     * 日志门面：只在真的会走到 {@code applyToLearningLoop} 末尾那两行日志的组里铺
     * （组 C 在门处就 return，铺了就是多余 stub）。
     */
    private void stubLogger() {
        when(plugin.getLogger()).thenReturn(logger);
    }

    /**
     * 学习闭环写入面（错题本 + 复习排程）：只有真正走到写入段的组才铺。
     *
     * <p>组 C（单词系统关闭）在 {@code isWordSystemEnabled()} 处就 return 了，绝不会问
     * {@code getReviewScheduler()} —— 那条 stub 铺出来就是 STRICT_STUBS 下的多余代码，
     * 故本 helper 只服务组 A/B；组 C 用 {@link #stubLearningLoopFacades()}。
     */
    private void stubLearningLoopTargets() {
        stubLearningLoopFacades();
        when(wordManager.getReviewScheduler()).thenReturn(reviewScheduler);
    }

    /**
     * 学习闭环只到「取门面」为止的两个 stub（组 C 专用：写完这两个就 return 了）。
     */
    private void stubLearningLoopFacades() {
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getWordManager()).thenReturn(wordManager);
    }

    /**
     * 词库白名单：<b>必须用 {@code anyString()} 一次铺完</b>，不能按词逐个 stub。
     *
     * <p>踩过的坑（本类调试实录）：{@code getWord("go")} 单参 stub + 被测代码先问
     * {@code getWord("goes")} 会触发 Mockito 的 {@code PotentialStubbingProblem}
     * （strict stubs 的「同方法不同实参」早失败），而 {@code applyToLearningLoop} 的
     * {@code try/catch(Exception)} 会把它当普通异常吞掉 —— 于是测试自以为在测闭环，
     * 实际测的是「异常被静默」。
     *
     * @param bank 词库内单词（小写）
     */
    private void stubWordBank(String... bank) {
        java.util.Set<String> known = java.util.Set.of(bank);
        when(wordManager.getWord(anyString()))
                .thenAnswer(invocation -> known.contains((String) invocation.getArgument(0))
                        ? mock(Word.class) : null);
    }

    /**
     * 让 {@code deliverEndMain} 的 AI 点评通道「立刻回调成功」。
     *
     * <p>回调参数位是 {@code requestConversationSummary(Player, SessionSummary,
     * BiConsumer<ConversationFeedback,String>)} 的第 2 个实参（0 起算），故取
     * {@code getArgument(2)}。
     *
     * @param summary  被测会话快照
     * @param feedback 要回放的点评（成功分支：errorKey = null）
     */
    private void stubSummaryCallback(AIChatManager.SessionSummary summary,
                                     ConversationFeedback feedback) {
        stubSummaryCallback(summary, feedback, true);
    }

    /**
     * 同上，但显式指定防串台判据 {@code isLatestSession(...)} 的返回值。
     *
     * <p><b>为什么默认必须给 {@code true}</b>：R4-05-logic-F-L2 把
     * {@code handleSummaryResult} 的判据收口成单一权威 {@code isLatestSession(...)}，
     * 而 Mockito 对 {@code boolean} 的默认返回是 {@code false} —— 不补这条 stub，
     * <b>全部</b>用例都会被推进「源会话不是最近会话」的静默分支（notify=false、
     * 不显示 ai-header），把 {@code ai-header} / {@code wrong-book.auto-add} 的断言
     * 一并打红。本组用例的前置条件都是「点评属于最近一场」，所以默认 {@code true}。
     * 一处 helper 覆盖全部用例，断言语义一律不改；{@code false} 这条分支由
     * {@link LateSummaryFromEndedSessionDoesNotLeak} 正向覆盖。
     *
     * @param latest 源会话是否就是最近一场（{@code false} = 迟到点评，应走静默分支）
     */
    private void stubSummaryCallback(AIChatManager.SessionSummary summary,
                                     ConversationFeedback feedback, boolean latest) {
        when(aiChatManager.canRequestConversationSummary(player)).thenReturn(true);
        when(aiChatManager.getScenarioDisplayName(player, summary.scenarioKey()))
                .thenReturn("Village Trade");
        when(aiChatManager.isLatestSession(any(), anyString())).thenReturn(latest);
        doAnswer(invocation -> {
            BiConsumer<ConversationFeedback, String> callback = invocation.getArgument(2);
            callback.accept(feedback, null);
            return null;
        }).when(aiChatManager).requestConversationSummary(eq(player), eq(summary), any());
    }

    /** 达标会话快照（validInputs=3 ≥ 默认阈值 3，aiReplyCount=2 > 0）。 */
    private static AIChatManager.SessionSummary qualifiedSummary() {
        return new AIChatManager.SessionSummary(SESSION_ID, "village_trade", "medium",
                3, 2, true, 1_000L, 42L, List.of());
    }

    /** 走公开入口 {@code deliverEnd}，并把 {@code Bukkit.isPrimaryThread()} 钉成 true。 */
    private void deliverEndOnPrimaryThread(AIChatManager.SessionSummary summary) {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            feedbackManager.deliverEnd(player, summary);
        }
    }

    /** 单词级纠错（{@code goes} 会被 {@code WordFormResolver} 还原成 {@code go}）。 */
    private static ConversationFeedback wordLevelFeedback() {
        return new ConversationFeedback("Nice work", List.of(
                new ConversationFeedback.Correction("goes", "go", "第三人称单数")), List.of());
    }

    // ------------------------------------------------------------------
    // A. applyToLearningLoop 真的被调用
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 A —— 删掉 applyToLearningLoop 调用必须变红")
    class LearningLoopIsWired {

        @Test
        @DisplayName("达标点评到达后：goes 必须经词形还原记到 go 的错次上（不是 goes 这个孤儿键）")
        void correctionIsCountedAgainstLemma() {
            stubDeliverBasics();
            stubLearningLoopTargets();
            stubOnlinePlayer();
            stubLogger();
            when(configManager.isWordSystemEnabled()).thenReturn(true);
            stubWordBank("go");
            when(dataStore.incrementWrongCount(PLAYER_ID, "go")).thenReturn(1);
            AIChatManager.SessionSummary summary = qualifiedSummary();
            stubSummaryCallback(summary, wordLevelFeedback());

            deliverEndOnPrimaryThread(summary);

            verify(dataStore, times(1)).incrementWrongCount(PLAYER_ID, "go");
            verify(dataStore, never()).incrementWrongCount(PLAYER_ID, "goes");
            assertEquals(List.of("go"), feedbackManager.getSuggestedFocusWords(PLAYER_ID),
                    "目标词快照没有写入：applyToLearningLoop 未被调用或提前 return");
        }

        @Test
        @DisplayName("错次到达第 3 次时：必须写错题本 addDifficultWord 并给玩家发 wrong-book.auto-add")
        void thirdWrongCountAddsToWrongBook() {
            stubDeliverBasics();
            stubLearningLoopTargets();
            stubOnlinePlayer();
            stubLogger();
            when(configManager.isWordSystemEnabled()).thenReturn(true);
            stubWordBank("go");
            when(dataStore.incrementWrongCount(PLAYER_ID, "go")).thenReturn(3);
            AIChatManager.SessionSummary summary = qualifiedSummary();
            stubSummaryCallback(summary, wordLevelFeedback());

            deliverEndOnPrimaryThread(summary);

            verify(dataStore, times(1)).addDifficultWord(PLAYER_ID, "go");
            verify(messageUtil, times(1)).getPlayerMessage(player, "wrong-book.auto-add");
        }
    }

    // ------------------------------------------------------------------
    // B. scheduleReview(..., true) 真分流
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 B —— 把 scheduleReview 的 true 改成 false 必须变红")
    class ReviewSchedulingDiverges {

        @Test
        @DisplayName("真答错 = seedReview + resetReview 两步；推荐词 = 只 seedReview、绝不 resetReview")
        void wrongWordResetsButRecommendedWordDoesNot() {
            stubDeliverBasics();
            stubLearningLoopTargets();
            stubOnlinePlayer();
            stubLogger();
            when(configManager.isWordSystemEnabled()).thenReturn(true);
            stubWordBank("go", "pear");
            when(dataStore.incrementWrongCount(PLAYER_ID, "go")).thenReturn(1);

            ConversationFeedback feedback = new ConversationFeedback("Good try", List.of(
                    new ConversationFeedback.Correction("goes", "go", "三单")),
                    List.of("pear"));
            AIChatManager.SessionSummary summary = qualifiedSummary();
            stubSummaryCallback(summary, feedback);

            deliverEndOnPrimaryThread(summary);

            // 真答错：两步都要（缺 resetReview 会让「答错 = 遗忘」语义落空）
            verify(reviewScheduler, times(1)).seedReview(PLAYER_ID, "go");
            verify(reviewScheduler, times(1)).resetReview(PLAYER_ID, "go");
            // 推荐词：只保证在复习池，绝不把已有复习间隔打回 1h
            verify(reviewScheduler, times(1)).seedReview(PLAYER_ID, "pear");
            verify(reviewScheduler, never()).resetReview(PLAYER_ID, "pear");
            // 推荐词不是错词：不得计入错次
            verify(dataStore, never()).incrementWrongCount(PLAYER_ID, "pear");
            // 下一场目标词 = 纠错词 + 推荐词（保序）
            assertEquals(List.of("go", "pear"), feedbackManager.getSuggestedFocusWords(PLAYER_ID));
        }
    }

    // ------------------------------------------------------------------
    // C / D. 两个 early return 门
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 C/D —— 单词系统关闭 / 玩家离线时必须零写入")
    class EarlyReturnGates {

        @Test
        @DisplayName("isWordSystemEnabled()==false：点评照常显示，但错题本与复习队列零写入")
        void wordSystemDisabledWritesNothing() {
            stubDeliverBasics();
            stubLearningLoopFacades();
            stubOnlinePlayer();
            when(configManager.isWordSystemEnabled()).thenReturn(false);
            AIChatManager.SessionSummary summary = qualifiedSummary();
            stubSummaryCallback(summary, wordLevelFeedback());

            deliverEndOnPrimaryThread(summary);

            // 正控：证明真的走进了 applyToLearningLoop（它先取门面、再查开关），
            // 否则下面的「零写入」可能只是「整段闭环根本没被调用」这一更严重缺陷的假象。
            verify(plugin, times(1)).getPlayerDataStore();
            verify(messageUtil, times(1)).getPlayerMessage(player, "dialogue.feedback.ai-header");
            verifyNoInteractions(dataStore, reviewScheduler);
            assertTrue(feedbackManager.getSuggestedFocusWords(PLAYER_ID).isEmpty(),
                    "单词系统关闭时仍写入了目标词快照");
        }

        @Test
        @DisplayName("player.isOnline()==false：迟到回调必须整段跳过（防重建只增不删的缓存条目）")
        void offlinePlayerWritesNothing() {
            stubDeliverBasics();
            when(player.getUniqueId()).thenReturn(PLAYER_ID);
            // lenient + 显式 false：这条是「离线态」这个<b>前置条件</b>，不是被测对象。
            // mock 默认也是 false，显式写出来是为了让「这道门被删」的变异真能被执行到；
            // lenient 是为了让「整段 applyToLearningLoop 调用被删」（本组的另一条变异）
            // 由组 A 正控报红，而不是被这里「stub 变死代码」的噪音抢先归因。
            lenient().when(player.isOnline()).thenReturn(false);
            AIChatManager.SessionSummary summary = qualifiedSummary();
            stubSummaryCallback(summary, wordLevelFeedback());

            deliverEndOnPrimaryThread(summary);

            // 离线门在取 PlayerDataStore / WordManager 之前：连门面都不该被问
            verify(plugin, never()).getPlayerDataStore();
            verify(plugin, never()).getWordManager();
            assertTrue(feedbackManager.getSuggestedFocusWords(PLAYER_ID).isEmpty(),
                    "玩家已离线仍写入了目标词快照");
        }
    }

    // ------------------------------------------------------------------
    // E. persistOnQuit
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 E —— quit 路径只落本地行（不需要 mockStatic Bukkit）")
    class QuitPersistence {

        @Test
        @DisplayName("达标会话 quit：异步 UPSERT 一行 source=local、payload=null")
        void quitPersistsLocalRow() {
            when(plugin.getConfigManager()).thenReturn(configManager);
            when(configManager.getMainConfig()).thenReturn(mainConfig);
            when(plugin.getDatabaseManager()).thenReturn(databaseManager);
            when(player.getUniqueId()).thenReturn(PLAYER_ID);
            // R11：本组不铺 stubDeliverBasics，必须自己给 upsertChatFeedback 一个 true ——
            // 否则 mock 默认 false 会让这条「成功落库」用例静默走进失败补偿分支，
            // 用例仍然绿（没人断言登记表），但它测的已经不是成功路径了。
            when(databaseManager.upsertChatFeedback(
                            any(), anyString(), anyString(), anyString(),
                            anyInt(), anyInt(), anyInt(), anyString(), any()))
                    .thenReturn(true);

            feedbackManager.persistOnQuit(player, qualifiedSummary());

            // 落库在 CompletableFuture 上：用 timeout 等，不 sleep。
            // L1 起不再 stub plugin.isEnabled()：writeTask 的守卫已换成 accepting/draining
            // 双闸（isEnabled 在 onDisable 之前就是 false，正是 L1 的本体缺陷）。
            verify(databaseManager, timeout(2000)).upsertChatFeedback(
                    eq(PLAYER_ID), eq(SESSION_ID), eq("village_trade"), eq("medium"),
                    eq(3), eq(2), eq(42), eq("local"), isNull());
        }

        @Test
        @DisplayName("未达标会话 quit：qualified 门先于一切 IO，零落库")
        void unqualifiedQuitWritesNothing() {
            AIChatManager.SessionSummary unqualified = new AIChatManager.SessionSummary(
                    SESSION_ID, "village_trade", "medium", 1, 0, false, 0L, 5L, List.of());

            feedbackManager.persistOnQuit(player, unqualified);

            // 未达标时连配置都不读：getConfigManager 一次都不该被调用
            verify(plugin, never()).getConfigManager();
            verify(databaseManager, never()).upsertChatFeedback(
                    any(), anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), anyInt(), anyString(), any());
        }
    }

    // ------------------------------------------------------------------
    // B'. 目标词快照的清空语义（L5）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 B' —— L5：空场次必须清掉上一场的目标词")
    class FocusWordsAreCleared {

        /**
         * 两场连打的回调装配：同一次 stub 按<b>调用顺序</b>依次回放给定 feedback。
         *
         * <p>为什么不调两次 {@code stubSummaryCallback}：对「同方法 + 同 matcher」重复 stub
         * 在 STRICT_STUBS 下会让前一次变成「从未被消费」，掩盖真正的死代码；而且两场的
         * {@code SessionSummary} 不同，{@code eq(summary)} 匹配本就需要两次 stub —— 队列写法
         * 一次覆盖 N 场，并把「回调次数超出预置」变成显式断言。
         */
        private void stubSummarySequence(ConversationFeedback... feedbacks) {
            Deque<ConversationFeedback> queue = new ArrayDeque<>(Arrays.asList(feedbacks));
            when(aiChatManager.canRequestConversationSummary(player)).thenReturn(true);
            when(aiChatManager.getScenarioDisplayName(player, "village_trade"))
                    .thenReturn("Village Trade");
            // 同 {@link #stubSummaryCallback}：本组两场都是「刚结束的那一场点评到达」，
            // 前置条件 = 源会话就是最近会话，否则判据会把点评全推入静默分支（notify=false），
            // 而本组测的是目标词快照的清空/覆盖语义。
            when(aiChatManager.isLatestSession(any(), anyString())).thenReturn(true);
            doAnswer(invocation -> {
                BiConsumer<ConversationFeedback, String> callback = invocation.getArgument(2);
                ConversationFeedback next = queue.poll();
                assertTrue(next != null, "AI 点评回放次数超过预置的 feedback 队列长度");
                callback.accept(next, null);
                return null;
            }).when(aiChatManager).requestConversationSummary(eq(player), any(), any());
        }

        /** 第二场快照（sessionId 与第一场不同，避免与去重/落库语义纠缠）。 */
        private AIChatManager.SessionSummary secondSummary() {
            return new AIChatManager.SessionSummary(
                    "sess0002", "village_trade", "medium", 3, 2, true, 2_000L, 30L, List.of());
        }

        /**
         * L5 回归：某场「没有任何纠错词与推荐词」时，上一场的目标词必须被清空。
         *
         * <p>原实现是 {@code if (!focusWords.isEmpty()) put(...)} —— 空场次什么都不做，
         * 于是 {@code suggestedFocusWords} 里残留着<b>更早</b>一场的值，被下一场 prompt
         * 当成本场目标词注入。玩家看到的 TARGET WORDS 与刚结束的那场毫无关系，
         * 「重点练什么」这条信号彻底失真。
         *
         * <p>为什么必须走两次 {@code deliverEnd}（而不是直接反射写 map）：L5 的缺陷在
         * 「写入点少了 else 分支」，只有完整跑一遍 applyToLearningLoop 才能覆盖真实路径；
         * 直接改 map 只测到测试自己。
         */
        @Test
        @DisplayName("第一场有 focus words、第二场为空 → 旧值必须被清空")
        void emptySessionClearsPreviousFocusWords() {
            stubDeliverBasics();
            stubLearningLoopTargets();
            stubOnlinePlayer();
            stubLogger();
            when(configManager.isWordSystemEnabled()).thenReturn(true);
            stubWordBank("go", "pear");
            when(dataStore.incrementWrongCount(PLAYER_ID, "go")).thenReturn(1);
            stubSummarySequence(
                    new ConversationFeedback("Good", List.of(
                            new ConversationFeedback.Correction("goes", "go", "三单")), List.of("pear")),
                    new ConversationFeedback("Nice", List.of(), List.of()));

            // 第一场：一条词级纠错 + 一个推荐词 → 快照 [go, pear]
            deliverEndOnPrimaryThread(qualifiedSummary());
            assertEquals(List.of("go", "pear"), feedbackManager.getSuggestedFocusWords(PLAYER_ID),
                    "前置条件不成立：第一场没有写入目标词，本用例测不到清空语义");

            // 第二场：既无纠错、也无推荐词 → 快照必须变成空表
            deliverEndOnPrimaryThread(secondSummary());

            assertTrue(feedbackManager.getSuggestedFocusWords(PLAYER_ID).isEmpty(),
                    "空场次没有清掉上一场的目标词 —— 下一场 prompt 会注入两场前的词（L5 回归）");
        }

        /**
         * 反向护栏：清空只影响「本场没有产出」这一情形，有产出的场次照常覆盖。
         *
         * <p>没有这条，把实现改成「无条件 remove」也能让上一条变绿 —— 那会让目标词注入
         * 整个失效（每次都只剩空表）。
         */
        @Test
        @DisplayName("反向：有产出的第二场必须覆盖（不是无条件清空）")
        void nonEmptySessionOverwritesPreviousFocusWords() {
            stubDeliverBasics();
            stubLearningLoopTargets();
            stubOnlinePlayer();
            stubLogger();
            when(configManager.isWordSystemEnabled()).thenReturn(true);
            stubWordBank("trade", "emerald");
            stubSummarySequence(
                    new ConversationFeedback("Good", List.of(), List.of("trade")),
                    new ConversationFeedback("Nice", List.of(), List.of("emerald")));

            deliverEndOnPrimaryThread(qualifiedSummary());
            assertEquals(List.of("trade"), feedbackManager.getSuggestedFocusWords(PLAYER_ID));

            deliverEndOnPrimaryThread(secondSummary());

            assertEquals(List.of("emerald"), feedbackManager.getSuggestedFocusWords(PLAYER_ID),
                    "第二场的目标词没有覆盖第一场 —— 玩家会被引导去练旧的词");
        }
    }

    // ------------------------------------------------------------------
    // F. 真 AIChatManager：目标词真的进了 system prompt
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 F —— ChatFeedbackManager 的目标词快照能被真 AIChatManager 消费")
    class FocusWordsReachSystemPrompt {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("setFocusWordProvider 注入的词必须出现在写入 DB 的 system prompt 的 TARGET WORDS 段")
        void systemPromptContainsInjectedFocusWords() {
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getConfigManager()).thenReturn(configManager);
            when(configManager.getMainConfig()).thenReturn(mainConfig);
            when(configManager.isAIChatEnabled()).thenReturn(true);
            when(plugin.getPlayerLanguage(player)).thenReturn("en");
            when(player.getUniqueId()).thenReturn(PLAYER_ID);
            when(plugin.getDatabaseManager()).thenReturn(databaseManager);
            // L1 起 writeTask 不再看 plugin.isEnabled()，但 AIChatManager 的
            // isShuttingDownOrDisabled() 仍然看 —— 不铺 true 会在写 system prompt 前短路。
            when(plugin.isEnabled()).thenReturn(true);

            AIChatManager ai = new AIChatManager(plugin);
            ai.setFocusWordProvider(id -> List.of("apple", "trade"));
            ai.startConversation(player, "village_trade");

            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(databaseManager, timeout(2000))
                    .insertChatHistory(eq(PLAYER_ID), anyString(), eq("system"), prompt.capture());

            assertTrue(prompt.getValue().contains("=== TARGET WORDS ==="),
                    "system prompt 缺少目标词段：注入通道断了（玩家下一场练不到 AI 推荐词）");
            assertTrue(prompt.getValue().contains("apple, trade"),
                    "目标词未按注入顺序出现在 prompt 里");
            assertTrue(prompt.getValue().startsWith(PromptComposer.SCENARIO_PREFIX),
                    "场景前缀被破坏：ChatHistoryEntry.extractScenarioFromSystemPrompt 会解析失败");
        }
    }

    // ------------------------------------------------------------------
    // G. 防串台判据必须接在 isLatestSession 上（R4-05-logic-F-L2）
    // ------------------------------------------------------------------

    /**
     * 组 G —— 「A 结束 → 开 B → B 也结束 → A 的点评才到达」这条链的唯一回归。
     *
     * <p><b>为什么必须有这一组</b>：上面的 A/B/B'/C/D/F 全部把
     * {@code isLatestSession(...)} stub 成 {@code true}（前置条件：点评属于最近一场），
     * 于是它们<b>无法区分</b>「判据接在 isLatestSession 上」与「判据还是旧的
     * hasActiveConversation + getActiveSessionId 组合」—— 这正是本条缺陷能逃过 295+ 用例
     * 的原因。本组把判据钉死成 {@code false}（= 源会话已不是最近会话），断言防串台分支
     * 真的生效：不渲染 ai-header、不回放 ai-comment，纠错仍以 {@code notify=false} 静默落地。
     *
     * <p><b>旧判据为什么在这里是空的</b>：B 也已经 {@code endConversation} 了 →
     * {@code hasActiveConversation == false} → 旧 if 整体为假 → 走即时显示分支 →
     * 玩家在 B 的 hint 之后看到 A 的 ai-header + ai-comment，会理解成「这是 B 的点评」；
     * 附带 A 的纠错词会弹「已加入错题本」，并写进 suggestedFocusWords 污染下一场 TARGET WORDS。
     */
    @Nested
    @DisplayName("组 G —— 迟到点评（源会话已非最近会话）必须静默落地、绝不渲染")
    class LateSummaryFromEndedSessionDoesNotLeak {

        /** 第三场（B 之后的会话 C）—— 与旧会话 id 明确不同，避免与去重语义纠缠。 */
        private AIChatManager.SessionSummary laterSummary() {
            return new AIChatManager.SessionSummary(
                    "sess0003", "village_trade", "medium", 3, 2, true, 3_000L, 20L, List.of());
        }

        /**
         * 正向回归：判据为 false（= 源会话已不是最近会话）时，点评必须完全静默。
         *
         * <p>时间线：A 结束（sessionId=sess0001，HTTP 在途）→ 玩家开 B →
         * B 也结束 → B 的点评先回来 → A 的那一单此时才到达。
         * 那一刻 {@code hasActiveConversation(B)} 已是 false（B 也从 sessions 摘除了），
         * 旧判据整体为假，A 的点评会被当作即时点评渲染 —— 就是本用例要钉住的那个 bug。
         */
        @Test
        @DisplayName("A 结束 → 开 B → B 结束 → A 回调到达：不得渲染 A 的点评，纠错静默落地")
        void lateSummaryIsNotRendered() {
            stubDeliverBasics();
            stubLearningLoopTargets();
            stubOnlinePlayer();
            stubLogger();
            when(configManager.isWordSystemEnabled()).thenReturn(true);
            stubWordBank("go");
            when(dataStore.incrementWrongCount(PLAYER_ID, "go")).thenReturn(1);

            // 源会话已非最近会话 —— 经 helper 的 latest 参数下发（不能在此单独 when()：
            // helper 内部对同 matcher 的 stub 会覆盖它）。
            // 旧判据在这条时间线上的实际取值也一并钉死，让「判据换回旧判据」这个变异
            // 真能走进即时显示分支（否则 mock 默认值恰好等价，变异不被区分）。
            // lenient：新判据下这两条<b>不该被消费</b>；它们存在的唯一目的，是让
            // 「把判据改回 hasActiveConversation + getActiveSessionId」这个变异真的走进
            // 即时显示分支（从而被下面的 never() 抓住）。
            lenient().when(aiChatManager.hasActiveConversation(player)).thenReturn(false);
            lenient().when(aiChatManager.getActiveSessionId(PLAYER_ID)).thenReturn("sess0003");

            AIChatManager.SessionSummary staleSummary = qualifiedSummary();
            stubSummaryCallback(staleSummary, wordLevelFeedback(), false);

            deliverEndOnPrimaryThread(staleSummary);

            // ① 展示层：A 的点评一个字都不能出现（玩家此刻看到的是 B 的收尾）。
            verify(messageUtil, never()).getPlayerMessage(player, "dialogue.feedback.ai-header");
            verify(messageUtil, never()).getPlayerMessage(player, "dialogue.feedback.ai-comment",
                    "comment", "Nice work");
            // ② 闭环信号层：纠错仍要落地，但绝不弹「已加入错题本」（玩家已在新对话里）。
            verify(dataStore, times(1)).incrementWrongCount(PLAYER_ID, "go");
            assertEquals(List.of("go"), feedbackManager.getSuggestedFocusWords(PLAYER_ID),
                    "静默分支必须照常写目标词快照：AI 花钱买来的纠错不能因为防串台就丢掉");
            verify(messageUtil, never()).getPlayerMessage(player, "wrong-book.auto-add");
            // ③ 数据层：仍要落库（source=ai 覆写本地行），只是不渲染。
            verify(databaseManager, timeout(2000)).upsertChatFeedback(
                    eq(PLAYER_ID), eq(SESSION_ID), eq("village_trade"), eq("medium"),
                    eq(3), eq(2), eq(42), eq("ai"), anyString());

            // 对照组用不到这条快照，但显式构造一次以证明 id 不同（防后来者误合并用例）。
            assertTrue(!laterSummary().sessionId().equals(staleSummary.sessionId()),
                    "对照组会话 id 与源会话重复，用例区分度失效");
        }

        /**
         * 边界正向：判据为 true（源会话就是最近一场）时，点评照常渲染。
         *
         * <p>没有这一条，「无条件静默」也能让上面那条变绿 —— 那会让所有点评都不显示，
         * 是比串台更严重的倒退。
         */
        @Test
        @DisplayName("反向护栏：源会话是最近一场时点评必须照常渲染（不是无条件静默）")
        void latestSummaryIsRendered() {
            stubDeliverBasics();
            stubLearningLoopTargets();
            stubOnlinePlayer();
            stubLogger();
            when(configManager.isWordSystemEnabled()).thenReturn(true);
            stubWordBank("go");
            when(dataStore.incrementWrongCount(PLAYER_ID, "go")).thenReturn(1);

            AIChatManager.SessionSummary summary = qualifiedSummary();
            stubSummaryCallback(summary, wordLevelFeedback());

            deliverEndOnPrimaryThread(summary);

            verify(messageUtil, times(1)).getPlayerMessage(player, "dialogue.feedback.ai-header");
            verify(messageUtil, times(1)).getPlayerMessage(player, "dialogue.feedback.ai-comment",
                    "comment", "Nice work");
            verify(dataStore, times(1)).incrementWrongCount(PLAYER_ID, "go");
        }
    }
}
