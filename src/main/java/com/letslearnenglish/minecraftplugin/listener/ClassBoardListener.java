package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.ClassBoardGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Listener for the Class Board GUI（US-CLS-2，Phase 3 Wave 4 · 4A-2）。
 *
 * 身份判定走 {@link ClassBoardGUI#isClassBoardGUI}（锚定前缀 + 尺寸双重确认，
 * 见该类 javadoc 的唯一性论证与 F6-02 纪律）。本界面除返回键外无其它可交互元素
 * ——成员头像仅展示，点击一律静默忽略（取消事件，不开任何次级界面）。
 *
 * 形态照抄 ChatHistoryListener：只处理上半区、背包点击不 cancel、
 * onInventoryDrag 上半区守卫。
 */
public class ClassBoardListener implements Listener {

    private final LetsLearnEnglish plugin;

    public ClassBoardListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        // 双重确认：标题锚定前缀 + 尺寸 = ClassBoardGUI.GUI_SIZE（与 ChatHistoryListener
        // / PersonalScenarioListener 同款姿态，防仅标题巧合命中的容器）
        if (event.getView().getTopInventory().getSize() != ClassBoardGUI.GUI_SIZE) {
            return;
        }
        if (!ClassBoardGUI.isClassBoardGUI(plugin, title)) {
            return;
        }

        // R-01 补漏：只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getSlot() == ClassBoardGUI.BACK_SLOT) {
            // Back to main menu (unified with other GUIs)
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }
        // 其余槽位（成员头像 / 空态 / 留白）：已取消事件，静默忽略。
    }

    /**
     * 拖拽 guard（照抄 ChatHistoryListener 形态）：班级榜的成员头像可被拖进玩家背包。
     * 只在拖拽触及界面上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();

        if (event.getView().getTopInventory().getSize() != ClassBoardGUI.GUI_SIZE) {
            return;
        }
        if (!ClassBoardGUI.isClassBoardGUI(plugin, title)) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
