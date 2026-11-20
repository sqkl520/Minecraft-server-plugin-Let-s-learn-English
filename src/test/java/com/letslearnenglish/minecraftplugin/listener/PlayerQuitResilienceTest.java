package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.core.ai.ChatFeedbackManager;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import com.letslearnenglish.minecraftplugin.core.manager.CheckInManager;
import com.letslearnenglish.minecraftplugin.core.onboarding.OnboardingManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.core.word.ReviewScheduler;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.ConfirmationGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 17-EH-Q1：{@code PlayerJoinListener.onPlayerQuit} 的 17 步下线编排必须<b>逐步隔离</b>
 *
 * <p>（D2 后由 16 步增至 17 步：新增 {@code duel.unloadPlayer} —— 决斗邀请冷却表
 * {@code DuelManager.lastInviteAt} 的 per-player 清理点。）
 *
 * <h2>被修的缺陷</h2>
 * <p>原实现是 16 处裸 {@code plugin.get...()} 顺序调用，<b>零</b> try/catch。第 ① 步
 * {@code endQuiz} 就会抛：{@code endQuiz → WordManagerImpl.endSession:170 →
 * PlayerDataStore.updateWordProgress / getLeaderboard().updateScore}，而这条链上的
 * {@code DatabaseManager.getConnection()} 在 DB 重连失败时抛的是
 * {@code RuntimeException("Failed to get database connection")} 而<b>不是</b>
 * {@code SQLException}（数据层 {@code catch (SQLException)} 接不到它，
 * 见 {@code PlayerDataStore:2362-2367} 的专门记录）。
 *
 * <p>后果不是「一条堆栈」而是<b>数据丢失</b>：第 ③ 步 {@code unloadPlayerData}
 * 是全量落盘（掌握度 / 学习分 / 复习种子 / 错题本刷盘），它永不执行 ⇒ 玩家
 * 「答了 10 题，重进服分数回退」；其后 14 步内存卫生也全部跳过（各自 map 只增不删）。
 *
 * <h2>本类断言的核心命题</h2>
 * <p><b>不是</b>「不抛异常」（那是把缺陷藏起来），而是「<b>某一步抛异常时，后续每一步
 * 仍被调用</b>」。所以每条用例都是「让某一步炸 → 断言下游仍执行」，
 * 而不是 {@code assertDoesNotThrow} 了事。
 *
 * <h2>两条变异都能被杀死</h2>
 * <ul>
 *   <li><b>删掉 try/catch（改回原样）</b>：{@link #stepOneFailureStillRunsTheWholeChain()}
 *       里 {@code endQuiz} 抛出的 RuntimeException 会从 {@code onPlayerQuit} 逃逸，
 *       第三行 {@code verify(playerDataStore).unloadPlayerData(...)} 直接红；</li>
 *   <li><b>把「每步独立」改成一个 try 包住全部</b>：同样一条用例会红 —— 异常被吞了，
 *       但 {@code unloadPlayerData} 依然没被调用。这正是本类把「独立」和「不抛」
 *       两种半修同时排除的原因。</li>
 * </ul>
 */
@DisplayName("17-EH-Q1：onPlayerQuit 17 步编排逐步隔离")
class PlayerQuitResilienceTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000a171");

    /** 每次抛出的都是同一个「数据层重连失败」形状的 RuntimeException。 */
    private static RuntimeException dbDown() {
        return new RuntimeException("Failed to get database connection");
    }

    private Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new Fixture();
    }

    @AfterEach
    void tearDown() {
        if (fx != null) {
            fx.close();
        }
    }

    // ==================================================================
    // 行为 1（任务指定的核心断言）：第 ① 步抛 → 后续全部仍被调用
    // ==================================================================

    /**
     * {@code endQuiz} 抛 RuntimeException（= DB 重连失败的真实形状）时：
     * <ul>
     *   <li>第 ③ 步 {@code unloadPlayerData}（<b>全量落盘</b>）必须被调用；</li>
     *   <li>其后 14 步也必须被调用；</li>
     *   <li>{@code onPlayerQuit} 本身不得把异常抛给 Bukkit 事件总线。</li>
     * </ul>
     */
    @Test
    @DisplayName("endQuiz 抛异常 → unloadPlayerData（全量落盘）仍执行，后续 14 步不被截断")
    void stepOneFailureStillRunsTheWholeChain() {
        doThrow(dbDown()).when(fx.wordLearning).endQuiz(ID);

        assertDoesNotThrow(() -> fx.listener.onPlayerQuit(fx.event),
                "第一步清理失败不得从 quit 事件处理器逃逸喵 —— Bukkit 的事件总线只会打一条"
                        + "与真实原因无关的堆栈，且后续 14 步内存卫生全被跳过。");

        // ←←← 本用例存在的理由：这一行在原实现下永不成立（全量落盘被跳过）
        verify(fx.playerDataStore).unloadPlayerData(ID);
        verify(fx.playerDataStore).unloadProfileSettings(ID);
        verify(fx.achievements).unloadPlayer(ID);
        verify(fx.challenges).unloadPlayer(ID);
        verify(fx.guiNavigation).clearPlayer(ID);
        verify(fx.checkIn).unloadPlayer(ID);
        verify(fx.rewardEngine).unloadPlayer(ID);
        verify(fx.onboarding).unloadPlayer(ID);
        verify(fx.social).unloadPlayer(ID);
        verify(fx.aiChat).clearPendingSummary(ID);
        verify(fx.aiChat).unloadPlayer(ID);
        verify(fx.reviewScheduler).unloadPlayer(ID);
        verify(fx.chatFeedback).unloadPlayer(ID);
        // D2：决斗邀请冷却表（DuelManager.lastInviteAt）的 quit 清理点。
        verify(fx.duel).unloadPlayer(ID);
        fx.confirm.verify(() -> ConfirmationGUI.resetStage(fx.player));

        // 失败步骤自身也留下了可定位的 WARNING（步骤名 + UUID）
        assertTrue(fx.logs.contains("wordLearning.endQuiz"),
                "失败步骤必须留一条带<b>步骤名</b>的 WARNING 喵 —— 没有步骤名时运维无法"
                        + "区分「落盘没写（数据丢失）」与「签到锁没清（纯内存卫生）」。实测日志："
                        + fx.logs.messages);
        assertTrue(fx.logs.contains(ID.toString()),
                "WARNING 必须带玩家 UUID 喵。实测日志：" + fx.logs.messages);
    }

    /**
     * ①② 双双抛（一次 DB 抖动通常连累相邻两步）时，第 ③ 步全量落盘<b>仍然</b>会执行。
     *
     * <p>这一条钉的是「放置顺序」这一决策：把 {@code unloadPlayerData} 留在第 3 位
     * （而不是挪到最后统一收尾）才有这个性质。若有人重排成「先做完全部内存清理、
     * 最后统一落盘」，本用例不会红 —— 但 {@link #everyStepIsIndependentlyGuarded()}
     * 会红吗？也不会。所以本用例与下面那条「顺序 lint」分工：
     * 行为钉「落盘不必等到最后」，lint 钉「落盘仍在 profileSettings 之前」。
     */
    @Test
    @DisplayName("①② 双双失败 → 全量落盘仍被尝试（位置在第 3 位，不等最后）")
    void fullSaveIsAttemptedWhenBothWordStepsFail() {
        doThrow(dbDown()).when(fx.wordLearning).endQuiz(ID);
        doThrow(dbDown()).when(fx.wordLearning).cancelPractice(ID);

        fx.listener.onPlayerQuit(fx.event);

        verify(fx.playerDataStore).unloadPlayerData(ID);
        verify(fx.chatFeedback).unloadPlayer(ID);
        assertEquals(2, fx.logs.countWarnings(), "两步各留一条 WARNING 喵（不许合并成一条"
                + "让人找不到是哪两步）。实测日志：" + fx.logs.messages);
    }

    // ==================================================================
    // 行为 2：逐步骤穷举 —— 任何一步炸都不影响其它 16 步
    // ==================================================================

    /**
     * 对 17 个步骤<b>逐个</b>注入失败，每次都断言「其余 16 步 + 失败步自身都仍被调用」。
     *
     * <p>为什么不能只测第 ① 步：一个「只在 ①② 外面包 try」的半修能过上面两条用例，
     * 却在 {@code unloadPlayerData} 自己抛的时候让后面 14 步全跳过。穷举是唯一
     * 能证明「<b>每个</b>边界都在」的方式。
     *
     * <p>每次迭代都换一套全新的 mock（{@link Fixture}）：{@link org.mockito.Mockito}
     * 的调用记录会跨用例累积，复用同一套 mock 会让「被调用」的断言被上一轮的记录喂饱。
     */
    @Test
    @DisplayName("逐步骤注入失败：17 步中的任何一步炸，其余 16 步仍全部执行")
    void everyStepIsIndependentlyGuarded() throws Throwable {
        // 先确认步骤数（步骤清单由同一个工厂方法产出，避免与本类的断言两处对不上）。
        // 用完立刻关掉本夹具：Mockito 的 static mock 同一线程内不可叠加注册，
        // 循环里新建 Fixture 之前必须先把 @BeforeEach 那个注销掉。
        List<Step> names = fx.steps();
        assertEquals(17, names.size(), "下线编排的步骤数变了喵 —— 请先确认现状，再同步本用例"
                + "（步骤清单：" + stepNames(names) + "）");
        fx.close();

        for (int i = 0; i < names.size(); i++) {
            // 每个步骤一套全新夹具：Mockito 的调用记录会跨用例累积，复用会让
            // 「被调用」的断言被上一轮喂饱（假绿形态之「计数被别路径满足」）。
            // 静态 mock 同一线程内不可叠加注册 —— 所以每次只在 try 作用域内活一个，
            // finally 里关掉再进下一轮。
            Fixture f = new Fixture();
            try {
                List<Step> steps = f.steps();
                String failing = steps.get(i).name;
                steps.get(i).arm.run(); // 让第 i 步抛「DB 重连失败」

                assertDoesNotThrow(() -> f.listener.onPlayerQuit(f.event),
                        "第 " + (i + 1) + " 步（" + failing + "）失败时异常逃逸出了 onPlayerQuit 喵 ——"
                                + "Bukkit 事件总线只会打一条无归因的堆栈，后续步骤全跳过。");

                for (Step s : steps) {
                    s.assertCalled.run(); // 失败步自身 + 其余 16 步都必须被调用过
                }
                assertTrue(f.logs.contains(failing),
                        "第 " + (i + 1) + " 步失败后必须留下带步骤名 '" + failing + "' 的 WARNING 喵。"
                                + "实测日志：" + f.logs.messages);
            } finally {
                f.close();
            }
        }
    }

    // ==================================================================
    // 行为 3：顺序契约
    // ==================================================================

    /**
     * 现有顺序有语义，不得被「重构顺手排个序」打乱：
     * <ul>
     *   <li>①② 结算 → ③ 全量落盘：落盘必须<b>在结算之后</b>，否则刚结算出的掌握度/学习分
     *       落不进库；</li>
     *   <li>③ {@code unloadPlayerData} 在 ⑥ {@code unloadProfileSettings} 之前：前者清
     *       玩家数据缓存、后者清 profile 缓存，两者都属「退出建条目、下线删条目」的
     *       生命周期对（join 侧按同一顺序建），顺序颠倒虽不致命但会让 join→quit 竞态
     *       判据（{@code getCachedProfileSettings == null}）的时序含义变味。</li>
     * </ul>
     */
    @Test
    @DisplayName("顺序契约：endQuiz → cancelPractice → unloadPlayerData → … → unloadProfileSettings")
    void quitChainOrderIsPreserved() {
        fx.listener.onPlayerQuit(fx.event);

        InOrder order = inOrder(fx.wordLearning, fx.playerDataStore, fx.achievements,
                fx.challenges, fx.guiNavigation, fx.checkIn, fx.rewardEngine, fx.onboarding,
                fx.social, fx.aiChat, fx.reviewScheduler, fx.chatFeedback, fx.duel);

        order.verify(fx.wordLearning).endQuiz(ID);
        order.verify(fx.wordLearning).cancelPractice(ID);
        order.verify(fx.playerDataStore).unloadPlayerData(ID); // ③ 必须在 ①② 之后
        order.verify(fx.achievements).unloadPlayer(ID);
        order.verify(fx.challenges).unloadPlayer(ID);
        order.verify(fx.playerDataStore).unloadProfileSettings(ID); // ⑥ 在 ③ 之后
        order.verify(fx.guiNavigation).clearPlayer(ID);
        order.verify(fx.checkIn).unloadPlayer(ID);
        order.verify(fx.rewardEngine).unloadPlayer(ID);
        order.verify(fx.onboarding).unloadPlayer(ID);
        order.verify(fx.social).unloadPlayer(ID);
        order.verify(fx.aiChat).clearPendingSummary(ID);
        order.verify(fx.aiChat).unloadPlayer(ID);
        order.verify(fx.reviewScheduler).unloadPlayer(ID);
        order.verify(fx.chatFeedback).unloadPlayer(ID);
        order.verify(fx.duel).unloadPlayer(ID); // D2：冷却表清理排在 resetStage 之前
        fx.confirm.verify(() -> ConfirmationGUI.resetStage(fx.player));
    }

    // ==================================================================
    // 行为 4：不吞 Error
    // ==================================================================

    /**
     * {@code Error}（{@code OutOfMemoryError} / {@code StackOverflowError} …）代表 JVM
     * 已不可信，必须<b>照原样向上冒</b>：吞掉它只会把「崩溃」伪装成「安静的数据丢失」。
     *
     * <p>这条同时是一个选择证明：若有人把 {@code catch (RuntimeException e)} 扩成
     * {@code catch (Throwable t)}（看起来更「稳」），本用例立刻红。
     */
    @Test
    @DisplayName("Error 不被吞：OutOfMemoryError 照原样逃出（只 catch RuntimeException）")
    void errorIsNotSwallowed() {
        doThrow(new OutOfMemoryError("heap exhausted")).when(fx.wordLearning).endQuiz(ID);

        assertThrows(OutOfMemoryError.class, () -> fx.listener.onPlayerQuit(fx.event),
                "Error 被吞了喵 —— JVM 已不可信时必须让崩溃可见，而不是伪装成安静的数据丢失。");
    }

    // ==================================================================
    // 源码 lint：编排结构（行为测不出「有没有人加了第二个大 try」的半修）
    // ==================================================================

    /**
     * 行为测试的补位：钉「17 个清理点<b>各自</b>走 {@code guardQuitStep(}」这个结构。
     *
     * <p>为什么必须补：如果有人在整段外面再包一个大 try（「双保险」），行为用例全绿
     * —— 因为每步本来就已有独立边界。可一旦有人<b>把逐步边界删掉只留大 try</b>，
     * 行为用例会红（好）；但若删完再加个 {@code catch (Throwable)} 就又会绿回去，
     * 而结构 lint 要求「17 次 guardQuitStep 调用」始终在，删不掉。
     *
     * <p>剥注释后再计数：方法上方的缺陷说明里就写着 {@code guardQuitStep} 与
     * {@code try}，不剥会把注释喂给断言（本仓多次踩过：lint 的锚点被注释满足）。
     */
    @Test
    @DisplayName("lint：onPlayerQuit 含 17 次 guardQuitStep 调用、0 个 try，且落盘早于 profile")
    void everyCleanupStepGoesThroughTheGuard() throws Exception {
        String body = Source.methodBody("public void onPlayerQuit(");

        int guardCalls = Source.countOccurrences(body, "guardQuitStep(");
        assertEquals(17, guardCalls,
                "onPlayerQuit 里的 guardQuitStep( 调用数为 " + guardCalls + " 喵（应为 17）。\n"
                        + "少一个就意味着那一步回到了裸调用：它一抛，后面的步骤全跳过 ——"
                        + " 最坏的情况是全量落盘（unloadPlayerData）被跳过，玩家重进服数据回退。\n"
                        + "方法体：\n" + body);

        assertEquals(0, Source.countOccurrences(body, "try {"),
                "onPlayerQuit 里出现了 try { 喵 —— 大 try 包住整段等于没修：第一步一抛，"
                        + "后面照样全跳过，只是把裸堆栈换成被吞的堆栈。异常边界必须**每步一个**。");

        int saveAt = body.indexOf("playerDataStore.unloadPlayerData");
        int profileAt = body.indexOf("playerDataStore.unloadProfileSettings");
        assertTrue(saveAt > 0 && profileAt > 0,
                "定位失效喵：落盘=" + saveAt + " profile=" + profileAt);
        assertTrue(saveAt < profileAt,
                "unloadPlayerData（全量落盘）必须早于 unloadProfileSettings 喵 ——"
                        + " 现有顺序有生命周期语义（join 侧同序建立），不许在重构里颠倒。");
    }

    /** 兜底：profile 清理不得丢失（它在一次「清缓存清过头」的重构里最容易被顺手删掉）。 */
    @Test
    @DisplayName("lint：confirmationGui.resetStage 仍在编排里（二确静态态必须清）")
    void confirmationStageResetIsStillWired() throws Exception {
        String body = Source.methodBody("public void onPlayerQuit(");
        assertTrue(body.contains("ConfirmationGUI.resetStage("),
                "quit 编排里找不到 ConfirmationGUI.resetStage( 喵 —— 「确认页 1 → 下线 →"
                        + " 重登」会从阶段 1 残留状态继续，跳过二段确认。");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 一个清理步骤：步骤名 + 「让它抛」的注入动作 + 「它被调用过」的断言。 */
    private static final class Step {
        final String name;
        final Arm arm;
        final Runnable assertCalled;

        Step(String name, Arm arm, Runnable assertCalled) {
            this.name = name;
            this.arm = arm;
            this.assertCalled = assertCalled;
        }
    }

    @FunctionalInterface
    private interface Arm {
        void run() throws Throwable;
    }

    private static List<String> stepNames(List<Step> steps) {
        List<String> names = new ArrayList<>();
        for (Step s : steps) {
            names.add(s.name);
        }
        return names;
    }

    /**
     * 一次用例的全部假件：mock plugin + 15 个服务 + {@code ConfirmationGUI} 的静态 mock。
     *
     * <p>每次迭代都新建，<b>不复用</b>：Mockito 的调用记录会累积，复用会让
     * 「被调用」的断言被上一轮喂饱（本仓假绿形态之「计数被别路径满足」）。
     */
    private static final class Fixture implements AutoCloseable {

        final Captured logs = new Captured();
        final LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        final PlayerDataStore playerDataStore = mock(PlayerDataStore.class);
        final WordLearningManager wordLearning = mock(WordLearningManager.class);
        final WordManagerImpl wordManager = mock(WordManagerImpl.class);
        final ReviewScheduler reviewScheduler = mock(ReviewScheduler.class);
        final AchievementManager achievements = mock(AchievementManager.class);
        final DailyChallengeManager challenges = mock(DailyChallengeManager.class);
        final GuiNavigationManager guiNavigation = mock(GuiNavigationManager.class);
        final CheckInManager checkIn = mock(CheckInManager.class);
        final ProgressManagerImpl progressManager = mock(ProgressManagerImpl.class);
        final RewardEngine rewardEngine = mock(RewardEngine.class);
        final OnboardingManager onboarding = mock(OnboardingManager.class);
        final SocialManager social = mock(SocialManager.class);
        final AIChatManager aiChat = mock(AIChatManager.class);
        final ChatFeedbackManager chatFeedback = mock(ChatFeedbackManager.class);
        /** D2：决斗邀请冷却表（{@code DuelManager.lastInviteAt}）的 quit 清理点载体。 */
        final DuelManager duel = mock(DuelManager.class);

        final Player player = mock(Player.class);
        final PlayerQuitEvent event;
        final MockedStatic<ConfirmationGUI> confirm;
        final PlayerJoinListener listener;

        Fixture() {
            when(plugin.getLogger()).thenReturn(logs.logger);
            when(plugin.getPlayerDataStore()).thenReturn(playerDataStore);
            when(plugin.getWordLearningManager()).thenReturn(wordLearning);
            when(plugin.getWordManager()).thenReturn(wordManager);
            when(wordManager.getReviewScheduler()).thenReturn(reviewScheduler);
            when(plugin.getAchievementManager()).thenReturn(achievements);
            when(plugin.getDailyChallengeManager()).thenReturn(challenges);
            when(plugin.getGuiNavigationManager()).thenReturn(guiNavigation);
            when(plugin.getCheckInManager()).thenReturn(checkIn);
            when(plugin.getProgressManager()).thenReturn(progressManager);
            when(progressManager.getRewardEngine()).thenReturn(rewardEngine);
            when(plugin.getOnboardingManager()).thenReturn(onboarding);
            when(plugin.getSocialManager()).thenReturn(social);
            when(plugin.getAIChatManager()).thenReturn(aiChat);
            when(plugin.getChatFeedbackManager()).thenReturn(chatFeedback);
            when(plugin.getDuelManager()).thenReturn(duel);

            when(player.getUniqueId()).thenReturn(ID);
            when(player.getName()).thenReturn("Alice");
            event = new PlayerQuitEvent(player, "Alice left the game");

            // 静态态清理：mock 掉以便既能注入失败、又能断言「被调用过」。
            confirm = mockStatic(ConfirmationGUI.class);

            listener = new PlayerJoinListener(plugin);
        }

        /**
         * 全部 17 个步骤，顺序与方法体一致。
         *
         * <p>每次调用都返回<b>新</b>的 Step 列表（arm/verify 的闭包捕获的是本 fixture 的
         * mock，跨 fixture 复用会串味）。
         */
        List<Step> steps() {
            List<Step> s = new ArrayList<>();
            s.add(new Step("wordLearning.endQuiz",
                    () -> doThrow(dbDown()).when(wordLearning).endQuiz(ID),
                    () -> verify(wordLearning).endQuiz(ID)));
            s.add(new Step("wordLearning.cancelPractice",
                    () -> doThrow(dbDown()).when(wordLearning).cancelPractice(ID),
                    () -> verify(wordLearning).cancelPractice(ID)));
            s.add(new Step("playerDataStore.unloadPlayerData",
                    () -> doThrow(dbDown()).when(playerDataStore).unloadPlayerData(ID),
                    () -> verify(playerDataStore).unloadPlayerData(ID)));
            s.add(new Step("achievement.unloadPlayer",
                    () -> doThrow(dbDown()).when(achievements).unloadPlayer(ID),
                    () -> verify(achievements).unloadPlayer(ID)));
            s.add(new Step("dailyChallenge.unloadPlayer",
                    () -> doThrow(dbDown()).when(challenges).unloadPlayer(ID),
                    () -> verify(challenges).unloadPlayer(ID)));
            s.add(new Step("playerDataStore.unloadProfileSettings",
                    () -> doThrow(dbDown()).when(playerDataStore).unloadProfileSettings(ID),
                    () -> verify(playerDataStore).unloadProfileSettings(ID)));
            s.add(new Step("guiNavigation.clearPlayer",
                    () -> doThrow(dbDown()).when(guiNavigation).clearPlayer(ID),
                    () -> verify(guiNavigation).clearPlayer(ID)));
            s.add(new Step("checkIn.unloadPlayer",
                    () -> doThrow(dbDown()).when(checkIn).unloadPlayer(ID),
                    () -> verify(checkIn).unloadPlayer(ID)));
            s.add(new Step("rewardEngine.unloadPlayer",
                    () -> doThrow(dbDown()).when(rewardEngine).unloadPlayer(ID),
                    () -> verify(rewardEngine).unloadPlayer(ID)));
            s.add(new Step("onboarding.unloadPlayer",
                    () -> doThrow(dbDown()).when(onboarding).unloadPlayer(ID),
                    () -> verify(onboarding).unloadPlayer(ID)));
            s.add(new Step("social.unloadPlayer",
                    () -> doThrow(dbDown()).when(social).unloadPlayer(ID),
                    () -> verify(social).unloadPlayer(ID)));
            s.add(new Step("aiChat.clearPendingSummary",
                    () -> doThrow(dbDown()).when(aiChat).clearPendingSummary(ID),
                    () -> verify(aiChat).clearPendingSummary(ID)));
            s.add(new Step("aiChat.unloadPlayer",
                    () -> doThrow(dbDown()).when(aiChat).unloadPlayer(ID),
                    () -> verify(aiChat).unloadPlayer(ID)));
            s.add(new Step("reviewScheduler.unloadPlayer",
                    () -> doThrow(dbDown()).when(reviewScheduler).unloadPlayer(ID),
                    () -> verify(reviewScheduler).unloadPlayer(ID)));
            s.add(new Step("chatFeedback.unloadPlayer",
                    () -> doThrow(dbDown()).when(chatFeedback).unloadPlayer(ID),
                    () -> verify(chatFeedback).unloadPlayer(ID)));
            // D2：决斗邀请冷却表（DuelManager.lastInviteAt）—— 位置紧随 chatFeedback，
            // 排在末尾的 confirmationGui.resetStage 之前（后者是唯一需要活 Player 的一步）。
            s.add(new Step("duel.unloadPlayer",
                    () -> doThrow(dbDown()).when(duel).unloadPlayer(ID),
                    () -> verify(duel).unloadPlayer(ID)));
            s.add(new Step("confirmationGui.resetStage",
                    () -> confirm.when(() -> ConfirmationGUI.resetStage(player))
                            .thenThrow(dbDown()),
                    () -> confirm.verify(() -> ConfirmationGUI.resetStage(player))));
            return s;
        }

        /** 幂等：用例内显式 close 过一次后，@AfterEach / finally 里再 close 不得重复注销。 */
        private boolean closed;

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                confirm.close();
            } catch (RuntimeException ignored) {
                // 用例已失败时关闭静态 mock 可能再抛，不能让它盖掉真实断言
            }
            logs.close();
        }
    }

    /** 日志探针（本仓每个测试类各带一份）。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("PlayerQuitResilienceTest." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    messages.add(record.getMessage() == null ? "" : record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
        }

        boolean contains(String needle) {
            for (String m : messages) {
                if (m.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        int countWarnings() {
            return messages.size();
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    // ==================================================================
    // 源码读取（复用 PlayerJoinListenerReloadWindowTest 的「上溯找 repo 根」做法）
    // ==================================================================

    private static final class Source {

        static String read() throws Exception {
            java.nio.file.Path p = locate();
            return java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        }

        /** 按签名截方法体（先剥注释，免得注释里的 {@code try} / 步骤名把断言喂饱）。 */
        static String methodBody(String signature) throws Exception {
            String stripped = stripComments(read());
            int at = stripped.indexOf(signature);
            assertTrue(at >= 0, "找不到签名 " + signature + " 喵 —— 定位失效必须先红。");
            int depth = 0;
            int start = stripped.indexOf('{', at);
            assertTrue(start > 0, "签名 " + signature + " 后面找不到 { 喵。");
            for (int i = start; i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return stripped.substring(start, i + 1);
                    }
                }
            }
            fail("方法体括号未闭合喵：" + signature);
            return null; // unreachable
        }

        /**
         * 剥掉行注释与块注释，<b>保留字符串字面量</b>（本类断言的都是标识符与 {@code try {}}
         * 结构，不涉及字符串内容）。
         */
        private static String stripComments(String src) {
            StringBuilder out = new StringBuilder(src.length());
            int i = 0;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                    while (i < src.length() && src.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i += 2;
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }

        static int countOccurrences(String haystack, String needle) {
            int count = 0;
            int at = haystack.indexOf(needle);
            while (at >= 0) {
                count++;
                at = haystack.indexOf(needle, at + needle.length());
            }
            return count;
        }

        private static java.nio.file.Path locate() {
            String rel = "src/main/java/com/letslearnenglish/minecraftplugin/listener/PlayerJoinListener.java";
            List<java.nio.file.Path> starts = new ArrayList<>();
            String cwd = System.getProperty("user.dir");
            if (cwd != null) {
                starts.add(java.nio.file.Path.of(cwd).toAbsolutePath());
            }
            try {
                starts.add(java.nio.file.Path.of(PlayerJoinListener.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toAbsolutePath());
            } catch (Exception ignored) {
                // 只靠 cwd
            }
            for (java.nio.file.Path start : starts) {
                for (java.nio.file.Path cur = start; cur != null; cur = cur.getParent()) {
                    java.nio.file.Path f = cur.resolve(rel);
                    if (java.nio.file.Files.isRegularFile(f)) {
                        return f;
                    }
                }
            }
            throw new AssertionError("找不到 " + rel + "（试过 " + starts + "）喵 —— "
                    + "本断言是语义检查，找不到就必须红灯，不能 skip。");
        }

        private Source() {
        }
    }

    /** 确保静态工具类被引用（避免 IDE 的「未使用」告警把它当死代码）。 */
    @Test
    @DisplayName("夹具自检：源码读取器真的能读到 onPlayerQuit（防 lint 静默失效）")
    void sourceReaderWorks() throws Exception {
        String body = Source.methodBody("public void onPlayerQuit(");
        assertNotNull(body);
        assertTrue(body.contains("getUniqueId()"),
                "读到的 onPlayerQuit 方法体不像方法体喵（" + body.length() + " 字符）："
                        + "定位失效时 lint 会恒绿，比没有 lint 更糟。");
    }
}
