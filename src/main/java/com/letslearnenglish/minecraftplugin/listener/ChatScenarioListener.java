package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 聊天框情景对话监听器
 *
 * 当玩家通过 /le dialogue 进入 AI 情景对话模式后：
 * - 普通消息自动发送给 AI 进行对话（不广播到公屏）
 * - 以 "& " 或 "&" 开头的消息正常发送到公共聊天
 * - 输入 "bye" / "再见" 等退出对话模式
 */
public class ChatScenarioListener implements Listener {

    private static final String PUBLIC_PREFIX = "& ";
    private static final String PUBLIC_PREFIX_ALT = "&";

    private final LetsLearnEnglish plugin;
    private final AIChatManager aiChatManager;

    /**
     * 正在 AI 对话模式中的玩家。
     *
     * <p>R2-L3 漂移契约（死代码/卫生审计记录）：本集合与 {@code AIChatManager.sessions}
     * 成对写入——进入：{@code LearnCommand:349} / {@code ScenarioSelectListener:153}
     * 两处 {@link #addPlayer}；退出：{@link #onPlayerQuit} 与
     * {@link #handleEndConversation} 两处 remove。任何新增写入点必须同时维护另一侧；
     * 若未来写入点数量超过阈值，改用 {@code aiChatManager.hasActiveConversation(player)}
     * 派生。现全部入口均成对，无漂移路径（本契约即为审计处置：不做派生重构）。
     */
    private final Set<UUID> activePlayers = Collections.synchronizedSet(new HashSet<>());

    public ChatScenarioListener(LetsLearnEnglish plugin, AIChatManager aiChatManager) {
        this.plugin = plugin;
        this.aiChatManager = aiChatManager;
    }

    public void addPlayer(Player player) {
        activePlayers.add(player.getUniqueId());
    }

    /**
     * 命令退出路径（{@code /le dialogue} 二次执行）：与聊天 bye 同流程 ——
     * 委托 {@link #handleEndConversation}（ended → 小结卡/AI 点评 → return-menu）。
     * PF-FB-01 去重：调用方（LearnCommand）不再自行补发 ended 文案。
     * 注意：本方法须在主线程调用（LearnCommand 命令线程即主线程）。
     */
    public void removePlayer(Player player) {
        handleEndConversation(player);
    }

    public boolean isInScenarioMode(Player player) {
        return activePlayers.contains(player.getUniqueId());
    }

    /*
     * F-16-05 实读校正（先读本段，再读下方 javadoc）。
     *
     * 下方 javadoc 里「ChatAnswerListener 随后看到 isCancelled() == true 便不再判分」一句与实际
     * 代码不符，照它理解优先级会把结论记反。以下为逐条实读（行号为撰写时的坐标，按符号 grep 为准）：
     *
     * 1) 全仓挂在 AsyncPlayerChatEvent 上的 handler 只有两个，没有第三个：ChatAnswerListener
     *    #onPlayerChat（ChatAnswerListener.java:141-142，LOWEST）与本方法（HIGH）。
     *    GlobalGuiActionGuardListener 的两个 LOWEST 挂在 InventoryClick / InventoryDrag
     *    （:141、:201），DuelListener 的两条 MONITOR 挂在 PlayerQuitEvent / PlayerJoinEvent
     *    （:60、:100）—— 这四者都不参与聊天分派。
     *
     * 2) 不能靠注册顺序：LetsLearnEnglish#registerListeners 里本类在 :964 注册，
     *    ChatAnswerListener 在 :966-967 注册 —— 本类先注册。Bukkit 对同优先级按注册先后调用，
     *    所以「两处都写 LOWEST」时先跑的是本类，恰好是最坏的次序。让本方法晚于那个 LOWEST
     *    只能靠优先级本身（HIGH > LOWEST）：这是 HIGH 唯一的作用，也是它不能写成 LOWEST 的原因。
     *
     * 3) 两条链的实际方向（不要假设它们互为前提）：
     *    - 本类这侧：方法体开头的 isCancelled() 早退要求消费方先 cancel，这条链成立。
     *    - ChatAnswerListener 那侧：方法体里没有任何 isCancelled() 读取，也没有
     *      ignoreCancelled = true（该文件全文无此参数）。它不因事件已被取消而让路 ——
     *      若轮到本类先 cancel，它照样判分，同一条消息会被消费两次。所以两者是各自独立消费，
     *      不是「一个取消、一个让路」的握手。
     *
     * 4) 顺序被改坏时到底坏在哪：把本方法降到 LOWEST，今天观察不到「答题输入被当成场景聊天
     *    发给 AI」，也观察不到「同一句话既发 AI 又被判分」—— 挡在前面的是 isCancelled() 之后的
     *    R-66 复核：hasActiveQuiz（WordLearningManager:196，即 quizStates.containsKey）比
     *    ChatAnswerListener 的 isAnswering 门（:584、:202）更宽，isPracticingWord（:886）与
     *    isInDuel（DuelManager:1525，实为 isActiveDuel：room != null && room.isActive()）与
     *    它的另两个门完全一致。也就是说 R-66 的覆盖集 ⊇ 消费方的消费集，默认分支
     *    （setCancelled(true) → 回显 dialogue.you-label → 预检 canSendChat 通过再回显
     *    dialogue.ai-typing → chatAsync）根本走不到；下方 javadoc 描述的那条退化链，只在
     *    R-66 也不再成立时才会发生。但「今天观察不到」不等于「可以调」：调低一次，双保险变
     *    单保险，而 R-66 被削弱的后果在现有单测里不可观测 —— 本仓 src/test 下没有任何一个
     *    测试构造这两个 listener（grep ChatScenarioListener / ChatAnswerListener 在 src/test
     *    零命中），要验证分派次序得先搭一套事件总线夹具。保留 HIGH 的收益，是让「这条消息
     *    已被别的策略消费」由事件层显式表达，让 R-66 只承担它本该承担的竞态窗口，
     *    而不是被迫兼任主保证。
     *
     * 5) 两层各自防什么、为什么都不能删：
     *    - HIGH（事件层）：保证本方法排在消费型 LOWEST 之后。降为同档＝次序完全交给注册先后，
     *      而当前的注册先后正好是本类在前。
     *    - R-66（状态层）：防 cancel 与状态判定之间的竞态窗口（消息进入分派时尚无活跃会话、
     *      判分前一刻会话才建立之类），这个窗口事件层看不见。
     *    - 上调到 HIGHEST / MONITOR 无收益：本方法已是聊天事件上最晚的消费点，没有更晚的
     *      handler 需要它让路；且本方法会改 event，放到 MONITOR 与「只观察不改」的惯例相反。
     */
    /**
     * F-16-05：为什么聊天分流必须是 {@link EventPriority#HIGH}。
     *
     * <p><b>它依赖一个跨文件的优先级条件，此前只写在方法体里</b>：本方法开头
     * {@code if (event.isCancelled()) return;} 的让路语义，要求<b>消费型</b>
     * {@link ChatAnswerListener#onPlayerChat}（{@code EventPriority.LOWEST}：答题 / 错题练习
     * / 决斗抢答，命中即 {@code setCancelled(true)} + {@code getRecipients().clear()}）先跑。
     * Bukkit 按优先级从低到高调用（{@code LOWEST → LOW → NORMAL → HIGH → HIGHEST → MONITOR}），
     * {@code HIGH} 保证本方法严格晚于那个 {@code LOWEST}。全仓 {@code AsyncPlayerChatEvent}
     * 的处理点只有这两处（本类与 {@code ChatAnswerListener}），同处 {@code LOWEST} 的
     * {@code GlobalGuiActionGuardListener} 只守 GUI 点击、与聊天事件无关。
     *
     * <p><b>为什么不靠注册顺序</b>：{@code registerEvents} 的先后只决定同优先级内的调用
     * 次序，且本类在 {@code LetsLearnEnglish#registerListeners} 里注册在
     * {@code ChatAnswerListener} <b>之前</b> —— 若两者同优先级，今天就是本类先跑，
     * 恰好是本注释描述的坏顺序。跨优先级是本方法唯一的正确性依据。
     *
     * <p><b>顺序被改动会怎样</b>：若本方法被下调到 {@code LOWEST} 或任何低于 {@code HIGH}
     * 且不晚于 {@code ChatAnswerListener} 的档位，答题玩家的输入会先被本方法当成场景聊天：
     * 走到默认分支后 {@code event.setCancelled(true)}、回显 {@code dialogue.you-label}
     * （预检 {@code canSendChat} 通过时再回显 {@code dialogue.ai-typing}）、最后把这句
     * 答案经 {@code aiChatManager.chatAsync} 发给 AI 会话 —— 而 {@code ChatAnswerListener}
     * 随后看到 {@code isCancelled() == true} 便不再判分，玩家的答案既没被计分、又被当成
     * 对话发给了 AI，屏幕上还多出两句风马牛不相及的提示。
     * 上调到 {@code HIGHEST} / {@code MONITOR} 则方向相反且无收益：本类已是最晚的消费点，
     * 没有更晚的 handler 需要它让路。
     *
     * <p><b>方法体里的 R-66 复核不是这条契约的替代品</b>：{@code onPlayerChat} 内紧接着
     * {@code isCancelled()} 早退之后的那段纵深防御（直接查 quiz / practice / duel 三个
     * 状态位）防的是 cancel 与状态判定的竞态窗口；主保证仍是「{@code HIGH} 晚于
     * {@code ChatAnswerListener} 的 {@code LOWEST} 消费」。两者都不可删：去掉优先级，
     * 正常路径就从「事件被取消、本方法早退」退化成「本方法先吞输入」；去掉状态复核，
     * 竞态窗口内的输入仍会漏进 AI 会话。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // 如果事件已被其他监听器取消（如 ChatAnswerListener 答题模式），不再处理
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        if (!activePlayers.contains(player.getUniqueId())) {
            return; // 不是对话模式，正常聊天
        }

        // R-66 纵深防御：答题/错题练习/决斗优先 —— 玩家在答题、错题练习或决斗中时，
        // 对话侧不处理该消息（正常流 ChatAnswerListener 已先 cancel 事件使其不可达；
        // 此处防竞态窗口。不 cancel、不提示、不消费 —— 让给答题/练习/决斗路径处理）。
        if (plugin.getWordLearningManager().hasActiveQuiz(player.getUniqueId())
                || plugin.getWordLearningManager().isPracticingWord(player.getUniqueId())
                || (plugin.getDuelManager() != null
                        && plugin.getDuelManager().isInDuel(player.getUniqueId()))) {
            return;
        }

        // MC-2（S-H）：ai-chat.enabled=false 时进行中的对话自动退出 —— 下一条消息
        // 收到 ai-chat.module-disabled 提示并结束对话模式。消息不转公屏
        // （event.setCancelled(true)，与 bye 同口径，避免"输入被吞"）。
        // 提示+结束切主线程（与 bye 分支同理：endConversation 链上可达 sendMessage /
        // 成就通知 / 每日挑战记分，Bukkit API 不得在异步线程直调）。
        // 位置在 R-66 之后：答题/决斗中的消息仍优先让给对应路径处理。
        if (!plugin.getConfigManager().isAIChatEnabled()) {
            event.setCancelled(true);
            // R-16：setCancelled(true) 只挡住 Bukkit 的默认投递路径；getRecipients() 是其他插件
            // （聊天管理、记录/日志、聊天过滤、分析插件）在**同一个事件**上读的收件人列表 ——
            // 只要它们不看 isCancelled，玩家这一行私聊 AI 的输入照样被当公开聊天记下来。
            // 本类三个 cancel 点（本处 / bye / 默认发 AI）内容全属对话内私有输入，故一律清空，
            // 与 ChatAnswerListener 三条消费分支同口径（该文件的注释已把「cancel + clear」
            // 并列为既定契约，本类此前只 cancel 不清空，正是文档与代码不符的那一侧）。
            event.getRecipients().clear();
            Bukkit.getScheduler().runTask(plugin, () -> {
                // slice-16-concurrency::03（round1 遗留 MEDIUM，round10 裁决修）：
                // 原实现此处是 `if (!activePlayers.contains(...)) return;` ——
                // 而上面 `setCancelled(true)` 是**不可回滚**的异步副作用（1 tick 后
                // `setCancelled(false)` 是空操作），于是「已不在会话中」的玩家
                // **无任何提示地丢了一条输入**。
                //
                // 修法（expert4 提出，比主线程初版更干净）：**删掉这个守卫**，而不是
                // 在它旁边再补一条重复的提示。因为 `handleEndConversation` **本身幂等** ——
                // `:305` 的 `if (!activePlayers.remove(...)) return;` 就是同一个守卫。
                // 外面这层是它的复制品，删掉后：
                //   · 玩家总能收到「AI 对话已被关闭」这句（吞输入 → 有提示，缺陷闭合）
                //   · 已退出过的玩家不会重复结算（由 handleEndConversation 内部早退兜住）
                //   · 净减 3 行
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "ai-chat.module-disabled"));
                handleEndConversation(player);
            });
            return;
        }

        String input = event.getMessage().trim();

        // 退出指令
        if (input.equalsIgnoreCase("bye") || input.equalsIgnoreCase("goodbye")
                || input.equalsIgnoreCase("end") || input.equals("再见") || input.equals("拜拜")) {
            event.setCancelled(true);
            // R-16：bye/goodbye/end/再见/拜拜 是退出令牌，不是聊天内容 —— 清空收件人，理由同上
            // （此前的疏漏点之一）。清空放在切主线程之前：收件人列表属事件对象，异步改它
            // 与默认投递路径之间无从调度器取得时序，故必须在本次异步回调内就地清掉；
            // 而 event.setMessage 的改写会把令牌原样带进公屏，本分支不碰 message —— 只 cancel + clear。
            event.getRecipients().clear();
            // PF-ACH-02（Phase 3 规划期发现，编排者即修）：本监听器运行在
            // AsyncPlayerChatEvent 的异步线程，endConversation 链上可达
            // tryUnlock 通知段（player.sendMessage / playSound）与每日挑战的
            // 记分逻辑——Bukkit API 不得在异步线程直调。切主线程执行。
            org.bukkit.Bukkit.getScheduler().runTask(plugin,
                    () -> handleEndConversation(player));
            return;
        }

        // 检查是否以 "& " 或 "&" 开头（公屏聊天）
        String publicMsg = null;
        if (input.startsWith(PUBLIC_PREFIX)) {
            publicMsg = input.substring(PUBLIC_PREFIX.length()).trim();
        } else if (input.startsWith(PUBLIC_PREFIX_ALT) && input.length() > 1 && input.charAt(1) != ' ') {
            publicMsg = input.substring(1).trim();
        }

        if (publicMsg != null && !publicMsg.isEmpty()) {
            // 公屏聊天 — 修改消息去掉 & 前缀后广播
            event.setMessage(publicMsg);
            // 不 cancel，让消息正常广播
            return;
        }

        // 默认：发送给 AI
        event.setCancelled(true);
        // R-16：本处泄漏面最严重 —— 玩家私聊 AI 的原句（含其输出标签对话）会被任何读
        // getRecipients() 的第三方插件看见。清空后 setCancelled + clear 与
        // ChatAnswerListener 的答题分支完全对称；上面「公屏聊天」分支刻意不清空（那条要广播）。
        event.getRecipients().clear();
        String youLabel = getMessageLine(player, "dialogue.you-label");
        broadcastToPlayer(player, youLabel + input);

        if (!aiChatManager.isAvailable()) {
            String aiUnavailable = getMessageLine(player, "dialogue.ai-unavailable");
            broadcastToPlayer(player, aiUnavailable);
            return;
        }

        // F1-08：先只读预检（每日轮次上限 / 冷却）——未通过时不显示"AI 正在输入"，
        // 交给 chatAsync 的正式检查给出准确拒绝提示（预检与正式检查间的时间差属提示语义级）。
        if (aiChatManager.canSendChat(player)) {
            String aiTyping = getMessageLine(player, "dialogue.ai-typing");
            broadcastToPlayer(player, aiTyping);
        }

        aiChatManager.chatAsync(player, input, (aiResponse, errorMsg) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                // slice-16-concurrency-01（round1 遗留 HIGH，本轮补修）：迟到回调守卫。
                //
                // 回调往返可能跨越「会话结束」：玩家说 bye / 下线 / 超时结束之后，这条
                // 异步回复仍会被投递。原实现只判 errorMsg / aiResponse 是否为 null，
                // 于是<b>会话终止后到达的回复仍会显示给玩家</b>；若玩家已开了新会话，
                // 旧会话的回复就串到新会话的聊天流里。
                //
                // 同文件「config 关闭」分支（handleEndConversation 调用前的早退）早就写了
                // if (!activePlayers.contains(...)) return; —— 作者已知这个窗口需要校验，
                // 唯独 AI 回调分支漏了。这里补齐同样的闸。
                //
                // 注意作用域：本改动只加**消费侧守卫**，不动 AIChatManager 的请求生命周期
                // （不做 future 取消）——取消会让「结束会话时最后一句话还没回来」变成
                // 永久丢失，而这里需要的只是「不再显示过期回复」。
                if (!activePlayers.contains(player.getUniqueId())) {
                    return;
                }
                if (errorMsg != null) {
                    String errorLabel = getMessageLine(player, "dialogue.error-label");
                    broadcastToPlayer(player, errorLabel + errorMsg);
                    return;
                }
                if (aiResponse == null) {
                    return;
                }
                String aiLabel = getMessageLine(player, "dialogue.ai-label");
                broadcastToPlayer(player, aiLabel + aiResponse);
                SoundManager.playSound(player, "menu-click");
            });
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (activePlayers.remove(player.getUniqueId())) {
            // H3：下线结束与正常结束走同一有效对话标准，未达阈值不计数
            AIChatManager.SessionSummary summary = aiChatManager.endConversationOnQuit(player);
            // US-FB（3B 集成）：下线只落本地行（人已走——不发卡不请求 AI）。
            plugin.getChatFeedbackManager().persistOnQuit(player, summary);
        }
    }

    private void handleEndConversation(Player player) {
        // PF-ACH-02 配套：切主线程后有 1 tick 窗口，期间 quit/命令退出可能已把
        // 玩家移出 activePlayers 并结束会话 —— 只有仍在场景模式才执行结束流程，
        // 防重复结算与重复文案。
        if (!activePlayers.remove(player.getUniqueId())) {
            return;
        }
        AIChatManager.SessionSummary summary = aiChatManager.endConversation(player);

        String endedMsg = getMessageLine(player, "dialogue.ended");
        player.sendMessage(endedMsg);
        // US-FB（3B 集成）时序照 plan §3.6：ended → deliverEnd（小结卡/点评）→ return-menu。
        plugin.getChatFeedbackManager().deliverEnd(player, summary);
        String returnMsg = getMessageLine(player, "dialogue.return-menu");
        player.sendMessage(returnMsg);
    }

    private void broadcastToPlayer(Player player, String message) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    /**
     * 从语言文件获取单行消息（已处理颜色代码与前缀），用于 AI 对话中的标签文本。
     * 3B code tester M-1 收口：原 {@code getGuiMsg} 链不替换 {@code {prefix}}
     * （dialogue.ended/return-menu 的模板以 {prefix} 开头，玩家会看到字面
     * {prefix}），改用 {@code getPlayerMessageSafe} —— 语言解析 + {prefix} 替换 +
     * 缺键 [?key] 可见占位 + 永不抛异常，四者一体。
     */
    private String getMessageLine(Player player, String key) {
        return plugin.getMessageUtil().getPlayerMessageSafe(player, key);
    }
}