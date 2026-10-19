package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.WrongBookGUI;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/**
 * Listener for the Wrong Book GUI.
 *
 * Handles:
 * - Left click: Remove word from wrong book
 * - Shift+click: Practice word (starts mini quiz via chat)
 * - Navigation (prev/next page, back to menu)
 *
 * 槽位一律引用 {@link WrongBookGUI} 的常量，不在本类重复魔法数字。
 */
public class WrongBookListener implements Listener {

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

        // R-01 补漏：只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();
        ClickType clickType = event.getClick();

        if (slot == WrongBookGUI.BACK_SLOT) {
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }

        // 模块门（点击侧，旅程验收收口）：word-system 关闭时错题本整体拒绝——渲染侧
        // 主菜单按钮已隐藏、GUI open 有门，本处覆盖"关闭前已打开的僵尸界面"点击
        //（含删除/翻页/练习入口——练习会消费聊天输入，必须一并拒绝，同 duel 分支模式；
        //  返回键在上方已放行，玩家总能退出残留界面）。
        if (!plugin.getConfigManager().isWordSystemEnabled()) {
            SoundManager.playSound(player, "menu-click");
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "module.disabled"));
            return;
        }

        // Navigation
        int currentPage = extractPageFromTitle(title);
        if (currentPage < 1) currentPage = 1;

        if (slot == WrongBookGUI.PREV_SLOT && event.getCurrentItem().getType() == Material.ARROW) {
            SoundManager.playSound(player, "menu-click");
            new WrongBookGUI(plugin).open(player, currentPage - 1);
            return;
        }
        if (slot == WrongBookGUI.NEXT_SLOT && event.getCurrentItem().getType() == Material.ARROW) {
            SoundManager.playSound(player, "menu-click");
            new WrongBookGUI(plugin).open(player, currentPage + 1);
            return;
        }

        // Word entries：槽位必须在 WrongBookGUI.WORD_SLOTS 内（与 GUI 共用同一份槽位真相，
        // 不要再手写 9-44 这种区间，否则会再次漂移出「点了没反应」的单词）
        ItemStack clicked = event.getCurrentItem();
        if (clicked.getType() == Material.PAPER
                && Arrays.binarySearch(WrongBookGUI.WORD_SLOTS, slot) >= 0) {
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

    /**
     * T0-01：拖拽 guard（抄 MiscGUIListener / MainMenuListener 的上半区模式）。
     *
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 错题本的单词条目（PAPER）/导航箭头可被拖进玩家背包。
     * 标题判定与 {@link #onInventoryClick} 入口一致；只在拖拽触及界面
     * 上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title == null) return;

        if (!title.contains("Wrong Book") && !title.contains("错题本") && !title.contains("Difficult Words")) {
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

    private void handleRemove(Player player, String word, int currentPage) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        plugin.getPlayerDataStore().removeDifficultWord(player.getUniqueId(), word);
        SoundManager.playSound(player, "menu-click");

        String msg = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "wrong-book.removed")
                        .replace("{word}", word));
        player.sendMessage(msg);

        // Re-open the GUI to reflect changes
        new WrongBookGUI(plugin).open(player, currentPage);
    }

    private void handlePractice(Player player, String word, int currentPage) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        // C1-N1 收口（R2-M4 同族，存量守卫缺口）：决斗中拒进错题练习 —— 决斗分支
        // 优先吞全部聊天（ChatAnswerListener），练习输入永不到达、练习态永挂。
        // 守卫顺序照既有先例：决斗优先、其次对话、其次答题。
        var duelManager = plugin.getDuelManager();
        if (duelManager != null && duelManager.isInDuel(player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "duel.finish-duel-first"));
            return;   // 不关界面、不进入练习
        }
        // C1-N2 收口（R2-M4 同族）：AI 对话进行中拒进错题练习 —— ChatScenarioListener
        // 以 HIGH 优先级消费聊天，练习输入被 AI 侧拦走（对话静默饥饿，可恢复无惩罚）。
        // 守卫顺序照编排裁决：决斗 > 对话 > 答题（答题判紧随下方 R1-M2）。
        if (plugin.getAIChatManager().hasActiveConversation(player)) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "dialogue.chat-active"));
            return;   // 不关界面、不进入练习
        }

        // R1-M2：答题与错题练习互斥 —— 答题中拒进练习（practice 输入分支在
        // ChatAnswerListener 里先于 quiz 分支消费聊天，两态并存会把答题输入吞掉）。
        // 守卫放 listener 入口：startPracticeWord 只收 UUID，无法在 manager 内发消息；
        // 本方法是全仓唯一调用方。复用 word.session-in-progress 键。
        var wl = plugin.getWordLearningManager();
        if (wl.hasActiveQuiz(player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "word.session-in-progress"));
            return;   // 不关界面、不进入练习
        }

        // Close inventory first
        player.closeInventory();

        // Start practice mode
        plugin.getWordLearningManager().startPracticeWord(player.getUniqueId(), word);
        // P2-24 音效盲点收口（3A-5）：有效动作补点击音，与同页左键删除
        // （handleRemove 的 menu-click）对齐 —— 此前 Shift+点练习全程静默。
        SoundManager.playSound(player, "menu-click");

        // Look up the word for Chinese meaning
        com.letslearnenglish.minecraftplugin.core.word.Word wordInfo = plugin.getWordManager().getWord(word);
        String chinese = wordInfo != null ? wordInfo.getChinese() : word;

        String msg = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "wrong-book.practice-start")
                        .replace("{chinese}", chinese));
        player.sendMessage(msg);

        String cancelMsg = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "wrong-book.practice-cancel-hint"));
        player.sendMessage(cancelMsg);
    }

    /**
     * Extract the page number from the GUI title.
     * Title format varies by language:
     *   EN: "...Wrong Book | Page N"
     *   ZH: "...错题本 / Wrong Book | 第 N 页"
     */
    private int extractPageFromTitle(String title) {
        // Try English format first
        int idx = title.lastIndexOf("Page ");
        if (idx >= 0) {
            try {
                String afterPage = title.substring(idx + 5).trim();
                // If there's a trailing char (e.g., "页"), strip it
                if (!afterPage.isEmpty() && !Character.isDigit(afterPage.charAt(afterPage.length() - 1))) {
                    afterPage = afterPage.substring(0, afterPage.length() - 1).trim();
                }
                return Integer.parseInt(afterPage);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        // Try Chinese format: "第 N 页"
        idx = title.indexOf("第 ");
        if (idx >= 0) {
            int endIdx = title.indexOf(" 页", idx);
            if (endIdx > idx) {
                try {
                    return Integer.parseInt(title.substring(idx + 2, endIdx).trim());
                } catch (NumberFormatException e) {
                    // ignore
                }
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
