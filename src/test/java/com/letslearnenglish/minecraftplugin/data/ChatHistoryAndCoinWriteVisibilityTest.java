package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;

/**
 * R11：{@link DatabaseManager#insertChatHistory} 与
 * {@link DatabaseManager#recordCoinsEarned} / {@link DatabaseManager#recordCoinsSpent}
 * 的<b>失败可见性</b>（数据层这一半）。
 *
 * <h2>为什么需要这个类</h2>
 * 这三个方法原本都是 {@code void} + 自己 {@code catch (SQLException)} 只记 WARNING。
 * 失败方向：
 * <ul>
 *   <li>{@code insertChatHistory} —— 聊天记录（{@code /lea chatlog} / 历史 GUI / 会话数统计的
 *       数据源）静默缺行；三个调用点的 {@code catch (Exception)} <b>永远看不到纯 SQL 失败</b>，
 *       而它们失败时只走 {@code debugLog}（默认 {@code debug=false} → 零日志）。</li>
 *   <li>{@code recordCoinsEarned}/{@code recordCoinsSpent} —— {@code total_coins_earned} /
 *       {@code total_coins_spent} 的写穿真源，写失败 = 金币流水漏记（统计、成就读数偏少）。</li>
 * </ul>
 *
 * <p>消费侧的语义由 {@code RewardEngineCoinLedgerFailureTest} 与
 * {@code AIChatHistoryWriteFailureVisibilityTest} 覆盖（mock 返回值）。本类只回答一个
 * mock 回答不了的问题：<b>真 SQL 失败时，方法真的返回 false 并留下 WARNING 吗？</b>
 * —— mock 的 {@code thenReturn(false)} 是「我们假设它返回 false」，不是「它确实返回 false」。
 *
 * <h2>故障注入与为什么用它</h2>
 * {@code PRAGMA query_only=ON}：真实 SQLite 把所有写语句拒成 {@code SQLITE_READONLY}，
 * 而读侧保持健康 —— 与「只读挂载 / MySQL {@code read_only=1}」同形，正是生产里最容易被
 * 静默吞掉的一类故障。它比 mock 连接强的地方在于：真实的
 * {@code try-with-resources} / {@code catch} / 日志整条路径都被执行。
 *
 * <h2>封死的变异</h2>
 * <ul>
 *   <li>任一方法改回「恒 true」（继续吞失败）→ 失败用例红；</li>
 *   <li>任一方法改成「恒 false」（过度失败）→ 成功用例红；</li>
 *   <li>删掉 WARNING 日志 → 日志断言红（现场丢失与「没有失败」不可区分）；</li>
 *   <li>把 {@code insertChatHistory} 的失败用例的「行没进库」断言删掉 →
 *       「返回 false 但其实写进去了」这种半吊子实现会假绿。</li>
 * </ul>
 */
class ChatHistoryAndCoinWriteVisibilityTest {

    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;
    private static Logger logger;
    private static CapturingHandler handler;

    @BeforeAll
    static void createMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        logger = Logger.getLogger("ChatHistoryAndCoinWriteVisibilityTest");
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);
        when(plugin.getLogger()).thenReturn(logger);
    }

    private DatabaseManager db;
    private Path dbDir;

    @BeforeEach
    void initRealDatabase(@TempDir Path dir) throws Exception {
        dbDir = dir;
        FileConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "chat-coin-test.db");
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
    // 目标 1：insertChatHistory
    // ==================================================================

    @Nested
    @DisplayName("insertChatHistory —— 聊天记录写失败必须上报")
    class InsertChatHistory {

        private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000c4a70");
        private static final String SESSION = "abc12345";

        @Test
        @DisplayName("成功 → true、行真的进库、无 WARNING（恒 false 会在这里红）")
        void returnsTrueOnSuccessAndRowIsPersisted() throws Exception {
            assertTrue(db.insertChatHistory(PLAYER, SESSION, "user", "hello there"),
                    "成功写入必须返回 true 喵 —— 恒 false 会让每次正常对话都刷一条"
                            + "「写失败」WARNING，真故障被淹没。");
            assertEquals(1, countChatRows(PLAYER, SESSION),
                    "返回 true 但行没进库 —— 成败信号与事实脱节");
            assertFalse(handler.hasWarning(), "成功路径不应有 WARNING 喵：" + handler.describe());
        }

        @Test
        @DisplayName("写被拒（SQLITE_READONLY）→ false + 行没进库 + WARNING 带玩家 UUID")
        void returnsFalseWhenWriteRejected() throws Exception {
            execOnDb("PRAGMA query_only=ON");
            boolean result;
            try {
                result = db.insertChatHistory(PLAYER, SESSION, "user", "hello there");
            } finally {
                execOnDb("PRAGMA query_only=OFF");
            }

            assertFalse(result,
                    "写库被拒时必须返回 false 喵 —— 恒 true 就是修复前的形状："
                            + "AIChatManager 的 catch 永远看不到失败，玩家聊天记录静默缺行。");
            assertEquals(0, countChatRows(PLAYER, SESSION),
                    "[CONTROL] 故障注入期间不该有行落库 —— 否则上面的 false 断言不成立");
            assertTrue(handler.hasWarning(),
                    "失败必须留下 WARNING（含堆栈）喵 —— 否则「写失败」与「没调用」在任何日志里"
                            + "都不可区分。实际日志：" + handler.describe());
            assertTrue(handler.describe().contains(PLAYER.toString()),
                    "WARNING 里必须能定位到玩家喵。实际日志：" + handler.describe());
        }

        @Test
        @DisplayName("撤销注入后恢复 true —— 证明断言不是被夹具弄坏的恒 false")
        void recoversAfterInjectionUndone() throws Exception {
            execOnDb("PRAGMA query_only=ON");
            assertFalse(db.insertChatHistory(PLAYER, SESSION, "user", "dropped"));
            execOnDb("PRAGMA query_only=OFF");
            assertTrue(db.insertChatHistory(PLAYER, SESSION, "user", "kept"),
                    "撤销故障注入后必须恢复 true 喵 —— 否则本类其它断言不可信。");
            assertEquals(1, countChatRows(PLAYER, SESSION),
                    "只该有失败注入撤销后那一条");
        }
    }

    // ==================================================================
    // 目标 2：recordCoinsEarned / recordCoinsSpent / adjustCoins 早退
    // ==================================================================

    @Nested
    @DisplayName("金币流水 —— 写失败必须上报，参数被拒不算失败")
    class CoinLedger {

        private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000c01e5");

        @Test
        @DisplayName("recordCoinsEarned 成功 → true、流水真的累加、无 WARNING")
        void earnedReturnsTrueOnSuccess() {
            assertTrue(db.recordCoinsEarned(PLAYER, 12.5),
                    "成功记账必须返回 true 喵 —— 恒 false 会让每次正常发币都刷「写失败」。");
            assertEquals(13L, db.getCoinsEarned(PLAYER),
                    "返回 true 但流水没累加 —— 成败信号与事实脱节（12.5 四舍五入到 13）");
            assertFalse(handler.hasWarning(), "成功路径不应有 WARNING 喵：" + handler.describe());
        }

        @Test
        @DisplayName("recordCoinsSpent 成功 → true、支出真的累加")
        void spentReturnsTrueOnSuccess() {
            assertTrue(db.recordCoinsSpent(PLAYER, 5.0),
                    "成功记账必须返回 true 喵");
            assertEquals(5L, db.getTotalCoinsSpent(),
                    "返回 true 但支出没累加");
            assertFalse(handler.hasWarning(), "成功路径不应有 WARNING 喵：" + handler.describe());
        }

        @Test
        @DisplayName("recordCoinsEarned 写被拒 → false + WARNING 带玩家 UUID")
        void earnedReturnsFalseWhenWriteRejected() throws Exception {
            execOnDb("PRAGMA query_only=ON");
            boolean result;
            try {
                result = db.recordCoinsEarned(PLAYER, 10.0);
            } finally {
                execOnDb("PRAGMA query_only=OFF");
            }
            assertFalse(result,
                    "写库被拒时必须返回 false 喵 —— 恒 true 就是修复前的形状：流水漏记却无人知晓。");
            assertTrue(handler.hasWarning(),
                    "失败必须留下 WARNING 喵。实际日志：" + handler.describe());
            assertTrue(handler.describe().contains(PLAYER.toString()),
                    "WARNING 里必须能定位到玩家喵。实际日志：" + handler.describe());
        }

        @Test
        @DisplayName("recordCoinsSpent 写被拒 → false")
        void spentReturnsFalseWhenWriteRejected() throws Exception {
            execOnDb("PRAGMA query_only=ON");
            boolean result;
            try {
                result = db.recordCoinsSpent(PLAYER, 5.0);
            } finally {
                execOnDb("PRAGMA query_only=OFF");
            }
            assertFalse(result, "写库被拒时必须返回 false 喵");
            assertTrue(handler.hasWarning(), "失败必须留下 WARNING 喵：" + handler.describe());
        }

        /**
         * <b>最容易被改错的一条</b>：{@code amount <= 0} 是「调用方给了一个不需要写的东西」，
         * 不是「写库失败」。报成 {@code false} 会让调用方去重试一个永远不会成功、也不该成功的操作。
         *
         * <p>在 {@code query_only=ON} 下断言，可以顺带证明它<b>根本没发出写语句</b>：
         * 若实现改成「先试写再看结果」，这个 true 就会变红。
         */
        @Test
        @DisplayName("amount <= 0 早退 → true（参数非法不是写失败），且期间没有任何写库尝试")
        void nonPositiveAmountIsNotAWriteFailure() throws Exception {
            execOnDb("PRAGMA query_only=ON");
            try {
                assertTrue(db.recordCoinsEarned(PLAYER, 0.0),
                        "amount=0 被报成 false —— 调用方会去重试一个永远不该成功的写入喵");
                assertTrue(db.recordCoinsEarned(PLAYER, -3.0),
                        "amount<0 被报成 false —— 同上喵");
                assertTrue(db.recordCoinsSpent(PLAYER, 0.0),
                        "spent 方向的 amount=0 同样是「无需写入」而非失败喵");
            } finally {
                execOnDb("PRAGMA query_only=OFF");
            }
            assertFalse(handler.hasWarning(),
                    "参数被拒不该产生 WARNING（它不是故障）喵：" + handler.describe());
        }
    }

    // ---------------- helpers ----------------

    private long countChatRows(UUID player, String session) throws Exception {
        Path file = dbDir.resolve("chat-coin-test.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM lle_ai_chat_history WHERE player_uuid = '"
                             + player + "' AND session_id = '" + session + "'")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    private void execOnDb(String sql) throws SQLException {
        synchronized (db.getConnectionLock()) {
            Connection conn = db.getConnection();
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
        }
    }

    /** 收集日志记录，供「失败必须可见」的断言使用。 */
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
