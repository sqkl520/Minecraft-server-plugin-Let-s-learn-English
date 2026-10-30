package com.letslearnenglish.minecraftplugin.core.season;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager.SeasonRewardRow;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager.SeasonRow;
import com.letslearnenglish.minecraftplugin.util.SchedulerGuard;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * SeasonManager — 赛季系统（域 5 批次 A）核心逻辑：窗口计算 / 结算 / 奖励台账与补发。
 *
 * <p><b>窗口模型（plan-season §1.2）</b>：锚点 + 固定时长（本地日粒度）。
 * {@code season.anchor-date}（默认 {@value #DEFAULT_ANCHOR}）+ {@code season.duration-days}
 * （默认 {@value #DEFAULT_DURATION_DAYS}）纯函数推导：第 N 季窗口 =
 * {@code [anchor + (N-1)*duration, anchor + N*duration - 1]}（闭区间）。任何时刻
 * （启服/午夜/PAPI）重算结果一致，停服漂移不累计误差。{@code today < anchor}
 * （含锚点在未来的首次误配）→ {@code currentSeasonId = 0}，全系统视为"赛季未开启"。
 *
 * <p><b>赛季 = 时间窗口视图，零数据重置（plan-season §1.3）</b>：赛季榜直接复用
 * {@code PlayerDataStore.getRankedPlayersForPeriod}（player_sessions 按日期窗聚合），
 * 无独立赛季累加分表。唯一新表是结算台账（seasons 头 + season_rewards 行）。
 *
 * <p><b>多服单发保护（plan-season §1.4）</b>：同一 MySQL 库的多台子服各自触发结算时，
 * 用 seasons 表条件 UPDATE 抢占结算权（{@link DatabaseManager#tryClaimSettlement}），
 * 只有 affected==1 的服写台账/发奖/广播，其余服静默跳过。
 *
 * <p><b>线程模型（plan-season §3.2，F2-11 教训）</b>：
 * <ol>
 *   <li>调度触发（主线程/异步均可）→ 只做日期纯计算与派发；</li>
 *   <li>结算 IO（异步任务）：查聚合 / 抢占 / 写台账；防重入由 {@link #settleInFlight} 保证；</li>
 *   <li>回主线程：全服广播（逐收件人语言渲染）、Vault 发钱、玩家消息；</li>
 *   <li>join 补发：异步读 unclaimed → 主线程条件标记 + 发钱（对齐
 *       {@code SocialManager.notifyPendingCheers} 的"异步查 → 主线程发"模式）。</li>
 * </ol>
 *
 * <p><b>经济门裁决（plan-season §1.4）</b>：赛季奖励不走 {@code money.daily-cap}
 * （{@code depositCappedReward} 会把大奖 clamp 到当日剩余额度），走直发：
 * {@code eco.depositPlayer} + {@code DatabaseManager.recordCoinsEarned}（lifetime 记账），
 * <b>不注入</b> {@code todayCoinsCache}（不挤占当日答题额度）。理由：赛季奖励一次性、
 * 有排名门槛、有台账幂等防重，与"可重复的日常收益"语义不同。无 Vault 时静默降级：
 * 只广播不发钱，台账行<b>保持 pending</b>（不标记不销毁——Vault 就位后任意进服/
 * 结算路径自动补发；与全项目"无经济则静默降级"一致且不丢玩家奖励）。
 *
 * <p><b>发钱前先标记（对齐 CheckInManager 先例）</b>：{@code markSeasonRewardClaimed}
 * 条件 UPDATE（{@code WHERE claimed=0}）affected==1 才开始发放——崩溃/双击/双服竞态
 * 都不可能二次发放（宁可极小概率"标记后崩溃少发一次"，不可重复发放，对齐 R-02 教训）。
 */
public class SeasonManager {

    /** 结算聚合与快照的 top 行数（plan-season §3.1：top10 + top_snapshot JSON）。 */
    private static final int TOP_N = 10;
    /** 赛季榜内存快照容量（对齐 Leaderboard.DEFAULT_CACHE_LIMIT 的展示语义）。 */
    private static final int CACHE_LIMIT = 50;
    /** 锚点缺省（与 config.yml season.anchor-date 同源；配置缺失/非法时回落）。 */
    private static final String DEFAULT_ANCHOR = "2026-11-02";
    /** 单季时长缺省（天）。 */
    private static final int DEFAULT_DURATION_DAYS = 30;

    /** top_snapshot JSON 序列化：List&lt;Map&gt; 结构（对 Gson 版本零假设）。 */
    private static final Gson GSON = new Gson();

    private final LetsLearnEnglish plugin;

    /** 赛季榜 volatile 快照（整体替换，读侧无锁；结算后 / 5 分钟班车刷新）。 */
    private volatile List<SeasonRankRow> topCache = Collections.emptyList();

    /** 结算防重入：同一时刻至多一个结算扫描在途（onEnable 与午夜两触发点可能相邻）。 */
    private final AtomicBoolean settleInFlight = new AtomicBoolean(false);

    /** 旅程验收收口：赛季"开而不动"启动 WARNING 一次性标记（防刷屏）。 */
    private volatile boolean dormancyWarned = false;
    /** 锚点配置非法只告警一次（点读式配置，防每次调用刷屏）。 */
    private final AtomicBoolean anchorWarned = new AtomicBoolean(false);

    /**
     * 赛季窗口（1-based，含首尾闭区间；日期比较口径与 player_sessions.session_date 同构）。
     *
     * @param id    赛季号（1-based）
     * @param start 首日（含）
     * @param end   末日（含）
     */
    public record SeasonWindow(int id, LocalDate start, LocalDate end) { }

    /**
     * 赛季榜一行（<b>冻结接口</b>，3A-4 的 PAPI / GUI 消费）。
     *
     * @param rank  名次（1-based）
     * @param uuid  玩家
     * @param name  展示名（DB 侧 player_name，空回落 uuid 字符串）
     * @param score 窗口内学习分合计
     */
    public record SeasonRankRow(int rank, UUID uuid, String name, long score) { }

    public SeasonManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //  门槛与配置（点读式，/lea reload 即时生效）
    // ============================================================

    /**
     * 赛季系统总门（<b>冻结接口</b>）：{@code season.enabled}（默认 false——运营属性，
     * 先关）<b>且</b>当前季已开启（{@code currentSeasonId(today) >= 1}；锚点在未来时
     * 同样休眠，plan-season §1.2）。false 时：无结算调度、无广播、cache 清空、
     * PAPI/展示返回空（真 gating，非静默 no-op）。
     */
    public boolean isEnabled() {
        if (!plugin.getConfigManager().getMainConfig().getBoolean("season.enabled", false)) {
            return false;
        }
        return currentSeasonId(LocalDate.now()) >= 1;
    }

    /** 开始/结束是否全服广播（{@code season.announce}，默认 true）。 */
    private boolean isAnnounceEnabled() {
        return plugin.getConfigManager().getMainConfig().getBoolean("season.announce", true);
    }

    /** 结算奖励是否入台账（{@code season.rewards.enabled}，默认 true；false 时只结算不发奖）。 */
    private boolean isRewardsEnabled() {
        return plugin.getConfigManager().getMainConfig().getBoolean("season.rewards.enabled", true);
    }

    /** 锚点日期（点读式；缺失/非法回落默认并告警一次）。 */
    private LocalDate anchorDate() {
        String raw = plugin.getConfigManager().getMainConfig()
                .getString("season.anchor-date", DEFAULT_ANCHOR);
        if (raw == null || raw.isBlank()) {
            raw = DEFAULT_ANCHOR;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            if (anchorWarned.compareAndSet(false, true)) {
                plugin.getLogger().warning("Invalid season.anchor-date '" + raw
                        + "', falling back to " + DEFAULT_ANCHOR);
            }
            return LocalDate.parse(DEFAULT_ANCHOR);
        }
    }

    /** 单季时长（天；非法/<=0 钳到 1，防除零）。 */
    private int durationDays() {
        return Math.max(1, plugin.getConfigManager().getMainConfig()
                .getInt("season.duration-days", DEFAULT_DURATION_DAYS));
    }

    // ============================================================
    //  窗口计算（纯函数，可随时调用——PAPI 直接消费）
    // ============================================================

    /**
     * 当前季 ID（<b>冻结接口</b>）。{@code date < anchor} → 0（赛季未开启，
     * 全系统休眠）。1-based。
     */
    public int currentSeasonId(LocalDate date) {
        return seasonId(date, anchorDate(), durationDays());
    }

    /**
     * 纯静态推导（本类实例方法委托至此；脱离 Bukkit 可直接冒烟）：
     * {@code floor(DAYS.between(anchor, date) / duration) + 1}。
     */
    static int seasonId(LocalDate date, LocalDate anchor, int durationDays) {
        if (date == null || anchor == null || durationDays <= 0 || date.isBefore(anchor)) {
            return 0;
        }
        return (int) (ChronoUnit.DAYS.between(anchor, date) / durationDays) + 1;
    }

    /**
     * 第 {@code id} 季窗口（<b>冻结接口</b>，id 1-based）：闭区间
     * {@code [anchor + (id-1)*duration, anchor + id*duration - 1]}。
     */
    public SeasonWindow windowOf(int id) {
        return windowFor(id, anchorDate(), durationDays());
    }

    /** 纯静态窗口推导（同 {@link #seasonId} 的可冒烟形态）。 */
    static SeasonWindow windowFor(int id, LocalDate anchor, int durationDays) {
        LocalDate start = anchor.plusDays((long) (id - 1) * durationDays);
        LocalDate end = start.plusDays(durationDays).minusDays(1);
        return new SeasonWindow(id, start, end);
    }

    /**
     * 当前季剩余毫秒（<b>冻结接口</b>）：至本季末日的 23:59:59.999（系统时区）。
     * 未开启（id&lt;1）返回 0。
     */
    public long millisUntilEnd() {
        int id = currentSeasonId(LocalDate.now());
        if (id < 1) {
            return 0L;
        }
        SeasonWindow window = windowOf(id);
        long endMillis = window.end().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        return Math.max(0L, endMillis - System.currentTimeMillis());
    }

    // ============================================================
    //  赛季榜缓存（volatile 快照；3A-4 的 PAPI/GUI 读缓存零查库）
    // ============================================================

    /**
     * 赛季榜快照前 {@code limit} 行（<b>冻结接口</b>）：零 DB 读（plan §1.7
     * 不复制广场周/月榜"开界面主线程查库"旧模式）。未启用/空态返回空列表。
     */
    public List<SeasonRankRow> getCurrentTopCached(int limit) {
        List<SeasonRankRow> snapshot = topCache;
        if (limit <= 0 || snapshot.isEmpty()) {
            return Collections.emptyList();
        }
        return limit >= snapshot.size()
                ? List.copyOf(snapshot)
                : List.copyOf(snapshot.subList(0, limit));
    }

    /**
     * 内存快照内名次（<b>冻结接口</b>）：未上榜（含快照外/CACHE_LIMIT 之外）
     * 返回 -1（与 Leaderboard 的 NOT_RANKED 同口径）。
     */
    public int getRankOf(UUID playerId) {
        if (playerId == null) {
            return -1;
        }
        for (SeasonRankRow row : topCache) {
            if (row.uuid().equals(playerId)) {
                return row.rank();
            }
        }
        return -1;
    }

    /**
     * 当前季剩余天数（<b>冻结接口</b>，单一真相源——W3A tester L4 收口：原
     * LeaderboardGUI/LLEPapiExpansion 各有一份复制实现，现两处改走本方法）：
     * 闭区间 [start,end] 语义下含今天的 +1（首日=整季天数、末日=1）；未开启返回 0。
     */
    public long daysLeft() {
        LocalDate today = LocalDate.now();
        int id = currentSeasonId(today);
        if (id <= 0) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(today, windowOf(id).end()) + 1;
    }

    /**
     * 可用性单判据（<b>冻结接口</b>）：null 安全 + 已启用（isEnabled 已折叠
     * "enabled=false" 与"锚点在未来 id=0"两种休眠）。原 LeaderboardGUI 与
     * LLEPapiExpansion 各有一份等价实现，tester L4 收口后统一走此方法。
     */
    public static boolean isAvailable(SeasonManager season) {
        return season != null && season.isEnabled();
    }

    /** 异步刷新赛季榜快照（5 分钟班车挂载点 + 结算收尾共用；未启用时清空即返）。 */
    public void refreshCacheAsync() {
        runAsync(this::refreshCacheSync);
    }

    private void refreshCacheSync() {
        if (!isEnabled()) {
            topCache = Collections.emptyList();
            return;
        }
        int id = currentSeasonId(LocalDate.now());
        SeasonWindow window = windowOf(id);
        List<LeaderboardEntry> entries = plugin.getPlayerDataStore()
                .getRankedPlayersForPeriod(CACHE_LIMIT, window.start(), window.end());
        List<SeasonRankRow> fresh = new ArrayList<>(entries.size());
        for (LeaderboardEntry entry : entries) {
            fresh.add(new SeasonRankRow(entry.rank, entry.playerId, entry.playerName, entry.score));
        }
        topCache = List.copyOf(fresh);
    }

    // ============================================================
    //  结算（onEnable 启动异步 + 午夜跨季边界，两触发点共用；幂等）
    // ============================================================

    /**
     * 结算扫描（幂等，两触发点共用——plan-season §3.2）。
     *
     * <p>游标 = {@code MAX(season_id) WHERE settled_at IS NOT NULL}，目标 =
     * {@code currentSeasonId(today) - 1}，逐季结算 {@code cursor+1 .. 目标}
     * （长停服跨多季逐季补；无数据季只写 settled 行不发奖）。<b>首次启用语义</b>
     * （seasons 表为空）：不追既往——只把当前季写成"已建立、未结算"的行即返回，
     * 防首次开启即大规模补发历史奖。后续扫描的起点 = 表内最小 season_id
     * （行只会由本方法建立，等价于"启用时刻的季"），保证只结算真正跨过的季。
     *
     * <p>防重入：执行中再次调用直接返回（onEnable 与午夜触发可能相邻）。
     * IO 全在异步任务；抢占成功才回主线程广播/发放在线者。
     */
    public void settleIfNeededAsync() {
        if (!isEnabled()) {
            // 旅程验收收口（赛季"开而不动"静默陷阱）：enabled=true 但锚点在未来时
            // 全系统休眠（isEnabled=false 是正确语义），但管理员会误判"功能没做"——
            // 启动时打一次 WARNING 说明原因与修正方法（dormancyWarned 防刷屏）。
            if (!dormancyWarned && plugin.getConfigManager().getMainConfig()
                    .getBoolean("season.enabled", false)) {
                dormancyWarned = true;
                plugin.getLogger().warning("season.enabled=true but the current season has not "
                        + "started yet (anchor-date is in the future or invalid) — season "
                        + "features stay dormant. Check season.anchor-date in config.yml.");
            }
            return;
        }
        if (!settleInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    runSettleSweep();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Season settle sweep failed", e);
                } finally {
                    settleInFlight.set(false);
                    // 结算后（或无事可做时）都重刷快照——启动路径的新赛季空态由此就位
                    refreshCacheSync();
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：任务不会执行，必须复位防重入标志
            settleInFlight.set(false);
        }
    }

    private void runSettleSweep() {
        DatabaseManager db = plugin.getDatabaseManager();
        LocalDate today = LocalDate.now();
        int currentId = currentSeasonId(today);
        if (currentId < 1) {
            return;
        }

        // 首次启用（表空）：只建立当前季行，不追既往（防首开即大规模补发历史奖）
        Integer minId = db.minSeasonId();
        if (minId == null) {
            SeasonWindow window = windowOf(currentId);
            db.ensureSeasonRow(window.id(), window.start(), window.end());
            plugin.getLogger().info("Season ledger initialized at season " + currentId
                    + " (first enable; past seasons are not settled).");
            return;
        }

        int targetId = currentId - 1;
        if (targetId < 1) {
            return; // 尚未跨过任何季边界
        }

        Long cursorObj = db.maxSettledSeasonId();
        int from = (cursorObj != null) ? (int) (long) cursorObj + 1 : minId;
        if (from > targetId) {
            return; // 常态：无未结算的已跨越季
        }

        int lastSettledId = 0;
        String lastTopName = null;
        long lastTopScore = 0;

        for (int s = from; s <= targetId; s++) {
            SeasonWindow window = windowOf(s);
            // R7-H2：只结算"真正走完"的季——anchor-date/duration 点读可改，
            // 改小锚点会让 currentSeasonId 跳变、targetId 命中仍在进行中的窗口；
            // 未走完的窗口跳过（不写台账不发放），防提前切割发奖。
            if (!window.end().isBefore(today)) {
                plugin.getLogger().warning("Skipping settlement for season " + s
                        + " (window " + window.start() + ".." + window.end()
                        + " not complete; anchor/duration changed?)");
                continue;
            }
            db.ensureSeasonRow(window.id(), window.start(), window.end());
            // 跨服聚合（查全表取 TOP N —— 多服连同一库即共享）
            List<LeaderboardEntry> top = plugin.getPlayerDataStore()
                    .getRankedPlayersForPeriod(TOP_N, window.start(), window.end());
            String snapshotJson = buildSnapshotJson(top);
            // 条件 UPDATE 抢占：唯一赢家写台账；败者（他服已结算）静默跳过
            boolean won = db.tryClaimSettlement(s, System.currentTimeMillis(), snapshotJson);
            if (!won) {
                continue;
            }
            plugin.getLogger().info("Season settled: " + s);
            if (isRewardsEnabled()) {
                List<SeasonRewardRow> rows = buildRewardRows(s, top, window);
                if (!rows.isEmpty()) {
                    db.insertSeasonRewards(rows);
                }
            }
            lastSettledId = s;
            if (!top.isEmpty()) {
                lastTopName = top.get(0).playerName;
                lastTopScore = top.get(0).score;
            }
        }

        if (lastSettledId < 1) {
            return; // 全被抢占（他服结算完成）——本服不广播不发放
        }

        // 结算 IO 已结束 → 回主线程：广播（含音效）+ 跨服子通道 + 本服在线者发放。
        // 多季 catch-up 只播最近一次的结束（历史季广播无意义，plan §3.3 伪代码的收窄）。
        final int settledId = lastSettledId;
        final String topName = lastTopName;
        final long topScore = lastTopScore;
        final int newSeasonId = currentId;
        runMain(() -> {
            broadcastEnded(settledId, topName, topScore);
            broadcastStarted(newSeasonId);
            if (plugin.getProxyMessenger() != null) {
                plugin.getProxyMessenger().sendSeasonSettled(settledId);
                plugin.getProxyMessenger().sendSeasonStarted(newSeasonId);
            }
            // 本服在线者即时发放（离线者走 join 补发；跨服在线者经他服的
            // SeasonSettled 回投触发本地同名发放）。R7-M1：一次批量查询替代
            // 逐人单查（N 人在单连接上排队 → 单条 IN 查询）。
            List<UUID> onlineIds = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                onlineIds.add(online.getUniqueId());
            }
            deliverPendingBatch(onlineIds, true);
        });
    }

    /**
     * 奖励台账行构建：分层 top1 / top2-3 / top4-10 / 参与奖（读 config
     * {@code season.rewards.*}，单位元 → 台账存"分"）。参与奖在配置 &gt;0 时对
     * 窗口内有会话的 uuid 发放（含隐身者——奖励是发放语义不受展示开关约束，
     * 与榜单展示过滤口径不同，plan §1.4）；未上榜参与者的分数字段存 0。
     * 零额行不入台账（无钱无消息）。
     */
    private List<SeasonRewardRow> buildRewardRows(int seasonId, List<LeaderboardEntry> top,
                                                 SeasonWindow window) {
        List<SeasonRewardRow> rows = new ArrayList<>();
        long top1 = rewardCents("top1", 5000.0);
        long top3 = rewardCents("top3", 2000.0);
        long top10 = rewardCents("top10", 500.0);
        Set<UUID> seen = new HashSet<>();
        for (LeaderboardEntry entry : top) {
            seen.add(entry.playerId);
            long cents = switch (entry.rank) {
                case 1 -> top1;
                case 2, 3 -> top3;
                default -> (entry.rank <= 10) ? top10 : 0L;
            };
            if (cents > 0) {
                rows.add(new SeasonRewardRow(seasonId, entry.playerId, entry.rank, entry.score, cents));
            }
        }
        long participated = rewardCents("participated", 0.0);
        if (participated > 0) {
            for (UUID participant : plugin.getDatabaseManager()
                    .getSessionParticipants(window.start(), window.end())) {
                if (seen.add(participant)) {
                    rows.add(new SeasonRewardRow(seasonId, participant, 0, 0L, participated));
                }
            }
        }
        return rows;
    }

    /** 奖励金额读配置（元，double）→ "分"（long，对齐 RewardEngine 记账惯例）。 */
    private long rewardCents(String key, double defaultValue) {
        double amount = plugin.getConfigManager().getMainConfig()
                .getDouble("season.rewards." + key, defaultValue);
        return Math.max(0L, Math.round(amount * 100.0));
    }

    /** top 快照 JSON（回顾用，免重聚合）：List&lt;{rank,uuid,name,score}&gt;。 */
    private String buildSnapshotJson(List<LeaderboardEntry> top) {
        List<Map<String, Object>> arr = new ArrayList<>(top.size());
        for (LeaderboardEntry entry : top) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", entry.rank);
            row.put("uuid", entry.playerId.toString());
            row.put("name", entry.playerName);
            row.put("score", entry.score);
            arr.add(row);
        }
        return GSON.toJson(arr);
    }

    /**
     * 解析 top_snapshot JSON → 榜单行列表（3A-4 的"上一季冠军"回顾可选用）。
     * 坏数据返回空列表，绝不抛出（回顾路径的容错优先）。
     */
    public static List<SeasonRankRow> parseTopSnapshot(String json) {
        List<SeasonRankRow> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : arr) {
                JsonObject obj = element.getAsJsonObject();
                String uuidRaw = obj.get("uuid").getAsString();
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidRaw);
                } catch (IllegalArgumentException e) {
                    continue; // 脏行跳过，不拖垮整段回顾
                }
                String name = (obj.has("name") && !obj.get("name").isJsonNull())
                        ? obj.get("name").getAsString() : uuidRaw;
                out.add(new SeasonRankRow(obj.get("rank").getAsInt(), uuid, name,
                        obj.get("score").getAsLong()));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    // ============================================================
    //  奖励发放（join 补发 + 结算即时，两条路径共用同一幂等核）
    // ============================================================

    /**
     * 进服补发检查（<b>冻结接口</b>，PlayerJoinListener 消费——对齐
     * {@code consumeOfflineDuelNotice} / join 摘要的既有消费模式）：异步读该玩家的
     * 全部 unclaimed 台账行 → 主线程条件标记（affected==1 才发）+ 发钱 + 个人消息。
     * 秒退复查不通过则不标记，留待下次进服（对齐 notifyPendingCheers 语义）。
     * 玩家在任意子服进服都能领（台账共享）。
     */
    public void claimPendingRewards(Player player) {
        deliverPending(player, false);
    }

    private void deliverPending(Player target, boolean settleTime) {
        if (!isEnabled() || target == null) {
            return;
        }
        UUID playerId = target.getUniqueId();
        runAsync(() -> {
            List<SeasonRewardRow> rows = plugin.getDatabaseManager().getPendingSeasonRewards(playerId);
            if (rows.isEmpty()) {
                return;
            }
            runMain(() -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null || !online.isOnline()) {
                    return; // 秒退：未标记未发放，留待下次进服
                }
                if (plugin.getEconomy() == null) {
                    // 无 Vault：静默降级——不标记不发放，台账留 pending（Vault 就位后
                    // 任意进服/结算路径补发）。与"只广播不发钱"一致，且不销毁奖励。
                    return;
                }
                deliverRows(online, rows, settleTime);
            });
        });
    }

    /**
     * R7-M1：结算规模收口 —— 一次批量查询（{@code getPendingSeasonRewardsFor}，
     * IN 展开）替代"逐在线玩家各一个异步任务、各自单查"的 N 连查；主线程发放核
     * 与单发版共用 {@link #deliverRows}（在线复检 → 条件标记先于发钱 → 失败回滚）。
     * 批量结果只含"至少一行 pending"的玩家（join 补发路径保留，兜底错过的玩家）；
     * 查询异常在 DB 层静默降级为空 map（发放推迟到 join 路径，与单发"秒退留待
     * 下次"同族）。
     */
    private void deliverPendingBatch(Collection<UUID> playerIds, boolean settleTime) {
        if (!isEnabled() || playerIds == null || playerIds.isEmpty()) {
            return;
        }
        List<UUID> idSnapshot = List.copyOf(playerIds);
        runAsync(() -> {
            Map<UUID, List<SeasonRewardRow>> byPlayer = plugin.getDatabaseManager()
                    .getPendingSeasonRewardsFor(idSnapshot);
            if (byPlayer.isEmpty()) {
                return;
            }
            runMain(() -> {
                if (plugin.getEconomy() == null) {
                    // 无 Vault：静默降级同单发（不标记不发放，全部留 pending）
                    return;
                }
                for (Map.Entry<UUID, List<SeasonRewardRow>> entry : byPlayer.entrySet()) {
                    Player online = Bukkit.getPlayer(entry.getKey());
                    if (online == null || !online.isOnline()) {
                        continue; // 查询间隙下线：未标记未发放，留待 join 补发
                    }
                    deliverRows(online, entry.getValue(), settleTime);
                }
            });
        });
    }

    /** 单玩家逐行发放核（join 单发 / 结算批量两路径共用）：条件标记先于发钱。 */
    private void deliverRows(Player online, List<SeasonRewardRow> rows, boolean settleTime) {
        UUID playerId = online.getUniqueId();
        for (SeasonRewardRow row : rows) {
            // 条件 UPDATE 先于发钱（CheckInManager 先例）：崩溃/双击/双服
            // 竞态都不可能二次发放
            if (!plugin.getDatabaseManager()
                    .markSeasonRewardClaimed(row.seasonId(), playerId)) {
                continue; // 已被他路径/他服标记
            }
            double paid = payReward(online, row.rewardCents());
            if (paid > 0) {
                sendRewardMessage(online, row, paid, settleTime);
            } else if (plugin.getEconomy() != null) {
                // W3A tester M1 收口：Vault 在但转账失败（economy 为 null 的
                // 分支上面已 return）——回滚 claimed，该行回到 pending 供
                // 下次 join/结算重试；不再"永久丢失且无重试面"。
                plugin.getDatabaseManager()
                        .unmarkSeasonRewardClaimed(row.seasonId(), playerId);
            }
        }
    }

    /**
     * 直发赛季奖励（不经 daily-cap，见类 javadoc 经济门裁决）：Vault
     * {@code depositPlayer} + lifetime 记账。无 Vault / 转账失败返回 0（静默降级，
     * 台账已标记不重试——与签到"先标记后付款"一致）。
     */
    private double payReward(Player player, long cents) {
        if (cents <= 0) {
            return 0;
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) {
            return 0;
        }
        double amount = cents / 100.0;
        EconomyResponse response = eco.depositPlayer(player, amount);
        if (response.transactionSuccess()) {
            // lifetime 记账（供统计/成就口径）；刻意不注入 RewardEngine.todayCoinsCache
            plugin.getDatabaseManager().recordCoinsEarned(player.getUniqueId(), amount);
            return amount;
        }
        plugin.getLogger().warning("Failed to deposit season reward for "
                + player.getName() + ": " + response.errorMessage);
        return 0;
    }

    /** 个人名次/参与消息（结算即时 = reward-earned；join 补发 = reward-pending-join）。 */
    private void sendRewardMessage(Player player, SeasonRewardRow row, double paid,
                                   boolean settleTime) {
        String key;
        String[] placeholders;
        if (row.rankPosition() <= 0) {
            key = "season.participated-reward";
            placeholders = new String[]{"amount", formatCoins(paid)};
        } else if (settleTime) {
            key = "season.reward-earned";
            placeholders = new String[]{"amount", formatCoins(paid),
                    "season", String.valueOf(row.seasonId()),
                    "rank", String.valueOf(row.rankPosition())};
        } else {
            key = "season.reward-pending-join";
            placeholders = new String[]{"rank", String.valueOf(row.rankPosition()),
                    "amount", formatCoins(paid)};
        }
        String message = plugin.getMessageUtil().getPlayerMessage(player, key, placeholders);
        if (message != null && !message.isEmpty()) {
            player.sendMessage(message);
        }
    }

    /** 金币金额玩家可见格式化（对齐 RewardEngine.formatCoins：整数不带小数点）。 */
    private String formatCoins(double v) {
        return v == Math.floor(v)
                ? String.valueOf((long) v)
                : String.format(Locale.ROOT, "%.2f", v);
    }

    // ============================================================
    //  跨服回投（ProxyMessenger 收端调用；本服玩家获结算服广播与发放）
    // ============================================================

    /**
     * 收到他服的 SeasonSettled：本服不重复结算（抢占已保证唯一赢家），只做
     * ①本地广播赛季结束（冠军读快照）②本地在线者发放 ③刷新快照。
     *
     * <p>重复投递安全：发放经 {@code claimed=0} 条件 UPDATE 幂等；回环去重由
     * ProxyMessenger 的 origin 过滤负责（{@code proxy.server-name} 为空时可能
     * 重复播报一次——配置注释已说明，发放仍不受影响）。
     */
    public void applyRelayedSettlement(int seasonId) {
        if (!isEnabled()) {
            return;
        }
        runAsync(() -> {
            String topName = null;
            long topScore = 0;
            SeasonRow row = plugin.getDatabaseManager().getSettledSeason(seasonId);
            if (row != null && row.topSnapshot() != null) {
                List<SeasonRankRow> top = parseTopSnapshot(row.topSnapshot());
                if (!top.isEmpty()) {
                    topName = top.get(0).name();
                    topScore = top.get(0).score();
                }
            }
            final String name = topName;
            final long score = topScore;
            runMain(() -> {
                broadcastEnded(seasonId, name, score);
                // R7-M1：批量发放（同结算路径；逐人单查已收口为单条 IN 查询）
                List<UUID> onlineIds = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    onlineIds.add(online.getUniqueId());
                }
                deliverPendingBatch(onlineIds, true);
            });
            refreshCacheSync();
        });
    }

    /** 收到他服的 SeasonStarted：本地广播新季开场（幂等性由"每个结算周期仅一次"保证）。 */
    public void applyRelayedSeasonStarted(int seasonId) {
        if (!isEnabled()) {
            return;
        }
        runMain(() -> broadcastStarted(seasonId));
    }

    // ============================================================
    //  播报（逐收件人语言渲染；主线程调用）
    // ============================================================

    /** 赛季结束全服广播（冠军名 + 分数；{@code season.announce} 门）。 */
    private void broadcastEnded(int seasonId, String topName, long topScore) {
        if (!isAnnounceEnabled()) {
            return;
        }
        String champion = (topName == null || topName.isEmpty()) ? "—" : topName;
        for (Player online : Bukkit.getOnlinePlayers()) {
            String message = plugin.getMessageUtil().getPlayerMessage(online,
                    "season.ended-broadcast",
                    "season", String.valueOf(seasonId),
                    "top1", champion,
                    "score1", String.valueOf(topScore));
            if (message != null && !message.isEmpty()) {
                online.sendMessage(message);
            }
        }
    }

    /** 赛季开始全服广播（+ complete 音效；plan §2.2 仪式感）。 */
    private void broadcastStarted(int seasonId) {
        if (!isAnnounceEnabled()) {
            return;
        }
        String days = String.valueOf(durationDays());
        for (Player online : Bukkit.getOnlinePlayers()) {
            String message = plugin.getMessageUtil().getPlayerMessage(online,
                    "season.started-broadcast",
                    "season", String.valueOf(seasonId),
                    "days", days);
            if (message != null && !message.isEmpty()) {
                online.sendMessage(message);
                SoundManager.playSound(online, "complete");
            }
        }
    }

    // ============================================================
    //  调度工具（对齐 SocialManager；停用窗口静默放弃）
    //  D2-5：实现已抽取至 util.SchedulerGuard，此处保留薄委托（调用点零改动）
    // ============================================================

    private void runAsync(Runnable task) {
        SchedulerGuard.runAsync(plugin, task);
    }

    private void runMain(Runnable task) {
        SchedulerGuard.runMain(plugin, task);
    }
}
