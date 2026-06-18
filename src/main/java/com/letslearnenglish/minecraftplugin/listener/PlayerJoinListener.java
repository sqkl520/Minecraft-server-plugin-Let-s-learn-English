package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

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
        plugin.getAchievementManager().loadPlayerAchievements(event.getPlayer().getUniqueId());
        plugin.getPlayerDataStore().loadProfileSettings(event.getPlayer().getUniqueId());

        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getUpdateChecker().notifyPlayer(event.getPlayer());
        }, 60L);

        plugin.getDailyChallengeManager().checkAndAssign(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        plugin.getPlayerDataStore().unloadPlayerData(playerId);
        plugin.getAchievementManager().unloadPlayer(playerId);
        plugin.getPlayerDataStore().unloadProfileSettings(playerId);
    }
}