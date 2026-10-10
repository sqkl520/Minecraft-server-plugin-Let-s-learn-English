package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Listener for Word Learning GUI interactions and inventory close events.
 */
public class WordLearningListener implements Listener {

    private final LetsLearnEnglish plugin;

    public WordLearningListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();

        if (!title.contains("Word Learning") && !title.contains("单词学习")) {
            return;
        }

        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        WordLearningManager wlManager = plugin.getWordLearningManager();

        if (!wlManager.hasActiveQuiz(player.getUniqueId())) {
            return;
        }

        if (wlManager.isAnswering(player.getUniqueId())) {
            return;
        }

        startAnswerForCurrentWord(player, wlManager);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.contains("Word Learning") && !title.contains("单词学习")) {
            return;
        }

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        handleWordLearningGUI(player, event.getSlot());
    }

    private void startAnswerForCurrentWord(Player player, WordLearningManager wlManager) {
        Word currentWord = wlManager.getCurrentWord(player.getUniqueId());
        if (currentWord == null) return;

        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        int timeout = wlManager.getAnswerTimeout();

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.answer-prompt")
                        .replace("{chinese}", currentWord.getChinese())
                        .replace("{seconds}", String.valueOf(timeout))));

        wlManager.startAnswerTimer(player);
    }

    private void handleWordLearningGUI(Player player, int slot) {
        WordLearningManager wlManager = plugin.getWordLearningManager();
        int pronSlot = plugin.getConfigManager().getGuiSlot("word-learning", "pronunciation-button", 14);
        // 槽位一律读 gui-layout 配置，避免管理员改配置后「按钮渲染在 A、监听器判定 B」
        int backSlot = plugin.getConfigManager().getGuiSlot("word-learning", "back-button", 8);
        int stopSlot = plugin.getConfigManager().getGuiSlot("word-learning", "stop-button", 16);
        int startSlot = plugin.getConfigManager().getGuiSlot("word-learning", "start-button", 22);

        if (slot == backSlot) {
            if (wlManager.hasActiveQuiz(player.getUniqueId())) {
                SoundManager.playSound(player, "incorrect");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word-learning-gui.cannot-return"));
            } else {
                SoundManager.playSound(player, "menu-click");
                new MainMenu(plugin).open(player);
            }
        } else if (slot == pronSlot) {
            handlePronunciationButton(player, wlManager);
        } else if (slot == stopSlot) {
            SoundManager.playSound(player, "menu-click");
            handleStopSession(player, wlManager);
        } else if (slot == startSlot) {
            SoundManager.playSound(player, "menu-click");
            startAnswerForCurrentWord(player, wlManager);
            player.closeInventory();
        }
    }

    private void handlePronunciationButton(Player player, WordLearningManager wlManager) {
        var ttsManager = plugin.getTtsManager();
        if (ttsManager == null || !ttsManager.isEnabled()) {
            SoundManager.playSound(player, "incorrect");
            String lang = plugin.getPlayerLanguage(player);
            var messages = plugin.getConfigManager().getMessageConfig(lang);
            String msg = messages.getString("word.pronunciation-unavailable");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return;
        }

        SoundManager.playSound(player, "menu-click");
        ttsManager.playPronunciationCue(player);

        // Show phonetic in chat as feedback
        // H1：发音反馈不再展示英文原词明文（防泄题），只保留音标
        var currentWord = wlManager.getCurrentWord(player.getUniqueId());
        if (currentWord != null) {
            String phonetic = currentWord.getPhonetic();
            if (phonetic != null && !phonetic.isEmpty()) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word.phonetic-feedback", "phonetic", phonetic));
            }
        }

        // Async download audio for future cache (best-effort, silent on failure)
        if (ttsManager.hasApiKey() && currentWord != null) {
            ttsManager.generateAudio(currentWord.getEnglish());
        }
    }

    private void handleStopSession(Player player, WordLearningManager wlManager) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        wlManager.stopQuiz(player);
        player.closeInventory();

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                messages.getString("word-learning-gui.session-ended")));
    }
}
