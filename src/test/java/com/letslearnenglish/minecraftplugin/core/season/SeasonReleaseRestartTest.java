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

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R12d-F7 回归测试：赛季「释放抢占」的待重试表必须<b>跨重启 / 热重载存活</b>。
 *
 * <h2>被修的缺陷（红队证实）</h2>
 * {@code SeasonManager.pendingReleaseRetries} 原为纯内存字段，
 * {@code LetsLearnEnglish} 每次 enable 都 {@code new SeasonManager(this)}、
 * {@code disableAll()} 置 null —— 重启或 {@code /lea reload} 后表恒空。而失败季的
 * {@code settled_at} 仍是脏 claim（非 NULL），于是
 * {@code from = MAX(season_id) WHERE settled_at IS NOT NULL + 1} 已经越过它，
 * 该季落在 {@code [minId, from)} 左侧，扫循环再也访问不到 →
 * <b>该季奖励永久丢失</b>。{@code scheduleReleaseDrain} 的 javadoc 还写着
 * "下次启服的 sweep 会继续重试"，与事实相反。
 *
 * <h2>为什么必须新建一个 {@code SeasonManager} 实例</h2>
 * 缺陷的触发条件就是"对象被换掉了"。同一个实例上跑两次 sweep 只会走 R11-P 的
 * 内存路径（{@code SeasonSettlementRollbackTest} 已覆盖），<b>测不到</b>重启。
 * 本类每条用例在构造出"脏状态"后重新 {@code new SeasonManager(plugin)}
 * —— 这就是 {@code /lea reload} 与进程重启对内存状态的全部影响。
 *
 * <h2>判据为什么落在 {@code seasons.ledger_pending} 而不是「台账缺行」</h2>
 * 「settled_at 非 NULL 且台账无行」由三种历史产生：①台账写失败 ②结算时奖励功能关闭
 * ③各档位金额全 0。后两者本来就不欠台账。按「缺行」重建会给它们反复释放/重结算，
 * 一旦之后奖励配置被打开就<b>补发真钱</b>（本类 L3 钉住这个方向）。
 * 三种历史在库里逐字节同形，所以意图必须在抢占那一刻写下来。
 *
 * <h2>被本类封死的变异</h2>
 * <ol>
 *   <li><b>删掉 {@code runSettleSweep} 里的 {@code rebuildPendingReleases(...)} 调用</b>
 *       → L1 红（重启后 sweep 完全看不到第 1 季，游标停在 2 以上）。</li>
 *   <li><b>把重建返回值当 {@code true}（吞掉 readFailed）</b> → L5 红
 *       （扫描读失败时游标会照常前进 = 缺陷复现）。</li>
 *   <li><b>把 {@code tryClaimSettlement} 的 {@code ledgerPending} 参数固定写成 false</b>
 *       → L1 红（没人被标记，重建扫不到）；L2 红（奖励关闭也不该被标记，此时反而绿
 *       —— 所以 L1 是这条的主判据）。</li>
 *   <li><b>删掉 {@code clearSeasonLedgerPending} 调用</b> → L3b 红（正常结算的季
 *       重启后被当成"欠账"，白释放、白重结算并重复广播）。</li>
 *   <li><b>把重建灌入的值从 {@code debt.settledAt()} 改成 {@code 0L}</b> → L1 红
 *       （releaseSettlement 的条件 UPDATE 永远匹配 0 行，脏 claim 清不掉）。</li>
 * </ol>
 *
 * <p><b>真 SQLite</b>（不是 mock）：判据是"库里实际有几行/什么值"，且 fencing 条件
 * {@code AND settled_at = ?} 的影响行数只有真库能给出（mock 的
 * {@code executeUpdate()==1} 是"我们假设它命中"）。
 */
@DisplayName("R12d-F7 赛季待重试表跨重启重建")
class SeasonReleaseRestartTest {

    private static final String PREFIX = "lle_";
    private static final int DURATION_DAYS = 30;

    private static final UUID TOP1_ID = UUID.fromString("00000000-0000-0000-0000-00000000ba01");

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
    /** {@code getPlayerMessage} 收到的 i18n key（广播次数断言用）。 */
    private final List<String> sentKeys = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        dataStore = mock(PlayerDataStore.class);
        messageUtil = mock(MessageUtil.class);
        onlinePlayer = mock(Player.class);

        logger = Logger.getLogger("SeasonReleaseRestartTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);

        config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "season-restart.db");
        config.set("database.mysql.table-prefix", PREFIX);
        config.set("season.enabled", true);
        config.set("season.duration-days", DURATION_DAYS);
        config.set("season.announce", true);
        config.set("season.rewards.enabled", true);
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
        when(plugin.getEconomy()).thenReturn(null);
        when(plugin.getProxyMessenger()).thenReturn(null);
        when(onlinePlayer.getUniqueId())
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000ee11"));
        when(onlinePlayer.isOnline()).thenReturn(true);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(inv -> {
                    // 广播次数是"重复结算"最直接的玩家可见症状，所以把 key 记下来供断言
                    // （广播本身不打日志，只看 handler 数不出来）。
                    sentKeys.add(inv.getArgument(1));
                    return "MSG:" + inv.getArgument(1);
                });

        // 调度器就地执行（异步/主线程都本线程跑完）；延迟重试**不执行** ——
        // 本类要断言的是"重启时能从库里发现"，让 drain 抢先清掉条目会测不到重建。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return null;
                });
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        when(scheduler.runTaskLaterAsynchronously(any(Plugin.class), any(Runnable.class), anyLong()))
                .thenReturn(null);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(onlinePlayer));

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支喵");
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
    // L1：核心 —— 模拟重启后必须重新发现"欠账"的季并把它结算掉
    // ==================================================================

    @Test
    @DisplayName("重启（新建 SeasonManager）后仍能发现「已抢占但台账没落库」的季，并把奖励补上")
    void restartRebuildsThePendingReleaseSetAndPaysTheLostSeason() {
        useAnchorDaysAgo(100); // currentSeasonId = 4 → 待结算 1..3
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        // 造出缺陷状态：第 1 季 claim 成功、台账写失败、释放也失败 → 脏 claim + 标记为 1。
        // 这三件事在生产上是 R11-P 的路径产生的；这里直接落到库里，避免依赖注入时序。
        assertTrue(db.tryClaimSettlement(1, 4242L, "{\"owner\":\"us\"}", true),
                "前置：第 1 季必须抢占成功喵");
        assertEquals(4242L, settledAt(1), "前置：脏 claim 留在库里喵");

        // ---- 重启：这就是 /lea reload 与进程重启对内存状态的全部影响 ----
        manager = new SeasonManager(plugin);
        assertEquals(0, pendingReleaseCount(),
                "前置：新实例的待重试表必须为空 —— 这正是缺陷的存在形态（纯内存）喵。"
                        + "非 0 说明有人把状态做成了静态/跨实例共享，本用例的前提不再成立。");

        // sweep 前的游标已经越过第 1 季（脏 claim 让它非 NULL）—— 缺陷链的起点。
        assertEquals(1L, db.maxSettledSeasonId(),
                "前置：脏 claim 已把游标推到第 1 季（下一轮 from 会是 2）喵");

        handler.clearAll();
        manager.settleIfNeededAsync();

        String log = handler.describe();
        assertEquals(1, rewardRowCount(1),
                "重启后的 sweep 必须把第 1 季重新结算并写出 1 行台账喵。为 0 说明重建逻辑"
                        + "没生效 —— 第 1 季落在 [minId, from) 左侧，循环再也访问不到它，"
                        + "奖励永久丢失（这正是本缺陷）。诊断：" + diagnostics(1));
        assertNotNull(settledAt(1), "第 1 季必须被重新 claim 成功（settled_at 非 NULL）喵");
        assertTrue(log.contains("recovered 1 season(s)"),
                "重建必须留一条 SEVERE 说明「上一轮没落完、现在补上」（否则运维无法解释"
                        + "重启后为何多了一次结算）喵。实际日志：" + log);
        assertTrue(log.contains("Season settled: 1"), "第 1 季必须真的走完结算喵：" + log);
    }

    @Test
    @DisplayName("重启后的重建把游标拉回到失败季（hold-back），后续季照常推进 —— 不被永久卡住")
    void restartHoldsTheCursorBackButStillSettlesLaterSeasons() {
        useAnchorDaysAgo(100); // 待结算 1..3
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        // 第 1 季欠账；第 2 季已正常结算（游标 = 2，于是下一轮 from = 3，跳过第 1 季）。
        assertTrue(db.tryClaimSettlement(1, 4242L, "{}", true), "前置：第 1 季脏 claim 欠账喵");
        assertTrue(db.tryClaimSettlement(2, 5555L, "{}", true), "前置：第 2 季已结算喵");
        // R-16：clear 现在带 fencing（settled_at + claim_owner），这里必须用**刚才 claim
        // 时的原值**，不能回读也不能现造 —— 回读值可能是他服的（那就等于没有 fencing）。
        // 断言 true 是这个前置条件的判据本身：false = 标记其实还留在库里（第 2 季会在重启
        // 后被当成欠账重建），本用例「只有第 1 季被拉回」的前提随之失效。
        assertTrue(db.clearSeasonLedgerPending(2, 5555L, db.getNodeId()),
                "前置：第 2 季已正常结算，欠账标记必须由本次 claim 清掉喵（返回 false 说明"
                        + " fencing 没对上，标记仍是 1 → 重启后第 2 季会被白释放、白重结算）");
        insertLedgerRow(2, TOP1_ID, 1, 1000L, 5000L);
        assertEquals(2L, db.maxSettledSeasonId(), "前置：游标已到第 2 季（from 会是 3）喵");

        manager = new SeasonManager(plugin);
        handler.clearAll();
        manager.settleIfNeededAsync();

        assertEquals(1, rewardRowCount(1),
                "游标必须被拉回到第 1 季并把它结算掉喵 —— 否则 from=3 直接越过它，"
                        + "奖励永久丢失。诊断：" + diagnostics(1));
        assertEquals(1, rewardRowCount(2), "第 2 季的台账行不该被动（已结算季不重做）喵");
        assertEquals(1, rewardRowCount(3),
                "第 1 季解开后第 3 季必须能正常结算（重建不能把整条链堵死）喵。"
                        + "诊断：" + diagnostics(3));
        assertTrue(handler.describe().contains("cursor held back at season 1"),
                "必须显式记录游标被拉回（运维否则无法解释扫描起点）喵："
                        + handler.describe());
    }

    @Test
    @DisplayName("重建的 fencing token 必须是库里 settled_at 的原值，否则脏 claim 永远清不掉")
    void rebuildCarriesTheExactSettledAtValueAsFencingToken() throws Exception {
        useAnchorDaysAgo(100);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        assertTrue(db.tryClaimSettlement(1, 987654321L, "{}", true),
                "前置：用一个明显不像 System.currentTimeMillis 的值，便于断言原值被透传喵");

        manager = new SeasonManager(plugin);
        // 直调重建（包级不可见 → 反射），只断言表内容，不跑后续结算（那会清掉条目）。
        invokeRebuildPendingReleases(1, 3);

        assertEquals(Long.valueOf(987654321L), pendingClaimOf(1),
                "重建灌入的必须是库里的 settled_at 原值喵。写成 0 或新时间戳会让"
                        + " releaseSettlement 的条件 UPDATE（WHERE season_id=? AND settled_at=?）"
                        + " 永远匹配 0 行 → 判成 NOT_HELD → 脏 claim 永远清不掉，"
                        + "缺陷原样保留。");
    }

    @Test
    @DisplayName("重建出来的季能被真正释放：settled_at 退回 NULL，随后可重新结算")
    void rebuiltSeasonCanActuallyBeReleased() {
        useAnchorDaysAgo(31); // 只结算第 1 季
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);
        assertTrue(db.tryClaimSettlement(1, 4242L, "{}", true), "前置：脏 claim + 欠账标记喵");

        manager = new SeasonManager(plugin);
        manager.settleIfNeededAsync();

        // 释放 → 重新 claim → 台账落库 → 标记清零，四步都必须在同一个 sweep 里完成。
        assertEquals(1, rewardRowCount(1),
                "重建的季必须被真正释放并重新结算（台账 1 行）喵。诊断：" + diagnostics(1));
        assertEquals(0, ledgerPending(1),
                "台账确认落库后欠账标记必须清零喵 —— 留着会让这一季在每次重启都被"
                        + "白释放、白重结算一次。");
        assertEquals(0, pendingReleaseCount(),
                "释放成功后待重试条目必须清掉（否则游标被永久钉住）喵："
                        + handler.describe());
    }

    // ==================================================================
    // L2：不得误判「奖励功能关闭导致没台账」的季
    // ==================================================================

    @Test
    @DisplayName("结算时 rewards.enabled=false 的季（无台账）重启后绝不能被当成欠账重试")
    void seasonsSettledWithRewardsDisabledAreNeverRebuilt() {
        useAnchorDaysAgo(100); // 待结算 1..3
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        // 历史：第 1 季在 rewards.enabled=false 下结算 —— 有 claim、无台账、无欠账标记。
        assertTrue(db.tryClaimSettlement(1, 4242L, "{}", false),
                "前置：奖励关闭时抢占成功但**不**标欠账喵");
        assertEquals(0, ledgerPending(1), "前置：不欠台账 → 标记必须是 0 喵");
        assertEquals(0, rewardRowCount(1), "前置：奖励关闭 → 台账 0 行（与「写失败」同形）喵");

        // 关键：事后把奖励打开（管理员常见操作），仍不得追溯重试。
        config.set("season.rewards.enabled", true);

        manager = new SeasonManager(plugin);
        handler.clearAll();
        manager.settleIfNeededAsync();

        assertEquals(4242L, settledAt(1),
                "奖励关闭时结算的季绝不能被释放重结算喵 —— 释放后会用**当前**配置重新写台账，"
                        + "等于给一批早已结算过的季补发真钱。这正是方案 A（按「台账缺行」重建）"
                        + "的核心误判风险，本用例钉死它。诊断：" + diagnostics(1));
        assertEquals(0, rewardRowCount(1),
                "该季台账必须仍为 0 行（不得追溯发钱）喵。非 0 = 误判成立、真的补发了钱。"
                        + "诊断：" + diagnostics(1));
        assertFalse(handler.describe().contains("recovered"),
                "不得报告「恢复了欠账季」喵：" + handler.describe());
    }

    @Test
    @DisplayName("各档位金额全 0 的季（无台账）重启后同样不能被当成欠账")
    void seasonsWithZeroRewardTiersAreNeverRebuilt() {
        useAnchorDaysAgo(100);
        config.set("season.rewards.top1", 0.0);
        config.set("season.rewards.top3", 0.0);
        config.set("season.rewards.top10", 0.0);
        config.set("season.rewards.participated", 0.0);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        // 第 1 季：奖励开着但所有档位 0 → writeSeasonLedger 走 rows.isEmpty() → true，
        // 标记随即被 clearSeasonLedgerPending 清掉。
        assertTrue(db.tryClaimSettlement(1, 4242L, "{}", true), "前置：抢占 + 标欠账喵");
        // R-16：同上 —— 用 claim 时的 settled_at 原值 + 本节点 id 才能命中；断言 true
        // 才是「标记归零」的证据（false 时下面那条 ledgerPending==0 会因为根本没清而变红，
        // 届时指向的会是库不是断言）。
        assertTrue(db.clearSeasonLedgerPending(1, 4242L, db.getNodeId()),
                "前置：零额档位无台账可写 → 结算成功 → 标记必须由本次 claim 清掉喵"
                        + "（返回 false = fencing 没对上，标记还是 1 → 重启后被判成欠账）");
        assertEquals(0, ledgerPending(1), "前置：确认无台账可写后标记归零喵");

        manager = new SeasonManager(plugin);
        handler.clearAll();
        manager.settleIfNeededAsync();

        assertEquals(4242L, settledAt(1),
                "零额配置的季不能被释放重结算（重启不能把「无奖励」读成「写失败」）喵。"
                        + "诊断：" + diagnostics(1));
        assertEquals(0, rewardRowCount(1), "仍应 0 行喵");
    }

    @Test
    @DisplayName("部分写入（台账行不全）的季重启后**必须**重建 —— 判据是标记而不是「缺行」")
    void partiallyWrittenLedgerIsStillRebuilt() {
        useAnchorDaysAgo(31);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);
        // 故障史：claim 记下「欠台账」，只写进去 1 行，回读确认失败 → 标记仍是 1。
        assertTrue(db.tryClaimSettlement(1, 4242L, "{}", true), "前置：抢占 + 标欠账喵");
        insertLedgerRow(1, TOP1_ID, 1, 1000L, 5000L);
        assertEquals(1, rewardRowCount(1), "前置：只写进 1 行喵");

        manager = new SeasonManager(plugin);
        manager.settleIfNeededAsync();

        assertEquals(0, ledgerPending(1),
                "重建并重新结算后标记必须归零喵（否则每次重启都白重复结算一次）");
        assertTrue(handler.describe().contains("recovered 1 season(s)"),
                "部分写入的季必须被识别为欠账：判据是「标记」而不是「台账缺行」——"
                        + "真有 1 行也必须重建，否则剩下那行永远补不上喵："
                        + handler.describe());
        assertEquals(1, rewardRowCount(1),
                "重新写入必须幂等（INSERT OR IGNORE），行数不得重复喵。诊断：" + diagnostics(1));
    }

    // ==================================================================
    // L3：正常季完全不受影响
    // ==================================================================

    @Test
    @DisplayName("台账写成功并确认的季：标记为 0，重启后不重建、不重复结算、无 SEVERE")
    void fullySettledSeasonIsUntouchedByTheRebuild() {
        useAnchorDaysAgo(31);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);

        manager.settleIfNeededAsync(); // 第 1 轮：正常结算
        assertEquals(1, rewardRowCount(1), "前置：第 1 季正常结算喵");
        Long firstClaimedAt = settledAt(1);
        assertNotNull(firstClaimedAt, "前置：第 1 季已 claim喵");
        assertEquals(0, ledgerPending(1), "前置：确认落库后标记必须归零喵");

        long before = broadcastEndedCount();

        manager = new SeasonManager(plugin); // 重启
        handler.clearAll();
        manager.settleIfNeededAsync();

        assertEquals(firstClaimedAt, settledAt(1),
                "已正常结算的季绝不能被重新 claim（settled_at 必须原封不动）喵 —— 变了说明"
                        + "重建把它误判成欠账。诊断：" + diagnostics(1));
        assertEquals(1, rewardRowCount(1), "台账行数不变（没重复写、没丢）喵");
        assertFalse(handler.describe().contains("recovered"),
                "正常季不该出现在重建报告里喵：" + handler.describe());
        assertFalse(handler.hasSevere(), "正常重启路径不该有 SEVERE 喵：" + handler.describe());
        assertEquals(before, broadcastEndedCount(),
                "重启不得重复播报「赛季结束」喵 —— 重复广播是重建误判最直接的玩家可见症状。");
    }

    // ==================================================================
    // L4：老库升级 —— 修复前就已丢奖的季不自动补，但要告警
    // ==================================================================

    @Test
    @DisplayName("老库升级（补列）：升级前「已抢占无台账」的季只告警并给 SQL，绝不自动补发")
    void legacyDebtIsReportedNotAutoRetried() {
        useAnchorDaysAgo(100);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        // 模拟"修复前"的库：脏 claim、无台账、标记为默认 0。
        assertTrue(db.tryClaimSettlement(1, 4242L, "{}", false), "前置：模拟旧版本地写入喵");
        // 重现升级瞬间：标记"本次启动刚补上该列"。
        markColumnJustAdded();

        manager = new SeasonManager(plugin);
        handler.clearAll();
        manager.settleIfNeededAsync();

        String log = handler.describe();
        assertTrue(log.contains("Pre-upgrade season ledger check"),
                "老库升级必须做一次性对账并告警（否则修复前丢的奖永远无人知道）喵：" + log);
        assertTrue(log.contains("season_id = <id>"),
                "告警必须给出可直接粘贴的手工 SQL 模板喵：" + log);
        assertEquals(4242L, settledAt(1),
                "遗留欠账绝不能被自动释放重结算喵 —— 库里无法区分「写失败」与「本来不发奖」，"
                        + "自动补发可能给早已结算过的季补真钱。诊断：" + diagnostics(1));
        assertEquals(0, rewardRowCount(1), "不得自动补写台账（钱不能自动补发）喵");
    }

    // ==================================================================
    // L5：扫描读失败必须中止 sweep，绝不能当成「没有欠账」
    // ==================================================================

    @Test
    @DisplayName("欠账扫描读失败（游标仍可读）→ 中止 sweep，不许结算任何季，记 SEVERE")
    void debtScanFailureAbortsTheSweep() {
        useAnchorDaysAgo(100); // 待结算 1..3
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1, 2, 3);
        // 精确裁面：只打坏欠账扫描，MIN/MAX(season_id) 仍可读 —— 否则 runSettleSweep 会在
        // 更早的 minRange 三态检查处就 return，测不到「扫描失败必须中止」这条判据。
        failOwedLedgerScanOnly();

        manager = new SeasonManager(plugin);
        handler.clearAll();
        manager.settleIfNeededAsync();

        String log = handler.describe();
        assertTrue(log.contains("owed-ledger scan FAILED"),
                "欠账扫描读失败必须报明确的 SEVERE（不能当成「没有欠账」）喵：" + log);
        assertNull(db.maxSettledSeasonId(),
                "扫描失败时游标必须仍为空 —— 有值就是「越过了欠账季」，`不知道` 被当成"
                        + "`没有欠账`，欠账季从此永不回访（缺陷复现）喵");
        assertFalse(log.contains("Season settled:"),
                "扫描失败时绝不许结算任何一季喵：" + log);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    @Test
    @DisplayName("端到端：第 1 轮「台账写失败 + 释放也失败」→ 重启 → 第 2 轮必须自动补上")
    void endToEndFailedSettleThenRestartThenRecover() {
        useAnchorDaysAgo(31); // 只结算第 1 季
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);
        // 同时打坏两件事：台账 INSERT（触发释放分支）与释放 UPDATE（让释放失败）。
        blockRelease();
        failLedgerForSeason(1);

        manager.settleIfNeededAsync(); // 第 1 轮：claim 成功 → 台账失败 → 释放失败
        assertNotNull(settledAt(1),
                "前置：第 1 轮释放失败，脏 claim 留在库里喵。诊断：" + diagnostics(1));
        assertEquals(1, ledgerPending(1),
                "前置：台账没落库 → 欠账标记必须仍是 1（这是重启后唯一的发现依据）喵。"
                        + "为 0 说明 tryClaimSettlement 的 ledgerPending 参数没被真实传入 —— "
                        + "那样重启后重建逻辑永远看不到这一季，奖励永久丢失。"
                        + "诊断：" + diagnostics(1));
        assertEquals(1, pendingReleaseCount(), "前置：待重试表有 1 条（本实例内存）喵");

        // ---- 重启 + 故障修复 ----
        clearInjectedFaults();
        manager = new SeasonManager(plugin);
        assertEquals(0, pendingReleaseCount(),
                "前置：新实例的待重试表为空（缺陷形态）喵");
        handler.clearAll();

        manager.settleIfNeededAsync(); // 第 2 轮

        assertEquals(1, rewardRowCount(1),
                "重启后的 sweep 必须自动把第 1 季的奖励补上（台账 1 行）喵。为 0 说明"
                        + "「跨重启重试」根本没实现 —— 这正是红队证实的缺陷。"
                        + "诊断：" + diagnostics(1));
        assertNotNull(settledAt(1), "第 1 季重新 claim 成功喵");
        assertEquals(0, ledgerPending(1), "补上后台账标记必须归零喵");
        assertTrue(handler.describe().contains("recovered 1 season(s)"),
                "必须留重建报告喵：" + handler.describe());
    }

    // ==================================================================
    // L6：schema 迁移 —— 老库（无 ledger_pending 列）必须被补列
    // ==================================================================

    @Test
    @DisplayName("老库升级：缺 ledger_pending 列的 seasons 表被补列，且一次性对账标志被消费")
    void oldSchemaGetsTheLedgerPendingColumnAdded() {
        useAnchorDaysAgo(31);
        // 退化成"修复前"的 schema：重建 seasons 表（无 ledger_pending 列），塞一行脏 claim。
        execNoVerify("DROP TABLE " + PREFIX + "seasons");
        execNoVerify("CREATE TABLE " + PREFIX + "seasons ("
                + " season_id INT PRIMARY KEY,"
                + " start_date DATE NOT NULL,"
                + " end_date DATE NOT NULL,"
                + " settled_at BIGINT,"
                + " top_snapshot TEXT)");
        execNoVerify("INSERT INTO " + PREFIX + "seasons"
                + " (season_id, start_date, end_date, settled_at, top_snapshot)"
                + " VALUES (1, '2026-01-01', '2026-01-30', 4242, NULL)");
        assertFalse(columnExists("ledger_pending"), "前置：老 schema 确实没有该列喵");

        db.initialize(); // 模拟升级后第一次启动

        assertTrue(columnExists("ledger_pending"),
                "启动时必须给 seasons 补上 ledger_pending 列（否则重建逻辑无法工作，"
                        + "修复等于没上线）喵");
        assertEquals(0, ledgerPending(1),
                "补列的默认值必须是 0 —— 遗留行不能被当成「欠账」自动补发喵");
        assertTrue(db.consumeSeasonLedgerPendingColumnAdded(),
                "补列发生的那次启动必须消费掉「刚升级」标志（据此做一次性人工对账）喵");
        assertFalse(db.consumeSeasonLedgerPendingColumnAdded(),
                "标志是消费语义：同一次启动里第二次读必须是 false（否则每次 sweep 都重复告警）喵");
    }

    // ==================================================================
    // L7：释放必须把行还原成「未抢占」的完整状态（含意图标记）
    // ==================================================================

    @Test
    @DisplayName("释放抢占必须连欠账标记一起清零（保持「settled_at IS NULL ⇒ 标记 0」不变式）")
    void releaseAlsoClearsTheOwedLedgerMarker() throws Exception {
        useAnchorDaysAgo(31);
        seedSeasons(1);
        assertTrue(db.tryClaimSettlement(1, 4242L, "{}", true), "前置：抢占 + 标欠账喵");
        assertEquals(1, ledgerPending(1), "前置：标记为 1喵");

        java.lang.reflect.Method m = SeasonManager.class
                .getDeclaredMethod("releaseSettlement", int.class, long.class);
        m.setAccessible(true);
        Object outcome = m.invoke(manager, 1, 4242L);
        assertEquals("RELEASED", String.valueOf(outcome), "前置：释放必须成功喵");

        assertNull(settledAt(1), "释放后 settled_at 必须为 NULL 喵");
        assertEquals(0, ledgerPending(1),
                "释放后欠账标记必须也归零喵 —— 行要还原成完整的「未抢占」状态，"
                        + "否则「settled_at IS NULL 但标记为 1」这种半状态会留在库里，"
                        + "让维护者无法从表内容判断该季到底欠不欠台账（不变式被破坏）。"
                        + "手工修法模板（javadoc 里给运维的 SQL）也依赖这条。");
    }

    // ==================================================================
    // helpers（触发器故障注入，手法照 SeasonSettlementRollbackTest）
    // ==================================================================

    /**
     * 注入「释放 UPDATE 失败」：只拦把 {@code settled_at} 置 NULL 的 UPDATE。
     * {@code WHEN NEW.settled_at IS NULL} 是精确判据 —— claim 的 UPDATE 写的是非 NULL
     * 时间戳，不会被连带挡住（否则用例会以「claim 失败」的形态假绿）。
     */
    private void blockRelease() {
        exec("CREATE TRIGGER block_release BEFORE UPDATE ON " + PREFIX + "seasons"
                + " WHEN NEW.settled_at IS NULL BEGIN SELECT RAISE(ABORT, 'blocked release'); END");
    }

    /** 只拦指定赛季的台账 INSERT（claim 走 seasons 表，不受影响）。 */
    private void failLedgerForSeason(int seasonId) {
        exec("CREATE TRIGGER fail_ledger BEFORE INSERT ON " + PREFIX + "season_rewards"
                + " WHEN NEW.season_id = " + seasonId
                + " BEGIN SELECT RAISE(ABORT, 'injected ledger failure'); END");
    }

    private void clearInjectedFaults() {
        execNoVerify("DROP TRIGGER IF EXISTS block_release");
        execNoVerify("DROP TRIGGER IF EXISTS fail_ledger");
    }

    /**
     * 建故障注入触发器。<b>必须回读确认</b>：{@code withConnection} 会把 SQLException
     * 吞成 WARNING 返回 fallback —— 注入若静默失败，用例会以"生产逻辑坏了"的形态假红。
     */
    private void exec(String sql) {
        db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return Boolean.TRUE;
        });
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
                "故障注入触发器没建成功（name=" + name + ", found=" + triggers
                        + "）—— 否则本用例根本不在测失败路径喵");
    }

    /** 锚点设为今天往前 N 天：currentSeasonId = floor(N / duration) + 1。 */
    private void useAnchorDaysAgo(int days) {
        config.set("season.anchor-date", LocalDate.now().minusDays(days).toString());
    }

    private void stubTop(List<LeaderboardEntry> entries) {
        when(dataStore.getRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(entries);
        when(dataStore.loadRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class), anyBoolean()))
                .thenReturn(new PlayerDataStore.RankedPlayersLoad(entries, false));
    }

    private void seedSeasons(int... ids) {
        for (int id : ids) {
            SeasonManager.SeasonWindow window = manager.windowOf(id);
            db.ensureSeasonRow(window.id(), window.start(), window.end());
        }
    }

    private static List<LeaderboardEntry> top(UUID id, String name, long score, int rank) {
        return List.of(new LeaderboardEntry(id, name, score, 0, 0, 0, rank));
    }

    /** 直调私有 rebuildPendingReleases（无外部入口，可靠的观测点只有表内容）。 */
    private void invokeRebuildPendingReleases(int minId, int maxId) throws Exception {
        java.lang.reflect.Method m = SeasonManager.class
                .getDeclaredMethod("rebuildPendingReleases", int.class, int.class);
        m.setAccessible(true);
        m.invoke(manager, minId, maxId);
    }

    /** 让"本次启动刚补上 ledger_pending 列"成立（重现老库升级那一刻）。 */
    private void markColumnJustAdded() {
        try {
            java.lang.reflect.Field f = DatabaseManager.class
                    .getDeclaredField("seasonLedgerPendingJustAdded");
            f.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) f.get(db)).set(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射设置升级标志失败", e);
        }
    }

    /**
     * 只让欠账扫描失败：SQLite 没有"按 SQL 文本拒绝 SELECT"的钩子，所以改用
     * 「删掉 season_rewards 表」—— 欠账扫描的 {@code ledger_rows} 子查询读它必然抛错，
     * 而 {@code MIN(season_id)} / {@code MAX(season_id)} 只读 {@code seasons}，
     * 仍能成功。这正是"游标可读、欠账不可知"的生产形态。
     */
    private void failOwedLedgerScanOnly() {
        execNoVerify("DROP TABLE " + PREFIX + "season_rewards");
    }

    private void insertLedgerRow(int seasonId, UUID uuid, int rank, long score, long cents) {
        Boolean ok = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT OR IGNORE INTO " + PREFIX + "season_rewards (season_id, uuid,"
                            + " rank_position, score, reward_coins, claimed, claimed_at)"
                            + " VALUES (?, ?, ?, ?, ?, 0, NULL)")) {
                st.setInt(1, seasonId);
                st.setString(2, uuid.toString());
                st.setInt(3, rank);
                st.setLong(4, score);
                st.setLong(5, cents);
                st.executeUpdate();
            }
            return Boolean.TRUE;
        });
        assertTrue(Boolean.TRUE.equals(ok), "前置：台账行插入失败喵");
    }

    private void execNoVerify(String sql) {
        Boolean ok = db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return Boolean.TRUE;
        });
        assertTrue(Boolean.TRUE.equals(ok), "SQL 执行失败（注入/清理没生效，本用例不可信喵）：" + sql);
    }

    /** 待重试表大小（反射读私有字段：它是「游标是否被钉住」的唯一真相源）。 */
    private int pendingReleaseCount() {
        return ((Map<?, ?>) readField("pendingReleaseRetries")).size();
    }

    private Long pendingClaimOf(int seasonId) {
        return ((Map<?, ?>) readField("pendingReleaseRetries")).entrySet().stream()
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

    private int ledgerPending(int seasonId) {
        Integer n = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT ledger_pending FROM " + PREFIX + "seasons WHERE season_id = ?")) {
                st.setInt(1, seasonId);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            }
        }, -1);
        return n == null ? -1 : n;
    }

    /** 列是否存在（SQLite {@code PRAGMA table_info}，与 DatabaseManager 同源判据）。 */
    private boolean columnExists(String column) {
        Boolean found = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "PRAGMA table_info(" + PREFIX + "seasons)")) {
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        if (column.equals(rs.getString("name"))) {
                            return Boolean.TRUE;
                        }
                    }
                }
            }
            return Boolean.FALSE;
        });
        return Boolean.TRUE.equals(found);
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

    /** 「赛季结束」广播次数（重复结算最直接的玩家可见症状）。 */
    private long broadcastEndedCount() {
        return sentKeys.stream().filter("season.ended-broadcast"::equals).count();
    }

    private String diagnostics(int seasonId) {
        return "settled_at=" + settledAt(seasonId)
                + " ledger_pending=" + ledgerPending(seasonId)
                + " cursor=" + db.maxSettledSeasonId()
                + " ledgerRows=" + rewardRowCount(seasonId)
                + " logs=" + handler.describe();
    }

    /** 收集日志：重建与读失败的唯一可观测信号。 */
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

        synchronized void clearAll() {
            records.clear();
        }

        synchronized String describe() {
            StringBuilder sb = new StringBuilder("[");
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
