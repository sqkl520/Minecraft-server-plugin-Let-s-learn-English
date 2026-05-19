package com.letslearnenglish.minecraftplugin.config;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Configuration Manager - handles loading, saving, and hot-reloading of all config files.
 *
 * Managed configuration files:
 * - config.yml         Main configuration
 * - achievements.yml   Achievement configuration
 * - words/*.yml        Word bank files
 * - dialogues/*.yml    Dialogue scene files
 * - messages/*.yml     Multi-language message files
 */
public class ConfigManager {

    private static final int MESSAGE_CONFIG_VERSION = 4;

    private final LetsLearnEnglish plugin;
    private final Map<String, FileConfiguration> configCache;

    private FileConfiguration mainConfig;
    private FileConfiguration achievementConfig;
    private FileConfiguration messageConfig;
    private FileConfiguration messageConfigZh;
    private FileConfiguration messageConfigEn;

    public ConfigManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.configCache = new HashMap<>();
    }

    public void loadAllConfigs() {
        configCache.clear();
        plugin.saveDefaultConfig();
        mainConfig = plugin.getConfig();

        achievementConfig = loadConfig("achievements.yml");

        createDirectoryIfNotExists("words");
        createDirectoryIfNotExists("dialogues");

        saveResourceIfNotExists("words/beginner.yml");
        saveResourceIfNotExists("words/intermediate.yml");
        saveResourceIfNotExists("words/advanced.yml");
        saveResourceIfNotExists("dialogues/restaurant.yml");
        saveResourceIfNotExists("dialogues/airport.yml");
        saveResourceIfNotExists("dialogues/shopping.yml");

        messageConfigZh = loadMessageConfig("messages/zh.yml");
        messageConfigEn = loadMessageConfig("messages/en.yml");

        String language = mainConfig.getString("general.language", "en");
        messageConfig = "zh".equals(language) ? messageConfigZh : messageConfigEn;

        plugin.getLogger().info("All configuration files loaded.");
    }

    public void reloadAllConfigs() {
        configCache.clear();
        plugin.reloadConfig();
        loadAllConfigs();
        plugin.getLogger().info("All configuration files hot-reloaded.");
    }

    public FileConfiguration loadConfig(String path) {
        if (configCache.containsKey(path)) {
            return configCache.get(path);
        }

        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        configCache.put(path, config);
        return config;
    }

    public FileConfiguration loadMessageConfig(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        } else {
            FileConfiguration existing = YamlConfiguration.loadConfiguration(file);
            int version = existing.getInt("config-version", 0);
            if (version < MESSAGE_CONFIG_VERSION) {
                plugin.getLogger().info("Updating " + path
                        + " (v" + version + " -> v" + MESSAGE_CONFIG_VERSION + ")");
                plugin.saveResource(path, true);
            }
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), path));
        configCache.put(path, config);
        return config;
    }

    public void saveConfig(FileConfiguration config, String path) {
        try {
            config.save(new File(plugin.getDataFolder(), path));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save config file: " + path, e);
        }
    }

    public FileConfiguration loadWordBank(String difficulty) {
        return loadConfig("words/" + difficulty + ".yml");
    }

    public FileConfiguration loadDialogueScene(String sceneName) {
        return loadConfig("dialogues/" + sceneName + ".yml");
    }

    private void createDirectoryIfNotExists(String dirName) {
        File dir = new File(plugin.getDataFolder(), dirName);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private void saveResourceIfNotExists(String resourcePath) {
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            plugin.saveResource(resourcePath, false);
        }
    }

    public FileConfiguration getMainConfig() {
        return mainConfig;
    }

    public FileConfiguration getAchievementConfig() {
        return achievementConfig;
    }

    public FileConfiguration getMessageConfig() {
        return messageConfig;
    }

    public FileConfiguration getMessageConfig(String lang) {
        return "zh".equals(lang) ? messageConfigZh : messageConfigEn;
    }
}