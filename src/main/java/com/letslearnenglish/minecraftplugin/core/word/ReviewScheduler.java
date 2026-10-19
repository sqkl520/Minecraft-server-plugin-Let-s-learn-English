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
    private final long masteredReviewIntervalMs;

    private final Map<UUID, CacheEntry> dueWordCache = new ConcurrentHashMap<>();
    // FIX-REVIEW-01 去重：记住每个玩家上次收到复习通知的自然日（内存态，跨天自动失效）
    private final Map<UUID, java.time.LocalDate> lastNotifiedDate = new ConcurrentHashMap<>();

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

        int masteredDays = plugin.getConfigManager().getMainConfig()
                .getInt("word-system.review.mastered-review-interval-days", 30);
        this.masteredReviewIntervalMs = TimeUnit.DAYS.toMillis(masteredDays);
    }

    public void checkAndNotifyReviews() {
        // MC-2（S-H）/ PF-MC-05：任务体内门（点读 config，reload 即时生效）——
        // 单词模块总门关闭、或复习子开关关闭时整个任务静默 no-op。
        // LetsLearnEnglish 侧的排程只在 enable 时判一次（reload 不重建任务），
        // 故这里的体内门是 reload 后"关得干净"的唯一保障，也是 word 总开关的兜底。
        if (!plugin.getConfigManager().isWordSystemEnabled()
                || !plugin.getConfigManager().getMainConfig()
                        .getBoolean("word-system.review.enabled", true)) {
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 同一玩家同一天只提醒一次（30 秒 TTL 缓存 + 5 分钟定时任务原本会反复刷屏）
            if (today.equals(lastNotifiedDate.get(player.getUniqueId()))) {
                continue;
            }
            List<String> dueWords = getDueReviewWords(player.getUniqueId());
            if (!dueWords.isEmpty()) {
                notifyPlayer(player, dueWords);
                lastNotifiedDate.put(player.getUniqueId(), today);
            }
        }
    }

    /**
     * 跨天清理复习通知去重态（由午夜任务调用）。不清理也能工作
     * （日期比对天然失效），这里顺手释放离线玩家的内存。
     */
    public void clearNotifiedDates() {
        lastNotifiedDate.clear();
    }

    /**
     * R13-M4 quit 清理钩：清玩家的 due 词缓存条目（只增不删的口径与本类其他清理
     * 同族）——quit 后 rejoin 首次 getDueReviewWords cache miss 重查 DB 重建，
     * TTL 语义不变，仅内存回收；中断玩家在途条目不再永留。由
     * PlayerJoinListener.onPlayerQuit 经 wordManager.getReviewScheduler() 调用。
     */
    public void unloadPlayer(UUID playerId) {
        dueWordCache.remove(playerId);
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

            if (reviewCount == null) {
                continue;
            }

            long nextReviewTime;
            if (reviewCount >= reviewIntervalsSize) {
                // Word has completed all Ebbinghaus intervals — enter low-frequency
                // MASTERED review pool with a configurable long-interval cycle
                nextReviewTime = lastReview + masteredReviewIntervalMs;
            } else {
                nextReviewTime = lastReview + reviewIntervalsMs[reviewCount];
            }

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

    /**
     * H5：普通学习结算时为答过的词写入复习种子。
     * 种子语义：lastReviewTime=now、reviewCount=0 → 读侧 getDueReviewWords
     * 算出 nextReview = now + intervals[0]，未到期前不会立即 due。
     */
    public void seedReview(UUID playerId, String word) {
        playerDataStore.seedReview(playerId, word);
        dueWordCache.remove(playerId);
    }

    /**
     * H5：复习答错时重置该词的间隔（reviewCount→0、lastReviewTime=now）。
     * 下次 due = now + intervals[0]（默认 1h），很快会再提醒。
     */
    public void resetReview(UUID playerId, String word) {
        playerDataStore.resetReview(playerId, word);
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