package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class WordLearningGUI {

    private final LetsLearnEnglish plugin;

    public WordLearningGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, String difficulty, String mode) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.title", "Word Learning - {difficulty}")
                        .replace("{difficulty}", difficulty));

        Inventory inv = Bukkit.createInventory(null, 27, title);
        buildInventory(inv, player);
        player.openInventory(inv);
    }

    public void openWithMessage(Player player, String difficulty, String mode) {
        open(player, difficulty, mode);
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.started-hint",
                        "&7Close the GUI and type the English word in chat.")));
    }

    private void buildInventory(Inventory inv, Player player) {
        inv.clear();

        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        WordLearningManager wlManager = plugin.getWordLearningManager();
        ConfigManager cm = plugin.getConfigManager();
        WordLearningManager.QuizState state = wlManager.getQuizState(player.getUniqueId());
        if (state == null) return;

        Word currentWord = state.session.getCurrentWord();
        int current = state.session.getCurrentIndex();
        int total = state.session.getTotalWords();
        int combo = state.consecutiveCorrect;
        int score = state.session.getScore();
        double multiplier = wlManager.getComboMultiplier(combo);

        if (currentWord != null) {
            String wordLabel = ChatColor.translateAlternateColorCodes('&',
                    messages.getString("word-learning-gui.current-word",
                            "&eCurrent Word: &f{chinese}")
                            .replace("{chinese}", currentWord.getChinese()));

            String phoneticStr = currentWord.getPhonetic();
            String phoneticLine = "";
            if (phoneticStr != null && !phoneticStr.isEmpty()) {
                phoneticLine = ChatColor.translateAlternateColorCodes('&', "&7" +
                        messages.getString("word.phonetic-label", "Phonetic")
                        + ": &f" + phoneticStr);
            }

            String viewLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                    messages.getString("word-learning-gui.current-word-lore-1",
                            "Close the GUI and type the English word"));

            String[] loreLines;
            if (!phoneticLine.isEmpty()) {
                loreLines = new String[] { phoneticLine, viewLore };
            } else {
                loreLines = new String[] { viewLore };
            }

            inv.setItem(cm.getGuiSlot("word-learning", "current-word", 13),
                    createItem(cm.getGuiMaterial("word-learning", "current-word", Material.BOOK),
                            wordLabel, loreLines));
        }

        // Pronunciation button
        String pronLabel = ChatColor.translateAlternateColorCodes('&',
                messages.getString("word.pronunciation-button", "&dPronounce"));
        String pronLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                messages.getString("word.pronunciation-button-lore",
                        "Click to play pronunciation cue"));
        int pronSlot = cm.getGuiSlot("word-learning", "pronunciation-button", 14);
        Material pronMat = cm.getGuiMaterial("word-learning", "pronunciation-button", Material.NOTE_BLOCK);
        inv.setItem(pronSlot, createItem(pronMat, pronLabel, pronLore));

        String progressLabel = ChatColor.translateAlternateColorCodes('&', "&a" +
                messages.getString("word-learning-gui.progress",
                        "Progress: {current}/{total}")
                        .replace("{current}", String.valueOf(current))
                        .replace("{total}", String.valueOf(total)));
        String scoreLore = ChatColor.translateAlternateColorCodes('&',
                "&7Score: &e" + score);

        inv.setItem(cm.getGuiSlot("word-learning", "progress-info", 4), createItem(cm.getGuiMaterial("word-learning", "progress-info", Material.CLOCK), progressLabel, scoreLore));

        String comboLabel = getComboLabel(combo, multiplier, messages);
        inv.setItem(cm.getGuiSlot("word-learning", "combo-info", 10), createItem(cm.getGuiMaterial("word-learning", "combo-info", Material.BLAZE_ROD), comboLabel));

        String stopLabel = ChatColor.translateAlternateColorCodes('&', "&c" +
                messages.getString("word-learning-gui.stop-button", "Stop Session"));
        String stopLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                messages.getString("word-learning-gui.stop-lore", "End the current learning session"));

        inv.setItem(cm.getGuiSlot("word-learning", "stop-button", 16), createItem(cm.getGuiMaterial("word-learning", "stop-button", Material.BARRIER), stopLabel, stopLore));

        String startLabel = ChatColor.translateAlternateColorCodes('&', "&a" +
                messages.getString("word-learning-gui.answer-hint", "Start Answering"));
        String startLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                messages.getString("word-learning-gui.answer-hint-lore-1",
                        "Close the GUI and start answering"));

        inv.setItem(cm.getGuiSlot("word-learning", "start-button", 22), createItem(cm.getGuiMaterial("word-learning", "start-button", Material.LIME_DYE), startLabel, startLore));

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                messages.getString("gui.back-button", "Back to Menu"));
        String backLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                messages.getString("gui.back-lore",
                        "Return to the main menu"));
        inv.setItem(cm.getGuiSlot("word-learning", "back-button", 8), createItem(cm.getGuiMaterial("word-learning", "back-button", Material.RED_STAINED_GLASS_PANE), backLabel, backLore));
    }

    private String getComboLabel(int combo, double multiplier, FileConfiguration messages) {
        if (combo <= 0) {
            return ChatColor.translateAlternateColorCodes('&', "&7" +
                    messages.getString("word-learning-gui.combo-none", "No streak"));
        }
        String comboText = messages.getString("word-learning-gui.combo-active",
                "Streak: {count} ({multiplier}x)")
                .replace("{count}", String.valueOf(combo))
                .replace("{multiplier}", String.valueOf(multiplier));
        return ChatColor.translateAlternateColorCodes('&', "&6&l" + comboText);
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}