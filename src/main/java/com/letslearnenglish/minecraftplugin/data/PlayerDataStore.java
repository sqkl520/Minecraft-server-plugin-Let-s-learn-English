package com.letslearnenglish.minecraftplugin.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import com.letslearnenglish.minecraftplugin.data.DifficultWord;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;

/**
 * Player Data Store
 *
 * Manages all player-related data including:
 * - Word mastery levels
 * - Review schedules
 * - Learning statistics
 * - Session state tracking
 *
 * Uses an in-memory cache with database persistence.
 * All cache operations are thread-safe via ConcurrentHashMap.
 */
public class PlayerDataStore {

    /**
     * R9/P1-B：错题本"收录阈值"——与 {@code WordLearningManager.trackWrongAnswer}
     * 的"第 3 次答错才提示已加入错题本"文案保持单一真相。
     * {@code difficult_words} 表在首次答错即建行（计数器用途），展示侧必须按此阈值过滤。
     */
    public static final int WRONG_BOOK_MIN_WRONG_COUNT = 3;

    private final LetsLearnEnglish plugin;
    private final DatabaseManager databaseManager;

    private final Map<UUID, Map<String, Double>> wordMasteryCache;
    private final Map<UUID, Map<String, Long>> lastReviewTimeCache;
    private final Map<UUID, Map<String, Integer>> reviewCountCache;
    private final Map<UUID, Map<String, Integer>> correctCountCache;
    private final Map<UUID, Map<String, Integer>> incorrectCountCache;
    private final Map<UUID, PlayerStats> playerStatsCache;
    private final Set<UUID> activeSessionPlayers;
    private final Map<UUID, Set<String>> masteredWordsCache;
    private final Map<UUID, ProfileSettings> profileSettingsCache;
    private final Map<UUID, Map<String, DifficultWord>> difficultWordsCache;
    private final Map<UUID, Map<String, Integer>> wrongCountCache;

    /**
     * B2b（R3-b）：近期已答词窗口 —— 词（小写）→ 最后一次答题的 epoch ms。
     *
     * <p><b>刻意纯内存、不落库</b>：它只是"开局别马上重出刚答过的题"这一交互优化，
     * 窗口以分钟计（默认 30），退服 / 重启 / reload 后清空完全可接受；
     * 长期记忆由 {@code lastReviewTimeCache} 里的复习种子承担，两者互不替代。
     *
     * <p>因此本字段<b>不进</b> {@link #savePlayerDataInternal} 的写路径，也不参与
     * {@code dirtyWords} 脏标记体系 —— 它没有"待落盘"语义，把它塞进写路径只会
     * 让每轮保存多一次无意义的 SQL 绑定。
     */
    private final Map<UUID, Map<String, Long>> recentAnswerCache = new ConcurrentHashMap<>();

    // H10：玩家名缓存（join 时由主线程写入，savePlayerData 读出随 UPSERT 落盘）。
    // 不走 Bukkit API，保证异步保存线程安全；quit 时随 unload 清理。
    private final Map<UUID, String> playerNameCache;

    // ===== 今日计数器（内存懒清零，服务器时区自然日）=====
    // 每个条目记录 (日期, 计数)；读写时若日期 != 今天则视为 0 并顺手重置。
    // 重启/关服丢失是可接受的：这是防刷上限而非账本，账本以 DB 的 lifetime 字段为准。
    private final Map<UUID, TodayCounter> todayAnswerCountCache;
    private final Map<UUID, TodayCounter> todayAiRoundCache;

    // R-28：AI 对话累计次数缓存（镜像 player_data.conversation_count）。
    // join 时随 loadPlayerData 载入；incrementConversationCount 先改缓存再即时落库，
    // 让 AI_CHAT_10/50 成就计数跨重启不丢。
    private final Map<UUID, Integer> conversationCountCache;

    // Phase 3 S-A（H11）：错题本累计攻克词数缓存（镜像 player_data.wrong_book_cleared）。
    // join 时随 loadPlayerData 载入；incrementWrongBookCleared 先改缓存再即时落库
    // （绝对值 UPSERT，同 R-28 pattern），让 WRONG_BOOK_CLEARED 成就计数跨重启不丢。
    private final Map<UUID, Integer> wrongBookClearedCache;

    // ===== R7-A：脏标记（增量落库）=====
    // 语义：dirtyPlayers / dirtyWords 只记录"自上次成功落盘后有哪些玩家、哪些词被改过"，
    // 用来把周期全量保存（遍历整个 wordMasteryCache，7 列全写）降成"只写变过的词"。
    //
    // 不变式（违反即数据丢失且无报错）：
    //   ① 任何写入 word_progress 相关 5 个 map（mastery / correct / incorrect /
    //      lastReviewTime / reviewCount）或 player_data 统计的公开方法都必须调用
    //      markDirty / markWordDirty 之一；
    //   ② 标记只在 commit 成功之后清理，失败保留标记供下轮重试；
    //   ③ 全量路径（savePlayerData / unloadPlayerData / onDisable）是兜底，永远保留。
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();
    /** 只记"哪些词脏"；值为上限型集合（ConcurrentHashMap.newKeySet）。 */
    private final Map<UUID, Set<String>> dirtyWords = new ConcurrentHashMap<>();

    /**
     * 全量兜底周期：同一玩家每 12 次增量保存后强制走一次全量。
     *
     * <p>为什么用计数器而不是"每小时"：本类没有常驻时钟源，按时间需要再存一个
     * per-player 时间戳并在每次 saveDirty 里比较 {@code System.currentTimeMillis()}
     * ——多一个可变字段、且"服务器空闲一小时"时会白跑全量。计数器无状态、可复现，
     * 且在默认 5 分钟保存间隔下 = 约 1 小时一次全量，与时间方案等价但更简单。
     *
     * <p>为什么计数<b>按玩家</b>而非全局 {@code AtomicInteger}：全局计数器时
     * 每 12 次调用只有 1 个玩家走全量，在线人数越多、单个玩家轮到全量的间隔越长
     * （100 人时约 8 小时），兜底强度随人数稀释；按玩家计数则恒定 ≈ 1 小时/人。
     *
     * <p>兜底的意义：即便某个写入点漏标 markDirty（本流最高风险），最坏也只丢
     * {@value #FULL_SAVE_EVERY_N_DIRTY_SAVES}× 保存间隔（默认约 1 小时）内的这部分数据，
     * 而不是"永不落库"。
     *
     * <p><b>R7-C（P1-2）注意</b>：本常量只管"有写入的轮次"。只对活跃轮次计数的兜底对
     * 漏标玩家结构性不可达（漏标 = 没有标记 = 与空闲不可区分），因此另设
     * {@link #IDLE_FULL_SAVE_EVERY_N_ROUNDS} 覆盖空转轮次。
     */
    private static final int FULL_SAVE_EVERY_N_DIRTY_SAVES = 12;

    /**
     * R7-C（P1-2）：空闲兜底周期 —— 同一玩家连续 36 个保存轮次<b>没有任何脏标记</b>
     * 时，强制走一次全量。
     *
     * <p>为什么必须有这一档：漏标玩家（某个写入点忘记 markDirty）在类内看起来就是
     * "完全空闲的玩家"——两者的可观察状态一模一样。任何只在"有写入的轮次"计数的兜底，
     * 对漏标玩家都<b>永不触发</b>，这正是 R7-A 兜底的原始缺陷（计数器在第一道
     * {@code if (!hasWords && !hasStats) return;} 门后，漏标玩家永远过不了那道门）。
     *
     * <p>为什么是 36 而不是 12：如果空转轮也按 12 计，完全空闲的玩家会被每小时
     * 全量重写一遍，本流"空闲玩家不再被全量重写"的收益就退化成"11/12 轮零 SQL"
     * （相对改造前仍省约 92%，但把最能省的场景吃掉了）。取 36 = 12 的 3 倍：
     * <ul>
     *   <li>空转玩家 36 轮里 35 轮零 SQL（≈ 97% 的轮次不产生任何写）；</li>
     *   <li>漏标数据的最坏滞后从"永不纠正"收敛到 36 × 保存间隔（默认 5 分钟 → 约 3 小时）。</li>
     * </ul>
     * 取值依据："漏标"与"真空闲"不可区分时只能给上界；3 小时对 total_score 这类
     * 绝对值覆写的统计可接受，且 quit / onDisable / reload 的三处全量仍会兜住下线玩家。
     * 之所以不再加"疑似漏标"探测：那要求每个写入口自证"我写过但没标脏"，
     * 等于把兜底建在漏标者身上——逻辑上不成立。
     */
    private static final int IDLE_FULL_SAVE_EVERY_N_ROUNDS = 36;

    /** 每个玩家"有写入"的增量保存次数（触发 {@link #FULL_SAVE_EVERY_N_DIRTY_SAVES}）；随 unload/clearCache 清理。 */
    private final Map<UUID, Integer> dirtySaveCount = new ConcurrentHashMap<>();

    /**
     * R7-C（P1-2）：每个玩家"连续空转"的保存轮次（触发 {@link #IDLE_FULL_SAVE_EVERY_N_ROUNDS}）。
     * 有写入的轮次会把它清零，因此它衡量的是"连续空闲了多久"，不是"总共空闲了多少轮"。
     * 随 unload/clearCache 清理。
     */
    private final Map<UUID, Integer> idleSaveCount = new ConcurrentHashMap<>();

    /**
     * 每次 {@link #markDirty}/{@link #markWordDirty} 递增的写入序号（按玩家）。
     *
     * <p>为什么需要它：保存线程在循环里逐词读值，主线程可能在"读完某词"与"commit"之间
     * 又改同一个词。此时 {@code dirtyWords} 里的 add 是 no-op（词已在集合里），commit 后
     * 若照常清标记，那次更新的值就丢了 —— 而且要等到 12 轮后的全量兜底才会被纠正。
     * 因此：保存开始时记下序号，commit 后只有当序号<b>没有变化</b>才清标记；
     * 有变化就整批保留，下一轮重写（冗余写一次，绝不多丢一次）。
     */
    private final Map<UUID, AtomicLong> playerWriteSeq = new ConcurrentHashMap<>();

    public PlayerDataStore(LetsLearnEnglish plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.wordMasteryCache = new ConcurrentHashMap<>();
        this.lastReviewTimeCache = new ConcurrentHashMap<>();
        this.reviewCountCache = new ConcurrentHashMap<>();
        this.correctCountCache = new ConcurrentHashMap<>();
        this.incorrectCountCache = new ConcurrentHashMap<>();
        this.playerStatsCache = new ConcurrentHashMap<>();
        this.activeSessionPlayers = ConcurrentHashMap.newKeySet();
        this.masteredWordsCache = new ConcurrentHashMap<>();
        this.profileSettingsCache = new ConcurrentHashMap<>();
        this.difficultWordsCache = new ConcurrentHashMap<>();
        this.wrongCountCache = new ConcurrentHashMap<>();
        this.playerNameCache = new ConcurrentHashMap<>();
        this.todayAnswerCountCache = new ConcurrentHashMap<>();
        this.todayAiRoundCache = new ConcurrentHashMap<>();
        this.conversationCountCache = new ConcurrentHashMap<>();
        this.wrongBookClearedCache = new ConcurrentHashMap<>();
    }

    // ===== R7-A：脏标记写入辅助 =====

    /**
     * 标记"该玩家的 player_data 行（统计/玩家名）需要落盘"。
     *
     * <p>必须在修改 {@code playerStatsCache} / {@code playerNameCache} 之后调用。
     * 幂等、无锁、O(1)：{@code ConcurrentHashMap.newKeySet()} 的 add 是线程安全的。
     *
     * <p>R7-C（P2-2）：顺序同 {@link #markWordDirty} —— 先 add 再 bump 序号，
     * 建立"看到新序号 ⇒ 必然已看到标记"的不变式，防止保存线程按"无新写入"误清标记。
     */
    private void markDirty(UUID playerId) {
        if (playerId != null) {
            dirtyPlayers.add(playerId);
            bumpWriteSeq(playerId); // R7-C（P2-2）：必须最后
        }
    }

    /**
     * 标记"该玩家的某个词需要落盘"（word_progress 行），同时标记该玩家的 player_data 行。
     *
     * <p>必须在修改 mastery / correct / incorrect / lastReviewTime / reviewCount
     * 任一 map 的该词条目之后调用。词统一走 {@code toLowerCase()} 归一 —— 与
     * {@link #saveDirty} 的写入键同源（缓存里的键本身都已小写）。
     *
     * <p>为什么要同时 markDirty：增量保存若只写 word_progress 而漏写 player_data，
     * total_words_learned / total_sessions / total_score 就永远不会更新。
     *
     * <p><b>R7-C（P2-2）：标记顺序必须是"先 add、最后 bump 序号"</b>。
     * 目的是让"序号前进"成为"标记已就位"的充分条件：任何在取 seq 时看到新序号的保存线程，
     * 必然也已经能看到这次的标记。若反过来先 bump 再 add，保存线程可能读到已递增的序号、
     * 却读到尚未包含该词的集合，于是按"窗口内无新写入"清掉整包标记，而那条写入从未落库
     * （要等下次全量或 quit 才补上）。先 add 后 bump 消除了这个方向。
     *
     * <p>但顺序本身<b>不足以</b>封死全部窗口：同一个缺陷还有一半出在保存侧 —— 若增量路径
     * 在参数求值时就把 {@code dirtyWords} 拷贝成 ArrayList，那么"拷贝完成 → 取 seq"之间
     * 新增的词仍然会落在拷贝之外，却因 seq 已包含它们而被误判成已保存。那一半在
     * {@link #saveDirty} 改传活引用后封死，详见 {@link #savePlayerDataInternal}。
     *
     * <p>残余窗口：orphan add（add 落进已被 clearDirty 摘掉的集合）仍是纳秒级，由空闲兜底
     * （{@link #IDLE_FULL_SAVE_EVERY_N_ROUNDS}）与 quit 全量覆盖；这类窗口只造成
     * "多保留一轮"，不丢数据。
     */
    private void markWordDirty(UUID playerId, String word) {
        if (playerId == null || word == null) {
            return;
        }
        dirtyPlayers.add(playerId);
        dirtyWords.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                .add(word.toLowerCase());
        bumpWriteSeq(playerId); // R7-C（P2-2）：必须最后 —— 让"序号前进"蕴含"标记已就位"
    }

    /** 写入序号 +1；{@link #clearDirty} 用"保存前后序号是否相同"判断保存窗口内是否有新写入。 */
    private void bumpWriteSeq(UUID playerId) {
        playerWriteSeq.computeIfAbsent(playerId, k -> new AtomicLong()).incrementAndGet();
    }

    /** 读取当前写入序号（0 = 从未写过）。 */
    private long currentWriteSeq(UUID playerId) {
        AtomicLong seq = playerWriteSeq.get(playerId);
        return seq != null ? seq.get() : 0L;
    }

    /**
     * 今日答题计数器（int 版）。
     * 内部用 AtomicInteger + volatile 日期做懒清零；并发下允许极小误差（防刷上限场景可接受）。
     */
    private static class TodayCounter {
        private volatile LocalDate date;
        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);

        int increment() {
            LocalDate today = LocalDate.now();
            if (!today.equals(date)) {
                synchronized (this) {
                    if (!today.equals(date)) {
                        date = today;
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet();
        }

        int get() {
            LocalDate today = LocalDate.now();
            if (!today.equals(date)) {
                return 0;
            }
            return count.get();
        }

        /**
         * P0-4 红队补丁（R-P1-1）：退还一次预扣（失败请求未产生上游计费）。
         * 下限钳 0 —— 并发重复退还最坏是少退，不会出现负数导致"多送额度"。
         */
        int decrement() {
            LocalDate today = LocalDate.now();
            if (!today.equals(date)) {
                return 0; // 跨日无当日计数可退
            }
            return count.updateAndGet(v -> v > 0 ? v - 1 : 0);
        }

        /** Block C2（R2-M2）：当前计数归属的自然日；从未 seed/increment 时为 null。 */
        LocalDate date() {
            return date;
        }

        /**
         * Block C2（R2-M2）：从持久化真源回填计数（AI 轮次表重启/quit-rejoin 后回读）。
         * synchronized 双检（同 {@link #increment} 惯式）：若 seed 前已有并发线程把日期
         * 推到 d 并开始递增，则跳过覆盖，保住新的递增结果（seeded 值最坏差 1，
         * 防刷上限语义容忍）。
         */
        void seed(LocalDate d, int v) {
            if (!d.equals(date)) {
                synchronized (this) {
                    if (!d.equals(date)) {
                        date = d;
                        count.set(v);
                    }
                }
            }
        }
    }

    /**
     * 今日答题计数 +1（每次提交答案时调用，无论对错），返回递增后的值。
     * 按服务器时区自然日懒清零。
     */
    public int incrementTodayAnswerCount(UUID playerId) {
        return todayAnswerCountCache.computeIfAbsent(playerId, k -> new TodayCounter()).increment();
    }

    /**
     * 读取玩家今日答题计数。跨天后自动视为 0。
     */
    public int getTodayAnswerCount(UUID playerId) {
        TodayCounter counter = todayAnswerCountCache.get(playerId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 今日 AI 有效轮次 +1（由 AIChatManager 在一轮对话记为有效时调用），返回递增后的值。
     * 按服务器时区自然日懒清零。
     *
     * <p>Block C2（R2-M2）：持久化版 —— 内存 TodayCounter 重启/换服/quit-rejoin 即清零，
     * daily-round-limit（默认 30）唯一判据源可被无限绕过（API 成本面）。三层：
     * ① 日期非今日（或从未 seed）→ 先同步回读 DB 真源 seed（读失败按 0 降级）；
     * ② 内存 increment 立即生效（返回值语义不变，AIChatManager 消费）；
     * ③ 经调度器异步落库（调用点在 {@code supplyAsync} 的 commonPool 链内——
     * 与 R2-H1 修复纪律一致，绝不直调 JDBC；关服窗口静默弃）。
     */
    public int incrementTodayAiRound(UUID playerId) {
        LocalDate today = LocalDate.now();
        String todayStr = today.toString();
        TodayCounter counter = todayAiRoundCache.computeIfAbsent(playerId, k -> new TodayCounter());
        if (!today.equals(counter.date())) {
            // ① 内存态无今日真值（重启/quit-rejoin/跨日）→ 先回读 DB 真源
            int db = databaseManager.getAiRoundCount(playerId, todayStr);
            counter.seed(today, db);
        }
        int v = counter.increment(); // ② 内存立即生效
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                    databaseManager.incrementAiRound(playerId, todayStr)); // ③ 异步落库
        } catch (IllegalStateException ignored) {
            // 关服窗口调度器已关：静默弃（与 R2-H1 纪律一致）
        }
        return v;
    }

    /**
     * P0-4 红队补丁（R-P1-1）：退还一次"发请求前预扣"的今日 AI 轮次。
     *
     * <p>只用于开场白在<b>确定到达上游之前</b>就失败的场景（超时 / 连接失败 / 5xx / 429）——
     * 这类请求没有产生上游计费，必须退还，否则上游持续故障时玩家反复点场景会耗尽当天
     * daily-round-limit（且重启/重登不可恢复）。4xx 客户端错误不退：上游已受理并计费。
     *
     * <p>内存与 DB 同步递减；DB 走异步（同 increment 纪律，绝不直调 JDBC）。
     * 未 seed 过的玩家（date 为 null）直接忽略，避免凭空产生负计数。
     */
    public int decrementTodayAiRound(UUID playerId) {
        LocalDate today = LocalDate.now();
        TodayCounter counter = todayAiRoundCache.get(playerId);
        if (counter == null || !today.equals(counter.date())) {
            return 0; // 无当日计数可退
        }
        int v = counter.decrement();
        String todayStr = today.toString();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                    databaseManager.decrementAiRound(playerId, todayStr));
        } catch (IllegalStateException ignored) {
            // 关服窗口调度器已关：静默弃（与 R2-H1 纪律一致）
        }
        return v;
    }

    /**
     * 读取玩家今日 AI 有效轮次。跨天后自动视为 0。
     *
     * <p>Block C2（R2-M2）：cache miss 或当日计数器未载入时，同步回读
     * {@code ai_round_counters} 真源并 seed（先例：{@link #getConversationCount}
     * "miss 时同步加载再读"模式；本处仅一条 SELECT）。quit/rejoin 后首次调用即走
     * 此路径读回真值——正是本修复要闭合的绕过路径。读取点均在异步线程
     * （AsyncPlayerChatEvent 线程 / chatAsync 异步链），同步 DB 读持 connectionLock 可接受。
     */
    public int getTodayAiRound(UUID playerId) {
        LocalDate today = LocalDate.now();
        TodayCounter counter = todayAiRoundCache.get(playerId);
        if (counter == null || !today.equals(counter.date())) {
            counter = todayAiRoundCache.computeIfAbsent(playerId, k -> new TodayCounter());
            if (!today.equals(counter.date())) {
                // 内存态无今日真值 → 同步回读 DB 真源（读失败按 0 降级）
                int db = databaseManager.getAiRoundCount(playerId, today.toString());
                counter.seed(today, db);
            }
        }
        return counter.get();
    }

    /**
     * R-28：读取玩家 AI 对话累计次数（供 AI_CHAT_10/50 成就判定）。
     * 缓存未命中时懒加载（loadPlayerData 会填入 conversation_count）。
     */
    public int getConversationCount(UUID playerId) {
        Integer v = conversationCountCache.get(playerId);
        if (v == null) {
            loadPlayerData(playerId);
            v = conversationCountCache.get(playerId);
        }
        return v != null ? v : 0;
    }

    /**
     * R-28：AI 对话累计次数 +1，返回递增后的值。
     *
     * <p>缓存为权威：先确保已从 DB 载入基数，+1 后即时经
     * {@code DatabaseManager.setConversationCount} 落库（绝对值 UPSERT）。
     * 之所以即时落库而不是等 savePlayerData，是因为 quit 时
     * {@code ChatScenarioListener.endConversationOnQuit}（触发本方法）与
     * {@code PlayerJoinListener} 的 unloadPlayerData（落盘+清缓存）是同一
     * PlayerQuitEvent 上的两个独立监听器，执行顺序不保证；若只改缓存等 save，
     * 当 unload 先跑时这次 +1 会丢。即时落库使成就计数与监听器顺序无关。
     */
    public int incrementConversationCount(UUID playerId) {
        if (!conversationCountCache.containsKey(playerId)) {
            loadPlayerData(playerId);
        }
        int newVal = conversationCountCache.merge(playerId, 1, Integer::sum);
        databaseManager.setConversationCount(playerId, newVal);
        return newVal;
    }

    /**
     * Phase 3 S-A（H11)：错题本累计攻克词数 +1，返回递增后的值。
     *
     * <p>缓存为权威：先确保已从 DB 载入基数，+1 后即时经
     * {@code DatabaseManager.setWrongBookCleared} 落库（绝对值 UPSERT）。
     * 即时落库理由同 R-28（quit 监听器顺序不保证，绝对值写使计数与顺序无关）。
     * 仅由练习答对移出分支调用（手动删除不计数）。
     */
    public int incrementWrongBookCleared(UUID playerId) {
        if (!wrongBookClearedCache.containsKey(playerId)) {
            loadPlayerData(playerId);
        }
        int newVal = wrongBookClearedCache.merge(playerId, 1, Integer::sum);
        databaseManager.setWrongBookCleared(playerId, newVal);
        return newVal;
    }

    /**
     * Phase 3 S-A（H11)：读取玩家错题本累计攻克词数（供 WRONG_BOOK_CLEARED 成就判定）。
     * 缓存未命中时懒加载（loadPlayerData 会填入 wrong_book_cleared）。
     */
    public int getWrongBookCleared(UUID playerId) {
        Integer v = wrongBookClearedCache.get(playerId);
        if (v == null) {
            loadPlayerData(playerId);
            v = wrongBookClearedCache.get(playerId);
        }
        return v != null ? v : 0;
    }

    /**
     * 记录一次学习会话到 player_sessions 表。
     * 同一天多次会话会合并（UPSERT，累加 words/correct/score）。
     *
     * <p>P3-05：UPSERT 不更新 mode 列——mode 记录当日首场模式（同日合并语义）；
     * 如需统计模式分布需先定口径（P3-05；报表侧同口径见 ReportGenerator 的 PF-RPT-04）。
     *
     * <p>Phase 3 S-A（H8）：review_words 为复习会话专用列——mode=review 时累加
     * correctCount（复习答对词数），否则 0。零签名变更（mode 已是入参），供
     * REVIEW_STREAK_7 成就按"当天复习答对 ≥1 词"判定。
     */
    public void recordSession(UUID playerId, int wordsCount, int correctCount, long score, String mode) {
        String tablePrefix = databaseManager.getTablePrefix();
        java.time.LocalDate today = java.time.LocalDate.now();
        String dateStr = today.toString();
        int reviewWords = "review".equalsIgnoreCase(mode) ? correctCount : 0;

        String sql = databaseManager.isSQLite()
                ? "INSERT INTO " + tablePrefix + "player_sessions (uuid, session_date, words_count, correct_count, score, mode, review_words) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(uuid, session_date) DO UPDATE SET words_count = words_count + excluded.words_count, correct_count = correct_count + excluded.correct_count, score = score + excluded.score, review_words = review_words + excluded.review_words"
                : "INSERT INTO " + tablePrefix + "player_sessions (uuid, session_date, words_count, correct_count, score, mode, review_words) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE words_count = words_count + VALUES(words_count), correct_count = correct_count + VALUES(correct_count), score = score + VALUES(score), review_words = review_words + VALUES(review_words)";

        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, dateStr);
            stmt.setInt(3, wordsCount);
            stmt.setInt(4, correctCount);
            stmt.setLong(5, score);
            stmt.setString(6, mode);
            stmt.setInt(7, reviewWords);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to record session: " + playerId, e);
        }
        }
    }

    /**
     * 获取玩家最近 N 天的学习天数列表（用于日历展示）。
     * 返回 Map<日期字符串, 当天的words_count>。
     */
    public Map<String, Integer> getSessionDays(UUID playerId, int daysBack) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT session_date, words_count FROM " + tablePrefix + "player_sessions WHERE uuid = ? AND session_date >= ? ORDER BY session_date ASC";

        java.time.LocalDate since = java.time.LocalDate.now().minusDays(daysBack);
        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, since.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("session_date"), rs.getInt("words_count"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load session days: " + playerId, e);
        }
        }
        return result;
    }

    /**
     * Load a player's data from the database into cache.
     *
     * <p>R7-A 防御：本方法会<b>无条件覆盖</b> 5 个 word_progress map 与 playerStatsCache。
     * 若该玩家此刻还有未落盘的脏数据，覆盖就等于把内存里的最新值直接丢掉
     * （而脏标记随后会被下一次保存"兑现"成 DB 的旧值 —— 静默丢数据）。
     * 因此进入覆盖前，若发现有脏标记，先全量落盘一次，保证"读回来的不旧于内存"。
     * 正常路径（join 首次加载 / reload 清空后重载）此时没有脏标记，不会多一次写。
     */
    public void loadPlayerData(UUID playerId) {
        if (dirtyPlayers.contains(playerId) || dirtyWords.containsKey(playerId)) {
            savePlayerData(playerId);
        }
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT word, mastery, correct_count, incorrect_count, last_review_time, review_count FROM "
                + tablePrefix + "word_progress WHERE uuid = ?";

        Connection conn;
        synchronized (databaseManager.getConnectionLock()) {
        conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                Map<String, Double> masteryMap = new ConcurrentHashMap<>();
                Map<String, Long> reviewTimeMap = new ConcurrentHashMap<>();
                Map<String, Integer> reviewCountMap = new ConcurrentHashMap<>();
                Map<String, Integer> correctMap = new ConcurrentHashMap<>();
                Map<String, Integer> incorrectMap = new ConcurrentHashMap<>();

                while (rs.next()) {
                    String word = rs.getString("word");
                    masteryMap.put(word, rs.getDouble("mastery"));
                    reviewTimeMap.put(word, rs.getLong("last_review_time"));
                    reviewCountMap.put(word, rs.getInt("review_count"));
                    correctMap.put(word, rs.getInt("correct_count"));
                    incorrectMap.put(word, rs.getInt("incorrect_count"));
                }

                wordMasteryCache.put(playerId, masteryMap);
                lastReviewTimeCache.put(playerId, reviewTimeMap);
                reviewCountCache.put(playerId, reviewCountMap);
                correctCountCache.put(playerId, correctMap);
                incorrectCountCache.put(playerId, incorrectMap);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player data: " + playerId, e);
            wordMasteryCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
            lastReviewTimeCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
            reviewCountCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
            correctCountCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
            incorrectCountCache.putIfAbsent(playerId, new ConcurrentHashMap<>());
        }
        }

        // 从 word_progress 表重建玩家统计数据
        PlayerStats stats = new PlayerStats();
        Map<String, Integer> correctMap = correctCountCache.get(playerId);
        Map<String, Integer> incorrectMap = incorrectCountCache.get(playerId);
        if (correctMap != null) {
            for (int c : correctMap.values()) stats.addCorrect(c);
        }
        if (incorrectMap != null) {
            for (int c : incorrectMap.values()) stats.addIncorrect(c);
        }

        // 从 player_data 表加载 sessions 和 score
        String statsSql = "SELECT total_sessions, total_score, conversation_count, wrong_book_cleared FROM " + tablePrefix + "player_data WHERE uuid = ?";
        int conversationCount = 0;
        int wrongBookCleared = 0;
        // F1-20：本块与上方锁块之间已释放过锁——重连（initialize）可能已替换
        // Connection 引用，原实现在此处复用锁外取得的 conn。照 DatabaseManager
        // E-04 范式：上个锁块已释放，重连可能已替换引用，此处锁内重取。
        synchronized (databaseManager.getConnectionLock()) {
        conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(statsSql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int sessions = rs.getInt("total_sessions");
                    long score = rs.getLong("total_score");
                    stats.setSessions(sessions);
                    stats.setScore(score);
                    conversationCount = rs.getInt("conversation_count"); // R-28
                    wrongBookCleared = rs.getInt("wrong_book_cleared"); // Phase 3 S-A（H11）
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player stats: " + playerId, e);
        }
        }
        // R7-A：先挂脏标记钩子再入缓存 —— 入缓存后任何线程都可能立刻拿到引用去改，
        // 钩子必须已就位（挂接本身不标脏，"从 DB 读入"不需要落盘）。
        stats.setDirtyHook(() -> markDirty(playerId));
        playerStatsCache.put(playerId, stats);
        conversationCountCache.put(playerId, conversationCount); // R-28
        wrongBookClearedCache.put(playerId, wrongBookCleared); // Phase 3 S-A（H11）

        // Load difficult words / wrong book data
        loadDifficultWordsFromDB(playerId);

        masteredWordsCache.remove(playerId);
    }

    /**
     * Load difficult words from database into cache.
     */
    private void loadDifficultWordsFromDB(UUID playerId) {
        List<DifficultWord> words = databaseManager.getDifficultWords(playerId);
        Map<String, DifficultWord> wordMap = new ConcurrentHashMap<>();
        Map<String, Integer> countMap = new ConcurrentHashMap<>();
        for (DifficultWord dw : words) {
            wordMap.put(dw.getWord().toLowerCase(), dw);
            countMap.put(dw.getWord().toLowerCase(), dw.getWrongCount());
        }
        difficultWordsCache.put(playerId, wordMap);
        wrongCountCache.put(playerId, countMap);
    }

    /**
     * R9/P1-B 收口：错题本的"收录阈值"视图（{@code wrong_count >= 3}）。
     *
     * <p>为什么需要它：{@link #incrementWrongCount} 是 UPSERT，<b>首次答错就会在
     * {@code difficult_words} 建行</b>（wrong_count=1）——它同时承担"累计错次计数器"职责。
     * 而 GUI 侧只该展示"够格进错题本"的词（与 {@code WordLearningManager.trackWrongAnswer}
     * 的"第 3 次才提示已加入"文案一致）。旧实现直接展示全部行，导致只错 1 次的词也出现在
     * 错题本里，与玩家被告知的规则自相矛盾。
     *
     * <p>本方法只做只读过滤，不改动缓存与 DB；{@link #incrementWrongCount} 的计数语义
     * 与 {@code decrementWrongCount} 的 {@code wrong_count > 1} 边界保持不变。
     *
     * @return 已收录（错次 ≥ {@value #WRONG_BOOK_MIN_WRONG_COUNT}）的词，按错次降序
     */
    public List<Map.Entry<String, DifficultWord>> getWrongBookEntries(UUID playerId) {
        Map<String, DifficultWord> all = getDifficultWordsData(playerId);
        List<Map.Entry<String, DifficultWord>> entries = new ArrayList<>();
        for (Map.Entry<String, DifficultWord> entry : all.entrySet()) {
            DifficultWord dw = entry.getValue();
            if (dw != null && dw.getWrongCount() >= WRONG_BOOK_MIN_WRONG_COUNT) {
                entries.add(entry);
            }
        }
        entries.sort((a, b) -> Integer.compare(
                b.getValue().getWrongCount(), a.getValue().getWrongCount()));
        return entries;
    }

    /**
     * Get all difficult words for a player from cache, loading from DB if needed.
     * Returns a map of word → DifficultWord data.
     */
    public Map<String, DifficultWord> getDifficultWordsData(UUID playerId) {
        Map<String, DifficultWord> cached = difficultWordsCache.get(playerId);
        if (cached == null) {
            loadDifficultWordsFromDB(playerId);
            cached = difficultWordsCache.get(playerId);
        }
        return cached != null ? cached : new ConcurrentHashMap<>();
    }

    /**
     * Get the set of difficult word strings for a player.
     */
    public Set<String> getDifficultWords(UUID playerId) {
        return getDifficultWordsData(playerId).keySet();
    }

    /**
     * Get the wrong count for a specific word.
     */
    public int getWrongCount(UUID playerId, String word) {
        Map<String, Integer> counts = wrongCountCache.get(playerId);
        if (counts == null) {
            loadDifficultWordsFromDB(playerId);
            counts = wrongCountCache.get(playerId);
        }
        if (counts != null) {
            Integer count = counts.get(word.toLowerCase());
            if (count != null) return count;
        }
        return 0;
    }

    /**
     * Increment wrong count for a word. If it reaches the threshold, adds to difficult words.
     * Runs DB increment asynchronously. Returns the new count.
     */
    public int incrementWrongCount(UUID playerId, String word) {
        String wordLower = word.toLowerCase();

        // Update in-memory wrong count cache
        Map<String, Integer> counts = wrongCountCache.computeIfAbsent(playerId,
                k -> new ConcurrentHashMap<>());
        int newCount = counts.merge(wordLower, 1, Integer::sum);

        // Async DB increment
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            databaseManager.incrementWrongCount(playerId, wordLower);
        });

        return newCount;
    }

    /**
     * Mark a word as difficult (add to wrong book cache).
     */
    public void addDifficultWord(UUID playerId, String word) {
        String wordLower = word.toLowerCase();
        Map<String, DifficultWord> words = difficultWordsCache.computeIfAbsent(playerId,
                k -> new ConcurrentHashMap<>());
        int count = getWrongCount(playerId, wordLower);
        words.put(wordLower, new DifficultWord(wordLower, Math.max(count, 3), System.currentTimeMillis()));
    }

    /**
     * Remove a word from the difficult words cache and database.
     */
    public void removeDifficultWord(UUID playerId, String word) {
        String wordLower = word.toLowerCase();
        Map<String, DifficultWord> words = difficultWordsCache.get(playerId);
        if (words != null) {
            words.remove(wordLower);
        }
        Map<String, Integer> counts = wrongCountCache.get(playerId);
        if (counts != null) {
            counts.remove(wordLower);
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            databaseManager.removeDifficultWord(playerId, wordLower);
        });
    }

    /**
     * Decrement wrong count by 1 (on practice correct). If reaches 0, remove from book.
     *
     * <p>Block C2（A3/R3-M2）：缓存 merge 后 ≤0 即 remove，缓存地板为 0；DB 侧走
     * {@code DatabaseManager.decrementWrongCount} 的原子条件 SQL（{@code wrong_count > 1}
     * 才减、否则返回 false → 本处删行），与缓存两值逐值对齐（cache 3→2 ↔ db 3→2；
     * cache 1→0 删 ↔ db 1→删）。残余乱序窗口：同一玩家的 increment 异步任务整体晚于
     * 本 decrement 任务执行时词会留书——概率极低（跨秒提交两任务乱序），最坏结果 =
     * 词留书（可重试清除，无数据损坏）；executor 保序队列会同时改动 increment 热路径，
     * 收益/风险不成比，接受此窗口。
     */
    public void decrementWrongCount(UUID playerId, String word) {
        String wordLower = word.toLowerCase();
        Map<String, Integer> counts = wrongCountCache.get(playerId);
        if (counts != null) {
            int newCount = counts.merge(wordLower, -1, Integer::sum);
            if (newCount <= 0) {
                counts.remove(wordLower);
                Map<String, DifficultWord> words = difficultWordsCache.get(playerId);
                if (words != null) {
                    words.remove(wordLower);
                }
            }
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!databaseManager.decrementWrongCount(playerId, wordLower)) {
                // 计数已达 1（或行不存在）→ 移出（幂等）；原子 SQL 已消除读回分裂窗口
                databaseManager.removeDifficultWord(playerId, wordLower);
            }
        });
    }

    /**
     * 全量保存：把该玩家在缓存里的<b>全部</b>词（{@code wordMasteryCache}）7 列 UPSERT
     * 进 {@code word_progress}，再全量写 {@code player_data} 行。
     *
     * <p>Uses UPSERT (INSERT OR REPLACE for SQLite, INSERT...ON DUPLICATE KEY UPDATE for MySQL)
     * in a single transaction to eliminate the DELETE+INSERT double-operation.
     *
     * <p>F0-02: 整个事务收进 {@code synchronized (connectionLock)}——setAutoCommit(false)
     * 会改变共享连接的状态，不持锁就会污染其他线程的读写。
     *
     * <p><b>R7-A 语义裁定（刻意保持"全量"）</b>：本方法<b>不</b>改成增量。四个既有调用点
     * 的语义都是"确保落盘"——{@code LetsLearnEnglish:onDisable}（关服）、
     * {@code HotReloadManager.flushOnlinePlayerData}（reload 前）、
     * {@link #unloadPlayerData}（quit）——这些窗口之后缓存就被清空/进程就退出，
     * 任何"只写脏词"的乐观优化一旦漏标（本流最高风险）就永久丢数据。因此：
     * <ul>
     *   <li>{@code savePlayerData} = 全量兜底（语义与 R7-A 之前逐字节一致）；</li>
     *   <li>{@link #saveDirty} = 增量，供<b>周期保存任务</b>使用。</li>
     * </ul>
     * 两者共用同一段写库实现（{@link #savePlayerDataInternal}），SQL 字符串由同一个
     * {@link #buildUpsertSql} / {@link #buildPlayerDataUpsertSql} 生成 —— 不存在
     * "增量写与全量写两套语义"的分叉。
     *
     * <p>提交成功后会清掉该玩家的脏标记（标记已兑现）；失败则<b>保留</b>标记供下轮重试。
     */
    public void savePlayerData(UUID playerId) {
        savePlayerDataInternal(playerId, null);
    }

    /**
     * R7-A：增量保存 —— 只写 {@link #dirtyWords} 里登记过的词 + {@code player_data} 行。
     *
     * <p>供<b>周期保存任务</b>使用（R7-C 已接线：{@code LetsLearnEnglish.startScheduledTasks}
     * 的 auto-save 定时器调用本方法）。
     * 不被 {@link #unloadPlayerData} / {@code onDisable} / reload 使用 —— 那三处必须全量。
     *
     * <p>行为：
     * <ol>
     *   <li>玩家有脏标记 → 计一次"活跃轮次"；每第 {@value #FULL_SAVE_EVERY_N_DIRTY_SAVES}
     *       次成功增量保存 → 该玩家走一次全量（见 {@link #FULL_SAVE_EVERY_N_DIRTY_SAVES}）；</li>
     *   <li>玩家完全没有脏标记 → 计一次"空转轮次"，连续 {@value #IDLE_FULL_SAVE_EVERY_N_ROUNDS}
     *       轮才走一次全量（见 {@link #IDLE_FULL_SAVE_EVERY_N_ROUNDS}）；其余轮次<b>零 SQL</b>；</li>
     *   <li>否则只 UPSERT 脏词。</li>
     * </ol>
     *
     * <p>R7-C（P1-2）：原实现把"无标记"直接 {@code return}，兜底计数器只在有标记时递增 ——
     * 但"漏标"的玩家恰恰没有标记，永远走不到计数器，兜底对其结构性不可达。现在空转
     * 轮次也有独立计数（更长的周期），兜底强度从"永不"恢复到"最多滞后 36 轮"。
     *
     * <p>写库失败时不清标记：下一轮周期保存会重试同样的词（幂等 UPSERT），不会丢数据。
     */
    public void saveDirty(UUID playerId) {
        Set<String> words = dirtyWords.get(playerId);
        boolean hasWords = words != null && !words.isEmpty();
        boolean hasStats = dirtyPlayers.contains(playerId);
        if (!hasWords && !hasStats) {
            // R7-C（P1-2）：空转轮次计数 —— 有写入的轮次会把它清零，所以它衡量的是
            // "连续空闲了多久"。漏标玩家在这里与真空闲玩家不可区分，只能靠这一档兜底。
            int idle = idleSaveCount.merge(playerId, 1, Integer::sum);
            if (idle >= IDLE_FULL_SAVE_EVERY_N_ROUNDS) {
                idleSaveCount.put(playerId, 0);
                savePlayerDataInternal(playerId, null);
            }
            return;
        }
        idleSaveCount.remove(playerId); // R7-C（P1-2）：本轮回暖，重置空转计数
        // 按玩家计数（见 FULL_SAVE_EVERY_N_DIRTY_SAVES 的说明）
        int n = dirtySaveCount.merge(playerId, 1, Integer::sum);
        if (n % FULL_SAVE_EVERY_N_DIRTY_SAVES == 0) {
            // 漏标兜底：该玩家全量重写一次
            savePlayerDataInternal(playerId, null);
        } else {
            // hasWords=false（只有 player_data 脏）时传空集合：只写 player_data 行。
            // R7-C 追补（P2-2）：这里必须传<b>活引用</b> {@code words}，绝不能
            // {@code new ArrayList<>(words)} —— 参数求值发生在方法体之前，而
            // {@code seqAtSnapshot} 在方法体第一行才读，拷贝会把"拷贝完成 → 序号采集"
            // 之间新增的词挡在本次写入之外（详见 savePlayerDataInternal 的窗口说明）。
            // 活引用由该方法在 seq 采集<b>之后</b>才开始迭代，且 newKeySet() 的迭代是
            // 弱一致的（不会 CME），因此不需要先拷贝成快照。
            savePlayerDataInternal(playerId, hasWords ? words : Collections.<String>emptyList());
        }
    }

    /**
     * 保存实现（全量 / 增量共用）。
     *
     * @param wordFilter {@code null} = 全量（遍历整个 masteryMap 的 key）；
     *                   非 null = 只写这些词（增量）。增量路径传的是 {@code dirtyWords}
     *                   的<b>活引用</b>（弱一致集合），本方法在 {@code seqAtSnapshot}
     *                   采集<b>之后</b>才第一次迭代它 —— 这是封死 P2-2 窗口的前提，
     *                   见下方注释，不要改回"外层先拷贝成 ArrayList"。
     *                   player_data 快照在 {@code connectionLock} 内取（R7-C/P1-3）。
     */
    private void savePlayerDataInternal(UUID playerId, Collection<String> wordFilter) {
        // R7-C（P1-3）：写入序号必须<b>先于</b>任何取值采集。保存线程用"保存前后序号是否
        // 相同"判断窗口内有没有新写入（见 clearDirty）；若先取值再记序号，取值之后、
        // 记序号之前的那次写入会被误判成"已包含在快照里"，commit 后标记被清而新值丢失。
        //
        // R7-C 追补（P2-2）：本行与下面"第一次迭代 wordFilter"的先后顺序是正确性前提。
        // 反例（改传活引用之前的真实窗口）：saveDirty 在<b>参数求值</b>时先执行
        // {@code new ArrayList<>(words)}（t0），随后主线程 markWordDirty(W) 完成 add+bump
        // 序号 4→5（t1），本方法才读 seqAtSnapshot 得到 <b>5</b>（t2）—— 序号已经包含
        // 那次写入，但拷贝里没有 W，本次只写下旧词（t3）；commit 后 clearDirty 比较
        // currentWriteSeq()==5 == seqAtSnapshot==5，判定"窗口内无新写入"而整包清空（t4）。
        // 结果是 W 的标记被清、数据也从未落库，只能等 12 个活跃轮次 / 36 个空闲轮次的
        // 全量，或玩家 quit 的 unloadPlayerData 才补上。注意这不是"多保留一轮"，
        // 而是<b>标记丢失 + 数据滞后</b>。
        //
        // 封死方式：增量路径传 newKeySet() 的活引用，本方法在 seq 采集<b>之后</b>才开始
        // 迭代（弱一致，不会 CME）。于是 seq 之后发生的写入要么被本次迭代看到并写入，
        // 要么没被看到但序号已变、clearDirty 整包保留 —— 两种分支都不会"清标记却没写值"。
        final long seqAtSnapshot = currentWriteSeq(playerId);

        String tablePrefix = databaseManager.getTablePrefix();
        String upsertSql = buildUpsertSql(tablePrefix);

        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        // R7-C（P1-3）：<b>所有取值都必须在这里、持锁之后做</b>。
        // 原实现把 masteryMap 与 player_data 快照都在锁外读，等于在"取值"与"抢锁写库"
        // 之间留了一个任意长的排队窗口：
        //   ① player_data 路径：异步保存取完旧快照后排队 → 玩家结算一局并 quit，
        //      主线程的 savePlayerData 抢到锁先写入新值并清缓存 → 本线程获锁后用旧快照
        //      覆盖，total_score / total_sessions 永久少一局。这两个值只存在于 player_data，
        //      下次 join 不会从 word_progress 重建 —— 不可自愈的数据损坏（本次回归）。
        //   ② word_progress 路径：锁外读到非 null 的 masteryMap（旧引用仍带着词表），
        //      但 quit 已清空 correct/incorrect/review 四个 map → 循环里 getIntValue 回落 0，
        //      用"正确 mastery + 全 0 计数"覆盖刚由 quit 全量写好的行。
        // 进锁后取值的额外好处：masteryMap == null && snap == null 的早退判断（清已下线
        // 玩家的 dirty 残留）看到的是"清缓存之后"的真实状态，而不是排队前的旧状态。
        final Map<String, Double> masteryMap = wordMasteryCache.get(playerId);
        final PlayerDataSnapshot snap = snapshotPlayerData(playerId);
        if (masteryMap == null && snap == null) {
            // 缓存已彻底清空（quit/reload 后）：内存里没有任何可落盘的东西。
            // 清掉标记，避免 dirty 集合为已下线玩家无限积累。
            dirtyPlayers.remove(playerId);
            dirtyWords.remove(playerId);
            return;
        }
        // 写库用的词集合（全量 = 整个 masteryMap 的 key；增量 = 调用方传入的脏词集合）。
        // 增量路径这里是 dirtyWords 的<b>活引用</b>，迭代发生在下方 for 循环 —— 即 seq
        // 采集之后，正是 R7-C 追补要的顺序。newKeySet() 迭代弱一致，遍历期间新增的词
        // 可能被看到也可能不被看到，两种结果都安全（见方法头注释）。
        // masteryMap 为 null（只剩统计/玩家名可写）时按"无词可写"处理 —— 仍要落
        // player_data 行，否则 total_score 这类只由 PlayerStats 承载的数据会永远滞后。
        final Collection<String> writtenWords;
        if (wordFilter != null) {
            writtenWords = wordFilter;
        } else if (masteryMap != null) {
            // 全量路径必须拷贝 keySet：迭代期间 masteryMap 仍可能被主线程写入，
            // 而全量的语义是"一个确定的时刻"，不能把并发新增的词算进这一轮。
            writtenWords = new ArrayList<>(masteryMap.keySet());
        } else {
            writtenWords = Collections.emptyList();
        }
        try {
            conn.setAutoCommit(false);

            try (PreparedStatement upsertStmt = conn.prepareStatement(upsertSql)) {

                int staged = 0;
                for (String word : writtenWords) {
                    if (masteryMap == null) {
                        break; // 无词可写（只剩 player_data 行）
                    }
                    Double mastery = masteryMap.get(word);
                    if (mastery == null) {
                        // 全量路径不会出现（keySet 来自同一 map）；增量路径可能是
                        // "只有 recordReview / resetReview 标记、但该词从未进过 masteryMap"的
                        // 词 —— 没有 mastery 就没有可写的行（该列是行的主体），跳过；
                        // 绝不拿 0.0 覆盖 DB 真值。
                        //
                        // R7-C（P2-1）：这里<b>不</b>把词重新加回 dirtyWords。理由：
                        // 重新加回只会让它永远满足 hasWords，每轮周期保存都白起一次事务
                        // （只能写 player_data 行），却仍然写不出这个词，直到 updateWordProgress
                        // 建出 mastery —— 而那一步本身就会 markWordDirty。也就是说"重新加回"
                        // 既不加速落盘，也不比"等 updateWordProgress 再标"多保住任何数据，
                        // 只会把"11/12 轮零 SQL"的收益持续吃掉。故：丢弃标记 + 留痕。
                        // 正常调用方（ReviewScheduler / WordLearningManager）都先走
                        // updateWordProgress，此分支只在未来新调用方误用时出现。
                        plugin.getLogger().log(Level.FINE,
                                "Skip persisting review state for word without mastery "
                                        + "(will be written once updateWordProgress creates it): "
                                        + playerId + " / " + word);
                        continue;
                    }

                    upsertStmt.setString(1, playerId.toString());
                    upsertStmt.setString(2, word);
                    upsertStmt.setDouble(3, mastery);
                    upsertStmt.setInt(4, getIntValue(correctCountCache.get(playerId), word));
                    upsertStmt.setInt(5, getIntValue(incorrectCountCache.get(playerId), word));
                    upsertStmt.setLong(6, getLongValue(lastReviewTimeCache.get(playerId), word));
                    upsertStmt.setInt(7, getIntValue(reviewCountCache.get(playerId), word));

                    upsertStmt.addBatch();
                    staged++;
                }

                // 空 batch 不调 executeBatch：部分驱动对空 batch 的行为未定义；
                // "只有 player_data 脏"的增量保存会走到这里（staged == 0）。
                if (staged > 0) {
                    upsertStmt.executeBatch();
                }

                // 保存玩家统计数据到 player_data 表
                // R7-A：全量与增量都写这一行（只有一行，增量写它没有任何额外代价；
                // 不写就会让 total_words_learned / total_sessions / total_score 永久滞后）。
                if (snap != null) {
                    String statsSql = buildPlayerDataUpsertSql(tablePrefix);
                    try (PreparedStatement statsStmt = conn.prepareStatement(statsSql)) {
                        statsStmt.setString(1, playerId.toString());
                        statsStmt.setString(2, snap.name);
                        statsStmt.setInt(3, snap.words);
                        statsStmt.setInt(4, snap.sessions);
                        statsStmt.setLong(5, snap.score);
                        statsStmt.setLong(6, System.currentTimeMillis());
                        statsStmt.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            // 失败：不清标记 —— 下一轮 saveDirty 会用同样的词重试（UPSERT 幂等）。
            plugin.getLogger().log(Level.WARNING, "Failed to save player data: " + playerId, e);
            return;
        }
        }

        // 只有 commit 成功才会走到这里。
        clearDirty(playerId, seqAtSnapshot);
    }

    /** R7-A：取 player_data 行的写库快照；{@code playerStatsCache} 无该玩家时返回 null（不写行）。 */
    private PlayerDataSnapshot snapshotPlayerData(UUID playerId) {
        PlayerStats stats = playerStatsCache.get(playerId);
        if (stats == null) {
            return null;
        }
        return new PlayerDataSnapshot(playerNameCache.get(playerId),
                stats.getTotalWords(), stats.getTotalSessions(), stats.getTotalScore());
    }

    /**
     * commit 成功之后清脏标记。**全有或全无**：只有当保存窗口内没有任何新写入时才清。
     *
     * <p>为什么不做"按词精细清理"（只移除本次写过的词）：那样会漏掉一种情况 ——
     * 保存线程读到 {@code apple=0.4} 后、commit 前，主线程把它改成 0.6。此时
     * "apple" 本来就在 dirty 集合里，主线程的 add 是 no-op，但这是<b>一次新写入</b>；
     * 若 commit 后按词移除 apple，0.6 就只存在于内存里，直到 12 轮后的全量兜底才落盘。
     *
     * <p>所以判断依据是 {@link #playerWriteSeq} 的序号，而不是"哪些词被写过"：
     * 序号没变 = 保存的这些值仍然是最新值 = 可以整批清；序号变了 = 一律保留，
     * 下一轮重写（重复写几个词只是几次幂等 UPSERT，代价远小于丢一次更新）。
     *
     * <p><b>R7-C 追补（P2-2）：本方法的判断只在"seq 采集之后才开始读数据"时才成立。</b>
     * 它把"序号没变"当作"本次写出的值已覆盖所有写入"的证明，而这个证明要求：seq 采集
     * 点之后发生的任何写入，要么被本次读到，要么让序号改变。若增量路径在 seq 采集<b>之前</b>
     * 就把词集合拷贝走（{@code new ArrayList<>(words)} 在参数求值时执行），这个前提被打破：
     * 拷贝之后、seq 采集之前新增的词两头落空 —— 不在拷贝里，序号却已计入它 ——
     * {@code clearDirty} 于是清掉了它的标记，而它从未落库。修复在 {@link #saveDirty}
     * （传活引用）：本方法无需改动，但它的正确性依赖那一点，勿单独回退。
     *
     * @param playerId     玩家
     * @param seqAtSnapshot 采集写入序号那一刻（方法体第一行，先于任何取值/迭代）
     */
    private void clearDirty(UUID playerId, long seqAtSnapshot) {
        if (currentWriteSeq(playerId) != seqAtSnapshot) {
            // 保存窗口内有新写入：整批保留，下轮重写
            return;
        }
        dirtyWords.remove(playerId);
        dirtyPlayers.remove(playerId);
    }

    /**
     * R7-A：写 {@code player_data} 行时使用的不可变快照。
     *
     * <p>为什么需要它（同时也是 {@link PlayerStats} 并发修复的第二道保险）：
     * {@link PlayerStats} 有 4 个字段，保存线程若逐个 {@code get} 读取，主线程可能在
     * 两次读之间完成一次结算，写出"新 total_words + 旧 total_sessions"这种跨字段
     * 不一致的行。这里一次取全，配合字段的 {@code volatile}，保证这一行写出去的
     * 4 个值来自同一个观察点（逐字段可见性由 volatile 保证；4 个字段之间仍然不是原子的，
     * 但那只是"某次结算的部分统计晚一轮落库"，下轮全量/增量保存会以绝对值覆盖，
     * 不会累积错误）。
     *
     * <p><b>R7-C（P1-3）：取快照的位置必须在 {@code synchronized (connectionLock)} 之内。</b>
     * 锁外取会把"取快照"与"抢锁写库"之间隔开一个任意长的排队窗口，异步保存任务可能
     * 拿着排在锁外的旧值覆盖掉主线程（quit 全量）刚写进去的新值；进锁后取快照则
     * 保证"读到的值"与"写出的值"之间没有任何其他落盘路径插入。注意这不改变本类
     * "快照一次取全"的初衷，只是把观察点挪进临界区。
     */
    private static final class PlayerDataSnapshot {
        final String name;
        final int words;
        final int sessions;
        final long score;

        PlayerDataSnapshot(String name, int words, int sessions, long score) {
            this.name = name;
            this.words = words;
            this.sessions = sessions;
            this.score = score;
        }
    }

    private String buildUpsertSql(String tablePrefix) {
        if (databaseManager.isSQLite()) {
            return "INSERT OR REPLACE INTO " + tablePrefix
                    + "word_progress (uuid, word, mastery, correct_count, incorrect_count, last_review_time, review_count) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        } else {
            return "INSERT INTO " + tablePrefix
                    + "word_progress (uuid, word, mastery, correct_count, incorrect_count, last_review_time, review_count) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE mastery=VALUES(mastery), correct_count=VALUES(correct_count), "
                    + "incorrect_count=VALUES(incorrect_count), last_review_time=VALUES(last_review_time), "
                    + "review_count=VALUES(review_count)";
        }
    }

    /**
     * R7-A：{@code player_data} 行 UPSERT SQL —— 从原 {@code savePlayerData} 内联串
     * 原样抽出，全量路径与增量路径共用同一字符串（避免两套语义分叉）。
     * H10：UPSERT 携带 player_name；COALESCE 保证缓存缺失（null）时不覆盖已有名字。
     */
    private String buildPlayerDataUpsertSql(String tablePrefix) {
        return databaseManager.isSQLite()
                ? "INSERT INTO " + tablePrefix + "player_data (uuid, player_name, total_words_learned, total_sessions, total_score, updated_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET player_name=COALESCE(excluded.player_name, player_name), total_words_learned=excluded.total_words_learned, total_sessions=excluded.total_sessions, total_score=excluded.total_score, updated_at=excluded.updated_at"
                : "INSERT INTO " + tablePrefix + "player_data (uuid, player_name, total_words_learned, total_sessions, total_score, updated_at) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_name=COALESCE(VALUES(player_name), player_name), total_words_learned=VALUES(total_words_learned), total_sessions=VALUES(total_sessions), total_score=VALUES(total_score), updated_at=VALUES(updated_at)";
    }

    private int getIntValue(Map<String, Integer> map, String key) {
        if (map == null) return 0;
        Integer val = map.get(key);
        return val != null ? val : 0;
    }

    private long getLongValue(Map<String, Long> map, String key) {
        if (map == null) return 0L;
        Long val = map.get(key);
        return val != null ? val : 0L;
    }

    /**
     * Get the set of words a player has mastered.
     * Results are cached and invalidated automatically when mastery data changes.
     */
    public Set<String> getMasteredWords(UUID playerId) {
        Set<String> cached = masteredWordsCache.get(playerId);
        if (cached != null) {
            return cached;
        }

        Map<String, Double> masteryMap = wordMasteryCache.get(playerId);
        if (masteryMap == null) {
            loadPlayerData(playerId);
            masteryMap = wordMasteryCache.get(playerId);
        }

        double threshold = plugin.getConfigManager().getMainConfig()
                .getDouble("word-system.review.mastery-threshold", 0.85);

        Set<String> mastered = new HashSet<>();
        if (masteryMap != null) {
            for (Map.Entry<String, Double> entry : masteryMap.entrySet()) {
                if (entry.getValue() >= threshold) {
                    mastered.add(entry.getKey());
                }
            }
        }

        Set<String> unmodifiable = Collections.unmodifiableSet(mastered);
        masteredWordsCache.put(playerId, unmodifiable);
        return unmodifiable;
    }

    /**
     * B2b（R3-b）：取「最近 {@code windowMillis} 毫秒内答过的词」。
     *
     * <p>读时顺手<b>剪枝</b>：窗口外的条目在本次读取时删掉，避免只增不删的内存泄漏
     * （窗口默认 30 分钟，玩家在线数小时也不该把历史全留在 map 里）。剪枝后若该玩家
     * 的 map 空了，把外层条目一并摘掉。
     *
     * <p>{@code windowMillis <= 0} = 功能关闭，直接返回空集（调用方据此把
     * {@code recent-word-exclusion-minutes: 0} 当作「不排除」）。
     *
     * <p>返回的是<b>防御拷贝</b>：调用方（{@code WordBank.selectWordsForSession}）只读，
     * 但把它包成不可变视图会让每次开局都多一层包装，而拷贝本身只有窗口内的词（≤ 每会话题数），
     * 代价可忽略，且彻底断掉"误改缓存"的可能。
     *
     * @param playerId     玩家
     * @param windowMillis 有效窗口（毫秒）；{@code <= 0} 表示关闭
     * @return 窗口内答过的词（小写）；永不为 {@code null}
     */
    public Set<String> getRecentlyAnsweredWords(UUID playerId, long windowMillis) {
        if (playerId == null || windowMillis <= 0) {
            return Collections.emptySet();
        }

        Map<String, Long> answers = recentAnswerCache.get(playerId);
        if (answers == null || answers.isEmpty()) {
            return Collections.emptySet();
        }

        long cutoff = System.currentTimeMillis() - windowMillis;
        Set<String> recent = new HashSet<>();
        for (Map.Entry<String, Long> entry : answers.entrySet()) {
            Long at = entry.getValue();
            if (at != null && at >= cutoff) {
                recent.add(entry.getKey());
            } else if (at == null) {
                // ConcurrentHashMap 不允许 null 值，理论上不可达；留着防止后人改写入点时
                // 用 remove(key, null) 触发 NPE（该重载的值参数不可为 null）。
                answers.remove(entry.getKey());
            } else {
                // 窗口外：顺手剪掉。remove(key, value) 两参形式避免误删并发写入的新时间戳。
                answers.remove(entry.getKey(), at);
            }
        }
        if (answers.isEmpty()) {
            // 外层条目也摘掉（否则在线玩家的 UUID 会一直堆在 recentAnswerCache 里）。
            // 残余窗口：isEmpty 判断与本次 remove 之间若有并发写入，那条新词会连带被摘 ——
            // 后果是"该词这次没被排除"，窗口只持续 30 分钟且不影响任何落盘数据，可接受。
            recentAnswerCache.remove(playerId, answers);
        }
        return recent;
    }

    /**
     * Update word progress after a learning session.
     * Invalidates the mastered words cache for the player.
     *
     * <p>Block C2（A4/R3-M3）：补全 5 个 map 的 computeIfAbsent —— savePlayerData 按
     * masteryMap 全量遍历、7 列全写，review 两 map 缺失时 getLongValue/getIntValue
     * 回落 0 会覆盖 DB 非零值（复习调度被清零）。masteryMap 的创建点仅本方法与
     * loadPlayerData 两处（后者五 map 全填），故补两行后"凡 savePlayerData 可被触达
     * （masteryMap 非 null）的状态下 5 map 必同存"，覆盖窗口封死。
     */
    public void updateWordProgress(UUID playerId, Map<String, LearningSession.WordResult> results) {
        Map<String, Double> masteryMap = wordMasteryCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> correctMap = correctCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        Map<String, Integer> incorrectMap = incorrectCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        lastReviewTimeCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        reviewCountCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());

        int sessionCorrect = 0;
        int sessionIncorrect = 0;

        for (Map.Entry<String, LearningSession.WordResult> entry : results.entrySet()) {
            String word = entry.getKey().toLowerCase();
            LearningSession.WordResult result = entry.getValue();

            int wordCorrect = result.getCorrectCount();
            int wordIncorrect = result.getIncorrectCount();
            correctMap.merge(word, wordCorrect, Integer::sum);
            incorrectMap.merge(word, wordIncorrect, Integer::sum);
            sessionCorrect += wordCorrect;
            sessionIncorrect += wordIncorrect;

            double currentMastery = masteryMap.getOrDefault(word, 0.0);
            double newMastery;

            if (result.isMastered()) {
                newMastery = Math.min(1.0, currentMastery + 0.2);
            } else {
                newMastery = Math.max(0.0, currentMastery - (wordIncorrect * 0.1));
            }

            masteryMap.put(word, newMastery);
            // R7-A markWordDirty 点 1/7 + 2/7（同一处覆盖两个写入点）：
            //   1/7 = masteryMap.put(word, newMastery)（掌握度）
            //   2/7 = correctMap.merge / incorrectMap.merge（正确/错误计数）
            // 三个 map 的同一词一次标记即可（dirtyWords 是词级粒度，不区分 map）。
            // 必须放在三个 map 的写入<b>之后</b>：标记会推进写入序号，保存线程据此判断
            // "窗口内是否有新写入"；先标后写会让"读到旧值 + 序号未变 → 误清标记"成立。
            // 不按"是否答过"过滤 —— 未作答的词 mastery 也可能被写，凡进循环即标。
            markWordDirty(playerId, word);

            // B2b（R3-b）：近期已答词窗口写入点。
            // 为什么<b>放在这里</b>：所有答题结算（学习会话 / 复习会话 / 下线结算 /
            // AI 纠错闭环）都必须经过 updateWordProgress —— 它是 word_progress 的唯一
            // 结算入口。因此放在这个循环里，"答对"与"答错"都会自动记账，不需要在每个
            // 调用方各写一遍（那样一定会漏掉某条路径）。时间戳取 settle 时刻而非逐题时刻：
            // 窗口语义是"最近 N 分钟内答过的词"，整局共用同一时刻的误差不超过一局时长。
            // 纯内存操作，不标脏 —— 本 map 不落库（见字段注释）。
            //
            // 只记"实际作答过"的词：results 里也包含中途 stop 残留的未作答词
            // （correct + incorrect == 0，与 endSession / review 分支的 continue 判据同源）。
            // 把没答过的词算进"近期已答"，会让"开局即停"的玩家把整局词表冻结 30 分钟。
            if (wordCorrect + wordIncorrect > 0) {
                recentAnswerCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                        .put(word, System.currentTimeMillis());
            }
        }

        masteredWordsCache.remove(playerId);

        updatePlayerStats(playerId, sessionCorrect, sessionIncorrect);
    }

    /**
     * Block C2（A5/R3-M4）：会话结算入口 —— total_sessions 按"局数口径"累加
     * （每次会话结束 +1，同日多局累计）。与 player_sessions 表"每人每日一行"的
     * 学习天数口径**不同源**（后者另有 DatabaseManager.getTotalSessions 消费）；
     * 成就判定（AchievementManager 按 total_sessions 涨幅）以本口径为准，属既定语义。
     */
    private void updatePlayerStats(UUID playerId, int correct, int incorrect) {
        PlayerStats stats = statsFor(playerId);
        stats.addCorrect(correct);
        stats.addIncorrect(incorrect);
        stats.incrementSessions();
        // R7-A markDirty 点 3/7：player_data 行（total_words_learned / total_sessions）。
        // addXxx / incrementXxx 的 dirtyHook 已标记过一次；这里显式再标一次是为了让
        // "本方法的写入点"在代码里可见（幂等，无额外代价）。
        markDirty(playerId);
    }

    /**
     * R7-A：取（必要时创建）玩家统计实例，并保证它挂着脏标记钩子。
     *
     * <p>{@link PlayerStats} 的变更方法会回调 {@code markDirty}，所以任何外部调用方
     * （如 {@code WordManagerImpl} 的 {@code getPlayerStats(id).addScore(...)}）都自动标脏，
     * 不依赖"枚举所有写入点"。
     */
    private PlayerStats statsFor(UUID playerId) {
        return playerStatsCache.computeIfAbsent(playerId, k -> {
            PlayerStats s = new PlayerStats();
            s.setDirtyHook(() -> markDirty(k));
            return s;
        });
    }

    /**
     * Get last review times for a player's words.
     *
     * <p><b>R7-C（P2-3）：返回不可修改视图</b>。原实现直接返回缓存内部的活引用，
     * 任何调用方都能绕过 {@link #markWordDirty} 直接写 —— 写进去的值既不进脏标记，
     * 也就永远不会落库（直到 12 轮全量兜底，甚至因缓存被清而丢失）。当前唯一调用方
     * {@code ReviewScheduler.getDueReviewWords} 只遍历不写，{@code unmodifiableMap}
     * 零拷贝且遍历语义不变；改为抛出 {@code UnsupportedOperationException} 反而能
     * 在第一次误写时立即暴露问题，而不是静默丢数据。
     *
     * <p>{@code computeIfAbsent} 仍保留：cache miss 时需要建出空 map，否则调用方的
     * 遍历会拿到 NPE（且 review 调度需要该玩家条目存在）。
     */
    public Map<String, Long> getLastReviewTimes(UUID playerId) {
        return Collections.unmodifiableMap(
                lastReviewTimeCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()));
    }

    /**
     * Get review counts for a player's words.
     *
     * <p><b>R7-C（P2-3）：返回不可修改视图</b>，理由同 {@link #getLastReviewTimes}。
     */
    public Map<String, Integer> getReviewCounts(UUID playerId) {
        return Collections.unmodifiableMap(
                reviewCountCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()));
    }

    /**
     * Record a completed review for a word.
     */
    public void recordReview(UUID playerId, String word) {
        String wordLower = word.toLowerCase();

        Map<String, Long> reviewTimes = lastReviewTimeCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        reviewTimes.put(wordLower, System.currentTimeMillis());

        Map<String, Integer> reviewCounts = reviewCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        reviewCounts.merge(wordLower, 1, Integer::sum);

        // R7-A markWordDirty 点 5/7：reviewCountCache.merge(wordLower, +1)（lastReviewTime 同词）。
        markWordDirty(playerId, wordLower);
    }

    /**
     * H5：普通学习结算时写入复习种子。
     * 仅当词条没有任何复习进度（无 lastReviewTime 且无 reviewCount）时才写：
     * lastReviewTime=now、reviewCount=0。已有种子或已进入复习流程的词不动，
     * 避免覆盖真实的复习间隔进度。
     */
    public void seedReview(UUID playerId, String word) {
        String wordLower = word.toLowerCase();

        Map<String, Long> reviewTimes = lastReviewTimeCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        if (reviewTimes.containsKey(wordLower)) {
            return;
        }
        Map<String, Integer> reviewCounts = reviewCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        if (reviewCounts.containsKey(wordLower)) {
            return;
        }

        reviewTimes.put(wordLower, System.currentTimeMillis());
        reviewCounts.put(wordLower, 0);

        // P1-RT-3：把词挂进 masteryMap 才能落库。savePlayerData 只遍历 wordMasteryCache
        // （即"走过会话结算"的词），而 seedReview 的调用方包括 AI 纠错闭环
        // （ChatFeedbackManager）——那条路径的词可能从未出现在 word_progress 里，
        // 只写两个内存 map 会导致"本次在线可见、重启即消失"。
        // putIfAbsent(0.0) 不改变已有词的掌握度（0 = 未掌握），只是补一行让 UPSERT 带上
        // 刚写入的 lastReviewTime / reviewCount；真实掌握度仍由 updateWordProgress 维护。
        wordMasteryCache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .putIfAbsent(wordLower, 0.0);

        // R7-A markWordDirty 点 6/7：seedReview 写 lastReviewTime + reviewCount，
        // 且可能补建 masteryMap 条目（putIfAbsent 0.0）—— 三者同属一个词，一次标记。
        markWordDirty(playerId, wordLower);
    }

    /**
     * H5：复习答错时重置该词的间隔进度。
     * reviewCount 归零 + lastReviewTime=now，下次 due = now + intervals[0]
     * （默认 1h 后再次提醒），而不是直接晋级到更长的间隔。
     */
    public void resetReview(UUID playerId, String word) {
        String wordLower = word.toLowerCase();

        Map<String, Long> reviewTimes = lastReviewTimeCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        reviewTimes.put(wordLower, System.currentTimeMillis());

        Map<String, Integer> reviewCounts = reviewCountCache.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
        reviewCounts.put(wordLower, 0);

        // R7-A markWordDirty 点 7/7：resetReview 写 lastReviewTime + reviewCount（mastery 不动）。
        markWordDirty(playerId, wordLower);
    }

    /**
     * Check if a player is currently in a learning session.
     */
    public boolean isPlayerInSession(UUID playerId) {
        return activeSessionPlayers.contains(playerId);
    }

    /**
     * Mark a player as being in a session.
     */
    public void setPlayerInSession(UUID playerId, boolean inSession) {
        if (inSession) {
            activeSessionPlayers.add(playerId);
        } else {
            activeSessionPlayers.remove(playerId);
        }
    }

    /**
     * Unload a player's data from cache (called on player quit).
     *
     * <p>R-03：补清两套今日计数器 —— 原实现只清持久化缓存，防刷上限（今日答题/AI 轮次）
     * 的内存态被漏掉，reset 后不重进服上限不归零。
     *
     * <p>S5 裁决：本方法只清自己持有的 map（含 R-28 的 conversationCountCache）。
     * 其余服务的 quit 级清理（GuiNavigationManager 返回栈 / CheckInManager 签到锁 /
     * RewardEngine 经济提示去重）<strong>不</strong>在这里反向调用 —— RewardEngine 已依赖
     * PlayerDataStore（getTodayAnswerCount 等），由此反向调用会引入双向依赖。
     * 统一收口在 {@code PlayerJoinListener.onPlayerQuit}（Listener → 服务单向，
     * 无环）。{@link #clearCache()}（reload 路径）刻意不清两套今日计数器：
     * reload 不重置防刷是既定语义（F5-04/T0-02 裁决）。
     */
    public void unloadPlayerData(UUID playerId) {
        savePlayerData(playerId);
        wordMasteryCache.remove(playerId);
        lastReviewTimeCache.remove(playerId);
        reviewCountCache.remove(playerId);
        correctCountCache.remove(playerId);
        incorrectCountCache.remove(playerId);
        playerStatsCache.remove(playerId);
        activeSessionPlayers.remove(playerId);
        masteredWordsCache.remove(playerId);
        difficultWordsCache.remove(playerId);
        wrongCountCache.remove(playerId);
        playerNameCache.remove(playerId);
        todayAnswerCountCache.remove(playerId);
        todayAiRoundCache.remove(playerId);
        conversationCountCache.remove(playerId); // R-28
        wrongBookClearedCache.remove(playerId); // Phase 3 S-A（H11）
        recentAnswerCache.remove(playerId); // B2b（R3-b）：纯内存窗口，下线即清
        // R7-A：全量 savePlayerData 已兑现所有标记；此处清残留防止为已下线玩家积累。
        // 注意：若上面的 savePlayerData 失败（SQLException），缓存随后仍被清空 ——
        // 这次 quit 的数据就真的丢了。这是 R7-A 之前既有的行为（全量保存失败 + 清缓存），
        // 本流不改变它；保留标记反而会让已下线玩家的 dirty 集合无限增长。
        dirtyPlayers.remove(playerId);
        dirtyWords.remove(playerId);
        dirtySaveCount.remove(playerId);
        idleSaveCount.remove(playerId); // R7-C（P1-2）：空转计数随下线清理，避免为已下线玩家积累
        playerWriteSeq.remove(playerId);
    }

    /**
     * Clear all in-memory caches.
     * Called during /lea reload to ensure fresh data from disk.
     *
     * <p>R7-A：<b>本方法不落盘</b>（既有的 {@code HotReloadManager.flushOnlinePlayerData}
     * 已在此之前对全部在线玩家走过全量 {@link #savePlayerData}，语义不能在此重复加锁）。
     * 这里同时清掉脏标记与兜底计数 —— 全量落盘已经兑现了所有标记；若不清，
     * 下一次 {@link #saveDirty} 会拿着"已落盘且缓存已清"的过期标记空跑。
     */
    public void clearCache() {
        wordMasteryCache.clear();
        lastReviewTimeCache.clear();
        reviewCountCache.clear();
        correctCountCache.clear();
        incorrectCountCache.clear();
        playerStatsCache.clear();
        activeSessionPlayers.clear();
        masteredWordsCache.clear();
        difficultWordsCache.clear();
        wrongCountCache.clear();
        playerNameCache.clear();
        conversationCountCache.clear(); // R-28
        wrongBookClearedCache.clear(); // Phase 3 S-A（H11）
        recentAnswerCache.clear(); // B2b（R3-b）：reload 时窗口整体作废（纯内存态，无落盘语义）
        dirtyPlayers.clear(); // R7-A
        dirtyWords.clear(); // R7-A
        dirtySaveCount.clear(); // R7-A
        idleSaveCount.clear(); // R7-C（P1-2）
        playerWriteSeq.clear(); // R7-A
    }

    /**
     * Get a player's aggregate learning statistics.
     */
    public PlayerStats getPlayerStats(UUID playerId) {
        PlayerStats stats = playerStatsCache.get(playerId);
        if (stats == null) {
            loadPlayerData(playerId);
            stats = statsFor(playerId);
        }
        return stats;
    }

    /**
     * Player aggregate statistics for data export and display.
     *
     * <p><b>R7-A 并发修复</b>：四个字段原本是裸 {@code int}/{@code long}，而写入
     * （{@link #updatePlayerStats}、{@code GameWorld} 侧 {@code addScore}）发生在
     * <b>主线程</b>、读取（{@code savePlayerData} 的 player_data 行）发生在
     * <b>异步保存线程</b> —— 没有任何 happens-before 边，保存线程可能永远读不到
     * 主线程的更新（JMM 允许缓存该字段）或读到撕裂值（32 位 JVM 上 long 尤其）。
     *
     * <p>修复方式：字段加 {@code volatile}。
     * <ul>
     *   <li>为什么 volatile 足够：每次操作都是<b>单个字段</b>的读或写；{@code +=} 虽非原子，
     *       但写入方只有主线程一条（会话结算）——不存在写写竞争，只需保证"写入对
     *       其他线程可见 + 不发生字撕裂"，这正是 volatile 的语义；</li>
     *   <li>为什么不用 AtomicInteger/LongAdder：会把 4 个字段的读放大成 4 次原子读，
     *       且 {@code getTotalWords()} 等对外 API 的返回类型/语义要跟着改（波及
     *       AchievementManager/PAPI/各 GUI），收益为零；</li>
     *   <li>跨字段一致性：保存侧用 {@link PlayerDataSnapshot} 一次取全 4 个值
     *       （见 {@link #snapshotPlayerData}），保证写出去的是"同一个观察点"的快照。
     *       4 个字段之间仍非原子，但绝对值覆写 + 下轮全量兜底使误差只可能是
     *       "某次结算的统计晚一轮落库"，不会累积。</li>
     *   <li>没有引入任何新锁：volatile 只是内存屏障，不阻塞、不加锁。</li>
     * </ul>
     */
    public static class PlayerStats {
        private volatile int totalCorrect;
        private volatile int totalIncorrect;
        private volatile int totalSessions;
        private volatile long totalScore;

        /**
         * R7-A：所有<b>变更型</b>方法（addXxx / incrementXxx / setXxx）结束时调用的回调。
         *
         * <p>为什么需要它：{@code WordManagerImpl.endSession} 直接用
         * {@code playerDataStore.getPlayerStats(id).addScore(session.getScore())}
         * 改 {@code totalScore} —— 这条路径<b>不经过</b>本类的任何方法，漏标 markDirty
         * 就等于 {@code total_score} 永不落库（榜单/统计全错）。把标记挂在实例上，
         * 无论谁拿着引用去改都能标脏，比"穷举所有外部写入点"可靠。
         *
         * <p>为 null 时表示该实例只用于读（如 {@link #loadPlayerData} 重建期间的临时对象），
         * 此时不做任何事。
         */
        private Runnable dirtyHook;

        /** 包私有：只允许 {@link PlayerDataStore} 挂接/摘除。 */
        void setDirtyHook(Runnable hook) { this.dirtyHook = hook; }

        private void onMutated() {
            Runnable hook = dirtyHook;
            if (hook != null) {
                hook.run();
            }
        }

        public void addCorrect(int count) { totalCorrect += count; onMutated(); }
        public void addIncorrect(int count) { totalIncorrect += count; onMutated(); }
        /**
         * Block C2（A5/R3-M4）：total_sessions = 学习局数（每次会话结束 +1，同日多局
         * 累计）——与 player_sessions 表"每人每日一行"的天数口径**不同源**；
         * 成就判定（AchievementManager）以本口径为准。
         */
        public void incrementSessions() { totalSessions++; onMutated(); }
        public void addScore(long score) { totalScore += score; onMutated(); }
        public void setSessions(int sessions) { totalSessions = sessions; onMutated(); }
        public void setScore(long score) { totalScore = score; onMutated(); }

        public int getTotalCorrect() { return totalCorrect; }
        public int getTotalIncorrect() { return totalIncorrect; }
        public int getTotalSessions() { return totalSessions; }
        public long getTotalScore() { return totalScore; }

        // M10："已学单词"只计答对（答错进错题本，不算学会）。
        // load 重建与 save 落盘两侧都经此方法，口径天然一致。
        public int getTotalWords() {
            return totalCorrect;
        }
    }

    public static class ProfileSettings {
        /** R-39：年龄合法上限（岁）。超过此值视为脏数据，读侧回 0、写侧钳制。 */
        public static final int MAX_VALID_AGE = 150;

        private int age;
        // D2 隐私优先：新玩家默认不公开答案
        private boolean showAnswers = false;
        private boolean showResults = true;
        private boolean showInSquare = true;
        // Phase 3 S-A：per-player 音效开关，默认开（fail-open，见 SoundManager 门）
        private boolean soundsEnabled = true;
        // R1/R4 教育骚扰治理：玩家侧提醒总开关，默认开（不打扰既有玩家 —— 保持上线行为不变）。
        // 关闭后不再收到每日学习提醒 / 进服提醒 / 复习提醒；服务器级 config
        // （daily-reminder.* / word-system.review.*）仍是总闸，本开关只能再收紧不能越权放开。
        private boolean remindersEnabled = true;

        /**
         * R-39 读侧校验：年龄 0 = 未设置（正常态，GUI 显示"未设置"）；
         * 负数或 &gt; MAX_VALID_AGE 视为脏数据，回 0。
         */
        public int getAge() {
            if (age < 0 || age > MAX_VALID_AGE) {
                return 0;
            }
            return age;
        }
        public void setAge(int age) { this.age = age; }
        public boolean isShowAnswers() { return showAnswers; }
        public void setShowAnswers(boolean showAnswers) { this.showAnswers = showAnswers; }
        public boolean isShowResults() { return showResults; }
        public void setShowResults(boolean showResults) { this.showResults = showResults; }
        public boolean isShowInSquare() { return showInSquare; }
        public void setShowInSquare(boolean showInSquare) { this.showInSquare = showInSquare; }
        // Phase 3 S-A：音效开关（SoundManager per-player 门读侧走 getProfileSettingsReadOnly）
        public boolean isSoundsEnabled() { return soundsEnabled; }
        public void setSoundsEnabled(boolean soundsEnabled) { this.soundsEnabled = soundsEnabled; }
        // R1/R4：提醒总开关（发送侧读侧统一走 getProfileSettingsReadOnly，不建缓存条目）
        public boolean isRemindersEnabled() { return remindersEnabled; }
        public void setRemindersEnabled(boolean remindersEnabled) { this.remindersEnabled = remindersEnabled; }
    }

    public ProfileSettings getProfileSettings(UUID playerId) {
        return profileSettingsCache.computeIfAbsent(playerId, k -> new ProfileSettings());
    }

    /**
     * L-05 只读版：展示路径（ProfileGUI / EnglishSquareGUI / Leaderboard 查榜 /
     * PAPI 占位符）用此方法读隐私开关。
     * 缓存缺失时返回一个全新的默认副本，绝不写入 profileSettingsCache，
     * 避免只读展示在缓存里建条目、污染 {@link #getActivePlayerIds()}。
     * 返回的实例是调用方私有的，改它不会影响缓存；写路径请走
     * {@link #getProfileSettings(UUID)}。
     */
    public ProfileSettings getProfileSettingsReadOnly(UUID playerId) {
        ProfileSettings cached = profileSettingsCache.get(playerId);
        return cached != null ? cached : new ProfileSettings();
    }

    public void saveProfileSettings(UUID playerId) {
        ProfileSettings settings = profileSettingsCache.get(playerId);
        if (settings == null) return;

        String tablePrefix = databaseManager.getTablePrefix();
        String sql = databaseManager.isSQLite()
                ? "INSERT OR REPLACE INTO " + tablePrefix + "player_profiles (uuid, age, show_answers, show_results, show_in_square, sounds_enabled, reminders_enabled) VALUES (?, ?, ?, ?, ?, ?, ?)"
                : "INSERT INTO " + tablePrefix + "player_profiles (uuid, age, show_answers, show_results, show_in_square, sounds_enabled, reminders_enabled) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE age=VALUES(age), show_answers=VALUES(show_answers), show_results=VALUES(show_results), show_in_square=VALUES(show_in_square), sounds_enabled=VALUES(sounds_enabled), reminders_enabled=VALUES(reminders_enabled)";

        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setInt(2, settings.getAge());
            stmt.setBoolean(3, settings.isShowAnswers());
            stmt.setBoolean(4, settings.isShowResults());
            stmt.setBoolean(5, settings.isShowInSquare());
            stmt.setBoolean(6, settings.isSoundsEnabled());
            stmt.setBoolean(7, settings.isRemindersEnabled()); // R1/R4：提醒总开关
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save profile settings: " + playerId, e);
        }
        }
    }

    public void loadProfileSettings(UUID playerId) {
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT age, show_answers, show_results, show_in_square, sounds_enabled, reminders_enabled FROM " + tablePrefix + "player_profiles WHERE uuid = ?";

        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ProfileSettings settings = new ProfileSettings();
                    settings.setAge(rs.getInt("age"));
                    settings.setShowAnswers(rs.getBoolean("show_answers"));
                    settings.setShowResults(rs.getBoolean("show_results"));
                    settings.setShowInSquare(rs.getBoolean("show_in_square"));
                    settings.setSoundsEnabled(rs.getBoolean("sounds_enabled")); // Phase 3 S-A
                    settings.setRemindersEnabled(rs.getBoolean("reminders_enabled")); // R1/R4：提醒总开关
                    profileSettingsCache.put(playerId, settings);
                } else {
                    profileSettingsCache.putIfAbsent(playerId, new ProfileSettings());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load profile settings: " + playerId, e);
            profileSettingsCache.putIfAbsent(playerId, new ProfileSettings());
        }
        }
    }

    public void unloadProfileSettings(UUID playerId) {
        saveProfileSettings(playerId);
        profileSettingsCache.remove(playerId);
    }

    /**
     * R-39 写侧钳制：年龄合法范围 0~MAX_VALID_AGE。非法写入记 WARNING 并钳制
     * （负数→0，&gt;150→150），脏数据永不进缓存/落盘。
     */
    public void setAge(UUID playerId, int age) {
        int clamped = age;
        if (clamped < 0) {
            clamped = 0;
        } else if (clamped > ProfileSettings.MAX_VALID_AGE) {
            clamped = ProfileSettings.MAX_VALID_AGE;
        }
        if (clamped != age) {
            plugin.getLogger().warning("R-39: clamped invalid age " + age
                    + " to " + clamped + " for " + playerId);
        }
        ProfileSettings settings = getProfileSettings(playerId);
        settings.setAge(clamped);
    }

    /**
     * L-05：候选集 = profileSettingsCache 键 ∪ 当前在线玩家。
     * 原先只取 profile 缓存键：受只读展示的 computeIfAbsent 建条目污染、
     * 且档案未加载的在线玩家（刚上线/异常路径）会被广场漏掉。
     * 展示路径已改走 {@link #getProfileSettingsReadOnly}，本方法不再被写副作用污染。
     * quit 清理语义不变（unloadProfileSettings 照常删缓存键）。
     */
    public List<UUID> getActivePlayerIds() {
        java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>(profileSettingsCache.keySet());
        try {
            for (org.bukkit.entity.Player online : plugin.getServer().getOnlinePlayers()) {
                ids.add(online.getUniqueId());
            }
        } catch (Exception e) {
            // 异步可达路径（如启动期 Leaderboard 预热）拿不到在线列表时，
            // 回落到 profile 缓存键，保证永不抛异常。
        }
        return new ArrayList<>(ids);
    }

    /**
     * 读取玩家名（展示用）。
     *
     * <p>F1-21 防御：优先读 join 时缓存的名（{@link #cachePlayerName}，H10）——
     * 缓存命中完全不触 Bukkit API。缓存未命中（离线玩家 / reload 后未重登 /
     * 未来的异步调用窗）时，仅主线程回落名册查询（{@code getOfflinePlayer}
     * 为阻塞式查询，异步调用不安全）；非主线程直接回落 uuid 字符串。
     *
     * <p>已核实既有四个消费点（ProfileGUI / EnglishSquareGUI / Leaderboard /
     * SocialListener）当前全在主线程——主线程行为与旧实现一致（离线玩家名
     * 仍走名册解析，不因缓存只覆盖在线而降级）；{@code isPrimaryThread}
     * 守卫是未来异步调用的防线（届时安全回落 uuid）。
     */
    public String getPlayerName(UUID playerId) {
        String cached = playerNameCache.get(playerId);
        if (cached != null) {
            return cached;
        }
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            org.bukkit.OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(playerId);
            if (offlinePlayer != null && offlinePlayer.getName() != null) {
                return offlinePlayer.getName();
            }
        }
        return playerId.toString();
    }

    /**
     * H10：缓存玩家名（供 savePlayerData 落盘 player_name 列）。
     * 由 PlayerJoinListener 在主线程写入；列宽 VARCHAR(32)，超长截断。
     */
    public void cachePlayerName(UUID playerId, String name) {
        if (playerId == null || name == null || name.isEmpty()) {
            return;
        }
        String trimmed = name.length() > 32 ? name.substring(0, 32) : name;
        playerNameCache.put(playerId, trimmed);
        // R7-A markDirty 点 4/7：player_data.player_name 列。
        markDirty(playerId);
    }

    /**
     * M9：将会话连击峰值落盘到 longest_streak（只增不减）。
     *
     * <p>R7-B ①：原实现「INSERT 保行 + 条件 UPDATE 取 max」两条 SQL 合并为单条 UPSERT
     * （答对路径每次连击结算省 1 次同步往返）。语义逐条对齐：</p>
     * <ul>
     *   <li>行不存在 → 插入 {@code longest_streak = streak}（原 INSERT OR IGNORE 分支）；</li>
     *   <li>行存在 → {@code longest_streak = max(旧值, 新值)}，只增不减；</li>
     *   <li>{@code longest_streak} 为 NULL 时按 0 参与比较（原 UPDATE 的
     *       {@code longest_streak IS NULL OR longest_streak < ?} 分支）。</li>
     * </ul>
     *
     * <p><strong>NULL 语义</strong>：SQLite 标量 {@code MAX(a, b)} 与 MySQL {@code GREATEST(a, b)}
     * 都是"任一参数为 NULL 则返回 NULL"，故两侧都必须用 {@code COALESCE(..., 0)} 包住旧值
     * （MySQL 尤其致命：不包的话 {@code longest_streak} 为 NULL 时整列会被写成 NULL）。</p>
     *
     * <p><strong>不冲突核实</strong>：{@code savePlayerData} 的 player_data UPSERT
     * （{@link #buildPlayerDataUpsertSql}）列清单只有
     * uuid / player_name / total_words_learned / total_sessions / total_score / updated_at，
     * <strong>不含 longest_streak</strong>；{@code DatabaseManager.adjustCoins} 只写
     * total_coins_* 两列。故本方法与该两条写路径互不覆盖，可独立合并。</p>
     *
     * <p>唯一可观察差异：{@code updated_at} 现在无论是否刷新最大值都会更新（原实现只在
     * "确实变大"时更新）。player_data.updated_at 全仓无任何读取点（仅 social_feed 有自己的
     * updated_at 排序列），故无影响。</p>
     *
     * <p>调用方（AchievementManager.checkStreakAchievement）跑在主线程，单条 SQL 很快，
     * 直接同步执行即可。</p>
     */
    public void persistLongestStreak(UUID playerId, int streak) {
        if (streak <= 0) {
            return;
        }
        String tablePrefix = databaseManager.getTablePrefix();
        String sql;
        if (databaseManager.isSQLite()) {
            sql = "INSERT INTO " + tablePrefix
                    + "player_data (uuid, longest_streak, updated_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET "
                    + "longest_streak = MAX(COALESCE(longest_streak, 0), excluded.longest_streak), "
                    + "updated_at = excluded.updated_at";
        } else {
            sql = "INSERT INTO " + tablePrefix
                    + "player_data (uuid, longest_streak, updated_at) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE "
                    + "longest_streak = GREATEST(COALESCE(longest_streak, 0), VALUES(longest_streak)), "
                    + "updated_at = VALUES(updated_at)";
        }

        synchronized (databaseManager.getConnectionLock()) {
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setInt(2, streak);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist longest_streak: " + playerId, e);
            }
        }
    }

    public void clearProfileCache() {
        profileSettingsCache.clear();
    }

    /**
     * Get the top N players ranked by total_score from the player_data table.
     * Used for all-time leaderboard display.
     */
    public List<LeaderboardEntry> getRankedPlayers(int count) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT d.uuid, d.player_name, d.total_words_learned, d.total_sessions, d.total_score "
                + "FROM " + tablePrefix + "player_data d "
                + "LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid "
                + "WHERE COALESCE(p.show_in_square, 1) <> 0 "
                + "ORDER BY d.total_score DESC LIMIT ?";

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, count);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawRows.add(new Object[]{
                        rs.getString("uuid"),
                        rs.getString("player_name"),
                        rs.getLong("total_score"),
                        rs.getInt("total_sessions")
                    });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load ranked players", e);
            return entries;
        }
        }

        // Step 2: ResultSet closed — now safe to call getPlayerStats (opens new Statement)
        // 注意：此处不能调用任何 Bukkit API（如 getPlayerName），因为本方法会在插件启动的异步任务里被调用。
        // 因此 player_name 为空时直接回落到 uuid.toString()。
        int rank = 1;
        for (Object[] row : rawRows) {
            UUID uuid = UUID.fromString((String) row[0]);
            PlayerStats stats = getPlayerStats(uuid);
            String name = (String) row[1];
            if (name == null || name.isEmpty()) {
                name = uuid.toString();
            }
            entries.add(new LeaderboardEntry(uuid, name, (Long) row[2],
                    stats.getTotalCorrect(), stats.getTotalIncorrect(),
                    (Integer) row[3], rank++));
        }
        return entries;
    }

    /**
     * F3（R13-M3）：广场"全部玩家"聚合查询 —— 单条 SQL 取代
     * {@code getActivePlayerIds + 逐人 getPlayerStats/隐身过滤} 的 N+1 装配
     *（旧路径每 cache-miss 玩家一次 loadPlayerData 全量查库）。
     *
     * <p>SQL 口径：{@code player_data} LEFT JOIN {@code player_profiles}
     *（COALESCE 隐身过滤，同 {@link #getRankedPlayers(int)}）+ LEFT JOIN
     * {@code word_progress} 按 uuid 聚合子查询（correct/incorrect 顺带 SUM，
     * 不再对每行补读——两处消费侧一次查询拿全）。"答过题"判定 = 聚合
     * correct+incorrect &gt; 0（等价旧 {@code getTotalCorrect()+getTotalIncorrect() > 0}）。
     * ORDER BY total_score DESC LIMIT ?（与 getRankedPlayers 同式）。
     * word_progress 聚合走 UNIQUE(uuid, word) 的 uuid 前缀索引。
     *
     * <p>数据源 = DB 真值（非内存统计镜像）：在线玩家在自动保存间隔（默认 5 分钟）
     * 内尚未落盘的最新答题会延迟可见——这是本方法替换装配口径的既定语义
     *（fix-plan-c2 F3 裁决：同步单查已达标，不加快照层）。
     *
     * @param limit 行数上限（"全部"语义传 {@link Integer#MAX_VALUE}；LIMIT ? 双后端兼容）
     */
    public List<LeaderboardEntry> getActiveSquareEntries(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String tablePrefix = databaseManager.getTablePrefix();
        String sql = "SELECT d.uuid AS uuid, d.player_name AS player_name, "
                + "d.total_score AS total_score, d.total_sessions AS total_sessions, "
                + "COALESCE(w.correct_sum, 0) AS correct_sum, COALESCE(w.incorrect_sum, 0) AS incorrect_sum "
                + "FROM " + tablePrefix + "player_data d "
                + "LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid "
                + "LEFT JOIN (SELECT uuid, SUM(correct_count) AS correct_sum, SUM(incorrect_count) AS incorrect_sum "
                + "FROM " + tablePrefix + "word_progress GROUP BY uuid) w ON w.uuid = d.uuid "
                + "WHERE COALESCE(p.show_in_square, 1) <> 0 "
                + "AND COALESCE(w.correct_sum, 0) + COALESCE(w.incorrect_sum, 0) > 0 "
                + "ORDER BY d.total_score DESC LIMIT ?";

        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                // 本方法无嵌套查询：行内数据即全部所需（区别于 getRankedPlayers 的两段式）。
                // 同样不调任何 Bukkit API（player_name 为空回落 uuid.toString()）。
                int rank = 1;
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("player_name");
                    if (name == null || name.isEmpty()) {
                        name = uuid.toString();
                    }
                    entries.add(new LeaderboardEntry(uuid, name,
                            rs.getLong("total_score"),
                            rs.getInt("correct_sum"), rs.getInt("incorrect_sum"),
                            rs.getInt("total_sessions"), rank++));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load active square entries", e);
            return entries;
        }
        }
        return entries;
    }

    /**
     * Get the top N players ranked by score within a date range from player_sessions.
     * Used for weekly/monthly leaderboard display.
     */
    public List<LeaderboardEntry> getRankedPlayersForPeriod(int count, LocalDate since, LocalDate until) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String tablePrefix = databaseManager.getTablePrefix();
        // LEFT JOIN player_data 直接取 player_name，避免回落到 Bukkit API（本方法可能在异步线程被调用）。
        // player_name 用 MAX() 包住以兼容 MySQL 的 ONLY_FULL_GROUP_BY。
        String sql = "SELECT s.uuid AS uuid, MAX(p.player_name) AS player_name, "
                + "SUM(s.score) as period_score, "
                + "SUM(s.correct_count) as total_correct, SUM(s.words_count) as total_words "
                + "FROM " + tablePrefix + "player_sessions s "
                + "LEFT JOIN " + tablePrefix + "player_data p ON p.uuid = s.uuid "
                + "LEFT JOIN " + tablePrefix + "player_profiles pf ON pf.uuid = s.uuid "
                + "WHERE s.session_date >= ? AND s.session_date <= ? "
                + "AND COALESCE(pf.show_in_square, 1) <> 0 "
                + "GROUP BY s.uuid ORDER BY period_score DESC LIMIT ?";

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, since.toString());
            stmt.setString(2, until.toString());
            stmt.setInt(3, count);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawRows.add(new Object[]{
                        rs.getString("uuid"),
                        rs.getString("player_name"),
                        rs.getLong("period_score"),
                        rs.getInt("total_correct"),
                        rs.getInt("total_words")
                    });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load ranked players for period", e);
            return entries;
        }
        }

        // Step 2: ResultSet closed — 此处不能调用任何 Bukkit API（如 getPlayerName），
        // 因为本方法会在插件启动的异步任务里被调用；player_name 为空时回落到 uuid.toString()。
        int rank = 1;
        for (Object[] row : rawRows) {
            UUID uuid = UUID.fromString((String) row[0]);
            String name = (String) row[1];
            if (name == null || name.isEmpty()) {
                name = uuid.toString();
            }
            long periodScore = (Long) row[2];
            int periodCorrect = (Integer) row[3];
            int periodWords = (Integer) row[4];
            int periodIncorrect = Math.max(0, periodWords - periodCorrect);
            int sessions = 0; // Not easily available from aggregated query
            entries.add(new LeaderboardEntry(uuid, name, periodScore,
                    periodCorrect, periodIncorrect, sessions, rank++));
        }
        return entries;
    }

    /**
     * Get the top N players ranked by score within a date range, restricted to a
     * specific set of member UUIDs (class leaderboard, US-CLS-2).
     * Same aggregation as {@link #getRankedPlayersForPeriod(int, LocalDate, LocalDate)}
     * with an extra "uuid IN (?)" filter.
     *
     * @param members members of the class; an empty collection short-circuits to an empty list
     */
    public List<LeaderboardEntry> getRankedPlayersForPeriodFiltered(int count, LocalDate since, LocalDate until,
            Collection<UUID> members) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        if (members == null || members.isEmpty()) {
            return entries;
        }
        String tablePrefix = databaseManager.getTablePrefix();
        // 与 getRankedPlayersForPeriod 同款聚合，只多了 uuid IN 过滤。
        // player_name 用 MAX() 包住以兼容 MySQL 的 ONLY_FULL_GROUP_BY。
        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                inClause.append(", ");
            }
            inClause.append("?");
        }
        String sql = "SELECT s.uuid AS uuid, MAX(p.player_name) AS player_name, "
                + "SUM(s.score) as period_score, "
                + "SUM(s.correct_count) as total_correct, SUM(s.words_count) as total_words "
                + "FROM " + tablePrefix + "player_sessions s "
                + "LEFT JOIN " + tablePrefix + "player_data p ON p.uuid = s.uuid "
                + "LEFT JOIN " + tablePrefix + "player_profiles pf ON pf.uuid = s.uuid "
                + "WHERE s.session_date >= ? AND s.session_date <= ? "
                + "AND COALESCE(pf.show_in_square, 1) <> 0 "
                + "AND s.uuid IN (" + inClause + ") "
                + "GROUP BY s.uuid ORDER BY period_score DESC LIMIT ?";

        // Step 1: collect raw rows while ResultSet is open — no nested queries
        List<Object[]> rawRows = new ArrayList<>();
        synchronized (databaseManager.getConnectionLock()) {
        Connection conn = databaseManager.getConnection();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            stmt.setString(idx++, since.toString());
            stmt.setString(idx++, until.toString());
            for (UUID member : members) {
                stmt.setString(idx++, member.toString());
            }
            stmt.setInt(idx, count);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rawRows.add(new Object[]{
                        rs.getString("uuid"),
                        rs.getString("player_name"),
                        rs.getLong("period_score"),
                        rs.getInt("total_correct"),
                        rs.getInt("total_words")
                    });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load ranked class players for period", e);
            return entries;
        }
        }

        // Step 2: ResultSet closed — no Bukkit API here (may run async); falls back to uuid.toString().
        int rank = 1;
        for (Object[] row : rawRows) {
            UUID uuid = UUID.fromString((String) row[0]);
            String name = (String) row[1];
            if (name == null || name.isEmpty()) {
                name = uuid.toString();
            }
            long periodScore = (Long) row[2];
            int periodCorrect = (Integer) row[3];
            int periodWords = (Integer) row[4];
            int periodIncorrect = Math.max(0, periodWords - periodCorrect);
            int sessions = 0; // Not easily available from aggregated query
            entries.add(new LeaderboardEntry(uuid, name, periodScore,
                    periodCorrect, periodIncorrect, sessions, rank++));
        }
        return entries;
    }
}
