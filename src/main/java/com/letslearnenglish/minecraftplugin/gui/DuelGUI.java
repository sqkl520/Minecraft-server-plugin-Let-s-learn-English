package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.duel.DuelRoom;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
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

    /** 界面尺寸（open 的唯一来源，避免魔法数字）。 */
    private static final int GUI_SIZE = 27;

    private final LetsLearnEnglish plugin;

    public DuelGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, DuelRoom room) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = truncateTitleSafe(
                MessageUtil.getGuiMsg(messages, "duel.gui-title"));

        // R5-6：挂结构身份 holder（NoPayload —— 对手身份已由 DuelChallengeGUI 的 PDC 承担，
        // 本界面无 per-view 上下文）。与下方 isViewingDuelGUI 的闸门是【同一批】改动：
        // 只挂 holder 不改闸门 → 行为不变（闸门仍走标题）；只改闸门不挂 holder →
        // DuelManager.refreshBothGUIs 判定恒 false，双方界面每回合都不刷新（冻结比分）。
        //
        // 收益点：本界面标题被 truncateTitleSafe 截到 32 个可见字符，配置标题较长时
        // "Word Duel"/"单词对决" 可能整段被截掉，标题 fallback 认不回自己；
        // holder 不受文案/截断影响，是对决刷新闸门唯一可靠的判定。
        Inventory inv = Bukkit.createInventory(PluginGuiHolder.of(GuiViewKey.DUEL), GUI_SIZE, title);
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
     * <p><b>R5-6 起判定主路径已改为 holder</b>（见 {@link #isViewingDuelGUI} 与
     * DuelGUIListener），标题匹配只剩迁移期兜底 —— 本方法保留纯属显示逻辑（客户端
     * 标题上限），不因 holder 迁移而删除。
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
     * R-23 / R5-6: 玩家当前是否正看着对决 GUI。
     *
     * <p>供 DuelManager.refreshBothGUIs 调用：只刷新仍在看界面的玩家，
     * ESC 关掉界面的玩家不再被每题强制弹回，靠 /le duel gui 手动回来。
     * 必须在主线程调用（读 openInventory）。
     *
     * <p><b>R5-6 判定切换</b>：holder 优先（{@link #open} 已挂 {@link PluginGuiHolder}，
     * 同为 R5-6 同批改动）、标题兜底。holder 是这里唯一可靠的判定：本界面标题会被
     * {@link #truncateTitleSafe} 截到 32 个可见字符，配置标题较长时 "Word Duel"/"单词对决"
     * 可能整段被截掉，{@link GuiIdentity#resolveByTitle(String)} 会返回 null —— 而本方法
     * 正是「每回合刷新双方界面」的唯一闸门，一旦误判 false，玩家会看着冻结的比分打完整局。
     *
     * <p>holder 命中即以 holder 为准（即使它答出的是别的界面，也不再拿标题翻案）；
     * 只有 holder 未命中（{@code null}，迁移期旧界面/异常路径）才回落到
     * {@link GuiIdentity#resolveByTitle(String)} 的 contains 白名单，语义与迁移前
     * 「非对决界面不刷新」一致。{@code plugin} 参数保留是为了不动
     * {@code DuelManager:1381/1384} 的调用签名（本流只改判定实现，不改调用点）。
     *
     * <p><b>注意</b>：标题兜底在长标题下会失效（{@code resolveByTitle} 也是
     * {@code contains}，截断后关键词可能没了）—— 这正是 holder 改造的收益点，
     * 不是本次要保留的行为。
     */
    public static boolean isViewingDuelGUI(LetsLearnEnglish plugin, Player player) {
        if (plugin == null || player == null) return false;
        InventoryView view = player.getOpenInventory();
        if (view == null) return false;
        // R5-6：holder 优先、标题兜底（GuiIdentity.resolve(view, title) 的语义）。
        // holder 非 null 时直接以 holder 为准（哪怕是别的界面），不用标题翻案。
        return GuiIdentity.resolve(view, view.getTitle()) == GuiViewKey.DUEL;
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
