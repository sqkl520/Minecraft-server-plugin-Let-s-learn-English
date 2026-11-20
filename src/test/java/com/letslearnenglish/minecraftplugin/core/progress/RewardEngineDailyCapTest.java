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
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * {@link RewardEngine} 的<b>钱路</b>不变量（S4）。
 *
 * <h2>为什么这一组必须存在</h2>
 * {@code RewardEngine} 是所有金币发放的唯一门面，{@code money.daily-cap} 是防刷的唯一闸门。
 * 现有 {@code RewardEngineCoinLedgerFailureTest} 只钉「流水写失败不改变返回口径」，
 * <b>完全没有</b>钉住日限钳位本身 —— 也就是说把 {@code clampToDailyCap} 整段删掉，
 * 现有全部用例依然全绿。本类补的就是那段空白。
 *
 * <h2>判据口径（刻意的）</h2>
 * <ul>
 *   <li>断言<b>返回值</b>（{@code depositCappedReward} 的 double）与<b>Vault 侧余额合计</b>
 *       （由 {@link #vaultBalanceCents} 记账的真答复桩），<b>不用</b>
 *       {@code verify(eco).depositPlayer(...)} 的调用次数 ——
 *       次数只证明「调了」，证明不了「调了多少」，而多调一次少调一分在这里都是事故。</li>
 *   <li>用<b>真 SQLite</b>（{@code @TempDir} 临时库）跑 {@code daily_coin_counters} 与
 *       {@code player_data} 的写穿路径，因为这些正是「reload/重启后日限是否还有效」的真源。</li>
 *   <li>只 mock Vault 的 {@link Economy}（外部系统）与 Bukkit 的 {@link Player}。</li>
 * </ul>
 *
 * <h2>封死的变异</h2>
 * <ul>
 *   <li>PLAN M5：{@code depositCappedReward} 里 {@code double pay = clampToDailyCap(...)}
 *       改成 {@code double pay = amount} → 「超额被钳到剩余额度」与「跨入口累计」两组必红；</li>
 *   <li>{@code clampToDailyCap} 的 {@code remaining <= 0} 分支改成 {@code return amount}
 *       → 「额度耗尽不得调 Vault」必红；</li>
 *   <li>转账失败分支也调 {@code addTodayCoinsEarned} → 「失败不消耗额度」必红；</li>
 *   <li>{@code getTodayCoinsEarnedCents} 去掉 DB 回读 seed（改成 miss 即 0）
 *       → 「reload 不是绕过路径」必红。</li>
 *   <li>{@code clampToDailyCap} 里去掉 {@code clampToPayoutCeiling} 这一步（删掉单笔天花板）
 *       → 「不限额度时也有有限天花板」「天花板独立于 daily-cap」「天花板覆盖全部入口」
 *       三组必红。注意<b>只</b>删天花板时「额度 500 的一笔 1e9」仍会被日限钳到 500，
 *       所以那条用例特意<b>同时</b>断言 WARNING 存在 —— 没有天花板就没有这条告警。</li>
 * </ul>
 *
 * <h2>已修并转为回归的两处</h2>
 * <p><b>① 跨自然日自动清零</b>：{@code RewardEngine} 过去在
 * {@code getTodayCoinsEarnedCents} / {@code addTodayCoinsEarned} /
 * {@code notifyEconomyDisabledOncePerDay} 三处直接调 {@code LocalDate.now()}，没有任何可注入
 * 的时钟缝 —— 那条用例只能靠真的等到午夜才能驱动。现在三处统一收口到
 * {@code currentDate()}（{@code LocalDate.now(clock)}），clock 默认为
 * {@code Clock.systemDefaultZone()}，并由包级可见的 {@code setClock} 供测试注入。
 * 见 {@link #dailyCapResetsOnNewDay()}（真驱动跨天）与 {@link #defaultClockIsSystemDefaultZone()}
 * （钉默认值 = 生产行为零变化）。</p>
 *
 * <p><b>② {@code money.daily-cap<=0}（不限）时的金额天花板</b> ——
 * 见 {@link #unlimitedCapHasNoAmountCeiling()}（原为 {@code @Disabled}，天花板落地后摘除）。
 * 天花板是 {@code RewardEngine.MAX_SINGLE_PAYOUT_CENTS = 100_000_000L} 分 = 1,000,000 金币，
 * 与 {@code SeasonManager.MAX_REWARD_CENTS} 同值，加在 {@code clampToDailyCap} 内部，
 * 因此覆盖全部 deposit 调用点。</p>
 */
@DisplayName("RewardEngine 钱路：日限钳位、返回值即实付、跨入口累计、失败不消耗额度")
class RewardEngineDailyCapTest {

    /** 测试库表前缀，与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000ca01");

    /** 默认日限（金币）。 */
    private static final int CAP = 500;

    // plugin / configManager / logger 的 mock 整类复用：Mockito 造 LetsLearnEnglish 的
    // mock 约十余秒（继承链深），16 个用例各造一次光打桩就要三分钟。每个用例重新打桩即可。
    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;
    private static Logger logger;
    private static CapturingHandler handler;

    private FileConfiguration config;
    private DatabaseManager databaseManager;
    private RewardEngine engine;
    private Player player;

    // ---- Vault 侧真记账（不是 lambda 返回常量） --------------------------------
    /** 余额（分）。所有 SUCCESS 的 deposit/withdraw 都改这里。 */
    private final AtomicLong vaultBalanceCents = new AtomicLong();
    /** 累计存入（分），用于「返回值之和 = 余额」的交叉校验。 */
    private final AtomicLong depositedCents = new AtomicLong();
    /** 单笔最大入账（分），用于证明 Long.MAX_VALUE 没有饱和进 Vault。 */
    private final AtomicLong maxDepositCents = new AtomicLong();
    private final AtomicInteger depositCalls = new AtomicInteger();
    private final AtomicInteger withdrawCalls = new AtomicInteger();
    private final List<Double> depositAmounts =
            Collections.synchronizedList(new ArrayList<>());
    /** 模拟 Vault provider 拒付。 */
    private volatile boolean failDeposits;

    // ---- 玩家可见消息 ----------------------------------------------------------
    private final List<String> sentMessages =
            Collections.synchronizedList(new ArrayList<>());
    private final List<String> requestedMessageKeys =
            Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void createSharedMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        logger = Logger.getLogger("RewardEngineDailyCapTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);
        when(plugin.getLogger()).thenReturn(logger);
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        handler.clear();
        vaultBalanceCents.set(0L);
        depositedCents.set(0L);
        maxDepositCents.set(0L);
        depositCalls.set(0);
        withdrawCalls.set(0);
        depositAmounts.clear();
        sentMessages.clear();
        requestedMessageKeys.clear();
        failDeposits = false;

        // 真 YamlConfiguration：getInt/getBoolean/getDouble 的默认值必须生效，
        // 否则日限、开关、奖励额全被 mock 成 0/false，测不到任何成功路径。
        config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "reward-engine-daily-cap.db");
        config.set("database.mysql.table-prefix", PREFIX);
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
        when(player.getName()).thenReturn("CapTester");
        doAnswer(inv -> {
            sentMessages.add(inv.<String>getArgument(0));
            return null;
        }).when(player).sendMessage(anyString());

        MessageUtil messageUtil = mock(MessageUtil.class);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(inv -> {
                    requestedMessageKeys.add(inv.getArgument(1));
                    return "MSG:" + inv.getArgument(1);
                });

        PlayerDataStore dataStore = mock(PlayerDataStore.class);
        when(dataStore.getTodayAnswerCount(any(UUID.class))).thenReturn(0);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);

        stubVaultTrackingEconomy();

        // 真 SQLite：daily_coin_counters / player_data 的写穿路径必须走真库，
        // 否则「reload 后日限是否仍有效」这条核心不变量根本测不到。
        databaseManager = new DatabaseManager(plugin, configManager);
        databaseManager.initialize();
        assertTrue(databaseManager.isSQLite(), "前置条件：必须走 SQLite 分支");
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);

        engine = new RewardEngine(plugin);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    // ==================================================================
    // 1. 日限钳位：返回值就是实付，余额合计就是真相
    // ==================================================================

    @Test
    @DisplayName("额度内全额发放；超额单笔被钳到剩余额度；耗尽后一分不发")
    void depositsUpToCapAreClampedToRemainingAllowance() {
        double first = engine.depositCappedReward(player, 400.0);
        assertEquals(400.0, first, 1e-9,
                "额度内应全额发放的喵");
        assertEquals(40000L, vaultBalanceCents.get(), "Vault 余额应只有第一次的 400.00");

        // 剩余 100.00：请求 400 只应到账 100 —— 这是「部分发放」的核心口径。
        double second = engine.depositCappedReward(player, 400.0);
        assertEquals(100.0, second, 1e-9,
                "超额单笔必须钳到剩余额度（100）而不是原请求（400）喵");
        assertEquals(50000L, vaultBalanceCents.get(),
                "余额合计必须正好等于日限 500.00，不得超发一分");

        int callsBefore = depositCalls.get();
        double third = engine.depositCappedReward(player, 10.0);
        assertEquals(0.0, third, 1e-9, "额度耗尽应返回 0");
        assertEquals(50000L, vaultBalanceCents.get(), "额度耗尽后余额不得再涨");
        assertEquals(callsBefore, depositCalls.get(),
                "额度耗尽时不得再调 Vault 一次（连 0 元都不该转）");
        assertTrue(requestedMessageKeys.contains("progress.daily-cap-reached"),
                "钳位与拦截都应提示玩家，实际请求的 key：" + requestedMessageKeys);
    }

    @Test
    @DisplayName("返回值是实付额不是请求额（PLAN M5 变异点）")
    void returnedAmountIsWhatWasPaidNotWhatWasRequested() {
        double paid = engine.depositCappedReward(player, 9999.0);

        assertEquals(500.0, paid, 1e-9,
                "返回值必须是实付（钳位后的 500），不是请求的 9999 喵 —— 调用方靠 paid>0 "
                        + "标记已领，报错了会导致记账与实际不符");
        assertEquals(50000L, vaultBalanceCents.get(), "Vault 侧必须只收到 500.00");
        assertEquals(50000L, maxDepositCents.get(),
                "单笔入账必须是 500.00，不得出现 9999.00 的转账痕迹");
        assertEquals(500.0, depositedCents.get() / 100.0, 1e-9,
                "累计入账必须与返回值一致");
    }

    @Test
    @DisplayName("额度已满时 depositCappedReward 完全不触碰 Vault（不是转 0 元）")
    void exhaustedCapNeverCallsEconomy() {
        assertEquals(500.0, engine.depositCappedReward(player, 500.0), 1e-9);
        int callsAfterFill = depositCalls.get();
        assertEquals(1, callsAfterFill, "前置：填满额度只应发生一次转账");

        assertEquals(0.0, engine.depositCappedReward(player, 1.0), 1e-9);
        assertEquals(callsAfterFill, depositCalls.get(),
                "额度已满时不该产生任何新的 Vault 调用喵 —— 0 元转账在部分 provider 上会留下"
                        + "流水噪音甚至抛异常");
        assertEquals(50000L, vaultBalanceCents.get());
    }

    @Test
    @DisplayName("负数 / 零 / 负零头：一律 0 且不写流水、不调 Vault")
    void negativeAndZeroAmountsNeverPay() {
        assertEquals(0.0, engine.depositCappedReward(player, -5.0), 1e-9,
                "负数金额必须被拒（否则等于倒扣玩家的钱）喵");
        assertEquals(0.0, engine.depositCappedReward(player, 0.0), 1e-9,
                "零金额必须被拒喵");
        assertEquals(0.0, engine.depositCappedReward(player, -0.01), 1e-9,
                "微小负数也必须被拒喵");

        assertEquals(0, depositCalls.get(), "非法金额不得产生任何 Vault 调用");
        assertEquals(0L, vaultBalanceCents.get());
        assertEquals(0L, dbCoinsEarned(PLAYER_ID),
                "非法金额不得写 total_coins_earned（流水与钱必须一致）");
        assertEquals(0L, dbDailyCents(PLAYER_ID),
                "非法金额不得占用今日额度");
    }

    @Test
    @DisplayName("Double.MAX_VALUE 被日限钳住，不会以 Long.MAX_VALUE 饱和进 Vault")
    void absurdAmountIsClampedBeforeReachingVault() {
        double paid = engine.depositCappedReward(player, Double.MAX_VALUE);

        assertEquals(500.0, paid, 1e-9,
                "天文数字必须先被 money.daily-cap 钳住喵（Math.round 对超范围 double 会静默"
                        + "饱和到 Long.MAX_VALUE 分，约 9.2e16 金币）");
        assertEquals(50000L, maxDepositCents.get(),
                "单笔入账必须是 500.00 —— 出现 Long.MAX_VALUE 说明饱和值直接进了 Vault");
        assertEquals(50000L, vaultBalanceCents.get(), "余额合计不得是天文数字");
        assertEquals(50000L, dbDailyCents(PLAYER_ID), "今日记账应是钳位后的 500.00，不是天文数字");
    }

    /**
     * {@code money.daily-cap<=0}（不限额度）时，{@code RewardEngine} 侧仍必须有<b>有限天花板</b>。
     *
     * <p><b>缺陷本体</b>：{@code clampToDailyCap} 的唯一上限来自剩余额度；{@code dailyCap <= 0}
     * 时它直接 {@code return amount}，于是 {@code Double.MAX_VALUE} 原样进入
     * {@code eco.depositPlayer}。这与 {@code SeasonManager.rewardCents}
     * （R-16 修过的 {@code MAX_REWARD_CENTS = 100_000_000L} 天花板）<b>不是同一处</b> ——
     * 那个天花板在赛季结算侧，不覆盖答题/挑战/签到的发币入口。
     *
     * <p><b>修法</b>：天花板加在 {@code clampToDailyCap} 内部（{@code clampToPayoutCeiling}），
     * 所以<b>一处覆盖全部 deposit 调用点</b>，与 {@code money.daily-cap} 的取值无关。
     * 本用例断言的是"期望"，修复后即为回归。
     */
    @Test
    @DisplayName("不限额度（daily-cap=0）时也有有限天花板，天文数字不得直达 Vault")
    void unlimitedCapHasNoAmountCeiling() {
        config.set("money.daily-cap", 0);
        double paid = engine.depositCappedReward(player, Double.MAX_VALUE);

        assertEquals(1_000_000.0, paid, 1e-9,
                "不限额度时也必须有一个有限天花板（对齐 SeasonManager.MAX_REWARD_CENTS = 1e6 金币），"
                        + "实际单笔实付：" + paid);
        assertEquals(100_000_000L, maxDepositCents.get(),
                "进 Vault 的单笔金额必须正好是 1,000,000 金币，实际（分）：" + maxDepositCents.get());
        assertTrue(handler.hasWarning(),
                "天花板命中意味着配置写错了，必须留 WARNING 供运营者定位，实际日志："
                        + handler.describe());
    }

    /**
     * 天花板<b>独立于</b> {@code money.daily-cap}：额度设成 500（远小于天花板）时，
     * 一笔 {@code 1e9} 也必须先被天花板钳到 1e6，再被日限剩余额度钳到 500。
     *
     * <p>这条用例钉的是"两层钳位都在、且顺序正确"：如果天花板被写在日限<b>之后</b>，
     * 或者被误删，实付会分别变成 500（碰巧对）或 1e9（红）。所以下面同时断言
     * <b>WARNING 存在</b> —— 只有天花板真的看见并拒绝了 1e9 才会打这条日志，
     * 日限单独把 1e9 钳到 500 是<b>不会</b>打天花板告警的。
     */
    @Test
    @DisplayName("天花板独立于 daily-cap：额度 500 时一笔 1e9 仍先被天花板钳（有 WARNING 为证）")
    void payoutCeilingIsIndependentOfDailyCap() {
        config.set("money.daily-cap", 500);
        double paid = engine.depositCappedReward(player, 1_000_000_000.0);

        assertEquals(500.0, paid, 1e-9,
                "日限 500 仍必须生效（实付 500）喵，实际：" + paid);
        assertEquals(50_000L, maxDepositCents.get(),
                "进 Vault 的单笔必须是 500.00（分 = 50000），实际：" + maxDepositCents.get());
        assertTrue(handler.hasWarning(),
                "1e9 必须先撞上天花板并留下 WARNING；没有 WARNING 说明天花板没生效，"
                        + "只是日限碰巧把它钳小了 —— 一旦运营者把 daily-cap 调大，1e9 就会漏出去。"
                        + "实际日志：" + handler.describe());
    }

    /**
     * 天花板对<b>每一个</b> deposit 入口都成立，不只是 {@code depositCappedReward}。
     *
     * <p>四个入口（答对 / 会话完成 / 复习答对 / 提前停止）的金额都来自配置，运营者手滑
     * 一次就是天文数字。这里把相关配置项全设成 {@code 1e18}，并把 {@code daily-cap} 设成 0
     * （不限）——最坏的叠加场景，然后断言 Vault 侧单笔入账从不超过 1,000,000 金币。
     */
    @Test
    @DisplayName("天花板覆盖全部 deposit 入口：四个入口的配置全设成 1e18 也不得超发")
    void payoutCeilingCoversEveryDepositEntryPoint() {
        config.set("money.daily-cap", 0);
        config.set("progress-system.rewards.types.money.base-amount", 1e18);
        config.set("progress-system.rewards.types.money.session-complete-bonus", 1e18);
        config.set("progress-system.rewards.types.money.max-combo-bonus", 1e18);
        config.set("progress-system.rewards.types.money.review-multiplier", 1e18);
        config.set("word-system.daily-limit", 0);

        engine.giveMoneyForCorrectAnswer(player, 1.0, null);
        engine.giveMoneyForCorrectAnswer(player, 1.0,
                new Word("apple", "苹果", "noun", "", "", null, null, "easy", 1, 1e18));
        engine.giveSessionCompleteMoneyReward(player, 5);
        engine.giveMoneyForReviewCorrect(player, 1.0, null);
        engine.giveStoppedSessionMoneyReward(player, 0, 2, 10);

        assertTrue(depositCalls.get() > 0, "前置：至少有一个入口应真的尝试付款");
        assertTrue(maxDepositCents.get() <= 100_000_000L,
                "任何入口的单笔入账都不得超过 1,000,000 金币，实际单笔最大（分）："
                        + maxDepositCents.get() + "，各笔金额：" + depositAmounts);
        for (Double amount : depositAmounts) {
            assertTrue(Double.isFinite(amount) && amount <= 1_000_000.0,
                    "每一笔转账都必须是有限且 ≤ 1e6 的金币，实际：" + amount);
        }
    }

    // ==================================================================
    // 2. 跨入口累计：日限是「当日合计」，不是 per-method
    // ==================================================================

    @Test
    @DisplayName("日限作用于所有发币入口的当日合计（不是每个方法各 500）")
    void capAppliesToSumAcrossDifferentRewardEntryPoints() {
        // 入口 1：答对一题，combo 1.0 → 10.00
        engine.giveMoneyForCorrectAnswer(player, 1.0, null);
        assertEquals(1000L, vaultBalanceCents.get(), "答对题应发 10.00");

        // 入口 2：会话完成，maxCombo=5 → 50 + 25 = 75.00
        engine.giveSessionCompleteMoneyReward(player, 5);
        assertEquals(8500L, vaultBalanceCents.get(),
                "会话完成奖应是 75.00（base 50 + max-combo 25），累计 85.00");

        // 入口 3：挑战/签到门面，请求 450，只剩 415
        double paid = engine.depositCappedReward(player, 450.0);
        assertEquals(415.0, paid, 1e-9,
                "第三个入口必须看得到前两个入口已占用的额度，只能拿到 415 喵");
        assertEquals(50000L, vaultBalanceCents.get(),
                "当日合计必须正好 500.00（10 + 75 + 415），不是 1000.00");
        assertEquals(50000L, dbDailyCents(PLAYER_ID),
                "DB 写穿真源也必须是 500.00 分（跨 reload/重启后日限仍有效的前提）");
    }

    @Test
    @DisplayName("词的定制 reward>0 直接生效，不吃 base×combo（已文档化的不对称）")
    void customWordRewardBypassesComboMultiplier() {
        Word custom = new Word("apple", "苹果", "noun", "", "", null, null, "easy", 1, 7.0);
        engine.giveMoneyForCorrectAnswer(player, 3.0, custom);
        assertEquals(700L, vaultBalanceCents.get(),
                "定制 reward 7.0 不得被 combo 3.0 放大成 21.0（这是代码注释写明的语义）喵");

        // 对照：无定制 reward（null 词）→ base 10 × combo 2.0 = 20.00
        engine.giveMoneyForCorrectAnswer(player, 2.0, null);
        assertEquals(2700L, vaultBalanceCents.get(),
                "无定制 reward 时应走 base-amount × combo = 20.00喵");
        assertEquals(2, depositAmounts.size(), "两次发币应各转一次");
        assertEquals(7.0, depositAmounts.get(0), 1e-9);
        assertEquals(20.0, depositAmounts.get(1), 1e-9);
    }

    @Test
    @DisplayName("复习扣钱不受金币日限约束（扣钱不是发钱）")
    void reviewPenaltyIgnoresDailyCap() {
        assertEquals(500.0, engine.depositCappedReward(player, 500.0), 1e-9,
                "前置：先把今日额度用满");
        assertEquals(50000L, vaultBalanceCents.get());

        engine.giveMoneyForReviewIncorrect(player);

        assertEquals(1, withdrawCalls.get(),
                "额度用满后复习答错仍必须扣钱（日限只拦发币，不拦扣钱）喵");
        assertEquals(49500L, vaultBalanceCents.get(),
                "余额应扣到 495.00，实际：" + vaultBalanceCents.get());
        assertEquals(5L, dbCoinsSpent(PLAYER_ID), "SPENT 方向流水应记 5");
    }

    // ==================================================================
    // 3. 失败方向：转账失败不消耗额度；流水失败不回滚付款但必须可见
    // ==================================================================

    @Test
    @DisplayName("Vault 拒付 → 返回 0、不记账、不消耗额度（重试仍拿满额）")
    void vaultFailureDoesNotConsumeDailyCap() {
        failDeposits = true;
        double failed = engine.depositCappedReward(player, 400.0);

        assertEquals(0.0, failed, 1e-9,
                "transferSuccess()==false 时必须返回 0（调用方据此不标记已领）喵");
        assertEquals(0L, vaultBalanceCents.get(), "拒付不得改变余额");
        assertEquals(0L, dbDailyCents(PLAYER_ID),
                "拒付不得占用今日额度 —— 否则一次 provider 抖动会白烧掉玩家 400 额度喵");
        assertEquals(0L, dbCoinsEarned(PLAYER_ID), "拒付不得写 earned 流水");

        failDeposits = false;
        double retried = engine.depositCappedReward(player, 400.0);
        assertEquals(400.0, retried, 1e-9,
                "重试必须仍能拿到完整 400（而不是只剩 100）喵");
        assertEquals(40000L, vaultBalanceCents.get());
    }

    @Test
    @DisplayName("流水写失败 → 付款不回滚、额度照记、但必须留 WARNING")
    void ledgerFailureDoesNotRollBackPaymentButIsLogged() {
        // 用真 SQL 故障（丢表）制造 recordCoinsEarned 返回 false，
        // 而不是 spy/mock 数据层 —— 判据是「钱真的发了、额度真的占了、日志真的留了」。
        databaseManager.withConnection(conn -> {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DROP TABLE " + PREFIX + "player_data");
            }
            return null;
        });

        double paid = engine.depositCappedReward(player, 400.0);

        assertEquals(400.0, paid, 1e-9,
                "流水写失败不得回滚付款、不得把返回值改成 0（那会让调用方以为没发、再发一次）喵");
        assertEquals(40000L, vaultBalanceCents.get(), "Vault 余额必须是已到账的 400.00");
        assertTrue(handler.describe().contains("under-reported"),
                "流水写失败必须留下可定位的 WARNING（含「统计偏少而非少付钱」的语义）喵，实际："
                        + handler.describe());
        assertTrue(handler.hasWarning(),
                "WARNING 级别必须被真的发出（不只是消息文案里恰好有这个词）喵：" + handler.describe());

        // 关键：额度仍被消耗（今日记账是独立于流水的另一半），否则玩家可借一次流水故障
        // 重刷满额。
        double second = engine.depositCappedReward(player, 400.0);
        assertEquals(100.0, second, 1e-9,
                "流水失败后今日额度仍应记为已用 400，第二笔只能拿 100喵");
        assertEquals(50000L, vaultBalanceCents.get());
    }

    // ==================================================================
    // 4. 「清缓存」不是绕过路径：reload / quit / /lea reset 三条路
    // ==================================================================

    @Test
    @DisplayName("reload 清空内存缓存后，日限从 DB 真源回填（不是从 0 重计）")
    void reloadDoesNotResetDailyCap() {
        assertEquals(400.0, engine.depositCappedReward(player, 400.0), 1e-9);
        assertEquals(40000L, dbDailyCents(PLAYER_ID), "前置：DB 应已写穿 400.00");

        engine.resetDailyCoinsCache();

        double paid = engine.depositCappedReward(player, 400.0);
        assertEquals(100.0, paid, 1e-9,
                "reload 后必须从 DB 回填 400 已用额度，只能再发 100喵 —— "
                        + "若返回 400 说明清缓存成了刷币绕过路径（F-09-02 修的就是这个）");
        assertEquals(50000L, vaultBalanceCents.get());
    }

    @Test
    @DisplayName("quit 不清今日额度：下线重进不得重刷")
    void quitDoesNotResetDailyCap() {
        assertEquals(500.0, engine.depositCappedReward(player, 500.0), 1e-9);

        engine.unloadPlayer(PLAYER_ID);

        assertEquals(0.0, engine.depositCappedReward(player, 10.0), 1e-9,
                "unloadPlayer 是 quit 语义，刻意不清 todayCoinsCache喵 —— "
                        + "清了下线重进即可重刷当日额度");
        assertEquals(50000L, vaultBalanceCents.get());
    }

    @Test
    @DisplayName("/lea reset 只清内存不够：DB 仍是真源，额度会读回来")
    void resetDailyCoinsForClearsMemoryButDbRemainsTruth() {
        assertEquals(500.0, engine.depositCappedReward(player, 500.0), 1e-9);

        engine.resetDailyCoinsFor(PLAYER_ID);

        assertEquals(0.0, engine.depositCappedReward(player, 10.0), 1e-9,
                "只清内存时下一次读会把额度从 daily_coin_counters 读回来（javadoc 明写的陷阱）喵");
        assertEquals(50000L, dbDailyCents(PLAYER_ID),
                "DB 计数必须仍在 500.00 —— 证明 resetDailyCoinsFor 必须与 "
                        + "DatabaseManager.deletePlayerData 配对才算清干净");
        assertEquals(50000L, vaultBalanceCents.get());

        assertDoesNotThrow(() -> engine.resetDailyCoinsFor(null),
                "null 入参必须安全忽略（javadoc 承诺）");
    }

    // ==================================================================
    // 5. 无 Vault：全部入口静默返回 0，不记流水、不占额度、不抛
    // ==================================================================

    @Test
    @DisplayName("无 Vault（eco==null）→ 各入口返回 0、不写流水、不占额度、不抛")
    void noVaultEconomyReturnsZeroAndRecordsNothing() {
        when(plugin.getEconomy()).thenReturn(null);

        assertEquals(0.0, engine.depositCappedReward(player, 100.0), 1e-9,
                "无 Vault 时 depositCappedReward 必须返回 0喵");
        assertDoesNotThrow(() -> engine.giveMoneyForCorrectAnswer(player, 1.0, null));
        assertDoesNotThrow(() -> engine.giveSessionCompleteMoneyReward(player, 5));
        assertDoesNotThrow(() -> engine.giveMoneyForReviewCorrect(player, 1.0, null));
        assertDoesNotThrow(() -> engine.giveMoneyForReviewIncorrect(player));
        assertDoesNotThrow(() -> engine.giveStoppedSessionMoneyReward(player, 0, 2, 10));
        assertDoesNotThrow(() -> engine.giveStoppedSessionMoneyReward(player, 0, 0, 0));

        assertEquals(0, depositCalls.get(), "无 Vault 时不得有任何转账调用");
        assertEquals(0, withdrawCalls.get(), "无 Vault 时不得有任何扣款调用");
        assertEquals(0L, dbCoinsEarned(PLAYER_ID), "无 Vault 时不得写 earned 流水");
        assertEquals(0L, dbCoinsSpent(PLAYER_ID), "无 Vault 时不得写 spent 流水");
        assertEquals(0L, dbDailyCents(PLAYER_ID), "无 Vault 时不得占用今日额度");
    }

    // ==================================================================
    // 6. 开关与去重提示
    // ==================================================================

    @Test
    @DisplayName("isMoneyEnabled 是三道门 AND（rewards 伞 + money 类型）")
    void isMoneyEnabledRequiresAllGates() {
        assertTrue(engine.isMoneyEnabled(), "前置：三门全开时应为 true");

        config.set("progress-system.rewards.types.money.enabled", false);
        assertFalse(engine.isMoneyEnabled(), "money.enabled=false 必须关掉总闸");

        config.set("progress-system.rewards.types.money.enabled", true);
        when(configManager.isRewardsEnabled()).thenReturn(false);
        assertFalse(engine.isMoneyEnabled(),
                "rewards 伞门关闭时也不得发币（原实现只读 money.enabled，伞门形同虚设）喵");
    }

    @Test
    @DisplayName("money.enabled=false 但 Vault 在：每玩家每天只提示一次，quit 后可再提示")
    void economyDisabledNoticeIsOncePerDayPerPlayer() {
        config.set("progress-system.rewards.types.money.enabled", false);

        engine.giveMoneyForCorrectAnswer(player, 1.0, null);
        engine.giveMoneyForCorrectAnswer(player, 1.0, null);
        engine.giveSessionCompleteMoneyReward(player, 5);

        long notices = sentMessages.stream()
                .filter(m -> m.contains("progress.economy-disabled"))
                .count();
        assertEquals(1, notices,
                "三个发币入口在同一天只应提示一次（否则每答对一题刷屏一整行）喵，实际消息："
                        + sentMessages);
        assertEquals(0, depositCalls.get(), "闸门关闭时不得发钱");

        engine.unloadPlayer(PLAYER_ID);
        engine.giveMoneyForCorrectAnswer(player, 1.0, null);
        long afterQuit = sentMessages.stream()
                .filter(m -> m.contains("progress.economy-disabled"))
                .count();
        assertEquals(2, afterQuit,
                "quit 后去重条目应被清除，下次上线可再提示一次喵（F1-24 防 UUID 条目永久残留）");
    }

    /**
     * <b>跨自然日额度清零</b> —— 本类原本唯一一条 {@code @Disabled}，理由是「{@code RewardEngine}
     * 三处直接调 {@code LocalDate.now()}，没有可注入的时钟缝，只能真的等到午夜」。
     *
     * <p>Clock 缝落地后本用例变成纯 JVM 可驱动：注入 {@link MutableClock} 并把时间推到次日，
     * 断言额度回到满额。
     *
     * <p><b>为什么用可变 Clock 而不是两次 {@code Clock.fixed}</b>：{@code Clock.fixed} 是
     * 恒定时刻，本身不能"跨天"；要靠它模拟跨天必须<b>重复注入</b>，那测的是"注入生效"，
     * 而不是"同一个注入点在时间推进后被重新读取"。可变 Clock 只注入一次、此后推进内部
     * {@code Instant}，与真实世界的时间流逝同构 —— 若实现把"今天"缓存进字段、每次发币只读
     * 缓存，本用例同样会红。
     *
     * <p><b>变异判别</b>：把 {@code currentDate()} 里的 {@code LocalDate.now(clock)} 改回裸
     * {@code LocalDate.now()} → 第二次发放仍按真实今天算额度（已满）→ 返回 0，本用例必红。
     * 这就是「Clock 真的被用上了」的证据。
     */
    @Test
    @DisplayName("跨自然日：额度自动清零重计（Clock 注入缝驱动）")
    void dailyCapResetsOnNewDay() {
        MutableClock clock = new MutableClock(
                LocalDate.of(2026, 2, 10).atTime(12, 0));
        engine.setClock(clock);

        assertEquals(500.0, engine.depositCappedReward(player, 500.0), 1e-9);
        assertEquals(0.0, engine.depositCappedReward(player, 10.0), 1e-9,
                "前置：当日额度已满");
        assertEquals(50000L, vaultBalanceCents.get(), "前置：Vault 里应正好是 500.00");
        assertEquals(1, depositCalls.get(),
                "前置：额度已满时不得产生第二次转账");

        // 时间推进到次日（不重新注入 —— 同一个时钟对象，只是内部 Instant 前进）
        clock.advance(java.time.Duration.ofDays(1));

        double nextDay = engine.depositCappedReward(player, 400.0);
        assertEquals(400.0, nextDay, 1e-9,
                "跨天后额度应清零并重新发放喵 —— 实发 0 说明日限读到的还是「昨天=今天」，"
                        + "即 currentDate() 没有走注入的 clock");
        assertEquals(90000L, vaultBalanceCents.get(),
                "跨天后的 400.00 必须真的到账（昨日 500.00 + 今日 400.00 = 900.00）");
        assertEquals(2, depositCalls.get(), "跨天应产生第二次转账");
    }

    /**
     * <b>默认行为不变的直接证据</b>：{@code setClock} 从未被调用时，本类的"今天"必须与
     * {@code LocalDate.now()} 逐字相同 —— 生产路径上 Clock 缝必须是零影响的。
     *
     * <p>测试手法：反射读出 {@code clock} 字段，断言它<b>就是</b>
     * {@code Clock.systemDefaultZone()} 的等价物（同一 zone、且 {@code LocalDate.now(clock)}
     * 与 {@code LocalDate.now()} 相等）。
     *
     * <p>为什么不只靠"其余 16 条用例全绿"：那是一份间接证据 —— 那些用例只覆盖当天行为，
     * 而"缝的默认值选错 zone"（例如 {@code Clock.systemUTC()}）在东八区的 00:00~08:00
     * 会算成<b>昨天</b>，恰好把所有当日用例都跑在"昨日"这一侧而仍能通过。故这里直接钉默认值。
     */
    @Test
    @DisplayName("Clock 默认值 = systemDefaultZone（生产行为零变化）")
    void defaultClockIsSystemDefaultZone() throws Exception {
        java.lang.reflect.Field field = RewardEngine.class.getDeclaredField("clock");
        field.setAccessible(true);
        java.time.Clock injected = (java.time.Clock) field.get(engine);

        assertEquals(java.time.ZoneId.systemDefault(), injected.getZone(),
                "clock 的默认 zone 必须是系统默认时区喵 —— 用 UTC 会让东八区玩家在凌晨 "
                        + "00:00~08:00 领到「昨天」的额度，且当日用例仍能通过（假绿）");
        assertEquals(LocalDate.now(), LocalDate.now(injected),
                "默认 clock 下 currentDate() 必须与 LocalDate.now() 返回同一个日期喵");
    }

    /**
     * 测试用可变时钟：内部持一个可前进的 {@link java.time.Instant}。
     *
     * <p>只在测试里存在 —— 生产代码的 Clock 是 {@code Clock.systemDefaultZone()}。
     * zone 取系统默认，使 {@code LocalDate.now(clock)} 与生产口径一致。
     */
    private static final class MutableClock extends java.time.Clock {

        private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> now;
        private final java.time.ZoneId zone = java.time.ZoneId.systemDefault();

        MutableClock(java.time.LocalDateTime start) {
            this.now = new java.util.concurrent.atomic.AtomicReference<>(
                    start.atZone(java.time.ZoneId.systemDefault()).toInstant());
        }

        void advance(java.time.Duration by) {
            now.updateAndGet(i -> i.plus(by));
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId newZone) {
            // 本测试从不需要换 zone；返回自身避免引入第二份可变状态。
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now.get();
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * 真记账的 Vault 桩：维护余额、返回带真实余额的 {@link EconomyResponse}。
     *
     * <p>刻意<b>不</b>用 {@code thenReturn(固定响应)} —— 那样测试只能证明「调用发生过」，
     * 证明不了「转了多少」，而本类的全部判据都是金额。
     */
    private void stubVaultTrackingEconomy() {
        Economy economy = mock(Economy.class);
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(1);
            depositCalls.incrementAndGet();
            depositAmounts.add(amount);
            if (failDeposits) {
                return new EconomyResponse(0.0, vaultBalanceCents.get() / 100.0,
                        EconomyResponse.ResponseType.FAILURE, "simulated refusal");
            }
            long cents = Math.round(amount * 100.0);
            // 只在真的入账时才采信这笔金额 —— 拒付分支不得污染「单笔最大入账」。
            maxDepositCents.accumulateAndGet(cents, Math::max);
            long balance = vaultBalanceCents.addAndGet(cents);
            depositedCents.addAndGet(cents);
            return new EconomyResponse(amount, balance / 100.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(1);
            withdrawCalls.incrementAndGet();
            long balance = vaultBalanceCents.addAndGet(-Math.round(amount * 100.0));
            return new EconomyResponse(amount, balance / 100.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(economy.format(anyDouble()))
                .thenAnswer(inv -> String.format(java.util.Locale.ROOT, "%.2f",
                        inv.<Double>getArgument(0)));
        when(plugin.getEconomy()).thenReturn(economy);
    }

    /**
     * 今日金币计数（分），读真库真源。
     *
     * <p>默认 0 而非 -1：{@code daily_coin_counters} 是 {@code RewardEngine} 懒建的，
     * 「从未发过币」的用例里表可能根本不存在 —— 此时「没有额度占用」与「读失败」对
     * 本类的判据是同一件事（都是 0 占用），不区分。
     */
    private long dbDailyCents(UUID playerId) {
        long[] out = {0L};
        databaseManager.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT cents FROM " + PREFIX
                            + "daily_coin_counters WHERE player_uuid = ? AND coin_date = ?")) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, LocalDate.now().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    out[0] = rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (java.sql.SQLException ignored) {
                // 表尚未懒建：等价于"今日零占用"。
                out[0] = 0L;
            }
            return null;
        });
        return out[0];
    }

    private long dbCoinsEarned(UUID playerId) {
        return dbPlayerDataColumn(playerId, "total_coins_earned");
    }

    private long dbCoinsSpent(UUID playerId) {
        return dbPlayerDataColumn(playerId, "total_coins_spent");
    }

    private long dbPlayerDataColumn(UUID playerId, String column) {
        long[] out = {0L};
        databaseManager.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT " + column + " FROM " + PREFIX + "player_data WHERE uuid = ?")) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    out[0] = rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (java.sql.SQLException ignored) {
                // 表被刻意 DROP 过的用例：调用方断言的是 0（= 没有流水），读失败等价于没有行。
                out[0] = 0L;
            }
            return null;
        });
        return out[0];
    }

    /** 收集日志：断言「流水失败必须可见」，以及「成功路径不该刷 WARNING」的反向对照。 */
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
