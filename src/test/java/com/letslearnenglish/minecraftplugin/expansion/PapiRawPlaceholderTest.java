package com.letslearnenglish.minecraftplugin.expansion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * R-48：PlaceholderAPI 扩展的<b>纯数字占位符</b>（{@code _raw} 别名族 + 新增占位符）。
 *
 * <h2>为什么需要单独一套用例</h2>
 * 本扩展原有的 {@code %lle_accuracy%}（{@code "75.0%"}）与 {@code %lle_achievements%}
 * （{@code "3/22"}）对<b>人</b>是可读的，对<b>机器</b>是废的：AJLeaderboards 的文档
 * 原话是「it just needs to have a PAPI placeholder that returns a number」，
 * 而 {@code Integer.parseInt("3/22")} / {@code Double.parseDouble("75.0%")} 都会抛
 * {@code NumberFormatException}。于是排行榜 / 权限组 / 条件事件这三类插件全都用不了
 * 这个插件最核心的两个成绩指标 —— 而这个缺陷在游戏内<b>完全看不出来</b>，
 * 只会在别人的插件里表现为「排行榜永远是空的」。
 *
 * <h2>本类钉住的三条不变量</h2>
 * <ol>
 *   <li><b>可解析性</b>：{@code _raw} 的输出必须能被 {@code Double.parseDouble} /
 *       {@code Integer.parseInt} 直接吃掉。这是「第三方能不能用」的唯一真实判据，
 *       不是「字符串看起来像数字」。</li>
 *   <li><b>locale 无关</b>：德语/法语默认 locale 下 {@code String.format("%.1f", 75.0)}
 *       产出 {@code "75,0"}，那个字符串在任何 locale 的 {@code parseDouble} 下都失败。
 *       故必须在 {@code Locale.GERMANY} 下跑一遍（R22-D11 在 {@code accuracy} 上踩过）。</li>
 *   <li><b>同门隐私</b>：{@code _raw} 与它的带符号原名是<b>同一份信息</b>。若只挡住
 *       {@code accuracy} 而漏掉 {@code accuracy_raw}，玩家关掉 showResults 后
 *       他人仍能从计分板读到他的正确率 —— 这是<b>绕过隐私保护</b>，不是格式问题。</li>
 * </ol>
 *
 * <h2>为什么不用「断言常量为 true」的写法</h2>
 * 本仓历史上多次出现「假绿」测试：用例自己复刻了生产代码的错误假设，于是永远绿。
 * 这里的每条断言都<b>真的调用</b> {@code onRequest} / {@code onPlaceholderRequest}
 * （即第三方插件实际走的入口），或<b>真的读</b>生产类的私有静态集合
 * （{@link #gatedSet()}），不存在「测试自己的替身对自己点头」的可能。
 *
 * <h2>调度器就地收集</h2>
 * {@code runTaskAsynchronously} 默认收进 {@link #asyncTasks} 由用例显式放行 ——
 * 真实线程池会让「第一轮是空串、补读之后才是数字」这个三态不可观测。
 */
@DisplayName("R-48：PAPI 纯数字占位符（_raw 别名 / duel_wins / review_due）")
class PapiRawPlaceholderTest {

    private static final UUID TARGET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID VIEWER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    /** 与 messages/zh.yml 的 {@code placeholder.private} 一致。 */
    private static final String PRIVATE = "已隐藏";

    private LetsLearnEnglish plugin;
    private PlayerDataStore store;
    private DatabaseManager db;
    private ConfigManager configManager;
    private MessageUtil messages;
    private AchievementManager achievements;
    private Server server;
    private BukkitScheduler scheduler;

    private OfflinePlayer offlineTarget;
    private Player target;
    private Player viewer;

    private PlayerDataStore.ProfileSettings profileSettings;
    private PlayerDataStore.PlayerStats stats;

    private final List<Runnable> asyncTasks = new ArrayList<>();
    private boolean runInline;

    private Locale previousDefaultLocale;
    private LLEPapiExpansion expansion;

    @BeforeEach
    void setUp() throws Exception {
        previousDefaultLocale = Locale.getDefault();
        // 断言必须与开发机默认 locale 无关；个别用例会自己改成 GERMANY（见方言用例）。
        Locale.setDefault(Locale.ROOT);
        asyncTasks.clear();
        runInline = false;

        plugin = mock(LetsLearnEnglish.class);
        store = mock(PlayerDataStore.class);
        db = mock(DatabaseManager.class);
        configManager = mock(ConfigManager.class);
        messages = mock(MessageUtil.class);
        achievements = mock(AchievementManager.class);
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);

        java.util.logging.Logger quietLogger =
                java.util.logging.Logger.getLogger("PapiRawPlaceholderTest");
        quietLogger.setLevel(java.util.logging.Level.OFF);

        when(plugin.getLogger()).thenReturn(quietLogger);
        when(plugin.getDatabaseManager()).thenReturn(db);
        when(plugin.getPlayerDataStore()).thenReturn(store);
        when(plugin.getMessageUtil()).thenReturn(messages);
        when(plugin.getAchievementManager()).thenReturn(achievements);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);

        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            Runnable task = inv.getArgument(1);
            if (runInline) {
                task.run();
            } else {
                asyncTasks.add(task);
            }
            return null;
        });

        // 语言回落：mainConfig 为 null → 一律 "zh"（离线目标的既有分支）。
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(null);
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");

        when(messages.getMessage("placeholder.private")).thenReturn(PRIVATE);
        when(messages.getPlayerMessage(any(Player.class), eq("placeholder.private")))
                .thenReturn(PRIVATE);

        // 隐私默认 = 新玩家默认值（showResults / showInSquare 均 true）。
        profileSettings = new PlayerDataStore.ProfileSettings();
        when(store.loadProfileSettingsForDisplay(any(UUID.class))).thenReturn(profileSettings);

        // 3 对 1 错 = 75.0%，与 accuracy 系列用例同一份夹具。
        stats = new PlayerDataStore.PlayerStats();
        stats.setScore(100L);
        stats.addCorrect(3);
        stats.addIncorrect(1);
        stats.setSessions(2);
        when(store.getPlayerStats(any(UUID.class))).thenReturn(stats);

        // 成就解锁数：3（与 stats 一样是基准夹具，个别用例会自己覆盖）。
        when(achievements.getUnlockedCount(TARGET_ID)).thenReturn(3);

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

    /** 第三方插件走的最常见入口（PAPI 只回传 target）。 */
    private String plain(String params) {
        return expansion.onRequest(offlineTarget, params);
    }

    /** 关系型入口的本人视角：{@code %rel_lle_<params>%}，viewer == target。 */
    private String self(String params) {
        return expansion.onPlaceholderRequest(target, target, params);
    }

    /** 关系型入口的他人视角：viewer != target。 */
    private String other(String params) {
        return expansion.onPlaceholderRequest(viewer, target, params);
    }

    private void drainAsync() {
        List<Runnable> pending = new ArrayList<>(asyncTasks);
        asyncTasks.clear();
        pending.forEach(Runnable::run);
    }

    /**
     * 反射读生产类的私有 {@code RESULTS_GATED}。
     *
     * <p>这里读的是<b>生产类本身的静态字段</b>（不是测试自己抄的一份名单），
     * 所以断言「某个键在集合里」检验的是真实门禁配置。字段改名会立即报错。
     */
    @SuppressWarnings("unchecked")
    private Set<String> gatedSet() throws Exception {
        Field f = LLEPapiExpansion.class.getDeclaredField("RESULTS_GATED");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    // ================================================ A. _raw 的可解析性

    @Test
    @DisplayName("A1：accuracy_raw 是能 Double.parseDouble 的纯数字，且与 accuracy 同值")
    void accuracyRawIsParseableAndAgreesWithSignedForm() {
        String raw = plain("accuracy_raw");
        assertNotNull(raw, "accuracy_raw 是已知占位符 → 不该返回 null（那是「不归我管」的契约）");

        // 真实判据：第三方插件就是这么消费它的。抛 NumberFormatException 即失败。
        double parsed = Double.parseDouble(raw);
        assertEquals(75.0, parsed, 0.05,
                "3 对 1 错 = 75.0%；实际 raw = \"" + raw + "\"");

        // 与带符号原名同值 —— 防止两个分支各自漂移（一个改了公式另一个没改）。
        String signed = plain("accuracy");
        double signedValue = Double.parseDouble(signed.replace("%", ""));
        assertEquals(signedValue, parsed, 0.05,
                "accuracy_raw (" + raw + ") 与 accuracy (" + signed
                        + ") 必须是同一份数据的两种格式，不能各自算一套");
    }

    @Test
    @DisplayName("A2：零答题时 accuracy_raw 是 \"0.0\"（不是 NaN / Infinity / 空串）")
    void accuracyRawHandlesZeroAnswers() {
        PlayerDataStore.PlayerStats empty = new PlayerDataStore.PlayerStats();
        when(store.getPlayerStats(any(UUID.class))).thenReturn(empty);

        String raw = plain("accuracy_raw");
        assertEquals("0.0", raw,
                "从没答过题 = 正确率无定义。返空串会让数值型排行榜把该玩家整行丢掉；"
                        + "\"0.0\" 与 accuracy 的 \"0.0%\" 同口径");
        assertEquals(0.0, Double.parseDouble(raw), 0.0);
    }

    @Test
    @DisplayName("A3：achievements_raw 是能 Integer.parseInt 的纯整数，且与 achievements 的分子一致")
    void achievementsRawIsParseableAndMatchesNumerator() {
        when(achievements.getUnlockedCount(TARGET_ID)).thenReturn(3);

        String raw = plain("achievements_raw");
        assertNotNull(raw, "achievements_raw 是已知占位符 → 不该返回 null");
        assertEquals(3, Integer.parseInt(raw),
                "解锁数本身；实际 raw = \"" + raw + "\"");

        // 两个占位符必须读同一个来源：分母来自 Achievement.values().length，
        // 这里只钉分子一致，免得用例把「22」硬编码成第二个真相源。
        String signed = plain("achievements");
        String numerator = signed.substring(0, signed.indexOf('/'));
        assertEquals(raw, numerator,
                "achievements_raw (" + raw + ") 必须等于 achievements (\"" + signed
                        + "\") 的分子 —— 否则两个占位符在互相矛盾");
        assertEquals(Achievement.values().length + "",
                signed.substring(signed.indexOf('/') + 1),
                "分母仍是成就总数（口径未改动）");
    }

    @Test
    @DisplayName("A5：accuracy_raw 的输出无后缀、无逗号（第三方 parse 的前提）")
    void accuracyRawHasNoSuffixOrComma() {
        // 满分边界先跑：它同时把「后缀"%"」与「逗号小数点」两种失效形态都暴露出来。
        // 注意每次换夹具后必须清外层 30s 缓存，否则第二次取值命中的是上一次的结果 ——
        // 本仓历史上正是这种「忘了清缓存」让用例变成空断言。
        PlayerDataStore.PlayerStats perfect = new PlayerDataStore.PlayerStats();
        perfect.addCorrect(10);
        when(store.getPlayerStats(any(UUID.class))).thenReturn(perfect);
        assertEquals("100.0", plain("accuracy_raw"), "10 对 0 错 = 100.0（无百分号）");

        clearOuterCache();
        when(store.getPlayerStats(any(UUID.class))).thenReturn(stats);

        String raw = plain("accuracy_raw");
        assertEquals("75.0", raw, "3 对 1 错 = 75.0");
        assertFalse(raw.contains("%"), "raw 不能带百分号 —— 那是 accuracy 的职责");
        assertFalse(raw.contains(","), "raw 不能有逗号 —— 逗号小数点会毁掉任何 locale 的解析");
        assertFalse(raw.isBlank(), "raw 不能是空/空白串 —— 数值型排行榜会丢行");
        Double.parseDouble(raw);
    }

    @Test
    @DisplayName("A4：所有纯数字占位符都能被 parse —— 这是「第三方能不能用」的唯一判据")
    void everyNumericPlaceholderParses() {
        when(db.getDuelStats(TARGET_ID)).thenReturn(new DatabaseManager.DuelStats(2, 1));
        // 让补读就地执行，好在同一个用例里观察到「空串 → 数字」的全过程
        // （若挂起异步任务，duel_wins 永远是空串，这条断言就失去意义）。
        runInline = true;

        // 逐个真的 parse 一遍。任何一个是 "%" / "/22" / 带逗号的小数都会在这里炸。
        Double.parseDouble(plain("accuracy_raw"));
        Integer.parseInt(plain("achievements_raw"));

        // duel_wins 是唯一带「还不知道」状态的：首轮空串，补读跑完后才是纯整数。
        assertEquals("", plain("duel_wins"), "首轮在途 → 空串（不是 0）");
        assertEquals(2, Integer.parseInt(plain("duel_wins")),
                "已确认的胜场数（含真实的 0）必须是一个纯整数");
    }

    // ================================================ B. Locale 无关

    @Test
    @DisplayName("B1：德语 locale 下 accuracy_raw 仍是 \"75.0\"（不是 \"75,0\"）")
    void accuracyRawIsLocaleIndependent() {
        // 修复前的形状：String.format("%.1f") 在 de_DE 下给出 "75,0"。
        // 那个字符串在 Double.parseDouble 下是 NumberFormatException ——
        // 也就是说漏掉 Locale.ROOT 时这个占位符会**彻底失效**，而不只是显示难看。
        Locale.setDefault(Locale.GERMANY);
        String raw = plain("accuracy_raw");
        assertEquals("75.0", raw,
                "raw 必须显式 Locale.ROOT；漏掉时德语环境给出 \"75,0\"，"
                        + "任何 locale 的 Double.parseDouble 都会抛异常");
        assertEquals(75.0, Double.parseDouble(raw), 0.0);

        Locale.setDefault(Locale.FRANCE);
        assertEquals("75.0", plain("accuracy_raw"), "法语 locale 同德语（逗号小数点）");
    }

    @Test
    @DisplayName("B2：德语 locale 下 accuracy 仍是 \"75.0%\"（R22-D11 未回退）")
    void accuracySignedFormStaysLocaleIndependent() {
        Locale.setDefault(Locale.GERMANY);
        assertEquals("75.0%", plain("accuracy"),
                "这是 R22-D11 原本就钉住的行为，本次改动不该让它回退");
    }

    // ================================================ C. 隐私门（真缺陷所在）

    @Test
    @DisplayName("C1：showResults=false 时 _raw 别名必须与带符号原名同门（否则绕过隐私）")
    void rawAliasesFollowTheResultsGate() {
        profileSettings.setShowResults(false);

        // 直接进入「目标已隐藏结果」的状态断言。不先跑一遍公开态再翻开关：
        // 那样外层 30s 缓存会留下公开值，必须靠反射清缓存才能让本用例有效 ——
        // 而「忘了清缓存 → 用例永远是绿的」正是本仓反复踩过的假绿形状。
        // 「公开态能读到真值」由 A1/A3 单独覆盖，两个方向各有各的用例。
        assertEquals(PRIVATE, other("accuracy_raw"),
                "玩家关掉 showResults 后，他人不得再通过 %lle_accuracy_raw% 读到他的正确率。"
                        + "_raw 与 accuracy 是同一份信息，只是格式不同 —— "
                        + "漏掉它就是一条真实可用的隐私旁路");
        assertEquals(PRIVATE, other("achievements_raw"),
                "同上：%lle_achievements_raw% 必须与 %lle_achievements% 同门");
        assertEquals(PRIVATE, other("duel_wins"),
                "决斗胜场是答题成绩的直接产物；产品内先例是他人主页在 hideResults 时"
                        + "整块战绩行不渲染（DuelStatsText）");
        assertEquals(PRIVATE, other("review_due"),
                "待复习词数属学习档案（同 words 一族），非本人查看隐藏结果时必须同门");

        // 对照：带符号原名同样被挡 —— 证明两个格式走的是同一道门，不是各判一遍。
        assertEquals(PRIVATE, other("accuracy"), "对照：accuracy 本来就在集合里");
        assertEquals(PRIVATE, other("achievements"), "对照：achievements 本来就在集合里");

        // 反向：把开关改回公开并清掉外层缓存后，_raw 必须立刻恢复真值。
        // 这一半证明上面那些 PRIVATE 是「门禁生效」而不是「值本来就是空的」——
        // 没有它，一个恒返回隐私文案的坏实现也能让本用例变绿。
        profileSettings.setShowResults(true);
        clearOuterCache();
        assertEquals("75.0", other("accuracy_raw"), "开关改回公开后必须恢复真值");
        assertEquals("3", other("achievements_raw"));
    }

    @Test
    @DisplayName("C2：本人视角穿过隐私门（修法不能把功能砍掉）")
    void rawAliasesAreReadableByTheOwner() {
        profileSettings.setShowResults(false);
        profileSettings.setShowInSquare(false);

        assertEquals("75.0", self("accuracy_raw"),
                "本人必须永远读得到自己的正确率；被自己挡住说明门禁接错了层");
        assertEquals("3", self("achievements_raw"));
    }

    @Test
    @DisplayName("C3：_raw 必须真的在 RESULTS_GATED 里（读生产类的静态集合，不是测试自己抄的名单）")
    void rawAliasKeysAreRegisteredInTheGateSet() throws Exception {
        Set<String> gated = gatedSet();

        assertTrue(gated.contains("accuracy_raw"),
                "accuracy_raw 不在 RESULTS_GATED 里 —— 门禁遍历的是这个集合，"
                        + "漏登记等于「代码写了隐私门，但这个键永远走不进去」。当前集合：" + gated);
        assertTrue(gated.contains("achievements_raw"),
                "achievements_raw 不在 RESULTS_GATED 里。当前集合：" + gated);
        assertTrue(gated.contains("duel_wins"),
                "duel_wins 不在 RESULTS_GATED 里。当前集合：" + gated);
        assertTrue(gated.contains("review_due"),
                "review_due 不在 RESULTS_GATED 里 —— 它今天恒返回空串，所以现在看不出问题；"
                        + "但等它接上真实数据源时，这条漏登记就变成一个静默的旁路。当前集合：" + gated);

        // 反向锚点：签到连击是「活跃度」不是「学习成绩」，必须仍然不在集合里。
        // 没有这条，上面几条可以靠「无脑把所有键都塞进去」满足 —— 而那会把
        // 「有没有每天来」也遮住，属过度隐藏。
        assertFalse(gated.contains("streak"),
                "签到连击刻意不入集合（见类内注释）：它不透露任何答题对错。"
                        + "若它出现在这里，说明有人为了让本用例变绿而把整个 switch 塞进集合了");
    }

    // ================================================ D. 新占位符的取值口径

    @Test
    @DisplayName("D1：duel_wins 首轮空串、补读后是胜场数（主线程不碰 DB）")
    void duelWinsIsBackfilledAsynchronously() {
        when(db.getDuelStats(TARGET_ID)).thenReturn(new DatabaseManager.DuelStats(4, 2));

        assertEquals("", plain("duel_wins"),
                "未命中必须是空串，不是 \"0\" —— 0 是「确实一场没赢过」的已确认结论，"
                        + "用它冒充「还没读到」等于发布一条编造的事实");
        assertEquals(1, asyncTasks.size(), "未命中应恰好排一次异步补读");

        drainAsync();
        assertEquals("4", plain("duel_wins"), "补读完成后拿到真实胜场数（不含败场）");
    }

    @Test
    @DisplayName("D2：duel_wins 在途窗口内只排一次读（计分板每秒刷新不会堆查询）")
    void duelWinsLoadIsDeduplicated() {
        when(db.getDuelStats(TARGET_ID)).thenReturn(new DatabaseManager.DuelStats(1, 0));

        for (int i = 0; i < 5; i++) {
            assertEquals("", plain("duel_wins"), "在途窗口内一律空串");
        }
        assertEquals(1, asyncTasks.size(),
                "同一玩家的 5 次刷新只能排 1 次读 —— 否则挂 20 人的计分板会把"
                        + "异步池堆满 duel_stats 的 COUNT 查询");
    }

    @Test
    @DisplayName("D3：duel_wins 确认 0 胜时输出 \"0\" 而不是空串（0 是结论，未知才是空串）")
    void confirmedZeroWinsRendersZero() {
        // runInline 必须先设：首个取值本身就是「未命中 → 排任务」，排的那个任务
        // 就地跑完才能让第二轮看到结论。顺序反过来会让首轮的空串被外层缓存冻住。
        runInline = true;
        when(db.getDuelStats(TARGET_ID)).thenReturn(new DatabaseManager.DuelStats(0, 0));

        assertEquals("", plain("duel_wins"), "首轮在途 → 空串");
        assertEquals("0", plain("duel_wins"),
                "查过了、确实一场没赢 —— 这是确定性结论，必须显示 0。"
                        + "返空串会让「零胜玩家」在排行榜上一直缺行");
    }

    @Test
    @DisplayName("D4：review_due 是已知占位符但恒为空串（宁可不显示，也不主线程查库）")
    void reviewDueIsKnownButAlwaysEmpty() {
        String value = plain("review_due");
        assertNotNull(value,
                "review_due 必须被 isKnownParams 认领 —— 返回 null 表示「不归本扩展管」，"
                        + "会把别的插件注册在同名 namespace 下的占位符静默吃掉");
        assertEquals("", value,
                "ReviewScheduler 没有「只读缓存、miss 不触发载入」的访问器，"
                        + "唯一现成的 getDueReviewWords 会同步查 word_progress 并顺手写回"
                        + "它自己的缓存。PAPI 跑在主线程，宁可空着也不能破「不碰 DB」");

        // 关键：它不该因为「返回空串」就偷偷排异步任务或读库 —— 那会把
        // 「刻意不实现」变成「实现了一半」。
        assertTrue(asyncTasks.isEmpty(), "review_due 不应排任何异步任务（它没有数据源）");
    }

    @Test
    @DisplayName("D5：新占位符未被误判为未知键（回归：isKnownParams 是唯一真相源）")
    void newKeysAreNotTreatedAsUnknown() {
        // 未知键的契约是 null（让别的 expansion 试）。这里逐个确认四个新键都已被认领，
        // 且与「真的未知」形成对照 —— 没有对照的话，「全是 null」也能让本用例变绿。
        assertNotNull(plain("accuracy_raw"));
        assertNotNull(plain("achievements_raw"));
        assertNotNull(plain("duel_wins"));
        assertNotNull(plain("review_due"));
        assertNull(plain("accuracy_raw_typo"),
                "对照：拼错的键必须仍是 null（不归我管），否则「已知」这个判定就是恒真的");
    }

    @Test
    @DisplayName("D6：combo_max 语义已有等价占位符 —— longest_streak 才是唯一真相源")
    void comboPeakIsServedByLongestStreak() {
        // 本用例钉的是「不新增重复占位符」这个决定：连击峰值的唯一入口是
        // %lle_longest_streak%（读 player_data.longest_streak，由
        // AchievementManager.checkStreakAchievement → persistLongestStreak 写入）。
        // 若将来有人为 AJLeaderboards 另开一个 %lle_combo_max%，这里会红 —— 那时应当
        // 复用本键（或加 _raw 别名），而不是再造一个读同一列的第二条路径。
        assertNull(plain("combo_max"),
                "combo_max 不应存在：连击峰值的真相源已经是 %lle_longest_streak%。"
                        + "两个占位符读同一列只会在将来某次改动里漂移成两个不同的数字");
    }

    /** 清空私有 {@code cache}（外层 30s 字符串缓存），让下一次取值必然重走 resolveRequest。 */
    @SuppressWarnings("unchecked")
    private void clearOuterCache() {
        try {
            Field f = LLEPapiExpansion.class.getDeclaredField("cache");
            f.setAccessible(true);
            ((java.util.Map<String, ?>) f.get(expansion)).clear();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("清不掉外层缓存（cache 被改名了？请同步本用例）", e);
        }
    }
}
