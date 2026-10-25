package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * GUI Utility methods shared across listener classes.
 *
 * <p>R5-10/R5-14/R5-15：判定「这个界面是不是插件自有 GUI」只有一个入口 ——
 * {@link #isPluginGUI(InventoryView)}，它直接转发给
 * {@link GuiIdentity#resolve(InventoryView)}（<b>纯 holder 判定</b>，本类不再持有判定
 * 逻辑，避免与 identity 包形成两份实现）。语义是「top inventory 的
 * {@code InventoryHolder} 是否为 {@code PluginGuiHolder}」，与标题文案完全解耦。
 * 原 {@code isPluginGUI(String)} 的标题白名单与
 * {@code extractPageFromTitle} 均已随标题层一并删除（R5-13 起 holder 是唯一身份）。
 *
 * <p>为什么底层是 {@code instanceof}（见 {@code GuiIdentity.holderOf}）而不是
 * {@code getHolder() != null}：Bukkit 里所有容器/玩家背包都有非 null holder，
 * 判 null 会把玩家背包也当成插件 GUI。
 */
public class GUIUtils {

    private GUIUtils() {
        // Utility class — cannot be instantiated
    }

    /**
     * R12-M3：把空槽统一填为玻璃板（防拖纵深；照 LearningCalendarGUI filler 先例
     * 逐字等价，8 个 GUI 共用）。调用约定：在 {@code openInventory(inv)} 之前、
     * 全部 setItem 之后调用一次——晚于本调用的 setItem 不受影响（filler 只是空槽
     * 兜底，不覆盖已渲染物品）。
     */
    public static void fillEmpty(Inventory inv) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler.clone());
        }
    }

    /**
     * R5-10 / R5-15：判断给定界面是否属于插件自有 GUI —— <b>纯 holder 判定</b>，
     * 委托给单一真相源 {@link GuiIdentity#resolve(InventoryView)}。
     *
     * <p>语义等价于「top inventory 的 {@code InventoryHolder} 是
     * {@link com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder}」：玩家自己的背包
     * holder 是 {@code Player}、原版容器有自己的 holder，所以不会误命中；标题完全不参与
     * 判定 —— 管理员改文案、切语言都不再影响结果。
     *
     * <p><b>R5-15：为什么改成委托（原实现是本类自己写一遍 {@code instanceof}）</b>——
     * 这条判定原本有两份逐字等价的实现：本方法和 {@code GuiIdentity.looksLikePluginGui}
     * （private，供 {@code closeIfPluginGui} 用）。两份实现就是两处漂移面：日后改判定
     * 语义（比如加一类 holder、或对 key 做非空校验）只改一处，另一处会静默地留下旧语义，
     * 表现是「关闭了界面但音效没放」或反向的「玩家背包也放音效」这类难查的分裂行为。
     * 现在 identity 包是唯一入口，本方法只做转发，不再持有判定逻辑。
     *
     * <p>依赖方向 {@code gui} → {@code gui.identity} 无环：{@code GuiIdentity} 刻意
     * 不 import 任何 {@code gui.*} 类（见其类注释的依赖约束）。
     *
     * <p>关于判空：不在这里重复判 {@code view == null} / {@code getTopInventory() == null}
     * —— {@code GuiIdentity.resolve} 内部的 {@code holderOf} 已对两者都做了防御并返回
     * null（见 {@code GuiIdentity:72-79}），重复判空不仅冗余，还会重新长出第二份防御逻辑。
     * 关于开销：{@code resolve} 内部也只是 {@code holderOf(view)}（两次 null 检查 +
     * 一次 {@code instanceof}）加一次 {@code holder.getKey()}，且无日志/无副作用
     * （{@code logMismatch} 已随标题层删除），相对 {@code instanceof} 只多一次静态调用，
     * 在点击/打开事件这种频率下可忽略。
     *
     * @param view 事件或玩家当前界面，可为 null（null 返回 false）
     * @return true 表示插件自有 GUI
     */
    public static boolean isPluginGUI(InventoryView view) {
        return GuiIdentity.resolve(view) != null;
    }
}
