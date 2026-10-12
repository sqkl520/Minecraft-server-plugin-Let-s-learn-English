package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
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

    public SoundManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.versionAdapter = plugin.getVersionAdapter();
        reloadSoundCache();
    }

    public void reloadSoundCache() {
        soundCache.clear();
        warnedMissingKeys.clear();
        FileConfiguration config = plugin.getConfigManager().getMainConfig();
        this.enabled = config.getBoolean("gui.sounds.enabled", true);
        if (!enabled) {
            return;
        }
        cacheSound(config, "correct", "ENTITY_PLAYER_LEVELUP");
        cacheSound(config, "incorrect", "ENTITY_VILLAGER_NO");
        cacheSound(config, "complete", "UI_TOAST_CHALLENGE_COMPLETE");
        cacheSound(config, "menu-open", "BLOCK_NOTE_BLOCK_PLING");
        cacheSound(config, "menu-click", "BLOCK_NOTE_BLOCK_HAT");
    }

    private void cacheSound(FileConfiguration config, String key, String defaultSound) {
        String soundName = config.getString("gui.sounds." + key, defaultSound);
        Sound sound = resolveSound(soundName);
        if (sound != null) {
            soundCache.put(key, sound);
            return;
        }
        plugin.getLogger().warning("Invalid sound name for '" + key + "': " + soundName);
        sound = resolveSound(defaultSound);
        if (sound != null) {
            soundCache.put(key, sound);
            plugin.getLogger().info("Using default sound '" + defaultSound + "' for '" + key + "'");
        } else {
            plugin.getLogger().warning("Default sound also invalid for '" + key + "': " + defaultSound);
        }
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
     * R-86 去重后 menu-open 的唯一播放点：插件自有 GUI（{@link GUIUtils#isPluginGUI}
     * 标题白名单）每次打开全局播一次。各 GUI 类不要再自行显式播放 menu-open，否则双声。
     */
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!enabled) return;
        if (!(event.getPlayer() instanceof Player)) return;
        if (!GUIUtils.isPluginGUI(event.getView().getTitle())) return;
        playSound((Player) event.getPlayer(), "menu-open");
    }

    public static void playSound(Player player, String configKey) {
        LetsLearnEnglish instance = LetsLearnEnglish.getInstance();
        if (instance == null) return;
        SoundManager manager = instance.getSoundManager();
        if (manager == null) return;
        if (!manager.enabled) return;

        Sound sound = manager.soundCache.get(configKey);
        if (sound == null) {
            // R-86：未知键（调用方拼写错误 / key 未在 reloadSoundCache 注册）——首次记一次
            // WARNING，之后静默防刷屏；并列出现有键方便定位拼写问题。
            if (manager.warnedMissingKeys.add(configKey)) {
                manager.plugin.getLogger().warning("Unknown sound key '" + configKey
                        + "' requested; sound skipped. Known keys: " + manager.soundCache.keySet());
            }
            return;
        }

        manager.versionAdapter.playSound(player, sound, 1.0f, 1.0f);
    }
}
