package com.letslearnenglish.minecraftplugin.core.progress;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.entity.Player;

/**
 * Reward Engine
 *
 * Handles distributing rewards to players for completing learning activities.
 * Supports multiple reward types: items, commands, experience, and money (via Vault).
 */
public class RewardEngine {

    private final LetsLearnEnglish plugin;

    public RewardEngine(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * Give a reward to a player for completing a learning session.
     *
     * @param player      the player to reward
     * @param score       the score achieved
     * @param accuracy    the accuracy rate (0.0 - 1.0)
     */
    public void giveSessionReward(Player player, int score, double accuracy) {
        if (!plugin.getConfigManager().getMainConfig()
                .getBoolean("progress-system.rewards.enabled", true)) {
            return;
        }

        giveExpReward(player, score);
    }

    /**
     * Give experience points as a reward.
     */
    private void giveExpReward(Player player, int score) {
        if (!plugin.getConfigManager().getMainConfig()
                .getBoolean("progress-system.rewards.types.exp.enabled", true)) {
            return;
        }

        int expAmount = Math.max(1, score / 2);
        player.giveExp(expAmount);
    }

    /**
     * Give a reward for unlocking an achievement.
     */
    public void giveAchievementReward(Player player, Achievement achievement) {
        String message = plugin.getMessageUtil().getPlayerMessage(player,
                "achievement.unlocked",
                "name", achievement.getName(),
                "description", achievement.getDescription());
        if (message != null && !message.isEmpty()) {
            player.sendMessage(message);
        }
    }
}