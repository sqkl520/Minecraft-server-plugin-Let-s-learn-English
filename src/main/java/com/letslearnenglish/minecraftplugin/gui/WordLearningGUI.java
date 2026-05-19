package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
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
                        "&7Type the English word in chat after closing the GUI.")));
    }

    private void buildInventory(Inventory inv, Player player) {
        inv.clear();

        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        WordLearningManager wlManager = plugin.getWordLearningManager();
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
            String viewLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                    messages.getString("word-learning-gui.current-word-lore-1",
                            "Close the GUI and type the English word"));

            inv.setItem(13, createItem(Material.BOOK, wordLabel, viewLore));
        }

        String progressLabel = ChatColor.translateAlternateColorCodes('&', "&a" +
                messages.getString("word-learning-gui.progress",
                        "Progress: {current}/{total}")
                        .replace("{current}", String.valueOf(current))
                        .replace("{total}", String.valueOf(total)));
        String scoreLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.score-lore",
                        "&7Score: &e{score}")
                        .replace("{score}", String.valueOf(score)));

        inv.setItem(4, createItem(Material.CLOCK, progressLabel, scoreLore));

        String comboLabel = getComboLabel(combo, multiplier, messages);
        inv.setItem(10, createItem(Material.BLAZE_ROD, comboLabel));

        String stopLabel = ChatColor.translateAlternateColorCodes('&', "&c" +
                messages.getString("word-learning-gui.stop-button", "Stop Session"));
        String stopLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                messages.getString("word-learning-gui.stop-lore",
                        "End the current learning session"));

        inv.setItem(16, createItem(Material.BARRIER, stopLabel, stopLore));

        String startLabel = ChatColor.translateAlternateColorCodes('&', "&a" +
                messages.getString("word-learning-gui.answer-hint", "Start Answering"));
        String startLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                messages.getString("word-learning-gui.answer-hint-lore-1",
                        "Close the GUI and start answering"));

        inv.setItem(22, createItem(Material.LIME_DYE, startLabel, startLore));
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