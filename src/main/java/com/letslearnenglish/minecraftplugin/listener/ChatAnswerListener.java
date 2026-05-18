package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class ChatAnswerListener implements Listener {

    private final LetsLearnEnglish plugin;

    public ChatAnswerListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        WordLearningManager wlManager = plugin.getWordLearningManager();

        if (!wlManager.isAnswering(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);

        String answer = event.getMessage();
        String playerLang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(playerLang);

        String broadcastMsg = ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.answer-broadcast",
                        "&7{player} &7answered: &f{answer}")
                        .replace("{player}", player.getName())
                        .replace("{answer}", answer));

        event.getRecipients().clear();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player)) {
                online.sendMessage(broadcastMsg);
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            WordLearningManager.AnswerResult result = wlManager.submitAnswer(player, answer);
            if (result == null) {
                return;
            }

            if (result.correct) {
                SoundManager.playSound(player, "correct");
                String correctKey = result.consecutiveCorrect > 1
                        ? "word-learning-gui.correct-answer-combo"
                        : "word-learning-gui.correct-answer";
                String correctMsg = messages.getString(correctKey,
                        "&a\u2714 Correct! &7(+&e{score}&7)")
                        .replace("{score}", String.valueOf(result.comboScore))
                        .replace("{combo}", String.valueOf(result.consecutiveCorrect))
                        .replace("{multiplier}", String.valueOf(result.comboMultiplier));
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', correctMsg));
            } else {
                SoundManager.playSound(player, "incorrect");
                String wrongMsg = messages.getString("word-learning-gui.wrong-answer",
                        "&c\u2716 Wrong! Answer: &e{answer}")
                        .replace("{answer}", result.correctAnswer);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', wrongMsg));
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