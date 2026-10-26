package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.ChatHistoryGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
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
        // ── R5-5 判定顺序（四条铁律，顺序不可调整）────────────────────────────
        // 1) 先判窗外：getClickedInventory() 只在点到界面之外时为 null。
        if (event.getClickedInventory() == null) return;

        // 2) 再判下半区（玩家背包）：rawSlot 落在 top 之外一律不处理、也不 cancel，
        //    玩家开着 GUI 仍能正常整理背包。
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();

        // FIX-C2：下半区 shift-click（MOVE_TO_OTHER_INVENTORY）与双击收集
        //（COLLECT_TO_CURSOR）的前置例外，照 SocialListener / MiscGUIListener 同式。
        //
        // 位置铁律：必须在下面 rawSlot 越界 return 之前 —— 这两类 action 的 rawSlot 落在
        // 下半区（玩家背包），一旦让越界 return 先吃掉就永远走不到本分支。
        //
        // 为什么必需：MOVE_TO_OTHER_INVENTORY 会把玩家背包物品合并进上半区同类堆叠
        //（不依赖空槽）；COLLECT_TO_CURSOR 会把上半区展示物扫进光标（反向失窃）。
        // 本页虽靠自铺满维持无空槽，但合堆与收集两条路径都不经过空槽。
        //
        // 为什么不含 PICKUP_ALL：Paper 1.21.4 把「光标空着、左键拾取整叠」映射为
        // PICKUP_ALL（点玩家自己背包的格子也走这个），拦它等于把「开着 GUI 整理背包」
        // 一并吞掉，与 R-01/R-22 语义冲突；双击收集的落点是 COLLECT_TO_CURSOR，已在守卫内。
        // 归属判定必须写在这里（holder 判定在第 3 步、位于越界 return 之后）。
        if (rawSlot >= topSize
                && (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        || event.getAction() == InventoryAction.COLLECT_TO_CURSOR)) {
            if (resolveOwnedView(event.getView()) != null) {
                event.setCancelled(true);
            }
            return;
        }

        if (rawSlot < 0 || rawSlot >= topSize) return;

        // 3) 然后才做身份判定：只认 holder（结构身份）。
        GuiViewKey key = resolveOwnedView(event.getView());
        if (key == null) return;

        // 4) 只有「本类辖管界面的上半区点击」才 cancel —— 绝不允许只判 holder 就 cancel
        //    （event.getInventory() 恒为上半区，与玩家实际点击的位置无关）。
        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        handleClick(player, key, event.getSlot(), event.getCurrentItem());
    }

    /**
     * T0-01：拖拽 guard（抄 MiscGUIListener / MainMenuListener 的上半区模式）。
     *
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * AI 对话历史/会话详情页的会话条目（BOOK）/导航箭头/页码标记可被拖进玩家背包。
     * 身份判定与 {@link #onInventoryClick} 入口同源（{@link #resolveOwnedView}）；
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
     * R5-11：解析「本监听器负责的界面」—— <b>只认 holder</b>。
     *
     * <p>两个屏幕同为 54 格、标题相近（"AI 对话历史" / "对话详情"），
     * holder 给出的是 {@link GuiViewKey#CHAT_HISTORY_LIST} 或
     * {@link GuiViewKey#CHAT_HISTORY_SESSION}。标题兜底
     * （{@code ChatHistoryGUI.isChatHistoryGUI}）已随 R5-11 删除。
     *
     * @param view 事件界面，可为 null
     * @return 本类辖管的 key；不归本类管时返回 null
     */
    private GuiViewKey resolveOwnedView(InventoryView view) {
        GuiViewKey byHolder = GuiIdentity.resolve(view);
        if (byHolder == GuiViewKey.CHAT_HISTORY_LIST
                || byHolder == GuiViewKey.CHAT_HISTORY_SESSION) {
            return byHolder;
        }
        return null;
    }

    private void handleClick(Player player, GuiViewKey key, int slot, ItemStack item) {
        if (key == GuiViewKey.CHAT_HISTORY_SESSION) {
            handleSessionViewClick(player, slot);
        } else if (key == GuiViewKey.CHAT_HISTORY_LIST) {
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
