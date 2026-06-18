package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
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

        ConfigManager cm = plugin.getConfigManager();

        for (int i = 0; i < 27; i++) {
            menu.setItem(i, createMenuItem(Material.GRAY_STAINED_GLASS_PANE, " ", ""));
        }

        String wordName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.word-learning", "&aWord Learning"));
        String wordLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.word-learning-lore", "&7Practice vocabulary with various modes"));
        String dialogueName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.dialogue-practice", "&bAI Scenario Chat"));
        String dialogueLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.dialogue-practice-lore", "&7Practice English with AI scenarios"));
        String progressName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.progress-stats", "&eProfile"));
        String progressLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.progress-stats-lore", "&7View your personal profile"));

        menu.setItem(cm.getGuiSlot("main-menu", "word-learning", 11), createMenuItem(cm.getGuiMaterial("main-menu", "word-learning", Material.BOOK), wordName, wordLore));
        menu.setItem(cm.getGuiSlot("main-menu", "dialogue-practice", 13), createMenuItem(cm.getGuiMaterial("main-menu", "dialogue-practice", Material.PAPER), dialogueName, dialogueLore));
        menu.setItem(cm.getGuiSlot("main-menu", "personal-profile", 15), createMenuItem(cm.getGuiMaterial("main-menu", "personal-profile", Material.DIAMOND), progressName, progressLore));

        String squareName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.square", "&dEnglish Square"));
        String squareLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.square-lore", "&7See other learners' progress"));
        menu.setItem(cm.getGuiSlot("main-menu", "english-square", 17), createMenuItem(cm.getGuiMaterial("main-menu", "english-square", Material.PLAYER_HEAD), squareName, squareLore));

        String dailyName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.daily-challenge", "&6Daily Challenge"));
        String dailyLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.daily-challenge-lore", "&7Complete today's tasks for rewards"));
        menu.setItem(cm.getGuiSlot("main-menu", "daily-challenge", 10),
                createMenuItem(cm.getGuiMaterial("main-menu", "daily-challenge", Material.WRITABLE_BOOK),
                        dailyName, dailyLore));

        String wrongName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.wrong-book", "&cWrong Book"));
        String wrongLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.wrong-book-lore", "&7Review your difficult words"));
        menu.setItem(14, createMenuItem(Material.BOOKSHELF, wrongName, wrongLore));

        String calendarName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.calendar", "&aLearning Calendar"));
        String calendarLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.calendar-lore", "&7View your daily learning activity"));
        menu.setItem(9, createMenuItem(Material.CLOCK, calendarName, calendarLore));

        String btnName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.button", "&eSwitch Language"));
        String btnLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.button-lore", "&7Click to switch"));
        menu.setItem(cm.getGuiSlot("main-menu", "language-toggle", 18), createMenuItem(cm.getGuiMaterial("main-menu", "language-toggle", Material.RED_BED), btnName, btnLore));

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