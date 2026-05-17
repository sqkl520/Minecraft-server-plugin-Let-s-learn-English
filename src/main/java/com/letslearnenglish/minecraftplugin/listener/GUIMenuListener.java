package com.letslearnenglish.minecraftplugin.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.gui.ConfirmationGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;

/**
 * GUI Menu Listener
 *
 * Handles interactions with the plugin's custom inventory GUIs.
 */
public class GUIMenuListener implements Listener {

    private final LetsLearnEnglish plugin;

    public GUIMenuListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!(title.contains("Let's Learn English") || ConfirmationGUI.isConfirmGUI(title))) {
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

        if (title.contains("Let's Learn English")) {
            handleMainMenu(player, slot);
        } else {
            handleConfirmGUI(player, slot);
        }
    }

    private void handleMainMenu(Player player, int slot) {
        switch (slot) {
            case 11 -> player.performCommand("le word");
            case 13 -> player.performCommand("le dialogue");
            case 15 -> player.performCommand("le progress");
            case 18 -> handleLanguageSwitch(player);
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
            player.closeInventory();
            String currentLang = plugin.getPlayerLanguage(player);
            String targetLang = "zh".equals(currentLang) ? "en" : "zh";
            Bukkit.getScheduler().runTask(plugin, () -> {
                ConfirmationGUI confirm = new ConfirmationGUI(plugin, targetLang);
                confirm.openSecond(player);
            });
        } else if (stage == 2) {
            player.closeInventory();
            String currentLang = plugin.getPlayerLanguage(player);
            String targetLang = "zh".equals(currentLang) ? "en" : "zh";
            ConfirmationGUI confirm = new ConfirmationGUI(plugin, targetLang);
            confirm.applySwitch(player);
            Bukkit.getScheduler().runTask(plugin, () -> {
                new MainMenu(plugin).open(player);
            });
        }
    }
}