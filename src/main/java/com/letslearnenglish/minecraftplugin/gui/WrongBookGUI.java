package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.data.DifficultWord;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Wrong Book GUI — displays words the player has answered incorrectly 3+ times.
 *
 * 54-slot layout (6 rows):
 *   Top row (0-8): header decoration
 *   Rows 1-5 (9-44): word entries (up to 45 per page)
 *   Bottom row (45-53): navigation
 *     Slot 47: Previous page
 *     Slot 49: Back to main menu
 *     Slot 51: Next page
 */
public class WrongBookGUI {

    private static final int GUI_SIZE = 54;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int PREV_SLOT = 47;
    private static final int BACK_SLOT = 49;
    private static final int NEXT_SLOT = 51;

    private final LetsLearnEnglish plugin;

    public WrongBookGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, int page) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("wrong-book.title", "&c&lWrong Book / Difficult Words"));

        // Embed page number in title for listener extraction
        title = title + " | Page " + page;

        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        Map<String, DifficultWord> difficultWords = plugin.getPlayerDataStore()
                .getDifficultWordsData(player.getUniqueId());
        List<Map.Entry<String, DifficultWord>> wordList = new ArrayList<>(difficultWords.entrySet());

        // Sort by wrong count descending
        wordList.sort((a, b) -> Integer.compare(b.getValue().getWrongCount(), a.getValue().getWrongCount()));

        int totalPages = Math.max(1, (int) Math.ceil((double) wordList.size() / ITEMS_PER_PAGE));
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        int startIndex = (page - 1) * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, wordList.size());

        // Fill top decoration row with glass
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, createGlassPane(Material.RED_STAINED_GLASS_PANE));
        }

        if (wordList.isEmpty()) {
            // Empty state
            ItemStack book = new ItemStack(Material.BOOK);
            ItemMeta bookMeta = book.getItemMeta();
            if (bookMeta != null) {
                bookMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("wrong-book.empty", "&7No difficult words!")));
                String emptyLore = messages.getString("wrong-book.empty-lore",
                        "&7Keep learning — words you miss 3 times will appear here");
                bookMeta.setLore(Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&', emptyLore)));
                book.setItemMeta(bookMeta);
            }
            inv.setItem(22, book);
        } else {
            // Populate word entries
            int slot = 0;
            for (int i = startIndex; i < endIndex; i++) {
                Map.Entry<String, DifficultWord> entry = wordList.get(i);
                String wordKey = entry.getKey();
                DifficultWord dw = entry.getValue();

                // Look up full word info from word bank
                Word wordInfo = plugin.getWordManager().getWord(wordKey);
                String chinese = wordInfo != null ? wordInfo.getChinese() : "?";

                // Skip the first row (decorative), so slots are 9-53 (rows 1-5)
                // Map linear index to inventory slot: skip row 0 (slots 0-8)
                int row = slot / 9;
                int col = slot % 9;
                slot++;
                int invSlot = (row + 1) * 9 + col;

                if (invSlot >= GUI_SIZE) break;

                Material mat = Material.PAPER;
                String itemName = ChatColor.translateAlternateColorCodes('&',
                        messages.getString("wrong-book.word-name", "&c{word}")
                                .replace("{word}", wordKey));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("wrong-book.word-chinese", "&7Chinese: &f{chinese}")
                                .replace("{chinese}", chinese)));
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("wrong-book.word-count", "&7Wrong count: &c{count}")
                                .replace("{count}", String.valueOf(dw.getWrongCount()))));
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("wrong-book.word-date", "&7Added: &f{date}")
                                .replace("{date}", sdf.format(new Date(dw.getAddedAt())))));
                lore.add("");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("wrong-book.click-remove", "&cClick to remove from book")));
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("wrong-book.shift-practice", "&eShift+Click to practice")));

                inv.setItem(invSlot, createItem(mat, itemName, lore));
            }
        }

        // Navigation buttons
        // Previous page
        if (page > 1) {
            inv.setItem(PREV_SLOT, createItem(Material.ARROW,
                    ChatColor.translateAlternateColorCodes('&',
                            messages.getString("wrong-book.prev-page", "&e← Previous Page")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            messages.getString("wrong-book.prev-page-lore", "&7Page {page}")
                                    .replace("{page}", String.valueOf(page - 1))))));
        }

        // Page indicator
        ItemStack pageIndicator = new ItemStack(Material.PAPER);
        ItemMeta piMeta = pageIndicator.getItemMeta();
        if (piMeta != null) {
            piMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("wrong-book.page-indicator", "&7Page {current}/{total}")
                            .replace("{current}", String.valueOf(page))
                            .replace("{total}", String.valueOf(totalPages))));
            pageIndicator.setItemMeta(piMeta);
        }
        inv.setItem(BACK_SLOT - 1, pageIndicator); // slot 48

        // Back button
        inv.setItem(BACK_SLOT, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&',
                        messages.getString("gui.back-button", "&cBack to Menu")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        messages.getString("gui.back-lore", "&7Return to the main menu")))));

        // Next page
        if (page < totalPages) {
            inv.setItem(NEXT_SLOT, createItem(Material.ARROW,
                    ChatColor.translateAlternateColorCodes('&',
                            messages.getString("wrong-book.next-page", "&eNext Page →")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            messages.getString("wrong-book.next-page-lore", "&7Page {page}")
                                    .replace("{page}", String.valueOf(page + 1))))));
        }

        // Bottom decoration
        for (int i = 45; i < 54; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, createGlassPane(Material.GRAY_STAINED_GLASS_PANE));
            }
        }

        player.openInventory(inv);
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createGlassPane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }
}
