package com.letslearnenglish.minecraftplugin.api;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

/**
 * Let's Learn English! Public API Interface
 *
 * Provides API for other plugin developers to extend learning functionality.
 * All methods are designed to be thread-safe.
 *
 * Usage example:
 * <pre>
 *   LetsLearnEnglishAPI api = LetsLearnEnglishAPI.getInstance();
 *   api.getWordManager().getPlayerMastery(player, "apple");
 * </pre>
 */
public class LetsLearnEnglishAPI {

    private static LetsLearnEnglishAPI instance;
    private final LetsLearnEnglish plugin;

    public LetsLearnEnglishAPI(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public static LetsLearnEnglishAPI getInstance() {
        return instance;
    }

    public WordManagerImpl getWordManager() {
        return plugin.getWordManager();
    }

    public AIChatManager getAIChatManager() {
        return plugin.getAIChatManager();
    }

    public ProgressManagerImpl getProgressManager() {
        return plugin.getProgressManager();
    }

    public boolean isPlayerLearning(Player player) {
        return plugin.getPlayerDataStore().isPlayerInSession(player.getUniqueId());
    }

    public String getPluginVersion() {
        return plugin.getDescription().getVersion();
    }

    public AchievementManager getAchievementManager() {
        return plugin.getAchievementManager();
    }

    public PlayerDataStore getPlayerDataStore() {
        return plugin.getPlayerDataStore();
    }

    public boolean hasAchievement(UUID playerId, Achievement achievement) {
        return plugin.getAchievementManager().hasAchievement(playerId, achievement);
    }

    public Set<Achievement> getUnlockedAchievements(UUID playerId) {
        return plugin.getAchievementManager().getUnlockedAchievements(playerId);
    }

    public int getPlayerAge(UUID playerId) {
        return plugin.getPlayerDataStore().getProfileSettings(playerId).getAge();
    }

    public void setPlayerAge(UUID playerId, int age) {
        plugin.getPlayerDataStore().setAge(playerId, age);
    }
}