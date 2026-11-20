package com.letslearnenglish.minecraftplugin.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
 * {@code reserveDailyCoinsInDb} 的 <b>CAS 乐观锁本体</b>：落败必须真重试、耗尽必须 fail-closed。
 *
 * <h2>为什么需要这个类（它是本仓库「假绿」的一个确证案例）</h2>
 * {@code DailyCapCrossServerTest} 已经把跨服日限的<b>结论</b>钉住了，但它钉住的是
 * 「SELECT 读到最新值 + {@code remaining <= 0} 早退」，<b>不是 CAS</b>。实测证据：
 * <ul>
 *   <li>把 {@code reserveDailyCoinsInDb} 的 {@code UPDATE ... AND cents = ?} 里
 *       {@code AND cents = ?} 整段删掉（退化成无条件 UPDATE）、同步删掉
 *       {@code upd.setLong(4, used)} —— 既有 29 个用例<b>全绿</b>；</li>
 *   <li>{@code RESERVE_CAS_ATTEMPTS} 从 {@code 4} 改成 {@code 1} —— 既有 29 个用例
 *       <b>全绿</b>；</li>
 *   <li>对照组（把 {@code MAX_SINGLE_PAYOUT_CENTS} 改成 {@code Long.MAX_VALUE}）恰好 3 红
 *       —— 说明变异流程本身是有效的，红的缺失是<b>覆盖缺口</b>而不是变异没生效。</li>
 * </ul>
 * 根因：跨服用例里「B 落败」那一步，B 重新 SELECT 就读到 A 写满的值，
 * {@code remaining = 0}，<b>UPDATE 根本没被发出去</b>。竞争窗口（"读到一个值"与
 * "在那个值上做加法"之间）从来没有被造出来过。全仓 {@code core/progress/} 下
 * {@code CyclicBarrier} / {@code Phaser} / {@code CountDownLatch} 零命中，也印证了这一点。
 *
 * <h2>本类怎么造出那个窗口（确定性，不是靠时序）</h2>
 * 真竞争窗口是：<b>本进程 SELECT 读到 used → 另一个写者改了库 → 本进程发出 UPDATE</b>。
 * 三种造法里我们选第三种：
 * <ol>
 *   <li><b>两个线程 + 屏障</b> —— 依赖调度；SQLite 的文件锁还可能把两个连接串行化，
 *       窗口未必存在（<b>要实测不要假设</b>，本类因此不走这条）；</li>
 *   <li><b>在 RewardEngine 里加一个 {@code afterSelectHook} 测试缝</b> —— 能工作，但要动生产代码；
 *       本类找到了更外的插入点，所以没有采用；</li>
 *   <li><b>包一层 DatabaseManager（本类的做法）</b>：{@link DatabaseManager} 是非 final 的
 *       public class，{@code withConnection} 是 public 且非 final，因此测试可以用
 *       {@link HookDatabaseManager} 继承并覆写它，再把传给业务 lambda 的 {@link Connection}
 *       换成 {@link Proxy}：{@code prepareStatement(sql)} 看得见 SQL 文本，
 *       于是可以精确地在「reserve 的 SELECT 的 {@link ResultSet#close()} 之后」执行一次
 *       <b>另一个连接</b>的写入。这是<b>真实发生</b>的第二次写（真 SQLite 文件、真第二条连接），
 *       不是 mock 出来的假象，而且时序完全确定。</li>
 * </ol>
 * <b>生产代码零改动</b>：钩子活在测试类里，{@code RewardEngine} / {@code DatabaseManager}
 * 一个字符都没变，也就不存在"生产路径多一个分支"的风险。
 *
 * <h2>本类杀掉的两个变异（父任务的核心要求）</h2>
 * <ul>
 *   <li><b>{@code AND cents = ?} 被删</b>（退化成无条件 UPDATE）：
 *       {@link #casLossRetriesAndConverges()} 的 {@code paid == 50.0} 会变成 {@code 200.0}、
 *       库值会从 50000 变成 65000（<b>越过 cap</b>），并且 {@code casUpdates == 2} 会变成 1；
 *       {@link #casExhaustedIsFailClosedAndNeverTouchesVault()} 的 {@code casUpdates == 4}
 *       会变成 1。<b>两组用例都会红</b>。</li>
 *   <li><b>{@code RESERVE_CAS_ATTEMPTS} 4 → 1</b>：
 *       {@link #casLossRetriesAndConverges()} 会退化成"第一次落败就放弃"（paid 变 0、库值停在 45000），
 *       {@link #casExhaustedIsFailClosedAndNeverTouchesVault()} 的 {@code casUpdates == 4} 变 1。
 *       <b>两组都会红</b>。</li>
 *   <li>SQL 形状另由 {@link SqlShapeLint} 从源码层面钉住（{@code = ?} 不得被改成 {@code >= ?}）——
 *       行为测试抓不到"恰好没撞上竞争时 {@code >=} 与 {@code =} 结果一样"。</li>
 * </ul>
 *
 * <h2>为什么"最终值正确"不足以证明重试发生过</h2>
 * 这是本类最容易被写成假绿的地方：只断言最终库值/返回值时，<b>退化后的实现往往也给出正确终值</b>
 * （第一次 UPDATE 无条件成功，加的增量恰好等于正确增量）。所以本类<b>同时</b>断言
 * 「CAS UPDATE 语句被发出去几次」（{@code casUpdates}）与「reserve 的 SELECT 被执行几次」
 * （{@code reserveSelects}）—— 这两个计数只能由"真的走了两轮"满足。
 */
@DisplayName("daily-cap 的库侧 CAS：落败真重试、耗尽 fail-closed、UPDATE 必须条件化")
class DailyCapCasRetryTest {

    /** 被测源码：SQL 形状 lint 必须读真实文件（读 class 看不到 SQL 字面量）。 */
    private static final Path ENGINE_SRC = Path.of(
            "src/main/java/com/letslearnenglish/minecraftplugin/core/progress/RewardEngine.java");

    /** 与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000ca5");

    /** 默认日限（金币）= 50000 分。 */
    private static final int CAP = 500;

    private static final long CAP_CENTS = (long) CAP * 100;

    /**
     * 生产常量 {@code RESERVE_CAS_ATTEMPTS} 的<b>字面值</b>，刻意硬编码。
     *
     * <p><b>为什么不反射读那个常量</b>：反射读出来的值会跟着变异一起变，"4 改成 1"的变异体
     * 依然满足 {@code attempts == RESERVE_CAS_ATTEMPTS}，于是本类全绿 —— 那正是父任务
     * 实测出来的假绿。硬编码 4 是刻意的 tripwire：谁要改这个数字，必须同时改这里并说明理由。
     * 它同时是「重试确实发生过」的唯一可观测证据（最终值对说明不了重试发生过）。
     */
    private static final int EXPECTED_CAS_ATTEMPTS = 4;

    // plugin mock 整类复用（LetsLearnEnglish 继承链深，Mockito 造一个约十余秒）。
    private static LetsLearnEnglish plugin;
    private static Logger logger;
    private static CapturingHandler handler;

    private FileConfiguration config;
    private ConfigManager configManager;

    /** 「本服」连接：被 {@link HookDatabaseManager} 包了一层，用来在窗口里注入第二方写入。 */
    private HookDatabaseManager dbA;

    /** 「另一台服」连接：钩子用它把库改掉 —— 这是真实的第二个写者，不是 mock。 */
    private DatabaseManager dbB;

    private RewardEngine engine;
    private Player player;

    private final AtomicLong vaultCents = new AtomicLong();
    private final AtomicInteger depositCalls = new AtomicInteger();

    @BeforeAll
    static void createSharedPluginMock() {
        plugin = mock(LetsLearnEnglish.class);
        logger = Logger.getLogger("DailyCapCasRetryTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);
        when(plugin.getLogger()).thenReturn(logger);
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        handler.clear();
        vaultCents.set(0L);
        depositCalls.set(0);

        // 真 YamlConfiguration：money.daily-cap 必须真的生效，否则日限被 mock 成 0（= 不限），
        // 整个类一条 CAS 都跑不到。
        config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "daily-cap-cas-retry.db");
        config.set("database.mysql.table-prefix", PREFIX);
        config.set("progress-system.rewards.types.money.enabled", true);
        config.set("money.daily-cap", CAP);
        config.set("word-system.daily-limit", 50);

        configManager = mock(ConfigManager.class);
        when(configManager.getMainConfig()).thenReturn(config);
        when(configManager.isRewardsEnabled()).thenReturn(true);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("CasRetryTester");

        MessageUtil messageUtil = mock(MessageUtil.class);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("MSG");

        PlayerDataStore dataStore = mock(PlayerDataStore.class);
        when(dataStore.getTodayAnswerCount(any(UUID.class))).thenReturn(0);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        stubVaultTrackingEconomy();

        // 两个 DatabaseManager 指向同一个 SQLite 文件 = 「两台服共享同一个库」。
        // dbA 是「本服」，包了钩子；dbB 是「他服」，钩子用它制造真实竞争。
        dbA = new HookDatabaseManager(plugin, configManager);
        dbA.initialize();
        when(plugin.getDatabaseManager()).thenReturn(dbA);

        dbB = new DatabaseManager(plugin, configManager);
        dbB.initialize();

        engine = new RewardEngine(plugin);
        // 生产判据是 isSQLite() 的反；本类跑在 SQLite 上，必须强制走多进程路径，
        // 否则 reserveDailyCoinsInDb 一行都不会执行（摘掉 CAS 也全绿 —— 假绿）。
        engine.setSharedDailyLedger(true);
    }

    @AfterEach
    void tearDown() {
        if (dbA != null) {
            dbA.shutdown();
        }
        if (dbB != null) {
            dbB.shutdown();
        }
    }

    // ==================================================================
    // 1. CAS 落败 → 重读 → 收敛
    // ==================================================================

    /**
     * <b>CAS 落败后必须重试并收敛</b>：本进程 SELECT 读到 {@code used=0}，钩子在
     * SELECT 关闭之后、UPDATE 发出之前把库改成 {@code 45000}（模拟另一台服刚预占了 450）。
     *
     * <h2>期望时间线（cap = 50000 分）</h2>
     * <table border="1">
     *   <caption>两轮 CAS</caption>
     *   <tr><th>轮次</th><th>SELECT 读到</th><th>钩子写入</th><th>UPDATE</th><th>结果</th></tr>
     *   <tr><td>1</td><td>0</td><td>45000</td>
     *       <td>{@code WHERE cents = 0} → affected 0</td><td>落败，重试</td></tr>
     *   <tr><td>2</td><td>45000</td><td>—</td>
     *       <td>{@code WHERE cents = 45000} → affected 1</td><td>放行 5000 分 = 50.00</td></tr>
     * </table>
     * 终值：库 = 50000（恰好 cap）、Vault = 50.00。
     *
     * <p><b>变异判别（这是本类存在的理由）</b>：
     * <ul>
     *   <li>删掉 {@code AND cents = ?}：第一轮 UPDATE 无条件成功，增量按陈旧值算成 20000，
     *       库变 65000（<b>越过 cap，超发 150 金币</b>）、实付 200.00，且 UPDATE 只发 1 次
     *       —— {@code paid}、库值、{@code casUpdates} 三条断言同时红；</li>
     *   <li>{@code RESERVE_CAS_ATTEMPTS} 4→1：第一轮落败即放弃，实付 0.0、库停在 45000
     *       —— {@code paid} 与库值断言红。</li>
     * </ul>
     */
    @Test
    @DisplayName("CAS 落败：第二条服写入后本轮 UPDATE 失败，重读后第二轮收敛（UPDATE 必须发两次）")
    void casLossRetriesAndConverges() {
        // 钩子只在第 1 次 reserve-SELECT 之后动手 —— 第二轮必须能看到干净的最新值。
        dbA.afterReserveSelect = n -> {
            if (n == 1) {
                // 45000 分已用 → 剩余 5000 分。若实现退化成无条件 UPDATE，这一笔会按
                // 陈旧的"剩余 50000"算出 20000 增量，库变 65000（超发）。
                writeLedgerViaOtherServer(45000L);
            }
        };

        double paid = engine.depositCappedReward(player, 200.0);

        assertEquals(50.0, paid, 1e-9,
                "第二轮必须只放行剩余额度 50.00 喵。拿到 200.00 说明第一轮的无条件 UPDATE "
                        + "在陈旧读数上放了行（CAS 条件丢了 = 跨服超发）；拿到 0.00 说明"
                        + "第一次落败后就放弃了（重试消失，跨服连点会被误判成额度已满）");
        assertEquals(CAP_CENTS - 45000L, vaultCents.get(),
                "Vault 侧实际入账必须恰好是剩余额度 50.00（5000 分）喵");
        assertEquals(CAP_CENTS, dbDailyCentsViaOtherServer(),
                "库里的今日已用必须恰好等于 cap（45000 他服 + 5000 本次）喵。"
                        + "停在 65000 = 两笔都按陈旧的'剩余 50000'算，日限被跨服超发；"
                        + "停在 45000 = 本次预占没落库（玩家白拿钱且额度没扣）");

        assertEquals(2, dbA.reserveSelects.get(),
                "reserve 的 SELECT 必须被执行 2 次（含落败后重读）喵。"
                        + "实际 1 次说明落败后没有重读，而是直接放弃或直接当成功");
        assertEquals(2, dbA.casUpdates.get(),
                "条件 UPDATE 必须被发出去 2 次喵 —— 这是'重试真的发生了'的唯一可观测证据。"
                        + "最终值正确不能替代它：无条件 UPDATE 也可能给出正确终值");
    }

    // ==================================================================
    // 2. 重试耗尽 → fail-closed
    // ==================================================================

    /**
     * <b>重试耗尽必须 fail-closed</b>：钩子每次都改库，使 CAS 永远落败。
     *
     * <p>断言四件事：
     * <ol>
     *   <li>条件 UPDATE <b>恰好发了 {@value #EXPECTED_CAS_ATTEMPTS} 次</b>（次数断言 ——
     *       这是 {@code RESERVE_CAS_ATTEMPTS} 4→1 变异唯一抓得住的证据）；</li>
     *   <li>返回 0（一分不放，调用方据此不标记"已领"）；</li>
     *   <li><b>Vault 一次都没被调用</b>（不是"转了 0 元"）；</li>
     *   <li>打了点名 compare-and-swap 的 WARNING（这条路径罕见，不告警就没人知道玩家这段
     *       时间一直在被静默跳过）。</li>
     * </ol>
     *
     * <p>额外断言「库值 == 钩子累计写入量」：证明耗尽路径<b>没有</b>把自己算出来的增量
     * 偷偷落库（那会让账本出现一笔没付出去的额度）。
     */
    @Test
    @DisplayName("CAS 连败：恰好重试 4 次后返回 0、Vault 零调用、打 WARNING（fail-closed）")
    void casExhaustedIsFailClosedAndNeverTouchesVault() {
        AtomicLong injected = new AtomicLong();
        // 每次 reserve-SELECT 之后都 +1000 分，于是"读到的值"永远与"库里的值"不同，
        // CAS 永远落败。cap=50000 远大于 4×1000，所以不会提前撞上 remaining<=0 的早退
        // —— 早退会让"耗尽"变成"额度已满"，那样就测不到耗尽了。
        dbA.afterReserveSelect = n -> {
            long after = writeLedgerIncrementViaOtherServer(1000L);
            injected.set(after);
        };

        double paid = engine.depositCappedReward(player, 200.0);

        assertEquals(EXPECTED_CAS_ATTEMPTS, dbA.casUpdates.get(),
                "条件 UPDATE 必须恰好被发出去 " + EXPECTED_CAS_ATTEMPTS + " 次喵。"
                        + "实际更少说明重试上限被下调（RESERVE_CAS_ATTEMPTS 4→1 会让这里变 1，"
                        + "跨服连点时玩家被静默跳过的概率随之升高）");
        assertEquals(EXPECTED_CAS_ATTEMPTS + 1, dbA.reserveSelects.get(),
                "reserve 的 SELECT 应为 " + EXPECTED_CAS_ATTEMPTS + " 次重试 + 1 次"
                        + "「耗尽后为渲染 {earned} 再读一次」喵。数量不符说明重试轮数变了");
        assertEquals(0.0, paid, 1e-9,
                "CAS 连败必须 fail-closed：返回 0、一分不发喵。返回正数意味着"
                        + "「抢不到也算赢」，跨服日限立刻失守");
        assertEquals(0, depositCalls.get(),
                "CAS 连败时不得调用 Vault 一次（连 0 元转账都不该发生）喵");
        assertEquals(injected.get(), dbDailyCentsViaOtherServer(),
                "库值必须恰好等于「他服」写入的累计（当前 " + injected.get() + " 分）喵。"
                        + "多出来的部分说明耗尽路径把自己的增量落了库 —— 那笔额度没付出去"
                        + "却记在玩家账上，等于永久烧掉他的当日额度");
        assertTrue(handler.describe().contains("compare-and-swap"),
                "耗尽必须留一条点名 compare-and-swap 的 WARNING 喵（这条路径罕见，"
                        + "不告警就没人知道玩家一直被静默跳过）。实际日志：" + handler.describe());
    }

    // ==================================================================
    // 3. SQL 形状 lint（行为测试抓不到"恰好没撞上竞争"）
    // ==================================================================

    /**
     * <b>{@code UPDATE} 的形状必须是 CAS</b>：{@code WHERE ... AND cents = ?旧值}。
     *
     * <h2>为什么行为测试不够</h2>
     * {@code cents >= ?} 与 {@code cents = ?} 在<b>没撞上竞争</b>时结果完全一样；
     * 而 {@code >=} 的语义是"对方写过更大的值时也按我的旧读数放行"——那是超发。
     * 只有源码形状能 100% 确定地钉住它。这与 {@code DailyCapCrossServerTest.LedgerModeWiring}
     * / {@code RewardEngineMainThreadIoTest.SourceShape} 同一处置。
     */
    @Nested
    @DisplayName("源码 lint：日限预占的 UPDATE 必须是条件化 CAS")
    class SqlShapeLint {

        /**
         * 变异判别：
         * <ul>
         *   <li>删掉 {@code AND cents = ?} 与 {@code upd.setLong(4, used)} → 第一条与第三条断言红；</li>
         *   <li>{@code = ?} 改成 {@code >= ?} → 第一条与第四条断言红。</li>
         * </ul>
         */
        @Test
        @DisplayName("UPDATE 带 AND cents = ? 且参数取自 SELECT 结果；不得退化成 >= 或无条件")
        void reservationUpdateIsCompareAndSwapOnTheReadValue() throws Exception {
            String body = methodBody(strippedEngineSource(), "DailyCapReserve",
                    "reserveDailyCoinsInDb");
            assertNotNull(body,
                    "必须能定位 reserveDailyCoinsInDb 的方法体（锚方法名，不锚行号）喵 —— "
                            + "重命名会让本 lint 变成空转，所以定位失败必须红");

            assertTrue(body.contains("AND cents = ?"),
                    "日限预占的 UPDATE 必须带 `AND cents = ?`（等于 SELECT 读到的旧值）喵。"
                            + "缺了它就是无条件 UPDATE：两个进程各自按陈旧读数放行，"
                            + "跨服日限直接失守（本项目最初的缺陷形状）。实际方法体：\n" + body);
            assertFalse(body.contains("cents >="),
                    "CAS 条件不得用 `cents >=` 喵：那会在对方写过更大的值时静默按旧读数放行"
                            + "——正是超发。等值比较才能分辨「我算的那份剩余额度还有效」。"
                            + "实际方法体：\n" + body);
            assertTrue(body.contains("setLong(4, used)"),
                    "UPDATE 的第 4 个参数必须绑定 SELECT 读到的 used 喵。缺了它（或绑成别的）"
                            + "条件里的 `cents = ?` 就是个摆设，CAS 永远在比错的数");
            assertTrue(body.contains("executeUpdate() == 1"),
                    "必须用 affected rows == 1 判成败喵 —— affected rows == 0 才是「别人改过，重试」的"
                            + "唯一信号；忽略返回值就变成「抢不到也算赢」");
        }

        // ---- 源码读取 / 剥注释 / 取方法体（与 DailyCapCrossServerTest.LedgerModeWiring 同法） ----

        private String strippedEngineSource() throws Exception {
            assertTrue(Files.isRegularFile(ENGINE_SRC),
                    "必须能读到 RewardEngine 源码（工作目录应为项目根）: "
                            + ENGINE_SRC.toAbsolutePath());
            return stripComments(Files.readString(ENGINE_SRC));
        }

        /**
         * 剥掉 {@code //} 与 block 注释，字符串字面量原样保留。
         *
         * <p><b>必须先剥</b>：{@code reserveDailyCoinsInDb} 的 javadoc 里逐字写着
         * {@code AND cents = ?旧值} 与 {@code >=} 的对比论证 —— 不剥注释，上面每条断言
         * 都会被<b>它自己的注释</b>满足（本仓 MEMORY 记录的「假绿」形态之一）。
         */
        private String stripComments(String src) {
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
                    out.append(' ');
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }

        /** 按「返回类型 + 方法名」定位方法体（含花括号）。锚返回类型只匹配声明，不匹配调用点。 */
        private String methodBody(String code, String returnType, String name) {
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
    }

    // ==================================================================
    // 钩子：包一层 DatabaseManager，在 SELECT 之后、UPDATE 之前注入第二方写入
    // ==================================================================

    /**
     * {@link DatabaseManager} 的测试包装：把交给业务 lambda 的 {@link Connection} 换成
     * {@link Proxy}，从而看见每条 SQL，并在「reserve 的 SELECT 结果集关闭之后」执行一次外部回调。
     *
     * <h2>为什么这个窗口是对的</h2>
     * {@code reserveDailyCoinsInDb} 的形状是：
     * <pre>
     *   try (PreparedStatement sel = conn.prepareStatement(selectSql)) {
     *       try (ResultSet rs = sel.executeQuery()) { used = rs.getLong(1); }  // ← 这里关闭
     *   }
     *   ... remaining / delta 计算 ...
     *   try (PreparedStatement upd = conn.prepareStatement(updateSql)) { upd.executeUpdate(); }
     * </pre>
     * {@code rs.close()} 恰好落在「used 已经读进内存」与「UPDATE 已经发出」之间 ——
     * 这就是 CAS 要防的那个窗口本身。在此处执行另一个连接的写入，得到的竞争是<b>真实的</b>
     * （真文件、真第二条连接、真行级冲突），但发生时刻<b>完全确定</b>，不靠 {@code sleep}、
     * 不靠屏障、不靠调度。
     *
     * <h2>生产代码为什么零改动</h2>
     * 包装全部活在测试侧：{@code DatabaseManager.withConnection} 本来就是 {@code public} 且
     * 非 {@code final}，覆盖它不需要生产类提供任何缝。因此生产路径上没有多出任何分支，
     * 也就不存在"钩子为 null 时零开销"这种需要被验证的承诺。
     */
    static final class HookDatabaseManager extends DatabaseManager {

        /** reserve 的 SELECT 被执行的次数（含落败后的重读与耗尽后的展示用重读）。 */
        final AtomicInteger reserveSelects = new AtomicInteger();

        /** 条件 UPDATE（{@code SET cents = cents + ?}）被发出去的次数。 */
        final AtomicInteger casUpdates = new AtomicInteger();

        /** 每次 reserve-SELECT 的结果集关闭后回调，参数是第几次（从 1 开始）。默认什么都不做。 */
        volatile IntConsumer afterReserveSelect = n -> { };

        HookDatabaseManager(LetsLearnEnglish plugin, ConfigManager configManager) {
            super(plugin, configManager);
        }

        @Override
        public <T> T withConnection(SqlFunction<T> fn) {
            return withConnection(fn, null);
        }

        /**
         * 只在 {@code reserveDailyCoinsInDb} 的动态范围内包装连接。
         *
         * <p><b>为什么按调用栈而不是按 SQL 文本判归属</b>：
         * {@code readDailyCoinsFromDb} 发出的 {@code SELECT cents FROM <prefix>daily_coin_counters
         * WHERE player_uuid = ? AND coin_date = ?} 与 {@code reserveDailyCoinsInDb} 里的那条
         * <b>拼出来逐字节相同</b>（两处都只拼前缀 + 固定字面量）。靠 SQL 文本区分会把
         * {@code readDailyCoinsFromDb} 的读也算成一次 reserve 读数，于是计数值被别的代码路径
         * 满足 —— 正是本仓 MEMORY 记录的「计数被别路径满足」这一假绿形态。
         * 调用栈是<b>确定性</b>的归属判据：reserve 的 lambda 是同步执行的，栈帧必然在。
         */
        @Override
        public <T> T withConnection(SqlFunction<T> fn, T fallback) {
            if (fn == null) {
                return fallback;
            }
            if (!insideReserveDailyCoinsInDb()) {
                return super.withConnection(fn, fallback);
            }
            return super.withConnection(conn -> fn.apply(wrapConnection(conn)), fallback);
        }

        /** 当前线程的调用栈里是否有 {@code reserveDailyCoinsInDb} 的栈帧。 */
        private static boolean insideReserveDailyCoinsInDb() {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if ("reserveDailyCoinsInDb".equals(frame.getMethodName())) {
                    return true;
                }
            }
            return false;
        }

        private Connection wrapConnection(Connection real) {
            return (Connection) Proxy.newProxyInstance(
                    HookDatabaseManager.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = call(real, method, args);
                        if (result instanceof PreparedStatement
                                && "prepareStatement".equals(method.getName())
                                && args != null && args.length > 0
                                && args[0] instanceof String) {
                            return wrapStatement((PreparedStatement) result, (String) args[0]);
                        }
                        return result;
                    });
        }

        private PreparedStatement wrapStatement(PreparedStatement real, String sql) {
            boolean casUpdate = isCasUpdate(sql);
            boolean reserveSelect = isReserveSelect(sql);
            if (!casUpdate && !reserveSelect) {
                return real; // 与本类无关的语句原样透传（INSERT / DDL / 补偿 UPDATE …）
            }
            return (PreparedStatement) Proxy.newProxyInstance(
                    HookDatabaseManager.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        Object result = call(real, method, args);
                        if (casUpdate && "executeUpdate".equals(method.getName())) {
                            casUpdates.incrementAndGet();
                        }
                        if (reserveSelect && "executeQuery".equals(method.getName())
                                && result instanceof ResultSet) {
                            return wrapResultSet((ResultSet) result);
                        }
                        return result;
                    });
        }

        /**
         * 结果集代理：只在 {@code close()} 之后回调一次。
         *
         * <p>{@code fired} 幂等闸是必要的：{@code PreparedStatement.close()} 可能级联关闭
         * 结果集，若被代理看到第二次 {@code close} 就会注入两次，把"一次竞争"变成"两次"，
         * 断言里的次数就不再是确定值。
         */
        private ResultSet wrapResultSet(ResultSet real) {
            AtomicBoolean fired = new AtomicBoolean(false);
            return (ResultSet) Proxy.newProxyInstance(
                    HookDatabaseManager.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        Object result = call(real, method, args);
                        if ("close".equals(method.getName()) && fired.compareAndSet(false, true)) {
                            int n = reserveSelects.incrementAndGet();
                            afterReserveSelect.accept(n);
                        }
                        return result;
                    });
        }

        /** 条件 CAS 的 UPDATE：{@code UPDATE <prefix>daily_coin_counters SET cents = cents + ?}。 */
        private static boolean isCasUpdate(String sql) {
            return sql.startsWith("UPDATE ")
                    && sql.contains("daily_coin_counters")
                    && sql.contains("SET cents = cents +");
        }

        /** reserve 的读数 SELECT：{@code SELECT cents FROM <prefix>daily_coin_counters}。 */
        private static boolean isReserveSelect(String sql) {
            return sql.startsWith("SELECT cents FROM")
                    && sql.contains("daily_coin_counters");
        }

        private static Object call(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 「另一台服」把该玩家的今日已用<b>覆盖</b>成绝对值（模拟他服预占完成后写满/推进）。 */
    private void writeLedgerViaOtherServer(long cents) {
        dbB.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE " + PREFIX + "daily_coin_counters SET cents = ?"
                            + " WHERE player_uuid = ? AND coin_date = ?")) {
                stmt.setLong(1, cents);
                stmt.setString(2, PLAYER_ID.toString());
                stmt.setString(3, LocalDate.now().toString());
                stmt.executeUpdate();
            }
            return null;
        });
    }

    /** 「另一台服」把今日已用<b>增量</b>推进，返回推进后的绝对值。 */
    private long writeLedgerIncrementViaOtherServer(long deltaCents) {
        AtomicLong after = new AtomicLong();
        dbB.withConnection(conn -> {
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE " + PREFIX + "daily_coin_counters SET cents = cents + ?"
                            + " WHERE player_uuid = ? AND coin_date = ?")) {
                upd.setLong(1, deltaCents);
                upd.setString(2, PLAYER_ID.toString());
                upd.setString(3, LocalDate.now().toString());
                upd.executeUpdate();
            }
            try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT cents FROM " + PREFIX
                            + "daily_coin_counters WHERE player_uuid = ? AND coin_date = ?")) {
                sel.setString(1, PLAYER_ID.toString());
                sel.setString(2, LocalDate.now().toString());
                try (ResultSet rs = sel.executeQuery()) {
                    after.set(rs.next() ? rs.getLong(1) : 0L);
                }
            }
            return null;
        });
        return after.get();
    }

    /**
     * 今日金币计数（分），通过<b>「另一台服」的连接</b>读真库真源。
     *
     * <p>刻意<b>不</b>用 {@code dbA}：{@code dbA} 上的 {@code SELECT cents FROM ...}
     * 会被钩子当成一次 reserve 读数而再次注入写入，断言就成了自己污染自己。
     */
    private long dbDailyCentsViaOtherServer() {
        long[] out = {0L};
        dbB.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT cents FROM " + PREFIX
                            + "daily_coin_counters WHERE player_uuid = ? AND coin_date = ?")) {
                stmt.setString(1, PLAYER_ID.toString());
                stmt.setString(2, LocalDate.now().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    out[0] = rs.next() ? rs.getLong(1) : 0L;
                }
            }
            return null;
        });
        return out[0];
    }

    /**
     * 真记账的 Vault 桩（照 {@code RewardEngineDailyCapTest} 的桩法）。
     *
     * <p>维护余额并返回带真实余额的 {@link EconomyResponse}；不用
     * {@code thenReturn(固定响应)} —— 那样只能证明"调用发生过"，证明不了"转了多少"，
     * 而本类全部判据都是金额。
     */
    private void stubVaultTrackingEconomy() {
        Economy economy = mock(Economy.class);
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(1);
            depositCalls.incrementAndGet();
            long balance = vaultCents.addAndGet(Math.round(amount * 100.0));
            return new EconomyResponse(amount, balance / 100.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(1);
            long balance = vaultCents.addAndGet(-Math.round(amount * 100.0));
            return new EconomyResponse(amount, balance / 100.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(economy.format(anyDouble()))
                .thenAnswer(inv -> String.format(java.util.Locale.ROOT, "%.2f",
                        inv.<Double>getArgument(0)));
        when(plugin.getEconomy()).thenReturn(economy);
    }

    /** 收集 WARNING 的日志处理器（{@code RewardEngine.warn} 走 {@code plugin.getLogger()}）。 */
    private static final class CapturingHandler extends Handler {
        private final java.util.List<LogRecord> records = new java.util.ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        synchronized void clear() {
            records.clear();
        }

        synchronized String describe() {
            StringBuilder sb = new StringBuilder("[");
            for (LogRecord record : records) {
                sb.append(record.getLevel().getName()).append(':')
                        .append(record.getMessage()).append(" | ");
            }
            return sb.append(']').toString();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
