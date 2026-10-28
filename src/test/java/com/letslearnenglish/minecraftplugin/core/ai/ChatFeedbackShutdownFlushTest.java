package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * L1（P1·关服丢数据）：{@link ChatFeedbackManager#flushPending(long)} 的行为守卫。
 *
 * <p><b>为什么需要这个类</b>：L1 的缺陷只在「关服瞬间」可观测 —— 落库走
 * {@code CompletableFuture.runAsync}（ForkJoinPool.commonPool），而 Paper 的
 * {@code waitForAsyncTasksShutdown()} 只等 BukkitScheduler 派发的异步任务。原来的
 * {@code writeTask} 用 {@code plugin.isEnabled()} 守卫，而 Bukkit 在调 {@code onDisable()}
 * <b>之前</b>就把 enabled 写成 false，于是「玩家已经看到点评、还没落库」的那一小段
 * 全部静默丢弃。这类缺陷在正常测试里永远不红：写任务的异常被吞、回调里也没有返回值。
 *
 * <p>本类钉三条：
 * <ol>
 *   <li>在途任务真的被排空（flush 返回 true 且 DB 真的被调用 N 次）；</li>
 *   <li>超时<b>不抛异常</b>，只返回 false（onDisable 没有上层可补救）；</li>
 *   <li>flush 之后的新提交被拒绝（否则关库后会刷一屏 "database is closed"）。</li>
 * </ol>
 *
 * <p><b>为什么用 latch 而不是 sleep</b>：写任务在 commonPool 上执行，完成时刻不可预测；
 * sleep 既慢又不稳（慢 CI 上会假红、快机器上会假绿）。latch 让「任务已开始 / 尚未结束」
 * 变成可精确控制的状态。
 */
@ExtendWith(MockitoExtension.class)
class ChatFeedbackShutdownFlushTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final String SESSION_ID = "sess-flush-1";

    private LetsLearnEnglish plugin;
    private AIChatManager aiChatManager;
    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private Player player;
    private Logger logger;
    private FileConfiguration mainConfig;

    private ChatFeedbackManager feedbackManager;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        aiChatManager = mock(AIChatManager.class);
        databaseManager = mock(DatabaseManager.class);
        configManager = mock(ConfigManager.class);
        player = mock(Player.class);
        logger = Logger.getLogger("ChatFeedbackShutdownFlushTest");
        // 真 YamlConfiguration：mock 的 getBoolean(key, def) 会返回 false，把
        // ai-chat.feedback.enabled 误读成关闭，persistOnQuit 直接早退 → 所有用例假绿。
        mainConfig = new YamlConfiguration();
        feedbackManager = new ChatFeedbackManager(plugin, aiChatManager);
    }

    /** 达标会话（validInputs=3 ≥ 默认阈值 3，aiReplyCount=2 > 0）。 */
    private AIChatManager.SessionSummary qualifiedSummary() {
        return new AIChatManager.SessionSummary(SESSION_ID, "village_trade", "medium",
                3, 2, true, 1_000L, 42L, List.of());
    }

    /** persistOnQuit 路径的最小依赖：配置 + DB（玩家 id 由各用例自行 stub）。 */
    private void stubQuitPath() {
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
    }

    /**
     * 日志门面：只在真的会走到「flush 超时 → WARNING」那条分支的用例里铺。
     *
     * <p>STRICT_STUBS 纪律：排空成功的用例不会打日志，提前铺 {@code getLogger()} 会变成
     * 死代码 stub，在下一次运行时以 {@code UnnecessaryStubbingException} 报红。
     */
    private void stubLogger() {
        when(plugin.getLogger()).thenReturn(logger);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("flushPending 会等在途写任务真正落库，返回 true 且登记表归零")
    void flushPendingDrainsInFlightWrites() throws Exception {
        stubQuitPath();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            // 卡住写任务，模拟「DB 慢 / 线程池排队」——此时记录仍在途
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(databaseManager).upsertChatFeedback(
                any(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), any());
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        assertTrue(started.await(2, TimeUnit.SECONDS), "写任务没有在 2 秒内启动");
        assertEquals(1, feedbackManager.pendingWriteCount(), "在途任务未被登记 —— flush 会漏掉它");

        // 从另一个线程放行，主线程此时正在 flush 里等
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(120L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.start();

        assertTrue(feedbackManager.flushPending(5_000L),
                "flushPending 没等到在途写任务 —— 关服时这条记录会被静默丢弃");
        releaser.join(2_000L);
        verify(databaseManager, times(1)).upsertChatFeedback(
                eq(PLAYER_ID), eq(SESSION_ID), eq("village_trade"), eq("medium"),
                eq(3), eq(2), eq(42), eq("local"), isNull());
        assertEquals(0, feedbackManager.pendingWriteCount(), "登记表未清理：完成的任务必须移除");
    }

    @Test
    @DisplayName("超时只返回 false、绝不抛异常；放行后登记表能排空（不永久卡住）")
    void flushPendingTimeoutDoesNotThrow() throws Exception {
        stubQuitPath();
        stubLogger();
        CountDownLatch gate = new CountDownLatch(1);
        doAnswer(invocation -> {
            gate.await(5, TimeUnit.SECONDS);
            return null;
        }).when(databaseManager).upsertChatFeedback(
                any(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), any());
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        // 登记是同步完成的（persistAsync 在 submitLock 内 add 之后才返回），因此这里可以
        // 确定性断言「在途 1 条」—— 不依赖 commonPool 何时真正调度这条任务。
        assertEquals(1, feedbackManager.pendingWriteCount(), "在途任务未被登记");

        // 150ms 预算 + 卡住的写任务：必须干净返回 false，不能把异常抛给 onDisable。
        // 这里只断言「不抛 + 返回 false」：超时是运维信号（DB 慢），不是 bug。
        assertFalse(feedbackManager.flushPending(150L),
                "写任务明明还没完成，flushPending 却报告已排空（关服会丢数据）");
        assertEquals(1, feedbackManager.pendingWriteCount(),
                "超时后条目被擅自清空了 —— 之后的 flush 会误以为已排空");

        // 放行后二次 flush：条目真正结算（写入或按闸拒绝），登记表归零，不会永久卡住。
        // 这条同时封死「flush 超时后把 pendingWrites 直接 clear」这类假修复
        // —— 那样第二次会立刻返回 true，但任务其实还在跑、DB 随时可能被关掉。
        gate.countDown();
        assertTrue(feedbackManager.flushPending(3_000L),
                "放行后二次 flush 仍未排空 —— 在途条目永久卡在登记表里");
        assertEquals(0, feedbackManager.pendingWriteCount());
    }

    @Test
    @DisplayName("flush 之后提交的写任务必须被拒绝（否则关库后刷一屏异常）")
    void writesAfterFlushAreRejected() {
        // 只铺「提交前」要读的配置：拒绝发生在提交点，写任务根本不会启动，
        // 因此 getDatabaseManager()/getLogger() 都不该被问（铺了就是 STRICT_STUBS 的多余代码）。
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        // 必须给出非 null 的 UUID：否则 persistAsync 会先在「playerId == null」处返回，
        // 本用例就变成「因为 id 是 null 所以没写库」，accepting 闸删掉也照样绿。
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        assertTrue(feedbackManager.flushPending(0L), "启动时本就没有在途任务，应直接返回 true");
        feedbackManager.persistOnQuit(player, qualifiedSummary());

        // 提交点直接拒绝：任务根本没进线程池，因此是确定性断言而不是「等一会儿看看」
        assertEquals(0, feedbackManager.pendingWriteCount(),
                "flush 之后仍登记了新任务 —— accepting 闸没关上");
        verify(plugin, never()).getDatabaseManager();
    }

    @Test
    @DisplayName("重复 flush 幂等：第二次直接返回 true，不会因登记表为空而异常")
    void flushIsIdempotent() {
        // 第二次调用时 pendingWrites 已空：走的是「isEmpty → 直接 true」短路，
        // 因此不该去问 plugin.getLogger()（STRICT_STUBS 下铺了就是多余 stub）。
        assertTrue(feedbackManager.flushPending(1_000L));
        assertTrue(feedbackManager.flushPending(1_000L));
    }

    // ------------------------------------------------------------------
    // E. draining 执行闸 —— 「已登记但尚未开始执行」的唯一场景
    //
    // 上面 A~D 四例都让写任务先跑起来再 flush，此时写线程早已越过 writeTask 的守卫，
    // draining 取什么值都不影响结果 —— 删掉 `draining = true` 依然全绿。那是假绿。
    // 下面两例用可控执行器把任务**扣在队列里不跑**，才真正覆盖到 draining。
    // ------------------------------------------------------------------

    /**
     * 「扣住任务不执行」的执行器：提交的 Runnable 先攒着，由测试决定何时放行。
     *
     * <p>这就是 commonPool 在「已提交、尚未被工作线程取走」那一刻的等价物 ——
     * 也正是 {@code draining} 存在的理由（排空窗口内必须让它们能跑，否则 flush 会
     * 「等不到、报成功、数据丢」）。
     */
    private static final class HoldingExecutor implements java.util.concurrent.Executor {
        private final List<Runnable> held = new java.util.ArrayList<>();

        @Override
        public void execute(Runnable command) {
            held.add(command);
        }

        /** 依次执行所有扣住的任务（在调用者线程上）。 */
        void releaseAll() {
            List<Runnable> drained = new java.util.ArrayList<>(held);
            held.clear();
            drained.forEach(Runnable::run);
        }

        int heldCount() {
            return held.size();
        }
    }

    @Test
    @DisplayName("draining：已登记但尚未开始执行的写任务，flush 期间必须放行（删掉 draining=true 会红）")
    void drainingLetsRegisteredButNotYetStartedWritesRun() throws Exception {
        stubQuitPath();
        HoldingExecutor holding = new HoldingExecutor();
        feedbackManager.setWriteExecutor(holding);

        CountDownLatch writeHappened = new CountDownLatch(1);
        doAnswer(invocation -> {
            writeHappened.countDown();
            return null;
        }).when(databaseManager).upsertChatFeedback(
                any(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), any());
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        // 现已登记，但执行器把任务扣住了 —— 写线程一次都没跑过。
        assertEquals(1, feedbackManager.pendingWriteCount());
        assertEquals(1, holding.heldCount(), "前置条件：任务应被扣在队列里未执行");

        // 关键时序：flush 先关闸（draining=true → accepting=false），再由另一线程放行。
        // 若 draining 没开，写线程会读到 !accepting && !draining → 直接 return，
        // future 以「正常完成」结算，flush 报成功而数据库一次都没被写 —— 正是 L1 的 bug。
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            holding.releaseAll();
        });
        releaser.start();

        assertTrue(feedbackManager.flushPending(5_000L), "flush 应等在途任务结算");
        releaser.join(3_000L);

        assertTrue(writeHappened.await(1, TimeUnit.SECONDS),
                "排空窗口内放行了『已登记未执行』的任务吗？没写库说明 draining 闸失效 —— "
                        + "flush 会报成功但数据丢失。");
        verify(databaseManager, times(1)).upsertChatFeedback(
                eq(PLAYER_ID), eq(SESSION_ID), eq("village_trade"), eq("medium"),
                eq(3), eq(2), eq(42), eq("local"), isNull());
        assertEquals(0, feedbackManager.pendingWriteCount());
    }

    @Test
    @DisplayName("draining 只覆盖排空窗口：flush 结束后的残留任务必须被拒绝（删掉 finally 会红）")
    void drainingWindowClosesAfterFlush() {
        // 只铺提交前要读的配置。
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        // 下面两条用 lenient：正确实现下守卫会在触碰 DB 之前就 return，这两条 stub
        // 确实不会被消费。但它们**必须存在** —— 否则一旦守卫失效，任务会先撞上
        // plugin.getDatabaseManager() == null 抛 NPE、被 writeTask 的 catch 吞掉，
        // 于是 verify(never()) 空过，变异杀不死（这正是本用例第一版踩的坑）。
        // 铺好之后，「被拒绝」与「NPE 后假装被拒绝」才区分得开。
        lenient().when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        lenient().when(plugin.getLogger()).thenReturn(logger);
        HoldingExecutor holding = new HoldingExecutor();
        feedbackManager.setWriteExecutor(holding);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        feedbackManager.persistOnQuit(player, qualifiedSummary());
        assertEquals(1, holding.heldCount(), "前置条件：任务被扣住未执行");

        // 0ms 预算 → 立刻判定超时返回 false，finally 把 draining 关回 false。
        assertFalse(feedbackManager.flushPending(0L), "任务未完成，flush 应报未排空");
        assertEquals(1, feedbackManager.pendingWriteCount(), "超时不得擅自清空登记表");

        // 排空窗口已关闭：此刻放行被扣住的任务 → 写线程读 !accepting && !draining
        // → 必须被拒绝，绝不能去碰已关闭的数据库。
        holding.releaseAll();

        verify(databaseManager, never()).upsertChatFeedback(
                any(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), any());
        assertEquals(0, feedbackManager.pendingWriteCount(),
                "被拒绝的任务仍以『正常完成』结算，登记表应归零");
    }
}
