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

    private static final int MESSAGE_CONFIG_VERSION = 14;

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
            "gui.menu-size",
            // Phase 3 · MC-2：新增模块门键（缺 key 仅启动审计告警；代码回落各自默认 true）
            "onboarding.enabled",
            "duel.enabled",
            "checkin.enabled",
            // Wave 4 · 4A tester I-2：班级系统门键存在性诊断（缺 key 回落默认 false）
            "class-system.enabled",
            // Phase 3 · MC-5：挑战奖励键存在性（缺 key / 非法由 getChallengeReward 回落
            // 枚举默认 30/50/25/40 并告警；本名单只做存在性诊断，不影响行为）
            "daily-challenges.rewards.answer_10",
            "daily-challenges.rewards.session_3",
            "daily-challenges.rewards.combo_5",
            "daily-challenges.rewards.ai_chat_5"
    };

    /**
     * Phase 3 · MC-5：行为挑战奖励键（数值正值审计用；存在性已含于
     * {@link #REQUIRED_CONFIG_KEYS}，此处只补"非法值"诊断——运行时不依赖本检查，
     * {@code DailyChallengeManager.getChallengeReward} 自会回落枚举默认值）。
     */
    private static final String[] CHALLENGE_REWARD_KEYS = {
            "daily-challenges.rewards.answer_10",
            "daily-challenges.rewards.session_3",
            "daily-challenges.rewards.combo_5",
            "daily-challenges.rewards.ai_chat_5"
    };

    /**
     * Message keys that must exist in every language file. Absence (in zh or en) is logged as a
     * WARNING on startup. These are keys whose callers would otherwise show a visible fallback or,
     * in a few legacy call sites, risk an NPE on a null {@code getString}.
     *
     * <p>R-86o：审计名单只保留"活键"（有真实调用点、缺了会明显伤到玩家核心流程）。
     * 已移除经核实的死键 {@code word.correct}/{@code word.incorrect}（判定文案实际走
     * {@code word-learning-gui.correct/incorrect}）。
     */
    private static final String[] REQUIRED_MESSAGE_KEYS = {
            "prefix",
            "general.no-permission",
            "general.reload-success",
            "word.session-start",
            "word-learning-gui.session-ended",
            "dialogue.daily-limit-reached",
            "dialogue.proxy-achievement",
            "daily-challenge.rollover-voided",
            "duel.cancelled-by-gm",
            "duel.opponent-timeout",
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

        // R-55l：缺 key 回落统一为 zh（与 yml 默认 "zh" + getPlayerLanguage 的 zh 回落对齐）。
        String language = mainConfig.getString("general.language", "zh");
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
            // Phase 3 · MC-5：挑战奖励数值正值检查（只告警不改行为——运行时
            // DailyChallengeManager.getChallengeReward 自行回落枚举默认值）。
            // 注意不能用 isDouble：YAML 的 30 装箱为 Integer，isDouble 会误判。
            for (String key : CHALLENGE_REWARD_KEYS) {
                Object raw = mainConfig.get(key);
                if (raw == null) continue; // 缺失已由上面存在性检查覆盖
                if (!(raw instanceof Number number) || number.doubleValue() <= 0
                        || !Double.isFinite(number.doubleValue())) {
                    plugin.getLogger().warning("[Config Audit] Invalid config value for " + key
                            + ": " + raw + " (must be a positive number; falls back to the built-in default)");
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

    // ------------------------------------------------------------------
    //  模块门（Phase 3 · MC-2）：集中式开关读取，全部点读 mainConfig（不缓存），
    //  /lea reload 即时生效。"关"的统一三层语义 = 入口隐藏 / 事件静默 / 数据保留：
    //  关闭只停"入口与新增写入"，绝不删除既有数据；重新开启立即恢复。
    // ------------------------------------------------------------------

    /**
     * 单词学习模块总门：{@code word-system.enabled}（默认 true）。点读式，reload 即时生效。
     * 三层语义：入口隐藏（主菜单按钮/相关 GUI 不可达） / 事件静默（入口 no-op、不写新数据） /
     * 数据保留（关闭不删数据，重开立即恢复）。
     */
    public boolean isWordSystemEnabled() {
        return mainConfig.getBoolean("word-system.enabled", true);
    }

    /**
     * AI 情景对话模块门：{@code ai-chat.enabled}（默认 true）。点读式，reload 即时生效。
     * 三层语义：入口隐藏（主菜单按钮/场景选择不可达） / 事件静默（不接新对话、进行中对话自动退出） /
     * 数据保留（历史对话与场景数据保留，/le history 与场景管理照常）。
     */
    public boolean isAIChatEnabled() {
        return mainConfig.getBoolean("ai-chat.enabled", true);
    }

    /**
     * 单词对决模块门：{@code duel.enabled}（Phase 3 新增键，默认 true）。点读式，reload 即时生效。
     * 三层语义：入口隐藏（主菜单按钮隐藏、发起/接受拒绝） / 事件静默（新邀请不受理） /
     * 数据保留（决斗零持久化）。进行中的对局自然收尾——quit/gui 与 /lea duel cancel 的放行属调用点约定。
     */
    public boolean isDuelEnabled() {
        return mainConfig.getBoolean("duel.enabled", true);
    }

    /**
     * 每日签到模块门：{@code checkin.enabled}（Phase 3 新增键，默认 true）。点读式，reload 即时生效。
     * 三层语义：入口隐藏（主菜单按钮隐藏、/le checkin 拒绝） / 事件静默（不记新签到） /
     * 数据保留（streak 记录保留；停用期间连续签到自然断签属预期语义）。
     */
    public boolean isCheckinEnabled() {
        return mainConfig.getBoolean("checkin.enabled", true);
    }

    /**
     * 每日挑战模块门：{@code daily-challenges.enabled}（默认 true）。点读式，reload 即时生效。
     * 三层语义：入口隐藏（主菜单按钮/挑战 GUI 不可达、/le challenge 拒绝） /
     * 事件静默（不分配、不更新进度、不结算、不可领取） / 数据保留（既有挑战行保留，重开后继续）。
     */
    public boolean isDailyChallengesEnabled() {
        return mainConfig.getBoolean("daily-challenges.enabled", true);
    }

    /**
     * 进度系统伞开关：{@code progress-system.enabled}（默认 true）。点读式，reload 即时生效。
     * false 时 {@link #isLeaderboardEnabled()} / {@link #isAchievementsEnabled()} /
     * {@link #isRewardsEnabled()} 同时为 false（内部 AND）。
     * 三层语义：入口隐藏 / 事件静默 / 数据保留——学习分数与统计继续累计（数据保全），
     * 个人主页展示保留（停"伞"不停展示）。
     */
    public boolean isProgressSystemEnabled() {
        return mainConfig.getBoolean("progress-system.enabled", true);
    }

    /**
     * 排行榜门：{@code progress-system.enabled && progress-system.leaderboard.enabled}
     * （默认 true）。点读式，reload 即时生效。
     * 三层语义：入口隐藏（/le leaderboard、榜单 GUI、广场三榜模式按钮隐藏） /
     * 事件静默（不查库） / 数据保留（内存榜继续更新作"热备"，重开即时恢复排名）。
     */
    public boolean isLeaderboardEnabled() {
        return isProgressSystemEnabled()
                && mainConfig.getBoolean("progress-system.leaderboard.enabled", true);
    }

    /**
     * 成就系统门：{@code progress-system.enabled && progress-system.achievements.enabled}
     * （默认 true）。点读式，reload 即时生效。
     * 三层语义：入口隐藏（个人主页成就墙按钮隐藏） /
     * 事件静默（tryUnlock 顶部 no-op，无解锁提示/音效/跨服广播） / 数据保留（已解锁记录保留）。
     */
    public boolean isAchievementsEnabled() {
        return isProgressSystemEnabled()
                && mainConfig.getBoolean("progress-system.achievements.enabled", true);
    }

    /**
     * 奖励系统门：{@code progress-system.enabled && progress-system.rewards.enabled}
     * （默认 true）。点读式，reload 即时生效。等效金币总闸
     * （{@code RewardEngine.isMoneyEnabled = 本门 && types.money.enabled}）。
     * 三层语义：入口（发币点）静默 / 数据保留（答题、签到 streak 照常推进，记录不删）。
     */
    public boolean isRewardsEnabled() {
        return isProgressSystemEnabled()
                && mainConfig.getBoolean("progress-system.rewards.enabled", true);
    }

    /**
     * 广场社交模块门：{@code social.enabled}（默认 true；该键随广场社交（Wave 2 域 4）落地）。
     * 点读式，reload 即时生效。
     * 三层语义：入口隐藏 / 事件静默（动态不产生、鼓励无效、进服摘要不发） / 数据保留。
     */
    public boolean isSocialEnabled() {
        return mainConfig.getBoolean("social.enabled", true);
    }

    /**
     * 新手引导门：{@code onboarding.enabled}（默认 true；键随新手引导（Wave 2 域 1）落地）。
     * 点读式，reload 即时生效，四处门槛同源。
     * 三层语义：入口隐藏（不给引导书、拦截不生效） / 事件静默（无任何提醒） / 数据保留。
     */
    public boolean isOnboardingEnabled() {
        return mainConfig.getBoolean("onboarding.enabled", true);
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
}