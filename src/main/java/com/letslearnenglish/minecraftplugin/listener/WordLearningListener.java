package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.word.AnswerChecker;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.core.word.WordModes;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.WordLearningGUI;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for Word Learning GUI interactions and inventory close events.
 */
public class WordLearningListener implements Listener {

    private final LetsLearnEnglish plugin;
    // M20：发音按钮 per-player 冷却（毫秒时间戳），防连点刷 API
    private final java.util.Map<java.util.UUID, Long> pronounceCooldown = new ConcurrentHashMap<>();

    /**
     * R4-choice：choice 选项的 GUI 作答节流（毫秒时间戳）。
     *
     * <p>防"连点两下"跨题误答：第一次点击提交后界面会就地刷新成下一题，
     * 客户端在刷新前后连发的第二次点击可能落在下一题的选项上（玩家看到的仍是旧画面）。
     * 300ms 内重复的选项点击直接忽略——正常作答（读题→点选）远慢于此值。
     * 判分本身的并发安全仍由 {@code submitAnswer} 的 {@code submitting} CAS 兜底。
     */
    private static final long CHOICE_CLICK_THROTTLE_MS = 300L;
    private final java.util.Map<java.util.UUID, Long> lastChoiceSubmitAt = new ConcurrentHashMap<>();

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
        lastChoiceSubmitAt.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // R5-11：纯 holder 判定。原「/reload 后旧 holder 失效」的顾虑已由
        // onDisable() 关闭全部在线玩家 GUI 消解 —— PlugMan/重启必先 onDisable，
        // 不存在跨 ClassLoader 的陈旧界面（/lea reload 不换 ClassLoader）。
        if (resolveOwnedView(event.getView()) != GuiViewKey.WORD_LEARNING) {
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
        // R5-11：纯 holder 判定（不再回落标题）。
        if (resolveOwnedView(event.getView()) != GuiViewKey.WORD_LEARNING) {
            return;
        }

        // R-01 补漏：只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        //
        // R5-1 红线：上面的身份判定<b>只证明「这个 view 的上半区是单词学习界面」</b>，
        // 不证明「玩家点的是上半区」—— event.getInventory()/getView().getTopInventory()
        // 恒为上半区，玩家点自己背包时 holder 依然命中。因此窗外的 null 与下半区的
        // rawSlot 越界这两道 return 必须留在 cancel 之前，否则「开着 GUI 也能整理背包」
        // 会被静默打碎。顺序：认 view → 判窗外 → 判下半区 → 最后才 cancel。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();

        // P0-5 纵深：下半区 shift-click（MOVE_TO_OTHER_INVENTORY）与双击收集
        //（COLLECT_TO_CURSOR）补 cancel —— 铺满后原版已无落点，此处防未来布局改动回归。
        // COLLECT_TO_CURSOR 由 R-P3-1 红队指出：玩家背包有同材质物品时，在下半区双击会
        // 从整个 view（含上半区展示物）收集同材质物品，可把 GUI 的提示物扫进光标。
        // 其余下半区点击不 cancel（玩家整理背包不受影响，R-01 语义保持）。必须放在下面的
        // rawSlot 越界 return 之前：这两类 action 的 rawSlot 落在下半区，会先被那个 return 吞掉。
        if ((event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR)
                && rawSlot >= topSize) {
            event.setCancelled(true);
            return;
        }

        if (rawSlot < 0 || rawSlot >= topSize) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        handleWordLearningGUI(player, event.getSlot(), clicked,
                // 选项作答只认鼠标左/右键：shift-click（整叠移动）、数字键互换、
                // F 键换手、双击收集都会在选项上产生"非作答意图"的点击，直接不当作答。
                event.getClick() == org.bukkit.event.inventory.ClickType.LEFT
                        || event.getClick() == org.bukkit.event.inventory.ClickType.RIGHT);
    }

    /**
     * T0-01：拖拽 guard（抄 MiscGUIListener / MainMenuListener 的上半区模式）。
     *
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 单词学习 GUI 的展示物品（发音按钮/停止/返回等）可被拖进玩家背包。
     * 身份判定与 {@link #onInventoryClick} 入口一致（R5-11：只认 holder）；
     * 只在拖拽触及界面上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        // R5-11：纯 holder 判定。
        if (resolveOwnedView(event.getView()) != GuiViewKey.WORD_LEARNING) {
            return;
        }

        // 身份判定不蕴含「拖的是上半区」，rawSlot < topSize 的判断必须保留。
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * R5-11：解析「本监听器负责的界面」—— <b>只认 holder</b>。
     *
     * <p>原 R5-7 的「{@code resolve(view, title)} 标题兜底」已删除：跨 ClassLoader
     * 的陈旧 holder 场景由 {@code onDisable()} 统一关闭在线玩家 GUI 消解，
     * 标题不再需要作为救生索。
     *
     * @param view 事件界面，可为 null
     * @return {@link GuiViewKey#WORD_LEARNING} 或 null（不归本类管）
     */
    private GuiViewKey resolveOwnedView(InventoryView view) {
        GuiViewKey key = GuiIdentity.resolve(view);
        return key == GuiViewKey.WORD_LEARNING ? key : null;
    }

    private void startAnswerForCurrentWord(Player player, WordLearningManager wlManager) {
        Word currentWord = wlManager.getCurrentWord(player.getUniqueId());
        if (currentWord == null) return;

        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        // MC-3：{seconds} 展示走会话快照的有效倒计时（choice 被 time-limit 覆盖时
        // 与真实计时器一致，不再展示全局值）。
        int timeout = wlManager.getEffectiveTimeout(player.getUniqueId());
        String mode = wlManager.getMode(player.getUniqueId());

        // FEAT-MODE-01：答题提示按 mode 渲染（判定收敛于 AnswerChecker，这里只做展示）。
        if (AnswerChecker.isChoice(mode)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "word-learning-gui.answer-prompt-choice")
                            .replace("{chinese}", currentWord.getChinese())
                            .replace("{seconds}", String.valueOf(timeout))));
            java.util.List<Word> options = wlManager.getChoiceOptions(player.getUniqueId());
            if (options != null) {
                for (int i = 0; i < options.size(); i++) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "word-learning-gui.choice-option")
                                    .replace("{letter}", String.valueOf(AnswerChecker.indexToLetter(i)))
                                    .replace("{word}", options.get(i).getEnglish())));
                }
            }
        } else if (AnswerChecker.isFillBlank(mode)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "word-learning-gui.answer-prompt-fill-blank")
                            .replace("{chinese}", currentWord.getChinese())
                            .replace("{seconds}", String.valueOf(timeout))));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "word-learning-gui.fill-blank-sentence")
                            .replace("{sentence}", AnswerChecker.blankSentence(currentWord))));
            // MC-3：首字母提示行按 modes.fill_blank.show-first-letter 短路（实时读配置，
            // reload 即时生效）；false 时只隐藏提示行，题目与判定不变。
            if (WordModes.showFirstLetter(plugin.getConfigManager())) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "word-learning-gui.fill-blank-hint")
                                .replace("{letter}", AnswerChecker.firstLetterHint(currentWord))));
            }
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "word-learning-gui.answer-prompt")
                            .replace("{chinese}", currentWord.getChinese())
                            .replace("{seconds}", String.valueOf(timeout))));
        }

        wlManager.startAnswerTimer(player);
    }

    private void handleWordLearningGUI(Player player, int slot, ItemStack clicked,
                                       boolean plainClick) {
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
            // 功能槽行为保持原样（不区分点击类型）；只有下方"选项作答"分支才限定左/右键。
            handlePronunciationButton(player, wlManager);
        } else if (slot == stopSlot) {
            SoundManager.playSound(player, "menu-click");
            handleStopSession(player, wlManager);
        } else if (slot == startSlot) {
            SoundManager.playSound(player, "menu-click");
            startAnswerForCurrentWord(player, wlManager);
            player.closeInventory();
        } else if (plainClick) {
            // R4-choice：功能槽都不匹配 → 再看是不是 choice 选项物品。
            // 用物品上的 PDC 标记判定（而非槽位号）：布局/配置在"已渲染"与"已点击"
            // 之间变化时，物品身份不会漂移；灰玻璃 filler 没有标记，天然不命中。
            handleChoiceOptionClick(player, wlManager, clicked);
        }
    }

    /**
     * R4-choice：点击选项物品 = 提交该选项字母（"A"/"B"/"C"/"D"）。
     *
     * <p>关键语义（与"关界面才启动倒计时"的既有设计共存）：
     * <ul>
     *   <li>界面开着、倒计时尚未启动（{@code isAnswering=false}）时点击也接受 ——
     *       玩家直觉"看得见就能点"，不要求先点开始/关界面。</li>
     *   <li>{@link WordLearningManager#submitAnswer} 内部第一步就 cancelAnswerTimer，
     *       再由 {@code submitting} CAS 串行化 —— 不管倒计时在不在跑都只有一次判分；
     *       计时器即便已经排到下一 tick，也会被 {@code cancelled} 标记拦下，不会二次结算。</li>
     *   <li>提交后走 {@link ChatAnswerListener#presentAnswerResult}（与聊天作答同一出口），
     *       仅收尾不同：就地刷新界面（{@code refresh}，不触发 InventoryCloseEvent），
     *       玩家可以连续点下一题；聊天通道仍完全可用。</li>
     * </ul>
     */
    private void handleChoiceOptionClick(Player player, WordLearningManager wlManager,
                                         ItemStack clicked) {
        int index = WordLearningGUI.readChoiceIndex(plugin, clicked);
        if (index < 0) {
            return;
        }
        WordLearningManager.QuizState state = wlManager.getQuizState(player.getUniqueId());
        if (state == null || !AnswerChecker.isChoice(state.mode) || state.session.isCompleted()) {
            return;
        }
        // 跨题校验：物品被画出来时所属的题号必须等于当前题号。刷新瞬间客户端补发的
        // "旧画面上的第二次点击"在此被丢弃（旧物品的题号已落后），不会误答下一题。
        int drawnQuestion = WordLearningGUI.readChoiceQuestionIndex(plugin, clicked);
        if (drawnQuestion >= 0 && drawnQuestion != state.session.getCurrentIndex()) {
            return;
        }
        // 选项数少于下标（词库过小导致选项被裁掉，理论上物品也不会渲染）→ 忽略
        java.util.List<Word> options = wlManager.getChoiceOptions(player.getUniqueId());
        if (options == null || index >= options.size()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastChoiceSubmitAt.get(player.getUniqueId());
        if (last != null && now - last < CHOICE_CLICK_THROTTLE_MS) {
            return;
        }
        lastChoiceSubmitAt.put(player.getUniqueId(), now);

        Word currentWord = state.session.getCurrentWord();
        String letter = String.valueOf(AnswerChecker.indexToLetter(index));
        String answer = letter;
        // R-56 兼容（AnswerChecker 既有语义：choice 先判"输入=正确词全文"，再判选项字母）：
        // 词库里有单字母词（beginner 的 "a"）。默认大小写不敏感时，字母 "A" 会被当成
        // 单词 "a" 的全文而无论点哪一项都判对。命中这种撞名且点的不是正确项时，
        // 改发"所选选项的单词全文"：它不可能等于正确词（选项已按 equalsIgnoreCase 去重），
        // 也不在 A-D 字母空间内，判分必然为错 —— 与玩家点错一项的语义一致。
        int correctIndex = AnswerChecker.indexOfAnswer(options, currentWord);
        if (currentWord != null && correctIndex >= 0 && index != correctIndex
                && !state.caseSensitive
                && letter.equalsIgnoreCase(currentWord.getEnglish())) {
            answer = options.get(index).getEnglish();
        }

        WordLearningManager.AnswerResult result = wlManager.submitAnswer(player, answer);
        if (result == null) {
            // 与聊天路径同口径：submitAnswer 返回 null = 本题已判过/会话已结束。
            // 若会话已消失（完成/停止），刷新会把界面留在原地，此处不再补画。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "word-learning-gui.answer-expired"));
            return;
        }
        // keepGuiOpen=true：不关界面，就地刷新到下一题（或结算）。
        ChatAnswerListener.presentAnswerResult(plugin, player, result, true);
        if (result.sessionComplete) {
            // 会话已走完：state 已移出 map（buildResult），界面里只剩最后一题的旧物品。
            // 关掉界面避免"死菜单"；下一 tick 再关，不在 InventoryClickEvent 同步栈里
            // 动视图（此时 InventoryCloseEvent 会因 hasActiveQuiz=false 直接返回，
            // 不会误判超时/重启倒计时）。
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.closeInventory();
                }
            });
        }
    }

    private void handlePronunciationButton(Player player, WordLearningManager wlManager) {
        var ttsManager = plugin.getTtsManager();
        if (ttsManager == null || !ttsManager.isEnabled()) {
            SoundManager.playSound(player, "incorrect");
            String lang = plugin.getPlayerLanguage(player);
            var messages = plugin.getConfigManager().getMessageConfig(lang);
            String msg = MessageUtil.getGuiMsg(messages, "word.pronunciation-unavailable");
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
                MessageUtil.getGuiMsg(messages, "word-learning-gui.session-ended")));
    }
}
