package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import com.letslearnenglish.minecraftplugin.gui.DuelGUI;
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

        // R5V-1 纵深：下半区 shift-click（MOVE_TO_OTHER_INVENTORY）补 cancel ——
        // 铺满后原版已无落点，此处防未来布局改动回归。其余下半区点击不 cancel
        //（玩家整理背包不受影响，R-01/R-22 语义保持）。
        // 注：MOVE_TO_OTHER_INVENTORY 是 InventoryAction 常量（fix-plan 写 ClickType 系笔误，编译不过）。
        if (event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                && rawSlot >= topSize) {
            event.setCancelled(true);
            return;
        }

        if (rawSlot < 0 || rawSlot >= topSize) return;

        // 上半区命中后立刻取消：确保没有任何分支能把展示用的物品从界面里拿走
        event.setCancelled(true);

        if (event.getCurrentItem() == null) return;

        // Slot 22 = Quit button
        if (event.getSlot() == QUIT_SLOT) {
            // R-86（P2 fan-in）：点击音效不再由 SoundManager 全局兜底（全局
            // InventoryClick 已删除），对决退出键这里显式补播——其余展示格
            // 按设计静音（无可点击动作，不应有反馈音）。
            com.letslearnenglish.minecraftplugin.core.SoundManager
                    .playSound(player, "menu-click");
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
     * R-23: 委托到 DuelGUI.isDuelGUITitle（唯一真相源），语义不变：
     * 标题取自 duel.gui-title，中英文案不同（中文「单词对决」/ 英文「Word Duel」），
     * 因此同时接受玩家当前语言与中英两种配置标题；配置读不到时回退到关键词匹配。
     */
    private boolean isDuelGUI(Player player, String title) {
        return DuelGUI.isDuelGUITitle(plugin, player, title);
    }
}
