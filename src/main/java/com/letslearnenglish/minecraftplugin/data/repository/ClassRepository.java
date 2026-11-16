package com.letslearnenglish.minecraftplugin.data.repository;

import com.letslearnenglish.minecraftplugin.data.DatabaseManager;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 班级成员编排仓储（R6-F）—— 承接 {@code AdminCommandHandler.applyClassAdd} 里
 * 唯一一段"借 {@code connectionLock} 做多语句原子段"的业务编排。
 *
 * <p><b>为什么要有这个类</b>：换班（一人一班）需要"查当前班 → 判是否同班 →
 * 判新班满员 → 退旧班 → 进新班"四条语句串成一个原子段，靠调用方持
 * {@link DatabaseManager#getConnectionLock()} 才成立。R6-F 之前这是 {@code data/}
 * 之外<b>唯一</b>一处 {@code synchronized (db.getConnectionLock())}（其余引用点已在
 * R6 各流陆续迁入 {@code data/repository/*} 与 {@code PlayerDataStore}），把"锁的
 * 持有者"和"锁的语义"拆在两个包里。本类把整段编排下沉，使 {@code data/} 之外对连接锁
 * 的引用归零——锁纪律从此只有 {@code data/} 一个守门人。
 *
 * <p><b>本类不含 SQL</b>：四条语句仍由 {@link DatabaseManager} 的既有 public 方法执行
 * （{@code getPlayerClassId} / {@code getClassMemberCount} / {@code removeClassMember} /
 * {@code addClassMember}）。这里只负责<b>顺序</b>与<b>原子性</b>，不重写语句、不复制
 * WHERE 子句 —— 避免出现第二套"班级成员"SQL 真相源。
 *
 * <p><b>锁纪律</b>：{@link #addMemberAtomic} 在 {@code synchronized (db.getConnectionLock())}
 * 内完成，锁内<b>首行</b>取连接（{@code db.getConnection()}），与
 * {@code AchievementRepository} / {@code DailyChallengeRepository} /
 * {@code PlayerDirectoryRepository} / {@code ReportRepository} 的"锁内取连接"范式一致：
 * 先确认连接可用，再让后续四条语句落在同一个已校验的连接上，不跨
 * {@link DatabaseManager#initialize()} 的重连窗口。内层 DB 方法自身也持同一把锁
 * （{@code synchronized} 可重入），嵌套安全，与 R6-F 之前的实现逐字等价。
 *
 * <p><b>锁内零消息投递（R9-L5）</b>：本类不接触 Bukkit、不调 {@code sendMessage}、
 * 不碰调度器——只返回一个枚举。消息投递全部留给调用方在锁外做，持锁区间因此只剩
 * 四条同步语句。
 *
 * <p><b>线程模型</b>：方法同步阻塞在调用线程。现存唯一调用方
 * （{@code AdminCommandHandler} 的 {@code runClassAsync} 异步段）在异步线程调用；
 * 本类不自行派发调度，也不假定主线程。方法内不做 UUID 解析 / 名字回落等 Bukkit 侧工作。
 *
 * <p><b>生命周期</b>：与其它仓储一致，表前缀由 {@link DatabaseManager} 现读
 * （本类不碰前缀），因此不存在构造期快照与重连换前缀的漂移问题；调用方仍须在
 * {@link DatabaseManager#initialize()} 之后构造本类。
 */
public class ClassRepository {

    /**
     * {@link #addMemberAtomic} 的几种结果——与调用方既有消息分支一一对应
     * （{@code ALREADY_MEMBER}/{@code ADDED} → {@code class.member-added}，
     * {@code FULL} → {@code class.member-full}，{@code REASSIGNED} → {@code class.reassigned}）。
     *
     * <p>注意 {@code REASSIGNED} 只在<b>真的换了班</b>时返回：原实现里
     * {@code reassigned} 标志在"满员被拒"时也是 true，但那条分支永远先被
     * {@code full} 短路掉、走不到换班消息。本枚举把这一点固化为类型：
     * {@code FULL} 与 {@code REASSIGNED} 互斥，调用方不必再判优先级。
     */
    public enum AddResult {
        /** 玩家已在新班内：幂等，未做任何写操作。 */
        ALREADY_MEMBER,
        /** 新班已满：未留下本次插入（预检 {@code >= maxMembers} 命中，或插入后自证超员已撤销本次插入）。
         *  换班路径下旧班已加回，但"{@code class_members} 行数"瞬时磁盘状态不保证与行数上限一致
         *  （无 DB 级"每班行数"约束，见 {@link #addMemberAtomic} 的 dc-01 之二）；调用方按"未加入新班"报。 */
        FULL,
        /** 旧班已退、新班已进（跨班换班成功）。 */
        REASSIGNED,
        /** 玩家原本无班，直接进新班。 */
        ADDED,
        /** 未生效。状态是否已回到调用前由<b>补偿后回读</b>确认：回读不可确认、或玩家落在非旧班时
         *  记 SEVERE 需人工核对，故此值不等于"库内状态已复原"。调用方不得报成功。 */
        WRITE_FAILED
    }

    private final DatabaseManager db;
    private final Logger logger;

    /**
     * @param db     数据库管理器（连接、连接锁的唯一来源；本类不碰表前缀）
     * @param logger 插件日志器（异常路径记 FINE；调用方传 {@code plugin.getLogger()}）
     */
    public ClassRepository(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /**
     * R6-F：把"查当前班 → 判是否同班 → 判满员 → 退旧班 + 进新班"四条语句包成
     * 一个原子段，语义与 R6-F 之前 {@code AdminCommandHandler.applyClassAdd} 的
     * 持锁块<b>逐句一致</b>（顺序不变、条件不变、副作用不变）：
     *
     * <ol>
     *   <li>{@code currentClassId = db.getPlayerClassId(playerId)}</li>
     *   <li>{@code currentClassId != null && currentClassId == classId} → {@code ALREADY_MEMBER}</li>
     *   <li>否则 {@code full = db.getClassMemberCount(classId) >= maxMembers}</li>
     *   <li>未满：若 {@code currentClassId != null} 先
     *       {@code db.removeClassMember(currentClassId, playerId)}，再
     *       {@code db.addClassMember(classId, playerId, joinedAt)}</li>
     * </ol>
     *
     * <p>满员时<b>不退旧班</b>（原实现注释："满则拒绝，防丢班"）——玩家留在原班，
     * 返回 {@link AddResult#FULL}。
     *
     * <p><b>跨进程满员（dc-01 之二 / round4-09 #1）</b>：{@code count >= maxMembers} 预检与
     * INSERT 之间没有"每班行数上限"的 DB 约束兜底（{@code class_members} 只有
     * {@code PK(class_id, uuid)}），多服共享库时两实例可各读到 29 并各插一行。故本方法在
     * 插入后<b>自证不超员</b>：超员即撤销本次插入（换班者同时把旧班加回）并报 {@code FULL}，
     * 使超员是瞬时状态而非长期状态。<b>已知残留</b>：彻底消除需要 DB 级约束（
     * {@code schema.sql} 的 {@code class_members} 加 {@code UNIQUE} + {@code DatabaseManager}
     * 的建表同形），两者均不在本次授权的写入范围内，见 residual_risks。
     *
     * <p>失败信号（dc-01/dc-02）：内层 DB 方法把 SQL 失败压成 {@code null}/0/false，与"无班/空班"不可区分，
     * 故改以可读性探针 + 锁内回读驱动决策；进新班失败时把玩家<b>加回旧班</b>，绝不返回 {@code REASSIGNED}。
     *
     * <p>锁内不投递任何消息（R9-L5），调用方按返回值在锁外分支。
     *
     * @param classId    目标班级的 {@code class_id}（调用方已按班名查到
     *                   {@link DatabaseManager.ClassRow}，本方法不再校验班级存在性）
     * @param playerId   玩家 UUID
     * @param joinedAt   进班时间戳（毫秒）；调用方传 {@code System.currentTimeMillis()}
     * @param maxMembers 班级人数上限（来自 {@code class-system.max-members}）
     * @return 五种结果之一，永不返回 null
     */
    public AddResult addMemberAtomic(int classId, UUID playerId, long joinedAt, int maxMembers) {
        boolean lostClass = false;
        AddResult result;
        synchronized (db.getConnectionLock()) {
            db.getConnection();
            if (db.countRows("class_members") < 0) return AddResult.WRITE_FAILED; // dc-02
            Integer currentClassId = db.getPlayerClassId(playerId);
            if (currentClassId != null && currentClassId == classId) return AddResult.ALREADY_MEMBER;
            if (db.getClassMemberCount(classId) >= maxMembers) return AddResult.FULL;
            boolean reassigned = currentClassId != null;
            if (reassigned && !db.removeClassMember(currentClassId, playerId)) {
                return AddResult.WRITE_FAILED; // 退旧班失败：状态未变，不写新班
            }
            db.addClassMember(classId, playerId, joinedAt);
            Integer after = db.getPlayerClassId(playerId); // dc-01：回读为准，不信歧义布尔
            if (after == null) after = db.getPlayerClassId(playerId); // 回读失败再确认一次，避免把读失败当写失败
            // 插入后自证不超员（跨进程满员，见 javadoc 的 dc-01 之二）。超额即撤销
            // **本次**插入；换班者同时把旧班加回。并发超额时各实例各撤自己那行。
            boolean joinedTarget = after != null && after == classId;
            int newCount = joinedTarget ? db.getClassMemberCount(classId) : 0;
            // 刚成功插入并回读到本班，计数却 <= 0 → 该 COUNT 查询失败（getClassMemberCount
            // 把读失败压成 0）。此时无法自证不超员：写本身是真成功的，不退回 WRITE_FAILED
            // （那会谎报"没加进去"），只记 WARNING 披露校验缺口。
            if (joinedTarget && newCount <= 0) {
                logger.log(Level.WARNING, "Cannot verify class capacity for " + playerId
                        + " (class " + classId + "): member count query failed after insert");
            }
            boolean overCapacity = joinedTarget && newCount > maxMembers;
            if (overCapacity) {
                db.removeClassMember(classId, playerId);
                if (reassigned) {
                    db.addClassMember(currentClassId, playerId, joinedAt); // 补偿：旧班加回
                    Integer back = db.getPlayerClassId(playerId);
                    lostClass = back == null || back.intValue() != currentClassId.intValue();
                }
                result = AddResult.FULL;
            } else if (joinedTarget) {
                result = reassigned ? AddResult.REASSIGNED : AddResult.ADDED;
            } else if (reassigned) {
                db.addClassMember(currentClassId, playerId, joinedAt); // 补偿：旧班加回
                Integer back = db.getPlayerClassId(playerId); // 复原因以回读确认落在旧班为准
                // 补偿写入的返回值折叠了"已在班"与"写失败"，故只认回读；back 为别的班 = 他实例已写别班
                lostClass = back == null || back.intValue() != currentClassId.intValue();
                result = AddResult.WRITE_FAILED;
            } else {
                if (after != null) {
                    db.removeClassMember(classId, playerId); // 一人两班 → 撤销本次插入
                }
                result = AddResult.WRITE_FAILED;
            }
        }
        if (lostClass) {
            logger.log(Level.SEVERE, "Lost class membership for " + playerId
                    + " (rollback failed) — manual repair required");
        }
        return result;
    }
}
