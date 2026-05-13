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
 * Dialogue GUI
 *
 * Interface for scenario-based dialogue practice.
 */
public class DialogueGUI {

    private final LetsLearnEnglish plugin;

    public DialogueGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, String sceneName) {
        Inventory inv = Bukkit.createInventory(null, 27,
                "Dialogue - " + sceneName);

        inv.setItem(4, createItem(Material.PAPER, "Dialogue Practice",
                "Scene: " + sceneName,
                "Talk to the NPC to practice"));

        inv.setItem(13, createItem(Material.BOOK, "Instructions",
                "1. Find the NPC for this scene",
                "2. Right-click the NPC to start",
                "3. Type your responses in chat"));

        inv.setItem(22, createItem(Material.BARRIER, "Close Inventory",
                "Close this window",
                "and find the dialogue NPC"));

        player.openInventory(inv);

        player.sendMessage("§aDialogue scene activated! §7Scene: §e" + sceneName);
        player.sendMessage("§7Find and talk to the NPC to begin the conversation.");
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