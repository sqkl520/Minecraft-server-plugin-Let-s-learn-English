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
        ALL_PLAYERS
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
                List<UUID> activeIds = store.getActivePlayerIds();
                entries = new ArrayList<>();
                int rank = 1;
                for (UUID id : activeIds) {
                    // R-05 补漏：隐身玩家（show_in_square=false）不上榜。
                    if (!store.getProfileSettings(id).isShowInSquare()) continue;
                    PlayerDataStore.PlayerStats stats = store.getPlayerStats(id);
                    String name = store.getPlayerName(id);
                    entries.add(new LeaderboardEntry(id, name, stats.getTotalScore(),
                            stats.getTotalCorrect(), stats.getTotalIncorrect(),
                            stats.getTotalSessions(), rank++));
                }
                entries.sort((a, b) -> Long.compare(b.score, a.score));
                List<LeaderboardEntry> reranked = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    reranked.add(entries.get(i).withRank(i + 1));
                }
                entries = reranked;
            }
            default -> entries = Collections.emptyList();
        }

        return entries.size() > count ? entries.subList(0, count) : entries;
    }

    /**
     * Update a player's score on the in-memory leaderboard.
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
            List<LeaderboardEntry> all = plugin.getPlayerDataStore().getRankedPlayers(Integer.MAX_VALUE);
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
