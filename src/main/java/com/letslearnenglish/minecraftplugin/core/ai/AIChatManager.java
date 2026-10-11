package com.letslearnenglish.minecraftplugin.core.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * AI 对话管理器 — 基于 DeepSeek API 的智能情景对话
 *
 * 特性：
 * - 兼容 DeepSeek API 标准接口（OpenAI 兼容）
 * - API 密钥存储在独立配置文件（ai_config.yml），不提交到版本控制
 * - 场景和难度提示词从 ai_scenarios.yml 读取，支持热重载
 * - 支持 {scenario} / {difficulty} 变量替换
 * - 异步 HTTP 请求，不阻塞服务器主线程
 * - 每玩家频率限制，防止 API 滥用
 * - 对话上下文管理，保持连贯性
 * - 输入安全过滤 + 长度限制
 * - API 不可用时优雅降级
 */
public class AIChatManager {

    private static final String DEFAULT_API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final int DEFAULT_MAX_TOKENS = 128;
    private static final int DEFAULT_DAILY_ROUND_LIMIT = 30;
    private static final int DEFAULT_COUNT_MIN_ROUNDS = 3;
    /** R-20：401/403 鉴权失败后熔断时长（毫秒）——期间不再打 API，避免烧钱/刷日志。 */
    private static final long AUTH_CIRCUIT_COOLDOWN_MS = 5 * 60 * 1000L;
    private static final double TEMPERATURE = 0.8;
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 2000;
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    private final LetsLearnEnglish plugin;
    private final Gson gson = new Gson();

    // 以下配置字段在构造 / 热重载时由主线程写入，但会被异步线程读取
    // （chatAsync 可由 AsyncPlayerChatEvent 触发，HTTP 请求在 supplyAsync 中执行），
    // 因此统一加 volatile 保证跨线程可见性
    private volatile String apiUrl;
    private volatile String apiKey;
    private volatile String model;
    private volatile int maxContextTokens;
    private volatile int timeoutSeconds;
    private volatile int rateLimitSeconds;
    private volatile int maxInputLength;
    private volatile int maxPromptLength;
    private volatile int maxTokens;
    private volatile int dailyRoundLimit;
    private volatile int countMinRounds;
    private volatile boolean debug;
    /** R-20：鉴权熔断"开启至"时间戳（毫秒）；&gt; now 即熔断中。成功请求/reload 清零。 */
    private volatile long authCircuitOpenUntil = 0L;

    private final Map<UUID, Long> lastRequestTime = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRequests = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Deque<MessagePair>> conversationContexts = new ConcurrentHashMap<>();

    /** 每个玩家当前的会话 ID */
    private final Map<UUID, String> activeSessionIds = new ConcurrentHashMap<>();

    /** 每个玩家当前使用的难度 */
    private final Map<UUID, String> playerDifficulties = new ConcurrentHashMap<>();
    private static final String DEFAULT_DIFFICULTY = "medium";

    /** 每个玩家当前会话的 System Prompt（用于导出场景分享码） */
    private final Map<UUID, String> playerSystemPrompts = new ConcurrentHashMap<>();

    /** 每个玩家本轮已收到的有效玩家消息条数（非退出词，sanitize 后非空） */
    private final Map<UUID, Integer> sessionValidInputs = new ConcurrentHashMap<>();
    /** 每个玩家本轮是否至少有一次成功的 API 回复 */
    private final Set<UUID> sessionHasAiReply = ConcurrentHashMap.newKeySet();

    /** 场景配置（保持插入顺序） */
    private final LinkedHashMap<String, ScenarioConfig> scenarios = new LinkedHashMap<>();
    /** 难度配置 */
    private final LinkedHashMap<String, DifficultyConfig> difficulties = new LinkedHashMap<>();
    /** 每玩家个人场景（key → ScenarioConfig，key 形如"personal:<uuid8>:<slug>"全局唯一） */
    private final Map<UUID, LinkedHashMap<String, ScenarioConfig>> personalScenarios = new ConcurrentHashMap<>();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private record MessagePair(String role, String content) {}

    public static class ScenarioConfig {
        public final String key;
        public String displayName;
        public String displayNameZh;
        public Material material;
        public String prompt;

        ScenarioConfig(String key) { this.key = key; }

        public String getDisplayName(boolean zh) {
            return zh ? displayNameZh : displayName;
        }
    }

    public static class DifficultyConfig {
        public final String key;
        public String displayName;
        public String displayNameZh;
        public String prompt;

        DifficultyConfig(String key) { this.key = key; }

        public String getDisplayName(boolean zh) {
            return zh ? displayNameZh : displayName;
        }
    }

    // ============================================================
    //  构造与配置加载
    // ============================================================

    public AIChatManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        loadConfig();
        loadScenariosConfig();
    }

    public void reload() {
        loadConfig();
        loadScenariosConfig();
        plugin.getLogger().info("AIChatManager reloaded — scenarios=" + scenarios.size()
                + " difficulties=" + difficulties.size());
    }

    private void loadConfig() {
        File aiConfigFile = new File(plugin.getDataFolder(), "ai_config.yml");
        if (!aiConfigFile.exists()) {
            plugin.getLogger().warning("ai_config.yml not found! AI chat will not work.");
            plugin.getLogger().warning("Create ai_config.yml with 'api-key' field in the plugin data folder.");
            this.apiKey = null;
        } else {
            YamlConfiguration aiConfig = YamlConfiguration.loadConfiguration(aiConfigFile);
            this.apiKey = aiConfig.getString("api-key", null);
            if (this.apiKey == null || this.apiKey.isEmpty() || this.apiKey.equals("YOUR_API_KEY_HERE")) {
                plugin.getLogger().warning("Invalid API key in ai_config.yml. AI chat will not work.");
                this.apiKey = null;
            }
        }

        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfigManager().getMainConfig();
        this.apiUrl = config.getString("ai-chat.api-url", DEFAULT_API_URL);
        this.model = config.getString("ai-chat.model", DEFAULT_MODEL);
        this.maxContextTokens = config.getInt("ai-chat.max-context-tokens", DEFAULT_MAX_CONTEXT_TOKENS);
        this.timeoutSeconds = config.getInt("ai-chat.timeout-seconds", 15);
        this.rateLimitSeconds = config.getInt("ai-chat.rate-limit-seconds", 3);
        this.maxInputLength = config.getInt("ai-chat.max-input-length", 200);
        this.maxPromptLength = config.getInt("scenario.max-prompt-length", 2000);
        this.maxTokens = config.getInt("ai-chat.max-tokens", DEFAULT_MAX_TOKENS);
        this.dailyRoundLimit = config.getInt("ai-chat.daily-round-limit", DEFAULT_DAILY_ROUND_LIMIT);
        this.countMinRounds = config.getInt("ai-chat.count-min-rounds", DEFAULT_COUNT_MIN_ROUNDS);
        this.debug = config.getBoolean("ai-chat.debug", false);

        // R-20：重载配置（含 /lea reload 与 API 密钥重载）时清掉鉴权熔断，换了 Key 立即恢复。
        this.authCircuitOpenUntil = 0L;

        if (debug) {
            plugin.getLogger().info("[AI-Debug] Debug logging enabled for AI Chat.");
        }
    }

    private void debugLog(String msg) {
        if (debug) {
            plugin.getLogger().info("[AI-Debug] " + msg);
        }
    }

    private void loadScenariosConfig() {
        scenarios.clear();
        difficulties.clear();

        // 保存默认资源到插件数据文件夹
        File dataFile = new File(plugin.getDataFolder(), "ai_scenarios.yml");
        if (!dataFile.exists()) {
            try {
                plugin.saveResource("ai_scenarios.yml", false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Could not save default ai_scenarios.yml from JAR.");
            }
        }

        // 从插件数据文件夹加载（优先，支持用户修改）
        YamlConfiguration config;
        if (dataFile.exists()) {
            config = YamlConfiguration.loadConfiguration(dataFile);
        } else {
            // fallback: 从 JAR 内的资源加载
            InputStream is = plugin.getResource("ai_scenarios.yml");
            if (is == null) {
                plugin.getLogger().severe("ai_scenarios.yml not found in JAR! Using built-in defaults.");
                loadBuiltinScenarios();
                return;
            }
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
        }

        // 加载难度
        ConfigurationSection diffSection = config.getConfigurationSection("difficulties");
        if (diffSection != null) {
            for (String key : diffSection.getKeys(false)) {
                ConfigurationSection sec = diffSection.getConfigurationSection(key);
                if (sec == null) continue;
                DifficultyConfig dc = new DifficultyConfig(key);
                dc.displayName = sec.getString("display_name", key);
                dc.displayNameZh = sec.getString("display_name_zh", dc.displayName);
                dc.prompt = sec.getString("prompt", "");
                difficulties.put(key, dc);
            }
        }

        // 确保至少有一个难度
        if (difficulties.isEmpty()) {
            difficulties.put("medium", createDefaultDifficulty("medium", "Medium", "中等",
                    "Use everyday English at a normal pace."));
        }

        // 加载场景
        ConfigurationSection sceneSection = config.getConfigurationSection("scenarios");
        if (sceneSection != null) {
            for (String key : sceneSection.getKeys(false)) {
                ConfigurationSection sec = sceneSection.getConfigurationSection(key);
                if (sec == null) continue;
                ScenarioConfig sc = new ScenarioConfig(key);
                sc.displayName = sec.getString("display_name", key);
                sc.displayNameZh = sec.getString("display_name_zh", sc.displayName);
                try {
                    sc.material = Material.valueOf(sec.getString("material", "PAPER").toUpperCase());
                } catch (IllegalArgumentException e) {
                    sc.material = Material.PAPER;
                }
                sc.prompt = sec.getString("prompt", "");
                scenarios.put(key, sc);
            }
        }

        if (scenarios.isEmpty()) {
            loadBuiltinScenarios();
        }
    }

    private void loadBuiltinScenarios() {
        scenarios.clear();
        difficulties.clear();

        difficulties.put("easy", createDefaultDifficulty("easy", "Easy", "简单",
                "Use very simple words and short sentences. Be very patient."));
        difficulties.put("medium", createDefaultDifficulty("medium", "Medium", "中等",
                "Use everyday English at a normal pace. Correct mistakes politely."));
        difficulties.put("hard", createDefaultDifficulty("hard", "Hard", "困难",
                "Use natural, idiomatic English. Challenge the player."));

        String[] scenarioKeys = {"airport", "restaurant", "shopping", "hotel", "hospital", "free_talk"};
        String[] names = {"Airport Check-in", "Restaurant Ordering", "Shopping Mall", "Hotel Check-in", "Hospital Visit", "Free Talk"};
        String[] namesZh = {"机场值机", "餐厅点餐", "购物中心", "酒店入住", "医院就诊", "自由对话"};
        Material[] mats = {Material.ELYTRA, Material.COOKED_BEEF, Material.LEATHER_CHESTPLATE,
                           Material.RED_BED, Material.ENCHANTED_BOOK, Material.BOOK};
        String[] prompts = {
            "You are an airline check-in staff. Help the passenger with passport, luggage, seat, and gate.",
            "You are a waiter. Guide the customer through greeting, menu, ordering, drinks, and dessert.",
            "You are a shop assistant. Help the customer find clothes: ask needs, suggest sizes and colors.",
            "You are a hotel receptionist. Help the guest: confirm reservation, ask preferences, give key.",
            "You are a doctor. Ask about symptoms and duration, give advice, prescribe rest or medicine.",
            "You are a friendly English tutor. Chat naturally, ask questions, suggest topics if stuck."
        };

        for (int i = 0; i < scenarioKeys.length; i++) {
            ScenarioConfig sc = new ScenarioConfig(scenarioKeys[i]);
            sc.displayName = names[i];
            sc.displayNameZh = namesZh[i];
            sc.material = mats[i];
            sc.prompt = prompts[i];
            scenarios.put(scenarioKeys[i], sc);
        }
    }

    private DifficultyConfig createDefaultDifficulty(String key, String name, String nameZh, String prompt) {
        DifficultyConfig dc = new DifficultyConfig(key);
        dc.displayName = name;
        dc.displayNameZh = nameZh;
        dc.prompt = prompt;
        return dc;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }

    // ============================================================
    //  频率限制
    // ============================================================

    public int getRemainingCooldown(Player player) {
        Long lastTime = lastRequestTime.get(player.getUniqueId());
        if (lastTime == null) return 0;
        long elapsed = (System.currentTimeMillis() - lastTime) / 1000;
        return Math.max(0, rateLimitSeconds - (int) elapsed);
    }

    private void markRequestTime(Player player) {
        lastRequestTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    // ============================================================
    //  难度管理
    // ============================================================

    public void setPlayerDifficulty(Player player, String difficulty) {
        if (difficulties.containsKey(difficulty)) {
            playerDifficulties.put(player.getUniqueId(), difficulty);
        }
    }

    public String getPlayerDifficulty(Player player) {
        String diff = playerDifficulties.get(player.getUniqueId());
        return (diff != null) ? diff : DEFAULT_DIFFICULTY;
    }

    public LinkedHashMap<String, DifficultyConfig> getDifficulties() {
        return difficulties;
    }

    // ============================================================
    //  上下文管理
    // ============================================================

    public void startConversation(Player player, String scenario) {
        String difficulty = getPlayerDifficulty(player);
        Deque<MessagePair> context = new ArrayDeque<>();
        String systemPrompt = buildSystemPrompt(scenario, difficulty, plugin.getPlayerLanguage(player),
                player.getUniqueId());
        String sessionId = UUID.randomUUID().toString().substring(0, 8);

        debugLog("[" + player.getName() + "] 开始对话 — 场景=" + scenario + " 难度=" + difficulty + " 会话=" + sessionId);
        debugLog("[" + player.getName() + "] 系统提示词(" + systemPrompt.length() + "字符): "
                + systemPrompt.substring(0, Math.min(120, systemPrompt.length())).replace("\n", "\\n") + "...");

        context.add(new MessagePair("system", systemPrompt));
        conversationContexts.put(player.getUniqueId(), context);
        activeSessionIds.put(player.getUniqueId(), sessionId);
        playerSystemPrompts.put(player.getUniqueId(), systemPrompt);
        // 新会话重置本轮有效对话计数
        sessionValidInputs.put(player.getUniqueId(), 0);
        sessionHasAiReply.remove(player.getUniqueId());
        // R-18：清掉上一场遗留的 in-flight 标记与冷却时间戳，否则 GUI 连点两个场景时：
        // 旧 pendingRequests 未清 → 新会话首条被 request-in-progress 误拒；
        // 旧 lastRequestTime 未清 → 新会话首条可能被冷却拦截。
        pendingRequests.remove(player.getUniqueId());
        lastRequestTime.remove(player.getUniqueId());

        // Write system prompt to DB asynchronously
        final UUID playerId = player.getUniqueId();
        final String finalSessionId = sessionId;
        final String finalPrompt = systemPrompt;
        CompletableFuture.runAsync(() -> {
            plugin.getDatabaseManager().insertChatHistory(playerId, finalSessionId, "system", finalPrompt);
        });
    }

    /**
     * 触发 AI 主动发送开场白。
     * 先添加用户触发语到上下文，再异步请求 AI，回调返回 AI 的开场回复。
     */
    public void triggerAiOpening(Player player, AIChatCallback callback) {
        if (!isAvailable()) {
            callback.onResponse(null, null);
            return;
        }
        Deque<MessagePair> context = conversationContexts.get(player.getUniqueId());
        if (context == null) {
            callback.onResponse(null, null);
            return;
        }
        String trigger = "Please start the conversation by greeting me and introducing the situation.";
        // R-16：开场白走独立通道 —— 不计有效输入、不计每日轮次、不查每日上限。
        dispatchChat(player, trigger, callback, false);
    }

    private String buildSystemPrompt(String scenarioKey, String difficultyKey, String lang) {
        return buildSystemPrompt(scenarioKey, difficultyKey, lang, null);
    }

    private String buildSystemPrompt(String scenarioKey, String difficultyKey, String lang, UUID ownerId) {
        ScenarioConfig sc = scenarios.get(scenarioKey);
        if (sc == null && ownerId != null) {
            LinkedHashMap<String, ScenarioConfig> mine = personalScenarios.get(ownerId);
            if (mine != null) {
                sc = mine.get(scenarioKey);
            }
        }
        DifficultyConfig dc = difficulties.get(difficultyKey);
        boolean zh = "zh".equalsIgnoreCase(lang);

        String scenarioName = (sc != null) ? sc.getDisplayName(zh) : scenarioKey;
        String scenarioPrompt = (sc != null) ? sc.prompt : "Chat with the player.";
        String difficultyPrompt = (dc != null) ? dc.prompt : "";

        return "You are role-playing a scenario: " + scenarioName + ".\n\n"
                + "=== SCENE ===\n" + scenarioPrompt.trim() + "\n\n"
                + "=== DIFFICULTY ===\n" + difficultyPrompt.trim() + "\n\n"
                + "Important rules:\n"
                + "- Always stay in character.\n"
                + "- If the player writes in Chinese, gently encourage them to try in English.\n"
                + "- Remember conversation context and refer back to earlier messages.\n"
                + "- End responses with a question or prompt when appropriate to keep the conversation going.\n"
                + "- About correcting mistakes: follow the difficulty instructions above.";
    }

    public void endConversation(Player player) {
        conversationContexts.remove(player.getUniqueId());
        lastRequestTime.remove(player.getUniqueId());
        pendingRequests.remove(player.getUniqueId());
        activeSessionIds.remove(player.getUniqueId());
        playerSystemPrompts.remove(player.getUniqueId());

        // H3：进退即计数刷取 — 仅当本轮有效对话达到阈值才计数。
        // 有效对话 = 收到 ≥N 条玩家非退出词消息（sanitize 后非空）且至少有一次成功 API 回复。
        int validInputs = sessionValidInputs.getOrDefault(player.getUniqueId(), 0);
        boolean hasReply = sessionHasAiReply.contains(player.getUniqueId());
        sessionValidInputs.remove(player.getUniqueId());
        sessionHasAiReply.remove(player.getUniqueId());
        if (validInputs < countMinRounds || !hasReply) {
            debugLog("[" + player.getName() + "] 对话结束未计数 — 有效输入=" + validInputs
                    + " 阈值=" + countMinRounds + " 有AI回复=" + hasReply);
            return;
        }

        // AI 对话计数 + 成就检查
        // R-28：计数改走 PlayerDataStore（落 player_data.conversation_count，即时 UPSERT），
        // 重启后 AI_CHAT_10/50 成就不再因纯内存计数清零而永不触发。
        int count = plugin.getPlayerDataStore().incrementConversationCount(player.getUniqueId());
        plugin.getAchievementManager().checkAIChatAchievement(player.getUniqueId(), count);
        // Daily challenge: AI conversation completed
        plugin.getDailyChallengeManager().updateProgress(player.getUniqueId(), ChallengeType.AI_CHAT_5, 1);
    }

    /**
     * 下线路径专用结束：与 {@link #endConversation(Player)} 走同一有效对话标准，
     * 未达阈值不计数。下线时玩家已不在，但计数判定只需内存状态，与在线与否无关。
     */
    public void endConversationOnQuit(Player player) {
        endConversation(player);
    }

    public boolean hasActiveConversation(Player player) {
        return conversationContexts.containsKey(player.getUniqueId());
    }

    // ============================================================
    //  输入安全过滤
    // ============================================================

    private String sanitizeInput(String input) {
        if (input == null || input.isEmpty()) return "";
        if (input.length() > maxInputLength) {
            input = input.substring(0, maxInputLength);
        }
        return input.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "").trim();
    }

    /**
     * Estimate token count from text length. Rough approximation: 1 token ≈ 4 characters
     * for English text. This is a heuristic — use with safety margin in the token budget.
     */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / (double) CHARS_PER_TOKEN_ESTIMATE);
    }

    /**
     * Trim the conversation context so total estimated tokens stay within
     * {@code maxContextTokens}. The system prompt (index 0) is ALWAYS preserved.
     * Only complete user/assistant pairs are removed, starting from the oldest
     * (closest to index 1). This guarantees the message list never breaks the
     * required user/assistant alternation after the system prompt.
     */
    private void trimContextByTokens(Deque<MessagePair> context) {
        // Calculate total token count
        int totalTokens = 0;
        for (MessagePair pair : context) {
            totalTokens += estimateTokens(pair.content());
        }

        // Remove oldest user/assistant pairs until within budget.
        // Index 0 = system prompt (never remove).
        // Index 1 = oldest user, index 2 = oldest assistant, etc.
        while (totalTokens > maxContextTokens && context.size() >= 3) {
            // We need at least system + one pair. Iterate to find the first user+assistant pair.
            Iterator<MessagePair> it = context.iterator();
            it.next(); // skip system prompt — NEVER remove

            // Find and remove the oldest user message (immediately after system)
            if (it.hasNext()) {
                MessagePair removed = it.next();
                totalTokens -= estimateTokens(removed.content());
                it.remove();
            }
            // Find and remove the oldest assistant message (next in sequence)
            if (it.hasNext()) {
                MessagePair removed = it.next();
                totalTokens -= estimateTokens(removed.content());
                it.remove();
            }
        }

        if (totalTokens > maxContextTokens) {
            debugLog("上下文裁剪后仍超出 token 限制 — total=" + totalTokens
                    + " limit=" + maxContextTokens + " messages=" + context.size());
        }
    }

    // ============================================================
    //  异步 AI 对话请求
    // ============================================================

    public void chatAsync(Player player, String input, AIChatCallback callback) {
        // 正常玩家消息：计入有效输入与每日轮次、受每日上限约束。
        dispatchChat(player, input, callback, true);
    }

    /**
     * R-16/17/19/20：AI 请求统一派发。
     *
     * @param countsTowardUsage true=正常玩家消息（计有效输入+每日轮次+查每日上限）；
     *                          false=开场白等系统触发（独立通道：不计输入/不计轮次/不查上限）
     */
    private void dispatchChat(Player player, String input, AIChatCallback callback,
                              boolean countsTowardUsage) {
        if (!isAvailable()) {
            callback.onResponse(null, plugin.getMessageUtil().getPlayerMessage(
                    player, "dialogue.ai-not-configured"));
            return;
        }

        // R-20：401/403 鉴权失败熔断期内直接拒绝，不再打 API（烧钱/刷日志）。
        if (isAuthCircuitOpen()) {
            callback.onResponse(null, plugin.getMessageUtil().getPlayerMessage(
                    player, "dialogue.auth-failed"));
            return;
        }

        int cooldown = getRemainingCooldown(player);
        if (cooldown > 0) {
            debugLog("[" + player.getName() + "] 限流拒绝 — 剩余冷却 " + cooldown + "s");
            callback.onResponse(null, plugin.getMessageUtil().getPlayerMessage(
                    player, "dialogue.cooldown", "seconds", String.valueOf(cooldown)));
            return;
        }

        String sanitized = sanitizeInput(input);
        if (sanitized.isEmpty()) {
            callback.onResponse(null, null);
            return;
        }

        // C4/R-16：仅正常玩家消息查今日轮次上限；开场白(countsTowardUsage=false)不受限。
        if (countsTowardUsage) {
            int todayRounds;
            try {
                todayRounds = plugin.getPlayerDataStore().getTodayAiRound(player.getUniqueId());
            } catch (Exception e) {
                debugLog("[" + player.getName() + "] 读取今日 AI 轮次失败，拒绝请求: " + e.getMessage());
                callback.onResponse(null, plugin.getMessageUtil().getPlayerMessage(
                        player, "dialogue.request-failed"));
                return;
            }
            if (todayRounds >= dailyRoundLimit) {
                debugLog("[" + player.getName() + "] 每日 AI 轮次超限 — 今日=" + todayRounds
                        + " 上限=" + dailyRoundLimit);
                callback.onResponse(null, plugin.getMessageUtil().getPlayerMessage(
                        player, "dialogue.daily-limit-reached",
                        "used", String.valueOf(todayRounds),
                        "limit", String.valueOf(dailyRoundLimit)));
                return;
            }
        }

        // R-19：markRequestTime 原先在此（上下文/pending 检查之前），导致被这些检查
        // 拒绝时也烧掉冷却。改为移动到所有拒绝检查通过、确定要发请求之后再记（见下）。
        Deque<MessagePair> context = conversationContexts.get(player.getUniqueId());
        if (context == null) {
            callback.onResponse(null, plugin.getMessageUtil().getPlayerMessage(
                    player, "dialogue.no-active-conversation"));
            return;
        }

        if (!pendingRequests.add(player.getUniqueId())) {
            callback.onResponse(null, plugin.getMessageUtil().getPlayerMessage(
                    player, "dialogue.request-in-progress"));
            return;
        }

        // R-19：所有拒绝检查已过，确定要发请求 —— 此刻才烧冷却（被上面任一检查拒绝时不烧）。
        markRequestTime(player);

        debugLog("[" + player.getName() + "] 用户输入(" + sanitized.length() + "字符): "
                + sanitized.substring(0, Math.min(100, sanitized.length())).replace("\n", "\\n"));

        final String sessionId = activeSessionIds.get(player.getUniqueId());
        final UUID playerId = player.getUniqueId();
        final String finalSanitized = sanitized;
        final boolean counts = countsTowardUsage;
        // R-19：有效输入计数移到成功回调（失败不计输入）；R-16：开场白不计。

        JsonObject requestBody;
        synchronized (context) {
            context.add(new MessagePair("user", sanitized));
            trimContextByTokens(context);
            requestBody = buildRequestBody(new ArrayDeque<>(context));
        }

        // Write user message to DB asynchronously
        if (sessionId != null) {
            CompletableFuture.runAsync(() -> {
                plugin.getDatabaseManager().insertChatHistory(playerId, sessionId, "user", finalSanitized);
            });
        }

        debugLog("[" + player.getName() + "] 发送 API 请求 — model=" + model
                + " messages=" + requestBody.getAsJsonArray("messages").size()
                + " body-size=" + gson.toJson(requestBody).length() + "B");

        CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                String response = sendHttpRequest(requestBody);
                long elapsed = System.currentTimeMillis() - startTime;

                debugLog("[" + player.getName() + "] API 响应(" + elapsed + "ms, "
                        + (response != null ? response.length() : 0) + "字符): "
                        + (response != null ? response.substring(0, Math.min(150, response.length())).replace("\n", "\\n") : "null"));
                if (response != null) {
                    // R-20：成功 200 说明 Key 可用，清掉鉴权熔断（若之前被 401/403 打开）。
                    authCircuitOpenUntil = 0L;
                    synchronized (context) {
                        context.add(new MessagePair("assistant", response));
                        trimContextByTokens(context);
                    }
                    // R-17：按 session 隔离 —— 仅当发起时的 session 仍是该玩家当前 active
                    // session，才计入本轮（防下线/切场景后旧 in-flight 回调污染新会话计数）。
                    boolean sameSession = sessionId != null
                            && sessionId.equals(activeSessionIds.get(playerId));
                    if (sameSession) {
                        sessionHasAiReply.add(playerId);
                        // H3/C4 + R-16/R-19：一次成功回复 = 一轮；仅正常消息计有效输入与今日轮次
                        // （开场白 counts=false 不计；失败不进此分支，故失败不计输入也不计轮次）。
                        if (counts) {
                            sessionValidInputs.merge(playerId, 1, Integer::sum);
                            try {
                                plugin.getPlayerDataStore().incrementTodayAiRound(playerId);
                            } catch (Exception e) {
                                debugLog("[" + player.getName() + "] 累加今日 AI 轮次失败: " + e.getMessage());
                            }
                        }
                    }
                    // Write AI response to DB asynchronously (sessionId captured from enclosing scope)
                    if (sessionId != null) {
                        try {
                            plugin.getDatabaseManager().insertChatHistory(playerId, sessionId, "assistant", response);
                        } catch (Exception e) {
                            // DB write failure should not affect the chat flow
                            debugLog("[" + player.getName() + "] 写入 AI 回复到数据库失败: " + e.getMessage());
                        }
                    }
                }
                return response;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).thenAcceptAsync(responseText -> {
            pendingRequests.remove(playerId);
            callback.onResponse(responseText, null);
        }, runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable))
        .exceptionally(throwable -> {
            pendingRequests.remove(playerId);
            Throwable cause = (throwable instanceof CompletionException) ? throwable.getCause() : throwable;
            // R-20：按异常类型分流提示并在 401/403 时熔断（classifyFailure 内处理）。
            String errorKey = classifyFailure(cause);
            plugin.getLogger().log(Level.WARNING, "AI API request failed for "
                    + player.getName() + " (" + errorKey + ")", cause);
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    callback.onResponse(null, plugin.getMessageUtil().getPlayerMessage(
                            player, errorKey)));
            return null;
        });
    }

    private JsonObject buildRequestBody(Deque<MessagePair> context) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", TEMPERATURE);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        for (MessagePair pair : context) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", pair.role());
            msg.addProperty("content", pair.content());
            messages.add(msg);
        }
        body.add("messages", messages);

        return body;
    }

    private String sendHttpRequest(JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            plugin.getLogger().warning("AI API error (" + response.statusCode() + "): "
                    + response.body());
            // R-20：带上状态码，供 classifyFailure 区分 401/403(鉴权) / 429(限流) / 其它。
            throw new AiApiException(response.statusCode(), "API returned " + response.statusCode());
        }

        // Null-safe JSON navigation
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("AI API response missing or empty 'choices' array");
        }
        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        if (firstChoice == null) {
            throw new IOException("AI API response first choice is null");
        }
        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null) {
            throw new IOException("AI API response missing 'message' object");
        }
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            throw new IOException("AI API response missing or null 'content' field");
        }
        return content.getAsString();
    }

    // ============================================================
    //  R-20：失败分类与鉴权熔断
    // ============================================================

    /** R-20：鉴权熔断是否开启（期间不打 API）。 */
    private boolean isAuthCircuitOpen() {
        return System.currentTimeMillis() < authCircuitOpenUntil;
    }

    /** R-20：开启鉴权熔断（401/403）——一段时间内拒绝所有 API 请求。 */
    private void openAuthCircuit() {
        authCircuitOpenUntil = System.currentTimeMillis() + AUTH_CIRCUIT_COOLDOWN_MS;
        plugin.getLogger().severe("AI API rejected the key (401/403). Circuit opened for "
                + (AUTH_CIRCUIT_COOLDOWN_MS / 1000) + "s — not calling the API until it closes. "
                + "Check 'api-key' in ai_config.yml, then /lea reload.");
    }

    /**
     * R-20：把请求失败归类成对应的 i18n 提示键；401/403 额外开启鉴权熔断。
     * <ul>
     *   <li>401/403 → 鉴权失败（提示服主查 Key）+ 熔断</li>
     *   <li>429 → 限流（稍后再试，不熔断）</li>
     *   <li>连接/读取超时 → 超时提示</li>
     *   <li>其它（5xx/解析/网络）→ 通用失败</li>
     * </ul>
     */
    private String classifyFailure(Throwable cause) {
        int status = (cause instanceof AiApiException api) ? api.statusCode : -1;
        if (status == 401 || status == 403) {
            openAuthCircuit();
            return "dialogue.auth-failed";
        }
        if (status == 429) {
            return "dialogue.rate-limited";
        }
        if (cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.net.http.HttpConnectTimeoutException) {
            return "dialogue.timeout";
        }
        return "dialogue.request-failed";
    }

    /** R-20：携带 HTTP 状态码的 API 异常，供 {@link #classifyFailure} 分流。 */
    private static final class AiApiException extends IOException {
        final int statusCode;

        AiApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    // ============================================================
    //  Public accessors
    // ============================================================

    /** 获取有序场景列表 */
    public List<String> getAvailableScenarios() {
        return new ArrayList<>(scenarios.keySet());
    }

    /** 获取所有场景配置 */
    public LinkedHashMap<String, ScenarioConfig> getScenarioConfigs() {
        return scenarios;
    }

    /** 获取玩家当前会话的 System Prompt（用于导出场景分享码），无会话时返回 null */
    public String getCurrentSystemPrompt(Player player) {
        return playerSystemPrompts.get(player.getUniqueId());
    }

    // ============================================================
    //  个人场景导入（H7：不再写全局 ai_scenarios.yml / 不 reload 全局）
    //  存储：plugins/LetsLearnEnglish/player_scenarios/<uuid>.yml
    //  格式：scenarios.<key>.{display_name, display_name_zh, material, prompt}
    // ============================================================

    /**
     * 导入一条玩家个人场景：落盘到该玩家的个人场景文件，并加载进内存。
     * 不触碰全局 ai_scenarios.yml，不触发全局 reload，不影响其他玩家。
     *
     * @param player 导入者
     * @param name   场景展示名（调用方已校验：≤32 字符，仅中英文/数字/下划线）
     * @param prompt 场景 prompt（调用方已校验长度）
     * @return 个人场景 key（全局唯一），落盘失败返回 null
     */
    public String importPersonalScenario(Player player, String name, String prompt) {
        UUID ownerId = player.getUniqueId();
        String slug = name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (slug.isEmpty()) slug = "custom";
        if (slug.length() > 24) slug = slug.substring(0, 24);
        // key 全局唯一：personal:<uuid前8>:<slug>，避免两玩家同名场景互相覆盖
        String key = "personal:" + ownerId.toString().substring(0, 8) + ":" + slug;

        File dir = new File(plugin.getDataFolder(), "player_scenarios");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Failed to create player_scenarios directory.");
            return null;
        }
        File file = new File(dir, ownerId + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("scenarios." + key + ".display_name", name);
        config.set("scenarios." + key + ".display_name_zh", name);
        config.set("scenarios." + key + ".material", "PAPER");
        config.set("scenarios." + key + ".prompt", prompt);
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save personal scenario for "
                    + player.getName() + ": " + e.getMessage());
            return null;
        }

        ScenarioConfig sc = new ScenarioConfig(key);
        sc.displayName = name;
        sc.displayNameZh = name;
        sc.material = Material.PAPER;
        sc.prompt = prompt;
        personalScenarios.computeIfAbsent(ownerId, k -> new LinkedHashMap<>()).put(key, sc);
        return key;
    }

    /**
     * 重载指定玩家的个人场景（从磁盘读入内存）。仅影响该玩家，不碰全局。
     * 个人文件损坏/缺失时清空其内存个人场景，保证"坏文件不炸全局"。
     */
    public void reloadPersonalScenarios(UUID ownerId) {
        LinkedHashMap<String, ScenarioConfig> mine =
                personalScenarios.computeIfAbsent(ownerId, k -> new LinkedHashMap<>());
        mine.clear();
        File file = new File(plugin.getDataFolder(), "player_scenarios" + File.separator + ownerId + ".yml");
        if (!file.exists()) {
            return;
        }
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = config.getConfigurationSection("scenarios");
            if (section == null) {
                return;
            }
            for (String key : section.getKeys(false)) {
                ConfigurationSection sec = section.getConfigurationSection(key);
                if (sec == null) continue;
                ScenarioConfig sc = new ScenarioConfig(key);
                sc.displayName = sec.getString("display_name", key);
                sc.displayNameZh = sec.getString("display_name_zh", sc.displayName);
                try {
                    sc.material = Material.valueOf(sec.getString("material", "PAPER").toUpperCase());
                } catch (IllegalArgumentException e) {
                    sc.material = Material.PAPER;
                }
                sc.prompt = sec.getString("prompt", "");
                mine.put(key, sc);
            }
        } catch (Exception e) {
            // 个人文件损坏：清空内存副本（已 clear），只记 warning，不影响全局与他人
            plugin.getLogger().warning("Failed to load personal scenarios for "
                    + ownerId + ", cleared in-memory copy: " + e.getMessage());
        }
    }

    /** 获取指定玩家的个人场景（内存副本；懒加载，保证下次登录仍可用）。 */
    public LinkedHashMap<String, ScenarioConfig> getPersonalScenarios(UUID ownerId) {
        LinkedHashMap<String, ScenarioConfig> mine = personalScenarios.get(ownerId);
        if (mine == null) {
            reloadPersonalScenarios(ownerId);
            mine = personalScenarios.get(ownerId);
        }
        return (mine != null) ? mine : new LinkedHashMap<>();
    }

    /** 获取指定玩家可见的全部场景 key（全局场景 + 其个人场景）。 */
    public List<String> getAvailableScenarios(Player player) {
        List<String> result = new ArrayList<>(scenarios.keySet());
        result.addAll(getPersonalScenarios(player.getUniqueId()).keySet());
        return result;
    }

    /** 获取指定玩家可见的全部场景配置（全局 + 个人；个人场景不污染全局 map）。 */
    public LinkedHashMap<String, ScenarioConfig> getScenarioConfigs(Player player) {
        LinkedHashMap<String, ScenarioConfig> merged = new LinkedHashMap<>(scenarios);
        merged.putAll(getPersonalScenarios(player.getUniqueId()));
        return merged;
    }

    /** 按玩家解析场景展示名（个人场景仅本人可见）。 */
    public String getScenarioDisplayName(Player player, String scenarioKey) {
        LinkedHashMap<String, ScenarioConfig> all = getScenarioConfigs(player);
        ScenarioConfig sc = all.get(scenarioKey);
        if (sc == null) {
            return scenarioKey;
        }
        boolean zh = "zh".equalsIgnoreCase(plugin.getPlayerLanguage(player));
        return sc.getDisplayName(zh);
    }

    // ============================================================
    //  R-49 fan-in：场景/难度稳定排序的唯一真相源
    //  GUI 渲染（ScenarioSelectGUI）与点击映射（ScenarioSelectListener）共用，
    //  避免两侧迭代顺序不一致导致"点 A 开 B"。
    // ============================================================

    /**
     * 按玩家语言稳定排序的可见场景配置（全局 + 个人）。
     * 排序键：当前语言展示名（空名兜底空串）→ key tie-break。个人场景参与同一排序。
     */
    public List<ScenarioConfig> getSortedScenarioConfigs(Player player) {
        final boolean zh = "zh".equalsIgnoreCase(plugin.getPlayerLanguage(player));
        List<ScenarioConfig> sorted = new ArrayList<>(getScenarioConfigs(player).values());
        sorted.sort(Comparator
                .comparing((ScenarioConfig sc) -> {
                    String name = sc.getDisplayName(zh);
                    return (name != null) ? name : "";
                })
                .thenComparing(sc -> sc.key));
        return sorted;
    }

    /** {@link #getSortedScenarioConfigs} 的 key 列表（点击侧按下标映射用，顺序完全一致）。 */
    public List<String> getSortedScenarioKeys(Player player) {
        List<String> keys = new ArrayList<>();
        for (ScenarioConfig sc : getSortedScenarioConfigs(player)) {
            keys.add(sc.key);
        }
        return keys;
    }

    /** 按 key 稳定排序的难度配置（GUI 渲染与点击映射共用）。 */
    public List<DifficultyConfig> getSortedDifficultyConfigs() {
        List<DifficultyConfig> sorted = new ArrayList<>(difficulties.values());
        sorted.sort(Comparator.comparing(dc -> dc.key));
        return sorted;
    }

    /** {@link #getSortedDifficultyConfigs} 的 key 列表（点击侧按下标映射用，顺序完全一致）。 */
    public List<String> getSortedDifficultyKeys() {
        List<String> keys = new ArrayList<>();
        for (DifficultyConfig dc : getSortedDifficultyConfigs()) {
            keys.add(dc.key);
        }
        return keys;
    }

    /** 获取场景 Prompt 最大长度限制 */
    public int getMaxPromptLength() {
        return maxPromptLength;
    }

    public void shutdown() {
        // HttpClient is static and shared — no explicit shutdown needed.
        // Clear in-memory state so stale references don't leak.
        conversationContexts.clear();
        playerDifficulties.clear();
        lastRequestTime.clear();
        pendingRequests.clear();
        activeSessionIds.clear();
        playerSystemPrompts.clear();
        sessionValidInputs.clear();
        sessionHasAiReply.clear();
        personalScenarios.clear();
    }

    @FunctionalInterface
    public interface AIChatCallback {
        void onResponse(String aiResponse, String errorMsg);
    }
}
