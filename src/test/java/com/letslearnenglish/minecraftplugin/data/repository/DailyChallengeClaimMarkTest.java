package com.letslearnenglish.minecraftplugin.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
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
import com.letslearnenglish.minecraftplugin.data.repository.DailyChallengeRepository.ClaimMark;

/**
 * {@code DailyChallengeRepository.markClaimedOrFail} 的<b>三态判据</b>回归测试 ——
 * 这是「行为挑战领奖」决定<b>发不发钱</b>的那一行 SQL。
 *
 * <h2>被测的契约（一句话）</h2>
 * <pre>
 *   UPDATE ... SET claimed = 1
 *    WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?
 *      AND completed = 1 AND claimed = 0        &lt;-- 全部胜负判据都在这里
 * </pre>
 * <ul>
 *   <li>{@link ClaimMark#MARKED} —— <b>本次</b>真的把 {@code claimed} 从 0 置成 1。
 *       <b>只有它可以发钱。</b></li>
 *   <li>{@link ClaimMark#NOT_ELIGIBLE} —— 护栏不满足（{@code completed != 1} 或
 *       {@code claimed} 已是 1，或压根没有这一行）。<b>正常拒绝，不发钱不算故障。</b></li>
 *   <li>{@link ClaimMark#WRITE_FAILED} —— SQL 抛异常：<b>钱可能已转账、台账未落</b>。
 *       必须能与 {@code NOT_ELIGIBLE} 区分，否则调用方无法分流处置。</li>
 * </ul>
 *
 * <h2>为什么这个类必须存在（0 覆盖的高危点）</h2>
 * <p>{@code target/site/jacoco/jacoco.csv} 显示 {@code DailyChallengeRepository.ClaimMark}
 * 与 {@code ChallengeRow} 覆盖率<b>都是 0 行</b>。而本仓库已经因为「boolean 承载三件事」
 * 翻过<b>三次</b>车：{@code SettlementClaim}、{@code LedgerClearResult}、{@code CheckInClaim}
 * 都是同一类缺陷的修复；{@code ClaimMark} 是第四次改对了的那一个，却<b>没有测试保护</b>——
 * 下一个重构者随手把它折回 {@code boolean} 时，没有任何一条断言会响。</p>
 * <p>折叠的后果不是「多一条日志」，而是
 * {@code MiscGUIListener} 的 {@code case WRITE_FAILED} 分支（SEVERE 对账 + 进程内
 * {@code paidButUnmarked} 防重复付款 + 不重开 GUI）整条失效，玩家在
 * 「钱已到账、{@code claimed} 仍是 0、按钮仍可点」的格子里可以<b>反复领取</b>。</p>
 *
 * <h2>为什么用真 SQLite（而不是 mock 掉 DatabaseManager）</h2>
 * <p>本类要证明的全部内容是「<b>这条 SQL 在真引擎上</b>恰好把 0 改成 1 一次」。
 * mock 掉 {@code DatabaseManager} 只能证明「调用了某个方法」，证明不了 affected-rows
 * 的语义 —— 而那正是胜负判据本身。故本类一律开<b>真 SQLite 文件库</b>
 * （test-scope {@code org.xerial:sqlite-jdbc}，与 pom 同版本），沿用
 * {@code DailyAnswerCountPersistenceTest} / {@code CheckInCrossServerClaimTest} /
 * {@code CheerResultWriteFailureTest} 的手法。
 *
 * <h2>每条用例封死哪个变异</h2>
 * <table border="1">
 *   <caption>变异 → 变红的用例</caption>
 *   <tr><th>变异</th><th>必须变红的用例</th></tr>
 *   <tr><td>M1 {@code catch} 里 {@code WRITE_FAILED} 改回 {@code NOT_ELIGIBLE}（折叠回布尔）</td>
 *       <td>{@link #writeFailureIsNotCollapsedIntoNotEligible()}、
 *           {@link #booleanViewIsExactlyMarkedAndLosesFailureInfo()}</td></tr>
 *   <tr><td>M2 删掉护栏 {@code AND claimed = 0}</td>
 *       <td>{@link #alreadyClaimedIsNotEligibleAndStaysClaimed()}、
 *           {@link #secondCallFromAnotherServerLosesTheRace()}</td></tr>
 *   <tr><td>M3 删掉护栏 {@code AND completed = 1}</td>
 *       <td>{@link #notCompletedIsNotEligibleAndClaimedStaysZero()}</td></tr>
 *   <tr><td>M4 {@code executeUpdate() > 0} 改成 {@code >= 0}</td>
 *       <td>{@link #notCompletedIsNotEligibleAndClaimedStaysZero()}、
 *           {@link #missingRowIsNotEligibleWithoutThrowing()}</td></tr>
 * </table>
 *
 * <p>本类<b>只读</b>生产代码：一个字符都没改。发现缺陷请报告，不要在这里"顺手修"。</p>
 */
@DisplayName("日常挑战领奖三态 — MARKED / NOT_ELIGIBLE / WRITE_FAILED（真 SQLite）")
class DailyChallengeClaimMarkTest {

    /** 与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    /** 固定日期：本类不测跨天逻辑，用常量避免与系统时钟耦合。 */
    private static final String DATE = "2026-02-10";

    /** 每条用例一个独立库文件（同一用例内多个 DatabaseManager 必须指向同一个文件）。 */
    private static final String DB_FILE = "daily-claim-mark.db";

    private static final String CHALLENGE = "MINE_BLOCKS";

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000c1a001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000c1a002");

    @TempDir
    Path tempDir;

    /**
     * plugin / config 的 mock 在整类生命周期内复用：{@code LetsLearnEnglish} 继承链很深，
     * 单次 {@code mock()} 是十秒级开销（同 {@code NodeIdDerivationTest} /
     * {@code DailyAnswerCountPersistenceTest} 的记载）。
     */
    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;
    private static Captured logs;

    /** 本用例开过的库，{@code @AfterEach} 统一关，避免 SQLite 句柄泄漏到下一个用例。 */
    private final List<DatabaseManager> opened = new ArrayList<>();

    private DatabaseManager db;
    private DailyChallengeRepository repo;

    /**
     * 日志探针：{@code WRITE_FAILED} 必须<b>留下 WARNING</b>（"台账没落"这件事要可见，
     * 不能静默），否则它与「压根没被调用」在任何日志里都不可区分。
     */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final List<Level> levels = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("DailyChallengeClaimMarkTest." + UUID.randomUUID());
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
    static void createMocks() {
        logs = new Captured();
        plugin = mock(LetsLearnEnglish.class);
        // 真 Logger：WRITE_FAILED 分支会 log(WARNING, ..., e)，桩成 null 会掩盖真实行为。
        when(plugin.getLogger()).thenReturn(logs.logger);
        configManager = mock(ConfigManager.class);
    }

    @BeforeEach
    void setUp() {
        logs.clear();
        // 每用例一个独立 @TempDir：SQLite 文件、-wal、-shm 全随它一起消失。
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", DB_FILE);
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(configManager.getMainConfig()).thenReturn(cfg);

        db = boot();
        repo = new DailyChallengeRepository(db, logs.logger);
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
        repo = null;
    }

    // ==================================================================
    // 用例 1：MARKED —— 唯一可以发钱的那一态
    // ==================================================================

    /**
     * {@code completed=1, claimed=0} → {@code MARKED}，且库里 {@code claimed} <b>真的</b>变成 1。
     *
     * <p>为什么要同时断言「返回值」与「库里的真值」：只看返回值的话，一个
     * {@code return MARKED} 而不执行 SQL 的实现（或 SQL 打到了错表/错行）能全绿。
     * “钱发出去了、台账没动” 正是 {@code WRITE_FAILED} 想表达的灾难，
     * 不能在 {@code MARKED} 这一态里被复现。</p>
     *
     * <p>同时断言 {@code progress} / {@code completed} 不被这条 UPDATE 顺手改动：
     * SET 列表只有 {@code claimed = 1}，若哪天有人"顺手"把 {@code completed} 也写一遍，
     * 展示侧与统计侧（{@code countUnclaimed}）都会跟着漂。</p>
     */
    @Test
    @DisplayName("MARKED：completed=1 且 claimed=0 → 返回 MARKED，库里 claimed 真的变 1")
    void completedUnclaimedIsMarked() throws Exception {
        seed(PLAYER, DATE, CHALLENGE, 7, true, false);
        assertEquals(Boolean.FALSE, claimedInDb(PLAYER, DATE, CHALLENGE), "前置：claimed 起点必须是 0");

        // 只观察这次调用产生的日志（initialize() 的 INFO 不该干扰"成功路径无 WARNING"的判据）。
        logs.clear();
        ClaimMark mark = repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE);

        assertEquals(ClaimMark.MARKED, mark,
                "completed=1 且 claimed=0 时必须是 MARKED 喵 —— 这是<b>唯一</b>允许发钱的一态。"
                        + "拿到 NOT_ELIGIBLE = 玩家完成了却永远领不到奖；拿到 WRITE_FAILED = 全网不停重试。");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, CHALLENGE),
                "返回 MARKED 但库里 claimed 仍是 0 —— 这就是「钱付了、台账没落」喵，"
                        + "下次 GUI 打开按钮仍显示可领取，玩家可以重复领取。");
        assertEquals(7, progressInDb(PLAYER, DATE, CHALLENGE), "这条 UPDATE 不该动 progress");
        assertEquals(Boolean.TRUE, completedInDb(PLAYER, DATE, CHALLENGE), "这条 UPDATE 不该动 completed");
        assertFalse(logs.anyAtOrAbove(Level.WARNING),
                "成功路径不该有 WARNING 喵：" + logs.messages + " —— 会让运维误以为领取普遍失败");
    }

    // ==================================================================
    // 用例 2：NOT_ELIGIBLE（已领）—— 幂等
    // ==================================================================

    /**
     * {@code claimed=1} → {@code NOT_ELIGIBLE}，且 {@code claimed} <b>仍是</b> 1（幂等）。
     *
     * <p><b>变异指向 M2</b>：删掉护栏 {@code AND claimed = 0}。SQLite 的
     * {@code executeUpdate()} 返回的是语句<b>命中并写回</b>的行数，把 {@code claimed=1}
     * 再写一遍仍然是 1 行 —— 于是重复领取会返回 {@code MARKED}，
     * {@code MiscGUIListener} 的 {@code case MARKED} 就会<b>再发一次钱</b>。
     * 这条断言是这个护栏的唯一守卫。</p>
     */
    @Test
    @DisplayName("NOT_ELIGIBLE（已领）：claimed=1 → NOT_ELIGIBLE 且 claimed 仍为 1（幂等）")
    void alreadyClaimedIsNotEligibleAndStaysClaimed() throws Exception {
        seed(PLAYER, DATE, CHALLENGE, 7, true, true);

        ClaimMark mark = repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE);

        assertEquals(ClaimMark.NOT_ELIGIBLE, mark,
                "claimed 已是 1 时必须 NOT_ELIGIBLE 喵 —— 返回 MARKED 就是<b>重复发钱</b>"
                        + "（删掉护栏 `AND claimed = 0` 即复现：SQLite 对同值覆写仍报 affected=1）");
        assertNotEquals(ClaimMark.WRITE_FAILED, mark, "这是正常拒绝，不是 SQL 故障");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, CHALLENGE), "claimed 必须仍是 1");

        // 幂等到"再调一次也不变"：一条会抖动到 MARKED 的实现，多打几次必然露馅。
        assertEquals(ClaimMark.NOT_ELIGIBLE, repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE),
                "第三次调用仍必须 NOT_ELIGIBLE（幂等语义要稳定，不能靠一次采样）");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, CHALLENGE), "claimed 仍必须是 1");
    }

    // ==================================================================
    // 用例 3：NOT_ELIGIBLE（未完成）
    // ==================================================================

    /**
     * {@code completed=0, claimed=0} → {@code NOT_ELIGIBLE}，且 {@code claimed}
     * <b>仍是 0</b>（不能误置）。
     *
     * <p>这条守的是「<b>完成了才能领</b>」这条业务护栏。两个变异都能杀死它：
     * <ul>
     *   <li><b>M3</b> 删掉 {@code AND completed = 1} → 返回值变成 {@code MARKED}，
     *       且库里 {@code claimed} 被误置成 1 —— 玩家<b>没完成也领钱</b>；</li>
     *   <li><b>M4</b> {@code > 0} 改成 {@code >= 0} → 返回值变成 {@code MARKED}
     *       （affected=0 也被当成"标记成功"）。</li>
     * </ul>
     * 故这里对「返回值」与「库里的 claimed」<b>两个面</b>都断言：只断言返回值的话，
     * 一个"返回 MARKED 但 SQL 没生效"的实现会漏过去。</p>
     */
    @Test
    @DisplayName("NOT_ELIGIBLE（未完成）：completed=0 且 claimed=0 → NOT_ELIGIBLE，claimed 仍为 0")
    void notCompletedIsNotEligibleAndClaimedStaysZero() throws Exception {
        seed(PLAYER, DATE, CHALLENGE, 3, false, false);

        ClaimMark mark = repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE);

        assertEquals(ClaimMark.NOT_ELIGIBLE, mark,
                "completed=0 时必须 NOT_ELIGIBLE 喵 —— 返回 MARKED 就是<b>没完成也发钱</b>"
                        + "（删掉护栏 `AND completed = 1`，或把 `> 0` 写成 `>= 0` 即复现）");
        assertEquals(Boolean.FALSE, claimedInDb(PLAYER, DATE, CHALLENGE),
                "护栏未命中时 claimed 必须**仍是 0** 喵 —— 被误置成 1 后，玩家真完成的那一刻"
                        + "反而会读到「已领取」，该得的奖励永久丢失（比多发钱更难被发现）");
        assertEquals(Boolean.FALSE, completedInDb(PLAYER, DATE, CHALLENGE),
                "这条 UPDATE 不得把 completed 顺手写成 1");
    }

    // ==================================================================
    // 用例 4：NOT_ELIGIBLE（行不存在）
    // ==================================================================

    /**
     * 查无此玩家 / 日期 / 挑战 → {@code NOT_ELIGIBLE}，<b>不抛异常</b>，且不凭空建行。
     *
     * <p><b>变异指向 M4</b>：{@code executeUpdate() > 0} 改成 {@code >= 0}。
     * 零行命中的两条路径（未完成 / 无行）会一起变成 {@code MARKED}，
     * 于是「今天没抽到这个挑战」的玩家点击时会被判定为领取成功。</p>
     *
     * <p>同时断言<b>没有被 INSERT 出幽灵行</b>：这条 SQL 只有 UPDATE，
     * 若哪天被改成 UPSERT，零行路径会凭空写入一行 {@code completed=0, claimed=1} ——
     * 那行会永久堵住该挑战当日的一切正常领取。</p>
     */
    @Test
    @DisplayName("NOT_ELIGIBLE（行不存在）：查无此行 → NOT_ELIGIBLE，不抛异常，不建幽灵行")
    void missingRowIsNotEligibleWithoutThrowing() throws Exception {
        assertNull(claimedInDb(PLAYER, DATE, CHALLENGE), "前置：库里必须一行都没有");
        assertEquals(0, rowCount(PLAYER, DATE), "前置：行数必须是 0");

        ClaimMark mark = repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE); // 不 try/catch：逃出即红

        assertEquals(ClaimMark.NOT_ELIGIBLE, mark,
                "查无此行时必须 NOT_ELIGIBLE 喵 —— 返回 MARKED 就是「零行也算成功」"
                        + "（`> 0` 写成 `>= 0` 即复现）。这条路径上 MARKED 会触发一次真实转账。");
        assertNotEquals(ClaimMark.WRITE_FAILED, mark,
                "零行命中不是 SQL 故障喵 —— 折叠成 WRITE_FAILED 会让每次点击都触发 SEVERE 对账告警");
        assertEquals(0, rowCount(PLAYER, DATE),
                "这条 UPDATE 不得凭空建行喵 —— 出现 completed=0/claimed=1 的幽灵行会永久堵住当日领取");

        // 「行不存在」与「别的玩家/别的日期」必须互不干扰（条件里三个键都要真的参与）。
        seed(OTHER, DATE, CHALLENGE, 1, true, false);
        seed(PLAYER, "2026-02-11", CHALLENGE, 1, true, false);
        assertEquals(ClaimMark.NOT_ELIGIBLE, repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE),
                "旁观者的行不得让本次调用变成 MARKED（uuid / challenge_date 必须真的参与 WHERE）");
        assertEquals(Boolean.FALSE, claimedInDb(OTHER, DATE, CHALLENGE), "旁观玩家的行不得被改动");
        assertEquals(Boolean.FALSE, claimedInDb(PLAYER, "2026-02-11", CHALLENGE),
                "别的日期的行不得被改动（challenge_date 必须真的参与 WHERE）");
    }

    // ==================================================================
    // 用例 5：WRITE_FAILED —— 钱可能已转账、台账未落
    // ==================================================================

    /**
     * SQL 抛异常 → {@code WRITE_FAILED}，<b>绝不能</b>返回 {@code NOT_ELIGIBLE}。
     *
     * <h3>故障注入：真库真异常（不用 mock）</h3>
     * <p>从第二条连接 {@code DROP TABLE lle_daily_challenges}，于是生产语句在
     * {@code prepareStatement} 阶段就抛 {@code SQLException: no such table} ——
     * 正是生产上「迁移未完成 / 表被误删 / 连接指向了空库 / 库被锁死」的形态。
     * <b>不</b>用 Mockito 桩 {@code DatabaseManager}：那只能证明调用方会读这个枚举，
     * 证明不了数据层真的把异常翻成 {@code WRITE_FAILED}（把 {@code return MARKED}
     * 直接写在 catch 里，自证桩照样绿）。</p>
     *
     * <p><b>变异指向 M1</b>：把 catch 里的 {@code WRITE_FAILED} 改回 {@code NOT_ELIGIBLE}
     * （= 原来 boolean 版的折叠），本条立刻变红。</p>
     *
     * <p>最后一段是<b>反过度修复</b>：{@code initialize()} 重建表后必须能重新
     * {@code MARKED} —— 一个「一旦怀疑失败就永久 WRITE_FAILED」的实现能通过前半段，
     * 却会让全服玩家的挑战从此再也领不了。</p>
     */
    @Test
    @DisplayName("WRITE_FAILED：表不存在（真 SQLException）→ WRITE_FAILED，不是 NOT_ELIGIBLE")
    void writeFailureIsNotCollapsedIntoNotEligible() throws Exception {
        // 前置：健康库必须能 MARKED —— 否则下面测的就不是"失败"，而是"本来就没写"。
        seed(PLAYER, DATE, CHALLENGE, 9, true, false);
        assertEquals(ClaimMark.MARKED, repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE),
                "前置：健康库必须是 MARKED");

        seed(PLAYER, DATE, CHALLENGE, 9, true, false); // 复位成可领
        logs.clear();
        exec("DROP TABLE " + PREFIX + "daily_challenges");
        assertNull(claimedInDb(PLAYER, DATE, CHALLENGE), "前置：表确实已被删掉（查询失败即 null）");

        ClaimMark mark = repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE); // 不 try/catch：SQLException 逃出即红

        assertEquals(ClaimMark.WRITE_FAILED, mark,
                "SQL 抛异常时必须 WRITE_FAILED 喵 —— 折叠成 NOT_ELIGIBLE（旧 boolean 的 false）后，"
                        + "MiscGUIListener 的 `case WRITE_FAILED`（SEVERE 对账 + paidButUnmarked 防重复付款"
                        + " + 不重开 GUI）整条失效：钱已到账、claimed 仍是 0、按钮仍可点 → 可重复领取。");
        assertNotEquals(ClaimMark.NOT_ELIGIBLE, mark,
                "WRITE_FAILED 与 NOT_ELIGIBLE 必须是两个不同的值（三态存在性的直接断言）喵");
        assertTrue(logs.anyAtOrAbove(Level.WARNING),
                "写失败必须留下 WARNING（含堆栈）喵 —— 否则「台账没落」与「压根没调用」"
                        + "在任何日志里都不可区分。实际日志：" + logs.messages);

        // 反过度修复：表恢复后必须能重新 MARKED，不能永久停摆。
        db.initialize(); // createTables() 重建空表
        seed(PLAYER, DATE, CHALLENGE, 9, true, false);
        assertEquals(ClaimMark.MARKED, repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE),
                "表重建后必须重新 MARKED 喵 —— 一个「失败过一次就永久 WRITE_FAILED」的实现"
                        + "会让全服玩家从此再也领不到挑战奖励");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, CHALLENGE), "恢复后 claimed 必须真的写成 1");
    }

    // ==================================================================
    // 用例 6：两台服 —— 同一行只能被标记一次
    // ==================================================================

    /**
     * <b>两台服（两个独立 {@link DatabaseManager}，同一个 .db 文件）</b>对同一行各调一次：
     * 先到者 {@code MARKED}、后到者 {@code NOT_ELIGIBLE}。
     *
     * <p>为什么这条不是用例 1+2 的重复：那两条用的<b>同一个连接</b>。
     * 跨连接的可见性才是「多服」的最小可观测等价物 —— 若护栏写在 Java 侧
     * （例如先 {@code findState} 再无条件 UPDATE），单连接测试全绿，双连接立刻双份钱。
     * {@code CheckInCrossServerClaimTest} 记录的正是这个形态的已发生事故。</p>
     *
     * <p>判据是<b>枚举计数</b>，不是调用次数：{@code MARKED} 恰好 1 次，库里 {@code claimed}=1。</p>
     *
     * <p><b>变异指向 M2</b>：删掉 {@code AND claimed = 0} 后，B 服那条 UPDATE 会命中并写回
     * 1 行 → 第二个 {@code MARKED} → 本条变红。</p>
     */
    @Test
    @DisplayName("两服抢占（串行、两条连接）：恰好一次 MARKED、一次 NOT_ELIGIBLE")
    void secondCallFromAnotherServerLosesTheRace() throws Exception {
        DatabaseManager dbB = boot(); // 同一个 dataFolder + 同一个文件名 → 同一个库，另一条连接
        DailyChallengeRepository repoB = new DailyChallengeRepository(dbB, logs.logger);
        seed(PLAYER, DATE, CHALLENGE, 5, true, false);

        ClaimMark first = repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE);
        ClaimMark second = repoB.markClaimedOrFail(PLAYER, DATE, CHALLENGE);

        assertEquals(ClaimMark.MARKED, first, "A 服必须先抢到（MARKED）");
        assertEquals(ClaimMark.NOT_ELIGIBLE, second,
                "B 服必须看到 A 服已写好的 claimed=1 并拒绝喵 —— 第二个 MARKED 就是<b>同一格发两次钱</b>。"
                        + "实测 first=" + first + " second=" + second + "；"
                        + "若 second 是 WRITE_FAILED，说明 SQLITE_BUSY 把并发写饿死了（fail-closed，不重复发钱）");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, CHALLENGE), "库里 claimed 必须是 1");
    }

    /**
     * <b>真并发</b>：两个线程各持自己的 {@link DatabaseManager}（= 两条连接）同时抢同一行。
     *
     * <p>这是原缺陷的确切现场：读与写之间没有 fencing 时两边都会「读到未领取 → 各写一次」。
     * 胜负判据只允许一个赢家。多轮是为了让"恰好一次"不是一次幸运采样。</p>
     *
     * <p>判定刻意<b>只</b>硬断言「{@code MARKED} 恰好 1 次」：那正是钱的安全边界。
     * 输家的具体枚举（{@code NOT_ELIGIBLE} vs {@code WRITE_FAILED}）取决于 SQLite 的
     * 写锁调度 —— {@code WRITE_FAILED} 是 fail-closed（不发钱），不是重复发钱，
     * 故不计入失败；但它会被打印出来，便于发现"每次都在 BUSY"这类性能问题。</p>
     */
    @Test
    @DisplayName("两服真并发抢同一行 → 每轮恰好一次 MARKED（多轮）")
    void concurrentClaimsFromTwoServersYieldExactlyOneMarked() throws Exception {
        DatabaseManager dbB = boot();
        DailyChallengeRepository repoB = new DailyChallengeRepository(dbB, logs.logger);

        int rounds = 5;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                // 每轮换一个挑战 id，避免上一轮的结果污染这一轮。
                String challengeId = "C" + round;
                seed(PLAYER, DATE, challengeId, 1, true, false);

                CyclicBarrier startLine = new CyclicBarrier(2);
                List<ClaimMark> outcomes = Collections.synchronizedList(new ArrayList<>());
                List<DailyChallengeRepository> repos = List.of(repo, repoB);
                CountDownLatch bothDone = new CountDownLatch(repos.size());
                for (DailyChallengeRepository each : repos) {
                    pool.submit(() -> {
                        try {
                            startLine.await(5, TimeUnit.SECONDS);
                            outcomes.add(each.markClaimedOrFail(PLAYER, DATE, challengeId));
                        } catch (Exception e) {
                            // 屏障超时 / 中断：记成 WRITE_FAILED（仍是"没赢"），不吞掉轮次。
                            outcomes.add(ClaimMark.WRITE_FAILED);
                        } finally {
                            bothDone.countDown();
                        }
                    });
                }
                assertTrue(bothDone.await(20, TimeUnit.SECONDS),
                        "第 " + round + " 轮：两个线程必须都在时限内结束喵");

                long marked = outcomes.stream().filter(o -> o == ClaimMark.MARKED).count();
                assertEquals(1, marked,
                        "第 " + round + " 轮：两服并发必须<b>恰好一个</b> MARKED 喵，实测 "
                                + outcomes + " —— 两个 MARKED 就是同一格发两次真钱");
                assertEquals(2, outcomes.size(), "每轮必须有两个结局喵，实测 " + outcomes);
                assertTrue(outcomes.contains(ClaimMark.MARKED), "每轮必须有一个赢家喵，实测 " + outcomes);
                assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, challengeId),
                        "第 " + round + " 轮结束后 claimed 必须是 1");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================================================================
    // 用例 7：markClaimed(boolean) 的退化语义
    // ==================================================================

    /**
     * 钉住「退化视图」契约：{@code markClaimed(...) == (markClaimedOrFail(...) == MARKED)}。
     *
     * <p>这条看着是同义反复，其实钉的是<b>两件独立的事</b>：
     * <ol>
     *   <li>布尔版<b>只能</b>在 {@code MARKED} 时返回 true —— 否则
     *       {@code DailyChallengeManager.markClaimed} 会在护栏不满足时也发出奖励金额；</li>
     *   <li>布尔版<b>必然丢失</b> {@code WRITE_FAILED}（{@code false}）—— 这正是三态存在的理由。
     *       把这条写成断言，是为了让"折回 boolean"这个重构<b>立刻表现为测试变红</b>，
     *       而不是被一句"反正都是 false"糊过去。</li>
     * </ol>
     *
     * <p><b>变异指向 M1</b>：若把 {@code ClaimMark} 折叠回 boolean，
     * 下面的 {@code WRITE_FAILED} 断言段就无从表达（枚举值不存在 → 编译错误），
     * 而"降级后仍返回 false"这条会静默继续绿 —— 所以本类同时保留了
     * {@link #writeFailureIsNotCollapsedIntoNotEligible()} 作为运行时守卫。</p>
     */
    @Test
    @DisplayName("退化视图：markClaimed() == (markClaimedOrFail() == MARKED)，WRITE_FAILED 折叠为 false")
    void booleanViewIsExactlyMarkedAndLosesFailureInfo() throws Exception {
        // ① MARKED 面：布尔版 true
        seed(PLAYER, DATE, CHALLENGE, 2, true, false);
        assertTrue(repo.markClaimed(PLAYER, DATE, CHALLENGE),
                "MARKED 时布尔版必须 true 喵 —— 恒 false 会让 DailyChallengeManager.claimReward 永远回 0");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, CHALLENGE), "布尔版也必须真的落库");

        // ② 已领面：布尔版 false（= NOT_ELIGIBLE）
        assertFalse(repo.markClaimed(PLAYER, DATE, CHALLENGE),
                "NOT_ELIGIBLE 时布尔版必须 false 喵");
        assertEquals(ClaimMark.NOT_ELIGIBLE, repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE),
                "同一状态在三态版上必须是 NOT_ELIGIBLE（不是 WRITE_FAILED）");

        // ③ 未完成面：布尔版 false（= NOT_ELIGIBLE）
        seed(OTHER, DATE, CHALLENGE, 0, false, false);
        assertFalse(repo.markClaimed(OTHER, DATE, CHALLENGE),
                "completed=0 时布尔版必须 false 喵");
        assertEquals(ClaimMark.NOT_ELIGIBLE, repo.markClaimedOrFail(OTHER, DATE, CHALLENGE),
                "未完成在三态版上必须是 NOT_ELIGIBLE");

        // ④ WRITE_FAILED 面：布尔版 false —— 这就是「退化」本身
        exec("DROP TABLE " + PREFIX + "daily_challenges");
        assertEquals(ClaimMark.WRITE_FAILED, repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE),
                "前置：表已删，三态版必须是 WRITE_FAILED");
        assertFalse(repo.markClaimed(PLAYER, DATE, CHALLENGE),
                "WRITE_FAILED 在布尔版上必然是 false 喵 —— 「钱已付、台账没落」被折叠成一个普通的"
                        + "「不可领」，调用方再也无法分流。这条断言存在的意义是<b>记录退化会丢信息</b>，"
                        + "任何把三态改回布尔的实现都必须先让上面 ④ 的第一条断言无从通过。");
    }

    // ==================================================================
    // 用例 8：库真的挂了（shutdown 之后）—— 「连不上」这一形态
    // ==================================================================

    /**
     * {@code DatabaseManager.shutdown()} 之后再调 {@code markClaimedOrFail} →
     * {@code WRITE_FAILED}，<b>不是</b> {@code NOT_ELIGIBLE}。
     *
     * <h3>这条为什么必须存在（此前唯一的漏洞）</h3>
     * <p>用例 5 用「表被删」制造异常，覆盖的是<b>语句执行期</b>失败。生产上还有第二种、
     * 也是更常见的形态：<b>库整个不可用</b>（关服竞态、连接被服务端踢掉、文件被移走）。
     * 这条路径下 {@link DatabaseManager#getConnection()} 在 {@code shuttingDown} 为真时
     * 返回 {@code UNAVAILABLE} 代理，而该代理在 {@code prepareStatement} 上直接抛
     * {@code SQLException} —— 于是异常在 <b>取连接阶段</b>就发生（连语句都没建起来），
     * 由生产方法自己的 {@code catch (SQLException)} 接住，返回 {@code WRITE_FAILED}。
     * 这与用例 5 的「语句执行期失败」是<b>两个不同触发点</b>：那条走 {@code DROP TABLE}
     * 让语句建立后失败，这条走连接替身让语句根本建不起来。</p>
     * <p>如果没有这条用例，把 catch 里的 {@code WRITE_FAILED} 改回 {@code NOT_ELIGIBLE}
     * 时，只要「表被删」那条用例还在，M1 仍会被杀死 —— 但「库不可用」这条形态
     * <b>没有任何断言钉住</b>：重构者可以为让用例 5 通过而只处理「表不存在」，
     * 让「连接不可用」继续折叠成「不可领」，而后者恰恰是关服竞态下
     * 「钱已转账、台账没落」最容易发生的现场。</p>
     *
     * <h3>断言设计</h3>
     * <ul>
     *   <li>返回值必须是 {@code WRITE_FAILED}，且 {@code assertNotEquals(NOT_ELIGIBLE)} 单独成立
     *       —— 前者靠 {@code equals}，后者在将来枚举扩值时仍能守住"不许折叠"；</li>
     *   <li>必须留 WARNING：库挂了却不吭声，运维无从发现；</li>
     *   <li>库里的 {@code claimed} 必须<b>仍是 0</b>（用独立连接读，不信生产 {@code findState}）——
     *       这一态的含义是"<b>台账没落</b>"，如果它被写成 1，反而变成"没写却声称写了"；</li>
     *   <li>反过度修复：重新 {@code initialize()} 后必须能重新 {@code MARKED}，
     *       否则一次关库就让全服当天再也领不了。</li>
     * </ul>
     */
    @Test
    @DisplayName("WRITE_FAILED（库不可用）：shutdown() 后调用 → WRITE_FAILED，不折叠成 NOT_ELIGIBLE")
    void unavailableConnectionIsWriteFailedNotNotEligible() throws Exception {
        // 前置：健康库必须能 MARKED —— 否则下面测到的可能是"本来就不该领"。
        seed(PLAYER, DATE, CHALLENGE, 4, true, false);
        assertEquals(ClaimMark.MARKED, repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE),
                "前置：健康库必须是 MARKED");
        seed(PLAYER, DATE, CHALLENGE, 4, true, false); // 复位成可领

        db.shutdown();                 // 闩上 shuttingDown，getConnection() 从此返回 UNAVAILABLE 代理
        logs.clear();

        ClaimMark mark = repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE);

        assertEquals(ClaimMark.WRITE_FAILED, mark,
                "库不可用时必须 WRITE_FAILED 喵 —— 这不是「不可领」，是「<b>不知道能不能领</b>」。"
                        + "折叠成 NOT_ELIGIBLE 后，MiscGUIListener 不会走 SEVERE 对账、不登记"
                        + " paidButUnmarked，玩家在「钱已转账、claimed 仍为 0、按钮仍可点」的格子里可以反复领取。");
        assertNotEquals(ClaimMark.NOT_ELIGIBLE, mark,
                "「连不上库」与「护栏不满足」必须是两个不同的值喵 —— 前者要重试/对账，后者是正常拒绝");
        assertTrue(logs.anyAtOrAbove(Level.WARNING),
                "库不可用必须留下 WARNING（含堆栈）喵 —— 否则关库竞态下的领取失败在日志里完全静默。"
                        + "实际日志：" + logs.messages);
        assertEquals(Boolean.FALSE, claimedInDb(PLAYER, DATE, CHALLENGE),
                "写失败时库里的 claimed 必须仍是 0 喵 —— 这一态的定义就是「钱可能已付、台账没落」，"
                        + "若读成 1 说明有人把失败当成功写了回头");

        // 反过度修复：重新起库后必须能正常领取，不能因为"失败过一次"就永久停摆。
        db.initialize();               // 清闩 + 重连 + 建表
        seed(PLAYER, DATE, CHALLENGE, 4, true, false);
        assertEquals(ClaimMark.MARKED, repo.markClaimedOrFail(PLAYER, DATE, CHALLENGE),
                "重连后必须重新 MARKED 喵 —— 一个「关过库就再也不领」的实现会让当天所有奖励作废");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, CHALLENGE), "恢复后 claimed 必须真的写成 1");
    }

    // ==================================================================
    // 用例 9：challenge_date 格式敏感性 —— 「完成却永远领不到」
    // ==================================================================

    /**
     * 钉住当前行为：{@code challenge_date} 传<b>非 ISO 补零格式</b>（{@code "2026-2-10"}）
     * 会<b>静默</b>返回 {@code NOT_ELIGIBLE}，即使库里那行 {@code completed=1, claimed=0} 完全符合领取条件。
     *
     * <h3>这是「记录缺陷」而不是「期望行为」</h3>
     * <p>本用例<b>不</b>主张格式敏感是对的。它存在的目的是把这条风险钉成可执行的断言：
     * 一旦有人改掉日期规范化逻辑（无论改成拒绝还是改成归一化），本条就会变红，
     * 迫使改动者<b>显式</b>决定新语义，而不是让一处静默的「玩家完成了但永远领不到」
     * 在重构里悄悄换了形态。</p>
     * <p>为什么它危险：{@code NOT_ELIGIBLE} 与「已完成待领取」在任何返回值、任何日志、
     * 任何 GUI 状态里都<b>不可区分</b>。玩家点一次、两次、十次，得到的都是同一个
     * 「不可领」，没有任何一处能告诉运维「日期格式错了」。而 {@code claimed} 始终是 0，
     * 所以钱也是永远发不出去 —— 属于"少发钱"类缺陷，比多发钱更难被发现。</p>
     *
     * <h3>为什么用「对照组 + 变异组」而不是只断言一次 {@code NOT_ELIGIBLE}</h3>
     * <p>只断言「散格式 → NOT_ELIGIBLE」是假绿高发点：一个<b>永远</b>返回
     * {@code NOT_ELIGIBLE} 的实现（例如忘了执行 SQL）能通过它。故先断言同一行在
     * ISO 格式下确实 {@code MARKED}（证明这行<b>本就可领</b>），再断言换格式后
     * 被拒，最后断言库里 {@code claimed} 仍是 0（证明确实没写）。</p>
     */
    @Test
    @DisplayName("challenge_date 格式敏感性：\"2026-2-10\" 静默 NOT_ELIGIBLE（已知风险，已钉住）")
    void nonIsoDateSilentlyYieldsNotEligible() throws Exception {
        final String iso = DATE;              // "2026-02-10"
        final String drifted = "2026-2-10";   // 非 ISO 补零：日/月不补零

        // 对照组：同一行用 ISO 格式必须是 MARKED —— 证明这行本就可领，不是"本来就没有"
        seed(PLAYER, iso, CHALLENGE, 8, true, false);
        assertEquals(ClaimMark.MARKED, repo.markClaimedOrFail(PLAYER, iso, CHALLENGE),
                "对照组前置：ISO 格式下必须 MARKED 喵 —— 否则本条测到的不是格式问题，而是这行根本不可领");

        // 变异组：把同一行复位成可领，改用非补零日期
        seed(PLAYER, iso, CHALLENGE, 8, true, false);
        ClaimMark mark = repo.markClaimedOrFail(PLAYER, drifted, CHALLENGE);

        assertEquals(ClaimMark.NOT_ELIGIBLE, mark,
                "已钉住的当前行为：非 ISO 补零日期（\"2026-2-10\"）会静默 NOT_ELIGIBLE 喵。"
                        + "<b>这是格式敏感性的已知风险，不是正确行为</b>：库里那行 completed=1 且 claimed=0，"
                        + "玩家明明完成了挑战，却永远领不到奖励，且与「未完成」在任何返回值/日志/GUI 里都不可区分。"
                        + "若本条变红，说明日期规范化行为改了 —— 请显式确认新语义（拒绝？归一化？）后再更新本条。");
        assertNotEquals(ClaimMark.WRITE_FAILED, mark,
                "格式不匹配不是 SQL 故障喵 —— 若返回 WRITE_FAILED 说明实现改成了「拼不出 SQL 就报错」，"
                        + "那是另一种（更吵、但可发现的）形态，需同步更新本条");
        assertEquals(Boolean.FALSE, claimedInDb(PLAYER, iso, CHALLENGE),
                "散格式的调用不得改动 ISO 那行喵 —— claimed 若变成 1，说明日期参数被归一化过了"
                        + "（那样上面 NOT_ELIGIBLE 的断言就该变红了）");
        assertEquals(Boolean.TRUE, completedInDb(PLAYER, iso, CHALLENGE), "completed 不该被顺手改动");

        // 反证「这行仍然可领」：紧接着用正确格式再领一次必须成功 ——
        // 把「格式不匹配 → 永久破坏状态」与「格式不匹配 → 这次没领到」区分开。
        assertEquals(ClaimMark.MARKED, repo.markClaimedOrFail(PLAYER, iso, CHALLENGE),
                "换回 ISO 格式后必须仍能领取喵 —— 证明散格式调用只影响本次匹配，没有破坏那行的可领状态");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, iso, CHALLENGE), "换回 ISO 后 claimed 必须真的写成 1");
    }

    // ==================================================================
    // 用例 10：D9 —— degradeStale 必须写 abandoned=1（否则该列是死列）
    // ==================================================================

    /**
     * {@code degradeStale} 除了写 {@code completed=1, claimed=1}，<b>还必须</b>写
     * {@code abandoned=1}。
     *
     * <h3>为什么这条必须有（只加 schema 不加写入方 = 死列）</h3>
     * <p>报表 Q8 的分子改用 {@code completed = 1 AND COALESCE(abandoned, 0) = 0}
     * 之后，若 {@code degradeStale} 不写这一列，<b>报表行为一个字节都不会变</b>
     * —— 而 schema 里那列永远读成 0，看起来一切正常。本用例是这条链路的写入端唯一守卫：
     * 删掉 SET 里的 {@code abandoned = 1} 而不改任何断言，Q8 的修复会静默失效。</p>
     *
     * <h3>同时钉住「不越界改写」</h3>
     * <p>SQL 的 WHERE 是 {@code completed = 0 AND claimed = 0}，即只降级"孩子确实没做"的行。
     * 已完成未领取（{@code completed=1, claimed=0}）的行保留其应得奖励 —— 本用例播种这样
     * 一行并断言 {@code claimed} 仍为 0、{@code abandoned} 仍为 0。否则一个忘了写 WHERE
     * 的实现会把「孩子做完了在等领奖」也标成放弃，那是<b>少发钱</b>。</p>
     */
    @Test
    @DisplayName("D9：degradeStale 写 completed=1/claimed=1/abandoned=1，且不动已完成的待领行")
    void degradeStaleWritesAbandonedFlag() throws Exception {
        // 该被降级的：孩子零进度、未完成未领取
        seed(PLAYER, DATE, CHALLENGE, 0, false, false);
        // 不该被动的：同一玩家、同一挑战，但孩子真做完了、只是在等领奖（另一天，
        // 与该行不共享主键 —— 这样才能真正检验 WHERE 的 `completed = 0 AND claimed = 0`，
        // 而不是靠 uuid/challenge_id 不匹配蒙对）
        final String pendingDate = "2026-02-11";
        seed(PLAYER, pendingDate, CHALLENGE, 10, true, false);

        assertEquals(1, repo.degradeStale(PLAYER, DATE, CHALLENGE),
                "零进度未完成未领取的那一行必须被降级（受影响 1 行）喵");

        assertEquals(Boolean.TRUE, completedInDb(PLAYER, DATE, CHALLENGE), "降级必须置 completed=1");
        assertEquals(Boolean.TRUE, claimedInDb(PLAYER, DATE, CHALLENGE), "降级必须置 claimed=1（堵住再完成分支）");
        assertEquals(Integer.valueOf(1), abandonedInDb(PLAYER, DATE, CHALLENGE),
                "degradeStale 必须写 abandoned=1 喵 —— 不写的话报表分子那处修复就是死的："
                        + "列恒为 0，Q8 仍把系统放弃的算成孩子完成的。");

        assertEquals(0, repo.degradeStale(PLAYER, pendingDate, CHALLENGE),
                "completed=1 且 claimed=0 的行<b>不得</b>被降级（受影响 0 行）喵 —— "
                        + "孩子做完了在等领奖，被标成放弃就是少发钱");
        assertEquals(Boolean.FALSE, claimedInDb(PLAYER, pendingDate, CHALLENGE), "待领行的 claimed 必须仍是 0");
        assertEquals(Integer.valueOf(0), abandonedInDb(PLAYER, pendingDate, CHALLENGE),
                "待领行不得被标成 abandoned");

        // 幂等：降级过的行再降级一次不得重复命中（WHERE 的 completed=0 已不成立）
        assertEquals(0, repo.degradeStale(PLAYER, DATE, CHALLENGE),
                "已降级的行再次调用必须 0 行 —— 否则重复调用会重复写同一行的状态");
        assertEquals(Integer.valueOf(1), abandonedInDb(PLAYER, DATE, CHALLENGE), "幂等调用后 abandoned 仍是 1");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 起一次「进程」：真 {@code initialize()} 到 {@code @TempDir} 下的同一个 db 文件。
     *
     * <p>同一用例内多次调用 = 多个 {@code DatabaseManager} 指向同一个文件
     * （照 {@code DailyAnswerCountPersistenceTest.boot} / {@code NodeIdDerivationTest}）。
     */
    private DatabaseManager boot() {
        DatabaseManager created = new DatabaseManager(plugin, configManager);
        created.initialize();
        assertTrue(created.isSQLite(), "前置条件：本类必须走真 SQLite 分支，否则故障注入手段无效");
        when(plugin.getDatabaseManager()).thenReturn(created);
        opened.add(created);
        return created;
    }

    private String dbUrl() {
        return "jdbc:sqlite:" + tempDir.resolve(DB_FILE).toAbsolutePath();
    }

    /** 在独立连接上执行一条 DDL（故障注入用）。 */
    private void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbUrl());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /**
     * 直接往库里播种一行（模拟"玩家今天有这个挑战"的历史状态）。
     *
     * <p>刻意<b>不</b>走生产 {@code assignAll} 再用生产方法推进进度：那等于用被测代码
     * 造前置，出错时无法区分"前置没建成"与"被测逻辑坏了"。
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
     * 独立连接直读 {@code claimed}；<b>无行或读失败返回 {@code null}</b>（与 0 区分）。
     *
     * <p>必须是独立连接：用生产 {@code findState} 做断言等于自证（读写两侧同一个笔误
     * 会互相掩盖），而且表被删掉后 {@code findState} 会降级成 {@code null}，
     * 恰好是我们要区分的那个"失败"。
     */
    private Boolean claimedInDb(UUID playerId, String date, String challengeId) {
        return flagInDb("claimed", playerId, date, challengeId);
    }

    private Boolean completedInDb(UUID playerId, String date, String challengeId) {
        return flagInDb("completed", playerId, date, challengeId);
    }

    /**
     * 独立连接直读 {@code abandoned}（D9）。
     *
     * <p>返回 {@code Integer}（可 {@code null}）而不是 {@code boolean}：这一列要区分
     * 「0 / 1 / NULL」三态 —— {@code getBoolean()} 会把 NULL 静默折成 {@code false}，
     * 那样「迁移把存量行补成了什么值」这个必须被看见的事实就被抹掉了。
     * 迁移用 {@code INT DEFAULT 0} 补列，新行与补过的旧行都应是 0，出现 NULL 即说明
     * 迁移路径与本 DDL 不一致。
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
            return null; // 表被删掉 = 读不到（调用方用 assertNull 区分"没有行"）
        }
    }

    private int progressInDb(UUID playerId, String date, String challengeId) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbUrl());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT progress FROM " + PREFIX + "daily_challenges"
                             + " WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, date);
            ps.setString(3, challengeId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "必须能读到那一行喵");
                return rs.getInt(1);
            }
        }
    }

    private int rowCount(UUID playerId, String date) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbUrl());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM " + PREFIX + "daily_challenges"
                             + " WHERE uuid = ? AND challenge_date = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}
