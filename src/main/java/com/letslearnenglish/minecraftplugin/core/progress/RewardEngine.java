package com.letslearnenglish.minecraftplugin.core.progress;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * Phase 1 止血 H2 比例门：手动停止会话时按答题比例结算。
     * - 已答题（含超时）≤ {@value #STOPPED_MIN_ANSWERS_FOR_REWARD}：零奖励
     * - 比例 ≥ {@value #STOPPED_FULL_REWARD_RATIO}：全额
     * - 中间段：按 answered/total 比例折算（向下取整到分）
     */
    public static final int STOPPED_MIN_ANSWERS_FOR_REWARD = 1;
    public static final double STOPPED_FULL_REWARD_RATIO = 0.5;

    private final LetsLearnEnglish plugin;

    /**
     * C3 金币门记账：每玩家当日累计发放金币（单位"分"，避免浮点累加漂移）。
     * 条目自带日期，每次读写先对日期，跨天自动清零重计。
     * 注意：此缓存不随 PlayerDataStore / 成就缓存自动清空，
     * 须由 HotReloadManager.reloadAll 显式调用 {@link #resetDailyCoinsCache()}。
     */
    private final Map<UUID, DailyCoins> todayCoinsCache = new ConcurrentHashMap<>();

    /** 当日金币累计条目：日期 + 累计分。 */
    private static final class DailyCoins {
        volatile LocalDate date;
        final AtomicLong cents = new AtomicLong(0);
    }

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
     * 发币前置检查（Phase 1 止血 C3）：两道日限门统一收口于此。
     * <ol>
     *   <li>答题数门：{@code word-system.daily-limit}（默认 50，0/负数=不限）。计数由
     *       {@code WordLearningManager.submitAnswer/handleTimeout} 在判分落定（recordCorrect /
     *       recordIncorrect）后立刻经流 E 的 {@code PlayerDataStore.incrementTodayAnswerCount}
     *       +1（含对错与超时，按自然日清零）；门用"计数值 &gt; daily-limit"拦截，使
     *       第 50 题（含其所在会话完成奖）仍正常发币，第 51 题起拦截。
     *       达上限后继续答题仍允许，但不再发金币（防刷不防学）。</li>
     *   <li>金币门：{@code money.daily-cap}（默认 500/人/天，0/负数=不限）。以今日
     *       累计发放金币为准（本类 {@code todayCoinsCache} 记账，跨天清零、reload 由流 E 清空）；
     *       大额单笔超出剩余额度时按"部分发放到 cap"处理，绝不超发。</li>
     * </ol>
     * 任一门拦截即发送友好提示并返回 false。复习扣钱（penalty）不受影响。
     *
     * @param player the player about to be paid
     * @return true=允许发币，false=已被日限拦截（已提示玩家）
     */
    private boolean checkDailyCaps(Player player) {
        UUID id = player.getUniqueId();

        int dailyLimit = plugin.getConfigManager().getMainConfig()
                .getInt("word-system.daily-limit", 50);
        if (dailyLimit > 0
                && plugin.getPlayerDataStore().getTodayAnswerCount(id) > dailyLimit) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.daily-limit-no-reward",
                    "limit", String.valueOf(dailyLimit));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
            return false;
        }

        int dailyCap = plugin.getConfigManager().getMainConfig()
                .getInt("money.daily-cap", 500);
        if (dailyCap > 0 && getTodayCoinsEarnedCents(id) >= (long) dailyCap * 100) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.daily-cap-reached",
                    "cap", String.valueOf(dailyCap),
                    "earned", String.valueOf(getTodayCoinsEarned(id)));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
            return false;
        }

        return true;
    }

    /**
     * 每日金币发放记账工具（C3，单位统一为"分"long，避免浮点累加漂移）。
     * 读写前都对日期：跨天则清零重计。
     */

    /** 取玩家今日累计发放金币（分）。 */
    private long getTodayCoinsEarnedCents(UUID playerId) {
        LocalDate today = LocalDate.now();
        DailyCoins entry = todayCoinsCache.get(playerId);
        if (entry == null) return 0;
        synchronized (entry) {
            if (!today.equals(entry.date)) {
                entry.date = today;
                entry.cents.set(0);
                return 0;
            }
            return entry.cents.get();
        }
    }

    /** 取玩家今日累计发放金币（元，供 i18n 展示用，保留两位小数）。 */
    private double getTodayCoinsEarned(UUID playerId) {
        return getTodayCoinsEarnedCents(playerId) / 100.0;
    }

    /** 累加玩家今日发放金币（amount 单位元，入账时四舍五入到分）。 */
    private void addTodayCoinsEarned(UUID playerId, double amount) {
        long cents = Math.round(amount * 100.0);
        if (cents <= 0) return;
        LocalDate today = LocalDate.now();
        DailyCoins entry = todayCoinsCache.computeIfAbsent(playerId, k -> {
            DailyCoins e = new DailyCoins();
            e.date = today;
            return e;
        });
        synchronized (entry) {
            if (!today.equals(entry.date)) {
                entry.date = today;
                entry.cents.set(0);
            }
            entry.cents.addAndGet(cents);
        }
    }

    /**
     * 供流 E 在热重载时调用：清空每日金币累计缓存。
     * 注意 RewardEngine 实例由 ProgressManagerImpl 懒加载持有，reload 不重建，
     * 因此 reload 时必须显式清空，否则今日累计会跨越 reload 继续累加。
     */
    public void resetDailyCoinsCache() {
        todayCoinsCache.clear();
    }

    /**
     * 金币门"部分发放"：给定拟发金额，返回在不突破 daily-cap 前提下实际可发金额。
     * 若已无剩余额度，发送 cap 提示并返回 0。
     *
     * @param player the player about to be paid
     * @param amount the intended payout
     * @return actual payout in whole cents as double (0 = blocked, caller must not pay)
     */
    private double clampToDailyCap(Player player, double amount) {
        int dailyCap = plugin.getConfigManager().getMainConfig()
                .getInt("money.daily-cap", 500);
        if (dailyCap <= 0) return amount;
        long capCents = (long) dailyCap * 100;
        long earnedCents = getTodayCoinsEarnedCents(player.getUniqueId());
        long remaining = capCents - earnedCents;
        if (remaining <= 0) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.daily-cap-reached",
                    "cap", String.valueOf(dailyCap),
                    "earned", String.valueOf(getTodayCoinsEarned(player.getUniqueId())));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
            return 0;
        }
        long amountCents = Math.round(amount * 100.0);
        long payCents = Math.min(amountCents, remaining);
        if (payCents < amountCents) {
            // 部分发放：告知玩家今日额度只剩这么多
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.daily-cap-reached",
                    "cap", String.valueOf(dailyCap),
                    "earned", String.valueOf(getTodayCoinsEarned(player.getUniqueId())));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        }
        return payCents / 100.0;
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
        if (!checkDailyCaps(player)) return;

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

        amount = clampToDailyCap(player, amount);
        if (amount <= 0) return;

        EconomyResponse response = eco.depositPlayer(player, amount);
        if (response.transactionSuccess()) {
            plugin.getDatabaseManager().recordCoinsEarned(player.getUniqueId(), amount);
            addTodayCoinsEarned(player.getUniqueId(), amount);
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
        if (!checkDailyCaps(player)) return;

        double sessionBonus = plugin.getConfigManager().getMainConfig()
                .getDouble("progress-system.rewards.types.money.session-complete-bonus", 50.0);
        double maxComboBonus = plugin.getConfigManager().getMainConfig()
                .getDouble("progress-system.rewards.types.money.max-combo-bonus", 25.0);

        double totalAmount = sessionBonus;
        if (maxComboReached >= 5) {
            totalAmount += maxComboBonus;
        }

        totalAmount = clampToDailyCap(player, totalAmount);
        if (totalAmount <= 0) return;

        EconomyResponse response = eco.depositPlayer(player, totalAmount);
        if (response.transactionSuccess()) {
            plugin.getDatabaseManager().recordCoinsEarned(player.getUniqueId(), totalAmount);
            addTodayCoinsEarned(player.getUniqueId(), totalAmount);
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
        if (!checkDailyCaps(player)) return;

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

        amount = clampToDailyCap(player, amount);
        if (amount <= 0) return;

        EconomyResponse response = eco.depositPlayer(player, amount);
        if (response.transactionSuccess()) {
            plugin.getDatabaseManager().recordCoinsEarned(player.getUniqueId(), amount);
            addTodayCoinsEarned(player.getUniqueId(), amount);
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
            plugin.getDatabaseManager().recordCoinsSpent(player.getUniqueId(), penaltyAmount);
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
     * 手动停止会话的会话奖励（Phase 1 止血 H2 比例门）。
     *
     * <p>规则（常量见类头 {@link #STOPPED_MIN_ANSWERS_FOR_REWARD} /
     * {@link #STOPPED_FULL_REWARD_RATIO}）：已答题数（含答对、答错与超时，均已推进
     * currentIndex）≤1 时零奖励；比例 ≥50% 时调用与正常走完完全相同的全额路径
     * （{@link #giveSessionCompleteMoneyReward}，含 maxComboBonus 规则与日限门）；
     * 中间段按 answered/total 比例折算基础会话奖励并同样经过日限门。</p>
     *
     * @param player           the player to reward
     * @param maxComboReached  the highest consecutive correct count in the session
     * @param answered         questions answered so far (correct + incorrect + timeouts)
     * @param total            total questions in this session
     */
    public void giveStoppedSessionMoneyReward(Player player, int maxComboReached,
                                               int answered, int total) {
        Economy eco = getEconomy();
        if (eco == null || !isMoneyEnabled()) return;
        if (total <= 0) return;

        if (answered <= STOPPED_MIN_ANSWERS_FOR_REWARD) {
            // 零奖励：告知玩家而非静默（i18n）
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "word-learning-gui.stopped-no-reward",
                    "answered", String.valueOf(Math.max(answered, 0)),
                    "total", String.valueOf(total));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
            return;
        }

        double ratio = (double) answered / total;
        if (ratio >= STOPPED_FULL_REWARD_RATIO) {
            // 全额：与正常走完完全同一条发放路径
            giveSessionCompleteMoneyReward(player, maxComboReached);
            return;
        }

        // 中间段：按比例折算，只折基础会话奖励（连击加成是"走完才有"的荣耀金，
        // 提前停止不享受 — 走完的路径不受影响，因为完成分支走的是上面的全额门）
        if (!checkDailyCaps(player)) return;

        double sessionBonus = plugin.getConfigManager().getMainConfig()
                .getDouble("progress-system.rewards.types.money.session-complete-bonus", 50.0);
        double partial = Math.floor(sessionBonus * ratio * 100.0) / 100.0;
        partial = clampToDailyCap(player, partial);
        if (partial <= 0) return;

        EconomyResponse response = eco.depositPlayer(player, partial);
        if (response.transactionSuccess()) {
            plugin.getDatabaseManager().recordCoinsEarned(player.getUniqueId(), partial);
            addTodayCoinsEarned(player.getUniqueId(), partial);
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "word-learning-gui.stopped-partial-reward",
                    "answered", String.valueOf(answered),
                    "total", String.valueOf(total),
                    "amount", eco.format(partial));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        } else {
            plugin.getLogger().warning("Failed to deposit stopped-session bonus for "
                    + player.getName() + ": " + response.errorMessage);
        }
    }
}
