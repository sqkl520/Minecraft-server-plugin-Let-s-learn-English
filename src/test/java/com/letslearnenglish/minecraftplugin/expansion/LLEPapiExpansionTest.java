package com.letslearnenglish.minecraftplugin.expansion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.data.CheckInData;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * Round 22：{@code LLEPapiExpansion}（PlaceholderAPI 扩展，<b>第三方集成面</b>）的行为契约。
 *
 * <h2>为什么这个类值得单独一套测试</h2>
 * 它是本插件向「记分板插件 / 排行榜插件 / 全息字插件」暴露数字的<b>唯一公开 API 面</b>。
 * 其它任何一层出错，服主至少能在游戏内看到；而这里的错误只会在<b>别人的插件</b>里
 * 表现为「一个错的数字」或「一个 NPE」，服主几乎无法自行诊断。jacoco 显示本类
 * （以及内部 {@code CachedValue} / {@code CoinsEntry}）此前是<b>零行覆盖</b>。
 *
 * <h2>本类钉住的四类不变量</h2>
 * <ol>
 *   <li><b>归属契约</b>：未知占位符必须返回 {@code null}（= 不归我管，让别的扩展试），
 *       已知但无值返回 {@code ""}（= 归我管，但没有值）。搞反会静默吃掉别人的占位符。</li>
 *   <li><b>钱的三态</b>：{@code %lle_coins_earned%} 未命中必须是空串，<b>不是 {@code "0"}</b> ——
 *       {@code 0} 是一个「看起来很可信的假数」，玩家会以为累计金币被清零。</li>
 *   <li><b>三道隐私门</b>：隐身（showInSquare）、学习成绩（showResults）、金币只给本人。
 *       隐藏玩家在<b>所有</b> gated 占位符上都必须返回隐私文案。</li>
 *   <li><b>分层缓存</b>：30s 外层值缓存 / 金币 60s 独立缓存 + 1s 在途哨兵 + 15s 失败退避 /
 *       条目上限淘汰。{@code coins_earned} 刻意<b>不进</b>外层缓存（否则首轮暂态空串会被冻 30s）。</li>
 * </ol>
 *
 * <h2>调度器就地挂起</h2>
 * {@code runTaskAsynchronously} 默认<b>不执行</b>，Runnable 收进 {@link #asyncTasks}，由用例
 * 显式驱动（或开 {@code runInline} 让它就地执行）。真实线程池会让「有没有重复排任务」
 * 「异步补读回来的值有没有进缓存」这两件事不可观测。
 *
 * <h2>缺陷记录约定（第 27 轮更新）</h2>
 * R22 审计给本类留了 7 条 {@code @Disabled}。第 27 轮逐条重审定级后<b>全部落地</b>，
 * 因此本类现在<b>零</b> {@code @Disabled} —— 每条缺陷都变成了一个会红的真用例。
 * 定级与处置的对应关系（供下一个人复核，不必再翻审计报告）：
 *
 * <table border="1">
 *   <caption>R22 七条缺陷的重审定级与处置</caption>
 *   <tr><th>编号</th><th>原定级</th><th>重审定级</th><th>处置</th></tr>
 *   <tr><td>R22-D1</td><td>HIGH</td><td><b>LOW</b>（纯性能，取值不变）</td>
 *       <td>修：把 PlayerStats 读取收敛到真正需要它的 6 个占位符</td></tr>
 *   <tr><td>R22-D2</td><td>HIGH</td><td><b>MEDIUM</b></td>
 *       <td>修：加 {@code getCoinsEarnedWithStatus} 两态变体，只给 PAPI 用</td></tr>
 *   <tr><td>R22-D3</td><td>MEDIUM</td><td><b>MEDIUM</b>（认可）</td>
 *       <td>修：{@code longest_streak} 进 RESULTS_GATED</td></tr>
 *   <tr><td>R22-D5</td><td>LOW</td><td>LOW（认可）</td>
 *       <td>修：null-player 分支复用同一套「已知」判据</td></tr>
 *   <tr><td>R22-D6</td><td>LOW</td><td>LOW（认可）</td>
 *       <td>修：未上榜文案改按查看者语言</td></tr>
 *   <tr><td>R22-D11</td><td>LOW</td><td>LOW（认可，但有真实外部性）</td>
 *       <td>修：{@code Locale.ROOT}</td></tr>
 *   <tr><td>R22-D15</td><td>LOW</td><td>LOW（认可，可维护性）</td>
 *       <td>修：随 D1 的同一处重构一并消失</td></tr>
 * </table>
 *
 * <p><b>为什么 D1 从 HIGH 降到 LOW</b>：它<b>不产生任何错误的值</b>，只是让一枚
 * 已经写好的异步优化被同一次调用里的同步 SELECT 抵消。R22 把它标 HIGH 是把它和
 * 「显示一个错的数字」混为一谈了 —— 本仓对 HIGH 的用法（{@code SettlementClaim} /
 * {@code LedgerClearResult} / {@code CheckInClaim}）一律指「会动钱/会永久丢数据」。
 *
 * <p><b>为什么 D2 从 HIGH 降到 MEDIUM</b>：它能让记分板显示可信的假 0 并锁 60 秒，
 * 但 ① 不发钱、不删数据、不触发自动化判定；② {@code DatabaseManager} 落 WARNING
 * 带堆栈（服主有一手信号）；③ 需要 {@code SQLException} 才触发。与「动钱」那一族
 * 有量级差异，不是程度差异。
 */
@DisplayName("R22：PlaceholderAPI 扩展（第三方集成面）")
class LLEPapiExpansionTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** 与 messages/zh.yml 的 {@code placeholder.private} 一致。 */
    private static final String PRIVATE = "已隐藏";
    /** 与 messages/zh.yml 的 {@code placeholder.rank-unranked} 一致。 */
    private static final String UNRANKED = "未上榜";

    private LetsLearnEnglish plugin;
    private PlayerDataStore store;
    private DatabaseManager db;
    private ConfigManager configManager;
    private MessageUtil messages;
    private AchievementManager achievements;
    private ProgressManagerImpl progress;
    private Leaderboard leaderboard;
    private SeasonManager season;
    private Server server;
    private BukkitScheduler scheduler;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet resultSet;

    /** 离线目标（plain 入口）：PAPI 对离线查询传的就是这个形态。 */
    private OfflinePlayer offlineTarget;
    /** 在线目标玩家。 */
    private Player target;
    /** 另一个在线玩家（非本人查看者）。 */
    private Player viewer;

    private PlayerDataStore.ProfileSettings profileSettings;
    private PlayerDataStore.PlayerStats stats;

    /** 被挂起的异步任务（{@link #runInline} = false 时收集到这里）。 */
    private final List<Runnable> asyncTasks = new ArrayList<>();
    /** true = 异步任务就地同步执行（便于观察「任务跑完之后缓存里是什么」）。 */
    private boolean runInline;

    private Locale previousDefaultLocale;
    private LLEPapiExpansion expansion;

    @BeforeEach
    void setUp() throws Exception {
        previousDefaultLocale = Locale.getDefault();
        // 格式化断言必须与开发机默认 Locale 无关（见 accuracy 系列用例）。
        Locale.setDefault(Locale.ROOT);
        asyncTasks.clear();
        runInline = false;

        plugin = mock(LetsLearnEnglish.class);
        store = mock(PlayerDataStore.class);
        db = mock(DatabaseManager.class);
        configManager = mock(ConfigManager.class);
        messages = mock(MessageUtil.class);
        achievements = mock(AchievementManager.class);
        progress = mock(ProgressManagerImpl.class);
        leaderboard = mock(Leaderboard.class);
        season = mock(SeasonManager.class);
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);

        // 异常分支（A6/A7）会打 WARNING 带堆栈 —— 用一个静音的真实 Logger，
        // 既避免污染 surefire 输出，又不用 mock JDK 的 Logger。
        Logger quietLogger = Logger.getLogger("LLEPapiExpansionTest");
        quietLogger.setLevel(java.util.logging.Level.OFF);
        when(plugin.getLogger()).thenReturn(quietLogger);
        when(plugin.getDatabaseManager()).thenReturn(db);
        when(plugin.getPlayerDataStore()).thenReturn(store);
        when(plugin.getMessageUtil()).thenReturn(messages);
        when(plugin.getAchievementManager()).thenReturn(achievements);
        when(plugin.getProgressManager()).thenReturn(progress);
        when(progress.getLeaderboard()).thenReturn(leaderboard);
        when(plugin.getSeasonManager()).thenReturn(season);
        // 赛季默认不可用（isEnabled=false 是 mock 默认值，显式写出来当文档）。
        when(season.isEnabled()).thenReturn(false);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);

        // 调度器：默认挂起；runInline 时就地执行（两个模式都让「排了几次」可断言）。
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            Runnable task = inv.getArgument(1);
            if (runInline) {
                task.run();
            } else {
                asyncTasks.add(task);
            }
            return null;
        });

        // 语言回落：configManager.getMainConfig() 为 null → 一律 "zh"（离线目标的分支）。
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(null);
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");

        // i18n：真实 zh.yml 的两条文案。生产调用一律不带占位符实参（零 varargs），
        // 故这里也用零 varargs 精确 stub —— 避免 varargs 匹配器的多样性歧义。
        when(messages.getMessage("placeholder.private")).thenReturn(PRIVATE);
        when(messages.getMessage("placeholder.rank-unranked")).thenReturn(UNRANKED);
        when(messages.getPlayerMessage(any(Player.class), eq("placeholder.private")))
                .thenReturn(PRIVATE);
        when(messages.getPlayerMessage(any(Player.class), eq("placeholder.rank-unranked")))
                .thenReturn(UNRANKED);

        // 隐私设置默认 = 新玩家默认值（showResults / showInSquare 均 true）。
        profileSettings = new PlayerDataStore.ProfileSettings();
        when(store.loadProfileSettingsForDisplay(any(UUID.class))).thenReturn(profileSettings);

        stats = new PlayerDataStore.PlayerStats();
        stats.setScore(100L);
        stats.addCorrect(3);
        stats.addIncorrect(1);
        stats.setSessions(2);
        when(store.getPlayerStats(any(UUID.class))).thenReturn(stats);

        // longest_streak 直查列：走 PlayerDirectoryRepository 的 JDBC 链（无行 → 0）。
        when(db.getConnectionLock()).thenReturn(new Object());
        when(db.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        offlineTarget = offlinePlayer(TARGET_ID);
        target = onlinePlayer(TARGET_ID);
        viewer = onlinePlayer(VIEWER_ID);

        expansion = new LLEPapiExpansion(plugin);
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(previousDefaultLocale);
    }

    // ---------------------------------------------------------------- helpers

    private Player onlinePlayer(UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.isOnline()).thenReturn(true);
        when(p.getPlayer()).thenReturn(p);
        return p;
    }

    private OfflinePlayer offlinePlayer(UUID id) {
        OfflinePlayer p = mock(OfflinePlayer.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.isOnline()).thenReturn(false);
        return p;
    }

    /** plain 入口：PAPI 只回传 target，拿不到调用者（一律按非本人）。 */
    private String plain(String params) {
        return expansion.onRequest(offlineTarget, params);
    }

    /** 关系型入口的「本人视角」：{@code %rel_lle_<params>%}，viewer == target。 */
    private String self(String params) {
        return expansion.onPlaceholderRequest(target, target, params);
    }

    /** 关系型入口的「他人视角」：viewer != target。 */
    private String other(String params) {
        return expansion.onPlaceholderRequest(viewer, target, params);
    }

    private void drainAsync() {
        List<Runnable> pending = new ArrayList<>(asyncTasks);
        asyncTasks.clear();
        pending.forEach(Runnable::run);
    }

    private int coinsCacheSize() {
        return cacheFieldSize("coinsCache");
    }

    private int outerCacheSize() {
        return cacheFieldSize("cache");
    }

    /** 读 {@code coinsCache} 里某玩家的 {@code value}（null = 未知 / 读失败）。 */
    private Long coinsEntryValue(UUID playerId) {
        return (Long) coinsEntryField(playerId, "value");
    }

    /** 读 {@code coinsCache} 里某玩家的条目<b>剩余</b> TTL（毫秒）。 */
    private long coinsEntryTtlRemaining(UUID playerId) {
        long expiresAt = (Long) coinsEntryField(playerId, "expiresAt");
        return expiresAt - System.currentTimeMillis();
    }

    /**
     * 反射读 {@code CoinsEntry} 的私有字段。
     *
     * <p>这是本类<b>唯一</b>必须读实现内部状态的地方：三态语义（{@code null} = 未知）
     * 与两个 TTL（60s 确认值 / 15s 退避）在公开 API 上完全不可观测 —— 它们在 PAPI 面上
     * 都只表现为「空串」或「0」。不读这两个字段，R22-D2 的修复就<span>无法被证伪</b>
     * （把 {@code readFailed() ? null : value} 改回 {@code read.value()} 也不会有任何
     * 可见差异，直到下一次 60 秒的假 0）。
     *
     * <p>失败信息必须可读 —— 字段一改名就报「请同步本用例」，而不是给个含糊的 NPE。
     */
    private Object coinsEntryField(UUID playerId, String fieldName) {
        try {
            Field cacheField = LLEPapiExpansion.class.getDeclaredField("coinsCache");
            cacheField.setAccessible(true);
            Object entry = ((Map<?, ?>) cacheField.get(expansion)).get(playerId);
            if (entry == null) {
                throw new AssertionError("coinsCache 里没有 " + playerId + " 的条目 —— "
                        + "要么没走过补读，要么键格式变了；请同步本用例");
            }
            Field f = entry.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(entry);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 CoinsEntry." + fieldName
                    + "（字段被改名了？请同步本用例）", e);
        }
    }

    /**
     * 读私有缓存字段大小。
     *
     * <p>只读取不写入：写侧（改 expiresAt 触发 TTL 过期）会让用例与实现细节死绑，
     * 且键格式一变就静默假绿；读侧失败会立即抛错，属可接受的 lint 锚点。
     */
    private int cacheFieldSize(String name) {
        try {
            Field f = LLEPapiExpansion.class.getDeclaredField(name);
            f.setAccessible(true);
            return ((Map<?, ?>) f.get(expansion)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 LLEPapiExpansion." + name
                    + "（字段被改名了？请同步本用例）", e);
        }
    }

    // ============================================================ A. 归属契约

    @Test
    @DisplayName("A1：未知占位符返回 null（让别的扩展试），不是空串")
    void unknownParamsReturnNull() {
        assertNull(plain("totally_unknown_thing"),
                "未知占位符必须返回 null —— 返回空串等于宣称「归我管」，"
                        + "会把别的插件注册在同名 namespace 下的占位符静默吃掉");
        assertNull(plain("score2"));
        assertNull(plain("achievement_this_id_does_not_exist"),
                "非法成就 id 走 legacy 契约（null），而不是空串");
        assertNull(self("nope"));
        assertNull(other("nope"));
    }

    @Test
    @DisplayName("A2：identifier/persist/canRegister —— 重载后仍注册")
    void identityContract() {
        assertEquals("lle", expansion.getIdentifier(),
                "namespace 变了 = 所有既有 %lle_*% 记分板同时失效");
        assertTrue(expansion.persist(), "/papi reload 后扩展必须仍在（persist=true）");
        assertTrue(expansion.canRegister());
    }

    @Test
    @DisplayName("A3：null player 不抛 NPE（离线/未知查询）")
    void nullPlayerDoesNotThrow() {
        // 显式转型到 OfflinePlayer：PAPI 同时带 2 参 onRequest(Player,..) 重载，
        // 裸 null 会选最具体的那个，从而绕过被测方法。
        assertEquals("", expansion.onRequest((OfflinePlayer) null, "score"));
        assertEquals("", expansion.onRequest((OfflinePlayer) null, "coins_earned"));
        assertEquals("", expansion.onRequest((OfflinePlayer) null, "rank"));
    }

    @Test
    @DisplayName("A4：null player + 未知占位符仍返回 null（R22-D5 已修）")
    void nullPlayerUnknownParamsReturnNull() {
        // R22-D5 修复前：onRequest 在 params 判定之前就 return ""，于是玩家为 null 时
        // %lle_<任意东西>% 都被宣称「归我管」，把别人注册在同 namespace 下的占位符吃掉。
        // 现在判据复用 resolveCached 的同一套预检（isKnownOrValidAchievement）。
        assertNull(expansion.onRequest((OfflinePlayer) null, "totally_unknown_thing"),
                "未知占位符必须让给别的扩展 —— null-player 分支不是这条契约的例外");
        assertNull(expansion.onRequest((OfflinePlayer) null, "achievement_nonexistent"),
                "非法成就 id 在 null-player 分支同样走 legacy null 契约");
        assertEquals("", expansion.onRequest((OfflinePlayer) null, "achievement_first_word"),
                "合法成就 id 仍属本扩展管辖 → 无值时给空串");
        assertEquals("", expansion.onRequest((OfflinePlayer) null, "season_rank"),
                "赛季占位符也是已知键 → 空串（不是 null）");
    }

    @Test
    @DisplayName("A5：relational 三个参数任一为 null 返回 null（不抛）")
    void relationalNullSafety() {
        assertNull(expansion.onPlaceholderRequest(null, target, "score"));
        assertNull(expansion.onPlaceholderRequest(viewer, null, "score"));
        assertNull(expansion.onPlaceholderRequest(viewer, target, null));
    }

    @Test
    @DisplayName("A6：下游抛 RuntimeException 时返回 null 而不是把异常穿给第三方插件")
    void downstreamRuntimeExceptionIsContained() {
        when(store.loadProfileSettingsForDisplay(any(UUID.class)))
                .thenThrow(new IllegalStateException("db exploded"));
        assertNull(plain("score"),
                "解析失败必须返回 null（下轮重试），绝不能把异常抛进 PAPI 调用方 —— "
                        + "那会让记分板插件的整个刷新任务崩掉");
    }

    @Test
    @DisplayName("A7：隐私设置读成 null（防御分支）时不抛，降级为 null")
    void nullProfileSettingsDegradesToNull() {
        when(store.loadProfileSettingsForDisplay(any(UUID.class))).thenReturn(null);
        assertNull(plain("score"), "下游 NPE 被兜住 → 返回 null，而不是把 NPE 抛出去");
    }

    // ==================================================== B. 金币三态与缓存

    @Test
    @DisplayName("B1：coins_earned 未命中返回空串，不是 \"0\"（钱不能发假数）")
    void coinsMissReturnsEmptyStringNotZero() {
        String first = self("coins_earned");
        assertEquals("", first,
                "未命中必须是空串 —— \"0\" 是一个看起来可信的假数，"
                        + "玩家会以为自己的累计金币被清零了");
        assertEquals(1, asyncTasks.size(), "未命中应恰好排一次异步补读");
    }

    @Test
    @DisplayName("B2：异步补读回来后 → 真值；首轮仍是空串（不阻塞）")
    void coinsValueAppearsAfterAsyncLoad() {
        runInline = true;
        when(db.getCoinsEarnedWithStatus(TARGET_ID)).thenReturn(DatabaseManager.CoinsEarnedRead.present(42L));

        assertEquals("", self("coins_earned"), "首轮不阻塞：排任务后立刻返回空串");
        assertEquals("42", self("coins_earned"), "补读完成后下一次刷新拿到真值");
        assertEquals("42", self("coins_earned"), "60s 内保持该值");
        verify(scheduler, times(1))
                .runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
    }

    @Test
    @DisplayName("B3：命中后不再重复排任务（每玩家每分钟最多一次异步读）")
    void coinsHitDoesNotReschedule() {
        runInline = true;
        when(db.getCoinsEarnedWithStatus(TARGET_ID)).thenReturn(DatabaseManager.CoinsEarnedRead.present(42L));

        self("coins_earned");
        self("coins_earned");
        self("coins_earned");
        verify(scheduler, times(1))
                .runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
    }

    @Test
    @DisplayName("B4：在途窗口去重 —— 补读未完成前连打多次只排一次")
    void coinsInFlightWindowDeduplicates() {
        when(db.getCoinsEarnedWithStatus(TARGET_ID)).thenReturn(DatabaseManager.CoinsEarnedRead.present(42L));

        assertEquals("", self("coins_earned"));
        assertEquals("", self("coins_earned"));
        assertEquals("", self("coins_earned"));
        assertEquals(1, asyncTasks.size(),
                "1s 在途哨兵条目就是补读去重闸门；去掉它 → DB 故障期每玩家每次刷新排一个任务");
    }

    @Test
    @DisplayName("B5：读失败落退避，且绝不把失败折成 0")
    void coinsFailureBacksOffWithoutFakingZero() {
        runInline = true;
        when(db.getCoinsEarnedWithStatus(TARGET_ID)).thenThrow(new IllegalStateException("db down"));

        assertEquals("", self("coins_earned"), "失败 = 未知 = 空串");
        assertEquals("", self("coins_earned"), "15s 退避窗口内仍是未知，不是 0");
        verify(scheduler, times(1))
                .runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
    }

    @Test
    @DisplayName("B5b：三态读失败 → 空串 + 15s 退避（R22-D2 已修）")
    void dbReadFailureMustNotRenderZero() {
        runInline = true;
        when(db.getCoinsEarnedWithStatus(TARGET_ID))
                .thenReturn(DatabaseManager.CoinsEarnedRead.FAILED);

        assertEquals("", self("coins_earned"), "读失败 = 未知 = 空串（不是 \"0\"）");
        assertEquals("", self("coins_earned"),
                "库读失败必须持续表现为「未知」，不能在执行一次读之后变成 \"0\"。"
                        + "修复前：getCoinsEarned 在 SQLException 时 return 0L，与「确实赚了 0 枚」"
                        + "逐字节同形，于是这里拿到 \"0\" 并吃满 60s「确认值」TTL —— "
                        + "记分板对本人显示一个看起来可信的假 0。");
    }

    @Test
    @DisplayName("B5c：读失败走 15s 退避 TTL，真 0 走 60s 确认值 TTL（两个窗口不混）")
    void failureUsesRetryTtlWhileConfirmedZeroUsesValueTtl() {
        runInline = true;

        // ① 读失败 → CoinsEntry 必须是「null 值 + 15s 退避」，不是「60s 确认值」。
        when(db.getCoinsEarnedWithStatus(TARGET_ID))
                .thenReturn(DatabaseManager.CoinsEarnedRead.FAILED);
        self("coins_earned");
        assertTrue(coinsEntryTtlRemaining(TARGET_ID) <= 15_000L,
                "读失败后缓存条目的 TTL 必须落在 15s 退避窗口内，实际剩余 "
                        + coinsEntryTtlRemaining(TARGET_ID) + "ms。"
                        + "修复前失败被折成 0L 走 COINS_CACHE_TTL_MILLIS（60s 确认值），"
                        + "于是一次库抖动被放大成 60 秒的假 0。");
        assertNull(coinsEntryValue(TARGET_ID),
                "读失败的条目 value 必须是 null（未知），不能是 Long 0");

        // ② 确认的 0 → 必须是「Long 0 + 60s」，绝不能也变成空串。
        Player fresh = onlinePlayer(OTHER_ID);
        when(db.getCoinsEarnedWithStatus(OTHER_ID))
                .thenReturn(DatabaseManager.CoinsEarnedRead.present(0L));
        expansion.onPlaceholderRequest(fresh, fresh, "coins_earned");
        assertEquals("0", expansion.onPlaceholderRequest(fresh, fresh, "coins_earned"),
                "真 0 必须显示 \"0\" —— 三态修复不能把「确实赚了 0 枚」也吞成空串");
        assertEquals(Long.valueOf(0L), coinsEntryValue(OTHER_ID),
                "确认的 0 在缓存里必须是 Long 0（非 null），否则与「未知」不可区分");
        assertTrue(coinsEntryTtlRemaining(OTHER_ID) > 15_000L,
                "确认值必须走 COINS_CACHE_TTL_MILLIS（60s），实际剩余 "
                        + coinsEntryTtlRemaining(OTHER_ID) + "ms");

        assertNull(coinsEntryValue(TARGET_ID),
                "另一个玩家的真值不能把失败条目覆盖成「已知」");
    }

    @Test
    @DisplayName("B6：金币缓存按 UUID 分键 —— 张三的钱不会显示给李四")
    void coinsCacheIsKeyedPerPlayer() {
        runInline = true;
        when(db.getCoinsEarnedWithStatus(TARGET_ID)).thenReturn(DatabaseManager.CoinsEarnedRead.present(42L));
        when(db.getCoinsEarnedWithStatus(OTHER_ID)).thenReturn(DatabaseManager.CoinsEarnedRead.present(777L));

        self("coins_earned");
        assertEquals("42", self("coins_earned"));

        Player other = onlinePlayer(OTHER_ID);
        assertEquals("", expansion.onPlaceholderRequest(other, other, "coins_earned"));
        assertEquals("777", expansion.onPlaceholderRequest(other, other, "coins_earned"),
                "两个玩家的值必须互不覆盖");
        assertEquals("42", self("coins_earned"), "回头再读第一个玩家，值仍在");
    }

    @Test
    @DisplayName("B7：非本人查看金币一律隐私文案，且根本不去查库")
    void coinsNeverLeaksToOtherViewers() {
        assertEquals(PRIVATE, other("coins_earned"),
                "金币只给本人（与 ProfileGUI 金币列同规则）");
        assertEquals(PRIVATE, plain("coins_earned"));
        assertTrue(asyncTasks.isEmpty(), "被隐私门挡住的请求不该浪费一次异步读");
        verify(scheduler, never()).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
    }

    @Test
    @DisplayName("B8（缺陷记录 R22-D14）：plain 入口的 coins_earned 永远显示「已隐藏」")
    void plainEntryCoinsIsAlwaysPrivate() {
        // sec-01 让 plain 入口一律按「非本人」处理（fail-closed，隐私上是对的）。
        // 代价：文档里主推的 %lle_coins_earned% 对第三方记分板恒为「已隐藏」。
        assertEquals(PRIVATE, plain("coins_earned"));
    }

    @Test
    @DisplayName("B9：coins_earned 不进 30s 外层缓存（否则首轮暂态空串被冻 30 秒）")
    void coinsEarnedIsNotFrozenByOuterCache() {
        runInline = true;
        when(db.getCoinsEarnedWithStatus(TARGET_ID)).thenReturn(DatabaseManager.CoinsEarnedRead.present(42L));

        self("coins_earned");
        self("coins_earned");
        // 进了外层缓存 → 第二次命中缓存直接返回（不再解析）→ 本断言变红。
        verify(store, times(2)).loadProfileSettingsForDisplay(TARGET_ID);
    }

    @Test
    @DisplayName("B10：coins_earned 路径不再同步调用 getPlayerStats（R22-D1 已修）")
    void coinsPathDoesNotReadPlayerStats() {
        runInline = true;
        when(db.getCoinsEarnedWithStatus(TARGET_ID))
                .thenReturn(DatabaseManager.CoinsEarnedRead.present(42L));
        self("coins_earned");
        // R22-D1 修复前：resolveRequest 在 switch 之前<b>无条件</b>调 getPlayerStats，
        // 而它 cache miss 时会同步 loadPlayerData（两条 SELECT + difficult_words）并抢
        // connectionLock。coins_earned 刻意不入 30s 外层缓存（见 B9），所以它每次刷新
        // 都要多付一次与结果无关的全量加载 —— 「异步补读金币」被原样抵消。
        verify(store, never()).getPlayerStats(any(UUID.class));
    }

    @Test
    @DisplayName("B10b：rank/streak/longest_streak/achievements 同样不碰 getPlayerStats")
    void nonStatsPlaceholdersDoNotReadPlayerStats() {
        when(db.getCheckInData(TARGET_ID)).thenReturn(new CheckInData("2026-01-20", 5));
        when(leaderboard.getPlayerRank(TARGET_ID)).thenReturn(3);
        when(achievements.getUnlockedCount(TARGET_ID)).thenReturn(3);

        assertEquals("#3", plain("rank"));
        assertEquals("5", plain("streak"));
        assertEquals("0", plain("longest_streak"), "无行 → 0（见 E6/E7）");
        assertEquals("3/" + Achievement.values().length, plain("achievements"));

        verify(store, never()).getPlayerStats(any(UUID.class));
    }

    @Test
    @DisplayName("B10c：PlayerStats 族仍必须读 getPlayerStats（收敛不能把数据路径也砍掉）")
    void statsFamilyStillReadsPlayerStats() {
        assertEquals("100", plain("score"));
        assertEquals("3", plain("words"));
        assertEquals("2", plain("sessions"));
        assertEquals("3", plain("correct"));
        assertEquals("1", plain("incorrect"));
        assertEquals("75.0%", plain("accuracy"));
        // 6 个占位符共 6 次 resolve；30s 缓存按 (玩家,params,视角,语言) 分键故各 miss 一次。
        verify(store, times(6)).getPlayerStats(TARGET_ID);
    }

    @Test
    @DisplayName("B11：在途哨兵过期后重新获得补读权（1s 窗口）")
    void coinsSentinelExpiryReschedules() throws Exception {
        when(db.getCoinsEarnedWithStatus(TARGET_ID)).thenReturn(DatabaseManager.CoinsEarnedRead.present(42L));

        assertEquals("", self("coins_earned"));
        assertEquals("", self("coins_earned"));
        verify(scheduler, times(1)).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));

        Thread.sleep(1_100L); // COINS_SENTINEL_TTL_MILLIS = 1000

        assertEquals("", self("coins_earned"), "哨兵已过期，本轮仍未知 → 空串");
        verify(scheduler, times(2)).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
    }

    @Test
    @DisplayName("B12：金币条目超上限后被淘汰，不会无限增长")
    void coinsCacheEvictsBeyondCap() {
        runInline = true;
        when(db.getCoinsEarnedWithStatus(any(UUID.class))).thenReturn(DatabaseManager.CoinsEarnedRead.present(1L));

        for (int i = 0; i < 2_100; i++) {
            Player p = onlinePlayer(new UUID(0xC0FFEEL, i));
            expansion.onPlaceholderRequest(p, p, "coins_earned");
        }
        assertTrue(coinsCacheSize() <= 2_000,
                "coinsCache 上界 2000（COINS_CACHE_MAX_ENTRIES），实际 " + coinsCacheSize()
                        + " —— 长期运行的服会为每个玩家留永久条目");
    }

    // ======================================================== C. 三道隐私门

    @Test
    @DisplayName("C1：隐身玩家（showInSquare=false）—— 所有 gated 占位符都是隐私文案")
    void hiddenPlayerIsPrivateEverywhere() {
        profileSettings.setShowInSquare(false);

        for (String params : List.of("score", "words", "accuracy", "sessions",
                "correct", "incorrect", "achievements", "achievement_first_word")) {
            assertEquals(PRIVATE, plain(params),
                    "%lle_" + params + "% 在非本人查看隐身玩家时必须返回隐私文案");
        }
        assertEquals(UNRANKED, plain("rank"),
                "rank 是例外：返回「未上榜」（与 P0 R-05 把隐身者排除出榜同语义）");
    }

    @Test
    @DisplayName("C2：隐身玩家的赛季占位符 —— 服务器级数据照发，season_rank 按 rank 规则")
    void hiddenPlayerSeasonPlaceholders() {
        profileSettings.setShowInSquare(false);
        when(season.isEnabled()).thenReturn(true);
        when(season.currentSeasonId(any(LocalDate.class))).thenReturn(3);
        when(season.daysLeft()).thenReturn(7L);
        when(season.getRankOf(TARGET_ID)).thenReturn(5);

        assertEquals("3", plain("season"), "赛季号是服务器级状态，零个人信息 → 隐私门不适用");
        assertEquals("7", plain("season_days_left"));
        assertEquals(UNRANKED, plain("season_rank"),
                "含个人信息 → 隐身者非本人查看走 rank 同款回落");
    }

    @Test
    @DisplayName("C3：showResults=false —— 学习成绩族全挡，achievement_<id> 与 longest_streak 同门")
    void showResultsOffBlocksLearningFamily() {
        profileSettings.setShowResults(false);

        for (String params : List.of("score", "words", "accuracy", "sessions",
                "correct", "incorrect", "achievements", "achievement_first_word",
                // R22-D3 已修：答题会话连击峰值也是答题成绩的直接产物（峰值高 ⇔ 连续答对
                // 过很多题），关掉 showResults 仍能被他人读到 = 用计分板绕过隐私开关。
                "longest_streak")) {
            assertEquals(PRIVATE, plain(params),
                    "%lle_" + params + "% 必须与 %lle_achievements% 同门 —— "
                            + "否则玩家能用计分板逐位探测「解锁了哪几个」/「连对过多少题」");
        }
        // 签到连击 = 活跃度（「有没有每天来」），不透露任何答题对错，不受这条门约束。
        assertEquals("0", plain("streak"));
    }

    @Test
    @DisplayName("C3b：longest_streak 走 showResults 门，但本人视角仍能读到真值（R22-D3 已修）")
    void longestStreakFollowsTheResultsGate() throws Exception {
        profileSettings.setShowResults(false);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt("longest_streak")).thenReturn(7);

        assertEquals(PRIVATE, plain("longest_streak"),
                "答题会话连击峰值是学习成绩 → 非本人查看必须被 showResults 挡住");
        assertEquals(PRIVATE, other("longest_streak"));
        assertEquals("7", self("longest_streak"),
                "本人视角必须穿过这道门，否则 R22-D3 的修法把功能砍掉了");
    }

    @Test
    @DisplayName("C4：本人视角穿过所有隐私门")
    void selfViewBypassesAllGates() {
        profileSettings.setShowInSquare(false);
        profileSettings.setShowResults(false);

        assertEquals("100", self("score"));
        assertEquals("2", self("sessions"));
        assertEquals("3", self("correct"));
    }

    @Test
    @DisplayName("C5（缺陷记录 R22-D4）：离线玩家（隐私缓存 miss）所有数字都是「已隐藏」")
    void offlinePlayerWithUnknownSettingsIsFullyHidden() {
        // loadProfileSettingsForDisplay 对缓存 miss 返回 unresolvedFallback()
        // （showInSquare=false / showResults=false，见 PlayerDataStore:2447-2491 与 L22）。
        // 于是「排行榜/全息字展示离线玩家」这条最主要的第三方用法全部渲染成「已隐藏」。
        // 隐私方向是对的（fail-closed），但产品上等于这些占位符对离线玩家不可用。
        when(store.loadProfileSettingsForDisplay(any(UUID.class)))
                .thenReturn(PlayerDataStore.ProfileSettings.unresolvedFallback());

        assertEquals(PRIVATE, plain("score"));
        assertEquals(UNRANKED, plain("rank"), "rank 例外仍然生效");
    }

    // ============================================== D. 外层缓存与值的正确性

    @Test
    @DisplayName("D1：30s 值缓存命中后不再解析（也不重复读设置）")
    void secondCallWithinTtlIsServedFromCache() {
        assertEquals("100", plain("score"));
        assertEquals("100", plain("score"));
        assertEquals("100", plain("score"));
        verify(store, times(1)).loadProfileSettingsForDisplay(TARGET_ID);
    }

    @Test
    @DisplayName("D2：缓存返回的是缓存时刻的值（不会半路换成新值）")
    void cachedValueIsStableWithinTtl() {
        assertEquals("100", plain("score"));

        PlayerDataStore.PlayerStats updated = new PlayerDataStore.PlayerStats();
        updated.setScore(999L);
        when(store.getPlayerStats(any(UUID.class))).thenReturn(updated);

        assertEquals("100", plain("score"),
                "30s 内保持旧值（本轮的设计取舍）；变红说明 TTL 缓存被绕过了");
    }

    @Test
    @DisplayName("D3：外层缓存超上限后淘汰，不会无限增长")
    void outerCacheEvictsBeyondCap() {
        // 键 = uuid|params|self/other|lang，故用「玩家 × 占位符」铺满 5000 个键，
        // 刻意避开 coins_earned（它不入本缓存）。
        List<String> params = List.of("score", "words", "accuracy", "sessions", "streak",
                "longest_streak", "achievements", "correct", "incorrect", "rank",
                "season", "season_days_left", "season_rank");
        int players = 400;
        for (int i = 0; i < players; i++) {
            OfflinePlayer p = offlinePlayer(new UUID(0xDEADBEEFL, i));
            for (String param : params) {
                expansion.onRequest(p, param);
            }
        }
        assertTrue(outerCacheSize() <= 5_000,
                "cache 上界 5000（CACHE_MAX_ENTRIES），实际 " + outerCacheSize()
                        + " —— 否则「玩家 × 占位符 × 视角 × 语言」会吃满堆");
    }

    @Test
    @DisplayName("D4：缓存按 (玩家, params, 视角, 语言) 分桶 —— 不同视角不串")
    void cacheKeySeparatesViewerBucket() {
        profileSettings.setShowResults(false);

        assertEquals(PRIVATE, other("score"), "他人视角被 showResults 挡住");
        assertEquals("100", self("score"), "本人视角必须拿到真值（不能被上一个桶污染）");
        assertEquals(PRIVATE, other("score"), "回头再取他人视角，仍然隐私");
    }

    @Test
    @DisplayName("D5：rank 的未上榜文案取「查看者」语言（R22-D6 已修）")
    void unrankedTextFollowsViewerLanguage() {
        Player zhTarget = onlinePlayer(TARGET_ID);
        Player enViewer = onlinePlayer(VIEWER_ID);
        when(plugin.getPlayerLanguage(zhTarget)).thenReturn("zh");
        when(plugin.getPlayerLanguage(enViewer)).thenReturn("en");
        when(messages.getPlayerMessage(eq(zhTarget), eq("placeholder.rank-unranked")))
                .thenReturn("未上榜");
        when(messages.getPlayerMessage(eq(enViewer), eq("placeholder.rank-unranked")))
                .thenReturn("Unranked");
        when(leaderboard.getPlayerRank(TARGET_ID)).thenReturn(0);

        // 修复前 getUnrankedText 按「目标」语言取，而缓存键里的 lang 按「查看者」取 ——
        // 同一次调用里两套口径，英文客户端在计分板上看到中文「未上榜」。
        assertEquals("Unranked", expansion.onPlaceholderRequest(enViewer, zhTarget, "rank"),
                "未上榜文案是渲染在查看者屏幕上的，必须随查看者语言");
        assertEquals("未上榜", expansion.onPlaceholderRequest(zhTarget, zhTarget, "rank"),
                "中文查看者看中文 —— 不能为了修 A 把 B 弄坏");
    }

    @Test
    @DisplayName("D5b：season_rank 的未上榜文案同样随查看者语言")
    void seasonUnrankedTextFollowsViewerLanguage() {
        Player zhTarget = onlinePlayer(TARGET_ID);
        Player enViewer = onlinePlayer(VIEWER_ID);
        when(plugin.getPlayerLanguage(zhTarget)).thenReturn("zh");
        when(plugin.getPlayerLanguage(enViewer)).thenReturn("en");
        when(messages.getPlayerMessage(eq(zhTarget), eq("placeholder.rank-unranked")))
                .thenReturn("未上榜");
        when(messages.getPlayerMessage(eq(enViewer), eq("placeholder.rank-unranked")))
                .thenReturn("Unranked");
        when(season.isEnabled()).thenReturn(true);
        when(season.currentSeasonId(any(LocalDate.class))).thenReturn(3);
        when(season.getRankOf(TARGET_ID)).thenReturn(0);

        assertEquals("Unranked", expansion.onPlaceholderRequest(enViewer, zhTarget, "season_rank"),
                "season_rank 与 rank 共用 getUnrankedText，语言口径必须一致");
    }

    @Test
    @DisplayName("D5c：plain 入口（无查看者上下文）退回服务端默认语言")
    void plainEntryUnrankedFallsBackToServerLanguage() {
        when(leaderboard.getPlayerRank(TARGET_ID)).thenReturn(0);
        // getMessage（非 getPlayerMessage）就是「服务端默认语言」的取值口。
        when(messages.getMessage("placeholder.rank-unranked")).thenReturn(UNRANKED);
        assertEquals(UNRANKED, plain("rank"));
        verify(messages, never()).getPlayerMessage(any(Player.class), eq("placeholder.rank-unranked"));
    }

    @Test
    @DisplayName("D6：外层缓存 30s 过期后重新解析（TTL 不是永久）")
    void outerCacheExpiresAfterTtl() throws Exception {
        assertEquals("100", plain("score"));
        verify(store, times(1)).loadProfileSettingsForDisplay(TARGET_ID);

        // 唯一不得不写缓存内部状态的地方：30s 真等不起。装的「旧值」故意是 "stale"
        // —— 若 TTL 判定被删掉，本用例会拿到 "stale" 而红，不会静默假绿。
        installExpiredOuterEntry(TARGET_ID + "|score|other|zh");

        assertEquals("100", plain("score"), "过期条目必须重新解析，不能返回旧缓存值");
        verify(store, times(2)).loadProfileSettingsForDisplay(TARGET_ID);
    }

    /**
     * 往私有 {@code cache} 里塞一个已过期条目。
     *
     * <p>{@code CachedValue} 的字段是 {@code final}，改不了时间戳；只能反射构造一个新实例
     * 覆写同一个键。两层反射都带可读的失败信息 —— 实现一改就报「请同步本用例」，
     * 而不是给出一个含糊的 NPE。
     */
    private void installExpiredOuterEntry(String key) {
        try {
            Class<?> cachedValueClass = Class.forName(
                    "com.letslearnenglish.minecraftplugin.expansion.LLEPapiExpansion$CachedValue");
            java.lang.reflect.Constructor<?> ctor =
                    cachedValueClass.getDeclaredConstructor(String.class, long.class);
            ctor.setAccessible(true);
            Object expired = ctor.newInstance("stale", System.currentTimeMillis() - 1_000L);

            Field f = LLEPapiExpansion.class.getDeclaredField("cache");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) f.get(expansion);
            map.put(key, expired);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("装不进过期缓存条目（CachedValue/cache 被改名了？"
                    + "请同步本用例）", e);
        }
    }

    // ================================================ E. 各占位符取值正确性

    @Test
    @DisplayName("E1：accuracy 按 %.1f% 输出（Locale.ROOT 下固定点号）")
    void accuracyIsFormattedWithRootLocale() {
        assertEquals("75.0%", plain("accuracy"), "3 对 1 错 = 75.0%");
    }

    @Test
    @DisplayName("E1b：accuracy 与默认 Locale 无关（R22-D11 已修）")
    void accuracyFormattingMustBeLocaleIndependent() {
        // 德语/法语环境用逗号作小数点。修复前这里输出 "75,0%"，第三方插件解析该数字
        // 会静默得到 0 或 NaN —— 与仓库其余格式化（ReportGenerator / ChallengeCompleteGUI /
        // DailyChallengeGUI 一律 Locale.ROOT）不一致。
        Locale.setDefault(Locale.GERMANY);
        assertEquals("75.0%", plain("accuracy"));
        Locale.setDefault(Locale.FRANCE);
        assertEquals("75.0%", plain("accuracy"));
    }

    @Test
    @DisplayName("E2：零答题时 accuracy 是 \"0.0%\"（不是 NaN / Infinity）")
    void accuracyWithZeroAnswers() {
        PlayerDataStore.PlayerStats empty = new PlayerDataStore.PlayerStats();
        when(store.getPlayerStats(any(UUID.class))).thenReturn(empty);
        assertEquals("0.0%", plain("accuracy"));
    }

    @Test
    @DisplayName("E3：rank —— 有名次加 #，无名次回落未上榜")
    void rankPlaceholder() {
        when(leaderboard.getPlayerRank(TARGET_ID)).thenReturn(3);
        assertEquals("#3", plain("rank"));

        OfflinePlayer other = offlinePlayer(OTHER_ID);
        assertEquals(UNRANKED, expansion.onRequest(other, "rank"),
                "NOT_RANKED(<=0) → 未上榜文本");
    }

    @Test
    @DisplayName("E4：achievements 计数用 values().length，不写死 22")
    void achievementsCountUsesEnumSize() {
        when(achievements.getUnlockedCount(TARGET_ID)).thenReturn(3);
        assertEquals("3/" + Achievement.values().length, plain("achievements"));
    }

    @Test
    @DisplayName("E5：动态成就位输出 ✓/✗，未知 id 返回 null")
    void dynamicAchievementPlaceholder() {
        when(achievements.hasAchievement(TARGET_ID, Achievement.FIRST_WORD)).thenReturn(true);
        assertEquals("✓", plain("achievement_first_word"));

        when(achievements.hasAchievement(any(UUID.class), eq(Achievement.FIRST_WORD))).thenReturn(false);
        OfflinePlayer other = offlinePlayer(OTHER_ID);
        assertEquals("✗", expansion.onRequest(other, "achievement_first_word"));

        assertNull(plain("achievement_nonexistent"), "未知成就 id 保留 null 契约");
    }

    @Test
    @DisplayName("E6：streak 取签到表（P3-03），longest_streak 取 player_data 列")
    void streakSourcesAreDistinct() throws Exception {
        when(db.getCheckInData(TARGET_ID)).thenReturn(new CheckInData("2026-01-20", 5));
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt("longest_streak")).thenReturn(7);

        assertEquals("5", plain("streak"), "签到连击来自 checkin_data.streak_count");
        assertEquals("7", plain("longest_streak"), "会话连击峰值来自 player_data.longest_streak");
    }

    @Test
    @DisplayName("E7：签到表无行 → streak 是 0（不是 null / NPE）")
    void streakFallsBackToZero() {
        when(db.getCheckInData(TARGET_ID)).thenReturn(null);
        assertEquals("0", plain("streak"));
    }

    @Test
    @DisplayName("E8：赛季未启用 → 三个赛季占位符一律空串（真 gating 优先于隐私回落）")
    void seasonDisabledReturnsEmpty() {
        when(season.isEnabled()).thenReturn(false);
        profileSettings.setShowInSquare(false); // 即使目标隐身，也应是空串而非「已隐藏」

        assertEquals("", plain("season"));
        assertEquals("", plain("season_days_left"));
        assertEquals("", plain("season_rank"));
    }

    @Test
    @DisplayName("E9：赛季启用后取值（本人视角的 season_rank 带 #）")
    void seasonEnabledValues() {
        when(season.isEnabled()).thenReturn(true);
        when(season.currentSeasonId(any(LocalDate.class))).thenReturn(3);
        when(season.daysLeft()).thenReturn(7L);
        when(season.getRankOf(TARGET_ID)).thenReturn(5);

        assertEquals("3", self("season"));
        assertEquals("7", self("season_days_left"));
        assertEquals("#5", self("season_rank"));
    }

    @Test
    @DisplayName("E10：getPlayerStats 为 null（防御分支）时给 \"0\"，不抛")
    void nullStatsFallsBackToZero() {
        when(store.getPlayerStats(any(UUID.class))).thenReturn(null);
        assertEquals("0", self("score"));
        assertEquals("0", self("sessions"));
    }

    @Test
    @DisplayName("E10b：stats 为 null 时 coins_earned 仍是空串，不借道 stats 分支发 \"0\"（R22-D15 已修）")
    void nullStatsMustNotFakeCoinsZero() {
        when(store.getPlayerStats(any(UUID.class))).thenReturn(null);
        // 修复前：coins_earned 与 PlayerStats 族共用同一条 null 兜底（一律 "0"），
        // 与 COINS_UNKNOWN 的空串契约自相矛盾 —— 同一份代码里「金币未知」有两种展示。
        // 现在 coins_earned 已在 stats 读取之前返回（见 resolveRequest 的提前 switch）。
        assertEquals("", self("coins_earned"));
        // 而真正的 PlayerStats 族保持原口径。
        assertEquals("0", self("score"));
    }

    @Test
    @DisplayName("E11：score/words/sessions/correct/incorrect 与 PlayerStats 口径一致")
    void plainStatPlaceholders() {
        assertEquals("100", plain("score"));
        assertEquals("3", plain("words"), "words = totalCorrect（M10：只计答对）");
        assertEquals("2", plain("sessions"));
        assertEquals("3", plain("correct"));
        assertEquals("1", plain("incorrect"));
    }

    @Test
    @DisplayName("E12：drainAsync 能把挂起的补读跑完（调度器夹具自检）")
    void asyncFixtureSelfCheck() {
        when(db.getCoinsEarnedWithStatus(TARGET_ID)).thenReturn(DatabaseManager.CoinsEarnedRead.present(42L));
        assertEquals("", self("coins_earned"));
        assertEquals(1, asyncTasks.size());
        drainAsync();
        assertEquals("42", self("coins_earned"),
                "补读跑完后必须看到真值 —— 否则说明哨兵/写回没生效");
    }
}
