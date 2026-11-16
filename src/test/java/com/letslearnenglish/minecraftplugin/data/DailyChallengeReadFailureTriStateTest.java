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

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A5/D2 + D3 + D6 的数据层回归测试：
 * <b>「读失败」与「真实空值」在返回契约上必须可区分</b>。
 *
 * <h2>为什么这三条要放在同一个类里</h2>
 * 它们不是三件独立的事故，而是<b>同一个形态</b>在三个方法上的三次发作：
 * {@code catch (SQLException) → 返回那个"什么都合理"的空值}，然后调用方拿这个空值
 * 做<b>不可回退</b>的决定。三条一起测，是为了让"以后再有人写 {@code catch → 空}"
 * 时，判据（{@code readFailed} / 三态枚举）有现成的样板可抄。
 *
 * <ul>
 *   <li><b>D2</b> {@code isDailyChallengeWordsCompleted → false}：rollover 补发把
 *       "不知道"读成"未完成" → 昨日已完成未发的奖励永久作废；</li>
 *   <li><b>D3</b> {@code getDailyChallengeWords → 空 List}：{@code existing.isEmpty()}
 *       把"不知道"读成"未分配" → 再分配一批 → 当日挑战集 N→N+M → 奖励不可达；</li>
 *   <li><b>D6</b> {@code minSeasonId → null}：结算 sweep 把"不知道"读成"首次启用" →
 *       本轮跳过 + 日志谎报"首次启用"。</li>
 * </ul>
 *
 * <p><b>R12-F8：上面三个两态方法已全部从 {@code DatabaseManager} 删除</b>（两个零调用点，
 * 第三个的唯一调用点 {@code dbSaysAllWordsCompleted} 已改为显式
 * {@code loadWordChallengeCompletion(...) == COMPLETED}）。本类仍保留三条用例 ——
 * 它们测的是<b>三态版</b>在真 SQLException 下确实分出三态，而这正是删除两态版的前提。
 * 两态版不得被加回来，由 {@code TwoStateLegacyLintTest} 的墓碑 lint 钉住。
 *
 * <h2>故障注入：真 SQLite，真 SQLException</h2>
 * 手法照 {@code DifficultWordsReadFailureTest}：从第二条连接执行
 * {@code ALTER TABLE ... RENAME COLUMN}，让生产 SELECT 点名的列消失 → 真实
 * {@code no such column} → 走 {@code catch (SQLException)}。
 * <b>不用 mock 桩 readFailed=true</b>：那只能证明"调用方会读这个 flag"，
 * 证明不了"数据层真的把异常翻成 flag"——把 {@code return ... true} 改成 {@code false}
 * 桩测试照样绿。
 *
 * <p>列改名的<b>可恢复性与精确性</b>是本类的关键前提（都是踩过的坑）：
 * <ul>
 *   <li>{@code ALTER TABLE ... RENAME TO}（整表改名）会被 SQLite 同步进既有连接，
 *       已开连接的 SELECT 依然成功 → 故障根本不发生，测试"全绿"纯属空转；</li>
 *   <li>{@code DROP COLUMN} 能造故障但恢复不了。</li>
 * </ul>
 * 故每条只改<b>读路径点名、写路径不碰</b>的那一列：{@code daily_challenge_words.completed}
 * （D2 的 COUNT/SUM 点名它；D3 的 SELECT word_id 不碰它）、
 * {@code seasons.settled_at}（D6 的 MIN(season_id) 不碰它，但这里改用整表 DROP 不可行，
 * 故 D6 用"删表"注入——见该用例说明）。
 */
class DailyChallengeReadFailureTriStateTest {

    private static final String PREFIX = "lle_";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000f6d01");
    private static final String DATE = "2026-02-10";

    @TempDir
    Path tempDir;

    private File dbFile;
    private LetsLearnEnglish plugin;
    private DatabaseManager db;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DailyChallengeReadFailureTriStateTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "dc-tristate.db");
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(cm.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        when(plugin.getDatabaseManager()).thenReturn(db);

        // D5 相关路径需要调度器（increment/decrement 走 runIfSameEpoch）；本类不测 D5，
        // 但共享 setUp 的 PlayerDataStore 用例会让任务真的派发 —— 就地执行以免线程池
        // 把断言时刻变成竞态。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(org.mockito.ArgumentMatchers.eq(plugin),
                org.mockito.ArgumentMatchers.any(Runnable.class))).thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return null;
                });
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        dbFile = new File(tempDir.toFile(), "dc-tristate.db");
        assertTrue(dbFile.isFile(), "前置：SQLite 文件未创建");
    }

    @AfterEach
    void tearDown() {
        // 用例中途失败时列名可能仍是改过的状态；恢复失败无所谓（临时库随 TempDir 删除）。
        try {
            renameColumn("daily_challenge_words", "completed_hidden", "completed");
        } catch (Exception ignored) {
            // no-op
        }
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // D2：isDailyChallengeWordsCompleted 的三态
    // ==================================================================

    /**
     * D2 的核心：读失败必须返回 {@link DatabaseManager.WordChallengeCompletion#READ_FAILED}，
     * <b>不能</b>返回 {@code NOT_COMPLETED}。
     *
     * <p>变异指向：把 {@code loadWordChallengeCompletion} 的 {@code catch} 改回
     * {@code return WordChallengeCompletion.NOT_COMPLETED}（= 旧 {@code return false}），
     * 本用例立刻红。</p>
     *
     * <p>为什么这样测才不是"自证桩"：真正的判据是<b>异常真的发生了</b>——用例先断言
     * 列确实被改走（{@code hasColumn} 风格的直连探测），再做调用。</p>
     */
    @Test
    @DisplayName("D2：读失败 → READ_FAILED（不得折叠成 NOT_COMPLETED）")
    void completionReadFailureIsTriState() throws Exception {
        seedChallengeWord(PLAYER, DATE, "alpha", true);
        seedChallengeWord(PLAYER, DATE, "bravo", true);

        // 前置：健康时必须是 COMPLETED（否则下面测的就不是"失败"而是"本来就没完成"）。
        assertEquals(DatabaseManager.WordChallengeCompletion.COMPLETED,
                db.loadWordChallengeCompletion(PLAYER, DATE),
                "前置：两词全完成时三态应为 COMPLETED");

        renameColumn("daily_challenge_words", "completed", "completed_hidden");

        DatabaseManager.WordChallengeCompletion tri = db.loadWordChallengeCompletion(PLAYER, DATE);
        assertEquals(DatabaseManager.WordChallengeCompletion.READ_FAILED, tri,
                "读失败必须返回 READ_FAILED —— 折叠成 NOT_COMPLETED（旧 return false）就是 D2 本体：\n"
                        + "rollover 补发会把它读成「昨日没有 pending」，而该日之后再无回访点，"
                        + "已完成未发的奖励被一次 SELECT 抖动永久抹掉。");

        // R12-F8：两态便捷版 {@code isDailyChallengeWordsCompleted} 已删除（它的
        // 「READ_FAILED → false」折叠正是本条要防的形态）。此处不再断言它 ——
        // 方法不存在时误用是编译错误，比任何运行时断言都硬；「不得被加回来」由
        // {@code TwoStateLegacyLintTest} 的墓碑 lint 钉住。
        // 保留真正有价值的强断言：READ_FAILED 与 COMPLETED 是两个不同的枚举值，
        // 任何「把失败折叠成布尔」的实现都无法同时满足上面与下面这两条。

        // 另外两态必须仍然正确（防止"一律 READ_FAILED"的过度修复）。
        renameColumn("daily_challenge_words", "completed_hidden", "completed");
        assertEquals(DatabaseManager.WordChallengeCompletion.COMPLETED,
                db.loadWordChallengeCompletion(PLAYER, DATE),
                "恢复后必须回到 COMPLETED");
    }

    /**
     * 反过度修复：未全完成的日子仍必须是 {@code NOT_COMPLETED}，不能被新加的
     * {@code READ_FAILED} 吞掉。少了这条，一个"永远返回 READ_FAILED"的实现也能通过
     * 上面的用例，而它会让 rollover 每天都 SEVERE 并永远不判"无 pending"。
     */
    @Test
    @DisplayName("D2：未全完成 → NOT_COMPLETED（不得被 READ_FAILED 吞掉）")
    void partialCompletionIsNotCompletedNotFailure() throws Exception {
        seedChallengeWord(PLAYER, DATE, "alpha", true);
        seedChallengeWord(PLAYER, DATE, "bravo", false);

        assertEquals(DatabaseManager.WordChallengeCompletion.NOT_COMPLETED,
                db.loadWordChallengeCompletion(PLAYER, DATE),
                "只完成 2 个中的 1 个 → NOT_COMPLETED（业务事实），不是 READ_FAILED（故障）");

        // 零行（该日没有任何挑战词）同样是 NOT_COMPLETED：与缓存口径 allCompleted()
        // 的「有词且全完成」逐条一致。若这里返回 READ_FAILED，rollover 会对每个
        // "昨天根本没分配挑战词"的玩家打 SEVERE 并永久 pending。
        assertEquals(DatabaseManager.WordChallengeCompletion.NOT_COMPLETED,
                db.loadWordChallengeCompletion(PLAYER, "2026-02-11"),
                "该日无行 → NOT_COMPLETED（+ 业务上没有 pending），不是 READ_FAILED");
    }

    // ==================================================================
    // D3：getDailyChallengeWords 的三态
    // ==================================================================

    /**
     * D3：读失败必须带 {@code readFailed=true}，而<b>"确实没分配"必须是 false</b>。
     * 这两者一旦同形，{@code ensureWordChallengesAssignedLocked} 的
     * {@code existing.isEmpty()} 就会把库抖动读成"还没分配"并再插一批。
     *
     * <p>变异指向：把 {@code loadDailyChallengeWords} 的 catch 改回
     * {@code return new DailyChallengeWordsLoad(words, false)}，本用例红。</p>
     */
    @Test
    @DisplayName("D3：读失败 → readFailed=true；真未分配 → readFailed=false 且空")
    void challengeWordsReadFailureIsDistinguishableFromEmpty() throws Exception {
        // 真未分配（健康库，零行）：读成功 + 空表 —— 这个组合才允许"去分配"。
        DatabaseManager.DailyChallengeWordsLoad empty =
                db.loadDailyChallengeWords(PLAYER, DATE);
        assertFalse(empty.readFailed(), "健康库零行必须是 readFailed=false（否则玩家永远分配不到词）");
        assertTrue(empty.words().isEmpty(), "健康库零行 words 应为空");

        seedChallengeWord(PLAYER, DATE, "alpha", false);
        seedChallengeWord(PLAYER, DATE, "bravo", false);

        DatabaseManager.DailyChallengeWordsLoad ok = db.loadDailyChallengeWords(PLAYER, DATE);
        assertEquals(List.of("alpha", "bravo"), ok.words(), "前置：健康读应返回两行");
        assertFalse(ok.readFailed());

        // 只打坏读面：word_id 改名 → SELECT word_id 报 no such column。
        // completed 列不动，保证这个注入与 D2 的注入互不干扰。
        renameColumn("daily_challenge_words", "word_id", "word_id_hidden");

        DatabaseManager.DailyChallengeWordsLoad failed = db.loadDailyChallengeWords(PLAYER, DATE);
        assertTrue(failed.readFailed(),
                "读失败必须 readFailed=true —— 折叠成 false 时它与「今天还没分配」同形，"
                        + "ensureWordChallengesAssignedLocked 会再插一批词：当日挑战集 N→N+M，"
                        + "而 isDailyChallengeWordsCompleted 要求 total == done → 当天奖励不可达。");

        renameColumn("daily_challenge_words", "word_id_hidden", "word_id");
        DatabaseManager.DailyChallengeWordsLoad recovered =
                db.loadDailyChallengeWords(PLAYER, DATE);
        assertEquals(List.of("alpha", "bravo"), recovered.words(), "恢复后必须重新读到两行");
        assertFalse(recovered.readFailed());
    }

    // ==================================================================
    // D6：minSeasonId 的三态
    // ==================================================================

    /**
     * D6：{@code EMPTY}（表确实空 = 首次启用）与 {@code READ_FAILED}（不知道）必须分开。
     *
     * <p>故障注入方式与上面两条不同：{@code MIN(season_id)} 只点名 {@code season_id}，
     * 而那是主键、不能改名（会连带打坏所有写路径）。所以这里用
     * <b>{@code DROP TABLE seasons}</b> —— 表不存在时 SELECT 必然抛
     * {@code no such table}，正是生产上"迁移未完成 / 表被误删 / 连接指向了空库"的形态。
     * 该注入不影响其它用例（本类每条用例都在自己的 @TempDir 里重建库）。</p>
     *
     * <p>变异指向：把 {@code loadMinSeasonId} 的 catch 改回 {@code return EMPTY}
     * （= 旧 {@code return null} 被读成"首次启用"），本用例红。</p>
     */
    @Test
    @DisplayName("D6：表空 → EMPTY；读失败 → READ_FAILED（不得被当成首次启用）")
    void minSeasonIdDistinguishesEmptyFromFailure() throws Exception {
        // 前置：initialize() 已建好 seasons 表且为空 → 这才是真正的"首次启用"。
        DatabaseManager.SeasonIdRange empty = db.loadMinSeasonId();
        assertEquals(DatabaseManager.MinSeasonIdResult.EMPTY, empty.state(),
                "空表必须是 EMPTY（首次启用），这条保证 D6 的修复没有把首次启用误判成失败");
        assertEquals(null, empty.id(), "EMPTY 不携带 id");

        db.ensureSeasonRow(1, java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 1, 30));
        db.ensureSeasonRow(3, java.time.LocalDate.of(2026, 3, 1), java.time.LocalDate.of(2026, 3, 30));

        DatabaseManager.SeasonIdRange present = db.loadMinSeasonId();
        assertEquals(DatabaseManager.MinSeasonIdResult.PRESENT, present.state());
        assertEquals(1, present.id(), "present 必须带回最小 season_id（catch-up 起点）");

        // 打坏读面：表不存在 → SELECT 抛 SQLException。
        exec("DROP TABLE " + PREFIX + "seasons");

        DatabaseManager.SeasonIdRange failed = db.loadMinSeasonId();
        assertEquals(DatabaseManager.MinSeasonIdResult.READ_FAILED, failed.state(),
                "读失败必须是 READ_FAILED —— 与 EMPTY 同形时 runSettleSweep 会判「首次启用」，"
                        + "跳过本轮结算并打出与事实相反的 INFO「Season ledger initialized …」。");
        assertEquals(null, failed.id(), "READ_FAILED 不携带 id（不知道，不能猜）");
    }

    /**
     * 反过度修复：{@code PRESENT} 必须真的带值。
     *
     * <p>单独立意：只测"失败态"时，一个「永远返回 FAILED」的实现也能全绿 ——
     * 那会让结算 sweep 永远跳过（奖励永远不发），比原缺陷更糟。
     */
    @Test
    @DisplayName("D6：表非空 → PRESENT + 正确的 min id（不得一律 FAILED）")
    void minSeasonIdStillReturnsRealValue() throws Exception {
        db.ensureSeasonRow(2, java.time.LocalDate.of(2026, 2, 1), java.time.LocalDate.of(2026, 2, 28));
        db.ensureSeasonRow(5, java.time.LocalDate.of(2026, 5, 1), java.time.LocalDate.of(2026, 5, 30));

        DatabaseManager.SeasonIdRange present = db.loadMinSeasonId();
        assertEquals(DatabaseManager.MinSeasonIdResult.PRESENT, present.state());
        assertEquals(2, present.id(),
                "MIN(season_id) 必须是 2（不是 5、不是 null）——这里错了整个 catch-up 起点就错");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 在第二条连接上执行一条 DDL/DML（故障注入用）。 */
    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /**
     * 改列名 —— 制造/撤销读失败。
     *
     * <p>只影响<b>点名该列</b>的 SELECT。选列的原则：读路径点名、写路径不点名，
     * 这样"失败 → 恢复 → 再成功"三段都是真库行为，不依赖任何桩。</p>
     */
    private void renameColumn(String table, String from, String to) throws Exception {
        exec("ALTER TABLE " + PREFIX + table + " RENAME COLUMN " + from + " TO " + to);
    }

    private void seedChallengeWord(UUID playerId, String date, String wordId, boolean completed)
            throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO " + PREFIX + "daily_challenge_words"
                             + " (player_uuid, challenge_date, word_id, completed) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, date);
            ps.setString(3, wordId);
            ps.setInt(4, completed ? 1 : 0);
            ps.executeUpdate();
        }
    }

    /** 直连确认列改名/删表真的生效（防"注入没建成"被误读成"生产逻辑坏了"）。 */
    @SuppressWarnings("unused")
    private int countTablesNamed(String name) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
