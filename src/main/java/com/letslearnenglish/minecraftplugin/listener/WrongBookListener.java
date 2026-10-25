package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.GUIUtils;
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
        // R5-7：改走 resolveOwnedView（holder 优先、标题兜底）。原判纯 holder —— /reload
        //（PlugMan 等）后旧 ClassLoader 的 PluginGuiHolder 过不了新 ClassLoader 的
        // instanceof，resolve 恒返回 null，本处理器整体失效：点击不处理也不 cancel，
        // 错题本的 PAPER 条目/导航箭头可被直接拿走。
        if (resolveOwnedView(event.getView()) != GuiViewKey.WRONG_BOOK) {
            return;
        }

        // R5-8：影子比对从「归属判定之前」挪到「命中之后」。原位置让任意界面（含别的
        // 插件 GUI / 原版箱子）的每次点击都白跑一遍 resolveByTitle 全表比对。权衡：
        // 判定不命中的漂移不再被记录 —— 但那类漂移会以功能症状暴露，不需要影子日志；
        // 本插桩要查的是「holder 已命中后标题说法是否仍对得上」。Wave 3 随标题兜底删除。
        GuiIdentity.logMismatch(plugin, event.getView());

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
        // R5-1：页码优先读 holder 载荷（GUI 打开时写入，结构性事实）；取不到才回退
        // 标题解析（双轨期：覆盖 holder 未挂的历史界面 / 其它插件伪造的旧标题界面）。
        // 回退统一走 GUIUtils.extractPageFromTitle —— 本类曾有一份私有重写版，与新标题
        // 格式口径不一致，已删除（避免"翻页永远回第 1 页 / 跳错页"的静默漂移）。
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
     * 身份判定与 {@link #onInventoryClick} 入口一致（R5-1：holder 优先、标题兜底）；
     * 只在拖拽触及界面上半部分时取消，玩家整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        // R5-7：同 onInventoryClick，改走 resolveOwnedView（原判纯 holder 时 /reload 后
        // 失效 —— 条目/箭头可被拖走）。
        if (resolveOwnedView(event.getView()) != GuiViewKey.WRONG_BOOK) {
            return;
        }

        // R5-8：影子比对挪到归属命中之后（否则任意界面拖拽都要跑一遍标题全表比对）。
        GuiIdentity.logMismatch(plugin, event.getView());

        // 同上：身份判定不蕴含「拖的是上半区」，rawSlot < topSize 的判断必须保留。
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
     * R5-7：解析「本监听器负责的界面」—— holder 优先、标题兜底。
     *
     * <p>为什么必须补标题兜底：{@link GuiIdentity#resolve(InventoryView)} 是<b>纯 holder</b>
     * 判定。{@code /reload}（PlugMan 等）之后，旧 ClassLoader 载入的 {@code PluginGuiHolder}
     * 过不了新 ClassLoader 的 {@code instanceof}，{@code resolve} 恒返回 {@code null} ——
     * 本类的 click / drag 两处会同时失效（点击不处理也不 cancel，条目可被拿走/拖走）。
     *
     * <p><b>裁决：走方案 A</b>（{@code GuiIdentity.resolve(view, title)}）。理由与
     * {@code WordLearningListener#resolveOwnedView} 相同：白名单只保留在
     * {@link GuiIdentity#resolveByTitle(String)} 一处，避免两份判定日后漂移；
     * {@code resolve(view, title)} 内部就是「holder 命中即返回 holder 结果，未命中才回落
     * 标题」，且 holder 非 null 时<b>不读标题</b> —— 正好满足「holder 明确说是别的屏幕时
     * 不许用标题翻案」的红线。
     *
     * <p>与 HEAD 版的口径差异（有意）：HEAD 版是
     * {@code contains("Wrong Book") || contains("错题本") || contains("Difficult Words")}。
     * 现代码走 GuiIdentity 后不再含 {@code "Difficult Words"} —— 该关键词已在 MC-7 终局
     * 清理中从 {@code GUIUtils.isPluginGUI(String)} 删除（注释明写：现行标题恒含
     * "Wrong Book"，"Difficult Words" 是历史死启发式），重新加回等于复活一个覆盖率为零的
     * 分支并制造第二份白名单。若将来真有只含 "Difficult Words" 的旧界面，必须先回到
     * {@code GuiIdentity.resolveByTitle} 补映射（单一真相源），而不是在本类里补 contains。
     *
     * <p>注意：本方法与 {@link #resolvePage(InventoryClickEvent)} 职责不同 —— 后者只解析
     * <b>页码</b>（holder 载荷 → 标题正则），不承担界面归属判定，二者不可互相替代。
     *
     * @param view 事件界面，可为 null
     * @return {@link GuiViewKey#WRONG_BOOK} 或 null（不归本类管）
     */
    private GuiViewKey resolveOwnedView(InventoryView view) {
        if (view == null) return null;
        // holder 优先、未命中才回落标题（两者都在 GuiIdentity 内部完成）。
        GuiViewKey key = GuiIdentity.resolve(view, view.getTitle());
        return key == GuiViewKey.WRONG_BOOK ? key : null;
    }

    /**
     * 解析当前错题本界面的页码（R5-1）。
     *
     * <p>优先级：
     * <ol>
     *   <li>{@link GuiIdentity#payloadOf(org.bukkit.inventory.InventoryView)} 的
     *       {@link GuiPayload.Page} —— GUI 打开时随 holder 写入的页码，结构性事实，
     *       不受标题文案/本地化/管理员改名影响；</li>
     *   <li>取不到（holder 未挂 / payload 非 Page / 非插件界面）时回退
     *       {@link GUIUtils#extractPageFromTitle(String)} —— 双轨期兜底，语义与迁移前
     *       的标题正则口径统一（本类原有的私有重写版口径不同，已删除）。</li>
     * </ol>
     * 无论走哪条路，返回值都 &lt; 1 时由调用方 clamp 到 1（GUIUtils 本身恒返回 &gt;= 1）。
     *
     * @param event 点击事件（读其 view 的 holder 载荷与标题）
     * @return 1 起的页码；都无法解析时为 1
     */
    private int resolvePage(InventoryClickEvent event) {
        var payload = GuiIdentity.payloadOf(event.getView());
        if (payload instanceof GuiPayload.Page pagePayload) {
            return pagePayload.page();
        }
        return GUIUtils.extractPageFromTitle(event.getView().getTitle());
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
