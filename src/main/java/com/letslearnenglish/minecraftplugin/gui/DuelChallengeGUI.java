package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Duel Challenge GUI — 发起对决的在线玩家选择器（R-82 / MC-1，54 格）。
 *
 * <p>入口：主菜单【单词对决】slot 12（非对决中时）。点击某玩家头像 =
 * {@code DuelManager.sendDuelRequest(player, target)} —— 全部忙态/AI 对话/答题互斥
 * 守卫都在 manager 内部复用，本 GUI 不预判忙态（预判与点击存在竞态，会误拒）。
 *
 * <p><b>标题避碰红线（plan §2.1）</b>：标题取自 {@code duel.challenge-title}
 * （「选择对决对手」/ "Pick a Duel Opponent"），<b>绝不含</b> "单词对决"/"Word Duel"
 * 子串 —— 旧 DuelGUI 标题判定（前缀比对 + 关键词兜底）会把含子串的标题误判为
 * 对决界面（点 slot 22 会被当成退出对决），故本标题刻意避开该子串。
 * <b>R5-6 起该红线不再依赖文案：</b>本界面挂 {@link PluginGuiHolder}
 * （{@link GuiViewKey#DUEL_CHALLENGE}），识别侧（
 * {@link com.letslearnenglish.minecraftplugin.listener.DuelChallengeListener}）<b>只认 holder</b>；
 * R5-14 起原标题等值 + 尺寸 54 的双重确认兜底已删除，文案改动与语言切换都不再影响识别。
 *
 * <p>布局（54 格）：
 * <pre>
 *   slot 0-53    先铺灰色玻璃板占位（防拖拽；头像/说明物/返回键随后覆盖）
 *   slot 4       说明物（duel.challenge-hint：点击头像发起挑战，对方 /le duel accept 接受）
 *   slot 8       返回主菜单
 *   slot 9-44 + 45/46/48/50/52/53   在线玩家头像（WrongBook 式 42 槽，排除自己）
 *   slot 22      空态物（只剩自己在线时：duel.challenge-empty；点击判定走 PDC，无目标键 = no-op）
 *   尾槽（53）   >42 人时改放 duel.challenge-more 提示
 * </pre>
 *
 * <p>点击映射不依赖槽位下标：目标玩家 UUID 存在头像物品的
 * {@link org.bukkit.persistence.PersistentDataContainer} 里（ChatHistoryGUI 同款模式），
 * 与渲染顺序/在线人数变动解耦，绝无「点 A 挑战 B」。因此本 GUI 无 per-player 状态，
 * Listener 也无需 quit 清理。
 */
public class DuelChallengeGUI {

    /** 界面尺寸（{@link #open} 建 inventory 用；holder 判定不依赖尺寸）。 */
    public static final int GUI_SIZE = 54;

    /** 返回主菜单槽位（写死 = 唯一真相，监听器引用本常量）。 */
    public static final int BACK_SLOT = 8;

    /** 页首说明物槽位（纯展示，点击 no-op）。 */
    private static final int HINT_SLOT = 4;

    /**
     * 空态提示槽位（只剩自己在线时）。位于头像槽位区内，但点击判定走 PDC
     * （物品无目标键 = no-op），不会像旧错题本那样落进"条目分支"，安全
     * （plan §2.1 定稿槽位）。
     */
    private static final int EMPTY_SLOT = 22;

    /**
     * 在线玩家头像占用的槽位（WrongBook 式 42 槽布局，显式列出方便一眼看懂）。
     * 第二行到第五行 = 9-44；第六行取 45/46/48/50/52/53（避开返回键 slot 8）。
     */
    private static final int[] PLAYER_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44,
            45, 46, 48, 50, 52, 53
    };

    /**
     * 头像物品的 PDC 数据键 —— 承载目标玩家 UUID（内部数据，不写进物品名/lore，
     * 玩家悬停看不到；监听器用同一份常量构造 NamespacedKey）。
     */
    public static final String TARGET_ID_KEY = "duel_challenge_target";

    /** 构造头像物品的目标玩家数据键。 */
    public static NamespacedKey targetIdKey(LetsLearnEnglish plugin) {
        return new NamespacedKey(plugin, TARGET_ID_KEY);
    }

    private final LetsLearnEnglish plugin;

    public DuelChallengeGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        // MC-1 模块门（纵深，plan §1.(b)#8"DuelChallengeGUI 不可达"）：正常路径由
        // MainMenuListener 点击分支在打开前拒绝（module.disabled）；此处兜底任何
        // 未来直达调用。门点读 ConfigManager（reload 即时生效），内存读无 IO。
        if (!plugin.getConfigManager().isDuelEnabled()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "module.disabled"));
            return;
        }

        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "duel.challenge-title"));

        Inventory inv = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.DUEL_CHALLENGE), GUI_SIZE, title);

        // 先用灰色玻璃板铺满全部槽位，防止玩家把物品拖进空槽
        // （头像/说明物/返回键随后覆盖，填充顺序：占位 -> 说明物/返回键 -> 头像）
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int i = 0; i < GUI_SIZE; i++) {
            inv.setItem(i, filler.clone());
        }

        // 页首说明物（slot 4，纯展示）
        inv.setItem(HINT_SLOT, createItem(Material.BOOK,
                ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "duel.challenge-hint")),
                Collections.emptyList()));

        // 返回键（slot 8，与其它 GUI 的右上角返回位一致）
        inv.setItem(BACK_SLOT, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&', "&e" +
                        MessageUtil.getGuiMsg(messages, "gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        // 在线玩家（排除自己）。按名字稳定排序仅为展示确定性 —— 点击映射走物品 PDC，
        // 在线名单在界面打开后变化也不影响"点谁是谁"。
        List<Player> others = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(player.getUniqueId())) {
                others.add(online);
            }
        }
        others.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        if (others.isEmpty()) {
            // 空态：只剩自己在线（plan §2.1：slot 22 空态物）
            inv.setItem(EMPTY_SLOT, createItem(Material.BOOK,
                    ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "duel.challenge-empty")),
                    Collections.emptyList()));
        } else {
            // >42 时截断为"前 41 位 + 尾槽提示"（ScenarioSelect"更多…"同款模式）
            boolean hasMore = others.size() > PLAYER_SLOTS.length;
            int shownCount = hasMore ? PLAYER_SLOTS.length - 1 : others.size();
            String headLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "duel.challenge-hint"));
            for (int i = 0; i < shownCount; i++) {
                inv.setItem(PLAYER_SLOTS[i], createHead(others.get(i), headLore));
            }
            if (hasMore) {
                inv.setItem(PLAYER_SLOTS[PLAYER_SLOTS.length - 1], createItem(Material.BOOK,
                        ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "duel.challenge-more")
                                        .replace("{count}", String.valueOf(shownCount))),
                        Collections.emptyList()));
            }
        }

        // R-86：menu-open 不在此显式播放 —— SoundManager.onInventoryOpen 对本界面
        //（holder = GuiViewKey.DUEL_CHALLENGE，GUIUtils.isPluginGUI 纯 holder 判定）全局播一次，此处保留会双声。
        player.openInventory(inv);
    }

    /**
     * 构造玩家头像（R-70 同款：PLAYER_HEAD + SkullMeta#setOwningPlayer 带上皮肤，
     * 与 ProfileGUI/EnglishSquareGUI 模式一致；主线程调用安全）。
     * 目标 UUID 存进 PersistentDataContainer —— 点击映射的唯一真相。
     */
    private ItemStack createHead(Player target, String loreLine) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b" + target.getName()));
            meta.setLore(Collections.singletonList(loreLine));
            meta.getPersistentDataContainer().set(targetIdKey(plugin),
                    PersistentDataType.STRING, target.getUniqueId().toString());
            head.setItemMeta(meta);
        }
        return head;
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
