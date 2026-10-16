package com.letslearnenglish.minecraftplugin.core.progress;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.CheckInData;
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
        // P3-03 收口：current_streak 的语义 = 签到连击，真源是 checkin_data.streak_count
        //（player_data.current_streak 列为历史遗留、零写入恒 0，不再作为数据源）；
        // longest_streak = 答题会话连击峰值，仍读 player_data 列。
        String sql = "SELECT longest_streak FROM "
                + databaseManager.getTablePrefix() + "player_data WHERE uuid = ?";
        Connection conn = databaseManager.getConnection();
        synchronized (databaseManager.getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    progress.setLongestStreak(rs.getInt("longest_streak"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to load streak data for player: " + playerId, e);
        }
        }
        // current_streak 从签到真源单独取：getCheckInData 自带 connectionLock
        //（对象锁可重入，嵌套也不会死锁；放在外层锁外只为缩短持锁区间、保持
        // 单查询单加锁的既有姿势）。
        CheckInData checkIn = databaseManager.getCheckInData(playerId);
        progress.setCurrentStreak(checkIn == null ? 0 : checkIn.getStreakCount());
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
