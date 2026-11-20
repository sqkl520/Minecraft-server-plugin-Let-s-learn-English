package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.onboarding.OnboardingManager;
import com.letslearnenglish.minecraftplugin.core.season.SeasonManager;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R18-M1：{@code PlayerJoinListener.onPlayerJoin} 的进服预热拆分验收。
 *
 * <h2>被修的缺陷（MEDIUM）</h2>
 * <p>进服预热的三个 load 原本全在 {@code @EventHandler} 方法体里 = <b>主线程</b>：
 * {@code loadPlayerData}（word_progress 一条 + player_data 一条 + fillMissing，外加
 * difficult_words 一条）、{@code loadPlayerAchievements}（achievements 一条）、
 * {@code loadProfileSettings}（player_profiles 一条）。多人同时进服（服务器重启后的
 * 典型场景）时这些 SELECT 在主线程上<b>串行</b>排队。
 *
 * <h2>为什么不能三行一起搬 —— 本类钉住的四条事实</h2>
 * <ol>
 *   <li><b>统计/成就可以异步</b>：{@code onPlayerJoin} 后文没有一处读这两份缓存
 *       （每日挑战走仓储自己的 COUNT/INSERT，本类另一条用例顺带确认），其余消费点
 *       全是「miss 懒加载」；</li>
 *   <li><b>profiles 必须同步</b>：{@code sendJoinDailyReminderIfEnabled} 与
 *       {@code OnboardingManager.handleJoin} 在<b>同一方法体的后文</b>就同步读
 *       {@code isRemindersEnabled()}，而该位在缓存 miss 时是 <b>fail-open（true）</b> ——
 *       异步化 = 「玩家关掉了提醒」被读成「开着」，关掉提醒的人照样收到进服提醒。
 *       这是越权打扰，不是隐私方向的安全降级，所以 {@link #profileSettingsStillLoadSynchronously()}
 *       把它钉在同步段；</li>
 *   <li><b>join→quit 竞态会复活已下线玩家的缓存</b>：异步任务可能在
 *       {@code unloadPlayerData} 之后才跑完，那时灌缓存等于给已下线玩家留一份台风眼里的
 *       内存态（它不会再被 quit 清一次）。{@link #joinQuitRaceMustNotRepopulateCache()}
 *       用「profile 缓存键已消失」模拟 quit，要求预热自我作废；</li>
 *   <li><b>进服路径不许抛</b>：异步段里未捕获的异常会变成一条与真实原因无关的裸堆栈，
 *       调度器不可用时 {@code IllegalPluginAccessException}（真实类型，不是
 *       {@code IllegalStateException} 的子类）也不许冒出去。</li>
 * </ol>
 *
 * <h2>为什么用 mock 而不是真库</h2>
 * <p>本类要断言的是<b>编排</b>（在主线程还是调度线程上调、调不调），数据库行为由
 * {@code PlayerDataStore} 自己的用例（{@code DifficultWordsReadFailureTest} 等）负责。
 * 用 mock 才能做到「挂起异步任务 → 先断言主线程段 → 再执行异步段」的分离观察，
 * 这是「改回同步必须变红」的前提。
 *
 * <p><b>调度器就地挂起</b>：{@code runTaskAsynchronously} 的 Runnable 不立即执行，
 * 而是收进 {@link #asyncTasks}；由用例显式 {@link #runAllAsync()}。真实线程池会让
 * 「主线程有没有查库」这件事不可观测，也会让 join→quit 的顺序无法确定。
 */
@DisplayName("PlayerJoinListener 进服预热 — 统计/成就异步、隐私设置同步")
class PlayerJoinAsyncLoadTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000a180");

    private LetsLearnEnglish plugin;
    private Server server;
    private BukkitScheduler scheduler;
    private PlayerDataStore pds;
    private AchievementManager achievements;

    /** 所有派发出去的异步任务，按派发顺序（预热在前、每日挑战在后）。 */
    private final List<Runnable> asyncTasks = new ArrayList<>();

    private Captured logs;
    private MockedStatic<org.bukkit.Bukkit> bukkit;

    /** 日志探针（本仓每个测试类各带一份，互不共享 logger）。 */
    private static final class Captured implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final List<Level> levels = Collections.synchronizedList(new ArrayList<>());
        final Logger logger;
        private final Handler handler;

        Captured() {
            this.logger = Logger.getLogger("PlayerJoinAsyncLoadTest." + UUID.randomUUID());
            this.logger.setUseParentHandlers(false);
            this.handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    messages.add(record.getMessage() == null ? "" : record.getMessage());
                    levels.add(record.getLevel());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            logger.addHandler(handler);
        }

        boolean has(Level level, String needle) {
            synchronized (messages) {
                for (int i = 0; i < messages.size(); i++) {
                    if (levels.get(i).equals(level) && messages.get(i).contains(needle)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    @BeforeEach
    void setUp() {
        logs = new Captured();
        plugin = mock(LetsLearnEnglish.class);

        scheduler = mock(BukkitScheduler.class);
        // 异步任务就地<b>挂起</b>（不执行）—— 用例控制执行时刻。
        when(scheduler.runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    asyncTasks.add(inv.getArgument(1));
                    return null;
                });
        // join 里还有 60L / 40L 的 runTaskLater（更新提示 / 欢迎语 / 引导 intro）：
        // 本类不测它们，但必须让调度调用成功返回，否则 join 编排会在中途 NPE 打断。
        when(scheduler.runTaskLater(any(org.bukkit.plugin.Plugin.class), any(Runnable.class), any(Long.class)))
                .thenReturn(null);

        server = mock(Server.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logs.logger);

        bukkit = mockStatic(org.bukkit.Bukkit.class);
        bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        logs.close();
    }

    // ==================================================================
    // 脚手架
    // ==================================================================

    /**
     * 让 join 方法体里除「预热」以外的分支全部安全短路，并返回可打桩的两个协作方。
     *
     * <p>{@link LetsLearnEnglish} 是 mock，未打桩的 getter 返回 null —— join 方法体里
     * 对 {@code getSocialManager()} 等的直接解引用会 NPE，所以用到的那几个必须桩出来，
     * 否则用例会在与缺陷无关的地方红。
     */
    private void stubCollaborators() {
        pds = mock(PlayerDataStore.class);
        achievements = mock(AchievementManager.class);
        OnboardingManager onboarding = mock(OnboardingManager.class);
        SocialManager social = mock(SocialManager.class);
        SeasonManager season = mock(SeasonManager.class);

        when(plugin.getPlayerDataStore()).thenReturn(pds);
        when(plugin.getAchievementManager()).thenReturn(achievements);
        when(plugin.getOnboardingManager()).thenReturn(onboarding);
        when(plugin.getSocialManager()).thenReturn(social);
        when(plugin.getSeasonManager()).thenReturn(season);
        when(plugin.getDuelManager()).thenReturn(null); // 空守卫分支
        // SEVERE 边界里会发 daily.load-failed 提示：语言解析必须可短路。
        MessageUtil messages = mock(MessageUtil.class);
        when(messages.getPlayerMessage(any(Player.class), any(String.class), any(String[].class)))
                .thenReturn("");
        when(plugin.getMessageUtil()).thenReturn(messages);
        // 每日挑战那条异步块：置 null 让它自己早退，避免与本类的预热断言互相干扰。
        when(plugin.getDailyChallengeManager()).thenReturn(null);
        // 稳态：运行时装配完整、配置开启。未打桩时 mock 返回 false，预热会被闸拦掉。
        when(plugin.isFullyEnabled()).thenReturn(true);
    }

    private Player joinPlayer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(ID);
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
        // 老玩家：跳过 welcome×3 与 onboarding 首进分支，减少无关噪音。
        when(player.hasPlayedBefore()).thenReturn(true);
        return player;
    }

    /** 主线程段：只调 join，异步任务保持挂起。 */
    private void runJoinMainThreadSection() {
        new PlayerJoinListener(plugin).onPlayerJoin(new PlayerJoinEvent(joinPlayer(), "Alice joined"));
    }

    /** 依次执行挂起的异步任务（模拟调度线程稍后跑到）。 */
    private void runAllAsync() {
        List<Runnable> snapshot = new ArrayList<>(asyncTasks);
        for (Runnable task : snapshot) {
            task.run(); // 刻意不 try/catch：生产代码必须自己吃掉异常
        }
    }

    /** profile 缓存里有该玩家条目（= 本次会话已建立，quit 尚未发生）。 */
    private void profileCachePresent() {
        when(pds.loadProfileSettings(ID)).thenReturn(true);
        when(pds.getCachedProfileSettings(ID)).thenReturn(new PlayerDataStore.ProfileSettings());
    }

    // ==================================================================
    // 行为 1：统计 / 成就搬出主线程
    // ==================================================================

    /**
     * 主线程一次都不该调 {@code loadPlayerData} / {@code loadPlayerAchievements}；
     * 异步段跑完后两者各调一次。
     *
     * <p>判别力：把这两行搬回主线程（或被搬到「异步段之外」），本用例在
     * {@code verify(..., never())} 处立刻红 —— 这正是 {@code R13-M1}（每日挑战分配）
     * 当初被修掉的同一类缺陷。
     */
    @Test
    @DisplayName("R18-M1：统计/成就不在主线程加载，异步段跑完后各加载一次")
    void statsAndAchievementsLoadOffTheMainThread() {
        stubCollaborators();
        profileCachePresent();

        runJoinMainThreadSection();

        // 主线程段结束（onPlayerJoin 已返回）：两份缓存都还没被加载。
        verify(pds, never()).loadPlayerData(any(UUID.class));
        verify(achievements, never()).loadPlayerAchievements(any(UUID.class));
        assertTrue(asyncTasks.size() >= 1,
                "前置：join 必须派发过预热异步任务喵（否则本用例什么都没测）");

        runAllAsync();

        verify(pds).loadPlayerData(ID);
        verify(achievements).loadPlayerAchievements(ID);
    }

    // ==================================================================
    // 行为 2：join → quit 竞态（守卫必须存在）
    // ==================================================================

    /**
     * <b>这是本类真正能杀死「裸搬异步」的那个用例。</b>
     *
     * <p>场景：玩家进服 → 异步预热还在排队 → 玩家秒退 → {@code onPlayerQuit} 跑完
     * {@code unloadPlayerData} / {@code unloadProfileSettings}。若无守卫，迟到的任务
     * 仍会把十几张表灌进缓存 —— 已下线玩家的内存态被「复活」，而 quit 已经跑过，
     * 不会再清一次，只能等下一轮 {@code evictOfflinePlayerCaches} 才回收。
     *
     * <p>这里用「profile 缓存键已消失」模拟 quit（{@code unloadProfileSettings} 删的正是
     * 这个键），断言预热自我作废：一次都不加载。
     *
     * <p>判别力：删掉生产代码里的那句 {@code getCachedProfileSettings(...) == null} 守卫，
     * 本用例立刻红（{@code loadPlayerData} 会被调用一次）。
     */
    @Test
    @DisplayName("R18-M1：join→quit 竞态 — 迟到的预热不得复活已下线玩家的缓存")
    void joinQuitRaceMustNotRepopulateCache() {
        stubCollaborators();
        // 进服时 profile 读成功（键建立）……
        when(pds.loadProfileSettings(ID)).thenReturn(true);
        // ……但任务执行时键已经没了（= quit 的 unloadProfileSettings 已跑过）。
        when(pds.getCachedProfileSettings(ID)).thenReturn(null);

        runJoinMainThreadSection();
        runAllAsync();

        verify(pds, never()).loadPlayerData(any(UUID.class));
        verify(achievements, never()).loadPlayerAchievements(any(UUID.class));
    }

    /**
     * 反向用例：<b>不能</b>因为加了竞态守卫就把正常路径也挡掉。
     *
     * <p>与 {@link #joinQuitRaceMustNotRepopulateCache()} 成对：两条只差「键在 / 键没了」，
     * 一条要求不加载、一条要求照常加载。只满足其中一条的实现都是半修
     * （「一律不加载」= 预热彻底失效；「一律加载」= 竞态泄漏）。
     */
    @Test
    @DisplayName("R18-M1：键仍在（玩家还在线）时预热照常执行")
    void liveSessionStillPreloads() {
        stubCollaborators();
        profileCachePresent();

        runJoinMainThreadSection();
        runAllAsync();

        verify(pds).loadPlayerData(ID);
        verify(achievements).loadPlayerAchievements(ID);
    }

    /**
     * 文档化守卫生效的<b>前提</b>：{@code loadProfileSettings} 读失败（返回 false）时
     * 没有可靠的在线判据，此时选择继续预热（fail-open）而不是跳过。
     *
     * <p>为什么这个方向是对的：跳过会让「一次 DB 抖动」变成「本次会话完全不预热」，
     * 而预热失败的代价只是多几次懒加载；而不预热的代价是统计/成就一路 miss
     * （每次展示都同步查库）。隐私面不受影响 —— 隐私位读的是
     * {@code getProfileSettingsReadOnly}，它在 miss 时是 fail-closed（隐藏）。
     *
     * <p>残余窗口（本类不试图掩盖）：这条路径下若玩家秒退，迟到的预热仍可能复活缓存，
     * 直到下一轮 {@code evictOfflinePlayerCaches} 回收。彻底封死需要把
     * {@code PlayerDataStore.playerCacheEpoch} 的世代号暴露到公开面（当前为 private），
     * 超出本轮改动范围。
     */
    @Test
    @DisplayName("R18-M1：profile 读失败（无可靠在线判据）→ 预热继续，不因一次抖动停摆")
    void profileLoadFailureStillAllowsPreload() {
        stubCollaborators();
        when(pds.loadProfileSettings(ID)).thenReturn(false);
        when(pds.getCachedProfileSettings(ID)).thenReturn(null);

        runJoinMainThreadSection();
        runAllAsync();

        verify(pds).loadPlayerData(ID);
        verify(achievements).loadPlayerAchievements(ID);
    }

    // ==================================================================
    // 行为 3：隐私 / 提醒开关仍在同步段（不得倒退）
    // ==================================================================

    /**
     * <b>隐私与提醒不倒退的钉子。</b>
     *
     * <p>{@code loadProfileSettings} 的结果在 {@code onPlayerJoin} <b>后文</b>被同步消费：
     * {@code sendJoinDailyReminderIfEnabled}（LetsLearnEnglish:1183）与
     * {@code OnboardingManager.handleJoin}（OnboardingManager:154）都读
     * {@code isRemindersEnabled()}。该位在缓存 miss 时是 <b>fail-open</b>
     * （{@code unresolvedFallback} 只收紧三个隐私位），所以异步化会让「关掉提醒的玩家」
     * 照样收到进服提醒 —— 可感知的越权打扰。
     *
     * <p>因此本方法必须在 {@code onPlayerJoin} <b>返回之前</b>（= 异步段执行之前）
     * 就已调用。判别力：把这行搬进异步段，本用例在下面的 {@code verify} 处红，
     * 因为此刻异步任务还全部挂起。
     */
    @Test
    @DisplayName("R18-M1：loadProfileSettings 仍在同步段（提醒开关 fail-open，不能异步）")
    void profileSettingsStillLoadSynchronously() {
        stubCollaborators();
        profileCachePresent();

        runJoinMainThreadSection();

        // 注意：没有 runAllAsync()。异步段此时全部挂起，因此这条 verify 只能由
        // 主线程段满足 —— 「搬进异步」会让它变红。
        verify(pds, times(1)).loadProfileSettings(ID);
    }

    // ==================================================================
    // 行为 4：进服路径的异常边界
    // ==================================================================

    /**
     * 异步段里的 {@code RuntimeException}（DB 抖动 / reload 窗口）必须收口成一条
     * WARNING，<b>不能</b>从调度线程冒出。
     *
     * <p>理由：Bukkit 的异步调度只打印不归因；一条裸堆栈会被服主读成「插件崩了」，
     * 而真实后果只是「本次进服少一次预热」（读侧 miss 会懒加载）。
     */
    @Test
    @DisplayName("R18-M1：预热抛 RuntimeException → 收口成 WARNING，不逃逸")
    void preloadFailureIsContained() {
        stubCollaborators();
        profileCachePresent();
        doThrow(new RuntimeException("db gone")).when(pds).loadPlayerData(ID);

        runJoinMainThreadSection();
        runAllAsync(); // 不 try/catch：异常必须被生产代码吃掉

        assertTrue(logs.has(Level.WARNING, "Async join preload failed"),
                "预热失败必须留一条可读 WARNING 喵（否则服主只看到一条裸堆栈）。实测：" + logs.messages);
    }

    /**
     * 插件停用窗口：{@code runTaskAsynchronously} 抛 {@code IllegalPluginAccessException}
     * （调度器已关闭）时，join 必须照常走完 —— <b>进服路径不许因为预热而抛</b>。
     *
     * <p>这是「宁可少预热一个，也不要让玩家进不来」的直接落地。
     *
     * <p><b>类型是真实的</b>：CraftScheduler.validate() 在插件已禁用时抛的就是这个类，
     * 它 extends RuntimeException 而<b>不是</b> IllegalStateException 的子类。本用例曾抛
     * ISE —— 测试替身复刻了生产代码的错误假设，所以守卫写错也永远绿。
     */
    @Test
    @DisplayName("R18-M1：调度器已关闭（IllegalPluginAccessException）→ join 不抛，主线程段照常")
    void closedSchedulerDoesNotBreakJoin() {
        stubCollaborators();
        profileCachePresent();
        when(scheduler.runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class), any(Runnable.class)))
                .thenThrow(new IllegalPluginAccessException("Plugin attempted to register task while disabled"));

        runJoinMainThreadSection(); // 旧式「无 try 的派发」会在这里把异常抛出去

        verify(pds, times(1)).loadProfileSettings(ID);
        assertFalse(logs.has(Level.SEVERE, "Player data load failed"),
                "停用窗口不是「玩家数据加载失败」，不该打 SEVERE 喵。实测：" + logs.messages);
    }

    /**
     * 同步段的 {@code loadProfileSettings} 自己抛 {@code RuntimeException} 时，
     * 17-EH-F1 的异常边界仍要兜住（该边界是既有行为，本轮搬家不得把它弄丢）：
     * 不抛、留一条 SEVERE、并且<b>不派发预热</b>（本次会话没建立，异步加载没有意义）。
     */
    @Test
    @DisplayName("R18-M1：同步段抛异常 → 被既有边界兜住，且不派发预热")
    void synchronousFailureIsStillContained() {
        stubCollaborators();
        when(pds.loadProfileSettings(ID)).thenThrow(new RuntimeException("Failed to get database connection"));

        runJoinMainThreadSection(); // 不得抛出

        assertTrue(logs.has(Level.SEVERE, "Player data load failed"),
                "既有 17-EH-F1 边界必须仍然留痕喵。实测：" + logs.messages);
        verify(pds, never()).loadPlayerData(any(UUID.class));
        for (Runnable task : asyncTasks) {
            task.run();
        }
        verify(pds, never()).loadPlayerData(any(UUID.class));
    }

    // ==================================================================
    // 结构 lint：主线程段不许出现统计/成就的加载，且不许把它们整段删掉
    // ==================================================================

    /**
     * 源码级断言（行为测试的补位）。
     *
     * <p>为什么需要它：上面几条用例是「在 mock 上观察编排」，若有人把 {@code onPlayerJoin}
     * 重写成调一个 helper，行为测试的桩就会失效（改调别人了），lint 至少保证
     * 「方法体里主线程段不出现这两行」。反过来，若有人为了过 lint 把两行<b>删掉</b>
     * （预热彻底消失），本用例也要求它们在异步段里出现 —— 删不得。
     *
     * <p>锚<b>方法名</b>不锚行号；先剥注释，免得注释里的类名/方法名把断言喂饱。
     */
    @Test
    @DisplayName("R18-M1：onPlayerJoin 主线程段无 loadPlayerData/loadPlayerAchievements，且两者仍在异步段")
    void mainThreadSectionHasNoStatsOrAchievementLoads() throws Exception {
        String body = methodBody("PlayerJoinListener.java", "public void onPlayerJoin(");

        int asyncAt = body.indexOf("runTaskAsynchronously(");
        assertTrue(asyncAt > 0,
                "onPlayerJoin 里找不到 runTaskAsynchronously( 喵 —— 预热不再是异步了？"
                        + "请先确认现状，再同步本 lint。");

        String mainThreadPart = body.substring(0, asyncAt);
        String asyncPart = body.substring(asyncAt);

        assertFalse(mainThreadPart.contains("loadPlayerData("),
                "onPlayerJoin 的主线程段仍在调 loadPlayerData( 喵 —— 3 条 SELECT + fillMissing"
                        + " 又回到进服主线程了（多人同时进服时串行压 TPS）。");
        assertFalse(mainThreadPart.contains("loadPlayerAchievements("),
                "onPlayerJoin 的主线程段仍在调 loadPlayerAchievements( 喵 —— 成就表的 SELECT"
                        + " 又回到进服主线程了。");
        assertTrue(mainThreadPart.contains("loadProfileSettings("),
                "onPlayerJoin 的主线程段里找不到 loadProfileSettings( 喵 —— 它必须同步喵："
                        + "提醒开关（isRemindersEnabled）在后文同步消费，而它 miss 时 fail-open，"
                        + "异步化会让关掉提醒的玩家照样收到进服提醒。");

        assertTrue(asyncPart.contains("loadPlayerData("),
                "异步段里找不到 loadPlayerData( 喵 —— 预热不是被搬走，而是被删掉了（本次会话"
                        + "统计/成就全靠懒加载，首次展示仍会同步查库）。");
        assertTrue(asyncPart.contains("loadPlayerAchievements("),
                "异步段里找不到 loadPlayerAchievements( 喵 —— 同上。");
    }

    /**
     * 闸的顺序断言：{@code isFullyEnabled()} 必须出现在异步段里、且早于两行 load。
     *
     * <p>与 {@code ServerToggleLintsTest} 的 L11（每日挑战块）同源：reload 拆解窗口里
     * {@code isEnabled()} 恒为 true，只有 {@code isFullyEnabled()} 能立刻回答
     * 「不能再派发业务」。放在 load 之后等于没放。
     */
    @Test
    @DisplayName("R18-M1：预热异步段用 isFullyEnabled 且早于 load 调用")
    void preloadGatePrecedesLoads() throws Exception {
        String body = methodBody("PlayerJoinListener.java", "public void onPlayerJoin(");
        int asyncAt = body.indexOf("runTaskAsynchronously(");
        assertTrue(asyncAt > 0, "找不到 runTaskAsynchronously( 喵 —— 定位失效。");

        int gateAt = body.indexOf("isFullyEnabled()", asyncAt);
        int loadAt = body.indexOf("loadPlayerData(", asyncAt);
        assertTrue(gateAt > 0, "预热异步段里找不到 isFullyEnabled() 喵 —— 裸 isEnabled() 在"
                + " /lea reload 期间恒为 true，闸形同虚设。");
        assertTrue(loadAt > gateAt,
                "isFullyEnabled() 出现在 loadPlayerData( <b>之后</b> 喵（闸=" + gateAt
                        + "，加载=" + loadAt + "）—— 放在加载之后的守卫挡不住任何东西。");
    }

    // ==================================================================
    // 源码读取工具（剥注释 + 按签名截方法体）
    // ==================================================================

    private static String methodBody(String fileName, String signature) throws IOException {
        String stripped = stripComments(readSource(fileName));
        int sigIndex = stripped.indexOf(signature);
        if (sigIndex < 0) {
            fail("源码中找不到方法签名「" + signature + "」喵 —— 方法可能被改名/改签名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (stripped.indexOf(signature, sigIndex + 1) >= 0) {
            fail("方法签名「" + signature + "」在源码中出现多次，无法唯一定位方法体喵。");
        }
        int bodyStart = stripped.indexOf('{', sigIndex + signature.length());
        if (bodyStart < 0) {
            fail("方法签名「" + signature + "」之后找不到左花括号喵。");
        }
        String between = stripped.substring(sigIndex + signature.length(), bodyStart);
        if (between.indexOf(';') >= 0) {
            fail("方法签名与左花括号之间出现了分号，匹配到的可能不是带体的方法喵："
                    + between.strip());
        }
        int close = findBlockEnd(stripped, bodyStart);
        if (close < 0) {
            fail("方法签名「" + signature + "」的花括号没有配对结束喵。");
        }
        return stripped.substring(bodyStart + 1, close);
    }

    private static int findBlockEnd(String s, int openBraceIdx) {
        int depth = 0;
        for (int i = openBraceIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 从 cwd / code-source 各自上溯找仓库根（不依赖 cwd），按文件名唯一定位源码。 */
    private static String readSource(String fileName) throws IOException {
        List<Path> starts = new ArrayList<>();
        String cwd = System.getProperty("user.dir");
        if (cwd != null) {
            starts.add(Path.of(cwd).toAbsolutePath());
        }
        try {
            starts.add(Path.of(PlayerJoinListener.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath());
        } catch (Exception ignored) {
            // 只靠 cwd
        }
        for (Path start : starts) {
            for (Path cur = start; cur != null; cur = cur.getParent()) {
                Path root = cur.resolve("src/main/java/com/letslearnenglish/minecraftplugin");
                Path direct = cur.resolve("src/main/java/com/letslearnenglish/minecraftplugin/listener")
                        .resolve(fileName);
                if (Files.isRegularFile(direct)) {
                    return Files.readString(direct, StandardCharsets.UTF_8);
                }
                if (!Files.isDirectory(root)) {
                    continue;
                }
                List<Path> hits = new ArrayList<>();
                try (var stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals(fileName))
                            .forEach(hits::add);
                }
                if (hits.size() == 1) {
                    return Files.readString(hits.get(0), StandardCharsets.UTF_8);
                }
                if (hits.size() > 1) {
                    fail("源码树里有 " + hits.size() + " 个 " + fileName + "，无法唯一定位喵。");
                }
            }
        }
        throw new AssertionError("找不到 " + fileName + "（试过 " + starts + "）喵 —— "
                + "本断言是语义检查，找不到就必须红灯，不能 skip。");
    }

    /**
     * 剥注释 + 抹字面量（保持长度不变，偏移/行号不错位）。
     *
     * <p>不剥的话，本类自己的注释里写满了 {@code loadPlayerData(} 等字样，
     * 断言会被注释喂饱 —— 那是「假绿」。与 {@code ServerToggleLintsTest.stripComments}
     * 同姿态（本类独立实现，刻意不复用其私有工具）。
     */
    private static String stripComments(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j < n && !(source.charAt(j) == '*' && j + 1 < n
                        && source.charAt(j + 1) == '/')) {
                    j++;
                }
                j = (j < n) ? Math.min(n, j + 2) : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"'
                    && source.charAt(i + 2) == '"') {
                int j = i + 3;
                while (j + 2 < n
                        && !(source.charAt(j) == '"' && source.charAt(j + 1) == '"'
                        && source.charAt(j + 2) == '"')) {
                    if (source.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = (j + 2 < n) ? j + 3 : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '"' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                blank(out, i, Math.min(j, n));
                i = j;
                continue;
            }

            i++;
        }
        return new String(out);
    }

    private static void blank(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
    }
}
