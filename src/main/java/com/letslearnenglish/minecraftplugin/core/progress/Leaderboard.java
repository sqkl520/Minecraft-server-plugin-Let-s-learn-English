package com.letslearnenglish.minecraftplugin.core.progress;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;

import java.util.*;

/**
 * Leaderboard - manages player rankings based on learning scores.
 */
public class Leaderboard {

    private static final int NOT_RANKED = -1;

    private final LetsLearnEnglish plugin;
    private final Map<UUID, Long> scores;

    public Leaderboard(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.scores = new LinkedHashMap<>();
    }

    /**
     * Get the top N players by score.
     */
    public List<Map.Entry<UUID, Long>> getTopPlayers(int count) {
        List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());
        return sorted.subList(0, Math.min(count, sorted.size()));
    }

    /**
     * Update a player's score on the leaderboard.
     */
    public void updateScore(UUID playerId, long score) {
        scores.merge(playerId, score, Long::sum);
    }

    /**
     * Get a player's rank.
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
}