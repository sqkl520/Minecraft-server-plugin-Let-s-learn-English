package com.letslearnenglish.minecraftplugin.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GuiNavigationManager} 的返回栈行为守卫。
 *
 * <p>零 Bukkit 依赖：该类只操作 {@link UUID} → {@link java.util.Deque}，动作是裸
 * {@link Runnable}，因此在纯 JVM 下可完整覆盖。
 *
 * <h2>按实现逐行核对得到的真实语义（不是注释转述）</h2>
 * <ul>
 *   <li>{@code pushBack} 用 {@link java.util.ArrayDeque#push}（<b>addFirst</b>）——LIFO 栈顶在队首，
 *       所以「push A → push B」后先弹出 B。</li>
 *   <li>深度上限 {@code MAX_BACK_DEPTH = 32}，超限时 {@code removeLast()} 丢<b>最旧</b>一帧
 *       （队尾），不是拒绝最新的。</li>
 *   <li>{@code popBack} 在弹空时<b>顺手删掉 map 条目</b>（防每玩家一个空 Deque 常驻），
 *       无栈时返回调用方给的 fallback，不抛异常。</li>
 *   <li><b>不去重</b>：同一个 {@link Runnable} 连推两次，栈里就是两帧。</li>
 *   <li>外层 {@link java.util.concurrent.ConcurrentHashMap} 提供跨线程可见性；内层
 *       {@link java.util.ArrayDeque} <b>非线程安全</b> —— 类注释明确「同一玩家的操作串行发生在
 *       主线程」，故本测试只钉「不同玩家并发安全」，不假装支持同一玩家并发。</li>
 *   <li><b>GUI 身份纪律</b>：本类只存闭包，全类不出现任何标题字符串／{@code getTitle} 比较，
 *       身份由闭包自身携带 —— 见 {@link #doesNotUseInventoryTitlesForIdentity()} 的源码 lint。</li>
 * </ul>
 *
 * <p><b>说明</b>：本轮交付环境没有 shell，本文件<b>未经编译、未经运行</b>。断言按
 * {@code GuiNavigationManager} 的实现逐行推导；变异验证的设计见最终报告，实际红/绿需由
 * 有 shell 的一方执行。
 */
class GuiNavigationManagerTest {

    /** 与生产常量 {@code MAX_BACK_DEPTH} 对齐（改上限时本测试会红，提醒复核）。 */
    private static final int MAX_BACK_DEPTH = 32;

    private static final String RELATIVE_SOURCE =
            "src/main/java/com/letslearnenglish/minecraftplugin/core/GuiNavigationManager.java";

    private final GuiNavigationManager nav = new GuiNavigationManager();

    /**
     * 每一帧都是<b>独立对象</b>，靠引用相等识别。
     *
     * <p>刻意<b>不用</b>「不捕获任何变量的空 lambda」当帧：JLS 15.27.4 允许实现缓存并复用同一个
     * 实例，那样「push A → push B → pop 得 B」用 {@code assertSame} 就永远成立（所有帧都是同一个
     * 对象），LIFO 顺序、深度上限丢帧这些断言全部变成空转，变异一个也杀不掉。
     * 普通类每次 {@code new} 必然是不同实例。
     */
    private static final class Frame implements Runnable {
        private final String label;

        Frame(String label) {
            this.label = label;
        }

        @Override
        public void run() {
            // 动作本体无关紧要，身份由对象本身承载
        }

        @Override
        public String toString() {
            return "Frame(" + label + ")";
        }
    }

    private static Frame action(String label) {
        return new Frame(label);
    }

    private static Frame fallbackProbe() {
        return new Frame("fallback");
    }

    /** 读私有 {@code profileBackActions} —— 只为断言「弹空后不留空 Deque 条目」。 */
    @SuppressWarnings("unchecked")
    private Map<UUID, ?> backStacks() {
        try {
            java.lang.reflect.Field f = GuiNavigationManager.class
                    .getDeclaredField("profileBackActions");
            f.setAccessible(true);
            return (Map<UUID, ?>) f.get(nav);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法读取 profileBackActions 喵", e);
        }
    }

    // ==================================================================
    // 组 1 —— 基本栈行为
    // ==================================================================

    @Test
    @DisplayName("push A → push B → pop 得 B → pop 得 A（LIFO，且返回的是同一个闭包对象）")
    void pushPushPopPopIsLifo() {
        UUID id = UUID.randomUUID();
        Runnable a = action("A");
        Runnable b = action("B");

        nav.pushBack(id, a);
        nav.pushBack(id, b);

        assertSame(b, nav.popBack(id, fallbackProbe()), "后压入的必须后弹出（栈顶在队首）");
        assertSame(a, nav.popBack(id, fallbackProbe()), "先压入的最后弹出");
    }

    @Test
    @DisplayName("返回的是压入的同一个实例，不是拷贝/包装")
    void popReturnsSameInstance() {
        UUID id = UUID.randomUUID();
        Runnable only = action("only");

        nav.pushBack(id, only);

        assertSame(only, nav.popBack(id, fallbackProbe()));
    }

    @Test
    @DisplayName("深链顺序：push 0..4 后依次弹出 4,3,2,1,0")
    void deepChainPopsInReversePushOrder() {
        UUID id = UUID.randomUUID();
        List<Runnable> pushed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Runnable r = action("frame-" + i);
            pushed.add(r);
            nav.pushBack(id, r);
        }

        for (int i = 4; i >= 0; i--) {
            assertSame(pushed.get(i), nav.popBack(id, fallbackProbe()),
                    "第 " + (4 - i + 1) + " 次弹出应是 frame-" + i);
        }
    }

    // ==================================================================
    // 组 2 —— 空栈与 fallback
    // ==================================================================

    @Test
    @DisplayName("空栈 pop 返回 fallback，不抛异常；重复调用仍返回 fallback")
    void emptyStackReturnsFallback() {
        UUID id = UUID.randomUUID();
        Runnable fb = fallbackProbe();

        assertSame(fb, nav.popBack(id, fb), "未知玩家应直接拿到 fallback");
        assertSame(fb, nav.popBack(id, fb), "再来一次也一样（不记忆、不抛）");
    }

    @Test
    @DisplayName("栈弹空后回落到 fallback（不是 null）")
    void exhaustedStackFallsBack() {
        UUID id = UUID.randomUUID();
        Runnable fb = fallbackProbe();
        nav.pushBack(id, action("A"));

        assertNotNull(nav.popBack(id, fb));
        assertSame(fb, nav.popBack(id, fb), "弹空之后必须走 fallback");
        assertSame(fb, nav.popBack(id, fb));
    }

    @Test
    @DisplayName("有栈时绝不消费 fallback")
    void nonEmptyStackNeverReturnsFallback() {
        UUID id = UUID.randomUUID();
        Runnable fb = fallbackProbe();
        Runnable a = action("A");
        nav.pushBack(id, a);

        assertSame(a, nav.popBack(id, fb));
        assertNotSame(fb, a, "fallback 不得被当成帧返回");
    }

    @Test
    @DisplayName("fallback 为 null 时返回 null，不抛异常")
    void nullFallbackIsReturnedAsIs() {
        assertNull(nav.popBack(UUID.randomUUID(), null));
    }

    // ==================================================================
    // 组 3 —— 重复 push 同一动作
    // ==================================================================

    @Test
    @DisplayName("同一 Runnable 连推两次 → 栈里两帧（不去重），弹两次都拿到它")
    void duplicatePushCreatesTwoFrames() {
        UUID id = UUID.randomUUID();
        Runnable same = action("same");

        nav.pushBack(id, same);
        nav.pushBack(id, same);

        assertSame(same, nav.popBack(id, fallbackProbe()));
        assertSame(same, nav.popBack(id, fallbackProbe()));
        assertNull(nav.popBack(id, null), "两帧弹完 → 落到 null fallback（证明确实只压了两次）");
    }

    @Test
    @DisplayName("重复 push 不会把对方的帧挤掉（去重若引入必须显式设计）")
    void duplicatePushDoesNotEvictOtherFrames() {
        UUID id = UUID.randomUUID();
        Runnable a = action("A");
        Runnable b = action("B");

        nav.pushBack(id, a);
        nav.pushBack(id, b);
        nav.pushBack(id, a);

        assertSame(a, nav.popBack(id, fallbackProbe()));
        assertSame(b, nav.popBack(id, fallbackProbe()));
        assertSame(a, nav.popBack(id, fallbackProbe()));
    }

    // ==================================================================
    // 组 4 —— clearPlayer（quit 编排点）
    // ==================================================================

    @Test
    @DisplayName("clearPlayer 清空该玩家整条栈，之后再 pop 走 fallback")
    void clearPlayerEmptiesTheStack() {
        UUID id = UUID.randomUUID();
        nav.pushBack(id, action("A"));
        nav.pushBack(id, action("B"));

        nav.clearPlayer(id);

        Runnable fb = fallbackProbe();
        assertSame(fb, nav.popBack(id, fb), "quit 清栈后不得再执行持旧 Player/GUI 引用的闭包");
        assertTrue(backStacks().isEmpty(), "clearPlayer 必须把 map 条目也删掉（防泄漏）");
    }

    @Test
    @DisplayName("clearPlayer 只影响目标玩家，别人的栈原样保留")
    void clearPlayerIsPerPlayer() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Runnable mineAction = action("mine");
        Runnable otherAction = action("other");
        nav.pushBack(mine, mineAction);
        nav.pushBack(other, otherAction);

        nav.clearPlayer(mine);

        assertNull(nav.popBack(mine, null));
        assertSame(otherAction, nav.popBack(other, fallbackProbe()), "别人的栈不得被误清");
    }

    @Test
    @DisplayName("clearPlayer 对未知玩家/重复调用是 no-op，不抛异常")
    void clearPlayerIsIdempotent() {
        UUID id = UUID.randomUUID();

        nav.clearPlayer(id);
        nav.clearPlayer(id);

        assertTrue(backStacks().isEmpty());
    }

    // ==================================================================
    // 组 5 —— 深度上限（FIX 6 / round14 F4）
    // ==================================================================

    @Test
    @DisplayName("深度上限 32：推 40 帧后只留最近 32 帧，最旧的被丢弃（丢队尾不是拒最新）")
    void depthCapDropsOldestFrames() {
        UUID id = UUID.randomUUID();
        List<Runnable> pushed = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Runnable r = action("frame-" + i);
            pushed.add(r);
            nav.pushBack(id, r);
        }

        // 栈里应是 frame-39 ... frame-8（32 帧，最近的在最前）
        for (int i = 39; i >= 40 - MAX_BACK_DEPTH; i--) {
            assertSame(pushed.get(i), nav.popBack(id, fallbackProbe()),
                    "第 " + (40 - i) + " 次弹出应是 frame-" + i);
        }
        assertNull(nav.popBack(id, null),
                "32 帧之后必须为空 —— 最旧的 frame-0..frame-7 应已被 removeLast 丢弃");
    }

    @Test
    @DisplayName("恰好 32 帧时不丢任何帧（边界内不截断）")
    void exactlyMaxDepthKeepsEverything() {
        UUID id = UUID.randomUUID();
        List<Runnable> pushed = new ArrayList<>();
        for (int i = 0; i < MAX_BACK_DEPTH; i++) {
            Runnable r = action("frame-" + i);
            pushed.add(r);
            nav.pushBack(id, r);
        }

        for (int i = MAX_BACK_DEPTH - 1; i >= 0; i--) {
            assertSame(pushed.get(i), nav.popBack(id, fallbackProbe()),
                    "恰好 " + MAX_BACK_DEPTH + " 帧时 frame-" + i + " 必须还在");
        }
    }

    @Test
    @DisplayName("超限丢帧后 map 条目仍在（只是栈被裁剪），继续 push 仍可用")
    void stackRemainsUsableAfterCapTrim() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            nav.pushBack(id, action("frame-" + i));
        }

        Runnable newest = action("newest");
        nav.pushBack(id, newest);

        assertSame(newest, nav.popBack(id, fallbackProbe()),
                "裁剪后队列仍可正常压/弹（removeLast 不应把 deque 弄坏）");
    }

    // ==================================================================
    // 组 6 —— 内存卫生：弹空即删条目
    // ==================================================================

    @Test
    @DisplayName("弹空最后一帧后 map 条目被删除（不留空 Deque 常驻）")
    void poppingLastFrameRemovesMapEntry() {
        UUID id = UUID.randomUUID();
        nav.pushBack(id, action("A"));
        nav.pushBack(id, action("B"));

        nav.popBack(id, fallbackProbe());
        assertEquals(1, backStacks().size(), "还有一帧时条目必须保留");

        nav.popBack(id, fallbackProbe());
        assertTrue(backStacks().isEmpty(),
                "弹空后应 remove(playerId)，否则一次性玩家会留下永久空条目");
    }

    // ==================================================================
    // 组 7 —— 多玩家隔离与并发
    // ==================================================================

    @Test
    @DisplayName("不同玩家的栈互不干扰")
    void stacksAreIsolatedPerPlayer() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        Runnable a = action("A");
        Runnable b = action("B");

        nav.pushBack(one, a);
        nav.pushBack(two, b);

        assertSame(b, nav.popBack(two, fallbackProbe()));
        assertSame(a, nav.popBack(one, fallbackProbe()), "弹别人的栈不得影响自己的");
    }

    /**
     * 只钉「不同 key 的并发 push/pop 安全」——这是 {@code ConcurrentHashMap} 提供的保证。
     * 同一 {@code UUID} 的并发写不在契约内（内层 ArrayDeque 非线程安全，类注释写明所有
     * 调用方均为主线程），故不在此断言，避免把设计取舍伪装成能力。
     */
    @Test
    @DisplayName("8 线程各推 100 帧到不同玩家：全部可原样弹回（ConcurrentHashMap 层并发安全）")
    void concurrentPushForDistinctPlayersIsSafe() throws Exception {
        final int threads = 8;
        final int perThread = 100;
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            ids.add(UUID.randomUUID());
        }

        for (int t = 0; t < threads; t++) {
            final UUID id = ids.get(t);
            Thread worker = new Thread(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < perThread; i++) {
                        nav.pushBack(id, action("t"));
                    }
                    int popped = 0;
                    while (nav.popBack(id, null) != null) {
                        popped++;
                        if (popped > perThread) {
                            failures.incrementAndGet();
                            break;
                        }
                    }
                    if (popped != Math.min(perThread, MAX_BACK_DEPTH)) {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "nav-worker-" + t);
            worker.setDaemon(true);
            worker.start();
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "并发工作线程未在 30 秒内结束");
        assertEquals(0, failures.get(), "不同玩家的并发 push/pop 不应丢帧或报错");
        assertTrue(backStacks().isEmpty(), "全部弹空后不得残留条目");
    }

    // ==================================================================
    // 组 8 —— null 入参（当前契约：不容忍，直接 NPE）
    // ==================================================================

    @Test
    @DisplayName("null playerId：pushBack / popBack / clearPlayer 均抛 NPE（调用方必须给 UUID）")
    void nullPlayerIdIsNotTolerated() {
        // 这些路径生产不可达：所有调用点都传 player.getUniqueId()。
        // 钉住是为了让「顺手给 null 容错」的改动显式暴露，而不是静默吞掉。
        assertThrows(NullPointerException.class, () -> nav.pushBack(null, action("A")));
        assertThrows(NullPointerException.class, () -> nav.popBack(null, fallbackProbe()));
        assertThrows(NullPointerException.class, () -> nav.clearPlayer(null));
    }

    // ==================================================================
    // 组 9 —— GUI 身份纪律（源码 lint）
    // ==================================================================

    /**
     * 本仓硬纪律：GUI 身份用 <b>holder 引用相等</b>，绝不用标题字符串。
     * {@link GuiNavigationManager} 的返回栈只持有闭包，天然不参与身份判定 ——
     * 这条 lint 钉住「不许有人在返回栈里塞标题比较」，否则 ESC 返回语义会随语言/页码漂移。
     */
    @Test
    @DisplayName("返回栈不得用 Inventory 标题做身份：源码里不出现 getTitle/org.bukkit")
    void doesNotUseInventoryTitlesForIdentity() throws Exception {
        Path source = Path.of(RELATIVE_SOURCE);
        assertTrue(Files.isRegularFile(source),
                "必须能读到源码（工作目录应为项目根）: " + source.toAbsolutePath());
        String text = Files.readString(source, StandardCharsets.UTF_8);

        assertTrue(text.contains("class GuiNavigationManager"),
                "扫到的文件不含目标类声明 —— 路径变了，本 lint 已失效");
        assertTrue(text.contains("Map<UUID, Deque<Runnable>>"),
                "返回栈形状应为 UUID → Deque<Runnable>（身份靠闭包，不靠标题）");

        for (String banned : List.of("getTitle", "stripColor", "org.bukkit", "InventoryView",
                "Inventory ")) {
            assertFalse(text.contains(banned),
                    "返回栈里出现 \"" + banned + "\"：GUI 身份只认 holder 引用相等，"
                            + "标题比较会随语言包/页码变化而失效（见本仓 gui-identity 纪律）");
        }
    }
}
