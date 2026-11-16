package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
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
 * R11：{@link DatabaseManager#markDailyWordRewardClaimed} 与
 * {@link DatabaseManager#insertSeasonRewards} 的<b>失败可见性</b>（数据层这一半）。
 *
 * <h2>为什么需要这个类</h2>
 * 这两个方法原本都是 {@code void} + 自己 {@code catch (SQLException)} 记 WARNING。
 * 失败方向分别是「<b>多给</b>」（每日单词挑战：钱已发、台账为空，重启后同日再发一次）与
 * 「<b>少给</b>」（赛季奖励：台账行是发放唯一数据源，行没进库就永远不发）。两种方向都
 * 影响玩家资产，而调用方此前<b>完全观测不到</b>。
 *
 * <p>消费侧的补偿语义由 {@code DailyChallengeLedgerFailureTest} 覆盖（mock 返回值）。
 * 本类只回答一个 mock 回答不了的问题：<b>真 SQL 失败时，方法真的返回 false 并留下
 * WARNING 吗？</b>——mock 的 {@code thenReturn(false)} 是"我们假设它返回 false"，
 * 不是"它确实返回 false"。
 *
 * <h2>故障注入与为什么用它</h2>
 * {@code PRAGMA query_only=ON}：真实 SQLite 把所有写语句拒成
 * {@code SQLITE_READONLY}，而读侧保持健康 —— 与「只读挂载 / MySQL {@code read_only=1}」
 * 同形，正是生产里最容易被静默吞掉的一类故障。它比 mock 连接强的地方在于：典型的
 * {@code catch}/{@code rollback}/{@code setAutoCommit(true)} 整条路径都被真实执行。
 *
 * <h2>封死的变异</h2>
 * <ul>
 *   <li>把任一方法改回"恒 true"（继续吞失败）→ 失败用例红；</li>
 *   <li>把任一方法改成"恒 false"（过度失败）→ 成功用例红；</li>
 *   <li>删掉 WARNING 日志 → 日志断言红（现场丢失与"没有失败"不可区分）。</li>
 * </ul>
 */
class CurrencyWriteFailureVisibilityTest {

    private static final String PREFIX = "lle_";

    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;
    private static Logger logger;
    private static CapturingHandler handler;

    @BeforeAll
    static void createMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        logger = Logger.getLogger("CurrencyWriteFailureVisibilityTest");
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);
        when(plugin.getLogger()).thenReturn(logger);
    }

    private DatabaseManager db;

    @BeforeEach
    void initRealDatabase(@TempDir Path dir) throws Exception {
        FileConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "currency-test.db");
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());

        handler.records.clear();
        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则故障注入手段无效喵");
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // 目标 1：markDailyWordRewardClaimed
    // ==================================================================

    @Test
    @DisplayName("markDailyWordRewardClaimed 成功 → true，且不产生 WARNING")
    void markClaimedReturnsTrueOnSuccess() {
        assertTrue(db.markDailyWordRewardClaimed(UUID.randomUUID(), "2026-11-10"),
                "成功写入台账时必须返回 true喵 —— 恒 false 会把每个玩家都推进"
                        + "「钱已发、台账没写」的补偿路径，疯狂补写 + SEVERE 刷屏。");
        assertFalse(handler.hasWarning(),
                "成功路径不应有 WARNING 喵：" + handler.describe());
    }

    @Test
    @DisplayName("markDailyWordRewardClaimed 幂等：同一 (玩家, 日期) 写两次都返回 true")
    void markClaimedIsIdempotentAndStillTrue() {
        UUID playerId = UUID.randomUUID();
        assertTrue(db.markDailyWordRewardClaimed(playerId, "2026-11-10"));
        // INSERT OR IGNORE：第二次影响 0 行，但"这一笔已付"是真事实 —— 仍是成功。
        assertTrue(db.markDailyWordRewardClaimed(playerId, "2026-11-10"),
                "重复标记必须仍返回 true喵 —— 若按影响行数判成败，正常的重复调用会在调用方"
                        + "被误判成写失败，触发无意义的 SEVERE 与补写。");
    }

    @Test
    @DisplayName("markDailyWordRewardClaimed 写被拒（SQLITE_READONLY）→ false + WARNING 带玩家与日期")
    void markClaimedReturnsFalseWhenWriteRejected() throws SQLException {
        UUID playerId = UUID.randomUUID();
        execOnDb("PRAGMA query_only=ON");
        boolean result;
        try {
            result = db.markDailyWordRewardClaimed(playerId, "2026-11-10");
        } finally {
            execOnDb("PRAGMA query_only=OFF");
        }

        assertFalse(result,
                "写库被拒时必须返回 false喵 —— 恒 true 就是修复前的形状：调用方以为台账写好了，"
                        + "内存位与 DB 台账分叉，重启后同一天再发一次钱（真实经济损失）。");
        assertTrue(handler.hasWarning(),
                "失败必须留下 WARNING（含堆栈）喵 —— 否则「写失败」与「没调用」在任何日志里"
                        + "都不可区分。实际日志：" + handler.describe());
        assertTrue(handler.describe().contains(playerId.toString()),
                "WARNING 里必须能定位到玩家喵。实际日志：" + handler.describe());
    }

    // ==================================================================
    // 目标 2：insertSeasonRewards
    // ==================================================================

    @Test
    @DisplayName("insertSeasonRewards 成功 → true；空列表/null → true（无写入即无失败）")
    void insertSeasonRewardsReturnsTrueOnSuccessAndOnEmpty() {
        assertTrue(db.insertSeasonRewards(null), "null 列表应视为无写入 → true 喵");
        assertTrue(db.insertSeasonRewards(List.of()), "空列表应视为无写入 → true 喵");
        assertTrue(db.insertSeasonRewards(List.of(
                        new DatabaseManager.SeasonRewardRow(1, UUID.randomUUID(), 1, 100L, 500L),
                        new DatabaseManager.SeasonRewardRow(1, UUID.randomUUID(), 2, 90L, 300L))),
                "成功写入台账时必须返回 true喵 —— 恒 false 会让结算方以为每季都写失败，"
                        + "反复释放抢占、永不前进（结算死循环）。");
        assertFalse(handler.hasWarning(), "成功路径不应有 WARNING 喵：" + handler.describe());
    }

    @Test
    @DisplayName("insertSeasonRewards 写被拒（SQLITE_READONLY）→ false + WARNING（失败方向=玩家拿不到奖励）")
    void insertSeasonRewardsReturnsFalseWhenWriteRejected() throws SQLException {
        execOnDb("PRAGMA query_only=ON");
        boolean result;
        try {
            result = db.insertSeasonRewards(List.of(
                    new DatabaseManager.SeasonRewardRow(2, UUID.randomUUID(), 1, 100L, 500L)));
        } finally {
            execOnDb("PRAGMA query_only=OFF");
        }

        assertFalse(result,
                "写库被拒时必须返回 false喵 —— 它的失败方向是「玩家拿不到奖励」（台账行是发放唯一"
                        + "数据源），比「多给」更严重，更不能静默吞掉。");
        assertTrue(handler.hasWarning(),
                "失败必须留下 WARNING 喵。实际日志：" + handler.describe());
    }

    @Test
    @DisplayName("前置换算：query_only 撤销后写入恢复成功（证明注入只挡写、且方法未被测试弄坏）")
    void writeRecoversAfterInjectionUndone() throws SQLException {
        execOnDb("PRAGMA query_only=ON");
        assertFalse(db.markDailyWordRewardClaimed(UUID.randomUUID(), "2026-11-11"));
        execOnDb("PRAGMA query_only=OFF");
        assertTrue(db.markDailyWordRewardClaimed(UUID.randomUUID(), "2026-11-11"),
                "撤销故障注入后必须恢复 true 喵 —— 否则本类其它断言不可信。");
    }

    // ---------------- helpers ----------------

    private void execOnDb(String sql) throws SQLException {
        synchronized (db.getConnectionLock()) {
            Connection conn = db.getConnection();
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
        }
    }

    /** 收集日志记录，供"失败必须可见"的断言使用。 */
    private static final class CapturingHandler extends Handler {
        private final java.util.List<LogRecord> records = new java.util.ArrayList<>();

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
