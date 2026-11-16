package com.letslearnenglish.minecraftplugin.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * Round 18：{@code RewardEngine} 日限记账链路的<b>线程归属</b>。
 *
 * <h2>被修的缺陷</h2>
 * {@code addTodayCoinsEarned} 的调用点全在玩家发币路径（主线程、Vault 转账之后），
 * 而它做两次同步 JDBC：{@code readDailyCoinsFromDb}（跨日/cache miss 时的 SELECT）
 * 与 {@code addDailyCoinsToDb}（每次都写的增量 UPSERT）。MySQL 远程库 +
 * {@code socketTimeout=30000} 时可以挂主线程 30 秒。同仓库
 * {@code AIChatManager.triggerAiOpening} 已因同一个理由把整段搬离主线程。
 *
 * <h2>本类的判断：只把<b>写</b>异步化，<b>读</b>保持同步</h2>
 * 判据不是「哪个更快」，而是<b>失败方向</b>：
 * <ul>
 *   <li><b>读必须同步</b>。它是 {@code clampToDailyCap} 发钱<b>之前</b>的判据输入。
 *       异步化或「超时按 0 降级」都会在「库里已用额度还没读回来」的窗口里把剩余额度
 *       当成满额 —— 方向是<b>多发钱</b>，不可回收。</li>
 *   <li><b>写可以异步</b>。调用点在 Vault 转账之后，且内存 {@code todayCoinsCache} 已在
 *       同一方法里先累加完。所以异步只推迟<b>持久化</b>，<b>本进程内</b>日限一秒都不会松
 *       （{@link #writeDeferredToAsyncStillEnforcesTheCapInProcess()} 钉住这一点）。
 *       最坏失败 = 崩服/reload 漏掉一小段已用额度 → 重启后日限偏松<b>一个发币额</b>。</li>
 * </ul>
 * 这与 {@code PlayerDataStore.incrementTodayAnswerCount}（Round 17：内存立即生效、
 * 落库异步）完全同构，不是本类新发明的口径。
 *
 * <h2>为什么 lint 那一组不能省</h2>
 * 「写异步、读同步」在单测里<b>几乎不可观测</b>：把 {@code persistDailyCoins} 改回
 * {@code addDailyCoinsToDb}（= 撤销本次修复），下面 4 个真库用例<b>全都仍然绿</b>
 * （同步退化路径本来就合法，且断言的是最终一致性）。这正是仓库里
 * {@code performance-optimizations-need-source-lint} 那条教训：纯线程归属的改动，
 * 只有<b>源码形状</b>能被 100% 确定地钉住。所以第一组用源码 lint（锚方法名、不锚行号、
 * 先剥注释）钉住「哪一个方法允许碰异步」，第二组用真 SQLite 钉住行为契约。
 *
 * <h2>封死的变异</h2>
 * <ul>
 *   <li>{@code addTodayCoinsEarned} 里的 {@code persistDailyCoins(...)} 改回
 *       {@code addDailyCoinsToDb(...)}（撤销修复）→
 *       {@link SourceShape#writeIsRoutedThroughTheSingleAsyncFaucet()} 与
 *       {@link SourceShape#onlyTheFaucetCallsRunTaskAsynchronously()} 双红；</li>
 *   <li>把读也包进 {@code scheduleAsync}（错误方向的"优化"）→
 *       {@link SourceShape#readPathStaysOnTheCallersThread()} 红，且
 *       {@link #readStaysSynchronousOnACacheMiss()} 红（实付从 100 变 400）；</li>
 *   <li>给 {@code readDailyCoinsFromDb} 加「超时按 0 降级」→ 同上两处红；</li>
 *   <li>异步派发不加同步退化（无调度器时静默丢弃）→
 *       {@link #noSchedulerFallsBackToSynchronousWrite()} 与
 *       {@link #schedulerRejectionDuringShutdownFallsBackToSynchronousWrite()} 红。</li>
 * </ul>
 *
 * <h2>本类刻意不覆盖</h2>
 * <p>{@code ensureDailyCoinsTable()} 在主线程做一次 {@code CREATE TABLE IF NOT EXISTS}
 * （进程内一次性，由 {@code AtomicBoolean} 闸住）。它也是一次 JDBC，但只在首次发币时发生、
 * 且 DDL 幂等；不纳入本类的红线（否则「首单慢一次」与「每单都慢」会被混为一谈）。
 */
@DisplayName("RewardEngine 日限记账的线程归属：读同步、写异步、退化路径完整")
class RewardEngineMainThreadIoTest {

    /** 被测源码。lint 断言的是<b>形状</b>，所以必须读真实文件，不能读 class。 */
    private static final Path ENGINE_SRC = Path.of(
            "src/main/java/com/letslearnenglish/minecraftplugin/core/progress/RewardEngine.java");

    /** 与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000018a1");

    /** 默认日限（金币）。 */
    private static final int CAP = 500;

    private static final Logger QUIET = quietLogger();

    /**
     * plugin mock 整类复用：{@code LetsLearnEnglish} 继承链很深，Mockito 造一个约十余秒
     * （与 {@code RewardEngineDailyCapTest} 的 {@code @BeforeAll} 同一条理由）。
     * 每用例只重打 {@code getDataFolder} / {@code getDatabaseManager} / {@code getServer}。
     */
    private static LetsLearnEnglish plugin;

    // ---- 每用例重建 -----------------------------------------------------------
    private FileConfiguration config;
    private DatabaseManager databaseManager;
    private RewardEngine engine;
    private Player player;
    private Economy economy;

    /** Vault 侧真实入账（分）。 */
    private final AtomicLong vaultCents = new AtomicLong();

    /**
     * 被投递到「异步线程」的任务队列。<b>不</b>自动执行 —— 「已投递但还没跑」正是
     * 「内存已记、库未记」那个窗口的可控复现。
     */
    private final List<Runnable> pendingTasks = Collections.synchronizedList(new ArrayList<>());

    private Server server;
    private BukkitScheduler scheduler;

    @BeforeAll
    static void createSharedPluginMock() {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(QUIET);
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        vaultCents.set(0L);
        pendingTasks.clear();

        // 真 YamlConfiguration：默认值必须真的生效，否则日限被 mock 成 0（= 不限）。
        config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "reward-engine-main-thread-io.db");
        config.set("database.mysql.table-prefix", PREFIX);
        config.set("progress-system.rewards.types.money.enabled", true);
        config.set("money.daily-cap", CAP);
        config.set("word-system.daily-limit", 50);

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getMainConfig()).thenReturn(config);
        when(configManager.isRewardsEnabled()).thenReturn(true);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("MainThreadIoTester");

        MessageUtil messageUtil = mock(MessageUtil.class);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("MSG");

        PlayerDataStore dataStore = mock(PlayerDataStore.class);
        when(dataStore.getTodayAnswerCount(any(UUID.class))).thenReturn(0);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        stubVault();
        stubCapturingScheduler();

        // 真 SQLite：判据是「库里真的有没有那一行」，mock 掉就等于假设结论成立。
        databaseManager = new DatabaseManager(plugin, configManager);
        databaseManager.initialize();
        assertTrue(databaseManager.isSQLite(), "前置条件：必须走 SQLite 分支");
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);

        engine = new RewardEngine(plugin);
    }

    @AfterEach
    void tearDown() {
        pendingTasks.clear();
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    // ==================================================================
    // 1. 源码 lint：允许碰异步的方法只有 scheduleAsync 一个
    // ==================================================================

    /**
     * 源码形状（不是行为）。锚<b>方法名</b>与<b>返回类型</b>，不锚行号 ——
     * 本类刚被多轮改动过（{@code transactionSucceeded} 收口、天花板、惩罚校验），
     * 行号漂移是常态。注释先剥掉，否则本类的 javadoc 里那些方法名会把断言变成自证。
     */
    @Nested
    @DisplayName("源码 lint：异步只有一个出口、读路径不碰异步、写只经唯一派发口")
    class SourceShape {

        @Test
        @DisplayName("addTodayCoinsEarned 经 persistDailyCoins 派发，不再直连 addDailyCoinsToDb")
        void writeIsRoutedThroughTheSingleAsyncFaucet() throws IOException {
            String code = strippedEngineSource();

            String earner = methodBody(code, "void", "addTodayCoinsEarned");
            assertNotNull(earner, "应能定位 addTodayCoinsEarned 的方法体（锚方法名，不锚行号）");
            assertTrue(earner.contains("persistDailyCoins("),
                    "addTodayCoinsEarned 必须经 persistDailyCoins 派发落库（本次修复的落点）；"
                            + "实际方法体：\n" + earner);
            assertTrue(!earner.contains("addDailyCoinsToDb("),
                    "addTodayCoinsEarned 不得再直连 addDailyCoinsToDb —— 那等于把这条 IO 留在"
                            + "主线程（撤销 Round 18 修复）。实际方法体：\n" + earner);
        }

        @Test
        @DisplayName("addDailyCoinsToDb 的调用点只有 persistDailyCoins 内的两处（唯一派发口）")
        void onlyTheFaucetCallsTheWriter() throws IOException {
            String code = strippedEngineSource();
            String faucet = methodBody(code, "void", "persistDailyCoins");
            assertNotNull(faucet, "应能定位 persistDailyCoins 的方法体");

            // 两处：① 投递给异步的 lambda；② 调度器不可用时的同步退化。
            assertEquals(2, countOccurrences(faucet, "addDailyCoinsToDb("),
                    "persistDailyCoins 内应有且只有两处 addDailyCoinsToDb（异步 + 同步退化），"
                            + "少一处说明退化路径被删了。实际方法体：\n" + faucet);

            // 剥掉整个派发口，以及被派发方法自身的「声明 + 方法体」——
            // 三处都会各自贡献一个 "addDailyCoinsToDb(" 字面量，漏剥任何一处这条断言就被自证满足。
            String writer = methodBody(code, "void", "addDailyCoinsToDb");
            assertNotNull(writer, "应能定位 addDailyCoinsToDb 的方法体（它是被派发的落库实现）");

            String outside = code.replace(faucet, " ").replace(writer, " ")
                    .replaceAll("void\\s+addDailyCoinsToDb\\s*\\(", " ");
            assertEquals(0, countOccurrences(outside, "addDailyCoinsToDb("),
                    "persistDailyCoins 之外不得再有 addDailyCoinsToDb 调用点（声明与派发口已一并剥除）"
                            + "——多一处就说明有主线程直连的漏网之鱼。多出来的位置："
                            + describeOccurrences(outside, "addDailyCoinsToDb("));
        }

        @Test
        @DisplayName("runTaskAsynchronously 只在 scheduleAsync 内出现一次")
        void onlyTheFaucetCallsRunTaskAsynchronously() throws IOException {
            String code = strippedEngineSource();
            String dispatch = methodBody(code, "boolean", "scheduleAsync");
            assertNotNull(dispatch, "应能定位 scheduleAsync 的方法体");
            assertEquals(1, countOccurrences(dispatch, "runTaskAsynchronously"),
                    "scheduleAsync 内应只有一处 runTaskAsynchronously。实际：\n" + dispatch);

            String outside = code.replace(dispatch, " ");
            assertEquals(0, countOccurrences(outside, "runTaskAsynchronously"),
                    "scheduleAsync 之外不得再出现 runTaskAsynchronously（否则这个 lint 只能覆盖一半"
                            + "主线程 IO）。位置：" + describeOccurrences(outside, "runTaskAsynchronously"));
        }

        @Test
        @DisplayName("读路径（readDailyCoinsFromDb / 两个调用方 / checkDailyCaps）不碰异步")
        void readPathStaysOnTheCallersThread() throws IOException {
            String code = strippedEngineSource();

            String reader = methodBody(code, "long", "readDailyCoinsFromDb");
            assertNotNull(reader, "应能定位 readDailyCoinsFromDb 的方法体");
            assertTrue(reader.contains("withConnection"),
                    "readDailyCoinsFromDb 必须自己（在调用线程上）执行那次 SELECT —— "
                            + "「读失败或读不到」的方向是多发钱，不允许异步。实际：\n" + reader);
            assertEquals(0, countOccurrences(reader, "runTaskAsynchronously")
                            + countOccurrences(reader, "scheduleAsync"),
                    "readDailyCoinsFromDb 不得自己派发异步（读必须同步）。实际：\n" + reader);

            for (String[] anchor : new String[][] {
                    {"long", "getTodayCoinsEarnedCents"},
                    {"void", "addTodayCoinsEarned"},
                    {"boolean", "checkDailyCaps"}}) {
                String body = methodBody(code, anchor[0], anchor[1]);
                assertNotNull(body, "应能定位 " + anchor[1] + " 的方法体");
                assertEquals(0, countOccurrences(body, "scheduleAsync(")
                                + countOccurrences(body, "runTaskAsynchronously"),
                        anchor[1] + " 不得把读派发到异步线程 —— 发钱判据必须读自己写、"
                                + "且必须在返回前拿到真值。实际方法体：\n" + body);
            }

            String caps = methodBody(code, "boolean", "checkDailyCaps");
            assertTrue(caps.contains("getTodayCoinsEarnedCents("),
                    "checkDailyCaps 必须直接（同步地）读今日已用额度。实际：\n" + caps);
        }

        @Test
        @DisplayName("RewardEngine 不持 connectionLock、不裸取 Connection（锁归 data 层）")
        void lockStaysInsideDataLayer() throws IOException {
            String code = strippedEngineSource();
            assertEquals(0, countOccurrences(code, "getConnectionLock()"),
                    "RewardEngine 在 data 层之外，必须经 withConnection 走；"
                            + "自己取锁会与 R6 的迁移约定冲突");
            assertEquals(0, countOccurrences(code, "getConnection()"),
                    "RewardEngine 不得裸取 Connection（锁外持有引用跨越重连不安全）");
            assertTrue(countOccurrences(code, "withConnection(") >= 3,
                    "三处 DB 访问（建表 / 读 / 写）都必须经 withConnection，实际次数："
                            + countOccurrences(code, "withConnection("));
        }
    }

    // ==================================================================
    // 2. 写侧异步：投递未执行时日限照样拦，跑完后库收敛
    // ==================================================================

    @Test
    @DisplayName("写已投递但未执行时，日限照拦（内存判决，异步不放松额度）")
    void writeDeferredToAsyncStillEnforcesTheCapInProcess() {
        assertEquals(400.0, engine.depositCappedReward(player, 400.0), 1e-9,
                "第一笔应全额发放");

        // 关键断言：落库任务还躺在队列里（= 「内存已加、库未加」的窗口）。
        assertEquals(1, pendingTasks.size(),
                "第一笔发币应投递恰好一次异步落库，实际队列：" + pendingTasks.size());
        assertEquals(0L, dbDailyCents(),
                "前置：任务未跑，库里此刻不应有额度（这正是被异步化的那个窗口）");

        // 窗口内再发一笔：判据读内存，必须仍看到已用 400 → 只给 100。
        double second = engine.depositCappedReward(player, 400.0);
        assertEquals(100.0, second, 1e-9,
                "异步落库不得放松本进程内的日限 —— 实际实付 " + second
                        + "（若是 400，说明写异步把判据也带去异步了）");
        assertEquals(50000L, vaultCents.get(), "Vault 合计必须正好 500.00，不得超发一分");
        assertEquals(2, pendingTasks.size(), "第二笔也应各投递一次落库");

        assertEquals(0.0, engine.depositCappedReward(player, 10.0), 1e-9,
                "额度耗尽后（仍在未落库窗口内）必须一分不发");
        assertEquals(50000L, vaultCents.get(), "余额不得再涨");

        // 跑完队列 → 库收敛到 500.00。
        drainPendingTasks();
        assertEquals(50000L, dbDailyCents(),
                "异步落库跑完后 daily_coin_counters 必须收敛到 500.00（两次增量之和），实际："
                        + dbDailyCents());
    }

    @Test
    @DisplayName("多笔增量乱序执行也收敛（cents = cents + ? 可交换）")
    void concurrentAsyncWritesConverge() throws InterruptedException {
        // 三个入口各发一笔，全部投递到队列。
        assertEquals(100.0, engine.depositCappedReward(player, 100.0), 1e-9);
        assertEquals(100.0, engine.depositCappedReward(player, 100.0), 1e-9);
        assertEquals(100.0, engine.depositCappedReward(player, 100.0), 1e-9);
        assertEquals(3, pendingTasks.size(), "前置：三次发币各投递一次落库");
        assertEquals(0L, dbDailyCents(), "前置：队列未跑，库里仍无额度");

        // 三条同时在各自线程上跑（顺序不可控），库侧必须仍等于 300.00。
        List<Thread> threads = new ArrayList<>();
        List<Runnable> batch;
        synchronized (pendingTasks) {
            batch = new ArrayList<>(pendingTasks);
            pendingTasks.clear();
        }
        for (Runnable task : batch) {
            Thread t = new Thread(task, "reward-io-test-writer");
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(30_000L);
        }

        assertEquals(30000L, dbDailyCents(),
                "增量 UPSERT 可交换，乱序并发执行后库侧仍须恰好 300.00，实际：" + dbDailyCents());
    }

    // ==================================================================
    // 3. 读侧同步：cache miss 必须当场读回库值（否则日限失守）
    // ==================================================================

    @Test
    @DisplayName("cache miss（reload 后）时读必须同步：当场按库值钳位，不依赖任何任务执行")
    void readStaysSynchronousOnACacheMiss() {
        // 建账：把 400.00 真的写进库。
        assertEquals(400.0, engine.depositCappedReward(player, 400.0), 1e-9);
        drainPendingTasks();
        assertEquals(40000L, dbDailyCents(), "前置：库里应有 400.00");

        // /lea reload 语义：只清内存，库仍是真源。
        engine.resetDailyCoinsCache();

        // 再发 400：必须<b>当场</b>读到库里的 400，只给 100。
        // 若读被异步化/超时按 0 降级，这里会拿到 400（= 日限被 reload 绕过）。
        double paid = engine.depositCappedReward(player, 400.0);
        assertEquals(100.0, paid, 1e-9,
                "cache miss 时的读必须同步 —— 实际实付 " + paid
                        + "（400 说明读被挪去异步或降级成 0 了）");
        assertEquals(50000L, vaultCents.get(), "Vault 合计必须正好 500.00");

        drainPendingTasks();
        assertEquals(50000L, dbDailyCents(), "落库后库值应为 500.00，实际：" + dbDailyCents());
    }

    @Test
    @DisplayName("无调度器（DI 未就绪 / 纯单测）：落库就地同步，语义与改动前逐字一致")
    void noSchedulerFallsBackToSynchronousWrite() {
        when(plugin.getServer()).thenReturn(null);

        assertEquals(400.0, engine.depositCappedReward(player, 400.0), 1e-9);

        assertTrue(pendingTasks.isEmpty(), "无调度器时不应投递任何异步任务");
        assertEquals(40000L, dbDailyCents(),
                "无调度器时必须就地同步落库（这是既有 20 个日限用例保持绿的前提），实际："
                        + dbDailyCents());
    }

    @Test
    @DisplayName("关服窗口调度器拒收：退回同步尽力写一次，不静默丢额度")
    void schedulerRejectionDuringShutdownFallsBackToSynchronousWrite() {
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenThrow(new IllegalStateException("Plugin attempted to register task while disabled"));

        assertEquals(400.0, engine.depositCappedReward(player, 400.0), 1e-9);

        assertEquals(40000L, dbDailyCents(),
                "调度器拒收时必须退回同步写一次 —— 静默丢弃会让重启后日限偏松，实际："
                        + dbDailyCents());
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private void stubCapturingScheduler() {
        scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    pendingTasks.add(inv.getArgument(1));
                    return null; // 代码不消费返回值
                });
        server = mock(Server.class);
        when(server.getScheduler()).thenReturn(scheduler);
        // JavaPlugin#getServer 是 final；Mockito 5 默认 inline mock maker 支持打桩
        // （本仓库已在既有用例里对同为 final 的 getLogger 做过同样的事）。
        when(plugin.getServer()).thenReturn(server);
    }

    /** 逐条在独立线程上执行队列里的落库任务并 join，然后清空。 */
    private void drainPendingTasks() {
        List<Runnable> batch;
        synchronized (pendingTasks) {
            batch = new ArrayList<>(pendingTasks);
            pendingTasks.clear();
        }
        List<Thread> threads = new ArrayList<>();
        for (Runnable task : batch) {
            Thread t = new Thread(task, "reward-io-test-drain");
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try {
                t.join(30_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("落库任务 join 被中断", e);
            }
        }
    }

    /** 今日金币计数（分），读真库。表未建 / 行不存在都算 0（与既有用例同口径）。 */
    private long dbDailyCents() {
        long[] out = {0L};
        databaseManager.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT cents FROM " + PREFIX
                            + "daily_coin_counters WHERE player_uuid = ? AND coin_date = ?")) {
                stmt.setString(1, PLAYER_ID.toString());
                stmt.setString(2, LocalDate.now().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    out[0] = rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (java.sql.SQLException ignored) {
                out[0] = 0L; // 表尚未懒建 = 今日零占用
            }
            return null;
        });
        return out[0];
    }

    private void stubVault() {
        economy = mock(Economy.class);
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(1);
            long balance = vaultCents.addAndGet(Math.round(amount * 100.0));
            return new EconomyResponse(amount, balance / 100.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(economy.format(anyDouble())).thenAnswer(inv -> String.format(java.util.Locale.ROOT,
                "%.2f", inv.<Double>getArgument(0)));
        when(plugin.getEconomy()).thenReturn(economy);
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("RewardEngineMainThreadIoTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        return logger;
    }

    // ---- 源码读/剥注释/取方法体（lint 专用） --------------------------------

    private static String strippedEngineSource() throws IOException {
        assertTrue(Files.isRegularFile(ENGINE_SRC),
                "必须能读到 RewardEngine 源码（工作目录应为项目根）: "
                        + ENGINE_SRC.toAbsolutePath());
        return stripComments(Files.readString(ENGINE_SRC));
    }

    /**
     * 剥掉 {@code //} 与 {@code /* *}{@code /} 注释，字符串字面量原样保留。
     *
     * <p><b>为什么必须先剥</b>：本类的方法 javadoc 里逐字写着
     * {@code addDailyCoinsToDb} / {@code scheduleAsync} / {@code readDailyCoinsFromDb}
     * 这些名字（正是本次改动留下的说明）。不剥注释，任何 footprint 断言都会被
     * <b>它自己的注释</b>满足 —— 那就是自证桩，变异永远变不红。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == c) {
                        j++;
                        break;
                    }
                    j++;
                }
                out.append(src, i, Math.min(j, n));
                i = Math.min(j, n);
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' '); // 保留分隔，避免把前后 token 粘成一个
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 按「返回类型 + 方法名」定位方法体（含花括号）。
     *
     * <p>锚返回类型是为了<b>只匹配声明、不匹配调用点</b>：
     * {@code addDailyCoinsToDb(playerId, today, cents);} 前面没有 {@code void}，
     * 所以不会被误当成声明。这样断言不依赖行号，重构后仍成立。
     *
     * @return 方法体（从 {@code {} 到配平的 {@code }}）；找不到返回 null
     */
    private static String methodBody(String code, String returnType, String name) {
        Pattern p = Pattern.compile(
                "\\b" + Pattern.quote(returnType) + "\\s+" + Pattern.quote(name) + "\\s*\\(");
        Matcher m = p.matcher(code);
        if (!m.find()) {
            return null;
        }
        int open = code.indexOf('{', m.end());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    /** 失败信息里带上偏移量，定位不靠猜。 */
    private static String describeOccurrences(String haystack, String needle) {
        List<Integer> offsets = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                break;
            }
            offsets.add(idx);
            from = idx + needle.length();
        }
        return offsets.toString();
    }
}
