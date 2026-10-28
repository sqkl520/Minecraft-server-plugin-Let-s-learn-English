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

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L18 回归测试：{@link DailyChallengeManager#hasPendingWordRewardForDate(UUID, String)} 的
 * "已付"判定必须<b>带日期</b>。
 *
 * <p><b>为什么需要这个类</b>：修复前"已付"标记是无日期的 {@code Set<UUID>}，第一道门只问
 * "这个玩家今天付过没有"，而方法入参 {@code date} 是"要结算的那一天"（rollover 传昨日）。
 * 当 rollover 被推迟（服务器卡顿 / 定时任务晚于玩家当日领取）时，玩家已领的<b>今日</b>
 * 奖励会让<b>昨日</b>的 pending 被误判成"已付" → 昨日奖励永久丢失，且没有任何异常、
 * 没有任何日志。这类 bug 只有"先领今天、再结算昨天"的调用顺序才能暴露，故本类刻意按这个
 * 顺序编排用例。</p>
 *
 * <p><b>纯 JVM 前提</b>：只触碰 {@code Bukkit.getPlayer(UUID)}（由 {@code mockStatic}
 * 接管），其余依赖（plugin / DB / Vault / 消息）全部 mock；奖励金额走真
 * {@link YamlConfiguration}（mock 的 {@code getDouble(key, def)} 会返回 0，把发奖金额读成
 * 0 会让断言失真）。</p>
 */
class DailyChallengeRolloverDateTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final String TODAY = "2026-10-20";
    private static final String YESTERDAY = "2026-10-19";
    private static final double WORD_REWARD = 100.0;

    private LetsLearnEnglish plugin;
    private WordManagerImpl wordManager;
    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private FileConfiguration mainConfig;
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
        wordManager = mock(WordManagerImpl.class);
        databaseManager = mock(DatabaseManager.class);
        configManager = mock(ConfigManager.class);
        messageUtil = mock(MessageUtil.class);
        economy = mock(Economy.class);
        progressManager = mock(ProgressManagerImpl.class);
        rewardEngine = mock(RewardEngine.class);
        player = mock(Player.class);
        logger = mock(Logger.class);

        mainConfig = new YamlConfiguration();
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
        // 精确参数 stub（零 varargs）：避免依赖 Mockito 对可变参数的宽松匹配规则。
        when(messageUtil.getPlayerMessage(player, "daily-challenge.reopen-hint")).thenReturn("reopen");

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);

        manager = new DailyChallengeManager(plugin, wordManager);
    }

    // ------------------------------------------------------------------
    // 用例 1：L18 的核心回归 —— 今日已领不能误判昨日已付
    // ------------------------------------------------------------------

    @Test
    @DisplayName("L18 核心：今天已领过奖，查昨天的 pending 仍必须走到 DB 判定（不能因今天的标记短路）")
    void todayClaimDoesNotMaskYesterdayPending() {
        when(databaseManager.isDailyWordRewardClaimed(eq(PLAYER_ID), anyString())).thenReturn(false);
        when(databaseManager.isDailyChallengeWordsCompleted(eq(PLAYER_ID), anyString())).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

            // ① 结算"今天"：走到补发，成功发放 → 内存记下"今天已付"。
            assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY),
                    "今天全完成且未付时，补发成功应返回 false（不算作废）");
            verify(databaseManager).markDailyWordRewardClaimed(PLAYER_ID, TODAY);

            // ② 结算"昨天"：修复前这里会在第一道门命中"今天已付"而直接返回 false，
            //    昨天那笔永远不会发；修复后必须继续问 DB。
            assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, YESTERDAY),
                    "昨天同样全完成且未付，应就地补发并返回 false");

            verify(databaseManager, times(1)).isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY);
            verify(databaseManager, times(1)).isDailyChallengeWordsCompleted(PLAYER_ID, YESTERDAY);
            verify(databaseManager).markDailyWordRewardClaimed(PLAYER_ID, YESTERDAY);
            verify(rewardEngine, times(2)).depositCappedReward(eq(player), eq(WORD_REWARD));
        }
    }

    // ------------------------------------------------------------------
    // 用例 2：该日已付（DB 真源）→ 不补发
    // ------------------------------------------------------------------

    @Test
    @DisplayName("昨天已领取（DB 台账命中）→ 返回 false 且不再发币")
    void alreadyPaidDateReturnsFalseWithoutPayout() {
        when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(true);

        // DB 台账在第二道门就命中，方法根本走不到 Bukkit.getPlayer —— 无需 static mock。
        assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, YESTERDAY));

        verify(rewardEngine, never()).depositCappedReward(any(Player.class), anyDouble());
        verify(databaseManager, never()).isDailyChallengeWordsCompleted(any(UUID.class), anyString());
    }

    // ------------------------------------------------------------------
    // 用例 3：已完成未领取 + 在线 → 补发并返回 false
    // ------------------------------------------------------------------

    @Test
    @DisplayName("昨天已完成未领取 + 玩家在线 → 就地补发、按昨日写台账，并返回 false")
    void onlinePlayerGetsBackPaid() {
        when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(false);
        when(databaseManager.isDailyChallengeWordsCompleted(PLAYER_ID, YESTERDAY)).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

            assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, YESTERDAY));
        }

        verify(rewardEngine, times(1)).depositCappedReward(player, WORD_REWARD);
        // 关键：补发归属"昨日"，不能标成今日（否则会误封今日奖励）。
        verify(databaseManager, times(1)).markDailyWordRewardClaimed(PLAYER_ID, YESTERDAY);
        verify(databaseManager, never()).markDailyWordRewardClaimed(PLAYER_ID, TODAY);
        verify(player, times(2)).sendMessage(anyString()); // 庆祝 + 重开提示
    }

    // ------------------------------------------------------------------
    // 用例 4：已完成未领取 + 离线 → 仍算作废
    // ------------------------------------------------------------------

    @Test
    @DisplayName("昨天已完成未领取 + 玩家离线 → 返回 true（无法发币，仍算作废）")
    void offlinePlayerStillPending() {
        when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, YESTERDAY)).thenReturn(false);
        when(databaseManager.isDailyChallengeWordsCompleted(PLAYER_ID, YESTERDAY)).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(null);

            assertTrue(manager.hasPendingWordRewardForDate(PLAYER_ID, YESTERDAY));
        }

        verify(rewardEngine, never()).depositCappedReward(any(Player.class), anyDouble());
    }

    // ------------------------------------------------------------------
    // 用例 5：clearAllWordChallengeClaims 的"全清"语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearAllWordChallengeClaims 后重新判定：内存已付位清空 → 回到 DB 真源判定")
    void clearAllClaimsFallsBackToDatabaseTruth() {
        // DB 台账已清（模拟 /lea reset 后的状态）：内存清空后必须重新补发，而不是永久误判已付。
        when(databaseManager.isDailyWordRewardClaimed(eq(PLAYER_ID), anyString())).thenReturn(false);
        when(databaseManager.isDailyChallengeWordsCompleted(eq(PLAYER_ID), anyString())).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(PLAYER_ID)).thenReturn(player);

            assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY));
            verify(rewardEngine, times(1)).depositCappedReward(any(Player.class), anyDouble());

            manager.clearAllWordChallengeClaims();

            // 清空后：内存不再声称已付 → 仍要问 DB（此处 DB 说未付）+ 已完成 → 再补一次。
            assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, TODAY));
            verify(databaseManager, times(2)).isDailyWordRewardClaimed(PLAYER_ID, TODAY);
            verify(rewardEngine, times(2)).depositCappedReward(any(Player.class), anyDouble());
        }
    }
}
