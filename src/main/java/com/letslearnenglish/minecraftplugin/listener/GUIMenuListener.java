package com.letslearnenglish.minecraftplugin.listener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.gui.ConfirmationGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;

public class GUIMenuListener implements Listener {

    private final LetsLearnEnglish plugin;

    public GUIMenuListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public static boolean isPluginGUI(String title) {
        if (title == null) return false;
        return title.contains("Let's Learn English")
                || title.contains("一起学英语")
                || ConfirmationGUI.isConfirmGUI(title)
                || title.contains("Word Learning")
                || title.contains("单词学习")
                || title.contains("Dialogue")
                || title.contains("情景对话")
                || title.contains("Learning Progress")
                || title.contains("学习进度");
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

        if (!isPluginGUI(title)) {
            return;
        }

        event.setCancelled(true);

        if (event.getCurrentItem() == null) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getSlot();

        if (title.contains("Let's Learn English") || title.contains("一起学英语")) {
            handleMainMenu(player, slot);
        } else if (ConfirmationGUI.isConfirmGUI(title)) {
            handleConfirmGUI(player, slot);
        } else if (title.contains("Word Learning") || title.contains("单词学习")) {
            handleWordLearningGUI(player, slot);
        } else if (title.contains("Dialogue") || title.contains("情景对话")) {
            handleDialogueGUI(player, slot);
        }
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

    private void handleMainMenu(Player player, int slot) {
        switch (slot) {
            case 11 -> {
                SoundManager.playSound(player, "menu-click");
                player.performCommand("le word");
            }
            case 13 -> {
                SoundManager.playSound(player, "menu-click");
                player.performCommand("le dialogue");
            }
            case 15 -> {
                SoundManager.playSound(player, "menu-click");
                player.performCommand("le progress");
            }
            case 18 -> {
                SoundManager.playSound(player, "menu-click");
                handleLanguageSwitch(player);
            }
            default -> {}
        }
    }

    private void handleLanguageSwitch(Player player) {
        String currentLang = plugin.getPlayerLanguage(player);
        String targetLang = "zh".equals(currentLang) ? "en" : "zh";
        ConfirmationGUI confirm = new ConfirmationGUI(plugin, targetLang);
        confirm.open(player);
    }

    private void handleConfirmGUI(Player player, int slot) {
        if (slot != 13) {
            return;
        }

        int stage = ConfirmationGUI.getStage(player);

        if (stage == 1) {
            String currentLang = plugin.getPlayerLanguage(player);
            String targetLang = "zh".equals(currentLang) ? "en" : "zh";
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                ConfirmationGUI confirm = new ConfirmationGUI(plugin, targetLang);
                confirm.openSecond(player);
            }, 2L);
        } else if (stage == 2) {
            String currentLang = plugin.getPlayerLanguage(player);
            String targetLang = "zh".equals(currentLang) ? "en" : "zh";
            ConfirmationGUI confirm = new ConfirmationGUI(plugin, targetLang);
            confirm.applySwitch(player);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                new MainMenu(plugin).open(player);
            }, 2L);
        }
    }

    private void handleWordLearningGUI(Player player, int slot) {
        WordLearningManager wlManager = plugin.getWordLearningManager();

        if (slot == 16) {
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

    private void handleDialogueGUI(Player player, int slot) {
        if (slot == 22) {
            player.closeInventory();
        }
    }
}