package com.letslearnenglish.minecraftplugin.core.achievement;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;

/**
 * Achievement Manager
 *
 * Tracks achievement progress for all players and handles unlocking logic.
 * Achievement data is persisted in the database via the achievements table.
 * In-memory cache is maintained per-player for fast lookups.
 *
 * Triggered by ProgressManagerImpl after each session completes, and by
 * WordLearningManager after each answer (for streak/perfect checks).
 */
public class AchievementManager {

    private final LetsLearnEnglish plugin;
    private final PlayerDataStore playerDataStore;
    private final Map<UUID, Set<String>> unlockedCache;

    public AchievementManager(LetsLearnEnglish plugin, PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.playerDataStore = playerDataStore;
        this.unlockedCache = new HashMap<>();
    }

    /**
     * Load a player's unlocked achievements into the cache.
     */
    public void loadPlayerAchievements(UUID playerId) {
        Set<String> unlocked = new HashSet<>();
        String sql = "SELECT achievement_id FROM "
                + plugin.getDatabaseManager().getTablePrefix() + "achievements WHERE uuid = ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    unlocked.add(rs.getString("achievement_id"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to load achievements for " + playerId, e);
        }
        unlockedCache.put(playerId, unlocked);
    }

    /**
     * Check if a player has unlocked an achievement.
     */
    public boolean hasAchievement(UUID playerId, Achievement achievement) {
        Set<String> unlocked = unlockedCache.get(playerId);
        if (unlocked == null) {
            loadPlayerAchievements(playerId);
            unlocked = unlockedCache.get(playerId);
        }
        return unlocked != null && unlocked.contains(achievement.getId());
    }

    /**
     * Get all unlocked achievements for a player.
     */
    public Set<Achievement> getUnlockedAchievements(UUID playerId) {
        Set<String> unlockedIds = unlockedCache.get(playerId);
        if (unlockedIds == null) {
            loadPlayerAchievements(playerId);
            unlockedIds = unlockedCache.get(playerId);
        }
        Set<Achievement> result = new LinkedHashSet<>();
        if (unlockedIds != null) {
            for (String id : unlockedIds) {
                Achievement a = Achievement.fromId(id);
                if (a != null) result.add(a);
            }
        }
        return result;
    }

    /**
     * Get the count of unlocked achievements for a player.
     */
    public int getUnlockedCount(UUID playerId) {
        Set<String> unlocked = unlockedCache.get(playerId);
        if (unlocked == null) {
            loadPlayerAchievements(playerId);
            unlocked = unlockedCache.get(playerId);
        }
        return unlocked != null ? unlocked.size() : 0;
    }

    /**
     * Try to unlock an achievement. If already unlocked, this is a no-op.
     * Broadcasts a message to the player if newly unlocked.
     */
    public boolean tryUnlock(UUID playerId, Achievement achievement) {
        Set<String> unlocked = unlockedCache.computeIfAbsent(playerId, k -> new HashSet<>());
        if (unlocked.contains(achievement.getId())) {
            return false;
        }

        unlocked.add(achievement.getId());

        String sql = "INSERT OR REPLACE INTO "
                + plugin.getDatabaseManager().getTablePrefix()
                + "achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?)";
        if (!plugin.getDatabaseManager().isSQLite()) {
            sql = "INSERT INTO " + plugin.getDatabaseManager().getTablePrefix()
                    + "achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE unlocked_at=VALUES(unlocked_at)";
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, achievement.getId());
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to save achievement " + achievement.getId() + " for " + playerId, e);
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            String lang = plugin.getPlayerLanguage(player);
            String title = "zh".equalsIgnoreCase(lang) ? "&6&l成就解锁！" : "&6&lAchievement Unlocked!";
            String name = achievement.getDisplayName(lang);
            String desc = achievement.getDisplayDesc(lang);
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    "&b&l✧ " + title + " &f- " + name));
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    "&7" + desc));
        }
        return true;
    }

    /**
     * Check and unlock all applicable achievements after a session completes.
     * Called by ProgressManagerImpl.
     */
    public void checkSessionAchievements(UUID playerId, int sessionCorrect, int sessionIncorrect,
                                          int sessionWordsCount, String mode) {
        PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(playerId);

        if (sessionCorrect >= 1 && sessionIncorrect == 0) {
            tryUnlock(playerId, Achievement.FIRST_WORD);
        }

        int totalCorrect = stats.getTotalCorrect();
        if (totalCorrect >= 25) tryUnlock(playerId, Achievement.WORDS_25);
        if (totalCorrect >= 100) tryUnlock(playerId, Achievement.WORDS_100);
        if (totalCorrect >= 500) tryUnlock(playerId, Achievement.WORDS_500);

        if (sessionCorrect == sessionWordsCount && sessionIncorrect == 0 && sessionWordsCount > 0) {
            tryUnlock(playerId, Achievement.PERFECT_SESSION);
        }

        if ("spelling".equalsIgnoreCase(mode)) {
            tryUnlock(playerId, Achievement.SPELLING_MASTER);
        }

        int totalSessions = stats.getTotalSessions();
        if (totalSessions >= 10) tryUnlock(playerId, Achievement.SESSIONS_10);
        if (totalSessions >= 50) tryUnlock(playerId, Achievement.SESSIONS_50);
    }

    /**
     * Check achievement for in-session streak.
     * Called by WordLearningManager after each correct answer within a session.
     */
    public void checkStreakAchievement(UUID playerId, int currentSessionStreak) {
        if (currentSessionStreak >= 3) tryUnlock(playerId, Achievement.STREAK_3);
        if (currentSessionStreak >= 10) tryUnlock(playerId, Achievement.STREAK_10);
    }

    /**
     * Check coin-based achievements.
     */
    public void checkCoinAchievements(UUID playerId, long totalCoins) {
        if (totalCoins >= 500) tryUnlock(playerId, Achievement.COINS_500);
        if (totalCoins >= 5000) tryUnlock(playerId, Achievement.COINS_5000);
    }

    /**
     * Unload achievement data from cache (called on player quit).
     */
    public void unloadPlayer(UUID playerId) {
        unlockedCache.remove(playerId);
    }

    /**
     * Clear all achievement caches (called on reload).
     */
    public void clearCache() {
        unlockedCache.clear();
    }
}