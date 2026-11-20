package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <b>R22-D2：{@code getCoinsEarned} 的「失败」与「确实是 0」在返回值上同形。</b>
 *
 * <h2>缺陷本体（已核实，非推测）</h2>
 * {@code DatabaseManager.getCoinsEarned}（{@code :2944-2957}）在 {@code SQLException} 分支
 * {@code return 0L}，与「这个玩家确实赚了 0 枚」<b>逐字节同形</b>。同一个 {@code 0L}
 * 至少承载三种输入：
 *
 * <ol>
 *   <li>{@code player_data} 无行（新玩家，确实没赚过）—— 语义上真的是 0；</li>
 *   <li>查询成功但列值为 0（赚过 0 枚）—— 语义上真的是 0；</li>
 *   <li><b>查询失败</b>（{@code SQLException} / 连接不可用）—— 语义上是<b>不知道</b>。</li>
 * </ol>
 *
 * <p>第 3 种与第 1/2 种在返回值上无法区分，这正是本仓已修过三次的那一类缺陷
 * （{@code SettlementClaim} / {@code LedgerClearResult} / {@code CheckInClaim}）。
 *
 * <h2>为什么它是缺陷而不是「刻意的降级」——下游写了相反的契约</h2>
 * {@code LLEPapiExpansion} 是<b>唯一</b>需要区分「未知」的调用方，而且它<b>已经把</b>
 * 三态建好了：{@code CoinsEntry.value} 是 {@code Long}，{@code null} = 未知
 * （{@code LLEPapiExpansion:160-169}），{@code COINS_UNKNOWN = ""} 是「未知」的展示
 * 形态（{@code :144}），{@code scheduleCoinsLoad} 的 javadoc（{@code :486-489}）逐字承诺
 * 「<b>绝不</b>把失败折成 0 写进缓存 —— 那会把「不知道」固化成「确实是 0」」。
 *
 * <p>但那段 {@code catch (RuntimeException)} <b>收不到</b> SQL 失败：{@code getCoinsEarned}
 * 在内部就把 {@code SQLException} 吃成 {@code 0L} <b>正常返回</b>了。于是
 * 「绝不折成 0」的代码是<b>死的</b>，三态在上游被静默压平，且失败值走的是
 * {@code COINS_CACHE_TTL_MILLIS = 60s}（"确认值"TTL）而不是
 * {@code COINS_RETRY_TTL_MILLIS = 15s}（退避）。<b>一次 DB 抖动 = 记分板对本人显示
 * 可信的假 0，持续 60 秒。</b>
 *
 * <h2>本类钉什么、不钉什么</h2>
 * <p><b>不</b>钉「现状是对的」。按本仓 {@code CoinLedgerRoundingTest:35-38} 与
 * {@code DailyCapCrossServerTest} 的约定：缺陷<b>不</b>写成「现状 = 正确」的绿测试
 * （那会把已知偏差固化成契约，将来想修的人得先推翻一条测试）。
 *
 * <p>所以本类分成两半：
 * <ul>
 *   <li><b>{@link CollapseInRealDatabase}（绿，真 SQLite）</b> —— 钉的是<b>仍然成立、
 *       且任何修法都不该破坏</b>的事实：三种输入在当前 API 上确实塌成同一个 {@code 0L}。
 *       这是缺陷存在的<b>证据</b>（不是对它的认可）。修法 B 落地后本条仍须绿 ——
 *       {@code getCoinsEarned} 作为退化视图本来就该继续返回 0。</li>
 *   <li><b>{@link RiskDirectionLints}（结构 lint）</b> —— 钉住我给出的定级依据：
 *       「0 不会误撤销成就」这条<b>安全性论断</b>必须有守卫，否则将来某次重构加了撤销
 *       路径，定级就该从 MEDIUM 升上去，而没人会想起这条报告。</li>
 * </ul>
 *
 * <h2>为什么失败路径用真库而不是 mock</h2>
 * <p>{@code db.shutdown()} 之后 {@code getConnection()} 返回 EH-01 的 {@code UNAVAILABLE}
 * 代理，其 {@code prepareStatement} <b>真的抛 SQLException</b>。这是一条不打桩、不反射、
 * 走生产代码的失败注入 —— 比 {@code thenThrow} 强：它证明「失败 → 0」的折叠发生在
 * {@code getCoinsEarned} 的真实控制流里，而不是我 mock 出来的。
 */
@DisplayName("R22-D2 — getCoinsEarned 的「失败」与「确实是 0」同形")
class CoinsEarnedFailureDirectionTest {

    private static final String PREFIX = "lle_";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000c025d2");

    private static LetsLearnEnglish plugin;
    private static ConfigManager configManager;

    private DatabaseManager db;

    @BeforeAll
    static void createMocks() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        Logger quiet = Logger.getLogger("CoinsEarnedFailureDirectionTest");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(java.util.logging.Level.OFF);
        when(plugin.getLogger()).thenReturn(quiet);
    }

    @BeforeEach
    void initRealDatabase(@TempDir Path dir) {
        FileConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.sqlite.filename", "coins-earned-failure.db");
        config.set("database.mysql.table-prefix", PREFIX);
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则本类的真库断言无意义");
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // 1. 真库证据：三种输入 → 同一个 0L
    // ==================================================================

    @Nested
    @DisplayName("真 SQLite —— 三种不同语义塌成同一个 0L")
    class CollapseInRealDatabase {

        /**
         * 三种输入逐个读，断言它们<b>逐字节相同</b>。
         *
         * <p>这不是三条独立断言，而是<b>一次塌陷的证明</b>：只要有一条与其它两条不同，
         * 缺陷就不存在（说明 API 已经能区分）。所以最后统一比较，失败消息直接指出
         * 「API 已能区分，请更新 R22-D2 报告」。
         */
        @Test
        @DisplayName("G1：无行 / 赚过 0 枚 / 读失败 —— 三者返回值全等")
        void threeInputsCollapseToOneValue() {
            // ① 无行：全新的 UUID，player_data 里没有任何记录。
            UUID neverPlayed = UUID.fromString("00000000-0000-0000-0000-00000000ba5e");
            long noRow = db.getCoinsEarned(neverPlayed);
            assertEquals(0L, noRow, "无行必须是 0 —— 这一态语义上确实是「没赚过」");

            // ② 查询成功、列值为 0：行存在且列值为 0。
            //    ⚠ 不能用 recordCoinsEarned(p, 0.0) 建行 —— adjustCoins 对 amount <= 0
            //    是「无需写入」早退（:2923 返回 true，**不执行任何 SQL**），行不会被建出来，
            //    那样 ② 与 ① 其实是同一条路径（都走 rs.next()==false），证明力为零。
            //    这里显式 INSERT 一行 total_coins_earned = 0，让「查询成功且值为 0」
            //    走 rs.next()==true 的分支。
            //    注意必须持 getConnectionLock()：DatabaseManager 的调用约定是
            //    「取连接与语句执行整体在同锁内」（见 getConnection() 的 E-04 javadoc），
            //    锁外裸用连接在并发重连时会把 Statement 打在已关闭的旧连接上。
            synchronized (db.getConnectionLock()) {
                try (java.sql.PreparedStatement stmt = db.getConnection().prepareStatement(
                        "INSERT INTO " + PREFIX + "player_data (uuid, total_coins_earned, updated_at)"
                                + " VALUES (?, 0, ?)")) {
                    stmt.setString(1, PLAYER.toString());
                    stmt.setLong(2, System.currentTimeMillis());
                    stmt.executeUpdate();
                } catch (java.sql.SQLException e) {
                    fail("夹具失败：无法插入 total_coins_earned = 0 的行：" + e.getMessage());
                }
            }
            long confirmedZero = db.getCoinsEarned(PLAYER);
            assertEquals(0L, confirmedZero, "确认的 0 必须是 0（本轮走的是 rs.next()==true）");

            // ③ 读失败：关库后 getConnection() 返回 EH-01 的 UNAVAILABLE 代理，
            //    其 prepareStatement 抛真实 SQLException → catch → return 0L。
            db.shutdown();
            long readFailed = db.getCoinsEarned(PLAYER);

            assertEquals(0L, readFailed,
                    "关库后 getCoinsEarned 必须仍返回 0 —— 这就是缺陷：失败被折叠成 0。"
                            + "若这里变成了抛异常，说明 R22-D2 已被修掉，请同步更新本断言与报告。");

            assertTrue(noRow == confirmedZero && confirmedZero == readFailed,
                    "R22-D2 的缺陷本体：三种不同语义在 getCoinsEarned 的返回值上不可区分"
                            + "（无行=" + noRow + " / 确认的 0=" + confirmedZero
                            + " / 读失败=" + readFailed + "）。"
                            + "\n若三者已能区分，说明下游读面已加了三态版本 —— 请把本类改成"
                            + "断言「读失败 ≠ 确认的 0」，并关闭 R22-D2。");
        }

        /**
         * 读失败必须<b>留日志</b>，否则「服主无从知道是库的问题」这句定级依据就不成立。
         *
         * <p>这条是<b>反向</b>的：它证明失败不是静默的，因此 R22-D2 的严重度低于
         * 「静默伪造数据」那一类。它同时防止将来有人为「降噪」把这行 WARNING 删掉 ——
         * 删了之后定级就真的该升上去，而这条断言会提醒他先来读这段 javadoc。
         */
        @Test
        @DisplayName("G2：读失败有 WARNING 日志（定级 MEDIUM 而非 HIGH 的依据之一）")
        void readFailureIsLogged() throws IOException {
            String src = stripComments(Files.readString(
                    Paths.get(DATA_MANAGER), StandardCharsets.UTF_8));

            // 刻意<b>不</b>用 methodBody("public long getCoinsEarned(") 定位：
            // 方案 B 落地后该方法退化成 one-liner，日志会随实现搬到
            // getCoinsEarnedWithStatus 里，按方法体定位会使本条<b>假红</b>。
            // 这里钉的是「这条日志语义还在」，与它在哪个方法内无关。
            // 注意日志文案是 "Failed to query coins for " + playerId —— 字符串里
            // "for" 后面<b>带一个空格</b>，故匹配时不给收尾引号，否则恒不命中（自证桩）。
            assertTrue(Pattern.compile(
                            "Level\\.WARNING\\s*,\\s*\"Failed to query coins for")
                            .matcher(src).find(),
                    "DatabaseManager 里不再有「Failed to query coins for」的 WARNING 日志了。"
                            + "R22-D2 定级 MEDIUM 的前提之一是「失败有日志信号，服主能自己查」；"
                            + "删掉这行等于让失败变静默，那时应重新评估定级（MEDIUM → HIGH）。");
        }
    }

    // ==================================================================
    // 2. 风险方向 lint：0 会不会「误撤销」成就？
    // ==================================================================

    /**
     * <b>定级 MEDIUM 的核心安全性论断必须有守卫。</b>
     *
     * <p>论断是：{@code AchievementManager:503} 把 {@code totalCoins == 0} 读成「未达阈值
     * → 本次不解锁」，方向 fail-closed，<b>不会</b>误撤销已解锁的成就；库恢复后下次
     * 会话结算会重新判定，已达成者不丢。<b>因此最坏后果是「解锁延后」而不是「撤销」。</b>
     *
     * <p>这个论断如果哪天不成立了（有人给成就加了撤销 / 回刷路径），R22-D2 的定级就
     * <b>必须</b>往上调 —— 因为那时「一次读失败」会真的把玩家已获得的成就打掉。
     * 没有任何运行时测试会覆盖这种「将来的编辑」，所以在这里用结构 lint 钉住。
     */
    @Nested
    @DisplayName("风险方向 —— 0 不得导致成就被撤销")
    class RiskDirectionLints {

        /**
         * 内存态里不得存在「按当前数据回撤单条成就」的路径。
         *
         * <p>只允许 {@code unlockedCache.remove(playerId)} / {@code unlockedAtCache.remove(playerId)}
         * 这类<b>整玩家缓存卸载</b>（quit / reload / reset 时清内存，真值仍在库里，下次
         * {@code loadPlayerAchievements} 回填）。禁止的是对<b>单条成就</b>的移除
         * （{@code unlocked.remove(achievement.getId())}）。
         *
         * <p><b>写侧</b>（数据库层面能不能删单条）由 {@link #repositoryHasNoDeleteWriteFace()}
         * 在方法名层面守 —— SQL 都是字符串字面量，本方法经 {@code stripCommentsAndLiterals}
         * 后匹配不到，硬在这里查会变成恒真自证桩。
         */
        @Test
        @DisplayName("L1：没有「按余额回撤成就」的路径（撤销只发生在 reset 的整玩家删除）")
        void noAchievementRevocationPath() throws IOException {
            List<String> offenders = new ArrayList<>();

            for (Path file : javaSources()) {
                String stripped = stripCommentsAndLiterals(
                        Files.readString(file, StandardCharsets.UTF_8));
                int lineNo = 0;
                for (String line : stripped.split("\n", -1)) {
                    lineNo++;
                    if (REMOVE_SINGLE_UNLOCK.matcher(line).find()) {
                        offenders.add(relativize(file) + ":" + lineNo
                                + " :: unlocked.remove( → " + line.trim());
                    }
                }
            }

            assertTrue(offenders.isEmpty(),
                    "出现了「从已解锁集合里移除单条成就」的路径：\n" + String.join("\n", offenders)
                            + "\n\nR22-D2 的定级（MEDIUM）建立在「0 只导致不解锁、绝不撤销」这一"
                            + "安全性论断上（见 AchievementManager.tryUnlock 的 R3 判定顺序："
                            + "unlocked.contains 短路在前，且全类无 unlocked.remove）。"
                            + "一旦有了按余额移除单条成就的路径，一次 DB 读失败就会被读成"
                            + "「未达标」并真的打掉玩家已解锁的成就 —— 那时定级必须上调，"
                            + "getCoinsEarned 的三态化不再是「可选优化」而是必须项。");
        }

        /**
         * 写侧也没有「删除单条成就」的能力 —— 光查内存态不够。
         *
         * <p>若仓储提供 {@code deleteUnlock(uuid, achievementId)}，即使当前没人调用，
         * 下一个人要「顺手回刷成就」时就会用它。所以断言 {@code AchievementRepository}
         * 的公开方法里<b>没有</b> delete/remove 语义的方法。这是本仓
         * {@code TwoStateLegacyLintTest} 确立的「防将来的某一次编辑」范畴。
         */
        @Test
        @DisplayName("L1b：AchievementRepository 不提供删除单条成就的写面")
        void repositoryHasNoDeleteWriteFace() throws IOException {
            String src = stripCommentsAndLiterals(Files.readString(
                    Paths.get(ACHIEVEMENT_REPOSITORY), StandardCharsets.UTF_8));

            List<String> deleters = new ArrayList<>();
            Matcher m = DELETE_WRITE_FACE.matcher(src);
            while (m.find()) {
                deleters.add(m.group(1));
            }

            assertTrue(deleters.isEmpty(),
                    "AchievementRepository 出现了删除/清空语义的公开方法：" + deleters
                            + "\n该仓储应当只有「读已解锁集」与「UPSERT 解锁」两个写面。"
                            + "新增删除能力会让 R22-D2 的风险方向从 fail-closed 翻成"
                            + "「读失败可能撤销成就」—— 定级必须上调。");
        }

        /**
         * L1b 的防空转自检。
         *
         * <p>L1b 在真实文件上期望<b>零命中</b> —— 一条只会零命中的断言等价于没有断言。
         * 这里用一个合成的「确实有删除方法」的仓储片段证明正则真的能命中；
         * 再用现状里的真实方法名证明它不会误伤 {@code findUnlocked} / {@code upsertUnlock}。
         */
        @Test
        @DisplayName("L1b 自检：命中 deleteUnlock / removeUnlock，放过 findUnlocked / upsertUnlock")
        void deleteWriteFaceDetectorSelfCheck() {
            assertTrue(DELETE_WRITE_FACE.matcher(
                            "    public boolean deleteUnlock(UUID playerId, String achievementId) {")
                            .find(),
                    "L1b 的判定函数已失效（恒不命中）—— 「仓储无删除写面」这条守卫等于没写");
            assertTrue(DELETE_WRITE_FACE.matcher(
                            "    public int removeAchievements(UUID playerId) {").find(),
                    "L1b 的判定函数漏掉了 remove* 形态");
            assertFalse(DELETE_WRITE_FACE.matcher(
                            "    public Map<String, Long> findUnlocked(UUID playerId) {").find(),
                    "L1b 把 findUnlocked 判成了删除写面 —— 误报会让人删掉整条 lint");
            assertFalse(DELETE_WRITE_FACE.matcher(
                            "    public boolean upsertUnlock(UUID playerId, String id, long at) {")
                            .find(),
                    "L1b 把 upsertUnlock 判成了删除写面 —— 它是唯一的合法写面，必须放过");
        }

        /** 判定函数自检（防空转）：合成源码两个方向都必须正确。 */
        @Test
        @DisplayName("L1 自检：合成源码上命中撤销、放过缓存卸载")
        void revocationDetectorSelfCheck() {
            String bad = String.join("\n",
                    "if (totalCoins < 500) {",
                    "    unlocked.remove(Achievement.COINS_500.getId());",
                    "}");
            assertTrue(hasRevocation(bad),
                    "撤销判定函数已失效（恒不命中），L1 等于没写");

            String ok = String.join("\n",
                    "public void unloadPlayer(UUID playerId) {",
                    "    flushPendingUnlockWrites(playerId);",
                    "    unlockedCache.remove(playerId);",
                    "    unlockedAtCache.remove(playerId);",
                    "}");
            assertFalse(hasRevocation(ok),
                    "撤销判定把<b>整玩家缓存卸载</b>判成了撤销。unloadPlayer 清的是内存缓存，"
                            + "真值仍在库里且下次会回填 —— 误报会让人被迫删掉整条 lint。"
                            + "这也是本类必须真的跑一遍自检的理由：真实的 AchievementManager"
                            + ":735 就有 unlockedCache.remove(playerId)，正则差一个字符就恒红。");
        }

        /**
         * 成就判定的失败方向必须是「不解锁」：{@code :503-505} 只能有
         * {@code if (>=) tryUnlock(...)} 形态，<b>不得</b>出现 else 分支里的惩罚动作。
         */
        @Test
        @DisplayName("L2：AchievementManager 的 COINS 判定只有「>= 则解锁」，无 else 惩罚")
        void coinsAchievementCheckHasNoElsePenalty() throws IOException {
            String src = stripCommentsAndLiterals(Files.readString(
                    Paths.get(ACHIEVEMENT_MANAGER), StandardCharsets.UTF_8));

            int idx = src.indexOf("getCoinsEarned(playerId)");
            if (idx < 0) {
                fail("AchievementManager 里找不到 getCoinsEarned(playerId) —— 判定点被搬走了，"
                        + "请同步本 lint 与 R22-D2 报告里的行号引用");
            }
            // 取判定点之后的一小段（COINS_500 / COINS_5000 两行 + 余量）
            String after = src.substring(idx, Math.min(src.length(), idx + 400));
            assertTrue(after.contains("COINS_500"),
                    "getCoinsEarned 之后 400 字内看不到 COINS_500 —— 判定结构变了，请核对");

            assertFalse(Pattern.compile("else\\s*\\{[^}]{0,200}(remove|delete|revoke)",
                            Pattern.CASE_INSENSITIVE).matcher(after).find(),
                    "COINS 判定出现了 else 分支里的移除/删除动作。0 在判定路径上的唯一合法"
                            + "处置是「不满足阈值 → 本次不解锁」（fail-closed）；"
                            + "任何 else 里的惩罚都会把「读失败」变成「撤销」。\n片段：\n" + after);
        }
    }

    // ==================================================================
    // 3. 下游契约 lint：三态是刻意的，不是巧合
    // ==================================================================

    /**
     * {@code LLEPapiExpansion} 的三态契约与 60s 窗口 —— 缺陷的<b>放大系数</b>在这里。
     *
     * <p>钉两件事，因为它们是「一次抖动 = 60 秒错值」这个量化结论的直接来源：
     * <ol>
     *   <li>{@code CoinsEntry.value} 必须是 {@code Long}（可为 null = 未知）——
     *       若哪天被改成基本类型 {@code long}，三态就<b>物理上</b>没了，
     *       本报告「三态被压平」的描述也随之失效，必须重写；</li>
     *   <li>60s / 15s 两个常量值 —— 失败值走哪个 TTL 决定了错值持续多久。</li>
     * </ol>
     */
    @Nested
    @DisplayName("下游契约 —— CoinsEntry 三态 + 60s 放大窗口")
    class DownstreamContractLints {

        @Test
        @DisplayName("L3：CoinsEntry.value 是 Long（三态仍存在），且两个 TTL 未被改动")
        void coinsEntryIsTriStateAndTtlsUnchanged() throws IOException {
            String src = stripCommentsAndLiterals(Files.readString(
                    Paths.get(PAPI_EXPANSION), StandardCharsets.UTF_8));

            assertTrue(Pattern.compile("final\\s+Long\\s+value\\s*;").matcher(src).find(),
                    "CoinsEntry.value 不再是 Long 了。它的三态语义（null = 未知）是"
                            + "LLEPapiExpansion 刻意设计的（见该类 :146-169 的 javadoc："
                            + "「用 value == null 表达『还不知道』这个三态，而不是用 0 冒充」）。"
                            + "改成基本类型 long 会让「未知」在物理上无处表达，"
                            + "本报告对 R22-D2 的描述需整段重写。");

            assertTrue(src.contains("COINS_CACHE_TTL_MILLIS = 60_000L"),
                    "COINS_CACHE_TTL_MILLIS 不再是 60s。R22-D2 的后果量化"
                            + "（「一次失败 = 记分板错 60 秒」）直接由它决定，"
                            + "改动后请同步更新报告里的窗口数字。");
            assertTrue(src.contains("COINS_RETRY_TTL_MILLIS = 15_000L"),
                    "COINS_RETRY_TTL_MILLIS 不再是 15s。它是「失败应走的退避窗口」，"
                            + "与被误用的 60s 确认值 TTL 形成对照 —— 这正是缺陷的经济学："
                            + "失败本该 15s 后重试，实际却把假 0 锁了 60s。");
        }

        /**
         * {@code scheduleCoinsLoad} 必须仍然显式拒绝「把失败折成 0」——
         * 这是被上游违反的那条契约。<b>它必须留着</b>，不是删掉它。
         */
        @Test
        @DisplayName("L4：scheduleCoinsLoad 仍保留「绝不折成 0」的 null + 退避逻辑")
        void scheduleCoinsLoadStillRefusesFakeZero() throws IOException {
            String src = stripCommentsAndLiterals(Files.readString(
                    Paths.get(PAPI_EXPANSION), StandardCharsets.UTF_8));

            String body = methodBody(src, "private void scheduleCoinsLoad(");
            assertTrue(body.contains("Long value = null"),
                    "scheduleCoinsLoad 不再用 null 表达未知了。这段是下游<b>正确</b>的一半："
                            + "它把 RuntimeException 落成 null + 15s 退避。"
                            + "R22-D2 的缺口在上游返回类型不能表达失败，不是这里写错了 ——"
                            + "不要为了「让测试变绿」把这段删掉，那会把缺陷固化。\n方法体：\n" + body);
            assertTrue(body.contains("COINS_RETRY_TTL_MILLIS"),
                    "scheduleCoinsLoad 里看不到失败退避窗口 —— 「失败走 15s 退避」的契约没了。"
                            + "\n方法体：\n" + body);
        }
    }

    // ==================================================================
    // 辅助（本类独立实现，不与其它 lint 类共用 —— 同 TwoStateLegacyLintTest 的理由：
    //       共用工具会让若干组 lint 同时因一处语法边缘情况静默失效）
    // ==================================================================

    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    private static final String DATA_MANAGER =
            "src/main/java/com/letslearnenglish/minecraftplugin/data/DatabaseManager.java";

    private static final String ACHIEVEMENT_MANAGER =
            "src/main/java/com/letslearnenglish/minecraftplugin/core/achievement/AchievementManager.java";

    private static final String PAPI_EXPANSION =
            "src/main/java/com/letslearnenglish/minecraftplugin/expansion/LLEPapiExpansion.java";

    private static final String ACHIEVEMENT_REPOSITORY =
            "src/main/java/com/letslearnenglish/minecraftplugin/data/repository/AchievementRepository.java";

    /**
     * 「从已解锁集合里移除<b>单条</b>成就」的形态。
     *
     * <p>{@code \bunlocked\s*\.\s*remove\s*\(} 刻意<b>不</b>匹配
     * {@code unlockedCache.remove(playerId)} / {@code unlockedAtCache.remove(playerId)}
     * —— 那是 {@code unloadPlayer} 的<b>整玩家缓存卸载</b>（quit / reload / reset 时清内存，
     * 真值仍在库里、下次 {@code loadPlayerAchievements} 回填），不是撤销成就。
     * 把这两者混为一谈会让本 lint 恒红，进而被人删掉。
     */
    private static final Pattern REMOVE_SINGLE_UNLOCK =
            Pattern.compile("\\bunlocked\\s*\\.\\s*remove\\s*\\(");

    /**
     * {@code AchievementRepository} 里的「删除/清空写面」形态（L1b 用）。
     *
     * <p>返回类型段刻意写成 {@code [^\n(]{1,80}} 而不是 {@code [\w<>\[\],\s.]+?}：
     * 后者含 {@code \s}（含换行），{@code find()} 会跨行把「上一个 public 方法」与
     * 「下一个方法名」拼成假匹配，生成噪声级误报。
     *
     * <p>必须<b>不</b>误伤 {@code findUnlocked}（读）与 {@code upsertUnlock}（写）——
     * 由 {@code deleteWriteFaceDetectorSelfCheck} 用真实方法名钉住。
     */
    private static final Pattern DELETE_WRITE_FACE = Pattern.compile(
            "public\\s+[^\\n(]{1,80}?\\s+(\\w*(?:delete|remove|revoke|clear)\\w*)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * {@link RiskDirectionLints#revocationDetectorSelfCheck()} 与 L1 共用的判定函数。
     *
     * <p>注意：<b>只能</b>查 {@code unlocked.remove(}。<b>不得</b>试图在这里查
     * {@code DELETE FROM ...achievements} —— 本方法的输入已被 {@link #stripCommentsAndLiterals}
     * 抹掉字符串字面量，而所有删除语句的 SQL 都是字符串（{@code "DELETE FROM " + prefix +
     * "achievements"}），抹掉后永远匹配不到，那会是一条<b>恒真的自证桩</b>。
     * 「写侧不许有删除能力」由 {@link RiskDirectionLints#repositoryHasNoDeleteWriteFace()}
     * 在方法名层面守。
     */
    private static boolean hasRevocation(String source) {
        return REMOVE_SINGLE_UNLOCK.matcher(stripCommentsAndLiterals(source)).find();
    }

    private static List<Path> javaSources() throws IOException {
        if (!Files.isDirectory(MAIN_JAVA)) {
            fail("找不到 " + MAIN_JAVA.toAbsolutePath() + "（测试必须以仓库根为工作目录）");
        }
        try (Stream<Path> s = Files.walk(MAIN_JAVA)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    /** 取以 {@code head} 开头的方法体（括号配对，不取第一个 {@code }} 以免被 lambda 截断）。 */
    private static String methodBody(String source, String head) {
        int start = source.indexOf(head);
        if (start < 0) {
            fail("源码里找不到方法声明起始串：" + head
                    + "（方法被改名/删除时请同步本 lint，而不是绕过它）");
        }
        int brace = source.indexOf('{', start);
        if (brace < 0) {
            fail("方法声明后找不到方法体起始 '{'：" + head);
        }
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(brace, i + 1);
                }
            }
        }
        fail("方法体花括号未闭合：" + head);
        return "";
    }

    private static String relativize(Path file) {
        try {
            return Paths.get("").toAbsolutePath().relativize(file.toAbsolutePath()).toString()
                    .replace('\\', '/');
        } catch (RuntimeException e) {
            return file.toString();
        }
    }

    /** 剥注释、保留字面量（本类没有「找字面量」的断言，但保留以便将来加反射兜底）。 */
    private static String stripComments(String source) {
        return scan(source, false);
    }

    /** 剥注释并把字面量抹成空格 —— 用于「找调用点/声明」，否则墓碑注释会被当成代码。 */
    private static String stripCommentsAndLiterals(String source) {
        return scan(source, true);
    }

    private static String scan(String source, boolean blankLiterals) {
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
                while (j + 2 < n
                        && !(source.charAt(j) == '"' && source.charAt(j + 1) == '"'
                        && source.charAt(j + 2) == '"')) {
                    if (source.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = (j + 2 < n) ? j + 3 : n;
                if (blankLiterals) {
                    blank(out, i, j);
                }
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
                if (blankLiterals) {
                    blank(out, i, j);
                }
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
                if (blankLiterals) {
                    blank(out, i, j);
                }
                i = j;
                continue;
            }

            i++;
        }
        return new String(out);
    }

    private static void blank(char[] out, int from, int to) {
        int end = Math.min(to, out.length);
        for (int k = Math.max(0, from); k < end; k++) {
            if (out[k] != '\n' && out[k] != '\r') {
                out[k] = ' ';
            }
        }
    }
}
