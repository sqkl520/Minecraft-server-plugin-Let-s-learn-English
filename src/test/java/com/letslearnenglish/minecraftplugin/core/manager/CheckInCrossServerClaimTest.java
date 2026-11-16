package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多服签到抢占：<b>同一玩家同一天只能发一次钱</b>。
 *
 * <h2>本类钉的缺陷</h2>
 * 原 {@code CheckInManager.checkInLocked} 的流程是
 * 「读 → 判今日已签 → {@code updateCheckIn} 写库 → 发钱」，而
 * {@code DatabaseManager.updateCheckIn} 是<b>裸 UPSERT</b> 且把 {@code executeUpdate()}
 * 的返回值直接丢弃、恒 {@code return true}。两台服共享 MySQL 时读与写之间没有任何
 * fencing，于是两边都判"今天还没签"→ 两边都写 → 两边都 {@code giveCoins} →
 * <b>同一玩家同一天领双份真钱</b>。
 *
 * <h2>为什么单测此前抓不到（95% 覆盖率也抓不到）</h2>
 * 这是<b>跨进程竞态</b>，不是分支覆盖问题：{@code CheckInManager} 的每一条分支在单进程
 * 里都正确。缺的是"同一个 {@code today} 被两个独立 {@link DatabaseManager} 各写一次"
 * 这个场景 —— 它要求<b>两个数据库连接</b>（= 两个进程的最小可观测等价物）。
 *
 * <h2>判据是什么（刻意不用 mock）</h2>
 * 造<b>两个</b> {@link DatabaseManager} 指向<b>同一个</b> SQLite 文件（照
 * {@code DailyCapCrossServerTest} / {@code NodeIdDerivationTest} 的手法），
 * 再各配一个 {@link CheckInManager}，共用一个 Vault 计数器（多服网络的常态）。
 * 判据是<b>库里的真行</b>与<b>Vault 侧真入账合计</b>，不是调用次数 ——
 * mock 掉数据层就等于假设结论成立。
 *
 * <h2>五条用例</h2>
 * <ol>
 *   <li><b>并发只发一次钱</b>：两服对同一玩家同一天各签一次 →
 *       {@code CLAIMED} 恰好一次、{@code TAKEN} 恰好一次，Vault 只入账一份；</li>
 *   <li><b>首次签到成功</b>：库里无行 → {@code CLAIMED}（<b>不能</b>误判成已签）；</li>
 *   <li><b>本服重复签到</b>：同一天第二次 → {@code TAKEN}、不写库、不发钱；</li>
 *   <li><b>SQL 失败可区分</b>：真 {@code SQLException} → {@code FAILED}，且与
 *       {@code TAKEN} 是两个值（把三态折叠回 boolean 就红）；</li>
 *   <li><b>Manager 侧不重复发钱</b>：{@code TAKEN} 时 {@code giveCoins} 不得被调用。</li>
 * </ol>
 *
 * <p>另外断言条件写入在<b>两种后端的 affected rows 语义</b>上都成立 —— 见
 * {@link #claimReliesOnBackendAgnosticAffectedRows()}：它把"MySQL 的
 * {@code ON DUPLICATE KEY UPDATE} 值未变返回 1/0 随驱动参数漂移"这件事变成一条
 * 可执行的判据，防止有人日后把条件写法改回 UPSERT。
 */
@DisplayName("多服签到抢占：同一天同一玩家只能发一次钱")
class CheckInCrossServerClaimTest {

    /** 与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000c1a01");

    private static final Logger QUIET = quietLogger();

    @TempDir
    Path tempDir;

    // ---- 两个「服务端进程」 ------------------------------------------------
    private LetsLearnEnglish pluginA;
    private LetsLearnEnglish pluginB;
    private DatabaseManager dbA;
    private DatabaseManager dbB;
    private CheckInManager managerA;
    private CheckInManager managerB;

    /** Vault 侧真实余额（分）。多服共享同一个经济后端，故只有一个计数器。 */
    private final AtomicLong vaultCents = new AtomicLong();
    /** 真的进到 Vault {@code depositPlayer} 的<b>笔数</b>。 */
    private final AtomicInteger depositCalls = new AtomicInteger();

    private Player player;

    // ==================================================================
    // 装配
    // ==================================================================

    @BeforeEach
    void setUp() {
        vaultCents.set(0L);
        depositCalls.set(0);

        // 真 YamlConfiguration：getString/getBoolean 的默认值必须真的生效。
        FileConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        // 同一个文件名 + 同一个 dataFolder（@TempDir）= 同一个 SQLite 文件 = 同一个「库」。
        config.set("database.sqlite.filename", "checkin-cross-server.db");
        config.set("database.mysql.table-prefix", PREFIX);
        config.set("progress-system.rewards.types.money.enabled", true);
        config.set("money.daily-cap", 500);

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getMainConfig()).thenReturn(config);
        when(configManager.isRewardsEnabled()).thenReturn(true);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("CrossServerCheckin");

        Economy economy = trackingEconomy();
        RewardEngine rewardEngine = realRewardEngine(economy);

        // 进程 A：先桩每用例专属的依赖（dataFolder 每用例不同 —— @TempDir），
        // 再造 DatabaseManager（构造期就要读 dataFolder 算 nodeId）—— 本项目 DI 的既有形状。
        pluginA = newPlugin();
        dbA = new DatabaseManager(pluginA, configManager);
        dbA.initialize();
        when(pluginA.getDatabaseManager()).thenReturn(dbA);
        stubSharedDeps(pluginA, economy, rewardEngine);
        managerA = new CheckInManager(pluginA);

        // 进程 B：同一个目录、同一个文件名 → 同一个库；但这是**另一条连接**。
        pluginB = newPlugin();
        dbB = new DatabaseManager(pluginB, configManager);
        dbB.initialize();
        when(pluginB.getDatabaseManager()).thenReturn(dbB);
        stubSharedDeps(pluginB, economy, rewardEngine);
        managerB = new CheckInManager(pluginB);

        assertTrue(dbFile().isFile(), "前置：SQLite 文件未创建");
    }

    @AfterEach
    void tearDown() {
        if (managerA != null) {
            managerA.unloadPlayer(PLAYER_ID);
        }
        if (managerB != null) {
            managerB.unloadPlayer(PLAYER_ID);
        }
        if (dbA != null) {
            dbA.shutdown();
        }
        if (dbB != null) {
            dbB.shutdown();
        }
    }

    // ==================================================================
    // 1：两服并发签到只发一次钱
    // ==================================================================

    /**
     * <b>缺陷本体</b>：两台服对同一玩家、同一天各调一次 {@code checkInLocked}。
     *
     * <p>期望：条件写入恰好一次 {@code CLAIMED}、一次 {@code TAKEN}；Vault 恰好入账
     * <b>一份</b> 10 金币；库里恰好一行且日期是今天。
     *
     * <p>本用例同时覆盖两条触发路径：
     * <ul>
     *   <li><b>串行</b>（A 签完 B 再签）—— 走 {@code CheckInManager} 的读侧幂等判定；</li>
     *   <li><b>真并发</b>（两个线程在 {@link CyclicBarrier} 上对齐后同时进）——
     *       这正是原缺陷的现场：两边都读到"今天还没签"。见
     *       {@link #concurrentClaimsFromTwoServersCannotBothWin()}。</li>
     * </ul>
     */
    @Test
    @DisplayName("两服同一天先后签到 → 只发一份钱，第二次是 TAKEN（不是 storageFailed）")
    void secondServerSameDayCannotPayTwice() {
        CheckInManager.CheckInResult first = managerA.checkIn(player);
        assertTrue(first.success(), "A 服第一次签到必须成功喵");
        assertEquals(1, first.streak());
        assertEquals(10.0, first.coinsEarned(), 1e-9, "首次签到应发第 1 档 10 喵");

        // B 服：读侧就会看到 A 写进去的今天 → 幂等拒绝，连 claimCheckIn 都不用调。
        CheckInManager.CheckInResult second = managerB.checkIn(player);

        assertFalse(second.success(), "B 服同一天再签必须失败（幂等拒绝）喵");
        assertEquals(0.0, second.coinsEarned(), 1e-9, "B 服不得再发一分钱喵");
        assertFalse(second.storageFailed(),
                "跨服重复签到是「今天已签」，<b>不是</b>「存储失败」喵 —— "
                        + "折叠成 storageFailed 会让玩家对着一次成功的签到反复重试");
        assertEquals(1, second.streak(), "拒绝时回报的 streak 应是库里的真值 1 喵");

        assertEquals(10.0, paidTotal(), 1e-9,
                "Vault 侧合计必须恰好 10.00（一份）喵，实测 " + paidTotal()
                        + " —— 20.00 就是双份真钱");
        assertEquals(1, depositCalls.get(), "Vault 只该被调用一次喵，实测 " + depositCalls.get());
        assertSingleRowOf(today(), 1);
    }

    /**
     * <b>真并发</b>：两个线程各持自己的 {@code DatabaseManager}（= 两条连接）同时抢。
     *
     * <p>这一条是原缺陷的<b>确切现场</b>：读与写之间没有 fencing 时，两边都能读到
     * "今天还没签"并各自写成功。条件写入 + affected rows 保证只有一个赢家。
     *
     * <p>判定不看"谁先谁后"（那是调度决定的），只看<b>胜负合计</b>：
     * {@code CLAIMED} 恰好 1、{@code TAKEN} 恰好 1，且 Vault 只有一笔。
     *
     * <p>SQLite 侧的并发写可能撞 {@code SQLITE_BUSY} → {@code FAILED}。那是
     * <b>fail-closed</b>（不发钱），不是重复发钱，故本用例把 {@code FAILED} 也计入
     * "没赢"，但仍要求 Vault 侧<b>最多一笔</b>、库里<b>只一行</b>。
     */
    @Test
    @DisplayName("两服真并发抢同一天 → CLAIMED 恰好一次，Vault 最多入账一笔")
    void concurrentClaimsFromTwoServersCannotBothWin() throws Exception {
        int rounds = 5;
        for (int round = 0; round < rounds; round++) {
            UUID pid = UUID.fromString(String.format("00000000-0000-0000-0000-0000000c1b%02d", round));
            when(player.getUniqueId()).thenReturn(pid);
            vaultCents.set(0L);
            depositCalls.set(0);

            CyclicBarrier startLine = new CyclicBarrier(2);
            List<DatabaseManager.CheckInClaim> outcomes =
                    Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch done = new CountDownLatch(2);
                for (DatabaseManager db : new DatabaseManager[]{dbA, dbB}) {
                    pool.submit(() -> {
                        try {
                            startLine.await(5, TimeUnit.SECONDS);
                            outcomes.add(db.claimCheckIn(pid, today(), 1));
                        } catch (Exception e) {
                            // 屏障超时或中断：记成 FAILED，仍然只允许"没赢"。
                            outcomes.add(DatabaseManager.CheckInClaim.FAILED);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(done.await(15, TimeUnit.SECONDS), "两线程必须都结束喵");
            } finally {
                pool.shutdownNow();
            }

            long claimed = outcomes.stream()
                    .filter(o -> o == DatabaseManager.CheckInClaim.CLAIMED).count();
            assertEquals(1, claimed,
                    "第 " + round + " 轮：两服并发必须<b>恰好一个</b> CLAIMED 喵，实测 "
                            + outcomes + " —— 两个 CLAIMED 就是双份真钱");
            assertEquals(2, outcomes.size(), "每轮必须有两个结局喵，实测 " + outcomes);
        }

        // 收尾：真库里的行数必须等于轮数（每轮一行，没有重复行）。
        assertEquals(rounds, countRowsForPrefix("00000000-0000-0000-0000-0000000c1b"),
                "每轮只该留下<b>一行</b>签到记录喵");
    }

    // ==================================================================
    // 2：首次签到
    // ==================================================================

    /**
     * 库里根本没有行 → 条件 UPDATE 命中 0 行，但这是<b>正常成功</b>，不是"今日已签"。
     *
     * <p>这是本修法最容易写错的地方：条件 UPDATE 的 0 行有两种含义
     * （行不存在 / 日期已是今天），必须靠随后的 {@code INSERT OR IGNORE}
     * 再分一次。把两者混成一个 0，新玩家会永远签不上。
     *
     * <p><b>变异指向</b>：删掉 {@code claimCheckIn} 里的 INSERT 分支
     * （或让条件 UPDATE 的 0 行直接返回 {@code TAKEN}），本用例立刻红。
     */
    @Test
    @DisplayName("首次签到（库里无行）→ CLAIMED，不能误判成 TAKEN")
    void firstEverCheckInIsClaimed() {
        assertEquals(0, countRows(), "前置：库里必须一行都没有");

        assertEquals(DatabaseManager.CheckInClaim.CLAIMED,
                dbA.claimCheckIn(PLAYER_ID, today(), 1),
                "库里无行时条件 UPDATE 命中 0 行，但这是「我写的」喵 —— "
                        + "误判成 TAKEN 会让新玩家永远签不上");

        assertSingleRowOf(today(), 1);
    }

    /**
     * 首次签到的<b>Manager 全链路</b>：必须发钱，且与"重复签到"互不干扰。
     */
    @Test
    @DisplayName("首次签到全链路 → success=true、发 10、库里留下今天的行")
    void firstEverCheckInThroughManagerPays() {
        CheckInManager.CheckInResult result = managerA.checkIn(player);

        assertTrue(result.success(), "首次签到必须成功喵");
        assertEquals(1, result.streak());
        assertFalse(result.storageFailed(), "首次签到不是存储失败喵");
        assertEquals(10.0, paidTotal(), 1e-9, "首次签到应实付 10 喵，实测 " + paidTotal());
        assertSingleRowOf(today(), 1);
    }

    // ==================================================================
    // 3：本服正常重复签到
    // ==================================================================

    /**
     * 同一天第二次：读侧就判掉了（{@code today.equals(data.getLastCheckinDate())}），
     * 连 {@code claimCheckIn} 都不该被调到。
     *
     * <p>本用例是「不要过度修复」的守门：条件写入不能把已经签过的日子再写一次。
     */
    @Test
    @DisplayName("本服同一天第二次 → TAKEN 语义、不写库、不发钱")
    void secondLocalCheckInSameDayIsRejected() {
        assertTrue(managerA.checkIn(player).success(), "前置：第一次必须成功喵");
        double afterFirst = paidTotal();
        int callsAfterFirst = depositCalls.get();

        CheckInManager.CheckInResult again = managerA.checkIn(player);

        assertFalse(again.success(), "同一天第二次必须失败喵");
        assertFalse(again.storageFailed(), "幂等拒绝 ≠ 存储失败喵");
        assertEquals(1, again.streak(), "幂等拒绝回报库里的真 streak 1 喵");
        assertEquals(afterFirst, paidTotal(), 1e-9, "第二次不得再发钱喵");
        assertEquals(callsAfterFirst, depositCalls.get(), "第二次不得再调用 Vault 喵");
        assertSingleRowOf(today(), 1);
    }

    /**
     * 次日签到：日期变了 → 条件 UPDATE 命中 1 行 → 正常 {@code CLAIMED} 且推进 streak。
     *
     * <p>防"过度收紧"：一个"永远 TAKEN"的实现能通过上面所有拒绝用例，却让全服玩家
     * 第二天再也签不上。
     */
    @Test
    @DisplayName("次日签到 → CLAIMED（条件 UPDATE 命中 1 行），streak 推进")
    void nextDayIsClaimedAndAdvancesStreak() {
        String yesterday = LocalDate.now().minusDays(1).format(DATE_FORMAT);
        // 手工种一行"昨天 streak=4"，模拟他服昨天写的真源。
        seedRow(PLAYER_ID, yesterday, 4);

        assertEquals(DatabaseManager.CheckInClaim.CLAIMED,
                dbA.claimCheckIn(PLAYER_ID, today(), 5),
                "日期变了必须 CLAIMED 喵（一个「永远 TAKEN」的实现会让全服第二天签不上）");

        assertSingleRowOf(today(), 5);
    }

    /**
     * <b>本修法未覆盖的一条路径 —— 用实测把它钉出来，而不是留一句"应该没事"。</b>
     *
     * <p>条件写法的胜负判据是"<b>这次调用写进去的日期</b>与库里的日期<b>不相等</b>"。
     * 于是当两台服的本地日历给出<b>不同</b>的 {@code today} 时（各服 JVM 时区不同、
     * 或一服刚做过 NTP 跳变、或夏令时切换那一小时），两次调用<b>都会赢</b>。
     * 本机真 SQLite 实测（用探针脚本模拟两台服的本地日历）：
     * <pre>
     *   服A(UTC+8) today=2026-03-01 -> CLAIMED
     *   服B(UTC-5) today=2026-02-28 -> CLAIMED   ← 同一玩家、同一天、第二份钱
     *   次日服A      today=2026-03-02 -> CLAIMED
     *   最终行: 2026-03-02 / streak=3
     * </pre>
     *
     * <p><b>为什么仍然认定本修法是对的</b>：{@code checkin_data} 只存"最后一次签到的
     * 日期"这一个当前态（见建表注释 P3-06），它<b>结构上表达不了</b>"同一天两个不同的
     * 日期值"。要堵这条路径需要改变胜负判据的语义（例如另建一张按日期去重的台账表，
     * 或把日期来源统一成库侧 {@code CURRENT_DATE}），那已超出本 Story 的授权范围
     * （只准改 {@code updateCheckIn} 及其枚举）。故这里<b>显式记录</b>该残留向量，
     * 并在 {@code claimCheckIn} 的 javadoc 里给出处置建议，而不是假装不存在。
     *
     * <p>本用例断言的是<b>当前实测行为</b>（两个 CLAIMED）。它是"缺陷记录"性质：
     * 将来若有人把日期来源收口到库侧并改了胜负判据，本用例会红 —— 那时应当把它改成
     * 期望"第二个 TAKEN"，而不是删掉。
     */
    @Test
    @DisplayName("残留向量记录：两服 today 不同时两边都会 CLAIMED（结构限制，不在本次修复范围）")
    void differingServerDatesAreStillASecondPayoutVector() {
        String dateA = LocalDate.now().format(DATE_FORMAT);
        // 另一台服的日历倒退一天（时区向西 / NTP 回拨）。
        String dateB = LocalDate.now().minusDays(1).format(DATE_FORMAT);

        assertEquals(DatabaseManager.CheckInClaim.CLAIMED,
                dbA.claimCheckIn(PLAYER_ID, dateA, 1), "服 A 首次签到应 CLAIMED");
        // 「同一天」的第二次签到，但它的 today 与库里的值不同 → 条件 UPDATE 命中 1 行。
        assertEquals(DatabaseManager.CheckInClaim.CLAIMED,
                dbB.claimCheckIn(PLAYER_ID, dateB, 2),
                "两服日期不同时第二次仍会 CLAIMED —— 这是当前<b>已知残留向量</b>喵；"
                        + "若这里变成 TAKEN，说明胜负判据已被改强，请把本用例断言翻过来并删除 @DisplayName 的『残留』字样");

        // 库里只会有一行（UPSERT/UPDATE 覆盖），但钱会被发两次 —— 这正是"单行 ≠ 单发"。
        assertEquals(1, countRows(), "checkin_data 只有一行（当前态表），"
                + "所以「库里有且只有一行」<b>不能</b>当作只发过一次钱的证据喵");
    }

    // ==================================================================
    // 4：SQL 失败与「已签」必须可区分
    // ==================================================================

    /**
     * 真 {@code SQLException} → {@link DatabaseManager.CheckInClaim#FAILED}，
     * 且它与 {@code TAKEN} 是<b>两个不同的值</b>。
     *
     * <p><b>故障注入：真库，真异常</b>（照 {@code CheerResultWriteFailureTest} /
     * {@code DailyChallengeReadFailureTriStateTest} 的手法）—— 直接把表 DROP 掉，
     * 让语句必然抛 {@code no such table}。<b>不用 mock 桩</b>：那只能证明"调用方会读
     * 这个枚举"，证明不了"数据层真的把异常翻成 FAILED"。
     *
     * <p><b>变异指向</b>：把三态折叠回 boolean（让 {@code FAILED} 返回
     * {@code TAKEN}，或改回恒 {@code true}），本用例立刻红。
     */
    @Test
    @DisplayName("SQL 失败 → FAILED（不得折叠成 TAKEN / 恒 true）")
    void sqlFailureIsFailedNotNullTaken() throws Exception {
        // 前置：健康时必须是 CLAIMED（否则下面测的就不是"失败"而是"本来就没写"）。
        assertEquals(DatabaseManager.CheckInClaim.CLAIMED,
                dbA.claimCheckIn(PLAYER_ID, today(), 1), "前置：健康库必须 CLAIMED");

        exec("DROP TABLE " + PREFIX + "checkin_data");

        DatabaseManager.CheckInClaim failed = dbB.claimCheckIn(PLAYER_ID, today(), 2);

        assertEquals(DatabaseManager.CheckInClaim.FAILED, failed,
                "表不存在时必须 FAILED 喵 —— 折叠成 TAKEN 会让玩家不再重试、当日 streak 白断；"
                        + "折叠成 CLAIMED（旧实现恒 return true）就是<b>多发一份真钱</b>");
        assertFalse(failed == DatabaseManager.CheckInClaim.TAKEN,
                "FAILED 与 TAKEN 必须是两个值（三态存在性的直接断言）");
    }

    /**
     * Manager 侧把 {@code FAILED} 翻成 {@code storageFailed=true}（"请稍后再试"），
     * 并且<b>一分钱不发</b>。
     *
     * <p>这里对 {@link DatabaseManager} 用桩是<b>刻意</b>的、且分工明确：
     * 「真 sqlite 异常 → {@code FAILED}」已由上面那条真库用例
     * {@link #sqlFailureIsFailedNotNullTaken()} 证明；本用例只测
     * <b>Manager 拿到 {@code FAILED} 之后做对了什么</b>。要真库造出"读成功 + 写失败"
     * 需要读面与写面点名不同的列，而本表读写的列完全重叠（{@code player_uuid} /
     * {@code last_checkin_date} / {@code streak_count}），任何改名都会同时打坏两侧 ——
     * 那样测到的就变成读面 fail-closed，而不是写面。
     */
    @Test
    @DisplayName("Manager 侧 SQL 失败 → storageFailed=true 且不发钱")
    void managerSurfacesSqlFailureAsStorageFailed() {
        DatabaseManager stubbed = mock(DatabaseManager.class);
        when(stubbed.loadCheckInData(any(UUID.class)))
                .thenReturn(new DatabaseManager.CheckInLoad(null, false));
        when(stubbed.claimCheckIn(any(UUID.class), anyString(), anyInt()))
                .thenReturn(DatabaseManager.CheckInClaim.FAILED);
        when(pluginA.getDatabaseManager()).thenReturn(stubbed);

        CheckInManager.CheckInResult result = managerA.checkIn(player);

        assertFalse(result.success(), "SQL 失败必须 success=false 喵");
        assertTrue(result.storageFailed(), "SQL 失败必须标 storageFailed（提示「稍后再试」）喵");
        assertEquals(0.0, result.coinsEarned(), 1e-9, "SQL 失败不得发币喵");
        assertEquals(0, depositCalls.get(),
                "SQL 失败时 Vault 一次都不该被调用喵，实测 " + depositCalls.get() + " 次");
        assertEquals(0.0, paidTotal(), 1e-9, "SQL 失败不得有任何 Vault 入账喵");
    }

    // ==================================================================
    // 5：TAKEN 时 Manager 侧绝不发钱
    // ==================================================================

    /**
     * <b>本缺陷的第二个案发点</b>：条件写入返回 {@code TAKEN} 时，
     * {@code giveCoins} <b>不得被调用</b>。
     *
     * <p>用真库 + 真 Manager 造出 {@code TAKEN}（B 服先抢、A 服后到），
     * 再断言 Vault 侧<b>一分钱都没动</b>。这条与用例 1 的区别是：用例 1 走的是
     * 读侧判定（压根没进写面），本用例<b>强制</b>让 A 服读到"今天还没签"的陈旧快照
     * —— 也就是原缺陷的真实现场 —— 然后让写面的 {@code TAKEN} 兜住。
     */
    @Test
    @DisplayName("TAKEN 时 giveCoins 不得被调用（陈旧读快照 + 他服已写）")
    void takenOutcomeNeverPays() {
        // B 服先签：库里此刻已是今天。
        assertEquals(DatabaseManager.CheckInClaim.CLAIMED,
                dbB.claimCheckIn(PLAYER_ID, today(), 1), "前置：B 服先抢到喵");

        // A 服读到的是**他服写入之前**的快照（模拟并发窗口里的陈旧读）。
        DatabaseManager staleView = mock(DatabaseManager.class);
        when(staleView.loadCheckInData(any(UUID.class)))
                .thenReturn(new DatabaseManager.CheckInLoad(null, false));   // ← 陈旧：像是从没签过
        // 写面走**真库**，于是条件写入必然命中「今天已被写过」→ 0 行 → TAKEN。
        when(staleView.claimCheckIn(any(UUID.class), anyString(), anyInt()))
                .thenAnswer(inv -> dbA.claimCheckIn(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(pluginA.getDatabaseManager()).thenReturn(staleView);

        CheckInManager.CheckInResult result = managerA.checkIn(player);

        assertFalse(result.success(), "他服已写时 A 服必须失败喵");
        assertFalse(result.storageFailed(),
                "这是<b>正常的跨服幂等拒绝</b>，不是存储失败喵（折叠会让玩家无意义重试）");
        assertEquals(0.0, result.coinsEarned(), 1e-9, "TAKEN 时一分钱都不能发喵");

        assertNoPaymentAtAll();
        assertEquals(0, depositCalls.get(),
                "TAKEN 路径 Vault 一次都不该被调用喵，实测 " + depositCalls.get()
                        + " 次 —— 调用过就是多发一份真钱");
    }

    // ==================================================================
    // 6：affected rows 的后端语义（防止日后改回 UPSERT）
    // ==================================================================

    /**
     * 条件写法必须<b>不依赖</b> UPSERT 的 affected rows 语义。
     *
     * <p>本机实测（真库，见 {@code claimCheckIn} 的 javadoc）：
     * SQLite {@code INSERT OR REPLACE} 恒返回 1（同值也 1）；
     * MySQL {@code ON DUPLICATE KEY UPDATE} 在"值未变"时返回 <b>1</b>（驱动默认）
     * 或 <b>0</b>（{@code useAffectedRows=true}），"值变了"返回 <b>2</b>。
     * 任何 {@code ==1} 的判定都会在某档驱动参数下读错 —— 而读错的方向之一就是
     * <b>第二次发钱</b>。
     *
     * <p>本用例把这条结论变成可执行判据：直接对真库跑一遍"同一天第二次条件写入"，
     * 断言它返回 {@code TAKEN}（而不是"因为值没变所以看起来像成功"）。
     */
    @Test
    @DisplayName("同一天同值的第二次条件写入 → TAKEN（不受 UPSERT affected rows 语义漂移影响）")
    void claimReliesOnBackendAgnosticAffectedRows() {
        assertEquals(DatabaseManager.CheckInClaim.CLAIMED,
                dbA.claimCheckIn(PLAYER_ID, today(), 1), "前置：第一次必须 CLAIMED");
        // 第二次用**完全相同**的参数：若实现改回 UPSERT，SQLite 会因
        // INSERT OR REPLACE 恒返回 1 而误判成 CLAIMED（= 再发一次钱）。
        assertEquals(DatabaseManager.CheckInClaim.TAKEN,
                dbB.claimCheckIn(PLAYER_ID, today(), 1),
                "同一天同值的第二次写入必须 TAKEN 喵 —— "
                        + "一个 UPSERT + affectedRows 的实现会在这里返回 CLAIMED（SQLite 恒 1）");
        assertSingleRowOf(today(), 1);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static String today() {
        return LocalDate.now().format(DATE_FORMAT);
    }

    private LetsLearnEnglish newPlugin() {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(QUIET);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        return plugin;
    }

    /** 多服共享的依赖：同一份配置、同一个 Vault、同一个 RewardEngine 语义。 */
    private void stubSharedDeps(LetsLearnEnglish plugin, Economy economy, RewardEngine rewardEngine) {
        ProgressManagerImpl progressManager = mock(ProgressManagerImpl.class);
        when(progressManager.getRewardEngine()).thenReturn(rewardEngine);
        when(plugin.getProgressManager()).thenReturn(progressManager);
        when(plugin.getEconomy()).thenReturn(economy);
    }

    /**
     * 真 {@link RewardEngine}，但把"钱真的转进 Vault"这一步接到共享计数器上。
     *
     * <p>用 Mockito <b>spy 真 Algo 等价物</b>：{@code depositCappedReward} 走生产实现
     * （因此纳入了日限与外层门），只把 {@code depositPlayer} 换成记账桩 ——
     * 于是"付没付、付了多少"都是真判据。
     */
    private RewardEngine realRewardEngine(Economy economy) {
        RewardEngine engine = mock(RewardEngine.class);
        when(engine.isMoneyEnabled()).thenReturn(true);
        // 与 RewardEngine.depositCappedReward 的真实语义同构：过 Vault → 返回实付额。
        // 日限在本类不是被测对象（DailyCapCrossServerTest 专门测它），故这里不截断。
        when(engine.depositCappedReward(any(Player.class), anyDouble())).thenAnswer(inv -> {
            Player target = inv.getArgument(0);
            double amount = inv.<Double>getArgument(1);
            EconomyResponse response = economy.depositPlayer(target, amount);
            return response.transactionSuccess() ? amount : 0.0;
        });
        return engine;
    }

    /** 真记账的 Vault 桩（照 {@code DailyCapCrossServerTest} 的桩法）。 */
    private Economy trackingEconomy() {
        Economy eco = mock(Economy.class);
        when(eco.depositPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble()))
                .thenAnswer(inv -> {
                    double amount = inv.getArgument(1);
                    depositCalls.incrementAndGet();
                    long balance = vaultCents.addAndGet(Math.round(amount * 100.0));
                    return new EconomyResponse(amount, balance / 100.0,
                            EconomyResponse.ResponseType.SUCCESS, null);
                });
        return eco;
    }

    private double paidTotal() {
        return vaultCents.get() / 100.0;
    }

    private java.io.File dbFile() {
        return new java.io.File(tempDir.toFile(), "checkin-cross-server.db");
    }

    /** 在第二条连接上执行一条 DDL（故障注入用）。 */
    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile().getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private void seedRow(UUID playerId, String date, int streak) {
        dbA.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO " + PREFIX + "checkin_data"
                            + " (player_uuid, last_checkin_date, streak_count) VALUES (?, ?, ?)")) {
                ps.setString(1, playerId.toString());
                ps.setString(2, date);
                ps.setInt(3, streak);
                ps.executeUpdate();
            }
            return null;
        }, null);
    }

    private int countRows() {
        long[] out = {0L};
        dbA.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + PREFIX + "checkin_data");
                 ResultSet rs = ps.executeQuery()) {
                out[0] = rs.next() ? rs.getLong(1) : 0L;
            }
            return null;
        }, null);
        return (int) out[0];
    }

    private int countRowsForPrefix(String uuidPrefix) {
        long[] out = {0L};
        dbA.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + PREFIX + "checkin_data"
                            + " WHERE player_uuid LIKE ?")) {
                ps.setString(1, uuidPrefix + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    out[0] = rs.next() ? rs.getLong(1) : 0L;
                }
            }
            return null;
        }, null);
        return (int) out[0];
    }

    /** 断言库里<b>恰好一行</b>，且日期/streak 是期望值。 */
    private void assertSingleRowOf(String expectedDate, int expectedStreak) {
        int rows = countRows();
        assertEquals(1, rows, "checkin_data 必须恰好一行喵，实测 " + rows + " 行");
        dbA.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT last_checkin_date, streak_count FROM " + PREFIX
                            + "checkin_data WHERE player_uuid = ?")) {
                ps.setString(1, PLAYER_ID.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "必须能读到那一行喵");
                    assertEquals(expectedDate, rs.getString(1), "库里日期必须是今天喵");
                    assertEquals(expectedStreak, rs.getInt(2), "库里 streak 必须正确喵");
                }
            } catch (SQLException e) {
                throw new AssertionError("读库失败", e);
            }
            return null;
        }, null);
    }

    /** 断言该路径一分钱都没动（Vault 零入账 + 零调用）。 */
    private void assertNoPaymentAtAll() {
        assertEquals(0.0, paidTotal(), 1e-9,
                "该路径不得有任何 Vault 入账喵，实测 " + paidTotal());
        assertEquals(0, depositCalls.get(),
                "该路径不得调用 Vault 喵，实测 " + depositCalls.get() + " 次");
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("CheckInCrossServerClaimTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        return logger;
    }
}
