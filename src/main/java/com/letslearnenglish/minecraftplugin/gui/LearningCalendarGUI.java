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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Study History GUI — GitHub contribution graph style.
 *
 * Layout (54 slots, 6 rows × 9 cols):
 *   Row 0 (0-8):  Day-of-week labels at slots 1-7, Back button at slot 8
 *   Rows 1-5 (9-53): Calendar grid
 *     Columns 1-7 (slots 10-16, 19-25, 28-34, 37-43, 46-52): data cells
 *     Column 8 (slots 17, 26, 35, 44): color legend (vertical, right side)
 *
 * Day-of-week alignment:
 *   Column 1 = Monday, Column 2 = Tuesday, ..., Column 7 = Sunday
 *   Each cell is placed in the correct day-of-week column so the
 *   calendar always aligns properly regardless of the start date.
 *
 * Color scheme (darker = less learning):
 *   0 words  → GRAY   (no activity)
 *   1-5      → GREEN  (light learning, darker green)
 *   6-15     → LIME   (moderate learning, brighter green)
 *   16+      → YELLOW (heavy learning, brightest)
 */
public class LearningCalendarGUI {

    private static final int GUI_SIZE = 54;

    /**
     * Data cell slots — column-major: column 0→6 = Monday→Sunday, row 0→4 = oldest→newest week.
     *   Col 0 (Mon): 10, 19, 28, 37, 46
     *   Col 1 (Tue): 11, 20, 29, 38, 47
     *   Col 2 (Wed): 12, 21, 30, 39, 48
     *   Col 3 (Thu): 13, 22, 31, 40, 49
     *   Col 4 (Fri): 14, 23, 32, 41, 50
     *   Col 5 (Sat): 15, 24, 33, 42, 51
     *   Col 6 (Sun): 16, 25, 34, 43, 52
     */
    private static final int[] DAY_SLOTS = {
        10, 19, 28, 37, 46,   // Monday
        11, 20, 29, 38, 47,   // Tuesday
        12, 21, 30, 39, 48,   // Wednesday
        13, 22, 31, 40, 49,   // Thursday
        14, 23, 32, 41, 50,   // Friday
        15, 24, 33, 42, 51,   // Saturday
        16, 25, 34, 43, 52    // Sunday
    };

    /** Legend slots — right side column (col 8, rows 1-4) */
    private static final int[] LEGEND_SLOTS = { 17, 26, 35, 44 };

    private final LetsLearnEnglish plugin;

    public LearningCalendarGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msg = plugin.getConfigManager().getMessageConfig(lang);

        String title = msg.getString("calendar.title", "");
        title = ChatColor.translateAlternateColorCodes('&', title);
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        // ── Day-of-week labels (row 0, slots 1-7) ──
        Material labelMat = Material.WHITE_STAINED_GLASS_PANE;
        String[] labels = getDayLabels(msg);
        for (int d = 0; d < 7; d++) {
            inv.setItem(1 + d, createItem(labelMat,
                    ChatColor.translateAlternateColorCodes('&', "&e&l" + labels[d]),
                    Collections.emptyList()));
        }

        // ── Data ──
        Map<String, Integer> dayData = plugin.getPlayerDataStore().getSessionDays(player.getUniqueId(), 35);
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(34); // 35-day window including today
        // Align grid start to Monday of startDate's week
        LocalDate gridStart = startDate.with(DayOfWeek.MONDAY);

        String noActivity = getCalendarMsg(msg, "legend-no-activity");
        String light = getCalendarMsg(msg, "legend-light");
        String moderate = getCalendarMsg(msg, "legend-moderate");
        String heavy = getCalendarMsg(msg, "legend-heavy");
        String todayMarker = getCalendarMsg(msg, "today-marker");
        String wordsUnit = getCalendarMsg(msg, "words-unit");

        for (int slotIdx = 0; slotIdx < 35; slotIdx++) {
            int slot = DAY_SLOTS[slotIdx];
            int col = slotIdx / 5; // 0=Mon ... 6=Sun
            int row = slotIdx % 5; // 0=oldest week ... 4=newest week

            LocalDate date = gridStart.plusDays(row * 7L + col);

            // Skip dates outside our 35-day window
            if (date.isBefore(startDate) || date.isAfter(today)) {
                continue;
            }

            String dateStr = date.toString();
            int wordsCount = dayData.getOrDefault(dateStr, 0);

            // Color: darker = less learning
            Material mat;
            String levelText;
            if (wordsCount == 0) {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                levelText = noActivity;
            } else if (wordsCount <= 5) {
                mat = Material.GREEN_STAINED_GLASS_PANE;
                levelText = light;
            } else if (wordsCount <= 15) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                levelText = moderate;
            } else {
                mat = Material.YELLOW_STAINED_GLASS_PANE;
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

            inv.setItem(slot, createItem(mat, displayName, lore));
        }

        // ── Color legend — right side column ──
        // Slot 17 (row 1): 0 words — GRAY
        inv.setItem(LEGEND_SLOTS[0], createItem(Material.GRAY_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&70"),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        "&7" + noActivity))));
        // Slot 26 (row 2): 1-5 words — GREEN (darker = less)
        inv.setItem(LEGEND_SLOTS[1], createItem(Material.GREEN_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&21-5"),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        "&7" + light))));
        // Slot 35 (row 3): 6-15 words — LIME (brighter = more)
        inv.setItem(LEGEND_SLOTS[2], createItem(Material.LIME_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&a6-15"),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        "&7" + moderate))));
        // Slot 44 (row 4): 16+ words — YELLOW (brightest = heaviest)
        inv.setItem(LEGEND_SLOTS[3], createItem(Material.YELLOW_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e16+"),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        "&7" + heavy))));

        // ── Back button ──
        String back = getCalendarMsg(msg, "back");
        String backLore = getCalendarMsg(msg, "back-lore");
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&c" + back),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', backLore))));

        // ── Fill remaining empty slots with filler panes to prevent item dragging ──
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int i = 0; i < GUI_SIZE; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }

        player.openInventory(inv);
    }

    private String[] getDayLabels(FileConfiguration msg) {
        String[] defaults = {"", "", "", "", "", "", ""};
        String[] labels = new String[7];
        for (int i = 1; i <= 7; i++) {
            labels[i - 1] = msg.getString("calendar.day-" + i, defaults[i - 1]);
        }
        return labels;
    }

    private String getCalendarMsg(FileConfiguration msg, String key) {
        return ChatColor.translateAlternateColorCodes('&',
                msg.getString("calendar." + key, ""));
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
