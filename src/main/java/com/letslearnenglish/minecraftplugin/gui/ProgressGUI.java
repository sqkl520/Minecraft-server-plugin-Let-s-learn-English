package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.PlayerProgress;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class ProgressGUI {

    private final LetsLearnEnglish plugin;

    public ProgressGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        PlayerProgress progress = plugin.getProgressManager()
                .getPlayerProgress(player.getUniqueId());

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("progress-gui.title", "Learning Progress"));

        Inventory inv = Bukkit.createInventory(null, 27, title);

        inv.setItem(4, createInfoItem(Material.PLAYER_HEAD, player.getName(),
                messages.getString("progress-gui.player-summary")));

        inv.setItem(11, createStatItem(Material.BOOK,
                messages.getString("progress-gui.words-learned"),
                String.valueOf(progress.getTotalWordsLearned())));

        inv.setItem(12, createStatItem(Material.PAPER,
                messages.getString("progress-gui.sessions"),
                String.valueOf(progress.getTotalSessions())));

        inv.setItem(13, createStatItem(Material.EXPERIENCE_BOTTLE,
                messages.getString("progress-gui.total-score"),
                String.valueOf(progress.getTotalScore())));

        inv.setItem(14, createStatItem(Material.CLOCK,
                messages.getString("progress-gui.current-streak"),
                progress.getCurrentStreak()
                        + messages.getString("progress-gui.days-suffix", " days")));

        inv.setItem(15, createStatItem(Material.DIAMOND,
                messages.getString("progress-gui.longest-streak"),
                progress.getLongestStreak()
                        + messages.getString("progress-gui.days-suffix", " days")));

        player.openInventory(inv);
    }

    private ItemStack createStatItem(Material material, String label, String value) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&e" + label));
            meta.setLore(Arrays.asList(
                    ChatColor.translateAlternateColorCodes('&', "&f" + value)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfoItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&6" + name));
            meta.setLore(Arrays.asList(
                    ChatColor.translateAlternateColorCodes('&', "&7" + lore)));
            item.setItemMeta(meta);
        }
        return item;
    }
}