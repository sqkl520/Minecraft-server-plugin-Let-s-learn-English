package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ReviewScheduler {

    private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(30);

    private final LetsLearnEnglish plugin;
    private final WordManagerImpl wordManager;
    private final PlayerDataStore playerDataStore;
    private final int reviewIntervalsSize;
    private final long[] reviewIntervalsMs;

    private final Map<UUID, CacheEntry> dueWordCache = new ConcurrentHashMap<>();

    public ReviewScheduler(LetsLearnEnglish plugin, WordManagerImpl wordManager,
                           PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.wordManager = wordManager;
        this.playerDataStore = playerDataStore;

        List<Integer> intervals = plugin.getConfigManager().getMainConfig()
                .getIntegerList("word-system.review.intervals");
        this.reviewIntervalsSize = intervals.size();
        this.reviewIntervalsMs = new long[reviewIntervalsSize];
        for (int i = 0; i < reviewIntervalsSize; i++) {
            reviewIntervalsMs[i] = TimeUnit.HOURS.toMillis(intervals.get(i));
        }
    }

    public void checkAndNotifyReviews() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<String> dueWords = getDueReviewWords(player.getUniqueId());
            if (!dueWords.isEmpty()) {
                notifyPlayer(player, dueWords);
            }
        }
    }

    public List<String> getDueReviewWords(UUID playerId) {
        long now = System.currentTimeMillis();

        CacheEntry cached = dueWordCache.get(playerId);
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.words;
        }

        Map<String, Long> lastReviewTimes = playerDataStore.getLastReviewTimes(playerId);
        Map<String, Integer> reviewCounts = playerDataStore.getReviewCounts(playerId);
        List<String> dueWords = new ArrayList<>();

        for (Map.Entry<String, Long> entry : lastReviewTimes.entrySet()) {
            String word = entry.getKey();
            long lastReview = entry.getValue();
            Integer reviewCount = reviewCounts.get(word);

            if (reviewCount == null || reviewCount >= reviewIntervalsSize) {
                continue;
            }

            long nextReviewTime = lastReview + reviewIntervalsMs[reviewCount];
            if (now >= nextReviewTime) {
                dueWords.add(word);
            }
        }

        dueWordCache.put(playerId, new CacheEntry(dueWords, now));
        return dueWords;
    }

    private void notifyPlayer(Player player, List<String> dueWords) {
        String message = plugin.getMessageUtil().getPlayerMessage(player,
                "review.notification", "count", String.valueOf(dueWords.size()));
        player.sendMessage(message);
    }

    public void recordReview(UUID playerId, String word) {
        playerDataStore.recordReview(playerId, word);
        dueWordCache.remove(playerId);
    }

    private static class CacheEntry {
        final List<String> words;
        final long timestamp;

        CacheEntry(List<String> words, long timestamp) {
            this.words = words;
            this.timestamp = timestamp;
        }
    }
}