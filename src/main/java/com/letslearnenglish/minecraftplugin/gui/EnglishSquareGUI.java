package com.letslearnenglish.minecraftplugin.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

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

    private static final int[] PLAYER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int PLAYERS_PER_PAGE = PLAYER_SLOTS.length;

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
     * 本次渲染<b>实际生效</b>的页码（clamp 之后的，不是调用方传入的原始值）。
     *
     * <p>P-SQ-PAGE：全量模式的 {@link #currentPlayerIds} 是本页切片，点击侧按下标反查
     * 时只有"快照页码 == 屏幕页码"才能保证"点 A 作用于 A"。payload 里的页码是屏幕事实，
     * 本字段是快照事实，二者相等是切片映射成立的前提（见 SocialListener 的 pageSlice 判定）。
     * 所有登记快照的打开路径都在同一主线程顺序里 open() 后立刻 put 快照，故正常情况下
     * 恒相等；本字段让"不等"可被显式检出，而不是静默错位。
     */
    private int currentPage = 1;
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

    public void open(Player viewer, int page, RankingMode mode) {
        ConfigManager cm = plugin.getConfigManager();
        String lang = plugin.getPlayerLanguage(viewer);
        FileConfiguration messages = cm.getMessageConfig(lang);
        PlayerDataStore store = plugin.getPlayerDataStore();
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
        this.currentMode = mode;

        int displayCount = cm.getMainConfig().getInt("progress-system.leaderboard.display-count", 10);

        List<LeaderboardEntry> rankedEntries;
        // 页数来源按模式分裂：FEED 与排名模式的行本来就在内存里（有界快照），
        // 全量模式的行已经不在内存（只取一页），页数必须单独 COUNT。
        int totalPages;
        if (mode == RankingMode.FEED) {
            // 零查库（F2-11 对照）：读 SocialManager volatile 快照（≤200 条内存切片）
            currentPlayerIds = new ArrayList<>();
            currentFeedItems = socialManager.getFeedSnapshot();
            rankedEntries = Collections.emptyList();
            totalPages = pageCountOf(currentFeedItems.size());
        } else if (mode == RankingMode.ALL_PLAYERS) {
            currentFeedItems = Collections.emptyList();
            rankedEntries = Collections.emptyList(); // 页码 clamp 后才取本页行，见下
            // F3（R13-M3）：聚合单查替换 N+1 装配 —— getActiveSquareEntries 单条 SQL
            // 完成隐身过滤（COALESCE show_in_square）+ 答过题过滤（word_progress 聚合 >0）
            // + correct/incorrect 求和；旧路径逐 cache-miss 玩家 getPlayerStats 查库。
            // 顺序 = score DESC + uuid ASC（全序，分页跨页不漂移）；数据源 = DB 真值
            // （自动保存窗口内的最新答题延迟可见，见 PlayerDataStore 同名方法 javadoc）。
            //
            // P-SQ-PAGE：先 COUNT 拿页数、clamp 页码，再按 (page-1)*28 只取一页 ——
            // 旧实现传 Integer.MAX_VALUE 把全库聚合行整片搬进内存，万人库实测
            // 1331.6 ms ≈ 26.6 tick 的主线程冻结（点一下广场全服卡 1.3 s）。
            totalPages = pageCountOf(store.countActiveSquareEntries());
        } else {
            currentFeedItems = Collections.emptyList();
            // Ranking mode: query leaderboard (include self if has activity)
            rankedEntries = plugin.getProgressManager().getLeaderboard().getTopPlayers(mode, displayCount);
            currentPlayerIds = new ArrayList<>();
            for (LeaderboardEntry e : rankedEntries) {
                currentPlayerIds.add(e.playerId);
            }
            totalPages = pageCountOf(currentPlayerIds.size());
        }

        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;
        this.currentPage = page;

        // 全量模式：页码确定后才能算 OFFSET。currentPlayerIds 只装本页 id，
        // 与 rankedEntries 逐下标对齐（渲染/点击"同源同序"契约在本页范围内成立）。
        if (mode == RankingMode.ALL_PLAYERS) {
            List<LeaderboardEntry> pageEntries =
                    store.getActiveSquareEntries(PLAYERS_PER_PAGE, (page - 1) * PLAYERS_PER_PAGE);
            rankedEntries = pageEntries;
            currentPlayerIds = new ArrayList<>(pageEntries.size());
            for (LeaderboardEntry e : pageEntries) {
                currentPlayerIds.add(e.playerId);
            }
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
        // page 已在上面 clamp 到 [1, totalPages]，SquarePage 的构造校验不会抛。
        Inventory inv = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.SQUARE,
                        new PluginGuiHolder.GuiPayload.SquarePage(page, modeKeyOf(mode))),
                GUI_SIZE, title);

        // ---- Mode toggle buttons (slots 2-5) ----
        // MC-2（S-H）：leaderboard.enabled=false 时周/月/总三榜按钮回落灰色玻璃占位
        //（沿用主菜单"模块关闭=玻璃占位"的既有视觉语言），"广场"全量模式保留（它不是榜单）。
        // 点击侧 SocialListener 对三榜槽位的分支拒绝并发 module.disabled。
        if (leaderboardEnabled) {
            addModeButton(inv, cm, messages, 2, RankingMode.WEEKLY, "mode-weekly", Material.CLOCK,
                    "leaderboard.mode-weekly");
            addModeButton(inv, cm, messages, 3, RankingMode.MONTHLY, "mode-monthly", Material.BOOK,
                    "leaderboard.mode-monthly");
            addModeButton(inv, cm, messages, 4, RankingMode.ALL_TIME_COINS, "mode-alltime", Material.GOLD_INGOT,
                    "leaderboard.mode-alltime");
        } else {
            addDisabledModePlaceholder(inv, cm, "mode-weekly", 2);
            addDisabledModePlaceholder(inv, cm, "mode-monthly", 3);
            addDisabledModePlaceholder(inv, cm, "mode-alltime", 4);
        }
        addModeButton(inv, cm, messages, 5, RankingMode.ALL_PLAYERS, "mode-all", Material.PLAYER_HEAD,
                "leaderboard.mode-all");
        // Wave 2 域 4：动态按钮（slot 6，BELL）——social 门关闭时不渲染
        //（点击侧 SocialListener 对残留占位/陈旧界面的 feed 槽回 module.disabled）。
        if (socialEnabled) {
            addModeButton(inv, cm, messages, 6, RankingMode.FEED, "mode-feed", Material.BELL,
                    "square.feed.mode-label");
        }

        // ---- Player heads / Feed entries ----
        // 榜单模式（周/月/总）的快照是全量，渲染从全局下标 startIdx 起；
        // 广场全量模式与 FEED 的快照已是本页切片，起点由渲染器自己按 0 取（见两方法）。
        int startIdx = (page - 1) * PLAYERS_PER_PAGE;
        if (mode == RankingMode.FEED) {
            renderFeedEntries(inv, viewer, messages, lang, page, startIdx, socialManager);
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
            int prevSlot = cm.getGuiSlot("english-square", "prev-page", 47);
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
            int nextSlot = cm.getGuiSlot("english-square", "next-page", 51);
            Material nextMaterial = cm.getGuiMaterial("english-square", "next-page", Material.ARROW);
            inv.setItem(nextSlot, createItem(nextMaterial, nextLabel,
                    Collections.singletonList(nextLore)));
        }

        // ---- Back button ----
        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                MessageUtil.getGuiMsg(messages, "gui.back-button"));
        int backSlot = cm.getGuiSlot("english-square", "back-button", 8);
        Material backMaterial = cm.getGuiMaterial("english-square", "back-button", Material.RED_STAINED_GLASS_PANE);
        inv.setItem(backSlot, createItem(backMaterial, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        if (mode == RankingMode.FEED && currentFeedItems.isEmpty()) {
            // 动态流空态（plan-social §1.3）：引导去学习
            String emptyLabel = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.feed.empty"));
            int emptySlot = cm.getGuiSlot("english-square", "empty-icon", 22);
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
            int emptySlot = cm.getGuiSlot("english-square", "empty-icon", 22);
            Material emptyMaterial = cm.getGuiMaterial("english-square", "empty-icon", Material.BOOK);
            inv.setItem(emptySlot, createItem(emptyMaterial, emptyLabel, Collections.emptyList()));
        }

        // Viewer's own head — slot 0
        String ownInfo;
        if (mode == RankingMode.ALL_PLAYERS) {
            // F3（R13-M3）：自己头像统计优先读聚合行（零额外查库）；聚合行不含自己
            //（隐身/未答过题）时回落 getPlayerStats（单人次、通常缓存命中，展示与旧一致）。
            // P-SQ-PAGE：聚合行现在只覆盖<b>本页 28 行</b>，自己不在本页时走回落 ——
            // 统计值与页面无关（total_correct/incorrect 是全局聚合），回落路径读的是
            // 同一份 cache/DB 真值，展示零变化；只是命中率从"几乎总是"变成"在本页时"，
            // 单次 getPlayerStats 的量级与既有兜底一致。
            LeaderboardEntry ownEntry = findEntry(rankedEntries, viewer.getUniqueId());
            int ownCorrect;
            int ownIncorrect;
            if (ownEntry != null) {
                ownCorrect = ownEntry.correct;
                ownIncorrect = ownEntry.incorrect;
            } else {
                PlayerDataStore.PlayerStats ownStats = store.getPlayerStats(viewer.getUniqueId());
                ownCorrect = ownStats.getTotalCorrect();
                ownIncorrect = ownStats.getTotalIncorrect();
            }
            ownInfo = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.player-info")
                            .replace("{correct}", String.valueOf(ownCorrect))
                            .replace("{incorrect}", String.valueOf(ownIncorrect)));
        } else if (mode == RankingMode.FEED) {
            // 动态页上的自己头像只做入口提示（鼓励名单走自己条目左键，见 SocialListener）
            ownInfo = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "square.click-hint"));
        } else {
            ownInfo = ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "leaderboard.rank-entry")
                            .replace("{rank}", "-")
                            .replace("{score}", String.valueOf(plugin.getDatabaseManager().getCoinsEarned(viewer.getUniqueId()))));
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

                if (mode == RankingMode.ALL_PLAYERS) {
                    if (rowEntry != null) {
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "square.player-info")
                                        .replace("{correct}", String.valueOf(rowEntry.correct))
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
                                MessageUtil.getGuiMsg(messages, "square.player-info")
                                        .replace("{correct}", String.valueOf(entry.correct))
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
        Set<Long> cheered = socialManager.getCheeredFeedIds(viewer.getUniqueId(), pageIds);
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

    private void addModeButton(Inventory inv, ConfigManager cm, FileConfiguration messages,
                                int defaultSlot, RankingMode mode, String configKey,
                                Material defaultMaterial, String messageKey) {
        int slot = cm.getGuiSlot("english-square", configKey, defaultSlot);
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
     * MC-2（S-H）：榜单门关闭时的模式按钮灰玻璃占位（槽位读与按钮同一份
     * gui-layout 配置，管理员改槽后占位与点击判定（SocialListener 读同键）
     * 仍然对齐；点击侧对残留占位槽的点击回 module.disabled）。
     */
    private void addDisabledModePlaceholder(Inventory inv, ConfigManager cm,
                                             String configKey, int defaultSlot) {
        int slot = cm.getGuiSlot("english-square", configKey, defaultSlot);
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
                PluginGuiHolder.of(GuiViewKey.SQUARE_HIDDEN), 27, title);

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
        int backSlot = cm.getGuiSlot("english-square", "back-button", 8);
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
