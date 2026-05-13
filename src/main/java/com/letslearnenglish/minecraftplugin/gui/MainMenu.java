package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Main Menu GUI
 *
 * The primary menu interface for the English learning plugin.
 * Provides access to all learning modules.
 */
public class MainMenu {

    private final LetsLearnEnglish plugin;

    public MainMenu(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the main menu for a player.
     */
    public void open(Player player) {
        String title = plugin.getConfigManager().getMainConfig()
                .getString("gui.main-menu-title", "&6&lLet's Learn English!");
        title = ChatColor.translateAlternateColorCodes('&', title);
        int size = plugin.getConfigManager().getMainConfig().getInt("gui.menu-size", 27);

        Inventory menu = Bukkit.createInventory(null, size, title);

        menu.setItem(11, createMenuItem(Material.BOOK, "&aWord Learning",
                "&7Practice vocabulary with various modes"));
        menu.setItem(13, createMenuItem(Material.PAPER, "&bDialogue Practice",
                "&7Practice English conversations"));
        menu.setItem(15, createMenuItem(Material.DIAMOND, "&eProgress & Stats",
                "&7View your learning progress"));

        player.openInventory(menu);
    }

    private ItemStack createMenuItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            meta.setLore(java.util.Collections.singletonList(
                    ChatColor.translateAlternateColorCodes('&', lore)));
            item.setItemMeta(meta);
        }
        return item;
    }
}