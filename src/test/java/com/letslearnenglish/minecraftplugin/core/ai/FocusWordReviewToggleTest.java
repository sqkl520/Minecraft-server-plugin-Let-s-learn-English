package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.ReviewScheduler;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L6（P1·隐私）：{@code ai-chat.focus-words-from-review} 开关的语义守卫。
 *
 * <p><b>为什么这是隐私问题</b>：回落数据源 {@code ReviewScheduler.getDueReviewWords} 返回的是
 * 玩家<b>尚未掌握、即将被考</b>的词。把它们注入 system prompt 的 TARGET WORDS 段，
 * 等于告诉 AI「请用这些词引导对话」—— 玩家下一场就会从 AI 的台词里看到自己复习队列里的
 * 正确答案。零成本（开一次对话即可）、可批量、可复现（{@code /le history} 能回看原句）。
 * 因此默认值必须是 <b>false</b>，且未配置的老服务器升级后也不能开始泄题。
 *
 * <p><b>测什么、不测什么</b>：被测对象是
 * {@code LetsLearnEnglish.resolveFocusWords(UUID, ChatFeedbackManager, WordManagerImpl, FileConfiguration)}
 * —— 从 {@code onEnable} 的 lambda 里抽出来的纯函数（无实例状态、无 Bukkit 静态调用，可纯 JVM 跑）。
 * 不测 {@code onEnable} 本身：它要 Bukkit server，且 {@code setFocusWordProvider} 的接线存在性
 * 已由 {@code B3WiringLintsTest} 的源码 lint 覆盖 —— 两边分工，前者管语义、后者管接线。
 */
@ExtendWith(MockitoExtension.class)
class FocusWordReviewToggleTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    private ChatFeedbackManager feedbackManager;
    private WordManagerImpl wordManager;
    private ReviewScheduler reviewScheduler;

    /** 真配置：mock 的 {@code getBoolean(key, def)} 会返回 false，把"默认值生效"这条测成假绿。 */
    private FileConfiguration mainConfig;

    @BeforeEach
    void setUp() {
        feedbackManager = mock(ChatFeedbackManager.class);
        wordManager = mock(WordManagerImpl.class);
        reviewScheduler = mock(ReviewScheduler.class);
        mainConfig = new YamlConfiguration();
    }

    /** 走被测入口（{@code public static} 纯函数，无 DI、无 Bukkit 静态调用）。 */
    private List<String> resolve() {
        return LetsLearnEnglish.resolveFocusWords(PLAYER_ID, feedbackManager, wordManager, mainConfig);
    }

    // ------------------------------------------------------------------
    // 默认（键缺失）必须等于 false
    // ------------------------------------------------------------------

    @Test
    @DisplayName("键缺失（老配置文件升级上来）：默认 false，绝不回落到复习词")
    void missingKeyDefaultsToNoReviewFallback() {
        when(feedbackManager.getSuggestedFocusWords(PLAYER_ID)).thenReturn(List.of());
        // mainConfig 保持空白：这正是「老配置文件里没有这个键」的真实形态

        assertTrue(resolve().isEmpty(),
                "配置里没有 focus-words-from-review 时回落到复习词了 —— 老服务器升级即开始泄题");

        // 关键断言：连数据源都不该被问（不是"取了再丢"）
        verify(wordManager, never()).getReviewScheduler();
        verifyNoInteractions(reviewScheduler);
    }

    @Test
    @DisplayName("显式 false：不回落到复习词")
    void explicitFalseDoesNotFallback() {
        when(feedbackManager.getSuggestedFocusWords(PLAYER_ID)).thenReturn(List.of());
        mainConfig.set("ai-chat.focus-words-from-review", false);

        assertTrue(resolve().isEmpty(), "开关关闭时仍返回了目标词");
        verifyNoInteractions(reviewScheduler);
    }

    @Test
    @DisplayName("显式 true：回落到艾宾浩斯到期复习词")
    void explicitTrueFallsBackToDueReviewWords() {
        when(feedbackManager.getSuggestedFocusWords(PLAYER_ID)).thenReturn(List.of());
        when(wordManager.getReviewScheduler()).thenReturn(reviewScheduler);
        when(reviewScheduler.getDueReviewWords(PLAYER_ID)).thenReturn(List.of("apple", "trade"));
        mainConfig.set("ai-chat.focus-words-from-review", true);

        assertEquals(List.of("apple", "trade"), resolve(),
                "开关打开后必须真的用复习词（否则本键成了死配置）");
        verify(reviewScheduler, times(1)).getDueReviewWords(PLAYER_ID);
    }

    // ------------------------------------------------------------------
    // 上一场建议词优先：开关状态不影响这一路
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 B —— 上一场 AI 纠错词始终优先")
    class SuggestedWordsWin {

        @Test
        @DisplayName("有 suggested 时直接用，开关为 false 也不碰复习词")
        void suggestedWinsWhenToggleOff() {
            when(feedbackManager.getSuggestedFocusWords(PLAYER_ID)).thenReturn(List.of("go"));
            mainConfig.set("ai-chat.focus-words-from-review", false);

            assertEquals(List.of("go"), resolve());
            verifyNoInteractions(reviewScheduler);
        }

        @Test
        @DisplayName("有 suggested 时直接用，开关为 true 也不叠加复习词")
        void suggestedWinsWhenToggleOn() {
            List<String> suggested = List.of("go", "pear");
            when(feedbackManager.getSuggestedFocusWords(PLAYER_ID)).thenReturn(suggested);
            mainConfig.set("ai-chat.focus-words-from-review", true);

            assertSame(suggested, resolve(),
                    "suggested 非空时不该再走复习词一路（两路混合会稀释「重点练什么」的信号）");
            verifyNoInteractions(reviewScheduler);
        }
    }

    // ------------------------------------------------------------------
    // 防御性边界
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 C —— DI 半初始化 / 缺依赖时的空表守卫")
    class DefensiveGates {

        @Test
        @DisplayName("playerId 为 null：直接空表，不问任何依赖")
        void nullPlayerIdReturnsEmpty() {
            assertTrue(LetsLearnEnglish.resolveFocusWords(null, feedbackManager, wordManager, mainConfig)
                    .isEmpty());
            verifyNoInteractions(feedbackManager, wordManager, reviewScheduler);
        }

        @Test
        @DisplayName("chatFeedbackManager 为 null（DI 半初始化）：不 NPE，按回落到复习词处理")
        void nullFeedbackManagerIsTolerated() {
            when(wordManager.getReviewScheduler()).thenReturn(reviewScheduler);
            when(reviewScheduler.getDueReviewWords(PLAYER_ID)).thenReturn(List.of("apple"));
            mainConfig.set("ai-chat.focus-words-from-review", true);

            assertEquals(List.of("apple"),
                    LetsLearnEnglish.resolveFocusWords(PLAYER_ID, null, wordManager, mainConfig));
        }

        @Test
        @DisplayName("mainConfig 为 null（配置尚未加载）：视为 false，不回落到复习词")
        void nullConfigTreatedAsDisabled() {
            when(feedbackManager.getSuggestedFocusWords(PLAYER_ID)).thenReturn(List.of());

            assertTrue(LetsLearnEnglish.resolveFocusWords(PLAYER_ID, feedbackManager, wordManager, null)
                    .isEmpty());
            verifyNoInteractions(reviewScheduler);
        }

        @Test
        @DisplayName("suggested 为 null（防御）：不能 NPE，也不能把 null 传出去")
        void nullSuggestedListIsTolerated() {
            when(feedbackManager.getSuggestedFocusWords(PLAYER_ID)).thenReturn(null);
            mainConfig.set("ai-chat.focus-words-from-review", false);

            assertTrue(resolve().isEmpty());
        }
    }
}
