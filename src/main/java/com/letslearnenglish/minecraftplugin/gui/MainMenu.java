package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    /**
     * 与 {@link #MENU_SLOT_KEYS} 逐位对应的显示名 i18n 键（冲突报错里给玩家看"是哪两个按钮"）。
     * 刻意复用各按钮<b>正式</b>的显示名键，而不是新造一套：同一个按钮在菜单里和冲突提示里
     * 必须叫同一个名字，否则服主对着玩家截图改配置时得自己翻译一遍。
     */
    private static final String[] MENU_SLOT_MESSAGE_KEYS = {
            "main-menu.word-learning", "main-menu.dialogue-practice", "main-menu.progress-stats",
            "main-menu.square", "main-menu.daily-challenge", "main-menu.wrong-book",
            "language.button", "main-menu.duel", "main-menu.checkin"
    };

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
        //
        // R12-slot：逐键校验 + 整组回落的实现已抽到 {@link GUIUtils#readSlots}，与
        // WordLearningGUI / EnglishSquareGUI / LeaderboardGUI 三处共用同一份逻辑 ——
        // 这条守卫原本是「四处各写一遍」的形状，四份实现就是四个漂移面：改一处漏三处，
        // 表现是「主菜单改了策略，别的 GUI 还按旧策略崩」。
        ConfigManager cm = plugin.getConfigManager();
        int[] menuSlots = GUIUtils.readSlots(cm, plugin.getLogger(), "main-menu",
                MENU_SLOT_KEYS, MENU_SLOT_DEFAULTS, size);

        // Fail-fast（用户裁决，取代原「仅告警不阻止」）：同槽 = 拒绝打开该菜单。
        //
        // 为什么必须 fail-fast：渲染是「后写覆盖先写」，点击是 if-else「先命中者胜」，
        // 两者方向相反 —— 槽位 11 同时给了 word-learning(idx0) 与 duel(idx7) 时，玩家看到
        // 的是 duel 的图标，点下去执行的却是 word-learning。图标与功能错位，玩家没有任何
        // 途径自救，只有服主改配置能修。原「仅告警」把不可用伪装成可用，是最坏的一类静默失败。
        //
        // 判据用「当前 gate 下真正会渲染的项」，不是「配置里的 9 个键」：duel 与 checkin
        // 都配 12 但 duel.enabled=false 时，玩家侧只有签到可见，界面完全无歧义 —— 这种
        // 情形若也拒绝打开，等于拿「配置难看」惩罚一个没有实伤的服。被 gate 隐藏的重复
        // 槽仍会单独 WARNING 告知服主（开着模块就会炸），不静默。
        List<SlotConflict> conflicts = findRenderedSlotConflicts(menuSlots, cm);
        if (!conflicts.isEmpty()) {
            reportSlotConflict(player, messages, conflicts);
            return;
        }
        warnHiddenSlotConflicts(menuSlots, cm, conflicts);

        // R5-2：挂 holder —— 主菜单尺寸随 gui.menu-size 变化（27/36/45/54），
        // 故 MAIN_MENU 的枚举构造值为 -1（不参与尺寸判定，该值仅作文档记录，
        // R5-12 已删除零调用的 size() 访问器）；holder 只回答「是本菜单」，
        // 点击侧 MainMenuListener 再自行核对上半区 + rawSlot 范围。
        Inventory menu = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.MAIN_MENU), size, title);

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
            // R1/R11 收口：AI 对话是主打功能，却只按 ai-chat.enabled 渲染 —— API Key 未配置时
            // 按钮看起来完全可用，点进去只能靠命令侧预检补一句提示，玩家体验等同"静默失败"。
            // 现改为两态渲染：Key 就绪 = 现状（管理员配置材质优先）；Key 缺失 = 灰态
            //（GRAY_DYE + 状态 lore），让"不可用"在菜单里一眼可见。
            // 注意：模块关闭（ai-chat.enabled=false）仍是整块隐藏，不进灰态——本分支只在
            // "模块开着、但服务不可用"时生效。
            if (isAIServiceUsable()) {
                menu.setItem(menuSlots[1], createMenuItem(cm.getGuiMaterial("main-menu", "dialogue-practice", Material.PAPER), dialogueName, dialogueLore));
            } else {
                // 灰态强制 GRAY_DYE：gui-layout 的 material 是"正常态"的外观配置，若在故障态
                // 继续沿用，管理员配了 PAPER/ENCHANTED_BOOK 的服上灰态就完全不可见（同一材质
                // 无法同时表达"可用"与"坏掉"）。状态指示优先于外观配置；Key 补上后自动恢复。
                List<String> unavailableLore = new ArrayList<>(2);
                unavailableLore.add(dialogueLore);
                // 复用既有 dialogue.api-not-configured（"API 状态: 未配置"）—— 短句、无 {prefix}，
                // 本就是给物品 lore/状态行用的文案，可直接嵌入而不污染界面。
                unavailableLore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "dialogue.api-not-configured")));
                menu.setItem(menuSlots[1], createMenuItem(Material.GRAY_DYE, dialogueName, unavailableLore));
            }
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

    /**
     * 找出「当前模块门下真正会渲染」的菜单项之间的全部同槽冲突。
     *
     * <p><b>为什么判据是「会渲染的项」而不是「配置里的 9 个键」</b>：本方法要防的实伤是
     * 「玩家看到的图标和点出来的功能不是同一个」。这个实伤只在两个项<b>都真的画上去了</b>
     * 时才成立 —— 渲染是后写覆盖先写，点击是 if-else 先命中者胜，两者方向相反，同槽即错位。
     * 若某一项被模块门挡住（例如 {@code duel.enabled=false}），它根本没画上去，也就谈不上
     * 错位。用配置级判据会把这种「配置难看但零实伤」的服一并打成开不了界面，属于过度拒绝。
     *
     * <p>被门挡住的那部分重复不改判据，但仍由 {@link #warnHiddenSlotConflicts} 告警明示 ——
     * 服主今天开模块，明天界面就废，这个预警必须留下。
     *
     * @param slots 已通过越界校验的 9 个槽位（下标与 {@link #MENU_SLOT_KEYS} 对齐）
     * @param cm    配置管理器（模块门的唯一来源，与渲染/点击同源）
     * @return 全部冲突（按首项下标升序，至多 4 对）；无冲突返回空列表
     */
    private static List<SlotConflict> findRenderedSlotConflicts(int[] slots, ConfigManager cm) {
        List<SlotConflict> conflicts = new ArrayList<>(2);
        for (int i = 0; i < slots.length; i++) {
            if (!isRendered(i, cm)) continue;
            for (int j = i + 1; j < slots.length; j++) {
                if (!isRendered(j, cm)) continue;
                if (slots[i] == slots[j]) conflicts.add(new SlotConflict(slots[i], i, j));
            }
        }
        return conflicts;
    }

    /**
     * 某个菜单项在当前模块门下是否真的会渲染进菜单（可见性判据的<b>唯一</b>来源）。
     *
     * <p>必须与 {@link #open(Player)} 里的 9 段渲染分支逐位一致。之所以抽成一格表而不是
     * 复用渲染分支：渲染分支是「读配置 + 造 ItemStack + setItem」，纯 JVM 下造不了 ItemStack
     * （无 RegistryAccess），无法在单测里当判据用；这张表只读布尔门，可被直接钉住。
     *
     * <p>注意 {@code dialogue-practice}（下标 1）：API Key 缺失时画成 GRAY_DYE 灰态，
     * <b>仍然占槽渲染</b>，所以只要 {@code ai-chat.enabled=true} 就算可见 —— 灰态是
     * 「模块开着但服务不可用」，与本判据无关。
     */
    private static boolean isRendered(int index, ConfigManager cm) {
        return switch (index) {
            // 0 word-learning / 5 wrong-book：同归 word-system 门（错题本是单词学习从属面）
            case 0, 5 -> cm.isWordSystemEnabled();
            case 1 -> cm.isAIChatEnabled();
            case 4 -> cm.isDailyChallengesEnabled();
            case 7 -> cm.isDuelEnabled();
            case 8 -> cm.isCheckinEnabled();
            // 2 personal-profile / 3 english-square / 6 language-toggle：不设门，恒可见
            default -> true;
        };
    }

    /**
     * 拒绝打开并把冲突说清楚：服主看 SEVERE 日志（含完整配置路径），玩家看聊天提示。
     *
     * <p><b>为什么不静默</b>：静默等于把「服务器配坏了」伪装成「按钮坏了」，玩家会一直点、
     * 一直以为是自己网络问题。<b>为什么不抛异常</b>：19 个调用点分布在命令与监听器里，
     * 抛出去就是一条英文堆栈，且部分调用点在 {@code runTaskLater} 里（异常只会进控制台，
     * 玩家侧彻底静默）。所以这里是「明确拒绝 + 双侧可见」。
     *
     * <p>日志每次打开都打，不做去重：与本文件既有的 {@code gui.menu-size} / 槽位越界告警
     * 同款处理（配置错误本就该吵），且去重会让「第二次起就查不到原因」。
     */
    private void reportSlotConflict(Player player, FileConfiguration messages,
                                    List<SlotConflict> conflicts) {
        StringBuilder log = new StringBuilder();
        log.append("Main menu refused: ").append(conflicts.size())
                .append(" duplicate slot(s) in gui-layout.main-menu. ")
                .append("Rendering is last-write-wins but click handling is first-match-wins, ")
                .append("so the icon shown and the action executed would differ.");
        for (SlotConflict c : conflicts) {
            log.append("\n  slot ").append(c.slot()).append(": '")
                    .append(MENU_SLOT_KEYS[c.firstIndex()]).append("' (")
                    .append(slotConfigPath(c.firstIndex())).append(") and '")
                    .append(MENU_SLOT_KEYS[c.secondIndex()]).append("' (")
                    .append(slotConfigPath(c.secondIndex()))
                    .append(") both resolve to it");
        }
        log.append("\n  Fix gui-layout.main-menu.<item>.slot in config.yml, then re-open the menu.");
        plugin.getLogger().severe(log.toString());

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                guiMsgOrFallback(messages, "gui.main-menu-slot-conflict-header",
                        "&cThe main menu cannot be opened: its configuration has a slot conflict.")));
        for (SlotConflict c : conflicts) {
            String template = guiMsgOrFallback(messages, "gui.main-menu-slot-conflict-line",
                    "  &cslot {slot}: &e{first} &cand &e{second}");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    fillConflictPlaceholders(template, c, messages)));
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                guiMsgOrFallback(messages, "gui.main-menu-slot-conflict-footer",
                        "&7Ask the server admin to fix &fgui-layout.main-menu.*.slot &7in config.yml.")));
    }

    /**
     * 被模块门挡住的重复槽：当前无害，但一开模块就会变成实伤，必须提前告警。
     *
     * <p>已经作为 {@code conflict} 报过的组合不再重复告警（那边玩家控制台已经吵过了）。
     */
    private void warnHiddenSlotConflicts(int[] slots, ConfigManager cm, List<SlotConflict> conflict) {
        List<String> hidden = new ArrayList<>(0);
        for (int i = 0; i < slots.length; i++) {
            for (int j = i + 1; j < slots.length; j++) {
                if (slots[i] != slots[j]) continue;
                boolean bothRendered = isRendered(i, cm) && isRendered(j, cm);
                final int firstIndex = i;
                final int secondIndex = j;
                final int slot = slots[i];
                boolean alreadyReported = conflict.stream()
                        .anyMatch(c -> c.slot() == slot
                                && c.firstIndex() == firstIndex && c.secondIndex() == secondIndex);
                if (bothRendered || alreadyReported) continue;
                hidden.add("slot " + slot + ": '" + MENU_SLOT_KEYS[firstIndex] + "' and '"
                        + MENU_SLOT_KEYS[secondIndex] + "' (harmless only while one of them stays hidden "
                        + "by its module switch)");
            }
        }
        if (hidden.isEmpty()) return;
        plugin.getLogger().warning("Duplicate slot(s) in gui-layout.main-menu that are currently "
                + "hidden by a module switch, but will break the menu once enabled:\n  "
                + String.join("\n  ", hidden));
    }

    /** 冲突行的 {@code {slot}} / {@code {first}} / {@code {second}} 替换（按玩家语言取显示名）。 */
    private static String fillConflictPlaceholders(String template, SlotConflict conflict,
                                                   FileConfiguration messages) {
        return template
                .replace("{slot}", String.valueOf(conflict.slot()))
                .replace("{first}", ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',
                        guiMsgOrFallback(messages, MENU_SLOT_MESSAGE_KEYS[conflict.firstIndex()],
                                MENU_SLOT_KEYS[conflict.firstIndex()]))))
                .replace("{second}", ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',
                        guiMsgOrFallback(messages, MENU_SLOT_MESSAGE_KEYS[conflict.secondIndex()],
                                MENU_SLOT_KEYS[conflict.secondIndex()]))));
    }

    /**
     * 读一条 GUI 文案；<b>键缺失时回落到内置英文</b>，而不是让玩家看到 {@code [?key]}。
     *
     * <p>为什么不直接用 {@link MessageUtil#getGuiMsg}：冲突提示是本菜单唯一的「开不了」出口，
     * 它必须在语言文件还没补上新键时<b>依然说人话</b>。{@code [?gui.main-menu-slot-conflict-line]}
     * 对玩家等于零信息，而这一刻恰恰是玩家最需要信息的时候。键补齐后自动切到本地化文案。
     */
    private static String guiMsgOrFallback(FileConfiguration messages, String key, String fallback) {
        String value = MessageUtil.getGuiMsg(messages, key);
        return value.startsWith("[?") ? fallback : value;
    }

    /** 冲突项对应的配置路径片段，给服主直接照着改。 */
    private static String slotConfigPath(int index) {
        return "gui-layout.main-menu." + MENU_SLOT_KEYS[index] + ".slot";
    }

    /** 一次主菜单槽位冲突：冲突的槽位号 + 两个互斥菜单项（下标指向 {@link #MENU_SLOT_KEYS}）。 */
    private record SlotConflict(int slot, int firstIndex, int secondIndex) { }

    /**
     * AI 服务是否真的可用（渲染侧灰态判定的唯一来源）。
     *
     * <p>与点击侧 {@code MainMenuListener} 同判 {@code AIChatManager#isAvailable()}
     * （apiKey 非空）。管理器为 null 时一律视为不可用：主菜单只在玩家在线时打开，
     * 正常流程下 onEnable 早已完成、管理器必然就绪；判 null 仅防"热重载/异常初始化
     * 次序"等边角路径抛 NPE，代价只是把按钮画成灰态，不会误伤正常路径。
     */
    private boolean isAIServiceUsable() {
        var aiChatManager = plugin.getAIChatManager();
        return aiChatManager != null && aiChatManager.isAvailable();
    }

    private ItemStack createMenuItem(Material material, String name, String lore) {
        return createMenuItem(material, name, Collections.singletonList(lore));
    }

    /**
     * 多行 lore 版本（R1/R11 灰态追加状态行用）。行序即显示序；空白行按原样保留，
     * 便于以后需要间隔行时直接传 ""。null 列表按空 lore 处理（不写入 lore）。
     */
    private ItemStack createMenuItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (lore != null && !lore.isEmpty()) {
                List<String> coloredLore = new ArrayList<>(lore.size());
                for (String line : lore) {
                    coloredLore.add(ChatColor.translateAlternateColorCodes('&', line == null ? "" : line));
                }
                meta.setLore(coloredLore);
            } else {
                meta.setLore(Collections.emptyList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}