package com.letslearnenglish.minecraftplugin.config;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Configuration Manager - handles loading, saving, and hot-reloading of all config files.
 *
 * Managed configuration files:
 * - config.yml         Main configuration
 * - words/*.yml      Word bank files
 * - messages/*.yml     Multi-language message files
 */
public class ConfigManager {

    private static final int MESSAGE_CONFIG_VERSION = 26;

    /**
     * P0-2：合并递归深度上限。语言文件实际只有 2~3 层，这里留足余量；上限只防
     * 畸形/恶意深嵌套 YAML 在合并时栈溢出，命中即整支保留原样（不丢数据）。
     */
    private static final int MAX_MERGE_DEPTH = 64;

    /** P0-2：一次合并的统计量，仅用于日志（不打进用户文件）。 */
    private static final class MergeStats {
        /** 两边都有叶子值 → 保留用户取值 */
        int preserved;
        /** 用户缺失 → 从新版补齐 */
        int added;
        /** 用户有、新版没有 → 原样保留的废弃键（按当前层级计数） */
        int deprecated;
        /** 结构冲突 → 采用新版结构（旧值在 .bak） */
        int conflicts;
        /** 超过深度上限、整支跳过的分支数 */
        int truncated;
    }

    /**
     * Config keys the code actually relies on at runtime. Absence is logged as a WARNING on
     * startup (see {@link #validateRequiredKeys()}). This is a diagnostic list only — missing
     * keys still degrade gracefully to each call site's built-in default.
     */
    private static final String[] REQUIRED_CONFIG_KEYS = {
            // 子服总开关：缺 key 时回落 true（存量 config.yml 升级后不会突然失能），
            // 这里只做存在性诊断，提醒服主新键已可用。
            "server.enabled",
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

    /**
     * L13：{@link #getGuiMaterial(String, String, Material)} 的告警去重集合。
     *
     * <p>该方法在 <b>GUI 渲染热路径</b>上（每次打开菜单都会调用十几次），配错一个键若
     * 每次都打日志，服主会看到刷屏、真实告警反被淹没。故按
     * {@code gui-layout.<gui>.<item>.material} 的完整路径去重：<b>每个键只警告一次</b>。</p>
     *
     * <p>内存有界：键空间由配置文件本身决定（GUI 数量 × 每 GUI 的按钮数），是有限常量，
     * 不会随玩家数/运行时长增长。{@link #loadAllConfigs()}（含 /lea reload）时清空 ——
     * 配置改动后允许每个键再告警一次，既方便服主验证修复，也不会退化成刷屏。</p>
     */
    private final Set<String> warnedGuiMaterials = ConcurrentHashMap.newKeySet();

    private FileConfiguration mainConfig;
    private FileConfiguration messageConfig;
    private FileConfiguration messageConfigZh;
    private FileConfiguration messageConfigEn;

    public ConfigManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.configCache = new HashMap<>();
    }

    public void loadAllConfigs() {
        configCache.clear();
        // L13：reload 后允许每个 GUI material 键重新告警一次（见 warnedGuiMaterials javadoc）。
        warnedGuiMaterials.clear();
        plugin.saveDefaultConfig();
        mainConfig = plugin.getConfig();

        // 诚实性审查收口（新发现 A/B）：原此处 loadConfig("achievements.yml")——该文件
        // 已废弃（自带 deprecated 头注、AchievementManager 实际读 achievements-config.yml），
        // 死链读取还会把废弃文件释放到数据目录误导管理员。整链移除（字段/getter 同删）。

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

    /**
     * 加载（必要时升级）一份语言文件。
     *
     * <p><b>P0-2：升级 = 合并，绝不覆盖。</b>旧实现用 {@code saveResource(path, true)} 把
     * JAR 内新版资源硬写进数据目录，服主改过的所有文案（颜色码、占位符、措辞）会被瞬间
     * 冲掉。现在的语义是：</p>
     * <ul>
     *   <li>用户已有的键 → 保留用户取值（用户改过的文案永远优先）；</li>
     *   <li>用户缺失的键 → 从 JAR 内新版补齐；</li>
     *   <li>新版已删除的键 → 保留在用户文件里（不替服主做减法，只打 info 说明数量）；</li>
     *   <li>类型冲突（用户是字符串、新版是 section 等）→ 以新版结构为准，旧值仍在
     *       {@code .bak} 升级前快照里；</li>
     *   <li>顶层 {@code config-version} 最后写成 {@link #MESSAGE_CONFIG_VERSION}。</li>
     * </ul>
     *
     * <p>实现上先把 JAR 内资源读进内存（{@code getResource} + UTF-8 Reader，<b>不落盘</b>），
     * 与磁盘上的用户文件做递归合并，再整份写回。写回前做单槽 {@code .bak} 快照；写回走
     * "临时文件 + 原子替换"，避免进程中断留下半截 YAML。任何失败都降级为"保留旧文件 +
     * 旧内容继续用"，绝不阻断启动。</p>
     *
     * <p>幂等：成功写回后 {@code config-version} 已是新值，再次调用直接跳过合并。
     * {@code synchronized} 只为防止并发 reload 同时读写同一份文件。</p>
     */
    public synchronized FileConfiguration loadMessageConfig(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
            return cacheMessageConfig(path, file);
        }

        FileConfiguration existing = YamlConfiguration.loadConfiguration(file);

        // P0-2 护栏：文件里有真实内容、却一个键都解析不出来 = YAML 损坏（
        // YamlConfiguration.loadConfiguration 会吞掉解析异常返回空配置）。此时若继续走
        // "合并 + 写回"，等于拿默认值把坏文件覆盖掉——原文件只剩 .bak 一条命。所以这里
        // 一字不动，内存里用 JAR 内默认值兜底，让服务器先跑起来，由服主自己修。
        // 仅含注释的文件不算损坏（getKeys 为空但没有"内容行"），照常走合并。
        if (existing.getKeys(false).isEmpty() && looksLikeBrokenYaml(file)) {
            plugin.getLogger().severe("Failed to parse " + path
                    + " (invalid YAML?). The file was left untouched; bundled defaults are used in memory "
                    + "for this run. Fix the file and run /lea reload.");
            FileConfiguration fallback = readBundledConfig(path);
            if (fallback != null) {
                configCache.put(path, fallback);
                return fallback;
            }
            configCache.put(path, existing);
            return existing;
        }

        int version = existing.getInt("config-version", 0);
        if (version < MESSAGE_CONFIG_VERSION) {
            FileConfiguration migrated = migrateMessageConfig(file, path, existing, version);
            if (migrated != null) {
                configCache.put(path, migrated);
                return migrated;
            }
            // 迁移被降级（JAR 资源缺失 / 写回失败）：原文件未动，缓存继续用旧内容
        }

        return cacheMessageConfig(path, file);
    }

    /** 读盘并放入缓存（与旧实现的收尾行为一致）。 */
    private FileConfiguration cacheMessageConfig(String path, File file) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        configCache.put(path, config);
        return config;
    }

    /**
     * 判断"解析出 0 个键"的文件是真损坏，还是仅仅是注释/空行。
     * 任何一行既非空、也非 {@code #} 注释 → 说明有真实内容却没被解析出来，按损坏处理。
     */
    private static boolean looksLikeBrokenYaml(File file) {
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return true; // 读不了就当损坏：宁可走只读兜底，也不冒险覆盖
        }
    }

    /**
     * P0-2：把旧版语言文件就地合并到 {@link #MESSAGE_CONFIG_VERSION}。
     *
     * @return 写回后重新读盘的配置；{@code null} 表示已降级（原文件保持原样，调用方继续用旧配置）
     */
    private FileConfiguration migrateMessageConfig(File file, String path,
                                                   FileConfiguration existing, int version) {
        YamlConfiguration bundled = readBundledConfig(path);
        if (bundled == null) {
            plugin.getLogger().warning("Cannot upgrade " + path + " (v" + version + " -> v"
                    + MESSAGE_CONFIG_VERSION + "): bundled resource missing or unreadable; file kept as is.");
            return null;
        }

        Map<String, Object> userTree = toTree(existing);
        Map<String, Object> bundledTree = toTree(bundled);
        // 版本号是升级元数据，单独在最后写入，不参与"保留用户值"的统计与判断
        bundledTree.remove("config-version");
        if (bundledTree.isEmpty()) {
            plugin.getLogger().warning("Bundled " + path
                    + " yields no keys; skipping upgrade to avoid replacing the user's file with nothing.");
            return null;
        }

        MergeStats stats = new MergeStats();
        mergeMissing(userTree, bundledTree, "", 0, stats);
        userTree.put("config-version", MESSAGE_CONFIG_VERSION);

        // 写回前快照：单槽 .bak = "升级前最后一份"（与 backupDatabase 的滚存策略区分）。
        // REPLACE_EXISTING 保证单槽语义——裸 copy 在第二次升级会因目标已存在而抛
        // FileAlreadyExistsException，反而留下过期首份。备份失败不阻断升级（仅告警，
        // 防新装环境权限问题卡启动），但会在日志里明说。
        String backupSuffix = "";
        try {
            Files.copy(file.toPath(), Path.of(file.getAbsolutePath() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
            backupSuffix = " (pre-upgrade snapshot: " + path + ".bak)";
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to back up " + path + " before merge-upgrade: " + e.getMessage());
        }

        YamlConfiguration merged = new YamlConfiguration();
        writeTree(merged, userTree);
        if (!writeAtomically(merged, file)) {
            plugin.getLogger().severe("Failed to write merged " + path
                    + "; keeping the original file and its old contents in memory.");
            return null;
        }

        plugin.getLogger().info("Merged " + path + " (v" + version + " -> v" + MESSAGE_CONFIG_VERSION + "): "
                + stats.preserved + " user value(s) kept, " + stats.added + " new key(s) added, "
                + stats.deprecated + " obsolete key(s) kept, " + stats.conflicts + " type conflict(s) resolved"
                + (stats.truncated > 0 ? ", " + stats.truncated + " over-deep branch(es) kept as-is" : "")
                + backupSuffix);
        return YamlConfiguration.loadConfiguration(file);
    }

    /** 从 JAR 读新版资源进内存（UTF-8，显式指定编码），绝不落盘。 */
    private YamlConfiguration readBundledConfig(String path) {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read bundled " + path + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 递归合并：{@code bundled} 中用户缺失的键补进 {@code user}，用户已有的键一律不动。
     *
     * <p>为什么保留用户值：语言文件是服主的"文案资产"，任何一次插件升级都不该让他们的
     * 措辞、颜色码、占位符悄悄回退成默认值——用户显式改过的内容优先级高于 JAR 默认值。</p>
     *
     * <p>为什么保留"废弃键"：本方法只遍历 {@code bundled}，从不删除 {@code user} 里多出来的
     * 键。插件不再读取的键对运行无害，但可能是服主自己加的备注/备用文案（甚至被其他插件
     * PlaceholderAPI 引用），替用户做减法就是数据丢失。</p>
     *
     * @param prefix 当前路径前缀（仅用于日志定位）
     * @param depth  当前递归深度（防御异常深或畸形的 YAML 结构导致栈溢出）
     */
    private void mergeMissing(Map<String, Object> user, Map<String, Object> bundled,
                              String prefix, int depth, MergeStats stats) {
        if (depth > MAX_MERGE_DEPTH) {
            stats.truncated++;
            return;
        }
        for (Map.Entry<String, Object> entry : bundled.entrySet()) {
            String key = entry.getKey();
            String childPath = prefix.isEmpty() ? key : prefix + "." + key;
            Object bundledValue = entry.getValue();
            Object userValue = user.get(key);
            if (userValue == null) {
                // 用户缺失（或显式留空）→ 用新版补齐
                user.put(key, deepCopy(bundledValue));
                stats.added++;
                continue;
            }
            boolean userIsSection = userValue instanceof Map;
            boolean bundledIsSection = bundledValue instanceof Map;
            if (userIsSection && bundledIsSection) {
                // toTree() 产出的 Map 键一定是 String，强转安全
                @SuppressWarnings("unchecked")
                Map<String, Object> userChild = (Map<String, Object>) userValue;
                @SuppressWarnings("unchecked")
                Map<String, Object> bundledChild = (Map<String, Object>) bundledValue;
                mergeMissing(userChild, bundledChild, childPath, depth + 1, stats);
            } else if (userIsSection != bundledIsSection) {
                // 类型冲突（用户把 section 折叠成字符串，或把字符串展开成 section）：
                // 以新版结构为准，否则后续 getString / getConfigurationSection 会错位；
                // 用户旧值仍完整躺在升级前的 .bak 快照里，可人工找回。
                plugin.getLogger().info("[Config Merge] Type conflict at '" + childPath
                        + "': adopting bundled structure (your previous value is in the .bak snapshot).");
                user.put(key, deepCopy(bundledValue));
                stats.conflicts++;
            } else {
                // 两边都是叶子值 → 保留用户取值（含被改过的文案、& 颜色码、{占位符}）
                stats.preserved++;
            }
        }
        // 用户有、新版没有的键：保留（见方法注释），仅计数用于日志
        for (String key : user.keySet()) {
            if (!bundled.containsKey(key) && !"config-version".equals(key)) {
                stats.deprecated++;
            }
        }
    }

    /**
     * 把 ConfigurationSection 展开成纯 Map 树。
     *
     * <p>刻意用 {@code getKeys(false)} + {@code get(key)} 逐层递归，而不是
     * {@code getValues(true)}：后者返回"扁平化的点号路径 → 值"（如
     * {@code "general.no-permission" → "..."}），把这种扁平结果 set 回去时每一步都要靠
     * {@code .} 路径拆分去重建结构，一旦某个键名本身含点号就会错位，而且 section 与叶子值
     * 混在同一个扁平 Map 里很难区分。逐层版本拿到的结构与层级是准确的。</p>
     */
    private static Map<String, Object> toTree(ConfigurationSection section) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            tree.put(key, value instanceof ConfigurationSection child ? toTree(child) : value);
        }
        return tree;
    }

    /** 把 Map 树写进一份空配置；嵌套 Map 走 createSection，避免把 section 当成标量值写进去。 */
    private void writeTree(ConfigurationSection target, Map<String, Object> tree) {
        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isEmpty() || value == null) {
                // null 在 YamlConfiguration 里等价于"删键"，这里跳过而不是写出歧义结构
                continue;
            }
            if (key.indexOf('.') >= 0) {
                // Bukkit 的 set/createSection 把 '.' 当路径分隔符，没有字面量点号键的 API。
                // 值不会丢（仍可用 a.b 路径读到），但 "a.b: x" 会落成嵌套结构——明确告警，
                // 免得服主以为文件被写坏。内置语言文件不含这种键，正常情况不会触发。
                plugin.getLogger().warning("[Config Merge] Key '" + key + "' contains a '.'; it will be "
                        + "rewritten as a nested section because Bukkit has no literal dotted-key API.");
            }
            if (value instanceof Map<?, ?> rawMap) {
                // toTree/deepCopy 产出的嵌套 Map 键一定是 String；此处统一按字符串键重铸，
                // 否则 Map<?,?> 无法直接传给接受 Map<String,Object> 的递归调用。
                Map<String, Object> map = new LinkedHashMap<>(rawMap.size());
                for (Map.Entry<?, ?> e : rawMap.entrySet()) {
                    map.put(String.valueOf(e.getKey()), e.getValue());
                }
                ConfigurationSection child = target.createSection(key);
                writeTree(child, map);
            } else {
                target.set(key, value);
            }
        }
    }

    /** 深拷贝 JAR 侧的值，避免合并结果与 bundled 配置共享可变 List/Map。 */
    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value; // String / Number / Boolean 不可变，直接复用
    }

    /**
     * P0-2：先写同目录临时文件再原子替换，避免进程中断/磁盘满时留下半截语言文件
     * （半截 YAML 会让下次启动解析失败，那才是真正的数据灾难）。
     *
     * @return 是否写成功；失败时目标文件保持原样
     */
    private boolean writeAtomically(YamlConfiguration config, File target) {
        Path tmp = Path.of(target.getAbsolutePath() + ".tmp");
        try {
            Files.writeString(tmp, config.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write temporary merged config " + tmp, e);
            deleteQuietly(tmp);
            return false;
        }
        try {
            Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            // 某些文件系统（网络盘 / 容器挂载）不支持原子 rename：退化为普通替换。
            // 此时临时文件仍在，下面这一步才是真正的"移动"。
            try {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException fallbackFailure) {
                plugin.getLogger().log(Level.SEVERE, "Failed to replace " + target, fallbackFailure);
                deleteQuietly(tmp);
                return false;
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to replace " + target, e);
            deleteQuietly(tmp);
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // 临时文件残留不影响数据安全，下次升级会覆盖同名 .tmp
        }
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

    public FileConfiguration getMessageConfig() {
        return messageConfig;
    }

    public FileConfiguration getMessageConfig(String lang) {
        return "zh".equals(lang) ? messageConfigZh : messageConfigEn;
    }

    /**
     * 本子服是否启用本插件：{@code server.enabled}（默认 true）。点读式，reload 即时生效
     * （但「关 → 开 / 开 → 关」的完整生效仍需重载服务器，见 config.yml 注释）。
     *
     * <p>语义：Velocity / BungeeCord 多服网络里「这个 Paper 子服要不要提供英语学习功能」。
     * false 时由 {@code onEnable} 短路：不构造任何 Manager、不注册监听器、不排程任务、
     * 不注册代理通道；仅保留 configManager / messageUtil / databaseManager 与命令注册
     * （两条命令的 executor 会按本值拒绝，{@code /lea reload} 例外放行）。
     *
     * <p>与 {@link #isWordSystemEnabled()} 等模块门的区别：那些是「功能级」开关（入口隐藏、
     * 事件静默、数据保留），本开关是「整服级」开关（连监听器与任务都不装）。单服部署
     * 也可以用它一键静默，名字刻意用 {@code server} 而非 {@code proxy}——它管的是本子服，
     * 与是否处在代理网络无关。
     */
    public boolean isServerEnabled() {
        return mainConfig.getBoolean("server.enabled", true);
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

    /**
     * 读 {@code gui-layout.<gui>.<item>.material}，缺失时回落 {@code defaultMaterial}。
     *
     * <p>L13 修复：<b>空气方块一律拒绝</b>。{@code AIR} / {@code CAVE_AIR} /
     * {@code VOID_AIR} 都是合法 {@link Material}（{@code valueOf} 会成功），但把它们塞进
     * {@code Inventory#setItem} 等价于该槽为空 —— 服主只要把某个按钮配成 {@code AIR}，
     * 就会得到一个"看不见但还能点"（或彻底消失）的按钮，且旧实现完全静默、无从排查。
     * 现在拒绝并回落默认值，同时打 WARNING 指明完整配置路径。</p>
     *
     * <p>同样地，非法名称（{@code valueOf} 抛 {@code IllegalArgumentException}）旧实现
     * 也是静默吞掉 —— 同一个"配错了没人知道"的问题，一并补 WARNING。</p>
     *
     * <p><b>防刷屏</b>：本方法处于 GUI 渲染热路径，用 {@link #warnedGuiMaterials} 按
     * 完整配置路径去重，每个键只警告一次（{@link #loadAllConfigs()} 时重置）。
     * <b>为什么判定用显式常量比较而不是 {@code Material#isAir()}</b>：{@code isAir()} 会经
     * {@code asBlockType()} 触碰 {@code Registry}（Bukkit 的服务端注册表），纯 JVM 单测里
     * 直接抛 {@code No RegistryAccess implementation found}，等于把一段可测逻辑焊死在服务端
     * 运行时上。Bukkit 的 {@link Material} 枚举里空气方块只有这三个常量（1.21 仍如此），
     * 显式比较既等价又可在纯 JVM 下被测试钉住。</p>
     *
     * @param guiName         {@code gui-layout} 下的一级节点名（如 {@code main-menu}）
     * @param itemKey         GUI 内按钮的键名（如 {@code back-button}）
     * @param defaultMaterial 配置缺失/非法/为空气时的回落值
     * @return 可安全放进 GUI 的 Material，永不为空气方块
     */
    public Material getGuiMaterial(String guiName, String itemKey, Material defaultMaterial) {
        String path = "gui-layout." + guiName + "." + itemKey + ".material";
        String matName = mainConfig.getString(path);
        if (matName != null) {
            Material resolved;
            try {
                resolved = Material.valueOf(matName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // L13：非法名称不再静默 —— 旧实现直接吞掉异常，服主配错完全无从察觉。
                warnGuiMaterialOnce(path, "is not a valid Material name", matName, defaultMaterial);
                return defaultMaterial;
            }
            if (isAirMaterial(resolved)) {
                // L13：空气方块会让按钮不可见（等于清空槽位），视为配置错误。
                warnGuiMaterialOnce(path, "is an air block (would make the button invisible)",
                        matName, defaultMaterial);
                return defaultMaterial;
            }
            return resolved;
        }
        return defaultMaterial;
    }

    /**
     * L13：是否为"空气方块"（放进入即等于空槽）。
     *
     * <p>刻意不用 {@code Material#isAir()} —— 它经 {@code asBlockType()} 依赖
     * {@code Registry}，纯 JVM 单测无法初始化（见 {@link #getGuiMaterial} 的 javadoc）。
     * Bukkit 的 Material 枚举中属于空气的只有这三个常量。</p>
     *
     * @param material 待判定材质（调用方保证非 null）
     * @return true = 空气方块，不能用作 GUI 按钮图标
     */
    private static boolean isAirMaterial(Material material) {
        return material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }

    /**
     * L13：GUI material 配置告警（按完整配置路径每键一次，防 GUI 渲染路径刷屏）。
     *
     * @param path     完整配置路径 {@code gui-layout.<gui>.<item>.material}
     * @param problem  问题描述（拼进日志的一句话）
     * @param rawValue 配置里的原始取值（便于服主定位）
     * @param fallback 实际回落的 Material
     */
    private void warnGuiMaterialOnce(String path, String problem, String rawValue, Material fallback) {
        if (!warnedGuiMaterials.add(path)) {
            return; // 该键本次启动/reload 内已警告过
        }
        plugin.getLogger().warning("[GUI Layout] " + path + "=\"" + rawValue + "\" " + problem
                + "; falling back to " + fallback + ".");
    }
}