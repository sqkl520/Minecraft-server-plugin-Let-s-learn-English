package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10-s16dc-04（round10 · slice-16 审计，HIGH）：教程会话票据恒真 → 同一题可被重复结算。
 *
 * <p><b>缺陷链</b>：{@link ChatAnswerListener} 用「题号」做防重复结算的票据
 * （{@code session.getCurrentIndex()}），而教程会话的答错/超时是<b>原地重试</b> ——
 * {@code LearningSession.recordTutorialRetry()} 只加计数、<b>不调 advance()</b>
 * （对比 {@code recordIncorrect()} 明确调 advance()）。于是整段教程会话内题号可能一直是 0，
 * 票据退化成恒真：同一 tick 连发的两条（三条）chat 包都能穿过执行时刻复核闸门，
 * 各自 {@code recordTutorialRetry()} 一次 → {@code incorrectCount} 重复累加 →
 * 经 {@code recordSession} 的累加 UPSERT 污染 {@code player_sessions.words_count}，
 * 并经 {@code endSession} → {@code reviewScheduler.resetReview} 影响复习间隔。
 *
 * <p><b>修法</b>：票据升级为「题号 + 结算序号」。{@code QuizState.settleSeq} 是
 * 每次结算 finally 里 {@code incrementAndGet()} 的 {@code AtomicInteger}，题号不动时
 * 它仍然单调前进，因此「同一道题被结算第二次」终于可区分。
 *
 * <p><b>为什么测在 listener 层</b>：缺陷的闸门本身就在
 * {@link ChatAnswerListener#onPlayerChat} 的执行时刻复核里；只有经真实
 * {@code onPlayerChat → runTask → 复核 → submitAnswer} 全链路驱动，
 * 「把票据改回只比 currentIndex」才会真正让用例变红。若只在 manager 层断言
 * {@code settleSeq} 自增，删掉 listener 的比对分支测试依然全绿 —— 那是假绿。
 *
 * <p><b>变异检验</b>：删掉复核条件里的 {@code live.settleSeq.get() != ticketSeq}
 * （即退回「只比题号」），两条用例都会红（incorrectCount 变成 2 / 3）。
 */
@ExtendWith(MockitoExtension.class)
class ChatAnswerListenerTutorialTicketTest {

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000b201");

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private WordManagerImpl wordManager;
    private Player player;
    private MessageUtil messageUtil;
    private LearningSession session;

    private MockedStatic<Bukkit> bukkitStatic;
    private MockedConstruction<WordLearningGUI> guiConstruction;
    private final List<Runnable> scheduled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        wordManager = mock(WordManagerImpl.class);
        player = mock(Player.class);
        messageUtil = mock(MessageUtil.class);

        FileConfiguration mainConfig = new YamlConfiguration();
        FileConfiguration messages = new YamlConfiguration();
        lenient().when(plugin.getConfigManager()).thenReturn(configManager);
        lenient().when(configManager.getMainConfig()).thenReturn(mainConfig);
        lenient().when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        lenient().when(configManager.isWordSystemEnabled()).thenReturn(true);
        lenient().when(plugin.getWordManager()).thenReturn(wordManager);
        lenient().when(plugin.getMessageUtil()).thenReturn(messageUtil);
        lenient().when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        // M4 的决斗前置门：本用例不涉及决斗。
        lenient().when(plugin.getDuelManager()).thenReturn(null);
        lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);

        lenient().when(messageUtil.getPlayerMessage(any(Player.class),
                eq("onboarding.retry-wrong"))).thenReturn("RETRY");
        lenient().when(messageUtil.getPlayerMessage(any(Player.class),
                eq("word-learning-gui.answer-expired"))).thenReturn("EXPIRED");

        session = new LearningSession(PLAYER_ID, "beginner", "choice", List.of(
                new Word("apple", "苹果", "noun", null, null, null, null, "beginner", 1),
                new Word("book", "书", "noun", null, null, null, null, "beginner", 1),
                new Word("cat", "猫", "noun", null, null, null, null, "beginner", 1)));

        // Bukkit 是纯静态入口（getScheduler）：拦截后把 runTask 的 runnable 收集起来，
        // 由用例显式按顺序执行，精确复刻「同一 tick 连发多条 chat 包，任务排队后依次跑」。
        // GUI 构造用 mockConstruction 顶掉：本用例的被测对象是票据闸门，呈现层不是；
        // 否则会连带拖入 Bukkit.createInventory / ItemStack 注册表依赖。
        guiConstruction = mockConstruction(WordLearningGUI.class);
        bukkitStatic = mockStatic(Bukkit.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        bukkitStatic.when(Bukkit::getScheduler).thenReturn(scheduler);
        lenient().when(scheduler.runTask(any(), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return task;
        });
    }

    @AfterEach
    void tearDown() {
        if (bukkitStatic != null) bukkitStatic.close();
        if (guiConstruction != null) guiConstruction.close();
    }

    /** 建一个教程会话并把 isAnswering 置位（等价于倒计时进行中，不真起 scheduler 任务）。 */
    private WordLearningManager tutorialManagerInAnsweringState() {
        when(wordManager.startSession(any(Player.class), anyString(), anyString(), anyInt()))
                .thenReturn(session);
        WordLearningManager manager = new WordLearningManager(plugin);
        manager.startTutorialQuiz(player, "beginner", 3);
        // isAnswering 是 public volatile 字段（生产由 startAnswerTimer 置位，见 WordLearningManager:451）。
        // 直接置位可省掉 plugin.getServer().getScheduler() 这条链，且不影响被测语义。
        manager.getQuizState(PLAYER_ID).isAnswering = true;
        // listener 取的是 plugin.getWordLearningManager()（与构造期用的 getWordManager() 不同），
        // 必须把同一个真实 manager 交回去，否则 onPlayerChat 首行就 NPE。
        when(plugin.getWordLearningManager()).thenReturn(manager);
        return manager;
    }

    private AsyncPlayerChatEvent chatEvent(String message) {
        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(message);
        Set<Player> recipients = new HashSet<>();
        lenient().when(event.getRecipients()).thenReturn(recipients);
        return event;
    }

    /**
     * 同一 tick 连发 {@code count} 条 chat 包（全部答错），按入队顺序执行排队的任务。
     *
     * @return 教程会话最终的 incorrectCount
     */
    private int sendSameTickWrongAnswers(int count) {
        WordLearningManager manager = tutorialManagerInAnsweringState();
        manager  // 显式持有，确保 startTutorialQuiz 已把 QuizState 写进 map
                .getQuizState(PLAYER_ID);

        ChatAnswerListener listener = new ChatAnswerListener(plugin);

        // ① 同一 tick：所有包都在任何任务执行前通过异步栈帧的取值+isAnswering 门，
        //    因此三条票据完全相同（题号 0 / 结算序号 0）。
        for (int i = 0; i < count; i++) {
            listener.onPlayerChat(chatEvent("zzz" + i));
        }
        assertEquals(count, scheduled.size(),
                "前置：每条 chat 包都应排出一个结算任务");

        // ② 依次执行：第一条真正结算（教程原地重试 → incorrect+1，题号仍 0），
        //    其余的都必须在执行时刻复核里被判为迟到答案。
        for (Runnable task : scheduled) {
            task.run();
        }
        return session.getTotalIncorrect();
    }

    @Test
    @DisplayName("同一 tick 两条 chat 包：教程同一题只结算一次（incorrect 只 +1）")
    void twoPacketsInSameTickSettleTutorialWordOnce() {
        int incorrect = sendSameTickWrongAnswers(2);

        assertEquals(1, incorrect,
                "教程会话题号恒为 0，票据若只比题号则两条包都通过闸门 → incorrect=2，"
                        + "污染 player_sessions.words_count 与复习间隔");
        assertEquals(0, session.getCurrentIndex(), "教程重试不推进题号（缺陷成立的前提）");
        verify(player, times(1)).sendMessage("EXPIRED");
    }

    @Test
    @DisplayName("同一 tick 三条 chat 包：仍只结算一次")
    void threePacketsInSameTickSettleTutorialWordOnce() {
        int incorrect = sendSameTickWrongAnswers(3);

        assertEquals(1, incorrect,
                "三条包同一 tick 到达时，第 2/3 条的票据（题号 0 + 序号 0）必须与执行时刻的"
                        + "实时状态（题号 0 + 序号 1）不等而被丢弃");
        assertEquals(0, session.getCurrentIndex());
        verify(player, times(2)).sendMessage("EXPIRED");
    }
}
