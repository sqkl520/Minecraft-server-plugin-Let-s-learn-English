package com.letslearnenglish.minecraftplugin.gui.identity;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.InventoryView;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI 身份解析（R5-0）——「这个界面是谁开的」的唯一入口。
 *
 * <p>为什么要有这一层：迁移前 {@code GUIUtils.isPluginGUI(String)}、各 listener 的
 * {@code title.contains(...)} 以及 {@code *GUI.isXxxTitle(...)} 把「身份」和「本地化
 * 标题文案」绑死。文案可被管理员改、可随语言切换、含占位符与动态后缀（页码、模式名、
 * 赛季剩余时间、成就计数、班级名、32 字符截断），任何一处变化都会让判定静默失效。
 * R5-0 引入 {@link PluginGuiHolder} 作为结构身份，本类负责三种解析：
 * <ol>
 *   <li>{@link #resolve(InventoryView)} —— holder 身份（唯一可信来源，O(1)）</li>
 *   <li>{@link #resolveByTitle(String)} —— 标题兜底（迁移期兼容旧 GUI 与旧事件，
 *       逐条翻译自 {@code GUIUtils.isPluginGUI} 的 39 条 {@code title.contains} +
 *       1 条二确页委托，按原出现顺序编号共 40 条判定）</li>
 *   <li>{@link #resolve(InventoryView, String)} —— 先 holder 后标题</li>
 * </ol>
 *
 * <p><b>依赖约束</b>：本包只依赖 Bukkit API + 插件本体（为了读二确页标题配置），
 * 故意<b>不</b> import {@code gui.GUIUtils} 或任何 {@code gui.*} GUI 类 —— 否则
 * GUIUtils（依赖本类）与本类形成环。二确页的等值判定在本类内按同一口径重写，
 * 来源标注见 {@link #matchesConfirmTitle(String)}。
 *
 * <p>线程模型：全部方法无共享可变状态（除一个只增不减的 WARNING 去重集合），
 * 可在事件线程任意调用。{@link #logMismatch} 只应主线程调用（读 inventory view）。
 */
public final class GuiIdentity {

    /**
     * 影子比对的 WARNING 去重集合（key = viewKey|titleKey|title）。
     *
     * <p>为什么无条件 WARNING 而不是挂在 {@code general.debug-gui-identity} 开关后面：
     * 迁移期最怕「判定悄悄换了一套、没人发现」；一条去重后的 WARNING 不会刷屏，
     * 而且能在生产环境直接抓到文案/映射漂移。上限 {@value #MAX_DEDUPE_ENTRIES} 条，
     * 超限清空（宁可重复告警，也不让集合无限增长）。
     */
    private static final Set<String> LOGGED_MISMATCHES = ConcurrentHashMap.newKeySet();

    /** 去重集合上限。 */
    private static final int MAX_DEDUPE_ENTRIES = 256;

    /**
     * 标题无法区分的 key 对（白名单）：这些 key 的标题逐字相同或互为子串，
     * 「holder 说 A、标题说 B」属于预期内的信息缺失，不是漂移，不告警。
     *
     * <ul>
     *   <li>{@link GuiViewKey#SQUARE} / {@link GuiViewKey#SQUARE_HIDDEN}：27 格笑话页
     *       与 54 格主页共用 {@code square.title}（{@code {page}} 都填 "1/1"），
     *       只有尺寸/holder 能区分。</li>
     * </ul>
     */
    private static boolean isTitleIndistinguishable(GuiViewKey a, GuiViewKey b) {
        if (a == null || b == null) return false;
        return (a == GuiViewKey.SQUARE && b == GuiViewKey.SQUARE_HIDDEN)
                || (a == GuiViewKey.SQUARE_HIDDEN && b == GuiViewKey.SQUARE);
    }

    private GuiIdentity() {
        // Utility class — cannot be instantiated
    }

    /**
     * 从 {@link InventoryView} 的 <b>top inventory</b> 的 holder 解析身份。
     *
     * <p>为什么读 top 而不是 {@code getClickedInventory()}：Bukkit 语义已核验（Paper
     * 1.21.4）——{@code InventoryEvent.getInventory()} 恒为 top inventory；
     * {@code getClickedInventory()} 只在点到界面外时为 null，点玩家背包时返回的是
     * 玩家背包（其 holder 是 {@code Player}，不是 {@link PluginGuiHolder}）。
     * 所以这里必须用 {@code getTopInventory()}，且必须用 {@code instanceof}
     * 而不是 {@code getHolder() != null}（玩家背包/原版容器都有非 null holder）。
     *
     * @param view 事件/玩家当前界面，可为 null
     * @return holder 的身份键；非插件 GUI、view 为 null、holder 非本插件 holder 时返回 null
     */
    public static GuiViewKey resolve(InventoryView view) {
        PluginGuiHolder holder = holderOf(view);
        return holder == null ? null : holder.getKey();
    }

    /**
     * 读取 top inventory 上的 {@link PluginGuiHolder}。
     *
     * @param view 可为 null
     * @return holder；非本插件 GUI 或 view 为 null 时返回 null
     */
    private static PluginGuiHolder holderOf(InventoryView view) {
        if (view == null) return null;
        // getTopInventory() 在 API 里是 @NotNull，但 mock/异常 view 可能返回 null，防御一下
        if (view.getTopInventory() == null) return null;
        Object holder = view.getTopInventory().getHolder();
        // 必须 instanceof：玩家背包的 holder 是 Player，原版容器有自己的 holder，都非 null
        return (holder instanceof PluginGuiHolder pluginHolder) ? pluginHolder : null;
    }

    /**
     * 读取 top inventory holder 上的 payload。
     *
     * @param view 可为 null
     * @return holder payload；无 holder 时返回 {@link PluginGuiHolder.GuiPayload.NoPayload#INSTANCE}
     */
    public static PluginGuiHolder.GuiPayload payloadOf(InventoryView view) {
        PluginGuiHolder holder = holderOf(view);
        return holder == null
                ? PluginGuiHolder.GuiPayload.NoPayload.INSTANCE
                : holder.getPayload();
    }

    /**
     * 先 holder 后标题的解析（Wave 2 各流切换判定时用这一个）。
     *
     * <p>holder 命中即以 holder 为准（结构身份压倒文案）；holder 未命中才回落标题。
     * 这一步正是「holder 优先、白名单兜底」的落地形态。
     *
     * @param view  当前界面，可为 null
     * @param title 标题；null 时尝试读 {@code view.getTitle()}
     * @return 身份键；都解析不出时返回 null
     */
    public static GuiViewKey resolve(InventoryView view, String title) {
        GuiViewKey byHolder = resolve(view);
        if (byHolder != null) return byHolder;
        String effectiveTitle = title;
        if (effectiveTitle == null && view != null) {
            effectiveTitle = view.getTitle();
        }
        return resolveByTitle(effectiveTitle);
    }

    /**
     * 标题 → 身份键的兜底映射。
     *
     * <p>本方法是 {@code GUIUtils.isPluginGUI(String)}（迁移期白名单）的逐条翻译，
     * 顺序与该白名单完全一致，便于 1:1 diff。判定语义严格保持 {@code contains}
     * （不做等值、不做前缀锚定、不加新关键词）——任何收紧或放宽都会改变
     * 「哪些界面会被插件认领」，属于行为变更，不在 R5-0 范围内。
     *
     * <p>已知无法映射/有歧义的标题见各分支注释，汇总：
     * <ul>
     *   <li>英语广场 27 格笑话页 → 标题与主页逐字相同，只能返回 {@link GuiViewKey#SQUARE}；
     *       要区分 {@link GuiViewKey#SQUARE_HIDDEN} 必须看 holder 或尺寸。</li>
     *   <li>对决页标题被截断到 32 可见字符（{@code DuelGUI.truncateTitleSafe}）——
     *       若配置标题较长，"Word Duel"/"单词对决" 可能被截掉 → 本方法返回 null。
     *       迁移期对决判定不得只靠标题兜底。</li>
     *   <li>二确页（{@link GuiViewKey#CONFIRMATION}）原白名单不是 contains 而是
     *       等值比对配置，本方法按同一口径重写；插件未初始化（纯单测环境）时返回 null。</li>
     * </ul>
     *
     * @param title 已做 {@code &} → {@code §} 转换的界面标题，可为 null
     * @return 命中的身份键；无法判定时返回 null
     */
    public static GuiViewKey resolveByTitle(String title) {
        if (title == null || title.isEmpty()) return null;

        // ── 以下 40 条与 GUIUtils.isPluginGUI(String) 的顺序 1:1 对应 ──
        // 1/2 主菜单品牌词：gui.main-menu-title =「一起学英语！」/「Let's Learn English!」
        //     注意：这是最宽的一条，任何未来标题只要含品牌词都会落到 MAIN_MENU；
        //     当前没有任何其它 GUI 标题含品牌词，故保持原顺序（作者不希望改变命中优先级）。
        if (title.contains("Let's Learn English")) return GuiViewKey.MAIN_MENU;
        if (title.contains("一起学英语")) return GuiViewKey.MAIN_MENU;
        // 3 二确页：原白名单委托 ConfirmationGUI.isConfirmGUI（配置等值，非 contains）
        if (matchesConfirmTitle(title)) return GuiViewKey.CONFIRMATION;
        // 4/5 word-learning-gui.title =「单词学习 - {difficulty}」
        if (title.contains("Word Learning")) return GuiViewKey.WORD_LEARNING;
        if (title.contains("单词学习")) return GuiViewKey.WORD_LEARNING;
        // 6/7 square.title（54 格主页 + 27 格笑话页同标题）→ 只能返回 SQUARE
        if (title.contains("English Square")) return GuiViewKey.SQUARE;
        if (title.contains("英语广场")) return GuiViewKey.SQUARE;
        // 8/9 leaderboard.title =「排行榜 / Leaderboard」+ tab 名 + 赛季行
        if (title.contains("Leaderboard")) return GuiViewKey.LEADERBOARD;
        if (title.contains("排行榜")) return GuiViewKey.LEADERBOARD;
        // 10/11/12 profile.title =「{player}的主页」/ profile.viewing-title =「{name}的个人主页」
        if (title.contains("'s Profile")) return GuiViewKey.PROFILE;
        if (title.contains("的主页")) return GuiViewKey.PROFILE;
        if (title.contains("个人主页")) return GuiViewKey.PROFILE;
        // 13/14 profile.settings-title =「隐私设置 / Privacy Settings」
        if (title.contains("Privacy Settings")) return GuiViewKey.PROFILE_SETTINGS;
        if (title.contains("隐私设置")) return GuiViewKey.PROFILE_SETTINGS;
        // 15/16 achievement-wall.title =「成就墙 / Achievement Wall」+ " (n/m)"
        if (title.contains("Achievement Wall")) return GuiViewKey.ACHIEVEMENT_WALL;
        if (title.contains("成就墙")) return GuiViewKey.ACHIEVEMENT_WALL;
        // 17/18 word-bank.title =「选择词库 / Select Word Bank」
        if (title.contains("Select Word Bank")) return GuiViewKey.WORD_BANK;
        if (title.contains("选择词库")) return GuiViewKey.WORD_BANK;
        // 19/20 dialogue.scenario-title =「场景选择 / Scenario Select」
        if (title.contains("Scenario Select")) return GuiViewKey.SCENARIO_SELECT;
        if (title.contains("场景选择")) return GuiViewKey.SCENARIO_SELECT;
        // 21/22 scenario.manage-title =「我的个人场景 / My Personal Scenarios」
        //     刻意不含"场景选择"/"Scenario Select"子串，与场景选择页互不命中
        if (title.contains("My Personal Scenarios")) return GuiViewKey.PERSONAL_SCENARIO;
        if (title.contains("我的个人场景")) return GuiViewKey.PERSONAL_SCENARIO;
        // 23/24 calendar.title =「过往学习 / Study History」；calendar.viewing-title 为前缀 + " - {player}"
        if (title.contains("过往学习")) return GuiViewKey.LEARNING_CALENDAR;
        if (title.contains("Study History")) return GuiViewKey.LEARNING_CALENDAR;
        // 25/26 daily.title =「每日挑战 / Daily Challenges」（注意英文是复数）
        if (title.contains("Daily Challenge")) return GuiViewKey.DAILY_CHALLENGE;
        if (title.contains("每日挑战")) return GuiViewKey.DAILY_CHALLENGE;
        // 27/28 daily-challenge.complete-title =「挑战完成！/ Challenge Complete!」
        if (title.contains("Challenge Complete")) return GuiViewKey.CHALLENGE_COMPLETE;
        if (title.contains("挑战完成")) return GuiViewKey.CHALLENGE_COMPLETE;
        // 29/30 chat-history.title =「AI 对话历史 / AI Chat History」
        if (title.contains("Chat History")) return GuiViewKey.CHAT_HISTORY_LIST;
        if (title.contains("对话历史")) return GuiViewKey.CHAT_HISTORY_LIST;
        // 31/32 chat-history.session-title =「对话详情 / Conversation Details」
        if (title.contains("Conversation Details")) return GuiViewKey.CHAT_HISTORY_SESSION;
        if (title.contains("对话详情")) return GuiViewKey.CHAT_HISTORY_SESSION;
        // 33/34 duel.gui-title =「单词对决 / Word Duel」（打开时截断到 32 可见字符）
        if (title.contains("Word Duel")) return GuiViewKey.DUEL;
        if (title.contains("单词对决")) return GuiViewKey.DUEL;
        // 35/36 duel.challenge-title =「选择对决对手 / Pick a Duel Opponent」
        //     刻意不含"单词对决"子串（见 DuelChallengeGUI 标题避碰红线）
        if (title.contains("Pick a Duel Opponent")) return GuiViewKey.DUEL_CHALLENGE;
        if (title.contains("选择对决对手")) return GuiViewKey.DUEL_CHALLENGE;
        // 37/38 wrong-book.title-page =「错题本 | 第 {page} 页 / Wrong Book | Page {page}」
        if (title.contains("Wrong Book")) return GuiViewKey.WRONG_BOOK;
        if (title.contains("错题本")) return GuiViewKey.WRONG_BOOK;
        // 39/40 class.board-title =「班级榜 - {class} / Class Board - {class}」
        if (title.contains("班级榜")) return GuiViewKey.CLASS_BOARD;
        if (title.contains("Class Board")) return GuiViewKey.CLASS_BOARD;

        return null;
    }

    /**
     * 二确页标题判定 —— 与原白名单里的 {@code ConfirmationGUI.isConfirmGUI(String)}
     * <b>同源同口径</b>（后者 R5-0 期间保留不动）：按 zh/en 两份配置读
     * {@code language.confirm-title}，经 {@code &} → {@code §} 转换后做<b>等值</b>比对。
     *
     * <p>为什么不直接调用 {@code ConfirmationGUI.isConfirmGUI}：本包刻意不 import
     * {@code gui.*}（见类 Javadoc 的依赖约束）。若后续口径变更，两处必须同步修改
     * （Wave 2 收尾删除 {@code isPluginGUI(String)} 时可把该实现迁到这里，成为唯一真相源）。
     *
     * @param title 已 translate 的标题
     * @return 是否为二确页标题
     */
    private static boolean matchesConfirmTitle(String title) {
        if (title == null || title.isEmpty()) return false;
        LetsLearnEnglish plugin = LetsLearnEnglish.getInstance();
        if (plugin == null || plugin.getConfigManager() == null) {
            // 纯逻辑单测 / 插件未初始化：无法读配置，按「不认领」处理（与原实现一致）
            return false;
        }
        ConfigManager cm = plugin.getConfigManager();
        for (String lang : new String[] { "zh", "en" }) {
            FileConfiguration messages = cm.getMessageConfig(lang);
            String expected = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "language.confirm-title"));
            if (title.equals(expected)) return true;
        }
        return false;
    }

    /**
     * 影子比对：holder 身份与标题身份不一致时打一条去重 WARNING。
     *
     * <p>用途：Wave 2 各流把 listener/音效的判定从标题换成 holder 时，在切换点调用本方法，
     * 就能在不改变行为（仍用旧判定）的前提下，把「holder 与标题说法不一致」的界面
     * 全部暴露出来 —— 迁移完成后再删掉调用即可。
     *
     * <p>告警条件：holder key 与 title key 不同（含一方为 null）。已登记为标题不可区分的
     * key 对（{@link #isTitleIndistinguishable}）不告警。同一
     * {@code viewKey|titleKey|title} 只告警一次（上限 {@value #MAX_DEDUPE_ENTRIES} 条，
     * 超限清空重来）。
     *
     * <p>必须主线程调用（读 inventory view / 标题）。
     *
     * @param plugin 插件实例（取 logger 用），可为 null（null 时静默返回）
     * @param view   当前界面，可为 null（null 时静默返回）
     */
    public static void logMismatch(LetsLearnEnglish plugin, InventoryView view) {
        if (plugin == null || view == null) return;

        GuiViewKey viewKey = resolve(view);
        String title = view.getTitle();
        GuiViewKey titleKey = resolveByTitle(title);
        if (viewKey == titleKey) return;                      // 两边一致（含都为 null）
        if (isTitleIndistinguishable(viewKey, titleKey)) return; // 预期内的信息缺失

        // 控制台不渲染颜色码，日志里把颜色控制符还原成 & 便于对照配置原文（语义不变）
        String readableTitle = title == null ? "<null>" : title.replace(ChatColor.COLOR_CHAR, '&');
        String dedupeKey = viewKey + "|" + titleKey + "|" + readableTitle;
        if (LOGGED_MISMATCHES.size() >= MAX_DEDUPE_ENTRIES) {
            LOGGED_MISMATCHES.clear();
        }
        if (!LOGGED_MISMATCHES.add(dedupeKey)) return;

        plugin.getLogger().warning("[GuiIdentity] holder/title mismatch: holderKey=" + viewKey
                + ", titleKey=" + titleKey + ", title='" + readableTitle + "'"
                + " — 若 holder 正确，请检查 GuiIdentity.resolveByTitle 的映射是否需同步；"
                + "若标题正确，请检查该 GUI 是否已挂 PluginGuiHolder。");
    }
}
