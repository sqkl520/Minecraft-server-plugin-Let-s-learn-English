package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.data.ChatHistoryEntry;
import com.letslearnenglish.minecraftplugin.gui.ChatHistoryGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Listener for AI Chat History GUI interactions.
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

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        handleClick(player, event.getSlot(), event.getCurrentItem(), title);
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
            // Previous page — lore stores the target page number
            if (item != null && item.getType() == Material.ARROW) {
                SoundManager.playSound(player, "menu-click");
                int targetPage = extractPageFromLore(item);
                new ChatHistoryGUI(plugin).open(player, targetPage);
            }
            return;
        }

        if (slot == ChatHistoryGUI.NEXT_PAGE_SLOT) {
            // Next page — lore stores the target page number
            if (item != null && item.getType() == Material.ARROW) {
                SoundManager.playSound(player, "menu-click");
                int targetPage = extractPageFromLore(item);
                new ChatHistoryGUI(plugin).open(player, targetPage);
            }
            return;
        }

        // Session item click (slots 0-9, 18-27 etc — any slot with a BOOK)
        if (slot >= 0 && slot < 45 && item != null && item.getType() == Material.BOOK) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                // Find the session ID from the item lore
                // The session ID is implicit in the position — we need to map it.
                // Re-query to find which session this slot corresponds to.

                // Extract current page from the page indicator (slot 45)
                int currentPage = 1;
                ItemStack pageItem = player.getOpenInventory().getItem(45);
                if (pageItem != null && pageItem.hasItemMeta()) {
                    ItemMeta pageMeta = pageItem.getItemMeta();
                    if (pageMeta != null && pageMeta.hasDisplayName()) {
                        currentPage = extractPageFromIndicator(
                                ChatColor.stripColor(pageMeta.getDisplayName()));
                    }
                }

                // Calculate which session index this slot represents
                int sessionIndex = slot; // Slots 0-9 map directly
                int pageSize = 10;

                // Get sessions for this page
                List<ChatHistoryEntry> sessions =
                        plugin.getDatabaseManager().getChatSessions(player.getUniqueId(), currentPage, pageSize);

                if (sessionIndex >= 0 && sessionIndex < sessions.size()) {
                    String sessionId = sessions.get(sessionIndex).getSessionId();
                    SoundManager.playSound(player, "menu-click");
                    new ChatHistoryGUI(plugin).openSession(player, sessionId, currentPage);
                }
            }
        }
    }

    private void handleSessionViewClick(Player player, int slot) {
        if (slot == ChatHistoryGUI.BACK_SLOT) {
            // Back to session list
            SoundManager.playSound(player, "menu-click");

            // Extract return page from hidden item at slot 53
            int returnPage = 1;
            ItemStack hiddenItem = player.getOpenInventory().getItem(53);
            if (hiddenItem != null && hiddenItem.hasItemMeta()) {
                ItemMeta meta = hiddenItem.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    String displayName = ChatColor.stripColor(meta.getDisplayName());
                    if (displayName != null && displayName.startsWith("ReturnPage: ")) {
                        try {
                            returnPage = Integer.parseInt(displayName.substring("ReturnPage: ".length()).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            new ChatHistoryGUI(plugin).open(player, returnPage);
        }
    }

    /**
     * Extract a page number from the item's lore (e.g., "Page 3" or "第 3 页").
     */
    private int extractPageFromLore(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasLore()) {
                List<String> lore = meta.getLore();
                if (lore != null) {
                    for (String line : lore) {
                        String stripped = ChatColor.stripColor(line);
                        if (stripped == null) continue;
                        // English format: "Page N"
                        if (stripped.startsWith("Page ")) {
                            try {
                                return Integer.parseInt(stripped.substring("Page ".length()).trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        // Chinese format: "第 N 页"
                        if (stripped.startsWith("第 ") && stripped.contains(" 页")) {
                            try {
                                int start = "第 ".length();
                                int end = stripped.indexOf(" 页");
                                return Integer.parseInt(stripped.substring(start, end).trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        }
        return 1;
    }

    private int extractPageFromIndicator(String indicator) {
        if (indicator == null) return 1;
        // Format: "Page 1/3"
        int slashIdx = indicator.indexOf('/');
        int startIdx = -1;
        for (int i = 0; i < indicator.length(); i++) {
            if (Character.isDigit(indicator.charAt(i))) {
                startIdx = i;
                break;
            }
        }
        if (startIdx >= 0) {
            int endIdx = slashIdx > startIdx ? slashIdx : indicator.length();
            try {
                return Integer.parseInt(indicator.substring(startIdx, endIdx).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }
}
