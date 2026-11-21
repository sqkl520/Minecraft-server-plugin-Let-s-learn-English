package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.ArgumentMatchers;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R11 回归测试：{@code markDailyWordRewardClaimed} 从 {@code void} 改为 {@code boolean} 后，
 * 「<b>钱已发、台账没写进去</b>」这一失败方向必须<b>可见且有补偿</b>。
 *
 * <h2>为什么需要这个类</h2>
 * 修复前的形状（data 层自己 {@code catch (SQLException)} 吞成 WARNING）让调用方完全
 * 观测不到台账写失败。失败方向是「<b>多给</b>」：内存位已置、DB 台账为空，重启后
 * {@code isDailyWordRewardClaimed} 查不到 → 同一天再发一次。这条链上<b>没有任何</b>
 * 可观测点 —— 日志里只有一行不带"后果"的 WARNING，没有任何补偿动作。
 *
 * <h2>三条用例各自封死的变异</h2>
 * <ol>
 *   <li>{@link FailureIsVisibleAndCompensated#failureIsSurfacedAndQueuedForRetry()} ——
 *       把新返回值改回<b>恒 true</b>（模拟"继续吞掉失败"）：本用例立刻红，因为
 *       SEVERE 不出现、补写也不会在下次判定时发生。</li>
 *   <li>{@link SuccessPathStaysQuiet#successWritesExactlyOnceAndLogsNoSevere()} ——
 *       把返回值改成<b>恒 false</b>（过度失败）：本用例立刻红，因为成功路径会打
 *       SEVERE 并在下一次判定时重复补写。</li>
 *   <li>{@link RolloverDateIsPreserved#retryKeepsTheOriginalChallengeDate()} —— 补写与
 *       告警必须认<b>原挑战日</b>（rollover 补发的是昨日）；变异成"一律补今天"会红。</li>
 * </ol>
 *
 * <p><b>为什么必须断言"钱不回滚"</b>：{@code verify(rewardEngine, never()).depositCappedReward
 * (any(), argThat(d -> d &lt; 0))} 封死"看到失败就反向扣款"这条诱人但更糟的修法 ——
 * 它会把「多给一次」翻成「玩家答对了拿不到奖励」。
 *
 * <p><b>纯 JVM</b>：只触碰 {@code Bukkit.getPlayer(UUID)}（static mock 接管），其余
 * plugin / DB / Vault / 消息全 mock；奖励金额走真 {@link YamlConfiguration}（mock 的
 * {@code getDouble(key, def)} 返回 0 会让发奖金额失真）。
 */
class DailyChallengeLedgerFailureTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final String TODAY = "2026-11-10";
    private static final String YESTERDAY = "2026-11-09";
    private static final double WORD_REWARD = 100.0;

    private LetsLearnEnglish plugin;
    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private MessageUtil messageUtil;
    private Economy economy;
    private ProgressManagerImpl progressManager;
    private RewardEngine rewardEngine;
    private Player player;
    private Logger logger;

    private DailyChallengeManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        WordManagerImpl wordManager = mock(WordManagerImpl.class);
        databaseManager = mock(DatabaseManager.class);
        configManager = mock(ConfigManager.class);
        messageUtil = mock(MessageUtil.class);
        economy = mock(Economy.class);
        progressManager = mock(ProgressManagerImpl.class);
        rewardEngine = mock(RewardEngine.class);
        player = mock(Player.class);
        logger = mock(Logger.class);

        FileConfiguration mainConfig = new YamlConfiguration();
        mainConfig.set("daily-challenges.word-challenge-reward", WORD_REWARD);

        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getEconomy()).thenReturn(economy);
        when(plugin.getProgressManager()).thenReturn(progressManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(progressManager.getRewardEngine()).thenReturn(rewardEngine);
        when(rewardEngine.isMoneyEnabled()).thenReturn(true);
        when(rewardEngine.depositCappedReward(any(Player.class), anyDouble())).thenReturn(WORD_REWARD);
        lenient().when(messageUtil.getPlayerMessage(player, "daily-challenge.reopen-hint"))
                .thenReturn("reopen");

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);

        manager = new DailyChallengeManager(plugin, wordManager);
    }

    // ------------------------------------------------------------------
    // 用例 1：失败必须可见 + 有补写（封死「恒 true」变异）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("台账写失败：返回 false → 钱照发不回滚、SEVERE 带日期与后果、下次判定补写成功")
    void failureIsSurfacedAndQueuedForRetry() {
        when(databaseManager.isDailyWordRewardClaimed(eq(PLAYER_ID), anyString())).thenReturn(false);
        when(databaseManager.loadWordChallengeCompletion(eq(PLAYER_ID), anyString()))
                .thenReturn(DatabaseManager.WordChallengeCompletion.COMPLETED);
        // 第一次写台账失败，第二次（补写）成功。
        when(databaseManager.markDailyWordRewardClaimed(PLAYER_ID, TODAY))
                .thenReturn(false)
                .thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

            // ① 补发发生：钱到账，台账写失败。
            assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY),
                    "内存位已标记（钱已发），本次不应被算作 pending 喵");

            // ② 钱不能被回滚 —— 反向扣款会把「多给一次」翻成「玩家白干」。
            //    doubleThat 而非 argThat：depositCappedReward 的第二个参数是原始类型
            //    double，argThat 返回装箱 null 会在拆箱处 NPE（并污染后续 matcher 栈）。
            verify(rewardEngine, times(1)).depositCappedReward(player, WORD_REWARD);
            verify(rewardEngine, never()).depositCappedReward(
                    any(Player.class), ArgumentMatchers.doubleThat(amount -> amount < 0));

            // ③ 失败必须有自己的、能让人判断后果的日志（不能只是数据层那行 WARNING）。
            verify(logger).severe(ArgumentMatchers.contains("ledger write FAILED"));
            verify(logger).severe(ArgumentMatchers.contains(TODAY));
            verify(logger).severe(ArgumentMatchers.contains("SECOND time"));
            verify(logger).severe(ArgumentMatchers.contains(PLAYER_ID.toString()));

            // ④ 下一次判定（等价"玩家又答对了"的补写触发点）必须再写一次并成功。
            manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY);
            verify(databaseManager, times(2)).markDailyWordRewardClaimed(PLAYER_ID, TODAY);

            // ⑤ 补写成功后登记表清空 → 第三次判定不再重复写（否则每次答对都多打一条 SQL）。
            manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY);
            verify(databaseManager, times(2)).markDailyWordRewardClaimed(PLAYER_ID, TODAY);
            // 且始终只发过一次钱。
            verify(rewardEngine, times(1)).depositCappedReward(player, WORD_REWARD);
        }
    }

    // ------------------------------------------------------------------
    // 用例 2：成功路径不得被过度修复（封死「恒 false」变异）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("台账写成功：不报 SEVERE、只写一次、后续判定不重复写")
    void successWritesExactlyOnceAndLogsNoSevere() {
        when(databaseManager.isDailyWordRewardClaimed(eq(PLAYER_ID), anyString())).thenReturn(false);
        when(databaseManager.loadWordChallengeCompletion(eq(PLAYER_ID), anyString()))
                .thenReturn(DatabaseManager.WordChallengeCompletion.COMPLETED);
        when(databaseManager.markDailyWordRewardClaimed(PLAYER_ID, TODAY)).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

            assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY));
            manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY);
            manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY);
        }

        verify(databaseManager, times(1)).markDailyWordRewardClaimed(PLAYER_ID, TODAY);
        verify(logger, never()).severe(anyString());
        verify(rewardEngine, times(1)).depositCappedReward(player, WORD_REWARD);
    }

    // ------------------------------------------------------------------
    // 用例 3：rollover 补发必须按「昨日」补写（不能标成今天）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("昨日补发台账写失败：SEVERE 与补写都只针对昨日，绝不写今日")
    void retryKeepsTheOriginalChallengeDate() {
        when(databaseManager.isDailyWordRewardClaimed(eq(PLAYER_ID), anyString())).thenReturn(false);
        when(databaseManager.loadWordChallengeCompletion(eq(PLAYER_ID), anyString()))
                .thenReturn(DatabaseManager.WordChallengeCompletion.COMPLETED);
        when(databaseManager.markDailyWordRewardClaimed(PLAYER_ID, YESTERDAY))
                .thenReturn(false)
                .thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

            assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, YESTERDAY));
            verify(logger).severe(ArgumentMatchers.contains(YESTERDAY));

            // 补写触发点：同一日的再次判定。
            manager.hasPendingWordRewardForDate(PLAYER_ID, YESTERDAY);
        }

        verify(databaseManager, times(2)).markDailyWordRewardClaimed(PLAYER_ID, YESTERDAY);
        verify(databaseManager, never()).markDailyWordRewardClaimed(PLAYER_ID, TODAY);
    }

    // ------------------------------------------------------------------
    // 用例 4：进服也是补写触发点（D1 收尾）
    // ------------------------------------------------------------------

    /**
     * <b>这是一个「补上遗漏触发点」的护栏，不是 N9 的修复。</b>
     *
     * <p>N9（{@code InMemoryStateDurabilityTest}）记录的缺陷是「欠账信息只存在内存，
     * 进程重启即丢」—— 那一条<b>仍然成立、仍然 {@code @Disabled}</b>，本用例不改变它。
     *
     * <p>本用例钉的是另一件事：补写的<b>触发点</b>原本只有两个，都要玩家<b>主动答题</b>
     * 才会走到（{@code onWordCorrect} 入口、{@code hasPendingWordRewardForDate} 补发前）。
     * 于是「领完奖就下线、重启后再也没答过题」的玩家，欠账永远补不上 —— 而那条欠账
     * 正是「重启后同日重复领钱」的入口。
     *
     * <p>现在 {@code doCheckAndAssign}（玩家每次进服的必经路径）也会先补写一次。
     * 这不能根治重启丢失（内存态本来就是内存态），但把「再也不答题」这个窗口关上了。
     *
     * <h2>为什么用源码断言而不是行为断言</h2>
     * <p>{@code checkAndAssign} 走 {@code DailyChallengeRepository.countAssigned} →
     * 真实 JDBC（{@code db.getConnection().prepareStatement}）。要让它跑通得 mock
     * 一整条连接链（含 {@code getConnectionLock()} 返回非 null 的锁对象），
     * 而本用例真正要钉的只有「那一行调用在不在」。用源码断言既直接又不会被
     * 无关的 JDBC 细节干扰 —— 本仓 {@code MainThreadIoContractTest} 等 lint 同此风格。
     */
    @Test
    @DisplayName("D1：doCheckAndAssign（进服必经路径）必须触发欠账补写，不必等玩家答对")
    void joinAlsoRetriesPendingLedgerWrites() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/core/DailyChallengeManager.java"),
                java.nio.charset.StandardCharsets.UTF_8);

        int doCheckAt = src.indexOf("private void doCheckAndAssign(");
        assertTrue(doCheckAt > 0, "找不到 doCheckAndAssign 方法喵 —— 源码结构变了，请更新本用例");

        // 取方法体的前 40 行（足够覆盖方法开头的几行初始化）。
        int bodyEnd = Math.min(src.length(), doCheckAt + 4000);
        String body = src.substring(doCheckAt, bodyEnd);

        int retryAt = body.indexOf("retryPendingClaimLedgerWrites(playerId)");
        assertTrue(retryAt > 0,
                "doCheckAndAssign 里没有 retryPendingClaimLedgerWrites(playerId) 喵 —— "
                        + "补写的触发点就只剩「玩家答对」和「有 pending 判定」两个，"
                        + "「领完奖就下线、重启后再也不答题」的欠账永远补不上，"
                        + "那正是重启后同日重复领钱的入口（N9 的触发条件）");

        // 顺序也要钉：补写必须在任何 return 之前，否则读失败短路（countAssigned < 0）
        // 会让补写被跳过 —— 而「DB 读失败」恰恰是最需要补写的时刻。
        int firstReturn = body.indexOf("return;");
        assertTrue(firstReturn < 0 || retryAt < firstReturn,
                "retryPendingClaimLedgerWrites 必须在方法里任何 return 之前喵 —— "
                        + "否则 countAssigned 读失败（返回 -1）时方法立刻 return，"
                        + "补写永远走不到，而读失败正是最该补写的时刻");
    }
}
