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
    // H10：玩家名缓存（join 时由主线程写入，savePlayerData 读出随 UPSERT 落盘）。
    // 不走 Bukkit API，保证异步保存线程安全；quit 时随 unload 清理。
    private final Map<UUID, String> playerNameCache;

    // ===== 今日计数器（内存懒清零，服务器时区自然日）=====
    // 每个条目记录 (日期, 计数)；读写时若日期 != 今天则视为 0 并顺手重置。
    // 重启/关服丢失是可接受的：这是防刷上限而非账本，账本以 DB 的 lifetime 字段为准。
    private final Map<UUID, TodayCounter> todayAnswerCountCache;
    private final Map<UUID, TodayCounter> todayAiRoundCache;

    // R-28：AI 对话累计次数缓存（镜像 player_data.conversation_count）。
    // join 时随 loadPlayerData 载入；incrementConversationCount 先改缓存再即时落库，
    // 让 AI_CHAT_10/50 成就计数跨重启不丢。
    private final Map<UUID, Integer> conversationCountCache;

    // Phase 3 S-A（H11）：错题本累计攻克词数缓存（镜像 player_data.wrong_book_cleared）。
    // join 时随 loadPlayerData 载入；incrementWrongBookCleared 先改缓存再即时落库
    // （绝对值 UPSERT，同 R-28 pattern），让 WRONG_BOOK_CLEARED 成就计数跨重启不丢。
    private final Map<UUID, Integer> wrongBookClearedCache;

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
        this.playerNameCache = new ConcurrentHashMap<>();
        this.todayAnswerCountCache = new ConcurrentHashMap<>();
        this.todayAiRoundCache = new ConcurrentHashMap<>();
        this.conversationCountCache = new ConcurrentHashMap<>();
        this.wrongBookClearedCache = new ConcurrentHashMap<>();
    }

    /**
     * 今日答题计数器（int 版）。
     * 内部用 AtomicInteger + volatile 日期做懒清零；并发下允许极小误差（防刷上限场景可接受）。
     */
    private static class TodayCounter {
        private volatile LocalDate date;
        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);

        int increment() {
            LocalDate today = LocalDate.now();
            if (!today.equals(date)) {
                synchronized (this) {
                    if (!today.equals(date)) {
                        date = today;
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet();
        }

        int get() {
            LocalDate today = LocalDate.now();
            if (!today.equals(date)) {
                return 0;
            }
            return count.get();
        }
    }

    /**
     * 今日答题计数 +1（每次提交答案时调用，无论对错），返回递增后的值。
     * 按服务器时区自然日懒清零。
     */
    public int incrementTodayAnswerCount(UUID playerId) {
        return todayAnswerCountCache.computeIfAbsent(playerId, k -> new TodayCounter()).increment();
    }

    /**
     * 读取玩家今日答题计数。跨天后自动视为 0。
     */
    public int getTodayAnswerCount(UUID playerId) {
        TodayCounter counter = todayAnswerCountCache.get(playerId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 今日 AI 有效轮次 +1（由 AIChatManager 在一轮对话记为有效时调用），返回递增后的值。
     * 按服务器时区自然日懒清零。
     */
    public int incrementTodayAiRound(UUID playerId) {
        return todayAiRoundCache.computeIfAbsent(playerId, k -> new TodayCounter()).increment();
    }

    /**
     * 读取玩家今日 AI 有效轮次。跨天后自动视为 0。
     */
    public int getTodayAiRound(UUID playerId) {
        TodayCounter counter = todayAiRoundCache.get(playerId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * R-28：读取玩家 AI 对话累计次数（供 AI_CHAT_10/50 成就判定）。
     * 缓存未命中时懒加载（loadPlayerData 会填入 conversation_count）。
     */
    public int getConversationCount(UUID playerId) {
        Integer v = conversationCountCache.get(playerId);
        if (v == null) {
            loadPlayerData(playerId);
            v = conversationCountCache.get(playerId);
        }
        return v != null ? v : 0;
    }

    /**
     * R-28：AI 对话累计次数 +1，返回递增后的值。
     *
     * <p>缓存为权威：先确保已从 DB 载入基数，+1 后即时经
     * {@code DatabaseManager.setConversationCount} 落库（绝对值 UPSERT）。
     * 之所以即时落库而不是等 savePlayerData，是因为 quit 时
     * {@code ChatScenarioListener.endConversationOnQuit}（触发本方法）与
     * {@code PlayerJoinListener} 的 unloadPlayerData（落盘+清缓存）是同一
     * PlayerQuitEvent 上的两个独立监听器，执行顺序不保证；若只改缓存等 save，
     * 当 unload 先跑时这次 +1 会丢。即时落库使成就计数与监听器顺序无关。
     */
    public int incrementConversationCount(UUID playerId) {
        if (!conversationCountCache.containsKey(playerId)) {
            loadPlayerData(playerId);
        }
        int newVal = conversationCountCache.merge(playerId, 1, Integer::sum);
        databaseManager.setConversationCount(playerId, newVal);
        return newVal;
    }

    /**
     * Phase 3 S-A（H11)：错题本累计攻克词数 +1，返回递增后的值。
     *
     * <p>缓存为权威：先确保已从 DB 载入基数，+1 后即时经
     * {@code DatabaseManager.setWrongBookCleared} 落库（绝对值 UPSERT）。
     * 即时落库理由同 R-28（quit 监听器顺序不保证，绝对值写使计数与顺序无关）。
     * 仅由练习答对移出分支调用（手动删除不计数）。
     */
    public int incrementWrongBookCleared(UUID playerId) {
        if (!wrongBookClearedCache.containsKey(playerId)) {
            loadPlayerData(playerId);
        }
        int newVal = wrongBookClearedCache.merge(playerId, 1, Integer::sum);
        databaseManager.setWrongBookCleared(playerId, newVal);
        return newVal;
    }

    /**
     * Phase 3 S-A（H11)：读取玩家错题本累计攻克词数（供 WRONG_BOOK_CLEARED 成就判定）。
     * 缓存未命中时懒加载（loadPlayerData 会填入 wrong_book_cleared）。
     */
    public int getWrongBookCleared(UUID playerId) {
        Integer v = wrongBookClearedCache.get(playerId);
        if (v == null) {
            loadPlayerData(playerId);
            v = wrongBookClearedCache.get(playerId);
        }
        return v != null ? v : 0;
    }

    /**
     * 记录一次学习会话到 player_sessions 表。
     * 同一天多次会话会合并（UPSERT，累加 words/correct/score）。
     *
     * <p>Phase 3 S-A（H8）：review_words 为复习会话专用列——mode=review 时累加
     * correctCount（复习答对词数），否则 0。零签名变更（mode 已是入参），供
     * REVIEW_STREAK_7 成就按"当天复习答对 ≥1 词"判定。
     */
    public void recordSession(UUID playerId, int wordsCount, int correctCount, long score, String mode) {
        String tablePrefix = databaseManager.getTablePrefix();
        java.time.LocalDate today = java.time.LocalDate.now();
        String dateStr = today.toString();
        int reviewWords = "review".equalsIgnoreCase(mode) ? correctCount : 0;

        String sql = databaseManager.isSQLite()
                ? "INSERT INTO " + tablePrefix + "player_sessions (uuid, session_date, words_count, correct_count, score, mode, review_words) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(uuid, session_date) DO UPDATE SET words_count = words_count + excluded.words_count, correct_count = correct_count + excluded.correct_count, score = score + excluded.score, review_words = review_words + excluded.review_words"
                : "INSERT INTO " + tablePrefix + "player_sessions (uuid, session_date, words_count, correct_count, score, mode, review_words) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE words_count = words_count + VALUES(words_count), correct_count = correct_count + VALUES(correct_count), score = score + VALUES(score), review_words = review_words + VALUES(review_words)";

        Connection conn = databaseManager.getConnection();
        synchronized (databaseManager.getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, dateStr);
            stmt.setInt(3, wordsCount);
            stmt.setInt(4, correctCount);
            stmt.setLong(5, score);
            stmt.setString(6, mode);
            stmt.setInt(7, reviewWords);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to record session: " + playerId, e);
        }
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
        synchronized (databaseManager.getConnectionLock()) {
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
        synchronized (databaseManager.getConnectionLock()) {
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
        String statsSql = "SELECT total_sessions, total_score, conversation_count, wrong_book_cleared FROM " + tablePrefix + "player_data WHERE uuid = ?";
        int conversationCount = 0;
        int wrongBookCleared = 0;
        synchronized (databaseManager.getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(statsSql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int sessions = rs.getInt("total_sessions");
                    long score = rs.getLong("total_score");
                    stats.setSessions(sessions);
                    stats.setScore(score);
                    conversationCount = rs.getInt("conversation_count"); // R-28
                    wrongBookCleared = rs.getInt("wrong_book_cleared"); // Phase 3 S-A（H11）
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player stats: " + playerId, e);
        }
        }
        playerStatsCache.put(playerId, stats);
        conversationCountCache.put(playerId, conversationCount); // R-28
        wrongBookClearedCache.put(playerId, wrongBookCleared); // Phase 3 S-A（H11）

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
     *
     * <p>F0-02: 整个事务收进 {@code synchronized (connectionLock)}——setAutoCommit(false)
     * 会改变共享连接的状态，不持锁就会污染其他线程的读写。
     */
    public void savePlayerData(UUID playerId) {
        Map<String, Double> masteryMap = wordMasteryCache.get(playerId);
        if (masteryMap == null) {
            return;
        }

        String tablePrefix = databaseManager.getTablePrefix();
        String upsertSql = buildUpsertSql(tablePrefix);

        Connection conn = databaseManager.getConnection();
        synchronized (databaseManager.getConnectionLock()) {
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
                    // H10：UPSERT 携带 player_name；COALESCE 保证缓存缺失（null）时不覆盖已有名字
                    String statsSql = databaseManager.isSQLite()
                            ? "INSERT INTO " + tablePrefix + "player_data (uuid, player_name, total_words_learned, total_sessions, total_score, updated_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET player_name=COALESCE(excluded.player_name, player_name), total_words_learned=excluded.total_words_learned, total_sessions=excluded.total_sessions, total_score=excluded.total_score, updated_at=excluded.updated_at"
                            : "INSERT INTO " + tablePrefix + "player_data (uuid, player_name, total_words_learned, total_sessions, total_score, updated_at) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_name=COALESCE(VALUES(player_name), player_name), total_words_learned=VALUES(total_words_learned), total_sessions=VALUES(total_sessions), total_score=VALUES(total_score), updated_at=VALUES(updated_at)";
                    try (PreparedStatement statsStmt = conn.prepareStatement(statsSql)) {
                        statsStmt.setString(1, playerId.toString());
                        statsStmt.setString(2, playerNameCache.get(playerId));
                        statsStmt.setInt(3, stats.getTotalWords());
                        statsStmt.setInt(4, stats.getTotalSessions());
                        statsStmt.setLong(5, stats.getTotalScore());
                        statsStmt.setLong(6, System.currentTimeMillis());
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
     * H5：普通学习结算时写入复习种子。
     * 仅当词条没有任何复习进度（无 lastReviewTime 且无 reviewCount）时才写：
     * lastReviewTime=now、reviewCount=0。已有种子或已进入复习流程的词不动，
     * 避免覆盖真实的复习间隔进度。
     */
    public void seedReview(UUID playerId, String word) {
        String wordLower = word.toLowerCase();

        Map<String, Long> reviewTimes = lastReviewTimeCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        if (reviewTimes.containsKey(wordLower)) {
            return;
        }
        Map<String, Integer> reviewCounts = reviewCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        if (reviewCounts.containsKey(wordLower)) {
            return;
        }

        reviewTimes.put(wordLower, System.currentTimeMillis());
        reviewCounts.put(wordLower, 0);
    }

    /**
     * H5：复习答错时重置该词的间隔进度。
     * reviewCount 归零 + lastReviewTime=now，下次 due = now + intervals[0]
     * （默认 1h 后再次提醒），而不是直接晋级到更长的间隔。
     */
    public void resetReview(UUID playerId, String word) {
        String wordLower = word.toLowerCase();

        Map<String, Long> reviewTimes = lastReviewTimeCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        reviewTimes.put(wordLower, System.currentTimeMillis());

        Map<String, Integer> reviewCounts = reviewCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        reviewCounts.put(wordLower, 0);
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
     *
     * <p>R-03：补清两套今日计数器 —— 原实现只清持久化缓存，防刷上限（今日答题/AI 轮次）
     * 的内存态被漏掉，reset 后不重进服上限不归零。
     *
     * <p>S5 裁决：本方法只清自己持有的 map（含 R-28 的 conversationCountCache）。
     * 其余服务的 quit 级清理（GuiNavigationManager 返回栈 / CheckInManager 签到锁 /
     * RewardEngine 经济提示去重）<strong>不</strong>在这里反向调用 —— RewardEngine 已依赖
     * PlayerDataStore（getTodayAnswerCount 等），由此反向调用会引入双向依赖。
     * 统一收口在 {@code PlayerJoinListener.onPlayerQuit}（Listener → 服务单向，
     * 无环）。{@link #clearCache()}（reload 路径）刻意不清两套今日计数器：
     * reload 不重置防刷是既定语义（F5-04/T0-02 裁决）。
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
        playerNameCache.remove(playerId);
        todayAnswerCountCache.remove(playerId);
        todayAiRoundCache.remove(playerId);
        conversationCountCache.remove(playerId); // R-28
        wrongBookClearedCache.remove(playerId); // Phase 3 S-A（H11）
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
        playerNameCache.clear();
        conversationCountCache.clear(); // R-28
        wrongBookClearedCache.clear(); // Phase 3 S-A（H11）
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

        // M10："已学单词"只计答对（答错进错题本，不算学会）。
        // load 重建与 save 落盘两侧都经此方法，口径天然一致。
        public int getTotalWords() {
            return totalCorrect;
        }
    }

    public static class ProfileSettings {
        /** R-39：年龄合法上限（岁）。超过此值视为脏数据，读侧回 0、写侧钳制。 */
        public static final int MAX_VALID_AGE = 150;

        private int age;
        // D2 隐私优先：新玩家默认不公开答案
        private boolean showAnswers = false;
        private boolean showResults = true;
        private boolean showInSquare = true;
        // Phase 3 S-A：per-player 音效开关，默认开（fail-open，见 SoundManager 门）
        private boolean soundsEnabled = true;

        /**
         * R-39 读侧校验：年龄 0 = 未设置（正常态，GUI 显示"未设置"）；
         * 负数或 &gt; MAX_VALID_AGE 视为脏数据，回 0。
         */
        public int getAge() {
            if (age < 0 || age > MAX_VALID_AGE) {
                return 0;
            }
            return age;
        }
        public void setAge(int age) { this.age = age; }
        public boolean isShowAnswers() { return showAnswers; }
        public void setShowAnswers(boolean showAnswers) { this.showAnswers = showAnswers; }
        public boolean isShowResults() { return showResults; }
        public void setShowResults(boolean showResults) { this.showResults = showResults; }
        public boolean isShowInSquare() { return showInSquare; }
        public void setShowInSquare(boolean showInSquare) { this.showInSquare = showInSquare; }
        // Phase 3 S-A：音效开关（SoundManager per-player 门读侧走 getProfileSettingsReadOnly）
        public boolean isSoundsEnabled() { return soundsEnabled; }
        public void setSoundsEnabled(boolean soundsEnabled) { this.soundsEnabled = soundsEnabled; }
    }

    public ProfileSettings getProfileSettings(UUID playerId) {
        return profileSettingsCache.computeIfAbsent(playerId, k -> new ProfileSettings());
    }

    /**
     * L-05 只读版：展示路径（ProfileGUI / EnglishSquareGUI / Leaderboard 查榜 /
     * PAPI 占位符）用此方法读隐私开关。
     * 缓存缺失时返回一个全新的默认副本，绝不写入 profileSettingsCache，
     * 避免只读展示在缓存里建条目、污染 {@link #getActivePlayerIds()}。
     * 返回的实例是调用方私有的，改它不会影响缓存；写路径请走
     * {@link #getProfileSettings(UUID)}。
     */
    public ProfileSettings getProfileSettingsReadOnly(UUID playerId) {
        ProfileSettings cached = profileSettingsCache.get(playerId);
        return cached != null ? cached : new ProfileSettings();
    }

    public void saveProfileSettings(UUID playerId) {
        ProfileSettings settings = profileSettingsCache.get(playerId);
        if (settings == null) return;

        String tablePrefix = databaseManager.getTablePrefix();
        String sql = databaseManager.isSQLite()
                ? "INSERT OR REPLACE INTO " + tablePrefix + "player_profiles (uuid, age, show_answers, show_results, show_in_square, sounds_enabled) VALUES (?, ?, ?, ?, ?, ?)"
                : "INSERT INTO " + tablePrefix + "player_profiles (uuid, age, show_answers, show_results, show_in_square, sounds_enabled) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE age=VALUES(age), show_answers=VALUES(show_answers), show_results=VALUES(show_results), show_in_square=VALUES(show_in_square), sounds_enabled=VALUES(sounds_enabled)";

        Connection conn = databaseManager.getConnection();
        synchronized (databaseManager.getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setInt(2, settings.getAge());
            stmt.setBoolean(3, settings.isShowAnswers());
            stmt.setBoolean(4, settings.isShowResults());
            stmt.setBoolean(5, settings.isShowInSquare());
            stmt.setBoolean(6, settings.isSoundsEnabled());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save profile settings: " + playerId, e);
        }
        }
    }

    public void loadProfileSettings(UUID playerId) {
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT age, show_answers, show_results, show_in_square, sounds_enabled FROM " + tablePrefix + "player_profiles WHERE uuid = ?";

        Connection conn = databaseManager.getConnection();
        synchronized (databaseManager.getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ProfileSettings settings = new ProfileSettings();
                    settings.setAge(rs.getInt("age"));
                    settings.setShowAnswers(rs.getBoolean("show_answers"));
                    settings.setShowResults(rs.getBoolean("show_results"));
                    settings.setShowInSquare(rs.getBoolean("show_in_square"));
                    settings.setSoundsEnabled(rs.getBoolean("sounds_enabled")); // Phase 3 S-A
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
    }

    public void unloadProfileSettings(UUID playerId) {
        saveProfileSettings(playerId);
        profileSettingsCache.remove(playerId);
    }

    /**
     * R-39 写侧钳制：年龄合法范围 0~MAX_VALID_AGE。非法写入记 WARNING 并钳制
     * （负数→0，&gt;150→150），脏数据永不进缓存/落盘。
     */
    public void setAge(UUID playerId, int age) {
        int clamped = age;
        if (clamped < 0) {
            clamped = 0;
        } else if (clamped > ProfileSettings.MAX_VALID_AGE) {
            clamped = ProfileSettings.MAX_VALID_AGE;
        }
        if (clamped != age) {
            plugin.getLogger().warning("R-39: clamped invalid age " + age
                    + " to " + clamped + " for " + playerId);
        }
        ProfileSettings settings = getProfileSettings(playerId);
        settings.setAge(clamped);
    }

    /**
     * L-05：候选集 = profileSettingsCache 键 ∪ 当前在线玩家。
     * 原先只取 profile 缓存键：受只读展示的 computeIfAbsent 建条目污染、
     * 且档案未加载的在线玩家（刚上线/异常路径）会被广场漏掉。
     * 展示路径已改走 {@link #getProfileSettingsReadOnly}，本方法不再被写副作用污染。
     * quit 清理语义不变（unloadProfileSettings 照常删缓存键）。
     */
    public List<UUID> getActivePlayerIds() {
        java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>(profileSettingsCache.keySet());
        try {
            for (org.bukkit.entity.Player online : plugin.getServer().getOnlinePlayers()) {
                ids.add(online.getUniqueId());
            }
        } catch (Exception e) {
            // 异步可达路径（如启动期 Leaderboard 预热）拿不到在线列表时，
            // 回落到 profile 缓存键，保证永不抛异常。
        }
        return new ArrayList<>(ids);
    }

    public String getPlayerName(UUID playerId) {
        org.bukkit.OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(playerId);
        if (offlinePlayer != null && offlinePlayer.getName() != null) {
            return offlinePlayer.getName();
        }
        return playerId.toString();
    }

    /**
     * H10：缓存玩家名（供 savePlayerData 落盘 player_name 列）。
     * 由 PlayerJoinListener 在主线程写入；列宽 VARCHAR(32)，超长截断。
     */
    public void cachePlayerName(UUID playerId, String name) {
        if (playerId == null || name == null || name.isEmpty()) {
            return;
        }
        String trimmed = name.length() > 32 ? name.substring(0, 32) : name;
        playerNameCache.put(playerId, trimmed);
    }

    /**
     * M9：将会话连击峰值落盘到 longest_streak（只增不减）。
     * 先 INSERT 保行（玩家可能从未 save 过），再条件 UPDATE 取 max。
     * 调用方（AchievementManager.checkStreakAchievement）跑在主线程，
     * 单条 SQL 很快，直接同步执行即可。
     */
    public void persistLongestStreak(UUID playerId, int streak) {
        if (streak <= 0) {
            return;
        }
        String tablePrefix = databaseManager.getTablePrefix();
        String insertSql = databaseManager.isSQLite()
                ? "INSERT OR IGNORE INTO " + tablePrefix + "player_data (uuid, longest_streak) VALUES (?, ?)"
                : "INSERT IGNORE INTO " + tablePrefix + "player_data (uuid, longest_streak) VALUES (?, ?)";
        String updateSql = "UPDATE " + tablePrefix
                + "player_data SET longest_streak = ?, updated_at = ? WHERE uuid = ? AND (longest_streak IS NULL OR longest_streak < ?)";

        Connection conn = databaseManager.getConnection();
        synchronized (databaseManager.getConnectionLock()) {
        try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
            insertStmt.setString(1, playerId.toString());
            insertStmt.setInt(2, streak);
            insertStmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to ensure player_data row: " + playerId, e);
            return;
        }
        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            updateStmt.setInt(1, streak);
            updateStmt.setLong(2, System.currentTimeMillis());
            updateStmt.setString(3, playerId.toString());
            updateStmt.setInt(4, streak);
            updateStmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to persist longest_streak: " + playerId, e);
        }
        }
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
        String sql = "SELECT d.uuid, d.player_name, d.total_words_learned, d.total_sessions, d.total_score "
                + "FROM " + tablePrefix + "player_data d "
                + "LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid "
                + "WHERE COALESCE(p.show_in_square, 1) <> 0 "
                + "ORDER BY d.total_score DESC LIMIT ?";

        Connection conn = databaseManager.getConnection();

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        synchronized (databaseManager.getConnectionLock()) {
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
                + "LEFT JOIN " + tablePrefix + "player_profiles pf ON pf.uuid = s.uuid "
                + "WHERE s.session_date >= ? AND s.session_date <= ? "
                + "AND COALESCE(pf.show_in_square, 1) <> 0 "
                + "GROUP BY s.uuid ORDER BY period_score DESC LIMIT ?";

        Connection conn = databaseManager.getConnection();

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        synchronized (databaseManager.getConnectionLock()) {
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
