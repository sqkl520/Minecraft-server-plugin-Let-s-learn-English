package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.GUIUtils;
import com.letslearnenglish.minecraftplugin.util.VersionAdapter;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sound manager — 配置驱动的 GUI / 反馈音效。
 *
 * R-86 音效去重裁决（S1 流，两个方向分开处理）：
 * - menu-open（开屏）：**保留本类的全局 InventoryOpen 播放，删各处显式播放**。
 *   理由：全库梳理后，16 个插件 GUI 类里只有 MainMenu / ScenarioSelectGUI /
 *   ChatHistoryGUI 这 3 处有显式播放，其余 13 个（个人主页/英语广场/排行榜/单词学习/
 *   错题本/词库选择/每日挑战/过往学习/成就墙/庆祝页/二确页等）完全没有显式播放 ——
 *   它们目前的开屏音全靠本全局兜底。若按"删全局"方向修，这 13 个界面会集体失去开屏音，
 *   且其中多数不在本流白名单内无法就地补播；而"保留全局、删显式"只需删 3 处显式播放，
 *   就能让全部 GUI 稳定单播一次。MainMenu 与 ScenarioSelectGUI 的显式播放已随本流移除。
 * - menu-click（点击）：**删除本类的全局 InventoryClick 播放**（方法与相关 import 一并清理）。
 *   理由：它与各 listener 在有效按钮分支上的显式播放叠加成双声，而且不分区 —— 点在背景
 *   玻璃、甚至玩家自己背包里的物品上也会响。各 listener 的显式播放已覆盖所有有效按钮
 *   （覆盖审计见 R-86 报告），删全局后反馈不缺。
 *
 * Phase 3 S-B 音效底座（plan-audio-feedback §3.2 / plan-achievements H16、PF-ACH-01）：
 * - 新增 3 键：combo-milestone（连击里程碑）、countdown-tick（倒计时 tick，学习+对决
 *   共用）、timeout（超时专用；默认取 incorrect 的解析结果 —— 缺省同音，旧行为零变化）。
 * - {@link #playSound(Player, String)} 保留原签名（~60 调用点零改动）；新增 pitch 与
 *   volume+pitch 重载，pitch 统一经 {@link #clampPitch} 钳制到 [0.5, 2.0]。
 * - "none/空串 = 故意静音"语义：见 {@link #cacheSound(FileConfiguration, String, String)} 注释
 *   （影响全部键，含原有 5 键）。
 * - per-player 开关门：读取 ProfileSettings.soundsEnabled；数据层不可用时 fail-open 放行。
 * - {@link #playSoundByName}：按任意音效名直放（成就 unlock-sound 真接线）。
 */

public class SoundManager implements Listener {

    private final LetsLearnEnglish plugin;
    private final VersionAdapter versionAdapter;
    // 音效缓存热重载时整体重建，且可能被异步线程读取（异步聊天触发的成就解锁音效走此路径），
    // 故改用 ConcurrentHashMap；enabled 同样跨线程读写，用 volatile
    private final Map<String, Sound> soundCache = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    // R-86：未知 configKey 首次出现记一次 WARNING（Set 防刷屏）；reload 时清空可重新告警
    private final Set<String> warnedMissingKeys = ConcurrentHashMap.newKeySet();
    // Phase 3：被配置为故意静音的键（gui.sounds.<key> = "" 或 none）。播放路径据此
    // 静默跳过，不误报"未知键"WARNING；reload 时与 soundCache 一同清空重建
    private final Set<String> mutedKeys = ConcurrentHashMap.newKeySet();

    public SoundManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.versionAdapter = plugin.getVersionAdapter();
        reloadSoundCache();
    }

    public void reloadSoundCache() {
        soundCache.clear();
        warnedMissingKeys.clear();
        mutedKeys.clear();
        FileConfiguration config = plugin.getConfigManager().getMainConfig();
        this.enabled = config.getBoolean("gui.sounds.enabled", true);
        if (!enabled) {
            return;
        }
        cacheSound(config, "correct", "ENTITY_PLAYER_LEVELUP");
        Sound incorrectSound = cacheSound(config, "incorrect", "ENTITY_VILLAGER_NO");
        cacheSound(config, "complete", "UI_TOAST_CHALLENGE_COMPLETE");
        cacheSound(config, "menu-open", "BLOCK_NOTE_BLOCK_PLING");
        cacheSound(config, "menu-click", "BLOCK_NOTE_BLOCK_HAT");
        // Phase 3 S-B：倒计时 / 连击里程碑新键（与 config.yml gui.sounds 段同步）
        cacheSound(config, "combo-milestone", "BLOCK_NOTE_BLOCK_BELL");
        cacheSound(config, "countdown-tick", "BLOCK_NOTE_BLOCK_HAT");
        // timeout 的默认值取 gui.sounds.incorrect 的"解析结果"（缺省同音，旧行为零变化）：
        // incorrect 正常解析 → timeout 未显式配置时直接复用同一个 Sound；
        // incorrect 被故意静音 / 解析失败 → timeout 缺省同静音
        // （服主仍可用显式 gui.sounds.timeout 覆盖以上两者）。
        cacheSound(config, "timeout", incorrectSound);
    }

    /**
     * Phase 3 新语义：{@code gui.sounds.<key>} 配为 {@code ""} 或 {@code none}
     * （大小写不敏感，容忍首尾空白）＝ 服主故意静音该事件 —— 不注册、不记 WARNING
     * （区别于"拼写错"：拼写错会记 WARNING 并回退默认音）。被静音的键记入
     * {@link #mutedKeys}，播放路径据此静默跳过，不会误报"未知键"。
     * 注意：该语义对全部键生效（含原有 5 键）—— 服主把任一键配空即静音对应事件，
     * 属预期新行为。
     *
     * @return 最终注册的音效实例；键被故意静音或双双失效时返回 null
     *         （timeout ← incorrect 的默认值链需要此返回值）
     */
    private Sound cacheSound(FileConfiguration config, String key, String defaultSound) {
        String soundName = config.getString("gui.sounds." + key, defaultSound);
        if (isSilenceMarker(soundName)) {
            mutedKeys.add(key);
            return null;
        }
        Sound sound = resolveSound(soundName);
        if (sound != null) {
            soundCache.put(key, sound);
            return sound;
        }
        plugin.getLogger().warning("Invalid sound name for '" + key + "': " + soundName);
        sound = resolveSound(defaultSound);
        if (sound != null) {
            soundCache.put(key, sound);
            plugin.getLogger().info("Using default sound '" + defaultSound + "' for '" + key + "'");
        } else {
            plugin.getLogger().warning("Default sound also invalid for '" + key + "': " + defaultSound);
        }
        return sound;
    }

    /**
     * Phase 3 重载：默认值已是"解析好的" Sound 对象（timeout 缺省复用 incorrect 的
     * 解析结果——incorrect 实际播什么，timeout 缺省就播什么，含两者同为静音）。
     * 未显式配置 gui.sounds.&lt;key&gt; 时直接采用 defaultSound（null = 同静音）。
     */
    private void cacheSound(FileConfiguration config, String key, Sound defaultSound) {
        String soundName = config.getString("gui.sounds." + key);
        if (soundName == null) {
            // 未显式配置：跟随来源键的解析结果（null = 来源键被静音/失效 → 本键同静音）
            if (defaultSound != null) {
                soundCache.put(key, defaultSound);
            } else {
                mutedKeys.add(key);
            }
            return;
        }
        if (isSilenceMarker(soundName)) {
            mutedKeys.add(key);
            return;
        }
        Sound sound = resolveSound(soundName);
        if (sound != null) {
            soundCache.put(key, sound);
            return;
        }
        plugin.getLogger().warning("Invalid sound name for '" + key + "': " + soundName);
        if (defaultSound != null) {
            soundCache.put(key, defaultSound);
            plugin.getLogger().info("Using resolved default sound for '" + key + "'");
        } else {
            mutedKeys.add(key);
        }
    }

    /** ""/"none"（大小写不敏感，容忍首尾空白）是"故意静音"的标记值；null 不是（走原有解析路径）。 */
    private static boolean isSilenceMarker(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.equalsIgnoreCase("none");
    }

    /**
     * Resolve a sound name, trying exact match first, then namespaced variants.
     * Paper 1.21.4 refactored the Sound enum — this handles both old and new naming.
     */
    private Sound resolveSound(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException ignored) {
        }
        // Try with namespaced prefix: minecraft:entity.villager.no → ENTITY_VILLAGER_NO
        if (!name.contains(".")) return null;
        String converted = name.replace("minecraft:", "")
                .replace('.', '_')
                .toUpperCase();
        try {
            return Sound.valueOf(converted);
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    /**
     * Phase 3 per-player 音效开关（ProfileSettings.soundsEnabled，数据层由 S-A 提供）：
     * 数据层不可用（store 为 null / 读取异常，如纯逻辑单测环境）时 fail-open 放行，
     * 只有明确读到 soundsEnabled=false 才静音。全局总闸仍由 {@link #enabled} 把关。
     */
    private boolean isPlayerSoundEnabled(Player player) {
        try {
            PlayerDataStore store = plugin.getPlayerDataStore();
            if (store == null) return true;
            return store.getProfileSettingsReadOnly(player.getUniqueId()).isSoundsEnabled();
        } catch (Exception e) {
            // fail-open：宁可偶尔多播一声，也不因数据层异常吞掉所有反馈音
            return true;
        }
    }

    /** Phase 3：pitch 钳制 —— Bukkit 播放侧 pitch 合法区间为 [0.5, 2.0]，越界会抛异常/失真。 */
    private static float clampPitch(float pitch) {
        if (pitch < 0.5f) return 0.5f;
        if (pitch > 2.0f) return 2.0f;
        return pitch;
    }

    /**
     * R-86 去重后 menu-open 的唯一播放点：插件自有 GUI 每次打开全局播一次。各 GUI 类
     * 不要再自行显式播放 menu-open，否则双声。
     *
     * <p>R5-0：判定改用 {@link GUIUtils#isPluginGUI(org.bukkit.inventory.InventoryView)} ——
     * 先看 top inventory 的 {@code PluginGuiHolder}（holder 优先），认不出才回落标题白名单。
     * {@code InventoryOpenEvent} 对玩家自己背包/工作台同样触发，但它们的 holder 是
     * {@code Player}/{@code Inventory} 而非本插件的 holder，因此 holder 分支不会误命中。
     */
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!enabled) return;
        if (!(event.getPlayer() instanceof Player)) return;
        if (!GUIUtils.isPluginGUI(event.getView())) return;
        playSound((Player) event.getPlayer(), "menu-open");
    }

    /** 原签名：默认 volume=1.0 / pitch=1.0，委托全参版（现存调用点零改动）。 */
    public static void playSound(Player player, String configKey) {
        playSound(player, configKey, 1.0f, 1.0f);
    }

    /** Phase 3：指定 pitch（连击升调 / 倒计时递增音高），volume 保持 1.0。 */
    public static void playSound(Player player, String configKey, float pitch) {
        playSound(player, configKey, 1.0f, pitch);
    }

    /** Phase 3：volume + pitch 全参。pitch 经 clampPitch 钳制到 [0.5, 2.0]。 */
    public static void playSound(Player player, String configKey, float volume, float pitch) {
        LetsLearnEnglish instance = LetsLearnEnglish.getInstance();
        if (instance == null) return;
        SoundManager manager = instance.getSoundManager();
        if (manager == null) return;
        if (!manager.enabled) return;
        if (!manager.isPlayerSoundEnabled(player)) return;

        Sound sound = manager.soundCache.get(configKey);
        if (sound == null) {
            // Phase 3 故意静音键（"none"/空串）：静默跳过，不算拼写错误、不记 WARNING
            if (manager.mutedKeys.contains(configKey)) return;
            // R-86：未知键（调用方拼写错误 / key 未在 reloadSoundCache 注册）——首次记一次
            // WARNING，之后静默防刷屏；并列出现有键方便定位拼写问题。
            if (manager.warnedMissingKeys.add(configKey)) {
                manager.plugin.getLogger().warning("Unknown sound key '" + configKey
                        + "' requested; sound skipped. Known keys: " + manager.soundCache.keySet());
            }
            return;
        }

        manager.versionAdapter.playSound(player, sound, volume, clampPitch(pitch));
    }

    /**
     * Phase 3（H16 / PF-ACH-01）：按"任意音效名"直接播放（配置值直放，如
     * {@code achievement-system.unlock-sound}）。复用 {@link #resolveSound} 的命名
     * 兼容（minecraft: 前缀 / 点分旧名）；未知名首次记一次 WARNING 防刷屏
     * （复用 {@link #warnedMissingKeys} 机制，加 "name:" 前缀与 configKey 告警区分）。
     * 空串 / "none"（大小写不敏感，容忍首尾空白）视为故意静音，静默跳过；
     * null 是调用方异常，走未知名路径记 WARNING-once 便于定位。
     */
    public static void playSoundByName(Player player, String soundName) {
        if (isSilenceMarker(soundName)) return;
        LetsLearnEnglish instance = LetsLearnEnglish.getInstance();
        if (instance == null) return;
        SoundManager manager = instance.getSoundManager();
        if (manager == null) return;
        if (!manager.enabled) return;
        if (!manager.isPlayerSoundEnabled(player)) return;

        Sound sound = manager.resolveSound(soundName);
        if (sound == null) {
            if (manager.warnedMissingKeys.add("name:" + soundName)) {
                manager.plugin.getLogger().warning("Unknown sound name '" + soundName
                        + "' requested; sound skipped.");
            }
            return;
        }

        manager.versionAdapter.playSound(player, sound, 1.0f, 1.0f);
    }
}
