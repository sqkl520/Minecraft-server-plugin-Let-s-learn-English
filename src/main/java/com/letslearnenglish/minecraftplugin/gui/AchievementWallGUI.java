package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class AchievementWallGUI {

    private static final int GUI_SIZE = 54;
    private final LetsLearnEnglish plugin;

    public AchievementWallGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, UUID targetId) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));
        AchievementManager am = plugin.getAchievementManager();
        Set<Achievement> unlocked = am.getUnlockedAchievements(targetId);
        String lang = plugin.getPlayerLanguage(viewer);

        String title = ChatColor.translateAlternateColorCodes('&',
                "zh".equalsIgnoreCase(lang) ? "&6&l成就墙" : "&6&lAchievement Wall");
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        FileConfiguration achConfig = loadAchievementConfig();

        ConfigManager cm = plugin.getConfigManager();

        int slot = 10;
        int col = 0;
        Material unlockedMat = cm.getGuiMaterial("achievement-wall", "unlocked-material", Material.GREEN_STAINED_GLASS_PANE);
        Material lockedMat = cm.getGuiMaterial("achievement-wall", "locked-material", Material.GRAY_STAINED_GLASS_PANE);
        for (Achievement a : Achievement.values()) {
            if (!isAchievementEnabled(achConfig, a)) continue;

            boolean isUnlocked = unlocked.contains(a);
            Material mat = isUnlocked ? unlockedMat : lockedMat;
            String name = isUnlocked
                    ? ChatColor.translateAlternateColorCodes('&', "&a" + a.getDisplayName(lang))
                    : ChatColor.translateAlternateColorCodes('&', "&8&m" + a.getDisplayName(lang));

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7" + a.getDisplayDesc(lang)));

            if (isUnlocked) {
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        "zh".equalsIgnoreCase(lang) ? "&6✔ 已解锁" : "&6✔ Unlocked"));
            } else {
                String timeLeft = getTimeLeftString(achConfig, a, lang);
                if (timeLeft != null && !timeLeft.isEmpty()) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', timeLeft));
                }
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        "zh".equalsIgnoreCase(lang) ? "&8🔒 未解锁" : "&8🔒 Locked"));
            }

            inv.setItem(slot, createItem(mat, name, lore));

            slot++;
            col++;
            if (col == 7) { col = 0; slot += 2; }
            if (slot > 43) break;
        }

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                messages.getString("gui.back-button", "Back to Menu"));
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore", "Return to the main menu")))));

        viewer.openInventory(inv);
    }

    private FileConfiguration loadAchievementConfig() {
        File file = new File(plugin.getDataFolder(), "achievements-config.yml");
        if (!file.exists()) {
            plugin.saveResource("achievements-config.yml", false);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private boolean isAchievementEnabled(FileConfiguration config, Achievement a) {
        if (config == null) return true;
        return config.getBoolean("achievements." + a.getId() + ".enabled", true);
    }

    private String getTimeLeftString(FileConfiguration config, Achievement a, String lang) {
        if (config == null) return null;
        if (!config.getBoolean("achievements." + a.getId() + ".time-limit.enabled", false)) return null;

        String startStr = config.getString("achievements." + a.getId() + ".time-limit.start");
        String endStr = config.getString("achievements." + a.getId() + ".time-limit.end");
        if (startStr == null || endStr == null) return null;

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            Date start = sdf.parse(startStr);
            Date end = sdf.parse(endStr);
            Date now = new Date();

            if (now.before(start)) {
                long diff = start.getTime() - now.getTime();
                return "zh".equalsIgnoreCase(lang)
                        ? "&7⏳ 距开放: " + formatDuration(diff)
                        : "&7⏳ Opens in: " + formatDuration(diff);
            }
            if (now.after(end)) {
                return "zh".equalsIgnoreCase(lang)
                        ? "&7⏰ 已过期" : "&7⏰ Expired";
            }
            long diff = end.getTime() - now.getTime();
            return "zh".equalsIgnoreCase(lang)
                    ? "&7⏳ 剩余: " + formatDuration(diff)
                    : "&7⏳ Remaining: " + formatDuration(diff);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatDuration(long millis) {
        long days = millis / 86400000;
        long hours = (millis % 86400000) / 3600000;
        long minutes = (millis % 3600000) / 60000;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
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
