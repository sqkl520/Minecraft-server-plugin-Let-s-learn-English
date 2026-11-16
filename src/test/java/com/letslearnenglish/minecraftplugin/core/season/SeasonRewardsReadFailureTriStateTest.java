package com.letslearnenglish.minecraftplugin.core.season;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * round12 · F1 §7.1 回归：{@code getPendingSeasonRewards} / {@code getPendingSeasonRewardsFor}
 * 把「读失败」折叠成空 → {@code deliverPendingBatch} 静默早退。
 *
 * <h2>缺陷本体（为什么不丢数据，但仍然是缺陷）</h2>
 * 两态版在 {@code catch (SQLException)} 后返回空 Map / 空 List。调用方的判据是
 * {@code byPlayer.isEmpty() → return}，于是：
 *
 * <ul>
 *   <li><b>真没人有奖励</b> → 空 → 早退（正确）；</li>
 *   <li><b>读失败</b> → 空 → 早退（<b>同形</b>）。</li>
 * </ul>
 *
 * 台账 {@code claimed} 仍是 0，所以<b>不丢数据</b>，下次 join 会重试 ——
 * 这是「延迟发放」而非「永久丢失」，也是判 LOW 而不是 HIGH 的理由。
 * 真实问题在别处：如果玩家<b>再也不上线</b>（毕业/退服），这笔钱就永远挂着，
 * 而<b>没有任何日志说明发生过什么</b>。运维排查「玩家说没收到赛季奖励」时，
 * 库里 {@code claimed=0} 与「读的时候刚好抖动」在日志上完全不可区分。
 *
 * <h2>修法：三态化（照本仓既有范式），不改发放逻辑</h2>
 * 新增 {@code PendingSeasonRewardsLoad(byPlayer, readFailed)}（照
 * {@code DifficultWordsLoad} / {@code SeasonLedgerDebtScan} 范式），
 * 两条发放路径（join 单发 + 结算批量）都改走三态版，读失败记 <b>SEVERE</b>。
 * 发放语义一个字节都不变：读失败时同样不标记、不发放，台账留 pending。
 *
 * <h2>本条最关键的一条约束：反向用例</h2>
 * 「真没人有奖励」时<b>不得</b>记 SEVERE —— 否则每次结算/每次 join 都刷一条
 * 假故障日志，运维很快就会把所有 SEVERE 当噪音忽略，那时真故障也看不见了。
 * 本类用 {@code nobodyOwedMeansNoSevere} 把这一格钉死。少了它，一个
 * 「一律 readFailed=true」的实现能通过所有正向用例。
 *
 * <h2>故障注入：真 SQLite + 真 SQLException</h2>
 * 手法照 {@code DifficultWordsReadFailureTest} / {@code DailyChallengeReadFailureTriStateTest}：
 * 用第二条连接 {@code DROP TABLE season_rewards}（或改列名），让生产 SELECT 真的抛
 * {@code SQLException}。
 * <b>不用 mock 桩 {@code readFailed=true}</b>：那只能证明「调用方会读这个 flag」，
 * 证明不了「数据层真的把异常翻成 flag」—— 把 {@code return ... true} 改成
 * {@code false}，桩测试照样绿。
 *
 * <p>为什么用 {@code DROP TABLE} 而不是 {@code RENAME COLUMN}：本类的被测读路径
 * 只碰 {@code season_rewards}，而这表在每条用例里都是「要么空、要么只由本用例 seed」，
 * 删掉不影响其它断言（不像 {@code daily_challenge_words} 那样还要验写路径）。
 * 删表是这里最干净的「读一定失败」。
 */
@DisplayName("赛季奖励读失败三态（F1 §7.1）")
class SeasonRewardsReadFailureTriStateTest {

    private static final String PREFIX = "lle_";
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-0000000f7a01");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-0000000f7a02");

    @TempDir
    Path tempDir;

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration config;
    private DatabaseManager db;
    private SeasonManager manager;
    private Player onlinePlayer;
    private Logger logger;
    private CapturingHandler handler;
    private MockedStatic<org.bukkit.Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        config = new YamlConfiguration();
        onlinePlayer = mock(Player.class);

        logger = Logger.getLogger("SeasonRewardsReadFailure-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);

        // season.enabled=true 且锚点在 31 天前 → currentSeasonId=2 → isEnabled() 为真
        // （deliverPending* 的第一步就是这个守卫，不满足会直接 return，等于什么都没测）。
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "season-rewards-tristate.db");
        config.set("database.mysql.table-prefix", PREFIX);
        config.set("season.enabled", true);
        config.set("season.duration-days", 30);
        config.set("season.anchor-date", LocalDate.now().minusDays(31).toString());
        config.set("season.announce", false);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        // 无 Vault：发放段静默降级（本类不测发钱，只测「读失败是否可见」）。
        when(plugin.getEconomy()).thenReturn(null);
        when(plugin.getProxyMessenger()).thenReturn(null);
        when(plugin.getMessageUtil())
                .thenReturn(mock(com.letslearnenglish.minecraftplugin.util.MessageUtil.class));
        when(plugin.getPlayerDataStore())
                .thenReturn(mock(com.letslearnenglish.minecraftplugin.data.PlayerDataStore.class));

        when(onlinePlayer.getUniqueId()).thenReturn(PLAYER_A);
        when(onlinePlayer.isOnline()).thenReturn(true);
        when(onlinePlayer.getName()).thenReturn("Alice");

        // 调度器就地执行：join/结算两条路径的异步段都在本线程跑完，断言时刻确定。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return null;
                });
        when(scheduler.runTask(any(org.bukkit.plugin.Plugin.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return null;
                });
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        bukkit = mockStatic(org.bukkit.Bukkit.class);
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer(any(UUID.class))).thenReturn(onlinePlayer);
        // 必须 stub：batch 路径的玩家列表来自 Bukkit.getOnlinePlayers()，
        // mockStatic 未 stub 时返回空集合 → deliverPendingBatch 在
        // `playerIds.isEmpty()` 处早退，本类就测不到「读失败是否可见」。
        bukkit.when(org.bukkit.Bukkit::getOnlinePlayers)
                .thenReturn(List.of(onlinePlayer));

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置：必须走 SQLite 分支，否则故障注入无效喵");
        when(plugin.getDatabaseManager()).thenReturn(db);

        manager = new SeasonManager(plugin);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        if (logger != null) {
            logger.removeHandler(handler);
        }
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // 组 A —— 数据层：三态必须真的分出「读失败」
    // ==================================================================

    @Nested
    @DisplayName("组 A —— DatabaseManager 三态（真 SQLException）")
    class DataLayerTriState {

        /**
         * 单玩家版：健康时 readFailed=false，破坏后 readFailed=true。
         *
         * <p>变异指向：把 {@code return new PendingSeasonRewardsLoad(Map.of(), true)}
         * 改回 {@code false}（= 原来的两态折叠）→ 本用例红。
         */
        @Test
        @DisplayName("loadPendingSeasonRewards：健康 → false；读失败 → true")
        void singlePlayerTriState() {
            // 前置：健康时必须能读到（否则下面测的就不是「失败」而是「本来就没数据」）。
            assertEquals(false, db.loadPendingSeasonRewards(PLAYER_A).readFailed(),
                    "前置：健康库的 readFailed 必须是 false喵");

            breakSeasonRewardsRead();

            DatabaseManager.PendingSeasonRewardsLoad load = db.loadPendingSeasonRewards(PLAYER_A);
            assertTrue(load.readFailed(),
                    "读失败必须 readFailed=true 喵 —— 折叠成 false 就是本条缺陷的原形："
                            + "「读失败」与「这个玩家没有待发奖励」在返回契约上不可区分。");
        }

        /**
         * 批量版：同样的两方向。这两条<b>都必须存在</b> —— 只测一个的话，
         * 「把另一个的 catch 改回 false」不会有任何信号。
         */
        @Test
        @DisplayName("loadPendingSeasonRewardsFor：健康 → false；读失败 → true")
        void batchTriState() {
            assertEquals(false,
                    db.loadPendingSeasonRewardsFor(List.of(PLAYER_A, PLAYER_B)).readFailed(),
                    "前置：健康库的 readFailed 必须是 false喵");

            breakSeasonRewardsRead();

            DatabaseManager.PendingSeasonRewardsLoad load =
                    db.loadPendingSeasonRewardsFor(List.of(PLAYER_A, PLAYER_B));
            assertTrue(load.readFailed(),
                    "批量读失败必须 readFailed=true 喵 —— 否则 deliverPendingBatch 会把它"
                            + "当成「确实没人欠奖励」静默早退。");
        }

        /**
         * 反向：真的没有 pending 行时必须 {@code readFailed=false}。
         *
         * <p>少了这条，一个「永远返回 readFailed=true」的实现能通过上面两条，
         * 而它会让每次 join / 每次结算都刷一条 SEVERE（本类组 C 反向用例也会红）。
         */
        @Test
        @DisplayName("空库（真的没人有奖励）→ readFailed=false，不是 fail")
        void emptyIsNotFailure() {
            DatabaseManager.PendingSeasonRewardsLoad single =
                    db.loadPendingSeasonRewards(PLAYER_A);
            assertFalse(single.readFailed(),
                    "空库不是读失败喵 —— 「没有」与「不知道」必须分开");
            assertTrue(single.byPlayer().isEmpty(), "空库的 map 应为空喵");

            DatabaseManager.PendingSeasonRewardsLoad batch =
                    db.loadPendingSeasonRewardsFor(List.of(PLAYER_A, PLAYER_B));
            assertFalse(batch.readFailed(), "同上（批量版）喵");
            assertTrue(batch.byPlayer().isEmpty(), "同上（批量版）喵");
        }

        @Test
        @DisplayName("null / 空集合 → readFailed=false（没有请求就没有失败）")
        void nullOrEmptyRequestIsNotFailure() {
            assertFalse(db.loadPendingSeasonRewards(null).readFailed(),
                    "null UUID 是调用方 bug，不是读失败喵");
            assertFalse(db.loadPendingSeasonRewardsFor(null).readFailed(),
                    "null 集合不是读失败喵");
            assertFalse(db.loadPendingSeasonRewardsFor(List.of()).readFailed(),
                    "空集合不是读失败喵");
        }

        /**
         * 有数据时必须真的读出来（防「一律返回空 + readFailed=false」的假绿）。
         */
        @Test
        @DisplayName("有 pending 行 → 三态版必须真的读出来（两版口径一致）")
        void pendingRowsAreActuallyRead() {
            seedReward(1, PLAYER_A, 1, 1000L, 5000L);

            DatabaseManager.PendingSeasonRewardsLoad single =
                    db.loadPendingSeasonRewards(PLAYER_A);
            assertFalse(single.readFailed(), "健康库读取不应失败喵");
            assertEquals(1, single.byPlayer().get(PLAYER_A).size(),
                    "单玩家版必须读出那一行喵");

            DatabaseManager.PendingSeasonRewardsLoad batch =
                    db.loadPendingSeasonRewardsFor(List.of(PLAYER_A));
            assertFalse(batch.readFailed(), "健康库批量读取不应失败喵");
            assertEquals(1, batch.byPlayer().get(PLAYER_A).size(),
                    "批量版必须读出那一行喵");
        }
    }

    // ==================================================================
    // 组 B —— 消费方：读失败必须记 SEVERE
    // ==================================================================

    @Nested
    @DisplayName("组 B —— 发放路径：读失败 → SEVERE")
    class ConsumerLogsSevere {

        /**
         * join 补发路径（单玩家）。这是「离线玩家唯一补发面」，静默失败的代价最高。
         *
         * <p>变异指向：把 {@code deliverPending} 里的 {@code if (loaded.readFailed())}
         * 整段删掉（回到两态早退）→ 本用例红。
         */
        @Test
        @DisplayName("join 补发：读失败 → SEVERE，且不标记不发放")
        void joinPathLogsSevereOnReadFailure() {
            breakSeasonRewardsRead();

            manager.claimPendingRewards(onlinePlayer);

            assertTrue(handler.hasSevere(),
                    "join 补发路径的读失败必须记 SEVERE喵 —— 否则「钱卡住了」与"
                            + "「本来就没钱」在日志上不可区分，玩家再也不上线时"
                            + "这笔钱永远挂着且没人知道。\n日志：" + handler.describe());
            assertTrue(handler.describe().contains("pending-reward query FAILED"),
                    "SEVERE 消息必须说清是「查询失败」喵：" + handler.describe());
            // 读失败绝不能变成「标记了但没发」—— 那才是真的丢钱。
            assertFalse(db.markSeasonRewardClaimed(1, PLAYER_A),
                    "读失败时不该有任何行可标记（表都没了）；这里断言的是"
                            + "「不发放路径没有被走到」的等价形态：无可标记行喵");
        }

        /**
         * 批量路径（结算即时发放）。变异指向同上（删掉 readFailed 分支）。
         *
         * <p>走 {@code manager.applyRelayedSettlement} 这个<b>公开入口</b>驱动批量发放，
         * 不反射 private 方法 —— 与 SeasonSettlementRollbackTest 的「驱动生产入口」纪律一致。
         */
        @Test
        @DisplayName("结算批量发放：读失败 → SEVERE")
        void batchPathLogsSevereOnReadFailure() {
            // 先建一个已结算的季，让 applyRelayedSettlement 的 isEnabled() 与
            // getSettledSeason 都有东西可读；然后破坏 season_rewards 的读。
            db.ensureSeasonRow(1, LocalDate.now().minusDays(31), LocalDate.now().minusDays(2));
            breakSeasonRewardsRead();

            manager.applyRelayedSettlement(1);

            assertTrue(handler.hasSevere(),
                    "结算批量发放的读失败必须记 SEVERE喵 —— 与 join 路径同一个缺陷形态。"
                            + "\n日志：" + handler.describe());
            assertTrue(handler.describe().contains("pending-reward query FAILED"),
                    "SEVERE 消息必须说清是「查询失败」喵：" + handler.describe());
        }
    }

    // ==================================================================
    // 组 C —— 最关键的反向：真没人有奖励时不得记 SEVERE
    // ==================================================================

    @Nested
    @DisplayName("组 C —— 反向：没有奖励 ≠ 读失败（不得刷假故障）")
    class NoNoiseWhenNobodyIsOwed {

        /**
         * <b>本类最重要的一条。</b>玩家在线、库健康、但确实没有任何 pending 行 ——
         * 这是<b>每一个新玩家每次进服</b>都会走到的路径。
         *
         * <p>如果这里记 SEVERE，日志会被「假故障」淹没，运维很快学会忽略所有 SEVERE，
         * 那时真正的「钱卡住了」也看不见了 —— 加日志反而降低了可观测性。
         *
         * <p>变异指向：把 {@code deliverPending} 的判断写成
         * {@code if (loaded.byPlayer().isEmpty()){ severe(...) }}（而不是
         * {@code if (loaded.readFailed())}）→ 本用例红。
         */
        @Test
        @DisplayName("join：健康库 + 无 pending 行 → 不得有任何 SEVERE")
        void nobodyOwedMeansNoSevere() {
            // 前置：库必须是健康的，且确实没有 pending 行（不是「坏了所以读不到」）。
            assertEquals(false, db.loadPendingSeasonRewards(PLAYER_A).readFailed(),
                    "前置：库必须健康，本用例才有意义喵");
            assertTrue(db.loadPendingSeasonRewards(PLAYER_A).byPlayer().isEmpty(),
                    "前置：必须真的没有 pending 行喵");

            manager.claimPendingRewards(onlinePlayer);

            assertFalse(handler.hasSevere(),
                    "「真没人有奖励」绝不能记 SEVERE喵 —— 这是每个新玩家每次进服都会走到的"
                            + "路径。刷假故障会让运维学会忽略所有 SEVERE，"
                            + "那时真故障也看不见了（可观测性的净损失）。\n"
                            + "日志：" + handler.describe());
        }

        @Test
        @DisplayName("批量：健康库 + 无人欠奖励 → 不得有任何 SEVERE")
        void nobodyOwedMeansNoSevereBatch() {
            db.ensureSeasonRow(1, LocalDate.now().minusDays(31), LocalDate.now().minusDays(2));

            manager.applyRelayedSettlement(1);

            assertFalse(handler.hasSevere(),
                    "批量路径同样不得在「无人欠奖励」时记 SEVERE喵：\n" + handler.describe());
        }

        /**
         * 正面：有 pending 行时应当真的走到发放（本类无 Vault，走静默降级但
         * <b>不</b>该记 SEVERE 也不该丢行）。这条防的是「把整个方法改成空实现」式的假绿。
         */
        @Test
        @DisplayName("有 pending 行 + 无 Vault → 不记 SEVERE，行保持 pending")
        void pendingRowWithoutVaultStaysPending() {
            seedReward(1, PLAYER_A, 1, 1000L, 5000L);

            manager.claimPendingRewards(onlinePlayer);

            assertFalse(handler.hasSevere(),
                    "无 Vault 是配置状态不是故障，不该记 SEVERE喵：\n" + handler.describe());
            assertEquals(0, claimedFlag(1, PLAYER_A),
                    "无 Vault 时不标记不发放，行必须保持 claimed=0（不销毁奖励）喵");
        }
    }

    // ------------------------------------------------------------------
    //  故障注入与 SQL 辅助
    // ------------------------------------------------------------------

    /**
     * 让 {@code season_rewards} 的读<b>真</b>失败：删表后任何 SELECT 都抛
     * {@code SQLException}，被 {@code DatabaseManager} 的 catch 转成 {@code readFailed=true}。
     *
     * <p>比「桩一个 readFailed=true」强：它同时验证了 DB 层<b>确实把失败翻成标志</b>。
     */
    private void breakSeasonRewardsRead() {
        execNoVerify("DROP TABLE " + PREFIX + "season_rewards");
        // 前置：证明故障真的注入了（表确实不在了）。
        // 少了这一步，「DROP 语法写错但没抛」会让后续断言在「空表」上假绿。
        Integer tables = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name = ?")) {
                st.setString(1, PREFIX + "season_rewards");
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            }
        }, -1);
        assertNotNull(tables, "探测 season_rewards 是否还在时连接失败喵");
        assertEquals(0, tables.intValue(),
                "故障注入失败：season_rewards 表仍然存在，本用例不可信喵");
    }

    private void seedReward(int seasonId, UUID uuid, int rank, long score, long rewardCents) {
        Boolean ok = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT INTO " + PREFIX + "season_rewards"
                            + " (season_id, uuid, rank_position, score, reward_coins, claimed)"
                            + " VALUES (?, ?, ?, ?, ?, 0)")) {
                st.setInt(1, seasonId);
                st.setString(2, uuid.toString());
                st.setInt(3, rank);
                st.setLong(4, score);
                st.setLong(5, rewardCents);
                st.executeUpdate();
            }
            return Boolean.TRUE;
        });
        assertTrue(Boolean.TRUE.equals(ok), "seed 奖励行失败，本用例不可信喵");
    }

    private int claimedFlag(int seasonId, UUID uuid) {
        Integer v = db.withConnection(conn -> {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT claimed FROM " + PREFIX + "season_rewards"
                            + " WHERE season_id = ? AND uuid = ?")) {
                st.setInt(1, seasonId);
                st.setString(2, uuid.toString());
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            }
        }, -1);
        return v == null ? -1 : v;
    }

    private void execNoVerify(String sql) {
        Boolean ok = db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return Boolean.TRUE;
        });
        assertTrue(Boolean.TRUE.equals(ok), "SQL 执行失败（注入没生效，本用例不可信喵）：" + sql);
    }

    /** 收集日志（与 SeasonSettlementRollbackTest 同款）。 */
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
