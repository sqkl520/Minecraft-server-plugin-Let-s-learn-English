package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;

/**
 * P-SQ-PAGE：广场分页（{@link PlayerDataStore#getActiveSquareEntries(int, int)} +
 * {@link PlayerDataStore#countActiveSquareEntries()}）的真 SQLite 验收。
 *
 * <p><b>为什么必须上真库</b>：这次改动的全部风险都在 SQL 语义里，mock 查不出来 ——
 * ① 计数与取行是<b>两条独立 SQL</b>，WHERE 一旦漂移，尾页会空白/永远翻不到，
 * 这是"能跑、能翻、但少人"的静默错误；② {@code LIMIT ? OFFSET ?} 的绑定顺序、
 * 双后端兼容性只有真驱动能证；③ 排序确定性只能靠"同一分值大量并列"的真数据来压
 * —— 只按 {@code total_score DESC} 时 SQLite 恰好按 rowid 返回，看着是稳定，
 * 换成 MySQL 或换查询计划就会漂，这里用"并列分 + 全页无交集无遗漏"把 tie-break
 * 钉成可测事实。
 *
 * <p><b>纯 JVM 可跑</b>：沿用 {@code SqliteMaintenanceTest} 的骨架 ——
 * {@code mock(LetsLearnEnglish)} + 真 {@link YamlConfiguration} + {@link TempDir}
 * 就能把 {@link DatabaseManager} 的 SQLite 分支跑起来，不需要 MockBukkit。
 * 驱动来自 test-scope 的 {@code org.xerial:sqlite-jdbc}。
 */
class ActiveSquarePagingTest {

    /** 与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";
    /** 与 {@code EnglishSquareGUI.PLAYERS_PER_PAGE} 同值（GUI 常量是 private，此处表达同一事实）。 */
    private static final int PAGE = 28;

    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;

    @BeforeAll
    static void createMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        // 真 Logger：失败路径的 log(WARNING, ..., e) 需要真实例。
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ActiveSquarePagingTest"));
    }

    // ------------------------------------------------------------------
    // A. 真 SQLite：计数 / 分页 / 排序
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("真 SQLite（临时文件）")
    class RealSqlite {

        private DatabaseManager databaseManager;
        private PlayerDataStore store;

        @BeforeEach
        void initRealDatabase(@TempDir Path tempDir) throws Exception {
            FileConfiguration config = new YamlConfiguration();
            config.set("database.type", "sqlite");
            config.set("database.sqlite.filename", "square-paging-test.db");
            when(configManager.getMainConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

            databaseManager = new DatabaseManager(plugin, configManager);
            databaseManager.initialize();
            assertTrue(databaseManager.isSQLite(), "前置条件：真库必须走 SQLite 分支");
            store = new PlayerDataStore(plugin, databaseManager);
        }

        @AfterEach
        void closeDatabase() {
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
        }

        /**
         * 核心不变式：计数 == "全量取行"的行数 == 逐页拼起来的行数。
         *
         * <p>三条口径互为对照：单看计数正确、单看某页正确，都可能一起错（比如两条
         * WHERE 同时少了隐身门）。只有三者相等，才能证明"页数"与"内容"同源。
         */
        @Test
        @DisplayName("计数 == 全量行数 == 逐页拼装行数，且隐身/未答题玩家不计入")
        void countMatchesFullScanAndPageConcatenation() throws SQLException {
            // 60 个"有答题记录且公开"的玩家 + 5 个隐身（也有答题记录）+ 5 个零进度
            List<UUID> visible = seedPlayers(60, 100L, 0, 5);
            seedPlayers(5, 100L, 0, 1, /* hidden */ true);
            seedVisibleWithoutProgress(5);

            int count = store.countActiveSquareEntries();
            assertEquals(60, count,
                    "计数必须只包含「公开 + 答过题」的玩家：隐身门或聚合 >0 门任一漏写，"
                            + "页数就会与实际内容不符（尾页空白 / 最后几页翻不到）");

            List<LeaderboardEntry> full = store.getActiveSquareEntries(Integer.MAX_VALUE, 0);
            assertEquals(count, full.size(),
                    "计数与全量取行必须同口径 —— 这两条是独立 SQL，WHERE 漂移时本断言先红");

            List<LeaderboardEntry> concatenated = new ArrayList<>();
            int totalPages = (count + PAGE - 1) / PAGE;
            for (int p = 1; p <= totalPages; p++) {
                List<LeaderboardEntry> pageRows = store.getActiveSquareEntries(PAGE, (p - 1) * PAGE);
                assertTrue(pageRows.size() <= PAGE, "单页不得超过 LIMIT " + PAGE + "（实际 " + pageRows.size() + "）");
                concatenated.addAll(pageRows);
            }
            assertEquals(count, concatenated.size(), "逐页拼装的行数必须等于总数（不多不少）");
            assertEquals(new HashSet<>(visible), new HashSet<>(idsOf(concatenated)),
                    "逐页拼装必须恰好覆盖全部可见玩家");
        }

        /**
         * 排序确定性：全部玩家<b>同分</b>时，页与页之间不得重复、不得遗漏。
         *
         * <p>没有 {@code d.uuid ASC} tie-break 时这条最容易在 MySQL 上翻车（同样的
         * 分数，两条 SQL 的执行计划不同就可能给出不同次序），表现为某玩家在第 1 页
         * 和第 2 页各出现一次、另一人消失。SQLite 上单次执行未必暴露，故本用例除了
         * "无交集"还直接钉住"页内按 uuid 升序" —— 把 tie-break 从偶然变成契约。
         */
        @Test
        @DisplayName("同分并列时页间无重复无遗漏，且页内按 uuid 升序（tie-break 契约）")
        void tiedScoresPageDeterministically() throws SQLException {
            seedPlayers(60, 500L, 0, 5); // 全员同分

            List<LeaderboardEntry> page1 = store.getActiveSquareEntries(PAGE, 0);
            List<LeaderboardEntry> page2 = store.getActiveSquareEntries(PAGE, PAGE);
            List<LeaderboardEntry> page3 = store.getActiveSquareEntries(PAGE, 2 * PAGE);

            assertEquals(PAGE, page1.size());
            assertEquals(PAGE, page2.size());
            assertEquals(4, page3.size(), "60 行的第 3 页应只剩 4 行");

            Set<UUID> seen = new HashSet<>(idsOf(page1));
            for (LeaderboardEntry e : page2) {
                assertTrue(seen.add(e.playerId),
                        "玩家 " + e.playerId + " 同时出现在第 1、2 页 —— 分页排序不是全序");
            }
            for (LeaderboardEntry e : page3) {
                assertTrue(seen.add(e.playerId),
                        "玩家 " + e.playerId + " 在尾页重复出现 —— 分页排序不是全序");
            }
            assertEquals(60, seen.size(), "三页必须恰好覆盖 60 个不同玩家");

            for (List<LeaderboardEntry> pageRows : List.of(page1, page2, page3)) {
                assertAscendingUuid(pageRows);
            }
        }

        /** 主排序键仍是分数降序（tie-break 只在前者相等时生效，不能反客为主）。 */
        @Test
        @DisplayName("分数降序仍是主序：跨页分数单调不增")
        void primaryOrderIsScoreDescending() throws SQLException {
            // 3 人一组同分，共 40 人 → 分数 400,400,400,397,397,397,...
            for (int i = 0; i < 40; i++) {
                // seedOne 的签名是 (id, name, score, showInSquare, words)：此处置 1 = 公开
                seedOne(UUID.randomUUID(), "p" + i, 400L - (i / 3) * 3L, 1, 1);
            }

            List<LeaderboardEntry> all = new ArrayList<>();
            for (int p = 0; p < 2; p++) {
                all.addAll(store.getActiveSquareEntries(PAGE, p * PAGE));
            }
            assertEquals(40, all.size());
            for (int i = 1; i < all.size(); i++) {
                assertTrue(all.get(i - 1).score >= all.get(i).score,
                        "第 " + i + " 行分数高于前一行 —— ORDER BY total_score DESC 被破坏");
            }
        }

        /**
         * 越界 OFFSET：返回空列表而不是抛异常。
         *
         * <p>GUI 的页码已 clamp 到 [1, totalPages]，正常路径不会越界；但 clamp 依赖
         * 计数，而计数与取行是两条 SQL —— 若期间有玩家隐身/清库，OFFSET 可能落在
         * 末尾之外。此时必须是"空页"（配合 totalPages 显示"暂无玩家"），不能抛。
         */
        @Test
        @DisplayName("OFFSET 超出总数返回空页；负数 limit/offset 不抛（按 0 处理）")
        void outOfRangeOffsetYieldsEmptyPage() throws SQLException {
            seedPlayers(10, 100L, 0, 3);

            assertTrue(store.getActiveSquareEntries(PAGE, 10 * PAGE).isEmpty(),
                    "OFFSET 越过末尾必须返回空列表（越界页渲染为空，不得抛异常）");
            assertTrue(store.getActiveSquareEntries(0, 0).isEmpty(), "limit=0 应返回空列表");
            assertTrue(store.getActiveSquareEntries(-5, 0).isEmpty(), "负数 limit 应按 0 处理");
            assertEquals(10, store.getActiveSquareEntries(PAGE, -5).size(),
                    "负数 offset 应按 0 处理（等价第一页），不得抛");
        }

        /** 空库：计数 0、取行空 —— GUI 侧据此渲染 "1/1 + 暂无玩家"。 */
        @Test
        @DisplayName("空库：计数为 0，取行为空列表")
        void emptyDatabaseYieldsZeroAndEmpty() {
            assertEquals(0, store.countActiveSquareEntries());
            assertTrue(store.getActiveSquareEntries(PAGE, 0).isEmpty());
        }

        /** 隐身玩家的单行也不得出现在任何页（隐身门是隐私语义，不是性能优化）。 */
        @Test
        @DisplayName("隐身玩家不出现在任何页，即使他在全库分数最高")
        void hiddenPlayerNeverAppears() throws SQLException {
            UUID hiddenId = UUID.randomUUID();
            // 隐身玩家必须有答题记录（聚合 > 0）—— 否则它会被"没答过题"门挡掉，
            // 本用例就变成在测另一条门，隐身门写坏了也照样绿。
            seedOne(hiddenId, "hidden", 999_999L, 0, 1);
            seedPlayers(3, 10L, 0, 1);

            List<LeaderboardEntry> all = store.getActiveSquareEntries(PAGE, 0);
            assertEquals(3, all.size());
            assertFalse(idsOf(all).contains(hiddenId), "show_in_square=0 的玩家泄漏到了广场列表");
            assertEquals(3, store.countActiveSquareEntries());
        }

        // -------------------------------------------------------------- 造数

        /** 公开玩家播种（{@code scoreStep=0} 即全员同分，用来压 tie-break）。 */
        private List<UUID> seedPlayers(int n, long score, long scoreStep, int wordsPerPlayer)
                throws SQLException {
            return seedPlayers(n, score, scoreStep, wordsPerPlayer, false);
        }

        /**
         * 播种 {@code n} 个玩家：每人 {@code wordsPerPlayer} 条 word_progress
         *（correct=2/incorrect=1，保证聚合 > 0）。
         *
         * @param scoreStep 每人分数递减步长；0 = 全员同分（压 tie-break）
         * @param hidden    true = 写 {@code show_in_square=0}（隐身，应被过滤）
         */
        private List<UUID> seedPlayers(int n, long score, long scoreStep, int wordsPerPlayer,
                                       boolean hidden) throws SQLException {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                seedOne(id, "p" + i, score - i * scoreStep, hidden ? 0 : 1, wordsPerPlayer);
            }
            return ids;
        }

        /** 只有 player_data 行、零 word_progress：应被 "答过题" 门挡在广场外。 */
        private void seedVisibleWithoutProgress(int n) throws SQLException {
            for (int i = 0; i < n; i++) {
                seedOne(UUID.randomUUID(), "lazy" + i, 50L, 1, 0);
            }
        }

        private void seedOne(UUID id, String name, long score, int showInSquare, int words)
                throws SQLException {
            synchronized (databaseManager.getConnectionLock()) {
                Connection conn = databaseManager.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + PREFIX + "player_data"
                                + " (uuid, player_name, total_score, total_sessions) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, id.toString());
                    ps.setString(2, name);
                    ps.setLong(3, score);
                    ps.setInt(4, 1);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + PREFIX + "player_profiles (uuid, show_in_square) VALUES (?, ?)")) {
                    ps.setString(1, id.toString());
                    ps.setInt(2, showInSquare);
                    ps.executeUpdate();
                }
                if (words > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO " + PREFIX + "word_progress"
                                    + " (uuid, word, correct_count, incorrect_count) VALUES (?, ?, ?, ?)")) {
                        for (int w = 0; w < words; w++) {
                            ps.setString(1, id.toString());
                            ps.setString(2, "word" + w);
                            ps.setInt(3, 2);
                            ps.setInt(4, 1);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
            }
        }

        private void assertAscendingUuid(List<LeaderboardEntry> rows) {
            for (int i = 1; i < rows.size(); i++) {
                String prev = rows.get(i - 1).playerId.toString();
                String cur = rows.get(i).playerId.toString();
                assertTrue(prev.compareTo(cur) < 0,
                        "同分页内 uuid 未升序：" + prev + " 在 " + cur + " 之前 —— "
                                + "缺少 d.uuid ASC tie-break 时同分玩家可能跨页重复/消失");
            }
        }
    }

    // ------------------------------------------------------------------
    // B. 失败语义（mock 连接）
    // ------------------------------------------------------------------

    /**
     * 失败必须与"确实没人"同形：取行返回空列表、计数返回 0，且异常不外泄。
     *
     * <p>这是既有契约（"广场最坏显示暂无玩家，不伪造任何行"）在新方法上的延续。
     * 用 {@code getConnection()} 抛 RuntimeException 模拟重连失败 —— 正是
     * {@code catch (Exception)} 而非 {@code catch (SQLException)} 要接住的那一类。
     */
    @Nested
    @DisplayName("失败语义（mock 连接）")
    class FailureSemantics {

        @Test
        @DisplayName("取连接抛异常：计数 0、取行空列表，不向调用方抛")
        void connectionFailureDegradesToEmpty() {
            // 复用类级 plugin mock：LetsLearnEnglish 继承链很深，每次 mock 约 12 s
            // （见 SqliteMaintenanceTest 同款说明），本用例只需要 getLogger()。
            DatabaseManager failing = mock(DatabaseManager.class);
            when(failing.getTablePrefix()).thenReturn(PREFIX);
            when(failing.getConnectionLock()).thenReturn(new Object());
            when(failing.getConnection()).thenThrow(new RuntimeException("reconnect failed"));

            PlayerDataStore failingStore = new PlayerDataStore(plugin, failing);

            assertEquals(0, failingStore.countActiveSquareEntries(),
                    "计数失败必须返回 0（GUI 会 clamp 成 1 页），不得抛");
            assertTrue(failingStore.getActiveSquareEntries(PAGE, 0).isEmpty(),
                    "取行失败必须返回空列表 —— 广场显示「暂无玩家」，绝不伪造行");
        }
    }

    private static List<UUID> idsOf(List<LeaderboardEntry> entries) {
        List<UUID> ids = new ArrayList<>(entries.size());
        for (LeaderboardEntry e : entries) {
            ids.add(e.playerId);
        }
        return ids;
    }
}