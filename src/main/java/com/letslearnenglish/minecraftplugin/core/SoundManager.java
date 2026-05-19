package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.listener.GUIMenuListener;
import com.letslearnenglish.minecraftplugin.util.VersionAdapter;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;

import java.util.HashMap;
import java.util.Map;

public class SoundManager implements Listener {

    private final LetsLearnEnglish plugin;
    private final VersionAdapter versionAdapter;
    private final Map<String, Sound> soundCache = new HashMap<>();
    private boolean enabled;

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
        try {
            Sound sound = Sound.valueOf(soundName);
            soundCache.put(key, sound);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid sound name for '" + key + "': " + soundName);
            try {
                Sound fallback = Sound.valueOf(defaultSound);
                soundCache.put(key, fallback);
                plugin.getLogger().info("Using default sound '" + defaultSound + "' for '" + key + "'");
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Default sound also invalid for '" + key + "': " + defaultSound);
            }
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!enabled) return;
        if (!(event.getPlayer() instanceof Player)) return;
        if (!GUIMenuListener.isPluginGUI(event.getView().getTitle())) return;
        playSound((Player) event.getPlayer(), "menu-open");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!GUIMenuListener.isPluginGUI(event.getView().getTitle())) return;
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