package com.letslearnenglish.minecraftplugin.core.word;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeType;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;

public class WordLearningManager implements Listener {

    private static final int BASE_SCORE = 10;
    private static final int INCORRECT_PENALTY = 2;
    private static final int[] COMBO_THRESHOLDS = {0, 3, 5, 7, 10};
    private static final double[] COMBO_MULTIPLIERS = {1.0, 1.5, 2.0, 2.5, 3.0};

    private final LetsLearnEnglish plugin;
    private final WordManagerImpl wordManager;
    private final Map<UUID, QuizState> quizStates = new ConcurrentHashMap<>();
    private final Map<UUID, String> practiceWords = new ConcurrentHashMap<>();

    /** R-13：因答题中死亡而暂停的会话（重生后自动重开 GUI 续答）。 */
    private final Set<UUID> deathPausedQuiz = ConcurrentHashMap.newKeySet();

    public WordLearningManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.wordManager = plugin.getWordManager();
        // R-13：自注册监听死亡/重生事件。本管理器在 onEnable 内构造（插件已 enabled，
        // registerEvents 合法），仅此一处 new（已核实无重建），不改 LetsLearnEnglish
        // 的 DI/注册代码即可接入死亡保护。
        // getServer() 判空：纯逻辑单测用 mock 插件构造本类（getServer 返回 null），
        // 跳过注册即可，不影响运行期（生产环境 getServer 恒非 null）。
        if (plugin.getServer() != null) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
    }

    public LearningSession startQuiz(Player player, String difficulty, String mode) {
        cancelAnswerTimer(player);
        quizStates.remove(player.getUniqueId());
        LearningSession session = wordManager.startSession(player, difficulty, mode);
        int timeout = plugin.getConfigManager().getMainConfig().getInt("word-system.answer-timeout", 30);
        quizStates.put(player.getUniqueId(), new QuizState(session, difficulty, mode, timeout));
        return session;
    }

    /**
     * Start a review quiz using words that are due for spaced-repetition review.
     *
     * @param player the player
     * @return the review session, or null if no words are due for review
     */
    public LearningSession startReviewQuiz(Player player) {
        cancelAnswerTimer(player);
        quizStates.remove(player.getUniqueId());
        LearningSession session = wordManager.startReviewSession(player);
        if (session == null) return null;
        int timeout = plugin.getConfigManager().getMainConfig().getInt("word-system.answer-timeout", 30);
        QuizState state = new QuizState(session, "review", "review", timeout);
        state.isReviewMode = true;
        quizStates.put(player.getUniqueId(), state);
        return session;
    }

    public boolean hasActiveQuiz(UUID playerId) {
        return quizStates.containsKey(playerId);
    }

    public QuizState getQuizState(UUID playerId) {
        return quizStates.get(playerId);
    }

    public Word getCurrentWord(UUID playerId) {
        QuizState state = quizStates.get(playerId);
        if (state == null) return null;
        return state.session.getCurrentWord();
    }

    public AnswerResult submitAnswer(Player player, String answer) {
        UUID id = player.getUniqueId();
        QuizState state = quizStates.get(id);
        if (state == null) return null;

        if (!state.submitting.compareAndSet(false, true)) {
            return null;
        }

        try {
            cancelAnswerTimer(player);

            LearningSession session = state.session;
            Word currentWord = session.getCurrentWord();
            if (currentWord == null) return null;

            // FEAT-MODE-01：三模式判分收敛到 AnswerChecker（spelling 全词拼写 /
            // choice 选项字母 / fill_blank 填缺失词）。输出仍是 correct + correctAnswer，
            // 下游连击/发币/计数/成就零感知。choice 下 correctAnswer 带正确项字母。
            List<Word> choiceOptions = AnswerChecker.isChoice(state.mode)
                    ? getChoiceOptions(id) : null;
            boolean correct = AnswerChecker.check(state.mode, currentWord, answer, choiceOptions);
            String correctAnswer = currentWord.getEnglish();
            if (choiceOptions != null) {
                int answerIndex = AnswerChecker.indexOfAnswer(choiceOptions, currentWord);
                if (answerIndex >= 0) {
                    correctAnswer = AnswerChecker.formatChoiceOption(answerIndex, currentWord);
                }
            }

            int comboScore;
            if (correct) {
                state.consecutiveCorrect++;
                if (state.consecutiveCorrect > state.maxComboReached) {
                    state.maxComboReached = state.consecutiveCorrect;
                }
                double multiplier = getComboMultiplier(state.consecutiveCorrect);
                comboScore = (int) Math.round(BASE_SCORE * multiplier);
                session.addScore(comboScore);
                session.recordCorrect();

                // C3：判分落定即累计今日答题数（流 E 的 PlayerDataStore 接口，按自然日清零）。
                // 计数在发币前累加，RewardEngine 门用"计数值 > daily-limit"拦截：
                // 第 50 题计入后（=50）仍正常发币，第 51 题起才拦截；第 50 题所在会话的
                // 完成奖同样在计数=50 时结算，不被误杀。
                plugin.getPlayerDataStore().incrementTodayAnswerCount(id);

                // Daily challenge hooks (behavioral)
                plugin.getDailyChallengeManager().updateProgress(id, ChallengeType.ANSWER_10, 1);
                if (state.consecutiveCorrect >= 5) {
                    plugin.getDailyChallengeManager().completeChallenge(id, ChallengeType.COMBO_5);
                }
                // Daily challenge hooks (word-bank-based)
                plugin.getDailyChallengeManager().onWordCorrect(player, currentWord);

                RewardEngine rewards = plugin.getProgressManager().getRewardEngine();
                if (state.isReviewMode) {
                    rewards.giveMoneyForReviewCorrect(player, multiplier, currentWord);
                } else {
                    rewards.giveMoneyForCorrectAnswer(player, multiplier, currentWord);
                }
            } else {
                state.consecutiveCorrect = 0;
                comboScore = -INCORRECT_PENALTY;
                session.addScore(-INCORRECT_PENALTY);
                session.recordIncorrect();

                // C3：答错也计入今日答题数（防刷口径 = 进行中答题次数，不论对错）
                plugin.getPlayerDataStore().incrementTodayAnswerCount(id);

                if (state.isReviewMode) {
                    plugin.getProgressManager().getRewardEngine()
                            .giveMoneyForReviewIncorrect(player);
                }

                // Track wrong answers for Wrong Book feature
                trackWrongAnswer(player, currentWord);
            }

            return buildResult(id, state, correct, correctAnswer, comboScore,
                    state.consecutiveCorrect, getComboMultiplier(state.consecutiveCorrect));
        } finally {
            state.submitting.set(false);
        }
    }

    public AnswerResult handleTimeout(Player player) {
        UUID id = player.getUniqueId();
        QuizState state = quizStates.get(id);
        if (state == null) return null;

        if (!state.submitting.compareAndSet(false, true)) {
            return null;
        }

        try {
            cancelAnswerTimer(player);

            LearningSession session = state.session;
            Word currentWord = session.getCurrentWord();
            if (currentWord == null) return null;

            // R-84：超时判错补音效（与 ChatAnswerListener 答错分支的 "incorrect" 对称）。
            // handleTimeout 的唯一调用方（startAnswerTimer 的倒计时到点分支）持有
            // Player 对象，可直接播；死亡暂停走 cancelAnswerTimer，不会误播。
            SoundManager.playSound(player, "incorrect");

            state.consecutiveCorrect = 0;
            session.addScore(-INCORRECT_PENALTY);
            session.recordIncorrect();

            // C3：超时判错同样计入今日答题数（与答错同口径，防"超时刷题不计数"漏洞）
            plugin.getPlayerDataStore().incrementTodayAnswerCount(id);

            if (state.isReviewMode) {
                plugin.getProgressManager().getRewardEngine()
                        .giveMoneyForReviewIncorrect(player);
            }

            // Track timeout as wrong answer for Wrong Book
            trackWrongAnswer(player, currentWord);

            // R-57：超时结算答案格式与 submitAnswer 的答错格式对齐 —— choice 模式下
            // submitAnswer 显示 "B. apple"，这里原先只给纯 "apple"，玩家看到的"正确答案"
            // 格式不一致。此处按同一规则（同 submitAnswer 的 lines）渲染选项字母前缀。
            String correctAnswer = currentWord.getEnglish();
            if (AnswerChecker.isChoice(state.mode)) {
                List<Word> choiceOptions = getChoiceOptions(id);
                if (choiceOptions != null) {
                    int answerIndex = AnswerChecker.indexOfAnswer(choiceOptions, currentWord);
                    if (answerIndex >= 0) {
                        correctAnswer = AnswerChecker.formatChoiceOption(answerIndex, currentWord);
                    }
                }
            }

            return buildResult(id, state, false, correctAnswer,
                    -INCORRECT_PENALTY, 0, 1.0);
        } finally {
            state.submitting.set(false);
        }
    }

    /**
     * Track a wrong answer for the Wrong Book / Difficult Words feature.
     * After 3 wrong answers, the word is automatically added to the player's wrong book.
     */
    private void trackWrongAnswer(Player player, Word incorrectWord) {
        UUID playerId = player.getUniqueId();
        String word = incorrectWord.getEnglish();
        com.letslearnenglish.minecraftplugin.data.PlayerDataStore store = plugin.getPlayerDataStore();

        int wrongCount = store.incrementWrongCount(playerId, word);

        // When wrong count hits exactly 3, add to wrong book and notify
        if (wrongCount == 3) {
            store.addDifficultWord(playerId, word);

            org.bukkit.configuration.file.FileConfiguration messages = plugin.getConfigManager()
                    .getMessageConfig(plugin.getPlayerLanguage(player));
            String msg = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    messages.getString("wrong-book.auto-add"));
            player.sendMessage(msg);
        }
    }

    private AnswerResult buildResult(UUID id, QuizState state, boolean correct,
                                      String correctAnswer, int comboScore,
                                      int consecutiveCorrect, double comboMultiplier) {
        LearningSession session = state.session;
        boolean completed = session.isCompleted();
        // Q1：completed 时 state 马上移出 map，结算需要的字段先快照，
        // 随 AnswerResult 带给 showSessionComplete，不再 map 重查
        // （重查必为 null，会导致会话奖励/成就/日历/挑战整段跳过）。
        int maxComboReached = state.maxComboReached;
        String mode = state.mode;
        if (completed) {
            wordManager.endSession(id);
            quizStates.remove(id);
        }
        return new AnswerResult(correct, correctAnswer,
                session.getCurrentIndex(), session.getTotalWords(),
                session.getTotalCorrect(), session.getTotalIncorrect(),
                completed, session.getAccuracy(), session.getScore(),
                comboScore, consecutiveCorrect, comboMultiplier,
                maxComboReached, mode);
    }

    public void startAnswerTimer(Player player) {
        UUID id = player.getUniqueId();
        QuizState state = quizStates.get(id);
        if (state == null) return;

        cancelAnswerTimer(player);

        state.isAnswering = true;
        state.cancelled = false;
        int[] remaining = {state.answerTimeout};

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (state.cancelled) {
                return;
            }

            if (!player.isOnline()) {
                cancelAnswerTimer(player);
                return;
            }

            remaining[0]--;

            FileConfiguration messages = getMessages(player);

            if (remaining[0] <= 0) {
                AnswerResult timeoutResult = handleTimeout(player);
                if (timeoutResult != null) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            messages.getString("word-learning-gui.timeout")
                                    .replace("{answer}", timeoutResult.correctAnswer)));
                    if (timeoutResult.sessionComplete) {
                        showSessionComplete(player, timeoutResult);
                    } else {
                        new WordLearningGUI(plugin).openWithMessage(player, state.difficulty, state.mode);
                    }
                }
            } else if (remaining[0] <= 5) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("word-learning-gui.countdown-urgent")
                                .replace("{seconds}", String.valueOf(remaining[0]))));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("word-learning-gui.countdown-normal")
                                .replace("{seconds}", String.valueOf(remaining[0]))));
            }
        }, 20L, 20L);

        state.answerTaskId.set(task.getTaskId());
    }

    public void cancelAnswerTimer(Player player) {
        UUID id = player.getUniqueId();
        QuizState state = quizStates.get(id);
        if (state == null) return;

        state.cancelled = true;

        if (state.answerTaskId.get() != -1) {
            plugin.getServer().getScheduler().cancelTask(state.answerTaskId.get());
            state.answerTaskId.set(-1);
        }
        state.isAnswering = false;
    }

    // ============================================================
    //  R-13：答题中死亡保护（倒计时中死亡不判错，重生后可续答）
    // ============================================================

    /**
     * R-13：玩家在答题倒计时中死亡时，取消计时器且不判错。
     *
     * <p>原先倒计时到点会走 handleTimeout 清连击 / 扣分 / 记错题本；死亡属不可抗力，
     * 不应因此受罚。此处停表（内部置 isAnswering=false 并取消任务），当前题不判对错，
     * QuizState 原样保留并标记为死亡暂停，重生后由 {@link #onPlayerRespawn} 重开 GUI 续答。
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();
        if (!hasActiveQuiz(id)) {
            return;
        }
        cancelAnswerTimer(player);
        deathPausedQuiz.add(id);
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "word-learning-gui.death-paused"));
    }

    /**
     * R-13：重生后若存在死亡暂停的答题会话，下一 tick 重开答题 GUI 让玩家续答
     * （关界面即按既有流程重新开始倒计时，与正常答题路径一致）。
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!deathPausedQuiz.remove(id) || !hasActiveQuiz(id)) {
            return;
        }
        QuizState state = quizStates.get(id);
        if (state == null) {
            return;
        }
        final String difficulty = state.difficulty;
        final String mode = state.mode;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && hasActiveQuiz(id)) {
                new WordLearningGUI(plugin).open(player, difficulty, mode);
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word-learning-gui.death-resumed"));
            }
        });
    }

    public boolean isAnswering(UUID playerId) {
        QuizState state = quizStates.get(playerId);
        return state != null && state.isAnswering;
    }

    public int getAnswerTimeout() {
        return plugin.getConfigManager().getMainConfig().getInt("word-system.answer-timeout", 30);
    }

    public void stopQuiz(Player player) {
        UUID id = player.getUniqueId();
        QuizState state = quizStates.get(id);
        if (state == null) return;

        cancelAnswerTimer(player);
        wordManager.endSession(id);

        int maxComboReached = state.maxComboReached;
        quizStates.remove(id);

        int score = state.session.getScore();
        int correct = state.session.getTotalCorrect();
        int total = state.session.getTotalWords();
        // H2：已答题数 = 答对 + 答错（含超时判错，三者都推进了 currentIndex）；
        // 正在倒计时但尚未判分的当前题不计入；clamp 到 total 防异常
        int answered = Math.min(correct + state.session.getTotalIncorrect(), total);

        FileConfiguration messages = getMessages(player);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.session-stopped")
                        .replace("{correct}", String.valueOf(correct))
                        .replace("{total}", String.valueOf(total))
                        .replace("{score}", String.valueOf(score))));

        plugin.getProgressManager().getRewardEngine()
                .giveStoppedSessionMoneyReward(player, maxComboReached, answered, total);
    }

    /**
     * R-59 行为描述（既定裁决，保留原行为）：下线时清答题态并<strong>照常结算</strong>。
     * endSession 会落掌握度、累计学习分/会话数、写复习种子、同步榜单——rage quit
     * 与正常结束同等待遇（不豁免）；唯一不发的是会话走完才有的金币奖励与完成庆祝。
     * 旧注释"不结算"与实际不符，已随 R-59 校正。
     */
    public void endQuiz(UUID playerId) {
        QuizState state = quizStates.get(playerId);
        if (state != null && state.answerTaskId.get() != -1) {
            plugin.getServer().getScheduler().cancelTask(state.answerTaskId.get());
        }
        quizStates.remove(playerId);
        wordManager.endSession(playerId);
    }

    public void clearAllSessions() {
        for (UUID playerId : quizStates.keySet()) {
            QuizState state = quizStates.get(playerId);
            if (state != null && state.answerTaskId.get() != -1) {
                plugin.getServer().getScheduler().cancelTask(state.answerTaskId.get());
            }
            wordManager.endSession(playerId);
        }
        quizStates.clear();
    }

    public double getComboMultiplier(int consecutiveCorrect) {
        if (consecutiveCorrect <= 0) return 1.0;
        for (int i = COMBO_THRESHOLDS.length - 1; i >= 0; i--) {
            if (consecutiveCorrect >= COMBO_THRESHOLDS[i]) {
                return COMBO_MULTIPLIERS[i];
            }
        }
        return 1.0;
    }

    public int getConsecutiveCorrect(UUID playerId) {
        QuizState state = quizStates.get(playerId);
        if (state == null) return 0;
        return state.consecutiveCorrect;
    }

    public String getDifficulty(UUID playerId) {
        QuizState state = quizStates.get(playerId);
        if (state == null) return "beginner";
        return state.difficulty;
    }

    public String getMode(UUID playerId) {
        QuizState state = quizStates.get(playerId);
        if (state == null) return "choice";
        return state.mode;
    }

    /**
     * FEAT-MODE-01：取本题 choice 选项快照（正确项 + 同词库随机干扰项）。
     * 懒生成 + 按题缓存：同一题多次调用返回同一份，保证 GUI、聊天提示、
     * 判分三方看到的选项一致；换题后自动重抽。非 choice 模式不调用。
     *
     * @param playerId 玩家 UUID
     * @return 本题选项（至少包含答案本身），无活跃会话时返回 null
     */
    public List<Word> getChoiceOptions(UUID playerId) {
        QuizState state = quizStates.get(playerId);
        if (state == null) return null;
        Word currentWord = state.session.getCurrentWord();
        if (currentWord == null) return null;
        String key = state.session.getCurrentIndex() + ":"
                + currentWord.getEnglish().toLowerCase();
        if (key.equals(state.choiceOptionsKey) && state.choiceOptions != null) {
            return state.choiceOptions;
        }
        WordBank bank = wordManager.getWordBank(state.difficulty);
        List<Word> options = AnswerChecker.buildChoiceOptions(bank, currentWord);
        state.choiceOptions = options;
        state.choiceOptionsKey = key;
        return options;
    }

    public void showSessionComplete(Player player, AnswerResult result) {
        FileConfiguration messages = getMessages(player);
        String msg = ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.session-complete")
                        .replace("{correct}", String.valueOf(result.correctCount))
                        .replace("{total}", String.valueOf(result.totalWords))
                        .replace("{accuracy}", String.format("%.1f", result.accuracy * 100))
                        .replace("{score}", String.valueOf(result.score)));
        player.sendMessage(msg);

        // Q1：不能再依赖 map 重查 — buildResult 在 completed 时已移出 state，
        // 重查必为 null。结算数据直接取自 result 携带的快照。
        plugin.getProgressManager().getRewardEngine()
                .giveSessionCompleteMoneyReward(player, result.maxComboReached);

        // R-30：先把今日会话落库（player_sessions），再判定成就。
        // checkSessionAchievements → checkDailyStreak 直接查 player_sessions 统计连续
        // 学习天数；若先判定后落库，今天这一行还不在表里，连续天数会少算一天（差一天），
        // DAILY_STREAK_3/7 永远晚一天解锁。调序后今天即算入，阈值逻辑不动。
        plugin.getPlayerDataStore().recordSession(
                player.getUniqueId(),
                result.totalWords,
                result.correctCount,
                result.score,
                result.mode
        );

        plugin.getAchievementManager().checkSessionAchievements(
                player.getUniqueId(),
                result.correctCount,
                result.incorrectCount,
                result.totalWords,
                result.mode
        );
        // Daily challenge: session completed
        plugin.getDailyChallengeManager().updateProgress(player.getUniqueId(), ChallengeType.SESSION_3, 1);
    }

    private FileConfiguration getMessages(Player player) {
        return plugin.getConfigManager().getMessageConfig(plugin.getPlayerLanguage(player));
    }

    // ============================================================
    //  Wrong Book Practice Mode
    // ============================================================

    /**
     * Start practicing a difficult word. The player types the word in chat.
     */
    public void startPracticeWord(UUID playerId, String word) {
        practiceWords.put(playerId, word.toLowerCase());
    }

    /**
     * Check if a player is currently practicing a difficult word.
     */
    public boolean isPracticingWord(UUID playerId) {
        return practiceWords.containsKey(playerId);
    }

    /**
     * Get the word being practiced, or null if not in practice mode.
     */
    public String getPracticingWord(UUID playerId) {
        return practiceWords.get(playerId);
    }

    /**
     * Submit a practice answer. Returns true if correct, false if not.
     * On correct, decrements wrong count and removes from book if count reaches 0.
     */
    public boolean submitPracticeAnswer(Player player, String answer) {
        UUID playerId = player.getUniqueId();
        String word = practiceWords.remove(playerId);
        if (word == null) return false;

        boolean correct = word.equalsIgnoreCase(answer.trim());
        com.letslearnenglish.minecraftplugin.data.PlayerDataStore store = plugin.getPlayerDataStore();

        if (correct) {
            store.decrementWrongCount(playerId, word);
            int newCount = store.getWrongCount(playerId, word);
            org.bukkit.configuration.file.FileConfiguration messages = plugin.getConfigManager()
                    .getMessageConfig(plugin.getPlayerLanguage(player));
            if (newCount <= 0) {
                store.removeDifficultWord(playerId, word);
                String msg = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        messages.getString("wrong-book.practice-removed"));
                player.sendMessage(msg);
            } else {
                String msg = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        messages.getString("wrong-book.practice-correct")
                                .replace("{count}", String.valueOf(newCount)));
                player.sendMessage(msg);
            }
        } else {
            org.bukkit.configuration.file.FileConfiguration messages = plugin.getConfigManager()
                    .getMessageConfig(plugin.getPlayerLanguage(player));
            String msg = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    messages.getString("wrong-book.practice-incorrect")
                            .replace("{word}", word));
            player.sendMessage(msg);
        }

        return correct;
    }

    /**
     * Cancel a practice session.
     */
    public void cancelPractice(UUID playerId) {
        practiceWords.remove(playerId);
    }

    public static class QuizState {
        public final LearningSession session;
        public final String difficulty;
        public final String mode;
        public final AtomicInteger answerTaskId = new AtomicInteger(-1);
        public int answerTimeout;
        public volatile boolean isAnswering;
        public volatile boolean cancelled;
        public final AtomicBoolean submitting = new AtomicBoolean(false);
        public volatile int consecutiveCorrect;
        public volatile int maxComboReached;
        public boolean isReviewMode;
        // FEAT-MODE-01：choice 本题选项快照（GUI/聊天提示/判分三方共用同一份，
        // key = 题号:小写答案，换题自动失效重抽）。
        public volatile List<Word> choiceOptions;
        public volatile String choiceOptionsKey;

        public QuizState(LearningSession session, String difficulty, String mode, int answerTimeout) {
            this.session = session;
            this.difficulty = difficulty;
            this.mode = mode;
            this.answerTimeout = answerTimeout;
            this.isAnswering = false;
            this.cancelled = false;
            this.consecutiveCorrect = 0;
        }
    }

    public static class AnswerResult {
        public final boolean correct;
        public final String correctAnswer;
        public final int currentIndex;
        public final int totalWords;
        public final int correctCount;
        public final int incorrectCount;
        public final boolean sessionComplete;
        public final double accuracy;
        public final int score;
        public final int comboScore;
        public final int consecutiveCorrect;
        public final double comboMultiplier;
        // Q1：结算快照 — completed 时 state 已移出 map，showSessionComplete
        // 凭此二字段完成奖励/成就/日历/挑战结算，不再 map 重查。
        public final int maxComboReached;
        public final String mode;

        public AnswerResult(boolean correct, String correctAnswer, int currentIndex,
                            int totalWords, int correctCount, int incorrectCount,
                            boolean sessionComplete, double accuracy,
                            int score, int comboScore, int consecutiveCorrect, double comboMultiplier,
                            int maxComboReached, String mode) {
            this.correct = correct;
            this.correctAnswer = correctAnswer;
            this.currentIndex = currentIndex;
            this.totalWords = totalWords;
            this.correctCount = correctCount;
            this.incorrectCount = incorrectCount;
            this.sessionComplete = sessionComplete;
            this.accuracy = accuracy;
            this.score = score;
            this.comboScore = comboScore;
            this.consecutiveCorrect = consecutiveCorrect;
            this.comboMultiplier = comboMultiplier;
            this.maxComboReached = maxComboReached;
            this.mode = mode;
        }
    }
}