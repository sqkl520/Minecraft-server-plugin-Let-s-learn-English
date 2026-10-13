package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import com.letslearnenglish.minecraftplugin.gui.DuelChallengeGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Listener for the Duel Challenge GUI (发起对决在线玩家选择器, MC-1 / R-82).
 *
 * <p>Click mapping：目标玩家身份从头像物品的 {@link PersistentDataContainer}
 * 读取（{@link DuelChallengeGUI#TARGET_ID_KEY}）——不按槽位反算下标，
 * 在线名单在界面打开后变化也不会「点 A 挑战 B」，因此本类无 per-player 状态、
 * 无需 quit 清理。
 *
 * <p>标题判定：{@link DuelChallengeGUI#isDuelChallengeTitle}（zh/en 两份配置的
 * <b>完整标题等值</b>，照 ConfirmationGUI.isConfirmGUI 模式）+ 尺寸 54 双重确认 ——
 * 绝不用 "对决" 这类宽子串，防与 DuelGUI 的「单词对决」标题互相误伤
 * （plan §2.1 标题避碰红线）。
 */
public class DuelChallengeListener implements Listener {

    private final LetsLearnEnglish plugin;

    public DuelChallengeListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        if (!isDuelChallengeGUI(event.getView().getTitle(), topSize)) return;

        // R-01/R-22: 只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        if (event.getClickedInventory() == null) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();

        if (slot == DuelChallengeGUI.BACK_SLOT) {
            // 返回主菜单 —— 单层 GUI 直开（与 ScenarioSelectListener/WrongBookListener
            // 的返回键同模式，不引返回栈）。
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }

        // 头像点击 → 发起对决。目标身份一律读物品 PDC（含空态/说明/尾槽提示
        // 这些非头像物品类型不符或无键，天然 no-op）。
        ItemStack clicked = event.getCurrentItem();
        if (clicked.getType() != Material.PLAYER_HEAD) return;
        UUID targetId = readTargetId(clicked);
        if (targetId == null) return;

        DuelManager duelManager = plugin.getDuelManager();
        if (duelManager == null) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "general.error", "message",
                    plugin.getMessageUtil().getPlayerMessage(player, "error.duel-unavailable")));
            return;
        }

        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            // 列举打开后对方下线（R2 竞态）：给准确反馈，不发无效邀请
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "duel.player-offline"));
            return;
        }

        // 不预判忙态/AI 对话/答题互斥（plan §2.1）：全部守卫在
        // sendDuelRequest 内部复用，提示准确；预判与点击的竞态反而会误拒。
        SoundManager.playSound(player, "menu-click");
        duelManager.sendDuelRequest(player, target);
    }

    /**
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 头像/说明物/返回键可被拖进玩家背包。
     *
     * 标题判定与 {@link #onInventoryClick} 入口同源（等值 + 尺寸双重确认）；
     * 只在拖拽触及界面上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        if (!isDuelChallengeGUI(event.getView().getTitle(), topSize)) return;

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * 本选择器界面判定：标题与配置完全等值（zh/en 两份）且尺寸为 54。
     * 尺寸是标题等值之外的独立第二条件（双重确认），两个条件都来自
     * {@link DuelChallengeGUI} 的同一套常量/方法，不重复魔法数字。
     */
    private boolean isDuelChallengeGUI(String title, int topSize) {
        return DuelChallengeGUI.isDuelChallengeTitle(title)
                && topSize == DuelChallengeGUI.GUI_SIZE;
    }

    /** 从头像物品的 PersistentDataContainer 取出目标玩家 UUID；无键/坏值时返回 null。 */
    private UUID readTargetId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String raw = pdc.get(DuelChallengeGUI.targetIdKey(plugin), PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
