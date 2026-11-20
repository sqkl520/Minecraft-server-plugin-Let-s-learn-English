package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R24 探索性探针：<b>不改一行生产代码</b>能否真实驱动
 * {@code DuelManager:917-1145} 的宽限状态机。
 *
 * <h2>它要证明的事</h2>
 * 审计提议抽出 {@code DuelLifecycle} 的主要论据是「这 229 行不可测」。
 * 本探针的结论是<b>可以测</b> —— 只要把两处静态入口接管：
 * <ul>
 *   <li>{@code Bukkit.getPlayer(UUID)} → 控制「此刻谁在线」（宽限逻辑唯一的玩家事实源）；</li>
 *   <li>{@code Bukkit.getScheduler().runTaskTimer(...)} → 捕获 Runnable，<b>手动 tick</b>。</li>
 * </ul>
 * 手动 tick 比真实调度器更强：它可以在任意一格停下、断言，再继续。
 *
 * <h2>为什么必须给 {@code runTaskTimer} 返回非 null</h2>
 * {@code graceTasks.put(playerId, selfRef[0])} 是 {@code ConcurrentHashMap} ——
 * 返回 null 会在 put 处 <b>NPE</b>，而不是「无害空任务」。所以 Answer 里必须回一个
 * {@link BukkitTask} mock，并顺便把 Runnable 收进 {@code runnables}。
 *
 * <h2>已覆盖的决策表行</h2>
 * #1 A 先断 B 后断、#3/#10 对手双侧保护、#5 A 断 B 直接 quit、
 * #9 到点前房间已被第三方拆。<b>其余行（#2/#4/#6/#7/#8/#11/#12）留给编排者补齐。</b>
 *
 * <h2>状态：<b>未编译、未运行</b></h2>
 * 本探针由只读角色在<b>无 shell</b> 的约束下写出（本轮严禁 {@code mvn}）。
 * 语义已逐行对照生产代码推导，但<b>编译通过与断言通过都必须由编排者实测确认</b>。
 */
class DuelLifecycleProbeTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000002400a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000002400b2");

    /** {@code GRACE_PERIOD_SECONDS}；到点需要第 30 次 tick（每次先自减再判）。 */
    private static final int GRACE = DuelManager.GRACE_PERIOD_SECONDS;

    private LetsLearnEnglish plugin;
    private DuelManager manager;
    private DuelRoom room;
    private MockedStatic<Bukkit> bukkit;

    /** 每次 runTaskTimer 捕获到的 Runnable，按注册顺序。 */
    private final List<Runnable> runnables = new ArrayList<>();
    /** 被判负方的直发消息（playerId → i18n key 列表）。 */
    private final List<String> sentKeys = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        MessageUtil messageUtil = mock(MessageUtil.class, invocation -> {
            String name = invocation.getMethod().getName();
            if ("toString".equals(name)) return "MessageUtilStub";
            if ("hashCode".equals(name)) return System.identityHashCode(invocation.getMock());
            if ("equals".equals(name)) return invocation.getMock() == invocation.getArgument(0);
            if ("getPlayerMessage".equals(name)) {
                sentKeys.add(invocation.getArgument(1));
                return "rendered";
            }
            Class<?> rt = invocation.getMethod().getReturnType();
            if (rt == String.class) return "rendered";
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            return null;
        });
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));

        // 调度器：捕获 Runnable，回非 null 的 BukkitTask（否则 graceTasks.put 处 NPE）。
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    runnables.add(inv.getArgument(1));
                    return mock(BukkitTask.class);
                });
        // 本探针只走宽限计时器；其余调度入口不桩化（默认 null = 不派发）。

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        // 默认双方都离线：getPlayer 未桩化即 null。
        bukkit.when(Bukkit::getScoreboardManager).thenReturn(null);

        manager = new DuelManager(plugin);
        room = new DuelRoom(A, "Alice", B, "Bob");
        activeDuelsOf(manager).put(A, room);
        activeDuelsOf(manager).put(B, room);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
    }

    // ------------------------------------------------------------------ 夹具

    @SuppressWarnings("unchecked")
    private static Map<UUID, DuelRoom> activeDuelsOf(DuelManager mgr) throws Exception {
        Field f = DuelManager.class.getDeclaredField("activeDuels");
        f.setAccessible(true);
        return (Map<UUID, DuelRoom>) f.get(mgr);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, DuelManager.OfflineDuelNotice> noticesOf() throws Exception {
        Field f = DuelManager.class.getDeclaredField("offlineNotices");
        f.setAccessible(true);
        return (Map<UUID, DuelManager.OfflineDuelNotice>) f.get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Object> timersOf() throws Exception {
        Field f = DuelManager.class.getDeclaredField("disconnectTimers");
        f.setAccessible(true);
        return (Map<UUID, Object>) f.get(manager);
    }

    /** 与生产 {@code DisconnectInfo} 同形（只持 room），供"模拟 cleanup 未摘条目"用。 */
    private Object newDisconnectInfo(DuelRoom r) throws Exception {
        Class<?> infoClass = Class.forName(
                "com.letslearnenglish.minecraftplugin.core.duel.DuelManager$DisconnectInfo");
        Constructor<?> ctor = infoClass.getDeclaredConstructor(DuelRoom.class);
        ctor.setAccessible(true);
        return ctor.newInstance(r);
    }

    private Player player(UUID id, String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    /** 驱动第 idx 个捕获到的宽限 Runnable 跑 {@code n} 次 tick。 */
    private void tick(int idx, int n) {
        Runnable r = runnables.get(idx);
        for (int i = 0; i < n; i++) {
            r.run();
        }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("#1 A 先断 B 后断：先到点者判负，受益方收中性说明，房间被拆且 B 不得再判负")
    void doubleDisconnectFirstToExpireLoses() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));
        assertEquals(2, runnables.size(), "双方各应有一条独立宽限计时（每玩家一份）");

        assertTrue(room.isActive(), "刚断线时房间必须仍 active（宽限期内不拆房）");

        // A 自己的 30 秒走完。
        tick(0, GRACE);

        // A 是判负方：离线 → 排队 lost-by-disconnect，占位名是 B。
        DuelManager.OfflineDuelNotice aNotice = noticesOf().get(A);
        assertNotNull(aNotice, "判负方离线时必须排队一条通告");
        assertEquals("duel.lost-by-disconnect", aNotice.messageKey());
        assertEquals("Bob", aNotice.otherPlayerName(), "{player} 占位必须是胜者名字");

        // B 是受益方：语义相反，不得复用 lost-by-disconnect。
        DuelManager.OfflineDuelNotice bNotice = noticesOf().get(B);
        assertNotNull(bNotice, "受益方也必须收到交代（R14 的「蒸发半场」）");
        assertEquals("duel.duel-already-over", bNotice.messageKey());

        // 房间已拆，双方不再 busy。
        assertFalse(room.isActive(), "判负后房间必须置 inactive");
        assertNull(activeDuelsOf(manager).get(A));
        assertNull(activeDuelsOf(manager).get(B));
        assertNull(timersOf().get(A), "判负方条目必须摘掉");
        assertNull(timersOf().get(B), "cleanup 会替对手摘掉条目 —— 这是 A3 依赖的副作用");
    }

    @Test
    @DisplayName("#10 变异守卫：即便对手条目被重新塞回（模拟 cleanup 没摘），也不得改判、不得覆盖通告")
    void stalePeerEntryMustNotForfeitTheWinner() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));
        tick(0, GRACE); // A 判负，房间拆除

        String bKeyBefore = noticesOf().get(B).messageKey();
        assertEquals("duel.duel-already-over", bKeyBefore);

        // 模拟「cleanup 漏了 cancel 对手任务」：条目与任务都还原，B 的 tick 继续跑。
        timersOf().put(B, newDisconnectInfo(room));
        tick(1, GRACE);

        // 第二层保护：activeDuels.get(A) 已 null → room-gone 分支。
        // 且 no-clobber 生效：B 的通告不得被改写成 lost-by-disconnect（那会说「你被判负」，
        // 与事实颠倒 —— 真正的负方是 A）。
        assertEquals("duel.duel-already-over", noticesOf().get(B).messageKey(),
                "受益方的中性说明不得被覆盖成「你被判负」");
        assertNull(timersOf().get(B), "room-gone 分支必须自行摘条目，不得残留");
    }

    @Test
    @DisplayName("#5 A 断、B 直接 quit：不判负、不写战绩，A 收 opponent-quit-while-disconnected")
    void quitByPeerWhileDisconnected() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));

        manager.quitDuel(player(B, "Bob"));

        DuelManager.OfflineDuelNotice aNotice = noticesOf().get(A);
        assertNotNull(aNotice, "对手 quit 时离线方必须收到交代（R23 A1）");
        assertEquals("duel.opponent-quit-while-disconnected", aNotice.messageKey());
        assertEquals("Bob", aNotice.otherPlayerName());
        assertNull(noticesOf().get(B), "在线 quit 方不发排队通告，避免下次进服重复播报");

        // A 的宽限任务被 cleanup 取消 —— 再 tick 也不得判负（房间已 inactive）。
        tick(0, GRACE);
        assertEquals("duel.opponent-quit-while-disconnected", noticesOf().get(A).messageKey(),
                "quit 之后不得再被判负覆盖");
        assertFalse(room.isActive());
    }

    @Test
    @DisplayName("#9 到点前房间被第三方拆：掉线方收中性说明，绝不说「你被判负」")
    void teardownBeforeExpiryYieldsNeutralNotice() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        // 第三方拆解：直接置 inactive（GM reload 路径的最小形状），但不摘 A 的条目。
        room.setActive(false);

        tick(0, GRACE);

        DuelManager.OfflineDuelNotice notice = noticesOf().get(A);
        assertNotNull(notice, "房间没了也必须给掉线方一句交代（不得静默 return）");
        assertEquals("duel.duel-already-over", notice.messageKey(),
                "无从判断谁负 —— 必须是中性文案");
    }

    @Test
    @DisplayName("#3 同时断线：只允许一方判负（谁的计时先注册谁先到点），另一方是受益方")
    void simultaneousDisconnectForfeitsExactlyOne() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));
        assertEquals(2, runnables.size());

        tick(0, GRACE); // 先注册的先到点

        int losers = 0;
        int winners = 0;
        for (DuelManager.OfflineDuelNotice n : noticesOf().values()) {
            if ("duel.lost-by-disconnect".equals(n.messageKey())) losers++;
            if ("duel.duel-already-over".equals(n.messageKey())) winners++;
        }
        assertEquals(1, losers, "只能有一方被判负 —— 两条 lost-by-disconnect 会互相矛盾");
        assertEquals(1, winners, "另一方必须收到中性说明");
    }

    /**
     * 探针自检：若 {@code runTaskTimer} 返回 null，{@code graceTasks.put(id, null)}
     * 会在 {@code ConcurrentHashMap} 上 NPE —— 即「没有宽限任务」这条路径<b>不是</b>
     * 静默降级。这条用例记录该前提，防止将来夹具被"简化成返回 null"。
     */
    @Test
    @DisplayName("夹具前提：handleDisconnect 必须真的登记一条宽限任务（非 null BukkitTask）")
    void graceTaskIsRegistered() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        assertEquals(1, runnables.size(), "必须向调度器登记恰好一条计时器");

        Field f = DuelManager.class.getDeclaredField("graceTasks");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, BukkitTask> graceTasks = (Map<UUID, BukkitTask>) f.get(manager);
        assertNotNull(graceTasks.get(A), "graceTasks 必须持有一个非 null 任务实例");
    }
}
