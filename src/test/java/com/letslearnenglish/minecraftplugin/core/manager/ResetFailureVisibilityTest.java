package com.letslearnenglish.minecraftplugin.core.manager;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.DailyChallengeManager;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.core.word.WordLearningManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R11：{@code /lea reset} 不得在删表失败时报"已重置"。
 *
 * <p><b>缺陷链</b>：{@code DatabaseManager.deletePlayerData} 原为 {@code void}，删表失败
 * （整批 rollback、一条数据都没删）时只留一行 WARNING；{@code AdminCommandHandler
 * .resetPlayerData} 在它返回后无条件打印 {@code "Admin: Player data reset complete"} 并
 * {@code return true}；上层 {@code AdminCommand.handleReset} 据此向管理员回
 * {@code admin.player-reset}（"已重置玩家 X 的学习数据"）。这是数据擦除语义 —— 管理员会
 * 认为隐私数据已依法清除。
 *
 * <h2>两个方向</h2>
 * <ul>
 *   <li>{@link #dbFailureIsReportedAndNeverClaimsComplete}：DB 返回 {@code false} →
 *       必须 {@code resetPlayerData == false}、必须有 SEVERE、<b>不得</b>出现
 *       "reset complete" 的 INFO。把 Handler 改回"忽略返回值" → 本用例红。</li>
 *   <li>{@link #dbSuccessIsReportedAndReturnsTrue}：DB 返回 {@code true} →
 *       必须 {@code true} 且有 "reset complete"、无 SEVERE。把 Handler 改成"一律报失败"
 *       → 本用例红。</li>
 *   <li>{@link #notFoundStillReturnsFalseWithoutTouchingDb}：回归守卫 ——
 *       "查无此人"这一既有 {@code false} 语义不得被新分支污染（不得为此多打 SEVERE，
 *       也不得去删表）。</li>
 * </ul>
 *
 * <h2>为什么不破坏既有契约</h2>
 * <p>{@code resetPlayerData} 的返回值仍是 {@code boolean}、仍只有两种取值，上层
 * {@code AdminCommand.handleReset} 的分支（{@code false → admin.player-no-data}）不改。
 * 代价是"重置失败"与"查无此人"共用同一个 {@code false}：管理员的屏幕文案是
 * "未找到该玩家的数据"，**不准确但绝不谎报成功**，且一定会去查日志 —— 而日志里有
 * {@link AdminCommandHandler} 打出的那条 SEVERE，其中明确写了"一条都没删"与"上层会显示
 * 什么"。把三态塞进 boolean 会改上层契约（{@code AdminCommand} 与 i18n 都要动），
 * 超出本任务授权范围；见报告里给出的新 i18n 键供后续收口。
 */
class ResetFailureVisibilityTest {

    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000e5e7");
    private static final String PLAYER_NAME = "Steve";

    private LetsLearnEnglish plugin;
    private DatabaseManager databaseManager;
    private Logger logger;
    private AdminCommandHandler handler;
    private PlayerDataStore dataStore;
    private WordLearningManager wordLearningManager;
    private AchievementManager achievementManager;
    private DailyChallengeManager dailyChallengeManager;
    private ProgressManagerImpl progressManager;
    private Leaderboard leaderboard;
    private RewardEngine rewardEngine;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        databaseManager = mock(DatabaseManager.class);
        logger = mock(Logger.class);
        dataStore = mock(PlayerDataStore.class);
        wordLearningManager = mock(WordLearningManager.class);
        achievementManager = mock(AchievementManager.class);
        dailyChallengeManager = mock(DailyChallengeManager.class);
        progressManager = mock(ProgressManagerImpl.class);
        leaderboard = mock(Leaderboard.class);
        rewardEngine = mock(RewardEngine.class);

        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        when(plugin.getWordLearningManager()).thenReturn(wordLearningManager);
        when(plugin.getAchievementManager()).thenReturn(achievementManager);
        when(plugin.getDailyChallengeManager()).thenReturn(dailyChallengeManager);
        when(plugin.getProgressManager()).thenReturn(progressManager);
        when(progressManager.getLeaderboard()).thenReturn(leaderboard);
        when(progressManager.getRewardEngine()).thenReturn(rewardEngine);
        // 构造器里 new PlayerDirectoryRepository 之后，resolveUuidByName 会走
        // synchronized (db.getConnectionLock()) + db.getConnection().prepareStatement(...)。
        // mock 默认返回 null：前者在同步块入口 NPE，后者在调用处 NPE —— 两者都会把
        // "查无此人"路径变成异常路径。这里给真锁 + 抛 SQLException 的替身连接
        // （等价 EH-01 的 UNAVAILABLE：连库都连不上 = 查不到），让三级解析真正走到
        // "名册也查不到 → null"。**不能**给 getConnection() 打桩返回 mock(Connection)：
        // 那会返回 null 的 ResultSet 并在 rs.next() 处 NPE，同样是假失败。
        when(databaseManager.getConnectionLock()).thenReturn(new Object());
        when(databaseManager.getConnection()).thenReturn(unavailableConnection());

        handler = new AdminCommandHandler(plugin);
    }

    /**
     * 定位仓库内源文件，**不依赖 cwd**。
     *
     * <p>为什么需要：Surefire fork 的 workdir 是模块根目录，而本项目的自建 runner
     * （{@code RunTests}）可以在任意目录启动。相对路径在两者之间不稳定 —— 一旦 cwd
     * 不对，{@link java.nio.file.Files#readString} 抛 {@code NoSuchFileException}，
     * 用例变红，看起来像"结构护栏失败"，实际是路径问题（本项目多次踩过这类假红）。
     * 因此从 classpath 上的类定位 {@code target/classes} 再上溯到仓库根。
     */
    private static java.nio.file.Path locateSource(String relPath) {
        // ① 优先按项目既有约定（WordFreqRankTest.sourceOf 同款）：cwd = 模块根。
        //    Surefire fork 与 mvn 都是这个 cwd，故生产路径下必然命中。
        java.nio.file.Path direct = java.nio.file.Path.of(relPath);
        if (java.nio.file.Files.exists(direct)) {
            return direct;
        }
        java.nio.file.Path cwd = java.nio.file.Path.of(System.getProperty("user.dir", "."));
        java.nio.file.Path fromCwd = cwd.resolve(relPath);
        if (java.nio.file.Files.exists(fromCwd)) {
            return fromCwd;
        }
        // ② 从 user.dir 逐级上溯（cwd 在模块子目录时仍能命中）。
        for (java.nio.file.Path p = cwd; p != null; p = p.getParent()) {
            java.nio.file.Path candidate = p.resolve(relPath);
            if (java.nio.file.Files.exists(candidate)) {
                return candidate;
            }
        }
        // ② 兜底：从本类字节码位置逐级上溯（应对 cwd 被改成别处的临时 runner）。
        try {
            java.nio.file.Path cls = java.nio.file.Path.of(
                    AdminCommandHandler.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            for (java.nio.file.Path p = cls; p != null; p = p.getParent()) {
                java.nio.file.Path candidate = p.resolve(relPath);
                if (java.nio.file.Files.exists(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // 落到下面的断言失败，消息里会带上 relPath 与 cwd
        }
        throw new AssertionError("找不到源文件 " + relPath
                + "（user.dir=" + cwd.toAbsolutePath() + "）");
    }

    /**
     * EH-01 形状的"连接不可用"替身：除 {@code isClosed}/{@code close} 外一律抛
     * {@link java.sql.SQLException}。用它而不是 {@code mock(Connection.class)}，是因为
     * mock 的 {@code prepareStatement} 返回 null，调用方会在 {@code rs.next()} 处 NPE ——
     * 那不是"查不到"，是夹具错，且会以最容易误判的形态出现（看着像代码 bug）。
     */
    private static java.sql.Connection unavailableConnection() {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(),
                new Class<?>[] { java.sql.Connection.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed": return Boolean.FALSE;
                        case "close": return null;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "UnavailableConnection";
                        default: throw new java.sql.SQLException(
                                "Database connection unavailable: " + method.getName());
                    }
                });
    }

    /** 让三级 UUID 解析里的第一级（在线玩家）命中。 */
    private MockedStatic<Bukkit> onlinePlayer() {
        org.bukkit.entity.Player online = mock(org.bukkit.entity.Player.class);
        when(online.getUniqueId()).thenReturn(PLAYER_ID);
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(PLAYER_NAME)).thenReturn(online);
        return bukkit;
    }

    @Test
    @DisplayName("删表失败：返回 false，SEVERE 说明「一条都没删」，绝不说 reset complete")
    void dbFailureIsReportedAndNeverClaimsComplete() {
        when(databaseManager.deletePlayerData(PLAYER_ID)).thenReturn(false);

        try (MockedStatic<Bukkit> ignored = onlinePlayer()) {
            boolean ok = handler.resetPlayerData(PLAYER_NAME);

            assertFalse(ok, "删表整批回滚了却返回 true —— 上层会向管理员回 "
                    + "\"已重置玩家 X 的学习数据\"，而数据一条都没删"
                    + "（隐私删除语义下这是最重的一类谎报）");

            // 必须有一条 SEVERE 串起"哪个玩家 / 后果 / 上层会显示什么"
            verify(logger).severe(contains("reset FAILED"));
            verify(logger).severe(contains(PLAYER_NAME));
            verify(logger).severe(contains("nothing was deleted"));
            // 关键反向断言：不得再出现"已完成"的 INFO（原缺陷就是这一条）
            verify(logger, never()).info(org.mockito.ArgumentMatchers.<String>argThat(
                    msg -> msg != null && msg.startsWith("Admin: Player data reset complete")));
        }
    }

    @Test
    @DisplayName("删表成功：返回 true，INFO 说 reset complete，且无 SEVERE")
    void dbSuccessIsReportedAndReturnsTrue() {
        when(databaseManager.deletePlayerData(PLAYER_ID)).thenReturn(true);

        try (MockedStatic<Bukkit> ignored = onlinePlayer()) {
            boolean ok = handler.resetPlayerData(PLAYER_NAME);

            assertTrue(ok, "删表成功却返回 false —— 管理员会以为重置失败并重复执行，"
                    + "而数据其实已经删干净（过度失败，方向同样有害）");
            verify(logger).info(org.mockito.ArgumentMatchers.<String>argThat(
                    msg -> msg != null && msg.startsWith("Admin: Player data reset complete")));
            verify(logger, never()).severe(anyString());
        }
    }

    @Test
    @DisplayName("回归守卫：查无此人仍是 false，且不得触碰删表（不误打 SEVERE）")
    void notFoundStillReturnsFalseWithoutTouchingDb() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(anyString())).thenReturn(null);
            OfflinePlayer offline = mock(OfflinePlayer.class);
            when(offline.hasPlayedBefore()).thenReturn(false);
            bukkit.when(() -> Bukkit.getOfflinePlayer(anyString())).thenReturn(offline);

            boolean ok = handler.resetPlayerData(PLAYER_NAME);

            assertFalse(ok, "查无此人必须仍是 false");
            verify(databaseManager, never()).deletePlayerData(any());
            // 既有语义：「未找到」只记 WARNING（warning 一行），不得升级成 SEVERE ——
            // 否则运维会把"打错名字"当成数据库故障。
            verify(logger, never()).severe(anyString());
        }
    }

    /**
     * 结构守卫：{@code resetPlayerData} 的返回值必须仍然被"当作判据"使用。
     *
     * <p>为什么需要：{@code boolean} 返回值被调用方忽略在 Java 里完全合法，所有行为测试
     * 都能通过 —— 缺陷会原样复发。这里直接读源码，钉住 {@code deletePlayerData(...)} 的
     * 结果被赋给一个局部变量并且该变量被 {@code if (!…)} 判过。
     *
     * <p>用<b>两条独立 {@code find()} 定位调用点</b>（而不是全局 indexOf）：本类里
     * {@code getDatabaseManager()} 出现多次，锚点必须锁定那一次删表调用。
     */
    @Test
    @DisplayName("结构守卫：AdminCommandHandler 必须检查 deletePlayerData 的返回值")
    void handlerMustCheckDeleteResult() throws Exception {
        java.nio.file.Path src = locateSource(
                "src/main/java/com/letslearnenglish/minecraftplugin/core/manager/"
                        + "AdminCommandHandler.java");
        String code = java.nio.file.Files.readString(src);

        int call = code.indexOf("deletePlayerData(playerId)");
        assertTrue(call > 0, "找不到 deletePlayerData 调用点，结构守卫失效（重构后需更新锚点）");
        int stmtStart = code.lastIndexOf("boolean deleted =", call);
        assertTrue(stmtStart > 0 && stmtStart < call,
                "deletePlayerData 的返回值没有被接收 —— 删表失败将重新变成不可观测，"
                        + "「reset 报成功、数据一条没删」的缺陷会立刻复发");
        assertTrue(code.indexOf("if (!deleted)", call) > call,
                "返回值被接收了但从未被判 —— 与不接收等价");
    }
}
