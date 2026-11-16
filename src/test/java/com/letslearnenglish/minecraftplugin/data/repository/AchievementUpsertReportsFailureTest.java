package com.letslearnenglish.minecraftplugin.data.repository;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R4-DC-02（round6）：{@code AchievementRepository.upsertUnlock} 必须把写库成败
 * 通过返回值暴露给调用方。
 *
 * <p><b>缺陷</b>：原实现返回 {@code void} 且把 {@code SQLException} 在仓储内部吞成
 * WARNING。上层 {@code AchievementManager.flushPendingUnlockWrites} 只能
 * {@code catch (RuntimeException)} —— 那条 catch 对「SQL 写失败」这条<b>原始缺陷的实际
 * 触发路径</b>完全不生效。于是待补写被 {@code remove} 走却不会回填，一次 DB 抖动就让该
 * 玩家<b>全部</b>待补写成就静默丢弃，与修复前的最终后果完全相同。
 *
 * <p>这组测试直接打真 SQLite：让 {@code achievements} 表不可写（表被删），断言
 * {@code upsertUnlock} 返回 {@code false}；正常情况返回 {@code true}。
 *
 * <p><b>变异验证</b>（编排者手工执行）：把 {@code return false} 改回 {@code void} 语义
 * （即 catch 后不返回、方法签名退回 void），本类编译会失败 —— 这正是「返回值是契约的一部分」
 * 的强证据。若只把 {@code return false} 改成 {@code return true}（谎报成功），
 * {@link WriteFailureIsReported} 必须变红。
 */
class AchievementUpsertReportsFailureTest {

    @TempDir
    Path tempDir;

    private LetsLearnEnglish plugin;
    private DatabaseManager db;
    private AchievementRepository repo;
    private File dbFile;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AchievementUpsertReportsFailureTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        com.letslearnenglish.minecraftplugin.config.ConfigManager cm =
                mock(com.letslearnenglish.minecraftplugin.config.ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "ach-upsert.db");
        cfg.set("database.mysql.table-prefix", "lle_");
        when(cm.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        dbFile = new File(tempDir.toFile(), "ach-upsert.db");
        repo = new AchievementRepository(db, Logger.getLogger("AchievementUpsertReportsFailureTest"));
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    private long countRows() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                     "jdbc:sqlite:" + dbFile.getAbsolutePath());
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lle_achievements")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    @Test
    @DisplayName("写成功返回 true，且行真的进库")
    void healthyWriteReturnsTrue() throws Exception {
        UUID id = UUID.randomUUID();
        boolean ok = repo.upsertUnlock(id, "ACH_FIRST", 1000L);
        assertTrue(ok, "正常写入却返回 false —— 调用方会把成功的写入当成失败重试");
        assertTrue(countRows() >= 1, "返回 true 但库里没有行 —— 谎报成功");
    }

    /**
     * 核心用例：让表不可写（drop 掉），{@code upsertUnlock} 必须返回 {@code false}。
     *
     * <p><b>为什么用 drop 而不是 mock</b>：原始缺陷的触发路径是「仓储内部的
     * {@code catch (SQLException)} 吞掉真实 SQL 错误」。用真 DB 制造真实
     * {@code SQLException}，才能证明返回值确实反映了那条路径。
     */
    @Test
    @DisplayName("写库失败返回 false（表不存在 → 真实 SQLException）")
    void writeFailureReturnsFalse() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                     "jdbc:sqlite:" + dbFile.getAbsolutePath());
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE lle_achievements");
        }

        boolean ok = repo.upsertUnlock(UUID.randomUUID(), "ACH_X", 12345L);

        assertFalse(ok,
                "写库失败却返回 true —— 这就是原缺陷：SQLException 被仓储吞成 WARNING，"
                        + "上层 flushPendingUnlockWrites 的 catch(RuntimeException) 永远不触发，"
                        + "待补写被 remove 走却不回填，该玩家全部待补写成就静默丢弃");
    }

    @Test
    @DisplayName("撞键 no-op 也算成功（行已存在，语义已满足）")
    void duplicateIsStillSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(repo.upsertUnlock(id, "ACH_DUP", 1000L), "首次写入应成功");
        assertTrue(repo.upsertUnlock(id, "ACH_DUP", 9999L),
                "撞键 no-op 应返回 true —— 行已在库里，语义已满足。"
                        + "返回 false 会让调用方无限重试一条永远写不进去的记录");
    }

    /**
     * 回归守卫：撞键必须保留原始 {@code unlocked_at}（round5 L-01 的已验收修复）。
     */
    @Test
    @DisplayName("回归：撞键不覆写 unlocked_at（round5 L-01 未被破坏）")
    void duplicateKeepsOriginalTimestamp() throws Exception {
        UUID id = UUID.randomUUID();
        repo.upsertUnlock(id, "ACH_TS", 1000L);
        repo.upsertUnlock(id, "ACH_TS", 9999L);

        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                     "jdbc:sqlite:" + dbFile.getAbsolutePath());
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT unlocked_at FROM lle_achievements WHERE uuid = '" + id
                             + "' AND achievement_id = 'ACH_TS'")) {
            assertTrue(rs.next(), "行不见了");
            long at = rs.getLong(1);
            org.junit.jupiter.api.Assertions.assertEquals(1000L, at,
                    "撞键把 unlocked_at 覆写成了 " + at
                            + " —— round5 L-01 的修复被破坏，窗口统计会被污染");
        }
    }
}
