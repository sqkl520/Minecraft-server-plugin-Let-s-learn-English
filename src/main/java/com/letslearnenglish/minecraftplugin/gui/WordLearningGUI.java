package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class WordLearningGUI {

    private final LetsLearnEnglish plugin;

    public WordLearningGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, String difficulty, String mode) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.title", "Word Learning - {difficulty}")
                        .replace("{difficulty}", difficulty));

        Inventory inv = Bukkit.createInventory(null, 27, title);

        inv.setItem(4, createItem(Material.BOOK,
                messages.getString("word-learning-gui.session-info"),
                messages.getString("word-learning-gui.session-info-lore-difficulty",
                        "Difficulty: {difficulty}").replace("{difficulty}", difficulty),
                messages.getString("word-learning-gui.session-info-lore-mode",
                        "Mode: {mode}").replace("{mode}", mode)));

        inv.setItem(13, createItem(Material.PAPER,
                messages.getString("word-learning-gui.answer-hint"),
                messages.getString("word-learning-gui.answer-hint-lore-1"),
                messages.getString("word-learning-gui.answer-hint-lore-2")));

        inv.setItem(22, createItem(Material.BARRIER,
                messages.getString("word-learning-gui.close"),
                messages.getString("word-learning-gui.close-lore-1"),
                messages.getString("word-learning-gui.close-lore-2")));

        player.openInventory(inv);

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.started",
                        "&aLearning session started!")
                        .replace("{difficulty}", difficulty)
                        .replace("{mode}", mode)));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.started-hint",
                        "&7Type your answers in chat when prompted.")));
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&6" + name));
            meta.setLore(Arrays.stream(lore)
                    .map(l -> ChatColor.translateAlternateColorCodes('&', "&7" + l))
                    .toList());
            item.setItemMeta(meta);
        }
        return item;
    }
}