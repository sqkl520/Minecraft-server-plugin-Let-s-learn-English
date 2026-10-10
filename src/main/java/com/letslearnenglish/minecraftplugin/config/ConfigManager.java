package com.letslearnenglish.minecraftplugin.config;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Material;
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
 * - words/*.yml      Word bank files
 * - messages/*.yml     Multi-language message files
 */
public class ConfigManager {

    private static final int MESSAGE_CONFIG_VERSION = 6;

    /**
     * Config keys the code actually relies on at runtime. Absence is logged as a WARNING on
     * startup (see {@link #validateRequiredKeys()}). This is a diagnostic list only — missing
     * keys still degrade gracefully to each call site's built-in default.
     */
    private static final String[] REQUIRED_CONFIG_KEYS = {
            "general.language",
            "prefix.zh",
            "prefix.en",
            "database.type",
            "word-system.answer-timeout",
            "word-system.words-per-session",
            "gui.menu-size"
    };

    /**
     * Message keys that must exist in every language file. Absence (in zh or en) is logged as a
     * WARNING on startup. These are keys whose callers would otherwise show a visible fallback or,
     * in a few legacy call sites, risk an NPE on a null {@code getString}.
     */
    private static final String[] REQUIRED_MESSAGE_KEYS = {
            "prefix",
            "general.no-permission",
            "general.reload-success",
            "word.session-start",
            "word.correct",
            "word.incorrect",
            "dialogue.proxy-achievement",
            "gui.main-menu-title"
    };

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

        saveResourceIfNotExists("words/beginner.yml");
        saveResourceIfNotExists("words/intermediate.yml");
        saveResourceIfNotExists("words/advanced.yml");

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

    /**
     * Startup safety audit (Phase 0): warn about expected-but-missing config and i18n keys.
     *
     * <p>This is a pure diagnostic pass — it ONLY emits log output. It never changes control
     * flow, never disables features and never mutates config, so a missing key behaves exactly
     * as before (the owning call site falls back to its own built-in default).
     */
    public void validateRequiredKeys() {
        int warnings = 0;

        if (mainConfig == null) {
            plugin.getLogger().warning("[Config Audit] Main config is not loaded; skipping config key audit.");
        } else {
            for (String key : REQUIRED_CONFIG_KEYS) {
                if (!mainConfig.isSet(key)) {
                    plugin.getLogger().warning("[Config Audit] Missing expected config key: " + key
                            + " (feature will fall back to its built-in default)");
                    warnings++;
                }
            }
        }

        warnings += auditMessageKeys("zh", messageConfigZh);
        warnings += auditMessageKeys("en", messageConfigEn);

        if (warnings == 0) {
            plugin.getLogger().info("[Config Audit] All expected config and i18n keys present.");
        } else {
            plugin.getLogger().warning("[Config Audit] " + warnings
                    + " expected key(s) missing — see warnings above. Plugin continues with defaults.");
        }
    }

    private int auditMessageKeys(String lang, FileConfiguration messageConfig) {
        if (messageConfig == null) {
            plugin.getLogger().warning("[i18n Audit] Message file for language '" + lang
                    + "' is not loaded; skipping its key audit.");
            return 0;
        }
        int missing = 0;
        for (String key : REQUIRED_MESSAGE_KEYS) {
            if (!messageConfig.isSet(key)) {
                plugin.getLogger().warning("[i18n Audit] Missing expected message key: " + key
                        + " (lang=" + lang + ")");
                missing++;
            }
        }
        return missing;
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

    public int getGuiSlot(String guiName, String itemKey, int defaultSlot) {
        return mainConfig.getInt("gui-layout." + guiName + "." + itemKey + ".slot", defaultSlot);
    }

    public Material getGuiMaterial(String guiName, String itemKey, Material defaultMaterial) {
        String matName = mainConfig.getString("gui-layout." + guiName + "." + itemKey + ".material");
        if (matName != null) {
            try {
                return Material.valueOf(matName.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return defaultMaterial;
    }

    public Material getGuiBorderMaterial(String guiName, Material defaultMaterial) {
        String matName = mainConfig.getString("gui-layout." + guiName + ".border-material");
        if (matName != null) {
            try {
                return Material.valueOf(matName.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return defaultMaterial;
    }
}