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
        // H10：主线程缓存玩家名，供 savePlayerData 落盘 player_data.player_name
        plugin.getPlayerDataStore().cachePlayerName(
                event.getPlayer().getUniqueId(), event.getPlayer().getName());

        org.bukkit.entity.Player joining = event.getPlayer();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // L-20 后半：延迟 3 秒后再 notify，玩家可能已经秒退，
            // 先复查在线，免得给离线玩家发消息。
            if (!joining.isOnline()) {
                return;
            }
            plugin.getUpdateChecker().notifyPlayer(joining);
        }, 60L);

        // R-45：纯新号（Bukkit 从未见过）发三行欢迎语 + /le 指引。
        // 老玩家零打扰。用 hasPlayedBefore 而不用查 player_data 行：
        // 数据行可能被 /lea reset 删掉，但那不代表玩家是新人。
        if (!joining.hasPlayedBefore()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!joining.isOnline()) {
                    return;
                }
                joining.sendMessage(plugin.getMessageUtil().getPlayerMessage(joining,
                        "join.welcome-1", "player", joining.getName()));
                joining.sendMessage(plugin.getMessageUtil().getPlayerMessage(joining,
                        "join.welcome-2"));
                joining.sendMessage(plugin.getMessageUtil().getPlayerMessage(joining,
                        "join.welcome-3"));
            }, 40L);
        }

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