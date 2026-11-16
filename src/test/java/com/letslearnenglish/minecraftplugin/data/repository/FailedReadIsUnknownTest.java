package com.letslearnenglish.minecraftplugin.data.repository;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * slice-12-logic-L-01（round1 遗留 MEDIUM，round10 裁决实施 A6-1）：
 * 「读失败」必须与「确实没有解锁任何成就」严格区分。
 *
 * <p><b>原缺陷链</b>：{@code findUnlocked} 读失败返回**空 Map**（与「新玩家」同形）
 * → {@code loadPlayerAchievements} 无条件落缓存 → {@code tryUnlock} 的 {@code contains}
 * 恒为 false → **已解锁的成就会被重新解锁、重新弹通知**，动态计数也随之虚增。
 *
 * <p><b>修法</b>：读失败返回 {@code null}（「不知道」），调用方据此不落缓存、不放行解锁。
 * 真值仍在库里，下次会话结算重新触发 {@code tryUnlock} 即可补上，**不丢解锁**。
 *
 * <p><b>变异检验</b>：把 {@code findUnlocked} 的 {@code return null} 改回 {@code return result}
 * （即恢复返回空 Map），{@link FailedReadIsUnknown#failedReadReturnsNull} 立刻变红。
 */
class FailedReadIsUnknownTest {

    @TempDir
    Path tempDir;

    private File dbFile;
    private LetsLearnEnglish plugin;
    private DatabaseManager db;
    private AchievementRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FailedReadIsUnknownTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "ach.db");
        cfg.set("database.mysql.table-prefix", "lle_");
        when(cm.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        dbFile = new File(tempDir.toFile(), "ach.db");
        repo = new AchievementRepository(db, Logger.getLogger("FailedReadIsUnknownTest"));
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.shutdown();
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) { st.executeUpdate(sql); }
    }

    @Test
    @DisplayName("读失败返回 null，不是空 Map")
    void failedReadReturnsNull() throws Exception {
        UUID id = UUID.randomUUID();
        // 制造真实 SQL 失败：把 achievements 表改名（这次不是只读列，是整表没了，
        // 因为 findUnlocked 只做 SELECT，不需要后续写同一列）。
        exec("ALTER TABLE lle_achievements RENAME TO lle_achievements_hidden");
        try {
            Map<String, Long> result = repo.findUnlocked(id);
            assertNull(result,
                    "读失败返回了空 Map（size=" + (result == null ? "-" : result.size()) + "）"
                            + " —— 这与「确实没有解锁任何成就」同形，会让已解锁的成就"
                            + "被重新解锁并重复弹通知");

            exec("ALTER TABLE lle_achievements_hidden RENAME TO lle_achievements");
        } finally {
            try { exec("ALTER TABLE lle_achievements_hidden RENAME TO lle_achievements"); }
            catch (Exception ignored) { }
        }
    }

    @Test
    @DisplayName("读成功返回非 null（防「一律返回 null」的过度修复）")
    void healthyReadReturnsMap() throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT OR REPLACE INTO lle_achievements (uuid, achievement_id, unlocked_at)"
                + " VALUES ('" + id + "', 'ACH_FIRST', 1000)");

        Map<String, Long> result = repo.findUnlocked(id);
        assertNotNull(result, "读成功却返回 null —— 修复把正常路径也堵死了");
        assertEquals(1, result.size(), "应读到 1 条已解锁记录");
        assertTrue(result.containsKey("ACH_FIRST"));
        assertEquals(1000L, result.get("ACH_FIRST"));
    }

    @Test
    @DisplayName("玩家无记录时返回**空 Map**（不是 null）—— 新玩家 vs 读失败必须可分")
    void noRowsReturnsEmptyMap() throws Exception {
        Map<String, Long> result = repo.findUnlocked(UUID.randomUUID());
        assertNotNull(result, "无记录被返回成 null —— 「新玩家」与「读失败」又混起来了");
        assertEquals(0, result.size(), "无记录应是空 Map");
    }
}
