package com.letslearnenglish.minecraftplugin.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * R11：{@code RewardEngine} 对 {@code recordCoinsEarned} / {@code recordCoinsSpent} 的
 * <b>消费侧</b>语义。
 *
 * <h2>为什么这一组必须单独存在</h2>
 * 数据层（{@code ChatHistoryAndCoinWriteVisibilityTest}）只证明「方法会返回 false」。
 * 真正决定缺陷是否被修好的是<b>调用方拿这个 false 做了什么</b> ——
 * 原来的形状是「返回值压根不存在，失败静默」。现在有返回值了，若调用方继续丢弃它，
 * 行为与修复前完全一致，而测试会全绿。所以这一组断言两件事：
 * <ol>
 *   <li>失败时<b>必须</b>留下可定位的 WARNING；</li>
 *   <li>失败<b>不得</b>改变对外返回口径 —— 尤其 {@code depositCappedReward} 必须仍返回
 *       已入账金额。这是本组最重要的一条：把流水写失败折进返回值会让调用方以为钱没发出去，
 *       于是不标记已领、玩家重答时<b>再发一次</b>，把「统计偏少」升级成「重复发钱」。</li>
 * </ol>
 *
 * <p>另外钉住「钱已发出去」这一点：失败路径上必须**仍然**调用
 * {@code addTodayCoinsEarned}（今日金币记账 = {@code money.daily-cap} 内存半边），
 * 否则一次流水抖动会连带让当日额度统计回退。
 *
 * <h2>封死的变异</h2>
 * <ul>
 *   <li>把 {@code recordCoinLedger} 里的返回值判断删掉（只调不看）→ 失败 WARNING 用例红；</li>
 *   <li>让失败冒泡（去掉 try/catch）→ 异常用例红；</li>
 *   <li>把 {@code recordCoinLedger} 换成「失败就返回 0」→ 返回值口径用例红；</li>
 *   <li>失败时提前 return（跳过 {@code addTodayCoinsEarned}）→ 今日记账用例红。</li>
 * </ul>
 */
class RewardEngineCoinLedgerFailureTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-00000000c01d");

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private Economy economy;
    private Player player;
    private MessageUtil messageUtil;
    private RewardEngine engine;
    private CapturingHandler handler;
    private Logger logger;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        databaseManager = mock(DatabaseManager.class);
        economy = mock(Economy.class);
        player = mock(Player.class);
        messageUtil = mock(MessageUtil.class);

        // 真 YamlConfiguration：getInt/getBoolean(key, def) 的默认值必须生效，
        // 否则日限/开关全被 mock 成 0/false，测不到成功路径。
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("progress-system.rewards.types.money.enabled", true);
        cfg.set("money.daily-cap", 500);
        cfg.set("word-system.daily-limit", 50);
        cfg.set("progress-system.rewards.types.money.session-complete-bonus", 50.0);
        cfg.set("progress-system.rewards.types.money.max-combo-bonus", 25.0);
        cfg.set("progress-system.rewards.types.money.review-penalty", true);
        cfg.set("progress-system.rewards.types.money.review-penalty-amount", 5.0);
        cfg.set("progress-system.rewards.types.money.base-amount", 10.0);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(cfg);
        when(configManager.isRewardsEnabled()).thenReturn(true);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getEconomy()).thenReturn(economy);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        // checkDailyCaps 的「答题数门」读 PlayerDataStore.getTodayAnswerCount；
        // mock 的默认值是 0，但 plugin.getPlayerDataStore() 本身是 null → 必须先铺。
        com.letslearnenglish.minecraftplugin.data.PlayerDataStore dataStore =
                mock(com.letslearnenglish.minecraftplugin.data.PlayerDataStore.class);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(dataStore.getTodayAnswerCount(any(UUID.class))).thenReturn(0);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("");
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("CoinTester");
        when(economy.format(anyDouble())).thenReturn("10.00");

        logger = Logger.getLogger("RewardEngineCoinLedgerFailureTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);
        when(plugin.getLogger()).thenReturn(logger);

        engine = new RewardEngine(plugin);
        // 前置：今日金币记账 / 日限回读都走 DatabaseManager.withConnection(...)。mock 的
        // 默认返回是 null，而 readDailyCoinsFromDb 期望兜底值 -1L（"读失败，按 0 降级"）——
        // 不铺这条会在 clampToDailyCap 里 NPE，与本次被测的流水语义无关。
        // 用 thenAnswer 原样返回第二个参数的 fallback，等价于"读失败降级"的真实行为。
        when(databaseManager.withConnection(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(databaseManager.isSQLite()).thenReturn(true);
        when(databaseManager.getTablePrefix()).thenReturn("lle_");
    }

    // ==================================================================
    // 1. depositCappedReward —— 返回值口径绝不能因流水写失败而改变
    // ==================================================================

    @Nested
    @DisplayName("depositCappedReward：流水写失败不得改变「实际入账金额」")
    class DepositCappedReward {

        @Test
        @DisplayName("流水写失败（false）→ 仍返回已入账金额 + WARNING")
        void ledgerFailureDoesNotChangePaidAmount() {
            when(economy.depositPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(10.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
            when(databaseManager.recordCoinsEarned(PLAYER_ID, 10.0)).thenReturn(false);

            double paid = engine.depositCappedReward(player, 10.0);

            assertEquals(10.0, paid, 0.0001,
                    "流水写失败把返值改成 0 了喵 —— 调用方会以为钱没发出去，不标记已领，"
                            + "玩家重进/重答时再发一次（把「统计偏少」升级成「重复发钱」）");
            assertTrue(handler.hasWarning(),
                    "流水写失败必须留下 WARNING 喵 —— 否则与修复前（完全静默）无法区分。实际："
                            + handler.describe());
            assertTrue(handler.describe().contains("CoinTester"),
                    "WARNING 里必须能定位到玩家喵：" + handler.describe());
        }

        @Test
        @DisplayName("流水写抛 RuntimeException → 不冒泡，仍返回已入账金额 + 带堆栈 WARNING")
        void ledgerRuntimeExceptionDoesNotEscape() {
            when(economy.depositPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(10.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
            when(databaseManager.recordCoinsEarned(PLAYER_ID, 10.0))
                    .thenThrow(new IllegalStateException("db gone"));

            double paid = engine.depositCappedReward(player, 10.0);

            assertEquals(10.0, paid, 0.0001,
                    "运行期异常冒泡把发币路径打断了喵 —— 钱已经到账，流水写失败不该否决主流程");
            assertTrue(handler.hasThrowable(),
                    "运行期异常必须带 throwable 记日志（保留类型与堆栈）喵：" + handler.describe());
        }

        @Test
        @DisplayName("流水写成功 → 无 WARNING（恒 false 会让每次发币都刷日志）")
        void ledgerSuccessIsQuiet() {
            when(economy.depositPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(10.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
            when(databaseManager.recordCoinsEarned(PLAYER_ID, 10.0)).thenReturn(true);

            assertEquals(10.0, engine.depositCappedReward(player, 10.0), 0.0001);
            assertFalse(handler.hasWarning(),
                    "成功路径不该有 WARNING 喵：" + handler.describe());
        }

        @Test
        @DisplayName("plugin.getLogger() 自身抛异常 → 不打断发币（可观测性无否决权）")
        void loggerFailureDoesNotBreakPayout() {
            when(economy.depositPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(10.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
            when(databaseManager.recordCoinsEarned(PLAYER_ID, 10.0)).thenReturn(false);
            // 极端形态：日志门面自己坏了（DI 半初始化 / 关服收尾 / 单测未铺桩）。
            when(plugin.getLogger()).thenThrow(new IllegalStateException("logger broken"));

            assertEquals(10.0, engine.depositCappedReward(player, 10.0), 0.0001,
                    "打不了日志不该升级成「发币被打断」喵 —— 钱已经到账，返回值必须仍是已入账金额");
        }

        @Test
        @DisplayName("转账失败（Vault 拒付）→ 根本不写流水，也不报流水失败")
        void vaultFailureDoesNotTouchLedger() {
            when(economy.depositPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(0.0, 10.0, EconomyResponse.ResponseType.FAILURE, "nope"));

            assertEquals(0.0, engine.depositCappedReward(player, 10.0), 0.0001);
            verify(databaseManager, never()).recordCoinsEarned(any(UUID.class), anyDouble());
            // Vault 分支自己也记一条 WARNING（"Failed to deposit ..."），那是转账失败的现场，
            // 不是流水失败 —— 所以这里断言的是「没有流水失败日志」，不是「没有任何 WARNING」。
            assertFalse(handler.describe().contains("record coin"),
                    "Vault 拒付不该产生流水失败日志（那会误导成「钱发了但没记账」）喵："
                            + handler.describe());
        }
    }

    // ==================================================================
    // 2. 其余五个发币/扣钱入口 —— 逐个证明返回值被检查了
    // ==================================================================

    @Nested
    @DisplayName("其余五处发币入口：recordCoinsEarned/Spent 的 false 必须被看见")
    class OtherRewardEntryPoints {

        @Test
        @DisplayName("giveMoneyForCorrectAnswer：流水失败 → WARNING，玩家提示照常发")
        void correctAnswerLedgerFailureIsLogged() {
            when(economy.depositPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(10.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
            when(databaseManager.recordCoinsEarned(PLAYER_ID, 10.0)).thenReturn(false);

            engine.giveMoneyForCorrectAnswer(player, 1.0, null);

            assertTrue(handler.hasWarning(),
                    "答题发币的流水写失败必须可见喵：" + handler.describe());
            verify(databaseManager).recordCoinsEarned(PLAYER_ID, 10.0);
        }

        @Test
        @DisplayName("giveSessionCompleteMoneyReward：流水失败 → WARNING")
        void sessionCompleteLedgerFailureIsLogged() {
            when(economy.depositPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(75.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
            when(databaseManager.recordCoinsEarned(PLAYER_ID, 75.0)).thenReturn(false);

            engine.giveSessionCompleteMoneyReward(player, 5);

            assertTrue(handler.hasWarning(),
                    "会话完成奖的流水写失败必须可见喵：" + handler.describe());
        }

        @Test
        @DisplayName("giveMoneyForReviewCorrect：流水失败 → WARNING")
        void reviewCorrectLedgerFailureIsLogged() {
            when(economy.depositPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(12.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
            when(databaseManager.recordCoinsEarned(any(UUID.class), anyDouble())).thenReturn(false);

            engine.giveMoneyForReviewCorrect(player, 1.0, null);

            assertTrue(handler.hasWarning(),
                    "复习答对奖的流水写失败必须可见喵：" + handler.describe());
        }

        @Test
        @DisplayName("giveMoneyForReviewIncorrect：SPENT 方向失败 → WARNING（方向词正确）")
        void reviewPenaltyLedgerFailureIsLogged() {
            when(economy.withdrawPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(0.0, 5.0, EconomyResponse.ResponseType.SUCCESS, null));
            when(databaseManager.recordCoinsSpent(PLAYER_ID, 5.0)).thenReturn(false);

            engine.giveMoneyForReviewIncorrect(player);

            assertTrue(handler.hasWarning(),
                    "复习扣钱的流水写失败必须可见喵：" + handler.describe());
            assertTrue(handler.describe().contains("spent"),
                    "SPENT 方向的日志必须写 spent（否则排查时看不出方向）喵：" + handler.describe());
            verify(databaseManager).recordCoinsSpent(PLAYER_ID, 5.0);
        }

        @Test
        @DisplayName("停止会话按比例结算：流水失败 → WARNING")
        void stoppedSessionPartialLedgerFailureIsLogged() {
            when(economy.depositPlayer(any(Player.class), anyDouble()))
                    .thenReturn(new EconomyResponse(30.0, 0.0, EconomyResponse.ResponseType.SUCCESS, null));
            when(databaseManager.recordCoinsEarned(any(UUID.class), anyDouble())).thenReturn(false);

            // STOPPED_MIN_ANSWERS_FOR_REWARD=1、STOPPED_FULL_REWARD_RATIO=0.5：
            // answered=2 / total=10 → ratio 0.2，低于全额门、高于零奖励门 → 走 partial 分支。
            engine.giveStoppedSessionMoneyReward(player, 0, 2, 10);

            assertTrue(handler.hasWarning(),
                    "比例结算的流水写失败必须可见喵：" + handler.describe());
        }
    }

    // ---------------- helpers ----------------

    /** 收集日志，供「失败必须可见」「运行期异常必须带堆栈」两类断言使用。 */
    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        synchronized boolean hasWarning() {
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    return true;
                }
            }
            return false;
        }

        synchronized boolean hasThrowable() {
            for (LogRecord record : records) {
                if (record.getThrown() != null) {
                    return true;
                }
            }
            return false;
        }

        synchronized String describe() {
            StringBuilder sb = new StringBuilder("[");
            for (LogRecord record : records) {
                sb.append(record.getLevel().getName()).append(':').append(record.getMessage());
                if (record.getThrown() != null) {
                    sb.append(" throwable=").append(record.getThrown().getClass().getSimpleName());
                }
                sb.append(" | ");
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
