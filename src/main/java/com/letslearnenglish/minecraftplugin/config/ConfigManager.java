package com.letslearnenglish.minecraftplugin.config;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.util.AtomicFileWriter;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.logging.Logger;

/**
 * Configuration Manager - handles loading, saving, and hot-reloading of all config files.
 *
 * Managed configuration files:
 * - config.yml         Main configuration
 * - words/*.yml      Word bank files
 * - messages/*.yml     Multi-language message files
 */
public class ConfigManager {

    /**
     * {@code messages/*.yml} 的<b>迁移闸门版本</b>（不是插件版本）。
     *
     * <p>语义：磁盘上用户的语言文件 {@code config-version < MESSAGE_CONFIG_VERSION} 时，
     * {@link #loadMessageConfig(String)} 才会调 {@link #migrateMessageConfig} 把 JAR 内新增的
     * 键补进用户文件。等号意味着"已是最新，跳过合并"。</p>
     *
     * <p><b>R31 修复的真缺陷（存量服必现、全新安装不可见）</b>：本常量与
     * {@code messages/*.yml} 头部的 {@code config-version} 都停在 26，而工作树相较已发布的
     * 1.0.7 新增了 75 个叶子键（711 → 786）。于是 {@code version < MESSAGE_CONFIG_VERSION}
     * 恒为 {@code false}（{@code 26 < 26}），迁移永不触发，<b>跑过 1.0.7 的服升级后语言文件
     * 仍是 711 个键</b>，那 75 个新键全部缺失，玩家在对应场景看到 {@code [?key]} 字面量。
     * 全新安装从 JAR 拷一份新 yml，因此单测（全新临时目录）跑不出这条。</p>
     *
     * <p><b>不变量（由 {@code I18nVersionGateTest} 钉住）</b>：本常量必须
     * <b>严格大于</b>任何曾经发布过的语言文件 {@code config-version}，且必须<b>等于</b>
     * 当前 JAR 内 {@code messages/zh.yml} 与 {@code messages/en.yml} 头部的
     * {@code config-version}，并且与当前叶子键集合指纹匹配。三条缺一不可：
     * 少了"大于已发布版本"，迁移不触发（就是本轮缺陷）；少了"等于 JAR 内头部"，用户文件
     * 迁移后写回的版本号与 JAR 资源不一致，下次升级会重复合并；少了"键集合指纹"，
     * 改了 yml 却忘了 bump 版本号时无人发现。</p>
     */
    private static final int MESSAGE_CONFIG_VERSION = 28;

    /** 受支持语言的两个 canonical 取值（也是 {@code messages/*.yml} 的文件名主干）。 */
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";

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

    // C1：热重载在主线程写、PAPI 可能异线程读，无同步原语 ⇒ volatile。
    private volatile FileConfiguration mainConfig;
    private volatile FileConfiguration messageConfig;
    private volatile FileConfiguration messageConfigZh;
    private volatile FileConfiguration messageConfigEn;

    /**
     * C2（round4 重修）：四份配置的<b>原子发布单元</b>。
     *
     * <p>原状：4 条相邻 {@code volatile} 写<b>不是</b>原子发布 —— {@code messageConfig} 写在
     * {@code mainConfig} 之前，任何在两者之间读取的线程会看到「新 messageConfig + 旧 mainConfig」
     * 的跨代配对（实测 3-5 次 / 30 代，最长连续 6.8-9.4ms）。该配对真实落在
     * {@code MessageUtil.getMessage}（:52 读 messageConfig、:58 读 mainConfig）上，
     * 并被 PAPI 的 30s TTL 把错误文案固化半分钟。
     *
     * <p>修法：一次 {@code volatile} 引用写完成"四字段同时换代"—— 读者要么整组看到旧代、
     * 要么整组看到新代，不存在中间态。{@link #getMainConfig()} / {@link #getMessageConfig()}
     * 改读本快照（测试反射注入 legacy 字段时快照为 null，仍回落 legacy 字段，行为不变）。
     */
    private volatile ConfigSnapshot configSnapshot;

    /** 同代四份配置的只读组合。字段名与 legacy 字段一一对应，便于逐点核对。 */
    public record ConfigSnapshot(FileConfiguration mainConfig, FileConfiguration messageConfig,
                                 FileConfiguration messageConfigZh, FileConfiguration messageConfigEn) { }

    public ConfigManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.configCache = new HashMap<>();
    }

    public void loadAllConfigs() {
        try {
            loadAllConfigsInternal();
        } catch (RuntimeException e) {
            // 锐评波降为 LOW：不做回滚/重构，只补失败可见性，异常照原样抛出（行为不变）。
            plugin.getLogger().log(Level.SEVERE,
                    "Configuration loading failed; config state is partially initialized", e);
            throw e;
        }
    }

    private void loadAllConfigsInternal() {
        configCache.clear();
        // L13：reload 后允许每个 GUI material 键重新告警一次（见 warnedGuiMaterials javadoc）。
        warnedGuiMaterials.clear();
        plugin.saveDefaultConfig();
        // C2：先全部算进局部变量，末尾集中发布。原分步赋值被 loadMessageConfig 的磁盘 IO
        // 隔开，异线程读者可见「新 mainConfig + 旧 messageConfig」的跨代混合视图。
        FileConfiguration newMain = plugin.getConfig();

        // 诚实性审查收口（新发现 A/B）：原此处 loadConfig("achievements.yml")——该文件
        // 已废弃（自带 deprecated 头注、AchievementManager 实际读 achievements-config.yml），
        // 死链读取还会把废弃文件释放到数据目录误导管理员。整链移除（字段/getter 同删）。

        createDirectoryIfNotExists("words");

        saveResourceIfNotExists("words/beginner.yml");
        saveResourceIfNotExists("words/intermediate.yml");
        saveResourceIfNotExists("words/advanced.yml");
        // 内置词库有实质变更时，老服的数据目录里还是旧文件（saveResourceIfNotExists 只补缺失）。
        // 这里按版本号做一次「备份 + 替换」，否则新词库对所有存量服主完全不可见。
        // 必须在 saveResourceIfNotExists 之后：先补齐缺失文件，再判断要不要升级。
        upgradeWordBanksIfNeeded();

        FileConfiguration newZh = loadMessageConfig("messages/zh.yml");
        FileConfiguration newEn = loadMessageConfig("messages/en.yml");

        // R-55l：缺 key 回落统一为 zh（与 yml 默认 "zh" + getPlayerLanguage 的 zh 回落对齐）。
        //
        // R14-F3（FIX 2）：原实现 `"zh".equals(language)` 把**任何**非字面 "zh" 都判成英文，
        // 于是 `general.language: cn`（中文用户最常见的写法）会让全服默认语言静默变成英文：
        // active 消息文件、resolveDefaultLang、无个人覆盖的玩家、PAPI 缓存键全部跟着走 en，
        // 而日志里一个字都没有。现在先归一化，再对**原始值**做合法性判定并点名告警。
        //
        // 判定分两档，因为"能用"和"该写"是两件事：
        //   · 无法识别（"fr"/"english"/"中文"）→ WARNING，回落 zh；
        //   · 可识别但不是规范写法（"cn"/"ZH"/"zh_CN"/"zh-CN"）→ 仍按 zh 生效，但 WARNING 一次，
        //     提示 config.yml 里文档化的取值只有 zh/en。第二档是刻意的：`cn` 是**能跑**的
        //     （这正是 FIX 2 的修复点），但服主写下的值与文档不符时应当能看见，
        //     否则下次改配置会以为自己写错了却没有任何反馈。
        String rawLanguage = newMain.getString("general.language", LANG_ZH);
        String canonicalRaw = canonicalLanguage(rawLanguage);
        if (!isRecognizedLanguage(rawLanguage)) {
            // 未知值回落 zh：与全仓"取不到就 zh"的口径一致，也让 zh 环境的服主看到中文
            // 而不是突然全英文（旧实现在这里会整服切英文）。
            plugin.getLogger().warning("[Config Audit] Invalid value for general.language: '"
                    + rawLanguage + "' — accepted values are 'zh' and 'en'. Falling back to '" + canonicalRaw + "'.");
        } else if (!canonicalRaw.equals(rawLanguage)) {
            // 能跑但不是规范写法：不改变行为，只让服主知道文档里的取值长什么样。
            plugin.getLogger().warning("[Config Audit] Non-canonical value for general.language: '"
                    + rawLanguage + "' (resolved to '" + canonicalRaw + "'). The documented values are"
                    + " 'zh' and 'en'; this spelling works but is not what config.yml advertises.");
        }
        String language = canonicalRaw;
        FileConfiguration newActive = LANG_EN.equals(language) ? newEn : newZh;

        // C2（round4 重修）：<b>一条</b> volatile 引用写完成四份配置的原子换代。
        // 原实现是 4 条相邻独立写，读者在两条之间会看到 msgGen > mainGen 的跨代配对
        // （实测 6.8-9.4ms 窗口，落在 MessageUtil:52/58 的读取对上并被 PAPI 30s TTL 固化）。
        //
        // slice-02-concurrency-F-02-C2（round10 批判专家复核，ALREADY_FIXED）：
        // 「4 条相邻 volatile 写之间的 6.8-9.4ms 窗口」**已结构性消除** —— 现在只有这一条
        // 引用写，两写之间不存在间隔。
        //
        // 精确边界（原注释的「被消除而非缩小」是过度声明）：**单次 getter 调用内部**是原子的
        // （一次 volatile read 拿到整组）；但**两次独立 getter 调用之间**（例如 MessageUtil
        // 先 getMessageConfig 再 getMainConfig）仍可能跨越一次 reload —— 那个窗口是调用方
        // 造成的，不是发布逻辑造成的，量级在亚毫秒（两条 getter 之间），与原来的 6.8-9.4ms
        // 差三个数量级。legacy 字段直读点（测试反射注入路径）同理。
        //
        // 若要连那两个 getter 之间的窗口也收口，需要调用方一次取快照再下传（见 round10
        // expert2 的 B 方案）；红队评估收益 < 成本，故本轮不做。
        configSnapshot = new ConfigSnapshot(newMain, newActive, newZh, newEn);
        // legacy 字段保持同步：测试经反射注入 mainConfig 时 getMainConfig 要能看到；
        // 字段仍在（不改公共形状），但生产读路径已优先走快照。
        messageConfigZh = newZh;
        messageConfigEn = newEn;
        messageConfig = newActive;
        mainConfig = newMain;

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
        // E7（R9 补齐）：损坏检测。YamlConfiguration.loadConfiguration 会吞掉解析异常并
        // 返回<b>空配置</b>，所以一个半截/语法错误的 words/advanced.yml 会被当成「这个词库
        // 没有词」静默传下去 —— 与 messages/*.yml 的处理不对称（那边有 looksLikeBrokenYaml
        // 检测 + SEVERE 日志 + JAR 默认值兜底，见本类 :299-303 的注释）。
        // 这里只做可观测性：不替换内容、不改调用方语义，避免把「空文件」与「损坏文件」混为一谈。
        if (file.isFile() && config.getKeys(false).isEmpty() && file.length() > 0) {
            plugin.getLogger().severe("Config file appears to be corrupted or empty after parsing: "
                    + path + " (" + file.length() + " bytes on disk but 0 keys parsed). "
                    + "YamlConfiguration swallows parse errors and returns an empty config — "
                    + "check the file for syntax errors. It will be treated as empty.");
        }
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
     * <p><b>R-11：参数类型从 {@link YamlConfiguration} 放宽到 {@link FileConfiguration}，
     * 让 {@link #saveConfig} 复用本方法而不是复制第二份实现。</b>本方法只用到
     * {@code saveToString()}，它是 {@link FileConfiguration} 上的抽象方法；而 Bukkit 的
     * {@code FileConfiguration.save(File)} 实现正是「建父目录 + {@code saveToString()} +
     * UTF-8 写出」—— 因此本方法写出的字节与 {@code config.save(file)} 逐字节相同，
     * 放宽类型不改变任何既有调用点（{@code migrateMessageConfig}）的落盘内容。
     *
     * <p>注意本方法<b>不建父目录</b>（{@code Files.writeString} 不会，
     * {@code FileConfiguration.save} 会）。调用方若依赖旧行为，必须自己保证目录存在 ——
     * {@link #saveConfig} 就是这么做的。
     *
     * <p><b>R-11（第三批）：实现已提到 {@link AtomicFileWriter}</b>。同一份「写 .tmp →
     * {@code ATOMIC_MOVE} → 退化普通 move → 失败清理」的逻辑原本只活在本类，而仓里还有三处
     * 落点（{@code PersonalScenarioStore} 的保存/删除、{@code savePlayerLanguages}）
     * 仍在裸 {@code config.save(file)} 覆写 —— 复制第二份必然分叉。现在它们是同一个静态方法，
     * 本方法只是保留「{@link FileConfiguration} 入参 + 插件 logger」的本地适配壳。
     *
     * <p>清理失败的诊断仍落在 {@code Logger.getLogger(ConfigManager.class.getName())} 上
     * （不是 {@code plugin.getLogger()}）：这是既有测试与运维过滤习惯依赖的契约，
     * 由 {@link AtomicFileWriter#write(Path, java.util.function.Supplier, Logger,
     * java.util.function.Consumer)} 的第四参注入，不在工具类里写死。
     *
     * @return 是否写成功；失败时目标文件保持原样
     */
    private boolean writeAtomically(FileConfiguration config, File target) {
        return AtomicFileWriter.write(target.toPath(), config::saveToString, plugin.getLogger(),
                tmp -> AtomicFileWriter.deleteQuietly(tmp, CONFIG_LOGGER));
    }

    /** 原子写清理失败的诊断落点（既有契约：按本类名过滤日志，与插件 logger 分开）。 */
    private static final Logger CONFIG_LOGGER = Logger.getLogger(ConfigManager.class.getName());

    /**
     * 把一份配置写回数据目录下的 {@code path}。
     *
     * <p><b>返回值不是装饰</b>：原实现 {@code void} + 吞 IOException，让调用方无法区分
     * 「写成功」与「磁盘满 / 权限不足 / 文件被占用」。唯一的产线使用方
     * {@code AdminCommandHandler.importWordBank} 因此会在落盘失败后照发
     * 「导入完成」—— 内存里加了词、磁盘上零字节变更，重启即全丢。
     *
     * <p>SEVERE 日志保留（失败必须留痕），但失败不再只有日志、没有返回值可查：
     * 调用方拿到 {@code false} 后有自己的上报义务。范式与
     * {@code AdminCommandHandler.exportWordBank} 的 catch-reason-return 一致。
     *
     * <h2>R-11：改为原子写（同 {@link #writeAtomically}），不再直写目标文件</h2>
     * <p>原实现是 {@code config.save(file)} —— <b>直接覆写目标文件</b>
     * （字节码确认：{@code createParentDirs} → {@code saveToString()} →
     * {@code new FileOutputStream(file)} 写入）。写到一半失败（磁盘满 / 进程被杀 /
     * I/O 错误）时目标文件变成<b>半截 YAML</b>：{@code YamlConfiguration.loadConfiguration}
     * 吞掉解析异常返回空配置，于是 {@code words/*.yml} 整档词库加载不出来，玩家侧表现为
     * 「难度突然不存在」。这正是本类 {@link #writeAtomically} 当初为语言文件解决过的
     * 同一个风险，{@code saveConfig} 当时漏在外面。
     *
     * <p>现在：写 {@code <path>.tmp} → {@code Files.move(..., ATOMIC_MOVE)} 替换。
     * 任何一步失败都返回 {@code false} 且<b>目标文件保持原样</b>（旧的可用词库仍在，
     * 下次启动照常加载 —— 数据是「旧的」而不是「坏的」）。
     *
     * <p><b>父目录</b>：{@code FileConfiguration.save} 会自己 {@code createParentDirs}，
     * {@code Files.writeString} 不会。调用方都不建目录（{@code saveWordBankToYaml} 只做
     * 路径越界校验，不 {@code mkdirs}），实跑路径上 {@code words/} 由
     * {@link #loadAllConfigs()} 里的 {@code createDirectoryIfNotExists("words")} 保证；
     * 但那是<b>启动期</b>的保证，且 {@code AdminCommandHandler} 的失败用例正是把
     * {@code words/} 造成普通文件。这里显式补建父目录，把「父目录存在」重新变成
     * {@code saveConfig} 自己的契约，并在建不出来时返回 {@code false}。
     *
     * @return {@code true} 已落盘；{@code false} 写失败（目标文件保持原样，
     *         不会留下半截 YAML）
     */
    public boolean saveConfig(FileConfiguration config, String path) {
        File target = new File(plugin.getDataFolder(), path);
        File parent = target.getParentFile();
        if (parent != null) {
            try {
                // 对应 FileConfiguration.save 里的 createParentDirs；失败直接判失败，
                // 不让后续 writeString 抛一个语义更模糊的 NoSuchFileException。
                Files.createDirectories(parent.toPath());
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to save config file: " + path + " (cannot create parent directory "
                                + parent.getAbsolutePath() + ")", e);
                return false;
            }
        }
        if (!writeAtomically(config, target)) {
            plugin.getLogger().severe("Failed to save config file: " + path
                    + " (target left untouched; a partially written file would break the next load)");
            return false;
        }
        return true;
    }

    public FileConfiguration loadWordBank(String difficulty) {
        return loadConfig("words/" + difficulty + ".yml");
    }

    private void createDirectoryIfNotExists(String dirName) {
        File dir = new File(plugin.getDataFolder(), dirName);
        // 必须检查返回值：原实现丢弃 mkdirs() 结果，建目录失败（权限/磁盘）会彻底静默。
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            plugin.getLogger().severe("Failed to create directory " + dir.getAbsolutePath()
                    + "; files under it cannot be written until it exists.");
        }
    }

    private void saveResourceIfNotExists(String resourcePath) {
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            plugin.saveResource(resourcePath, false);
        }
    }

    /**
     * 词库内置版本号。**每次内置词库内容有实质变更时必须 +1**（改难度曲线、增删词条、
     * 修释义/音标都算）。数据目录里的 {@code words-version.txt} 小于此值时，三份词库会被
     * 备份后替换为 JAR 内的新版本。
     *
     * <p>版本历史：
     * <ul>
     *   <li>{@code 1} —— 2026-10-31 按 NGSL 词频重建（1025 → 1357 词，覆盖率 66.16% → 85.37%）</li>
     * </ul>
     *
     * <p><b>为什么需要这个机制</b>：{@link #saveResourceIfNotExists} 只在文件<b>缺失</b>时释放。
     * 于是任何已经跑过一次插件的服务器，数据目录里都有旧的 {@code words/*.yml}，
     * 升级插件后 {@code WordManagerImpl.loadAllWordBanks()} 读的仍是旧文件 —— 新版词库
     * 只有全新安装才看得到。这会让「扩充词库」这类内容更新对**所有存量服主**完全不可见，
     * 而日志里没有任何提示。玩家感知到的就是「说好的新词呢」。
     *
     * <p><b>为什么要先备份</b>：词库文件是服主可以自己改的（{@code /lea exportwordbank} +
     * 手工编辑是官方支持的工作流）。直接覆盖会静默冲掉他们的改动。备份到
     * {@code words-backup-<旧版本>-<时间戳>/} 下，并打一条 WARNING 说明如何回滚。
     */
    private static final int WORD_BANK_VERSION = 1;

    /**
     * 按版本号升级数据目录里的词库文件。只在 {@link #WORD_BANK_VERSION} 高于磁盘记录时动作。
     *
     * <p>不做的事：不尝试合并、不做逐条 diff。词库是整份内容资产，逐条合并的语义
     * （「服主删掉的词要不要加回来」「改过的释义以谁为准」）没有正确答案，而备份 + 整体
     * 替换至少是**可解释、可回滚**的。
     */
    private void upgradeWordBanksIfNeeded() {
        File dataFolder = plugin.getDataFolder();
        File versionFile = new File(dataFolder, "words-version.txt");

        int installed = 0;
        if (versionFile.isFile()) {
            try {
                installed = Integer.parseInt(Files.readString(versionFile.toPath(),
                        StandardCharsets.UTF_8).trim());
            } catch (IOException | NumberFormatException e) {
                // 读不出来当作 0（视为旧版），走升级路径；不阻断启动
                plugin.getLogger().fine("Could not read words-version.txt: " + e.getMessage());
                installed = 0;
            }
        }
        if (installed >= WORD_BANK_VERSION) {
            return;
        }

        // 旧版本 < 新版本：备份后替换。备份目录带旧版本与时间戳，避免多次升级互相覆盖。
        File backupDir = new File(dataFolder, "words-backup-v" + installed + "-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()));
        int needed = 0, upgraded = 0;   // 分母 = 所有需要升级的档位，不是「任一成功」
        boolean backupFailed = false;

        for (String tier : new String[] {"beginner", "intermediate", "advanced"}) {
            File disk = new File(dataFolder, "words/" + tier + ".yml");
            if (!disk.isFile()) {
                continue;   // 缺失的由 saveResourceIfNotExists 负责释放，这里不重复
            }
            needed++;   // 备份/写入失败的档位同样计入分母，否则会被部分成功骗过
            // LG-02：守卫必须同时罩住备份与替换。原实现替换在 if 之外，使「备份失败就
            // 不替换」只对抛异常的那一档成立，其后档位无 .bak 即被硬覆盖。
            if (!backupFailed) {
                try {
                    if (!backupDir.isDirectory() && !backupDir.mkdirs()) {
                        throw new IOException("mkdirs failed: " + backupDir.getAbsolutePath());
                    }
                    Files.copy(disk.toPath(), new File(backupDir, tier + ".yml").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    // 备份失败就**不替换**：覆盖服主改过的词库且没有回滚点，是不可接受的损失
                    backupFailed = true;
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to back up the existing word bank before upgrading; "
                            + "the word banks will NOT be replaced. Back them up manually, then "
                            + "delete words-version.txt and restart.", e);
                    continue;
                }
                // LG-02：替换与备份同受此守卫保护。
                try {
                    plugin.saveResource("words/" + tier + ".yml", true);   // true = 覆盖
                    upgraded++;
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to write upgraded word bank " + tier + ".yml", t);
                }
            }
        }

        if (upgraded < needed) {
            // 部分成功不盖章：否则下次启动 installed >= VERSION 直接早退，旧档位永久滞留。
            plugin.getLogger().warning("Word bank upgrade incomplete: " + upgraded + "/" + needed
                    + " tier(s); words-version.txt left at v" + installed + " so it retries next start.");
        }
        if (needed > 0 && upgraded == needed) {
            try {
                Files.writeString(versionFile.toPath(), String.valueOf(WORD_BANK_VERSION),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Word banks were upgraded but words-version.txt could not be written; "
                        + "the upgrade will run again next start.", e);
            }
            plugin.getLogger().warning("Built-in word banks upgraded to v" + WORD_BANK_VERSION
                    + ". The previous version was backed up to "
                    + backupDir.getName() + "/ — if you had edited the word files yourself, "
                    + "copy them back from there and add your changes to the new files.");
        }
    }

    public FileConfiguration getMainConfig() {
        ConfigSnapshot snap = configSnapshot;
        return snap != null ? snap.mainConfig() : mainConfig;
    }

    public FileConfiguration getMessageConfig() {
        ConfigSnapshot snap = configSnapshot;
        return snap != null ? snap.messageConfig() : messageConfig;
    }

    /**
     * R14-F3（FIX 1/2）：语言标签归一化的<b>唯一</b>入口。
     *
     * <p><b>为什么必须有它</b>：{@code player_lang.yml} 是数据目录里可手改的文件，
     * {@code config.yml} 的 {@code general.language} 也只是自由字符串。旧实现里
     * {@link #getMessageConfig(String)} 用精确的 {@code "zh".equals(lang)}，而
     * {@code Achievement} / {@code AIChatManager} / {@code ProxyMessenger} /
     * {@code ChatAnswerListener} / {@code ChatFeedbackManager} / {@code PersonalScenarioGUI} /
     * {@code ScenarioSelectGUI} / {@code ScenarioSelectListener} 这 9 处兄弟调用点用的是
     * {@code equalsIgnoreCase}。于是一旦存了 {@code "ZH"}，同一个玩家会同时看到<b>英文</b>的
     * 聊天/GUI 文案和<b>中文</b>的成就名/场景名/代理广播 —— 半英半中的服。</p>
     *
     * <p><b>规则</b>：去空白 → 小写（{@link Locale#ROOT}，避免土耳其语环境把 {@code "I"}
     * 折成无点 ı）→ 连字符折成下划线（{@code zh-CN} 与 {@code zh_cn} 等价）→
     * {@code zh}/{@code cn}/{@code zh_*} 归 {@code zh}，{@code en}/{@code en_*} 归 {@code en}。
     * 前缀规则而不是枚举表：{@code zh_tw}/{@code en_gb} 这类"方言"落到本插件仅有的两份文案
     * 上，比丢弃更接近服主本意。</p>
     *
     * <p><b>未知值（含 null）回落 {@code zh}</b>：与全仓既有的 R-55l 口径一致
     * （缺 key / 取不到一律回落 zh），且保证本方法<b>永不返回 null</b>——
     * {@code "prefix." + lang} 这类拼接因此不会拼出 {@code prefix.null}。
     * 需要区分"合法输入"时用 {@link #isRecognizedLanguage(String)}。</p>
     *
     * @param lang 任意来源的语言标签，可为 null
     * @return {@code "zh"} 或 {@code "en"}，绝不为 null
     */
    public static String canonicalLanguage(String lang) {
        String mapped = mapLanguageToken(normalizeLanguageToken(lang));
        return (mapped != null) ? mapped : LANG_ZH;
    }

    /**
     * R14-F3（FIX 1）：{@link #canonicalLanguage(String)} 的布尔视图。
     * 与 {@link #canonicalLanguage(String)} 同源，因此 {@code null}/{@code 未知值} 一律视为 zh
     * （不是"既不 zh 也不 en"——非 zh 即 en 的写法会让未知值静默变成英文服）。
     *
     * @param lang 任意来源的语言标签，可为 null
     * @return true 表示应使用 {@code messages/zh.yml}
     */
    public static boolean isZh(String lang) {
        return LANG_ZH.equals(canonicalLanguage(lang));
    }

    /**
     * R14-F3（FIX 2）：该值是否是<b>可识别</b>的语言标签（而不是"能不能归一化"）。
     *
     * <p>调用点只有 {@code loadAllConfigsInternal}：{@code general.language} 写错时要点名
     * 告警，而 {@link #canonicalLanguage(String)} 会把 {@code "fr"} 与 {@code "cn"} 一起
     * 折叠成 zh —— 归一化成功不代表输入合法，所以判定必须独立于回落。</p>
     *
     * @param lang 原始配置值，可为 null
     * @return true 表示 zh/en 系（大小写与 {@code _}/{@code -} 国家后缀均接受）
     */
    public static boolean isRecognizedLanguage(String lang) {
        return mapLanguageToken(normalizeLanguageToken(lang)) != null;
    }

    /** 去空白 + 小写 + 连字符折下划线。仅本类的归一化链使用。 */
    private static String normalizeLanguageToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 归一化 token 到 canonical 值；无法识别返回 {@code null}（由调用方决定回落策略）。
     * 刻意<b>不</b>读 {@code Locale} 运行时环境：语言标签由配置/玩家选择决定，与 JVM 默认
     * locale 无关。
     */
    private static String mapLanguageToken(String token) {
        if (token.equals("zh") || token.equals("cn") || token.startsWith("zh_")) {
            return LANG_ZH;
        }
        if (token.equals("en") || token.startsWith("en_")) {
            return LANG_EN;
        }
        return null;
    }

    /**
     * 按语言取一份 {@code messages/*.yml}。
     *
     * <p>R14-F3（FIX 1）：语言比较收敛到 {@link #canonicalLanguage(String)}（与 9 处兄弟
     * 调用点的 {@code equalsIgnoreCase} 语义对齐，并额外接受 {@code zh_CN}/{@code cn} 等写法）。
     * 归一化只发生在本方法这一处入口，兄弟类各自的比较因此不再能改变本方法选中的文件 ——
     * 它们最多只影响自己那句 {@code getDisplayName(boolean)}，不会再出现"同一语言两份结果"。</p>
     *
     * @param lang 玩家语言 / 配置语言，可为 null
     * @return 对应语言的消息配置；快照未发布时回落 legacy 字段（可能为 null，调用方需自守）
     */
    public FileConfiguration getMessageConfig(String lang) {
        boolean zh = isZh(lang);
        ConfigSnapshot snap = configSnapshot;
        if (snap != null) {
            return zh ? snap.messageConfigZh() : snap.messageConfigEn();
        }
        return zh ? messageConfigZh : messageConfigEn;
    }

    /**
     * R14-F3：服务端默认语言（{@code general.language}）的 <b>canonical</b> 取值，
     * 必定是 {@code "zh"} 或 {@code "en"}。
     *
     * <p><b>为什么要有这个 getter</b>：{@code general.language} 在 6 个地方被直接读
     * （{@code MessageUtil} 三处、{@code LetsLearnEnglish} 三处、{@code DuelManager}、
     * {@code LLEPapiExpansion}、{@code DiagnoseCommand}）。每一处各自拿原始字符串去拼
     * {@code "prefix." + lang} 或做 {@code equals}，任何一个写错都会让服主看到
     * {@code prefix.cn} 这种取不到的前缀（然后静默回落语言文件里的 {@code prefix}），
     * 而玩家表里的默认语言又和 active 消息文件对不上。这里把"读 + 归一化"收成一处，
     * 调用方拿到的永远是那两个 canonical 值。</p>
     *
     * <p>与 {@link #getMessageConfig(String)} 的一致性：{@code getMessageConfig(getDefaultLanguage())}
     * 与 {@code getMessageConfig()}（active 文件）必然指向同一份文件 —— 这正是 FIX 2 之前
     * 在 {@code general.language: cn} 下被破坏的那条不变量。</p>
     *
     * @return {@code "zh"} 或 {@code "en"}，永不为 null（mainConfig 未加载时回落 zh）
     */
    public String getDefaultLanguage() {
        FileConfiguration main = getMainConfig();
        if (main == null) {
            return LANG_ZH;
        }
        return canonicalLanguage(main.getString("general.language", LANG_ZH));
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