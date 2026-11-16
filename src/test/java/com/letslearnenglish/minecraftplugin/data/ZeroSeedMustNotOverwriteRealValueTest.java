package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R10-s16dc-01（round10 · slice-16 data-consistency 首次审计，HIGH）：
 * 读库失败后的「零值种子」不得被当作权威基数写回数据库。
 *
 * <p><b>缺陷链</b>（5 环缺一不可）：
 * <ol>
 *   <li>{@code loadPlayerData} 把 {@code conversationCount}/{@code wrongBookCleared} 的局部初值
 *       设为 0（`PlayerDataStore:674-675`）；</li>
 *   <li>读异常时 catch 只 {@code statsUnloaded.add}，<b>不覆盖</b>那两个局部变量（`:696-700`）；</li>
 *   <li>但零值仍被无条件灌进缓存（`:707-710`）；</li>
 *   <li>{@code incrementConversationCount} 用 {@code containsKey} 判断是否要重载 —— 键已在，
 *       所以<b>不重载</b>，直接在 0 上 {@code merge(+1)} 得 1；</li>
 *   <li>{@code setConversationCount} 是<b>绝对值 UPSERT</b>，DB 里真实的 N 被覆写成 1。</li>
 * </ol>
 *
 * <p>后果：一次 DB 抖动让玩家的会话计数与「错题本攻克数」永久归零，成就进度同步回退，
 * 且无任何回滚路径。
 *
 * <p><b>变异检验</b>：把修复的 {@code statsUnloaded.contains} 守卫删掉，
 * {@link ZeroSeedMustNotOverwriteRealValue#failedLoadMustNotOverwriteDbValue} 立刻变红
 * （DB 里的 200 会变成 1）。
 */
class ZeroSeedMustNotOverwriteRealValueTest {

    @TempDir
    Path tempDir;

    private File dbFile;
    private LetsLearnEnglish plugin;
    private DatabaseManager db;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ZeroSeedTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "zero-seed.db");
        cfg.set("database.mysql.table-prefix", "lle_");
        when(cm.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        when(plugin.getDatabaseManager()).thenReturn(db);
        dbFile = new File(tempDir.toFile(), "zero-seed.db");
        assertTrue(dbFile.isFile(), "前置：SQLite 文件未创建");
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.shutdown();
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) { st.executeUpdate(sql); }
    }

    private long readInt(String col, String uuid) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT " + col + " FROM lle_player_data WHERE uuid = '" + uuid + "'")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    @Test
    @DisplayName("读失败后 +1 不得把 DB 里的真实值覆写成 1")
    void failedLoadMustNotOverwriteDbValue() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000ab01");
        String s = id.toString();

        // 前置：DB 里已有真实值 200 / 50。
        exec("INSERT OR REPLACE INTO lle_player_data (uuid, player_name, total_sessions,"
                + " total_score, conversation_count, wrong_book_cleared)"
                + " VALUES ('" + s + "', 'seedtest', 0, 0, 200, 50)");
        assertEquals(200, readInt("conversation_count", s), "前置：种子值未写入");

        PlayerDataStore store = new PlayerDataStore(plugin, db);

        // 制造「读失败」：把**统计查询要读的列**改名。
        //
        // 试过两种不行的做法（记下来免得后人再踩）：
        //   ① ALTER TABLE ... RENAME —— SQLite 会把改名同步进既有连接的表引用，
        //      SELECT 照样成功，读失败根本不发生；
        //   ② DROP COLUMN —— 读确实失败了，但后续 setConversationCount 的 UPSERT
        //      也要写那一列，于是报 no such column，测的就不是我们要测的东西了。
        //
        // 这里改用「列改名」：`conversation_count` → `conversation_count_hidden`。
        // SELECT 里写的是旧名 → 抛 no such column（读失败）；
        // 而 setConversationCount 的 UPSERT 也写旧名 → 同样会失败……
        //
        // 因此最干净的构造是**在缓存里预置一个零值种子**，直接模拟「读失败后零值入缓存」
        // 那个状态 —— 不依赖真实读失败，只测「零值种子 + statsUnloaded 标记」下的写库决策。
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            // 直接制造与 loadPlayerData 读失败后完全相同的可观察状态：
            //   statsUnloaded 含该 id
            //   conversationCountCache 里是零值种子
            // 用反射注入口见下方 helper（生产代码没有公开入口，这正是缺陷的结构特征）。
        }
        injectFailedLoadState(store, id);

        assertTrue(store.isStatsUnloaded(id),
                "前置失败：statsUnloaded 未置位，本用例失去意义");

        // 被测动作：在「读失败 + 零值种子已入缓存」的状态下 +1。
        int after = store.incrementConversationCount(id);

        // 断言 1：返回值仍然推进（调用方靠它推成就，返回旧值会丢进度）。
        assertEquals(1, after, "零值种子上 +1 应得 1（内存值）");

        // 断言 2（核心）：DB 里的 200 必须原封不动。
        long dbValue = readInt("conversation_count", s);
        assertEquals(200, dbValue,
                "DB 里的真实值 200 被零值种子覆写成了 " + dbValue
                        + " —— 一次读库抖动永久清零玩家的会话计数与成就进度");
    }

    /**
     * 用反射构造出「loadPlayerData 读失败」后的可观察状态：
     * {@code statsUnloaded} 含该 id，且两个计数器缓存里是该玩家的零值种子。
     *
     * <p>生产代码没有公开入口能做到这件事 —— 这正是缺陷的结构特征：零值种子只能由
     * 「读失败」产生。测试用反射精确复刻那个状态，而不是依赖某种特定的 DB 故障注入
     * （后者要么不触发读失败、要么连写库一起弄坏）。
     */
    @SuppressWarnings("unchecked")
    private void injectFailedLoadState(PlayerDataStore store, UUID id) throws Exception {
        java.lang.reflect.Field unloadedF = PlayerDataStore.class.getDeclaredField("statsUnloaded");
        unloadedF.setAccessible(true);
        ((java.util.Set<UUID>) unloadedF.get(store)).add(id);

        java.lang.reflect.Field convF = PlayerDataStore.class.getDeclaredField("conversationCountCache");
        convF.setAccessible(true);
        ((java.util.Map<UUID, Integer>) convF.get(store)).put(id, 0);

        java.lang.reflect.Field wbF = PlayerDataStore.class.getDeclaredField("wrongBookClearedCache");
        wbF.setAccessible(true);
        ((java.util.Map<UUID, Integer>) wbF.get(store)).put(id, 0);
    }

    @Test
    @DisplayName("读成功时仍正常落库（防「一律不写」的过度修复）")
    void healthyLoadStillPersists() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000ab002");
        String s = id.toString();
        exec("INSERT OR REPLACE INTO lle_player_data (uuid, player_name, total_sessions,"
                + " total_score, conversation_count, wrong_book_cleared)"
                + " VALUES ('" + s + "', 'healthy', 0, 0, 7, 0)");

        PlayerDataStore store = new PlayerDataStore(plugin, db);
        store.loadPlayerData(id);
        int after = store.incrementConversationCount(id);

        assertEquals(8, after, "读成功时应在真实基数 7 上 +1 得 8");
        assertEquals(8, readInt("conversation_count", s),
                "读成功时 +1 必须落库 —— 若这里还是 7，说明修复把正常路径也堵死了");
    }

    @Test
    @DisplayName("错题本攻克数同理：读失败不覆写")
    void wrongBookClearedSameProtection() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000ab003");
        String s = id.toString();
        exec("INSERT OR REPLACE INTO lle_player_data (uuid, player_name, total_sessions,"
                + " total_score, conversation_count, wrong_book_cleared)"
                + " VALUES ('" + s + "', 'wb', 0, 0, 0, 50)");

        PlayerDataStore store = new PlayerDataStore(plugin, db);
        exec("ALTER TABLE lle_player_data RENAME TO lle_player_data_hidden");
        try {
            store.loadPlayerData(id);
            store.incrementWrongBookCleared(id);
            exec("ALTER TABLE lle_player_data_hidden RENAME TO lle_player_data");
            assertEquals(50, readInt("wrong_book_cleared", s),
                    "wrong_book_cleared 的 50 被零值种子覆写 —— 同一缺陷的第二个入口");
        } finally {
            try { exec("ALTER TABLE lle_player_data_hidden RENAME TO lle_player_data"); }
            catch (Exception ignored) { }
        }
    }
}
