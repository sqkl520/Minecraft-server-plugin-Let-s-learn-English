package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.data.CheckInData;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CheckInManager} 钱路：<b>每日签到的金币实付额</b>。
 *
 * <h2>为什么需要这个类</h2>
 * <p>{@code CheckInManager} 是玩家可见的「签到发币」入口，共 44 行有效逻辑，
 * 此前覆盖率 0%、全仓 {@code src/test} 里连一处 import 都没有。它是四条发币链里
 * 唯一一条既没单测、也没有任何「余额真的加了多少钱」断言的钱路。
 *
 * <h2>钉住的不变量（每条都是一次可执行的变异）</h2>
 * <ol>
 *   <li><b>金额表</b>：{@code STREAK_REWARDS} 的 7 个档位（10/20/30/50/70/85/100）
 *       按 streak 索引取，断言的是<b>实付金额</b>不是调用次数。</li>
 *   <li><b>取模循环</b>：{@code ((streak-1)%7)+1}。streak 8 → 10、14 → 100、15 → 10；
 *       把 {@code %7} 删掉会数组越界，把 {@code -1} 删掉会让第 8 天变 20 —— 两种都红。</li>
 *   <li><b>读库失败 ≠ 从未签到</b>（EH-03）：{@code readFailed=true} 必须
 *       fail-closed —— 不写库、不发钱、如实报 {@code storageFailed}。</li>
 *   <li><b>先记账后发钱</b>：{@code claimCheckIn} 不返回 {@code CLAIMED} 时<b>一分钱都不能出</b>
 *       （claim 没落地，付钱就是白送）。</li>
 *   <li><b>幂等</b>：今天已签到 → 一分钱都不动。</li>
 *   <li><b>经济关闸</b>（R-44）：{@code money.enabled=false} 时 claim 仍算落地
 *       （{@code success=true}），但 {@code coinsEarned=0} 且走的是
 *       {@code economyDisabled} 提示而不是静默 0 币。</li>
 * </ol>
 *
 * <h2>测试结构</h2>
 * <p>SUT 是 {@code CheckInManager} 的<b>付款决策</b>（哪个档位、付不付、付多少），
 * 所以 {@link DatabaseManager} 与 {@link RewardEngine} 都是替身：前者负责三态读面
 * 与写面成败，后者负责「收下金额 → 真的转进 Vault」。Vault 这一层用<b>真的
 * {@link Economy} 替身返回真 {@link EconomyResponse}</b>，并把金额记进台账 ——
 * 于是「决策侧的应付额」与「Vault 侧的实收额」两条链都被钉住，且都不是数调用次数。
 *
 * <p>日期<b>不静态 mock {@code LocalDate}</b>：生产代码用 {@code LocalDate.now()}，
 * 本类按同一 {@code DATE_FORMAT}（{@code yyyy-MM-dd}）现算「今天/昨天/前天」，
 * 与生产同构。
 */
@DisplayName("CheckInManager 钱路：签到档位金额、取模循环、读/写失败不付钱")
class CheckInManagerPayoutTest {

    /** 与 {@code CheckInManager.DATE_FORMAT} 同构（生产是 yyyy-MM-dd）。 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 与 {@code STREAK_REWARDS} 同值，用来把「期望值」写成可读的字面量并交叉验证下标。 */
    private static final int[] EXPECTED_REWARDS = {0, 10, 20, 30, 50, 70, 85, 100};

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000c4e01");
    private static final UUID PLAYER2_ID = UUID.fromString("00000000-0000-0000-0000-0000000c4e02");

    private LetsLearnEnglish plugin;
    private DatabaseManager databaseManager;
    private ProgressManagerImpl progressManager;
    private RewardEngine rewardEngine;
    private Economy economy;
    private Player player;
    private Player player2;
    private CheckInManager manager;

    /** 进到 {@code RewardEngine.depositCappedReward} 的金额（决策侧应付额）。 */
    private final List<Double> requestedAmounts = Collections.synchronizedList(new ArrayList<>());

    /** 真的进到 Vault {@code depositPlayer} 的金额（实收额）。 */
    private final List<Double> vaultDeposits = Collections.synchronizedList(new ArrayList<>());

    /** 假想的经济余额（以「分」为单位的 AtomicLong，避免累加的浮点误差）。 */
    private final java.util.concurrent.atomic.AtomicLong vaultBalance =
            new java.util.concurrent.atomic.AtomicLong(0L);

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        databaseManager = mock(DatabaseManager.class);
        progressManager = mock(ProgressManagerImpl.class);
        rewardEngine = mock(RewardEngine.class);
        player = mock(Player.class);
        player2 = mock(Player.class);

        // 真 Economy 替身返回真 EconomyResponse（照 SeasonLedgerPendingCapTest 的手法）：
        // 记台账 + 维护一个<b>真实滚动的余额</b>，而不是恒 0.0 的常量 balance。
        economy = mock(Economy.class);
        when(economy.depositPlayer(any(Player.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.<Double>getArgument(1);
            vaultDeposits.add(amount);
            // 照 Vault 语义：depositPlayer 返回的 EconomyResponse.balance 是<b>入账后</b>余额。
            double newBalance = vaultBalance.addAndGet(Math.round(amount * 100.0)) / 100.0;
            return new EconomyResponse(amount, newBalance,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });

        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getProgressManager()).thenReturn(progressManager);
        when(progressManager.getRewardEngine()).thenReturn(rewardEngine);
        when(plugin.getEconomy()).thenReturn(economy);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");
        when(player2.getUniqueId()).thenReturn(PLAYER2_ID);
        when(player2.getName()).thenReturn("Bob");

        // 经济默认开闸。
        when(rewardEngine.isMoneyEnabled()).thenReturn(true);
        // 替身按 RewardEngine.depositCappedReward 的真实语义转一手：收下金额 → 过 Vault →
        // 返回实付额（>0 = 已付）。CheckInManager 用 "paid > 0" 判 PAID，与生产一致。
        when(rewardEngine.depositCappedReward(any(Player.class), anyDouble())).thenAnswer(inv -> {
            Player target = inv.getArgument(0);
            double amount = inv.<Double>getArgument(1);
            requestedAmounts.add(amount);
            EconomyResponse response = economy.depositPlayer(target, amount);
            return response.transactionSuccess() ? amount : 0.0;
        });

        // 读面默认：确实没有记录（首次签到）；写面默认成功。
        when(databaseManager.loadCheckInData(any(UUID.class)))
                .thenReturn(new DatabaseManager.CheckInLoad(null, false));
        when(databaseManager.claimCheckIn(any(UUID.class), anyString(), anyInt()))
                .thenReturn(DatabaseManager.CheckInClaim.CLAIMED);

        manager = new CheckInManager(plugin);
    }

    // ==================================================================
    // 1~5：档位 / 循环 / 幂等 / 断签
    // ==================================================================

    @Test
    @DisplayName("首次签到（data=null）→ streak 1，实付 STREAK_REWARDS[1]=10")
    void firstEverCheckInPaysDayOneReward() {
        String today = today();

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertTrue(result.success(), "首次签到必须算 claim 落地喵（success=true）");
        assertEquals(1, result.streak(), "首次签到 streak 必须是 1 喵");
        assertFalse(result.streakBroken(), "首次签到不是「断签」喵（没有前一段可断）");
        assertFalse(result.storageFailed(), "写面成功时不得报 storageFailed 喵");
        assertEquals(EXPECTED_REWARDS[1], result.coinsEarned(), 1e-9,
                "首次签到回报的 coinsEarned 必须等于第 1 档 10 喵");

        // 金额：决策侧应付额 == Vault 实收额 == 第 1 档。
        assertEquals(List.of((double) EXPECTED_REWARDS[1]), requestedAmounts,
                "进到 depositCappedReward 的必须<b>恰好一笔 10</b>（不是「付过」，是「付了 10」）喵");
        assertEquals(List.of((double) EXPECTED_REWARDS[1]), vaultDeposits,
                "Vault 实收额必须<b>恰好是 10</b> 喵");
        assertEquals(result.coinsEarned(), vaultDeposits.get(0), 1e-9,
                "回报给玩家的 coinsEarned 必须与 Vault 实收额一分不差喵");

        // claim 真的写对了：今天的日期 + streak 1。
        verify(databaseManager).claimCheckIn(PLAYER_ID, today, 1);
    }

    @Test
    @DisplayName("连续签到（昨天签过）→ streak+1，实付 STREAK_REWARDS[新 streak]")
    void consecutiveDayIncrementsStreakAndPaysThatTier() {
        when(databaseManager.loadCheckInData(PLAYER_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(daysAgo(1), 2), false));

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertTrue(result.success(), "连续签到必须成功喵");
        assertEquals(3, result.streak(), "昨天 streak=2 → 今天必须是 3 喵");
        assertFalse(result.streakBroken(), "间隔恰好 1 天不得判成断签喵");

        int expected = EXPECTED_REWARDS[3];   // 30
        assertEquals(expected, result.coinsEarned(), 1e-9,
                "第 3 档是 30 喵（若实测 20，说明档位下标偏了一位）");
        assertEquals(List.of((double) expected), requestedAmounts,
                "进到 depositCappedReward 的必须<b>恰好一笔 " + expected + "</b> 喵");
        assertEquals(List.of((double) expected), vaultDeposits,
                "Vault 实收额必须<b>恰好是 " + expected + "</b> 喵");
        verify(databaseManager).claimCheckIn(PLAYER_ID, today(), 3);
    }

    @Test
    @DisplayName("今天已签到 → success=false、coinsEarned=0、一分钱都不动")
    void alreadyCheckedInTodayMovesNoMoney() {
        when(databaseManager.loadCheckInData(PLAYER_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(today(), 4), false));

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertFalse(result.success(), "今天已签到必须是 success=false 喵");
        assertEquals(4, result.streak(), "幂等拒绝必须原样回报既有 streak 喵（不得改成 1）");
        assertEquals(0, result.coinsEarned(), 1e-9, "幂等拒绝时 coinsEarned 必须是 0 喵");
        assertFalse(result.streakBroken(), "幂等拒绝不是断签喵");
        assertFalse(result.storageFailed(), "幂等拒绝 ≠ 存储失败（否则玩家会白重试）喵");

        // 这里 verify(never()) 是<b>断言本身</b>：要证的就是「钱一分没动」。
        verify(rewardEngine, never()).depositCappedReward(any(Player.class), anyDouble());
        verify(economy, never()).depositPlayer(any(Player.class), anyDouble());
        verify(databaseManager, never()).claimCheckIn(any(UUID.class), anyString(), anyInt());
        assertTrue(requestedAmounts.isEmpty(),
                "幂等拒绝路径不得产生任何应付额，实测：" + requestedAmounts);
        assertTrue(vaultDeposits.isEmpty(),
                "幂等拒绝路径不得有任何 Vault 入账，实测：" + vaultDeposits);
    }

    @Test
    @DisplayName("断签（间隔 2 天）→ streakBroken=true，streak 重置 1，仍发第 1 档 10")
    void brokenStreakResetsToOneButStillPaysDayOne() {
        when(databaseManager.loadCheckInData(PLAYER_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(daysAgo(2), 5), false));

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertTrue(result.success(), "断签后重新签到仍是一次成功的 claim 喵");
        assertTrue(result.streakBroken(), "间隔 2 天必须报 streakBroken=true 喵");
        assertEquals(1, result.streak(), "断签后 streak 必须重置为 1 喵（哪怕之前是 5）");

        int expected = EXPECTED_REWARDS[1];   // 10
        assertEquals(expected, result.coinsEarned(), 1e-9,
                "断签重来只拿第 1 档 10 喵（若拿到 70，说明没走重置分支）");
        assertEquals(List.of((double) expected), requestedAmounts,
                "断签路径进到 depositCappedReward 的必须<b>恰好一笔 " + expected + "</b> 喵");
        assertEquals(List.of((double) expected), vaultDeposits,
                "Vault 实收额必须<b>恰好是 " + expected + "</b> 喵");
        verify(databaseManager).claimCheckIn(PLAYER_ID, today(), 1);
    }

    @Test
    @DisplayName("取模循环：streak 8→1→10、14→7→100、15→1→10")
    void streakRewardCyclesEverySevenDays() {
        // 8：7+1 → 有效 1 → 10
        when(databaseManager.loadCheckInData(PLAYER_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(daysAgo(1), 7), false));
        CheckInManager.CheckInResult day8 = manager.checkIn(player);

        assertTrue(day8.success(), "第 8 天必须签到成功喵");
        assertEquals(8, day8.streak(), "第 8 天 streak 必须是 8（streak 本身不取模，只有档位取模）喵");
        assertEquals(EXPECTED_REWARDS[1], day8.coinsEarned(), 1e-9,
                "第 8 天应循环回第 1 档 10 喵");
        assertEquals(List.of(10.0), requestedAmounts,
                "第 8 天进到 depositCappedReward 的必须是 10（删掉 %7 会越界，删掉 -1 会变 20）喵");

        // 14：13%7+1 = 7 → 100
        requestedAmounts.clear();
        vaultDeposits.clear();
        when(databaseManager.loadCheckInData(PLAYER2_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(daysAgo(1), 13), false));
        CheckInManager.CheckInResult day14 = manager.checkIn(player2);

        assertEquals(14, day14.streak(), "第 14 天 streak 必须是 14 喵");
        assertEquals(EXPECTED_REWARDS[7], day14.coinsEarned(), 1e-9,
                "第 14 天应落在第 7 档 100 喵（循环的最高档，最容易被 %7 写错的一格）");
        assertEquals(List.of(100.0), requestedAmounts,
                "第 14 天进到 depositCappedReward 的必须是 100 喵");
        assertEquals(List.of(100.0), vaultDeposits,
                "第 14 天 Vault 实收额必须是 100 喵");

        // 15：14%7+1 = 1 → 10
        requestedAmounts.clear();
        vaultDeposits.clear();
        when(databaseManager.loadCheckInData(PLAYER_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(daysAgo(1), 14), false));
        CheckInManager.CheckInResult day15 = manager.checkIn(player);

        assertEquals(15, day15.streak(), "第 15 天 streak 必须是 15 喵");
        assertEquals(EXPECTED_REWARDS[1], day15.coinsEarned(), 1e-9,
                "第 15 天应再循环回第 1 档 10 喵（14→1 这一格是界点，off-by-one 会变 100）");
        assertEquals(List.of(10.0), requestedAmounts,
                "第 15 天进到 depositCappedReward 的必须是 10 喵");
    }

    // ==================================================================
    // 6~7：失败路径 —— 一分钱都不能出
    // ==================================================================

    @Test
    @DisplayName("读库失败（readFailed）→ fail-closed：不写库、不发钱、storageFailed=true")
    void readFailureFailsClosedWithoutPayingOrWriting() {
        when(databaseManager.loadCheckInData(PLAYER_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(null, true));

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertFalse(result.success(), "读库失败必须如实报失败喵（不得当成首次签到报成功）");
        assertTrue(result.storageFailed(), "读库失败必须标 storageFailed 喵");
        assertEquals(0, result.coinsEarned(), 1e-9, "读库失败不得发币喵");
        assertEquals(0, result.streak(), "读库失败时 streak 不得被伪造成 1 喵（那会覆盖真实连续天数）");

        verify(databaseManager, never()).claimCheckIn(any(UUID.class), anyString(), anyInt());
        verify(rewardEngine, never()).depositCappedReward(any(Player.class), anyDouble());
        verify(economy, never()).depositPlayer(any(Player.class), anyDouble());
        assertTrue(requestedAmounts.isEmpty(),
                "读库失败不得不产生应付额，实测：" + requestedAmounts);
        assertTrue(vaultDeposits.isEmpty(),
                "读库失败不得有任何 Vault 入账，实测：" + vaultDeposits);
    }

    @Test
    @DisplayName("写库失败（claimCheckIn=FAILED）→ claim 没落地，storageFailed=true，一分钱不发")
    void claimWriteFailurePaysNothing() {
        when(databaseManager.loadCheckInData(PLAYER_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(daysAgo(1), 3), false));
        when(databaseManager.claimCheckIn(any(UUID.class), anyString(), anyInt()))
                .thenReturn(DatabaseManager.CheckInClaim.FAILED);

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertFalse(result.success(), "claim 没落地必须 success=false 喵");
        assertTrue(result.storageFailed(), "写库失败必须标 storageFailed（让调用方提示「稍后再试」）喵");
        assertEquals(0, result.coinsEarned(), 1e-9, "claim 没落地时不得发币喵");
        assertEquals(3, result.streak(),
                "写库失败必须回报<b>既有</b> streak 3（不是本次算出的 4，也不能是 0）喵");

        verify(rewardEngine, never()).depositCappedReward(any(Player.class), anyDouble());
        verify(economy, never()).depositPlayer(any(Player.class), anyDouble());
        assertTrue(requestedAmounts.isEmpty(),
                "写库失败不得不产生应付额，实测：" + requestedAmounts);
        assertTrue(vaultDeposits.isEmpty(),
                "写库失败不得有任何 Vault 入账（先记账后发钱的意义就在这里）喵，实测：" + vaultDeposits);
    }

    // ==================================================================
    // 8~9：经济开关 / 精确金额
    // ==================================================================

    @Test
    @DisplayName("经济关闸（isMoneyEnabled=false）→ success=true、economyDisabled=true、coinsEarned=0")
    void economyDisabledStillLandsTheClaimButPaysZero() {
        when(rewardEngine.isMoneyEnabled()).thenReturn(false);

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertTrue(result.success(), "经济关闸时签到权仍必须到账喵（success=true，R-44）");
        assertEquals(1, result.streak(), "经济关闸不影响 streak 喵");
        assertTrue(result.economyDisabled(),
                "必须标 economyDisabled，调用方才知道要给「经济未启用」提示喵");
        assertFalse(result.storageFailed(), "经济关闸不是存储失败喵");
        assertEquals(0, result.coinsEarned(), 1e-9,
                "经济关闸时 coinsEarned 必须计 0喵（不得虚报 10）");

        // claim 必须真的落地。
        verify(databaseManager).claimCheckIn(PLAYER_ID, today(), 1);
        // 且一分钱都没动。
        verify(rewardEngine, never()).depositCappedReward(any(Player.class), anyDouble());
        verify(economy, never()).depositPlayer(any(Player.class), anyDouble());
        assertTrue(vaultDeposits.isEmpty(),
                "经济关闸时 Vault 不得有任何入账，实测：" + vaultDeposits);
    }

    @Test
    @DisplayName("精确金额：第 1 档=10、第 7 档=100，一路走到 Vault 一分不差")
    void exactTierAmountReachesVault() {
        // 第 1 档：首次签到。
        CheckInManager.CheckInResult firstTier = manager.checkIn(player);
        assertEquals(EXPECTED_REWARDS[1], firstTier.coinsEarned(), 1e-9,
                "第 1 档回报额必须是 10 喵");
        assertEquals(10.0, requestedAmounts.get(0), 1e-9,
                "进到 depositCappedReward 的第 1 档金额必须是 <b>10.0</b> 喵，实测 "
                        + requestedAmounts.get(0));
        assertEquals(10.0, vaultDeposits.get(0), 1e-9,
                "Vault 侧第 1 档实收额必须是 <b>10.0</b> 喵，实测 " + vaultDeposits.get(0));

        // 第 7 档：昨天 streak=6 → 今天 7。
        requestedAmounts.clear();
        vaultDeposits.clear();
        when(databaseManager.loadCheckInData(PLAYER2_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(daysAgo(1), 6), false));
        CheckInManager.CheckInResult seventhTier = manager.checkIn(player2);

        assertEquals(7, seventhTier.streak(), "第 7 档对应 streak 7 喵");
        assertEquals(EXPECTED_REWARDS[7], seventhTier.coinsEarned(), 1e-9,
                "第 7 档回报额必须是 100 喵");
        assertEquals(1, requestedAmounts.size(),
                "第 7 档必须<b>恰好一笔</b>应付额（多发/少发都说明金额表错了）喵，实测："
                        + requestedAmounts);
        assertEquals(100.0, requestedAmounts.get(0), 1e-9,
                "进到 depositCappedReward 的第 7 档金额必须是 <b>100.0</b> 喵，实测 "
                        + requestedAmounts.get(0));
        assertEquals(100.0, vaultDeposits.get(0), 1e-9,
                "Vault 侧第 7 档实收额必须是 <b>100.0</b> 喵，实测 " + vaultDeposits.get(0));
        // 回报额 == 实收额 == 应付额，三者一分不差。
        assertEquals(seventhTier.coinsEarned(), vaultDeposits.get(0), 1e-9,
                "回报给玩家的金额必须与 Vault 实收额完全一致喵");
        verify(databaseManager).claimCheckIn(eq(PLAYER2_ID), eq(today()), eq(7));
    }

    @Test
    @DisplayName("日限截断（R-16）：实发 0.20 就报 0.20，面值 100 另存 nominalCoins，rewardCapped=true")    void cappedPayoutReportsPaidAmountNotFaceValue() {
        // 面值是第 7 档 100，但 RewardEngine 只实付 0.20（日限只剩 0.20）。
        when(rewardEngine.depositCappedReward(any(Player.class), anyDouble())).thenAnswer(inv -> {
            double nominal = inv.<Double>getArgument(1);
            requestedAmounts.add(nominal);
            // 真 Vault 只收到被截断后的 0.20。
            economy.depositPlayer(inv.<Player>getArgument(0), 0.20);
            return 0.20;
        });

        when(databaseManager.loadCheckInData(PLAYER_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(daysAgo(1), 6), false));

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertTrue(result.success(), "截断不是失败：claim 已落地、streak 已推进，必须 success=true 喵");
        assertEquals(7, result.streak(), "截断不影响 streak 喵");

        // 核心判据：回报的是<b>实发额</b>，不是面值。
        assertEquals(0.20, result.coinsEarned(), 1e-9,
                "coinsEarned 必须是<b>实发 0.20</b> 喵 —— 若为 100，说明面值被当成到账额播报"
                        + "（玩家会被告知「获得 100 金币」而实际只到 0.20）");
        assertEquals(EXPECTED_REWARDS[7], result.nominalCoins(), 1e-9,
                "nominalCoins 必须是面值 100 喵（供渲染「应发 100」）");
        assertTrue(result.rewardCapped(),
                "实发 < 面值 必须让 rewardCapped()=true，调用方才能补一句「被日限截断」喵");
        assertEquals(0.20, vaultDeposits.get(0), 1e-9,
                "Vault 实收额必须是 0.20 喵，实测 " + vaultDeposits.get(0));
        assertEquals(result.coinsEarned(), vaultDeposits.get(0), 1e-9,
                "回报额与 Vault 实收额必须一分不差喵");
    }

    @Test
    @DisplayName("未截断（R-16）：rewardCapped=false，nominalCoins 与实发额同值")
    void uncappedPayoutHasNoCapFlag() {
        when(databaseManager.loadCheckInData(PLAYER_ID))
                .thenReturn(new DatabaseManager.CheckInLoad(new CheckInData(daysAgo(1), 2), false));

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertEquals(EXPECTED_REWARDS[3], result.coinsEarned(), 1e-9,
                "未截断时实发额 = 面值 = 30 喵");
        assertEquals(EXPECTED_REWARDS[3], result.nominalCoins(), 1e-9,
                "未截断时 nominalCoins 也应等于 30 喵");
        assertFalse(result.rewardCapped(),
                "足额发放不得报 rewardCapped 喵（否则玩家会看到多余的「被截断」提示）");
    }

    /**
     * 档位表逐格遍历：streak 1..7 的<b>七个档位</b>全部钉死。
     *
     * <p>上面几个用例只走到第 1/3/7 档（10 / 30 / 100），第 2/4/5/6 档
     * （20 / 50 / 70 / 85）此前<b>没有任何断言</b>——把 {@code STREAK_REWARDS}
     * 里 20 改成 25、把 85 改成 80，测试全绿。本用例按 (昨日 streak → 今日 streak)
     * 逐档驱动真实生产路径，并对每一档断言：决策侧应付额、Vault 实收额、
     * 回报给玩家的 coinsEarned 三者都<b>恰好等于该档面值</b>。
     */
    @ParameterizedTest(name = "昨日 streak={0} → 今日 streak={1}，实付 {2}")
    @CsvSource({
            "0, 1, 10",   // 首次签到（昨日 streak 0 用 data=null 表达，见下）
            "1, 2, 20",
            "2, 3, 30",
            "3, 4, 50",
            "4, 5, 70",
            "5, 6, 85",
            "6, 7, 100"
    })
    void everyStreakTierPaysItsExactTableAmount(int previousStreak, int expectedStreak,
                                                double expectedAmount) {
        // previousStreak == 0 → 首次签到（data=null），其余 → 昨天签过。
        when(databaseManager.loadCheckInData(PLAYER_ID)).thenReturn(
                previousStreak == 0
                        ? new DatabaseManager.CheckInLoad(null, false)
                        : new DatabaseManager.CheckInLoad(
                                new CheckInData(daysAgo(1), previousStreak), false));

        CheckInManager.CheckInResult result = manager.checkIn(player);

        assertTrue(result.success(), "第 " + expectedStreak + " 档签到必须成功喵");
        assertEquals(expectedStreak, result.streak(),
                "昨日 streak=" + previousStreak + " 连续签到后必须是 " + expectedStreak + " 喵");
        assertEquals(expectedAmount, result.coinsEarned(), 1e-9,
                "第 " + expectedStreak + " 档的 coinsEarned 必须恰好是 " + expectedAmount
                        + " 喵（档位表逐格遍历）");
        assertEquals(List.of(expectedAmount), requestedAmounts,
                "第 " + expectedStreak + " 档进到 depositCappedReward 的必须<b>恰好一笔 "
                        + expectedAmount + "</b> 喵");
        assertEquals(List.of(expectedAmount), vaultDeposits,
                "第 " + expectedStreak + " 档 Vault 实收额必须<b>恰好是 " + expectedAmount + "</b> 喵");
        assertEquals(result.coinsEarned(), vaultDeposits.get(0), 1e-9,
                "第 " + expectedStreak + " 档回报额必须与 Vault 实收额一分不差喵");
        verify(databaseManager).claimCheckIn(PLAYER_ID, today(), expectedStreak);
    }

    // ==================================================================
    // 日期工具（与生产同构：LocalDate.now() + yyyy-MM-dd）
    // ==================================================================

    private static String today() {
        return LocalDate.now().format(DATE_FORMAT);
    }

    private static String daysAgo(int days) {
        return LocalDate.now().minusDays(days).format(DATE_FORMAT);
    }
}
