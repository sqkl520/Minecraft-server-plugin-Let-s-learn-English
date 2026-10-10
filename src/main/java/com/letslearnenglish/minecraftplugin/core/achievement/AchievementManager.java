package com.letslearnenglish.minecraftplugin.core.achievement;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private FileConfiguration achievementConfig;

    public AchievementManager(LetsLearnEnglish plugin, PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.playerDataStore = playerDataStore;
        this.unlockedCache = new ConcurrentHashMap<>();
    }

    private FileConfiguration getAchievementConfig() {
        if (achievementConfig == null) {
            File file = new File(plugin.getDataFolder(), "achievements-config.yml");
            if (!file.exists()) {
                plugin.saveResource("achievements-config.yml", false);
            }
            achievementConfig = YamlConfiguration.loadConfiguration(file);
        }
        return achievementConfig;
    }

    /**
     * Load a player's unlocked achievements into the cache.
     */
    public void loadPlayerAchievements(UUID playerId) {
        Set<String> unlocked = new HashSet<>();
        String sql = "SELECT achievement_id FROM "
                + plugin.getDatabaseManager().getTablePrefix() + "achievements WHERE uuid = ?";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
        FileConfiguration achConfig = getAchievementConfig();
        if (!isAchievementEligible(achConfig, achievement)) {
            return false;
        }
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

        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, achievement.getId());
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to save achievement " + achievement.getId() + " for " + playerId, e);
        }
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            FileConfiguration messages = plugin.getConfigManager()
                    .getMessageConfig(plugin.getPlayerLanguage(player));
            String title = messages.getString("achievement.unlock-title");
            String fmt = messages.getString("achievement.unlock-format");
            String name = achievement.getDisplayName(plugin.getPlayerLanguage(player));
            String desc = achievement.getDisplayDesc(plugin.getPlayerLanguage(player));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    fmt.replace("{title}", title).replace("{name}", name)));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&7" + desc));

            String soundName = plugin.getConfigManager().getMainConfig()
                    .getString("achievement-system.unlock-sound", "ENTITY_PLAYER_LEVELUP");
            if (soundName != null && !soundName.isEmpty()) {
                SoundManager.playSound(player, "complete");
            }

            // 跨服成就广播（Velocity / BungeeCord）
            if (plugin.getProxyMessenger() != null && plugin.getProxyMessenger().isRegistered()) {
                plugin.getProxyMessenger().sendAchievementBroadcast(
                        player.getName(), playerId, achievement.getDisplayName(plugin.getPlayerLanguage(player)));
            }
        }
        return true;
    }

    private boolean isAchievementEligible(FileConfiguration config, Achievement achievement) {
        if (config == null) return true;
        if (!config.getBoolean("achievements." + achievement.getId() + ".enabled", true)) {
            return false;
        }
        if (!config.getBoolean("achievements." + achievement.getId() + ".time-limit.enabled", false)) {
            return true;
        }
        String startStr = config.getString("achievements." + achievement.getId() + ".time-limit.start");
        String endStr = config.getString("achievements." + achievement.getId() + ".time-limit.end");
        if (startStr == null || endStr == null) return true;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            Date start = sdf.parse(startStr);
            Date end = sdf.parse(endStr);
            Date now = new Date();
            return !now.before(start) && !now.after(end);
        } catch (java.text.ParseException e) {
            return true;
        }
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

        checkDailyStreak(playerId);
        long totalCoins = plugin.getDatabaseManager().getCoinsEarned(playerId);
        if (totalCoins >= 500) tryUnlock(playerId, Achievement.COINS_500);
        if (totalCoins >= 5000) tryUnlock(playerId, Achievement.COINS_5000);
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

    private void checkDailyStreak(UUID playerId) {
        java.util.Set<String> days = new java.util.LinkedHashSet<>();
        String daySql = "SELECT session_date FROM "
                + plugin.getDatabaseManager().getTablePrefix()
                + "player_sessions WHERE uuid = ? ORDER BY session_date DESC";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(daySql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    days.add(rs.getString("session_date"));
                }
            }
        } catch (SQLException e) {
            return;
        }
        }

        int consecutive = 0;
        java.time.LocalDate today = java.time.LocalDate.now();
        for (int i = 0; i < 30; i++) {
            java.time.LocalDate date = today.minusDays(i);
            String dateStr = date.toString();
            if (days.contains(dateStr)) {
                consecutive++;
            } else if (i > 0) {
                break;
            }
        }
        if (consecutive >= 3) tryUnlock(playerId, Achievement.DAILY_STREAK_3);
        if (consecutive >= 7) tryUnlock(playerId, Achievement.DAILY_STREAK_7);
    }

    /**
     * Unload achievement data from cache (called on player quit).
     */
    public void unloadPlayer(UUID playerId) {
        unlockedCache.remove(playerId);
    }

    /**
     * Check AI chat conversation count achievements.
     * Called when a player ends an AI scenario conversation.
     */
    public void checkAIChatAchievement(UUID playerId, int totalConversations) {
        if (totalConversations >= 10) tryUnlock(playerId, Achievement.AI_CHAT_10);
        if (totalConversations >= 50) tryUnlock(playerId, Achievement.AI_CHAT_50);
    }

    /**
     * Clear all achievement caches (called on reload).
     * H9：同步刷新懒加载的 achievementConfig，下次 tryUnlock 经 getAchievementConfig()
     * 重新从磁盘读取 — 否则 reload 后成就开关/时间窗口改动永不生效。
     */
    public void clearCache() {
        unlockedCache.clear();
        achievementConfig = null;
    }
}
