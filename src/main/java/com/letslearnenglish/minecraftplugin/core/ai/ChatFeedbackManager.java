package com.letslearnenglish.minecraftplugin.core.ai;

import com.google.gson.Gson;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.ReviewScheduler;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.ChatFeedbackEntry;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

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
 * HTTP 回调由 AIChatManager 保证主线程；落库一律异步（本类的 {@code accepting}/{@code draining}
 * 双闸守卫，见 {@link #writeTask}），关服前由 {@link #flushPending(long)} 排空。
 */
public class ChatFeedbackManager {

    /** Gson 无状态可复用（与 AIChatManager 同姿态；arch tester 6① 收口——避免每单 new）。 */
    private static final Gson GSON = new Gson();

    /** 每玩家"下一场建议目标词"（最近一次 AI 点评产出；去重保插入序）。 */
    private final Map<UUID, List<String>> suggestedFocusWords = new ConcurrentHashMap<>();

    // ── FIX-H1（RT-H2 中危·可观测性）：全服丢弃统计的节流聚合 ──────────────────
    //
    // 逐场丢弃情况原来只有 FINE 一条，而 Bukkit 默认日志级别是 INFO —— 运维在默认
    // logback 配置下<b>完全看不到</b>「AI 纠错被丢了多少」。改成每 N 场打一条 INFO 汇总：
    // 既不刷屏（一场一行 INFO 在高峰是全服刷屏），又让"纠错闭环在静默失效"有信号。
    //
    // 累加器是<b>全服共享</b>而非 per-player：语义是「运维看全服丢弃率」，per-player
    // 拆分只会让汇总更难读，还要额外的 Map 生命周期管理。计数取 ≤ 上限的自饱和值，
    // 累加到上限后不再增长（实际不可能触及，纯防溢出 + 防日志里出现负数的荒谬值）。
    /** 汇总日志间隔：每 N 场点评打一条 INFO（1 = 每场都打，用于临时排障）。 */
    private static final int SUMMARY_LOG_EVERY_N = 10;

    /** 单场各计数的累加上限（防 long 溢出；触及后停止累加，汇总行标 saturated）。 */
    private static final long COUNTER_SATURATION = Long.MAX_VALUE / 4;

    /** 已处理的点评场数（同时充当「该不该打汇总」的节流计数）。 */
    private final AtomicLong summaryLogCounter = new AtomicLong();
    private final AtomicLong totalApplied = new AtomicLong();
    private final AtomicLong totalReviewOnly = new AtomicLong();
    private final AtomicLong totalDroppedSentence = new AtomicLong();
    private final AtomicLong totalDroppedUnknown = new AtomicLong();

    private final LetsLearnEnglish plugin;
    private final AIChatManager aiChatManager;

    // ── L1（P1·关服丢数据）：在途落库 future 的登记表 + 收口闸 ──────────────────
    //
    // 为什么必须自持这份登记表：落库走 CompletableFuture.runAsync(...)，底层是
    // ForkJoinPool.commonPool —— <b>不受</b> Paper 的 waitForAsyncTasksShutdown() 管辖
    // （那个只等 BukkitScheduler 的异步任务）。关服时序是
    //   disablePlugins() → setEnabled(false) → onDisable() → waitForAsyncTasksShutdown()
    //   → playerList.saveAll() → removeAll()
    // 而 setEnabled(false) 在 onDisable() <b>之前</b>就写了字段，所以排队中的写任务一旦
    // 读到 isEnabled()==false 就静默 return，整条 chat_feedback 记录凭空消失 ——
    // 且这个丢失发生在「玩家已经看到点评」之后，事后无法察觉。
    //
    // 修法：登记 → onDisable 里 flushPending 排空 → 排空期间仍允许执行。
    /** 在途落库 future（提交时登记、完成时移除）；key 为 future 自身，值无意义。 */
    private final Set<CompletableFuture<Void>> pendingWrites = ConcurrentHashMap.newKeySet();

    /**
     * 是否还接受新的落库任务（L1）。{@code false} 之后 {@link #persistAsync} /
     * {@link #persistAfter} 一律不再登记 —— 这就是「onDisable 之后新提交被拒绝」
     * 的实现点（DB 已/即将关闭，再提交只会刷屏）。
     */
    private volatile boolean accepting = true;

    /**
     * 是否处于关服排空窗口（{@link #flushPending(long)} 期间为 {@code true}）。
     *
     * <p><b>为什么必须有这个字段，而不能只靠 {@code accepting}</b>：
     * 字面上「flush 时先置 accepting=false 再 join，writeTask 守卫改成
     * {@code if (!accepting) return;}」看起来成立，实际会得到一个<b>假绿</b>的修复 ——
     * 提交到 commonPool 但<b>尚未开始执行</b>的任务，一启动就读到 {@code accepting==false}
     * 并立刻 return，future 随即以「正常完成」结算，于是 flush 报告「已排空」而数据
     * 全丢。<b>排空窗口内必须允许执行已提交的任务</b>，所以判据拆成两半：
     * 「现在还接不接新活」（{@code accepting}，提交点判定）与「这条已登记的任务还能不能
     * 跑」（本窗口标记，执行点判定）。
     *
     * <p><b>写入顺序</b>：{@code flushPending} 必须先写本字段、再写 {@code accepting}
     * （两笔都在 {@link #submitLock} 里）。{@code writeTask} 先读 accepting 再读本字段，
     * 因此「读到 accepting 新值 false ⇒ 一定能读到本字段 true」，不会误杀已登记任务。
     */
    private volatile boolean draining = false;

    /**
     * 提交与「关闸」之间的互斥锁（L1）。
     *
     * <p>为什么光靠 {@code volatile} 不够：提交分两步 —— 先查 {@code accepting}、再
     * {@code runAsync}、再 {@code track}（登记）。若 flush 恰好插在「查过 accepting」
     * 与「登记完成」之间，它会看到空集合、立刻报「已排空」并退出排空窗口；随后那条
     * 任务才被登记，等它真正开始跑时 {@code accepting=false} 且 {@code draining=false}，
     * 直接被守卫丢弃 —— 排空报告成功、数据却丢了，正是本类要修的那个 bug 换了个位置。
     *
     * <p>因此提交侧的「判 accepting + 登记」与 flush 侧的「关闸」必须互斥：要么任务先登记好
     * （flush 一定能看到它），要么 flush 先关闸（提交被明确拒绝）。两者不可能交错。
     * 锁只在毫秒级临界区里持有（无 IO），对每场一次的落库路径没有可测开销。
     */
    private final Object submitLock = new Object();

    /**
     * 落库任务的执行器（L1 可测试性缝隙）。
     *
     * <p><b>生产值恒为 commonPool</b>（{@code CompletableFuture.runAsync(r, executor)} 的
     * 默认执行器就是它，显式传与不传完全等价）——这个字段不改变任何运行时行为，它存在的
     * 唯一目的是让测试能注入一个「提交了但不立刻执行」的执行器，从而构造出
     * {@link #draining} 唯一存在的那个场景：<b>任务已登记、但还没开始跑</b>。
     *
     * <p>为什么非要这个缝隙：{@code draining} 的作用是「排空窗口内放行已登记但未启动的
     * 任务」。若测试让任务先跑起来再 flush（现有测试就是这样），写线程早已越过守卫，
     * {@code draining} 取什么值都不影响结果 —— 于是删掉 {@code draining = true} 这一行，
     * 测试依然全绿。那是假绿，不是守卫。
     *
     * <p>包级可见 + 仅包级 setter：不污染公共 API。
     *
     * <p><b>C-P2-3（红队 C）：{@code volatile}</b>。生产路径从不调
     * {@link #setWriteExecutor}，本字段恒为 {@code commonPool}；volatile 只为杜绝
     * 「异步线程读到 null」的极端情形（构造期与注入期的可见性），零运行时代价。
     */
    private volatile java.util.concurrent.Executor writeExecutor = java.util.concurrent.ForkJoinPool.commonPool();

    public ChatFeedbackManager(LetsLearnEnglish plugin, AIChatManager aiChatManager) {
        this.plugin = plugin;
        this.aiChatManager = aiChatManager;
    }

    /** 仅供测试注入执行器；传 {@code null} 回落 commonPool。 */
    void setWriteExecutor(java.util.concurrent.Executor executor) {
        this.writeExecutor = (executor == null)
                ? java.util.concurrent.ForkJoinPool.commonPool()
                : executor;
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
     * <p>R9 收口：本方法只负责「执行」，映射决策全部下放到纯函数
     * {@link CorrectionPlanner#plan}（可单测、无 Bukkit 依赖）。执行语义：
     * <ul>
     *   <li>{@code countAsWrong}：与 {@code WordLearningManager.trackWrongAnswer} 完全同款 ——
     *       {@code incrementWrongCount} 返回值 == 3 时 {@code addDifficultWord}（错题本）；
     *       随后排复习。</li>
     *   <li>{@code reviewOnly}：只排复习，<b>不</b>计数、不进错题本（推荐词不是错词；
     *       句级纠错切出的已学词也没有"错次"语义）。</li>
     *   <li>两者合并为「下一场建议目标词」，存入本类内存 map 供
     *       {@link #getSuggestedFocusWords(UUID)} 消费。</li>
     * </ul>
     *
     * <p>词形变化（{@code goes} / {@code went} 之类）由 {@link WordFormResolver} 还原成词库
     * 原形后记账，还原失败的按未知词丢弃并计入 FINE 日志 —— 绝不猜、绝不往错题本写词库外
     * 的键。句级纠错不进错题本（错题本是单词库，收句子是脏数据），改为按词回收进复习池。
     * 已知限制：单字母词 {@code a} / {@code i} 因单词级正则要求 ≥2 字母而无法进入闭环。
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

            // R9：映射决策收口在纯函数里（词级/句级/词形/去重/丢弃计数一次算清）
            CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                    feedback.corrections(),
                    feedback.words(),
                    word -> existsInWordBank(wordManager, word),
                    word -> hasLearned(store, playerId, word),
                    CorrectionPlanner.DEFAULT_MAX_SENTENCE_WORDS);

            boolean addedToWrongBook = false;

            for (String word : plan.countAsWrong()) {
                int wrongCount = store.incrementWrongCount(playerId, word);
                // 与 trackWrongAnswer 完全同款：恰好第 3 次才进错题本
                if (wrongCount == 3) {
                    store.addDifficultWord(playerId, word);
                    addedToWrongBook = true;
                }
                // R9 裁决：真答错的词必须「重新排到最近的一次复习」—— 只调 seedReview 时，
                // 已经学过的词会被 containsKey 直接挡回（空操作），AI 纠错等于没接上闭环。
                scheduleReview(playerId, word, true);
            }

            for (String word : plan.reviewOnly()) {
                // 推荐词 / 句级纠错切出的词不是「答错事件」：只保证它在复习池里，
                // 已有进度的词不重置间隔（seedReview 空操作正是期望行为）。
                scheduleReview(playerId, word, false);
            }

            // 下一场目标词：语义<b>冻结</b>（B1 消费）—— 只收「纠错词 + AI 推荐词」，
            // 与既有实现的成员口径一致（区别仅在于词形变化现在归到原形）。
            // 句级纠错切出的词只进复习池、不进目标词：它们多半是玩家句中用对的词，
            // 掺进 prompt 会稀释"该重点练什么"的信号。故这里重算推荐词而<b>不</b>直接用
            // plan.reviewOnly()（后者混了句级回收结果，无法区分）。
            LinkedHashSet<String> focusWords = new LinkedHashSet<>(plan.countAsWrong());
            appendFocusWords(focusWords, feedback.words(), wordManager);
            // L5（P2·污染下一场 prompt）：<b>无论是否为空都要写</b>。
            //
            // 原实现是 `if (!focusWords.isEmpty()) put(...)`，看着像省内存的优化，实际是
            // 一个残留 bug：某场没有任何纠错词与推荐词时<b>不会</b>覆盖上一场的快照，
            // 于是「下一场目标词」仍是两场前甚至更早的值 —— 玩家在新一场看到的
            // TARGET WORDS 与刚结束的那场毫无关系，AI 被引导去练一批早已过时的词，
            // 闭环信号彻底失效。空场次必须把旧值<b>清掉</b>：
            // 语义是「这场没学到 / 没推荐任何词 → 下一场不该有针对性的目标词」。
            // 用 remove 而非 put(List.of()) 省一个空条目：消费侧
            // getSuggestedFocusWords 对 null 已返回 List.of()，语义等价但少一份
            // per-player 常驻内存。
            if (focusWords.isEmpty()) {
                suggestedFocusWords.remove(playerId);
            } else {
                // 不可变快照：消费方（B1 的 prompt 注入）不会与后续写入竞争
                suggestedFocusWords.put(playerId, List.copyOf(focusWords));
            }

            // 会话末一行 FINE：纠错落地情况可观测（丢弃原本完全静默，是 R9 的核心缺口之一）。
            // FIX-H1：保留逐场 FINE 给调试用，另加全服节流 INFO 汇总（见字段说明）——
            // 两者并存是刻意的：FINE 是"这一场发生了什么"，INFO 是"运维看不看得见"。
            plugin.getLogger().log(Level.FINE,
                    "AI corrections applied=" + plan.countAsWrong().size()
                            + " reviewOnly=" + plan.reviewOnly().size()
                            + " droppedSentence=" + plan.droppedSentence()
                            + " droppedUnknown=" + plan.droppedUnknown()
                            + " dup=" + plan.droppedDuplicate());
            logPeriodicSummary(plan);

            if (addedToWrongBook && notify) {
                // 复用既有键（不新增 i18n）：语义与 WordLearningManager 的"已加入错题本"一致
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "wrong-book.auto-add"));
            }
        } catch (Exception e) {
            // 静默降级：点评是玩家可见主功能，本段失败只记日志。
            // L2（P2·可观测性）：必须带异常对象 —— 原来只打 e.getMessage()，线上拿到的是
            // "null"（NPE 之类）或一句无栈的一句话，既不知道异常类型也不知道在哪一行，
            // 等于「有日志但无法定位」。Logger.log(Level, msg, Throwable) 会输出完整栈。
            plugin.getLogger().log(Level.WARNING,
                    "Failed to apply AI corrections to learning loop", e);
        }
    }

    /**
     * FIX-H1（RT-H2 中危·可观测性）：全服 AI 纠错落地情况的分级汇总日志。
     *
     * <p>为什么需要：{@link #applyToLearningLoop} 的逐场统计原本只打 {@code Level.FINE}，
     * 而 Bukkit 默认日志级别是 {@code INFO} —— 随包发出去的 logback 配置下运维根本看不到
     * 「AI 纠错丢弃了多少」，闭环静默失效（例如词形归一化突然大面积失配）没有任何信号。
     *
     * <p>为什么必须节流：本类是全服单例，一场一波流；高峰期每场一行 INFO 会把控制台
     * 刷满、把真正重要的告警挤走。折中是每 {@link #SUMMARY_LOG_EVERY_N} 场打一条汇总，
     * 丢掉的是"单场明细"（那仍由 FINE 覆盖），保住的是"趋势可见"。
     *
     * <p>口径：<b>窗口值</b>而非累计值 —— 打完一条就把四个累加器归零，所以文案里的
     * "last N sessions" 名副其实。用累计值配这个文案会让运维把「开服以来总共丢了多少」
     * 误读成「最近 N 场丢了多少」，进而在量级上做出错误判断（两者差着几个数量级）。
     *
     * <p>线程：调用点由 AIChatManager 保证在主线程，但累加器仍用 {@link AtomicLong}
     * 做无锁累加 —— 未来若换成异步调用点也不会给出撕裂的汇总值（窗口取用 getAndSet，
     * 两个并发汇总最多各自少算一半，不会重复计入）。
     *
     * @param plan 本场映射结果（不为 null；调用点已判空）
     */
    private void logPeriodicSummary(CorrectionPlanner.Plan plan) {
        accumulate(totalApplied, plan.countAsWrong().size());
        accumulate(totalReviewOnly, plan.reviewOnly().size());
        accumulate(totalDroppedSentence, plan.droppedSentence());
        accumulate(totalDroppedUnknown, plan.droppedUnknown());

        // 先累加再自增：第 N 场打完汇总时，本场已计入，窗口值名副其实。
        if (summaryLogCounter.incrementAndGet() % SUMMARY_LOG_EVERY_N != 0) {
            return;
        }
        // getAndSet(0)：取走本窗口值并开新窗口（窗口语义见方法头 "口径"）。
        long applied = totalApplied.getAndSet(0);
        long reviewOnly = totalReviewOnly.getAndSet(0);
        long droppedSentence = totalDroppedSentence.getAndSet(0);
        long droppedUnknown = totalDroppedUnknown.getAndSet(0);
        plugin.getLogger().info("AI corrections (last " + SUMMARY_LOG_EVERY_N + " sessions): applied="
                + applied + " reviewOnly=" + reviewOnly
                + " droppedSentence=" + droppedSentence
                + " droppedUnknown=" + droppedUnknown
                + saturatedSuffix(applied, reviewOnly, droppedSentence, droppedUnknown));
    }

    /** 单调累加 + 饱和保护（触及上限后停止增长，避免 long 回绕成负数写进日志）。 */
    private static void accumulate(AtomicLong counter, int delta) {
        if (delta <= 0) {
            return;
        }
        counter.updateAndGet(current -> current >= COUNTER_SATURATION
                ? COUNTER_SATURATION
                : Math.min(COUNTER_SATURATION, current + delta));
    }

    /**
     * 汇总行的饱和标记：只有本窗口某个计数真的触顶才追加，正常运行时零噪音。
     *
     * @param applied         本窗口纠错落地数
     * @param reviewOnly      本窗口只排复习数
     * @param droppedSentence 本窗口句级丢弃数
     * @param droppedUnknown  本窗口未知词丢弃数
     * @return 触顶时 {@code " (saturated)"}，否则空串
     */
    private static String saturatedSuffix(long applied, long reviewOnly,
                                          long droppedSentence, long droppedUnknown) {
        boolean saturated = applied >= COUNTER_SATURATION
                || reviewOnly >= COUNTER_SATURATION
                || droppedSentence >= COUNTER_SATURATION
                || droppedUnknown >= COUNTER_SATURATION;
        return saturated ? " (saturated)" : "";
    }

    /**
     * R9：按「是否真答错」选择复习排程入口。
     *
     * <p><b>裁决：真答错用 {@code seedReview + resetReview} 连调，其余用 {@code seedReview}。</b>
     * 理由（读实现后确认）：
     * <ul>
     *   <li>{@code PlayerDataStore.seedReview} 在 {@code lastReviewTime} 或 {@code reviewCount}
     *       任一已存在时<b>直接 return</b> —— 对练过的词是纯空操作。而 AI 纠错命中的词几乎
     *       全是「玩家在对话里用错的、多半已经学过」的词，只会 seedReview 等于整条闭环落空。</li>
     *   <li>{@code resetReview} 的语义是「把这词的间隔进度重置」：{@code reviewCount = 0} +
     *       {@code lastReviewTime = now}，因此下一次 due = {@code now + intervals[0]}（默认 1h），
     *       正好是「答错后尽快再考一次」的期望行为。它<b>不碰 mastery</b>（掌握度由
     *       {@code updateWordProgress} 单源维护），也不新建 mastery 行。</li>
     *   <li>反过来对 {@code reviewOnly}（推荐词 / 句级切出的已学词）用 {@code resetReview}
     *       是错的：推荐词不是错词，把玩家已有的复习进度打回 1h 会平白增加复习量。故仍走
     *       {@code seedReview}（已有进度不动，没有才种）。</li>
     * </ul>
     *
     * <p><b>FIX-H1（RT-H2 中危·数据丢失）：为什么先 seedReview 再 resetReview。</b>
     * 只调 {@code resetReview} 时会出现「重启后复习种子蒸发」：落盘路径
     * {@code savePlayerDataInternal} 只遍历 {@code wordMasteryCache}，而
     * {@code masteryMap.get(word) == null} 的词会被<b>跳过并留 FINE 日志</b>（它绝不拿
     * 0.0 覆盖 DB 真值）。AI 纠错里的「玩家没学过的词」正是这种没有 mastery 行的词 ——
     * {@code resetReview} 刻意不建 mastery 行，于是刚写的复习种子只在内存里活着，
     * 下次重启就没了，只剩错题本那一行。
     *
     * <p>{@code seedReview} 补的是这一行（{@code putIfAbsent(0.0)}，已有 mastery 则 no-op）。
     * 顺序论证（读 {@code PlayerDataStore:1409-1458} 实现）：
     * <ul>
     *   <li>对<b>已有复习记录</b>的词：{@code seedReview} 在 {@code reviewTimes.containsKey}
     *       处直接 return —— <b>不</b>重置 {@code reviewCount}、不写任何 map；随后
     *       {@code resetReview} 无条件覆盖为 {@code lastReviewTime=now / reviewCount=0}。
     *       结果与「只调 resetReview」逐值一致，零副作用。</li>
     *   <li>对<b>没有复习记录</b>的词：{@code seedReview} 写入 now/0 并补 mastery 行，
     *       {@code resetReview} 再写一遍同样的语义（now/0）—— 幂等覆盖，不是"两次不同状态"。</li>
     * </ul>
     * 两条路径都不存在「seedReview 的新种子被 resetReview 破坏」或反之的窗口；
     * 唯一区别是后者顺手把词挂进了 masteryMap，让 UPSERT 能带上复习种子。
     *
     * <p>风险说明：{@code resetReview} 会把「已复习到第 5 个间隔」的词打回第 0 个间隔。
     * 对真答错的词这是<b>期望</b>语义（与复习答错完全一致），且 AI 纠错的词频很低（≤5 条/场），
     * 不会造成间隔反复归零。
     *
     * @param asWrong true = 真答错（补 mastery 行 + 重置间隔到 1h 后）；false = 只保证在复习池里（不动已有进度）
     */
    private void scheduleReview(UUID playerId, String word, boolean asWrong) {
        WordManagerImpl wordManager = plugin.getWordManager();
        ReviewScheduler scheduler = wordManager != null ? wordManager.getReviewScheduler() : null;
        if (!asWrong) {
            if (scheduler != null) {
                scheduler.seedReview(playerId, word);
            } else {
                plugin.getPlayerDataStore().seedReview(playerId, word);
            }
            return;
        }
        // 真答错：两步都必须 —— seedReview 保证 mastery 行存在（否则 resetReview 写的
        // 种子落不了盘），resetReview 保证「答错 = 遗忘」语义（间隔打回 intervals[0]）。
        // 走同一个入口（scheduler 优先）而不是 scheduler.seedReview + store.resetReview 混用：
        // ReviewScheduler 两方法都顺带清 dueWordCache，混用会漏清一次缓存。
        if (scheduler != null) {
            scheduler.seedReview(playerId, word);
            scheduler.resetReview(playerId, word);
        } else {
            PlayerDataStore store = plugin.getPlayerDataStore();
            store.seedReview(playerId, word);
            store.resetReview(playerId, word);
        }
    }

    /** 词库存在性（globalWordIndex 小写索引，getWord 内部已 toLowerCase）。 */
    private boolean existsInWordBank(WordManagerImpl wordManager, String word) {
        return wordManager.getWord(word) != null;
    }

    /**
     * 「下一场目标词」追加 AI 推荐词（语义冻结：只有词库内、小写原形、保序去重）。
     *
     * <p>为什么不用 {@code plan.reviewOnly()}：那里混了句级纠错回收的已学词，无法区分
     * 「AI 主动推荐要练的词」与「玩家句子里顺带出现的词」。目标词只该是前者。
     * 词形变化（{@code desserts}）同样归到原形（{@code dessert}）。
     *
     * <p>单词级归一化走 {@link CorrectionPlanner#normalizeWordLevel(String)}（单一真相）。
     * 已知限制：单字母词 {@code a} / {@code i} 不匹配（正则要求 ≥2 字母），无法进入闭环。
     */
    private void appendFocusWords(LinkedHashSet<String> focusWords, List<String> recommended,
                                  WordManagerImpl wordManager) {
        if (recommended == null) {
            return;
        }
        for (String raw : recommended) {
            String wordLevel = CorrectionPlanner.normalizeWordLevel(raw);
            if (wordLevel == null) {
                continue;
            }
            String lemma = WordFormResolver.resolve(wordLevel, word -> existsInWordBank(wordManager, word));
            if (lemma != null) {
                focusWords.add(lemma);
            }
        }
    }

    /**
     * 玩家是否「学过」某词（R9 句级纠错回收的过滤门：未学过的词排进复习，玩家答不出）。
     *
     * <p>{@code PlayerDataStore.wordMasteryCache} 是私有字段，此处用其两个公开只读视图的
     * 等价判据：{@code lastReviewTimes} 与 {@code reviewCounts} 的 key 集合覆盖了所有写入过
     * 学习进度的词 —— {@code seedReview}（普通会话结算 / 推荐词）/ {@code recordReview}
     * （复习答对）/ {@code resetReview}（复习答错）都会写这两个 map，而
     * {@code updateWordProgress} 建 mastery 行的同时 {@code endSession} 也必写复习种子。
     * 两视图均为 {@code unmodifiableMap}（只读，不会误改缓存）。
     *
     * <p>取并集而非单边：{@code seedReview} 只在两 map 均无该词时才写入，理论上存在
     * 「只有 reviewCount 无 lastReviewTime」的中间态，单边判断会漏。
     */
    private boolean hasLearned(PlayerDataStore store, UUID playerId, String word) {
        if (store == null || playerId == null || word == null) {
            return false;
        }
        return store.getLastReviewTimes(playerId).containsKey(word)
                || store.getReviewCounts(playerId).containsKey(word);
    }

    /**
     * R9：最近一次 AI 点评产出的"下一场建议目标词"（corrections 的 original 词 +
     * AI 推荐词，均已过滤到词库内、保序去重、小写）。
     *
     * <p>B1（R12）已接线：{@code LetsLearnEnglish} 在 DI 阶段调用
     * {@code AIChatManager.setFocusWordProvider} 把本方法注册为目标词数据源
     * （{@code LetsLearnEnglish} 约 :179），消费点是 {@code AIChatManager.collectFocusWords}
     * → {@code PromptComposer} 的 system prompt 注入。取数在主线程同步执行，因此本方法
     * 只读 {@code ConcurrentHashMap}，零 DB、零锁。
     *
     * <p>返回语义冻结：词库内小写原形、保序去重、不可变快照、永不为 null（无数据时空列表）。
     * 若返回空表，{@code AIChatManager} <b>默认</b>就到此为止（不再注入任何目标词）；
     * 只有服主显式打开 {@code ai-chat.focus-words-from-review}（默认 false）时，
     * 接线处才会回落到艾宾浩斯到期复习词（见 {@code LetsLearnEnglish} 的 provider 接线）。
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
     * 异步 UPSERT（accepting/draining 双闸守卫；异常吞掉记 WARNING——DB 层已记）。
     *
     * <p>L1：future 先登记进 {@link #pendingWrites} 再返回，完成（正常/异常/取消）时移除。
     * {@code whenComplete} 在<b>同一个任务线程</b>上跑，不会再排一个 task，因此不会
     * 引入额外的调度开销，也不会在 DB 已关时多一次异常。
     *
     * @return 写完成 future（异常已在内部吞掉，future 不会以异常完成）——
     *         供 AI 覆写经 {@link #persistAfter} 串行排在本地行之后
     */
    private CompletableFuture<Void> persistAsync(UUID playerId, AIChatManager.SessionSummary summary,
                                                 String source, String payloadJson) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(null);
        }
        // 提交与 track 必须在同一临界区里完成（见 submitLock）：否则 flush 可能插在
        // 「runAsync 已发起、track 还没执行」之间，看到空集合就报「已排空」并退出排空窗口，
        // 那条任务随后才被登记、执行时撞上 accepting=false 被丢 —— 排空报告成功、数据丢失。
        // 这里刻意<b>不</b>用 plugin.isEnabled() 判 —— 那是 L1 要修的 bug 本体（见 writeTask）。
        synchronized (submitLock) {
            if (!accepting) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(writeTask(playerId, summary, source, payloadJson),
                            writeExecutor);
            pendingWrites.add(future);
            // whenComplete 在任务线程上跑，注册本身不阻塞；以 future 自身为 key 精确移除。
            future.whenComplete((ignored, error) -> pendingWrites.remove(future));
            return future;
        }
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
        // 与 persistAsync 同一条纪律：判 accepting + 挂链 + 登记 在同一临界区。
        // AI 覆写是最容易丢的那一笔（它总排在 local 写之后，关服时正好卡在队列尾部），
        // 所以派生 future 也必须登记 —— 只登记 prerequisite 会漏掉它。
        synchronized (submitLock) {
            if (!accepting) {
                return;
            }
            CompletableFuture<Void> chained = prerequisite.thenRunAsync(
                    writeTask(playerId, summary, source, payloadJson), writeExecutor);
            pendingWrites.add(chained);
            chained.whenComplete((ignored, error) -> pendingWrites.remove(chained));
        }
    }

    /**
     * L1（P1·关服丢数据）：排空所有在途落库任务，供 {@code LetsLearnEnglish.onDisable}
     * 在关闭 DatabaseManager <b>之前</b>调用。
     *
     * <p>为什么需要：{@link #persistAsync} 走 {@code ForkJoinPool.commonPool}，
     * 而 Paper 的 {@code waitForAsyncTasksShutdown()} 只等 BukkitScheduler 派发的异步任务
     * —— commonPool 上的写任务不在它的管辖内。关服时若不等，这些写要么被
     * {@code plugin.isEnabled()} 守卫丢掉（本类原来的行为），要么撞上已关闭的连接。
     *
     * <p>语义细节：
     * <ul>
     *   <li><b>先开执行闸、再关提交闸</b>：{@code accepting = false} 必须在 join 之前（否则
     *       「排空期间又有新任务提交」会让等待永远追不上）；{@code draining = true} 也必须在
     *       join 之前，<b>且要写在 {@code accepting} 之前</b>，否则排队中的任务一启动就自杀，
     *       排空变成一场空跑（顺序论证见 {@link #draining} 与 {@link #flushPending}）。</li>
     *   <li><b>幂等</b>：重复调用直接返回 true（已排空 / 本来就没有在途任务）——
     *       第二次进来时 {@code pendingWrites} 已空，直接短路。</li>
     *   <li><b>超时不抛异常</b>：onDisable 已经没有上层可补救，抛出去只会截断后续的
     *       关库流程。超时记一条 WARNING（带剩余条数）后返回 false，让调用方知情但不中断。</li>
     *   <li><b>不吞 InterruptedException 的中断标志</b>：恢复后返回 false —— 被中断说明
     *       服务器在催，继续等没有意义。</li>
     * </ul>
     *
     * <p><b>C-P2-4（红队 C）调用方约束</b>：本方法会重新写 {@code draining = true} /
     * {@code accepting = false} —— 也就是说<b>第二次调用会重新开启排空窗口</b>。
     * 若第一次调用后 DB 已被关闭，第二次排空出来的写任务将落在一个已关闭的连接上。
     * 因此<b>调用方必须保证第二次调用时 DB 尚未关闭</b>（当前唯一调用点
     * {@code LetsLearnEnglish.onDisable} 位于关库之前，满足该约束）。
     *
     * @param timeoutMillis 最长等待毫秒；{@code <= 0} 视为不等（直接关闸返回是否已空）
     * @return true = 所有在途任务已完成（或本就无在途）；false = 超时/被中断仍有残余
     */
    public boolean flushPending(long timeoutMillis) {
        // 提交侧的「判 accepting + 登记」与这里的「关闸」互斥：见 submitLock 的说明。
        // 临界区只有两次 volatile 写，不涉及等待。
        synchronized (submitLock) {
            // 执行闸<b>先</b>开、提交闸<b>后</b>关 —— 顺序不能反，这是一个真实的并发竞态：
            //
            // writeTask 的判据是 `!accepting && !draining`（先读 accepting、再读 draining）。
            // 若先写 accepting=false 再写 draining=true，中间存在一个窗口：写线程读到
            // 「accepting=false（新值）+ draining=false（旧值）」，于是直接 return ——
            // 任务以「正常完成」结算，flush 报「已排空」，数据却丢了，恰好是本类要修的那个
            // bug 换了个位置复现。
            //
            // 反过来写（先 draining=true）则安全：若写线程读到 accepting 的<b>新值</b> false，
            // 按 JMM 的 synchronization order，accepting=false 的写排在 draining=true 的写之后，
            // 那么它随后对 draining 的读必然看到 true → 放行。若读到 accepting 的旧值 true，
            // 短路后直接放行。两条路径都不会误杀已登记任务。
            draining = true;
            // 提交闸：排空期间不再收新活（否则等待可能永远追不上提交）。
            accepting = false;
        }
        try {
            if (pendingWrites.isEmpty()) {
                return true;
            }
            if (timeoutMillis <= 0) {
                return pendingWrites.isEmpty();
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            // 循环而不是「一次 allOf(...).get(timeout)」：pendingWrites 是<b>成员会变</b>的活容器 ——
            // 每轮取快照等它，等完再看集合是否真空。这样既覆盖「等待期间又派生出的任务」
            // （例如 persistAfter 的链式覆写），也不会因为某个任务晚到 1ms 就漏掉它。
            // 每轮都等「剩余预算」而不是固定切片：总耗时严格被 deadline 约束。
            while (!pendingWrites.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                CompletableFuture<?>[] snapshot = pendingWrites.toArray(new CompletableFuture<?>[0]);
                try {
                    CompletableFuture.allOf(snapshot)
                            .get(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)),
                                    TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // 本轮快照耗尽预算：回到 while 顶部由 isEmpty 定夺（超时瞬间刚好完成的
                    // 任务因此仍会被看到，不会误报「有残余」）。
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 保留中断标志，别吞
                    return false;
                } catch (ExecutionException e) {
                    // writeTask 内部已吞异常，future 理论上不会以异常完成；真出现也只当作
                    // 「这一批没等到」，由下面的 isEmpty 给最终结论，不在此处刷日志。
                    break;
                }
                // allOf 返回后，本轮快照里的 whenComplete（负责从集合里移除条目）可能还没跑完
                // —— 这是 CompletableFuture 的既定语义，不是 bug。此时若直接再取快照会对
                // 已完成的 future 立即返回、形成忙等，故让出 CPU 1ms 再看一次。
                if (!pendingWrites.isEmpty()) {
                    java.util.concurrent.locks.LockSupport.parkNanos(
                            TimeUnit.MILLISECONDS.toNanos(1L));
                }
            }
            int pending = pendingWrites.size();
            if (pending > 0) {
                // 超时不是 bug，是「DB 太慢 / 任务太多」的运维信号：记 WARNING 并带上剩余条数，
                // 便于服主判断要不要调大 timeout。绝不抛异常 —— onDisable 没有上层可补救。
                // logger 双重判空：本方法可能在 DI 半初始化时被调用，日志失败不能反过来打断关服。
                java.util.logging.Logger logger = plugin != null ? plugin.getLogger() : null;
                if (logger != null) {
                    logger.warning("Chat feedback flush timed out after "
                            + timeoutMillis + " ms with " + pending
                            + " write(s) still pending; they may be lost at shutdown.");
                }
                return false;
            }
            return true;
        } finally {
            // 排空窗口结束：之后到 DB 关闭之间的残余任务一律拒绝（DB 已不可用，
            // 放行只会换来一屏 "database is closed"）。这里<b>不</b>重置 accepting ——
            // 关服是单向的，flush 之后本类不该再收任何新活。
            draining = false;
        }
    }

    /** 在途落库任务数（诊断/测试用；稳态为 0）。 */
    int pendingWriteCount() {
        return pendingWrites.size();
    }

    /**
     * 落库任务工厂（D2-4：persistAsync/persistAfter 两路径逐字去重）：
     * accepting 守卫 + upsertChatFeedback 7 参 + WARNING 吞异常。
     *
     * <p>每次调用返回新 Runnable 实例（无状态，但保守起见不跨 future 复用同一实例）。
     */
    private Runnable writeTask(UUID playerId, AIChatManager.SessionSummary summary,
                               String source, String payloadJson) {
        return () -> {
            // L1：守卫从 plugin.isEnabled() 改为「本类自己的两个闸」。
            //
            // 原守卫的问题：Bukkit 在调 onDisable() <b>之前</b>就把 enabled 字段写成
            // false，所以 onDisable → flush 这段窗口里所有排队中的写都读到 false 而
            // 静默 return —— 恰恰是「玩家看到点评之后、关服之前」那一小段最容易丢数据的
            // 时间。现在：
            //   · accepting == false 且不在排空窗口 → 拒绝（onDisable 之后的新任务，DB 已关）；
            //   · 排空窗口内（draining）→ 放行已登记的任务，让 flush 真的能排空；
            //   · 常态 → 照常执行（即使 isEnabled() 已是 false）。
            if (!accepting && !draining) {
                return;
            }
            try {
                plugin.getDatabaseManager().upsertChatFeedback(playerId, summary.sessionId(),
                        summary.scenarioKey(), summary.difficultyKey(),
                        summary.validInputs(), summary.aiReplyCount(),
                        (int) summary.durationSeconds(), source, payloadJson);
            } catch (Exception e) {
                // L2：带异常对象，保留类型与堆栈（原来只有 getMessage()）。
                plugin.getLogger().log(Level.WARNING,
                        "Failed to persist chat feedback for " + playerId, e);
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
