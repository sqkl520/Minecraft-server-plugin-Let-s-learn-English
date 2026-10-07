package com.letslearnenglish.minecraftplugin.gui;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;

/**
 * Two-step confirmation GUI for language switching.
 *
 * Flow: MainMenu language button → Confirm page 1 → Confirm page 2 → switch
 *
 * 每个阶段都有两个按钮：确认（slot 13）与取消（slot 22，返回主菜单）。
 */
public class ConfirmationGUI {

    private static final Map<UUID, Integer> confirmStages = new ConcurrentHashMap<>();
    public static final String CONFIRM_TITLE_KEY = "language.confirm-title";

    /** 确认按钮槽位（两个阶段共用） */
    public static final int CONFIRM_SLOT = 13;
    /** 取消按钮槽位（底部居中，与其它 GUI 的底部操作区一致） */
    public static final int CANCEL_SLOT = 22;

    private static final int GUI_SIZE = 27;

    private final LetsLearnEnglish plugin;
    private final String targetLang;

    public ConfirmationGUI(LetsLearnEnglish plugin, String targetLang) {
        this.plugin = plugin;
        this.targetLang = targetLang;
    }

    public static boolean isConfirmGUI(String title) {
        return title.contains("Confirm") || title.contains("确认");
    }

    public static int getStage(Player player) {
        return confirmStages.getOrDefault(player.getUniqueId(), 0);
    }

    public static void resetStage(Player player) {
        confirmStages.remove(player.getUniqueId());
    }

    /** 第一阶段确认界面 */
    public void open(Player player) {
        showConfirmPage(player, 1);
    }

    /** 第二阶段确认界面 */
    public void openSecond(Player player) {
        showConfirmPage(player, 2);
    }

    /**
     * 打开确认界面。
     *
     * 文案按玩家「当前」语言渲染（切换要等到第二次确认之后才生效），
     * 因此中英文玩家各自看到的都是自己的语言。
     */
    private void showConfirmPage(Player player, int stage) {
        FileConfiguration messages = plugin.getConfigManager()
                .getMessageConfig(plugin.getPlayerLanguage(player));

        String title = ChatColor.translateAlternateColorCodes('&',
                messages.getString(CONFIRM_TITLE_KEY));
        String itemName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.confirm-item"));
        String itemLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.confirm-lore"));
        String cancelName = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.cancel-item"));
        String cancelLore = ChatColor.translateAlternateColorCodes('&',
                messages.getString("language.cancel-lore"));

        confirmStages.put(player.getUniqueId(), stage);

        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);
        inv.setItem(CONFIRM_SLOT, createItem(Material.EMERALD, itemName, itemLore));
        inv.setItem(CANCEL_SLOT, createItem(Material.BARRIER, cancelName, cancelLore));

        player.openInventory(inv);
    }

    public void applySwitch(Player player) {
        confirmStages.remove(player.getUniqueId());
        plugin.setPlayerLanguage(player, targetLang);

        // 切换后用新语言播报（前缀与文案都由 MessageUtil 按玩家语言解析）
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "language.switched"));
    }

    private ItemStack createItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}
