package com.letslearnenglish.minecraftplugin.gui;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * Two-step confirmation GUI for language switching.
 *
 * Flow: MainMenu language button → Confirm page 1 → Confirm page 2 → switch
 *
 * 每个阶段都有两个按钮：确认（slot 13）与取消（slot 22，返回主菜单）。
 */
public class ConfirmationGUI {

    /** Per-player 二确状态：阶段 + 阶段 1 落盘的目标语言（R-09：防阶段 2 重算切错方向）。 */
    private record ConfirmState(int stage, String targetLang) {}

    private static final Map<UUID, ConfirmState> confirmStages = new ConcurrentHashMap<>();
    public static final String CONFIRM_TITLE_KEY = "language.confirm-title";

    /** 确认按钮槽位（两个阶段共用） */
    public static final int CONFIRM_SLOT = 13;
    /** 取消按钮槽位（底部居中，与其它 GUI 的底部操作区一致） */
    public static final int CANCEL_SLOT = 22;

    private static final int GUI_SIZE = 27;

    private final LetsLearnEnglish plugin;
    private final String targetLang;

    public ConfirmationGUI(LetsLearnEnglish plugin, String targetLang) {
        this.plugin = plugin;
        this.targetLang = targetLang;
    }

    /**
     * F6-02：判定标题是否为本二确界面。
     *
     * <p>旧实现是裸子串 {@code contains("Confirm") || contains("确认")} —— 过宽：
     * 任何未来标题含这两个词的界面（如"确认操作"类提示页）都会被调用方
     * （{@code MainMenuListener}）误当作二确界面，被确认/取消槽位逻辑捕获甚至吞掉点击。
     *
     * <p>新实现与 {@link #open}/{@link #openSecond} 的标题生成完全同源：按 zh/en 两份
     * 配置读 {@link #CONFIRM_TITLE_KEY}，经相同的 {@code &} 颜色码转换后做等值比对。
     * open 侧不截断标题（标题短，本类也不像 DuelGUI 那样预截断），故用等值而非前缀；
     * 配置缺键时两侧同为 {@code getGuiMsg} 的 {@code [?key]} 占位符，比对仍然一致 ——
     * 不会把本类自己生成的标题排除掉。
     */
    public static boolean isConfirmGUI(String title) {
        if (title == null || title.isEmpty()) return false;
        LetsLearnEnglish plugin = LetsLearnEnglish.getInstance();
        if (plugin == null || plugin.getConfigManager() == null) return false;
        for (String lang : new String[] { "zh", "en" }) {
            String expected = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(
                            plugin.getConfigManager().getMessageConfig(lang), CONFIRM_TITLE_KEY));
            if (title.equals(expected)) return true;
        }
        return false;
    }

    public static int getStage(Player player) {
        ConfirmState state = confirmStages.get(player.getUniqueId());
        return state == null ? 0 : state.stage();
    }

    public static void resetStage(Player player) {
        confirmStages.remove(player.getUniqueId());
    }

    /** 取阶段 1 落盘的目标语言；无状态时返回 null（调用方回退到"当前语言反推"保底）。 */
    public static String getTargetLang(Player player) {
        ConfirmState state = confirmStages.get(player.getUniqueId());
        return state == null ? null : state.targetLang();
    }

    /** 第一阶段确认界面 */
    public void open(Player player) {
        showConfirmPage(player, 1);
    }

    /** 第二阶段确认界面 */
    public void openSecond(Player player) {
        showConfirmPage(player, 2);
    }

    /**
     * 打开确认界面。
     *
     * 文案按玩家「当前」语言渲染（切换要等到第二次确认之后才生效），
     * 因此中英文玩家各自看到的都是自己的语言。
     */
    private void showConfirmPage(Player player, int stage) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(player));

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, CONFIRM_TITLE_KEY));
        String itemName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.confirm-item"));
        String itemLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.confirm-lore"));
        String cancelName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.cancel-item"));
        String cancelLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.cancel-lore"));

        // R-09：阶段与目标语言一起落盘。阶段 2 重开页时同样写入（监听器透传的即阶段 1 存的值），
        // applySwitch 只认这里存的语言，不认实例字段。
        confirmStages.put(player.getUniqueId(), new ConfirmState(stage, targetLang));

        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);
        inv.setItem(CONFIRM_SLOT, createItem(Material.EMERALD, itemName, itemLore));
        inv.setItem(CANCEL_SLOT, createItem(Material.BARRIER, cancelName, cancelLore));

        player.openInventory(inv);
    }

    public void applySwitch(Player player) {
        // R-09：读存储里的目标语言（阶段 1 落盘值）；取不到时（异常路径）回退到实例字段保底。
        ConfirmState state = confirmStages.remove(player.getUniqueId());
        String lang = (state != null && state.targetLang() != null) ? state.targetLang() : targetLang;
        plugin.setPlayerLanguage(player, lang);

        // 切换后用新语言播报（前缀与文案都由 MessageUtil 按玩家语言解析）
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "language.switched"));
    }

    private ItemStack createItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}
