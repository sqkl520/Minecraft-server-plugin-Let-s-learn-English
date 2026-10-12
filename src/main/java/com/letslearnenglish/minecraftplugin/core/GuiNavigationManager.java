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

    private final Map<UUID, Deque<Runnable>> profileBackActions = new ConcurrentHashMap<>();

    /**
     * Store a back-navigation action for a player.
     * Pushes onto the front of the player's navigation deque so that
     * successive navigations do not overwrite earlier entries.
     */
    public void pushBack(UUID playerId, Runnable action) {
        profileBackActions.computeIfAbsent(playerId, k -> new ArrayDeque<>()).push(action);
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
