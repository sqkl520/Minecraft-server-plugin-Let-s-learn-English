package com.letslearnenglish.minecraftplugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R15（wave3 后继审计 · 并发专场）：{@code RuntimeModules} 的装配/拆解闸门。
 *
 * <h2>被修的缺陷</h2>
 * <p>原实现是：
 * <pre>
 *   public boolean beginEnable() {
 *       if (active) { return false; }   // ← check
 *       active = true;                  // ← then act，中间没有原子性
 *       return true;
 *   }
 * </pre>
 * {@code active} 是普通 {@code boolean}。两个线程并发调用时<b>都</b>会读到 false、
 * <b>都</b>置 true、<b>都</b>返回 true —— 调用方据此<b>重复装配</b>：监听器注册两遍、
 * 定时任务排两遍、代理通道注册两遍。Bukkit <b>没有</b>「按名字反注册一个监听器」的
 * API（{@code HandlerList.unregisterAll} 只能整插件清），重复注册的监听器无法单独撤销，
 * 会在插件生命周期内一直双份响应事件。
 *
 * <p>拆解侧同理：重复 {@code beginDisable} 会让第二次拆解去碰第一次已置 null 的字段。
 *
 * <h2>修复</h2>
 * <p>{@code AtomicBoolean.compareAndSet} —— 单比特原子翻转，天然只允许一个赢家。
 *
 * <h2>这条测试为什么必须并发跑</h2>
 * <p>单线程顺序调用 {@code beginEnable()} 两次，老实现也能正确返回 true/false，
 * <b>所以功能测试永远是绿的</b>。竞争只在多线程同时进入临界窗口时出现 —— 必须用
 * 闩锁把 N 个线程卡在同一起跑线上再放行，才有机会撞上。
 */
class RuntimeModulesGateRaceTest {

    /**
     * 并发起跑，只有一个线程能拿到 {@code true}。
     *
     * <p>用 {@link CountDownLatch} 让所有线程在 {@code await()} 处对齐，然后同一瞬间
     * 放行 —— 没有这个闩锁，线程会自然错开，老实现也测不出问题（这正是这类缺陷
     * 「跑 100 次也不复现」的原因）。
     */
    @Test
    @DisplayName("并发 beginEnable：N 个线程同时抢，有且只有一个返回 true")
    void concurrentBeginEnableHasSingleWinner() throws Exception {
        final int threads = 32;
        final int rounds = 200; // 多轮提高撞窗口的概率
        int violations = 0;

        for (int round = 0; round < rounds; round++) {
            RuntimeModules modules = new RuntimeModules();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger winners = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (modules.beginEnable()) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "线程池未在限定时间内完成");
            pool.shutdownNow();

            if (winners.get() != 1) {
                violations++;
            }
        }

        assertEquals(0, violations,
                "有 " + violations + " 轮出现了多个/零个赢家。beginEnable 必须是原子的 —— "
                        + "多个赢家意味着重复装配（监听器注册两遍且无法反注册）");
    }

    @Test
    @DisplayName("并发 beginDisable：已装配状态下 N 个线程同时抢，有且只有一个返回 true")
    void concurrentBeginDisableHasSingleWinner() throws Exception {
        final int threads = 32;
        final int rounds = 200;
        int violations = 0;

        for (int round = 0; round < rounds; round++) {
            RuntimeModules modules = new RuntimeModules();
            assertTrue(modules.beginEnable(), "前置：先装配");

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger winners = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (modules.beginDisable()) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            pool.shutdownNow();

            if (winners.get() != 1) {
                violations++;
            }
        }

        assertEquals(0, violations,
                "有 " + violations + " 轮出现了多个/零个拆解赢家 —— 重复拆解会去碰"
                        + "第一次已置 null 的字段");
    }

    @Test
    @DisplayName("顺序语义不变：enable→true、再 enable→false、disable→true、再 disable→false")
    void sequentialSemanticsUnchanged() {
        RuntimeModules modules = new RuntimeModules();

        assertFalse(modules.isActive(), "新建应处于未装配态");
        assertTrue(modules.beginEnable(), "首次 enable 应成功");
        assertTrue(modules.isActive());
        assertFalse(modules.beginEnable(), "重复 enable 应被拒");
        assertTrue(modules.beginDisable(), "首次 disable 应成功");
        assertFalse(modules.isActive());
        assertFalse(modules.beginDisable(), "重复 disable 应被拒");
    }

    /**
     * 启停循环后必须能重新装配 —— 防「CAS 改对了但把状态机搞坏」。
     */
    @Test
    @DisplayName("启停循环：反复 enable/disable 都能正确翻转")
    void enableDisableCycles() {
        RuntimeModules modules = new RuntimeModules();
        for (int i = 0; i < 10; i++) {
            assertTrue(modules.beginEnable(), "第 " + i + " 轮 enable 应成功");
            assertTrue(modules.isActive());
            assertTrue(modules.beginDisable(), "第 " + i + " 轮 disable 应成功");
            assertFalse(modules.isActive());
        }
    }
}
