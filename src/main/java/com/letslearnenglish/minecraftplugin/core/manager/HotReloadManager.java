package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;

/**
 * Hot Reload Manager
 *
 * Handles hot-reloading of all plugin configurations without server restart.
 */
public class HotReloadManager {

    private final LetsLearnEnglish plugin;
    private final ConfigManager configManager;

    public HotReloadManager(LetsLearnEnglish plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * Reload all plugin configurations and reinitialize dependent modules.
     */
    public void reloadAll() {
        configManager.reloadAllConfigs();
        reloadWordBanks();
        plugin.getPlayerDataStore().clearCache();
        plugin.getPlayerDataStore().clearProfileCache();
        if (plugin.getAchievementManager() != null) {
            plugin.getAchievementManager().clearCache();
        }
        // 音效缓存读取 config.yml 的 gui.sounds.*，必须放在 config 重载之后刷新
        if (plugin.getSoundManager() != null) {
            plugin.getSoundManager().reloadSoundCache();
        }
        plugin.getLogger().info("All configurations hot-reloaded successfully.");
    }

    /**
     * Reload only the word banks.
     */
    public void reloadWordBanks() {
        plugin.getWordManager().loadAllWordBanks();
        plugin.getLogger().info("Word banks hot-reloaded.");
    }
}