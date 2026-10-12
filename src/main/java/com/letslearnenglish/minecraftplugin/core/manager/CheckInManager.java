package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.CheckInData;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CheckIn Manager
 *
 * Handles daily check-in logic: streak tracking, reward calculation,
 * and coin distribution via Vault (when available).
 */
public class CheckInManager {

    private final LetsLearnEnglish plugin;
    private final ConcurrentHashMap<UUID, Object> playerLocks = new ConcurrentHashMap<>();

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
        Object lock = playerLocks.computeIfAbsent(player.getUniqueId(), ignored -> new Object());
        synchronized (lock) {
            return checkInLocked(player);
        }
    }

    /**
     * R-76：移除某玩家的签到互斥锁条目（由 quit 编排点 PlayerJoinListener 调用）。
     *
     * <p>原先 playerLocks 只增不删：一次性玩家（退服不再来）的 UUID → Object
     * 条目永久残留。锁只在 checkIn 的 synchronized 临界区里有意义，玩家离线后
     * 无保留价值。安全前提与其它 quit 级缓存一致：checkIn 的调用方（/le checkin
     * 命令、GUI 点击）均在 Bukkit 主线程，移除不会与持锁线程并发。
     */
    public void unloadPlayer(UUID playerId) {
        playerLocks.remove(playerId);
    }

    private CheckInResult checkInLocked(Player player) {
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

        // Claim in the database first. This makes a crash unable to produce a second claim.
        if (!plugin.getDatabaseManager().updateCheckIn(player.getUniqueId(), today, newStreak)) {
            return new CheckInResult(false, data == null ? 0 : data.getStreakCount(), 0, false);
        }

        // Vault is external to the database, so a failed payment cannot roll back the claim.
        boolean economyDisabled = isMoneyDisabledByConfig();
        GiveCoinsOutcome payout = giveCoins(player, coinsEarned);
        // R-34：coins 记账与今日金币记账已在 RewardEngine.depositCappedReward 内完成，
        // 此处不再单独 recordCoinsEarned（否则双计）。

        // R-44：money.enabled=false 时 coinsEarned 计 0，但 success 保持 true
        // （签到权已到账），调用方凭 economyDisabled 给"经济未启用"提示。
        boolean paid = payout == GiveCoinsOutcome.PAID;
        return new CheckInResult(true, newStreak, paid ? coinsEarned : 0,
                streakBroken, economyDisabled && !paid);
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
     * R-44: outcome of a coin payout attempt. Distinguishes "economy disabled
     * by config" from other failures so the caller can hint correctly.
     */
    private enum GiveCoinsOutcome {
        PAID,
        ECONOMY_DISABLED,
        FAILED
    }

    /**
     * R-44: true only when money.enabled=false (config switch off).
     * Vault-missing and deposit-failure do NOT count — they keep the old
     * silent behavior (R-44 只管开关语义，不管 Vault 缺失）。
     */
    private boolean isMoneyDisabledByConfig() {
        return !plugin.getConfigManager().getMainConfig()
                .getBoolean("progress-system.rewards.types.money.enabled", false);
    }

    /**
     * Give coins to the player via Vault economy.
     * Falls back gracefully if Vault is not available.
     *
     * @param player the player
     * @param amount the amount of coins to give
     * @return payout outcome (paid / disabled-by-config / other failure)
     */
    private GiveCoinsOutcome giveCoins(Player player, double amount) {
        if (isMoneyDisabledByConfig()) {
            return GiveCoinsOutcome.ECONOMY_DISABLED;
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) {
            return GiveCoinsOutcome.FAILED;
        }

        // R-34：改走 RewardEngine 门面，纳入 money.daily-cap 并记今日金币账
        // （签到此前 eco.depositPlayer 直发，绕过日限与 todayCoinsCache 记账）。
        double paid = plugin.getProgressManager().getRewardEngine().depositCappedReward(player, amount);
        return paid > 0 ? GiveCoinsOutcome.PAID : GiveCoinsOutcome.FAILED;
    }

    /**
     * Result of a check-in attempt.
     *
     * @param success whether the check-in claim landed (DB marked claimed)
     * @param streak current consecutive streak
     * @param coinsEarned coins actually paid to the player (0 if unpaid)
     * @param streakBroken whether the previous streak was reset by a gap
     * @param economyDisabled R-44: true when the claim landed but no coins were
     *                        paid ONLY because money.enabled=false. Caller uses
     *                        this to show the "economy disabled" hint instead of
     *                        a 0-coin "success" line.
     */
    public record CheckInResult(boolean success, int streak, double coinsEarned,
                                boolean streakBroken, boolean economyDisabled) {

        /** Back-compat ctor for paths that never disabled the economy. */
        public CheckInResult(boolean success, int streak, double coinsEarned,
                             boolean streakBroken) {
            this(success, streak, coinsEarned, streakBroken, false);
        }
    }
}
