package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

/**
 * R6-06-EH-F1：{@link DatabaseManager#cheerFeed} 的<b>四态</b>语义 —— 写库失败必须与
 * 「行确实不存在」在任何读侧状态下都不可混淆。
 *
 * <h2>为什么需要这个类（红队实测的假绿缺口）</h2>
 * round5 的修复只在消费侧加了事后判别：{@code result == GONE && loadCheerers().readFailed()}
 * → 视为写失败。那是用<b>读侧健康度</b>推断<b>写侧故障</b>。而本仓默认
 * {@code journal_mode=WAL}（{@code DatabaseManager:161}），WAL 的设计目的恰恰是
 * 「写时读不阻塞」—— 所以「写失败 + 读成功」不是边角，而是本仓配置下的<b>主路径</b>。
 * 用它做判别，方向反了。
 *
 * <p>本类<b>不打 mock 连接</b>：mock 的 {@code executeUpdate} 只能演「我抛 / 我不抛」，
 * 演不出「写被引擎拒绝、而同一连接随后仍能读到数据」这个真实形态。所以这里直接开
 * <b>真 SQLite</b>（test-scope {@code org.xerial:sqlite-jdbc}，与 pom 同版本），
 * 复刻本仓真实 PRAGMA，再逐一注入故障。
 *
 * <h2>五条用例各自封死的变异</h2>
 * <ol>
 *   <li>{@link WriteFailureNotCollapsedToGone#writeFailureIsNotReportedAsGone()} ——
 *       把 {@code catch (SQLException)} 改回 {@code return CheerResult.GONE}，立刻红。</li>
 *   <li>{@link WriteFailureNotCollapsedToGone#genuineGoneStillReportsGone()} ——
 *       防「一律报失败」的过度修复：行真的不在时必须是 {@code GONE}。</li>
 *   <li>{@link WriteFailureNotCollapsedToGone#writeSideFaultIsNotInferredFromReadSide()} ——
 *       本条的<b>核心</b>：在每个故障形态上同时断言「写侧报 FAILED」与「读侧此刻是健康的」。
 *       两者同时成立，就证明任何以 {@code readFailed} 为输入的判别式<b>必然</b>把故障
 *       误判成 {@code GONE}。这条用例在 round5 的代码上会红（当时返回 GONE）。</li>
 *   <li>{@link ConsumerSide#debounceStampClearedForEveryFailureForm()} —— 防抖戳回收
 *       不再依赖读侧；任何 {@code FAILED} 都回收。</li>
 *   <li>{@link ConsumerSide#goneDoesNotTriggerAnExtraRead()} —— 删掉判别式那次多余
 *       {@code loadCheerers} 往返后，{@code GONE} 路径的读次数不再增加（防退化复发）。</li>
 * </ol>
 *
 * <p><b>为什么不依赖 Bukkit 运行时</b>：{@code DatabaseManager} 除
 * {@code plugin.getLogger()} / {@code plugin.getDataFolder()} / {@code configManager
 * .getMainConfig()} 外不触 Bukkit 静态方法，因此 {@code mock(LetsLearnEnglish.class)} +
 * 真 {@link YamlConfiguration} + {@link TempDir} 就够跑真库。同款手法见
 * {@code SqliteMaintenanceTest}。
 */
class CheerResultWriteFailureTest {

    private static final String PREFIX = "lle_";

    /** plugin / config 的 mock 在整类生命周期内复用（{@code mock()} 单次约 10s 级开销）。 */
    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;

    @BeforeAll
    static void createMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        // 真 Logger：失败路径的 log(WARNING, ..., e) 需要真实例，否则 NPE 会掩盖真实行为。
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CheerResultWriteFailureTest"));
    }

    // ==================================================================
    // 数据层：四态语义（真 SQLite，注入四种写库故障）
    // ==================================================================

    @Nested
    @DisplayName("数据层 —— cheerFeed 的四态（真 SQLite + 本仓真实 PRAGMA）")
    class WriteFailureNotCollapsedToGone {

        private DatabaseManager db;
        private String url;
        private long feedId;

        @BeforeEach
        void initRealDatabase(@TempDir Path dir) throws Exception {
            FileConfiguration config = new YamlConfiguration();
            config.set("database.type", "sqlite");
            config.set("database.sqlite.filename", "cheer-test.db");
            when(configManager.getMainConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(dir.toFile());

            db = new DatabaseManager(plugin, configManager);
            db.initialize();
            assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则故障注入手段无效");
            url = "jdbc:sqlite:" + db.getSqliteFilePath();
            feedId = seedFeed();
        }

        @AfterEach
        void closeDatabase() {
            if (db != null) {
                db.shutdown();
            }
        }

        /**
         * <b>前置条件自检</b>：真库 PRAGMA 必须与本仓生产配置一致。
         *
         * <p>为什么这条不是形式主义：WAL 是这个缺陷的<b>放大器</b>——若测试里的库不是 WAL，
         * 「写失败 + 读成功」这个形态根本不成立，本类的核心断言会退化成空转。
         */
        @Test
        @DisplayName("前置：真库跑在本仓真实 PRAGMA 上（journal_mode=WAL, busy_timeout=5000）")
        void runsUnderProductionPragmas() throws Exception {
            assertEquals("wal", scalar("PRAGMA journal_mode").toLowerCase(),
                    "测试库不是 WAL 喵 —— 本缺陷的主路径（写失败 + 读成功）依赖 WAL 语义，"
                            + "PRAGMA 跑偏会让本类的核心断言变成空转。");
            assertEquals("5000", scalar("PRAGMA busy_timeout"),
                    "busy_timeout 与 DatabaseManager:169 不一致 —— 锁竞争的表现形态会变（立刻失败"
                            + " vs 等 5 秒），断言时刻可能不稳。");
        }

        /**
         * 核心回归：<b>写失败不得被折叠成 GONE</b>。
         *
         * <p>注入 WAL 写锁竞争（第二个连接持 {@code BEGIN IMMEDIATE} 写事务），让
         * {@code social_cheers} 的 INSERT 抛 {@code SQLITE_BUSY}。
         *
         * <p>红队变异：把 {@code DatabaseManager.cheerFeed} 的
         * {@code catch (SQLException)} 改回 {@code return CheerResult.GONE}。
         */
        @Test
        @DisplayName("WAL 写锁竞争：cheerFeed 返回 FAILED，绝不是 GONE")
        void writeFailureIsNotReportedAsGone() throws Exception {
            DatabaseManager.CheerResult result;
            try (Connection blocker = DriverManager.getConnection(url)) {
                blocker.setAutoCommit(false);
                try (Statement st = blocker.createStatement()) {
                    st.executeUpdate("INSERT INTO " + PREFIX + "player_data (uuid, player_name)"
                            + " VALUES ('locker', 'locker')");
                }
                result = db.cheerFeed(feedId, UUID.randomUUID());
                blocker.rollback();
            }

            assertNotEquals(DatabaseManager.CheerResult.GONE, result,
                    "写库故障被折叠成 GONE 了喵 —— 这是本缺陷的原始行为（R6-06-EH-F1）。\n"
                            + "GONE 在消费侧渲染成 square.feed.cheer-gone「这条动态已经翻篇了」，"
                            + "于是「库写不进去」被谎报成业务事实，玩家以为动态被别人删了。\n"
                            + "正确语义：SQLException 必须返回 FAILED，由调用方给「请稍后再试」类提示。");
            assertEquals(DatabaseManager.CheerResult.FAILED, result,
                    "写库故障必须自成一态（CheerResult.FAILED），实际拿到 " + result + " 喵。");
        }

        /**
         * 反过度修复：<b>行真的不存在</b>时仍必须是 {@code GONE}。
         *
         * <p>若为了消除谎报而把 GONE 一并改成 FAILED，玩家点一条确实已被清理的旧动态会收到
         * 「鼓励失败，请稍后再试」—— 无限重试永远失败，且日志里没有任何真故障。这是
         * 从「谎报」滑到「另一个谎报」。
         */
        @Test
        @DisplayName("真正的 GONE（feed 行确实不存在）仍返回 GONE，不被改成 FAILED")
        void genuineGoneStillReportsGone() {
            long absentFeedId = Long.MAX_VALUE - 7L;
            DatabaseManager.CheerResult result = db.cheerFeed(absentFeedId, UUID.randomUUID());
            assertEquals(DatabaseManager.CheerResult.GONE, result,
                    "行确实不存在时必须仍是 GONE 喵（消费侧渲染 cheer-gone）。\n"
                            + "改成 FAILED 属于过度修复：玩家对一条已被保留策略清理的旧动态会收到"
                            + "「鼓励失败，请稍后再试」，怎么重试都不会成功，而且日志里没有真故障。");
        }

        /**
         * <b>本条的核心</b>：证明「写失败」与「读侧健康」在真实故障下<b>同时</b>成立。
         *
         * <p>这是对 round5 判别式的直接证伪：判别式是
         * {@code result == GONE && loadCheerers().readFailed()}。只要存在一种故障形态使
         * {@code readFailed == false} 而写确实是失败的，判别式就必然把它误判为
         * 「动态已消失」。本用例逐个故障形态把这两个值一起打出来。
         *
         * <p>三种形态（全部落在「写失败 + 读成功」区间）：
         * <ol>
         *   <li>WAL 写锁竞争 → {@code SQLITE_BUSY}（异步保存线程与管理员备份撞锁）；</li>
         *   <li>{@code PRAGMA query_only=ON} → {@code SQLITE_READONLY}（只读挂载 /
         *       MySQL {@code read_only=1} 同形）；</li>
         *   <li>存储层拒绝写入 —— {@code BEFORE INSERT ... RAISE(ABORT)} 触发器
         *       （权限不足 / DBA 装的写入闸 / MySQL 只读账号的同形物）。</li>
         * </ol>
         *
         * <p>红队变异：任一形态下若 {@code cheerFeed} 返回 GONE，本用例的第二个断言就会红。
         */
        @Test
        @DisplayName("三种「写失败 + 读成功」形态：写侧均报 FAILED，而读侧同一时刻仍健康")
        void writeSideFaultIsNotInferredFromReadSide() throws Exception {
            // ---- 形态 1：WAL 写锁竞争（SQLITE_BUSY）
            try (Connection blocker = DriverManager.getConnection(url)) {
                blocker.setAutoCommit(false);
                try (Statement st = blocker.createStatement()) {
                    st.executeUpdate("INSERT INTO " + PREFIX + "player_data (uuid, player_name)"
                            + " VALUES ('locker-busy', 'locker-busy')");
                }
                assertWriteFailedButReadHealthy("WAL 写锁竞争（SQLITE_BUSY）");
                blocker.rollback();
            }

            // ---- 形态 2：PRAGMA query_only=ON（SQLITE_READONLY）
            execOnDb("PRAGMA query_only=ON");
            try {
                assertWriteFailedButReadHealthy("PRAGMA query_only=ON（SQLITE_READONLY）");
            } finally {
                // 必须恢复，否则同 JVM 内后续检查（含本用例的收尾断言）全部被污染。
                execOnDb("PRAGMA query_only=OFF");
            }
            assertFalse(db.loadCheerers(feedId, 1).readFailed(),
                    "query_only 撤销后读侧应恢复正常喵 —— 若仍失败，说明测试自身把库弄坏了，"
                            + "后续断言不可信。");

            // ---- 形态 3：存储层拒绝写入（BEFORE INSERT 触发器 RAISE(ABORT)）
            execOnDb("CREATE TRIGGER IF NOT EXISTS test_reject_cheer BEFORE INSERT ON "
                    + PREFIX + "social_cheers BEGIN SELECT RAISE(ABORT, 'insert not permitted'); END");
            try {
                assertWriteFailedButReadHealthy("存储层拒绝写入（RAISE(ABORT)，权限/只读闸同形）");
            } finally {
                execOnDb("DROP TRIGGER IF EXISTS test_reject_cheer");
            }
        }

        /**
         * 单一断言点：写侧报 {@code FAILED}，且<b>同一时刻</b>读侧 {@code readFailed == false}。
         *
         * <p>把两者放在一个方法里断言，是为了让失败消息能同时呈现两个值 —— 这样
         * 「判别式为什么会误判」在未来复现时不需要二次调试。
         */
        private void assertWriteFailedButReadHealthy(String form) {
            DatabaseManager.CheerResult result = db.cheerFeed(feedId, UUID.randomUUID());
            boolean readFailed = db.loadCheerers(feedId, 1).readFailed();

            assertEquals(DatabaseManager.CheerResult.FAILED, result,
                    "[" + form + "] cheerFeed 未返回 FAILED（实际 " + result + "）喵。\n"
                            + "这是本缺陷的根因：SQLException 被折叠成 GONE，消费侧渲染成"
                            + " square.feed.cheer-gone「这条动态已经翻篇了」—— 写库故障被谎报成业务事实。");
            assertFalse(readFailed,
                    "[" + form + "] 读侧必须仍然健康才构成「写失败 + 读成功」形态（readFailed="
                            + readFailed + "）喵。\n"
                            + "若读侧也失败了，本形态就退化成「整体宕机」，证明不了 round5 判别式"
                            + "（GONE && readFailed）的方向性错误。检查注入手段是否真的只挡写。");
        }

        /** 造一条 feed 行，返回它的 id。 */
        private long seedFeed() throws SQLException {
            synchronized (db.getConnectionLock()) {
                Connection conn = db.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + PREFIX + "social_feed (player_uuid, feed_date, event_type,"
                                + " events_count, sum_correct, sum_total, last_mode, last_streak,"
                                + " achievement_id, cheer_count, created_at, updated_at)"
                                + " VALUES (?, '2026-11-03', 'SESSION_DONE', 1, 1, 1, 'normal', 0,"
                                + " NULL, 0, 1, 1)")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.executeUpdate();
                }
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT MAX(id) FROM " + PREFIX + "social_feed")) {
                    assertTrue(rs.next(), "前置条件不成立：social_feed 里应有刚插入的行");
                    long id = rs.getLong(1);
                    assertTrue(id > 0, "前置条件不成立：feed id 应有效（实际 " + id + "）");
                    return id;
                }
            }
        }

        private String scalar(String sql) throws SQLException {
            synchronized (db.getConnectionLock()) {
                try (Statement st = db.getConnection().createStatement();
                     ResultSet rs = st.executeQuery(sql)) {
                    return rs.next() ? rs.getString(1) : "<no row>";
                }
            }
        }

        private void execOnDb(String sql) throws SQLException {
            synchronized (db.getConnectionLock()) {
                try (Statement st = db.getConnection().createStatement()) {
                    st.execute(sql);
                }
            }
        }
    }

    // ==================================================================
    // 消费侧：防抖戳回收 + 无额外读往返
    //
    // 这两条是<b>源码级 lint</b>（不是行为测试）。理由：SocialManager.cheer 的写库段
    // 整段跑在 runAsync 里（Bukkit 调度器），纯 JVM 下单测里派发不出去；而它们要封死的
    // 变异恰是「代码形态」层面的 —— 「回收条件里又混进了读侧信号」「判别式那次多余查询
    // 回来了」。行为测试即使能跑也看不见「多打了一次查询」这种退化。
    //
    // 与 GuiWiringLintsTest / B3WiringLintsTest 的关系：本类<b>零复用</b>，理由同那两类的
    // 类注释 —— 复用会让一处的容错调宽静默污染另一处。这里只需要「剥注释 + 取方法体」，
    // 因此实现比那两类小得多。
    // ==================================================================

    @Nested
    @DisplayName("消费侧 lint —— 防抖戳回收不依赖读侧，且不再有判别式读往返")
    class ConsumerSide {

        /** 源码根：{@code com/letslearnenglish/minecraftplugin}。 */
        private java.nio.file.Path socialManagerPath() {
            java.nio.file.Path p = java.nio.file.Paths.get(System.getProperty("user.dir"),
                    "src", "main", "java", "com", "letslearnenglish", "minecraftplugin",
                    "core", "social", "SocialManager.java");
            if (!java.nio.file.Files.isRegularFile(p)) {
                throw new AssertionError("找不到 " + p.toAbsolutePath()
                        + " 喵 —— 本 lint 须从仓库根目录运行（mvn test 默认满足）。");
            }
            return p;
        }

        private String socialManagerSource() {
            try {
                return stripComments(java.nio.file.Files.readString(socialManagerPath()));
            } catch (java.io.IOException e) {
                throw new AssertionError("读取失败喵：" + e.getMessage(), e);
            }
        }

        private String socialManagerRaw() {
            try {
                return java.nio.file.Files.readString(socialManagerPath());
            } catch (java.io.IOException e) {
                throw new AssertionError("读取失败喵：" + e.getMessage(), e);
            }
        }

        /**
         * 防抖戳回收：{@code cheerDebounceAt.remove(...)} 必须只在 {@code FAILED} 上触发，
         * <b>不得</b>再出现任何 {@code readFailed} 形态的判别输入。
         *
         * <p>红队变异：把回收条件改回 round5 的
         * {@code result == GONE && loadCheerers(...).readFailed()}。那样在「写失败 + 读成功」
         * 下防抖戳不回收，玩家 750ms 内重试被静默吞掉 —— 而主导故障模式正是这一种。
         */
        @Test
        @DisplayName("L1：防抖戳回收只看 CheerResult.FAILED，回收条件里没有读侧信号")
        void debounceStampClearedForEveryFailureForm() {
            String body = methodBody(socialManagerSource(), "public void cheer(Player cheerer, long feedId, Runnable onSuccess)");

            java.util.List<Integer> removes = occurrences(body, "cheerDebounceAt.remove(");
            assertFalse(removes.isEmpty(),
                    "cheer(...) 里找不到 cheerDebounceAt.remove(...) 喵。\n"
                            + "失败时必须回收防抖戳，否则玩家在上一次失败后的 750ms 内重试会被"
                            + "静默吞掉（:379-381 的窗口判断在 :382 put 之前无条件生效），"
                            + "而写库故障的主路径下这个窗口恰恰是最需要重试的时刻。");
            assertEquals(1, removes.size(),
                    "cheer(...) 里 cheerDebounceAt.remove(...) 出现 " + removes.size()
                            + " 次（偏移=" + removes + "），期望恰好 1 次 喵。\n"
                            + "两处以上说明有人把回收逻辑复制到另一分支（例如 GONE 一份、FAILED 一份）；"
                            + "语义上『失败形态 → 回收戳』只该有一个统一处置点，分散后必然漂移。");

            assertFalse(body.contains("readFailed"),
                    "cheer(...) 里仍出现 readFailed 喵 —— 这是 round5 的代理判别式残留"
                            + "（用读侧健康度推断写侧故障）。\n"
                            + "WAL 下「写失败 + 读成功」是主路径，该判别式方向相反："
                            + "四种故障形态里有三种会得到 readFailed=false，于是防抖戳不回收、"
                            + "提示仍是 cheer-gone。\n"
                            + "正确形态：数据层自报 CheerResult.FAILED，回收条件只看它。");

            // 额外读往返：判别式每次 GONE 都多打一条 loadCheerers —— 纯退化，根因修复后应删净。
            java.util.List<Integer> cheererReads = occurrences(body, "loadCheerers(");
            assertTrue(cheererReads.isEmpty(),
                    "cheer(...) 里仍有 " + cheererReads.size() + " 处 loadCheerers(...) 调用（偏移="
                            + cheererReads + "），期望 0 处 喵。\n"
                            + "那是判别式引入的额外读往返：每次 GONE（含正常的『行已清理』）都多打一条查询。"
                            + "根因修掉后它没有任何用途 —— 写侧故障已由 CheerResult.FAILED 直接自报，"
                            + "不再需要任何读侧信号。\n"
                            + "若确有必要新增读查询，请先确认它不是为了推断写侧结果。");

            // 关键锚点：回收动作必须被「FAILED」这一个条件守住，而不是被别的值守住。
            // 只查「方法体里出现过 CheerResult.FAILED」抓不到下面这个变异：
            //   if (result == CheerResult.GONE) cheerDebounceAt.remove(cheererId);
            // FAILED 分支仍然存在（只是不再回收戳），方法体里照样能搜到 FAILED —— 假绿。
            // 因此这里回溯到包住 remove 的那条 if 的条件式，断言条件里认的是 FAILED。
            String guard = enclosingIfCondition(body, removes.get(0));
            assertTrue(guard != null,
                    "cheer(...) 里 cheerDebounceAt.remove(...) 不在任何 if 条件块内 喵"
                            + "（偏移=" + removes.get(0) + "）。\n"
                            + "无条件回收会让「成功」路径也丢掉防抖戳，宏连点将每次都能打库 —— "
                            + "原本 750ms 静默窗口要防的正是这件事。");
            assertTrue(guard.replaceAll("\\s+", "").contains("CheerResult.FAILED"),
                    "守着 cheerDebounceAt.remove(...) 的条件式里没有 CheerResult.FAILED 喵：\n"
                            + "    if (" + guard.strip() + ")\n"
                            + "常见变异：把条件从 FAILED 改回 GONE（或改成任何别的值）—— "
                            + "写失败时防抖戳不回收，玩家 750ms 内重试仍被静默吞掉。\n"
                            + "注意本 lint 读的是<b>包住 remove 的那条 if</b>，不是「方法体里有没有 FAILED」："
                            + "后者会被别处的 FAILED 分支满足（假绿）。");
        }

        /**
         * 结果分发必须有 {@code FAILED} 分支，且该分支用<b>专门的键</b>，
         * 不得复用 {@code cheer-gone}（业务事实）或 {@code daily.load-failed}（读语义）。
         *
         * <p>红队变异：把 {@code case FAILED} 删掉。Java 的 switch 表达式/语句在枚举上不做
         * 穷尽性强制（语句形态），于是新值会静默落在「无分支」上 —— 玩家点鼓励后<b>什么</b>
         * 提示都收不到，音效也没有，界面看起来像点击被吞了。
         */
        @Test
        @DisplayName("L2：handleCheerResult 有 FAILED 分支，且用 square.feed.cheer-failed 而非复用 GONE 文案")
        void failedBranchUsesItsOwnMessageKey() {
            // 用**原文**而不是剥过的文本：i18n 键是字符串字面量，stripComments 会把字面量内容
            // 抹成空格，在那里查键名永远是空答复（假绿）。偏移在两种文本上一一对应，所以先用
            // 剥过的文本求方法体区间，再用同一对偏移去切原文。
            String stripped = socialManagerSource();
            String raw = socialManagerRaw();
            String signature = "private void handleCheerResult(Player cheerer, UUID cheererId, String cheererName,";

            int sigIndex = stripped.indexOf(signature);
            if (sigIndex < 0) {
                throw new AssertionError("源码里找不到 handleCheerResult 的签名喵 —— 方法可能被改名/"
                        + "改签名，请同步本 lint（否则门禁会静默失效）。");
            }
            int bodyStart = stripped.indexOf('{', sigIndex + signature.length());
            int depth = 0;
            int bodyEnd = -1;
            for (int k = bodyStart; k < stripped.length(); k++) {
                char ch = stripped.charAt(k);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        bodyEnd = k;
                        break;
                    }
                }
            }
            if (bodyEnd < 0) {
                throw new AssertionError("handleCheerResult 的花括号没有配对结束喵。");
            }
            String body = raw.substring(bodyStart + 1, bodyEnd);

            assertTrue(body.contains("case FAILED"),
                    "handleCheerResult 里没有 case FAILED 喵。\n"
                            + "CheerResult 新增第四态后，任何 switch 语句都不会被编译器强制覆盖新值；"
                            + "缺这一支 ⇒ 写库失败时玩家收不到任何反馈（无文案、无音效），"
                            + "看起来就是「点了没反应」，比谎报更难排查。");

            assertTrue(body.contains("\"square.feed.cheer-failed\""),
                    "case FAILED 分支没有使用 square.feed.cheer-failed 喵。\n"
                            + "复用 square.feed.cheer-gone 会把写故障重新谎报成业务事实（本条缺陷原文）；"
                            + "复用 daily.load-failed 则语义指向「界面数据读失败」，与写侧故障不符，"
                            + "玩家按提示去重开界面也不会好转。");

            // GONE 分支必须保留自己的文案：防「一律报失败」的过度修复。
            assertTrue(body.contains("\"square.feed.cheer-gone\""),
                    "handleCheerResult 里找不到 square.feed.cheer-gone 喵。\n"
                            + "真正的 GONE（行确实不存在）必须仍渲染「这条动态已经翻篇了」—— "
                            + "把它一并改成失败提示是从一个谎报滑到另一个谎报：玩家对一条已被保留策略"
                            + "清理的旧动态会收到「请稍后再试」，怎么重试都不会成功。");
        }

        /**
         * 新键必须<b>真的存在于两份语言文件里</b>，且中英对称。
         *
         * <p>为什么这条不能省：{@code SocialManager} 里写的是字面量
         * {@code "square.feed.cheer-failed"}，编译器对它一无所知。键漏了的话
         * {@code MessageUtil} 只回一句 {@code Missing message: ...}，玩家看到的是
         * {@code Missing message: square.feed.cheer-failed} —— 也就是说「写库失败」这个本该
         * 明确告知的状态，退化成了另一句玩家看不懂的内部串。而这类缺失在纯 mock 的行为测试里
         * 完全不可见（mock 的 {@code getPlayerMessage} 直接吞掉 key）。
         *
         * <p>缩进陷阱：写成顶层 {@code square.feed.cheer-failed} 却缩在别的段内部时，YAML 会
         * 解析成 {@code <该段>.square.feed.cheer-failed}，点路径查不到 —— 因此这里按
         * <b>点路径</b>解析而不是 grep 文本。
         */
        @Test
        @DisplayName("L3：square.feed.cheer-failed 在 zh/en 两份语言文件里都真实存在且带 {prefix}")
        void failedMessageKeyExistsInBothLanguages() {
            for (String lang : new String[] {"zh", "en"}) {
                java.nio.file.Path p = java.nio.file.Paths.get(System.getProperty("user.dir"),
                        "src", "main", "resources", "messages", lang + ".yml");
                if (!java.nio.file.Files.isRegularFile(p)) {
                    throw new AssertionError("找不到语言文件 " + p.toAbsolutePath() + " 喵。");
                }
                org.bukkit.configuration.file.YamlConfiguration yml =
                        new org.bukkit.configuration.file.YamlConfiguration();
                try {
                    // 刻意用 loadFromString 而不是 loadConfiguration(File)：后者解析失败时会走
                    // Bukkit 的 logger，纯 JVM 下 Bukkit.server 为 null → NPE，于是「YAML 写坏了」
                    // 会表现成 NullPointerException 而不是可读的断言失败。
                    yml.loadFromString(java.nio.file.Files.readString(
                            p, java.nio.charset.StandardCharsets.UTF_8));
                } catch (org.bukkit.configuration.InvalidConfigurationException
                         | java.io.IOException | RuntimeException e) {
                    throw new AssertionError("解析 " + p.getFileName() + " 失败喵"
                            + "（YAML 语法/缩进错误）：" + e.getMessage(), e);
                }

                String value = yml.getString("square.feed.cheer-failed");
                assertTrue(value != null && !value.isBlank(),
                        lang + ".yml 里按点路径取不到 square.feed.cheer-failed 喵。\n"
                                + "常见原因：键写在别的段内部 —— YAML 会把它解析成"
                                + " `<该段>.square.feed.cheer-failed`，而代码按 "
                                + "square.feed.cheer-failed 查，玩家看到的是"
                                + " `Missing message: square.feed.cheer-failed`。\n"
                                + "缩进必须与同段的 cheer-gone / cheer-already 完全一致。");
                assertTrue(value.contains("{prefix}"),
                        lang + " 的 square.feed.cheer-failed 里没有 {prefix} 占位符喵 —— "
                                + "与同段其余 cheer-* 文案风格不一致（那些都带前缀）。");
            }
        }

        // ---------------- 剥注释 + 取方法体（本类独立实现） ----------------

        /**
         * 剥掉注释并把字符串/字符字面量内容抹成空格（保留换行，偏移与原文一致）。
         *
         * <p>必须抹字面量：{@code SocialManager} 的注释里出现过 {@code readFailed} /
         * {@code loadCheerers} 的说明文字，不抹会在「已删掉代码」时假红。
         */
        private String stripComments(String source) {
            int n = source.length();
            char[] out = source.toCharArray();
            int i = 0;
            while (i < n) {
                char c = source.charAt(i);
                if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                    int j = i;
                    while (j < n && source.charAt(j) != '\n') {
                        j++;
                    }
                    blank(out, i, j);
                    i = j;
                    continue;
                }
                if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                    int j = i + 2;
                    while (j < n && !(source.charAt(j) == '*' && j + 1 < n
                            && source.charAt(j + 1) == '/')) {
                        j++;
                    }
                    j = (j < n) ? Math.min(n, j + 2) : n;
                    blank(out, i, j);
                    i = j;
                    continue;
                }
                if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"'
                        && source.charAt(i + 2) == '"') {
                    int j = i + 3;
                    while (j + 2 < n && !(source.charAt(j) == '"' && source.charAt(j + 1) == '"'
                            && source.charAt(j + 2) == '"')) {
                        if (source.charAt(j) == '\\') {
                            j++;
                        }
                        j++;
                    }
                    j = (j + 2 < n) ? j + 3 : n;
                    blank(out, i, j);
                    i = j;
                    continue;
                }
                if (c == '"') {
                    int j = i + 1;
                    while (j < n) {
                        char d = source.charAt(j);
                        if (d == '\\') {
                            j += 2;
                            continue;
                        }
                        if (d == '"' || d == '\n') {
                            j++;
                            break;
                        }
                        j++;
                    }
                    j = Math.min(j, n);
                    blank(out, i, j);
                    i = j;
                    continue;
                }
                if (c == '\'') {
                    int j = i + 1;
                    while (j < n) {
                        char d = source.charAt(j);
                        if (d == '\\') {
                            j += 2;
                            continue;
                        }
                        if (d == '\'' || d == '\n') {
                            j++;
                            break;
                        }
                        j++;
                    }
                    j = Math.min(j, n);
                    blank(out, i, j);
                    i = j;
                    continue;
                }
                i++;
            }
            return new String(out);
        }

        private void blank(char[] out, int from, int to) {
            int end = Math.min(to, out.length);
            for (int k = Math.max(0, from); k < end; k++) {
                if (out[k] != '\n' && out[k] != '\r') {
                    out[k] = ' ';
                }
            }
        }

        /**
         * 按方法签名截出方法体。签名必须唯一出现（否则 {@code fail}）—— 一旦需要
         * 「第 N 个匹配」这种逃生口，说明锚点已不可信。
         */
        private String methodBody(String strippedSource, String signature) {
            int sigIndex = strippedSource.indexOf(signature);
            if (sigIndex < 0) {
                throw new AssertionError("源码里找不到方法签名「" + signature + "」喵 —— "
                        + "方法可能被改名/改签名，请同步本 lint（否则门禁会静默失效）。");
            }
            if (strippedSource.indexOf(signature, sigIndex + 1) >= 0) {
                throw new AssertionError("方法签名「" + signature + "」出现多次，无法唯一定位喵。");
            }
            int bodyStart = strippedSource.indexOf('{', sigIndex + signature.length());
            if (bodyStart < 0) {
                throw new AssertionError("签名「" + signature + "」之后找不到左花括号喵。");
            }
            int depth = 0;
            for (int k = bodyStart; k < strippedSource.length(); k++) {
                char ch = strippedSource.charAt(k);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        return strippedSource.substring(bodyStart + 1, k);
                    }
                }
            }
            throw new AssertionError("签名「" + signature + "」的花括号没有配对结束喵。");
        }

        private java.util.List<Integer> occurrences(String haystack, String needle) {
            java.util.List<Integer> hits = new java.util.ArrayList<>();
            int from = 0;
            while (true) {
                int at = haystack.indexOf(needle, from);
                if (at < 0) {
                    break;
                }
                hits.add(at);
                from = at + needle.length();
            }
            return hits;
        }


        /**
         * 求<b>包住</b> {@code offset} 的最内层 {@code if (...)} 的条件式文本（不含括号）。
         *
         * <p>为什么需要它：只断言「方法体里出现过 {@code CheerResult.FAILED}」抓不到
         * 「把回收条件从 FAILED 改回 GONE」这个变异 —— FAILED 分支仍然在（只是不再回收戳），
         * 方法体里照样搜得到。必须回溯到真正守住建言的那条 if。
         *
         * @return 条件式；{@code offset} 不在任何 if 条件块内时返回 {@code null}
         */
        private String enclosingIfCondition(String body, int offset) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bif\\s*\\(").matcher(body);
            String best = null;
            int bestOpen = -1;
            while (m.find()) {
                int parenOpen = body.indexOf('(', m.start());
                int parenClose = matchingParen(body, parenOpen);
                if (parenClose < 0) {
                    continue;
                }
                int braceOpen = skipWhitespace(body, parenClose + 1);
                if (braceOpen >= body.length() || body.charAt(braceOpen) != '{') {
                    continue; // 单语句 if：没有块，不认（保守方向）
                }
                int braceClose = findBlockByBraceMatching(body, braceOpen);
                if (braceClose < 0) {
                    continue;
                }
                if (offset > braceOpen && offset < braceClose && braceOpen > bestOpen) {
                    best = body.substring(parenOpen + 1, parenClose);
                    bestOpen = braceOpen;
                }
            }
            return best;
        }

        private int matchingParen(String s, int openIdx) {
            if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '(') {
                return -1;
            }
            int depth = 0;
            for (int i = openIdx; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private int findBlockByBraceMatching(String s, int openBraceIdx) {
            if (openBraceIdx < 0 || openBraceIdx >= s.length() || s.charAt(openBraceIdx) != '{') {
                return -1;
            }
            int depth = 0;
            for (int i = openBraceIdx; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private int skipWhitespace(String s, int from) {
            int i = Math.max(0, from);
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return i;
        }
    }
}
