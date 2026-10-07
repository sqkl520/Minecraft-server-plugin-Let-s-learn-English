package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.gui.GUIUtils;
import com.letslearnenglish.minecraftplugin.util.VersionAdapter;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SoundManager implements Listener {

    private final LetsLearnEnglish plugin;
    private final VersionAdapter versionAdapter;
    // 音效缓存热重载时整体重建，且可能被异步线程读取（异步聊天触发的成就解锁音效走此路径），
    // 故改用 ConcurrentHashMap；enabled 同样跨线程读写，用 volatile
    private final Map<String, Sound> soundCache = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    public SoundManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.versionAdapter = plugin.getVersionAdapter();
        reloadSoundCache();
    }

    public void reloadSoundCache() {
        soundCache.clear();
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

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!enabled) return;
        if (!(event.getPlayer() instanceof Player)) return;
        if (!GUIUtils.isPluginGUI(event.getView().getTitle())) return;
        playSound((Player) event.getPlayer(), "menu-open");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled) return;
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!GUIUtils.isPluginGUI(event.getView().getTitle())) return;
        playSound((Player) event.getWhoClicked(), "menu-click");
    }

    public static void playSound(Player player, String configKey) {
        LetsLearnEnglish instance = LetsLearnEnglish.getInstance();
        if (instance == null) return;
        SoundManager manager = instance.getSoundManager();
        if (manager == null) return;
        if (!manager.enabled) return;

        Sound sound = manager.soundCache.get(configKey);
        if (sound == null) return;

        manager.versionAdapter.playSound(player, sound, 1.0f, 1.0f);
    }
}