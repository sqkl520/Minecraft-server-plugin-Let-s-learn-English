package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R11：{@code assignDailyChallengeWords} 的 {@code void → boolean} 契约。
 *
 * <p><b>缺陷</b>：原实现自己 {@code catch (SQLException)} 只记 WARNING，而调用方
 * {@code DailyChallengeManager.ensureWordChallengesAssignedLocked} 紧接着无条件打印
 * "Assigned N daily challenge words to player …" —— <b>写库失败与"已分配"同时出现在日志里</b>。
 * 挑战词是当日单词挑战的前提：没写进 DB，玩家当天就没有挑战可做（每答对一题都会再空回查一次）。
 *
 * <h2>两个方向</h2>
 * <ul>
 *   <li>{@link #failedBatchReturnsFalse}：注入真实故障 → 必须 {@code false}，且表里必须
 *       <b>一行都没有</b>（证明 false 的语义是"这批词一个也没落库"）。把返回值改成恒
 *       {@code true}（继续吞掉失败）→ 本用例红。</li>
 *   <li>{@link #successReturnsTrueAndRowsLand}：正常库 → 必须 {@code true} 且行已落；
 *       把返回值改成恒 {@code false}（过度失败）→ 本用例红。</li>
 *   <li>{@link #emptyBatchIsSuccess}：空列表 = 一次空 batch，按成功处理（不是失败）。
 *       这条封死"用 {@code executeBatch()} 返回计数长度做判据"的写法 —— 空批返回
 *       {@code int[0]}，若以"长度&gt;0"判成功，正常路径会被误报成失败。</li>
 * </ul>
 *
 * <p><b>故障注入手段</b>：把 {@code daily_challenge_words} 改名（{@code ALTER TABLE … RENAME}）
 * → {@code INSERT} 抛 {@code no such table}，这是真实的 SQLException，不经 mock。
 * 用真 SQLite 的理由：判据是"batch 是否真的提交了"，只有驱动能给出这个事实。
 */
class AssignDailyChallengeWordsReturnTest {

    @TempDir
    Path tempDir;

    private File dbFile;
    private DatabaseManager db;

    @BeforeEach
    void setUp() throws Exception {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AssignDailyChallengeWordsReturnTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager configManager = mock(ConfigManager.class);
        org.bukkit.configuration.file.FileConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.set("database.type", "sqlite");
        // 键名必须与 DatabaseManager 实际读的一致，否则 initialize() 会在别处落库，
        // 而本测试的独立 JDBC 连接指向另一个文件 —— 表现为「表不存在」的假错。
        cfg.set("database.sqlite.filename", "assign-return.db");
        cfg.set("database.mysql.table-prefix", "lle_");
        when(configManager.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则故障注入手段无效");
        dbFile = new File(tempDir.toFile(), "assign-return.db");
        assertTrue(dbFile.isFile(), "前置条件：SQLite 文件未创建，路径推导错了");
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private long countWords(UUID playerId, String date) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lle_daily_challenge_words"
                     + " WHERE player_uuid = '" + playerId + "'"
                     + " AND challenge_date = '" + date + "'")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000a55e");
    private static final String TODAY = "2026-11-10";

    @Test
    @DisplayName("成功路径：返回 true 且 5 行真的落库")
    void successReturnsTrueAndRowsLand() throws Exception {
        boolean ok = db.assignDailyChallengeWords(PLAYER, TODAY,
                List.of("alpha", "bravo", "charlie", "delta", "echo"));

        assertTrue(ok, "正常写入却返回 false —— 调用方会打 SEVERE 说「今天没有挑战」，"
                + "而词其实已经分配好了（过度失败，方向同样有害）");
        assertEquals(5, countWords(PLAYER, TODAY),
                "返回了 true 但行数不对 —— 返回值与事实不符，正是本缺陷的镜像");
    }

    @Test
    @DisplayName("写库失败：返回 false，且一行都没落（不是部分落）")
    void failedBatchReturnsFalse() throws Exception {
        // 故障注入：把表改名，让 INSERT 抛 no such table（真实 SQLException）。
        exec("ALTER TABLE lle_daily_challenge_words RENAME TO lle_daily_challenge_words_gone");

        boolean ok = db.assignDailyChallengeWords(PLAYER, TODAY, List.of("alpha", "bravo"));

        assertFalse(ok, "写库失败却返回 true —— 调用方紧接着会打 "
                + "\"Assigned 2 daily challenge words\"，日志自相矛盾，"
                + "而玩家当天根本没有挑战可做");
        // 表已改名，回读必须走同一张"不存在的表"的反面：确认原名下没有新区。
        long rows;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lle_daily_challenge_words_gone")) {
            rows = rs.next() ? rs.getLong(1) : -1L;
        }
        assertEquals(0, rows, "返回 false 但表里已有行 —— false 的语义必须是「一个词也没落库」");
    }

    @Test
    @DisplayName("空列表 = 空 batch，按成功处理（封死用返回计数长度判成功）")
    void emptyBatchIsSuccess() {
        boolean ok = db.assignDailyChallengeWords(PLAYER, TODAY, List.of());

        assertTrue(ok, "空 batch 被当成失败 —— executeBatch() 对空批返回 int[0]，"
                + "若以「计数长度 > 0」判成功，正常的「今天没有候选词」路径会被误报成写库失败，"
                + "调用方每次 tick 打一条 SEVERE 日志");
    }

    /**
     * 结构守卫：签名必须是 {@code boolean}，不能悄悄改回 {@code void}。
     *
     * <p>为什么需要它：{@code void} 版本编译后与原样完全一致，**任何行为测试都发现不了**
     * 返回值被删掉（调用方忽略返回值在 Java 里完全合法，所有用例仍绿）。这是本项目
     * 「变异验证才是唯一证明」一条踩过的坑：可观测的只有方法签名本身。
     */
    @Test
    @DisplayName("结构守卫：assignDailyChallengeWords 的返回类型仍是 boolean")
    void signatureStaysBoolean() throws Exception {
        Method m = DatabaseManager.class.getMethod("assignDailyChallengeWords",
                UUID.class, String.class, List.class);
        assertEquals(boolean.class, m.getReturnType(),
                "assignDailyChallengeWords 不再是 boolean —— 调用方再也拿不到成败，"
                        + "「分配失败但日志说已分配」的缺陷会立刻复发");
    }
}
