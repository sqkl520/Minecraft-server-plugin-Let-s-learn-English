package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class Board GUI（US-CLS-2，Phase 3 Wave 4 · 4A-2）— 54 格班级榜。
 *
 * 结构：slot 8 返回主菜单；slot 9-53 成员头像（按当前赛季窗口学习分降序，最多 45 行）；
 * slot 22 空态（BARRIER，仅当无任何条目时出现——与成员格互斥，不会同屏）。
 *
 * <b>零查库链路（PF-SEA-04 教训）</b>：本类不调用任何 DatabaseManager / PlayerDataStore
 * 方法。数据由调用方（/le class 的异步链）拉好传入 {@link #open}；打开后不再重查
 * （快照语义）。渲染面任何一点触发的"主线程查库"都是回归，测试关注点。
 *
 * <b>身份判定</b>：见 {@link #isClassBoardGUI}——标题含运行时班级名，无法整串等值
 * 比对，采用「锚定前缀」方案（从 i18n 模式截取 {class} 前固定前缀，双语言比对）。
 *
 * <b>音效白名单</b>：SoundManager 的打开/点击音效依赖 {@link GUIUtils#isPluginGUI}
 * 识别标题；本 GUI 标题（class.board-title = 「班级榜 / Class Board」）需由编排者
 * fan-in 同步加入该白名单，否则本界面静默无打开音效（本类不自行修改 GUIUtils，
 * 按 Wave 4 白名单纪律）。
 */
public class ClassBoardGUI {

    /** 54 格（监听器用其做身份双重确认：标题前缀 + 尺寸）。 */
    public static final int GUI_SIZE = 54;

    /** 返回主菜单按钮 —— 与其余 GUI 统一在右上角 slot 8。 */
    public static final int BACK_SLOT = 8;

    /** 空态提示槽位（与 LeaderboardGUI / ChatHistoryGUI 惯例一致）。 */
    public static final int EMPTY_SLOT = 22;

    /**
     * 成员头像槽位（slot 9-53，共 45 格，升序；超出 45 名的成员不渲染——这是"榜"，
     * 天然只取赛季分最高的前 N 名）。不含 {@link #BACK_SLOT}（行 0 留给返回键）。
     */
    public static final int[] ENTRY_SLOTS = {
        9, 10, 11, 12, 13, 14, 15, 16, 17,
        18, 19, 20, 21, 22, 23, 24, 25, 26,
        27, 28, 29, 30, 31, 32, 33, 34, 35,
        36, 37, 38, 39, 40, 41, 42, 43, 44,
        45, 46, 47, 48, 49, 50, 51, 52, 53
    };

    private final LetsLearnEnglish plugin;

    public ClassBoardGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * 标题判定 —— 点击/拖拽路由的唯一真相源（监听器与渲染端共用）。
     *
     * <p>F6-02 教训（禁裸子串匹配）：但本界面标题含运行时班级名，无法像
     * PersonScenarioListener 那样做整串等值比对。采用的折中：<b>锚定前缀比对</b>
     * ——从 {@code class.board-title} 的 i18n 模式串截取 {@code {class}} 之前的
     * 固定前缀（zh/en 双语言各验一次），渲染后要求标题以该前缀开头（startsWith，
     * 非 contains）。
     *
     * <p>前缀唯一性论证：zh「班级榜 - 」/ en「Class Board - 」是标题首段。其余所有
     * GUI 标题（见 GUIUtils.isPluginGUI 白名单）无一以此开头——最接近的同类名
     * （玩家班级摘要页若将来落地，「我的班级 / My Class」）不含「班级榜」子串且
     * 不以之开头。判定与渲染读同一 i18n 键：config 篡改时两侧同步失效，不会产生
     * "渲染是 A、判定是 B"的错配。
     *
     * @param plugin 插件实例（读语言各自的 messages 配置）
     * @param title  事件里的 {@code view.getTitle()}
     */
    public static boolean isClassBoardGUI(LetsLearnEnglish plugin, String title) {
        if (plugin == null || title == null || title.isEmpty()) {
            return false;
        }
        for (String lang : new String[] {"zh", "en"}) {
            FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
            String pattern = MessageUtil.getGuiMsg(messages, "class.board-title");
            int idx = pattern.indexOf("{class}");
            String fixedRaw = (idx >= 0) ? pattern.substring(0, idx) : pattern;
            if (fixedRaw.isEmpty()) {
                continue;
            }
            String fixed = ChatColor.translateAlternateColorCodes('&', fixedRaw);
            if (title.startsWith(fixed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打开班级榜（数据在手，零查库）。
     *
     * @param viewer    观看者（语言取自本人）
     * @param className 班级名（用于标题 {class} 占位符；null 按空串处理）
     * @param entries   已按赛季分降序排好的成员条目（null / 空 = 空态）
     */
    public void open(Player viewer, String className, List<LeaderboardEntry> entries) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(viewer));

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "class.board-title")
                        .replace("{class}", className == null ? "" : className));
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        List<LeaderboardEntry> safeEntries = (entries == null) ? Collections.emptyList() : entries;

        // ---- 成员头像（slot 9-53，PLAYER_HEAD + 赛季分 lore；渲染风格照抄 LeaderboardGUI）----
        int displayCount = Math.min(safeEntries.size(), ENTRY_SLOTS.length);
        for (int i = 0; i < displayCount; i++) {
            LeaderboardEntry entry = safeEntries.get(i);
            int slot = ENTRY_SLOTS[i];
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.playerId));
                // 与 LeaderboardGUI 同款：#rank 名字为首行，颜色码为动态值的固定包装（非玩家文案）
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                        "&6#" + entry.rank + " &f" + entry.playerName));
                List<String> lore = new ArrayList<>();
                // 分数行复用既有活键 leaderboard.entry-season（语义即"窗口内学习分"）
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "leaderboard.entry-season")
                                .replace("{score}", String.valueOf(entry.score))));
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(slot, head);
        }

        // ---- 空态（BARRIER，slot 22；仅当没有任何条目时出现，与成员格互斥）----
        if (safeEntries.isEmpty()) {
            String emptyLabel = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "class.empty"));
            inv.setItem(EMPTY_SLOT, createItem(Material.BARRIER, emptyLabel, Collections.emptyList()));
        }

        // ---- 返回键（slot 8，RED_STAINED_GLASS_PANE；gui.back-button / gui.back-lore 既有键）----
        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                MessageUtil.getGuiMsg(messages, "gui.back-button"));
        inv.setItem(BACK_SLOT, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        // P0-5：空槽统一填灰玻璃（防 shift-click / 拖拽纵深；R12-M3 同款）。
        // 放在全部 setItem 之后、openInventory 之前：成员头像（slot 9-53）、空态
        // （slot 22）、返回键（slot 8）都已就位，fillEmpty 只兜剩余空槽，不会覆盖内容。
        // 本方法无早退分支（entries 为 null 已在上方收敛为空列表），故这一处即覆盖
        // 「满页 / 半页 / 空页」三种渲染形态。
        GUIUtils.fillEmpty(inv);

        viewer.openInventory(inv);
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
