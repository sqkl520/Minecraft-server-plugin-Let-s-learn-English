package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.ai.PersonalScenarioStore;
import com.letslearnenglish.minecraftplugin.gui.PersonalScenarioGUI;
import com.letslearnenglish.minecraftplugin.gui.ScenarioSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 个人场景管理页（{@link PersonalScenarioGUI}）点击/拖拽守卫 — Phase 3 · MC-4。
 *
 * <p>R5-11：界面识别<b>只认 holder</b>。{@link PersonalScenarioGUI#open}
 * 打开时已挂 {@link com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder}
 * （{@link GuiViewKey#PERSONAL_SCENARIO}）；原
 * {@code PersonalScenarioGUI.isPersonalScenarioTitle} + 尺寸 54 双重确认的标题兜底（R5-14 已删除）已废弃。
 *
 * <p>行内二次确认（首点红 / 再点删 / 其它点击复位）为"屏幕即真相"实现：确认态记在
 * 条目物品的 PDC 标记上（{@link PersonalScenarioGUI#confirmKey}），本 Listener
 * <b>无 per-player 状态</b>、无 quit 清理面 —— 对比 Map&lt;UUID,String&gt; 方案，
 * 杜绝"ESC 重开页面后旧确认态残留 → 首点即误删"的快照脱节类缺陷
 * （DuelChallengeGUI 同款无状态模式）。
 */
public class PersonalScenarioListener implements Listener {

    private final LetsLearnEnglish plugin;

    public PersonalScenarioListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // ── R5-7 判定顺序（顺序不可调整）────────────────────────────────────
        // 1) 先判窗外：getClickedInventory() 只在点到界面之外时为 null。
        if (event.getClickedInventory() == null) return;

        // 2) 再判下半区（玩家背包）：rawSlot 落在 top 之外一律不处理、也不 cancel，
        //    玩家开着 GUI 仍能正常整理背包（R-01 语义原样保留）。
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        // 3) 然后才做身份判定：只认 holder（结构身份）。
        if (!isManageView(event.getView())) return;

        // 4) 只有「本页上半区点击」才 cancel —— 绝不允许只判 holder 就 cancel
        //    （event.getInventory() 恒为上半区，与玩家实际点击的位置无关）。
        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();

        // 返回：弹回进管理页前压入的「场景选择页」帧（SocialListener 子页返回模式）。
        // 返回键不设权限门 —— 不困住玩家。
        if (slot == PersonalScenarioGUI.BACK_SLOT) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new ScenarioSelectGUI(plugin).open(player));
            backAction.run();
            return;
        }

        // 纵深：无 letslearnenglish.scenario.import 权限不可达（入口按钮已隐藏；此处防
        // "页面开着时权限被撤 / 旧页面残留"的点击 —— 与 /le load_scenario 及 slot 26
        // API 刷新键同一权限口径）。
        if (!player.hasPermission("letslearnenglish.scenario.import")) {
            SoundManager.playSound(player, "incorrect");
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.load-no-permission"));
            player.closeInventory();
            return;
        }

        String entryKey = readEntryKey(event.getCurrentItem());
        if (entryKey == null) {
            // 非条目点击（计数物/玻璃板/空态物）—— "期间任何其它点击先复位"：
            // 仅当屏幕上确实存在红字确认条目时才重开干净页（无确认态则 no-op，不重绘）。
            if (hasConfirmMarker(event)) {
                SoundManager.playSound(player, "menu-click");
                new PersonalScenarioGUI(plugin).open(player, null);
            }
            return;
        }

        if (isConfirmed(event.getCurrentItem())) {
            // 确认态再点 → 执行删除（key 来自物品 PDC，与槽位下标解耦）
            handleDelete(player, entryKey);
        } else {
            // 首点（或点其它条目先复位）→ 该条目进入确认态（红字），其余条目随之复位
            SoundManager.playSound(player, "menu-click");
            new PersonalScenarioGUI(plugin).open(player, entryKey);
        }
    }

    /**
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 条目/计数物/返回键可被拖进玩家背包。
     *
     * <p>身份判定与 {@link #onInventoryClick} 入口同源（R5-11：只认 holder）；
     * 只在拖拽触及界面上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isManageView(event.getView())) return;

        // 身份判定不蕴含「拖的是上半区」，rawSlot < topSize 的判断必须保留。
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * R5-11：本页归属判定 —— <b>只认 holder</b>（{@link GuiViewKey#PERSONAL_SCENARIO}）。
     *
     * <p>原标题等值 + 尺寸 54 的双重确认兜底已删除；其余（含 {@code null}）一律不是本页。
     *
     * @param view 事件界面，可为 null
     * @return true 表示本页属于个人场景管理页
     */
    private boolean isManageView(InventoryView view) {
        return GuiIdentity.resolve(view) == GuiViewKey.PERSONAL_SCENARIO;
    }

    /**
     * 删除执行与结果分发（plan §3.4）：成功 / 未找到 / 保存失败 三种 i18n 提示，
     * 删除后原地重开管理页（与 WrongBook 删词同模式 —— 失败也重开，刷新屏幕状态）。
     */
    private void handleDelete(Player player, String key) {
        // 删除前取展示名（删除后 getScenarioDisplayName 只会回退到 key 本身）
        String displayName = plugin.getAIChatManager().getScenarioDisplayName(player, key);
        PersonalScenarioStore.DeleteResult result =
                plugin.getAIChatManager().deletePersonalScenario(player, key);

        if (result.deleted()) {
            SoundManager.playSound(player, "menu-click");
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.manage-deleted", "name", displayName));
        } else if (result.notFound()) {
            SoundManager.playSound(player, "incorrect");
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.manage-not-found"));
        } else {
            SoundManager.playSound(player, "incorrect");
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "scenario.manage-save-failed"));
        }

        new PersonalScenarioGUI(plugin).open(player, null);
    }

    /** 从条目物品的 PDC 取出个人场景 key；非条目物/无键/坏值返回 null。 */
    private String readEntryKey(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String key = meta.getPersistentDataContainer().get(
                PersonalScenarioGUI.entryKey(plugin), PersistentDataType.STRING);
        return (key == null || key.isEmpty()) ? null : key;
    }

    /** 条目物品是否处于「确认删除」态（PDC 标记；红字与标记同源渲染）。 */
    private boolean isConfirmed(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(
                PersonalScenarioGUI.confirmKey(plugin), PersistentDataType.STRING);
    }

    /** 当前屏幕上是否存在任一确认态条目（"其它点击先复位"的判定依据）。 */
    private boolean hasConfirmMarker(InventoryClickEvent event) {
        for (ItemStack item : event.getView().getTopInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;
            if (meta.getPersistentDataContainer().has(
                    PersonalScenarioGUI.confirmKey(plugin), PersistentDataType.STRING)) {
                return true;
            }
        }
        return false;
    }
}
