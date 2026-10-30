package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * DuelListener — handles PlayerJoinEvent and PlayerQuitEvent for duel disconnect/reconnect.
 *
 * When a player disconnects mid-duel, the DuelManager starts a 30-second grace period.
 * If the player rejoins within the grace period, the duel resumes normally.
 * If the grace period expires, the disconnected player forfeits.
 */
public class DuelListener implements Listener {

    private final LetsLearnEnglish plugin;

    public DuelListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        DuelManager duelManager = plugin.getDuelManager();
        if (duelManager != null) {
            duelManager.handleDisconnect(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        DuelManager duelManager = plugin.getDuelManager();
        if (duelManager != null) {
            UUID playerId = event.getPlayer().getUniqueId();   // 不闭包持有 Player 句柄（R5-M1(c)）
            // Run slightly delayed to ensure player is fully loaded
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;
                duelManager.handleReconnect(p);
            }, 20L); // 1 second delay
        }
    }
}
