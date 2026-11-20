package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * 成就墙（plan-achievements §2.1/§2.2/§3.6，US-ACH-4）。
 *
 * 信息架构：单页 54 格、28 个内容格（slot 10-16 / 19-25 / 28-34 / 37-43），
 * 22 个成就按 5 个类别稳定排序（enum ordinal 分组），类别名作为每个条目
 * lore 第一行——不用 header 格（22+5=27 贴顶且脆弱，见规划 §2.1 裁决）。
 *
 * 标题附带计数"成就墙 (n/22)"。R5-4 起 {@code SocialListener} 的身份判定走
 * {@code PluginGuiHolder}（{@link GuiViewKey#ACHIEVEMENT_WALL}），不再解析标题，
 * 计数后缀因此彻底退出判定路径；R5-14/R5-16 起标题层（标题兜底与
 * {@code GUIUtils.isPluginGUI} 白名单）已整体删除，标题中保留「成就墙 / Achievement Wall」
 * 词元仅为文案可读性，不承担任何身份职责。
 *
 * <h2>R18：两段式异步打开（主线程零查库）</h2>
 *
 * <p>改动前 {@link #open} 在主线程同步走完 {@code getUnlockedAchievements}（cache miss
 * 时 SELECT 成就表）+ {@code getProgressSnapshot}（内部 {@code getPlayerStats} 可能触发
 * {@code loadPlayerData} 的两段 SELECT、{@code getCoinsEarned} 一条 SELECT、
 * {@code fetchSessionDays} 一条 {{@code player_sessions}} 全历史 SELECT、
 * {@code getConversationCount} / {@code getWrongBookCleared} 各自的懒加载），
 * 外加每次 {@code getUnlockedAt} / {@code meetsPrerequisites} 的逐条查询。
 * 看<b>离线玩家</b>时这些全部走 cache-miss 路径——原注释那句「≤3 条有界索引查询，
 * 主线程，可接受」在离线目标上不成立。
 *
 * <p>现在与同族（{@code ChatHistoryGUI} / {@code DailyChallengeGUI} /
 * {@code LearningCalendarGUI} / {@code ProfileGUI}）一致：主线程只做<b>零 DB</b> 的
 * 准备工作（语言、文案、achievements-config 文件、可见成就列表、请求时所在界面的
 * holder 引用），数据查询全部搬进 {@link #prefetchWallData} 并在
 * {@code runTaskAsynchronously} 里执行，回主线程一次性渲染（{@link #openWithData}，
 * 零 DB 调用）。
 *
 * <p><b>为什么 {@code getProgressSnapshot} 可以整体异步</b>：它返回的是<b>值快照</b>
 * （新分配的 {@link EnumMap} + 逐项新 {@code int[2]}），不泄漏 {@code PlayerDataStore}
 * 的活对象；它的计算过程只有「读」和「缓存懒加载」，而缓存懒加载在异步线程上不是新
 * 语义——{@code ProfileGUI#scheduleProfileBackfill} 早已在异步段调
 * {@code getPlayerStats}，{@code AchievementManager} 自身的
 * {@code checkHelpAchievement} 也在异步段跑完整判定链。因此这里不需要"分段异步"折中。
 *
 * <p><b>回填守卫（三条，全部基于 holder，绝不看标题）</b>：
 * <ol>
 *   <li>{@code viewer.isOnline()} —— 异步间隙可能下线；</li>
 *   <li><b>holder 引用相等</b>：请求发起时记下玩家所在界面的
 *       {@link PluginGuiHolder} 实例，回填前重取；两者不是<b>同一个实例</b>说明玩家
 *       已经离开那张界面（关掉、换了别的主页、或进了别的插件界面）→ 放弃本次打开，
 *       不把成就墙盖到别人脸上；</li>
 *   <li>per-player 打开 token —— 连点/连开时只渲染最新一次（照
 *       {@code DailyChallengeGUI} 的 E1 同式）。</li>
 * </ol>
 * 三条守卫失效的后果不是「看起来旧」，而是<b>用一次过期点击强开界面、覆盖玩家当前所在
 * 屏幕</b>。守卫里一律用 holder 引用，不用标题字符串：标题是本地化文案 + 动态计数后缀，
 * 它既不能证明「还是那张界面」，也不该参与任何判定（见 {@code GuiViewKey} 契约）。
 */
public class AchievementWallGUI {

    private static final int GUI_SIZE = 54;

    /**
     * 内容格容量：4 行 × 7 列 = 28（slot 10-16 / 19-25 / 28-34 / 37-43）。
     */
    private static final int MAX_GRID_SLOTS = 28;

    /**
     * PF-ACH-07 护栏：超过 28 个可见成就时 {@code slot > 43} 会静默丢弃多余条目，
     * WARNING 只报一次（静态防刷屏；成就数超容量需要分页时手动定位此提示）。
     */
    private static volatile boolean truncationWarningLogged = false;

    /**
     * R18：打开 token（跨实例单调序号；{@code new} 即用即弃，per-player 只记最新一条）。
     *
     * <p>与 {@code DailyChallengeGUI} / {@code LearningCalendarGUI} / {@code ChatHistoryGUI}
     * 的 E1 去重同式：主线程首部登记、回主线程渲染前比对。条目随每次打开覆写自身收敛
     * （量级 = 历史玩家数条 Long），不挂 quit 清理。
     */
    private static final AtomicLong SEQ = new AtomicLong();
    private static final Map<UUID, Long> openSeq = new ConcurrentHashMap<>();

    private final LetsLearnEnglish plugin;

    public AchievementWallGUI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * 兼容入口 —— 统一委托 {@link #openAsync(Player, UUID)}。
     *
     * <p>既有调用点（{@code SocialListener} 主页成就墙按钮）零改动；行为语义与原同步
     * {@code open} 一致，只是改为两段式异步打开。
     *
     * <p><b>本方法体内不得出现任何成就/统计/金币查询</b>：那是
     * {@code AchievementWallOpenAsyncTest} 的源码 lint 锚点（把取数搬回主线程会立刻变红）。
     */
    public void open(Player viewer, UUID targetId) {
        openAsync(viewer, targetId);
    }

    /**
     * R18：异步两段式打开入口（照 {@code DailyChallengeGUI.openAsync} 同式）。
     *
     * <p>主线程段只做零 DB 的准备：语言/文案、{@code achievements-config.yml}（本地文件，
     * 非数据库）、可见成就列表（配置滤波 + 稳定排序）、请求时所在界面的 holder 引用。
     * 异步段（{@link #prefetchWallData}）只查数据，不碰任何 Bukkit 世界 API。
     * 主线程渲染段（{@link #openWithData}）只画物品，零 DB 调用。
     *
     * <p>守卫见类 Javadoc「回填守卫」段。三者任一失败都只放弃本次渲染，绝不抛异常
     *（异步间隙玩家下线/换界面是常态，不是异常状态）。
     *
     * @param viewer   打开界面的玩家（可查看他人成就）
     * @param targetId 成就算谁的 —— 与他人主页同源，<b>不是</b>打开者
     */
    public void openAsync(Player viewer, UUID targetId) {
        if (viewer == null || targetId == null) {
            return;
        }

        final UUID viewerId = viewer.getUniqueId();
        final String lang = plugin.getPlayerLanguage(viewer);
        final FileConfiguration messages = plugin.getConfigManager().getMessageConfig(lang);
        final FileConfiguration achConfig = loadAchievementConfig();
        final List<Achievement> visible = List.copyOf(visibleAchievements(achConfig));
        if (visible.size() > MAX_GRID_SLOTS) {
            warnTruncationOnce(visible.size());
        }

        // 守卫 2 的基准：请求发起时玩家所在界面的 holder 实例。之后只做引用比较，
        // 不读标题、不读 key 字符串。
        final PluginGuiHolder arrivalHolder = pluginGuiHolderOf(viewer);
        final long token = SEQ.incrementAndGet();
        openSeq.put(viewerId, token);

        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!plugin.isEnabled()) {
                    return;
                }
                final WallData data;
                try {
                    data = prefetchWallData(targetId, visible);
                } catch (Exception e) {
                    // R11-M3：失败必有反馈 —— 异步段不得直调 Bukkit API，切主线程发提示。
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to preload achievement wall for " + targetId, e);
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> notifyLoadFailed(viewer));
                    } catch (IllegalPluginAccessException | IllegalStateException ignored) {
                        // 关服竞态：调度器已关闭，放弃提示
                    }
                    return;
                }
                if (!plugin.isEnabled()) {
                    return;
                }
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!stillOnRequestedScreen(viewer, arrivalHolder)) {
                            return;
                        }
                        if (openSeq.getOrDefault(viewerId, -1L) != token) {
                            return;
                        }
                        openWithData(viewer, targetId, lang, messages, achConfig, visible, data);
                    });
                } catch (IllegalPluginAccessException | IllegalStateException ignored) {
                    // 关服竞态：调度器已关闭，放弃本次渲染
                }
            });
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次打开
        }
    }

    /**
     * R18：异步段唯一的取数入口 —— 成就墙需要的全部数据在这里一次取齐。
     *
     * <p><b>为什么独立成方法</b>：{@code AchievementWallOpenAsyncTest} 的源码 lint 用
     * 「这些查询只能出现在本方法体内」来钉死线程归属。写进 {@code openAsync} 的
     * lambda 里（文本上仍属 {@code openAsync} 的方法体）会让「主线程不查库」这条断言
     * 失去判别力，也会让未来把调用挪回主线程的重构无声通过。
     *
     * <p>四类数据各自可能查库，但都<b>只读</b>；任一失败由调用方整段 catch 兜底
     * （一次取不全就整张墙不出，而不是画半张错数字的墙——进度行写 0 比不打开更误导）。
     *
     * @param targetId 成就算谁的（可离线）
     * @param visible  主线程算好的可见成就集（只读，用于批量取解锁时刻/前置判定）
     * @return 可与主线程共享的值快照
     */
    private WallData prefetchWallData(UUID targetId, List<Achievement> visible) {
        AchievementManager am = plugin.getAchievementManager();
        if (am == null) {
            // 成就模块未装配：整墙全锁。比 NPE 更接近真相，且保持「不抛」契约。
            return WallData.empty();
        }
        Set<Achievement> unlocked = am.getUnlockedAchievements(targetId);
        Map<Achievement, int[]> progress = am.getProgressSnapshot(targetId);
        Map<Achievement, Long> unlockedAt = am.getUnlockedAtSnapshot(targetId, visible);
        Set<Achievement> prereqUnmet = am.getPrereqUnmetSnapshot(targetId, visible);
        return new WallData(unlocked, progress, unlockedAt, prereqUnmet);
    }

    /**
     * R18：主线程渲染 —— 数据由 {@link #prefetchWallData} 在异步段取好，本方法零 DB 调用。
     *
     * <p>渲染内容与异步化之前逐项等价（槽位、材质、lore 行、返回键、空槽填充都在原位）。
     */
    private void openWithData(Player viewer, UUID targetId, String lang,
                              FileConfiguration messages, FileConfiguration achConfig,
                              List<Achievement> visible, WallData data) {
        Set<Achievement> unlocked = data.unlocked();

        int unlockedAmongVisible = 0;
        for (Achievement a : visible) {
            if (unlocked.contains(a)) unlockedAmongVisible++;
        }

        // 标题计数为可选增强（规划 §2.1）：统计口径 = 墙内可见成就（管理员禁用的不计入，
        // 与渲染集一致）。R5-4 起点击侧判定走 holder，后缀只是展示；R5-14/R5-16 起标题层
        // （标题兜底与 GUIUtils 白名单）已整体删除，标题里的「成就墙 / Achievement Wall」
        // 词元仅为文案可读性，不承担身份职责。
        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(messages, "achievement-wall.title"))
                + " (" + unlockedAmongVisible + "/" + visible.size() + ")";
        // R5-4：挂结构身份 holder。R18 起 payload 带目标玩家 —— 与 ProfileGUI 同式，
        // 让「这面墙是谁的」成为结构性事实（回填守卫的纵深、以及未来点击侧需要目标时
        // 不必再解析任何文案）。计数后缀不参与身份。
        Inventory inv = Bukkit.createInventory(
                PluginGuiHolder.of(GuiViewKey.ACHIEVEMENT_WALL,
                        new PluginGuiHolder.GuiPayload.Target(targetId)),
                GUI_SIZE, title);

        ConfigManager cm = plugin.getConfigManager();

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        // P3-12：统一系统时区（原硬编码 Asia/Shanghai——成就墙解锁日期与
        // AchievementManager 时窗判定同口径，跟服务器本地时区走）。
        dateFormat.setTimeZone(TimeZone.getDefault());

        int slot = 10;
        int col = 0;
        Material unlockedMat = cm.getGuiMaterial("achievement-wall", "unlocked-material", Material.GREEN_STAINED_GLASS_PANE);
        Material lockedMat = cm.getGuiMaterial("achievement-wall", "locked-material", Material.GRAY_STAINED_GLASS_PANE);
        for (Achievement a : visible) {
            boolean isUnlocked = unlocked.contains(a);
            Material mat = isUnlocked ? unlockedMat : lockedMat;
            String name = isUnlocked
                    ? ChatColor.translateAlternateColorCodes('&', "&a" + a.getDisplayName(lang))
                    : ChatColor.translateAlternateColorCodes('&', "&8&m" + a.getDisplayName(lang));

            List<String> lore = new ArrayList<>();
            // 行 1：类别（键由 fan-in 落 zh/en.yml：achievement-wall.category-line +
            // category-learning/review/consistency/social/economy，枚举名小写即键后缀）
            String categoryName = MessageUtil.getGuiMsg(messages,
                    "achievement-wall.category-" + a.getCategory().name().toLowerCase(Locale.ROOT));
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    MessageUtil.getGuiMsg(messages, "achievement-wall.category-line")
                            .replace("{category}", categoryName)));
            // 行 2：描述
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7" + a.getDisplayDesc(lang)));

            if (isUnlocked) {
                // 行 3：解锁日期（PF-ACH-09 数据本就在库；R18 起由异步段的
                // getUnlockedAtSnapshot 批量取好，渲染侧只读快照）
                Long unlockedAt = data.unlockedAt().get(a);
                if (unlockedAt != null) {
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "achievement-wall.unlocked-at")
                                    .replace("{date}", dateFormat.format(new Date(unlockedAt)))));
                }
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "profile.achievement-unlocked")));
            } else {
                // 进度行：仅对可量化数据源（progressSource != NONE）显示。
                // achievement.progress 为复活死键（zh/en.yml:106），值里带 {prefix}
                // （设计给聊天的），墙内 lore 无前缀语境 → 置空替换。
                if (a.getProgressSource() != Achievement.ProgressSource.NONE) {
                    int[] progress = data.progress().get(a);
                    if (progress != null && progress.length >= 2) {
                        lore.add(ChatColor.translateAlternateColorCodes('&',
                                MessageUtil.getGuiMsg(messages, "achievement.progress")
                                        .replace("{prefix}", "")
                                        .replace("{name}", a.getDisplayName(lang))
                                        .replace("{current}", String.valueOf(progress[0]))
                                        .replace("{target}", String.valueOf(progress[1]))));
                    }
                }
                // 前置未满足提示（R18 起由异步段的 getPrereqUnmetSnapshot 批量判定；
                // boolean 接口不提供缺失前置的名字，{name} 填本成就名，
                // 语义 = "{name} 的前置尚未满足"）
                if (data.prereqUnmet().contains(a)) {
                    lore.add(ChatColor.translateAlternateColorCodes('&',
                            MessageUtil.getGuiMsg(messages, "achievement.prereq-hint")
                                    .replace("{name}", a.getDisplayName(lang))));
                }
                String timeLeft = getTimeLeftString(achConfig, a, messages);
                if (timeLeft != null && !timeLeft.isEmpty()) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', timeLeft));
                }
                lore.add(ChatColor.translateAlternateColorCodes('&',
                        MessageUtil.getGuiMsg(messages, "profile.achievement-locked")));
            }

            inv.setItem(slot, createItem(mat, name, lore));

            slot++;
            col++;
            if (col == 7) { col = 0; slot += 2; }
            if (slot > 43) break;
        }

        String backLabel = ChatColor.translateAlternateColorCodes('&', "&e" +
                MessageUtil.getGuiMsg(messages, "gui.back-button"));
        inv.setItem(8, createItem(Material.RED_STAINED_GLASS_PANE, backLabel,
                Collections.singletonList(ChatColor.translateAlternateColorCodes('&', "&7" +
                        MessageUtil.getGuiMsg(messages, "gui.back-lore")))));

        GUIUtils.fillEmpty(inv); // R12-M3：空槽填灰玻璃（防拖纵深）
        viewer.openInventory(inv);
    }

    /**
     * R18：回填守卫 1+2 —— 玩家仍在请求发起时的那张界面上。
     *
     * <p>判据是 {@link PluginGuiHolder} 的<b>引用相等</b>：请求时记下的是那一刻实例，
     * 回填时重取到的必须是同一个实例。玩家关掉界面（当前界面变成自己的背包，
     * holder 是 {@code Player}）、换了别人的主页、或点进了别的插件界面，都会得到不同的
     * holder（或 null）→ 放弃本次打开。
     *
     * <p><b>故意不用标题/不用 key 字符串</b>：标题是本地化文案 + 动态计数后缀，
     * 既不能证明「还是那张界面」，也不该参与任何判定（{@code GuiViewKey} 契约）。
     * 两个都没开插件界面时（命令路径）双方都是 {@code null}，引用相等成立 —— 语义正确。
     */
    private static boolean stillOnRequestedScreen(Player viewer, PluginGuiHolder arrivalHolder) {
        if (viewer == null || !viewer.isOnline()) {
            return false;
        }
        return pluginGuiHolderOf(viewer) == arrivalHolder;
    }

    /**
     * 取玩家当前打开界面的 {@link PluginGuiHolder}；不是插件界面（或没有界面）时返回 null。
     *
     * <p>只认 {@code instanceof PluginGuiHolder}，因此玩家背包（holder 是 {@code Player}）
     * 与原版容器（自己的 holder）一律归为 null —— 不参与身份比较。
     */
    private static PluginGuiHolder pluginGuiHolderOf(Player viewer) {
        InventoryView view = viewer.getOpenInventory();
        if (view == null) {
            return null;
        }
        Inventory top = view.getTopInventory();
        return (top != null && top.getHolder() instanceof PluginGuiHolder holder) ? holder : null;
    }

    /**
     * R18：异步取数失败的玩家侧反馈（主线程执行）。
     *
     * <p>复用既有的 {@code daily.load-failed}（与 {@code LearningCalendarGUI} 同款：
     * 「界面数据加载失败，请稍后再试」）。<b>不新增 i18n 键</b>：文案文件不在本次改动范围内，
     * 且语义逐字相同。
     *
     * <p>全程判空/判在线：本方法的存在意义是「失败时别把异常再抛一次」，
     * {@code plugin} 或 {@code MessageUtil} 在纯 JVM 单测里可能为 null。
     */
    private void notifyLoadFailed(Player viewer) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        MessageUtil util = plugin.getMessageUtil();
        if (util == null) {
            return;
        }
        viewer.sendMessage(util.getPlayerMessage(viewer, "daily.load-failed"));
    }

    /**
     * 墙内可见成就（管理员禁用的不渲染）按类别稳定排序。
     *
     * <p>{@code List.sort} 是稳定排序：同类别内保持 enum 声明序，旧展示序不被扰动。
     * 纯配置读取，零 DB —— 因此留在主线程段（{@link #openAsync}）。
     */
    private List<Achievement> visibleAchievements(FileConfiguration achConfig) {
        List<Achievement> visible = new ArrayList<>();
        for (Achievement a : Achievement.values()) {
            if (isAchievementEnabled(achConfig, a)) {
                visible.add(a);
            }
        }
        visible.sort(Comparator.comparingInt(a -> a.getCategory().ordinal()));
        return visible;
    }

    private void warnTruncationOnce(int visibleCount) {
        if (truncationWarningLogged) return;
        truncationWarningLogged = true;
        plugin.getLogger().warning("Achievement wall grid overflow: " + visibleCount
                + " enabled achievements exceed the " + MAX_GRID_SLOTS
                + " display slots — the remainder is silently dropped. "
                + "Add paging before expanding the achievement list further.");
    }

    /**
     * 直读 dataFolder 下的 achievements-config.yml。
     *
     * <p>诚实性审查收口：原"四个真相源"中的 {@code ConfigManager.achievements.yml}
     * 死链已随废弃文件整链移除（2026-10-16）——运行期真正生效的只有
     * {@code achievements-config.yml}，本方法与
     * {@code AchievementManager.getAchievementConfig} 是仅有的两个消费点。
     *
     * <p>R18：保持在<b>主线程</b>段。这是本地文件 IO（必要时 {@code saveResource} 释放
     * 内置文件），不是数据库查询；{@code saveResource} 属插件资源加载路径，不保证线程安全，
     * 因此不随取数一起下沉到异步段。
     */
    private FileConfiguration loadAchievementConfig() {
        File file = new File(plugin.getDataFolder(), "achievements-config.yml");
        if (!file.exists()) {
            plugin.saveResource("achievements-config.yml", false);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private boolean isAchievementEnabled(FileConfiguration config, Achievement a) {
        if (config == null) return true;
        return config.getBoolean("achievements." + a.getId() + ".enabled", true);
    }

    private String getTimeLeftString(FileConfiguration config, Achievement a,
                                      FileConfiguration messages) {
        if (config == null) return null;
        if (!config.getBoolean("achievements." + a.getId() + ".time-limit.enabled", false)) return null;

        String startStr = config.getString("achievements." + a.getId() + ".time-limit.start");
        String endStr = config.getString("achievements." + a.getId() + ".time-limit.end");
        if (startStr == null || endStr == null) return null;

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            // P3-12：统一系统时区（与 AchievementManager.isAchievementEligible 的
            // 时窗解析口径一致——同一份配置串在两处被解析，必须同时区）。
            sdf.setTimeZone(TimeZone.getDefault());
            Date start = sdf.parse(startStr);
            Date end = sdf.parse(endStr);
            Date now = new Date();

            if (now.before(start)) {
                long diff = start.getTime() - now.getTime();
                return MessageUtil.getGuiMsg(messages, "achievement-wall.time-opens")
                        .replace("{time}", formatDuration(diff));
            }
            if (now.after(end)) {
                return MessageUtil.getGuiMsg(messages, "achievement-wall.time-expired");
            }
            long diff = end.getTime() - now.getTime();
            return MessageUtil.getGuiMsg(messages, "achievement-wall.time-remaining")
                    .replace("{time}", formatDuration(diff));
        } catch (Exception e) {
            return null;
        }
    }

    private String formatDuration(long millis) {
        long days = millis / 86400000;
        long hours = (millis % 86400000) / 3600000;
        long minutes = (millis % 3600000) / 60000;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
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

    /**
     * R18：异步段取到的成就墙数据 —— <b>纯值快照</b>，可安全跨线程交给渲染段。
     *
     * <p>四项都是异步段内新构造的容器（{@code getProgressSnapshot} 返回的
     * {@link EnumMap} 与其中的 {@code int[]} 每次都是新分配），不持有
     * {@code PlayerDataStore} / {@code AchievementManager} 的任何活对象引用 ——
     * 这是「整体异步」而不是「分段异步」的前提。
     *
     * <p>取不到数据的字段用空容器表示（不是 null）：渲染侧据此把该成就当「未解锁、
     * 无进度行」——本墙的语义本来就是「不知道就按未解锁展示」，与异步化前
     * {@code getProgressSnapshot} 每项独立 try/catch 回落 0 的口径一致。
     */
    private record WallData(Set<Achievement> unlocked,
                            Map<Achievement, int[]> progress,
                            Map<Achievement, Long> unlockedAt,
                            Set<Achievement> prereqUnmet) {

        private static WallData empty() {
            return new WallData(Set.of(), Map.of(), Map.of(), Set.of());
        }
    }
}
