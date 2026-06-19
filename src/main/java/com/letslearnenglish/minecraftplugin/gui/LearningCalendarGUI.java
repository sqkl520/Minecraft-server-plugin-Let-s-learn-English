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

import java.time.LocalDate;
import java.util.*;

/**
 * Learning Calendar GUI — GitHub contribution graph style.
 */
public class LearningCalendarGUI {

    private static final int GUI_SIZE = 54;

    private static final int[] DAY_SLOTS = {
        10, 19, 28, 37, 46,
        11, 20, 29, 38, 47,
        12, 21, 30, 39, 48,
        13, 22, 31, 40, 49,
        14, 23, 32, 41, 50,
        15, 24, 33, 42, 51,
        16, 25, 34, 43, 52
    };

    private final LetsLearnEnglish plugin;

    public LearningCalendarGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msg = plugin.getConfigManager().getMessageConfig(lang);

        String title = msg.getString("calendar.title", "&a&lLearning Calendar");
        title = ChatColor.translateAlternateColorCodes('&', title);
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        // Day-of-week labels
        Material labelMat = Material.WHITE_STAINED_GLASS_PANE;
        String[] labels = getDayLabels(msg);
        for (int d = 0; d < 7; d++) {
            inv.setItem(1 + d, createItem(labelMat,
                    ChatColor.translateAlternateColorCodes('&', "&e&l" + labels[d]),
                    Collections.emptyList()));
        }

        Map<String, Integer> dayData = plugin.getPlayerDataStore().getSessionDays(player.getUniqueId(), 35);
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(34);

        String noActivity = getCalendarMsg(msg, lang, "legend-no-activity", "无学习记录", "No activity");
        String light = getCalendarMsg(msg, lang, "legend-light", "轻度学习", "Light");
        String moderate = getCalendarMsg(msg, lang, "legend-moderate", "适度学习", "Moderate");
        String heavy = getCalendarMsg(msg, lang, "legend-heavy", "深入学习", "Heavy");
        String todayMarker = getCalendarMsg(msg, lang, "today-marker", "&e★ 今天", "&e★ Today");
        String wordsUnit = getCalendarMsg(msg, lang, "words-unit", "词", "words");

        for (int i = 0; i < 35; i++) {
            LocalDate date = startDate.plusDays(i);
            String dateStr = date.toString();
            int wordsCount = dayData.getOrDefault(dateStr, 0);

            Material mat;
            String levelText;
            if (wordsCount == 0) {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                levelText = noActivity;
            } else if (wordsCount <= 5) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                levelText = light;
            } else if (wordsCount <= 15) {
                mat = Material.GREEN_STAINED_GLASS_PANE;
                levelText = moderate;
            } else {
                mat = Material.GREEN_STAINED_GLASS_PANE;
                levelText = heavy;
            }

            String displayName = ChatColor.translateAlternateColorCodes('&',
                    "&f" + dateStr + (wordsCount > 0 ? " &a✦" : ""));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    "&7" + levelText + " &f(" + wordsCount + " " + wordsUnit + ")"));

            if (date.equals(today)) {
                lore.add(ChatColor.translateAlternateColorCodes('&', todayMarker));
            }

            inv.setItem(DAY_SLOTS[i], createItem(mat, displayName, lore));
        }

        // Legend
        inv.setItem(45, createItem(Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&70"),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        "&7" + noActivity))));
        inv.setItem(46, createItem(Material.LIME_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&a1-5"),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        "&7" + light))));
        inv.setItem(47, createItem(Material.GREEN_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&26-15"),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        "&7" + moderate))));
        inv.setItem(48, createItem(Material.GREEN_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&216+"),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        "&7" + heavy))));

        // Back button
        String back = getCalendarMsg(msg, lang, "back", "返回", "Back");
        String backLore = getCalendarMsg(msg, lang, "back-lore", "&7返回来源页面", "&7Return to source page");
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&c" + back),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', backLore))));

        player.openInventory(inv);
    }

    private String[] getDayLabels(FileConfiguration msg) {
        String[] defaults = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        String[] labels = new String[7];
        for (int i = 1; i <= 7; i++) {
            labels[i - 1] = msg.getString("calendar.day-" + i, defaults[i - 1]);
        }
        return labels;
    }

    private String getCalendarMsg(FileConfiguration msg, String lang, String key, String zhFallback, String enFallback) {
        String val = msg.getString("calendar." + key);
        if (val != null) {
            return ChatColor.translateAlternateColorCodes('&', val);
        }
        // fallback based on language
        return ChatColor.translateAlternateColorCodes('&', "zh".equalsIgnoreCase(lang) ? zhFallback : enFallback);
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
