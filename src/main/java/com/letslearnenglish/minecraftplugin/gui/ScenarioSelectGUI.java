package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager.DifficultyConfig;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager.ScenarioConfig;
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

/**
 * 情景选择 GUI - 选择 AI 情景对话场景 + 难度
 */
public class ScenarioSelectGUI {

    private static final int GUI_SIZE = 27;

    /** 场景按钮槽位（7 个，slots 10-16） */
    private static final int[] SCENARIO_SLOTS = { 10, 11, 12, 13, 14, 15, 16 };

    /** R-50：场景材质回退（sc.material 非法/缺失时用 PAPER，保证按钮永远可渲染） */
    private static final Material SCENARIO_FALLBACK_MATERIAL = Material.PAPER;

    private final LetsLearnEnglish plugin;

    /** 难度按钮的起始 slot */
    public static final int DIFFICULTY_SLOT_START = 18;
    /** 刷新 API 按钮 slot（管理员） */
    public static final int RELOAD_API_SLOT = 26;
    /**
     * 管理我的场景按钮 slot（Phase 3 · MC-4）。
     * 空闲位（10-16 场景 / 18-20 难度 / 22 标签 / 26 API 刷新均不占用）；
     * 可见门 = letslearnenglish.scenario.import（与个人场景功能全集一致）。
     */
    public static final int MANAGE_SLOT = 24;

    /** R-49 fan-in：场景按钮槽位起点（点击侧映射共用）。 */
    public static final int SCENARIO_SLOT_START = SCENARIO_SLOTS[0];
    /** R-49 fan-in：场景按钮槽位数 = 截断阈值（点击侧映射共用）。 */
    public static final int SCENARIO_SLOT_COUNT = SCENARIO_SLOTS.length;
    /** R-49 fan-in：难度按钮最多展示数（超出走"更多…"提示；点击侧映射共用）。 */
    public static final int DIFFICULTY_SLOT_COUNT = 3;

    public ScenarioSelectGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msg = plugin.getConfigManager().getMessageConfig(lang);

        String title = MessageUtil.getGuiMsg(msg, "dialogue.scenario-title");
        title = ChatColor.translateAlternateColorCodes('&', title);

        // R5-7：挂结构身份 holder（无 payload —— 本页只回答"是谁"，无分页/模式上下文）。
        // 与 ScenarioSelectListener 的 holder 优先判定必须同批：只改一侧会退化为标题兜底。
        Inventory gui = Bukkit.createInventory(PluginGuiHolder.of(GuiViewKey.SCENARIO_SELECT),
                GUI_SIZE, title);

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
        String backLabel = MessageUtil.getGuiMsg(msg, "dialogue.back-to-menu");
        String backLore = MessageUtil.getGuiMsg(msg, "dialogue.back-lore");
        gui.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel, backLore));

        // 场景按钮 (slots 10-16)
        String clickStart = MessageUtil.getGuiMsg(msg, "dialogue.click-start");
        String systemPrompt = MessageUtil.getGuiMsg(msg, "dialogue.system-prompt");
        // R-04：按玩家版场景配置 —— 个人场景出现在该玩家的列表里，
        // 展示名与全局同规则（display_name / display_name_zh）。
        // R-49：稳定排序 —— 按当前语言展示名排序（同名/空名时按 key 兜底），
        // 个人场景参与同一排序而非永远缀尾。
        // R-49 fan-in：排序逻辑收敛到 AIChatManager.getSortedScenarioConfigs（唯一真相源），
        // 点击侧 ScenarioSelectListener 调同一方法取 key 列表，保证"点 A 开 A"。
        List<ScenarioConfig> sortedScenarios =
                plugin.getAIChatManager().getSortedScenarioConfigs(player);
        List<ScenarioConfig> shownScenarios = sortedScenarios.size() > SCENARIO_SLOTS.length
                ? sortedScenarios.subList(0, SCENARIO_SLOTS.length - 1)
                : sortedScenarios;
        boolean hasMoreScenarios = sortedScenarios.size() > SCENARIO_SLOTS.length;
        for (int i = 0; i < shownScenarios.size(); i++) {
            ScenarioConfig sc = shownScenarios.get(i);
            int slot = SCENARIO_SLOTS[i];
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
            gui.setItem(slot, createItemLore(resolveScenarioMaterial(sc),
                    "&b&l" + sc.getDisplayName("zh".equalsIgnoreCase(lang)), lore));
        }

        // R-49：超 7 场景时尾槽放"更多…"提示（不再静默丢弃；完整分页留 Phase 3）
        if (hasMoreScenarios) {
            gui.setItem(SCENARIO_SLOTS[SCENARIO_SLOTS.length - 1], createItem(Material.BOOK,
                    MessageUtil.getGuiMsg(msg, "dialogue.more-scenarios"),
                    MessageUtil.getGuiMsg(msg, "dialogue.more-scenarios-lore")
                            .replace("{count}", String.valueOf(
                                    sortedScenarios.size() - SCENARIO_SLOTS.length + 1))));
        }

        // 难度选择按钮 (slots 18, 19, 20)
        String currentDiff = plugin.getAIChatManager().getPlayerDifficulty(player);
        int diffSlot = DIFFICULTY_SLOT_START;
        String diffSelected = MessageUtil.getGuiMsg(msg, "dialogue.diff-selected");
        String diffSelect = MessageUtil.getGuiMsg(msg, "dialogue.diff-select");
        // R-49：难度同样稳定排序（按 key）。注意难度增删同样影响点击侧 index 映射，
        // 超 DIFFICULTY_SLOT_COUNT 难度时尾槽放"更多…"提示而非第 4 个难度按钮（其余难度可经其它入口切换）。
        // R-49 fan-in：排序收敛到 AIChatManager.getSortedDifficultyConfigs，点击侧 ScenarioSelectListener 共用同序。
        List<DifficultyConfig> sortedDiffs =
                plugin.getAIChatManager().getSortedDifficultyConfigs();
        boolean hasMoreDiffs = sortedDiffs.size() > DIFFICULTY_SLOT_COUNT;
        List<DifficultyConfig> shownDiffs = hasMoreDiffs
                ? sortedDiffs.subList(0, DIFFICULTY_SLOT_COUNT - 1)
                : sortedDiffs;
        for (DifficultyConfig dc : shownDiffs) {
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

        // R-49：超 DIFFICULTY_SLOT_COUNT 难度时尾槽放"更多…"提示（不再静默丢弃；完整分页留 Phase 3）
        if (hasMoreDiffs) {
            gui.setItem(DIFFICULTY_SLOT_START + DIFFICULTY_SLOT_COUNT - 1, createItem(Material.BOOK,
                    MessageUtil.getGuiMsg(msg, "dialogue.more-difficulties"),
                    MessageUtil.getGuiMsg(msg, "dialogue.more-difficulties-lore")
                            .replace("{count}", String.valueOf(
                                    sortedDiffs.size() - (DIFFICULTY_SLOT_COUNT - 1)))));
        }

        // 难度分隔标签 (slot 22)
        String diffLabel = MessageUtil.getGuiMsg(msg, "dialogue.difficulty-label");
        String diffLore = MessageUtil.getGuiMsg(msg, "dialogue.difficulty-lore");
        gui.setItem(22, createItem(Material.NAME_TAG, diffLabel, diffLore));

        // 管理我的场景按钮 (slot 24) — Phase 3 · MC-4：与个人场景功能全集同权限门
        //（无此权限者永无个人场景，展示管理页只会困惑）。空场景玩家也可进入——
        // 页面空态提示如何导入，解决"根本不知道有这功能"的发现性问题。
        if (player.hasPermission("letslearnenglish.scenario.import")) {
            gui.setItem(MANAGE_SLOT, createItem(Material.BOOKSHELF,
                    MessageUtil.getGuiMsg(msg, "scenario.manage-button"),
                    MessageUtil.getGuiMsg(msg, "scenario.manage-button-lore")));
        }

        // 刷新 API 密钥按钮 (slot 26) — R-55m：权限与 /le load_scenario 对齐，
        // 持 letslearnenglish.scenario.import 者可见（原 letslearnenglish.admin）
        if (player.hasPermission("letslearnenglish.scenario.import")) {
            boolean apiAvailable = plugin.getAIChatManager().isAvailable();
            String apiReload = MessageUtil.getGuiMsg(msg, "dialogue.api-reload");
            String apiStatus = apiAvailable
                    ? MessageUtil.getGuiMsg(msg, "dialogue.api-configured")
                    : MessageUtil.getGuiMsg(msg, "dialogue.api-not-configured");
            gui.setItem(26, createItem(
                    apiAvailable ? Material.ENCHANTED_BOOK : Material.WRITABLE_BOOK,
                    apiReload, apiStatus));
        }

        // R-86：menu-open 不再在此显式播放 —— SoundManager.onInventoryOpen 会对所有
        // 插件 GUI 全局播一次，此处保留会双声（去重方向与理由见 SoundManager 类注释）。
        player.openInventory(gui);
    }

    /**
     * R-50：场景材质解析（渲染层纵深防御）。
     * 加载层已有 valueOf 校验，但防御性地处理 null / LEGACY / AIR：一律回退 PAPER，
     * 保证按钮永远可渲染、绝不因为材质炸界面。
     */
    private static Material resolveScenarioMaterial(ScenarioConfig sc) {
        if (sc == null || sc.material == null) {
            return SCENARIO_FALLBACK_MATERIAL;
        }
        if (sc.material.isLegacy() || sc.material.isAir()) {
            return SCENARIO_FALLBACK_MATERIAL;
        }
        return sc.material;
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