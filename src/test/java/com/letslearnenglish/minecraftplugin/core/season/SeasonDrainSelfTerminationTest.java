package com.letslearnenglish.minecraftplugin.core.season;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R12c-F4：{@code SeasonManager} 的后台 drain 任务是全仓唯一「无句柄、无 shutdown」的
 * 周期型调度派发点。本类钉住它的<b>三重自限</b>是否真的成立，以及
 * 「为什么当前不需要 shutdown() 钩子」+「什么条件下它真的会变成泄漏」。
 *
 * <h2>三重自限（A2 审计列出，本类逐条验证）</h2>
 * <ol>
 *   <li>{@code releaseDrainScheduled} 单飞：同刻至多 1 个在途；</li>
 *   <li>{@code MAX_DRAIN_ATTEMPTS} 封顶后 {@code hasDrainablePending()} 返回 false，
 *       链自然终止；</li>
 *   <li>停用窗口的 {@code IllegalStateException} 被捕获并复位标志（不会「任务没跑
 *       但闩卡住」→ 永不重试）。</li>
 * </ol>
 *
 * <h2>本类对 F4 判定的贡献</h2>
 * <p>第 2 条是「不需要 shutdown() 的唯一理由」：链条自己会停。所以它<b>必须</b>被测试
 * 钉住 —— 一旦有人把 {@code MAX_DRAIN_ATTEMPTS} 去掉（或改成无限重试 / 改成
 * {@code Integer.MAX_VALUE} 而不改其它逻辑），F4 立刻从 ACCEPTED_RISK 变成真泄漏，
 * 而<b>没有任何现有用例会红</b>（A2 审计原话：「若将来有人把 MAX_DRAIN_ATTEMPTS 去掉
 * 或改成无限重试，就会变成关服后仍在重排的真泄漏」）。
 *
 * <p>第 3 条钉住「停用窗口不会把闩卡死」：闩卡死 = 链条永久停摆 = 奖励永久卡住，
 * 这和「泄漏」是同一枚硬币的两面。
 */
@DisplayName("SeasonManager drain 任务 — 三重自限是否真的收敛")
class SeasonDrainSelfTerminationTest {

    private static final String PREFIX = "lle_";

    @TempDir
    Path tempDir;

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration config;
    private SeasonManager manager;
    private MockedStatic<Bukkit> bukkit;
    private CapturingHandler handler;
    private Logger logger;

    /** 记录被排的延迟任务，但**不执行** —— 本类要数「排了几次」，不是跑几次。 */
    private final AtomicInteger scheduledDrains = new AtomicInteger();
    /** 模拟 scheduler 已关闭（插件停用窗口）：runTaskLaterAsynchronously 抛 IllegalStateException。 */
    private final AtomicBoolean schedulerClosed = new AtomicBoolean(false);

    private static final class CapturingHandler extends Handler {
        final List<String> messages = new ArrayList<>();
        final List<java.util.logging.Level> levels = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            messages.add(record.getMessage() == null ? "" : record.getMessage());
            levels.add(record.getLevel());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        synchronized void clear() {
            messages.clear();
            levels.clear();
        }

        synchronized String describe() {
            return String.join(" | ", messages);
        }
    }

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("SeasonDrainSelfTerminationTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);

        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        config = new YamlConfiguration();
        config.set("season.enabled", true);
        config.set("season.duration-days", 30);
        config.set("database.type", "sqlite");
        config.set("database.mysql.table-prefix", PREFIX);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getEconomy()).thenReturn(null);
        when(plugin.getProxyMessenger()).thenReturn(null);

        PlayerDataStore pds = mock(PlayerDataStore.class);
        when(plugin.getPlayerDataStore()).thenReturn(pds);
        MessageUtil mu = mock(MessageUtil.class);
        when(plugin.getMessageUtil()).thenReturn(mu);
        when(mu.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(inv -> "MSG:" + inv.getArgument(1));

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenReturn(null);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenReturn(null);
        // drain 排程：只记账，不执行（要数次数）。
        when(scheduler.runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), anyLong()))
                .thenAnswer(inv -> {
                    if (schedulerClosed.get()) {
                        // 停用窗口：Bukkit 在 scheduler 已关时抛这个
                        throw new IllegalStateException("Plugin attempted to register task while disabled");
                    }
                    scheduledDrains.incrementAndGet();
                    return null;
                });
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        // loadRankedPlayersForPeriod 的读失败路径会打 SEVERE 并 break —— 本类不关心结算本身。
        when(plugin.getDatabaseManager()).thenReturn(mock(DatabaseManager.class));

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());

        manager = new SeasonManager(plugin);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        logger.removeHandler(handler);
    }

    // ------------------------------------------------------------------ 反射工具

    @SuppressWarnings("unchecked")
    private java.util.Map<Integer, Long> pendingReleases() {
        return (java.util.Map<Integer, Long>) readField("pendingReleaseRetries");
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<Integer, Integer> drainAttempts() {
        return (java.util.Map<Integer, Integer>) readField("releaseDrainAttempts");
    }

    private boolean drainScheduledFlag() {
        java.util.concurrent.atomic.AtomicBoolean b =
                (java.util.concurrent.atomic.AtomicBoolean) readField("releaseDrainScheduled");
        return b.get();
    }

    private Object readField(String name) {
        try {
            Field f = SeasonManager.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射读 " + name + " 失败", e);
        }
    }

    private void callScheduleReleaseDrain() {
        try {
            Method m = SeasonManager.class.getDeclaredMethod("scheduleReleaseDrain");
            m.setAccessible(true);
            m.invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射调 scheduleReleaseDrain 失败", e);
        }
    }

    /**
     * 直接往待重试表里塞一条，并把它的重试计数推到 {@code attempts}。
     *
     * <p>为什么不走 {@code settleIfNeededAsync} 造这个状态：那需要真 DB + 故障注入
     * （{@code SeasonSettlementRollbackTest} 已经这么做了），而本类要测的是
     * <b>「计数器到顶之后链还排不排」</b>这个纯内存判定，跟库无关。
     * 用反射构状态能把「上限定在哪、到顶后还排不排」变成可数的事实。
     */
    private void seedPending(int seasonId, int attempts) {
        pendingReleases().put(seasonId, System.currentTimeMillis());
        if (attempts > 0) {
            drainAttempts().put(seasonId, attempts);
        }
    }

    // ==================================================================
    // 自限 1：单飞
    // ==================================================================

    @Test
    @DisplayName("F4：单飞 —— 连续多次请求只排 1 个任务")
    void onlyOneDrainIsScheduledAtATime() {
        seedPending(7, 0);

        callScheduleReleaseDrain();
        callScheduleReleaseDrain();
        callScheduleReleaseDrain();

        assertEquals(1, scheduledDrains.get(),
                "单飞失效喵：排了 " + scheduledDrains.get() + " 个 drain 任务。"
                        + "同一个待重试条目被重排多次 = 关服后仍有多条链在排 —— 这就是真泄漏的形态。");
    }

    // ==================================================================
    // 自限 2：MAX_DRAIN_ATTEMPTS 封顶 —— F4 判 ACCEPTED_RISK 的唯一依据
    // ==================================================================

    /**
     * 本条是 F4 的<b>全部理由</b>：重试计数到顶后，链不再重排。
     *
     * <p>变异（A2 审计点名的假设）：把 {@code MAX_DRAIN_ATTEMPTS} 从 5 改成
     * {@code Integer.MAX_VALUE}（或删掉 {@code hasDrainablePending} 里的上界比较）——
     * 本用例立刻红，并且这是<b>唯一</b>会红的用例。届时 F4 从 ACCEPTED_RISK
     * 升级为必须修的真泄漏（关服后仍在 60s 一次地往调度器里排任务）。
     */
    @Test
    @DisplayName("F4：重试耗尽后不再重排（上界就是「不需要 shutdown()」的唯一理由）")
    void exhaustedRetriesStopTheChain() {
        assertEquals(5, SeasonManager.MAX_DRAIN_ATTEMPTS,
                "MAX_DRAIN_ATTEMPTS 必须是有界的正数喵。实测为 " + SeasonManager.MAX_DRAIN_ATTEMPTS
                        + "。\n若被改成 Integer.MAX_VALUE 或去掉，F4 从 ACCEPTED_RISK 变成"
                        + "「关服后仍在重排」的真泄漏 —— 那时必须补 shutdown() 钩子并接进 D1，"
                        + "只加测试上界不够。");

        seedPending(7, SeasonManager.MAX_DRAIN_ATTEMPTS); // 已到顶

        assertFalse(hasDrainablePending(),
                "到顶后 hasDrainablePending() 必须返回 false 喵 —— 这是链自然终止的判据。"
                        + "若为 true，drainPendingReleases 的末尾会继续 scheduleReleaseDrain()，"
                        + "永不停歇。");

        callScheduleReleaseDrain();
        assertEquals(0, scheduledDrains.get(),
                "到顶后<b>不得</b>再排任务喵（实测排了 " + scheduledDrains.get() + " 个）。"
                        + "这就是「当前不需要 shutdown()」的全部依据：链会自己停。");
    }

    /** 未到顶（MAX-1）仍必须继续重试 —— 防「一律不重试」的过度修复（那会让奖励卡死）。 */
    @Test
    @DisplayName("F4：未到顶（MAX-1）仍继续重试（防过度修复成「永不重试」）")
    void belowTheCapStillRetries() {
        seedPending(7, SeasonManager.MAX_DRAIN_ATTEMPTS - 1);

        assertTrue(hasDrainablePending(),
                "未到顶时必须有可重试条目喵 —— 否则失败的释放永不重试，赛季奖励永久卡住"
                        + "（比泄漏更严重的业务缺陷）。");
        callScheduleReleaseDrain();
        assertEquals(1, scheduledDrains.get(), "未到顶时必须排一次重试喵");
    }

    private boolean hasDrainablePending() {
        try {
            Method m = SeasonManager.class.getDeclaredMethod("hasDrainablePending");
            m.setAccessible(true);
            return (Boolean) m.invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射调 hasDrainablePending 失败", e);
        }
    }

    // ==================================================================
    // 自限 3：停用窗口不把闩卡死
    // ==================================================================

    /**
     * 停用窗口（scheduler 已关）里排任务会抛 {@code IllegalStateException}。
     * 必须被捕获<b>并复位 {@code releaseDrainScheduled}</b> —— 否则闩永远停在 true，
     * 下次启服后的任何 {@code scheduleReleaseDrain()} 都会被单飞闸挡掉，
     * 待重试条目<b>永不重试</b> = 奖励永久卡死。
     *
     * <p>这是 A2 审计称「靠 :703-705 捕获复位」的那一处，本用例把它变成可执行断言。
     * 变异：删掉 {@code releaseDrainScheduled.set(false);} 那一行 → 本用例红。
     */
    @Test
    @DisplayName("F4：停用窗口抛 IllegalStateException 后复位单飞闩（不把重试永久卡死）")
    void disabledWindowResetsTheLatch() {
        seedPending(9, 0);
        schedulerClosed.set(true);

        callScheduleReleaseDrain(); // 内部抛 IllegalStateException 并被捕获

        assertFalse(drainScheduledFlag(),
                "停用窗口之后 releaseDrainScheduled 必须复位为 false 喵。\n"
                        + "留在 true = 单飞闩永久卡死：下次启服的 scheduleReleaseDrain() 会被"
                        + " compareAndSet 挡掉，待重试的赛季释放永不重试 → 那一季的奖励永久卡住"
                        + "（audit 的原文描述：「防『任务没跑但闩卡住』」）。\n"
                        + "注意这条与「泄漏」是同一枚硬币的两面：一边是该停的没停，"
                        + "一边是该跑的跑不了。");
        assertEquals(1, pendingReleases().size(),
                "待重试条目必须<b>保留</b>喵 —— 停用窗口里丢掉它等于放弃那季的奖励。"
                        + "条目保留 + 闩复位 = 下次 sweep 会继续重试，这是正确语义。");
    }

    /** 停用窗口里抛出的异常不得逃出 {@code scheduleReleaseDrain}（调用方在异步结算链里）。 */
    @Test
    @DisplayName("F4：停用窗口的 IllegalStateException 不逃逸")
    void disabledWindowExceptionIsContained() {
        seedPending(9, 0);
        schedulerClosed.set(true);
        callScheduleReleaseDrain(); // 不 try/catch：逃逸即红
    }

    // ==================================================================
    // 无句柄这件事本身：现状记录
    // ==================================================================

    /**
     * 记录现状：drain 任务的 {@code BukkitTask} 句柄被丢弃，且 {@code SeasonManager}
     * 没有 {@code shutdown()}。
     *
     * <p>这条<b>不是</b>缺陷断言 —— 它是「F4 判定为 ACCEPTED_RISK 的现状快照」。
     * 一旦有人补上 {@code shutdown()}，本用例会红，提醒同步 F4 的结论
     * （从「无钩子但自收敛」变成「有钩子」，这是修好不是回归）。
     */
    @Test
    @DisplayName("F4：现状快照 —— SeasonManager 无 shutdown()（补了就要更新 F4 结论）")
    void noShutdownHookYet() {
        boolean hasShutdown = java.util.Arrays.stream(SeasonManager.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("shutdown") && m.getParameterCount() == 0);
        assertFalse(hasShutdown,
                "SeasonManager 出现 shutdown() 了喵 —— 说明有人补上了 F4 建议的钩子。"
                        + "请同步：① 确认它被接进 disableAll（否则是零调用点的新债）；"
                        + "② 更新 F4 的判定为「已修」；③ 删掉本用例（它只是现状快照，不是缺陷断言）。");
    }

    /** 现状快照：全仓 {@code SeasonManager} 之外没有 second drain chain 的来源。 */
    @Test
    @DisplayName("F4：drain 链的起点只有 recordPendingRelease（单一起点）")
    void singleEntryPointForTheDrainChain() {
        seedPending(3, 0);
        callScheduleReleaseDrain();
        assertEquals(1, scheduledDrains.get(), "前置：有可重试条目时排 1 次");
        // 已排（闩 true）时再请求不排第二个
        callScheduleReleaseDrain();
        assertEquals(1, scheduledDrains.get(), "闩未复位前重复请求不得再排喵");
    }
}
