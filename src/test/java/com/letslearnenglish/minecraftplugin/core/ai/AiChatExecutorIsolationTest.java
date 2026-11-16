package com.letslearnenglish.minecraftplugin.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * R-17：AI 的 HTTP 与落库<b>不再跑在 {@code ForkJoinPool.commonPool}</b>，且有一个
 * <b>显式的全局在途上限</b>。
 *
 * <h2>为什么这一组必须包含源码级 lint（而不是纯行为测试）</h2>
 * 缺陷的主体是「执行器归属」—— 一个<b>静态</b>属性。构造一个真实的 {@code LetsLearnEnglish}
 * 来验证这件事极难（YAML、DI、Bukkit、真实 API Key 全套），而"某一处 {@code runAsync}
 * 忘了带 executor"这种回归一旦发生，行为测试<b>永远不会红</b>：
 * <ul>
 *   <li>任务照常执行、结果照常正确 —— 只是排到了 commonPool 上；</li>
 *   <li>commonPool 是 JVM 全局的，测试跑在它上面时与生产运行时的可见症状完全一致；</li>
 *   <li>「关服不等它」与「饿死」这两个真实后果只在<b>多人服 + 关服瞬间</b>出现，
 *       单测里没有任何断言能数到它们。</li>
 * </ul>
 * 所以 lint 是主证据：它扫的是<b>源码文本</b>，直接对"单参数的 {@code runAsync(}/{@code supplyAsync(}"
 * 计数断言为 0。把任何一处改回去，lint 立刻变红（见本类 {@code AiExecutorIsolationLint}
 * 的失败消息，那里写明了--变异验证的实际做法）。
 *
 * <h2>行为侧只测得不到 lint 覆盖的那部分</h2>
 * {@code AiExecutor} 被刻意做成<b>不依赖 Bukkit</b> 的静态嵌套类，于是可以用小池
 * （1 线程 + 容量 1 队列 / 1 个在途许可）把三条运行时性质逼出来：
 * <ol>
 *   <li><b>有界 + 拒绝可达且不抛</b>：队满时 {@code submitRun} 返回 false、计数 +1、
 *       调用方<b>不</b>收到 {@code RejectedExecutionException}；</li>
 *   <li><b>shutdown 后新任务被拒绝且不 NPE</b>；</li>
 *   <li><b>全局在途闸</b>：许可耗尽后 {@code tryAcquirePermit()} 返回 false，归还后恢复。</li>
 * </ol>
 */
class AiChatExecutorIsolationTest {

    // ==================================================================
    //  A. 源码级 lint：任何一处 CompletableFuture 异步调用都必须显式带 executor
    // ==================================================================

    @Nested
    @DisplayName("A. 源码 lint：AIChatManager / ChatFeedbackManager 不得再落到 commonPool")
    class AiExecutorIsolationLint {

        /** 被检查的两个文件（相对 {@code src/main/java}）。 */
        private static final List<String> FILES = List.of(
                "com/letslearnenglish/minecraftplugin/core/ai/AIChatManager.java",
                "com/letslearnenglish/minecraftplugin/core/ai/ChatFeedbackManager.java");

        @Test
        @DisplayName("L1 单参数的 runAsync( / supplyAsync( 必须为 0 处")
        void noSingleArgumentAsyncCalls() throws IOException {
            List<String> offenders = new ArrayList<>();
            for (String rel : FILES) {
                String src = stripComments(sourceOf(rel));
                for (String needle : List.of("runAsync(", "supplyAsync(")) {
                    for (int idx = src.indexOf(needle); idx >= 0; idx = src.indexOf(needle, idx + 1)) {
                        // 只统计以 CompletableFuture. 形式出现的那一种（这就是落到默认执行器的形状）。
                        int dot = idx - 1;
                        if (dot < 0 || src.charAt(dot) != '.') {
                            continue;
                        }
                        int end = matchingParen(src, idx + needle.length() - 1);
                        if (end < 0) {
                            continue; // 括号不配对：另一条 lint 会管，这里不误报
                        }
                        String call = src.substring(idx, end + 1);
                        int args = topLevelArgumentCount(call);
                        if (args < 2) {
                            int line = lineOf(src, idx);
                            offenders.add(rel + ":" + line + " -> " + firstLineOf(call));
                        }
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    "以下 CompletableFuture 异步调用没有显式执行器，会落到 ForkJoinPool.commonPool。"
                            + " commonPool 是整个 JVM 共享的（并行度 = 核数-1），HTTP 秒级阻塞会把"
                            + " 本插件的落库任务饿在别人后面；而且它不受 Paper 的"
                            + " waitForAsyncTasksShutdown() 管辖 —— 关服时这些写要么被强杀（丢数据），"
                            + " 要么在插件 disable 后继续跑（刷异常栈）。"
                            + " 正确形状：AIChatManager 用 {\\@code aiExecutor.submit(...)/submitRun(...)}，"
                            + " ChatFeedbackManager 用 {\\@code submitWrite(...)}。违规点：" + offenders);
        }

        @Test
        @DisplayName("L2 两个文件里都不得出现 ForkJoinPool.commonPool 作为执行器")
        void noCommonPoolAsExecutor() throws IOException {
            for (String rel : FILES) {
                String src = stripComments(sourceOf(rel));
                assertFalse(src.contains("ForkJoinPool.commonPool"),
                        rel + " 仍然直接引用了 ForkJoinPool.commonPool ——"
                                + " 这正是 R-17 要修掉的缺陷（注释里提到它做历史说明没问题，"
                                + " 本 lint 已经剥掉注释，所以出现在代码里就是真的在用）。");
            }
        }

        @Test
        @DisplayName("L3 三处 chat_history 落库 + 两处 HTTP 全部改走专属执行器")
        void allFiveCallSitesUseOwnExecutor() throws IOException {
            String ai = stripComments(sourceOf(FILES.get(0)));

            // 三个落库点：system（startConversation）/ user（dispatchChat）/ assistant（HTTP 回调内）。
            // 判定方式：写 system/user/assistant 的 Runnable 变量必须交给 submitRun。
            for (String role : List.of("systemWrite", "userWrite", "assistantWrite")) {
                assertTrue(ai.contains(role),
                        "找不到 " + role + " —— 三处 chat_history 落库的 Runnable 已被重命名/删除。"
                                + " 这是**文本形状**断言：合法的等价重构（改成内联 lambda）会让它误红，"
                                + " 误红必须由人裁决并同步本 lint，不许为了让 lint 过而改回旧形状。");
            }
            assertTrue(countOccurrences(ai, "aiExecutor.submitRun(") >= 3,
                    "submitRun(...) 少于 3 处 —— 三处 chat_history 落库（system/user/assistant）"
                            + " 必须各自显式提交给专属执行器。实际出现 "
                            + countOccurrences(ai, "aiExecutor.submitRun(") + " 处。");
            assertEquals(2, countOccurrences(ai, "aiExecutor.submit(() ->"),
                    "HTTP 请求必须恰好有 2 处走 aiExecutor.submit(...)："
                            + " 对话（dispatchChat）+ 总结（requestConversationSummary）。"
                            + " 少一处意味着那条通道又落回了默认执行器。");
        }

        @Test
        @DisplayName("L4 pending permit 必须严格配对：2 处获取、4 处归还，且预检只读")
        void permitsAreAlwaysReleased() throws IOException {
            String ai = stripComments(sourceOf(FILES.get(0)));
            int acquired = countOccurrences(ai, ".tryAcquirePermit()");
            int released = countOccurrences(ai, ".releasePermit()");
            // 为什么用**精确**值而不是 >=：这条 lint 的全部价值是抓"漏归还"，
            // 而漏一处的计数是 2 ≥ 2 —— 用 >= 会让「删掉 handle 里的那次归还」完全静默、
            // 全局在途闸会随运行时间被慢慢关死（每失败一次漏一格，直到新请求全被拒绝）。
            // 精确值让任何增删都必须由人裁决并同步本断言（本仓 lint 的既定约定）。
            assertEquals(2, acquired,
                    "tryAcquirePermit() 应恰好 2 处：对话 dispatchChat + 总结 requestConversationSummary。"
                            + " 实际 " + acquired + " 处。新增通道时请同步本断言。");
            // 4 处归还的逐条对应（互斥，不会双重归还）：
            //   ① dispatchChat 的开场白额度闸拒绝分支（拿到了但请求没发出，早 return）；
            //   ② dispatchChat 的 try/catch —— 提交前失败（buildRequestBody / appendTranscript
            //      抛 RuntimeException），此刻 .handle 尚未挂上，故由 catch 归还；
            //      这条是 R-18 新增的，与 ③ 靠 holdsPermit 守卫 + 时序互斥；
            //   ③ dispatchChat 的 handle(...)（成功/失败都归还，整条链结束后；有 holdsPermit 守卫）；
            //   ④ 总结通道的 handle(...)（同上，另一条通道）。
            assertEquals(4, released,
                    "releasePermit() 应恰好 4 处（开场白闸提前 return 1 + 提交前 catch 1 + 两条通道的"
                            + " handle 各 1）。实际 " + released + " 处。少一处 = 有条路径拿了许可不还，"
                            + " 全局在途闸会随时间被慢慢关死；多一处 = 可能双重归还，"
                            + " 会把上限放大到失效。注意 ② 与 ③ 的正确性依赖两点：② 只覆盖"
                            + " 「提交前」失败（此时 handle 未挂）、③ 有 holdsPermit 守卫 —— "
                            + "删掉任一条，双重归还就会真实发生。");
            assertTrue(ai.contains("holdsPermit"),
                    "缺少 holdsPermit 守卫 —— handle 里的归还会与 catch 里的归还叠加，"
                            + "一次请求归还两次许可，全局在途上限被悄悄放大。");
            assertTrue(ai.contains("hasAvailablePermit()"),
                    "缺少只读预检 hasAvailablePermit() —— canRequestConversationSummary 会在"
                            + " 预检阶段白白吃掉一个在途许可（占位行判定不该消费资源）。");
        }

        @Test
        @DisplayName("L5 shutdown() 必须关停并等待专属执行器（否则等于把 commonPool 的'关服不等'搬过来）")
        void shutdownDrainsOwnExecutor() throws IOException {
            String ai = stripComments(sourceOf(FILES.get(0)));
            int bodyStart = ai.indexOf("public void shutdown()");
            assertTrue(bodyStart > 0, "找不到 AIChatManager.shutdown()");
            int bodyEnd = matchingBrace(ai, ai.indexOf('{', bodyStart));
            String body = ai.substring(bodyStart, bodyEnd + 1);
            assertTrue(body.contains("shutdownAndAwait("),
                    "shutdown() 没有调用 aiExecutor.shutdownAndAwait(...) —— 换个执行器而不关它，"
                            + " 等于把'关服不等在途任务'这个缺陷原样保留（只是从一个共享池换成一个"
                            + " 私有池，而私有池反而更难被发现）。");
            // 顺序：先置关服标志（让在途任务自己短路），再等待。
            int flag = body.indexOf("shuttingDown = true");
            int drain = body.indexOf("shutdownAndAwait(");
            assertTrue(flag > 0 && flag < drain,
                    "shutdown() 必须先置 shuttingDown 标志再 await —— 反过来的话会白等一整个"
                            + " timeout 才看到任务因为守卫而立刻返回。");
        }

        @Test
        @DisplayName("L6 ChatFeedbackManager 的默认 writeExecutor 不得是 commonPool，且拒绝路径不抛")
        void feedbackWriteExecutorIsOwnPool() throws IOException {
            String fb = stripComments(sourceOf(FILES.get(1)));
            assertTrue(fb.contains("DEFAULT_WRITE_EXECUTOR"),
                    "缺少 DEFAULT_WRITE_EXECUTOR —— 默认落库执行器必须是本类自持的有界池。");
            assertTrue(fb.contains("new ArrayBlockingQueue<>("),
                    "默认执行器必须有界（ArrayBlockingQueue）—— 无界队列等于没有背压。");
            assertTrue(fb.contains("LLEP-FB-"),
                    "落库线程必须命名可辨认（LLEP-FB-#）—— 线程栈里认不出是谁的池就没法排障。");
            assertTrue(fb.contains("setDaemon(true)"),
                    "线程必须是 daemon（与 TtsManager 同式）—— 漏调关停时不能拖住 JVM 退出。");
            assertTrue(fb.contains("catch (RejectedExecutionException"),
                    "缺少 RejectedExecutionException 的 catch —— JDK 21 实测 runAsync/thenRunAsync"
                            + " 在池已关停或队满时会**同步抛出**，异常会从 quit 事件链冒出去。");
        }

        @Test
        @DisplayName("L7 不该改的地方没被改：会话结算仍走 Paper 异步池（受 waitForAsyncTasksShutdown 管辖）")
        void settlementStillUsesPaperAsyncPool() throws IOException {
            String ai = stripComments(sourceOf(FILES.get(0)));
            int idx = ai.indexOf("private void scheduleConversationSettlement(");
            assertTrue(idx > 0, "找不到 scheduleConversationSettlement —— 该方法是会话结算段"
                    + "（计数 + 成就 + 每日挑战）的唯一入口。");
            int end = matchingBrace(ai, ai.indexOf('{', idx));
            String body = ai.substring(idx, end + 1);
            assertTrue(body.contains("Bukkit.getScheduler().runTaskAsynchronously("),
                    "scheduleConversationSettlement 不再走 Paper 的异步池 ——"
                            + " 这**不是**本轮要修的东西：那条通道受 waitForAsyncTasksShutdown()"
                            + " 管辖，本来就有关服收口；它是'结算'（成就/挑战/计数），"
                            + " 与 AI 的 HTTP + chat_history 落库不是同一类工作。"
                            + " 把它也塞进 aiExecutor 会让结算失去关服保障。");
            assertFalse(body.contains("aiExecutor"),
                    "scheduleConversationSettlement 出现了 aiExecutor —— 见上一条："
                            + " 这处刻意保持走 Paper 异步池。");
        }

        // ---------------- 源码工具（本类独立实现，不与他组 lint 共享） ----------------

        private Path srcRoot() {
            // surefire 的 workingDirectory = 项目根（pom.xml 所在）。
            Path direct = Paths.get("src", "main", "java");
            if (Files.isDirectory(direct)) {
                return direct;
            }
            // 兜底：从 user.dir 向上找（IDE 里 workingDirectory 可能是模块目录）。
            Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            while (p != null) {
                Path candidate = p.resolve("src").resolve("main").resolve("java");
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
                p = p.getParent();
            }
            return fail("找不到 src/main/java —— lint 无法读源码；"
                    + " workingDirectory=" + System.getProperty("user.dir"));
        }

        private String sourceOf(String rel) throws IOException {
            Path file = srcRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file),
                    "源码文件不存在：" + file.toAbsolutePath()
                            + " —— 若文件被移动/重命名，请同步更新本 lint 的 FILES 常量。");
            return Files.readString(file, StandardCharsets.UTF_8);
        }
    }

    // ==================================================================
    //  B. 行为：有界池 + 拒绝路径 + 关停
    // ==================================================================

    @Nested
    @DisplayName("B. AiExecutor：有界、拒绝不抛、shutdown 后拒绝且不 NPE")
    class AiExecutorBehaviour {

        @Test
        @DisplayName("队满时 submitRun 返回 false、计数 +1，且不向调用方抛异常")
        void queueFullRejectsWithoutThrowing() throws Exception {
            // 1 线程 + 容量 1：占住线程 + 塞满队列后，第三次提交必然被拒。
            // 这是"容量推导失真时会怎样"的最小复现，不必伪造整个插件。
            AIChatManager.AiExecutor executor = new AIChatManager.AiExecutor(1, 1, 8);

            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            assertTrue(executor.submitRun(() -> {
                started.countDown();
                await(release);
            }), "第一条任务应被接受（占住唯一的工作线程）");
            assertTrue(started.await(5, TimeUnit.SECONDS), "第一条任务没有在 5 秒内启动");

            assertTrue(executor.submitRun(() -> { }), "第二条任务应被接受（进容量为 1 的队列）");

            // 第三次：队满。关键断言是"返回 false 而不是抛"。
            boolean accepted;
            try {
                accepted = executor.submitRun(() -> { });
            } catch (RuntimeException e) {
                fail("队满时 submitRun 向调用方抛了异常（" + e.getClass().getName() + "）——"
                        + " JDK 21 的 runAsync 在队满时会同步抛 RejectedExecutionException，"
                        + " 所以必须在这里 catch 掉；异常冒到 dispatchChat / quit 事件链会中断其后续业务。");
                return; // 不可达
            }
            assertFalse(accepted, "队满时第三条任务竟被接受 —— 队列容量断言失效");
            assertEquals(1L, executor.rejectedByPoolCount(),
                    "池拒绝计数应为 1（拒绝路径可达的凭证）");

            release.countDown();
        }

        @Test
        @DisplayName("队满时 submit(Supplier) 返回异常完成的 future（而不是抛）")
        void queueFullSupplyReturnsFailedFuture() throws Exception {
            AIChatManager.AiExecutor executor = new AIChatManager.AiExecutor(1, 1, 8);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submit(() -> {
                started.countDown();
                await(release);
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS), "第一条任务没有在 5 秒内启动");
            executor.submit(() -> "queued");

            CompletableFuture<String> rejected;
            try {
                rejected = executor.submit(() -> "should not run");
            } catch (RuntimeException e) {
                fail("队满时 submit 向调用方抛了异常（" + e.getClass().getName()
                        + "）—— 必须收敛成异常完成的 future，让下游 .exceptionally 走"
                        + "『清 pendingRequests + 归还许可 + 提示玩家』那条既有分支。");
                return;
            }
            assertNotNull(rejected, "submit 在队满时应返回一个 future（哪怕是失败的）");
            assertTrue(rejected.isCompletedExceptionally(),
                    "队满时返回的 future 应已异常完成 —— 否则下游会一直等一个永远不来的响应");

            release.countDown();
        }

        @Test
        @DisplayName("shutdown 之后：submitRun 返回 false、submit 返回失败 future，都不 NPE")
        void afterShutdownNewTasksAreRejectedWithoutNpe() throws Exception {
            AIChatManager.AiExecutor executor = new AIChatManager.AiExecutor(2, 4, 4);
            assertTrue(executor.tryAcquirePermit(), "前置条件：关停前应能拿到在途许可");
            executor.releasePermit();

            AtomicInteger ran = new AtomicInteger();
            assertEquals(0, executor.shutdownAndAwait(2_000L), "无在途任务时应干净排空（返回 0）");
            assertTrue(executor.isClosed(), "shutdownAndAwait 之后必须处于 closed 状态");

            // 关停后：提交必须被明确拒绝，且**不抛**、**不 NPE**。
            assertFalse(executor.submitRun(ran::incrementAndGet),
                    "关停后 submitRun 应返回 false");
            CompletableFuture<String> failed = executor.submit(() -> "never");
            assertTrue(failed.isCompletedExceptionally(),
                    "关停后 submit 应返回异常完成的 future");
            // future 的异常必须是可读的顶层类型（不是 NPE）。
            try {
                failed.get(1, TimeUnit.SECONDS);
                fail("异常完成的 future 竟正常返回了");
            } catch (ExecutionException e) {
                assertNotNull(e.getCause(), "future 的失败原因不应为 null");
                assertFalse(e.getCause() instanceof NullPointerException,
                        "future 的失败原因是 NPE —— 这正是本节要封死的那种崩溃");
            }

            // 闸也必须在关停后关闭：否则已 disable 的插件还会被"许可放行"骗去发 HTTP。
            assertFalse(executor.tryAcquirePermit(),
                    "关停后 tryAcquirePermit 必须返回 false");
            assertFalse(executor.hasAvailablePermit(),
                    "关停后 hasAvailablePermit 必须返回 false");
            assertEquals(0, ran.get(), "关停后提交的任务一次都不该执行");
        }

        @Test
        @DisplayName("全局在途闸：许可耗尽即拒绝，归还后恢复")
        void globalInFlightGateIsBounded() {
            AIChatManager.AiExecutor executor = new AIChatManager.AiExecutor(2, 8, 2);

            assertTrue(executor.tryAcquirePermit(), "第 1 个许可应能拿到");
            assertTrue(executor.tryAcquirePermit(), "第 2 个许可应能拿到");
            assertFalse(executor.tryAcquirePermit(),
                    "在途上限是 2，第 3 个许可不该拿到 —— 这是「没有全局上限」这个缺陷的"
                            + " 唯一直接断言点（per-player 限流只封住单个玩家）");
            executor.releasePermit();
            assertTrue(executor.tryAcquirePermit(), "归还一个许可后应能再拿到");

            // 计数口径：被闸拒绝的那次不该被算成"池拒绝"（语义不同，运维要能分开看）。
            assertEquals(0L, executor.rejectedByPoolCount(),
                    "在途闸拒绝不该记进池拒绝计数 —— 两者是不同的运维信号"
                            + "（一个是容量推导失真，一个是全服并发打满）");
            assertEquals(2, executor.maxInFlight(), "maxInFlight 应是构造时定值");
        }

        @Test
        @DisplayName("生产参数：线程数 clamp(核数,2,8)、队列 8×线程、在途 4×线程")
        void productionSizingIsDerivedFromCoreCount() {
            AIChatManager.AiExecutor executor = AIChatManager.AiExecutor.forProduction();
            int cores = Runtime.getRuntime().availableProcessors();
            int expectedThreads = Math.max(2, Math.min(8, cores));
            assertEquals(4 * expectedThreads, executor.maxInFlight(),
                    "全局在途上限应为 4 × 线程数（推导见 AiExecutor.forProduction 的注释）");
            assertEquals(8 * expectedThreads, executor.queueCapacity(),
                    "队列容量应为 8 × 线程数");
            assertTrue(executor.queueCapacity() > executor.maxInFlight(),
                    "队列必须比在途上限更宽 —— 否则'池拒绝'会在记账正确时就被踩到，"
                            + " 那时它就变成常态而不是异常信号了");
            executor.shutdownAndAwait(1_000L); // 不泄漏线程（daemon 也不拖 JVM，但别留垃圾）
        }

        @Test
        @DisplayName("非法尺寸立刻失败（避免 0 线程/0 许可的静默死锁）")
        void invalidSizesFailFast() {
            assertThrows(IllegalArgumentException.class,
                    () -> new AIChatManager.AiExecutor(0, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new AIChatManager.AiExecutor(1, 0, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new AIChatManager.AiExecutor(1, 1, 0));
        }

        @Test
        @DisplayName("任务只落在 LLEP-AI-* 线程上（运行期归属断言，补 lint 的盲区）")
        void tasksRunOnNamedDaemonThreads() throws Exception {
            AIChatManager.AiExecutor executor = new AIChatManager.AiExecutor(2, 4, 4);
            AtomicReference<String> threadName = new AtomicReference<>();
            AtomicReference<Boolean> daemon = new AtomicReference<>();
            executor.submit(() -> {
                threadName.set(Thread.currentThread().getName());
                daemon.set(Thread.currentThread().isDaemon());
                return null;
            }).get(5, TimeUnit.SECONDS);
            assertNotNull(threadName.get(), "任务没有执行");
            assertTrue(threadName.get().startsWith("LLEP-AI-"),
                    "任务跑在 " + threadName.get() + " 上 —— 线程名必须以 LLEP-AI- 开头，"
                            + " 否则日志/线程栈里认不出是谁的池（也就没法判断有没有落回 commonPool）");
            assertEquals(Boolean.TRUE, daemon.get(), "线程必须是 daemon");
            assertFalse(threadName.get().contains("ForkJoinPool"),
                    "任务跑在 ForkJoinPool 上 —— 正是本轮要修的缺陷");
            executor.shutdownAndAwait(1_000L);
        }

        @Test
        @DisplayName("awaitTermination 超时会返回残余数并 shutdownNow（不永久挂住关服）")
        void shutdownTimesOutInsteadOfHanging() {
            AIChatManager.AiExecutor executor = new AIChatManager.AiExecutor(1, 4, 4);
            // 用一个"永远不返回"的任务占住唯一工作线程 —— shutdownAndAwait 必然超时。
            // 不用 latch 而是 sleep(1s)：这条用例只关心"超时路径不挂住"，用 sleep 更直白。
            executor.submitRun(() -> {
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            // 100ms 预算：任务不会完成，应干净返回 >=1 而不是抛/永久阻塞。
            int stranded = executor.shutdownAndAwait(100L);
            assertTrue(stranded >= 1,
                    "超时的 shutdown 应报出残余任务数（返回 " + stranded + "）——"
                            + " 那个数字是判『要不要调大 AI_SHUTDOWN_AWAIT_MS』的唯一依据");
        }
    }

    // ==================================================================
    //  工具
    // ==================================================================

    /** 安静的 await：中断只恢复标志（测试里不需要处理）。 */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 剥掉 Java 注释（行注释 + 块注释），保留字符串/字符/文本块里的内容。
     *
     * <p>为什么必须剥：本类多处断言"不得出现 ForkJoinPool.commonPool"，而改动后的注释里
     * <b>刻意</b>保留了历史说明（"改动前是 commonPool"）。不剥注释的话，那些解释性注释
     * 会被当成"还在用 commonPool"，让 lint 变成"谁写注释谁挨罚" —— 那样的测试会逼着
     * 后人删掉有价值的论证，是本类最该避免的假红。
     *
     * <p>字符串与文本块必须保留内容：{@code "LLEP-FB-"} 这类断言就住在字符串里。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' && src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                end = (end < 0) ? n : end + 3;
                out.append(src, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == c) {
                        j++;
                        break;
                    }
                    j++;
                }
                out.append(src, i, Math.min(j, n));
                i = Math.min(j, n);
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                end = (end < 0) ? n : end;
                out.append(' '); // 保留行结构，别让行号偏移
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = (end < 0) ? n : end + 2;
                for (int k = i; k < end; k++) {
                    out.append(src.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 从 {@code open} 处的 {@code (} 找到配对的 {@code )} 下标；不配对返回 -1。 */
    private static int matchingParen(String s, int open) {
        if (open < 0 || open >= s.length() || s.charAt(open) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 同上，针对 {@code {}。 */
    private static int matchingBrace(String s, int open) {
        if (open < 0 || open >= s.length() || s.charAt(open) != '{') {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 数一个参数列表里有几个顶层实参：只看第一层括号内的逗号（跳过嵌套括号与尖括号里的泛型）。
     *
     * <p>为什么要这么麻烦：{@code supplyAsync(() -> foo(a, b), aiExecutor)} 里那个逗号在
     * lambda 体内，不是参数分隔符。简单的 {@code contains(",")} 会把单参数的
     * {@code runAsync(() -> { a(); b(); })} 误判成"有两个参数"—— 恰好漏掉要抓的那一形状。
     */
    private static int topLevelArgumentCount(String call) {
        int open = call.indexOf('(');
        int close = matchingParen(call, open);
        if (open < 0 || close < 0) {
            return -1;
        }
        String inner = call.substring(open + 1, close);
        if (inner.isBlank()) {
            return 0;
        }
        int depth = 0;
        int angle = 0;
        int count = 1;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == '<') {
                angle++;
            } else if (c == '>') {
                angle = Math.max(0, angle - 1);
            } else if (c == ',' && depth == 0 && angle == 0) {
                // 三元表达式里的 '?' ':' 不影响逗号计数；lambda 的参数列表已在括号里。
                count++;
            }
        }
        return count;
    }

    private static int lineOf(String s, int index) {
        int line = 1;
        for (int i = 0; i < index && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String firstLineOf(String call) {
        int nl = call.indexOf('\n');
        String head = (nl < 0) ? call : call.substring(0, nl);
        return head.length() > 90 ? head.substring(0, 90) + "…" : head;
    }

    private static int countOccurrences(String haystack, String needle) {
        Set<Integer> hits = new LinkedHashSet<>();
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            hits.add(i);
        }
        return hits.size();
    }
}
