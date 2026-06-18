package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
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

/**
 * Challenge Complete GUI — 27-slot celebration screen shown when
 * a player finishes all daily word challenges.
 *
 * Layout:
 *   Row 1 (0-8):  decorative border + back button (slot 8)
 *   Row 2 (9-17): player head (11), fireworks (13), reward (15)
 *   Row 3 (18-26): claim reward button (22), return to menu (8)
 */
public class ChallengeCompleteGUI {

    private static final int GUI_SIZE = 27;

    private final LetsLearnEnglish plugin;

    public ChallengeCompleteGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, double reward) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("daily-challenge.complete-title", "&6&lChallenge Complete!"));
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        // Fill with decorative glass
        ItemStack glass = createItem(Material.YELLOW_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int i = 0; i < GUI_SIZE; i++) {
            if (i == 8 || i == 11 || i == 13 || i == 15 || i == 22) continue;
            inv.setItem(i, glass);
        }

        // Player head (slot 11)
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            skullMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("daily-challenge.player-head",
                            "&e" + player.getName())));
            List<String> headLore = new ArrayList<>();
            headLore.add(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("daily-challenge.player-head-lore",
                            "&7All daily challenge words completed!")));
            skullMeta.setLore(headLore);
            head.setItemMeta(skullMeta);
        }
        inv.setItem(11, head);

        // Fireworks / celebration item (slot 13)
        ItemStack firework = createItem(Material.FIREWORK_ROCKET,
                ChatColor.translateAlternateColorCodes('&',
                        messages.getString("daily-challenge.firework",
                                "&6&l★ Congratulations! ★")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("daily-challenge.firework-lore",
                                "&7You completed all today's challenge words!"))));
        inv.setItem(13, firework);

        // Reward display (slot 15)
        String rewardStr = String.valueOf((int) reward);
        ItemStack rewardItem = createItem(Material.GOLD_INGOT,
                ChatColor.translateAlternateColorCodes('&',
                        messages.getString("daily-challenge.reward-title",
                                "&eReward: {reward} coins")
                                .replace("{reward}", rewardStr)),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("daily-challenge.reward-lore",
                                "&7Claim your hard-earned reward!"))));
        inv.setItem(15, rewardItem);

        // Claim reward button (slot 22)
        ItemStack claimBtn = createItem(Material.EMERALD,
                ChatColor.translateAlternateColorCodes('&',
                        messages.getString("daily-challenge.claim-button",
                                "&a&lClaim Reward!")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("daily-challenge.claim-button-lore",
                                "&7Click to receive your coins!"))));
        inv.setItem(22, claimBtn);

        // Back button (slot 8)
        ItemStack backBtn = createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&',
                        messages.getString("gui.back-button", "&eBack to Menu")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("gui.back-lore", "&7Return to the main menu"))));
        inv.setItem(8, backBtn);

        SoundManager.playSound(player, "complete");
        player.openInventory(inv);
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