package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.DifficultWord;
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
import org.bukkit.scheduler.BukkitScheduler;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p><b>R19-GUI2（主线程冻结修复）：打开路径已两段式异步化。</b>
 * {@code open(Player, int)} 是兼容入口，统一委托 {@link #openAsync(Player, int)}；
 * 既有调用点（主菜单点击 / 翻页 / 删词后重开）零改动。异步段只做
 * {@code getWrongBookEntries}（→ 缓存 miss 时一次 {@code difficult_words} SELECT）
 * 与「列表为空时」的 {@code loadDifficultWords(...).readFailed()} 复检，主线程段只渲染
 * （{@link #render}，零 DB 调用）。与 {@code DailyChallengeGUI} / {@code LearningCalendarGUI} /
 * {@code ChatHistoryGUI} 同一范式。
 *
 * <p><b>为什么本类不用「先开占位界面、再异步回填」那种范式</b>（{@code ProfileGUI} 用的
 * 是它）：本页的页码 clamp、标题里的 {@code {page}/{total}}、以及 holder 载荷里的
 * {@code GuiPayload.Page} <b>三者必须逐字一致</b>，而 clamp 的上界只有拿到行数才知道。
 * 占位界面只能编造一个页码 —— 编造会直接违反 {@code WrongBookListener} 唯一的页码来源
 * （holder payload），翻页/删词后重开就会跳错页。行数未知时不开界面，是唯一不撒谎的选择。
 */
public class WrongBookGUI {

    private static final int GUI_SIZE = 54;

    /**
     * R19-GUI2：openAsync 在途去重 token（per-class 序列 + per-player 记录，照
     * {@code ChatHistoryGUI.OPEN_SEQ} 同式）。
     *
     * <p>为什么要 static：翻页/删词后重开每次都 {@code new WrongBookGUI(plugin)}，
     * 实例级令牌拦不住「上一页的请求比这一页晚回来」——那会让玩家点"下一页"之后
     * 界面又跳回上一页。连点两次翻页时后发起者胜，旧请求在回主线程 render 前作废。
     * 条目量级 = 历史玩家数条 Long，每次 openAsync 覆写自身，惰性收口（不挂 quit 清理）。
     */
    private static final AtomicLong OPEN_SEQ = new AtomicLong();
    private static final Map<UUID, Long> OPEN_SEQ_BY_PLAYER = new ConcurrentHashMap<>();

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

    /**
     * R19-GUI2：兼容入口 —— 统一委托 {@link #openAsync(Player, int)}。
     *
     * <p>既有调用点（{@code MainMenuListener} 主菜单点击、{@code WrongBookListener}
     * 的翻页与删词后重开）全部零改动，行为语义与原同步 open 一致（仅改为两段式异步）。
     *
     * <p>模块门留在这里而不是搬进异步段：{@code isWordSystemEnabled()} 是纯配置读
     * （零 IO），而玩家在异步窗口内点了"翻页"时应当<b>立刻</b>收到 {@code module.disabled}
     * ——把一句拒绝文案拖到几十毫秒之后，观感就是"点了没反应"。
     */
    public void open(Player player, int page) {
        // 模块门（旅程验收收口）：word-system 承诺"错题本隐藏"——本类收敛所有打开
        // 入口（主菜单点击/翻页/删除后重开），单点门覆盖全链；点击侧
        // WrongBookListener 另有僵尸界面兜底。
        if (!plugin.getConfigManager().isWordSystemEnabled()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "module.disabled"));
            return;
        }
        openAsync(player, page);
    }

    /**
     * R19-GUI2：异步两段式打开入口（照 {@code DailyChallengeGUI.openAsync} 同式）。
     *
     * <p>异步段只碰数据层与 logger，<b>零 Bukkit 世界 API</b>：
     * <ol>
     *   <li>{@code getWrongBookEntries}（缓存 miss 时内含一次 {@code difficult_words} SELECT）；</li>
     *   <li>「列表为空」时的 {@code loadDifficultWords(...).readFailed()} 复检 —— 空列表才有
     *       歧义（确实没收录 vs 查询失败被吞成空）；</li>
     *   <li>页码 clamp —— 上界来自行数，必须在这里算完，否则主线程还得再查一次。</li>
     * </ol>
     *
     * <p><b>失败路径</b>：读异常 / 查询级读失败 → 保持旧行为（{@code daily.load-failed}
     * 文案），但必须切回主线程再发（{@code sendMessage} 不保证异步安全）。
     *
     * <p><b>为什么失败时不开界面</b>：与旧同步实现逐字一致 —— 旧实现同样是「先查、
     * 失败就提示并 return」，玩家停在来源界面。编造一个空错题本会谎报"还没有错题"。
     */
    public void openAsync(Player player, int page) {
        UUID playerId = player.getUniqueId();
        // 连点/翻页竞态：后发起者胜（旧请求回主线程 render 前比对失败即作废）。
        final long token = OPEN_SEQ.incrementAndGet();
        OPEN_SEQ_BY_PLAYER.put(playerId, token);
        // 守卫三（R20-GUI2）：玩家在异步窗口内「关界面但仍在线」时必须放弃本次渲染。
        // 与 token/在线复检是三种独立失效方式，缺任一条都能实测复现：
        //   · 下线 → 界面无处可开（isOnline 拦）；
        //   · 连点 → 旧请求晚落地拽回上一页（token 拦）；
        //   · <b>关界面（仍在线）</b> → 数据回来时无脑 openInventory，界面自己弹回来。
        // 判据必须是 <b>holder 引用相等</b>（本仓库硬纪律：身份只认 holder，绝不比标题）。
        // 请求发起时记下玩家所在界面的 holder，回填前重取；不是同一个实例 = 玩家已离开
        // 那张屏幕（关掉 / 进了别的插件界面 / 回了自己背包），放弃本次打开。
        // 照 {@code AchievementWallGUI#stillOnRequestedScreen} 同式。
        loadWrongBook(playerId); // MUTANT: 主线程同步查库
        final PluginGuiHolder arrivalHolder = pluginGuiHolderOf(player);

        BukkitScheduler scheduler = Bukkit.getScheduler();
        if (scheduler == null) {
            // 无调度器环境（纯 JVM 单测夹具里 mockStatic(Bukkit) 默认返回 null）：退回
            // 同步取数 + 渲染，与修复前的同步实现逐行等价。生产环境
            // Bukkit.getScheduler() 永不为 null，故该分支不参与线上路径 —— 它存在的
            // 唯一理由是「已经能跑通的既有夹具不该因为线程模型改造而被判红」。
            openSyncFallback(player, page, playerId, token);
            return;
        }
        try {
            scheduler.runTaskAsynchronously(plugin, () -> {
                if (!plugin.isEnabled()) return;

                WrongBookLoad load = loadWrongBook(playerId);
                // 异常，或查询级读失败 → 可重试提示（不谎报"没有错题"）。
                if (!load.ok()) {
                    runOnMain(scheduler, () -> notifyLoadFailed(player, playerId, token));
                    return;
                }
                final List<Map.Entry<String, DifficultWord>> entries = load.entries();

                // 页码 clamp（旧实现在主线程做，现在必须在建界面之前算完，且只能算一次）：
                // R5-1 起页码要写进 holder 的载荷（GuiPayload.Page 的构造器拒绝 page < 1，
                // 越界会直接抛异常而不是静默回落）。同时保证「标题里的页码」与
                // 「holder 载荷里的页码」逐字一致。
                final int totalPages = totalPagesOf(entries);
                final int safePage = clampPage(page, totalPages);

                if (!plugin.isEnabled()) return;
                runOnMain(scheduler, () -> {
                    if (!player.isOnline()) return;
                    // 连点/翻页竞态：已有更新的打开请求（翻页、删词重开），本次作废。
                    if (OPEN_SEQ_BY_PLAYER.getOrDefault(playerId, -1L) != token) return;
                    // 守卫三：玩家已关掉界面（仍在线）→ 不把界面强行弹回来。
                    if (!stillOnArrivalScreen(player, arrivalHolder)) return;
                    render(player, entries, safePage, totalPages);
                });
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次打开（玩家也已不可能看到界面）。
        }
    }

    /**
     * 无调度器环境的同步兜底（<b>仅测试夹具路径</b>，生产不可达 —— {@code Bukkit.getScheduler()}
     * 在活服务器上永不为 null）。
     *
     * <p>抽成独立方法而不是内联在 {@link #openAsync(Player, int)} 里，是为了让源码 lint
     * 的锚点精确：{@code openAsync} 的主线程段必须<b>只剩调度逻辑</b>，任何"顺手在这里
     * 查一次库"的写法都要能被静态抓住。抽出来之后，本方法的存在本身就是一条声明：
     * 「同步取数只允许发生在这里，而这里只在无调度器时被调用」。
     *
     * <p>本分支**不查 {@code plugin.isEnabled()}**：夹具里的 mock 插件默认
     * {@code isEnabled=false}，而在无调度器窗口里"插件已停用"这个前提根本不成立。
     */
    private void openSyncFallback(Player player, int page, UUID playerId, long token) {
        WrongBookLoad load = loadWrongBook(playerId);
        if (!load.ok()) {
            notifyLoadFailed(player, playerId, token);
            return;
        }
        int totalPages = totalPagesOf(load.entries());
        render(player, load.entries(), clampPage(page, totalPages), totalPages);
    }

    /**
     * R19-GUI2：一次错题本读数的结果（{@code entries} 非 null 时 {@code readFailed=false}）。
     *
     * <p>为什么包成 record 而不是「返回 null 表示失败」：失败有两种成因（抛异常 /
     * 查询级读失败被吞成空列表），而<b>空列表本身是合法成功值</b>。用 {@code null}
     * 承载失败会让「空错题本」与「读失败」在类型层面同形 —— 那正是 R4-15-EH-15-02
     * 花一整轮修掉的缺陷形状（旧判据把可见性挂在{@code isConnectionHealthy()}上，
     * 查询级 SQLException 不改变它，玩家看到"还没有错题呢~"且无失败提示）。
     */
    private record WrongBookLoad(List<Map.Entry<String, DifficultWord>> entries, boolean readFailed) {
        boolean ok() {
            return entries != null && !readFailed;
        }
    }

    /**
     * R19-GUI2：错题本读数的<b>唯一</b>入口（异步段与同步兜底共用，避免两处判据漂移）。
     *
     * <p>只碰数据层与 logger，<b>不触任何 Bukkit 世界 API</b>，因此异步线程可安全调用。
     */
    private WrongBookLoad loadWrongBook(UUID playerId) {
        // R9/P1-B：只展示"已收录"的词（wrong_count >= 3）——difficult_words 表在首次答错
        // 即建行（兼作计数器），旧实现直接全量展示，与"错 3 次才进错题本"的提示自相矛盾。
        // 15-EH-01/02：原链路无 try（RuntimeException 直冒到事件处理器）；读失败又被折叠成空列表 →
        // 谎报「还没有错题呢~」。
        // R4-15-EH-15-02 重修：旧判据把可见性挂在 isConnectionHealthy() 上，而它只测「持有且未关闭」——
        // 查询级 SQLException（SQLITE_BUSY / 表缺失 / I/O 错误）不会让它变 false，玩家仍看到
        // 「还没有错题呢~」且无失败提示。改问数据层要「这次读到底成没成」（loadDifficultWords 的 readFailed）。
        try {
            List<Map.Entry<String, DifficultWord>> wordList =
                    plugin.getPlayerDataStore().getWrongBookEntries(playerId);
            if (wordList == null) {
                // 数据层违约（契约是恒非 null）：按「结果未知」处理，绝不当成空错题本。
                return new WrongBookLoad(null, true);
            }
            if (!wordList.isEmpty()) {
                return new WrongBookLoad(wordList, false);
            }
            // 空列表才有歧义：优雅区分「确实没收录」与「查询失败被吞成空」。
            DatabaseManager.DifficultWordsLoad reload =
                    plugin.getDatabaseManager().loadDifficultWords(playerId);
            return new WrongBookLoad(wordList, reload == null || reload.readFailed());
        } catch (RuntimeException e) {
            logWarning("Failed to load wrong book for " + playerId, e);
            return new WrongBookLoad(null, true);
        }
    }

    /** 总页数：{@code ceil(rows / ITEMS_PER_PAGE)}，下限 1（空错题本也是 1 页）。 */
    private static int totalPagesOf(List<Map.Entry<String, DifficultWord>> entries) {
        if (entries == null || entries.isEmpty()) return 1;
        return Math.max(1, (int) Math.ceil((double) entries.size() / ITEMS_PER_PAGE));
    }

    /** 页码 clamp：下界 1、上界 totalPages（totalPages 恒 ≥1）。 */
    private static int clampPage(int page, int totalPages) {
        if (page < 1) return 1;
        return Math.min(page, totalPages);
    }

    /**
     * 读失败提示（{@code daily.load-failed}，可重试语义）。
     *
     * <p>三件事必须一起做：① 在线复检（异步窗口内玩家可能已下线）；② token 复检
     * （已有更新的打开请求时，这条陈旧提示不该弹）；③ 只发文案、<b>不开界面</b> ——
     * 与旧同步实现逐字一致。
     *
     * <p>注意这里<b>不做</b> holder 守卫：失败提示是「你那一下没成功」的反馈，
     * 玩家即便已关界面也该知道（与 {@code AchievementWallGUI#notifyLoadFailed} 同口径）。
     * 守卫只用于拦「强行打开界面」。
     */
    private void notifyLoadFailed(Player player, UUID playerId, long token) {
        logWarning("Failed to load wrong book for " + playerId);
        if (!player.isOnline()) return;
        if (OPEN_SEQ_BY_PLAYER.getOrDefault(playerId, -1L) != token) return;
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "daily.load-failed"));
    }

    /**
     * R20-GUI2 守卫三：玩家是否仍停在「请求发起时那张界面」上。
     *
     * <p><b>为什么必须是 holder 引用相等</b>：本仓库的界面身份唯一来源是
     * {@link PluginGuiHolder}（{@code GuiViewKey} 契约：标题不参与任何判定）。标题是
     * 本地化文案 + 动态页码后缀，既证明不了「还是那张屏幕」，也在管理员改文案/切语言时
     * 静默失效。此处同理 —— 比标题等于给自己埋一个「改了 lang 文件后界面就学会自己弹回来」
     * 的坑。
     *
     * <p>{@code null == null} 是<b>正确</b>的通过：玩家从自己背包（无插件 holder）里
     * 触发打开时（例如命令直达），发起与回填两侧都没有插件界面，身份一致。
     * 这和「两个都是 null 所以不知道」不同 —— 我们比的不是 payload，而是「期间有没有换屏幕」。
     *
     * @param player        打开者
     * @param arrivalHolder 请求发起时其界面 holder（可为 null = 当时不在插件界面）
     * @return 仍然是同一张界面（或同样都不在插件界面）时 true
     */
    private static boolean stillOnArrivalScreen(Player player, PluginGuiHolder arrivalHolder) {
        return pluginGuiHolderOf(player) == arrivalHolder;
    }

    /**
     * 取玩家当前打开界面的 {@link PluginGuiHolder}；不是插件界面（或没有界面）时返回 null。
     *
     * <p>只认 {@code instanceof PluginGuiHolder}，因此玩家背包（holder 是 {@code Player}）
     * 与原版容器（自己的 holder）一律归为 null —— 不参与身份比较。
     * 照 {@code AchievementWallGUI#pluginGuiHolderOf} 同式。
     */
    private static PluginGuiHolder pluginGuiHolderOf(Player player) {
        if (player == null) return null;
        var view = player.getOpenInventory();
        if (view == null) return null;
        Inventory top = view.getTopInventory();
        return (top != null && top.getHolder() instanceof PluginGuiHolder holder) ? holder : null;
    }

    /**
     * R19-GUI2：把「异步段 → 主线程段」的调度收成一处，带 {@code IllegalStateException} 守卫。
     *
     * <p>关服竞态下 {@code runTask} 会抛 {@code IllegalStateException}；此时玩家必然已
     * 下线/不可能看到界面，静默放弃是正确终态。单测里调度器被就地同步执行，本方法
     * 只是一次直调（无额外 tick）。
     */
    private void runOnMain(BukkitScheduler scheduler, Runnable action) {
        try {
            scheduler.runTask(plugin, action);
        } catch (IllegalStateException ignored) {
            // 关服竞态：调度器已关闭，放弃本次回主线程
        }
    }

    /** 日志辅助：{@code plugin.getLogger()} 在纯 JVM 单测的 mock 插件上可能为 null。 */
    private void logWarning(String message) {
        var logger = plugin.getLogger();
        if (logger != null) {
            logger.warning(message);
        }
    }

    /** 带栈告警（{@link #logWarning(String)} 的重载）：失败根因要能追。 */
    private void logWarning(String message, Throwable error) {
        var logger = plugin.getLogger();
        if (logger != null) {
            logger.log(java.util.logging.Level.WARNING, message, error);
        }
    }

    /**
     * R19-GUI2：主线程渲染 —— 数据与页码 clamp 均由 {@link #openAsync(Player, int)} 预取，
     * 本方法<b>零 DB 调用</b>；渲染段与修复前逐行等价（物品/槽位/lore 全同）。
     *
     * <p>唯一保留的「非 DB 但会摸数据层」的调用是 {@code getWord} / {@code getWrongCountIfCached}
     * —— 前者读内存 {@code globalWordIndex}，后者<b>只查缓存、不触发载入</b>（见其 javadoc），
     * 两者都不产生 IO，故留在这里。
     *
     * @param wordList   已收录的词（错次降序）
     * @param page       已 clamp 到 [1, totalPages] 的页码
     * @param totalPages 总页数（由 wordList.size() 算出，与标题/载荷同源）
     */
    private void render(Player player, List<Map.Entry<String, DifficultWord>> wordList,
                        int page, int totalPages) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);

        // 页码与总页数已由异步段算好（`page` 已 clamp 到 [1, totalPages]）——此处
        // 不重算，否则「标题页码」「载荷页码」「翻页按钮指向」会有两个真相源。
        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "wrong-book.title-page")
                        .replace("{page}", String.valueOf(page)));

        // R5-1/R5-16：挂结构身份 holder + 页码载荷 —— 页码此前只活在标题里，翻页/删除后重开
        // 全靠 listener 用正则反解标题（两份口径不一致的私有实现）。现在页码是结构性事实，
        // listener 直接读 payload；payload 取不到时返回第 1 页，不再回退标题解析。
        Inventory inv = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.WRONG_BOOK,
                        new PluginGuiHolder.GuiPayload.Page(page)),
                GUI_SIZE, title);

        // 先用灰色玻璃板铺满全部槽位，防止玩家把物品拖进空槽
        // （单词与导航键随后覆盖，填充顺序：占位 -> 单词 -> 导航键）
        ItemStack filler = createGlassPane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < GUI_SIZE; i++) {
            inv.setItem(i, filler.clone());
        }

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
                // R13：错次数字取<b>活缓存</b>而不是 dw 携带的快照 —— DifficultWord 不可变，
                // 它带的是「加进错题本那一刻」的计数；玩家本次在线期间继续答错只改
                // wrongCountCache，不重建那个对象，于是界面会一直显示旧数字直到重进服。
                int liveWrongCount = plugin.getPlayerDataStore()
                        .getWrongCountIfCached(player.getUniqueId(), wordKey, dw.getWrongCount());
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "wrong-book.word-count")
                                .replace("{count}", String.valueOf(liveWrongCount))));
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
