package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.ClassBoardGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;

/**
 * Listener for the Class Board GUI（US-CLS-2，Phase 3 Wave 4 · 4A-2）。
 *
 * 身份判定 R5-5 起改为「holder 优先、标题兜底」：{@link ClassBoardGUI#open} 已挂
 * {@link com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder}
 * （{@link GuiViewKey#CLASS_BOARD}）；holder 未命中（迁移期旧 inventory）才回落
 * {@link ClassBoardGUI#isClassBoardGUI}（锚定前缀 + 尺寸双重确认，见该类 javadoc
 * 的唯一性论证与 F6-02 纪律）。
 *
 * 本界面除返回键外无其它可交互元素 ——成员头像仅展示，点击一律静默忽略
 * （取消事件，不开任何次级界面）。
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
        // ── R5-5 判定顺序（四条铁律 + 一条既有 P0-5 前置例外）──────────────────
        // 1) 先判窗外：getClickedInventory() 只在点到界面之外时为 null。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();

        // 2) P0-5 前置例外（改造前就存在的分支，位置不可后移）：下半区 shift-click
        //（MOVE_TO_OTHER_INVENTORY）与双击收集（COLLECT_TO_CURSOR）必须在上面的
        //    rawSlot 越界 return 之前处理，否则永远走不到。本分支只 cancel 这两类
        //    action，且先确认「是本类辖管的界面」—— 不是「只判 holder 就 cancel」
        //    的一般性路径；普通下半区点击仍然一步不拦（见下面的 return）。
        if (rawSlot >= topSize
                && (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        || event.getAction() == InventoryAction.COLLECT_TO_CURSOR)) {
            if (resolveOwnedView(event.getView()) != null) {
                event.setCancelled(true);
            }
            return;
        }

        // 3) 下半区（玩家背包）点击一律不处理、也不 cancel，玩家开着 GUI 也能整理背包。
        if (rawSlot < 0 || rawSlot >= topSize) return;

        // 4) 然后才做身份判定：holder 优先（结构身份），未命中才回落标题（迁移期兜底）。
        //    本界面只有返回键一个动作，故只需判断归属，不需按 key 分派。
        if (resolveOwnedView(event.getView()) == null) return;

        // R5-5 切换点插桩：holder 与标题说法不一致时暴露漂移（去重 WARNING），不改变行为。
        GuiIdentity.logMismatch(plugin, event.getView());

        // 5) 只有「本类辖管界面的上半区点击」才 cancel —— 绝不允许只判 holder 就 cancel。
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
        if (resolveOwnedView(event.getView()) == null) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * R5-5：解析「本监听器负责的界面」—— holder 优先，标题兜底。
     *
     * <p>holder 命中 {@link GuiViewKey#CLASS_BOARD} 即为本界面（结构身份压倒文案）；
     * holder 答出别的 key 时不再用标题翻案。只有 holder 为 {@code null}
     * （尚未迁移的旧 inventory）才走标题兜底，并保留改造前的尺寸确认（54 格）。
     *
     * @param view 事件界面，可为 null
     * @return {@link GuiViewKey#CLASS_BOARD} 或 null（不归本类管）
     */
    private GuiViewKey resolveOwnedView(InventoryView view) {
        GuiViewKey byHolder = GuiIdentity.resolve(view);
        if (byHolder == GuiViewKey.CLASS_BOARD) return byHolder;
        if (byHolder != null) return null; // holder 明确说是别的屏幕 —— 不归本类管
        if (view == null || view.getTopInventory() == null) return null;

        // ── 以下为迁移期兜底（holder 未命中）──
        // 双重确认：标题锚定前缀 + 尺寸 = ClassBoardGUI.GUI_SIZE（防仅标题巧合命中的容器）
        if (view.getTopInventory().getSize() != ClassBoardGUI.GUI_SIZE) return null;
        return ClassBoardGUI.isClassBoardGUI(plugin, view.getTitle())
                ? GuiViewKey.CLASS_BOARD : null;
    }
}
