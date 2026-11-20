package com.letslearnenglish.minecraftplugin.core.progress;

import java.time.Clock;
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
     * Round 30：日限账本<b>是否与其他进程共享</b>（{@code null} = 按生产判据自动决定）。
     *
     * <p><b>为什么要分流而不是统一</b>：{@link #todayCoinsCache} 是<b>进程内</b>账本。
     * 单进程（SQLite 单文件）时它就是完整真相，读它零 IO；多进程共享一个 MySQL 时它是
     * 一份会陈旧的副本 —— 玩家在 A 服花掉的额度，B 服的条目里没有，于是日限实际额度
     * 变成「子服数 × cap」（时间线与触发条件见 {@code DailyCapCrossServerTest}）。
     * 两条路的最优解相反，压成一条就必然有一条吃亏：统一走库 = 给绝大多数单服部署
     * 每笔发币加若干次 IO；统一走内存 = 多服部署的日限永久失守。
     *
     * <p><b>生产判据是 {@code DatabaseManager.isSQLite()}</b>，不是
     * {@code proxy.server-name} 是否为空：后者为空<b>不等于</b>单服（MySQL + 两台子服
     * 忘了填名字完全可能存在），而本分流的漏判方向是<b>多发钱</b>，不能靠"运营者填对了
     * 配置"来保证。{@code isSQLite()} 是「后端只有一个文件、只能被一个进程持有」的
     * <b>结构性</b>保证 —— 恰好多服场景在结构上不可能满足。
     *
     * <p><b>为什么需要这个测试缝</b>：{@code DailyCapCrossServerTest} 用「两个
     * {@link DatabaseManager} 指向同一个 SQLite 文件」造出「两个进程」的最小可观测等价物
     * —— 那是唯一能在纯 JVM 里跑起来的双写者装置。若判据硬编 {@code !isSQLite()}，
     * 那条回归用例<b>永远走不到</b>新代码（它必然是 SQLite），修法与测试就脱钩了：
     * 把原子预占改回纯内存判据，测试照样全绿。所以留一个包级可见的强制开关，
     * 让它在真 SQLite 上驱动<b>同一段</b>预占/补偿 SQL（SQLite 与 MySQL 只差
     * {@code INSERT OR IGNORE} / {@code INSERT IGNORE} 这一处，已由既有
     * {@code isSQLite()} 分支处理）。与 {@link #setClock} 同一姿态：包级可见、
     * 生产零调用方、{@code null} 回落生产判据。
     */
    private volatile Boolean sharedDailyLedger;

    /**
     * 仅供测试强制 {@link #usesSharedDailyLedger()} 的返回值；{@code null} = 回落生产判据。
     *
     * <p>包级可见（不是 {@code public}）：与 {@link #setClock} 同口径 —— 生产代码没有
     * 调用方，同包测试可直接调，不必把这个开关暴露成 public API。
     */
    void setSharedDailyLedger(Boolean forced) {
        this.sharedDailyLedger = forced;
    }

    /**
     * Round 30：库侧原子预占的 CAS 重试上限。
     *
     * <p><b>为什么是 CAS 而不是 {@code SELECT ... FOR UPDATE}</b>：本仓在 MySQL 上
     * <b>不设置任何会话级状态</b>（无 {@code SET SESSION} / 用户变量 / {@code GET_LOCK} /
     * {@code SELECT ... FOR UPDATE}，{@code initializeMySQL} 的注释明写并已 grep 确认）。
     * 乐观 CAS 只靠"读到一个值 → 只在这个值上做加法 → 看 affected rows 是否 1"，
     * 不需要行锁、不需要事务、不需要会话态，跨进程语义由后端的行级原子性保证。
     *
     * <p><b>为什么 affected rows 在 CAS 下是可信的</b>：本仓 MySQL URL 没有显式设置
     * {@code useAffectedRows}，connector-j 8.3.0 的默认值是 {@code false}，即驱动会发
     * {@code CLIENT_FOUND_ROWS}（已由字节码核实：{@code NativeAuthenticationProvider}
     * 里 {@code clientFlags |= useAffectedRows ? 0 : CLIENT_FOUND_ROWS}）。该开关会改变
     * <b>"匹配到但值未变"</b> 的返回口径（1 vs 0），所以本类<b>刻意不依赖</b>
     * {@code ON DUPLICATE KEY UPDATE} 的返回值 —— 那是既有
     * {@code DatabaseManager.claimCheckIn} javadoc 已经论证过的坑，此处沿用同一结论。
     * 而 CAS 的 {@code SET cents = cents + ?} 里 {@code ? >= 1}（见
     * {@link #reserveDailyCoinsInDb} 的 {@code delta} 计算：{@code remaining > 0} 且
     * {@code want >= 1}），值<b>必然变化</b>，于是"匹配且变化"在两种口径下都是 <b>1</b>；
     * "CAS 落败"（{@code cents <> 旧值}）在两种口径下都是 <b>0</b>。判据因此与驱动参数无关。
     *
     * <p>4 次而不是 2 次：同一玩家在同一毫秒被两台服同时发币时，每次 CAS 落败都说明对方
     * 抢赢了一次，重读后剩余额度只会更小；4 次足够覆盖真实的跨服连点，再多只会在病态竞争下
     * 空转（且每次空转都在主线程上）。耗尽时<b>不发钱</b>（fail-closed，方向安全），见该方法。
     */
    private static final int RESERVE_CAS_ATTEMPTS = 4;

    /**
     * Round 30：一笔发币的日限判据结果。
     *
     * <p><b>为什么不是一个 {@code double}</b>：{@link #clampToDailyCap} 原来只回答
     * "能发多少"，但多进程路径上"能发多少"是<b>库侧预占</b>出来的 —— 预占一旦成功，
     * 这笔额度就已经写进库了。于是调用方必须同时知道三件事，否则要么重复记账、要么在
     * Vault 转账失败时把额度永久烧掉：
     * <ol>
     *   <li>{@link #grantedCents()} —— 实际放行多少（可能少于请求额，也可能 0）；</li>
     *   <li>{@link #earnedCents()} —— <b>本次放行之前</b>的"今日已用"，与判据同源，
     *       供 {@code progress.daily-cap-reached} 的 {@code {earned}} 占位符使用
     *       （拿内存旧值渲染就是又一次"报给玩家的数字与真相不同源"）。
     *       刻意是"放行前"：改动前那条文案读的就是 {@code getTodayCoinsEarned}（尚未累加
     *       本次），改成"放行后"会让所有既有提示的数字整体偏大一笔，是没人要的副作用；</li>
     *   <li>{@link #reservedInDb()} —— 额度是否已由库侧预占写好。是则
     *       {@code addTodayCoinsEarned} 不得再落一次库（会双计），且转账失败必须补偿反向
     *       UPDATE。</li>
     * </ol>
     *
     * @param date          判据使用的自然日（补偿时要用<b>同一个</b>日期，否则跨午夜会把
     *                      退款打到另一行 —— 预占与补偿相隔几微秒，但时钟确实会翻页）
     * @param grantedCents  实际放行的"分"（用于预占/补偿记账；{@code <= 0} = 一分不放）
     * @param paid          实际入账金额（金币）。<b>单进程路径上<b>不是</b>
     *                      {@code grantedCents / 100.0} 的等价物</b>：改动前
     *                      {@code money.daily-cap <= 0} 时原样返回配置里的 double，
     *                      亚分精度就直接进了 Vault。这里保留原值，否则"不限额度"这条
     *                      最常用的路径会静默多一次四舍五入（{@link CappedPayout} 的
     *                      javadoc 正好记着这类"面值 vs 实发"的坑）
     * @param earnedCents   本次放行<b>之前</b>的今日已用；{@code < 0} = 不可知（仅
     *                      {@link #unlimited} 用，那条路必定全额放行、不会渲染文案）
     * @param reservedInDb  true = {@code grantedCents} 已在 {@code daily_coin_counters} 里预占
     */
    private record DailyCapHold(LocalDate date, long grantedCents, double paid,
                                long earnedCents, boolean reservedInDb) {

        /** 一分不放（NaN 等"没有可发金额"的输入）。 */
        static DailyCapHold none(LocalDate date) {
            return new DailyCapHold(date, 0L, 0.0, 0L, false);
        }

        /** {@code money.daily-cap <= 0}（不限额度）：原样放行，不预占、不落库。 */
        static DailyCapHold unlimited(LocalDate date, double requested) {
            return new DailyCapHold(date, Math.round(requested * 100.0), requested, -1L, false);
        }

        /** 纯内存判据（单进程 SQLite 路径，行为与改动前逐字节一致）。 */
        static DailyCapHold memory(LocalDate date, long grantedCents, long earnedCents) {
            return new DailyCapHold(date, grantedCents, grantedCents / 100.0, earnedCents, false);
        }

        /** 库侧已原子预占。 */
        static DailyCapHold reserved(LocalDate date, long grantedCents, long earnedCents) {
            return new DailyCapHold(date, grantedCents, grantedCents / 100.0, earnedCents, true);
        }

        /** 一分不放（调用方据此直接返回，不给 Vault 任何调用）。 */
        boolean blocked() {
            return paid <= 0;
        }

        /** 放行后的今日已用（库侧预占时用于回填内存镜像）。 */
        long earnedAfterCents() {
            return earnedCents < 0 ? earnedCents : earnedCents + grantedCents;
        }

        /** 本次放行前的今日已用（金币），仅用于玩家文案；{@code < 0} 时由调用方回落。 */
        double earnedBeforeCoins() {
            return earnedCents / 100.0;
        }
    }

    /**
     * {@link #reserveDailyCoinsInDb} 的结果：放行额 + 放行<b>之前</b>的今日已用。
     *
     * <p>{@link #usedBeforeCents()} 为负是<b>哨兵</b>（"库不可用，判据不可知"），不是金额 ——
     * 用负数而不是 {@code null} 是为了让"额度已满（放行 0）"与"读不到库（放行 0）"这两件
     * 后果完全不同的事<b>在类型上就分得开</b>。混成一个 0 会让库抖动被谎报成
     * "你今天额度用完了"，玩家收到一条错误提示且这一笔白丢。
     */
    private record DailyCapReserve(long grantedCents, long usedBeforeCents) {

        static DailyCapReserve unavailable() {
            return new DailyCapReserve(0L, -1L);
        }

        boolean isUnavailable() {
            return usedBeforeCents < 0;
        }
    }

    /**
     * 发币<b>唯一</b>的收口（Round 30）：日限判据（含跨进程原子预占）→ Vault 转账 →
     * 流水 → 今日记账，失败时<b>补偿</b>已预占的额度。
     *
     * <h2>为什么必须是一个收口而不是各调用点自己拼</h2>
     * 原来五个发币入口各自重复"钳位 → 转账 → 记流水 → 今日记账"四步。多进程路径把
     * <b>顺序</b>变成了正确性的一部分：预占必须排在转账<b>之前</b>（否则两个进程都能通过
     * 判据），而补偿必须排在转账<b>之后</b>（否则一次 provider 抖动会把额度永久烧掉）。
     * 五处各写一遍意味着第六个入口出现时必然漏掉其中一步，而漏掉的方向是"多发钱"或
     * "白烧额度"两种事故之一。收成一个方法后，"能发的钱"与"记账/退款"只有一份实现。
     *
     * <h2>补偿为什么可靠</h2>
     * 见 {@link #releaseDailyCapHold}：反向 UPDATE 用 {@code CASE WHEN cents >= ? ...} 保证
     * 不会把行减成负数，<b>不看 affected rows</b>（那是 {@code useAffectedRows} 会改变口径的
     * 地方），失败时重试一次，仍失败则留一条点名金额与玩家的 WARNING —— 那是唯一需要人工
     * 介入的形态，绝不能静默。
     *
     * @param eco    已确认非 null 的经济后端
     * @param player 收款玩家
     * @param amount 拟发金额（金币）
     * @param source 日限/天花板告警的配置来源标签（只进日志）
     * @param action Vault 操作名（进既有 WARNING 格式）
     * @return 实际入账金额（0 = 未付：被日限拦下或转账失败；调用方据此不发"已到账"提示）
     */
    private double payCappedReward(Economy eco, Player player, double amount,
                                  String source, String action) {
        if (eco == null || !(amount > 0)) {
            // 负数/0/NaN 一律到此为止：NaN 也在这里被拦（`!(NaN > 0)` 为 true），
            // 不让它有机会进 Math.round 或 Vault（见 clampToDailyCap 的 NaN 说明）。
            return 0;
        }
        DailyCapHold hold = clampToDailyCap(player, amount, source);
        if (hold.blocked()) {
            return 0;
        }
        double pay = hold.paid();
        if (!depositSucceeded(eco, player, pay, action)) {
            // 转账没成 → 钱没动。预占过的额度必须退回，否则一次 provider 抖动 = 玩家当日
            // 额度永久缩水（比"多发钱"更伤的不可逆损失）。
            releaseDailyCapHold(player.getUniqueId(), hold);
            return 0;
        }
        // R11：流水写失败只记日志并继续（钱已到账；见 recordCoinLedger 的失败方向说明）。
        recordCoinLedger(player, player.getUniqueId(), pay, true);
        if (hold.reservedInDb()) {
            // 库侧预占已经把这笔写进 daily_coin_counters 了。再调 addTodayCoinsEarned
            // 会二次落库（当日额度凭空翻倍 → 日限提前收紧）——故这里只同步内存展示值。
            addTodayCoinsEarnedInMemoryOnly(player.getUniqueId(), hold);
        } else {
            addTodayCoinsEarned(player.getUniqueId(), pay);
        }
        return pay;
    }

    /**
     * Round 30：库侧已预占时的内存镜像 —— <b>只写内存，绝不落库</b>。
     *
     * <p>{@code todayCoinsCache} 在多进程路径上不再是判据（判据是库），只服务两件事：
     * {@code {earned}} 占位符的展示，以及"库整个不可用"时的降级判据。所以这里
     * <b>直接覆盖</b>为预占时读到的库值，而不是累加 —— 累加会让镜像与库慢慢漂移，
     * 而漂移的镜像正是本缺陷的成因。
     */
    private void addTodayCoinsEarnedInMemoryOnly(UUID playerId, DailyCapHold hold) {
        if (hold == null || hold.grantedCents() <= 0) {
            return;
        }
        long earnedAfter = hold.earnedAfterCents();
        if (earnedAfter < 0) {
            return;
        }
        DailyCoins entry = todayCoinsCache.computeIfAbsent(playerId, k -> new DailyCoins());
        synchronized (entry) {
            entry.date = hold.date();
            entry.cents.set(earnedAfter);
        }
    }

    /**
     * Round 30：转账失败后把已预占的额度退回（补偿反向 UPDATE）。
     *
     * <p>这条路径<b>必须</b>存在：改"先转账后记账"为"先预占后转账"之后，漏了它就是
     * "一次 Vault 抖动 → 额度永久烧掉"，比原缺陷严重得多。
     * {@code RewardEngineDailyCapTest.vaultFailureDoesNotConsumeDailyCap} 是它在
     * 单进程路径上的守门人；{@code DailyCapCrossServerTest} 里有一条对应的多进程用例。
     *
     * <p>内存镜像在<b>库侧确实退回之后</b>才校正为"预占之前"的那个值 —— 不用
     * {@code -granted} 的增量写法：这次失败的发币从未把镜像推进过（推进只发生在转账
     * <b>成功</b>之后），对一份没被推进的镜像做减法会把额度凭空多让出来。改成
     * 直接写入 {@link DailyCapHold#earnedCents()}（预占时读到的库值）与库同源，
     * 且对"镜像还停留在更早的旧值"这种本来就存在的偏差也顺带纠正。
     */
    private void releaseDailyCapHold(UUID playerId, DailyCapHold hold) {
        if (hold == null || !hold.reservedInDb() || hold.grantedCents() <= 0) {
            return;
        }
        boolean released = releaseDailyCoinsInDb(playerId, hold.date(), hold.grantedCents());
        if (!released) {
            // 唯一需要人工介入的形态，绝不静默：额度被扣掉但这笔钱没发出去。
            warn("Failed to release the daily-cap quota reserved for " + playerId + " / "
                    + hold.date() + " after the Vault transfer failed: "
                    + (hold.grantedCents() / 100.0) + " coins stay counted as earned although"
                    + " nothing was paid out. The player's money.daily-cap allowance is"
                    + " permanently reduced by that amount for today. Retry the reservation"
                    + " cleanup manually: UPDATE " + tablePrefixOrEmpty() + "daily_coin_counters"
                    + " SET cents = CASE WHEN cents >= " + hold.grantedCents() + " THEN cents - "
                    + hold.grantedCents() + " ELSE 0 END WHERE player_uuid = '"
                    + playerId + "' AND coin_date = '" + hold.date() + "'");
            return;
        }
        if (hold.earnedCents() < 0) {
            return;
        }
        DailyCoins entry = todayCoinsCache.get(playerId);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            if (hold.date().equals(entry.date)) {
                entry.cents.set(hold.earnedCents());
            }
        }
    }

    /** 日志里引用表名用；{@code DatabaseManager} 不在时给空串（告警本身不能因此抛异常）。 */
    private String tablePrefixOrEmpty() {
        try {
            DatabaseManager db = plugin.getDatabaseManager();
            if (db != null && db.getTablePrefix() != null) {
                return db.getTablePrefix();
            }
        } catch (RuntimeException e) {
            // 拿不到前缀不该让"额度没退"这条告警消失。
        }
        return "";
    }

    /**
     * Round 30：在库侧<b>原子预占</b>一段今日额度，返回实际放行额。
     *
     * <h2>为什么不是"先 SELECT 再判再 UPDATE"</h2>
     * 那正是缺陷的形状：两个进程各自读到 {@code remaining=400}，各自付 400。
     * 判据必须<b>下推到库</b>，让"够不够"由一次写操作的结果回答。
     *
     * <h2>三步（每条都是单语句，不依赖事务）</h2>
     * <ol>
     *   <li>{@code INSERT OR IGNORE} / {@code INSERT IGNORE} 建行（幂等）——
     *       条件 UPDATE 在"行不存在"时也返回 0，与"额度不足"无法区分，必须先保证行在；</li>
     *   <li>{@code SELECT cents} 读当前已用；</li>
     *   <li>{@code UPDATE ... SET cents = cents + ? WHERE ... AND cents = ?旧值} ——
     *       affected rows == 1 表示"在<b>我读到的那个值</b>上抢到了"，== 0 表示别的进程
     *       在这两步之间改动过（重试一次 CAS）。</li>
     * </ol>
     * 不需要 {@code BEGIN}：单条 UPDATE 自带行级原子性；也不需要
     * {@code SELECT ... FOR UPDATE}（本仓在 MySQL 上不设任何会话级状态）。
     *
     * <h2>为什么 CAS 用等值比较而不是 {@code >=}</h2>
     * {@code cents} 在本表里对同一行<b>单调递减可用额度</b>（只增，除补偿/夜间清理）。
     * 等值比较能精确分辨"我算的那份剩余额度还有效"，{@code >=} 会在对方写过更大的值时
     * 静默按旧值放行 —— 那就是超发。单调性让 CAS 一定能收敛：每次落败后重读到的
     * 已用额度只会更大、剩余只会更小。
     *
     * @param wantCents 本次想预占的分（调用方已保证 {@code >= 1} 且不超过 cap）
     * @param capCents  当日上限（分）
     * @return 放行额 + 放行<b>之前</b>的今日已用；库不可用时返回
     *         {@link DailyCapReserve#unavailable()}（调用方按内存判据降级，与改动前同向）
     */
    private DailyCapReserve reserveDailyCoinsInDb(UUID playerId, LocalDate date,
                                                 long wantCents, long capCents) {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || wantCents <= 0) {
            return DailyCapReserve.unavailable();
        }
        ensureDailyCoinsTable();
        String table = db.getTablePrefix() + "daily_coin_counters";
        // 两种后端的 IGNORE 插入语义一致（1 = 插入 / 0 = 已存在），且返回值本方法不看。
        String insertSql = (db.isSQLite() ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ")
                + table + " (player_uuid, coin_date, cents) VALUES (?, ?, 0)";
        String selectSql = "SELECT cents FROM " + table
                + " WHERE player_uuid = ? AND coin_date = ?";
        String updateSql = "UPDATE " + table + " SET cents = cents + ?"
                + " WHERE player_uuid = ? AND coin_date = ? AND cents = ?";
        DailyCapReserve result = db.withConnection(conn -> {
            try (java.sql.PreparedStatement ins = conn.prepareStatement(insertSql)) {
                ins.setString(1, playerId.toString());
                ins.setString(2, date.toString());
                ins.executeUpdate();
            }
            long lastUsed = 0L;
            for (int attempt = 0; attempt < RESERVE_CAS_ATTEMPTS; attempt++) {
                long used;
                try (java.sql.PreparedStatement sel = conn.prepareStatement(selectSql)) {
                    sel.setString(1, playerId.toString());
                    sel.setString(2, date.toString());
                    try (java.sql.ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            // 建行成功却读不到：库的行为不可解释，按"判据不可知"降级，
                            // 不谎报成"额度已满"。
                            return DailyCapReserve.unavailable();
                        }
                        used = rs.getLong(1);
                    }
                }
                lastUsed = used;
                long remaining = capCents - used;
                if (remaining <= 0) {
                    return new DailyCapReserve(0L, used);
                }
                long delta = Math.min(wantCents, remaining);
                if (delta <= 0) {
                    return new DailyCapReserve(0L, used);
                }
                try (java.sql.PreparedStatement upd = conn.prepareStatement(updateSql)) {
                    upd.setLong(1, delta);
                    upd.setString(2, playerId.toString());
                    upd.setString(3, date.toString());
                    upd.setLong(4, used);
                    if (upd.executeUpdate() == 1) {
                        // used 是<b>预占前</b>的已用额度 —— {earned} 占位符要的就是这个
                        // （与改动前读 getTodayCoinsEarned 的时机一致）。
                        return new DailyCapReserve(delta, used);
                    }
                }
            }
            // CAS 连败（同一个玩家正被多台服同时发币）。fail-closed：不放行、不转账。
            // 报"额度已满"在语义上不精确，但方向安全（不会多发），且留了 WARNING。
            warn("Daily-cap reservation for " + playerId + " / " + date + " lost the"
                    + " compare-and-swap " + RESERVE_CAS_ATTEMPTS + " times in a row (another"
                    + " server keeps winning the same row). This payout was skipped to keep the"
                    + " cap from being exceeded; the player will be paid on their next attempt.");
            // 再读一次只为让玩家提示里的 {earned} 是真实数字（这条路径罕见，多一次
            // 单行 SELECT 换掉一个编造的数，值得）。读不到就沿用最后一次成功读到的值。
            long forDisplay = lastUsed;
            try (java.sql.PreparedStatement sel = conn.prepareStatement(selectSql)) {
                sel.setString(1, playerId.toString());
                sel.setString(2, date.toString());
                try (java.sql.ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        forDisplay = rs.getLong(1);
                    }
                }
            }
            return new DailyCapReserve(0L, Math.max(0L, forDisplay));
        }, DailyCapReserve.unavailable());
        return result == null ? DailyCapReserve.unavailable() : result;
    }

    /**
     * Round 30：退回一段已预占的额度（补偿反向 UPDATE）。
     *
     * <p><b>不看 affected rows</b>：{@code CASE WHEN cents >= ? THEN cents - ? ELSE 0 END}
     * 在"库里的值本来就是 0"时是<b>匹配但值不变</b>，而 MySQL 的 {@code CLIENT_FOUND_ROWS}
     * 会把这个 1 变成 0（见 {@link #RESERVE_CAS_ATTEMPTS} 的核实记录）。用返回值判成败
     * 就会把"退成功"误判成"退失败"并打一条假告警。所以这里<b>无异常即成功</b>。
     *
     * <p>{@code ELSE 0} 而不是直接相减：补偿与另一台服的预占可能交错，硬减会得到负数，
     * 而负数已用额度等于<b>凭空放大</b>当日剩余额度 —— 方向是超发。
     *
     * <p>失败重试一次：单发币路径上的失败几乎都是短暂锁冲突（SQLite
     * {@code busy_timeout=5000} 到点 / MySQL 锁等待超时），立刻重试通常就过了；
     * 两次都不成才是真的需要人看。
     *
     * @return true = 已退回（或本来就没什么可退）；false = 两次都失败，调用方必须告警
     */
    private boolean releaseDailyCoinsInDb(UUID playerId, LocalDate date, long cents) {
        if (cents <= 0) {
            return true;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) {
            return false;
        }
        String sql = "UPDATE " + db.getTablePrefix() + "daily_coin_counters"
                + " SET cents = CASE WHEN cents >= ? THEN cents - ? ELSE 0 END"
                + " WHERE player_uuid = ? AND coin_date = ?";
        for (int attempt = 0; attempt < 2; attempt++) {
            Boolean ok = db.withConnection(conn -> {
                try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setLong(1, cents);
                    stmt.setLong(2, cents);
                    stmt.setString(3, playerId.toString());
                    stmt.setString(4, date.toString());
                    stmt.executeUpdate();
                }
                return Boolean.TRUE;
            }, Boolean.FALSE);
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本进程的日限账本是否需要「库侧原子预占」这条慢路径。
     *
     * @return {@code true} = 存在第二个写者（或测试强制），必须由库侧条件 UPDATE 判定
     */
    private boolean usesSharedDailyLedger() {
        Boolean forced = this.sharedDailyLedger;
        if (forced != null) {
            return forced.booleanValue();
        }
        DatabaseManager db = plugin.getDatabaseManager();
        return db != null && !db.isSQLite();
    }

    /**
     * R-44 fan-in：{@code progress.economy-disabled} 的 per-player 每自然日去重。
     * money.enabled=false 但 Vault 存在时，发币入口每天只提示一次，避免每答对一题刷屏。
     * 条目随自然日用 {@link LocalDate#equals} 判新鲜，跨天自动失效（无需清理）；
     * F1-24：quit 时经 {@link #unloadPlayer} 移除，防一次性玩家的 UUID 条目永久残留。
     */
    private final Map<UUID, LocalDate> economyDisabledNotified = new ConcurrentHashMap<>();

    /**
     * 「今天」的<b>唯一读取口</b>（可注入的 {@link Clock}）。
     *
     * <p><b>为什么要有这个缝</b>：本类三处「读今天」（{@link #notifyEconomyDisabledOncePerDay}、
     * {@link #getTodayCoinsEarnedCents}、{@link #addTodayCoinsEarned}）过去直接调
     * {@code LocalDate.now()}，而 {@code money.daily-cap} 的<b>核心不变量</b>恰恰是
     * 「跨自然日额度清零重计」—— 那条用例只能靠真的等到午夜（或 {@code Thread.sleep}
     * 24 小时）来触发，等于无法测试。这是<b>可测性</b>缺陷，不是行为缺陷：生产代码是对的。
     *
     * <p><b>为什么是字段而非常量</b>：测试需要把时钟「推进到明天」，方法级静态调用做不到。
     * 注入后 {@code RewardEngineDailyCapTest} 的跨天用例变成纯 JVM 可驱动。
     *
     * <p><b>默认行为必须逐字不变</b>：{@code Clock.systemDefaultZone()} 是
     * {@code LocalDate.now()} 的无参重载<b>内部本来就调用的那个时钟</b>（同一
     * {@code Clock.systemDefaultZone()} + {@code ZoneId}），所以生产路径上
     * 「{@code LocalDate.now(clock)}」与「{@code LocalDate.now()}」返回值恒等，
     * 只有测试把 clock 换掉时才分叉。
     *
     * <p><b>可变但 {@code volatile}</b>：测试在驱动用例前写入、生产路径只读；用 volatile
     * 是为了避免「写测试线程 → 读异步落库线程」之间的可见性争议（本类的发币链路会跨到
     * {@link #scheduleAsync} 的池线程）。
     */
    private volatile Clock clock = Clock.systemDefaultZone();

    /**
     * 仅供测试注入时钟；传 {@code null} 回落 {@link Clock#systemDefaultZone()}。
     *
     * <p>包级可见（不是 {@code public}）：继承 {@code ChatFeedbackManager.setWriteExecutor}
     * 那类测试口的同一姿态 —— 生产代码没有调用方，但同包测试可以直接调，
     * 不必为此把 {@code Clock} 暴露到 public API 上。
     *
     * <p>回落而不是抛 NPE：与 {@code setWriteExecutor(null)} 同口径，让「测试忘了传」
     * 退化成生产行为（跨天用例会因此红，而不是把整个类炸掉）。
     */
    void setClock(Clock clock) {
        this.clock = (clock == null) ? Clock.systemDefaultZone() : clock;
    }

    /** 「今天」的唯一读取口 —— 全类所有日期判定都必须经它，不得再写裸 {@code LocalDate.now()}。 */
    private LocalDate currentDate() {
        return LocalDate.now(clock);
    }

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
        LocalDate today = currentDate();
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
        LocalDate today = currentDate();
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
        LocalDate today = currentDate();
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
     * 金币门"部分发放"：给定拟发金额，返回在不突破 daily-cap 前提下<b>实际可发</b>的额度，
     * 以及这次判定所依赖的"今日已用"与"是否已在库侧预占"（见 {@link DailyCapHold}）。
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
     * 请求额 → min(天花板, 面值) → min(上一步, 剩余额度)。调用方拿到的永远是"真能发的钱"，
     * 这个契约在天花板触发时依然成立（见 {@link CappedPayout#capped()} 对"少发"的判定 ——
     * 天花板触发也是真实的"少发"，玩家确实只拿到钳后的钱）。
     *
     * <p><b>Round 30：判据分两条路，由 {@link #usesSharedDailyLedger()} 决定</b>
     * <ul>
     *   <li><b>单进程（SQLite，绝大多数部署）</b>：判据仍是 {@link #todayCoinsCache}，
     *       <b>零额外 IO</b>，返回值与改动前逐字节等价（放行额、提示时机、提示内容都不变）。</li>
     *   <li><b>多进程（共享 MySQL）</b>：判据下推到库（{@link #reserveDailyCoinsInDb}），
     *       放行即预占。于是"两个进程同时读到剩余 400"不再可能 —— 谁的 CAS 先落库谁发。
     *       库不可用时<b>降级</b>回内存判据（与"库抖动不该让日限彻底关掉"权衡后的选择，
     *       失败方向是回到改动前的行为，而不是新引入一个更宽的窗口）。</li>
     * </ul>
     *
     * <p><b>为什么玩家文案里的 {@code {earned}} 在多进程路径上必须用库值</b>：
     * 库侧预占返回的"放行后已用"是<b>唯一</b>与真实账本同源的数字。拿内存旧值渲染就是
     * 又一次"报给玩家的数字与真相不同源"（{@link CappedPayout} 记录的那类事故）。
     *
     * @param player the player about to be paid
     * @param amount the intended payout
     * @param source 发币入口的配置来源描述（仅进 WARNING，不参与任何算术）
     * @return 判据结果；{@link DailyCapHold#blocked()} 为 true 时调用方不得付款
     */
    private DailyCapHold clampToDailyCap(Player player, double amount, String source) {
        LocalDate today = currentDate();
        double requested = clampToPayoutCeiling(player, amount, source);
        // NaN 是唯一已经"失去数值语义"的输入：下游 Math.round(NaN) 得 0，而 0 会让
        // dailyCap<=0 的路径原样把 NaN 交回给 depositPlayer —— 一个 NaN 进入经济 provider
        // 会把玩家余额变成 NaN，之后加多少都是 NaN（永不可恢复）。这里统一按"别发"处理。
        if (Double.isNaN(requested)) {
            return DailyCapHold.none(today);
        }
        int dailyCap = plugin.getConfigManager().getMainConfig()
                .getInt("money.daily-cap", 500);
        if (dailyCap <= 0) {
            // 原实现此处 `return requested`（原样交回配置里的 double，含亚分精度），
            // 所以这里把 requested 原值带下去，而不是 /100.0 还原。
            return DailyCapHold.unlimited(today, requested);
        }
        long capCents = (long) dailyCap * 100;
        long amountCents = Math.round(requested * 100.0);

        if (usesSharedDailyLedger() && amountCents > 0) {
            DailyCapReserve reserve = reserveDailyCoinsInDb(
                    player.getUniqueId(), today, amountCents, capCents);
            if (!reserve.isUnavailable()) {
                long granted = Math.min(reserve.grantedCents(), amountCents);
                DailyCapHold hold = granted > 0
                        ? DailyCapHold.reserved(today, granted, reserve.usedBeforeCents())
                        : DailyCapHold.memory(today, 0L, reserve.usedBeforeCents());
                warnIfCapped(player, dailyCap, hold, amountCents);
                return hold;
            }
            // 库不可用 → 掉下去走内存判据（见方法 javadoc 的降级说明）。
        }

        // ---- 单进程路径：与改动前逐字节等价（含各分支的提示时机与顺序） ----
        long earnedCents = getTodayCoinsEarnedCents(player.getUniqueId());
        long remaining = capCents - earnedCents;
        if (remaining <= 0) {
            DailyCapHold hold = DailyCapHold.memory(today, 0L, earnedCents);
            // 改动前这一分支<b>无条件</b>发提示（不看请求额），照抄。
            notifyCapReached(player, dailyCap, hold);
            return hold;
        }
        long payCents = Math.min(amountCents, remaining);
        DailyCapHold hold = DailyCapHold.memory(today, payCents, earnedCents);
        if (payCents < amountCents) {
            notifyCapReached(player, dailyCap, hold);
        }
        return hold;
    }

    /**
     * 日限触发时的玩家提示（"额度已满"与"部分发放"共用同一条 i18n key，与改动前一致）。
     *
     * <p>抽出来是为了让 {@code {earned}} 走<b>判据同源</b>的那个数字：多进程路径下它来自
     * 库侧预占读到的"预占前已用"，单进程路径下它就是 {@code todayCoinsCache} 的值 ——
     * 两处各写一遍 {@code getTodayCoinsEarned} 必然漂移成"提示说已得 100、实际库里 400"。
     *
     * <p>{@code {earned}} 刻意是<b>本次放行之前</b>的已用额度：改动前那条文案读的就是
     * 尚未累加本次的 {@code getTodayCoinsEarned}，改成"放行后"会让所有既有提示的数字
     * 整体偏大一笔 —— 那是没人要的副作用，会让"额度已满"的玩家看到 {@code earned > cap}。
     */
    private void notifyCapReached(Player player, int dailyCap, DailyCapHold hold) {
        double earned = (hold != null && hold.earnedCents() >= 0)
                ? hold.earnedBeforeCoins()
                // 已用额度不可知 —— 回落内存值而不是渲染一个哨兵负数。
                : getTodayCoinsEarned(player.getUniqueId());
        String msg = plugin.getMessageUtil().getPlayerMessage(player,
                "progress.daily-cap-reached",
                "cap", String.valueOf(dailyCap),
                "earned", formatCoins(earned));
        if (msg != null && !msg.isEmpty()) {
            player.sendMessage(msg);
        }
    }

    /**
     * 多进程路径的提示判定：只有真正"少发"才提示（{@code granted < requested}）。
     *
     * <p><b>为什么必须与单进程路径分开</b>：旧实现在"额度已满"分支<b>无条件</b>发提示，
     * 而"部分发放"分支的条件是 {@code pay < requested}。多进程路径的 {@code granted}
     * 来自库侧预占，语义上只有这两种情况，所以这里用"少发才提示"一条判据即可 ——
     * 单进程路径则必须<b>逐字复刻</b>旧的两个分支，否则 {@code amount=0.004}
     * （{@code amountCents==0}）撞上"额度已满"时会从"照旧提示"变成"静默"，
     * 而那条路径在纯 JVM 里可达（{@code amount > 0} 但 {@code Math.round(amount*100)==0}）。
     */
    private void warnIfCapped(Player player, int dailyCap, DailyCapHold hold,
                              long amountCents) {
        if (hold == null || hold.grantedCents() >= amountCents) {
            return; // 全额放行：与改动前一样静默
        }
        notifyCapReached(player, dailyCap, hold);
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
        return payCappedReward(getEconomy(), player, amount, DEFAULT_PAYOUT_SOURCE,
                "deposit capped reward");
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

        double paid = payCappedReward(eco, player, amount, CORRECT_ANSWER_SOURCE,
                "deposit money");
        if (paid > 0) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-reward",
                    "amount", eco.format(paid));
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

        double paid = payCappedReward(eco, player, totalAmount, SESSION_BONUS_SOURCE,
                "deposit session bonus");
        if (paid > 0) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-session-bonus",
                    "amount", eco.format(paid));
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

        double paid = payCappedReward(eco, player, amount, REVIEW_CORRECT_SOURCE,
                "deposit review money");
        if (paid > 0) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "progress.money-review-reward",
                    "amount", eco.format(paid));
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
        double paid = payCappedReward(eco, player, partial, STOPPED_SESSION_SOURCE,
                "deposit stopped-session bonus");
        if (paid > 0) {
            String msg = plugin.getMessageUtil().getPlayerMessage(player,
                    "word-learning-gui.stopped-partial-reward",
                    "answered", String.valueOf(answered),
                    "total", String.valueOf(total),
                    "amount", eco.format(paid));
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(msg);
            }
        }
    }
}
