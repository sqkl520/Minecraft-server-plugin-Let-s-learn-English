package com.letslearnenglish.minecraftplugin.core.progress;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Leaderboard — manages player rankings with weekly/monthly/all-time modes.
 *
 * Queries scores from the database via PlayerDataStore.
 * The in-memory scores map is kept for fast single-player rank lookups.
 */
public class Leaderboard {

    public enum RankingMode {
        WEEKLY,
        MONTHLY,
        ALL_TIME_COINS,
        ALL_PLAYERS,
        /**
         * 学习动态流（广场第 5 模式，非榜单语义——与 ALL_PLAYERS 同为"承载非排名
         * 内容的模式"先例）。数据走 {@code SocialManager} 的 FeedCache，不走本类。
         *
         * <p><b>调用方契约（PF-SOC-04）：FEED 永不传进 {@link #getTopPlayers}</b> ——
         * 该方法 default 分支会静默返回空列表（防误用兜底，不是功能路径）。
         */
        FEED
    }

    public static class LeaderboardEntry {
        public final UUID playerId;
        public final String playerName;
        public final long score;
        public final int correct;
        public final int incorrect;
        public final int sessions;
        public final int rank;

        public LeaderboardEntry(UUID playerId, String playerName, long score,
                                int correct, int incorrect, int sessions, int rank) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.score = score;
            this.correct = correct;
            this.incorrect = incorrect;
            this.sessions = sessions;
            this.rank = rank;
        }

        /** Return a copy of this entry with a different rank. Used for re-sorting. */
        public LeaderboardEntry withRank(int newRank) {
            return new LeaderboardEntry(playerId, playerName, score, correct, incorrect, sessions, newRank);
        }
    }

    private static final int NOT_RANKED = -1;
    private static final int DEFAULT_CACHE_LIMIT = 50;
    /**
     * E-10：启动快照的行数上限。原实现传 Integer.MAX_VALUE，下游
     * getRankedPlayers 还对每行调 getPlayerStats 逐人补读（N+1），
     * 大服启动即全表扫描 + 全员落盘读。1000 行封顶后超出者不在内存榜
     * （%lle_rank% 显示未上榜），读时查库的大改留 Phase 3。
     */
    private static final int RANK_SNAPSHOT_LIMIT = 1000;

    private final LetsLearnEnglish plugin;
    private final Map<UUID, Long> scores;

    private volatile List<LeaderboardEntry> coinsCache = new ArrayList<>();
    private volatile List<LeaderboardEntry> wordsCache = new ArrayList<>();
    private volatile List<LeaderboardEntry> comboCache = new ArrayList<>();

    public Leaderboard(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.scores = new ConcurrentHashMap<>();
    }

    /**
     * Get ranked player entries for a given mode and count.
     * For ALL_PLAYERS mode, returns from the in-memory cache sorted by score.
     * For other modes, queries the database.
     */
    public List<LeaderboardEntry> getTopPlayers(RankingMode mode, int count) {
        PlayerDataStore store = plugin.getPlayerDataStore();
        List<LeaderboardEntry> entries;

        switch (mode) {
            case WEEKLY -> {
                LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate sunday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                entries = store.getRankedPlayersForPeriod(count, monday, sunday);
            }
            case MONTHLY -> {
                LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
                LocalDate lastOfMonth = LocalDate.now().with(TemporalAdjusters.lastDayOfMonth());
                entries = store.getRankedPlayersForPeriod(count, firstOfMonth, lastOfMonth);
            }
            case ALL_TIME_COINS -> entries = store.getRankedPlayers(count);
            case ALL_PLAYERS -> {
                // F3（R13-M3）：聚合单查替换 N+1 装配 —— getActiveSquareEntries 单条 SQL
                // 完成隐身过滤（COALESCE show_in_square）+ 答过题过滤（word_progress 聚合 >0）
                // + correct/incorrect 求和；旧路径逐 cache-miss 玩家 getPlayerStats 查库。
                // 保留"再排序+重排行号"语义零漂移（SQL 已按 score DESC 取 count 行，
                // 重排循环对同分次序稳定）。
                List<LeaderboardEntry> active = store.getActiveSquareEntries(count);
                List<LeaderboardEntry> reranked = new ArrayList<>(active.size());
                for (int i = 0; i < active.size(); i++) {
                    reranked.add(active.get(i).withRank(i + 1));
                }
                entries = reranked;
            }
            // FEED 走 SocialManager FeedCache，永不进入本方法（契约见 RankingMode.FEED
            // javadoc，PF-SOC-04）——default -> emptyList 是防误用的静默兜底。
            default -> entries = Collections.emptyList();
        }

        return entries.size() > count ? entries.subList(0, count) : entries;
    }

    /**
     * Update a player's score on the in-memory leaderboard.
     *
     * <p>R-42：语义为"增量累加"（merge-sum），不是"设值"。
     * 调用点（WordManagerImpl.endSession）传的是本次会话增量分；
     * 全量覆盖请走 {@link #loadFromDB()} 启动快照。
     */
    public void updateScore(UUID playerId, long score) {
        scores.merge(playerId, score, Long::sum);
    }

    /**
     * Get a player's rank from the in-memory scores map.
     */
    public int getPlayerRank(UUID playerId) {
        List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());

        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getKey().equals(playerId)) {
                return i + 1;
            }
        }
        return NOT_RANKED;
    }

    /**
     * Load all player scores from the database into the in-memory map.
     * Called once after plugin enable to populate the leaderboard.
     */
    public void loadFromDB() {
        scores.clear();
        try {
            List<LeaderboardEntry> all = plugin.getPlayerDataStore().getRankedPlayers(RANK_SNAPSHOT_LIMIT);
            for (LeaderboardEntry entry : all) {
                scores.put(entry.playerId, entry.score);
            }
            plugin.getLogger().info("Leaderboard loaded " + all.size() + " players from database.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load leaderboard from database", e);
        }
    }

    /**
     * Refresh all three leaderboard caches from the database.
     * Called on plugin startup and periodically (every 5 minutes).
     */
    public void refreshCache() {
        try {
            coinsCache = plugin.getDatabaseManager().getTopCoins(DEFAULT_CACHE_LIMIT);
            wordsCache = plugin.getDatabaseManager().getTopWordsLearned(DEFAULT_CACHE_LIMIT);
            comboCache = plugin.getDatabaseManager().getTopCombo(DEFAULT_CACHE_LIMIT);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to refresh leaderboard caches", e);
        }
    }

    /**
     * Get the cached coins leaderboard (top players by lifetime coins earned).
     */
    public List<LeaderboardEntry> getCachedCoinsLeaderboard() {
        return coinsCache;
    }

    /**
     * Get the cached words learned leaderboard.
     */
    public List<LeaderboardEntry> getCachedWordsLeaderboard() {
        return wordsCache;
    }

    /**
     * Get the cached combo leaderboard (top players by longest_streak).
     */
    public List<LeaderboardEntry> getCachedComboLeaderboard() {
        return comboCache;
    }
}
