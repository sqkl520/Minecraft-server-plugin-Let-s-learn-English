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

import java.util.Arrays;
import java.util.Collections;

/**
 * Main Menu GUI
 *
 * The primary menu interface for the English learning plugin.
 * Provides access to all learning modules.
 */
public class MainMenu {

    /** R8-L3（D3-7）：主菜单 9 个按钮的 gui-layout 键，与下方默认槽位表逐位对应。 */
    private static final String[] MENU_SLOT_KEYS = {
            "word-learning", "dialogue-practice", "personal-profile", "english-square",
            "daily-challenge", "wrong-book", "language-toggle", "duel", "checkin"
    };
    /** R8-L3（D3-7）：9 个按钮的默认槽位（任一配置槽位越界时整组回落此表）。 */
    private static final int[] MENU_SLOT_DEFAULTS = { 11, 13, 15, 17, 10, 14, 18, 12, 16 };

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

        // R8-L3（D3-7）：槽位守卫（R8-L3 的 clamp 换位到本文件，ConfigManager 不动）。
        // gui-layout.main-menu 的 9 个槽位逐一校验：越界（<0 / >= size）会让 setItem 抛
        // ArrayIndexOutOfBoundsException（开菜单即崩），任一非法即整组回落默认槽位表 +
        // WARNING。点击侧（MainMenuListener）按裁决不动、仍读原配置——配置合法时两侧
        // 天然一致；配置非法时属管理员自担（WARNING 已明示），本批只保不崩。
        ConfigManager cm = plugin.getConfigManager();
        int[] menuSlots = new int[MENU_SLOT_KEYS.length];
        boolean menuSlotsValid = true;
        for (int i = 0; i < MENU_SLOT_KEYS.length; i++) {
            menuSlots[i] = cm.getGuiSlot("main-menu", MENU_SLOT_KEYS[i], MENU_SLOT_DEFAULTS[i]);
            if (menuSlots[i] < 0 || menuSlots[i] >= size) {
                plugin.getLogger().warning("Invalid gui-layout.main-menu." + MENU_SLOT_KEYS[i]
                        + ".slot: " + menuSlots[i] + " (must be 0.." + (size - 1)
                        + "). Falling back to default main-menu layout.");
                menuSlotsValid = false;
            }
        }
        if (!menuSlotsValid) {
            menuSlots = MENU_SLOT_DEFAULTS.clone();
        } else if (hasDuplicateSlots(menuSlots)) {
            // 同槽重复：仅告警不阻止（后渲染的按钮覆盖先渲染的，点击侧两分支取先命中者）
            plugin.getLogger().warning("Duplicate slot(s) in gui-layout.main-menu: "
                    + Arrays.toString(menuSlots) + " — buttons may overlap.");
        }

        Inventory menu = Bukkit.createInventory(null, size, title);

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

        // 模块门（渲染侧，一致性审计/旅程验收收口）：config 三层语义承诺"入口隐藏"，
        // 此前只对 duel/checkin 落了渲染门，word-system/ai-chat/daily-challenges 四个
        // 按钮关闭后照常渲染（承诺与实现不符的实伤）。现按各模块 config 注释逐一接线；
        // 关闭时该槽回落开头的灰玻璃占位。个人主页（15）不设门——progress 伞注释明示
        // "停伞不停展示"。点击侧（MainMenuListener）同款门对僵尸界面兜底拒绝。
        if (cm.isWordSystemEnabled()) {
            menu.setItem(menuSlots[0], createMenuItem(cm.getGuiMaterial("main-menu", "word-learning", Material.BOOK), wordName, wordLore));
        }
        if (cm.isAIChatEnabled()) {
            menu.setItem(menuSlots[1], createMenuItem(cm.getGuiMaterial("main-menu", "dialogue-practice", Material.PAPER), dialogueName, dialogueLore));
        }
        menu.setItem(menuSlots[2], createMenuItem(cm.getGuiMaterial("main-menu", "personal-profile", Material.DIAMOND), progressName, progressLore));

        String squareName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.square"));
        String squareLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.square-lore"));
        menu.setItem(menuSlots[3], createMenuItem(cm.getGuiMaterial("main-menu", "english-square", Material.PLAYER_HEAD), squareName, squareLore));

        String dailyName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.daily-challenge"));
        String dailyLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.daily-challenge-lore"));
        if (cm.isDailyChallengesEnabled()) {
            menu.setItem(menuSlots[4],
                    createMenuItem(cm.getGuiMaterial("main-menu", "daily-challenge", Material.WRITABLE_BOOK),
                            dailyName, dailyLore));
        }

        String wrongName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.wrong-book"));
        String wrongLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "main-menu.wrong-book-lore"));
        // R-47: 错题本按钮接配置（与其它 6 按钮一致；此前 slot 14 / BOOKSHELF 写死。
        // P2 fan-in（S1 上报）：MainMenuListener.handleMainMenu 已改读同一份
        // gui-layout.main-menu 配置（getGuiSlot + 相同默认值），两侧不再漂移；
        // 管理员改配置槽位后渲染与点击自动对齐。
        // 模块门（旅程验收收口）：word-system 承诺"错题本隐藏"——归 word-system 门
        // （错题本是单词学习的从属面；其练习入口消费答题输入，关闭必须同步隐藏）。
        if (cm.isWordSystemEnabled()) {
            menu.setItem(menuSlots[5], createMenuItem(cm.getGuiMaterial("main-menu", "wrong-book", Material.BOOKSHELF), wrongName, wrongLore));
        }

        String btnName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.button"));
        String btnLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "language.button-lore"));
        menu.setItem(menuSlots[6], createMenuItem(cm.getGuiMaterial("main-menu", "language-toggle", Material.RED_BED), btnName, btnLore));

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
            menu.setItem(menuSlots[7],
                    createMenuItem(cm.getGuiMaterial("main-menu", "duel", Material.DIAMOND_SWORD),
                            duelName, duelLore));
        }

        if (cm.isCheckinEnabled()) {
            String checkinName = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "main-menu.checkin"));
            String checkinLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "main-menu.checkin-lore"));
            menu.setItem(menuSlots[8],
                    createMenuItem(cm.getGuiMaterial("main-menu", "checkin", Material.SUNFLOWER),
                            checkinName, checkinLore));
        }

        // R-86：menu-open 不再在此显式播放 —— SoundManager.onInventoryOpen 会对所有
        // 插件 GUI 全局播一次，此处保留会双声（去重方向与理由见 SoundManager 类注释）。
        player.openInventory(menu);
    }

    /** R8-L3（D3-7）：排序后相邻相等即存在同槽（仅告警，不阻止）。 */
    private static boolean hasDuplicateSlots(int[] slots) {
        int[] sorted = slots.clone();
        Arrays.sort(sorted);
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] == sorted[i - 1]) return true;
        }
        return false;
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