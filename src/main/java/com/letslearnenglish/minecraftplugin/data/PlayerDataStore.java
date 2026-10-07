package com.letslearnenglish.minecraftplugin.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import com.letslearnenglish.minecraftplugin.data.DifficultWord;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;

/**
 * Player Data Store
 *
 * Manages all player-related data including:
 * - Word mastery levels
 * - Review schedules
 * - Learning statistics
 * - Session state tracking
 *
 * Uses an in-memory cache with database persistence.
 * All cache operations are thread-safe via ConcurrentHashMap.
 */
public class PlayerDataStore {

    private final LetsLearnEnglish plugin;
    private final DatabaseManager databaseManager;

    private final Map<UUID, Map<String, Double>> wordMasteryCache;
    private final Map<UUID, Map<String, Long>> lastReviewTimeCache;
    private final Map<UUID, Map<String, Integer>> reviewCountCache;
    private final Map<UUID, Map<String, Integer>> correctCountCache;
    private final Map<UUID, Map<String, Integer>> incorrectCountCache;
    private final Map<UUID, PlayerStats> playerStatsCache;
    private final Set<UUID> activeSessionPlayers;
    private final Map<UUID, Set<String>> masteredWordsCache;
    private final Map<UUID, ProfileSettings> profileSettingsCache;
    private final Map<UUID, Map<String, DifficultWord>> difficultWordsCache;
    private final Map<UUID, Map<String, Integer>> wrongCountCache;

    public PlayerDataStore(LetsLearnEnglish plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.wordMasteryCache = new ConcurrentHashMap<>();
        this.lastReviewTimeCache = new ConcurrentHashMap<>();
        this.reviewCountCache = new ConcurrentHashMap<>();
        this.correctCountCache = new ConcurrentHashMap<>();
        this.incorrectCountCache = new ConcurrentHashMap<>();
        this.playerStatsCache = new ConcurrentHashMap<>();
        this.activeSessionPlayers = ConcurrentHashMap.newKeySet();
        this.masteredWordsCache = new ConcurrentHashMap<>();
        this.profileSettingsCache = new ConcurrentHashMap<>();
        this.difficultWordsCache = new ConcurrentHashMap<>();
        this.wrongCountCache = new ConcurrentHashMap<>();
    }

    /**
     * 记录一次学习会话到 player_sessions 表。
     * 同一天多次会话会合并（UPSERT，累加 words/correct/score）。
     */
    public void recordSession(UUID playerId, int wordsCount, int correctCount, long score, String mode) {
        String tablePrefix = databaseManager.getTablePrefix();
        java.time.LocalDate today = java.time.LocalDate.now();
        String dateStr = today.toString();

        String sql = databaseManager.isSQLite()
                ? "INSERT INTO " + tablePrefix + "player_sessions (uuid, session_date, words_count, correct_count, score, mode) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(uuid, session_date) DO UPDATE SET words_count = words_count + excluded.words_count, correct_count = correct_count + excluded.correct_count, score = score + excluded.score"
                : "INSERT INTO " + tablePrefix + "player_sessions (uuid, session_date, words_count, correct_count, score, mode) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE words_count = words_count + VALUES(words_count), correct_count = correct_count + VALUES(correct_count), score = score + VALUES(score)";

        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, dateStr);
            stmt.setInt(3, wordsCount);
            stmt.setInt(4, correctCount);
            stmt.setLong(5, score);
            stmt.setString(6, mode);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to record session: " + playerId, e);
        }
    }

    /**
     * 获取玩家最近 N 天的学习天数列表（用于日历展示）。
     * 返回 Map<日期字符串, 当天的words_count>。
     */
    public Map<String, Integer> getSessionDays(UUID playerId, int daysBack) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT session_date, words_count FROM " + tablePrefix + "player_sessions WHERE uuid = ? AND session_date >= ? ORDER BY session_date ASC";

        java.time.LocalDate since = java.time.LocalDate.now().minusDays(daysBack);
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, since.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("session_date"), rs.getInt("words_count"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load session days: " + playerId, e);
        }
        return result;
    }

    /**
     * Load a player's data from the database into cache.
     */
    public void loadPlayerData(UUID playerId) {
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT word, mastery, correct_count, incorrect_count, last_review_time, review_count FROM "
                + tablePrefix + "word_progress WHERE uuid = ?";

        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                Map<String, Double> masteryMap = new ConcurrentHashMap<>();
                Map<String, Long> reviewTimeMap = new ConcurrentHashMap<>();
                Map<String, Integer> reviewCountMap = new ConcurrentHashMap<>();
                Map<String, Integer> correctMap = new ConcurrentHashMap<>();
                Map<String, Integer> incorrectMap = new ConcurrentHashMap<>();

                while (rs.next()) {
                    String word = rs.getString("word");
                    masteryMap.put(word, rs.getDouble("mastery"));
                    reviewTimeMap.put(word, rs.getLong("last_review_time"));
                    reviewCountMap.put(word, rs.getInt("review_count"));
                    correctMap.put(word, rs.getInt("correct_count"));
                    incorrectMap.put(word, rs.getInt("incorrect_count"));
                }

                wordMasteryCache.put(playerId, masteryMap);
                lastReviewTimeCache.put(playerId, reviewTimeMap);
                reviewCountCache.put(playerId, reviewCountMap);
                correctCountCache.put(playerId, correctMap);
                incorrectCountCache.put(playerId, incorrectMap);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player data: " + playerId, e);
            wordMasteryCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
            lastReviewTimeCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
            reviewCountCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
            correctCountCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
            incorrectCountCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
        }

        // 从 word_progress 表重建玩家统计数据
        PlayerStats stats = new PlayerStats();
        Map<String, Integer> correctMap = correctCountCache.get(playerId);
        Map<String, Integer> incorrectMap = incorrectCountCache.get(playerId);
        if (correctMap != null) {
            for (int c : correctMap.values()) stats.addCorrect(c);
        }
        if (incorrectMap != null) {
            for (int c : incorrectMap.values()) stats.addIncorrect(c);
        }

        // 从 player_data 表加载 sessions 和 score
        String statsSql = "SELECT total_sessions, total_score FROM " + tablePrefix + "player_data WHERE uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(statsSql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int sessions = rs.getInt("total_sessions");
                    long score = rs.getLong("total_score");
                    stats.setSessions(sessions);
                    stats.setScore(score);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player stats: " + playerId, e);
        }
        playerStatsCache.put(playerId, stats);

        // Load difficult words / wrong book data
        loadDifficultWordsFromDB(playerId);

        masteredWordsCache.remove(playerId);
    }

    /**
     * Load difficult words from database into cache.
     */
    private void loadDifficultWordsFromDB(UUID playerId) {
        List<DifficultWord> words = databaseManager.getDifficultWords(playerId);
        Map<String, DifficultWord> wordMap = new ConcurrentHashMap<>();
        Map<String, Integer> countMap = new ConcurrentHashMap<>();
        for (DifficultWord dw : words) {
            wordMap.put(dw.getWord().toLowerCase(), dw);
            countMap.put(dw.getWord().toLowerCase(), dw.getWrongCount());
        }
        difficultWordsCache.put(playerId, wordMap);
        wrongCountCache.put(playerId, countMap);
    }

    /**
     * Get all difficult words for a player from cache, loading from DB if needed.
     * Returns a map of word → DifficultWord data.
     */
    public Map<String, DifficultWord> getDifficultWordsData(UUID playerId) {
        Map<String, DifficultWord> cached = difficultWordsCache.get(playerId);
        if (cached == null) {
            loadDifficultWordsFromDB(playerId);
            cached = difficultWordsCache.get(playerId);
        }
        return cached != null ? cached : new ConcurrentHashMap<>();
    }

    /**
     * Get the set of difficult word strings for a player.
     */
    public Set<String> getDifficultWords(UUID playerId) {
        return getDifficultWordsData(playerId).keySet();
    }

    /**
     * Get the wrong count for a specific word.
     */
    public int getWrongCount(UUID playerId, String word) {
        Map<String, Integer> counts = wrongCountCache.get(playerId);
        if (counts == null) {
            loadDifficultWordsFromDB(playerId);
            counts = wrongCountCache.get(playerId);
        }
        if (counts != null) {
            Integer count = counts.get(word.toLowerCase());
            if (count != null) return count;
        }
        return 0;
    }

    /**
     * Increment wrong count for a word. If it reaches the threshold, adds to difficult words.
     * Runs DB increment asynchronously. Returns the new count.
     */
    public int incrementWrongCount(UUID playerId, String word) {
        String wordLower = word.toLowerCase();

        // Update in-memory wrong count cache
        Map<String, Integer> counts = wrongCountCache.computeIfAbsent(playerId,
                k -> new ConcurrentHashMap<>());
        int newCount = counts.merge(wordLower, 1, Integer::sum);

        // Async DB increment
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            databaseManager.incrementWrongCount(playerId, wordLower);
        });

        return newCount;
    }

    /**
     * Mark a word as difficult (add to wrong book cache).
     */
    public void addDifficultWord(UUID playerId, String word) {
        String wordLower = word.toLowerCase();
        Map<String, DifficultWord> words = difficultWordsCache.computeIfAbsent(playerId,
                k -> new ConcurrentHashMap<>());
        int count = getWrongCount(playerId, wordLower);
        words.put(wordLower, new DifficultWord(wordLower, Math.max(count, 3), System.currentTimeMillis()));
    }

    /**
     * Remove a word from the difficult words cache and database.
     */
    public void removeDifficultWord(UUID playerId, String word) {
        String wordLower = word.toLowerCase();
        Map<String, DifficultWord> words = difficultWordsCache.get(playerId);
        if (words != null) {
            words.remove(wordLower);
        }
        Map<String, Integer> counts = wrongCountCache.get(playerId);
        if (counts != null) {
            counts.remove(wordLower);
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            databaseManager.removeDifficultWord(playerId, wordLower);
        });
    }

    /**
     * Decrement wrong count by 1 (on practice correct). If reaches 0, remove from book.
     */
    public void decrementWrongCount(UUID playerId, String word) {
        String wordLower = word.toLowerCase();
        Map<String, Integer> counts = wrongCountCache.get(playerId);
        if (counts != null) {
            int newCount = counts.merge(wordLower, -1, Integer::sum);
            if (newCount <= 0) {
                counts.remove(wordLower);
                Map<String, DifficultWord> words = difficultWordsCache.get(playerId);
                if (words != null) {
                    words.remove(wordLower);
                }
            }
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            databaseManager.decrementWrongCount(playerId, wordLower);
            // Check if count reached 0 in DB and remove
            int dbCount = databaseManager.getWrongCount(playerId, wordLower);
            if (dbCount <= 0) {
                databaseManager.removeDifficultWord(playerId, wordLower);
            }
        });
    }

    /**
     * Save difficult words cache to database.
     */
    public void saveDifficultWords(UUID playerId) {
        // Difficult words are written incrementally via async DB ops,
        // so no explicit full save is needed. This method exists for API consistency.
    }

    /**
     * Save a player's data from cache to the database.
     * Uses UPSERT (INSERT OR REPLACE for SQLite, INSERT...ON DUPLICATE KEY UPDATE for MySQL)
     * in a single transaction to eliminate the DELETE+INSERT double-operation.
     */
    public void savePlayerData(UUID playerId) {
        Map<String, Double> masteryMap = wordMasteryCache.get(playerId);
        if (masteryMap == null) {
            return;
        }

        String tablePrefix = databaseManager.getTablePrefix();
        String upsertSql = buildUpsertSql(tablePrefix);

        Connection conn = databaseManager.getConnection();
        try {
            conn.setAutoCommit(false);

            try (PreparedStatement upsertStmt = conn.prepareStatement(upsertSql)) {

                for (Map.Entry<String, Double> entry : masteryMap.entrySet()) {
                    String word = entry.getKey();

                    upsertStmt.setString(1, playerId.toString());
                    upsertStmt.setString(2, word);
                    upsertStmt.setDouble(3, entry.getValue());
                    upsertStmt.setInt(4, getIntValue(correctCountCache.get(playerId), word));
                    upsertStmt.setInt(5, getIntValue(incorrectCountCache.get(playerId), word));
                    upsertStmt.setLong(6, getLongValue(lastReviewTimeCache.get(playerId), word));
                    upsertStmt.setInt(7, getIntValue(reviewCountCache.get(playerId), word));

                    upsertStmt.addBatch();
                }

                upsertStmt.executeBatch();

                // 保存玩家统计数据到 player_data 表
                PlayerStats stats = playerStatsCache.get(playerId);
                if (stats != null) {
                    String statsSql = databaseManager.isSQLite()
                            ? "INSERT INTO " + tablePrefix + "player_data (uuid, total_words_learned, total_sessions, total_score, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET total_words_learned=excluded.total_words_learned, total_sessions=excluded.total_sessions, total_score=excluded.total_score, updated_at=excluded.updated_at"
                            : "INSERT INTO " + tablePrefix + "player_data (uuid, total_words_learned, total_sessions, total_score, updated_at) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE total_words_learned=VALUES(total_words_learned), total_sessions=VALUES(total_sessions), total_score=VALUES(total_score), updated_at=VALUES(updated_at)";
                    try (PreparedStatement statsStmt = conn.prepareStatement(statsSql)) {
                        statsStmt.setString(1, playerId.toString());
                        statsStmt.setInt(2, stats.getTotalWords());
                        statsStmt.setInt(3, stats.getTotalSessions());
                        statsStmt.setLong(4, stats.getTotalScore());
                        statsStmt.setLong(5, System.currentTimeMillis());
                        statsStmt.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save player data: " + playerId, e);
        }
    }

    private String buildUpsertSql(String tablePrefix) {
        if (databaseManager.isSQLite()) {
            return "INSERT OR REPLACE INTO " + tablePrefix
                    + "word_progress (uuid, word, mastery, correct_count, incorrect_count, last_review_time, review_count) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        } else {
            return "INSERT INTO " + tablePrefix
                    + "word_progress (uuid, word, mastery, correct_count, incorrect_count, last_review_time, review_count) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE mastery=VALUES(mastery), correct_count=VALUES(correct_count), "
                    + "incorrect_count=VALUES(incorrect_count), last_review_time=VALUES(last_review_time), "
                    + "review_count=VALUES(review_count)";
        }
    }

    private int getIntValue(Map<String, Integer> map, String key) {
        if (map == null) return 0;
        Integer val = map.get(key);
        return val != null ? val : 0;
    }

    private long getLongValue(Map<String, Long> map, String key) {
        if (map == null) return 0L;
        Long val = map.get(key);
        return val != null ? val : 0L;
    }

    /**
     * Get the set of words a player has mastered.
     * Results are cached and invalidated automatically when mastery data changes.
     */
    public Set<String> getMasteredWords(UUID playerId) {
        Set<String> cached = masteredWordsCache.get(playerId);
        if (cached != null) {
            return cached;
        }

        Map<String, Double> masteryMap = wordMasteryCache.get(playerId);
        if (masteryMap == null) {
            loadPlayerData(playerId);
            masteryMap = wordMasteryCache.get(playerId);
        }

        double threshold = plugin.getConfigManager().getMainConfig()
                .getDouble("word-system.review.mastery-threshold", 0.85);

        Set<String> mastered = new HashSet<>();
        if (masteryMap != null) {
            for (Map.Entry<String, Double> entry : masteryMap.entrySet()) {
                if (entry.getValue() >= threshold) {
                    mastered.add(entry.getKey());
                }
            }
        }

        Set<String> unmodifiable = Collections.unmodifiableSet(mastered);
        masteredWordsCache.put(playerId, unmodifiable);
        return unmodifiable;
    }

    /**
     * Get a player's mastery level for a specific word.
     * Uses computeIfAbsent to avoid redundant loadPlayerData calls across threads.
     */
    public double getWordMastery(UUID playerId, String word) {
        Map<String, Double> masteryMap = wordMasteryCache.computeIfAbsent(playerId, k -> {
            loadPlayerData(k);
            return wordMasteryCache.getOrDefault(k, new ConcurrentHashMap<>());
        });

        Double val = masteryMap.get(word.toLowerCase());
        return val != null ? val : 0.0;
    }

    /**
     * Update word progress after a learning session.
     * Invalidates the mastered words cache for the player.
     */
    public void updateWordProgress(UUID playerId, Map<String, LearningSession.WordResult> results) {
        Map<String, Double> masteryMap = wordMasteryCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> correctMap = correctCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> incorrectMap = incorrectCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());

        int sessionCorrect = 0;
        int sessionIncorrect = 0;

        for (Map.Entry<String, LearningSession.WordResult> entry : results.entrySet()) {
            String word = entry.getKey().toLowerCase();
            LearningSession.WordResult result = entry.getValue();

            int wordCorrect = result.getCorrectCount();
            int wordIncorrect = result.getIncorrectCount();
            correctMap.merge(word, wordCorrect, Integer::sum);
            incorrectMap.merge(word, wordIncorrect, Integer::sum);
            sessionCorrect += wordCorrect;
            sessionIncorrect += wordIncorrect;

            double currentMastery = masteryMap.getOrDefault(word, 0.0);
            double newMastery;

            if (result.isMastered()) {
                newMastery = Math.min(1.0, currentMastery + 0.2);
            } else {
                newMastery = Math.max(0.0, currentMastery - (wordIncorrect * 0.1));
            }

            masteryMap.put(word, newMastery);
        }

        masteredWordsCache.remove(playerId);

        updatePlayerStats(playerId, sessionCorrect, sessionIncorrect);
    }

    private void updatePlayerStats(UUID playerId, int correct, int incorrect) {
        PlayerStats stats = playerStatsCache.computeIfAbsent(playerId, k -> new PlayerStats());
        stats.addCorrect(correct);
        stats.addIncorrect(incorrect);
        stats.incrementSessions();
    }

    /**
     * Get last review times for a player's words.
     */
    public Map<String, Long> getLastReviewTimes(UUID playerId) {
        return lastReviewTimeCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
    }

    /**
     * Get review counts for a player's words.
     */
    public Map<String, Integer> getReviewCounts(UUID playerId) {
        return reviewCountCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
    }

    /**
     * Record a completed review for a word.
     */
    public void recordReview(UUID playerId, String word) {
        String wordLower = word.toLowerCase();

        Map<String, Long> reviewTimes = lastReviewTimeCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        reviewTimes.put(wordLower, System.currentTimeMillis());

        Map<String, Integer> reviewCounts = reviewCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        reviewCounts.merge(wordLower, 1, Integer::sum);
    }

    /**
     * Check if a player is currently in a learning session.
     */
    public boolean isPlayerInSession(UUID playerId) {
        return activeSessionPlayers.contains(playerId);
    }

    /**
     * Mark a player as being in a session.
     */
    public void setPlayerInSession(UUID playerId, boolean inSession) {
        if (inSession) {
            activeSessionPlayers.add(playerId);
        } else {
            activeSessionPlayers.remove(playerId);
        }
    }

    /**
     * Unload a player's data from cache (called on player quit).
     */
    public void unloadPlayerData(UUID playerId) {
        savePlayerData(playerId);
        wordMasteryCache.remove(playerId);
        lastReviewTimeCache.remove(playerId);
        reviewCountCache.remove(playerId);
        correctCountCache.remove(playerId);
        incorrectCountCache.remove(playerId);
        playerStatsCache.remove(playerId);
        activeSessionPlayers.remove(playerId);
        masteredWordsCache.remove(playerId);
        difficultWordsCache.remove(playerId);
        wrongCountCache.remove(playerId);
    }

    /**
     * Clear all in-memory caches.
     * Called during /lea reload to ensure fresh data from disk.
     */
    public void clearCache() {
        wordMasteryCache.clear();
        lastReviewTimeCache.clear();
        reviewCountCache.clear();
        correctCountCache.clear();
        incorrectCountCache.clear();
        playerStatsCache.clear();
        activeSessionPlayers.clear();
        masteredWordsCache.clear();
        difficultWordsCache.clear();
        wrongCountCache.clear();
    }

    /**
     * Get a player's aggregate learning statistics.
     */
    public PlayerStats getPlayerStats(UUID playerId) {
        PlayerStats stats = playerStatsCache.get(playerId);
        if (stats == null) {
            loadPlayerData(playerId);
            stats = playerStatsCache.computeIfAbsent(playerId, k -> new PlayerStats());
        }
        return stats;
    }

    /**
     * Player aggregate statistics for data export and display.
     */
    public static class PlayerStats {
        private int totalCorrect;
        private int totalIncorrect;
        private int totalSessions;
        private long totalScore;

        public void addCorrect(int count) { totalCorrect += count; }
        public void addIncorrect(int count) { totalIncorrect += count; }
        public void incrementSessions() { totalSessions++; }
        public void addScore(long score) { totalScore += score; }
        public void setSessions(int sessions) { totalSessions = sessions; }
        public void setScore(long score) { totalScore = score; }

        public int getTotalCorrect() { return totalCorrect; }
        public int getTotalIncorrect() { return totalIncorrect; }
        public int getTotalSessions() { return totalSessions; }
        public long getTotalScore() { return totalScore; }

        public int getTotalWords() {
            return totalCorrect + totalIncorrect;
        }
    }

    public static class ProfileSettings {
        private int age;
        private boolean showAnswers = true;
        private boolean showResults = true;
        private boolean showInSquare = true;

        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public boolean isShowAnswers() { return showAnswers; }
        public void setShowAnswers(boolean showAnswers) { this.showAnswers = showAnswers; }
        public boolean isShowResults() { return showResults; }
        public void setShowResults(boolean showResults) { this.showResults = showResults; }
        public boolean isShowInSquare() { return showInSquare; }
        public void setShowInSquare(boolean showInSquare) { this.showInSquare = showInSquare; }
    }

    public ProfileSettings getProfileSettings(UUID playerId) {
        return profileSettingsCache.computeIfAbsent(playerId, k -> new ProfileSettings());
    }

    public void saveProfileSettings(UUID playerId) {
        ProfileSettings settings = profileSettingsCache.get(playerId);
        if (settings == null) return;

        String tablePrefix = databaseManager.getTablePrefix();
        String sql = databaseManager.isSQLite()
                ? "INSERT OR REPLACE INTO " + tablePrefix + "player_profiles (uuid, age, show_answers, show_results, show_in_square) VALUES (?, ?, ?, ?, ?)"
                : "INSERT INTO " + tablePrefix + "player_profiles (uuid, age, show_answers, show_results, show_in_square) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE age=VALUES(age), show_answers=VALUES(show_answers), show_results=VALUES(show_results), show_in_square=VALUES(show_in_square)";

        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setInt(2, settings.getAge());
            stmt.setBoolean(3, settings.isShowAnswers());
            stmt.setBoolean(4, settings.isShowResults());
            stmt.setBoolean(5, settings.isShowInSquare());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save profile settings: " + playerId, e);
        }
    }

    public void loadProfileSettings(UUID playerId) {
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT age, show_answers, show_results, show_in_square FROM " + tablePrefix + "player_profiles WHERE uuid = ?";

        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ProfileSettings settings = new ProfileSettings();
                    settings.setAge(rs.getInt("age"));
                    settings.setShowAnswers(rs.getBoolean("show_answers"));
                    settings.setShowResults(rs.getBoolean("show_results"));
                    settings.setShowInSquare(rs.getBoolean("show_in_square"));
                    profileSettingsCache.put(playerId, settings);
                } else {
                    profileSettingsCache.putIfAbsent(playerId, new ProfileSettings());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load profile settings: " + playerId, e);
            profileSettingsCache.putIfAbsent(playerId, new ProfileSettings());
        }
    }

    public void unloadProfileSettings(UUID playerId) {
        saveProfileSettings(playerId);
        profileSettingsCache.remove(playerId);
    }

    public void setAge(UUID playerId, int age) {
        ProfileSettings settings = getProfileSettings(playerId);
        settings.setAge(age);
    }

    public List<UUID> getActivePlayerIds() {
        return new ArrayList<>(profileSettingsCache.keySet());
    }

    public String getPlayerName(UUID playerId) {
        org.bukkit.OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(playerId);
        if (offlinePlayer != null && offlinePlayer.getName() != null) {
            return offlinePlayer.getName();
        }
        return playerId.toString();
    }

    public void clearProfileCache() {
        profileSettingsCache.clear();
    }

    /**
     * Get the top N players ranked by total_score from the player_data table.
     * Used for all-time leaderboard display.
     */
    public List<LeaderboardEntry> getRankedPlayers(int count) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT uuid, player_name, total_words_learned, total_sessions, total_score "
                + "FROM " + tablePrefix + "player_data "
                + "ORDER BY total_score DESC LIMIT ?";

        Connection conn = databaseManager.getConnection();

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, count);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawRows.add(new Object[]{
                        rs.getString("uuid"),
                        rs.getString("player_name"),
                        rs.getLong("total_score"),
                        rs.getInt("total_sessions")
                    });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load ranked players", e);
            return entries;
        }

        // Step 2: ResultSet closed — now safe to call getPlayerStats (opens new Statement)
        // 注意：此处不能调用任何 Bukkit API（如 getPlayerName），因为本方法会在插件启动的异步任务里被调用。
        // 因此 player_name 为空时直接回落到 uuid.toString()。
        int rank = 1;
        for (Object[] row : rawRows) {
            UUID uuid = UUID.fromString((String) row[0]);
            PlayerStats stats = getPlayerStats(uuid);
            String name = (String) row[1];
            if (name == null || name.isEmpty()) {
                name = uuid.toString();
            }
            entries.add(new LeaderboardEntry(uuid, name, (Long) row[2],
                    stats.getTotalCorrect(), stats.getTotalIncorrect(),
                    (Integer) row[3], rank++));
        }
        return entries;
    }

    /**
     * Get the top N players ranked by score within a date range from player_sessions.
     * Used for weekly/monthly leaderboard display.
     */
    public List<LeaderboardEntry> getRankedPlayersForPeriod(int count, LocalDate since, LocalDate until) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String tablePrefix = databaseManager.getTablePrefix();
        // LEFT JOIN player_data 直接取 player_name，避免回落到 Bukkit API（本方法可能在异步线程被调用）。
        // player_name 用 MAX() 包住以兼容 MySQL 的 ONLY_FULL_GROUP_BY。
        String sql = "SELECT s.uuid AS uuid, MAX(p.player_name) AS player_name, "
                + "SUM(s.score) as period_score, "
                + "SUM(s.correct_count) as total_correct, SUM(s.words_count) as total_words "
                + "FROM " + tablePrefix + "player_sessions s "
                + "LEFT JOIN " + tablePrefix + "player_data p ON p.uuid = s.uuid "
                + "WHERE s.session_date >= ? AND s.session_date <= ? "
                + "GROUP BY s.uuid ORDER BY period_score DESC LIMIT ?";

        Connection conn = databaseManager.getConnection();

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, since.toString());
            stmt.setString(2, until.toString());
            stmt.setInt(3, count);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawRows.add(new Object[]{
                        rs.getString("uuid"),
                        rs.getString("player_name"),
                        rs.getLong("period_score"),
                        rs.getInt("total_correct"),
                        rs.getInt("total_words")
                    });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load ranked players for period", e);
            return entries;
        }

        // Step 2: ResultSet closed — 此处不能调用任何 Bukkit API（如 getPlayerName），
        // 因为本方法会在插件启动的异步任务里被调用；player_name 为空时回落到 uuid.toString()。
        int rank = 1;
        for (Object[] row : rawRows) {
            UUID uuid = UUID.fromString((String) row[0]);
            String name = (String) row[1];
            if (name == null || name.isEmpty()) {
                name = uuid.toString();
            }
            long periodScore = (Long) row[2];
            int periodCorrect = (Integer) row[3];
            int periodWords = (Integer) row[4];
            int periodIncorrect = Math.max(0, periodWords - periodCorrect);
            int sessions = 0; // Not easily available from aggregated query
            entries.add(new LeaderboardEntry(uuid, name, periodScore,
                    periodCorrect, periodIncorrect, sessions, rank++));
        }
        return entries;
    }
}
