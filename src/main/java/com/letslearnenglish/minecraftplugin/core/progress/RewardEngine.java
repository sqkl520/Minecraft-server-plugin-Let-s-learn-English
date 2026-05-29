package com.letslearnenglish.minecraftplugin.core.progress;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
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

    private Economy getEconomy() {
        return plugin.getEconomy();
    }

    private boolean isMoneyEnabled() {
        return plugin.getConfigManager().getMainConfig()
                .getBoolean("progress-system.rewards.types.money.enabled", false);
    }

    /**
     * Give a money reward for a single correct answer.
     * Called when player answers a word correctly.
     * If the word has a custom reward (&gt;0), use it directly (no combo multiplier).
     * Otherwise fall back to global config base-amount × combo multiplier.
     *
     * @param player            the player to reward
     * @param comboMultiplier   current combo multiplier (1.0x - 3.0x)
     * @param word              the word that was answered correctly
     */
    public void giveMoneyForCorrectAnswer(Player player, double comboMultiplier, Word word) {
        Economy eco = getEconomy();
        if (eco == null || !isMoneyEnabled()) return;

        double amount;
        if (word != null && word.getReward() > 0) {
            // Word has custom reward, use it directly
            amount = word.getReward();
        } else {
            double baseAmount = plugin.getConfigManager().getMainConfig()
                    .getDouble("progress-system.rewards.types.money.base-amount", 10.0);
            boolean comboBonus = plugin.getConfigManager().getMainConfig()
                    .getBoolean("progress-system.rewards.types.money.combo-bonus", true);
            amount = comboBonus ? Math.round(baseAmount * comboMultiplier * 100.0) / 100.0 : baseAmount;
        }

        EconomyResponse response = eco.depositPlayer(player, amount);
        if (response.transactionSuccess()) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-reward",
                    "amount", eco.format(amount));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        } else {
            plugin.getLogger().warning("Failed to deposit money for "
                    + player.getName() + ": " + response.errorMessage);
        }
    }

    /**
     * Give money reward on session completion.
     * Awards session-complete-bonus and optional max-combo-bonus.
     *
     * @param player           the player to reward
     * @param maxComboReached  the highest consecutive correct count in the session
     */
    public void giveSessionCompleteMoneyReward(Player player, int maxComboReached) {
        Economy eco = getEconomy();
        if (eco == null || !isMoneyEnabled()) return;

        double sessionBonus = plugin.getConfigManager().getMainConfig()
                .getDouble("progress-system.rewards.types.money.session-complete-bonus", 50.0);
        double maxComboBonus = plugin.getConfigManager().getMainConfig()
                .getDouble("progress-system.rewards.types.money.max-combo-bonus", 25.0);

        double totalAmount = sessionBonus;
        if (maxComboReached >= 5) {
            totalAmount += maxComboBonus;
        }

        EconomyResponse response = eco.depositPlayer(player, totalAmount);
        if (response.transactionSuccess()) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-session-bonus",
                    "amount", eco.format(totalAmount));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        } else {
            plugin.getLogger().warning("Failed to deposit session bonus for "
                    + player.getName() + ": " + response.errorMessage);
        }
    }

    /**
     * Give a money reward for a correct answer during review mode.
     * If the word has a custom reward (&gt;0), use it directly.
     * Otherwise uses a separate review multiplier to differentiate from learning rewards.
     *
     * @param player            the player to reward
     * @param comboMultiplier   current combo multiplier (1.0x - 3.0x)
     * @param word              the word that was answered correctly
     */
    public void giveMoneyForReviewCorrect(Player player, double comboMultiplier, Word word) {
        Economy eco = getEconomy();
        if (eco == null || !isMoneyEnabled()) return;

        double amount;
        if (word != null && word.getReward() > 0) {
            // Word has custom reward, use it directly
            amount = word.getReward();
        } else {
            double baseAmount = plugin.getConfigManager().getMainConfig()
                    .getDouble("progress-system.rewards.types.money.base-amount", 10.0);
            double reviewMultiplier = plugin.getConfigManager().getMainConfig()
                    .getDouble("progress-system.rewards.types.money.review-multiplier", 0.5);
            boolean comboBonus = plugin.getConfigManager().getMainConfig()
                    .getBoolean("progress-system.rewards.types.money.combo-bonus", true);

            amount = baseAmount * reviewMultiplier;
            if (comboBonus) {
                amount = Math.round(amount * comboMultiplier * 100.0) / 100.0;
            }
        }

        EconomyResponse response = eco.depositPlayer(player, amount);
        if (response.transactionSuccess()) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-review-reward",
                    "amount", eco.format(amount));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        } else {
            plugin.getLogger().warning("Failed to deposit review money for "
                    + player.getName() + ": " + response.errorMessage);
        }
    }

    /**
     * Deduct money from a player for an incorrect answer during review mode.
     * Only applies if review-penalty is enabled in config.
     *
     * @param player the player to penalize
     */
    public void giveMoneyForReviewIncorrect(Player player) {
        Economy eco = getEconomy();
        if (eco == null || !isMoneyEnabled()) return;

        boolean penaltyEnabled = plugin.getConfigManager().getMainConfig()
                .getBoolean("progress-system.rewards.types.money.review-penalty", false);
        if (!penaltyEnabled) return;

        double penaltyAmount = plugin.getConfigManager().getMainConfig()
                .getDouble("progress-system.rewards.types.money.review-penalty-amount", 5.0);

        EconomyResponse response = eco.withdrawPlayer(player, penaltyAmount);
        if (response.transactionSuccess()) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-review-penalty",
                    "amount", eco.format(penaltyAmount));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        } else {
            plugin.getLogger().warning("Failed to withdraw penalty money for "
                    + player.getName() + ": " + response.errorMessage);
        }
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