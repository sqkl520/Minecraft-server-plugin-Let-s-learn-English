package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.AnswerChecker;
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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for Word Learning GUI interactions and inventory close events.
 */
public class WordLearningListener implements Listener {

    private final LetsLearnEnglish plugin;
    // M20：发音按钮 per-player 冷却（毫秒时间戳），防连点刷 API
    private final java.util.Map<java.util.UUID, Long> pronounceCooldown = new ConcurrentHashMap<>();

    public WordLearningListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * R-64：发音冷却 map 的 quit 收口 —— 本监听器自清（自注册 quit 事件）。
     *
     * <p>原先 pronounceCooldown 只 put 不删：一次性玩家（退服不再来）的 UUID
     * 条目永久残留。清理挂在本类自己的 quit 处理器上，避免让 PlayerJoinListener
     * 持有本类实例（LetsLearnEnglish.registerListeners 里是匿名 new，无公开引用），
     * 也避免 PlayerDataStore 反向调 listener 的跨层依赖。
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pronounceCooldown.remove(event.getPlayer().getUniqueId());
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

        // R-01 补漏：只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        handleWordLearningGUI(player, event.getSlot());
    }

    /**
     * T0-01：拖拽 guard（抄 MiscGUIListener / MainMenuListener 的上半区模式）。
     *
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 单词学习 GUI 的展示物品（发音按钮/停止/返回等）可被拖进玩家背包。
     * 标题判定与 {@link #onInventoryClick} 入口一致；只在拖拽触及界面
     * 上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title == null) return;

        if (!title.contains("Word Learning") && !title.contains("单词学习")) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void startAnswerForCurrentWord(Player player, WordLearningManager wlManager) {
        Word currentWord = wlManager.getCurrentWord(player.getUniqueId());
        if (currentWord == null) return;

        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        int timeout = wlManager.getAnswerTimeout();
        String mode = wlManager.getMode(player.getUniqueId());

        // FEAT-MODE-01：答题提示按 mode 渲染（判定收敛于 AnswerChecker，这里只做展示）。
        if (AnswerChecker.isChoice(mode)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("word-learning-gui.answer-prompt-choice")
                            .replace("{chinese}", currentWord.getChinese())
                            .replace("{seconds}", String.valueOf(timeout))));
            java.util.List<Word> options = wlManager.getChoiceOptions(player.getUniqueId());
            if (options != null) {
                for (int i = 0; i < options.size(); i++) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            messages.getString("word-learning-gui.choice-option")
                                    .replace("{letter}", String.valueOf(AnswerChecker.indexToLetter(i)))
                                    .replace("{word}", options.get(i).getEnglish())));
                }
            }
        } else if (AnswerChecker.isFillBlank(mode)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("word-learning-gui.answer-prompt-fill-blank")
                            .replace("{chinese}", currentWord.getChinese())
                            .replace("{seconds}", String.valueOf(timeout))));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("word-learning-gui.fill-blank-sentence")
                            .replace("{sentence}", AnswerChecker.blankSentence(currentWord))));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("word-learning-gui.fill-blank-hint")
                            .replace("{letter}", AnswerChecker.firstLetterHint(currentWord))));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    messages.getString("word-learning-gui.answer-prompt")
                            .replace("{chinese}", currentWord.getChinese())
                            .replace("{seconds}", String.valueOf(timeout))));
        }

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

        // M20：冷却内点击只提示，不调 API
        // R-64：下限从 1 放宽到 0 —— 配 0（或负数）表示不启用冷却，
        // 此时完全跳过冷却判定与时间戳写入，管理员可显式关掉该限制。
        int cooldownSeconds = Math.max(0, plugin.getConfigManager().getMainConfig()
                .getInt("tts.pronounce-cooldown-seconds", 5));
        if (cooldownSeconds > 0) {
            long now = System.currentTimeMillis();
            Long lastClick = pronounceCooldown.get(player.getUniqueId());
            if (lastClick != null && now - lastClick < cooldownSeconds * 1000L) {
                long remain = cooldownSeconds - (now - lastClick) / 1000L;
                SoundManager.playSound(player, "incorrect");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "word.pronunciation-cooldown", "seconds", String.valueOf(Math.max(1, remain))));
                return;
            }
            pronounceCooldown.put(player.getUniqueId(), now);
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
