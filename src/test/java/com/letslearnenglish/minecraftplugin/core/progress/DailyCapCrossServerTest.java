package com.letslearnenglish.minecraftplugin.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * {@code money.daily-cap} 的<b>多子服语义</b>：日限账本是「按进程」而不是「按玩家」。
 *
 * <h2>本类要回答的问题</h2>
 * {@link RewardEngine} 的日限账本是两层结构：
 * <ol>
 *   <li>{@code todayCoinsCache}（进程内 {@code Map<UUID, DailyCoins>}，一个 RewardEngine 一份）；</li>
 *   <li>{@code daily_coin_counters} 表（{@code (player_uuid, coin_date)} 唯一，写穿真源）。</li>
 * </ol>
 * 读路径 {@code getTodayCoinsEarnedCents} <b>只在内存 miss 或跨日时</b>才回读 DB；
 * 写路径 {@code addDailyCoinsToDb} 是<b>增量</b> UPSERT（{@code cents = cents + ?}）。
 *
 * <p>于是多台子服共享同一 MySQL 时，每台服各持一份独立的 {@code todayCoinsCache}：
 * 玩家在 A 服花掉的额度，<b>B 服的 cache 里没有</b>。只要 B 服对这名玩家今天已有条目
 * （= 玩家今天早些时候在 B 服玩过），B 就不会回读 DB，日限实际额度变成 {@code N × cap}。
 *
 * <h2>为什么这个测试确实能证明它</h2>
 * 造<b>两个</b> {@link RewardEngine} 实例，各配一个独立的 {@link DatabaseManager}
 * 指向<b>同一个</b> SQLite 文件（{@code @TempDir} 下的同一个 {@code .db}）。
 * 两份 {@code DatabaseManager} = 两条独立 JDBC 连接 = 「两个进程」的最小可观测等价物；
 * 两份 {@code RewardEngine} = 两份互不可见的 {@code todayCoinsCache}。
 * 唯一的共享物是钱（同一个 Vault 经济后端，多服网络的常态）与库。
 *
 * <p>本类<b>不</b>用 mock 顶替数据层：判据是「Vault 侧真实入账合计」与「库里真实行」，
 * 这正是怀疑对象所在，mock 掉就等于假设结论成立。
 *
 * <h2>已证伪的两种「其实安全」猜想</h2>
 * <ol>
 *   <li><b>「{@code addDailyCoinsToDb} 是读-改-写，会丢更新」——证伪。</b>
 *       它是单条增量 UPSERT，两个「进程」各写各的增量、库侧正确求和。
 *       见 {@link #theLedgerItselfConvergesBecauseTheWriteIsIncremental()}：
 *       该用例是绿的，且修复后仍应保持绿。</li>
 *   <li><b>「DB 回读路径本身有问题」——证伪。</b>
 *       当 B 服的 cache <b>真的</b> miss 时（B 今天没见过这名玩家），它从 DB 读到 A 写的
 *       额度并正确拦住。见 {@link #aCacheMissOnTheSecondServerStillEnforcesTheCap()}。
 *       所以缺陷<b>只在</b>「cache hit 且命中值陈旧」这一条路径上，不是「DB 不共享」。</li>
 * </ol>
 *
 * <h2>Round 30 修复后：本类必须显式启用「多进程判据」</h2>
 * 生产判据是 {@code DatabaseManager.isSQLite()} 的<b>反</b>（见
 * {@code RewardEngine.usesSharedDailyLedger} 的 javadoc：SQLite 单文件结构上只有一个
 * 进程，MySQL 可能有第二个）。而本类为了能在纯 JVM 里跑，必然用 SQLite —— 于是
 * {@code isSQLite() == true}，生产判据会把它判成<b>单进程</b>，那条新路径永远走不到。
 * 故每条用例前调 {@code setSharedDailyLedger(true)}（包级可见的测试缝，与
 * {@code setClock} 同一姿态），让「两个进程」这个前提真的生效。
 *
 * <p><b>这个缝本身也被钉住了</b>：{@link #defaultLedgerModeIsSingleProcessOnSqlite()}
 * 断言未强制时 SQLite 走内存判据 —— 否则"忘了把开关接到判据上"会让本类全绿而生产
 * 行为完全没变（假绿的典型形态）。
 *
 * <h2>本类的绿/红划分（Round 30 之后）</h2>
 * 缺陷本体那条用例已摘掉 {@code @Disabled}（修复落地后它就是回归用例）。
 * 另外三条钉的是「修复后仍必须成立」的事实，不会因修完而变红。
 * <b>刻意不写「断言现状 = 900 金币」的绿测试</b> —— 那会把缺陷固化成契约。
 */
@DisplayName("money.daily-cap 的多子服语义：按进程记账 vs 按玩家记账")
class DailyCapCrossServerTest {

    /** 被测源码（lint 断言的是<b>形状</b>，所以必须读真实文件，不能读 class）。 */
    private static final Path ENGINE_SRC = Path.of(
            "src/main/java/com/letslearnenglish/minecraftplugin/core/progress/RewardEngine.java");

    /** 与 {@code database.mysql.table-prefix} 默认值一致。 */
    private static final String PREFIX = "lle_";

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000c50f");

    /** 默认日限（金币）。 */
    private static final int CAP = 500;

    private static final Logger QUIET = quietLogger();

    /**
     * plugin mock 整类复用：{@code LetsLearnEnglish} 继承链很深，Mockito 造一个约十余秒。
     * 两个「进程」各一个 = 每用例二十余秒，三个执行用例就一分多钟 —— 与
     * {@code RewardEngineDailyCapTest} 的 {@code @BeforeAll} 同一条理由。
     * 每用例重新打桩 {@code getDataFolder} / {@code getDatabaseManager} 即可，
     * 这两个正是每用例必须换的东西。
     */
    private static LetsLearnEnglish pluginA;
    private static LetsLearnEnglish pluginB;

    private FileConfiguration config;
    private ConfigManager configManager;
    private MessageUtil messageUtil;
    private PlayerDataStore dataStore;
    private Player player;
    private Economy economy;

    /** Vault 侧真实余额（分）。多服共享同一个经济后端，故只有一个计数器。 */
    private final AtomicLong vaultCents = new AtomicLong();
    private final AtomicInteger depositCalls = new AtomicInteger();

    /** 模拟 Vault provider 拒付（补偿逻辑的守门人用例用）。 */
    private volatile boolean failDeposits;

    // ---- 两个「服务端进程」 ------------------------------------------------
    private DatabaseManager dbA;
    private DatabaseManager dbB;
    private RewardEngine engineA;
    private RewardEngine engineB;

    @BeforeAll
    static void createSharedPluginMocks() {
        pluginA = mock(LetsLearnEnglish.class);
        pluginB = mock(LetsLearnEnglish.class);
        when(pluginA.getLogger()).thenReturn(QUIET);
        when(pluginB.getLogger()).thenReturn(QUIET);
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        vaultCents.set(0L);
        depositCalls.set(0);
        failDeposits = false;

        // 真 YamlConfiguration：getInt/getBoolean 的默认值必须真的生效，
        // 否则 money.daily-cap 被 mock 成 0（= 不限），整类测不到任何东西。
        config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        // 同一个文件名 + 同一个 dataFolder（@TempDir）= 同一个 SQLite 文件 = 同一个「库」。
        config.set("database.sqlite.filename", "cross-server-daily-cap.db");
        config.set("database.mysql.table-prefix", PREFIX);
        config.set("progress-system.rewards.types.money.enabled", true);
        config.set("money.daily-cap", CAP);
        config.set("word-system.daily-limit", 50);

        configManager = mock(ConfigManager.class);
        when(configManager.getMainConfig()).thenReturn(config);
        when(configManager.isRewardsEnabled()).thenReturn(true);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("CrossServerTester");

        messageUtil = mock(MessageUtil.class);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("MSG");

        dataStore = mock(PlayerDataStore.class);
        when(dataStore.getTodayAnswerCount(any(UUID.class))).thenReturn(0);

        economy = trackingEconomy();

        // 进程 A：先桩好每用例专属的依赖（dataFolder 每用例不同 —— @TempDir），
        // 再造 DatabaseManager（构造期就要读 dataFolder 算 nodeId），最后回填 db。
        // 这是本项目 DI 的既有形状。
        stubPerTestDeps(pluginA, dir);
        dbA = new DatabaseManager(pluginA, configManager);
        dbA.initialize();
        when(pluginA.getDatabaseManager()).thenReturn(dbA);
        engineA = new RewardEngine(pluginA);

        // 进程 B：同一个目录、同一个文件名 → 同一个库；但 cache 是全新的一份。
        stubPerTestDeps(pluginB, dir);
        dbB = new DatabaseManager(pluginB, configManager);
        dbB.initialize();
        when(pluginB.getDatabaseManager()).thenReturn(dbB);
        engineB = new RewardEngine(pluginB);

        // Round 30：本类的"两个进程"是用<b>两个 SQLite 连接</b>模拟的，而生产判据
        // （isSQLite() 的反）会把它判成单进程。不强制这一步，被测的跨进程预占路径
        // 一行都不会执行，摘掉 @Disabled 也是绿的 —— 那就是假绿。
        engineA.setSharedDailyLedger(true);
        engineB.setSharedDailyLedger(true);
    }

    @AfterEach
    void tearDown() {
        if (dbA != null) {
            dbA.shutdown();
        }
        if (dbB != null) {
            dbB.shutdown();
        }
    }

    // ==================================================================
    // 缺陷本体（期望行为，摘掉 @Disabled 即红）
    // ==================================================================

    /**
     * <b>缺陷本体（Round 30 已修，现为回归用例）</b>：玩家 P 当天先后经过 B、A 两台
     * 子服，总额必须<b>不越过</b> {@code money.daily-cap}。
     *
     * <h2>修复前的失败时间线（{@code money.daily-cap: 500}）</h2>
     * <table border="1">
     *   <caption>跨服日限失守</caption>
     *   <tr><th>时刻</th><th>事件</th><th>B 服 cache</th><th>A 服 cache</th><th>DB 行</th><th>已发合计</th></tr>
     *   <tr><td>09:00</td><td>P 在 B 答一题，请求 100</td><td>10000</td><td>无条目</td><td>10000</td><td>100</td></tr>
     *   <tr><td>09:10</td><td>P 换到 A，请求 400</td>
     *       <td>10000</td><td>10000（<b>miss → 读 DB</b>）</td><td>50000</td><td>500</td></tr>
     *   <tr><td>09:20</td><td>P 回到 B，请求 400</td>
     *       <td>50000（<b>hit，未读 DB</b>）</td><td>50000</td><td>90000</td><td><b>900</b></td></tr>
     * </table>
     *
     * <p>09:20 那次判定在修复前是<b>cache hit</b>：{@code todayCoinsCache} 里 P 的条目
     * {@code date == today}，{@code getTodayCoinsEarnedCents} 的
     * {@code entry == null || !today.equals(entry.date)} 整个不成立，于是
     * {@code readDailyCoinsFromDb} 根本不被调用，B 拿着 09:00 的 10000 分算出
     * 「还剩 400」并实付 400。
     *
     * <p><b>为什么 B 的条目还在</b>：{@code unloadPlayer}（quit 语义）刻意<b>不</b>清
     * {@code todayCoinsCache}（清了就等于下线重进可刷额度，见该方法的 javadoc）。
     * 因此「P 离开 B 服」不会让条目不新鲜；唯一会让它失效的是
     * {@code /lea reload}（{@code resetDailyCoinsCache}）或跨自然日。
     *
     * <p><b>Round 30 的修法</b>：多进程判据下（{@link #setSharedDailyLedger} 强制），
     * 发币前先走 {@code reserveDailyCoinsInDb} —— 一次条件 UPDATE
     * （{@code WHERE cents = ?旧值}）把"够不够"交给库回答，affected rows 决定谁赢。
     * 于是 09:20 那次 B 的条件 UPDATE 落败（库里的值已被 A 改成 50000）→ 一分不发。
     *
     * <p><b>变异判别</b>：把 {@code clampToDailyCap} 里的多进程分支整段删掉
     * （退回纯内存判据）→ 本用例必红，实发变成 400。
     */
    @Test
    @DisplayName("跨两台子服：同一天先后经过，日限总额不得越过 cap（Round 30 回归）")
    void crossServerDailyCapIsNotEnforcedAcrossProcesses() {
        // 09:00 —— P 在 B 服拿到 100，B 的 cache 由此建立今天的条目。
        assertEquals(100.0, engineB.depositCappedReward(player, 100.0), 1e-9,
                "前置：B 服的第一笔应正常发放");
        assertEquals(10000L, dbDailyCents(dbB), "前置：DB 应有 B 服写入的 100.00");
        assertEquals(10000L, vaultCents.get(), "前置：Vault 已到账 100.00");

        // 09:10 —— P 换到 A 服。A 的 cache 今天没见过 P，是真正的 miss → 回读 DB 得 100。
        // 所以 A 手里只剩 400 额度，这一笔正好用满 —— 到这里为止一切正确。
        assertEquals(400.0, engineA.depositCappedReward(player, 400.0), 1e-9,
                "A 服应从 DB 读到 B 服记的 100，只剩 400 额度");
        assertEquals(50000L, dbDailyCents(dbA), "此时 DB 恰好等于 cap：500.00");
        assertEquals(50000L, vaultCents.get(), "此时合计恰好 500.00，日限成立");

        // 09:20 —— P 回到 B 服（未重启、未 /lea reload）。B 的条目仍是今天的 → hit。
        double paidOnReturn = engineB.depositCappedReward(player, 400.0);

        // 期望：B 必须重新看到 DB 里的 500（已用满），一分都不再发。
        assertEquals(0.0, paidOnReturn, 1e-9,
                "跨服回来后日限必须仍是「本玩家今天总额 500」，而不是「本进程今天总额 500」喵。"
                        + "实际实发：" + paidOnReturn + "（说明 B 服按自己 09:00 记的 100 在算）");
        assertEquals(50000L, vaultCents.get(),
                "当日实发合计必须停在 500.00，实际：" + (vaultCents.get() / 100.0)
                        + " 金币 —— 越过 500 就是多发了真钱");
    }

    // ==================================================================
    // 绿：修复后仍必须成立的事实（不会因修完而变红）
    // ==================================================================

    /**
     * 证伪猜想「{@code addDailyCoinsToDb} 是读-改-写所以会丢更新」。
     *
     * <p>写路径是单条增量 UPSERT（{@code cents = cents + ?}），两个「进程」各写各的增量，
     * 库侧求和正确。所以<b>库里的账是对的</b>，错的只有读路径的缓存。
     * 这条区分很重要：它决定了修法是「让读路径看库」（(b) 每次回读 / (a) 把判定下推到库），
     * 而不是「修写路径」。
     */
    @Test
    @DisplayName("日限账本的写是真增量：两个「进程」各写各的，库里正确求和（不丢更新）")
    void theLedgerItselfConvergesBecauseTheWriteIsIncremental() {
        assertEquals(100.0, engineB.depositCappedReward(player, 100.0), 1e-9);
        assertEquals(400.0, engineA.depositCappedReward(player, 400.0), 1e-9);

        assertEquals(50000L, dbDailyCents(dbA),
                "A 服连接读到的今日累计应是 100 + 400 = 500.00（增量 UPSERT 求和）喵");
        assertEquals(50000L, dbDailyCents(dbB),
                "B 服连接读到的必须是同一条行、同一个值 —— 证明两个 DatabaseManager 真的"
                        + "共享同一个库（这是本类全部用例的前提）");
    }

    /**
     * 证伪猜想「DB 回读路径本身有问题 / 多服根本不共享额度」。
     *
     * <p>当 B 服今天<b>从未见过</b>这名玩家时，{@code todayCoinsCache} 真 miss，
     * {@code getTodayCoinsEarnedCents} 会同步回读 DB 并拿到他服写的额度 —— 日限正确生效。
     *
     * <p>这条把缺陷的边界钉死：不是「DB 不可靠」，而是「<b>陈旧命中</b>」。
     * 任何修法只要能消灭陈旧命中（强制回读或库侧原子判定）就能修好，无须改动数据模型。
     */
    @Test
    @DisplayName("第二台服的 cache 真 miss 时会回读 DB，日限正确生效（缺陷只出在陈旧命中）")
    void aCacheMissOnTheSecondServerStillEnforcesTheCap() {
        // A 服把今日额度用满（B 服此刻还没有这名玩家的任何条目）。
        assertEquals(500.0, engineA.depositCappedReward(player, 500.0), 1e-9);
        assertEquals(50000L, dbDailyCents(dbA), "前置：DB 已满 500.00");

        int callsBefore = depositCalls.get();

        // B 服第一次见到 P：miss → 回读 DB → 剩余 0 → 一分不发，且不产生任何 Vault 调用。
        assertEquals(0.0, engineB.depositCappedReward(player, 100.0), 1e-9,
                "B 服 cache miss 时必须从 DB 读到 A 服写满的 500，实发应为 0喵");
        assertEquals(50000L, vaultCents.get(), "合计不得越过 cap");
        assertEquals(callsBefore, depositCalls.get(),
                "额度已满时不该产生任何新的 Vault 调用（连 0 元都不该转）");
    }

    /**
     * 目前唯一有效的运维止血：{@code /lea reload}（{@code resetDailyCoinsCache}）。
     *
     * <p>清空内存后下一次读必然 miss → 回读 DB 真源 → 日限恢复正确。
     * 这条修复后仍应保持成立，且是<b>本次缺陷的立即可用缓解手段</b>
     * （代价是每次跨服都要 reload，运维上不可持续 —— 所以只是止血，不是修法）。
     */
    @Test
    @DisplayName("止血有效：/lea reload 清空缓存后，第二台服会回读 DB 并重新拦住")
    void reloadingTheSecondServerRestoresTheCap() {
        assertEquals(100.0, engineB.depositCappedReward(player, 100.0), 1e-9);
        assertEquals(400.0, engineA.depositCappedReward(player, 400.0), 1e-9);
        assertEquals(50000L, dbDailyCents(dbB), "前置：DB 已满 500.00");

        engineB.resetDailyCoinsCache(); // = HotReloadManager.reloadAll 的那一步

        assertEquals(0.0, engineB.depositCappedReward(player, 100.0), 1e-9,
                "reload 后必须回读 DB 并拦住 —— 这正是 HotReloadManager 里那次清缓存的"
                        + "实际作用（也说明为什么它不能当常态运维手段）喵");
        assertEquals(50000L, vaultCents.get(), "合计仍应停在 500.00");
    }

    // ==================================================================
    // Round 30：补偿逻辑（转账失败必须退回预占）
    // ==================================================================

    /**
     * <b>补偿逻辑的守门人（多进程版）</b>：Vault 转账失败时，已由库侧预占的额度
     * <b>必须</b>退回 —— 否则一次 provider 抖动就把玩家当日额度永久烧掉，
     * 比原缺陷（多发钱）更严重，因为不可逆。
     *
     * <p>与 {@code RewardEngineDailyCapTest.vaultFailureDoesNotConsumeDailyCap} 的分工：
     * 那条钉的是<b>单进程</b>路径（预占都还没发生，本来就不该占额度）；
     * 这条钉的是<b>多进程</b>路径（额度已经写进库了，必须补偿反向 UPDATE 才算完）。
     * 两条都不可省 —— 单进程路径<b>根本不经过</b>补偿代码。
     *
     * <p><b>变异判别（实测）</b>：把 {@code payCappedReward} 失败分支里的
     * {@code releaseDailyCapHold(...)} 删掉 → 本用例必红，库值停在 40000。
     */
    @Test
    @DisplayName("多进程：Vault 拒付必须补偿退回已预占的额度（不得永久烧掉当日额度）")
    void crossServerVaultFailureReleasesTheReservation() {
        failDeposits = true;

        double failed = engineA.depositCappedReward(player, 400.0);

        assertEquals(0.0, failed, 1e-9, "拒付必须返回 0（调用方据此不标记已领）喵");
        assertEquals(0L, vaultCents.get(), "拒付不得改变 Vault 余额");
        assertEquals(0L, dbDailyCents(dbA),
                "拒付后 daily_coin_counters 必须回到 0 —— 停在 40000 说明预占没被补偿，"
                        + "玩家当日额度被一次 provider 抖动永久烧掉了（比多发钱更伤的不可逆损失）");

        // 恢复经济后端：同一笔请求必须仍能拿到完整 400（额度真的退回来了，不是"记账上退了"）。
        failDeposits = false;
        assertEquals(400.0, engineA.depositCappedReward(player, 400.0), 1e-9,
                "重试必须仍能拿到完整 400（而不是只剩 0）喵");
        assertEquals(40000L, vaultCents.get(), "重试后 Vault 恰好 400.00");
        assertEquals(40000L, dbDailyCents(dbA), "重试成功后库值恰为 400.00");
    }

    /**
     * 多进程路径下<b>不重复落库</b>：预占已经写进 {@code daily_coin_counters}，
     * 若再走一次 {@code addTodayCoinsEarned}（增量 UPSERT），库值会变成 2 倍，
     * 于是玩家下一笔就被提前卡住 —— 症状是"钱发得比 cap 少很多"，不会多发，
     * 但那是明显的功能回归。
     *
     * <p>判据是<b>库里的分</b>而不是内存：内存镜像本来就会被覆盖成预占读到的值。
     */
    @Test
    @DisplayName("多进程：预占成功后不得再落一次库（否则当日额度双计、日限提前收紧）")
    void crossServerReservationIsNotDoubleCounted() {
        assertEquals(200.0, engineA.depositCappedReward(player, 200.0), 1e-9);

        assertEquals(20000L, dbDailyCents(dbA),
                "库值必须恰好是 200.00（预占写入一次）。停在 40000 说明预占之后"
                        + "又走了一次增量 UPSERT —— 当日额度被双计，玩家会提前撞上限");
        assertEquals(20000L, dbDailyCents(dbB),
                "另一台服的连接也必须看到同一个 200.00（同一行、同一个库）");

        // 剩余额度必须仍是 300（不是 100）。
        assertEquals(300.0, engineB.depositCappedReward(player, 300.0), 1e-9,
                "B 服应能发出剩余的全部 300.00 —— 若只有 100 说明 A 的那笔被算了两次");
        assertEquals(50000L, vaultCents.get(), "合计恰好 500.00");
        assertEquals(50000L, dbDailyCents(dbA), "库值恰好 500.00");
    }

    // ==================================================================
    // Round 30：分流判据的源码 lint（行为测试在这里<b>测不出来</b>，见下）
    // ==================================================================

    /**
     * <b>分流判据的生产形状</b>：源码 lint。
     *
     * <h2>为什么这条不能用行为测试</h2>
     * 判据是 {@code isSQLite()} 的<b>反</b>，而本类的双写者装置<b>必然</b>是 SQLite
     * （纯 JVM 里造两个共享库的进程只有这样可行）。于是：
     * <ul>
     *   <li>把判据写成恒 {@code true}（永远走库侧预占）—— <b>行为上完全安全</b>，
     *       只是给 SQLite 单进程部署（绝大多数）每笔发币白加 3 条语句。
     *       「多发钱」这一类断言抓不到它，实测在恒 true 的变异体上
     *       {@link #crossServerDailyCapIsNotEnforcedAcrossProcesses()} 与
     *       {@code RewardEngineDailyCapTest} 全部 27 个用例<b>照样全绿</b>；</li>
     *   <li>把判据写成恒 {@code false} —— 靠 {@code setSharedDailyLedger(true)} 强制后
     *       本类仍绿，只有生产行为是坏的（正是本缺陷原样）。</li>
     * </ul>
     * 两种"接错线"都只有<b>源码形状</b>能 100% 确定地钉住。这是仓库里既定的处置：
     * {@code RewardEngineMainThreadIoTest.SourceShape} 用同一手法钉住"异步只有一个出口"，
     * 理由逐字相同（纯线程归属 / 纯分支选择的改动在单测里不可观测）。
     *
     * <h2>钉住的四件事</h2>
     * <ol>
     *   <li>判据读 {@code isSQLite()}（而不是常量、也不是 {@code proxy.server-name}）；</li>
     *   <li>测试缝 {@code sharedDailyLedger} <b>先于</b>生产判据被读取（否则缝是死的）；</li>
     *   <li>{@code clampToDailyCap} 里同时出现 {@code usesSharedDailyLedger()} 与
     *       {@code reserveDailyCoinsInDb}（判据必须真的接到预占上）；</li>
     *   <li>{@code payCappedReward} 里同时出现 {@code reserveDailyCoinsInDb} 的下游用法
     *       —— 转账失败分支必须调 {@code releaseDailyCapHold}。</li>
     * </ol>
     */
    @Nested
    @DisplayName("源码 lint：分流判据真的读后端、真的接到预占与补偿上")
    class LedgerModeWiring {

        @Test
        @DisplayName("判据 = isSQLite() 的反；测试缝先于生产判据被读（缝不能是死的）")
        void predicateReadsTheBackendNotAConstant() throws IOException {
            String code = strippedEngineSource();

            String predicate = methodBody(code, "boolean", "usesSharedDailyLedger");
            assertNotNull(predicate, "应能定位 usesSharedDailyLedger 的方法体（锚方法名，不锚行号）");
            assertTrue(predicate.contains("isSQLite()"),
                    "分流判据必须读 DatabaseManager.isSQLite() —— 那是「进程数上限 = 1」的"
                            + "结构性保证。恒 true 会给 SQLite 单进程部署白加每笔 3 条语句，"
                            + "恒 false 则本缺陷原样保留。实际方法体：\n" + predicate);
            assertFalse(predicate.contains("server-name"),
                    "不得改判 proxy.server-name：它为空**不等于**单服"
                            + "（MySQL + 两台子服忘填名字完全可能），漏判方向是多发钱。"
                            + "实际方法体：\n" + predicate);

            int seam = predicate.indexOf("sharedDailyLedger");
            int backend = predicate.indexOf("isSQLite()");
            assertTrue(seam >= 0, "测试缝 sharedDailyLedger 必须被读取（否则算不出强制值）。"
                    + "实际方法体：\n" + predicate);
            assertTrue(seam < backend,
                    "测试缝必须在生产判据**之前**被读 —— 顺序反了会让 setSharedDailyLedger "
                            + "形同虚设，本类靠它才走得到跨进程路径。实际方法体：\n" + predicate);
        }

        @Test
        @DisplayName("clampToDailyCap 把判据接到预占上；payCappedReward 把补偿接在失败分支上")
        void reservationAndCompensationAreWired() throws IOException {
            String code = strippedEngineSource();

            String clamp = methodBody(code, "DailyCapHold", "clampToDailyCap");
            assertNotNull(clamp, "应能定位 clampToDailyCap 的方法体");
            assertTrue(clamp.contains("usesSharedDailyLedger()"),
                    "clampToDailyCap 必须真的查一次分流判据。实际：\n" + clamp);
            assertTrue(clamp.contains("reserveDailyCoinsInDb("),
                    "多进程分支必须调 reserveDailyCoinsInDb（库侧原子预占）——"
                            + "没有它，判据永远停在进程内缓存上，本缺陷原样保留。实际：\n" + clamp);

            String pay = methodBody(code, "double", "payCappedReward");
            assertNotNull(pay, "应能定位 payCappedReward 的方法体");
            assertTrue(pay.contains("clampToDailyCap("),
                    "payCappedReward 必须先取日限判据。实际：\n" + pay);
            assertTrue(pay.contains("releaseDailyCapHold("),
                    "转账失败分支必须调 releaseDailyCapHold —— 漏了它，一次 Vault 抖动就把"
                            + "预占的额度永久烧掉（比多发钱更伤的不可逆损失）。实际：\n" + pay);
            // 预占成功的路径不得再落一次库（会把当日额度双计 → 日限提前收紧）。剥掉
            // 「内存镜像」那个方法自身的声明与另一处受控调用后才断言。
            String memoryMirror = methodBody(code, "void", "addTodayCoinsEarnedInMemoryOnly");
            assertNotNull(memoryMirror, "应能定位 addTodayCoinsEarnedInMemoryOnly 的方法体");
            String payWithoutMirrorCall = pay.replace("addTodayCoinsEarnedInMemoryOnly(", " ");
            assertTrue(payWithoutMirrorCall.contains("addTodayCoinsEarned("),
                    "非预占路径（单进程）仍必须走 addTodayCoinsEarned 落库 —— 否则 SQLite "
                            + "部署的日限账本不再写穿，重启后额度会凭空恢复。实际：\n" + pay);
            assertTrue(pay.indexOf("reservedInDb()") > 0,
                    "必须按 hold.reservedInDb() 分支决定是否再落库（预占路径再落一次 = 双计）。"
                            + "实际：\n" + pay);
        }

        // ---- 源码读/剥注释/取方法体（与 RewardEngineMainThreadIoTest.SourceShape 同法） ----

        private String strippedEngineSource() throws IOException {
            assertTrue(Files.isRegularFile(ENGINE_SRC),
                    "必须能读到 RewardEngine 源码（工作目录应为项目根）: "
                            + ENGINE_SRC.toAbsolutePath());
            return stripComments(Files.readString(ENGINE_SRC));
        }

        /**
         * 剥掉 {@code //} 与 block 注释，字符串字面量原样保留。
         *
         * <p><b>必须先剥</b>：本类这次改动的方法 javadoc 里逐字写着
         * {@code usesSharedDailyLedger} / {@code reserveDailyCoinsInDb} /
         * {@code releaseDailyCapHold} —— 不剥注释，每条断言都会被<b>它自己的注释</b>满足。
         */
        private String stripComments(String src) {
            StringBuilder out = new StringBuilder(src.length());
            int i = 0;
            int n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                if (c == '"' || c == '\'') {
                    int j = i + 1;
                    while (j < n) {
                        char d = src.charAt(j);
                        if (d == '\\') {
                            j += 2;
                            continue;
                        }
                        if (d == c) {
                            j++;
                            break;
                        }
                        j++;
                    }
                    out.append(src, i, Math.min(j, n));
                    i = Math.min(j, n);
                } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                    while (i < n && src.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i = Math.min(i + 2, n);
                    out.append(' ');
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }

        /** 按「返回类型 + 方法名」定位方法体（含花括号）。锚返回类型只匹配声明，不匹配调用点。 */
        private String methodBody(String code, String returnType, String name) {
            Pattern p = Pattern.compile(
                    "\\b" + Pattern.quote(returnType) + "\\s+" + Pattern.quote(name) + "\\s*\\(");
            Matcher m = p.matcher(code);
            if (!m.find()) {
                return null;
            }
            int open = code.indexOf('{', m.end());
            if (open < 0) {
                return null;
            }
            int depth = 0;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return code.substring(open, i + 1);
                    }
                }
            }
            return null;
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * 给一个「进程」的 plugin mock 打上每用例专属的桩。
     *
     * <p>除 DB 连接外全部依赖（配置、消息、数据层、经济后端）都是<b>网络级共享</b>的 ——
     * 多服网络的常态：所有子服读同一份配置、连同一个 Vault、连同一个库。
     * 唯一每用例变化的是 {@code dataFolder}（{@code @TempDir} 每次不同）。
     */
    private void stubPerTestDeps(LetsLearnEnglish plugin, Path dir) {
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getEconomy()).thenReturn(economy);
    }

    /**
     * 真记账的 Vault 桩（照 {@code RewardEngineDailyCapTest} 的桩法，不重新发明）。
     *
     * <p>维护余额并返回带真实余额的 {@link EconomyResponse}；刻意<b>不</b>用
     * {@code thenReturn(固定响应)} —— 那样只能证明「调用发生过」，证明不了「转了多少」，
     * 而本类全部判据都是金额。
     */
    private Economy trackingEconomy() {
        Economy eco = mock(Economy.class);
        when(eco.depositPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(1);
            depositCalls.incrementAndGet();
            if (failDeposits) {
                return new EconomyResponse(0.0, vaultCents.get() / 100.0,
                        EconomyResponse.ResponseType.FAILURE, "simulated refusal");
            }
            long balance = vaultCents.addAndGet(Math.round(amount * 100.0));
            return new EconomyResponse(amount, balance / 100.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(eco.withdrawPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(1);
            long balance = vaultCents.addAndGet(-Math.round(amount * 100.0));
            return new EconomyResponse(amount, balance / 100.0,
                    EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(eco.format(anyDouble()))
                .thenAnswer(inv -> String.format(java.util.Locale.ROOT, "%.2f",
                        inv.<Double>getArgument(0)));
        return eco;
    }

    /**
     * 今日金币计数（分），通过<b>指定进程的连接</b>读真库真源。
     *
     * <p>「用哪条连接读」是刻意的：两个 {@link DatabaseManager} 指向同一个文件，
     * 只有两条连接都读到同一个值，才能证明它们共享同一个库。
     *
     * <p>默认 0 而非 -1：{@code daily_coin_counters} 由 {@code RewardEngine} 懒建，
     * 「从未发过币」的用例里表可能根本不存在 —— 此时「没有额度占用」与「读失败」对
     * 本类的判据是同一件事（都是 0 占用），不区分。
     */
    private long dbDailyCents(DatabaseManager db) {
        long[] out = {0L};
        db.withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT cents FROM " + PREFIX
                            + "daily_coin_counters WHERE player_uuid = ? AND coin_date = ?")) {
                stmt.setString(1, PLAYER_ID.toString());
                stmt.setString(2, LocalDate.now().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    out[0] = rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException ignored) {
                // 表尚未懒建：等价于「今日零占用」。
                out[0] = 0L;
            }
            return null;
        });
        return out[0];
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("DailyCapCrossServerTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        return logger;
    }
}
