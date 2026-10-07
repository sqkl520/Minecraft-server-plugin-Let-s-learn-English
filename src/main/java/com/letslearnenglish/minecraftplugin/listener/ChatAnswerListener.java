package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
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

    public ChatAnswerListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
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

        event.setCancelled(true);

        String answer = event.getMessage();
        String playerLang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(playerLang);

        String broadcastMsg = ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.answer-broadcast")
                        .replace("{player}", player.getName())
                        .replace("{answer}", answer));

        event.getRecipients().clear();
        long now = System.currentTimeMillis();
        if (cachedOnlinePlayers == null || now - playersCacheTimestamp > 3000) {
            cachedOnlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            playersCacheTimestamp = now;
        }
        for (Player online : cachedOnlinePlayers) {
            if (!online.equals(player) && online.isOnline()) {
                PlayerDataStore.ProfileSettings ps = plugin.getPlayerDataStore()
                        .getProfileSettings(player.getUniqueId());
                if (ps.isShowAnswers()) {
                    online.sendMessage(broadcastMsg);
                }
            }
        }

        FileConfiguration msgs = messages;
        Bukkit.getScheduler().runTask(plugin, () -> {
            WordLearningManager.AnswerResult result = wlManager.submitAnswer(player, answer);
            if (result == null) {
                return;
            }

            if (result.correct) {
                SoundManager.playSound(player, "correct");
                String comboMsg = "";
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
                String mode = wlManager.getMode(player.getUniqueId());
                new WordLearningGUI(plugin).openWithMessage(player, difficulty, mode);
            }
        });
    }
}