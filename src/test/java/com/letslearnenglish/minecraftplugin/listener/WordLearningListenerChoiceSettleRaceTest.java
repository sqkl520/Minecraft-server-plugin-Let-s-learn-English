package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.AnswerChecker;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R11-H（round11 · GUI 点选通道审计）：GUI 点选通道是否与 chat 通道<b>同源</b>
 * —— 即 r10-s16dc-04 修的那个「教程会话题号不推进 → 防重复结算票据恒真 → 同一题被
 * 重复结算」在 GUI 点选通道上是否同样成立。
 *
 * <h2>结论：不成立（NOT_A_DEFECT）。本类是把结论与「为什么」一起钉住的回归测试</h2>
 *
 * <h3>票据退化是真的，但它退化掉的那道闸不是承重墙</h3>
 * <p>{@code drawnQuestion}（{@code WordLearningGUI} 渲染时写进选项物品 PDC 的题号）
 * 在教程会话里<b>确实恒等</b> —— 教程答错/超时是原地重试，
 * {@code recordTutorialRetry()} 只加计数不 {@code advance()}，题号一直是 0，
 * 于是 {@code WordLearningListener:291-294} 的跨题比对恒真。这一点与 chat 通道
 * 完全同源，本类不否认。
 *
 * <p><b>但两个通道的「一次输入 → 多次结算」威胁模型不同</b>：
 * <ul>
 *   <li><b>chat</b>：{@code AsyncPlayerChatEvent} 在<b>异步线程</b>取票据
 *       （{@code ChatAnswerListener:226-228}），结算经 {@code runTask} 推到
 *       <b>下一个主线程 tick</b>（{@code :249}）才复核（{@code :255-262}）。
 *       取值与结算之间隔着一个调度队列窗口，客户端同一 tick 连发的多条包能全部
 *       取到同一张旧票据再依次结算 —— <b>一次输入，多次结算</b>，这才是缺陷。</li>
 *   <li><b>GUI 点选</b>：{@code InventoryClickEvent} <b>不是</b> {@code AsyncEvent}
 *       （javap 核验：本类未重写 {@code isAsynchronous()}，父链
 *       {@code InventoryInteractEvent → InventoryEvent → Event} 均未实现
 *       {@code AsyncEvent}），主线程同步派发；{@code submitAnswer}
 *       在<b>同一栈帧内同步跑完</b>（{@code WordLearningListener:323}，外面没有
 *       {@code runTask}）。<b>一次物理点击只产生一个服务端事件</b>，
 *       且一次事件里只调一次 {@code submitAnswer} —— 不存在「一次输入、多次结算」的
 *       来路，票据比对挡不住也无所谓。</li>
 * </ul>
 *
 * <h3>「同一题被结算两次」在 GUI 上是设计语义，不是缺陷</h3>
 * <p>教程会话里玩家点错 → 界面原地刷新回同一题 → 玩家<b>再点一次</b>。
 * 这是<b>两个不同的玩家输入</b>，各自结算一次，属于 plan-onboarding D1 的
 * 「答错原地重试」语义（{@code LearningSession.recordTutorialRetry} 的注释明确写
 * 「只记错误计数、不推进」）。与 chat 的「一条消息被结算两次」性质完全不同。
 * 第 3 条用例把这条语义钉死，防止后人把「设计内重试」误判成「重复结算」而加错闸。
 *
 * <h3>谁是 GUI 通道真正承重的闸（下表每条都被本类变异验证过）</h3>
 * <table border="1">
 *   <caption>闸 → 挡什么 → 对应用例 → 变异后变红的用例</caption>
 *   <tr><th>闸</th><th>挡什么</th><th>用例</th><th>变异后红光</th></tr>
 *   <tr><td>300ms 节流 {@code CHOICE_CLICK_THROTTLE_MS}</td>
 *       <td>重画窗口（1 tick＝50ms）内客户端补发的第二刀</td>
 *       <td>2</td><td>2</td></tr>
 *   <tr><td>{@code drawnQuestion} 跨题比对</td>
 *       <td>旧画面上的陈旧物品点击（正常会话）</td>
 *       <td>4</td><td>4</td></tr>
 *   <tr><td>{@code state == null} 早退</td>
 *       <td>会话已结束后的点击</td>
 *       <td>5</td><td>5</td></tr>
 *   <tr><td>一次事件只调一次 {@code submitAnswer}</td>
 *       <td>「一次点击、多次结算」</td>
 *       <td>1</td><td>1</td></tr>
 * </table>
 *
 * <p><b>刻意不断言的</b>：{@code submitting} CAS。对 GUI 通道做「删掉 CAS」的变异后
 * 本类<b>依然全绿</b> —— 因为主线程同步执行下第二次点击必然发生在第一次结算完整返回
 * 之后，CAS 在这里没有可供竞态的时间窗（它真正承重的是 chat 通道与超时任务那条
 * 异步路径）。把「CAS 保护了 GUI」写成断言会是假绿，故不写。
 */
@ExtendWith(MockitoExtension.class)
class WordLearningListenerChoiceSettleRaceTest {

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000c301");

    /** 默认布局下 choice 选项首选槽位的第一格（{@code WordLearningGUI.PREFERRED_CHOICE_SLOTS[0]}）。 */
    private static final int CHOICE_SLOT = 18;

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private WordManagerImpl wordManager;
    private Player player;
    private MessageUtil messageUtil;
    private LearningSession session;
    private MockedStatic<WordLearningGUI> guiStatic;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        wordManager = mock(WordManagerImpl.class);
        player = mock(Player.class);
        messageUtil = mock(MessageUtil.class);

        FileConfiguration mainConfig = new YamlConfiguration();
        lenient().when(plugin.getConfigManager()).thenReturn(configManager);
        lenient().when(configManager.getMainConfig()).thenReturn(mainConfig);
        lenient().when(configManager.isWordSystemEnabled()).thenReturn(true);
        lenient().when(configManager.getMessageConfig(anyString()))
                .thenReturn(new YamlConfiguration());
        lenient().when(plugin.getWordManager()).thenReturn(wordManager);
        lenient().when(plugin.getMessageUtil()).thenReturn(messageUtil);
        lenient().when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);
        lenient().when(player.isOnline()).thenReturn(true);
        lenient().when(messageUtil.getPlayerMessage(any(Player.class), anyString()))
                .thenReturn("MSG");
        // getGuiSlot 一律回落到调用方默认值（＝默认布局），避免逐键 stub。
        lenient().when(configManager.getGuiSlot(anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(2));

        // presentAnswerResult 的收尾会走 runTask 重画界面；本用例不测渲染层，
        // 只把调度链通到"能返回一个 task"，不执行排队的 runnable。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getScheduler()).thenReturn(scheduler);
        // WordLearningManager 构造器会自注册死亡/重生监听（:52）；给它一个非 null 的
        // PluginManager，否则 NPE（生产环境恒非 null）。
        lenient().when(server.getPluginManager())
                .thenReturn(mock(org.bukkit.plugin.PluginManager.class));
        lenient().when(scheduler.runTask(any(), any(Runnable.class)))
                .thenReturn(mock(BukkitTask.class));

        session = new LearningSession(PLAYER_ID, "beginner", "choice", List.of(
                word("apple", "苹果"), word("book", "书"), word("cat", "猫")));

        // 真实词库（3 词）→ getChoiceOptions 能产出多选项，才有"点错"的余地。
        lenient().when(wordManager.getWordBank("beginner")).thenReturn(threeWordBank());

        // 静态读方法顶掉：本用例测的是"点击 → 结算"的闸门数量，不是 PDC 编解码
        // （渲染与读取共用同一份 private helper；静态读方法需要真实 NamespacedKey）。
        guiStatic = mockStatic(WordLearningGUI.class);
    }

    @AfterEach
    void tearDown() {
        if (guiStatic != null) guiStatic.close();
    }

    private static Word word(String english, String chinese) {
        return new Word(english, chinese, "noun", null, null, null, null, "beginner", 1);
    }

    /** 造一个含 3 个词的真实 {@link WordBank}（getChoiceOptions 需要它产干扰项）。 */
    private static WordBank threeWordBank() {
        YamlConfiguration cfg = new YamlConfiguration();
        List<Object> words = new ArrayList<>();
        for (String[] pair : new String[][]{{"apple", "苹果"}, {"book", "书"}, {"cat", "猫"}}) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("english", pair[0]);
            entry.put("chinese", pair[1]);
            words.add(entry);
        }
        cfg.set("words", words);
        return new WordBank("beginner", cfg);
    }

    /** 建一个教程会话（isTutorial=true：答错原地重试），并接到 plugin 上。 */
    private WordLearningManager tutorialManager() {
        when(wordManager.startSession(any(Player.class), anyString(), anyString(), anyInt()))
                .thenReturn(session);
        WordLearningManager manager = new WordLearningManager(plugin);
        manager.startTutorialQuiz(player, "beginner", 3);
        when(plugin.getWordLearningManager()).thenReturn(manager);
        return manager;
    }

    /** 造一个"已渲染的选项物品"：{@code index = 选项下标}，{@code question = 渲染时题号}。 */
    private ItemStack choiceItem(int index, int question) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        lenient().when(item.hasItemMeta()).thenReturn(true);
        lenient().when(item.getItemMeta()).thenReturn(meta);
        lenient().when(meta.getPersistentDataContainer()).thenReturn(pdc);
        guiStatic.when(() -> WordLearningGUI.readChoiceIndex(plugin, item)).thenReturn(index);
        guiStatic.when(() -> WordLearningGUI.readChoiceQuestionIndex(plugin, item))
                .thenReturn(question);
        return item;
    }

    /**
     * 驱动一次真正的"点选作答"：走真实
     * {@code handleWordLearningGUI → handleChoiceOptionClick → submitAnswer} 全链路。
     */
    private void click(WordLearningListener listener, ItemStack item) throws Exception {
        Method m = WordLearningListener.class.getDeclaredMethod(
                "handleWordLearningGUI", Player.class, int.class, ItemStack.class, boolean.class);
        m.setAccessible(true);
        m.invoke(listener, player, CHOICE_SLOT, item, true);
    }

    /**
     * 把该 listener 的节流时间戳推到"很久以前"—— 精确模拟「距上次点击已过 &gt;300ms」，
     * 而<b>不</b>换 listener 实例（生产里 listener 是单例，见
     * {@code LetsLearnEnglish:946}）。换实例虽然也能让节流失效，但那是伪造，
     * 且会让"节流为什么没挡住"这件事看不出原因。
     */
    @SuppressWarnings("unchecked")
    private void expireThrottle(WordLearningListener listener) throws Exception {
        Field f = WordLearningListener.class.getDeclaredField("lastChoiceSubmitAt");
        f.setAccessible(true);
        Map<UUID, Long> map = (Map<UUID, Long>) f.get(listener);
        map.put(PLAYER_ID, System.currentTimeMillis() - 10_000L);
    }

    /** 当前题正确答案在选项里的下标（选项顺序随机，不能写死）。 */
    private int correctOptionIndex(WordLearningManager manager) {
        List<Word> options = manager.getChoiceOptions(PLAYER_ID);
        assertTrue(options != null && options.size() >= 2,
                "前置：本题应产出至少 2 个选项，否则没有点错的余地喵。");
        int correct = AnswerChecker.indexOfAnswer(options, session.getCurrentWord());
        assertTrue(correct >= 0, "前置：正确项必须能在选项里定位到喵。");
        return correct;
    }

    /** 一个必然判错的下标（任何不等于正确项的下标）。 */
    private int wrongOptionIndex(WordLearningManager manager) {
        int size = manager.getChoiceOptions(PLAYER_ID).size();
        return (correctOptionIndex(manager) + 1) % size;
    }

    private int settledCount() {
        return session.getTotalCorrect() + session.getTotalIncorrect();
    }

    // =====================================================================
    //  用例 1：一次点击 = 恰好一次结算（「一次输入多次结算」这一缺陷形态不成立）
    // =====================================================================

    @Test
    @DisplayName("用例1：一次点选 = 恰好一次结算（教程会话，题号恒 0）")
    void oneClickSettlesExactlyOnce() throws Exception {
        WordLearningManager manager = tutorialManager();
        ItemStack wrong = choiceItem(wrongOptionIndex(manager), 0);

        click(new WordLearningListener(plugin), wrong);

        assertEquals(1, settledCount(),
                "一次点选必须恰好结算一次喵。若 handleChoiceOptionClick 里 submitAnswer "
                        + "被调两次（或在同一次点击里重复结算），这里会变成 2 —— "
                        + "那才是 chat 通道同源的缺陷形态。");
        assertEquals(0, session.getCurrentIndex(), "前置：教程答错原地重试，题号不推进（票据退化成立）");
    }

    // =====================================================================
    //  用例 2：重画窗口内的补发第二刀被 300ms 节流挡下
    // =====================================================================

    @Test
    @DisplayName("用例2：同一画面 300ms 内连点两次 = 节流挡下第二刀，只结算一次")
    void rapidReplayWithinThrottleWindowIsBlocked() throws Exception {
        WordLearningManager manager = tutorialManager();
        WordLearningListener listener = new WordLearningListener(plugin);
        ItemStack wrong = choiceItem(wrongOptionIndex(manager), 0);

        click(listener, wrong);
        // 同一 listener、不推时间戳 → 第二次落在 300ms 节流窗口内。
        click(listener, wrong);

        assertEquals(1, settledCount(),
                "客户端补发的第二刀必然落在重画窗口（1 tick = 50ms）内，必须被 300ms 节流"
                        + "挡下喵。若节流失效，incorrect 会变成 2，经 recordSession 的累加 "
                        + "UPSERT 污染 player_sessions.words_count，并经 endSession 影响复习间隔。");
        assertEquals(0, session.getCurrentIndex());
        assertEquals(1, session.getTotalIncorrect());
    }

    // =====================================================================
    //  用例 3：节流过后再点是「设计内重试」，不是「重复结算」
    // =====================================================================

    @Test
    @DisplayName("用例3：节流窗口过后再点同一题 = 设计内重试（两次输入各结算一次）")
    void clickAfterThrottleWindowIsADesignedRetryNotADuplicate() throws Exception {
        WordLearningManager manager = tutorialManager();
        WordLearningListener listener = new WordLearningListener(plugin);
        ItemStack wrong = choiceItem(wrongOptionIndex(manager), 0);

        click(listener, wrong);
        expireThrottle(listener); // 精确模拟「距上次点击已过 >300ms」
        click(listener, wrong);

        // 这条断言刻意是 2，且它不是缺陷 —— 它把 plan-onboarding D1 的语义钉死：
        // 教程会话答错 = 原地重试，玩家**两次不同的点击**各自结算一次，
        // 每题的重试次数就是错误次数（recordTutorialRetry 的设计意图）。
        // 与 chat「一条消息被结算两次」性质完全不同：这里没有「一次输入」。
        assertEquals(2, settledCount(),
                "教程会话的第二次点击是玩家**第二个**输入（原地重试），必须各自结算喵。"
                        + "把它改成 1 等于禁用重试语义；这条断言的存在是为了防止后人"
                        + "把设计内重试误判成重复结算而加一道错误的闸。");
        assertEquals(0, session.getCurrentIndex(),
                "教程重试不推进题号 —— 两次结算都落在同一题上（这正是票据恒等的来源，"
                        + "但在 GUI 上它不构成缺陷，因为两次结算是两次输入）");
    }

    // =====================================================================
    //  用例 4：正常会话里旧题物品被丢弃（drawnQuestion 真正承重的场合）
    // =====================================================================

    @Test
    @DisplayName("用例4：正常会话中旧题物品（drawnQuestion 落后）被点击 = 丢弃，不结算")
    void staleDrawnQuestionIsDiscarded() throws Exception {
        WordLearningManager manager = tutorialManager();
        // 模拟"界面已推进到第 1 题，玩家点到的是第 0 题画出来的旧物品"。
        ItemStack staleFromQuestion0 = choiceItem(wrongOptionIndex(manager), 0);
        // 把会话推进到第 1 题（等价于正常会话的 advance：recordIncorrect 会推进）。
        session.recordIncorrect();
        assertEquals(1, session.getCurrentIndex(), "前置：会话已推进到第 1 题");
        int before = settledCount();

        click(new WordLearningListener(plugin), staleFromQuestion0);

        assertEquals(before, settledCount(),
                "drawnQuestion=0 而当前题号=1，跨题校验必须把这次点击丢弃喵。"
                        + "这正是 drawnQuestion 在**正常会话**里承重的场合 —— "
                        + "它只在教程会话（题号不推进）里退化成恒真。");
        assertEquals(1, session.getCurrentIndex(), "题号不应因这次陈旧点击而推进");
    }

    // =====================================================================
    //  用例 5：会话已结束后点击 = 不结算、不 NPE
    // =====================================================================

    @Test
    @DisplayName("用例5：会话已结束（state 移出 map）后点击 = 不结算、不 NPE")
    void clickAfterSessionEndedSettlesNothing() throws Exception {
        WordLearningManager manager = tutorialManager();
        ItemStack wrong = choiceItem(wrongOptionIndex(manager), 0);
        // 会话消失（完成/停止/quit 都会走到 quizStates.remove）。
        manager.endQuiz(PLAYER_ID);
        int before = settledCount();

        click(new WordLearningListener(plugin), wrong);

        assertEquals(before, settledCount(), "state 为 null 时 handleChoiceOptionClick 必须早退喵。");
        assertEquals(0, session.getCurrentIndex(), "无会话时不应推进任何会话状态");
    }
}
