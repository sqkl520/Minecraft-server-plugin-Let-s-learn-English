package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
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

import java.util.*;

/**
 * Personal Profile GUI — 27-slot small chest layout.
 *
 * Shows:
 * - Player head with name (top center, slot 4)
 * - Key statistics row (slots 10-15)
 * - Achievement wall button (bottom-left, slot 18)
 * - Settings button (bottom-right, slot 26, only when viewing own profile)
 */
public class ProfileGUI {

    private static final int GUI_SIZE = 27;
    private final LetsLearnEnglish plugin;

    public ProfileGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, UUID targetId) {
        boolean isOwn = viewer.getUniqueId().equals(targetId);
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));
        ConfigManager cm = plugin.getConfigManager();
        String lang = plugin.getPlayerLanguage(viewer);

        PlayerDataStore store = plugin.getPlayerDataStore();
        String targetName = store.getPlayerName(targetId);
        if (targetName == null) {
            Player targetPlayer = Bukkit.getPlayer(targetId);
            targetName = targetPlayer != null ? targetPlayer.getName()
                    : messages.getString("profile.unknown-player", "Unknown");
        }

        String title;
        if (isOwn) {
            title = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("profile.title", "&e{player}'s Profile")
                            .replace("{player}", viewer.getName()));
        } else {
            title = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("profile.viewing-title", "&b{name}'s Profile")
                            .replace("{name}", targetName));
        }

        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);
        PlayerDataStore.PlayerStats stats = store.getPlayerStats(targetId);
        PlayerDataStore.ProfileSettings ps = store.getProfileSettings(targetId);
        AchievementManager am = plugin.getAchievementManager();

        fillBorder(inv);

        // Player head — slot 4
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta hm) {
            hm.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
            hm.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("profile.head-name-color", "&6&l") + targetName));
            head.setItemMeta(hm);
        }
        inv.setItem(4, head);

        // Stats
        int totalWords = stats.getTotalWords();
        int totalCorrect = stats.getTotalCorrect();
        int totalIncorrect = stats.getTotalIncorrect();
        int sessions = stats.getTotalSessions();
        long score = stats.getTotalScore();
        int accuracy = totalWords > 0 ? (int) ((double) totalCorrect / totalWords * 100) : 0;
        int unlockedCount = am.getUnlockedCount(targetId);
        int totalAchievements = Achievement.values().length;

        // Words learned — slot 11
        inv.setItem(11, createInfoItem(Material.BOOK,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("profile.stats-words-label", "Words Learned")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&',
                                messages.getString("profile.stats-words-value", "&f{words}")
                                        .replace("{words}", String.valueOf(totalWords))),
                        ChatColor.translateAlternateColorCodes('&',
                                messages.getString("profile.stats-words-detail",
                                        "&7Correct: {correct} | Wrong: {incorrect}")
                                        .replace("{correct}", String.valueOf(totalCorrect))
                                        .replace("{incorrect}", String.valueOf(totalIncorrect)))
                )));

        // Accuracy — slot 12
        inv.setItem(12, createInfoItem(Material.EXPERIENCE_BOTTLE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("profile.stats-accuracy-label", "Accuracy")),
                Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&',
                                messages.getString("profile.stats-accuracy-value", "&f{accuracy}%")
                                        .replace("{accuracy}", String.valueOf(accuracy)))
                )));

        // Age — slot 13
        int age = ps.getAge();
        inv.setItem(13, createInfoItem(Material.CAKE,
                ChatColor.translateAlternateColorCodes('&',
                        messages.getString("profile.age", "&eAge: &f{age}")
                                .replace("{age}", String.valueOf(age))),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("profile.age-hint", "&7You cannot set your age here~")))));

        // Sessions — slot 14
        inv.setItem(14, createInfoItem(Material.CLOCK,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("profile.stats-sessions-label", "Sessions")),
                Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&',
                                messages.getString("profile.stats-sessions-value", "&f{sessions}")
                                        .replace("{sessions}", String.valueOf(sessions)))
                )));

        // Coins — slot 15 (centered with other stats)
        inv.setItem(15, createInfoItem(Material.GOLD_INGOT,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("profile.stats-coins-label", "Coins")),
                Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&',
                                messages.getString("profile.stats-coins-value", "&f{coins}")
                                        .replace("{coins}", String.valueOf(score)))
                )));

        // Achievement wall button — slot 18 (bottom-left) with count
        String achWallLabel = ChatColor.translateAlternateColorCodes('&',
                "zh".equalsIgnoreCase(lang) ? "&e&l成就墙" : "&e&lAchievement Wall");
        String achWallCount = ChatColor.translateAlternateColorCodes('&',
                "zh".equalsIgnoreCase(lang)
                        ? "&7已解锁: &f" + unlockedCount + "&7/&f" + totalAchievements
                        : "&7Unlocked: &f" + unlockedCount + "&7/&f" + totalAchievements);
        inv.setItem(18, createItem(Material.PAINTING, achWallLabel,
                Arrays.asList(
                        achWallCount,
                        ChatColor.translateAlternateColorCodes('&',
                                "zh".equalsIgnoreCase(lang) ? "&7点击查看全部成就" : "&7Click to view all achievements"))));

        // Back button — slot 8
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("gui.back-button", "Back to Menu")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore", "Return to the main menu")))));

        // Settings button — slot 26 (bottom-right, only own)
        if (isOwn) {
            inv.setItem(26, createItem(Material.COMPARATOR,
                    ChatColor.translateAlternateColorCodes('&',
                            messages.getString("profile.settings-button", "&eSettings")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            messages.getString("profile.settings-lore", "&7Privacy settings")))));
        } else {
            // 别人主页 — 显示灰色玻璃板表示设置不可用
            inv.setItem(26, createItem(Material.GRAY_STAINED_GLASS_PANE,
                    ChatColor.translateAlternateColorCodes('&',
                            messages.getString("profile.settings-locked", "&8Settings (Locked)")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            messages.getString("profile.settings-locked-lore", "&7只能修改自己的设置")))));
        }

        viewer.openInventory(inv);
    }

    /**
     * Open the settings sub-page (privacy toggles).
     */
    public void openSettings(Player viewer) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));
        String lang = plugin.getPlayerLanguage(viewer);
        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("profile.settings-title", "Privacy Settings"));
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        UUID playerId = viewer.getUniqueId();
        PlayerDataStore.ProfileSettings ps = plugin.getPlayerDataStore().getProfileSettings(playerId);

        // Fill border
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        int[] borderSlots = {0,1,2,3,5,6,7,9,17,18,19,20,21,22,23,24,25,26};
        for (int s : borderSlots) inv.setItem(s, border);

        // Player head at top
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta hm) {
            hm.setOwningPlayer(Bukkit.getOfflinePlayer(playerId));
            hm.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&6" + viewer.getName()));
            head.setItemMeta(hm);
        }
        inv.setItem(4, head);

        // Show answers toggle — slot 11
        boolean showAnswersOn = ps.isShowAnswers();
        inv.setItem(11, createItem(
                showAnswersOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("profile.settings-show-answers", "Answers visible to others")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', showAnswersOn
                                ? messages.getString("profile.settings-show-answers-on", "&aCurrent: Visible")
                                : messages.getString("profile.settings-show-answers-off", "&7Current: Hidden")),
                        ChatColor.translateAlternateColorCodes('&',
                                messages.getString("profile.settings-toggle-hint", "&7Click to toggle"))
                )));

        // Show results toggle — slot 13
        boolean showResultsOn = ps.isShowResults();
        inv.setItem(13, createItem(
                showResultsOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("profile.settings-show-results", "Results visible to others")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', showResultsOn
                                ? messages.getString("profile.settings-show-results-on", "&aCurrent: Visible")
                                : messages.getString("profile.settings-show-results-off", "&7Current: Hidden")),
                        ChatColor.translateAlternateColorCodes('&',
                                messages.getString("profile.settings-toggle-hint", "&7Click to toggle"))
                )));

        // Show in square toggle — slot 15
        boolean showInSquareOn = ps.isShowInSquare();
        inv.setItem(15, createItem(
                showInSquareOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("profile.settings-show-in-square", "Visible in English Square")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', showInSquareOn
                                ? messages.getString("profile.settings-show-in-square-on", "&aCurrent: Visible")
                                : messages.getString("profile.settings-show-in-square-off", "&7Current: Hidden")),
                        ChatColor.translateAlternateColorCodes('&',
                                messages.getString("profile.settings-toggle-hint", "&7Click to toggle"))
                )));

        // Back button — slot 8
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        messages.getString("gui.back-button", "Back to Menu")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore", "Return to the main menu")))));

        viewer.openInventory(inv);
    }

    private void fillBorder(Inventory inv) {
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        int[] borderSlots = {0,1,2,3,5,6,7,9,10,16,17,19,20,21,22,23,24,25};
        for (int slot : borderSlots) {
            inv.setItem(slot, border);
        }
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

    private ItemStack createInfoItem(Material material, String name, List<String> lore) {
        return createItem(material, name, lore);
    }
}
