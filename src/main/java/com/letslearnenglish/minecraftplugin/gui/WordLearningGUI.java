package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.AnswerChecker;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.core.word.WordModes;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
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

        // R11-M1：标题难度本地化（F2-13 同根漏面收口）；"review" 特判 ——
        // localizeDifficulty 的 switch 只覆盖三档，review 会原样回落。
        String difficultyLabel = "review".equalsIgnoreCase(difficulty)
                ? plugin.getMessageUtil().getPlayerMessage(player, "word.mode-review")
                : WordModes.localizeDifficulty(plugin.getMessageUtil(), player, difficulty);
        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-learning-gui.title")
                        .replace("{difficulty}", difficultyLabel));

        Inventory inv = Bukkit.createInventory(null, 27, title);
        buildInventory(inv, player);
        player.openInventory(inv);
    }

    public void openWithMessage(Player player, String difficulty, String mode) {
        open(player, difficulty, mode);
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-learning-gui.started-hint")));
    }

    private void buildInventory(Inventory inv, Player player) {
        inv.clear();

        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        WordLearningManager wlManager = plugin.getWordLearningManager();
        ConfigManager cm = plugin.getConfigManager();
        WordLearningManager.QuizState state = wlManager.getQuizState(player.getUniqueId());
        if (state == null) {
            // P0-5：会话不存在时也要铺满空槽 —— 否则空界面里所有格子都是空气，
            // 原版 shift-click / 拖拽都有落点，玩家能把背包物品塞进去且取不回来。
            GUIUtils.fillEmpty(inv);
            return;
        }

        Word currentWord = state.session.getCurrentWord();
        int current = state.session.getCurrentIndex();
        int total = state.session.getTotalWords();
        int combo = state.consecutiveCorrect;
        int score = state.session.getScore();
        double multiplier = wlManager.getComboMultiplier(combo);

        if (currentWord != null) {
            // FEAT-MODE-01：当前单词卡按 mode 渲染。出题展示阶段不泄露答案：
            // choice 正确项不加亮、fill 空不带答案。槽位/材质配置键不动。
            String mode = state.mode;
            if (AnswerChecker.isChoice(mode)) {
                renderChoiceOptions(inv, cm, messages, wlManager, player, currentWord);
            } else if (AnswerChecker.isFillBlank(mode)) {
                renderFillBlankCard(inv, cm, messages, currentWord);
            } else {
                renderSpellingCard(inv, cm, messages, currentWord);
            }
        }

        // Pronunciation button
        String pronLabel = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word.pronunciation-button"));
        String pronLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                MessageUtil.getGuiMsg(messages, "word.pronunciation-button-lore"));
        int pronSlot = cm.getGuiSlot("word-learning", "pronunciation-button", 14);
        Material pronMat = cm.getGuiMaterial("word-learning", "pronunciation-button", Material.NOTE_BLOCK);
        inv.setItem(pronSlot, createItem(pronMat, pronLabel, pronLore));

        String progressLabel = ChatColor.translateAlternateColorCodes('&', "&a" +
                MessageUtil.getGuiMsg(messages, "word-learning-gui.progress")
                        .replace("{current}", String.valueOf(current))
                        .replace("{total}", String.valueOf(total)));
        String scoreLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-learning-gui.score")
                        .replace("{score}", String.valueOf(score)));

        inv.setItem(cm.getGuiSlot("word-learning", "progress-info", 4), createItem(cm.getGuiMaterial("word-learning", "progress-info", Material.CLOCK), progressLabel, scoreLore));

        String comboLabel = getComboLabel(combo, multiplier, messages);
        inv.setItem(cm.getGuiSlot("word-learning", "combo-info", 10), createItem(cm.getGuiMaterial("word-learning", "combo-info", Material.BLAZE_ROD), comboLabel));

        String stopLabel = ChatColor.translateAlternateColorCodes('&', "&c" +
                MessageUtil.getGuiMsg(messages, "word-learning-gui.stop-button"));
        String stopLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                MessageUtil.getGuiMsg(messages, "word-learning-gui.stop-lore"));

        inv.setItem(cm.getGuiSlot("word-learning", "stop-button", 16), createItem(cm.getGuiMaterial("word-learning", "stop-button", Material.BARRIER), stopLabel, stopLore));

        String startLabel = ChatColor.translateAlternateColorCodes('&', "&a" +
                MessageUtil.getGuiMsg(messages, "word-learning-gui.answer-hint"));
        String startLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                MessageUtil.getGuiMsg(messages, "word-learning-gui.answer-hint-lore-1"));

        inv.setItem(cm.getGuiSlot("word-learning", "start-button", 22), createItem(cm.getGuiMaterial("word-learning", "start-button", Material.LIME_DYE), startLabel, startLore));

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                MessageUtil.getGuiMsg(messages, "gui.back-button"));
        String backLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                MessageUtil.getGuiMsg(messages, "gui.back-lore"));
        inv.setItem(cm.getGuiSlot("word-learning", "back-button", 8), createItem(cm.getGuiMaterial("word-learning", "back-button", Material.RED_STAINED_GLASS_PANE), backLabel, backLore));

        // P0-5：空槽统一填灰玻璃（防 shift-click / 拖拽纵深；R12-M3 同款）。
        // 必须放在本方法全部 setItem 之后 —— 这是唯一的内容渲染公共出口（spelling /
        // choice / fill_blank 三个分支都汇到这里），fillEmpty 只兜空槽、不覆盖已渲染物品。
        GUIUtils.fillEmpty(inv);
    }

    /**
     * FEAT-MODE-01：spelling 渲染 — 保持现状（中文 + 音标 + 关闭界面提示）。
     */
    private void renderSpellingCard(Inventory inv, ConfigManager cm,
                                    FileConfiguration messages, Word currentWord) {
        String wordLabel = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-learning-gui.current-word")
                        .replace("{chinese}", currentWord.getChinese()));

        String phoneticStr = currentWord.getPhonetic();
        String phoneticLine = "";
        if (phoneticStr != null && !phoneticStr.isEmpty()) {
            phoneticLine = ChatColor.translateAlternateColorCodes('&', "&7" +
                    MessageUtil.getGuiMsg(messages, "word.phonetic-label")
                    + ": &f" + phoneticStr);
        }

        String viewLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                MessageUtil.getGuiMsg(messages, "word-learning-gui.current-word-lore-1"));

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

    /**
     * FEAT-MODE-01：choice 渲染 — 中文题面卡（原槽位）+ 4 选项 lore 行。
     * 选项快照与判分共用 {@link WordLearningManager#getChoiceOptions} 同一份，
     * 正确项不做任何高亮标记。选项不足 4 个（词库过小）时按实际数量渲染。
     */
    private void renderChoiceOptions(Inventory inv, ConfigManager cm,
                                     FileConfiguration messages, WordLearningManager wlManager,
                                     Player player, Word currentWord) {
        String wordLabel = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-learning-gui.current-word")
                        .replace("{chinese}", currentWord.getChinese()));

        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7" +
                MessageUtil.getGuiMsg(messages, "word-learning-gui.current-word-lore-choice")));

        java.util.List<Word> options = wlManager.getChoiceOptions(player.getUniqueId());
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "word-learning-gui.choice-option")
                                .replace("{letter}", String.valueOf(AnswerChecker.indexToLetter(i)))
                                .replace("{word}", options.get(i).getEnglish())));
            }
        }

        inv.setItem(cm.getGuiSlot("word-learning", "current-word", 13),
                createItem(cm.getGuiMaterial("word-learning", "current-word", Material.BOOK),
                        wordLabel, lore.toArray(new String[0])));
    }

    /**
     * FEAT-MODE-01：fill_blank 渲染 — 挖空例句卡 + 首字母提示 lore。
     * 空位不带答案（缺例句/例句不含目标词时显示纯 "_____"）。
     *
     * <p>MC-3：首字母提示行按 {@code modes.fill_blank.show-first-letter} 短路
     * （展示层实时读配置，reload 即时生效）；false 时只隐藏提示行，题目本身与判定不变。
     */
    private void renderFillBlankCard(Inventory inv, ConfigManager cm,
                                     FileConfiguration messages, Word currentWord) {
        // R-11：加中文题面（与 spelling/choice 卡一致）——物品名用"当前单词: {中文}"，
        // 挖空例句下沉到 lore 第一行，只看 GUI 也知道在考哪个词。槽位/材质不动。
        String wordLabel = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-learning-gui.current-word")
                        .replace("{chinese}", currentWord.getChinese()));

        String sentenceLine = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-learning-gui.fill-blank-sentence")
                        .replace("{sentence}", AnswerChecker.blankSentence(currentWord)));

        String viewLore = ChatColor.translateAlternateColorCodes('&', "&7" +
                MessageUtil.getGuiMsg(messages, "word-learning-gui.current-word-lore-1"));

        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add(sentenceLine);
        if (WordModes.showFirstLetter(cm)) {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "word-learning-gui.fill-blank-hint")
                            .replace("{letter}", AnswerChecker.firstLetterHint(currentWord))));
        }
        lore.add(viewLore);

        inv.setItem(cm.getGuiSlot("word-learning", "current-word", 13),
                createItem(cm.getGuiMaterial("word-learning", "current-word", Material.BOOK),
                        wordLabel, lore.toArray(new String[0])));
    }

    private String getComboLabel(int combo, double multiplier, FileConfiguration messages) {
        if (combo <= 0) {
            return ChatColor.translateAlternateColorCodes('&', "&7" +
                    MessageUtil.getGuiMsg(messages, "word-learning-gui.combo-none"));
        }
        String comboText = MessageUtil.getGuiMsg(messages, "word-learning-gui.combo-active")
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