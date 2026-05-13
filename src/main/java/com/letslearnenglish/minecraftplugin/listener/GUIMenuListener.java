package com.letslearnenglish.minecraftplugin.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * GUI Menu Listener
 *
 * Handles interactions with the plugin's custom inventory GUIs.
 */
public class GUIMenuListener implements Listener {

    public GUIMenuListener() {
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.contains("Let's Learn English")) {
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
        player.closeInventory();

        switch (slot) {
            case 11 -> player.performCommand("le word");
            case 13 -> player.performCommand("le dialogue");
            case 15 -> player.performCommand("le progress");
            default -> {}
        }
    }
}