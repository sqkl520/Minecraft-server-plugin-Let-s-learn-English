package com.letslearnenglish.minecraftplugin.core.ai;

import com.google.gson.Gson;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.ChatFeedbackEntry;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Chat Feedback Manager — AI 会话小结与点评的显示编排 + 持久化（Phase 3 · US-FB 1b，plan-ai-feedback §3.5）。
 *
 * <p>职责边界：本类只做"内容编排 + 落库 + 显示"；HTTP 栈（密钥/熔断/关服守卫/去重）
 * 单源在 {@link AIChatManager}（{@code requestConversationSummary}），本类不复制任何
 * HTTP 逻辑。依赖方向：ChatScenarioListener/LearnCommand/ChatHistoryGUI → 本类
 * → AIChatManager / DatabaseManager，无环。
 *
 * <p>两段落库（plan §"两段落库"）：达标会话在结束/退出时先异步落本地统计行
 * （{@code source=local}）；AI 点评到达后用 UPSERT 覆写为 {@code source=ai} +
 * {@code ai_payload} JSON（经 {@link #persistAfter} 严格串行，防乱序覆写）。
 * 中断/崩溃时本地行已可回看；UPSERT 幂等无读-改-写。
 *
 * <p>线程纪律：{@link #deliverEnd} 呼叫方线程不限（主线程直执行保消息顺序，异步切主线程）；
 * HTTP 回调由 AIChatManager 保证主线程；落库一律异步（{@code isEnabled} 守卫）。
 */
public class ChatFeedbackManager {

    /** Gson 无状态可复用（与 AIChatManager 同姿态；arch tester 6① 收口——避免每单 new）。 */
    private static final Gson GSON = new Gson();

    /**
     * R9"纠错闭环"：单词级纠错判定 —— 单个英文词、字母开头、至少 2 个字母、无空格；
     * 允许词内连字符/撇号（{@code don't} / {@code well-known}），不允许首尾标点。
     * 句子级纠错（含空格/中文/数字开头）不进错题本（错题本是单词库，收句子只会产生脏数据）。
     */
    private static final Pattern WORD_LEVEL_PATTERN =
            Pattern.compile("[A-Za-z](?:[A-Za-z]|['’-][A-Za-z])+");

    /** 目标词长度上限（与 ConversationFeedback 的单词上限对齐，防异常长 token）。 */
    private static final int MAX_FOCUS_WORD_CHARS = 40;

    /** 每玩家"下一场建议目标词"（最近一次 AI 点评产出；去重保插入序）。 */
    private final Map<UUID, List<String>> suggestedFocusWords = new ConcurrentHashMap<>();

    private final LetsLearnEnglish plugin;
    private final AIChatManager aiChatManager;

    public ChatFeedbackManager(LetsLearnEnglish plugin, AIChatManager aiChatManager) {
        this.plugin = plugin;
        this.aiChatManager = aiChatManager;
    }

    // ============================================================
    //  会话结束路径（plan §1.3 状态机）
    // ============================================================

    /**
     * 在线正常结束路径（bye / 命令退出）：状态机全流程 —— 本地小结卡 → （达标时）
     * 本地行落库 → AI 点评请求。呼叫方线程不限。
     *
     * <p>code tester 4 收口：呼叫方已在主线程时同步执行（不再无条件 runTask）——
     * 原实现无条件切下一 tick，导致 ended → return-menu → 卡片 的顺序错位，
     * 与 plan §3.6 / AC-1 的 ended → 卡片 → 占位 → return-menu 不符。
     */
    public void deliverEnd(Player player, AIChatManager.SessionSummary summary) {
        if (summary == null) {
            return; // 3B-1a 边界：无活跃会话无小结
        }
        if (Bukkit.isPrimaryThread()) {
            deliverEndMain(player, summary);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> deliverEndMain(player, summary));
        }
    }

    private void deliverEndMain(Player player, AIChatManager.SessionSummary summary) {
        if (!plugin.getConfigManager().getMainConfig()
                .getBoolean("ai-chat.feedback.enabled", true)) {
            return; // 总开关关闭：行为与旧版一致（静默）
        }
        if (summary.validInputs() == 0) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.empty"));
            return;
        }
        if (!summary.qualified()) {
            // 未达标：不落库、不调 API（plan §1.3）；{required} 复用既有阈值键
            int required = plugin.getConfigManager().getMainConfig()
                    .getInt("ai-chat.count-min-rounds", 3);
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.not-passed",
                    "valid", String.valueOf(summary.validInputs()),
                    "required", String.valueOf(required)));
            return;
        }

        // 达标 → 本地小结卡（≤4 行 + hint）
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.feedback.header"));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.feedback.stats",
                "scenario", scenarioDisplayName(player, summary.scenarioKey()),
                "difficulty", difficultyDisplayName(player, summary.difficultyKey()),
                "duration", formatDuration(player, summary.durationSeconds())));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.feedback.counts",
                "user", String.valueOf(summary.validInputs()),
                "ai", String.valueOf(summary.aiReplyCount())));
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.feedback.passed"));

        // 本地行先落库（两段落库：崩溃时至少可回看统计）；future 传递给 AI 回调——
        // code tester 2 收口：两笔异步写曾无先后序，极端调度下 local 晚于 ai 会把
        // 点评行覆写回 source=local。现在 AI 覆写经 thenCompose 严格排在本地写之后。
        CompletableFuture<Void> localWrite = persistAsync(player.getUniqueId(), summary, "local", null);

        // AI 点评（独立开关 + 预检含 dedup —— 预检为假时不发占位，防"占位永远不收回"）
        if (!plugin.getConfigManager().getMainConfig()
                .getBoolean("ai-chat.feedback.ai-summary", true)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.hint"));
            return;
        }
        if (!aiChatManager.canRequestConversationSummary(player)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.hint"));
            return;
        }
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.feedback.ai-pending"));
        aiChatManager.requestConversationSummary(player, summary,
                (feedback, errorKey) -> handleSummaryResult(player, summary, feedback, errorKey,
                        localWrite));
    }

    /**
     * 下线路径：玩家已不在——只落本地行（不发消息、不请求 AI——人走且放弃语义概率高）。
     * 达标判定与 deliverEnd 同一表达式（summary.qualified）。
     * 调用方：{@code ChatScenarioListener.onPlayerQuit}（quit 时 Player 对象仍可得）。
     */
    public void persistOnQuit(Player player, AIChatManager.SessionSummary summary) {
        if (player == null || summary == null || !summary.qualified()) {
            return;
        }
        // plan §3.4 "四处真接线"之一（deliver / quit persist / GUI 物品 / AI 调用处）：
        // 总开关关闭时零落库，与 deliverEndMain 同门。
        if (!plugin.getConfigManager().getMainConfig()
                .getBoolean("ai-chat.feedback.enabled", true)) {
            return;
        }
        // 不用主线程（quit 路径玩家已离线，无显示面）——直接异步
        persistAsync(player.getUniqueId(), summary, "local", null);
    }

    /**
     * AI 点评结果回调（主线程，AIChatManager 保证）：成功 → 点评块 + 覆写 source=ai；
     * 失败 → ai-failed 一行（本地行留存）。
     *
     * @param localWrite deliverEndMain 的本地行异步写 future——AI 覆写严格排其后
     *                   （code tester 2 收口：防极端调度下 local 晚到覆写回 ai 行）
     */
    private void handleSummaryResult(Player player, AIChatManager.SessionSummary summary,
                                     ConversationFeedback feedback, String errorKey,
                                     CompletableFuture<Void> localWrite) {
        // R2-M3：玩家已开新会话 —— 上一场点评不显示（防串台）。仍照常落库：
        // 成功分支经 persistAfter 写 source=ai 行（数据不丢）；失败分支无库写，直接丢。
        // 判据：上一场会话在 endConversation 时已从 sessions 移除，此处若仍有活跃
        // 会话必然是新的（sessionId 必不同），等价实现"比对不匹配静默丢弃"。
        if (aiChatManager.hasActiveConversation(player)) {
            if (errorKey == null && feedback != null) {
                // R9：点评虽不显示（防串台），AI 花钱买来的纠错仍要回接学习闭环；
                // notify=false —— 玩家已在新对话里，不再弹"已加入错题本"提示。
                applyToLearningLoop(player, feedback, false);
                persistAfter(player.getUniqueId(), summary, localWrite,
                        "ai", GSON.toJson(feedback));
            }
            return;
        }
        if (errorKey != null || feedback == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.ai-failed"));
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.hint"));
            return;
        }
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.feedback.ai-header"));
        if (feedback.comment() != null && !feedback.comment().isEmpty()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.ai-comment",
                    "comment", feedback.comment()));
        }
        int maxShow = Math.max(0, plugin.getConfigManager().getMainConfig()
                .getInt("ai-chat.feedback.max-corrections-display", 3));
        if (!feedback.corrections().isEmpty() && maxShow > 0) {
            int shown = Math.min(maxShow, feedback.corrections().size());
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.ai-corrections-header",
                    "count", String.valueOf(shown)));
            for (int i = 0; i < shown; i++) {
                ConversationFeedback.Correction c = feedback.corrections().get(i);
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "dialogue.feedback.ai-correction-line",
                        "original", c.original(),
                        "corrected", c.corrected()));
            }
        }
        if (!feedback.words().isEmpty()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.ai-words",
                    "words", String.join(", ", feedback.words())));
        }

        // R9：点评已完整显示后，再把 corrections / 推荐词接回学习闭环
        // （落库/入本失败绝不影响上面的玩家可见点评——内部整体 try/catch）。
        applyToLearningLoop(player, feedback, true);

        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.feedback.hint"));

        // 覆写为 AI 行（source=ai + payload JSON；Gson 对 record 的 reflective 序列化
        // 自 2.10 起支持——项目依赖版本已满足）。经 localWrite future 串行化。
        persistAfter(player.getUniqueId(), summary, localWrite,
                "ai", GSON.toJson(feedback));
    }

    // ============================================================
    //  R9：AI 纠错回接学习闭环（corrections → 错题本 / 复习种子 / 下一场目标词）
    // ============================================================

    /**
     * 把一次 AI 点评的 {@code corrections} 与推荐词接回学习闭环。
     *
     * <p>语义（与 {@code WordLearningManager.trackWrongAnswer} 完全同款，不另立阈值）：
     * <ul>
     *   <li>corrections：仅"单词级"（单英文词、字母开头、≥2 字符、无空格）且存在于已加载
     *       词库的 {@code original} 才计数；{@code incrementWrongCount} 返回值 == 3 时
     *       {@code addDifficultWord}（错题本）；同场同词只计一次（Set 去重，防 AI 重复
     *       输出把一个词直接刷进错题本）；随后 {@code seedReview} 排入 1h 后复习。</li>
     *   <li>推荐词（{@code feedback.words()}）：存在于词库的才 {@code seedReview}
     *       （进复习/待学排程），<b>不</b>进错题本（推荐词不是错词）。</li>
     *   <li>两者合并为"下一场建议目标词"，存入本类内存 map 供
     *       {@link #getSuggestedFocusWords(UUID)} 消费。</li>
     * </ul>
     *
     * <p>线程：由 {@link #handleSummaryResult} 调用，AIChatManager 已保证主线程
     * （见其 {@code thenAcceptAsync(..., runnable -> scheduler.runTask(...))}），
     * 故此处 {@code player.sendMessage} 与 Bukkit API 均安全；PlayerDataStore 的缓存
     * 均为 ConcurrentHashMap，且 incrementWrongCount 内部自行把 DB 写切异步。
     *
     * <p>异常：整体 try/catch —— 落库/入本失败绝不阻断玩家可见的点评（主功能）。
     *
     * @param notify 是否向玩家补发"已加入错题本"提示（防串台分支静默落库时为 false）
     */
    private void applyToLearningLoop(Player player, ConversationFeedback feedback, boolean notify) {
        if (player == null || feedback == null) {
            return;
        }
        if (!player.isOnline()) {
            // 点评回调晚于 quit（deliverEnd 在途时玩家下线）：PlayerDataStore 已 unload，
            // 此时写回会重建"只增不删"的缓存条目且无人可见 → 整段跳过
            // （该场统计的本地行已由 quit 路径落库，不丢）。
            return;
        }
        try {
            UUID playerId = player.getUniqueId();
            PlayerDataStore store = plugin.getPlayerDataStore();
            WordManagerImpl wordManager = plugin.getWordManager();
            if (store == null || wordManager == null) {
                return; // 防御：DI 未完成（理论上不会）
            }
            if (!plugin.getConfigManager().isWordSystemEnabled()) {
                return; // 单词系统整体关闭：错题本/复习队列都不该被写入
            }

            // 下一场目标词（保序去重；只收词库内词，避免脏数据进 prompt）
            LinkedHashSet<String> focusWords = new LinkedHashSet<>();
            // 本场已计过错的词（同场去重：AI 重复输出同一条 correction 只算一次）
            Set<String> countedThisSession = new HashSet<>();
            boolean addedToWrongBook = false;

            List<ConversationFeedback.Correction> corrections = feedback.corrections() != null
                    ? feedback.corrections() : List.<ConversationFeedback.Correction>of();
            List<String> recommended = feedback.words() != null
                    ? feedback.words() : List.<String>of();

            for (ConversationFeedback.Correction correction : corrections) {
                if (correction == null) {
                    continue;
                }
                String original = normalizeFocusCandidate(correction.original());
                if (original == null) {
                    continue; // 非单词级（句子/短语/带标点）→ 不进单词错题本
                }
                if (!existsInWordBank(wordManager, original)) {
                    continue; // 错题本只收词库里的词
                }
                focusWords.add(original);
                if (!countedThisSession.add(original)) {
                    continue; // 同场重复 correction 只计一次
                }
                int wrongCount = store.incrementWrongCount(playerId, original);
                // 与 trackWrongAnswer 完全同款：恰好第 3 次才进错题本
                if (wrongCount == 3) {
                    store.addDifficultWord(playerId, original);
                    addedToWrongBook = true;
                }
                seedReview(playerId, original);
            }

            for (String rawWord : recommended) {
                String word = normalizeFocusCandidate(rawWord);
                if (word == null || !existsInWordBank(wordManager, word)) {
                    continue;
                }
                focusWords.add(word);
                seedReview(playerId, word); // 推荐词只排复习，不进错题本
            }

            if (!focusWords.isEmpty()) {
                // 不可变快照：消费方（未来 AIChatManager 注入点）不会与后续写入竞争
                suggestedFocusWords.put(playerId, List.copyOf(focusWords));
            }

            if (addedToWrongBook && notify) {
                // 复用既有键（不新增 i18n）：语义与 WordLearningManager 的"已加入错题本"一致
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "wrong-book.auto-add"));
            }
        } catch (Exception e) {
            // 静默降级：点评是玩家可见主功能，本段失败只记日志
            plugin.getLogger().warning("Failed to apply AI corrections to learning loop: "
                    + e.getMessage());
        }
    }

    /**
     * 复习种子（含 ReviewScheduler 的 30s due 缓存失效）——走 WordManager 侧入口，
     * 只调 PlayerDataStore.seedReview 会让复习提醒延迟至多 30s 才看到新种子。
     */
    private void seedReview(UUID playerId, String word) {
        WordManagerImpl wordManager = plugin.getWordManager();
        if (wordManager == null || wordManager.getReviewScheduler() == null) {
            plugin.getPlayerDataStore().seedReview(playerId, word);
            return;
        }
        wordManager.getReviewScheduler().seedReview(playerId, word);
    }

    /** 词库存在性（globalWordIndex 小写索引，getWord 内部已 toLowerCase）。 */
    private boolean existsInWordBank(WordManagerImpl wordManager, String word) {
        return wordManager.getWord(word) != null;
    }

    /**
     * 单词级候选归一化：字母开头、长度 ≥2、无空格、全英文（允许词内连字符/撇号），
     * 统一小写；不合格返回 {@code null}。句子级纠错（含空格/中文/标点/数字开头）返回 null。
     */
    private String normalizeFocusCandidate(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_FOCUS_WORD_CHARS
                || !WORD_LEVEL_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * R9：最近一次 AI 点评产出的"下一场建议目标词"（corrections 的 original 词 +
     * AI 推荐词，均已过滤到词库内、保序去重、小写）。
     *
     * <p>当前 AIChatManager 尚无"下一场目标词注入"API（无 focus/target 概念），
     * 本方法只完成数据准备，供未来接线（会话 system prompt 注入）。
     * 返回不可变快照，永不为 null；无数据时返回空列表。
     */
    public List<String> getSuggestedFocusWords(UUID playerId) {
        if (playerId == null) {
            return List.of();
        }
        List<String> words = suggestedFocusWords.get(playerId);
        return words != null ? words : List.of();
    }

    // ============================================================
    //  持久化与查询
    // ============================================================

    /**
     * 异步 UPSERT（isEnabled 守卫；异常吞掉记 WARNING——DB 层已记）。
     *
     * @return 写完成 future（异常已在内部吞掉，future 不会以异常完成）——
     *         供 AI 覆写经 {@link #persistAfter} 串行排在本地行之后
     */
    private CompletableFuture<Void> persistAsync(UUID playerId, AIChatManager.SessionSummary summary,
                                                 String source, String payloadJson) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(writeTask(playerId, summary, source, payloadJson));
    }

    /**
     * AI 覆写专用：经 {@code prerequisite}（本地行 future）串行——严格保证
     * source=local 先落、source=ai 后覆写（code tester 2 收口的顺序保证）。
     */
    private void persistAfter(UUID playerId, AIChatManager.SessionSummary summary,
                              CompletableFuture<Void> prerequisite,
                              String source, String payloadJson) {
        if (playerId == null) {
            return;
        }
        prerequisite.thenRunAsync(writeTask(playerId, summary, source, payloadJson));
    }

    /**
     * 落库任务工厂（D2-4：persistAsync/persistAfter 两路径逐字去重）：
     * isEnabled 守卫 + upsertChatFeedback 7 参 + WARNING 吞异常。
     *
     * <p>每次调用返回新 Runnable 实例（无状态，但保守起见不跨 future 复用同一实例）。
     */
    private Runnable writeTask(UUID playerId, AIChatManager.SessionSummary summary,
                               String source, String payloadJson) {
        return () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                plugin.getDatabaseManager().upsertChatFeedback(playerId, summary.sessionId(),
                        summary.scenarioKey(), summary.difficultyKey(),
                        summary.validInputs(), summary.aiReplyCount(),
                        (int) summary.durationSeconds(), source, payloadJson);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to persist chat feedback for "
                        + playerId + ": " + e.getMessage());
            }
        };
    }

    /** 读单行反馈（GUI 详情页用；有界索引查询——UNIQUE(player_uuid, session_id) 天然覆盖）。 */
    public ChatFeedbackEntry getFeedback(UUID playerId, String sessionId) {
        if (playerId == null || sessionId == null) {
            return null;
        }
        return plugin.getDatabaseManager().getChatFeedback(playerId, sessionId);
    }

    /**
     * quit 生命周期钩（清本类自持的 per-player 状态：R9 的"下一场建议目标词"）。
     * 由 {@code PlayerJoinListener.onPlayerQuit} 调用；卡在点评回调前下线时条目自然丢弃
     * （玩家已离线，下一场注入也无意义）。
     */
    public void unloadPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        suggestedFocusWords.remove(playerId);
    }

    // ============================================================
    //  展示辅助
    // ============================================================

    /** 场景展示名（本人语言）；缺失回落 key。 */
    private String scenarioDisplayName(Player player, String scenarioKey) {
        if (scenarioKey == null) {
            return "-";
        }
        return aiChatManager.getScenarioDisplayName(player, scenarioKey);
    }

    /** 难度展示名（本人语言）；缺失回落 key。 */
    private String difficultyDisplayName(Player player, String difficultyKey) {
        if (difficultyKey == null) {
            return "-";
        }
        AIChatManager.DifficultyConfig dc = aiChatManager.getDifficulties().get(difficultyKey);
        if (dc == null) {
            return difficultyKey;
        }
        boolean zh = "zh".equalsIgnoreCase(plugin.getPlayerLanguage(player));
        return dc.getDisplayName(zh);
    }

    /** 时长渲染：≥60s 走 minutes 子键，否则 seconds 子键（plan §2.3 注：两段都走 i18n）。 */
    private String formatDuration(Player player, long seconds) {
        if (seconds >= 60) {
            return plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.feedback.duration-minutes",
                    "minutes", String.valueOf(seconds / 60));
        }
        return plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.feedback.duration-seconds",
                "seconds", String.valueOf(Math.max(0, seconds)));
    }
}
