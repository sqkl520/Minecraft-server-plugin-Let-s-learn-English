package com.letslearnenglish.minecraftplugin.core.duel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R26：{@link DuelGraceOutcome#decide} 的纯函数测试 —— <b>零 mock、零 Bukkit、零调度器</b>。
 *
 * <h2>这一组为什么存在</h2>
 * 宽限到点的分支里有一个<b>特别容易写反</b>的语义：对手离线时补排的是<b>中性</b>文案
 * {@code duel.duel-already-over}，不是 {@code duel.lost-by-disconnect}。理由是那时对手
 * 是<b>受益方</b>（判负的是先到点的这一方），发"你被判负、X 获胜"会把胜负说反。
 *
 * <p>这个语义原先靠两个 {@code if} 加一次 {@code containsKey} 隐式保证，
 * 回归网 {@code DuelDoubleDisconnectMatrixTest} 只能靠「造 Player 桩 + 手动 tick 30 次 +
 * 反射读 offlineNotices」间接观察。抽成纯函数后，三种对手处理变成可以直接断言的值 ——
 * 本类就是那层断言，它与矩阵测试互补而不是替代：矩阵测「副作用跑得对不对」，
 * 本类测「判决本身对不对」。
 *
 * <h2>关于「两个方向必须同判」</h2>
 * {@code decide} 的签名里没有名字、没有「我是 p1 还是 p2」，因为占位符取值
 * （{@code {player}} = 房里另一个人叫什么）由调用方用同源的 {@code otherDuelistName}
 * 取。本类用 {@link #bothDirectionsGiveSameVerdict} 把这条<b>结构性</b>地钉住：
 * 万一将来有人往签名里塞进「方向」参数并写歪一个分支，这里会红。
 */
class DuelGraceOutcomeTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000026a1a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000026b2b2");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-00000026ffff");

    private static DuelRoom room() {
        return new DuelRoom(A, "Alice", B, "Bob");
    }

    // ==================================================================
    // 1. 三种文案分支 —— 这是本次重构的全部意义，逐条钉住
    // ==================================================================

    @Test
    @DisplayName("对手在线 → 当场发 duel.opponent-timeout（胜者措辞），不是 lost-by-disconnect")
    void opponentOnlineGetsWinnerWording() {
        DuelGraceOutcome outcome = DuelGraceOutcome.decide(room(), A, true, false);

        DuelGraceOutcome.Forfeit forfeit = assertInstanceOf(DuelGraceOutcome.Forfeit.class, outcome);
        assertEquals(DuelGraceOutcome.KEY_FORFEITER, forfeit.forfeiterKey(),
                "判负方的键恒为 lost-by-disconnect");
        DuelGraceOutcome.OpponentNotice.Direct direct = assertInstanceOf(
                DuelGraceOutcome.OpponentNotice.Direct.class, forfeit.opponentNotice(),
                "对手在线时必须当场直发 —— 排队会让已在线的人下次进服再收一遍");
        assertEquals(DuelGraceOutcome.KEY_WINNER_ONLINE, direct.key());
        assertNotEquals(DuelGraceOutcome.KEY_FORFEITER, direct.key(),
                "胜者绝不能收到「你被判负」");
    }

    @Test
    @DisplayName("对手离线且无通告 → 补排中性文案 duel-already-over（不得是 lost-by-disconnect）")
    void offlineOpponentGetsNeutralWording() {
        DuelGraceOutcome outcome = DuelGraceOutcome.decide(room(), A, false, false);

        DuelGraceOutcome.Forfeit forfeit = assertInstanceOf(DuelGraceOutcome.Forfeit.class, outcome);
        DuelGraceOutcome.OpponentNotice.QueueNeutral neutral = assertInstanceOf(
                DuelGraceOutcome.OpponentNotice.QueueNeutral.class, forfeit.opponentNotice());
        assertEquals(DuelGraceOutcome.KEY_PEER_NEUTRAL, neutral.key());
        assertNotEquals(DuelGraceOutcome.KEY_FORFEITER, neutral.key(),
                "对手在本时序里是**受益方** —— 对他说「你被判负，X 获胜」与事实颠倒");
        assertNotEquals(DuelGraceOutcome.KEY_WINNER_ONLINE, neutral.key(),
                "他不在线，发不出去；这个键必须走离线队列");
    }

    @Test
    @DisplayName("对手离线但已有通告 → PreserveExisting（不重复排、不覆盖 GM 解散等更具体原因）")
    void offlineOpponentAlreadyNotifiedIsPreserved() {
        DuelGraceOutcome outcome = DuelGraceOutcome.decide(room(), A, false, true);

        DuelGraceOutcome.Forfeit forfeit = assertInstanceOf(DuelGraceOutcome.Forfeit.class, outcome);
        assertInstanceOf(DuelGraceOutcome.OpponentNotice.PreserveExisting.class,
                forfeit.opponentNotice(),
                "已有通告不覆盖 —— 队列是覆盖写语义，无条件再排会吃掉更具体的原因");
    }

    @Test
    @DisplayName("对手在线时「已有通告」不影响判决 —— 在线分支优先，队列里的残留留给下一次进服")
    void onlineOpponentWinsRegardlessOfStaleQueueEntry() {
        DuelGraceOutcome outcome = DuelGraceOutcome.decide(room(), A, true, true);

        DuelGraceOutcome.Forfeit forfeit = assertInstanceOf(DuelGraceOutcome.Forfeit.class, outcome);
        assertInstanceOf(DuelGraceOutcome.OpponentNotice.Direct.class, forfeit.opponentNotice(),
                "他在线就必须当场收到胜者措辞；队列里有旧条目不能把这条降级成「什么都不做」");
    }

    @Test
    @DisplayName("三种分支互斥且穷举 —— 同一次判决只可能命中一种对手处理")
    void threeBranchesAreMutuallyExclusive() {
        DuelRoom room = room();
        List<DuelGraceOutcome.OpponentNotice> notices = List.of(
                ((DuelGraceOutcome.Forfeit) DuelGraceOutcome.decide(room, A, true, false))
                        .opponentNotice(),
                ((DuelGraceOutcome.Forfeit) DuelGraceOutcome.decide(room, A, false, false))
                        .opponentNotice(),
                ((DuelGraceOutcome.Forfeit) DuelGraceOutcome.decide(room, A, false, true))
                        .opponentNotice());

        assertEquals(DuelGraceOutcome.OpponentNotice.Direct.class, notices.get(0).getClass());
        assertEquals(DuelGraceOutcome.OpponentNotice.QueueNeutral.class, notices.get(1).getClass());
        assertEquals(DuelGraceOutcome.OpponentNotice.PreserveExisting.class, notices.get(2).getClass());
    }

    // ==================================================================
    // 2. p1 / p2 两个方向 —— 判决必须与「谁先掉线」无关
    // ==================================================================

    /**
     * 表驱动：同一组前提对 p1、对 p2 各算一次，结果必须相同。
     *
     * <p>这条防的是一类容易写出来的错误：把「判负方」硬编码成 {@code room.getPlayer1()}
     * （于是 p2 掉线也判 p1 负，胜负颠倒）。{@code decide} 的签名里没有方向，但签名是
     * 可以被改的 —— 本用例钉住的是<b>行为</b>，不是签名形状。
     */
    @ParameterizedTest(name = "对 {0} 到点：online={1}, alreadyNotified={2} → {3}")
    @MethodSource("bothDirectionCases")
    @DisplayName("p1 / p2 两个方向给出完全相同的判决")
    void bothDirectionsGiveSameVerdict(String label, boolean online, boolean notified,
                                       String expectedKind) {
        DuelRoom room = room();
        String atA = kindOf(DuelGraceOutcome.decide(room, A, online, notified));
        String atB = kindOf(DuelGraceOutcome.decide(room, B, online, notified));

        assertEquals(expectedKind, atA, "对 p1（" + label + "）的判决");
        assertEquals(expectedKind, atB, "对 p2（" + label + "）的判决 —— 判负方不得硬编码成 p1");
        assertEquals(atA, atB, "两个方向必须同判");
    }

    static Stream<Arguments> bothDirectionCases() {
        return Stream.of(
                Arguments.of("对手在线", true, false, "DIRECT"),
                Arguments.of("对手在线且有残留通告", true, true, "DIRECT"),
                Arguments.of("对手离线无通告", false, false, "QUEUE_NEUTRAL"),
                Arguments.of("对手离线已有通告", false, true, "PRESERVE"));
    }

    // ==================================================================
    // 3. 不该判负的前提 —— 房间已拆 / 不是成员
    // ==================================================================

    @Test
    @DisplayName("房间已 !active → Defer（该判负的时刻早过去了，不得再判一次）")
    void inactiveRoomDefers() {
        DuelRoom room = room();
        room.setActive(false);

        DuelGraceOutcome outcome = DuelGraceOutcome.decide(room, A, false, false);

        DuelGraceOutcome.Defer defer = assertInstanceOf(DuelGraceOutcome.Defer.class, outcome);
        assertEquals(DuelGraceOutcome.Defer.REASON_INACTIVE_ROOM, defer.reason());
    }

    @Test
    @DisplayName("room 为 null → Defer（不 NPE）")
    void nullRoomDefers() {
        DuelGraceOutcome outcome = DuelGraceOutcome.decide(null, A, false, false);
        assertInstanceOf(DuelGraceOutcome.Defer.class, outcome);
    }

    @Test
    @DisplayName("到点者不是房间成员 / 为 null → Defer（无从判负，也不该判别人负）")
    void nonMemberDefers() {
        DuelRoom room = room();
        assertInstanceOf(DuelGraceOutcome.Defer.class,
                DuelGraceOutcome.decide(room, OUTSIDER, false, false),
                "第三人不在这间房里 —— 判负必须基于成员身份");
        assertInstanceOf(DuelGraceOutcome.Defer.class,
                DuelGraceOutcome.decide(room, null, false, false),
                "UUID 为 null 时必须安全返回 Defer，不得 NPE");
    }

    @Test
    @DisplayName("Defer 的 reason 可辨识 —— 便于日志/测试区分两种前提不成立")
    void deferReasonsAreDistinct() {
        DuelRoom room = room();
        DuelGraceOutcome.Defer notMember = assertInstanceOf(DuelGraceOutcome.Defer.class,
                DuelGraceOutcome.decide(room, OUTSIDER, false, false));
        assertEquals(DuelGraceOutcome.Defer.REASON_NOT_A_MEMBER, notMember.reason());

        room.setActive(false);
        DuelGraceOutcome.Defer inactive = assertInstanceOf(DuelGraceOutcome.Defer.class,
                DuelGraceOutcome.decide(room, A, false, false));
        assertNotEquals(notMember.reason(), inactive.reason(),
                "两种 Defer 前提不同，reason 必须能区分");
    }

    // ==================================================================
    // 4. 纯函数性质 —— 同样的入参恒得同样的结果，且不改动房间
    // ==================================================================

    @Test
    @DisplayName("纯：同一组入参重复调用结果恒等，且不改动房间任何状态")
    void decideIsPureAndSideEffectFree() {
        DuelRoom room = room();
        room.setPaused(true);
        int score1Before = room.getScore1();
        int score2Before = room.getScore2();
        long roundBefore = room.getWordRoundId();

        DuelGraceOutcome first = DuelGraceOutcome.decide(room, A, false, false);
        DuelGraceOutcome second = DuelGraceOutcome.decide(room, A, false, false);

        assertEquals(first, second, "纯函数：同样入参必须给出相等的结果（record 值语义）");
        assertEquals(room.isActive(), true, "decide 不得拆房 —— 副作用全在 DuelManager");
        assertEquals(true, room.isPaused(), "decide 不得动停盘位（它只读，不写）");
        assertEquals(score1Before, room.getScore1(), "decide 不碰比分");
        assertEquals(score2Before, room.getScore2());
        assertEquals(roundBefore, room.getWordRoundId(), "decide 不推进回合");
    }

    @Test
    @DisplayName("纯：同一房间上两个不同的到点者互不影响（无隐藏共享状态）")
    void decidingForOnePlayerDoesNotAffectTheOther() {
        DuelRoom room = room();
        DuelGraceOutcome atA = DuelGraceOutcome.decide(room, A, false, false);
        DuelGraceOutcome atB = DuelGraceOutcome.decide(room, B, false, false);

        assertEquals(atA, atB, "同一组前提下，先算谁的结果都必须一样 —— decide 不缓存任何中间状态");
    }

    @Test
    @DisplayName("纯：对手在线状态翻转会翻转判决（证明这个入参真的参与判决，不是死参数）")
    void togglingOnlineFlipsTheNoticeBranch() {
        DuelRoom room = room();
        DuelGraceOutcome online = DuelGraceOutcome.decide(room, A, true, false);
        DuelGraceOutcome offline = DuelGraceOutcome.decide(room, A, false, false);

        assertNotEquals(online, offline, "在线与否必须改变判决，否则这个入参是死参数");
    }

    // ==================================================================
    // 5. 构造守卫 —— 非法值不得静默变成「一条空键」
    // ==================================================================

    @Test
    @DisplayName("构造守卫：空键 / null 一律拒绝（不静默退化成空 i18n key）")
    void constructorsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new DuelGraceOutcome.Forfeit("", new DuelGraceOutcome.OpponentNotice.PreserveExisting()));
        assertThrows(IllegalArgumentException.class,
                () -> new DuelGraceOutcome.Forfeit(DuelGraceOutcome.KEY_FORFEITER, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DuelGraceOutcome.OpponentNotice.Direct(""));
        assertThrows(IllegalArgumentException.class,
                () -> new DuelGraceOutcome.Defer(""));
        assertThrows(IllegalArgumentException.class,
                () -> new DuelGraceOutcome.Defer(null));
    }

    @Test
    @DisplayName("三个键常量互不相同 —— 合并任意两个都会造成「说反了」或「重复播报」")
    void keyConstantsAreDistinct() {
        assertNotEquals(DuelGraceOutcome.KEY_FORFEITER, DuelGraceOutcome.KEY_WINNER_ONLINE);
        assertNotEquals(DuelGraceOutcome.KEY_FORFEITER, DuelGraceOutcome.KEY_PEER_NEUTRAL);
        assertNotEquals(DuelGraceOutcome.KEY_WINNER_ONLINE, DuelGraceOutcome.KEY_PEER_NEUTRAL);
        assertTrue(DuelGraceOutcome.KEY_FORFEITER.startsWith("duel."),
                "键必须落在 duel 命名空间（i18n 对齐测试按前缀扫描）");
    }

    /** 把判决折叠成可读的短标签，供表驱动断言用。 */
    private static String kindOf(DuelGraceOutcome outcome) {
        if (outcome instanceof DuelGraceOutcome.Defer) {
            return "DEFER";
        }
        DuelGraceOutcome.OpponentNotice notice =
                ((DuelGraceOutcome.Forfeit) outcome).opponentNotice();
        if (notice instanceof DuelGraceOutcome.OpponentNotice.Direct) {
            return "DIRECT";
        }
        if (notice instanceof DuelGraceOutcome.OpponentNotice.QueueNeutral) {
            return "QUEUE_NEUTRAL";
        }
        return "PRESERVE";
    }
}
