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

    /**
     * 内置默认复习档位（小时）：1h / 6h / 24h / 72h / 168h(7d)。
     *
     * <p>取值与出厂 {@code config.yml} 的 {@code word-system.review.intervals}
     * （{@code [1, 6, 24, 72, 168]}）逐字一致 —— 这不是"另一套参数"，就是出厂档位本身，
     * 只是在这里再存一份常量，以便配置读空时能继续按出厂行为工作。
     *
     * <p>为什么需要这份兜底：{@code YamlConfiguration#getIntegerList} 在<b>键缺失</b>时会回落到
     * defaults（{@code JavaPlugin#reloadConfig} 先 {@code setDefaults(getResource("config.yml"))}，
     * 出厂值把这一半兜住了）；但服主显式写 {@code intervals: []} 时 defaults 救不了 ——
     * 键存在、值就是空列表，读到的是真空表。此时若放任 {@code reviewIntervalsSize = 0}，
     * 读侧 {@code reviewCount >= reviewIntervalsSize} 对任何词都恒成立，每个词都被判为
     * "已掌握"塞进 30 天 mastered 池 —— 复习提醒事实上停摆一个月，而构造器零日志，
     * 服主完全无从察觉。故空表一律按出厂档位继续工作，并 WARNING 点名该键。
     */
    private static final int[] DEFAULT_REVIEW_INTERVALS_HOURS = {1, 6, 24, 72, 168};

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
        // FIX-REVIEW-EMPTY（Round 31）：显式写 `intervals: []` 时 defaults 兜不住 —— 键在、值就是
        // 空表。若放任 reviewIntervalsSize=0，读侧 `reviewCount >= reviewIntervalsSize` 对任何词
        // 恒真，全部词被塞进 30 天 mastered 池；而本类零日志，服主无从察觉。
        // 修复必须两条一起：WARNING 让服主能看见配置错误，回落出厂档位让复习照常工作。
        // 只做告警 = 功能还是坏的；只做回落 = 配置错误被掩盖。
        if (intervals.isEmpty()) {
            plugin.getLogger().warning("[Config Audit] 'word-system.review.intervals' is empty"
                    + " (it is present in config.yml but has no entries). Left as-is, every word"
                    + " would be treated as mastered and review reminders would degrade to the"
                    + " 30-day mastered cycle. Falling back to the built-in defaults "
                    + Arrays.toString(DEFAULT_REVIEW_INTERVALS_HOURS) + " (hours)."
                    + " Please check the 'word-system.review.intervals' key in config.yml.");
            List<Integer> builtInDefaults = new ArrayList<>(DEFAULT_REVIEW_INTERVALS_HOURS.length);
            for (int hours : DEFAULT_REVIEW_INTERVALS_HOURS) {
                builtInDefaults.add(hours);
            }
            intervals = builtInDefaults;
        }
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
            // R1/R4：玩家侧提醒总开关。放在去重判断之前，且**不**放进 getDueReviewWords ——
            // due 词计算是复习页/学习功能依赖，关提醒不应让复习功能失能，只该停止推送。
            if (!playerDataStore.getProfileSettingsReadOnly(player.getUniqueId()).isRemindersEnabled()) {
                continue;
            }
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

            // slice-10-logic-01：0 = 「从未排程」哨兵而非 1970 时间戳，不拦则永久 due。
            if (lastReview <= 0) {
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
        // slice-10-dc-01：读侧把 lastReview <= 0 当「从未排程」（本类 :115），但
        // PlayerDataStore.seedReview 的 guard 是 containsKey —— DB 里未作答词的空行
        // last_review_time 恰为 0，键存在即空转，词被永久锁在复习池外。写侧补同一口径：
        // 0 值按未排程处理，走 resetReview（无条件写 now/0），已有真实种子仍是幂等覆盖。
        Long lastReview = playerDataStore.getLastReviewTimes(playerId).get(word.toLowerCase());
        playerDataStore.seedReview(playerId, word);   // 仍先走：补 mastery 行，否则种子落不了盘
        if (lastReview != null && lastReview <= 0L) {
            playerDataStore.resetReview(playerId, word);
        }
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