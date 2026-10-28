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
import com.letslearnenglish.minecraftplugin.listener.GlobalGuiActionGuardListener;
import com.letslearnenglish.minecraftplugin.listener.MainMenuListener;
import com.letslearnenglish.minecraftplugin.listener.MiscGUIListener;
import com.letslearnenglish.minecraftplugin.listener.PlayerJoinListener;
import com.letslearnenglish.minecraftplugin.listener.ScenarioSelectListener;
import com.letslearnenglish.minecraftplugin.listener.SocialListener;
import com.letslearnenglish.minecraftplugin.listener.WordLearningListener;
import com.letslearnenglish.minecraftplugin.expansion.LLEPapiExpansion;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiIdentity;
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

        // 位置刻意保持在总开关判定**之前**：onDisable 会无条件调用 savePlayerLanguages()
        // 把内存里的 playerLanguage 全量写回 player_lang.yml。若关闭态下跳过这一步加载，
        // 内存表为空 → 停用瞬间会把玩家已选的语言偏好整文件清空。读文件本身零副作用。
        loadPlayerLanguages();

        this.messageUtil = new MessageUtil(this, configManager);

        // Phase 0 safety net: audit expected config & i18n keys (warnings only; never gates behaviour).
        this.configManager.validateRequiredKeys();

        this.databaseManager = new DatabaseManager(this, configManager);
        this.databaseManager.initialize();

        // ---------------- 子服总开关（server.enabled）----------------
        // 语义见 config.yml 顶部注释。关闭时**只**保留 configManager / messageUtil /
        // databaseManager 三件套，其余全部不构造、不注册、不排程：
        //   · 不构造 playerDataStore 与任何 Manager —— 顺带保证 WordLearningManager /
        //     OnboardingManager 这两个「构造即自注册监听器」的类不会偷偷注册事件；
        //   · 不注册 proxyMessenger —— 不向网络广播任何本插件消息；
        //   · 不排程任何周期任务 —— 零事件/零 tick 开销；
        //   · 不跑启动预热（榜单 / 广场 / 赛季结算 / 统计 HTTP）。
        // 保留 databaseManager 的理由：/lea reload 的收尾路径会用到它（HotReloadManager），
        // 且玩家数据在共享 MySQL 里由其它子服维护，本服不读不写也不影响。
        // 保留 registerCommands()：/lea reload 必须可用，否则服主改完开关只能重启。
        // 正因为放行了 reload，hotReloadManager 也必须在这里构造 —— 它只持有
        // plugin + configManager 两个引用、构造零副作用，而 AdminCommand 的 reload 分支是
        // `plugin.getHotReloadManager().reloadAll()` 的裸调用，不构造就是 NPE。它内部的
        // reloadAll 在 playerDataStore 为 null 时会短路成「只刷配置 + 语言文件」。
        // 其余 Manager 一律不构造（这也是 WordLearningManager / OnboardingManager 这类
        // 「构造即自注册监听器」的类在关闭态下不会偷偷注册事件的唯一保证）。
        // 注意判定位置：必须在 loadAllConfigs() 之后（才有配置可读），在
        // databaseManager.initialize() 之后（reload 需要可用连接）。
        if (!configManager.isServerEnabled()) {
            this.hotReloadManager = new HotReloadManager(this, configManager);
            registerCommands();
            getLogger().info("server.enabled=false — English learning is DISABLED on this server: "
                    + "no listeners, no scheduled tasks, no proxy channel, no startup warmup.");
            getLogger().info("Only '/lea reload' is available. Changing this key requires a full"
                    + " server restart (or a plugin reload) to take effect.");
            return;
        }

        enableFullFeatures(startTime);
    }

    /**
     * 完整功能启动路径（{@code server.enabled=true} 时由 {@link #onEnable()} 调用）。
     *
     * <p>本方法体是 {@code onEnable} 原有逻辑<b>逐字搬移</b>的结果（含全部注释），除
     * {@code startTime} 改为入参外无任何逻辑改动 —— 这样「子服总开关」那次改动的 diff
     * 只剩一行分支加一次方法抽取，审查时不需要重读一遍启动顺序。
     *
     * @param startTime {@code onEnable} 入口的计时起点，用于末尾打印加载耗时
     */
    private void enableFullFeatures(long startTime) {
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
        // B1（R12）：目标词注入接线 —— 只走零 DB 数据源。buildSystemPrompt 在主线程跑，
        // 所以这里绝不能用 getMasteredWords / loadPlayerData（缓存 miss 时会主线程同步
        // 查库并等 connectionLock）。两路数据源都是纯内存：
        //   1) getSuggestedFocusWords —— ChatFeedbackManager 的 ConcurrentHashMap（上一场纠错/推荐词，已按词库过滤）；
        //   2) getDueReviewWords —— ReviewScheduler 的 30s TTL 内存缓存（缓存内也是纯 map 读，且默认不启用，见 L6）。
        // 用 Function 注入而非直接持有 ChatFeedbackManager：本类先构造 aiChatManager 再构造
        // chatFeedbackManager，反向引用会成环。
        //
        // 决策体抽到 {@link #resolveFocusWords}（包级可见的纯函数）：L6 的开关语义
        // （默认不回落到复习词）必须能被纯 JVM 单测钉住，而 onEnable 整段依赖 Bukkit
        // server，跑不起来。lambda 保持一行转发，配置仍在<b>每次调用时</b>读取
        // （{@code getMainConfig()} 作为实参在调用点求值，reload 换对象后即刻生效）。
        this.aiChatManager.setFocusWordProvider(id ->
                resolveFocusWords(id, chatFeedbackManager, wordManager,
                        configManager != null ? configManager.getMainConfig() : null));
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
        // R5-15：这句是**整段停用流程**的入口标记（清会话 → 决斗收尾 → GUI 清扫 → 落盘
        // → 关库），不是"落盘马上开始"。真正的落盘完成另有 "Saved data for N player(s)."
        // 一行，两者不重复：前者标停用开始，后者报实际落盘人数（0 人时不打印）。
        getLogger().info("Disabling plugin - clearing sessions and saving player data...");

        // R-75：自递归午夜任务在停用时取消（下次 enable 会重新排程）。
        if (midnightTask != null) {
            midnightTask.cancel();
            midnightTask = null;
        }

        // R5-15：会话清理必须自带 try/catch —— 它是 onDisable 里第一个会碰 DB 的步骤。
        // 故障链：clearAllSessions() → endSession → PlayerDataStore.updateWordProgress /
        // getPlayerStats，缓存缺失时会走 loadPlayerData → DatabaseManager.getConnection()，
        // 而后者在取连接失败时 throw new RuntimeException("Failed to get database
        // connection", e)。没有这层兜底时，整段 onDisable 会就此中断，后面四件事全部跳过：
        //   1. duelManager.shutdown()（决斗房间/任务/记分板清理）
        //   2. GUI 清扫（closeIfPluginGui）—— 它是「holder 跨 ClassLoader 失效」这条裂缝的
        //      唯一堵漏手段，跳过就等于裂缝重新敞开，且是在热重载/重启这种最需要它的时刻
        //   3. savePlayerLanguages() 与 playerDataStore 全量落盘（玩家进度丢失）
        //   4. databaseManager.shutdown()（连接未正常关闭）
        // R5-15 补充：上面这四件事要**真的**都能跑到，只靠本处的 try/catch 不够 —— 每一步
        // 自己也得扛得住异常。所以落盘段另做了逐玩家 try/catch（见下方），否则本处注释就
        // 变成空头支票：会话清理兜住了，紧接着的落盘一抛，第 2、3、4 件照样跑不到。
        // 用 Throwable 而不是 Exception，照下面 duelManager.shutdown() 的既有先例：
        // 清理路径上真正要防的是"任何"异常，包括 LinkageError / NoClassDefFoundError 这类
        // 换 ClassLoader 或依赖缺失才会冒出来的 Error；catch(Exception) 漏掉它们的话，
        // 停用流程照样会被截断，兜底就名不副实。异常只记 WARNING、不重抛 —— onDisable
        // 没有上层可以补救，继续往下走（至少把 GUI 关掉、把数据落盘）才是最优解。
        if (wordLearningManager != null) {
            try {
                wordLearningManager.clearAllSessions();
                getLogger().info("Cleared all active word learning sessions.");
            } catch (Throwable t) {
                getLogger().warning("Failed to clear word learning sessions: " + t.getMessage());
            }
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

        // R5-9：关闭所有在线玩家身上残留的本插件 GUI —— 堵死「holder 跨 ClassLoader 失效」。
        //
        // 为什么必须有这一步：GUI 归属判定已迁到 holder（PluginGuiHolder）。holder 判定唯一
        // 的死穴是 ClassLoader 换代 —— PlugMan 之类热重载卸载本插件时会新建 ClassLoader，旧
        // 实例留在玩家客户端上的界面在新 ClassLoader 下 instanceof 恒为 false，holder 判定
        // 集体失效（R5-16 起标题层已整体删除，没有任何 fallback 可以兜住）。这类操作必然
        // 先调 onDisable()，所以在这里把界面关掉，就不存在「陈旧界面存活到新 ClassLoader」的
        // 窗口。注意 /lea reload 不换 ClassLoader（只是重载配置 + clearCache），因此 reload
        // 路径不关 GUI。
        //
        // 位置裁决（三处顺序都是刻意的）：
        //   1. 在 wordLearningManager.clearAllSessions() **之后** —— WordLearningListener
        //      .onInventoryClose 在 hasActiveQuiz=true && !isAnswering 时会调
        //      startAnswerForCurrentWord()（发答题提示 + startAnswerTimer）。会话已清空后
        //      该判定为 false，关闭事件 early return，不会在停用途中凭空起一个倒计时任务，
        //      也不会给即将断线的玩家推答题提示。反过来放前面则必触发。
        //   2. 在 duelManager.shutdown() **之后** —— 决斗收尾本身会 closeInventory() 并
        //      清 activeDuels；先让它按自己的顺序收尾（清任务/记分板/状态），我们这步只做
        //      兜底清扫，覆盖决斗界面之外的所有插件界面。若反过来，shutdown 里的
        //      removeScoreboard/closeInventory 会作用在已被关闭的界面上（虽幂等，但语义更乱）。
        //   3. 在 savePlayerLanguages()/savePlayerData **之前** —— 关闭界面会同步触发
        //      InventoryCloseEvent；当前唯一处理它的是 WordLearningListener（已被上一条约束
        //      挡成 no-op），其余 listener 不监听该事件。仍放在落盘之前，是为了让「关界面可能
        //      引发的任何内存态变化」都发生在全量保存之前，而不是落在落盘之后丢失。
        // 整段 try/catch(Throwable) 兜底，照 duelManager.shutdown() 的先例：一个玩家关闭失败
        // 不能拖垮后续的落盘与关库。
        try {
            int closedPluginGuis = 0;
            for (Player online : getServer().getOnlinePlayers()) {
                try {
                    if (GuiIdentity.closeIfPluginGui(online)) {
                        closedPluginGuis++;
                    }
                } catch (Throwable t) {
                    getLogger().warning("Failed to close plugin GUI for " + online.getName()
                            + " during shutdown: " + t.getMessage());
                }
            }
            if (closedPluginGuis > 0) {
                getLogger().info("Closed " + closedPluginGuis
                        + " lingering plugin GUI(s) before shutdown.");
            }
        } catch (Throwable t) {
            getLogger().warning("Failed to sweep plugin GUIs during shutdown: " + t.getMessage());
        }

        savePlayerLanguages();

        // R5-15：落盘必须**逐玩家**独立 try/catch，形状照上面的 GUI 清扫段。
        //
        // 为什么不用整段 try、也不沿用原来的 forEach lambda（原来连一层 try 都没有）：
        //   a) 原实现是 forEach + lambda，任何一条抛异常都会中断**剩余所有玩家**的保存 ——
        //      第一个玩家失败，后面 N-1 个玩家的进度全部丢失。逐玩家 catch 才能把损失
        //      限制在失败的那一个玩家身上（与上面的 GUI 清扫段同一形状、同一语义）。
        //   b) 更不能让异常冲出本段：本段之后还有 achievementManager.clearCache()、
        //      proxyMessenger.unregister()、aiChatManager.shutdown()、
        //      statisticsHttpServer.stop()、databaseManager.shutdown() —— 尤其
        //      databaseManager.shutdown()，跳过它就是连接不关、SQLite 可能留下锁。
        //
        // 真实故障点（不是假想）：DatabaseManager.getConnection() 在取连接失败时
        // throw new RuntimeException("Failed to get database connection", e)；而
        // PlayerDataStore.savePlayerDataInternal 的 catch(SQLException) 在 SQL 阶段，
        // **晚于**它取连接那一行（PlayerDataStore.java:918），所以该异常会一路冒到调用方。
        // saveProfileSettings 同样在 try 之外取连接（PlayerDataStore.java:1626）。
        // 即：DB 不可用时，这里的异常是**必然**发生的，不是理论风险。
        //
        // 用 Throwable 而非 Exception，理由与 clearAllSessions / duelManager.shutdown
        // 一致：停用路径要防的正是 LinkageError / NoClassDefFoundError 这类换 ClassLoader
        // 或依赖缺失才会出现的 Error；catch(Exception) 会让它们照旧截断停用流程。
        // 单体失败只记 WARNING、不重抛 —— onDisable 没有上层可补救，继续往下走
        // （把后面的清理和关库做完）才是最优解。
        if (playerDataStore != null) {
            int savedPlayers = 0;
            for (Player p : getServer().getOnlinePlayers()) {
                try {
                    playerDataStore.savePlayerData(p.getUniqueId());
                    playerDataStore.saveProfileSettings(p.getUniqueId());
                    savedPlayers++;
                } catch (Throwable t) {
                    getLogger().warning("Failed to save data for " + p.getName()
                            + " during shutdown: " + t.getMessage());
                }
            }
            if (savedPlayers > 0) {
                getLogger().info("Saved data for " + savedPlayers + " player(s).");
            }
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

        // L1（P1·关服丢数据）：排空 ChatFeedbackManager 的在途落库任务。
        //
        // 为什么位置在这里（aiChatManager.shutdown() 之后、databaseManager.shutdown() 之前）：
        //   1) 必须在 databaseManager.shutdown() <b>之前</b> —— 排在它之后时，flush 要写的
        //      正是已经关闭的连接，等于把「静默丢弃」换成「一屏异常」，一条都救不回来；
        //   2) 必须在 aiChatManager.shutdown() <b>之后</b> —— shutdown 置 shuttingDown 标志，
        //      让所有在途 AI 回调短路，因此 flush 窗口里不会再有 AI 点评回调提交新的
        //      覆写任务，「排空」才真的有终点（否则 flush 与回调赛跑）；
        //   3) 为什么不是更早（例如紧接 clearAllSessions）：那之后还有 savePlayerData 等
        //      纯写库步骤，提前 flush 会白白占掉 5s 预算却让后面的等待变长。
        //
        // 为什么必须 flush：落库走 CompletableFuture.runAsync（ForkJoinPool.commonPool），
        // Paper 的 waitForAsyncTasksShutdown() 只等 BukkitScheduler 的异步任务，管不到它。
        // 不 flush 时，onDisable 期间排队中的 chat_feedback 写会在 writeTask 的 accepting
        // 守卫处被丢 —— 而玩家此时已经看过点评，事后无从察觉。
        //
        // 异常与超时都只记 WARNING：onDisable 没有上层可补救，绝不能因为排空失败而跳过
        // 下面的关库（跳过 = 连接不关、SQLite 可能留锁）。null 守卫覆盖「DI 半初始化」
        // 这一情形（chatFeedbackManager 在 onEnable 里构造，理论上非 null，但 onDisable
        // 可能在 enable 中途被调用）。
        if (chatFeedbackManager != null) {
            try {
                chatFeedbackManager.flushPending(5000L);
            } catch (Throwable t) {
                // 用 Throwable 而非 Exception，理由同本方法其余兜底段：停用路径要防的
                // 正是 LinkageError / NoClassDefFoundError 这类换 ClassLoader 才会冒出来的 Error。
                getLogger().log(java.util.logging.Level.WARNING,
                        "Failed to flush pending chat feedback writes during shutdown", t);
            }
        }

        // R5-15：这里只包 statisticsHttpServer.stop() **一个**，不是漏了上面那四个 ——
        //   * achievementManager.clearCache() / aiChatManager.shutdown() 是纯内存 clear，
        //     不碰 DB / IO，也没有会抛的调用；
        //   * proxyMessenger.unregister() 内部已有 catch(Exception ignored)；
        //   * ttsManager.shutdown() 是空方法。
        // 而 stop() 是 JDK 的 com.sun.net.httpserver.HttpServer.stop(0)，对
        // IllegalStateException / SecurityException 这类非受检异常没有任何承诺；更关键的是
        // 它**紧邻** databaseManager.shutdown() —— 一旦抛，关库直接被跳过（连接不关、
        // SQLite 可能留下 -wal/-shm 与锁），这是五个裸奔点里唯一有实害的。
        // 其余四个即使抛也只截断"更靠后的空操作"，收益远小于给停用流程堆 try 的噪音成本，
        // 故有意保持原样。
        if (statisticsHttpServer != null && statisticsHttpServer.isRunning()) {
            try {
                statisticsHttpServer.stop();
            } catch (Throwable t) {
                getLogger().warning("Failed to stop statistics HTTP server during shutdown: "
                        + t.getMessage());
            }
        }

        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("Let's Learn English! plugin has been safely disabled.");
    }

    /**
     * B1（R12）+ L6（P1·隐私）：本场「目标词」的取数决策体。
     *
     * <p>为什么抽成独立静态方法：{@code onEnable} 整段依赖 Bukkit server，纯 JVM 单测跑不起来，
     * 而 L6 的开关语义（默认<b>不</b>回落到复习词）恰恰是最需要被钉死的一条 —— 它是隐私
     * 边界，不是功能偏好。方法不带任何实例状态，三个入参由调用点注入，因此可被
     * {@code FocusWordReviewToggleTest} 直接调用。
     *
     * <p>数据源优先级：
     * <ol>
     *   <li>{@code ChatFeedbackManager#getSuggestedFocusWords} —— 上一场 AI 点评产出的
     *       纠错词 + 推荐词。这些词玩家<b>已经见过</b>，且已在点评里明示，不构成泄题；</li>
     *   <li><b>可选</b>：{@code ReviewScheduler#getDueReviewWords} —— 艾宾浩斯到期复习词，
     *       由 {@code ai-chat.focus-words-from-review} 控制，<b>默认 false</b>。</li>
     * </ol>
     *
     * <p><b>为什么第 2 路默认关闭（L6）</b>：到期复习词 = 玩家尚未掌握、即将被考的单词。
     * 把它们写进 system prompt 的 TARGET WORDS 段，等于直接告诉 AI「请用这些词引导对话」——
     * 玩家下一场就会从 AI 的台词里看到自己复习队列里的正确答案。零成本（开一次对话即可）、
     * 可批量（一轮刷一批）、可复现（{@code /le history} 还能回看原句），把「考」变成「泄题」。
     * 上一场纠错词不受影响（已见过 + 已明示）。
     *
     * <p>为什么是 {@code public static} 而不是私有：这是给 {@code FocusWordReviewToggleTest}
     * 的<b>纯函数测试缝</b>。{@code onEnable} 依赖 Bukkit server，纯 JVM 跑不起来，而
     * 「默认不回落到复习词」是隐私边界、必须被断言住。把它做成静态纯函数后，测试不需要
     * mockStatic(Bukkit) 也不需要任何 DI 桩，直接传三个入参即可 —— 这是刻意的取舍：
     * 多一个 public 方法，换一条隐私边界可测。方法无实例状态、无副作用，外部误用的风险
     * 仅限于「读到一份与本类当前状态不一致的快照」，不影响插件行为。
     *
     * <p>线程：只在主线程的 {@code buildSystemPrompt} 里被调用，两路数据源都必须是<b>纯内存</b>
     * 读（ConcurrentHashMap / 30s TTL 缓存）—— 绝不许在这里查库（会主线程阻塞等 connectionLock）。
     *
     * @param playerId            玩家；null 直接返回空表
     * @param chatFeedbackManager 上一场点评快照源；null（DI 半初始化）时跳过该路
     * @param wordManager         词库管理器；null 时复习词一路不可用
     * @param mainConfig          主配置。<b>每次调用传入</b>（不在字段里缓存）——本插件没有配置
     *                            迁移机制，老配置文件缺键时靠「读点默认值」兜住；缓存会让
     *                            {@code /lea reload} 后的改动不生效
     * @return 不可变列表，永不为 null
     */
    public static List<String> resolveFocusWords(UUID playerId,
                                                 ChatFeedbackManager chatFeedbackManager,
                                                 WordManagerImpl wordManager,
                                                 FileConfiguration mainConfig) {
        if (playerId == null) {
            return List.of();
        }
        if (chatFeedbackManager != null) {
            List<String> suggested = chatFeedbackManager.getSuggestedFocusWords(playerId);
            if (suggested != null && !suggested.isEmpty()) {
                return suggested;
            }
        }
        // L6：默认 false —— 未配置的老服务器也不会因为升级而开始泄题。
        boolean allowReviewFallback = mainConfig != null
                && mainConfig.getBoolean("ai-chat.focus-words-from-review", false);
        if (!allowReviewFallback || wordManager == null || wordManager.getReviewScheduler() == null) {
            return List.of();
        }
        return wordManager.getReviewScheduler().getDueReviewWords(playerId);
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
        // 全局 GUI 物品守卫：放在最前只为阅读时先看到「全局规则」—— 实际执行顺序由
        // @EventHandler(priority = LOWEST) 决定，与注册先后无关。它只拦「插件 GUI + 下半区 +
        // shift-click / 双击收集」这一种组合，不参与任何业务分派。
        // 刻意不含 PICKUP_ALL：那是「左键取整叠」，拦它会锁死背包整理（见该类的字节码实证）。
        getServer().getPluginManager().registerEvents(
                new GlobalGuiActionGuardListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new MainMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new WordLearningListener(this), this);
        getServer().getPluginManager().registerEvents(new ScenarioSelectListener(this), this);
        // Phase 3 · MC-4：个人场景管理页（纯 holder 判定，GuiViewKey.PERSONAL_SCENARIO；
        // 与 ScenarioSelectListener 各自按 holder key 分派，互不影响）
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
        // Wave 4 · US-CLS-2：班级榜（纯 holder 判定，GuiViewKey.CLASS_BOARD）
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

    /**
     * 本服的英语学习功能是否<b>真的</b>可用：配置门开 <b>且</b> 运行时已经装配。
     *
     * <p>命令入口用它而不是直接读 {@link ConfigManager#isServerEnabled()}，因为两者会在
     * 一个窗口里分叉：启动时 {@code server.enabled=false}，服主随后 {@code /lea reload}
     * 把它改成 true —— 配置文件读出来是 true，但监听器/Manager 一个都没装（动态装配
     * 本轮不实现），此时若按配置值放行，命令会一路走到 null Manager 上 NPE。
     * 用「配置 + 已装配」双条件，这个窗口里命令继续拒绝，直到重启真正装好为止。
     *
     * <p>判据选 {@code playerDataStore != null}：它是 {@code enableFullFeatures()} 里
     * 第一个被赋值的运行时依赖，也是 {@code HotReloadManager} 短路用的同一个标记，
     * 两处语义一致（见该类 reloadAll 的注释）。
     */
    public boolean isFullyEnabled() {
        return configManager != null && configManager.isServerEnabled() && playerDataStore != null;
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
