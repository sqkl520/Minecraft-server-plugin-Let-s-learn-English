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

    // ---------------- 动态装卸（/lea reload 切换 server.enabled）----------------
    //
    // 状态机与动作分离：RuntimeModules 只做「这次该不该装/拆」的判断与记账（可纯 JVM 单测），
    // 真正的装配/拆解仍在本类，因为 30+ 个字段必须留在入口类上（全仓 40+ 个 getXxx() 消费点）。
    /**
     * 运行时装配状态机 + 记账。实例跨启停保活，只翻内部 active 标志。
     *
     * <p>非 final 且经 {@link #modules()} 取值：{@code ServerToggleTest} 用 Objenesis 造
     * mock 实例（字段初始化器不执行，本字段为 null），直接 {@code modules().beginDisable()}
     * 会 NPE 并把「关闭态 onDisable 不抛异常」那条既有用例打成红。懒初始化让 mock 实例上
     * 的语义与真实实例一致（{@code beginDisable()} 返回 false ⇒ 拆解段整体跳过）。
     */
    private RuntimeModules modules = new RuntimeModules();

    /**
     * 运行时模块（监听器 / 周期任务 / 全部 Manager）是否已装配。
     *
     * <p>它与 {@code isEnabled()} 的区别是本类停用逻辑的全部要害：{@code /lea reload} 期间
     * {@code isEnabled()} 恒为 true，只有这个字段能在「配置已读成 false、Manager 还没拆完」
     * 的窗口里立刻回答「现在不能再派发业务」。
     */
    private boolean runtimeActive;

    /**
     * onEnable 的计时起点：{@link #enableAll()} 末尾打印加载耗时用。
     *
     * <p>为什么做成字段而不是 {@code enableAll(long)} 入参：冻结契约要求 {@code void enableAll()}
     * 无参（B 流直接调用）。onEnable 在调用前写入本字段以保留「从 onEnable 第一行算起」的
     * 原语义；/lea reload 触发时它是 -1，enableAll 自行计时。
     */
    private long enableStartTime = -1L;

    /**
     * PlaceholderAPI expansion 的持有引用（A5）。
     *
     * <p>原实现里它是 {@code setupPlaceholderAPI()} 的局部变量，{@code register()} 之后引用即丢，
     * 全仓没有任何 {@code unregister()} 调用 —— 停用后 {@code %lle_score%} 仍会被 PAPI 求值，
     * 打在已置空的 Manager 上。改成字段才能在 D8 里注销。
     */
    private LLEPapiExpansion papiExpansion;

    // D1 的 5 个周期任务句柄（第 6 个是上面的 midnightTask）。全部显式持有：
    // scheduler.cancelTasks(plugin) 会连 PlayerDataStore 的异步落库与
    // ChatFeedbackManager 的 flush 链一起杀掉，绝不能用。
    private BukkitTask autoSaveTask;
    private BukkitTask reviewNotifyTask;
    private BukkitTask dailyReminderTask;
    private BukkitTask leaderboardRefreshTask;
    private BukkitTask backupTask;

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

        // 计时起点从 onEnable 第一行算起（enableAll 无参，见 enableStartTime 字段注释）。
        this.enableStartTime = startTime;
        enableAll();
    }

    /**
     * 完整功能装配路径（{@code server.enabled=true} 时由 {@link #onEnable()} 调用，
     * 或 {@code /lea reload} 在 false→true 时调用）。
     *
     * <p><b>本方法体是原 {@code enableFullFeatures(long)} 的逐字搬移</b>（含全部注释与逐行
     * 顺序），锁着它的形状的是两条契约：
     * <ol>
     *   <li>{@code ServerToggleLintsTest} 的 L2 断言本方法体内 {@code new WordLearningManager(}、
     *       {@code new OnboardingManager(}、{@code proxyMessenger.register()}、
     *       {@code papiExpansion.register()}、{@code startScheduledTasks()}、
     *       {@code registerListeners()} 各出现<b>恰好一次</b> —— 所以不要把这些调用抽到
     *       辅助方法里去；</li>
     *   <li>{@code onEnable} 的计时起点由 {@link #enableStartTime} 传入，本方法体末尾打印。</li>
     * </ol>
     *
     * <p><b>为什么必须有失败回滚</b>：{@code onEnable} 期装配失败由 Bukkit 兜底 disable
     * 整个插件；{@code /lea reload} 期没有那层兜底，半装配会留下「监听器齐了但
     * startScheduledTasks 没跑」这类怪状态。{@link #disableAll()} 每步都有 null 守卫 +
     * try/catch，天然能吃下半成品，所以回滚就是直接调它。
     *
     * <p><b>为什么 Manager 每次 enable 全新建而不复用</b>：{@code AIChatManager.shuttingDown}
     * 与 {@code ChatFeedbackManager.accepting} 都是单向闩（一旦置位永不回 true），复用实例
     * 会让重开后的 AI 对话永久短路、chat_feedback 落库被静默丢弃。新建用「重建」换双向对称。
     */
    public void enableAll() {
        if (!modules().beginEnable()) {
            return; // 幂等：已装配时重复调用什么都不做
        }
        // 开装前先清上一次拆解留下的记账（正常路径下 disableAll 的 D10 已清，这里是纵深防御：
        // 若上一次 enable 半途失败并回滚，记账本不该带进下一代）。
        modules().releaseAll();
        // 计时起点：onEnable 会把 enableStartTime 预设成 onEnable 第一行的时间；
        // /lea reload 触发时它是 -1，此处自行计时。**在 try 之外求值** —— 它在 try 里
        // 声明就跨不出括号，末尾打印加载耗时要读它。
        final long startTime = enableStartTime > 0 ? enableStartTime : System.currentTimeMillis();
        try {
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

        // A5：expansion 存字段（D8 要 unregister）。原实现是 setupPlaceholderAPI() 里的局部
        // 变量，register() 后引用即丢，全仓无注销点 —— 停用后 %lle_score% 会打在已置空的
        // Manager 上。**先在装配路径里登记字段、再 register()**：register() 抛异常时字段仍
        // 持有引用，D8 仍能尝试注销（未注册成功的 unregister() 在 PAPI 侧是安全 no-op）。
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            LLEPapiExpansion expansion = new LLEPapiExpansion(this);
            if (expansion.canRegister()) {
                this.papiExpansion = expansion;
                papiExpansion.register();
                getLogger().info("PlaceholderAPI expansion registered. Available: %lle_score%, %lle_rank%, etc.");
            }
        }

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

            runtimeActive = true;
        } catch (Throwable t) {
            // 半装配必须能安全收尾：/lea reload 期没有 Bukkit 兜底 disable。
            // disableAll() 每步都有 null 守卫 + try/catch，天然能吃下半成品。
            getLogger().log(java.util.logging.Level.WARNING,
                    "Runtime assembly failed; rolling back to the disabled state.", t);
            try {
                disableAll();
            } catch (Throwable rollbackFailure) {
                // disableAll 设计上不抛（每步自吞只 WARNING），但它是几十个调用的聚合体，
                // 将来有人在里面加了不经过 self-catch 的代码就会抛。**绝不能让它盖掉原始异常** ——
                // 那样 /lea reload 的 FAILED 只会报「回滚时崩了」，而真正的原因（哪个 Manager
                // 构造失败）彻底丢失，日志里只剩一条无关的次生异常。
                t.addSuppressed(rollbackFailure);
                getLogger().log(java.util.logging.Level.SEVERE,
                        "Rollback during runtime assembly failed too (see suppressed).", rollbackFailure);
            }
            throw t;
        }
    }

    /**
     * 拆解运行时模块（true → false）。<b>顺序就是本方法的全部价值</b>，每一步都在注释里
     * 写清「为什么必须在这个位置」。
     *
     * <p>幂等：{@link RuntimeModules#beginDisable()} 首次返回 true、之后恒 false；每步都有
     * null 守卫 + {@code catch(Throwable)}（照 {@code onDisable} 的既有姿态：停用路径要防的
     * 正是 {@code LinkageError} / {@code NoClassDefFoundError} 这类换 ClassLoader 或依赖缺失
     * 才会冒出来的 Error，{@code catch(Exception)} 会让它们照旧截断停用流程）。
     *
     * <p><b>本方法不关数据库</b>——这与 {@code onDisable} 的根本差异：{@code /lea reload} 之后
     * 插件仍然活着，{@code databaseManager} 是关闭态三件套之一，必须留着。
     */
    public void disableAll() {
        // ---- D0：runtimeActive = false（必须是第一行）----
        // 清理过程本身会**同步触发事件回环**（closeInventory() → InventoryCloseEvent，
        // endSession → 成就/挑战回调）。任何回环里的 isFullyEnabled() 必须在第一纳秒就为
        // false，否则监听器会在「半拆」的 Manager 上继续派发。这一个布尔量同时是午夜自续、
        // GUI 异步回填、赛季结算回投的统一闸门。
        runtimeActive = false;

        if (!modules().beginDisable()) {
            // 已处于关闭态：幂等返回。beginDisable() 已经先把 active 翻成 false，
            // 所以这里不会留下「标志没翻、活也没干」的漏洞。
            return;
        }
        getLogger().info("Unloading runtime modules (listeners / tasks / channels)...");

        // ---- D1：取消插件级周期任务（6 个句柄 + SocialManager 自持的那个）----
        // 先停「生产新工作」的源头，D6 落盘期间就不会有 auto-save 的异步写插进来
        //（saveDirty 与全量 savePlayerData 抢同一把 connectionLock，虽安全但会拉长落盘窗口）。
        //
        // **不用 scheduler.cancelTasks(plugin)**：它会把 PlayerDataStore 已派发的异步落库
        //（:363/390/747/778/807）、ChatFeedbackManager 的 flush 链、SeasonManager 在途结算
        // 一起杀掉 —— 正是 D6 要保全的写操作。只 cancel 我们记账的句柄。
        cancelTaskQuietly("auto-save", autoSaveTask);
        autoSaveTask = null;
        cancelTaskQuietly("review-notify", reviewNotifyTask);
        reviewNotifyTask = null;
        cancelTaskQuietly("daily-reminder", dailyReminderTask);
        dailyReminderTask = null;
        cancelTaskQuietly("leaderboard-refresh", leaderboardRefreshTask);
        leaderboardRefreshTask = null;
        cancelTaskQuietly("database-backup", backupTask);
        backupTask = null;
        // midnightTask 必须单独 cancel，不能只靠自续守卫：它的守卫原为 if (!isEnabled())，
        // reload 期间 plugin 一直 enabled，任务一旦触发就会在拆分之后重排下一天，等于在
        // 禁用态留下一个活任务。（守卫已加 isRuntimeActive() 做纵深防御，见 A7。）
        cancelTaskQuietly("midnight-rollover", midnightTask);
        midnightTask = null;
        // 兜底：tasksSnapshot() 里的任何漏网句柄（例如将来有人 track 了新任务却忘了写
        // 显式字段）。只清记账，真正的 cancel 在本类做 —— RuntimeModules 不调 Bukkit 方法。
        for (BukkitTask task : modules().tasksSnapshot()) {
            cancelTaskQuietly("tracked", task);
        }
        // 广场社交的周期刷新任务是它自己持有的（C 流新增 shutdown()），此处一并收。
        if (socialManager != null) {
            try {
                socialManager.shutdown();
            } catch (Throwable t) {
                getLogger().warning("Failed to shut down social manager: " + t.getMessage());
            }
        }

        // ---- D2：中断 AI 对话（逐在线玩家 endConversation）----
        // endConversation 做三件必须做的事：移出 sessions、统计计数（incrementConversationCount
        // 直接 UPSERT）、成就与每日挑战进度。三件都要 achievementManager / playerDataStore
        // **还活着**（所以必须在 D10 之前），又写内存态（所以必须在 D6 之前）。
        // 它不发 UI、不发 HTTP，是干净的状态终局。
        //
        // **不用 ChatScenarioListener.removePlayer()**：那个方法会发小结卡、触发 AI 点评
        // 异步链（ChatFeedbackManager → HTTP），正好在我们要拆 HTTP 栈的时刻又开一条链。
        if (aiChatManager != null) {
            for (Player online : getServer().getOnlinePlayers()) {
                try {
                    aiChatManager.endConversation(online);
                } catch (Throwable t) {
                    getLogger().warning("Failed to end AI conversation for " + online.getName()
                            + " during unload: " + t.getMessage());
                }
            }
            // D2 收尾：置 shuttingDown 单向闩，短路仍在途的 HTTP 回调链 —— 否则旧实例的
            // 回调会在下一代运行时装配后继续写库/调旧 scheduler。放在 endConversation
            // **之后**：endConversation 自身要读 sessions 与计数。
            try {
                aiChatManager.shutdown();
            } catch (Throwable t) {
                getLogger().warning("Failed to shut down AI chat manager: " + t.getMessage());
            }
        }

        // ---- D3：清答题会话（**必须先清会话、后关 GUI**）----
        // WordLearningListener.onInventoryClose 的三道守卫是 resolveOwnedView == WORD_LEARNING
        // → hasActiveQuiz → isAnswering，第三道为 false 时调 startAnswerForCurrentWord()。
        // 先关 GUI 而会话还在 ⇒ 每关一个界面就凭空起一个 20L 周期倒计时任务 + 给「即将断开」
        // 的玩家推答题提示；先清会话 ⇒ 第二道守卫即 early return，事件彻底 no-op。
        // clearAllSessions() 自身也是「写库步骤」（endSession 落掌握度/累计分/复习种子），
        // 所以必须在 D6 之前。
        // resetTransientState() 补 clearAllSessions() 的两个漏：practiceWords /
        // deathPausedQuiz 都不在它的清理范围，不清会让玩家重开后卡在「练习进行中」。
        if (wordLearningManager != null) {
            // 先告知、后清会话：清会话会结算已得进度，但玩家看到的是「界面突然没了」。
            // 不解释一句，玩家会以为是卡顿或掉线（红队 B 指出这个体验缺口）。
            // 文案必须说清「已答的题算数」——否则玩家会以为白练一局。
            notifyActiveQuizzesBeforeDisable();
            try {
                wordLearningManager.clearAllSessions();
            } catch (Throwable t) {
                getLogger().warning("Failed to clear word learning sessions: " + t.getMessage());
            }
            try {
                wordLearningManager.resetTransientState();
            } catch (Throwable t) {
                getLogger().warning("Failed to reset word learning transient state: " + t.getMessage());
            }
        }

        // ---- D4：决斗 —— 直接解散，不结算 ----
        // 裁决：不判负、不记战绩、不写 duel_stats。理由：DuelRoom.getWinner() 未分胜负时返回
        // null，而 endDuel 对 null winner 仍会调 tryUnlock(null, ...)（NPE 风险）；且对局被
        // 打断不是任何一方的过错，判负会写进 duel_stats 影响胜率展示。
        // dismissAllActiveDuels() 走 cancelDuelInternal 的现成语义（双方提示 + removeScoreboard
        // + closeInventory + cleanup）；随后 shutdown() 幂等收尾 grace/wordTimeout/requestExpiry
        // 三类任务、BossBar、pendingRequests、offlineNotices。
        if (duelManager != null) {
            try {
                int dismissed = duelManager.dismissAllActiveDuels();
                if (dismissed > 0) {
                    getLogger().info("Dismissed " + dismissed + " active duel(s) during unload.");
                }
            } catch (Throwable t) {
                getLogger().warning("Failed to dismiss active duels: " + t.getMessage());
            }
            try {
                duelManager.shutdown();
            } catch (Throwable t) {
                getLogger().warning("Failed to shut down duel manager: " + t.getMessage());
            }
        }

        // ---- D5：关 GUI（在 D3/D4 之后、D6 之前）----
        // 位置：D3/D4 之后 —— 避免 D3 论证的倒计时副作用、避免对已被关闭的界面重复操作；
        // D6 之前 —— 让「关界面引发的任何内存态变化」发生在全量保存之前，而不是落在落盘后
        // 丢失。逐人 try/catch：一个玩家失败不能拖垮剩余玩家（照抄 onDisable 的形状）。
        try {
            int closedPluginGuis = 0;
            for (Player online : getServer().getOnlinePlayers()) {
                try {
                    if (GuiIdentity.closeIfPluginGui(online)) {
                        closedPluginGuis++;
                    }
                } catch (Throwable t) {
                    getLogger().warning("Failed to close plugin GUI for " + online.getName()
                            + " during unload: " + t.getMessage());
                }
            }
            if (closedPluginGuis > 0) {
                getLogger().info("Closed " + closedPluginGuis + " lingering plugin GUI(s) during unload.");
            }
        } catch (Throwable t) {
            getLogger().warning("Failed to sweep plugin GUIs during unload: " + t.getMessage());
        }

        // ---- D6：落盘（在所有中断之后）----
        // D2 的计数/成就、D3 的 endSession（掌握度/分数/复习种子）、D3 里 stopQuiz 的金币奖励
        // 都改的是内存态，落在 D6 之前才能被这一次全量写覆盖。逐玩家独立 catch：第一个玩家
        // 失败不能让后面 N-1 个玩家的进度全丢（原 onDisable 注释里的真实故障点：
        // DatabaseManager.getConnection() 取连接失败会抛 RuntimeException，不是理论风险）。
        if (playerDataStore != null) {
            int savedPlayers = 0;
            for (Player p : getServer().getOnlinePlayers()) {
                try {
                    playerDataStore.savePlayerData(p.getUniqueId());
                    playerDataStore.saveProfileSettings(p.getUniqueId());
                    savedPlayers++;
                } catch (Throwable t) {
                    getLogger().warning("Failed to save data for " + p.getName()
                            + " during unload: " + t.getMessage());
                }
            }
            if (savedPlayers > 0) {
                getLogger().info("Saved data for " + savedPlayers + " player(s).");
            }
        }

        // ---- D7：排空 chat_feedback 在途写（在 D6 之后）----
        // 它走 CompletableFuture.runAsync（ForkJoinPool.commonPool），**不受 Bukkit 调度器
        // 取消管辖**。不排空 ⇒ 玩家已看过点评、但落库被 writeTask 的 !accepting 守卫丢掉，
        // 事后无从察觉。位置在 D6 之后：让 D2/D3 引发的任何新写先进来再一次性排空。
        // 本流程**不关 databaseManager**，所以 flush 之后连接仍然可用。
        if (chatFeedbackManager != null) {
            try {
                chatFeedbackManager.flushPending(5000L);
            } catch (Throwable t) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Failed to flush pending chat feedback writes during unload", t);
            }
        }

        // ---- D8：拆外部接线 ----
        // 四件的共同点是「插件**外部**可见」：消息通道在 Messenger 注册表里、占位符在 PAPI
        // 注册表里、HTTP 在端口上、成就缓存在静态可达的 manager 上 —— HandlerList.unregisterAll
        // 一个都管不到，必须显式逐个撤。
        if (proxyMessenger != null) {
            try {
                proxyMessenger.unregister();
            } catch (Throwable t) {
                getLogger().warning("Failed to unregister proxy messenger: " + t.getMessage());
            }
        }
        if (papiExpansion != null) {
            // A5：当前全仓此前没有任何 unregister() 调用 —— 停用后 %lle_score% 仍会被
            // PAPI 求值，打在已置空的 Manager 上。
            try {
                papiExpansion.unregister();
            } catch (Throwable t) {
                getLogger().warning("Failed to unregister PlaceholderAPI expansion: " + t.getMessage());
            }
        }
        if (statisticsHttpServer != null && statisticsHttpServer.isRunning()) {
            try {
                statisticsHttpServer.stop();
            } catch (Throwable t) {
                getLogger().warning("Failed to stop statistics HTTP server during unload: "
                        + t.getMessage());
            }
        }
        if (achievementManager != null) {
            try {
                achievementManager.clearCache();
            } catch (Throwable t) {
                getLogger().warning("Failed to clear achievement cache: " + t.getMessage());
            }
        }

        // ---- D9：反注册事件监听器（必须在所有清理之后）----
        // 理由：D2~D5 期间仍希望本插件自己的事件回环走正常处理器（例如某玩家此刻 quit，
        // DuelListener.onPlayerQuit 需要 duelManager 还活着来走它自己的收尾）；且 D3 的
        // 「先清会话后关 GUI」论证本身就假定监听器**还注册着**（要求它 early return，而不是
        // 压根不存在）。**保持「监听器最后撤」这一不变量，请勿把它提前。**
        // 用 plugin 级全撤而不是逐个 unregister：它能覆盖「我们记不住的那一个」（记账清单
        // 永远可能漏），天然幂等，且不会误伤别的插件（按 RegisteredListener 的 plugin 过滤）。
        try {
            org.bukkit.event.HandlerList.unregisterAll(this);
            // 免费的生产自检：撤完应为空。非空说明有人在我们记账之外注册了东西。
            List<?> leftover = org.bukkit.event.HandlerList.getRegisteredListeners(this);
            if (!leftover.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (Object registered : leftover) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(registered.getClass().getName());
                }
                getLogger().warning("HandlerList still reports " + leftover.size()
                        + " registered listener(s) after unregisterAll: " + names);
            }
        } catch (Throwable t) {
            getLogger().warning("Failed to unregister event listeners: " + t.getMessage());
        }

        // ---- D10：置空字段 ----
        // 全新建（不复用）的理由见 enableAll()：AIChatManager.shuttingDown 与
        // ChatFeedbackManager.accepting 都是单向闩，复用会让重开后静默失效。
        //
        // **必须保留**：configManager / messageUtil / databaseManager（关闭态三件套）、
        // versionAdapter（onLoad 建，SoundManager 构造期要它）、hotReloadManager（关闭态唯一
        // 逃生口，AdminCommand 裸调用）、updateChecker（PlayerJoinListener 的 60L 延迟任务在
        // 触发时才取它，置 null 会 NPE）、economy（外部 provider）、
        // playerLanguage / joinReminderSent（插件级跨启停状态）。
        this.playerDataStore = null;
        this.wordManager = null;
        this.wordLearningManager = null;
        this.achievementManager = null;
        this.aiChatManager = null;
        this.chatFeedbackManager = null;
        this.chatScenarioListener = null;
        this.socialListener = null;
        this.guiNavigationManager = null;
        this.dailyChallengeManager = null;
        this.progressManager = null;
        this.duelManager = null;
        this.checkInManager = null;
        this.onboardingManager = null;
        this.socialManager = null;
        this.seasonManager = null;
        this.adminCommandHandler = null;
        this.dataExporter = null;
        this.reportGenerator = null;
        this.soundManager = null;
        this.ttsManager = null;
        this.proxyMessenger = null;
        this.statisticsHttpServer = null;
        this.api = null;
        // 光把字段置 null 不够 —— getInstance() 是第三方插件的唯一入口，
        // 静态引用还指着刚拆掉的实例。不清的话第三方拿到的是一个「所有 getter 都返回
        // null Manager」的僵尸对象：它通常只判 getInstance() != null 就继续用，
        // 于是 NPE 发生在调用方那一侧，而不是「本插件未启用」这个可理解的事实。
        // 清掉之后「停用后拿不到 API」与「启动时就是 server.enabled=false」完全同构。
        // 用 force 版：D10 时已确定是本实例在拆，不需要 clearInstance() 的「别抹掉别人」保护。
        LetsLearnEnglishAPI.forceClearInstance();
        this.papiExpansion = null;
        // 只清记账，不撤销注册（注册已由 D9 全撤）。
        modules().releaseAll();
        this.enableStartTime = -1L;

        getLogger().info("Runtime modules unloaded: listeners unregistered, tasks cancelled,"
                + " channels closed, player data saved.");
    }

    /** D1 用：单个任务取消，null 守卫 + 异常自吞（cancel 失败不能拖垮拆解流程）。 */
    private void cancelTaskQuietly(String what, BukkitTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (Throwable t) {
            getLogger().warning("Failed to cancel " + what + " task during unload: " + t.getMessage());
        }
    }

    /**
     * 拆运行时之前，先给「正在答题」的玩家一句解释。
     *
     * <p><b>为什么需要这个</b>：{@link #disableAll()} 的 D3 会调
     * {@code clearAllSessions()}，它把已答对的题结算入账（掌握度/分数/复习种子），
     * 然后 D5 关掉界面。玩家看到的事实是「界面突然没了」—— 不解释一句，
     * 会以为是卡顿或掉线；更要紧的是<b>不知道已答的题算不算数</b>，
     * 于是可能去找客服、或者以为白练一局。
     *
     * <p><b>为什么放在清会话之前</b>：清完之后 {@code hasActiveQuiz()} 全是 false，
     * 就找不到「谁需要被通知」了。
     *
     * <p>逐人 try/catch：一个玩家发送失败不能拖垮其余玩家（与 D3~D6 的姿态一致）。
     * 管理器为 null 时静默返回（关闭态下重复调用的正常情形）。
     */
    private void notifyActiveQuizzesBeforeDisable() {
        if (wordLearningManager == null || messageUtil == null) {
            return;
        }
        for (Player online : getServer().getOnlinePlayers()) {
            try {
                if (!wordLearningManager.hasActiveQuiz(online.getUniqueId())) {
                    continue;
                }
                online.sendMessage(messageUtil.getPlayerMessage(online, "server.quiz-interrupted"));
            } catch (Throwable t) {
                getLogger().warning("Failed to notify player " + online.getName()
                        + " about the interrupted quiz: " + t.getMessage());
            }
        }
    }

    /**
     * 服务器/插件停用：先走 {@link #disableAll()} 拆运行时，再补两件**只有这里才做**的事。
     *
     * <p>为什么收敛成「调 disableAll + 关库/语言落盘」：两套拆解逻辑并存必然漂移 ——
     * 有一处加了守卫、另一处没加，症状只会在「热重载 vs 关服」某一条路径上出现。
     * 注意不能反过来让 disableAll 吞掉这两步：{@code /lea reload} 之后插件仍活着，
     * {@code databaseManager} 必须留着，{@code playerLanguage} 也不必每次 reload 都写盘。
     */
    @Override
    public void onDisable() {
        // R5-15：这句是**整段停用流程**的入口标记（清会话 → 决斗收尾 → GUI 清扫 → 落盘
        // → 关库），不是"落盘马上开始"。真正的落盘完成另有 "Saved data for N player(s)."
        // 一行，两者不重复：前者标停用开始，后者报实际落盘人数（0 人时不打印）。
        getLogger().info("Disabling plugin - clearing sessions and saving player data...");

        disableAll();

        // 关闭态（server.enabled=false 启动）下 disableAll() 因 beginDisable() 返回 false 而
        // 整段跳过，但语言落盘与关库仍然必须执行 —— 它们与「运行时是否装配」无关。
        // onDisable 独有的两步，顺序与旧实现一致：先写语言文件，再关库。
        savePlayerLanguages();

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
        //
        // 全部经 modules().track(...) 记账：记账**只用于停用时的日志统计与漏网 cancel**，
        // 反注册统一走 HandlerList.unregisterAll(plugin)（见 disableAll 的 D9）—— 所以这里
        // 漏记一笔不会造成监听器泄漏，只会让日志少一个可读的类名。
        GlobalGuiActionGuardListener globalGuiGuard = new GlobalGuiActionGuardListener(this);
        getServer().getPluginManager().registerEvents(globalGuiGuard, this);
        modules().track(globalGuiGuard);
        PlayerJoinListener playerJoinListener = new PlayerJoinListener(this);
        getServer().getPluginManager().registerEvents(playerJoinListener, this);
        modules().track(playerJoinListener);
        MainMenuListener mainMenuListener = new MainMenuListener(this);
        getServer().getPluginManager().registerEvents(mainMenuListener, this);
        modules().track(mainMenuListener);
        WordLearningListener wordLearningListener = new WordLearningListener(this);
        getServer().getPluginManager().registerEvents(wordLearningListener, this);
        modules().track(wordLearningListener);
        ScenarioSelectListener scenarioSelectListener = new ScenarioSelectListener(this);
        getServer().getPluginManager().registerEvents(scenarioSelectListener, this);
        modules().track(scenarioSelectListener);
        // Phase 3 · MC-4：个人场景管理页（纯 holder 判定，GuiViewKey.PERSONAL_SCENARIO；
        // 与 ScenarioSelectListener 各自按 holder key 分派，互不影响）
        com.letslearnenglish.minecraftplugin.listener.PersonalScenarioListener personalScenarioListener =
                new com.letslearnenglish.minecraftplugin.listener.PersonalScenarioListener(this);
        getServer().getPluginManager().registerEvents(personalScenarioListener, this);
        modules().track(personalScenarioListener);
        this.socialListener = new SocialListener(this);
        getServer().getPluginManager().registerEvents(socialListener, this);
        modules().track(socialListener);
        MiscGUIListener miscGUIListener = new MiscGUIListener(this);
        getServer().getPluginManager().registerEvents(miscGUIListener, this);
        modules().track(miscGUIListener);
        getServer().getPluginManager().registerEvents(chatScenarioListener, this);
        modules().track(chatScenarioListener);
        ChatAnswerListener chatAnswerListener = new ChatAnswerListener(this);
        getServer().getPluginManager().registerEvents(chatAnswerListener, this);
        modules().track(chatAnswerListener);
        ChatHistoryListener chatHistoryListener = new ChatHistoryListener(this);
        getServer().getPluginManager().registerEvents(chatHistoryListener, this);
        modules().track(chatHistoryListener);
        DuelGUIListener duelGUIListener = new DuelGUIListener(this);
        getServer().getPluginManager().registerEvents(duelGUIListener, this);
        modules().track(duelGUIListener);
        DuelChallengeListener duelChallengeListener = new DuelChallengeListener(this);
        getServer().getPluginManager().registerEvents(duelChallengeListener, this);
        modules().track(duelChallengeListener);
        DuelListener duelListener = new DuelListener(this);
        getServer().getPluginManager().registerEvents(duelListener, this);
        modules().track(duelListener);
        com.letslearnenglish.minecraftplugin.listener.WrongBookListener wrongBookListener =
                new com.letslearnenglish.minecraftplugin.listener.WrongBookListener(this);
        getServer().getPluginManager().registerEvents(wrongBookListener, this);
        modules().track(wrongBookListener);
        // Wave 4 · US-CLS-2：班级榜（纯 holder 判定，GuiViewKey.CLASS_BOARD）
        com.letslearnenglish.minecraftplugin.listener.ClassBoardListener classBoardListener =
                new com.letslearnenglish.minecraftplugin.listener.ClassBoardListener(this);
        getServer().getPluginManager().registerEvents(classBoardListener, this);
        modules().track(classBoardListener);
        getServer().getPluginManager().registerEvents(soundManager, this);
        modules().track(soundManager);
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
        // 6 个周期任务的句柄全部落在字段里（+ modules.track 记账）：disableAll 的 D1 逐个
        // cancel。绝不能用 scheduler.cancelTasks(plugin) —— 它会连 PlayerDataStore 的异步
        // 落库与 ChatFeedbackManager 的 flush 链一起杀掉。
        autoSaveTask = getServer().getScheduler().runTaskTimer(this, () -> {
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
        modules().track(autoSaveTask);

        if (configManager.getMainConfig().getBoolean("word-system.review.enabled", true)) {
            reviewNotifyTask = getServer().getScheduler().runTaskTimer(this, () -> {
                wordManager.getReviewScheduler().checkAndNotifyReviews();
            }, 6000L, 6000L);
            modules().track(reviewNotifyTask);
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
        leaderboardRefreshTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            progressManager.getLeaderboard().refreshCache();
            if (seasonManager != null) {
                seasonManager.refreshCacheAsync();
            }
        }, refreshTicks, refreshTicks);
        modules().track(leaderboardRefreshTask);
    }

    private void scheduleDailyReminders() {
        List<Integer> reminderHours = configManager.getMainConfig().getIntegerList("daily-reminder.reminder-hours");
        if (reminderHours.isEmpty()) {
            getLogger().warning("Daily reminder is enabled but no reminder-hours configured.");
            return;
        }

        Set<Integer> remindedHours = new HashSet<>();
        final Set<Integer> effectiveHours = new HashSet<>(reminderHours);

        dailyReminderTask = getServer().getScheduler().runTaskTimer(this, () -> {
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
        modules().track(dailyReminderTask);

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

        backupTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            backupDatabase(maxBackups);
        }, intervalTicks, intervalTicks);
        modules().track(backupTask);

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
        // A7：守卫必须同时含 isRuntimeActive()。/lea reload 期间 isEnabled() 恒为 true，
        // 只靠它救不了 —— 任务一旦在拆分之后触发，就会把下一天的任务重排进禁用态。
        if (!isEnabled() || !isRuntimeActive()) {
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
        modules().track(midnightTask);

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

    /**
     * @deprecated A5 起注册动作内联进 {@link #enableAll()}（那里才看得到 {@code papiExpansion}
     * 字段与 D8 的配对关系），本方法保留只为不破坏既有调用点，行为与内联版本一致。
     */
    @Deprecated
    private void setupPlaceholderAPI() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        LLEPapiExpansion expansion = new LLEPapiExpansion(this);
        if (expansion.canRegister()) {
            this.papiExpansion = expansion;
            papiExpansion.register();
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
     * 一个窗口里分叉：{@code /lea reload} 把 {@code server.enabled} 改关的瞬间，配置文件
     * 已经是 false，但拆解还没跑完；反过来改开时，配置已是 true 而装配尚未完成。这两种
     * 窗口里若按裸配置值放行，命令会一路走到 null Manager 上 NPE。用「配置 + 已装配」双条件，
     * 命令在窗口里继续拒绝。
     *
     * <p>判据选 {@code playerDataStore != null}：它是 {@link #enableAll()} 里第一个被赋值的
     * 运行时依赖，也是 {@code HotReloadManager} 短路用的同一个标记，两处语义一致
     *（见该类 reloadAll 的注释）。{@code runtimeActive} 是更早、更粗的一道闸（见字段注释）。
     */
    public boolean isFullyEnabled() {
        // A6：加 runtimeActive 在前。它覆盖「配置已 reload 成 false 但 pds 还没拆完」的那几百
        // 毫秒；playerDataStore != null 一项保留 —— ServerToggleTest 有 6 个用例钉的就是它，
        // 而它同时也是 HotReloadManager 短路用的同一语义。
        return isRuntimeActive() && configManager != null && configManager.isServerEnabled()
                && playerDataStore != null;
    }

    /**
     * 运行时模块是否已装配（true = 监听器/任务/Manager 齐备）。
     *
     * <p>与 {@code isEnabled()} 的区别是本轮改动的核心：{@code /lea reload} 期间
     * {@code isEnabled()} 恒为 true，只有本方法能在拆解窗口里立刻回答「不能再派发业务」。
     * 冻结契约方法，B/C/D 流直接调用。
     */
    public boolean isRuntimeActive() {
        return runtimeActive;
    }

    /**
     * 取状态机实例，null 时就地补一个。
     *
     * <p>为什么需要它：{@code ServerToggleTest} 用 {@code mock(LetsLearnEnglish.class)}
     * （Objenesis，不跑字段初始化器）造实例再 {@code doCallRealMethod().onDisable()}，
     * 此时本字段是 null。直接解引用会 NPE，把「关闭态 onDisable 必须跑完且不抛」这条
     * 既有用例打成红。补出来的实例是「未装配」状态，{@code beginDisable()} 返回 false，
     * 拆解段整体跳过 —— 正是该场景应有的语义。
     */
    private RuntimeModules modules() {
        RuntimeModules m = modules;
        if (m == null) {
            m = new RuntimeModules();
            modules = m;
        }
        return m;
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
