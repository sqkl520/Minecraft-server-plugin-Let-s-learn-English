package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.ConfirmationGUI;
import com.letslearnenglish.minecraftplugin.gui.DailyChallengeGUI;
import com.letslearnenglish.minecraftplugin.gui.DuelChallengeGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.WordBankSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.WrongBookGUI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Listener for Main Menu, Language Switch, and Confirmation GUI interactions.
 */
public class MainMenuListener implements Listener {

    /** 标题被截断时，至少要这么长才允许做「前缀即命中」的宽松比对 */
    private static final int MIN_TITLE_MATCH = 4;

    private final LetsLearnEnglish plugin;

    public MainMenuListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (title == null) return;

        boolean isMainMenu = isMainMenuTitle(title);
        if (!isMainMenu && !ConfirmationGUI.isConfirmGUI(title)) {
            return;
        }

        // R-01/R-22: 只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        if (event.getClickedInventory() == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();

        if (isMainMenu) {
            handleMainMenu(player, slot);
        } else {
            handleConfirmGUI(player, slot);
        }
    }

    /**
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 主菜单的玻璃板、确认界面的取消按钮都能被拖进玩家背包。
     *
     * 只在拖拽触及界面上半部分（插件 GUI 自身）时取消，整理自己背包不受影响。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title == null) return;
        if (!isMainMenuTitle(title) && !ConfirmationGUI.isConfirmGUI(title)) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * 判定标题是否为主菜单。
     *
     * 优先与配置中的 gui.main-menu-title 比对（标题可被自定义，硬编码子串会漂移），
     * 配置读不到时回退到中英关键词，与 GUIUtils 的白名单保持一致。
     */
    private boolean isMainMenuTitle(String title) {
        if (title == null || title.isEmpty()) return false;

        for (String lang : new String[]{"zh", "en"}) {
            String configured = plugin.getConfigManager().getMessageConfig(lang)
                    .getString("gui.main-menu-title");
            if (configured == null || configured.isEmpty()) continue;
            String expected = ChatColor.translateAlternateColorCodes('&', configured);
            if (title.equals(expected)) return true;
            // 客户端可能把标题截断，因此「标题是配置标题的前缀」也算命中（避免过短误配）
            if (title.length() >= MIN_TITLE_MATCH && expected.startsWith(title)) return true;
        }

        return title.contains("Let's Learn English") || title.contains("一起学英语");
    }

    /**
     * R-55d：槽位不再写死，改读 {@code gui-layout.main-menu} 配置，
     * 与渲染侧 {@link MainMenu} 的 getGuiSlot 调用逐项对齐（fallback 值相同）。
     * R-47 fan-in：错题本也改读 {@code gui-layout.main-menu.wrong-book}（默认 14），
     * 与渲染侧 MainMenu 的 getGuiSlot 对齐，不再两边写死（配置改槽位后点击不再错位）。
     * 注意：switch 的 case 必须是编译期常量，配置值只能走 if-else 链。
     */
    private void handleMainMenu(Player player, int slot) {
        var cm = plugin.getConfigManager();
        // 模块门（点击侧，旅程验收收口）：渲染侧已按 word-system/ai-chat/daily-challenges
        // 隐藏对应按钮；本处覆盖"关闭前已打开的菜单残留按钮"的存量点击（僵尸界面兜底，
        // 与 duel/checkin 分支既有模式一致）——关闭时音效 + module.disabled 提示并拒绝。
        boolean wordOn = cm.isWordSystemEnabled();
        if (slot == cm.getGuiSlot("main-menu", "word-learning", 11)) {
            if (!wordOn) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "module.disabled"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            new WordBankSelectGUI(plugin).open(player);
        } else if (slot == cm.getGuiSlot("main-menu", "dialogue-practice", 13)) {
            if (!cm.isAIChatEnabled()) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "module.disabled"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            player.performCommand("le dialogue");
        } else if (slot == cm.getGuiSlot("main-menu", "personal-profile", 15)) {
            SoundManager.playSound(player, "menu-click");
            player.performCommand("le progress");
        } else if (slot == cm.getGuiSlot("main-menu", "english-square", 17)) {
            SoundManager.playSound(player, "menu-click");
            player.performCommand("le square");
        } else if (slot == cm.getGuiSlot("main-menu", "daily-challenge", 10)) {
            if (!cm.isDailyChallengesEnabled()) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "module.disabled"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            new DailyChallengeGUI(plugin).open(player);
        } else if (slot == cm.getGuiSlot("main-menu", "wrong-book", 14)) {
            // R-47 fan-in：错题本槽位改读配置，与渲染侧 MainMenu 对齐
            // 模块门（旅程验收收口）：归 word-system（错题本是单词学习从属面）
            if (!wordOn) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "module.disabled"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            new WrongBookGUI(plugin).open(player, 1);
        } else if (slot == cm.getGuiSlot("main-menu", "language-toggle", 18)) {
            SoundManager.playSound(player, "menu-click");
            handleLanguageSwitch(player);
        } else if (slot == cm.getGuiSlot("main-menu", "duel", 12)) {
            // Phase 3 · MC-1（R-82）：【单词对决】点击分支。与渲染侧 MainMenu 读同一份
            // gui-layout.main-menu 配置（fallback 12 两侧一致），改槽位后双侧自动对齐。
            // 门语义（plan-menu-config §4.2）：duel.enabled=false 时"发起"恒被拦
            //（module.disabled 兜底），但"已在局中"放行重开界面 —— 关开关后进行中的
            // 对局仍可退出/重开，与 LearnCommand duel 分支的 duel.enabled || inDuel 同款。
            var duelManager = plugin.getDuelManager();
            if (duelManager == null) {
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "general.error", "message",
                        plugin.getMessageUtil().getPlayerMessage(player, "error.duel-unavailable")));
                return;
            }
            boolean inDuel = duelManager.isInDuel(player.getUniqueId());
            if (!inDuel && !cm.isDuelEnabled()) {
                // 僵尸界面兜底：渲染侧已把该槽收回为玻璃占位，这里覆盖"关闭前已打开的
                // 菜单残留按钮"的存量点击（与 SocialListener 榜单占位槽拒绝同模式）。
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "module.disabled"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            if (inDuel) {
                // 已在局中：重开当前对决界面（等价 /le duel gui）
                duelManager.reopenDuelGUI(player);
            } else {
                // 发起：打开在线玩家选择器（点头像 = sendDuelRequest，全部忙态/互斥
                // 守卫在 DuelManager 内部复用，本处不预判）
                new DuelChallengeGUI(plugin).open(player);
            }
        } else if (slot == cm.getGuiSlot("main-menu", "checkin", 16)) {
            // Phase 3 · MC-1：【每日签到】点击分支 —— 直接 performCommand("le checkin")
            //（签到是瞬时动作：一条聊天消息 + 音效，不开专属界面）。
            // 点击侧模块门（僵尸界面兜底）与渲染侧同源；命令侧亦有门（纵深）。
            if (!cm.isCheckinEnabled()) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "module.disabled"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            player.performCommand("le checkin");
        }
    }

    private void handleLanguageSwitch(Player player) {
        String currentLang = plugin.getPlayerLanguage(player);
        String targetLang = "zh".equals(currentLang) ? "en" : "zh";
        ConfirmationGUI confirm = new ConfirmationGUI(plugin, targetLang);
        confirm.open(player);
    }

    private void handleConfirmGUI(Player player, int slot) {
        // 取消按钮：任何阶段都可直接放弃，返回主菜单
        if (slot == ConfirmationGUI.CANCEL_SLOT) {
            ConfirmationGUI.resetStage(player);
            SoundManager.playSound(player, "menu-click");
            new MainMenu(plugin).open(player);
            return;
        }

        if (slot != ConfirmationGUI.CONFIRM_SLOT) return;

        // R-86：点击音效不再由 SoundManager 全局兜底（去重时已删除全局 InventoryClick
        // 播放），确认键这里显式补播 —— 与取消键及主菜单各按钮的显式播放保持一致。
        SoundManager.playSound(player, "menu-click");

        int stage = ConfirmationGUI.getStage(player);

        if (stage == 1) {
            // R-09：复用阶段 1 落盘的目标语言，不再从"当前语言反推"（中途语言变化时反推会切错方向）。
            // store 缺失（异常路径）时回退到反推保底。
            String targetLang = ConfirmationGUI.getTargetLang(player);
            if (targetLang == null) {
                String currentLang = plugin.getPlayerLanguage(player);
                targetLang = "zh".equals(currentLang) ? "en" : "zh";
            }
            String resolvedTarget = targetLang;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // F6-01：延迟 2 tick 期间玩家可能已点取消（resetStage 清光状态）或已下线 ——
                // 只有状态仍是「阶段 1 + 同一落盘目标语言」才续开第二页；任一不符直接放弃这次
                // reopen，绝不把已取消的玩家拽回二确第二页。
                if (!player.isOnline()) return;
                if (ConfirmationGUI.getStage(player) != 1) return;
                String storedTarget = ConfirmationGUI.getTargetLang(player);
                if (storedTarget == null || !storedTarget.equals(resolvedTarget)) return;
                ConfirmationGUI confirm = new ConfirmationGUI(plugin, resolvedTarget);
                confirm.openSecond(player);
            }, 2L);
        } else if (stage == 2) {
            // R-09：目标语言以存储为准；applySwitch 内部同样读存储，这里透传仅为构造实例。
            String targetLang = ConfirmationGUI.getTargetLang(player);
            if (targetLang == null) {
                String currentLang = plugin.getPlayerLanguage(player);
                targetLang = "zh".equals(currentLang) ? "en" : "zh";
            }
            ConfirmationGUI confirm = new ConfirmationGUI(plugin, targetLang);
            confirm.applySwitch(player);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                new MainMenu(plugin).open(player);
            }, 2L);
        }
    }
}
