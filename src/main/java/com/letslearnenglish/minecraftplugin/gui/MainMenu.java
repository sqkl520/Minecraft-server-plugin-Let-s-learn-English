package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
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
        // R-46/R-86i: menu-size 非法回落 — Bukkit 只接受 9 的倍数（最大 54），非法值直接炸
        // IllegalArgumentException。且本菜单按钮槽位固定在 10-18（word-learning 11 ~
        // language-toggle 18），布局至少需要 27 格：9/18 虽通过 Bukkit 校验，但按钮 setItem
        // 越界会抛 ArrayIndexOutOfBoundsException、开菜单即崩，故最小合法值提为 27 ——
        // 低于 27 / 非 9 倍数 / 超 54 一律 WARNING 回落 27。填充循环按实际 size 走；
        // 点击侧（MainMenuListener）按同一份配置槽位判定，渲染与点击天然一致。
        int size = plugin.getConfigManager().getMainConfig().getInt("gui.menu-size", 27);
        if (size < 27 || size > 54 || size % 9 != 0) {
            plugin.getLogger().warning("Invalid gui.menu-size: " + size
                    + " (must be one of 27/36/45/54; at least 27 slots are required for the button layout). Falling back to 27.");
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
        // P2 fan-in（S1 上报）：MainMenuListener.handleMainMenu 已改读同一份
        // gui-layout.main-menu 配置（getGuiSlot + 相同默认值），两侧不再漂移；
        // 管理员改配置槽位后渲染与点击自动对齐。
        menu.setItem(cm.getGuiSlot("main-menu", "wrong-book", 14), createMenuItem(cm.getGuiMaterial("main-menu", "wrong-book", Material.BOOKSHELF), wrongName, wrongLore));

        String btnName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.button"));
        String btnLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.button-lore"));
        menu.setItem(cm.getGuiSlot("main-menu", "language-toggle", 18), createMenuItem(cm.getGuiMaterial("main-menu", "language-toggle", Material.RED_BED), btnName, btnLore));

        // Phase 3 · MC-1（R-82 收口）：新增【单词对决】slot 12 +【每日签到】slot 16，
        // 零移动零扩容（既有 7 按钮零改动）。槽位/材质走同一份 gui-layout.main-menu
        // 配置（与点击侧 MainMenuListener 双侧同源，fallback 值一致：12/16）。
        // 模块门（渲染侧）：duel.enabled / checkin.enabled=false 时按钮不渲染，
        // 该槽回落开头的灰玻璃占位；点击侧（MainMenuListener）同款门对僵尸界面兜底拒绝。
        if (cm.isDuelEnabled()) {
            String duelName = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "main-menu.duel"));
            String duelLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "main-menu.duel-lore"));
            menu.setItem(cm.getGuiSlot("main-menu", "duel", 12),
                    createMenuItem(cm.getGuiMaterial("main-menu", "duel", Material.DIAMOND_SWORD),
                            duelName, duelLore));
        }

        if (cm.isCheckinEnabled()) {
            String checkinName = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "main-menu.checkin"));
            String checkinLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "main-menu.checkin-lore"));
            menu.setItem(cm.getGuiSlot("main-menu", "checkin", 16),
                    createMenuItem(cm.getGuiMaterial("main-menu", "checkin", Material.SUNFLOWER),
                            checkinName, checkinLore));
        }

        // R-86：menu-open 不再在此显式播放 —— SoundManager.onInventoryOpen 会对所有
        // 插件 GUI 全局播一次，此处保留会双声（去重方向与理由见 SoundManager 类注释）。
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