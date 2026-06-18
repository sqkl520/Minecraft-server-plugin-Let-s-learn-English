package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.ConfirmationGUI;
import com.letslearnenglish.minecraftplugin.gui.DailyChallengeGUI;
import com.letslearnenglish.minecraftplugin.gui.LearningCalendarGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.WordBankSelectGUI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Listener for Main Menu, Language Switch, and Confirmation GUI interactions.
 */
public class MainMenuListener implements Listener {

    private final LetsLearnEnglish plugin;

    public MainMenuListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (title == null) return;

        if (!title.contains("Let's Learn English") && !title.contains("一起学英语")
                && !ConfirmationGUI.isConfirmGUI(title)) {
            return;
        }

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();

        if (title.contains("Let's Learn English") || title.contains("一起学英语")) {
            handleMainMenu(player, slot);
        } else if (ConfirmationGUI.isConfirmGUI(title)) {
            handleConfirmGUI(player, slot);
        }
    }

    private void handleMainMenu(Player player, int slot) {
        switch (slot) {
            case 11 -> {
                SoundManager.playSound(player, "menu-click");
                new WordBankSelectGUI(plugin).open(player);
            }
            case 13 -> {
                SoundManager.playSound(player, "menu-click");
                player.performCommand("le dialogue");
            }
            case 15 -> {
                SoundManager.playSound(player, "menu-click");
                player.performCommand("le progress");
            }
            case 17 -> {
                SoundManager.playSound(player, "menu-click");
                player.performCommand("le square");
            }
            case 9 -> {
                SoundManager.playSound(player, "menu-click");
                plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                        () -> new MainMenu(plugin).open(player));
                new LearningCalendarGUI(plugin).open(player);
            }
            case 10 -> {
                SoundManager.playSound(player, "menu-click");
                plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                        () -> new MainMenu(plugin).open(player));
                new DailyChallengeGUI(plugin).open(player);
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
        if (slot != 13) return;

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
}
