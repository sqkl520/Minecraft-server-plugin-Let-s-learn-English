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

    /** 正在 AI 对话模式中的玩家 */
    private final Set<UUID> activePlayers = Collections.synchronizedSet(new HashSet<>());

    public ChatScenarioListener(LetsLearnEnglish plugin, AIChatManager aiChatManager) {
        this.plugin = plugin;
        this.aiChatManager = aiChatManager;
    }

    public void addPlayer(Player player) {
        activePlayers.add(player.getUniqueId());
    }

    public void removePlayer(Player player) {
        activePlayers.remove(player.getUniqueId());
        aiChatManager.endConversation(player);
    }

    public boolean isInScenarioMode(Player player) {
        return activePlayers.contains(player.getUniqueId());
    }

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

        // R-66 纵深防御：答题/决斗优先 —— 玩家在答题或决斗中时，对话侧不处理该消息
        // （正常流 ChatAnswerListener 已先 cancel 事件使其不可达；此处防竞态窗口。
        //  不 cancel、不提示、不消费 —— 让给答题/决斗路径处理）。
        if (plugin.getWordLearningManager().hasActiveQuiz(player.getUniqueId())
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
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!activePlayers.contains(player.getUniqueId())) {
                    return; // 切主线程的 1 tick 窗口内已被 quit/命令退出处理，不重复提示与结算
                }
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
            aiChatManager.endConversationOnQuit(player);
        }
    }

    private void handleEndConversation(Player player) {
        // PF-ACH-02 配套：切主线程后有 1 tick 窗口，期间 quit/命令退出可能已把
        // 玩家移出 activePlayers 并结束会话 —— 只有仍在场景模式才执行结束流程，
        // 防重复结算与重复文案。
        if (!activePlayers.remove(player.getUniqueId())) {
            return;
        }
        aiChatManager.endConversation(player);

        String endedMsg = getMessageLine(player, "dialogue.ended");
        player.sendMessage(endedMsg);
        String returnMsg = getMessageLine(player, "dialogue.return-menu");
        player.sendMessage(returnMsg);
    }

    private void broadcastToPlayer(Player player, String message) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    /**
     * 从语言文件获取单行消息（已处理颜色代码），用于 AI 对话中的标签文本。
     */
    private String getMessageLine(Player player, String key) {
        String lang = plugin.getPlayerLanguage(player);
        org.bukkit.configuration.file.FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        String msg = messages.getString(key);
        if (msg == null) {
            return ChatColor.RED + "Missing: " + key;
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }
}