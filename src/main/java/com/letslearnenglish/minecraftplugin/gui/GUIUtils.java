package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.logging.Logger;

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

    // ------------------------------------------------------------------
    // R12-slot：配置驱动槽位的边界校验（gui-layout.<gui>.<item>.slot）
    // ------------------------------------------------------------------

    /**
     * 槽位是否落在该界面的合法区间 {@code [0, inventorySize)}。
     *
     * <p><b>为什么需要这一层</b>：{@code ConfigManager#getGuiSlot} 是<b>无校验直读</b>
     * （{@code mainConfig.getInt(path, defaultSlot)}），而 Bukkit 的
     * {@code Inventory#setItem} 对越界下标<b>不做钳制也不报友好错误</b>：Paper 1.21.4 的
     * {@code CraftInventoryCustom$MinecraftInventory.setItem} → {@code NonNullList.set}
     * → {@code List.set}，越界抛 {@code ArrayIndexOutOfBoundsException}（不是
     * {@code IllegalArgumentException}）。因此「服主把 slot 手滑多打一位」的后果是
     * {@code open()} 在 {@code player.openInventory(inv)} 之前就抛异常 —— 玩家侧表现是
     * 「点了没反应」，服主侧是日志里一条栈。{@code config.yml} 的注释只写
     * 「0-53，主菜单 0-26」，27 格的 word-learning 写 28 属于「看起来合法」，很容易踩。
     *
     * <p><b>为什么尺寸由调用方传入、不读 {@code inv.getSize()}</b>：本仓每个 GUI 的尺寸
     * 都是自己 createInventory 时定下的常量（{@code WordLearningGUI.MENU_SIZE}=27、
     * {@code EnglishSquareGUI.GUI_SIZE}=54…），传常量最忠实；而且单测里
     * {@code createInventory} 返回的是 mock，{@code getSize()} 恒为 0，读它会退化成
     * 「全部越界」。同一份尺寸常量同时喂 createInventory 与本校验，两处不可能漂移。
     *
     * @param slot          配置读出的槽位
     * @param inventorySize 本界面的格数
     * @return true = 可以安全 setItem
     */
    public static boolean isSlotInRange(int slot, int inventorySize) {
        return slot >= 0 && slot < inventorySize;
    }

    /**
     * 越界告警的统一文案（只造字符串，不打日志）—— {@link #readSlot} /
     * {@link #readSlots} 与 {@code MainMenu} 三处共用，避免同一件事有三份措辞。
     *
     * <p>刻意带上<b>完整配置路径</b>与实际值：服主看到日志的第一反应是去 config.yml
     * 找那一行，少给一段路径就得自己拼。
     *
     * @param guiName       {@code gui-layout.<guiName>}
     * @param itemKey       {@code .<itemKey>.slot}
     * @param actual        配置里的实际值
     * @param inventorySize 本界面的格数
     * @return 形如 {@code Invalid gui-layout.word-learning.start-button.slot: 28 (must be 0..26)}
     */
    public static String slotWarningMessage(String guiName, String itemKey, int actual,
                                            int inventorySize) {
        return "Invalid gui-layout." + guiName + "." + itemKey + ".slot: " + actual
                + " (must be 0.." + (inventorySize - 1) + ")";
    }

    /**
     * 读一个配置槽位，越界时<b>回落该键的默认值</b>并打 WARNING（含配置路径与实际值）。
     *
     * <p><b>为什么不静默用 0</b>：把多个项都钳到 0 会在一格里堆叠多个图标 —— 渲染是
     * 「后写覆盖先写」、点击是 if-else「先命中者胜」，玩家看到 A 点下去执行 B。
     * {@code MainMenu} 已把这类同槽错位判为「不可用伪装成可用」（最坏的一类静默失败），
     * 所以这里**只回落该键自己的默认值**：默认值是出厂配置里那套经过核对、两两不同的布局。
     *
     * <p><b>回落的是默认值、不是"钳到最近的合法值"</b>：钳到 {@code size-1} 会让两个
     * 越界键落到同一格，又变回上面那个同槽问题；而默认值对每个键是唯一的。
     *
     * @param cm            配置管理器
     * @param logger        日志出口，允许为 null（纯 JVM 单测里 {@code plugin.getLogger()}
     *                      可能是 null；本方法的契约是「永不抛」，不能因为写不了日志就崩）
     * @param guiName       {@code gui-layout.<guiName>}
     * @param itemKey       {@code .<itemKey>.slot}
     * @param defaultSlot   该键的出厂默认槽位
     * @param inventorySize 本界面的格数
     * @return 可直接 setItem 的槽位
     */
    public static int readSlot(ConfigManager cm, Logger logger, String guiName, String itemKey,
                               int defaultSlot, int inventorySize) {
        int configured = cm.getGuiSlot(guiName, itemKey, defaultSlot);
        if (isSlotInRange(configured, inventorySize)) {
            return configured;
        }
        int fallback = defaultSlot;
        String suffix;
        if (isSlotInRange(defaultSlot, inventorySize)) {
            suffix = ". Falling back to default slot " + defaultSlot + ".";
        } else {
            // 最后一道保险：连默认值都放不下（例如把某个键的默认值调大了却漏改尺寸）。
            // 宁可钳进合法区间 + 明确告警，也不能让 setItem 抛出去把整个界面打死。
            fallback = Math.max(0, Math.min(inventorySize - 1, defaultSlot));
            suffix = ". Falling back to the built-in default " + defaultSlot
                    + ", which does not fit this inventory either; clamped to " + fallback + ".";
        }
        if (logger != null) {
            logger.warning(slotWarningMessage(guiName, itemKey, configured, inventorySize) + suffix);
        }
        return fallback;
    }

    /**
     * 批量读一组配置槽位，<b>任一越界即整组回落默认表</b>并逐个 WARNING。
     *
     * <p>这是 {@code MainMenu} 的既有策略（R8-L3 / D3-7）：主菜单那 9 个按钮是一套
     * 视觉布局，逐键回落会把「其中两个键落到同一格」这种新错位造出来，而整组回落用的是
     * 出厂那套两两不同的槽位表。渲染侧回落、点击侧（{@code MainMenuListener}）仍读原配置
     * —— 配置合法时两侧天然一致；配置非法时属管理员自担（WARNING 已明示路径与值），
     * 本层只保「不崩」。
     *
     * @param cm            配置管理器
     * @param logger        日志出口，允许为 null
     * @param guiName       {@code gui-layout.<guiName>}
     * @param itemKeys      槽位键（与 {@code defaults} 逐位对应）
     * @param defaults      默认槽位表（与 {@code itemKeys} 逐位对应）
     * @param inventorySize 本界面的格数
     * @return 长度为 {@code itemKeys.length} 的槽位数组（全部合法）
     */
    public static int[] readSlots(ConfigManager cm, Logger logger, String guiName,
                                  String[] itemKeys, int[] defaults, int inventorySize) {
        int[] slots = new int[itemKeys.length];
        boolean valid = true;
        for (int i = 0; i < itemKeys.length; i++) {
            slots[i] = cm.getGuiSlot(guiName, itemKeys[i], defaults[i]);
            if (!isSlotInRange(slots[i], inventorySize)) {
                if (logger != null) {
                    logger.warning(slotWarningMessage(guiName, itemKeys[i], slots[i], inventorySize)
                            + ". Falling back to default " + guiName + " layout.");
                }
                valid = false;
            }
        }
        return valid ? slots : defaults.clone();
    }
}
