package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Daily Challenge Manager
 *
 * Two challenge systems:
 * 1. Behavioral challenges (ChallengeType enum) — task-based, e.g. "answer 10 words"
 * 2. Word challenges — N random words selected from WordBank each day
 *
 * Progress is tracked in the database and checked after relevant actions.
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
    private final Set<UUID> wordChallengeClaimedToday;

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

    public DailyChallengeManager(LetsLearnEnglish plugin, WordManagerImpl wordManager) {
        this.plugin = plugin;
        this.wordManager = wordManager;
        // M8 附带 + F1-10：已领取标记集合（Phase 1 流 C 在用，不能删），换线程安全实现
        this.wordChallengeClaimedToday = ConcurrentHashMap.newKeySet();
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
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String checkSql = "SELECT COUNT(*) FROM " + tablePrefix
                + "daily_challenges WHERE uuid = ? AND challenge_date = ?";

        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    // Already assigned — but also ensure word challenges are assigned
                    ensureWordChallengesAssigned(playerId);
                    return;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check daily challenges for " + playerId, e);
            return;
        }
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

        String insertSql = "INSERT OR REPLACE INTO " + tablePrefix
                + "daily_challenges (uuid, challenge_date, challenge_id, progress, completed, claimed) VALUES (?, ?, ?, 0, 0, 0)";
        if (!plugin.getDatabaseManager().isSQLite()) {
            // F2-01：MySQL 分支补 ON DUPLICATE KEY，保并发幂等 —— 双路径（join 与
            // updateProgress 的 R-32 补分配）同时走 checkAndAssign 时，第二次 INSERT
            // 撞主键 (uuid, challenge_date, challenge_id) 不再抛 SQLException 记 WARNING，
            // 而是 no-op 忽略（challenge_id = challenge_id 为 MySQL 8 合法自赋值）。
            // 撞键时不动既有行（比 SQLite 分支的 INSERT OR REPLACE 更保守：不会把
            // 已积累的 progress/completed 重置）。正常路径有外层 COUNT 预检，本就不会
            // 撞键，此保护仅兜住并发窗口。
            insertSql = "INSERT INTO " + tablePrefix
                    + "daily_challenges (uuid, challenge_date, challenge_id, progress, completed, claimed) VALUES (?, ?, ?, 0, 0, 0) "
                    + "ON DUPLICATE KEY UPDATE challenge_id = challenge_id";
        }

        Connection insertConn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = insertConn.prepareStatement(insertSql)) {
            for (ChallengeType ct : picked) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, today);
                stmt.setString(3, ct.id);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to assign daily challenges for " + playerId, e);
        }
        }

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
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        // R-33：追加 ORDER BY challenge_id——展示（DailyChallengeGUI 的 CHALLENGE_SLOTS 槽位
        // 11/13/15/17）与领取（MiscGUIListener 按本方法返回列表的下标查位映射）两侧都走此查询，
        // 排序固定后下标与展示恒一致，不再因 SQLite 返回顺序不定而领到错项（与 F2R-03 同根）。
        String sql = "SELECT challenge_id, progress, completed, claimed FROM " + tablePrefix
                + "daily_challenges WHERE uuid = ? AND challenge_date = ? ORDER BY challenge_id";

        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ChallengeType type = ChallengeType.fromId(rs.getString("challenge_id"));
                    if (type != null) {
                        // R-79：读取侧同样 clamp —— 修复前写入的超靶旧行（如已领取的
                        // 47/10）不会再有写入机会去改写，只能在读侧收敛；展示方
                        // （DailyChallengeGUI 的 daily.progress、/le challenge）直读此值。
                        // 无逻辑分支读 progress，clamp 只影响展示。
                        list.add(new ChallengeProgress(type,
                                Math.min(rs.getInt("progress"), type.target),
                                rs.getBoolean("completed"), rs.getBoolean("claimed")));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load daily challenges for " + playerId, e);
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
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "UPDATE " + tablePrefix
                + "daily_challenges SET completed = 1, claimed = 1 "
                + "WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? "
                + "AND completed = 0 AND claimed = 0";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            stmt.setString(3, ChallengeType.AI_CHAT_5.id);
            int rows = stmt.executeUpdate();
            if (rows > 0) {
                plugin.getLogger().info("Degraded stale AI_CHAT_5 challenge for " + playerId
                        + " (AI unavailable) — auto-abandoned, no reward granted.");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to degrade stale challenges for " + playerId, e);
        }
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
     */
    public void updateProgress(UUID playerId, ChallengeType type, int increment) {
        // MC-2（S-H）：daily-challenges.enabled=false 时入口 no-op —— 不推进进度、不补分配。
        if (!plugin.getConfigManager().isDailyChallengesEnabled()) {
            return;
        }
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();

        int[] state = loadChallengeState(playerId, type, today);
        if (state == null) {
            // R-32：查无行不再静默丢弃。先 ensure 今日挑战已分配（checkAndAssign 幂等：
            // 已有则只补单词挑战、无则抽 N 条），再重查一次。仍无行说明该类型今日确实
            // 没被抽中（N < 4 时属正常），此时才返回；否则推进刚补上的进度，不再丢失。
            checkAndAssign(playerId);
            state = loadChallengeState(playerId, type, today);
            if (state == null) {
                return;
            }
        }
        int currentProgress = state[0];
        boolean alreadyCompleted = state[1] == 1;
        boolean alreadyClaimed = state[2] == 1;

        if (alreadyClaimed) return;

        int newProgress = currentProgress + increment;
        boolean nowCompleted = newProgress >= type.target;
        if (alreadyCompleted) nowCompleted = true;
        // R-79：写入侧 clamp —— completed 后继续答题/对话时 progress 原样累加，
        // 展示出来就是 "47/10" 这类超靶读数（GUI 的 CHALLENGE_SLOTS 槽位 11/13/15/17 与 /le challenge
        // 都直读本值）。封顶在 target：完成判定已在上两行算完，clamp 不影响任何
        // 状态迁移，只让展示与实际语义一致。
        newProgress = Math.min(newProgress, type.target);

        String updateSql = "UPDATE " + tablePrefix
                + "daily_challenges SET progress = ?, completed = ? WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?";
        Connection updateConn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = updateConn.prepareStatement(updateSql)) {
            stmt.setInt(1, newProgress);
            stmt.setBoolean(2, nowCompleted);
            stmt.setString(3, playerId.toString());
            stmt.setString(4, today);
            stmt.setString(5, type.id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update daily challenge progress", e);
        }
        }
    }

    /**
     * R-32：读取某挑战当日行的 {progress, completed(0/1), claimed(0/1)}；无行返回 null。
     * 抽出成独立方法，供 {@link #updateProgress} 在"查无行→ensure 分配→重查一次"中复用。
     */
    private int[] loadChallengeState(UUID playerId, ChallengeType type, String today) {
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String selectSql = "SELECT progress, completed, claimed FROM " + tablePrefix
                + "daily_challenges WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            stmt.setString(3, type.id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new int[]{
                            rs.getInt("progress"),
                            rs.getBoolean("completed") ? 1 : 0,
                            rs.getBoolean("claimed") ? 1 : 0
                    };
                }
                return null;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to load daily challenge state for " + playerId, e);
            return null;
        }
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
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String updateSql = "UPDATE " + tablePrefix
                + "daily_challenges SET completed = 1 WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? AND claimed = 0";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            stmt.setString(3, type.id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to complete daily challenge", e);
        }
        }
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
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String updateSql = "UPDATE " + tablePrefix
                + "daily_challenges SET claimed = 1 WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? AND completed = 1 AND claimed = 0";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            stmt.setString(3, type.id);
            int rows = stmt.executeUpdate();
            // MC-5：返回值走配置化读取口（展示/到账同一数值来源）；不可达分支不读配置。
            return rows > 0 ? getChallengeReward(type) : 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to claim daily challenge reward", e);
            return 0;
        }
        }
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
        return plugin.getConfigManager().getMainConfig().getDouble("daily-challenges.word-challenge-reward", 100.0);
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
        String today = LocalDate.now().toString();
        List<String> existing = plugin.getDatabaseManager().getDailyChallengeWords(playerId, today);
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

        plugin.getDatabaseManager().assignDailyChallengeWords(playerId, today, selectedIds);
        plugin.getLogger().info("Assigned " + selectedIds.size()
                + " daily challenge words to player " + playerId);
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

        // M12: pending-payout retry — all words done but money never arrived.
        // Must run before the challenge-words membership check: retry answers may
        // be outside today's challenge set or already-completed words.
        if (hasPendingWordReward(playerId, today)) {
            onAllWordChallengesComplete(player);
            return;
        }

        // Check if this word is assigned as today's challenge
        List<String> challengeWords = plugin.getDatabaseManager().getDailyChallengeWords(playerId, today);
        if (!challengeWords.contains(wordId)) return;

        // Mark it complete in DB (returns false if already completed)
        boolean freshlyCompleted = plugin.getDatabaseManager().markDailyChallengeWordComplete(playerId, today, wordId);

        // Only check all-done if this word was just now completed
        if (freshlyCompleted && plugin.getDatabaseManager().isDailyChallengeWordsCompleted(playerId, today)) {
            onAllWordChallengesComplete(player);
        }
    }

    /**
     * R-07: read-only check whether today's word-challenge reward has actually
     * landed for this player. The claimed flag is only set AFTER the Vault
     * transfer succeeds (M12), so presence in the set means paid and absence
     * means pending — no DB round trip needed. Set semantics are day-scoped:
     * entries are added on successful payout and cleared at midnight
     * rollover (see {@link #clearAllWordChallengeClaims}), so a stale
     * cross-day true is impossible.
     *
     * @param playerId the player
     * @return true if today's word reward money has arrived
     */
    public boolean isWordRewardPaidToday(UUID playerId) {
        return wordChallengeClaimedToday.contains(playerId);
    }

    /**
     * Check whether the player has finished all of today's word challenges but
     * the reward payout has not landed yet (claimed flag not set).
     *
     * @param playerId the player
     * @param today today's date string (yyyy-MM-dd)
     * @return true if payout is pending and should be retried
     */
    private boolean hasPendingWordReward(UUID playerId, String today) {
        if (wordChallengeClaimedToday.contains(playerId)) {
            return false;
        }
        return plugin.getDatabaseManager().isDailyChallengeWordsCompleted(playerId, today);
    }

    /**
     * Called when all word challenges for today are completed.
     * Deposits the flat reward and sends the celebration chat.
     *
     * <p>M12: deposit-then-mark. The claimed flag ({@link #wordChallengeClaimedToday})
     * is set only AFTER the Vault transfer succeeds. If the deposit fails, the
     * celebration is withheld, the player gets a retry hint, and the next
     * {@link #onWordCorrect} invocation (any correct answer) retries — so the
     * reward is never lost as long as the player keeps studying.
     *
     * <p>R-31 reuse: {@link #hasPendingWordRewardForDate} also calls this at the
     * midnight rollover to back-pay a prior-day pending reward (flat amount, same
     * mark), so a deposit that failed before midnight is honored instead of lost.
     */
    private void onAllWordChallengesComplete(Player player) {
        UUID playerId = player.getUniqueId();
        double reward = getWordChallengeReward();

        if (!depositWordChallengeReward(player, playerId, reward)) {
            // R-44 对齐（与 LearnCommand /le challenge 同语义）：区分两种发币失败。
            // MC-2（S-H）：改走 RewardEngine.isMoneyEnabled()（单一真相收口）——
            // "关奖励伞"（rewards.enabled=false）与"关 money 键"任一 false 均判为
            // 终态无奖可发，走 economy-disabled 分支（否则伞关闭会误判成瞬时失败、
            // 每答对一题重入 reward-pending 刷屏）。
            boolean moneyEnabled = plugin.getProgressManager().getRewardEngine().isMoneyEnabled();
            if (!moneyEnabled) {
                // 经济开关永久关闭 = 无奖可发（终态）。标记已处理避免 hasPendingWordReward
                // 恒 true 导致每答对一题重入刷屏；发一次 economy-disabled 说明，不承诺"稍后补发"。
                wordChallengeClaimedToday.add(playerId);
                player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                        "daily-challenge.economy-disabled"));
                return;
            }
            // Vault 瞬时失败 / 超 cap：保留 pending 可重试语义，不标记已领。
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.reward-pending", "reward", String.valueOf((int) reward)));
            return;
        }

        // Success: mark claimed only after money arrived.
        wordChallengeClaimedToday.add(playerId);

        // Send celebration message
        String lang = plugin.getPlayerLanguage(player);
        var msgs = plugin.getConfigManager().getMessageConfig(lang);
        String msg = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(msgs, "daily-challenge.all-complete")
                        .replace("{reward}", String.valueOf((int) reward)));
        player.sendMessage(msg);

        // FIX-CHAL-01 去强制化：不再自动弹庆祝 GUI（玩家可能在决斗/看别的界面），
        // 改为聊天提醒，玩家按需用 /le challenge 重开庆祝页。
        player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                "daily-challenge.reopen-hint"));
    }

    /**
     * Deposit the word-challenge reward.
     *
     * <p>R-34/F1-23: routes through {@link com.letslearnenglish.minecraftplugin.core.progress.RewardEngine#depositCappedReward}
     * so the payout obeys {@code money.daily-cap} and updates the today-coins ledger,
     * and so coin stats are recorded inside the reward layer — this manager no longer
     * calls {@code DatabaseManager.recordCoinsEarned} directly (layering fix).
     *
     * @param player the player
     * @param playerId the player UUID (kept for call-site symmetry)
     * @param reward the reward amount
     * @return true only if money actually arrived (Vault present, under cap, success)
     */
    private boolean depositWordChallengeReward(Player player, UUID playerId, double reward) {
        var eco = plugin.getEconomy();
        if (eco == null) {
            plugin.getLogger().warning("Word challenge reward of " + reward
                    + " for " + player.getName() + " held: Vault economy unavailable");
            return false;
        }
        // R-44 fan-in：money.enabled=false 时不发币、返回 false（走既有 reward-pending /
        // economy-disabled 语义，不标记已领、不静默吞）。depositCappedReward 本身不查此开关，
        // 故在此门把关，与 LearnCommand 的 /le challenge economy-disabled 分支衔接。
        // MC-2（S-H）：改走 RewardEngine.isMoneyEnabled()（单一真相收口）—— 奖励伞
        // （progress-system.rewards.enabled）关闭时同样不发币。
        if (!plugin.getProgressManager().getRewardEngine().isMoneyEnabled()) {
            return false;
        }
        double paid = plugin.getProgressManager().getRewardEngine().depositCappedReward(player, reward);
        return paid > 0;
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

        List<String> wordIds = plugin.getDatabaseManager().getDailyChallengeWords(playerId, today);
        List<WordChallengeProgress> result = new ArrayList<>();

        for (String wordId : wordIds) {
            boolean wordCompleted = checkSingleWordCompleted(playerId, today, wordId);
            result.add(new WordChallengeProgress(wordId, wordCompleted));
        }
        return result;
    }

    private boolean checkSingleWordCompleted(UUID playerId, String date, String wordId) {
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT completed FROM " + tablePrefix
                + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ? AND word_id = ?";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, date);
            stmt.setString(3, wordId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("completed");
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        }
        return false;
    }

    /**
     * Clear claimed state for a player (called at midnight).
     */
    public void clearWordChallengeClaim(UUID playerId) {
        wordChallengeClaimedToday.remove(playerId);
    }

    /**
     * M8：午夜跨天时清空全服的单词挑战已领标记。
     * 由 LetsLearnEnglish 的跨天定时任务调用（在 per-player 的
     * {@link #hasPendingWordRewardForDate} 补发之后执行，故补发用的是本次清空前的标记）。
     */
    public void clearAllWordChallengeClaims() {
        wordChallengeClaimedToday.clear();
    }

    /**
     * M8 跨天：统计玩家某日"已完成但未领取"的行为挑战数。
     * 供午夜作废提示用；昨日数据天然按 challenge_date 隔离，无需删行。
     */
    public int countUnclaimedForDate(UUID playerId, String date) {
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT COUNT(*) FROM " + tablePrefix
                + "daily_challenges WHERE uuid = ? AND challenge_date = ? AND completed = 1 AND claimed = 0";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, date);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to count unclaimed challenges for " + playerId, e);
        }
        }
        return 0;
    }

    /**
     * M8 跨天：指定日期的单词挑战是否"已全完成但奖励未到账"。
     *
     * <p>R-31 跨天补发：此前该日 pending 的奖励在午夜只被"作废提示"白白丢掉。现在若检测到
     * "已完成但未付"，就地补发（{@link #onAllWordChallengesComplete} 发放 flat 奖励并标记已付），
     * 补发成功即返回 false（不再算作废）。安全性关键：本方法<strong>只在午夜 rollover 的
     * per-online-player 循环里被调用</strong>（见 LetsLearnEnglish.scheduleMidnightRollover），
     * 此刻服务器自上个午夜起连续在线、{@code wordChallengeClaimedToday} 标记仍有效（尚未
     * {@link #clearAllWordChallengeClaims} 全清），因此"已付昨日"会命中 contains 直接返回 false、
     * 绝不会重复补发；重启跨午夜时该定时任务本就不触发，故也不会在重启后误补（无双发风险）。
     * 补发走主线程（rollover 非异步），对在线玩家发币安全。
     *
     * @param playerId the player
     * @param date the day to settle (rollover 传昨日)
     * @return true 仅当该日仍"已完成但未补发成功"（真正作废，用于作废提示）
     */
    public boolean hasPendingWordRewardForDate(UUID playerId, String date) {
        if (wordChallengeClaimedToday.contains(playerId)) {
            return false; // 已付，无 pending
        }
        if (!plugin.getDatabaseManager().isDailyChallengeWordsCompleted(playerId, date)) {
            return false; // 未全完成，无 pending
        }
        // R-31：已完成但未付 —— 就地补发（rollover 循环中玩家在线）。
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            onAllWordChallengesComplete(player);
            // 补发成功则 wordChallengeClaimedToday 已含该玩家 → 不再算作废。
            return !wordChallengeClaimedToday.contains(playerId);
        }
        // 玩家不在线（rollover 循环理论上只遍历在线玩家，兜底）——无法发币，仍算作废。
        return true;
    }
}
