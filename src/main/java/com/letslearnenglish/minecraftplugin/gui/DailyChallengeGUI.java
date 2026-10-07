package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeProgress;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Daily Challenge GUI — shows today's 3 challenges with progress and claim buttons.
 *
 * 27-slot layout:
 *   Row 1 (0-8): header + back button
 *   Row 2 (9-17): 3 challenge slots at 11, 13, 15
 *   Row 3 (18-26): status / claim info
 */
public class DailyChallengeGUI {

    private static final int GUI_SIZE = 27;

    private final LetsLearnEnglish plugin;

    public DailyChallengeGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("daily.title"));
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        List<ChallengeProgress> challenges = plugin.getDailyChallengeManager().getTodayChallenges(player.getUniqueId());

        int[] slots = {11, 13, 15};
        for (int i = 0; i < Math.min(challenges.size(), slots.length); i++) {
            ChallengeProgress cp = challenges.get(i);
            ChallengeType type = cp.type;

            Material mat;
            String status;
            if (cp.claimed) {
                mat = Material.LIME_DYE;
                status = messages.getString("daily.claimed");
            } else if (cp.completed) {
                mat = Material.GOLD_INGOT;
                status = messages.getString("daily.completed");
            } else {
                mat = Material.PAPER;
                status = messages.getString("daily.in-progress");
            }

            String desc = messages.getString(type.descKey)
                    .replace("{target}", String.valueOf(type.target));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7" + desc));
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("daily.progress")
                            .replace("{progress}", String.valueOf(cp.progress))
                            .replace("{target}", String.valueOf(type.target))));
            lore.add(ChatColor.translateAlternateColorCodes('&', status));
            if (!cp.claimed) {
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("daily.reward-hint")
                                .replace("{reward}", String.valueOf((int) type.reward))));
            }

            inv.setItem(slots[i], createItem(mat,
                    ChatColor.translateAlternateColorCodes('&', "&e" + getChallengeName(type, messages)),
                    lore));
        }

        // Back button
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore")))));

        player.openInventory(inv);
    }

    private String getChallengeName(ChallengeType type, FileConfiguration messages) {
        String key = switch (type) {
            case ANSWER_10 -> "daily.name-answer-10";
            case SESSION_3 -> "daily.name-session-3";
            case COMBO_5 -> "daily.name-combo-5";
            case AI_CHAT_5 -> "daily.name-ai-chat-5";
        };
        return ChatColor.translateAlternateColorCodes('&', messages.getString(key));
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
