package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * DuelGUIListener — handles clicks inside the Duel GUI.
 *
 * The only interactive element is the Quit button (barrier at slot 22).
 * All other slots are display-only and clicking does nothing.
 */
public class DuelGUIListener implements Listener {

    /** DuelGUI 打开界面时会把标题截断到这个长度（Minecraft 标题上限） */
    private static final int MAX_TITLE_LENGTH = 32;

    /** 退出按钮槽位（与 DuelGUI 的 barrier 一致） */
    private static final int QUIT_SLOT = 22;

    private final LetsLearnEnglish plugin;

    public DuelGUIListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();
        if (!isDuelGUI(player, title)) return;

        // R-01/R-22: 只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel（对决中点背包 22 不再误触退出判负，玩家也能正常整理背包）。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        // 上半区命中后立刻取消：确保没有任何分支能把展示用的物品从界面里拿走
        event.setCancelled(true);

        if (event.getCurrentItem() == null) return;

        // Slot 22 = Quit button
        if (event.getSlot() == QUIT_SLOT) {
            player.closeInventory();
            DuelManager duelManager = plugin.getDuelManager();
            if (duelManager != null) {
                duelManager.quitDuel(player);
            }
        }
    }

    /**
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 展示用的玻璃板/绿宝石等会被拖进玩家背包。
     *
     * 只在拖拽触及界面上半部分（对决界面自身）时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isDuelGUI(player, event.getView().getTitle())) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * 判定标题是否属于单词对决界面。
     *
     * 标题取自 duel.gui-title，中英文案不同（中文「单词对决」/ 英文「Word Duel」），
     * 因此同时接受玩家当前语言与中英两种配置标题；配置读不到时回退到关键词匹配
     * （与 GUIUtils 的白名单保持一致）。
     */
    private boolean isDuelGUI(Player player, String title) {
        if (title == null) return false;

        if (matchesTitle(title, getConfiguredTitle(plugin.getPlayerLanguage(player)))) return true;
        if (matchesTitle(title, getConfiguredTitle("zh"))
                || matchesTitle(title, getConfiguredTitle("en"))) {
            return true;
        }

        return title.contains("Word Duel") || title.contains("单词对决");
    }

    /**
     * 读取配置中的对决标题并转换颜色码；未配置时返回 null。
     */
    private String getConfiguredTitle(String lang) {
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        String raw = messages == null ? null : messages.getString("duel.gui-title");
        if (raw == null || raw.isEmpty()) return null;
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /**
     * DuelGUI 打开界面时会把标题截断到 32 字符，因此比对前做同样的截断，
     * 否则长标题会被误判为非对决界面。
     */
    private boolean matchesTitle(String title, String expected) {
        if (expected == null) return false;
        String prefix = expected.length() > MAX_TITLE_LENGTH
                ? expected.substring(0, MAX_TITLE_LENGTH) : expected;
        return title.startsWith(prefix);
    }
}
