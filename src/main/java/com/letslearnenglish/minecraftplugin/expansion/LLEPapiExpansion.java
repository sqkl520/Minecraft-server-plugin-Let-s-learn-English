package com.letslearnenglish.minecraftplugin.expansion;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PlaceholderAPI expansion for Let's Learn English!
 *
 * Provides these placeholders (all support both zh/en players):
 *   %lle_score%          — total learning score
 *   %lle_coins_earned%   — lifetime coins earned
 *   %lle_rank%           — rank text ("#1" or "未上榜"/"Unranked")
 *   %lle_words%          — total words attempted
 *   %lle_accuracy%       — accuracy percentage (e.g. "85.3%")
 *   %lle_sessions%       — total learning sessions
 *   %lle_streak%         — current daily streak
 *   %lle_longest_streak% — longest daily streak
 *   %lle_achievements%   — "unlocked/total" (e.g. "3/16")
 *   %lle_correct%        — total correct answers
 *   %lle_incorrect%      — total incorrect answers
 *
 * R-43 privacy: looking at somebody else's placeholders first checks their
 * switches — showInSquare=false (invisible) returns the private placeholder
 * (rank falls back to the unranked text so scoreboard standings keep working);
 * showResults=false hides the learning-score family. Coins follow the same
 * rule as ProfileGUI: only the owner sees their own number.
 *
 * R-43 cache: every resolved value is cached per (player, params, viewer
 * language/relationship bucket) with a 30s TTL so scoreboard refreshes no
 * longer hit the DB every tick. Thread-safe via ConcurrentHashMap; PAPI
 * callbacks run on the main thread but other plugins may call off-thread.
 */
public class LLEPapiExpansion extends PlaceholderExpansion {

    /** R-43：占位符缓存 TTL（毫秒）。 */
    private static final long CACHE_TTL_MILLIS = 30_000L;
    /** R-43：缓存上限条目数，超限随机淘汰一批。 */
    private static final int CACHE_MAX_ENTRIES = 5000;
    /** R-43：超限时一次淘汰的条目数。 */
    private static final int CACHE_EVICT_BATCH = 500;

    /** Learn placeholders (score family) gated by target showResults. */
    private static final java.util.Set<String> RESULTS_GATED = java.util.Set.of(
            "score", "words", "accuracy", "sessions", "correct", "incorrect");

    private final LetsLearnEnglish plugin;

    /** R-43: key = targetUuid + "|" + params + "|" + selfFlag + "|" + lang. */
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    private static final class CachedValue {
        final String value;
        final long expiresAt;

        CachedValue(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    public LLEPapiExpansion(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "lle";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return plugin.getDescription().getAuthors().isEmpty()
                ? "sqkl520" : plugin.getDescription().getAuthors().get(0);
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Keep registered across /papi reload
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) {
            return "";
        }
        UUID playerId = offlinePlayer.getUniqueId();

        // R-43: unknown params keep the legacy contract (null = let other
        // expansions try). They are never cached.
        if (!isKnownParams(params)) {
            return null;
        }

        // R-43 缓存键必须区分查看语言与查看关系：同一 (uuid, params) 在
        // zh/en 玩家、本人/他人视角下返回值不同，不能串味。
        Player viewer = offlinePlayer.isOnline() ? offlinePlayer.getPlayer() : null;
        String lang = viewer != null
                ? plugin.getPlayerLanguage(viewer)
                : plugin.getConfigManager().getMainConfig().getString("general.language", "zh");
        // 本人/他人关系：off-viewer（控制台/其他插件无 viewer 上下文）按"非本人"处理，
        // 隐私默认收紧。
        boolean isSelfView = isSelfView(offlinePlayer, viewer);
        String cacheKey = playerId + "|" + params + "|" + (isSelfView ? "self" : "other") + "|" + lang;

        long now = System.currentTimeMillis();
        CachedValue cached = cache.get(cacheKey);
        if (cached != null) {
            if (now < cached.expiresAt) {
                return cached.value;
            }
            cache.remove(cacheKey, cached);
        }

        String resolved = resolveRequest(offlinePlayer, viewer, playerId, params, isSelfView);
        if (resolved != null) {
            maybeEvict();
            cache.put(cacheKey, new CachedValue(resolved, now + CACHE_TTL_MILLIS));
        }
        return resolved;
    }

    /**
     * R-43: 本人视角判定。
     * PAPI 的 onRequest 只有一个 OfflinePlayer 参数（既是目标也是名义上的查看者），
     * 跨玩家场景（A 的计分板解析 B 的占位符）由 PlaceholderAPI 以 B 的上下文回调，
     * 此时无法得知真正的屏幕前 viewer，只能按"非本人"收紧。
     * 只有目标在线（viewer 非空）且 UUID 相等时才算本人 —— 而 PAPI 回调的
     * viewer 恒等于 target，所以在线目标在此恒为本人；离线/控制台解析恒为他人。
     */
    private boolean isSelfView(OfflinePlayer target, Player viewer) {
        return viewer != null && viewer.getUniqueId().equals(target.getUniqueId());
    }

    private String resolveRequest(OfflinePlayer offlinePlayer, Player viewer,
                                  UUID playerId, @NotNull String params, boolean isSelfView) {
        PlayerDataStore store = plugin.getPlayerDataStore();

        // L-05：展示路径走只读版，不建缓存条目。
        PlayerDataStore.ProfileSettings ps = store.getProfileSettingsReadOnly(playerId);

        // R-43 门禁 1：隐身者（showInSquare=false）—— 非本人查看时，
        // 所有占位符返回隐私占位串；rank 例外：返回未上榜文本（榜单占位符的
        // 既有语义，P0 R-05 SQL 已把隐身者排除出榜，此处与之对齐）。
        if (!isSelfView && !ps.isShowInSquare()) {
            if (params.equals("rank")) {
                return getUnrankedText(offlinePlayer);
            }
            return getPrivateText(viewer);
        }

        // R-43 门禁 2：学习成绩族叠加目标 showResults 检查（非本人查看时）。
        if (!isSelfView && !ps.isShowResults() && RESULTS_GATED.contains(params)) {
            return getPrivateText(viewer);
        }

        // R-43 门禁 3：金币只给本人（与 ProfileGUI R-38 金币规则对齐）。
        if (!isSelfView && params.equals("coins_earned")) {
            return getPrivateText(viewer);
        }

        // Ensure data is loaded (may trigger DB load for offline players)
        PlayerDataStore.PlayerStats stats = store.getPlayerStats(playerId);
        if (stats == null) {
            return params.equals("rank") ? getUnrankedText(offlinePlayer) : "0";
        }

        return switch (params) {
            case "score" -> String.valueOf(stats.getTotalScore());
            case "coins_earned" -> String.valueOf(plugin.getDatabaseManager().getCoinsEarned(playerId));
            case "rank" -> {
                int rank = plugin.getProgressManager().getLeaderboard().getPlayerRank(playerId);
                yield rank > 0 ? "#" + rank : getUnrankedText(offlinePlayer);
            }
            case "words" -> String.valueOf(stats.getTotalWords());
            case "accuracy" -> {
                int total = stats.getTotalCorrect() + stats.getTotalIncorrect();
                if (total == 0) {
                    yield "0.0%";
                }
                double acc = (double) stats.getTotalCorrect() / total * 100.0;
                yield String.format("%.1f%%", acc);
            }
            case "sessions" -> String.valueOf(stats.getTotalSessions());
            case "streak" -> String.valueOf(getStreak(playerId, "current_streak"));
            case "longest_streak" -> String.valueOf(getStreak(playerId, "longest_streak"));
            case "achievements" -> {
                int unlocked = plugin.getAchievementManager().getUnlockedCount(playerId);
                int total = com.letslearnenglish.minecraftplugin.core.achievement.Achievement.values().length;
                yield unlocked + "/" + total;
            }
            case "correct" -> String.valueOf(stats.getTotalCorrect());
            case "incorrect" -> String.valueOf(stats.getTotalIncorrect());
            default -> null; // Unknown placeholder
        };
    }

    private boolean isKnownParams(String params) {
        return switch (params) {
            case "score", "coins_earned", "rank", "words", "accuracy", "sessions",
                    "streak", "longest_streak", "achievements", "correct", "incorrect" -> true;
            default -> false;
        };
    }

    /**
     * R-43：缓存超限时随机淘汰一批。ConcurrentHashMap 的 keySet 迭代器是
     * 弱一致的，多线程下安全；淘汰不精确计数，够用即可。
     */
    private void maybeEvict() {
        if (cache.size() < CACHE_MAX_ENTRIES) {
            return;
        }
        int removed = 0;
        for (String key : cache.keySet()) {
            if (removed >= CACHE_EVICT_BATCH) {
                break;
            }
            if (cache.remove(key) != null) {
                removed++;
            }
        }
    }

    private String getPrivateText(Player viewer) {
        if (viewer != null) {
            return plugin.getMessageUtil().getPlayerMessage(viewer, "placeholder.private");
        }
        return plugin.getMessageUtil().getMessage("placeholder.private");
    }

    private String getUnrankedText(OfflinePlayer player) {
        // 在线玩家按其自身语言取，离线玩家没有语言信息，退回服务端默认语言
        if (player.isOnline() && player.getPlayer() != null) {
            return plugin.getMessageUtil().getPlayerMessage(player.getPlayer(), "placeholder.rank-unranked");
        }
        return plugin.getMessageUtil().getMessage("placeholder.rank-unranked");
    }

    private int getStreak(UUID playerId, String column) {
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT " + column + " FROM " + tablePrefix + "player_data WHERE uuid = ?";
        java.sql.Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(column);
                }
            }
        } catch (java.sql.SQLException e) {
            // Silently return 0 on error
        }
        }
        return 0;
    }
}
