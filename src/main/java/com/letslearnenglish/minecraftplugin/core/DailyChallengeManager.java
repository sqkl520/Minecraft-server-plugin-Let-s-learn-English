package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.repository.DailyChallengeRepository;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Daily Challenge Manager
 *
 * Two challenge systems:
 * 1. Behavioral challenges (ChallengeType enum) — task-based, e.g. "answer 10 words"
 * 2. Word challenges — N random words selected from WordBank each day
 *
 * Progress is tracked in the database and checked after relevant actions.
 *
 * <p><strong>R7-D（P1-2）：多服共享 MySQL 的单词挑战口径</strong>——完成判定以 DB 为
 * 真源：本进程缓存（{@link #dailyWordStateCache}）只加速单服高频路径；每有一个挑战词
 * 真正在本进程完成落库，都用完成度三态（{@code loadWordChallengeCompletion}，取
 * {@code COMPLETED} 为确认）复核一次 DB，
 * 跨服分段完成（A 服做部分词、B 服做其余）照样触发发奖，不会出现"DB 已 5/5 但本服
 * 不发奖"。发奖台账（{@code daily_word_reward_claims}）本身跨服共享，同日不会双发。</p>
 */
public class DailyChallengeManager {

    /**
     * Behavioral challenge types.
     *
     * <p>PF-MC-09（Phase 3 · MC-5）：{@link #reward} 接线后降级为<strong>默认值容器</strong> ——
     * 实际发奖与展示一律走 {@link DailyChallengeManager#getChallengeReward}（读
     * {@code daily-challenges.rewards.<id>}，缺键/非法/≤0 回落本字段值）。
     * 调用点不要再直读 {@code type.reward}。</p>
     */
    public enum ChallengeType {
        ANSWER_10("answer_10", "daily.p1", 10, 30.0),
        SESSION_3("session_3", "daily.p2", 3, 50.0),
        // R-55o：target 1→5（"达到 5 连击"语义对）。该值仅用于聊天/GUI 显示的分母；
        // 触发路径走 completeChallenge 二进制完成（见 WordLearningManager: consecutiveCorrect>=5），不受此值影响。
        COMBO_5("combo_5", "daily.p3", 5, 25.0),
        AI_CHAT_5("ai_chat_5", "daily.p4", 5, 40.0);

        public final String id;
        public final String descKey;
        public final int target;

        /**
         * 默认奖励金币（默认值容器，非权威值 —— 见枚举 javadoc 与
         * {@link DailyChallengeManager#getChallengeReward}）。
         */
        public final double reward;

        ChallengeType(String id, String descKey, int target, double reward) {
            this.id = id;
            this.descKey = descKey;
            this.target = target;
            this.reward = reward;
        }

        public static ChallengeType fromId(String id) {
            for (ChallengeType ct : values()) {
                if (ct.id.equals(id)) return ct;
            }
            return null;
        }
    }

    public static class ChallengeProgress {
        public final ChallengeType type;
        public int progress;
        public boolean completed;
        public boolean claimed;

        public ChallengeProgress(ChallengeType type, int progress, boolean completed, boolean claimed) {
            this.type = type;
            this.progress = progress;
            this.completed = completed;
            this.claimed = claimed;
        }

        public boolean isAllDone() { return completed && claimed; }
    }

    /**
     * Word challenge progress info.
     */
    public static class WordChallengeProgress {
        public final String wordId;
        public final boolean completed;

        public WordChallengeProgress(String wordId, boolean completed) {
            this.wordId = wordId;
            this.completed = completed;
        }
    }

    private final LetsLearnEnglish plugin;
    private final WordManagerImpl wordManager;
    private final DailyChallengeRepository repository;

    /**
     * "已付"内存快路径：玩家 → 已确认发过奖的挑战日集合（{@code yyyy-MM-dd}）。
     *
     * <p><b>L18 修复</b>：原实现是无日期维度的 {@code Set<UUID>}。而
     * {@link #hasPendingWordRewardForDate(UUID, String)} 的入参 {@code date} 是"要结算的
     * 那一天"（rollover 传昨日），第一道门却只问"这个玩家今天有没有付过"——
     * rollover 延迟（服务器卡顿 / 定时任务被推迟到下一个 tick 周期之后）时，玩家在
     * rollover 真正执行前已经领了<b>今天</b>的奖励，{@code contains} 命中 → 判定<b>昨日</b>
     * "已付" → 昨日的 pending 奖励被误判成已发，实际从未发放且不会再补发（永久丢奖）。</p>
     *
     * <p>故改为按日期记录：查询"某日是否已付"必须命中<b>该日</b>，别的日期的标记不再
     * 干扰判定。为什么是"玩家 → 日期集合"而不是"玩家 → 单个日期"：rollover 补发写的是
     * 昨日，而玩家可能已经在同一进程内领过今日 —— 单个日期字段会被覆盖，进而让
     * {@link #isRewardSettled} 误判"今日未付"、重复发钱（DB 台账 INSERT 幂等，但 Vault
     * 转账不幂等）。集合同时容纳两三天，正好覆盖这个窗口。</p>
     *
     * <p>内存有界：每个玩家每天最多新增 1 个日期，午夜 rollover 由
     * {@link #clearAllWordChallengeClaims()} 全量清空；两个日期并存是上限级常量。
     * 线程安全：外层 map 与内层集合均为并发实现（与改造前同一线程纪律，读写点分布在
     * 主线程/异步 rollover 段）。</p>
     */
    private final Map<UUID, Set<String>> wordChallengeClaimedDates;

    /**
     * R7-B ③：per-player 单词挑战当日缓存（答对路径去重查询）。
     *
     * <p>答对一题原本要发 4 条 SELECT（台账 COUNT、挑战词列表、完成度 COUNT/SUM，外加
     * 完成后的再次 COUNT）。本缓存把"当日不变的部分"（挑战词集合、已完成集合、台账位）
     * 一次加载后复用，答对路径常态是：1 条台账 SELECT（{@code rewardPaid} 为 false 时，
     * 见 {@code hasPendingWordReward}——这条本来就省不掉）；命中"真正新完成的挑战词"
     * 再 +1 条 UPDATE 与 +1 条完成度复核（R7-D P1-2 的跨服真源，见
     * {@code dbSaysAllWordsCompleted}）。非挑战词 / 已完成的重复词不再有挑战词列表与
     * 完成度查询。</p>
     *
     * <p><strong>一致性纪律（破坏即出 bug）</strong>：</p>
     * <ul>
     *   <li>{@code completedWords} 只在 DB 把这些词标记为完成之后才写入（先写库、后改缓存）；</li>
     *   <li>台账位（{@code rewardPaid}）只在 {@code markDailyWordRewardClaimed} 调用之后置 true；</li>
     *   <li>任何"发钱/写台账"的判定仍然以 DB 台账为真源 —— 缓存只向下游提供"已付"的
     *       快路径，绝不把"未付"当成"已付"（{@code rewardPaid} 为 false 时仍回查 DB）；</li>
     *   <li>失效：跨天（{@code date != today}）自动重建；玩家 quit 由
     *       {@link #unloadPlayer(UUID)} 清除；{@code /lea reset} 由
     *       {@link #resetPlayer(UUID)} 清除（同时清"已付"内存位，见其 javadoc）；
     *       午夜 rollover 由 {@link #clearAllWordChallengeClaims()} 一并清空。</li>
     * </ul>
     */
    private final Map<UUID, DailyWordChallengeState> dailyWordStateCache = new ConcurrentHashMap<>();

    /**
     * R11：<b>台账写失败的重试登记</b>（player → 待补写的挑战日集合）。
     *
     * <p><b>为什么需要它</b>：{@code markDailyWordRewardClaimed} 现在是
     * {@code boolean}，失败方向是「<b>钱已发出、台账没写进去</b>」。此时若什么都不做，
     * 该玩家当天"已付"只存在于内存，重启后 DB 台账查不到、挑战词在 DB 里仍是"全完成"
     * → 同一天<b>再发一次</b>（多给），是真实的经济损失。而"多发一次"比"玩家白干"
     * 轻，故<b>绝不回滚已发出的钱</b>（见 {@link #persistWordRewardClaim}）。
     *
     * <p>能做的补偿只有两条：① 一条 SEVERE 日志（唯一跨重启的现场）；② 本登记表 ——
     * 只要数据库在重启前恢复，玩家下一次答对（任意词）时补写即闭合窗口。补写是
     * {@code INSERT OR IGNORE} 幂等语句，重复写只影响 0 行，零副作用。
     *
     * <p><b>为什么不写一个持久化的"对账标记"</b>：持久化的地方就是那张写不进去的表 ——
     * 写失败时无处可写。除此之外唯一的跨界载体只有服务端日志。
     *
     * <p>有界性：每人每天最多 1 个日期（集合去重），午夜 rollover 由
     * {@link #clearAllWordChallengeClaims()} 全清，{@code /lea reset} 由
     * {@link #resetPlayer(UUID)} 清除（<b>必须清</b>：reset 已删台账行，再补写会把
     * 刚删掉的"已付"重新插回去，让该玩家当天领不到奖）。与
     * {@link #wordChallengeClaimedDates} 同一线程纪律。
     */
    private final Map<UUID, Set<String>> pendingClaimLedgerWrites = new ConcurrentHashMap<>();

    /**
     * R7-B ③：单词挑战的 per-player 当日缓存体。
     *
     * <p>可变字段只有 {@code completedWords} / {@code rewardPaid} /
     * {@code completedConfirmedByDb}，且写入全部发生在 {@link #onWordCorrect} 与发奖路径上
     * （主线程 / stripe lock 内，与既有 {@code wordChallengeClaimedDates} 同一线程纪律）；
     * 读取侧（{@link #getTodayWordDetails} 等 GUI 路径）只做遍历与包含判断。集合本身用
     * 并发实现，避免 GUI 线程遍历时与答题线程写入相互踩踏。</p>
     */
    private static final class DailyWordChallengeState {
        /** 缓存归属的挑战日（yyyy-MM-dd）；与今日不符即整体失效重建。 */
        final String date;
        /** 今日分配的全部挑战词 id（缓存期内不再回查 DB）。 */
        final Set<String> challengeWords;
        /** 已确认落库完成的词 id（写库成功后才加入）。 */
        final Set<String> completedWords;
        /** 当日奖励台账是否已确认写过（true 才可跳过一次 DB 回查）。 */
        volatile boolean rewardPaid;
        /**
         * R7-D（P1-2）：DB 真源已确认"今日全部完成"，但本进程缓存只见过其中一部分
         * （典型：多服共享 MySQL，其余词在别的子服完成）。置位后 {@link #allCompleted()}
         * 恒真：① 本次立即发奖；② 若发奖失败（Vault 瞬时失败），后续任意答对经
         * {@code hasPendingWordReward} 的重试钩子继续补发 —— 否则旧的
         * {@code completedWords} 永远凑不满，重试钩子再也不会触发。
         */
        volatile boolean completedConfirmedByDb;
        /** DB 里"今日无挑战词行"（未分配）—— 缓存期内避免每题重复回查。 */
        final boolean noWordsAssigned;

        /**
         * R-16（round16 F-D4 #1）：本次加载<b>读失败</b>，三份数据全部未知。
         *
         * <p><b>为什么必须自成一态</b>：读失败时 {@code challengeWords} 必然是空集，
         * 于是 {@link #noWordsAssigned} 也恒为真 —— 「读不到」被伪装成业务事实
         * 「今天没分配」，正是本轮 {@code loadWordChallengeSnapshot} 从两态改三态要
         * 消灭的那个形状。置位后所有"看集合做判定"的路径都必须先短路，不许拿空集合
         * 当作"没分配/没完成"。本态<b>永不落缓存</b>（下一次答对自然会重读）。</p>
         */
        final boolean unreadable;

        DailyWordChallengeState(String date, Collection<String> challengeWords,
                                Collection<String> completedWords, boolean rewardPaid) {
            this(date, challengeWords, completedWords, rewardPaid, false);
        }

        private DailyWordChallengeState(String date, Collection<String> challengeWords,
                                        Collection<String> completedWords, boolean rewardPaid,
                                        boolean unreadable) {
            this.date = date;
            this.challengeWords = ConcurrentHashMap.newKeySet();
            this.challengeWords.addAll(challengeWords);
            this.completedWords = ConcurrentHashMap.newKeySet();
            this.completedWords.addAll(completedWords);
            this.rewardPaid = rewardPaid;
            this.noWordsAssigned = challengeWords.isEmpty();
            this.unreadable = unreadable;
        }

        /**
         * R-16：「读失败、结果未知」的一次性占位态（<b>不得</b>放入
         * {@link #dailyWordStateCache}）。
         *
         * <p>日期仍按本次加载的 {@code date} 填 —— 万一将来有人把它塞进缓存，按日期
         * 失效的机制至少还能把它清掉，不会比现状更糟。</p>
         */
        static DailyWordChallengeState unreadable(String date) {
            return new DailyWordChallengeState(date, List.of(), List.of(), false, true);
        }

        /** 是否"有词且全部完成"（与 DB 侧 {@code total > 0 && total == done} 同义）。 */
        boolean allCompleted() {
            // R-16：读失败时这张表根本没算过 —— 空集合天然算出 false，但那是"碰巧对了"。
            // 显式写出来，避免将来有人把 challengeWords 的初值改成非空、或给 allCompleted()
            // 加"空集即完成"的优化时，把「不知道」读成「完成了」→ 直接多发一次整额奖励。
            if (unreadable) {
                return false;
            }
            return completedConfirmedByDb
                    || (!challengeWords.isEmpty()
                    && completedWords.size() >= challengeWords.size());
        }
    }

    /**
     * R-02: claim stripe locks. Fixed 32-slot striping by player UUID keeps
     * concurrent claims of the same challenge serialized without a growing
     * lock map (nothing to evict, no quit cleanup needed). Constant nesting
     * order with the listener stripe lock (L then M) — no reverse path, so
     * no deadlock.
     */
    private final Object[] claimStripeLocks = new Object[32];

    /**
     * MC-5：challenges-per-day 越界钳位 WARNING 只报一次（进程内单次防刷屏；
     * 每次分配都点读该键，reload 即时生效，仅告警不重复）。
     */
    private static volatile boolean challengeCountClampWarningLogged = false;

    /**
     * MC-5：配置 N 超过当前可用挑战类型数（典型：无 AI Key 时可用=3 而配置=4）的
     * WARNING 只报一次（进程内单次防刷屏；实际按可用数分配）。
     */
    private static volatile boolean reducedChallengeCountWarningLogged = false;

    /**
     * A5/D2：读取「该日挑战是否全完成」遇到 {@code READ_FAILED} 时的同轮重试次数
     * （含首次尝试）。只在午夜 rollover 的 per-player 循环里使用。
     */
    static final int COMPLETION_READ_ATTEMPTS = 3;

    /**
     * A5/D2：同上重试的退避毫秒（在 {@code connectionLock} 之外 sleep，
     * 不阻塞其它 DB 使用者）。
     */
    private static final long COMPLETION_READ_BACKOFF_MS = 50L;

    public DailyChallengeManager(LetsLearnEnglish plugin, WordManagerImpl wordManager) {
        this.plugin = plugin;
        this.wordManager = wordManager;
        // 数据访问已收编到仓储（纯机械搬家；stripe lock 仍在本类，见 checkAndAssign /
        // claimReward）。DatabaseManager 在构造本类之前已完成 initialize()，tablePrefix 可用。
        this.repository = new DailyChallengeRepository(plugin.getDatabaseManager(), plugin.getLogger());
        // M8 附带 + F1-10：已领取标记（Phase 1 流 C 在用，不能删），换线程安全实现。
        // L18：Set<UUID> → Map<UUID, Set<String>>，按挑战日记录"已付"，见字段 javadoc。
        this.wordChallengeClaimedDates = new ConcurrentHashMap<>();
        for (int i = 0; i < claimStripeLocks.length; i++) {
            claimStripeLocks[i] = new Object();
        }
    }

    // ============================================================
    //  Behavioral Challenges (existing)
    // ============================================================

    /**
     * Check if the player has challenges for today. If not, assign N random ones
     * (N = {@code daily-challenges.challenges-per-day}, clamped to [1, type count]
     * and further bounded by the currently available types; MC-5).
     */
    public void checkAndAssign(UUID playerId) {
        // MC-2（S-H）：daily-challenges.enabled=false 时入口 no-op —— 不产生新挑战行
        //（既有行保留；重开立即恢复分配/进度）。
        if (!plugin.getConfigManager().isDailyChallengesEnabled()) {
            return;
        }
        // F2-11 收口（tester MEDIUM-1）：午夜 rollover 的 checkAndAssign 首次进入异步
        // 线程后，可与主线程触发者（答题 updateProgress 的 R-32 补分配）并发——COUNT
        // 预检与 INSERT 分处两个 connectionLock 临界区，两线程可同时见 COUNT=0 各插
        // 一批（当日行数最多 2N；allDoneToday 遍历全行致"多完成"、极端下多领 ≤1 项）。
        // R-02 先例：按 UUID stripe lock 串行化本方法的整个"预检+分配"段；与
        // MiscGUIListener 领取路径的线程序一致性（L→M 同向，无反向路径不死锁）。
        synchronized (getClaimStripeLock(playerId)) {
            doCheckAndAssign(playerId);
        }
    }

    /** checkAndAssign 的 stripe 锁内体（F2-11 收口拆出）。 */
    private void doCheckAndAssign(UUID playerId) {
        // D1（R31 收尾）：进服是「钱已发、台账没写」的第三个回访点。
        //
        // 原有两个触发点都要玩家**主动答题**才会走到：onWordCorrect 的入口、
        // hasPendingWordRewardForDate 的补发前。于是「领完奖就下线、重启后再也没答过题」
        // 的玩家，那条欠账永远不会被补写 —— 而它就是重启后同日重复领钱的入口。
        // checkAndAssign 是玩家每次进服的必经路径（且已在 stripe lock 内），
        // 挂在这里能把「再也不答题」这个窗口关上，代价是一次 map miss（稳态零开销）。
        //
        // 注意这**不能**根治「重启前欠账信息只存在内存」这件事本身（见
        // InMemoryStateDurabilityTest 的 N9 登记）—— 它只保证：只要玩家再进一次服，
        // 欠账就会被补上，不必等他答对。真正的修复需要跨重启的持久化载体，
        // 而「数据库写不进去」正是本缺陷本身，用数据库记录它属自指，故本轮不做。
        retryPendingClaimLedgerWrites(playerId);
        String today = LocalDate.now().toString();
        // COUNT 预检（仓储内锁内取连接）。仓储以 -1 表示"读失败"：原实现在此记 WARNING
        // 后直接 return（不补单词挑战、不 INSERT），故读失败必须单独短路，不能落到 INSERT。
        int assignedRows = repository.countAssigned(playerId, today);
        if (assignedRows < 0) {
            return;
        }
        if (assignedRows > 0) {
            // Already assigned — but also ensure word challenges are assigned
            ensureWordChallengesAssigned(playerId);
            return;
        }

        // MC-5：challenges-per-day 真接线 + R-74 重抽/兜底消灭。
        // 旧逻辑"随机抽 3、含不可用项则重抽、5 轮失败走兜底"（R-74 论证无 AI Key 时
        // 兜底命中率 ≈24%）与"直接从可用集合取 N"在分布上等价（生效结果恒为可用集合
        // 的均匀随机 N 子集），后者确定性且无重试/兜底分支。读取钳位 [1, 枚举总数]，
        // 越界 WARNING 一次并钳位；默认 3 = 接线前行为零变化。
        int configured = plugin.getConfigManager().getMainConfig()
                .getInt("daily-challenges.challenges-per-day", 3);
        int wanted = configured;
        if (wanted < 1 || wanted > ChallengeType.values().length) {
            wanted = Math.min(Math.max(wanted, 1), ChallengeType.values().length);
            warnChallengeCountClamped(configured, wanted);
        }
        // FIX-DAY-01 保留：只从可用类型中抽（无 AI Key 的服务器抽不到 AI_CHAT_5）。
        List<ChallengeType> available = new ArrayList<>();
        for (ChallengeType ct : ChallengeType.values()) {
            if (isChallengeAvailable(ct)) {
                available.add(ct);
            }
        }
        Collections.shuffle(available, ThreadLocalRandom.current());
        int assigned = Math.min(wanted, available.size());
        List<ChallengeType> picked = new ArrayList<>(available.subList(0, assigned));
        if (picked.isEmpty()) {
            plugin.getLogger().warning("No available daily challenges for " + playerId
                    + " — all challenge types filtered, skipping assignment.");
            ensureWordChallengesAssigned(playerId);
            return;
        }
        if (assigned < wanted) {
            // N > 可用数（典型：无 AI Key 可用=3 而配置=4）——按可用数分配，WARNING 一次。
            warnChallengeCountReduced(wanted, assigned);
        }

        // 批量分配（仓储内保留 SQLite INSERT OR REPLACE / MySQL ON DUPLICATE KEY 方言分支，
        // F2-01 并发幂等语义不变）。写失败只 WARNING，与原实现一致。
        List<String> pickedIds = new ArrayList<>(picked.size());
        for (ChallengeType ct : picked) {
            pickedIds.add(ct.id);
        }
        repository.assignAll(playerId, today, pickedIds);

        // Also assign word challenges
        ensureWordChallengesAssigned(playerId);
    }

    /**
     * FIX-DAY-01：判断某类挑战在当前服务器是否可完成。
     * 目前只有 AI_CHAT_5 受限（需要 AI Key）；其余行为挑战永远可用。
     *
     * <p>MC-2（S-H）：AI_CHAT_5 追加 {@code ai-chat.enabled} 门 —— 模块关闭时
     * 本方法即"AI 任务可完成性"的唯一真相，防分配出打不了的死任务
     * （同时使 R-21 存量降级链在模块关闭后也自然生效）。</p>
     */
    private boolean isChallengeAvailable(ChallengeType type) {
        if (type == ChallengeType.AI_CHAT_5) {
            // 结构性环注记（P2-26）：Core↔Core 互引（反向见 AIChatManager 调
            // DailyChallengeManager.updateProgress）；无递归栈风险，事件化重构成本大于收益，保持现状。
            return plugin.getConfigManager().isAIChatEnabled()
                    && plugin.getAIChatManager() != null && plugin.getAIChatManager().isAvailable();
        }
        return true;
    }

    /** MC-5：challenges-per-day 越界钳位 WARNING（进程内一次，防刷屏）。 */
    private void warnChallengeCountClamped(int configured, int clamped) {
        if (challengeCountClampWarningLogged) return;
        challengeCountClampWarningLogged = true;
        plugin.getLogger().warning("Invalid daily-challenges.challenges-per-day: " + configured
                + " (must be within [1, " + ChallengeType.values().length + "]). Clamped to "
                + clamped + ".");
    }

    /** MC-5：配置数超过可用类型数（如无 AI Key 时配 4 只得 3）的 WARNING（进程内一次）。 */
    private void warnChallengeCountReduced(int wanted, int assigned) {
        if (reducedChallengeCountWarningLogged) return;
        reducedChallengeCountWarningLogged = true;
        plugin.getLogger().warning("daily-challenges.challenges-per-day=" + wanted
                + " exceeds the number of currently available challenge types (" + assigned
                + ") — assigning " + assigned + " instead (typically: AI chat unavailable).");
    }

    /**
     * MC-5：行为挑战奖励金额的<strong>唯一读取口</strong>。
     * 读 {@code daily-challenges.rewards.<id>}；缺键 / 非法（非数字）/ ≤0 一律回落
     * 枚举默认值（{@link ChallengeType#reward}，默认值容器，PF-MC-09）。
     * ≤0 钳回默认而非"0 奖励"：避免产生 "deposit 0 → 判定失败 → 永远领不到" 的畸形分支
     * （记录于 plan-menu-config 1.(e)-2）。
     *
     * @param type the challenge type
     * @return the configured reward, or the enum default when missing/invalid/non-positive
     */
    public double getChallengeReward(ChallengeType type) {
        String path = "daily-challenges.rewards." + type.id;
        // 注意不能用 config.isDouble(path)：它只认 Double 装箱（YAML 的 30 会被读成
        // Integer，isDouble=false 会把合法整数误判为非法）。instanceof Number 覆盖
        // Integer/Double/Long 等全部数值装箱；字符串 "abc"、布尔等非 Number 一律回落。
        // NaN/Infinity（YAML 的 .nan/.inf）同判非法回落。
        Object raw = plugin.getConfigManager().getMainConfig().get(path);
        if (!(raw instanceof Number number)) {
            return type.reward;
        }
        double value = number.doubleValue();
        return value > 0 && Double.isFinite(value) ? value : type.reward;
    }

    /**
     * Get today's challenges for a player.
     */
    public List<ChallengeProgress> getTodayChallenges(UUID playerId) {
        // R-21：读取前先把"存量不可用挑战"降级（自动放弃），避免 Key 中途失效后
        // 已分配的 AI_CHAT_5 永久卡死、拖死当天整组挑战。只在不可用时才动，幂等。
        degradeStaleChallenges(playerId);

        List<ChallengeProgress> list = new ArrayList<>();
        String today = LocalDate.now().toString();
        // R-33：仓储查询保留 ORDER BY challenge_id——展示（DailyChallengeGUI 的 CHALLENGE_SLOTS
        // 槽位 11/13/15/17）与领取（MiscGUIListener 按本方法返回列表的下标查位映射）两侧都走
        // 此查询，排序固定后下标与展示恒一致，不再因 SQLite 返回顺序不定而领到错项（与 F2R-03 同根）。
        for (DailyChallengeRepository.ChallengeRow row : repository.findTodayChallenges(playerId, today)) {
            ChallengeType type = ChallengeType.fromId(row.challengeId());
            if (type != null) {
                // R-79：读取侧同样 clamp —— 修复前写入的超靶旧行（如已领取的
                // 47/10）不会再有写入机会去改写，只能在读侧收敛；展示方
                // （DailyChallengeGUI 的 daily.progress、/le challenge）直读此值。
                // 无逻辑分支读 progress，clamp 只影响展示。
                list.add(new ChallengeProgress(type,
                        Math.min(row.progress(), type.target),
                        row.completed(), row.claimed()));
            }
        }
        return list;
    }

    /**
     * R-21：存量不可用挑战降级（自动放弃）。
     *
     * <p>Key 中途失效（AI 不可用）时，已分配但未完成/未领取的 AI_CHAT_5 会永久卡死：
     * 既无法完成（打不了 AI）也不会被重新分配（分配过滤只管新增量，不管存量）。此处把
     * 这类"卡死"挑战直接标记为已完成+已领取（reward 记 0，即自动放弃、不发任何奖励），
     * 使其不再阻塞 {@link #allDoneToday}，玩家当天挑战不被一个打不了的任务拖死。
     *
     * <p>只对"当前不可用且既未完成也未领取"的行生效（{@code completed=0 AND claimed=0}）：
     * 若玩家在 Key 失效前已完成该 AI 挑战（completed=1、claimed=0），保留其应得奖励不动。
     * AI 恢复后不再降级（今日已降级的次日随跨天重抽恢复）。仅新增这一条降级支路，
     * 不触碰 claim/分配(checkAndAssign)/跨天/庆祝主流程。
     *
     * @param playerId the player
     */
    private void degradeStaleChallenges(UUID playerId) {
        if (isChallengeAvailable(ChallengeType.AI_CHAT_5)) {
            return; // AI 可用，无需降级
        }
        String today = LocalDate.now().toString();
        // 降级写入（SQL 语义：只动 completed=0 AND claimed=0 的行）。写失败只 WARNING。
        int rows = repository.degradeStale(playerId, today, ChallengeType.AI_CHAT_5.id);
        if (rows > 0) {
            plugin.getLogger().info("Degraded stale AI_CHAT_5 challenge for " + playerId
                    + " (AI unavailable) — auto-abandoned, no reward granted.");
        }
    }

    /**
     * Check if all today's challenges are completed and claimed.
     */
    public boolean allDoneToday(UUID playerId) {
        List<ChallengeProgress> challenges = getTodayChallenges(playerId);
        if (challenges.isEmpty()) return false;
        return challenges.stream().allMatch(ChallengeProgress::isAllDone);
    }

    /**
     * Update progress for a challenge type. Called from hooks.
     *
     * <p>R7-B ②：原「{@code findState} SELECT + 判定 + {@code updateProgress} UPDATE」两步
     * 合并为一条带 {@code CASE} 的条件 UPDATE（{@code DatabaseManager.advanceChallengeProgress}）。
     * 判定逻辑（claimed 短路、R-79 写入侧 clamp、completed 只置不撤）逐条下推为 SQL，
     * 无任何判定规则变化：</p>
     * <ul>
     *   <li>{@code claimed = 1} → 两列都原样写回（等价原 {@code if (alreadyClaimed) return;}）；</li>
     *   <li>{@code progress} 封顶 {@code target}（等价 {@code Math.min(newProgress, type.target)}）；</li>
     *   <li>{@code completed} 一旦为 1 不再撤销（等价 {@code if (alreadyCompleted) nowCompleted = true;}）。</li>
     * </ul>
     *
     * <p><strong>R-32 补分配契约保持不变</strong>：受影响行数 {@code 0} 时先回查一次
     * {@code findState} 区分"今日无行"与"有行但本次无变化"——前者与原来
     * {@code loadChallengeState(...) == null} 完全等价 → 走 {@code checkAndAssign}
     * 再重试一次；后者（MySQL 驱动若配置 {@code useAffectedRows=true}，无变化的 UPDATE 会
     * 返回 0）直接结束。这条兜底让语义不依赖驱动开关，且只在 {@code rows == 0} 时多一次
     * SELECT —— 正常路径（SQLite / Connector-J 默认的 matched-rows 语义）里 {@code rows == 0}
     * 本就只出现在"无行"时，开销与原实现持平。
     * {@code -1} = 写失败，直接返回（原实现写失败也只是 WARNING，不重试）。</p>
     */
    public void updateProgress(UUID playerId, ChallengeType type, int increment) {
        // MC-2（S-H）：daily-challenges.enabled=false 时入口 no-op —— 不推进进度、不补分配。
        if (!plugin.getConfigManager().isDailyChallengesEnabled()) {
            return;
        }
        String today = LocalDate.now().toString();

        int rows = plugin.getDatabaseManager()
                .advanceChallengeProgress(playerId, today, type.id, increment, type.target);
        if (rows < 0) {
            // 写失败（已记 WARNING）：原实现同样不重试、不补分配，直接放弃本次推进。
            return;
        }
        if (rows == 0) {
            // 区分"今日无行"（需补分配）与"有行但本次无变化"（无需处理）。
            if (repository.findState(playerId, today, type.id) != null) {
                return;
            }
            // R-32：查无行不再静默丢弃。先 ensure 今日挑战已分配（checkAndAssign 幂等：
            // 已有则只补单词挑战、无则抽 N 条），再重试一次。仍无行说明该类型今日确实
            // 没被抽中（N < 4 时属正常），此时才返回；否则推进刚补上的进度，不再丢失。
            checkAndAssign(playerId);
            plugin.getDatabaseManager()
                    .advanceChallengeProgress(playerId, today, type.id, increment, type.target);
        }
    }

    /**
     * Set a challenge as completed (for binary challenges like combo).
     */
    public void completeChallenge(UUID playerId, ChallengeType type) {
        // MC-2（S-H）：daily-challenges.enabled=false 时入口 no-op。
        if (!plugin.getConfigManager().isDailyChallengesEnabled()) {
            return;
        }
        String today = LocalDate.now().toString();
        // 二进制完成写入（SQL 护栏 claimed = 0 不变）；写失败只 WARNING。
        repository.complete(playerId, today, type.id);
    }

    /**
     * Claim reward for a completed challenge.
     *
     * <p>M12: deposit-then-mark. Callers must transfer the Vault reward FIRST
     * and only call this method to finalize AFTER the deposit succeeds, so a
     * failed deposit leaves the reward re-claimable. The guarded UPDATE keeps
     * this idempotent: double-claims mark at most one row.
     *
     * <p>R-02: additionally serialized per player on a stripe lock. Any two
     * claims for the same player run back-to-back, so the "last check → mark"
     * window in the listener can no longer interleave twice.
     *
     * <p><b>R-18 警告：不要把这个方法接回生产路径。</b>它的返回类型 {@code double}
     * 把「护栏不满足」与「SQL 写失败」折叠成同一个 {@code 0} —— 而
     * {@link DailyChallengeRepository.ClaimMark#WRITE_FAILED} 的含义是
     * <b>「钱可能已转账、台账未落」</b>，两者必须分流（一个该提示「已领过」，
     * 一个该提示「稍后再试」并留下可对账的日志）。真正的领奖入口是
     * {@code MiscGUIListener} 里直连 {@link DailyChallengeRepository#markClaimedOrFail}，
     * 它按三态分流（见该类的类头说明）。本方法与 {@code markClaimed(...)} 目前
     * <b>零生产调用方</b>，保留只为兼容既有测试；若将来需要统一入口，请改成返回三态，
     * 不要从 boolean 版转发。
     *
     * @param playerId the player
     * @param type the challenge type
     * @return the reward amount if marked claimed, or 0 if not eligible
     */
    public double claimReward(UUID playerId, ChallengeType type) {
        synchronized (getClaimStripeLock(playerId)) {
            return markClaimed(playerId, type);
        }
    }

    /**
     * Mark a completed challenge as claimed. Must only be called AFTER a
     * successful economy transfer. The guarded UPDATE keeps this idempotent:
     * concurrent double-claims mark at most one row.
     *
     * @param playerId the player
     * @param type the challenge type
     * @return the reward amount if the row was marked, or 0 otherwise
     */
    private double markClaimed(UUID playerId, ChallengeType type) {
        String today = LocalDate.now().toString();
        // MC-5：返回值走配置化读取口（展示/到账同一数值来源）；不可达分支不读配置。
        return repository.markClaimed(playerId, today, type.id) ? getChallengeReward(type) : 0;
    }

    /**
     * R-02: stripe-lock lookup shared by {@link #claimReward}. Same player
     * always maps to the same monitor.
     *
     * @param playerId the player
     * @return the stripe monitor for this player
     */
    private Object getClaimStripeLock(UUID playerId) {
        return claimStripeLocks[(playerId.hashCode() & 0x7fffffff) % claimStripeLocks.length];
    }

    /**
     * R15（wave3 后继审计 · 并发专场）：<b>暴露本类持有的 stripe 锁数组</b>，供
     * {@code MiscGUIListener} 共用。
     *
     * <p><b>为什么必须共用同一份</b>：挑战领奖有两个入口 —— GUI 点击
     * （{@code MiscGUIListener.claimBehavioralChallenge}）与命令/其它路径（本类的
     * claim 方法）。`synchronized` 的有效性完全依赖所有竞争方拿到<b>同一个</b> monitor。
     * 原先两处各 new 了一份 {@code Object[32]}，等于两把不同的锁，`check → deposit → mark`
     * 仍可跨类交错，双发保护失效。
     *
     * <p>返回数组本身（而非副本）：调用方只做「按下标取一个 monitor」这一个动作，
     * 给副本反而会让锁重新失效。该数组长度固定 32、元素在构造期一次性创建后不再替换，
     * 因此暴露引用是安全的。
     */
    public Object[] getClaimStripeLocks() {
        return claimStripeLocks;
    }

    // ============================================================
    //  Word-Bank-Based Daily Challenges (NEW)
    // ============================================================

    /**
     * Get the configured word count for daily word challenges.
     */
    private int getWordCountConfig() {
        return plugin.getConfigManager().getMainConfig().getInt("daily-challenges.word-count", 5);
    }

    /**
     * Get the reward for completing all daily word challenges.
     */
    private double getWordChallengeReward() {
        // LOG-01：读口校验，与 getChallengeReward 同口径——≤0/NaN/非数值回落默认，否则
        // depositCappedReward 恒返 0 → 只发 pending 不标记 → 每答对一题重入 pending 环。
        Object raw = plugin.getConfigManager().getMainConfig()
                .get("daily-challenges.word-challenge-reward");
        if (!(raw instanceof Number number)) return 100.0;
        double value = number.doubleValue();
        return value > 0 && Double.isFinite(value) ? value : 100.0;
    }

    /**
     * Ensure the player has word challenges assigned for today.
     * Called on player join and when checking behavioral challenges.
     *
     * <p>MC-2（S-H）：双重门 —— {@code daily-challenges.enabled=false} 时挑战体系
     * 整体入口 no-op（本方法亦为公开入口之一，见 plan-menu-config 1.(b) 表 row 4）；
     * {@code word-system.enabled=false} 时词库不再出题，不再分配新单词挑战
     * （分配出来即死任务）。既有分配行保留，重开继续。</p>
     */
    private void ensureWordChallengesAssigned(UUID playerId) {
        if (!plugin.getConfigManager().isDailyChallengesEnabled()
                || !plugin.getConfigManager().isWordSystemEnabled()) {
            return;
        }
        // R5-DC-01：判空 SELECT 与 INSERT 必须同在一把 stripe 锁内，否则 join 的
        // checkAndAssign 与 GUI 的 getTodayWordProgress 可同时见空各插一批（UNIQUE
        // 只去重同词，当日变 2N 行、需全 2N 才发奖）。锁可重入，持锁调用路径不受影响。
        synchronized (getClaimStripeLock(playerId)) {
            ensureWordChallengesAssignedLocked(playerId);
        }
    }

    /** R5-DC-01：{@link #ensureWordChallengesAssigned} 的锁内体（必须持锁调用）。 */
    private void ensureWordChallengesAssignedLocked(UUID playerId) {
        String today = LocalDate.now().toString();
        // A5/D3：判空 SELECT 的<b>读失败</b>与「今天确实还没分配」必须分开。
        // 旧实现走两态 getDailyChallengeWords（读失败 → 空 List），而本方法的判据正是
        // existing.isEmpty() —— 库抖一次就与"未分配"同形，于是随机抽词再 INSERT 一批：
        // 当日挑战集 N → N+M（INSERT OR IGNORE 只去重同词，**不覆盖**既有行），
        // 而 isDailyChallengeWordsCompleted 要求 total == done →
        // <b>当天奖励变成不可达</b>，玩家 GUI 里还会多出几条从未作答的词。
        // 现在读失败什么都不做（不分配），下一次调用（join / GUI / 答对后回查）自然重试。
        DatabaseManager.DailyChallengeWordsLoad existingLoad =
                plugin.getDatabaseManager().loadDailyChallengeWords(playerId, today);
        if (existingLoad == null || existingLoad.readFailed()) {
            // 载荷为 null 只可能来自替换实现/测试替身，同样按「结果未知」处理。
            plugin.getLogger().warning("Skipped daily word-challenge assignment for " + playerId
                    + " on " + today + ": the read of already-assigned words FAILED, so"
                    + " \"not assigned yet\" cannot be distinguished from \"assignment unknown\"."
                    + " Assigning now would risk doubling today's challenge set (and make the"
                    + " all-done reward unreachable). Will retry on the next check.");
            return;
        }
        List<String> existing = existingLoad.words();
        if (!existing.isEmpty()) return;

        // Needs assignment — pick random words from WordBank
        int wordCount = getWordCountConfig();
        List<Word> allWords = new ArrayList<>();
        for (String difficulty : wordManager.getAvailableDifficulties()) {
            WordBank bank = wordManager.getWordBank(difficulty);
            if (bank != null) {
                allWords.addAll(bank.getAllWords());
            }
        }

        if (allWords.isEmpty()) return;

        // Shuffle and pick up to wordCount
        Collections.shuffle(allWords, ThreadLocalRandom.current());
        int count = Math.min(wordCount, allWords.size());
        List<String> selectedIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            selectedIds.add(allWords.get(i).getEnglish().toLowerCase());
        }

        // R11：assignDailyChallengeWords 现在返回成败。原实现后面无条件打 "Assigned N …"，
        // 于是「写库失败」与「已分配」同时出现在日志里 —— 而挑战词是当日单词挑战的前提，
        // 没写进去玩家当天就没有挑战可做（每答对一题都会再空回查一次）。
        // 失败时只记 SEVERE 说明后果，<b>不再说 Assigned</b>。
        //
        // 内存态：本方法不写任何"已分配"标记 —— 唯一的分配快照缓存
        // （dailyWordStateCache / DailyWordChallengeState.noWordsAssigned）由
        // getOrLoadWordState 在"空集"时<b>刻意不落缓存</b>（见该方法的 javadoc），
        // 而本方法的入口判据每次都是一条新的 loadDailyChallengeWords SELECT。
        // 因此失败无需额外清理，下一次调用自然会重试分配 —— 无需新增抑制位。
        if (plugin.getDatabaseManager().assignDailyChallengeWords(playerId, today, selectedIds)) {
            plugin.getLogger().info("Assigned " + selectedIds.size()
                    + " daily challenge words to player " + playerId);
        } else {
            plugin.getLogger().severe("FAILED to assign " + selectedIds.size()
                    + " daily challenge words to player " + playerId + " for " + today
                    + " — this player has NO word challenge today until a later attempt succeeds.");
        }
    }

    /**
     * Called when a player answers a word correctly during learning.
     * Checks if the word is part of today's challenge and marks it complete.
     *
     * <p>M12 retry hook: if all words are done but the reward payout previously
     * failed (claimed flag not set), retry the payout on any further correct
     * answer — even non-challenge words or already-completed words.
     */
    public void onWordCorrect(Player player, Word word) {
        // MC-2（S-H）：daily-challenges.enabled=false 时入口 no-op —— 不完成挑战词、
        // 不触发（也不重试）奖励补发链。word-system 门在 ensureWordChallengesAssigned
        // 与 WordLearningManager 调用侧，本方法不重复门。
        if (!plugin.getConfigManager().isDailyChallengesEnabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        String today = LocalDate.now().toString();
        String wordId = word.getEnglish().toLowerCase();

        // R11：先把上一笔"钱已发、台账没写进去"的日期补上 —— 必须在
        // hasPendingWordReward 之前，这样补写成功的日期立刻能被 DB 台账命中，
        // 不会在同一次登录里又发一遍。稳态下这是一次 map miss，零开销。
        retryPendingClaimLedgerWrites(playerId);

        // R7-B ③：一次加载、当日复用（挑战词集合 / 已完成集合 / 台账位）。
        DailyWordChallengeState state = getOrLoadWordState(playerId, today);

        // R-16（round16 F-D4 #1）：读失败 → 三份数据全部未知。此处直接 return，<b>刻意</b>
        // 连 hasPendingWordReward 都不进：
        //   · 它内部的 isDailyWordRewardClaimed 会自己回查 DB 台账（那一路的口径不变），
        //     但随后要读 state.rewardPaid / state.allCompleted() —— 两者在 unreadable 下都是
        //     "猜出来的值"，拿它决定"是否补发奖励"就是拿未知当事实；
        //   · 本词这次不落库也没关系：markDailyChallengeWordComplete 是幂等 UPDATE
        //     （completed = FALSE 才影响 1 行），玩家再答对同一个词会重新走到这里，
        //     届时若读成功就正常标记。方向是「推迟」而不是「少标 / 多发」。
        // getOrLoadWordState 已留 WARNING，重试点就是下一次答对。
        if (state.unreadable) {
            return;
        }

        // M12: pending-payout retry — all words done but money never arrived.
        // Must run before the challenge-words membership check: retry answers may
        // be outside today's challenge set or already-completed words.
        if (hasPendingWordReward(playerId, today, state)) {
            onAllWordChallengesComplete(player, today);
            // 提前 return 不标记本次词：该词已在"全完成集合"内，奖励已覆盖，无实际丢失（F2R-02）
            return;
        }

        // Check if this word is assigned as today's challenge
        if (!state.challengeWords.contains(wordId)) return;

        // R7-B ③：缓存已确认落库的词直接短路（等价原实现"UPDATE 影响 0 行 → 不判定全完成"，
        // 但省掉每条已完成的重复词一次同步 UPDATE）。缓存只收录"DB 写成功后"的词，
        // 故 contains 为真时 DB 必为已完成，跳过不会漏标记。
        if (state.completedWords.contains(wordId)) {
            return;
        }

        // Mark it complete in DB (returns false if already completed)
        boolean freshlyCompleted = plugin.getDatabaseManager().markDailyChallengeWordComplete(playerId, today, wordId);
        if (!freshlyCompleted) {
            // 不动缓存：false 既可能是"DB 里早已完成"，也可能是 SQLException（写失败）。
            // 两者无法从返回值区分 —— 若把词记进 completedWords，写失败的情形会让该词
            // 当天永远不再重试落库（永久漏标记）。保持原语义：本次不判定全完成、不写缓存，
            // 下次答对同一词会再试一次。
            //
            // R7-D（P1-2）：但"DB 里早已完成"的一个来源是别的子服（多服共享 MySQL）——
            // 此时本进程缓存看不到该词，若它恰好是最后一个词，本服永远等不到"新完成"
            // 触发点。故这里补一次 DB 真源复核（命中则由 dbSaysAllWordsCompleted 缓存置位，
            // 后续答对零查询；SQLException 情形下复核同样大概率失败 → 等价未完成）。
            // 走到这里必然"该词不在本进程的 completedWords 里"（上面已短路），无需重复判。
            // 仍要判"是否已付"：hasPendingWordReward 返回 false 有两种含义（已付 / 未全完成），
            // 而"已付"在跨服场景下确实会走到这里（别的子服已发奖、本服缓存 collection 里
            // 没有该词）—— 不复判就会重复发钱（DB 台账 INSERT 幂等，但 Vault 转账不幂等）。
            if (!isRewardSettled(playerId, state)
                    && dbSaysAllWordsCompleted(playerId, today, state)) {
                onAllWordChallengesComplete(player, today);
            }
            return;
        }
        // 缓存只在 DB 写成功之后推进（写库→改缓存，顺序不可交换）。
        state.completedWords.add(wordId);

        // Only check all-done if this word was just now completed
        // R7-D（P1-2）：本地缓存说"没全完成"时以 DB 真源复核一次 —— 多服共享 MySQL
        // 时，别的子服完成的词不在本进程缓存里，只看缓存会漏发奖（DB 已 5/5）。
        // 同款"已结算不再发"门（跨服下 DB 台账可能已被别的子服写过）。
        if (!isRewardSettled(playerId, state)
                && (state.allCompleted() || dbSaysAllWordsCompleted(playerId, today, state))) {
            onAllWordChallengesComplete(player, today);
        }
    }

    /**
     * R7-D（P1-2）：内存态是否已结算（不再发奖）。
     *
     * <p>只看两个进程内标志，<b>零查库</b>：{@link #hasPendingWordReward} 在返回 false
     * 之前若命中 DB 台账，会同步置 {@code state.rewardPaid} 并回填
     * {@code wordChallengeClaimedDates}。所以在 {@code onWordCorrect} 里、
     * {@code hasPendingWordReward} 刚返回 false 之后，这两位即"已付"的等价判据。</p>
     *
     * <p>为什么新调用点必须复判：{@code hasPendingWordReward} 返回 false 有两种含义——
     * "已付"与"未全完成"。新增的两处"DB 真源复核"只知道后者；跨服场景下别的子服可能
     * 已经发过奖，不复判就会重复走 {@link #onAllWordChallengesComplete}（DB 台账 INSERT
     * 幂等，但 Vault 转账不幂等，等于重复发钱）。</p>
     *
     * @param playerId 玩家
     * @param state    本进程缓存
     * @return true = 今日奖励已结算，不得再发
     */
    private boolean isRewardSettled(UUID playerId, DailyWordChallengeState state) {
        return state.rewardPaid || isWordChallengeClaimedOn(playerId, state.date);
    }

    /**
     * L18：某玩家在<b>指定挑战日</b>是否已确认发过奖（内存快路径，零查库）。
     *
     * <p>日期维度是本次修复的核心：调用方传哪一天，就只回答那一天的已付状态。</p>
     *
     * @param playerId 玩家
     * @param date     挑战日 {@code yyyy-MM-dd}
     * @return true = 该日的单词挑战奖励已发放（或已终态标记）
     */
    private boolean isWordChallengeClaimedOn(UUID playerId, String date) {
        Set<String> dates = wordChallengeClaimedDates.get(playerId);
        return dates != null && dates.contains(date);
    }

    /**
     * L18：标记某玩家<b>指定挑战日</b>的单词挑战奖励已处理（到账成功 / 经济禁用终态）。
     *
     * <p>只能在 {@code markDailyWordRewardClaimed} 之后调用（与 DB 台账同序；台账是防重启
     * 真源，内存位只是快路径）。rollover 补发传的是昨日 —— 这正是必须按日期记录的理由：
     * 标昨日不能连今日一起标，否则会误封今日奖励。</p>
     *
     * @param playerId 玩家
     * @param date     本次发放归属的挑战日 {@code yyyy-MM-dd}
     */
    private void markWordChallengeClaimedOn(UUID playerId, String date) {
        wordChallengeClaimedDates
                .computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                .add(date);
    }

    /**
     * R7-D（P1-2）：用 DB 真源复核"今日单词挑战是否已全部完成"。
     *
     * <p>故障链（多服共享 MySQL）：A 服完成第 1/5 词写 DB → B 服此前已加载快照
     * （{@code completedWords={}}）并缓存 → 玩家在 B 服答完剩余词，B 的缓存
     * {@code size < 5} → {@code allCompleted()} false → <b>不发奖</b>，而 DB 侧已是
     * 5/5（三态 {@code loadWordChallengeCompletion} 的 {@code COMPLETED}）。HEAD 版每题都查 DB COUNT，不存在
     * 这个背离 —— 这是 R7-B 缓存引入的回归。</p>
     *
     * <p><strong>为什么不能只查"差一个词"的边界</strong>：跨服可补的缺口不一定是 1。
     * 缺口 ≥2 时（同一玩家在两个子服的会话交错）"差一词才查"永远不触发；而
     * {@link #hasPendingWordRewardForDate} 的次日补发只在午夜 rollover 遍历<b>在线</b>
     * 玩家，缺奖玩家若当时离线就是<strong>永久丢奖</b>（次日 onWordCorrect 只看"今日"）。
     * 故这里对"本次真正新完成的挑战词"一律复核一次 —— 上限为每日每玩家 N 条
     * SELECT（N = 当日挑战词数，默认 5），且同一词重复答对在前面的
     * {@code completedWords.contains} 已短路，不会重复计费；相比改造前"每个答对
     * （含非挑战词）都查 4 条"，仍是数量级下降。</p>
     *
     * <p>命中即把 {@code completedConfirmedByDb} 置位：① 本次立刻发奖；② 若发币失败
     * （Vault 瞬时失败/超 cap），后续任意答对经 {@code hasPendingWordReward} 的重试钩子
     * 继续补发 —— 只看 {@code completedWords} 的话，跨服缺口永远凑不满，重试钩子再也不会
     * 触发（发奖失败也会变成永久丢奖）。</p>
     *
     * @param playerId 玩家
     * @param today    今日日期
     * @param state    本进程缓存
     * @return true 仅当 DB 确认今日所有挑战词已完成
     */
    private boolean dbSaysAllWordsCompleted(UUID playerId, String today,
                                            DailyWordChallengeState state) {
        if (state.completedConfirmedByDb) {
            return true;
        }
        // R12-F8：原调用两态版 {@code isDailyChallengeWordsCompleted}（该方法已删除）。
        // 这里显式写出折叠语义，绝不隐式依赖一个"看起来像布尔事实"的返回值：
        //   · COMPLETED     → 确认全完成，置位缓存并放行发奖；
        //   · NOT_COMPLETED → 业务事实「没做完」，本轮不发奖（正确）；
        //   · READ_FAILED   → 结果<b>未知</b>。本方法的调用点都在「缓存说没完成」之后，
        //     方向是「不发奖 = 推迟」而非「少发」：pending 未被标记、缓存未推进，
        //     下一次任意答对会重新复核（见 onWordCorrect 的重试豆）。若哪天要在
        //     "决定是否放弃发放"的路径上用本方法，必须先重新审视 READ_FAILED 分支 ——
        //     那时正确的处置是保留 pending（照 hasPendingWordRewardForDate 的范式），
        //     不是返回 false。
        DatabaseManager.WordChallengeCompletion completion =
                plugin.getDatabaseManager().loadWordChallengeCompletion(playerId, today);
        // null 只可能来自替换实现/测试替身（生产实现的三态永不返回 null）——
        // 与 readWordChallengeCompletionWithRetry 同口径，按「未知」处理，不猜成完成。
        boolean done = completion == DatabaseManager.WordChallengeCompletion.COMPLETED;
        if (done) {
            state.completedConfirmedByDb = true;
        }
        return done;
    }

    /**
     * R7-B ③：取（或加载）该玩家的今日单词挑战缓存。
     *
     * <p>加载即一次 {@code DatabaseManager.loadWordChallengeSnapshot}（1 条单词查询 +
     * 1 条台账查询），此后当日所有答对路径复用，直到跨天 / quit / 午夜 rollover。</p>
     *
     * <p><strong>不缓存"今日无挑战词"</strong>：分配可能在本进程稍后才到达（join 的异步
     * {@code checkAndAssign}、R-32 补分配、以及 {@code ensureWordChallengesAssigned}
     * 自身的重试路径）。若把空集缓存一整天，当日后续分配出的挑战词将永远无法被标记完成
     * —— 故空快照只返回临时对象、不落缓存，与改造前"每题回查一次"的行为一致。</p>
     *
     * <p><strong>R-16（round16 F-D4 #1）：读失败必须自成一态，且同样不落缓存。</strong>
     * 旧实现在这里只判 {@code fresh.noWordsAssigned} —— 而读失败的空集会让它恒真，于是
     * 调用方看到的是"今日无挑战词"。现在数据层把 {@code readFailed} 明确带上来，本方法
     * 用 {@link DailyWordChallengeState#unreadable} 占位：
     * <ul>
     *   <li>不落缓存 —— 读失败是瞬时故障，缓一整天等于整天看不见真实挑战词；</li>
     *   <li>置 {@code unreadable} —— {@link #onWordCorrect} 等路径据此短路，不拿空集合
     *       当"没分配/没完成"（折叠方向必须是「推迟」而不是「当成未完成」）；</li>
     *   <li>留一条 WARNING —— 与 {@code ensureWordChallengesAssignedLocked} 的读失败
     *       告警同口径，是本路径唯一可观测信号。</li>
     * </ul>
     *
     * <p>为什么<b>不</b>把 {@code rewardPaid} 的读失败也折进 {@code readFailed}：
     * 那一路"按未付处理"是 {@code isDailyWordRewardClaimed} 刻意保留的口径，且
     * {@link #hasPendingWordReward} 在缓存未命中时会再补一次 DB 回查兜底 —— 折进来会让
     * 一次台账抖动把整份挑战词集合判成不可信，反而更糟。</p>
     *
     * @param playerId 玩家
     * @param today    {@code yyyy-MM-dd}
     * @return 当日缓存（或一次性临时对象；读失败时 {@code unreadable=true}）
     */
    private DailyWordChallengeState getOrLoadWordState(UUID playerId, String today) {
        DailyWordChallengeState cached = dailyWordStateCache.get(playerId);
        if (cached != null && cached.date.equals(today)) {
            return cached;
        }
        DatabaseManager.WordChallengeSnapshotLoad load =
                plugin.getDatabaseManager().loadWordChallengeSnapshot(playerId, today);
        // 载荷为 null 只可能来自替换实现/测试替身（生产实现的三态永不返回 null）——
        // 与 ensureWordChallengesAssignedLocked / readWordChallengeCompletionWithRetry
        // 同口径，按「结果未知」处理，绝不猜成"今天没词"。
        if (load == null || load.readFailed()) {
            plugin.getLogger().warning("Daily word-challenge snapshot for " + playerId + " on "
                    + today + " is UNKNOWN (the read FAILED) — discarded instead of cached, and"
                    + " the empty/partial word set is NOT treated as \"nothing assigned today\"."
                    + " Two things this prevents: (a) a half-read set cached as the day's real"
                    + " set, which would make allCompleted() compare against a too-small total"
                    + " and pay the reward early; (b) \"could not read\" being answered as"
                    + " \"not assigned / not completed\". Will retry on the next correct answer.");
            dailyWordStateCache.remove(playerId);
            return DailyWordChallengeState.unreadable(today);
        }
        DatabaseManager.WordChallengeSnapshot snapshot = load.snapshot();
        DailyWordChallengeState fresh = new DailyWordChallengeState(
                today, snapshot.words(), snapshot.completedWords(), snapshot.rewardPaid());
        if (fresh.noWordsAssigned) {
            // 不缓存空集（见方法 javadoc）；同时清掉可能残留的昨日条目。
            dailyWordStateCache.remove(playerId);
            return fresh;
        }
        dailyWordStateCache.put(playerId, fresh);
        return fresh;
    }

    /**
     * R7-B：quit 清理钩子（与 {@code PlayerDataStore.unloadPlayerData} 同模式）——
     * 下线即丢弃该玩家的当日单词挑战缓存，防缓存随在线玩家数无量增长。
     *
     * <p>只清缓存、<strong>不动</strong> {@code wordChallengeClaimedDates}：后者是
     * "已付"的内存快路径，清掉虽安全（DB 台账兜底）但非必要，保持原语义不变。</p>
     *
     * <p>R7-D（P1-1）：失效点有两个 —— ① 玩家 quit（本方法，{@code PlayerJoinListener}
     * 的 quit 编排点）；② {@code /lea reset}（走 {@link #resetPlayer(UUID)}，本方法
     * <strong>不</strong>足以覆盖：reset 会连 DB 台账一起删，内存里的"已付"必须同步失效，
     * 否则 DB 说没付、内存说已付 → 当天挑战重刷到 5/5 也发不出奖）。</p>
     *
     * <p>兜底：跨天时 entry 会被判定失效并替换，午夜 rollover 的
     * {@link #clearAllWordChallengeClaims()} 会全量清空 —— 即便将来新增的清理调用点
     * 漏接，也不会让旧日的缓存被误用。</p>
     *
     * @param playerId 下线的玩家
     */
    public void unloadPlayer(UUID playerId) {
        dailyWordStateCache.remove(playerId);
    }

    /**
     * R7-D（P1-1）：{@code /lea reset} 的内存态失效点 —— 清当日单词挑战缓存<b>与</b>
     * "今日已付"内存位。
     *
     * <p>与 {@link #unloadPlayer(UUID)} 的唯一差别是多清 {@code wordChallengeClaimedDates}，
     * 原因：reset 经 {@code DatabaseManager.deletePlayerData} 删掉
     * {@code daily_challenge_words} / {@code daily_word_reward_claims} 两表的玩家行，DB 侧
     * 回到"今日未分配、未发奖"；若只清 {@code dailyWordStateCache}，内存里的已付位仍在
     * {@code hasPendingWordReward} 第一层直接短路返回 false → 玩家当天就算重新刷满词挑战
     * 也拿不到奖（DB 台账明明已空）。这不属于"非必要"，是 reset 语义的一部分。</p>
     *
     * <p>反过来，quit 路径不能清这个位（同一天反复上下线会重启当日"已付"判定、依赖 DB
     * 回查兜底），故两个语义拆成两个方法，{@code unloadPlayer} 保持原样。</p>
     *
     * <p>线程/落盘：只做两次进程内集合 remove，不查库、不写库。<b>必须在
     * {@code deletePlayerData} 之前调用</b>——与其他 unload 同一时序纪律
     * （缓存清理先于删表），且本方法不落盘，不存在把旧态写回的风险。</p>
     *
     * @param playerId 被 reset 的玩家
     */
    public void resetPlayer(UUID playerId) {
        dailyWordStateCache.remove(playerId);
        wordChallengeClaimedDates.remove(playerId);
        // R11：<b>必须</b>连"失败待补写"一起清 —— reset 已经删掉
        // daily_word_reward_claims 里该玩家的行（见方法 javadoc），若保留补写登记，
        // 下一次答对会把刚删掉的"已付"重新插回去，玩家当天刷满词挑战也领不到奖 ——
        // 正是本方法 javadoc 描述的那个缺陷换个位置复发。
        pendingClaimLedgerWrites.remove(playerId);
    }

    /**
     * R-07: read-only check whether today's word-challenge reward has actually
     * landed for this player. The claimed flag is only set AFTER the Vault
     * transfer succeeds (M12), so presence in the set means paid and absence
     * means pending. Set semantics are day-scoped: entries are added on
     * successful payout and cleared at midnight
     * rollover (see {@link #clearAllWordChallengeClaims}), so a stale
     * cross-day true is impossible.
     *
     * <p>修复批 A（R10-H1）：内存未命中时回查 DB 台账（{@code daily_word_reward_claims}）
     * —— 重启后"已付"不再因内存清空被误判为 pending；内存已含则短路免查库。
     *
     * @param playerId the player
     * @return true if today's word reward money has arrived
     */
    public boolean isWordRewardPaidToday(UUID playerId) {
        // 只取一次「今天」：跨午夜瞬间两次 LocalDate.now() 可能落在不同日期，
        // 那会让内存判定查今日、DB 判定查昨日（或反之），得出自相矛盾的结论。
        String today = LocalDate.now().toString();
        return isWordChallengeClaimedOn(playerId, today)
                || plugin.getDatabaseManager().isDailyWordRewardClaimed(playerId, today);
    }

    /**
     * Check whether the player has finished all of today's word challenges but
     * the reward payout has not landed yet (claimed flag not set).
     *
     * <p>修复批 A（R10-H1）：三层判定 —— ① 内存快路径（本进程已付）；② DB 台账
     * （{@code daily_word_reward_claims}，重启后"已付"真源；命中即回填内存）；
     * ③ 全完成判定。此前仅 ①②合一的内存 Set 作台账，同日重启后 Set 空 + DB
     * completed=true → 每答对一题重复发钱。
     *
     * <p><strong>R7-B ③：判定顺序/真源不变，只把"查什么"换成当日缓存</strong>——
     * ① 仍是 {@code wordChallengeClaimedDates} 里<b>今日</b>的那一位（不缓存）；② 缓存 {@code rewardPaid}
     * 为 true 时短路（它只可能在 {@code markDailyWordRewardClaimed} 之后置位），为 false 时
     * <strong>照旧回查 DB 台账</strong>（绝不把"未付"当"已付"）；③ 用缓存的
     * {@code completedWords} 作快判。R7-D（P1-2）：③ 的 {@code allCompleted()} 现在还多认得
     * "DB 已复核全完成"这一路（{@code completedConfirmedByDb}）—— 多服共享 MySQL 下别的
     * 子服完成的词不在本进程缓存里，仅靠缓存会漏掉重试钩子（详见
     * {@code dbSaysAllWordsCompleted}）。</p>
     *
     * @param playerId the player
     * @param today today's date string (yyyy-MM-dd)
     * @param state the player's cached word-challenge state for {@code today}
     * @return true if payout is pending and should be retried
     */
    private boolean hasPendingWordReward(UUID playerId, String today, DailyWordChallengeState state) {
        // L18：第一道门带日期 —— 只认"今天"的已付标记。旧实现是无日期 Set，
        // 会把别的日期的已付错当今日已付（反向地，rollover 判定昨日时也会把今天的
        // 已付错当昨日已付，见 hasPendingWordRewardForDate）。
        if (isWordChallengeClaimedOn(playerId, today)) {
            return false;
        }
        if (state.rewardPaid) {
            // 缓存已确认写过台账（markDailyWordRewardClaimed 之后置位）→ 等价原 DB 命中。
            markWordChallengeClaimedOn(playerId, today); // 回填内存，后续走快路径
            return false;
        }
        // 注意：rewardPaid 为 false 有两种含义 —— "确认未付"与"查询失败" —— 两者都
        // 必须回查 DB（失败时回查同样失败 → 仍按未付处理，与原降级语义一致）。
        if (plugin.getDatabaseManager().isDailyWordRewardClaimed(playerId, today)) {
            state.rewardPaid = true;                        // 缓存同步 DB 真源
            markWordChallengeClaimedOn(playerId, today);    // 回填内存，后续走快路径
            return false;
        }
        return state.allCompleted();
    }

    /**
     * Called when all word challenges for today are completed.
     * Deposits the flat reward and sends the celebration chat.
     *
     * <p>M12: deposit-then-mark. The claimed flag ({@link #wordChallengeClaimedDates})
     * is set only AFTER the Vault transfer succeeds. If the deposit fails, the
     * celebration is withheld, the player gets a retry hint, and the next
     * {@link #onWordCorrect} invocation (any correct answer) retries — so the
     * reward is never lost as long as the player keeps studying.
     *
     * <p>R-31 reuse: {@link #hasPendingWordRewardForDate} also calls this at the
     * midnight rollover to back-pay a prior-day pending reward (flat amount, same
     * mark), so a deposit that failed before midnight is honored instead of lost.
     *
     * <p>修复批 A（R10-H1）：{@code claimDate} 为本次发放归属的挑战日 —— 正常路径
     * （onWordCorrect 补发/完成）传今日；rollover 补发传昨日（不能把昨日补发标成
     * "今日已领"，否则误封今日挑战奖励）。成功与"经济禁用终态"两侧均写 DB 台账
     * （{@code markDailyWordRewardClaimed(claimDate)}），同日重启后不再重复发放。
     *
     * @param player    the player
     * @param claimDate the challenge date this payout settles (yyyy-MM-dd)
     */
    private void onAllWordChallengesComplete(Player player, String claimDate) {
        UUID playerId = player.getUniqueId();
        double reward = getWordChallengeReward();

        // R-16：面值 + 实发额一起带下来。过去这里只拿到 boolean，于是下面所有出口
        // 都只能拿面值 reward 渲染 —— 日限截断时（默认 cap 500，玩家今天已得 480）
        // 实发 0.20 却播报「+100 金币」，同一行台账还被标成「已领」，剩下的 80
        // 永久不可追回（本缺陷的第二个案发点）。
        RewardEngine.CappedPayout payout = depositWordChallengeReward(player, playerId, reward);
        if (payout.paid() <= 0) {
            // R-44 对齐（与 LearnCommand /le challenge 同语义）：区分两种发币失败。
            // MC-2（S-H）：改走 RewardEngine.isMoneyEnabled()（单一真相收口）——
            // "关奖励伞"（rewards.enabled=false）与"关 money 键"任一 false 均判为
            // 终态无奖可发，走 economy-disabled 分支（否则伞关闭会误判成瞬时失败、
            // 每答对一题重入 reward-pending 刷屏）。
            boolean moneyEnabled = plugin.getProgressManager().getRewardEngine().isMoneyEnabled();
            if (!moneyEnabled) {
                // 经济开关永久关闭 = 无奖可发（终态）。标记已处理避免 hasPendingWordReward
                // 恒 true 导致每答对一题重入刷屏；发一次 economy-disabled 说明，不承诺"稍后补发"。
                // 修复批 A（R10-H1）：终态同样写 DB 标记 —— 防重启后内存清空、
                // 每答对一题重复刷 economy-disabled 提示。
                // L18：内存位按 claimDate 记录（rollover 补发传昨日，不能连今日一起标）。
                // R11：台账写失败时内存位照旧标记（否则每次答对都重刷一遍提示），
                // 另外登记待补写 + SEVERE —— 见 persistWordRewardClaim。
                markWordChallengeClaimedOn(playerId, claimDate);
                persistWordRewardClaim(playerId, claimDate);
                syncCachedRewardPaid(playerId, claimDate); // R7-B ③：台账写后同步缓存
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "daily-challenge.economy-disabled"));
                return;
            }
            // Vault 瞬时失败 / 超 cap：保留 pending 可重试语义，不标记已领。
            // R-16：报的仍是**面值**——一分钱没发时不存在"实发额"；"未丢失"指这
            // 笔面额仍挂在该日 pending 上可重试。顺手去掉 `(int)` 截断。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.reward-pending", "reward", payout.nominalText()));
            return;
        }

        // Success: mark claimed only after money arrived.
        // 修复批 A（R10-H1）：DB 台账同步标记。
        // L18：内存位按 claimDate 记录 —— rollover 补发传昨日，只标昨日。
        // R11：台账写失败不再静默 —— persistWordRewardClaim 记 SEVERE 并登记补写。
        // 刻意<b>不回滚</b>已发出的钱：失败方向是「多给」（重启后可能同日再发一次），
        // 回滚会把它翻成「玩家答对了拿不到奖励」，那是更严重的体验倒退。
        //
        // R-16 裁决（单词挑战，整日一次）：<b>截断也照常收盘，但如实报实发额</b>。
        // 为什么不在截断时保留 pending 等明天补（选项 b）—— 逐条否决：
        //   ① 补发路径发的是<b>整个面值</b>而非"剩下的 80"：rollover 的
        //      hasPendingWordRewardForDate → onAllWordChallengesComplete 再走一遍
        //      getWordChallengeReward() 全额 100。玩家手里已留 0.20，于是总额变成
        //      100.20 —— 把"少发"翻成"多发"，且与台账（该日仅一行）对不上账。
        //   ② 当日刷屏：未收盘时 hasPendingWordReward 在<b>每次</b>答对都触发补发尝试，
        //      而 cap 当天已饱和 → paid 恒 0 → 每答对一题一条 reward-pending。
        //   ③ 隔天可能永久滞留：rollover 补发只遍历<b>在线</b>玩家（见本类
        //      hasPendingWordRewardForDate 的 javadoc）。玩家若不在那次午夜在线，
        //      昨日那行再没有任何其它回访点 → 永久 pending（正是 M12/R31 反复强调
        //      的反面：pending 只在"重试真的能拿到钱"时才是善意）。
        // 而"收盘 + 说实话"三条都不成立：台账干净、玩家立刻知道实发多少、且
        // ChallengeCompleteGUI 早已改成显示<b>面值</b>并撤回"已到账"承诺（slice-13-F5），
        // 与这里同一口径。故：收盘，用 daily.reward-capped 明示"实发 X（应发 Y）"。
        markWordChallengeClaimedOn(playerId, claimDate);
        persistWordRewardClaim(playerId, claimDate);
        syncCachedRewardPaid(playerId, claimDate); // R7-B ③：台账写后同步缓存

        // R-16：先报截断实情（若有），再播庆祝。原子仍用 daily-challenge.all-complete，
        // 但 {reward} 换成**实发额** —— 这是本缺陷的正面修复：报给玩家的数字与
        // 真正落账的数字必须同源。
        if (payout.capped()) {
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily.reward-capped",
                    "paid", payout.paidText(),
                    "nominal", payout.nominalText()));
        }

        // Send celebration message
        String lang = plugin.getPlayerLanguage(player);
        var msgs = plugin.getConfigManager().getMessageConfig(lang);
        String msg = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(msgs, "daily-challenge.all-complete")
                        .replace("{reward}", payout.paidText()));
        player.sendMessage(msg);

        // FIX-CHAL-01 去强制化：不再自动弹庆祝 GUI（玩家可能在决斗/看别的界面），
        // 改为聊天提醒，玩家按需用 /le challenge 重开庆祝页。
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "daily-challenge.reopen-hint"));
    }

    /**
     * R11：写"某日已付"DB 台账，<b>失败可见</b>，并登记待补写。
     *
     * <p><b>失败方向必须先说清</b>：本方法只在<b>钱已经发出去之后</b>被调用
     * （{@link #onAllWordChallengesComplete} 的两条出口：Vault 到账成功 / 经济开关
     * 永久关闭的终态）。所以台账写失败<b>不会</b>让玩家少拿 —— 它让"已付"这件事
     * 在重启后消失，DB 台账查不到 + 挑战词仍是"全完成" → 同一天<b>再发一次</b>。
     * 方向是「多给」，是真实的经济损失，但比「玩家白干」轻。
     *
     * <p><b>刻意不做的事：不回滚已发的钱。</b>回滚要么反向扣款（对玩家可见的资产变动、
     * 且扣款本身也可能失败），要么"台账写失败就当没发"（下次答对再发 → 变成重复发）。
     * 两条都比"多给一次"更糟。所以这里的处置只有三条：
     * <ol>
     *   <li>记一条 <b>SEVERE</b>（{@link DatabaseManager#markDailyWordRewardClaimed} 的
     *       WARNING 附堆栈只是数据层现场；SEVERE 这一条带玩家与日期，并明写"重启后可能
     *       同日重复发放"，是唯一跨重启的线索）；</li>
     *   <li>把 {@code claimDate} 登记进 {@link #pendingClaimLedgerWrites} —— 数据库恢复后
     *       玩家下一次答对（任意词）或下次发奖即补写，窗口在重启前大多能闭合；</li>
     *   <li>内存位照旧标记（调用方做），所以本进程内不会重复发。</li>
     * </ol>
     *
     * <p>补写用同一个 {@code INSERT OR IGNORE} / {@code INSERT IGNORE} 方法 —— 幂等，
     * 重复写影响 0 行，绝不会把"未付"写成"已付"之外的任何副作用。
     *
     * @param playerId  玩家
     * @param claimDate 本次发放归属的挑战日 {@code yyyy-MM-dd}
     * @return true = 台账已确认落库（含"原本就存在"）；false = 写失败，已登记待补写
     */
    private boolean persistWordRewardClaim(UUID playerId, String claimDate) {
        boolean written;
        try {
            written = plugin.getDatabaseManager().markDailyWordRewardClaimed(playerId, claimDate);
        } catch (RuntimeException e) {
            // 连接层故障（{@code getConnection()} 抛未检查异常）不是 SQLException，
            // 数据层接不到它。这里必须自己接：否则异常会从发奖路径逃出去，
            // 让玩家收到庆祝消息之前就断在半路（而钱已经到账）。
            written = false;
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Reward ledger write threw for " + playerId + " / " + claimDate, e);
        }
        if (written) {
            Set<String> pending = pendingClaimLedgerWrites.get(playerId);
            if (pending != null) {
                pending.remove(claimDate);
                if (pending.isEmpty()) {
                    pendingClaimLedgerWrites.remove(playerId, pending);
                }
            }
            return true;
        }

        // 岗位 ②：登记补写（有界：每人每天最多 1 个日期，午夜 rollover 全清）。
        pendingClaimLedgerWrites
                .computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                .add(claimDate);
        // 岗位 ①：SEVERE。文案里必须同时有"钱已发"与"重启后可能重复发放"两个事实 ——
        // 运维据此判断是否需要人工补 INSERT，而不是去查"玩家是不是没拿到钱"。
        plugin.getLogger().severe("Daily word challenge reward ledger write FAILED for "
                + playerId + " / " + claimDate + " — the payout itself already succeeded"
                + " (or was terminally marked economy-disabled), so NO retry of the money is"
                + " needed. But this date is now missing from daily_word_reward_claims: if the"
                + " server restarts before the ledger write succeeds, the same challenge day"
                + " can pay out a SECOND time. A retry is queued and will be attempted on the"
                + " player's next correct answer; manual fix if the player never answers again:"
                + " INSERT OR IGNORE INTO " + plugin.getDatabaseManager().getTablePrefix()
                + "daily_word_reward_claims (player_uuid, challenge_date) VALUES ('"
                + playerId + "', '" + claimDate + "');");
        return false;
    }

    /**
     * R11：补写登记表里该玩家的全部台账行（{@link #persistWordRewardClaim} 的落实点）。
     *
     * <p>调用点有两个：{@link #onWordCorrect} 的入口（任意答对都试一次 —— 包括非挑战词，
     * 因为 {@link #onWordCorrect} 的注释已明确"重试答案可能不在挑战词集合内"）与
     * {@link #hasPendingWordRewardForDate} 的补发前。两处都不改变既有判定语义 ——
     * 补写成功后登记表变空，下次调用是零开销的 map miss。
     *
     * @param playerId 玩家
     */
    private void retryPendingClaimLedgerWrites(UUID playerId) {
        Set<String> pending = pendingClaimLedgerWrites.get(playerId);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        // 拷贝后再遍历：persistWordRewardClaim 成功时会在同一集合上 remove。
        for (String date : new ArrayList<>(pending)) {
            persistWordRewardClaim(playerId, date);
        }
    }

    /**
     * R7-B ③：台账写入之后的缓存同步（只在 {@code markDailyWordRewardClaimed} 之后调用）。
     *
     * <p>只标记"归属日为今日"的缓存条目：rollover 补发传昨日（{@code claimDate} = 昨天），
     * 此时若把今日缓存置成已付，会让今日挑战奖励永远发不出去（正是 M12/R31 反复强调的
     * 跨日误封）。日期不符即不碰缓存，下次 {@link #getOrLoadWordState} 会按新日期重建。</p>
     *
     * @param playerId  玩家
     * @param claimDate 本次发放归属的挑战日（yyyy-MM-dd）
     */
    private void syncCachedRewardPaid(UUID playerId, String claimDate) {
        DailyWordChallengeState cached = dailyWordStateCache.get(playerId);
        if (cached != null && cached.date.equals(claimDate)) {
            cached.rewardPaid = true;
        }
    }

    /**
     * Deposit the word-challenge reward.
     *
     * <p>R-34/F1-23: routes through {@link com.letslearnenglish.minecraftplugin.core.progress.RewardEngine#depositCappedReward}
     * so the payout obeys {@code money.daily-cap} and updates the today-coins ledger,
     * and so coin stats are recorded inside the reward layer — this manager no longer
     * calls {@code DatabaseManager.recordCoinsEarned} directly (layering fix).
     *
     * <p><b>R-16：返回 {@link RewardEngine.CappedPayout}，不再折叠成 boolean。</b>
     * {@code paid() > 0} 与旧 boolean 完全等价（旧 {@code return paid > 0}），故两个
     * 调用出口的分支判断不变；新增的是"实发多少"这一路信息，供文案报真数。
     *
     * @param player the player
     * @param playerId the player UUID (kept for call-site symmetry)
     * @param reward the nominal reward amount
     * @return 面值 + 实发额（{@code paid() > 0} 才表示钱真的到了）
     */
    private RewardEngine.CappedPayout depositWordChallengeReward(Player player, UUID playerId,
                                                                double reward) {
        var eco = plugin.getEconomy();
        if (eco == null) {
            plugin.getLogger().warning("Word challenge reward of " + reward
                    + " for " + player.getName() + " held: Vault economy unavailable");
            return new RewardEngine.CappedPayout(reward, 0);
        }
        // R-44 fan-in：money.enabled=false 时不发币、返回 false（走既有 reward-pending /
        // economy-disabled 语义，不标记已领、不静默吞）。depositCappedReward 本身不查此开关，
        // 故在此门把关，与 LearnCommand 的 /le challenge economy-disabled 分支衔接。
        // MC-2（S-H）：改走 RewardEngine.isMoneyEnabled()（单一真相收口）—— 奖励伞
        // （progress-system.rewards.enabled）关闭时同样不发币。
        if (!plugin.getProgressManager().getRewardEngine().isMoneyEnabled()) {
            return new RewardEngine.CappedPayout(reward, 0);
        }
        double paid = plugin.getProgressManager().getRewardEngine().depositCappedReward(player, reward);
        return new RewardEngine.CappedPayout(reward, paid);
    }

    /**
     * Get today's word challenge progress for a player.
     * Returns int[]{completed, total}.
     */
    public int[] getTodayWordProgress(Player player) {
        String today = LocalDate.now().toString();
        ensureWordChallengesAssigned(player.getUniqueId());
        return plugin.getDatabaseManager().getDailyChallengeWordsProgress(player.getUniqueId(), today);
    }

    /**
     * Get today's word challenge details (words and completion status).
     */
    public List<WordChallengeProgress> getTodayWordDetails(Player player) {
        String today = LocalDate.now().toString();
        UUID playerId = player.getUniqueId();
        ensureWordChallengesAssigned(playerId);

        // 刻意不走 R7-B ③ 的答对路径缓存：本方法是 GUI / 指令展示路径
        // （调用频率低、要求"所见即库中真实状态"，且 loadDailyChallengeWords 的 words 顺序
        // 即展示顺序）。缓存只服务于 onWordCorrect 的高频去重，不扩散到读展示侧。
        //
        // A5/D3：这里<b>仍按空列表降级</b>，但那纯粹是展示面（下方每条会变成"待完成"），
        // 不参与任何"是否分配/是否发奖"的判定 —— 那条判定在
        // ensureWordChallengesAssignedLocked 里，已改成读失败不分配。
        // 之所以不在这里抛（同仓库 DailyChallengeRepository.findTodayChallenges 的范式），
        // 是因为本方法的两个调用方（GUI 的 openAsync / LearnCommand 的 /le challenge）
        // 只有前者有 catch → daily.load-failed，后者会把异常直接抛到 Bukkit 命令层。
        // 故这里选择"降级 + 一条能看出根因的日志"，把可观测性留在数据层与本方法的
        // WARNING 上，而不改变命令层的异常契约。
        DatabaseManager.DailyChallengeWordsLoad load =
                plugin.getDatabaseManager().loadDailyChallengeWords(playerId, today);
        if (load == null || load.readFailed()) {
            plugin.getLogger().warning("Daily word-challenge details for " + playerId + " on "
                    + today + " rendered EMPTY because the read FAILED — this is a display-only"
                    + " degradation: nothing is assigned, paid or deleted based on it, and the"
                    + " 'no challenges today' shape is not used for any decision here.");
        }
        List<String> wordIds = (load == null) ? List.of() : load.words();
        List<WordChallengeProgress> result = new ArrayList<>();

        for (String wordId : wordIds) {
            boolean wordCompleted = checkSingleWordCompleted(playerId, today, wordId);
            result.add(new WordChallengeProgress(wordId, wordCompleted));
        }
        return result;
    }

    private boolean checkSingleWordCompleted(UUID playerId, String date, String wordId) {
        // 无行/读失败均为 false（原实现 catch 块静默 ignore）。
        return repository.isWordCompleted(playerId, date, wordId);
    }

    /**
     * A5/D2：读「该日是否全完成」的三态，<b>在 READ_FAILED 时有界重试</b>。
     *
     * <p>为什么值得重试而不是一次就放弃：{@code DatabaseManager.getConnection()} 在连接
     * 关闭/自愈失败后，下一次调用会重新 {@code initialize()}，所以"再读一次"有真实成功
     * 机会；而本方法只在午夜 rollover 的 per-player 循环里跑（每人每日期至多一次），
     * 重试成本可忽略。退避在 {@code connectionLock} 之外（本方法不持锁），不阻塞他人。</p>
     *
     * <p>重试耗尽仍失败 → 返回 {@link DatabaseManager.WordChallengeCompletion#READ_FAILED}，
     * 由调用方 {@link #hasPendingWordRewardForDate} 记 SEVERE 并<b>保留 pending</b>。</p>
     */
    private DatabaseManager.WordChallengeCompletion readWordChallengeCompletionWithRetry(
            UUID playerId, String date) {
        DatabaseManager.WordChallengeCompletion result =
                DatabaseManager.WordChallengeCompletion.READ_FAILED;
        for (int attempt = 1; attempt <= COMPLETION_READ_ATTEMPTS; attempt++) {
            result = plugin.getDatabaseManager().loadWordChallengeCompletion(playerId, date);
            // null 只可能来自替换实现/测试替身（生产实现的三态永不返回 null）——
            // 按「未知」处理。绝不能落进下面"不是 FAILED、不是 NOT_COMPLETED ⇒ 全完成"
            // 的默认分支：那会把一次读不到当成"全完成"并**多发一次钱**（失败方向从
            // 「少发」翻成「多发」，与 A5 的整条修复方向相反）。
            if (result == null) {
                result = DatabaseManager.WordChallengeCompletion.READ_FAILED;
            }
            if (result != DatabaseManager.WordChallengeCompletion.READ_FAILED) {
                return result;
            }
            if (attempt < COMPLETION_READ_ATTEMPTS) {
                try {
                    Thread.sleep(COMPLETION_READ_BACKOFF_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return DatabaseManager.WordChallengeCompletion.READ_FAILED;
                }
            }
        }
        return result;
    }

    /**
     * M8：午夜跨天时清空全服的单词挑战已领标记。
     * 由 LetsLearnEnglish 的跨天定时任务调用（在 per-player 的
     * {@link #hasPendingWordRewardForDate} 补发之后执行，故补发用的是本次清空前的标记）。
     *
     * <p>R7-B ③：同时清空 per-player 当日单词挑战缓存 —— 跨天后 {@code date} 已不符，
     * 本就会失效重建，此处主动清是防"离线玩家条目"在 map 里滞留（与
     * {@link #unloadPlayer} 双保险，两者都不影响已付/未付判定真源：缓存 {@code rewardPaid}
     * 为 false 时仍回查 DB 台账）。</p>
     *
     * <p>L18：语义仍是<b>全清</b>（所有玩家、所有日期的内存"已付"位），不是"清某天"。
     * 按日期记录后本可以只裁掉早于昨日的日期，但这里保持原有"全清"时序 ——
     * 调用点在补发之后，清掉后安全性由 DB 台账（{@code daily_word_reward_claims}）兜底：
     * 内存未命中只会让下次判定多一条 SELECT，绝不会误判"未付"（DB 先被查、命中即回填）。
     * 全清还能顺带释放离线玩家条目，避免长期运行下 map 只增不减。</p>
     */
    public void clearAllWordChallengeClaims() {
        wordChallengeClaimedDates.clear();
        dailyWordStateCache.clear();
        // R11：失败待补写登记同样"全清"。理由：跨天后这些日期已不是"今天"，
        // 补写它们的价值只剩"给未来的自己留一条已付记录"；而保住它们的代价是
        // 补写逻辑要理解"哪天该补" —— 语义复杂度不值得。真发生了跨日写失败，
        // SEVERE 日志已经是可追溯的现场（见 persistWordRewardClaim）。
        pendingClaimLedgerWrites.clear();
    }

    /**
     * M8 跨天：统计玩家某日"已完成但未领取"的行为挑战数。
     * 供午夜作废提示用；昨日数据天然按 challenge_date 隔离，无需删行。
     */
    public int countUnclaimedForDate(UUID playerId, String date) {
        // 仓储内"锁内取连接"（R6-L1 / R3-H3 范式，原实现 :900 已有此顺序）；读失败返回 0。
        return repository.countUnclaimed(playerId, date);
    }

    /**
     * M8 跨天：指定日期的单词挑战是否"已全完成但奖励未到账"。
     *
     * <p>R-31 跨天补发：此前该日 pending 的奖励在午夜只被"作废提示"白白丢掉。现在若检测到
     * "已完成但未付"，就地补发（{@link #onAllWordChallengesComplete} 发放 flat 奖励并标记已付），
     * 补发成功即返回 false（不再算作废）。安全性关键：本方法<strong>只在午夜 rollover 的
     * per-online-player 循环里被调用</strong>（见 LetsLearnEnglish.scheduleMidnightRollover），
     * 此刻服务器自上个午夜起连续在线、{@code wordChallengeClaimedDates} 标记仍有效（尚未
     * {@link #clearAllWordChallengeClaims} 全清），因此"已付昨日"会命中 contains 直接返回 false、
     * 绝不会重复补发；重启跨午夜时该定时任务本就不触发，故也不会在重启后误补（无双发风险）。
     * 修复批 A（R10-H1）：DB 台账（{@code daily_word_reward_claims}）按日查重为第二道
     * 防线——即使内存标记失效（异常窗口），已付日期也命中返回 false、不重复补发。
     * 补发走主线程（rollover 非异步），对在线玩家发币安全。
     * <p>调用约束：必须在 {@link #clearAllWordChallengeClaims()} 之前调用（rollover 时序），否则 pending 判定失真。</p>
     *
     * <p><b>L18 修复</b>：第一道门原本是无日期 {@code Set<UUID>} 的 {@code contains}，
     * 只问"这个玩家今天付过没有"，与本方法的 {@code date}（要结算的那一天）无关。
     * rollover 延迟到玩家已领今日奖励之后执行时，今日的标记会让昨日 pending 被误判为
     * 已付 → 昨日奖励永久丢失。现内存标记按日期记录，三次判定（首门 / DB 台账 / 补发后
     * 复判）全部对齐 {@code date}。</p>
     *
     * @param playerId the player
     * @param date the day to settle (rollover 传昨日)
     * @return true 仅当该日仍"已完成但未补发成功"（真正作废，用于作废提示）
     */
    public boolean hasPendingWordRewardForDate(UUID playerId, String date) {
        // R11：先补写上一笔"钱已发、台账没写进去"的日期（含本 date 的可能性 ——
        // 它若补写成功，紧接着的 DB 台账判定就能命中，不会重复补发）。
        retryPendingClaimLedgerWrites(playerId);
        // L18：只认"该结算日"的已付标记。绝不能只问"这个玩家是否有任何已付记录"——
        // rollover 延迟时玩家可能已在同一进程内领过今日奖励，那与昨日是否已付无关。
        if (isWordChallengeClaimedOn(playerId, date)) {
            return false; // 该日已付，无 pending
        }
        if (plugin.getDatabaseManager().isDailyWordRewardClaimed(playerId, date)) {
            return false; // 该日已付（DB 真源；重启后内存清空亦不重复补发）
        }
        // A5/D2：三态 —— 「未全完成」与「读失败」不可同形。
        // 旧实现走 boolean 版，读失败 → false → 这里 return false ＝「该日没有 pending」，
        // 而<b>该日之后再无回访点</b>（hasPendingWordReward 只判今日状态）→
        // 「昨日确实全完成、奖励还没发」这笔钱被一次 SELECT 抖动永久抹掉。
        // 现有界重试（连接自愈通常在下次 getConnection() 即恢复），仍失败则：
        //   · 记 SEVERE（运维唯一能看出"没发是因为读不到库"的现场）；
        //   · 返回 true（仍算 pending / 作废），绝不谎报「无 pending」。
        DatabaseManager.WordChallengeCompletion completion =
                readWordChallengeCompletionWithRetry(playerId, date);
        if (completion == DatabaseManager.WordChallengeCompletion.READ_FAILED) {
            plugin.getLogger().severe("Word-challenge reward for " + playerId + " on " + date
                    + " could NOT be settled: the completion query FAILED after "
                    + COMPLETION_READ_ATTEMPTS + " attempts, so we do not know whether that"
                    + " day's word challenges were all completed. This is NOT \"no pending"
                    + " reward\" — treat it as an unresolved day. The reward is not paid out and"
                    + " this day will not be revisited by the rollover (it only inspects the"
                    + " current day). If the database is healthy again, the reward can still be"
                    + " paid manually; if this log repeats, the database connection is broken.");
            return true; // 保留 pending：不得因「读不到」而把该日判成「无 pending」
        }
        if (completion == DatabaseManager.WordChallengeCompletion.NOT_COMPLETED) {
            return false; // 未全完成，无 pending
        }
        // R-31：已完成但未付 —— 就地补发（rollover 循环中玩家在线）。
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            // 修复批 A（R10-H1）：补发按"昨日"标记（不能标成今日，否则误封今日挑战奖励）。
            onAllWordChallengesComplete(player, date);
            // L18：补发把 {@code date} 写进了按日期记录的内存位，故这里同日期复判 ——
            // 若发币失败（仍 pending）则返回 true（真正作废，用于作废提示）。
            return !isWordChallengeClaimedOn(playerId, date);
        }
        // 玩家不在线（rollover 循环理论上只遍历在线玩家，兜底）——无法发币，仍算作废。
        return true;
    }
}
