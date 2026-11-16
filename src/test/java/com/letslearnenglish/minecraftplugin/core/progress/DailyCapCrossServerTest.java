package com.letslearnenglish.minecraftplugin.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
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
 * <h2>本类的绿/红划分（刻意）</h2>
 * 三个 {@code @Test} 钉的都是<b>修复后仍必须成立</b>的事实（不会因修完而变红）。
 * 缺陷本身写成 {@code @Disabled}：断言的是<b>期望</b>（跨服总额不超过 cap），
 * 一旦摘掉 {@code @Disabled} 就会红，且红的信息里带着实发额。
 * <b>刻意不写「断言现状 = 900 金币」的绿测试</b> —— 那会把缺陷固化成契约。
 */
@DisplayName("money.daily-cap 的多子服语义：按进程记账 vs 按玩家记账")
class DailyCapCrossServerTest {

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
     * <b>缺陷证明</b>：玩家 P 当天先后经过 B、A 两台子服，总额落在 {@code 2 × cap}。
     *
     * <h2>时间线（{@code money.daily-cap: 500}）</h2>
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
     * <p>09:20 那次判定是<b>cache hit</b>：{@code todayCoinsCache} 里 P 的条目
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
     * <p>反过来说：若 P 是<b>当天第一次</b>在 B 上出现（cache 真 miss），B 会回读 DB
     * 拿到 50000 并正确拦住 —— 见 {@link #aCacheMissOnTheSecondServerStillEnforcesTheCap()}。
     * 所以事故的触发条件是「同一天先在 B 玩过、再跨到 A、再跨回 B」，而不是「随便跨服」。
     */
    @Test
    @Disabled("已知缺陷：money.daily-cap 的账本是进程内 todayCoinsCache，多子服共享库时"
            + "每台服各算一份，日限实际额度 = 子服数 × cap。修复（DB 原子条件 UPDATE 或"
            + "每次发币前强制回读）后摘掉本注解即可当回归用")
    @DisplayName("缺陷记录：同一天跨两台子服，日限总额被突破到 2 × cap")
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
