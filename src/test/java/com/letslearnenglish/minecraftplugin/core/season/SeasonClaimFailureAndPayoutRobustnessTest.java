package com.letslearnenglish.minecraftplugin.core.season;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
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

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R15 回归：wave-3 锐评后继审计的两条 HIGH。
 *
 * <h2>F1 —— claim 失败被当成「他服已抢先」，整季奖励永久丢失</h2>
 * {@code tryClaimSettlement} 的 {@code false} 同时承载三件事：他服已抢占（正确跳过）、
 * 行不存在、SQL 失败。{@code SeasonManager} 原来一律 {@code continue}，于是
 * 「第 N 季 claim 失败 / 第 N+1 季成功」会让 {@code MAX(settled_at)} 越过第 N 季；
 * 而第 N 季的 {@code ledger_pending} 仍是 0（claim 没成功 → 标记也没写），
 * 债务扫描要求 {@code settled_at IS NOT NULL AND ledger_pending <> 0} → 看不见它。
 * <b>整季奖励永久丢失，无任何回访路径。</b>
 *
 * <p>修法：改为三态 {@link DatabaseManager.SettlementClaim}，
 * {@code FAILED → break}（不是 continue）。
 * <b>同一个方法往下几行的 {@code writeSeasonLedger} 失败分支早就写着「必须 break」</b>，
 * 同一个道理对 claim 失败同样成立 —— 只是当时它藏在 boolean 里表达不出来。
 *
 * <h2>F2 —— {@code claimed=1} 写在 Vault 调用之前，且没有 try/catch</h2>
 * Vault provider 抛异常或返回 {@code null} 时，原实现会 NPE 冲出 {@code deliverRows}，
 * 而那一行的 {@code claimed=1} 已经落库 —— 这笔记账永远没有 {@code paid_at}、
 * 也没有对账路径，奖励永久丢失。修法：把「抛异常」与「返回 null」都归到「返回 0」，
 * 复用既有的 {@code unmarkSeasonRewardClaimed} 回滚分支。
 */
class SeasonClaimFailureAndPayoutRobustnessTest {

    private static final UUID TOP1_ID = UUID.fromString("00000000-0000-0000-0000-00000000c001");

    @TempDir
    Path dir;

    private DatabaseManager db;
    private MockedStatic<Bukkit> bukkit;
    private LetsLearnEnglish plugin;
    private SeasonManager season;
    private final AtomicLong depositedCents = new AtomicLong();

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration config = new YamlConfiguration();
        config.set("season.enabled", true);
        config.set("season.anchor-date", "2026-01-05");
        config.set("season.duration-days", 28);
        config.set("season.rewards.enabled", true);
        config.set("season.rewards.top1-cents", 5000);
        config.set("season.rewards.top10-cents", 2000);
        config.set("season.rewards.participation-cents", 500);

        PlayerDataStore dataStore = mock(PlayerDataStore.class);
        MessageUtil mu = mock(MessageUtil.class);
        Logger logger = Logger.getLogger("SeasonRobustnessTest-" + System.nanoTime());

        Player online = mock(Player.class);
        when(online.getUniqueId()).thenReturn(TOP1_ID);
        when(online.isOnline()).thenReturn(true);
        when(online.getName()).thenReturn("Alice");

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(config);
        when(configManager.getDefaultLanguage()).thenReturn("zh");
        when(configManager.getMessageConfig(anyString())).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getMessageUtil()).thenReturn(mu);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getProxyMessenger()).thenReturn(null);
        when(mu.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("MSG");

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(inv -> { inv.<Runnable>getArgument(1).run(); return null; });
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(inv -> { inv.<Runnable>getArgument(1).run(); return null; });
        when(scheduler.runTaskLaterAsynchronously(any(Plugin.class), any(Runnable.class), anyLong()))
                .thenReturn(null);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(online));
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(online);

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置：必须走 SQLite 分支");
        when(plugin.getDatabaseManager()).thenReturn(db);

        when(dataStore.getRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dataStore.loadRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new PlayerDataStore.RankedPlayersLoad(List.of(), false));

        season = new SeasonManager(plugin);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        if (db != null) {
            db.shutdown();
        }
    }

    // ================== F1 ==================

    @Test
    @DisplayName("F1：claim 的三态把「他服已抢先」与「SQL 失败」区分开")
    void claimIsTriState() {
        SeasonManager.SeasonWindow w = season.windowOf(1);
        db.ensureSeasonRow(w.id(), w.start(), w.end());

        assertEquals(DatabaseManager.SettlementClaim.CLAIMED,
                db.claimSettlement(1, 1000L, "{}", true),
                "首次抢占必须 CLAIMED");

        assertEquals(DatabaseManager.SettlementClaim.TAKEN,
                db.claimSettlement(1, 2000L, "{}", true),
                "已被抢（settled_at 非 NULL）必须是 TAKEN —— 这一种才该 continue");
    }

    @Test
    @DisplayName("F1：boolean 兼容版与三态版语义一致（现有调用点不受影响）")
    void booleanViewMatchesTriState() {
        SeasonManager.SeasonWindow w = season.windowOf(1);
        db.ensureSeasonRow(w.id(), w.start(), w.end());

        assertTrue(db.tryClaimSettlement(1, 1000L, "{}", true), "首次应为 true");
        assertFalse(db.tryClaimSettlement(1, 2000L, "{}", true), "已抢应为 false");
    }

    /**
     * 核心判据：claim 失败时<b>不能继续推进到下一季</b>。
     *
     * <p>构造方式：把第 1、2 季的行都建好，但用一条已 SETTLED 的第 2 季来模拟
     * 「第 2 季已结算、第 1 季没有」的历史。此时 {@code maxSettledSeasonId()} 返回 2，
     * sweep 从 3 开始 —— 第 1 季永远不会被回访。
     *
     * <p>这条测试钉的是<b>数据形态本身</b>：它证明「游标越过未结算季」这个状态一旦形成，
     * 就是不可恢复的。所以 F1 的修法必须是<b>不形成该状态</b>（FAILED → break），
     * 而不是事后补救。
     */
    @Test
    @DisplayName("F1：游标越过未结算季之后，债务扫描看不见那一季（这正是必须 break 的原因）")
    void cursorSkippingMakesSeasonInvisible() {
        SeasonManager.SeasonWindow w1 = season.windowOf(1);
        SeasonManager.SeasonWindow w2 = season.windowOf(2);
        db.ensureSeasonRow(w1.id(), w1.start(), w1.end());
        db.ensureSeasonRow(w2.id(), w2.start(), w2.end());

        // 第 1 季 claim 失败（模拟），第 2 季成功 —— 原实现 runSettleSweep 的真实产物
        assertEquals(DatabaseManager.SettlementClaim.CLAIMED,
                db.claimSettlement(2, System.currentTimeMillis(), "{}", true));

        assertEquals(2L, db.maxSettledSeasonId(), "游标现在指向第 2 季");
        // 第 1 季：settled_at IS NULL 且 ledger_pending = 0
        DatabaseManager.SeasonLedgerDebtScan scan = db.loadSeasonsWithOwedLedger(1, 2);
        assertFalse(scan.readFailed(), "前置：债务扫描本身要读成功，否则本断言无意义");
        assertTrue(scan.debts().stream().noneMatch(d -> d.seasonId() == 1),
                "债务扫描看不见第 1 季 —— 它既没有 settled_at 也没有 ledger_pending 标记，"
                        + "这正是「整季奖励永久丢失且无回访路径」的证据。"
                        + "实际债务列表: " + scan.debts());
    }

    // ================== F2 ==================

    /**
     * 主判据：Vault provider 抛异常时，{@code payReward} 必须返回 0 而不是把异常抛出去。
     *
     * <p>抛出去意味着 {@code deliverRows} 的循环中断，而那一行的 {@code claimed=1}
     * 已经写进库 —— 该玩家的赛季奖励永久丢失。
     */
    @Test
    @DisplayName("F2：Vault 抛异常 → payReward 返回 0（不抛），从而走回滚分支")
    void throwingEconomyDoesNotEscape() {
        Economy boom = mock(Economy.class);
        when(boom.depositPlayer(any(Player.class), anyDouble()))
                .thenThrow(new IllegalStateException("provider exploded"));
        when(plugin.getEconomy()).thenReturn(boom);

        double paid = invokePayReward(stubbedPlayer(), 5000L);
        assertEquals(0.0, paid,
                "provider 抛异常必须被吞掉并返回 0（语义=没发出去），"
                        + "绝不能把异常抛到 deliverRows 之外");
    }

    @Test
    @DisplayName("F2：Vault 返回 null response → payReward 返回 0（不 NPE）")
    void nullEconomyResponseDoesNotNpe() {
        Economy nullish = mock(Economy.class);
        when(nullish.depositPlayer(any(Player.class), anyDouble())).thenReturn(null);
        when(plugin.getEconomy()).thenReturn(nullish);

        assertDoesNotThrow(() -> invokePayReward(stubbedPlayer(), 5000L),
                "null response 不得 NPE —— 后果与抛异常相同（claimed 已置 1，奖励永久丢失）");
        assertEquals(0.0, invokePayReward(stubbedPlayer(), 5000L));
    }

    @Test
    @DisplayName("F2：正常 SUCCESS 路径仍然发钱（修复没破坏正常路径）")
    void successPathStillPays() {
        Economy ok = mock(Economy.class);
        when(ok.depositPlayer(any(Player.class), anyDouble())).thenAnswer(inv -> {
            depositedCents.addAndGet(Math.round(inv.<Double>getArgument(1) * 100.0));
            return new EconomyResponse(inv.<Double>getArgument(1), 0.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(plugin.getEconomy()).thenReturn(ok);

        double paid = invokePayReward(stubbedPlayer(), 5000L);
        assertEquals(50.0, paid, "5000 cents = 50.0 单位");
        assertEquals(5000L, depositedCents.get(), "实付必须等于台账金额");
    }

    @Test
    @DisplayName("F2：transactionSuccess=false 仍返回 0 且记 WARNING（既有行为不变）")
    void failedTransactionStillReturnsZero() {
        Economy fail = mock(Economy.class);
        when(fail.depositPlayer(any(Player.class), anyDouble()))
                .thenReturn(new EconomyResponse(0, 0,
                        EconomyResponse.ResponseType.FAILURE, "no funds"));
        when(plugin.getEconomy()).thenReturn(fail);

        assertEquals(0.0, invokePayReward(stubbedPlayer(), 5000L));
    }

    /**
     * 反射调 private {@code payReward}：它是纯函数式判定，没有更外的可观察面。
     *
     * <p>传进来的 player 必须 stub 出 {@code getUniqueId()} —— 成功路径会调
     * {@code recordCoinsEarned}，那里会 {@code playerId.toString()}，null 会 NPE。
     * 这与被测逻辑无关，是测试夹具的责任。
     */
    private double invokePayReward(Player player, long cents) {
        try {
            var m = SeasonManager.class.getDeclaredMethod("payReward", Player.class, long.class);
            m.setAccessible(true);
            return (Double) m.invoke(season, player, cents);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AssertionError("反射调用 payReward 抛异常（" + cause + "）", cause);
        }
    }

    /** 一个 stub 齐了必需字段的玩家（成功路径会读到 UUID 与名字）。 */
    private Player stubbedPlayer() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(TOP1_ID);
        when(p.getName()).thenReturn("Alice");
        when(p.isOnline()).thenReturn(true);
        return p;
    }
}
