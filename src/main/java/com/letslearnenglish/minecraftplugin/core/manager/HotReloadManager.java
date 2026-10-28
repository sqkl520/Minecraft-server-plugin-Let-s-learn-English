package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.entity.Player;

import java.util.logging.Level;

/**
 * Hot Reload Manager
 *
 * Handles hot-reloading of all plugin configurations without server restart.
 *
 * <p>P0-3：除 config.yml / messages/*.yml / 词库 / 音效 / 统计服务外，AI 对话段也在此一并
 * 重载——{@code ai_config.yml} 的 {@code api-key} 与 config.yml 的 {@code ai-chat.*} /
 * {@code scenario.max-prompt-length} / {@code ai_scenarios.yml}。即改完 Key 执行
 * {@code /lea reload} 即可生效，无需重启服务器。
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
     * <p>顺序：配置 + 语言文件（{@code reloadAllConfigs}）→ AI 对话段（读刷新后的 mainConfig
     * 与磁盘 ai_config.yml / ai_scenarios.yml）→ 词库 → 在线玩家落盘与缓存清理 →
     * 奖励/成就/音效 → 统计服务。AI 段失败只打 WARNING，不阻断后续步骤。
     *
     * H9：clearCache 前必须先对在线玩家落盘（savePlayerData + saveProfileSettings，
     * 与 onDisable 同一套公开方法），否则缓存里的单词掌握度/统计/隐私设置直接丢失。
     */
    public void reloadAll() {
        configManager.reloadAllConfigs();
        // 子服总开关（server.enabled=false）：本方法在关闭态下**仍会被 /lea reload 调用**
        // （那是关闭态唯一放行的管理命令）。判据刻意用 `playerDataStore == null` 而不是
        // `!isServerEnabled()`——因为刚刚 reloadAllConfigs() 已经把配置换成新值，两种情形
        // 都需要这条守卫：
        //   a) 启动时就是 false：Manager 全为 null，下面每一步裸调用都会 NPE；
        //   b) 启动时 false、本次 reload 改成 true：配置读到 true，但 Manager 仍然全为 null
        //      （动态装配本轮不实现），同样会 NPE。
        // 反过来「启动时 true、本次改成 false」时 Manager 是齐的，走完整 reload 无害，
        // 也符合"reload 只保证配置值已更新"的承诺。见 config.yml 顶部注释。
        if (plugin.getPlayerDataStore() == null) {
            plugin.getLogger().info("server.enabled=false — hot reload refreshed config files only"
                    + " (no runtime managers are loaded on this server).");
            return;
        }
        // P0-3：AI 段依赖 reloadAllConfigs() 已刷新的 mainConfig（AIChatManager.loadConfig()
        // 直接读 getMainConfig() 的 ai-chat.* / scenario.max-prompt-length，并重读磁盘
        // ai_config.yml 的 api-key），故必须紧跟在配置重载之后。
        // 异常自吞只告警：AI 段失败不能阻断后续词库/玩家数据/音效等 reload（同下方音效段风格）。
        if (plugin.getAIChatManager() != null) {
            try {
                plugin.getAIChatManager().reload();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to hot-reload AI chat config (ai_config.yml 'api-key' / ai-chat.*)"
                                + " — AI settings may be left partially applied; check the file then"
                                + " run /lea reload again.", e);
            }
        }
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
        // R9-M1：统计服务配置为构造快照——按最新配置重启（enabled 切换/端口/认证生效）。
        // 方法本身幂等（isRunning 防御 + enabled=false 仅停置空），异常自吞，不阻断 reload。
        plugin.restartStatisticsServerIfNeeded();
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