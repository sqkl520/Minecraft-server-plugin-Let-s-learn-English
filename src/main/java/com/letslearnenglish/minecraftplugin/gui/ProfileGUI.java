package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
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
 * Personal Profile GUI — replaces the old ProgressGUI.
 *
 * Shows:
 * - Player head with name (top center)
 * - Age (read-only, set by other plugins via API, default 0)
 * - Key statistics (words learned, accuracy, sessions, coins)
 * - Achievements showcase (unlocked + locked preview)
 * - Settings page (only when viewing own profile)
 *
 * When viewing another player's profile, settings are hidden and only
 * the stats + achievements are shown.
 */
public class ProfileGUI {

    private static final int GUI_SIZE = 54;
    private final LetsLearnEnglish plugin;

    public ProfileGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the profile overview (stats + achievements) page.
     */
    public void open(Player viewer, UUID targetId) {
        boolean isOwn = viewer.getUniqueId().equals(targetId);
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("profile.title", "Personal Profile"));
        if (!isOwn) {
            String targetName = "Unknown";
            Player targetPlayer = Bukkit.getPlayer(targetId);
            if (targetPlayer != null) targetName = targetPlayer.getName();
            else {
                String dbName = plugin.getPlayerDataStore().getPlayerName(targetId);
                if (dbName != null) targetName = dbName;
            }
            String viewTitle = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("profile.viewing-title", "&b{name}'s Profile")
                            .replace("{name}", targetName));
            title = viewTitle;
        }

        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);
        String lang = plugin.getPlayerLanguage(viewer);
        PlayerDataStore store = plugin.getPlayerDataStore();
        PlayerDataStore.PlayerStats stats = store.getPlayerStats(targetId);
        PlayerDataStore.ProfileSettings ps = store.getProfileSettings(targetId);
        AchievementManager am = plugin.getAchievementManager();

        fillBorder(inv);

        // Player head — slot 4
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta hm = (SkullMeta) head.getItemMeta();
        hm.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
        String headName = "Unknown";
        Player targetPlayer = Bukkit.getPlayer(targetId);
        if (targetPlayer != null) headName = targetPlayer.getName();
        else {
            String dbName = store.getPlayerName(targetId);
            if (dbName != null) headName = dbName;
        }
        hm.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&6&l" + headName));
        head.setItemMeta(hm);
        inv.setItem(4, head);

        // Age — slot 22 (read-only, clicking tells player they cannot change it here)
        int age = ps.getAge();
        String ageLabel = ChatColor.translateAlternateColorCodes('&',
                messages.getString("profile.age", "&eAge: &f{age} years old")
                        .replace("{age}", String.valueOf(age)));
        List<String> ageLore = new ArrayList<>();
        ageLore.add(ChatColor.translateAlternateColorCodes('&',
                "zh".equalsIgnoreCase(lang) ? "&7不可以在这里设置年龄哦~" : "&7You cannot set your age here~"));
        inv.setItem(22, createItem(Material.PAPER, ageLabel, ageLore));

        // Stats row — slots 19-25 (using key positions)
        int totalWords = stats.getTotalWords();
        int totalCorrect = stats.getTotalCorrect();
        int totalIncorrect = stats.getTotalIncorrect();
        int sessions = stats.getTotalSessions();
        long score = stats.getTotalScore();
        int accuracy = totalWords > 0 ? (int) ((double) totalCorrect / totalWords * 100) : 0;

        int unlockedCount = am.getUnlockedCount(targetId);
        int totalAchievements = Achievement.values().length;

        // Words learned — slot 20
        String wordsKey = "zh".equalsIgnoreCase(lang) ? "已学单词" : "Words Learned";
        inv.setItem(20, createInfoItem(Material.BOOK,
                ChatColor.translateAlternateColorCodes('&', "&e" + wordsKey),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', "&f" + totalWords),
                        ChatColor.translateAlternateColorCodes('&',
                                "&7" + (lang.equals("zh") ? "答对: " + totalCorrect + " | 答错: " + totalIncorrect
                                        : "Correct: " + totalCorrect + " | Wrong: " + totalIncorrect))
                )));

        // Accuracy — slot 21
        String accKey = "zh".equalsIgnoreCase(lang) ? "正确率" : "Accuracy";
        inv.setItem(21, createInfoItem(Material.EXPERIENCE_BOTTLE,
                ChatColor.translateAlternateColorCodes('&', "&e" + accKey),
                Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&', "&f" + accuracy + "%")
                )));

        // Sessions — slot 23
        String sessKey = "zh".equalsIgnoreCase(lang) ? "学习会话" : "Sessions";
        inv.setItem(23, createInfoItem(Material.CLOCK,
                ChatColor.translateAlternateColorCodes('&', "&e" + sessKey),
                Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&', "&f" + String.valueOf(sessions))
                )));

        // Coins — slot 24
        String coinKey = "zh".equalsIgnoreCase(lang) ? "金币" : "Coins";
        inv.setItem(24, createInfoItem(Material.GOLD_INGOT,
                ChatColor.translateAlternateColorCodes('&', "&e" + coinKey),
                Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&', "&f" + String.valueOf(score))
                )));

        // Achievements — slot 25
        String achKey = "zh".equalsIgnoreCase(lang) ? "成就" : "Achievements";
        inv.setItem(25, createInfoItem(Material.DIAMOND,
                ChatColor.translateAlternateColorCodes('&', "&e" + achKey),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&',
                                "&f" + unlockedCount + "/" + totalAchievements),
                        ChatColor.translateAlternateColorCodes('&',
                                "zh".equalsIgnoreCase(lang) ? "&7点击查看成就列表" : "&7Click to view achievements")
                )));

        // Achievements showcase — slots 28-34 (7 per row)
        Set<Achievement> unlocked = am.getUnlockedAchievements(targetId);
        int slot = 28;
        int shown = 0;
        for (Achievement a : unlocked) {
            if (shown >= 14) break;
            Material mat = Material.GREEN_STAINED_GLASS_PANE;
            inv.setItem(slot, createInfoItem(mat,
                    ChatColor.translateAlternateColorCodes('&', "&a" + a.getDisplayName(lang)),
                    Arrays.asList(
                            ChatColor.translateAlternateColorCodes('&', "&7" + a.getDisplayDesc(lang)),
                            ChatColor.translateAlternateColorCodes('&',
                                    "zh".equalsIgnoreCase(lang) ? "&6✔ 已解锁" : "&6✔ Unlocked")
                    )));
            slot++;
            shown++;
            if (slot == 35 && shown == 7) slot = 37;
        }

        // Locked achievement preview
        for (Achievement a : Achievement.values()) {
            if (shown >= 14) break;
            if (unlocked.contains(a)) continue;
            Material mat = Material.GRAY_STAINED_GLASS_PANE;
            inv.setItem(slot, createInfoItem(mat,
                    ChatColor.translateAlternateColorCodes('&', "&8&m" + a.getDisplayName(lang)),
                    Arrays.asList(
                            ChatColor.translateAlternateColorCodes('&', "&7" + a.getDisplayDesc(lang)),
                            ChatColor.translateAlternateColorCodes('&',
                                    "zh".equalsIgnoreCase(lang) ? "&8🔒 未解锁" : "&8🔒 Locked")
                    )));
            slot++;
            shown++;
            if (slot == 35 && shown <= 7) slot = 37;
            if (slot > 44) break;
        }

        // Back button — slot 49
        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                messages.getString("gui.back-button", "Back to Menu"));
        inv.setItem(49, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore", "Return to the main menu")))));

        // Settings button — slot 51 (only when viewing own profile)
        if (isOwn) {
            String settingsLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("profile.settings-button", "&eSettings"));
            inv.setItem(51, createItem(Material.COMPARATOR, settingsLabel,
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            "zh".equalsIgnoreCase(lang) ? "&7隐私设置" : "&7Privacy Settings"))));
        }

        viewer.openInventory(inv);
    }

    /**
     * Open the settings sub-page (privacy toggles).
     * Only callable when viewing own profile.
     */
    public void openSettings(Player viewer) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));
        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("profile.settings-title", "Privacy Settings"));
        Inventory inv = Bukkit.createInventory(null, 27, title);

        String lang = plugin.getPlayerLanguage(viewer);
        UUID playerId = viewer.getUniqueId();
        PlayerDataStore.ProfileSettings ps = plugin.getPlayerDataStore().getProfileSettings(playerId);

        // Show answers toggle — slot 11
        Material answersMat = ps.isShowAnswers() ? Material.LIME_DYE : Material.GRAY_DYE;
        String answersKey = "zh".equalsIgnoreCase(lang) ? "答题记录对他人可见" : "Answers visible to others";
        String answersStatus = ps.isShowAnswers()
                ? (lang.equals("zh") ? "&a当前: 可见" : "&aCurrent: Visible")
                : (lang.equals("zh") ? "&7当前: 隐藏" : "&7Current: Hidden");
        inv.setItem(11, createItem(answersMat,
                ChatColor.translateAlternateColorCodes('&', "&e" + answersKey),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', answersStatus),
                        ChatColor.translateAlternateColorCodes('&',
                                lang.equals("zh") ? "&7点击切换" : "&7Click to toggle")
                )));

        // Show results toggle — slot 13
        Material resultsMat = ps.isShowResults() ? Material.LIME_DYE : Material.GRAY_DYE;
        String resultsKey = "zh".equalsIgnoreCase(lang) ? "对错结果对他人可见" : "Results visible to others";
        String resultsStatus = ps.isShowResults()
                ? (lang.equals("zh") ? "&a当前: 可见" : "&aCurrent: Visible")
                : (lang.equals("zh") ? "&7当前: 隐藏" : "&7Current: Hidden");
        inv.setItem(13, createItem(resultsMat,
                ChatColor.translateAlternateColorCodes('&', "&e" + resultsKey),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', resultsStatus),
                        ChatColor.translateAlternateColorCodes('&',
                                lang.equals("zh") ? "&7点击切换" : "&7Click to toggle")
                )));

        // Show in square toggle — slot 15
        Material squareMat = ps.isShowInSquare() ? Material.LIME_DYE : Material.GRAY_DYE;
        String squareKey = "zh".equalsIgnoreCase(lang) ? "英语广场中可见" : "Visible in English Square";
        String squareStatus = ps.isShowInSquare()
                ? (lang.equals("zh") ? "&a当前: 可见" : "&aCurrent: Visible")
                : (lang.equals("zh") ? "&7当前: 隐藏" : "&7Current: Hidden");
        inv.setItem(15, createItem(squareMat,
                ChatColor.translateAlternateColorCodes('&', "&e" + squareKey),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', squareStatus),
                        ChatColor.translateAlternateColorCodes('&',
                                lang.equals("zh") ? "&7点击切换" : "&7Click to toggle")
                )));

        // Back button — slot 8
        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                messages.getString("gui.back-button", "Back to Menu"));
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("gui.back-lore", "Return to the main menu")))));

        viewer.openInventory(inv);
    }

    private void fillBorder(Inventory inv) {
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        int[] borderSlots = {0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 50, 52, 53};
        for (int slot : borderSlots) {
            inv.setItem(slot, border);
        }
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createInfoItem(Material material, String name, List<String> lore) {
        return createItem(material, name, lore);
    }
}