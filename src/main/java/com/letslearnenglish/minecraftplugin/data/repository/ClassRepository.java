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
     * {@link #addMemberAtomic} 的四种结果——与调用方既有消息分支一一对应
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
        /** 新班已满（{@code >= maxMembers}）：未做任何写操作，旧班保留。 */
        FULL,
        /** 旧班已退、新班已进（跨班换班成功）。 */
        REASSIGNED,
        /** 玩家原本无班，直接进新班。 */
        ADDED
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
     * <p>异常语义照搬：内层 DB 方法自己吞 SQLException 并记 WARNING、返回
     * {@code null}/0/false。因此读失败时 {@code getPlayerClassId} 返回 null 会被当作
     * "无班"，{@code getClassMemberCount} 返回 0 会被当作"未满"——这与 R6-F 之前
     * 的行为完全相同（同一条代码路径），本类<b>不</b>新增新的失败语义。
     *
     * <p>锁内不投递任何消息（R9-L5），调用方按返回值在锁外分支。
     *
     * @param classId    目标班级的 {@code class_id}（调用方已按班名查到
     *                   {@link DatabaseManager.ClassRow}，本方法不再校验班级存在性）
     * @param playerId   玩家 UUID
     * @param joinedAt   进班时间戳（毫秒）；调用方传 {@code System.currentTimeMillis()}
     * @param maxMembers 班级人数上限（来自 {@code class-system.max-members}）
     * @return 四种结果之一，永不返回 null
     */
    public AddResult addMemberAtomic(int classId, UUID playerId, long joinedAt, int maxMembers) {
        // 预先初始化：两条 early-return 分支在锁内直接返回，赋值只发生在"真的要写"
        // 的那条路径上；显式初值让"锁外读这两个变量"不依赖 DA 分析的边角规则。
        boolean insertRejected = false;
        AddResult result = AddResult.ADDED;
        synchronized (db.getConnectionLock()) {
            // R6 硬约束：锁内首行取连接——先确认共享连接可用（必要时触发自愈重连），
            // 让下面四条语句全部落在同一个已校验的连接上，不跨重连窗口。
            db.getConnection();
            Integer currentClassId = db.getPlayerClassId(playerId);
            if (currentClassId != null && currentClassId == classId) {
                // 已在本班：幂等（无写操作）
                return AddResult.ALREADY_MEMBER;
            }
            if (db.getClassMemberCount(classId) >= maxMembers) {
                // 满员：拒绝且不退旧班（防丢班）
                return AddResult.FULL;
            }
            boolean reassigned = currentClassId != null;
            if (reassigned) {
                db.removeClassMember(currentClassId, playerId);
            }
            insertRejected = !db.addClassMember(classId, playerId, joinedAt);
            result = reassigned ? AddResult.REASSIGNED : AddResult.ADDED;
        }
        if (insertRejected) {
            // addClassMember 自己已对 SQL 失败记 WARNING；这里只补一条 FINE，覆盖
            // "check 看到无行、INSERT 撞 PK"的并发窗口（多服同时加同人），不升级
            // 日志级别、不改变返回值语义（与 R6-F 之前的静默继续一致）。
            // 放在锁外：日志可能是阻塞 IO，不该占着连接锁。
            logger.log(Level.FINE, "addClassMember returned false for " + playerId
                    + " in class " + classId + " (concurrent add or SQL failure)");
        }
        return result;
    }
}
