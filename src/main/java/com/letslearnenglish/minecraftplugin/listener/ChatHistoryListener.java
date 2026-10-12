package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.ChatHistoryGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
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

import java.util.Arrays;

/**
 * Listener for AI Chat History GUI interactions.
 *
 * 会话身份 / 返回页码一律从物品的 {@link PersistentDataContainer} 读取 ——
 * 这些是内部数据，写进物品名或 lore 会让玩家悬停就看到硬编码英文。
 */
public class ChatHistoryListener implements Listener {

    private final LetsLearnEnglish plugin;

    public ChatHistoryListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!ChatHistoryGUI.isChatHistoryGUI(title)) {
            return;
        }

        // R-01 补漏：只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        handleClick(player, event.getSlot(), event.getCurrentItem(), title);
    }

    /**
     * T0-01：拖拽 guard（抄 MiscGUIListener / MainMenuListener 的上半区模式）。
     *
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * AI 对话历史/会话详情页的会话条目（BOOK）/导航箭头/页码标记可被拖进玩家背包。
     * 标题判定与 {@link #onInventoryClick} 入口同源（ChatHistoryGUI.isChatHistoryGUI）；
     * 只在拖拽触及界面上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();

        if (!ChatHistoryGUI.isChatHistoryGUI(title)) {
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

    private void handleClick(Player player, int slot, ItemStack item, String title) {
        boolean isSessionView = title.contains("Conversation Details")
                || title.contains("对话详情");
        boolean isListView = title.contains("AI Chat History")
                || title.contains("AI 对话历史");

        if (isSessionView) {
            handleSessionViewClick(player, slot);
        } else if (isListView) {
            handleListViewClick(player, slot, item);
        }
    }

    private void handleListViewClick(Player player, int slot, ItemStack item) {
        if (slot == ChatHistoryGUI.BACK_SLOT) {
            // Back to main menu (unified with other GUIs)
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }

        if (slot == ChatHistoryGUI.PREV_PAGE_SLOT) {
            // Previous page — 目标页码存在箭头的 PersistentDataContainer 里
            if (item != null && item.getType() == Material.ARROW) {
                SoundManager.playSound(player, "menu-click");
                int targetPage = readPage(item);
                new ChatHistoryGUI(plugin).open(player, targetPage);
            }
            return;
        }

        if (slot == ChatHistoryGUI.NEXT_PAGE_SLOT) {
            // Next page — 同上
            if (item != null && item.getType() == Material.ARROW) {
                SoundManager.playSound(player, "menu-click");
                int targetPage = readPage(item);
                new ChatHistoryGUI(plugin).open(player, targetPage);
            }
            return;
        }

        // Session item click
        // 槽位必须先落在 ChatHistoryGUI.SESSION_SLOTS 内（binarySearch，数组保持升序）
        if (Arrays.binarySearch(ChatHistoryGUI.SESSION_SLOTS, slot) < 0) return;
        if (item == null || item.getType() != Material.BOOK) return;

        // 会话身份由条目 PersistentDataContainer 里的 session id 决定 ——
        // 不要用 slot 反算下标后再查库：翻页期间若有新会话插入，下标会指向错误的会话
        String sessionId = readSessionId(item);
        if (sessionId == null || sessionId.isEmpty()) return;

        SoundManager.playSound(player, "menu-click");
        // 返回页码直接读页码指示器的 PDC，同样不查库
        new ChatHistoryGUI(plugin).openSession(player, sessionId, extractCurrentPage(player));
    }

    /** 从物品的 PersistentDataContainer 取出会话身份。 */
    private String readSessionId(ItemStack item) {
        PersistentDataContainer pdc = readPdc(item);
        return pdc == null ? null : pdc.get(ChatHistoryGUI.sessionIdKey(plugin), PersistentDataType.STRING);
    }

    /** 从物品的 PersistentDataContainer 取出目标页码，取不到时回到第 1 页。 */
    private int readPage(ItemStack item) {
        PersistentDataContainer pdc = readPdc(item);
        if (pdc == null) return 1;
        Integer page = pdc.get(ChatHistoryGUI.returnPageKey(plugin), PersistentDataType.INTEGER);
        return page == null ? 1 : page;
    }

    private PersistentDataContainer readPdc(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer();
    }

    /**
     * 从页码指示器（slot 45）读出当前页码，用于返回列表时保持在同一页。
     */
    private int extractCurrentPage(Player player) {
        ItemStack pageItem = player.getOpenInventory().getItem(ChatHistoryGUI.PAGE_INDICATOR_SLOT);
        int page = readPage(pageItem);
        return page < 1 ? 1 : page;
    }

    private void handleSessionViewClick(Player player, int slot) {
        if (slot == ChatHistoryGUI.BACK_SLOT) {
            // Back to session list
            SoundManager.playSound(player, "menu-click");

            // 返回页码存在 slot 53 标记物品的 PersistentDataContainer 里
            ItemStack hiddenItem = player.getOpenInventory().getItem(ChatHistoryGUI.RETURN_PAGE_SLOT);
            int returnPage = readPage(hiddenItem);

            new ChatHistoryGUI(plugin).open(player, returnPage);
        }
    }
}
