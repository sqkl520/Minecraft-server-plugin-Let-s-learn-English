package com.letslearnenglish.minecraftplugin.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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

        /**
         * P0-4 红队补丁（R-P1-1）：退还一次预扣（失败请求未产生上游计费）。
         * 下限钳 0 —— 并发重复退还最坏是少退，不会出现负数导致"多送额度"。
         */
        int decrement() {
            LocalDate today = LocalDate.now();
            if (!today.equals(date)) {
                return 0; // 跨日无当日计数可退
            }
            return count.updateAndGet(v -> v > 0 ? v - 1 : 0);
        }

        /** Block C2（R2-M2）：当前计数归属的自然日；从未 seed/increment 时为 null。 */
        LocalDate date() {
            return date;
        }

        /**
         * Block C2（R2-M2）：从持久化真源回填计数（AI 轮次表重启/quit-rejoin 后回读）。
         * synchronized 双检（同 {@link #increment} 惯式）：若 seed 前已有并发线程把日期
         * 推到 d 并开始递增，则跳过覆盖，保住新的递增结果（seeded 值最坏差 1，
         * 防刷上限语义容忍）。
         */
        void seed(LocalDate d, int v) {
            if (!d.equals(date)) {
                synchronized (this) {
                    if (!d.equals(date)) {
                        date = d;
                        count.set(v);
                    }
                }
            }
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
     *
     * <p>Block C2（R2-M2）：持久化版 —— 内存 TodayCounter 重启/换服/quit-rejoin 即清零，
     * daily-round-limit（默认 30）唯一判据源可被无限绕过（API 成本面）。三层：
     * ① 日期非今日（或从未 seed）→ 先同步回读 DB 真源 seed（读失败按 0 降级）；
     * ② 内存 increment 立即生效（返回值语义不变，AIChatManager 消费）；
     * ③ 经调度器异步落库（调用点在 {@code supplyAsync} 的 commonPool 链内——
     * 与 R2-H1 修复纪律一致，绝不直调 JDBC；关服窗口静默弃）。
     */
    public int incrementTodayAiRound(UUID playerId) {
        LocalDate today = LocalDate.now();
        String todayStr = today.toString();
        TodayCounter counter = todayAiRoundCache.computeIfAbsent(playerId, k -> new TodayCounter());
        if (!today.equals(counter.date())) {
            // ① 内存态无今日真值（重启/quit-rejoin/跨日）→ 先回读 DB 真源
            int db = databaseManager.getAiRoundCount(playerId, todayStr);
            counter.seed(today, db);
        }
        int v = counter.increment(); // ② 内存立即生效
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                    databaseManager.incrementAiRound(playerId, todayStr)); // ③ 异步落库
        } catch (IllegalStateException ignored) {
            // 关服窗口调度器已关：静默弃（与 R2-H1 纪律一致）
        }
        return v;
    }

    /**
     * P0-4 红队补丁（R-P1-1）：退还一次"发请求前预扣"的今日 AI 轮次。
     *
     * <p>只用于开场白在<b>确定到达上游之前</b>就失败的场景（超时 / 连接失败 / 5xx / 429）——
     * 这类请求没有产生上游计费，必须退还，否则上游持续故障时玩家反复点场景会耗尽当天
     * daily-round-limit（且重启/重登不可恢复）。4xx 客户端错误不退：上游已受理并计费。
     *
     * <p>内存与 DB 同步递减；DB 走异步（同 increment 纪律，绝不直调 JDBC）。
     * 未 seed 过的玩家（date 为 null）直接忽略，避免凭空产生负计数。
     */
    public int decrementTodayAiRound(UUID playerId) {
        LocalDate today = LocalDate.now();
        TodayCounter counter = todayAiRoundCache.get(playerId);
        if (counter == null || !today.equals(counter.date())) {
            return 0; // 无当日计数可退
        }
        int v = counter.decrement();
        String todayStr = today.toString();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                    databaseManager.decrementAiRound(playerId, todayStr));
        } catch (IllegalStateException ignored) {
            // 关服窗口调度器已关：静默弃（与 R2-H1 纪律一致）
        }
        return v;
    }

    /**
     * 读取玩家今日 AI 有效轮次。跨天后自动视为 0。
     *
     * <p>Block C2（R2-M2）：cache miss 或当日计数器未载入时，同步回读
     * {@code ai_round_counters} 真源并 seed（先例：{@link #getConversationCount}
     * "miss 时同步加载再读"模式；本处仅一条 SELECT）。quit/rejoin 后首次调用即走
     * 此路径读回真值——正是本修复要闭合的绕过路径。读取点均在异步线程
     * （AsyncPlayerChatEvent 线程 / chatAsync 异步链），同步 DB 读持 connectionLock 可接受。
     */
    public int getTodayAiRound(UUID playerId) {
        LocalDate today = LocalDate.now();
        TodayCounter counter = todayAiRoundCache.get(playerId);
        if (counter == null || !today.equals(counter.date())) {
            counter = todayAiRoundCache.computeIfAbsent(playerId, k -> new TodayCounter());
            if (!today.equals(counter.date())) {
                // 内存态无今日真值 → 同步回读 DB 真源（读失败按 0 降级）
                int db = databaseManager.getAiRoundCount(playerId, today.toString());
                counter.seed(today, db);
            }
        }
        return counter.get();
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
     * <p>P3-05：UPSERT 不更新 mode 列——mode 记录当日首场模式（同日合并语义）；
     * 如需统计模式分布需先定口径（P3-05；报表侧同口径见 ReportGenerator 的 PF-RPT-04）。
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

        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
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
        synchronized (databaseManager.getConnectionLock()) {
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

        Connection conn;
        synchronized (databaseManager.getConnectionLock()) {
        conn = databaseManager.getConnection();
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
        // F1-20：本块与上方锁块之间已释放过锁——重连（initialize）可能已替换
        // Connection 引用，原实现在此处复用锁外取得的 conn。照 DatabaseManager
        // E-04 范式：上个锁块已释放，重连可能已替换引用，此处锁内重取。
        synchronized (databaseManager.getConnectionLock()) {
        conn = databaseManager.getConnection();
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
     *
     * <p>Block C2（A3/R3-M2）：缓存 merge 后 ≤0 即 remove，缓存地板为 0；DB 侧走
     * {@code DatabaseManager.decrementWrongCount} 的原子条件 SQL（{@code wrong_count > 1}
     * 才减、否则返回 false → 本处删行），与缓存两值逐值对齐（cache 3→2 ↔ db 3→2；
     * cache 1→0 删 ↔ db 1→删）。残余乱序窗口：同一玩家的 increment 异步任务整体晚于
     * 本 decrement 任务执行时词会留书——概率极低（跨秒提交两任务乱序），最坏结果 =
     * 词留书（可重试清除，无数据损坏）；executor 保序队列会同时改动 increment 热路径，
     * 收益/风险不成比，接受此窗口。
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
            if (!databaseManager.decrementWrongCount(playerId, wordLower)) {
                // 计数已达 1（或行不存在）→ 移出（幂等）；原子 SQL 已消除读回分裂窗口
                databaseManager.removeDifficultWord(playerId, wordLower);
            }
        });
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

        synchronized (databaseManager.getConnectionLock()) {
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
     * Update word progress after a learning session.
     * Invalidates the mastered words cache for the player.
     *
     * <p>Block C2（A4/R3-M3）：补全 5 个 map 的 computeIfAbsent —— savePlayerData 按
     * masteryMap 全量遍历、7 列全写，review 两 map 缺失时 getLongValue/getIntValue
     * 回落 0 会覆盖 DB 非零值（复习调度被清零）。masteryMap 的创建点仅本方法与
     * loadPlayerData 两处（后者五 map 全填），故补两行后"凡 savePlayerData 可被触达
     * （masteryMap 非 null）的状态下 5 map 必同存"，覆盖窗口封死。
     */
    public void updateWordProgress(UUID playerId, Map<String, LearningSession.WordResult> results) {
        Map<String, Double> masteryMap = wordMasteryCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> correctMap = correctCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> incorrectMap = incorrectCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        lastReviewTimeCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        reviewCountCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());

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

    /**
     * Block C2（A5/R3-M4）：会话结算入口 —— total_sessions 按"局数口径"累加
     * （每次会话结束 +1，同日多局累计）。与 player_sessions 表"每人每日一行"的
     * 学习天数口径**不同源**（后者另有 DatabaseManager.getTotalSessions 消费）；
     * 成就判定（AchievementManager 按 total_sessions 涨幅）以本口径为准，属既定语义。
     */
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
        /**
         * Block C2（A5/R3-M4）：total_sessions = 学习局数（每次会话结束 +1，同日多局
         * 累计）——与 player_sessions 表"每人每日一行"的天数口径**不同源**；
         * 成就判定（AchievementManager）以本口径为准。
         */
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

        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
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

        synchronized (databaseManager.getConnectionLock()) {
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

    /**
     * 读取玩家名（展示用）。
     *
     * <p>F1-21 防御：优先读 join 时缓存的名（{@link #cachePlayerName}，H10）——
     * 缓存命中完全不触 Bukkit API。缓存未命中（离线玩家 / reload 后未重登 /
     * 未来的异步调用窗）时，仅主线程回落名册查询（{@code getOfflinePlayer}
     * 为阻塞式查询，异步调用不安全）；非主线程直接回落 uuid 字符串。
     *
     * <p>已核实既有四个消费点（ProfileGUI / EnglishSquareGUI / Leaderboard /
     * SocialListener）当前全在主线程——主线程行为与旧实现一致（离线玩家名
     * 仍走名册解析，不因缓存只覆盖在线而降级）；{@code isPrimaryThread}
     * 守卫是未来异步调用的防线（届时安全回落 uuid）。
     */
    public String getPlayerName(UUID playerId) {
        String cached = playerNameCache.get(playerId);
        if (cached != null) {
            return cached;
        }
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            org.bukkit.OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(playerId);
            if (offlinePlayer != null && offlinePlayer.getName() != null) {
                return offlinePlayer.getName();
            }
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

        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
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

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
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
     * F3（R13-M3）：广场"全部玩家"聚合查询 —— 单条 SQL 取代
     * {@code getActivePlayerIds + 逐人 getPlayerStats/隐身过滤} 的 N+1 装配
     *（旧路径每 cache-miss 玩家一次 loadPlayerData 全量查库）。
     *
     * <p>SQL 口径：{@code player_data} LEFT JOIN {@code player_profiles}
     *（COALESCE 隐身过滤，同 {@link #getRankedPlayers(int)}）+ LEFT JOIN
     * {@code word_progress} 按 uuid 聚合子查询（correct/incorrect 顺带 SUM，
     * 不再对每行补读——两处消费侧一次查询拿全）。"答过题"判定 = 聚合
     * correct+incorrect &gt; 0（等价旧 {@code getTotalCorrect()+getTotalIncorrect() > 0}）。
     * ORDER BY total_score DESC LIMIT ?（与 getRankedPlayers 同式）。
     * word_progress 聚合走 UNIQUE(uuid, word) 的 uuid 前缀索引。
     *
     * <p>数据源 = DB 真值（非内存统计镜像）：在线玩家在自动保存间隔（默认 5 分钟）
     * 内尚未落盘的最新答题会延迟可见——这是本方法替换装配口径的既定语义
     *（fix-plan-c2 F3 裁决：同步单查已达标，不加快照层）。
     *
     * @param limit 行数上限（"全部"语义传 {@link Integer#MAX_VALUE}；LIMIT ? 双后端兼容）
     */
    public List<LeaderboardEntry> getActiveSquareEntries(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT d.uuid AS uuid, d.player_name AS player_name, "
                + "d.total_score AS total_score, d.total_sessions AS total_sessions, "
                + "COALESCE(w.correct_sum, 0) AS correct_sum, COALESCE(w.incorrect_sum, 0) AS incorrect_sum "
                + "FROM " + tablePrefix + "player_data d "
                + "LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid "
                + "LEFT JOIN (SELECT uuid, SUM(correct_count) AS correct_sum, SUM(incorrect_count) AS incorrect_sum "
                + "FROM " + tablePrefix + "word_progress GROUP BY uuid) w ON w.uuid = d.uuid "
                + "WHERE COALESCE(p.show_in_square, 1) <> 0 "
                + "AND COALESCE(w.correct_sum, 0) + COALESCE(w.incorrect_sum, 0) > 0 "
                + "ORDER BY d.total_score DESC LIMIT ?";

        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                // 本方法无嵌套查询：行内数据即全部所需（区别于 getRankedPlayers 的两段式）。
                // 同样不调任何 Bukkit API（player_name 为空回落 uuid.toString()）。
                int rank = 1;
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("player_name");
                    if (name == null || name.isEmpty()) {
                        name = uuid.toString();
                    }
                    entries.add(new LeaderboardEntry(uuid, name,
                            rs.getLong("total_score"),
                            rs.getInt("correct_sum"), rs.getInt("incorrect_sum"),
                            rs.getInt("total_sessions"), rank++));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load active square entries", e);
            return entries;
        }
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

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
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

    /**
     * Get the top N players ranked by score within a date range, restricted to a
     * specific set of member UUIDs (class leaderboard, US-CLS-2).
     * Same aggregation as {@link #getRankedPlayersForPeriod(int, LocalDate, LocalDate)}
     * with an extra "uuid IN (?)" filter.
     *
     * @param members members of the class; an empty collection short-circuits to an empty list
     */
    public List<LeaderboardEntry> getRankedPlayersForPeriodFiltered(int count, LocalDate since, LocalDate until,
            Collection<UUID> members) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        if (members == null || members.isEmpty()) {
            return entries;
        }
        String tablePrefix = databaseManager.getTablePrefix();
        // 与 getRankedPlayersForPeriod 同款聚合，只多了 uuid IN 过滤。
        // player_name 用 MAX() 包住以兼容 MySQL 的 ONLY_FULL_GROUP_BY。
        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                inClause.append(", ");
            }
            inClause.append("?");
        }
        String sql = "SELECT s.uuid AS uuid, MAX(p.player_name) AS player_name, "
                + "SUM(s.score) as period_score, "
                + "SUM(s.correct_count) as total_correct, SUM(s.words_count) as total_words "
                + "FROM " + tablePrefix + "player_sessions s "
                + "LEFT JOIN " + tablePrefix + "player_data p ON p.uuid = s.uuid "
                + "LEFT JOIN " + tablePrefix + "player_profiles pf ON pf.uuid = s.uuid "
                + "WHERE s.session_date >= ? AND s.session_date <= ? "
                + "AND COALESCE(pf.show_in_square, 1) <> 0 "
                + "AND s.uuid IN (" + inClause + ") "
                + "GROUP BY s.uuid ORDER BY period_score DESC LIMIT ?";

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            stmt.setString(idx++, since.toString());
            stmt.setString(idx++, until.toString());
            for (UUID member : members) {
                stmt.setString(idx++, member.toString());
            }
            stmt.setInt(idx, count);
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
            plugin.getLogger().log(Level.WARNING, "Failed to load ranked class players for period", e);
            return entries;
        }
        }

        // Step 2: ResultSet closed — no Bukkit API here (may run async); falls back to uuid.toString().
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
