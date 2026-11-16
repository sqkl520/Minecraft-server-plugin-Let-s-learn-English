package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * R14：{@code DuelManager.isAnyDisconnectPendingFor(DuelRoom)} 的<b>房间身份</b>语义。
 *
 * <p><b>缺陷形态</b>：{@code handleReconnect} 原来用
 * {@code disconnectTimers.containsKey(room.getPlayer1()) || ...containsKey(room.getPlayer2())}
 * 判定「本房间是否还有人掉线」。这个判据把「这两个 UUID 有没有宽限条目」当成
 * 「本房间有没有人掉线」—— 一旦存在<b>属于上一场对局</b>的残留条目，本房间就会被
 * 永久误判为停盘，而 {@code setPaused(true)} 会让 {@code submitAnswer} 恒返回
 * {@code NOT_IN_DUEL}，两边都答不了、又不判负，对局彻底挂死。
 *
 * <p><b>为什么必须按 {@code DisconnectInfo.room} 身份比对</b>：UUID 是跨对局复用的
 * （同一个玩家这局打完又开一局），而房间实例不是。只有实例身份能把「这条宽限条目
 * 属于这一局」表达出来。当前代码在 {@code cleanup()} 里同时摘 {@code activeDuels} 与
 * {@code disconnectTimers}，所以残留条目构造不出来 —— 但那依赖拆解顺序，属"论证出来的
 * 不可能"。本测试钉住的是<b>结构上的不可能</b>。
 *
 * <p><b>这组测试直接调 private 方法</b>：它是纯判定、无副作用、只读两个 map，
 * 没有更外的可观察面（{@code handleReconnect} 需要完整的 Bukkit Player + 调度器）。
 * 为了让测试能<b>真正驱动逻辑</b>而不是复刻逻辑，这里用反射。反射的代价是
 * 「方法改名会红」—— 那是好事，改名前该先想清楚语义有没有变。
 */
class DuelDisconnectRoomIdentityTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    /** 与生产 {@code DuelManager.DisconnectInfo} 同形：只持有一个 room 引用。 */
    private static void putDisconnect(Map<UUID, Object> timers, UUID playerId, DuelRoom room)
            throws Exception {
        Class<?> infoClass = Class.forName(
                "com.letslearnenglish.minecraftplugin.core.duel.DuelManager$DisconnectInfo");
        Constructor<?> ctor = infoClass.getDeclaredConstructor(DuelRoom.class);
        ctor.setAccessible(true);
        timers.put(playerId, ctor.newInstance(room));
    }

    /** 拿一个只注入空 disconnectTimers 的 DuelManager 实例（不触发任何 Bukkit 调用）。 */
    private static DuelManager newManager(Map<UUID, Object> timers) throws Exception {
        DuelManager mgr = new DuelManager(mock(LetsLearnEnglish.class));
        Field f = DuelManager.class.getDeclaredField("disconnectTimers");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> real = (Map<UUID, Object>) f.get(mgr);
        real.putAll(timers);
        return mgr;
    }

    private static boolean invokeIsAny(DuelManager mgr, DuelRoom room) throws Exception {
        Method m = DuelManager.class.getDeclaredMethod("isAnyDisconnectPendingFor", DuelRoom.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(mgr, room);
    }

    @Test
    @DisplayName("本房间有宽限条目 → true（必须停盘）")
    void sameRoomPending() throws Exception {
        DuelRoom room = new DuelRoom(P1, "A", P2, "B");
        Map<UUID, Object> timers = new java.util.HashMap<>();
        putDisconnect(timers, P1, room);

        assertTrue(invokeIsAny(newManager(timers), room),
                "本房间的玩家仍在宽限期，必须判定为停盘");
    }

    @Test
    @DisplayName("只有别的房间的条目 → false（本房间不许被误停盘）")
    void otherRoomDoesNotPauseThisOne() throws Exception {
        DuelRoom mine = new DuelRoom(P1, "A", P2, "B");
        // 同一对玩家、同一 UUID，但是**上一局**的房间实例
        DuelRoom previousGame = new DuelRoom(P1, "A", P2, "B");

        Map<UUID, Object> timers = new java.util.HashMap<>();
        putDisconnect(timers, P1, previousGame);

        assertFalse(invokeIsAny(newManager(timers), mine),
                "残留的上一局条目不得让本局停盘 —— 旧实现（按 UUID 查）在这里会返回 true，"
                        + "对局随即永久挂死：submitAnswer 恒 NOT_IN_DUEL，两边都答不了又不判负");
    }

    @Test
    @DisplayName("两个房间各一条条目 → 各自只看自己那条")
    void twoRoomsAreIndependent() throws Exception {
        DuelRoom roomA = new DuelRoom(P1, "A", P2, "B");
        DuelRoom roomB = new DuelRoom(P1, "A", P2, "B");
        UUID p3 = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
        UUID p4 = UUID.fromString("00000000-0000-0000-0000-0000000000a4");

        Map<UUID, Object> timers = new java.util.HashMap<>();
        putDisconnect(timers, P1, roomA);
        putDisconnect(timers, p3, roomB);

        DuelManager mgr = newManager(timers);
        assertTrue(invokeIsAny(mgr, roomA), "roomA 自己有人掉线");
        assertTrue(invokeIsAny(mgr, roomB), "roomB 自己有人掉线");
        assertFalse(invokeIsAny(mgr, new DuelRoom(P1, "A", p4, "D")),
                "第三个房间没有条目，不得被别的房间连坐");
    }

    @Test
    @DisplayName("空表 → false；room 为 null → false（不 NPE）")
    void emptyAndNull() throws Exception {
        DuelManager mgr = newManager(new java.util.HashMap<>());
        assertFalse(invokeIsAny(mgr, new DuelRoom(P1, "A", P2, "B")));
        assertFalse(invokeIsAny(mgr, null), "room 为 null 时必须安全返回 false，不得 NPE");
    }

    /**
     * 结构 lint：{@code handleReconnect} 的停盘判据不得退回按 UUID 查。
     * 上面几条行为测试已经覆盖了语义，这条钉的是「别把调用点改回去」——
     * 方法留着但没人用，同样等于缺陷复活。
     */
    @Test
    @DisplayName("handleReconnect 必须调 isAnyDisconnectPendingFor，不得退回 containsKey(UUID)")
    void reconnectUsesRoomIdentity() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/letslearnenglish/minecraftplugin/core/duel/DuelManager.java");
        assertTrue(java.nio.file.Files.exists(src),
                "必须读真实源文件（工作目录应为项目根）: " + src.toAbsolutePath());
        String text = java.nio.file.Files.readString(src);

        assertTrue(text.contains("isAnyDisconnectPendingFor(room)"),
                "handleReconnect 必须用房间身份判据");

        // 旧写法：disconnectTimers.containsKey(room.getPlayer1())
        assertFalse(text.contains("disconnectTimers.containsKey(room.getPlayer1())"),
                "不得退回按 UUID 判定（上一局残留条目会让本局永久停盘）");
        assertFalse(text.contains("disconnectTimers.containsKey(room.getPlayer2())"),
                "同上");
    }
}
