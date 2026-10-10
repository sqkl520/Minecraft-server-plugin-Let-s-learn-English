package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.entity.Player;

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
     *
     * H9：clearCache 前必须先对在线玩家落盘（savePlayerData + saveProfileSettings，
     * 与 onDisable 同一套公开方法），否则缓存里的单词掌握度/统计/隐私设置直接丢失。
     */
    public void reloadAll() {
        configManager.reloadAllConfigs();
        reloadWordBanks();
        flushOnlinePlayerData();
        plugin.getPlayerDataStore().clearCache();
        plugin.getPlayerDataStore().clearProfileCache();
        // C3：RewardEngine 实例常驻不重建，其每日金币累计缓存必须随 reload 清空，
        // 否则今日累计会跨越 reload 继续累加（day 边界仍按自然日懒清零）。
        if (plugin.getProgressManager() != null && plugin.getProgressManager().getRewardEngine() != null) {
            plugin.getProgressManager().getRewardEngine().resetDailyCoinsCache();
        }
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

    /**
     * H9：把所有在线玩家的缓存数据落盘（与 onDisable 同一套落盘调用）。
     * 离线玩家数据已在 quit 时由 unload* 落盘，此处无需处理。
     */
    private void flushOnlinePlayerData() {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            plugin.getPlayerDataStore().savePlayerData(online.getUniqueId());
            plugin.getPlayerDataStore().saveProfileSettings(online.getUniqueId());
        }
    }
}