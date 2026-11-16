package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R10-s16dc-03（round10 · slice-16 审计 FINDING 3，HIGH）：
 * {@code handleTimeout} 原先与 {@code submitAnswer} 结构完全相同（取 state → CAS 抢占
 * {@code submitting} → try），但结尾<b>只有 finally、没有 catch</b>。
 *
 * <p><b>为什么这不是「崩一次就完事」</b>：方法首行已调 {@code cancelAnswerTimer}，它把
 * {@code cancelled=true} / {@code isAnswering=false} 并取消任务；随后任何
 * {@code RuntimeException} 直接逃进 Bukkit 定时任务（唯一调用方是
 * {@code startAnswerTimer} 的到点分支，不包 try），被 Bukkit 吞进日志。留下的状态是
 * <b>比异常更难查的僵死</b>：
 * <ul>
 *   <li>{@code quizStates} 仍留条目 → {@code hasActiveQuiz} 恒 true，入口守卫拒绝新会话；</li>
 *   <li>倒计时已停、{@code isAnswering=false} → {@code ChatAnswerListener.onPlayerChat}
 *       的 isAnswering 门直接 return，玩家聊天既不被消费也不被判分；</li>
 *   <li>{@code WordLearningListener.onInventoryClose} 因 isAnswering=false 会重新
 *       {@code startAnswerForCurrentWord} 给同一题重新起表 → 退化成「同一题可无限重答」。</li>
 * </ul>
 *
 * <p>已知会抛的真实路径：{@code RewardEngine} 的 {@code eco.withdrawPlayer} 未包 try、
 * {@code DatabaseManager.getConnection()} 可抛 RuntimeException。本类用
 * {@code PlayerDataStore.incrementTodayAnswerCount} 抛 {@code IllegalStateException}
 * 模拟等价故障点（同为结算中段的 RuntimeException，与 submitAnswer 的 EH-01 同口径）。
 *
 * <p><b>变异检验</b>：把 {@code handleTimeout} 新增的 {@code catch} 整段删掉，
 * {@link WordLearningManagerTimeoutFailureTest#runtimeExceptionDuringTimeoutCleansUpSession}
 * 立刻变红（异常直接逃出，且 {@code hasActiveQuiz} 仍为 true）。
 */
@ExtendWith(MockitoExtension.class)
class WordLearningManagerTimeoutFailureTest {

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000b101");

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private WordManagerImpl wordManager;
    private Player player;
    private MessageUtil messageUtil;
    private PlayerDataStore dataStore;
    private Logger logger;
    private LearningSession session;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        wordManager = mock(WordManagerImpl.class);
        player = mock(Player.class);
        messageUtil = mock(MessageUtil.class);
        dataStore = mock(PlayerDataStore.class);
        logger = mock(Logger.class);

        FileConfiguration mainConfig = new YamlConfiguration();
        FileConfiguration messages = new YamlConfiguration();
        // lenient：两个用例走的是不同分支（异常路径 vs 教程正常路径），不是每条都用得上
        // 全部协作者；STRICT_STUBS 下未用到的桩会被判 UnnecessaryStubbingException。
        lenient().when(plugin.getConfigManager()).thenReturn(configManager);
        lenient().when(configManager.getMainConfig()).thenReturn(mainConfig);
        lenient().when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        lenient().when(configManager.isWordSystemEnabled()).thenReturn(true);
        lenient().when(plugin.getWordManager()).thenReturn(wordManager);
        lenient().when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        lenient().when(plugin.getMessageUtil()).thenReturn(messageUtil);
        lenient().when(plugin.getLogger()).thenReturn(logger);
        lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);

        session = new LearningSession(PLAYER_ID, "beginner", "spelling", List.of(
                new Word("apple", "苹果", "noun", null, null, null, null, "beginner", 1),
                new Word("book", "书", "noun", null, null, null, null, "beginner", 1),
                new Word("cat", "猫", "noun", null, null, null, null, "beginner", 1)));
    }

    @Test
    @DisplayName("缺陷一：结算中抛 RuntimeException —— 会话必须被清理、玩家收到 session-ended、返回 null")
    void runtimeExceptionDuringTimeoutCleansUpSession() {
        when(wordManager.startSession(any(Player.class), anyString(), anyString()))
                .thenReturn(session);
        when(dataStore.incrementTodayAnswerCount(PLAYER_ID))
                .thenThrow(new IllegalStateException("database down"));
        when(messageUtil.getPlayerMessageSafe(any(Player.class),
                eq("word-learning-gui.session-ended"))).thenReturn("SESSION-ENDED");

        WordLearningManager manager = new WordLearningManager(plugin);
        assertNotNull(manager.startQuiz(player, "beginner", "spelling"),
                "前置：会话应成功建立");
        assertTrue(manager.hasActiveQuiz(PLAYER_ID), "前置：quizStates 应有条目");

        WordLearningManager.AnswerResult result = manager.handleTimeout(player);

        assertNull(result,
                "异常路径必须返回 null —— 调用方（startAnswerTimer 到点分支）对 null 不弹超时文案、"
                        + "不重开 GUI，返回半成品 AnswerResult 会显示一份没结算完的结果");
        assertFalse(manager.hasActiveQuiz(PLAYER_ID),
                "会话必须被清理。残留条目 + 已停表 + isAnswering=false 的僵死态会让玩家聊天"
                        + "不判分，而关界面又能给同一题重新起表（同一题无限重答）");
        verify(player).sendMessage("SESSION-ENDED");
        verify(logger).log(eq(Level.WARNING), contains("handleTimeout failed"),
                isA(RuntimeException.class));
    }

    @Test
    @DisplayName("修复不得过度：教程超时正常路径仍返回结果且会话保留（原地重试）")
    void tutorialTimeoutStillReturnsResultAndKeepsSession() {
        when(wordManager.startSession(any(Player.class), anyString(), anyString(), anyInt()))
                .thenReturn(session);

        WordLearningManager manager = new WordLearningManager(plugin);
        assertNotNull(manager.startTutorialQuiz(player, "beginner", 3),
                "前置：教程会话应成功建立");

        WordLearningManager.AnswerResult result = manager.handleTimeout(player);

        assertNotNull(result, "正常路径不得被新 catch 吞掉");
        assertTrue(result.tutorial, "教程结果必须带 tutorial=true 供调用方分流 retry 文案");
        assertTrue(manager.hasActiveQuiz(PLAYER_ID),
                "教程超时是原地重试：会话必须保留，否则引导线在第一道错题就断了");
        org.junit.jupiter.api.Assertions.assertEquals(0, result.currentIndex,
                "教程原地重试不推进题号（这正是缺陷二票据失效的根因）");
        verifyNoInteractions(logger);
    }
}
