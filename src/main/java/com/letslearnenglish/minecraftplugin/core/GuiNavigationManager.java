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
