package com.letslearnenglish.minecraftplugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.letslearnenglish.minecraftplugin.RuntimeModules;

/**
 * {@link RuntimeModules} 的<b>状态机 + 幂等闸 + 记账</b>测试。
 *
 * <p><b>为什么这个类是本批最重要的</b>：{@code /lea reload} 把「停用」从进程终止时的
 * 一次性收尾改成了可反复执行的双向操作。幂等性是整个改造的地基 —— 一旦
 * {@code beginEnable()} 在已装配时仍返回 {@code true}，第二次 reload 会把全部 Manager
 * 重建、监听器注册两遍（同一事件触发两次业务）、定时任务排两份（金币/复习提醒翻倍）。
 * 而这些症状在 {@code mvn test} 里<b>完全不可见</b>：真实路径需要 Bukkit server，
 * {@code enableAll()} 在纯 JVM 下起不来，所以没有任何行为测试会变红。
 *
 * <p><b>为什么用 mock 而不是真实对象</b>：{@link RuntimeModules} 的 javadoc 立了一条硬契约
 * —— 方法体内<b>不得出现任何 Bukkit 类型的方法调用</b>，只允许存引用。因此这里传
 * {@code mock(Listener.class)} / {@code mock(BukkitTask.class)}：它们能被构造（Mockito 用
 * Objenesis，不跑构造、不需要 Bukkit server），但一旦实现胆敢调用它们的任何方法
 *（{@code cancel()} / {@code equals()} / {@code hashCode()}），Mockito 都会记账，
 * 由 {@code verifyNoInteractions} 抓出来。这正是「契约可测」的关键：如果这里改用真实对象
 * 或 {@code verify(mock).cancel()}，就会诱使实现去调用 Bukkit 方法，契约当场失效。
 *
 * <p><b>不覆盖的内容</b>：真正的 cancel / unregister 顺序归 {@code disableAll()}，
 * 由 {@code ServerToggleLintsTest} 的源码 lint 钉住；本类只管状态机本身。
 */
class RuntimeModulesLifecycleTest {

    private RuntimeModules modules;

    @BeforeEach
    void setUp() {
        modules = new RuntimeModules();
    }

    private static Listener listener() {
        return mock(Listener.class);
    }

    private static BukkitTask task() {
        return mock(BukkitTask.class);
    }

    // ==================================================================
    @Nested
    @DisplayName("闸门 —— beginEnable / beginDisable 的幂等性")
    class Gates {

        @Test
        @DisplayName("beginEnable 首次 true、二次 false（不重复装配）")
        void beginEnableIsIdempotent() {
            assertTrue(modules.beginEnable(),
                    "全新实例的 beginEnable() 必须返回 true 喵 —— 返回 false 等于"
                            + "「永远不装配」，插件变成静默空壳（enable 成功但零监听器零任务）。");
            assertTrue(modules.isActive(),
                    "beginEnable() 返回 true 之后 isActive() 必须为 true 喵 —— "
                            + "否则 isRuntimeActive() 恒 false，所有命令入口永久拒绝。");

            assertFalse(modules.beginEnable(),
                    "已开启状态下第二次 beginEnable() 必须返回 false 喵（幂等闸）。\n"
                            + "返回 true 的后果：reload 连点 / reloadAll 重复判定时，"
                            + "enableAll() 会把全部 Manager 重建、监听器注册两遍"
                            + "（同一事件业务执行两次）、定时任务排两份（金币/复习提醒翻倍），"
                            + "旧实例还会因为被覆盖而永远无法收尾。\n"
                            + "这个缺陷在纯 JVM 测试里没有别的地方看得见 —— 本断言是唯一防线。");
            assertTrue(modules.isActive(),
                    "第二次 beginEnable() 返回 false 时不得顺手把状态翻掉 喵 —— "
                            + "「已开启」的语义是保持不变，不是「重新开启」。");
        }

        @Test
        @DisplayName("beginDisable 首次 true、二次 false（不重复拆解）")
        void beginDisableIsIdempotent() {
            modules.beginEnable();

            assertTrue(modules.beginDisable(),
                    "已装配状态下 beginDisable() 必须返回 true 喵 —— 返回 false 等于"
                            + "「永远不拆解」，关闭 server.enabled 后监听器/任务/HTTP 端口全都还活着。");
            assertFalse(modules.isActive(),
                    "beginDisable() 返回 true 之后 isActive() 必须立刻为 false 喵。\n"
                            + "javadoc 要求「先翻标志再干活」：拆解过程会同步触发事件回环"
                            + "（closeInventory → InventoryCloseEvent、endSession → 成就回调），"
                            + "回环里的 isFullyEnabled() 必须在第一纳秒就是 false，"
                            + "否则监听器会继续在半拆的 Manager 上派发业务。");

            assertFalse(modules.beginDisable(),
                    "已关闭状态下第二次 beginDisable() 必须返回 false 喵（幂等闸）。\n"
                            + "返回 true 的后果：disableAll() 会把已置空的字段再走一遍清理，"
                            + "至少是重复落盘（savePlayerData 对着已 null 的 PlayerDataStore 走 null 守卫），"
                            + "更糟的是 D9 的 HandlerList.unregisterAll 之外还会重复 cancel，"
                            + "日志里出现两份「Runtime modules unloaded」。");
        }

        @Test
        @DisplayName("从未 enable 时 beginDisable 返回 false（不会误拆）")
        void beginDisableOnFreshInstanceReturnsFalse() {
            assertFalse(modules.beginDisable(),
                    "全新实例（从未 enable）的 beginDisable() 必须返回 false 喵。\n"
                            + "这一格对应「启动即关闭」（server.enabled=false 时 onEnable 走短路，"
                            + "从未调过 enableAll）以及 onDisable 在 mock 实例上的补建场景。\n"
                            + "返回 true 会让 disableAll() 进入拆解段：对着一堆 null 字段执行"
                            + "「取消任务 / 清会话 / 落盘 / 反注册」，日志宣称「已卸载」而实际"
                            + "什么都没卸 —— 服主据此判断「关闭成功」，排查方向被带偏。\n"
                            + "注意：disableAll() 里 runtimeActive = false 写在闸门<b>之前</b>，"
                            + "所以这一格返回 false 时状态仍会被正确置为「未装配」。");
            assertFalse(modules.isActive(),
                    "从未 enable 的实例，isActive() 必须为 false 喵。");
        }

        @Test
        @DisplayName("enable → disable → enable 三连：第三次 beginEnable 返回 true 且状态为 active")
        void enableDisableEnableRoundTripWorks() {
            assertTrue(modules.beginEnable(), "第一轮 enable 必须成功 喵");

            assertTrue(modules.beginDisable(), "第一轮 disable 必须成功 喵");
            assertFalse(modules.isActive(), "disable 之后必须处于未装配态 喵");

            assertTrue(modules.beginEnable(),
                    "enable → disable → enable 三连的第三次 beginEnable() 必须返回 true 喵。\n"
                            + "这是「双向可反复」的核心承诺：服主改开 → reload → 改关 → reload →"
                            + " 改开 → reload，每一次都必须真的生效。\n"
                            + "若闸门写成单向闩（true 之后永不再翻），第三次返回 false，"
                            + "运行时永远停在拆解后的空壳态，只能重启服务器才能恢复 —— "
                            + "而日志会显示三次 reload 全部成功。");
            assertTrue(modules.isActive(), "第三次 enable 之后 isActive() 必须为 true 喵。");

            // 第四轮再拆一次，确认不是「只能用三次」的巧合。
            assertTrue(modules.beginDisable(), "第二轮 disable 必须成功 喵");
            assertFalse(modules.isActive(), "第二轮 disable 之后必须处于未装配态 喵");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("记账 —— track / releaseAll / snapshot")
    class Bookkeeping {

        @Test
        @DisplayName("track(null) 不抛、不计数")
        void trackNullIsIgnored() {
            assertDoesNotThrow(() -> modules.track((Listener) null),
                    "track((Listener) null) 不得抛异常 喵。\n"
                            + "调用方（enableAll 的 D0 段）会在大量可选模块上无条件 track，"
                            + "模块未启用时拿到 null 是常态；抛 NPE 会直接打断装配，"
                            + "再被 try/catch 回滚成整个运行时装不起来。");
            assertDoesNotThrow(() -> modules.track((BukkitTask) null),
                    "track((BukkitTask) null) 不得抛异常 喵（理由同上）。");

            assertEquals(0, modules.trackedListenerCount(),
                    "track(null) 不得让监听器计数变成 1 喵 —— null 入账后 disableAll 的"
                            + "兜底循环会对 null 调 cancelTaskQuietly（它有 null 守卫所以不炸），"
                            + "但日志统计会虚报「已取消 1 个任务」。");
            assertEquals(0, modules.trackedTaskCount(),
                    "track(null) 不得让任务计数变成 1 喵。");
        }

        @Test
        @DisplayName("track(listener) / track(task) 计数正确，且 null 混入时只计非 null")
        void trackCountsNonNullEntries() {
            Listener l1 = listener();
            Listener l2 = listener();
            BukkitTask t1 = task();
            BukkitTask t2 = task();

            modules.track(l1);
            modules.track(l2);
            modules.track((Listener) null);

            assertEquals(2, modules.trackedListenerCount(),
                    "两次 track(Listener) + 一次 track(null) 后监听器计数应为 2 喵。\n"
                            + "3 = null 被入账（见上一条）；1 = 重复 track 被去重 ——"
                            + "账本语义是「记了几笔」，不是「有几个不同的监听器」，"
                            + "因为取消/统计都要按笔数走。");

            modules.track(t1);
            modules.track(t2);
            modules.track((BukkitTask) null);

            assertEquals(2, modules.trackedTaskCount(),
                    "两次 track(BukkitTask) + 一次 track(null) 后任务计数应为 2 喵。");
        }

        @Test
        @DisplayName("releaseAll 后计数归零，但 isActive 不变（只清记账，不翻状态）")
        void releaseAllClearsBookkeepingOnly() {
            modules.beginEnable();
            modules.track(listener());
            modules.track(task());

            assertEquals(1, modules.trackedListenerCount(), "前置：应有 1 个监听器 喵");
            assertEquals(1, modules.trackedTaskCount(), "前置：应有 1 个任务 喵");

            modules.releaseAll();

            assertEquals(0, modules.trackedListenerCount(),
                    "releaseAll() 之后监听器计数必须归零 喵 —— 否则重装时账本会跨代累积，"
                            + "disableAll 的兜底循环会把上一代已 cancel 的句柄再 cancel 一遍。");
            assertEquals(0, modules.trackedTaskCount(),
                    "releaseAll() 之后任务计数必须归零 喵。");
            assertTrue(modules.isActive(),
                    "releaseAll() <b>不得</b>翻转 active 状态 喵。\n"
                            + "它的职责是「清记账」，不是「停用」：enableAll() 在 beginEnable() "
                            + "成功之后、真正装配之前会调 releaseAll() 做纵深防御（清掉上一次"
                            + "半途失败留下的账），此时状态必须仍是 active；"
                            + "disableAll() 在 D10 末尾调它做收尾，此时状态是 inactive、"
                            + "翻转与否看不出差别 —— 只有前一个调用点能区分正确实现。\n"
                            + "把 releaseAll 写成「清零 + 翻 false」会让 enableAll 在装配途中"
                            + "把自己判成未装配，isFullyEnabled() 全程 false。");
        }

        @Test
        @DisplayName("tasksSnapshot 返回副本：改返回值不影响内部，且两次取到的是不同实例")
        void tasksSnapshotIsDefensiveCopy() {
            BukkitTask t1 = task();
            BukkitTask t2 = task();
            modules.track(t1);

            List<BukkitTask> first = modules.tasksSnapshot();
            assertEquals(1, first.size(), "前置：快照应含 1 个任务 喵");

            // ① 改返回值：clear / add 都不得穿透到内部账本。
            first.clear();
            first.add(t2);

            assertEquals(1, modules.trackedTaskCount(),
                    "清空/追加 tasksSnapshot() 的返回值之后，内部任务计数仍应为 1 喵 —— "
                            + "返回内部 List 的直接引用时，外部的一个 clear() 就会把账本抹掉，"
                            + "disableAll 的兜底 cancel 循环随即变空（漏网任务不再被取消）。");

            List<BukkitTask> second = modules.tasksSnapshot();
            assertEquals(1, second.size(),
                    "再次取快照应仍含 1 个任务（被 clear 掉的不能是内部账本）喵，实际 "
                            + second.size() + " 个。\n"
                            + "2 个说明第一次的 add(t2) 穿透进了内部账本 —— 同样是返回了"
                            + "直接引用。");
            assertSame(t1, second.get(0),
                    "快照里应仍是当初 track 的那个任务引用 喵 —— 存副本不等于复制元素，"
                            + "拿到的必须是同一个句柄，否则 disableAll cancel 的是别的对象。");

            // ② 两次取到的是不同实例（不是同一个被复用的缓冲）。
            assertNotSame(first, second,
                    "两次 tasksSnapshot() 返回了同一个 List 实例 喵 —— 这说明实现是"
                            + "「缓存一个副本反复返回」或「直接返回字段」。\n"
                            + "disableAll() 会边遍历快照边 cancel，若两次拿到同一实例，"
                            + "任何一处残留的 clear()/remove() 都会影响另一次遍历；"
                            + "更直接的问题：调用方对快照的修改会污染下一次读取。\n"
                            + "正确实现每次 new ArrayList<>(tasks)。");
        }

        /**
         * 纵深防御：整个类的公开 API 都不许调用 Bukkit 方法。
         *
         * <p>为什么这条放在本类里：javadoc 的硬契约是「方法体内不得出现任何 Bukkit 类型的
         * 方法调用」，它是 {@link RuntimeModules} 能在纯 JVM 里被测试的<b>唯一</b>前提。
         * 一旦有人在 {@code releaseAll()} 里顺手加一句 {@code tasks.forEach(BukkitTask::cancel)}
         *（看起来更内聚），所有需要 mock Bukkit scheduler 的测试立刻失效，
         * 而生产路径上的行为完全正确 —— 这类「可测性退化」不会被任何功能测试发现。
         *
         * <p>用 {@code verifyNoInteractions} 而不是 {@code verify(mock, never()).cancel()}：
         * 前者的语义是「一个方法都没被调用」，能同时覆盖 equals / hashCode / toString；
         * 后者只堵 cancel 一个方法，实现改成调 {@code task.getTaskId()} 照样绿。
         */
        @Test
        @DisplayName("全流程不调用任何 Bukkit 对象的方法（契约：只存引用）")
        void neverCallsBukkitMethods() {
            Listener l = listener();
            BukkitTask t = task();

            modules.beginEnable();
            modules.track(l);
            modules.track(t);
            modules.isActive();
            modules.trackedListenerCount();
            modules.trackedTaskCount();
            List<BukkitTask> snapshot = modules.tasksSnapshot();
            assertSame(t, snapshot.get(0), "前置：快照里应是 track 的那个任务 喵");
            modules.releaseAll();
            modules.beginDisable();

            verifyNoInteractions(l);
            verifyNoInteractions(t);
        }
    }
}
