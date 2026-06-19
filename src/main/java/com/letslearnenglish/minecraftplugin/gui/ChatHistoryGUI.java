package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.data.ChatHistoryEntry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI Chat History GUI — browse past AI conversations.
 *
 * Page 1: Session list (54-slot, 10 sessions per page).
 * Click a session → show expanded conversation view.
 */
public class ChatHistoryGUI {

    private static final int GUI_SIZE = 54;
    private static final int SESSIONS_PER_PAGE = 10;
    private static final int SESSION_START_SLOT = 0;

    public static final int BACK_SLOT = 8;
    public static final int PREV_PAGE_SLOT = 47;
    public static final int NEXT_PAGE_SLOT = 51;

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
        boolean zh = "zh".equalsIgnoreCase(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                msg.getString("chat-history.title", "&6&lAI Chat History"));
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
        String backLabel = msg.getString("chat-history.back-button", "&c&lBack to Menu");
        String backLore = msg.getString("chat-history.back-lore", "&7Click to return to main menu");
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
            String emptyLabel = msg.getString("chat-history.empty", "&7No chat history yet~");
            String emptyLore = msg.getString("chat-history.empty-lore", "&7Start an AI conversation with /le dialogue");
            gui.setItem(22, createItem(Material.PAPER, emptyLabel, emptyLore));
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            int slot = SESSION_START_SLOT;
            for (ChatHistoryEntry session : sessions) {
                if (slot >= SESSION_START_SLOT + SESSIONS_PER_PAGE) break;

                String sessionId = session.getSessionId();
                String[] preview = plugin.getDatabaseManager()
                        .getSessionPreview(player.getUniqueId(), sessionId);
                String systemContent = preview[0];
                String firstUserContent = preview[1];

                String scenario = ChatHistoryEntry.extractScenarioFromSystemPrompt(systemContent);
                String dateStr = session.getTimestamp() != null
                        ? sdf.format(session.getTimestamp()) : "Unknown";
                String userPreview = firstUserContent != null
                        ? (firstUserContent.length() > 40
                                ? firstUserContent.substring(0, 40) + "..."
                                : firstUserContent)
                        : "";

                List<String> lore = new ArrayList<>();
                String dateFormat = msg.getString("chat-history.session-date", "&7Date: &f{date}");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        dateFormat.replace("{date}", dateStr)));
                String scenarioFormat = msg.getString("chat-history.session-scenario", "&7Scenario: &e{scenario}");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        scenarioFormat.replace("{scenario}", scenario)));
                if (!userPreview.isEmpty()) {
                    String previewFormat = msg.getString("chat-history.session-preview", "&7First: &f{msg}");
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            previewFormat.replace("{msg}", userPreview)));
                }
                String clickHint = msg.getString("chat-history.click-to-view", "&7Click to view full conversation");
                lore.add(ChatColor.translateAlternateColorCodes('&', clickHint));

                String itemFormat = msg.getString("chat-history.session-item-format", "&b&l{scenario} &7- {date}");
                gui.setItem(slot, createItemLore(Material.BOOK,
                        ChatColor.translateAlternateColorCodes('&',
                                itemFormat.replace("{scenario}", scenario).replace("{date}", dateStr)),
                        lore));
                slot++;
            }
        }

        // Previous page button (slot 47)
        if (page > 1) {
            String prevLabel = msg.getString("chat-history.prev-page", "&e← Previous Page");
            String prevLore = msg.getString("chat-history.prev-page-lore", "&7Page {page}");
            gui.setItem(PREV_PAGE_SLOT, createItem(Material.ARROW,
                    prevLabel,
                    prevLore.replace("{page}", String.valueOf(page - 1))));
        }

        // Next page button (slot 51)
        if (page < totalPages) {
            String nextLabel = msg.getString("chat-history.next-page", "&eNext Page →");
            String nextLore = msg.getString("chat-history.next-page-lore", "&7Page {page}");
            gui.setItem(NEXT_PAGE_SLOT, createItem(Material.ARROW,
                    nextLabel,
                    nextLore.replace("{page}", String.valueOf(page + 1))));
        }

        // Page indicator (slot 45)
        String pageFormat = msg.getString("chat-history.page-indicator", "&7Page {current}/{total}");
        gui.setItem(45, createItem(Material.NAME_TAG,
                "&e" + pageFormat.replace("{current}", String.valueOf(page))
                        .replace("{total}", String.valueOf(totalPages)),
                ""));

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
                msg.getString("chat-history.session-title", "&6&lConversation Details"));
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
        String backLabel = msg.getString("chat-history.back-to-list", "&e← Back to List");
        String backLore = msg.getString("chat-history.back-to-list-lore", "&7Return to session list");
        gui.setItem(BACK_SLOT, createItem(Material.RED_STAINED_GLASS_PANE, backLabel, backLore));

        // Return page indicator (stored in lore for listener use)
        // We'll store the return page in a special item
        gui.setItem(53, createItem(Material.PAPER,
                "&7&oReturnPage: " + returnPage,
                "&7SessionID: " + sessionId));

        // Get all messages for this session
        List<ChatHistoryEntry> messages = plugin.getDatabaseManager()
                .getChatSession(player.getUniqueId(), sessionId);

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
        int slot = 0;
        for (ChatHistoryEntry entry : messages) {
            if (slot >= 45) break; // Leave space for nav buttons

            Material mat;
            String rolePrefix;
            switch (entry.getRole()) {
                case "system":
                    mat = Material.NAME_TAG;
                    rolePrefix = msg.getString("chat-history.role-system", "&5[System]");
                    break;
                case "user":
                    mat = Material.PAPER;
                    rolePrefix = msg.getString("chat-history.role-user", "&a[You]");
                    break;
                case "assistant":
                    mat = Material.WRITABLE_BOOK;
                    rolePrefix = msg.getString("chat-history.role-assistant", "&b[AI]");
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
