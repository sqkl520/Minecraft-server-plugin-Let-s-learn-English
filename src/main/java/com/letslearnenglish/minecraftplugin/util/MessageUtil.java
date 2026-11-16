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
        // R14-F3：语言标签可能是 "ZH"/"zh_CN"/"cn"（player_lang.yml 与 config.yml 都是自由
        // 字符串），直接拼 "prefix." + lang 会得到 prefix.ZH / prefix.cn —— 取不到 → 静默掉回
        // 语言文件自己的 prefix，玩家的自定义前缀"神秘消失"。归一化后只可能是 zh/en 两个键。
        String canonicalLang = ConfigManager.canonicalLanguage(lang);
        FileConfiguration mainConfig = configManager.getMainConfig();
        if (mainConfig != null) {
            String prefix = mainConfig.getString("prefix." + canonicalLang);
            if (prefix != null && !prefix.isEmpty()) {
                return prefix;
            }
        }
        // R14-F3：null 配置下不许解引用（formatMessage 旧实现在这里 NPE，见 formatMessage 的守卫）。
        // 主配置拿不到前缀时退回内置前缀，而不是抛异常。
        if (messageConfig == null) {
            return DEFAULT_PREFIX;
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
        if (messageConfig == null) {
            plugin.getLogger().warning("Message config unavailable while resolving key: " + key);
            return ChatColor.translateAlternateColorCodes('&', String.format(MISSING_KEY_PLACEHOLDER, key));
        }
        // R-55l：缺 key 回落统一为 zh（与 yml 默认 + ConfigManager 启动语言对齐）。
        // R14-F3：走 ConfigManager.getDefaultLanguage()——原实现直接读原始字符串，
        // `general.language: cn` 会拼出 `prefix.cn` 取不到，前缀静默丢失。
        String prefix = resolvePrefix(messageConfig, configManager.getDefaultLanguage());

        String message = messageConfig.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key: " + key);
            // R14（红队 INFO-4 收口）：原为硬编码英文 `"Missing message: " + key`，
            // 会原样漏进中文客户端。同一文件里 getMessageSafe / formatSafe / getGuiMsg
            // 三条路径早已统一成「WARNING + 可见占位符 &c[?key]」，只有这里和 Map 重载
            // 还留着旧写法 —— 缺键时玩家看到一句英文，反而比看到 [?key] 更难自查。
            // 占位符刻意保留键名：缺的是哪个键必须一眼可见，不能退化成空白提示。
            return ChatColor.translateAlternateColorCodes('&', String.format(MISSING_KEY_PLACEHOLDER, key));
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
        if (messageConfig == null) {
            plugin.getLogger().warning("Message config unavailable while resolving key: " + key);
            return ChatColor.translateAlternateColorCodes('&', String.format(MISSING_KEY_PLACEHOLDER, key));
        }
        // R-55l：缺 key 回落统一为 zh（同上）。
        // R14-F3：同上——canonical 默认语言，避免 `prefix.cn` 这类取不到的键。
        String prefix = resolvePrefix(messageConfig, configManager.getDefaultLanguage());

        String message = messageConfig.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key: " + key);
            // R14（红队 INFO-4 收口）：与上一条同因，统一成可见占位符。
            return ChatColor.translateAlternateColorCodes('&', String.format(MISSING_KEY_PLACEHOLDER, key));
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

    /**
     * R14-F3（FIX 3）：{@code getPlayerMessage} / {@code getSenderMessage} 的共用实现。
     *
     * <p><b>为什么必须补 null 守卫</b>：这两个入口传进来的 {@code messageConfig} 来自
     * {@code configManager.getMessageConfig(lang)}，而该方法在 {@code configSnapshot == null}
     * 时回落的是 legacy 字段 —— 那两个字段在 {@code loadAllConfigs()} 之前（以及加载抛异常
     * 之后）**可以是 null**。旧实现第一行就调 {@link #resolvePrefix} → 直接
     * {@code messageConfig.getString(...)} → NPE 穿透到监听器/命令，玩家看不到任何提示，
     * 控制台只有一条无上下文的堆栈。</p>
     *
     * <p><b>为什么和 {@link #formatSafe} 同款而不是和 {@code getMessage} 同款</b>：
     * {@code getMessage} 的 {@code "Missing message: key"} 是硬编码英文，会漏进中文界面；
     * 而且它属于另一条调用链（默认语言 / 地图重载），改它会改变既有行为。这里刻意复用
     * {@link #formatSafe} 的约定：WARNING + 可见占位符 {@code &c[?key]}，玩家一眼能看到
     * 缺的是哪个键，而不是看到一个空白提示。</p>
     *
     * <p>键缺失分支也顺带补上 {@code (lang=...)}：本方法的语言是玩家自己选的，
     * 排查"为什么这个玩家缺键"时必须能从日志里看出来是哪个语言文件。</p>
     */
    private String formatMessage(FileConfiguration messageConfig, String lang,
                                  String key, String... placeholders) {
        // R14-F3：null 配置走与 formatSafe 完全一致的 fail-visible 路径（WARNING + [?key]）。
        if (messageConfig == null) {
            plugin.getLogger().warning("Message config unavailable while resolving key: " + key
                    + " (lang=" + lang + ")");
            return ChatColor.translateAlternateColorCodes('&', String.format(MISSING_KEY_PLACEHOLDER, key));
        }

        String prefix = resolvePrefix(messageConfig, lang);

        String message = messageConfig.getString(key);
        if (message == null) {
            // R14-F3：占位符统一为 formatSafe 的 &c[?key]，不再返回硬编码英文句子。
            plugin.getLogger().warning("Missing message key: " + key + " (lang=" + lang + ")");
            return ChatColor.translateAlternateColorCodes('&', String.format(MISSING_KEY_PLACEHOLDER, key));
        }

        message = message.replace("{prefix}", prefix);

        if (placeholders != null) {
            if (placeholders.length % 2 != 0) {
                plugin.getLogger().warning("Odd number of placeholder arguments for key: " + key
                        + " (length=" + placeholders.length + ")");
            }
            for (int i = 0; i < placeholders.length - 1; i += 2) {
                // R14-F3：null 元素不得进 String#replace（会 NPE 成 CharSequence）。与
                // formatSafe 同款跳过，其余占位符照常替换。
                if (placeholders[i] != null && placeholders[i + 1] != null) {
                    message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
                }
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

    private String resolveDefaultLang() {
        // R14-F3：原实现直接读原始字符串，"cn"/"ZH" 会原样传下去（进而拼出 prefix.cn /
        // 甚至在别的类里被判成英文）。语言归一化只在 ConfigManager 一处，这里只做转发。
        return configManager.getDefaultLanguage();
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