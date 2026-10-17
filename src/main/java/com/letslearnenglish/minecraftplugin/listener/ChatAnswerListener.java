package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.AnswerChecker;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.Collection;

public class ChatAnswerListener implements Listener {

    private final LetsLearnEnglish plugin;
    // P2-21: 3 秒在线玩家快照缓存 —— 意图：答对广播是逐题高频路径，
    // 每次直接 Bukkit.getOnlinePlayers() 会产生高频集合分配/拷贝；
    // 3 秒粒度对本广播（纯聊天通知，无正确性要求）足够新鲜。
    // 已知副作用（刻意保留，B 流核实裁定）：新进服玩家在快照刷新前
    // （最多 3 秒）收不到广播。
    private volatile Collection<? extends Player> cachedOnlinePlayers;
    private volatile long playersCacheTimestamp;

    /** 广播玩家名最大长度：stripColor 后截断，防超长刷屏（C2） */
    private static final int BROADCAST_NAME_MAX_LENGTH = 50;

    public ChatAnswerListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * 清洗用于广播的玩家名：去颜色码 + 截断。
     * 防颜色码注入（&/§）与超长名刷屏。
     */
    private static String sanitizeBroadcastName(String name) {
        if (name == null) {
            return "?";
        }
        // 先去 Bukkit 颜色码（§），再去 & 风格颜色码（广播模板走颜色翻译，& 会被二次解析）
        String clean = ChatColor.stripColor(name).replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        if (clean.length() > BROADCAST_NAME_MAX_LENGTH) {
            clean = clean.substring(0, BROADCAST_NAME_MAX_LENGTH);
        }
        return clean.isEmpty() ? "?" : clean;
    }

    /**
     * C2：判分后广播"答对+分"（不含答案文本）。
     * 答案文本永不出现在广播里 — 此方法只接收玩家名与得分，不接收答案参数，
     * 即使 showAnswers 被手动打开也只走这条无答案路径。
     */
    private void broadcastCorrectAnswer(Player answerer, int score) {
        long now = System.currentTimeMillis();
        if (cachedOnlinePlayers == null || now - playersCacheTimestamp > 3000) {
            cachedOnlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            playersCacheTimestamp = now;
        }
        String safeName = sanitizeBroadcastName(answerer.getName());
        String scoreText = String.valueOf(score);
        // R-86m（P2 批次）：按每位收件人自己的语言渲染——不再是"答题者语言全员共用"。
        // 双语各渲染一次后按收件人语言取用（最多 2 次模板解析）。
        String zhMsg = renderBroadcastTemplate("zh", safeName, scoreText);
        String enMsg = renderBroadcastTemplate("en", safeName, scoreText);
        for (Player online : cachedOnlinePlayers) {
            if (!online.equals(answerer) && online.isOnline()) {
                // B2：隐私开关查的是收件人自己的设置，不是答题者的
                PlayerDataStore.ProfileSettings ps = plugin.getPlayerDataStore()
                        .getProfileSettings(online.getUniqueId());
                if (ps.isShowAnswers()) {
                    String rendered = "zh".equalsIgnoreCase(plugin.getPlayerLanguage(online))
                            ? zhMsg : enMsg;
                    if (rendered != null) {
                        online.sendMessage(rendered);
                    }
                }
            }
        }
    }

    /**
     * R-86m：按指定语言渲染广播模板；该语言缺键时回落 zh，两语言都缺返回 null（放弃广播）。
     */
    private String renderBroadcastTemplate(String lang, String safeName, String scoreText) {
        String template = plugin.getConfigManager().getMessageConfig(lang)
                .getString("word-learning-gui.answer-broadcast-correct");
        if (template == null && !"zh".equalsIgnoreCase(lang)) {
            template = plugin.getConfigManager().getMessageConfig("zh")
                    .getString("word-learning-gui.answer-broadcast-correct");
        }
        if (template == null) {
            return null;
        }
        return ChatColor.translateAlternateColorCodes('&',
                template.replace("{player}", safeName).replace("{score}", scoreText));
    }

    /**
     * M4: tell a dueling player (on the main thread) to finish their duel
     * before quiz/practice can consume chat input again. The message
     * resolves in the player's own language; strictly one-way — the
     * chat input itself is never forwarded to the duel as an answer.
     */
    private void notifyFinishDuelFirst(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "duel.finish-duel-first"));
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        // Check duel first — duel answers take priority over word learning
        var duelManager = plugin.getDuelManager();
        if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
            event.setCancelled(true);
            event.getRecipients().clear();
            String answer = event.getMessage();
            Bukkit.getScheduler().runTask(plugin, () -> {
                duelManager.submitAnswer(player, answer);
            });
            return;
        }

        WordLearningManager wlManager = plugin.getWordLearningManager();

        // Check practice mode first (Wrong Book practice)
        if (wlManager.isPracticingWord(player.getUniqueId())) {
            // M4: duel-in-progress guard — don't consume a dueling player's
            // chat as a practice answer; leave the event for public chat
            if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
                notifyFinishDuelFirst(player);
                return;
            }
            event.setCancelled(true);
            String answer = event.getMessage();
            String practiceWord = wlManager.getPracticingWord(player.getUniqueId());

            // Check for cancel command
            if (answer.trim().equalsIgnoreCase("cancel")) {
                wlManager.cancelPractice(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "wrong-book.practice-cancelled"));
                    new com.letslearnenglish.minecraftplugin.gui.WrongBookGUI(plugin).open(player, 1);
                });
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                wlManager.submitPracticeAnswer(player, answer);
                // Re-open WrongBookGUI after answer
                new com.letslearnenglish.minecraftplugin.gui.WrongBookGUI(plugin).open(player, 1);
            });
            return;
        }

        if (!wlManager.isAnswering(player.getUniqueId())) {
            return;
        }

        // M4: duel-in-progress guard — a dueling player must never have
        // their chat consumed as a quiz answer (covers the async-chat
        // branch check / main-thread acceptDuel race window).
        if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
            notifyFinishDuelFirst(player);
            return;
        }

        event.setCancelled(true);

        String answer = event.getMessage();
        String playerLang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(playerLang);

        event.getRecipients().clear();
        // C2：答题广播（含答案文本的旧 answer-broadcast）已删除 — 改为判分后仅答对广播
        // "答对+分"（broadcastCorrectAnswer，不含答案文本），见下方主线程回调。
        FileConfiguration msgs = messages;
        Bukkit.getScheduler().runTask(plugin, () -> {
            // FEAT-MODE-01：按会话 mode 分流判定 — mode 取自 WordLearningManager，
            // 判分收敛于 AnswerChecker.check（submitAnswer 内部调用），本分支只喂答案。
            String mode = wlManager.getMode(player.getUniqueId());

            // R-10：choice 模式下敲了越界选项字母（小词库只有 A/B 却输入 C/D）属"无效输入"，
            // 不当答错结算（不清连击 / 不扣分 / 不进错题本）。判定收敛在 AnswerChecker，
            // 这里仅在 submitAnswer 之前拦截并提示；计时器未被取消，玩家可在剩余倒计时内重答。
            // MC-3：caseSensitive 走会话快照（与 submitAnswer 内部判分同参数，
            // 防"判分判对的输入被前置无效判定抢先拦下"）。
            if (AnswerChecker.isChoice(mode)) {
                Word currentWord = wlManager.getCurrentWord(player.getUniqueId());
                java.util.List<Word> options = wlManager.getChoiceOptions(player.getUniqueId());
                if (AnswerChecker.isInvalidChoiceInput(mode, currentWord, answer, options,
                        wlManager.isCaseSensitive(player.getUniqueId()))) {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "word-learning-gui.invalid-option"));
                    return;
                }
            }

            WordLearningManager.AnswerResult result = wlManager.submitAnswer(player, answer);
            if (result == null) {
                // R-15：迟到答案 —— 倒计时已判定或会话已结束（state 被移出 map / 正被占用），
                // submitAnswer 返回 null。不再静默丢弃，给玩家一句"本题已过"提示。
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word-learning-gui.answer-expired"));
                return;
            }

            if (result.correct) {
                // Phase 3 3A-2（plan-audio-feedback B3）：连击反馈升级。
                // 里程碑 tick（combo 命中 3/5/7/10 且 milestone-enabled）：铃音
                // 替代升调 correct 音（不叠加，避免双音浑浊）+ ActionBar 闪里程碑文案；
                // 其余答对：correct 音 pitch 随连击递增（getComboPitch，断连击自然回落）。
                if (wlManager.isComboMilestone(result.consecutiveCorrect)) {
                    SoundManager.playSound(player, "combo-milestone");
                    plugin.getVersionAdapter().sendActionBar(player,
                            ChatColor.translateAlternateColorCodes('&',
                                    MessageUtil.getGuiMsg(msgs, "word-learning-gui.combo-milestone")
                                            .replace("{count}", String.valueOf(result.consecutiveCorrect))
                                            .replace("{multiplier}", String.valueOf(result.comboMultiplier))));
                } else {
                    SoundManager.playSound(player, "correct",
                            wlManager.getComboPitch(result.consecutiveCorrect));
                }
                String comboMsg = "";
                if (result.consecutiveCorrect > 1) {
                    // P2 收尾：裸 getString 收敛为 getGuiMsg（缺键返回 [?key] 不 NPE）
                    comboMsg = " " + ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(msgs, "word-learning-gui.combo-message")
                                    .replace("{count}", String.valueOf(result.consecutiveCorrect))
                                    .replace("{multiplier}", String.valueOf(result.comboMultiplier)));
                }
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(msgs, "word-learning-gui.correct")
                                .replace("{score}", String.valueOf(result.comboScore))
                                .replace("{combo}", comboMsg)));

                // C2：答对后广播"答对+分"（不含答案文本）
                broadcastCorrectAnswer(player, result.comboScore);

                plugin.getAchievementManager().checkStreakAchievement(
                        player.getUniqueId(), result.consecutiveCorrect);
            } else {
                SoundManager.playSound(player, "incorrect");
                // Phase 3 onboarding（plan-onboarding §3.5）：教程迷你会话答错 = 原地重试
                // 提示（不显示答案）；音效保持 incorrect 不变；随后既有
                // else { openWithMessage } 循环原样复用 —— 会话未推进，重开的仍是同题。
                // 教程文案走 getPlayerMessage（per-player 语言 + {prefix} 替换；
                // onboarding.* 聊天键按规划带 {prefix}，getGuiMsg 不做该替换）。
                if (result.tutorial) {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "onboarding.retry-wrong"));
                } else {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(msgs, "word-learning-gui.incorrect")
                                    .replace("{answer}", result.correctAnswer)));
                }
            }

            if (result.sessionComplete) {
                SoundManager.playSound(player, "complete");
                wlManager.showSessionComplete(player, result);
            } else {
                String difficulty = wlManager.getDifficulty(player.getUniqueId());
                new WordLearningGUI(plugin).openWithMessage(player, difficulty, mode);
            }
        });
    }
}