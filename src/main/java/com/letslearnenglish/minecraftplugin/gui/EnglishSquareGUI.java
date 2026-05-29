package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
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
 * English Square GUI — paginated list of all active players.
 *
 * Shows every player who has ever answered at least one word.
 * Players who set "show_in_square = false" will appear in the list by name,
 * but clicking them opens a joke screen ("throwing invisibility potions")
 * instead of their profile.
 */
public class EnglishSquareGUI {

    private static final int GUI_SIZE = 54;
    private static final int PLAYERS_PER_PAGE = 45;
    private final LetsLearnEnglish plugin;

    public EnglishSquareGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, int page) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));

        PlayerDataStore store = plugin.getPlayerDataStore();
        List<UUID> allPlayerIds = store.getActivePlayerIds();

        int totalPages = Math.max(1, (int) Math.ceil((double) allPlayerIds.size() / PLAYERS_PER_PAGE));
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("square.title", "English Square"));
        title = title + " " + ChatColor.GRAY + "| Page " + page + "/" + totalPages;
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        int startIdx = (page - 1) * PLAYERS_PER_PAGE;
        int endIdx = Math.min(startIdx + PLAYERS_PER_PAGE, allPlayerIds.size());

        String lang = plugin.getPlayerLanguage(viewer);

        for (int i = startIdx; i < endIdx; i++) {
            UUID targetId = allPlayerIds.get(i);
            String targetName = store.getPlayerName(targetId);
            if (targetName == null) targetName = "Unknown";

            PlayerDataStore.ProfileSettings ps = store.getProfileSettings(targetId);
            PlayerDataStore.PlayerStats stats = store.getPlayerStats(targetId);

            String zhInfo = "&7答对: &a{correct} &7答错: &c{incorrect} &7会话: &e{sessions}";
            String enInfo = "&7Correct: &a{correct} &7Wrong: &c{incorrect} &7Sessions: &e{sessions}";
            String infoTemplate = "zh".equalsIgnoreCase(lang) ? zhInfo : enInfo;
            String loreInfo = ChatColor.translateAlternateColorCodes('&',
                    infoTemplate
                            .replace("{correct}", String.valueOf(stats.getTotalCorrect()))
                            .replace("{incorrect}", String.valueOf(stats.getTotalIncorrect()))
                            .replace("{sessions}", String.valueOf(stats.getTotalSessions())));

            String clickHint = ChatColor.translateAlternateColorCodes('&',
                    "zh".equalsIgnoreCase(lang) ? "&7点击查看个人主页" : "&7Click to view profile");

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b" + targetName));
            List<String> lore = new ArrayList<>();
            lore.add(loreInfo);
            lore.add("");
            lore.add(clickHint);
            meta.setLore(lore);
            head.setItemMeta(meta);

            inv.setItem(i - startIdx, head);
        }

        if (page < totalPages) {
            String nextLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("square.next-page", "&eNext Page →"));
            inv.setItem(50, createItem(Material.ARROW, nextLabel,
                    Collections.singletonList(ChatColor.GRAY + "Page " + (page + 1) + "/" + totalPages)));
        }
        if (page > 1) {
            String prevLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("square.prev-page", "&e← Previous Page"));
            inv.setItem(48, createItem(Material.ARROW, prevLabel,
                    Collections.singletonList(ChatColor.GRAY + "Page " + (page - 1) + "/" + totalPages)));
        }

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                messages.getString("gui.back-button", "Back to Menu"));
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore", "Return to the main menu")))));

        String emptyLabel = ChatColor.translateAlternateColorCodes('&',
                "zh".equalsIgnoreCase(lang) ? "&7还没有人答过题" : "&7No one has answered yet");
        if (allPlayerIds.isEmpty()) {
            inv.setItem(22, createItem(Material.BOOK, emptyLabel, Collections.emptyList()));
        }

        viewer.openInventory(inv);
    }

    /**
     * Show the "hidden" joke screen when a player hides their profile.
     */
    public void openHidden(Player viewer, UUID targetId) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));
        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("square.title", "English Square"));
        Inventory inv = Bukkit.createInventory(null, 27, title);

        String jokeName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("square.hidden-title", "&b&k??? &r&b??? &k???"));
        String jokeLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("square.hidden-lore",
                        "&7The player is throwing invisibility splash potions everywhere..."));

        ItemStack potion = new ItemStack(Material.SPLASH_POTION);
        ItemMeta meta = potion.getItemMeta();
        meta.setDisplayName(jokeName);
        meta.setLore(Collections.singletonList(jokeLore));
        potion.setItemMeta(meta);

        inv.setItem(13, potion);

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                messages.getString("gui.back-button", "Back to Menu"));
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore", "Return to the main menu")))));

        viewer.openInventory(inv);
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}