package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Word Manager Implementation
 *
 * Responsible for:
 * - Word bank loading and management
 * - Multiple learning mode scheduling
 * - Player word mastery tracking
 * - Spaced repetition review scheduling
 */
public class WordManagerImpl {

    private static final Set<String> VALID_MODES = Set.of("spelling", "choice", "fill_blank");

    private final LetsLearnEnglish plugin;
    private final ConfigManager configManager;
    private final PlayerDataStore playerDataStore;
    private final ReviewScheduler reviewScheduler;

    private final Map<String, WordBank> wordBanks;
    private final Map<UUID, LearningSession> activeSessions;
    private final Map<String, Word> globalWordIndex;

    public WordManagerImpl(LetsLearnEnglish plugin, ConfigManager configManager,
                           PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.playerDataStore = playerDataStore;
        this.reviewScheduler = new ReviewScheduler(plugin, this, playerDataStore);
        this.wordBanks = new ConcurrentHashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
        this.globalWordIndex = new ConcurrentHashMap<>();

        loadAllWordBanks();
    }

    public void loadAllWordBanks() {
        wordBanks.clear();
        globalWordIndex.clear();

        File wordsDir = new File(plugin.getDataFolder(), "words");
        if (!wordsDir.exists() || !wordsDir.isDirectory()) {
            plugin.getLogger().warning("Words directory not found: " + wordsDir.getAbsolutePath());
            return;
        }

        File[] yamlFiles = wordsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (yamlFiles == null || yamlFiles.length == 0) {
            plugin.getLogger().warning("No .yml word bank files found in words directory.");
            return;
        }

        for (File file : yamlFiles) {
            String name = file.getName().replace(".yml", "");
            FileConfiguration config = configManager.loadWordBank(name);
            WordBank bank = new WordBank(name, config);
            wordBanks.put(name, bank);
            for (Word w : bank.getAllWords()) {
                globalWordIndex.put(w.getEnglish().toLowerCase(), w);
            }
            plugin.getLogger().info("Loaded word bank: " + name
                    + " (" + bank.getWordCount() + " words)");
        }
    }

    /**
     * Start a new learning session for a player.
     *
     * @param player     the player
     * @param difficulty difficulty level
     * @param mode       learning mode (spelling/choice/fill_blank)
     * @return the learning session
     */
    public LearningSession startSession(Player player, String difficulty, String mode) {
        WordBank bank = wordBanks.get(difficulty);
        if (bank == null) {
            throw new IllegalArgumentException("Unknown difficulty level: " + difficulty);
        }

        if (!VALID_MODES.contains(mode)) {
            throw new IllegalArgumentException("Unknown learning mode: " + mode
                    + ". Valid modes: " + VALID_MODES);
        }

        Set<String> masteredWords = playerDataStore.getMasteredWords(player.getUniqueId());
        List<Word> sessionWords = bank.selectWordsForSession(
                configManager.getMainConfig().getInt("word-system.words-per-session", 10),
                masteredWords
        );

        if (sessionWords.isEmpty()) {
            throw new IllegalStateException("No words available for session in difficulty: " + difficulty);
        }

        LearningSession session = new LearningSession(player.getUniqueId(), difficulty, mode, sessionWords);
        activeSessions.put(player.getUniqueId(), session);
        return session;
    }

    /**
     * End a player's learning session.
     */
    public void endSession(UUID playerId) {
        LearningSession session = activeSessions.remove(playerId);
        if (session != null) {
            playerDataStore.updateWordProgress(playerId, session.getResults());

            if ("review".equals(session.getMode())) {
                for (LearningSession.WordResult result : session.getResults().values()) {
                    reviewScheduler.recordReview(playerId, result.getWord().getEnglish());
                }
            }
        }
    }

    /**
     * Get a player's active learning session.
     */
    public LearningSession getSession(UUID playerId) {
        return activeSessions.get(playerId);
    }

    /**
     * Get a player's mastery level for a specific word (0.0 ~ 1.0).
     */
    public double getWordMastery(UUID playerId, String word) {
        return playerDataStore.getWordMastery(playerId, word);
    }

    /**
     * Get the word bank for a specific difficulty.
     */
    public WordBank getWordBank(String difficulty) {
        return wordBanks.get(difficulty);
    }

    /**
     * Get all available difficulty levels.
     */
    public Set<String> getAvailableDifficulties() {
        return Collections.unmodifiableSet(wordBanks.keySet());
    }

    public ReviewScheduler getReviewScheduler() {
        return reviewScheduler;
    }

    /**
     * Start a review session using words that are due for review.
     *
     * @param player the player
     * @return the review session, or null if no words are due
     */
    public LearningSession startReviewSession(Player player) {
        List<String> dueWords = reviewScheduler.getDueReviewWords(player.getUniqueId());
        if (dueWords.isEmpty()) {
            return null;
        }

        List<Word> reviewWords = new ArrayList<>(Math.min(dueWords.size(),
                configManager.getMainConfig().getInt("word-system.words-per-session", 10)));
        for (String english : dueWords) {
            Word word = globalWordIndex.get(english.toLowerCase());
            if (word != null) {
                reviewWords.add(word);
            }
        }

        if (reviewWords.isEmpty()) {
            return null;
        }

        int limit = configManager.getMainConfig().getInt("word-system.words-per-session", 10);
        if (reviewWords.size() > limit) {
            reviewWords = reviewWords.subList(0, limit);
        }

        LearningSession session = new LearningSession(player.getUniqueId(), "review", "review", reviewWords);
        activeSessions.put(player.getUniqueId(), session);
        return session;
    }

    public PlayerDataStore getPlayerDataStore() {
        return playerDataStore;
    }
}