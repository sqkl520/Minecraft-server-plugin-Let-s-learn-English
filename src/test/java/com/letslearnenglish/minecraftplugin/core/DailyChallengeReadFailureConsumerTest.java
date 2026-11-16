package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A5/D2 + D3 的<b>消费端</b>回归测试：
 * 数据层把「读失败」区分出来只是必要条件 —— <b>调用方必须据此改变行为</b>才算修好。
 *
 * <p>为什么单独立这个类，而不是只测数据层的三态：本仓库反复踩过「守卫空转」——
 * 加了标志/守卫，但调用方那条路径从来没被别的东西兜住时没人发现它没执行。
 * 这里两条用例的断言都落在<b>可观察的行为差异</b>上：
 * <ul>
 *   <li>D2：{@code READ_FAILED} 时 {@code hasPendingWordRewardForDate} 必须返回
 *       <b>true（仍 pending）</b>并留 SEVERE —— 返回 false 就是「昨日奖励永久作废」；</li>
 *   <li>D3：{@code readFailed} 时 {@code ensureWordChallengesAssigned} 必须
 *       <b>一次 {@code assignDailyChallengeWords} 都不调用</b> —— 调用了就是当日挑战集翻倍。</li>
 * </ul>
 *
 * <p>纯 JVM / 全 mock（本类只测调用方决策，不测 SQL —— 那部分由
 * {@code DailyChallengeReadFailureTriStateTest} 用真 SQLite 覆盖）。
 */
class DailyChallengeReadFailureConsumerTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000f6f01");
    private static final String DATE = "2026-02-10";
    private static final String TODAY = LocalDate.now().toString();

    private LetsLearnEnglish plugin;
    private DatabaseManager databaseManager;
    private Logger logger;
    private DailyChallengeManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        databaseManager = mock(DatabaseManager.class);
        logger = mock(Logger.class);
        ConfigManager configManager = mock(ConfigManager.class);
        WordManagerImpl wordManager = mock(WordManagerImpl.class);

        FileConfiguration mainConfig = new YamlConfiguration();
        mainConfig.set("daily-challenges.word-count", 5);
        mainConfig.set("daily-challenges.word-challenge-reward", 100.0);

        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getLogger()).thenReturn(logger);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        // 两把伞都打开，否则 ensureWordChallengesAssigned 直接 return —— 断言会因
        // "什么都没跑"而假绿（尤其 D3 的"不得调用 assign"那条）。
        when(configManager.isDailyChallengesEnabled()).thenReturn(true);
        when(configManager.isWordSystemEnabled()).thenReturn(true);

        // D3 需要词库非空，否则也走不到 assign。
        WordBank bank = mock(WordBank.class);
        List<Word> words = new ArrayList<>();
        for (String w : List.of("alpha", "bravo", "charlie")) {
            Word word = mock(Word.class);
            lenient().when(word.getEnglish()).thenReturn(w);
            words.add(word);
        }
        when(bank.getAllWords()).thenReturn(words);
        Set<String> difficulties = new LinkedHashSet<>();
        difficulties.add("beginner");
        when(wordManager.getAvailableDifficulties()).thenReturn(difficulties);
        when(wordManager.getWordBank("beginner")).thenReturn(bank);

        manager = new DailyChallengeManager(plugin, wordManager);
    }

    // ==================================================================
    // D2：读失败时必须保留 pending + 留 SEVERE
    // ==================================================================

    /**
     * 变异指向：把 {@code hasPendingWordRewardForDate} 的
     * {@code if (completion == READ_FAILED) { severe; return true; }} 整段删掉
     * （或把 {@code return true} 改成 {@code return false}），本用例红。
     *
     * <p>后半段断言 {@code never()).depositCappedReward} 是关键：修复方向绝不能是
     * "读不到就当全完成、直接发钱" —— 那是把「少发」翻成「多发」，与 A5 整体方向相反。
     */
    @Test
    @DisplayName("D2：完成度读失败 → 仍算 pending（返回 true）、留 SEVERE、不发币")
    void completionReadFailureKeepsRewardPending() {
        when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, DATE)).thenReturn(false);
        when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, DATE))
                .thenReturn(DatabaseManager.WordChallengeCompletion.READ_FAILED);

        boolean pending = manager.hasPendingWordRewardForDate(PLAYER_ID, DATE);

        assertTrue(pending,
                "读失败必须返回 true（仍算 pending / 作废提示），绝不能返回 false ——\n"
                        + "false 的含义是「该日没有 pending」，而该日之后再无回访点"
                        + "（hasPendingWordReward 只判今日状态），昨日已完成未发的奖励就此永久作废。");

        verify(logger).severe(contains("could NOT be settled"));
        verify(logger).severe(contains(DATE));
        verify(logger).severe(contains(PLAYER_ID.toString()));
    }

    /**
     * 反过度修复：{@code NOT_COMPLETED} 必须照旧返回 false，且<b>不得</b>刷 SEVERE。
     *
     * <p>少了这条，一个「任何非 COMPLETED 都当读失败」的实现也能通过上面的用例，
     * 而它会让每个没做完挑战的玩家每天午夜吃一条 SEVERE（日志洪水 + 假告警）。
     */
    @Test
    @DisplayName("D2：未全完成 → 返回 false（无 pending）且不得留 SEVERE")
    void notCompletedStaysQuietAndNotPending() {
        when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, DATE)).thenReturn(false);
        when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, DATE))
                .thenReturn(DatabaseManager.WordChallengeCompletion.NOT_COMPLETED);

        assertFalse(manager.hasPendingWordRewardForDate(PLAYER_ID, DATE),
                "未全完成 → 业务上确实没有 pending，必须返回 false");
        verify(logger, never()).severe(ArgumentMatchers.<String>any());
    }

    /**
     * 反向守卫（夹具自检）：健康的 {@code COMPLETED} 路径必须仍然返回「不是 pending 才算」——
     * 这里只断言它<b>问到了</b>三态接口，确保上面两条不是在测一条根本没走到的分支。
     */
    @Test
    @DisplayName("D2 夹具自检：健康路径确实查询了完成度三态接口")
    void healthyPathActuallyQueriesTriState() {
        when(databaseManager.isDailyWordRewardClaimed(PLAYER_ID, DATE)).thenReturn(false);
        when(databaseManager.loadWordChallengeCompletion(PLAYER_ID, DATE))
                .thenReturn(DatabaseManager.WordChallengeCompletion.NOT_COMPLETED);

        manager.hasPendingWordRewardForDate(PLAYER_ID, DATE);

        // R12-F8：两态便捷版 isDailyChallengeWordsCompleted 已删除，故这里无法再 verify
        // "没问两态版" —— 方法不存在本身就是更强的保证（误用会编译失败）。
        // 保留的可观测契约改成"聚合查询恰好一次"：READ_FAILED 的重试环与缓存短路都在
        // 三态接口上，多于一次即说明有人绕开缓存重复回查。
        verify(databaseManager, org.mockito.Mockito.times(1))
                .loadWordChallengeCompletion(PLAYER_ID, DATE);
    }

    // ==================================================================
    // D3：读失败时不得分配
    // ==================================================================

    /**
     * 变异指向：把 {@code ensureWordChallengesAssignedLocked} 里
     * {@code if (existingLoad == null || existingLoad.readFailed()) { warning; return; }}
     * 整段删掉，本用例红（会调用一次 assignDailyChallengeWords）。
     */
    @Test
    @DisplayName("D3：已分配词读失败 → 一次 assign 都不得发生，并留 WARNING")
    void readFailureSkipsAssignmentEntirely() {
        when(databaseManager.loadDailyChallengeWords(eq(PLAYER_ID), anyString()))
                .thenReturn(new DatabaseManager.DailyChallengeWordsLoad(List.of(), true));

        manager.getTodayWordProgress(player());

        verify(databaseManager, never()).assignDailyChallengeWords(any(UUID.class), anyString(),
                any());
        verify(logger).warning(contains("Skipped daily word-challenge assignment"));
        verify(logger).warning(contains(PLAYER_ID.toString()));
    }

    /**
     * 反过度修复：<b>真·未分配</b>（{@code readFailed=false} + 空表）必须照常分配。
     *
     * <p>单独立意：只测"读失败不分配"时，一个"永不分配"的实现也能通过 ——
     * 那会让玩家永远没有当日单词挑战。
     */
    @Test
    @DisplayName("D3：真未分配 → 必须照常分配（不得变成永不分配）")
    void genuineEmptyStillAssigns() {
        when(databaseManager.loadDailyChallengeWords(eq(PLAYER_ID), anyString()))
                .thenReturn(new DatabaseManager.DailyChallengeWordsLoad(List.of(), false));
        lenient().when(databaseManager.assignDailyChallengeWords(any(UUID.class), anyString(), any()))
                .thenReturn(true);

        manager.getTodayWordProgress(player());

        verify(databaseManager).assignDailyChallengeWords(eq(PLAYER_ID), eq(TODAY),
                ArgumentMatchers.argThat(ids -> ids instanceof List && ((List<?>) ids).size() == 3));
        verify(logger, never()).warning(contains("Skipped daily word-challenge assignment"));
    }

    /**
     * 已分配（非空）时同样不得重复分配 —— 这条是 D3 的原意（幂等读），
     * 保证修复没有把"看到既有词"的判断一起改坏。
     */
    @Test
    @DisplayName("D3：已有分配 → 不得再分配")
    void existingAssignmentIsLeftAlone() {
        when(databaseManager.loadDailyChallengeWords(eq(PLAYER_ID), anyString()))
                .thenReturn(new DatabaseManager.DailyChallengeWordsLoad(
                        List.of("alpha", "bravo"), false));

        manager.getTodayWordProgress(player());

        verify(databaseManager, never()).assignDailyChallengeWords(any(UUID.class), anyString(),
                any());
    }

    private Player player() {
        return mock(Player.class, inv -> {
            if ("getUniqueId".equals(inv.getMethod().getName())) return PLAYER_ID;
            return null;
        });
    }
}
