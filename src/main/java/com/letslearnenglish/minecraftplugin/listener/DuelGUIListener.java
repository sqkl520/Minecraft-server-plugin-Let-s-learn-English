package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import com.letslearnenglish.minecraftplugin.gui.DuelGUI;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;

/**
 * DuelGUIListener — handles clicks inside the Duel GUI.
 *
 * The only interactive element is the Quit button (barrier at slot 22).
 * All other slots are display-only and clicking does nothing.
 *
 * <p>R5-11：界面识别<b>只认 holder</b> ——{@link DuelGUI#open} 已挂
 * {@link com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder}
 * （{@link GuiViewKey#DUEL}）。这一点对本界面尤其重要：标题会被
 * {@link DuelGUI#truncateTitleSafe} 截到 32 个可见字符，配置标题较长时关键词可能被截掉，
 * 纯标题判定会静默失联。原 {@code DuelGUI.isDuelGUITitle} 标题兜底（R5-13 已删除）已废弃。
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

        if (!isDuelGUI(event.getView())) return;

        // R-01/R-22: 只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel（对决中点背包 22 不再误触退出判负，玩家也能正常整理背包）。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();

        // R5V-1 纵深：下半区 shift-click（MOVE_TO_OTHER_INVENTORY）与双击收集
        //（COLLECT_TO_CURSOR）补 cancel —— 铺满后原版已无落点，此处防未来布局改动回归。
        // 其余下半区点击不 cancel（玩家整理背包不受影响，R-01/R-22 语义保持）。
        // 注：MOVE_TO_OTHER_INVENTORY 是 InventoryAction 常量（fix-plan 写 ClickType 系笔误，编译不过）。
        if ((event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR)
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
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!isDuelGUI(event.getView())) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * R5-11：判定这个 view 是否为本类的对决界面 —— <b>只认 holder</b>。
     *
     * <p>holder 命中 {@link GuiViewKey#DUEL} 即「是我」，其余（含 {@code null}）
     * 一律不是。原 {@code DuelGUI.isDuelGUITitle} 标题兜底（R5-13 已删除）已废弃。
     *
     * <p>注意：本方法只回答「这个 view 属于谁」，<b>不</b>回答「这一下点击落在哪」。
     * 上半区/下半区/窗外守卫必须留在调用方、且在 {@code setCancelled(true)} 之前。
     *
     * @param view 事件界面，可为 null
     */
    private boolean isDuelGUI(InventoryView view) {
        return GuiIdentity.resolve(view) == GuiViewKey.DUEL;
    }
}
