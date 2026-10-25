package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.WrongBookGUI;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder.GuiPayload;
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
import org.bukkit.inventory.InventoryView;
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
        // R5-11：纯 holder 判定。原「/reload 后旧 holder 失效」的顾虑已由 onDisable()
        // 关闭全部在线玩家 GUI 消解（PlugMan/重启必先 onDisable；/lea reload 不换
        // ClassLoader），标题不再是必要兜底。
        if (resolveOwnedView(event.getView()) != GuiViewKey.WRONG_BOOK) {
            return;
        }

        // R-01 补漏：只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        //
        // R5-1 红线：身份判定只证明「上半区是错题本」，不证明「点的是上半区」——
        // getView().getTopInventory() 恒为上半区，点自己背包时 holder 一样命中。
        // 顺序：认 view → 判窗外 → 判下半区 → 最后才 cancel。
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
        // R5-11：页码只读 holder 载荷（GUI 打开时写入，结构性事实）；取不到时落第 1 页。
        // 原「标题正则兜底」（GUIUtils.extractPageFromTitle）已随标题兜底一并删除。
        int currentPage = resolvePage(event);
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
     * 身份判定与 {@link #onInventoryClick} 入口一致（R5-11：只认 holder）；
     * 只在拖拽触及界面上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        // R5-11：同 onInventoryClick，纯 holder 判定。
        if (resolveOwnedView(event.getView()) != GuiViewKey.WRONG_BOOK) {
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
     * R5-11：解析「本监听器负责的界面」—— <b>只认 holder</b>。
     *
     * <p>原 R5-7 的「{@code resolve(view, title)} 标题兜底」已删除；跨 ClassLoader 的
     * 陈旧 holder 场景由 {@code onDisable()} 关闭在线玩家 GUI 消解。
     *
     * <p>注意：本方法与 {@link #resolvePage(InventoryClickEvent)} 职责不同 —— 后者只解析
     * <b>页码</b>（holder 载荷 → 默认 1），不承担界面归属判定，二者不可互相替代。
     *
     * @param view 事件界面，可为 null
     * @return {@link GuiViewKey#WRONG_BOOK} 或 null（不归本类管）
     */
    private GuiViewKey resolveOwnedView(InventoryView view) {
        GuiViewKey key = GuiIdentity.resolve(view);
        return key == GuiViewKey.WRONG_BOOK ? key : null;
    }

    /**
     * 解析当前错题本界面的页码（R5-1 / R5-11）。
     *
     * <p>优先级：{@link GuiIdentity#payloadOf(org.bukkit.inventory.InventoryView)} 的
     * {@link GuiPayload.Page} —— GUI 打开时随 holder 写入的页码，结构性事实，不受标题
     * 文案/本地化/管理员改名影响。payload 缺失时返回 1（标题正则兜底已删除）。
     *
     * @param event 点击事件（读其 view 的 holder 载荷）
     * @return 1 起的页码；payload 缺失时为 1
     */
    private int resolvePage(InventoryClickEvent event) {
        var payload = GuiIdentity.payloadOf(event.getView());
        if (payload instanceof GuiPayload.Page pagePayload) {
            return pagePayload.page();
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
