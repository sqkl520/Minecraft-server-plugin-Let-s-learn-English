package com.letslearnenglish.minecraftplugin.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GuiNavigationManager — Shared back-navigation service for plugin GUIs.
 *
 * Maintains a multi-depth navigation stack per player (UUID → Deque&lt;Runnable&gt;).
 * When a player navigates from one GUI to another (e.g. English Square → Profile),
 * the source GUI stores a "back action" here. The destination GUI's back button
 * pops and executes this action, returning the player to where they came from.
 *
 * Multi-depth stacks ensure correct "back" behavior through chains like
 * English Square → Profile → Achievement Wall → Profile → Back → ... → English Square.
 */
public class GuiNavigationManager {

    /**
     * FIX 6（round14 F4）：单玩家返回栈深度上限。
     *
     * <p>为什么是 32：GUI 真实导航链最长也就 4~5 层（English Square → Profile →
     * Achievement Wall → 会话详情 → 反馈页），32 给足 6 倍余量，正常玩家永远撞不到；
     * 同时它又小到每个玩家最多只留 32 个闭包 —— 每个闭包捕获 Player + plugin +
     * GUI 回调，32 个的量级完全可控。
     *
     * <p>为什么需要上限：原先无界，push 没有任何拒绝路径，clearPlayer 只在 quit
     * 时跑。玩家在两个 GUI 页之间反复点前进/返回，每秒能压进上千帧，堆只增不减。
     */
    private static final int MAX_BACK_DEPTH = 32;

    private final Map<UUID, Deque<Runnable>> profileBackActions = new ConcurrentHashMap<>();

    /**
     * Store a back-navigation action for a player.
     * Pushes onto the front of the player's navigation deque so that
     * successive navigations do not overwrite earlier entries.
     *
     * <p>FIX 6（round14 F4）：超出 {@link #MAX_BACK_DEPTH} 时丢<b>最旧</b>的一帧（队尾），不是拒绝最新的：
     * 返回键的语义是「回到我最近来的那个地方」，最近的导航才是玩家真正要的，
     * 远古的栈底帧几乎不可能被点到（要连按 32 次返回）。ArrayDeque 实现了 Deque，
     * {@code removeLast()} 是 O(1)，且在非空时不会抛 NoSuchElementException ——
     * 这里刚 push 过所以必然非空。
     */
    public void pushBack(UUID playerId, Runnable action) {
        Deque<Runnable> deque = profileBackActions.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        deque.push(action);
        while (deque.size() > MAX_BACK_DEPTH) {
            deque.removeLast();
        }
    }

    /**
     * R-41：移除某玩家的整条返回栈（由 quit 编排点调用，防泄漏）。
     *
     * <p>原先返回栈 quit 不清：一次性玩家的 UUID → Deque 条目永久残留，
     * 且重进后点返回可能执行持旧 Player/GUI 引用的过期闭包。
     * 线程安全语义：所有 push/pop/clear 调用方均为 Bukkit 主线程（GUI 交互与
     * quit 事件），外层 ConcurrentHashMap 保证跨线程可见性，内层 ArrayDeque
     * 无需加锁（同一玩家的操作串行发生在主线程）。
     */
    public void clearPlayer(UUID playerId) {
        profileBackActions.remove(playerId);
    }

    /**
     * Retrieve and remove the back-navigation action for a player.
     * Pops from the front of the player's navigation deque.
     * Returns the fallback Runnable if the deque is empty or absent.
     */
    public Runnable popBack(UUID playerId, Runnable fallback) {
        Deque<Runnable> deque = profileBackActions.get(playerId);
        if (deque != null && !deque.isEmpty()) {
            Runnable action = deque.pop();
            if (deque.isEmpty()) {
                profileBackActions.remove(playerId);
            }
            return action;
        }
        return fallback;
    }
}
