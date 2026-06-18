package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * DuelGUIListener — handles clicks inside the Duel GUI.
 *
 * The only interactive element is the Quit button (barrier at slot 22).
 * All other slots are display-only and clicking does nothing.
 */
public class DuelGUIListener implements Listener {

    private final LetsLearnEnglish plugin;

    public DuelGUIListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null) return;

        String title = event.getView().getTitle();
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String duelTitleZh = ChatColor.translateAlternateColorCodes('&',
                messages.getString("duel.gui-title", "&6&lWord Duel / 单词对决"));
        String duelTitleEn = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfigManager().getMessageConfig("en").getString("duel.gui-title", "&6&lWord Duel / 单词对决"));

        // Check if this is a duel GUI by matching the prefix
        if (!title.startsWith(ChatColor.translateAlternateColorCodes('&', "&6&lWord Duel"))) {
            return;
        }

        event.setCancelled(true);

        // Slot 22 = Quit button
        if (event.getSlot() == 22) {
            player.closeInventory();
            DuelManager duelManager = plugin.getDuelManager();
            if (duelManager != null) {
                duelManager.quitDuel(player);
            }
        }
    }
}
