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
     * @param key          the message key (e.g., "word.session-start")
     * @param placeholders key-value pairs for placeholder replacement
     * @return the formatted message string
     */
    public String getMessage(String key, String... placeholders) {
        FileConfiguration messageConfig = configManager.getMessageConfig();
        // R-55l：缺 key 回落统一为 zh（与 yml 默认 + ConfigManager 启动语言对齐）。
        String defaultLang = configManager.getMainConfig() != null
                ? configManager.getMainConfig().getString("general.language", "zh") : "zh";
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
        // R-55l：缺 key 回落统一为 zh（同上）。
        String defaultLang = configManager.getMainConfig() != null
                ? configManager.getMainConfig().getString("general.language", "zh") : "zh";
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

    // ============================================================
    //  Null-safe helpers (Phase 0 i18n safety baseline)
    // ============================================================

    /**
     * R-48/R-52: thin GUI-layer wrapper over {@link FileConfiguration#getString(String)}.
     *
     * <p>GUI classes hold a per-language {@code FileConfiguration} whose raw
     * {@code getString(key)} returns {@code null} on a missing key — the next
     * {@code .replace(...)}/{@code translateAlternateColorCodes} then NPEs and
     * the whole inventory fails to open. This helper never returns {@code null}:
     * on a miss it returns a visible {@code [?key]} placeholder (colour codes are
     * applied by the caller as before, so the placeholder shows up in-game and the
     * offending key is obvious instead of silently masked).
     *
     * <p>S7 javadoc correction: this helper consults only the single config passed
     * in — the caller has already resolved the player's language via
     * {@code getMessageConfig(lang)}. There is no zh/en cross-file merge (and no
     * zh-file fallback) for the same key here. A {@code null} config or a
     * {@code null} key also yields a visible placeholder ({@code [?null]} for a
     * null key) — the method never throws. Deliberately logging-free — the startup
     * i18n audit ({@link ConfigManager#validateRequiredKeys}) already covers
     * expected-key diagnostics, and GUI opens are player-facing hot paths.
     *
     * @param messages the per-language message config (may itself be null)
     * @param key      the message key
     * @return the raw configured value, or {@code [?key]} on any miss — never null
     */
    public static String getGuiMsg(FileConfiguration messages, String key) {
        if (messages == null || key == null) {
            return "[?" + key + "]";
        }
        String value = messages.getString(key);
        return (value != null) ? value : "[?" + key + "]";
    }

    /**
     * Fixed, visible placeholder returned when a message key cannot be resolved.
     * It intentionally surfaces the offending key (instead of a plausible-looking
     * sentence) so a missing key is obvious in-game rather than silently masked.
     */
    private static final String MISSING_KEY_PLACEHOLDER = "&c[?%s]";

    /**
     * Null-safe message lookup using the server's default language.
     *
     * <p>Unlike {@link #getMessage(String, String...)} this method is hardened against
     * every failure mode: a missing message config, a missing key, or null placeholders.
     * It never returns {@code null} and never throws — on any miss it logs a WARNING and
     * returns a visible diagnostic placeholder ({@code &c[?key]}). Callers that currently
     * read raw values via {@code FileConfiguration#getString} (which can return null and
     * trigger an NPE) can adopt this helper for a guaranteed-visible fallback.
     *
     * @param key          the message key (e.g. "word.session-start")
     * @param placeholders optional key-value pairs for substitution
     * @return the formatted message, or a visible placeholder — never null
     */
    public String getMessageSafe(String key, String... placeholders) {
        return formatSafe(configManager.getMessageConfig(), resolveDefaultLang(), key, placeholders);
    }

    /**
     * Null-safe message lookup resolved in the player's current language.
     * Behaves like {@link #getMessageSafe(String, String...)} but uses the player's language.
     *
     * @param player       the target player (null falls back to the default language)
     * @param key          the message key
     * @param placeholders optional key-value pairs for substitution
     * @return the formatted message, or a visible placeholder — never null
     */
    public String getPlayerMessageSafe(Player player, String key, String... placeholders) {
        String lang = (player != null) ? plugin.getPlayerLanguage(player) : resolveDefaultLang();
        return formatSafe(configManager.getMessageConfig(lang), lang, key, placeholders);
    }

    /**
     * @return true if the message file for {@code lang} defines {@code key}; never throws
     *         on a null config. Backs the startup i18n audit in {@link ConfigManager}.
     */
    public boolean hasMessageKey(String lang, String key) {
        FileConfiguration messageConfig = configManager.getMessageConfig(lang);
        return messageConfig != null && key != null && messageConfig.isSet(key);
    }

    private String resolveDefaultLang() {
        FileConfiguration mainConfig = configManager.getMainConfig();
        // R-55l：缺 key 回落统一为 zh（同上）。
        return (mainConfig != null) ? mainConfig.getString("general.language", "zh") : "zh";
    }

    /**
     * Shared null-safe formatter: resolves the key, falls back to a visible placeholder on any
     * miss (logging a WARNING), then applies {prefix}, placeholder substitution and '&' colours.
     * Guaranteed non-null; never throws.
     */
    private String formatSafe(FileConfiguration messageConfig, String lang, String key, String... placeholders) {
        if (key == null) {
            plugin.getLogger().warning("Null message key requested.");
            return ChatColor.translateAlternateColorCodes('&', String.format(MISSING_KEY_PLACEHOLDER, "null"));
        }
        if (messageConfig == null) {
            plugin.getLogger().warning("Message config unavailable while resolving key: " + key);
            return ChatColor.translateAlternateColorCodes('&', String.format(MISSING_KEY_PLACEHOLDER, key));
        }

        String message = messageConfig.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key: " + key + " (lang=" + lang + ")");
            return ChatColor.translateAlternateColorCodes('&', String.format(MISSING_KEY_PLACEHOLDER, key));
        }

        message = message.replace("{prefix}", resolvePrefix(messageConfig, lang));

        if (placeholders != null) {
            if (placeholders.length % 2 != 0) {
                plugin.getLogger().warning("Odd number of placeholder arguments for key: " + key
                        + " (length=" + placeholders.length + ")");
            }
            for (int i = 0; i < placeholders.length - 1; i += 2) {
                if (placeholders[i] != null && placeholders[i + 1] != null) {
                    message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
                }
            }
        }

        return ChatColor.translateAlternateColorCodes('&', message);
    }
}