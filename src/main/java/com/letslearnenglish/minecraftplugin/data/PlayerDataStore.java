package com.letslearnenglish.minecraftplugin.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
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
    }

    /**
     * Load a player's data from the database into cache.
     */
    public void loadPlayerData(UUID playerId) {
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT word, mastery, correct_count, incorrect_count, last_review_time, review_count FROM "
                + tablePrefix + "word_progress WHERE uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

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

        masteredWordsCache.remove(playerId);
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

        try (Connection conn = databaseManager.getConnection()) {
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

        public int getTotalCorrect() { return totalCorrect; }
        public int getTotalIncorrect() { return totalIncorrect; }
        public int getTotalSessions() { return totalSessions; }
        public long getTotalScore() { return totalScore; }

        public int getTotalWords() {
            return totalCorrect + totalIncorrect;
        }
    }
}