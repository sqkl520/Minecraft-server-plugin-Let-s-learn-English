package com.letslearnenglish.minecraftplugin.core.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「思考模式」请求参数（{@code ai-chat.thinking-mode} / {@code ai-chat.reasoning-effort}）的守卫。
 *
 * <p><b>为什么需要这个类</b>：本功能的全部价值都在「默认态」—— 两个键都是 default 时，
 * 请求体必须与加入本功能前<b>逐字节一致</b>。这类"什么都没发生"的契约没有任何运行期
 * 症状可观测：多写一个 {@code body.addProperty("thinking", "default")} 也照样能发出去、
 * 照样能拿到回复，只有在 DeepSeek 真的因为它拒请求、或预算被吃掉返回空 content 时才炸。
 * 所以这里用反射直接调 {@code buildRequestBody} / {@code buildSummaryRequestBody}，
 * 对整段 JSON 字符串做比对（元组顺序 + 内容一起钉死），而不是只断言"字段不存在"。
 *
 * <p><b>技术前提</b>：
 * <ul>
 *   <li>{@link AIChatManager} 构造器会 {@code loadConfig()} + {@code loadScenariosConfig()}，
 *       后者要读 {@code ai_scenarios.yml}（不存在的 {@code getResource} 返回 null 时走
 *       {@code loadBuiltinScenarios()} 内置兜底，不需要真实 JAR 资源）；</li>
 *   <li>因此 {@code getDataFolder()} 必须指到 {@link TempDir}，否则会在仓库里落文件；</li>
 *   <li>配置必须是<b>真</b> {@link YamlConfiguration} —— mock 的 {@code getString(key, def)}
 *       返回 null，会把"默认值生效"这条路径测成 null 分支；</li>
 *   <li>两个 body builder 都是 private，用 {@code getDeclaredMethod} + {@code setAccessible}
 *       调真实实现（仓库既有先例：EnglishSquareModeContractTest、ConfigManagerGuiMaterialTest）。</li>
 * </ul>
 */
class AIChatThinkingModeTest {

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration mainConfig;
    private AIChatManager ai;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        mainConfig = new YamlConfiguration();
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AIChatThinkingModeTest"));
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
    }

    /** 用当前 {@link #mainConfig} 构造一个真的 AIChatManager。 */
    private AIChatManager newManager() {
        return new AIChatManager(plugin);
    }

    /**
     * 反射调用 {@code buildRequestBody(Deque&lt;MessagePair&gt;)}。
     * 入参用 {@code Deque.class}：MessagePair 是私有 record，外部拿不到它的 Class 字面量，
     * 但 {@code getDeclaredMethod} 只需要参数<b>类型</b>对象，Deque 是 public 的。
     */
    @SuppressWarnings("unchecked")
    private String chatBodyJson(AIChatManager manager, String userText) throws Exception {
        Method m = AIChatManager.class.getDeclaredMethod("buildRequestBody", Deque.class);
        m.setAccessible(true);
        Deque<Object> context = new ArrayDeque<>();
        // 造一条 message：用反射实例化私有 record MessagePair(role, content)。
        Class<?> pairClass = Class.forName(
                "com.letslearnenglish.minecraftplugin.core.ai.AIChatManager$MessagePair");
        var ctor = pairClass.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        context.add(ctor.newInstance("user", userText));
        JsonObject body = (JsonObject) m.invoke(manager, context);
        return body.toString();
    }

    // ------------------------------------------------------------------
    // 1. 默认态：逐字节零漂移
    // ------------------------------------------------------------------

    /**
     * 加入本功能之前 {@code buildRequestBody} 的逐字复刻（字段顺序也照抄）。
     * 用它做全量字符串比对，才能同时证明「没多字段」和「既有字段顺序/取值没被顺手改动」。
     */
    private static String legacyChatBody(String model, int maxTokens, double temperature, String userText) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":" + maxTokens
                + ",\"temperature\":" + temperature + ",\"stream\":false"
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"" + userText + "\"}]}";
    }

    @Test
    @DisplayName("默认配置：请求体与旧实现逐字节一致（不含 thinking / reasoning_effort）")
    void defaultConfigProducesByteIdenticalBody() throws Exception {
        // 键完全缺失 —— 线上大多数服主的 config.yml 就是这个状态（升级后未手改）。
        AIChatManager manager = newManager();

        String actual = chatBodyJson(manager, "hello");
        String expected = legacyChatBody("deepseek-chat", 128, 0.8, "hello");

        assertEquals(expected, actual,
                "默认配置下请求体必须与旧实现逐字节一致 —— 多一个字段就是线上行为变更");
        assertFalse(actual.contains("thinking"), "默认态不得出现 thinking 字段");
        assertFalse(actual.contains("reasoning_effort"), "默认态不得出现 reasoning_effort 字段");
    }

    @Test
    @DisplayName("显式写 default/default：与键缺失等价（不是只有缺键才零漂移）")
    void explicitDefaultIsSameAsMissingKeys() throws Exception {
        mainConfig.set("ai-chat.thinking-mode", "default");
        mainConfig.set("ai-chat.reasoning-effort", "default");
        AIChatManager manager = newManager();

        assertEquals(legacyChatBody("deepseek-chat", 128, 0.8, "hi"),
                chatBodyJson(manager, "hi"),
                "显式 default 必须与缺键走同一条 no-op 路径");
    }

    // ------------------------------------------------------------------
    // 2. 开/关两态：字段形状与协议值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("thinking-mode: disabled → \"thinking\":{\"type\":\"disabled\"}")
    void disabledSendsThinkingDisabled() throws Exception {
        mainConfig.set("ai-chat.thinking-mode", "disabled");
        AIChatManager manager = newManager();

        JsonObject body = JsonParser.parseString(chatBodyJson(manager, "hello")).getAsJsonObject();

        assertTrue(body.has("thinking"), "disabled 必须发出 thinking 字段");
        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString(),
                "thinking.type 取值错误 —— 上游会当成无关字段忽略，思考模式照旧开着");
        // 其余字段不受影响（防止顺手改坏既有请求形状）
        assertEquals(128, body.get("max_tokens").getAsInt());
        assertEquals("deepseek-chat", body.get("model").getAsString());
    }

    @Test
    @DisplayName("thinking-mode: enabled 与 reasoning-effort: high 可同时生效")
    void enabledPlusReasoningEffort() throws Exception {
        mainConfig.set("ai-chat.thinking-mode", "enabled");
        mainConfig.set("ai-chat.reasoning-effort", "high");
        AIChatManager manager = newManager();

        JsonObject body = JsonParser.parseString(chatBodyJson(manager, "hello")).getAsJsonObject();

        assertEquals("enabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("high", body.get("reasoning_effort").getAsString());
    }

    @Test
    @DisplayName("reasoning-effort 单独开启时不得带出 thinking 字段")
    void reasoningEffortAloneDoesNotAddThinking() throws Exception {
        mainConfig.set("ai-chat.reasoning-effort", "low");
        AIChatManager manager = newManager();

        JsonObject body = JsonParser.parseString(chatBodyJson(manager, "hello")).getAsJsonObject();

        assertFalse(body.has("thinking"), "两个键相互独立，没配 thinking-mode 就不该发");
        assertEquals("low", body.get("reasoning_effort").getAsString());
    }

    // ------------------------------------------------------------------
    // 3. 非法值：不崩 + 回落 default
    // ------------------------------------------------------------------

    @Test
    @DisplayName("非法 thinking-mode: bogus → 按 default 处理，不崩也不发字段")
    void invalidThinkingModeFallsBackToDefault() throws Exception {
        mainConfig.set("ai-chat.thinking-mode", "bogus");
        AIChatManager manager = newManager();

        String actual = chatBodyJson(manager, "hello");

        assertEquals(legacyChatBody("deepseek-chat", 128, 0.8, "hello"), actual,
                "非法值必须回落 default（请求体与旧实现一致），而不是抛异常或发个瞎猜的枚举");
    }

    @Test
    @DisplayName("非法 reasoning-effort: ultra → 按 default 处理")
    void invalidReasoningEffortFallsBackToDefault() throws Exception {
        mainConfig.set("ai-chat.reasoning-effort", "ultra");
        AIChatManager manager = newManager();

        assertFalse(chatBodyJson(manager, "hello").contains("reasoning_effort"),
                "非法值不得被原样发出去");
    }

    // ------------------------------------------------------------------
    // 4. 总结路径同源
    // ------------------------------------------------------------------

    /**
     * 总结请求体走 {@code buildSummaryRequestBody(Player, SessionSummary, String)}。
     * 这里只验 shape（有无字段 + 取值），不逐字节比对 —— 它的 prompt 文案是另一份契约，
     * 由 ConversationFeedbackTest 负责。
     */
    @Test
    @DisplayName("AI 总结请求体同样受 thinking-mode 控制")
    void summaryBodyHonoursThinkingMode() throws Exception {
        mainConfig.set("ai-chat.thinking-mode", "disabled");
        AIChatManager manager = newManager();

        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(plugin.getPlayerLanguage(player)).thenReturn("en");

        AIChatManager.SessionSummary summary = new AIChatManager.SessionSummary(
                "sess-think", "village_trade", "medium", 3, 3, true, 0L, 60L,
                java.util.List.of(new AIChatManager.TranscriptLine("student", "I want buy emerald")));

        Method m = AIChatManager.class.getDeclaredMethod("buildSummaryRequestBody",
                org.bukkit.entity.Player.class, AIChatManager.SessionSummary.class, String.class);
        m.setAccessible(true);
        JsonObject body = (JsonObject) m.invoke(manager, player, summary, "en");

        assertTrue(body.has("thinking"),
                "总结路径漏接线：对话关了思考、总结还在想，总结的 max_tokens 一样会被吃光");
        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
    }
}
