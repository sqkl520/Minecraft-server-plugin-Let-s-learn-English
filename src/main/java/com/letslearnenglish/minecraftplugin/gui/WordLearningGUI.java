package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.word.AnswerChecker;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.core.word.WordModes;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
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

    /** 菜单固定 27 格（尺寸由本类决定，不读配置；改动需同步 refresh 的尺寸判定）。 */
    private static final int MENU_SIZE = 27;

    /**
     * choice 模式的可点击选项槽位（固定值，<b>不参与 gui-layout 配置</b>，本轮不引入新配置键）。
     *
     * <p>默认布局占用：back 8 / progress 4 / combo 10 / current-word 13 / pron 14 /
     * stop 16 / start 22。首选 18/19/20/21 —— 第三行（18..26）里紧挨着"开始答题"(22)
     * 左侧的连续四格，视觉上是一排整齐的选项，与全部功能槽零冲突。后面的
     * 23/24/25/26/9/11/12/15 只是管理员把功能槽改到首选位时的兜底候选；
     * {@link #resolveChoiceOptionSlots(ConfigManager)} 会按当前配置动态剔除被功能槽
     * 占用的格子。点击侧不依赖槽位号（认物品上的 PDC 下标），所以"画在哪"与
     * "点什么"不会因为布局改动而错位。
     */
    private static final int[] PREFERRED_CHOICE_SLOTS = {18, 19, 20, 21, 23, 24, 25, 26, 9, 11, 12, 15};

    /** 需要避让的功能槽配置键（与 buildInventory / WordLearningListener 的读取保持一致）。 */
    private static final String[] RESERVED_SLOT_KEYS = {
            "back-button", "progress-info", "combo-info", "current-word",
            "stop-button", "pronunciation-button", "start-button"
    };

    /** 与 {@link #RESERVED_SLOT_KEYS} 一一对应的默认槽位（配置缺键时按渲染侧默认值避让）。 */
    private static final int[] RESERVED_SLOT_DEFAULTS = {8, 4, 10, 13, 16, 14, 22};

    /** choice 选项物品材质（不新增配置键，固定用纸）。 */
    private static final Material CHOICE_OPTION_MATERIAL = Material.PAPER;

    /**
     * 选项物品上的 PDC 标记键名：值 = 选项下标（0=A）。
     *
     * <p>点击侧凭物品自身标记反解下标，而不是凭槽位号 —— 管理员改 gui-layout 或
     * {@code /lea reload} 恰好落在"已渲染"与"已点击"之间时，槽位含义可能漂移，
     * 物品标记不会。标记对玩家不可见。键的构造照 ChatHistoryGUI / DuelChallengeGUI
     * 的既有写法（{@code new NamespacedKey(plugin, name)}），避免字符串命名空间漂移。
     */
    private static final String CHOICE_INDEX_KEY_NAME = "choice_option_index";

    /**
     * 选项物品上的"题号"PDC 标记键名：值 = 渲染时的 session 题号。
     *
     * <p>防跨题误答：界面刷新是"原地重画"，客户端可能把刷新瞬间的第二次点击连同
     * 旧画面一起发上来 —— 那次点击携带的是<b>上一题</b>的选项下标。点击侧比对
     * PDC 题号与当前会话题号，不等就丢弃，保证一次点击只作用于它被画出来时所对应
     * 的那一题（不依赖时间窗口，比单纯节流更硬）。
     */
    private static final String CHOICE_QUESTION_KEY_NAME = "choice_option_question";

    /** 选项下标 PDC 键（渲染与点击两侧共用同一份构造）。 */
    public static org.bukkit.NamespacedKey choiceIndexKey(LetsLearnEnglish plugin) {
        return new org.bukkit.NamespacedKey(plugin, CHOICE_INDEX_KEY_NAME);
    }

    /** 选项所属题号 PDC 键（渲染与点击两侧共用同一份构造）。 */
    public static org.bukkit.NamespacedKey choiceQuestionKey(LetsLearnEnglish plugin) {
        return new org.bukkit.NamespacedKey(plugin, CHOICE_QUESTION_KEY_NAME);
    }

    private final LetsLearnEnglish plugin;

    public WordLearningGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * choice 模式四个选项的槽位（最多 4 个，词库过小/功能槽占位过多时可能少于 4 个）。
     *
     * <p>渲染侧的唯一来源：GUI 按返回顺序把 A/B/C/D 放进这些槽。被 gui-layout
     * 功能槽占用的候选位直接跳过，因此管理员改布局不会出现"按钮盖住选项"。
     * 点击侧不读槽位 —— 它认的是物品上写入的选项下标，两侧天然不会错位。
     *
     * @param cm 配置管理器
     * @return 可用选项槽（长度 0..4，按 A/B/C/D 顺序）
     */
    public static int[] resolveChoiceOptionSlots(ConfigManager cm) {
        boolean[] reserved = new boolean[MENU_SIZE];
        for (int i = 0; i < RESERVED_SLOT_KEYS.length; i++) {
            int slot = cm.getGuiSlot("word-learning", RESERVED_SLOT_KEYS[i], RESERVED_SLOT_DEFAULTS[i]);
            if (slot >= 0 && slot < MENU_SIZE) {
                reserved[slot] = true;
            }
        }
        int[] slots = new int[AnswerChecker.CHOICE_OPTION_MAX];
        int count = 0;
        for (int candidate : PREFERRED_CHOICE_SLOTS) {
            if (count >= slots.length) {
                break;
            }
            if (candidate >= 0 && candidate < MENU_SIZE && !reserved[candidate]) {
                slots[count++] = candidate;
                reserved[candidate] = true;
            }
        }
        return Arrays.copyOf(slots, count);
    }

    private String buildTitle(Player player, String difficulty) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        // R11-M1：标题难度本地化（F2-13 同根漏面收口）；"review" 特判 ——
        // localizeDifficulty 的 switch 只覆盖三档，review 会原样回落。
        String difficultyLabel = "review".equalsIgnoreCase(difficulty)
                ? plugin.getMessageUtil().getPlayerMessage(player, "word.mode-review")
                : WordModes.localizeDifficulty(plugin.getMessageUtil(), player, difficulty);
        return ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-learning-gui.title")
                        .replace("{difficulty}", difficultyLabel));
    }

    public void open(Player player, String difficulty, String mode) {
        String title = buildTitle(player, difficulty);

        // R5-1：挂结构身份 holder（无 payload —— 难度只活在标题里，无人读取）。
        // R5-13 起它是身份判定的唯一依据：refresh() 的闸门与 WordLearningListener 均只认
        // holder，标题层已整体删除。open 不挂 holder → 界面永不刷新。
        Inventory inv = Bukkit.createInventory(PluginGuiHolder.of(GuiViewKey.WORD_LEARNING),
                MENU_SIZE, title);
        buildInventory(inv, player);
        player.openInventory(inv);
    }

    /**
     * 就地重画"当前已打开"的单词学习界面（choice 点选作答后连续出下一题用，不关界面）。
     *
     * <p>闸门：玩家在线 / 会话仍在 / 顶栏尺寸是本菜单的 27 格 / <b>身份</b>是单词学习界面
     * （{@link GuiIdentity#resolve(org.bukkit.inventory.InventoryView)}，纯 holder 判定）——
     * 任一不满足就什么都不做，避免把玩家正开着的别的界面（主菜单、结算页等）当成单词学习
     * 界面重画。重画不触发 {@code InventoryCloseEvent}，所以不会顺带启动倒计时
     * （倒计时仍只由"关界面 / 点开始"按既有设计触发）。
     *
     * <p><b>R5-13：纯 holder，无标题兜底</b>。标题兜底原本只为「旧界面 / holder 未挂」而设，
     * 而 holder 跨 ClassLoader 失效这条唯一裂缝已由 {@code LetsLearnEnglish.onDisable()}
     * 调用 {@link GuiIdentity#closeIfPluginGui(org.bukkit.entity.Player)} 堵上：换 ClassLoader
     * 必然先 disable，界面在那之前已关闭，不存在陈旧界面活到新 ClassLoader。因此这里不再
     * 需要标题保底。尺寸检查保留 —— 那是防御性检查，不是身份判定。
     *
     * <p><b>R5-1 迁移红线</b>：本闸门与 {@link #open} 里的 holder 挂载必须<b>同批</b>存在。
     * {@code open} 不挂 holder → 界面永不刷新，玩家点选项后被跨题校验丢弃，表现为
     * "选择题点了没反应"。
     *
     * @param player 目标玩家（必须在主线程调用）
     */
    public void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        WordLearningManager.QuizState state =
                plugin.getWordLearningManager().getQuizState(player.getUniqueId());
        if (state == null) {
            return;
        }
        org.bukkit.inventory.InventoryView view = player.getOpenInventory();
        Inventory top = view.getTopInventory();
        if (top == null || top.getSize() != MENU_SIZE) {
            return;
        }
        // 身份闸（R5-13）：纯 holder。只重画本菜单，绝不碰玩家开着的别的 27 格界面；
        // 标题层已删除 —— onDisable 已堵住 ClassLoader 换代裂缝，不需要标题保底。
        GuiViewKey key = GuiIdentity.resolve(view);
        if (key != GuiViewKey.WORD_LEARNING) {
            return;
        }
        buildInventory(top, player);
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
                renderChoiceOptions(inv, cm, messages, wlManager, player, currentWord, current);
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
     * FEAT-MODE-01：choice 渲染 — 中文题面卡（原槽位）+ 4 个<b>可点击</b>选项物品。
     * 选项快照与判分共用 {@link WordLearningManager#getChoiceOptions} 同一份，
     * 正确项不做任何高亮标记。选项不足 4 个（词库过小）时按实际数量渲染。
     *
     * <p>R4-手感：选项不再只是槽 13 的 lore 文本 —— 每个选项另占一个独立槽位
     * （{@link #resolveChoiceOptionSlots}，默认 18/19/20/21），物品名沿用
     * {@code word-learning-gui.choice-option}（"A. apple"），并在 PDC 写入下标，
     * 点击由 {@code WordLearningListener} 反解为 "A"/"B"/"C"/"D" 提交。
     * 题面卡上的 lore 选项行保留：词库/布局导致可选槽不足时，lore 仍是完整选项表，
     * 且聊天作答通道（关界面后敲字母）完全不受影响。
     * 正确项与干扰项渲染完全一致，不泄露答案。
     */
    private void renderChoiceOptions(Inventory inv, ConfigManager cm,
                                     FileConfiguration messages, WordLearningManager wlManager,
                                     Player player, Word currentWord, int questionIndex) {
        String wordLabel = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-learning-gui.current-word")
                        .replace("{chinese}", currentWord.getChinese()));

        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7" +
                MessageUtil.getGuiMsg(messages, "word-learning-gui.current-word-lore-choice")));

        java.util.List<Word> options = wlManager.getChoiceOptions(player.getUniqueId());
        if (options == null) {
            options = java.util.Collections.emptyList();
        }
        for (int i = 0; i < options.size(); i++) {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "word-learning-gui.choice-option")
                            .replace("{letter}", String.valueOf(AnswerChecker.indexToLetter(i)))
                            .replace("{word}", options.get(i).getEnglish())));
        }

        inv.setItem(cm.getGuiSlot("word-learning", "current-word", 13),
                createItem(cm.getGuiMaterial("word-learning", "current-word", Material.BOOK),
                        wordLabel, lore.toArray(new String[0])));

        // 可点击选项：槽位与点击侧同源（resolveChoiceOptionSlots），槽数不足时按实际数量渲染。
        // 必须在 fillEmpty 之前 —— buildInventory 末尾统一兜灰玻璃，只填空槽不覆盖已渲染物。
        int[] slots = resolveChoiceOptionSlots(cm);
        int clickable = Math.min(slots.length, options.size());
        for (int i = 0; i < clickable; i++) {
            String name = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "word-learning-gui.choice-option")
                            .replace("{letter}", String.valueOf(AnswerChecker.indexToLetter(i)))
                            .replace("{word}", options.get(i).getEnglish()));
            inv.setItem(slots[i], createChoiceOptionItem(name, i, questionIndex));
        }
    }

    /**
     * 生成一个可点击选项物品：名称 = "A. apple"，PDC 写入选项下标 + 渲染时题号。
     * 无 lore（选项文本已在题面卡 lore 里完整列出一份）。
     */
    private ItemStack createChoiceOptionItem(String name, int index, int questionIndex) {
        ItemStack item = createItem(CHOICE_OPTION_MATERIAL, name);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(choiceIndexKey(plugin),
                    org.bukkit.persistence.PersistentDataType.INTEGER, index);
            meta.getPersistentDataContainer().set(choiceQuestionKey(plugin),
                    org.bukkit.persistence.PersistentDataType.INTEGER, questionIndex);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 读取选项物品上的下标标记（供点击侧使用）。
     *
     * @param plugin 插件实例（PDC 键的命名空间来源）
     * @param item   被点击的物品
     * @return 0..3 = 选项下标；物品为空或没有标记返回 -1
     */
    public static int readChoiceIndex(LetsLearnEnglish plugin, ItemStack item) {
        int value = readPdcInt(plugin, item, choiceIndexKey(plugin));
        if (value < 0 || value >= AnswerChecker.CHOICE_OPTION_MAX) {
            return -1;
        }
        return value;
    }

    /**
     * 读取选项物品被渲染时所属的题号（供点击侧做跨题校验）。
     *
     * @return 题号；无标记/物品为空返回 -1
     */
    public static int readChoiceQuestionIndex(LetsLearnEnglish plugin, ItemStack item) {
        return readPdcInt(plugin, item, choiceQuestionKey(plugin));
    }

    private static int readPdcInt(LetsLearnEnglish plugin, ItemStack item,
                                  org.bukkit.NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return -1;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return -1;
        }
        Integer value = meta.getPersistentDataContainer().get(key,
                org.bukkit.persistence.PersistentDataType.INTEGER);
        return value == null ? -1 : value;
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