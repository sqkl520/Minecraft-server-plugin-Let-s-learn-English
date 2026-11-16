package com.letslearnenglish.minecraftplugin.core.season;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
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

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-09-01 回归测试：赛季结算的「台账写失败 → 释放抢占 → 下一轮 sweep 重试」回退语义。
 *
 * <h2>为什么需要这个类</h2>
 * {@code SeasonManager} 的 {@code writeSeasonLedger} / {@code releaseSettlement} /
 * {@code runSettleSweep} 里那段约 106 行的结算回退逻辑，在 {@code src/test} 里的
 * 引用数是 <b>0</b>。这段逻辑守护的是一条真实资产缺陷：{@code tryClaimSettlement} 先
 * 推进游标（{@code MAX(season_id) WHERE settled_at IS NOT NULL}），台账写失败被吞掉时
 * 游标已越过该季 → 该季奖励永久丢失且永不回访。
 *
 * <h2>被本类封死的变异（每条断言都要能红）</h2>
 * <ol>
 *   <li><b>删掉 {@code releaseSettlement(s, claimedAt)}</b> →
 *       {@code ledgerFailureReleasesTheClaim} 红（{@code settled_at}
 *       仍非 NULL，游标不后退）。</li>
 *   <li><b>把 {@code break} 改成 {@code continue}</b> →
 *       {@code ledgerFailureStopsTheSweepInsteadOfContinuing} 红
 *       （第 2、3 季会照常 claim 成功，{@code MAX(...)} 一跳就再也回不来）。</li>
 *   <li><b>把 {@code ledgered >= rows.size()} 改成 {@code ledgered >= 0}（恒真）</b> →
 *       {@code partialWriteIsVisibleAsFailure} 红（部分成功被当成成功）。</li>
 *   <li><b>把 {@code rows.isEmpty()} 的 {@code return true} 改成 {@code return false}</b> →
 *       {@code noRowsMeansNothingToWriteAndDoesNotBlockSettlement} 红。</li>
 *   <li><b>把 {@code isRewardsEnabled()} 短路删掉</b> →
 *       {@code rewardsDisabledWritesNoLedgerAndReleasesNothing} 红。</li>
 *   <li><b>把 {@code releaseSettlement} 的条件 UPDATE 改成无条件 UPDATE</b>（丢掉
 *       {@code AND settled_at = ?}）→ {@code releaseOnlyClearsThisSweepsOwnClaim}
 *       红（他服接手后的抢占被误清）。</li>
 * </ol>
 *
 * <h2>为什么用真 SQLite 而不是纯 mock</h2>
 * 被保护的核心是「<b>库里实际有几行</b>」这条判据，以及 {@code tryClaimSettlement} /
 * {@code releaseSettlement} 的条件 UPDATE 的<b>真实影响行数</b>。mock 出来的
 * {@code executeUpdate() == 1} 是"我们假设条件 SQL 命中"，不是"它命中"——
 * 把 {@code AND settled_at = ?} 删掉、把 {@code WHERE} 整段删掉，mock 一样返回 1。
 * 真库还顺带覆盖 {@code MIN/MAX(season_id)} 游标（缺陷本体）与
 * {@code INSERT OR IGNORE} 的主键幂等。
 *
 * <p>故障注入用 SQLite 触发器（{@code RAISE(ABORT)} / {@code RAISE(IGNORE)}），
 * 因为它能<b>按 season_id / 名次精确限定</b>——「第 1 季失败、第 2 季成功」这种序列
 * 用 {@code PRAGMA query_only} 做不到（那是全库粒度，连 claim 都会被挡）。
 *
 * <p><b>纯 JVM</b>：调度器两个入口的内联 mock（{@code runTask} /
 * {@code runTaskAsynchronously} 就地执行）驱动 {@code settleIfNeededAsync()} 这条
 * <b>生产入口</b>，不反射 {@code runSettleSweep}；只有 {@code writeSeasonLedger} /
 * {@code releaseSettlement} 这两个无外部入口的私有方法走反射直调（它们可靠的
 * 观测点只有返回值/库状态，绕不开）。
 */
class SeasonSettlementRollbackTest {

    /** 与 config 的 {@code database.mysql.table-prefix} 同源（SQLite 也生效）。 */
    private static final String PREFIX = "lle_";
    private static final int DURATION_DAYS = 30;

    private static final UUID TOP1_ID = UUID.fromString("00000000-0000-0000-0000-00000000ab01");
    private static final UUID TOP2_ID = UUID.fromString("00000000-0000-0000-0000-00000000ab02");
    private static final UUID TOP3_ID = UUID.fromString("00000000-0000-0000-0000-00000000ab03");

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration config;
    private DatabaseManager db;
    private PlayerDataStore dataStore;
    private MessageUtil messageUtil;
    private Player onlinePlayer;
    private Logger logger;
    private CapturingHandler handler;
    private MockedStatic<Bukkit> bukkit;
    private SeasonManager manager;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        dataStore = mock(PlayerDataStore.class);
        messageUtil = mock(MessageUtil.class);
        onlinePlayer = mock(Player.class);

        logger = Logger.getLogger("SeasonSettlementRollbackTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);

        config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "season-settlement.db");
        config.set("database.mysql.table-prefix", PREFIX);
        config.set("season.enabled", true);
        config.set("season.duration-days", DURATION_DAYS);
        config.set("season.announce", true);
        config.set("season.rewards.enabled", true);
        // 单位"元"：top1 50 元 / 2-3 名 20 元 / 4-10 名 5 元 / 参与奖 0（关掉参与奖，
        // 让台账行数只由 top 决定，断言的可数性最强）。
        config.set("season.rewards.top1", 50.0);
        config.set("season.rewards.top3", 20.0);
        config.set("season.rewards.top10", 5.0);
        config.set("season.rewards.participated", 0.0);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(plugin.getLogger()).thenReturn(logger);
        // 无 Vault：结算后的发放段静默降级（本类不测发钱，只测结算/台账/抢占）。
        when(plugin.getEconomy()).thenReturn(null);
        when(plugin.getProxyMessenger()).thenReturn(null);
        when(onlinePlayer.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000ee01"));
        when(onlinePlayer.isOnline()).thenReturn(true);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(inv -> "MSG:" + inv.getArgument(1));

        // 调度器就地执行：settleIfNeededAsync 的"异步 sweep"与 runMain 的广播段都在
        // 本线程跑完，断言时刻确定（真线程池会让时序不可控）。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        // R11-P：释放失败的后台重试走 runTaskLaterAsynchronously。这里**不执行** ——
        // 延迟重试若就地跑会在同一调用栈里把待重试表清空，测不到「释放失败后游标被钉住」
        // 这个状态；需要驱动它时用例里显式调 manager.drainPendingReleases()。
        when(scheduler.runTaskLaterAsynchronously(any(Plugin.class), any(Runnable.class), anyLong()))
                .thenReturn(null);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(onlinePlayer));

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则触发器故障注入无效喵");
        when(plugin.getDatabaseManager()).thenReturn(db);

        manager = new SeasonManager(plugin);
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

    // ==================================================================
    // 行为 1：台账写成功 → 不释放抢占，lastSettledId 推进，广播照常
    // ==================================================================

    @Test
    @DisplayName("台账写成功 → settled_at 保留（未被释放）、台账行落库、结束广播照常发出")
    void ledgerSuccessKeepsTheClaimAndStillBroadcasts() {
        useAnchorDaysAgo(31); // currentSeasonId = 2 → 只结算第 1 季
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);

        manager.settleIfNeededAsync();

        assertNotNull(settledAt(1),
                "台账写成功后绝不释放抢占喵。这里为 NULL 说明 releaseSettlement 被无条件调用"
                        + "（或被错误地当成失败分支）—— 下一轮 sweep 会重写台账、广播重发，"
                        + "且台账行已 claimed 时还会重复发钱。");
        assertNotNull(topSnapshot(1), "抢占时写入的 top_snapshot 应保留（释放会把它清成 NULL）喵");
        assertEquals(1, rewardRowCount(1),
                "第 1 季台账必须有 1 行（top1 50 元 > 0）喵");
        verify(messageUtil, atLeastOnce()).getPlayerMessage(
                same(onlinePlayer), eq("season.ended-broadcast"), any(String[].class));
        assertFalse(handler.hasSevere(), "成功路径不应有 SEVERE 喵：" + handler.describe());
    }

    // ==================================================================
    // 行为 2：台账写失败 → 释放抢占 + break
    // ==================================================================

    @Test
    @DisplayName("台账写失败 → 释放抢占（settled_at 退回 NULL），游标不前进，不发结束广播")
    void ledgerFailureReleasesTheClaim() {
        useAnchorDaysAgo(31);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);
        // 故障注入：只拦第 1 季的台账 INSERT。claim 走的是 seasons 表，不受影响。
        exec("CREATE TRIGGER fail_ledger_s1 BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " WHEN NEW.season_id = 1 BEGIN SELECT RAISE(ABORT, 'injected ledger failure'); END");

        manager.settleIfNeededAsync();

        assertNull(settledAt(1),
                "台账写失败必须释放本次抢占（settled_at 退回 NULL）喵 —— 否则游标 = MAX(season_id)"
                        + " WHERE settled_at IS NOT NULL 已越过本季，该季奖励永久丢失且永不回访"
                        + "（这正是 F-09-01 要修的真实资产缺陷）。诊断：" + diagnostics(1));
        assertNull(topSnapshot(1), "释放必须连 top_snapshot 一起清（下一轮会重新聚合）喵");
        assertNull(db.maxSettledSeasonId(),
                "释放后游标必须仍是「无已结算季」喵 —— 非 NULL 意味着下一轮 sweep 的起点被永久跳过。");
        assertEquals(0, rewardRowCount(1), "写入被拒时台账必须为 0 行喵");
        verify(messageUtil, never()).getPlayerMessage(
                any(Player.class), eq("season.ended-broadcast"), any(String[].class));
        assertTrue(handler.hasSevere(), "台账确认失败必须留 SEVERE（否则失败与「没结算」不可区分）喵："
                + handler.describe());
    }

    @Test
    @DisplayName("第 1 季台账失败 → break 而不是 continue：第 2、3 季绝不能被 claim（游标不许跳过失败季）")
    void ledgerFailureStopsTheSweepInsteadOfContinuing() {
        useAnchorDaysAgo(100); // currentSeasonId = 4 → 待结算 1..3 三季
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        exec("CREATE TRIGGER fail_ledger_s1 BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " WHEN NEW.season_id = 1 BEGIN SELECT RAISE(ABORT, 'injected ledger failure'); END");

        manager.settleIfNeededAsync();

        assertNull(settledAt(1), "第 1 季必须被释放喵。诊断：" + diagnostics(1));
        assertNull(settledAt(2),
                "第 1 季失败后必须 break，第 2 季连 claim 都不许发生喵。"
                        + "若这里非 NULL，说明 break 被改成了 continue —— 后续季照常 claim 会把"
                        + " settled_at 推到失败季之上，MAX(...) 游标一跃就再也回不来，"
                        + " 第 1 季的奖励从此永久丢失（F-09-01 缺陷复现）。");
        assertNull(settledAt(3), "同上：第 3 季也不许被 claim 喵");
        assertNull(db.maxSettledSeasonId(),
                "三季全未结算 → 游标必须仍是「无已结算季」喵。非 NULL 就是跳过失败季的直接证据。");
        assertEquals(0, rewardRowCount(2), "第 2 季不该有台账行喵");
        assertEquals(0, rewardRowCount(3), "第 3 季不该有台账行喵");
    }

    // ==================================================================
    // 行为 3：奖励关闭 → 不写台账、也不释放
    // ==================================================================

    @Test
    @DisplayName("season.rewards.enabled=false → 不调 writeSeasonLedger、不释放抢占，结算照常推进")
    void rewardsDisabledWritesNoLedgerAndReleasesNothing() {
        useAnchorDaysAgo(31);
        config.set("season.rewards.enabled", false);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);
        // 双保险：任何对 season_rewards 的写入都会失败。若奖励门被绕过（照常写台账），
        // 写失败会触发释放 → settled_at 变 NULL → 下面两条断言同时红。
        exec("CREATE TRIGGER fail_any_ledger BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " BEGIN SELECT RAISE(ABORT, 'rewards disabled: ledger must not be touched'); END");

        manager.settleIfNeededAsync();

        assertNotNull(settledAt(1),
                "奖励关闭时无台账义务，抢占绝不能被释放喵（释放会让下一轮 sweep 重做一次无意义的结算）。");
        assertEquals(0, rewardRowCount(1),
                "奖励关闭时不得写任何台账行喵 —— 非 0 说明 isRewardsEnabled() 的短路被绕过，"
                        + "玩家会在'本赛季不发奖'的配置下拿到钱。");
        verify(messageUtil, atLeastOnce()).getPlayerMessage(
                same(onlinePlayer), eq("season.ended-broadcast"), any(String[].class));
        assertFalse(handler.hasSevere(), "奖励关闭是正常配置，不该有 SEVERE 喵：" + handler.describe());
    }

    // ==================================================================
    // 行为 4/5：writeSeasonLedger 自身的判据（回读计数 / 空行）
    // ==================================================================

    @Test
    @DisplayName("回读计数 == 期望行数 → true")
    void fullWriteIsReportedAsSuccess() throws Exception {
        useAnchorDaysAgo(31);
        List<LeaderboardEntry> entries = top(
                top(TOP1_ID, "Alice", 1000L, 1).get(0),
                top(TOP2_ID, "Bob", 900L, 2).get(0),
                top(TOP3_ID, "Carol", 800L, 3).get(0));

        boolean result = invokeWriteSeasonLedger(1, entries);

        assertTrue(result, "三行全部落库时必须返回 true（否则结算方会无限释放/重试）喵");
        assertEquals(3, rewardRowCount(1), "三行都该在台账里喵");
        assertFalse(handler.hasSevere(), "成功路径不该有 SEVERE 喵：" + handler.describe());
    }

    @Test
    @DisplayName("回读计数 < 期望行数（部分成功）→ false（RAISE(IGNORE) 让语句不报错但少一行）")
    void partialWriteIsVisibleAsFailure() throws Exception {
        useAnchorDaysAgo(31);
        List<LeaderboardEntry> entries = top(
                top(TOP1_ID, "Alice", 1000L, 1).get(0),
                top(TOP2_ID, "Bob", 900L, 2).get(0),
                top(TOP3_ID, "Carol", 800L, 3).get(0));
        // 关键注入：RAISE(IGNORE) 不会抛异常，只是"这一行没写进去"。
        // 任何"看返回值/看有没有抛异常"的判据都看不见它 —— 只有回读计数看得见。
        exec("CREATE TRIGGER skip_rank3 BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " WHEN NEW.season_id = 1 AND NEW.rank_position = 3 BEGIN SELECT RAISE(IGNORE); END");

        boolean result = invokeWriteSeasonLedger(1, entries);

        assertFalse(result,
                "回读只有 2 行、期望 3 行时必须返回 false 喵。恒 true（或 ledgered >= 0）就是修复前的"
                        + "形状：部分成功被当成完整成功 → 游标前进 → 少掉的那一行奖励永久不发。");
        assertEquals(2, rewardRowCount(1), "前置：注入确实只漏了 1 行（否则本用例的判据不成立）喵");
        assertTrue(handler.describe().contains("2 of 3 rows"),
                "SEVERE 必须报出实际/期望行数，运维才能判断差几行喵。实际日志：" + handler.describe());
    }

    @Test
    @DisplayName("零额行 → rows.isEmpty() → true，且不碰数据库（无台账可写不阻结算）")
    void noRowsMeansNothingToWriteAndDoesNotBlockSettlement() throws Exception {
        useAnchorDaysAgo(31);
        config.set("season.rewards.top1", 0.0);
        config.set("season.rewards.top3", 0.0);
        config.set("season.rewards.top10", 0.0);
        config.set("season.rewards.participated", 0.0);
        // 任何写入都会被拒：若 isEmpty 的早退被改成 return false（或干脆删掉早退），
        // 返回值就不再是 true。
        exec("CREATE TRIGGER fail_any_ledger BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " BEGIN SELECT RAISE(ABORT, 'no ledger expected'); END");

        boolean result = invokeWriteSeasonLedger(1, top(TOP1_ID, "Alice", 1000L, 1));

        assertTrue(result,
                "零额行 = 无台账可写，必须返回 true（不阻结算）喵。改成 false 会让每一季都"
                        + "白释放一次抢占、永不推进 —— 配置成「只结算不发奖」的服会卡死。");
        assertEquals(0, rewardRowCount(1), "零额行不该写进台账（无钱无消息）喵");
    }

    // ==================================================================
    // 释放抢占的自身语义（claimedAt 的作用 / 释放失败可见）
    // ==================================================================

    @Test
    @DisplayName("releaseSettlement 的条件 UPDATE 只清本次 claim：时间戳不匹配（他服接手）时不清")
    void releaseOnlyClearsThisSweepsOwnClaim() throws Exception {
        useAnchorDaysAgo(31);
        seedSeasons(1);
        long ourClaim = 111L;
        long otherServersClaim = 222L;
        assertTrue(db.tryClaimSettlement(1, otherServersClaim, "{\"owner\":\"other\"}", true),
                "前置：他服已接手本季的抢占喵");

        invokeReleaseSettlement(1, ourClaim);

        assertEquals(otherServersClaim, settledAt(1),
                "settled_at 不是本次 claim 的时间戳时必须不动喵 —— 无条件 UPDATE 会把他服正在"
                        + "结算的抢占清掉，两服各写一次台账、各发一次钱。");
        assertEquals("{\"owner\":\"other\"}", topSnapshot(1), "top_snapshot 同样不许被误清喵");

        invokeReleaseSettlement(1, otherServersClaim);

        assertNull(settledAt(1), "时间戳匹配时必须真正释放喵（否则重试路径整个失效）");
    }

    @Test
    @DisplayName("释放无行匹配（他服已接手 / 已被后台 drain 释放）→ 不抛异常，"
            + "记一条带 seasonId + 手工修法的 WARNING（多服共享库的正常结局，不是故障）")
    void releaseFailureIsVisibleNotThrown() throws Exception {
        useAnchorDaysAgo(31);
        seedSeasons(1);
        // 没有 claim 就释放：条件 UPDATE affected=0 → NOT_HELD。
        invokeReleaseSettlement(1, 999L);

        assertTrue(handler.hasWarning("release did NOT take effect"),
                "释放无行匹配必须留 WARNING（含稳定短语 'release did NOT take effect'）喵 ——"
                        + " 它决定「下一轮会不会重试」这个关键结论，静默失败会让日志看起来像"
                        + "「已经修好了」。实际日志：" + handler.describe());
        assertFalse(handler.hasSevere(),
                "这条必须是 WARNING 而不是 SEVERE 喵 —— 多服共享库时 NOT_HELD 是正常结局"
                        + "（读之后、写之前他服重新抢走，或后台 drain 已释放）。用 SEVERE 会把"
                        + "运维的分贝预算耗在正常事件上，真出事时看不见。实际日志：" + handler.describe());
        String log = handler.describe();
        assertTrue(log.contains("season_id = 1"),
                "WARNING 必须给出可直接粘贴的手工修法（UPDATE ... WHERE season_id = 1）喵：" + log);
        assertTrue(log.contains("release did NOT take effect"),
                "措辞要与 writeSeasonLedger 的告警对得上（后者让运维去看这一行）喵：" + log);
        assertTrue(log.contains("no retry is possible or useful"),
                "必须写明「重试不可能也没用」喵 —— 否则运维会等一个永远不会来的重试：" + log);
    }

    // ==================================================================
    // R11-P：释放失败可重试（修复前的缺陷：release 失败只打一条 SEVERE 就永久丢奖）
    //
    // 缺陷链条：claim 成功（游标 MAX(season_id) WHERE settled_at IS NOT NULL 已推进）
    // → 台账写失败 → releaseSettlement 失败（DB 抖动/断连）→ settled_at 仍非 NULL
    // → 游标跳过该季 → 该季奖励永久丢失。R5-eh-09-01 只补了日志，没补重试面。
    // ==================================================================

    @Test
    @DisplayName("R11-P：释放失败 → 同轮退避重试 3 次 + 登记待重试 + 后季被挡住（不再永久丢奖）")
    void releaseFailureIsRetriedAndBlocksLaterSeasons() {
        useAnchorDaysAgo(100); // currentSeasonId = 4 → 待结算 1..3
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        blockRelease();
        // 台账写失败是进入释放分支的入口（注入只拦第 1 季）
        exec("CREATE TRIGGER fail_ledger_s1 BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " WHEN NEW.season_id = 1 BEGIN SELECT RAISE(ABORT, 'injected ledger failure'); END");

        manager.settleIfNeededAsync();

        String log = handler.describe();
        assertEquals(3, count(log, "could not be APPLIED"),
                "库不可用导致的释放失败必须在同一轮 sweep 内重试（RELEASE_ATTEMPTS=3 次）喵 —— "
                        + "只试 1 次就是修复前的形状：DB 抖动一下，该季奖励就永久丢失。实际日志：" + log);
        assertEquals(2, count(log, "retrying in"),
                "3 次尝试之间应有 2 次退避重试（日志带毫秒数，运维可确认不是忙等）喵：" + log);
        assertTrue(log.contains("release FAILED after 3 attempts"),
                "重试耗尽必须留一条明确的「彻底失败」记录（不是静默通过）喵：" + log);
        assertTrue(log.contains("cursor is held at season 1"),
                "必须写明游标被钉在第 1 季 —— 这是「奖励没丢」的唯一凭据喵：" + log);
        assertNotNull(settledAt(1), "前置：注入生效，settled_at 仍是脏 claim（释放确实没成功）喵");
        assertNull(settledAt(2),
                "第 1 季释放失败后，第 2 季绝不能被 claim 喵 —— 非 NULL 就是游标跳过失败季的直接证据"
                        + "（F-09-01 缺陷复现）。诊断：" + diagnostics(1));
        assertNull(settledAt(3), "同上：第 3 季也不许被 claim 喵");
        assertEquals(0, rewardRowCount(2), "被挡住时第 2 季不该有台账行喵");
    }

    @Test
    @DisplayName("R11-P：下一轮释放成功 → 游标回退并真正结算失败季，后续季照常推进（不被永久卡住）")
    void releaseSuccessOnTheNextSweepSettlesTheBlockedSeason() {
        useAnchorDaysAgo(100); // 待结算 1..3
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        blockRelease();
        exec("CREATE TRIGGER fail_ledger_s1 BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " WHEN NEW.season_id = 1 BEGIN SELECT RAISE(ABORT, 'injected ledger failure'); END");

        manager.settleIfNeededAsync(); // 第 1 轮：释放失败，游标被钉在第 1 季
        assertNotNull(settledAt(1), "前置：第 1 轮释放失败，脏 claim 留在库里喵");
        assertNull(settledAt(2), "前置：第 1 轮后第 2 季不得被 claim 喵");
        assertEquals(1, pendingReleaseCount(),
                "前置：第 1 轮必须把「待重试」登记下来（这是游标被钉住的唯一凭据）喵");

        handler.clearAll(); // 只断言「第 2 轮」的日志
        clearInjectedFaults(); // 故障源修复（DB 恢复）

        manager.settleIfNeededAsync(); // 第 2 轮

        assertEquals(1, rewardRowCount(1),
                "第 2 轮必须把第 1 季真正结算掉：台账 1 行（top1 50 元）喵。为 0 说明释放成功"
                        + "但游标没有回退到第 1 季（hold-back 缺失 → 该季永不回访 = 奖励永久丢失）。"
                        + "诊断：" + diagnostics(1));
        assertNotNull(settledAt(1), "第 1 季重新 claim 成功（settled_at 非 NULL）喵");
        assertNotNull(settledAt(2),
                "第 1 季解开后第 2 季必须能正常结算（游标不被卡住 —— 重试机制不能把整条链堵死）喵。"
                        + "诊断：" + diagnostics(2));
        assertEquals(1, rewardRowCount(2), "第 2 季台账也应有 1 行喵");
        assertTrue(handler.describe().contains("Season settled: 1"),
                "第 1 季的结算成功日志必须出现喵：" + handler.describe());
        assertEquals(0, pendingReleaseCount(),
                "第 2 轮释放成功后待重试条目必须被清掉喵 —— 留着会让游标永远被钉在这里。"
                        + "诊断：" + handler.describe());
    }

    @Test
    @DisplayName("R11-P：后台 drain 无需等下一轮 sweep 就能把失败的释放补上")
    void backgroundDrainReleasesWithoutWaitingForTheNextSweep() {
        useAnchorDaysAgo(31);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);
        blockRelease();
        exec("CREATE TRIGGER fail_ledger_s1 BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " WHEN NEW.season_id = 1 BEGIN SELECT RAISE(ABORT, 'injected ledger failure'); END");

        manager.settleIfNeededAsync(); // 释放失败，条目进待重试表
        assertNotNull(settledAt(1), "前置：第 1 季释放失败喵");
        assertEquals(1, pendingReleaseCount(), "前置：待重试表必须有 1 条（season 1）喵");

        clearInjectedFaults();
        manager.drainPendingReleases(); // 包级可见：直接驱动后台重试，不经调度器

        assertNull(settledAt(1),
                "后台重试成功后必须把 settled_at 退回 NULL 喵 —— 仍非 NULL 说明 drain 没生效，"
                        + "下一轮 sweep 依旧会跳过本季。诊断：" + diagnostics(1));
        assertEquals(0, pendingReleaseCount(),
                "释放已生效后待重试条目必须清掉（否则游标被永久挡住）喵");
        assertTrue(handler.describe().contains("released by a background retry"),
                "后台补释放必须留 INFO（运维要能解释 settled_at 为何变回 NULL）喵："
                        + handler.describe());

        manager.settleIfNeededAsync();
        assertEquals(1, rewardRowCount(1), "释放生效后下一轮 sweep 必须真正结算本季喵");
        assertNotNull(settledAt(1), "前置：本季重新 claim 成功喵");
    }

    @Test
    @DisplayName("R11-P：彻底失败后下一轮 sweep 仍不越过失败季（hold-back 可见，不是静默通过）")
    void exhaustedReleaseStillBlocksTheFollowingSweep() {
        useAnchorDaysAgo(100); // 待结算 1..3
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        blockRelease();
        exec("CREATE TRIGGER fail_ledger_s1 BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " WHEN NEW.season_id = 1 BEGIN SELECT RAISE(ABORT, 'injected ledger failure'); END");

        manager.settleIfNeededAsync(); // 第 1 轮
        manager.settleIfNeededAsync(); // 第 2 轮：故障仍在

        assertNull(settledAt(2),
                "两轮都失败时第 2 季仍不得被 claim 喵 —— 非 NULL 说明第二轮从 season 2 开始扫"
                        + "（游标已越过失败季），第 1 季的奖励从此再也不会被回访。诊断：" + diagnostics(1));
        assertNull(settledAt(3), "同上：第 3 季也不许被 claim 喵");
        assertEquals(0, rewardRowCount(2), "被挡住时第 2 季不该有台账行喵");
        assertTrue(count(handler.describe(), "release FAILED after 3 attempts") >= 2,
                "两轮 sweep 都必须重试并各自报告彻底失败（第二轮不能静默跳过）喵："
                        + handler.describe());
        assertTrue(handler.describe().contains("cursor held back at season 1"),
                "第二轮必须显式记录「游标被拉回 season 1」喵：" + handler.describe());
    }

    // ==================================================================
    // R11-P：updated == -1（库不可用）与 updated == 0（无行匹配）必须分开
    // ==================================================================

    @Test
    @DisplayName("R11-P：updated=-1 与 updated=0 判成不同结局、记录不同措辞（前者可重试，后者不可）")
    void connectionFailureAndNoRowMatchAreDistinguished() throws Exception {
        useAnchorDaysAgo(31);
        seedSeasons(1);
        // 真建一个本次 sweep 的 claim（时间戳 12345），否则条件 UPDATE 无从匹配，
        // -1 与 0 的差别根本演示不出来。
        assertTrue(db.tryClaimSettlement(1, 12345L, "{\"owner\":\"us\"}", true),
                "前置：本次 sweep 的 claim 必须建立成功喵");
        blockRelease();

        Object connFailed = invokeReleaseOutcome(1, 12345L);
        String log1 = handler.describe();
        assertEquals("CONNECTION_FAILED", String.valueOf(connFailed),
                "库不可用（withConnection 兜底 -1）必须判成 CONNECTION_FAILED（可重试）喵。"
                        + "判成 NOT_HELD 会让它不再重试 → 修复失效。实际日志：" + log1);
        assertTrue(log1.contains("could not be APPLIED (affected=-1"),
                "-1 的日志必须自己报出 affected=-1，运维一看就知道是连接问题喵：" + log1);
        assertTrue(log1.contains("database connection unavailable"),
                "-1 的日志必须点明「连接不可用」（与「无行匹配」的可操作结论完全不同）喵：" + log1);
        assertEquals(Long.valueOf(12345L), settledAt(1),
                "前置：注入生效，释放确实没落到库上（脏 claim 还在）喵");

        handler.clearAll();
        execNoVerify("DROP TRIGGER block_release");

        Object noRow = invokeReleaseOutcome(1, 999L); // settled_at=12345 ≠ 999 → affected=0
        String log2 = handler.describe();
        assertEquals("NOT_HELD", String.valueOf(noRow),
                "affected=0（settled_at 已不是本次 claim）必须判成 NOT_HELD 而不是可重试失败喵。"
                        + "判成可重试会在别人结算时反复打库、且永远匹配不上。实际日志：" + log2);
        assertTrue(log2.contains("did NOT take effect (affected=0"),
                "0 的日志必须报出 affected=0 喵：" + log2);
        assertTrue(log2.contains("no retry is possible or useful"),
                "0 的日志必须明确「重试不可能也没用」（否则运维会等一个永远不会来的重试）喵：" + log2);
        assertFalse(log2.contains("could not be APPLIED"),
                "0 绝不能被记成「连接不可用」喵 —— 这正是原实现混在一起的两种故障：" + log2);
    }

    @Test
    @DisplayName("R11-P：releaseWithRetry 只对连接失败重试；无行匹配（他服已接手）不重试也不登记")
    void onlyConnectionFailuresAreRetried() throws Exception {
        useAnchorDaysAgo(31);
        seedSeasons(1);
        assertTrue(db.tryClaimSettlement(1, 777L, "{\"owner\":\"us\"}", true),
                "前置：本次 sweep 的 claim 必须建立成功喵");

        blockRelease();
        assertFalse(invokeReleaseWithRetry(1, 777L),
                "连接失败重试耗尽必须返回 false（调用方据此把游标钉住）喵");
        String retried = handler.describe();
        assertTrue(retried.contains("retrying in"),
                "连接失败必须触发同轮重试喵：" + retried);
        assertEquals(1, pendingReleaseCount(),
                "重试耗尽必须登记待重试（否则游标会带着脏 claim 前进 = 原缺陷）喵");
        assertEquals(Long.valueOf(777L), pendingClaimOf(1),
                "待重试必须记住本次 claim 的时间戳（fencing token 原值，不能改成别的值）喵");

        handler.clearAll();
        execNoVerify("DROP TRIGGER block_release");
        assertTrue(invokeReleaseWithRetry(1, 777L),
                "连接恢复后同一次重试必须成功（返回 true）喵");
        String released = handler.describe();
        assertFalse(released.contains("retrying in"),
                "成功路径不得重试喵：" + released);
        assertEquals(0, pendingReleaseCount(),
                "释放成功（或无行可释放）后不该留待重试条目（留条目会让游标被永久挡住）喵");
        assertNull(settledAt(1), "第二次调用必须真正把 settled_at 退回 NULL 喵");

        // NOT_HELD 也必须不重试：releaseWithRetry 的重试条件必须是「只重试连接失败」。
        // 只测 releaseSettlement 的三态判不出来 —— 循环退出条件若写成「只有 RELEASED 才
        // break」，NOT_HELD 就会被连试 3 次（永远匹配不上，还在别人结算时反复打库）。
        handler.clearAll();
        assertTrue(db.tryClaimSettlement(1, 888L, "{\"owner\":\"us\"}", true),
                "前置：重新建一个本次 sweep 的 claim（时间戳 888）喵");
        assertTrue(invokeReleaseWithRetry(1, 999L),
                "NOT_HELD 仍应返回 true（本季已不归本次 sweep 持有，游标可以前进）喵");
        String notHeld = handler.describe();
        assertFalse(notHeld.contains("retrying in"),
                "NOT_HELD 绝不能被重试喵 —— 重试条件必须是「只重试 CONNECTION_FAILED」。"
                        + "实际日志：" + notHeld);
        assertEquals(1, count(notHeld, "release did NOT take effect"),
                "NOT_HELD 必须只被尝试 1 次（出现 3 次说明循环把 NOT_HELD 也当可重试）喵："
                        + notHeld);
        assertEquals(0, pendingReleaseCount(), "NOT_HELD 不该进待重试表喵");
        assertEquals(Long.valueOf(888L), settledAt(1),
                "NOT_HELD 必须不动库里的抢占（他方持有）喵");
    }

    // ==================================================================
    // F1（R12-B）：结算「读失败」必须中止，不得当成「空」
    //
    // 缺陷链条（修复前）：榜单读失败 → 空列表 → 与「整季无人答题」同形 →
    // buildRewardRows 只可能产出参与奖（未配置时 0 行）→ writeSeasonLedger 的
    // rows.isEmpty() 直接 return true → settled_at 已写（claim 先行）、游标
    // MAX(season_id) WHERE settled_at IS NOT NULL 前进、日志打 "Season settled" →
    // 该季永不回访，整季奖励永久丢失且无任何失败信号。
    // R11 的 releaseWithRetry 只兜「写失败」，兜不住「读失败」。
    // ==================================================================

    @Test
    @DisplayName("F1：榜单读失败 → 该季不被标记 settled、游标不前进、台账不写、记 SEVERE")
    void leaderboardReadFailureAbortsTheSeasonInsteadOfSettlingEmpty() {
        useAnchorDaysAgo(31); // currentSeasonId = 2 → 只结算第 1 季
        stubTopReadFailure();
        seedSeasons(1);

        manager.settleIfNeededAsync();

        assertNull(settledAt(1),
                "榜单读失败时绝不许写 settled_at 喵 —— 非 NULL 意味着游标（MAX(season_id) WHERE"
                        + " settled_at IS NOT NULL）已越过本季，该季永不回访，整季奖励永久丢失"
                        + "（这正是 F1 要修的真实资产缺陷）。诊断：" + diagnostics(1));
        assertNull(db.maxSettledSeasonId(),
                "读失败后游标必须仍是「无已结算季」，下一轮 sweep 才能重试本季喵");
        assertEquals(0, rewardRowCount(1), "读失败时不许写任何台账行（不知道奖励名单）喵");
        assertTrue(handler.hasSevere(),
                "读失败必须留 SEVERE（否则与「本季无奖励」不可区分，运维看不到）喵："
                        + handler.describe());
        assertTrue(handler.describe().contains("Season 1 settlement ABORTED"),
                "SEVERE 必须点明是哪个季被中止并说明奖励未丢而是待重试喵：" + handler.describe());
        assertFalse(handler.describe().contains("Season settled: 1"),
                "读失败时绝不许打「已结算」喵 —— 这条日志是运维判断赛季已完成的唯一依据："
                        + handler.describe());
        verify(messageUtil, never()).getPlayerMessage(
                any(Player.class), eq("season.ended-broadcast"), any(String[].class));
    }

    @Test
    @DisplayName("F1：榜单读失败 → break 而不是 continue：后续季也不许被 claim（游标不许跳读失败季）")
    void leaderboardReadFailureStopsTheSweepInsteadOfContinuing() {
        useAnchorDaysAgo(100); // 待结算 1..3
        stubTopReadFailure();
        seedSeasons(1, 2, 3);

        manager.settleIfNeededAsync();

        assertNull(settledAt(1), "第 1 季读失败 → 不许 settled 喵。诊断：" + diagnostics(1));
        assertNull(settledAt(2),
                "第 1 季读失败后必须 break，第 2 季连 claim 都不许发生喵 —— 若这里非 NULL，说明"
                        + " continue 让后续季照常 claim，MAX(...) 游标一跃就再也回不到第 1 季"
                        + "（第 1 季奖励永久丢失）。");
        assertNull(settledAt(3), "同上：第 3 季也不许被 claim 喵");
        assertNull(db.maxSettledSeasonId(), "三季全未结算 → 游标必须仍为空喵");
    }

    @Test
    @DisplayName("F1：参与奖名单读失败（真 SQLException）→ 释放抢占、台账不写、记 SEVERE")
    void participantReadFailureReleasesTheClaimAndWritesNoLedger() {
        useAnchorDaysAgo(31);
        // 打开参与奖，才会真的去读 player_sessions（否则 buildRewardRows 根本不查名单，
        // 本用例会以「读失败没发生」的形态假绿）。
        config.set("season.rewards.participated", 10.0);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);
        // 真故障：删表 → loadSessionParticipants 的 catch 把它翻成 readFailed=true。
        // 榜单读走的是 mock 桩，不受影响，故障被精确限定在「参与奖名单」这一条读上。
        breakPlayerSessionsRead();

        manager.settleIfNeededAsync();

        assertNull(settledAt(1),
                "参与奖名单读失败发生在 tryClaimSettlement 之后 → 本次 claim 必须被释放"
                        + "（settled_at 退回 NULL），游标才算真正后退喵。诊断：" + diagnostics(1));
        assertNull(topSnapshot(1), "释放必须连 top_snapshot 一起清（下一轮重新聚合）喵");
        assertNull(db.maxSettledSeasonId(), "释放后游标必须仍为空喵");
        assertEquals(0, rewardRowCount(1),
                "名单不认识时不许写入「只含 TOP10」的部分台账喵 —— 那样会静默少发参与奖，"
                        + "且回读判据（ledgered >= rows.size()）用的 rows 本身已缩水，照样"
                        + "「确认成功」（F1 的 E4）。实际：" + rewardRowCount(1) + " 行");
        assertTrue(handler.describe().contains("participant list query FAILED"),
                "SEVERE 必须点明是参与奖名单读失败（不是「本季无奖励」）喵：" + handler.describe());
        assertFalse(handler.describe().contains("Season settled: 1"),
                "读失败时绝不许打「已结算」喵：" + handler.describe());
    }

    @Test
    @DisplayName("F1：真的没有奖励（全档位 0）→ 正常结算，settled_at 写入、无 SEVERE、不释放")
    void genuinelyNoRewardsStillSettlesTheSeason() {
        useAnchorDaysAgo(31);
        config.set("season.rewards.top1", 0.0);
        config.set("season.rewards.top3", 0.0);
        config.set("season.rewards.top10", 0.0);
        config.set("season.rewards.participated", 0.0);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);

        manager.settleIfNeededAsync();

        assertNotNull(settledAt(1),
                "配置上确实没有奖励要发时必须正常结算（settled_at 保留）喵 —— 若为 NULL，说明"
                        + "「零额行」被当成读失败，每季都会白释放一次抢占、永不推进，"
                        + "配置成「只结算不发奖」的服会永久卡死。诊断：" + diagnostics(1));
        assertEquals(1L, db.maxSettledSeasonId(), "游标必须前进到第 1 季喵");
        assertEquals(0, rewardRowCount(1), "零额行不入台账（无钱无消息）喵");
        assertFalse(handler.hasSevere(), "零奖励是正常配置，不该有 SEVERE 喵：" + handler.describe());
        assertTrue(handler.describe().contains("Season settled: 1"),
                "正常结算路径必须留「已结算」日志喵：" + handler.describe());
    }

    @Test
    @DisplayName("F1：DB 层三态 —— player_sessions 不可读时两个 load 都报 readFailed=true，"
            + "两态包装仍返回空（展示路径语义不变）")
    void readFailuresAreVisibleThroughBothLoads() {
        breakPlayerSessionsRead();

        DatabaseManager.SessionParticipantsLoad participants =
                db.loadSessionParticipants(LocalDate.now().minusDays(30), LocalDate.now());
        assertTrue(participants.readFailed(),
                "SELECT 失败必须翻成 readFailed=true 喵 —— 恒 false 就是修复前的形状："
                        + "调用方把「库读不出来」当成「无人参与」。");
        assertTrue(db.getSessionParticipants(LocalDate.now().minusDays(30), LocalDate.now()).isEmpty(),
                "两态包装在失败时仍返回空集合（展示/降级语义不变）喵");

        PlayerDataStore store = new PlayerDataStore(plugin, db);
        PlayerDataStore.RankedPlayersLoad ranked = store.loadRankedPlayersForPeriod(
                10, LocalDate.now().minusDays(30), LocalDate.now());
        assertTrue(ranked.readFailed(),
                "榜单查询失败同样必须 readFailed=true 喵（否则结算会写出空台账并当成成功）");
        assertTrue(store.getRankedPlayersForPeriod(
                10, LocalDate.now().minusDays(30), LocalDate.now()).isEmpty(),
                "两态包装（周/月榜展示）失败时仍返回空列表喵");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * 注入「释放 UPDATE 失败」：只拦把 {@code settled_at} 置 NULL 的 UPDATE。
     * {@code WHEN NEW.settled_at IS NULL} 是精确判据 —— claim 的 UPDATE 写的是非 NULL
     * 时间戳，因此不会被连带挡住（否则用例测不到释放路径，会以「claim 失败」的形态假绿）。
     */
    private void blockRelease() {
        exec("CREATE TRIGGER block_release BEFORE UPDATE ON " + PREFIX + "seasons"
                + " WHEN NEW.settled_at IS NULL BEGIN SELECT RAISE(ABORT, 'blocked release'); END");
    }

    /** 撤掉全部注入的故障（模拟 DB 恢复）。 */
    private void clearInjectedFaults() {
        execNoVerify("DROP TRIGGER IF EXISTS block_release");
        execNoVerify("DROP TRIGGER IF EXISTS fail_ledger_s1");
    }

    /** 不需要「触发器已建成」校验的执行（DROP TRIGGER 用）。 */
    private void execNoVerify(String sql) {
        Boolean ok = db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return Boolean.TRUE;
        });
        assertTrue(Boolean.TRUE.equals(ok), "SQL 执行失败（注入/清理没生效，本用例不可信喵）：" + sql);
    }

    /** 子串出现次数（日志断言的判据：重试次数、失败报告次数）。 */
    private static int count(String haystack, String needle) {
        int n = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            n++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return n;
    }

    /** 待重试表大小（反射读私有字段：它是「游标是否被钉住」的唯一真相源）。 */
    private int pendingReleaseCount() {
        return ((java.util.Map<?, ?>) readField("pendingReleaseRetries")).size();
    }

    private Long pendingClaimOf(int seasonId) {
        return ((java.util.Map<?, ?>) readField("pendingReleaseRetries"))
                .entrySet().stream()
                .filter(e -> Integer.valueOf(seasonId).equals(e.getKey()))
                .map(e -> (Long) e.getValue())
                .findFirst()
                .orElse(null);
    }

    private Object readField(String name) {
        try {
            java.lang.reflect.Field f = SeasonManager.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射读字段失败：" + name, e);
        }
    }

    /** 直调 releaseSettlement，返回结局枚举的 name（三态判据）。 */
    private Object invokeReleaseOutcome(int seasonId, long claimedAt) throws Exception {
        Method m = SeasonManager.class.getDeclaredMethod("releaseSettlement", int.class, long.class);
        m.setAccessible(true);
        return m.invoke(manager, seasonId, claimedAt);
    }

    /** 直调 releaseWithRetry（返回 boolean，本类只用它的日志/待重试表副作用）。 */
    private boolean invokeReleaseWithRetry(int seasonId, long claimedAt) throws Exception {
        Method m = SeasonManager.class.getDeclaredMethod("releaseWithRetry", int.class, long.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(manager, seasonId, claimedAt);
    }

    /** 锚点设为今天往前 N 天：currentSeasonId = floor(N / duration) + 1。 */
    private void useAnchorDaysAgo(int days) {
        LocalDate anchor = LocalDate.now().minusDays(days);
        config.set("season.anchor-date", anchor.toString());
    }

    /**
     * 榜单桩：<b>两个方法都要桩</b>——{@code runSettleSweep} 走三态版
     * {@code loadRankedPlayersForPeriod}（F1 起），收尾的 {@code refreshCacheSync}
     * 仍走两态版 {@code getRankedPlayersForPeriod}。只桩一个会让另一处返回 null
     * （Mockito 默认值）→ NPE，把"桩没写全"伪装成"生产逻辑坏了"。
     */
    private void stubTop(List<LeaderboardEntry> entries) {
        when(dataStore.getRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(entries);
        when(dataStore.loadRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class), anyBoolean()))
                .thenReturn(new PlayerDataStore.RankedPlayersLoad(entries, false));
    }

    /** F1：榜单「读失败」的桩（三态 contract 的失败方向）。 */
    private void stubTopReadFailure() {
        when(dataStore.loadRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class), anyBoolean()))
                .thenReturn(new PlayerDataStore.RankedPlayersLoad(List.of(), true));
        when(dataStore.getRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
    }

    /**
     * 让 {@code player_sessions} 的读<b>真</b>失败（不是桩）：删表后任何 SELECT 都抛
     * {@code SQLException}，被 {@code loadSessionParticipants} / 真实
     * {@code PlayerDataStore} 的 catch 转成 {@code readFailed=true}。
     * 比"桩一个 true"强：它同时验证了 DB 层<b>确实把失败翻成标志</b>
     * （把 {@code return new ...Load(..., true)} 改回 {@code false} 会红）。
     */
    private void breakPlayerSessionsRead() {
        execNoVerify("DROP TABLE " + PREFIX + "player_sessions");
    }

    private void seedSeasons(int... ids) {
        for (int id : ids) {
            SeasonManager.SeasonWindow window = manager.windowOf(id);
            db.ensureSeasonRow(window.id(), window.start(), window.end());
        }
    }

    private static List<LeaderboardEntry> top(LeaderboardEntry... entries) {
        return new ArrayList<>(List.of(entries));
    }

    private static List<LeaderboardEntry> top(UUID id, String name, long score, int rank) {
        return List.of(new LeaderboardEntry(id, name, score, 0, 0, 0, rank));
    }

    private boolean invokeWriteSeasonLedger(int seasonId, List<LeaderboardEntry> entries)
            throws Exception {
        Method m = SeasonManager.class.getDeclaredMethod("writeSeasonLedger", int.class,
                List.class, SeasonManager.SeasonWindow.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(manager, seasonId, entries, manager.windowOf(seasonId));
    }

    private void invokeReleaseSettlement(int seasonId, long claimedAt) throws Exception {
        Method m = SeasonManager.class.getDeclaredMethod("releaseSettlement", int.class, long.class);
        m.setAccessible(true);
        m.invoke(manager, seasonId, claimedAt);
    }

    /** 失败时把关键状态一次打印出来：这条路径的失败只在"哪一步没生效"上有意义。 */
    private String diagnostics(int seasonId) {
        return "settled_at=" + settledAt(seasonId)
                + " cursor=" + db.maxSettledSeasonId()
                + " ledgerRows=" + rewardRowCount(seasonId)
                + " logs=" + handler.describe();
    }

    /**
     * 建故障注入触发器。<b>必须失败即炸并回读确认</b>：{@code withConnection} 会把
     * SQLException 吞成 WARNING 返回 fallback —— 注入若静默失败，用例前半段（claim
     * 成功）照常发生，断言就会以"台账写成功"的形态报错，把"注入没建成"伪装成
     * "生产逻辑坏了"（历史上这种假红最耗时）。
     */
    private void exec(String sql) {
        db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return Boolean.TRUE;
        });
        String table = sql.contains(PREFIX + "season_rewards") ? "season_rewards" : "seasons";
        String name = sql.contains("TRIGGER ") ? sql.split("TRIGGER ")[1].split(" ")[0] : null;
        Integer triggers = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name = ?")) {
                st.setString(1, name);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }, -1);
        assertTrue(triggers != null && triggers == 1,
                "故障注入触发器没建成功（name=" + name + ", table=" + table + ", found="
                        + triggers + "）—— 否则本用例根本不在测失败路径喵");
    }

    /** settled_at；null = 未结算（或行不存在 —— 本类所有用例都先 seed，故不会混淆）。 */
    private Long settledAt(int seasonId) {
        return db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT settled_at FROM " + PREFIX + "seasons WHERE season_id = ?")) {
                st.setInt(1, seasonId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        });
    }

    private String topSnapshot(int seasonId) {
        return db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT top_snapshot FROM " + PREFIX + "seasons WHERE season_id = ?")) {
                st.setInt(1, seasonId);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private int rewardRowCount(int seasonId) {
        Integer n = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + PREFIX + "season_rewards WHERE season_id = ?")) {
                st.setInt(1, seasonId);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }, -1);
        return n == null ? -1 : n;
    }

    /** 收集日志：WARNING（NOT_HELD）/ SEVERE（真故障）是这条回退路径的可观测信号。 */
    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        synchronized boolean hasSevere() {
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 是否存在「级别恰好是 WARNING（不含 SEVERE）且消息含 {@code fragment}」的记录。
         * 按消息限定而非只看「有没有 WARNING」：DB 初始化等旁路也会打 WARNING，
         * 那样断言会以「别处的 WARNING」假绿，删掉本条日志也不会红。
         */
        synchronized boolean hasWarning(String fragment) {
            for (LogRecord record : records) {
                int level = record.getLevel().intValue();
                String message = record.getMessage();
                if (level >= Level.WARNING.intValue() && level < Level.SEVERE.intValue()
                        && message != null && message.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }

        /** 分段断言用：同一用例里要比较「前一次调用的日志」与「后一次调用的日志」。 */
        synchronized void clearAll() {
            records.clear();
        }

        synchronized String describe() {            StringBuilder sb = new StringBuilder("[");
            for (LogRecord record : records) {
                sb.append(record.getLevel().getName()).append(':').append(record.getMessage())
                        .append(" | ");
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
