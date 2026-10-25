package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class WordBankSelectGUI {

    private static final int GUI_SIZE = 27;
    /** 词库按钮槽位（7 个，slots 10-16） */
    private static final int[] BANK_SLOTS = { 10, 11, 12, 13, 14, 15, 16 };
    /** R-53 fan-in：词库按钮槽位起点（点击侧 MiscGUIListener 映射共用）。 */
    public static final int BANK_SLOT_START = BANK_SLOTS[0];
    /** R-53 fan-in：词库按钮槽位数 = 截断阈值（点击侧映射共用，保证"更多…"位置一致）。 */
    public static final int BANK_SLOT_COUNT = BANK_SLOTS.length;
    private final LetsLearnEnglish plugin;
    // R-53：构造快照仅作 open() 重查失败时的兜底（reloadWordBanks 前 wordManager 可能尚无词库）。
    // 正常路径 open() 每次重查 getAvailableDifficulties()，新词库热重载后即刻可见。
    private final List<String> availableBanks;

    public WordBankSelectGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.availableBanks = new ArrayList<>(plugin.getWordManager().getAvailableDifficulties());
    }

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-bank.title"));
        // R5-3/R5-16：挂结构身份（holder），holder 是唯一身份，标题仅作显示。
        Inventory inv = Bukkit.createInventory(PluginGuiHolder.of(GuiViewKey.WORD_BANK), GUI_SIZE, title);

        String clickHint = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "word-bank.click-hint"));

        // R-53：open() 时重查 —— getAvailableDifficulties() 返回 TreeSet 排序快照，
        // 与 MiscGUIListener 点击侧重取同序（点 A 开 A 语义不变）。构造快照仅兜底。
        List<String> banks = new ArrayList<>(plugin.getWordManager().getAvailableDifficulties());
        if (banks.isEmpty() && !availableBanks.isEmpty()) {
            banks = availableBanks;
        }
        boolean hasMore = banks.size() > BANK_SLOTS.length;
        int shown = hasMore ? BANK_SLOTS.length - 1 : banks.size();
        for (int i = 0; i < shown; i++) {
            String bankName = banks.get(i);
            int slot = BANK_SLOTS[i];
            String displayName = getDisplayName(bankName, messages);
            inv.setItem(slot, createItem(Material.BOOK,
                    ChatColor.translateAlternateColorCodes('&', "&e" + displayName),
                    Collections.singletonList(clickHint)));
        }

        // R-53：第 8 个词库起给"更多词库…"提示行（不再静默丢弃；完整翻页留 Phase 3）
        if (hasMore) {
            inv.setItem(BANK_SLOTS[BANK_SLOTS.length - 1], createItem(Material.BOOK,
                    ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "word-bank.more-banks")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "word-bank.more-banks-lore")
                                    .replace("{count}", String.valueOf(
                                            banks.size() - BANK_SLOTS.length + 1))))));
        }

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&c" +
                MessageUtil.getGuiMsg(messages, "gui.back-button"));
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));
        GUIUtils.fillEmpty(inv); // R12-M3：空槽填灰玻璃（防拖纵深）

        player.openInventory(inv);
    }

    /**
     * 词库显示名：先查 messages 里的 {@code word-bank.difficulty-<key>}（R1-L1：
     * 先原样、后小写两次查键 —— 自定义词库 {@code MyBank.yml} 配
     * {@code difficulty-MyBank} 与 {@code difficulty-mybank} 均可命中），
     * 缺失时回退到「首字母大写化」的 key（纯装饰性兜底，不构成硬编码文案）。
     * S7：保留原 getString + null 判语义（回退文案比 [?key] 占位更好），仅补 messages
     * 自身为 null 时的防护（此前会在词库按钮渲染处 NPE）。
     */
    private String getDisplayName(String bankKey, FileConfiguration messages) {
        if (messages == null) {
            return prettifyBankKey(bankKey);
        }
        // R1-L1：原样键优先（键值可能带大写），未命中再小写化（历史键约定）
        String configured = messages.getString("word-bank.difficulty-" + bankKey);
        if (configured == null || configured.isEmpty()) {
            configured = messages.getString(
                    "word-bank.difficulty-" + bankKey.toLowerCase(Locale.ROOT));
        }
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        return prettifyBankKey(bankKey);
    }

    /** 兜底显示名：下划线分段首字母大写化（原 getDisplayName 尾部逻辑抽出）。 */
    private static String prettifyBankKey(String bankKey) {
        String[] parts = bankKey.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.length() > 0)
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
