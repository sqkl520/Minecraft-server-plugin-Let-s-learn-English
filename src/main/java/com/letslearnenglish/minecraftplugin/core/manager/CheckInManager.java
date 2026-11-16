package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.data.CheckInData;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
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
        // EH-03：读库失败 ≠ 从未签到。原实现两者都是 null，DB 抖一次就把真实 streak 覆盖成 1 且报 success。
        // 现读失败即拒绝本次签到：不写库、不覆盖 streak、如实报失败。null 同按读失败（fail-closed）。
        DatabaseManager.CheckInLoad loaded =
                plugin.getDatabaseManager().loadCheckInData(player.getUniqueId());
        if (loaded == null || loaded.readFailed()) {
            return new CheckInResult(false, 0, 0, false, false, true);
        }
        CheckInData data = loaded.data();

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

        // 抢占签到权：条件写入 + affected rows 判定谁赢（多服单发保护）。
        // 原实现是裸 UPSERT，恒 return true —— 两台服共享 MySQL 时读与写之间没有 fencing，
        // 两边都判"今天还没签"→ 两边都写 → 两边都 giveCoins，同一玩家同一天领双份真钱。
        // 现在由 DatabaseManager.claimCheckIn 的三态收口，下面按结局分别处置。
        DatabaseManager.CheckInClaim claim =
                plugin.getDatabaseManager().claimCheckIn(player.getUniqueId(), today, newStreak);
        if (claim == DatabaseManager.CheckInClaim.FAILED) {
            // R11-M4：存储失败 ≠ 今日已签到（两者都是 success=false）。显式标记
            // storageFailed，调用方给"请稍后再试"提示，而不是伪装成幂等拒绝
            // （否则玩家不再重试、当日 streak 断）。
            return new CheckInResult(false, data == null ? 0 : data.getStreakCount(), 0,
                    false, false, true);
        }
        if (claim == DatabaseManager.CheckInClaim.TAKEN) {
            // 多服竞态：今天已被他服（或本服并发）写进去了。这是<b>正常的幂等拒绝</b>，
            // 不是故障 —— 与 storageFailed 必须分开（否则玩家会对着一次成功的跨服签到
            // 反复重试）。它同样是一分钱都不能发的分支：钱只跟着 CLAIMED 走。
            //
            // streak 取真源而不是本地快照：data 是本次调用最初读的，他服写入之后它就陈旧了
            // （首次签到场景下它甚至是 null）。只在竞态这条罕见路径上多读一次库，读失败
            // 时退回本地快照 —— 此处仅为展示，不值得让一次读抖动把"已签到"变成"请重试"。
            DatabaseManager.CheckInLoad latest =
                    plugin.getDatabaseManager().loadCheckInData(player.getUniqueId());
            int streak = latest != null && !latest.readFailed() && latest.data() != null
                    ? latest.data().getStreakCount()
                    : (data == null ? 0 : data.getStreakCount());
            return new CheckInResult(false, streak, 0, false);
        }

        // Vault is external to the database, so a failed payment cannot roll back the claim.
        boolean economyDisabled = isMoneyDisabledByConfig();
        // R-16：giveCoins 过去只回 GiveCoinsOutcome（付没付），实发额被丢在
        // depositCappedReward 里 —— 于是 checkInLocked 只能把**面值** coinsEarned
        // 当成"到账金额"写进 CheckInResult，调用方（LearnCommand 的 checkin.success）
        // 照着播报。日限截断时（默认 cap 500、玩家今天已得 480）实发 0.20 却报
        // 「获得 100 金币」。现改为带出实发额。
        RewardEngine.CappedPayout payout = giveCoins(player, coinsEarned);
        // R-34：coins 记账与今日金币记账已在 RewardEngine.depositCappedReward 内完成，
        // 此处不再单独 recordCoinsEarned（否则双计）。

        // R-44：money.enabled=false 时 coinsEarned 计 0，但 success 保持 true
        // （签到权已到账），调用方凭 economyDisabled 给"经济未启用"提示。
        boolean paid = payout.paid() > 0;
        // R-16 裁决（签到）：<b>照常收盘，只如实报实发额</b>——签到没有"保留 pending"这个选项，
        // 因为 claim（streak）在发钱之前就已经落库了（见上方 claimCheckIn），今天再签只会
        // 得到 success=false 的幂等拒绝，截断掉的那部分没有任何重试窗口。
        // 而 streak 本身是一次真实的业务成功（连续天数确实推进了），不能因为币少了就报失败
        // —— 那会让玩家误以为签到没生效。所以第三参改为<b>实发额</b>，
        // 第 7 参带出面值面（仅当真的付了才有意义），由 CheckInResult.rewardCapped() 判是否
        // 被日限截断，调用方据此补一句 daily.reward-capped。
        // R-16 注释修正：此处过去写作 checkin.reward-capped —— <b>该键不存在</b>
        // （zh.yml 与 en.yml 的 checkin: 段落里都没有），按它照写只会得到
        // 缺键占位符 [?checkin.reward-capped]。真实键是 <b>daily.reward-capped</b>
        // （zh.yml:433 / en.yml:425，挂在 daily: 段下，占位符 {paid}/{nominal}），
        // LearnCommand 的第 857 行已在消费它，DailyChallengeManager:1156 与
        // MiscGUIListener:547 也共用同一个键 —— 全仓只有这一个 reward-capped 键。
        // 纯注释修正，代码零改动。
        return new CheckInResult(true, newStreak, paid ? payout.paid() : 0,
                streakBroken, economyDisabled && !paid, false,
                paid ? payout.nominal() : 0);
    }

    // R-16：原 private enum GiveCoinsOutcome{PAID, ECONOMY_DISABLED, FAILED} 已删除。
    // 它的三态对调用方没有增量信息：ECONOMY_DISABLED 与 FAILED 在 checkInLocked 里
    // 早已各有独立判定（isMoneyDisabledByConfig() 在发币前求值 → economyDisabled；
    // plugin.getEconomy()==null 在同一处 judge），PAID 则等价于 CappedPayout.paid()>0。
    // 留着它只会让人以为"这里有第四个状态要维护"，而它恰恰是实发额被丢掉的那一层抽象。

    /**
     * R-44: true only when money.enabled=false (config switch off).
     * Vault-missing and deposit-failure do NOT count — they keep the old
     * silent behavior (R-44 只管开关语义，不管 Vault 缺失）。
     */
    private boolean isMoneyDisabledByConfig() {
        // Wave 1 fan-in（S-H 上报收口）：改走 RewardEngine 公开并集
        // （isRewardsEnabled() && money.enabled）——原先只读 money 键，关奖励伞时漏判。
        return !plugin.getProgressManager().getRewardEngine().isMoneyEnabled();
    }

    /**
     * Give coins to the player via Vault economy.
     * Falls back gracefully if Vault is not available.
     *
     * <p><b>R-16：返回值由三态枚举换成 {@link RewardEngine.CappedPayout}。</b>枚举只能表达
     * 「付了 / 经济关 / 失败」三态，
     * 把「付了多少」丢在 {@code depositCappedReward} 里 —— 于是调用方只能拿面值当实发额
     * 播报（本缺陷的第三个案发点）。换成记录后 {@code paid() > 0} 仍是「钱到了」，
     * {@code paidText()/nominalText()} 提供两段可渲染金额，{@code capped()} 说明是否被
     * 日限截断；同时它天然把「经济关」与「失败」都折叠成 {@code paid()==0}
     * —— 但这两态在 {@link #checkInLocked} 里<b>早就各有一道自己的门</b>：
     * {@code isMoneyDisabledByConfig()} 在调本方法<b>之前</b>就已求值
     * （{@code economyDisabled}），Vault 缺失则由 {@code plugin.getEconomy() == null}
     * 在同一处判掉。故枚举三态所承载的信息没有丢，只是不再需要一条返回值通道。
     *
     * @param player the player
     * @param amount the nominal amount of coins to give
     * @return 面值 + 实发额（{@code paid() > 0} 才表示钱真的到了）
     */
    private RewardEngine.CappedPayout giveCoins(Player player, double amount) {
        if (isMoneyDisabledByConfig()) {
            return new RewardEngine.CappedPayout(amount, 0);
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) {
            return new RewardEngine.CappedPayout(amount, 0);
        }

        // R-34：改走 RewardEngine 门面，纳入 money.daily-cap 并记今日金币账
        // （签到此前 eco.depositPlayer 直发，绕过日限与 todayCoinsCache 记账）。
        double paid = plugin.getProgressManager().getRewardEngine().depositCappedReward(player, amount);
        return new RewardEngine.CappedPayout(amount, paid);
    }

    /**
     * Result of a check-in attempt.
     *
     * @param success whether the check-in claim landed (DB marked claimed)
     * @param streak current consecutive streak
     * @param coinsEarned coins <b>actually paid</b> to the player (0 if unpaid).
     *                    R-16: 这里曾是<b>面值</b>（{@code STREAK_REWARDS[档位]}）——
     *                    {@code depositCappedReward} 的返回值被 {@code paid > 0} 折叠成
     *                    布尔，实发额整条丢掉，于是日限截断时玩家被告知"获得 100 金币"
     *                    而实际只到 0.20。现在它与 Vault 实收额一分不差（既有单测
     *                    {@code CheckInManagerPayoutTest} 正是在断言这一点）。
     * @param streakBroken whether the previous streak was reset by a gap
     * @param economyDisabled R-44: true when the claim landed but no coins were
     *                        paid ONLY because money.enabled=false. Caller uses
     *                        this to show the "economy disabled" hint instead of
     *                        a 0-coin "success" line.
     * @param storageFailed R11-M4: true when the DB write of the claim failed —
     *                      NOT the same as "already checked in today" (both are
     *                      success=false); caller must hint "try again" instead
     *                      of faking an idempotent rejection.
     * @param nominalCoins R-16: 本次签到档位的<b>面值</b>（{@code STREAK_REWARDS[档位]}），
     *                     与 {@code coinsEarned} 同源同时求值。失败/未发币路径计 0。
     *                     只用于判断"是否被日限截断"与渲染"应发 X"，<b>不得</b>当作
     *                     到账金额使用 —— 那是 {@code coinsEarned} 的职责。
     */
    public record CheckInResult(boolean success, int streak, double coinsEarned,
                                boolean streakBroken, boolean economyDisabled,
                                boolean storageFailed, double nominalCoins) {

        /**
         * R-16：本次签到是否「<b>发了，但比应发少</b>」= 被 {@code money.daily-cap} 截断。
         *
         * <p>判据本身<b>不在这里重写</b>，而是委托给
         * {@link RewardEngine.CappedPayout#capped()} —— 三个领奖点必须共用同一份算术，
         * 否则「按分对齐」「paid==0 不算截断」这些边界会各写各的、慢慢漂移。
         */
        public boolean rewardCapped() {
            return new RewardEngine.CappedPayout(nominalCoins, coinsEarned).capped();
        }

        /** Back-compat ctor for paths that never disabled the economy. */
        public CheckInResult(boolean success, int streak, double coinsEarned,
                             boolean streakBroken) {
            this(success, streak, coinsEarned, streakBroken, false, false, 0);
        }

        /** R44 5-arg ctor (existing call sites keep compiling). */
        public CheckInResult(boolean success, int streak, double coinsEarned,
                             boolean streakBroken, boolean economyDisabled) {
            this(success, streak, coinsEarned, streakBroken, economyDisabled, false, 0);
        }

        /** R11-M4 6-arg ctor（失败/拒绝路径照旧：面值无意义，计 0）。 */
        public CheckInResult(boolean success, int streak, double coinsEarned,
                             boolean streakBroken, boolean economyDisabled,
                             boolean storageFailed) {
            this(success, streak, coinsEarned, streakBroken, economyDisabled, storageFailed, 0);
        }
    }
}
