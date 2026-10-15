package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
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
        // 域 1 新手引导第 3 步登记：打开本人主页（step==2 时）→ 引导完成 +
        // complete 文案/音效 + 默认收书。他人主页不计数、step≠2 时 no-op，
        // 顺序刚性无法跳步（plan-onboarding §1.2）。
        if (isOwn) {
            plugin.getOnboardingManager().onProfileOpened(viewer);
        }
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));

        PlayerDataStore store = plugin.getPlayerDataStore();
        String targetName = store.getPlayerName(targetId);
        if (targetName == null) {
            Player targetPlayer = Bukkit.getPlayer(targetId);
            targetName = targetPlayer != null ? targetPlayer.getName()
                    : MessageUtil.getGuiMsg(messages, "profile.unknown-player");
        }

        String title;
        if (isOwn) {
            title = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.title")
                            .replace("{player}", viewer.getName()));
        } else {
            title = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.viewing-title")
                            .replace("{name}", targetName));
        }

        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);
        PlayerDataStore.PlayerStats stats = store.getPlayerStats(targetId);
        // L-05：展示路径走只读版，缓存缺失不建条目。
        PlayerDataStore.ProfileSettings ps = store.getProfileSettingsReadOnly(targetId);
        AchievementManager am = plugin.getAchievementManager();

        // R-38：他人主页隐私 —— 目标关 showResults 则学习成绩三卡（字数/正确率/场次）
        // 只隐藏数值行；标签行保留。自己主页（isOwn）零改动。
        boolean hideResults = !isOwn && !ps.isShowResults();
        String hiddenValue = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.stats-hidden-value"));
        String ageUnsetLine = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.age-unset"));
        String ageHiddenLine = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.age-hidden"));

        fillBorder(inv);

        // 过往学习按钮 — slot 0 (left-top corner)
        String calendarLabel = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.calendar", ""));
        String calendarLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("main-menu.calendar-lore", ""));
        inv.setItem(0, createItem(Material.CLOCK, calendarLabel,
                Collections.singletonList(calendarLore)));

        // Player head — slot 4
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta hm) {
            hm.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
            hm.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.head-name-color") + targetName));
            head.setItemMeta(hm);
        }
        inv.setItem(4, head);

        // Stats
        int totalWords = stats.getTotalWords();
        int totalCorrect = stats.getTotalCorrect();
        int totalIncorrect = stats.getTotalIncorrect();
        int sessions = stats.getTotalSessions();
        double coins = plugin.getEconomy() == null ? 0.0
                : plugin.getEconomy().getBalance(Bukkit.getOfflinePlayer(targetId));
        // P3-01 修复（Phase 3 规划期发现，编排者即修）：原公式 correct/getTotalWords() 在
        // M10 语义下分子分母同值（getTotalWords()==totalCorrect），正确率恒显 100%。
        // 改为 correct/(correct+incorrect)，与 LLEPapiExpansion 的 %lle_accuracy% 同口径。
        int accuracy = (totalCorrect + totalIncorrect) > 0
                ? (int) ((double) totalCorrect / (totalCorrect + totalIncorrect) * 100)
                : 0;
        int unlockedCount = am.getUnlockedCount(targetId);
        int totalAchievements = Achievement.values().length;

        // Words learned — slot 11 (R-38: 他人关 showResults 时只隐藏数值行，标签保留)
        inv.setItem(11, createInfoItem(Material.BOOK,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-words-label")),
                hideResults
                        ? Collections.singletonList(hiddenValue)
                        : Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.stats-words-value")
                                        .replace("{words}", String.valueOf(totalWords))),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.stats-words-detail")
                                        .replace("{correct}", String.valueOf(totalCorrect))
                                        .replace("{incorrect}", String.valueOf(totalIncorrect)))
                )));

        // Accuracy — slot 12 (R-38)
        inv.setItem(12, createInfoItem(Material.EXPERIENCE_BOTTLE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-accuracy-label")),
                hideResults
                        ? Collections.singletonList(hiddenValue)
                        : Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.stats-accuracy-value")
                                        .replace("{accuracy}", String.valueOf(accuracy)))
                )));

        // Age — slot 13 (R-39: 0/脏数据经 getAge 校验归 0，显示"未设置"；
        // 非本人看他人主页一律不显示年龄，与 showResults 开关无关)
        int age = ps.getAge();
        String ageLine;
        String ageLore;
        if (!isOwn) {
            ageLine = ageHiddenLine;
            ageLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.age-hidden-hint"));
        } else if (age <= 0) {
            ageLine = ageUnsetLine;
            ageLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.age-hint"));
        } else {
            ageLine = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.age")
                            .replace("{age}", String.valueOf(age)));
            ageLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.age-hint"));
        }
        inv.setItem(13, createInfoItem(Material.CAKE, ageLine,
                Collections.singletonList(ageLore)));

        // Sessions — slot 14 (R-38)
        inv.setItem(14, createInfoItem(Material.CLOCK,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-sessions-label")),
                hideResults
                        ? Collections.singletonList(hiddenValue)
                        : Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.stats-sessions-value")
                                        .replace("{sessions}", String.valueOf(sessions)))
                )));

        // Coins — slot 15 (R-38: 金币余额非本人一律只隐藏数值行，标签保留)
        String coinsLine = isOwn
                ? ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "profile.stats-coins-value")
                                .replace("{coins}", plugin.getEconomy() == null ? "0"
                                        : plugin.getEconomy().format(coins)))
                : hiddenValue;
        inv.setItem(15, createInfoItem(Material.GOLD_INGOT,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.stats-coins-label")),
                Collections.singletonList(coinsLine)));

        // Achievement wall button — slot 18 (bottom-left) with count.
        // MC-2 模块门：progress-system/achievements 关闭时按钮不渲染（槽位留空）；
        // 点击侧（SocialListener）另有分支拒绝，渲染与点击同源 gating。
        if (plugin.getConfigManager().isAchievementsEnabled()) {
            String achWallLabel = ChatColor.translateAlternateColorCodes('&', "&e&l" +
                    MessageUtil.getGuiMsg(messages, "profile.stats-achievements-label"));
            String achWallCount = plugin.getMessageUtil().getPlayerMessage(viewer,
                    "profile.stats-achievements-value",
                    "unlocked", String.valueOf(unlockedCount),
                    "total", String.valueOf(totalAchievements));
            String achWallHint = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "profile.achievements-hint"));
            inv.setItem(18, createItem(Material.PAINTING, achWallLabel,
                    Arrays.asList(achWallCount, achWallHint)));
        }

        // Back button — slot 8
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        // Settings button — slot 26 (bottom-right, only own)
        if (isOwn) {
            inv.setItem(26, createItem(Material.COMPARATOR,
                    ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.settings-button")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.settings-lore")))));
        } else {
            // 别人主页 — 显示灰色玻璃板表示设置不可用
            inv.setItem(26, createItem(Material.GRAY_STAINED_GLASS_PANE,
                    ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.settings-locked")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "profile.settings-locked-lore")))));
        }

        viewer.openInventory(inv);
    }

    /**
     * Open the settings sub-page (privacy toggles).
     */
    public void openSettings(Player viewer) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));
        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "profile.settings-title"));
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
                        MessageUtil.getGuiMsg(messages, "profile.settings-show-answers")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', showAnswersOn
                                ? MessageUtil.getGuiMsg(messages, "profile.settings-show-answers-on")
                                : MessageUtil.getGuiMsg(messages, "profile.settings-show-answers-off")),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.settings-toggle-hint"))
                )));

        // Show results toggle — slot 13
        boolean showResultsOn = ps.isShowResults();
        inv.setItem(13, createItem(
                showResultsOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.settings-show-results")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', showResultsOn
                                ? MessageUtil.getGuiMsg(messages, "profile.settings-show-results-on")
                                : MessageUtil.getGuiMsg(messages, "profile.settings-show-results-off")),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.settings-toggle-hint"))
                )));

        // Show in square toggle — slot 15
        boolean showInSquareOn = ps.isShowInSquare();
        inv.setItem(15, createItem(
                showInSquareOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.settings-show-in-square")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', showInSquareOn
                                ? MessageUtil.getGuiMsg(messages, "profile.settings-show-in-square-on")
                                : MessageUtil.getGuiMsg(messages, "profile.settings-show-in-square-off")),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.settings-toggle-hint"))
                )));

        // Sounds toggle — slot 16（Wave 1 3A-4 补建：per-player 音效开关 UI 入口，
        // SoundManager.isPlayerSoundEnabled 门早已接线；plan-audio-feedback §2.2 语义：
        // 关闭后全部 SoundManager 播放静音，ActionBar/BossBar 视觉保留）
        boolean soundsOn = ps.isSoundsEnabled();
        inv.setItem(16, createItem(
                soundsOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "profile.settings-sounds")),
                Arrays.asList(
                        ChatColor.translateAlternateColorCodes('&', soundsOn
                                ? MessageUtil.getGuiMsg(messages, "profile.settings-sounds-on")
                                : MessageUtil.getGuiMsg(messages, "profile.settings-sounds-off")),
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "profile.settings-toggle-hint"))
                )));

        // Back button — slot 8
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        viewer.openInventory(inv);
    }

    private void fillBorder(Inventory inv) {
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        int[] borderSlots = {1,2,3,5,6,7,9,10,16,17,19,20,21,22,23,24,25};
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
