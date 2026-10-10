package com.letslearnenglish.minecraftplugin.expansion;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

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
 */
public class LLEPapiExpansion extends PlaceholderExpansion {

    private final LetsLearnEnglish plugin;

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
        PlayerDataStore store = plugin.getPlayerDataStore();

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
