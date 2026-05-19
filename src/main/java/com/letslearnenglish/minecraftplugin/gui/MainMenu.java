package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;

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

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("gui.main-menu-title", "&6&lLet's Learn English!"));
        int size = plugin.getConfigManager().getMainConfig().getInt("gui.menu-size", 27);

        Inventory menu = Bukkit.createInventory(null, size, title);

        String wordName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.word-learning", "&aWord Learning"));
        String wordLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.word-learning-lore", "&7Practice vocabulary with various modes"));
        String dialogueName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.dialogue-practice", "&bDialogue Practice"));
        String dialogueLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.dialogue-practice-lore", "&7Practice English conversations"));
        String progressName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.progress-stats", "&eProgress & Stats"));
        String progressLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.progress-stats-lore", "&7View your learning progress"));

        menu.setItem(11, createMenuItem(Material.BOOK, wordName, wordLore));
        menu.setItem(13, createMenuItem(Material.PAPER, dialogueName, dialogueLore));
        menu.setItem(15, createMenuItem(Material.DIAMOND, progressName, progressLore));

        String btnName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.button", "&eSwitch Language"));
        String btnLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.button-lore", "&7Click to switch"));
        menu.setItem(18, createMenuItem(Material.RED_BED, btnName, btnLore));

        SoundManager.playSound(player, "menu-open");
        player.openInventory(menu);
    }

    private ItemStack createMenuItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            meta.setLore(Collections.singletonList(
                    ChatColor.translateAlternateColorCodes('&', lore)));
            item.setItemMeta(meta);
        }
        return item;
    }
}