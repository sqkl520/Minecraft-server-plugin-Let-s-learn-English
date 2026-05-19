package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class WordLearningManager {

    private static final int BASE_SCORE = 10;
    private static final int INCORRECT_PENALTY = 2;
    private static final int[] COMBO_THRESHOLDS = {0, 3, 5, 7, 10};
    private static final double[] COMBO_MULTIPLIERS = {1.0, 1.5, 2.0, 2.5, 3.0};

    private final LetsLearnEnglish plugin;
    private final WordManagerImpl wordManager;
    private final Map<UUID, QuizState> quizStates = new ConcurrentHashMap<>();

    public WordLearningManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.wordManager = plugin.getWordManager();
    }

    public LearningSession startQuiz(Player player, String difficulty, String mode) {
        LearningSession session = wordManager.startSession(player, difficulty, mode);
        int timeout = plugin.getConfigManager().getMainConfig().getInt("word-system.answer-timeout", 30);
        quizStates.put(player.getUniqueId(), new QuizState(session, difficulty, mode, timeout));
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

            boolean correct = currentWord.getEnglish().equalsIgnoreCase(answer.trim());
            String correctAnswer = currentWord.getEnglish();

            int comboScore;
            if (correct) {
                state.consecutiveCorrect++;
                double multiplier = getComboMultiplier(state.consecutiveCorrect);
                comboScore = (int) Math.round(BASE_SCORE * multiplier);
                session.addScore(comboScore);
                session.recordCorrect();
            } else {
                state.consecutiveCorrect = 0;
                comboScore = -INCORRECT_PENALTY;
                session.addScore(-INCORRECT_PENALTY);
                session.recordIncorrect();
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

            state.consecutiveCorrect = 0;
            session.addScore(-INCORRECT_PENALTY);
            session.recordIncorrect();

            return buildResult(id, state, false, currentWord.getEnglish(),
                    -INCORRECT_PENALTY, 0, 1.0);
        } finally {
            state.submitting.set(false);
        }
    }

    private AnswerResult buildResult(UUID id, QuizState state, boolean correct,
                                      String correctAnswer, int comboScore,
                                      int consecutiveCorrect, double comboMultiplier) {
        LearningSession session = state.session;
        boolean completed = session.isCompleted();
        if (completed) {
            wordManager.endSession(id);
            quizStates.remove(id);
        }
        return new AnswerResult(correct, correctAnswer,
                session.getCurrentIndex(), session.getTotalWords(),
                session.getTotalCorrect(), session.getTotalIncorrect(),
                completed, session.getAccuracy(), session.getScore(),
                comboScore, consecutiveCorrect, comboMultiplier);
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
                            messages.getString("word-learning-gui.timeout",
                                    "&c\u23f1 Time's up! Correct answer: &e{answer}")
                                    .replace("{answer}", timeoutResult.correctAnswer)));
                    if (timeoutResult.sessionComplete) {
                        showSessionComplete(player, timeoutResult);
                    } else {
                        new WordLearningGUI(plugin).openWithMessage(player, state.difficulty, state.mode);
                    }
                }
            } else if (remaining[0] <= 5) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("word-learning-gui.countdown-urgent",
                                "&c\u23f3 {seconds}s remaining!")
                                .replace("{seconds}", String.valueOf(remaining[0]))));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("word-learning-gui.countdown-normal",
                                "&e\u23f3 {seconds}s remaining...")
                                .replace("{seconds}", String.valueOf(remaining[0]))));
            }
        }, 20L, 20L);

        state.answerTaskId = task.getTaskId();
    }

    public void cancelAnswerTimer(Player player) {
        UUID id = player.getUniqueId();
        QuizState state = quizStates.get(id);
        if (state == null) return;

        state.cancelled = true;

        if (state.answerTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(state.answerTaskId);
            state.answerTaskId = -1;
        }
        state.isAnswering = false;
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
        quizStates.remove(id);

        int score = state.session.getScore();
        int correct = state.session.getTotalCorrect();
        int total = state.session.getTotalWords();

        FileConfiguration messages = getMessages(player);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.session-stopped",
                        "&aSession stopped! &7Correct: &e{correct}&7/&e{total} &7Score: &e{score}")
                        .replace("{correct}", String.valueOf(correct))
                        .replace("{total}", String.valueOf(total))
                        .replace("{score}", String.valueOf(score))));
    }

    public void endQuiz(UUID playerId) {
        QuizState state = quizStates.get(playerId);
        if (state != null && state.answerTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(state.answerTaskId);
        }
        quizStates.remove(playerId);
        wordManager.endSession(playerId);
    }

    public void clearAllSessions() {
        for (UUID playerId : quizStates.keySet()) {
            QuizState state = quizStates.get(playerId);
            if (state != null && state.answerTaskId != -1) {
                plugin.getServer().getScheduler().cancelTask(state.answerTaskId);
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

    public void showSessionComplete(Player player, AnswerResult result) {
        FileConfiguration messages = getMessages(player);
        String msg = ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.session-complete",
                        "&6\u2605 &aSession complete! &7Correct: &e{correct}&7/&e{total} &7Accuracy: &e{accuracy}% &7Score: &e{score}")
                        .replace("{correct}", String.valueOf(result.correctCount))
                        .replace("{total}", String.valueOf(result.totalWords))
                        .replace("{accuracy}", String.format("%.1f", result.accuracy * 100))
                        .replace("{score}", String.valueOf(result.score)));
        player.sendMessage(msg);
    }

    private FileConfiguration getMessages(Player player) {
        return plugin.getConfigManager().getMessageConfig(plugin.getPlayerLanguage(player));
    }

    public static class QuizState {
        public final LearningSession session;
        public final String difficulty;
        public final String mode;
        public int answerTaskId;
        public int answerTimeout;
        public boolean isAnswering;
        public volatile boolean cancelled;
        public final AtomicBoolean submitting = new AtomicBoolean(false);
        public int consecutiveCorrect;

        public QuizState(LearningSession session, String difficulty, String mode, int answerTimeout) {
            this.session = session;
            this.difficulty = difficulty;
            this.mode = mode;
            this.answerTaskId = -1;
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

        public AnswerResult(boolean correct, String correctAnswer, int currentIndex,
                            int totalWords, int correctCount, int incorrectCount,
                            boolean sessionComplete, double accuracy,
                            int score, int comboScore, int consecutiveCorrect, double comboMultiplier) {
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
        }
    }
}