package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Listener for Word Learning GUI interactions and inventory close events.
 */
public class WordLearningListener implements Listener {

    private final LetsLearnEnglish plugin;

    public WordLearningListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();

        if (!title.contains("Word Learning") && !title.contains("单词学习")) {
            return;
        }

        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        WordLearningManager wlManager = plugin.getWordLearningManager();

        if (!wlManager.hasActiveQuiz(player.getUniqueId())) {
            return;
        }

        if (wlManager.isAnswering(player.getUniqueId())) {
            return;
        }

        startAnswerForCurrentWord(player, wlManager);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.contains("Word Learning") && !title.contains("单词学习")) {
            return;
        }

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        handleWordLearningGUI(player, event.getSlot());
    }

    private void startAnswerForCurrentWord(Player player, WordLearningManager wlManager) {
        Word currentWord = wlManager.getCurrentWord(player.getUniqueId());
        if (currentWord == null) return;

        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        int timeout = wlManager.getAnswerTimeout();

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.answer-prompt",
                        "&eSpell: &f{chinese} &7({seconds}s)")
                        .replace("{chinese}", currentWord.getChinese())
                        .replace("{seconds}", String.valueOf(timeout))));

        wlManager.startAnswerTimer(player);
    }

    private void handleWordLearningGUI(Player player, int slot) {
        WordLearningManager wlManager = plugin.getWordLearningManager();

        if (slot == 8) {
            if (wlManager.hasActiveQuiz(player.getUniqueId())) {
                SoundManager.playSound(player, "incorrect");
                String lang = plugin.getPlayerLanguage(player);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "zh".equalsIgnoreCase(lang)
                                ? "&c答题中无法返回主菜单！请先完成或停止当前答题会话"
                                : "&cCannot return to menu while answering! Finish or stop the current session first"));
            } else {
                SoundManager.playSound(player, "menu-click");
                new MainMenu(plugin).open(player);
            }
        } else if (slot == 16) {
            SoundManager.playSound(player, "menu-click");
            handleStopSession(player, wlManager);
        } else if (slot == 22) {
            SoundManager.playSound(player, "menu-click");
            startAnswerForCurrentWord(player, wlManager);
            player.closeInventory();
        }
    }

    private void handleStopSession(Player player, WordLearningManager wlManager) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        wlManager.stopQuiz(player);
        player.closeInventory();

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.session-ended",
                        "&aLearning session ended!")));
    }
}
