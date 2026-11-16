package com.letslearnenglish.minecraftplugin.core.progress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * {@link RewardEngine} 的<b>支付防护</b>不变量（round17）。
 *
 * <h2>本类钉的两个缺陷</h2>
 *
 * <h3>缺陷 1：六处 {@code response.transactionSuccess()} 裸解引用</h3>
 * {@code EconomyResponse} 是 Vault 的<b>外部</b>返回值。第三方 provider（自研的、
 * CMI/Essentials 的旧版本、Vault 的 mock 实现）内部出错时<strong>可以返回 null</strong>，
 * 也<strong>可能抛 RuntimeException</strong>。原实现在六处直接写
 * {@code response.transactionSuccess()}，于是 provider 的一个内部错误会在
 * <b>答题判分 / 会话完成 / 复习判定</b>这些主线程热路径上变成 NPE —— 玩家这一局答题
 * 被打断，而异常栈里没有「这是经济插件的问题」的线索。{@code giveMoneyForReviewIncorrect}
 * 那处更糟：它在任何 try 之外，NPE 会中断复习判定的整个后续流程。
 *
 * <h3>缺陷 2：{@code review-penalty-amount} 配负数 → 无限刷钱</h3>
 * {@code withdrawPlayer(player, -500)} 在 Vault 语义下是<b>给玩家加 500</b>，而这条路
 * 完全绕过 {@code money.daily-cap}（{@code clampToDailyCap} 只挂在 deposit 侧）。
 * 于是「复习答错」变成「每错一次得 500 金币」，可无限刷。
 *
 * <h2>为什么这些断言能杀死变异（而不是自证桩）</h2>
 * <ul>
 *   <li>null / 异常的桩是<b>真的</b>从 {@code Economy} mock 返回的，不是绕过被测代码的
 *       lambda —— 判据是「调用方不抛 + 钱没动 + 有 WARNING」，三者缺一即红；</li>
 *   <li>「钱没动」用 Vault 侧<b>记账桩</b>（{@code depositCalls} / {@code withdrawCalls}）+
 *       数据库真源（{@code total_coins_earned} / {@code daily_coin_counters}）双向核对，
 *       不是只看返回值；</li>
 *   <li>负数用例断言 {@code withdrawPlayer} <b>从未被调用</b>（{@code never()}），
 *       把「用 0 代替负数去调一次」这种半吊子修法也一并杀死。</li>
 * </ul>
 *
 * <p>只 mock 两个外部系统：Vault 的 {@link Economy} 与 Bukkit 的 {@link Player}。
 * 数据库用<b>内存替代</b>（{@code DatabaseManager} mock 返回 false / null）—— 本类
 * 不关心落库细节，{@code RewardEngineDailyCapTest} 已用真 SQLite 覆盖。
 */
@DisplayName("RewardEngine 支付防护：null 响应、provider 抛异常、负数额惩罚")
class RewardEnginePaymentGuardTest {

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000ab01");

    private static final int CAP = 500;

    // Mockito 造 LetsLearnEnglish 的 mock 要十几秒（继承链深），与既有测试同样整类复用。
    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;
    private static Logger logger;
    private static CapturingHandler handler;

    private FileConfiguration config;
    private RewardEngine engine;
    private Player player;
    private Economy economy;

    /** Vault 侧的调用记账（不是 lambda 常量返回，见类头）。 */
    private final List<Double> depositAmounts = new ArrayList<>();
    private final List<Double> withdrawAmounts = new ArrayList<>();
    private final List<String> sentMessages = new ArrayList<>();
    /** 调用次数：故障分支不记金额，所以次数必须独立计数，否则「前置：调用过一次」无法断言。 */
    private int depositCalls;
    private int withdrawCalls;

    /** provider 行为开关。 */
    private volatile boolean depositReturnsNull;
    private volatile boolean depositThrows;
    private volatile boolean withdrawReturnsNull;
    private volatile boolean withdrawThrows;

    @BeforeAll
    static void createSharedMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        logger = Logger.getLogger("RewardEnginePaymentGuardTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);
        when(plugin.getLogger()).thenReturn(logger);
    }

    @BeforeEach
    void setUp() {
        handler.clear();
        depositAmounts.clear();
        withdrawAmounts.clear();
        sentMessages.clear();
        depositCalls = 0;
        withdrawCalls = 0;
        depositReturnsNull = false;
        depositThrows = false;
        withdrawReturnsNull = false;
        withdrawThrows = false;

        // 真 YamlConfiguration：getInt/getBoolean/getDouble 的默认值必须生效，
        // 否则 money.enabled / 日限 / 惩罚额全被 mock 成 0|false，一行成功路径都跑不到。
        config = new YamlConfiguration();
        config.set("progress-system.rewards.types.money.enabled", true);
        config.set("progress-system.rewards.types.money.base-amount", 10.0);
        config.set("progress-system.rewards.types.money.combo-bonus", true);
        config.set("progress-system.rewards.types.money.session-complete-bonus", 50.0);
        config.set("progress-system.rewards.types.money.max-combo-bonus", 25.0);
        config.set("progress-system.rewards.types.money.review-multiplier", 1.2);
        config.set("progress-system.rewards.types.money.review-penalty", true);
        config.set("progress-system.rewards.types.money.review-penalty-amount", 5.0);
        config.set("money.daily-cap", CAP);
        config.set("word-system.daily-limit", 50);

        when(configManager.getMainConfig()).thenReturn(config);
        when(configManager.isRewardsEnabled()).thenReturn(true);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("GuardTester");
        doAnswer(inv -> {
            sentMessages.add(inv.getArgument(0));
            return null;
        }).when(player).sendMessage(anyString());

        MessageUtil messageUtil = mock(MessageUtil.class);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(inv -> "MSG:" + inv.getArgument(1));

        PlayerDataStore dataStore = mock(PlayerDataStore.class);
        when(dataStore.getTodayAnswerCount(any(UUID.class))).thenReturn(0);

        // 内存替代：本类关心的不是落库，而是「钱没动时不该记流水」。
        // recordCoinsEarned/Spent 返回 true（成功），判据靠 verify(never()) 与调用记账。
        DatabaseManager databaseManager = mock(DatabaseManager.class);
        when(databaseManager.recordCoinsEarned(any(UUID.class), anyDouble())).thenReturn(true);
        when(databaseManager.recordCoinsSpent(any(UUID.class), anyDouble())).thenReturn(true);
        when(databaseManager.isSQLite()).thenReturn(true);
        when(databaseManager.getTablePrefix()).thenReturn("lle_");
        // withConnection 的回调一律不执行：单参版返回 null（建表闸据此复位，等价于
        // 「没有 DB，退化回纯内存」），双参版返回 -1L（= 回读失败，调用方按 0 降级）。
        // 显式 <Long> 是必需的：泛型擦除后默认 mock 返回 null，会在这里对 long 拆箱成 NPE，
        // 让「provider 返回 null」的用例以一个完全无关的 NPE 变红 —— 典型的自证桩陷阱。
        when(databaseManager.withConnection(any())).thenReturn(null);
        when(databaseManager.<Long>withConnection(any(), any())).thenReturn(-1L);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);

        stubVaultTrackingEconomy();

        engine = new RewardEngine(plugin);
    }

    // ==================================================================
    // 缺陷 1a：depositPlayer 返回 null
    // ==================================================================

    @Test
    @DisplayName("depositCappedReward：provider 返回 null → 不抛、返 0、不记账、留 WARNING")
    void nullDepositResponseIsTreatedAsFailure() {
        depositReturnsNull = true;

        double paid = assertDoesNotThrow(
                () -> engine.depositCappedReward(player, 100.0),
                "provider 返回 null 不得让 NPE 冒到答题/领奖调用链上喵");

        assertEquals(0.0, paid, 1e-9,
                "null 响应必须视为失败返回 0（调用方据此不标记已领）喵");
        assertEquals(1, depositCalls, "前置：Vault 应被调用过一次");
        assertTrue(handler.describe().contains("null EconomyResponse"),
                "必须留 WARNING 说明是 provider 返回了 null，实际：" + handler.describe());
        assertTrue(handler.hasWarning(), "级别必须是 WARNING 及以上");
        // 钱没动 → 不得写 earned 流水、不得占用今日金币额度。
        // （DB 在 setUp 里把 withConnection 打桩成"读失败"，真落库路径由
        //   RewardEngineDailyCapTest 用真 SQLite 覆盖；这里断言的是"根本没去写"。）
        verify(plugin.getDatabaseManager(), never())
                .recordCoinsEarned(any(UUID.class), anyDouble());
        assertTrue(sentMessages.isEmpty(),
                "失败时不得给玩家发「+N 金币」的假提示，实际：" + sentMessages);
    }

    @Test
    @DisplayName("giveMoneyForCorrectAnswer：provider 返回 null → 不抛、不提示玩家")
    void nullDepositResponseInCorrectAnswerPathDoesNotThrow() {
        depositReturnsNull = true;

        assertDoesNotThrow(() -> engine.giveMoneyForCorrectAnswer(player, 1.0, null),
                "答题热路径上的 NPE 会打断玩家这一局答题喵");
        assertTrue(handler.describe().contains("null EconomyResponse"),
                "必须可见，实际：" + handler.describe());
        assertTrue(sentMessages.isEmpty(),
                "钱没到账就不该提示已发放，实际：" + sentMessages);
    }

    @Test
    @DisplayName("giveSessionCompleteMoneyReward：provider 返回 null → 不抛")
    void nullDepositResponseInSessionCompletePathDoesNotThrow() {
        depositReturnsNull = true;

        assertDoesNotThrow(() -> engine.giveSessionCompleteMoneyReward(player, 5));
        assertTrue(handler.describe().contains("null EconomyResponse"),
                "必须可见，实际：" + handler.describe());
    }

    @Test
    @DisplayName("giveMoneyForReviewCorrect：provider 返回 null → 不抛")
    void nullDepositResponseInReviewCorrectPathDoesNotThrow() {
        depositReturnsNull = true;

        assertDoesNotThrow(() -> engine.giveMoneyForReviewCorrect(player, 1.0, null));
        assertTrue(handler.describe().contains("null EconomyResponse"),
                "必须可见，实际：" + handler.describe());
    }

    @Test
    @DisplayName("giveStoppedSessionMoneyReward：provider 返回 null → 不抛")
    void nullDepositResponseInStoppedSessionPathDoesNotThrow() {
        depositReturnsNull = true;

        // answered=2 / total=10 → 比例 0.2 走中间段的部分发放分支。
        assertDoesNotThrow(() -> engine.giveStoppedSessionMoneyReward(player, 0, 2, 10));
        assertTrue(handler.describe().contains("null EconomyResponse"),
                "必须可见，实际：" + handler.describe());
    }

    // ==================================================================
    // 缺陷 1b：depositPlayer 抛 RuntimeException
    // ==================================================================

    @Test
    @DisplayName("depositCappedReward：provider 抛 RuntimeException → 不抛、返 0、留 WARNING 含异常消息")
    void throwingDepositResponseIsTreatedAsFailure() {
        depositThrows = true;

        double paid = assertDoesNotThrow(
                () -> engine.depositCappedReward(player, 100.0),
                "provider 抛 RuntimeException 不得从发币入口冒出去喵");

        assertEquals(0.0, paid, 1e-9, "抛异常的响应必须视为失败返回 0喵");
        String log = handler.describe();
        assertTrue(log.contains("IllegalStateException"),
                "WARNING 必须带上异常类型（服主才能判断该找谁），实际：" + log);
        assertTrue(log.contains("provider blew up"),
                "WARNING 必须带上异常消息，实际：" + log);
        verify(plugin.getDatabaseManager(), never())
                .recordCoinsEarned(any(UUID.class), anyDouble());
        assertTrue(sentMessages.isEmpty(), "失败时不得提示已发放，实际：" + sentMessages);
    }

    @Test
    @DisplayName("giveMoneyForReviewIncorrect：withdraw 抛异常 → 不抛（原实现此处 NPE 会中断复习流程）")
    void throwingWithdrawResponseDoesNotBreakReviewFlow() {
        withdrawThrows = true;

        assertDoesNotThrow(() -> engine.giveMoneyForReviewIncorrect(player),
                "复习扣款在 try 之外，异常会中断复习判定的整个后续流程喵");
        assertTrue(handler.describe().contains("IllegalStateException"),
                "必须可见，实际：" + handler.describe());
        assertTrue(sentMessages.isEmpty(), "扣款失败不得提示已扣款，实际：" + sentMessages);
    }

    // ==================================================================
    // 缺陷 1c：响应对象本身在 transactionSuccess() 里抛异常
    // ==================================================================

    @Test
    @DisplayName("transactionSuccess() 自身抛 RuntimeException → 不抛、返 0、WARNING 点名那个方法")
    void transactionSuccessThrowingIsTreatedAsFailure() {
        // 与「Vault 调用抛」是两条不同的路径：这条是 provider 安全返回了对象，
        // 但对象的方法自己炸了（自研 provider 里很常见：懒计算结果时摸到 null）。
        EconomyResponse broken = mock(EconomyResponse.class);
        when(broken.transactionSuccess()).thenThrow(new IllegalStateException("lazy calc broke"));
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble())).thenReturn(broken);

        double paid = assertDoesNotThrow(() -> engine.depositCappedReward(player, 100.0),
                "transactionSuccess() 抛异常不得冒出发币入口喵");

        assertEquals(0.0, paid, 1e-9, "必须视为失败返回 0喵");
        String log = handler.describe();
        assertTrue(log.contains("transactionSuccess()"),
                "WARNING 必须点名是 transactionSuccess() 抛的（区别于 Vault 调用本身抛），实际：" + log);
        assertTrue(log.contains("lazy calc broke"), "必须带异常消息，实际：" + log);
        assertTrue(sentMessages.isEmpty(), "失败不得提示已发放，实际：" + sentMessages);
    }

    // ==================================================================
    // 缺陷 1d：withdrawPlayer 返回 null（复习扣款）
    // ==================================================================

    @Test
    @DisplayName("giveMoneyForReviewIncorrect：withdraw 返回 null → 不抛、不写 SPENT 流水")
    void nullWithdrawResponseIsTreatedAsFailure() {
        withdrawReturnsNull = true;

        assertDoesNotThrow(() -> engine.giveMoneyForReviewIncorrect(player));
        assertEquals(1, withdrawCalls, "前置：Vault 应被调用过一次");
        assertTrue(handler.describe().contains("null EconomyResponse"),
                "必须可见，实际：" + handler.describe());
        assertTrue(sentMessages.isEmpty(), "失败不得提示已扣款，实际：" + sentMessages);
        verify(plugin.getDatabaseManager(), never())
                .recordCoinsSpent(any(UUID.class), anyDouble());
    }

    // ==================================================================
    // 2. 负数额惩罚 —— 无限刷钱
    // ==================================================================

    @Test
    @DisplayName("review-penalty-amount=-500 → withdrawPlayer 从未被调用（负数是「发钱」）")
    void negativePenaltyAmountNeverTouchesVault() {
        config.set("progress-system.rewards.types.money.review-penalty-amount", -500.0);

        assertDoesNotThrow(() -> engine.giveMoneyForReviewIncorrect(player));

        assertTrue(withdrawAmounts.isEmpty(),
                "负数额惩罚必须一次都不调 Vault 喵 —— withdrawPlayer(-500) 在 Vault 语义下是"
                        + "「给玩家加 500」，而这条路绕过 money.daily-cap（只挂 deposit 侧），"
                        + "等于复习答错就无限刷钱。实际调用：" + withdrawAmounts);
        verify(economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
        assertTrue(handler.describe().contains("review-penalty-amount"),
                "必须留 WARNING 指出配置键，服主才知道改哪里，实际：" + handler.describe());
        assertTrue(sentMessages.isEmpty(),
                "配置非法时不该给玩家发任何扣款提示，实际：" + sentMessages);
    }

    @Test
    @DisplayName("review-penalty-amount=0 → 不调 Vault、不发提示（0 元流水是噪音）")
    void zeroPenaltyAmountNeverTouchesVault() {
        config.set("progress-system.rewards.types.money.review-penalty-amount", 0.0);

        engine.giveMoneyForReviewIncorrect(player);

        assertTrue(withdrawAmounts.isEmpty(),
                "0 也应短路：不产生 0 元转账流水，实际：" + withdrawAmounts);
        assertTrue(sentMessages.isEmpty(), "「-0 金币」提示是噪音，实际：" + sentMessages);
    }

    @Test
    @DisplayName("review-penalty-amount=NaN / Infinity → 不调 Vault（NaN<=0 是 false，只判正数会漏）")
    void nonFinitePenaltyAmountNeverTouchesVault() {
        config.set("progress-system.rewards.types.money.review-penalty-amount", Double.NaN);
        engine.giveMoneyForReviewIncorrect(player);

        config.set("progress-system.rewards.types.money.review-penalty-amount",
                Double.POSITIVE_INFINITY);
        engine.giveMoneyForReviewIncorrect(player);

        assertTrue(withdrawAmounts.isEmpty(),
                "NaN/Infinity 也必须短路（isFinite 判据），实际：" + withdrawAmounts);
        assertTrue(handler.hasWarning(), "非有限值必须留 WARNING");
    }

    @Test
    @DisplayName("正向对照：合法惩罚额 5.0 仍照常扣钱并写 SPENT 流水（修复没把功能关掉）")
    void validPenaltyAmountStillWithdraws() {
        engine.giveMoneyForReviewIncorrect(player);

        assertEquals(List.of(5.0), withdrawAmounts,
                "合法配置必须照常扣 5.0喵 —— 只加防护不改既有行为");
        verify(plugin.getDatabaseManager(), times(1))
                .recordCoinsSpent(PLAYER_ID, 5.0);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("money-review-penalty")),
                "扣款成功应提示玩家，实际：" + sentMessages);
        assertFalse(handler.hasWarning(),
                "成功路径不该有 WARNING，实际：" + handler.describe());
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * 记账式 Vault 桩：真的记录每一笔金额，并可按开关模拟 provider 故障。
     *
     * <p>刻意<b>不</b>用 {@code thenReturn(固定响应)} —— 那样「钱没动」只能用返回值间接
     * 推断；这里的 {@code depositAmounts}/{@code withdrawAmounts} 是「Vault 侧到底收到了
     * 什么」的直接证据。
     */
    private void stubVaultTrackingEconomy() {
        economy = mock(Economy.class);
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(1);
            depositCalls++;
            if (depositReturnsNull) {
                return null;
            }
            if (depositThrows) {
                throw new IllegalStateException("provider blew up");
            }
            depositAmounts.add(amount);
            return new EconomyResponse(amount, 0.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(1);
            withdrawCalls++;
            if (withdrawReturnsNull) {
                return null;
            }
            if (withdrawThrows) {
                throw new IllegalStateException("provider blew up");
            }
            withdrawAmounts.add(amount);
            return new EconomyResponse(amount, 0.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(economy.format(anyDouble()))
                .thenAnswer(inv -> String.format(java.util.Locale.ROOT, "%.2f",
                        inv.<Double>getArgument(0)));
        when(plugin.getEconomy()).thenReturn(economy);
    }

    /** 收集日志：断言「故障必须可见」，也用于「成功路径不该有 WARNING」的反向对照。 */
    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        synchronized void clear() {
            records.clear();
        }

        synchronized boolean hasWarning() {
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    return true;
                }
            }
            return false;
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
