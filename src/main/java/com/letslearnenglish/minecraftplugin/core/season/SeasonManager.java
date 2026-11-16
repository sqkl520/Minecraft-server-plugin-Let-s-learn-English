package com.letslearnenglish.minecraftplugin.core.season;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager.SeasonRewardRow;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager.SeasonRow;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.SchedulerGuard;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * SeasonManager — 赛季系统（域 5 批次 A）核心逻辑：窗口计算 / 结算 / 奖励台账与补发。
 *
 * <p><b>窗口模型（plan-season §1.2）</b>：锚点 + 固定时长（本地日粒度）。
 * {@code season.anchor-date}（默认 {@value #DEFAULT_ANCHOR}）+ {@code season.duration-days}
 * （默认 {@value #DEFAULT_DURATION_DAYS}）纯函数推导：第 N 季窗口 =
 * {@code [anchor + (N-1)*duration, anchor + N*duration - 1]}（闭区间）。任何时刻
 * （启服/午夜/PAPI）重算结果一致，停服漂移不累计误差。{@code today < anchor}
 * （含锚点在未来的首次误配）→ {@code currentSeasonId = 0}，全系统视为"赛季未开启"。
 *
 * <p><b>赛季 = 时间窗口视图，零数据重置（plan-season §1.3）</b>：赛季榜直接复用
 * {@code PlayerDataStore.getRankedPlayersForPeriod}（player_sessions 按日期窗聚合），
 * 无独立赛季累加分表。唯一新表是结算台账（seasons 头 + season_rewards 行）。
 *
 * <p><b>多服单发保护（plan-season §1.4）</b>：同一 MySQL 库的多台子服各自触发结算时，
 * 用 seasons 表条件 UPDATE 抢占结算权（{@link DatabaseManager#tryClaimSettlement}），
 * 只有 affected==1 的服写台账/发奖/广播，其余服静默跳过。
 *
 * <p><b>线程模型（plan-season §3.2，F2-11 教训）</b>：
 * <ol>
 *   <li>调度触发（主线程/异步均可）→ 只做日期纯计算与派发；</li>
 *   <li>结算 IO（异步任务）：查聚合 / 抢占 / 写台账；防重入由 {@link #settleInFlight} 保证；</li>
 *   <li>回主线程：全服广播（逐收件人语言渲染）、Vault 发钱、玩家消息；</li>
 *   <li>join 补发：异步读 unclaimed → 主线程条件标记 + 发钱（对齐
 *       {@code SocialManager.notifyPendingCheers} 的"异步查 → 主线程发"模式）。</li>
 * </ol>
 *
 * <p><b>经济门裁决（plan-season §1.4）</b>：赛季奖励不走 {@code money.daily-cap}
 * （{@code depositCappedReward} 会把大奖 clamp 到当日剩余额度），走直发：
 * {@code eco.depositPlayer} + {@code DatabaseManager.recordCoinsEarned}（lifetime 记账），
 * <b>不注入</b> {@code todayCoinsCache}（不挤占当日答题额度）。理由：赛季奖励一次性、
 * 有排名门槛、有台账幂等防重，与"可重复的日常收益"语义不同。无 Vault 时静默降级：
 * 只广播不发钱，台账行<b>保持 pending</b>（不标记不销毁——Vault 就位后任意进服/
 * 结算路径自动补发；与全项目"无经济则静默降级"一致且不丢玩家奖励）。
 *
 * <p><b>发钱前先标记（对齐 CheckInManager 先例）</b>：{@code markSeasonRewardClaimed}
 * 条件 UPDATE（{@code WHERE claimed=0}）affected==1 才开始发放——崩溃/双击/双服竞态
 * 都不可能二次发放（宁可极小概率"标记后崩溃少发一次"，不可重复发放，对齐 R-02 教训）。
 *
 * <p><b>R-16（审计自查，非用户反馈）：上面这段不变式有一个例外，必须显式写下来。</b>
 * 金币总闸 {@code money.enabled=false}（出厂默认就是 false）时，台账行照写、标记照做、
 * 广播照发，<b>只是不发钱</b>——此时"标记了但没付"是<b>预期终态</b>，不是需要回滚的失败。
 * 因此 {@link #deliverRows} 的分流判据是「本行该不该付钱」而不是「有没有付成功」：
 * 该付而没付 → 回滚 pending（可重试）；不该付 → 标记留 1 并结束（<b>不可</b>回滚，
 * 否则该行会永久 pending、每次进服/每次结算都被重试一遍，见 {@link #isMoneyPayable()}）。
 * 回滚失败的处置见 {@link #rollbackClaimAfterFailedPayment}。
 *
 * <p><b>R-16 行为表（{@code season.enabled=true} 且 {@code season.rewards.enabled=true}
 * 且 Vault 在的前提下，结算一季时的可观察结果）</b>：
 * <table border="1">
 *   <caption>金币总闸对赛季结算的影响</caption>
 *   <tr><th>配置</th><th>台账行写入</th><th>全服广播</th><th>金币到账</th>
 *       <th>台账行留在 pending</th></tr>
 *   <tr><td>{@code money.enabled: true}</td><td>是</td><td>是</td><td>是</td><td>否</td></tr>
 *   <tr><td>{@code money.enabled: false}</td><td><b>是</b></td><td>是</td><td><b>否</b></td>
 *       <td><b>否</b></td></tr>
 * </table>
 * 中间两格是本条修复的要点：台账是<b>账</b>（记录"这一季谁该拿多少"），与"钱有没有
 * 出去"是两件事；而 pending 与否由「该不该付」决定，不由「有没有付成功」决定。
 */
public class SeasonManager {

    /** 结算聚合与快照的 top 行数（plan-season §3.1：top10 + top_snapshot JSON）。 */
    private static final int TOP_N = 10;
    /** 赛季榜内存快照容量（对齐 Leaderboard.DEFAULT_CACHE_LIMIT 的展示语义）。 */
    private static final int CACHE_LIMIT = 50;
    /** 锚点缺省（与 config.yml season.anchor-date 同源；配置缺失/非法时回落）。 */
    private static final String DEFAULT_ANCHOR = "2026-11-02";
    /** 单季时长缺省（天）。 */
    private static final int DEFAULT_DURATION_DAYS = 30;

    /** top_snapshot JSON 序列化：List&lt;Map&gt; 结构（对 Gson 版本零假设）。 */
    private static final Gson GSON = new Gson();

    private final LetsLearnEnglish plugin;

    /** 赛季榜 volatile 快照（整体替换，读侧无锁；结算后 / 5 分钟班车刷新）。 */
    private volatile List<SeasonRankRow> topCache = Collections.emptyList();

    /** 结算防重入：同一时刻至多一个结算扫描在途（onEnable 与午夜两触发点可能相邻）。 */
    private final AtomicBoolean settleInFlight = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    //  R11-P：释放抢占失败的重试面（缺陷：release 失败 → settled_at 残留 →
    //  游标 MAX(season_id) WHERE settled_at IS NOT NULL 跳过该季 → 奖励永久丢失）
    // ------------------------------------------------------------------

    /** 同轮释放重试总次数（含首次尝试）——只在异步线程使用，见 {@link #releaseWithRetry}。 */
    static final int RELEASE_ATTEMPTS = 3;
    /** 同轮重试的退避毫秒（索引 0 = 第 1 次重试前；末项复用）。 */
    private static final long[] RELEASE_BACKOFF_MS = {50L, 200L};
    /** 后台 drain 自动重排次数上限；超过后条目<b>保留</b>（继续阻挡游标）但不再自动重排。 */
    static final int MAX_DRAIN_ATTEMPTS = 5;
    /** 首次后台 drain 延迟（tick；60s——连接抖动自愈通常在秒级恢复）。 */
    private static final long DRAIN_DELAY_TICKS = 60L * 20L;

    /**
     * R-16：「他服的抢占已经陈旧到可以当作死服遗留」的年龄阈值（毫秒；15 分钟）。
     *
     * <p><b>判的是 {@code seasons.settled_at} 的年龄</b>（库里写入 claim 的墙钟毫秒），
     * 只在 {@code claim_owner} 非本服时使用：{@code claim_owner} 是「谁抢的」，
     * {@code settled_at} 是「什么时候抢的」—— 单看前者只能知道"不是我"，
     * 分不清"他正在结算"和"他结算到一半就崩了"，后者必须靠年龄。</p>
     *
     * <p><b>为什么是 15 分钟</b>：一次正常结算在秒级完成（一张 top10 榜 + 一批台账行，
     * 单连接同步写），秒级抖动都算宽裕。能把 claim 拖过 15 分钟的只有两种情形：
     * ① 那个进程已经死了（崩溃/被 kill/被 {@code /lea reload} 换掉），它的 claim 从此
     *    无人认领 —— 这正是本常量要回收的对象；
     * ② 共享库本身停摆十几分钟（MySQL 长时间不可达）。此时那张 live claim 的主人也写不动
     *    任何东西；等它恢复，它自己的 sweep 会重入本方法的欠账路径重新处理该季，
     *    而释放是幂等且不丢钱的（行级 {@code claimed} 条件 UPDATE），多一次重结算
     *    不会重复发钱。
     * 反过来把窗口调小（如 60s）会在 MySQL 慢查询/长 GC 时误伤 live claim，
     * 调大（如 24h）会让"死服遗留"的季白卡一整天（游标被它钉住，后续季都不结算）。</p>
     */
    static final long FOREIGN_CLAIM_STALE_MS = 15L * 60L * 1000L;

    /**
     * 释放失败待重试：{@code seasonId → 本次 claim 的 settled_at}（fencing token 原值）。
     * <p>条目存在 = 该季的 {@code settled_at} 仍躺在库里且<b>不是</b>我们想要的 NULL，
     * 因此 {@link #runSettleSweep} 一律拒绝把游标推过这些季（见其起点回退与循环内的
     * {@link #releaseWithRetry} 早退）。
     * <p><b>R12d-F7：这张表跨重启由 {@link #rebuildPendingReleases} 从库里重建</b>
     * （数据源 = {@code seasons.ledger_pending}，与 claim 同一条 UPDATE 写入的持久标记）。
     * 在此之前它是纯内存的，重启 / {@code /lea reload} 后恒空，脏 claim 却留在库里，
     * 游标一跃就永久丢奖。
     * <p>并发：写入方只有结算 sweep（{@code settleInFlight} 保证串行），读取方是后台
     * drain 任务（另一个异步线程）——故用 ConcurrentHashMap。
     */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Long> pendingReleaseRetries =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 每季已重排的后台 drain 次数（仅用于封顶，不决定成败）。 */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> releaseDrainAttempts =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 后台 drain 只允许一个在途（防反复失败时任务堆积）。 */
    private final AtomicBoolean releaseDrainScheduled = new AtomicBoolean(false);

    /**
     * R-16 后续修正：他服 lives claim 的「到期复查」只允许一个在途。
     *
     * <p><b>为什么需要这个闸</b>：{@link #rebuildPendingReleases} 可能一轮扫出多季都在
     * 别人手里，也可能被前半段的其它路径反复调用。每个"还新"的季各排一个定时器会
     * 堆出 N 个几十分钟后才跑的异步任务（多服共享库 + 长窗口时 especially），
     * 而它们的作用完全相同 —— 到期后再跑一轮 sweep。<b>一季只需一次</b>。</p>
     *
     * <p>与 {@link #releaseDrainScheduled} 分开而不是复用：两者语义不同（drain 是
     * "本服释放失败要重试"，本闸是"他服的 claim 到期了要重新裁定"），
     * 复用一个布尔会让两条路径互相吞掉对方的排程。</p>
     */
    private final AtomicBoolean foreignClaimRecheckScheduled = new AtomicBoolean(false);

    /**
     * R-16 后续修正：到期复查因"已有一轮 sweep 在飞"而重排的累计次数（见
     * {@link #FOREIGN_CLAIM_RECHECK_MAX_REARMS}）。成功把 sweep 排上后归零。
     */
    private final java.util.concurrent.atomic.AtomicInteger foreignClaimRecheckRearms =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * R-16 后续修正：到期复查的<b>最短</b>等待（毫秒；1 分钟）。
     *
     * <p>剩余宽限期可能只剩几毫秒（我们正好在他服写 claim 后 14:59.9 看到它），
     * 那样排出来的任务等于立刻重跑一轮完整 sweep —— 而 {@code settled_at} 几乎肯定
     * 还没跨过阈值（每次 sweep 都要重新读库聚合榜单，代价不小），于是又排一次，
     * 变成抖动的忙循环。取 1 分钟：最多多等一分钟，不会多跑无意义的一轮。</p>
     */
    private static final long FOREIGN_CLAIM_RECHECK_MIN_MS = 60L * 1000L;

    /**
     * R-16 后续修正：到期复查因「已有一轮 sweep 在飞」而重排的次数上限。
     *
     * <p>{@link #settleIfNeededAsync} 有防重入闸（{@code settleInFlight}），所以当复查
     * 撞上进行中的 sweep 时它是<b>空转</b>的 —— 必须再排一次，否则这次复查就丢了。
     * 但"库停摆 → sweep 卡住几十分钟"是真实存在的失败态，无上限重排会退化成一个
     * <b>每分钟一次、永不终止</b>的定时器（而本方法本来就是为了不依赖外部触发，
     * 那种定时器等于把"没有自愈"换成了"无限自旋"）。
     * 取 5：足够熬过正常的库抖动，也足够在一轮 sweep 卡死时自己停下来并留一条可操作的
     * WARNING —— 那一季<b>不会丢</b>，下次启服或季边界仍会重新裁定。</p>
     */
    private static final int FOREIGN_CLAIM_RECHECK_MAX_REARMS = 5;

    /**
     * R12e-G2：每季「由 {@link #rebuildPendingReleases} 从库里重新发现、并因此重结算」
     * 的次数（<b>仅本进程内</b>计数）。
     *
     * <p><b>为什么需要封顶</b>：{@code ledger_pending} 的清零由
     * {@code DatabaseManager.clearSeasonLedgerPendingDetailed} 负责，它现在会把
     * "标记清不掉"（{@link DatabaseManager.LedgerClearResult#FAILED}）如实报回调用方，
     * 但<b>报出来也不能让标记消失</b> —— 那一行的值仍然是 1。于是"台账已完整但标记清不掉"
     * 这个状态会在每一轮 sweep 里被重建逻辑重新发现一次 → 再释放、再 claim、再重写台账、
     * 再广播。这个循环<b>没有自愈条件</b>：只要那一条 UPDATE 一直失败，它就一直转。
     * （三态化之前连"报回来"这一步都没有：方法 {@code void}，失败只记 WARNING。）
     * 实测（真 SQLite + 精确故障注入只拦该 UPDATE）：第 1/2/3 轮 sweep 全部重结算并
     * 重播，{@code ledger_pending} 恒为 1，直到进程重启也照样继续。
     *
     * <p><b>为什么这是真缺陷而不只是"多一次广播"</b>：重建出来的台账按<b>当前</b>配置
     * 生成。若管理员在两次 sweep 之间把某个原本为 0 的档位调大（例如
     * {@code season.rewards.participated} 从 0 改成 5），下一次重结算就会为那个
     * <b>早已关闭</b>的赛季插出<u>新的</u>参与奖行（{@code INSERT OR IGNORE} 挡不住
     * 不存在的行），并立刻发真钱。实测：先 0 结算 → 改成 5 → 下一轮 sweep，
     * 该季台账从 1 行变 2 行、实付从 15000 分变 16500 分。
     *
     * <p><b>封顶取值</b>：允许 {@value #MAX_REBUILD_RESETTLES} 次。留这一次余量是为了
     * 不把"那一条 UPDATE 只是偶发抖动、下一轮就好了"的正常恢复路径堵死（实测：故障一解除，
     * 第 2 轮就能把标记清掉并收尾）；第二次仍然失败即视为「库写不进去」的持续故障，
     * 停止自动重结算并留一条可操作的 SEVERE。与 {@link #MAX_DRAIN_ATTEMPTS} 同族：
     * <b>有界</b>是这里的全部价值。
     *
     * <p><b>计数口径 = 本实例（每个进程生命周期）</b>，刻意不做成静态。理由：
     * {@code SeasonManager} 在 {@code /lea reload} 与重启时都是新建的（见
     * {@code LetsLearnEnglish.enableAll} 的"每次 enable 全新建"约定），于是
     * 计数也随之归零 —— 那正是本修复<b>不去覆盖</b>的残余面：一次 reload / 重启最多
     * 多结算一次。这个残余量与修复前「每个进程内无限重结算」不是一个量级，而且
     * reload 本身是运维的离散动作。做成静态能连这个残余也封掉，但会让单测之间共享
     * 计数、互相污染，收益/风险不成比例。</p>
     */
    static final int MAX_REBUILD_RESETTLES = 1;

    /** R12e-G2：本进程内每季被 rebuild 重新发现并重结算的次数（见 {@link #MAX_REBUILD_RESETTLES}）。 */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> rebuildResettleCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** R12e-G2：防止"已封顶"那条 SEVERE 每轮 sweep 重复刷屏（每季一次）。 */
    private final java.util.Set<Integer> rebuildCapWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * R15（后继审计 F4）：无活动赛季导致发放被跳过的告警，每 JVM 只打一次。
     *
     * <p>这条路径是<b>每个玩家每次进服</b>都会走到的（{@code claimPendingRewards} 在
     * {@code PlayerJoinListener} 里），逐次打日志可以直接把日志刷满；但完全静默又会让
     * 「台账里压着已结算赛季的钱」这件事无人知晓。取一次性的中间态。
     */
    private final java.util.concurrent.atomic.AtomicBoolean warnedNoActiveSeason =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * R-16（审计自查）：已就「本季奖励入台账但不发钱（金币总闸关闭）」提示过的赛季号。
     *
     * <p>一次性而非逐行：一季最多 10 + N 行（参与奖可上万人），逐行就是刷屏；
     * 而完全静默会让"结算了、播了、玩家没拿到钱"没有任何解释。用赛季号做键，
     * 同一个赛季被重结算/多服补发时只提示一次（与 {@code warnedNoActiveSeason}、
     * {@link #rewardClampWarned} 同口径的一次性中间态）。
     * 写入方是主线程发放核，读取方同上，但重结算可能来自异步 sweep → 用并发集合。
     */
    private final Set<Integer> moneySuppressedWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * R-16（审计自查）：金币总闸"读不出来"（进度管理器未接线）的告警，每 JVM 只打一次。
     *
     * <p>与 {@link #moneySuppressedWarned} 分开：那个是"配置说关"（正常状态、按赛季记 INFO），
     * 这个是"根本没读到配置"（接线问题、SEVERE 级病因、只值得说一次）。
     * 两者合用一个标志会让其中一条永远不出现。
     */
    private final AtomicBoolean moneyGateUnavailableWarned = new AtomicBoolean(false);

    /**
     * 释放抢占的三种结局（R11-P：原为 {@code void}，把「连不上库」和「没有行匹配」
     * 混在一条日志里，运维无法判断该不该手工修）。
     *
     * <p>包级可见（非 public）：仅供本类与同包测试消费，对外 API 面不变。
     */
    enum ReleaseOutcome {
        /** 本次抢占已退回 NULL —— 下一轮 sweep 会重做本季。 */
        RELEASED,
        /**
         * 条件 UPDATE 命中 0 行：{@code settled_at} 已不是<b>本次</b> claim 的时间戳。
         * 可能是他服已释放并重新抢占（本季的成败已归他方），也可能行被删。
         * <b>不重试</b>：再试也永远匹配不上，而且他方正在结算时重试是有害的。
         */
        NOT_HELD,
        /**
         * 条件 UPDATE 没能落到库上（{@code withConnection} 兜底 -1）：连接不可用或语句报错。
         * <b>必须重试</b>，且重试未成功前游标不得前进。
         */
        CONNECTION_FAILED
    }

    /** 旅程验收收口：赛季"开而不动"启动 WARNING 一次性标记（防刷屏）。 */
    private volatile boolean dormancyWarned = false;
    /** 锚点配置非法只告警一次（点读式配置，防每次调用刷屏）。 */
    private final AtomicBoolean anchorWarned = new AtomicBoolean(false);

    /**
     * 赛季窗口（1-based，含首尾闭区间；日期比较口径与 player_sessions.session_date 同构）。
     *
     * @param id    赛季号（1-based）
     * @param start 首日（含）
     * @param end   末日（含）
     */
    public record SeasonWindow(int id, LocalDate start, LocalDate end) { }

    /**
     * 赛季榜一行（<b>冻结接口</b>，3A-4 的 PAPI / GUI 消费）。
     *
     * @param rank  名次（1-based）
     * @param uuid  玩家
     * @param name  展示名（DB 侧 player_name，空回落 uuid 字符串）
     * @param score 窗口内学习分合计
     */
    public record SeasonRankRow(int rank, UUID uuid, String name, long score) { }

    public SeasonManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //  门槛与配置（点读式，/lea reload 即时生效）
    // ============================================================

    /**
     * 赛季系统总门（<b>冻结接口</b>）：{@code season.enabled}（默认 false——运营属性，
     * 先关）<b>且</b>当前季已开启（{@code currentSeasonId(today) >= 1}；锚点在未来时
     * 同样休眠，plan-season §1.2）。false 时：无结算调度、无广播、cache 清空、
     * PAPI/展示返回空（真 gating，非静默 no-op）。
     */
    public boolean isEnabled() {
        if (!isSeasonModuleEnabled()) {
            return false;
        }
        return currentSeasonId(LocalDate.now()) >= 1;
    }

    /**
     * R15（后继审计 F4）：<b>只</b>看 {@code season.enabled}，不看「当前是否有活动赛季」。
     *
     * <p>拆出来是因为这两个条件在<b>发放路径</b>上的语义完全不同：
     * 模块关闭 = 服主刻意关掉，静默不发是预期；「无活动赛季」= 锚点配置异常，
     * 而台账里可能压着已结算赛季的钱 —— 后者必须留痕（见 {@code deliverPending}）。
     */
    private boolean isSeasonModuleEnabled() {
        return plugin.getConfigManager().getMainConfig().getBoolean("season.enabled", false);
    }

    /** 开始/结束是否全服广播（{@code season.announce}，默认 true）。 */
    private boolean isAnnounceEnabled() {
        return plugin.getConfigManager().getMainConfig().getBoolean("season.announce", true);
    }

    /** 结算奖励是否入台账（{@code season.rewards.enabled}，默认 true；false 时只结算不发奖）。 */
    private boolean isRewardsEnabled() {
        return plugin.getConfigManager().getMainConfig().getBoolean("season.rewards.enabled", true);
    }

    /**
     * R-16（审计自查）：<b>金币总闸</b> —— 本行该不该真的 {@code depositPlayer}。
     *
     * <h2>修的缺陷</h2>
     * 全项目其余发币点（{@code LearnCommand} / {@code DailyChallengeManager} /
     * {@code CheckInManager} / {@code MiscGUIListener}）都走
     * {@link RewardEngine#isMoneyEnabled()}（= {@code progress-system.rewards.enabled
     * && progress-system.rewards.types.money.enabled}），<b>唯独赛季发放没有</b>
     * （{@code payReward} 只看 {@code eco == null}）。而
     * {@code progress-system.rewards.types.money.enabled} <b>出厂默认 false</b>：
     * 服主按默认配置留着 Vault、开着 {@code season.enabled}，每个午夜就会真实入账 ——
     * 而 top1 的 5000 是全插件最大的一笔单发奖，日志里没有任何一条说明它为何发出。
     *
     * <h2>为什么必须在这里判，而不是 {@code payReward} 里 return 0</h2>
     * {@code payReward} 返回 0 会被 {@link #deliverRows} 读成"Vault 在但转账失败"，
     * 转而 {@code unmarkSeasonRewardClaimed} 把该行退回 pending —— 于是它<b>永远</b>
     * 发不出去、每次进服/每次结算都重试一遍，比不发还糟。
     * 「不该付」是<b>策略</b>，「付失败」是<b>故障</b>，两者的下游处置完全相反，
     * 所以门必须开在标记与回滚的<b>分流处</b>（见 {@link #deliverRows}）。
     *
     * <h2>为什么不自己直读 {@code money.enabled}</h2>
     * 那会漏掉"奖励伞"（{@code progress-system.rewards.enabled}）：服主关伞时其它发币点
     * 全停、只有赛季继续打钱。因此委托 {@link RewardEngine#isMoneyEnabled()}，
     * 保持全插件同一个金币真相源（该方法的 javadoc 明写"统一改走本方法"）。
     *
     * <p><b>与 {@link #isRewardsEnabled()} 的区别</b>（两个不同的键，别混）：
     * {@code season.rewards.enabled} 决定<b>台账写不写</b>（关 = 这一季压根不记账）；
     * 本方法决定<b>钱发不发</b>（关 = 照记账、照广播、不发钱）。
     *
     * <p>兜底语义：进度管理器尚未就绪（{@code null}）时按<b>关闭</b>处理并留一条 WARNING ——
     * 赛季在 {@code onEnable} 里晚于进度管理器构造，正常时序下不会走到；真走到了也
     * 只可能少发（行仍是 pending，恢复后自动补发），不会在配置关闭时照发真钱。
     * 但"配置读不出来"与"配置说关"必须能分开：否则一次接线错误会让整季奖励静默不发，
     * 而日志与"服主自己关了"完全同形（与全类"未知 ≠ 没有"的口径一致）。
     */
    private boolean isMoneyPayable() {
        if (plugin.getProgressManager() == null
                || plugin.getProgressManager().getRewardEngine() == null) {
            if (moneyGateUnavailableWarned.compareAndSet(false, true)) {
                plugin.getLogger().warning("Season reward payout gate is UNAVAILABLE (the"
                        + " progress manager / reward engine is not wired yet), so season coins"
                        + " are treated as DISABLED for now. Ledger rows stay pending (claimed=0)"
                        + " and are paid automatically on the player's next join once the gate is"
                        + " readable. If this warning appears on a healthy server, the plugin"
                        + " wiring order is broken — check onEnable. (Prints once per JVM.)");
            }
            return false;
        }
        return plugin.getProgressManager().getRewardEngine().isMoneyEnabled();
    }

    /** 锚点日期（点读式；缺失/非法回落默认并告警一次）。 */
    private LocalDate anchorDate() {
        String raw = plugin.getConfigManager().getMainConfig()
                .getString("season.anchor-date", DEFAULT_ANCHOR);
        if (raw == null || raw.isBlank()) {
            raw = DEFAULT_ANCHOR;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            if (anchorWarned.compareAndSet(false, true)) {
                plugin.getLogger().warning("Invalid season.anchor-date '" + raw
                        + "', falling back to " + DEFAULT_ANCHOR);
            }
            return LocalDate.parse(DEFAULT_ANCHOR);
        }
    }

    /** 单季时长（天；非法/<=0 钳到 1，防除零）。 */
    private int durationDays() {
        return Math.max(1, plugin.getConfigManager().getMainConfig()
                .getInt("season.duration-days", DEFAULT_DURATION_DAYS));
    }

    // ============================================================
    //  窗口计算（纯函数，可随时调用——PAPI 直接消费）
    // ============================================================

    /**
     * 当前季 ID（<b>冻结接口</b>）。{@code date < anchor} → 0（赛季未开启，
     * 全系统休眠）。1-based。
     */
    public int currentSeasonId(LocalDate date) {
        return seasonId(date, anchorDate(), durationDays());
    }

    /**
     * 纯静态推导（本类实例方法委托至此；脱离 Bukkit 可直接冒烟）：
     * {@code floor(DAYS.between(anchor, date) / duration) + 1}。
     */
    static int seasonId(LocalDate date, LocalDate anchor, int durationDays) {
        if (date == null || anchor == null || durationDays <= 0 || date.isBefore(anchor)) {
            return 0;
        }
        return (int) (ChronoUnit.DAYS.between(anchor, date) / durationDays) + 1;
    }

    /**
     * 第 {@code id} 季窗口（<b>冻结接口</b>，id 1-based）：闭区间
     * {@code [anchor + (id-1)*duration, anchor + id*duration - 1]}。
     */
    public SeasonWindow windowOf(int id) {
        return windowFor(id, anchorDate(), durationDays());
    }

    /** 纯静态窗口推导（同 {@link #seasonId} 的可冒烟形态）。 */
    static SeasonWindow windowFor(int id, LocalDate anchor, int durationDays) {
        LocalDate start = anchor.plusDays((long) (id - 1) * durationDays);
        LocalDate end = start.plusDays(durationDays).minusDays(1);
        return new SeasonWindow(id, start, end);
    }

    /**
     * 当前季剩余毫秒（<b>冻结接口</b>）：至本季末日的 23:59:59.999（系统时区）。
     * 未开启（id&lt;1）返回 0。
     */
    public long millisUntilEnd() {
        int id = currentSeasonId(LocalDate.now());
        if (id < 1) {
            return 0L;
        }
        SeasonWindow window = windowOf(id);
        long endMillis = window.end().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        return Math.max(0L, endMillis - System.currentTimeMillis());
    }

    // ============================================================
    //  赛季榜缓存（volatile 快照；3A-4 的 PAPI/GUI 读缓存零查库）
    // ============================================================

    /**
     * 赛季榜快照前 {@code limit} 行（<b>冻结接口</b>）：零 DB 读（plan §1.7
     * 不复制广场周/月榜"开界面主线程查库"旧模式）。未启用/空态返回空列表。
     */
    public List<SeasonRankRow> getCurrentTopCached(int limit) {
        List<SeasonRankRow> snapshot = topCache;
        if (limit <= 0 || snapshot.isEmpty()) {
            return Collections.emptyList();
        }
        return limit >= snapshot.size()
                ? List.copyOf(snapshot)
                : List.copyOf(snapshot.subList(0, limit));
    }

    /**
     * 内存快照内名次（<b>冻结接口</b>）：未上榜（含快照外/CACHE_LIMIT 之外）
     * 返回 -1（与 Leaderboard 的 NOT_RANKED 同口径）。
     */
    public int getRankOf(UUID playerId) {
        if (playerId == null) {
            return -1;
        }
        for (SeasonRankRow row : topCache) {
            if (row.uuid().equals(playerId)) {
                return row.rank();
            }
        }
        return -1;
    }

    /**
     * 当前季剩余天数（<b>冻结接口</b>，单一真相源——W3A tester L4 收口：原
     * LeaderboardGUI/LLEPapiExpansion 各有一份复制实现，现两处改走本方法）：
     * 闭区间 [start,end] 语义下含今天的 +1（首日=整季天数、末日=1）；未开启返回 0。
     */
    public long daysLeft() {
        LocalDate today = LocalDate.now();
        int id = currentSeasonId(today);
        if (id <= 0) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(today, windowOf(id).end()) + 1;
    }

    /**
     * 可用性单判据（<b>冻结接口</b>）：null 安全 + 已启用（isEnabled 已折叠
     * "enabled=false" 与"锚点在未来 id=0"两种休眠）。原 LeaderboardGUI 与
     * LLEPapiExpansion 各有一份等价实现，tester L4 收口后统一走此方法。
     */
    public static boolean isAvailable(SeasonManager season) {
        return season != null && season.isEnabled();
    }

    /** 异步刷新赛季榜快照（5 分钟班车挂载点 + 结算收尾共用；未启用时清空即返）。 */
    public void refreshCacheAsync() {
        runAsync(this::refreshCacheSync);
    }

    private void refreshCacheSync() {
        if (!isEnabled()) {
            topCache = Collections.emptyList();
            return;
        }
        int id = currentSeasonId(LocalDate.now());
        SeasonWindow window = windowOf(id);
        List<LeaderboardEntry> entries = plugin.getPlayerDataStore()
                .getRankedPlayersForPeriod(CACHE_LIMIT, window.start(), window.end());
        List<SeasonRankRow> fresh = new ArrayList<>(entries.size());
        for (LeaderboardEntry entry : entries) {
            fresh.add(new SeasonRankRow(entry.rank, entry.playerId, entry.playerName, entry.score));
        }
        topCache = List.copyOf(fresh);
    }

    // ============================================================
    //  结算（onEnable 启动异步 + 午夜跨季边界，两触发点共用；幂等）
    // ============================================================

    /**
     * 结算扫描（幂等，两触发点共用——plan-season §3.2）。
     *
     * <p>游标 = {@code MAX(season_id) WHERE settled_at IS NOT NULL}，目标 =
     * {@code currentSeasonId(today) - 1}，逐季结算 {@code cursor+1 .. 目标}
     * （长停服跨多季逐季补；无数据季只写 settled 行不发奖）。<b>首次启用语义</b>
     * （seasons 表为空）：不追既往——只把当前季写成"已建立、未结算"的行即返回，
     * 防首次开启即大规模补发历史奖。后续扫描的起点 = 表内最小 season_id
     * （行只会由本方法建立，等价于"启用时刻的季"），保证只结算真正跨过的季。
     *
     * <p>防重入：执行中再次调用直接返回（onEnable 与午夜触发可能相邻）。
     * IO 全在异步任务；抢占成功才回主线程广播/发放在线者。
     */
    public void settleIfNeededAsync() {
        if (!isEnabled()) {
            // 旅程验收收口（赛季"开而不动"静默陷阱）：enabled=true 但锚点在未来时
            // 全系统休眠（isEnabled=false 是正确语义），但管理员会误判"功能没做"——
            // 启动时打一次 WARNING 说明原因与修正方法（dormancyWarned 防刷屏）。
            if (!dormancyWarned && plugin.getConfigManager().getMainConfig()
                    .getBoolean("season.enabled", false)) {
                dormancyWarned = true;
                plugin.getLogger().warning("season.enabled=true but the current season has not "
                        + "started yet (anchor-date is in the future or invalid) — season "
                        + "features stay dormant. Check season.anchor-date in config.yml.");
            }
            return;
        }
        if (!settleInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    runSettleSweep();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Season settle sweep failed", e);
                } finally {
                    settleInFlight.set(false);
                    // 结算后（或无事可做时）都重刷快照——启动路径的新赛季空态由此就位。
                    //
                    // R15（wave3 后继审计 F8）：**必须单独 try/catch**。
                    // `refreshCacheSync()` 会调 `plugin.getPlayerDataStore()...`，而
                    // `disableAll` 会把 playerDataStore 置 null（它刻意不中断这个
                    // 未被跟踪的结算异步任务）。于是 `/lea reload` 恰好撞上进行中的 sweep 时，
                    // 这里抛 NPE —— 它发生在 finally 里，会**压制掉 runSettleSweep 本来
                    // 正在传播的异常**，让真正的原因（例如某个 DB 错误）彻底消失，只在日志里
                    // 留一条看起来像结算 bug 的 NPE。刷新缓存本就是尽力而为，不该有能力
                    // 掩盖真实故障。
                    try {
                        refreshCacheSync();
                    } catch (RuntimeException e) {
                        plugin.getLogger().log(java.util.logging.Level.FINE,
                                "Season cache refresh skipped (plugin shutting down or data store"
                                        + " already torn down)", e);
                    }
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：任务不会执行，必须复位防重入标志
            settleInFlight.set(false);
        }
    }

    private void runSettleSweep() {
        DatabaseManager db = plugin.getDatabaseManager();
        LocalDate today = LocalDate.now();
        int currentId = currentSeasonId(today);
        if (currentId < 1) {
            return;
        }

        // 首次启用（表空）：只建立当前季行，不追既往（防首开即大规模补发历史奖）
        // A5/D6：三态 —— 「表空」与「读失败」不可同形。旧实现 catch → null，
        // runSettleSweep 据此判「首次启用」：本轮结算被跳过（不丢数据，游标未前进，
        // 下一轮读成功即补上），但会打出 "Season ledger initialized … (first enable)"
        // 这条<b>与事实相反</b>的 INFO —— 运维据此以为历史季按设计不追发。
        // 现在读失败走独立的 SEVERE + return，日志不再谎报首次启用。
        DatabaseManager.SeasonIdRange minRange = db.loadMinSeasonId();
        if (minRange.state() == DatabaseManager.MinSeasonIdResult.READ_FAILED) {
            plugin.getLogger().severe("Season settlement sweep skipped: MIN(season_id) on the"
                    + " seasons table could NOT be read, so the settlement start point is"
                    + " unknown. This is NOT \"first enable\" — the ledger contents are"
                    + " unverified. Nothing was settled this round; the next sweep retries"
                    + " from the same place (the cursor did not move), so no reward is lost."
                    + " If this repeats, fix the database connection.");
            return;
        }
        if (minRange.state() == DatabaseManager.MinSeasonIdResult.EMPTY) {
            SeasonWindow window = windowOf(currentId);
            db.ensureSeasonRow(window.id(), window.start(), window.end());
            plugin.getLogger().info("Season ledger initialized at season " + currentId
                    + " (first enable; past seasons are not settled).");
            return;
        }
        Integer minId = minRange.id();
        if (minId == null) {
            // 不可达（PRESENT 必带值）；保留防御性早退，绝不把 null 猜成"首次启用"。
            plugin.getLogger().severe("Season settlement sweep skipped: PRESENT min season id"
                    + " carried no value (inconsistent tri-state); nothing was settled.");
            return;
        }

        int targetId = currentId - 1;
        if (targetId < 1) {
            return; // 尚未跨过任何季边界
        }

        Long cursorObj = db.maxSettledSeasonId();
        int from = (cursorObj != null) ? (int) (long) cursorObj + 1 : minId;
        // R12d-F7：从库里重建待重试表 —— 修复前这张表是纯内存的，重启 / `/lea reload`
        // 之后恒为空，而失败季的 settled_at 仍是脏 claim（非 NULL），游标
        // MAX(season_id) WHERE settled_at IS NOT NULL 已经越过它 → 该季落在
        // [minId, from) 左侧，循环再也访问不到 = 奖励永久丢失（且 javadoc 还写着
        // "下次启服的 sweep 会继续重试"，与事实相反）。
        // 重建必须在算完 from 之后、进循环之前：重建出来的季同样要把 from 拉回。
        if (!rebuildPendingReleases(minId, targetId)) {
            return; // 读失败：不猜"没有欠账"（见 rebuildPendingReleases 的返回语义）
        }
        // R11-P：上一轮释放失败的季，其 settled_at 仍非 NULL —— 游标已经停在它上面
        // （甚至因为后季被 claim 而越过它）。把起点拉回最靠前的待重试季，本轮就地重试，
        // 而不是继续从它后面扫（那样这一季永远不会被回访 = 奖励永久丢失）。
        Integer earliestPending = earliestPendingRelease();
        if (earliestPending != null && earliestPending < from) {
            plugin.getLogger().warning("Season settlement cursor held back at season "
                    + earliestPending + " (was " + (from - 1) + ") pending a release retry —"
                    + " seasons at or after it that are not yet settled stay blocked.");
            from = earliestPending;
        }
        if (from > targetId) {
            return; // 常态：无未结算的已跨越季
        }

        int lastSettledId = 0;
        String lastTopName = null;
        long lastTopScore = 0;

        for (int s = from; s <= targetId; s++) {
            SeasonWindow window = windowOf(s);
            // R11-P：本季上一轮"释放抢占"失败 → 它的 settled_at 还躺着脏 claim。
            // 先重试释放；<b>释放不成功就 break</b>：若放行，本季因 settled_at 非 NULL
            // 抢不到（continue），后续季却会照常 claim 成功，MAX(...) 游标一跳跃过本季
            // → 与原缺陷完全相同的后果（奖励永久丢失）。放在窗口完整性检查之前：
            // 释放是无害的补偿动作，窗口是否走完不影响它该不该被重试。
            Long pendingClaim = pendingReleaseRetries.get(s);
            if (pendingClaim != null && !releaseWithRetry(s, pendingClaim)) {
                break; // 释放仍未生效：游标停在本季，下一轮/后台 drain 继续
            }
            // R7-H2：只结算"真正走完"的季——anchor-date/duration 点读可改，
            // 改小锚点会让 currentSeasonId 跳变、targetId 命中仍在进行中的窗口；
            // 未走完的窗口跳过（不写台账不发放），防提前切割发奖。
            if (!window.end().isBefore(today)) {
                plugin.getLogger().warning("Skipping settlement for season " + s
                        + " (window " + window.start() + ".." + window.end()
                        + " not complete; anchor/duration changed?)");
                continue;
            }
            db.ensureSeasonRow(window.id(), window.start(), window.end());
            // 跨服聚合（查全表取 TOP N —— 多服连同一库即共享）
            // F1（R12-B）：读面三态 —— 「读失败」必须与「整季无人答题」分开。
            // 原实现读失败返回空列表 → 空 top 写出空/缺行的台账 → 却被当成"全部落库"
            // （settled_at 已写、游标 = MAX(season_id) WHERE settled_at IS NOT NULL 前进、
            // 该季永不回访）→ 整季奖励永久丢失且日志一路 INFO。
            // 现在在读之前就中止：此时 tryClaimSettlement 尚未执行（下面才抢），
            // 库里没有脏 claim 需要释放，直接 break 让下一轮 sweep 重试本季。
            PlayerDataStore.RankedPlayersLoad ranked = plugin.getPlayerDataStore()
                    // R15（F5）：必须传 includeHidden=true —— 名次奖励按「含隐身者」的完整
                    // 人口口径计算。用展示口径会有两条静默后果：隐身者整笔丢掉名次奖；
                    // 名次是过滤后列表的稠密序号，所以隐身者下面所有人被提升一名、拿更高档。
                    // 结算口径与参与奖（loadSessionParticipants，本就不过滤）保持一致。
                    .loadRankedPlayersForPeriod(TOP_N, window.start(), window.end(), true);
            if (ranked.readFailed()) {
                plugin.getLogger().severe("Season " + s + " settlement ABORTED: the season"
                        + " leaderboard query FAILED (window " + window.start() + ".."
                        + window.end() + "), so the reward list is unknown."
                        + " No settlement claim was made and the sweep cursor is held at season "
                        + s + " — the season is retried on the next sweep. If this persists,"
                        + " the whole season's rewards are pending, not lost; fix the database"
                        + " connection and they will be paid out.");
                break; // 与「台账写失败」一致：绝不越过本季
            }
            List<LeaderboardEntry> top = ranked.entries();
            String snapshotJson = buildSnapshotJson(top);
            // F-09-01：原序是"claim → 写台账"，台账写失败被 insertSeasonRewards
            // （void，吞 SQLException）静默吞掉，而游标 = MAX(season_id) WHERE
            // settled_at IS NOT NULL 已随 claim 推进 → 该季奖励永久丢失且永不回访。
            // 修法：claim 后立刻写台账并回读确认；确认失败则**释放本次抢占**
            // （settled_at 条件置 NULL），游标随之后退，下一轮 sweep 重试本季。
            // 保留"先 claim 后写台账"的顺序（抢占者唯一 → 台账单写者），
            // 只补上原来缺失的失败回退路径。
            long claimedAt = System.currentTimeMillis();
            // R-16（审计自查）：这里的 `rewardsOwed` 是<b>台账腿</b>的开关，只读
            // season.rewards.enabled，<b>刻意不看金币总闸</b>。
            // 于是 money.enabled=false（出厂默认）时本季照样写台账、照样广播，
            // 只是钱不发 —— 由 deliverRows 在「标记之后、付款之前」按
            // isMoneyPayable() 分流（不回滚，行留在 claimed=1 的已结清态）。
            // 千万别在这里顺手把金币门也塞进来：那样整季台账会凭空消失，
            // 事后无从核对"这一季本该发多少"，且会把 ledger_pending 债务标记
            // 搅进"配置关闭"这种正常状态里。
            boolean rewardsOwed = isRewardsEnabled();
            // R12d-F7：把「本季欠一份台账」和 claim 写在同一条 UPDATE 里。分开写会留一个
            // 「已抢占但标记未落库」的窗口，重启正好落在窗口里就退化成修复前的形状
            // （脏 claim + 无标记 → 重建逻辑永远看不到它）。
            DatabaseManager.SettlementClaim claim =
                    db.claimSettlement(s, claimedAt, snapshotJson, rewardsOwed);
            if (claim == DatabaseManager.SettlementClaim.TAKEN) {
                // 他服已抢先：正确跳过，游标由他服推进，本服什么都不该做。
                continue;
            }
            if (claim == DatabaseManager.SettlementClaim.FAILED) {
                // R15（后继审计 F1）：**必须 break，不能 continue**。
                // 原先这里是 `if (!won) continue;`，把「SQL 失败」与「他服已抢先」
                // 混成同一个 false。后果链条：第 N 季 claim 失败（SQLITE_BUSY 等）、
                // 第 N+1 季成功 → MAX(settled_at) 越过第 N 季；而第 N 季的
                // ledger_pending 仍是 0（claim 没成功，标记也没写），债务扫描要求
                // `settled_at IS NOT NULL AND ledger_pending <> 0` → **看不见它** →
                // 那一季的玩家永远拿不到奖励，且没有任何后续路径会回访。
                // 紧邻的 writeSeasonLedger 失败分支早就写着「必须 break」，
                // 同一个道理对 claim 失败同样成立。
                plugin.getLogger().severe("Season " + s + " claim FAILED (SQL error) —"
                        + " settlement halted at this season so the cursor cannot advance past it."
                        + " The season stays unclaimed and will be retried on the next sweep.");
                break;
            }
            // claim == CLAIMED：抢到了，继续写台账。
            if (rewardsOwed && !writeSeasonLedger(s, top, window)) {
                // 必须 break：若 continue，后续季会照常 claim 并把 settled_at
                // 推到本季之上，MAX(...) 游标一跳跃过失败季 → 缺陷复现。
                // R11-P：释放改为有界重试 + 待重试登记（原为一次调用 + 无条件 break）。
                // 释放失败时游标被钉在本季（pendingReleaseRetries + 本处 break），
                // 不再是"打一条 SEVERE 然后奖励永久丢失"。
                releaseWithRetry(s, claimedAt);
                break;
            }
            if (rewardsOwed) {
                // R12d-F7：台账回读确认完整 → 清掉「欠台账」标记。不清的话，下次重启的
                // 重建逻辑会把这一季当成"写失败"，白释放、白重结算一次（不会重复发钱，
                // 但会重复广播，且把 SEVERE 分贝浪费在正常季上）。
                //
                // R-16（红队实测 / 事后追补）：**这一句原来没有 fencing，能擦掉别人的标记。**
                // 原调用是 `db.clearSeasonLedgerPending(s)`，库侧 WHERE 只有 season_id ——
                // 而他服接管该季之后我们这条写入仍然会命中的情形是真实存在的：本服的
                // writeSeasonLedger 被挂住（MySQL 锁等待；initializeMySQL 没设 socketTimeout），
                // 15 分钟后他服按墙钟年龄判本服 claim 陈旧 → 接管 → 自行结算 →
                // 本服那条写终于返回，走到这里把**他服**的 ledger_pending 清零 →
                // 唯一能触发恢复的信号被销毁，且日志里没有任何痕迹。
                // 现在把本次 claim 的两个 token 一起传下去：
                //   claimedAt = 本次 claimSettlement 用的那个值（**不是**从库里回读的 ——
                //               回读值可能是他服的，那就等于没有 fencing）；
                //   claimOwner = db.getNodeId()（claimSettlement 写进库的就是它，逐字必须一致，
                //               写成 per-process instanceId 会恒 0 行）。
                DatabaseManager.LedgerClearResult cleared =
                        db.clearSeasonLedgerPendingDetailed(s, claimedAt, db.getNodeId());
                if (cleared == DatabaseManager.LedgerClearResult.NOT_HELD) {
                    // NOT_HELD 的语义 = 「0 行匹配」= 本服已经不再持有这一季的 claim。
                    // 处置：**不能把本季当成"本服结算成功"** ——
                    //   ① 不置 lastSettledId：后面的广播/跨服子通道/在线者发放整段是为
                    //      "本服真结算完的季"准备的，归属别人的结算不该由本服代播（重复广播），
                    //      而在"0 行匹配"这个已经不确定的状态上宣称成功正是本条缺陷的病根；
                    //   ② 显式 continue：不依赖 lastSettledId 的副作用，让"本季不由本服上报"
                    //      这件事在代码里直接可见；
                    //   ③ 级别 SEVERE 而不是 WARNING：上方 DatabaseManager 里那条 WARNING 是
                    //      "标记没清掉、下次可能多结算一次"（幂等，无害）；这里是"结算归属
                    //      已经不是我们的了"，是两条完全不同的故障，不能共用一个分贝。
                    // 钱不丢：标记留着（或已由真正的持有者清掉）→ 该季仍会被
                    // rebuildPendingReleases 看见 → 释放/重结算都是幂等的；台账行也已经在库里。
                    plugin.getLogger().severe("Season " + s + " settlement LOST its claim between"
                            + " the ledger write and the marker clear (settled_at=" + claimedAt
                            + ", claim_owner=" + db.getNodeId() + " matched 0 rows): another"
                            + " server instance took the claim over (the local settlement write"
                            + " was slow enough to be judged stale). This server does NOT report"
                            + " the season as settled — no broadcast and no payout from here; the"
                            + " current holder does that. No reward is lost or paid twice: the"
                            + " ledger insert is idempotent and payouts go through a claimed=0"
                            + " conditional update.");
                    continue;
                }
                if (cleared == DatabaseManager.LedgerClearResult.FAILED) {
                    // ----------------------------------------------------------------
                    // R-16 后续修正：**新增的一态，处置与 NOT_HELD 相反 —— 本服照常上报。**
                    //
                    // 为什么不能沿用 `continue`：FAILED 是 UPDATE **抛了**（SQLITE_BUSY /
                    // 锁等待超时 / 触发器 ABORT / 磁盘错误），我们**从未拿到 0 行的答案** ——
                    // 三列 fencing 没有给出"不匹配"这个结论。所以「本服持有这一季」这件事
                    // **从来没有被否认**：
                    //   ① 台账行已经落库 —— 走到这里的前提就是 writeSeasonLedger 回读计数
                    //      完整（见上面 rewardsOwed 分支），结算这件事在库里已经是既成事实；
                    //   ② claim 仍是本服的（settled_at + claim_owner 原值未动），
                    //      他服按陈旧判据接管要等满 15 分钟。
                    // 把「瞬时抖动」读成「他服抢走了」，代价就是本条缺陷：一个真结算完的季
                    // **无广播、无本服发放**，而另一台服要 15 分钟后才可能补上 —— 其间玩家
                    // 看到的是赛季结束了却什么都没发生。
                    //
                    // 为什么反过来「照常上报」也不会双播/双发：
                    //   ① 广播只在本服这一个线程里发，且 lastSettledId 只前进不重复赋值；
                    //      他服此刻并没有判定接管（它看到的 claim 还是新的、不是陈旧的），
                    //      不会并发广播同一季；
                    //   ② 付款走台账行级 `claimed=0` 条件 UPDATE，本服与他服都跑也只付一次；
                    //   ③ 真正会"再结算一次"的是 rebuildPendingReleases —— 它要求的
                    //      ledger_pending 仍是 1，而那正是 FAILED 下我们**故意留下**的状态。
                    //      留下的正收益：标记 = 1 会被重建逻辑看见 → 以后幂等地把这一季
                    //      再确认一遍（不重复发钱），万一本轮广播真的丢了也还有回访路径。
                    //      代价：可能多一次「赛季结束」广播，外加 rebuildPendingReleases
                    //      的 MAX_REBUILD_RESETTLES 封顶保护（这条路径早就为"标记清不掉"
                    //      准备过了，见该常量的 javadoc）。
                    // 两害相权：多一次幂等广播 << 整季静默不上报。故此处**不 continue**。
                    // 级别 WARNING：这是"标记没清掉"（幂等、可自愈），不是"归属已丢"。
                    // 也不能降成 FINE —— 它确实意味着这一季会被重建逻辑再碰一次。
                    // ----------------------------------------------------------------
                    plugin.getLogger().warning("Season " + s + " settled and its reward ledger is"
                            + " confirmed, but the owed-ledger marker could NOT be cleared (SQL"
                            + " error on the marker UPDATE: settled_at=" + claimedAt
                            + ", claim_owner=" + db.getNodeId() + "). The claim is STILL ours —"
                            + " the UPDATE threw, so its WHERE clause never answered 'no match'"
                            + " and nothing denied the fencing token. This server reports the"
                            + " season as settled"
                            + " (broadcast + payout proceed) because the ledger rows are already"
                            + " committed; no reward is lost or paid twice (the ledger insert is"
                            + " idempotent and payouts go through a claimed=0 conditional update)."
                            + " Side effect: ledger_pending stays 1, so the owed-ledger rebuild"
                            + " will re-confirm this season once more later (bounded by"
                            + " MAX_REBUILD_RESETTLES). If this repeats every sweep, the marker"
                            + " UPDATE itself is failing — check the database connection.");
                }
            }
            plugin.getLogger().info("Season settled: " + s);
            lastSettledId = s;
            if (!top.isEmpty()) {
                lastTopName = top.get(0).playerName;
                lastTopScore = top.get(0).score;
            }
        }

        if (lastSettledId < 1) {
            return; // 全被抢占（他服结算完成）——本服不广播不发放
        }

        // 结算 IO 已结束 → 回主线程：广播（含音效）+ 跨服子通道 + 本服在线者发放。
        // 多季 catch-up 只播最近一次的结束（历史季广播无意义，plan §3.3 伪代码的收窄）。
        final int settledId = lastSettledId;
        final String topName = lastTopName;
        final long topScore = lastTopScore;
        final int newSeasonId = currentId;
        runMain(() -> {
            broadcastEnded(settledId, topName, topScore);
            broadcastStarted(newSeasonId);
            if (plugin.getProxyMessenger() != null) {
                plugin.getProxyMessenger().sendSeasonSettled(settledId);
                plugin.getProxyMessenger().sendSeasonStarted(newSeasonId);
            }
            // 本服在线者即时发放（离线者走 join 补发；跨服在线者经他服的
            // SeasonSettled 回投触发本地同名发放）。R7-M1：一次批量查询替代
            // 逐人单查（N 人在单连接上排队 → 单条 IN 查询）。
            List<UUID> onlineIds = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                onlineIds.add(online.getUniqueId());
            }
            deliverPendingBatch(onlineIds, true);
        });
    }

    /**
     * F-09-01：释放本次结算抢占（把 {@code settled_at} 退回 NULL）。
     *
     * <p><b>R-16：fencing 条件是 {@code settled_at = ? AND claim_owner = ?}（两列都要匹配）。</b>
     * 原来只有 {@code settled_at = ?}，而那个值是<b>从共享库读出来的</b>
     * （{@link DatabaseManager#loadSeasonsWithOwedLedger}）。他服正在结算该季时，我们读到的
     * 就是<b>他的</b> claim 值 → WHERE 匹配 → 释放掉的是<b>他服的 live claim</b>，
     * 接着本服重新抢占再结算一次：<b>重复结算 + 重复「赛季结束」广播</b>。
     * （玩家不会重复拿钱：台账行是 {@code INSERT OR IGNORE}、发放走行级 {@code claimed}
     * 条件 UPDATE，所以这是"重复结算/重复广播"缺陷，<b>不是</b>双倍发钱。）
     * 加 {@code claim_owner} 后只有<b>本节点自己写的</b> claim 才能被本节点释放。
     *
     * <p>R11-P：<b>返回三态而不是 void</b>。原实现把 {@code withConnection(..., -1)} 的
     * 兜底 {@code -1}（连接失败）和条件 UPDATE 的 {@code 0}（没有行匹配）混进同一条
     * SEVERE，两者含义完全相反：
     * <ul>
     *   <li>{@code -1} = 库连不上，<b>该重试</b>（{@code getConnection()} 自愈机制在
     *       下一次调用会重新 {@code initialize()}，重试有真实成功机会）；</li>
     *   <li>{@code 0} = 库里 {@code settled_at} 已不是本次 claim（他服释放后重新抢占 /
     *       行被重建），<b>重试永远匹配不上</b>，重试只会在别人结算时反复打库。</li>
     * </ul>
     * 现在把两者分成 {@link ReleaseOutcome#CONNECTION_FAILED} 与
     * {@link ReleaseOutcome#NOT_HELD}，调用方按结局处置。
     *
     * <p><b>R-16：{@code NOT_HELD} 的措辞必须说清「两列都没匹配上」，而不再是「只有
     * settled_at 没匹配上」。</b>加了持有者列之后，{@code affected=0} 有两种成因：
     * ① {@code settled_at} 已不是本次 claim（他服释放后重新抢占 / 行被重建）；
     * ② {@code settled_at} 一模一样但 {@code claim_owner} 已不是本服 —— 也就是
     * <b>本次修复要挡下的那种情形</b>（他服正持有该季，本服的条件 UPDATE 正确地不命中）。
     * 这正是本条修复生效的证据：修复前这里会命中并把他服的 live claim 放掉。
     * 级别仍是 SEVERE（下方日志）：走到这里说明本服的 sweep 已失去该季的持有权，
     * 是需要运维解释的状态；只是不再是"连接失败"，所以不重试。
     *
     * @return 见 {@link ReleaseOutcome}
     */
    private ReleaseOutcome releaseSettlement(int seasonId, long claimedAt) {
        DatabaseManager db = plugin.getDatabaseManager();
        // R-16 后续修正：这里必须取**节点级** nodeId，因为 claimSettlement 写进
        // seasons.claim_owner 的就是它。取 per-process instanceId 会让重启后的释放
        // 带着一个库里从不存在的值去匹配 → 0 行 → NOT_HELD → 脏 claim 永远清不掉。
        String ourNodeId = db.getNodeId();
        Integer updated = db.withConnection(conn -> {
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE " + db.getTablePrefix() + "seasons SET settled_at = NULL,"
                            + " top_snapshot = NULL, ledger_pending = 0, claim_owner = NULL"
                            + " WHERE season_id = ? AND settled_at = ? AND claim_owner = ?")) {
                stmt.setInt(1, seasonId);
                stmt.setLong(2, claimedAt);
                stmt.setString(3, ourNodeId);
                return stmt.executeUpdate();
            }
        }, -1);
        if (updated != null && updated == 1) {
            return ReleaseOutcome.RELEASED;
        }
        String manualFix = "UPDATE " + db.getTablePrefix() + "seasons SET settled_at = NULL,"
                + " top_snapshot = NULL, ledger_pending = 0, claim_owner = NULL"
                + " WHERE season_id = " + seasonId
                + " AND settled_at = " + claimedAt
                + " AND claim_owner = '" + ourNodeId + "';";
        if (updated == null || updated < 0) {
            // 连接失败（withConnection 兜底 -1）：与"0 行匹配"是完全不同的故障 ——
            // 库里状态未知，条件 UPDATE 可能因为连不上而根本没执行。
            plugin.getLogger().severe("Season " + seasonId + " settlement release could not be"
                    + " APPLIED (affected=" + updated + ", database connection unavailable);"
                    + " settled_at may still be non-NULL and the sweep cursor is held at this"
                    + " season until the release takes effect. Manual fix: " + manualFix);
            return ReleaseOutcome.CONNECTION_FAILED;
        }
        // affected == 0：语句跑到库上，但没有行匹配本次 claim —— 可能是 settled_at 已不是
        // claimedAt，也可能是 claim_owner 已不是本节点（他服正持有该季，本节点正确地不命中；
        // 后者正是 R-16 修复生效的形态，修复前这里会命中并放掉他服的 live claim）。
        // 再试也匹配不上，故不重试。
        // E2（本轮要求）：级别从 SEVERE 降到 WARNING —— 多服共享库时 NOT_HELD 是**正常**结局
        // （"读之后、写之前，他服重新抢走了"，或本节点那条 claim 已被后台 drain 释放），
        // 不是故障。用 SEVERE 会把运维的分贝预算耗在正常事件上，真出事时看不见。
        // 手工修法提示保留（措辞改成陈述句，不再读作告警）。
        plugin.getLogger().warning("Season " + seasonId + " settlement release did NOT take"
                + " effect (affected=" + updated + ", no row matched settled_at=" + claimedAt
                + " AND claim_owner=" + ourNodeId + "); the claim is no longer held by"
                + " this sweep — it was already released, or another server re-claimed the"
                + " season between our read and this update. Nothing else on this row was"
                + " touched, so the other holder's settlement proceeds normally. no retry is"
                + " possible or useful. Manual fix (only if you believe otherwise): " + manualFix);
        return ReleaseOutcome.NOT_HELD;
    }

    /**
     * R11-P：带<b>同轮有界重试</b>的释放（只在 {@code runSettleSweep} 的异步线程调用）。
     *
     * <p>只对 {@link ReleaseOutcome#CONNECTION_FAILED} 重试：这是唯一"再试一次有真实
     * 成功机会"的结局（{@code DatabaseManager.getConnection()} 在连接关闭/自愈失败后
     * 下次调用会重新初始化）。{@code NOT_HELD} 不重试（重试不可能成功）。
     *
     * <p><b>不会阻塞主线程</b>：调用方是 {@code runTaskAsynchronously} 的线程；
     * 退避 {@code Thread.sleep} 在 {@code withConnection} 的锁<b>之外</b>，因此不持有
     * {@code connectionLock}，其它 DB 使用者不受影响。代价只是本轮 sweep 慢几十毫秒
     * （{@code settleInFlight} 期间本就无第二个 sweep 需要等待）。
     *
     * <p>重试耗尽仍失败 → 记入 {@link #pendingReleaseRetries}：<b>释放没成功就不许
     * 游标前进</b>（由 {@code runSettleSweep} 的 hold-back 与 {@code break} 保证），
     * 并在 60s 后由后台 drain 与后续每次 sweep 再试。
     *
     * @return {@code true} = 本季已不再持有脏 claim，可以继续推进游标；
     *         {@code false} = 释放彻底失败，游标必须停在本季
     */
    private boolean releaseWithRetry(int seasonId, long claimedAt) {
        ReleaseOutcome outcome = ReleaseOutcome.CONNECTION_FAILED;
        for (int attempt = 1; attempt <= RELEASE_ATTEMPTS; attempt++) {
            outcome = releaseSettlement(seasonId, claimedAt);
            if (outcome != ReleaseOutcome.CONNECTION_FAILED) {
                break;
            }
            if (attempt < RELEASE_ATTEMPTS) {
                long backoff = RELEASE_BACKOFF_MS[
                        Math.min(attempt - 1, RELEASE_BACKOFF_MS.length - 1)];
                plugin.getLogger().warning("Season " + seasonId + " settlement release attempt "
                        + attempt + "/" + RELEASE_ATTEMPTS + " failed (database unavailable);"
                        + " retrying in " + backoff + "ms.");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (outcome == ReleaseOutcome.CONNECTION_FAILED) {
            recordPendingRelease(seasonId, claimedAt);
            return false;
        }
        clearPendingRelease(seasonId);
        return true;
    }

    /** 记下"释放未生效"的季：游标从此不得越过它，直到释放真的生效。 */
    private void recordPendingRelease(int seasonId, long claimedAt) {
        pendingReleaseRetries.put(seasonId, claimedAt);
        // R-16/E1：手工修法 SQL 必须与 releaseSettlement 里那份**逐字一致**（同前缀、同条件、
        // 同 SET 列表）。原实现漏了两处，运维照抄会踩坑：
        //   ① 写成 "UPDATE seasons" 没有表前缀 → 默认配置下报 no such table: seasons；
        //   ② 少了 ledger_pending = 0 → 欠账标记留着，该季还会被重建逻辑再结算一次。
        DatabaseManager db = plugin.getDatabaseManager();
        String prefix = db == null ? "" : db.getTablePrefix();
        StringBuilder manualFix = new StringBuilder("UPDATE ").append(prefix).append("seasons")
                .append(" SET settled_at = NULL, top_snapshot = NULL, ledger_pending = 0,")
                .append(" claim_owner = NULL WHERE season_id = ").append(seasonId)
                .append(" AND settled_at = ").append(claimedAt);
        if (db != null) {
            // R-16 后续修正：与 releaseSettlement 的 WHERE 保持同一个值（节点级 nodeId）。
            // 写成 per-process id 会让运维照抄一条**永远匹配不上**的 SQL。
            manualFix.append(" AND claim_owner = '").append(db.getNodeId()).append('\'');
        }
        manualFix.append(';');
        plugin.getLogger().severe("Season " + seasonId + " settlement release FAILED after "
                + RELEASE_ATTEMPTS + " attempts (database unavailable). Rewards are NOT lost:"
                + " the sweep cursor is held at season " + seasonId + " instead of skipping it,"
                + " and the release is retried in the background every "
                + (DRAIN_DELAY_TICKS / 20L) + "s and at the start of every future sweep."
                + " Manual fix: " + manualFix);
        scheduleReleaseDrain();
    }

    /** 释放已生效（或被证实已由他方持有）：清掉阻塞条目。 */
    private void clearPendingRelease(int seasonId) {
        if (pendingReleaseRetries.remove(seasonId) != null) {
            releaseDrainAttempts.remove(seasonId);
            plugin.getLogger().info("Season " + seasonId + " settlement claim released;"
                    + " the sweep cursor is free to advance again.");
        }
    }

    /** 最靠前的待重试季；null = 无（游标可以正常前进）。 */
    private Integer earliestPendingRelease() {
        Integer earliest = null;
        for (Integer seasonId : pendingReleaseRetries.keySet()) {
            if (earliest == null || seasonId < earliest) {
                earliest = seasonId;
            }
        }
        return earliest;
    }

    /**
     * R12d-F7：<b>从库里重建 {@link #pendingReleaseRetries}</b> —— 让待重试面跨重启/热重载存活。
     *
     * <h2>修的缺陷</h2>
     * 原实现这张表是纯内存，{@code LetsLearnEnglish} 每次 enable 都 {@code new SeasonManager}，
     * {@code disableAll()} 置 null → 重启或 {@code /lea reload} 后表恒空。而失败季的
     * {@code settled_at} 仍是脏 claim（非 NULL），于是
     * {@code from = MAX(season_id) WHERE settled_at IS NOT NULL + 1} 已经越过它，
     * 该季落在 {@code [minId, from)} 左侧，循环再也访问不到 → 奖励永久丢失。
     * 更糟的是 {@link #scheduleReleaseDrain} 的 javadoc 写着"下次启服的 sweep 会继续重试"，
     * 让运维以为已经修好了。
     *
     * <h2>重建的判据</h2>
     * 只看 {@code seasons.ledger_pending <> 0}，<b>不看</b>「台账缺行」。
     * 「settled_at 非 NULL 且台账无行」由三种历史产生：①台账写失败 ②结算时奖励功能关闭
     * ③各档位金额全 0。后两者本来就不欠台账，按「缺行」重建会给它们反复释放/重结算，
     * 一旦之后奖励配置被打开就<b>补发真钱</b>。三种历史在库里逐字节同形，无法事后区分，
     * 所以意图必须在抢占那一刻写下来（{@code ledger_pending}，与 claim 同一条 UPDATE）。
     * 详见 {@link DatabaseManager#loadSeasonsWithOwedLedger} 的 javadoc。
     *
     * <h2>fencing token</h2>
     * 灌进 {@link #pendingReleaseRetries} 的值必须是库里 {@code settled_at} 的<b>实际值</b>：
     * {@link #releaseSettlement} 的条件 UPDATE 是
     * {@code WHERE season_id = ? AND settled_at = ? AND claim_owner = ?}，换任何其它
     * {@code settled_at} 都永远匹配 0 行（判成 {@link ReleaseOutcome#NOT_HELD}）→
     * 脏 claim 永远清不掉。
     *
     * <p>R-16：{@code settled_at} 单独一个值<strong>不</strong>构成 fencing —— 必须连
     * {@code claim_owner} 一起匹配。{@code settled_at} 是从共享库读出来的，
     * 他服此刻正持有该季时读到的就是<b>他的</b>值，只按它释放会释放掉别人的 live claim。
     * 所以本方法在把它灌进待重试表之前，先按 {@code claim_owner} 分流（见下方 R-16 修法）。
     *
     * <h2>为什么在启动期是对的（<span style="color:red">本条已被证伪，见下方订正</span>）</h2>
     * 重建出来的季会被 {@code runSettleSweep} 的 hold-back 拉回 {@code from}，然后
     * {@link #releaseWithRetry} 释放、下一轮重新结算。多服共享 MySQL 时他服可能同时持有
     * 该季 —— 此时本服的释放匹配 0 行得到 {@code NOT_HELD}（不是连接失败），不重试、
     * 清掉条目，由他服完成结算，语义正确。
     *
     * <p><b>R15 订正（wave3 后继审计 F3）：上面那段推理是错的。</b>
     * fencing token 就是 {@code seasons.settled_at} 的<b>实际值</b>，而它是从<b>共享库</b>里
     * 读出来的（见 {@link DatabaseManager#loadSeasonsWithOwedLedger}）。他服此刻正持有该季时，
     * 库里的 {@code settled_at} 就是<b>他服的</b> claim 值；本服把它读出来当 token，
     * {@link #releaseSettlement} 的 {@code WHERE season_id = ? AND settled_at = ?}
     * 因此<b>会匹配</b>——释放掉的正是他服的 live claim，而不是"匹配 0 行"。
     *
     * <p>后果（已确认，非双发钱）：他服被抢走 claim 后，本服重新 claim 并再结算一次 →
     * <b>重复结算 + 重复广播</b>；台账行是 {@code INSERT OR IGNORE}，所以<b>先写者定金额</b>，
     * 另一台的 {@code season.rewards.*} 配置被静默丢弃（config.yml 已警告这些键需多服同步，
     * 那本身就是"这会发生"的承认）。行级 {@code claimed} 标记保证每个玩家仍只发一次钱。
     * <b>这不是双倍发钱缺陷</b>，措辞不要夸大：玩家拿到的钱只由那一行台账决定。
     *
     * <h2>R-16 修法：{@code claim_owner} 持有者列 + 有界陈旧兜底</h2>
     * <p>上一段留的"正确的修法是给 claim 加持有者标识"已在 R-16 落地：
     * {@code seasons.claim_owner} 由 {@link DatabaseManager#claimSettlement} 与
     * {@code settled_at} 写在<b>同一条</b> UPDATE 里（{@link DatabaseManager#getNodeId()}，
     * 一个<b>节点</b>一个稳定值 —— 不是 per-process，否则快速重启救不回自己刚留的脏 claim），
     * {@link #releaseSettlement} 的条件 UPDATE 加上了
     * {@code AND claim_owner = ?}。于是"匹配 0 行得到 {@code NOT_HELD}"<b>现在才是真的</b> ——
     * 原来那句推理的错误之处（他服的 claim 会被匹配上）已被从根上消除。</p>
     *
     * <h2>R-16 后续修正（架构复审发现的回归）：进程级 id + 没有自愈触发</h2>
     * <p>初版把<b>每个进程一个新 UUID</b> 写进 {@code claim_owner}，并让"还新就跳过"依赖
     * 外部的下一轮 sweep。两者组合出正好把本方法核心用途堵死的场景：<b>同机快速重启</b> ——
     * 结算后崩溃、15 分钟内重启 → 新进程的新 UUID ≠ 库里那个 → 判成"别节点" → 年龄不到
     * 15 分钟 → 跳过；而全项目只有 onEnable 与季边界两个 sweep 触发点，{@code /lea reload}
     * 还明确保留 {@code DatabaseManager}（既不换 id 也不重跑 sweep），于是脏 claim 要躺到
     * <b>下一个季边界</b>，默认 {@code season.duration-days: 30}。
     * <b>钱没丢</b>（欠账扫描覆盖 {@code minId..targetId}，迟早会找到），但"重启后第一轮就把
     * 上一轮没落完的奖补上"这条<b>主恢复路径</b>被推迟了最多一个月。修法两条，缺一不可：
     * <ol>
     *   <li>{@code claim_owner} 改存<b>节点级</b> {@link DatabaseManager#getNodeId()}
     *       （{@code sha256} 前 16 位十六进制，前像是「每段 UTF-8 字节长度 + {@code ':'} + 原文」
     *       的长度前缀编码，两段为 {@code proxy.server-name} 与 dataFolder 绝对路径，直接相接；
     *       单射，跨重启稳定）→
     *       快速重启的自己被认成"自己"，立刻走 ① 分支，恢复原始行为；</li>
     *   <li>"还新就跳过"的分支改成 {@link #scheduleForeignClaimRecheck} 排一次到期复查
     *       → 恢复最多等满 15 分钟，而不是等下一个季边界。</li>
     * </ol>
     * 只做 ① 不够：改服名 / 换机器 / 真·他服 这三种情形仍会落进"跳过"分支；
     * 只做 ② 也不够：那只是把 30 天压到 15 分钟，快速重启本该<b>零延迟</b>恢复。</p>
     *
     * <p>本方法的责任是<b>不能把别人的欠账行灌进自己的待重试表</b>（灌进去就等于让
     * {@code releaseSettlement} 去撞一次无意义的 0 行，然后每轮 sweep 重复一次）。
     * 按 {@code debt.claimOwner()} 分四路：
     * <ol>
     *   <li><b>本节点</b>（{@code owner == getNodeId()}）→ 重启/热重载前自己写的脏 claim，
     *       照旧灌表重试。这是本方法最初要修的那条路径，行为不变；</li>
     *   <li><b>别节点，且 {@code settled_at} 还新</b>（年龄 ≤ {@link #FOREIGN_CLAIM_STALE_MS}）
     *       → 那个进程可能正在写这一季的台账。<b>不释放、不接管、不灌表</b>，
     *       本轮跳过，归持有者处理；但<b>排一次到期复查</b>
     *       （{@link #scheduleForeignClaimRecheck}），不再把恢复寄托在外部触发上。
     *       日志 FINE —— 多服下这是正常状态，不是故障；</li>
     *   <li><b>别节点，但 {@code settled_at} 已够老</b> → 持有者多半已经不在了（崩溃 / 被 kill /
     *       被 {@code /lea reload} 换掉），那条 claim 从此无人认领，不处理会让这一季
     *       <b>永远卡住</b>（游标被它钉住，后续季都不结算）。于是先
     *       {@link DatabaseManager#reassignSeasonClaimOwner} 把 {@code claim_owner} <b>只</b>
     *       改成自己（{@code settled_at} 原值保留 —— 绝不动别人的 fencing token，
     *       且条件里带 {@code AND claim_owner = <观察到的值>} 防 TOCTOU），再灌表，
     *       随后的 {@code releaseSettlement} 就能匹配上并清空；</li>
     *   <li><b>{@code owner} 为 NULL</b>（本列上线前写入的旧行，持有者未知）→ 与 ②③ 同一判据：
     *       还新就保守跳过（可能真有一个升级前启动的进程在结算）；够老则按 ③ 一样先接管
     *       （{@code AND claim_owner IS NULL} 精确命中"我们刚看到的那个无主状态"）再灌表。
     *       <b>不能省掉接管这一步</b>：{@code releaseSettlement} 的 WHERE 带
     *       {@code AND claim_owner = <本节点>}，owner 仍是 NULL 的行永远匹配 0 行 →
     *       判成 {@code NOT_HELD} → 条目被清掉、而 {@code settled_at} 还是非 NULL →
     *       该季再也 claim 不成 → <b>整季奖励永久丢失</b>，比原缺陷更糟。</li>
     * </ol>
     *
     * <p><b>陈旧兜底的代价（诚实记录）</b>：一个"还活着但结算慢到超过 15 分钟"的服
     * 仍可能被抢走 claim。但那意味着它在 15 分钟里连一次台账 INSERT 都没成功 ——
     * 它自己的清理路径本来就在失败，恢复后它自己的 sweep 会重入本条欠账路径，
     * 而释放/重结算都是幂等的（{@code INSERT OR IGNORE} + 行级 {@code claimed}
     * 条件 UPDATE），所以玩家不会被重复发钱，最多多一次广播。
     * 反过来把窗口调小（如 60s）会在 MySQL 慢查询 / 长 GC 时误伤 live claim，
     * 调大（如 24h）会让死服遗留的季白卡一整天。详见 {@link #FOREIGN_CLAIM_STALE_MS}。</p>
     *
     * @param minId    赛季号下界（含；取 {@code MIN(season_id)}，与游标起点同源）
     * @param maxId    赛季号上界（含；取 {@code targetId = currentSeasonId - 1}）
     * @return {@code true} = 重建已完成（可能有 0 条，也可能有 N 条）；
     *         {@code false} = 扫描读失败，调用方必须中止本轮 sweep（<b>不能</b>当成
     *         "没有欠账" —— 那正是缺陷本身）
     */
    private boolean rebuildPendingReleases(int minId, int maxId) {
        DatabaseManager db = plugin.getDatabaseManager();
        // 遗留欠账的一次性对账：只在「本次启动刚给 seasons 补上 ledger_pending 列」时做。
        // 升级前就已丢奖的季没有标记，重建逻辑看不到它们；库里的行也无法区分三种历史，
        // 所以不自动重试，只列出可疑季让人工裁定（见 warnAboutLegacyLedgerDebts）。
        if (db.consumeSeasonLedgerPendingColumnAdded()) {
            warnAboutLegacyLedgerDebts(db);
        }
        DatabaseManager.SeasonLedgerDebtScan scan = db.loadSeasonsWithOwedLedger(minId, maxId);
        if (scan.readFailed()) {
            plugin.getLogger().severe("Season settlement sweep ABORTED: the owed-ledger scan"
                    + " FAILED, so it is unknown which settled seasons still owe rewards."
                    + " Treating that as \"no debt\" would let the cursor advance past them and"
                    + " lose those rewards permanently. Nothing was settled this round; the cursor"
                    + " did not move, so the next sweep retries. Fix the database connection.");
            return false;
        }
        List<DatabaseManager.SeasonLedgerDebt> debts = scan.debts();
        if (debts.isEmpty()) {
            return true;
        }
        StringBuilder list = new StringBuilder();
        int restored = 0;
        // R12e-G2：本次因重建而被重结算的季（用于计数与封顶日志），与 restored 分开记 ——
        // restored 统计"本次新灌进待重试表的"，本列表统计"本次真的会被再结算一次"的。
        List<Integer> reSettled = new ArrayList<>();
        int capped = 0;
        // R-16：本次因「持有者不是本服」而跳过的季（不计入 restored，也不搬游标）。
        int foreign = 0;
        // R-16 后续修正：本节点的稳定标识（跨重启不变），循环外取一次 —— 每季都取会
        // 掩盖一个事实：这个值在一次 sweep 里绝不该变化。它变了就意味着"谁的抢占"这一
        // 判据在循环中途换了答案，那种情形必须是不可能的。
        String ourId = db.getNodeId();
        for (DatabaseManager.SeasonLedgerDebt debt : debts) {
            int seasonId = debt.seasonId();
            // ------------------------------------------------------------------
            // R-16：释放之前必须先看「这一行是谁的抢占」。
            // fencing token（settled_at）是从共享库读的，不是本服的；只按它释放 = 释放掉
            // 他服正在结算的 live claim → 重复结算 + 重复广播。三态分流：
            //
            //   ① 有主且是本服   → 本服的（重启/热重载前自己写的），照旧重试释放；
            //   ② owner 为 NULL  → 本列上线前写入的旧行，**持有者未知**。这是升级路径，
            //                      也是最容易回归的地方：既不能一律重试（真有一个 live
            //                      claim 就退化成缺陷），也不能一律跳过（死服遗留的季会
            //                      永远卡住）。裁定标准与 ③ 一致——看年龄：settled_at
            //                      够老 = 那条 claim 不可能还活着 = 接管并重试；
            //                      还新 = 保守跳过（下一个 sweep 再判定，时间会解决）。
            //                      注意「接管」这一步<b>不能省</b>：releaseSettlement 的
            //                      WHERE 带 {@code AND claim_owner = ?}，owner 还是 NULL 的行
            //                      永远匹配 0 行 → 判成 NOT_HELD → 条目被清掉而
            //                      settled_at 仍非 NULL → 该季再也抢不成（整季奖励永久丢失）；
            //   ③ 有主但不是本服 → 他服的。年龄够老 = 死服的陈旧抢占，先接管
            //                      （claim_owner 改成自己，settled_at 原值保留）再灌进待重试表，
            //                      于是随后的 releaseSettlement 能匹配上并清空；
            //                      还新 = 他服正在结算，静默跳过（FINE），游标也不搬。
            // ------------------------------------------------------------------
            String owner = debt.claimOwner();
            if (owner == null || !owner.equals(ourId)) {
                String observed = owner == null ? "NULL (pre-upgrade row, holder unknown)" : owner;
                if (!isForeignClaimStale(debt.settledAt())) {
                    // 还新：可能正有进程（他服的，或改名前/换机器前的本服）在写这一季的台账。
                    // 跳过 = 本轮不碰它，下一轮 sweep 重新判定（时间会把它变成"陈旧"）。
                    foreign++;
                    // R-16 后续修正（第二部分）：**必须自己安排一次延迟复查**。
                    // 修正前这里只有 continue —— 而 settleIfNeededAsync 全项目只有两个调用点
                    // （onEnable 一次、季边界一次），/lea reload 又不重建 DatabaseManager，
                    // 于是"跳过"就等于"这一季的恢复要等下一个季边界"，默认 duration-days: 30
                    // = 最长卡一个月。改动后最多等满 FOREIGN_CLAIM_STALE_MS（15 分钟）。
                    scheduleForeignClaimRecheck(debt.settledAt());
                    plugin.getLogger().log(Level.FINE, "Season " + seasonId + " owes a reward"
                            + " ledger but its claim is not ours and is too recent (observed"
                            + " claim_owner=" + observed + ", settled_at=" + debt.settledAt()
                            + ", age=" + (System.currentTimeMillis() - debt.settledAt())
                            + "ms < stale threshold " + FOREIGN_CLAIM_STALE_MS + "ms) — not"
                            + " touching it this round (another server may still be working on"
                            + " it). A re-check is scheduled for when the claim goes stale.");
                    continue;
                }
                // 陈旧：接管为本服的抢占，让随后的 releaseSettlement 能匹配 claim_owner。
                if (!db.reassignSeasonClaimOwner(seasonId, debt.settledAt(), owner)) {
                    // 行在我们读出之后被改动（他服刚刚释放/换过持有者，或他自己清了标记）。
                    // 不改也不释放：下一轮 sweep 重新判定，绝不盲写别人刚碰过的行。
                    foreign++;
                    // 同样要排复查：这次没接管上是因为行在动，而不是因为"还早"；
                    // 没有外部触发的话它同样会一直躺着。
                    scheduleForeignClaimRecheck(debt.settledAt());
                    plugin.getLogger().log(Level.FINE, "Season " + seasonId + " settlement claim"
                            + " changed hands between the owed-ledger scan and the take-over"
                            + " (observed claim_owner=" + observed + ", settled_at="
                            + debt.settledAt() + ") — deferring to the next sweep.");
                    continue;
                }
                plugin.getLogger().warning("Season " + seasonId + " settlement claim is not held"
                        + " by this server instance (observed claim_owner=" + observed + ","
                        + " settled_at=" + debt.settledAt() + ", age="
                        + (System.currentTimeMillis() - debt.settledAt()) + "ms > stale"
                        + " threshold " + FOREIGN_CLAIM_STALE_MS + "ms) — no live process can"
                        + " still be settling it, so this server takes the claim over and"
                        + " re-settles the season. No reward is lost and nothing is paid twice:"
                        + " the ledger insert is idempotent and payouts go through a claimed=0"
                        + " conditional update.");
            }
            // R12e-G2 封顶（判定顺序见上面 R-16 的说明）：只对「确实归本服处理」的季计数。
            int seen = rebuildResettleCounts.merge(seasonId, 1, Integer::sum);
            if (seen > MAX_REBUILD_RESETTLES) {
                capped++;
                if (rebuildCapWarned.add(seasonId)) {
                    plugin.getLogger().severe("Season " + seasonId + " has been re-settled "
                            + (seen - 1) + " time(s) by the owed-ledger rebuild and the"
                            + " 'ledger_pending' marker STILL cannot be cleared — automatic"
                            + " re-settlement now STOPS for this season (it stays blocked, no"
                            + " reward is lost). Cause: the UPDATE in"
                            + " DatabaseManager.clearSeasonLedgerPendingDetailed keeps failing"
                            + " (LedgerClearResult.FAILED) while the"
                            + " rest of the database works. Consequences if left alone: the season"
                            + " is re-broadcast on every sweep, and any reward tier enabled AFTER the"
                            + " first settlement would be paid RETROACTIVELY to a closed season."
                            + " Manual fix: UPDATE " + db.getTablePrefix() + "seasons"
                            + " SET ledger_pending = 0 WHERE season_id = " + seasonId + ";");
                }
                // 不灌进待重试表：否则这一季会被释放→重 claim→再结算。
                if (list.length() > 0) {
                    list.append(", ");
                }
                list.append(seasonId).append(" (CAPPED after ").append(seen - 1)
                        .append(" re-settles, ledgerRows=").append(debt.ledgerRows()).append(')');
                continue;
            }
            reSettled.add(seasonId);
            if (pendingReleaseRetries.putIfAbsent(seasonId, debt.settledAt()) == null) {
                restored++;
            }
            if (list.length() > 0) {
                list.append(", ");
            }
            list.append(seasonId).append(" (settled_at=").append(debt.settledAt())
                    .append(", ledgerRows=").append(debt.ledgerRows()).append(')');
        }
        if (restored > 0) {
            // SEVERE 而不是 WARNING：这条路径意味着「上一轮结算真的没落完，奖励还欠着」，
            // 而且是在重启后才被发现的 —— 运维必须知道这期间发生过什么。
            plugin.getLogger().severe("Season settlement recovered " + restored + " season(s) that"
                    + " were claimed but whose reward ledger was never confirmed: " + list
                    + ". This is what a previous run's failed settlement left behind (the ledger"
                    + " write and the claim release both failed, and the in-memory retry list did"
                    + " not survive the restart). They are re-added to the release-retry set now:"
                    + " the sweep cursor is held at the earliest of them, their claims are released,"
                    + " and they are settled again — no reward is lost. If this line repeats every"
                    + " restart, the database cannot be written to; fix the connection.");
        } else if (capped > 0 && reSettled.isEmpty()) {
            // 全部被封顶：只留上面那条每季一次的 SEVERE，不再重复报"recovered"。
            plugin.getLogger().warning("Season settlement sweep: " + capped + " season(s) are"
                    + " blocked on an un-clearable 'ledger_pending' marker and were NOT re-settled"
                    + " this round (see the SEVERE above for the manual fix).");
        } else if (foreign > 0) {
            // R-16：全部欠账季都在别人手里（或旧行还很新）。这是**正常**的多服状态，
            // 不是故障 —— 本服不碰它们，游标也不为它们后退（否则每一轮都会把 from 拉回去
            // 白扫一遍）。用 FINE：每季的判定理由上面已经逐条记过了。
            plugin.getLogger().log(Level.FINE, "Season settlement sweep: " + foreign
                    + " owed-ledger season(s) are held by another server instance (or are too"
                    + " recent to judge) and were left untouched this round.");
        }
        return true;
    }

    /**
     * R-16：库里那条抢占是否已经<b>老到不可能是 live claim</b>。
     *
     * <p>用 {@code System.currentTimeMillis() - settledAt()} 判龄，而不是"能不能联系上
     * 持有者"（多服之间没有可靠的心跳通道）。{@code settledAt} 是 claim 写入的那一刻的
     * 墙钟毫秒，所以年龄就是"这次结算已经开始了多久"。</p>
     *
     * <p>时钟回拨（{@code settledAt} 在未来）会让 {@code age} 为负 → 判成"不陈旧" →
     * 保守跳过。这个方向是安全的：宁可让游标多卡一轮（下一次 sweep 重判），
     * 也不要释放一张可能还在写的 claim。</p>
     *
     * @param settledAt 库里 {@code seasons.settled_at} 的原值
     */
    private static boolean isForeignClaimStale(long settledAt) {
        return System.currentTimeMillis() - settledAt > FOREIGN_CLAIM_STALE_MS;
    }

    /**
     * R12d-F7：老库升级时的一次性对账 —— 列出「修复前就已丢奖」的可疑赛季。
     *
     * <p>这些季在升级瞬间被补成 {@code ledger_pending = 0}，因此<b>不会</b>被
     * {@link #rebuildPendingReleases} 自动重试。这是刻意的：库里无法区分
     * 「台账写失败」与「本来就不发奖」，自动重试可能在奖励配置后来被打开时给一批
     * 早已结算过的赛季补发真钱。此处只告警并给出可直接粘贴的 SQL，由运维裁定。</p>
     */
    private void warnAboutLegacyLedgerDebts(DatabaseManager db) {
        List<Integer> legacy = db.findLegacySeasonsWithClaimAndNoLedger();
        if (legacy == null) {
            plugin.getLogger().warning("Pre-upgrade season ledger reconciliation could not run"
                    + " (the query failed). It will NOT be retried automatically — check the"
                    + " database connection and inspect the seasons / season_rewards tables"
                    + " manually if you suspect lost season rewards.");
            return;
        }
        if (legacy.isEmpty()) {
            return;
        }
        plugin.getLogger().severe("Pre-upgrade season ledger check: season(s) " + legacy
                + " are marked settled but have NO reward ledger rows. The upgraded schema cannot"
                + " tell whether that is a lost reward write or a season that deliberately paid"
                + " nothing (season.rewards.enabled=false, or all reward amounts were 0 when it was"
                + " settled), so NOTHING was retried automatically — paying players retroactively"
                + " for a season that was settled without rewards would be worse than the bug."
                + " Check these seasons by hand; if rewards were owed, release the claim and settle"
                + " again: UPDATE " + db.getTablePrefix() + "seasons SET settled_at = NULL,"
                + " top_snapshot = NULL, ledger_pending = 0 WHERE season_id = <id>;");
    }

    /**
     * 排一次后台重试（单飞：反复失败时不会堆积任务）。
     * 调度器已关闭（插件停用窗口）→ 静默放弃，条目保留。
     *
     * <p><b>R12d-F7 更正</b>：本方法 javadoc 原写着"下次启服的 sweep 会继续重试"，
     * <b>那是假的</b> —— 条目当时只存在这个对象的 {@code ConcurrentHashMap} 里，
     * 而 {@code disableAll()} 会把整个 {@code SeasonManager} 置 null。现在这句成立了，
     * 但成立的原因是新的机制而不是这条注释：
     * {@link #rebuildPendingReleases} 在本轮 sweep 里从 {@code seasons.ledger_pending}
     * 重建条目（与 claim 写在同一条 UPDATE 的持久标记）。改这条注释时请同时确认
     * 那个重建路径还在 —— 否则本注释会再次变成谎话。</p>
     */
    private void scheduleReleaseDrain() {
        if (!hasDrainablePending() || !releaseDrainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin,
                    this::drainPendingReleases, DRAIN_DELAY_TICKS);
        } catch (IllegalStateException ignored) {
            releaseDrainScheduled.set(false); // 停用窗口：任务不会执行
        }
    }

    /**
     * R-16 后续修正（第二部分）：为他服的 live claim 排<b>一次</b>到期复查。
     *
     * <h2>修的缺陷</h2>
     * <p>{@link #rebuildPendingReleases} 遇到「{@code claim_owner} 不是本节点且
     * {@code settled_at} 还新」的行时只做 {@code foreign++; continue;} —— 也就是说
     * <b>把恢复的希望寄托在某个未来的外部触发上</b>。而全项目只有两个触发点：
     * {@code LetsLearnEnglish.onEnable} 的那次启动 sweep，和季边界跨越时的
     * {@code settleIfNeededAsync}（{@code /lea reload} 明确保留 {@code DatabaseManager}，
     * 不会造出新节点、也不会重跑 sweep）。于是"跳过"的实际代价是
     * <b>等到下一个季边界</b>，默认 {@code season.duration-days: 30} = 最长卡一个月。
     * 这段逻辑存在的唯一理由就是「尽快把上一轮没落完的奖励补上」，等一个月等于没修。</p>
     *
     * <h2>排程设计</h2>
     * <ul>
     *   <li><b>延迟 = 剩余宽限期</b>（{@code FOREIGN_CLAIM_STALE_MS - age}），下限
     *       {@link #FOREIGN_CLAIM_RECHECK_MIN_MS}（1 分钟，防抖成忙循环），
     *       上限 = 宽限期本身（时钟回拨让 age 为负时不能排出一个比宽限期还长的任务）。
     *       这样最多等满 15 分钟，比修正前的最长 30 天少三个数量级。</li>
     *   <li><b>单飞</b>：{@link #foreignClaimRecheckScheduled} 的 CAS 保证全进程同时
     *       只有一个到期复查在途。一季一次就够 —— 复查的动作是<b>重跑整轮 sweep</b>，
     *       它会把库里<b>所有</b>欠账季重新裁定一遍，包括我们这次跳过的那些。</li>
     *   <li><b>插件已停用就不再动</b>：任务体先判 {@code plugin.isEnabled()}。
     *       停用后继续跑结算会走到已拆掉的 {@code playerDataStore} / 调度器上，
     *       既无意义又会在日志里留一堆 NPE；单纯 return 即可（闸已在任务体开头复位）。</li>
     *   <li><b>撞上进行中的 sweep 要重排</b>：{@link #settleIfNeededAsync} 有防重入闸，
     *       撞上时它是<b>空转</b>的 —— 本次复查会白丢。用最短间隔再排一次，但以
     *       {@link #FOREIGN_CLAIM_RECHECK_MAX_REARMS} 封顶，避免库停摆时退化成
     *       永不终止的每分钟定时器（那等于把"没有自愈"换成"无限自旋"）。</li>
     *   <li><b>调度器已关闭（停用窗口）</b>：{@code runTaskLaterAsynchronously} 抛
     *       {@link IllegalStateException} —— 与 {@link #scheduleReleaseDrain} 同口径吞掉
     *       并复位闸，<b>不</b>改成同步执行（那会在停用线程上跑完整 sweep）。</li>
     * </ul>
     *
     * <p><b>不排程的情形</b>：本方法只应在"确实跳过了某季"时调用。若 {@code settled_at}
     * 已经被别处改成一个足够新的值，剩余宽限期就是满的 15 分钟 —— 排一个 15 分钟后的
     * 复查是正确且必要的（否则那一季又要等下个季边界）。</p>
     *
     * @param settledAt 我们观察到的 {@code seasons.settled_at} 原值（claim 写入时间）
     */
    private void scheduleForeignClaimRecheck(long settledAt) {
        long age = System.currentTimeMillis() - settledAt;
        long remaining = FOREIGN_CLAIM_STALE_MS - age;
        if (remaining < FOREIGN_CLAIM_RECHECK_MIN_MS) {
            remaining = FOREIGN_CLAIM_RECHECK_MIN_MS;
        }
        if (remaining > FOREIGN_CLAIM_STALE_MS) {
            // 只可能由时钟回拨（settledAt 在未来）造成：宽限期本身就是上限，
            // 排更久没有意义 —— 下次复查会把同一个未来时间戳再判一次。
            remaining = FOREIGN_CLAIM_STALE_MS;
        }
        if (!foreignClaimRecheckScheduled.compareAndSet(false, true)) {
            return; // 已有一个在途：它会把所有欠账季一起重新裁定
        }
        long delayTicks = Math.max(1L, remaining / 50L); // 毫秒 → tick（20 tick = 1s）
        try {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                // 闸<b>先</b>复位：本任务已经"用掉"了这次排程，无论下面走哪条分支。
                // 复位在 settleIfNeededAsync() 之前是安全的 —— 它只是把 sweep 挪到另一个
                // 异步任务里；若那一轮又跳过某季，它会自己再排一次。
                foreignClaimRecheckScheduled.set(false);
                if (!plugin.isEnabled()) {
                    return; // 停用后继续跑结算会碰已拆掉的 playerDataStore / 调度器
                }
                if (settleInFlight.get()) {
                    // 另一轮 sweep 正在跑：settleIfNeededAsync() 会因防重入<b>直接返回</b>，
                    // 本次复查等于白排；而那一轮很可能早已走过 rebuildPendingReleases 阶段，
                    // 不会替我们补上。用最短间隔再排一次（传原 settledAt → 剩余宽限期已 ≤ 0
                    // → 正落到 FOREIGN_CLAIM_RECHECK_MIN_MS）。
                    // 有上限：一个卡死的 sweep（库停摆）不能让这里退化成每分钟一次的永久定时器。
                    if (foreignClaimRecheckRearms.incrementAndGet()
                            <= FOREIGN_CLAIM_RECHECK_MAX_REARMS) {
                        scheduleForeignClaimRecheck(settledAt);
                    } else {
                        plugin.getLogger().warning("Giving up on the periodic re-check of stale"
                                + " season settlement claims: a settlement sweep has been in"
                                + " flight across " + FOREIGN_CLAIM_RECHECK_MAX_REARMS
                                + " re-check(s) (the database is probably hung). The claim is"
                                + " NOT lost: the next plugin start or season boundary"
                                + " re-evaluates it.");
                    }
                    return;
                }
                foreignClaimRecheckRearms.set(0);
                plugin.getLogger().info("Re-evaluating season settlement claims now that the"
                        + " foreign claim seen earlier has passed the"
                        + " " + (FOREIGN_CLAIM_STALE_MS / 60000L) + "min staleness threshold.");
                settleIfNeededAsync();
            }, delayTicks);
        } catch (IllegalStateException ignored) {
            foreignClaimRecheckScheduled.set(false); // 停用窗口：任务不会执行
        }
    }

    /** 是否还有条目值得自动重试（重排次数未耗尽）。 */
    private boolean hasDrainablePending() {
        for (Integer seasonId : pendingReleaseRetries.keySet()) {
            Integer attempts = releaseDrainAttempts.get(seasonId);
            if (attempts == null || attempts < MAX_DRAIN_ATTEMPTS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 后台重试一次全部待释放季（异步线程）。成功 → 清条目（下一轮 sweep 会正常结算该季）；
     * 失败 → 计数 +1 并重排，耗尽 {@link #MAX_DRAIN_ATTEMPTS} 后只告警不再自动重排
     * （条目<b>保留</b>：游标继续被挡住，宁可卡住也不丢奖励；下次 sweep 仍会重试）。
     */
    void drainPendingReleases() {
        try {
            for (Map.Entry<Integer, Long> entry
                    : new LinkedHashMap<>(pendingReleaseRetries).entrySet()) {
                int seasonId = entry.getKey();
                long claimedAt = entry.getValue();
                ReleaseOutcome outcome = releaseSettlement(seasonId, claimedAt);
                if (outcome == ReleaseOutcome.CONNECTION_FAILED) {
                    int attempts = releaseDrainAttempts.merge(seasonId, 1, Integer::sum);
                    if (attempts >= MAX_DRAIN_ATTEMPTS) {
                        plugin.getLogger().severe("Season " + seasonId + " settlement release still"
                                + " failing after " + attempts + " background retries; automatic"
                                + " retries stop here. Season " + seasonId + " stays blocked"
                                + " (rewards are not lost — the cursor will not skip it). Check the"
                                + " database connection; the next settlement sweep retries once more.");
                    }
                    continue;
                }
                pendingReleaseRetries.remove(seasonId);
                releaseDrainAttempts.remove(seasonId);
                plugin.getLogger().info("Season " + seasonId + " settlement claim released by a"
                        + " background retry (outcome=" + outcome + "); the next sweep settles it.");
            }
        } finally {
            releaseDrainScheduled.set(false);
        }
        if (hasDrainablePending()) {
            scheduleReleaseDrain(); // 还有未成功的条目 → 继续重试
        }
    }

    /**
     * F-09-01：写本季台账并回读确认，返回"是否已完整落库"。
     *
     * <p>不调 {@code insertSeasonRewards}：R11 起它虽已改为返回 {@code boolean}
     * （失败方向 = 玩家拿不到奖励，见其 javadoc），但仍是<b>裸 INSERT 单事务</b>——重试
     * 会撞 {@code PK(season_id, uuid)} 而整体回滚，<b>不够</b>做本路径需要的幂等重试。
     * 本方法改走 {@code INSERT OR IGNORE} + 回读计数：幂等使重试不撞主键，配置调大也能
     * 补进新增行；成败判据是"库里实际有几行"而不是"语句有没有抛异常"，比单纯看返回值
     * 更强（部分成功也看得见）。
     * 刻意不包手工事务：本机单共享连接，{@code setAutoCommit(true)} 一旦失败会把
     * 连接永久留在手工提交态、波及全库；幂等语句允许部分成功，由回读计数兜底。
     */
    private boolean writeSeasonLedger(int seasonId, List<LeaderboardEntry> top,
                                      SeasonWindow window) {
        RewardRowsBuild built = buildRewardRows(seasonId, top, window);
        // F1（R12-B）：先判「读失败」，再判「空」——顺序不能反。
        // 参与奖名单读失败时 rows 可能只有 TOP10 那几行（或一行都没有），
        // 与「配置上确实没有奖励要发」完全同形。若先判 isEmpty，读失败会被
        // return true 当成「全部落库」→ claim 保留、游标前进、该季永不回访
        // → 参与奖永久丢失且无任何失败信号（本次修的缺陷）。
        if (built.readFailed()) {
            plugin.getLogger().severe("Season " + seasonId + " reward ledger NOT written: the"
                    + " participant list query FAILED, so the reward set is unknown."
                    + " Writing a partial ledger would silently under-pay the season;"
                    + " the caller releases the settlement claim and retries the whole season"
                    + " on the next sweep.");
            return false;
        }
        List<SeasonRewardRow> rows = built.rows();
        if (rows.isEmpty()) {
            // 零额行：配置上确实没有奖励可发（rewards.enabled=false 由调用方短路，
            // 或各档位金额都是 0）→ 无台账义务，不阻结算。此分支只有在
            // readFailed=false 时才可能到达，故与「读失败导致空」不可混淆。
            return true;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        db.withConnection(conn -> {
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "INSERT " + (db.isSQLite() ? "OR IGNORE" : "IGNORE") + " INTO "
                            + db.getTablePrefix() + "season_rewards (season_id, uuid,"
                            + " rank_position, score, reward_coins, claimed, claimed_at)"
                            + " VALUES (?, ?, ?, ?, ?, 0, NULL)")) {
                for (SeasonRewardRow row : rows) {
                    stmt.setInt(1, row.seasonId());
                    stmt.setString(2, row.uuid().toString());
                    stmt.setInt(3, row.rankPosition());
                    stmt.setLong(4, row.score());
                    stmt.setLong(5, row.rewardCents());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            return Boolean.TRUE;
        });
        int ledgered = db.withConnection(conn -> {
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + db.getTablePrefix()
                            + "season_rewards WHERE season_id = ?")) {
                stmt.setInt(1, seasonId);
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }, -1);
        if (ledgered >= rows.size()) {
            return true;
        }
        plugin.getLogger().severe("Season " + seasonId + " reward ledger write could not be"
                + " confirmed (" + ledgered + " of " + rows.size() + " rows); the caller will"
                + " release this settlement claim and retry on the next sweep — this holds ONLY"
                + " if the release itself succeeds (see any following 'release did NOT take"
                + " effect' line for season " + seasonId + ").");
        return false;
    }

    /**
     * F1（R12-B）：台账行构建的三态结果 —— 「读失败」不得折进「没有行」。
     * 照 {@code DatabaseManager.DifficultWordsLoad} / {@code CheckInLoad} 范式。
     *
     * @param rows       台账行（{@code readFailed=false} 时才是完整的奖励集合）
     * @param readFailed {@code true} = 候选名单查询失败，{@code rows} <b>不完整，
     *                   不可据此判定"本季没有奖励"</b>
     */
    private record RewardRowsBuild(List<SeasonRewardRow> rows, boolean readFailed) {}

    /**
     * 奖励台账行构建：分层 top1 / top2-3 / top4-10 / 参与奖（读 config
     * {@code season.rewards.*}，单位元 → 台账存"分"）。参与奖在配置 &gt;0 时对
     * 窗口内有会话的 uuid 发放（含隐身者——奖励是发放语义不受展示开关约束，
     * 与榜单展示过滤口径不同，plan §1.4）；未上榜参与者的分数字段存 0。
     * 零额行不入台账（无钱无消息）。
     *
     * <p>F1（R12-B）：参与奖名单改走三态读 —— 读失败时返回
     * {@code readFailed=true}（{@code rows} 同时丢弃，防被误用），由
     * {@link #writeSeasonLedger} 转成「台账未写入」。
     */
    private RewardRowsBuild buildRewardRows(int seasonId, List<LeaderboardEntry> top,
                                            SeasonWindow window) {
        List<SeasonRewardRow> rows = new ArrayList<>();
        long top1 = rewardCents("top1", 5000.0);
        long top3 = rewardCents("top3", 2000.0);
        long top10 = rewardCents("top10", 500.0);
        Set<UUID> seen = new HashSet<>();
        for (LeaderboardEntry entry : top) {
            seen.add(entry.playerId);
            long cents = switch (entry.rank) {
                case 1 -> top1;
                case 2, 3 -> top3;
                default -> (entry.rank <= 10) ? top10 : 0L;
            };
            if (cents > 0) {
                rows.add(new SeasonRewardRow(seasonId, entry.playerId, entry.rank, entry.score, cents));
            }
        }
        long participated = rewardCents("participated", 0.0);
        if (participated > 0) {
            DatabaseManager.SessionParticipantsLoad participants = plugin.getDatabaseManager()
                    .loadSessionParticipants(window.start(), window.end());
            if (participants.readFailed()) {
                // 名单不认识 → 台账不完整。整份丢弃，由调用方判失败并释放抢占。
                return new RewardRowsBuild(List.of(), true);
            }
            for (UUID participant : participants.ids()) {
                if (seen.add(participant)) {
                    rows.add(new SeasonRewardRow(seasonId, participant, 0, 0L, participated));
                }
            }
        }
        return new RewardRowsBuild(rows, false);
    }

    /**
     * R-16（低危积压 FIX 3）：{@code season.rewards.*} 的<b>上限</b>（单位：分）。
     *
     * <p><b>原实现的缺陷</b>：{@code rewardCents} 只有地板 {@code Math.max(0L, ...)}，没有天花板。
     * 而 {@code Math.round(double)} 对超范围输入会<b>静默饱和</b>到 {@code Long.MAX_VALUE}
     * （{@code 1e17} → {@code 1e19} 分 → {@code Long.MAX_VALUE}），于是配置里一个手滑
     * （多打两个 0，或 YAML 里写成科学计数法 {@code 1e17}）就会把
     * {@code cents/100.0 ≈ 9.2e16} 送进 {@code depositPlayer} —— 不报错、不告警，
     * Vault 侧要么直接给出天文数字余额，要么在 provider 内部溢出/拒绝，
     * 而配置表里那个值看起来"只是个数字"。
     *
     * <p><b>为什么取 1,000,000 金币</b>：看默认值量级 —— {@code top1 = 5000}、
     * {@code top3 = 2000}、{@code top10 = 500}（元/金币），冠军奖励比其余两档高一到两个
     * 数量级。1,000,000 是默认冠军奖的 <b>200 倍</b>，足够任何"豪爽赛季"的运营设定，
     * 同时远低于 {@code double} 余额开始失真、以及 {@code long} 分制接近上限的区域。
     * 判据是：正当配置碰不到它，配置手滑一定碰得到。
     */
    static final long MAX_REWARD_CENTS = 100_000_000L;

    /**
     * R-16：已就"奖励超上限被钳位"告过警的配置键。
     *
     * <p>一次性而非每次调用：{@code rewardCents} 在<b>每次结算</b>里被叫四次
     * （top1/top3/top10/participated），而结算会在每个午夜与加入补发路径上反复发生 ——
     * 逐次打日志就能把一份配错的配置刷成全屏。但完全静默又会让"我配的钱没发出去"
     * 无从诊断，所以取一次性的中间态（与 {@link #rebuildCapWarned}、
     * {@code warnedNoActiveSeason} 同口径）。
     */
    private final java.util.Set<String> rewardClampWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 奖励金额读配置（元，double）→ "分"（long，对齐 RewardEngine 记账惯例）。 */
    private long rewardCents(String key, double defaultValue) {
        String configKey = "season.rewards." + key;
        double amount = plugin.getConfigManager().getMainConfig()
                .getDouble(configKey, defaultValue);
        long cents = Math.round(amount * 100.0);
        // R-16：地板（0）与天花板一起钳。地板必须在 —— 负数金额等于"倒扣玩家的钱"。
        // 比较放在 Math.max 之前：NaN 经 Math.round 得 0、+Infinity 得 Long.MAX_VALUE，
        // 两者都会被这条比较或地板收进合法区间，不需要额外判 NaN/Inf。
        if (cents > MAX_REWARD_CENTS) {
            if (rewardClampWarned.add(key)) {
                // 原值（元）与钳位后的值（元）都给出来，服主才能一眼看出是"多打了几个 0"。
                // 原值走 %.0f 定点半点：直接拼 double 会打成 "1.0E17"，
                // 服主对不上自己写的那个数。
                plugin.getLogger().warning("season.rewards." + key + " = "
                        + String.format(Locale.ROOT, "%.0f", amount)
                        + " is absurdly large; clamped to " + formatCoins(MAX_REWARD_CENTS / 100.0)
                        + " coins per player. This warning appears once per key"
                        + " per server lifetime.");
            }
            cents = MAX_REWARD_CENTS;
        }
        return Math.max(0L, cents);
    }

    /** top 快照 JSON（回顾用，免重聚合）：List&lt;{rank,uuid,name,score}&gt;。 */
    private String buildSnapshotJson(List<LeaderboardEntry> top) {
        List<Map<String, Object>> arr = new ArrayList<>(top.size());
        for (LeaderboardEntry entry : top) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", entry.rank);
            row.put("uuid", entry.playerId.toString());
            row.put("name", entry.playerName);
            row.put("score", entry.score);
            arr.add(row);
        }
        return GSON.toJson(arr);
    }

    /**
     * 解析 top_snapshot JSON → 榜单行列表（3A-4 的"上一季冠军"回顾可选用）。
     * 坏数据返回空列表，绝不抛出（回顾路径的容错优先）。
     */
    public static List<SeasonRankRow> parseTopSnapshot(String json) {
        List<SeasonRankRow> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : arr) {
                JsonObject obj = element.getAsJsonObject();
                String uuidRaw = obj.get("uuid").getAsString();
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidRaw);
                } catch (IllegalArgumentException e) {
                    continue; // 脏行跳过，不拖垮整段回顾
                }
                String name = (obj.has("name") && !obj.get("name").isJsonNull())
                        ? obj.get("name").getAsString() : uuidRaw;
                out.add(new SeasonRankRow(obj.get("rank").getAsInt(), uuid, name,
                        obj.get("score").getAsLong()));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    // ============================================================
    //  奖励发放（join 补发 + 结算即时，两条路径共用同一幂等核）
    // ============================================================

    /**
     * 进服补发检查（<b>冻结接口</b>，PlayerJoinListener 消费——对齐
     * {@code consumeOfflineDuelNotice} / join 摘要的既有消费模式）：异步读该玩家的
     * 全部 unclaimed 台账行 → 主线程条件标记（affected==1 才发）+ 发钱 + 个人消息。
     * 秒退复查不通过则不标记，留待下次进服（对齐 notifyPendingCheers 语义）。
     * 玩家在任意子服进服都能领（台账共享）。
     */
    public void claimPendingRewards(Player player) {
        deliverPending(player, false);
    }

    private void deliverPending(Player target, boolean settleTime) {
        // R15（后继审计 F4）：把两种「不发」分开。
        //
        // 原实现 `if (!isEnabled() || target == null) return;` 把两件性质完全不同的事
        // 合并成一个静默 return：
        //   (a) `season.enabled=false` —— 服主刻意关掉，不发是预期，静默 OK；
        //   (b) `currentSeasonId(now) < 1` —— 锚点被改到未来等情况，**台账里可能压着
        //       已结算赛季的 claimed=0 行**，这些钱会永久冻住且玩家与运维都看不到。
        // 台账跨服共享，不随本服开关消失，所以 (b) 必须留下痕迹。
        if (!isSeasonModuleEnabled()) {
            return;
        }
        if (target == null) {
            return;
        }
        if (currentSeasonId(LocalDate.now()) < 1) {
            // 每个玩家每次进服都打会刷屏，所以只记一次（AtomicBoolean）。
            if (warnedNoActiveSeason.compareAndSet(false, true)) {
                plugin.getLogger().warning("Season reward delivery is SKIPPED because no season"
                        + " is currently active (anchor date is in the future or season length"
                        + " is zero). Ledger rows from already-settled seasons — which are shared"
                        + " across servers and survive this switch — will stay unpaid until a"
                        + " season becomes active again. Check season.anchor-date /"
                        + " season.duration-days. (This warning prints once per JVM.)");
            }
            return;
        }
        UUID playerId = target.getUniqueId();
        runAsync(() -> {
            // R12-G1（round12 F1 §7.1，join 补发路径）：改用三态版。两态版的空 List 让
            // 「读失败」与「这个玩家确实没有待发奖励」同形 —— 早退之后没有任何痕迹。
            // join 是离线玩家的**唯一**补发面，静默失败意味着「钱一直挂着且没人知道」
            // （若玩家再也不上线）。发放语义不变：读失败时同样不标记、不发放，
            // 台账留 pending 待下次 join。
            DatabaseManager.PendingSeasonRewardsLoad loaded =
                    plugin.getDatabaseManager().loadPendingSeasonRewards(playerId);
            if (loaded.readFailed()) {
                plugin.getLogger().severe("Season reward delivery SKIPPED for " + target.getName()
                        + ": the pending-reward query FAILED, so it is unknown whether this"
                        + " player is owed a season reward. Nothing was marked or paid; the rows"
                        + " stay pending (claimed=0) and retry on the next join. If this repeats,"
                        + " the database connection is broken and this player may be owed rewards"
                        + " indefinitely.");
                return;
            }
            List<SeasonRewardRow> rows = loaded.byPlayer().get(playerId);
            if (rows == null || rows.isEmpty()) {
                return;
            }
            runMain(() -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null || !online.isOnline()) {
                    return; // 秒退：未标记未发放，留待下次进服
                }
                if (plugin.getEconomy() == null) {
                    // 无 Vault：静默降级——不标记不发放，台账留 pending（Vault 就位后
                    // 任意进服/结算路径补发）。与"只广播不发钱"一致，且不销毁奖励。
                    return;
                }
                deliverRows(online, rows, settleTime);
            });
        });
    }

    /**
     * R7-M1：结算规模收口 —— 一次批量查询（{@link DatabaseManager#loadPendingSeasonRewardsFor}，
     * IN 展开）替代"逐在线玩家各一个异步任务、各自单查"的 N 连查；主线程发放核
     * 与单发版共用 {@link #deliverRows}（在线复检 → 条件标记先于发钱 → 失败回滚）。
     * 批量结果只含"至少一行 pending"的玩家（join 补发路径保留，兜底错过的玩家）。
     *
     * <p><b>R12-G1：读失败不再静默。</b>原实现走两态版 {@code getPendingSeasonRewardsFor}，
     * 它在查询异常时返回空 map，与「确实没人欠奖励」同形 —— 早退后没有任何痕迹。
     * 现改用三态版：{@code readFailed=true} 时记 SEVERE（运维据此知道有一笔钱卡住了），
     * 发放语义不变（不标记、不发放，台账留 pending 待下次 join 重试）。
     */
    private void deliverPendingBatch(Collection<UUID> playerIds, boolean settleTime) {
        if (!isSeasonModuleEnabled() || playerIds == null || playerIds.isEmpty()) {
            return;
        }
        if (currentSeasonId(LocalDate.now()) < 1) {
            // 同 deliverPending 的 (b) 分支：无活动赛季时批量发放同样静默冻结，
            // 也同样是「台账里可能压着已结算赛季的钱」这一种需要留痕的情况。
            if (warnedNoActiveSeason.compareAndSet(false, true)) {
                plugin.getLogger().warning("Batch season reward delivery is SKIPPED because no"
                        + " season is currently active. Already-settled seasons' ledger rows"
                        + " (shared across servers) will stay unpaid until a season becomes"
                        + " active again. (This warning prints once per JVM.)");
            }
            return;
        }
        List<UUID> idSnapshot = List.copyOf(playerIds);
        runAsync(() -> {
            // R12-G1（round12 F1 §7.1）：改走三态版。两态版的空 map 让「读失败」与
            // 「确实没人欠奖励」同形 —— 早退之后没有任何痕迹。此处只补可观测性：
            // 发放语义不变（读失败时同样不标记、不发放，留 pending 待下次 join）。
            DatabaseManager.PendingSeasonRewardsLoad loaded = plugin.getDatabaseManager()
                    .loadPendingSeasonRewardsFor(idSnapshot);
            if (loaded.readFailed()) {
                plugin.getLogger().severe("Season reward delivery SKIPPED for " + idSnapshot.size()
                        + " player(s): the pending-reward query FAILED, so it is unknown whether"
                        + " any of them are owed a season reward. Nothing was marked or paid;"
                        + " the rows stay pending (claimed=0) and retry on the next join."
                        + " If this repeats, the database connection is broken and some players"
                        + " may be owed rewards indefinitely.");
                return;
            }
            Map<UUID, List<SeasonRewardRow>> byPlayer = loaded.byPlayer();
            if (byPlayer.isEmpty()) {
                return;
            }
            runMain(() -> {
                if (plugin.getEconomy() == null) {
                    // 无 Vault：静默降级同单发（不标记不发放，全部留 pending）
                    return;
                }
                for (Map.Entry<UUID, List<SeasonRewardRow>> entry : byPlayer.entrySet()) {
                    Player online = Bukkit.getPlayer(entry.getKey());
                    if (online == null || !online.isOnline()) {
                        continue; // 查询间隙下线：未标记未发放，留待 join 补发
                    }
                    deliverRows(online, entry.getValue(), settleTime);
                }
            });
        });
    }

    /**
     * 单玩家逐行发放核（join 单发 / 结算批量两路径共用）：条件标记先于发钱。
     *
     * <p><b>R-16（审计自查）：付款失败与「不该付款」必须分成两条路，不能共用一个返回值。</b>
     * 原实现只有 {@code paid > 0} 与 {@code else if (eco != null)} 两支，于是
     * {@code money.enabled=false} 这种<b>策略性不发</b>会被当成<b>转账故障</b>回滚成
     * pending —— 该行于是永远发不出去（门一直是关的），却在每次进服与每次结算都被
     * 重试一遍，等于把"少发一次钱"升级成"永久卡住 + 无限重试噪声"。
     * 现在按「本行该不该付钱」（{@link #isMoneyPayable()}）先分流：
     * <ul>
     *   <li><b>该付</b>：照旧标记 → 付款 → 失败则回滚 pending（可重试）；</li>
     *   <li><b>不该付</b>（金币总闸关闭）：标记照做（记录"本季奖励已结清"）、
     *       <b>不付钱也不回滚</b>、也不发个人金额消息（没付钱不能谎报"已到账"）。</li>
     * </ul>
     * 广播不在这里，因此关闭金币不影响「赛季结束」播报（见类 javadoc 行为表）。
     */
    private void deliverRows(Player online, List<SeasonRewardRow> rows, boolean settleTime) {
        UUID playerId = online.getUniqueId();
        // 点读一次并在整批内复用：候选行来自同一次查询，同一次发放里配置不应中途变化；
        // 逐行读会让一屏 11 行各读一次配置（HotReload 之后还可能前后不一致）。
        boolean moneyPayable = isMoneyPayable();
        for (SeasonRewardRow row : rows) {
            // 条件 UPDATE 先于发钱（CheckInManager 先例）：崩溃/双击/双服
            // 竞态都不可能二次发放
            if (!plugin.getDatabaseManager()
                    .markSeasonRewardClaimed(row.seasonId(), playerId)) {
                continue; // 已被他路径/他服标记
            }
            if (!moneyPayable) {
                // 金币总闸关闭：台账行保留、claim 留在 1（= 该笔奖励按当前策略已结清），
                // 绝不回滚——回滚只会让它变回 pending 并在下次进服/结算再走一遍。
                noteMoneySuppressedOnce(row);
                continue;
            }
            double paid = payReward(online, row.rewardCents());
            if (paid > 0) {
                sendRewardMessage(online, row, paid, settleTime);
            } else if (plugin.getEconomy() != null) {
                // W3A tester M1 收口：Vault 在但转账失败（economy 为 null 的
                // 分支上面已 return）——回滚 claimed，该行回到 pending 供
                // 下次 join/结算重试；不再"永久丢失且无重试面"。
                rollbackClaimAfterFailedPayment(row, online, playerId);
            }
        }
    }

    /**
     * R-16（审计自查）：付款失败后把 {@code claimed} 回滚成 pending，<b>并检查回滚是否真的生效</b>。
     *
     * <h2>修的缺陷</h2>
     * 原实现是裸调用，把 {@link DatabaseManager#unmarkSeasonRewardClaimed} 的 {@code boolean}
     * 直接丢掉（该方法在 {@code SQLException} 时只记一条 WARNING 并返回 {@code false}，
     * 而共享单连接上的 busy/timeout 是常态）。丢返回值的后果链：
     * {@code markSeasonRewardClaimed} 成功（claimed=1）→ 付款失败返回 0 → 回滚 UPDATE 抛
     * {@code SQLException} → 库侧只记 WARNING 返回 {@code false} → 本处丢弃 →
     * 该行<b>停在 claimed=1</b>、没有钱、没有 {@code paid_at}；而
     * {@code loadPendingSeasonRewards*} 只查 {@code claimed = 0}，
     * 于是<b>之后每一次进服与每一次结算都看不见它</b> —— 玩家永久少拿这笔奖励，
     * 既没有 SEVERE 也没有任何重试入口。
     *
     * <p><b>为什么这里必须报 SEVERE</b>：隔两个方法的 {@link #releaseWithRetry} 及其 javadoc
     * 坚持"释放失败必须可见，因为没释放的 claim 会永久搁浅"，而本处的失败形态与代价
     * <b>完全相同</b>（一行奖励永久搁浅），原实现却对此没有 {@code if}。
     *
     * <p><b>为什么不加自动重试队列</b>：回滚已经失败说明库此刻写不动，排一个重试任务
     * 只是把同一条失败 SQL 挪到几十秒后再打一次；而"回滚 pending"本身没有 fencing token
     * （{@code WHERE season_id=? AND uuid=? AND claimed=1}），无界重试会在多服场景下
     * 与别人的 legit 标记互相踩。宁可留一条<b>可直接粘贴</b>的错误，交运维决定。
     */
    private void rollbackClaimAfterFailedPayment(SeasonRewardRow row, Player online,
                                                 UUID playerId) {
        DatabaseManager db = plugin.getDatabaseManager();
        boolean rolledBack = db.unmarkSeasonRewardClaimed(row.seasonId(), playerId);
        if (rolledBack) {
            return;
        }
        // 与 releaseWithRetry 的手工修法同一条纪律：前缀必须取自 getTablePrefix()。
        // 本仓库有过"手工修法 SQL 漏表前缀 → 默认配置下 no such table"的先例，
        // 运维照着抄等于没有修法。
        String prefix = db.getTablePrefix() == null ? "" : db.getTablePrefix();
        String manualFix = "UPDATE " + prefix + "season_rewards SET claimed = 0, claimed_at = NULL"
                + " WHERE season_id = " + row.seasonId()
                + " AND uuid = '" + playerId + "' AND claimed = 1;";
        plugin.getLogger().severe("Season reward payout for " + online.getName() + " (uuid="
                + playerId + ") is now PERMANENTLY UNPAYABLE without manual intervention: season "
                + row.seasonId() + ", rank " + row.rankPosition() + ", amount "
                + formatCoins(row.rewardCents() / 100.0) + " coins (reward_coins="
                + row.rewardCents() + " cents). The Vault deposit FAILED and the rollback of"
                + " claimed=1 FAILED too (the database rejected the UPDATE), so the ledger row is"
                + " stuck at claimed=1 with no money paid and no paid_at. Every future join and"
                + " settlement skips it: the pending-reward queries only select claimed = 0."
                + " This player has lost this reward until an operator fixes it. Manual fix"
                + " (run it, then the row is picked up again on the player's next join): "
                + manualFix);
    }

    /**
     * R-16（审计自查）：金币总闸关闭、奖励被按策略抑制时，<b>每个赛季只记一次</b> INFO。
     *
     * <p>为什么需要这条日志：原实现在这条路径上是<b>完全静默</b>的 —— 服主看到的是
     * "季度结算了、广播也播了、玩家没拿到钱、日志里一个字都没有"，只能靠猜。
     * 而 gate 的实现（{@code RewardEngine.isMoneyEnabled}）藏在另一个类里，
     * 从赛季日志完全看不出来是它拦下的。
     *
     * <p>为什么不逐行打：一季最多 10 + N 行（参与奖可能上万人），逐行就是刷屏。
     * 用赛季号做键（与会 {@link #rewardClampWarned} 同为一次性中间态）：
     * 同一个赛季被重结算/多服补发时只提示一次。
     *
     * <p>级别取 INFO 而不是 WARNING：这是<b>配置状态</b>（出厂默认就是关），不是故障 ——
     * 与 {@code CheckInManager} 把 {@code money.enabled=false} 归为"经济未启用提示"
     * 而非错误同一口径。
     */
    private void noteMoneySuppressedOnce(SeasonRewardRow row) {
        if (!moneySuppressedWarned.add(row.seasonId())) {
            return;
        }
        plugin.getLogger().info("Season " + row.seasonId() + " rewards are recorded in the ledger"
                + " but NOT paid out: the money payout gate is closed"
                + " (progress-system.rewards.types.money.enabled, and/or"
                + " progress-system.rewards.enabled) while Vault IS present. The ledger rows stay"
                + " and are marked claimed (settled, nothing owed) — coins are suppressed by"
                + " configuration, not lost to an error, and this is NOT retried later."
                + " Set progress-system.rewards.types.money.enabled: true to pay the next season.");
    }

    /**
     * 直发赛季奖励（不经 daily-cap，见类 javadoc 经济门裁决）：Vault
     * {@code depositPlayer} + lifetime 记账。
     *
     * <p>返回值语义被 R-16（审计自查）收紧为<b>只有一种</b>：{@code 0} =
     * 「尽量试过了，钱没出去」（无 Vault / provider 抛异常 / 返回 null / 交易失败），
     * {@code >0} = 实付金额。调用方 {@code deliverRows} 据此判定"要不要回滚 pending"。
     *
     * <p><b>本方法<b>不</b>判金币总闸</b>（{@code money.enabled}）：门开在
     * {@link #deliverRows} 的分流处、由 {@link #isMoneyPayable()} 决定。原因见该方法
     * 的 javadoc —— 在本方法里 return 0 等于把"策略性不发"伪装成"转账故障"，
     * 会触发回滚，从而让该行永久卡在 pending 并在每次进服/结算被无限重试。
     */
    private double payReward(Player player, long cents) {
        if (cents <= 0) {
            return 0;
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) {
            return 0;
        }
        double amount = cents / 100.0;
        EconomyResponse response;
        try {
            response = eco.depositPlayer(player, amount);
        } catch (RuntimeException e) {
            // R15（后继审计 F2）：Vault 实现**抛异常**是完全可能的（银行插件世界/账户
            // 配置错、Essentials 序列化失败、provider 桥接里的 ClassCastException）。
            // 原实现没有 try/catch，异常会冲出 deliverRows，而那一行的 claimed=1 已经
            // 写进库了 —— 这笔记账永远没有 paid_at、也没有任何对账路径，奖励**永久丢失**。
            // 抛异常与"返回 0"在语义上是同一件事（没发出去），所以走同一条回滚路径。
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Economy provider THREW while depositing season reward for "
                            + player.getName() + " (cents=" + cents + ")", e);
            return 0;
        }
        if (response == null) {
            // 部分第三方 provider 在内部错误时会返回 null。原实现直接
            // `response.transactionSuccess()` → NPE，后果同上（claimed 已置 1）。
            plugin.getLogger().warning("Economy provider returned NULL response for season"
                    + " reward deposit to " + player.getName() + " (cents=" + cents + ")");
            return 0;
        }
        if (response.transactionSuccess()) {
            // lifetime 记账（供统计/成就口径）；刻意不注入 RewardEngine.todayCoinsCache
            // R11：流水写失败必须可见。钱已经通过 Vault 发出去了，失败方向是「统计偏少」
            // 而不是「玩家没拿到」，所以不阻断、不回滚 —— 但也不能像原来那样丢弃返回值。
            if (!plugin.getDatabaseManager().recordCoinsEarned(player.getUniqueId(), amount)) {
                plugin.getLogger().warning("Season reward paid to " + player.getName()
                        + " (" + amount + ") but the lifetime coin ledger write FAILED —"
                        + " total_coins_earned is now under-reported for this player.");
            }
            return amount;
        }
        plugin.getLogger().warning("Failed to deposit season reward for "
                + player.getName() + ": " + response.errorMessage);
        return 0;
    }

    /** 个人名次/参与消息（结算即时 = reward-earned；join 补发 = reward-pending-join）。 */
    private void sendRewardMessage(Player player, SeasonRewardRow row, double paid,
                                   boolean settleTime) {
        String key;
        String[] placeholders;
        if (row.rankPosition() <= 0) {
            key = "season.participated-reward";
            placeholders = new String[]{"amount", formatCoins(paid)};
        } else if (settleTime) {
            key = "season.reward-earned";
            placeholders = new String[]{"amount", formatCoins(paid),
                    "season", String.valueOf(row.seasonId()),
                    "rank", String.valueOf(row.rankPosition())};
        } else {
            key = "season.reward-pending-join";
            placeholders = new String[]{"rank", String.valueOf(row.rankPosition()),
                    "amount", formatCoins(paid)};
        }
        String message = plugin.getMessageUtil().getPlayerMessage(player, key, placeholders);
        if (message != null && !message.isEmpty()) {
            player.sendMessage(message);
        }
    }

    /** 金币金额玩家可见格式化（对齐 RewardEngine.formatCoins：整数不带小数点）。 */
    private String formatCoins(double v) {
        return v == Math.floor(v)
                ? String.valueOf((long) v)
                : String.format(Locale.ROOT, "%.2f", v);
    }

    // ============================================================
    //  跨服回投（ProxyMessenger 收端调用；本服玩家获结算服广播与发放）
    // ============================================================

    /**
     * 收到他服的 SeasonSettled：本服不重复结算（抢占已保证唯一赢家），只做
     * ①本地广播赛季结束（冠军读快照）②本地在线者发放 ③刷新快照。
     *
     * <p>重复投递安全：发放经 {@code claimed=0} 条件 UPDATE 幂等；回环去重由
     * ProxyMessenger 的 origin 过滤负责（{@code proxy.server-name} 为空时可能
     * 重复播报一次——配置注释已说明，发放仍不受影响）。
     */
    public void applyRelayedSettlement(int seasonId) {
        if (!isEnabled()) {
            return;
        }
        runAsync(() -> {
            String topName = null;
            long topScore = 0;
            SeasonRow row = plugin.getDatabaseManager().getSettledSeason(seasonId);
            if (row != null && row.topSnapshot() != null) {
                List<SeasonRankRow> top = parseTopSnapshot(row.topSnapshot());
                if (!top.isEmpty()) {
                    topName = top.get(0).name();
                    topScore = top.get(0).score();
                }
            }
            final String name = topName;
            final long score = topScore;
            runMain(() -> {
                broadcastEnded(seasonId, name, score);
                // R7-M1：批量发放（同结算路径；逐人单查已收口为单条 IN 查询）
                List<UUID> onlineIds = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    onlineIds.add(online.getUniqueId());
                }
                deliverPendingBatch(onlineIds, true);
            });
            refreshCacheSync();
        });
    }

    /** 收到他服的 SeasonStarted：本地广播新季开场（幂等性由"每个结算周期仅一次"保证）。 */
    public void applyRelayedSeasonStarted(int seasonId) {
        if (!isEnabled()) {
            return;
        }
        runMain(() -> broadcastStarted(seasonId));
    }

    // ============================================================
    //  播报（逐收件人语言渲染；主线程调用）
    // ============================================================

    /** 赛季结束全服广播（冠军名 + 分数；{@code season.announce} 门）。 */
    private void broadcastEnded(int seasonId, String topName, long topScore) {
        if (!isAnnounceEnabled()) {
            return;
        }
        String champion = (topName == null || topName.isEmpty()) ? "—" : topName;
        for (Player online : Bukkit.getOnlinePlayers()) {
            String message = plugin.getMessageUtil().getPlayerMessage(online,
                    "season.ended-broadcast",
                    "season", String.valueOf(seasonId),
                    "top1", champion,
                    "score1", String.valueOf(topScore));
            if (message != null && !message.isEmpty()) {
                online.sendMessage(message);
            }
        }
    }

    /** 赛季开始全服广播（+ complete 音效；plan §2.2 仪式感）。 */
    private void broadcastStarted(int seasonId) {
        if (!isAnnounceEnabled()) {
            return;
        }
        String days = String.valueOf(durationDays());
        for (Player online : Bukkit.getOnlinePlayers()) {
            String message = plugin.getMessageUtil().getPlayerMessage(online,
                    "season.started-broadcast",
                    "season", String.valueOf(seasonId),
                    "days", days);
            if (message != null && !message.isEmpty()) {
                online.sendMessage(message);
                SoundManager.playSound(online, "complete");
            }
        }
    }

    // ============================================================
    //  调度工具（对齐 SocialManager；停用窗口静默放弃）
    //  D2-5：实现已抽取至 util.SchedulerGuard，此处保留薄委托（调用点零改动）
    // ============================================================

    private void runAsync(Runnable task) {
        SchedulerGuard.runAsync(plugin, task);
    }

    private void runMain(Runnable task) {
        SchedulerGuard.runMain(plugin, task);
    }
}
