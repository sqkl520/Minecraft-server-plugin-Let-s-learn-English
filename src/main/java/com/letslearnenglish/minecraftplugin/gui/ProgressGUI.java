package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.PlayerProgress;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

/**
 * Progress GUI
 *
 * Interface for viewing learning progress, achievements, and leaderboards.
 */
public class ProgressGUI {

    private final LetsLearnEnglish plugin;

    public ProgressGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        PlayerProgress progress = plugin.getProgressManager()
                .getPlayerProgress(player.getUniqueId());

        Inventory inv = Bukkit.createInventory(null, 27, "Learning Progress");

        inv.setItem(4, createInfoItem(Material.PLAYER_HEAD, player.getName(),
                "Here's your learning summary"));

        inv.setItem(11, createStatItem(Material.BOOK, "Words Learned",
                String.valueOf(progress.getTotalWordsLearned())));

        inv.setItem(12, createStatItem(Material.PAPER, "Sessions",
                String.valueOf(progress.getTotalSessions())));

        inv.setItem(13, createStatItem(Material.EXPERIENCE_BOTTLE, "Total Score",
                String.valueOf(progress.getTotalScore())));

        inv.setItem(14, createStatItem(Material.CLOCK, "Current Streak",
                progress.getCurrentStreak() + " days"));

        inv.setItem(15, createStatItem(Material.DIAMOND, "Longest Streak",
                progress.getLongestStreak() + " days"));

        player.openInventory(inv);
    }

    private ItemStack createStatItem(Material material, String label, String value) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e" + label);
            meta.setLore(Arrays.asList("§f" + value));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfoItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6" + name);
            meta.setLore(Arrays.asList("§7" + lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}