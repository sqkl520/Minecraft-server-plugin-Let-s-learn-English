package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * slice-09-F-09-03/04/05（round1 遗留 MEDIUM，round10 裁决实施）：
 * 名次必须有<b>单一真相源</b>，且与广场榜同一套口径。
 *
 * <p><b>原缺陷</b>：两个名次来源口径不同 ——
 * <ul>
 *   <li>内存榜 {@code Leaderboard.getPlayerRank}：启动快照 + 增量，WHERE 里**只有隐身门**；</li>
 *   <li>广场榜 {@code getActiveSquareEntries}：实时 SQL，WHERE 里还有**答题门**
 *       （{@code EXISTS word_progress ... correct+incorrect > 0}）。</li>
 * </ul>
 * 于是「刚进服、还没答过题」的玩家在 {@code %lle_rank%} 里有名次、在广场上却不在。
 *
 * <p><b>本测试钉住三件事</b>：
 * <ol>
 *   <li>没答过题的玩家**不**上榜（答题门生效）</li>
 *   <li>隐身玩家**不**上榜（隐身门生效）</li>
 *   <li>名次与「按 score DESC, uuid ASC 排序后的下标 + 1」**数学等价**（含同分 tie-break）</li>
 * </ol>
 *
 * <p><b>变异检验</b>：把 {@code getAuthoritativeRank} 的 {@code EXISTS} 答题门删掉，
 * {@link AnswerGate#neverAnsweredIsNotRanked} 立刻变红。
 */
class AuthoritativeRankTest {

    @TempDir
    Path tempDir;

    private File dbFile;
    private LetsLearnEnglish plugin;
    private DatabaseManager db;
    private PlayerDataStore store;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AuthoritativeRankTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "rank.db");
        cfg.set("database.mysql.table-prefix", "lle_");
        when(cm.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        lenient().when(plugin.getDatabaseManager()).thenReturn(db);
        dbFile = new File(tempDir.toFile(), "rank.db");

        store = new PlayerDataStore(plugin, db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.shutdown();
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) { st.executeUpdate(sql); }
    }

    /**
     * 建一个玩家：分数 + 是否答过题 + 是否隐身。
     *
     * <p>R-48：答题门已从 {@code EXISTS(word_progress)} 改为读去规范化列
     * {@code player_data.answered_count}，所以播种必须同时维护那一列 —— 否则本类会
     * 退化成"全员未答题 → 谁都排不上名"，且所有断言都会以"符合预期"的方式变绿
     * （{@code -1} 恰好是 {@code neverAnsweredIsNotRanked} 期望的值）。
     * {@code answered_count} 与 {@code word_progress} 一起写，正是为了不让这种假绿发生。
     */
    private void seed(String uuid, long score, boolean answered, boolean hidden) throws Exception {
        exec("INSERT OR REPLACE INTO lle_player_data (uuid, player_name, total_sessions,"
                + " total_score, conversation_count, wrong_book_cleared, answered_count)"
                + " VALUES ('" + uuid + "', 'p', 0, " + score + ", 0, 0, "
                + (answered ? 1 : 0) + ")");
        if (hidden) {
            exec("INSERT OR REPLACE INTO lle_player_profiles (uuid, age, show_answers,"
                    + " show_results, show_in_square, sounds_enabled, reminders_enabled)"
                    + " VALUES ('" + uuid + "', 0, 0, 0, 0, 1, 1)");
        }
        if (answered) {
            exec("INSERT OR REPLACE INTO lle_word_progress (uuid, word, mastery, review_count,"
                    + " correct_count, incorrect_count, last_review_time)"
                    + " VALUES ('" + uuid + "', 'apple', 0.5, 1, 3, 0, 0)");
        }
    }

    @Nested
    @DisplayName("A. 两道门")
    class AnswerGate {

        @Test
        @DisplayName("没答过题的玩家不排名（答题门）")
        void neverAnsweredIsNotRanked() throws Exception {
            String high = "00000000-0000-0000-0000-000000000101";
            String noob = "00000000-0000-0000-0000-000000000102";
            seed(high, 1000, true, false);
            seed(noob, 5000, false, false);   // 分数更高，但从没答过题

            assertEquals(-1, store.getAuthoritativeRank(UUID.fromString(noob)),
                    "没答过题的玩家出现在榜单上 —— 答题门没生效，"
                            + "这正是「内存榜有名次、广场榜没有」的分叉根源");
            assertEquals(1, store.getAuthoritativeRank(UUID.fromString(high)),
                    "高分但从没答过题的玩家被排除了，剩下的 high 就应该是第 1 名"
                            + "（注：若答题门失效，noob 会占据第 1、high 变成第 2 —— 这条断言能同时抓到两件事）");
        }

        @Test
        @DisplayName("隐身玩家不排名（隐私门）")
        void hiddenIsNotRanked() throws Exception {
            String visible = "00000000-0000-0000-0000-000000000103";
            String hidden = "00000000-0000-0000-0000-000000000104";
            seed(visible, 100, true, false);
            seed(hidden, 900, true, true);

            assertEquals(-1, store.getAuthoritativeRank(UUID.fromString(hidden)),
                    "隐身玩家出现在榜单上 —— 隐私门没生效");
            assertEquals(1, store.getAuthoritativeRank(UUID.fromString(visible)),
                    "唯一可见的答过题玩家应该是第 1 名");
        }

        @Test
        @DisplayName("DB 里没有该玩家的行 → 未上榜")
        void unknownPlayerNotRanked() throws Exception {
            assertEquals(-1, store.getAuthoritativeRank(UUID.randomUUID()),
                    "不存在的玩家返回了名次");
            assertEquals(-1, store.getAuthoritativeRank(null), "null 应安全返回未上榜");
        }
    }

    @Nested
    @DisplayName("B. 名次算术：与「排序后下标+1」数学等价")
    class RankArithmetic {

        @Test
        @DisplayName("按 score DESC 排名")
        void ranksByScoreDescending() throws Exception {
            String[] ids = {
                    "00000000-0000-0000-0000-000000000201", // 300 → 第 1
                    "00000000-0000-0000-0000-000000000202", // 200 → 第 2
                    "00000000-0000-0000-0000-000000000203", // 100 → 第 3
            };
            seed(ids[0], 300, true, false);
            seed(ids[1], 200, true, false);
            seed(ids[2], 100, true, false);

            assertEquals(1, store.getAuthoritativeRank(UUID.fromString(ids[0])));
            assertEquals(2, store.getAuthoritativeRank(UUID.fromString(ids[1])));
            assertEquals(3, store.getAuthoritativeRank(UUID.fromString(ids[2])));
        }

        @Test
        @DisplayName("同分时按 uuid ASC 做 tie-break（与广场 ORDER BY 一致）")
        void tiesBreakByUuidAscending() throws Exception {
            // 三个同分玩家，uuid 递增。按 (score DESC, uuid ASC) 排序：
            // ...0205(uuid 最小) → 第1，...0206 → 第2，...0207 → 第3
            String a = "00000000-0000-0000-0000-000000000205";
            String b = "00000000-0000-0000-0000-000000000206";
            String c = "00000000-0000-0000-0000-000000000207";
            seed(a, 500, true, false);
            seed(b, 500, true, false);
            seed(c, 500, true, false);

            assertEquals(1, store.getAuthoritativeRank(UUID.fromString(a)), "uuid 最小者应第 1");
            assertEquals(2, store.getAuthoritativeRank(UUID.fromString(b)), "uuid 居中者应第 2");
            assertEquals(3, store.getAuthoritativeRank(UUID.fromString(c)), "uuid 最大者应第 3");
        }

        @Test
        @DisplayName("同分且 uuid 最小但分数低于他人时，名次仍然正确")
        void mixedScoresAndTies() throws Exception {
            // 期望顺序：(score DESC, uuid ASC)
            //   900/...0301 → 1
            //   800/...0302 → 2
            //   800/...0303 → 3
            //   700/...0304 → 4
            String p1 = "00000000-0000-0000-0000-000000000301";
            String p2 = "00000000-0000-0000-0000-000000000302";
            String p3 = "00000000-0000-0000-0000-000000000303";
            String p4 = "00000000-0000-0000-0000-000000000304";
            seed(p1, 900, true, false);
            seed(p2, 800, true, false);
            seed(p3, 800, true, false);
            seed(p4, 700, true, false);

            assertEquals(1, store.getAuthoritativeRank(UUID.fromString(p1)));
            assertEquals(2, store.getAuthoritativeRank(UUID.fromString(p2)));
            assertEquals(3, store.getAuthoritativeRank(UUID.fromString(p3)));
            assertEquals(4, store.getAuthoritativeRank(UUID.fromString(p4)));
        }
    }
}
