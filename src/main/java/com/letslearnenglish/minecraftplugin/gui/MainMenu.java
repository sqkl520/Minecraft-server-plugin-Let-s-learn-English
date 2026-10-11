package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;

/**
 * Main Menu GUI
 *
 * The primary menu interface for the English learning plugin.
 * Provides access to all learning modules.
 */
public class MainMenu {

    private final LetsLearnEnglish plugin;

    public MainMenu(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "gui.main-menu-title"));
        // R-46: menu-size 非法回落 — Bukkit 只接受 9 的倍数（最大 54），非法值直接炸
        // IllegalArgumentException。回落 27 并记 WARNING，填充循环按实际 size 走。
        int size = plugin.getConfigManager().getMainConfig().getInt("gui.menu-size", 27);
        if (size != 9 && size != 18 && size != 27 && size != 36 && size != 45 && size != 54) {
            plugin.getLogger().warning("Invalid gui.menu-size: " + size
                    + " (must be one of 9/18/27/36/45/54). Falling back to 27.");
            size = 27;
        }

        Inventory menu = Bukkit.createInventory(null, size, title);

        ConfigManager cm = plugin.getConfigManager();

        for (int i = 0; i < size; i++) {
            menu.setItem(i, createMenuItem(Material.GRAY_STAINED_GLASS_PANE, " ", ""));
        }

        String wordName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.word-learning"));
        String wordLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.word-learning-lore"));
        String dialogueName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.dialogue-practice"));
        String dialogueLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.dialogue-practice-lore"));
        String progressName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.progress-stats"));
        String progressLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.progress-stats-lore"));

        menu.setItem(cm.getGuiSlot("main-menu", "word-learning", 11), createMenuItem(cm.getGuiMaterial("main-menu", "word-learning", Material.BOOK), wordName, wordLore));
        menu.setItem(cm.getGuiSlot("main-menu", "dialogue-practice", 13), createMenuItem(cm.getGuiMaterial("main-menu", "dialogue-practice", Material.PAPER), dialogueName, dialogueLore));
        menu.setItem(cm.getGuiSlot("main-menu", "personal-profile", 15), createMenuItem(cm.getGuiMaterial("main-menu", "personal-profile", Material.DIAMOND), progressName, progressLore));

        String squareName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.square"));
        String squareLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.square-lore"));
        menu.setItem(cm.getGuiSlot("main-menu", "english-square", 17), createMenuItem(cm.getGuiMaterial("main-menu", "english-square", Material.PLAYER_HEAD), squareName, squareLore));

        String dailyName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.daily-challenge"));
        String dailyLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.daily-challenge-lore"));
        menu.setItem(cm.getGuiSlot("main-menu", "daily-challenge", 10),
                createMenuItem(cm.getGuiMaterial("main-menu", "daily-challenge", Material.WRITABLE_BOOK),
                        dailyName, dailyLore));

        String wrongName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.wrong-book"));
        String wrongLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.wrong-book-lore"));
        // R-47: 错题本按钮接配置（与其它 6 按钮一致；此前 slot 14 / BOOKSHELF 写死。
        // 注意 MainMenuListener.handleMainMenu 仍按 slot 14 判定 —— 管理员改了配置后需
        // 同步改监听器，见 R-47 报告；默认值保持 14 因此默认行为零变化）。
        menu.setItem(cm.getGuiSlot("main-menu", "wrong-book", 14), createMenuItem(cm.getGuiMaterial("main-menu", "wrong-book", Material.BOOKSHELF), wrongName, wrongLore));

        String btnName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.button"));
        String btnLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.button-lore"));
        menu.setItem(cm.getGuiSlot("main-menu", "language-toggle", 18), createMenuItem(cm.getGuiMaterial("main-menu", "language-toggle", Material.RED_BED), btnName, btnLore));

        SoundManager.playSound(player, "menu-open");
        player.openInventory(menu);
    }

    private ItemStack createMenuItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            meta.setLore(Collections.singletonList(
                    ChatColor.translateAlternateColorCodes('&', lore)));
            item.setItemMeta(meta);
        }
        return item;
    }
}