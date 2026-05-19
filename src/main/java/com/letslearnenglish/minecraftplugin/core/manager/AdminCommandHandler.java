package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class AdminCommandHandler {

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
            String[] tables = {"player_data", "word_progress", "achievements", "learning_log"};
            for (String table : tables) {
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
            wordList.add(wordData);
        }
        config.set("words", wordList);

        plugin.getConfigManager().saveConfig(config, "words/" + difficulty + ".yml");
    }
}