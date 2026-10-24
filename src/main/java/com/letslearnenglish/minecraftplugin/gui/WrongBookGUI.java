package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.data.DifficultWord;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Wrong Book GUI — displays words the player has answered incorrectly 3+ times.
 *
 * 54-slot layout (6 rows):
 *   Slot 8: Back to main menu (upper-right corner)
 *   Word entries: 见 {@link #WORD_SLOTS}（第二行起 + 底行避开导航键的剩余槽位）
 *   Bottom row (45-53) navigation:
 *     Slot 47: Previous page
 *     Slot 49: Page indicator
 *     Slot 51: Next page
 *
 * 槽位定义（唯一真相，本类的常量供监听器判定点击，不要在别处再写魔法数字）：
 * 单词槽位与返回键/导航键槽位**不允许有交集**。
 */
public class WrongBookGUI {

    private static final int GUI_SIZE = 54;

    public static final int PREV_SLOT = 47;
    public static final int BACK_SLOT = 8;
    public static final int NEXT_SLOT = 51;
    public static final int PAGE_INDICATOR_SLOT = 49;

    /**
     * 单词条目占用的槽位（显式列出，方便一眼看懂）。
     * 第二行到第五行 = 9-44；第六行取 45/46/48/50/52/53，
     * 已避开 BACK_SLOT(8) / PREV_SLOT(47) / PAGE_INDICATOR_SLOT(49) / NEXT_SLOT(51)。
     *
     * 必须保持升序 —— {@link com.letslearnenglish.minecraftplugin.listener.WrongBookListener}
     * 用 Arrays.binarySearch 判定点击。
     */
    public static final int[] WORD_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44,
            45, 46, 48, 50, 52, 53
    };

    /** 每页单词数 = 可用槽位数（36 + 6 = 42），避免出现「显示了却点不动」的单词。 */
    private static final int ITEMS_PER_PAGE = WORD_SLOTS.length;

    /**
     * R-54：空错题本提示 BOOK 的展示槽位 —— 顶部中央（第一行正中）。
     * 必须在 WORD_SLOTS 之外：旧位置 slot 22 在 WORD_SLOTS 内，玩家点击中央 BOOK
     * 会落进单词分支（监听器只认 PAPER 材质所以今天是"点了没反应"而非误删，
     * 但仍属误触面）。slot 4 不在 WORD_SLOTS / BACK_SLOT / 导航键内，纯展示。
     */
    private static final int EMPTY_BOOK_SLOT = 4;

    private final LetsLearnEnglish plugin;

    public WrongBookGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, int page) {
        // 模块门（旅程验收收口）：word-system 承诺"错题本隐藏"——本类收敛所有打开
        // 入口（主菜单点击/翻页/删除后重开），单点门覆盖全链；点击侧
        // WrongBookListener 另有僵尸界面兜底。
        if (!plugin.getConfigManager().isWordSystemEnabled()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "module.disabled"));
            return;
        }
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "wrong-book.title-page")
                        .replace("{page}", String.valueOf(page)));

        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);

        // 先用灰色玻璃板铺满全部槽位，防止玩家把物品拖进空槽
        // （单词与导航键随后覆盖，填充顺序：占位 -> 单词 -> 导航键）
        ItemStack filler = createGlassPane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < GUI_SIZE; i++) {
            inv.setItem(i, filler.clone());
        }

        // R9/P1-B：只展示"已收录"的词（wrong_count >= 3）——difficult_words 表在首次答错
        // 即建行（兼作计数器），旧实现直接全量展示，与"错 3 次才进错题本"的提示自相矛盾。
        List<Map.Entry<String, DifficultWord>> wordList = plugin.getPlayerDataStore()
                .getWrongBookEntries(player.getUniqueId());

        int totalPages = Math.max(1, (int) Math.ceil((double) wordList.size() / ITEMS_PER_PAGE));
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        int startIndex = (page - 1) * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, wordList.size());

        if (wordList.isEmpty()) {
            // Empty state — R-54：BOOK 放 EMPTY_BOOK_SLOT（slot 4，WORD_SLOTS 之外），
            // 中央区域留给灰色玻璃板占位，点中央无反应、不再误入单词分支
            ItemStack book = new ItemStack(Material.BOOK);
            ItemMeta bookMeta = book.getItemMeta();
            if (bookMeta != null) {
                bookMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "wrong-book.empty")));
                String emptyLore = MessageUtil.getGuiMsg(messages, "wrong-book.empty-lore");
                bookMeta.setLore(Collections.singletonList(
                        ChatColor.translateAlternateColorCodes('&', emptyLore)));
                book.setItemMeta(bookMeta);
            }
            inv.setItem(EMPTY_BOOK_SLOT, book);
        } else {
            // Populate word entries
            for (int i = startIndex; i < endIndex; i++) {
                Map.Entry<String, DifficultWord> entry = wordList.get(i);
                String wordKey = entry.getKey();
                DifficultWord dw = entry.getValue();

                // Look up full word info from word bank
                Word wordInfo = plugin.getWordManager().getWord(wordKey);
                String chinese = wordInfo != null ? wordInfo.getChinese() : "?";

                // 槽位一律从 WORD_SLOTS 取（已避开返回键与导航键），不再自己算 (row+1)*9+col
                int slotIndex = i - startIndex;
                if (slotIndex >= WORD_SLOTS.length) break;
                int invSlot = WORD_SLOTS[slotIndex];

                Material mat = Material.PAPER;
                String itemName = ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "wrong-book.word-name")
                                .replace("{word}", wordKey));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "wrong-book.word-chinese")
                                .replace("{chinese}", chinese)));
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "wrong-book.word-count")
                                .replace("{count}", String.valueOf(dw.getWrongCount()))));
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "wrong-book.word-date")
                                .replace("{date}", sdf.format(new Date(dw.getAddedAt())))));
                lore.add("");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "wrong-book.click-remove")));
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "wrong-book.shift-practice")));

                inv.setItem(invSlot, createItem(mat, itemName, lore));
            }
        }

        // Navigation buttons
        // Previous page
        if (page > 1) {
            inv.setItem(PREV_SLOT, createItem(Material.ARROW,
                    ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "wrong-book.prev-page")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "wrong-book.prev-page-lore")
                                    .replace("{page}", String.valueOf(page - 1))))));
        }

        // Page indicator
        ItemStack pageIndicator = new ItemStack(Material.PAPER);
        ItemMeta piMeta = pageIndicator.getItemMeta();
        if (piMeta != null) {
            piMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "wrong-book.page-indicator")
                            .replace("{current}", String.valueOf(page))
                            .replace("{total}", String.valueOf(totalPages))));
            pageIndicator.setItemMeta(piMeta);
        }
        inv.setItem(PAGE_INDICATOR_SLOT, pageIndicator);

        // Back button (upper-right corner, unified with other GUIs)
        inv.setItem(BACK_SLOT, createItem(Material.RED_STAINED_GLASS_PANE,
                ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "gui.back-button")),
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        // Next page
        if (page < totalPages) {
            inv.setItem(NEXT_SLOT, createItem(Material.ARROW,
                    ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "wrong-book.next-page")),
                    Collections.singletonList(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "wrong-book.next-page-lore")
                                    .replace("{page}", String.valueOf(page + 1))))));
        }

        // 底行剩余空槽已在开头的占位填充里铺好玻璃板，无需再补

        player.openInventory(inv);
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

    private ItemStack createGlassPane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }
}
