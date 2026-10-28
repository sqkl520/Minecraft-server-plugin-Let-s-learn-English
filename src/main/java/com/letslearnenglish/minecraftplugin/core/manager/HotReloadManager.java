package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.RuntimeModules;
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
 *
 * <p><b>本次改动（/lea reload 动态装卸）</b>：{@code reloadAll()} 现在先做一次<b>状态迁移
 * 判定</b>（{@link RuntimeModules#classify(boolean, boolean)}），再决定「装配 / 拆解 / 只刷配置」
 * 三条路。{@code server.enabled} 的改动从此<b>不再需要重启</b>：改开 → reload 装运行时；
 * 改关 → reload 拆运行时。
 */
public class HotReloadManager {

    /**
     * {@code /lea reload} 的结局，供命令层映射文案。
     *
     * <p><b>为什么不返回 boolean</b>：旧接口只有一个「成功/失败」维度，而 reload 现在有
     * <b>五种</b>有效结局（其中 FULL / CONFIG_ONLY / ENABLED / DISABLED 都是成功），
     * 用 boolean 必然把至少两种成功折叠成同一个提示 —— 上一轮「绿字说已重新加载、
     * 玩家却发现功能没恢复」的谎报正是这么来的。枚举让命令层无法「忘记区分」。
     */
    public enum ReloadResult {
        /** 运行时原本就装着，配置也仍然开着：完整热重载（AI / 词库 / 玩家数据 / 音效 / 统计服务）。 */
        FULL,
        /** 运行时未装配（关闭态）：只把 config / messages / 词库路径读进内存，不装任何运行时。 */
        CONFIG_ONLY,
        /** 本次 reload 把 {@code server.enabled} 从 false 改成 true：已动态装配运行时。 */
        ENABLED,
        /** 本次 reload 把 {@code server.enabled} 从 true 改成 false：已动态拆解运行时。 */
        DISABLED,
        /** 装配或拆解过程中抛了异常：装配路径已回滚到关闭态，或拆解路径半途中止，需看控制台。 */
        FAILED
    }

    private final LetsLearnEnglish plugin;
    private final ConfigManager configManager;

    public HotReloadManager(LetsLearnEnglish plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * Reload all plugin configurations and reinitialize dependent modules.
     *
     * <p>顺序：配置 + 语言文件（{@code reloadAllConfigs}）→ <b>状态迁移判定</b> →
     * （仅在「无迁移」时）AI 对话段（读刷新后的 mainConfig 与磁盘 ai_config.yml /
     * ai_scenarios.yml）→ 词库 → 在线玩家落盘与缓存清理 → 奖励/成就/音效 → 统计服务。
     * AI 段失败只打 WARNING，不阻断后续步骤。
     *
     * <p>H9：clearCache 前必须先对在线玩家落盘（savePlayerData + saveProfileSettings，
     * 与 onDisable 同一套公开方法），否则缓存里的单词掌握度/统计/隐私设置直接丢失。
     *
     * @return 本次 reload 的结局；命令层据此选文案（见 {@link ReloadResult}）
     */
    public ReloadResult reloadAll() {
        // reloadAllConfigs() 必须在分叉判定**之前**：classify 的第二个入参是
        // 「本次 reload 之后配置里 server.enabled 的值」，判据要读到新值，
        // 否则「false → true」会被判成 NONE_DISABLED，开关改了也不装配。
        configManager.reloadAllConfigs();

        // 基准用「运行时是否已装配」而不是「reload 前的配置值」，理由见
        // RuntimeModules.classify 的 javadoc：上一次 enableAll 半途失败时配置已是 true
        // 而运行时未装配，按配置值比较会永远判成「无变化」，从此再也装不起来。
        final RuntimeModules.Transition transition =
                RuntimeModules.classify(plugin.isRuntimeActive(), configManager.isServerEnabled());
        switch (transition) {
            case ENABLE -> {
                plugin.getLogger().info("server.enabled is now true — loading runtime modules"
                        + " (listeners / tasks / channels) via /lea reload...");
                try {
                    plugin.enableAll();
                    plugin.getLogger().info("Runtime modules loaded by hot reload.");
                    return ReloadResult.ENABLED;
                } catch (Throwable t) {
                    // enableAll() 内部已 try/catch → disableAll() → rethrow（半装配不能留着：
                    // 「监听器齐了但任务没排」比彻底关闭更难排查）。这里只把失败写清楚，
                    // 不吞掉事实 —— 命令层会回一条红色的 enable-failed。
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to load runtime modules after server.enabled was set to true"
                                    + " — rolled back to the disabled state; the plugin stays loaded"
                                    + " with config only. Fix the cause (e.g. statistics-server port"
                                    + " already in use, missing soft dependency) then run /lea reload"
                                    + " again.", t);
                    return ReloadResult.FAILED;
                }
            }
            case DISABLE -> {
                plugin.getLogger().info("server.enabled is now false — unloading runtime modules"
                        + " (listeners / tasks / channels) via /lea reload...");
                try {
                    plugin.disableAll();
                    plugin.getLogger().info("Runtime modules unloaded by hot reload.");
                    return ReloadResult.DISABLED;
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to unload runtime modules after server.enabled was set to false"
                                    + " — the plugin may be left partially torn down; restart the"
                                    + " server to reach a clean disabled state.", t);
                    return ReloadResult.FAILED;
                }
            }
            default -> {
                // NONE_ENABLED / NONE_DISABLED：状态未迁移，落到下面的 legacy 段。
            }
        }

        // ==== 以下为 legacy 段：只在「配置与运行时状态一致」时执行 ====
        //
        // 【关键裁决】ENABLE / DISABLE 分支必须 return，绝不能 fall through 到这里：
        //   1) 这里的每一步都作用在「马上要被丢弃的旧对象」或「刚新建的新对象」上 ——
        //      DISABLE 时旧对象已被 disableAll() 置空/关闭，ENABLE 时新对象刚由
        //      enableAll() 装配好并已读过最新配置，再刷一遍纯属浪费（部分还会重复落盘）；
        //   2) restartStatisticsServerIfNeeded() 是「先 stop 再 start」—— 落在 DISABLE
        //      分支会留出一个端口重绑窗口，甚至把刚拆掉的统计服务重新拉起来，直接违背
        //      server.enabled=false 的承诺；
        //   3) reloadWordBanks() 是裸调用 plugin.getWordManager().loadAllWordBanks()，
        //      **没有 null 守卫** —— DISABLE 分支跑它就是 NPE（wordManager 已被置空），
        //      而那个 NPE 会被命令层当成 reload 失败，服主会以为卸载没成功。
        if (plugin.getPlayerDataStore() == null) {
            // 运行时未装配：只保证配置值已刷新。
            // 红队 I 的 P1：日志曾硬编码 "server.enabled=false"，但此刻 reloadAllConfigs()
            // 已经把配置换成新值 —— 若本次恰好把开关从 false 改成 true，日志陈述会与事实相反
            //（配置 true、Manager 仍 null）。所以打印<b>实际读到的配置值</b>；
            // 本分支走到这里时 classify 已判定「无迁移」。可达组合只有一种：
            // 运行时未装配（NONE_DISABLED，配置 false）。NONE_ENABLED 不可能落到这里 ——
            // enableAll() 在**末尾**才置 runtimeActive=true，而 playerDataStore 在那之前
            // 就已赋值；disableAll() 先置 runtimeActive=false 再走 D10 置空 pds。所以
            // 「runtimeActive=true 且 pds=null」在两套流程里都不存在。
            plugin.getLogger().info("hot reload refreshed config files only — runtime modules are"
                    + " not loaded on this server (server.enabled="
                    + configManager.isServerEnabled()
                    + "). Set server.enabled=true and run /lea reload to load them.");
            return ReloadResult.CONFIG_ONLY;
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
        return ReloadResult.FULL;
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