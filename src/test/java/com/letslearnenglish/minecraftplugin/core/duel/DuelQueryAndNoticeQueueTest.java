package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 第 23 轮（续）：{@code DuelManager} 的<b>三个查询方法</b>与<b>离线通告队列</b>。
 *
 * <p>审计报告给这两块定的价码是「最便宜的一档」——它们都是纯内存判定，没有
 * Bukkit 实体、没有调度器、没有数据库，全部可以在纯 JVM 上驱动。也正因为便宜，
 * 它们此前<b>零测试</b>：{@code DuelManagerAuditTest} 只做了源码 grep（一个
 * {@code body.contains("paused")} 就能被一行注释满足）。
 *
 * <h2>本类钉住的三组事实</h2>
 * <ol>
 *   <li><b>三个查询方法是三个不同的集合</b>，不是同一个问题的三种写法：
 *     <ul>
 *       <li>{@code isInDuel} = {@code room != null && room.isActive()} ——
 *           「这行聊天该被吃成答案吗」；</li>
 *       <li>{@code isBusy} = {@code activeDuels.containsKey} ——
 *           「能不能再被挑战」。它是 {@code isInDuel} 的<b>严格超集</b>，
 *           多出来的那一格正是 {@code endDuel} 先置 {@code active=false}、
 *           摘键留到 {@code finally cleanup} 的拆解窗口；</li>
 *       <li>{@code isAnswering} = 跨模块只读 {@code WordLearningManager}
 *           的两个既有态（quiz / 错题练习）。</li>
 *     </ul>
 *     把「超集」关系写成断言，是因为它<b>反直觉</b>：超集不是冗余，而是
 *     R5-07-F4 的必需条件 —— {@code isBusy} 若在拆解窗口返回 false，一个新挑战
 *     会被接受，随后待执行的 {@code cleanup} 会把<b>新房间</b>的键一起摘掉。</li>
 *   <li><b>缺陷 R23-A2 的可执行复现与修法夹（R24 已修，路线 (b)）</b>：{@code isInDuel}
 *     <b>刻意</b>不看 {@code paused}（占用事实未变），修的是第三段文案 ——
 *     宽限期拒收答案时改回「对手断线中，你这条消息未计入本题」，不再谎称
 *     「你当前不在对决中」。见 {@link #pausedRoomShouldNotBeAnswerConsumable()}（期望，已绿）
 *     与 {@link #nonPausedRejectionKeepsGenericNotice()}（分流对照，防整体替换）。</li>
 *   <li><b>离线通告队列的完整机制</b>：结构（每玩家一条）、覆盖语义、读后即清、
 *     无容量上限、清理点只有 {@code consume} 与 {@code shutdown}、
 *     且 {@code cleanup(room)} <b>刻意不清</b>它。</li>
 * </ol>
 *
 * <h2>为什么用反射</h2>
 * {@code isAnswering} / {@code enqueueOfflineDuelNotice} / {@code offlineNotices}
 * 都是 private，且没有更外的可观察面（{@code enqueue} 的公开观察面是
 * {@code consume}，这一条已经够用；{@code isAnswering} 的公开面是
 * {@code acceptDuel}，需要完整 Player + 调度器）。反射的代价是「改名会红」——
 * 那是好事，改名前该先想清楚语义有没有变（与
 * {@code DuelDisconnectRoomIdentityTest} 同一套取舍）。
 *
 * <h2>纯 JVM 前提</h2>
 * 只触碰 {@code Bukkit.getPlayer(...)} / {@code Bukkit.getScoreboardManager()}
 * 静态入口（{@link MockedStatic} 接管，默认 null = 「双方都离线」且「无可摘计分板」）、
 * {@code MessageUtil}（mock，并把解析过的 i18n 键记下来）、{@code plugin.getLogger()}（mock）。
 * 唯一走真实业务方法的是 {@link #realEndDuelWindowKeepsBusyButNotInDuel()} 的
 * {@code endDuel} —— 它的两个外部门（成就管理器、数据库）都被打桩，
 * {@code getDatabaseManager()} 返回 null 时 {@code recordDuelResultAsync} 在
 * 派发之前就早退，因此<b>调度器一次都不会被调用</b>。
 * 不启动服务器、不碰数据库、不碰文件系统。
 */
class DuelQueryAndNoticeQueueTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000c0001");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-0000000c0002");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-0000000c00ff");

    private static final String KEY_GM = "duel.cancelled-by-gm";
    private static final String KEY_LOST = "duel.lost-by-disconnect";

    private LetsLearnEnglish plugin;
    private MessageUtil messageUtil;
    private WordLearningManager wordLearning;
    private DuelManager manager;
    /** 所有经 {@code getPlayerMessage} 解析过的 i18n 键，按调用顺序。 */
    private final java.util.List<String> playerMessageKeys = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        playerMessageKeys.clear();

        plugin = mock(LetsLearnEnglish.class);
        wordLearning = mock(WordLearningManager.class);

        // 用一个默认 Answer 顶掉 MessageUtil，而不是 when(...) 打桩：
        // getPlayerMessage 是 varargs 方法，`any(String[].class)` 在 Mockito 5 的
        // 变长参数匹配语义下会随实际参数个数（0 个也合法）改变行为，容易踩
        // InvalidUseOfMatchers。默认 Answer 直接拿原始 arguments，零匹配器、零歧义，
        // 顺带把「到底用了哪个 i18n 键」这件事变成可观测数据。
        // toString/hashCode/equals 显式处理：Mockito 会把它们也送进 Answer，
        // 返回值类型不匹配时（hashCode 要 int 却拿到 null）会 NPE 在 unboxing 上。
        messageUtil = mock(MessageUtil.class, invocation -> {
            String name = invocation.getMethod().getName();
            if ("toString".equals(name)) return "MessageUtilStub";
            if ("hashCode".equals(name)) return System.identityHashCode(invocation.getMock());
            if ("equals".equals(name)) return invocation.getMock() == invocation.getArgument(0);
            if ("getPlayerMessage".equals(name)) {
                playerMessageKeys.add(invocation.<String>getArgument(1));
                return "rendered";
            }
            Class<?> rt = invocation.getMethod().getReturnType();
            if (rt == String.class) return "rendered";
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            return null;
        });

        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(plugin.getWordLearningManager()).thenReturn(wordLearning);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));

        manager = new DuelManager(plugin);
    }

    // ==================================================================
    // 反射夹具（全部集中在这里，业务用例不出现 getDeclaredField）
    // ==================================================================

    @SuppressWarnings("unchecked")
    private static void registerRoom(DuelManager mgr, DuelRoom room) throws Exception {
        Field f = DuelManager.class.getDeclaredField("activeDuels");
        f.setAccessible(true);
        Map<UUID, DuelRoom> active = (Map<UUID, DuelRoom>) f.get(mgr);
        active.put(room.getPlayer1(), room);
        active.put(room.getPlayer2(), room);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, DuelManager.OfflineDuelNotice> notices(DuelManager mgr)
            throws Exception {
        Field f = DuelManager.class.getDeclaredField("offlineNotices");
        f.setAccessible(true);
        return (Map<UUID, DuelManager.OfflineDuelNotice>) f.get(mgr);
    }

    private static void invokeEnqueue(DuelManager mgr, UUID playerId, String otherPlayerName,
                                      String messageKey) throws Exception {
        Method m = DuelManager.class.getDeclaredMethod("enqueueOfflineDuelNotice",
                UUID.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(mgr, playerId, otherPlayerName, messageKey);
    }

    private static boolean invokeIsAnswering(DuelManager mgr, UUID playerId) throws Exception {
        Method m = DuelManager.class.getDeclaredMethod("isAnswering", UUID.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(mgr, playerId);
    }

    /** DuelRoom 只用到 getEnglish()；其余字段给最小合法值（签名沿用 DuelRoomPauseTest）。 */
    private static Word word(String en) {
        return new Word(en, "中文", "n.", "/" + en + "/", "a " + en + " here",
                java.util.List.of(), java.util.List.of(), "beginner", 1);
    }

    private static DuelRoom newRoom() {
        return new DuelRoom(P1, "Alice", P2, "Bob");
    }

    // ==================================================================
    // 1. isInDuel：只看 active，不看 paused（这是缺陷，不是疏漏）
    // ==================================================================

    @Test
    @DisplayName("isInDuel：无房间 / 非成员 → false")
    void unknownPlayerIsNotInDuel() {
        assertFalse(manager.isInDuel(P1), "从没开过对局的人不在对决中");
        assertFalse(manager.isInDuel(OUTSIDER));
    }

    @Test
    @DisplayName("isInDuel：活跃房间对双方都返回 true，对第三人 false")
    void activeRoomIsInDuelForBothSides() throws Exception {
        registerRoom(manager, newRoom());
        assertTrue(manager.isInDuel(P1), "房间登记即算在局内（acceptDuel 的双键写入）");
        assertTrue(manager.isInDuel(P2));
        assertFalse(manager.isInDuel(OUTSIDER), "旁观者不得被当成对决成员");
    }

    /**
     * <b>R5-07-F4 的核心</b>：成员表 ≠ 进行中。
     *
     * <p>{@code endDuel} 的顺序是「先 {@code setActive(false)}，摘键留到
     * {@code finally cleanup}」。这个窗口里 {@code activeDuels} 仍有双键，
     * 但房间已经结束 —— 若 {@code isInDuel} 退化成 {@code containsKey}，
     * 玩家的正常聊天会被 {@code ChatAnswerListener:166} 吞成答案，再被
     * {@code submitAnswer} 回一句 {@code duel.not-in-duel}。
     *
     * <p><b>变异</b>：把 {@code isActiveDuel} 的返回改成
     * {@code activeDuels.containsKey(playerId)}，本用例变红。
     */
    @Test
    @DisplayName("isInDuel：拆解窗口（active=false 但键还在）必须返回 false")
    void teardownWindowIsNotInDuel() throws Exception {
        DuelRoom room = newRoom();
        registerRoom(manager, room);
        room.setActive(false); // endDuel 的第一步；cleanup 尚未跑

        assertFalse(manager.isInDuel(P1),
                "拆解窗口内玩家已经不在对决中 —— 此刻吞掉他的聊天等于静默吃消息");
        assertFalse(manager.isInDuel(P2));
    }

    /**
     * 上面那条是手工摆的状态。这条用<b>真实 {@code endDuel}</b> 走一遍拆解窗口：
     * 把探针挂在赢家 {@code sendMessage} 上 —— 那一刻 {@code endDuel} 已经执行了
     * {@code setActive(false)}，而 {@code cleanup} 还在 {@code finally} 里没跑。
     *
     * <p>为什么这条不可替代：手工 {@code setActive(false)} 只能证明「判据读了 active」，
     * 不能证明「{@code endDuel} 真的先置 active 再摘键」这个<b>顺序</b>。顺序一旦被改成
     * 「先 cleanup 再播报」，两者同时为 false，玩家会在播报窗口里既收不到 win 消息、
     * 又对第三方显示「空闲可挑战」—— 正是 F4 要防的那类事故。
     *
     * <p><b>变异</b>：把 {@code endDuel} 里的 {@code setActive(false)} 挪到
     * {@code finally cleanup} 之后，本用例红（探针会看到 isInDuel=true）。
     */
    @Test
    @DisplayName("真实 endDuel 的拆解窗口：isInDuel=false 而 isBusy=true（顺序不可颠倒）")
    void realEndDuelWindowKeepsBusyButNotInDuel() throws Exception {
        DuelRoom room = newRoom();
        for (int i = 0; i < DuelRoom.WIN_SCORE; i++) {
            room.setCurrentWord(word("w" + i));
            assertEquals(DuelRoom.AnswerResult.CORRECT, room.submitAnswer(P1, "w" + i));
        }
        registerRoom(manager, room);

        // endDuel 的两个外部依赖：成就与 DB。前者空实现（第一次解锁对 mock 是 no-op），
        // 后者返回 null → recordDuelResultAsync 自行早退，不碰调度器。
        when(plugin.getAchievementManager())
                .thenReturn(mock(com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager.class));
        when(plugin.getDatabaseManager()).thenReturn(null);

        // 探针：赢家收到 duel.win 的那一刻 = endDuel 的播报相位。
        java.util.List<Boolean> inDuelDuringAnnounce = new java.util.ArrayList<>();
        java.util.List<Boolean> busyDuringAnnounce = new java.util.ArrayList<>();
        Player winner = mock(Player.class);
        when(winner.getUniqueId()).thenReturn(P1);
        when(winner.isOnline()).thenReturn(true);
        org.mockito.Mockito.doAnswer(inv -> {
            inDuelDuringAnnounce.add(manager.isInDuel(P1));
            busyDuringAnnounce.add(manager.isBusy(P1));
            return null;
        }).when(winner).sendMessage(org.mockito.ArgumentMatchers.anyString());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(P1)).thenReturn(winner);
            manager.endDuel(room);
        }

        assertFalse(inDuelDuringAnnounce.isEmpty(),
                "探针没被触发 —— 赢家播报阶段必须在 cleanup 之前发生（否则本用例是假绿）");
        for (Boolean b : inDuelDuringAnnounce) {
            assertFalse(b, "播报相位里 isInDuel 必须已是 false —— 否则赢家的收尾聊天会被当答案吞掉");
        }
        for (Boolean b : busyDuringAnnounce) {
            assertTrue(b, "播报相位里 isBusy 必须仍是 true —— 否则新挑战会被接受，"
                    + "随后待执行的 cleanup 把新房间的键一起摘掉");
        }

        // 拆解完成后：两个都归 false。
        assertFalse(manager.isInDuel(P1));
        assertFalse(manager.isBusy(P1), "cleanup 之后必须彻底摘键，双方才能再开一局");
        assertFalse(manager.isBusy(P2));
    }

    /**
     * <b>刻意的语义契约</b>：{@code paused=true} 时 {@code isInDuel} <b>仍</b>
     * 返回 true，因为它的判据里没有 {@code paused} —— 这是 R24-A2 裁决后的
     * <b>有意为之</b>，不是遗留疏漏。
     *
     * <p>理由：{@code isInDuel} 回答的是「这名玩家是否已被一场对决<b>占用</b>」，
     * 而不是「这行聊天此刻能否被计为答案」。停盘不改变占用事实 —— 房间还在、
     * GUI 还开着、30 秒后自动复赛。它的 13 处调用方全都按「占用」理解
     * （不开新答题 / 不开 AI 对话 / 不进错题练习 / 主菜单重开对决界面而非发起挑战），
     * 把 {@code paused} 折进来会让这些守卫在宽限期内集体失效。
     *
     * <p>这条是<b>绿</b>的，且必须一直绿：它与
     * {@link #pausedRoomShouldNotBeAnswerConsumable()} 构成一对<b>方向互反</b>的夹子 ——
     * 谁改 {@code isInDuel} 去考虑 {@code paused}（即审计给的路线 (a)），本用例立刻红，
     * 逼他显式面对那 13 处调用方的语义，而不是悄悄拓宽一个被四处依赖的判据。
     * A2 的缺陷实际修在文案一侧（第三段），不需要翻转本条。
     *
     * <p><b>变异</b>：给 {@code isActiveDuel} 补 {@code && !room.isPaused()}-类判定后，
     * 本用例红；同时 {@link #pausedRoomShouldNotBeAnswerConsumable()} 里
     * {@code assertTrue(manager.isInDuel(P1))} 那条也会红 —— 双向夹，谁都绕不过去。
     */
    @Test
    @DisplayName("现状：宽限期 paused=true 时 isInDuel 仍为 true（A2 的形状）")
    void pausedRoomStillReportsInDuel() throws Exception {
        DuelRoom room = newRoom();
        registerRoom(manager, room);
        room.setPaused(true); // 等价于「对手已断线，进入 30 秒宽限」

        assertTrue(manager.isInDuel(P1),
                "现状如此：isInDuel 砍在 room.isActive() 上，paused 不在判据里");
        assertTrue(room.isActive(), "暂停 ≠ 结束，active 位不动（见 DuelRoomPauseTest）");
    }

    /**
     * <b>缺陷 R23-A2（MEDIUM）—— 裁决走路线 (b)：仍然拒收，但文案改为准确的说明。</b>
     *
     * <h2>缺陷链（三段，全部已由本类与 DuelRoomPauseTest 实测）</h2>
     * <pre>
     * t=0  对手断线 → DuelManager.handleDisconnect → disconnectTimers.put + room.setPaused(true)
     * t=1  在场方敲一行普通聊天（"等我一下" / "gg" / 任何话）
     *      ChatAnswerListener:166  if (duelManager.isInDuel(id))  → true  ← 第一段（本类钉住）
     *      ├─ event.setCancelled(true)           ← 聊天被吞，全服收不到
     *      ├─ event.getRecipients().clear()      ← 连别的插件的收件人也清掉
     *      └─ runTask → DuelManager.submitAnswer
     *      DuelRoom:80  if (!active || currentWord == null || paused) → NOT_IN_DUEL  ← 第二段
     *      DuelManager:424  case NOT_IN_DUEL → player.sendMessage(...)  ← 第三段（已修）
     * </pre>
     * 修前：第三段恒发 {@code duel.not-in-duel}（「你当前不在对决中」）—— 玩家在<b>自己的
     * 对决里</b>被告知不在对决中，持续整个 30 秒宽限期，每敲一行都是这个结果。
     * 修后：按 {@code room.isPaused()} 分流到 {@code duel.paused-opponent-disconnected}
     * （「对手断线中，你这条消息未计入本题」）—— 不再谎报状态，玩家知道消息去哪了。
     *
     * <h2>为什么是路线 (b) 而不是 (a)（放行普通聊天）</h2>
     * 见 {@link #pausedRoomStillReportsInDuel()}：{@code isInDuel} <b>刻意保持</b>不看
     * {@code paused}。那个判据被 13 处调用方用来表达「这名玩家已被一场对决占用」，
     * 停盘不改变占用事实（房间还在、GUI 还开着、30 秒后自动复赛），把它一并解除会让
     * 「不开新答题 / 不开 AI 对话 / 不进错题练习」等守卫在宽限期内集体失效。
     * 因此本用例<b>只</b>钉第三段（文案），不要求 {@code isInDuel} 翻转 ——
     * 翻转会同时打红 {@link #pausedRoomStillReportsInDuel()}，那是刻意设的双向夹。
     *
     * <h2>为什么断言「解析了哪个键」而不是源码 {@code contains("paused")}</h2>
     * 审计给 A2 的教训正是：{@code DuelManagerAuditTest} 的旧判据
     * {@code body.contains("paused")} 能被<b>一行注释</b>满足。这里驱动真实
     * {@code submitAnswer}，观察 {@link MessageUtil#getPlayerMessage} 实际收到的键名，
     * 且断言是<b>正向</b>的（必须是新键），不是「只要不是旧键就算过」——
     * 后者在「一条消息都不发」或「换成任何别的键」时都会假绿。
     */
    @Test
    @DisplayName("缺陷 A2：宽限期拒收答案时，文案须说明「消息未计入」而非谎称「不在对决中」")
    void pausedRoomShouldNotBeAnswerConsumable() throws Exception {
        DuelRoom room = newRoom();
        room.setCurrentWord(word("apple"));
        registerRoom(manager, room);
        room.setPaused(true);

        // 路线 (a) 未采纳：门仍然声称「这行聊天是答案」（见 pausedRoomStillReportsInDuel
        // 的完整论证）。此断言是同一条双向夹的另一侧 —— 谁将来偷偷翻转 isInDuel，
        // 两条用例会同时红，逼他显式面对 13 处调用方的语义。
        assertTrue(manager.isInDuel(P1),
                "路线 (b) 下 isInDuel 必须保持 true（占用事实未变，见本方法 javadoc）");

        Player p1 = mock(Player.class);
        when(p1.getUniqueId()).thenReturn(P1);
        when(p1.isOnline()).thenReturn(true);
        playerMessageKeys.clear();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            manager.submitAnswer(p1, "hello");
        }

        // 必须先有捕获，否则下面两条断言都退化成「空集合里没有那个键」的恒真。
        assertFalse(playerMessageKeys.isEmpty(),
                "probe 未捕获任何 i18n 键 —— 本用例的文案判定将失去意义（假绿）");

        // 正向断言：必须是新的、说明「消息未计入」的那条键。
        assertTrue(playerMessageKeys.contains("duel.paused-opponent-disconnected"),
                "宽限期停盘中拒收答案时，必须回 duel.paused-opponent-disconnected"
                        + "（说明消息去处），实际解析的键 = " + playerMessageKeys);
        // 反向断言：不得再谎称「你当前不在对决中」。
        assertFalse(playerMessageKeys.contains("duel.not-in-duel"),
                "宽限期内不得回 duel.not-in-duel —— 玩家就在对局里，这条文案与事实相反"
                        + "且会重复整整 30 秒。实际解析的键 = " + playerMessageKeys);

        // 房间确实在拒收答案（这一点现状已成立，见 DuelRoomPauseTest）——
        // 正是「拒收 + 吞聊天 + 文案反了」三件同时发生才构成缺陷；本轮只修第三件，
        // 拒收本身是 R4-07-logic-F01 的既定契约（防在场方连答打满 WIN_SCORE）。
        assertEquals(DuelRoom.AnswerResult.NOT_IN_DUEL,
                room.submitAnswer(P1, "hello"),
                "同一时刻房间确实在拒收答案（paused 短路），这不是缺陷的那一半");
    }

    /**
     * 对照用例：<b>非</b>停盘时的拒收（题未就位等窗口态）必须<b>仍然</b>复用
     * {@code duel.not-in-duel}。
     *
     * <p>没有这一条，把 {@code DuelManager} 的 NOT_IN_DUEL 分支整体换成新键也能全绿 ——
     * 那会把「真的不在对局里」这个正常诊断一并抹掉。两条用例合起来才钉住"分流"本身。
     */
    @Test
    @DisplayName("对照：非停盘的拒收仍走 duel.not-in-duel（分流不是整体替换）")
    void nonPausedRejectionKeepsGenericNotice() throws Exception {
        DuelRoom room = newRoom(); // 刻意不 setCurrentWord：currentWord == null 也会拒收
        registerRoom(manager, room);
        assertFalse(room.isPaused(), "前置：本用例房间不在停盘态");

        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        try {
            Player p1 = mock(Player.class);
            when(p1.getUniqueId()).thenReturn(P1);
            when(p1.isOnline()).thenReturn(true);
            playerMessageKeys.clear();
            manager.submitAnswer(p1, "hello");
        } finally {
            bukkit.close();
        }

        assertFalse(playerMessageKeys.isEmpty(),
                "probe 未捕获任何 i18n 键 —— 本用例将失去意义（假绿）");
        assertTrue(playerMessageKeys.contains("duel.not-in-duel"),
                "非停盘的房间层拒收必须仍回 duel.not-in-duel（否则正常诊断被抹掉），"
                        + "实际解析的键 = " + playerMessageKeys);
        assertFalse(playerMessageKeys.contains("duel.paused-opponent-disconnected"),
                "非停盘时不得回「对手断线中」—— 那是谎话。实际解析的键 = " + playerMessageKeys);
    }

    // ==================================================================
    // 2. isBusy：containsKey 语义，是 isInDuel 的严格超集
    // ==================================================================

    @Test
    @DisplayName("isBusy：无房间 → false；活跃 / 暂停 / 拆解窗口 → 全部 true")
    void busyCoversEveryMappedRoomState() throws Exception {
        assertFalse(manager.isBusy(P1), "没房间 = 不忙");

        DuelRoom room = newRoom();
        registerRoom(manager, room);
        assertTrue(manager.isBusy(P1), "活跃房间 = 忙");
        assertTrue(manager.isBusy(P2));

        room.setPaused(true);
        assertTrue(manager.isBusy(P1), "暂停期必须仍然忙 —— 否则第三方可插一脚挑战一个"
                + "正在宽限期里的玩家，接受后本房间的宽限任务还在跑");

        room.setPaused(false);
        room.setActive(false);
        assertTrue(manager.isBusy(P1),
                "拆解窗口（active=false、键未摘）必须仍然是忙 —— 这是 R5-07-F4 的必需条件："
                        + "此刻返回 false 会让新挑战被接受，随后待执行的 cleanup 把新房间的键一起摘掉，"
                        + "新对局当场失去登记（双方永久「不在对决中」却都看着 GUI）");
    }

    @Test
    @DisplayName("isBusy：非成员 → false（成员表只认双键）")
    void outsiderIsNotBusy() throws Exception {
        registerRoom(manager, newRoom());
        assertFalse(manager.isBusy(OUTSIDER), "第三人不许被房间里的人连坐成 busy");
    }

    /**
     * 超集关系：{@code isInDuel ⇒ isBusy}，但反之不成立。
     *
     * <p>这条把「两个方法不是同一个东西」写成可执行断言。反方向（{@code isBusy ⇒ isInDuel}）
     * <b>必须</b>为假，否则拆解窗口那一格就没了 —— 下面明确断言存在这样的反例。
     *
     * <p><b>刻意不碰 paused 态</b>：{@code paused} 下的取值由
     * {@link #pausedRoomStillReportsInDuel()}（占用语义，刻意不翻转）与
     * {@link #pausedRoomShouldNotBeAnswerConsumable()}（文案分流，已修）专门负责。
     * 本用例只在<b>不受 A2 影响</b>的两个状态上钉超集关系，
     * 这样 A2 的变异信号（将来谁动 {@code isInDuel} 或文案分流）只会落在那一对用例上，
     * 不会被本用例污染成「到处都红，看不出根因」。
     */
    @Test
    @DisplayName("isInDuel 是 isBusy 的真子集：拆解窗口是 isBusy=true 且 isInDuel=false 的反例")
    void inDuelIsStrictSubsetOfBusy() throws Exception {
        DuelRoom active = newRoom();
        registerRoom(manager, active);
        assertTrue(manager.isInDuel(P1));
        assertTrue(manager.isBusy(P1), "活跃态：两个方法都为真");

        active.setActive(false);
        assertFalse(manager.isInDuel(P1), "拆解窗口：isInDuel 必须转 false");
        assertTrue(manager.isBusy(P1), "拆解窗口：isBusy 必须保持 true —— 两个方法在此分道");
    }

    // ==================================================================
    // 3. isAnswering：跨模块只读，读的是 WordLearningManager 的两个既有态
    // ==================================================================

    @Test
    @DisplayName("isAnswering：WordLearningManager 缺失 → false（不 NPE，不假设初始化顺序）")
    void answeringFalseWhenManagerMissing() throws Exception {
        when(plugin.getWordLearningManager()).thenReturn(null);
        assertFalse(invokeIsAnswering(manager, P1),
                "热重载窗口里 wlManager 可能已置空 —— 必须安全返回 false 而不是 NPE");
    }

    @Test
    @DisplayName("isAnswering：quiz 中或错题练习中 → true；两者皆无 → false")
    void answeringReadsBothWordLearningStates() throws Exception {
        assertFalse(invokeIsAnswering(manager, P1), "两个态都为 false 时必须不忙");

        when(wordLearning.hasActiveQuiz(P1)).thenReturn(true);
        assertTrue(invokeIsAnswering(manager, P1), "quiz 会话中 = 忙（M4 门禁）");

        when(wordLearning.hasActiveQuiz(P1)).thenReturn(false);
        when(wordLearning.isPracticingWord(P1)).thenReturn(true);
        assertTrue(invokeIsAnswering(manager, P1), "错题练习中同样是忙态（M4 不只管 quiz）");
    }

    @Test
    @DisplayName("isAnswering：只看自己的 UUID，不串味到对手/第三人")
    void answeringIsPerPlayer() throws Exception {
        when(wordLearning.hasActiveQuiz(P1)).thenReturn(true);
        assertTrue(invokeIsAnswering(manager, P1));
        assertFalse(invokeIsAnswering(manager, P2), "P1 在答题不代表 P2 在答题");
    }

    // ==================================================================
    // 4. 离线通告队列：结构、覆盖、读后即清、无上限、清理点
    // ==================================================================

    @Test
    @DisplayName("consume 是读后即清：第一次拿到，第二次 null（幂等）")
    void consumeIsReadOnce() throws Exception {
        invokeEnqueue(manager, P1, "Bob", KEY_GM);

        DuelManager.OfflineDuelNotice first = manager.consumeOfflineDuelNotice(P1);
        assertNotNull(first, "排了队就必须取得到");
        assertEquals(KEY_GM, first.messageKey());
        assertEquals("Bob", first.otherPlayerName(), "{player} 占位用的是对手名");

        assertNull(manager.consumeOfflineDuelNotice(P1),
                "读后即清 —— 残留会让玩家每次进服都收到同一条通告");
        assertEquals(0, notices(manager).size(), "队列里不得留痕");

        // 变异：把 `remove` 改成 `get`，上面这条 assertNull 变红。
    }

    @Test
    @DisplayName("consume 未知玩家 / null 入参 → null，且不抛 NPE")
    void consumeUnknownAndNullAreSafe() {
        assertNull(manager.consumeOfflineDuelNotice(OUTSIDER));
        assertNull(manager.consumeOfflineDuelNotice(null),
                "ConcurrentHashMap.remove(null) 会抛 NPE —— 公开方法必须有 null 守卫"
                        + "（join 路径每次都调它）");
    }

    @Test
    @DisplayName("同一玩家重复入队 = 覆盖（每玩家至多一条，最近一次拆解获胜）")
    void repeatedEnqueueOverwrites() throws Exception {
        invokeEnqueue(manager, P1, "Bob", KEY_GM);
        invokeEnqueue(manager, P1, "Carol", KEY_LOST);

        assertEquals(1, notices(manager).size(),
                "同一玩家只允许一条 —— 追加会让重连时播报一串互相矛盾的旧结局");

        DuelManager.OfflineDuelNotice n = manager.consumeOfflineDuelNotice(P1);
        assertNotNull(n);
        assertEquals(KEY_LOST, n.messageKey(), "覆盖语义：最近一次拆解的原因获胜");
        assertEquals("Carol", n.otherPlayerName(), "名字必须与键同源更新，不能新旧混搭");

        // 变异：把 `offlineNotices.put` 改成「containsKey 才 put」（或改成 add 到 List），
        // 本用例的 size==1 与 KEY_LOST 两条断言都会红。
    }

    @Test
    @DisplayName("别的玩家互不干扰：不同 UUID 各占一条")
    void queueIsKeyedPerPlayer() throws Exception {
        invokeEnqueue(manager, P1, "Bob", KEY_GM);
        invokeEnqueue(manager, P2, "Alice", KEY_LOST);

        assertEquals(2, notices(manager).size());
        assertEquals(KEY_GM, manager.consumeOfflineDuelNotice(P1).messageKey());
        assertNotNull(manager.consumeOfflineDuelNotice(P2),
                "取走 P1 的条目不得连带删掉 P2 的 —— 一次拆解会给双方各排一条");
        assertEquals(0, notices(manager).size());
    }

    @Test
    @DisplayName("null 收件人入队 → 静默丢弃（无处投递，绝不留一条取不出的死条目）")
    void nullRecipientIsDropped() throws Exception {
        invokeEnqueue(manager, null, "Bob", KEY_GM);
        assertEquals(0, notices(manager).size(),
                "null UUID 无法投递 —— 入队等于制造永远消费不到的内存垃圾");
    }

    @Test
    @DisplayName("null 对手名归一为 '?'（MessageUtil 的占位符替换对 null 直接 NPE）")
    void nullOtherNameBecomesPlaceholderSafe() throws Exception {
        invokeEnqueue(manager, P1, null, KEY_GM);
        DuelManager.OfflineDuelNotice n = manager.consumeOfflineDuelNotice(P1);
        assertNotNull(n);
        assertEquals("?", n.otherPlayerName(),
                "占位符替换不接受 null（见 enqueueOfflineDuelNotice 的 javadoc）—— "
                        + "必须归一成 '?' 而不是把 NPE 留到 join 路径的 sendMessage 里");
    }

    /**
     * <b>队列没有容量上限</b>，也没有淘汰策略 —— 这是刻意的：它是
     * 「一对一的通知」，任何一行被淘汰都意味着一个玩家永远不知道自己那局怎么结束的。
     *
     * <p>它的「有界」来自 <b>覆盖语义</b>（每 UUID 一条），不是来自容量。
     * 这条断言的作用是把这条设计钉住：将来若有人加上 {@code if (size > N) evict()}，
     * 本用例变红，逼他在「丢通知」与「改设计」之间做显式选择。
     */
    @Test
    @DisplayName("队列无容量上限：1000 个不同玩家 = 1000 条，一条都不许被淘汰")
    void queueHasNoEvictionAmongDistinctPlayers() throws Exception {
        for (int i = 0; i < 1000; i++) {
            invokeEnqueue(manager, new UUID(0L, 1000L + i), "Opp" + i, KEY_GM);
        }
        assertEquals(1000, notices(manager).size(),
                "不同玩家各占一条，无容量上限、无淘汰 —— 淘汰 = 静默丢一个玩家的结局说明");
    }

    /**
     * 真实路径：GM 解散一场<b>双方都离线</b>的对局 → 双方各排一条
     * {@code duel.cancelled-by-gm}，{@code {player}} 是<b>对方</b>的名字。
     *
     * <p>这条同时钉住「{@code cleanup(room)} <b>不清</b>通告队列」：{@code cancelDuelByAdmin}
     * 末尾就调 {@code cleanup}，若某天有人「顺手清理干净」，本条与重连补发链路一起断。
     */
    @Test
    @DisplayName("GM 解散（双方离线）：两侧各一条 cancelled-by-gm，且 cleanup 不得清掉队列")
    void gmDismissalQueuesNoticeForEachOfflineSide() throws Exception {
        DuelRoom room = newRoom();
        registerRoom(manager, room);
        CommandSender gm = mock(CommandSender.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            // 默认返回 null = 双方都离线；targetName 回退到按名字遍历 activeDuels。
            manager.cancelDuelByAdmin(gm, "Alice");
        }

        assertFalse(manager.isBusy(P1), "解散后双方必须不再 busy（否则永远无法再对战）");
        assertFalse(manager.isBusy(P2));
        assertEquals(2, notices(manager).size(),
                "双方都离线 → 必须各排一条，实际=" + notices(manager).size());

        DuelManager.OfflineDuelNotice n1 = manager.consumeOfflineDuelNotice(P1);
        DuelManager.OfflineDuelNotice n2 = manager.consumeOfflineDuelNotice(P2);
        assertNotNull(n1, "P1 离线期间被 GM 解散，重连必须能知道这件事");
        assertNotNull(n2);
        assertEquals(KEY_GM, n1.messageKey(), "GM 解散的文案键是 cancelled-by-gm");
        assertEquals(KEY_GM, n2.messageKey());
        assertEquals("Bob", n1.otherPlayerName(), "P1 看到的 {player} 必须是 P2 的名字");
        assertEquals("Alice", n2.otherPlayerName(), "P2 看到的 {player} 必须是 P1 的名字");
        assertFalse(n1.otherPlayerName().equals(n2.otherPlayerName()),
                "两侧名字必须互换 —— 取错源会让自己看到自己的名字（otherDuelistName 回归）");
    }

    /**
     * <b>清理点只有两处</b>：{@code consumeOfflineDuelNotice} 与 {@code shutdown}。
     *
     * <p>这意味着「玩家永不重连 → 条目活到关服」是<b>已知且刻意的残留</b>（javadoc 原文：
     * "the recipient may never come back — that is a bounded, intended residue"）。
     * 它不是泄漏：每条只持一个 {@code String}×2，且同名玩家覆盖。本条钉住
     * {@code shutdown} 这个收敛点真的在，并且真的把队列清空。
     */
    @Test
    @DisplayName("shutdown 是队列的第二个清理点：关服必须清空，重启不得带着旧通告")
    void shutdownClearsPendingNotices() throws Exception {
        invokeEnqueue(manager, P1, "Bob", KEY_GM);
        invokeEnqueue(manager, P2, "Alice", KEY_LOST);
        assertEquals(2, notices(manager).size());

        manager.shutdown();

        assertEquals(0, notices(manager).size(),
                "关服必须清空 —— 否则这条内存数据会在 /lea reload 的拆建之间被当成「真实事件」重播");
        assertNull(manager.consumeOfflineDuelNotice(P1));
        assertFalse(manager.isBusy(P1));

        // 变异：删掉 shutdown() 里的 `offlineNotices.clear();`，本用例变红。
    }

    @Test
    @DisplayName("shutdown 幂等：连续两次不抛（拆建/reload 路径会重复调）")
    void shutdownIsIdempotent() {
        manager.shutdown();
        manager.shutdown();
        assertFalse(manager.isBusy(P1));
    }

    /**
     * 结构 lint：{@code cleanup} 不得顺手清通告队列。
     *
     * <p>行为侧已由 {@link #gmDismissalQueuesNoticeForEachOfflineSide()} 钉住
     * （它走真实 {@code cancelDuelByAdmin → cleanup}，之后通告仍在）。这条是<b>第二道锁</b>：
     * 万一将来 {@code cleanup} 的调用点变了、行为用例不再覆盖那条顺序，本条仍会红。
     * 只钉队列字段名，不钉整个方法体 —— 语义留给行为用例。
     */
    @Test
    @DisplayName("lint：cleanup(room) 不得清 offlineNotices（那是唯一投递链，清了就永久失联）")
    void cleanupMustNotSweepNoticeQueue() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/core/duel/DuelManager.java");
        assertTrue(java.nio.file.Files.exists(src),
                "必须读真实源文件（工作目录应为项目根）: " + src.toAbsolutePath());
        String text = java.nio.file.Files.readString(src);

        int start = text.indexOf("private void cleanup(DuelRoom room)");
        assertTrue(start > 0, "cleanup(DuelRoom) 必须存在（关服/拆解的唯一汇聚点）");
        int end = text.indexOf("\n    }", text.indexOf("duelBars.remove(p1)", start));
        assertTrue(end > start, "cleanup 方法体边界解析失败（源码结构变了，需更新本 lint）");
        String body = text.substring(start, end);

        assertFalse(body.contains("offlineNotices"),
                "cleanup 不得触碰 offlineNotices —— 它是离线玩家唯一的重连补发通道，"
                        + "而 cleanup 恰恰总在「双方有一方离线」的拆解路径上执行");
    }

    /**
     * 结构 lint：三个查询方法是<b>三种不同判据</b>，谁都不许被改成另一个的别名。
     *
     * <p>把 {@code isInDuel} 改成 {@code isBusy} 会让拆解窗口吞聊天（R5-07-F4 回归）；
     * 把 {@code isBusy} 改成 {@code isInDuel} 会让新挑战被接受后被旧 cleanup 摘键
     * （F4 的另一半）。两条都是「一行改动、行为全错」的形态，值得一条零成本 lint。
     */
    @Test
    @DisplayName("lint：isInDuel 必须走 isActiveDuel，isBusy 必须走 containsKey（不得互为别名）")
    void queryMethodsKeepDistinctPredicates() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/core/duel/DuelManager.java");
        String text = java.nio.file.Files.readString(src);

        int inDuel = text.indexOf("public boolean isInDuel(UUID playerId)");
        assertTrue(inDuel > 0, "isInDuel 必须存在");
        String inDuelBody = text.substring(inDuel, text.indexOf("\n    }", inDuel));
        assertTrue(inDuelBody.contains("isActiveDuel"),
                "isInDuel 必须委托 isActiveDuel（room != null && room.isActive()）");
        assertFalse(inDuelBody.contains("containsKey"),
                "isInDuel 不得退化成 containsKey —— 拆解窗口会把正常聊天吞成答案（R5-07-F4）");

        int busy = text.indexOf("public boolean isBusy(UUID playerId)");
        assertTrue(busy > 0, "isBusy 必须存在");
        String busyBody = text.substring(busy, text.indexOf("\n    }", busy));
        assertTrue(busyBody.contains("containsKey"),
                "isBusy 必须是 containsKey 语义（拆解窗口也算忙），否则旧 cleanup 会摘掉新房间的键");
    }
}
