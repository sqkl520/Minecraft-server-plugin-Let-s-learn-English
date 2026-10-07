package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.gui.ConfirmationGUI;
import com.letslearnenglish.minecraftplugin.gui.DailyChallengeGUI;
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

    private void handleMainMenu(Player player, int slot) {
        switch (slot) {
            case 11 -> {
                SoundManager.playSound(player, "menu-click");
                new WordBankSelectGUI(plugin).open(player);
            }
            case 13 -> {
                SoundManager.playSound(player, "menu-click");
                player.performCommand("le dialogue");
            }
            case 15 -> {
                SoundManager.playSound(player, "menu-click");
                player.performCommand("le progress");
            }
            case 17 -> {
                SoundManager.playSound(player, "menu-click");
                player.performCommand("le square");
            }
            case 10 -> {
                SoundManager.playSound(player, "menu-click");
                plugin.getGuiNavigationManager().pushBack(player.getUniqueId(),
                        () -> new MainMenu(plugin).open(player));
                new DailyChallengeGUI(plugin).open(player);
            }
            case 14 -> {
                SoundManager.playSound(player, "menu-click");
                new WrongBookGUI(plugin).open(player, 1);
            }
            case 18 -> {
                SoundManager.playSound(player, "menu-click");
                handleLanguageSwitch(player);
            }
            default -> {}
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

        int stage = ConfirmationGUI.getStage(player);

        if (stage == 1) {
            String currentLang = plugin.getPlayerLanguage(player);
            String targetLang = "zh".equals(currentLang) ? "en" : "zh";
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                ConfirmationGUI confirm = new ConfirmationGUI(plugin, targetLang);
                confirm.openSecond(player);
            }, 2L);
        } else if (stage == 2) {
            String currentLang = plugin.getPlayerLanguage(player);
            String targetLang = "zh".equals(currentLang) ? "en" : "zh";
            ConfirmationGUI confirm = new ConfirmationGUI(plugin, targetLang);
            confirm.applySwitch(player);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                new MainMenu(plugin).open(player);
            }, 2L);
        }
    }
}
