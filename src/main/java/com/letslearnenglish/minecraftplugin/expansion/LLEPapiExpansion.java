package com.letslearnenglish.minecraftplugin.expansion;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.data.CheckInData;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.data.repository.PlayerDirectoryRepository;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PlaceholderAPI expansion for Let's Learn English!
 *
 * Provides these placeholders (all support both zh/en players):
 *   %lle_score%          — total learning score
 *   %lle_coins_earned%   — lifetime coins earned
 *   %lle_rank%           — rank text ("#1" or "未上榜"/"Unranked")
 *   %lle_words%          — total words attempted
 *   %lle_accuracy%       — accuracy percentage (e.g. "85.3%")
 *   %lle_sessions%       — total learning sessions
 *   %lle_streak%         — current daily check-in streak (P3-03: sourced from
 *                          checkin_data.streak_count, not player_data)
 *   %lle_longest_streak% — longest combo streak
 *   %lle_achievements%   — "unlocked/total" (e.g. "3/22")
 *   %lle_achievement_<id>% — "✓"/"✗" for one achievement (e.g.
 *                            %lle_achievement_duel_first_win%); unknown ids
 *                            fall back to the legacy null contract
 *   %lle_correct%        — total correct answers
 *   %lle_incorrect%      — total incorrect answers
 *   %lle_season%         — current season number, e.g. "3" ("" when the season
 *                          system is disabled or not started yet)
 *   %lle_season_days_left% — days left in the current season ("" when disabled)
 *   %lle_season_rank%    — season rank text ("#7" or "未上榜"/"Unranked"; ""
 *                          when disabled)
 *
 * R-43 privacy: looking at somebody else's placeholders first checks their
 * switches — showInSquare=false (invisible) returns the private placeholder
 * (rank falls back to the unranked text so scoreboard standings keep working;
 * season_rank follows the same rank rule); showResults=false hides the
 * learning-score family. Coins follow the same rule as ProfileGUI: only the
 * owner sees their own number.
 *
 * R-43 cache: every resolved value is cached per (player, params, viewer
 * language/relationship bucket) with a 30s TTL so scoreboard refreshes no
 * longer hit the DB every tick. Thread-safe via ConcurrentHashMap; PAPI
 * callbacks run on the main thread but other plugins may call off-thread.
 *
 * R-16: %lle_coins_earned% is the one placeholder whose value is never read
 * from the DB on the calling thread — it keeps its own 60s value cache and
 * fills it from an async task, returning "" (unknown) on a miss instead of
 * blocking on a SELECT under the shared connection lock.
 *
 * <h2>「值已缓存，计分板刷新不碰 DB」这条不变量的真实边界</h2>
 *
 * <p>上面这条承诺只对<b>缓存命中</b>成立，而且只覆盖 {@link #cache} 与
 * {@link #coinsCache} 两级 TTL。TTL 过期后的首个刷新<b>必然</b>走一次
 * {@link #resolveRequest}，而它在三种情况下会同步查库：
 * <ol>
 *   <li>隐私开关 {@code loadProfileSettingsForDisplay} 的 miss（一行 player_profiles）；</li>
 *   <li>{@code longest_streak} 的单列 SELECT（{@code streak} 则读签到表）；</li>
 *   <li>PlayerStats 族的 {@code getPlayerStats} —— 它 miss 时会同步
 *       {@code loadPlayerData}（两条 SELECT + difficult_words，全抢 connectionLock）。</li>
 * </ol>
 *
 * <p><b>R22-D1 的处置正是把第 3 条收敛到「真的需要才读」</b>：{@code coins_earned} /
 * {@code rank} / {@code streak} / {@code longest_streak} / {@code achievements}
 * 这 5 个占位符一个字节的 PlayerStats 都不需要，原实现却无条件先付一次全量加载。
 * 其中 {@code coins_earned} 最刺眼 —— 它刻意不入 30s 外层缓存（否则首轮暂态空串会被
 * 冻 30 秒），于是它<b>每次</b>刷新都要多付那次同步加载，把「异步补读」的优化抵消干净。
 *
 * <p>剩下的 1、2 两条是<b>刻意保留</b>的：它们各自只是单条主键/单列 SELECT，且都是
 * 「这个值本身就必须从库里拿」的数据，异步化需要引入三态占位 UI（"加载中"），
 * 那是产品决策而不是性能修补。真实风险描述见 {@link #resolveRequest} 的注释。
 */
public class LLEPapiExpansion extends PlaceholderExpansion implements Relational {

    /** R-43：占位符缓存 TTL（毫秒）。 */
    private static final long CACHE_TTL_MILLIS = 30_000L;
    /** R-43：缓存上限条目数，超限随机淘汰一批。 */
    private static final int CACHE_MAX_ENTRIES = 5000;
    /** R-43：超限时一次淘汰的条目数。 */
    private static final int CACHE_EVICT_BATCH = 500;

    /**
     * R-16（性能）：{@code total_coins_earned} 专用缓存 TTL（毫秒）。
     *
     * <p>刻意比 {@link #CACHE_TTL_MILLIS}（30s）长一倍：该列只随发币单调递增，
     * 60s 的陈旧代价是一条「金币数慢了半分钟」的显示差，而它换来的是「每玩家每分钟
     * 最多一次异步读」的硬上限。不发币的玩家几乎零成本。
     */
    private static final long COINS_CACHE_TTL_MILLIS = 60_000L;

    /** R-16：金币读失败后的退避 TTL —— 期间返回空串，不再重复排任务（防 DB 故障期刷屏）。 */
    private static final long COINS_RETRY_TTL_MILLIS = 15_000L;

    /** R-16：金币「在途/未知」窗口的短 TTL —— 它本身就是补读去重闸门（见 {@link CoinsEntry}）。 */
    private static final long COINS_SENTINEL_TTL_MILLIS = 1_000L;

    /**
     * R-16（隐私）：学习成绩「固定键」族 —— 受目标 showResults 开关约束（非本人查看时）。
     *
     * <p>本集合只管<b>精确键</b>；{@code achievement_<id>} 是一族动态键，无法用
     * {@code contains} 表达，改由 {@link #isResultsGated(String)} 统一收口 ——
     * 两处（本集合 + 该谓词）是「哪些占位符算学习成绩」的唯一真相源，新增占位符
     * 只需往其中一处落子，门禁代码零改动。
     */
    private static final java.util.Set<String> RESULTS_GATED = java.util.Set.of(
            "score", "words", "accuracy", "sessions", "correct", "incorrect",
            // R-16：成就计数 %lle_achievements%（"3/22"）。此前漏在此集合外，
            // 导致「关掉 showResults 的玩家」在游戏内被成就墙挡住（SocialListener
            // 成就墙点击分支对目标 showResults=false 直接拒绝），PAPI 却照发解锁
            // 进度 —— 同一份隐私开关在产品内出现两套口径，是最糟的那种不一致。
            "achievements",
            // R22-D3：答题会话连击峰值 %lle_longest_streak%。它不是「活跃度」（那是
            // 签到连击 streak），而是答题成绩的直接产物 —— 峰值高 ⇔ 连续答对过很多题。
            // 关掉 showResults 的玩家仍能被他人读到它，等于用计分板绕过隐私开关。
            // 产品内先例：ProfileGUI 的决斗连胜行在 hideResults 时整行不渲染
            // （DuelStatsText 的 hideValues 分支，见 messages 的 stats-duel-streak 注释
            // 「nor when the target player has hidden their results」）。
            // 注意 %lle_streak%（签到连击）刻意<b>不</b>入本集合：它衡量的是「有没有每天
            // 来」，不透露任何答题对错，与 C3 用例「签到连击不受这条门约束」同口径。
            "longest_streak");

    private final LetsLearnEnglish plugin;

    /** R6-A：player_data 单列读取仓储（SQL 与参数绑定已收口）。 */
    private final PlayerDirectoryRepository playerDirectory;

    /** R-43: key = targetUuid + "|" + params + "|" + selfFlag + "|" + lang. */
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    /**
     * R-16（性能）：{@code %lle_coins_earned%} 的独立值缓存 —— 全类唯一一条
     * **不靠 DB 读取**拿数据的占位符路径。
     *
     * <p><b>为什么必须独立于 {@link #cache}</b>：后者只缓存「已经算出来的字符串」，
     * 而且它<b>不覆盖 DB 读取本身</b> —— 缓存 miss 时仍要同步查库。原实现在
     * {@code case "coins_earned"} 直接调 {@code DatabaseManager.getCoinsEarned}，
     * 即：任何一次 30s TTL 过期后的首个 scoreboard 刷新，都会在<b>调用线程</b>
     * 上同步做一次 {@code SELECT} 并抢 {@code connectionLock}。占位符按计分板刷新
     * 频率解析（每秒数次/每玩家），这条路径与会话结算、周期落盘、榜单刷新抢同一把
     * 全局连接锁 —— 正是能把服务器拖住的那个形状。本缓存把「拿到值」与「用值」
     * 解耦：命中直接返回，未命中返回 {@link #COINS_UNKNOWN} 并<b>在后台</b>补一次。
     *
     * <p>键只有 UUID：该值是玩家级标量，与查看语言/视角无关（币值走门禁 3
     * 「只给本人」，与 {@link #cache} 的 language/relationship 分桶无关）。
     */
    private final Map<UUID, CoinsEntry> coinsCache = new ConcurrentHashMap<>();

    /**
     * R-16：{@link #coinsCache} 的条目上限。与 {@link #CACHE_MAX_ENTRIES} 同理超限淘汰，
     * 但键是玩家 UUID（数量级远小于「玩家 × 占位符 × 视角 × 语言」），故上限取小得多。
     * 不设上限的话，一个长期运行的服会为每个曾解析过该占位符的玩家留一条永久条目。
     */
    private static final int COINS_CACHE_MAX_ENTRIES = 2000;

    /**
     * R-16：{@code %lle_coins_earned%} 在缓存未命中时的返回值 —— 空串，不是 {@code "0"}。
     *
     * <p>选空串而不是 0：0 是一个<b>看起来很可信的假数</b>（玩家会以为自己的累计
     * 金币被清零了），而空串在计分板上只是一个暂时空位，下一次刷新就填上。宁可少显示
     * 一秒，也不要发一个错误数字。
     */
    private static final String COINS_UNKNOWN = "";

    /**
     * R-16：金币值缓存条目。
     *
     * <p>用 {@code Boolean value == null} 表达「还不知道」这个三态，而不是用 {@code 0}
     * 冒充：{@code total_coins_earned} 的真实值 0 与「读失败/尚未读到」在展示上是
     * 两件事（前者该显示 0，后者该显示空串）。把两者合成一个 {@code 0} 就是本类
     * 反复踩过的那类「不可信被当成真值」的坑。
     *
     * <p><b>「在途加载」不需要额外的布尔位</b>：{@code value == null && expiresAt 未到}
     * 就是这个状态 —— 未命中的调用方把条目的过期时刻写成很短的
     * {@link #COINS_SENTINEL_TTL_MILLIS}，于是这个窗口本身就是去重闸门；异步读完成后
     * 用真值 + 正常 TTL 覆写。慢库导致窗口先过期最多多排一次读，是有界退化，
     * 不值得为它引入第二套状态位。
     */
    private static final class CoinsEntry {
        /** {@code null} = 读失败或尚未读到（未知）；非 null = 已确认的库内值。 */
        final Long value;
        final long expiresAt;

        CoinsEntry(Long value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private static final class CachedValue {
        final String value;
        final long expiresAt;

        CachedValue(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    public LLEPapiExpansion(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.playerDirectory = new PlayerDirectoryRepository(plugin.getDatabaseManager(),
                plugin.getLogger());
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "lle";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return plugin.getDescription().getAuthors().isEmpty()
                ? "sqkl520" : plugin.getDescription().getAuthors().get(0);
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Keep registered across /papi reload
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) {
            // R22-D5：null player 时也必须守住「归属契约」——未知占位符返回 null
            // （= 不归我管，让别的 expansion 试），只有<b>已知</b>占位符才返回空串
            // （= 归我管，但没有值）。原实现在 params 判定之前无差别 return ""，
            // 于是 %lle_<任意东西>% 都被本扩展宣称「归我管」，把别人注册在 lle
            // namespace 下的占位符静默吃掉。判据复用 resolveCached 的同一套预检，
            // 保证「已知」的定义只有一个真相源。
            return isKnownOrValidAchievement(params) ? "" : null;
        }
        // R-43 缓存键必须区分查看语言与查看关系。
        Player langPlayer = offlinePlayer.isOnline() ? offlinePlayer.getPlayer() : null;
        // sec-01：本入口拿不到真正的调用者身份（PAPI 只回传 target），一律按「非本人」处理
        // （fail-closed）。原实现用「目标在线」冒充本人，使三门对任何在线玩家全部短路。
        // 需要本人视角请用 %rel_lle_<params>%（见 onPlaceholderRequest）。
        return resolveCached(offlinePlayer, langPlayer, false, params);
    }

    /**
     * R22-D5：{@code params} 是否落在本扩展的管辖范围内。
     *
     * <p>{@link #resolveCached} 里的两段预检（动态成就位合法 id → 固定集合）在此收口成
     * 一个谓词，供 null-player 分支复用 —— 「已知」的定义只允许存在一处，
     * 否则两条路径会慢慢分叉（本类已经因为「两处各判一遍」踩过隐私口径不一致的坑）。
     *
     * @param params 占位符参数名，非 null
     * @return {@code true} = 归本扩展管（无值时该给空串）；{@code false} = 不归本扩展管（该给 null）
     */
    private boolean isKnownOrValidAchievement(@NotNull String params) {
        if (params.startsWith("achievement_")) {
            return Achievement.fromId(params.substring("achievement_".length())) != null;
        }
        return isKnownParams(params);
    }

    /**
     * sec-01：关系型入口 %rel_lle_&lt;params&gt;%。PAPI 传入真正的两个 Player
     * （viewer, target），这里才拿得到真实调用者身份，而非「目标在线」的冒充值。
     */
    @Override
    public String onPlaceholderRequest(Player viewer, Player target, String params) {
        if (viewer == null || target == null || params == null) {
            return null;
        }
        return resolveCached(target, viewer,
                viewer.getUniqueId().equals(target.getUniqueId()), params);
    }

    /** 参数预检 + 语言判定 + 30s 缓存 + 解析。{@code isSelfView} 由调用方以真实身份给出。 */
    private String resolveCached(OfflinePlayer offlinePlayer, Player langPlayer,
                                 boolean isSelfView, @NotNull String params) {
        UUID playerId = offlinePlayer.getUniqueId();

        // plan-achievements §3.5：动态成就位 %lle_achievement_<id>%。
        // 合法性预检短路在 isKnownParams 之前（isKnownParams 保持固定集合不动）：
        // 未知 id（fromId 返回 null）保留 legacy 契约（null = 让其他 expansion 尝试，
        // 同下方 R-43 注释）；合法 id 继续走下方缓存 + resolveRequest 路径——
        // 30s TTL 缓存按 params（即 achievement_<id>）分键，动态 key 天然适配。
        boolean dynamicAchievement = params.startsWith("achievement_");
        if (dynamicAchievement && Achievement.fromId(params.substring("achievement_".length())) == null) {
            return null;
        }

        // R-43: unknown params keep the legacy contract (null = let other
        // expansions try). They are never cached.
        if (!dynamicAchievement && !isKnownParams(params)) {
            return null;
        }

        // 同 MessageUtil.resolvePrefix 风格：先判 null 再取值，不做无守卫的链式解引用
        // （loadAllConfigs 失败时 getMainConfig() 可能为 null）；取不到回落 zh。
        ConfigManager configManager = plugin.getConfigManager();
        FileConfiguration mainConfig = configManager == null ? null : configManager.getMainConfig();
        String lang = langPlayer != null
                ? plugin.getPlayerLanguage(langPlayer)
                : (mainConfig != null ? mainConfig.getString("general.language", "zh") : "zh");
        String cacheKey = playerId + "|" + params + "|" + (isSelfView ? "self" : "other") + "|" + lang;

        long now = System.currentTimeMillis();
        CachedValue cached = cache.get(cacheKey);
        if (cached != null) {
            if (now < cached.expiresAt) {
                return cached.value;
            }
            cache.remove(cacheKey, cached);
        }

        String resolved;
        try {
            resolved = resolveRequest(langPlayer, playerId, params, isSelfView);
        } catch (RuntimeException e) {
            // slice-02-eh-08：下游异常不穿透 PAPI 调用者、不编造数值、不写缓存，返回 null（下轮重试）。
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Failed to resolve placeholder " + params + " for " + playerId, e);
            return null;
        }
        if (resolved != null) {
            maybeEvict();
            // R-16：coins_earned 不进本缓存 —— 它自己已有 coinsCache（专门管「值 + TTL
            // + 后台补读」），再套一层 30s 会把「首轮暂态空串」冻上整整 30 秒：异步读
            // 毫秒级就回来了，玩家却要盯着空位半分钟。它的解析成本只有一次
            // ConcurrentHashMap 查询，不需要外层兜底。
            if (!params.equals("coins_earned")) {
                cache.put(cacheKey, new CachedValue(resolved, now + CACHE_TTL_MILLIS));
            }
        }
        return resolved;
    }

    /**
     * 解析单个占位符的实际取值（不含缓存）。
     *
     * <p><b>参数语义</b>：{@code viewer} 就是「看这一屏的人」，用于两种 i18n 取值
     * （隐私文案 / 未上榜文案）与 {@code isSelfView} 判定；{@code playerId} 是被查看的
     * 目标。R22-D6 之后<b>不再</b>需要单独的 {@code offlinePlayer} 形参 —— 原实现用它
     * 判「目标是否在线」来决定未上榜文案的语言，而正确口径是查看者语言（文案渲染在
     * 查看者屏幕上），目标是否在线与它无关。
     *
     * @param viewer      查看者（plain 入口为 null = 拿不到调用者身份，回落服务端默认语言）
     * @param playerId    目标玩家 UUID
     * @param params      占位符参数名（已知集合内或合法成就 id）
     * @param isSelfView  查看者是否就是目标本人
     * @return 解析结果；{@code null} = 不归本扩展管（永不缓存）
     */
    private String resolveRequest(Player viewer,
                                  UUID playerId, @NotNull String params, boolean isSelfView) {
        PlayerDataStore store = plugin.getPlayerDataStore();

        // L-05：展示路径走只读版，不建缓存条目。
        // sec-01：isSelfView 现由真实调用者身份决定（关系型入口 viewer==target 才算本人；
        // 无 viewer 上下文的 plain 入口一律非本人），miss 的 fail-closed 仍与「已隐藏」同口径。
        // 榜单全息字、别的插件 /papi parse 对离线玩家走 showInSquare/showResults 的公开判定。
        // 改用 loadProfileSettingsForDisplay：缓存命中零 IO；miss 时同步查一行
        // player_profiles（与本方法已有的 getPlayerStats / getCoinsEarned 同级成本），
        // 只有查询失败才落 unresolvedFallback()。同样绝不写本质缓存。
        PlayerDataStore.ProfileSettings ps = store.loadProfileSettingsForDisplay(playerId);

        // plan-season §2.5：三个赛季占位符在隐私门禁之前解析 ——
        // ① season.enabled=false 或赛季未开启（锚点在未来，id≤0）→ 空串真 gating，
        //    优先于一切（含隐私占位语义）；
        // ② %lle_season% / %lle_season_days_left% 是服务器级状态（纯计算，零个人信息，
        //    隐私门禁不适用 —— 离线目标的非本人视角也返回真实赛季数据）；
        // ③ %lle_season_rank% 含个人信息，遵循 rank 同款规则（非本人查看隐身者
        //    → 未上榜文本，与下方门禁 1 的 rank 例外同语义）。
        if (params.equals("season") || params.equals("season_days_left")
                || params.equals("season_rank")) {
            SeasonManager season = plugin.getSeasonManager();
            if (!isSeasonAvailable(season)) {
                return ""; // 真 gating：未启用/未开启一律空串，不进任何隐私回落
            }
            if (params.equals("season")) {
                return String.valueOf(season.currentSeasonId(LocalDate.now()));
            }
            if (params.equals("season_days_left")) {
                return String.valueOf(seasonDaysLeft(season));
            }
            if (!isSelfView && !ps.isShowInSquare()) {
                return getUnrankedText(viewer);
            }
            int seasonRank = season.getRankOf(playerId);
            return seasonRank > 0 ? "#" + seasonRank : getUnrankedText(viewer);
        }

        // R-43 门禁 1：隐身者（showInSquare=false）—— 非本人查看时，
        // 所有占位符返回隐私占位串；rank 例外：返回未上榜文本（榜单占位符的
        // 既有语义，P0 R-05 SQL 已把隐身者排除出榜，此处与之对齐）。
        if (!isSelfView && !ps.isShowInSquare()) {
            if (params.equals("rank")) {
                return getUnrankedText(viewer);
            }
            return getPrivateText(viewer);
        }

        // R-43 门禁 2：学习成绩族叠加目标 showResults 检查（非本人查看时）。
        // R-16：判定收口到 isResultsGated —— 固定键（RESULTS_GATED 集合）与动态族
        // （achievement_<id>）走同一处裁决，不再各自判一遍。
        if (!isSelfView && !ps.isShowResults() && isResultsGated(params)) {
            return getPrivateText(viewer);
        }

        // R-43 门禁 3：金币只给本人（与 ProfileGUI R-38 金币规则对齐）。
        if (!isSelfView && params.equals("coins_earned")) {
            return getPrivateText(viewer);
        }

        // plan-achievements §3.5：动态成就位 %lle_achievement_<id>% 的取值。
        // 解析放在三门之后 = 与 %lle_achievements% 计数占位符同口径：门禁 1（隐身者
        // 非本人查看 → 隐私占位串）与门禁 2（R-16：showResults=false 的非本人查看 →
        // 隐私占位串，见 isResultsGated）都已在上方返回，走到这里的动态成就位一定
        // 是「本人 或 目标公开了结果」。输出字符 ✓/✗ 语言中立、不进新 i18n
        // （计分板空间极小），与规划一致。
        if (params.startsWith("achievement_")) {
            Achievement achievement = Achievement.fromId(params.substring("achievement_".length()));
            if (achievement == null) {
                return null; // onRequest 已预检；防御性兜底 —— null 永不缓存
            }
            boolean unlocked = plugin.getAchievementManager().hasAchievement(playerId, achievement);
            return unlocked ? "✓" : "✗";
        }

        // R22-D1（性能，不是取值错误）：PlayerStats 只在这几个占位符真正需要时才读。
        //
        // 原实现把 getPlayerStats 无条件放在 switch 之前，而它在缓存 miss 时会<b>同步</b>
        // 调 loadPlayerData（word_progress + player_data 两条 SELECT，外加 difficult_words），
        // 全部抢 connectionLock。这对 score/words/... 是必需的，但下面这 5 个占位符
        // 一个字节的 PlayerStats 都不需要 —— 而它们里有 3 个（coins_earned / streak /
        // longest_streak）自己就要查库，等于一次刷新多付一次与结果无关的全量加载。
        // 最刺眼的是 coins_earned：它刻意<b>不入</b>外层 30s 缓存（否则首轮暂态空串会被
        // 冻 30 秒，见 getCoinsCached），所以它每次刷新都走到这里，一次摊销都没有 ——
        // 「异步补读金币」这个优化被同一次调用里的同步 SELECT 原样抵消。
        //
        // 这不改变任何返回值，只是把「读什么」收敛到「这个占位符真的需要什么」。
        switch (params) {
            // R-16（性能）：原实现直连 DatabaseManager.getCoinsEarned —— 那是一次
            // 同步 SELECT 且抢 connectionLock，而本类的既有不变量（见类 javadoc）是
            // 「值已缓存，计分板刷新不碰 DB」。占位符每题秒级刷新，冲突面是整个
            // 单连接库。改走 getCoinsCached：命中零 IO，未命中返回空串并在后台补读。
            case "coins_earned" -> {
                return getCoinsCached(playerId);
            }
            case "rank" -> {
                int rank = plugin.getProgressManager().getLeaderboard().getPlayerRank(playerId);
                return rank > 0 ? "#" + rank : getUnrankedText(viewer);
            }
            case "streak" -> {
                // P3-03：签到连击（checkin_data.streak_count），与 PlayerStats 无关。
                return String.valueOf(getCurrentCheckInStreak(playerId));
            }
            case "longest_streak" -> {
                // 答题会话连击峰值（player_data.longest_streak），直查单列，与 PlayerStats 无关。
                return String.valueOf(getLongestStreak(playerId));
            }
            case "achievements" -> {
                int unlocked = plugin.getAchievementManager().getUnlockedCount(playerId);
                int total = Achievement.values().length;
                return unlocked + "/" + total;
            }
            default -> {
                // 落到下方 PlayerStats 族（score / words / accuracy / sessions /
                // correct / incorrect）。switch 穷尽性由 isKnownParams 的固定集合保证。
            }
        }

        PlayerDataStore.PlayerStats stats = store.getPlayerStats(playerId);
        if (stats == null) {
            // R22-D15：这条兜底只服务 PlayerStats 族，故一律 "0" —— 原先那句
            // `params.equals("rank") ? 未上榜 : "0"` 的分支随着 rank 上移已成为死代码，
            // 而「coins_earned 也走这里返回 0」这个副作用（与 COINS_UNKNOWN 的空串契约
            // 自相矛盾，同一份代码里「金币未知」有两种展示）也随之消失。
            return "0";
        }

        return switch (params) {
            case "score" -> String.valueOf(stats.getTotalScore());
            case "words" -> String.valueOf(stats.getTotalWords());
            case "accuracy" -> {
                int total = stats.getTotalCorrect() + stats.getTotalIncorrect();
                if (total == 0) {
                    yield "0.0%";
                }
                double acc = (double) stats.getTotalCorrect() / total * 100.0;
                // R22-D11：必须显式 Locale.ROOT。默认 Locale 在德语/法语环境输出
                // "75,0%"（逗号小数点），第三方插件解析这个数字会静默得到 0 或 NaN。
                // 仓库其余格式化（ReportGenerator / ChallengeCompleteGUI / DailyChallengeGUI）
                // 一律 Locale.ROOT，此处与之对齐。
                yield String.format(java.util.Locale.ROOT, "%.1f%%", acc);
            }
            case "sessions" -> String.valueOf(stats.getTotalSessions());
            case "correct" -> String.valueOf(stats.getTotalCorrect());
            case "incorrect" -> String.valueOf(stats.getTotalIncorrect());
            default -> null; // 不可达：固定集合外的键已在 resolveCached 返回 null
        };
    }

    /**
     * R-16（性能）：{@code %lle_coins_earned%} 的缓存读取入口 —— <b>调用线程永不碰 DB</b>。
     *
     * <p><b>为什么不能直接用 PlayerDataStore 的现成读法</b>：本类其余占位符走
     * {@code store.getPlayerStats(playerId)}，而它 miss 时会<b>同步</b>调
     * {@code loadPlayerData}（两条 SELECT + 一次 {@code difficult_words} 读）。
     * 那只是把同一个缺陷换个位置：PAPI 的解析线程不受控（主线程、别的插件的异步
     * 任务、甚至 HTTP 面板线程都会调），在任意线程上同步查库仍是抢
     * {@code connectionLock}。{@code PlayerDataStore} 目前也<b>没有</b>
     * 「只读缓存、miss 不触发加载」的 lifetime 金币访问器 —— 可复用的
     * {@code getWrongCountIfCached} 形态只存在于错题计数，coins 没有对应物。
     *
     * <p>故本类自己持一份「值 + 过期时刻」的小缓存，把读库推到后台：
     * <ol>
     *   <li>命中且未过期 → 直接返回字符串（零 IO）；</li>
     *   <li>未命中/已过期 → 返回 {@link #COINS_UNKNOWN}（空串），并<b>仅当
     *       没有在途加载时</b>投递一次异步读（{@link CoinsEntry#loading} 去重）；</li>
     *   <li>本轮读到后写入缓存，下一次计分板刷新自然拿到真值。</li>
     * </ol>
     *
     * <p><b>代价是一次刷新周期的空值</b>：首次解析（或 60s 过期后的首次解析）
     * 会短暂显示空串。这是刻意选择的交换——空串是一个「还没有」的诚实表达，
     * 而同步查库换来的是把服务器拖住的风险。金币不是必须实时到秒的数字。
     *
     * <p><b>正确性</b>：{@code total_coins_earned} 只随发币单调递增（
     * {@code DatabaseManager.adjustCoins} 只有 {@code col = col + amount} 一条写路径），
     * 因此 60s 陈旧值最多是「偏少」，绝不会显示一个玩家从未拥有过的数字；
     * {@code /lea reset} 把行删掉后最坏显示 60s 旧值，属可接受窗口。
     *
     * @param playerId 目标玩家（非 null，由调用链保证）
     * @return 已确认的库内值字符串；暂不可知时返回空串（永不返回 {@code null}，
     *         使上游缓存不会因「未知」而反复失效）
     */
    private String getCoinsCached(UUID playerId) {
        long now = System.currentTimeMillis();
        CoinsEntry entry = coinsCache.get(playerId);
        if (entry != null && now < entry.expiresAt) {
            // 命中窗口内：null = 上一轮读失败（退避未到），仍是「未知」。
            return entry.value == null ? COINS_UNKNOWN : String.valueOf(entry.value);
        }
        // 从未读过 / 已过期：抢占「在途」窗口（短 TTL 的未知条目），抢到的那个线程
        // 负责排一次异步读；并发调用方看到这个未过期的未知条目就只返回空串。
        CoinsEntry claiming = new CoinsEntry(null, now + COINS_SENTINEL_TTL_MILLIS);
        CoinsEntry prev = coinsCache.putIfAbsent(playerId, claiming);
        if (prev != null) {
            // 别人先一步占了窗口（或填了真值）：不重复排队。
            if (now < prev.expiresAt) {
                return prev.value == null ? COINS_UNKNOWN : String.valueOf(prev.value);
            }
            // 已过期 → 本次抢到补读权。用 replace(旧值, 新值) 而非裸 put：并发下另一个
            // 线程可能刚好把**真值**写进来，裸 put 会把它盖成「未知」，凭空多一次空串
            // 展示。replace 只有「旧条目还是我看到的那个过期条目」时才生效。
            if (!coinsCache.replace(playerId, prev, claiming)) {
                return COINS_UNKNOWN; // 被别人抢先换了条目 —— 下一轮刷新再读
            }
        }
        scheduleCoinsLoad(playerId);
        return COINS_UNKNOWN;
    }

    /**
     * R-16：异步补读 {@code total_coins_earned} —— 唯一一处真正碰该列的代码。
     *
     * <p>用 {@code runTaskAsynchronously} 而非主线程任务：读取本身是阻塞 JDBC，
     * 放主线程等于把「计分板刷新」变成 tick 卡顿源。写入 {@link #coinsCache}
     * 是无锁的 {@code ConcurrentHashMap.put}，跨线程安全。
     *
     * <p>失败/异常口径：一律落 {@code value = null} + {@link #COINS_RETRY_TTL_MILLIS}
     * 退避，<b>不记 WARNING</b>（PAPI 每秒可能被调多次；{@code getCoinsEarned} 内部
     * 已按行记过日志），也<b>绝不</b>把失败折成 0 写进缓存 —— 那会把「不知道」
     * 固化成「确实是 0」。
     */
    private void scheduleCoinsLoad(UUID playerId) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                Long value = null;
                try {
                    // R22-D2：走三态版而不是 getCoinsEarned —— 后者的 SQLException 分支
                    // return 0L，与「确实赚了 0 枚」逐字节同形，本方法下方 finally 里的
                    // 「失败 → null + 退避」承诺因此永远收不到信号（异常在 DatabaseManager
                    // 内部就被吃掉了）。失败必须显式落成「未知」，否则记分板会显示一个
                    // 看起来可信的假 0，并走 60s 确认值 TTL（而不是 15s 退避）。
                    DatabaseManager.CoinsEarnedRead read =
                            plugin.getDatabaseManager().getCoinsEarnedWithStatus(playerId);
                    value = read.readFailed() ? null : read.value();
                } catch (RuntimeException e) {
                    // 连接层运行期异常（重连失败等）：保持未知，等退避后重试。
                } finally {
                    long ttl = value != null ? COINS_CACHE_TTL_MILLIS : COINS_RETRY_TTL_MILLIS;
                    maybeEvictCoins();
                    coinsCache.put(playerId, new CoinsEntry(value,
                            System.currentTimeMillis() + ttl));
                }
            });
        } catch (RuntimeException e) {
            // 调度器已关闭（插件卸载中）/ 非主线程调度被拒：落退避条目让下次能重试，
            // 保持未知而不是伪造 0。占位符解析绝不能因为「排不上任务」而抛给调用方。
            coinsCache.put(playerId, new CoinsEntry(null,
                    System.currentTimeMillis() + COINS_RETRY_TTL_MILLIS));
        }
    }

    /**
     * R-16（隐私）：这个 params 是否属于「学习成绩」族 —— 即非本人查看时是否要
     * 叠加目标 {@code showResults} 门禁。
     *
     * <p><b>为什么不能只用 {@code RESULTS_GATED.contains}</b>：{@code achievement_<id>}
     * 是一族动态键（22 个成就 id 各一个，见 {@link Achievement}），集合字面量表达不了。
     * 若为此把 22 个 id 全铺进集合，将来新增成就会静默漏门（集合不会报错，占位符
     * 照样泄漏）—— 这正是一条「忘同步」缺陷的温床。故改为<b>前缀判定</b>：
     * 族成员由 {@code achievement_} 前缀一次性覆盖，集合只保留固定键。
     *
     * <p><b>为什么前缀判定在这里是安全的</b>：{@code achievement_} 前缀在
     * {@link #resolveCached} 已被预检过 —— 未知 id 早已返回 {@code null} 短路，
     * 走到本方法的 {@code achievement_<id>} 一定是合法成就。因此这里不需要
     * 再做一次 {@code Achievement.fromId} 校验（门禁要判的是「类别」不是「合法性」）。
     *
     * <p><b>与产品内其它入口的口径对齐</b>：游戏内成就墙点击分支
     * （{@code SocialListener}）对「非本人 + 目标 showResults=false」直接拒绝并提示
     * 不公开；本谓词让 PAPI 侧同受该开关约束。若只补 {@code achievements} 计数
     * 而漏掉 {@code achievement_<id>}，玩家仍能通过计分板逐位探测「解锁了哪几个」——
     * 信息量比一个总数更大，隐私收益归零。故两者必须同门。
     *
     * <p>响应值沿用 {@link #getPrivateText}（i18n 键 {@code placeholder.private}，
     * zh「已隐藏」/ en「Hidden」）：与门禁 1（隐身）逐字同款，查看方分辨不出
     * 「被隐身挡」还是「被结果开关挡」，避免以错误文案反推对方隐私设置。
     *
     * @param params 已归一化（PAPI 传入即小写）的占位符参数名，非 null
     * @return {@code true} = 属于学习成绩族，非本人查看时须叠加 showResults 门禁
     */
    private static boolean isResultsGated(@NotNull String params) {
        return RESULTS_GATED.contains(params) || params.startsWith("achievement_");
    }

    private boolean isKnownParams(String params) {
        return switch (params) {
            case "score", "coins_earned", "rank", "words", "accuracy", "sessions",
                    "streak", "longest_streak", "achievements", "correct", "incorrect",
                    // plan-season §2.5：三个赛季占位符（固定集合扩展，非动态 key）
                    "season", "season_days_left", "season_rank" -> true;
            default -> false;
        };
    }

    /**
     * plan-season §2.5 真 gating 判定（tester L4 收口：走 {@link SeasonManager#isAvailable}
     * 单一真相源，原跨层复制实现已废——expansion 层直接委托 core 层静态方法，
     * 不再有"改动须两侧同步"的漂移面）。
     */
    private boolean isSeasonAvailable(SeasonManager season) {
        return SeasonManager.isAvailable(season);
    }

    /**
     * plan-season §2.5：剩余天数（tester L4 收口：委托 {@link SeasonManager#daysLeft()}
     * 单一真相源，闭区间含今天语义由 core 层保证）。
     */
    private long seasonDaysLeft(SeasonManager season) {
        return season == null ? 0 : season.daysLeft();
    }

    /**
     * R-43：缓存超限时随机淘汰一批。ConcurrentHashMap 的 keySet 迭代器是
     * 弱一致的，多线程下安全；淘汰不精确计数，够用即可。
     */
    private void maybeEvict() {
        if (cache.size() < CACHE_MAX_ENTRIES) {
            return;
        }
        int removed = 0;
        for (String key : cache.keySet()) {
            if (removed >= CACHE_EVICT_BATCH) {
                break;
            }
            if (cache.remove(key) != null) {
                removed++;
            }
        }
    }

    /**
     * R-16：{@link #coinsCache} 超限时淘汰一批 —— 与 {@link #maybeEvict()} 同范式
     * （弱一致迭代器，多线程下安全；不精确计数，够用即可）。
     */
    private void maybeEvictCoins() {
        if (coinsCache.size() < COINS_CACHE_MAX_ENTRIES) {
            return;
        }
        int removed = 0;
        for (UUID key : coinsCache.keySet()) {
            if (removed >= CACHE_EVICT_BATCH) {
                break;
            }
            if (coinsCache.remove(key) != null) {
                removed++;
            }
        }
    }

    private String getPrivateText(Player viewer) {
        if (viewer != null) {
            return plugin.getMessageUtil().getPlayerMessage(viewer, "placeholder.private");
        }
        return plugin.getMessageUtil().getMessage("placeholder.private");
    }

    /**
     * R-05 / R22-D6：{@code rank} / {@code season_rank} 的「未上榜」文案。
     *
     * <p><b>语言口径必须是查看者</b>：占位符的输出是渲染在<b>查看者</b>屏幕上的
     * 计分板/全息字，{@link #getPrivateText} 也一直按查看者取（同一方法里的两条回落
     * 路径必须同口径）。原实现按<b>目标</b>玩家语言取，于是英文客户端看中文目标的
     * 名次会拿到中文「未上榜」，而缓存键里的 {@code lang} 段又是查看者语言 ——
     * 同一次调用里两套语言口径。
     *
     * <p>离线/无查看者上下文（plain 入口，PAPI 只回传 target）时无法知道查看者语言，
     * 退回服务端默认语言：此时「目标是否为离线玩家」不再是判据 —— 因为本人视角
     * （关系型入口）也走这条路径，而本人一定是在线的。
     *
     * @param viewer 查看者（可为 null = plain 入口拿不到调用者）
     */
    private String getUnrankedText(Player viewer) {
        if (viewer != null) {
            return plugin.getMessageUtil().getPlayerMessage(viewer, "placeholder.rank-unranked");
        }
        return plugin.getMessageUtil().getMessage("placeholder.rank-unranked");
    }

    /**
     * P3-03：current_streak 语义 = 每日签到连击，唯一真源是
     * {@code checkin_data.streak_count}（CheckInManager 写入）。
     * {@code player_data.current_streak} 列已废弃（历史零写入，恒 0），
     * 本占位符不再读它。数据库异常时返回 0（与旧实现同口径）。
     */
    private int getCurrentCheckInStreak(UUID playerId) {
        CheckInData data = plugin.getDatabaseManager().getCheckInData(playerId);
        return data == null ? 0 : data.getStreakCount();
    }

    /**
     * longest_streak = 答题会话连击峰值（PlayerDataStore.persistLongestStreak 写入
     * player_data.longest_streak，只增不减），与签到连击是两个不同语义，保持直查该列。
     * R6-A：SQL 收口到 {@link PlayerDirectoryRepository#findLongestStreak}（锁内取
     * 连接）；异常语义原样保留——静默返回 0，不记日志（PAPI 每秒可能被调多次）。
     */
    private int getLongestStreak(UUID playerId) {
        return playerDirectory.findLongestStreak(playerId);
    }
}
