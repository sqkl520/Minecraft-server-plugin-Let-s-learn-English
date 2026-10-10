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
        // H9：下线清理答题态 — endQuiz 取消倒计时并移除 QuizState（含 WordManager 侧
        // endSession 落掌握度），cancelPractice 清错题本练习态；不结算、不发奖励
        plugin.getWordLearningManager().endQuiz(playerId);
        plugin.getWordLearningManager().cancelPractice(playerId);
        plugin.getPlayerDataStore().unloadPlayerData(playerId);
        plugin.getAchievementManager().unloadPlayer(playerId);
        plugin.getPlayerDataStore().unloadProfileSettings(playerId);
    }
}