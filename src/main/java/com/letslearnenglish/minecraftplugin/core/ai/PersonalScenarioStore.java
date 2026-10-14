package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager.ScenarioConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * 个人场景存储 — 玩家个人场景（player_scenarios/&lt;uuid&gt;.yml）的落盘、懒加载、
 * 内存副本与删除。
 *
 * <p>P2-28/PF-FB-05：从 AIChatManager 抽取。AIChatManager 保留全部读取门面
 * （签名不变，委托本类），不再自持文件 IO 与内存副本。
 * 边界：本类只负责"文件 + 内存"两件事，不碰 HTTP / 会话态 / 全局场景。</p>
 *
 * <p>线程：所有方法在主线程同步调用（文件 ≤512KB，与 import 路径一致；
 * 不上异步回调复杂度）。</p>
 */
public class PersonalScenarioStore {

    /** R-62：单玩家个人场景数量上限（超出只加载前 N 条 / 新导入被拒）。 */
    private static final int MAX_PERSONAL_SCENARIOS = 30;
    /** R-62：个人场景文件大小上限（512KB）——超限视为异常，不解析。 */
    private static final long MAX_PERSONAL_SCENARIO_FILE_BYTES = 512 * 1024L;
    /** 个人场景 key 前缀：personal:&lt;uuid前8&gt;:&lt;slug&gt;。 */
    private static final String PERSONAL_KEY_PREFIX = "personal:";

    private final LetsLearnEnglish plugin;

    /** 每玩家个人场景（key → ScenarioConfig，key 形如"personal:<uuid8>:<slug>"全局唯一） */
    private final Map<UUID, LinkedHashMap<String, ScenarioConfig>> personalScenarios = new ConcurrentHashMap<>();

    public PersonalScenarioStore(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //  导入（H7：不再写全局 ai_scenarios.yml / 不 reload 全局）
    //  存储：plugins/LetsLearnEnglish/player_scenarios/<uuid>.yml
    //  格式：scenarios.<key>.{display_name, display_name_zh, material, prompt}
    // ============================================================

    /**
     * 导入一条玩家个人场景：落盘到该玩家的个人场景文件，并加载进内存。
     * 不触碰全局 ai_scenarios.yml，不触发全局 reload，不影响其他玩家。
     *
     * @param player 导入者
     * @param name   场景展示名（调用方已校验：≤32 字符，仅中英文/数字/下划线）
     * @param prompt 场景 prompt（调用方已校验长度）
     * @return {@link AIChatManager.ImportResult}（冻结接口，定义保留在 AIChatManager 以
     *         保证 LearnCommand 调用点零改动）：成功时 key 非空；覆盖同名场景时
     *         {@code updated=true}；写盘失败 {@code saveFailed=true}；数量达上限被拒
     *         {@code limitReached=true}。saveFailed / limitReached 时调用点不得报成功。
     */
    public AIChatManager.ImportResult importPersonalScenario(Player player, String name, String prompt) {
        UUID ownerId = player.getUniqueId();
        String slug = personalScenarioSlug(name);
        // key 全局唯一：personal:<uuid前8>:<slug>，避免两玩家同名场景互相覆盖；
        // slug 尾附名称短码 —— 同名幂等复用同一 key，异名（含纯中文名）互不覆盖。
        String key = PERSONAL_KEY_PREFIX + ownerId.toString().substring(0, 8) + ":" + slug;

        // R-62：先确保内存副本反映最新磁盘状态（懒加载 + 文件大小/条数上限一并生效）。
        LinkedHashMap<String, ScenarioConfig> mine = personalScenarios.get(ownerId);
        if (mine == null) {
            reloadPersonalScenarios(ownerId);
            mine = personalScenarios.get(ownerId);
        }
        if (mine == null) {
            mine = personalScenarios.computeIfAbsent(ownerId, k -> new LinkedHashMap<>());
        }

        File dir = new File(plugin.getDataFolder(), "player_scenarios");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Failed to create player_scenarios directory.");
            return new AIChatManager.ImportResult(null, false, true, false);
        }
        File file = new File(dir, ownerId + ".yml");
        // R-62：超大文件与 reload 同策略 —— 不解析（视为损坏），走下方备份 + 干净重写。
        YamlConfiguration config = new YamlConfiguration();
        ConfigurationSection existing = null;
        if (file.exists()) {
            if (file.length() > MAX_PERSONAL_SCENARIO_FILE_BYTES) {
                plugin.getLogger().warning("Personal scenario file for " + ownerId + " exceeds "
                        + (MAX_PERSONAL_SCENARIO_FILE_BYTES / 1024)
                        + "KB — not parsed; will be backed up and rewritten cleanly.");
            } else {
                config = YamlConfiguration.loadConfiguration(file);
                existing = config.getConfigurationSection("scenarios");
            }
        }
        boolean exists = mine.containsKey(key) || (existing != null && existing.contains(key));

        // R-62：数量上限 —— 仅拒绝"新增"；同名覆盖更新不占新额度，幂等更新始终放行。
        if (!exists && mine.size() >= MAX_PERSONAL_SCENARIOS) {
            return new AIChatManager.ImportResult(null, false, false, true);
        }

        // R-61(d)：文件有内容但解析不出 scenarios 段（损坏/空表）→ save 前备份为 .bak，
        // 防止"仅含新场景"的覆盖把旧数据清光（只保留一份 bak，覆盖旧 bak）。
        if (file.exists() && file.length() > 0 && existing == null) {
            File backup = new File(dir, ownerId + ".yml.bak");
            try {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().warning("Personal scenario file for " + ownerId
                        + " appears corrupted (no 'scenarios' section) — backed up to "
                        + backup.getName() + " before overwrite.");
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to back up corrupted personal scenario file for "
                        + ownerId + ": " + e.getMessage());
            }
        }

        config.set("scenarios." + key + ".display_name", name);
        config.set("scenarios." + key + ".display_name_zh", name);
        config.set("scenarios." + key + ".material", "PAPER");
        config.set("scenarios." + key + ".prompt", prompt);
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save personal scenario for "
                    + player.getName() + ": " + e.getMessage());
            return new AIChatManager.ImportResult(null, false, true, false);
        }

        ScenarioConfig sc = new ScenarioConfig(key);
        sc.displayName = name;
        sc.displayNameZh = name;
        sc.material = Material.PAPER;
        sc.prompt = prompt;
        mine.put(key, sc);
        return new AIChatManager.ImportResult(key, exists, false, false);
    }

    /**
     * 重载指定玩家的个人场景（从磁盘读入内存）。仅影响该玩家，不碰全局。
     * 个人文件损坏/缺失时清空其内存个人场景，保证"坏文件不炸全局"。
     * R-62：文件大小上限（超限 warn + 视为空表）；单玩家场景条数上限（只加载前 N 条 + warn）。
     */
    public void reloadPersonalScenarios(UUID ownerId) {
        LinkedHashMap<String, ScenarioConfig> mine =
                personalScenarios.computeIfAbsent(ownerId, k -> new LinkedHashMap<>());
        mine.clear();
        File file = new File(plugin.getDataFolder(), "player_scenarios" + File.separator + ownerId + ".yml");
        if (!file.exists()) {
            return;
        }
        // R-62(a)：文件大小上限 —— 超限不解析（防止巨大/被污染的 YAML 拖垮主线程），视为空表。
        if (file.length() > MAX_PERSONAL_SCENARIO_FILE_BYTES) {
            plugin.getLogger().warning("Personal scenario file for " + ownerId + " exceeds "
                    + (MAX_PERSONAL_SCENARIO_FILE_BYTES / 1024) + "KB — skipped without parsing.");
            return;
        }
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = config.getConfigurationSection("scenarios");
            if (section == null) {
                return;
            }
            // R-62(b)：条数上限 —— 超出时只加载前 N 条并 warn（按文件中的键顺序）。
            int loaded = 0;
            for (String key : section.getKeys(false)) {
                if (loaded >= MAX_PERSONAL_SCENARIOS) {
                    plugin.getLogger().warning("Personal scenario file for " + ownerId
                            + " contains more than " + MAX_PERSONAL_SCENARIOS
                            + " scenarios — only the first " + MAX_PERSONAL_SCENARIOS + " are loaded.");
                    break;
                }
                ConfigurationSection sec = section.getConfigurationSection(key);
                if (sec == null) continue;
                ScenarioConfig sc = new ScenarioConfig(key);
                sc.displayName = sec.getString("display_name", key);
                sc.displayNameZh = sec.getString("display_name_zh", sc.displayName);
                try {
                    sc.material = Material.valueOf(sec.getString("material", "PAPER").toUpperCase());
                } catch (IllegalArgumentException e) {
                    sc.material = Material.PAPER;
                }
                sc.prompt = sec.getString("prompt", "");
                mine.put(key, sc);
                loaded++;
            }
        } catch (Exception e) {
            // 个人文件损坏：清空内存副本（已 clear），只记 warning，不影响全局与他人
            plugin.getLogger().warning("Failed to load personal scenarios for "
                    + ownerId + ", cleared in-memory copy: " + e.getMessage());
        }
    }

    // ============================================================
    //  读取
    // ============================================================

    /** 获取指定玩家的个人场景（内存副本的防御性拷贝；懒加载，保证下次登录仍可用）。 */
    public LinkedHashMap<String, ScenarioConfig> getPersonalScenarios(UUID ownerId) {
        LinkedHashMap<String, ScenarioConfig> mine = personalScenarios.get(ownerId);
        if (mine == null) {
            reloadPersonalScenarios(ownerId);
            mine = personalScenarios.get(ownerId);
        }
        // F4-03：返回防御性拷贝 —— 调用方拿到的是快照，修改/迭代不会污染内部状态。
        return (mine != null) ? new LinkedHashMap<>(mine) : new LinkedHashMap<>();
    }

    /**
     * 读取单条个人场景（不触发懒加载，null 安全）。
     * 供 AIChatManager.buildSystemPrompt 的"懒加载最多 1 次/玩家/进程"守卫逻辑使用。
     */
    public ScenarioConfig getLoadedPersonalScenario(UUID ownerId, String key) {
        LinkedHashMap<String, ScenarioConfig> mine = personalScenarios.get(ownerId);
        return (mine != null) ? mine.get(key) : null;
    }

    /** 该玩家的个人场景内存副本是否已加载过（懒加载守卫，防刷屏语义）。 */
    public boolean isLoaded(UUID ownerId) {
        return personalScenarios.containsKey(ownerId);
    }

    /**
     * R-62：单玩家个人场景数量上限（供调用点填充 {@code scenario.limit-reached} 的 {max} 占位）。
     */
    public int getMaxPersonalScenarios() {
        return MAX_PERSONAL_SCENARIOS;
    }

    /** key 是否为个人场景 key（personal: 前缀判定）。 */
    public static boolean isPersonalKey(String key) {
        return key != null && key.startsWith(PERSONAL_KEY_PREFIX);
    }

    // ============================================================
    //  删除
    // ============================================================

    /**
     * R-67/管理页：删除结果（冻结接口，PersonalScenarioListener 调用点消费）。
     * <ul>
     *   <li>{@code deleted} — true 表示已删（文件 set-null + save 成功，内存已同步移除）</li>
     *   <li>{@code notFound} — true 表示未找到（key 非 personal: 前缀 / 不在该玩家内存登记 /
     *       文件不存在 / 超大未解析文件的内存空副本——已知限制：此类场景不可见亦不可删）</li>
     *   <li>{@code saveFailed} — true 表示写盘失败（内存未动）；与 notFound 互斥</li>
     * </ul>
     */
    public static record DeleteResult(boolean deleted, boolean notFound, boolean saveFailed) {}

    /**
     * 删除一条玩家个人场景：文件 {@code set("scenarios."+key, null)} + save，
     * 成功后同步原地更新内存副本（不做全量 reload；文件空表保留不删文件——
     * reload 对空/缺段有既有容错，删文件反而多一条错误面）。
     *
     * <p>纵深校验：key 必须带 {@code personal:} 前缀（key 已含 uuid8 前缀），
     * 且登记在该玩家的 {@code personalScenarios} 内存副本中，防跨玩家 key 注入。</p>
     *
     * <p>边界：玩家正处于该场景的对话中——对话不打断（system prompt 已在开局冻结，
     * 上下文与此无关）；不产生 .bak（删除只在有效 YAML 上 set-null + save）。</p>
     *
     * @param ownerId 场景归属玩家
     * @param key     个人场景 key（须 personal: 前缀且归属该玩家）
     * @return {@link DeleteResult}；主线程同步执行（文件 ≤512KB、import 路径同为同步）。
     */
    public DeleteResult deletePersonalScenario(UUID ownerId, String key) {
        if (!isPersonalKey(key)) {
            return new DeleteResult(false, true, false);
        }
        LinkedHashMap<String, ScenarioConfig> mine = personalScenarios.get(ownerId);
        if (mine == null) {
            reloadPersonalScenarios(ownerId);
            mine = personalScenarios.get(ownerId);
        }
        if (mine == null || !mine.containsKey(key)) {
            return new DeleteResult(false, true, false);
        }
        File file = new File(plugin.getDataFolder(), "player_scenarios" + File.separator + ownerId + ".yml");
        if (!file.exists()) {
            return new DeleteResult(false, true, false);
        }
        // R-62 同口径：超大文件不解析，无法安全 set-null 重写 —— 视为保存失败
        if (file.length() > MAX_PERSONAL_SCENARIO_FILE_BYTES) {
            plugin.getLogger().warning("Personal scenario file for " + ownerId + " exceeds "
                    + (MAX_PERSONAL_SCENARIO_FILE_BYTES / 1024) + "KB — delete skipped.");
            return new DeleteResult(false, false, true);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("scenarios." + key, null);
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to delete personal scenario " + key + " for "
                    + ownerId + ": " + e.getMessage());
            return new DeleteResult(false, false, true);
        }
        mine.remove(key);
        return new DeleteResult(true, false, false);
    }

    // ============================================================
    //  内部工具
    // ============================================================

    /**
     * R-61：slug 生成 —— 可读前缀 + 名称 CRC32 短码（8 位十六进制）。
     * 纯中文名（如"开会"/"购物"）归一化后 slug 全为 "_"，靠短码区分产出不同 key，
     * 不再互相静默覆盖；同名重复导入归属同一 key（幂等更新语义）。
     */
    private static String personalScenarioSlug(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        String slug = normalized.replaceAll("[^a-z0-9_]", "_");
        if (slug.isEmpty()) slug = "custom";
        if (slug.length() > 24) slug = slug.substring(0, 24);
        CRC32 crc = new CRC32();
        crc.update(normalized.getBytes(StandardCharsets.UTF_8));
        return slug + "_" + String.format("%08x", crc.getValue());
    }

    /** R-67：关服清理（AIChatManager.shutdown 委托）——清空全部玩家内存副本。 */
    public void clearAll() {
        personalScenarios.clear();
    }
}
