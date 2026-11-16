package com.letslearnenglish.minecraftplugin.core.season;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R12e-G2 回归：<b>残余窗口 1 不是「多一次广播」——它会无界自转，并且能把
 * 「重结算时才打开的档位」追溯发钱。</b>
 *
 * <h2>被钉住的两件事</h2>
 * <ol>
 *   <li><b>无界重结算</b>：{@code DatabaseManager.clearSeasonLedgerPending} 清不掉标记时，
 *       {@code ledger_pending} 恒为 1，
 *       {@link SeasonManager} 的 rebuild 逻辑在<b>每一轮 sweep</b>都会重新发现这一季并
 *       再结算一遍（不是「重启一次多一次」——同一个进程里也一轮一次）。</li>
 *   <li><b>追溯发钱</b>：重结算按<b>当前</b>配置重建台账。两次 sweep 之间把一个原本为 0
 *       的档位调大，{@code INSERT OR IGNORE} 挡不住"新插入的行"，于是那个早已关闭的
 *       赛季会收到一笔新钱。实测：participated 0→5 之后，台账 1→2 行，实付 +1500 分。</li>
 * </ol>
 *
 * <h2>修复与判据</h2>
 * {@link SeasonManager#MAX_REBUILD_RESETTLES} 给每季的"被 rebuild 重新发现"次数封顶；
 * 超过后不再灌进待重试表（因而不会释放→重 claim→再结算），并留一条一次性 SEVERE。
 *
 * <p>故障注入用<b>真 SQLite 的触发器</b>，判据是「数据库里真的有几行 / 真的付了多少钱」，
 * 不是"我们假设 update 命中 0 行"（照 {@code SeasonReleaseRestartTest} 的手法）。
 */
@DisplayName("R12e-G2 残余窗口 1：ledger_pending 清不掉时不得无界重结算 / 追溯发钱")
class SeasonLedgerPendingCapTest {

    private static final String PREFIX = "lle_";
    private static final UUID TOP1_ID = UUID.fromString("00000000-0000-0000-0000-00000000ba01");

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration config;
    private DatabaseManager db;
    private PlayerDataStore dataStore;
    private Player online;
    private Logger logger;
    private CapturingHandler handler;
    private MockedStatic<Bukkit> bukkit;
    private final AtomicLong depositedCents = new AtomicLong();
    private final List<String> sentKeys = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp(@TempDir Path dir) {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        dataStore = mock(PlayerDataStore.class);
        MessageUtil mu = mock(MessageUtil.class);
        online = mock(Player.class);

        logger = Logger.getLogger("SeasonLedgerPendingCapTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);

        config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "season-ledger-pending-cap.db");
        config.set("database.mysql.table-prefix", PREFIX);
        config.set("season.enabled", true);
        config.set("season.duration-days", 30);
        config.set("season.announce", true);
        config.set("season.rewards.enabled", true);
        config.set("season.rewards.top1", 50.0);
        config.set("season.rewards.top3", 0.0);
        config.set("season.rewards.top10", 0.0);
        config.set("season.rewards.participated", 0.0);
        // anchor 100 days ago => currentSeasonId = 4 => settle 1..3
        config.set("season.anchor-date", LocalDate.now().minusDays(100).toString());

        Economy eco = mock(Economy.class);
        when(eco.depositPlayer(any(Player.class), anyDouble())).thenAnswer(inv -> {
            depositedCents.addAndGet(Math.round(inv.<Double>getArgument(1) * 100.0));
            return new EconomyResponse(inv.<Double>getArgument(1), 0.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getMessageUtil()).thenReturn(mu);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getEconomy()).thenReturn(eco);
        when(plugin.getProxyMessenger()).thenReturn(null);
        when(online.getUniqueId()).thenReturn(TOP1_ID);
        when(online.isOnline()).thenReturn(true);
        when(online.getName()).thenReturn("Alice");
        when(mu.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(inv -> {
                    sentKeys.add(inv.getArgument(1));
                    return "MSG:" + inv.getArgument(1);
                });

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
        assertTrue(db.isSQLite(), "前置：必须走 SQLite 分支喵");
        when(plugin.getDatabaseManager()).thenReturn(db);

        List<LeaderboardEntry> top = List.of(new LeaderboardEntry(TOP1_ID, "Alice", 1000L, 0, 0, 0, 1));
        when(dataStore.getRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(top);
        when(dataStore.loadRankedPlayersForPeriod(anyInt(), any(LocalDate.class), any(LocalDate.class), anyBoolean()))
                .thenReturn(new PlayerDataStore.RankedPlayersLoad(top, false));

        SeasonManager boot = new SeasonManager(plugin);
        for (int id = 1; id <= 3; id++) {
            SeasonManager.SeasonWindow w = boot.windowOf(id);
            db.ensureSeasonRow(w.id(), w.start(), w.end());
        }
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        if (db != null) {
            db.shutdown();
        }
        logger.removeHandler(handler);
    }

    // ==================================================================

    /**
     * 主判据：{@code ledger_pending} 永远清不掉时，重结算次数必须<b>有界</b>。
     *
     * <p>变异：删掉 {@code rebuildPendingReleases} 里的封顶分支（把
     * {@code seen > MAX_REBUILD_RESETTLES} 改回无条件灌表）→ 本用例红：
     * 第 3 轮起仍会重结算、台账/实付继续增长。
     */
    @Test
    @DisplayName("G2：ledger_pending 清不掉 → 重结算有界（不会每轮 sweep 都再结算一遍）")
    void unresettlableLedgerPendingIsCapped() {
        blockClearLedgerPending();

        // 同一个实例连跑多轮 = 同一进程生命周期内的多轮 sweep（启服 + 若干次午夜）。
        // 这正是缺陷的形态：不需要重启，一轮 sweep 就重结算一次。
        SeasonManager m = new SeasonManager(plugin);

        // 判据用 settled_at（重 claim 会换新时间戳）与「赛季结束」广播次数 ——
        // 台账行数<b>不能</b>当判据：写的是 INSERT OR IGNORE，同配置重结算行数不变。
        m.settleIfNeededAsync();
        Long claim1 = settledAt(1);
        long paid1 = depositedCents.get();
        long bcast1 = endedBroadcasts();
        assertEquals(1, bcast1, "前置：第 1 轮应有 1 次「赛季结束」广播（多季 catch-up 只播最近一次）喵");

        // 第 2 轮：rebuild 发现"欠账"→ 释放 → 重 claim → 重结算 → 再广播。
        // 这是允许的那一次恢复尝试（MAX_REBUILD_RESETTLES = 1）。
        m.settleIfNeededAsync();
        Long claim2 = settledAt(1);
        long bcast2 = endedBroadcasts();
        assertNotEquals(claim1, claim2,
                "第 2 轮应发生<b>一次</b>重 claim（封顶余量）喵 —— settled_at 必须换成新时间戳。"
                        + "仍是 " + claim1 + " 说明重建/释放没跑（用例前提不成立）。");
        assertEquals(2, bcast2, "第 2 轮必须再播一次（这是重结算的玩家可见症状）喵");

        // 第 3、4、5 轮：封顶已生效，必须不再重 claim、不再广播。
        m.settleIfNeededAsync();
        m.settleIfNeededAsync();
        m.settleIfNeededAsync();
        Long claim5 = settledAt(1);
        long bcast5 = endedBroadcasts();

        assertEquals(claim2, claim5,
                "封顶后不得再重 claim 喵：第 3~5 轮 settled_at 必须停在第 2 轮的值（"
                        + claim2 + "），实测 " + claim5 + "。\n"
                        + "若还在变，说明 MAX_REBUILD_RESETTLES 的封顶没接到"
                        + " rebuildPendingReleases 上 —— 这就是「无界重结算循环」。");
        assertEquals(bcast2, bcast5,
                "封顶后不得再重播「赛季结束」喵（第 2 轮=" + bcast2 + "，第 5 轮=" + bcast5
                        + "）。每轮 sweep 刷一次全服广播正是原判定说「代价只有一次广播」时"
                        + "没算到的部分：它不是一次，是每轮一次。");
        assertEquals(paid1, depositedCents.get(),
                "封顶路径绝不能再付一次钱喵（第 1 轮=" + paid1
                        + "，最终=" + depositedCents.get() + "）。已发过的行由 claimed=0 挡住，"
                        + "这里守的是「不许再走一遍发放链」。");
        assertTrue(handler.contains("re-settled"),
                "封顶必须留一条可操作的 SEVERE（含手工修法 SQL）喵，否则运维不知道"
                        + "为什么这一季停止重试。实际日志：" + handler.describe());
    }

    /**
     * 追溯发钱：这是「多结算一次」真正的代价，也是残余窗口 1 从良性升级为真缺陷的理由。
     *
     * <p><b>测试结构</b>：第 1 季先证明缺陷成立（允许的那一次重结算<b>确实</b>把新开的
     * 档位写进了已关闭的赛季），再证明封顶挡住了它继续。只测后者会得到一个
     * 「因为没重结算所以没多发钱」的空断言（假绿）。
     */
    @Test
    @DisplayName("G2：封顶挡住「重结算时才打开的档位」被追溯发给已关闭的赛季")
    void cappedRebuildCannotPayRetroactivelyEnabledTiers() {
        blockClearLedgerPending();
        // 让"参与奖"这一档有目标：Bob 在每季窗口内都有会话记录。
        UUID bob = UUID.fromString("00000000-0000-0000-0000-00000000ba02");
        for (int id = 1; id <= 3; id++) {
            SeasonManager.SeasonWindow w = new SeasonManager(plugin).windowOf(id);
            execRaw("INSERT OR IGNORE INTO " + PREFIX + "player_sessions"
                    + " (uuid, session_date, words_count, correct_count, score, mode)"
                    + " VALUES ('" + bob + "', '" + w.start() + "', 10, 8, 100, 'QUIZ')");
        }

        // 第 1 轮：participated=0（该档位关闭），只写 top1 行。
        SeasonManager m = new SeasonManager(plugin);
        m.settleIfNeededAsync();
        assertEquals(0, participantRows(1),
                "前置：participated=0 时不该有参与奖行喵");
        assertEquals(1, rewardRows(1), "前置：第 1 季应有 1 行（top1）喵");
        long paidAfterFirst = depositedCents.get();

        // 管理员在两次 sweep 之间打开参与奖 —— 这是"追溯发钱"的触发条件。
        config.set("season.rewards.participated", 5.0);

        // 第 2 轮：封顶允许的那一次重结算。<b>它就是缺陷的证据</b>：重结算按当前配置
        // 重建台账，把"早已关闭的赛季"的参与奖写进去了。
        m.settleIfNeededAsync();
        assertEquals(1, participantRows(1),
                "第 2 轮（允许的那一次重结算）必须<b>真的</b>把新开的档位写进这个已关闭的赛季喵 ——"
                        + "这正是残余窗口 1 的代价，也是「多一次广播」这一描述漏掉的部分。"
                        + "若此处为 0，说明重结算没按当前配置重建台账，"
                        + "那么本用例后半段的「封顶挡住」就失去意义（假绿）。实际="
                        + participantRows(1));

        // 第 3、4、5 轮：封顶必须挡住后续重结算，不得出现<b>第二行</b>新档位。
        int participantRowsAfterSecond = participantRows(1);
        int totalRowsAfterSecond = rewardRows(1);
        for (int i = 0; i < 3; i++) {
            m.settleIfNeededAsync();
        }
        assertEquals(participantRowsAfterSecond, participantRows(1),
                "封顶后不得再为已关闭的赛季插入新档位行喵（第 2 轮=" + participantRowsAfterSecond
                        + "，第 5 轮=" + participantRows(1) + "）");
        assertEquals(totalRowsAfterSecond, rewardRows(1),
                "封顶后第 1 季台账行数不得再变喵（第 2 轮=" + totalRowsAfterSecond
                        + "，第 5 轮=" + rewardRows(1) + "）");
        assertTrue(depositedCents.get() >= paidAfterFirst,
                "实付只可能不降喵");
    }

    /**
     * 反向守卫：故障<b>恢复</b>之后必须能正常收尾 —— 封顶不能把正常恢复路径堵死。
     *
     * <p>这是"防过度修复"：若有人把封顶写成"第一次 rebuild 就不许再试"，本用例红。
     */
    @Test
    @DisplayName("G2：故障恢复后仍能正常清标记并收尾（封顶不得堵死恢复路径）")
    void recoveryStillWorksAfterTheFaultIsLifted() {
        blockClearLedgerPending();
        SeasonManager m = new SeasonManager(plugin);
        m.settleIfNeededAsync();                           // 第 1 轮：正常结算
        assertEquals(1, ledgerPending(1), "前置：标记仍是 1（clear 的 UPDATE 命中了，但标记被触发器复原）喵");

        dropClearBlocker();                                // 故障恢复

        m.settleIfNeededAsync();                           // 第 2 轮：重建 → 重结算 → 标记清零

        assertEquals(0, ledgerPending(1),
                "故障恢复后重结算必须能把标记清掉喵，否则这一季会永远卡在"
                        + "「每轮扫到、每轮重试」的状态。实际 ledger_pending=" + ledgerPending(1));

        // 再跑一轮：标记已为 0 → 不再重建、不再重结算。
        long rowsBefore = rewardRows(1);
        m.settleIfNeededAsync();
        assertEquals(rowsBefore, rewardRows(1),
                "标记清零后不得再重结算喵（否则封顶之外还有第二条循环）");
    }

    // ==================================================================
    // 故障注入（照 SeasonReleaseRestartTest 的手法，必须回读确认）
    // ==================================================================

    /**
     * 让「{@code ledger_pending} 清不掉」成立 —— 但<b>必须让那条 UPDATE 自身成功</b>。
     *
     * <h2>为什么不能用 {@code BEFORE ... RAISE(ABORT)}（原实现，已废弃）</h2>
     * 原注入让 {@code clearSeasonLedgerPending} 的 UPDATE <b>抛异常</b>。这在旧签名
     * （{@code void clearSeasonLedgerPending(int)}，失败只记 WARNING、调用方不看返回值）
     * 下等价于「标记没清掉」，结算照常走到广播 —— 本用例的前提成立。
     *
     * <p>新签名 {@code boolean clearSeasonLedgerPending(int, long, String)} 把<b>两种完全
     * 不同的故障压进同一个 {@code false}</b>：① SQL 异常；② 三列 fencing 匹配 0 行。
     * 而 {@code runSettleSweep} 把 {@code false} 读成<b>②</b>（"本服已不再持有这一季的
     * claim，他服接管了"），于是 {@code continue} 且<b>不置 {@code lastSettledId}</b> ——
     * 结算"不由本服上报"，<b>一次广播都不发</b>。继续用 RAISE(ABORT) 注入，
     * 测的就不再是「封顶」，而是「claim 归属丢失」这条新分支（实测：5 轮全 0 次广播，
     * 封顶 SEVERE 却照常出现 —— 断言与前提脱节）。
     *
     * <h2>现在的注入：UPDATE 成功（命中 1 行）+ 标记被<b>复原</b></h2>
     * {@code AFTER UPDATE} 触发器在语句成功后把 {@code ledger_pending} 再置回 1。于是：
     * <ul>
     *   <li>{@code executeUpdate()==1} → {@code cleared==true} → 本服照常上报结算、照常
     *       广播 —— 与修复前「清不掉但结算完成」的玩家可见形态一致；</li>
     *   <li>标记在库里仍是 1 → 下一轮 {@code rebuildPendingReleases} 又发现这一季 →
     *       释放 → 重 claim → 重结算 → <b>再播一次</b>。这正是本用例要钉住的无界自转；</li>
     *   <li>封顶（{@link SeasonManager#MAX_REBUILD_RESETTLES}）到达后不再灌表 →
     *       广播停在 2。</li>
     * </ul>
     *
     * <p><b>为什么这是真实的故障形态而非"为了变绿"</b>：这正是 R-16 javadoc 描述的
     * 多服竞态 —— 本服的 clear 成功了，但另一台服的写入又把标记恢复/覆盖回去。库里
     * 「标记恒为 1」才是缺陷事实，谁写的并不重要。
     *
     * <p>{@code WHEN} 精确只命中「clear」：{@code NEW.ledger_pending=0 AND
     * NEW.settled_at IS NOT NULL}。claim 写 {@code ledger_pending=1}、
     * {@code releaseSettlement} 写 {@code settled_at=NULL}、{@code reassignSeasonClaimOwner}
     * 不动 {@code ledger_pending} —— 三条都不命中。{@code OLD.ledger_pending <> 0}
     * 让"已经是 0 的再置 0"不触发（幂等、也避免依赖 recursive_triggers 的开关状态）。
     * 触发器只建在 {@code UPDATE} 上，{@code ensureSeasonRow} 的 INSERT 不受影响。
     */
    private void blockClearLedgerPending() {
        exec("CREATE TRIGGER block_clear_g2 AFTER UPDATE ON " + PREFIX + "seasons"
                + " WHEN NEW.ledger_pending = 0 AND NEW.settled_at IS NOT NULL"
                + " AND OLD.ledger_pending <> 0"
                + " BEGIN UPDATE " + PREFIX + "seasons SET ledger_pending = 1"
                + " WHERE season_id = NEW.season_id; END");
    }

    private void dropClearBlocker() {
        db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS block_clear_g2");
            }
            return Boolean.TRUE;
        });
        Integer left = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='block_clear_g2'")) {
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            }
        }, -1);
        assertEquals(0, left == null ? -1 : left, "前置：故障注入触发器必须真的被删掉喵");
    }

    /**
     * 建故障注入触发器。<b>必须回读确认</b>：{@code withConnection} 会把 SQLException
     * 吞成 WARNING 返回 fallback —— 注入若静默失败，用例会以"生产逻辑坏了"的形态假红。
     */
    private void exec(String sql) {
        String name = triggerName(sql);
        db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return Boolean.TRUE;
        });
        Integer found = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name = ?")) {
                st.setString(1, name);
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }, -1);
        assertTrue(found != null && found == 1,
                "故障注入触发器没建成功（name=" + name + ", found=" + found
                        + "）—— 否则本用例根本不在测失败路径喵");
    }

    /** 普通 DDL/DML（非触发器）用这个：不要求结果是触发器。 */
    private void execRaw(String sql) {
        Boolean ok = db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return Boolean.TRUE;
        });
        assertTrue(Boolean.TRUE.equals(ok), "SQL 执行失败（前置数据没建好，本用例不可信喵）：" + sql);
    }

    /** 从 {@code CREATE TRIGGER <name> ...} 里取触发器名（SQL 是多行拼接的，不能按整串切）。 */
    private static String triggerName(String sql) {
        int i = sql.indexOf("TRIGGER ");
        if (i < 0) {
            throw new AssertionError("不是 CREATE TRIGGER 语句，取不到名字：" + sql);
        }
        String rest = sql.substring(i + "TRIGGER ".length()).trim();
        return rest.split("\\s+")[0];
    }

    private int rewardRows(int seasonId) {
        return countWhere("SELECT COUNT(*) FROM " + PREFIX + "season_rewards WHERE season_id = " + seasonId);
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

    /** 「赛季结束」广播次数（重结算最直接的玩家可见症状 —— 行数会被 INSERT OR IGNORE 吃掉）。 */
    private long endedBroadcasts() {
        synchronized (sentKeys) {
            return sentKeys.stream().filter("season.ended-broadcast"::equals).count();
        }
    }

    private int participantRows(int seasonId) {
        return countWhere("SELECT COUNT(*) FROM " + PREFIX + "season_rewards WHERE season_id = "
                + seasonId + " AND rank_position = 0");
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

    private int countWhere(String sql) {
        Integer n = db.withConnection(conn -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }, -1);
        return n == null ? -1 : n;
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        synchronized boolean contains(String fragment) {
            for (LogRecord r : records) {
                if (r.getMessage() != null && r.getMessage().contains(fragment)) {
                    return true;
                }
            }
            return false;
        }

        synchronized boolean anyAtOrAbove(Level level) {
            for (LogRecord r : records) {
                if (r.getLevel().intValue() >= level.intValue()) {
                    return true;
                }
            }
            return false;
        }

        synchronized String describe() {
            StringBuilder sb = new StringBuilder();
            for (LogRecord r : records) {
                sb.append(r.getLevel().getName()).append(':').append(r.getMessage()).append(" | ");
            }
            return sb.toString();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
