package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.duel.DuelRoom;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.UUID;

/**
 * DuelGUI — 27-slot GUI for the PvP Word Duel.
 *
 * Layout:
 * - Left side (slots 9-12): Player 1 name + score (green stained glass)
 * - Center (slot 13): Current word (Chinese)
 * - Right side (slots 14-17): Player 2 name + score (red stained glass)
 * - Bottom center (slot 22): Quit button (barrier)
 *
 * Score visualization: filled wool/glass slots proportional to score.
 */
public class DuelGUI {

    private final LetsLearnEnglish plugin;

    public DuelGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, DuelRoom room) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("duel.gui-title"));

        // Truncate title if too long (max 32 chars for Minecraft)
        if (title.length() > 32) {
            title = title.substring(0, 32);
        }

        Inventory inv = Bukkit.createInventory(null, 27, title);
        buildInventory(inv, player, room, messages);
        player.openInventory(inv);
    }

    private void buildInventory(Inventory inv, Player viewer, DuelRoom room, FileConfiguration messages) {
        inv.clear();

        UUID viewerId = viewer.getUniqueId();
        boolean isPlayer1 = viewerId.equals(room.getPlayer1());

        String p1Name = room.getPlayer1Name();
        String p2Name = room.getPlayer2Name();
        int score1 = room.getScore1();
        int score2 = room.getScore2();

        // --- Player 1 score display (left side, slots 10-12) ---
        Material p1Material;
        if (isPlayer1) {
            p1Material = Material.GREEN_WOOL;
        } else {
            p1Material = Material.GREEN_STAINED_GLASS;
        }

        // Score visualization: fill slots based on score
        int[] p1Slots = {10, 11, 12, 19, 20}; // up to 5 slots
        for (int i = 0; i < DuelRoom.WIN_SCORE; i++) {
            int slot = p1Slots[i];
            Material mat = i < score1 ? Material.GREEN_WOOL : Material.GRAY_STAINED_GLASS;
            String label = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("duel.score-point"));
            inv.setItem(slot, createItem(mat, label));
        }

        // Player 1 info (slot 9)
        String p1Label = ChatColor.translateAlternateColorCodes('&',
                messages.getString("duel.player-score")
                        .replace("{player}", p1Name)
                        .replace("{score}", String.valueOf(score1)));
        inv.setItem(9, createItem(Material.PLAYER_HEAD, p1Label));

        // --- Player 2 score display (right side, slots 14-16) ---
        Material p2Material;
        if (!isPlayer1) {
            p2Material = Material.RED_WOOL;
        } else {
            p2Material = Material.RED_STAINED_GLASS;
        }

        // Score visualization: fill slots based on score
        int[] p2Slots = {14, 15, 16, 23, 24}; // up to 5 slots
        for (int i = 0; i < DuelRoom.WIN_SCORE; i++) {
            int slot = p2Slots[i];
            Material mat = i < score2 ? Material.RED_WOOL : Material.GRAY_STAINED_GLASS;
            String label = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("duel.score-point"));
            inv.setItem(slot, createItem(mat, label));
        }

        // Player 2 info (slot 17)
        String p2Label = ChatColor.translateAlternateColorCodes('&',
                messages.getString("duel.player-score")
                        .replace("{player}", p2Name)
                        .replace("{score}", String.valueOf(score2)));
        String headOwner = p2Name;
        inv.setItem(17, createSkullItem(headOwner, p2Label));

        // --- Current word (center slot 13) ---
        Word currentWord = room.getCurrentWord();
        if (currentWord != null) {
            String wordLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("duel.current-word")
                            .replace("{chinese}", currentWord.getChinese()));
            String wordHint1 = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("duel.type-answer-hint"));
            String wordHint2 = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("duel.first-correct-wins"));
            inv.setItem(13, createItem(Material.BOOK, wordLabel, wordHint1, wordHint2));
        } else {
            String waitLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("duel.waiting-word"));
            inv.setItem(13, createItem(Material.BOOK, waitLabel));
        }

        // --- Quit button (slot 22) ---
        String quitLabel = ChatColor.translateAlternateColorCodes('&',
                messages.getString("duel.quit-button"));
        String quitLore1 = ChatColor.translateAlternateColorCodes('&',
                messages.getString("duel.quit-lore"));
        String quitLore2 = ChatColor.translateAlternateColorCodes('&',
                messages.getString("duel.quit-hint"));
        inv.setItem(22, createItem(Material.BARRIER, quitLabel, quitLore1, quitLore2));
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @SuppressWarnings("deprecation")
    private ItemStack createSkullItem(String owner, String name) {
        // Use PLAYER_HEAD for modern versions, fallback to SKULL_ITEM
        Material skullMaterial;
        try {
            skullMaterial = Material.valueOf("PLAYER_HEAD");
        } catch (IllegalArgumentException e) {
            skullMaterial = Material.valueOf("LEGACY_SKULL_ITEM");
        }
        ItemStack item = new ItemStack(skullMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
}
