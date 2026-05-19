package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Player Join Listener
 *
 * Handles player join/quit events for data loading, update notifications,
 * and welcome (learning encouragement) messages.
 */
public class PlayerJoinListener implements Listener {

    private final LetsLearnEnglish plugin;

    public PlayerJoinListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        plugin.getPlayerDataStore().loadPlayerData(player.getUniqueId());

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getUpdateChecker().notifyPlayer(player);
        }, 60L);

        if (plugin.getConfig().getBoolean("daily-reminder.on-join", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                sendJoinReminder(player);
            }, 80L);
        }
    }

    private void sendJoinReminder(Player player) {
        String lang = plugin.getPlayerLanguage(player);
        String prefix = plugin.getConfig().getString("prefix." + lang,
                plugin.getConfig().getString("prefix.en", ""));
        String rawMessage = plugin.getConfig().getString("daily-reminder.message",
                "{prefix}&6It's time to learn English! Use &e/le menu &6to start!");

        String message = ChatColor.translateAlternateColorCodes('&',
                rawMessage.replace("{prefix}", prefix)
                        .replace("{player}", player.getName()));
        player.sendMessage(message);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPlayerDataStore().unloadPlayerData(event.getPlayer().getUniqueId());
    }
}