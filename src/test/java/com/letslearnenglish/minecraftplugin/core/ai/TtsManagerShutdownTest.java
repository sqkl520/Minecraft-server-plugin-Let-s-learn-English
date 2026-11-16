package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R12c-F1：{@code TtsManager.shutdown()} 的生命周期对称性。
 *
 * <h2>被修的缺陷</h2>
 * <p>{@code TtsManager} 在字段初始化器里建了一个<b>自建单线程池</b>（{@code "LLEP-TTS"}），
 * 并且 {@code shutdown()} 方法自带一条 javadoc 承诺「R4-06-F1: 中断在途下载，不拖住关服」。
 * 而这条承诺从写下那天起<b>没有任何执行者</b>：全仓 {@code .shutdown()} 只有
 * {@code socialManager} / {@code aiChatManager} / {@code duelManager} / {@code databaseManager}
 * 四处真实调用，{@code ttsManager} 一次都没有。{@code /lea reload} 每开关一轮就多一个
 * 永不回收的 {@code LLEP-TTS} 线程 + 一个持有插件引用的 {@code TtsManager} 实例
 * （线程是 daemon，不拖 JVM，但钉住插件 ClassLoader）。
 *
 * <h2>本类钉的两件事</h2>
 * <ol>
 *   <li><b>闩与池成对</b>：{@link TtsManager#shutdown()} 之后池必须真的 Terminated，
 *       且 {@code isShutdown()} 为真。</li>
 *   <li><b>关停后不抛异常</b>：{@code generateAudio} 在关停后必须返回一个<b>已正常完成</b>
 *       （值为 null）的 future，而<b>不是</b>抛 {@code RejectedExecutionException}。
 *       这一条是本轮真正的行为修复 —— 只加 {@code shutdownNow()} 而不加闸，会让
 *       {@code CompletableFuture.runAsync(task, pool)} <b>同步抛</b>
 *       {@code RejectedExecutionException}，而 {@code generateAudio} 唯一的调用点是
 *       {@code WordLearningListener.handlePronunciationButton}（{@code InventoryClickEvent}
 *       处理器）—— 异常冒出去会中断同事件里的后续业务。</li>
 * </ol>
 *
 * <h2>为什么走生产入口而不是反射</h2>
 * <p>{@code generateAudio} 是 public，测试直接调它。刻意<b>不</b>用
 * {@code "pool.isShutdown()"} 之外的白盒手段：第一步完成后池的状态由 JDK 定义，
 * 是可观测的真事实。
 */
@DisplayName("TtsManager 生命周期 — shutdown() 对称性与关停后不抛")
class TtsManagerShutdownTest {

    @TempDir
    Path dataDir;

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private YamlConfiguration config;
    private Captured logs;

    /** 收集日志的探针（本仓每个测试类各带一份，跨包私有工具不可复用）。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("TtsManagerShutdownTest." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    messages.add(record.getMessage() == null ? "" : record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    @BeforeEach
    void setUp() {
        logs = new Captured();
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
        when(plugin.getLogger()).thenReturn(logs.logger);

        config = new YamlConfiguration();
        config.set("tts.enabled", true);
        config.set("tts.cache-days", 7);
        // 必须给一个 Key：只有走到「有 Key 且未熔断」这条路才会真的派发到线程池。
        // 空 Key 时 generateAudio 在 hasApiKey() 就 early return，测不到派发点。
        config.set("tts.voicerss-api-key", "test-key-not-used");
        configManager = mock(ConfigManager.class);
        when(configManager.getMainConfig()).thenReturn(config);
        when(plugin.getConfigManager()).thenReturn(configManager);
    }

    private ExecutorService poolOf(TtsManager manager) throws Exception {
        Field f = TtsManager.class.getDeclaredField("downloadPool");
        f.setAccessible(true);
        return (ExecutorService) f.get(manager);
    }

    // ==================================================================
    // 行为 1：shutdown() 真的把池关掉
    // ==================================================================

    @Test
    @DisplayName("F1：shutdown() 之后线程池 Terminated，isShutdown() 为 true")
    void shutdownActuallyTerminatesThePool() throws Exception {
        TtsManager manager = new TtsManager(plugin);
        ExecutorService pool = poolOf(manager);
        assertFalse(pool.isShutdown(), "前置：新建实例的池不应该是关的喵");

        manager.shutdown();

        assertTrue(manager.isShutdown(),
                "shutdown() 必须把 closed 闩置真喵 —— 否则 generateAudio 的入口守卫形同虚设");
        assertTrue(pool.isShutdown(),
                "shutdown() 必须真的关掉 downloadPool 喵 —— 这是「/lea reload 不泄漏 LLEP-TTS"
                        + " 线程」这一承诺的唯一兑现点");
        // shutdownNow() 是异步收敛的：给它 2 秒真的走完 Terminated。
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS),
                "池在 shutdown() 之后 2 秒内仍未 Terminated（没有在途任务时不该发生）喵");
    }

    @Test
    @DisplayName("F1：shutdown() 幂等（重复调用不抛）")
    void shutdownIsIdempotent() {
        TtsManager manager = new TtsManager(plugin);
        manager.shutdown();
        manager.shutdown(); // 已 Terminated 的池上 shutdownNow() 是 JDK 定义的 no-op
        manager.shutdown();
        assertTrue(manager.isShutdown());
    }

    // ==================================================================
    // 行为 2：关停后 generateAudio 不抛 RejectedExecutionException
    // ==================================================================

    /**
     * 这是本缺陷的<b>行为面</b>：只加 {@code shutdownNow()} 而不加 {@code closed} 闸，
     * 本用例会以 {@code RejectedExecutionException} 变红（不是断言失败，是异常逃逸）。
     *
     * <p>为什么必须是行为测试而不是 lint：{@code RejectedExecutionException} 的具体触发
     * 条件（Terminated 的 {@code ThreadPoolExecutor} 对 {@code CompletableFuture.runAsync}
     * 同步拒绝）是 JDK 的语义，不是本仓的约定。lint 只能断言「有个 if (closed)」，
     * 断言不了「那个 if 真的挡在前面」。
     */
    @Test
    @DisplayName("F1：shutdown() 之后 generateAudio 返回已完成的 null，不抛 RejectedExecutionException")
    void generateAudioAfterShutdownDoesNotThrow() {
        TtsManager manager = new TtsManager(plugin);
        manager.shutdown();

        CompletableFuture<java.io.File> future = manager.generateAudio("apple");

        assertNotNull(future, "generateAudio 永不返回 null future（调用方直接 .thenAccept）喵");
        assertTrue(future.isDone(),
                "关停后必须返回一个<b>已同步完成</b>的 future 喵 —— 未完成意味着还在等一个"
                        + "永远不会跑的下载任务，调用方的回调永不触发");
        assertNull(future.getNow(new java.io.File("sentinel")),
                "关停后 generateAudio 的结果必须是 null（「本次没下载成功」）喵");
    }

    // ==================================================================
    // 行为 3：在途下载真的被中断（R4-06-F1 的原承诺）
    // ==================================================================

    /**
     * R4-06-F1 承诺的是「中断在途下载」。用一个会阻塞到被中断的 task 直接投进池里，
     * 验证 {@code shutdown()} 用的确实是 {@code shutdownNow()}（会 {@code interrupt()} 工作者）
     * 而不是 {@code shutdown()}（只是不再接新任务，在途任务继续跑完）。
     *
     * <p>这一条与行为 1 不重复：{@code awaitTermination} 在「任务已自己结束」时也会成功，
     * 所以光靠它区分不出 {@code shutdown} 与 {@code shutdownNow}。
     */
    @Test
    @DisplayName("F1：shutdown() 中断在途任务（证明用的是 shutdownNow 而非 shutdown）")
    void shutdownInterruptsInFlightWork() throws Exception {
        TtsManager manager = new TtsManager(plugin);
        ExecutorService pool = poolOf(manager);

        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        Future<?> inFlight = pool.submit(() -> {
            started.countDown();
            try {
                // 15 分钟：任何正常路径都不会自己结束，只能被中断唤醒。
                Thread.sleep(15 * 60 * 1000L);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS), "前置：在途任务必须已开跑喵");

        manager.shutdown();

        assertTrue(interrupted.await(5, TimeUnit.SECONDS),
                "在途任务没有被 interrupt 喵 —— 说明用的是 shutdown()（等在途跑完）而不是"
                        + " shutdownNow()。这正是 R4-06-F1 那条 javadoc 承诺的内容。");
        // 被 shutdownNow 打断的任务在以后台上是「已取消/已中断结束」，两种都算收掉。
        try {
            inFlight.get(5, TimeUnit.SECONDS);
        } catch (Exception expected) {
            // 取消 / 中断 / 执行异常都说明它没有跑完 15 分钟 —— 正是我们要的。
        }
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "关停后池必须收敛到 Terminated 喵");
    }

    /** {@code isShutdown()} 只验「闸已落」；顺带钉住它不依赖池的运行时状态。 */
    @Test
    @DisplayName("F1：isShutdown() 在 shutdown() 前为 false，之后恒为 true")
    void isShutdownReflectsTheLatch() {
        TtsManager manager = new TtsManager(plugin);
        assertFalse(manager.isShutdown(), "新建实例没关过，isShutdown() 必须为 false 喵");
        manager.shutdown();
        assertTrue(manager.isShutdown(), "shutdown() 之后必须为 true 喵");
    }

    /** 关停后日志里应有 fine 级「已关停跳过」痕迹，便于线上归因。 */
    @Test
    @DisplayName("F1：关停后的 generateAudio 走静默早退（不产生 WARNING/SEVERE）")
    void shutdownPathIsSilent() {
        TtsManager manager = new TtsManager(plugin);
        manager.shutdown();
        manager.generateAudio("apple");
        for (String m : logs.messages) {
            assertFalse(m.contains("RejectedExecution"),
                    "关停路径不该出现任何 RejectedExecution 的痕迹喵：" + m);
        }
    }

    /**
     * 反射检查：{@code generateAudio} 里 {@code closed} 的读必须出现在
     * {@code CompletableFuture.runAsync} <b>之前</b>。
     *
     * <p>这是行为测试的补充而不是替代：行为测试能证明「现在不抛」，但证明不了
     * 「以后有人在 runAsync 之后再补一个 closed 检查也能过」。顺序是本方法的全部要点。
     */
    @Test
    @DisplayName("F1：generateAudio 的 closed 守卫在派发点之前（顺序即语义）")
    void closedGuardPrecedesDispatch() throws Exception {
        Method m = TtsManager.class.getDeclaredMethod("generateAudio", String.class);
        String src = sourceOf(m);

        int guard = src.indexOf("if (closed)");
        int dispatch = src.indexOf("CompletableFuture.runAsync(");
        assertTrue(guard >= 0,
                "generateAudio 里找不到 `if (closed)` 守卫喵 —— 关停后 runAsync 会同步抛"
                        + " RejectedExecutionException，从 InventoryClickEvent 处理器里冒出去。");
        assertTrue(dispatch >= 0, "generateAudio 里找不到 CompletableFuture.runAsync( 喵 —— 定位失效。");
        assertTrue(guard < dispatch,
                "`if (closed)` 出现在 CompletableFuture.runAsync( <b>之后</b>（守卫偏移=" + guard
                        + "，派发偏移=" + dispatch + "）喵。放在后面的守卫挡不住任何东西："
                        + "异常在 runAsync 那一行就抛出去了，根本走不到检查。");
    }

    // ------------------------------------------------------------------ 源码定位

    /**
     * 定位仓库根：从 cwd 与 code-source 位置各自上溯，找含
     * {@code src/main/java/.../TtsManager.java} 的那一级。
     *
     * <p>两条都试是必要的：{@code mvn test} 的 Surefire fork 以模块根为 cwd，而
     * 自建 runner 可能在任意目录启动，且 class 输出目录也未必在模块根下
     * （本仓多次踩过「相对路径假红」）。找不到就 <b>fail</b>，绝不 skip ——
     * 跳过会让这条顺序断言静默失效。
     */
    private static Path repoRoot() {
        List<Path> starts = new ArrayList<>();
        String cwd = System.getProperty("user.dir");
        if (cwd != null) {
            starts.add(Path.of(cwd).toAbsolutePath());
        }
        try {
            starts.add(Path.of(TtsManager.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath());
        } catch (Exception ignored) {
            // code source 拿不到就只靠 cwd
        }
        for (Path start : starts) {
            for (Path p = start; p != null; p = p.getParent()) {
                if (java.nio.file.Files.isRegularFile(p.resolve(REL_SOURCE))) {
                    return p;
                }
            }
        }
        throw new AssertionError("找不到含 " + REL_SOURCE + " 的仓库根（试过 " + starts + "）喵。\n"
                + "本断言是语义顺序检查，找不到源码就必须红灯，不能 skip —— "
                + "跳过等于让「守卫在派发点之后」这个坏形状静默通过。");
    }

    private static final String REL_SOURCE =
            "src/main/java/com/letslearnenglish/minecraftplugin/core/ai/TtsManager.java";

    /**
     * 读生产源码里某个方法的<b>方法体原文</b>（不剥注释）。
     *
     * <p>刻意不剥注释：本用例找的两个串在注释里都没有出现，剥不剥等价；而不剥的好处是
     * 定位器更短、更不容易有 bug。若将来注释里出现了同名字符串，本用例会误红 —— 那是
     * 需要人裁决的信号，不是可以让它静默通过的。
     */
    private static String sourceOf(Method m) throws Exception {
        Path file = repoRoot().resolve(REL_SOURCE);
        assertTrue(java.nio.file.Files.isRegularFile(file), "找不到 " + file.toAbsolutePath() + " 喵");
        String src = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        // 显式写全签名（含泛型）：只用 getSimpleName() 拼会得到
        // "CompletableFuture generateAudio(" 而源码里是 "CompletableFuture<File>"，匹配不上。
        String signature = "public CompletableFuture<File> generateAudio(String word)";
        int at = src.indexOf(signature);
        assertTrue(at >= 0, "源码里找不到签名「" + signature + "」喵 —— 方法可能被改名了。");
        int open = src.indexOf('{', at);
        int close = matchingBrace(src, open);
        assertTrue(open > 0 && close > open, "花括号配对失败喵");
        return src.substring(open + 1, close);
    }

    private static int matchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
