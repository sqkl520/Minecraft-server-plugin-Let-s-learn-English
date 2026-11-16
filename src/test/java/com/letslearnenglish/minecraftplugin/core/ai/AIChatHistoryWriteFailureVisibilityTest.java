package com.letslearnenglish.minecraftplugin.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
 * R11：{@code AIChatManager} 三处 {@code insertChatHistory} 的<b>消费侧</b>语义。
 *
 * <h2>为什么必须单独测这一层</h2>
 * 数据层（{@code ChatHistoryAndCoinWriteVisibilityTest}）只证明「失败时返回 false」。
 * 缺陷的主体在消费侧：三处调用点原本把<b>两条</b>失败路径（SQL 失败、运行期异常）都塞进
 * {@code debugLog}，而 {@code debug} 默认 {@code false} —— 也就是说
 * <b>默认配置下聊天记录静默缺行，一条日志都没有</b>。所以本类断言：
 * <ul>
 *   <li>{@code false}（SQL 失败）→ <b>必须</b>有 WARNING，且能定位玩家与会话；</li>
 *   <li>{@code throw}（运行期异常）→ <b>必须</b>有 WARNING 且<b>带 throwable</b>（保留堆栈）；</li>
 *   <li>成功 → <b>不得</b>有 WARNING（否则每次正常对话都刷日志，真故障被淹没）；</li>
 *   <li><b>不做补偿重放</b>：失败后不得出现第二次 {@code insertChatHistory} 调用
 *       （表无唯一键，重放会写出重复行 —— 补偿在这里反成缺陷）。</li>
 * </ul>
 *
 * <h2>三处的上下文不同（本类据此分三组）</h2>
 * <ol>
 *   <li><b>system</b>（{@code startConversation}，{@code CompletableFuture.runAsync}）：
 *       异步 fire-and-forget，没有同步返回值可看，只能靠日志 —— 所以「日志必达」是唯一契约。</li>
 *   <li><b>user</b>（{@code dispatchChat}，同样是 {@code runAsync}，在 HTTP 请求<b>之前</b>）：
 *       与 system 同形态，但它在请求发出前就落地，所以不需要 HTTP 成功即可观测。</li>
 *   <li><b>assistant</b>（HTTP 成功回调内，<b>双层异步</b>：{@code supplyAsync} 内再
 *       {@code runAsync}）：需要真 HTTP 才能到达该分支，本类用 JDK 自带
 *       {@code com.sun.net.httpserver.HttpServer} 起一个只回 200 的本地端点。</li>
 * </ol>
 *
 * <h2>封死的变异</h2>
 * <ul>
 *   <li>把任一处的失败分支改回 {@code debugLog}（即修复前的形状）→ 该组 WARNING 用例红；</li>
 *   <li>把 {@code if (!insertChatHistory(...))} 的判定删掉（只调不看）→ 同上红；</li>
 *   <li>让 {@code catch} 分支丢掉 throwable（只打 getMessage）→ {@code hasThrowable} 用例红；</li>
 *   <li>在失败分支里"重试一次" → {@code times(1)} 用例红。</li>
 * </ul>
 */
class AIChatHistoryWriteFailureVisibilityTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000c4a71");
    private static final String SCENARIO = "village_trade";

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

        // 把真 ai_scenarios.yml 放进临时数据目录：mock 的 plugin.getResource() 返回 null，
        // 否则 AIChatManager 构造期会打 SEVERE "ai_scenarios.yml not found in JAR" ——
        // 那条 SEVERE 会污染「成功路径不得有 WARNING」的断言（假红）。
        // 用真资源文件也顺带让内置场景/难度是真表，而不是兜底三分支。
        Path scenarios = tempDir.resolve("ai_scenarios.yml");
        Files.copy(Path.of("src/main/resources/ai_scenarios.yml"), scenarios);

        mainConfig = new YamlConfiguration();
        mainConfig.set("ai-chat.focus-words-max", 5);
        mainConfig.set("ai-chat.rate-limit-seconds", 0);
        mainConfig.set("ai-chat.timeout-seconds", 5);
        mainConfig.set("ai-chat.daily-round-limit", 0);

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(configManager.isAIChatEnabled()).thenReturn(true);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenReturn("");
        when(dataStore.getTodayAiRound(any(UUID.class))).thenReturn(0);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("ChatTester");
        when(player.getLocale()).thenReturn("en");
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("en");
        // AIChatManager 的 isShuttingDownOrDisabled() 看它；不铺 true 会在写库前短路。
        when(plugin.isEnabled()).thenReturn(true);

        // 调度器：runTask 就地执行（测试线程）。thenAcceptAsync 的回调因此同步完成，
        // 不依赖真实主线程 tick。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        });

        logger = Logger.getLogger("AIChatHistoryWriteFailureVisibilityTest-" + System.nanoTime());
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

    /** 写一个可用的 ai_config.yml（否则 isAvailable() 恒 false，请求根本发不出去）。 */
    private void writeApiKey(String apiUrl) throws Exception {
        File cfg = new File(tempDir.toFile(), "ai_config.yml");
        Files.writeString(cfg.toPath(),
                "api-key: test-key\n", StandardCharsets.UTF_8);
        mainConfig.set("ai-chat.api-url", apiUrl);
    }

    /** 起一个只回 200 + 合法 OpenAI 形状的本地端点（assistant 分支必须真 HTTP 才能到达）。 */
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

    // ==================================================================
    // A. system prompt 写入（startConversation，异步 fire-and-forget）
    // ==================================================================

    @Nested
    @DisplayName("A. system prompt：SQL 失败 / 运行期异常都必须有 WARNING")
    class SystemPromptWrite {

        @Test
        @DisplayName("SQL 失败（false）→ WARNING 含玩家与会话，且只尝试写 1 次（不重试）")
        void sqlFailureIsWarned() throws Exception {
            writeApiKey("http://127.0.0.1:1/unused");
            when(databaseManager.insertChatHistory(eq(PLAYER_ID), anyString(), eq("system"), anyString()))
                    .thenReturn(false);

            AIChatManager ai = new AIChatManager(plugin);
            ai.startConversation(player, SCENARIO);
            String sessionId = ai.getActiveSessionId(PLAYER_ID);

            assertTrue(waitForWarning(),
                    "system 行写失败没有任何 WARNING 喵 —— 这就是修复前的形状"
                            + "（debugLog + 默认 debug=false = 完全静默），"
                            + "玩家历史页会显示「没有开场白」而服主无从排查。实际日志："
                            + handler.describe());
            assertTrue(handler.describe().contains("ChatTester"),
                    "WARNING 必须能定位到玩家喵：" + handler.describe());
            assertTrue(handler.describe().contains(sessionId),
                    "WARNING 必须能定位到会话喵：" + handler.describe());
            // 不重试：表无唯一键，重放一单会让同一句话在历史里出现两次。
            org.mockito.Mockito.verify(databaseManager, org.mockito.Mockito.times(1))
                    .insertChatHistory(eq(PLAYER_ID), anyString(), eq("system"), anyString());
        }

        @Test
        @DisplayName("运行期异常 → WARNING 带 throwable（保留类型与堆栈），不外抛")
        void runtimeExceptionIsWarnedWithThrowable() throws Exception {
            writeApiKey("http://127.0.0.1:1/unused");
            when(databaseManager.insertChatHistory(eq(PLAYER_ID), anyString(), eq("system"), anyString()))
                    .thenThrow(new IllegalStateException("db exploded"));

            AIChatManager ai = new AIChatManager(plugin);
            ai.startConversation(player, SCENARIO);

            assertTrue(waitForThrowableWarning(),
                    "运行期异常必须带 throwable 记日志喵 —— 只打 getMessage 会丢掉类型与堆栈。实际日志："
                            + handler.describe());
        }

        @Test
        @DisplayName("成功 → 无 WARNING（恒 false 会让每场对话都刷日志）")
        void successIsQuiet() throws Exception {
            writeApiKey("http://127.0.0.1:1/unused");
            when(databaseManager.insertChatHistory(eq(PLAYER_ID), anyString(), eq("system"), anyString()))
                    .thenReturn(true);

            AIChatManager ai = new AIChatManager(plugin);
            ai.startConversation(player, SCENARIO);
            // 等到写库真的发生（否则「没有 WARNING」可能只是因为还没执行 = 假绿）。
            org.mockito.Mockito.verify(databaseManager, org.mockito.Mockito.timeout(2000))
                    .insertChatHistory(eq(PLAYER_ID), anyString(), eq("system"), anyString());

            assertFalse(handler.hasWarning(),
                    "成功路径不该有 WARNING 喵：" + handler.describe());
        }
    }

    // ==================================================================
    // B. user 消息写入（dispatchChat，HTTP 请求之前）
    // ==================================================================

    @Nested
    @DisplayName("B. user 消息：失败必须有 WARNING，且不阻断主流程")
    class UserMessageWrite {

        @Test
        @DisplayName("SQL 失败（false）→ WARNING；玩家消息仍进入上下文/transcript")
        void sqlFailureIsWarned() throws Exception {
            // api-url 指向不可达端口：HTTP 必失败，但 user 行在发请求前就写，不受影响。
            writeApiKey("http://127.0.0.1:1/unused");
            when(databaseManager.insertChatHistory(eq(PLAYER_ID), anyString(), eq("user"), anyString()))
                    .thenReturn(false);

            AIChatManager ai = new AIChatManager(plugin);
            ai.startConversation(player, SCENARIO);
            ai.chatAsync(player, "hello teacher", (response, error) -> { });

            assertTrue(waitForWarningContaining("user"),
                    "user 行写失败没有任何 WARNING 喵 —— 玩家事后翻聊天记录会少一整段。实际日志："
                            + handler.describe());
        }

        @Test
        @DisplayName("运行期异常 → WARNING 带 throwable")
        void runtimeExceptionIsWarnedWithThrowable() throws Exception {
            writeApiKey("http://127.0.0.1:1/unused");
            when(databaseManager.insertChatHistory(eq(PLAYER_ID), anyString(), eq("user"), anyString()))
                    .thenThrow(new IllegalStateException("db exploded"));

            AIChatManager ai = new AIChatManager(plugin);
            ai.startConversation(player, SCENARIO);
            ai.chatAsync(player, "hello teacher", (response, error) -> { });

            assertTrue(waitForThrowableWarning(),
                    "运行期异常必须带 throwable 记日志喵。实际日志：" + handler.describe());
        }
    }

    // ==================================================================
    // C. assistant 消息写入（HTTP 成功回调内，双层异步）
    // ==================================================================

    @Nested
    @DisplayName("C. assistant 消息：失败必须有 WARNING")
    class AssistantMessageWrite {

        @Test
        @DisplayName("SQL 失败（false）→ WARNING，且回复仍正常回调给玩家")
        void sqlFailureIsWarned() throws Exception {
            String url = startFakeApi();
            writeApiKey(url);
            when(databaseManager.insertChatHistory(eq(PLAYER_ID), anyString(), eq("assistant"), anyString()))
                    .thenReturn(false);

            AIChatManager ai = new AIChatManager(plugin);
            ai.startConversation(player, SCENARIO);
            List<String> replies = new ArrayList<>();
            ai.chatAsync(player, "hello teacher", (response, error) -> replies.add(response));

            assertTrue(waitForWarningContaining("assistant"),
                    "assistant 行写失败没有任何 WARNING 喵（这一层是双层异步，最容易被漏掉）。实际日志："
                            + handler.describe());
            assertEquals(List.of("Hi there!"), replies,
                    "写库失败不该影响 AI 回复送达玩家 —— 聊天记录是辅助数据");
        }
    }

    // ---------------- helpers ----------------

    private boolean waitForWarning() throws InterruptedException {
        return waitFor(true);
    }

    private boolean waitForThrowableWarning() throws InterruptedException {
        return waitFor(false);
    }

    private boolean waitForWarningContaining(String token) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            synchronized (handler) {
                for (LogRecord r : handler.records) {
                    if (r.getLevel().intValue() >= Level.WARNING.intValue()
                            && r.getMessage() != null && r.getMessage().contains(token)) {
                        return true;
                    }
                }
            }
            Thread.sleep(20);
        }
        return false;
    }

    private boolean waitFor(boolean anyWarning) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            synchronized (handler) {
                for (LogRecord r : handler.records) {
                    if (r.getLevel().intValue() < Level.WARNING.intValue()) {
                        continue;
                    }
                    if (anyWarning || r.getThrown() != null) {
                        return true;
                    }
                }
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** 收集日志；失败断言要同时看「有没有 WARNING」与「有没有带 throwable」。 */
    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        synchronized boolean hasWarning() {
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
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
