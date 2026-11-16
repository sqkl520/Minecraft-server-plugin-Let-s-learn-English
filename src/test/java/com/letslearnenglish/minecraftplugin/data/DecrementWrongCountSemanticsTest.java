package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;

/**
 * F2（MEDIUM-HIGH）：{@code decrementWrongCount} 的 {@code boolean} <b>语义重载</b> ——
 * 把「写库失败」翻译成「删数据」。
 *
 * <h2>缺陷原文（改造前）</h2>
 * <p>{@code DatabaseManager.decrementWrongCount} 返回 {@code boolean}，两种毫无关系的含义
 * 共用 {@code false}：① 业务事实「SQL 执行成功、0 行被改（行不存在 / wrong_count 已达 1）」；
 * ② 故障事实「SQL 根本没执行成功（{@code SQLException}）」。而调用方
 * {@code PlayerDataStore.decrementWrongCount} 写的是
 * {@code if (!databaseManager.decrementWrongCount(...)) { removeDifficultWord(...); }} ——
 * <b>按 ① 消费</b>。
 *
 * <p>于是失败链是：一次瞬时写库故障（连接自愈窗口 / {@code SQLITE_BUSY} / 只读挂载）→
 * UPDATE 抛 {@code SQLException} → 返回 {@code false} → 调用方以为「计数到 1 该删行」→
 * 执行 DELETE → 若连接此刻已恢复，DELETE 成功 → <b>该词整行从错题本消失</b>；
 * 而缓存侧刚把计数从 N 减到 N-1（N&gt;1 时词还在缓存里），
 * <b>缓存说「错 3 次」、DB 里行没了</b>。
 *
 * <p>这正是「改成 {@code boolean} 反而更危险」的典型：改造前是 {@code void}，失败只落一条
 * WARNING、数据不动；改造后 {@code false} 成了业务信号，被消费成删除。
 *
 * <h2>修法</h2>
 * <p>三态枚举 {@link DatabaseManager.DecrementResult}（照本仓既有
 * {@link DatabaseManager.CheerResult} 范式）：{@code DECREMENTED} /
 * {@code NO_ROW_TO_DECREMENT} / {@code FAILED}，调用侧用 <b>switch 表达式</b>穷尽映射，
 * 只有 {@code NO_ROW_TO_DECREMENT} 才删行。
 *
 * <h2>本类怎么做到「真绿」而不是「自证桩」</h2>
 * <p>写失败<b>不是打桩打出来的</b>：真 SQLite 上执行 {@code PRAGMA query_only=ON}
 * （只读挂载 / MySQL {@code read_only=1} 的同形物），让 UPDATE 抛真实的
 * {@code SQLITE_READONLY}，而同一条连接上的 SELECT 仍然健康 —— 「写失败 + 读成功」
 * 这个本缺陷赖以成立的真实形态因此完整复现。
 *
 * <h2>每条用例封死的变异</h2>
 * <ol>
 *   <li>{@link #decrementedWhenRowHasCountAboveOne()} —— 正常减 1 路径不得回归；</li>
 *   <li>{@link #noRowWhenCountAlreadyOne()} / {@link #noRowWhenWordAbsent()} ——
 *       「真的没有可减的行」仍必须是 {@code NO_ROW_TO_DECREMENT}（防「一律报 FAILED」的过度修复，
 *       那会让玩家练到 0 的词永远删不掉、卡在错题本里）；</li>
 *   <li>{@link #writeFailureIsFailedNotNoRow()} —— <b>核心</b>：把 {@code catch (SQLException)}
 *       里改回 {@code return NO_ROW_TO_DECREMENT}（等价于原 {@code return false}），立刻红；</li>
 *   <li>{@link #storeMustNotDeleteOnWriteFailure()} —— {@code PlayerDataStore} 层的
 *       Mockito {@code verify(never())}，把 {@code case FAILED -> false} 改成 {@code true}
 *       立刻红；</li>
 *   <li>{@link #storeStillDeletesWhenNoRowToDecrement()} —— 防「永远不删」的过度修复；</li>
 *   <li>{@link #writeFailureKeepsRowInRealDatabase()} —— 全链路：真库 + 真 {@code PlayerDataStore}，
 *       写失败后行必须还在（这一条不依赖任何 mock 的返回约定）。</li>
 * </ol>
 */
class DecrementWrongCountSemanticsTest {

    private static final String PREFIX = "lle_";

    @TempDir
    Path tempDir;

    private LetsLearnEnglish plugin;
    private DatabaseManager db;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        // 真 Logger：失败路径的 log(WARNING, ..., e) 需要真实例，否则 NPE 会掩盖真实行为。
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DecrementWrongCountSemanticsTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "decrement-semantics.db");
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(cm.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        when(plugin.getDatabaseManager()).thenReturn(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            // query_only 若因用例失败留成 ON，shutdown 时的收尾写会失败；先尽力恢复。
            try (Statement st = db.getConnection().createStatement()) {
                st.execute("PRAGMA query_only=OFF");
            } catch (Exception ignored) {
                // no-op：临时库随 TempDir 删除
            }
            db.shutdown();
        }
    }

    // ==================================================================
    // 数据层：三态语义（真 SQLite）
    // ==================================================================

    @Test
    @DisplayName("wrong_count=3 且条件满足 → DECREMENTED，行仍在且计数变 2")
    void decrementedWhenRowHasCountAboveOne() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000d001");
        insertWord(id, "apple", 3);

        DatabaseManager.DecrementResult result = db.decrementWrongCount(id, "apple");

        assertEquals(DatabaseManager.DecrementResult.DECREMENTED, result,
                "有行且 wrong_count>1 时必须返回 DECREMENTED 喵。");
        assertEquals(2, countInDb(id, "apple"), "计数应从 3 减到 2");
    }

    @Test
    @DisplayName("wrong_count 已达 1 → NO_ROW_TO_DECREMENT（调用方据此删行），计数不得变 0 或负数")
    void noRowWhenCountAlreadyOne() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000d002");
        insertWord(id, "apple", 1);

        DatabaseManager.DecrementResult result = db.decrementWrongCount(id, "apple");

        assertEquals(DatabaseManager.DecrementResult.NO_ROW_TO_DECREMENT, result,
                "计数已达 1 时 SQL 的 wrong_count > 1 条件不满足，0 行被改 —— "
                        + "这是<b>业务事实</b>，必须是 NO_ROW_TO_DECREMENT（调用方据此删行）喵。\n"
                        + "把它改成 FAILED 属于过度修复：玩家练到 0 的词会永远删不掉，"
                        + "卡在错题本里再也出不来。");
        assertEquals(1, countInDb(id, "apple"), "0 行被改 ⇒ 计数不得被减成 0");
    }

    @Test
    @DisplayName("行不存在 → NO_ROW_TO_DECREMENT（与「已达 1」同为业务事实）")
    void noRowWhenWordAbsent() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000d003");

        DatabaseManager.DecrementResult result = db.decrementWrongCount(id, "ghost");

        assertEquals(DatabaseManager.DecrementResult.NO_ROW_TO_DECREMENT, result,
                "行不存在时 0 行被改，同样是业务事实喵（调用方的 removeDifficultWord 是幂等的，"
                        + "重复删除无害）。");
        assertEquals(-1, countInDb(id, "ghost"), "前置：行确实不存在");
    }

    /**
     * <b>本类的核心用例</b>：写库失败必须自成一态，且同一时刻读侧仍然健康。
     *
     * <p>注入手段 {@code PRAGMA query_only=ON} 只挡写不挡读 —— 于是「写失败 + 读成功」这个
     * 真实形态完整成立。原实现正是在这种形态下返回 {@code false}，被调用方消费成「该删行」。
     *
     * <p>红队变异：把 {@code catch (SQLException)} 里的
     * {@code return DecrementResult.FAILED} 改回 {@code return DecrementResult.NO_ROW_TO_DECREMENT}
     * （等价于原 {@code return false}），本用例立刻红。
     */
    @Test
    @DisplayName("写库失败（SQLITE_READONLY）→ FAILED，且读侧同一时刻仍健康")
    void writeFailureIsFailedNotNoRow() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000d004");
        insertWord(id, "apple", 3);

        execOnDb("PRAGMA query_only=ON");
        DatabaseManager.DecrementResult result;
        boolean readFailed;
        try {
            result = db.decrementWrongCount(id, "apple");
            readFailed = db.loadDifficultWords(id).readFailed();
        } finally {
            execOnDb("PRAGMA query_only=OFF");
        }

        assertEquals(DatabaseManager.DecrementResult.FAILED, result,
                "写库故障必须自成一态（DecrementResult.FAILED），实际拿到 " + result + " 喵。\n"
                        + "原实现这里返回 false，与「0 行被改」逐字节同形，于是调用方"
                        + "（PlayerDataStore.decrementWrongCount）把它消费成「计数到 1 该删行」，"
                        + "执行 DELETE —— 若连接此刻已恢复，那个词整行从错题本消失，"
                        + "而缓存里还留着「错 3 次」。");
        assertFalse(readFailed,
                "读侧必须仍然健康，才构成「写失败 + 读成功」这个真实形态喵"
                        + "（readFailed=" + readFailed + "）。\n"
                        + "若读也失败，本形态就退化成「整体宕机」，证明不了"
                        + "「用写失败以外的信号判断结果」这条路走不通。");
        assertEquals(3, countInDb(id, "apple"), "写失败时计数必须原封不动（3，不是 2、不是 0）");
    }

    // ==================================================================
    // 消费层：PlayerDataStore 只在 NO_ROW_TO_DECREMENT 上删行
    // ==================================================================

    /** 造一个 {@code PlayerDataStore}，其异步任务<b>就地同步</b>执行，断言时刻可控。 */
    private PlayerDataStore storeWith(DatabaseManager databaseManager) {
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(LetsLearnEnglish.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return null;
                });
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);
        return new PlayerDataStore(plugin, databaseManager);
    }

    /**
     * <b>核心回归（消费侧）</b>：数据层报 {@code FAILED} 时，{@code removeDifficultWord} 一次都不得被调用。
     *
     * <p>红队变异：把 {@code PlayerDataStore} 里
     * {@code case DECREMENTED, FAILED -> false} 中的 {@code FAILED} 挪到 {@code true} 那一支，
     * 本用例立刻红（verify 数到 1 次 DELETE）。
     */
    @Test
    @DisplayName("PlayerDataStore：数据层 FAILED → removeDifficultWord 绝不被调用")
    void storeMustNotDeleteOnWriteFailure() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000d005");
        DatabaseManager mocked = mock(DatabaseManager.class);
        when(mocked.decrementWrongCount(id, "apple"))
                .thenReturn(DatabaseManager.DecrementResult.FAILED);
        PlayerDataStore store = storeWith(mocked);

        store.decrementWrongCount(id, "apple");

        verify(mocked, never()).removeDifficultWord(any(UUID.class), anyString());
        verify(mocked, never()).removeDifficultWord(any(), any());
    }

    /** 反过度修复：{@code NO_ROW_TO_DECREMENT} 必须<b>真的</b>删行（否则错题本永久残留）。 */
    @Test
    @DisplayName("PlayerDataStore：数据层 NO_ROW_TO_DECREMENT → 恰好一次 removeDifficultWord")
    void storeStillDeletesWhenNoRowToDecrement() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000d006");
        DatabaseManager mocked = mock(DatabaseManager.class);
        when(mocked.decrementWrongCount(id, "apple"))
                .thenReturn(DatabaseManager.DecrementResult.NO_ROW_TO_DECREMENT);
        PlayerDataStore store = storeWith(mocked);

        store.decrementWrongCount(id, "apple");

        verify(mocked, times(1)).removeDifficultWord(id, "apple");
    }

    /** {@code DECREMENTED} 也不得删行 —— 词仍在错题本里。 */
    @Test
    @DisplayName("PlayerDataStore：数据层 DECREMENTED → 不删行")
    void storeMustNotDeleteWhenDecremented() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000d007");
        DatabaseManager mocked = mock(DatabaseManager.class);
        when(mocked.decrementWrongCount(id, "apple"))
                .thenReturn(DatabaseManager.DecrementResult.DECREMENTED);
        PlayerDataStore store = storeWith(mocked);

        store.decrementWrongCount(id, "apple");

        verify(mocked, never()).removeDifficultWord(any(UUID.class), anyString());
    }

    /**
     * 全链路（真库 + 真 {@code PlayerDataStore}，无任何「返回约定」的打桩）：
     * 写库失败后，{@code difficult_words} 里那一行必须原封不动。
     *
     * <p><b>为什么用「只挡 UPDATE 的触发器」而不是 {@code PRAGMA query_only=ON}</b>：
     * 本缺陷的原始后果链是「UPDATE 抛异常 → 调用方以为该删行 → <b>DELETE 成功</b>」——
     * 也就是说删除必须<b>能成功</b>，故障才有后果。{@code query_only} 会把 DELETE 一起挡掉，
     * 于是即便控制流没修（FAILED 被当成 NO_ROW），DELETE 也只是失败并记条 WARNING，
     * 行照样还在 —— 用例假绿。{@code BEFORE UPDATE ... RAISE(ABORT)} 精确地只让 UPDATE 失败，
     * 复刻「写失败 + 随后的 DELETE 成功」这个真实形态。
     *
     * <p>因此本用例自带<b>前置自检</b>：故障窗口内 DELETE 必须真的能生效（否则本用例证明不了任何事）。
     *
     * <p>为什么这条单独存在：上面三条消费侧用例的 {@code FAILED} 都是 mock 出来的约定，
     * 若有人把数据层的 {@code catch} 改回 {@code NO_ROW_TO_DECREMENT}，它们照样全绿
     * （假绿形态「自证桩」）。本条把真实写失败喂进真实链路，只有控制流的修复才活得下来。
     */
    @Test
    @DisplayName("全链路（真库）：UPDATE 失败而 DELETE 可用时，行必须还在（原始后果链）")
    void writeFailureKeepsRowInRealDatabase() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000d008");
        insertWord(id, "apple", 3);
        insertWord(id, "probe", 5);

        PlayerDataStore store = storeWith(db);

        // 只挡 UPDATE：UPDATE 抛 SQLITE_CONSTRAINT（RAISE(ABORT)），DELETE 完全不受影响。
        execOnDb("CREATE TRIGGER " + PREFIX + "reject_update BEFORE UPDATE ON " + PREFIX
                + "difficult_words BEGIN SELECT RAISE(ABORT, 'update not permitted'); END");
        try {
            // 前置自检：同一故障窗口内 DELETE 必须真的生效 —— 否则本用例是空转。
            // （'probe' 只用于这条自检，不参与下面的主断言。）
            db.removeDifficultWord(id, "probe");
            assertEquals(-1, countInDb(id, "probe"),
                    "前置自检失败喵：本故障注入把 DELETE 也挡掉了，于是「UPDATE 失败 → DELETE 成功」"
                            + "这条真实后果链根本没被复现，主断言会退化成空转（假绿）。");

            store.decrementWrongCount(id, "apple");
        } finally {
            execOnDb("DROP TRIGGER IF EXISTS " + PREFIX + "reject_update");
        }

        assertEquals(3, countInDb(id, "apple"),
                "写库失败被翻译成了 DELETE：apple 的行没了（或计数被改）喵。\n"
                        + "原始后果链：UPDATE 抛 SQLException → 旧实现返回 false → 调用方以为"
                        + "「计数到 1 该删行」→ 此刻 UPDATE 已不再被执行、DELETE 却完全可用 → "
                        + "该词整行从错题本消失，而缓存里还留着「错 3 次」。\n"
                        + "正确语义：SQLException = 结果未知，调用方不得删行 —— "
                        + "多留一行只是多显示一轮，错删一行即刻永久丢失且无报错。");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void execOnDb(String sql) throws SQLException {
        synchronized (db.getConnectionLock()) {
            try (Statement st = db.getConnection().createStatement()) {
                st.execute(sql);
            }
        }
    }

    private void insertWord(UUID playerId, String word, int count) throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + db.getSqliteFilePath());
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO " + PREFIX + "difficult_words"
                             + " (player_uuid, word, wrong_count) VALUES (?, ?, ?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, word);
            ps.setInt(3, count);
            ps.executeUpdate();
        }
    }

    /** 读某行某列；行不存在返回 -1。 */
    private int countInDb(UUID playerId, String word) throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + db.getSqliteFilePath());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT wrong_count FROM " + PREFIX + "difficult_words"
                             + " WHERE player_uuid = ? AND word = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, word);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}
