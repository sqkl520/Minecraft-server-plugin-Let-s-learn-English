package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败补偿（本轮缺陷：{@code ai_chat_feedback} 的异步 UPSERT 一旦失败就永久丢失）。
 *
 * <p><b>被钉的缺陷链条</b>（三处代码共同构成「失败不留痕」）：
 * <ol>
 *   <li>{@code persistAsync} 的 {@code whenComplete((ignored, error) -> ...)} 不看
 *       {@code error}，成功失败都从 {@code pendingWrites} 里删掉；</li>
 *   <li>{@code writeTask} 把异常吞成一条 WARNING，future 因此<b>恒以正常完成结算</b>；</li>
 *   <li>{@code flushPending} 只「等」不「重试」。</li>
 * </ol>
 * 于是「玩家已经看到点评、落库失败」这条路径上，数据静默蒸发。本类钉住补偿的四条语义：
 * <ul>
 *   <li>写成功 → <b>不</b>登记（防过度登记，否则每次正常落库都会留一份垃圾）；</li>
 *   <li>写失败 → 登记；</li>
 *   <li>登记后重放成功 → 库里有该行，且登记清空（防「只增不删」泄漏）；</li>
 *   <li>重放也失败 → <b>仍在</b>登记里（重放本身会失败，丢在这里等于永久丢）。</li>
 * </ul>
 *
 * <p><b>为什么失败分支必须由测试强制 observable</b>：这里曾经出现过「空转守卫」——
 * 加了判断却一次都没执行，被别处的 null 早退兜住了。所以本类的每个失败用例都<b>直接断言
 * 登记集合的内容</b>（{@link ChatFeedbackManager#pendingRetryCount()}），而不是只断言
 * 「日志里有 WARNING」：集合非空是「入队那行真的跑过」的唯一硬证据。
 *
 * <p><b>同步性</b>：落库走 {@code CompletableFuture}，完成时刻不确定。本类用
 * {@link ChatFeedbackManager#setWriteExecutor} 注入<b>直接执行</b>的执行器（包级可见缝隙，
 * 生产值恒为 commonPool），让「写已失败并登记」变成确定性前置条件 —— 否则每个断言后面
 * 都要跟一个 timeout，且失败用例之间会互相串扰。
 */
@ExtendWith(MockitoExtension.class)
class ChatFeedbackFailureCompensationTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final String SESSION_ID = "sess-comp-1";

    private LetsLearnEnglish plugin;
    private AIChatManager aiChatManager;
    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private Player player;
    private Logger logger;
    private FileConfiguration mainConfig;

    private ChatFeedbackManager feedbackManager;

    /** 本次用例期间 {@code logger} 收到的 WARNING 文案（用例间清空，防串味）。 */
    private final List<String> capturedWarnings = new ArrayList<>();
    private java.util.logging.Handler warningCapture;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        aiChatManager = mock(AIChatManager.class);
        databaseManager = mock(DatabaseManager.class);
        configManager = mock(ConfigManager.class);
        player = mock(Player.class);
        logger = Logger.getLogger("ChatFeedbackFailureCompensationTest");
        // 真 YamlConfiguration：mock 的 getBoolean(key, def) 返回 false，会把
        // ai-chat.feedback.enabled 误读成关闭，persistOnQuit 直接早退 → 全类假绿。
        mainConfig = new YamlConfiguration();
        feedbackManager = new ChatFeedbackManager(plugin, aiChatManager);
        // 直接执行的执行器：persistAsync 返回时写任务已经跑完（成功或失败已登记）。
        // 这是「确定性前置条件」，不是为了让测试好写 —— 生产路径不受影响。
        feedbackManager.setWriteExecutor(Runnable::run);

        // WARNING 捕获：把「失败分支被执行过」变成可观测事实（见 failedWriteIsRegistered）。
        capturedWarnings.clear();
        warningCapture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    capturedWarnings.add(String.valueOf(record.getMessage()));
                }
            }

            @Override
            public void flush() {
                // 内存收集，无需刷新
            }

            @Override
            public void close() {
                // 内存收集，无需释放
            }
        };
        logger.addHandler(warningCapture);
    }

    @AfterEach
    void tearDown() {
        // 务必摘除：Logger 按名字全局缓存，不摘会在同一 JVM 后续用例里重复累积 handler。
        if (warningCapture != null) {
            logger.removeHandler(warningCapture);
            warningCapture = null;
        }
        capturedWarnings.clear();
    }

    /** 达标会话（validInputs=3 ≥ 默认阈值 3，aiReplyCount=2 > 0）。 */
    private AIChatManager.SessionSummary qualifiedSummary() {
        return new AIChatManager.SessionSummary(SESSION_ID, "village_trade", "medium",
                3, 2, true, 1_000L, 42L, List.of());
    }

    /** persistOnQuit 路径的最小依赖；player id 由各用例自行 stub。 */
    private void stubQuitPath() {
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
    }

    /**
     * 让 {@code upsertChatFeedback} 前 {@code failTimes} 次抛异常、其后成功（返回 true）。
     *
     * <p>用「按次失败」而不是「一直失败」：补偿的核心场景是「第一次失败、重放时好了」。
     * 记录每次调用的 sessionId，供「重放写的是同一行」的断言使用。
     *
     * <p><b>R11：成功时必须显式返回 {@code true}。</b>方法签名从 {@code void} 改成
     * {@code boolean} 之后，{@code doAnswer} 返回 {@code null} 会被 Mockito 拆箱成
     * {@code false} —— 那会让「成功」用例整批误入 SQL 失败分支，登记表凭空多出一条。
     */
    private void failWriteTimesThenSucceed(int failTimes, List<String> succeededSessions) {
        AtomicInteger calls = new AtomicInteger();
        lenient().doAnswer(invocation -> {
            int n = calls.incrementAndGet();
            if (n <= failTimes) {
                throw new IllegalStateException("simulated DB failure #" + n);
            }
            succeededSessions.add(invocation.getArgument(1));
            return true;
        }).when(databaseManager).upsertChatFeedback(
                any(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), any());
    }

    /**
     * R11 新增：让 {@code upsertChatFeedback} 前 {@code failTimes} 次<b>返回 false</b>
     * （纯 SQL 失败路径）、其后返回 true。
     *
     * <p>与 {@link #failWriteTimesThenSucceed} 的区别就是<b>失败的表现形式</b>：
     * 那条走 {@code RuntimeException}（writeTask 的 catch），这条走返回值
     * （writeTask 的 {@code !persisted} 分支）。断言两者都必须触发补偿登记。
     */
    private void returnFalseTimesThenSucceed(int failTimes, List<String> succeededSessions) {
        AtomicInteger calls = new AtomicInteger();
        lenient().doAnswer(invocation -> {
            int n = calls.incrementAndGet();
            if (n <= failTimes) {
                return false; // 纯 SQL 失败：data 层已 catch 并记 WARNING，这里只回报 false
            }
            succeededSessions.add(invocation.getArgument(1));
            return true;
        }).when(databaseManager).upsertChatFeedback(
                any(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), any());
    }

    // ------------------------------------------------------------------
    // 1. 成功路径：一条都不该登记（防过度登记）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写成功 → 不登记：登记表为空，且这条断言不是靠早退空过的")
    void successfulWriteIsNotRegistered() {
        stubQuitPath();
        List<String> succeeded = new ArrayList<>();
        failWriteTimesThenSucceed(0, succeeded);

        feedbackManager.persistOnQuit(player, qualifiedSummary());

        // 先证明写<b>真的发生过</b>：否则「没登记」可能只是因为压根没走到写。
        assertEquals(List.of(SESSION_ID), succeeded,
                "前置条件：写成功路径必须真的调用了一次 UPSERT");
        assertEquals(0, feedbackManager.pendingRetryCount(),
                "写成功仍被登记 —— 每次正常落库都会留一份垃圾，登记表只增不删");
    }

    // ------------------------------------------------------------------
    // 2. 失败 → 登记
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写失败 → 登记（同时抓 WARNING 证明 catch 块真的被执行过）")
    void failedWriteIsRegistered() {
        stubQuitPath();
        when(plugin.getLogger()).thenReturn(logger);
        List<String> succeeded = new ArrayList<>();
        failWriteTimesThenSucceed(1, succeeded);

        List<String> warnings = new ArrayList<>();
        feedbackManager.persistOnQuit(player, qualifiedSummary());
        warnings.addAll(capturedWarnings);

        assertTrue(succeeded.isEmpty(), "前置条件：这一次写应当失败（未写入成功列表）");
        // 双向证据：① catch 块真的被进入过（有那条带异常对象的 WARNING）；
        assertEquals(1, warnings.size(),
                "没有记录写失败 —— 说明失败分支根本没执行，下面的登记断言就是空转的");
        assertTrue(warnings.get(0).contains("Failed to persist chat feedback for " + PLAYER_ID),
                "WARNING 文案不对，失败分支可能走了别的路径：" + warnings);
        // ② 进入之后补偿真的发生了（登记集合非空）。
        assertEquals(1, feedbackManager.pendingRetryCount(),
                "写失败没有登记 —— 这条记录会在无人知晓的情况下永久丢失（本缺陷本体）");
    }

    // ------------------------------------------------------------------
    // 2b. R11：纯 SQL 失败（返回 false，不抛异常）→ 同样登记
    //
    // 缺陷本体：upsertChatFeedback 原本是 void，内部把 SQLException 吞成 WARNING。
    // 于是 writeTask 的 catch 永远看不到这类失败 —— 补偿对「磁盘满 / 表锁 / 连接断」
    // 完全无效。下面两例把 upsertChatFeedback 的失败表现改成「返回 false」，
    // 用来钉住 writeTask 的「用返回值」分支。删掉那一段，本组立刻变红。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SQL 失败（返回 false）→ 登记：不是只有抛异常才补，删掉返回值判定必红")
    void sqlFailureReturningFalseIsRegistered() {
        stubQuitPath();
        when(plugin.getLogger()).thenReturn(logger);
        List<String> succeeded = new ArrayList<>();
        // 只返回 false，绝不抛异常 —— 这正是真实 SQLException 在 DatabaseManager 里的形状。
        returnFalseTimesThenSucceed(Integer.MAX_VALUE, succeeded);

        List<String> warnings = new ArrayList<>();
        feedbackManager.persistOnQuit(player, qualifiedSummary());
        warnings.addAll(capturedWarnings);

        assertTrue(succeeded.isEmpty(), "前置条件：这一次写应当失败（未写入成功列表）");
        // ① 写确实被调用过（不是被守卫/早退兜住）：硬证据，排除「压根没走到写」的空转。
        verify(databaseManager, times(1)).upsertChatFeedback(
                eq(PLAYER_ID), eq(SESSION_ID), eq("village_trade"), eq("medium"),
                eq(3), eq(2), eq(42), eq("local"), isNull());
        // ② 失败分支真的被执行过（文案明确区分「返回值 false」与「抛异常」两条路）。
        assertEquals(1, warnings.size(),
                "SQL 失败没有留下任何痕迹 —— 失败分支根本没执行：" + warnings);
        assertTrue(warnings.get(0).contains("SQL write returned false"),
                "失败分支走错了（应当是『返回值 false』这条，不是 catch）：" + warnings);
        // ③ 补偿真的登记了（集合非空是「入队那行跑过」的唯一硬证据）。
        assertEquals(1, feedbackManager.pendingRetryCount(),
                "SQL 失败（返回 false）没有登记 —— 磁盘满 / 表锁这类失败仍会永久丢数据");
    }

    @Test
    @DisplayName("SQL 失败登记后 quit 重放成功 → 登记清空、且重放用的是同一 session 行")
    void sqlFailureIsReplayedOnQuit() {
        stubQuitPath();
        List<String> succeeded = new ArrayList<>();
        returnFalseTimesThenSucceed(1, succeeded); // 第 1 次返回 false → 登记；重放成功

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        assertEquals(1, feedbackManager.pendingRetryCount(), "前置条件：SQL 失败未登记");

        feedbackManager.unloadPlayer(PLAYER_ID);

        assertEquals(List.of(SESSION_ID), succeeded,
                "quit 没有重放 SQL 失败的待补写行 —— 玩家下线这条记录就再也补不回来");
        assertEquals(0, feedbackManager.pendingRetryCount(), "重放成功后登记未清");
        verify(databaseManager, times(2)).upsertChatFeedback(
                eq(PLAYER_ID), eq(SESSION_ID), eq("village_trade"), eq("medium"),
                eq(3), eq(2), eq(42), eq("local"), isNull());
    }

    // ------------------------------------------------------------------
    // 2c. R11：重放阶段也必须看返回值
    //
    // 这条钉的是 replayWrites 而不是 writeTask：初次写失败（抛异常）→ 登记；
    // 重放时改成「返回 false」而不是抛异常。若 replayWrites 只看 catch，
    // 这条会把「失败」当成功、把条目从登记里抹掉 —— 一次 SQL 抖动即永久丢失。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重放时 SQL 失败（返回 false）→ 条目必须留在登记里，不能被当成功抹掉")
    void failedReplayReturningFalseStaysRegistered() {
        stubQuitPath();
        List<String> succeeded = new ArrayList<>();
        // 首写抛异常 → 登记；之后的所有重放一律返回 false（纯 SQL 失败形状）。
        AtomicInteger calls = new AtomicInteger();
        lenient().doAnswer(invocation -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new IllegalStateException("simulated first failure");
            }
            return false;
        }).when(databaseManager).upsertChatFeedback(
                any(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), any());

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        assertEquals(1, feedbackManager.pendingRetryCount(), "前置条件：首写失败已登记");

        feedbackManager.unloadPlayer(PLAYER_ID);

        assertTrue(succeeded.isEmpty(), "前置条件：没有任何一次写成功过");
        // 硬证据：首写 + 重放各一次 —— 证明「留在登记里」不是因为重放压根没跑。
        verify(databaseManager, times(2)).upsertChatFeedback(
                any(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), any());
        assertEquals(1, feedbackManager.pendingRetryCount(),
                "重放返回 false 却被当成成功清掉了 —— SQL 抖动一次就永久丢数据（replayWrites "
                        + "必须与 writeTask 一样看返回值）");
    }

    // ------------------------------------------------------------------
    // 3. 登记后 quit 重放成功 → 库有该行 + 登记清空
    // ------------------------------------------------------------------

    @Test
    @DisplayName("quit 重放成功：重放写的就是同一 session 行，且登记被清空")
    void quitReplaysRegisteredWrite() {
        stubQuitPath();
        List<String> succeeded = new ArrayList<>();
        failWriteTimesThenSucceed(1, succeeded); // 第 1 次失败 → 登记；重放成功

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        assertEquals(1, feedbackManager.pendingRetryCount(), "前置条件：失败已登记");

        // quit 出口：先重放、后清 per-player 状态。
        feedbackManager.unloadPlayer(PLAYER_ID);

        assertEquals(List.of(SESSION_ID), succeeded,
                "quit 没有重放待补写行 —— 玩家一下线，这条记录就再也补不回来了");
        assertEquals(0, feedbackManager.pendingRetryCount(),
                "重放成功后登记未清 —— 同一个 session 会每次 quit 重写一次，只增不删");
    }

    // ------------------------------------------------------------------
    // 4. 重放也失败 → 仍在登记里（不能丢）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重放也失败：剩余部分必须留在登记里，等下一次出口")
    void failedReplayStaysRegistered() {
        stubQuitPath();
        List<String> succeeded = new ArrayList<>();
        failWriteTimesThenSucceed(Integer.MAX_VALUE, succeeded); // 永远失败

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        assertEquals(1, feedbackManager.pendingRetryCount(), "前置条件：失败已登记");

        feedbackManager.unloadPlayer(PLAYER_ID);

        assertEquals(1, feedbackManager.pendingRetryCount(),
                "重放失败后把条目丢了 —— 一次失败被升级成永久丢失（照 "
                        + "AchievementManager.flushPendingUnlockWrites 的回填语义）");
    }

    // ------------------------------------------------------------------
    // 5. 关服出口（flushPending）同样会重放；终局残留会清空并报损失
    // ------------------------------------------------------------------

    @Test
    @DisplayName("关服 flushPending 重放成功：库有该行、登记清空、flush 返回 true")
    void flushPendingReplaysRegisteredWrite() {
        stubQuitPath();
        List<String> succeeded = new ArrayList<>();
        failWriteTimesThenSucceed(1, succeeded);

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        assertEquals(1, feedbackManager.pendingRetryCount(), "前置条件：失败已登记");

        assertTrue(feedbackManager.flushPending(5_000L),
                "关服排空应包含失败补偿；返回 false 说明这条记录被留在了身后");
        assertEquals(List.of(SESSION_ID), succeeded, "关服出口没有重放待补写行");
        assertEquals(0, feedbackManager.pendingRetryCount(), "重放成功后登记未清");
    }

    @Test
    @DisplayName("关服 flushPending 重放仍失败：返回 false、登记被清空（终局无下次机会）")
    void flushPendingDiscardsUnrecoverableWrites() {
        stubQuitPath();
        when(plugin.getLogger()).thenReturn(logger);
        List<String> succeeded = new ArrayList<>();
        failWriteTimesThenSucceed(Integer.MAX_VALUE, succeeded);

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        assertEquals(1, feedbackManager.pendingRetryCount(), "前置条件：失败已登记");

        assertFalse(feedbackManager.flushPending(5_000L),
                "重放失败却报告排空成功 —— 服主会以为数据都落了库");

        // 关服是终局：不是「静静留着」（再没人重放它，看起来却像还有救），而是
        // 明确记损失后清空（内存卫生 + 不撒谎）。这与 quit 出口的回填语义刻意不同。
        assertEquals(0, feedbackManager.pendingRetryCount(),
                "终局出口仍留着永远没人消费的登记 —— 假队列");
    }

    // ------------------------------------------------------------------
    // 6. 成功路径的行为不变：payload=null 的 local 行、失败重放也带同样的参数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重放用的是原始写入参数（source/payload 不被改写），UPSERT 幂等")
    void replayPreservesOriginalColumns() {
        stubQuitPath();
        List<String> succeeded = new ArrayList<>();
        failWriteTimesThenSucceed(1, succeeded);

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        feedbackManager.unloadPlayer(PLAYER_ID);

        // quit 路径写的是 source=local、payload=null 的本地行；重放必须逐列一致，
        // 否则「补回来」的那一行与原始语义不同（例如把 local 写成 ai）。
        verify(databaseManager, times(2)).upsertChatFeedback(
                eq(PLAYER_ID), eq(SESSION_ID), eq("village_trade"), eq("medium"),
                eq(3), eq(2), eq(42), eq("local"), isNull());
    }

    // ------------------------------------------------------------------
    // 7. 主动拒绝 ≠ 写失败：闸门挡掉的提交不该进补偿登记
    // ------------------------------------------------------------------

    @Test
    @DisplayName("关闸后的提交不进补偿登记（DB 已关，登记了也没人重放）")
    void rejectedSubmissionIsNotRegistered() {
        // flush 0ms 关闸后提交：writeTask 在触碰 DB 之前就 return，不是「写失败」。
        assertTrue(feedbackManager.flushPending(0L));
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        feedbackManager.persistOnQuit(player, qualifiedSummary());

        verify(databaseManager, never()).upsertChatFeedback(
                any(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), any());
        assertEquals(0, feedbackManager.pendingRetryCount(),
                "被闸门拒绝的提交进了补偿登记 —— 关服期间会积累一堆永远补不上的条目");
    }

    // ------------------------------------------------------------------
    // 8. 同一场先 local 后 ai：失败那笔的快照覆盖，重放只写一行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一 session 多次失败只留一条登记（内层键是业务主键 session_id）")
    void sameSessionFailuresCollapseToOneEntry() {
        stubQuitPath();
        List<String> succeeded = new ArrayList<>();
        failWriteTimesThenSucceed(Integer.MAX_VALUE, succeeded);

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        // 同一场再来一笔（真实场景里是 AI 覆写 source=ai 的那次）：
        AIChatManager.SessionSummary sameSession = new AIChatManager.SessionSummary(
                SESSION_ID, "village_trade", "medium", 3, 2, true, 1_000L, 42L, List.of());
        feedbackManager.persistOnQuit(player, sameSession);

        assertEquals(1, feedbackManager.pendingRetryCount(),
                "同一 session 留了多条登记 —— UPSERT 写的是同一行，重复重放只是白跑；"
                        + "且会让登记表随失败次数线性膨胀");
    }
}
