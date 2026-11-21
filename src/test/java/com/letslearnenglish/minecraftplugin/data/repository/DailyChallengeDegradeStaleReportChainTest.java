package com.letslearnenglish.minecraftplugin.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;

/**
 * D9 的<b>端到端</b>链路：{@code degradeStale} 写入 → 库里的行状态 → 报表 Q8 的读数。
 *
 * <h2>为什么这个类必须存在（「断言被另一条路径满足」的假绿）</h2>
 * <p>D9 的修复是一条<b>四步</b>的链：schema 加列 → 迁移补列 → {@code degradeStale} 写
 * {@code abandoned = 1} → 报表分子 {@code AND COALESCE(abandoned, 0) = 0} 剔除。此前
 * 已有的两组用例各自只覆盖链路的一半，<b>中间那一跳没有人验证</b>：</p>
 * <ul>
 *   <li>{@code ReportRepositoryTest.d2AutoAbandonedChallengeShouldNotCountAsCompleted}
 *       用<b>独立连接手写 SQL</b> 造出 {@code abandoned=1} 的行再读报表 —— 它证明的是
 *       「报表会剔除 abandoned 行」，<b>完全没有调用 {@code degradeStale}</b>；</li>
 *   <li>{@code DailyChallengeClaimMarkTest.degradeStaleWritesAbandonedFlag} 真跑了
 *       {@code degradeStale} 并断言 {@code abandoned=1} —— 但它<b>不读报表</b>
 *       （该类没有 {@code ReportRepository} 字段），证明的是「列被写了」，
 *       不是「报表真的读到了这个列」。</li>
 * </ul>
 * <p>两段拼起来看似乎闭环，实际是两个互不相干的断言：把 {@code degradeStale} 的
 * {@code SET} 去掉 {@code abandoned = 1}，{@code ReportRepositoryTest} 里手造的行
 * 仍然是 {@code abandoned=1}，<b>那一类的 36 条用例一个都不会红</b> —— 这正是本次
 * 变异验证观测到的现象（36 例全绿；36 恰为 {@code ReportRepositoryTest} 的用例总数）。
 * 换句话说：断言「报表剔除 abandoned 行」这件事，被「测试自己手写的 abandoned 行」
 * 这条与生产写入路径无关的路径满足了。</p>
 *
 * <h2>本类补的就是那一跳</h2>
 * <p>唯一的数据来源是<b>生产方法自己写的行</b>（不手写 {@code abandoned} 列），
 * 然后用<b>生产报表方法</b>去读。任何一端断开，断言必红：</p>
 * <table border="1">
 *   <caption>变异 → 变红的用例</caption>
 *   <tr><th>变异</th><th>必须变红的用例</th><th>为何红</th></tr>
 *   <tr><td>M-A {@code degradeStale} 的 SET 去掉 {@code abandoned = 1}</td>
 *       <td>{@link #degradeStaleRowIsWrittenThenExcludedFromReport()}</td>
 *       <td>① 直读列断言 {@code abandoned == 1} 红；② 报表 {@code done} 从 0 变 1 红。
 *           两处独立，任一处单独都足以杀死该变异。</td></tr>
 *   <tr><td>M-B 报表分子去掉 {@code AND COALESCE(abandoned, 0) = 0}</td>
 *       <td>{@link #degradeStaleRowIsWrittenThenExcludedFromReport()}</td>
 *       <td>降级行（{@code abandoned=1}）被重新计入，{@code done} 从 0 变 1。</td></tr>
 *   <tr><td>M-C {@code COALESCE(abandoned, 0) = 0} 改成裸 {@code abandoned = 0}</td>
 *       <td>{@link #nullAbandonedLegacyRowStillCountsAsDone()}</td>
 *       <td>SQL 三值逻辑把 {@code abandoned IS NULL} 判成 UNKNOWN 而非 TRUE，
 *           迁移补列后的存量行凭空消失，{@code done} 少 1。</td></tr>
 *   <tr><td>M-D 报表分子改成 {@code progress > 0}</td>
 *       <td>{@link #genuineCompletionWithAbandonedZeroCountsAsDone()}</td>
 *       <td>{@code COMBO_5} 式二进制完成为 {@code progress=0}，会被误剔（少报）。</td></tr>
 * </table>
 *
 * <h2>为什么用真 SQLite 而不是 mock</h2>
 * <p>本类要证明的全部内容是「这条 SQL 写下的值，被另一条 SQL 按预期读出来」。
 * mock 掉 {@code DatabaseManager} 只能证明「调用了某个方法」，两侧的 SQL 语义一个字节
 * 都验证不到 —— 而这正是唯一的争议点。故一律开真 SQLite 文件库
 * （test-scope {@code org.xerial:sqlite-jdbc}），沿用
 * {@code DailyChallengeClaimMarkTest} / {@code ReportRepositoryTest} 的夹具手法。</p>
 *
 * <p>本类<b>只读</b>生产代码：一个字符都没改。发现缺陷请报告，不要在这里"顺手修"。</p>
 */
@DisplayName("D9 端到端 — degradeStale 写的 abandoned 必须被报表真的读到（真 SQLite）")
class DailyChallengeDegradeStaleReportChainTest {

    /** 与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    /** 固定日期：本类不测跨天，用常量避免与系统时钟耦合。 */
    private static final String DATE = "2026-02-10";

    /** 降级目标挑战（{@code DailyChallengeManager.degradeStaleChallenges} 只传这一个）。 */
    private static final String CHALLENGE = "AI_CHAT_5";

    private static final String DB_FILE = "degrade-stale-report-chain.db";

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000d5a001");

    @TempDir
    Path tempDir;

    /**
     * plugin / config 的 mock 在整类生命周期内复用：{@code LetsLearnEnglish} 继承链很深，
     * 单次 {@code mock()} 是十秒级开销（同 {@code DailyChallengeClaimMarkTest} 的记载）。
     */
    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;
    private static Logger logger;

    /** 本用例开过的库，{@code @AfterEach} 统一关，避免 SQLite 句柄泄漏到下一个用例。 */
    private final List<DatabaseManager> opened = new ArrayList<>();

    private DatabaseManager db;
    private DailyChallengeRepository challengeRepo;
    private ReportRepository reportRepo;

    @BeforeAll
    static void createMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        logger = Logger.getLogger("DailyChallengeDegradeStaleReportChainTest." + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        when(plugin.getLogger()).thenReturn(logger);
    }

    @BeforeEach
    void setUp() {
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", DB_FILE);
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(configManager.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：本类必须走真 SQLite 分支，否则测不到真实 SQL 语义");
        opened.add(db);
        when(plugin.getDatabaseManager()).thenReturn(db);

        challengeRepo = new DailyChallengeRepository(db, logger);
        reportRepo = new ReportRepository(db, logger);
    }

    @AfterEach
    void tearDown() {
        for (DatabaseManager each : opened) {
            try {
                each.shutdown();
            } catch (RuntimeException ignored) {
                // 关库失败不该掩盖断言结果
            }
        }
        opened.clear();
        db = null;
        challengeRepo = null;
        reportRepo = null;
    }

    // ==================================================================
    // 用例 1：端到端主链路 —— 生产写入 → 生产读取
    // ==================================================================

    /**
     * <b>端到端</b>：造一行「孩子没做完」的挑战 → 真调 {@code degradeStale} →
     * 直读四列 → 再用报表 Q8 读，断言这一行<b>不计入完成</b>但仍计入总数。
     *
     * <h3>为什么每一步都不能省</h3>
     * <ul>
     *   <li><b>不用手写 {@code abandoned}</b>：本用例的 {@code abandoned=1} 只能由
     *       {@code degradeStale} 产生。这正是不再被「测试自己造的数据」满足的关键 ——
     *       删掉 SET 里的 {@code abandoned = 1}，这一行就退回建表默认 0，
     *       下面的直读断言立刻红。</li>
     *   <li><b>断言返回值 1 而不是 0</b>：{@code 0} 在契约里有第二含义
     *       （写失败也返回 0）。若 {@code WHERE} 被改坏导致零行命中，
     *       后续的列断言会因为「行还是原样」而给出误导性的绿。</li>
     *   <li><b>直读四列</b>：{@code completed=1} 与 {@code claimed=1} 证明
     *       {@code WHERE ... AND completed = 0 AND claimed = 0} 的条件确实被满足后写回，
     *       {@code abandoned=1} 是本条链路的业务身份，{@code NOT NULL} 证明不是 NULL 蒙混。</li>
     *   <li><b>报表断言 {@code done == 0}</b>：这是「写入端写的东西真的被读取端看到」的
     *       唯一证据。只断言列值的话，报表分子写错（或压根没读这列）依然是绿的 ——
     *       那正是 D9 修好之前的状态。</li>
     * </ul>
     *
     * <p>{@code total == 1} 同时钉住「分母刻意不改」：降级行代表当天系统确实给过他这张
     * 挑战，把它从分母剔除会让完成率变成 1/1 而不是 0/1，等于把一次没做完粉饰成满分。</p>
     */
    @Test
    @DisplayName("端到端：degradeStale 写的行，报表 done=0 且 total=1（不手写 abandoned 列）")
    void degradeStaleRowIsWrittenThenExcludedFromReport() throws Exception {
        // 孩子零进度、未完成未领取 —— 这是 degradeStale 唯一会动的形态。
        // 刻意不写 abandoned 列：那一列只能由生产方法产生。
        seed(PLAYER, DATE, CHALLENGE, 0, false, false);
        assertEquals(Integer.valueOf(0), abandonedInDb(PLAYER, DATE, CHALLENGE),
                "前置：播种行的 abandoned 必须是建表默认 0 喵 —— 若这里不是 0，"
                        + "说明测试自己往列里写了值，本用例就退回「手写数据自证」的假绿形态");

        int affected = challengeRepo.degradeStale(PLAYER, DATE, CHALLENGE);

        assertEquals(1, affected,
                "未完成未领取的行必须被降级（受影响 1 行）喵 —— 返回 0 有两种含义"
                        + "（零行命中 / 写失败），无论哪种，下面的列断言都不该再往下走");

        // ---- 第二跳：库里的真值（独立连接直读，不信生产 findState）----
        assertEquals(Boolean.TRUE, completedInDb(PLAYER, DATE, CHALLENGE),
                "降级必须写 completed=1（SATISFY `WHERE ... completed = 0` 后的结果）");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, CHALLENGE),
                "降级必须写 claimed=1 喵 —— 这一位让所有带 `claimed = 0` 护栏的「再完成」分支永久失效");
        assertEquals(Integer.valueOf(1), abandonedInDb(PLAYER, DATE, CHALLENGE),
                "【M-A 杀点 ①】degradeStale 必须写 abandoned=1 喵 —— 删掉 SET 里的 "
                        + "`abandoned = 1` 后这一行退回默认 0，本断言立刻红。"
                        + "没有这一位，报表那处修复就是交付了一个恒为 0 的死列。");
        assertNotNull(abandonedInDb(PLAYER, DATE, CHALLENGE),
                "abandoned 不得为 NULL 喵 —— 声明是 `INT DEFAULT 0`，出现 NULL 说明写入路径漏了这列");

        // ---- 第三跳：报表真的读到了这个列 ----
        ReportRepository.ChallengeRows rows =
                reportRepo.countDailyChallengesOrFail(PLAYER, DATE, DATE);
        assertTrue(rows.complete(), "报表读取必须成功（complete=true），否则下面两个数字不可信");

        assertEquals(0, rows.progress().done(),
                "【M-A 杀点 ② / M-B 唯一杀点】唯一的一行是系统自动放弃，孩子亲手完成数必须是 0 喵。"
                        + "得 1 说明链路断开：要么 degradeStale 没写 abandoned=1（M-A），"
                        + "要么报表分子没剔除该列（M-B）—— 两者都会让家长看到"
                        + "「本期完成每日挑战 1/1」，而孩子一个都没做。");
        assertEquals(1, rows.progress().total(),
                "分母<b>刻意不改</b>喵：降级行代表'当天系统确实给过他这张挑战'。"
                        + "把它从分母剔除会让完成率变成 1/1 而不是 0/1，等于把一次没做完粉饰成满分。");
    }

    // ==================================================================
    // 用例 2：反向配对 —— 真完成（abandoned=0）必须计入
    // ==================================================================

    /**
     * <b>与用例 1 成对</b>：手造 {@code completed=1, claimed=1, abandoned=0} 的行
     * （模拟「玩家真完成并已领」），断言报表 {@code done == 1}。
     *
     * <h3>这条证明的是「区分 abandoned」而不是「凡 completed=1 就排除」</h3>
     * <p>只有用例 1 的话，一个「报表分子恒返回 0」的实现也能全绿（{@code done} 依旧是 0）。
     * 加上本条，「{@code done} 在 abandoned=0 时必须是 1、在 abandoned=1 时必须是 0」
     * 才构成完整的判别力 —— 报表区分的<b>必须是</b> {@code abandoned} 这一列本身。</p>
     *
     * <p>并刻意让 {@code progress = 0}：{@code COMBO_5} 这类挑战走二进制完成，
     * {@code progress} 保持 0，它的列组合与降级行<b>只差 abandoned 这一列</b>。
     * 若有人把分子改成 {@code progress > 0}（【M-D】），玩家真做完的连击挑战会从
     * 「已完成」里消失 —— 那是少报，比多报更难被发现（家长只会觉得孩子做得少）。</p>
     */
    @Test
    @DisplayName("反向配对：completed=1/claimed=1/abandoned=0（progress=0）必须计入 done")
    void genuineCompletionWithAbandonedZeroCountsAsDone() throws Exception {
        // 手写 abandon=0 在这里是<b>正确</b>的：本条要构造的就是「玩家真完成」的既有形态，
        // 它不经过 degradeStale（那条路径只处理 completed=0 的行）。
        seedWithAbandoned(PLAYER, DATE, CHALLENGE, 0, true, true, 0);

        ReportRepository.ChallengeRows rows =
                reportRepo.countDailyChallengesOrFail(PLAYER, DATE, DATE);
        assertTrue(rows.complete(), "报表读取必须成功");

        assertEquals(1, rows.progress().done(),
                "【M-D 杀点】abandoned=0 的合法完成行必须计入喵 —— 得 0 说明报表用的不是 "
                        + "abandoned 判据（例如被改成 `progress > 0`），"
                        + "COMBO_5 这类二进制完成的挑战会整批从「已完成」里消失，"
                        + "这是<b>少报</b>：家长只会觉得孩子做得少，比多报更难被发现。");
        assertEquals(1, rows.progress().total(), "分母同样不变");
    }

    // ==================================================================
    // 用例 3：COALESCE 兜底 —— 迁移补列后的 NULL 存量行
    // ==================================================================

    /**
     * 迁移用 {@code ALTER TABLE ... ADD COLUMN abandoned INT DEFAULT 0} 补列后，
     * 旧 SQLite 的存量行在该列上可能是 {@code NULL}。
     *
     * <p>本用例断言这种行<b>按「未放弃」计入</b>，钉住分子里的 {@code COALESCE(abandoned, 0)}
     * 不是可有可无的装饰：</p>
     * <ul>
     *   <li>【M-C】把 {@code COALESCE(abandoned, 0) = 0} 改成裸 {@code abandoned = 0} 后，
     *       SQL 三值逻辑把 {@code NULL = 0} 判成 UNKNOWN（非 TRUE），
     *       这一行被静默排除，{@code done} 从 1 变成 0 ——</li>
     *   <li>后果不是「多算一个」，而是<b>升级服的全部历史完成数一夜清零</b>，
     *       且没有任何报错。这条用例是那个场景的唯一守卫。</li>
     * </ul>
     * <p>注意本条<b>不能</b>验证 M-B（删掉整个 {@code AND COALESCE(...)} 子句）：
     * 那个变异只会把 abandoned=1 的行错误计入，与 NULL 行无关 —— 那由用例 1 负责。
     * 三条用例各锁一个变异，互不替代。</p>
     */
    @Test
    @DisplayName("COALESCE 兜底：迁移补列后的 abandoned=NULL 存量行必须按未放弃计入")
    void nullAbandonedLegacyRowStillCountsAsDone() throws Exception {
        exec("INSERT INTO " + PREFIX + "daily_challenges"
                + " (uuid, challenge_date, challenge_id, progress, completed, claimed, abandoned)"
                + " VALUES ('" + PLAYER + "', '" + DATE + "', '" + CHALLENGE + "', 10, 1, 1, NULL)");

        assertNull(abandonedInDb(PLAYER, DATE, CHALLENGE),
                "前置：这一行的 abandoned 必须真的是 NULL（模拟旧 SQLite 的 ADD COLUMN 不回填）");

        ReportRepository.ChallengeRows rows =
                reportRepo.countDailyChallengesOrFail(PLAYER, DATE, DATE);
        assertTrue(rows.complete(), "报表读取必须成功");

        assertEquals(1, rows.progress().done(),
                "【M-C 杀点】abandoned=NULL 的存量行必须计入喵 —— 得 0 说明分子把 "
                        + "COALESCE(abandoned, 0) 改成了裸 `abandoned = 0`，"
                        + "SQL 三值逻辑把 NULL 判成 UNKNOWN 而非 TRUE，"
                        + "于是<b>升级服的全部历史完成数一夜清零</b>，且没有任何报错。");
        assertEquals(1, rows.progress().total(), "分母同样不变（NULL 行仍在窗口内）");
    }

    // ==================================================================
    // 用例 4：WHERE 护栏 —— 已完成的待领行不得被降级，且仍计入 done
    // ==================================================================

    /**
     * {@code degradeStale} 的 SQL 带 {@code AND completed = 0 AND claimed = 0} 护栏：
     * 「孩子做完了、只是在等领奖」（{@code completed=1, claimed=0}）的行<b>不得</b>被降级。
     *
     * <h3>为什么这条必须与报表连在一起断言</h3>
     * <p>一个忘了写 {@code WHERE} 的实现会把这一行也标成放弃 —— 那不只是「状态写错」，
     * 而是<b>少发钱</b>：{@code abandoned=1} 一旦写下不会被撤销（降级行的 {@code claimed=1}
     * 让所有「再完成」分支的护栏永久失效），这一行应得的奖励永远发不出去。
     * 所以本用例同时断言<b>两个面</b>：</p>
     * <ol>
     *   <li>库里 {@code claimed} 仍是 0、{@code abandoned} 仍是 0（没被越界改写）；</li>
     *   <li>报表 {@code done} 仍是 1（这一行仍被当作孩子的真实完成）——
     *       若不是 1，说明它已被标成放弃并被报表剔除，即护栏失效。</li>
     * </ol>
     * <p>两面缺一：只看列值的话，一个「列写对了但报表读错」的实现会漏过去；
     * 只看报表的话，一个「列被改写但报表恰好没读」的实现也会漏过去。</p>
     */
    @Test
    @DisplayName("WHERE 护栏：completed=1/claimed=0 的待领行不得被降级，且报表仍计入 done")
    void completedUnclaimedRowIsNotDegradedAndStillCountsAsDone() throws Exception {
        seed(PLAYER, DATE, CHALLENGE, 10, true, false);

        int affected = challengeRepo.degradeStale(PLAYER, DATE, CHALLENGE);

        assertEquals(0, affected,
                "completed=1 且 claimed=0 的行<b>不得</b>被降级（受影响 0 行）喵 —— "
                        + "孩子做完了在等领奖，被标成放弃就是少发钱");
        assertEquals(Boolean.FALSE, claimedInDb(PLAYER, DATE, CHALLENGE),
                "待领行的 claimed 必须仍是 0（否则奖励永久发不出去）");
        assertEquals(Integer.valueOf(0), abandonedInDb(PLAYER, DATE, CHALLENGE),
                "待领行不得被标成 abandoned=1 喵 —— 那会让报表把它从「已完成」里剔除，"
                        + "孩子的真实成绩凭空消失");

        ReportRepository.ChallengeRows rows =
                reportRepo.countDailyChallengesOrFail(PLAYER, DATE, DATE);
        assertTrue(rows.complete(), "报表读取必须成功");
        assertEquals(1, rows.progress().done(),
                "待领行是孩子的真实完成，报表必须计入 done=1 喵 —— 得 0 说明它被降级路径"
                        + "越界改写了（WHERE 护栏失效），或者报表把 completed=1 的行也排除了");
        assertEquals(1, rows.progress().total(), "分母同样不变");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private String dbUrl() {
        return "jdbc:sqlite:" + tempDir.resolve(DB_FILE).toAbsolutePath();
    }

    /** 在独立连接上执行一条 DDL/DML。 */
    private void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbUrl());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /**
     * 播种一行，<b>不写</b> {@code abandoned} 列 —— 让它走建表默认值。
     *
     * <p>这是本类的核心纪律：需要 {@code abandoned=1} 的地方，只能由生产
     * {@code degradeStale} 产生。若测试自己写上这一列，用例就退化成
     * {@code ReportRepositoryTest} 里那条「手造 abandoned 行 → 验报表」的形态，
     * 生产写入路径上的变异再也无法让它变红。</p>
     */
    private void seed(UUID playerId, String date, String challengeId,
                      int progress, boolean completed, boolean claimed) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbUrl());
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO " + PREFIX + "daily_challenges"
                             + " (uuid, challenge_date, challenge_id, progress, completed, claimed)"
                             + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, date);
            ps.setString(3, challengeId);
            ps.setInt(4, progress);
            ps.setBoolean(5, completed);
            ps.setBoolean(6, claimed);
            ps.executeUpdate();
        }
    }

    /**
     * 播种一行并<b>显式</b>写 {@code abandoned} 列。
     *
     * <p>仅在「要构造的既有形态本来就不经过 {@code degradeStale}」时使用
     * （真完成行、NULL 存量行）。用于降级行即为自证。</p>
     */
    private void seedWithAbandoned(UUID playerId, String date, String challengeId,
                                   int progress, boolean completed, boolean claimed,
                                   int abandoned) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbUrl());
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO " + PREFIX + "daily_challenges"
                             + " (uuid, challenge_date, challenge_id, progress, completed, claimed, abandoned)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, date);
            ps.setString(3, challengeId);
            ps.setInt(4, progress);
            ps.setBoolean(5, completed);
            ps.setBoolean(6, claimed);
            ps.setInt(7, abandoned);
            ps.executeUpdate();
        }
    }

    /**
     * 独立连接直读 {@code abandoned}，返回 {@code Integer}（可 {@code null}）。
     *
     * <p>必须是独立连接：用生产 {@code findState} 做断言等于自证（读写两侧同一个笔误
     * 会互相掩盖）。返回 {@code Integer} 而非 {@code boolean} 是为了区分
     * 「0 / 1 / NULL」三态 —— {@code getBoolean()} 会把 NULL 静默折成 {@code false}，
     * 那样用例 3 的前置条件就无法成立。</p>
     */
    private Integer abandonedInDb(UUID playerId, String date, String challengeId) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbUrl());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT abandoned FROM " + PREFIX + "daily_challenges"
                             + " WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, date);
            ps.setString(3, challengeId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "必须能读到那一行喵");
                Object raw = rs.getObject(1);
                return raw == null ? null : ((Number) raw).intValue();
            }
        }
    }

    private Boolean completedInDb(UUID playerId, String date, String challengeId) {
        return flagInDb("completed", playerId, date, challengeId);
    }

    private Boolean claimedInDb(UUID playerId, String date, String challengeId) {
        return flagInDb("claimed", playerId, date, challengeId);
    }

    private Boolean flagInDb(String column, UUID playerId, String date, String challengeId) {
        try (Connection c = DriverManager.getConnection(dbUrl());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM " + PREFIX + "daily_challenges"
                             + " WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, date);
            ps.setString(3, challengeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        } catch (SQLException e) {
            return null; // 表被删掉 = 读不到
        }
    }
}
