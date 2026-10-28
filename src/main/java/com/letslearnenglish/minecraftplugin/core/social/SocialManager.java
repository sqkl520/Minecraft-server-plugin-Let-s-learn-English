package com.letslearnenglish.minecraftplugin.core.social;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.util.SchedulerGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SocialManager — 广场社交（域 4）核心逻辑：学习动态流 + 鼓励（cheer）+ 回应闭环。
 *
 * <p>形态对齐 {@code DuelManager}（嵌套 record + 内聚逻辑，无独立 DAO）。数据落
 * {@code social_feed} / {@code social_cheers} 两表（DatabaseManager）。
 *
 * <p><b>线程模型（F2-11 教训的正面回答，plan-social §3.2）</b>：
 * <ul>
 *   <li>动态/鼓励<b>全部写</b>：一律异步（scheduler），主线程零写库——{@link #recordSessionDone}
 *       等方法可能自异步线程（成就解锁 F1-03 路径）或主线程（会话完成）两路进入，
 *       入口统一派发异步；</li>
 *   <li>广场打开读动态页：零 DB，读 {@link #getFeedSnapshot()} volatile 快照（上限 200 条）；</li>
 *   <li>唯一同步查询 = 打开时的每页 ≤28-id 有界"你已鼓励"集合
 *       （{@link #getCheeredFeedIds}，走 unique 索引；后续可优化为内存 set）；</li>
 *   <li>进服摘要：异步查 → 主线程发 → 异步标已读；</li>
 *   <li>缓存新鲜度：写入后 5s 去抖刷新；另有独立 300s 周期异步任务
 *       （plan §3.2 明许"或独立 300s 异步任务"）。</li>
 * </ul>
 *
 * <p><b>白名单裁决说明（实现期）</b>：plan §3.6 的 HotReloadManager reload 一行与
 * LetsLearnEnglish 午夜 rollover 清理钩子不在本流白名单内，改由本类自持的方案覆盖：
 * 启动预热（{@link #startupWarmup}）在 LetsLearnEnglish 启动异步块一行挂入，
 * 并以日期翻转判定在周期任务里跑每日保留清理（等价于"午夜清理"意图）。
 *
 * <p><b>隐私口径（plan-social §1.6，与"隐私四层"同款）</b>：产生侧
 * {@link #recordEvent} 查 {@code showInSquare}；读取侧 SQL JOIN 过滤（DatabaseManager）；
 * 点击守卫在 {@link #cheer} 内复查作者当前开关（缓存窗口内陈旧条目兜底）。
 * 边界决策：隐身期间收到的鼓励离线摘要照常发送（摘要只发给作者本人，不向第三方泄露）。
 */
public class SocialManager {

    /** FeedCache 上限（≈7 页 ×28，plan §1.8）——翻页在内存切片，无逐页查库。 */
    private static final int FEED_CACHE_LIMIT = 200;
    /** 写入成功后缓存刷新的去抖窗口（ms）：同一窗口内多次写只刷一次。 */
    private static final long REFRESH_DEBOUNCE_MS = 5000L;
    /** 周期刷新间隔（tick）= 300 秒（plan §3.2）。 */
    private static final long PERIODIC_REFRESH_TICKS = 300L * 20L;
    /** "谁鼓励了我"名单上限（防极端刷屏）。 */
    private static final int CHEERERS_LIST_LIMIT = 50;

    private final LetsLearnEnglish plugin;

    /** FeedCache volatile 快照：整体替换，读侧无锁（对齐 Leaderboard.refreshCache 模式）。 */
    private volatile List<FeedItem> feedCache = Collections.emptyList();
    /** 缓存写锁：volatile 整体替换需串行，防刷新与乐观 +1 互相覆盖。 */
    private final Object cacheLock = new Object();

    /** 鼓励点击防抖（per-player，quit 时经 {@link #unloadPlayer} 清理——F2-10/F1-24 教训）。 */
    private final Map<UUID, Long> cheerDebounceAt = new ConcurrentHashMap<>();
    /** 缓存刷新去抖时间戳（同一窗口内多次写只刷一次）。 */
    private final AtomicLong nextRefreshAt = new AtomicLong(0L);
    /** 每日保留清理的日期去重（日期翻转时在周期任务里跑一次）。 */
    private final AtomicReference<String> lastCleanupDay = new AtomicReference<>();

    /**
     * 300s 周期维护任务句柄（{@link #startupWarmup()} 排程，{@link #shutdown()} 取消）。
     *
     * <p>原实现直接丢弃 {@code runTaskTimerAsynchronously} 的返回值 —— 本类 6 个周期/延迟
     * 任务里唯一「连句柄都没有」的一个。{@code /lea reload} 走的是「不换 ClassLoader 的
     * 逻辑卸载」，插件本身仍在启用态，调度器不会连带取消它，因此禁用功能后这条任务会继续
     * 每 300s 打一次 DB（{@link #refreshCache} + 可能的保留清理）。持有句柄是取消的前提。
     */
    private volatile BukkitTask maintenanceTask;

    /**
     * 一条动态流缓存条目（volatile 快照的元素；不可变）。
     * {@code type} ∈ SESSION_DONE / ACHIEVEMENT / CHECKIN；文本渲染由
     * {@link FeedTextFormatter} 按 viewer 语言完成（本 record 不承载语言相关串）。
     */
    public record FeedItem(long id, UUID authorId, String authorName, String type,
                           int eventsCount, int sumCorrect, int sumTotal, String lastMode,
                           int lastStreak, String achievementId, int cheerCount, long updatedAt) {

        /** 乐观计数用：仅 cheer_count 变更的副本。 */
        public FeedItem withCheerCount(int newCheerCount) {
            return new FeedItem(id, authorId, authorName, type, eventsCount, sumCorrect, sumTotal,
                    lastMode, lastStreak, achievementId, newCheerCount, updatedAt);
        }
    }

    /**
     * 鼓励统计（<b>冻结接口</b>，P3-3 互助成就消费，plan-social §4.3）。
     *
     * @param given           累计发出鼓励数
     * @param distinctTargets 发出对象去重数（成就判据 {@code >= 10}）
     * @param received        收到鼓励总数
     */
    public record CheerStats(int given, int distinctTargets, int received) { }

    public SocialManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //  门槛与配置（点读式，reload 即时生效）
    // ============================================================

    /** 广场社交总门（social.enabled；GUI/Listener/摘要/点击四处接线，plan §2.5）。 */
    public boolean isEnabled() {
        return plugin.getConfigManager().isSocialEnabled();
    }

    private boolean isCheerEnabled() {
        return plugin.getConfigManager().getMainConfig()
                .getBoolean("social.cheer.enabled", true);
    }

    private int getRetentionDays() {
        return plugin.getConfigManager().getMainConfig()
                .getInt("social.feed.retention-days", 7);
    }

    private long getCheerDebounceMs() {
        return plugin.getConfigManager().getMainConfig()
                .getLong("social.cheer.click-debounce-ms", 750L);
    }

    // ============================================================
    //  事件产生（SESSION_DONE / ACHIEVEMENT / CHECKIN）
    // ============================================================

    /**
     * 产生一条 SESSION_DONE 动态（{@code WordLearningManager.showSessionComplete} 末尾调用，
     * 自然走完会话才到达——stop/quit/死亡中断不产生，与"不奖励没走完的会话"同一哲学）。
     * 同日同玩家 UPSERT 合并为 1 行（events_count+1、答对/题数累加、last_mode 覆盖）。
     */
    public void recordSessionDone(UUID playerId, String mode, int correct, int total) {
        recordEvent(playerId, "SESSION_DONE", correct, total, mode, 0, null);
    }

    /**
     * 产生一条 ACHIEVEMENT 动态（{@code AchievementManager.tryUnlock} 新解锁分支调用；
     * 已解锁重复调用与不合格解锁都到不了调用点）。同日合并：计数 +1，成就名取最新。
     */
    public void recordAchievement(UUID playerId, String achievementId) {
        recordEvent(playerId, "ACHIEVEMENT", 0, 0, null, 0, achievementId);
    }

    /**
     * 产生一条 CHECKIN 动态（{@code LearnCommand} 签到成功分支调用；重复签/断签失败
     * 不产生）。天然每日至多 1 条（结构上限）。
     */
    public void recordCheckin(UUID playerId, int streak) {
        recordEvent(playerId, "CHECKIN", 0, 0, null, streak, null);
    }

    /**
     * 统一事件入口：social.enabled 门（真接线，F0-11 教训）→ 产生侧隐身门
     * （{@code getProfileSettingsReadOnly}，不建缓存条目——L-05）→ 异步 DB 写 →
     * 写成功后 5s 去抖缓存刷新。本方法可从主线程或异步线程进入，入口统一派发异步。
     */
    private void recordEvent(UUID playerId, String eventType, int correctDelta, int totalDelta,
                             String lastMode, int lastStreak, String achievementId) {
        if (!isEnabled()) {
            return;
        }
        if (!plugin.getPlayerDataStore().getProfileSettingsReadOnly(playerId).isShowInSquare()) {
            return;
        }
        runAsync(() -> {
            plugin.getDatabaseManager().upsertFeedEvent(playerId, eventType,
                    correctDelta, totalDelta, lastMode, lastStreak, achievementId);
            scheduleCacheRefresh();
        });
    }

    // ============================================================
    //  FeedCache（读路径：广场打开零查库）
    // ============================================================

    /** 当前 FeedCache 快照（不可变列表；≤200 条，按 updated_at 倒序）。 */
    public List<FeedItem> getFeedSnapshot() {
        return feedCache;
    }

    /** 异步刷新入口（启动预热 / 周期任务 / 重载用；读路径对 volatile 快照零等待）。 */
    public void refreshCacheAsync() {
        runAsync(this::refreshCache);
    }

    /**
     * 启动预热（LetsLearnEnglish 启动异步块一行挂入）：清理过期动态 + 首次填充
     * FeedCache + 排程 300s 周期刷新任务（内含每日保留清理）。
     *
     * <p>重复排程保护：先取消上一次留下的 {@link #maintenanceTask} 再排 —— 同名
     * {@code /lea reload} 卸载后重新启用（或 enable 被重复触发）不应叠加第二条 300s
     * 任务。原注释「插件 disable 时调度器会连带取消，无需手动管理句柄」只对真正的
     * {@code onDisable} 成立；逻辑卸载（reload）下插件仍在启用态，任务不会被连带取消，
     * 故句柄必须自持并在 {@link #shutdown()} / 本处显式取消。
     */
    public void startupWarmup() {
        plugin.getDatabaseManager().cleanupOldFeedEvents(getRetentionDays());
        lastCleanupDay.set(LocalDate.now().toString());
        refreshCache();
        BukkitTask previous = maintenanceTask;
        if (previous != null) {
            previous.cancel();
            maintenanceTask = null;
        }
        try {
            maintenanceTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
                    this::periodicMaintenance, PERIODIC_REFRESH_TICKS, PERIODIC_REFRESH_TICKS);
        } catch (IllegalStateException ignored) {
            // 停用窗口：调度器已关闭，周期刷新放弃（缓存仍可用，下次 enable 重排）
            maintenanceTask = null;
        }
    }

    /**
     * 动态卸载钩子（{@code /lea reload} 关闭广场社交时调用）：取消本类持有的周期任务，
     * 回到「未启动」状态。
     *
     * <p>幂等：连续调用两次不抛（第二次 {@code task == null} 直接返回）；任务已被外部
     * 取消（{@code onDisable} 的调度器整体关闭）时再 cancel 也是安全的。
     *
     * <p>只取消任务、<b>不清缓存</b>：{@code feedCache} 是只读快照，禁用后读路径自己会
     * 被 {@link #isEnabled()} 拦住；清空反而会在重新启用时出现短暂空窗。
     *
     * <p>检查结论（grep 全类）：{@code SocialManager} 内除本任务外的所有调度都走
     * {@link #runAsync} / {@link #runMain} 的一次性投递（{@link #refreshCacheAsync}、
     * {@link #scheduleCacheRefresh} 的去抖刷新）—— 没有其他 {@code runTaskTimer} /
     * {@code runTaskLater} 需要持句柄。一次性任务最多存活数秒且自带 DB 异常兜底，
     * 不需要在此取消。
     */
    public void shutdown() {
        BukkitTask task = maintenanceTask;
        if (task != null) {
            maintenanceTask = null;
            task.cancel();
        }
    }

    /**
     * 周期维护（300s 异步）：刷新缓存；日期翻转时跑一次保留清理并重读
     * （替代 plan 的午夜 rollover 钩子——该钩子代码不在本流白名单，意图等价：
     * 过期动态连同其鼓励记录在次日首次周期任务内清掉）。
     */
    private void periodicMaintenance() {
        refreshCache();
        String today = LocalDate.now().toString();
        String last = lastCleanupDay.get();
        if (today.equals(last)) {
            return;
        }
        if (lastCleanupDay.compareAndSet(last, today)) {
            plugin.getDatabaseManager().cleanupOldFeedEvents(getRetentionDays());
            refreshCache();
        }
    }

    /** 异步刷新实现（可被任意线程调；DB 读 + volatile 整体替换）。 */
    private void refreshCache() {
        List<DatabaseManager.FeedRow> rows = plugin.getDatabaseManager()
                .getFeedCacheRows(getRetentionDays(), FEED_CACHE_LIMIT);
        List<FeedItem> items = new ArrayList<>(rows.size());
        for (DatabaseManager.FeedRow row : rows) {
            items.add(new FeedItem(row.id(), row.authorId(), row.authorName(), row.eventType(),
                    row.eventsCount(), row.sumCorrect(), row.sumTotal(), row.lastMode(),
                    row.lastStreak(), row.achievementId(), row.cheerCount(), row.updatedAt()));
        }
        synchronized (cacheLock) {
            feedCache = Collections.unmodifiableList(items);
        }
    }

    /** 5s 去抖：同一窗口内多次写只调度一次异步刷新。 */
    private void scheduleCacheRefresh() {
        long now = System.currentTimeMillis();
        while (true) {
            long next = nextRefreshAt.get();
            if (now < next) {
                return;
            }
            if (nextRefreshAt.compareAndSet(next, now + REFRESH_DEBOUNCE_MS)) {
                break;
            }
        }
        runAsync(this::refreshCache);
    }

    /**
     * "你已鼓励"集合（有界同步查询：{@code cheerer_uuid=? AND feed_id IN (≤28 ids)}，
     * 走 UNIQUE 索引）。唯一允许在广场打开路径上的同步查询（plan §3.2 权衡标注；
     * 后续可优化为并入内存 set）。空输入不查库。
     */
    public Set<Long> getCheeredFeedIds(UUID viewerId, List<Long> feedIds) {
        if (feedIds == null || feedIds.isEmpty()) {
            return Collections.emptySet();
        }
        return plugin.getDatabaseManager().getCheeredFeedIds(viewerId, feedIds);
    }

    // ============================================================
    //  鼓励（cheer）
    // ============================================================

    private FeedItem findCached(long feedId) {
        for (FeedItem item : feedCache) {
            if (item.id() == feedId) {
                return item;
            }
        }
        return null;
    }

    /**
     * 鼓励一条动态（主线程入口；结果分发回主线程）。
     *
     * <p>校验链：social.enabled + cheer.enabled 门 → 条目在缓存中存在（否则条目过期）
     * → 不可自赞（自己的条目左键应走"查看谁鼓励了我"）→ 点击守卫复查作者当前
     * showInSquare（缓存窗口内陈旧条目兜底 → 已隐身提示，不写脏行）→ 750ms 防抖
     * （宏连点静默吞掉）→ 异步写（DB 唯一约束兜底一人一条一次）。
     *
     * <p>OK 回调（主线程）：播 cheer 音效（复用"correct"键——plan 的 gui.sounds.cheer
     * 新键需动 SoundManager，不在本流白名单，裁决为复用正向音键）+ 发 cheer-success +
     * 乐观缓存 cheer_count+1 + 作者在线则立即发 notify-online 并异步标该条已读；
     * 作者离线则留 read_by_owner=0，走下次进服摘要。
     */
    public void cheer(Player cheerer, long feedId) {
        cheer(cheerer, feedId, null);
    }

    /**
     * 带成功回调的重载：{@code onSuccess} 在 OK 结果分发回主线程后执行
     * （供 {@code SocialListener} 重渲染当前 FEED 页，实现"lore 即时更新"
     * ——plan §2.2 GUI 即时性；ALREADY/GONE 不触发）。
     */
    public void cheer(Player cheerer, long feedId, Runnable onSuccess) {
        if (!isEnabled() || !isCheerEnabled()) {
            cheerer.sendMessage(plugin.getMessageUtil().getPlayerMessage(cheerer,
                    "module.disabled"));
            return;
        }
        FeedItem item = findCached(feedId);
        if (item == null) {
            cheerer.sendMessage(plugin.getMessageUtil().getPlayerMessage(cheerer,
                    "square.feed.cheer-gone"));
            return;
        }
        if (item.authorId().equals(cheerer.getUniqueId())) {
            cheerer.sendMessage(plugin.getMessageUtil().getPlayerMessage(cheerer,
                    "square.feed.cheer-own"));
            return;
        }
        if (!plugin.getPlayerDataStore().getProfileSettingsReadOnly(item.authorId()).isShowInSquare()) {
            cheerer.sendMessage(plugin.getMessageUtil().getPlayerMessage(cheerer,
                    "square.feed.cheer-hidden"));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = cheerDebounceAt.get(cheerer.getUniqueId());
        if (last != null && now - last < getCheerDebounceMs()) {
            return; // 防抖窗口内静默吞掉（宏连点；不回消息防刷屏本身）
        }
        cheerDebounceAt.put(cheerer.getUniqueId(), now);

        UUID cheererId = cheerer.getUniqueId();
        String cheererName = cheerer.getName();
        runAsync(() -> {
            DatabaseManager.CheerResult result =
                    plugin.getDatabaseManager().cheerFeed(feedId, cheererId);
            runMain(() -> handleCheerResult(cheerer, cheererId, cheererName, item, result, onSuccess));
        });
    }

    /** 写作结果分发（主线程）：音效 / 文案 / 乐观计数 / 在线作者即时提醒。 */
    private void handleCheerResult(Player cheerer, UUID cheererId, String cheererName,
                                   FeedItem item, DatabaseManager.CheerResult result,
                                   Runnable onSuccess) {
        switch (result) {
            case OK -> {
                SoundManager.playSound(cheerer, "correct");
                cheerer.sendMessage(plugin.getMessageUtil().getPlayerMessage(cheerer,
                        "square.feed.cheer-success", "player", item.authorName()));
                applyOptimisticCheerCount(item.id());
                Player author = Bukkit.getPlayer(item.authorId());
                if (author != null && author.isOnline()) {
                    author.sendMessage(plugin.getMessageUtil().getPlayerMessage(author,
                            "square.feed.notify-online", "player", cheererName));
                    SoundManager.playSound(author, "correct");
                    runAsync(() -> plugin.getDatabaseManager()
                            .markSingleCheerRead(item.id(), cheererId));
                }
                // else：作者离线 → 留 read_by_owner=0，走下次进服摘要
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }
            case ALREADY -> cheerer.sendMessage(plugin.getMessageUtil().getPlayerMessage(cheerer,
                    "square.feed.cheer-already"));
            case GONE -> cheerer.sendMessage(plugin.getMessageUtil().getPlayerMessage(cheerer,
                    "square.feed.cheer-gone"));
        }
    }

    /** 乐观 +1（volatile 列表整体替换；其余玩家 ≤1 个刷新周期可见——R6 已文档化）。 */
    private void applyOptimisticCheerCount(long feedId) {
        synchronized (cacheLock) {
            List<FeedItem> current = feedCache;
            List<FeedItem> next = new ArrayList<>(current.size());
            boolean changed = false;
            for (FeedItem item : current) {
                if (item.id() == feedId) {
                    next.add(item.withCheerCount(item.cheerCount() + 1));
                    changed = true;
                } else {
                    next.add(item);
                }
            }
            if (changed) {
                feedCache = Collections.unmodifiableList(next);
            }
        }
    }

    // ============================================================
    //  进服摘要 / 鼓励名单 / 统计
    // ============================================================

    /**
     * 进服摘要（{@code PlayerJoinListener.onPlayerJoin} 调用，形态对齐
     * {@code DuelManager.consumeOfflineDuelNotice} 的"join 时一次"，但存储取 DB
     * 而非内存队列——多次事件 + 重启不丢 + 共享 MySQL 跨服聚合）。
     *
     * <p>调度契约：本方法只在主线程被调用；内部异步 count →
     * 主线程发（count&gt;0 且 social.enabled 且 notify-on-join）→ 异步标已读。
     * 只有确实发出后才标已读（未发不标，下次进服重试）；作者恰在"socket 发出后
     * 立即退服"间隙的竞态允许重复提醒一次（宽容语义，R4）。
     */
    public void notifyPendingCheers(Player joining) {
        if (!isEnabled()) {
            return;
        }
        if (!plugin.getConfigManager().getMainConfig()
                .getBoolean("social.notify-on-join", true)) {
            return;
        }
        UUID playerId = joining.getUniqueId();
        runAsync(() -> {
            int count = plugin.getDatabaseManager().countUnreadCheers(playerId);
            if (count <= 0) {
                return;
            }
            runMain(() -> {
                Player target = Bukkit.getPlayer(playerId);
                if (target == null || !target.isOnline()) {
                    return; // 秒退复查：未发出则不标已读，留待下次进服
                }
                target.sendMessage(plugin.getMessageUtil().getPlayerMessage(target,
                        "square.feed.notify-join", "count", String.valueOf(count)));
                runAsync(() -> plugin.getDatabaseManager().markFeedCheersRead(playerId));
            });
        });
    }

    /**
     * 自条目左键："谁鼓励了我"名单。异步查名单 → 主线程回调（callback 只收到名字
     * 列表，消息组装由 listener 按 viewer 语言完成；requester 离线时 callback 仍会
     * 执行，由调用方自行复查 isOnline）。
     */
    public void getCheerersAsync(UUID requesterId, long feedId, Consumer<List<String>> callback) {
        runAsync(() -> {
            List<String> names = plugin.getDatabaseManager()
                    .getCheerers(feedId, CHEERERS_LIST_LIMIT);
            runMain(() -> {
                if (callback != null) {
                    callback.accept(names);
                }
            });
        });
    }

    /**
     * 鼓励统计（<b>冻结接口</b>，plan-social §4.3）。
     *
     * <p>线程契约：本方法做同步 DB 读，<b>禁止主线程调用</b>——成就侧调用点
     * （AchievementManager.checkHelpAchievement）负责异步派发。返回口径：
     * {@code given}=累计发出；{@code distinctTargets}=发出对象（feed 作者）去重数；
     * {@code received}=收到总数。DB 异常返回全 0（fail-safe）。
     */
    public CheerStats getCheerStats(UUID playerId) {
        DatabaseManager.CheerStatsRow row = plugin.getDatabaseManager().getCheerStatsRow(playerId);
        return new CheerStats(row.given(), row.distinctTargets(), row.received());
    }

    /** quit 编排点（PlayerJoinListener 调用）：清防抖 map（F2-10/F1-24"只增不删"教训）。 */
    public void unloadPlayer(UUID playerId) {
        cheerDebounceAt.remove(playerId);
    }

    // ============================================================
    //  调度工具（停用窗口静默放弃，对齐 AchievementManager PF-ACH-02）
    //  D2-5：实现已抽取至 util.SchedulerGuard，此处保留薄委托（调用点零改动）
    // ============================================================

    private void runAsync(Runnable task) {
        SchedulerGuard.runAsync(plugin, task);
    }

    private void runMain(Runnable task) {
        SchedulerGuard.runMain(plugin, task);
    }
}
