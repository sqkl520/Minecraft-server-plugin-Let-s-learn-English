package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round 31：{@code ReviewScheduler} 的<b>行为</b>测试（此前该类在测试里 {@code new} 了 0 次，
 * 行覆盖 1.3%，即只有类加载/构造器第一行被跑到）。
 *
 * <p><b>为什么必须测这类</b>：艾宾浩斯复习提醒是「玩家进服第一眼看到的东西」。
 * {@link ReviewScheduler#getDueReviewWords(UUID)} 里两道闸门（{@code lastReview <= 0} 哨兵、
 * {@code reviewCount >= reviewIntervalsSize} 档位兜底）都是**修过的缺陷的残留守卫**，
 * 但没有任何测试钉住；任何一次「顺手简化」都会把守卫删掉而全量测试仍绿。
 *
 * <p><b>夹具口径</b>：依赖只有 mock 的 {@code plugin} + mock {@code WordManagerImpl} +
 * mock {@code PlayerDataStore}——后者的 {@code getLastReviewTimes} / {@code getReviewCounts}
 * 是纯内存读（{@code Collections.unmodifiableMap(computeIfAbsent(...))}），所以这里返回
 * 可变 {@code HashMap} 实例即可，测内改 map 就等于「内存里数据变了」。
 *
 * <p><b>配置用真 {@code YamlConfiguration} 而不是 mock</b>：{@code mock(FileConfiguration.class)}
 * 的 {@code getIntegerList} 默认返回空列表、{@code getInt(key, def)} 默认返回 0，
 * 会把「配置读到了」和「配置没读到」两种情形糊成一种，本类第 6 节（空 intervals 的回落与告警）
 * 就会测成假绿。
 *
 * <p><b>第 6 节的夹具口径（Round 31 修正）</b>：旧用例用裸的 {@code new YamlConfiguration()}
 * 去构造「键缺失」，于是断言了「键缺失 → 静默降级」。这条断言在生产环境是<b>假的</b> ——
 * 真实的 {@code JavaPlugin#reloadConfig()} 会先 {@code setDefaults(getResource("config.yml"))}，
 * 出厂值 {@code [1, 6, 24, 72, 168]} 把缺失键兜住了（已用 paper-api 实测核实）。
 * 现在「键缺失」用例改用 {@code setDefaults(出厂 config.yml)} 复刻生产路径。
 */
@ExtendWith(MockitoExtension.class)
class ReviewSchedulerBehaviorTest {

    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private WordManagerImpl wordManager;
    private PlayerDataStore dataStore;
    private Logger logger;

    /** 真配置：键名与 {@code config.yml} L157-168 完全一致。 */
    private FileConfiguration mainConfig;

    /** mock 的 {@code getLastReviewTimes} / {@code getReviewCounts} 直接返回这两个实例。 */
    private Map<String, Long> lastReviewTimes;
    private Map<String, Integer> reviewCounts;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        wordManager = mock(WordManagerImpl.class);
        dataStore = mock(PlayerDataStore.class);
        logger = mock(Logger.class);

        mainConfig = new YamlConfiguration();
        setDefaultIntervals();

        lastReviewTimes = new HashMap<>();
        reviewCounts = new HashMap<>();

        when(plugin.getConfigManager()).thenReturn(configManager);
        // lenient：个别用例（intervals 键缺失）会换成另一份配置重打桩，
        // 严格桩会把 setUp 这一次判成 UnnecessaryStubbing。
        lenient().when(configManager.getMainConfig()).thenReturn(mainConfig);
        // 构造器只在「intervals 读成空表」时写 WARNING；多数用例走非空配置不会写日志，
        // 故 lenient 以免严格桩报未使用。
        lenient().when(plugin.getLogger()).thenReturn(logger);
        // thenAnswer 而不是 thenReturn：测内会往 map 里 put，必须每次都拿同一个活实例。
        lenient().when(dataStore.getLastReviewTimes(any())).thenAnswer(inv -> lastReviewTimes);
        lenient().when(dataStore.getReviewCounts(any())).thenAnswer(inv -> reviewCounts);
    }

    /** 与出厂 {@code config.yml} 一致：1h / 6h / 24h / 72h / 168h，mastered 30 天。 */
    private void setDefaultIntervals() {
        // 用可变 ArrayList 而不是 List.of：getIntegerList 会遍历存进去的对象，
        // 不押在 YamlConfiguration 对不可变集合的兼容性上。
        mainConfig.set("word-system.review.intervals",
                new java.util.ArrayList<>(List.of(1, 6, 24, 72, 168)));
        mainConfig.set("word-system.review.mastered-review-interval-days", 30);
        mainConfig.set("word-system.review.enabled", true);
    }

    private ReviewScheduler newScheduler() {
        return new ReviewScheduler(plugin, wordManager, dataStore);
    }

    private ReviewScheduler newSchedulerWithIntervals(List<Integer> intervals) {
        // 拷进可变 ArrayList：不依赖 YamlConfiguration 对不可变列表/set(null) 的处理细节。
        mainConfig.set("word-system.review.intervals", new java.util.ArrayList<>(intervals));
        return newScheduler();
    }

    private long now() {
        return System.currentTimeMillis();
    }

    /**
     * {@code assertDoesNotThrow} 的<b>显式目标类型</b>包装。
     *
     * <p>直接写 {@code List<String> due = assertDoesNotThrow(() -> f(), "msg")} 会撞上
     * JUnit 的 {@code Executable} / {@code ThrowingSupplier} 双重载（隐式 lambda 同时
     * void 兼容与值兼容）。这里形参已声明为 {@link ThrowingSupplier}，调用点的 lambda
     * 目标类型因此唯一，不存在重载歧义。
     */
    private static <T> T noThrow(ThrowingSupplier<T> supplier, String message) {
        return assertDoesNotThrow(supplier, message);
    }

    // ==================================================================
    // 1. lastReview <= 0 哨兵：从未复习的词不得永久 due
    // ==================================================================

    @Test
    @DisplayName("lastReviewTime=0/-1（从未排程哨兵）→ 永不 due；对照组证明循环确实跑了")
    void neverScheduledWordIsNeverDue() {
        // 0 = DB 里「未作答」的空行（schema.sql:37 last_review_time BIGINT 可 NULL，
        // rs.getLong 读成 0），不是 1970 年时间戳。删掉守卫后 0 + intervals[0] 必然 << now
        // → 该词永久 due → 玩家进服即被刷屏。
        lastReviewTimes.put("apple", 0L);
        reviewCounts.put("apple", 0);
        lastReviewTimes.put("plum", -1L);
        reviewCounts.put("plum", 0);

        // 对照组（防「闸门未触及」假绿）：真有种子且已过 1h 档的词必须 due。
        // 若整个循环没跑，下面这条会红，而不是让本用例凭空调绿。
        lastReviewTimes.put("pear", now() - 2 * HOUR);
        reviewCounts.put("pear", 0);

        List<String> due = newScheduler().getDueReviewWords(UUID.randomUUID());

        assertFalse(due.contains("apple"),
                "lastReviewTime=0（从未排程哨兵）被算成 due 了 —— 守卫 lastReview<=0 失效，"
                        + "每个从未复习的词都会永久 due");
        assertFalse(due.contains("plum"), "负值时间戳同样必须被哨兵守卫挡掉");
        assertTrue(due.contains("pear"),
                "对照组失败：2 小时前复习、档位 0（1h）的词本应 due。"
                        + "本条红说明循环根本没跑，上面两条 assertFalse 是假绿");
    }

    // ==================================================================
    // 2. reviewCount 超出配置档位数：不得数组越界
    // ==================================================================

    @Test
    @DisplayName("reviewCount >= 档位数 → 走 mastered 30 天兜底，不越界")
    void reviewCountBeyondConfiguredIntervalsFallsBackToMasteredPool() {
        ReviewScheduler scheduler = newScheduler();

        // 出厂 5 个档位（索引 0..4）。recordReview 每答对一次 +1 且无上限
        // （PlayerDataStore:1721 merge(wordLower, 1, Integer::sum)），
        // 所以第 6 次复习后 reviewCount=5 —— 直接下标 reviewIntervalsMs[5] 就是 AIOOBE。
        lastReviewTimes.put("apple", now() - 31 * DAY);
        reviewCounts.put("apple", 5);

        List<String> due = noThrow(
                () -> scheduler.getDueReviewWords(UUID.randomUUID()),
                "reviewCount=5 撞上 5 个档位就抛了 —— mastered 分支守卫没了，生产上是 ArrayIndexOutOfBounds");

        assertTrue(due.contains("apple"),
                "31 天前 + 已过全部档位 → 必须进 mastered 池并按 30 天周期到期");

        // 「没到期」对照组：29 天前不该 due。这条同时钉住「用的是 30 天 mastered 周期」
        // 而不是回落到某个更短的档位（若有人把 mastered 分支改成 intervals[size-1]=168h，
        // 29 天前的词就会 due，本条立即红）。
        lastReviewTimes.put("kiwi", now() - 29 * DAY);
        reviewCounts.put("kiwi", 5);
        assertFalse(scheduler.getDueReviewWords(UUID.randomUUID()).contains("kiwi"),
                "29 天 < 30 天 mastered 周期，不该 due —— 说明 mastered 周期取错了");

        // 远超标（防御）：并发/脏数据下 reviewCount 可能很大，同样不许越界。
        lastReviewTimes.put("melon", now() - 400 * DAY);
        reviewCounts.put("melon", 9999);
        List<String> farOut = noThrow(
                () -> scheduler.getDueReviewWords(UUID.randomUUID()),
                "reviewCount=9999 越界了 —— 兜底是 `>= size` 而不是 `== size - 1` 才对");
        assertTrue(farOut.contains("melon"), "远超档位的词仍应落到 mastered 池并 due");
    }

    // ==================================================================
    // 3. 正常艾宾浩斯算术：到期两侧各一条
    // ==================================================================

    @Test
    @DisplayName("档位 0 = 1h：61 分钟前 due，59 分钟前不 due")
    void firstIntervalBoundary() {
        ReviewScheduler scheduler = newScheduler();

        lastReviewTimes.put("apple", now() - TimeUnit.MINUTES.toMillis(61));
        reviewCounts.put("apple", 0);
        assertTrue(scheduler.getDueReviewWords(UUID.randomUUID()).contains("apple"),
                "过了 1h 档（61 分钟前）却没 due");

        lastReviewTimes.put("pear", now() - TimeUnit.MINUTES.toMillis(59));
        reviewCounts.put("pear", 0);
        assertFalse(scheduler.getDueReviewWords(UUID.randomUUID()).contains("pear"),
                "还没到 1h 档（59 分钟前）就 due 了 —— 复习提醒会过于频繁");
    }

    @Test
    @DisplayName("档位 4 = 168h(7d)：8 天前 due，6 天前不 due")
    void fifthIntervalBoundary() {
        ReviewScheduler scheduler = newScheduler();

        lastReviewTimes.put("apple", now() - 8 * DAY);
        reviewCounts.put("apple", 4);
        assertTrue(scheduler.getDueReviewWords(UUID.randomUUID()).contains("apple"),
                "过了 168h 档（8 天前）却没 due");

        lastReviewTimes.put("pear", now() - 6 * DAY);
        reviewCounts.put("pear", 4);
        assertFalse(scheduler.getDueReviewWords(UUID.randomUUID()).contains("pear"),
                "还没到 168h 档（6 天前）就 due 了");
    }

    @Test
    @DisplayName("reviewCount 缺失（lastReviewTimes 有键、reviewCounts 无键）→ 跳过，不 NPE")
    void missingReviewCountIsSkipped() {
        // DB 读失败/半加载时可出现 only-one-map 的状态（两段 fillMissing 不是原子的）。
        lastReviewTimes.put("apple", now() - 100 * DAY);
        // 故意不 put reviewCounts —— 旧实现在这里对 Integer 拆箱就 NPE。
        lastReviewTimes.put("pear", now() - 2 * HOUR);
        reviewCounts.put("pear", 0);

        List<String> due = noThrow(
                () -> newScheduler().getDueReviewWords(UUID.randomUUID()),
                "reviewCounts 缺键时 NPE 了 —— null 检查（L110-112）被删");
        assertFalse(due.contains("apple"), "reviewCount 缺失的词不该被判 due（无从算间隔）");
        assertTrue(due.contains("pear"), "对照组：完整的词仍须正常判定");
    }

    // ==================================================================
    // 4. 缓存 / 失效 / 清理
    // ==================================================================

    @Test
    @DisplayName("30 秒 TTL 内命中缓存；unloadPlayer 后强制重算")
    void dueWordCacheServesWithinTtlAndUnloadInvalidates() {
        UUID id = UUID.randomUUID();
        ReviewScheduler scheduler = newScheduler();

        lastReviewTimes.put("apple", now() - 2 * HOUR);
        reviewCounts.put("apple", 0);
        assertTrue(scheduler.getDueReviewWords(id).contains("apple"));

        // 数据改了，但仍在 TTL 窗口内 → 应命中缓存（复习页/学习功能会连续调多次）
        lastReviewTimes.put("apple", now());
        assertTrue(scheduler.getDueReviewWords(id).contains("apple"),
                "30 秒 TTL 内应命中缓存，不该每次重算");

        // quit 清理钩（PlayerJoinListener.onPlayerQuit 经 wordManager.getReviewScheduler 调用）
        scheduler.unloadPlayer(id);
        assertFalse(scheduler.getDueReviewWords(id).contains("apple"),
                "unloadPlayer 没清 due 词缓存 —— 退服玩家的条目会一直留到 TTL 到期");
    }

    @Test
    @DisplayName("recordReview 落库并清缓存（否则刚答完的词仍被提醒）")
    void recordReviewDelegatesAndInvalidatesCache() {
        UUID id = UUID.randomUUID();
        ReviewScheduler scheduler = newScheduler();

        lastReviewTimes.put("apple", now() - 2 * HOUR);
        reviewCounts.put("apple", 0);
        assertTrue(scheduler.getDueReviewWords(id).contains("apple"));

        lastReviewTimes.put("apple", now());
        scheduler.recordReview(id, "apple");

        verify(dataStore).recordReview(id, "apple");
        assertFalse(scheduler.getDueReviewWords(id).contains("apple"),
                "recordReview 没清缓存 —— 玩家刚复习完还会继续收到该词的提醒");
    }

    // ==================================================================
    // 5. checkAndNotifyReviews 任务体内门（L51-55，两道门都在摸 Bukkit 之前返回）
    // ==================================================================

    @Test
    @DisplayName("word 总开关关 → 任务体 no-op，不读玩家复习数据")
    void wordSystemDisabledShortCircuits() {
        // 默认 false（mock boolean 默认值）；这就是 config 里 word-system.enabled: false
        newScheduler().checkAndNotifyReviews();
        verify(dataStore, never()).getLastReviewTimes(any());
        verify(dataStore, never()).getReviewCounts(any());
    }

    @Test
    @DisplayName("复习子开关关 → 任务体 no-op（reload 后能关干净）")
    void reviewToggleDisabledShortCircuits() {
        when(configManager.isWordSystemEnabled()).thenReturn(true);
        mainConfig.set("word-system.review.enabled", false);

        newScheduler().checkAndNotifyReviews();
        verify(dataStore, never()).getLastReviewTimes(any());
        verify(dataStore, never()).getReviewCounts(any());
    }

    // ==================================================================
    // 6. 空 intervals 配置：修复后的行为（WARNING + 回落出厂档位）
    // ==================================================================

    /**
     * <b>修复后的实测行为</b>：{@code intervals: []} 时回落内置默认档位
     * {@code [1, 6, 24, 72, 168]}，并按该键名 WARNING 一次。
     *
     * <p>为什么这是唯一需要修的路径：{@code YamlConfiguration#getIntegerList} 在
     * <b>键缺失</b>时会回落到 defaults（生产上 {@code JavaPlugin#reloadConfig()} 先
     * {@code setDefaults(getResource("config.yml"))}，出厂值把这一半兜住了，见下面
     * {@code missingIntervalsKeyFallsBackToBundledDefaults}）；只有服主<b>显式</b>写空表时
     * defaults 救不了 —— 键在、值就是空列表。
     *
     * <p>本条钉住两件事，缺一即红：
     * <ol>
     *   <li>2 小时前的词必须按默认 1h 档 due（回落生效，复习没有静默失效）；</li>
     *   <li>WARNING 消息里必须原样出现键名（服主能看见配置错了）。</li>
     * </ol>
     * 只做第 1 条 = 配置错误被掩盖；只做第 2 条 = 告警了但功能还是坏的。
     */
    @Test
    @DisplayName("intervals 显式为空 → 回落出厂档位继续复习，并 WARNING 点名该键")
    void emptyIntervalsFallsBackToBuiltInDefaultsAndWarns() {
        ReviewScheduler scheduler = newSchedulerWithIntervals(List.of());

        lastReviewTimes.put("apple", now() - 2 * HOUR);
        reviewCounts.put("apple", 0);
        assertTrue(scheduler.getDueReviewWords(UUID.randomUUID()).contains("apple"),
                "空 intervals 配置下 2 小时前的词必须 due（应回落到内置默认 1h 档），"
                        + "而不是被静默换成 30 天周期");

        // 对照组：证明回落的是真档位表，而不是「全都不 due / 全都 due」。
        // 30 分钟前 < 1h 档 → 不该 due；31 天前 + 已过 5 档 → 落 mastered 池 → due。
        lastReviewTimes.put("pear", now() - TimeUnit.MINUTES.toMillis(30));
        reviewCounts.put("pear", 0);
        assertFalse(scheduler.getDueReviewWords(UUID.randomUUID()).contains("pear"),
                "30 分钟前不该 due —— 若这里 due，说明回落成了比 1h 更短的档位");

        lastReviewTimes.put("melon", now() - 31 * DAY);
        reviewCounts.put("melon", 5);
        assertTrue(scheduler.getDueReviewWords(UUID.randomUUID()).contains("melon"),
                "31 天前 + 已过 5 个默认档位 → 仍应落 mastered 池并 due");

        verify(logger, atLeastOnce()).warning(contains("word-system.review.intervals"));
    }

    /**
     * <b>生产上「键缺失」不是坏路径</b>：真实的 {@code JavaPlugin#reloadConfig()} 会先
     * {@code setDefaults(getResource("config.yml"))}，于是 {@code getIntegerList} 对缺失键
     * 回落到出厂值 {@code [1, 6, 24, 72, 168]}。
     *
     * <p>本条夹具用 {@link YamlConfiguration#setDefaults} 复刻这条生产路径 —— <b>不能</b>用裸的
     * {@code new YamlConfiguration()}，那正是让旧用例断言「键缺失 = 静默降级」这种非生产行为的
     * 根因（裸配置没有任何 defaults，缺失键读成空表，生产上根本不会出现这个状态）。
     *
     * <p>断言：键缺失 → 读到出厂 5 档 → 2 小时前的词按 1h 档 due，且<b>不该</b>发 WARNING
     * （配置没错，只是老 config.yml 没合并新键；出厂值已兜住，无需打扰服主）。
     */
    @Test
    @DisplayName("intervals 键缺失（生产 defaults 兜住）→ 读到出厂 5 档，2 小时前的词 due，无 WARNING")
    void missingIntervalsKeyFallsBackToBundledDefaults() {
        // 建一份**没有**该键的配置，但把出厂 config.yml 设成 defaults —— 生产 reloadConfig 的等价复刻。
        YamlConfiguration userConfig = new YamlConfiguration();
        userConfig.set("word-system.review.mastered-review-interval-days", 30);

        // 自证前置条件 1：设置 defaults 之前，这份配置本地确实没有该键
        // （放在 setDefaults 之前才有意义 —— 之后读会经 defaults 兜住，测不出「本地缺失」）
        assertTrue(userConfig.getIntegerList("word-system.review.intervals").isEmpty(),
                "夹具坏了：设置 defaults 之前这份配置里竟然已经有 intervals");
        assertNull(userConfig.getString("word-system.review.intervals"),
                "夹具坏了：设置 defaults 之前这份配置里竟然已经有 intervals 键");

        // 复刻生产 JavaPlugin#reloadConfig()：先 setDefaults(出厂 config.yml)
        userConfig.setDefaults(loadBundledMainConfig());
        // 自证前置条件 2：defaults 确实兜住了，读出来正是出厂 5 档
        assertEquals(List.of(1, 6, 24, 72, 168),
                userConfig.getIntegerList("word-system.review.intervals"),
                "夹具坏了：defaults 没兜住缺失键 —— 生产 reloadConfig 会 setDefaults(config.yml)");

        mainConfig = userConfig;
        when(configManager.getMainConfig()).thenReturn(mainConfig);

        ReviewScheduler scheduler = newScheduler();

        lastReviewTimes.put("apple", now() - 2 * HOUR);
        reviewCounts.put("apple", 0);
        assertTrue(scheduler.getDueReviewWords(UUID.randomUUID()).contains("apple"),
                "键缺失但 defaults 兜住 5 档 → 2 小时前的词必须按 1h 档 due"
                        + "（生产上就是这条路径，不该退化成 30 天池）");

        lastReviewTimes.put("pear", now() - TimeUnit.MINUTES.toMillis(30));
        reviewCounts.put("pear", 0);
        assertFalse(scheduler.getDueReviewWords(UUID.randomUUID()).contains("pear"),
                "30 分钟前不该 due —— 说明默认档位确实被读到了");

        // 配置没错（只是老 config.yml 没合并新键，出厂值已兜住）→ 不该打扰服主。
        verify(logger, never()).warning(anyString());
    }

    /**
     * 读 JAR 内的出厂 {@code config.yml}（与生产 {@code JavaPlugin#getResource("config.yml")}
     * 同一个文件），用作 {@link YamlConfiguration#setDefaults} 的 defaults —— 复刻
     * {@code JavaPlugin#reloadConfig()} 的真实做法。
     */
    private static FileConfiguration loadBundledMainConfig() {
        InputStream in = ReviewSchedulerBehaviorTest.class.getClassLoader()
                .getResourceAsStream("config.yml");
        assertNotNull(in, "bundled config.yml missing from test classpath");
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
