package com.letslearnenglish.minecraftplugin.core.ai;

import com.google.gson.Gson;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.ChatFeedbackEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "dialogue.feedback.hint"));

        // 覆写为 AI 行（source=ai + payload JSON；Gson 对 record 的 reflective 序列化
        // 自 2.10 起支持——项目依赖版本已满足）。经 localWrite future 串行化。
        persistAfter(player.getUniqueId(), summary, localWrite,
                "ai", GSON.toJson(feedback));
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
     * quit 生命周期钩（当前无自持 map——预留与 clearPendingSummary 同编排点对齐）。
     * 经死代码审计豁免（D2-3）：空实现系编排点形状保留，非遗漏。
     */
    public void unloadPlayer(UUID playerId) {
        // 本类当前无 per-player 状态；保留方法维持 quit 编排点形状一致（未来扩展点）。
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
