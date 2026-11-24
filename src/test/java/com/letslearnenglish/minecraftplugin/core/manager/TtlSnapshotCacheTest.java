package com.letslearnenglish.minecraftplugin.core.manager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TtlSnapshotCache} 的回归：TTL 语义、stale-while-revalidate、失败保旧值、单飞。
 *
 * <h2>这个类守的是什么缺陷</h2>
 * 面板每 60 s 拉一次 {@code /api/stats}，5 条聚合在 5 万玩家库上合计 5.5 s 且全程持数据库
 * 唯一连接锁。缓存的作用是<b>把频率降下来</b>，所以它的失效形态全都是"频率又变回去了"：
 *
 * <ol>
 *   <li><b>TTL 不生效</b>（每次请求都算）—— 等于没加缓存，5.5 s 卡顿照旧；</li>
 *   <li><b>过期时同步重算</b> —— 每 TTL 卡一个请求，而且卡在 HTTP 工作线程上；</li>
 *   <li><b>重算失败清空缓存</b> —— 一次偶发 SQL 失败就退回"下一个请求同步冷算"，
 *       正好把最该避免的 5.5 s 请回来；</li>
 *   <li><b>并发不单飞</b> —— 2 个 HTTP 线程同时冷启动 → 2 份 5.5 s 查询，
 *       持的是同一把连接锁，等于把卡顿时长乘 2。</li>
 * </ol>
 *
 * <h2>为什么这些用例不能靠"看返回值对不对"通过</h2>
 * 缓存的所有承诺都是<b>次数与线程</b>承诺（算了几次、在哪个线程算、失败后还留着什么），
 * 返回值永远是对的。所以每个用例都数 {@code loader} 的调用次数，或断言
 * {@code loader} 跑在哪条线程上。
 */
@DisplayName("TtlSnapshotCache：TTL / stale-while-revalidate / 失败保旧值 / 单飞")
class TtlSnapshotCacheTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    /** 冷启动等待上限：远大于用例里的同步时间，正常路径不会触发超时。 */
    private static final long WAIT = TimeUnit.SECONDS.toNanos(10);

    private final List<TtlSnapshotCache<?>> created = new ArrayList<>();
    private final Logger logger = quietLogger();

    @AfterEach
    void tearDown() {
        for (TtlSnapshotCache<?> cache : created) {
            cache.shutdown();
        }
        created.clear();
    }

    private <T> TtlSnapshotCache<T> newCache(java.util.function.Supplier<T> loader, long ttlNanos) {
        TtlSnapshotCache<T> cache = new TtlSnapshotCache<>(loader, ttlNanos, logger, "test");
        created.add(cache);
        return cache;
    }

    // ==================================================================
    // 组 A —— 命中与 TTL
    // ==================================================================

    @Nested
    @DisplayName("组 A —— TTL 内只算一次，过期后重算")
    class TtlSemantics {

        /** 基础契约：TTL 内多次 get 只算一次，且每次拿到<b>同一个</b>对象。 */
        @Test
        @DisplayName("TTL 内重复 get 只算一次，返回同一个对象")
        void hitsWithinTtlComputeOnce() {
            AtomicInteger calls = new AtomicInteger();
            TtlSnapshotCache<String> cache = newCache(() -> "v" + calls.incrementAndGet(), 60 * SECOND);

            String first = cache.get(WAIT);
            String second = cache.get(WAIT);
            String third = cache.get(WAIT);

            assertNotNull(first);
            assertEquals(1, calls.get(),
                    "TTL 内三次 get 必须只算一次喵 —— 每次算就是每次一条全表聚合，"
                            + "这正是要修的那个 5.5 s 卡顿。实际算了 " + calls.get() + " 次");
            assertSame(first, second, "命中时应返回同一个对象，不该重新构造");
            assertSame(first, third, "命中时应返回同一个对象，不该重新构造");
        }

        /**
         * 过期后必须重算 —— 否则缓存就成了"永久冻结"，面板永远显示服务刚启动时的数字。
         *
         * <p>TTL 给 1 ms：等 20 ms 必然过期，不依赖机器快慢。用 {@code peekSnapshot}
         * 观察后台重算是否落地（不用 {@code get}，因为 {@code get} 自己就会驱动重算，
         * 那就成了自证）。
         */
        @Test
        @DisplayName("TTL 过期后重算，新值发布到缓存")
        void expiredValueIsRefreshed() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            TtlSnapshotCache<String> cache = newCache(() -> "v" + calls.incrementAndGet(),
                    TimeUnit.MILLISECONDS.toNanos(1));

            assertEquals("v1", cache.get(WAIT));
            Thread.sleep(20);

            // stale-while-revalidate：这一次拿到的是旧值，但必须已经触发后台重算。
            assertEquals("v1", cache.get(WAIT), "过期的第一次 get 必须返回旧值（不等重算）");

            assertEquals("v2", awaitPeek(cache, "v2", 2000),
                    "过期后必须重算出新值并发布喵 —— 只返回旧值而不重算，"
                            + "面板就永久冻结在启动那一刻的数字上");
            assertEquals(2, calls.get(), "过期只该重算一次，实际 " + calls.get() + " 次");
        }

        /**
         * <b>核心性能承诺</b>：过期后的 get 不得阻塞到"等重算完成"。
         *
         * <p>loader 故意睡 400 ms。若实现是"过期就同步重算"，这次 get 会耗掉 400 ms；
         * 现在是 stale-while-revalidate，必须远早于此返回。
         * 阈值取 150 ms（重算要 400 ms），留足调度抖动余量但不至于放过同步实现。
         */
        @Test
        @DisplayName("过期 get 立刻返回旧值，不等后台重算（stale-while-revalidate）")
        void expiredGetDoesNotBlockOnRefresh() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch loaderDone = new CountDownLatch(1);
            TtlSnapshotCache<String> cache = newCache(() -> {
                int n = calls.incrementAndGet();
                if (n > 1) {
                    // 只有"重算"那次慢；冷启动那次要快，否则下面的计时无从比较。
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    loaderDone.countDown();
                }
                return "v" + n;
            }, TimeUnit.MILLISECONDS.toNanos(1));

            assertEquals("v1", cache.get(WAIT));
            Thread.sleep(20); // 保证已过期

            long start = System.nanoTime();
            String stale = cache.get(WAIT);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals("v1", stale, "过期后第一反应必须是交出手上的旧值");
            assertTrue(elapsedMs < 150,
                    "过期后的 get 耗了 " + elapsedMs + " ms，接近重算耗时 400 ms 喵 —— "
                            + "说明它在请求线程上同步重算了。那正是本缓存要消灭的形态："
                            + "每过 TTL 就卡一个请求，而请求线程是 2 线程的 HTTP 池。");

            assertTrue(loaderDone.await(5, TimeUnit.SECONDS), "后台重算没跑完喵");
            assertEquals("v2", awaitPeek(cache, "v2", 2000), "后台重算的结果没有被发布");
        }
    }

    // ==================================================================
    // 组 B —— 失败保留旧值（不得清空）
    // ==================================================================

    @Nested
    @DisplayName("组 B —— 重算异常保留旧值并记 WARNING")
    class FailureKeepsPrevious {

        /**
         * 缺陷本体：刷新抛异常就把缓存清空 → 下一个请求无值可用 → 走冷启动 →
         * <b>同步</b>再算一次（而这次很可能也要 5.5 s，甚至因为库真的坏了而超时）。
         *
         * <p>本用例连做两轮失败后的 get，断言拿到的始终是旧值、且 loader 没被
         * 追加调用（即没有因为"没值"再算）。
         */
        @Test
        @DisplayName("刷新抛异常后，后续 get 仍返回旧值，不清空缓存")
        void exceptionKeepsOldValue() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            TtlSnapshotCache<String> cache = newCache(() -> {
                if (calls.incrementAndGet() == 1) {
                    return "good";
                }
                throw new IllegalStateException("db exploded");
            }, TimeUnit.MILLISECONDS.toNanos(1));

            assertEquals("good", cache.get(WAIT));
            Thread.sleep(20);

            // 触发一次会失败的后台重算
            assertEquals("good", cache.get(WAIT), "过期时仍应返回旧值");
            // 等它真的跑完，再用"不在飞"作判据 —— 不能靠 sleep 猜时机：
            // 判早了会拿到"还没失败"的中间态，于是「失败清空缓存」这个变异能逃过去
            // （本仓实测踩到过：只 sleep 150ms 的版本对 M2 恒绿）。
            awaitRefreshIdle(cache, 2000);

            assertEquals("good", cache.get(WAIT),
                    "刷新失败后必须继续返回旧值喵 —— 清空缓存会让下一个请求退回冷启动，"
                            + "把一次偶发 SQL 失败放大成一次 5.5 s 的全服卡顿");
            assertEquals("good", cache.get(WAIT), "第二次也要保留旧值");
            assertTrue(cache.hasValue(), "失败不得把缓存置空");
        }

        /**
         * 失败必须留下 WARNING：否则服主只能看到"面板数字不动了"，
         * 没有任何线索指向数据库。
         */
        @Test
        @DisplayName("刷新失败必须记 WARNING")
        void refreshFailureLogsWarning() throws Exception {
            CapturingHandler handler = new CapturingHandler();
            Logger loudLogger = Logger.getLogger("TtlSnapshotCacheTest-loud-" + System.nanoTime());
            loudLogger.setUseParentHandlers(false);
            loudLogger.addHandler(handler);

            AtomicInteger calls = new AtomicInteger();
            TtlSnapshotCache<String> cache = new TtlSnapshotCache<>(() -> {
                if (calls.incrementAndGet() == 1) {
                    return "good";
                }
                throw new IllegalStateException("db exploded");
            }, TimeUnit.MILLISECONDS.toNanos(1), loudLogger, "test");
            created.add(cache);

            assertEquals("good", cache.get(WAIT));
            Thread.sleep(20);
            cache.get(WAIT); // 触发重算（会失败）
            awaitCallCount(calls, 2, 2000);
            // 日志在 loader 抛出之后由 refresh() 写；再给一小段时间让 publish 落地，
            // 但判据是"出现过 WARNING"，不是"等够了时间"。
            awaitWarning(handler, 2000);

            assertTrue(handler.hasAtLeast(Level.WARNING),
                    "刷新失败必须记 WARNING 喵，否则服主只看到数字不动、却查不到原因。日志："
                            + handler.describe());
        }
    }

    // ==================================================================
    // 组 C —— 冷启动算在后台线程上（不能占调用线程）
    // ==================================================================

    @Nested
    @DisplayName("组 C —— 首次计算跑在后台线程，调用线程不查库")
    class FirstComputeRunsOffCallerThread {

        /**
         * 「首次请求仍会慢 5.5 s」这件事能不能接受，取决于它<b>慢在谁的线程上</b>。
         * 这里断言 loader 实际执行的线程名既不是调用线程，也不是主线程。
         */
        @Test
        @DisplayName("loader 执行线程名为 lle-stats-refresh-*，不是调用线程")
        void loaderRunsOnDedicatedThread() {
            String callerName = Thread.currentThread().getName();
            List<String> loaderThreads = new ArrayList<>();
            TtlSnapshotCache<String> cache = newCache(() -> {
                synchronized (loaderThreads) {
                    loaderThreads.add(Thread.currentThread().getName());
                }
                return "v";
            }, 60 * SECOND);

            assertEquals("v", cache.get(WAIT));

            assertFalse(loaderThreads.isEmpty(), "loader 根本没被执行喵");
            String loaderThread = loaderThreads.get(0);
            assertTrue(loaderThread.startsWith("lle-stats-refresh-"),
                    "首次计算必须跑在后台刷新线程上喵，实际线程：" + loaderThread
                            + "。跑在调用线程上 = 它持着 HTTP 工作线程查库，"
                            + "而加缓存的前提正是「首次那 5.5 s 不在请求线程上」。");
            assertFalse(loaderThread.equals(callerName),
                    "loader 跑在了调用线程（" + callerName + "）上喵 —— 那就是同步计算");
        }
    }

    // ==================================================================
    // 组 D —— 单飞（并发只算一次）
    // ==================================================================

    @Nested
    @DisplayName("组 D —— 并发冷启动只触发一次计算")
    class SingleFlight {

        /**
         * 缺陷本体：2 个 HTTP 线程同时到达冷启动 → 各自算一次 → 两条 5.5 s 查询
         * 抢同一把连接锁 → 卡顿时长翻倍。
         *
         * <p>用 {@link CyclicBarrier} 把 4 个线程顶在同一瞬间，loader 故意慢 200 ms
         * 让重叠窗口足够宽。
         */
        @Test
        @DisplayName("4 个线程同时冷启动，loader 只执行一次")
        void concurrentColdStartComputesOnce() throws Exception {
            int threads = 4;
            AtomicInteger calls = new AtomicInteger();
            CyclicBarrier barrier = new CyclicBarrier(threads);
            TtlSnapshotCache<String> cache = newCache(() -> {
                calls.incrementAndGet();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "shared";
            }, 60 * SECOND);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return cache.get(WAIT);
                    }));
                }
                for (java.util.concurrent.Future<String> future : futures) {
                    assertEquals("shared", future.get(10, TimeUnit.SECONDS),
                            "并发冷启动的每个线程都必须拿到值（等待共享结果）");
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, calls.get(),
                    "并发冷启动触发了 " + calls.get() + " 次查询喵 —— 必须是 1 次。"
                            + "多次 = 多份全表聚合抢同一把数据库连接锁，卡顿时长成倍增长。");
        }
    }

    // ==================================================================
    // 组 E —— invalidate / shutdown / 冷启动失败
    // ==================================================================

    @Nested
    @DisplayName("组 E —— warmUp / shutdown / 冷启动失败")
    class Lifecycle {

        /**
         * {@code start()} 里调 {@code warmUp()}：把首次 5.5 s 从"第一个浏览器请求"
         * 挪到"面板启动时的后台线程"。断言预热确实算过一次、且第一个请求直接命中。
         */
        @Test
        @DisplayName("warmUp 在后台算一次；之后首个 get 直接命中、不再算")
        void warmUpPrefetchesWithoutBlocking() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch loaderStarted = new CountDownLatch(1);
            TtlSnapshotCache<String> cache = newCache(() -> {
                calls.incrementAndGet();
                loaderStarted.countDown();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "warm";
            }, 60 * SECOND);

            long start = System.nanoTime();
            cache.warmUp();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMs < 50,
                    "warmUp() 必须立刻返回喵（耗时 " + elapsedMs + " ms）—— 它是被 start() 调用的，"
                            + "在里面同步算就会把统计服务的启动拖 5.5 s");
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS), "预热的后台计算没启动喵");

            assertEquals("warm", cache.get(WAIT), "预热完成后第一个请求必须直接命中");
            assertEquals(1, calls.get(), "预热 + 首个请求合计只该算一次，实际 " + calls.get() + " 次");

            cache.warmUp(); // 幂等：已有值时不重复算
            Thread.sleep(50);
            assertEquals(1, calls.get(), "已有值后 warmUp 必须 no-op，不能又算一次");
        }

        @Test
        @DisplayName("shutdown 后 get 返回 null 而不是抛异常或卡住")
        void getAfterShutdownReturnsNull() {
            TtlSnapshotCache<String> cache = new TtlSnapshotCache<>(
                    () -> "v", 60 * SECOND, logger, "test");
            cache.shutdown();

            assertNull(cache.get(WAIT),
                    "已 shutdown 的缓存必须立刻返回 null 喵 —— 抛异常会掀掉 HTTP handler，"
                            + "卡住会占死 worker 线程；null 是调用方已经处理过的路径（503）");
        }

        @Test
        @DisplayName("冷启动 loader 立刻抛异常 → 返回 null，且不缓存 null")
        void coldStartFailureReturnsNullAndCachesNothing() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            TtlSnapshotCache<String> cache = newCache(() -> {
                calls.incrementAndGet();
                throw new IllegalStateException("no db");
            }, 60 * SECOND);

            assertNull(cache.get(WAIT), "冷启动失败必须返回 null（调用方报 5xx，不得用 0 顶替）");
            assertFalse(cache.hasValue(), "失败不得写入缓存（否则会把失败当成有效值）");

            // 下次请求会重试（库恢复后能自愈），这是"保留重试机会"的体现。
            assertNull(cache.get(WAIT));
            // 两次 get 各自触发了一次计算：一次冷启动、一次重试。
            awaitCallCount(calls, 2, 2000);
        }

        @Test
        @DisplayName("TTL 必须为正：0/负数在构造时就被拒")
        void nonPositiveTtlRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new TtlSnapshotCache<String>(() -> "v", 0, logger, "test"),
                    "TTL=0 会让缓存每次请求都过期（等于没缓存），必须在构造时就拒");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new TtlSnapshotCache<String>(() -> "v", -1, logger, "test"),
                    "负 TTL 无意义，必须在构造时就拒");
        }
    }

    // ==================================================================
    // 组 E —— 降级值（loader 正常返回、但自己标记为降级）走与异常相同的路径
    // ==================================================================

    /**
     * 这一组的由来：统计面板的 5 条聚合各自把 {@code SQLException} 折成 {@code 0}，
     * 于是 loader 会「成功地」交出一个全 0 快照。若缓存只认异常，库抖一下就会被冻成
     * 「全服数据是 0」并持续整个 TTL —— 比改造前（只影响一次请求）更糟。
     *
     * <p>所以 {@code TtlSnapshotCache} 必须能通过 {@code degradedTester} 认出这种
     * 「表面成功、实质失败」的值，并走<b>与抛异常完全相同</b>的路径。
     */
    @Nested
    @DisplayName("组 E —— 降级值不发布、不推进时间戳、保留旧值")
    class DegradedValueBehavesLikeFailure {

        /** 用字符串 "DEGRADED" 当降级标记，避免为测试引入额外类型。 */
        private TtlSnapshotCache<String> degradedCache(
                java.util.function.Supplier<String> loader, Logger log) {
            TtlSnapshotCache<String> c = new TtlSnapshotCache<>(
                    loader, TimeUnit.MILLISECONDS.toNanos(1), log, "degraded",
                    v -> "DEGRADED".equals(v));
            created.add(c);
            return c;
        }

        @Test
        @DisplayName("降级值不得发布：后续 get 仍返回上一轮真值")
        void degradedValueIsNotPublished() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            TtlSnapshotCache<String> cache = degradedCache(
                    () -> calls.incrementAndGet() == 1 ? "good" : "DEGRADED", logger);

            assertEquals("good", cache.get(WAIT));
            Thread.sleep(20);

            assertEquals("good", cache.get(WAIT), "过期时先返回旧值");
            awaitCallCount(calls, 2, 2000);
            awaitRefreshIdle(cache, 2000);

            assertEquals("good", cache.get(WAIT),
                    "降级值被当成功值发布了 —— 这正是「库抖一下变成全服数据 0 并冻住整个 TTL」"
                            + "的形态。降级必须走与异常完全相同的路径：不发布、保留旧值。");
            assertEquals("good", cache.get(WAIT));
        }

        @Test
        @DisplayName("降级不得推进时间戳：每次过期后的 get 都会重试")
        void degradedValueDoesNotAdvanceTimestamp() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            TtlSnapshotCache<String> cache = degradedCache(
                    () -> calls.incrementAndGet() == 1 ? "good" : "DEGRADED", logger);

            assertEquals("good", cache.get(WAIT));
            Thread.sleep(20);

            for (int i = 0; i < 3; i++) {
                assertEquals("good", cache.get(WAIT));
                awaitRefreshIdle(cache, 2000);
            }
            assertEquals(4, calls.get(),
                    "降级不该推进时间戳。时间戳一旦推进，一次库抖动就会让面板在整整一个"
                            + " TTL 内显示「全服数据是 0」——改造前只影响一次请求，缓存把它放大成"
                            + " 2 分钟。实际 loader 调用 " + calls.get() + " 次（期望 4 次）。");
        }

        @Test
        @DisplayName("冷启动就降级：get 返回 null（走既有 503 分支），且不入缓存")
        void degradedOnColdStartYieldsNoValue() throws Exception {
            TtlSnapshotCache<String> cache = degradedCache(() -> "DEGRADED", logger);

            assertNull(cache.get(WAIT), "冷启动就降级时没有旧值可保留，必须给 null 让上层走 503");
            awaitRefreshIdle(cache, 2000);
            assertFalse(cache.hasValue(), "降级值不得进缓存");
        }

        @Test
        @DisplayName("降级路径必须记 WARNING（否则服主只看到数字不动）")
        void degradedPathLogsWarning() throws Exception {
            CapturingHandler handler = new CapturingHandler();
            Logger loudLogger = Logger.getLogger("TtlSnapshotCacheTest-degraded-" + System.nanoTime());
            loudLogger.setUseParentHandlers(false);
            loudLogger.addHandler(handler);

            TtlSnapshotCache<String> cache = degradedCache(() -> "DEGRADED", loudLogger);
            cache.get(WAIT);
            awaitWarning(handler, 2000);

            assertTrue(handler.hasAtLeast(Level.WARNING),
                    "降级必须记 WARNING，否则服主只能看到「面板数字不动了」而查不到原因。日志："
                            + handler.describe());
        }

        @Test
        @DisplayName("反向对照：没传 degradedTester 的老构造器照常发布，证明上几条是判据起的作用")
        void absentTesterKeepsLegacyBehavior() throws Exception {
            TtlSnapshotCache<String> cache = newCache(
                    () -> "DEGRADED", TimeUnit.MILLISECONDS.toNanos(1));

            assertEquals("DEGRADED", cache.get(WAIT));
            Thread.sleep(20);
            awaitRefreshIdle(cache, 2000);
            assertEquals("DEGRADED", cache.get(WAIT),
                    "没传 degradedTester 时不知何为降级，只能照常发布 —— 这是老调用方的既有契约");
            assertTrue(cache.hasValue(), "老构造器无判据，任何非 null 值都算成功");
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 轮询等到后台重算<b>不在飞</b>（即上一次刷新已成功或已失败地结束）。 */
    private static void awaitRefreshIdle(TtlSnapshotCache<?> cache, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (!cache.isRefreshInFlight()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("后台重算在 " + timeoutMs + " ms 内没有结束喵");
    }

    /** 轮询等到日志里出现 WARNING 及以上（日志由后台线程异步写，不能靠 sleep）。 */
    private static void awaitWarning(CapturingHandler handler, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (handler.hasAtLeast(Level.WARNING)) {
                return;
            }
            Thread.sleep(5);
        }
    }

    /** 轮询等到 loader 被调用到 {@code expected} 次（确定性地等"后台那次跑完了"）。 */
    private static void awaitCallCount(AtomicInteger calls, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (calls.get() >= expected) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("loader 在 " + timeoutMs + " ms 内只被调用 "
                + calls.get() + " 次（期望 " + expected + " 次）喵 —— "
                + "后台重算没被触发，或触发后被丢弃了");
    }

    /**
     * 轮询 {@link TtlSnapshotCache#peekSnapshot()} 直到缓存里出现期望值。
     *
     * <p><b>为什么不能退回用 {@code get} 轮询</b>：{@code get} 在过期时会触发重算，
     * 轮询它 = 自己把被测对象推进下一轮，"后台重算有没有落地"这件事就永远为真。
     * 这就是本仓记录过的"自证桩"形态，只是换成时间维度。
     */
    private static String awaitPeek(TtlSnapshotCache<String> cache, String expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String last = cache.peekSnapshot();
        while (System.nanoTime() < deadline) {
            last = cache.peekSnapshot();
            if (expected.equals(last)) {
                return last;
            }
            Thread.sleep(5);
        }
        return last;
    }

    private static Logger quietLogger() {
        Logger quiet = Logger.getLogger("TtlSnapshotCacheTest-" + System.nanoTime());
        quiet.setUseParentHandlers(false);
        return quiet;
    }

    /** 收集日志的 Handler（与仓内 CapturingHandler 同款）。 */
    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        synchronized boolean hasAtLeast(Level level) {
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= level.intValue()) {
                    return true;
                }
            }
            return false;
        }

        synchronized String describe() {
            StringBuilder sb = new StringBuilder("[");
            for (LogRecord record : records) {
                sb.append(record.getLevel().getName()).append(':')
                        .append(record.getMessage()).append(" | ");
            }
            return sb.append(']').toString();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
