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
                messages.getString("word-bank.title"));
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        String clickHint = ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-bank.click-hint"));

        int slot = 10;
        for (String bankName : availableBanks) {
            if (slot > 16) break;
            String displayName = getDisplayName(bankName, messages);
            inv.setItem(slot, createItem(Material.BOOK,
                    ChatColor.translateAlternateColorCodes('&', "&e" + displayName),
                    Collections.singletonList(clickHint)));
            slot++;
        }

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&c" +
                messages.getString("gui.back-button"));
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore")))));

        player.openInventory(inv);
    }

    /**
     * 词库显示名：先查 messages 里的 {@code word-bank.difficulty-<key>}，
     * 缺失时回退到「首字母大写化」的 key（纯装饰性兜底，不构成硬编码文案）。
     */
    private String getDisplayName(String bankKey, FileConfiguration messages) {
        String lower = bankKey.toLowerCase();
        String key = "word-bank.difficulty-" + lower;
        String configured = messages.getString(key);
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        String[] parts = bankKey.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.length() > 0)
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
        }
        return sb.toString().trim();
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
