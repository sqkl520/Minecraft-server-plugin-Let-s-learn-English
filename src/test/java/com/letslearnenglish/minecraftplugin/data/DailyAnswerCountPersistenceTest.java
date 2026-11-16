package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;

/**
 * Round 17：{@code word-system.daily-limit} 的判据源（{@code PlayerDataStore.getTodayAnswerCount}）
 * 必须跨 quit / 重启保持真值。
 *
 * <h2>缺陷本体（三条绕过路径，前两条<b>单服</b>即可复现）</h2>
 * <p>答题计数此前是纯内存 {@code ConcurrentHashMap<UUID, TodayCounter>}：
 * <ol>
 *   <li>{@code PlayerDataStore.unloadPlayerData} 会 {@code todayAnswerCountCache.remove(playerId)}
 *       —— quit 后内存归零，退服重进 = 当日答题金币额度重置；</li>
 *   <li>重启内存归零，且<b>没有 seed 路径</b>可回读；</li>
 *   <li>多子服各算各的（需要 MySQL 才谈得上，严重度低于前两条）。</li>
 * </ol>
 * <p>对照同文件里<b>已经做对的</b>{@code todayAiRoundCache}：它有
 * {@code ai_round_counters} 表 + {@code incrementAiRound}/{@code getAiRoundCount} +
 * {@code getTodayAiRound} 的 seed 路径。答题计数是这套机制里唯一被漏掉的那个；
 * 修法即照抄其三层（seed → 内存立即生效 → 异步落库），见
 * {@code PlayerDataStore.incrementTodayAnswerCount} 的 javadoc。
 *
 * <h2>为什么必须用真 SQLite 文件库</h2>
 * <p>本条缺陷的全部内容是「内存态与库态之间是否一致」，mock 一个
 * {@code DatabaseManager} 只能验证「调用了哪个方法」，验证不了
 * 「换一个 {@code PlayerDataStore} 实例还能不能读回 30」——而那正是「重启不重置」
 * 的唯一判据。{@link #restartDoesNotResetAnswerCount} 因此真的起了第二个
 * {@code DatabaseManager} + 第二个 {@code PlayerDataStore} 指向同一个 {@code .db}
 * （先例：{@code NodeIdDerivationTest} 的「两个 DatabaseManager 指同一文件」）。
 *
 * <h2>每个用例守的是哪一半</h2>
 * <ul>
 *   <li>{@link #quitRejoinDoesNotResetAnswerCount} —— <b>seed 路径</b>；</li>
 *   <li>{@link #restartDoesNotResetAnswerCount} —— <b>异步落库 + seed</b> 两者都必需
 *       （只修 seed 不修落库 → 库里没有值；只修落库不修 seed → 新实例读不到）；</li>
 *   <li>{@link #yesterdayRowDoesNotCountToday} —— {@code answer_date} 真的参与判定
 *       （防「读回来不比对日期」把昨天的 45 当成今天的 45）；</li>
 *   <li>{@link #readFailureDegradesToZeroWithWarning} —— 读失败按 0 降级 + WARNING，
 *       对齐 {@code getAiRoundCount} 的降级语义，不把 DB 故障升级成发币路径崩溃；</li>
 *   <li>{@link #resetClearsTheLedger} —— 新表必须进 {@code deletePlayerData} 的
 *       {@code playerUuidTables}，否则 {@code /lea reset} 后额度被残行回读复活。</li>
 * </ul>
 */
@DisplayName("今日答题计数持久化 — quit / 重启 / 跨日 / 读失败降级 / reset 清收")
class DailyAnswerCountPersistenceTest {

    private static final String PREFIX = "lle_";
    /** 两个 DatabaseManager 必须指向同一个文件，才叫「一次重启」。 */
    private static final String DB_FILE = "daily-answer-count.db";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000dac001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000dac002");

    @TempDir
    Path tempDir;

    /**
     * plugin mock 在整类内复用：{@code LetsLearnEnglish} 继承链很深，单次
     * {@code mock()} 是十秒级开销（同 {@code NodeIdDerivationTest} 的记载）。
     * 每个用例只需重打 {@code getDataFolder()} 的桩。
     */
    private static LetsLearnEnglish plugin;
    private static Captured logs;

    /** 本用例开过的库/商店，{@code @AfterEach} 统一关，避免 SQLite 句柄泄漏到下一个用例。 */
    private final List<DatabaseManager> opened = new ArrayList<>();

    /** 日志探针：用例 4 要证明「降级时留下 WARNING」而不是静默吞。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final List<Level> levels = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("DailyAnswerCountPersistenceTest." + UUID.randomUUID());
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

        void clear() {
            messages.clear();
            levels.clear();
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    @BeforeAll
    static void createPluginMock() {
        logs = new Captured();
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(logs.logger);

        // 异步落库必须**就地执行**：本类要断言的是「落库真的发生了」，把任务丢给真线程池
        // 会让断言时刻不可控（且纯 JVM 下 mock 的 Server 返回 null 调度器）。
        // 就地执行不改生产语义 —— 生产上它仍是 async 线程，只是本类把「稍后」钉成「立刻」。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);
    }

    @BeforeEach
    void setUp() {
        logs.clear();
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
    }

    @AfterEach
    void closeDatabases() {
        for (DatabaseManager db : opened) {
            try {
                db.shutdown();
            } catch (RuntimeException ignored) {
                // 关库失败不该掩盖断言结果
            }
        }
        opened.clear();
    }

    // ==================================================================
    // 用例 1：退服重进不重置（seed 路径）
    // ==================================================================

    /**
     * 变异指向：删掉 {@code getTodayAnswerCount} 的 seed 分支（回到纯内存 get）→ 本用例红。
     *
     * <p>为什么同时用「独立 JDBC 读库」断言一次：只走生产读方法的话，若读写两侧
     * 对称地错（同一处笔误），round-trip 仍然自洽 —— 库里的真实值必须由本测试自己看见。
     */
    @Test
    @DisplayName("退服重进：increment 到 30 → unloadPlayerData → 仍读回 30（seed 生效）")
    void quitRejoinDoesNotResetAnswerCount() throws Exception {
        DatabaseManager db = boot();
        PlayerDataStore store = new PlayerDataStore(plugin, db);

        for (int i = 0; i < 30; i++) {
            store.incrementTodayAnswerCount(PLAYER);
        }
        assertEquals(30, store.getTodayAnswerCount(PLAYER), "前置：内存里应是 30");
        assertEquals(30, answersInDb(db, PLAYER, LocalDate.now()),
                "前置：30 次 increment 必须已落库（异步落库被就地执行）。库里不是 30 说明"
                        + "① 落库压根没接，或 ② UPSERT 的累加语义写错了（例如覆盖成 1）");

        store.unloadPlayerData(PLAYER); // = PlayerQuitListener.onPlayerQuit 的调用点

        assertEquals(30, store.getTodayAnswerCount(PLAYER),
                "退服重进不得重置当日答题额度喵。unloadPlayerData 会 remove 掉内存条目，"
                        + "修复前这里读到 0（= 反复退服重进即可无限刷 word-system.daily-limit）；"
                        + "修复后 getTodayAnswerCount 的 cache miss 分支必须从 "
                        + PREFIX + "daily_answer_counters seed 回真值。读到 0 说明 seed 路径没接上。");
    }

    // ==================================================================
    // 用例 2：重启不重置（落库 + seed 两者都必需）
    // ==================================================================

    /**
     * 变异指向：删掉异步落库（{@code increment} 只改内存）→ 新实例读到 0，本用例红；
     * 删掉 seed 路径 → 新实例读到 0，本用例红。**两个变异各自都能杀死本用例**，
     * 这正是「重启后仍有额度」需要同时具备两个半边的证明。
     */
    @Test
    @DisplayName("重启：第二个 PlayerDataStore 指向同一库 → 读回库里的 30")
    void restartDoesNotResetAnswerCount() throws Exception {
        DatabaseManager before = boot();
        PlayerDataStore firstBoot = new PlayerDataStore(plugin, before);
        for (int i = 0; i < 30; i++) {
            firstBoot.incrementTodayAnswerCount(PLAYER);
        }
        assertEquals(30, answersInDb(before, PLAYER, LocalDate.now()), "前置：库里应是 30");
        before.shutdown(); // 关服

        // 新进程：新 DatabaseManager + 新 PlayerDataStore，同一个 .db 文件
        DatabaseManager after = boot();
        PlayerDataStore secondBoot = new PlayerDataStore(plugin, after);

        assertEquals(30, secondBoot.getTodayAnswerCount(PLAYER),
                "重启不得重置当日答题额度喵。新进程的内存缓存是空的，唯一可能拿到 30 的途径是"
                        + "「上一次落库真的写进了 " + PREFIX + "daily_answer_counters」+「本次 "
                        + "cache miss 时真的回读了它」。读到 0 时逐项排查：库里有行吗"
                        + "（" + answersInDb(after, PLAYER, LocalDate.now()) + "）→ 无行 = 落库缺口；"
                        + "有行但仍返 0 = seed 缺口。");
    }

    // ==================================================================
    // 用例 3：跨日重置（answer_date 真的参与判定）
    // ==================================================================

    /**
     * 变异指向：把 seed 的查询条件里的 {@code answer_date = ?} 去掉（或换成不比对日期）
     * → 昨天那行的 45 被当成今天的值，本用例红。
     *
     * <p>同时反向断言「昨天的行仍在库里」：跨日重置靠的是<b>按今天查不到行</b>，
     * 不是靠删昨天的行（删了也没关系，但本测试要钉住的是查询口径而非清理时机）。
     */
    @Test
    @DisplayName("跨日：昨天有 45 行、今天无行 → 今日读 0，且昨天那行仍在")
    void yesterdayRowDoesNotCountToday() throws Exception {
        DatabaseManager db = boot();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        insertRow(PLAYER, yesterday, 45);

        PlayerDataStore store = new PlayerDataStore(plugin, db);
        assertEquals(0, store.getTodayAnswerCount(PLAYER),
                "昨天的 45 次答题不能算进今天喵。读到 45 说明 seed 回读了行却没过日期 —— "
                        + "跨日重置形同虚设，玩家的额度在整个生命周期内只增不减。");

        assertEquals(1, store.incrementTodayAnswerCount(PLAYER),
                "跨日后今天的第一题必须是 1，不能把昨天的 45 累加进来（实际读到 "
                        + store.getTodayAnswerCount(PLAYER) + "）");

        assertEquals(45, answersInDb(db, PLAYER, yesterday), "昨天的行不该被本次读取改动");
    }

    // ==================================================================
    // 用例 4：读失败降级（对齐 getAiRoundCount 口径）
    // ==================================================================

    /**
     * 真故障注入：{@code db.shutdown()} 之后 {@code getConnection()} 返回 UNAVAILABLE 代理，
     * 任何 JDBC 方法都抛 {@code SQLException}（见 {@code DatabaseManager.UNAVAILABLE} 的契约，
     * 已被 {@code PlayerDataStoreShutdownInFlightWriteTest} 钉住）。此时：
     * <ol>
     *   <li>不得把异常抛给调用方 —— 调用方是 {@code RewardEngine.checkDailyCaps}，
     *       它在发币路径上，异常逃出去等于「答题不发币 + 栈溢出到玩家」；</li>
     *   <li>必须返回 0（降级方向与 {@code getAiRoundCount} 一致：查不到就按 0）；</li>
     *   <li>必须留下 WARNING（「丢一次额度」这件事要可见，不能静默）。</li>
     * </ol>
     */
    @Test
    @DisplayName("读失败降级：库不可用 → 返回 0 + WARNING，异常不逃出")
    void readFailureDegradesToZeroWithWarning() {
        DatabaseManager db = boot();
        PlayerDataStore store = new PlayerDataStore(plugin, db);

        db.shutdown();
        logs.clear();

        int value = store.getTodayAnswerCount(PLAYER); // 不 try/catch：逃出即本用例红

        assertEquals(0, value,
                "读失败必须按 0 降级（同 getAiRoundCount 口径 —— 查不到就按 0，"
                        + "最坏后果是当日额度可重复，与修复前持平），实际 " + value);
        assertTrue(logs.anyAtOrAbove(Level.WARNING),
                "读失败必须留下 WARNING 喵 —— 与「玩家今天确实还没答题」区分开，"
                        + "否则额度门静默失效且无人知晓");
    }

    // ==================================================================
    // 用例 5：/lea reset 必须清收本表
    // ==================================================================

    /**
     * 变异指向：把 {@code "daily_answer_counters"} 从
     * {@code DatabaseManager.deletePlayerData} 的 {@code playerUuidTables} 里删掉 →
     * 本用例红。
     *
     * <p>为什么这条重要：本表是额度的<b>写穿真源</b>，残留行会让 reset 后的额度被
     * 回读「复活」——而且 reload / 重启都救不回来（与 {@code daily_coin_counters}
     * 的 R5-DC-02 缺陷同形）。
     */
    @Test
    @DisplayName("reset：deletePlayerData 清掉本玩家的答题计数行，旁观者不受影响")
    void resetClearsTheLedger() throws Exception {
        DatabaseManager db = boot();
        PlayerDataStore store = new PlayerDataStore(plugin, db);
        for (int i = 0; i < 7; i++) {
            store.incrementTodayAnswerCount(PLAYER);
            store.incrementTodayAnswerCount(OTHER);
        }
        assertEquals(7, answersInDb(db, PLAYER, LocalDate.now()), "前置：PLAYER 应是 7");

        assertTrue(db.deletePlayerData(PLAYER), "前置：reset 事务应成功");

        assertEquals(-1, answersInDb(db, PLAYER, LocalDate.now()),
                "reset 后本玩家的答题计数行必须被删喵。残留行会让额度被回读复活 —— "
                        + "本表已进 deletePlayerData 的 playerUuidTables，若这里仍是 7 说明"
                        + "清单里漏了这张表（与 R5-DC-02 的 daily_coin_counters 同形缺陷）");
        assertEquals(7, answersInDb(db, OTHER, LocalDate.now()),
                "旁观玩家的行不得被误删（防止「改成清全库」这种过度修复）");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 起一次「进程」：桩数据目录 → 构造 → 真 {@code initialize()} 到
     * {@code @TempDir} 下的同一个 db 文件（与 {@code NodeIdDerivationTest.boot} 同式）。
     */
    private DatabaseManager boot() {
        ConfigManager configManager = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", DB_FILE);
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(configManager.getMainConfig()).thenReturn(cfg);

        DatabaseManager db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：本类必须走真 SQLite 分支");
        when(plugin.getDatabaseManager()).thenReturn(db);
        opened.add(db);
        return db;
    }

    private String dbUrl() {
        return "jdbc:sqlite:" + tempDir.resolve(DB_FILE).toAbsolutePath();
    }

    /**
     * 独立 JDBC 连接直读某玩家某日的答题次数；<b>行不存在返回 -1</b>（与 0 区分）。
     *
     * <p>不必走生产 {@code getDailyAnswerCount}：那正是被测代码，用它做前置断言等于自证。
     */
    private int answersInDb(DatabaseManager db, UUID playerId, LocalDate date) throws SQLException {
        assertNotNull(db, "读库前必须已有 DatabaseManager");
        try (Connection c = DriverManager.getConnection(dbUrl());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT answers FROM " + PREFIX + "daily_answer_counters"
                             + " WHERE player_uuid = ? AND answer_date = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /** 直接往库里播种一条「某日已答 N 次」的行（模拟历史数据 / 昨天的行）。 */
    private void insertRow(UUID playerId, LocalDate date, int answers) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbUrl());
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO " + PREFIX + "daily_answer_counters"
                             + " (player_uuid, answer_date, answers) VALUES (?, ?, ?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, date.toString());
            ps.setInt(3, answers);
            ps.executeUpdate();
        }
    }
}
