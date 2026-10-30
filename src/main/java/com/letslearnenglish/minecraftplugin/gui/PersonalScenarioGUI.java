package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager.ScenarioConfig;
import com.letslearnenglish.minecraftplugin.core.ai.PersonalScenarioStore;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 个人场景管理 GUI（Phase 3 · MC-4）— 列出 / 行内二次确认删除玩家自己导入的个人场景。
 *
 * <p>布局（54 格）：
 * <pre>
 *   slot 0-53      先铺灰色玻璃板占位（防拖拽；其余物品随后覆盖）
 *   slot 4         场景计数（scenario.manage-count："个人场景: {current}/{max}"）
 *   slot 8         返回场景选择页（pushBack 框架，Listener 用 popBack 弹回）
 *   条目槽         见 {@link #ENTRY_SLOTS}（WrongBook 式 42 槽：9-44 + 45/46/48/50/52/53）
 *   slot 22        空态物（scenario.manage-empty + -lore；仅列表为空时渲染）
 * </pre>
 *
 * <p>条目点击映射不依赖槽位下标：场景 key 存在条目物品的
 * {@link org.bukkit.persistence.PersistentDataContainer}（DuelChallengeGUI 同款模式），
 * 与渲染顺序/列表变动解耦，绝无「点 A 删 B」。
 * 删除为行内二次确认（确认态名字/lore 变红，见 {@code scenario.manage-confirm}），
 * per-player 确认态由 {@link com.letslearnenglish.minecraftplugin.listener.PersonalScenarioListener} 持有。
 *
 * <p>单页恒够：个人场景上限 30 &lt; 42 槽。**若上限提升需加翻页**。
 * （空态物 slot 22 虽在 ENTRY_SLOTS 内，但空态时无条目渲染，无冲突面。）
 */
public class PersonalScenarioGUI {

    /** 界面尺寸（{@link #open} 建 inventory 用；holder 判定不依赖尺寸）。 */
    public static final int GUI_SIZE = 54;

    /** 场景计数物槽位（写死 = 唯一真相，监听器引用本常量）。 */
    public static final int COUNT_SLOT = 4;

    /** 返回场景选择页槽位（写死 = 唯一真相，监听器引用本常量）。 */
    public static final int BACK_SLOT = 8;

    /** 空态物槽位（仅列表为空时渲染；监听器对无 PDC 键的物品走复位分支）。 */
    public static final int EMPTY_SLOT = 22;

    /**
     * 场景条目占用的槽位（显式列出，方便一眼看懂）。
     * 第二行到第五行 = 9-44；第六行取 45/46/48/50/52/53，
     * 已避开 BACK_SLOT(8)，与 WrongBookGUI.WORD_SLOTS 同布局。
     * 必须保持升序（与渲染顺序一致，便于人工核对）。
     */
    public static final int[] ENTRY_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44,
            45, 46, 48, 50, 52, 53
    };

    /** 条目物品上 PDC key 的名字（存个人场景 key，Listener 反查删除目标）。 */
    private static final String ENTRY_KEY_PDC = "personal_scenario_key";

    /** 确认删除态的 PDC 标记键（屏幕与点击判定同源：红字即带此标记）。 */
    private static final String CONFIRM_KEY_PDC = "personal_scenario_confirm";

    /** R-50：场景材质回退（sc.material 非法/缺失时用 PAPER，保证条目永远可渲染）。 */
    private static final Material SCENARIO_FALLBACK_MATERIAL = Material.PAPER;

    private final LetsLearnEnglish plugin;

    public PersonalScenarioGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * 打开管理页。
     *
     * @param player     目标玩家
     * @param confirmKey 处于「确认删除」态的个人场景 key（该条目渲染为红字）；null = 无确认态。
     *                   由 Listener 行内二次确认流程传入。
     */
    public void open(Player player, String confirmKey) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msg = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(msg, "scenario.manage-title"));

        // R5-7：挂结构身份 holder（NoPayload）。确认删除态刻意「不」进 payload ——
        // 它已由条目物品的 PDC 标记承载（confirmKey，"屏幕即真相"），
        // 再搬进 holder 会产生两份确认态真相、且每次重渲染都要重建 holder。
        Inventory gui = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.PERSONAL_SCENARIO), GUI_SIZE, title);

        // 背景：先铺灰色玻璃板，防止玩家把物品拖进空槽（其余物品随后覆盖）
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }
        for (int i = 0; i < GUI_SIZE; i++) {
            gui.setItem(i, filler.clone());
        }

        // 场景计数（slot 4）：实时读该玩家个人场景总数与全局上限
        int currentCount = plugin.getAIChatManager()
                .getPersonalScenarios(player.getUniqueId()).size();
        String countText = MessageUtil.getGuiMsg(msg, "scenario.manage-count")
                .replace("{current}", String.valueOf(currentCount))
                .replace("{max}", String.valueOf(plugin.getAIChatManager().getMaxPersonalScenarios()));
        gui.setItem(COUNT_SLOT, createItem(Material.BOOK, countText));

        // 返回按钮（slot 8）：Listener 用 popBack 弹回「场景选择页」帧（pushBack 框架）。
        // 复用既有 gui.back-button/-lore —— 与 AchievementWallGUI 等 SocialListener
        // 子页（栈弹返回）同款惯例，不新增键。
        gui.setItem(BACK_SLOT, createItem(Material.RED_STAINED_GLASS_PANE,
                MessageUtil.getGuiMsg(msg, "gui.back-button"),
                Arrays.asList(MessageUtil.getGuiMsg(msg, "gui.back-lore"))));

        // 条目 / 空态
        List<ScenarioConfig> entries = personalEntries(plugin, player);
        if (entries.isEmpty()) {
            gui.setItem(EMPTY_SLOT, createItem(Material.BOOK,
                    MessageUtil.getGuiMsg(msg, "scenario.manage-empty"),
                    Arrays.asList(MessageUtil.getGuiMsg(msg, "scenario.manage-empty-lore"))));
        } else {
            String systemPromptLabel = MessageUtil.getGuiMsg(msg, "dialogue.system-prompt");
            String clickHint = MessageUtil.getGuiMsg(msg, "scenario.manage-click-hint");
            String confirmHint = MessageUtil.getGuiMsg(msg, "scenario.manage-confirm");
            boolean zh = "zh".equalsIgnoreCase(lang);
            // 上限 30 < 42，单页恒够；min 仅防御外部手工编辑的越界（若上限提升需加翻页）
            int shown = Math.min(entries.size(), ENTRY_SLOTS.length);
            for (int i = 0; i < shown; i++) {
                ScenarioConfig sc = entries.get(i);
                boolean confirm = sc.key != null && sc.key.equals(confirmKey);

                List<String> lore = new ArrayList<>();
                // prompt 摘要（ScenarioSelectGUI 同款 45 字符截断多行，悬停可见）
                if (sc.prompt != null && !sc.prompt.isEmpty()) {
                    lore.add(systemPromptLabel);
                    for (String line : sc.prompt.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            lore.add("&7  " + (trimmed.length() > 45
                                    ? trimmed.substring(0, 45) + "..." : trimmed));
                        }
                    }
                    lore.add("");
                }
                // 操作提示：确认态红字（scenario.manage-confirm），否则普通提示
                lore.add(confirm ? confirmHint : clickHint);
                String name = (confirm ? "&c&l" : "&b&l") + sc.getDisplayName(zh);
                gui.setItem(ENTRY_SLOTS[i],
                        createEntryItem(resolveScenarioMaterial(sc), name, lore, sc.key, confirm));
            }
        }

        player.openInventory(gui);
    }

    /** 条目物品的 PDC key（PersonalScenarioListener 反查删除目标用）。 */
    public static NamespacedKey entryKey(LetsLearnEnglish plugin) {
        return new NamespacedKey(plugin, ENTRY_KEY_PDC);
    }

    /**
     * 确认删除态的 PDC 标记键 —— 行内二确的"屏幕即真相"实现：
     * 首点条目 → 重渲染该条目并写入本标记（名字/lore 变红）；再点同一（带标记的）条目 →
     * 执行删除；ESC 重开/点其它条目 = 渲染无标记物品 → 天然回落到"首点"。
     * 因此 Listener 无 per-player 确认态、无需 quit 清理（DuelChallengeGUI 无状态模式）。
     */
    public static NamespacedKey confirmKey(LetsLearnEnglish plugin) {
        return new NamespacedKey(plugin, CONFIRM_KEY_PDC);
    }

    /**
     * 本页条目列表（渲染与点击侧共用的唯一真相源）：取
     * {@link com.letslearnenglish.minecraftplugin.core.ai.AIChatManager#getSortedScenarioConfigs}
     * 的稳定排序（R-49 fan-in），过滤出个人场景（personal: 前缀）。
     */
    public static List<ScenarioConfig> personalEntries(LetsLearnEnglish plugin, Player player) {
        List<ScenarioConfig> result = new ArrayList<>();
        for (ScenarioConfig sc : plugin.getAIChatManager().getSortedScenarioConfigs(player)) {
            if (PersonalScenarioStore.isPersonalKey(sc.key)) {
                result.add(sc);
            }
        }
        return result;
    }

    /**
     * R-50：场景材质解析（渲染层纵深防御）—— null / LEGACY / AIR 一律回退 PAPER，
     * 保证条目永远可渲染、绝不因为材质炸界面（ScenarioSelectGUI 同款）。
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

    private ItemStack createItem(Material material, String name) {
        return createItem(material, name, null);
    }

    /** name 与 lore 行均在此统一 translate（& 码 → §），调用点传原始配置文案。 */
    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (lore != null) {
                List<String> translated = new ArrayList<>(lore.size());
                for (String line : lore) {
                    translated.add(ChatColor.translateAlternateColorCodes('&', line));
                }
                meta.setLore(translated);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 条目物品：与 createItem 相同，另把场景 key（+确认态标记）写入 PDC，解耦槽位序。 */
    private ItemStack createEntryItem(Material material, String name, List<String> lore,
                                      String scenarioKey, boolean confirmed) {
        ItemStack item = createItem(material, name, lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null && scenarioKey != null) {
            meta.getPersistentDataContainer().set(entryKey(plugin),
                    PersistentDataType.STRING, scenarioKey);
            if (confirmed) {
                meta.getPersistentDataContainer().set(confirmKey(plugin),
                        PersistentDataType.STRING, "1");
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
