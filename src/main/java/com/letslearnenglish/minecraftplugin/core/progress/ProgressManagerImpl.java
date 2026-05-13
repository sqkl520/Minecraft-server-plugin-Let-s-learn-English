package com.letslearnenglish.minecraftplugin.core.progress;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

/**
 * Progress Manager Implementation
 *
 * Manages player learning progress, achievements, leaderboards, and rewards.
 */
public class ProgressManagerImpl {

    private final LetsLearnEnglish plugin;
    private final PlayerDataStore playerDataStore;

    public ProgressManagerImpl(LetsLearnEnglish plugin, ConfigManager configManager,
                               PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.playerDataStore = playerDataStore;
    }

    /**
     * Get a player's learning progress summary with actual data.
     */
    public PlayerProgress getPlayerProgress(java.util.UUID playerId) {
        PlayerDataStore.PlayerStats stats = playerDataStore.getPlayerStats(playerId);
        PlayerProgress progress = new PlayerProgress(playerId);
        progress.setTotalWordsLearned(stats.getTotalWords());
        progress.setTotalSessions(stats.getTotalSessions());
        progress.setTotalScore(stats.getTotalScore());
        return progress;
    }

    /**
     * Get the leaderboard instance.
     */
    public Leaderboard getLeaderboard() {
        return new Leaderboard(plugin);
    }

    /**
     * Get the reward engine instance.
     */
    public RewardEngine getRewardEngine() {
        return new RewardEngine(plugin);
    }
}