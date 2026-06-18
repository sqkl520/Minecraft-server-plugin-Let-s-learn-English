package com.letslearnenglish.minecraftplugin.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GuiNavigationManager — Shared back-navigation service for plugin GUIs.
 *
 * Maintains a single-depth navigation stack per player (UUID → Runnable).
 * When a player navigates from one GUI to another (e.g. English Square → Profile),
 * the source GUI stores a "back action" here. The destination GUI's back button
 * pops and executes this action, returning the player to where they came from.
 */
public class GuiNavigationManager {

    private final Map<UUID, Runnable> profileBackActions = new ConcurrentHashMap<>();

    /**
     * Store a back-navigation action for a player.
     */
    public void pushBack(UUID playerId, Runnable action) {
        profileBackActions.put(playerId, action);
    }

    /**
     * Retrieve and remove the back-navigation action for a player.
     * Returns the fallback Runnable if no action was stored.
     */
    public Runnable popBack(UUID playerId, Runnable fallback) {
        Runnable action = profileBackActions.remove(playerId);
        return (action != null) ? action : fallback;
    }
}
