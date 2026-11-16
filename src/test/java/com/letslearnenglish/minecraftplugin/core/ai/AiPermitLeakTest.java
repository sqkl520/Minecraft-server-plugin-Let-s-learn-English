package com.letslearnenglish.minecraftplugin.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * R-19-B：{@code dispatchChat} 在「拿到全局在途许可」之后、「提交 HTTP 任务」之前的
 * 失败必须显式收尾 —— 许可归还一次、在途标记摘掉、上下文回滚、玩家收到提示、有 WARNING。
 *
 * <h2>为什么这条缺陷单开一组测试</h2>
 * 触发概率低（要 gson / 集合遍历 / DI 半初始化在某一句上抛 {@code RuntimeException}），
 * 但<b>后果是永久的</b>：
 * <ul>
 *   <li>{@code permits} 每失败一次少一格。归还只挂在 {@code aiExecutor.submit(...)} 之后的
 *       {@code .handle(...)} 上，而那时异常已经抛出去了 —— 于是没有任何路径会还它。
 *       漏满 {@code maxInFlight}（生产 16~32）之后<b>全服 AI 永久"忙"</b>，只能重启；</li>
 *   <li>{@code pendingRequests} 永久留下该玩家 → 此后该玩家每条消息都被
 *       {@code dialogue.request-in-progress} 拒掉，直到重登 / {@code /lea reload}；</li>
 *   <li>而且原先<b>一行日志都没有</b>：运维看到的只有"AI 突然不理人了"。</li>
 * </ul>
 * 所以本类的核心断言不是"异常别抛出去"（那是次要的），而是
 * <b>"资源回到原值 + 状态干净 + 有人看得见"</b>。
 * 注意许可断言用的是<b>精确数</b>而非布尔：布尔形式下"多还一格"（上限被静默放大）
 * 与"少还一格"都可能落在同一个 true 上。
 *
 * <h2>封死的变异（三条都实测跑过，见类末「变异验证实测」）</h2>
 * <ul>
 *   <li>删掉 {@code dispatchChat} 里的 try/catch（回到修复前的形状）→ 用例 1 变红；</li>
 *   <li>catch 里归还、同时保留 {@code .handle(...)} 里的归还（双重归还）→ 用例 3 变红；</li>
 *   <li>把 {@code loadScenariosConfig} 的 try-with-resources 改回裸赋值 → 用例 4 变红。</li>
 * </ul>
 *
 * <h2>为什么要一个生产代码里的注入钩子</h2>
 * "许可已拿到 → 请求体还没构建"这一段<b>没有可替换的依赖</b>：
 * <ul>
 *   <li>mock gson 没用 —— {@code gson.toJson(...)} 只在 {@code debug=true} 的日志行里被调，
 *       默认配置下那行不求值，注入点走不到；</li>
 *   <li>mock {@code insertChatHistory} 没用 —— 它在 {@code userWrite} 的 lambda 里被自己的
 *       catch 吞掉，根本到不了外层；</li>
 *   <li>反射把 {@code aiExecutor} 换成抛异常的桩没用 —— 会连带破坏
 *       {@code releasePermit} 的观测，而许可数正是本次断言的核心。</li>
 * </ul>
 * 故 {@code AIChatManager} 留了一个包级、默认 {@code null} 的 no-op 钩子
 * （{@code setTestHookAfterPermit}）。与既有的 {@code aiExecutor()} 测试口同一姿态。
 */
class AiPermitLeakTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000cead1");
    private static final String SCENARIO = "village_trade";
    /** {@code dialogue.request-failed} 的哨兵文案：用于分辨"回给玩家的是哪一条提示"。 */
    private static final String SENTINEL_FAILED = "SENTINEL:request-failed";

    @TempDir
    Path tempDir;

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PlayerDataStore dataStore;
    private MessageUtil messageUtil;
    private Player player;
    private FileConfiguration mainConfig;
    private Logger logger;
    private CapturingHandler handler;
    private HttpServer httpServer;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        databaseManager = mock(DatabaseManager.class);
        dataStore = mock(PlayerDataStore.class);
        messageUtil = mock(MessageUtil.class);
        player = mock(Player.class);

        // 真 ai_scenarios.yml 放进临时数据目录（mock 的 plugin.getResource 返回 null）。
        Files.copy(Path.of("src/main/resources/ai_scenarios.yml"),
                tempDir.resolve("ai_scenarios.yml"));

        mainConfig = new YamlConfiguration();
        mainConfig.set("ai-chat.focus-words-max", 5);
        mainConfig.set("ai-chat.rate-limit-seconds", 0);
        mainConfig.set("ai-chat.timeout-seconds", 5);
        mainConfig.set("ai-chat.daily-round-limit", 0);
        mainConfig.set("ai-chat.count-min-rounds", 1);

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(configManager.isAIChatEnabled()).thenReturn(true);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("");
        // 只有 request-failed 给哨兵，其余给空串 —— 于是"回的是哪条提示"可分辨。
        when(messageUtil.getPlayerMessage(any(Player.class), eq("dialogue.request-failed"),
                any(String[].class))).thenReturn(SENTINEL_FAILED);
        when(dataStore.getTodayAiRound(any(UUID.class))).thenReturn(0);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("PermitTester");
        when(player.getLocale()).thenReturn("en");
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("en");
        when(plugin.isEnabled()).thenReturn(true);

        // 调度器：runTask 就地执行（测试线程），回调链因此同步完成。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        });

        logger = Logger.getLogger("AiPermitLeakTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        handler = new CapturingHandler();
        logger.addHandler(handler);
        when(plugin.getLogger()).thenReturn(logger);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    // ==================================================================
    //  1. 提交前失败：许可、标记、上下文、日志、提示 五样全部收尾
    // ==================================================================

    @Nested
    @DisplayName("1. 提交前抛 RuntimeException：许可回到原值、标记摘掉、上下文回滚、有 WARNING")
    class PreSubmitFailureIsCleanedUp {

        @Test
        @DisplayName("a) 可用许可回到原值（精确数，不是布尔）")
        void permitReturnsToOriginalCount() throws Exception {
            AIChatManager ai = newManager("http://127.0.0.1:1/unused");
            ai.startConversation(player, SCENARIO);
            int permitsBefore = ai.aiExecutor().availablePermits();
            assertTrue(permitsBefore > 0, "前置条件：开始时应还有可用许可");

            ai.setTestHookAfterPermit(() -> {
                throw new IllegalStateException("injected failure right after permit acquisition");
            });
            ai.chatAsync(player, "hello teacher", (response, error) -> { });

            assertEquals(permitsBefore, ai.aiExecutor().availablePermits(),
                    "提交前失败后可用许可必须回到 " + permitsBefore + "。少一格 = 许可泄漏："
                            + "每失败一次漏一格，漏满 maxInFlight（" + ai.aiExecutor().maxInFlight()
                            + "）之后全服 AI 永久忙、只能重启。实际日志：" + handler.describe());
        }

        @Test
        @DisplayName("b) pendingRequests 不再含该玩家（否则他此后每条消息都被拒）")
        void pendingMarkIsCleared() throws Exception {
            AIChatManager ai = newManager("http://127.0.0.1:1/unused");
            ai.startConversation(player, SCENARIO);
            ai.setTestHookAfterPermit(() -> {
                throw new IllegalStateException("injected failure right after permit acquisition");
            });
            ai.chatAsync(player, "hello teacher", (response, error) -> { });

            assertFalse(pendingKeys(ai).contains(PLAYER_ID),
                    "提交前失败后 pendingRequests 必须摘掉该玩家。残留的后果：此后该玩家"
                            + " <b>每条</b>消息都被 dialogue.request-in-progress 拒掉，"
                            + "直到重登或 /lea reload —— 而日志里同样什么都没有。");
        }

        @Test
        @DisplayName("c) 内存 context 回滚（没发出去的那句不该跟着下一轮请求走）")
        void contextIsRolledBack() throws Exception {
            AIChatManager ai = newManager("http://127.0.0.1:1/unused");
            ai.startConversation(player, SCENARIO);
            int contextBefore = contextSize(ai);
            assertTrue(contextBefore >= 1, "前置条件：context 至少含 system 行");

            ai.setTestHookAfterPermit(() -> {
                throw new IllegalStateException("injected failure right after permit acquisition");
            });
            ai.chatAsync(player, "hello teacher", (response, error) -> { });

            assertEquals(contextBefore, contextSize(ai),
                    "提交前失败后 context 必须回到发请求之前的条数。否则这句没发出去的话会留在"
                            + " context 里，下一轮被一起发给上游 —— AI 会对着它作答。");
        }

        @Test
        @DisplayName("d) 有带 throwable 的 WARNING，且能定位到玩家")
        void warningWithThrowableIsLogged() throws Exception {
            AIChatManager ai = newManager("http://127.0.0.1:1/unused");
            ai.startConversation(player, SCENARIO);
            ai.setTestHookAfterPermit(() -> {
                throw new IllegalStateException("injected failure right after permit acquisition");
            });
            ai.chatAsync(player, "hello teacher", (response, error) -> { });

            String warn = handler.describe();
            assertTrue(handler.hasWarningWithThrowable(),
                    "提交前失败必须有<b>带 throwable</b> 的 WARNING。原先这里是零日志："
                            + "许可与标记永久漏掉，而服主只看到『AI 突然不理人了』，无从定位。"
                            + "实际日志：" + warn);
            assertTrue(warn.contains("PermitTester"),
                    "WARNING 必须能定位到玩家。实际日志：" + warn);
        }

        @Test
        @DisplayName("e) 异常不外抛，且玩家收到一次 request-failed")
        void doesNotPropagateAndNotifiesPlayer() throws Exception {
            AIChatManager ai = newManager("http://127.0.0.1:1/unused");
            ai.startConversation(player, SCENARIO);
            ai.setTestHookAfterPermit(() -> {
                throw new IllegalStateException("injected failure right after permit acquisition");
            });

            List<String> errors = new CopyOnWriteArrayList<>();
            try {
                ai.chatAsync(player, "hello teacher", (response, error) -> errors.add(error));
            } catch (RuntimeException e) {
                fail("dispatchChat 把提交前的 RuntimeException 抛给了调用方（"
                        + e.getClass().getName() + ": " + e.getMessage() + "）—— "
                        + "chatAsync 的调用点是 AsyncPlayerChatEvent 处理器，异常冒出去会中断"
                        + " 该事件的后续业务（TtsManager:87 记录过同一个坑）。");
            }
            assertEquals(List.of(SENTINEL_FAILED), errors,
                    "玩家必须收到<b>恰好一次</b> request-failed —— 否则他会一直等一句永远不来的"
                            + " 回复。实际： " + errors);
        }
    }

    // ==================================================================
    //  2. 成功路径不得双重归还
    // ==================================================================

    @Nested
    @DisplayName("2. 成功路径：N 次请求后可用许可恒等于初始值（不增不减）")
    class NoDoubleRelease {

        @Test
        @DisplayName("连续 5 次成功请求后许可数不变（多还一格 = 闸门被静默放大）")
        void successfulRequestsDoNotInflatePermits() throws Exception {
            String url = startFakeApi();
            AIChatManager ai = newManager(url);
            ai.startConversation(player, SCENARIO);
            int permitsBefore = ai.aiExecutor().availablePermits();
            int max = ai.aiExecutor().maxInFlight();
            List<String> replies = new CopyOnWriteArrayList<>();

            for (int i = 0; i < 5; i++) {
                ai.chatAsync(player, "hello teacher " + i, (response, error) -> replies.add(response));
                awaitQuiescent();
            }

            assertEquals(5, replies.size(), "前置条件：5 次请求应全部拿到回复，实际 " + replies);
            assertEquals(permitsBefore, ai.aiExecutor().availablePermits(),
                    "5 次成功请求后可用许可必须回到 " + permitsBefore + "。多于它 = 双重归还"
                            + "（catch 与 .handle 各还一次），在途上限被静默放大、闸等于失效；"
                            + "少于它 = 漏归还。");
            assertTrue(permitsBefore <= max,
                    "可用许可不该超过构造时的上限（" + max + "）");
            assertFalse(pendingKeys(ai).contains(PLAYER_ID),
                    "成功路径结束后 pendingRequests 不该残留该玩家");
        }
    }

    // ==================================================================
    //  3. InputStream：源码 lint（锚方法名，先剥注释）
    // ==================================================================

    @Nested
    @DisplayName("3. 源码 lint：loadScenariosConfig 的 getResource 必须在 try-with-resources 里")
    class InputStreamLint {

        @Test
        @DisplayName("getResource(...) 不得以裸赋值形式出现（会漏 JarURLConnection 句柄）")
        void getResourceIsClosedByTryWithResources() throws Exception {
            // 先剥注释：修复处的注释<b>刻意</b>引用了 `InputStream is = plugin.getResource(...)`
            // 这个反例形状，不剥注释就会把解释性注释当成"还在犯"（本仓库此类假阳性 > 4 次）。
            String src = stripComments(sourceOf(
                    "com/letslearnenglish/minecraftplugin/core/ai/AIChatManager.java"));
            String body = methodBody(src, "loadScenariosConfig");
            assertNotNull(body, "找不到 loadScenariosConfig 的方法体 —— 方法被重命名/搬走了？"
                    + " 本 lint 锚的是方法名，请同步更新。");

            // ① 前提：资源确实被打开过（否则"没有裸赋值"是没有任何内容的假绿）。
            assertTrue(body.contains("plugin.getResource("),
                    "loadScenariosConfig 里没有 plugin.getResource(...) —— fallback 分支被删了？"
                            + " 本 lint 的前提是它存在。");

            // ② 形态断言：第一处 getResource 必须落在 `try (` 的头部。
            //    只断言 contains("try (") 会被文件里任意别的 try-with-resources 满足（假绿），
            //    所以把窗口收敛到该处往前 120 字符内。
            int idx = body.indexOf("plugin.getResource(");
            String window = body.substring(Math.max(0, idx - 120), idx);
            assertTrue(window.contains("try ("),
                    "getResource(...) 前 120 字符内没有 `try (` —— 说明它又回到了裸赋值"
                            + "（`InputStream is = plugin.getResource(...)`），每次 dataFile 不存在"
                            + " 就漏一个 JarURLConnection 句柄。实际上下文：... " + window);

            // ③ 形态断言（精确）：`= plugin.getResource(` 之前不得是语句起始的裸赋值。
            //    注意<b>不能</b>直接断言 body 不含 "InputStream is = plugin.getResource(" ——
            //    那是 `try (InputStream is = plugin.getResource(` 的<b>子串</b>，正确写法会被误判
            //    （本断言的第一版就是这么假红的）。所以判据是"赋值号之前那个 token 不是 `try (`"。
            Pattern naked = Pattern.compile("(?<!try\\s\\()\\bInputStream\\s+\\w+\\s*=\\s*plugin\\.getResource\\(");
            Matcher nakedMatcher = naked.matcher(body);
            assertFalse(nakedMatcher.find(),
                    "loadScenariosConfig 仍在用裸赋值 `InputStream x = plugin.getResource(...)`"
                            + " —— 这正是本轮修掉的句柄泄漏形状（裸赋值的 close 永远不会发生）。");
        }

        @Test
        @DisplayName("getResource 返回 null 时回落到内置场景（try-with-resources 允许初始化式为 null）")
        void nullResourceFallsBackToBuiltins() throws Exception {
            // 行为侧：plugin.getResource 返回 null（mock 默认）且 dataFile 不存在时，
            // 构造必须活着建出来并回落到内置场景。若形状被改坏成"先判 null 再裸赋值"或
            // "去掉 null 判定"，这里会 NPE。
            Path cleanDir = tempDir.resolve("clean-" + System.nanoTime());
            Files.createDirectories(cleanDir);
            when(plugin.getDataFolder()).thenReturn(cleanDir.toFile());

            AIChatManager ai = new AIChatManager(plugin);
            assertFalse(ai.getSortedScenarioKeys(player).isEmpty(),
                    "getResource 返回 null 时必须回落到内置场景 —— try-with-resources 的初始化"
                            + " 表达式求值为 null 是合法的（不会 NPE、close 被跳过）");
            assertTrue(handler.describe().contains("not found in JAR"),
                    "资源缺失时必须有 SEVERE 痕迹（否则服主不知道为什么只剩内置场景）。实际日志："
                            + handler.describe());
        }
    }

    // ==================================================================
    //  依赖构造 / 状态读取
    // ==================================================================

    /** 写一个可用的 ai_config.yml（否则 isAvailable() 恒 false，请求根本发不出去）。 */
    private AIChatManager newManager(String apiUrl) throws Exception {
        Files.writeString(tempDir.resolve("ai_config.yml"),
                "api-key: test-key\n", StandardCharsets.UTF_8);
        mainConfig.set("ai-chat.api-url", apiUrl);
        lastManager = new AIChatManager(plugin);
        return lastManager;
    }

    /** 起一个只回 200 + 合法 OpenAI 形状的本地端点。 */
    private String startFakeApi() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/chat", exchange -> {
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"Hi there!\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        httpServer.start();
        return "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/chat";
    }

    /**
     * 等在途任务排空。
     *
     * <p>用"执行器 pending 归零 + 一小段静默期"而不是固定 sleep：固定 sleep 会让
     * "许可已经还了"这类断言在慢机器上偶发假红，也会让"其实什么都没跑"变成假绿。
     */
    private void awaitQuiescent() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (pendingTaskCount() == 0) {
                // 再给回调链（handle → thenAcceptAsync → exceptionally）一点收尾时间。
                Thread.sleep(60);
                if (pendingTaskCount() == 0) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        fail("专属执行器在 5 秒内没有排空 —— 请求链卡住了？");
    }

    private int pendingTaskCount() {
        return lastManager == null ? 0 : lastManager.aiExecutor().pending();
    }

    /** 最近一次 {@link #newManager(String)} 造出来的实例（供 {@link #awaitQuiescent()} 读池）。 */
    private AIChatManager lastManager;
    @SuppressWarnings("unchecked")
    private Set<UUID> pendingKeys(AIChatManager ai) throws Exception {
        Field f = AIChatManager.class.getDeclaredField("pendingRequests");
        f.setAccessible(true);
        Map<UUID, String> map = (Map<UUID, String>) f.get(ai);
        return new LinkedHashSet<>(map.keySet());
    }

    /** 当前会话的 context 条数（无会话返回 -1）。 */
    @SuppressWarnings("unchecked")
    private int contextSize(AIChatManager ai) throws Exception {
        Field sessionsField = AIChatManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Map<UUID, Object> sessions = (Map<UUID, Object>) sessionsField.get(ai);
        Object state = sessions.get(PLAYER_ID);
        if (state == null) {
            return -1;
        }
        Field contextField = state.getClass().getDeclaredField("context");
        contextField.setAccessible(true);
        Deque<?> context = (Deque<?>) contextField.get(state);
        synchronized (context) {
            return context.size();
        }
    }

    // ==================================================================
    //  源码 lint 工具
    // ==================================================================

    private Path srcRoot() {
        Path direct = Paths.get("src", "main", "java");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            Path candidate = p.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            p = p.getParent();
        }
        return fail("找不到 src/main/java —— lint 无法读源码；workingDirectory="
                + System.getProperty("user.dir"));
    }

    private String sourceOf(String rel) throws Exception {
        Path file = srcRoot().resolve(rel);
        assertTrue(Files.isRegularFile(file),
                "源码文件不存在：" + file.toAbsolutePath()
                        + " —— 若文件被移动/重命名，请同步更新本 lint 的路径常量。");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 剥掉 Java 注释（行/块/javadoc），保留字符串、字符与文本块内容。 */
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

    /** 取一个方法的方法体（含花括号）；找不到返回 null。锚方法名，不锚行号。 */
    private static String methodBody(String src, String methodName) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{");
        Matcher m = p.matcher(src);
        if (!m.find()) {
            return null;
        }
        int open = src.indexOf('{', m.start());
        int end = matchingBrace(src, open);
        return (end < 0) ? null : src.substring(m.start(), end + 1);
    }

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

    // ---------------- 日志捕获 ----------------

    /** 收集日志；断言要区分「有没有 WARNING」与「WARNING 带不带 throwable」。 */
    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        synchronized boolean hasWarningWithThrowable() {
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()
                        && record.getThrown() != null) {
                    return true;
                }
            }
            return false;
        }

        synchronized String describe() {
            StringBuilder sb = new StringBuilder("[");
            for (LogRecord record : records) {
                sb.append(record.getLevel().getName()).append(':').append(record.getMessage());
                if (record.getThrown() != null) {
                    sb.append(" throwable=").append(record.getThrown().getClass().getSimpleName());
                }
                sb.append(" | ");
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
