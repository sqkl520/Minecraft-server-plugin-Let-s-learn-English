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

import java.util.*;

public class WordBankSelectGUI {

    private static final int GUI_SIZE = 27;
    private final LetsLearnEnglish plugin;
    private final List<String> availableBanks;

    public WordBankSelectGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.availableBanks = new ArrayList<>(plugin.getWordManager().getAvailableDifficulties());
    }

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                "zh".equalsIgnoreCase(lang) ? "&a&l选择词库" : "&a&lSelect Word Bank");
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        int slot = 10;
        for (String bankName : availableBanks) {
            if (slot > 16) break;
            String displayName = getDisplayName(bankName, lang);
            inv.setItem(slot, createItem(Material.BOOK,
                    ChatColor.translateAlternateColorCodes('&', "&e" + displayName),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            "zh".equalsIgnoreCase(lang) ? "&7点击选择此词库开始答题" : "&7Click to select this word bank"))));
            slot++;
        }

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&c" +
                messages.getString("gui.back-button", "Back to Menu"));
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore", "Return to the main menu")))));

        player.openInventory(inv);
    }

    private String getDisplayName(String bankKey, String lang) {
        switch (bankKey.toLowerCase()) {
            case "beginner":
                return "zh".equals(lang) ? "初级" : "Beginner";
            case "intermediate":
                return "zh".equals(lang) ? "中级" : "Intermediate";
            case "advanced":
                return "zh".equals(lang) ? "高级" : "Advanced";
            default:
                String[] parts = bankKey.split("_");
                StringBuilder sb = new StringBuilder();
                for (String p : parts) {
                    if (p.length() > 0)
                        sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
                }
                return sb.toString().trim();
        }
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
