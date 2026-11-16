package com.letslearnenglish.minecraftplugin.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitScheduler;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.RankingMode;
import com.letslearnenglish.minecraftplugin.core.social.FeedTextFormatter;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;

/**
 * English Square GUI with ranking modes — paginated list of players.
 *
 * Supports 5 modes:
 * - WEEKLY: this week's top players by coin
 * - MONTHLY: this month's top players by coin
 * - ALL_TIME_COINS: all-time top players by total score
 * - ALL_PLAYERS: all active players (original English Square behavior)
 * - FEED: 学习动态流（第 5 模式，Wave 2 域 4）——SocialManager FeedCache 快照渲染
 *
 * Players who set "show_in_square = false" get the invisibility joke screen.
 */
public class EnglishSquareGUI {

    private static final int GUI_SIZE = 54;

    /**
     * R12-slot：笑话页（{@link #openHidden}）的格数。
     *
     * <p><b>为什么必须是常量而不是别的什么</b>：笑话页与主页<b>共用</b>同一批
     * {@code gui-layout.english-square.*.slot} 配置键（尤其是 {@code back-button}），
     * 但尺寸是 27 而不是 54。落在 {@code [27, 53]} 的槽位对主页完全合法（例如把返回键
     * 挪到右上角 44），对笑话页必然越界 —— 且笑话页是「隐身玩家看主页时的替代页」，
     * 普通玩家和服主都不容易发现它崩了，属于典型的静默半失效。
     *
     * <p>因此渲染侧的边界判据一律用<b>当前页面的尺寸</b>（主页传 {@link #GUI_SIZE}、
     * 笑话页传本常量），绝不用常量 54 一把梭。
     */
    private static final int HIDDEN_GUI_SIZE = 27;

    /**
     * R12-slot：主页用到的 9 个 {@code gui-layout.english-square.*.slot} 键与默认值。
     *
     * <p>策略与 {@code MainMenu} / {@code WordLearningGUI} 一致：<b>任一越界即整组回落</b>
     * （{@link GUIUtils#readSlots}）。不用「逐键回落」的理由与主菜单那条相同 ——
     * 逐键回落会让两个配错/配对的键落到同一格，而渲染是「后写覆盖先写」、点击是
     * if-else「先命中者胜」（{@code SocialListener}:357-365 的模式按钮分派正是这个形状），
     * 于是玩家看到 A 点下去执行 B。整组回落用的是出厂那套两两不同的槽位表，天然无此问题。
     */
    private static final String[] HOME_SLOT_KEYS = {
            "back-button", "prev-page", "next-page", "empty-icon",
            "mode-weekly", "mode-monthly", "mode-alltime", "mode-all", "mode-feed"
    };
    /** 与 {@link #HOME_SLOT_KEYS} 逐位对应的出厂默认槽位（与 config.yml 一致）。 */
    private static final int[] HOME_SLOT_DEFAULTS = {8, 47, 51, 22, 2, 3, 4, 5, 6};
    /** {@link #HOME_SLOT_KEYS} 中 {@code back-button} 的下标。 */
    private static final int IDX_BACK = 0;
    /** {@link #HOME_SLOT_KEYS} 中 {@code prev-page} 的下标。 */
    private static final int IDX_PREV = 1;
    /** {@link #HOME_SLOT_KEYS} 中 {@code next-page} 的下标。 */
    private static final int IDX_NEXT = 2;
    /** {@link #HOME_SLOT_KEYS} 中 {@code empty-icon} 的下标。 */
    private static final int IDX_EMPTY = 3;
    /** {@link #HOME_SLOT_KEYS} 中 {@code mode-weekly} 的下标。 */
    private static final int IDX_MODE_WEEKLY = 4;
    /** {@link #HOME_SLOT_KEYS} 中 {@code mode-monthly} 的下标。 */
    private static final int IDX_MODE_MONTHLY = 5;
    /** {@link #HOME_SLOT_KEYS} 中 {@code mode-alltime} 的下标。 */
    private static final int IDX_MODE_ALLTIME = 6;
    /** {@link #HOME_SLOT_KEYS} 中 {@code mode-all} 的下标。 */
    private static final int IDX_MODE_ALL = 7;
    /** {@link #HOME_SLOT_KEYS} 中 {@code mode-feed} 的下标。 */
    private static final int IDX_MODE_FEED = 8;

    /**
     * R12-slot：把主页 9 个配置槽位一次读出并逐个校验（越界 → 回落该键默认值 + WARNING）。
     *
     * <p>一次性读出而不是各方法各读一遍：{@code /lea reload} 若正好落在
     * 「addModeButton 读一次、下一页按钮再读一次」之间，同一个键会在同一次渲染里
     * 读到两个值，界面自相矛盾。本方法在 {@code open()} 开头调用一次，之后全部复用。
     *
     * @param cm     配置管理器
     * @param logger 日志出口，可为 null（纯 JVM 单测）
     * @return 长度 9 的槽位表，与 {@link #HOME_SLOT_KEYS} 逐位对应
     */
    private static int[] resolveHomeSlots(ConfigManager cm, java.util.logging.Logger logger) {
        return GUIUtils.readSlots(cm, logger, "english-square",
                HOME_SLOT_KEYS, HOME_SLOT_DEFAULTS, GUI_SIZE);
    }

    /**
     * R13：主页 9 个配置槽位里有没有两个按钮落在同一格。
     *
     * <p><b>为什么这与「越界」是两件事</b>：越界（配成 99）会让整组回落默认值，
     * 界面仍然自洽；而<b>同槽</b>（两个键都配成 10）不越界，两组都会「正常」渲染 ——
     * 后写的覆盖先写的，于是玩家看到的是后者的图标，点下去触发的却是前者
     * （点击侧按槽位分支，先命中者胜）。
     *
     * <p>判据用<b>裁决后</b>的 {@code layout}（不是原始配置）：越界已被
     * {@link GUIUtils#readSlots} 整组打回默认，那一组默认值两两不同，不会误报。
     * 也就是说本方法只在「配置合法但互相撞车」时返回 true。
     *
     * @param layout 裁决后的槽位表
     * @param keys   与之逐位对应的键名（用于日志/定位）
     * @return 存在同槽时 true
     */
    private static boolean hasSlotConflict(int[] layout, String[] keys) {
        java.util.Map<Integer, String> seen = new java.util.HashMap<>();
        for (int i = 0; i < layout.length; i++) {
            String previous = seen.putIfAbsent(layout[i], keys[i]);
            if (previous != null) {
                // 只记一次（每个冲突格一条），调用方负责给玩家一句可操作的话。
                return true;
            }
        }
        return false;
    }

    private static final int[] PLAYER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int PLAYERS_PER_PAGE = PLAYER_SLOTS.length;

    /**
     * R19-GUI2：open 在途去重 token（照 {@code ChatHistoryGUI.OPEN_SEQ} 同式）。
     *
     * <p>必须 static：翻页/切模式每次都 {@code new EnglishSquareGUI(plugin)}，实例级令牌
     * 拦不住「先点的第 2 页比后点的第 3 页晚回来」。连点时后发起者胜，旧请求在回主线程
     * render 前作废。条目量级 = 历史玩家数条 Long，每次 open 覆写自身，惰性收口。
     */
    private static final AtomicLong OPEN_SEQ = new AtomicLong();
    private static final Map<UUID, Long> OPEN_SEQ_BY_PLAYER = new ConcurrentHashMap<>();

    private final LetsLearnEnglish plugin;
    /**
     * 本次渲染的玩家 id 快照 —— <b>与屏幕内容逐槽同源同序</b>（SocialListener 点击侧
     * 只按下标反查，不做任何二次查库/重排）。
     *
     * <p><b>P-SQ-PAGE：非 FEED 模式这里是"当前页切片"，不是全量列表</b>
     *（下标 0 = 本页第一个头像，最多 {@link #PLAYERS_PER_PAGE} 个）。旧实现存全量 id，
     * 于是广场首页要把全库聚合结果整片搬进内存（万人库实测 1.3 s 主线程冻结）；
     * 现在行数由 SQL 的 LIMIT/OFFSET 限定，页数由
     * {@link PlayerDataStore#countActiveSquareEntries()} 单独给出。
     * 代价：点击侧的反算公式从 {@code (page-1)*28 + slotIdx} 退化为 {@code slotIdx}
     *（页偏移已在取数时消费掉，**不得**再加一次，否则第 2 页永远点不中）。
     * FEED 模式不走本列表（见 {@link #currentFeedItems}），保持全量快照。
     */
    private List<UUID> currentPlayerIds = new ArrayList<>();
    private RankingMode currentMode = RankingMode.ALL_PLAYERS;
    /**
     * R19-GUI2：<b>「本实例还没有渲染过任何一页」</b>的页码哨兵。
     *
     * <p>为什么需要它：本类在 {@code open()} 返回后被 {@code SocialListener} 立刻登记为
     * {@code squareGuis} 快照，而点击侧复用快照的判据是
     * {@code myGui.getCurrentPage() == page}（见 {@code SocialListener}
     * {@code handleEnglishSquareGUI} 的「陈旧快照」注释）。异步化之后，登记那一刻数据还没回来 ——
     * 若 {@code currentPage}
     * 沿用默认值 {@code 1}，那么"打开第 1 页"的陈旧窗口会<b>恰好蒙对</b>判据，
     * 点击侧就会拿一个<b>空列表</b>去反查下标 → 头像点击全部静默失效（越界 return）。
     * 更糟的是"打开第 2 页"时它蒙不对，于是行为随页码漂移，最难排查。
     *
     * <p>{@code 0} 与任何真实页码（clamp 后恒 ≥1）都不相等，于是未渲染的实例<b>必然</b>
     * 落到点击侧的「按屏幕页码从 DB 重建本页」分支（既有代码，只查 28 行，正确）。
     * 这就是本批异步化不引入「点 A 开 B」的全部依据。
     */
    private static final int PAGE_UNRENDERED = 0;
    /**
     * 本次渲染<b>实际生效</b>的页码（clamp 之后的，不是调用方传入的原始值）。
     *
     * <p>P-SQ-PAGE：全量模式的 {@link #currentPlayerIds} 是本页切片，点击侧按下标反查
     * 时只有"快照页码 == 屏幕页码"才能保证"点 A 作用于 A"。payload 里的页码是屏幕事实，
     * 本字段是快照事实，二者相等是切片映射成立的前提（见 SocialListener 的 pageSlice 判定）。
     * 所有登记快照的打开路径都在同一主线程顺序里 open() 后立刻 put 快照，故正常情况下
     * 恒相等；本字段让"不等"可被显式检出，而不是静默错位。
     *
     * <p>R19-GUI2：初值改为 {@link #PAGE_UNRENDERED}（0）而不是 1 —— 异步化后
     * 「已登记但尚未渲染」是一个真实存在的状态，必须与「已渲染第 1 页」区分开。
     */
    private int currentPage = PAGE_UNRENDERED;
    /**
     * Wave 2 域 4：本次渲染的 FEED 快照（仅 FEED 模式非空；immutable）。
     *
     * <p>与 {@link #currentPlayerIds} 同为"渲染即快照、点击按快照反查"的契约，但
     * <b>粒度不同</b>：FEED 保持<b>全量</b>（≤200 条 FeedCache 切片，监听器按
     * {@code (page-1)*28 + slotIdx} 反查）；P-SQ-PAGE 后的玩家列表则是<b>本页切片</b>
     *（监听器按 slotIdx 反查）。别把两者混用 —— 三类列表共用同一套 28 槽布局。
     */
    private List<SocialManager.FeedItem> currentFeedItems = Collections.emptyList();

    public EnglishSquareGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public List<UUID> getCurrentPlayerIds() {
        return currentPlayerIds;
    }

    public RankingMode getCurrentMode() {
        return currentMode;
    }

    /** P-SQ-PAGE：本次渲染实际生效的页码（clamp 后；见字段说明）。 */
    public int getCurrentPage() {
        return currentPage;
    }

    /** Wave 2 域 4：FEED 模式的渲染快照（非 FEED 模式为空列表）。 */
    public List<SocialManager.FeedItem> getCurrentFeedItems() {
        return currentFeedItems;
    }

    public void open(Player viewer, int page) {
        open(viewer, page, null);
    }

    /**
     * 广场打开（R19-GUI2：非 FEED 模式已两段式异步化）。
     *
     * <p><b>为什么 FEED 仍然同步</b>：FEED 的行来自 {@code SocialManager} 的
     * {@code volatile} FeedCache 快照（≤200 条内存切片），打开时<b>零查库</b> ——
     * 把它也塞进异步只会让「点动态」多一帧延迟，却不省任何 IO，反而把
     * {@code InventoryOpenEvent} 冻结渲染列表的时序拉长。
     *
     * <p><b>主线程段只做三件事</b>：模式/门的解析（纯配置读）、槽位裁决（纯配置读）、
     * 建界面并渲染。所有行数据（COUNT / 本页行 / 榜单行 / 自己统计 / 自己金币）在
     * 异步段取（见 {@link #loadSquareData}）。
     *
     * <p><b>快照失效（关键）</b>：{@code SocialListener} 在本方法返回后<b>立刻</b>把本实例
     * 登记为 {@code squareGuis} 快照，并在点击侧按 {@code getCurrentMode()} /
     * {@code getCurrentPage()} / {@code getCurrentPlayerIds()} 反查下标。异步化后这些字段
     * 在登记那一刻必然还是空的 —— 于是本方法开头 {@link #invalidateSnapshot()} 把
     * {@code currentPage} 置成 {@link #PAGE_UNRENDERED}（0），点击侧的
     * 「{@code getCurrentPage() == page}」判据必然不成立 → 自动走它自己的「按屏幕页码
     * 从 DB 重建本页」分支（那是既有代码，正确且只查 28 行）。<b>绝不允许</b>让陈旧组合
     * 蒙对：{@code 0} 与任何真实页码（≥1）都不相等，这是本轮不引入「点 A 开 B」的全部依据。
     */
    public void open(Player viewer, int page, RankingMode mode) {
        ConfigManager cm = plugin.getConfigManager();
        String lang = plugin.getPlayerLanguage(viewer);
        FileConfiguration messages = cm.getMessageConfig(lang);
        // Wave 2：mode == null → 按配置默认落点（/le square 无参路径与主菜单广场按钮）。
        if (mode == null) {
            mode = resolveDefaultMode();
        }
        SocialManager socialManager = plugin.getSocialManager();
        boolean socialEnabled = socialManager != null && socialManager.isEnabled();
        // MC-2（S-H）：榜单门（progress-system.enabled && leaderboard.enabled）关闭时，
        // 防御性回落"广场"全量模式 —— 挡掉旧界面翻页/返回栈残留的排名模式重开路径
        //（点击侧 SocialListener 已拒绝切换，但翻页分支仍会携带 currentMode）。
        // "广场"全量模式不是榜单，不受此门影响；Wave 2 的 FEED 同样不是榜单
        //（数据走 SocialManager FeedCache），也不吃本门。
        boolean leaderboardEnabled = cm.isLeaderboardEnabled();
        if (!leaderboardEnabled && mode != RankingMode.ALL_PLAYERS && mode != RankingMode.FEED) {
            mode = RankingMode.ALL_PLAYERS;
        }
        // Wave 2 域 4（plan-social §2.5 四处接线之一）：social 门关闭时动态页不可达，
        // 防御性回落"广场"全量模式（挡旧界面翻页/返回栈残留的 FEED 重开路径）。
        if (mode == RankingMode.FEED && !socialEnabled) {
            mode = RankingMode.ALL_PLAYERS;
        }

        // R12-slot：槽位在打开时一次读出并做边界校验（越界 → 整组回落 + WARNING），
        // 之后本次渲染全部 setItem 都用这一份。判据是主页尺寸 GUI_SIZE(54)，不是笑话页的 27。
        int[] layout = resolveHomeSlots(cm, plugin.getLogger());
        // R13：同槽冲突 fail-fast —— 与主菜单同口径（那一处用户已裁定为「拒绝加载」）。
        //
        // 为什么这里也必须 fail-fast 而不是只记警告：渲染侧把第 i 个按钮画到 layout[i]，
        // 后者来自「越界整组回落」后的裁决值；而点击侧 SocialListener 直接读<b>原始配置</b>
        // （见 :331 的 back-button）。两者一旦分叉，就会出现主菜单那种
        // 「看到的图标和点出来的功能不是同一个」—— 而且这里更隐蔽，只在一组槽位配错时发生。
        // 放在异步派发之前：这是纯配置判定，没有任何理由让玩家先等一次 IO 再被拒绝。
        if (hasSlotConflict(layout, HOME_SLOT_KEYS)) {
            plugin.getLogger().severe("English-square main page has a SLOT CONFLICT in"
                    + " gui-layout.english-square.*.slot — two or more buttons resolved to the"
                    + " same slot. The page is refused because the icon a player sees would not"
                    + " be the button they trigger (render is last-write-wins, click is"
                    + " first-match-wins). Fix the slots in config.yml.");
            viewer.sendMessage(plugin.getMessageUtil().getPlayerMessage(viewer,
                    "gui.english-square-slot-conflict"));
            return;
        }

        // 快照先作废：见方法 javadoc 的「快照失效」。必须在任何分支之前（含 FEED）。
        // currentMode 例外：它由纯配置判定得出（零 IO），在主线程就能定稿，故照旧立刻赋值
        // —— 点击侧读到的模式与屏幕一致；安全性仍由 PAGE_UNRENDERED 那道页码判据兜住。
        this.currentMode = mode;
        invalidateSnapshot();

        if (mode == RankingMode.FEED) {
            // FEED：零查库（Volatile 内存切片），保持同步打开 —— 见方法 javadoc。
            List<SocialManager.FeedItem> feed = socialManager != null
                    ? socialManager.getFeedSnapshot()
                    : Collections.emptyList();
            if (feed == null) {
                feed = Collections.emptyList();
            }
            int totalPages = pageCountOf(feed.size());
            int safePage = clampPage(page, totalPages);
            renderSquare(viewer, mode, new SquareData(safePage, totalPages,
                            Collections.emptyList(), feed, null, null, null),
                    layout, leaderboardEnabled, socialEnabled, messages);
            return;
        }

        // 异步段要用的主线程事实一次取好（异步线程不碰 Bukkit / ConfigManager）。
        final int displayCount = cm.getMainConfig()
                .getInt("progress-system.leaderboard.display-count", 10);
        final UUID viewerId = viewer.getUniqueId();
        final String langForLog = lang;
        // mode / page 在方法上半段被重新赋值过（默认落点、模块门回落、参数清理），
        // 不是 effectively final —— lambda 只能捕获 final 局部量，故在此定稿。
        final RankingMode effectiveMode = mode;
        final int requestedPage = page;

        // R19-GUI2：分页取行口。
        //
        // <b>为什么生产路径的实现在下面的异步 lambda 里、而不是抽成私有方法</b>：
        // {@code EnglishSquarePagingContractTest#guiDoesNotRequestUnboundedRows} 用
        // methodBody() 把「取行必须带 (page-1)*每页容量 的 offset」这条契约<b>锚定在
        // open() 的方法体里</b>，专门防有人把 LIMIT/OFFSET 改回 Integer.MAX_VALUE
        // （那是 1.3 s 主线程冻结的成因）。抽到别的方法会让那条断言找不到调用而失效 ——
        // 它保护的恰恰是本轮最容易悄悄退化的东西。
        //
        // 匿名类的形参可以合法遮蔽外层同名参数，于是异步 lambda 里的 `page` 就是
        // 「已 clamp 的页码」，offset 的算术形状与契约逐字一致。
        //
        // E1 token：连点翻页/切模式时后发起者胜 —— 旧请求回主线程 render 前比对失败即作废，
        // 否则「先点的第 2 页」可能比「后点的第 3 页」晚落地，把屏幕拽回上一页。
        final long token = OPEN_SEQ.incrementAndGet();
        OPEN_SEQ_BY_PLAYER.put(viewerId, token);
        // R20-GUI2 守卫三：玩家在异步窗口内「关界面但仍在线」时必须放弃本次渲染。
        // 与 token/在线复检并列的第三种独立失效方式：数据回来时无脑 openInventory 会让
        // 界面自己弹回来（玩家 ESC 后去点别的，屏幕却被盖回去）。判据必须是
        // <b>holder 引用相等</b> —— 本仓库硬纪律：身份只认 holder，绝不比标题
        //（标题带本地化文案 + "{page}/{total}" 后缀，改文案/切语言即静默失效）。
        // 照 {@code AchievementWallGUI#stillOnRequestedScreen} 同式。
        final PluginGuiHolder arrivalHolder = pluginGuiHolderOf(viewer);

        BukkitScheduler scheduler = Bukkit.getScheduler();
        if (scheduler == null) {
            // 无调度器环境（纯 JVM 单测夹具里 mockStatic(Bukkit) 默认返回 null）：退回
            // 同步取数 + 渲染，与修复前逐行等价。生产环境 Bukkit.getScheduler() 永不为
            // null，故该分支不参与线上路径 —— 它存在的唯一理由是「已经能跑通的既有夹具
            // 不该因为线程模型改造而被判红」。本分支**不查 plugin.isEnabled()**：夹具里的
            // mock 插件默认 isEnabled=false，而在无调度器窗口里"插件已停用"并不成立。
            //
            // 抽成独立方法（{@link #openSyncFallback}）而不是内联：这样 open() 的主线程段
            // 只剩调度逻辑 + 纯配置准备，源码 lint 才能对「把取数搬回主线程」精确变红。
            openSyncFallback(viewer, effectiveMode, requestedPage, displayCount, langForLog,
                    layout, leaderboardEnabled, socialEnabled, messages);
            return;
        }
        try {
            scheduler.runTaskAsynchronously(plugin, () -> {
                if (!plugin.isEnabled()) return;
                // 取数整段在异步线程：COUNT / 本页 28 行 / 榜单行 / 自己统计 / 自己金币。
                SquareData data = loadSquareData(viewerId, requestedPage, effectiveMode,
                        displayCount, langForLog, new PageFetch() {
                            @Override
                            public List<LeaderboardEntry> fetch(int page) {
                                int offset = (page - 1) * PLAYERS_PER_PAGE;
                                return plugin.getPlayerDataStore()
                                        .getActiveSquareEntries(PLAYERS_PER_PAGE, offset);
                            }
                        });
                if (data == null) {
                    // R11-M3：失败必有反馈 —— 异步段不得直调 Bukkit API，切主线程发提示。
                    runOnMain(scheduler, () -> {
                        if (!viewer.isOnline()) return;
                        if (OPEN_SEQ_BY_PLAYER.getOrDefault(viewerId, -1L) != token) return;
                        notifyLoadFailed(viewer);
                    });
                    return;
                }
                if (!plugin.isEnabled()) return;
                runOnMain(scheduler, () -> {
                    if (!viewer.isOnline()) return;
                    // E1 token：已有更新的打开请求（翻页/切模式/返回），本次作废。
                    if (OPEN_SEQ_BY_PLAYER.getOrDefault(viewerId, -1L) != token) return;
                    // 守卫三：玩家已关掉界面（仍在线）→ 不把界面强行弹回来。
                    // 放弃渲染同时意味着快照保持「未渲染」（PAGE_UNRENDERED），点击侧
                    // 因此不会复用空白快照，而是按屏幕页码从 DB 重建本页（既有分支）。
                    if (!stillOnArrivalScreen(viewer, arrivalHolder)) return;
                    renderSquare(viewer, effectiveMode, data, layout, leaderboardEnabled,
                            socialEnabled, messages);
                });
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次打开（玩家也已不可能看到界面）。
        }
    }

    /**
     * 广场全量模式的「取一页行」实现（{@code (page-1)*每页容量} 的 OFFSET）。
     *
     * <p><b>只被无调度器的夹具分支使用</b>（见 {@link #openSyncFallback} 的调用点）。
     * 生产路径用的是 {@code open(...)} 异步 lambda 里的同名匿名实现 —— 两份实现共用
     * {@link #PLAYERS_PER_PAGE} 与同一个算术，语义逐字等价。为什么不合一：查询语句必须
     * 留在 {@code open()} 方法体内（分页契约 lint 锚定此处），而夹具分支不能出现在
     * 主线程查库的判定里；把语句放异步 lambda、此处只留方法引用，两个约束同时成立。
     *
     * @param page 已 clamp 到 [1, totalPages] 的页码
     * @return 本页行（数据层失败时为空列表）
     */
    private List<LeaderboardEntry> fetchSquarePage(int page) {
        int offset = (page - 1) * PLAYERS_PER_PAGE;
        List<LeaderboardEntry> rows =
                plugin.getPlayerDataStore().getActiveSquareEntries(PLAYERS_PER_PAGE, offset);
        return rows != null ? rows : Collections.emptyList();
    }

    /**
     * 无调度器环境的同步兜底（<b>仅测试夹具路径</b>，生产不可达 —— {@code Bukkit.getScheduler()}
     * 在活服务器上永不为 null）。
     *
     * <p>抽成独立方法而不是内联在 {@link #open(Player, int, RankingMode)} 里：{@code open()}
     * 的主线程段必须只剩「纯配置准备 + 调度」，任何"顺手在这里查一次库"的写法都要能被
     * 源码 lint 静态抓住。本方法的存在本身就是那条声明 —— 同步取数只允许发生在这里，
     * 而这里只在无调度器时被调用。取行口用方法引用（{@link #fetchSquarePage}）与生产路径
     * 共用同一算术。
     */
    private void openSyncFallback(Player viewer, RankingMode mode, int page, int displayCount,
                                  String lang, int[] layout, boolean leaderboardEnabled,
                                  boolean socialEnabled, FileConfiguration messages) {
        SquareData data = loadSquareData(viewer.getUniqueId(), page, mode,
                displayCount, lang, this::fetchSquarePage);
        if (data == null) {
            notifyLoadFailed(viewer);
            return;
        }
        renderSquare(viewer, mode, data, layout, leaderboardEnabled, socialEnabled, messages);
    }

    /**
     * R19-GUI2：一页广场行的取数口。
     *
     * <p>存在的理由见 {@code open(...)} 里的实例化注释：查询语句必须留在 {@code open()}
     * 的方法体内（分页契约 lint 锚定此处），而执行必须发生在异步线程。用接口把「形状」
     * 与「时机」解耦之后，两者同时成立，且 {@link #loadSquareData} 只依赖这个窄接口，
     * 不依赖任何 Bukkit 类型。
     */
    @FunctionalInterface
    private interface PageFetch {
        /**
         * @param page 已 clamp 到 [1, totalPages] 的页码
         * @return 本页行（数据层失败时为空列表）
         */
        List<LeaderboardEntry> fetch(int page);
    }

    /**
     * R19-GUI2：作废本实例的渲染快照（{@link #PAGE_UNRENDERED} = 「尚未渲染」）。
     *
     * <p>只在主线程调用（{@link #open(Player, int, RankingMode)} 开头）。
     */
    private void invalidateSnapshot() {
        currentPlayerIds = new ArrayList<>();
        currentFeedItems = Collections.emptyList();
        currentPage = PAGE_UNRENDERED;
    }

    /** 页码 clamp：下界 1、上界 totalPages（totalPages 恒 ≥1，见 {@link #pageCountOf}）。 */
    private static int clampPage(int page, int totalPages) {
        if (page < 1) return 1;
        return Math.min(page, totalPages);
    }

    /**
     * R19-GUI2：异步段取数（<b>仅数据层 + logger，零 Bukkit/配置读</b>）。
     *
     * <p>三条查询按模式分裂（与修复前的同步实现逐条对应，只换线程）：
     * <ul>
     *   <li>ALL_PLAYERS：{@code countActiveSquareEntries}（算页数）→ clamp 页码 →
     *       {@code getActiveSquareEntries(PLAYERS_PER_PAGE, (page-1)*28)}（本页切片）→
     *       自己那一行的统计（本页命中则零额外查库，未命中才回落 {@code getPlayerStats}）；</li>
     *   <li>排名模式：{@code getTopPlayers} + 自己头像的 {@code getCoinsEarned}。</li>
     * </ul>
     *
     * @param viewerId    打开者 UUID（只用于「自己头像」统计与日志）
     * @param page        调用方请求的页码（未 clamp；clamp 在下方按真实页数做）
     * @param mode        已走完全部落的模式（{@code null} 已被上游消解）
     * @param displayCount 榜单模式的取行上限（主线程预读的配置，异步线程不碰 ConfigManager）
     * @param lang        打开者语言（仅日志用；渲染读 messages，故不在此处使用）
     * @param pageFetch   本页取行口（由 {@code open} 提供给，语句形状由分页契约钉住）
     * @return 取好的数据；取数失败（异常）时 {@code null}（调用方发提示、不开界面）
     */
    private SquareData loadSquareData(UUID viewerId, int page, RankingMode mode, int displayCount,
                                      String lang, PageFetch pageFetch) {
        try {
            PlayerDataStore store = plugin.getPlayerDataStore();
            List<LeaderboardEntry> rows;
            int totalPages;
            if (mode == RankingMode.ALL_PLAYERS) {
                // P-SQ-PAGE：先 COUNT 拿页数、clamp 页码，再按 (page-1)*28 只取一页 ——
                // 旧实现传 Integer.MAX_VALUE 把全库聚合行整片搬进内存，万人库实测
                // 1331.6 ms ≈ 26.6 tick 的主线程冻结（点一下广场全服卡 1.3 s）。
                int total = store.countActiveSquareEntries();
                totalPages = pageCountOf(total);
                int safePage = clampPage(page, totalPages);
                List<LeaderboardEntry> pageRows = pageFetch.fetch(safePage);
                rows = pageRows != null ? pageRows : Collections.emptyList();

                // F3（R13-M3）：自己头像统计优先读聚合行（零额外查库）；聚合行不含自己
                //（隐身/未答过题/不在本页）时回落 getPlayerStats（与旧实现逐字一致）。
                Integer ownCorrect = null;
                Integer ownIncorrect = null;
                LeaderboardEntry own = findEntry(rows, viewerId);
                if (own != null) {
                    ownCorrect = own.correct;
                    ownIncorrect = own.incorrect;
                } else {
                    try {
                        PlayerDataStore.PlayerStats ownStats = store.getPlayerStats(viewerId);
                        if (ownStats != null) {
                            ownCorrect = ownStats.getTotalCorrect();
                            ownIncorrect = ownStats.getTotalIncorrect();
                        }
                    } catch (Exception e) {
                        // 与旧实现最坏值一致（读失败时统计本来就是 0），但不阻断整页渲染。
                        logWarning("Failed to preload English-square own stats for " + viewerId, e);
                    }
                }
                return new SquareData(safePage, totalPages, rows, Collections.emptyList(),
                        ownCorrect, ownIncorrect, null);
            }

            // 排名模式（周/月/总）
            List<LeaderboardEntry> ranked =
                    plugin.getProgressManager().getLeaderboard().getTopPlayers(mode, displayCount);
            rows = ranked != null ? ranked : Collections.emptyList();
            totalPages = pageCountOf(rows.size());
            int safePage = clampPage(page, totalPages);
            Long coins = null;
            try {
                coins = plugin.getDatabaseManager().getCoinsEarned(viewerId);
            } catch (Exception e) {
                logWarning("Failed to preload English-square coins for " + viewerId, e);
            }
            return new SquareData(safePage, totalPages, rows, Collections.emptyList(),
                    null, null, coins);
        } catch (Exception e) {
            logWarning("Failed to preload English-square GUI for " + viewerId
                    + " (mode=" + mode + ")", e);
            return null;
        }
    }

    /**
     * R19-GUI2：一次异步取数的结果快照。
     *
     * <p>字段为 {@code null} 表示「这一路没取到」（渲染侧按旧实现的最坏值兜底：
     * 统计 0 / 金币 0），{@code rows} 与 {@code feed} 恒非 null。
     *
     * @param page       已 clamp 到 [1, totalPages] 的页码（屏幕事实）
     * @param totalPages 总页数（与页码、取行 offset 同源）
     * @param rows       本页/本榜的行（ALL_PLAYERS 是本页切片，排名模式是全量有界榜）
     * @param feed       FEED 模式的内存快照（非 FEED 恒空）
     * @param ownCorrect 自己头像的正确数（仅 ALL_PLAYERS；null = 没取到）
     * @param ownIncorrect 自己头像的错误数（仅 ALL_PLAYERS；null = 没取到）
     * @param ownCoins   自己头像的金币（仅排名模式；null = 没取到）
     */
    private record SquareData(int page, int totalPages,
                              List<LeaderboardEntry> rows,
                              List<SocialManager.FeedItem> feed,
                              Integer ownCorrect, Integer ownIncorrect, Long ownCoins) {
    }

    /**
     * R19-GUI2：主线程渲染 —— 数据由 {@link #loadSquareData} 预取，本方法<b>零 DB 调用</b>
     * （{@code getCheeredFeedIds} 除外，见 {@code renderFeedEntries} 的说明：它只对
     * 每页 ≤28 个 id 做一次有界查询，plan-social §3.2 已明确接受，属本批范围外）。
     *
     * <p>渲染段与修复前逐行等价；唯一新增的写入是开头三个快照字段（{@code currentMode} /
     * {@code currentPage} / {@code currentPlayerIds} / {@code currentFeedItems}）——
     * 它们本来就在 {@code open()} 里赋值，现在只是把「赋值时刻」挪到数据真正就绪之后，
     * 于是「快照 == 屏幕」这条契约仍然成立。
     */
    private void renderSquare(Player viewer, RankingMode mode, SquareData data, int[] layout,
                              boolean leaderboardEnabled, boolean socialEnabled,
                              FileConfiguration messages) {
        ConfigManager cm = plugin.getConfigManager();
        String lang = plugin.getPlayerLanguage(viewer);
        PlayerDataStore store = plugin.getPlayerDataStore();
        int page = data.page();
        int totalPages = data.totalPages();
        List<LeaderboardEntry> rankedEntries = data.rows();

        // ---- 快照登记（与屏幕同刻、同源、同序）----
        this.currentMode = mode;
        this.currentPage = page;
        this.currentFeedItems = data.feed();
        if (mode == RankingMode.FEED) {
            this.currentPlayerIds = new ArrayList<>();
        } else {
            // 排名模式：快照 = 全量有界榜；ALL_PLAYERS：快照 = 本页切片。两者都等于 rows。
            List<UUID> ids = new ArrayList<>(rankedEntries.size());
            for (LeaderboardEntry e : rankedEntries) {
                ids.add(e.playerId);
            }
            this.currentPlayerIds = ids;
        }

        // R5-4：标题只做展示（仍保留「英语广场 / English Square」与模式后缀，玩家可读）；
        // 点击侧的身份与状态（页码/模式）已改由下方 holder + payload 提供，不再依赖文案。
        String baseTitle = MessageUtil.getGuiMsg(messages, "square.title");
        String modeLabel = switch (mode) {
            case WEEKLY -> MessageUtil.getGuiMsg(messages, "leaderboard.mode-weekly");
            case MONTHLY -> MessageUtil.getGuiMsg(messages, "leaderboard.mode-monthly");
            case ALL_TIME_COINS -> MessageUtil.getGuiMsg(messages, "leaderboard.mode-alltime");
            case FEED -> MessageUtil.getGuiMsg(messages, "square.feed.mode-label");
            default -> "";
        };
        // 标题页码：旧实现有 "两列表都空 → 1/1" 的特判。分页后它已冗余 ——
        // totalPages 由同一数据源算出（空数据 ⇒ 1 页），page 又已 clamp 到 [1, totalPages]，
        // 空数据自然得到 "1/1"，无需（也不能）再用"本页行数"判空：本页为空不代表没有下一页。
        String title = ChatColor.translateAlternateColorCodes('&',
                (modeLabel.isEmpty() ? baseTitle : baseTitle + " | " + modeLabel)
                        .replace("{page}", page + "/" + totalPages));
        // R5-4：挂结构身份 holder —— key 区分 54 格主页（SQUARE）；payload 携带
        // 「页码 + 模式」两件点击侧必须知道的事实。标题时代的做法是二者都从标题反解
        // （页码靠抓标题里第一个整数，模式名/玩家名里出现数字就会抓错；模式靠逐条
        // 子串匹配模式标签文案，管理员改文案即静默失效）——R5-11/14 已把标题层整体
        // 删除，payload 是唯一来源。
        // page 已在异步段 clamp 到 [1, totalPages]，SquarePage 的构造校验不会抛。
        Inventory inv = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.SQUARE,
                        new PluginGuiHolder.GuiPayload.SquarePage(page, modeKeyOf(mode))),
                GUI_SIZE, title);

        // ---- Mode toggle buttons (slots 2-5) ----
        // MC-2（S-H）：leaderboard.enabled=false 时周/月/总三榜按钮回落灰色玻璃占位
        //（沿用主菜单"模块关闭=玻璃占位"的既有视觉语言），"广场"全量模式保留（它不是榜单）。
        // 点击侧 SocialListener 对三榜槽位的分支拒绝并发 module.disabled。
        if (leaderboardEnabled) {
            addModeButton(inv, cm, messages, layout[IDX_MODE_WEEKLY], RankingMode.WEEKLY, "mode-weekly", Material.CLOCK,
                    "leaderboard.mode-weekly");
            addModeButton(inv, cm, messages, layout[IDX_MODE_MONTHLY], RankingMode.MONTHLY, "mode-monthly", Material.BOOK,
                    "leaderboard.mode-monthly");
            addModeButton(inv, cm, messages, layout[IDX_MODE_ALLTIME], RankingMode.ALL_TIME_COINS, "mode-alltime", Material.GOLD_INGOT,
                    "leaderboard.mode-alltime");
        } else {
            addDisabledModePlaceholder(inv, layout[IDX_MODE_WEEKLY]);
            addDisabledModePlaceholder(inv, layout[IDX_MODE_MONTHLY]);
            addDisabledModePlaceholder(inv, layout[IDX_MODE_ALLTIME]);
        }
        addModeButton(inv, cm, messages, layout[IDX_MODE_ALL], RankingMode.ALL_PLAYERS, "mode-all", Material.PLAYER_HEAD,
                "leaderboard.mode-all");
        // Wave 2 域 4：动态按钮（slot 6，BELL）——social 门关闭时不渲染
        //（点击侧 SocialListener 对残留占位/陈旧界面的 feed 槽回 module.disabled）。
        if (socialEnabled) {
            addModeButton(inv, cm, messages, layout[IDX_MODE_FEED], RankingMode.FEED, "mode-feed", Material.BELL,
                    "square.feed.mode-label");
        }

        // ---- Player heads / Feed entries ----
        // 榜单模式（周/月/总）的快照是全量，渲染从全局下标 startIdx 起；
        // 广场全量模式与 FEED 的快照已是本页切片，起点由渲染器自己按 0 取（见两方法）。
        int startIdx = (page - 1) * PLAYERS_PER_PAGE;
        if (mode == RankingMode.FEED) {
            renderFeedEntries(inv, viewer, messages, lang, page, startIdx, plugin.getSocialManager());
        } else {
            renderPlayerHeads(inv, store, messages, mode, page, startIdx, rankedEntries);
        }

        // ---- Pagination ----
        // R-36: 上一页/下一页各自独立门禁（上一页只看 page>1，下一页只看 page<totalPages）。
        // 旧 pageFull 联合门禁会让不满的尾页两按钮都不放，玩家卡死翻不回去。
        if (page > 1) {
            String prevLabel = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.prev-page"));
            String prevLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.prev-page-lore")
                            .replace("{page}", String.valueOf(page - 1)));
            int prevSlot = layout[IDX_PREV];
            Material prevMaterial = cm.getGuiMaterial("english-square", "prev-page", Material.ARROW);
            inv.setItem(prevSlot, createItem(prevMaterial, prevLabel,
                    Collections.singletonList(prevLore)));
        }
        if (page < totalPages) {
            String nextLabel = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.next-page"));
            String nextLore = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.next-page-lore")
                            .replace("{page}", String.valueOf(page + 1)));
            int nextSlot = layout[IDX_NEXT];
            Material nextMaterial = cm.getGuiMaterial("english-square", "next-page", Material.ARROW);
            inv.setItem(nextSlot, createItem(nextMaterial, nextLabel,
                    Collections.singletonList(nextLore)));
        }

        // ---- Back button ----
        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                MessageUtil.getGuiMsg(messages, "gui.back-button"));
        int backSlot = layout[IDX_BACK];
        Material backMaterial = cm.getGuiMaterial("english-square", "back-button", Material.RED_STAINED_GLASS_PANE);
        inv.setItem(backSlot, createItem(backMaterial, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        if (mode == RankingMode.FEED && currentFeedItems.isEmpty()) {
            // 动态流空态（plan-social §1.3）：引导去学习
            String emptyLabel = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.feed.empty"));
            int emptySlot = layout[IDX_EMPTY];
            Material emptyMaterial = cm.getGuiMaterial("english-square", "empty-icon", Material.BOOK);
            inv.setItem(emptySlot, createItem(emptyMaterial, emptyLabel, Collections.emptyList()));
        } else if (mode != RankingMode.FEED && currentPlayerIds.isEmpty()) {
            // 非 FEED 模式的空态。P-SQ-PAGE：这里判的是"本页无行"，不是"全库无人"——
            // 分页后前者不再蕴含后者。但正常路径下二者等价（页码已 clamp 到
            // [1, totalPages]，能翻到的页必有行）；唯一的例外是"计数与取行两条 SQL
            // 之间有人隐身/清库"的纳秒级窗口，此时显示"暂无玩家"是可接受的最坏值
            // （不伪造行、不抛异常），不为此增加一次判空查询。
            String emptyLabel = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.empty"));
            int emptySlot = layout[IDX_EMPTY];
            Material emptyMaterial = cm.getGuiMaterial("english-square", "empty-icon", Material.BOOK);
            inv.setItem(emptySlot, createItem(emptyMaterial, emptyLabel, Collections.emptyList()));
        }

        // Viewer's own head — slot 0
        String ownInfo;
        if (mode == RankingMode.ALL_PLAYERS) {
            // F3（R13-M3）：自己头像统计来自异步段（聚合行优先，未命中才回落
            // getPlayerStats）；null = 那一路没取到，按旧实现的最坏值 0 渲染。
            int ownCorrect = data.ownCorrect() != null ? data.ownCorrect() : 0;
            int ownIncorrect = data.ownIncorrect() != null ? data.ownIncorrect() : 0;
            ownInfo = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.player-info")
                            .replace("{correct}", String.valueOf(ownCorrect))
                            .replace("{incorrect}", String.valueOf(ownIncorrect)));
        } else if (mode == RankingMode.FEED) {
            // 动态页上的自己头像只做入口提示（鼓励名单走自己条目左键，见 SocialListener）
            ownInfo = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.click-hint"));
        } else {
            // 排名模式：金币由异步段取（null = 没取到，按旧实现最坏值 0）。
            long ownCoins = data.ownCoins() != null ? data.ownCoins() : 0L;
            ownInfo = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "leaderboard.rank-entry")
                            .replace("{rank}", "-")
                            .replace("{score}", String.valueOf(ownCoins)));
        }
        String ownClickHint = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "square.click-hint"));
        ItemStack ownHead = new ItemStack(Material.PLAYER_HEAD);
        if (ownHead.getItemMeta() instanceof SkullMeta ownMeta) {
            ownMeta.setOwningPlayer(Bukkit.getOfflinePlayer(viewer.getUniqueId()));
            ownMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b" + viewer.getName()));
            List<String> ownLore = new ArrayList<>();
            ownLore.add(ownInfo);
            if (mode != RankingMode.FEED) {
                ownLore.add("");
                ownLore.add(ownClickHint);
            }
            ownMeta.setLore(ownLore);
            ownHead.setItemMeta(ownMeta);
        }
        inv.setItem(0, ownHead);

        GUIUtils.fillEmpty(inv); // R12-M3：空槽填灰玻璃（防拖纵深；全部 setItem 之后）
        viewer.openInventory(inv);
    }

    /**
     * R19-GUI2：把「异步段 → 主线程段」的调度收成一处，带 {@code IllegalStateException} 守卫
     * （关服竞态下 {@code runTask} 会抛）。
     */
    private void runOnMain(BukkitScheduler scheduler, Runnable action) {
        try {
            scheduler.runTask(plugin, action);
        } catch (IllegalStateException ignored) {
            // 关服竞态：调度器已关闭，放弃本次回主线程
        }
    }

    /** 取数失败的玩家提示（必须主线程调用）。 */
    private void notifyLoadFailed(Player viewer) {
        viewer.sendMessage(plugin.getMessageUtil().getPlayerMessage(viewer, "daily.load-failed"));
    }

    /**
     * R20-GUI2 守卫三：玩家是否仍停在「请求发起时那张界面」上。
     *
     * <p><b>为什么必须是 holder 引用相等</b>：见 {@code GuiViewKey} 契约 —— 本仓库的界面
     * 身份唯一来源是 {@link PluginGuiHolder}，标题不参与任何判定。标题是
     * {@code square.title} + 模式标签 + {@code {page}/{total}} 后缀，既证明不了
     * 「还是那张屏幕」，也会在管理员改文案 / 玩家切语言后静默失效。
     * 照 {@code AchievementWallGUI#stillOnRequestedScreen} 同式。
     *
     * <p>{@code null == null} 通过是正确的：命令直达时玩家不在插件界面，发起与回填
     * 两侧都是 null，身份一致（比的是「期间有没有换屏幕」，不是 payload）。
     *
     * @param viewer        打开者
     * @param arrivalHolder 请求发起时其界面 holder（可为 null = 当时不在插件界面）
     * @return 仍然是同一张界面（或同样都不在插件界面）时 true
     */
    private static boolean stillOnArrivalScreen(Player viewer, PluginGuiHolder arrivalHolder) {
        return pluginGuiHolderOf(viewer) == arrivalHolder;
    }

    /**
     * 取玩家当前打开界面的 {@link PluginGuiHolder}；不是插件界面（或没有界面）时返回 null。
     *
     * <p>只认 {@code instanceof PluginGuiHolder}，因此玩家背包（holder 是 {@code Player}）
     * 与原版容器（自己的 holder）一律归为 null —— 不参与身份比较。
     */
    private static PluginGuiHolder pluginGuiHolderOf(Player viewer) {
        if (viewer == null) return null;
        var view = viewer.getOpenInventory();
        if (view == null) return null;
        Inventory top = view.getTopInventory();
        return (top != null && top.getHolder() instanceof PluginGuiHolder holder) ? holder : null;
    }

    /** 日志辅助：{@code plugin.getLogger()} 在纯 JVM 单测的 mock 插件上可能为 null。 */
    private void logWarning(String message, Throwable error) {
        var logger = plugin.getLogger();
        if (logger != null) {
            logger.log(java.util.logging.Level.WARNING, message, error);
        }
    }


    /**
     * 广场默认落点（Wave 2 域 4）：配置 {@code english-square.default-mode}
     * （{@code feed|all}，默认 {@code feed}——动态流放首页最大化循环入口；
     * 非法值同样回落 feed）。social 门关闭时的回落由 {@link #open} 内的独立门处理。
     */
    private RankingMode resolveDefaultMode() {
        String raw = plugin.getConfigManager().getMainConfig()
                .getString("english-square.default-mode", "feed");
        return "all".equalsIgnoreCase(raw) ? RankingMode.ALL_PLAYERS : RankingMode.FEED;
    }

    /**
     * R5-4：{@link RankingMode} → holder payload 的模式键。
     *
     * <p>契约见 {@link PluginGuiHolder.GuiPayload.SquarePage}：只认
     * {@code "weekly"}/{@code "monthly"}/{@code "alltime"}/{@code "all"}/{@code "feed"}
     * 五个小写串（gui.identity 包刻意不依赖 {@code core.progress.RankingMode}）。
     * 调用点只有一个（{@link #open(Player, int, RankingMode)}），且 mode 此时已走完全部
     * 回落（null → 默认落点、榜单门/social 门 → ALL_PLAYERS），所以这里拿到的是
     * <b>真正渲染的模式</b> —— 点击侧读 payload 得到的模式必然与屏幕一致，不会再
     * 出现「标题说 A、列表是 B」。
     */
    private static String modeKeyOf(RankingMode mode) {
        return switch (mode) {
            case WEEKLY -> "weekly";
            case MONTHLY -> "monthly";
            case ALL_TIME_COINS -> "alltime";
            case FEED -> "feed";
            default -> "all";
        };
    }

    /**
     * 玩家头像区渲染（榜单/全量模式）：原内联循环抽方法，行为零变化。
     * <b>渲染顺序契约</b>：第 (page-1)*28 + slotIdx 个列表元素 → {@link #PLAYER_SLOTS}[slotIdx]，
     * 与点击侧的下标反算严格一致。
     *
     * <p><b>P-SQ-PAGE：两种模式的下标基准不同</b> ——
     * <ul>
     *   <li>榜单模式（周/月/总）：快照是<b>全量</b>（display-count 行），列表下标是全局下标，
     *       故从 {@code startIdx=(page-1)*28} 起取；</li>
     *   <li>广场全量模式：快照是<b>本页切片</b>（SQL 已按 OFFSET 取好一页），列表下标 0
     *       就是本页第一个头像，起点必须是 0。若沿用 startIdx，第 2 页会从 28 起算而
     *       列表只有 28 行 → 整页空白（分页后最典型的"翻页即空"回归）。</li>
     * </ul>
     */
    private void renderPlayerHeads(Inventory inv, PlayerDataStore store, FileConfiguration messages,
                                   RankingMode mode, int page, int startIdx,
                                   List<LeaderboardEntry> rankedEntries) {
        int listStart = mode == RankingMode.ALL_PLAYERS ? 0 : startIdx;
        int endIdx = Math.min(listStart + PLAYERS_PER_PAGE, currentPlayerIds.size());
        for (int i = listStart; i < endIdx; i++) {
            int slot = PLAYER_SLOTS[i - listStart];
            UUID targetId = currentPlayerIds.get(i);
            // F3（R13-M3）：全量模式的统计直接读聚合行（word_progress 聚合值随
            // getActiveSquareEntries 单查一次带回）——旧实现逐人 getPlayerStats 查库
            //（N+1 热源）。名字优先读行内 player_name（DB 真值），行值缺失（uuid 兜底）
            // 时回落既有主线程名册路径（缓存→名册，非 DB 查询），展示不降级。
            LeaderboardEntry rowEntry = mode == RankingMode.ALL_PLAYERS
                    ? findEntry(rankedEntries, targetId) : null;
            String targetName = store.getPlayerName(targetId);
            if (rowEntry != null && !rowEntry.playerName.isEmpty()
                    && !rowEntry.playerName.equals(targetId.toString())) {
                targetName = rowEntry.playerName;
            }
            if (targetName == null) targetName = MessageUtil.getGuiMsg(messages, "profile.unknown-player");

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetId));
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b" + targetName));
                List<String> lore = new ArrayList<>();

                // 14-S1：对错数字须过目标 showResults 门（同文件 FEED 分支已同口径；数据层 WHERE 只过滤 show_in_square）。
                String playerInfo = store.getProfileSettingsReadOnly(targetId).isShowResults()
                        ? MessageUtil.getGuiMsg(messages, "square.player-info")
                        : MessageUtil.getGuiMsg(messages, "profile.stats-hidden-value");

                if (mode == RankingMode.ALL_PLAYERS) {
                    if (rowEntry != null) {
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                playerInfo.replace("{correct}", String.valueOf(rowEntry.correct))
                                        .replace("{incorrect}", String.valueOf(rowEntry.incorrect))));
                    }
                } else {
                    // Find the entry for this player
                    LeaderboardEntry entry = findEntry(rankedEntries, targetId);
                    if (entry != null) {
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "leaderboard.rank-entry")
                                        .replace("{rank}", String.valueOf(entry.rank))
                                        .replace("{score}", String.valueOf(entry.score))));
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                playerInfo.replace("{correct}", String.valueOf(entry.correct))
                                        .replace("{incorrect}", String.valueOf(entry.incorrect))));
                    }
                }
                lore.add("");
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "square.click-hint")));
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(slot, head);
        }
    }

    /**
     * 动态流条目渲染（Wave 2 域 4，plan-social §3.3）：
     * 头像 + 事件行（{@link FeedTextFormatter} 按 viewer 语言组装）+ 数字行
     * （SESSION_DONE 专属；作者 {@code showResults=false} 时降级 hidden-detail）+
     * cheer 计数行 + 提示行（自己=查看鼓励 / 他人=左键鼓励+右键主页 / 已鼓励=cheered 行）。
     *
     * <p>唯一新增同步查询：每页 ≤28-id 有界"你已鼓励"集合（{@link SocialManager#getCheeredFeedIds}），
     * 量级与既有 getPlayerStats 兜底同级（plan §3.2 权衡标注）。
     * 数字行读作者 {@code getProfileSettingsReadOnly}（只读版，不建缓存条目——L-05）。
     */
    private void renderFeedEntries(Inventory inv, Player viewer, FileConfiguration messages,
                                   String lang, int page, int startIdx,
                                   SocialManager socialManager) {
        int endIdx = Math.min(startIdx + PLAYERS_PER_PAGE, currentFeedItems.size());
        if (endIdx <= startIdx) {
            return;
        }
        List<SocialManager.FeedItem> pageItems = currentFeedItems.subList(startIdx, endIdx);
        List<Long> pageIds = new ArrayList<>(pageItems.size());
        for (SocialManager.FeedItem item : pageItems) {
            pageIds.add(item.id());
        }
        // R19-GUI2：FEED 仍是同步打开（零查库），但 social 门可能在异步窗口/陈旧界面
        // 里被关掉 —— 这里必须判空，否则 `socialManager.getCheeredFeedIds` 直接 NPE，
        // 玩家点"动态"得到的是「点了没反应」而不是页面。
        Set<Long> cheered = (socialManager != null)
                ? socialManager.getCheeredFeedIds(viewer.getUniqueId(), pageIds)
                : Collections.emptySet();
        FeedTextFormatter.KeyLookup lookup = key -> MessageUtil.getGuiMsg(messages, key);

        for (int i = 0; i < pageItems.size(); i++) {
            SocialManager.FeedItem item = pageItems.get(i);
            int slot = PLAYER_SLOTS[i];
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(item.authorId()));
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b" + item.authorName()));
                List<String> lore = new ArrayList<>();

                String eventLine = FeedTextFormatter.buildEventLine(item.type(), item.eventsCount(),
                        item.authorName(), item.lastMode(), item.lastStreak(),
                        item.achievementId(), lang, lookup);
                if (eventLine == null) {
                    continue; // 未知事件类型（脏行）—— 跳过该条，不渲染空条目
                }
                lore.add(ChatColor.translateAlternateColorCodes('&', eventLine));

                boolean showResults = plugin.getPlayerDataStore()
                        .getProfileSettingsReadOnly(item.authorId()).isShowResults();
                String detailLine = FeedTextFormatter.buildDetailLine(item.type(), showResults,
                        item.sumCorrect(), item.sumTotal(), lookup);
                if (detailLine != null) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', detailLine));
                }

                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "square.feed.cheer-count")
                                .replace("{count}", String.valueOf(item.cheerCount()))));
                lore.add("");
                if (item.authorId().equals(viewer.getUniqueId())) {
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "square.feed.own-hint")));
                } else {
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "square.feed.cheer-hint")));
                    if (cheered.contains(item.id())) {
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "square.feed.cheered")));
                    }
                }
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(slot, head);
        }
    }

    /**
     * 渲染一个模式切换按钮。
     *
     * <p>R12-slot：{@code slot} 由调用方传入<b>已裁决</b>的槽位（{@link #resolveHomeSlots}），
     * 本方法不再读配置 —— 否则同一键在一次渲染里会被读两次，两次之间若发生
     * {@code /lea reload} 就会画出两个互相矛盾的位置。
     */
    private void addModeButton(Inventory inv, ConfigManager cm, FileConfiguration messages,
                                int slot, RankingMode mode, String configKey,
                                Material defaultMaterial, String messageKey) {
        Material mat = cm.getGuiMaterial("english-square", configKey, defaultMaterial);
        boolean selected = (currentMode == mode);
        Material displayMat = selected ? Material.LIME_DYE : mat;
        String label = ChatColor.translateAlternateColorCodes('&',
                (selected ? "&a&l" : "&7") + MessageUtil.getGuiMsg(messages, messageKey));
        String hint = MessageUtil.getGuiMsg(messages, selected ? "leaderboard.selected" : "leaderboard.switch-hint");
        inv.setItem(slot, createItem(displayMat, label,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', hint))));
    }

    /**
     * MC-2（S-H）：榜单门关闭时的模式按钮灰玻璃占位。
     *
     * <p>R12-slot：槽位由调用方传入同一个已裁决值（占位与按钮必须落在同一格，否则
     * 管理员改过槽位后占位与点击判定（SocialListener 读同键）会分成两处）；点击侧
     * 对残留占位槽的点击回 module.disabled。
     */
    private void addDisabledModePlaceholder(Inventory inv, int slot) {
        inv.setItem(slot, createItem(Material.GRAY_STAINED_GLASS_PANE, " ",
                Collections.emptyList()));
    }

    /**
     * 总页数：{@code ceil(rows / 28)}，下限 1（空列表也显示 "1/1"，与旧实现同式）。
     *
     * <p>用整数运算而非 {@code (int) Math.ceil((double) rows / 28)}：浮点路径在
     * 极大行数（&gt;2^53）会丢精度，且这里本来就是整除语义。
     */
    private static int pageCountOf(int rows) {
        if (rows <= 0) return 1;
        return Math.max(1, (rows + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
    }

    private LeaderboardEntry findEntry(List<LeaderboardEntry> entries, UUID playerId) {
        for (LeaderboardEntry e : entries) {
            if (e.playerId.equals(playerId)) return e;
        }
        return null;
    }

    public void openHidden(Player viewer, UUID targetId) {
        ConfigManager cm = plugin.getConfigManager();
        FileConfiguration messages = cm.getMessageConfig(plugin.getPlayerLanguage(viewer));
        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "square.title")
                        // R11-L1：补 {page} 替换（照 open() 的标题构造；笑话页无分页语义，
                        // 取 open() 空列表同款的 "1/1" —— 标题残留 {page} 视觉破绽）。
                        .replace("{page}", "1/1"));
        // R5-4：笑话页与 54 格主页共用 square.title（{page} 都填 "1/1"），标题逐字相同
        // —— holder key 是唯一能区分两者的结构事实。点击侧因此不再靠
        // 「top size <= 27」猜这是不是笑话页。
        Inventory inv = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.SQUARE_HIDDEN), HIDDEN_GUI_SIZE, title);

        String jokeName = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "square.hidden-title"));
        String jokeLore = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "square.hidden-lore"));

        ItemStack potion = new ItemStack(Material.POTION);
        ItemMeta meta = potion.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(jokeName);
            meta.setLore(Collections.singletonList(jokeLore));
            potion.setItemMeta(meta);
        }
        inv.setItem(13, potion);

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                MessageUtil.getGuiMsg(messages, "gui.back-button"));
        // R12-slot：笑话页只有 27 格，而 english-square.back-button 这个键被主页（54 格）
        // 与本页共用。落在 [27, 53] 的值对主页合法、对本页必炸 —— 且本页是隐身玩家的
        // 替代页，服主从主页看不出任何异常。故判据必须是**本页尺寸**，不能复用主页那份
        // 已裁决结果。回落用该键的出厂默认值 8（对两页都合法）。
        int backSlot = GUIUtils.readSlot(cm, plugin.getLogger(), "english-square",
                "back-button", HOME_SLOT_DEFAULTS[IDX_BACK], HIDDEN_GUI_SIZE);
        Material backMaterial = cm.getGuiMaterial("english-square", "back-button", Material.RED_STAINED_GLASS_PANE);
        inv.setItem(backSlot, createItem(backMaterial, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        GUIUtils.fillEmpty(inv); // R12-M3：空槽填灰玻璃（防拖纵深）
        viewer.openInventory(inv);
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
