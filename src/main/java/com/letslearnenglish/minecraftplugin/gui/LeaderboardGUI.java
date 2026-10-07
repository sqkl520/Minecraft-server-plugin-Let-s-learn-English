package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Leaderboard GUI — a 54-slot inventory showing top players by coins, words learned, or combo.
 *
 * Row 0: Tab buttons (Coins / Words / Combo) + back button (slot 8)
 * Rows 1-5: Top 10 entries with player heads, rank, name, and score
 */
public class LeaderboardGUI {

    private static final int GUI_SIZE = 54;

    /**
     * Slots for the top 10 entries: 2 per row across rows 1-5.
     * 注意：不得包含 {@link #BACK_SLOT}（slot 8，第一行）—— 否则返回键会被玩家头像覆盖。
     */
    public static final int[] ENTRY_SLOTS = {
        10, 16,  // Rank 1, 2
        19, 25,  // Rank 3, 4
        28, 34,  // Rank 5, 6
        37, 43,  // Rank 7, 8
        46, 52   // Rank 9, 10
    };

    /** 三个榜单切换标签页槽位（监听器直接引用，避免两处魔法数字漂移）。 */
    public static final int TAB_COINS_SLOT = 0;
    public static final int TAB_WORDS_SLOT = 1;
    public static final int TAB_COMBO_SLOT = 2;
    /** 返回主菜单按钮 —— 与其余 GUI 统一在右上角 slot 8。 */
    public static final int BACK_SLOT = 8;

    private final LetsLearnEnglish plugin;
    private int activeTab; // 0=coins, 1=words, 2=combo
    private List<UUID> displayedPlayerIds;

    public LeaderboardGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.activeTab = 0;
        this.displayedPlayerIds = new ArrayList<>();
    }

    public int getActiveTab() {
        return activeTab;
    }

    public List<UUID> getDisplayedPlayerIds() {
        return displayedPlayerIds;
    }

    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int tab) {
        this.activeTab = tab;
        ConfigManager cm = plugin.getConfigManager();
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = cm.getMessageConfig(lang);

        List<LeaderboardEntry> entries;
        String subtabLabel;
        switch (tab) {
            case 1:
                entries = plugin.getProgressManager().getLeaderboard().getCachedWordsLeaderboard();
                subtabLabel = messages.getString("leaderboard.tab-words");
                break;
            case 2:
                entries = plugin.getProgressManager().getLeaderboard().getCachedComboLeaderboard();
                subtabLabel = messages.getString("leaderboard.tab-combo");
                break;
            default:
                entries = plugin.getProgressManager().getLeaderboard().getCachedCoinsLeaderboard();
                subtabLabel = messages.getString("leaderboard.tab-coins");
                break;
        }

        this.displayedPlayerIds = new ArrayList<>();
        for (LeaderboardEntry e : entries) {
            displayedPlayerIds.add(e.playerId);
        }

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("leaderboard.title")
                        + " | " + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', subtabLabel)));
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        // ---- Tab buttons (slots 0, 1, 2) ----
        addTabButton(inv, cm, messages, TAB_COINS_SLOT, 0, "tab-coins",
                Material.GOLD_INGOT, "leaderboard.tab-coins");
        addTabButton(inv, cm, messages, TAB_WORDS_SLOT, 1, "tab-words",
                Material.BOOK, "leaderboard.tab-words");
        addTabButton(inv, cm, messages, TAB_COMBO_SLOT, 2, "tab-combo",
                Material.DIAMOND_SWORD, "leaderboard.tab-combo");

        // ---- Top 10 entries ----
        int displayCount = Math.min(entries.size(), ENTRY_SLOTS.length);
        for (int i = 0; i < displayCount; i++) {
            LeaderboardEntry entry = entries.get(i);
            int slot = ENTRY_SLOTS[i];
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.playerId));
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                        "&6#" + entry.rank + " &f" + entry.playerName));
                List<String> lore = new ArrayList<>();
                String scoreLabel;
                switch (tab) {
                    case 1:
                        scoreLabel = messages.getString("leaderboard.entry-words");
                        break;
                    case 2:
                        scoreLabel = messages.getString("leaderboard.entry-combo");
                        break;
                    default:
                        scoreLabel = messages.getString("leaderboard.entry-coins");
                        break;
                }
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        scoreLabel.replace("{score}", String.valueOf(entry.score))));
                lore.add("");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("leaderboard.click-hint")));
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(slot, head);
        }

        // ---- Empty state ----
        if (entries.isEmpty()) {
            String emptyLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("leaderboard.empty"));
            ItemStack emptyItem = createItem(Material.BARRIER, emptyLabel, Collections.emptyList());
            inv.setItem(22, emptyItem);
        }

        // ---- Back button (slot 8) ----
        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                messages.getString("gui.back-button"));
        int backSlot = cm.getGuiSlot("leaderboard", "back-button", BACK_SLOT);
        Material backMaterial = cm.getGuiMaterial("leaderboard", "back-button", Material.RED_STAINED_GLASS_PANE);
        inv.setItem(backSlot, createItem(backMaterial, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore")))));

        player.openInventory(inv);
    }

    private void addTabButton(Inventory inv, ConfigManager cm, FileConfiguration messages,
                              int defaultSlot, int tabIndex, String configKey,
                              Material defaultMaterial, String messageKey) {
        int slot = cm.getGuiSlot("leaderboard", configKey, defaultSlot);
        Material mat = cm.getGuiMaterial("leaderboard", configKey, defaultMaterial);
        boolean selected = (activeTab == tabIndex);
        Material displayMat = selected ? Material.LIME_STAINED_GLASS_PANE : mat;
        String label = ChatColor.translateAlternateColorCodes('&',
                (selected ? "&a&l" : "&7") + messages.getString(messageKey));
        String hint = messages.getString(selected ? "leaderboard.selected" : "leaderboard.switch-hint");
        inv.setItem(slot, createItem(displayMat, label,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', hint))));
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
