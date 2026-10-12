package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeProgress;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager.ChallengeType;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
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
 *   Row 3 (18-26): word-challenge visibility line (FIX-CHAL-01, slot 22)
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
                MessageUtil.getGuiMsg(messages, "daily.title"));
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
                status = MessageUtil.getGuiMsg(messages, "daily.claimed");
            } else if (cp.completed) {
                mat = Material.GOLD_INGOT;
                status = MessageUtil.getGuiMsg(messages, "daily.completed");
            } else {
                mat = Material.PAPER;
                status = MessageUtil.getGuiMsg(messages, "daily.in-progress");
            }

            String desc = MessageUtil.getGuiMsg(messages, type.descKey)
                    .replace("{target}", String.valueOf(type.target));
            // S7：展示层 clamp —— 已完成挑战的 progress 在写入侧仍可能继续累加（写入侧修复在 S5 流），
            // 这里显示 Math.min(progress, target)，不再出现 47/10；target<=0 时原样显示（本渲染路径无除法，不涉除零）。
            int displayProgress = type.target > 0 ? Math.min(cp.progress, type.target) : cp.progress;
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7" + desc));
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "daily.progress")
                            .replace("{progress}", String.valueOf(displayProgress))
                            .replace("{target}", String.valueOf(type.target))));
            lore.add(ChatColor.translateAlternateColorCodes('&', status));
            if (!cp.claimed) {
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily.reward-hint")
                                .replace("{reward}", String.valueOf((int) type.reward))));
            }

            inv.setItem(slots[i], createItem(mat,
                    ChatColor.translateAlternateColorCodes('&', "&e" + getChallengeName(type, messages)),
                    lore));
        }

        // Back button
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        // FIX-CHAL-01: cross-visibility (read-only) — word-challenge progress
        // line at slot 22 (mirrors ChallengeCompleteGUI's center-bottom slot).
        // Clicking does nothing (see MiscGUIListener.handleDailyChallenge);
        // words are completed via learning, not claimed here.
        int[] wordProgress = plugin.getDailyChallengeManager().getTodayWordProgress(player);
        int wordDone = wordProgress[0];
        int wordTotal = wordProgress[1];
        Material wordMat = (wordTotal > 0 && wordDone >= wordTotal)
                ? Material.LIME_DYE : Material.BOOK;
        List<String> wordLore = new ArrayList<>();
        wordLore.add(ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "daily.word-progress")
                        .replace("{progress}", String.valueOf(wordDone))
                        .replace("{total}", String.valueOf(wordTotal))));
        wordLore.add(ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "daily.word-hint")));
        if (wordTotal > 0 && wordDone >= wordTotal) {
            wordLore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "daily.word-done")));
        }
        inv.setItem(22, createItem(wordMat,
                ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "daily.word-title")),
                wordLore));

        player.openInventory(inv);
    }

    private String getChallengeName(ChallengeType type, FileConfiguration messages) {
        String key = switch (type) {
            case ANSWER_10 -> "daily.name-answer-10";
            case SESSION_3 -> "daily.name-session-3";
            case COMBO_5 -> "daily.name-combo-5";
            case AI_CHAT_5 -> "daily.name-ai-chat-5";
        };
        return ChatColor.translateAlternateColorCodes('&', MessageUtil.getGuiMsg(messages, key));
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
