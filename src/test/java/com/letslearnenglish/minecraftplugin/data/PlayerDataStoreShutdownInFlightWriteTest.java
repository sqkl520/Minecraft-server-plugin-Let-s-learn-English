package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R12c-F3：{@code PlayerDataStore} 的在途异步写在<b>{@code databaseManager.shutdown()} 之后</b>
 * 会怎样 —— 这是 A2 审计要求「先判断再决定」的那条。
 *
 * <h2>问题</h2>
 * <p>{@code PlayerDataStore} 有 3 处 fire-and-forget 的
 * {@code runTaskAsynchronously}（{@code runIfSameEpoch} × 3 调用点 +
 * {@code incrementTodayAiRound} / {@code decrementTodayAiRound}），全类 grep
 * {@code isEnabled|shuttingDown|runtimeActive} <b>零命中</b>。{@code disableAll()} 的 D1
 * <b>刻意不</b> {@code cancelTasks(plugin)}（注释点名为了保住这批写），也没有
 * 「等待/排空」—— 于是关服时它们在途跑着，而 {@code onDisable} 末尾会把库关掉。
 *
 * <h2>这条测试回答的唯一问题：会不会写坏数据？</h2>
 * <p>用<b>真 SQLite</b>（不是 mock）压这个窗口：先正常写入一行，再 {@code db.shutdown()}，
 * 然后让在途任务体跑 {@code incrementWrongCount} / {@code decrementWrongCount} /
 * {@code incrementAiRound}。断言三件事：
 * <ol>
 *   <li><b>不抛异常</b>：关库后 {@code getConnection()} 返回 {@code UNAVAILABLE} 代理，
 *       任何 JDBC 方法都抛 {@code SQLException} —— 而三个写方法各自
 *       {@code catch (SQLException)} 记 WARNING，所以异常不会逃出去；</li>
 *   <li><b>库里原有数据逐字节不变</b>：这是「数据损坏」的判据。若写操作打在
 *       「半关闭」的连接上造成了部分更新 / 破坏事务边界，这里会看见行被改；</li>
 *   <li><b>只留下 WARNING 级日志</b>：即「丢一次增量 + 一条噪音」，不是静默丢也不是崩溃。</li>
 * </ol>
 *
 * <p>结论若为 ①②③ 全部成立，则 F3 判 {@code ACCEPTED_RISK} 有实证依据：
 * 真实代价是「一次增量写丢失 + 一条 WARNING」，而权威落盘由 D6 的全量
 * {@code savePlayerData} 承担（它在 {@code databaseManager.shutdown()} 之前）。
 *
 * <h2>为什么这必须是行为测试而不是读代码推断</h2>
 * <p>「{@code UNAVAILABLE} 代理会不会让 {@code executeUpdate} 抛」，
 * 以及「抛之前会不会已经改了行」，是 JDBC + 代理实现的联合行为；
 * 读代码只能看到 {@code throw new SQLException(...)} 那一行，看不到 SQLite 侧是否已经
 * 打开了隐式事务。真库跑一遍才有定论。
 */
@DisplayName("PlayerDataStore 关库后在途写 — 是数据损坏还是日志噪音")
class PlayerDataStoreShutdownInFlightWriteTest {

    private static final String PREFIX = "lle_";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000f3001");
    private static final String WORD = "apple";

    @TempDir
    Path tempDir;

    private LetsLearnEnglish plugin;
    private DatabaseManager db;
    private PlayerDataStore store;
    private Captured logs;

    /** 日志探针（本仓每个测试类各带一份）。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final List<Level> levels = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("PlayerDataStoreShutdownInFlightWriteTest." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    messages.add(record.getMessage() == null ? "" : record.getMessage());
                    levels.add(record.getLevel());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
        }

        boolean anyAtOrAbove(Level level) {
            for (Level l : levels) {
                if (l.intValue() >= level.intValue()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    @BeforeEach
    void setUp() {
        logs = new Captured();
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(logs.logger);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "shutdown-inflight.db");
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(cm.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        assertTrue(db.isSQLite(), "前置：必须走 SQLite 分支喵");
        when(plugin.getDatabaseManager()).thenReturn(db);

        // 异步调度就地执行 —— 本类要的是「任务体在关库之后跑」这个确定的顺序，
        // 真线程池会让「它到底跑在关库之前还是之后」不可控，测不到窗口。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        store = new PlayerDataStore(plugin, db);
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    // ------------------------------------------------------------------ 库读取

    /** 直读真库拿某词当前 wrong_count；null = 行不存在。 */
    private Integer wrongCountInDb(String word) {
        // 关库之后不能再走生产 getConnection()，用独立连接读同一个文件。
        String url = "jdbc:sqlite:" + tempDir.resolve("shutdown-inflight.db").toAbsolutePath();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url);
             java.sql.PreparedStatement st = c.prepareStatement(
                     "SELECT wrong_count FROM " + PREFIX + "difficult_words"
                             + " WHERE player_uuid = ? AND word = ?")) {
            st.setString(1, PLAYER.toString());
            st.setString(2, word);
            try (java.sql.ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (java.sql.SQLException e) {
            throw new AssertionError("读库失败（本测试的读库路径不该失败）：" + e.getMessage(), e);
        }
    }

    private int aiRoundsInDb() {
        String url = "jdbc:sqlite:" + tempDir.resolve("shutdown-inflight.db").toAbsolutePath();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url);
             java.sql.PreparedStatement st = c.prepareStatement(
                     "SELECT rounds FROM " + PREFIX + "ai_round_counters"
                             + " WHERE player_uuid = ?")) {
            st.setString(1, PLAYER.toString());
            try (java.sql.ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (java.sql.SQLException e) {
            throw new AssertionError("读库失败：" + e.getMessage(), e);
        }
    }

    // ==================================================================
    // 用例 1：错题计数在途写撞上关库
    // ==================================================================

    /**
     * 关库后让 {@code incrementWrongCount} 的异步任务体跑一次真 SQL。
     *
     * <p>断言「库里原有的 3 逐字节不变」是核心：若代理没挡住、写打在半关闭的连接上，
     * 这里会看到 4（被加）或 0/行消失（被破坏）。
     */
    @Test
    @DisplayName("F3：关库后在途 incrementWrongCount 不抛、不改库、只留 WARNING")
    void inFlightIncrementAfterShutdownIsNoiseNotCorruption() {
        // 前置：库里先有一条真数据（3 次答错）。
        db.incrementWrongCount(PLAYER, WORD);
        db.incrementWrongCount(PLAYER, WORD);
        db.incrementWrongCount(PLAYER, WORD);
        assertEquals(3, wrongCountInDb(WORD), "前置：库里应是 3 喵");

        db.shutdown(); // onDisable 末尾那一步

        // 在途任务体（runIfSameEpoch 的 lambda 内层）现在跑。
        // 它内部是 databaseManager.incrementWrongCount(...)，后者自己 catch(SQLException)。
        db.incrementWrongCount(PLAYER, WORD); // 不 try/catch：若抛出去，本用例红

        assertEquals(3, wrongCountInDb(WORD),
                "关库后的在途写<b>不得改动库</b>喵。变成 4 说明它真的落库了（那反而说明"
                        + "关闭闩没生效，问题更大）；变成 0 / 行消失说明半关闭连接上的写把数据"
                        + "破坏了 —— 那就是必须修的数据损坏，不是日志噪音。");
        assertTrue(logs.anyAtOrAbove(Level.WARNING),
                "写失败必须留 WARNING 喵（「丢一次增量」这件事要能看见，不能静默）");
    }

    /** 同一条结论对 {@code decrementWrongCount} 成立 —— 它返回三态，失败必须是 FAILED。 */
    @Test
    @DisplayName("F3：关库后在途 decrementWrongCount 返回 FAILED（不被误判成可删行）")
    void inFlightDecrementAfterShutdownReturnsFailed() {
        db.incrementWrongCount(PLAYER, WORD);
        db.incrementWrongCount(PLAYER, WORD);
        assertEquals(2, wrongCountInDb(WORD), "前置：库里应是 2 喵");

        db.shutdown();

        DatabaseManager.DecrementResult r = db.decrementWrongCount(PLAYER, WORD);

        assertEquals(DatabaseManager.DecrementResult.FAILED, r,
                "关库后的 decrement 必须返回 FAILED（=「结果未知」）喵。"
                        + "若返回 NO_ROW_TO_DECREMENT，PlayerDataStore.decrementWrongCount 会据此"
                        + "<b>执行 DELETE</b> —— 而那条 DELETE 同样打不通，症状是「词悄悄留书」；"
                        + "更糟的是连接自愈的场景下它可能真的删成功。三态语义正是为这个窗口设的。");
        assertEquals(2, wrongCountInDb(WORD), "库不得被改动喵");
    }

    // ==================================================================
    // 用例 2：今日 AI 轮次在途写
    // ==================================================================

    @Test
    @DisplayName("F3：关库后在途 incrementAiRound 不抛、不改库、只留 WARNING")
    void inFlightAiRoundAfterShutdownIsNoiseNotCorruption() {
        db.incrementAiRound(PLAYER, java.time.LocalDate.now().toString());
        db.incrementAiRound(PLAYER, java.time.LocalDate.now().toString());
        assertEquals(2, aiRoundsInDb(), "前置：库里应是 2 喵");

        db.shutdown();

        db.incrementAiRound(PLAYER, java.time.LocalDate.now().toString()); // 不 try/catch

        assertEquals(2, aiRoundsInDb(), "关库后的在途写不得改动库喵");
        assertTrue(logs.anyAtOrAbove(Level.WARNING), "必须留 WARNING 喵");
    }

    // ==================================================================
    // 用例 3：UNAVAILABLE 代理的契约（本仓其它防线都依赖它）
    // ==================================================================

    /**
     * 关库后 {@code getConnection()} 必须返回「除了 isClosed/close/equals/hashCode/toString
     * 之外一律抛 {@code SQLException}」的代理，而<b>不是</b> null 或半关闭的真连接。
     *
     * <p>这条是 F3 判 ACCEPTED_RISK 的<b>根本前提</b>：整条降级链（f「丢一次写 + 一条
     * WARNING」而不是「写坏数据」）都建在这个契约上。它变了，F3 的结论就作废。
     */
    @Test
    @DisplayName("F3：关库后 getConnection 返回 UNAVAILABLE 代理（非 null、非真连接）")
    void shutdownLeavesAnUnavailableProxy() throws Exception {
        db.shutdown();
        java.sql.Connection c = db.getConnection();
        assertTrue(c != null, "关库后 getConnection 不得返回 null 喵 —— 全类 18 个裸取连接点"
                + "会 NPE，而 NPE 逃出 catch(SQLException)。");

        // 允许通过的那几个（代理的契约，见 DatabaseManager 的 UNAVAILABLE 定义）
        c.isClosed();
        c.toString();
        // 其余一律 SQLException
        try {
            c.prepareStatement("SELECT 1");
            throw new AssertionError("关库后的代理居然允许 prepareStatement 喵 —— "
                    + "整条降级链（丢写 + WARNING 而不是写坏数据）都建在「这里会抛」上。");
        } catch (java.sql.SQLException expected) {
            assertTrue(expected.getMessage().contains("Database connection unavailable"),
                    "抛的必须是可识别的不可用信号喵，实测：" + expected.getMessage());
        }
    }
}
