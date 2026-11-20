package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 25 轮：{@code DuelManager:917-1145}（断线 / 重连 / 宽限计时，229 行，分支覆盖 0%）的
 * <b>行为夹具</b>测试 —— 零生产改动。
 *
 * <h2>为什么需要这一组</h2>
 * 本仓库在断线宽限这块<b>已出过两次事故</b>：
 * <ul>
 *   <li><b>R4-07-logic-F01</b>：宽限期不停盘 → 在场方连答打满 {@code WIN_SCORE}，
 *       一场 5-0 的"正常战绩"写进 {@code duel_stats}；</li>
 *   <li><b>R14</b>：按 UUID 判"本房间是否还有人掉线" → 上一局残留条目让本局永久停盘
 *       （{@code submitAnswer} 恒 {@code NOT_IN_DUEL}，两边都答不了又不判负）。</li>
 * </ul>
 * 而现有 4 个 duel 测试类（{@code DuelDisconnectRoomIdentityTest} /
 * {@code DuelRoomPauseTest} / {@code DuelManagerAuditTest} / {@code DuelQueryAndNoticeQueueTest}）
 * <b>都只测单掉线</b>，双掉线矩阵没有任何一条被覆盖。本类补的就是那张矩阵。
 *
 * <h2>双掉线决策表（本类逐行钉住；"当前行为"列指当前实现，不是愿望）</h2>
 * <pre>
 * # | 时序                                       | 谁先到点 | 当前行为
 * --+--------------------------------------------+----------+--------------------------------------
 * 1 | A 断 → B 断（B 在 A 宽限期内）→ A 先到点     | A        | A 判负（lost-by-disconnect, player=Bob）
 *   |                                            |          | ；B 离线 → 排队 duel-already-over
 *   |                                            |          | (player=Alice)；房间拆解；B 的宽限任务被
 *   |                                            |          | cleanup cancel；双方 busy 解除
 * 2 | 同上但 B 的计时先到点                        | B        | 对称：B 判负，A 收中性文案
 * 3 | 双方断线、两条计时交错推进（各 15 tick）      | 先到点者 | 只有一条 lost-by-disconnect；另一条即使
 *   |                                            |          | 继续 tick 也走 "entry 已空→自取消"，不改判
 * 4 | A 断 → B 直接 /le duel quit                 | 无人判负 | A 排队 opponent-quit-while-disconnected
 *   |                                            |          | (player=Bob)；不写战绩、不判负
 * 5 | A 断 → A 重连（B 未断）→ A 再断              | 后一次到点 | 重连解暂停并重排题钟；再断起**新**任务，
 *   |                                            |          | 到点仍能判负（旧条目/旧任务不残留卡死）
 * 6 | 同一玩家重复 handleDisconnect                | 无到点   | 覆盖写前取消旧任务：表中恒只有一条
 * 7 | 两人都断、都不重连                           | 先到点者 | 必有一方被判负，双方都有交代，无人永久 busy
 * 8 | 双掉线，B 已回到线上（join 回调未到）        | A        | B 当场收 opponent-timeout + grace-countdown
 *   |                                            |          | ；**不排队**任何通告，绝不被说成"你被判负"
 * 9 | 双掉线，B 离线且已有更具体的通告（如 GM 解散）| A        | 不覆盖：已有通告优先（守卫 :1074）
 * 10| 房间被第三方拆（active=false），A 条目残留且 A | 无到点   | room-gone 兜底：A 在线则**直发**
 *   | 已回到线上                                  |          | duel-already-over，不排队（守卫 :1011）
 * </pre>
 *
 * <h2>计时器怎么驱动（不真等 30 秒）</h2>
 * 宽限是 {@code Bukkit.getScheduler().runTaskTimer(plugin, r, 20L, 20L)}（每秒一次，共
 * {@link DuelManager#GRACE_PERIOD_SECONDS} 次）。夹具把 {@code runTaskTimer} 整个接管：
 * <ul>
 *   <li>每次调用回一个 {@code BukkitTask} mock，并把它与 Runnable 记进
 *       {@code IdentityHashMap<BukkitTask, Runnable>}；</li>
 *   <li>测试用 {@link #tick(BukkitTask, int)} 手动跑 N 次，<b>按任务取 Runnable</b>
 *       （不按下标）—— 因为 {@code handleReconnect} 内部还会调 {@code scheduleWordTimeout}
 *       注册第二个 {@code runTaskTimer}，按下标会在那个用例里错位；</li>
 *   <li>{@code runTaskTimer} 必须返回<b>非 null</b>：{@code graceTasks.put(id, selfRef[0])} 是
 *       {@code ConcurrentHashMap}，返回 null 直接 NPE，不是"无害空任务"；</li>
 *   <li>到点需要整 {@code GRACE} 次 tick（lambda 每次先自减再判），即 30 次 —— 纯内存循环，
 *       一次测试毫秒级。</li>
 * </ul>
 *
 * <h2>纯 JVM 前提</h2>
 * 只触碰 {@code Bukkit.getPlayer(UUID)}（"此刻谁在线"的唯一事实源）、
 * {@code Bukkit.getScheduler()}、{@code Bukkit.getScoreboardManager()}（null = 无板可摘，
 * {@code removeScoreboard} 自行早退）。重连用例额外接管 {@code Bukkit.createInventory} 与
 * {@code new ItemStack(...)}（纯 JVM 无 RegistryAccess，不拦截会抛
 * {@code ExceptionInInitializerError}）。{@code SoundManager.playSound} 在
 * {@code LetsLearnEnglish.getInstance() == null} 时静默跳过。不启服、不碰数据库、不碰文件。
 *
 * <h2>关于"人造残留"用例（#4 第二段 / #9 / #10）</h2>
 * 正常路径上 {@code cleanup} 总会同时摘 {@code disconnectTimers} 与 {@code graceTasks}，
 * 所以「房间已拆但条目还在」当前<b>构造不出来</b> —— 那正是 {@code :993-1024} 那段注释自称的
 * <b>兜底</b>。这些用例刻意把条目塞回去，钉住「将来新增绕过 cleanup 的拆解路径 / 改序时，
 * 已有通告不被覆盖、在线方不被排队」。它们不是可达路径的回归，而是<b>契约</b>的回归。
 *
 * <h2>关于"变异验证"</h2>
 * 本类由只读角色编写（无 shell），本回合<b>不能运行 mvn</b>，因此<b>没有实测红/绿输出</b>。
 * 每条用例的 javadoc 标了它负责杀哪个变异（"变异锚点"），供有 shell 的一方实测。
 */
class DuelDoubleDisconnectMatrixTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000025a1a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000025b2b2");

    /** 到点需要第 GRACE 次 tick（lambda 每次先自减再判）。 */
    private static final int GRACE = DuelManager.GRACE_PERIOD_SECONDS;

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration mainConfig;
    private FileConfiguration messages;
    private MessageUtil messageUtil;
    private DuelManager manager;
    private DuelRoom room;
    private MockedStatic<Bukkit> bukkit;

    /** 本夹具捕获到的全部调度器任务 → Runnable。 */
    private final Map<BukkitTask, Runnable> runnableOfTask = new IdentityHashMap<>();
    /** 每次 {@code runTaskTimer} 的 (delay, period)，按注册顺序。 */
    private final List<long[]> timerSchedules = new ArrayList<>();
    /** 每次 {@code getPlayerMessage} 的展开参数（Player, key, placeholders...）。 */
    private final List<Object[]> messageCalls = new ArrayList<>();

    // ==================================================================
    // 夹具
    // ==================================================================

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        mainConfig = mock(FileConfiguration.class);
        messages = mock(FileConfiguration.class);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        when(mainConfig.getInt(anyString(), anyInt())).thenReturn(DuelRoom.WORD_TIMEOUT_SECONDS);
        // duel-bossbar 关掉：否则 scheduleWordTimeout 会走 Bukkit.createBossBar（未桩化 → null
        // → bar.setProgress NPE）。宽限逻辑与 BossBar 无关，关掉不影响被测行为。
        when(mainConfig.getBoolean(anyString(), anyBoolean())).thenReturn(false);
        when(messages.getString(anyString())).thenReturn("");

        // 用默认 Answer 顶掉 MessageUtil：getPlayerMessage 是 varargs，用
        // any(String[].class) 打桩在变长参数语义下易踩 InvalidUseOfMatchers；
        // 默认 Answer 拿原始 arguments，顺带把"哪个玩家收到了哪个 i18n 键"变成可观测数据
        // （本类一半断言靠它 —— 这是唯一能区分 lost-by-disconnect 与 duel-already-over 的面）。
        // toString/hashCode/equals 显式兜底：Mockito 会把它们也送进 Answer，返回类型
        // 不匹配（hashCode 要 int 却拿到 null）会 NPE 在拆箱上。
        messageUtil = mock(MessageUtil.class, invocation -> {
            String name = invocation.getMethod().getName();
            if ("toString".equals(name)) return "MessageUtilStub";
            if ("hashCode".equals(name)) return System.identityHashCode(invocation.getMock());
            if ("equals".equals(name)) return invocation.getMock() == invocation.getArgument(0);
            if ("getPlayerMessage".equals(name)) {
                messageCalls.add(flatten(invocation.getArguments()));
                return "rendered";
            }
            Class<?> rt = invocation.getMethod().getReturnType();
            if (rt == String.class) return "rendered";
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            return null;
        });
        when(plugin.getMessageUtil()).thenReturn(messageUtil);

        // 调度器：捕获 Runnable 并回非 null 的 BukkitTask（否则 graceTasks.put 处 NPE）。
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    BukkitTask task = mock(BukkitTask.class);
                    runnableOfTask.put(task, inv.getArgument(1));
                    timerSchedules.add(new long[]{
                            inv.<Long>getArgument(2), inv.<Long>getArgument(3)});
                    return task;
                });

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        // getScoreboardManager / getPlayer 故意不桩化：MockedStatic 的默认答案就是 null，
        // 恰好等于"没有可摘的计分板"（removeScoreboard 自行早退，R-72 关服加固同款）
        // 与"双方都离线"。需要的用例在方法内单独桩。

        manager = new DuelManager(plugin);
        room = new DuelRoom(A, "Alice", B, "Bob");
        activeDuelsOf().put(A, room);
        activeDuelsOf().put(B, room);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
    }

    /** varargs 展开成一层（Material 对 getArguments() 是否展开随版本而变，两种形状都吃）。 */
    private static Object[] flatten(Object[] raw) {
        List<Object> out = new ArrayList<>();
        for (Object a : raw) {
            if (a instanceof String[] arr) {
                out.addAll(Arrays.asList(arr));
            } else {
                out.add(a);
            }
        }
        return out.toArray();
    }

    // ------------------------------------------------------------------ 反射夹具

    @SuppressWarnings("unchecked")
    private Map<UUID, DuelRoom> activeDuelsOf() throws Exception {
        return (Map<UUID, DuelRoom>) field("activeDuels").get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, DuelManager.OfflineDuelNotice> noticesOf() throws Exception {
        return (Map<UUID, DuelManager.OfflineDuelNotice>) field("offlineNotices").get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Object> timersOf() throws Exception {
        return (Map<UUID, Object>) field("disconnectTimers").get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, BukkitTask> graceTasksOf() throws Exception {
        return (Map<UUID, BukkitTask>) field("graceTasks").get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, BukkitTask> wordTasksOf() throws Exception {
        return (Map<UUID, BukkitTask>) field("wordTimeoutTasks").get(manager);
    }

    private static Field field(String name) throws Exception {
        Field f = DuelManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** 与生产 {@code DuelManager.DisconnectInfo} 同形（只持 room），供"模拟漏摘条目"用。 */
    private Object newDisconnectInfo(DuelRoom r) throws Exception {
        Class<?> infoClass = Class.forName(
                "com.letslearnenglish.minecraftplugin.core.duel.DuelManager$DisconnectInfo");
        Constructor<?> ctor = infoClass.getDeclaredConstructor(DuelRoom.class);
        ctor.setAccessible(true);
        return ctor.newInstance(r);
    }

    // ------------------------------------------------------------------ 行为夹具

    private Player player(UUID id, String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    /** 手动驱动某个宽限任务 n 次 tick（生产里由调度器每秒调一次）。 */
    private void tick(BukkitTask task, int n) {
        Runnable r = runnableOfTask.get(task);
        assertNotNull(r, "该任务不是本夹具捕获到的计时器 —— 说明 handleDisconnect 没走 "
                + "runTaskTimer 的返回值登记（graceTasks.put 的对象必须是调度器返回的任务本体）");
        for (int i = 0; i < n; i++) {
            r.run();
        }
    }

    private List<Object[]> messagesFor(UUID playerId, String key) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] args : messageCalls) {
            if (args.length < 2) continue;
            if (!(args[0] instanceof Player p) || !p.getUniqueId().equals(playerId)) continue;
            if (!key.equals(args[1])) continue;
            out.add(args);
        }
        return out;
    }

    private boolean sawMessage(UUID playerId, String key) {
        return !messagesFor(playerId, key).isEmpty();
    }

    /**
     * 断言某玩家从未收到"你被判负"这条 —— 双掉线的受益方一旦收到就是与事实颠倒。
     *
     * <p>两个通道都要查：<b>在线</b>走 {@code getPlayerMessage}（{@code messageCalls}），
     * <b>离线</b>走队列（{@code offlineNotices}）。只查一个通道会让离线玩家身上这条断言
     * 变成空断言（离线根本不会调 getPlayerMessage）。
     */
    private void assertNeverLostByDisconnect(UUID playerId, String why) throws Exception {
        assertFalse(sawMessage(playerId, "duel.lost-by-disconnect"),
                why + "：该玩家绝不能被当场播报 duel.lost-by-disconnect（那等于说「你被判负」）");
        DuelManager.OfflineDuelNotice queued = noticesOf().get(playerId);
        assertTrue(queued == null || !"duel.lost-by-disconnect".equals(queued.messageKey()),
                why + "：排队通告也绝不能被写成 duel.lost-by-disconnect（进服即播，同样颠倒）");
    }

    private int countKey(String key) throws Exception {
        int n = 0;
        for (DuelManager.OfflineDuelNotice notice : noticesOf().values()) {
            if (key.equals(notice.messageKey())) n++;
        }
        return n;
    }

    // ==================================================================
    // 0. 夹具前提：每玩家一份独立宽限计时
    // ==================================================================

    @Test
    @DisplayName("前提：双掉线各起一条独立宽限任务，房间停盘但未拆（每玩家一份，不是每房间一份）")
    void doubleDisconnectRegistersOneGraceTaskPerPlayer() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));

        BukkitTask taskA = graceTasksOf().get(A);
        BukkitTask taskB = graceTasksOf().get(B);
        assertNotNull(taskA, "A 必须有一条宽限任务（补 null 会在 put 处 NPE，不是降级）");
        assertNotNull(taskB, "B 也必须有一条 —— 宽限是每玩家一份，paused 才是每房间一个");
        assertNotSame(taskA, taskB, "两条必须是不同任务实例，否则一方到点会连坐另一方");
        assertEquals(2, timersOf().size(), "disconnectTimers 必须两条");
        assertEquals(2, runnableOfTask.size(), "必须向调度器登记恰好两个计时器");
        assertTrue(room.isPaused(),
                "R4-07-logic-F01：宽限期内房间必须停盘，否则在场方连答打满 WIN_SCORE 即写出 5-0 假战绩");
        assertTrue(room.isActive(), "宽限期内不得拆房");

        // 计时器形状：runTaskTimer(plugin, r, 20L, 20L) —— 1 秒后首 tick、每秒一次。
        // 宽限秒数与 "每秒一次" 必须自洽，否则 GRACE_PERIOD_SECONDS 就不再是"秒"。
        assertEquals(2, timerSchedules.size(), "两名掉线者各登记一条计时器");
        for (long[] sched : timerSchedules) {
            assertEquals(20L, sched[0], "首 tick 必须在 20 tick（1 秒）后");
            assertEquals(20L, sched[1], "周期必须是 20 tick（1 秒/次）—— 否则 GRACE_PERIOD_SECONDS 不是秒");
        }
    }

    @Test
    @DisplayName("边界：第 GRACE-1 次 tick 不得判负，第 GRACE 次才判负（差一秒都不许提前）")
    void forfeitHappensExactlyOnTheLastTick() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        BukkitTask taskA = graceTasksOf().get(A);

        tick(taskA, GRACE - 1);
        assertTrue(noticesOf().isEmpty(), "第 " + (GRACE - 1) + " 秒还差 1 秒，不得判负");
        assertTrue(room.isActive(), "未到点前房间必须仍 active");
        assertFalse(timersOf().isEmpty(), "未到点前条目必须还在");

        tick(taskA, 1);
        assertNotNull(noticesOf().get(A), "第 " + GRACE + " 秒必须判负");
        assertEquals("duel.lost-by-disconnect", noticesOf().get(A).messageKey());
        assertFalse(room.isActive());
    }

    // ==================================================================
    // 1. A 先到点 → A 判负；B（离线）收中性文案
    // ==================================================================

    /**
     * 变异锚点：
     * <ul>
     *   <li><b>M1</b>（{@code cleanup} 只摘自己那个 UUID —— 不 remove/cancel 对手的
     *       {@code disconnectTimers} 与 {@code graceTasks}）→ {@code timersOf().isEmpty()}、
     *       {@code graceTasksOf().isEmpty()}、{@code verify(taskB).cancel()} 三处全红。
     *       注意：<b>消息层这里不会红</b> —— 对手计时即便活过拆解，第一个 tick 也会命中
     *       "房间已无"兜底分支（{@code :993-1024}），由 {@code :1011} 的 no-clobber 守卫保住
     *       正确文案。所以 M1 只能靠<b>登记表与任务生命周期</b>断言杀（见本用例 + #3 + 安全网）。</li>
     *   <li><b>M3</b>（"到点判负"改成"到点只重连/只拆房"）→ 本用例所有通告断言变红。</li>
     * </ul>
     */
    @Test
    @DisplayName("#1：A 先断、B 后断，A 的计时先到点 → A 判负、B 收中性文案，B 的任务被 cleanup 取消")
    void firstToExpireForfeitsALoses() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));
        BukkitTask taskA = graceTasksOf().get(A);
        BukkitTask taskB = graceTasksOf().get(B);
        assertNotNull(taskA);
        assertNotNull(taskB);

        tick(taskA, GRACE);

        DuelManager.OfflineDuelNotice aNotice = noticesOf().get(A);
        assertNotNull(aNotice, "判负方离线时必须排队一条通告（否则重连后零反馈 —— R14 已修的缺口）");
        assertEquals("duel.lost-by-disconnect", aNotice.messageKey());
        assertEquals("Bob", aNotice.otherPlayerName(), "{player} 占位必须是胜者名字");

        DuelManager.OfflineDuelNotice bNotice = noticesOf().get(B);
        assertNotNull(bNotice,
                "受益方也必须拿到交代（R14「蒸发半场」：他的计时被 cleanup cancel 后永远跑不到自己分支）");
        assertEquals("duel.duel-already-over", bNotice.messageKey(),
                "受益方只能收中性文案；复用 lost-by-disconnect 会说「你被判负，Alice 获胜」—— 与事实完全颠倒");
        assertEquals("Alice", bNotice.otherPlayerName());
        assertNeverLostByDisconnect(B, "#1 双掉线中 B 是胜者");

        // 房间 / 成员表 / 两张任务表 —— 不能双方永久 busy。
        assertFalse(room.isActive(), "判负后房间必须置 inactive");
        assertNull(activeDuelsOf().get(A));
        assertNull(activeDuelsOf().get(B));
        assertTrue(timersOf().isEmpty(), "判负拆房后 disconnectTimers 必须干净（含对手那条）");
        assertTrue(graceTasksOf().isEmpty(), "graceTasks 必须干净 —— cleanup 要顺手 cancel 对手的宽限任务");
        assertFalse(manager.isBusy(A));
        assertFalse(manager.isBusy(B));
        assertFalse(manager.isInDuel(A));
        assertFalse(manager.isInDuel(B));

        verify(taskA).cancel();
        // M1 的锚点：对手（受益方）的任务必须被 cancel，否则它带着已空登记表每秒空转到关服。
        verify(taskB).cancel();
    }

    // ==================================================================
    // 2. 对称：B 的计时先到点 → B 判负
    // ==================================================================

    @Test
    @DisplayName("#2：同一时序换一条计时先到点 → 判负方随之翻转（谁先到点谁负，不是固定判某一边）")
    void firstToExpireForfeitsBLoses() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));
        BukkitTask taskA = graceTasksOf().get(A);
        BukkitTask taskB = graceTasksOf().get(B);

        tick(taskB, GRACE);

        DuelManager.OfflineDuelNotice bNotice = noticesOf().get(B);
        assertNotNull(bNotice);
        assertEquals("duel.lost-by-disconnect", bNotice.messageKey());
        assertEquals("Alice", bNotice.otherPlayerName());

        DuelManager.OfflineDuelNotice aNotice = noticesOf().get(A);
        assertNotNull(aNotice, "受益方 A 同样必须有交代");
        assertEquals("duel.duel-already-over", aNotice.messageKey());
        assertNeverLostByDisconnect(A, "#2 受益方是 A");

        assertFalse(room.isActive());
        assertTrue(timersOf().isEmpty());
        assertTrue(graceTasksOf().isEmpty());
        verify(taskA).cancel();
    }

    // ==================================================================
    // 3. 交错推进（"几乎同时"）：只有一条 lost-by-disconnect
    // ==================================================================

    /**
     * 变异锚点：
     * <ul>
     *   <li>任何造成"双方各判一次负"的改动 → {@code lost == 1} 与
     *       {@code noticesOf().size() == 2} 变红；</li>
     *   <li>删掉 {@code :988} 的 {@code !disconnectTimers.containsKey(playerId)} 首 tick 闸门
     *       → 残留计时会走判负分支并试图改判，第二段"通告键不变"断言变红；</li>
     *   <li>删掉 {@code :1011}/{@code :1074} 的 no-clobber 守卫之一 → 第二段同样变红
     *       （受益方的中性文案被再写一遍时，若守卫缺失则无害；但若守卫被替换成"无条件覆盖成
     *       lost-by-disconnect"则必然红）。</li>
     * </ul>
     */
    @Test
    @DisplayName("#3：两条计时交错推进 → 只允许一方判负，残留 tick 不得改判、不得产生第二条 lost")
    void interleavedTimersForfeitExactlyOnce() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));
        BukkitTask taskA = graceTasksOf().get(A);
        BukkitTask taskB = graceTasksOf().get(B);

        // "几乎同时掉线"的真实形状：两条计时在同一秒范围内各走一段。
        tick(taskA, GRACE / 2);
        tick(taskB, GRACE / 2);
        assertTrue(room.isActive(), "都还没到点时房间不得被拆");
        assertTrue(noticesOf().isEmpty(), "未到点前不得给任何一方排队通告（那会让进服播报抢跑）");

        tick(taskA, GRACE - GRACE / 2); // A 先到点

        assertEquals(1, countKey("duel.lost-by-disconnect"),
                "只能有一方被判负 —— 两条 lost-by-disconnect 会互相矛盾");
        assertEquals(1, countKey("duel.duel-already-over"), "另一方必须收到中性文案");
        assertEquals(2, noticesOf().size(), "两名玩家都必有交代，不许有静默的一方");

        // B 的残留计时被强行继续跑（生产里它已被 cleanup cancel，见 #1）：不得改判。
        tick(taskB, GRACE);
        assertEquals(1, countKey("duel.lost-by-disconnect"), "残留计时不得产生第二条判负");
        assertEquals("duel.duel-already-over", noticesOf().get(B).messageKey(),
                "受益方的中性文案不得被改写成「你被判负」");
        assertNull(timersOf().get(B), "残留计时必须自行摘条目，不得残留");
    }

    // ==================================================================
    // 4. A 断 → B 直接 quit（R23-A1）；残留计时不得覆盖这条通告（守卫 :1011）
    // ==================================================================

    /**
     * 变异锚点：
     * <ul>
     *   <li><b>M2a</b>：删掉 {@code :1011} 的 {@code if (!offlineNotices.containsKey(playerId))}
     *       守卫 → 第二段变红（{@code opponent-quit-while-disconnected} 被覆盖成
     *       {@code duel-already-over}，把"对手主动退出"降级成"对决已经结束了"）。</li>
     *   <li>把 quit 改成"判负 + 写战绩"→ 第一条断言与 {@code lost-by-disconnect} 缺席断言变红。</li>
     * </ul>
     */
    @Test
    @DisplayName("#4：A 断 → B 直接 quit → A 收 opponent-quit-while-disconnected；残留计时不得覆盖它")
    void peerQuitWhileDisconnectedKeepsItsOwnNotice() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        BukkitTask taskA = graceTasksOf().get(A);
        assertNotNull(taskA);

        Player bob = player(B, "Bob");
        bukkit.when(() -> Bukkit.getPlayer(B)).thenReturn(bob); // B 在线，自己执行 /le duel quit
        manager.quitDuel(bob);

        DuelManager.OfflineDuelNotice aNotice = noticesOf().get(A);
        assertNotNull(aNotice, "对手 quit 时离线方必须收到交代（R23 A1）");
        assertEquals("duel.opponent-quit-while-disconnected", aNotice.messageKey(),
                "quit 不判负、不记战绩：不得复用 lost-by-disconnect，也不得复用 cancelled-by-gm");
        assertEquals("Bob", aNotice.otherPlayerName());
        assertFalse(room.isActive(), "quit 后房间必须置 inactive");
        // atLeastOnce 而非 times(1)：quitDuel → cleanup 先 cancel 一次，随后人造残留那段
        // 的 tick 又会走 selfCancelGrace 再 cancel 一次（cancel 是幂等的，重复不算缺陷）。
        verify(taskA, atLeastOnce()).cancel();

        // ---- 人造残留：模拟"未来某条拆解路径漏摘条目 / cancel 未生效" ----
        timersOf().put(A, newDisconnectInfo(room));
        tick(taskA, 1);

        assertEquals("duel.opponent-quit-while-disconnected", noticesOf().get(A).messageKey(),
                "「已有通告不覆盖」：更具体的原因不得被中性 duel-already-over 吃掉");
        assertNull(timersOf().get(A), "兜底分支必须自行摘条目，不得残留");
    }

    // ==================================================================
    // 5 / 6 / 7. 重连后再断；重复断线；都不重连
    // ==================================================================

    /**
     * 变异锚点：
     * <ul>
     *   <li>把 {@code handleReconnect} 的 {@code isAnyDisconnectPendingFor(room)} 判据改回
     *       {@code disconnectTimers.containsKey(p1/p2)} 一类写法 → "解除暂停"断言在
     *       <b>残留条目</b>场景下变红（本用例还额外钉住"再断能起新任务"）；</li>
     *   <li>让 {@code handleDisconnect} 复用旧任务 / 不再覆盖写 → {@code assertNotSame} 与
     *       "表中只有一条"变红；</li>
     *   <li><b>M3</b> → 第二次到点不判负，"仍必须判负"变红。</li>
     * </ul>
     *
     * <p>这是本类唯一需要完整 {@code handleReconnect} 副作用面的用例：它要新建 {@code DuelGUI}
     * （{@code Bukkit.createInventory} + {@code new ItemStack(...)}）并写计分板，故额外接管
     * 这两处并给一个真能 {@code getNewScoreboard()} 的 {@code ScoreboardManager}。
     */
    @Test
    @DisplayName("#5：A 断 → A 重连（B 未断）→ 解暂停；A 再断 → 新宽限任务且到点仍能判负")
    void reconnectThenDisconnectStartsFreshGrace() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        BukkitTask firstTask = graceTasksOf().get(A);
        assertNotNull(firstTask);
        assertTrue(room.isPaused());

        stubGuiAndScoreboard();
        try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
            manager.handleReconnect(player(A, "Alice"));
        }

        assertFalse(timersOf().containsKey(A), "重连必须摘掉自己的宽限条目");
        assertFalse(graceTasksOf().containsKey(A), "重连必须摘掉自己的宽限任务");
        verify(firstTask).cancel();
        assertFalse(room.isPaused(), "B 并未掉线 → 本房间不该还有人处宽限期，必须解除停盘");
        assertFalse(wordTasksOf().isEmpty(),
                "解除停盘后必须重排本题超时，否则房间会停在「已复赛但不发题」的半死态");

        // ---- 再断：必须是新的宽限，不能因为第一次的残留条目永久卡住 ----
        manager.handleDisconnect(player(A, "Alice"));
        BukkitTask secondTask = graceTasksOf().get(A);
        assertNotNull(secondTask, "第二次断线必须起新的宽限任务");
        assertNotSame(firstTask, secondTask, "第二次断线不得复用旧任务实例");
        assertTrue(room.isPaused(), "再次断线必须重新停盘");
        assertEquals(1, timersOf().size(), "同房间同时只允许一条属于本玩家的宽限条目");

        tick(secondTask, GRACE);

        DuelManager.OfflineDuelNotice aNotice = noticesOf().get(A);
        assertNotNull(aNotice, "第二次到点也必须给出交代");
        assertEquals("duel.lost-by-disconnect", aNotice.messageKey(),
                "第二次断线到点仍必须判负 —— 不得因为第一次的残留条目永久卡住");
        assertFalse(room.isActive());
        assertTrue(timersOf().isEmpty());
    }

    /** 重连路径的外部门：GUI 渲染 + 计分板。只在本用例里打开。 */
    private void stubGuiAndScoreboard() {
        ScoreboardManager sbm = mock(ScoreboardManager.class);
        Scoreboard board = mock(Scoreboard.class);
        Objective objective = mock(Objective.class);
        Score line = mock(Score.class);
        Team team = mock(Team.class);
        when(sbm.getNewScoreboard()).thenReturn(board);
        when(board.registerNewObjective(anyString(), anyString(), anyString())).thenReturn(objective);
        when(objective.getScore(anyString())).thenReturn(line);
        when(board.registerNewTeam(anyString())).thenReturn(team);
        bukkit.when(Bukkit::getScoreboardManager).thenReturn(sbm);

        Inventory inv = mock(Inventory.class);
        when(inv.getSize()).thenReturn(27);
        bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString())).thenReturn(inv);
    }

    /**
     * 变异锚点：删掉 {@code handleDisconnect} 开头的 {@code prevGrace.cancel()}（R5-M1(a)）
     * → {@code verify(firstTask).cancel()} 与"表中只有一条"变红。
     *
     * <p><b>诚实说明</b>：生产里被 cancel 的任务不会再跑，所以本用例<b>不</b>去 tick 旧任务
     * （那不是可达状态）；"不会双判负"这条性质由"表中恒只有一条任务"结构性地保证。
     */
    @Test
    @DisplayName("#6：同一玩家重复 handleDisconnect → 覆盖写前取消旧任务，表中恒只有一条")
    void duplicateDisconnectCancelsPreviousTask() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        BukkitTask firstTask = graceTasksOf().get(A);
        assertNotNull(firstTask);

        manager.handleDisconnect(player(A, "Alice"));
        BukkitTask secondTask = graceTasksOf().get(A);
        assertNotNull(secondTask);
        assertNotSame(firstTask, secondTask, "第二次断线必须换新任务实例");
        verify(firstTask).cancel();
        assertEquals(1, graceTasksOf().size(), "同一玩家恒只有一条宽限任务（否则会双倍空转）");
        assertEquals(1, timersOf().size(), "disconnectTimers 同样只允许一条");

        tick(secondTask, GRACE);
        assertEquals(1, countKey("duel.lost-by-disconnect"), "只允许判负一次");
        assertTrue(timersOf().isEmpty());
    }

    @Test
    @DisplayName("#7：两人都断、都不重连 → 必有一方被判负，双方都有交代，无人永久 busy")
    void bothDisconnectedNeverStayBusy() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));
        BukkitTask taskA = graceTasksOf().get(A);
        BukkitTask taskB = graceTasksOf().get(B);

        tick(taskA, GRACE);

        assertEquals(1, countKey("duel.lost-by-disconnect"), "必须有人被判负（不能双方永久 busy）");
        assertEquals(2, noticesOf().size(), "双方都必须拿到一句交代");
        assertFalse(manager.isBusy(A));
        assertFalse(manager.isBusy(B));
        assertFalse(room.isActive());

        // 另一条计时即便继续跑，也不得复活房间或改判。
        tick(taskB, GRACE);
        assertFalse(room.isActive(), "残留计时不得把房间拉回 active");
        assertFalse(manager.isBusy(A));
        assertFalse(manager.isBusy(B));
        assertEquals(1, countKey("duel.lost-by-disconnect"));
    }

    // ==================================================================
    // 8. 双掉线时 B 已回到线上 —— 受益方拿到"对手超时"，不是"你被判负"
    // ==================================================================

    /**
     * 变异锚点：
     * <ul>
     *   <li><b>M3</b>（到点判负 → 到点只重连）→ {@code opponent-timeout} 断言变红；</li>
     *   <li>把 {@code :1073} 的条件改成"无条件排队"→ {@code noticesOf().get(B) == null} 变红
     *       （在线方已当场收到，再排队会在下次进服重复播报一次）；</li>
     *   <li>删掉"在线就当场发"（只排队）→ {@code sawMessage(B, "duel.opponent-timeout")} 变红；</li>
     *   <li>把给受益方的文案改成 {@code lost-by-disconnect} → {@code assertNeverLostByDisconnect} 变红。</li>
     * </ul>
     */
    @Test
    @DisplayName("#8：双掉线，B 已回到线上 → 当场收 opponent-timeout + grace-countdown，不排队、绝不被说成判负")
    void onlinePeerGetsTimeoutWordingNotLostByDisconnect() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));
        BukkitTask taskA = graceTasksOf().get(A);
        BukkitTask taskB = graceTasksOf().get(B);

        // B 已经进服，但 handleReconnect 的延迟回调还没跑 —— 极端窗口，getPlayer 已能拿到实体。
        Player bob = player(B, "Bob");
        bukkit.when(() -> Bukkit.getPlayer(B)).thenReturn(bob);

        tick(taskA, GRACE);

        assertTrue(sawMessage(B, "duel.opponent-timeout"),
                "受益方在线时必须当场收到「{player} 未能在 {seconds} 秒内重连，你赢了」");
        List<Object[]> timeoutCalls = messagesFor(B, "duel.opponent-timeout");
        assertEquals(1, timeoutCalls.size(), "同一次到点只提示一次");
        List<Object> args = Arrays.asList(timeoutCalls.get(0));
        assertTrue(args.contains("Alice"), "{player} 占位必须是被判负方，实际参数=" + args);
        assertTrue(args.contains(String.valueOf(GRACE)),
                "秒数必须来自 GRACE_PERIOD_SECONDS 单一真相源（R-86n），实际参数=" + args);

        assertTrue(sawMessage(B, "duel.grace-countdown"),
                "倒计时播报（20/10/5..1 秒）必须送到在场方 —— 他是唯一还看得见屏幕的人");

        assertNull(noticesOf().get(B), "已当场通知的在线方不得再排队（否则下次进服重复播报一次）");
        assertNeverLostByDisconnect(B, "#8 B 在线且是胜者");

        DuelManager.OfflineDuelNotice aNotice = noticesOf().get(A);
        assertNotNull(aNotice);
        assertEquals("duel.lost-by-disconnect", aNotice.messageKey());
        assertEquals("Bob", aNotice.otherPlayerName());

        assertFalse(room.isActive());
        verify(taskB).cancel();
    }

    // ==================================================================
    // 9. 守卫 :1074 —— 受益方已有更具体的通告时不得被中性文案覆盖
    // ==================================================================

    /**
     * 变异锚点：<b>M2b</b> —— 删掉 {@code :1074} 的
     * {@code && !offlineNotices.containsKey(opponentId)} → 本用例变红。
     *
     * <p><b>为什么需要人造通告</b>：主线分支 {@code :1073-1077} 只在对手离线时补排，
     * 而"对手离线且队列里已有另一条更具体的通告"当前只能由 GM 解散等链路先发生 ——
     * 该顺序在正常时序下不成立（GM 解散会连房间一起拆）。这里直接往队列里塞一条
     * {@code cancelled-by-gm} 代表"更具体的原因先到"，钉住契约：<b>中性兜底不得吃掉具体原因</b>。
     */
    @Test
    @DisplayName("#9：B 离线且队列里已有更具体的通告 → A 到点不得把它覆盖成中性 duel-already-over")
    void neutralFallbackMustNotClobberSpecificNotice() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        manager.handleDisconnect(player(B, "Bob"));
        BukkitTask taskA = graceTasksOf().get(A);

        // 更具体的原因先到（代表 GM 解散 / 离线 quit 等链路）。
        noticesOf().put(B, new DuelManager.OfflineDuelNotice("duel.cancelled-by-gm", "Alice"));

        tick(taskA, GRACE);

        assertEquals("duel.cancelled-by-gm", noticesOf().get(B).messageKey(),
                "「已有通告不覆盖」：中性 duel-already-over 不得吃掉更具体的 cancelled-by-gm");
        assertEquals(2, noticesOf().size(),
                "A 新增一条 lost-by-disconnect，B 原有那条 cancelled-by-gm 被保留（不是被替换）");
        assertEquals("duel.lost-by-disconnect", noticesOf().get(A).messageKey());
        assertFalse(room.isActive());
    }

    // ==================================================================
    // 10. room-gone 兜底（守卫 :1011）：残留条目 + 已重连在线 → 直发，不排队
    // ==================================================================

    /**
     * 变异锚点：
     * <ul>
     *   <li>删掉 {@code :1015-1021} 的"在线则直发 / 离线则排队"分流（改成无条件排队）
     *       → {@code sawMessage(A, "duel.duel-already-over")} 与
     *       {@code noticesOf().get(A) == null} 变红；</li>
     *   <li><b>M2a</b>（删 {@code :1011} 守卫）在本用例无差别（A 本来就没通告），
     *       由 {@link #peerQuitWhileDisconnectedKeepsItsOwnNotice()} 负责；</li>
     *   <li>把这个兜底分支改回"静默 return"→ 三条断言全红
     *       （R14「蒸发半场」的原始缺陷形状）。</li>
     * </ul>
     */
    @Test
    @DisplayName("#10：房间被第三方拆、A 条目残留且已重连在线 → 直发 duel-already-over，不排队")
    void roomGoneWhileRejoinedSendsNeutralWordingDirectly() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        BukkitTask taskA = graceTasksOf().get(A);
        assertNotNull(taskA);

        // 第三方拆解的最小形状（GM / reload 路径）：只置 inactive，不清表。
        room.setActive(false);
        Player rejoined = player(A, "Alice");
        bukkit.when(() -> Bukkit.getPlayer(A)).thenReturn(rejoined);

        tick(taskA, 1);

        assertTrue(sawMessage(A, "duel.duel-already-over"),
                "房间没了必须给掉线方一句交代 —— 不得静默 return（R14「蒸发半场」）");
        assertNeverLostByDisconnect(A, "#10 第三方拆解时无从判断谁负");
        assertNull(noticesOf().get(A),
                "玩家已在线时必须当场发，不得排队（排队要等到「下一次」进服才送达）");
        assertNull(timersOf().get(A), "兜底分支必须自行摘条目");
        verify(taskA).cancel();
    }

    // ==================================================================
    // 安全网：不留僵尸宽限计时
    // ==================================================================

    @Test
    @DisplayName("安全网：判负拆房后不得留下僵尸宽限计时（任务必须已从 graceTasks 摘除并 cancel）")
    void noZombieGraceTimerAfterTeardown() throws Exception {
        manager.handleDisconnect(player(A, "Alice"));
        BukkitTask taskA = graceTasksOf().get(A);
        assertNotNull(taskA);

        tick(taskA, GRACE);

        assertTrue(graceTasksOf().isEmpty(),
                "任务跑完必须把自己从 graceTasks 摘掉（R5-M1(b) 自取消）—— 否则它带着已空登记表"
                        + "每 20 tick 空转到关服，cleanup/shutdown 因条目已摘都造访不到它");
        assertEquals(1, runnableOfTask.size(), "本用例只应有一名玩家掉线过");
        verify(taskA).cancel();
    }
}
