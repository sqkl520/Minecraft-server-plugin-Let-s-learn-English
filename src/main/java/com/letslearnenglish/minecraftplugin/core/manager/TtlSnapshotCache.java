package com.letslearnenglish.minecraftplugin.core.manager;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 统计快照的<b>单槽时间缓存</b>（TTL + stale-while-revalidate + 单飞重算）。
 *
 * <h2>为什么需要它</h2>
 * 面板的 {@code /api/stats} 每次刷新要跑 5 条全表聚合（{@code COUNT(DISTINCT word)} 在
 * 5 万玩家库上实测 4853 ms），且<b>全程持</b>
 * {@link com.letslearnenglish.minecraftplugin.data.DatabaseManager} 的唯一连接锁 ——
 * 浏览器每 60 s 拉一次，等于每 60 s 让全服所有 DB 操作排队 5.5 s。
 * 这不是"查询写得不够快"，而是"算得太频繁"，所以正确的修法是<b>降低频率</b>，
 * 而不是改语义（把 {@code COUNT(DISTINCT word)} 换成本地词库总数会改变面板口径）。
 *
 * <h2>契约</h2>
 * <ol>
 *   <li><b>不改变返回值</b>：{@link #get} 交出的永远是 {@code loader} 亲手算出的对象，
 *       不包装、不聚合、不修正。缓存只负责"少算几次"。</li>
 *   <li><b>计算永不发生在调用线程上</b>：所有 {@code loader.get()} 都提交到
 *       {@link #refreshExecutor}（<b>单线程</b>，天然串行）。调用线程只会命中缓存或
 *       等待一次 {@link CompletableFuture}，<b>从不</b>亲自查库。这一条是"首次 5.5 s
 *       不落在 HTTP 工作线程（更不落在主线程）"的结构性保证，而不是靠调用方小心。</li>
 *   <li><b>同一时刻最多一次计算在飞</b>：{@link #refreshInFlight} + 单线程 executor 双重保证。
 *       并发请求只等待，不会变成 N 份重复查询。</li>
 *   <li><b>过期不阻塞请求线程</b>：过了 TTL 之后请求线程<b>立刻拿到旧快照</b>，
 *       同时在后台起一次重算，新值从下一次请求开始可见（stale-while-revalidate）。
 *       所以除冷启动外，请求延迟与 TTL、与查询耗时都无关。</li>
 *   <li><b>重算失败保留旧值</b>：{@code loader} 抛出的任何 {@link Throwable} 只记 WARNING、
 *       丢弃本次结果，旧快照<b>原样保留</b>且时间戳<b>不推进</b>（下次请求会再试）。
 *       绝不清空缓存 —— 清空会把一次偶发故障放大成"下一个请求同步冷算"，
 *       正好落在最该避免的 5.5 s 上。</li>
 *   <li><b>「降级值」等同失败</b>（R-TTL-3）：{@code loader} 有两种方式报告失败 ——
 *       抛异常，或<b>正常返回一个自己标记为降级的对象</b>（见构造器
 *       {@code degradedTester}）。后者是统计面板的真实形态：5 条聚合各自把
 *       {@code SQLException} 折成 {@code 0}，于是 loader 会「成功地」交出一个全 0 快照。
 *       若只认异常，库抖一下就会被冻成「全服数据是 0」并持续整个 TTL ——
 *       比改造前（只影响一次请求）更糟。降级值走<b>与异常完全相同</b>的路径：
 *       不发布、不推进时间戳、保留上一轮真值。</li>
 *   <li><b>连续失败升级为 SEVERE</b>（R-TTL-3）：面板是可选的低优先级功能，所以单次失败
 *       只记 WARNING；但「永远是旧值 + 一串 WARNING」这种形态在日志里很难被注意到，
 *       而它意味着<b>面板已经永久停止更新</b>。因此连续
 *       {@value #SEVERE_AFTER_CONSECUTIVE_FAILURES} 次失败后升级为 SEVERE，并在下一次
 *       成功后打一条 INFO 表示已恢复（否则服主无法判断"现在好了没有"）。</li>
 *   <li><b>时刻用 {@link System#nanoTime()}</b>：单调时钟，不受 NTP 校时 / 时区 / 夏令时
 *       跳变影响。本仓有过用 {@code currentTimeMillis()} 判超时、被墙上时钟跳变造成
 *       假过期的缺陷记录，这里刻意沿用 {@code nanoTime} 口径（跨调用只能做差值，
 *       所以时间戳与判断两边都是 {@code nanoTime}）。</li>
 *   <li><b>并发安全</b>：全部可变状态藏在 {@link #stateLock} 后；锁内只做状态转移
 *       （判过期 → 抢占重算权 → 发布结果），<b>不做任何 IO</b>，所以慢查询不会堵住
 *       其它请求线程。</li>
 *   <li><b>可预热</b>：{@link #warmUp()} 让首次计算在服务启动时就在后台跑掉，
 *       把"第一个请求等 5.5 s"变成"启动时后台算一次"。</li>
 * </ol>
 *
 * @param <T> 快照类型（调用方须保证其不可变/只读，本类不清洗它）
 */
final class TtlSnapshotCache<T> {

    /** 重算线程名前缀。daemon：不拖住关服，但仍要在 {@link #shutdown()} 里收，免得 reload 攒线程。 */
    private static final String WORKER_NAME_PREFIX = "lle-stats-refresh-";

    /**
     * 连续失败多少次后把日志从 WARNING 升级为 SEVERE。
     *
     * <p>取 3 而不是 1 的理由：单次 {@code SQLException}（SQLite busy / MySQL
     * {@code socketTimeout}）是<b>噪声</b>，这类失败本来就不该淹没日志；取 3 而不是 10
     * 的理由：一次刷新就是整个 TTL（默认 120 s），3 次 ≈ 6 分钟面板数字一动不动 ——
     * 那时已经不该再是 WARNING 了。
     */
    static final int SEVERE_AFTER_CONSECUTIVE_FAILURES = 3;

    /** 单线程池 = 天然串行，也保证"最多一个查询在飞"。 */
    private final ExecutorService refreshExecutor;

    /** 保护下面四个字段。锁内只做状态转移，不做 IO。 */
    private final Object stateLock = new Object();

    /** 最近一次<b>成功</b>算出的值；null = 冷启动（从未成功过）。 */
    private T snapshot;

    /** 最近一次成功计算的时刻（{@link System#nanoTime()}）；仅当 {@link #snapshot} 非 null 时有意义。 */
    private long stampNanos;

    /** 是否有一次重算正在飞行中。 */
    private boolean refreshInFlight;

    /** 飞行中那次计算的结果载体，供冷启动的等待者做<b>带超时</b>等待。 */
    private CompletableFuture<T> inFlight;

    /**
     * 连续失败次数（异常<b>或</b>降级值都计数）。
     *
     * <p>成功一次即清零。只在日志升级判定里读，不需要对外暴露；但
     * {@link #consecutiveFailures()} 给测试用（否则「连续 N 次升级 SEVERE」这条
     * 只能靠数日志行猜，而日志由后台线程异步写）。
     */
    private int consecutiveFailures;

    /** TTL，纳秒。 */
    private final long ttlNanos;

    private final Supplier<T> loader;
    private final Logger logger;
    private final String label;

    /**
     * 「这个值算降级吗」的判据；{@code null} = 只认异常（纯值缓存，无降级概念）。
     *
     * <p>为什么用 {@link Predicate} 而不是让 {@code T} 实现某个接口：本类是泛型工具，
     * 宿主 {@code StatisticsHttpServer} 的快照是 private record，为了给缓存提供判据
     * 而让它实现公共接口是泄漏。谓词由宿主提供，泛型保持不变。
     */
    private final Predicate<T> degradedTester;

    /** shutdown() 之后置真：不再提交后台任务。 */
    private volatile boolean closed;

    /**
     * @param loader   真正去算的那个 {@code Supplier}（通常直接包一组 DB 查询）
     * @param ttlNanos TTL，纳秒，必须为正
     * @param logger   记 WARNING 用（用插件 logger，不要用 JUL 根 logger）
     * @param label    日志里标识是哪块快照（如 {@code "stats"}）
     */
    TtlSnapshotCache(Supplier<T> loader, long ttlNanos, Logger logger, String label) {
        this(loader, ttlNanos, logger, label, null);
    }

    /**
     * 带降级判据的重载。
     *
     * @param degradedTester 返回 {@code true} 表示该值<b>不可作为成功值发布</b>
     *                       （不推进时间戳、保留上一轮真值）；{@code null} = 无此概念
     */
    TtlSnapshotCache(Supplier<T> loader, long ttlNanos, Logger logger, String label,
                     Predicate<T> degradedTester) {
        if (ttlNanos <= 0) {
            throw new IllegalArgumentException("ttlNanos must be positive, got " + ttlNanos);
        }
        this.loader = Objects.requireNonNull(loader, "loader");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.label = Objects.requireNonNull(label, "label");
        this.degradedTester = degradedTester;
        this.ttlNanos = ttlNanos;
        this.refreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, WORKER_NAME_PREFIX + label);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 取当前快照。<b>永不抛出</b>。
     *
     * <p>三条分支：
     * <ol>
     *   <li><b>未过期</b> → 直接命中；</li>
     *   <li><b>已过期但仍有旧值</b> → 返回旧值 + 后台重算（stale-while-revalidate）；</li>
     *   <li><b>冷启动（无任何成功过的值）</b> → 触发一次后台计算，然后<b>带超时等待</b>
     *       它的结果。没有旧值可给，只能等；但等的是后台线程，本线程不查库。</li>
     * </ol>
     *
     * @param waitTimeoutNanos 冷启动时最多等这么久；超时返回 {@code null}
     *                         （调用方据此报 5xx，不要假造一个值）
     * @return 快照；仅在冷启动失败/超时/被中断时为 {@code null}
     */
    T get(long waitTimeoutNanos) {
        CompletableFuture<T> pending;
        synchronized (stateLock) {
            if (snapshot != null && !isExpiredLocked()) {
                return snapshot; // 命中
            }
            if (snapshot != null) {
                // 过期但有旧值：后台重算，本线程立刻返回旧值。
                startRefreshLocked();
                return snapshot;
            }
            // 冷启动：无值可给，只能等后台那次。
            if (inFlight == null) {
                startRefreshLocked();
            }
            pending = inFlight;
        }
        if (pending == null) {
            // startRefreshLocked 被拒（executor 已关）：本进程已不接活。
            return null;
        }
        try {
            return pending.get(Math.max(0L, waitTimeoutNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            logger.warning("Timed out after " + TimeUnit.NANOSECONDS.toMillis(waitTimeoutNanos)
                    + " ms waiting for the first " + label + " snapshot;"
                    + " the background computation is still running and the next request will use it.");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            // refresh() 内部已把 loader 的异常吞成 WARNING + 保留旧值，不会走到这里；
            // 兜底返回 null（不抛给 HTTP handler 之外的地方）。
            logger.log(Level.WARNING, "Unexpected failure while computing " + label + " snapshot", e);
            return null;
        }
    }

    /**
     * 启动预热：没有值且没有重算在飞时，在后台跑一次。
     *
     * <p>用途见 {@code StatisticsHttpServer.start()} —— 面板启动即预热，把首次
     * 5.5 s 从"第一个浏览器请求"挪到"后台线程"，且此时服主还没打开页面。
     * 已有值或在飞时是 no-op（幂等）。
     */
    void warmUp() {
        synchronized (stateLock) {
            if (snapshot == null) {
                startRefreshLocked();
            }
        }
    }

    /**
     * 抢占重算权并提交后台任务。调用方须持 {@link #stateLock}。
     *
     * <p>{@code refreshInFlight} 与 {@code inFlight} 同时置位，所以"是否有重算在飞"
     * 只有一个真源；刷新结束（成功或失败）时两者一起清。提交被拒（executor 已关）
     * 时回滚并立即完成 future，让等待者不至于卡住。
     */
    private void startRefreshLocked() {
        if (closed || refreshInFlight) {
            return;
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        refreshInFlight = true;
        inFlight = future;
        try {
            refreshExecutor.execute(() -> runRefresh(future));
        } catch (RejectedExecutionException e) {
            refreshInFlight = false;
            inFlight = null;
            future.complete(null);
        }
    }

    /** 后台重算任务体（跑在 lle-stats-refresh-* 线程上）。 */
    private void runRefresh(CompletableFuture<T> future) {
        T value = null;
        Throwable failure = null;
        try {
            value = loader.get();
        } catch (Throwable t) {
            failure = t;
        }

        // 两态判定：抛异常，或（有判据时）返回了一个自标记为降级的值。
        // 降级走与异常完全相同的路径 —— 这是 R-TTL-3 的核心：loader「成功地返回假 0」
        // 不能被当成成功值发布，否则库抖一次就被冻住整个 TTL。
        boolean degraded = failure != null
                || (value != null && degradedTester != null && degradedTester.test(value));
        String reason = failure != null
                ? null
                : (degraded ? "loader returned a degraded snapshot (its aggregate reads failed)" : null);
        int failuresAfter;
        boolean hasSnapshot;
        int recoveredAfter = 0;

        synchronized (stateLock) {
            if (degraded) {
                // 不发布、不推进 stampNanos —— 保留上一轮真值，下次请求会再试。
                consecutiveFailures++;
            } else if (value != null) {
                if (consecutiveFailures > 0) {
                    // 恢复信号：没有它，服主只能从"数字动了"反推，而日志里只剩一串 WARNING。
                    // 记下次数，日志留到锁外打（见 logRefreshOutcome 的理由）。
                    recoveredAfter = consecutiveFailures;
                }
                consecutiveFailures = 0;
                snapshot = value;
                stampNanos = System.nanoTime();
            }
            // value == null 且无异常：loader 主动放弃（无值可发布），按失败计一次但不刷屏。
            else {
                consecutiveFailures++;
            }
            failuresAfter = consecutiveFailures;
            hasSnapshot = snapshot != null;
            refreshInFlight = false;
            if (inFlight == future) {
                inFlight = null;
            }
        }
        if (recoveredAfter > 0) {
            logger.info("Recovered after " + recoveredAfter + " consecutive failed "
                    + label + " snapshot refresh(es); publishing fresh numbers again.");
        }
        logRefreshOutcome(failure, failuresAfter, hasSnapshot, reason);
        future.complete(degraded ? null : value);
    }

    /**
     * 按「连续失败次数」决定日志级别并落一条日志。
     *
     * <p><b>为什么不在锁内打日志</b>：日志 handler 可能很慢（文件 flush / 服主接的
     * Discord 转发插件），在 {@code stateLock} 里做就等于让所有请求线程陪它等 ——
     * 这个类的锁只允许做状态转移。所以失败次数与「有没有旧值」由调用方在锁内取出后传进来。
     *
     * <p><b>为什么要把连续次数写进消息</b>：单看一条「Failed to compute dashboard snapshot」
     * 无法判断这是偶发抖动还是已经死了半小时；「连续 7 次」一眼可判。
     */
    private void logRefreshOutcome(Throwable failure, int failures, boolean hasSnapshot,
                                   String degradedReason) {
        if (failures == 0) {
            return; // 成功路径：不刷日志（每个 TTL 一条 INFO 会淹没日志）
        }
        boolean escalated = failures >= SEVERE_AFTER_CONSECUTIVE_FAILURES;
        Level level = escalated ? Level.SEVERE : Level.WARNING;
        String message = (escalated ? "Still failing" : "Failed") + " to compute " + label
                + " snapshot (consecutive failures: " + failures
                + "); keeping the previous value"
                + (hasSnapshot ? "" : " (none yet — the dashboard will report 503)")
                + (degradedReason == null ? "" : ": " + degradedReason);
        logger.log(level, message, failure);
    }

    /** 调用方须持 {@link #stateLock}。 */
    private boolean isExpiredLocked() {
        return stampNanos != 0L && System.nanoTime() - stampNanos >= ttlNanos;
    }

    /**
     * 关闭后台线程池。关闭位先置，避免 shutdown 之后还有人提交。
     *
     * <p><b>不可重开</b>：关闭后 {@link #get} 恒返回 {@code null}、{@link #warmUp()} 恒为 no-op。
     * 之所以不需要"重开"能力：本类的宿主 {@code StatisticsHttpServer} 在
     * {@code /lea reload} 时是<b>整对象重建</b>（{@code restartStatisticsServerIfNeeded}），
     * 新实例自带一个新缓存。也正因为如此，reload 之后不可能读到 reload 之前的旧快照。
     */
    void shutdown() {
        closed = true;
        refreshExecutor.shutdownNow();
    }

    /**
     * 仅供测试/诊断：是否还有一次重算在飞。
     *
     * <p>为什么必须能观察到它：{@code hasValue()} 在"刷新失败且保留旧值"与
     * "刷新还没跑完"这两种情况下<b>都</b>为 true，测试无法据此区分
     * "失败保旧值"和"还没来得及失败"。少了这个观察点，用例只能靠 sleep 猜时机，
     * 而猜早了就会让"失败清空缓存"这个变异逃过去（已实测踩到）。
     */
    boolean isRefreshInFlight() {
        synchronized (stateLock) {
            return refreshInFlight;
        }
    }

    /** 仅供测试/诊断：当前是否已有成功值（生产代码不依赖它）。 */
    boolean hasValue() {
        synchronized (stateLock) {
            return snapshot != null;
        }
    }

    /**
     * 仅供测试/诊断：<b>无副作用</b>地读当前快照（不判过期、不触发重算）。
     *
     * <p>存在的理由：{@link #get} 在过期时会写（触发重算），所以测试没法用它来
     * "看一眼后台重算有没有落地" —— 一用就把被测对象推进下一轮。这个方法把
     * "观察"与"驱动"分开，让用例能确定性地断言结果，而不是靠 sleep 猜时机。
     */
    T peekSnapshot() {
        synchronized (stateLock) {
            return snapshot;
        }
    }

    /**
     * 仅供测试/诊断：当前连续失败次数（成功一次即清零）。
     *
     * <p>为什么需要它：「连续 3 次失败升级 SEVERE」这条只能用日志验证，而日志是
     * 后台线程异步写的 —— 靠 sleep 猜时机会让「计数被错误清零」这类变异逃走
     * （与 {@link #isRefreshInFlight()} 同一个理由）。
     */
    int consecutiveFailures() {
        synchronized (stateLock) {
            return consecutiveFailures;
        }
    }
}
