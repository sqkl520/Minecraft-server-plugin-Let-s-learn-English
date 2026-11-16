package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R11：{@code assignDailyChallengeWords} 改成 {@code boolean} 之后，调用方
 * {@code DailyChallengeManager.ensureWordChallengesAssignedLocked} 的<b>日志纪律</b>。
 *
 * <p><b>缺陷</b>：原实现在写库之后无条件打印
 * {@code "Assigned N daily challenge words to player …"}。写库失败时（数据层只留一行
 * WARNING）日志里同时出现"失败了"和"已分配" —— 运维读日志会得到相反结论。挑战词是当日
 * 单词挑战的前提，没写进去玩家当天就没有挑战可做。
 *
 * <h2>两个方向 + 一条内存态守卫</h2>
 * <ul>
 *   <li>{@link #failedAssignLogsSevereAndNeverSaysAssigned}：DB 返回 {@code false} →
 *       <b>必须</b>有 SEVERE 说明后果，且<b>不得</b>出现任何 "Assigned" INFO。
 *       把调用方改回"忽略返回值、无条件打 Assigned" → 本用例红。</li>
 *   <li>{@link #successfulAssignLogsInfoAndNoSevere}：DB 返回 {@code true} →
 *       必须是 "Assigned N" INFO，且不得有 SEVERE。把调用方改成"一律报失败" → 本用例红。</li>
 *   <li>{@link #failedAssignIsRetriedOnNextCall}：失败后<b>不得</b>留下"今天已分配"的内存
 *       标记 —— 下一次调用必须再试一次。这是任务书特别要求核对的一点：本方法路径里唯一
 *       的分配快照缓存（{@code dailyWordStateCache}）由 {@code getOrLoadWordState} 在
 *       "空集"时<b>刻意不落缓存</b>，入口判据每次都是一条新的 SELECT —— 故失败天然可重试。
 *       本用例用"第二次调用再写一次"把这个性质钉住，防止将来有人加一个"已尝试"抑制位。</li>
 * </ul>
 *
 * <p><b>纯 JVM</b>：{@code plugin} / {@code DatabaseManager} / {@code WordManagerImpl} /
 * {@code Logger} 全 mock，配置走真 {@link YamlConfiguration}（mock 的
 * {@code getInt(key, def)} 默认返回 0，会把 word-count 变成 0，测试将永远选不出词）。
 */
class WordChallengeAssignLogTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000a551");

    private LetsLearnEnglish plugin;
    private DatabaseManager databaseManager;
    private Logger logger;
    private DailyChallengeManager manager;
    private String today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now().toString();

        plugin = mock(LetsLearnEnglish.class);
        databaseManager = mock(DatabaseManager.class);
        logger = mock(Logger.class);
        ConfigManager configManager = mock(ConfigManager.class);
        WordManagerImpl wordManager = mock(WordManagerImpl.class);

        FileConfiguration mainConfig = new YamlConfiguration();
        mainConfig.set("daily-challenges.word-count", 5);

        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getLogger()).thenReturn(logger);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        // MC-2 双门：两把伞都必须打开，否则 ensureWordChallengesAssigned 直接 return，
        // 本类所有断言都会"因为什么都没跑"而假绿（尤其"没有 Assigned 日志"那条）。
        when(configManager.isDailyChallengesEnabled()).thenReturn(true);
        when(configManager.isWordSystemEnabled()).thenReturn(true);

        // 词库：一个难度、6 个词 —— 足够抽出 5 个。
        WordBank bank = mock(WordBank.class);
        List<Word> words = new ArrayList<>();
        for (String w : List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")) {
            Word word = mock(Word.class);
            when(word.getEnglish()).thenReturn(w);
            words.add(word);
        }
        when(bank.getAllWords()).thenReturn(words);
        Set<String> difficulties = new LinkedHashSet<>();
        difficulties.add("beginner");
        when(wordManager.getAvailableDifficulties()).thenReturn(difficulties);
        when(wordManager.getWordBank("beginner")).thenReturn(bank);

        // 前置：今日尚无挑战词 → ensure 会走分配分支。
        // A5/D3：判空判据已改为三态版 —— 桩必须打在 loadDailyChallengeWords 上，
        // 且 readFailed=false 才是「确实未分配」（readFailed=true 会走「不分配」分支）。
        lenient().when(databaseManager.loadDailyChallengeWords(eq(PLAYER), anyString()))
                .thenReturn(new DatabaseManager.DailyChallengeWordsLoad(List.of(), false));

        manager = new DailyChallengeManager(plugin, wordManager);
    }

    @Test
    @DisplayName("分配失败：SEVERE 说明后果，绝不打印 Assigned")
    void failedAssignLogsSevereAndNeverSaysAssigned() {
        when(databaseManager.assignDailyChallengeWords(eq(PLAYER), anyString(), any()))
                .thenReturn(false);

        manager.getTodayWordProgress(mock(org.bukkit.entity.Player.class, inv -> {
            if ("getUniqueId".equals(inv.getMethod().getName())) return PLAYER;
            return null;
        }));

        // 必须有一条带"后果"的 SEVERE —— 运维据此知道该玩家今天没有挑战。
        verify(logger).severe(contains("FAILED to assign"));
        verify(logger).severe(contains(PLAYER.toString()));
        verify(logger).severe(contains(today));
        // 关键反向断言：不得再出现「已分配」的 INFO（原缺陷就是这一条与 WARNING 并存）。
        verify(logger, never()).info(org.mockito.ArgumentMatchers.<String>argThat(
                msg -> msg != null && msg.startsWith("Assigned ")));
    }

    @Test
    @DisplayName("分配成功：INFO 说 Assigned N，且不得有 SEVERE")
    void successfulAssignLogsInfoAndNoSevere() {
        when(databaseManager.assignDailyChallengeWords(eq(PLAYER), anyString(), any()))
                .thenReturn(true);

        manager.getTodayWordProgress(mock(org.bukkit.entity.Player.class, inv -> {
            if ("getUniqueId".equals(inv.getMethod().getName())) return PLAYER;
            return null;
        }));

        verify(logger).info(org.mockito.ArgumentMatchers.<String>argThat(
                msg -> msg != null && msg.startsWith("Assigned 5 ")));
        verify(logger, never()).severe(anyString());
    }

    @Test
    @DisplayName("失败后不留「今天已分配」的内存标记：下一次调用必须重试")
    void failedAssignIsRetriedOnNextCall() {
        when(databaseManager.assignDailyChallengeWords(eq(PLAYER), anyString(), any()))
                .thenReturn(false, true);

        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class, inv -> {
            if ("getUniqueId".equals(inv.getMethod().getName())) return PLAYER;
            return null;
        });

        manager.getTodayWordProgress(player);
        manager.getTodayWordProgress(player);

        // 两次都要真的尝试写库 —— 若失败被记成"今天已分配"，第二次就不会再调。
        verify(databaseManager, org.mockito.Mockito.times(2))
                .assignDailyChallengeWords(eq(PLAYER), anyString(), any());
    }

    /**
     * 反向守卫：入口判据必须仍是"SELECT 回读为空"，不能因为失败而少查一次。
     *
     * <p>若有人为了"省一次查询"把当日分配结果缓存成布尔位，失败时该位会被设成"已分配"，
     * 当天不再重试 —— 这正是任务书要求核对的内存态风险。本用例把"每次调用都回读"
     * 这一事实钉住（与上一条互补：上一条看写，这一条看读）。
     */
    @Test
    @DisplayName("反向守卫：每次调用都回读当日挑战词（不缓存「已分配」布尔位）")
    void everyCallReadsBackAssignmentState() {
        when(databaseManager.assignDailyChallengeWords(eq(PLAYER), anyString(), any()))
                .thenReturn(true);

        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class, inv -> {
            if ("getUniqueId".equals(inv.getMethod().getName())) return PLAYER;
            return null;
        });

        manager.getTodayWordProgress(player);
        manager.getTodayWordProgress(player);

        verify(databaseManager, org.mockito.Mockito.times(2))
                .loadDailyChallengeWords(eq(PLAYER), anyString());
    }

    /** 前置自检：确认夹具真的会走到分配分支（否则上面四条断言都可能假绿）。 */
    @Test
    @DisplayName("夹具自检：成功路径确实调用了 assignDailyChallengeWords")
    void harnessActuallyReachesAssign() {
        when(databaseManager.assignDailyChallengeWords(eq(PLAYER), anyString(), any()))
                .thenReturn(true);

        manager.getTodayWordProgress(mock(org.bukkit.entity.Player.class, inv -> {
            if ("getUniqueId".equals(inv.getMethod().getName())) return PLAYER;
            return null;
        }));

        verify(databaseManager).assignDailyChallengeWords(eq(PLAYER), anyString(),
                argThat(ids -> ids instanceof List && ((List<?>) ids).size() == 5));
    }
}
