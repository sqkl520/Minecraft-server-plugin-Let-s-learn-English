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
     * <p>刻意<b>不</b>在这里 stub {@code plugin.getDatabaseManager()} 或
     * {@code plugin.isEnabled()}：主线程路径下本地行落库走 {@code CompletableFuture}，
     * 未 stub 的 {@code isEnabled()} 默认 false 会在写任务入口直接短路 —— 于是展示组
     * 不需要 DB，也不会留下「没人消费」的 stub。
     */
    private void stubDeliverBasics() {
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("msg");
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
        when(aiChatManager.canRequestConversationSummary(player)).thenReturn(true);
        when(aiChatManager.getScenarioDisplayName(player, summary.scenarioKey()))
                .thenReturn("Village Trade");
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
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getDatabaseManager()).thenReturn(databaseManager);
            when(player.getUniqueId()).thenReturn(PLAYER_ID);

            feedbackManager.persistOnQuit(player, qualifiedSummary());

            // 落库在 CompletableFuture 上：用 timeout 等，不 sleep
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
}
