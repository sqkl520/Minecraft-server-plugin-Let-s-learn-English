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

public class DialogueGUI {

    private final LetsLearnEnglish plugin;

    public DialogueGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, String sceneName) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("dialogue-gui.title", "Dialogue - {scene}")
                        .replace("{scene}", sceneName));

        Inventory inv = Bukkit.createInventory(null, 27, title);

        inv.setItem(4, createItem(Material.PAPER,
                messages.getString("dialogue-gui.practice-info"),
                messages.getString("dialogue-gui.practice-info-lore-scene",
                        "Scene: {scene}").replace("{scene}", sceneName),
                messages.getString("dialogue-gui.practice-info-lore-hint")));

        inv.setItem(13, createItem(Material.BOOK,
                messages.getString("dialogue-gui.instructions"),
                messages.getString("dialogue-gui.instructions-lore-1"),
                messages.getString("dialogue-gui.instructions-lore-2"),
                messages.getString("dialogue-gui.instructions-lore-3")));

        inv.setItem(22, createItem(Material.BARRIER,
                messages.getString("dialogue-gui.close"),
                messages.getString("dialogue-gui.close-lore-1"),
                messages.getString("dialogue-gui.close-lore-2")));

        player.openInventory(inv);

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("dialogue-gui.activated",
                        "&aDialogue scene activated!")
                        .replace("{scene}", sceneName)));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("dialogue-gui.activated-hint")));
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