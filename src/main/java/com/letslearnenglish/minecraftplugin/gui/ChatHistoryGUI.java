package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.data.ChatHistoryEntry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI Chat History GUI — browse past AI conversations.
 *
 * Page 1: Session list (54-slot). Click a session → show expanded conversation view.
 *
 * 槽位定义（唯一真相，本类的常量供监听器判定点击，不要在别处再写魔法数字）：
 *   slot 0-7   留白
 *   slot 8     返回主菜单
 *   slot 9-18  会话条目（见 {@link #SESSION_SLOTS}）
 *   slot 22    空状态提示（仅当没有任何会话时）
 *   slot 45    页码指示器
 *   slot 47    上一页
 *   slot 51    下一页
 */
public class ChatHistoryGUI {

    private static final int GUI_SIZE = 54;

    public static final int BACK_SLOT = 8;
    public static final int PREV_PAGE_SLOT = 47;
    public static final int NEXT_PAGE_SLOT = 51;
    public static final int PAGE_INDICATOR_SLOT = 45;
    /** 会话详情视图里承载「返回页码」标记的槽位。 */
    public static final int RETURN_PAGE_SLOT = 53;

    /**
     * 内部数据键 —— 会话身份与返回页码存在 {@link org.bukkit.persistence.PersistentDataContainer} 里，
     * 而不是写进物品名/lore。写进 lore 会渲染成玩家悬停可见的硬编码英文，且与 i18n 无关。
     * 监听器用同一份常量构造 {@link NamespacedKey}，不要在别处再写字符串字面量。
     */
    public static final String SESSION_ID_KEY = "chat_history_session_id";
    public static final String RETURN_PAGE_KEY = "chat_history_return_page";

    /** 构造会话身份数据键。 */
    public static NamespacedKey sessionIdKey(LetsLearnEnglish plugin) {
        return new NamespacedKey(plugin, SESSION_ID_KEY);
    }

    /** 构造返回页码数据键。 */
    public static NamespacedKey returnPageKey(LetsLearnEnglish plugin) {
        return new NamespacedKey(plugin, RETURN_PAGE_KEY);
    }

    /**
     * 会话条目占用的槽位（显式列出，方便一眼看懂）。
     * 从第二行开始，slot 0-8 留给返回键与留白。
     *
     * 必须保持升序 —— {@link com.letslearnenglish.minecraftplugin.listener.ChatHistoryListener}
     * 用 Arrays.binarySearch 反查会话下标。
     */
    public static final int[] SESSION_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17, 18
    };

    /** 会话区起始槽位（由 SESSION_SLOTS 推导，避免两处魔法数字漂移）。 */
    public static final int SESSION_START_SLOT = SESSION_SLOTS[0];

    /** 每页会话数 = 槽位数。 */
    private static final int SESSIONS_PER_PAGE = SESSION_SLOTS.length;

    private final LetsLearnEnglish plugin;

    public ChatHistoryGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //  Session List View
    // ============================================================

    public void open(Player player) {
        open(player, 1);
    }

    public void open(Player player, int page) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msg = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                msg.getString("chat-history.title"));
        Inventory gui = Bukkit.createInventory(null, GUI_SIZE, title);

        // Background
        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        if (bgMeta != null) {
            bgMeta.setDisplayName(" ");
            bg.setItemMeta(bgMeta);
        }
        for (int i = 0; i < GUI_SIZE; i++) {
            gui.setItem(i, bg.clone());
        }

        // Back button (upper-right corner, unified with other GUIs)
        String backLabel = msg.getString("chat-history.back-button");
        String backLore = msg.getString("chat-history.back-lore");
        gui.setItem(BACK_SLOT, createItem(Material.RED_STAINED_GLASS_PANE, backLabel, backLore));

        // Get sessions
        int totalSessions = plugin.getDatabaseManager().getChatHistoryCount(player.getUniqueId());
        int totalPages = Math.max(1, (int) Math.ceil((double) totalSessions / SESSIONS_PER_PAGE));

        // Clamp page
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        List<ChatHistoryEntry> sessions = plugin.getDatabaseManager()
                .getChatSessions(player.getUniqueId(), page, SESSIONS_PER_PAGE);

        if (sessions.isEmpty()) {
            // Empty state
            String emptyLabel = msg.getString("chat-history.empty");
            String emptyLore = msg.getString("chat-history.empty-lore");
            gui.setItem(22, createItem(Material.PAPER, emptyLabel, emptyLore));
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            int slotIndex = 0;
            for (ChatHistoryEntry session : sessions) {
                if (slotIndex >= SESSION_SLOTS.length) break;
                // 会话槽位一律从 SESSION_SLOTS 取，绝不自己算偏移，否则会和监听器的判定漂移
                int slot = SESSION_SLOTS[slotIndex];
                slotIndex++;

                String sessionId = session.getSessionId();
                String[] preview = plugin.getDatabaseManager()
                        .getSessionPreview(player.getUniqueId(), sessionId);
                String systemContent = preview[0];
                String firstUserContent = preview[1];

                String scenario = ChatHistoryEntry.extractScenarioFromSystemPrompt(systemContent);
                String dateStr = session.getTimestamp() != null
                        ? sdf.format(session.getTimestamp()) : msg.getString("chat-history.unknown-date");
                String userPreview = firstUserContent != null
                        ? (firstUserContent.length() > 40
                                ? firstUserContent.substring(0, 40) + "..."
                                : firstUserContent)
                        : "";

                List<String> lore = new ArrayList<>();
                String dateFormat = msg.getString("chat-history.session-date");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        dateFormat.replace("{date}", dateStr)));
                String scenarioFormat = msg.getString("chat-history.session-scenario");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        scenarioFormat.replace("{scenario}", scenario)));
                if (!userPreview.isEmpty()) {
                    String previewFormat = msg.getString("chat-history.session-preview");
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            previewFormat.replace("{msg}", userPreview)));
                }
                String clickHint = msg.getString("chat-history.click-to-view");
                lore.add(ChatColor.translateAlternateColorCodes('&', clickHint));

                String itemFormat = msg.getString("chat-history.session-item-format");
                // 会话身份存进 PersistentDataContainer —— 不写进 lore，
                // 否则玩家悬停就会看到 "SessionID: xxx" 这类内部硬编码英文。
                ItemStack sessionItem = createItemLore(Material.BOOK,
                        ChatColor.translateAlternateColorCodes('&',
                                itemFormat.replace("{scenario}", scenario).replace("{date}", dateStr)),
                        lore);
                if (sessionId != null && !sessionId.isEmpty()) {
                    setSessionId(sessionItem, sessionId);
                }
                gui.setItem(slot, sessionItem);
            }
        }

        // Previous page button (slot 47)
        if (page > 1) {
            String prevLabel = msg.getString("chat-history.prev-page");
            String prevLore = msg.getString("chat-history.prev-page-lore");
            ItemStack prevItem = createItem(Material.ARROW,
                    prevLabel,
                    prevLore.replace("{page}", String.valueOf(page - 1)));
            setPageIndex(prevItem, page - 1); // 目标页码存 PDC，监听器据此翻页
            gui.setItem(PREV_PAGE_SLOT, prevItem);
        }

        // Next page button (slot 51)
        if (page < totalPages) {
            String nextLabel = msg.getString("chat-history.next-page");
            String nextLore = msg.getString("chat-history.next-page-lore");
            ItemStack nextItem = createItem(Material.ARROW,
                    nextLabel,
                    nextLore.replace("{page}", String.valueOf(page + 1)));
            setPageIndex(nextItem, page + 1);
            gui.setItem(NEXT_PAGE_SLOT, nextItem);
        }

        // Page indicator (slot 45)
        String pageFormat = msg.getString("chat-history.page-indicator");
        ItemStack pageIndicator = createItem(Material.NAME_TAG,
                "&e" + pageFormat.replace("{current}", String.valueOf(page))
                        .replace("{total}", String.valueOf(totalPages)),
                "");
        // 当前页码也存进 PDC，监听器据此在返回列表时保持页码，无需解析展示文本
        setPageIndex(pageIndicator, page);
        gui.setItem(PAGE_INDICATOR_SLOT, pageIndicator);

        SoundManager.playSound(player, "menu-open");
        player.openInventory(gui);
    }

    // ============================================================
    //  Expanded Conversation View
    // ============================================================

    public void openSession(Player player, String sessionId, int returnPage) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msg = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                msg.getString("chat-history.session-title"));
        Inventory gui = Bukkit.createInventory(null, GUI_SIZE, title);

        // Background
        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        if (bgMeta != null) {
            bgMeta.setDisplayName(" ");
            bg.setItemMeta(bgMeta);
        }
        for (int i = 0; i < GUI_SIZE; i++) {
            gui.setItem(i, bg.clone());
        }

        // Back button — returns to session list (unified slot with list view)
        String backLabel = msg.getString("chat-history.back-to-list");
        String backLore = msg.getString("chat-history.back-to-list-lore");
        gui.setItem(BACK_SLOT, createItem(Material.RED_STAINED_GLASS_PANE, backLabel, backLore));

        // 返回页码 / 会话身份存在 PersistentDataContainer 里（不是物品名，玩家看不到），
        // 物品本身用背景玻璃板伪装，避免出现悬停可见的 "ReturnPage: n" 硬编码英文。
        ItemStack marker = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta markerMeta = marker.getItemMeta();
        if (markerMeta != null) {
            markerMeta.setDisplayName(" ");
            markerMeta.getPersistentDataContainer().set(returnPageKey(plugin),
                    PersistentDataType.INTEGER, returnPage);
            if (sessionId != null && !sessionId.isEmpty()) {
                markerMeta.getPersistentDataContainer().set(sessionIdKey(plugin),
                        PersistentDataType.STRING, sessionId);
            }
            marker.setItemMeta(markerMeta);
        }
        gui.setItem(RETURN_PAGE_SLOT, marker);

        // Get all messages for this session
        List<ChatHistoryEntry> messages = plugin.getDatabaseManager()
                .getChatSession(player.getUniqueId(), sessionId);

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
        int slot = 0;
        for (ChatHistoryEntry entry : messages) {
            if (slot >= PAGE_INDICATOR_SLOT) break; // 底行留给返回键标记与导航，不铺消息
            // 跳过返回键槽位，否则第 9 条消息会盖住返回按钮（点它会误跳回列表）
            if (slot == BACK_SLOT) slot++;

            Material mat;
            String rolePrefix;
            switch (entry.getRole()) {
                case "system":
                    mat = Material.NAME_TAG;
                    rolePrefix = msg.getString("chat-history.role-system");
                    break;
                case "user":
                    mat = Material.PAPER;
                    rolePrefix = msg.getString("chat-history.role-user");
                    break;
                case "assistant":
                    mat = Material.WRITABLE_BOOK;
                    rolePrefix = msg.getString("chat-history.role-assistant");
                    break;
                default:
                    mat = Material.PAPER;
                    rolePrefix = "&7[" + entry.getRole() + "]";
            }

            String time = entry.getTimestamp() != null
                    ? sdf.format(entry.getTimestamp()) : "";

            // Truncate content for display
            String displayContent = entry.getContent() != null
                    ? (entry.getContent().length() > 50
                            ? entry.getContent().substring(0, 50) + "..."
                            : entry.getContent())
                    : "";

            List<String> lore = new ArrayList<>();
            // Show full content in lore (split into lines)
            if (entry.getContent() != null) {
                String full = entry.getContent();
                // Word-wrap at ~40 chars for readability
                int wrapLen = 40;
                int pos = 0;
                while (pos < full.length()) {
                    int end = Math.min(pos + wrapLen, full.length());
                    lore.add(ChatColor.WHITE + full.substring(pos, end));
                    pos = end;
                }
            }

            gui.setItem(slot++, createItemLore(mat,
                    ChatColor.translateAlternateColorCodes('&',
                            rolePrefix + " &7" + time + "&f " + displayContent),
                    lore));
        }

        SoundManager.playSound(player, "menu-open");
        player.openInventory(gui);
    }

    // ============================================================
    //  Utility
    // ============================================================

    private ItemStack createItem(Material material, String name, String lore) {
        return createItemLore(material, name,
                lore != null ? Arrays.asList(ChatColor.translateAlternateColorCodes('&', lore)) : new ArrayList<>());
    }

    /** 把会话身份写进物品的 PersistentDataContainer（玩家不可见）。 */
    private void setSessionId(ItemStack item, String sessionId) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(sessionIdKey(plugin), PersistentDataType.STRING, sessionId);
        item.setItemMeta(meta);
    }

    /** 把当前页码写进页码指示器的 PersistentDataContainer（玩家不可见）。 */
    private void setPageIndex(ItemStack item, int page) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(returnPageKey(plugin), PersistentDataType.INTEGER, page);
        item.setItemMeta(meta);
    }

    private ItemStack createItemLore(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Check if an inventory title is a ChatHistoryGUI.
     */
    public static boolean isChatHistoryGUI(String title) {
        if (title == null) return false;
        return title.contains("AI Chat History")
                || title.contains("AI 对话历史")
                || title.contains("Conversation Details")
                || title.contains("对话详情");
    }
}
