package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Spaced Repetition Review Scheduler
 *
 * Based on the Ebbinghaus forgetting curve principle, reminds players
 * to review words at key time intervals.
 * Review intervals: 1h, 6h, 24h, 72h, 168h (7 days)
 */
public class ReviewScheduler {

    private final LetsLearnEnglish plugin;
    private final WordManagerImpl wordManager;
    private final PlayerDataStore playerDataStore;
    private final List<Integer> reviewIntervals;

    public ReviewScheduler(LetsLearnEnglish plugin, WordManagerImpl wordManager,
                           PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.wordManager = wordManager;
        this.playerDataStore = playerDataStore;
        this.reviewIntervals = plugin.getConfigManager().getMainConfig()
                .getIntegerList("word-system.review.intervals");
    }

    /**
     * Check and notify players who have words due for review.
     */
    public void checkAndNotifyReviews() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<String> dueWords = getDueReviewWords(player.getUniqueId());
            if (!dueWords.isEmpty()) {
                notifyPlayer(player, dueWords);
            }
        }
    }

    /**
     * Get the list of words due for review for a player.
     */
    public List<String> getDueReviewWords(UUID playerId) {
        List<String> dueWords = new ArrayList<>();
        Map<String, Long> lastReviewTimes = playerDataStore.getLastReviewTimes(playerId);
        Map<String, Integer> reviewCounts = playerDataStore.getReviewCounts(playerId);
        long now = System.currentTimeMillis();

        for (Map.Entry<String, Long> entry : lastReviewTimes.entrySet()) {
            String word = entry.getKey();
            long lastReview = entry.getValue();
            int reviewCount = reviewCounts.getOrDefault(word, 0);

            if (reviewCount >= reviewIntervals.size()) {
                continue;
            }

            long nextReviewTime = lastReview + TimeUnit.HOURS.toMillis(reviewIntervals.get(reviewCount));
            if (now >= nextReviewTime) {
                dueWords.add(word);
            }
        }

        return dueWords;
    }

    /**
     * Notify a player to review words.
     */
    private void notifyPlayer(Player player, List<String> dueWords) {
        String message = plugin.getMessageUtil().getPlayerMessage(player,
                "review.notification", "count", String.valueOf(dueWords.size()));
        player.sendMessage(message);
    }

    /**
     * Record a completed review.
     */
    public void recordReview(UUID playerId, String word) {
        playerDataStore.recordReview(playerId, word);
    }
}