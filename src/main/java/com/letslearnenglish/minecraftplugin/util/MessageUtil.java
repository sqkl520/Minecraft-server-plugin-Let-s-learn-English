package com.letslearnenglish.minecraftplugin.util;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Message Utility
 *
 * Handles multi-language message retrieval with placeholder replacement.
 * Supports color codes using the '&' symbol.
 * The prefix is read from config.yml (prefix.zh / prefix.en),
 * falling back to the language file's prefix key.
 */
public class MessageUtil {

    private static final String DEFAULT_PREFIX = "&8[&bEnglish&8] &f";

    private final LetsLearnEnglish plugin;
    private final ConfigManager configManager;

    public MessageUtil(LetsLearnEnglish plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    private String resolvePrefix(FileConfiguration messageConfig, String lang) {
        FileConfiguration mainConfig = configManager.getMainConfig();
        if (mainConfig != null) {
            String prefix = mainConfig.getString("prefix." + lang);
            if (prefix != null && !prefix.isEmpty()) {
                return prefix;
            }
        }
        return messageConfig.getString("prefix", DEFAULT_PREFIX);
    }

    /**
     * Get a message by key path with placeholder replacements.
     * Uses the server's default language for prefix resolution.
     *
     * @param key          the message key (e.g., "word.correct")
     * @param placeholders key-value pairs for placeholder replacement
     * @return the formatted message string
     */
    public String getMessage(String key, String... placeholders) {
        FileConfiguration messageConfig = configManager.getMessageConfig();
        String defaultLang = configManager.getMainConfig() != null
                ? configManager.getMainConfig().getString("general.language", "en") : "en";
        String prefix = resolvePrefix(messageConfig, defaultLang);

        String message = messageConfig.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key: " + key);
            return ChatColor.RED + "Missing message: " + key;
        }

        message = message.replace("{prefix}", prefix);

        if (placeholders != null) {
            if (placeholders.length % 2 != 0) {
                plugin.getLogger().warning("Odd number of placeholder arguments for key: " + key
                        + " (length=" + placeholders.length + ")");
            }
            for (int i = 0; i < placeholders.length - 1; i += 2) {
                message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
            }
        }

        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Get a message by key path with a map of placeholders.
     */
    public String getMessage(String key, Map<String, String> placeholders) {
        FileConfiguration messageConfig = configManager.getMessageConfig();
        String defaultLang = configManager.getMainConfig() != null
                ? configManager.getMainConfig().getString("general.language", "en") : "en";
        String prefix = resolvePrefix(messageConfig, defaultLang);

        String message = messageConfig.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key: " + key);
            return ChatColor.RED + "Missing message: " + key;
        }

        message = message.replace("{prefix}", prefix);

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Get a raw message without prefix or color processing.
     */
    public String getRawMessage(String key) {
        FileConfiguration messageConfig = configManager.getMessageConfig();
        return messageConfig.getString(key, key);
    }

    public String getPlayerMessage(Player player, String key, String... placeholders) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration messageConfig = configManager.getMessageConfig(lang);
        return formatMessage(messageConfig, lang, key, placeholders);
    }

    public String getSenderMessage(CommandSender sender, String key, String... placeholders) {
        String lang = (sender instanceof Player player)
                ? plugin.getPlayerLanguage(player)
                : "en";
        FileConfiguration messageConfig = configManager.getMessageConfig(lang);
        return formatMessage(messageConfig, lang, key, placeholders);
    }

    private String formatMessage(FileConfiguration messageConfig, String lang,
                                  String key, String... placeholders) {
        String prefix = resolvePrefix(messageConfig, lang);

        String message = messageConfig.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key: " + key);
            return ChatColor.RED + "Missing message: " + key;
        }

        message = message.replace("{prefix}", prefix);

        if (placeholders != null) {
            if (placeholders.length % 2 != 0) {
                plugin.getLogger().warning("Odd number of placeholder arguments for key: " + key
                        + " (length=" + placeholders.length + ")");
            }
            for (int i = 0; i < placeholders.length - 1; i += 2) {
                message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
            }
        }

        return ChatColor.translateAlternateColorCodes('&', message);
    }
}