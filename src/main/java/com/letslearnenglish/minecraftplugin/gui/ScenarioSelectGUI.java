package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager.DifficultyConfig;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager.ScenarioConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * 情景选择 GUI - 选择 AI 情景对话场景 + 难度
 */
public class ScenarioSelectGUI {

    private static final int GUI_SIZE = 27;

    private final LetsLearnEnglish plugin;

    /** 难度按钮的起始 slot */
    public static final int DIFFICULTY_SLOT_START = 18;
    /** 刷新 API 按钮 slot（管理员） */
    public static final int RELOAD_API_SLOT = 26;

    public ScenarioSelectGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msg = plugin.getConfigManager().getMessageConfig(lang);

        String title = msg.getString("dialogue.scenario-title");
        title = ChatColor.translateAlternateColorCodes('&', title);

        Inventory gui = Bukkit.createInventory(null, GUI_SIZE, title);

        // 背景
        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        if (bgMeta != null) {
            bgMeta.setDisplayName(" ");
            bg.setItemMeta(bgMeta);
        }
        for (int i = 0; i < GUI_SIZE; i++) {
            gui.setItem(i, bg.clone());
        }

        // 返回按钮 (slot 8)
        String backLabel = msg.getString("dialogue.back-to-menu");
        String backLore = msg.getString("dialogue.back-lore");
        gui.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel, backLore));

        // 场景按钮 (slots 10-16)
        int slot = 10;
        String clickStart = msg.getString("dialogue.click-start");
        String systemPrompt = msg.getString("dialogue.system-prompt");
        // R-04：按玩家版场景配置 —— 个人场景出现在该玩家的列表里，
        // 展示名与全局同规则（display_name / display_name_zh）。
        for (ScenarioConfig sc : plugin.getAIChatManager().getScenarioConfigs(player).values()) {
            if (slot > 16) break;
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', clickStart));
            // 显示系统提示词摘要（悬停可见）
            if (sc.prompt != null && !sc.prompt.isEmpty()) {
                lore.add("");
                lore.add(ChatColor.translateAlternateColorCodes('&', systemPrompt));
                for (String line : sc.prompt.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                "&7  " + (trimmed.length() > 45 ? trimmed.substring(0, 45) + "..." : trimmed)));
                    }
                }
            }
            gui.setItem(slot++, createItemLore(sc.material,
                    "&b&l" + sc.getDisplayName("zh".equalsIgnoreCase(lang)), lore));
        }

        // 难度选择按钮 (slots 18, 19, 20)
        String currentDiff = plugin.getAIChatManager().getPlayerDifficulty(player);
        int diffSlot = DIFFICULTY_SLOT_START;
        String diffSelected = msg.getString("dialogue.diff-selected");
        String diffSelect = msg.getString("dialogue.diff-select");
        for (DifficultyConfig dc : plugin.getAIChatManager().getDifficulties().values()) {
            if (diffSlot > 20) break;
            boolean selected = dc.key.equals(currentDiff);
            Material mat;
            String lore;
            if (selected) {
                mat = Material.LIME_DYE;
                lore = diffSelected;
            } else {
                mat = Material.GRAY_DYE;
                lore = diffSelect;
            }
            gui.setItem(diffSlot++, createItem(mat,
                    (selected ? "&a&l" : "&7") + dc.getDisplayName("zh".equalsIgnoreCase(lang)),
                    lore));
        }

        // 难度分隔标签 (slot 22)
        String diffLabel = msg.getString("dialogue.difficulty-label");
        String diffLore = msg.getString("dialogue.difficulty-lore");
        gui.setItem(22, createItem(Material.NAME_TAG, diffLabel, diffLore));

        // 刷新 API 密钥按钮 (slot 26) — 仅管理员可见
        if (player.hasPermission("letslearnenglish.admin")) {
            boolean apiAvailable = plugin.getAIChatManager().isAvailable();
            String apiReload = msg.getString("dialogue.api-reload");
            String apiStatus = apiAvailable
                    ? msg.getString("dialogue.api-configured")
                    : msg.getString("dialogue.api-not-configured");
            gui.setItem(26, createItem(
                    apiAvailable ? Material.ENCHANTED_BOOK : Material.WRITABLE_BOOK,
                    apiReload, apiStatus));
        }

        SoundManager.playSound(player, "menu-open");
        player.openInventory(gui);
    }

    private ItemStack createItem(Material material, String name, String lore) {
        return createItemLore(material, name, Arrays.asList(ChatColor.translateAlternateColorCodes('&', lore)));
    }

    private ItemStack createItemLore(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}