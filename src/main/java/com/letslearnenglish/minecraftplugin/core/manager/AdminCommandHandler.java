package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordBankValidator;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class AdminCommandHandler {

    private static final String[] PLAYER_TABLES = {
        "player_data", "word_progress", "achievements", "learning_log"
    };

    private final LetsLearnEnglish plugin;

    public AdminCommandHandler(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    public void resetPlayerData(String playerName) {
        plugin.getLogger().info("Admin: Resetting data for player: " + playerName);

        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            plugin.getLogger().warning("Admin: Player not found: " + playerName);
            return;
        }

        PlayerDataStore dataStore = plugin.getPlayerDataStore();
        dataStore.unloadPlayerData(playerId);

        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            for (String table : PLAYER_TABLES) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM " + tablePrefix + table + " WHERE uuid = ?")) {
                    stmt.setString(1, playerId.toString());
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to reset player data: " + playerName, e);
            return;
        }

        plugin.getLogger().info("Admin: Player data reset complete for: " + playerName);
    }

    private UUID resolvePlayerUUID(String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target != null) {
            return target.getUniqueId();
        }

        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT uuid FROM " + tablePrefix
                + "player_data WHERE player_name = ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString("uuid"));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to lookup player UUID: " + playerName, e);
        }
        return null;
    }

    public void addWord(String difficulty, String english, String chinese) {
        plugin.getLogger().info("Admin: Adding word '" + english + "' to " + difficulty);

        WordManagerImpl wordManager = plugin.getWordManager();
        WordBank bank = wordManager.getWordBank(difficulty);
        if (bank == null) {
            plugin.getLogger().warning("Admin: Unknown difficulty level: " + difficulty);
            return;
        }

        Word word = new Word(english, chinese, null, null, null,
                Collections.emptyList(), Collections.emptyList(), difficulty, 1);
        bank.addWord(word);

        saveWordBankToYaml(difficulty, bank);
        plugin.getLogger().info("Admin: Word '" + english
                + "' added successfully to " + difficulty);
    }

    public void removeWord(String difficulty, String english) {
        plugin.getLogger().info("Admin: Removing word '" + english
                + "' from " + difficulty);

        WordManagerImpl wordManager = plugin.getWordManager();
        WordBank bank = wordManager.getWordBank(difficulty);
        if (bank == null) {
            plugin.getLogger().warning("Admin: Unknown difficulty level: " + difficulty);
            return;
        }

        boolean removed = bank.removeWord(english);
        if (!removed) {
            plugin.getLogger().warning("Admin: Word '" + english
                    + "' not found in " + difficulty);
            return;
        }

        saveWordBankToYaml(difficulty, bank);
        plugin.getLogger().info("Admin: Word '" + english
                + "' removed successfully from " + difficulty);
    }

    /**
     * Export a word bank to a YAML file in plugins/LLEP/exports/.
     *
     * @param difficulty the difficulty name of the word bank to export
     * @param sender     the command sender (for feedback messages)
     */
    public void exportWordBank(String difficulty, org.bukkit.command.CommandSender sender) {
        plugin.getLogger().info("Admin: Exporting word bank: " + difficulty);

        WordManagerImpl wordManager = plugin.getWordManager();
        WordBank bank = wordManager.getWordBank(difficulty);
        if (bank == null) {
            String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.export-failed",
                    "reason", "Unknown difficulty: " + difficulty);
            sender.sendMessage(msg);
            plugin.getLogger().warning("Admin: Export failed - unknown difficulty: " + difficulty);
            return;
        }

        // Build YAML configuration
        FileConfiguration config = new YamlConfiguration();
        config.set("difficulty", bank.getDifficulty());
        config.set("description", bank.getDescription());

        List<Map<String, Object>> wordList = new ArrayList<>();
        for (Word w : bank.getAllWords()) {
            Map<String, Object> wordData = new LinkedHashMap<>();
            wordData.put("english", w.getEnglish());
            wordData.put("chinese", w.getChinese());
            if (w.getPartOfSpeech() != null) {
                wordData.put("part_of_speech", w.getPartOfSpeech());
            }
            if (w.getPhonetic() != null) {
                wordData.put("phonetic", w.getPhonetic());
            }
            if (w.getExampleSentence() != null) {
                wordData.put("example_sentence", w.getExampleSentence());
            }
            if (!w.getSynonyms().isEmpty()) {
                wordData.put("synonyms", w.getSynonyms());
            }
            if (!w.getAntonyms().isEmpty()) {
                wordData.put("antonyms", w.getAntonyms());
            }
            wordData.put("difficulty_level", w.getDifficultyLevel());
            if (w.getReward() > 0.0) {
                wordData.put("reward", w.getReward());
            }
            wordList.add(wordData);
        }
        config.set("words", wordList);

        // Create exports directory
        File exportsDir = new File(plugin.getDataFolder(), "exports");
        if (!exportsDir.exists()) {
            exportsDir.mkdirs();
        }

        // Generate filename with timestamp
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String fileName = difficulty + "_export_" + timestamp + ".yml";
        File exportFile = new File(exportsDir, fileName);

        try {
            config.save(exportFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to save exported word bank: " + exportFile.getAbsolutePath(), e);
            String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.export-failed",
                    "reason", "IO error: " + e.getMessage());
            sender.sendMessage(msg);
            return;
        }

        String relativePath = "exports/" + fileName;
        // Log full path to console
        plugin.getLogger().info("Admin: Word bank '" + difficulty
                + "' exported to: " + exportFile.getAbsolutePath()
                + " (" + bank.getWordCount() + " words)");

        // Send relative path to sender (for in-game use, players see the relative path)
        String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.export-success",
                "difficulty", difficulty,
                "file", relativePath,
                "count", String.valueOf(bank.getWordCount()));
        sender.sendMessage(msg);
    }

    /**
     * Import a word bank from a YAML file.
     * Looks for the file in plugins/LLEP/exports/ first, then in the plugin data folder.
     * Validates the file, then imports new words (skipping duplicates).
     *
     * @param fileName the file name (relative to exports/ or data folder)
     * @param sender   the command sender (for feedback messages)
     */
    public void importWordBank(String fileName, org.bukkit.command.CommandSender sender) {
        plugin.getLogger().info("Admin: Importing word bank from file: " + fileName);

        // Look for the file in exports/ first, then in the plugin data folder
        File importFile = new File(plugin.getDataFolder(), "exports/" + fileName);
        if (!importFile.exists()) {
            importFile = new File(plugin.getDataFolder(), fileName);
        }
        if (!importFile.exists()) {
            String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.import-failed",
                    "reason", "File not found: " + fileName);
            sender.sendMessage(msg);
            plugin.getLogger().warning("Admin: Import failed - file not found: " + fileName);
            return;
        }

        // Load as YamlConfiguration
        FileConfiguration config = YamlConfiguration.loadConfiguration(importFile);

        // Validate
        WordBankValidator.ValidationResult result = WordBankValidator.validate(config);
        if (!result.valid()) {
            plugin.getLogger().warning("Admin: Import validation failed for: " + fileName);
            for (String error : result.errors()) {
                plugin.getLogger().warning("  Validation error: " + error);
            }

            String headerMsg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.validation-failed");
            sender.sendMessage(headerMsg);
            // Send each error (limit to first 15 to avoid spam)
            int shown = 0;
            for (String error : result.errors()) {
                if (shown >= 15) {
                    sender.sendMessage(ChatColor.GRAY + "... and "
                            + (result.errors().size() - 15) + " more errors");
                    break;
                }
                sender.sendMessage(ChatColor.RED + "  - " + error);
                shown++;
            }
            return;
        }

        // Parse words from validated config
        WordManagerImpl wordManager = plugin.getWordManager();
        List<?> rawList = config.getList("words");
        if (rawList == null) {
            String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.import-failed",
                    "reason", "No words list found");
            sender.sendMessage(msg);
            return;
        }

        int imported = 0;
        int skipped = 0;
        int errors = 0;
        Map<String, WordBank> affectedBanks = new java.util.HashMap<>();

        for (Object item : rawList) {
            if (!(item instanceof Map)) {
                errors++;
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> wordData = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                if (entry.getKey() instanceof String key) {
                    wordData.put(key, entry.getValue());
                }
            }

            String english = (String) wordData.get("english");
            String chinese = (String) wordData.get("chinese");

            if (english == null || english.trim().isEmpty()
                    || chinese == null || chinese.trim().isEmpty()) {
                errors++;
                plugin.getLogger().warning("Admin: Skipping word entry with missing english/chinese");
                continue;
            }

            // Determine target difficulty: use the 'difficulty' field from the word,
            // falling back to a 'difficulty' key at the top level of the config
            String targetDifficulty = (String) wordData.get("difficulty");
            if (targetDifficulty == null || targetDifficulty.trim().isEmpty()) {
                targetDifficulty = config.getString("difficulty");
            }
            if (targetDifficulty == null || targetDifficulty.trim().isEmpty()) {
                errors++;
                plugin.getLogger().warning("Admin: No difficulty for word: " + english);
                continue;
            }

            WordBank targetBank = wordManager.getWordBank(targetDifficulty);
            if (targetBank == null) {
                // Bank doesn't exist yet — create a word list file for it
                targetBank = new WordBank(targetDifficulty, new YamlConfiguration());
                // We'll track affected banks to save them later
            }

            // Check if word already exists in any WordBank (by english name, case-insensitive)
            boolean exists = false;
            for (String diff : wordManager.getAvailableDifficulties()) {
                WordBank bank = wordManager.getWordBank(diff);
                if (bank != null && bank.getWord(english) != null) {
                    exists = true;
                    break;
                }
            }

            if (exists) {
                skipped++;
                plugin.getLogger().info("Admin: Skipping duplicate word: " + english);
                continue;
            }

            // Build the Word object
            String partOfSpeech = (String) wordData.get("part_of_speech");
            String phonetic = (String) wordData.get("phonetic");
            String exampleSentence = (String) wordData.get("example_sentence");

            List<String> synonyms = new ArrayList<>(4);
            Object synObj = wordData.get("synonyms");
            if (synObj instanceof List) {
                for (Object s : (List<?>) synObj) {
                    if (s instanceof String str) synonyms.add(str);
                }
            }

            List<String> antonyms = new ArrayList<>(4);
            Object antObj = wordData.get("antonyms");
            if (antObj instanceof List) {
                for (Object a : (List<?>) antObj) {
                    if (a instanceof String str) antonyms.add(str);
                }
            }

            int difficultyLevel = 1;
            Object diffObj = wordData.get("difficulty_level");
            if (diffObj instanceof Number num) {
                difficultyLevel = num.intValue();
            }

            double reward = 0.0;
            Object rewardObj = wordData.get("reward");
            if (rewardObj instanceof Number num) {
                reward = num.doubleValue();
            }

            Word word = new Word(english, chinese, partOfSpeech, phonetic,
                    exampleSentence, synonyms, antonyms,
                    targetDifficulty, difficultyLevel, reward);
            targetBank.addWord(word);
            affectedBanks.put(targetDifficulty, targetBank);
            imported++;
        }

        // Save all affected word banks to YAML
        for (Map.Entry<String, WordBank> entry : affectedBanks.entrySet()) {
            saveWordBankToYaml(entry.getKey(), entry.getValue());
        }

        // Log summary to console
        plugin.getLogger().info("Admin: Import complete. Imported " + imported
                + " words, skipped " + skipped + " duplicates, " + errors + " errors.");

        // Send summary to sender
        String msg = plugin.getMessageUtil().getSenderMessage(sender, "wordbank.import-summary",
                "imported", String.valueOf(imported),
                "skipped", String.valueOf(skipped),
                "errors", String.valueOf(errors));
        sender.sendMessage(msg);
    }

    private void saveWordBankToYaml(String difficulty, WordBank bank) {
        FileConfiguration config = new YamlConfiguration();
        config.set("description", bank.getDescription());

        List<Map<String, Object>> wordList = new ArrayList<>();
        for (Word w : bank.getAllWords()) {
            Map<String, Object> wordData = new LinkedHashMap<>();
            wordData.put("english", w.getEnglish());
            wordData.put("chinese", w.getChinese());
            if (w.getPartOfSpeech() != null) {
                wordData.put("part_of_speech", w.getPartOfSpeech());
            }
            if (w.getPhonetic() != null) {
                wordData.put("phonetic", w.getPhonetic());
            }
            if (w.getExampleSentence() != null) {
                wordData.put("example_sentence", w.getExampleSentence());
            }
            if (!w.getSynonyms().isEmpty()) {
                wordData.put("synonyms", w.getSynonyms());
            }
            if (!w.getAntonyms().isEmpty()) {
                wordData.put("antonyms", w.getAntonyms());
            }
            wordData.put("difficulty_level", w.getDifficultyLevel());
            if (w.getReward() > 0.0) {
                wordData.put("reward", w.getReward());
            }
            wordList.add(wordData);
        }
        config.set("words", wordList);

        plugin.getConfigManager().saveConfig(config, "words/" + difficulty + ".yml");
    }
}