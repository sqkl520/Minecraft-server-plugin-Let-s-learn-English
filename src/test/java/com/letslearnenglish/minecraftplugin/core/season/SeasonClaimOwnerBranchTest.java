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
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * R-16：{@code seasons.claim_owner} 归属闸的<b>四条分支</b>行为测试。
 *
 * <h2>被测代码</h2>
 * {@code SeasonManager.rebuildPendingReleases(int, int)} 在 {@code :1123} 按「这次抢占是谁的」
 * 三态分流：
 * <ol>
 *   <li><b>① 本服</b>（{@code claim_owner == db.getNodeId()}）→ 照旧灌进待重试表，本轮释放+重结算；</li>
 *   <li><b>② 他服且还新</b> → {@code foreign++}、排一次到期复查、{@code continue}，<b>一个字节都不碰</b>；</li>
 *   <li><b>③ 他服且陈旧</b> → {@code reassignSeasonClaimOwner} 先接管再释放重结算；</li>
 *   <li><b>④ owner 为 NULL</b>（本列上线前的旧行）→ 与 ③ 同判据：还新就跳过，够老就接管
 *       （{@code AND claim_owner IS NULL}）再释放重结算。</li>
 * </ol>
 *
 * <h2>为什么本类<b>不需要</b>任何生产改动</h2>
 * {@code claim_owner} 就是一个可空 {@code VARCHAR(36)} 列，{@code getNodeId()} 是 public。
 * 于是「本服」用 {@code tryClaimSettlement} 自然写出（它写的就是 {@code db.getNodeId()}），
 * 「他服 / 无主」用一条裸 {@code UPDATE} 覆盖该列即可；<b>陈旧与否</b>完全由
 * {@code tryClaimSettlement} 传入的 {@code settled_at} 值决定
 * （{@code now} vs {@code now - FOREIGN_CLAIM_STALE_MS - 60_000}），
 * 不 mock 时钟、不 mock 数据库。
 *
 * <h2>为什么<b>不</b>用「继承 DatabaseManager 覆盖 getNodeId()」这种桩</h2>
 * 写入方用的是<b>字段</b> {@code nodeId}（{@code DatabaseManager:3342 / :3947}），
 * 读取方用的是<b>getter</b>（{@code SeasonManager:804}）。覆盖 getter 会让
 * {@code releaseSettlement} 的 {@code AND claim_owner = ?} 匹配 0 行，
 * 任何「接管成功」断言都变成自证 —— 所以本类一律读真实的 {@code db.getNodeId()}。
 *
 * <h2>判据为什么落在库上而不是日志上</h2>
 * 日志措辞会被重写；真正能证明「SQL 到底命中了几行」的只有
 * {@code claim_owner} / {@code settled_at} / {@code ledger_pending} / {@code season_rewards}
 * 行数。日志只放进断言失败信息里做诊断。
 *
 * <p>真 SQLite（{@code @TempDir} 下的临时文件），无 DB 层 mock。</p>
 */
@DisplayName("R-16 赛季 claim_owner 归属四分支")
class SeasonClaimOwnerBranchTest {

    private static final String PREFIX = "lle_";
    private static final int DURATION_DAYS = 30;
    /** {@code claim_owner} 列的「他服」值。故意不是 UUID 形态：只要不等于本节点 id 就够。 */
    private static final String FOREIGN_OWNER = "other-node";
    /** 「行不存在」哨兵，用来把「SQL NULL」与「没有这一行」区分开。 */
    private static final String NO_ROW = "<no-such-season-row>";

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

    @BeforeEach
    void setUp(@TempDir Path dir) {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        dataStore = mock(PlayerDataStore.class);
        messageUtil = mock(MessageUtil.class);
        onlinePlayer = mock(Player.class);

        logger = Logger.getLogger("SeasonClaimOwnerBranchTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);

        config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "season-claim-owner.db");
        config.set("database.mysql.table-prefix", PREFIX);
        config.set("season.enabled", true);
        config.set("season.duration-days", DURATION_DAYS);
        config.set("season.announce", true);
        config.set("season.rewards.enabled", true);
        config.set("season.rewards.top1", 50.0);
        config.set("season.rewards.top3", 20.0);
        config.set("season.rewards.top10", 5.0);
        // 参与奖保持 0：避免多一条 loadSessionParticipants 查询，让「台账 1 行」这个
        // 断言只反映 top1 那一条规则（判据越窄，误绿越少）。
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
                .thenReturn("MSG");

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        // 异步任务就地执行：settleIfNeededAsync 的 sweep 必须真的跑完，本类才有可断言的库状态。
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return null;
                });
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        // 延迟任务**一律不执行**。两个消费者：scheduleForeignClaimRecheck（到期复查，
        // 会重跑整轮 sweep 并把本用例精心构造的库状态改掉）与 scheduleReleaseDrain。
        // 让它们就地执行 = 用例在测一个自己制造出来的第二轮状态，判据全废。
        when(scheduler.runTaskLaterAsynchronously(any(Plugin.class), any(Runnable.class), anyLong()))
                .thenReturn(null);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(onlinePlayer));

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支");
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
    // ① 本服持主 → 必须重结算（回归守卫：这条本来是唯一被覆盖的分支，不许退化）
    // ==================================================================

    @Test
    @DisplayName("① 本服持主 + 欠台账：端到端重结算，ledger_pending 归零，台账 1 行")
    void ownClaimIsReleasedAndTheSeasonIsSettledAgain() {
        useAnchorDaysAgo(31); // currentSeasonId = 2 → 目标是第 1 季
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);

        long oldSettledAt = System.currentTimeMillis() - 2L * SeasonManager.FOREIGN_CLAIM_STALE_MS;
        assertTrue(db.tryClaimSettlement(1, oldSettledAt, "{}", true),
                "前置：抢占必须成功（否则下面根本没有欠账行）");
        assertEquals(db.getNodeId(), claimOwner(1),
                "前置：tryClaimSettlement 写的必须是本节点 id（它写字段 nodeId，读 getter 也是同一个值）");
        assertEquals(1, ledgerPending(1), "前置：欠台账标记必须是 1（重建逻辑唯一的数据源）");
        assertEquals(Long.valueOf(oldSettledAt), settledAt(1), "前置：脏 claim 留在库里");

        manager = new SeasonManager(plugin); // 重启/热重载：内存里的待重试表被换掉
        manager.settleIfNeededAsync();       // 端到端：整条 sweep，不是直调私有方法

        assertEquals(1, rewardRowCount(1),
                "本服持有的欠账季必须被释放并重新结算，台账 1 行。为 0 说明分支①塌了。"
                        + diag(1));
        assertEquals(0, ledgerPending(1),
                "台账确认落库后欠账标记必须归零（留着会让它每轮都被重结算）。" + diag(1));
        Long after = settledAt(1);
        assertNotNull(after, "重新 claim 后 settled_at 必须非 NULL。" + diag(1));
        assertNotEquals(Long.valueOf(oldSettledAt), after,
                "settled_at 必须换成本次 claim 的新 token（释放后重新抢占）。" + diag(1));
        assertEquals(db.getNodeId(), claimOwner(1),
                "重结算后 claim_owner 必须仍是本节点（claimSettlement 写的）。" + diag(1));
        assertEquals(0, pendingReleaseCount(),
                "释放成功后待重试条目必须清掉，否则游标被永久钉住。" + diag(1));
    }

    // ==================================================================
    // ② 他服 + 还新 → 一个字节都不许碰
    // ==================================================================

    @Test
    @DisplayName("② 他服持主 + 抢占还新：settled_at/台账/claim_owner 全部原样，待重试表为空")
    void freshForeignClaimIsLeftCompletelyUntouched() {
        useAnchorDaysAgo(31);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);

        long freshSettledAt = System.currentTimeMillis(); // 年龄 ≈ 0 → 远小于 15min
        assertTrue(db.tryClaimSettlement(1, freshSettledAt, "{}", true), "前置：抢占成功");
        // 把持有者改成他服：模拟「另一个服正在结算第 1 季」的 live claim。
        overwriteClaimOwner(1, "'" + FOREIGN_OWNER + "'");
        // 再放一行台账：让「台账行数不变」这个断言有东西可数（0→0 是弱断言）。
        insertLedgerRow(1, TOP1_ID, 1, 1000L, 5000L);
        assertEquals(1, rewardRowCount(1), "前置：他服已经写进去 1 行台账");

        manager = new SeasonManager(plugin);
        manager.settleIfNeededAsync();

        assertEquals(Long.valueOf(freshSettledAt), settledAt(1),
                "他服还新的 live claim 绝不能被释放（这就是 R-16 修的缺陷：只按 settled_at fencing "
                        + "会放掉别人正在结算的那一行 → 重复结算 + 重复广播）。" + diag(1));
        assertEquals(FOREIGN_OWNER, claimOwner(1),
                "claim_owner 必须仍是他服 —— 变成别的值说明我们越权接管了。" + diag(1));
        assertEquals(1, rewardRowCount(1),
                "台账行数必须原样（既没被清掉也没被补写）。" + diag(1));
        assertEquals(0, pendingReleaseCount(),
                "分支②是 continue，绝不能把这一季灌进待重试表（灌了就等于排定了要释放它）。"
                        + diag(1));
    }

    // ==================================================================
    // ③ 他服 + 陈旧 → 先接管再释放重结算
    // ==================================================================

    @Test
    @DisplayName("③ 他服持主 + 抢占已陈旧：claim_owner 变成本节点，赛季被释放并重结算")
    void staleForeignClaimIsTakenOverThenSettled() {
        useAnchorDaysAgo(31);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);

        long staleSettledAt = System.currentTimeMillis()
                - SeasonManager.FOREIGN_CLAIM_STALE_MS - 60_000L; // 跨过阈值 1 分钟
        assertTrue(db.tryClaimSettlement(1, staleSettledAt, "{}", true), "前置：抢占成功");
        overwriteClaimOwner(1, "'" + FOREIGN_OWNER + "'");
        assertEquals(FOREIGN_OWNER, claimOwner(1), "前置：库里确实是他服持有");
        assertEquals(0, rewardRowCount(1), "前置：一行台账都没有（死服写到一半就没了）");

        manager = new SeasonManager(plugin);
        manager.settleIfNeededAsync();

        assertEquals(db.getNodeId(), claimOwner(1),
                "陈旧的他服抢占必须被接管成本节点 —— claim_owner 不变说明 reassignSeasonClaimOwner "
                        + "没生效（那样 releaseSettlement 的 AND claim_owner = ? 会匹配 0 行 → "
                        + "判 NOT_HELD → 脏 claim 永远清不掉，整季奖励永久丢失）。" + diag(1));
        Long after = settledAt(1);
        assertNotNull(after,
                "接管后必须完成「释放 → 重新 claim」，settled_at 必须重新变成非 NULL。" + diag(1));
        assertNotEquals(Long.valueOf(staleSettledAt), after,
                "settled_at 必须是本次重新 claim 的新 token，而不是被原样保留的旧值。" + diag(1));
        assertEquals(1, rewardRowCount(1),
                "重结算必须把这一季的台账写出来（1 行）。为 0 = 接管成功但结算没走到。" + diag(1));
        assertEquals(0, ledgerPending(1), "台账落库确认后标记必须归零。" + diag(1));
        assertEquals(0, pendingReleaseCount(), "释放成功后待重试条目必须清掉。" + diag(1));
    }

    // ==================================================================
    // ④ 无主 + 还新 → 保守跳过
    // ==================================================================

    @Test
    @DisplayName("④ claim_owner 为 NULL 且抢占还新：保守跳过，库里一切原样")
    void freshUnownedClaimIsLeftUntouched() {
        useAnchorDaysAgo(31);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);

        long freshSettledAt = System.currentTimeMillis();
        assertTrue(db.tryClaimSettlement(1, freshSettledAt, "{}", true), "前置：抢占成功");
        overwriteClaimOwner(1, "NULL"); // 本列上线前写入的旧行：有 claim、无持有者
        assertNull(claimOwner(1), "前置：claim_owner 必须是 SQL NULL（不是空串）");
        assertEquals(1, ledgerPending(1), "前置：欠台账标记仍是 1");

        manager = new SeasonManager(plugin);
        manager.settleIfNeededAsync();

        // 指针 4 的要求：只断库状态，不读日志文本。
        assertEquals(Long.valueOf(freshSettledAt), settledAt(1),
                "持有者未知且抢占还新的行必须被保守跳过（可能真有一个升级前启动的进程在结算）。"
                        + diag(1));
        assertNull(claimOwner(1),
                "既然没接管，claim_owner 必须还是 NULL —— 变成非 NULL 说明我们碰了它。" + diag(1));
        assertEquals(0, rewardRowCount(1),
                "跳过的行不得写出任何台账行。" + diag(1));
        assertEquals(1, ledgerPending(1),
                "欠账标记必须原样保留（欠账是事实，不由「这次跳不跳」决定）。" + diag(1));
        assertEquals(0, pendingReleaseCount(),
                "分支②是 continue，不能把这一季灌进待重试表。" + diag(1));
    }

    // ==================================================================
    // ⑤ 无主 + 陈旧 → 必须接管；且绝不允许「settled_at 非 NULL 而 claim_owner 仍 NULL」
    // ==================================================================

    @Test
    @DisplayName("⑤ claim_owner 为 NULL 且抢占陈旧：接管并结算，全表不留「有 claim 无主」行")
    void staleUnownedClaimIsTakenOverAndSettled() {
        useAnchorDaysAgo(31);
        stubTop(top(TOP1_ID, "Alice", 1000L, 1));
        seedSeasons(1);

        long staleSettledAt = System.currentTimeMillis()
                - SeasonManager.FOREIGN_CLAIM_STALE_MS - 60_000L;
        assertTrue(db.tryClaimSettlement(1, staleSettledAt, "{}", true), "前置：抢占成功");
        overwriteClaimOwner(1, "NULL");
        assertNull(claimOwner(1), "前置：这是一条无主旧行");

        manager = new SeasonManager(plugin);
        manager.settleIfNeededAsync();

        Long after = settledAt(1);
        assertNotNull(after, "陈旧的无主抢占必须被接管并重新结算。" + diag(1));
        assertEquals(1, rewardRowCount(1),
                "重结算必须写出 1 行台账。为 0 = 接管步骤被省掉了：releaseSettlement 的 "
                        + "AND claim_owner = ? 对 owner 仍为 NULL 的行永远匹配 0 行 → NOT_HELD → "
                        + "条目被清掉而 settled_at 还是非 NULL → 该季再也 claim 不成（整季奖励永久丢失）。"
                        + diag(1));
        assertEquals(0, ledgerPending(1), "台账确认后标记必须归零。" + diag(1));
        assertEquals(db.getNodeId(), claimOwner(1),
                "接管后 claim_owner 必须是本节点。" + diag(1));

        // ⑤ 的核心不变式：这段修复的注释警告的正是「settled_at 非 NULL 而 owner 仍 NULL」
        // 这个状态 —— 它意味着这一行谁也释放不了（releaseSettlement 的 WHERE 要求
        // claim_owner = 本节点），条目被清掉后 settled_at 还留着 → 整季奖励永久丢失。
        // 所以不只查这一季，而是查全表：任何一行都不许是那个形状。
        assertEquals(0, claimedRowsWithoutOwner(),
                "全表不许存在「settled_at 非 NULL 且 claim_owner 为 NULL」的行（整季奖励永久丢失态）。"
                        + diag(1));
    }

    // ==================================================================
    // ⑥ 接管原语本身的 TOCTOU fencing（无法从外部交错，所以直接断原语）
    // ==================================================================
    @Test
    @DisplayName("⑥ reassignSeasonClaimOwner：settled_at 或 owner 观察值不匹配就不命中，且绝不动 fencing token")
    void reassignSeasonClaimOwnerOnlyMatchesTheObservedRow() {
        useAnchorDaysAgo(31);
        seedSeasons(1);
        long settledAt = 111_111L;
        assertTrue(db.tryClaimSettlement(1, settledAt, "{}", false), "前置：先造一条有主 claim");
        overwriteClaimOwner(1, "'" + FOREIGN_OWNER + "'");

        // (a) settled_at 不匹配 → 0 行。这正是「读出之后行被别处改过」的形态。
        assertFalse(db.reassignSeasonClaimOwner(1, settledAt + 1L, FOREIGN_OWNER),
                "settled_at 是 fencing token：值不对就必须 0 行命中（否则会盲写别人刚碰过的行）");
        assertEquals(FOREIGN_OWNER, claimOwner(1),
                "未命中时 claim_owner 必须原样（绝不能写成本节点）");
        assertEquals(Long.valueOf(settledAt), settledAt(1),
                "接管原语永远不许动 settled_at（那是别人的 fencing token）");

        // (b) 两列都匹配 → 1 行，改成自己的 id。
        assertTrue(db.reassignSeasonClaimOwner(1, settledAt, FOREIGN_OWNER),
                "settled_at 与观察到的 owner 都匹配时必须命中 1 行");
        assertEquals(db.getNodeId(), claimOwner(1),
                "接管写进去的必须是**节点级** nodeId（写 per-process id 会让重启后的释放匹配 0 行）");
        assertEquals(Long.valueOf(settledAt), settledAt(1),
                "接管后 settled_at 必须原值保留");

        // (c) claimOwner 传 null 时，条件必须是 IS NULL —— 对一个有主的行必须 0 行。
        overwriteClaimOwner(1, "'" + FOREIGN_OWNER + "'");
        assertFalse(db.reassignSeasonClaimOwner(1, settledAt, null),
                "null 观察值只应命中 NULL 行；对 'other-node' 的行必须 0 行命中");
        assertEquals(FOREIGN_OWNER, claimOwner(1), "未命中时持有者必须原样");

        // (d) 反过来：对一条真正的 NULL 行传具体 owner，也必须 0 行（不能把 NULL 当成万能匹配）。
        overwriteClaimOwner(1, "NULL");
        assertFalse(db.reassignSeasonClaimOwner(1, settledAt, FOREIGN_OWNER),
                "观察值是 'other-node' 而库里是 NULL → 行已变，必须 0 行命中");
        assertNull(claimOwner(1), "未命中时 NULL 必须原样保留");

        // (e) 对 NULL 行传 null → 命中。
        assertTrue(db.reassignSeasonClaimOwner(1, settledAt, null),
                "观察值与库里的 NULL 一致时必须命中 1 行");
        assertEquals(db.getNodeId(), claimOwner(1), "接管后必须是本节点");
        assertEquals(Long.valueOf(settledAt), settledAt(1),
                "整组操作下来 settled_at 一次都不许变");
    }

    // ==================================================================
    // helpers（私有副本，刻意不抽公共基类：上一轮两个 agent 就是在这里互相踩掉的）
    // ==================================================================

    /** 锚点设为今天往前 N 天：currentSeasonId = floor(N / duration) + 1。 */
    private void useAnchorDaysAgo(int days) {
        config.set("season.anchor-date", LocalDate.now().minusDays(days).toString());
    }

    private void stubTop(List<LeaderboardEntry> entries) {
        when(dataStore.getRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(entries);
        when(dataStore.loadRankedPlayersForPeriod(
                anyInt(), any(LocalDate.class), any(LocalDate.class), anyBoolean()))
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

    /** 裸 SQL 覆盖 {@code claim_owner}（传 {@code NULL} 字面量即可造出无主旧行）。 */
    private void overwriteClaimOwner(int seasonId, String sqlValue) {
        execNoVerify("UPDATE " + PREFIX + "seasons SET claim_owner = " + sqlValue
                + " WHERE season_id = " + seasonId);
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
        assertTrue(Boolean.TRUE.equals(ok), "前置：台账行插入失败");
    }

    private void execNoVerify(String sql) {
        Boolean ok = db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return Boolean.TRUE;
        });
        assertTrue(Boolean.TRUE.equals(ok), "SQL 执行失败（本用例的状态构造没生效，结论不可信）：" + sql);
    }

    /** 待重试表大小（分支②「一个字节都不碰」的直接判据）。 */
    private int pendingReleaseCount() {
        return ((Map<?, ?>) readField("pendingReleaseRetries")).size();
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

    /**
     * 库里 {@code claim_owner} 的实际值。
     * <p>{@code null} = <b>SQL NULL</b>（本列上线前的无主旧行）；
     * {@link #NO_ROW} = 这一季根本没有行。两者必须区分：分支④的判据就是「NULL 而不是空串、
     * 也不是别的东西」，把「没有行」误读成「NULL」会让 4a/4b 在行都没建起来时假绿。
     */
    private String claimOwner(int seasonId) {
        return db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT claim_owner FROM " + PREFIX + "seasons WHERE season_id = ?")) {
                st.setInt(1, seasonId);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getString(1) : NO_ROW;
                }
            }
        }, NO_ROW);
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

    /**
     * 全表「有 claim 但持有者未知」的行数。
     * <p>这个形状是 {@code rebuildPendingReleases} 的注释点名的<b>「整季奖励永久丢失」</b>态：
     * {@code releaseSettlement} 的 WHERE 带 {@code AND claim_owner = ?}，owner 为 NULL 的行
     * 永远匹配 0 行 → NOT_HELD → 条目被清掉而 {@code settled_at} 还留着 → 该季再也
     * claim 不成。所以分支④的成功判据不只是「这一季结算了」，而是「全表没有任何一行
     * 落在这个形状里」。
     */
    private int claimedRowsWithoutOwner() {
        Integer n = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + PREFIX + "seasons"
                            + " WHERE settled_at IS NOT NULL AND claim_owner IS NULL")) {
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }, -1);
        return n == null ? -1 : n;
    }

    private int rewardRowCount(int seasonId) {        Integer n = db.withConnection(conn -> {
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

    /** 断言失败信息：把库状态与日志一起塞进去，避免为了看一行而重跑。 */
    private String diag(int seasonId) {
        return " [settled_at=" + settledAt(seasonId)
                + " claim_owner=" + claimOwner(seasonId)
                + " ledger_pending=" + ledgerPending(seasonId)
                + " ledgerRows=" + rewardRowCount(seasonId)
                + " pending=" + pendingReleaseCount()
                + " cursor=" + db.maxSettledSeasonId()
                + " logs=" + handler.describe() + "]";
    }

    /** 收集日志：只用于失败信息，<b>不作为</b>任何断言的判据。 */
    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
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
