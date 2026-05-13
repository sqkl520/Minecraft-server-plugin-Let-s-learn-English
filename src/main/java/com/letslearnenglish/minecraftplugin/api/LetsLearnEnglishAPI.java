package com.letslearnenglish.minecraftplugin.api;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.dialogue.DialogueManagerImpl;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import org.bukkit.entity.Player;

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

    public DialogueManagerImpl getDialogueManager() {
        return plugin.getDialogueManager();
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
}