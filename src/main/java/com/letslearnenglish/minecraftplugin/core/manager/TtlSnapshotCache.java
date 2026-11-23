package com.letslearnenglish.minecraftplugin.core.manager;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
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

    /** TTL，纳秒。 */
    private final long ttlNanos;

    private final Supplier<T> loader;
    private final Logger logger;
    private final String label;

    /** shutdown() 之后置真：不再提交后台任务。 */
    private volatile boolean closed;

    /**
     * @param loader   真正去算的那个 {@code Supplier}（通常直接包一组 DB 查询）
     * @param ttlNanos TTL，纳秒，必须为正
     * @param logger   记 WARNING 用（用插件 logger，不要用 JUL 根 logger）
     * @param label    日志里标识是哪块快照（如 {@code "stats"}）
     */
    TtlSnapshotCache(Supplier<T> loader, long ttlNanos, Logger logger, String label) {
        if (ttlNanos <= 0) {
            throw new IllegalArgumentException("ttlNanos must be positive, got " + ttlNanos);
        }
        this.loader = Objects.requireNonNull(loader, "loader");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.label = Objects.requireNonNull(label, "label");
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
        try {
            value = loader.get();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to compute " + label + " snapshot;"
                    + " keeping the previous value (the dashboard may show stale numbers)", t);
        }
        synchronized (stateLock) {
            if (value != null) {
                snapshot = value;
                stampNanos = System.nanoTime();
            }
            // value == null：不动 snapshot / stampNanos —— 保留旧值，下次请求会再试。
            refreshInFlight = false;
            if (inFlight == future) {
                inFlight = null;
            }
        }
        future.complete(value);
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
}
