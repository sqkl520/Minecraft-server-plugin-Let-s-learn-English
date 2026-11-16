package com.letslearnenglish.minecraftplugin.core.progress;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
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

    /**
     * 单笔发币的<b>绝对天花板</b>（单位：分，= 1,000,000 金币/单笔）。
     *
     * <p><b>为什么必须有它</b>：{@link #clampToDailyCap} 的唯一上限来自
     * {@code money.daily-cap} 的剩余额度，而 {@code config.yml} 明写「0 = 不限制」。
     * 于是「运营者把某笔奖励金额手滑成 {@code 1e18}」+「把 {@code money.daily-cap} 设成 0」
     * 两条一叠加，{@code double} 原样进 {@code Economy#depositPlayer} —— Vault 对
     * {@code double} 不做任何范围校验，多数经济实现背后是 {@code double} 列：
     * 要么静默饱和，要么把一个天文数字存进玩家余额，而配置表里那个值看起来"只是个数字"。
     *
     * <p><b>为什么取 1,000,000 金币</b>：与
     * {@code SeasonManager.MAX_REWARD_CENTS}（R-16 修的赛季奖励天花板）<b>同值</b>，
     * 那边的论证同样适用：看默认值量级 —— 答题 10.00、会话 50+25、签到/挑战几十到几百，
     * 最大的一档与 1,000,000 差了<b>四到五个数量级</b>。判据是：正当配置碰不到它，
     * 配置手滑一定碰得到，同时它远低于 {@code long} 分制接近上限、
     * 以及 {@code double} 余额开始失真的区域。
     *
     * <p><b>为什么在 {@code RewardEngine} 里再定义一份而不是复用那个常量</b>：
     * {@code SeasonManager} 的常量是包级私有（{@code core.season}），跨包不可见；
     * 而把数字口头沿用而不落成常量，正是"两处天花板慢慢漂移"的起点。两处必须是同一个值 ——
     * 改动任一处都要同步另一处（各自的 javadoc 互相点名）。
     */
    public static final long MAX_SINGLE_PAYOUT_CENTS = 100_000_000L;

    /**
     * 天花板告警里「来源」的默认标签：{@code depositCappedReward} 通用发币门面
     * （签到 / 每日挑战 / 成就等"固定额"奖励共用它，配置来源各不相同，
     * 故只能报门面名而不是某个具体配置键）。
     */
    private static final String DEFAULT_PAYOUT_SOURCE =
            "depositCappedReward (check-in / daily-challenge / achievement reward amounts)";

    /** 天花板告警来源：答对一题的奖励（{@code progress-system.rewards.types.money.*} / 词的 reward）。 */
    private static final String CORRECT_ANSWER_SOURCE =
            "progress-system.rewards.types.money.base-amount × combo (or word reward)";

    /** 天花板告警来源：会话完成奖励。 */
    private static final String SESSION_BONUS_SOURCE =
            "progress-system.rewards.types.money.session-complete-bonus / max-combo-bonus";

    /** 天花板告警来源：复习答对的奖励。 */
    private static final String REVIEW_CORRECT_SOURCE =
            "progress-system.rewards.types.money.review-multiplier × base-amount (or word reward)";

    /** 天花板告警来源：提前停止会话的比例结算。 */
    private static final String STOPPED_SESSION_SOURCE =
            "progress-system.rewards.types.money.session-complete-bonus (prorated by answered/total)";

    private final LetsLearnEnglish plugin;

    /**
     * 已就「单笔发币金额超天花板被钳位」告过警的<b>来源</b>标签集合。
     *
     * <p><b>为什么是一次的</b>：天花板命中意味着配置写错了，钳位本身是正确的兜底，
     * 但发币走的是<b>答题/会话/签到</b>这些高频热路径 —— 逐次打日志会把一份配错的配置
     * 刷成满屏，反而埋掉别的告警。取一次性而不是完全静默的理由与
     * {@code SeasonManager.rewardClampWarned} 完全一致：静默会让「我配的钱怎么没发出去」
     * 无从诊断。粒度是<b>来源</b>而不是全局一个布尔，这样四个入口各自的配置错都能各报一次。
     */
    private final java.util.Set<String> payoutCeilingWarned =
            ConcurrentHashMap.newKeySet();

    /**
     * C3 金币门记账：每玩家当日累计发放金币（单位"分"，避免浮点累加漂移）。
     * 条目自带日期，每次读写先对日期，跨天自动清零重计。
     * 注意：此缓存不随 PlayerDataStore / 成就缓存自动清空，
     * 须由 HotReloadManager.reloadAll 显式调用 {@link #resetDailyCoinsCache()}。
     *
     * <p><b>F-09-02（round2）</b>：本 Map 不再是唯一权威 —— 它是 DB 表
     * {@code daily_coin_counters} 前面的一层写穿缓存。语义与
     * {@code PlayerDataStore.todayAiRoundCache}（R2-M2 三层设计）同构：
     * <ol>
     *   <li>日期非今日（或从未 seed）→ 先<b>同步</b>回读 DB 真源（读失败按 0 降级）；</li>
     *   <li>内存累加立即生效（返回值/判据语义不变）；</li>
     *   <li>落库为增量 UPSERT（{@code cents = cents + ?}），跨进程/跨日都收敛。</li>
     * </ol>
     * 因此 {@code /lea reload} 或重启后清零不再是绕过路径 —— 下一次读会重新
     * seed 出今日真实累计（原实现 reload 后从 0 重计 = 日限可反复突破）。
     *
     * <p><b>Round 18：三层里「读」与「写」的线程归属刻意不同</b>
     * （{@link #readDailyCoinsFromDb} 同步、{@link #addDailyCoinsToDb} 异步），
     * 判据是<b>失败方向</b>而不是「哪个更快」：
     * <ul>
     *   <li><b>读必须同步</b>。它是 {@link #clampToDailyCap} 发钱<b>之前</b>的判据输入。
     *       一旦异步化或加短超时降级，判据就会在「库里的已用额度还没读回来」的窗口里
     *       把剩余额度当成满额 —— 那个方向是<b>多发钱</b>（真金白银，且不可回收）。
     *       宁可让一次 SELECT 阻塞发币，也不能让日限在这个窗口里失效。</li>
     *   <li><b>写可以异步</b>。调用点在 Vault 转账<b>之后</b>，内存已经累加完（判据以内存为准，
     *       所以<b>本进程内</b>日限一秒都不会松）。异步只影响持久化这一层：最坏 = 崩服/reload
     *       碰上在途写时漏掉一小段增量 → 重启后 seed 出的「已用额度」<b>偏小</b> → 那一档额度
     *       偏松，上限是<b>漏掉的那一笔发币额</b>（默认答题 10 金币量级），且不会把已经发出去的
     *       钱变成「当天可以再发一次」的循环窗口。这与
     *       {@code PlayerDataStore.incrementTodayAnswerCount}（Round 17）完全同构，
     *       是仓库里已有的既定形态，不是本类新发明的口径。</li>
     * </ul>
     *
     * <p><b>残留窗口（已知且刻意接受，与 {@code incrementTodayAnswerCount} 同款）</b>：
     * ① 在途写尚未落库时 {@code /lea reload} 清空了本 Map → 下一次 seed 读到的是「少一段」
     * 的值；② 关服窗口调度器已取消任务 → 最后一次发币的增量丢失；
     * ③ {@code /lea reset} 的删行与在途增量写重叠 → 可能留一条残行（残行 = 日限偏高 = 少发，
     * 方向安全）。三者都由下一次 seed / 自然日边界自动收敛，且代价都以「一次发币额」为界。
     *
     * <p><b>④ 主线程并不是「再也不碰 DB」</b>（如实记录，别把这个改动读过头）：读仍可能
     * 排在一次<b>在途异步写</b>后面等锁（两边共用 {@code connectionLock}）。不过：(a) 读只在
     * cache miss / 跨日发生，而同一笔发币的「读」在时间上<b>早于</b>它自己的「写」被派发，
     * 所以不会自己堵自己；(b) 每次发币现在只产生<b>一次</b>异步写，读最多等它一条
     * 单行 UPSERT。改动前的形态是「每笔发币<b>无条件</b>在主线程排队等一次写」，因此这里
     * 是<b>从"每笔必等"降到"仅 miss 时可能等一次"</b>，而不是零等待。要真正消除主线程
     * JDBC，得把日限判定整体下推到库侧（单条条件 UPDATE），那是另一个量级的改造。
     */
    private final Map<UUID, DailyCoins> todayCoinsCache = new ConcurrentHashMap<>();

    /**
     * R-44 fan-in：{@code progress.economy-disabled} 的 per-player 每自然日去重。
     * money.enabled=false 但 Vault 存在时，发币入口每天只提示一次，避免每答对一题刷屏。
     * 条目随自然日用 {@link LocalDate#equals} 判新鲜，跨天自动失效（无需清理）；
     * F1-24：quit 时经 {@link #unloadPlayer} 移除，防一次性玩家的 UUID 条目永久残留。
     */
    private final Map<UUID, LocalDate> economyDisabledNotified = new ConcurrentHashMap<>();

    /** 当日金币累计条目：日期 + 累计分。 */
    private static final class DailyCoins {
        volatile LocalDate date;
        final AtomicLong cents = new AtomicLong(0);
    }

    /**
     * F-09-02：{@code daily_coin_counters} 的建表闸。
     * 表由本类懒建 —— 写进 {@code DatabaseManager.createTables()} / {@code schema.sql}
     * 需越权（已记 {@code residual_risks: PUBLIC_API_CHANGE}）。结构与
     * {@code ai_round_counters} 同构：{@code (player_uuid, coin_date)} 唯一，
     * {@code cents} 为当日累计分。DDL 失败复位闸以便重试，退化回纯内存行为。
     */
    private final AtomicBoolean dailyCoinsTableReady = new AtomicBoolean(false);

    private void ensureDailyCoinsTable() {
        if (!dailyCoinsTableReady.compareAndSet(false, true)) {
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        Boolean ok = db == null ? null : db.withConnection(conn -> {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + db.getTablePrefix()
                        + "daily_coin_counters (player_uuid VARCHAR(36) NOT NULL,"
                        + " coin_date DATE NOT NULL, cents BIGINT NOT NULL DEFAULT 0,"
                        + " UNIQUE(player_uuid, coin_date))");
            }
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(ok)) {
            dailyCoinsTableReady.set(false); // 未初始化/失败：允许下次重试（语句幂等）
        }
    }

    /**
     * F-09-02：回读 DB 真源（分）。读失败返回 -1，调用方按 0 降级。
     *
     * <p><b>Round 18：本方法刻意保持<b>同步</b>，不要把它挪到异步线程。</b>
     * 它是 {@link #clampToDailyCap} 发钱<b>之前</b>拿剩余额度的判据输入；异步化（或加超时
     * 后按 0 降级）会在「库里已用额度还没读回来」的窗口里把额度当成满额 —— 那个失败方向是
     * <b>多发钱</b>，不可回收。相对地，写侧 {@link #persistDailyCoins} 的失败方向是
     * 「重启后漏算一小段已用额度」，安全得多，所以只有写侧被异步化。
     *
     * <p><b>为什么不给它加 {@code setQueryTimeout} 短超时</b>：本类只持一条共享连接，
     * 超时到点也仍持有 {@code connectionLock}（synchronized 无超时），只是把 30 秒等待
     * 变成 5 秒等待 + 一次 `KILL QUERY`（MySQL 驱动用<b>另一条连接</b>发，需额外权限；
     * SQLite 侧 {@code setQueryTimeout} 只是存字段的桩）。收益与风险不成比例。
     *
     * <p>可测性：本方法在主线程被调用是<b>预期</b>，不是缺陷残留 ——
     * 见 {@code RewardEngineMainThreadIoTest} 的读/写方向判定。
     */
    private long readDailyCoinsFromDb(UUID playerId, LocalDate date) {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) {
            return -1L;
        }
        return db.withConnection(conn -> {
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "SELECT cents FROM " + db.getTablePrefix()
                            + "daily_coin_counters WHERE player_uuid = ? AND coin_date = ?")) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date.toString());
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }, -1L);
    }

    /**
     * F-09-02：增量累加进 DB 真源（双后端 UPSERT，同 {@code incrementAiRound} 惯例）。
     * 写 {@code cents = cents + ?} 而非覆盖：多服同库时不会抹掉他人额度。
     * 失败只记 WARNING（最坏 = 重启后少算这一段，与修复前持平）。
     *
     * <p><b>Round 18：本方法不再从主线程直接调用</b>，唯一入口是
     * {@link #persistDailyCoins}。<b>可任意线程调用</b> —— 它只经
     * {@link DatabaseManager#withConnection} 取连接，锁由那一层持有（本类不取
     * {@code getConnectionLock()}，也不裸用 {@code getConnection()}，与 data 层外的
     * 迁移约定一致）。因此异步化不会引入「并发写同一连接」，只是把这次串行化
     * 从主线程挪到了数据库访问链上。
     */
    private void addDailyCoinsToDb(UUID playerId, LocalDate date, long deltaCents) {
        DatabaseManager db = plugin.getDatabaseManager();
        if (deltaCents <= 0 || db == null) {
            return;
        }
        String sql = "INSERT INTO " + db.getTablePrefix()
                + "daily_coin_counters (player_uuid, coin_date, cents) VALUES (?, ?, ?) "
                + (db.isSQLite()
                        ? "ON CONFLICT(player_uuid, coin_date) DO UPDATE SET cents = cents + ?"
                        : "ON DUPLICATE KEY UPDATE cents = cents + ?");
        db.withConnection(conn -> {
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date.toString());
                stmt.setLong(3, deltaCents);
                stmt.setLong(4, deltaCents);
                stmt.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Round 18：今日金币记账<b>落库的唯一派发口</b> —— 能异步就异步，不能就地同步。
     *
     * <p><b>为什么写可以异步</b>：调用点在 Vault 转账<b>之后</b>，而日限判据读的是
     * {@link #todayCoinsCache}（内存已在 {@link #addTodayCoinsEarned} 里累加完，且那一步
     * 先于本方法）。所以异步只推迟<b>持久化</b>，<b>本进程内</b>的日限一秒都不会松。
     * 失败方向是「重启/reload 后漏算一小段已用额度」，代价以一次发币额为界；
     * 反方向（异步化读）才是「多发钱」。判据详见 {@code todayCoinsCache} 的 javadoc。
     *
     * <p><b>为什么保留同步退化路径</b>（三条都不牺牲正确性）：
     * <ol>
     *   <li>无调度器（DI 未就绪 / 单测直接 new 的本类）→ 就地写，行为与改动前逐字一致；</li>
     *   <li>关服窗口 {@code runTaskAsynchronously} 抛 {@code IllegalStateException} →
     *       退回同步<b>尽力写一次</b>（比 {@code incrementTodayAnswerCount} 的「静默弃」
     *       更保守：日限账本丢一段会让重启后多发，不值得为了退出快 1ms 丢掉）；</li>
     *   <li>写失败 / 库不可用仍由 {@code withConnection} 记 WARNING 并降级，本方法不吞错。</li>
     * </ol>
     *
     * <p><b>顺序性</b>：多条增量是<b>可交换</b>的（{@code cents = cents + ?}），异步池乱序
     * 执行不影响库侧最终值；同一玩家的并发写由 {@code connectionLock} 串行化。
     */
    private void persistDailyCoins(UUID playerId, LocalDate date, long deltaCents) {
        if (deltaCents <= 0) {
            return;
        }
        if (scheduleAsync(() -> addDailyCoinsToDb(playerId, date, deltaCents))) {
            return;
        }
        addDailyCoinsToDb(playerId, date, deltaCents); // 退化：就地同步（语义同改动前）
    }

    /**
     * Round 18：整份文件里<b>唯一</b>的「丢给异步线程」出口（源码 lint 钉住这一点）。
     *
     * <p>刻意不判 {@code Bukkit.isPrimaryThread()}：落库链路本身与线程无关
     * （见 {@link #addDailyCoinsToDb}），从异步线程调用本类时再套一层异步只是白白多排一次队。
     * 这与 {@code AIChatManager.triggerAiOpening} 需要补 {@code ensureMainThreadDelivery}
     * 的情形不同 —— 那边被搬走的是<b>会调 Bukkit API</b> 的整段，这里搬走的只有 JDBC。
     *
     * @return true = 已投递到异步线程；false = 无可用调度器，调用方须就地同步执行
     */
    private boolean scheduleAsync(Runnable task) {
        if (plugin == null || task == null) {
            return false;
        }
        try {
            org.bukkit.Server server = plugin.getServer();
            if (server == null) {
                return false;
            }
            org.bukkit.scheduler.BukkitScheduler scheduler = server.getScheduler();
            if (scheduler == null) {
                return false;
            }
            scheduler.runTaskAsynchronously(plugin, task);
            return true;
        } catch (RuntimeException e) {
            // 关服/停用窗口调度器已关；也可能 mock/DI 未就绪。一律退回同步，不冒泡。
            return false;
        }
    }

    public RewardEngine(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    private Economy getEconomy() {
        return plugin.getEconomy();
    }

    /**
     * 金币总闸（单一真相收口，MC-2 / S-H）：{@code progress-system.enabled &amp;&amp;
     * progress-system.rewards.enabled &amp;&amp; progress-system.rewards.types.money.enabled}。
     * 原实现只读 {@code money.enabled} 一个键 —— 服主关"奖励伞"
     * （{@code progress-system.rewards.enabled=false}）时本类仍会发币，伞门形同虚设。
     * 现改为 AND 并集：任一门关都不发币（"都开才发"）。
     * 此前散布在 LearnCommand / DailyChallengeManager / CheckInManager / MiscGUIListener
     * 的 {@code money.enabled} 直读点，统一改走本方法（public 供各调用点收口）。
     */
    public boolean isMoneyEnabled() {
        return plugin.getConfigManager().isRewardsEnabled()
                && plugin.getConfigManager().getMainConfig()
                        .getBoolean("progress-system.rewards.types.money.enabled", false);
    }

    /**
     * R-44 fan-in：money.enabled=false 但 Vault 在时，告知玩家经济未启用（学习进度照常记录），
     * per-player 每自然日仅提示一次。四个发币入口的 {@code !isMoneyEnabled()} 早退处调用。
     * {@code eco==null}（无 Vault）不走此路径 —— 没有经济系统可言，静默即可。
     */
    private void notifyEconomyDisabledOncePerDay(Player player) {
        UUID id = player.getUniqueId();
        LocalDate today = LocalDate.now();
        // put 返回旧值；旧值即今日说明今天已提示过，直接返回（主线程调用，无竞态）
        if (today.equals(economyDisabledNotified.put(id, today))) {
            return;
        }
        String msg = plugin.getMessageUtil().getPlayerMessage(player, "progress.economy-disabled");
        if (msg != null && !msg.isEmpty()) {
            player.sendMessage(msg);
        }
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
                    "earned", formatCoins(getTodayCoinsEarned(id)));
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

    /**
     * 取玩家今日累计发放金币（分）。
     * F-09-02：内存 miss / 跨日时不再直接返回 0，而是先回读 DB 真源 seed，
     * 使 reload 与重启后日限仍然有效（读失败按 0 降级，同 getAiRoundCount 口径）。
     */
    private long getTodayCoinsEarnedCents(UUID playerId) {
        LocalDate today = LocalDate.now();
        DailyCoins entry = todayCoinsCache.get(playerId);
        if (entry == null || !today.equals(entry.date)) {
            ensureDailyCoinsTable();
            long seed = Math.max(0L, readDailyCoinsFromDb(playerId, today));
            entry = todayCoinsCache.computeIfAbsent(playerId, k -> new DailyCoins());
            synchronized (entry) {
                // 并发下他线程可能已 seed 过今天：不能拿本次（可能更陈旧的）值覆盖
                if (!today.equals(entry.date)) {
                    entry.date = today;
                    entry.cents.set(seed);
                }
            }
        }
        return entry.cents.get();
    }

    /** 取玩家今日累计发放金币（元，供 i18n 展示用，保留两位小数）。 */
    private double getTodayCoinsEarned(UUID playerId) {
        return getTodayCoinsEarnedCents(playerId) / 100.0;
    }

    /**
     * 累加玩家今日发放金币（amount 单位元，入账时四舍五入到分）。
     *
     * <p>F-09-02：内存累加 + 落库（增量 UPSERT）。
     *
     * <p><b>Round 18：落库改为异步（经 {@link #persistDailyCoins}），内存累加仍在调用线程</b>。
     * 原 javadoc 写着「这里选同步而非异步…异步反而会引入『内存已加、库未加』的窗口，
     * 正是本缺陷要消灭的那类差」—— 那句话把两件事混成一件了，实测后果不同：
     * <ul>
     *   <li>日限的<b>判据</b>读的是 {@link #todayCoinsCache}，而内存累加发生在<b>本方法
     *       返回之前</b>、且早于落库派发。所以「内存已加、库未加」的窗口里，
     *       <b>本进程</b>的日限<b>完全正常</b>（该拦的照样拦），并不存在「多发钱」窗口；</li>
     *   <li>那个窗口真正的代价只是「持久化还没到」：崩服/reload 时可能漏掉这一小段，
     *       重启后 seed 出的已用额度<b>偏小</b> → 日限偏松<b>一个发币额</b>（一次答题 10 金币
     *       量级），且严格小于「同步阻塞主线程最多 30 秒（socketTimeout）」的代价；</li>
     *   <li>更关键的是<b>读/写失败方向不对称</b>：写失败 → 日限偏松一点（可量化、有界）；
     *       读失败或异步 → 发钱<b>之前</b>拿不到已用额度 → 可能多发一整笔甚至突破 cap。
     *       所以只有写侧被异步化，{@link #readDailyCoinsFromDb} 保持同步。</li>
     * </ul>
     *
     * <p>内存累加与跨天 seed 全部留在调用线程，是为了保住返回值/判据的<b>读自己写</b>
     * 语义（调用方紧接着就可能再判一次 cap）。
     */
    private void addTodayCoinsEarned(UUID playerId, double amount) {
        long cents = Math.round(amount * 100.0);
        if (cents <= 0) return;
        LocalDate today = LocalDate.now();
        ensureDailyCoinsTable();
        DailyCoins entry = todayCoinsCache.computeIfAbsent(playerId, k -> new DailyCoins());
        boolean rollover = !today.equals(entry.date);
        // 跨天时先在锁外回读 DB（避免持 entry 监视器做阻塞 IO），再进锁重置
        long seed = rollover ? readDailyCoinsFromDb(playerId, today) : 0L;
        synchronized (entry) {
            if (!today.equals(entry.date)) {
                entry.date = today;
                entry.cents.set(seed >= 0 ? seed : 0L);
            }
            entry.cents.addAndGet(cents);
        }
        // Round 18：内存累加在调用线程（判据立即可见），落库经 persistDailyCoins 派发 ——
        // 能异步就异步，无调度器/关服窗口退回同步。见该方法与 readDailyCoinsFromDb 的 javadoc。
        persistDailyCoins(playerId, today, cents);
    }

    /**
     * R11：金币流水落库 + <b>失败可见</b>（{@code recordCoinsEarned} / {@code recordCoinsSpent}
     * 的消费侧）。
     *
     * <p><b>失败方向是「统计偏少」，不是「玩家少拿钱」</b>：本类六个调用点全部在
     * {@code EconomyResponse.transactionSuccess()} 之后 —— Vault 的钱已经到账，这里写的是
     * {@code player_data.total_coins_earned} / {@code total_coins_spent} 的 lifetime 累计，
     * 供成就（COINS_500 / COINS_5000）、PlaceholderAPI、广场分数、统计服务读取。
     * 写失败既不会把钱收回，也不会重发，只是流水少一笔。
     *
     * <p><b>因此刻意不改本类任何方法的对外返回口径</b>：{@code depositCappedReward} 的返回值
     * 语义是「实际入账金额」，调用方据 {@code paid > 0} 标记已领
     * （{@code DailyChallengeManager} / {@code MiscGUIListener} / {@code CheckInManager}）。
     * 把流水写失败折进返回值（返 0）会让调用方以为钱没发出去 → 不标记已领 →
     * 玩家重进/重答时<b>再发一次</b>，把「统计偏少」升级成「重复发钱」。
     *
     * <p><b>也不做补偿重放</b>：{@code adjustCoins} 是 {@code col = col + excluded.col} 的
     * <b>增量</b> UPSERT 且无幂等键，重放一单会在流水里多加一笔（成就可能提前解锁）。
     *
     * <p>异常口径：{@code false}（SQL 失败，data 层已打过带堆栈的 WARNING）只补一条可定位到
     * 玩家与方向的语义日志，不重复打印堆栈；{@code RuntimeException}（连接层运行期异常 /
     * DI 未就绪）带 throwable。两条路径都<b>不冒泡</b> —— 钱已经发出去了，写流水失败不该
     * 打断发币后的玩家提示与今日金币记账。
     *
     * @param player   玩家（仅用于日志；可为 null）
     * @param playerId 玩家 UUID
     * @param amount   金额（元）
     * @param earned   {@code true} = 记到 {@code total_coins_earned}；{@code false} = {@code total_coins_spent}
     */
    private void recordCoinLedger(Player player, UUID playerId, double amount, boolean earned) {
        Logger logger = loggerOrNull();
        try {
            boolean recorded = earned
                    ? plugin.getDatabaseManager().recordCoinsEarned(playerId, amount)
                    : plugin.getDatabaseManager().recordCoinsSpent(playerId, amount);
            if (!recorded) {
                logCoinLedgerFailure(logger, player, playerId, amount, earned, null);
            }
        } catch (RuntimeException e) {
            logCoinLedgerFailure(logger, player, playerId, amount, earned, e);
        }
    }

    /**
     * 日志门面兜底：{@code plugin} 或其 {@code getLogger()} 不可用时返回 {@code null}，
     * 调用方据此跳过日志（与 {@code ChatFeedbackManager.loggerOrNull} 同口径）。
     *
     * <p><b>必须先取再进 try</b>：这样「取 logger」本身抛异常时，{@code recordCoinLedger}
     * 的 catch 也兜得住，不会把一次「打不了日志」升级成「发币路径被打断」。
     */
    private Logger loggerOrNull() {
        if (plugin == null) {
            return null;
        }
        try {
            return plugin.getLogger();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 流水写失败的日志。{@code logger} 为 null 时静默跳过 —— 可观测性不该否决主逻辑。 */
    private void logCoinLedgerFailure(Logger logger, Player player, UUID playerId, double amount,
                                      boolean earned, Throwable error) {
        if (logger == null) {
            return;
        }
        String name;
        try {
            name = player == null ? String.valueOf(playerId) : player.getName();
        } catch (RuntimeException e) {
            name = String.valueOf(playerId);
        }
        if (name == null) {
            name = String.valueOf(playerId);
        }
        String message = "Failed to record coin " + (earned ? "earned" : "spent")
                + " ledger for " + name + " (" + amount + ") — money was already"
                + " transferred via Vault, so this is an under-reported lifetime stat,"
                + " not a lost payout";
        if (error != null) {
            logger.log(Level.WARNING, message, error);
        } else {
            logger.warning(message);
        }
    }

    /** WARNING 门面：{@code logger} 取不到时静默跳过 —— 可观测性不该否决主逻辑。 */
    private void warn(String message) {
        Logger logger = loggerOrNull();
        if (logger == null) {
            return;
        }
        logger.warning(message);
    }

    /**
     * 日志用玩家名：任何一步取不到都降级为占位符 —— <b>打日志本身绝不能再抛异常</b>，
     * 否则「修 NPE」会变成「换个地方 NPE」。与 {@link #logCoinLedgerFailure} 同口径。
     */
    private static String logName(Player player) {
        if (player == null) {
            return "<unknown player>";
        }
        try {
            String name = player.getName();
            if (name != null) {
                return name;
            }
            UUID id = player.getUniqueId();
            return id == null ? "<unknown player>" : id.toString();
        } catch (RuntimeException e) {
            return "<unknown player>";
        }
    }

    /**
     * 发币收口：{@code depositPlayer} 的调用与响应判定一起兜住。六个调用点全走这两个门面
     * （而不是自己起 lambda），是为了避开「金额变量被 {@code clampToDailyCap} 重新赋值后
     * 不再 effectively-final、无法进 lambda」的编译限制 —— 那样每个调用点都要多写一个
     * {@code final double x = amount} 别名，是纯粹的噪音。
     *
     * @return true = 钱确实到账；false = 未到账（已记 WARNING，调用方不得记流水/占额度/发提示）
     */
    private boolean depositSucceeded(Economy eco, Player player, double amount, String action) {
        return transactionSucceeded(() -> eco.depositPlayer(player, amount), player, action);
    }

    /** 扣款收口，语义同 {@link #depositSucceeded}。 */
    private boolean withdrawSucceeded(Economy eco, Player player, double amount, String action) {
        return transactionSucceeded(() -> eco.withdrawPlayer(player, amount), player, action);
    }

    /**
     * Vault 转账的<b>唯一收口</b>：六处 {@code depositPlayer} / {@code withdrawPlayer}
     * 统一走这里，<b>调用方既不直接调 Vault、也不直接解引用 {@code EconomyResponse}</b>。
     *
     * <p><b>为什么连"调用"本身也要兜</b>：缺陷报告只点了 {@code transactionSuccess()}
     * 的裸解引用，但第三方 provider 的故障面比这更大 —— {@code depositPlayer} /
     * {@code withdrawPlayer} 自身也可能抛 RuntimeException（自研实现里的一次空指针、
     * Essentials 旧版对非在线玩家的处理、跨服同步经济在另一线程死锁后抛出的
     * IllegalStateException 等）。只包住 {@code transactionSuccess()} 等于只在
     * "provider 已经安全返回"这一种情况下有效，而那恰恰不是出问题的那种情况。
     * 两个故障面必须一起兜，否则修完仍是「复习判定被中断」。
     *
     * <p><b>三条失败路径收敛到同一个 false</b>：对调用方而言，「provider 拒付」「provider
     * 返 null」「provider 抛异常」的后继语义完全一致 —— 钱没动，所以不得记流水、不得占用
     * 今日金币额度、不得给玩家发「+N 金币」提示。日志沿用既有
     * {@code "Failed to <action> for <player>: <原因>"} 格式，只在「原因」位置区分三种
     * 情况，服主据此能一眼判断该去找谁（改配置 / 看经济插件日志）。
     *
     * <p><b>日志由本方法打，调用方不得再打一次</b>（否则每条失败会留两行 WARNING）。
     *
     * @param transfer Vault 转账调用（通常是 {@code () -> eco.depositPlayer(player, amount)}）
     * @param player   玩家（仅用于日志；可为 null）
     * @param action   操作名，如 {@code "deposit capped reward"}，拼进既有文案格式
     * @return true = 钱确实动了；false = 未动（调用抛异常 / 响应为 null / 事务本身失败）
     */
    private boolean transactionSucceeded(Supplier<EconomyResponse> transfer,
                                        Player player, String action) {
        String who = logName(player);
        EconomyResponse response;
        try {
            response = transfer.get();
        } catch (RuntimeException e) {
            warn("Failed to " + action + " for " + who
                    + ": the economy provider threw " + e.getClass().getName()
                    + " from the Vault call itself (" + e.getMessage() + ")."
                    + " Treated as a failed transaction (no money recorded).");
            return false;
        }
        if (response == null) {
            warn("Failed to " + action + " for " + who
                    + ": the economy provider returned a null EconomyResponse."
                    + " Treated as a failed transaction (no money recorded)."
                    + " Check the Vault-compatible economy plugin's own logs.");
            return false;
        }
        try {
            if (response.transactionSuccess()) {
                return true;
            }
            warn("Failed to " + action + " for " + who + ": " + response.errorMessage);
            return false;
        } catch (RuntimeException e) {
            warn("Failed to " + action + " for " + who
                    + ": the economy provider threw " + e.getClass().getName()
                    + " from transactionSuccess() (" + e.getMessage() + ")."
                    + " Treated as a failed transaction (no money recorded).");
            return false;
        }
    }

    /**
     * 供流 E 在热重载时调用：清空每日金币累计缓存。
     * 注意 RewardEngine 实例由 ProgressManagerImpl 懒加载持有，reload 不重建，
     * 因此 reload 时必须显式清空，否则今日累计会跨越 reload 继续累加。
     *
     * <p><b>F-09-02 后：清空不再是绕过路径</b>。缓存已降级为 DB 表
     * {@code daily_coin_counters} 前面的写穿层，{@link #getTodayCoinsEarnedCents}
     * 在 miss 时会同步回读真源。所以本方法保留原语义（让 reload 拿到干净的
     * 内存态）而不再有"日限清零"的副作用。
     */
    public void resetDailyCoinsCache() {
        todayCoinsCache.clear();
    }

    /**
     * F1-24：移除某玩家的 quit 级残留条目（由 quit 编排点 PlayerJoinListener 调用）。
     *
     * <p>只清 {@code economyDisabledNotified}（per-player 去重提示，quit 后无保留意义）。
     * {@code todayCoinsCache} 刻意<strong>不</strong>在此清：它是防刷日限账本，
     * quit 不清是既定语义（清了解除日限即等于放开刷币），仅由
     * {@link #resetDailyCoinsCache()}（reload）和跨天懒清零管理。
     */
    public void unloadPlayer(UUID playerId) {
        economyDisabledNotified.remove(playerId);
    }

    /**
     * R5-DC-02：{@code /lea reset} 专用的 per-player 日限账本清理。
     *
     * <p><b>为什么不能在 {@link #unloadPlayer} 里做</b>：那个方法是 <b>quit</b> 语义 ——
     * 下线不是重置，清了 {@code todayCoinsCache} 等于当场解除当日额度，玩家下线重进即可
     * 重刷（这正是它 javadoc 明写「刻意不清」的原因）。本方法只服务 {@code /lea reset}
     * 这一条路径：那是管理员主动抹掉该玩家数据，日限额度当然也该归零。
     *
     * <p><b>为什么只清内存还不够</b>：{@code daily_coin_counters} 自 F-09-02 起是日限的
     * <b>写穿真源</b> —— {@link #getTodayCoinsEarnedCents} 在内存 miss 时回读该表。所以
     * 只清内存的话，下一次读会把 reset 前的额度从表里读回来，反而比「reset 后 reload
     * 丢内存值」更糟（残行变得更权威）。DB 侧的删除由
     * {@code DatabaseManager.deletePlayerData} 负责（该表已列入 {@code playerUuidTables}），
     * 本方法与之配对使用；两者都做才算清干净。
     *
     * @param playerId 目标玩家；null 安全忽略
     */
    public void resetDailyCoinsFor(UUID playerId) {
        if (playerId == null) {
            return;
        }
        todayCoinsCache.remove(playerId);
    }

    /**
     * F1-04：金币金额的玩家可见格式化 —— 整数值不带小数点（{@code 490}），
     * 非整数值保留两位小数（{@code 490.5}）。避免 {@code String.valueOf(double)}
     * 把 {@code 490.0} 原样渲染给玩家。仅用于 i18n 占位符（Vault 展示走其在
     * {@code eco.format} 的职责，不替换）。
     */
    private static String formatCoins(double v) {
        return v == Math.floor(v)
                ? String.valueOf((long) v)
                : String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * 金币门"部分发放"：给定拟发金额，返回在不突破 daily-cap 前提下实际可发金额。
     * 若已无剩余额度，发送 cap 提示并返回 0。
     *
     * <p><b>本方法内部是<b>两层</b>钳位，顺序不可交换</b>：
     * <ol>
     *   <li><b>先</b>过 {@link #MAX_SINGLE_PAYOUT_CENTS}（与 {@code money.daily-cap} 无关的
     *       绝对天花板，见 {@link #clampToPayoutCeiling}）。先钳这一步是为了让后续
     *       {@code Math.round(amount * 100.0)} 永远作用在有限的、量级正常的值上 ——
     *       {@code Math.round} 对超出 {@code long} 的输入会<b>静默饱和</b>到
     *       {@code Long.MAX_VALUE}，先按分算就会把饱和值当成"真实请求额"参与后续比较。</li>
     *   <li><b>再</b>过 {@code money.daily-cap} 的剩余额度（本方法原本的职责）。</li>
     * </ol>
     * 因此返回值语义仍是「<b>实际可发金额</b>（0 = 别发）」，只是现在有两条独立的上界：
     * 请求额 → min(天花板, 面值) → min(上一步, 剩余额度)。调用方拿到的永远是"真能发的钱"，
     * 这个契约在天花板触发时依然成立（见 {@link CappedPayout#capped()} 对"少发"的判定 ——
     * 天花板触发也是真实的"少发"，玩家确实只拿到钳后的钱）。
     *
     * <p><b>为什么只留两参数版本给 {@code depositCappedReward} 用</b>：那个方法由
     * 另一个改动方认领，签名一个字都不能动；它调用本重载时拿到的 {@code source} 是
     * {@link #DEFAULT_PAYOUT_SOURCE}。其余四个发币入口用三参数版本各报自己的来源，
     * 这样日志能直接指向"是哪笔奖励的配置写错了"。
     *
     * @param player the player about to be paid
     * @param amount the intended payout
     * @return actual payout in whole cents as double (0 = blocked, caller must not pay)
     */
    private double clampToDailyCap(Player player, double amount) {
        return clampToDailyCap(player, amount, DEFAULT_PAYOUT_SOURCE);
    }

    /**
     * 三参数版本：多一个 {@code source} 标签，只用于天花板日志的定位
     * （日限部分的行为与两参数版本<b>完全一致</b>）。
     *
     * @param player the player about to be paid
     * @param amount the intended payout
     * @param source 发币入口的配置来源描述（仅进 WARNING，不参与任何算术）
     * @return actual payout in whole cents as double (0 = blocked, caller must not pay)
     */
    private double clampToDailyCap(Player player, double amount, String source) {
        double requested = clampToPayoutCeiling(player, amount, source);
        // NaN 是唯一已经"失去数值语义"的输入：下游 Math.round(NaN) 得 0，而 0 会让
        // dailyCap<=0 的路径原样把 NaN 交回给 depositPlayer —— 一个 NaN 进入经济 provider
        // 会把玩家余额变成 NaN，之后加多少都是 NaN（永不可恢复）。这里统一按"别发"处理。
        if (Double.isNaN(requested)) {
            return 0;
        }
        int dailyCap = plugin.getConfigManager().getMainConfig()
                .getInt("money.daily-cap", 500);
        if (dailyCap <= 0) return requested;
        long capCents = (long) dailyCap * 100;
        long earnedCents = getTodayCoinsEarnedCents(player.getUniqueId());
        long remaining = capCents - earnedCents;
        if (remaining <= 0) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.daily-cap-reached",
                    "cap", String.valueOf(dailyCap),
                    "earned", formatCoins(getTodayCoinsEarned(player.getUniqueId())));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
            return 0;
        }
        long amountCents = Math.round(requested * 100.0);
        long payCents = Math.min(amountCents, remaining);
        if (payCents < amountCents) {
            // 部分发放：告知玩家今日额度只剩这么多
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.daily-cap-reached",
                    "cap", String.valueOf(dailyCap),
                    "earned", formatCoins(getTodayCoinsEarned(player.getUniqueId())));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        }
        return payCents / 100.0;
    }

    /**
     * 单笔发币的绝对天花板：请求额超过 {@link #MAX_SINGLE_PAYOUT_CENTS} 时钳到该值，
     * 并<b>按来源</b>留一次 WARNING。
     *
     * <p><b>为什么不做成"逐调用点各写一遍 {@code Math.min}"</b>：{@code RewardEngine} 里
     * 有五个 deposit 入口（固定额奖励门面、答对、会话完成、复习答对、提前停止按比例），
     * 逐点加意味着<b>第六个入口出现时必然漏加</b>，而且每处都要重新判一遍
     * {@code amount} 的有限性。收敛进 {@link #clampToDailyCap} 后，"能发的钱"只有
     * 一个算术出口，天花板天然覆盖全部 deposit 调用点（含
     * {@code depositCappedReward} —— 它同样经本方法取钱）。
     * <b>注意扣款方向（{@code withdrawPlayer}）刻意不过天花板</b>：本方法只挂在 deposit 侧，
     * 与 {@code money.daily-cap} 的既有挂载点一致（扣钱不是发钱）。
     *
     * <p><b>为什么命中时不给玩家发文案</b>：玩家侧只有 {@code progress.daily-cap-reached}
     * 一个既有 key，而那次截断的<b>真实原因</b>是"运营者把配置写错了"，不是"你今天领够了"。
     * 拿日限文案去解释配置错误，就是又一次"报给玩家的数字与真相不同源"（{@link CappedPayout}
     * 记录的那类事故）。所以玩家侧静默、运营者侧记 WARNING。
     *
     * <p><b>非正数与非有限值</b>：{@code amount <= 0} 原样返回（调用方各自的 {@code <= 0}
     * 短路语义不变）；{@code NaN} 也原样返回，由 {@link #clampToDailyCap} 统一折成 0；
     * {@code +Infinity} 先 {@code Math.round} 饱和成 {@code Long.MAX_VALUE} 再被本天花板收住。
     *
     * @param player 收款玩家（仅用于日志）
     * @param amount 请求金额（金币，double）
     * @param source 配置来源描述，只进日志
     * @return 钳位后的金额（金币）；可能等于 {@code amount}
     */
    private double clampToPayoutCeiling(Player player, double amount, String source) {
        if (!(amount > 0)) {
            return amount;
        }
        long cents = Math.round(amount * 100.0);
        if (cents <= MAX_SINGLE_PAYOUT_CENTS) {
            return amount;
        }
        double ceiling = MAX_SINGLE_PAYOUT_CENTS / 100.0;
        if (payoutCeilingWarned.add(source)) {
            // 原值走 %.0f 定点：直接拼 double 会打成 "1.0E18"/"Infinity" 之外还可能变成
            // 科学计数法，服主对不上自己写的那个数（与 SeasonManager 的告警同口径）。
            warn("Reward payout of " + String.format(Locale.ROOT, "%.0f", amount)
                    + " coins from " + source + " exceeds the single-payout ceiling;"
                    + " clamped to " + formatCoins(ceiling) + " coins (player "
                    + logName(player) + "). Nothing in the shipped config comes close to"
                    + " " + formatCoins(ceiling) + " coins per payout, so this almost certainly"
                    + " means a reward amount was mistyped (extra zeros / scientific notation)"
                    + " or money.daily-cap was set to 0 while a reward value is absurd."
                    + " Check money.daily-cap and the reward amounts that feed this path."
                    + " This warning appears once per source per server lifetime.");
        }
        return ceiling;
    }

    /**
     * R-16：{@code depositCappedReward} 的「<b>应发面值 vs 实发额</b>」纯值对象（无状态、无 IO、
     * 不碰 Vault）。三个领奖调用点共用它做「是否被日限截断」的唯一算术。
     *
     * <p><b>为什么需要它（R-16 缺陷本体）</b>：{@code MiscGUIListener.claimBehavioralChallenge}、
     * {@code DailyChallengeManager.onAllWordChallengesComplete}、
     * {@code CheckInManager.checkInLocked} 三处过去都把 {@code depositCappedReward} 的返回值
     * 折叠成 {@code paid > 0}，然后<b>拿面值</b>去渲染文案、并据此收盘写台账。日限截断时
     * （默认 {@code money.daily-cap: 500}，玩家今天已得 480）实发 0.20 而玩家被告知
     * 「+100 金币」，且台账已标「已领」——剩下的 80 永久不可追回。报给玩家的数字与真正
     * 落账的数字必须<b>同源</b>，否则每一次截断都是一次「谎报 + 吞钱」。
     *
     * <p><b>为什么不做成 {@code depositCappedRewardDetailed(Player,double)} 直接返本记录</b>：
     * 三个调用点在生产代码里当然改得动，但它们<b>全部</b>被既有单测用
     * {@code when(rewardEngine.depositCappedReward(any(), anyDouble())).thenReturn(...)} 打桩
     * （{@code CheckInManagerPayoutTest}、{@code DailyChallengeLedgerFailureTest}、
     * {@code DailyChallengeRolloverDateTest}）。换一个方法名去付钱，等于让这些桩全部落空
     * —— 替身返回 {@code null}/{@code 0} —— 钱路断言会整片变红，而它们钉的正是「付了多少」。
     * 故：<b>付钱入口仍是 {@link #depositCappedReward}（签名与语义一字不改）</b>，调用方把
     * 面值 {@code nominal} 与返回值 {@code paid} 交给本记录折叠，
     * 「是否截断」的判断从此只有这一份实现（三处各自写 {@code paid < nominal} 必然漂移）。
     *
     * <p><b>为什么 {@link #capped()} 按「分」比较</b>：{@link #depositCappedReward} 内部先
     * {@code Math.round(amount*100)} 再 {@code /100.0}。面值带亚分精度时（如 {@code 100.004}）
     * 实发会被四舍五入成 {@code 100.00}，直接写 {@code paid < nominal} 会把一次
     * 「根本没截断」误报成「被截断」→ 玩家收到一句错误的「实际发放 100（应发 100.004）」。
     * 按分对齐后，只有真正少发才计为截断。
     *
     * @param nominal 应发面值（调用方想发的金额，含非整数分）
     * @param paid    {@code depositCappedReward} 的返回值（实际入账额，0 = 一分没发）
     */
    public record CappedPayout(double nominal, double paid) {

        /**
         * 是否「发了，但比应发少」= 被 {@code money.daily-cap} 截断。
         *
         * <p><b>必须要求 {@code paid > 0}</b>：{@code paid == 0} 是「一分没发」语义
         * （无 Vault / 金额非正 / 额度已满 / 转账失败），调用方各走自己的
         * {@code claim-failed} / {@code reward-pending} / {@code storageFailed} 分支。
         * 把 0 也算成「截断」，就会对一次彻底失败的领奖报一句「实际发放 0 金币」，
         * 把失败伪装成「已领但少给」——正是本缺陷要消灭的那种谎报。
         */
        public boolean capped() {
            return paid > 0 && Math.round(paid * 100.0) < Math.round(nominal * 100.0);
        }

        /** 实发额的玩家可见文本：整数去小数点（{@code 20}）、非整数保留两位（{@code 0.20}）。 */
        public String paidText() {
            return formatCoins(paid);
        }

        /** 应发面值的玩家可见文本（与 {@link #paidText()} 同一套格式化，避免两边写法不同）。 */
        public String nominalText() {
            return formatCoins(nominal);
        }
    }

    /**
     * R-34：挑战/签到等"固定额"奖励的 public 发币门面——纳入 {@code money.daily-cap}。
     *
     * <p>此前每日挑战（{@code DailyChallengeManager.depositWordChallengeReward}、
     * {@code MiscGUIListener.depositChallengeReward}）与签到（{@code CheckInManager.giveCoins}）
     * 各自 {@code eco.depositPlayer} 直发，绕过了金币日限与今日金币记账
     * （{@code todayCoinsCache}），玩家可借此刷爆 cap。改走此门面后：
     * <ol>
     *   <li>经 {@link #clampToDailyCap} 过 {@code money.daily-cap}（超限部分不发并提示玩家）；</li>
     *   <li>成功后 {@code recordCoinsEarned}（lifetime 统计）+ {@link #addTodayCoinsEarned}
     *       （今日记账，使后续答题发币也看得到这笔已发额）。</li>
     * </ol>
     *
     * <p>注意：这里<strong>只过金币门，不过答题数门</strong>——{@code word-system.daily-limit}
     * 是"答题数"语义，对领奖/签到无意义，故调 {@link #clampToDailyCap} 而非
     * {@link #checkDailyCaps}。{@code money.enabled} 开关由各调用方按既有策略自行把关
     * （签到保留其 enabled 预检；挑战沿用"有 Vault 即发"的既有行为）。
     *
     * <p><b>R-16：返回值必须由调用方原样带下去，不许折叠成布尔。</b>{@code >0} 只能回答
     * 「钱有没有到」这一个问题，回答不了「到了多少」。三个调用点过去把本方法的返回值折成
     * {@code paid > 0} 再用<b>面值</b>渲染文案并写「已领」台账 —— 日限截断时玩家被
     * 告知的金额与实际入账差一个数量级，且余额永久丢失（详见 {@link CappedPayout} 的说明）。
     * 正确用法：{@code new RewardEngine.CappedPayout(nominal, depositCappedReward(...))}，
     * 由它统一判「是否截断」并给出两个可渲染的金额文本。
     * <b>本方法签名与语义（实发额，0 = 未付）保持不变</b>，既有调用方与打桩不受影响。
     *
     * @param player the player to pay
     * @param amount the intended reward amount
     * @return 实际入账金额（0 = 无 Vault / 金额非正 / cap 已满 / 转账失败，调用方以 &gt;0 视为已付）
     */
    public double depositCappedReward(Player player, double amount) {
        Economy eco = getEconomy();
        if (eco == null || amount <= 0) return 0;
        double pay = clampToDailyCap(player, amount);
        if (pay <= 0) return 0;
        if (!depositSucceeded(eco, player, pay, "deposit capped reward")) {
            return 0;
        }
        // R11：流水写失败只记日志并继续（钱已到账；见 recordCoinLedger 的失败方向说明）。
        recordCoinLedger(player, player.getUniqueId(), pay, true);
        addTodayCoinsEarned(player.getUniqueId(), pay);
        return pay;
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
        if (eco == null) return;
        if (!isMoneyEnabled()) {
            notifyEconomyDisabledOncePerDay(player);
            return;
        }
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

        amount = clampToDailyCap(player, amount, CORRECT_ANSWER_SOURCE);
        if (amount <= 0) return;

        if (depositSucceeded(eco, player, amount, "deposit money")) {
            // R11：流水写失败只记日志并继续（见 recordCoinLedger）。
            recordCoinLedger(player, player.getUniqueId(), amount, true);
            addTodayCoinsEarned(player.getUniqueId(), amount);
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-reward",
                    "amount", eco.format(amount));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
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
        if (eco == null) return;
        if (!isMoneyEnabled()) {
            notifyEconomyDisabledOncePerDay(player);
            return;
        }
        if (!checkDailyCaps(player)) return;

        double sessionBonus = plugin.getConfigManager().getMainConfig()
                .getDouble("progress-system.rewards.types.money.session-complete-bonus", 50.0);
        double maxComboBonus = plugin.getConfigManager().getMainConfig()
                .getDouble("progress-system.rewards.types.money.max-combo-bonus", 25.0);

        double totalAmount = sessionBonus;
        if (maxComboReached >= 5) {
            totalAmount += maxComboBonus;
        }

        totalAmount = clampToDailyCap(player, totalAmount, SESSION_BONUS_SOURCE);
        if (totalAmount <= 0) return;

        if (depositSucceeded(eco, player, totalAmount, "deposit session bonus")) {
            // R11：流水写失败只记日志并继续（见 recordCoinLedger）。
            recordCoinLedger(player, player.getUniqueId(), totalAmount, true);
            addTodayCoinsEarned(player.getUniqueId(), totalAmount);
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-session-bonus",
                    "amount", eco.format(totalAmount));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
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
        if (eco == null) return;
        if (!isMoneyEnabled()) {
            notifyEconomyDisabledOncePerDay(player);
            return;
        }
        if (!checkDailyCaps(player)) return;

        double amount;
        if (word != null && word.getReward() > 0) {
            // Word has custom reward, use it directly
            amount = word.getReward();
        } else {
            double baseAmount = plugin.getConfigManager().getMainConfig()
                    .getDouble("progress-system.rewards.types.money.base-amount", 10.0);
            double reviewMultiplier = plugin.getConfigManager().getMainConfig()
                    .getDouble("progress-system.rewards.types.money.review-multiplier", 1.2);
            boolean comboBonus = plugin.getConfigManager().getMainConfig()
                    .getBoolean("progress-system.rewards.types.money.combo-bonus", true);

            amount = baseAmount * reviewMultiplier;
            if (comboBonus) {
                amount = Math.round(amount * comboMultiplier * 100.0) / 100.0;
            }
        }

        amount = clampToDailyCap(player, amount, REVIEW_CORRECT_SOURCE);
        if (amount <= 0) return;

        if (depositSucceeded(eco, player, amount, "deposit review money")) {
            // R11：流水写失败只记日志并继续（见 recordCoinLedger）。
            recordCoinLedger(player, player.getUniqueId(), amount, true);
            addTodayCoinsEarned(player.getUniqueId(), amount);
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-review-reward",
                    "amount", eco.format(amount));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
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
        // 负数/NaN/Infinity 一律拒绝：Vault 的 withdrawPlayer(player, -500) 语义是
        // 【给玩家加 500】，而这条路不经过 money.daily-cap（clampToDailyCap 只挂在 deposit 侧），
        // 于是「复习答错」会变成无限刷币机 —— 配错一个符号就把惩罚反转成奖励。
        // 0 也短路：既不提示玩家（"扣了 0 金币"是噪音），也不写 SPENT 流水。
        if (!isValidPenaltyAmount(penaltyAmount)) {
            warn("Invalid progress-system.rewards.types.money.review-penalty-amount = "
                    + penaltyAmount + " for " + logName(player)
                    + ": review penalty must be a finite positive number."
                    + " Negative values would be paid out as income by Vault and bypass"
                    + " money.daily-cap entirely, so the penalty was skipped."
                    + " Fix the value in config.yml (e.g. 5.0) or set"
                    + " review-penalty: false to disable it.");
            return;
        }

        if (withdrawSucceeded(eco, player, penaltyAmount, "withdraw penalty money")) {
            // R11：SPENT 方向的流水写失败同上（钱已扣走，只影响统计与成就口径）。
            recordCoinLedger(player, player.getUniqueId(), penaltyAmount, false);
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-review-penalty",
                    "amount", eco.format(penaltyAmount));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        }
    }

    /**
     * 复习扣款额的合法性判定：必须是<b>有限正数</b>。
     *
     * <p><b>为什么要把 0 也判非法</b>：{@code withdrawPlayer(player, 0)} 在多数 provider 上
     * 会返回 SUCCESS 并留下一条 0 元流水，接着给玩家发「-0 金币」的提示 —— 纯噪音。
     *
     * <p><b>为什么必须显式判 NaN/Infinity</b>：{@code YamlConfiguration.getDouble} 对
     * {@code -500}、{@code .NaN}、{@code .Infinity} 都照读不误；{@code NaN <= 0} 是
     * {@code false}，只写 {@code amount <= 0} 会让 NaN 一路溜进 Vault。
     */
    private static boolean isValidPenaltyAmount(double amount) {
        return Double.isFinite(amount) && amount > 0;
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
        if (eco == null) return;
        if (!isMoneyEnabled()) {
            notifyEconomyDisabledOncePerDay(player);
            return;
        }
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
        partial = clampToDailyCap(player, partial, STOPPED_SESSION_SOURCE);
        if (partial <= 0) return;

        if (depositSucceeded(eco, player, partial, "deposit stopped-session bonus")) {
            // R11：流水写失败只记日志并继续（见 recordCoinLedger）。
            recordCoinLedger(player, player.getUniqueId(), partial, true);
            addTodayCoinsEarned(player.getUniqueId(), partial);
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "word-learning-gui.stopped-partial-reward",
                    "answered", String.valueOf(answered),
                    "total", String.valueOf(total),
                    "amount", eco.format(partial));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        }
    }
}
