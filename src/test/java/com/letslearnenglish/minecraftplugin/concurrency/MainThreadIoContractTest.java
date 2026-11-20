package com.letslearnenglish.minecraftplugin.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主线程同步查库的<b>源码 lint</b>（R21）。
 *
 * <h2>为什么需要它</h2>
 * R18~R20 手工修了 6 处「主线程同步查库」：{@code RewardEngine} 发币路径、
 * {@code AchievementWallGUI}、{@code WrongBookGUI}、{@code EnglishSquareGUI}、
 * {@code PlayerJoinListener}、{@code AIChatManager}。每一处都是<b>同一个缺陷形状</b>：
 * 在 {@code @EventHandler} 或 GUI 打开路径里直接 {@code getDatabaseManager().xxx()} /
 * {@code getPlayerDataStore().getXxx()}，缓存 miss 时就是一次 JDBC 往返。MySQL 远程库 +
 * {@code socketTimeout=30000} 可以把主线程钉住 30 秒，症状是<b>全服卡死</b>，而功能完全正常。
 * 这类退化没有任何单测能观测：界面照旧打开、数值照旧正确，只有 TPS 跟着玩家手速走。
 *
 * <p>手工修的代价是：下一个新 GUI / 新监听器会重新引入同样的缺陷，而没有任何东西拦它。
 * 本类就是那道拦截 —— 它<b>读源码</b>，断言「主线程会执行的代码里没有查库调用」。
 *
 * <h2>判据：怎么静态判断「这段代码跑在主线程」</h2>
 * 静态分析做不到精确的线程推断，所以这里用<b>保守优先</b>的两层模型（宁可漏，不可误报）：
 *
 * <ol>
 *   <li><b>方法级豁免</b>（两种）：
 *     <ul>
 *       <li>方法名以 {@code Async} 结尾（{@code openAsync} / {@code openSessionAsync} ——
 *           本仓约定俗成的异步入口）；</li>
 *       <li>在 {@link #ASYNC_ONLY_METHODS} 里的「只允许异步调用」取数方法
 *           （{@code loadSquareData} / {@code loadWrongBook} / {@code prefetchWallData} /
 *           {@code fetchSquarePage}）。</li>
 *     </ul>
 *     <b>第二种豁免不是逃生口</b>：这些方法名同时进入「主线程禁止调用」名单，所以
 *     「有人把它们搬回主线程」会被抓 —— 见
 *     {@link MainLint#asyncOnlyHelpersAreNotAnEscapeHatch()}。</li>
 *   <li><b>区域级</b>：方法体内 {@code runTaskAsynchronously(...)} 的实参不算主线程；
 *     而<b>嵌在异步段里的</b> {@code runTask(...)} / {@code runOnMain(...)} 实参
 *     <b>重新算回主线程</b>。后者是本仓「异步取数 → 切回主线程渲染」的标准形状，
 *     而渲染段恰恰最容易被人顺手塞一次查询。</li>
 * </ol>
 *
 * <h2>哪些算「查库调用」</h2>
 * <ul>
 *   <li><b>接收者兜底规则</b>：{@code getDatabaseManager().X(} —— X 不在
 *       {@link #DB_NON_IO_METHODS}（几个纯元数据方法）即判违规。用<b>允许名单</b>：
 *       {@code DatabaseManager} 的 public 方法几乎全是 IO，新加一个也几乎必然是 IO。
 *       这条规则是唯一能覆盖「将来新加的 DatabaseManager 方法」的机制。</li>
 *   <li><b>方法名禁止名单</b>：{@link #DB_READ_METHODS}。用<b>禁止名单</b>：
 *       {@code PlayerDataStore} 里大量方法是纯内存（{@code getProfileSettingsReadOnly} /
 *       {@code getWrongCountIfCached} …），用允许名单会在每个新内存方法上误报。</li>
 * </ul>
 * <b>带点与裸调用两种形态都匹配</b>（{@code .loadWrongBook(} 与
 * {@code loadWrongBook(}）—— R20-GUI2 实测过：只认带点形态会让「把包装方法内联回
 * 主线程」恒绿。
 *
 * <p>只覆盖<b>读</b>。同步<b>写</b>（{@code saveProfileSettings} / {@code unloadPlayerData}
 * 的落盘）刻意不在范围内：quit / reload 路径必须刷盘，那是设计决定而不是缺陷。
 *
 * <h2>已知违规基线</h2>
 * 本轮<b>只交付 lint、不修代码</b>，所以现有未修的点进 {@link #KNOWN_MAIN_THREAD_IO}。
 * 基线是<b>双向</b>的：漏登记 → 红；修好了却没从基线删 → 也红（提示可以删了）。
 * 这样基线不会腐化成"永久豁免"。
 *
 * <h2>变异轴（{@link Policy}）</h2>
 * 每个开关对应一种「实现被削弱」的失效形态，{@link MutationSelfChecks} 逐条把它们钉住。
 *
 * <h2>R31：扫描范围扩展到 {@code command/}（补系统性盲区）</h2>
 * 原扫描范围只有 {@code gui/} 与 {@code listener/}，<b>{@code command/} 整目录不在里面</b> ——
 * 而命令执行器同样跑在主线程（{@code CommandExecutor#onCommand} 由 Bukkit 在主线程调用），
 * 是同一条 {@code socketTimeout=30000} 卡死通道。{@code /le challenge} 就是从这个洞里漏出去的：
 * 它在主线程同步走了四次 JDBC，而本类当时<b>看不见</b>它。
 *
 * <p><b>只加目录还不够</b>（R31 实测）：命令层调的是 {@code DailyChallengeManager} 的
 * <i>门面方法</i>（{@code getTodayChallenges} / {@code getTodayWordProgress} …），
 * 这些名字不在 {@link #DB_READ_METHODS} 里，所以「把 {@code command} 加进
 * {@link #SCAN_DIRS}」后 {@code /le challenge} 仍是<b>零命中</b>。补盲区必须同时补
 * 「门面方法名」这一档 —— 见 {@link #DB_READ_METHODS} 的 R31 注释。
 */
@DisplayName("R21/R31：主线程同步查库的源码 lint（gui/、listener/、command/）")
class MainThreadIoContractTest {

    // ==================================================================
    // 白名单 / 黑名单（本 lint 的全部策略都在这一段）
    // ==================================================================

    /**
     * 被抽出来、<b>只允许在异步线程调用</b>的取数方法（含「包装方法」）。
     *
     * <p>为什么必须有这一档：本仓把取数逻辑从 GUI 主线程段里抽成独立方法
     * （{@code loadSquareData} / {@code loadWrongBook} / {@code prefetchWallData}），
     * 它们的方法体里<b>当然</b>有查库调用 —— 那是正确的，因为它们只从
     * {@code runTaskAsynchronously} 的 lambda 里被调用。没有这一档，每个异步取数方法
     * 都会误报，下一个人最自然的处置就是删掉整条 lint，防护归零。
     *
     * <p>为什么它们<b>同时</b>是「主线程禁止调用」：白名单只免除「方法体内容的判定」，
     * 不免除「谁可以调它」。{@code WrongBookGUI.openSyncFallback} 就是
     * {@code loadWrongBook(playerId)} 被内联回主线程的那条路径（R20-GUI2 变异 M1 实测
     * lint 恒绿），所以调用点必须单独设防。
     */
    static final Set<String> ASYNC_ONLY_METHODS = Set.of(
            "loadSquareData",     // EnglishSquareGUI：COUNT + 本页行 + 自己统计 + 金币
            "fetchSquarePage",    // EnglishSquareGUI：无调度器兜底用的「取一页行」
            "loadWrongBook",      // WrongBookGUI：getWrongBookEntries + loadDifficultWords
            "prefetchWallData"    // AchievementWallGUI：成就解锁/进度/时刻/前置四查
    );

    /** {@code runTaskAsynchronously} 家族：实参在异步线程执行。 */
    static final Set<String> ASYNC_DISPATCH = Set.of(
            "runTaskAsynchronously", "runTaskTimerAsynchronously", "runTaskLaterAsynchronously"
    );

    /**
     * {@code runTask} 家族 + 本仓自己的「切回主线程」包装 {@code runOnMain}：
     * <b>嵌在异步段里时</b>它们的实参重新算回主线程。
     *
     * <p><b>R31 补 {@code runTaskSafe} 与 {@code runMain}</b>：漏掉它们的后果实测过 ——
     * {@code LearnCommand.handleClassCommand} 的「异步取数 → runTaskSafe 回主线程开界面」
     * 里，如果把查库塞进 {@code runTaskSafe} 的 lambda，旧名单会判<b>干净</b>
     * （{@code runTaskSafe} 既不在 {@link #ASYNC_DISPATCH} 也不在旧 {@link #SYNC_DISPATCH}，
     * 于是它的实参继承外层的"异步"上下文 → 整段免检）。而 {@code runTaskSafe} 的实现
     * 就是 {@code Bukkit.getScheduler().runTask(...)} —— 一模一样的主线程。
     * {@code SchedulerGuard.runMain} 同理（{@code SocialManager}/{@code SeasonManager}
     * 的切回主线程口）。这类"薄封装"是最容易被下一个人当成异步用的形状。
     */
    static final Set<String> SYNC_DISPATCH = Set.of(
            "runTask", "runTaskLater", "runTaskTimer", "runOnMain", "runTaskLaterOnMain",
            "runTaskSafe", "runMain"
    );

    /**
     * {@code DatabaseManager} 上<b>不碰连接/语句</b>的方法 —— 读后端类型、表前缀、锁对象。
     *
     * <p>用允许名单的理由：该类 90+ 个 public 方法里几乎全是 IO，只有这几个是元数据；
     * 新加一个方法大概率也是 IO，默认判违规才是正确的失败方向。
     * 注意 {@code withConnection} / {@code getConnection} <b>不在</b>名单里 ——
     * 它们才是真正执行 SQL 的入口。
     */
    static final Set<String> DB_NON_IO_METHODS = Set.of(
            "isSQLite", "getTablePrefix", "getConnectionLock", "getNodeId",
            "getSqliteFilePath", "isConnectionHealthy"
    );

    /**
     * 查库读方法名（{@code PlayerDataStore} 与 {@code DatabaseManager} 的并集）。
     *
     * <p><b>刻意排除</b>（每条都对应一种已实测的误报/越界）：
     * <ul>
     *   <li>{@code getPlayerName} —— 有 {@code isPrimaryThread()} 守卫，非主线程回落 uuid；
     *       主线程名册查询是它既有的、刻意保留的行为。</li>
     *   <li>{@code getProfileSettings} / {@code getProfileSettingsReadOnly} /
     *       {@code getCachedProfileSettings} / {@code getWrongCountIfCached} /
     *       {@code isStatsUnloaded} / {@code isDifficultWordsReadComplete} —— 纯内存，
     *       零 IO（前两个的语义差异是<b>缓存污染</b>，不是线程问题，由别的用例守）。</li>
     *   <li>{@code getTotalSessions} / {@code getWrongCount} / {@code getTotalScore} ——
     *       与 {@code PlayerStats} / {@code DifficultWord} 的纯内存 getter <b>同名</b>，
     *       收进来会造成稳定误报（{@code stats.getTotalSessions()} 读的是内存字段）。</li>
     *   <li>{@code SocialManager.*} 与 {@code AchievementManager.*} —— 它们内部才走 DB，
     *       属那两个类自己的线程契约；在这里再列一遍会把「调用一个已异步化的服务方法」
     *       也判红。</li>
     * </ul>
     */
    static final Set<String> DB_READ_METHODS = Set.of(
            // ---- PlayerDataStore：缓存 miss 即一次 SELECT ----
            "getPlayerStats", "loadPlayerData", "loadPlayerAchievements",
            "getWrongBookEntries", "loadDifficultWords", "loadDifficultWordsFromDB",
            "getDifficultWordsData",
            "getSessionDays",
            "loadProfileSettings", "loadProfileSettingsForDisplay",
            "getActiveSquareEntries", "countActiveSquareEntries",
            "getAuthoritativeRank", "getRankedPlayers",
            "loadRankedPlayersForPeriod", "getRankedPlayersForPeriodFiltered",
            "getTodayAnswerCount", "getTodayAiRound", "getConversationCount",
            "getLastReviewTimes", "getReviewCounts", "getMasteredWords", "getRecentlyAnsweredWords",
            // ---- DatabaseManager：全部是 JDBC ----
            "getCoinsEarned", "getChatHistoryCount", "getChatSessions", "getChatSession",
            "getSessionPreview", "getChatFeedback",
            "getDuelStats", "getDuelWinStreak",
            "getOnboardingStep", "loadOnboardingStep",
            "getCheckInData", "loadCheckInData",
            "loadCheerers", "getCheerers", "countUnreadCheers", "getCheerStatsRow",
            // D4：广场打开路径上那次「有界同步查询」（plan-social §3.2 已接受，但此前
            // 不在任何门禁覆盖内 —— 它是本 lint 的隐形人）。收进名单后调用点才可见：
            // gui/EnglishSquareGUI.java#renderFeedEntries 的命中登记在
            // KNOWN_MAIN_THREAD_IO（附理由），而「别处又调一次」会立刻变红。
            "getCheeredFeedIds",
            "loadPendingSeasonRewards", "getPendingSeasonRewards", "getPendingSeasonRewardsFor",
            "getFeedCacheRows",
            "loadDailyChallengeWords", "getDailyChallengeWordsProgress", "loadWordChallengeSnapshot",
            "getAiRoundCount", "getDailyAnswerCount", "isDailyWordRewardClaimed",
            "getTopCoins", "getTopWordsLearned", "getTopCombo",
            "getClassByName", "listClasses", "getClassMembers", "getPlayerClassId",
            "getClassMemberCount",
            "getLast7DaysStats", "getTodayActivePlayers", "getTotalCoinsEarned", "getTotalWordCount",
            "loadMinSeasonId", "loadSeasonsWithOwedLedger", "getSettledSeason",
            "loadSessionParticipants", "getSessionParticipants",
            // ---- R31 新增：DailyChallengeManager 的<b>门面</b>方法 ----
            // 这几个名字本身不碰 JDBC，但它们的方法体<b>每个分支</b>都落到一条 JDBC：
            //   getTodayWordProgress  → getDailyChallengeWordsProgress
            //   getTodayWordDetails   → loadDailyChallengeWords（+ isWordCompleted 逐词）
            //   isWordRewardPaidToday → isDailyWordRewardClaimed（内存未命中时）
            //   getTodayChallenges    → repository.findTodayChallenges（+ degradeStale 写）
            // 不收进来就会漏掉 /le challenge 那四次主线程查库（R31 实测：只加 command/
            // 目录时它零命中，因为调用点是门面名而不是底层的 DB 方法名）。
            // 这类"门面即 IO"的名字要一次收全：漏一个就等于那一个门面在主线程上无保护。
            "getTodayWordProgress", "getTodayWordDetails", "isWordRewardPaidToday",
            "getTodayChallenges",
            // ---- 直接碰连接/语句的底层口子 ----
            "countRows", "withConnection", "getConnection", "executeUpdate"
    );

    /**
     * 本轮<b>不修</b>的已知违规（只交付 lint，改动另派）。
     *
     * <p>键 = {@code <相对路径>#<方法名>@<被调用的查库方法名>}。逐条理由见
     * {@link #KNOWN_WHY}；缺理由会红（避免基线变成"看名字猜原因"）。
     */
    static final Set<String> KNOWN_MAIN_THREAD_IO = Set.of(
            "gui/ProfileGUI.java#openSettings@loadProfileSettings",
            "gui/EnglishSquareGUI.java#openSyncFallback@loadSquareData",
            "gui/WrongBookGUI.java#openSyncFallback@loadWrongBook",
            "listener/MiscGUIListener.java#claimBehavioralChallenge@getTodayChallenges",
            "listener/MiscGUIListener.java#handlePrivacySettings@loadProfileSettings",
            "listener/PlayerJoinListener.java#onPlayerJoin@loadProfileSettings",
            "listener/SocialListener.java#handleEnglishSquareGUI@getActiveSquareEntries",
            "listener/SocialListener.java#handleFeedSlot@loadCheerers",
            "gui/EnglishSquareGUI.java#renderFeedEntries@getCheeredFeedIds",
            "command/DiagnoseCommand.java#probeOnce@getConnection"
    );

    /**
     * 每条基线的理由（键必须与 {@link #KNOWN_MAIN_THREAD_IO} 完全一致）。
     *
     * <p><b>为什么是 {@code Map.ofEntries} 而不是 {@code Map.of}</b>：{@code Map.of} 的
     * 重载只到 10 对键值，D4 登记后正好是第 11 条 —— 用 {@code Map.of} 会直接编译失败。
     * 将来再加基线时这里不需要再动（{@code ofEntries} 无上限）。
     */
    static final Map<String, String> KNOWN_WHY = Map.ofEntries(
            Map.entry("gui/ProfileGUI.java#openSettings@loadProfileSettings",
                    "设置页必须在渲染前拿到真实开关值（读侧 fail-closed，miss 会显示成「全关」的假象）。"
                            + "单条主键 SELECT，由玩家主动点击触发；同步是刻意的，异步化需要三态占位 UI。"),
            Map.entry("gui/EnglishSquareGUI.java#openSyncFallback@loadSquareData",
                    "无调度器兜底分支：Bukkit.getScheduler() 在活服务器上永不为 null，生产不可达，"
                            + "只服务纯 JVM 单测夹具。抽成独立方法就是为了让这条边界可被静态指认。"),
            Map.entry("gui/WrongBookGUI.java#openSyncFallback@loadWrongBook",
                    "同上（错题本版的无调度器兜底，生产不可达）。"),
            Map.entry("listener/MiscGUIListener.java#handlePrivacySettings@loadProfileSettings",
                    "隐私开关的<b>写</b>路径前置闸：缓存 miss 时必须先读真实值，否则 "
                            + "getProfileSettings 的 computeIfAbsent 会合成「全公开」默认值，"
                            + "玩家点一下就把 show_in_square=true 永久写回 DB。"
                            + "宁可同步读一次也不能丢隐私位（且这是点击触发的单条主键 SELECT）。"),
            Map.entry("listener/PlayerJoinListener.java#onPlayerJoin@loadProfileSettings",
                    "进服预热同步段：返回值在<b>同一方法后文</b>被消费两次（进服提醒 / 新手引导都读 "
                            + "isRemindersEnabled()），而该位 miss 时是 fail-open(true) —— 异步化等于把"
                            + "「玩家关掉了提醒」读成「开着」，是可感知的越权打扰而非安全降级。"),
            Map.entry("listener/SocialListener.java#handleEnglishSquareGUI@getActiveSquareEntries",
                    "广场点击侧的无快照/陈旧快照兜底重建：按屏幕页码从 DB 取回本页行（有界，28 行）。"
                            + "正常路径复用 open() 的快照；快照缺失窗口很窄（打开后第一次点击前），"
                            + "但这条路径确实在主线程查库。"),
            Map.entry("listener/SocialListener.java#handleFeedSlot@loadCheerers",
                    "鼓励名单的 readFailed 复检：getCheerersAsync 的回调已在主线程，回调里又同步查了一次 "
                            + "loadCheerers 只为区分「确实无人鼓励」与「读失败」。"
                            + "修法应让异步侧一并带回 readFailed（与 loadCheerers 的 record 同形）。"),
            Map.entry("gui/EnglishSquareGUI.java#renderFeedEntries@getCheeredFeedIds",
                    "广场打开/翻页渲染段上<b>唯一</b>保留的同步查询（plan-social §3.2 明示接受的取舍）。"
                            + "输入有界：只取本页 ≤28 个 feed id，走 social_cheers 的 "
                            + "UNIQUE(feed_id, cheerer_uuid) 索引，量级与既有 getPlayerStats 兜底同级。"
                            + "业务上它必须在渲染前拿到「我点过哪些」，否则卡片上的「已鼓励」标记要等"
                            + "一次界面重开才正确；异步化需要给每条 lore 做占位回填，属独立的一批改动。"
                            + "D4 之前它<b>不在</b>任何门禁覆盖内（本 lint 的隐形人）—— 登记在这里的"
                            + "目的是让「别处又调它」和「这次查询本身失控」都变成可见的红，而不是继续隐形。"),
            Map.entry("listener/MiscGUIListener.java#claimBehavioralChallenge@getTodayChallenges",
                    "行为挑战<b>领奖</b>路径的状态复读：调用方已持该玩家的 stripe 锁，"
                            + "「读状态 → 判定 → 付款 → 标记」必须整段串行化（R-02），"
                            + "异步化会把锁内判定拆成两段，重复点击就能二次付款。"
                            + "这是<b>判定</b>不是展示，与 /le challenge（纯展示，R31 已异步化）本质不同。"
                            + "属于玩家主动点击触发的单次查询，且已被 CLAIM_SLOW_THRESHOLD_MS 观测。"),
            Map.entry("command/DiagnoseCommand.java#probeOnce@getConnection",
                    "诊断命令的连通性探测本体：必须<b>真发一条语句</b>才能识别坏连接 —— "
                            + "DatabaseManager 换发的 UNAVAILABLE 代理 isClosed() 恒 false（EH-01），"
                            + "只问连接对象自己永远得到「我很健康」。语句超时被 setQueryTimeout 压到 5s，"
                            + "且只在 /lea diagnose 这种管理员手工触发的低频路径上跑。"
                            + "异步化会引入「报告没跑完命令就返回」的半成品输出（见该方法的耗时论证）。")
    );

    // ==================================================================
    // 1. 主 lint
    // ==================================================================

    @Nested
    @DisplayName("组 1 —— 主 lint：gui/、listener/、command/ 的主线程段不得查库")
    class MainLint {

        @Test
        @DisplayName("主线程段零查库（未登记的违规 → 红，并逐条报 文件:行号）")
        void noUnregisteredMainThreadIo() throws IOException {
            List<Finding> found = scan(mainJavaRoot(), Policy.defaults());
            assertFalse(found.isEmpty(),
                    "扫描结果为空 —— 要么扫描路径失效，要么分析器把一切都当成了异步段。"
                            + "这时 lint 恒绿、零判别力（比没有 lint 更糟：它让人以为已经被守住）。"
                            + "见「组 2 —— lint 自检」里的逐步自检。");

            Set<String> unexpected = new TreeSet<>();
            for (Finding f : found) {
                unexpected.add(f.baselineKey());
            }
            unexpected.removeAll(KNOWN_MAIN_THREAD_IO);
            assertTrue(unexpected.isEmpty(),
                    "发现 " + unexpected.size() + " 处<b>未登记</b>的主线程同步查库喵 —— "
                            + "这些是本次 lint 抓出来的新违规（或有人把修好的代码又搬回主线程）。\n"
                            + "修法：把查库挪进 runTaskAsynchronously 的 lambda，主线程段只渲染；"
                            + "或（若确属设计决定）登记进 KNOWN_MAIN_THREAD_IO 并补 KNOWN_WHY。\n"
                            + "未登记项：\n" + String.join("\n", unexpected)
                            + "\n\n本次全部命中（含已登记）：\n" + report(found));
        }

        @Test
        @DisplayName("基线双向自洽：每条登记项都还在（修好了就得删登记），且都有理由")
        void knownViolationsAreDocumentedAndStillPresent() throws IOException {
            List<Finding> found = scan(mainJavaRoot(), Policy.defaults());
            Set<String> actual = new TreeSet<>();
            for (Finding f : found) {
                actual.add(f.baselineKey());
            }
            Set<String> stale = new TreeSet<>(KNOWN_MAIN_THREAD_IO);
            stale.removeAll(actual);
            assertTrue(stale.isEmpty(),
                    "这些登记项已经不再是违规了喵 —— 说明对应的主线程查库已被修好（或代码被删）。"
                            + "请把它们从 KNOWN_MAIN_THREAD_IO / KNOWN_WHY 里删掉，"
                            + "否则基线会腐化成永久豁免，将来同一处再退化时不会再红。\n"
                            + "已失效的登记：\n" + String.join("\n", stale)
                            + "\n\n当前实际命中：\n" + report(found));

            Set<String> undocumented = new TreeSet<>(KNOWN_MAIN_THREAD_IO);
            undocumented.removeAll(KNOWN_WHY.keySet());
            assertTrue(undocumented.isEmpty(),
                    "这些登记项缺少理由喵 —— 基线里每一条都必须写清「为什么这一处可以留在主线程」，"
                            + "否则下一个人无法判断它是否还是设计决定，只能猜。缺失：\n"
                            + String.join("\n", undocumented));
        }

        /**
         * 白名单的双向性：{@code ASYNC_ONLY_METHODS} <b>只</b>免除「方法体内容的判定」，
         * 不免除「谁可以调它」。
         *
         * <p>两次扫描对比：把豁免清空后，命中数必须<b>增加</b>（证明豁免确实在起作用），
         * 但「对这几个方法名的调用点」命中集合必须<b>完全不变</b>（证明豁免没有顺手
         * 把调用点也放过）。这正是 R20-GUI2 变异 M1 的形状：只免方法体、不免调用点。
         */
        @Test
        @DisplayName("白名单不是逃生口：清空豁免 → 命中变多，但调用点命中集合不变")
        void asyncOnlyHelpersAreNotAnEscapeHatch() throws IOException {
            List<Finding> withWhitelist = scan(mainJavaRoot(), Policy.defaults());
            List<Finding> withoutBodyExemption =
                    scan(mainJavaRoot(), Policy.defaults().withoutAsyncOnlyBodies());

            assertTrue(withoutBodyExemption.size() > withWhitelist.size(),
                    "清空「异步专用方法体豁免」后命中数没有增加喵 —— 说明这些取数方法的方法体"
                            + "其实没被豁免过（白名单是摆设），或它们里面根本没有查库调用"
                            + "（那本身就是一条回归：取数方法不查库 = 数据永远不回来）。"
                            + "with=" + withWhitelist.size()
                            + " without=" + withoutBodyExemption.size());

            Set<String> callSitesWith = new TreeSet<>();
            for (Finding f : withWhitelist) {
                if (ASYNC_ONLY_METHODS.contains(f.member())) {
                    callSitesWith.add(f.baselineKey());
                }
            }
            Set<String> callSitesWithout = new TreeSet<>();
            for (Finding f : withoutBodyExemption) {
                if (ASYNC_ONLY_METHODS.contains(f.member())) {
                    callSitesWithout.add(f.baselineKey());
                }
            }
            assertEquals(callSitesWith, callSitesWithout,
                    "「对异步专用方法的调用点」在两次扫描里不一致喵 —— "
                            + "说明方法体豁免把调用点也一起放过了（那样谁都能从主线程调它），"
                            + "或者两套策略的调用点判定本身不同源。\nwith: " + callSitesWith
                            + "\nwithout: " + callSitesWithout);
        }
    }

    // ==================================================================
    // 2. lint 自检：判据本身必须可证伪
    // ==================================================================

    @Nested
    @DisplayName("组 2 —— lint 自检：剥注释 / 剥字符串 / 裸调用 / 异步段边界 / 白名单")
    class LintSelfChecks {

        /** <b>自检 1（验收要求 #1）：剥注释有效。</b>本仓 lint 因注释假阳性超过 4 次。 */
        @Test
        @DisplayName("剥注释：干净方法里塞一条 // getDatabaseManager().xxx() → 仍然绿")
        void commentsAreStripped() {
            // 注释里两种形态都要放：接收者链（getDatabaseManager().getCoinsEarned）与
            // 裸调用（loadProfileSettings）。后者尤其重要 —— 本仓真正的主线程 IO 入口
            // 常常是包装方法的裸调用，注释里也最常被这样引用。
            String withComment = "void render() {\n"
                    + "  // R18 修复前这里是 getDatabaseManager().getCoinsEarned(id);\n"
                    + "  /* 也不要 loadProfileSettings(id); */\n"
                    + "  draw();\n"
                    + "}\n";
            assertTrue(detectAll(withComment, Policy.defaults()).isEmpty(),
                    "注释里的 getDatabaseManager().getCoinsEarned( 与 loadProfileSettings( 被判成了违规喵 —— "
                            + "本仓的说明文字里到处写着被禁的方法名（本类的 javadoc 就在讲这件事），"
                            + "不剥注释的话整条 lint 会被自己的注释满足/推翻。"
                            + "实际命中：" + detectAll(withComment, Policy.defaults()));

            // 反向：去掉注释符后<b>必须变红</b>（证明上一条不是因为「根本没扫到」才绿的）。
            String asCode = "void render() {\n"
                    + "  plugin.getDatabaseManager().getCoinsEarned(id);\n"
                    + "  loadProfileSettings(id);\n"
                    + "  draw();\n"
                    + "}\n";
            List<Finding> asCodeHits = detectAll(asCode, Policy.defaults());
            assertFalse(asCodeHits.isEmpty(),
                    "把注释符去掉后仍然判为干净喵 —— 说明剥注释把整段代码也吃掉了（边界算错），"
                            + "那样 lint 恒绿、零判别力。");
            assertEquals(2, asCodeHits.size(),
                    "去注释后应命中 2 处（接收者链 + 裸调用），实际 " + asCodeHits.size()
                            + "：" + asCodeHits + " —— 少一处说明某条匹配规则没工作");
        }

        /** <b>自检 2：字符串字面量里的调用形态不得误报。</b> */
        @Test
        @DisplayName("剥字符串：日志/文案里的方法名不得被判为违规")
        void literalsAreStripped() {
            // 刻意写成<b>带括号的调用形态</b>：只有 `getCoinsEarned` 这种裸名字的话，
            // 即使不剥字符串也不会命中（名单规则要求后面跟 `(`）—— 那样这条自检就是
            // 自证桩，`mutationLiteralStrippingRemoved` 也永远杀不死。
            String snippet = "void logIt() {\n"
                    + "  logger.warning(\"slow path: getDatabaseManager().getCoinsEarned(id) returned 0\");\n"
                    + "  send(\"loadProfileSettings(id) failed\");\n"
                    + "}\n";
            assertTrue(detectAll(snippet, Policy.defaults()).isEmpty(),
                    "字符串字面量里的调用形态被当成真调用了喵 —— 这类文案（尤其日志与报错说明）"
                            + "会让 lint 稳定误红，最后必然被删掉。实际命中："
                            + detectAll(snippet, Policy.defaults()));
        }

        /** <b>自检 3：裸调用必须被抓到（R20-GUI2 实测过的真缺口）。</b> */
        @Test
        @DisplayName("裸调用：loadWrongBook(id) 不带点也命中；loadWrongBookSafe(id) 不命中")
        void bareInvocationsAreMatched() {
            String bare = "void open() {\n  loadWrongBook(playerId);\n}\n";
            assertFalse(detectAll(bare, Policy.defaults()).isEmpty(),
                    "裸调用 loadWrongBook( 没被抓住喵 —— 本类的包装方法调用都没有前导点，"
                            + "只认 `.name(` 会让「把包装方法内联回主线程」这类回归静默通过。");

            String longer = "void open() {\n  loadWrongBookSafe(playerId);\n}\n";
            assertTrue(detectAll(longer, Policy.defaults()).isEmpty(),
                    "更长标识符的尾部（loadWrongBookSafe）被误判了喵 —— "
                            + "同名后缀的不同方法不该命中，否则 lint 会因为命名风格误红。实际："
                            + detectAll(longer, Policy.defaults()));

            // 方法引用 store::getPlayerStats 不在此刻执行 —— 单独一张契约，不该算主线程查库
            String methodRef = "void open() {\n  use(this::getPlayerStats);\n}\n";
            assertTrue(detectAll(methodRef, Policy.defaults()).isEmpty(),
                    "方法引用 getPlayerStats 被判成了调用喵 —— 方法引用只是把能力传出去，"
                            + "真正执行的线程由接收方决定（如 runTaskAsynchronously 内）");
        }

        /** <b>自检 4：异步段边界 + 嵌套回主线程。</b> */
        @Test
        @DisplayName("异步段边界：lambda 内不报；lambda 外必报；嵌套 runTask/runOnMain 重新算主线程")
        void asyncRegionBoundaries() {
            String ok = "void open() {\n"
                    + "  scheduler.runTaskAsynchronously(plugin, () -> {\n"
                    + "    store.getPlayerStats(id);\n"
                    + "  });\n"
                    + "  draw();\n"
                    + "}\n";
            assertTrue(detectAll(ok, Policy.defaults()).isEmpty(),
                    "异步 lambda 里的查询被判成了主线程查库喵 —— 于是所有已修好的 GUI 都会误红，"
                            + "下一个人只能删掉 lint。实际：" + detectAll(ok, Policy.defaults()));

            String bad = "void open() {\n"
                    + "  store.getPlayerStats(id);\n"
                    + "  scheduler.runTaskAsynchronously(plugin, () -> { draw(); });\n"
                    + "}\n";
            assertFalse(detectAll(bad, Policy.defaults()).isEmpty(),
                    "把查询挪到 runTaskAsynchronously <b>之前</b>（= 回到主线程）后仍判为干净喵 —— "
                            + "这是本 lint 存在的全部意义所在，它必须有判别力。");

            // 嵌套：异步取数 → runTask 切回主线程渲染。渲染段是最容易被人补一次查询的地方。
            String nested = "void open() {\n"
                    + "  scheduler.runTaskAsynchronously(plugin, () -> {\n"
                    + "    store.getPlayerStats(id);\n"
                    + "    scheduler.runTask(plugin, () -> {\n"
                    + "      store.getWrongBookEntries(id);\n"
                    + "    });\n"
                    + "  });\n"
                    + "}\n";
            List<Finding> nestedFound = detectAll(nested, Policy.defaults());
            assertEquals(1, nestedFound.size(),
                    "嵌套场景应<b>只</b>报回主线程那一处（async 段里的 getPlayerStats 不该报），"
                            + "实际报了 " + nestedFound.size() + " 处：" + nestedFound);
            assertEquals("getWrongBookEntries", nestedFound.get(0).member(),
                    "嵌套 runTask（回主线程）里的查询没被抓住喵 —— 那样"
                            + "「异步取数、切回主线程渲染」这条最标准的形状里，"
                            + "渲染段的查库会完全失守");

            // runOnMain 是本仓自己的「切回主线程」包装，与 runTask 同待遇
            String viaRunOnMain = "void open() {\n"
                    + "  scheduler.runTaskAsynchronously(plugin, () -> {\n"
                    + "    runOnMain(scheduler, () -> store.getCoinsEarned(id));\n"
                    + "  });\n"
                    + "}\n";
            assertFalse(detectAll(viaRunOnMain, Policy.defaults()).isEmpty(),
                    "runOnMain(...) 的回调没被算回主线程喵 —— 广场/错题本/主页都用这个包装"
                            + "切回主线程，漏掉它等于漏掉这三条最关键的渲染路径");
        }

        /** <b>自检 5：接收者规则，且不得误伤「传引用」与元数据方法。</b> */
        @Test
        @DisplayName("接收者规则：getDatabaseManager().xxx() 命中；传引用 / isSQLite / getTablePrefix 不命中")
        void databaseManagerReceiverRule() {
            assertFalse(detectAll("void f() {\n  var n = plugin.getDatabaseManager().someNewQuery(id);\n}\n",
                            Policy.defaults()).isEmpty(),
                    "getDatabaseManager().任意方法( 这条兜底规则失效了喵 —— 它是唯一能抓住"
                            + "「将来新加的 DatabaseManager 方法」的机制；只靠 DB_READ_METHODS "
                            + "名单会漏掉所有新方法，而 DatabaseManager 的 public 方法几乎全是 IO。");

            // 「传引用」不是调用：getDatabaseManager() 后面不跟 `.name(`，零 IO。
            // 注意片段必须写成<b>真正的方法声明</b>（带返回类型）—— 只写
            // `MiscGUIListener(...)` 会让它不被识别为方法，命中被整体跳过，
            // 于是这条断言会因为「没扫」而不是「规则正确」而通过（自证桩）。
            String passByReference = "private void wire() {\n"
                    + "  this.repo = new DailyChallengeRepository(plugin.getDatabaseManager(), plugin.getLogger());\n"
                    + "}\n";
            assertTrue(detectAll(passByReference, Policy.defaults()).isEmpty(),
                    "把 getDatabaseManager() 当引用传给构造函数被判成了查库喵 —— 那本身零 IO，"
                            + "误报会让人被迫写一堆豁免，最后整条 lint 被关掉。实际："
                            + detectAll(passByReference, Policy.defaults()));

            for (String meta : new String[] {"isSQLite", "getTablePrefix", "getConnectionLock"}) {
                String snippet = "void f() {\n  boolean b = plugin.getDatabaseManager()." + meta + "();\n}\n";
                assertTrue(detectAll(snippet, Policy.defaults()).isEmpty(),
                        meta + "() 被判成了查库喵 —— 它读的是后端类型/表前缀/锁对象，零 JDBC");
            }
        }

        /**
         * <b>自检 6（验收要求 #3）：白名单有效。</b>
         * {@code openAsync} 里调查库 → 必须绿；去掉豁免 → 必须红。
         */
        @Test
        @DisplayName("Async 入口豁免：openAsync 体内调查库 → 绿；去掉豁免 → 红")
        void asyncEntryPointsAreExempt() {
            String snippet = "void openAsync(Player p) {\n"
                    + "  store.getPlayerStats(p.getUniqueId());\n"
                    + "}\n";
            assertTrue(detectAll(snippet, Policy.defaults()).isEmpty(),
                    "openAsync 里的查库被判成了违规喵 —— 于是每个异步入口都要误报，"
                            + "而「方法名以 Async 结尾」正是本仓约定俗成的异步入口标记。实际："
                            + detectAll(snippet, Policy.defaults()));
            assertFalse(detectAll(snippet, Policy.defaults().withoutAsyncSuffix()).isEmpty(),
                    "去掉 Async 豁免后仍然判绿喵 —— 白名单没接上，"
                            + "这条自检是自证桩（它证明不了白名单在起作用）");
        }

        /**
         * <b>自检 7（R31）：{@code command/} 必须在扫描范围里。</b>
         *
         * <p>这条钉住的是<b>盲区本身</b>，不是某条违规。{@code /le challenge} 在主线程
         * 同步查库四次却能长期存在，唯一原因就是 {@code command/} 整目录不在
         * {@code SCAN_DIRS} 里 —— 而命令执行器与 {@code @EventHandler} 一样跑在主线程。
         *
         * <p>变异指向：把 {@code command} 从 {@link #SCAN_DIRS} 里删掉 → 本条红
         * （{@link #commandSourcesAreScanned()} 的 {@code assertTrue} 直接失败）。
         */
        @Test
        @DisplayName("扫描范围：command/ 必须在 SCAN_DIRS 里，且真的被扫到")
        void commandSourcesAreScanned() throws IOException {
            assertTrue(SCAN_DIRS.contains(PKG + "command"),
                    "SCAN_DIRS 里没有 command/ 喵 —— 命令执行器同样跑在主线程，"
                            + "漏掉整目录等于命令层零保护。/le challenge 的四次主线程查库"
                            + "就是从这个洞里漏出去的。当前范围：" + SCAN_DIRS);

            // 只断言常量存在会被「常量写着但 walk 没扫到」骗过（目录名打错即静默零命中）。
            // 直接数 LearnCommand 的命中：它的 handleChallengeCommand 已异步化，
            // 但 renderChallenge 里若有回归应能被抓到 —— 这里只验「文件确实被打开解析」。
            Path learned = mainJavaRoot().resolve(PKG + "command/LearnCommand.java");
            assertTrue(Files.isRegularFile(learned),
                    "找不到 LearnCommand.java 喵：" + learned.toAbsolutePath());

            // 反向证据：用一段含已知违规的合成源码驱动同一个扫描器，确认它对该文件返回非空。
            // 真正的判别力证据在 {@link InjectionRegression#injectedCommandViolationIsCaught()}。
            String synthetic =
                    "package x;\n"
                            + "class FakeCommand {\n"
                            + "  void onCommand() {\n"
                            + "    plugin.getDatabaseManager().countRows(\"t\");\n"
                            + "  }\n"
                            + "}\n";
            assertFalse(scanSource(synthetic, "command/FakeCommand.java", Policy.defaults()).isEmpty(),
                    "扫描器对 command/ 下的合成违规返回空喵 —— 扫描范围接上了但分析器对该目录失效，"
                            + "这条 lint 会恒绿（比没有更糟：让人以为命令层已被守住）");
        }

        /**
         * <b>自检 8（R31）：薄封装的「切回主线程」口必须被识别。</b>
         *
         * <p>{@code runTaskSafe}（{@code LearnCommand}）与 {@code SchedulerGuard.runMain}
         * （{@code SocialManager} / {@code SeasonManager}）都只是 {@code runTask} 的薄封装，
         * 跑在主线程。旧名单里没有它们，于是它们 lambda 里的查库会<b>继承外层的异步上下文</b>
         * 而被判干净 —— 那是一整片假阴性（"异步取数 → 回主线程渲染"结构里的渲染段）。
         *
         * <p>变异指向：把 {@code runTaskSafe} 从 {@link #SYNC_DISPATCH} 删掉 → 本条红。
         */
        @Test
        @DisplayName("切回主线程的薄封装：runTaskSafe / SchedulerGuard.runMain 的实参算主线程")
        void mainThreadWrapperHelpersAreRecognized() {
            for (String wrapper : new String[] {"runTaskSafe", "runMain", "runOnMain"}) {
                String snippet = "void open() {\n"
                        + "  scheduler.runTaskAsynchronously(plugin, () -> {\n"
                        + "    " + wrapper + "(() -> store.getPlayerStats(id));\n"
                        + "  });\n"
                        + "}\n";
                assertFalse(detectAll(snippet, Policy.defaults()).isEmpty(),
                        wrapper + "(...) 的回调没被算回主线程喵 —— 它只是 runTask 的薄封装，"
                                + "实参跑在主线程。漏掉它 = 「异步取数 → 切回主线程渲染」这条"
                                + "最标准的形状里，渲染段查库完全失守。实际命中："
                                + detectAll(snippet, Policy.defaults()));

                // 反向：把它从名单里摘掉后必须真的变绿 —— 否则上一条不是本表在起作用。
                assertTrue(detectAll(snippet, Policy.defaults().withoutSyncDispatch()).isEmpty(),
                        "摘掉 SYNC_DISPATCH 后 " + wrapper + " 仍然命中喵 —— "
                                + "说明这条判据不是靠那张表实现的，上一条是自证桩");
            }
        }

        /**
         * <b>自检 9（R31）：门面方法名也算查库（{@code DailyChallengeManager} 那一档）。</b>
         *
         * <p>命令层调的常常是<i>门面</i>而不是底层 DAO 方法（{@code getTodayChallenges}
         * 而非 {@code repository.findTodayChallenges}）。门面名不在名单里时，
         * 「把 command/ 加进扫描范围」依然是零命中 —— R31 实测过这个组合坑。
         *
         * <p>变异指向：把 {@code getTodayChallenges} 从 {@link #DB_READ_METHODS} 删掉 → 本条红。
         */
        @Test
        @DisplayName("门面方法名：DailyChallengeManager 的四个门面在主线程被调 → 命中")
        void facadeMethodsCountAsIo() {
            for (String facade : new String[] {"getTodayWordProgress", "getTodayWordDetails",
                    "isWordRewardPaidToday", "getTodayChallenges"}) {
                String snippet = "void handle(Player p) {\n"
                        + "  var v = dcm." + facade + "(p);\n"
                        + "}\n";
                assertFalse(detectAll(snippet, Policy.defaults()).isEmpty(),
                        facade + "( 没被当成查库喵 —— 它每个分支都落到一条 JDBC，"
                                + "/le challenge 的四次主线程查库正是这样漏掉的。实际命中："
                                + detectAll(snippet, Policy.defaults()));
            }
        }
    }

    // ==================================================================
    // 3. 变异：把实现削弱 → 必须变红
    // ==================================================================

    @Nested
    @DisplayName("组 3 —— 变异：削弱判据后必须变红")
    class MutationSelfChecks {

        /**
         * <b>变异 M1（任务点名的「把白名单删掉」）：清空异步取数方法白名单 → 必须有一批红。</b>
         *
         * <p>注意 <b>为什么变异打在这条而不是 {@code *Async} 豁免上</b>：本仓所有
         * {@code openAsync} 的查库调用都写在 {@code runTaskAsynchronously} 的 lambda 里
         * （区域级判据已经判它们是非主线程），所以去掉 {@code *Async} 豁免在真实源码树上
         * <b>一处红都不会多</b> —— 那条豁免只是便利，真正有咬合力的是本条白名单。
         * {@code *Async} 豁免由 {@link LintSelfChecks#asyncEntryPointsAreExempt()} 用
         * 合成片段单独钉住。
         */
        @Test
        @DisplayName("变异 M1：清空 ASYNC_ONLY_METHODS → 一批命中变红")
        void mutationAsyncHelperWhitelistRemoved() throws IOException {
            List<Finding> baseline = scan(mainJavaRoot(), Policy.defaults());
            List<Finding> mutated = scan(mainJavaRoot(), Policy.defaults().withoutAsyncOnlyBodies());

            int delta = mutated.size() - baseline.size();
            assertTrue(delta >= 4,
                    "清空白名单后只多出 " + delta + " 处命中喵 —— 预期至少 4 处"
                            + "（loadSquareData 的 COUNT/统计/金币、loadWrongBook 的错题查询、"
                            + "prefetchWallData 的成就查询）。多出太少说明白名单里某个方法其实"
                            + "已经不再查库（回归），或方法体解析把他们漏掉了。");

            Set<String> newlyReported = new TreeSet<>();
            for (Finding f : mutated) {
                if (!baseline.contains(f)) {
                    newlyReported.add(f.method() + " → " + f.member());
                }
            }
            for (String must : new String[] {
                    "loadSquareData → countActiveSquareEntries",
                    "loadWrongBook → getWrongBookEntries",
                    "loadSquareData → getCoinsEarned"}) {
                assertTrue(newlyReported.contains(must),
                        "变异后没有报出「" + must + "」喵 —— 白名单豁免对这条查询不起作用"
                                + "（要么它根本不在被豁免的方法里，要么该方法体没被解析到）。"
                                + "变异后新增：" + newlyReported);
            }
        }

        /** <b>变异 M2（任务点名）：把剥注释那段删掉 → 自检用例变红。</b> */
        @Test
        @DisplayName("变异 M2：不剥注释 → 注释里的调用被当成违规（自检用例变红）")
        void mutationCommentStrippingRemoved() {
            String snippet = "void render() {\n"
                    + "  // 修复前：getDatabaseManager().getCoinsEarned(id);\n"
                    + "  draw();\n"
                    + "}\n";
            assertTrue(detectAll(snippet, Policy.defaults()).isEmpty(),
                    "默认策略下注释应被剥掉、判干净");
            assertFalse(detectAll(snippet, Policy.defaults().withoutCommentStripping()).isEmpty(),
                    "不剥注释后注释里的调用<b>没有</b>被判出来喵 —— 那说明剥注释这一步其实"
                            + "没在起作用（变异未被杀死），{@link LintSelfChecks#commentsAreStripped()}"
                            + " 就是自证桩。");
        }

        /** <b>变异 M3：只认 {@code .name(} → 裸调用漏报（R20-GUI2 的真缺口）。</b> */
        @Test
        @DisplayName("变异 M3：dottedOnly → 裸调用 loadWrongBook( 漏报")
        void mutationDottedOnlyMissesBareCalls() {
            String bare = "void open() {\n  loadWrongBook(playerId);\n}\n";
            assertFalse(detectAll(bare, Policy.defaults()).isEmpty(), "默认策略应抓住裸调用");
            assertTrue(detectAll(bare, Policy.defaults().withDottedReceiverOnly()).isEmpty(),
                    "只认带点形态时竟然抓住了裸调用 —— 变异开关没接上，M3 是假变异");
            // 带点的形态在该策略下仍必须命中（否则这个开关把 lint 整个关掉了）
            assertFalse(detectAll("void open() {\n  store.countActiveSquareEntries();\n}\n",
                            Policy.defaults().withDottedReceiverOnly()).isEmpty(),
                    "只认带点形态下带点调用也没命中 —— 开关实现错了");
        }

        /** <b>变异 M4：不剥字符串 → 日志文案误报。</b> */
        @Test
        @DisplayName("变异 M4：不剥字符串 → 日志里的调用形态被误报")
        void mutationLiteralStrippingRemoved() {
            // 片段必须写成<b>带括号的调用形态</b>（getCoinsEarned(id)），
            // 否则名单规则（要求后面跟 `(`）根本不会命中，这个变异永远杀不死 —— 假变异。
            String snippet = "void logIt() {\n"
                    + "  logger.warning(\"slow: getCoinsEarned(id) returned 0\");\n"
                    + "}\n";
            assertTrue(detectAll(snippet, Policy.defaults()).isEmpty(),
                    "默认策略下字符串应被剥掉、判干净");
            assertFalse(detectAll(snippet, Policy.defaults().withoutLiteralStripping()).isEmpty(),
                    "不剥字符串后日志文案里的方法名<b>没有</b>被误报喵 —— "
                            + "那说明剥字符串这一步没起作用（变异未被杀死）");
        }

        /** <b>变异 M5：关掉「嵌套回主线程」翻转 → 渲染段的查库全部漏报。</b> */
        @Test
        @DisplayName("变异 M5：不翻转嵌套 runTask → 渲染段查库漏报")
        void mutationNestedMainThreadFlipRemoved() {
            // 注：翻转逻辑由 SYNC_DISPATCH 集合驱动，这里用「清空该集合」等价表达
            String nested = "void open() {\n"
                    + "  scheduler.runTaskAsynchronously(plugin, () -> {\n"
                    + "    runOnMain(() -> store.getCoinsEarned(id));\n"
                    + "  });\n"
                    + "}\n";
            assertFalse(detectAll(nested, Policy.defaults()).isEmpty(),
                    "默认策略下 runOnMain 的回调算主线程，应命中");
            assertTrue(detectAll(nested, Policy.defaults().withoutSyncDispatch()).isEmpty(),
                    "清空 SYNC_DISPATCH 后仍然命中喵 —— 说明翻转逻辑不是靠那张表实现的，"
                            + "M5 是假变异（也就无法证明「切回主线程」这条判据真的在工作）");
        }
    }

    // ==================================================================
    // 4. 注入式回归：真违规必须被抓住，且报出的行号就是那一处
    // ==================================================================

    @Nested
    @DisplayName("组 4 —— 注入式回归（验收要求 #2）：搬进主线程方法体 → 红，行号精确")
    class InjectionRegression {

        /**
         * 在 {@code @TempDir} 的<b>源码副本</b>里做变异（绝不改仓库）：把
         * {@code WrongBookGUI.render(...)} 这个主线程渲染方法的第一行换成一次查库调用。
         *
         * <p>为什么这条要在测试里常驻（而不是手工验一次就完）：它把「lint 能抓住真违规 +
         * 报出的位置是那一处」变成每次构建都跑的证据。手工验一次的证据会随代码漂移失效。
         */
        @Test
        @DisplayName("把 getWrongBookEntries 注入 render() → 必须报 gui/WrongBookGUI.java:<注入行>")
        void injectedViolationIsCaughtWithExactLocation(@TempDir Path tmp) throws IOException {
            Path rel = Path.of("com/letslearnenglish/minecraftplugin/gui/WrongBookGUI.java");
            String original = Files.readString(mainJavaRoot().resolve(rel), StandardCharsets.UTF_8);
            String injected = "plugin.getPlayerDataStore().getWrongBookEntries(player.getUniqueId());";
            String relKey = "gui/WrongBookGUI.java";

            // 变异前的对照：render() 当前必须是干净的 —— 否则「变红」不能归因于注入
            assertEquals(0, findingsForMethod(original, relKey, "render").size(),
                    "前置条件失败：WrongBookGUI.render 目前已有主线程查库（"
                            + findingsForMethod(original, relKey, "render")
                            + "）—— 本用例的「注入后才红」失去归因力");

            String mutated = injectAtMethodBodyStart(original, "render", injected);
            Path mutatedFile = tmp.resolve(rel);
            Files.createDirectories(mutatedFile.getParent());
            Files.writeString(mutatedFile, mutated, StandardCharsets.UTF_8);

            List<Finding> mine = new ArrayList<>();
            for (Finding f : scan(tmp, Policy.defaults())) {
                if (f.path().equals(relKey) && f.member().equals("getWrongBookEntries")) {
                    mine.add(f);
                }
            }
            assertFalse(mine.isEmpty(),
                    "注入 render() 的查库调用没有被 lint 抓住喵 —— 本 lint 的全部价值就是这一条。"
                            + "扫描根：" + tmp.toAbsolutePath());

            int expectedLine = lineOf(mutated, mutated.indexOf(injected));
            assertEquals(expectedLine, mine.get(0).line(),
                    "报出的行号不是注入的那一处喵（期望 " + expectedLine + "，实际 "
                            + mine.get(0).line() + "）—— 行号错位会让修的人找不到地方，"
                            + "lint 的报错等于没报。全部命中：" + mine);
            assertEquals("render", mine.get(0).method(),
                    "报出的方法名不是 render 喵 —— 方法归属算错，修的人会被指向别的方法");
        }

        @Test
        @DisplayName("对照：同一份副本但不注入 → 不得凭空报出 render")
        void unmutatedCopyReportsNothingNew(@TempDir Path tmp) throws IOException {
            Path rel = Path.of("com/letslearnenglish/minecraftplugin/gui/WrongBookGUI.java");
            Path copy = tmp.resolve(rel);
            Files.createDirectories(copy.getParent());
            Files.copy(mainJavaRoot().resolve(rel), copy);

            for (Finding f : scan(tmp, Policy.defaults())) {
                assertFalse(f.path().equals("gui/WrongBookGUI.java") && f.method().equals("render"),
                        "未注入的 render() 却被报出查库喵 —— 注入用例的变红无法归因到注入：" + f);
            }
        }

        /**
         * <b>R31：{@code command/} 里的注入必须被抓住。</b>
         *
         * <p>与上一条同构，但目标是命令层 —— 这正是 R31 补上盲区前<b>不可能通过</b>的用例：
         * 旧 {@code SCAN_DIRS} 不含 {@code command/}，所以往 {@code renderChallenge}
         * 里塞一次查库会静默零命中。
         *
         * <p>变异指向：把 {@code command} 从 {@link #SCAN_DIRS} 里删掉 → 本条红
         * （{@code mine.isEmpty()} 成立）。
         */
        @Test
        @DisplayName("R31：把 getTodayChallenges 注入 command/LearnCommand.renderChallenge → 必须报出")
        void injectedCommandViolationIsCaught(@TempDir Path tmp) throws IOException {
            Path rel = Path.of("com/letslearnenglish/minecraftplugin/command/LearnCommand.java");
            String original = Files.readString(mainJavaRoot().resolve(rel), StandardCharsets.UTF_8);
            // 注入用的正是 /le challenge 被修掉的那次调用 —— 语义上就是回归。
            String injected = "plugin.getDailyChallengeManager().getTodayChallenges(uuid);";
            String relKey = "command/LearnCommand.java";

            // 前置对照：renderChallenge 当前必须干净，否则「注入后才红」无法归因。
            assertEquals(0, findingsForMethod(original, relKey, "renderChallenge").size(),
                    "前置条件失败：renderChallenge 目前已有主线程查库（"
                            + findingsForMethod(original, relKey, "renderChallenge")
                            + "）—— 本用例的「注入后才红」失去归因力");

            String mutated = injectAtMethodBodyStart(original, "renderChallenge", injected);
            Path mutatedFile = tmp.resolve(rel);
            Files.createDirectories(mutatedFile.getParent());
            Files.writeString(mutatedFile, mutated, StandardCharsets.UTF_8);

            List<Finding> mine = new ArrayList<>();
            for (Finding f : scan(tmp, Policy.defaults())) {
                if (f.path().equals(relKey) && f.member().equals("getTodayChallenges")) {
                    mine.add(f);
                }
            }
            assertFalse(mine.isEmpty(),
                    "注入 command/LearnCommand.renderChallenge 的查库没有被抓住喵 —— "
                            + "说明 command/ 虽然写进了 SCAN_DIRS 但实际没被扫描（目录名/遍历失效）。"
                            + "这正是 R31 之前 /le challenge 能长期漏网的那个洞。");

            int expectedLine = lineOf(mutated, mutated.indexOf(injected));
            assertEquals(expectedLine, mine.get(0).line(),
                    "报出的行号不是注入的那一处喵（期望 " + expectedLine + "，实际 "
                            + mine.get(0).line() + "）。全部命中：" + mine);
            assertEquals("renderChallenge", mine.get(0).method(),
                    "报出的方法名不是 renderChallenge 喵 —— 命令层的方法归属算错了");
        }
    }

    // ==================================================================
    // 分析器
    // ==================================================================

    /** 一次命中：{@code path:line  method → member(}。 */
    record Finding(String path, int line, String method, String member) {
        /** 基线键：{@code <path>#<method>@<member>}。 */
        String baselineKey() {
            return path + "#" + method + "@" + member;
        }

        @Override
        public String toString() {
            return path + ":" + line + "  " + method + " → " + member + "(";
        }
    }

    /**
     * 判据的可变部分 —— 每个开关对应一种「实现被削弱」的失效形态，供变异用例驱动。
     *
     * @param asyncOnlyMethods  异步专用方法白名单（方法体免检）
     * @param honorAsyncSuffix  方法名以 {@code Async} 结尾即整方法豁免
     * @param stripComments     剥行注释与块注释（含 Javadoc）
     * @param stripLiterals     剥字符串/字符字面量内容
     * @param dottedOnly        只认 {@code .name(}（R20-GUI2 实测过的真缺口）
     * @param flipNestedMain    把嵌在异步段里的 runTask/runOnMain 实参算回主线程
     */
    record Policy(Set<String> asyncOnlyMethods, boolean honorAsyncSuffix,
                  boolean stripComments, boolean stripLiterals, boolean dottedOnly,
                  boolean flipNestedMain) {

        static Policy defaults() {
            return new Policy(ASYNC_ONLY_METHODS, true, true, true, false, true);
        }

        Policy withoutAsyncSuffix() {
            return new Policy(asyncOnlyMethods, false, stripComments, stripLiterals, dottedOnly,
                    flipNestedMain);
        }

        Policy withoutAsyncOnlyBodies() {
            return new Policy(Set.of(), honorAsyncSuffix, stripComments, stripLiterals, dottedOnly,
                    flipNestedMain);
        }

        Policy withoutCommentStripping() {
            return new Policy(asyncOnlyMethods, honorAsyncSuffix, false, stripLiterals, dottedOnly,
                    flipNestedMain);
        }

        Policy withoutLiteralStripping() {
            return new Policy(asyncOnlyMethods, honorAsyncSuffix, stripComments, false, dottedOnly,
                    flipNestedMain);
        }

        /**
         * 变体：只认 {@code .name(}（模拟「只认带点调用」的实现）。
         *
         * <p>方法名<b>不能</b>叫 {@code dottedOnly()} —— 那会与 record 组件
         * {@code boolean dottedOnly} 生成的访问器签名冲突（同类返回值不同也算重复定义），
         * 编译直接失败。故用带语义的名字。
         */
        Policy withDottedReceiverOnly() {
            return new Policy(asyncOnlyMethods, honorAsyncSuffix, stripComments, stripLiterals, true,
                    flipNestedMain);
        }

        Policy withoutSyncDispatch() {
            return new Policy(asyncOnlyMethods, honorAsyncSuffix, stripComments, stripLiterals,
                    dottedOnly, false);
        }
    }

    private static final String PKG = "com/letslearnenglish/minecraftplugin/";

    /**
     * 扫描范围：{@code gui/}、{@code listener/}、{@code command/}。
     *
     * <p><b>R31 为什么必须含 {@code command/}</b>：命令执行器（{@code CommandExecutor#onCommand}）
     * 与 {@code @EventHandler}、GUI 打开路径一样由主线程调用，是<b>同一条</b>卡死通道。
     * 漏掉它的代价实测过 —— {@code /le challenge} 在主线程同步查库 4 次却零命中，
     * 因为整目录不在列表里（{@code LearnCommandParsingTest} 的 {@code @Disabled} 用例
     * 就是照着这个洞写的）。R31 把它加进来并修掉了暴露出的违规。
     *
     * <p>扩展范围时<b>只需担心一类副作用</b>：新目录里"本来就在异步段"的代码会被误报。
     * 已核 {@code command/} 三个文件（{@code LearnCommand} / {@code AdminCommand} /
     * {@code DiagnoseCommand}）的异步形状，除下面基线登记的两处外都是干净的。
     */
    private static final List<String> SCAN_DIRS =
            List.of(PKG + "gui", PKG + "listener", PKG + "command");

    private static Path mainJavaRoot() {
        Path p = Path.of("src/main/java");
        assertTrue(Files.isDirectory(p),
                "找不到 src/main/java 喵 —— 本 lint 读的是源码，工作目录必须是项目根。实际："
                        + p.toAbsolutePath());
        return p;
    }

    /** 扫描 {@link #SCAN_DIRS} 下全部 {@code .java}，返回主线程查库命中。 */
    static List<Finding> scan(Path javaRoot, Policy policy) throws IOException {
        List<Finding> out = new ArrayList<>();
        for (String dir : SCAN_DIRS) {
            Path start = javaRoot.resolve(dir);
            if (!Files.isDirectory(start)) {
                continue;
            }
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(start)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(files::add);
            }
            files.sort(null);
            for (Path file : files) {
                // 报告里的路径相对<b>包根</b>（gui/…、listener/…）—— 基线键就是这么写的，
                // 保持同源才不会出现「登记了却永远匹配不上」的静默失效。
                String rel = javaRoot.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith(PKG)) {
                    rel = rel.substring(PKG.length());
                }
                out.addAll(scanSource(Files.readString(file, StandardCharsets.UTF_8), rel, policy));
            }
        }
        return out;
    }

    /** 扫一段源码文本（{@code rel} 只用于报错信息）。 */
    static List<Finding> scanSource(String src, String rel, Policy policy) {
        String san = sanitize(src, policy);
        List<MethodDecl> decls = parseMethods(san);

        // 掩码在<b>整个文件</b>上算，而不是逐方法体算 —— 这是关键的正确性要求：
        // EnglishSquareGUI 在 runTaskAsynchronously 的实参里 new 了一个匿名 PageFetch，
        // 它的 fetch(int) 是<b>独立的方法声明</b>。若逐方法体从 ctxMain=true 起算，
        // fetch() 里的 getActiveSquareEntries 会被判成主线程查库（假阳性），
        // 尽管它明明在异步 lambda 里。全文件掩码天然带着外层上下文。
        boolean[] main = mainThreadMask(san, policy);

        // 方法<b>声明</b>处的名字（`private WrongBookLoad loadWrongBook(UUID id) {`）
        // 在文本上和调用完全同形。不排除它们，`loadWrongBook` / `loadSquareData` /
        // `prefetchWallData` / `fetchSquarePage` 的声明行会被全部报成违规 —— 稳定的
        // 假阳性，足以让人删掉整条 lint。声明由 parseMethods 精确定位，逐个跳过。
        Set<Integer> declarationStarts = new LinkedHashSet<>();
        for (MethodDecl d : decls) {
            declarationStarts.add(d.nameStart());
        }

        List<Finding> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Set<String> names = new LinkedHashSet<>(DB_READ_METHODS);
        // 异步专用方法名<b>恒在</b>禁止名单里（与 policy 无关）——
        // 「这些名字不许从主线程调」是一条固定规则；policy 只控制「方法体是否免检」。
        // 两者若共用同一个集合，M1 变异（清空豁免）会把调用点检测也一并关掉，
        // 于是 {@link MainLint#asyncOnlyHelpersAreNotAnEscapeHatch()} 就永远测不出东西。
        names.addAll(ASYNC_ONLY_METHODS);

        for (String name : names) {
            for (int idx : matchName(san, name, policy.dottedOnly())) {
                if (declarationStarts.contains(idx)) {
                    continue;
                }
                if (idx >= 0 && idx < main.length && main[idx]) {
                    addFinding(out, seen, san, rel, idx, name, decls, policy);
                }
            }
        }

        // 兜底接收者规则：getDatabaseManager().任意方法( —— 覆盖将来新加的 DatabaseManager 方法
        Matcher dm = RECEIVER_DB.matcher(san);
        while (dm.find()) {
            String member = dm.group(1);
            if (DB_NON_IO_METHODS.contains(member)) {
                continue;
            }
            int idx = dm.start(1);
            if (idx < main.length && main[idx]) {
                addFinding(out, seen, san, rel, idx, member, decls, policy);
            }
        }
        return out;
    }

    private static void addFinding(List<Finding> out, Set<String> seen, String san, String rel,
                                   int idx, String member, List<MethodDecl> decls, Policy policy) {
        String owner = enclosingMethod(decls, idx);
        if (isExempt(owner, policy)) {
            return;
        }
        int line = lineOf(san, idx);
        if (seen.add(member + "@" + line)) {
            out.add(new Finding(rel, line, owner, member));
        }
    }

    /** 方法级豁免：异步入口（{@code *Async}）与「只允许异步调用」的取数方法。 */
    static boolean isExempt(String methodName, Policy policy) {
        if (policy.honorAsyncSuffix() && methodName.endsWith("Async")) {
            return true;
        }
        return policy.asyncOnlyMethods().contains(methodName);
    }

    /**
     * 包含 {@code offset} 的<b>最内层</b>方法声明名。
     *
     * <p>找不到时返回 {@link #CLASS_INIT}（字段初始化 / 静态块 / 类体）而不是静默跳过 ——
     * 「不在任何方法里」并不意味着「不在主线程」：构造函数与字段初始化同样由主线程执行。
     * 静默跳过会留下一个看不见的洞（把查库搬进字段初始化就能绕过本 lint）。
     * 当前 {@code gui/} 与 {@code listener/} 里零例（已核），故这个哨兵永远不会出现在基线里。
     */
    static String enclosingMethod(List<MethodDecl> decls, int offset) {
        String best = CLASS_INIT;
        int bestLen = Integer.MAX_VALUE;
        for (MethodDecl d : decls) {
            int start = d.bodyStart();
            int end = start + d.body().length();
            if (offset >= start && offset < end && (end - start) < bestLen) {
                best = d.name();
                bestLen = end - start;
            }
        }
        return best;
    }

    /** 「不在任何方法声明内」的归属哨兵（字段初始化 / 静态块）。 */
    static final String CLASS_INIT = "<class-init>";

    /** 单文件单方法的命中（供注入用例做前后对照）。 */
    static List<Finding> findingsForMethod(String src, String rel, String methodName) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : scanSource(src, rel, Policy.defaults())) {
            if (f.method().equals(methodName)) {
                out.add(f);
            }
        }
        return out;
    }

    /** {@code getDatabaseManager().member(} —— 容忍跨行/空白的点号链。 */
    private static final Pattern RECEIVER_DB =
            Pattern.compile("getDatabaseManager\\s*\\(\\s*\\)\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\(");

    /** 在 {@code body} 里找出所有对 {@code name} 的调用（带点 / 裸调用两种形态），返回标识符起始下标。 */
    static List<Integer> matchName(String body, String name, boolean dottedOnly) {
        String q = Pattern.quote(name);
        Set<Integer> hits = new TreeSet<>();
        Matcher dotted = Pattern.compile("\\.\\s*" + q + "\\s*\\(").matcher(body);
        while (dotted.find()) {
            int idx = dotted.start() + dotted.group().indexOf(name);
            hits.add(idx);
        }
        if (!dottedOnly) {
            // 裸调用：前面不能是标识符字符（排除 reLoadWrongBook / loadWrongBookSafe 这类
            // 更长标识符的尾部），也不能是 `::`（方法引用不在此刻执行）。
            Matcher bare = Pattern.compile("(?<![\\w$:])" + q + "\\s*\\(").matcher(body);
            while (bare.find()) {
                hits.add(bare.start());
            }
        }
        return new ArrayList<>(hits);
    }

    /**
     * 把方法体按「主线程会执行 / 不会执行」打标。
     *
     * <p>{@code true} = 主线程。规则：
     * <ul>
     *   <li>{@code runTaskAsynchronously} 家族的实参 → false（异步）；</li>
     *   <li>而<b>嵌在异步段里</b>的 {@code runTask}/{@code runOnMain} 家族实参 → true（切回主线程）；</li>
     *   <li>递归，因此「异步 → 主线程 → 异步」多层嵌套也能正确翻转。</li>
     * </ul>
     */
    static boolean[] mainThreadMask(String body, Policy policy) {
        boolean[] mask = new boolean[body.length()];
        classify(mask, body, 0, body.length(), true, policy);
        return mask;
    }

    private static void classify(boolean[] mask, String s, int from, int to, boolean ctxMain,
                                 Policy policy) {
        int segStart = from;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            boolean identStart = Character.isJavaIdentifierStart(c)
                    && (i == 0 || !isIdentChar(s.charAt(i - 1)));
            if (identStart) {
                int idEnd = i;
                while (idEnd < to && isIdentChar(s.charAt(idEnd))) {
                    idEnd++;
                }
                String name = s.substring(i, idEnd);
                boolean async = ASYNC_DISPATCH.contains(name);
                boolean sync = policy.flipNestedMain() && SYNC_DISPATCH.contains(name);
                if ((ctxMain && async) || (!ctxMain && sync)) {
                    int open = idEnd;
                    while (open < s.length() && Character.isWhitespace(s.charAt(open))) {
                        open++;
                    }
                    if (open < s.length() && s.charAt(open) == '(') {
                        int close = matchingParen(s, open);
                        if (close > open) {
                            for (int k = segStart; k <= close && k < mask.length; k++) {
                                mask[k] = ctxMain;
                            }
                            classify(mask, s, open + 1, close, !ctxMain, policy);
                            i = close + 1;
                            segStart = i;
                            continue;
                        }
                    }
                }
                i = idEnd;
                continue;
            }
            i++;
        }
        for (int k = segStart; k < to && k < mask.length; k++) {
            mask[k] = ctxMain;
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isJavaIdentifierPart(c) || c == '$';
    }

    /** 从 {@code leftParen} 起做圆括号配对（入参已剥字符串，故只需数括号）。 */
    private static int matchingParen(String s, int leftParen) {
        int depth = 0;
        for (int i = leftParen; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ---- 方法解析 ----------------------------------------------------

    /**
     * 一个方法声明。
     *
     * @param name       方法名（构造器为 {@code <init>:ClassName}）
     * @param body       含首尾花括号的方法体，是整文件的一个子串
     * @param bodyStart  {@code body} 在整文件里的起始偏移
     * @param nameStart  方法名标识符在整文件里的起始偏移（用于跳过「声明即同形调用」）
     */
    record MethodDecl(String name, String body, int bodyStart, int nameStart) {
    }

    /**
     * 方法签名（含注解/修饰符/泛型/返回类型/参数/throws）。
     *
     * <p>返回类型要容忍 {@code List<Map.Entry<String, DifficultWord>>} 这类嵌套泛型 ——
     * 只用 {@code <[^<>]*>} 会在第一层 {@code >} 处截断，导致整个方法解析不出来、
     * 方法体被静默跳过（漏报），故用 {@code <[^;{}()]*>} 允许嵌套尖括号。
     */
    private static final Pattern METHOD_SIG = Pattern.compile(
            "(?s)^(?:@[\\w$.]+(?:\\([^)]*\\))?\\s*)*"
                    + "(?:(?:public|protected|private|static|final|abstract|synchronized"
                    + "|native|default|strictfp)\\s+)*"
                    + "(?:<[^;{}()]*>\\s*)?"
                    + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;{}()]*>)?(?:\\s*\\[\\s*\\])*)\\s+"   // 返回类型
                    + "([A-Za-z_$][\\w$]*)\\s*"                                      // 方法名
                    + "\\(([^()]*)\\)\\s*"
                    + "(?:throws\\s+[\\w$.,\\s]+)?$");

    private static final Pattern CTOR_SIG = Pattern.compile(
            "(?s)^(?:@[\\w$.]+(?:\\([^)]*\\))?\\s*)*"
                    + "(?:(?:public|protected|private)\\s+)+"
                    + "([A-Z][\\w$]*)\\s*\\(([^()]*)\\)\\s*$");

    private static final List<String> CONTROL_KEYWORDS = List.of(
            "if", "for", "while", "switch", "catch", "try", "do", "else", "return", "new",
            "synchronized", "throw", "case", "default", "assert", "yield");

    /**
     * 扫出所有方法/构造器声明。
     *
     * <p>做法：对每个 {@code &#123;} 先做花括号配对拿到候选体，再回看它前面那段
     * （到上一个 {@code ;} / {@code &#123;} / {@code &#125;} 为止）是不是签名。
     * 这样天然跳过 {@code if/for/lambda/匿名类/数组初始化/record 体} 的花括号 ——
     * 它们的"前缀"不像签名（含 {@code ->}、{@code =}、{@code new}、控制关键字）。
     */
    static List<MethodDecl> parseMethods(String san) {
        int[] close = bracePairs(san);
        List<MethodDecl> out = new ArrayList<>();
        for (int i = 0; i < san.length(); i++) {
            if (san.charAt(i) != '{' || close[i] < 0) {
                continue;
            }
            int end = close[i];
            // 回看签名：到上一个语句/块边界为止。分支提前 break，所以这里的窗口大小
            // 只在「往回 1500 字符内都没有边界」这种（不存在的）情况下才生效。
            int from = i - 1;
            int floor = Math.max(0, i - 1500);
            while (from >= floor) {
                char c = san.charAt(from);
                if (c == ';' || c == '{' || c == '}') {
                    break;
                }
                from--;
            }
            int prefixStart = from + 1;
            String prefix = san.substring(prefixStart, i).trim();
            if (prefix.isEmpty()) {
                continue;
            }
            Matcher m = METHOD_SIG.matcher(prefix);
            Matcher c = CTOR_SIG.matcher(prefix);
            String name;
            if (m.matches()) {
                name = m.group(2);
            } else if (c.matches()) {
                name = "<init>:" + c.group(1);
            } else {
                continue;
            }
            // 正则<b>先</b>匹配、启发式只做兜底 —— 顺序很关键：`synchronized void foo()`
            // 既是合法方法又以同步关键字开头，先跑关键字过滤会把这种合法方法整条漏掉
            // （方法体不被扫描 = 看不见的洞）。正则要求「类型 + 名字 + 括号」。
            if (looksLikeNonSignature(prefix, name)) {
                continue;
            }
            // nameStart 要换算回整文件偏移：prefix 被 trim() 过，先找回它的真实起点。
            int lead = 0;
            while (prefixStart + lead < i && Character.isWhitespace(san.charAt(prefixStart + lead))) {
                lead++;
            }
            int nameStart = m.matches()
                    ? prefixStart + lead + m.start(2)
                    : prefixStart + lead + c.start(1);
            out.add(new MethodDecl(name, san.substring(i, end + 1), i, nameStart));
        }
        return out;
    }

    /**
     * 一次扫描算出每对花括号的配对关系（{@code pair[i]} = 与 {@code san[i]} 配对的
     * 那个下标；{@code i} 不是花括号或未配对时为 {@code -1}）。
     *
     * <p>为什么不用「对每个 {@code &#123;} 各扫一遍」：那是对每个花括号 O(n)，
     * 单个 1500 行文件就是几十万次字符比较，而本类的扫描会被十几个用例各跑一遍 ×
     * 40 个文件 —— 会慢到让人受不了、最后被 @Disabled 掉。一次栈扫描是 O(n)。
     */
    private static int[] bracePairs(String s) {
        int[] pair = new int[s.length()];
        Arrays.fill(pair, -1);
        int[] stack = new int[64];
        int top = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                if (top == stack.length) {
                    stack = Arrays.copyOf(stack, top * 2);
                }
                stack[top++] = i;
            } else if (c == '}' && top > 0) {
                int open = stack[--top];
                pair[open] = i;
                pair[i] = open;
            }
        }
        return pair;
    }

    /**
     * 正则匹配之后的<b>兜底排除</b>：明显不是方法声明的形态。
     *
     * <p>正则 {@link #METHOD_SIG} 要求「类型 + 名字 + 参数表」，本来就匹配不上
     * {@code if (x)} / {@code for (...)} / {@code switch (x)}。这里只排除两种
     * <b>能骗过正则</b>的形态：lambda / 赋值 / 匿名类。
     *
     * <p><b>注解必须先摘掉再判</b>：{@code @EventHandler(priority = EventPriority.LOW)} 里
     * 含有 {@code =}，不摘就会把 {@code ChatAnswerListener}/{@code ChatScenarioListener}
     * 这类带参数的 {@code @EventHandler} 方法整条漏掉 —— 而它们正是本 lint 最重要的
     * 目标（事件处理器保证在主线程）。漏掉后方法体归给 {@link #CLASS_INIT}，
     * 基线键全部错位。
     *
     * <p><b>不</b>按控制关键字整体排除前缀：{@code synchronized void foo()} 既是合法方法
     * 又以关键字开头，按关键字过滤会把这种合法方法整条漏掉（= 看不见的洞）。
     *
     * @param prefix 已 trim 的签名候选（可能带注解）
     * @param name   正则认出来的方法名（用于排除「名字恰好是控制关键字」的伪签名）
     */
    private static boolean looksLikeNonSignature(String prefix, String name) {
        String bare = ANNOTATION.matcher(prefix).replaceAll(" ").trim();
        if (bare.isEmpty()) {
            return true;
        }
        if (bare.contains("->") || bare.contains("=")) {
            return true; // lambda / 赋值 / 数组初始化
        }
        if (bare.startsWith("new ") || bare.contains(" new ")) {
            return true; // 匿名类
        }
        // `} else if (x) {` 的前缀会解析成「类型 else + 名字 if + 参数 (x)」——
        // 名字就是关键字本身，这种要排除。
        return CONTROL_KEYWORDS.contains(name);
    }

    /** 注解（含带参数的形式），从签名候选里摘掉后再做启发式判断。 */
    private static final Pattern ANNOTATION =
            Pattern.compile("@[\\w$.]+(?:\\s*\\([^)]*\\))?");

    // ---- 预处理 ------------------------------------------------------

    /**
     * 把注释与字符串字面量<b>原地替换成空格</b>（换行保留）。
     *
     * <p>字符数不变是硬要求：命中位置要换算成 {@code 文件:行号}，任何长度变化都会让
     * 报出来的行号错位 —— 行号错位等于没报。
     */
    static String sanitize(String src, Policy policy) {
        char[] out = src.toCharArray();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (policy.stripComments() && c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out[i++] = ' ';
                }
            } else if (policy.stripComments() && c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                // 块注释：把 /* 到 */ 之间（含两端）全换成空格，换行原样保留。
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                while (i < n) {
                    if (src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/') {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i += 2;
                        break;
                    }
                    if (src.charAt(i) != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
            } else if (policy.stripLiterals() && (c == '"' || c == '\'')) {
                char quote = c;
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        if (src.charAt(i) != '\n') {
                            out[i] = ' ';
                        }
                        i++;
                        if (i < n && src.charAt(i) != '\n') {
                            out[i] = ' ';
                        }
                        i++;
                        continue;
                    }
                    if (d == quote) {
                        i++;
                        break;
                    }
                    if (d != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    // ---- 工具 --------------------------------------------------------

    /** {@code text} 里第 {@code offset} 个字符所在行号（1 起）。 */
    static int lineOf(String text, int offset) {
        int line = 1;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 在 {@code src} 的 {@code methodName} 方法体开头插入一行代码（返回新源码）。 */
    static String injectAtMethodBodyStart(String src, String methodName, String code) {
        String san = sanitize(src, Policy.defaults());
        for (MethodDecl m : parseMethods(san)) {
            if (m.name().equals(methodName)) {
                int at = m.bodyStart() + 1;
                return src.substring(0, at) + "\n            " + code + "\n" + src.substring(at);
            }
        }
        throw new AssertionError("找不到方法 " + methodName + " 喵 —— 注入用例的锚点失效");
    }

    /** 纯查询版 helper（供自检用，不抛断言）：返回命中列表。 */
    static List<Finding> detectAll(String src, Policy policy) {
        return scanSource(src, "snippet.java", policy);
    }

    private static String report(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            sb.append("  ").append(f).append('\n');
        }
        return sb.toString();
    }
}
