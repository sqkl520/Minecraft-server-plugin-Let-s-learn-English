package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.data.DifficultWord;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.WrongBookGUI;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Listener for the Wrong Book GUI.
 *
 * Handles:
 * - Left click: Remove word from wrong book
 * - Shift+click: Practice word (starts mini quiz via chat)
 * - Navigation (prev/next page, back to menu)
 */
public class WrongBookListener implements Listener {

    private static final int PREV_SLOT = 47;
    private static final int BACK_SLOT = 49;
    private static final int NEXT_SLOT = 51;

    private final LetsLearnEnglish plugin;

    public WrongBookListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.contains("Wrong Book") && !title.contains("错题本") && !title.contains("Difficult Words")) {
            return;
        }

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();
        ClickType clickType = event.getClick();

        if (slot == BACK_SLOT) {
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }

        // Navigation
        int currentPage = extractPageFromTitle(title);
        if (currentPage < 1) currentPage = 1;

        if (slot == PREV_SLOT && event.getCurrentItem().getType() == org.bukkit.Material.ARROW) {
            SoundManager.playSound(player, "menu-click");
            new WrongBookGUI(plugin).open(player, currentPage - 1);
            return;
        }
        if (slot == NEXT_SLOT && event.getCurrentItem().getType() == org.bukkit.Material.ARROW) {
            SoundManager.playSound(player, "menu-click");
            new WrongBookGUI(plugin).open(player, currentPage + 1);
            return;
        }

        // Word entries are in rows 1-5 (slots 9-44)
        ItemStack clicked = event.getCurrentItem();
        if (clicked.getType() == org.bukkit.Material.PAPER && slot >= 9 && slot <= 44) {
            String itemName = clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : "";
            // Extract word from display name (which contains color codes)
            String word = extractWordFromDisplayName(itemName);
            if (word == null || word.isEmpty()) return;

            if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                // Practice mode
                handlePractice(player, word, currentPage);
            } else {
                // Remove from wrong book
                handleRemove(player, word, currentPage);
            }
        }
    }

    private void handleRemove(Player player, String word, int currentPage) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        plugin.getPlayerDataStore().removeDifficultWord(player.getUniqueId(), word);
        SoundManager.playSound(player, "menu-click");

        String msg = ChatColor.translateAlternateColorCodes('&',
                messages.getString("wrong-book.removed",
                        "&a'{word}' has been removed from your Wrong Book!")
                        .replace("{word}", word));
        player.sendMessage(msg);

        // Re-open the GUI to reflect changes
        new WrongBookGUI(plugin).open(player, currentPage);
    }

    private void handlePractice(Player player, String word, int currentPage) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        // Close inventory first
        player.closeInventory();

        // Start practice mode
        plugin.getWordLearningManager().startPracticeWord(player.getUniqueId(), word);

        // Look up the word for Chinese meaning
        com.letslearnenglish.minecraftplugin.core.word.Word wordInfo = plugin.getWordManager().getWord(word);
        String chinese = wordInfo != null ? wordInfo.getChinese() : word;

        String msg = ChatColor.translateAlternateColorCodes('&',
                messages.getString("wrong-book.practice-start",
                        "&6★ Practice Mode: &eType the English word for: &f{chinese}")
                        .replace("{chinese}", chinese));
        player.sendMessage(msg);

        String cancelMsg = ChatColor.translateAlternateColorCodes('&',
                messages.getString("wrong-book.practice-cancel-hint",
                        "&7(Type &ecancel &7to stop)"));
        player.sendMessage(cancelMsg);
    }

    /**
     * Extract the page number from the GUI title.
     * Title format: "...Wrong Book... | Page N"
     */
    private int extractPageFromTitle(String title) {
        int idx = title.lastIndexOf("Page ");
        if (idx >= 0) {
            try {
                return Integer.parseInt(title.substring(idx + 5).trim());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return 1;
    }

    /**
     * Extract the word from a colored display name (e.g., "§capple" -> "apple").
     */
    private String extractWordFromDisplayName(String displayName) {
        // Strip color codes
        String stripped = ChatColor.stripColor(displayName);
        if (stripped == null || stripped.trim().isEmpty()) return null;
        return stripped.trim();
    }
}
