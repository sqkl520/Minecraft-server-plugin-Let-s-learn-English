package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.AnswerChecker;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
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
        String answererLang = plugin.getPlayerLanguage(answerer);
        FileConfiguration msgs = plugin.getConfigManager().getMessageConfig(answererLang);
        String template = msgs.getString("word-learning-gui.answer-broadcast-correct");
        if (template == null) {
            return;
        }
        String safeName = sanitizeBroadcastName(answerer.getName());
        String broadcastMsg = ChatColor.translateAlternateColorCodes('&',
                template.replace("{player}", safeName)
                        .replace("{score}", String.valueOf(score)));
        for (Player online : cachedOnlinePlayers) {
            if (!online.equals(answerer) && online.isOnline()) {
                // B2：隐私开关查的是收件人自己的设置，不是答题者的
                PlayerDataStore.ProfileSettings ps = plugin.getPlayerDataStore()
                        .getProfileSettings(online.getUniqueId());
                if (ps.isShowAnswers()) {
                    online.sendMessage(broadcastMsg);
                }
            }
        }
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
            if (AnswerChecker.isChoice(mode)) {
                Word currentWord = wlManager.getCurrentWord(player.getUniqueId());
                java.util.List<Word> options = wlManager.getChoiceOptions(player.getUniqueId());
                if (AnswerChecker.isInvalidChoiceInput(mode, currentWord, answer, options)) {
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
                SoundManager.playSound(player, "correct");                String comboMsg = "";
                if (result.consecutiveCorrect > 1) {
                    comboMsg = " " + ChatColor.translateAlternateColorCodes('&',
                            msgs.getString("word-learning-gui.combo-message")
                                    .replace("{count}", String.valueOf(result.consecutiveCorrect))
                                    .replace("{multiplier}", String.valueOf(result.comboMultiplier)));
                }
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        msgs.getString("word-learning-gui.correct")
                                .replace("{score}", String.valueOf(result.comboScore))
                                .replace("{combo}", comboMsg)));

                // C2：答对后广播"答对+分"（不含答案文本）
                broadcastCorrectAnswer(player, result.comboScore);

                plugin.getAchievementManager().checkStreakAchievement(
                        player.getUniqueId(), result.consecutiveCorrect);
            } else {
                SoundManager.playSound(player, "incorrect");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        msgs.getString("word-learning-gui.incorrect")
                                .replace("{answer}", result.correctAnswer)));
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