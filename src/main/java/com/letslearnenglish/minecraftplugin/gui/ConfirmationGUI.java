package com.letslearnenglish.minecraftplugin.gui;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;

/**
 * Two-step confirmation GUI for language switching.
 *
 * Flow: MainMenu language button → Confirm page 1 → Confirm page 2 → switch
 */
public class ConfirmationGUI {

    private static final Map<UUID, Integer> confirmStages = new ConcurrentHashMap<>();
    public static final String CONFIRM_TITLE_KEY = "language.confirm-title";

    private final LetsLearnEnglish plugin;
    private final String targetLang;

    public ConfirmationGUI(LetsLearnEnglish plugin, String targetLang) {
        this.plugin = plugin;
        this.targetLang = targetLang;
    }

    public static boolean isConfirmGUI(String title) {
        return title.contains("Confirm") || title.contains("确认");
    }

    public static int getStage(Player player) {
        return confirmStages.getOrDefault(player.getUniqueId(), 0);
    }

    public static void resetStage(Player player) {
        confirmStages.remove(player.getUniqueId());
    }

    public void open(Player player) {
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig("en");

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString(CONFIRM_TITLE_KEY, "&eConfirm Language Switch?"));
        String itemName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.confirm-item", "&c&lReally?"));
        String itemLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.confirm-lore", "&7Click again to confirm"));

        confirmStages.put(player.getUniqueId(), 1);

        Inventory inv = Bukkit.createInventory(null, 27, title);
        inv.setItem(13, createConfirmItem(itemName, itemLore));

        player.openInventory(inv);
    }

    public void openSecond(Player player) {
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig("zh");

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString(CONFIRM_TITLE_KEY, "&e确认切换语言？"));
        String itemName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.confirm-item", "&c&l真的吗？"));
        String itemLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.confirm-lore", "&7再次点击确认切换"));

        confirmStages.put(player.getUniqueId(), 2);

        Inventory inv = Bukkit.createInventory(null, 27, title);
        inv.setItem(13, createConfirmItem(itemName, itemLore));

        player.openInventory(inv);
    }

    public void applySwitch(Player player) {
        confirmStages.remove(player.getUniqueId());
        plugin.setPlayerLanguage(player, targetLang);

        String switcMsg = plugin.getMessageUtil().getPlayerMessage(player, "language.switched");
        player.sendMessage(switcMsg);
    }

    private ItemStack createConfirmItem(String name, String lore) {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}