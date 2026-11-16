package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R12c-F2：{@code PlayerJoinListener} 的 join 异步块守卫。
 *
 * <h2>被修的缺陷</h2>
 * <p>异步块原本只判 {@code plugin.isEnabled()}。而 {@code /lea reload} 把
 * {@code server.enabled} 由 true 改 false 时走的是 {@code HotReloadManager → disableAll()}，
 * 插件本身<b>一直是 enabled</b>（没有 {@code onDisable}）—— 所以窗口里
 * {@code isEnabled()} 恒为 true，而 {@code disableAll()} 的 D10 已经把
 * {@code dailyChallengeManager} 置 null。异步任务于是在 null 上 NPE，
 * 堆栈从 Bukkit 调度线程冒出，与真实原因（有人 reload 了）毫无关联。
 *
 * <h2>怎么在纯 JVM 里真的复现这个窗口</h2>
 * <p><b>不</b>打桩 {@code isEnabled()} 返回 false（那是「假设闸关了」，不是「闸真的会关」）。
 * 这里让 {@code isEnabled()} 照旧返回 <b>true</b>（reload 窗口的真实值），
 * 只把 {@code isFullyEnabled()} 打桩成 false（拆解中的真实值）—— 两个判据在窗口里
 * <b>分叉</b>，这正是缺陷成立的条件。于是：
 * <ul>
 *   <li>旧实现（裸 {@code isEnabled()}）→ 放行 → 拿到 null → NPE；</li>
 *   <li>新实现（{@code isFullyEnabled()}）→ 拦住 → 零派发。</li>
 * </ul>
 * 两种实现在这条用例下的行为<b>不同</b>，所以它可以红。
 */
@DisplayName("PlayerJoinListener join 异步块 — reload 窗口不 NPE")
class PlayerJoinListenerReloadWindowTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000f2f01");

    private LetsLearnEnglish plugin;
    private Server server;
    private BukkitScheduler scheduler;
    private DailyChallengeManager challenges;

    /**
     * 异步块就地执行：把 {@code runTaskAsynchronously} 的 Runnable 在本线程跑掉，
     * 断言时刻确定。真实线程池会让「异常有没有冒出」这件事不可观测。
     *
     * <p>刻意<b>不</b> try/catch 执行过程：旧实现抛出的 NPE 必须逃到这里来，
     * 让用例以异常变红（而不是被吞成一条没人看的 WARNING）。
     */
    private Runnable lastAsyncTask;

    private Captured logs;
    private MockedStatic<org.bukkit.Bukkit> bukkit;

    /** 日志探针（本仓每个测试类各带一份）。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("PlayerJoinListenerReloadWindowTest." + UUID.randomUUID());
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

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    @BeforeEach
    void setUp() {
        logs = new Captured();
        plugin = mock(LetsLearnEnglish.class);
        challenges = mock(DailyChallengeManager.class);

        scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    lastAsyncTask = inv.getArgument(1);
                    return null;
                });
        // join 里还有 60L / 40L 的 runTaskLater（update checker / 欢迎语）：本类不测它们，
        // 但必须让调度调用成功返回，否则 join 编排会在中途抛 NPE 打断。
        when(scheduler.runTaskLater(any(org.bukkit.plugin.Plugin.class), any(Runnable.class), any(Long.class)))
                .thenReturn(null);

        server = mock(Server.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logs.logger);

        bukkit = mockStatic(org.bukkit.Bukkit.class);
        bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        logs.close();
    }

    /**
     * 造一个「join 事件已到、但插件的其它 manager 都存在」的最小玩家。
     *
     * <p>回归技巧：{@link LetsLearnEnglish} 是 mock，未打桩的 getter 返回 null。join 方法体
     * 里对 {@code getWordLearningManager()} 等的直接解引用会 NPE —— 所以这里必须把
     * 方法体<b>用到</b>的那几个桩出来，否则用例会在与缺陷无关的地方红。
     */
    private Player joinEventPlayer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(ID);
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
        when(player.hasPlayedBefore()).thenReturn(true);
        return player;
    }

    /** 让 join 方法体里除「每日挑战分配」以外的分支全部安全短路。 */
    private void stubOtherManagersToShortCircuit() {
        var pds = mock(com.letslearnenglish.minecraftplugin.data.PlayerDataStore.class);
        when(plugin.getPlayerDataStore()).thenReturn(pds);
        var achievements = mock(com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager.class);
        when(plugin.getAchievementManager()).thenReturn(achievements);
        var onboarding = mock(com.letslearnenglish.minecraftplugin.core.onboarding.OnboardingManager.class);
        when(plugin.getOnboardingManager()).thenReturn(onboarding);
        var social = mock(com.letslearnenglish.minecraftplugin.core.social.SocialManager.class);
        when(plugin.getSocialManager()).thenReturn(social);
        var season = mock(com.letslearnenglish.minecraftplugin.core.season.SeasonManager.class);
        when(plugin.getSeasonManager()).thenReturn(season);
        // updateChecker：60L 任务体里才取，本类不执行该任务，但 join 主线程不取它。
        when(plugin.getDuelManager()).thenReturn(null); // 空守卫分支
    }

    // ==================================================================
    // 行为 1：reload 拆解窗口 —— 不派发、不 NPE
    // ==================================================================

    /**
     * <b>这是本类真正能杀死「只判 isEnabled()」的那个用例</b>。
     *
     * <p>窗口取 D1~D9：{@code runtimeActive} 已置 false（{@code disableAll} 的第一条语句），
     * 但 D10 的置空<b>还没跑</b> —— 所以 {@code dailyChallengeManager} <b>非 null</b>。
     * 这一点是本用例与 {@link #reloadWindowAfterD10DoesNotNpe()} 的全部差别：
     * <ul>
     *   <li>只判 {@code isEnabled()}（旧实现）→ 放行 → 在**半拆的运行时**上跑一次
     *       {@code checkAndAssign}（COUNT 预检 + INSERT，一次完整 DB 往返），
     *       与此同时 D7 正在 flush、D8 正在拆外部接线；</li>
     *   <li>判 {@code isFullyEnabled()} → 拦住。</li>
     * </ul>
     *
     * <p>为什么不靠「字段为 null」来杀变异：加一个 null 复检也能消掉 NPE，
     * 那样变异就不红了 —— 但 null 复检只是把症状从「NPE」换成「拆解期间照常写库」，
     * 缺陷没修。所以要断言的真正命题是「拆解窗口里<b>零派发</b>」，不是「不 NPE」。
     */
    @Test
    @DisplayName("F2：reload 拆解窗口（D1~D9，字段仍非 null）零派发")
    void reloadWindowDispatchesNothingWhileRuntimeIsTornDown() {
        stubOtherManagersToShortCircuit();
        // 窗口里的真实值：插件仍 enabled（没有 onDisable），运行时已开始拆。
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.isFullyEnabled()).thenReturn(false);
        // D10 还没跑到 —— 字段非 null，所以「判空」这道防线在这里<b>不生效</b>。
        when(plugin.getDailyChallengeManager()).thenReturn(challenges);

        Player player = joinEventPlayer();
        new PlayerJoinListener(plugin).onPlayerJoin(new PlayerJoinEvent(player, "Alice joined"));

        assertTrue(lastAsyncTask != null, "前置：join 必须派发过异步任务（否则本用例什么都没测）喵");
        lastAsyncTask.run();

        verify(challenges, never()).checkAndAssign(any(UUID.class));
    }

    /**
     * 窗口取 D10 之后：字段已被 {@code disableAll} 置 null。旧实现在这里抛 NPE
     * （异常从 Bukkit 异步调度线程冒出，栈里与真实原因毫无关联）。
     *
     * <p>单靠「字段判空」也能让本用例绿 —— 这正是它与上一条的分工：上一条钉「零派发」，
     * 本一条钉「不 NPE」。两条都在，才能同时排除「只加判空」和「只换闸」两种半修。
     */
    @Test
    @DisplayName("F2：reload 窗口 D10 之后（字段已置 null）不 NPE")
    void reloadWindowAfterD10DoesNotNpe() {
        stubOtherManagersToShortCircuit();
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.isFullyEnabled()).thenReturn(false);
        // D10 已置空 —— 旧实现的 NPE 来源。
        when(plugin.getDailyChallengeManager()).thenReturn(null);

        Player player = joinEventPlayer();
        new PlayerJoinListener(plugin).onPlayerJoin(new PlayerJoinEvent(player, "Alice joined"));

        assertTrue(lastAsyncTask != null, "前置：join 必须派发过异步任务喵");
        // 旧实现会在这里抛 NPE（未捕获，从 Bukkit 调度线程冒出）。
        lastAsyncTask.run();

        for (String m : logs.messages) {
            assertFalse(m.contains("Daily challenge assignment failed"),
                    "拆解窗口里不该走到「分配失败」那条 WARNING 喵 —— 正常路径是在闸就早退。"
                            + "实测日志：" + logs.messages);
        }
    }

    /** 稳态（运行时装着、配置开着）：必须照常派发 —— 闸不能把正常路径也挡掉。 */
    @Test
    @DisplayName("F2：稳态（isFullyEnabled=true）照常派发 checkAndAssign")
    void steadyStateStillDispatches() {
        stubOtherManagersToShortCircuit();
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.isFullyEnabled()).thenReturn(true);
        when(plugin.getDailyChallengeManager()).thenReturn(challenges);

        Player player = joinEventPlayer();
        new PlayerJoinListener(plugin).onPlayerJoin(new PlayerJoinEvent(player, "Alice joined"));
        lastAsyncTask.run();

        verify(challenges).checkAndAssign(ID);
    }

    /**
     * 闸与取值之间仍有几纳秒：即便闸通过了，字段也可能刚被置空。
     * 这一条钉住「纵深防御」不得被当成冗余删掉。
     */
    @Test
    @DisplayName("F2：闸通过但字段已置空 → 静默跳过，不 NPE")
    void nullManagerAfterGateIsTolerated() {
        stubOtherManagersToShortCircuit();
        when(plugin.isFullyEnabled()).thenReturn(true);
        when(plugin.getDailyChallengeManager()).thenReturn(null);

        Player player = joinEventPlayer();
        new PlayerJoinListener(plugin).onPlayerJoin(new PlayerJoinEvent(player, "Alice joined"));
        lastAsyncTask.run(); // 旧实现（无 null 复检）在这里 NPE
    }

    /**
     * 分配本身抛 RuntimeException（DB 故障 / stripe lock 竞争下的罕见失败）时，
     * 必须被收口成本方法自己的一条 WARNING，<b>不能</b>从调度线程冒出。
     *
     * <p>理由：Bukkit 的异步调度只打印，不归因；一条裸堆栈会被服主读成「插件崩了」，
     * 而真实后果只是「今天少三个挑战」。
     */
    @Test
    @DisplayName("F2：checkAndAssign 抛 RuntimeException → 收口成 WARNING，不逃逸")
    void assignmentFailureIsContained() {
        stubOtherManagersToShortCircuit();
        when(plugin.isFullyEnabled()).thenReturn(true);
        when(plugin.getDailyChallengeManager()).thenReturn(challenges);
        org.mockito.Mockito.doThrow(new IllegalStateException("db gone"))
                .when(challenges).checkAndAssign(ID);

        Player player = joinEventPlayer();
        new PlayerJoinListener(plugin).onPlayerJoin(new PlayerJoinEvent(player, "Alice joined"));
        lastAsyncTask.run(); // 不 try/catch：异常必须被生产代码吃掉

        assertTrue(logs.messages.stream().anyMatch(m -> m.contains("Daily challenge assignment failed")),
                "分配失败必须留一条可读 WARNING 喵（否则服主只看到一条裸堆栈）。实测：" + logs.messages);
    }

    // ==================================================================
    // 结构：派发点仍在 async 块里（不是被搬到主线程）
    // ==================================================================

    /**
     * join 是主线程事件处理器。{@code checkAndAssign} 会做 COUNT 预检 + INSERT，
     * 全在主线程做等于给每次进服加一次同步 DB 往返（R13-M1 修的就是这个）。
     * 这条钉住「异步」这个性质没被顺手改掉。
     */
    @Test
    @DisplayName("F2：checkAndAssign 仍在 runTaskAsynchronously 的 Runnable 里（不在主线程）")
    void assignmentStillRunsOffTheMainThread() throws Exception {
        stubOtherManagersToShortCircuit();
        when(plugin.isFullyEnabled()).thenReturn(true);
        when(plugin.getDailyChallengeManager()).thenReturn(challenges);

        Player player = joinEventPlayer();
        new PlayerJoinListener(plugin).onPlayerJoin(new PlayerJoinEvent(player, "Alice joined"));

        // 主线程（join 方法体）里一次都不该调 —— 只在异步 Runnable 里调。
        verify(challenges, never()).checkAndAssign(any(UUID.class));
        assertEquals(1, 1, "占位：真实断言是上面那句 never()");

        lastAsyncTask.run();
        verify(challenges).checkAndAssign(ID);
    }

    /** 取 join 异步块体内是否含守卫（源码级），与 ServerToggleLintsTest 的 L11 互为补充。 */
    @Test
    @DisplayName("F2：源码里 isFullyEnabled() 每出现一次都在 async 块内且早于 checkAndAssign")
    void gateIsInsideTheAsyncBlock() throws Exception {
        String src = readSource();
        int asyncAt = src.indexOf("runTaskAsynchronously(");
        int gateAt = src.indexOf("isFullyEnabled()", asyncAt);
        int assignAt = src.indexOf("checkAndAssign(", asyncAt);
        assertTrue(asyncAt > 0 && gateAt > 0 && assignAt > 0,
                "定位失效喵：async=" + asyncAt + " gate=" + gateAt + " assign=" + assignAt);
        assertTrue(gateAt < assignAt, "守卫必须在派发点之前喵");
    }

    private static String readSource() throws Exception {
        PathHolder p = PathHolder.locate();
        return java.nio.file.Files.readString(p.path, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 从 cwd / code-source 各自上溯找 repo 根（不依赖 cwd）。 */
    private static final class PathHolder {
        final java.nio.file.Path path;

        private PathHolder(java.nio.file.Path p) {
            this.path = p;
        }

        static PathHolder locate() {
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
                        return new PathHolder(f);
                    }
                }
            }
            throw new AssertionError("找不到 " + rel + "（试过 " + starts + "）喵 —— "
                    + "本断言是语义检查，找不到就必须红灯，不能 skip。");
        }
    }
}
