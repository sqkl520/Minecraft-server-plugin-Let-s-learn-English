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

        return deterministicRanks(entries, count);
    }

    // 09-logic-F-R4L-01：同分次序由后端定而 rank 换算奖金档位，故加 (score DESC, uuid ASC)。
    private static List<LeaderboardEntry> deterministicRanks(List<LeaderboardEntry> entries, int count) {
        List<LeaderboardEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            int cmp = Long.compare(b.score, a.score);
            return cmp != 0 ? cmp : a.playerId.compareTo(b.playerId);
        });
        for (int i = 0; i < sorted.size(); i++) sorted.set(i, sorted.get(i).withRank(i + 1));
        return sorted.size() > count ? sorted.subList(0, count) : sorted;
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
     * 查玩家名次。
     *
     * <p><b>slice-09-F-09-03/04/05（round1 遗留 MEDIUM，round10 裁决实施）：改为委托
     * 权威查询，不再读内存 {@code scores}。</b>
     *
     * <p>原实现有**两个名次真相源且口径不同**：
     * <ul>
     *   <li>这里读 {@code scores} —— 启动快照（上限 1000 行）+ 本服增量、5 分钟刷一次，
     *       WHERE 里<b>只有隐身门</b>；</li>
     *   <li>广场榜走实时 SQL，WHERE 里除了隐身门还有<b>答题门</b>
     *       （{@code EXISTS word_progress ... correct+incorrect > 0}）。</li>
     * </ul>
     * 于是「刚进服、还没答过题」的玩家在 {@code %lle_rank%} 里有名次，在广场榜上却不在。
     *
     * <p>现在统一到 {@link PlayerDataStore#getAuthoritativeRank}，与广场侧同一套 WHERE
     * 与 tie-break。调用点仍保留原签名，调用方零改动。
     *
     * @return 1-based 名次；不在榜上返回 {@link #NOT_RANKED}
     */
    public int getPlayerRank(UUID playerId) {
        int rank = plugin.getPlayerDataStore().getAuthoritativeRank(playerId);
        return rank > 0 ? rank : NOT_RANKED;
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
