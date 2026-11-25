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
import org.bukkit.plugin.IllegalPluginAccessException;
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
 *   %lle_accuracy_raw%   — the same accuracy as a bare number (e.g. "85.3").
 *                          The "%" is what makes Integer.parseInt / Double.parseDouble
 *                          fail in AJLeaderboards / Autorank / ConditionalEvents, whose
 *                          docs only require "a placeholder that returns a number".
 *   %lle_sessions%       — total learning sessions
 *   %lle_streak%         — current daily check-in streak (P3-03: sourced from
 *                          checkin_data.streak_count, not player_data)
 *   %lle_longest_streak% — longest combo streak
 *   %lle_achievements%   — "unlocked/total" (e.g. "3/22")
 *   %lle_achievements_raw% — the unlocked count alone (e.g. "3"), without the
 *                          "/22" suffix — same reason as {@code accuracy_raw}
 *   %lle_achievement_<id>% — "✓"/"✗" for one achievement (e.g.
 *                            %lle_achievement_duel_first_win%); unknown ids
 *                            fall back to the legacy null contract
 *   %lle_correct%        — total correct answers
 *   %lle_incorrect%      — total incorrect answers
 *   %lle_duel_wins%      — duel wins as a bare integer ("" while unknown — see
 *                          below; the wins figure comes from the duel_stats
 *                          aggregate, which must never be read on the calling
 *                          thread)
 *   %lle_review_due%     — words due for review (currently always "" — see below)
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
 * R-47: %lle_rank% follows the same shape for a different reason —
 * its read is not a fast key lookup but an O(player-count) COUNT(*) with a
 * correlated EXISTS subquery (measured 350ms at 10k players, 2.5s at 50k).
 * It used to run on the PAPI calling thread (the scoreboard's main thread),
 * and its 30s cache is keyed per player, so a 20-player scoreboard produced
 * 20 main-thread O(N) queries every 30 seconds. It now keeps its own
 * 30s value cache filled from an async task. On a miss it returns "" (unknown)
 * — not "unranked", because "unranked" is a confirmed conclusion while a
 * cache miss is merely "not known yet". See {@link #RANK_UNKNOWN}.
 *
 * <p>Differs from coins on the <b>expired</b> path: a value that is merely
 * stale is still a value, so rank is <b>stale-while-revalidate</b> — it returns
 * the last confirmed conclusion and refreshes in the background, returning ""
 * only when no value was ever read. Returning "" on every expiry would leave
 * the scoreboard cell blank for the length of one O(N) query every 30s per
 * player, because the outer 30s string cache expires slightly after this one
 * and refuses to cache the temporary "". See {@link #getRankCached}.
 *
 * <h2>「值已缓存，计分板刷新不碰 DB」这条不变量的真实边界</h2>
 *
 * <p>上面这条承诺只对<b>缓存命中</b>成立，而且只覆盖 {@link #cache} 与
 * {@link #coinsCache} / {@link #rankCache} 这几级 TTL。TTL 过期后的首个刷新<b>必然</b>走一次
 * {@link #resolveRequest}，而它在三种情况下会同步查库：
 * <ol>
 *   <li>隐私开关 {@code loadProfileSettingsForDisplay} 的 miss（一行 player_profiles）；</li>
 *   <li>{@code longest_streak} 的单列 SELECT（{@code streak} 则读签到表）；</li>
 *   <li>PlayerStats 族的 {@code getPlayerStats} —— 它 miss 时会同步
 *       {@code loadPlayerData}（两条 SELECT + difficult_words，全抢 connectionLock）。</li>
 * </ol>
 *
 * <p>R-47 之后 {@code rank} 已从这条「同步查库」清单里移出（改走
 * {@link #getRankCached} 的异步补读，见类顶部说明）；它对外层缓存的写入由
 * {@link #shouldCacheOuter} 约束 —— 只有拿到确定性结论（名次 / 已确认未上榜）才缓存，
 * 暂态空串不缓存。「过期但仍有值」现在也属于确定性结论，所以过期那条路径也不会
 * 再把外层缓存搅成空串。
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
            "longest_streak",
            // R-48：两个 _raw 别名与它们的带符号原名是同<b>一份信息</b>，只是格式不同。
            // 漏在这里 = 玩家关掉 showResults 后，他人在计分板上用 %lle_accuracy_raw% /
            // %lle_achievements_raw% 照旧读到正确率与解锁进度 —— _raw 不是新数据源，
            // 而是绕开隐私门的旁路。入集合的唯一判据是「信息内容」，不是「占位符名字」。
            "accuracy_raw", "achievements_raw",
            // R-48：%lle_duel_wins% 与 %lle_longest_streak% 同族 —— 它同样是答题成绩的
            // 直接产物（赢了多少场是对局结果，不是「来没来」的活跃度），产品内先例见上
            // 一段的 DuelStatsText：他人主页在 hideResults 时整块战绩行不渲染，
            // 连胜行也不渲染。若这里不入集合，隐藏结果的玩家仍能被他人从计分板读到胜场数。
            "duel_wins",
            // R-48：%lle_review_due% 也入集合。判据沿用上面两条注释给出的那条线 ——
            // 「是不是答题成绩/学习进度的产物」，而不是「占位符名字好不好听」。
            // 待复习词数来自 word_progress 的复习种子（词 + 上次复习时间 + 复习次数），
            // 它直接暴露「这个人学过多少个词、进度到哪」，与已在集合里的 words
            // （累计答题词数）是同一族数据。它<b>不</b>属于 streak 那样的「有没有每天来」
            // 活跃度指标 —— 签到天数完全不透露学习内容，待复习词数则几乎是学习档案的切片。
            // 当前该占位符恒返回空串（见 REVIEW_DUE_UNKNOWN），入集合不改变今日行为；
            // 但这条判据必须先落子，否则将来接上真实数据源时它就是一个静默的旁路。
            "review_due");

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
     * R-47（性能）：{@code %lle_rank%} 的独立值缓存 —— 与 {@link #coinsCache} 同构的
     * 「异步补读 + 短窗口去重」模式，只是值域是名次而不是金币。
     *
     * <p><b>为什么必须异步</b>：{@code %lle_rank%} 走
     * {@code Leaderboard.getPlayerRank} → {@code PlayerDataStore.getAuthoritativeRank}，
     * 那是一条 {@code SELECT COUNT(*) ... AND EXISTS (SELECT 1 FROM word_progress ...)}
     * 的逐行相关子查询 —— 成本 O(玩家数)，实测 1 万玩家 <b>350ms</b>、5 万玩家 <b>2.5s</b>。
     * 而 PAPI 的 {@link #onRequest} 由计分板插件在<b>主线程</b>求值，外层 30s 缓存又是
     * 「按玩家分键」的：一块挂 20 人的计分板 = 每 30 秒 20 次主线程 O(N) 查询，
     * 每次都可能让整个 tick 停住几秒。
     *
     * <p>外层 {@link #cache} 拦不住它：它缓存的是「已经算出来的字符串」，缓存 miss 时
     * 仍要同步算一遍。所以这条路径必须像金币一样把「拿到值」与「用值」拆开。
     *
     * <p>键只有 UUID：名次是玩家级标量，「未上榜」文案的语言差异在处理时渲染
     * （见 {@link RankEntry} 的说明），所以缓存本身与视角/语言无关。
     */
    private final Map<UUID, RankEntry> rankCache = new ConcurrentHashMap<>();

    /**
     * R-48（性能）：{@code %lle_duel_wins%} 的独立值缓存 —— 与 {@link #rankCache}
     * 同构（{@code ConcurrentHashMap} + 过期时刻 + 短窗口去重），换的只是值域。
     *
     * <p><b>为什么必须异步</b>：胜场来自 {@code DatabaseManager.getDuelStats}，它的
     * javadoc 明写「同步阻塞读，禁止主线程调用」—— 里面是两条 COUNT 并抢
     * {@code connectionLock}。而 PAPI 的 {@link #onRequest} 由计分板插件在<b>主线程</b>
     * 求值，外层 30s 缓存又按玩家分键，于是「挂 20 人的计分板」= 每 30 秒 20 次主线程
     * 查询，与会话结算/周期落盘抢同一把全局连接锁。这与 {@code rank} / {@code coins_earned}
     * 是同一条失效形状，处置也必须同一条。
     *
     * <p>键只有 UUID：胜场是玩家级标量，与查看语言/视角无关（隐私门在上层已把
     * 「非本人 + 目标隐藏结果」挡掉，走到这里不需要文案差异）。
     */
    private final Map<UUID, DuelWinsEntry> duelWinsCache = new ConcurrentHashMap<>();

    /**
     * R-48：{@link #duelWinsCache} 的条目上限，与 {@link #RANK_CACHE_MAX_ENTRIES} 同量级
     * （键是玩家 UUID，不是「玩家 × 占位符 × 视角 × 语言」）。复用 {@link #trimToCap}。
     *
     * <p>两个 TTL 常量刻意定义在 {@code rank*} 那一组之后：它们的初值就是那两条常量本身
     * （单一真相源，不是各自独立的魔数），Java 的 static 初始化按声明顺序执行，
     * 前置引用会让它们被读成 0，于是「在途窗口」退化成一个已经过期的条目、
     * 每次刷新都重排一次查询 —— 这正是本条路径要避免的形状。
     */
    private static final int DUEL_WINS_CACHE_MAX_ENTRIES = 2000;

    /**
     * R-48：{@code %lle_review_due%} 的恒定返回值 —— 空串。
     *
     * <p>这不是「暂时没实现所以先空着」的占位，而是<b>刻意选定的正确行为</b>，理由见
     * {@link #resolveRequest} 里该 case 的注释。
     * 将来若 {@code ReviewScheduler} 补上「只读缓存、miss 不触发载入」的访问器，
     * 这里再改成走该访问器 —— 在那之前一个同步查库的实现在本类是不允许的。
     */
    private static final String REVIEW_DUE_UNKNOWN = "";

    /**
     * R-48：{@code %lle_duel_wins%} 的「还不知道」返回值 —— 空串，与
     * {@link #RANK_UNKNOWN} / {@link #COINS_UNKNOWN} 同一条理由：{@code 0} 是「确实
     * 一场没赢过」这个<b>已确认</b>结论，用 0 冒充「还没读到」等于在计分板上发布
     * 一条编造的事实。单独开一个常量而不是复用 {@code RANK_UNKNOWN}：两者语义不同，
     * 各自改名/改值时不希望互相牵连。
     */
    private static final String DUEL_WINS_UNKNOWN = "";

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

    /**
     * R-47（性能）：{@code %lle_rank%} 专用缓存 TTL（毫秒）—— 与 {@link #CACHE_TTL_MILLIS} 一致。
     *
     * <p>名次是榜单类数字，30s 陈旧在计分板上无害；而每一次刷新都要付一次
     * O(玩家数) 的 {@code COUNT(*)} + 相关子查询（实测 1 万玩家 350ms、5 万玩家 2.5s），
     * 所以「同一玩家 30s 内最多一次后台读」是这条路径的核心收益。
     */
    private static final long RANK_CACHE_TTL_MILLIS = CACHE_TTL_MILLIS;

    /** R-47：名次读失败后的退避 TTL —— 与 {@link #COINS_RETRY_TTL_MILLIS} 同口径。 */
    private static final long RANK_RETRY_TTL_MILLIS = 15_000L;

    /**
     * R-47：名次「在途/未知」窗口的短 TTL（毫秒）—— 补读去重闸门（同 {@link RankEntry}）。
     *
     * <p><b>为什么不是照抄 {@link #COINS_SENTINEL_TTL_MILLIS} 的 1s</b>：名次查询本身就是
     * 那个慢查询（1 万玩家 350ms、5 万玩家 2.5s，且与大服的写入抢同一把
     * {@code connectionLock}）。1s 窗口在 2.5s 的读面前会失效 —— 计分板每秒刷新一次，
     * 同一玩家的请求会在首个读还没回来时再排两个<b>同量级的 O(N) 查询</b>，
     * 把「主线程卡 2.5s」换成「异步池里堆叠三份 2.5s」。窗口必须覆盖一次读的耗时才叫去重。
     *
     * <p>10s 的代价：一次慢读失败/卡住后，同一玩家最多 10s 才重排一次。期间占位符
     * <b>不是</b>空串 —— 闸门条目带着上一次的旧结论（见 {@link #getRankCached} 的
     * {@code claiming}），只有「从未读到过」才空串。这个方向是安全的 —— 宁可少读，不可堆读。
     */
    private static final long RANK_SENTINEL_TTL_MILLIS = 10_000L;

    /**
     * R-48：{@code %lle_duel_wins%} 的「在途/未知」窗口（毫秒），复用名次的 10s。
     *
     * <p>之所以不取金币的 1s：{@code getDuelStats} 是两条 COUNT，在 MySQL 远程库慢时
     * 可能超过 1 秒，1s 窗口会在首个读还没回来时被计分板的下一秒刷新再排一次同量级查询。
     * 窗口必须覆盖一次读的耗时才叫去重（与 {@link #RANK_SENTINEL_TTL_MILLIS} 同理由）。
     */
    private static final long DUEL_WINS_SENTINEL_TTL_MILLIS = RANK_SENTINEL_TTL_MILLIS;

    /** R-48：胜场读失败后的退避 TTL —— 与 {@link #RANK_RETRY_TTL_MILLIS} 同口径。 */
    private static final long DUEL_WINS_RETRY_TTL_MILLIS = RANK_RETRY_TTL_MILLIS;

    /**
     * R-47：{@link #rankCache} 的条目上限，取与 {@link #COINS_CACHE_MAX_ENTRIES} 相同的量级。
     *
     * <p>键是玩家 UUID（不是「玩家 × 占位符 × 视角 × 语言」），数量级由在线/近期玩家数决定，
     * 故上限远小于 {@link #CACHE_MAX_ENTRIES} 的 5000。复用的是同一套淘汰逻辑
     * （{@link #trimToCap}），只换上限值。
     */
    private static final int RANK_CACHE_MAX_ENTRIES = 2000;

    /**
     * R-47：{@code %lle_rank%} <b>从未读到过</b>（冷启动）/ 读失败时的返回值 —— 空串，
     * <b>不是</b>「未上榜」。
     *
     * <p>注意<b>过期</b>不在此列：条目过期但仍有值时返回的是那个值（stale-while-revalidate，
     * 见 {@link #getRankCached}）—— 「旧」与「未知」是两件事，把后者也当成后者会让计分板
     * 每 30 秒空一次。
     *
     * <p><b>「不知道」与「未上榜」不能合并</b>：「未上榜」是一个<b>已确认的结论</b>（查过，
     * 这个人确实不在榜上：没答过题 / 隐身 / 无行），而「不知道」只是<b>还没有结论</b>。
     * 把「还不知道」渲染成「未上榜」等于在计分板上发布一条编造的事实 —— 玩家会以为自己的
     * 名次掉了。空串在计分板上只是个暂时的空位，下一轮刷新（异步读回来之后）就填上。
     *
     * <p>同文件先例：{@link #COINS_UNKNOWN}（{@code "0"} 是可信的假数，空串才是诚实表达）。
     */
    private static final String RANK_UNKNOWN = "";

    /**
     * R-47：名次值缓存条目 —— 结构同 {@link CoinsEntry}（{@code ConcurrentHashMap} + 过期时刻，
     * 「在途」状态就是 {@code value == null} 且过期时刻未到，不再引入第二套状态位）。
     *
     * <p><b>为什么存数字而不存渲染好的字符串</b>：本缓存的键只有 UUID，而「未上榜」文案随
     * <b>查看者语言</b>变化（见 {@link #getUnrankedText}，R22-D6 的既有口径）。若把
     * {@code "未上榜"} 冻结进这个与语言无关的缓存，第一个 fill 的人是谁，之后所有语言的
     * 查看者就都看到谁的语言 —— 正是 D5 用例钉住的那个缺陷的翻版。故这里只存<b>结论</b>
     * （名次 / 已确认未上榜），文案在 {@link #renderRank} 里按当次查看者渲染。
     */
    private static final class RankEntry {
        /** {@code null} = 读失败或尚未读到（未知）；非 null = 已确认的库内结论。 */
        final Integer value;
        final long expiresAt;

        RankEntry(Integer value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * R-48：{@code %lle_duel_wins%} 的值缓存条目 —— 结构同 {@link RankEntry}。
     *
     * <p>{@code value == null} 同时表达「还没读到」与「读失败」：两者在展示上都是空串，
     * 区别只在 TTL（在途哨兵 vs 失败退避）。不引入第二套状态位 —— 同 {@link CoinsEntry}
     * 的理由：三态用 {@code null} + 过期时刻已经足够，多一个布尔位只会有两处写它。
     */
    private static final class DuelWinsEntry {
        /** {@code null} = 读失败或尚未读到（未知）；非 null = 已确认的胜场数。 */
        final Integer value;
        final long expiresAt;

        DuelWinsEntry(Integer value, long expiresAt) {
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
            //
            // R-47：rank 只在<b>拿到确定性结论</b>时才进本缓存（见 shouldCacheOuter）——
            // 同理不能把「还不知道」的暂态空串冻 30s；但拿到 "#5" /「未上榜」之后仍要缓存，
            // 否则每次计分板刷新都会重走 resolveRequest（含一次
            // loadProfileSettingsForDisplay，它在隐私缓存 miss 时是<b>同步</b> SELECT，
            // 正是本类要避免的形状）。
            if (shouldCacheOuter(params, resolved)) {
                cache.put(cacheKey, new CachedValue(resolved, now + CACHE_TTL_MILLIS));
            }
        }
        return resolved;
    }

    /**
     * R-16 / R-47：本次解析结果是否该进 30s 外层缓存 {@link #cache}。
     *
     * <p><b>为什么要有这条谓词</strong>（而不是在 resolveCached 里堆 {@code if}）：
     * 两条「异步补读」路径（coins / rank）都有同一个形状 —— 它们自己持有一份
     * 「值 + TTL + 在途去重」的缓存，且未命中时会返回一个<b>暂态占位值</b>。
     * 把那个暂态值冻进外层 30s 缓存，等于让异步读回来的真值再等一个完整 TTL 才可见。
     *
     * <ul>
     *   <li>{@code coins_earned}：<b>一律</b>不入外层缓存。它每次解析的成本只有一次
     *       ConcurrentHashMap 查询，不需要兜底（反例见 {@link #getCoinsCached}）。</li>
     *   <li>{@code rank}：<b>只在拿到确定性结论时</b>入缓存。暂态占位值是空串
     *       （{@link #RANK_UNKNOWN}），而确定性结论是 {@code "#5"} 或「未上榜」文案 ——
     *       后者非空，故用「是否为空串」就能区分，不必在渲染层再传一个布尔。
     *       拿到结论后仍必须缓存：否则每次刷新都会重走 {@link #resolveRequest}，
     *       而它开头是一次 {@code loadProfileSettingsForDisplay}（隐私缓存 miss 时是
     *       同步 SELECT），正好抵消异步化的收益。</li>
     *   <li>{@code duel_wins}（R-48）：与 {@code rank} 逐字同款 —— 暂态占位值是空串，
     *       已确认的胜场数（含真实的 {@code "0"}）非空。判据复用同一条「是否为空串」，
     *       因为「0 场」是一个<b>非空</b>的确定性结论，必须能够进缓存（否则零胜玩家
     *       每次刷新都重走一次 resolveRequest）。</li>
     *   <li>{@code review_due}（R-48）：<b>一律</b>不入外层缓存。它恒定返回空串
     *       （见 {@link #REVIEW_DUE_UNKNOWN}），缓存它只是往 5000 条上限里塞无效条目，
     *       还会让将来「接上真实数据源」的那次改动必须记得同步改本方法 ——
     *       一个恒为空的结论没有缓存的必要。</li>
     * </ul>
     *
     * @param params   占位符参数名（非 null）
     * @param resolved 本次解析出的字符串（非 null）
     * @return {@code true} = 可以写入外层 30s 缓存
     */
    private static boolean shouldCacheOuter(@NotNull String params, @NotNull String resolved) {
        if (params.equals("coins_earned") || params.equals("review_due")) {
            return false;
        }
        if (params.equals("rank") || params.equals("duel_wins")) {
            // 空串 = 还不知道（异步读在途/失败），不能冻 30s；非空 = 已确认的结论，可缓存。
            return !resolved.isEmpty();
        }
        return true;
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
                // R-47（性能）：原实现同步调 Leaderboard.getPlayerRank —— 那是一条
                // O(玩家数) 的 COUNT(*) + 相关子查询（1 万玩家 350ms、5 万玩家 2.5s），
                // 却跑在 PAPI 的调用线程（计分板 = 主线程）上，30s 缓存又按玩家分键，
                // 于是每块挂 N 人的计分板每 30 秒产生 N 次主线程慢查询。
                // 改走 getRankCached：命中零 IO，未命中/在途返回空串并在后台补读。
                // 注意未命中返回空串而<b>不是</b>「未上榜」—— 两者语义不同，见 RANK_UNKNOWN。
                // 已过期但<b>有值</b>不算未命中：返回上次结论并在后台补读（stale-while-revalidate），
                // 否则计分板会每 30 秒规律性空一次（见 getRankCached 的 javadoc）。
                return renderRank(getRankCached(playerId), viewer);
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
            case "achievements_raw" -> {
                // R-48：只发解锁数本身（"3"），不带 "/22" —— 与上方 achievements 同一份
                // 信息、同一次读取，唯一的差别是格式。第三方排行榜插件要的是「能转成数字
                // 的字符串」，"3/22" 会在 Integer.parseInt 上炸掉。两者必须读同一个来源，
                // 否则会出现「计数占位符说 3/22、raw 说 4」这种自相矛盾。
                return String.valueOf(plugin.getAchievementManager().getUnlockedCount(playerId));
            }
            case "duel_wins" -> {
                // R-48（性能）：决斗胜场来自 duel_stats 的两条 COUNT 聚合
                // （DatabaseManager.getDuelStats），是同步阻塞读，且它自己的 javadoc 明写
                // 「禁止主线程调用」。而 PAPI 的 onRequest 正是跑在计分板插件的主线程上，
                // 所以这里绝不能直连 —— 照 rank / coins 的既有范式走 getDuelWinsCached：
                // 命中零 IO，未命中返回空串并在后台补读。
                //
                // 未命中返回空串而不是 0：0 是「确实一场没赢过」的<b>已确认结论</b>，
                // 与 RANK_UNKNOWN / COINS_UNKNOWN 同口径 —— 计分板上宁可先空一格，
                // 也不要发一个看起来可信的假 0。已确认的 0 胜则照常渲染成 "0"。
                Integer wins = getDuelWinsCached(playerId);
                return wins == null ? DUEL_WINS_UNKNOWN : String.valueOf(wins);
            }
            case "review_due" -> {
                // R-48：待复习词数<b>刻意不返回数字</b>，恒为空串。原因是唯一现成的读取入口
                // （ReviewScheduler.getDueReviewWords）不符合本类的两条不变量：
                // ① 它 miss 时会读 word_progress（同步 SELECT，抢 connectionLock）——
                //    正是「主线程不碰 DB」要禁的形状；
                // ② 它是「读+写」而不是纯读：算出结果后会把 due 列表写回自己的 30s 缓存，
                //    而 PAPI 是展示路径，不该顺手给别的模块灌缓存条目；
                // ③ 玩家数据尚未载入时它会把「一张空表」算成「0 个待复习」，而 0 是一个
                //    看起来可信的假数（与 COINS_UNKNOWN / RANK_UNKNOWN 同一条理由）。
                // 在 ReviewScheduler 补出「只读缓存、miss 直接返回未知」的访问器之前，
                // 这个占位符就诚实地空着 —— 空串在计分板上只是一个空位，不误导任何人。
                return REVIEW_DUE_UNKNOWN;
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
            case "accuracy_raw" -> {
                // R-48：与 accuracy 逐位同源，只是去掉 "%" 后缀 —— 第三方排行榜
                // （AJLeaderboards 的文档原话是「it just needs to have a PAPI placeholder
                // that returns a number」）要的是能直接 Double.parseDouble 的字符串。
                int total = stats.getTotalCorrect() + stats.getTotalIncorrect();
                if (total == 0) {
                    // 从没答过题 = 正确率无定义。给 "0.0" 而不是空串：这个占位符的消费者
                    // 是数值型排行榜，空串会让它把该玩家整行丢掉；而 "0.0" 与
                    // accuracy 的 "0.0%" 语义一致（同一份数据、同一套口径）。
                    yield "0.0";
                }
                double acc = (double) stats.getTotalCorrect() / total * 100.0;
                // R22-D11 的坑在 raw 上更严重：accuracy 漏了 Locale.ROOT 只是显示成
                // "75,0%"，raw 漏了则会给第三方一个 "75,0" —— 那个字符串在任何 locale
                // 下的 Double.parseDouble 都是 NumberFormatException，占位符直接失效。
                yield String.format(java.util.Locale.ROOT, "%.1f", acc);
            }
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
     * R-47（性能）：{@code %lle_rank%} 的缓存读取入口 —— <b>调用线程永不碰 DB</b>。
     *
     * <p>过期方向是 <b>stale-while-revalidate</b>（先给旧结论，再异步补读）：
     * <ol>
     *   <li>命中且未过期 → 直接返回缓存结论（零 IO）；</li>
     *   <li>已过期但<b>有值</b> → <b>先返回这个旧结论</b>，同时用
     *       {@code putIfAbsent} + {@code replace(旧, 新)} 抢一个短 TTL 的补读窗口
     *       （{@link #RANK_SENTINEL_TTL_MILLIS}）；抢到的那个线程排<b>一次</b>异步读，
     *       抢不到的线程照样拿到旧结论；</li>
     *   <li>只有<b>从未读到过</b>（{@code entry == null}，或 {@code value == null}
     *       即冷启动 / 上次读失败）才返回 {@code null}（= 还不知道，渲染成
     *       {@link #RANK_UNKNOWN} 空串）；</li>
     *   <li>异步读完成后用真结论 + 正常 TTL 覆写，下一次刷新自然拿到真值。</li>
     * </ol>
     *
     * <p><b>为什么过期分支必须返回旧值，而不是像金币那样返回「未知」</b>：外层 30s
     * 字符串缓存 {@link #cache} 是在本方法返回<b>之后</b>才写的，两层 TTL 的起点相差
     * 一次解析（内层 T1、外层 T2 &gt; T1），于是外层<b>总比内层晚一点过期</b>。
     * 内层如果在过期分支把已确认的 {@code "#5"} 换成 {@code null}，那么外层过期那次
     * 重解析就会拿到空串，而 {@link #shouldCacheOuter} 又会因空串拒绝写外层 ——
     * 结果就是计分板那一格<b>每 30 秒规律性空一次</b>（1 万玩家空 0.35s、5 万玩家空
     * 2.5s，每个玩家每 30 秒一次），而不是只空一次。返回旧值把「过期」与「未知」拆开：
     * 过期只是「可能不是最新」，未知才是「没有结论」。名次在计分板上 30s 陈旧无害，
     * 而规律性空一格是玩家可见的故障。
     *
     * <p><b>抢占用 {@code putIfAbsent} + {@code replace(旧, 新)}</b>，不是裸 {@code put}：
     * 并发下另一个线程可能刚好把<b>真结论</b>写进来，裸 put 会把它盖成「未知」，
     * 凭空多一次空串展示。{@code replace} 只在「旧条目还是我看到的那个」时才生效，
     * 因此并发下<b>只有一个</b>线程能排异步读。
     *
     * <p><b>{@code claiming} 条目携带 stale 值而不是 {@code null}</b>：它就是那 10s 的
     * 补读闸门，闸门内所有并发调用读到的都是这个条目。若写成 {@code null}，一次 2.5s
     * 的慢读会被放大成 10s 的空串（闸门窗口有多长，空串就有多久）。
     *
     * @param playerId 目标玩家（非 null，由调用链保证）
     * @return {@code null} = 还不知道（应渲染空串）；{@code >0} = 名次；
     *         {@code <=0} = <b>已确认</b>不在榜上（应渲染「未上榜」）
     */
    private Integer getRankCached(UUID playerId) {
        long now = System.currentTimeMillis();
        RankEntry entry = rankCache.get(playerId);
        if (entry != null && now < entry.expiresAt) {
            // 命中窗口内：null = 上一轮读失败（退避未到），仍是「还不知道」。
            return entry.value;
        }
        // 走到这里 = 从未读过 / 已过期。旧结论先留在手上 —— 它只是「不再是最新」，
        // 丢掉它就等于把「已确认」降级成「未知」（本方法要修的那个每 30s 空一次）。
        Integer stale = entry == null ? null : entry.value;
        RankEntry claiming = new RankEntry(stale, now + RANK_SENTINEL_TTL_MILLIS);
        RankEntry prev = rankCache.putIfAbsent(playerId, claiming);
        if (prev == null) {
            // 我们是第一个：由本次调用负责排补读，返回手上这份旧结论（可能为 null = 冷启动）。
            scheduleRankLoad(playerId, stale);
            return stale;
        }
        if (now < prev.expiresAt) {
            // 别人先一步占了窗口（或填了真结论）：不重复排队，直接给现在缓存里的结论。
            return prev.value;
        }
        // prev 也过期了 → 本次抢到补读权。prev 是比 entry 更晚的一次读，非空就优先用它
        // （「读到过」永远优于「没读到过」，方向不可反）。
        Integer known = prev.value != null ? prev.value : stale;
        RankEntry claim = new RankEntry(known, now + RANK_SENTINEL_TTL_MILLIS);
        // 用 replace 防覆盖并发写入的真结论（见 javadoc）。
        if (!rankCache.replace(playerId, prev, claim)) {
            // 没抢到：别的线程刚好换了条目（多半是它刚补读回来的新结论）。用缓存里当前的
            // 值；条目已被淘汰（trimToCap）或当前也是未知，则退回手上这份旧结论。
            RankEntry current = rankCache.get(playerId);
            if (current != null && current.value != null) {
                return current.value;
            }
            return known;
        }
        scheduleRankLoad(playerId, known);
        return known;
    }

    /**
     * R-47：异步补读名次 —— 唯一一处真正执行那条 O(玩家数) 查询的代码。
     *
     * <p>用 {@code runTaskAsynchronously}：读取是阻塞 JDBC（且是慢查询），
     * 放主线程就是把计分板刷新变成 tick 卡顿源。写 {@link #rankCache} 是无锁的
     * {@code ConcurrentHashMap.put}，跨线程安全。
     *
     * <p><b>失败口径</b>（与金币刻意不同，见下方理由）：失败落
     * {@code value = null} + {@link #RANK_RETRY_TTL_MILLIS} 退避，<b>并记一次 WARNING</b>。
     * 金币那条路径不记日志是因为 {@code DatabaseManager.getCoinsEarnedWithStatus} 内部
     * 已经按行记过；而名次的<b>读失败</b>（三态 {@code readFailed}）本身没有旁路日志，
     * 静默的话服主永远看不到「计分板名次一直是空」的原因。频率是「每玩家每 15s 最多一条」
     * （失败会写退避条目，期间不再排队），不会刷屏。
     *
     * <p><b>绝不把失败折成「未上榜」</b>：{@code -1} / {@code 0} 是<b>已确认不在榜上</b>的
     * 结论（查过、确实没有），把「不知道」写成它就是编造事实。三态版
     * {@link PlayerDataStore.AuthoritativeRankLoad#readFailed()} 正是为此存在。
     *
     * @param playerId 目标玩家（非 null）
     * @param stale    调用方手上已有的旧结论（{@code null} = 从未读到过）。只在<b>排不上
     *                 异步任务</b>时用到 —— 那时既拿不到新值也不会再排下一次，把已知的
     *                 旧结论丢掉换成「未知」是净损失（多空一格）。注意真正的<b>读失败</b>
     *                 仍落 {@code null}（见 {@link #loadRankIntoCache}）：旧结论可以说是
     *                 「旧」，但不能替一次失败的读背书。
     */
    private void scheduleRankLoad(UUID playerId, Integer stale) {
        if (!scheduleAsync(() -> loadRankIntoCache(playerId))) {
            // 无调度器 / 调度器已关：等退避后重试，期间保持调用方手上那份结论
            // （有旧值就给旧值，没有才落「未知」）—— 把已知的旧结论在这里丢掉是净损失。
            // 占位符解析绝不能因为「排不上任务」而抛给 PAPI 调用方。
            rankCache.put(playerId, new RankEntry(stale,
                    System.currentTimeMillis() + RANK_RETRY_TTL_MILLIS));
        }
    }

    /**
     * R-47：排一次异步任务，返回是否成功投递。
     *
     * <p><b>为什么单开一个方法</b>：{@code SchedulerRejectionCatchTest} 的检查规则是
     * 「catch 往上 8 行内出现 {@code runTask} / {@code getScheduler()} 就必须接住
     * {@link IllegalPluginAccessException}」。把派发与守卫放在一起，这条规则才真的<b>看得见</b>
     * 这段代码；塞在 {@link #scheduleRankLoad} 里（中间隔着一个长 lambda）会让守卫
     * 落在规则的扫描窗口之外 —— 于是一条本该被钉住的契约在 lint 眼里根本不存在。
     *
     * <p>与 {@code RewardEngine.scheduleAsync} 同姿态：不只 catch ISE。
     * {@link IllegalPluginAccessException} 才是 Paper 的 {@code CraftScheduler.validate}
     * 在插件已禁用时真正抛的类型（extends {@link RuntimeException}，<b>不是</b>
     * {@link IllegalStateException} 的子类）；ISE 留给纯 JVM 夹具的调度器替身。
     *
     * @return {@code true} = 已投递到异步线程；{@code false} = 排不上（调用方落退避条目）
     */
    private boolean scheduleAsync(Runnable task) {
        try {
            org.bukkit.Server server = plugin.getServer();
            if (server == null || server.getScheduler() == null) {
                return false;
            }
            server.getScheduler().runTaskAsynchronously(plugin, task);
            return true;
        } catch (IllegalPluginAccessException | IllegalStateException e) {
            // 关服/停用窗口，或 mock 调度器拒收：调用方据 false 落退避条目，不冒泡。
            return false;
        }
    }

    /**
     * R-47：异步任务本体 —— 读一次名次并写回 {@link #rankCache}。
     *
     * <p>拆出来是为了让 {@link #scheduleAsync} 的 lambda 只有一行，上面的守卫规则才站得住。
     *
     * <p><b>失败口径</b>（与金币刻意不同，见 {@link #RANK_RETRY_TTL_MILLIS} 与文档）：
     * 失败落 {@code value = null} + 退避 TTL，<b>并记一次 WARNING</b> —— 名次的读失败
     * （三态 {@code readFailed}）没有旁路日志，静默的话服主永远看不到「计分板名次一直是空」
     * 的原因。频率是「每玩家每 15s 最多一条」（失败会写退避条目，期间不再排队），不会刷屏。
     * <b>绝不把失败折成「未上榜」</b>：那是编造结论。
     */
    private void loadRankIntoCache(UUID playerId) {
        Integer value = null;
        boolean failed = false;
        try {
            PlayerDataStore.AuthoritativeRankLoad load =
                    plugin.getPlayerDataStore().getAuthoritativeRankWithStatus(playerId);
            if (load == null || load.readFailed()) {
                failed = true;
            } else {
                // rank <= 0 = 已确认不在榜上（结论），不是失败。
                value = load.rank();
            }
        } catch (RuntimeException e) {
            // 连接层运行期异常（重连失败等）：保持未知，等退避后重试。
            failed = true;
        } finally {
            // 先写缓存再记日志：日志本身若失败（getLogger 为 null 的夹具）不能把
            // 「写回退避条目」这一步跳掉 —— 那会让同一玩家每次刷新都重排一次慢查询，
            // 正好退化成本次要修的那个形状。
            long ttl = value != null ? RANK_CACHE_TTL_MILLIS : RANK_RETRY_TTL_MILLIS;
            maybeEvictRanks();
            rankCache.put(playerId, new RankEntry(value, System.currentTimeMillis() + ttl));
            if (failed) {
                java.util.logging.Logger logger = plugin.getLogger();
                if (logger != null) {
                    logger.log(java.util.logging.Level.WARNING,
                            "Failed to read rank for " + playerId
                                    + " — placeholder stays empty until the next retry");
                }
            }
        }
    }

    /**
     * R-47：把 {@link #getRankCached} 的三态结论渲染成占位符文本。
     *
     * <p>文案在这里（而不是缓存里）渲染：结论与查看者无关，<b>语言</b>与查看者有关 ——
     * 见 {@link RankEntry} 的说明（R22-D6 已把语言口径钉成「查看者」，不能因为加了缓存
     * 又退回「第一个谁 fill 就用谁的语言」）。
     *
     * @param rank   {@code null} = 还不知道 → {@link #RANK_UNKNOWN}（空串，<b>不是</b>未上榜）
     * @param viewer 查看者（null = plain 入口，回落服务端默认语言）
     */
    private String renderRank(Integer rank, Player viewer) {
        if (rank == null) {
            return RANK_UNKNOWN;
        }
        return rank > 0 ? "#" + rank : getUnrankedText(viewer);
    }

    /**
     * R-48（性能）：{@code %lle_duel_wins%} 的缓存读取入口 —— <b>调用线程永不碰 DB</b>。
     *
     * <p>与 {@link #getRankCached} 同一套三态 + stale-while-revalidate：
     * <ol>
     *   <li>命中且未过期 → 直接返回结论（零 IO）；</li>
     *   <li>已过期但<b>有值</b> → 先返回那个旧结论，同时用 {@code putIfAbsent} +
     *       {@code replace(旧, 新)} 抢一个补读窗口（{@link #DUEL_WINS_SENTINEL_TTL_MILLIS}）；
     *       抢到的那个线程排<b>一次</b>异步读；</li>
     *   <li>只剩「从未读到过」才返回 {@code null}（= 还不知道，调用方渲染成
     *       {@link #DUEL_WINS_UNKNOWN} 空串）。</li>
     * </ol>
     *
     * <p><b>为什么过期分支也必须返回旧值</b>：与名次逐字同因 —— 外层 30s 字符串缓存
     * {@link #cache} 是在本方法返回<b>之后</b>才写的，两层 TTL 的起点相差一次解析，
     * 于是外层总比内层晚过期。内层在过期分支返回空串的话，外层那次重解析就拿不到结论，
     * 而 {@link #shouldCacheOuter} 又会因空串拒绝写外层 —— 计分板那一格会每 30 秒
     * 规律性空一次。胜场数在计分板上 30s 陈旧无害，规律性空一格才是可见故障。
     *
     * <p><b>并发抢占用 {@code replace(旧, 新)} 而不是裸 {@code put}</b>：另一个线程可能
     * 刚好把真结论写进来，裸 put 会把它盖成「未知」，凭空多一次空串展示。
     *
     * @param playerId 目标玩家（非 null，由调用链保证）
     * @return {@code null} = 还不知道（调用方渲染成空串）；非 null = 已确认的胜场数
     */
    private Integer getDuelWinsCached(UUID playerId) {
        long now = System.currentTimeMillis();
        DuelWinsEntry entry = duelWinsCache.get(playerId);
        if (entry != null && now < entry.expiresAt) {
            return entry.value;
        }
        // 走到这里 = 从未读过 / 已过期。旧结论留在手上：它只是「不再最新」，
        // 丢掉它就等于把「已确认」降级成「未知」（同 getRankCached 的理由）。
        Integer stale = entry == null ? null : entry.value;
        DuelWinsEntry claiming = new DuelWinsEntry(stale, now + DUEL_WINS_SENTINEL_TTL_MILLIS);
        DuelWinsEntry prev = duelWinsCache.putIfAbsent(playerId, claiming);
        if (prev == null) {
            scheduleDuelWinsLoad(playerId, stale);
            return stale;
        }
        if (now < prev.expiresAt) {
            // 别人先一步占了窗口（或填了真结论）：不重复排队，给现在缓存里的结论。
            return prev.value;
        }
        // prev 也过期了 → 本次抢到补读权。「读到过」永远优于「没读到过」，方向不可反。
        Integer known = prev.value != null ? prev.value : stale;
        DuelWinsEntry claim = new DuelWinsEntry(known, now + DUEL_WINS_SENTINEL_TTL_MILLIS);
        if (!duelWinsCache.replace(playerId, prev, claim)) {
            // 没抢到：别的线程刚好换了条目（多半是它刚补读回来的新结论）。
            DuelWinsEntry current = duelWinsCache.get(playerId);
            if (current != null && current.value != null) {
                return current.value;
            }
            return known;
        }
        scheduleDuelWinsLoad(playerId, known);
        return known;
    }

    /**
     * R-48：排一次胜场补读；排不上就落退避条目。
     *
     * <p>与 {@link #scheduleRankLoad} 同构，包括「排不上时保留手上那份旧结论」的理由：
     * 那时既拿不到新值也不会再排下一次，把已知旧结论丢掉换「未知」是净损失。
     *
     * @param playerId 目标玩家（非 null）
     * @param stale    调用方手上已有的旧结论（{@code null} = 从未读到过）
     */
    private void scheduleDuelWinsLoad(UUID playerId, Integer stale) {
        if (!scheduleAsync(() -> loadDuelWinsIntoCache(playerId))) {
            duelWinsCache.put(playerId, new DuelWinsEntry(stale,
                    System.currentTimeMillis() + DUEL_WINS_RETRY_TTL_MILLIS));
        }
    }

    /**
     * R-48：异步任务本体 —— 读一次决斗胜场并写回 {@link #duelWinsCache}。
     *
     * <p>拆出来是为了让 {@link #scheduleAsync} 的 lambda 只有一行（理由见
     * {@link #scheduleAsync} 的 javadoc：{@code SchedulerRejectionCatchTest} 的扫描窗口
     * 要求守卫与派发紧邻）。
     *
     * <p><b>失败口径</b>：运行期异常（连接层重连失败等，由 {@code DatabaseManager.getConnection}
     * 抛出、{@code getDuelStats} 不接）落 {@code value = null} + 退避 TTL，<b>不记 WARNING</b> ——
     * {@code getDuelStats} 内部已在 {@code SQLException} 分支记过一次带堆栈的 WARNING，
     * 这里再记一遍只会让同一故障在日志里出现两份。
     *
     * <p><b>已知残余风险（故意记在这里，不要当成已解决的问题）</b>：
     * {@code getDuelStats} 的 {@code SQLException} 分支返回 {@code (0, 0)}，
     * 与「确实一场没赢过」<b>逐字节同形</b>且<b>不抛异常</b> —— 所以本方法<b>无法</b>把
     * 「查询失败」与「真的零胜」区分开，失败会被当作已确认的 0 缓存 30 秒。
     * 这正是 R22-D2 在金币上修掉的那个形状（当时的处置是给 {@code DatabaseManager}
     * 加三态变体 {@code getCoinsEarnedWithStatus}）。本占位符<b>不做</b>那个改造：
     * 它需要动 {@code DatabaseManager}（本批次禁改的文件），且决斗胜场是低频展示数字，
     * 与金币（动钱）的量级不同。正确的后续处置是照 {@code CoinsEarnedRead} 的形状补一个
     * {@code DuelStatsRead(wins, losses, readFailed)}，届时本方法改调三态版并让
     * {@code readFailed} 落 {@code null}。在这之前：错误方向是「显示 0 而不是空串」，
     * 不是「显示一个玩家从未拥有过的高胜场」，故不构成发错奖励一类的后果。
     */
    private void loadDuelWinsIntoCache(UUID playerId) {
        Integer value = null;
        try {
            DatabaseManager.DuelStats stats = plugin.getDatabaseManager().getDuelStats(playerId);
            if (stats != null) {
                value = stats.wins();
            }
        } catch (RuntimeException e) {
            // 连接层运行期异常（重连失败等）：保持未知，等退避后重试。
            value = null;
        } finally {
            long ttl = value != null ? RANK_CACHE_TTL_MILLIS : DUEL_WINS_RETRY_TTL_MILLIS;
            maybeEvictDuelWins();
            duelWinsCache.put(playerId, new DuelWinsEntry(value,
                    System.currentTimeMillis() + ttl));
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
                    // R-48：第三方排行榜可用的纯数字别名 + 新增的两个占位符。
                    // accuracy_raw / achievements_raw 与它们的带符号原名同源同门
                    // （见 RESULTS_GATED）；duel_wins / review_due 见各自的 case 注释。
                    "accuracy_raw", "achievements_raw", "duel_wins", "review_due",
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
        trimToCap(coinsCache, COINS_CACHE_MAX_ENTRIES);
    }

    /**
     * R-47：{@link #rankCache} 超限时淘汰一批。
     *
     * <p><b>复用 {@link #trimToCap} 而不是再抄一份循环</b>：两处淘汰逻辑若各写一遍，
     * 将来改「一次淘汰多少 / 怎么判上限」就会只改到一处 —— 本类已经因为「同一件事两处
     * 各判一遍」踩过隐私口径不一致的坑（见 {@code RESULTS_GATED} 的注释）。上限值本身
     * 仍是各自独立的常量（{@link RANK_CACHE_MAX_ENTRIES}），因为键的量级不同。
     */
    private void maybeEvictRanks() {
        trimToCap(rankCache, RANK_CACHE_MAX_ENTRIES);
    }

    /**
     * R-48：{@link #duelWinsCache} 超限时淘汰一批 —— 与 {@link #maybeEvictRanks()} 同范式。
     */
    private void maybeEvictDuelWins() {
        trimToCap(duelWinsCache, DUEL_WINS_CACHE_MAX_ENTRIES);
    }

    /**
     * R-47：把任意 {@code UUID -> 条目} 缓存修剪到给定上限之下。
     *
     * <p>ConcurrentHashMap 的 keySet 迭代器是<b>弱一致</b>的，多线程下迭代安全；
     * 淘汰不精确计数（并发 remove 可能少删几条），够用即可 —— 这不是 LRU，
     * 目标是「长期运行的服不会为每个曾查询过的玩家留一条永久条目」。
     *
     * @param map 目标缓存（非 null）
     * @param cap 条目上限；{@code map.size() < cap} 时零动作
     */
    private static <V> void trimToCap(Map<UUID, V> map, int cap) {
        if (map.size() < cap) {
            return;
        }
        int removed = 0;
        for (UUID key : map.keySet()) {
            if (removed >= CACHE_EVICT_BATCH) {
                break;
            }
            if (map.remove(key) != null) {
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
