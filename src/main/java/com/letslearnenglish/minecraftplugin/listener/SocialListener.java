package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.RankingMode;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.AchievementWallGUI;
import com.letslearnenglish.minecraftplugin.gui.EnglishSquareGUI;
import com.letslearnenglish.minecraftplugin.gui.GUIUtils;
import com.letslearnenglish.minecraftplugin.gui.LeaderboardGUI;
import com.letslearnenglish.minecraftplugin.gui.LearningCalendarGUI;
import com.letslearnenglish.minecraftplugin.gui.MainMenu;
import com.letslearnenglish.minecraftplugin.gui.ProfileGUI;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder.GuiPayload;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder.GuiPayload.SquarePage;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder.GuiPayload.Target;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for English Square, Profile, and Achievement Wall GUI interactions.
 * Uses GuiNavigationManager for back-navigation between these GUIs.
 *
 * 槽位一律读 {@code gui-layout} 配置（默认值与 GUI 渲染时一致），
 * 避免管理员改配置后出现「按钮渲染在 A、监听器判定 B」的静默失效。
 */
public class SocialListener implements Listener {

    private final LetsLearnEnglish plugin;

    /**
     * R-09b: 每位查看者各自的 GUI 快照（按 viewer UUID 分键）。
     *
     * 旧代码用两个全局单例字段存"最后打开的 GUI"：A 开广场后 B 再开，
     * A 的点击会复用 B 的玩家列表（跨玩家串号）。分键后每人只读自己的快照。
     * 退出时清理条目（见 onPlayerQuit），防 Map 无限增长。
     */
    private final Map<UUID, EnglishSquareGUI> squareGuis = new ConcurrentHashMap<>();
    private final Map<UUID, LeaderboardGUI> leaderboardGuis = new ConcurrentHashMap<>();

    /**
     * Wave 2 域 4：每次"动态页"被打开时捕获的 FeedCache 现态（key = viewer）。
     *
     * 动态条目的点击目标必须与"屏幕上显示的那一页"严格一致（三类列表共用同一
     * 28 槽布局，错位 = 点 A 作用到 B；鼓励还没有撤销手段）。但广场有两条打开
     * 路径（/le square 与主菜单广场按钮直达 EnglishSquareGUI.open()）不登记
     * {@link #squareGuis} 快照，且该快照会跨 ESC→重开陈旧；若点击时现读缓存
     * 重建，缓存刷新（写入后 5s 去抖）恰好落在"打开→点击"窗口内就会漂移。
     * 打开事件在主线程与渲染同刻触发，此处取到的 volatile 快照即渲染所用列表
     * ——按 viewer 冻结，点击侧优先消费。只登记动态页（其余模式维持既有快照/
     * 重建口径，不在本域扩面）；quit 时清理，重开即覆盖。
     */
    private final Map<UUID, List<SocialManager.FeedItem>> renderedFeedPages = new ConcurrentHashMap<>();

    /** Player head slots: rows 2-5, skipping first and last columns */
    private static final int[] SQUARE_PLAYER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    /**
     * ProfileGUI / AchievementWallGUI 的返回键槽位。
     *
     * 这两个 GUI 把返回键**写死**在 slot 8，并不读 {@code gui-layout}（旧
     * {@code gui-layout.profile} 段属死配置，已在 MC-7 死键清理中整体移除）。
     * 因此监听器**不能**改成读配置 —— 一旦管理员改了配置，GUI 仍渲染在 8，
     * 监听器却去判定新槽位，反而制造出 W2 那类静默失效。
     * 唯一真相 = GUI 的写死值，这里用常量表达同一份事实。
     */
    private static final int PROFILE_BACK_SLOT = 8;
    private static final int ACHIEVEMENT_WALL_BACK_SLOT = 8;

    /** 广场里「查看者自己的头像」槽位（EnglishSquareGUI 写死 slot 0，无配置项）。 */
    private static final int SQUARE_OWN_HEAD_SLOT = 0;
    /**
     * R-09d 笑话页识别（迁移期兜底）：openHidden 建 27 格小箱子，open 建 54 格大箱子，
     * 标题却完全相同。主路径已改为 holder key（{@link GuiViewKey#SQUARE_HIDDEN}）；
     * 只有未挂 holder 的旧界面才回落到这里的尺寸猜测。Wave 3 随标题兜底删除。
     */
    private static final int HIDDEN_JOKE_GUI_SIZE = 27;
    /** 笑话页药水槽位（openHidden 写死 slot 13，无配置项）。 */
    private static final int HIDDEN_JOKE_POTION_SLOT = 13;
    /** ProfileGUI 的其余固定槽位（GUI 写死，无配置项）。 */
    private static final int PROFILE_CALENDAR_SLOT = 0;
    private static final int PROFILE_ACHIEVEMENT_SLOT = 18;
    private static final int PROFILE_SETTINGS_SLOT = 26;

    public SocialListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * R-09b: 玩家退出时清理其 GUI 快照条目，防 Map 无限增长。
     * （PlayerJoinListener 不在本流白名单内，故在此自包含处理。）
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        squareGuis.remove(id);
        leaderboardGuis.remove(id);
        renderedFeedPages.remove(id);
    }

    /**
     * Wave 2 域 4：动态页打开瞬间冻结其渲染列表（见 {@link #renderedFeedPages}
     * 说明）。只登记 FEED 页；笑话页与其它模式不受影响。
     *
     * <p>R5-4：判定从「标题 contains 英语广场 + 尺寸 > 27 + detectModeFromTitle」
     * 换成 holder 优先（{@link GuiViewKey#SQUARE} 才登记，{@link GuiViewKey#SQUARE_HIDDEN}
     * 直接排除，不再靠尺寸猜），标题解析保留为未挂 holder 的旧界面 fallback。
     */
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryView view = event.getView();
        // 与点击侧同一个解析入口：holder 优先，未挂 holder 的旧界面走标题 + 尺寸兜底。
        GuiViewKey key = resolveSocialKey(view);
        if (key != GuiViewKey.SQUARE && key != GuiViewKey.SQUARE_HIDDEN) return;

        // 切换点插桩：SQUARE_HIDDEN 与 SQUARE 同标题，属于已登记的「标题不可区分」
        // key 对，不会产生噪声告警；只有真正的文案/映射漂移才会打 WARNING。
        GuiIdentity.logMismatch(plugin, view);

        // 笑话页不登记动态快照（它没有分页/条目语义）。
        if (key == GuiViewKey.SQUARE_HIDDEN) return;
        if (resolveSquareMode(player, view) != RankingMode.FEED) return;
        SocialManager sm = plugin.getSocialManager();
        renderedFeedPages.put(player.getUniqueId(),
                (sm != null && sm.isEnabled())
                        ? sm.getFeedSnapshot() : Collections.emptyList());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // R-01/R-22: 只处理上半区（Top 容器）点击。玩家背包（下半区）直接 return
        // 且不 cancel，玩家开着 GUI 也能正常整理背包。
        // R5-4 顺序铁律：先做上半区判定（只依赖 slot 算术），再做身份判定；绝不
        // 「只判 holder 就 cancel」——否则玩家在下半区整理背包会被无端吞掉。
        if (event.getClickedInventory() == null) return;
        InventoryView view = event.getView();
        int topSize = view.getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topSize) return;

        GuiViewKey key = resolveSocialKey(view);
        if (key == null) {
            // 迁移前语义保全：命中 15 字面量白名单、但不在四路分派链里的旧标题
            //（如仅含「周榜/Weekly Ranking」的历史标题）也会被 cancel（档位只吞
            // 不动作）。holder 存在时绝不走这条 —— 那表示界面属于别的 listener。
            if (isLegacySocialClaim(view)) event.setCancelled(true);
            return;
        }

        // 切换点插桩（R5-0 的影子比对）：holder 与标题说法不一致时打一条去重 WARNING。
        GuiIdentity.logMismatch(plugin, view);

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getSlot();
        switch (key) {
            case LEADERBOARD -> handleLeaderboardGUI(player, slot);
            case SQUARE, SQUARE_HIDDEN ->
                    handleEnglishSquareGUI(player, slot, view, key, event.getClick());
            case PROFILE -> handleProfileGUI(player, slot, view);
            case ACHIEVEMENT_WALL -> handleAchievementWall(player, slot);
            default -> {
                // resolveSocialKey 已过滤，不可达
            }
        }
    }

    /**
     * R-55e: 拖拽 guard（抄 DuelGUIListener / MainMenuListener 的上半区模式）。
     *
     * 拖拽是独立事件，点击的 setCancelled(true) 拦不住它 —— 不拦的话
     * 广场/排行榜/主页/成就墙的展示物品（玻璃板/头像）可被拖进玩家背包。
     *
     * R5-4：判定与 onInventoryClick 共用 {@link #resolveSocialKey}（holder 优先、
     * 标题白名单兜底）；只在拖拽触及界面上半部分时取消。
     */
    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        InventoryView view = event.getView();
        GuiViewKey key = resolveSocialKey(view);
        // 与点击侧同一条迁移期语义：recognized 或「旧白名单命中但无分派」都守卫。
        if (key == null && !isLegacySocialClaim(view)) return;

        if (key != null) {
            GuiIdentity.logMismatch(plugin, view);
        }

        int topSize = view.getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * R5-4：解析「本流负责的五个屏幕」的身份键。
     *
     * <p>holder 优先：holder 给出的 key 是权威答案（{@link GuiViewKey#SQUARE_HIDDEN}
     * 与 {@link GuiViewKey#SQUARE} 同标题，只有 holder 能区分）；holder 存在但不是
     * 本流屏幕（如主菜单、隐私设置页）时<b>直接返回 null</b> —— 不再回落标题，
     * 否则「holder 说不是、标题说像」会造成误认领。
     *
     * <p>holder 缺失（旧界面 / 未挂 holder）时才走 {@link #legacyKeyFromTitle}：
     * 它是迁移前 onInventoryClick 的 15 个字面量大 AND 门 + 9 个字面量分派链的
     * 逐字保留，Wave 2 收尾随标题兜底一起删除。
     *
     * @param view 事件界面（非 null）
     * @return 本流屏幕键；不属于本流时返回 null
     */
    private static GuiViewKey resolveSocialKey(InventoryView view) {
        if (view == null) return null;
        GuiViewKey key = GuiIdentity.resolve(view);
        if (key != null) {
            return switch (key) {
                case LEADERBOARD, SQUARE, SQUARE_HIDDEN, PROFILE, ACHIEVEMENT_WALL -> key;
                default -> null;
            };
        }
        return legacyKeyFromTitle(view);
    }

    /**
     * 迁移期标题兜底：holder 缺失时按原 15 字面量白名单 + 原分派链认屏幕。
     *
     * <p>与迁移前逐字等价（顺序、字面量都不动），包含 6 个由 MC-7 清理后已无人产出的
     * 历史字面量（周榜/月榜/总榜/Weekly Ranking/Monthly Ranking/All-Time Ranking）——
     * 保留它们是为了「旧界面仍按旧行为处理」，不是新契约。
     *
     * <p>唯一一处「比迁移前多做一点」：广场分支额外按尺寸把 27 格页识别为
     * {@link GuiViewKey#SQUARE_HIDDEN}（迁移前这一步在 handleEnglishSquareGUI 里做，
     * 效果相同，只是提前到这里，让点击分派与笑话页判定共用同一个 key）。
     *
     * <p>Wave 3 删除本方法及其调用点。
     *
     * @param view 事件界面（非 null）
     * @return 本流屏幕键；不属于本流时返回 null
     */
    private static GuiViewKey legacyKeyFromTitle(InventoryView view) {
        String title = view.getTitle();
        if (!isSocialTitle(title)) return null;
        if (title.contains("Leaderboard") || title.contains("排行榜")) {
            return GuiViewKey.LEADERBOARD;
        }
        if (title.contains("English Square") || title.contains("英语广场")) {
            return view.getTopInventory().getSize() <= HIDDEN_JOKE_GUI_SIZE
                    ? GuiViewKey.SQUARE_HIDDEN : GuiViewKey.SQUARE;
        }
        if (title.contains("'s Profile") || title.contains("的主页")
                || title.contains("个人主页")) {
            return GuiViewKey.PROFILE;
        }
        if (title.contains("Achievement Wall") || title.contains("成就墙")) {
            return GuiViewKey.ACHIEVEMENT_WALL;
        }
        // 只命中周榜/月榜/总榜等历史模式词（无广场/排行榜/主页/成就墙词）：迁移前
        // 这条会先 cancel 点击再掉出四路分派（即「吞掉但什么也不做」），这里返回
        // null 表示「本流不认领」。差异只在「是否 cancel」——而 MC-7 清理后已没有任何
        // GUI 会产出这类标题（这些键只作为广场标题的模式后缀出现，且标题必含
        // 英语广场 / English Square，前面那个分支先命中），故不可达。
        return null;
    }

    /**
     * 迁移前的 15 字面量社交界面白名单（holder 缺失时的兜底）。Wave 3 删除。
     */
    private static boolean isSocialTitle(String title) {
        if (title == null) return false;
        return title.contains("English Square") || title.contains("英语广场")
                || title.contains("周榜") || title.contains("月榜") || title.contains("总榜")
                || title.contains("Weekly Ranking") || title.contains("Monthly Ranking")
                || title.contains("All-Time Ranking")
                || title.contains("Leaderboard") || title.contains("排行榜")
                || title.contains("'s Profile") || title.contains("的主页")
                || title.contains("个人主页")
                || title.contains("Achievement Wall") || title.contains("成就墙");
    }

    /**
     * 迁移期兜底：无 holder 且标题落在旧白名单里，但解析不出四路分派 key 时，
     * 迁移前会「cancel 但不动作」。保留这一 cancel 语义（Wave 3 删除）。
     *
     * <p>有 holder 的界面永不走这里 —— holder 说「不是本流屏幕」就是不是。
     */
    private static boolean isLegacySocialClaim(InventoryView view) {
        return view != null && GuiIdentity.resolve(view) == null
                && isSocialTitle(view.getTitle());
    }

    /**
     * R5-4：广场当前页码 —— payload 优先（{@link SquarePage#page()}），
     * payload 缺失/类型不符时回退迁移前的标题解析
     * （{@link GUIUtils#extractPageFromTitle}）。
     */
    private static int resolveSquarePage(InventoryView view) {
        SquarePage payload = squarePageOf(view);
        if (payload != null) return payload.page();
        return GUIUtils.extractPageFromTitle(view.getTitle());
    }

    /**
     * R5-4：广场当前模式 —— payload 优先（{@link SquarePage#modeKey()}），
     * payload 缺失/未知 modeKey 时回退迁移前的标题后缀识别
     * （{@link #detectModeFromTitle}）。
     */
    private RankingMode resolveSquareMode(Player viewer, InventoryView view) {
        SquarePage payload = squarePageOf(view);
        if (payload != null) {
            RankingMode fromPayload = modeFromKey(payload.modeKey());
            if (fromPayload != null) return fromPayload;
        }
        return detectModeFromTitle(viewer, view.getTitle());
    }

    /** holder payload 是 SQUARE 的 {@link SquarePage} 时返回它，否则 null。 */
    private static SquarePage squarePageOf(InventoryView view) {
        GuiPayload payload = GuiIdentity.payloadOf(view);
        return (payload instanceof SquarePage page) ? page : null;
    }

    /** payload 模式键 → {@link RankingMode}；未知键返回 null（交调用方回退标题）。 */
    private static RankingMode modeFromKey(String modeKey) {
        if (modeKey == null) return null;
        return switch (modeKey) {
            case "weekly" -> RankingMode.WEEKLY;
            case "monthly" -> RankingMode.MONTHLY;
            case "alltime" -> RankingMode.ALL_TIME_COINS;
            case "feed" -> RankingMode.FEED;
            case "all" -> RankingMode.ALL_PLAYERS;
            default -> null;
        };
    }

    /**
     * R5-4：主页归属 —— payload 优先（{@link Target#targetId()}），
     * payload 缺失时回退迁移前的「最长玩家名子串」反查
     * （{@link #extractTargetIdFromTitle}）。
     */
    private UUID resolveTargetId(Player viewer, InventoryView view) {
        GuiPayload payload = GuiIdentity.payloadOf(view);
        if (payload instanceof Target target) return target.targetId();
        return extractTargetIdFromTitle(viewer, view.getTitle());
    }

    private void handleEnglishSquareGUI(Player player, int slot, InventoryView view,
                                        GuiViewKey key, ClickType clickType) {
        ConfigManager cm = plugin.getConfigManager();

        // R-09d / R5-4: 笑话页与广场主页同标题 —— 分流完全由 key 决定：
        // holder 命中 SQUARE_HIDDEN 即是笑话页；旧界面（无 holder）由
        // legacyKeyFromTitle 按「27 格」提前折算成 SQUARE_HIDDEN。本方法内不再
        // 二次猜尺寸（尺寸猜测只在 legacyKeyFromTitle 的那一处保留）。
        // 笑话页只有返回键 + 中央药水：药水只发一行提示，绝不打开任何玩家主页。
        if (key == GuiViewKey.SQUARE_HIDDEN) {
            int jokeBackSlot = cm.getGuiSlot("english-square", "back-button", 8);
            if (slot == jokeBackSlot) {
                SoundManager.playSound(player, "menu-click");
                squareGuis.remove(player.getUniqueId());
                // F3-02（行为变更，红队收口）：原先直达主菜单（旧行为）——但所有进入笑话页
                // 的上游路径（广场头像/榜单头像/动态右键，或经主页进入后再点成就墙）此前
                // 都已 pushBack 压入"来源界面"帧，直开主菜单会把这帧留成栈残留，被后续
                // 无关界面的返回误消费。现改走 popBack 消费该帧：返回 = 回来源界面
                //（页码/模式/归属由帧闭包保真）；fallback = 主菜单，覆盖帧缺失窗口
                //（quit 清栈等）。若产品将来定"笑话页返回应恒到主菜单"，需同步调整
                // 上游调用点不再 pushBack，而不是退回残留帧写法。
                Runnable backAction = plugin.getGuiNavigationManager()
                        .popBack(player.getUniqueId(), () -> new MainMenu(plugin).open(player));
                backAction.run();
            } else if (slot == HIDDEN_JOKE_POTION_SLOT) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "square.hidden-lore"));
            }
            return;
        }

        GuiNavigationManager nav = plugin.getGuiNavigationManager();
        PlayerDataStore store = plugin.getPlayerDataStore();

        // 槽位一律读 gui-layout 配置（EnglishSquareGUI 渲染时也读同一份配置）
        int backSlot = cm.getGuiSlot("english-square", "back-button", 8);
        int prevSlot = cm.getGuiSlot("english-square", "prev-page", 47);
        int nextSlot = cm.getGuiSlot("english-square", "next-page", 51);
        int modeWeeklySlot = cm.getGuiSlot("english-square", "mode-weekly", 2);
        int modeMonthlySlot = cm.getGuiSlot("english-square", "mode-monthly", 3);
        int modeAllTimeSlot = cm.getGuiSlot("english-square", "mode-alltime", 4);
        int modeAllSlot = cm.getGuiSlot("english-square", "mode-all", 5);
        // Wave 2 域 4：动态模式按钮（EnglishSquareGUI 渲染同键同默认 6）
        int modeFeedSlot = cm.getGuiSlot("english-square", "mode-feed", 6);

        // Determine mode and player list early (needed for slot 0 and head clicks)
        // R-09b: 每人只读自己的快照 —— 别人的列表与自己看到的页/模式完全无关。
        //
        // Wave 2 域 4 修正：plan §3.3 的 "FEED 界面打开即登记快照" 对 /le square
        // （默认落点已是 FEED）与主菜单广场按钮不成立——两者直达 open() 不登记
        // 快照；且快照会陈旧（ESC 关界面不清、/le square 重开不换）。三类列表
        // （全量玩家/榜单/动态条目）共用同一套 28 槽布局，用错列表路由 = 点击
        // "看到的是 A、作用到 B"。因此：
        //   ① FEED 页一律以打开瞬间冻结的渲染列表为准（renderedFeedPages，
        //      见其字段说明——与屏幕严格一致，杜绝"打开→点击"窗口内缓存刷新
        //      导致的条目漂移；鼓励无撤销手段，错位不可接受）；
        //   ② 其余模式按 holder payload 的 modeKey 识别（payload 缺失才回退
        //      detectModeFromTitle 标题后缀）：与快照一致时信任快照（零重建），
        //      不一致/无快照时按显示的模式重建。
        RankingMode currentMode = resolveSquareMode(player, view);
        List<UUID> playerIds;
        List<SocialManager.FeedItem> feedItems;
        EnglishSquareGUI myGui = squareGuis.get(player.getUniqueId());
        if (currentMode == RankingMode.FEED) {
            playerIds = new ArrayList<>();
            List<SocialManager.FeedItem> frozen = renderedFeedPages.get(player.getUniqueId());
            if (frozen != null) {
                feedItems = frozen;
            } else if (myGui != null && myGui.getCurrentMode() == RankingMode.FEED) {
                // 冻结缺失容错：快照携带的渲染列表是次优真相
                feedItems = myGui.getCurrentFeedItems();
            } else {
                // 末位容错：现读缓存（social 门关闭时为空列表，条目点击由越界保护吞掉）
                SocialManager sm = plugin.getSocialManager();
                feedItems = (sm != null && sm.isEnabled())
                        ? sm.getFeedSnapshot() : Collections.emptyList();
            }
        } else if (myGui != null && myGui.getCurrentMode() == currentMode) {
            playerIds = myGui.getCurrentPlayerIds();
            feedItems = myGui.getCurrentFeedItems();
        } else {
            feedItems = Collections.emptyList();
            if (currentMode == RankingMode.ALL_PLAYERS) {
                // 经典"广场"全量页（无模式后缀）→ 维持既有重建口径
                List<UUID> raw = store.getActivePlayerIds();
                playerIds = new ArrayList<>();
                for (UUID id : raw) {
                    // R-37 fan-in：无快照兜底重建分支补隐身过滤（只读，不写副作用），与主装配口径一致
                    if (!store.getProfileSettingsReadOnly(id).isShowInSquare()) continue;
                    PlayerDataStore.PlayerStats s = store.getPlayerStats(id);
                    if (s.getTotalCorrect() + s.getTotalIncorrect() > 0) {
                        playerIds.add(id);
                    }
                }
            } else {
                // 排名模式（/le square weekly|monthly|alltime 直达的无快照窗口）：
                // 与 open() 同源重建（有界，display-count 行；本窗口只在无/陈旧
                // 快照的首次点击前存在，任一点击登记后即关闭）
                int displayCount = cm.getMainConfig()
                        .getInt("progress-system.leaderboard.display-count", 10);
                var entries = plugin.getProgressManager().getLeaderboard()
                        .getTopPlayers(currentMode, displayCount);
                playerIds = new ArrayList<>();
                for (var e : entries) {
                    playerIds.add(e.playerId);
                }
            }
        }
        // R5-4：页码来源由标题第一个整数改为 holder payload（SquarePage.page），
        // 取不到才回退 GUIUtils.extractPageFromTitle。后续 P1-D 的
        // globalIndex = (page-1)*28 + slotIdx 公式<b>逐字不变</b> —— 只换了 page 的来源。
        int page = resolveSquarePage(view);

        if (slot == backSlot) {
            SoundManager.playSound(player, "menu-click");
            squareGuis.remove(player.getUniqueId());
            new MainMenu(plugin).open(player);
            return;
        }
        // Viewer's own head at slot 0 — open own profile
        if (slot == SQUARE_OWN_HEAD_SLOT) {
            SoundManager.playSound(player, "menu-click");
            int cp = page;
            RankingMode cmode = currentMode;
            nav.pushBack(player.getUniqueId(), () -> {
                EnglishSquareGUI backGui = new EnglishSquareGUI(plugin);
                backGui.open(player, cp, cmode);
                squareGuis.put(player.getUniqueId(), backGui);
            });
            new ProfileGUI(plugin).open(player, player.getUniqueId());
            return;
        }
        // Mode toggle buttons（槽位读配置，逐个分支判定，避免全部塌成同一个数字）
        RankingMode toggledMode = null;
        if (slot == modeWeeklySlot) {
            toggledMode = RankingMode.WEEKLY;
        } else if (slot == modeMonthlySlot) {
            toggledMode = RankingMode.MONTHLY;
        } else if (slot == modeAllTimeSlot) {
            toggledMode = RankingMode.ALL_TIME_COINS;
        } else if (slot == modeAllSlot) {
            toggledMode = RankingMode.ALL_PLAYERS;
        } else if (slot == modeFeedSlot) {
            toggledMode = RankingMode.FEED;
        }
        if (toggledMode != null) {
            // MC-2（S-H）：榜单门（progress-system.enabled && leaderboard.enabled）关闭时，
            // 拒绝周/月/总三榜切换（上面的三个排名模式分支 + 渲染侧灰玻璃占位槽的点击都汇到
            // 这里）；回 module.disabled。"广场"全量模式与 Wave 2 的 FEED 都不是榜单，不吃本门。
            if (toggledMode != RankingMode.ALL_PLAYERS && toggledMode != RankingMode.FEED
                    && !cm.isLeaderboardEnabled()) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "module.disabled"));
                return;
            }
            // Wave 2 域 4：social 门关闭时拒绝动态切换（覆盖"关开关前已打开界面"的
            // 存量点击 / 旧界面残留的 feed 槽位；渲染侧对应不渲染动态按钮）。
            if (toggledMode == RankingMode.FEED && !cm.isSocialEnabled()) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "module.disabled"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            EnglishSquareGUI newGui = new EnglishSquareGUI(plugin);
            newGui.open(player, 1, toggledMode);
            squareGuis.put(player.getUniqueId(), newGui);
            return;
        }

        if (slot == prevSlot) {
            SoundManager.playSound(player, "menu-click");
            EnglishSquareGUI prevGui = new EnglishSquareGUI(plugin);
            prevGui.open(player, Math.max(1, page - 1), currentMode);
            squareGuis.put(player.getUniqueId(), prevGui);
        } else if (slot == nextSlot) {
            SoundManager.playSound(player, "menu-click");
            EnglishSquareGUI nextGui = new EnglishSquareGUI(plugin);
            nextGui.open(player, page + 1, currentMode);
            squareGuis.put(player.getUniqueId(), nextGui);
        } else if (currentMode == RankingMode.FEED) {
            // Wave 2 域 4：动态条目区的点击独立分流（plan-social §3.3）。禁止把 FEED
            // 的 slot 映射进 playerIds —— 条目 index 按 getCurrentFeedItems() 反查。
            handleFeedSlot(player, slot, page, clickType, feedItems);
        } else {
            int slotIdx = getPlayerSlotIndexOnSquare(slot);
            if (slotIdx < 0) return;
            int globalIndex = (page - 1) * getPlayersPerPageOnSquare() + slotIdx;
            if (globalIndex < 0 || globalIndex >= playerIds.size()) return;

            UUID targetId = playerIds.get(globalIndex);
            SoundManager.playSound(player, "menu-click");
            int cp = page;
            RankingMode cmode = currentMode;
            nav.pushBack(player.getUniqueId(), () -> {
                EnglishSquareGUI backGui = new EnglishSquareGUI(plugin);
                backGui.open(player, cp, cmode);
                squareGuis.put(player.getUniqueId(), backGui);
            });
            PlayerDataStore.ProfileSettings ps = store.getProfileSettings(targetId);
            if (ps.isShowInSquare()) {
                new ProfileGUI(plugin).open(player, targetId);
            } else {
                new EnglishSquareGUI(plugin).openHidden(player, targetId);
            }
        }
    }

    private void handleProfileGUI(Player player, int slot, InventoryView view) {
        GuiNavigationManager nav = plugin.getGuiNavigationManager();

        if (slot == PROFILE_CALENDAR_SLOT) {
            // 过往学习按钮：需要知道主页归属才能正确「返回」。
            // R5-4：归属改由 holder payload（Target）提供；旧界面才回退标题反查。
            UUID targetId = resolveTargetId(player, view);
            if (targetId == null) return;
            UUID finalTargetId = targetId;
            // R4-M2 隐私门（三层，顺序照 fix-plan-c2 §C2-B/B2）：他人日历=学习结果
            // 类数据，查看须过目标 showResults 门（与 FeedTextFormatter SESSION_DONE
            // detail 降级同口径）。被拒路径不放行也不压返回帧（F4-04 纪律：压帧会让
            // 笑话页/拒绝页的返回立刻重开刚被拦下的界面）。
            if (!targetId.equals(player.getUniqueId())) {
                PlayerDataStore store = plugin.getPlayerDataStore();
                var ps = store.getProfileSettingsReadOnly(targetId);
                if (!ps.isShowInSquare()) {
                    // 隐身者 → 笑话页（纵深同款；陈旧主页入口窗口防绕过）
                    new EnglishSquareGUI(plugin).openHidden(player, targetId);
                    return;
                }
                if (!ps.isShowResults()) {
                    // 人在广场可见、只是不公开学习结果 → 拒绝打开 + 提示
                    SoundManager.playSound(player, "menu-click");
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "calendar.not-public"));
                    return;
                }
            }
            SoundManager.playSound(player, "menu-click");
            nav.pushBack(player.getUniqueId(),
                    () -> new ProfileGUI(plugin).open(player, finalTargetId));
            new LearningCalendarGUI(plugin).openAsync(player, finalTargetId);
            return;
        }
        if (slot == PROFILE_BACK_SLOT) {
            SoundManager.playSound(player, "menu-click");
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new MainMenu(plugin).open(player));
            backAction.run();
        } else if (slot == PROFILE_ACHIEVEMENT_SLOT) {
            // 模块门（旅程验收收口）：渲染侧 ProfileGUI 已按 achievements 门隐藏按钮，
            // 本处覆盖"关闭前已打开的主页残留按钮"点击（渲染与点击同源 gating 闭环）。
            if (!plugin.getConfigManager().isAchievementsEnabled()) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "module.disabled"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            UUID targetId = resolveTargetId(player, view);
            if (targetId != null) {
                // F4-04 纵深：主页入口已有 showInSquare 守卫（广场/榜单/动态/命令），
                // 但"打开主页时可见 → 查看期间主人改隐身"的陈旧界面窗口可绕过——
                // 成就墙点击前复核一次。口径与广场/榜单点击分支逐字同款：看自己不受
                // 影响（targetId == 查看者时放行），隐身者进 openHidden 笑话页。
                PlayerDataStore store = plugin.getPlayerDataStore();
                if (targetId.equals(player.getUniqueId())
                        || store.getProfileSettings(targetId).isShowInSquare()) {
                    UUID backToTarget = targetId;
                    // 返回帧只在放行路径压入（成就墙返回键消费它回本人主页）。
                    // 隐身路径不压 —— 否则笑话页返回（F3-02 popBack）会立刻重开刚被
                    // 守卫拦下的隐身主页，纵深被自身返回路径绕过；此时笑话页返回落在
                    // 更下层来源帧（如广场）或 fallback 主菜单。
                    nav.pushBack(player.getUniqueId(),
                            () -> new ProfileGUI(plugin).open(player, backToTarget));
                    new AchievementWallGUI(plugin).open(player, targetId);
                } else {
                    new EnglishSquareGUI(plugin).openHidden(player, targetId);
                }
            }
        } else if (slot == PROFILE_SETTINGS_SLOT) {
            // 设置只能改自己的：主页归属解析不出、或不是本人时，忽略这次点击
            // （别人主页的 slot 26 是「设置已锁定」灰色玻璃板）
            UUID targetId = resolveTargetId(player, view);
            if (targetId != null && targetId.equals(player.getUniqueId())) {
                SoundManager.playSound(player, "menu-click");
                // R-86b：进设置前把「本人主页」帧压入返回栈 —— 设置页返回时弹回本人主页，
                // 不再绕栈直开（直开会把旧帧留给下一次返回误用，屏幕位置与栈脱节）。
                nav.pushBack(player.getUniqueId(),
                        () -> new ProfileGUI(plugin).open(player, player.getUniqueId()));
                new ProfileGUI(plugin).openSettings(player);
            }
        }
    }

    private void handleAchievementWall(Player player, int slot) {
        if (slot == ACHIEVEMENT_WALL_BACK_SLOT) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            Runnable backAction = nav.popBack(player.getUniqueId(),
                    () -> new ProfileGUI(plugin).open(player, player.getUniqueId()));
            backAction.run();
        }
    }

    /**
     * F3-01 收口（旅程验收）：命令入口（{@code /le leaderboard}）与主菜单直达——
     * 打开榜单 GUI 的同时登记 {@link #leaderboardGuis} 快照。此前命令直开不登记，
     * 玩家点头像会静默无反应（点击侧 {@code handleLeaderboardGUI} 要求快照非空），
     * 先点任意 tab 后才恢复。本方法为该路径的唯一补登记点（GUI 打开与快照同刻，
     * 主线程调用）。
     */
    public void openLeaderboardWithSnapshot(Player player) {
        LeaderboardGUI gui = new LeaderboardGUI(plugin);
        gui.open(player);
        leaderboardGuis.put(player.getUniqueId(), gui);
    }

    /**
     * R4-M1：命令/主菜单直达入口 —— 打开广场 GUI 的同时登记 {@link #squareGuis}
     * 快照（照 openLeaderboardWithSnapshot F3-01 先例；mode 可为 null=默认落点）。
     * 登记后 SocialListener 点击侧信任快照，直达窗口期零重建。
     */
    public void openSquareWithSnapshot(Player player, RankingMode mode) {
        EnglishSquareGUI gui = new EnglishSquareGUI(plugin);
        gui.open(player, 1, mode);
        squareGuis.put(player.getUniqueId(), gui);
    }

    private void handleLeaderboardGUI(Player player, int slot) {
        GuiNavigationManager nav = plugin.getGuiNavigationManager();
        ConfigManager cm = plugin.getConfigManager();

        // 槽位与 LeaderboardGUI 渲染时读同一份 gui-layout 配置（默认值与 GUI 常量一致），
        // 否则管理员改了配置就会出现「标签渲染在 A、监听器判定 B」的点击失效
        int backSlot = cm.getGuiSlot("leaderboard", "back-button", LeaderboardGUI.BACK_SLOT);
        int tabCoinsSlot = cm.getGuiSlot("leaderboard", "tab-coins", LeaderboardGUI.TAB_COINS_SLOT);
        int tabWordsSlot = cm.getGuiSlot("leaderboard", "tab-words", LeaderboardGUI.TAB_WORDS_SLOT);
        int tabComboSlot = cm.getGuiSlot("leaderboard", "tab-combo", LeaderboardGUI.TAB_COMBO_SLOT);

        // Back button
        if (slot == backSlot) {
            SoundManager.playSound(player, "menu-click");
            leaderboardGuis.remove(player.getUniqueId());
            new MainMenu(plugin).open(player);
            return;
        }

        // Tab buttons（逐分支映射 tab 下标，不能直接拿 slot 当下标 —— 槽位可被配置改掉）
        int tab = -1;
        if (slot == tabCoinsSlot) {
            tab = 0;
        } else if (slot == tabWordsSlot) {
            tab = 1;
        } else if (slot == tabComboSlot) {
            tab = 2;
        } else if (slot == cm.getGuiSlot("leaderboard", "tab-season", LeaderboardGUI.TAB_SEASON_SLOT)) {
            // Wave 3（3A-4 fan-in）：赛季 tab 点击分支。tester L7 收口：门序与其它
            // tab 一致——榜单模块门优先（回 module.disabled），赛季可用性其次
            // （回 season.disabled）；两门均与渲染侧同读同键同判据，无漂移面。
            if (!cm.isLeaderboardEnabled()) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "module.disabled"));
                return;
            }
            if (!com.letslearnenglish.minecraftplugin.core.season.SeasonManager
                    .isAvailable(plugin.getSeasonManager())) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "season.disabled"));
                return;
            }
            tab = LeaderboardGUI.SEASON_TAB_INDEX;
        }
        if (tab >= 0) {
            // MC-2（S-H）：榜单门关闭时拒绝 tab 切换（tab-coins/tab-words/tab-combo
            // 三个分支汇于此；覆盖"关开关前已打开榜单界面"的存量点击），回 module.disabled。
            if (!cm.isLeaderboardEnabled()) {
                SoundManager.playSound(player, "menu-click");
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "module.disabled"));
                return;
            }
            SoundManager.playSound(player, "menu-click");
            LeaderboardGUI tabGui = new LeaderboardGUI(plugin);
            tabGui.open(player, tab);
            leaderboardGuis.put(player.getUniqueId(), tabGui);
            return;
        }

        // Player head slots
        int entryIndex = getLeaderboardSlotIndex(slot);
        if (entryIndex < 0) return;

        // R-09b: 只读自己的快照
        LeaderboardGUI gui = leaderboardGuis.get(player.getUniqueId());
        if (gui == null) return;

        List<UUID> playerIds = gui.getDisplayedPlayerIds();
        if (entryIndex >= playerIds.size()) return;

        UUID targetId = playerIds.get(entryIndex);
        SoundManager.playSound(player, "menu-click");
        int currentTab = gui.getActiveTab();
        nav.pushBack(player.getUniqueId(), () -> {
            LeaderboardGUI backGui = new LeaderboardGUI(plugin);
            backGui.open(player, currentTab);
            leaderboardGuis.put(player.getUniqueId(), backGui);
        });
        // R-05：与广场点击分支同语义 —— 看别人主页先查 showInSquare，
        // 隐身者进 openHidden 笑话页；看自己主页不受影响。
        // R4-L1：榜单头像点击是纯读判定 —— 走 getProfileSettingsReadOnly 只读快照，
        // 不把目标玩家拉进本机缓存（全仓 10+ 消费点同款；与动态条目 :721 分支一致）。
        if (targetId.equals(player.getUniqueId())) {
            new ProfileGUI(plugin).open(player, targetId);
        } else if (plugin.getPlayerDataStore().getProfileSettingsReadOnly(targetId).isShowInSquare()) {
            new ProfileGUI(plugin).open(player, targetId);
        } else {
            new EnglishSquareGUI(plugin).openHidden(player, targetId);
        }
    }

    private int getLeaderboardSlotIndex(int slot) {
        int[] entrySlots = LeaderboardGUI.ENTRY_SLOTS;
        for (int i = 0; i < entrySlots.length; i++) {
            if (entrySlots[i] == slot) return i;
        }
        return -1;
    }

    private int getPlayerSlotIndexOnSquare(int slot) {
        for (int i = 0; i < SQUARE_PLAYER_SLOTS.length; i++) {
            if (SQUARE_PLAYER_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    /**
     * 从广场标题的 " | 模式后缀" 识别当前显示的模式。
     *
     * <p><b>R5-4 起本方法降级为 fallback</b>：主路径是 holder payload 的 modeKey
     * （{@link #resolveSquareMode}）。只有「未挂 holder 的旧界面」才会走到这里。
     * Wave 3 随标题兜底一起删除。
     *
     * <p>EnglishSquareGUI 渲染的五种模式中四种带后缀（周榜/月榜/总榜/动态，见其
     * open() 的标题 switch）；"广场"全量模式无后缀——识别不出时返回
     * {@link RankingMode#ALL_PLAYERS}。
     *
     * <p>为什么需要它：/le square [mode]（含默认落点 FEED）与主菜单广场按钮
     * 直达 {@code EnglishSquareGUI.open()}，不经本监听器登记快照；快照还会陈旧
     * （ESC 关界面不清、下次直达重开不换）。三类列表（全量玩家/榜单/动态条目）
     * 共用同一套 28 槽布局，用错列表路由 = 点击"看到的是 A、作用到 B"。标题
     * 后缀是显示的真相，据此选择信任快照还是重建。
     *
     * <p>匹配口径：标签经与渲染侧相同的颜色码转换后子串匹配；messages 缺键时
     * 两侧取到同一个 "[?key]" 占位（getGuiMsg），判定仍自洽（W2-B 同款窗口）。
     * 注意不可检测 "广场" 标签——它是基础标题"英语广场"的子串，永远命中。
     */
    private RankingMode detectModeFromTitle(Player viewer, String title) {
        String lang = plugin.getPlayerLanguage(viewer);
        FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        if (titleMatchesLabel(title, messages, "square.feed.mode-label")) {
            return RankingMode.FEED;
        }
        if (titleMatchesLabel(title, messages, "leaderboard.mode-weekly")) {
            return RankingMode.WEEKLY;
        }
        if (titleMatchesLabel(title, messages, "leaderboard.mode-monthly")) {
            return RankingMode.MONTHLY;
        }
        if (titleMatchesLabel(title, messages, "leaderboard.mode-alltime")) {
            return RankingMode.ALL_TIME_COINS;
        }
        return RankingMode.ALL_PLAYERS;
    }

    private boolean titleMatchesLabel(String title, FileConfiguration messages, String key) {
        if (title == null) return false;
        String label = MessageUtil.getGuiMsg(messages, key);
        return title.contains(ChatColor.translateAlternateColorCodes('&', label));
    }

    /**
     * 动态条目区（FEED 模式 28 槽）的点击分流（plan-social §3.3）。
     *
     * <p>左键：别人的条目 = 鼓励（成功回调里重开当前页实现"lore 即时更新"
     * ——plan §2.2 GUI 即时性，同时接 helpful 成就判定）；自己的条目 = 聊天
     * 列出鼓励者名单。右键：打开作者主页（隐身者的陈旧条目 → 既有 openHidden
     * 笑话页守卫，pushBack 闭包捕获当前页与 FEED 模式原样返回）。
     *
     * <p>条目 index 按渲染快照 {@code getCurrentFeedItems()} 反查——绝不映射进
     * playerIds（三类列表的槽位布局相同但元素完全不同）。
     */
    private void handleFeedSlot(Player player, int slot, int page, ClickType clickType,
                                List<SocialManager.FeedItem> feedItems) {
        int slotIdx = getPlayerSlotIndexOnSquare(slot);
        if (slotIdx < 0) return;
        int globalIndex = (page - 1) * getPlayersPerPageOnSquare() + slotIdx;
        // 越界保护照旧（快照为空/条目已清时静默吞掉：陈旧界面的点击无副作用）
        if (globalIndex < 0 || globalIndex >= feedItems.size()) return;

        SocialManager socialManager = plugin.getSocialManager();
        if (socialManager == null || !socialManager.isEnabled()) {
            // social 门关闭（覆盖"关开关前已打开的存量界面"/陈旧快照的点击）；
            // 与模式切换分支同款反馈（音效 + module.disabled）
            SoundManager.playSound(player, "menu-click");
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "module.disabled"));
            return;
        }
        SocialManager.FeedItem item = feedItems.get(globalIndex);

        // 右键 = 看作者主页（与既有玩家头点击同款：隐身 → openHidden 笑话页）
        if (clickType == ClickType.RIGHT || clickType == ClickType.SHIFT_RIGHT) {
            SoundManager.playSound(player, "menu-click");
            GuiNavigationManager nav = plugin.getGuiNavigationManager();
            int cp = page;
            nav.pushBack(player.getUniqueId(), () -> {
                EnglishSquareGUI backGui = new EnglishSquareGUI(plugin);
                backGui.open(player, cp, RankingMode.FEED);
                squareGuis.put(player.getUniqueId(), backGui);
            });
            PlayerDataStore store = plugin.getPlayerDataStore();
            if (item.authorId().equals(player.getUniqueId())) {
                new ProfileGUI(plugin).open(player, item.authorId());
            } else if (store.getProfileSettingsReadOnly(item.authorId()).isShowInSquare()) {
                new ProfileGUI(plugin).open(player, item.authorId());
            } else {
                // 点击守卫（plan §1.6 第③层，只读版不建缓存条目）：作者已隐身 → 笑话页
                new EnglishSquareGUI(plugin).openHidden(player, item.authorId());
            }
            return;
        }
        // 其余点击类型（数字键/丢弃键/中键等）不映射动作：鼓励要写库且无撤销手段，
        // 不接受"意外点击类型意外点赞"（plan §2.1B 只定义左键/右键两种语义）
        if (clickType != ClickType.LEFT && clickType != ClickType.SHIFT_LEFT) {
            return;
        }

        // 左键：自己的条目 = 看鼓励名单（聊天输出，不入返回栈）；别人的 = 鼓励
        if (item.authorId().equals(player.getUniqueId())) {
            SoundManager.playSound(player, "menu-click");
            socialManager.getCheerersAsync(player.getUniqueId(), item.id(), names -> {
                if (!player.isOnline()) {
                    return; // 异步回调窗口内玩家已退服
                }
                if (names == null || names.isEmpty()) {
                    player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                            "square.feed.cheerers-none"));
                    return;
                }
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "square.feed.cheerers",
                        "names", String.join(", ", names),
                        "count", String.valueOf(names.size())));
            });
            return;
        }
        // 鼓励成功音效由 SocialManager 统一播放（correct 键），此处不叠 click 音
        socialManager.cheer(player, item.id(), () -> {
            // Wave 2 域 4 × P3-3 互助成就（plan-social §4.3 冻结接口）：鼓励成功后
            // 由 listener 层编排成就判定，SocialManager 不反向依赖成就域。
            // 与视图无关（判据是 DB 里的鼓励行），离开动态页/离线也照常判
            //（tryUnlock 的通知段自带在线复检）。
            plugin.getAchievementManager().checkHelpAchievement(player.getUniqueId());
            if (!player.isOnline() || !isViewingFeedPage(player)) {
                return; // 异步写窗口内玩家已退服/已导航离开——不把界面拽回动态页
            }
            // 成功回调（主线程）：当前 FEED 页重开——缓存 cheer_count 已乐观 +1，
            // 重渲染即见"lore 即时更新"（plan §2.2 GUI 即时性）。页号取当前界面的
            // holder payload（窗口内玩家可能已翻页，拽回旧页反而是倒退）；payload
            // 缺失（旧界面）才回退标题解析。重开同时登记快照。
            int currentPage = resolveSquarePage(player.getOpenInventory());
            EnglishSquareGUI refreshed = new EnglishSquareGUI(plugin);
            refreshed.open(player, currentPage, RankingMode.FEED);
            squareGuis.put(player.getUniqueId(), refreshed);
        });
    }

    /**
     * 玩家当前打开的界面是否仍停在动态页（cheer 异步回调的"未被拽走"守卫）。
     *
     * <p>R5-4：与点击侧共用 {@link #resolveSocialKey}（holder 优先、标题 + 尺寸兜底），
     * 只认 {@link GuiViewKey#SQUARE} + payload modeKey=="feed"。笑话页
     * （{@link GuiViewKey#SQUARE_HIDDEN}）、其它插件界面与玩家背包一律 false。
     * 迁移前这里是「尺寸 > 27 + 标题含广场 + 标题后缀说 FEED」三段判定，等价语义
     * 已全部收进 resolveSocialKey + resolveSquareMode 的兜底分支。
     */
    private boolean isViewingFeedPage(Player player) {
        InventoryView view = player.getOpenInventory();
        if (resolveSocialKey(view) != GuiViewKey.SQUARE) {
            return false;
        }
        return resolveSquareMode(player, view) == RankingMode.FEED;
    }

    private int getPlayersPerPageOnSquare() {
        return SQUARE_PLAYER_SLOTS.length;
    }

    /**
     * 从主页标题反查主页归属玩家。
     *
     * <p><b>R5-4 起本方法降级为 fallback</b>：主路径是 holder payload 的
     * {@link Target#targetId()}（{@link #resolveTargetId}）。只有「未挂
     * holder 的旧界面」才会走到这里。Wave 3 随标题兜底一起删除。
     *
     * 标题形如 {@code xxx的主页} / {@code xxx的个人主页} / {@code xxx's Profile}，
     * 需要从缓存里反查出 UUID。
     *
     * 规则：
     * 1. 取**最长的匹配名字** —— 昵称互为前缀时（如 Bob / Bobby），
     *    短名会命中长名的主页标题，只取第一个命中就会把操作作用到错误的玩家身上。
     * 2. 候选集合 = PlayerDataStore 的 profile 缓存 + 查看者自己快照里展示过的玩家 ——
     *    （R-09b：只补自己的快照，别人的展示列表与这张主页无关）
     *    玩家在你看他主页的期间退出（QuitEvent 会 unloadProfileSettings 把对方从缓存摘掉）时，
     *    补上展示列表仍能反查出正确的人，而不是解析失败。
     * 3. 解析不出来时返回 {@code null}（而不是静默回落到查看者自己）——
     *    回落会让「查看别人主页」时点击返回/设置等操作**作用在查看者本人身上**，
     *    可能误改自己的隐私设置。调用方必须显式处理 null。
     *
     * @return 匹配到的玩家 UUID；标题里没有任何已知玩家名时返回 null
     */
    private UUID extractTargetIdFromTitle(Player viewer, String title) {
        PlayerDataStore store = plugin.getPlayerDataStore();

        // 候选集合：profile 缓存里的玩家优先，再补上查看者自己快照里的玩家
        LinkedHashSet<UUID> candidates = new LinkedHashSet<>(store.getActivePlayerIds());
        LeaderboardGUI myBoard = leaderboardGuis.get(viewer.getUniqueId());
        if (myBoard != null) {
            candidates.addAll(myBoard.getDisplayedPlayerIds());
        }
        EnglishSquareGUI mySquare = squareGuis.get(viewer.getUniqueId());
        if (mySquare != null) {
            candidates.addAll(mySquare.getCurrentPlayerIds());
        }

        UUID bestMatch = null;
        int bestLength = 0;
        for (UUID id : candidates) {
            String name = store.getPlayerName(id);
            if (name == null || name.isEmpty()) continue;
            if (title.contains(name) && name.length() > bestLength) {
                bestMatch = id;
                bestLength = name.length();
            }
        }

        if (bestMatch == null) {
            // 标题里的玩家一个候选都没匹配上 —— 不猜，交给调用方处理
            plugin.getLogger().warning("无法从 GUI 标题解析主页归属玩家: \"" + title
                    + "\" (viewer=" + viewer.getName() + ")");
            return null;
        }
        return bestMatch;
    }
}
