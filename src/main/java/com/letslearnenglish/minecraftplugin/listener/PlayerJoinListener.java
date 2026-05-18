package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Player Join Listener
 *
 * Handles player join/quit events for data loading and cleanup.
 */
public class PlayerJoinListener implements Listener {

    private final LetsLearnEnglish plugin;

    public PlayerJoinListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getPlayerDataStore().loadPlayerData(event.getPlayer().getUniqueId());

        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getUpdateChecker().notifyPlayer(event.getPlayer());
        }, 60L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPlayerDataStore().unloadPlayerData(event.getPlayer().getUniqueId());
    }
}