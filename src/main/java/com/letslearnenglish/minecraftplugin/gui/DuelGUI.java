package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.duel.DuelRoom;
import com.letslearnenglish.minecraftplugin.core.word.Word;
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

    /** 打开界面时会把标题截断到这个长度（Minecraft 标题上限）；标题匹配侧做同样的截断，两处必须一致。 */
    private static final int MAX_TITLE_LENGTH = 32;

    private final LetsLearnEnglish plugin;

    public DuelGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, DuelRoom room) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = truncateTitleSafe(
                MessageUtil.getGuiMsg(messages, "duel.gui-title"));

        Inventory inv = Bukkit.createInventory(null, 27, title);
        buildInventory(inv, room, messages);
        player.openInventory(inv);
    }

    /**
     * R-51: 按可见字符截断标题（Minecraft 标题上限 32）。
     *
     * <p>旧代码先 translate 再 substring：截断点落在 "§x" 中间会劈开颜色码，
     * 客户端显示乱码。新流程：stripColor 取可见文本 → 截 32 → 再 translate，
     * 保证永远不在 § 中间下刀。
     *
     * <p>界面识别侧（本类的 matchesTitle，DuelGUIListener 委托到
     * {@link #isDuelGUITitle}）已同步改用同一截断口径，长标题下 open 后仍能认回自己；
     * 短标题（当前默认配置）下与旧口径输出恒一致。
     *
     * @param rawTitle duel.gui-title 原始值（可含 & 颜色码；缺键时为 [?key] 占位）
     * @return translate 后的最终标题，绝不劈开颜色码
     */
    static String truncateTitleSafe(String rawTitle) {
        if (rawTitle == null) {
            return MessageUtil.getGuiMsg(null, "duel.gui-title");
        }
        String visible = ChatColor.stripColor(
                ChatColor.translateAlternateColorCodes('&', rawTitle));
        if (visible != null && visible.length() > MAX_TITLE_LENGTH) {
            // 可见文本超长：从原始 & 码逐字符走，数满 32 个可见字符即停
            // （&x 颜色码不计入可见长度但原样保留，避免劈开 § 且保留配色）。
            StringBuilder sb = new StringBuilder();
            int visibleCount = 0;
            for (int i = 0; i < rawTitle.length() && visibleCount < MAX_TITLE_LENGTH; i++) {
                char c = rawTitle.charAt(i);
                if (c == '&' && i + 1 < rawTitle.length()) {
                    sb.append(c).append(rawTitle.charAt(i + 1));
                    i++;
                    continue;
                }
                sb.append(c);
                visibleCount++;
            }
            // 尾部若残留孤立的 '&'（本是颜色码开头但码字被截掉），去掉它防乱码
            int len = sb.length();
            if (len > 0 && sb.charAt(len - 1) == '&') {
                sb.deleteCharAt(len - 1);
            }
            rawTitle = sb.toString();
        }
        return ChatColor.translateAlternateColorCodes('&', rawTitle);
    }

    /**
     * R-23: 玩家当前是否正看着对决 GUI。
     *
     * <p>供 DuelManager.refreshBothGUIs 调用：只刷新仍在看界面的玩家，
     * ESC 关掉界面的玩家不再被每题强制弹回，靠 /le duel gui 手动回来。
     * 必须在主线程调用（读 openInventory）。
     */
    public static boolean isViewingDuelGUI(LetsLearnEnglish plugin, Player player) {
        if (plugin == null || player == null) return false;
        return isDuelGUITitle(plugin, player, player.getOpenInventory().getTitle());
    }

    /**
     * R-23: 对决界面标题匹配（唯一真相源，DuelGUIListener 委托到这里）。
     *
     * <p>标题取自 duel.gui-title，中英文案不同（中文「单词对决」/ 英文「Word Duel」），
     * 因此同时接受玩家当前语言与中英两种配置标题；配置读不到时回退到关键词匹配。
     */
    public static boolean isDuelGUITitle(LetsLearnEnglish plugin, Player player, String title) {
        if (plugin == null || player == null || title == null) return false;

        if (matchesTitle(title, getConfiguredTitle(plugin, plugin.getPlayerLanguage(player)))) return true;
        if (matchesTitle(title, getConfiguredTitle(plugin, "zh"))
                || matchesTitle(title, getConfiguredTitle(plugin, "en"))) {
            return true;
        }

        return title.contains("Word Duel") || title.contains("单词对决");
    }

    /**
     * 读取配置中的对决标题原始值（& 颜色码，未 translate）；未配置时返回 null。
     * R-51：截断 + translate 统一走 truncateTitleSafe，与 open() 同口径。
     */
    private static String getConfiguredTitle(LetsLearnEnglish plugin, String lang) {
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        String raw = messages == null ? null : messages.getString("duel.gui-title");
        if (raw == null || raw.isEmpty()) return null;
        return raw;
    }

    /**
     * R-51：与 open() 同口径截断后做前缀比对（open() 打开界面时会把标题截断到 32 个
     * 可见字符，因此比对前做同样的截断，否则长标题会被误判为非对决界面）。
     * 短标题下与旧 substring 口径输出恒一致。
     */
    private static boolean matchesTitle(String title, String expectedRaw) {
        if (expectedRaw == null) return false;
        return title.startsWith(truncateTitleSafe(expectedRaw));
    }

    private void buildInventory(Inventory inv, DuelRoom room, FileConfiguration messages) {
        inv.clear();

        // R5V-1：灰色玻璃铺满全部槽位 —— 根因封堵 shift-click 塞入（原版无落点），
        // 保持与 DuelChallengeGUI 全格填充同模式（该界面因此天然免疫）。
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler.clone());
        }

        String p1Name = room.getPlayer1Name();
        String p2Name = room.getPlayer2Name();
        int score1 = room.getScore1();
        int score2 = room.getScore2();

        // --- Player 1 score display (left side, slots 10-12) ---
        // Score visualization: fill slots based on score
        int[] p1Slots = {10, 11, 12, 19, 20}; // up to 5 slots
        for (int i = 0; i < DuelRoom.WIN_SCORE; i++) {
            int slot = p1Slots[i];
            Material mat = i < score1 ? Material.GREEN_WOOL : Material.GRAY_STAINED_GLASS;
            String label = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "duel.score-point"));
            inv.setItem(slot, createItem(mat, label));
        }

        // Player 1 info (slot 9)
        String p1Label = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "duel.player-score")
                        .replace("{player}", p1Name)
                        .replace("{score}", String.valueOf(score1)));
        inv.setItem(9, createSkullItem(room.getPlayer1(), p1Label));

        // --- Player 2 score display (right side, slots 14-16) ---
        // Score visualization: fill slots based on score
        int[] p2Slots = {14, 15, 16, 23, 24}; // up to 5 slots
        for (int i = 0; i < DuelRoom.WIN_SCORE; i++) {
            int slot = p2Slots[i];
            Material mat = i < score2 ? Material.RED_WOOL : Material.GRAY_STAINED_GLASS;
            String label = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "duel.score-point"));
            inv.setItem(slot, createItem(mat, label));
        }

        // Player 2 info (slot 17)
        String p2Label = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "duel.player-score")
                        .replace("{player}", p2Name)
                        .replace("{score}", String.valueOf(score2)));
        inv.setItem(17, createSkullItem(room.getPlayer2(), p2Label));

        // --- Current word (center slot 13) ---
        Word currentWord = room.getCurrentWord();
        if (currentWord != null) {
            String wordLabel = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "duel.current-word")
                            .replace("{chinese}", currentWord.getChinese()));
            String wordHint1 = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "duel.type-answer-hint"));
            String wordHint2 = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "duel.first-correct-wins"));
            // R-24: 赛前警告 —— 决斗中所有聊天都算作答案。
            String wordHint3 = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "duel.chat-counts-warning"));
            inv.setItem(13, createItem(Material.BOOK, wordLabel, wordHint1, wordHint2, wordHint3));
        } else {
            String waitLabel = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "duel.waiting-word"));
            inv.setItem(13, createItem(Material.BOOK, waitLabel));
        }

        // --- Quit button (slot 22) ---
        String quitLabel = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "duel.quit-button"));
        String quitLore1 = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "duel.quit-lore"));
        String quitLore2 = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "duel.quit-hint"));
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
    private ItemStack createSkullItem(UUID ownerId, String name) {
        // R-70: 头像必须带上皮肤 —— 旧实现只塞了 displayName，owner 参数算而不用，
        // 双方头像都是无主 Steve/Alex。与 ProfileGUI/EnglishSquareGUI 同款写法：
        // PLAYER_HEAD + SkullMeta#setOwningPlayer。调用发生在主线程（GUI open），
        // Bukkit.getOfflinePlayer 主线程调用安全。
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerId));
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
}
