package com.letslearnenglish.minecraftplugin.core.duel;

import java.util.UUID;

/**
 * R26：宽限计时器<b>到点那一刻的判决</b> —— 纯值，不含任何副作用。
 *
 * <h2>为什么要有这个类</h2>
 * 断线宽限到点的分支（{@code DuelManager.handleDisconnect} 的计时 lambda）是整块断线
 * 逻辑里最脆的一段：它要同时决定三件互不相同的事，而这三件事原先靠两个 {@code if} 和
 * 一次 {@code containsKey} <b>隐式</b>保证：
 * <ol>
 *   <li>对手<b>在线</b> → 当场发 {@code duel.opponent-timeout}（「X 未能在 N 秒内重连，你赢了」）；</li>
 *   <li>对手<b>离线</b>且本房间还没有通告 → 补排<b>中性</b>的 {@code duel.duel-already-over}。
 *       理由：{@code cleanup} 马上会把对手的宽限任务一并 cancel，对手的计时永远跑不到
 *       自己的分支，进服后必须有一句交代；但**不能**用 {@code lost-by-disconnect} ——
 *       那会说「你被判负，X 获胜」，而事实完全相反：判负的是先到点的那一方；</li>
 *   <li>对手离线但队列里<b>已有更具体的通告</b>（GM 解散等）→ 保持原样，中性文案不得吃掉它。</li>
 * </ol>
 * 这三条只要有一条被写成另一条，玩家收到的就是<b>与事实颠倒的结论</b>，而单测里唯一的
 * 观察面是「哪个玩家收到了哪个 i18n 键」。把判决抽成纯值之后，这三条变成可以直接断言
 * 的输出，不再需要启调度器、造 {@code Player} 桩、手动 tick 30 次才能验证。
 *
 * <h2>边界：只决定「给谁发哪条」，不决定「怎么发」</h2>
 * {@code sendMessage} / {@code BukkitTask.cancel} / {@code removeScoreboard} /
 * {@code cleanup} <b>全部留在</b> {@link DuelManager}。本类型不读 Bukkit、不读配置、
 * 不碰任何 {@code Map} —— 在线与否、有没有排过通告，都由调用方作为布尔参数传入。
 *
 * <h2>关于 {@code {player}} 占位符</h2>
 * 本类型<b>不</b>携带任何玩家名字。占位符取值是「房间里另一个人叫什么」，与判负方向
 * 强相关而与本判决无关；它由调用方用同源的 {@code otherDuelistName(room, ...)} 取。
 * 因此 {@link #decide} 对 p1、p2 两个方向必须给出<b>完全相同</b>的判决 —— 这一点由
 * {@code DuelGraceOutcomeTest} 逐条钉住。
 */
public sealed interface DuelGraceOutcome {

    /** 判负方收到的键（在线直发 / 离线排队同一条）。 */
    String KEY_FORFEITER = "duel.lost-by-disconnect";

    /** 对手在线时收到的键（胜者措辞，带 {player} 与 {seconds} 占位）。 */
    String KEY_WINNER_ONLINE = "duel.opponent-timeout";

    /**
     * 对手离线时的<b>中性</b>补偿键。
     *
     * <p>刻意与 {@link #KEY_FORFEITER} 分开：对手在这个时序里是<b>受益方</b>，
     * 「你被判负、X 获胜」与事实颠倒。本键无占位符，多传的名字无害。
     */
    String KEY_PEER_NEUTRAL = "duel.duel-already-over";

    /**
     * 宽限到点、<b>要</b>判负：{@code expiredPlayer} 负、对手胜。
     *
     * @param forfeiterKey   判负方要收到的 i18n 键（{@link #KEY_FORFEITER}）
     * @param opponentNotice 对手（本场胜者）那一侧的处理 —— 三选一，见 {@link OpponentNotice}
     */
    record Forfeit(String forfeiterKey, OpponentNotice opponentNotice) implements DuelGraceOutcome {
        public Forfeit {
            if (forfeiterKey == null || forfeiterKey.isEmpty()) {
                throw new IllegalArgumentException("forfeiterKey must not be empty");
            }
            if (opponentNotice == null) {
                throw new IllegalArgumentException("opponentNotice must not be null");
            }
        }
    }

    /**
     * <b>不</b>判负：调用方的前提不成立（房间不存在 / 已不是活跃对局 / 该玩家不在房里）。
     *
     * <p>当前调用点上游已有「房间已无」的分支（计时 lambda 里 {@code activeDuels.get} 那一段），
     * 它同样负责给掉线方一句中性交代；本类型在这里只保证「判决函数自身绝不判死房间」。
     *
     * @param reason 便于测试与日志辨识的短标记（不是面向玩家的文案）
     */
    record Defer(String reason) implements DuelGraceOutcome {
        public static final String REASON_INACTIVE_ROOM = "inactive-room";
        public static final String REASON_NOT_A_MEMBER = "not-a-member";

        public Defer {
            if (reason == null || reason.isEmpty()) {
                throw new IllegalArgumentException("reason must not be empty");
            }
        }
    }

    /**
     * 对手（本场胜者）那一侧该收到什么。
     *
     * <p>三种情况互斥，且<b>绝不可合并</b>：把 {@link QueueNeutral} 写成
     * {@link Direct} 会让已进服的人下次再收一遍；把它写成 {@code KEY_FORFEITER}
     * 会对胜者说「你被判负」；把 {@link PreserveExisting} 吞进 {@link QueueNeutral}
     * 会让 GM 解散这类更具体的交代被中性兜底覆盖。
     */
    sealed interface OpponentNotice {

        /** 对手在线：当场发 {@link #KEY_WINNER_ONLINE}（并收走他的对战界面与音效）。 */
        record Direct(String key) implements OpponentNotice {
            public Direct {
                if (key == null || key.isEmpty()) {
                    throw new IllegalArgumentException("key must not be empty");
                }
            }
        }

        /** 对手离线且本房间尚无通告：补排 {@link #KEY_PEER_NEUTRAL}，等其进服补发。 */
        record QueueNeutral(String key) implements OpponentNotice {
            public QueueNeutral {
                if (key == null || key.isEmpty()) {
                    throw new IllegalArgumentException("key must not be empty");
                }
            }
        }

        /** 对手离线但队列里已有更具体的通告：保留原样，不重复排、不覆盖。 */
        record PreserveExisting() implements OpponentNotice { }
    }

    /**
     * 判决：宽限到点后该给谁发哪条文案。
     *
     * <p><b>纯函数</b>：同样的入参恒得同样的结果，无副作用、无外部状态。
     *
     * @param room                   到点那一刻的房间；{@code null} 或已 {@code !active} → {@link Defer}
     * @param expiredPlayer          宽限到点的玩家（判负候选）
     * @param opponentOnline         对手此刻是否在线（{@code Player != null && isOnline()} 已由调用方折叠）
     * @param opponentAlreadyNotified 对手在该房间是否已有一条待投递的离线通告
     *                               （调用方传 {@code offlineNotices.containsKey(opponentId)}）
     * @return 判决结果；三种对手处理分支见 {@link OpponentNotice}
     */
    static DuelGraceOutcome decide(DuelRoom room,
                                   UUID expiredPlayer,
                                   boolean opponentOnline,
                                   boolean opponentAlreadyNotified) {
        if (room == null || !room.isActive()) {
            return new Defer(Defer.REASON_INACTIVE_ROOM);
        }
        if (expiredPlayer == null || room.getOpponent(expiredPlayer) == null) {
            return new Defer(Defer.REASON_NOT_A_MEMBER);
        }

        // 对手在线时**不看**队列：他已经收到（或将当场收到）胜者措辞；此刻队列里若还躺着
        // 一条旧通告（如 GM 解散后他又回来了），那是给「下一次进服」的残留，与本次判决无关。
        OpponentNotice notice;
        if (opponentOnline) {
            notice = new OpponentNotice.Direct(KEY_WINNER_ONLINE);
        } else if (opponentAlreadyNotified) {
            notice = new OpponentNotice.PreserveExisting();
        } else {
            notice = new OpponentNotice.QueueNeutral(KEY_PEER_NEUTRAL);
        }
        return new Forfeit(KEY_FORFEITER, notice);
    }
}
