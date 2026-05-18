package com.letslearnenglish.minecraftplugin.core.progress;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

public class ProgressManagerImpl {

    private final LetsLearnEnglish plugin;
    private final PlayerDataStore playerDataStore;
    private final DatabaseManager databaseManager;

    private volatile Leaderboard leaderboard;
    private volatile RewardEngine rewardEngine;

    public ProgressManagerImpl(LetsLearnEnglish plugin, PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.playerDataStore = playerDataStore;
        this.databaseManager = plugin.getDatabaseManager();
    }

    public PlayerProgress getPlayerProgress(UUID playerId) {
        PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(playerId);
        PlayerProgress progress = new PlayerProgress(playerId);
        progress.setTotalWordsLearned(stats.getTotalWords());
        progress.setTotalSessions(stats.getTotalSessions());
        progress.setTotalScore(stats.getTotalScore());
        loadStreakData(playerId, progress);
        return progress;
    }

    private void loadStreakData(UUID playerId, PlayerProgress progress) {
        String sql = "SELECT current_streak, longest_streak FROM "
                + databaseManager.getTablePrefix() + "player_data WHERE uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    progress.setCurrentStreak(rs.getInt("current_streak"));
                    progress.setLongestStreak(rs.getInt("longest_streak"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to load streak data for player: " + playerId, e);
        }
    }

    public Leaderboard getLeaderboard() {
        if (leaderboard == null) {
            synchronized (this) {
                if (leaderboard == null) {
                    leaderboard = new Leaderboard(plugin);
                }
            }
        }
        return leaderboard;
    }

    public RewardEngine getRewardEngine() {
        if (rewardEngine == null) {
            synchronized (this) {
                if (rewardEngine == null) {
                    rewardEngine = new RewardEngine(plugin);
                }
            }
        }
        return rewardEngine;
    }
}