package com.letslearnenglish.minecraftplugin;

import com.letslearnenglish.minecraftplugin.api.LetsLearnEnglishAPI;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager;
import com.letslearnenglish.minecraftplugin.core.GuiNavigationManager;
import com.letslearnenglish.minecraftplugin.core.ProxyMessenger;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.core.ai.ChatFeedbackManager;
import com.letslearnenglish.minecraftplugin.core.ai.TtsManager;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import com.letslearnenglish.minecraftplugin.core.manager.AdminCommandHandler;
import com.letslearnenglish.minecraftplugin.core.manager.CheckInManager;
import com.letslearnenglish.minecraftplugin.core.manager.DataExporter;
import com.letslearnenglish.minecraftplugin.core.manager.HotReloadManager;
import com.letslearnenglish.minecraftplugin.core.manager.ReportGenerator;
import com.letslearnenglish.minecraftplugin.core.manager.StatisticsHttpServer;
import com.letslearnenglish.minecraftplugin.core.onboarding.OnboardingManager;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.listener.ChatAnswerListener;
import com.letslearnenglish.minecraftplugin.listener.ChatHistoryListener;
import com.letslearnenglish.minecraftplugin.listener.ChatScenarioListener;
import com.letslearnenglish.minecraftplugin.listener.DuelChallengeListener;
import com.letslearnenglish.minecraftplugin.listener.DuelGUIListener;
import com.letslearnenglish.minecraftplugin.listener.DuelListener;
import com.letslearnenglish.minecraftplugin.listener.MainMenuListener;
import com.letslearnenglish.minecraftplugin.listener.MiscGUIListener;
import com.letslearnenglish.minecraftplugin.listener.PlayerJoinListener;
import com.letslearnenglish.minecraftplugin.listener.ScenarioSelectListener;
import com.letslearnenglish.minecraftplugin.listener.SocialListener;
import com.letslearnenglish.minecraftplugin.listener.WordLearningListener;
import com.letslearnenglish.minecraftplugin.expansion.LLEPapiExpansion;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import com.letslearnenglish.minecraftplugin.util.VersionAdapter;
import com.letslearnenglish.minecraftplugin.core.UpdateChecker;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class LetsLearnEnglish extends JavaPlugin {

    private static LetsLearnEnglish instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PlayerDataStore playerDataStore;
    private MessageUtil messageUtil;
    private VersionAdapter versionAdapter;

    private WordManagerImpl wordManager;
    private AIChatManager aiChatManager;
    private ChatFeedbackManager chatFeedbackManager;
    private ProgressManagerImpl progressManager;

    private AdminCommandHandler adminCommandHandler;
    private DataExporter dataExporter;
    private HotReloadManager hotReloadManager;

    // US-RPT-3（域 6 家长报告）：/lea report 的报告生成器（构造注入 db/word 依赖）
    private ReportGenerator reportGenerator;

    private LetsLearnEnglishAPI api;

    private UpdateChecker updateChecker;

    private WordLearningManager wordLearningManager;
    private AchievementManager achievementManager;

    private SoundManager soundManager;
    private TtsManager ttsManager;
    private ProxyMessenger proxyMessenger;

    private ChatScenarioListener chatScenarioListener;

    /** F3-01 收口：命令入口榜单打开需经 SocialListener 登记快照（getter 供 LearnCommand）。 */
    private SocialListener socialListener;

    private GuiNavigationManager guiNavigationManager;

    private DailyChallengeManager dailyChallengeManager;

    private DuelManager duelManager;

    private CheckInManager checkInManager;

    private OnboardingManager onboardingManager;

    private SocialManager socialManager;

    /** Wave 3 域 5 批次 A：赛季系统（窗口计算/结算/奖励补发；isEnabled 门控默认关）。 */
    private SeasonManager seasonManager;

    private StatisticsHttpServer statisticsHttpServer;

    private Economy economy = null;

    private final Map<UUID, String> playerLanguage = new ConcurrentHashMap<>();

    /** R-75：自递归午夜 rollover 任务的当前引用（重载先 cancel 再重排，停用 cancel）。主线程访问。 */
    private BukkitTask midnightTask;

    /** R-86p：join 每日提醒去重 — 玩家 UUID → 最近发送的自然日（quit 不清，按日期懒判定）。 */
    private final Map<UUID, LocalDate> joinReminderSent = new ConcurrentHashMap<>();

    @Override
    public void onLoad() {
        instance = this;
        this.versionAdapter = new VersionAdapter(this);
        getLogger().info("Version adapter initialized for server version: "
                + getServer().getMinecraftVersion());
    }

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();

        this.configManager = new ConfigManager(this);
        this.configManager.loadAllConfigs();

        loadPlayerLanguages();

        this.messageUtil = new MessageUtil(this, configManager);

        // Phase 0 safety net: audit expected config & i18n keys (warnings only; never gates behaviour).
        this.configManager.validateRequiredKeys();

        this.databaseManager = new DatabaseManager(this, configManager);
        this.databaseManager.initialize();

        // Clean up old AI chat history (older than 30 days) asynchronously
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            databaseManager.cleanupOldChatHistory(30);
        });

        this.playerDataStore = new PlayerDataStore(this, databaseManager);

        this.wordManager = new WordManagerImpl(this, configManager, playerDataStore);
        this.wordLearningManager = new WordLearningManager(this);
        this.achievementManager = new AchievementManager(this, playerDataStore);
        this.aiChatManager = new AIChatManager(this);
        this.chatFeedbackManager = new ChatFeedbackManager(this, aiChatManager);
        this.chatScenarioListener = new ChatScenarioListener(this, aiChatManager);
        this.guiNavigationManager = new GuiNavigationManager();
        this.dailyChallengeManager = new DailyChallengeManager(this, wordManager);
        this.progressManager = new ProgressManagerImpl(this, playerDataStore);

        this.duelManager = new DuelManager(this);

        this.checkInManager = new CheckInManager(this);

        // 域 1 新手引导：自注册 PlayerInteractEvent（照 WordLearningManager 先例），
        // 无需改 registerListeners；位置在 registerListeners 之前（构造即完成注册）。
        this.onboardingManager = new OnboardingManager(this);

        // Wave 2 域 4（广场社交）：FeedCache 预热与周期刷新见下方启动异步块（startupWarmup）。
        this.socialManager = new SocialManager(this);

        // Wave 3 域 5 批次 A（赛季）：结算扫描在启动异步块挂载（messenger 已注册、
        // DB 已初始化）；isEnabled()（season.enabled 默认 false）守卫全部路径。
        this.seasonManager = new SeasonManager(this);

        this.adminCommandHandler = new AdminCommandHandler(this);
        this.dataExporter = new DataExporter(this, playerDataStore);
        this.reportGenerator = new ReportGenerator(this, databaseManager, wordManager);
        this.hotReloadManager = new HotReloadManager(this, configManager);

        this.soundManager = new SoundManager(this);

        this.ttsManager = new TtsManager(this);
        // Clean expired TTS audio cache asynchronously on startup
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            ttsManager.cleanExpiredCache();
        });

        // Proxy 消息通道（Velocity / BungeeCord 兼容）
        this.proxyMessenger = new ProxyMessenger(this);
        this.proxyMessenger.register();

        setupEconomy();

        setupPlaceholderAPI();

        this.api = new LetsLearnEnglishAPI(this);

        registerListeners();
        registerCommands();
        startScheduledTasks();

        // Load leaderboard from database and refresh caches
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            progressManager.getLeaderboard().loadFromDB();
            progressManager.getLeaderboard().refreshCache();
            // Wave 2 域 4：广场社交缓存预热 + 保留清理 + 周期刷新排程
            //（对齐 cleanupOldChatHistory 的启动异步模式）。
            socialManager.startupWarmup();
            // Wave 3 域 5 批次 A：赛季结算扫描（启服 catch-up；幂等，isEnabled 守卫）。
            // 挂此异步块 = 此时 messenger 已注册、DB 已初始化（plan-season §3.2）。
            seasonManager.settleIfNeededAsync();
        });

        // Statistics HTTP Dashboard
        if (configManager.getMainConfig().getBoolean("statistics-server.enabled", false)) {
            statisticsHttpServer = new StatisticsHttpServer(this, databaseManager);
            statisticsHttpServer.start();
        }

        long loadTime = System.currentTimeMillis() - startTime;
        getLogger().info("============================================");
        getLogger().info("  Let's Learn English! v" + getDescription().getVersion());
        getLogger().info("  Plugin enabled successfully! (Time: " + loadTime + "ms)");
        getLogger().info("============================================");

        // F0-08：checker 实例照常创建（PlayerJoinListener 会调用 notifyPlayer——未发起/未完成
        // 检查时其内部 isUpdateAvailable/checkComplete 守卫为 safe no-op，不会 NPE）；
        // 仅"发起联网检查"受 general.check-updates 控制，关=不连 GitHub API。
        this.updateChecker = new UpdateChecker(this);
        if (configManager.getMainConfig().getBoolean("general.check-updates", true)) {
            updateChecker.checkAsync();
        } else {
            getLogger().info("Update check disabled (general.check-updates=false).");
        }

        // R11：开服自检 —— 打印**生效值**而非配置值（Vault 是否真的挂钩、AI key 在不在、
        // MySQL 驱动是否随包、金币四道闸各自的解析结果、词库档数与词数、模块门）。
        // 仅当存在 FAIL 时打 WARNING 块，全绿只打一行 INFO；内部 catch(Throwable)，
        // 绝不中断 enable。玩家投诉"奖励没发"时，服主先看这里再看 /lea diagnose。
        com.letslearnenglish.minecraftplugin.command.DiagnoseCommand.logStartupSummary(this);
    }

    @Override
    public void onDisable() {
        getLogger().info("Saving player data...");

        // R-75：自递归午夜任务在停用时取消（下次 enable 会重新排程）。
        if (midnightTask != null) {
            midnightTask.cancel();
            midnightTask = null;
        }

        if (wordLearningManager != null) {
            wordLearningManager.clearAllSessions();
            getLogger().info("Cleared all active word learning sessions.");
        }

        // R-72：决斗房间清理（清任务/记分板/map，shutdown() 幂等）。
        // 放在数据落盘之前、会话清理之后：先清活动对局态，再进入持久化阶段；
        // try/catch 兜底任何清理异常，保证 onDisable 后续步骤（落盘/关库）不被拖垮。
        if (duelManager != null) {
            try {
                duelManager.shutdown();
            } catch (Throwable t) {
                getLogger().warning("Failed to shut down duel manager: " + t.getMessage());
            }
        }

        savePlayerLanguages();

        if (playerDataStore != null) {
            getServer().getOnlinePlayers().forEach(p -> {
                playerDataStore.savePlayerData(p.getUniqueId());
                playerDataStore.saveProfileSettings(p.getUniqueId());
            });
        }

        if (achievementManager != null) {
            achievementManager.clearCache();
        }

        if (proxyMessenger != null) {
            proxyMessenger.unregister();
        }

        if (ttsManager != null) {
            ttsManager.shutdown();
        }

        if (aiChatManager != null) {
            aiChatManager.shutdown();
        }

        if (statisticsHttpServer != null && statisticsHttpServer.isRunning()) {
            statisticsHttpServer.stop();
        }

        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("Let's Learn English! plugin has been safely disabled.");
    }

    /**
     * R9-M1：reload 后按最新配置重启统计服务（enabled 切换/端口/认证改动生效）。
     * 幂等：先停（isRunning 防御）再按最新配置建/启；enabled=false 时仅停并置空。
     * 异常自吞（不阻断 reload 主流程）；由 HotReloadManager.reloadAll 尾部调用。
     */
    public void restartStatisticsServerIfNeeded() {
        if (statisticsHttpServer != null && statisticsHttpServer.isRunning()) {
            statisticsHttpServer.stop();
        }
        boolean enabled = configManager.getMainConfig()
                .getBoolean("statistics-server.enabled", false);
        if (!enabled) {
            statisticsHttpServer = null;
            return;
        }
        try {
            statisticsHttpServer = new StatisticsHttpServer(this, databaseManager);
            statisticsHttpServer.start();
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "Failed to restart statistics HTTP server after reload", e);
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new MainMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new WordLearningListener(this), this);
        getServer().getPluginManager().registerEvents(new ScenarioSelectListener(this), this);
        // Phase 3 · MC-4：个人场景管理页（标题等值 + 尺寸双重确认，独立于
        // ScenarioSelectListener 的 contains 判定）
        getServer().getPluginManager().registerEvents(
                new com.letslearnenglish.minecraftplugin.listener.PersonalScenarioListener(this), this);
        this.socialListener = new SocialListener(this);
        getServer().getPluginManager().registerEvents(socialListener, this);
        getServer().getPluginManager().registerEvents(new MiscGUIListener(this), this);
        getServer().getPluginManager().registerEvents(chatScenarioListener, this);
        getServer().getPluginManager().registerEvents(new ChatAnswerListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatHistoryListener(this), this);
        getServer().getPluginManager().registerEvents(new DuelGUIListener(this), this);
        getServer().getPluginManager().registerEvents(new DuelChallengeListener(this), this);
        getServer().getPluginManager().registerEvents(new DuelListener(this), this);
        getServer().getPluginManager().registerEvents(new com.letslearnenglish.minecraftplugin.listener.WrongBookListener(this), this);
        // Wave 4 · US-CLS-2：班级榜（标题锚定前缀 + 尺寸双重确认）
        getServer().getPluginManager().registerEvents(new com.letslearnenglish.minecraftplugin.listener.ClassBoardListener(this), this);
        getServer().getPluginManager().registerEvents(soundManager, this);
    }

    private void registerCommands() {
        var learnCmd = getCommand("learnenglish");
        if (learnCmd != null) {
            learnCmd.setExecutor(new com.letslearnenglish.minecraftplugin.command.LearnCommand(this));
        }
        var adminCmd = getCommand("learnadmin");
        if (adminCmd != null) {
            adminCmd.setExecutor(new com.letslearnenglish.minecraftplugin.command.AdminCommand(this));
        }
    }

    private void startScheduledTasks() {
        // R10-M3：钳位 [1,60]（照 update-interval 先例）。0/负数会退化为 1 tick
        // 保存风暴（CraftScheduler 周期 0 钳到 1）；超出范围钳位并告警，
        // 不引入"0=禁用"新语义（注释声称范围 1-60，歧义应消除而非另赋意义）。
        int saveInterval = configManager.getMainConfig().getInt("general.auto-save-interval", 5);
        int clamped = Math.max(1, Math.min(60, saveInterval));
        if (clamped != saveInterval) {
            getLogger().warning("general.auto-save-interval=" + saveInterval
                    + " out of range [1,60], clamped to " + clamped + ".");
            saveInterval = clamped;
        }
        getServer().getScheduler().runTaskTimer(this, () -> {
            List<UUID> onlinePlayerIds = getServer().getOnlinePlayers().stream()
                    .map(Player::getUniqueId)
                    .toList();
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                // R7-C（P1-1）：周期保存走增量 saveDirty —— 只 UPSERT 脏词 + player_data 行，
                // 空闲玩家零 SQL（R7-A 的 saveDirty 此前已写好但无调用点，"写放大"实际没降）。
                // 三处"确保落盘"的窗口（onDisable / unloadPlayerData / reload flush）仍用全量
                // savePlayerData，这是 R7-A 的既定语义（见 PlayerDataStore:savePlayerData javadoc）。
                // 顺序上必须先于本接线修掉 P1-3（快照在锁外取），否则会激活旧值覆盖竞态。
                onlinePlayerIds.forEach(playerId -> playerDataStore.saveDirty(playerId));
                onlinePlayerIds.forEach(playerId -> playerDataStore.saveProfileSettings(playerId));
            });
        }, saveInterval * 1200L, saveInterval * 1200L);

        if (configManager.getMainConfig().getBoolean("word-system.review.enabled", true)) {
            getServer().getScheduler().runTaskTimer(this, () -> {
                wordManager.getReviewScheduler().checkAndNotifyReviews();
            }, 6000L, 6000L);
        }

        if (configManager.getMainConfig().getBoolean("daily-reminder.enabled", true)) {
            scheduleDailyReminders();
        }

        // M8：午夜跨天（每日挑战重分配 + 已领标记/复习提醒态清理）
        scheduleMidnightRollover();

        if (configManager.getMainConfig().getBoolean("general.backup.enabled", true)) {
            scheduleDatabaseBackup();
        }

        // Leaderboard cache refresh（Wave 3 域 5：赛季榜快照同乘此班车；结算完成后另有即时重刷）
        // PF-MC-06 终案（Wave 4 收尾）：周期接线 progress-system.leaderboard.update-interval
        // （秒，默认 300 = 原硬编码 5 分钟零漂移；钳位 [30, 3600]；周期改动需重启，
        // reload 不重排任务属既有语义）。原注释 "every 5 minutes" 与 6000L（ticks）对齐。
        int refreshSeconds = configManager.getMainConfig()
                .getInt("progress-system.leaderboard.update-interval", 300);
        refreshSeconds = Math.max(30, Math.min(3600, refreshSeconds));
        long refreshTicks = refreshSeconds * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            progressManager.getLeaderboard().refreshCache();
            if (seasonManager != null) {
                seasonManager.refreshCacheAsync();
            }
        }, refreshTicks, refreshTicks);
    }

    private void scheduleDailyReminders() {
        List<Integer> reminderHours = configManager.getMainConfig().getIntegerList("daily-reminder.reminder-hours");
        if (reminderHours.isEmpty()) {
            getLogger().warning("Daily reminder is enabled but no reminder-hours configured.");
            return;
        }

        Set<Integer> remindedHours = new HashSet<>();
        final Set<Integer> effectiveHours = new HashSet<>(reminderHours);

        getServer().getScheduler().runTaskTimer(this, () -> {
            // R10-M1：reload 不重建任务，体内点读门是唯一保障（对齐 ReviewScheduler 先例）。
            if (!configManager.getMainConfig().getBoolean("daily-reminder.enabled", true)) return;
            Calendar cal = Calendar.getInstance();
            int currentHour = cal.get(Calendar.HOUR_OF_DAY);
            int currentMinute = cal.get(Calendar.MINUTE);

            if (currentHour == 0 && currentMinute == 0) {
                remindedHours.clear();
            }

            if (currentMinute == 0 && effectiveHours.contains(currentHour) && !remindedHours.contains(currentHour)) {
                remindedHours.add(currentHour);
                // R-86p：逐玩家组装（{player}/{prefix} 全量替换），而非全员发同一份文本。
                LocalDate today = LocalDate.now();
                for (Player player : getServer().getOnlinePlayers()) {
                    // R1/R4：玩家侧提醒总开关（服务器 config 仍是总闸，这里只能再收紧）。
                    // 注意：关闭提醒者不写 joinReminderSent —— 否则会占掉"当日已提醒"位，
                    // 玩家中途打开开关后当天也收不到，属状态污染。
                    if (!playerDataStore.getProfileSettingsReadOnly(player.getUniqueId())
                            .isRemindersEnabled()) {
                        continue;
                    }
                    player.sendMessage(buildDailyReminderMessage(player));
                    // R-86p：共享去重 map——定时提醒已覆盖的玩家，当日 join 不再重复发。
                    joinReminderSent.put(player.getUniqueId(), today);
                }
                getLogger().info("Daily study reminder sent to all online players at hour " + currentHour + ":00");
            }
        }, 1200L, 1200L);

        getLogger().info("Daily study reminder scheduled at hours: " + effectiveHours);
    }

    /**
     * R-86p：join 时的每日学习提醒（S5 的 PlayerJoinListener 调用此冻结接口）。
     *
     * <p>条件：{@code daily-reminder.enabled} 且 {@code daily-reminder.on-join} 均为 true。
     * 行为与定时提醒共用同一文案组装逻辑（{@link #buildDailyReminderMessage(Player)}），
     * 只发给该玩家；同一玩家同一自然日只发一次（{@link #joinReminderSent} 去重，
     * quit 不清、按日期懒判定）。定时提醒发全员时也写该 map——两条通道共享去重：
     * 当日定时提醒已覆盖的在线玩家，之后 join 不再重复收到。
     */
    public void sendJoinDailyReminderIfEnabled(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!configManager.getMainConfig().getBoolean("daily-reminder.enabled", true)) {
            return;
        }
        if (!configManager.getMainConfig().getBoolean("daily-reminder.on-join", true)) {
            return;
        }
        // R1/R4：玩家侧提醒总开关 —— 放在去重 map 写入之前，避免关闭提醒的玩家
        // 占掉当日去重位（否则他当天打开开关也收不到第一条）。
        if (!playerDataStore.getProfileSettingsReadOnly(player.getUniqueId()).isRemindersEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDate last = joinReminderSent.get(player.getUniqueId());
        if (today.equals(last)) {
            return;
        }
        joinReminderSent.put(player.getUniqueId(), today);
        player.sendMessage(buildDailyReminderMessage(player));
    }

    /**
     * R-86p：每日提醒文案组装（定时提醒与 join 提醒共用）。
     * 模板来自 config.yml 的 {@code daily-reminder.message}（既定架构：该键是 config 不是 i18n），
     * {prefix} 按 {@code general.language} 取前缀，{player} 替换为玩家名。
     */
    private String buildDailyReminderMessage(Player player) {
        String rawMessage = configManager.getMainConfig().getString("daily-reminder.message",
                "{prefix}&6It's time to learn English! Use &e/le menu &6to start!");
        String defaultLang = configManager.getMainConfig().getString("general.language", "zh");
        String prefix = configManager.getMainConfig().getString("prefix." + defaultLang, "&8[&b英语学习&8] &f");
        String message = rawMessage
                .replace("{player}", player.getName())
                .replace("{prefix}", prefix);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void scheduleDatabaseBackup() {
        int intervalMinutes = configManager.getMainConfig().getInt("general.backup.interval", 1440);
        if (intervalMinutes <= 0) {
            getLogger().info("Database auto-backup is disabled (interval set to 0).");
            return;
        }

        int maxBackups = configManager.getMainConfig().getInt("general.backup.max-backups", 7);
        long intervalTicks = intervalMinutes * 1200L;

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            backupDatabase(maxBackups);
        }, intervalTicks, intervalTicks);

        getLogger().info("Database auto-backup scheduled every " + intervalMinutes
                + " minutes, keeping up to " + maxBackups + " backups.");
    }

    /**
     * M8：午夜跨天任务入口。
     *
     * <p>R-75：不再用 runTaskTimer 固定 24h 周期（DST 切换后偏离午夜、停机后丢一次）。
     * 改为自递归的一次性延迟任务：每次排程重新按 Calendar 对齐到"次日 0:00:01"计算 delay，
     * 任务体执行完 rollover 后自行重排下一次。重载/重复调用时先 cancel 旧任务再排新
     * （{@link #midnightTask} 实例字段持引用），onDisable 亦会 cancel。
     *
     * <p>停机 catch-up：不做（保持现状语义）——服务器停机跨过午夜时该次 rollover
     * 不补跑；玩家 join 时 PlayerJoinListener 的 {@code dailyChallengeManager.checkAndAssign}
     * 兜底分配当日挑战（昨日未领奖励按 challenge_date 天然隔离作废，只是无 rollover 提示）。
     */
    private void scheduleMidnightRollover() {
        if (midnightTask != null) {
            midnightTask.cancel();
            midnightTask = null;
        }
        scheduleNextMidnightRollover();
    }

    /**
     * R-75：排程下一次午夜 rollover（自递归调度）。
     * delay 按 Calendar 对齐到次日 0:00:01（墙钟语义，DST 切换日自动正确）；
     * 任务体执行现有全部 rollover 逻辑，末尾再调本方法续排下一天。
     */
    private void scheduleNextMidnightRollover() {
        if (!isEnabled()) {
            return;
        }
        Calendar now = Calendar.getInstance();
        Calendar nextMidnight = (Calendar) now.clone();
        nextMidnight.add(Calendar.DAY_OF_MONTH, 1);
        nextMidnight.set(Calendar.HOUR_OF_DAY, 0);
        nextMidnight.set(Calendar.MINUTE, 0);
        nextMidnight.set(Calendar.SECOND, 1);
        nextMidnight.set(Calendar.MILLISECOND, 0);
        long delayTicks = Math.max(1200L,
                (nextMidnight.getTimeInMillis() - now.getTimeInMillis()) / 50L);

        midnightTask = getServer().getScheduler().runTaskLater(this, () -> {
            // F2-11 收口（两段式）：重活（昨日作废计数/今日分配 = DB + 词库读）移入异步段；
            // R-31 补发与发消息（hasPendingWordRewardForDate 链含 Bukkit.getPlayer/
            // Vault 发币/sendMessage）留在主线程段。原实现逐玩家同步查库串行在主线程，
            // 百人服午夜卡顿（dev-findings F2-11）。
            // 顺序重排说明：checkAndAssign 只碰"今日"行 + 词库（异步安全）；
            // countUnclaimedForDate 只读"昨日"行；hasPendingWordRewardForDate 碰"昨日"
            // 行 + 内存补发标记——三者集合不相交，重排无语义漂移。
            final String yesterday = LocalDate.now().minusDays(1).toString();
            final List<UUID> onlineIds = new ArrayList<>();
            for (Player p : getServer().getOnlinePlayers()) {
                onlineIds.add(p.getUniqueId());
            }
            try {
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    // 异步段：昨日作废计数 + 今日分配（线程干净已逐一核实）
                    final Map<UUID, Integer> voidedBehaviorByPlayer = new HashMap<>();
                    try {
                        if (!isEnabled()) return;
                        for (UUID id : onlineIds) {
                            voidedBehaviorByPlayer.put(id,
                                    dailyChallengeManager.countUnclaimedForDate(id, yesterday));
                            dailyChallengeManager.checkAndAssign(id);
                        }
                    } catch (Exception e) {
                        getLogger().log(java.util.logging.Level.WARNING,
                                "Midnight rollover async phase failed", e);
                    }
                    // R7（战略锐评）长 uptime 缺口修复：原 cleanupOldChatHistory(30) 全库
                    // 唯一调用点在 onEnable，不重启的服 ai_chat_history / ai_chat_feedback /
                    // ai_round_counters 只增不清。现挂入本午夜异步段（每日一次）；启动期那次
                    // 保留（首启立即清，不等到午夜）。keepDays 硬编码 30 与启动期一致，
                    // 不新增 config 键（配置面已过大）。
                    // 位置：在派发主线程段之前跑完。DatabaseManager 单连接以 connectionLock
                    // 串行，若清理与下方主线程段的逐玩家查库并发，主线程会等锁数秒（F2-11
                    // 同类问题）；先清完再派发 = 两者不重叠，主线程零等待。
                    // 性能：全表扫删（timestamp / created_at / round_date 无索引），表很大时
                    // 首次执行可能耗时数秒——本段在异步线程，不阻塞 tick，可接受。
                    // 异常：cleanupOldChatHistory 内部已逐条 catch SQLException 并记 WARNING；
                    // 此处再兜一层 RuntimeException，清理失败绝不中断后续 rollover 与自续链。
                    if (isEnabled()) {
                        try {
                            databaseManager.cleanupOldChatHistory(30);
                        } catch (Exception e) {
                            getLogger().log(java.util.logging.Level.WARNING,
                                    "Midnight AI chat history cleanup failed", e);
                        }
                    }
                    if (!isEnabled()) return;
                    try {
                        getServer().getScheduler().runTask(this, () -> {
                            // 主线程段：R-31 补发（含 Bukkit 调用）+ 发消息 + 内存清理 + 自续
                            try {
                                for (UUID id : onlineIds) {
                                    Player player = getServer().getPlayer(id);
                                    if (player == null || !player.isOnline()) continue;
                                    boolean voidedWord = dailyChallengeManager
                                            .hasPendingWordRewardForDate(id, yesterday);
                                    int voidedBehavior = voidedBehaviorByPlayer.getOrDefault(id, 0);
                                    if (voidedBehavior > 0 || voidedWord) {
                                        player.sendMessage(messageUtil.getPlayerMessage(player,
                                                "daily-challenge.rollover-voided"));
                                    }
                                }
                                dailyChallengeManager.clearAllWordChallengeClaims();
                                wordManager.getReviewScheduler().clearNotifiedDates();
                                // R10-L3/R13-M4：跨日后旧日期条目一律失效（当日去重语义不受影响）。
                                // 注：下方赛季段内另有名为 today 的局部变量（同 lambda 嵌套块），
                                // 故本处用独立变量名，避免重复声明/遮蔽冲突。
                                LocalDate rolloverToday = LocalDate.now();
                                joinReminderSent.values().removeIf(d -> !rolloverToday.equals(d));

                                // Wave 3 域 5 批次 A：午夜跨季检测（纯日期推导零 IO，F2-11 教训：
                                // 不在主线程查库）。仅当"跨过季边界"才派发结算扫描；结算 IO 全在
                                // settleIfNeededAsync 的异步任务里，主线程只做这一次纯计算。
                                if (seasonManager != null) {
                                    SeasonManager sm = seasonManager;
                                    LocalDate today = LocalDate.now();
                                    int todaySeason = sm.currentSeasonId(today);
                                    int yesterdaySeason = sm.currentSeasonId(today.minusDays(1));
                                    if (todaySeason != yesterdaySeason) {
                                        getLogger().info("Season boundary crossed (" + yesterdaySeason
                                                + " -> " + todaySeason + "), dispatching settlement sweep.");
                                        sm.settleIfNeededAsync();
                                    }
                                }

                                getLogger().info("Midnight rollover done: daily challenges reassigned.");
                            } finally {
                                // R-75 自续：放在 finally——即使本次 rollover 抛异常，也照旧重排
                                // 下一天，保证调度链路不会静默中断（与旧 runTaskTimer 的
                                // "周期性必然再触发"语义对齐）。异步段异常路径也汇入本段（见上）。
                                scheduleNextMidnightRollover();
                            }
                        });
                    } catch (IllegalStateException ignored) {
                        // 插件停用窗口（调度器已关）：两段链终止。isEnabled 已为 false，
                        // 自续本就不会重排（scheduleNextMidnightRollover 首行守卫）。
                    }
                });
            } catch (IllegalStateException ignored) {
                // 停用窗口：异步段未派发，无自续需求（同上）。
            }
        }, delayTicks);

        getLogger().info("Midnight rollover scheduled, next run in "
                + (delayTicks / 20L) + " seconds.");
    }

    private void backupDatabase(int maxBackups) {
        String dbType = configManager.getMainConfig().getString("database.type", "sqlite");
        if (!"sqlite".equalsIgnoreCase(dbType)) {
            getLogger().info("Database backup skipped: only SQLite backup is supported.");
            return;
        }

        String dbFileName = configManager.getMainConfig().getString("database.sqlite.filename", "playerdata.db");
        File dbFile = new File(getDataFolder(), dbFileName);
        if (!dbFile.exists()) {
            getLogger().warning("Database file not found for backup: " + dbFile.getAbsolutePath());
            return;
        }

        File backupDir = new File(getDataFolder(), "backups");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File backupFile = new File(backupDir, dbFileName + "." + timestamp + ".bak");

        try {
            // VACUUM INTO：SQLite 3.27+ 原子一致快照（替代"wal_checkpoint(TRUNCATE)
            // + Files.copy"——原 PRAGMA 对非 WAL 库是空操作、拷贝在锁外可撕裂；
            // R3-H1）。路径为内部构造（数据目录+时间戳），单引号转义防御。
            // R6-D：加锁/取连接/转义整体上收到 DatabaseManager.backupTo（逻辑逐字保留）。
            databaseManager.backupTo(backupFile.getAbsolutePath());
            // 独立只读连接校验产物（原实现查的是活连接=查错对象，R3-H1 第二修）
            boolean ok = databaseManager.checkBackupIntegrity(backupFile);
            if (!ok) {
                Files.deleteIfExists(backupFile.toPath());
                getLogger().warning("Database backup rejected: backup quick_check failed.");
                return;
            }
            getLogger().info("Database backup created: " + backupFile.getName());
        } catch (IOException | SQLException e) {
            // V2 红队加固：VACUUM INTO 中途失败（磁盘满等）可能留部分写入产物——
            // 尽力清理，防残缺文件被 retention 当有效备份留存。
            try {
                Files.deleteIfExists(backupFile.toPath());
            } catch (IOException ignored) {
            }
            getLogger().warning("Failed to create database backup: " + e.getMessage());
            return;
        }

        File[] backupFiles = backupDir.listFiles((dir, name) -> name.startsWith(dbFileName) && name.endsWith(".bak"));
        if (backupFiles != null && backupFiles.length > maxBackups) {
            java.util.Arrays.sort(backupFiles, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int toDelete = backupFiles.length - maxBackups;
            for (int i = 0; i < toDelete; i++) {
                if (backupFiles[i].delete()) {
                    getLogger().info("Old backup deleted: " + backupFiles[i].getName());
                }
            }
        }
    }

    // R6-D：原 private checkBackupIntegrity(File) 已上收为
    // DatabaseManager.checkBackupIntegrity(File)（新增显式 Class.forName("org.sqlite.JDBC")），
    // 本类不再保留副本，避免两处 quick_check 逻辑漂移。

    public static LetsLearnEnglish getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * R-P2-1：诊断命令需要读"HTTP 服务是否真的在跑"，而不是配置里的 enabled。
     * 绑定失败（端口被占）时 {@code start()} 会置 {@code running=false}，配置却仍是 true——
     * 只报配置值会让服主看到绿灯却打不开面板。
     *
     * @return 统计服务实例；模块关闭时可能为 {@code null}
     */
    public StatisticsHttpServer getStatisticsHttpServer() {
        return statisticsHttpServer;
    }

    public PlayerDataStore getPlayerDataStore() {
        return playerDataStore;
    }

    public MessageUtil getMessageUtil() {
        return messageUtil;
    }

    public VersionAdapter getVersionAdapter() {
        return versionAdapter;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public WordLearningManager getWordLearningManager() {
        return wordLearningManager;
    }

    public AchievementManager getAchievementManager() {
        return achievementManager;
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
            getLogger().info("Vault economy integration enabled.");
        }
    }

    public Economy getEconomy() {
        return economy;
    }

    private void setupPlaceholderAPI() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        LLEPapiExpansion expansion = new LLEPapiExpansion(this);
        if (expansion.canRegister()) {
            expansion.register();
            getLogger().info("PlaceholderAPI expansion registered. Available: %lle_score%, %lle_rank%, etc.");
        }
    }

    public WordManagerImpl getWordManager() {
        return wordManager;
    }

    public AIChatManager getAIChatManager() {
        return aiChatManager;
    }

    /** Phase 3 US-FB：会话小结/AI 点评编排（依赖 AIChatManager 的 HTTP 栈单源）。 */
    public ChatFeedbackManager getChatFeedbackManager() {
        return chatFeedbackManager;
    }

    public ChatScenarioListener getChatScenarioListener() {
        return chatScenarioListener;
    }

    /** F3-01 收口：命令入口榜单打开的快照登记入口（见 SocialListener#openLeaderboardWithSnapshot）。 */
    public SocialListener getSocialListener() {
        return socialListener;
    }

    public ProgressManagerImpl getProgressManager() {
        return progressManager;
    }

    public AdminCommandHandler getAdminCommandHandler() {
        return adminCommandHandler;
    }

    public DataExporter getDataExporter() {
        return dataExporter;
    }

    /** US-RPT-3（域 6）：/lea report 的报告生成器（未启用前为 null，命令侧不触碰）。 */
    public ReportGenerator getReportGenerator() {
        return reportGenerator;
    }

    public HotReloadManager getHotReloadManager() {
        return hotReloadManager;
    }

    public LetsLearnEnglishAPI getAPI() {
        return api;
    }

    public SoundManager getSoundManager() {
        return soundManager;
    }

    public TtsManager getTtsManager() {
        return ttsManager;
    }

    public GuiNavigationManager getGuiNavigationManager() {
        return guiNavigationManager;
    }

    public DailyChallengeManager getDailyChallengeManager() {
        return dailyChallengeManager;
    }

    public DuelManager getDuelManager() {
        return duelManager;
    }

    public CheckInManager getCheckInManager() {
        return checkInManager;
    }

    public OnboardingManager getOnboardingManager() {
        return onboardingManager;
    }

    /** Wave 2 域 4：广场社交管理器（GUI/Listener/成就接线四处消费）。 */
    public SocialManager getSocialManager() {
        return socialManager;
    }

    /** Wave 3 域 5 批次 A：赛季管理器（ProxyMessenger/join/3A-4 展示消费）。 */
    public SeasonManager getSeasonManager() {
        return seasonManager;
    }

    public ProxyMessenger getProxyMessenger() {
        return proxyMessenger;
    }

    public String getPlayerLanguage(Player player) {
        return playerLanguage.getOrDefault(player.getUniqueId(),
                configManager.getMainConfig().getString("general.language", "zh"));
    }

    public void setPlayerLanguage(Player player, String lang) {
        playerLanguage.put(player.getUniqueId(), lang);
        savePlayerLanguages();
    }

    private void loadPlayerLanguages() {
        File langFile = new File(getDataFolder(), "player_lang.yml");
        if (!langFile.exists()) {
            return;
        }
        FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
        // T0-04：缺省与 getPlayerLanguage 同源（config general.language，默认 zh），
        // 旧值 "en" 会在玩家条目只有 UUID 无值时才命中，属不一致的隐性回归。
        String defaultLang = configManager.getMainConfig().getString("general.language", "zh");
        for (String key : langConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                playerLanguage.put(uuid, langConfig.getString(key, defaultLang));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID in player_lang.yml: " + key);
            }
        }
        getLogger().info("Loaded " + playerLanguage.size() + " player language preferences.");
    }

    private void savePlayerLanguages() {
        File langFile = new File(getDataFolder(), "player_lang.yml");
        FileConfiguration langConfig = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : playerLanguage.entrySet()) {
            langConfig.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            langConfig.save(langFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save player language preferences: " + e.getMessage());
        }
    }
}
