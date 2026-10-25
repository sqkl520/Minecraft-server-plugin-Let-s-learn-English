package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.ai.ConversationFeedback;
import com.letslearnenglish.minecraftplugin.data.ChatFeedbackEntry;
import com.letslearnenglish.minecraftplugin.data.ChatHistoryEntry;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
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

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

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
 *
 * <p>已知越层（P2-27，属重构批次）：本类直调 DatabaseManager 读取会话数据
 *（{@code getChatHistoryCount}/{@code getChatSessions}/{@code getSessionPreview}、
 * {@code getChatSession}），未经 Core 门面。收敛方向 = 抽 Core 门面（会话读侧统一
 * 入口，含批量预取）后 GUI 只依赖门面；接线属重构批次排期，本类暂维持现状。
 *
 * <p>R13-M2（F2）：打开/翻页/详情页已两段式异步化（异步段查库 + 主线程渲染），
 * 主线程零 DB 调用；{@code open(Player, int)}、{@code open(Player)} 与
 * {@code openSession} 均为兼容入口，统一委托 {@code openAsync*}（既有调用点零改动）。
 * P2-27 的 N+1（逐会话 getSessionPreview）保留但移出主线程，与裁决一致。
 */
public class ChatHistoryGUI {

    private static final int GUI_SIZE = 54;

    /**
     * R13-M2/E1：openAsync 在途去重 token（per-GUI-class static 序列 + per-player 记录，
     * 两类同式）。连点/交叉打开时旧请求回主线程 render 前比对失败即作废（闪变与双倍
     * 查库消除）；每次 openAsync 覆写自身条目——量级=历史玩家数条 Long，惰性收口
     * （不做 quit 清理，注释说明，照 E1 裁决）。
     */
    private static final AtomicLong OPEN_SEQ = new AtomicLong();
    private static final Map<UUID, Long> OPEN_SEQ_BY_PLAYER = new ConcurrentHashMap<>();

    public static final int BACK_SLOT = 8;
    public static final int PREV_PAGE_SLOT = 47;
    public static final int NEXT_PAGE_SLOT = 51;
    public static final int PAGE_INDICATOR_SLOT = 45;
    /** US-FB：详情页反馈物品槽位（底行导航区空位，避开 45/47/51/53）。 */
    public static final int FEEDBACK_SLOT = 49;
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

    /**
     * R13-M2（F2）：兼容入口 —— 统一委托 {@link #openAsync(Player, int)}。
     * 既有调用点（LearnCommand / ChatHistoryListener 的翻页与返回）全部零改动，
     * 行为语义与原同步 open 一致（仅改为两段式异步打开）。
     */
    public void open(Player player, int page) {
        openAsync(player, page);
    }

    /**
     * R13-M2（F2）：异步两段式打开入口（照 C-3 DailyChallengeGUI.openAsync 同式）。
     *
     * <p>异步段（仅 DB/logger，无 Bukkit 世界 API）：1 次 count + 1 次 sessions +
     * ≤N 次 preview（N+1 保留但移出主线程，与 F2 裁决"两段式优先，DB 批 API 非必需"
     * 一致）；page clamp 基于真实 totalSessions 在异步段完成。主线程段只渲染
     * （{@link #openWithData}，零 DB 调用）。
     *
     * <p>R-67：异步体开头与 runTask 前各查一次 {@code isEnabled()}；主线程段
     * {@code isOnline()} 复检（异步间隙玩家可能下线）。E1 token：回主线程 render
     * 前比对，连点/交叉打开时旧请求作废（后发起者胜）。
     */
    public void openAsync(Player player, int page) {
        UUID playerId = player.getUniqueId();
        final long token = OPEN_SEQ.incrementAndGet();
        OPEN_SEQ_BY_PLAYER.put(playerId, token);
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!plugin.isEnabled()) return;
                final int totalSessions;
                final int effPage;
                final List<ChatHistoryEntry> sessions;
                final Map<String, String[]> previews;
                try {
                    totalSessions = plugin.getDatabaseManager().getChatHistoryCount(playerId);
                    int totalPages = Math.max(1,
                            (int) Math.ceil((double) totalSessions / SESSIONS_PER_PAGE));
                    effPage = Math.max(1, Math.min(page, totalPages));
                    sessions = plugin.getDatabaseManager()
                            .getChatSessions(playerId, effPage, SESSIONS_PER_PAGE);
                    Map<String, String[]> prev = new HashMap<>();
                    // F2 裁决：N+1（逐会话 preview）保留但移出主线程；P2-27 的批量
                    // 预取收敛方向不变（届时仅改此预取实现，渲染面不动）。
                    for (ChatHistoryEntry session : sessions) {
                        String sessionId = session.getSessionId();
                        if (sessionId != null) {
                            prev.put(sessionId, plugin.getDatabaseManager()
                                    .getSessionPreview(playerId, sessionId));
                        }
                    }
                    previews = prev;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to preload chat history GUI for " + playerId, e);
                    return;
                }
                if (!plugin.isEnabled()) return;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        // E1 token：已有更新的打开请求（翻页/返回/详情），本次作废
                        if (OPEN_SEQ_BY_PLAYER.getOrDefault(playerId, -1L) != token) return;
                        openWithData(player, effPage, totalSessions, sessions, previews);
                    });
                } catch (IllegalStateException ignored) {
                    // 关服竞态：调度器已关闭，放弃本次渲染
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次打开
        }
    }

    /**
     * R13-M2（F2）：主线程渲染 —— 数据由 {@link #openAsync(Player, int)} 预取，
     * 本方法零 DB 调用；渲染段与修复前逐行等价（物品/槽位/lore 全同）。
     */
    private void openWithData(Player player, int page, int totalSessions,
                              List<ChatHistoryEntry> sessions, Map<String, String[]> previews) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msg = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(msg, "chat-history.title"));
        // R5-5：挂结构身份 holder —— 列表页与详情页标题文案不同，本页是 LIST。
        // 两者同为 54 格，尺寸无法区分，必须靠两个 key（见 GuiViewKey 粒度约定）。
        Inventory gui = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.CHAT_HISTORY_LIST), GUI_SIZE, title);

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
        String backLabel = MessageUtil.getGuiMsg(msg, "chat-history.back-button");
        String backLore = MessageUtil.getGuiMsg(msg, "chat-history.back-lore");
        gui.setItem(BACK_SLOT, createItem(Material.RED_STAINED_GLASS_PANE, backLabel, backLore));

        // R13-M2（F2）：totalSessions / page clamp / sessions 均由 openAsync 异步段
        // 预取并 clamp 完成，本处零 DB 调用。
        int totalPages = Math.max(1, (int) Math.ceil((double) totalSessions / SESSIONS_PER_PAGE));

        if (sessions.isEmpty()) {
            // Empty state
            String emptyLabel = MessageUtil.getGuiMsg(msg, "chat-history.empty");
            String emptyLore = MessageUtil.getGuiMsg(msg, "chat-history.empty-lore");
            gui.setItem(22, createItem(Material.PAPER, emptyLabel, emptyLore));
        } else {
            int slotIndex = 0;
            for (ChatHistoryEntry session : sessions) {
                if (slotIndex >= SESSION_SLOTS.length) break;
                // 会话槽位一律从 SESSION_SLOTS 取，绝不自己算偏移，否则会和监听器的判定漂移
                int slot = SESSION_SLOTS[slotIndex];
                slotIndex++;

                String sessionId = session.getSessionId();
                // R13-M2（F2）：preview 由 openAsync 异步段逐会话预取（N+1 保留、移出
                // 主线程）；P2-27 收敛方向不变——届时只改预取实现（DB 批 API），渲染不动。
                // null sessionId 预取时未查库，回读 null 与旧同步查询（＝无行 [null,null]）等价。
                String[] preview = previews.get(sessionId);
                String systemContent = (preview != null) ? preview[0] : null;
                String firstUserContent = (preview != null) ? preview[1] : null;

                // P2-19：提取失败时数据层返回硬编码英文哨兵 "Unknown"（数据层不在本流
                // 白名单，显示侧就地兜底；空串同样按失败处理）；lore 与物品名共用此变量，
                // i18n 化只在一处收口。
                String scenario = ChatHistoryEntry.extractScenarioFromSystemPrompt(systemContent);
                if (scenario == null || scenario.isEmpty() || "Unknown".equals(scenario)) {
                    scenario = MessageUtil.getGuiMsg(msg, "chat-history.unknown-scenario");
                }
                String dateStr = session.getTimestamp() != null
                        ? formatHistoryTimestamp(session.getTimestamp(), "yyyy-MM-dd HH:mm")
                        : MessageUtil.getGuiMsg(msg, "chat-history.unknown-date");
                String userPreview = firstUserContent != null
                        ? (firstUserContent.length() > 40
                                ? firstUserContent.substring(0, 40) + "..."
                                : firstUserContent)
                        : "";

                List<String> lore = new ArrayList<>();
                String dateFormat = MessageUtil.getGuiMsg(msg, "chat-history.session-date");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        dateFormat.replace("{date}", dateStr)));
                String scenarioFormat = MessageUtil.getGuiMsg(msg, "chat-history.session-scenario");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        scenarioFormat.replace("{scenario}", scenario)));
                if (!userPreview.isEmpty()) {
                    String previewFormat = MessageUtil.getGuiMsg(msg, "chat-history.session-preview");
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            previewFormat.replace("{msg}", userPreview)));
                }
                String clickHint = MessageUtil.getGuiMsg(msg, "chat-history.click-to-view");
                lore.add(ChatColor.translateAlternateColorCodes('&', clickHint));

                String itemFormat = MessageUtil.getGuiMsg(msg, "chat-history.session-item-format");
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
            String prevLabel = MessageUtil.getGuiMsg(msg, "chat-history.prev-page");
            String prevLore = MessageUtil.getGuiMsg(msg, "chat-history.prev-page-lore");
            ItemStack prevItem = createItem(Material.ARROW,
                    prevLabel,
                    prevLore.replace("{page}", String.valueOf(page - 1)));
            setPageIndex(prevItem, page - 1); // 目标页码存 PDC，监听器据此翻页
            gui.setItem(PREV_PAGE_SLOT, prevItem);
        }

        // Next page button (slot 51)
        if (page < totalPages) {
            String nextLabel = MessageUtil.getGuiMsg(msg, "chat-history.next-page");
            String nextLore = MessageUtil.getGuiMsg(msg, "chat-history.next-page-lore");
            ItemStack nextItem = createItem(Material.ARROW,
                    nextLabel,
                    nextLore.replace("{page}", String.valueOf(page + 1)));
            setPageIndex(nextItem, page + 1);
            gui.setItem(NEXT_PAGE_SLOT, nextItem);
        }

        // Page indicator (slot 45)
        String pageFormat = MessageUtil.getGuiMsg(msg, "chat-history.page-indicator");
        ItemStack pageIndicator = createItem(Material.NAME_TAG,
                "&e" + pageFormat.replace("{current}", String.valueOf(page))
                        .replace("{total}", String.valueOf(totalPages)),
                "");
        // 当前页码也存进 PDC，监听器据此在返回列表时保持页码，无需解析展示文本
        setPageIndex(pageIndicator, page);
        gui.setItem(PAGE_INDICATOR_SLOT, pageIndicator);

        // R-86 音效去重（S1 裁决）：menu-open 由 SoundManager 全局 InventoryOpen
        // 唯一播放，本处显式播放移除（原先双声）。
        player.openInventory(gui);
    }

    // ============================================================
    //  Expanded Conversation View
    // ============================================================

    /**
     * R13-M2（F2）：兼容入口 —— 统一委托 {@link #openSessionAsync(Player, String, int)}
     * （详情页同为两段式异步，既有调用点零改动）。
     */
    public void openSession(Player player, String sessionId, int returnPage) {
        openSessionAsync(player, sessionId, returnPage);
    }

    /**
     * R13-M2（F2）：详情页异步两段式入口（同列表页 openAsync 同式）。
     * 异步段：{@code getChatSession} 单查 + 反馈行预取（总开关关闭时零查库）；
     * 主线程段只渲染（{@link #openSessionWithData}，零 DB 调用）。E1 token 与
     * 列表页共用（同类共享序列：最后发起的打开请求胜）。
     */
    public void openSessionAsync(Player player, String sessionId, int returnPage) {
        UUID playerId = player.getUniqueId();
        final long token = OPEN_SEQ.incrementAndGet();
        OPEN_SEQ_BY_PLAYER.put(playerId, token);
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!plugin.isEnabled()) return;
                final List<ChatHistoryEntry> messages;
                final ChatFeedbackEntry feedback;
                try {
                    messages = plugin.getDatabaseManager().getChatSession(playerId, sessionId);
                    // plan §3.4 "四处真接线"之三（GUI 物品）：总开关关闭时详情页零物品
                    // 零查库（门在异步段点读，渲染面与旧版表现完全一致）。
                    if (sessionId != null && !sessionId.isEmpty()
                            && plugin.getConfigManager().getMainConfig()
                                    .getBoolean("ai-chat.feedback.enabled", true)) {
                        feedback = plugin.getChatFeedbackManager().getFeedback(playerId, sessionId);
                    } else {
                        feedback = null;
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to preload chat session view for " + playerId, e);
                    return;
                }
                if (!plugin.isEnabled()) return;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (OPEN_SEQ_BY_PLAYER.getOrDefault(playerId, -1L) != token) return;
                        openSessionWithData(player, sessionId, returnPage, messages, feedback);
                    });
                } catch (IllegalStateException ignored) {
                    // 关服竞态：调度器已关闭，放弃本次渲染
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次打开
        }
    }

    /**
     * R13-M2（F2）：详情页主线程渲染 —— 数据由 {@link #openSessionAsync} 预取，
     * 本方法零 DB 调用；渲染段与修复前逐行等价（物品/槽位/lore 全同）。
     */
    private void openSessionWithData(Player player, String sessionId, int returnPage,
                                     List<ChatHistoryEntry> messages, ChatFeedbackEntry feedback) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msg = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(msg, "chat-history.session-title"));
        // R5-5：详情页挂 SESSION key —— 与列表页同尺寸（54），只有 holder 能区分两屏。
        Inventory gui = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.CHAT_HISTORY_SESSION), GUI_SIZE, title);

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
        String backLabel = MessageUtil.getGuiMsg(msg, "chat-history.back-to-list");
        String backLore = MessageUtil.getGuiMsg(msg, "chat-history.back-to-list-lore");
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

        // R13-M2（F2）：messages 由 openSessionAsync 异步段预取，本处零 DB 调用。
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
                    rolePrefix = MessageUtil.getGuiMsg(msg, "chat-history.role-system");
                    break;
                case "user":
                    mat = Material.PAPER;
                    rolePrefix = MessageUtil.getGuiMsg(msg, "chat-history.role-user");
                    break;
                case "assistant":
                    mat = Material.WRITABLE_BOOK;
                    rolePrefix = MessageUtil.getGuiMsg(msg, "chat-history.role-assistant");
                    break;
                default:
                    mat = Material.PAPER;
                    rolePrefix = "&7[" + entry.getRole() + "]";
            }

            String time = entry.getTimestamp() != null
                    ? formatHistoryTimestamp(entry.getTimestamp(), "MM-dd HH:mm") : "";

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
                    // D2-8：防在代理对（emoji/生僻字）中间截断——若截点恰落在 low
                    // surrogate 上则回退一位（wrapLen=40 为字符数而非视觉宽度的语义
                    // 不变，视觉宽度改进超出本批范围）。
                    if (end < full.length() && Character.isLowSurrogate(full.charAt(end))) {
                        end--;
                    }
                    lore.add(ChatColor.WHITE + full.substring(pos, end));
                    pos = end;
                }
            }

            gui.setItem(slot++, createItemLore(mat,
                    ChatColor.translateAlternateColorCodes('&',
                            rolePrefix + " &7" + time + "&f " + displayContent),
                    lore));
        }

        // US-FB（3B 集成，plan §3.6）：反馈槽 49 —— 会话有小结行时渲染只读反馈物品
        //（本地统计 + 有 AI 点评时展开 comment/纠错/推荐词）；无行则留背景玻璃。
        // R13-M2（F2）：反馈行由 openSessionAsync 异步段预取（总开关门/空 sessionId
        // 拦截均在异步段，本处零 DB 调用）。
        renderFeedbackItem(player, gui, msg, feedback);

        // R-86 音效去重（S1 裁决）：menu-open 由 SoundManager 全局 InventoryOpen
        // 唯一播放，本处显式播放移除（原先双声）。
        player.openInventory(gui);
    }

    /**
     * US-FB：详情页反馈物品（FEEDBACK_SLOT=49，底行导航区空位）。数据由
     * {@link #openSessionAsync} 异步段经 ChatFeedbackManager 门面预取（有界索引
     * 查询）；本方法只做渲染（零 DB 调用）。行不存在（老会话/未达标/总开关关闭）
     * 时整槽留背景，不出现空物。
     */
    private void renderFeedbackItem(Player player, Inventory gui, FileConfiguration msg,
                                    ChatFeedbackEntry fb) {
        if (fb == null) {
            return;
        }
        String duration = fb.getDurationSeconds() >= 60
                ? MessageUtil.getGuiMsg(msg, "dialogue.feedback.duration-minutes")
                        .replace("{minutes}", String.valueOf(fb.getDurationSeconds() / 60))
                : MessageUtil.getGuiMsg(msg, "dialogue.feedback.duration-seconds")
                        .replace("{seconds}", String.valueOf(Math.max(0, fb.getDurationSeconds())));
        List<String> lore = new ArrayList<>();
        // plan §2.2 mock-up：场景行（展示名按观察者语言解析，个人场景仅本人可见；
        // 键缺失回落 key，与 ChatFeedbackManager 同姿态）
        if (fb.getScenarioKey() != null && !fb.getScenarioKey().isEmpty()) {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(msg, "chat-history.feedback-scenario")
                            .replace("{scenario}", plugin.getAIChatManager()
                                    .getScenarioDisplayName(player, fb.getScenarioKey()))));
        }
        lore.add(ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(msg, "chat-history.feedback-counts")
                        .replace("{user}", String.valueOf(fb.getUserMessages()))
                        .replace("{ai}", String.valueOf(fb.getAiMessages()))
                        .replace("{duration}", duration)));
        // AI 点评解析（payload JSON）。code tester 5 / arch tester 3 收口：复用
        // ConversationFeedback.parse —— 单一契约（原实现内联第二份 Gson 解析，
        // 部分字段渲染后异常会与 feedback-no-ai 行自相矛盾）。parse 永不抛异常，
        // 返回 null（坏 payload/全字段降级不可解析）才落 no-ai 行；全量解析成功后
        // 才渲染，无中间态窗口。corrections/words 已完成清洗与硬上限（5/8 条）。
        ConversationFeedback feedback = null;
        if ("ai".equals(fb.getSource()) && fb.getPayload() != null) {
            feedback = ConversationFeedback.parse(fb.getPayload());
        }
        if (feedback == null) {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(msg, "chat-history.feedback-no-ai")));
        } else {
            lore.add("");
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(msg, "chat-history.feedback-ai-header")));
            if (!feedback.comment().isEmpty()) {
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(msg, "chat-history.feedback-comment")
                                .replace("{comment}", feedback.comment())));
            }
            if (!feedback.corrections().isEmpty()) {
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(msg, "chat-history.feedback-corrections")
                                .replace("{count}", String.valueOf(feedback.corrections().size()))));
                // config 注释口径：聊天栏只显示前 N 条，历史详情页可见全部
                //（parse 侧已硬上限 5 条）
                for (ConversationFeedback.Correction c : feedback.corrections()) {
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(msg, "chat-history.feedback-correction-line")
                                    .replace("{original}", c.original())
                                    .replace("{corrected}", c.corrected())));
                    if (!c.note().isEmpty()) {
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(msg, "chat-history.feedback-correction-note")
                                        .replace("{note}", c.note())));
                    }
                }
            }
            if (!feedback.words().isEmpty()) {
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(msg, "chat-history.feedback-words")
                                .replace("{words}", String.join(", ", feedback.words()))));
            }
        }
        gui.setItem(FEEDBACK_SLOT, createItemLore(Material.WRITTEN_BOOK,
                ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(msg, "chat-history.feedback-title")),
                lore));
    }

    // ============================================================
    //  Utility
    // ============================================================

    /**
     * R9-H2/R3-H2（显示侧读取换算）：SQLite 的 {@code ai_chat_history.timestamp} 是
     * CURRENT_TIMESTAMP 写入的 UTC 文本（P3-12 时区统一只统了 Java 侧、未统 DB 默认值
     * ——本处收口该裂缝的显示缝口，写入侧不改）。sqlite-jdbc 将库内文本按 JVM 默认时区
     * 解读，{@link Timestamp#toString()} 在合法范围内原样回显库内数字（3.47 驱动实测），
     * 故把这些数字按 UTC 重解析得到真实时刻，再按系统时区渲染；MySQL 的 TIMESTAMP 列
     * 按会话时区自洽，原样渲染；解析失败防御性回落原样渲染。
     */
    private String formatHistoryTimestamp(Timestamp raw, String pattern) {
        if (raw == null) {
            return "";
        }
        SimpleDateFormat out = new SimpleDateFormat(pattern);
        if (plugin.getDatabaseManager().isSQLite()) {
            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                in.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = in.parse(raw.toString());
                if (d != null) {
                    return out.format(d);
                }
            } catch (ParseException ignored) {
                // 解析失败：按原样渲染（防御）
            }
        }
        return out.format(raw);
    }

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
     *
     * <p>R5-5：本方法已降级为<b>迁移期标题兜底</b>—— 两个屏幕（列表 / 详情）现在各自
     * 挂了 {@code PluginGuiHolder}（{@code CHAT_HISTORY_LIST} / {@code CHAT_HISTORY_SESSION}），
     * listener 以 holder 为准，只有 holder 认不出时才调用本方法。方法本身保留原样
     * （宽认领：四个关键词任一命中都算本类 GUI），具体是哪一屏由调用方再按标题细分。
     */
    public static boolean isChatHistoryGUI(String title) {
        if (title == null) return false;
        return title.contains("AI Chat History")
                || title.contains("AI 对话历史")
                || title.contains("Conversation Details")
                || title.contains("对话详情");
    }
}
