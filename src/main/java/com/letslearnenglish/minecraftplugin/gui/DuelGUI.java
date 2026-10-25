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

    /** 打开界面时会把标题截断到这个长度（Minecraft 标题上限，纯显示约束，与身份判定无关）。 */
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
        // 本界面无 per-view 上下文）。R5-13 起这是本界面身份判定的【唯一】依据：
        // DuelManager.refreshBothGUIs 经 isViewingDuelGUI 只认 holder，标题层已删除。
        //
        // 收益点：本界面标题被 truncateTitleSafe 截到 32 个可见字符，配置标题较长时
        // "Word Duel"/"单词对决" 可能整段被截掉，标题兜底认不回自己；
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
     * <p><b>R5-13 起本方法纯属显示逻辑</b>：身份判定已完全走 holder
     * （{@link #isViewingDuelGUI}），没有任何标题匹配代码依赖本方法的截断口径。
     * 保留是为了遵守客户端标题上限、不破坏打开时的显示。
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
     * <p><b>R5-13：已改为纯 holder 判定</b>（{@link GuiIdentity#resolve(InventoryView)}），
     * 标题层彻底不参与。这一层能删得掉，正是因为本界面旧标题判定本来就不可靠：标题会被
     * {@link #truncateTitleSafe} 截到 32 个可见字符，配置标题较长时 "Word Duel"/"单词对决"
     * 可能整段被截掉，标题兜底反而认不回自己 —— 而本方法正是「每回合刷新双方界面」的
     * 唯一闸门，误判 false 会让玩家看着冻结的比分打完整局。holder 是本界面唯一可靠的
     * 判定依据，也是 R5-6 起的主路径。{@code plugin} 参数保留是为了不动
     * {@code DuelManager} 的调用签名（本流只改判定实现，不改调用点）。
     */
    public static boolean isViewingDuelGUI(LetsLearnEnglish plugin, Player player) {
        if (plugin == null || player == null) return false;
        InventoryView view = player.getOpenInventory();
        if (view == null) return false;
        // R5-13：纯 holder，无标题兜底 —— holder 是唯一真相源。
        return GuiIdentity.resolve(view) == GuiViewKey.DUEL;
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
