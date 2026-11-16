package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;

/**
 * <b>已知偏差的记录</b>：{@code player_data.total_coins_earned} 只记「整币」，
 * 亚币奖励的钱进了 Vault、却不进 lifetime 流水。
 *
 * <h2>缺陷本体</h2>
 * {@link DatabaseManager#recordCoinsEarned} → {@code adjustCoins} 用
 * {@code stmt.setLong(2, Math.round(amount))} 写 {@code total_coins_earned}
 * （{@code BIGINT}）。而 {@code RewardEngine.clampToDailyCap} 是按<b>分</b>截断的
 * （{@code payCents / 100.0}），日限只剩 0.20 元时真的会把 {@code 0.20} 发给 Vault。
 * 于是这一笔：<b>钱到了玩家手上，流水记 0</b>。
 *
 * <h2>本类刻意<b>不</b>断言「现状是对的」</h2>
 * <p>按本仓 {@code DailyCapCrossServerTest} 的约定：缺陷写成 {@code @Disabled} +
 * <b>期望</b>断言，摘掉注解即红。绝不写「现状 = 0 就是正确」的绿测试 ——
 * 那会把一个已知偏差固化成契约，将来想修的人得先推翻一条测试。
 *
 * <p>因此本类的划分是：
 * <ul>
 *   <li>{@link #wholeCoinRewardsAreRecordedExactly()} —— <b>绿的</b>。它钉的是
 *       「默认配置下（全是整币）流水是准的」这一<b>仍然成立、且修复后仍须成立</b>的
 *       事实。它是本类的前提：偏差只在亚币场景出现，不是「流水整体坏了」。</li>
 *   <li>{@link #subCoinRewardsAreLostFromTheLifetimeLedger()} —— <b>@Disabled</b>。
 *       断言的是<b>期望</b>（流水应等于真钱），一旦修复就摘掉注解当回归用。</li>
 * </ul>
 *
 * <h2>为什么用真 SQLite</h2>
 * <p>{@code Math.round} 的行为当然是纯函数，可单测；但<b>缺陷的可见形态</b>是
 * 「{@code BIGINT} 列被 setLong 写入后读回来是多少」。用 mock 断言
 * {@code setLong(2, 0)} 只能证明「我们以为它写了 0」，而真库能证明
 * 「确实丢了、而且丢的方向是少」——本仓已有过「mock 通过、真库语义不同」的先例。
 */
class CoinLedgerRoundingTest {

    private static final String PREFIX = "lle_";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000c01e01");

    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;

    private DatabaseManager db;

    @BeforeAll
    static void createMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        Logger quiet = Logger.getLogger("CoinLedgerRoundingTest");
        quiet.setUseParentHandlers(false);
        when(plugin.getLogger()).thenReturn(quiet);
    }

    @BeforeEach
    void initRealDatabase(@TempDir Path dir) {
        FileConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "coin-ledger-rounding.db");
        config.set("database.mysql.table-prefix", PREFIX);
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则本类的真库断言无意义");
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // 绿：前提仍然成立（默认配置全是整币 → 流水准确）
    // ==================================================================

    /**
     * 默认配置实发额全是整币（答对 10 / 会话 50+25 / 挑战 30-100 / 签到 10-100 /
     * 赛季 500-5000），{@code Math.round} 不引入任何偏差。
     *
     * <p>这条<b>修复后也必须保持绿</b>：任何修法都不能把现在准确的整币流水搞坏。
     * 它同时是「偏差只在亚币场景」这一判断的证据 —— 否则本类记录的就不是一个
     * 边界缺陷，而是「流水整体不可信」。
     */
    @Test
    @DisplayName("默认整币奖励（10/15/20/25/30）→ lifetime 流水与实发额逐分相符")
    void wholeCoinRewardsAreRecordedExactly() {
        double[] each = {10.0, 15.0, 20.0, 25.0, 30.0};
        double realPaid = 0;
        for (double amount : each) {
            realPaid += amount;
            assertTrue(db.recordCoinsEarned(PLAYER, amount), "整币写入必须成功");
        }

        assertEquals(Math.round(realPaid), db.getCoinsEarned(PLAYER),
                "整币奖励的 lifetime 流水必须与 Vault 实发额完全一致喵 —— 若有偏差，"
                        + "说明问题比「亚币四舍五入」严重得多（影响的就不只是边界场景了）");
        assertEquals(100L, db.getCoinsEarned(PLAYER),
                "10+15+20+25+30 = 100（整币，Math.round 不改变任何一笔）");
    }

    // ==================================================================
    // 红（@Disabled）：缺陷本体 —— 期望行为，摘掉注解即红
    // ==================================================================

    /**
     * <b>缺陷证明</b>：日限截断产出的亚币奖励，钱进了 Vault 但不进 lifetime 流水。
     *
     * <h2>为什么这是真实可达的（不是构造出来的边缘值）</h2>
     * <p>{@code money.daily-cap} 默认 500，{@code RewardEngine.clampToDailyCap} 按分
     * 计算剩余额度并返回 {@code payCents / 100.0}。玩家今天已得 499.80 时再答对一题，
     * 实发额就是 {@code 0.20}。这不是手滑配置，是**默认配置下的正常结局**。
     *
     * <h2>影响面（实测，不是推测）</h2>
     * <ul>
     *   <li>三次 {@code 0.20}：Vault 实付 0.60，流水记 0（偏差 -0.60）；</li>
     *   <li>七次 {@code 14.28}（提前停止的按比例结算产物）：Vault 实付 99.96，
     *       流水记 98（偏差 -1.96）；</li>
     *   <li>偏差方向恒为「少」（{@code Math.round} 对 0.20 得 0、对 14.28 得 14），
     *       于是 {@code COINS_500}/{@code COINS_5000} 成就、排行榜
     *       {@code getTopCoins}、PAPI {@code total_coins_earned} 全部系统性偏低。</li>
     * </ul>
     *
     * <p><b>注意偏差不总是负数</b>：{@code Math.round(0.66) = 1}（偏高）。
     * 但高位截断产出的分布集中在 {@code < 0.5} 区间，且每笔独立取整的期望偏差是
     * 有偏的（不是随机抵消）—— 所以是「系统性偏低」而非「随机误差」。
     *
     * <p><b>修复后</b>：把 {@code adjustCoins} 改成存分（{@code BIGINT} 存 cents，
     * 读侧除以 100）或引入 per-player 余数进位后，摘掉 {@code @Disabled} 即可当回归用。
     * 届时 {@link #wholeCoinRewardsAreRecordedExactly()} 可能也要跟着改口径（存分后
     * {@code getCoinsEarned} 若仍返回「元」则无需改）。
     */
    @Test
    @org.junit.jupiter.api.Disabled("已知偏差：total_coins_earned 是 BIGINT + Math.round(amount)，"
            + "亚币奖励（日限截断产物）计入 Vault 但不计入 lifetime 流水。修复（改存分或"
            + "余数进位）后摘掉本注解即可当回归用")
    @DisplayName("缺陷记录：三次 0.20 金币实付 0.60，lifetime 流水只记 0")
    void subCoinRewardsAreLostFromTheLifetimeLedger() {
        // 日限只剩 0.60 时的三次答对：每次实发 0.20（clampToDailyCap 的分级截断产物）。
        double[] partialPayouts = {0.20, 0.20, 0.20};
        double realPaidToVault = 0;
        for (double amount : partialPayouts) {
            realPaidToVault += amount;
            assertTrue(db.recordCoinsEarned(PLAYER, amount), "前置：写库本身必须成功");
        }

        // 期望：流水应该等于真正付出去的钱。
        assertEquals(Math.round(realPaidToVault * 100.0) / 100.0, db.getCoinsEarned(PLAYER),
                "lifetime 流水必须与 Vault 实发额一致喵。当前实发 0.60 却记 0 —— 这不是"
                        + "「四舍五入的微小误差」，是整整三笔钱从统计里消失。"
                        + "这个断言现在会红，红得就是这条已知偏差本身。");
    }
}
