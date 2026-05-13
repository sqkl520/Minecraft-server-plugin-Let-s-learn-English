package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

/**
 * Word Learning GUI
 *
 * Interface for word learning sessions with interactive elements.
 */
public class WordLearningGUI {

    private final LetsLearnEnglish plugin;

    public WordLearningGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, String difficulty, String mode) {
        Inventory inv = Bukkit.createInventory(null, 27,
                "Word Learning - " + difficulty);

        inv.setItem(4, createItem(Material.BOOK, "Word Learning Session",
                "Difficulty: " + difficulty,
                "Mode: " + mode));

        inv.setItem(13, createItem(Material.PAPER, "Answer in chat!",
                "Type your answer in the chat window",
                "Follow the prompts to practice words"));

        inv.setItem(22, createItem(Material.BARRIER, "Close Inventory",
                "Close this window and watch",
                "the chat for word prompts"));

        player.openInventory(inv);

        player.sendMessage("§aLearning session started! §7Difficulty: §e" + difficulty
                + " §7Mode: §e" + mode);
        player.sendMessage("§7Type your answers in chat when prompted.");
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6" + name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}