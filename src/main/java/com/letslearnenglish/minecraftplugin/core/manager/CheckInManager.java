package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.CheckInData;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * CheckIn Manager
 *
 * Handles daily check-in logic: streak tracking, reward calculation,
 * and coin distribution via Vault (when available).
 */
public class CheckInManager {

    private final LetsLearnEnglish plugin;

    /**
     * Streak-based coin rewards.
     * Index = streak day (1-7). Index 0 is unused.
     * For streaks > 7, cycle: effectiveStreak = ((streak - 1) % 7) + 1
     */
    private static final int[] STREAK_REWARDS = {0, 10, 20, 30, 50, 70, 85, 100};

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public CheckInManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * Process daily check-in for a player.
     *
     * @param player the player checking in
     * @return CheckInResult with success flag, streak, coins earned, and message key
     */
    public CheckInResult checkIn(Player player) {
        // 1. Get today's date in server timezone
        String today = LocalDate.now().format(DATE_FORMAT);

        // 2. Load player's CheckInData from DB
        CheckInData data = plugin.getDatabaseManager().getCheckInData(player.getUniqueId());

        int newStreak;
        double coinsEarned;
        boolean streakBroken = false;

        if (data == null) {
            // First time check-in ever
            newStreak = 1;
            coinsEarned = STREAK_REWARDS[1];
        } else if (today.equals(data.getLastCheckinDate())) {
            // Already checked in today
            return new CheckInResult(false, data.getStreakCount(), 0, false);
        } else {
            // Check if last check-in was yesterday
            String yesterday = LocalDate.now().minusDays(1).format(DATE_FORMAT);
            if (yesterday.equals(data.getLastCheckinDate())) {
                // Consecutive day — increase streak
                newStreak = data.getStreakCount() + 1;
            } else {
                // Streak broken (2+ days gap)
                newStreak = 1;
                streakBroken = true;
            }

            // Calculate reward using cycling for streaks > 7
            int effectiveStreak = ((newStreak - 1) % 7) + 1;
            coinsEarned = STREAK_REWARDS[effectiveStreak];
        }

        // 8. Give coins via Vault if available
        giveCoins(player, coinsEarned);

        // 9. Update database
        plugin.getDatabaseManager().updateCheckIn(player.getUniqueId(), today, newStreak);

        return new CheckInResult(true, newStreak, coinsEarned, streakBroken);
    }

    /**
     * Get a player's current check-in info for display.
     *
     * @param player the player
     * @return CheckInData or null if never checked in
     */
    public CheckInData getCheckInInfo(Player player) {
        return plugin.getDatabaseManager().getCheckInData(player.getUniqueId());
    }

    /**
     * Give coins to the player via Vault economy.
     * Falls back gracefully if Vault is not available.
     *
     * @param player the player
     * @param amount the amount of coins to give
     * @return true if coins were given successfully, false otherwise
     */
    private boolean giveCoins(Player player, double amount) {
        Economy eco = plugin.getEconomy();
        if (eco == null) {
            return false;
        }

        boolean moneyEnabled = plugin.getConfigManager().getMainConfig()
                .getBoolean("progress-system.rewards.types.money.enabled", false);
        if (!moneyEnabled) {
            return false;
        }

        EconomyResponse response = eco.depositPlayer(player, amount);
        return response.transactionSuccess();
    }

    /**
     * Result of a check-in attempt.
     */
    public record CheckInResult(boolean success, int streak, double coinsEarned, boolean streakBroken) {
    }
}
