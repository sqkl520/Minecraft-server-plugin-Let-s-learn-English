package com.letslearnenglish.minecraftplugin.core.dialogue;

import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

/**
 * Dialogue Manager Implementation
 *
 * Manages scenario-based dialogue practice sessions.
 * Handles dialogue tree traversal, NPC interactions, and grammar checking.
 */
public class DialogueManagerImpl {

    private final ConfigManager configManager;
    private final GrammarChecker grammarChecker;

    public DialogueManagerImpl(ConfigManager configManager,
                               PlayerDataStore playerDataStore) {
        this.configManager = configManager;
        this.grammarChecker = new GrammarChecker();
    }

    /**
     * Load a dialogue scene by name.
     */
    public DialogueScene loadScene(String sceneName) {
        return new DialogueScene(configManager.loadDialogueScene(sceneName));
    }

    /**
     * Get the grammar checker instance (cached singleton).
     */
    public GrammarChecker getGrammarChecker() {
        return grammarChecker;
    }
}