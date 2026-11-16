package com.letslearnenglish.minecraftplugin.core.ai;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R4-05-logic-F-L2：{@code latestSessionId} 的双侧契约 —— <b>判据要靠它</b>（endConversation
 * 之后必须还在），<b>内存卫生要清它</b>（quit / shutdown 之后必须不在）。
 *
 * <p><b>为什么需要这个类</b>：{@code latestSessionId} 是
 * {@link AIChatManager#isLatestSession} 的唯一数据源，而它的两个生命周期约束都是
 * 「没有可见症状」的那种缺陷：
 * <ul>
 *   <li>把 {@code remove} 挪进 {@code endConversation} —— 看起来更"对称"，实际会让
 *       「A 结束 → A 的点评到达」这条最常见的路径从即时显示退化成静默落库，
 *       玩家再也看不到点评。删掉 {@code endConversation} 里不存在的 remove 不会有红灯，
 *       但把 remove 加进去同样不会有红灯（除非有本类第一条断言）。</li>
 *   <li>完全不清理 —— 每曾进服玩家永久留一条，长期不重启的服上只增不删。
 *       这条<b>没有任何运行期行为</b>可观测，只有本类第二条断言能钉住。</li>
 * </ul>
 * 两条断言方向相反，缺一条就会诱导实现往错误的方向"对称化"。
 *
 * <p><b>技术前提</b>（照 {@code AIChatThinkingModeTest} 的既有做法）：
 * {@link AIChatManager} 构造器会 {@code loadConfig()} + {@code loadScenariosConfig()}，
 * 后者要读 {@code ai_scenarios.yml} —— 数据目录指到 {@link TempDir} 后文件不存在，
 * 走 {@code loadBuiltinScenarios()} 内置兜底；主配置必须是<b>真</b>
 * {@link YamlConfiguration}，否则 {@code getInt(key, def)} 的默认值不生效。
 */
class AIChatLatestSessionLifecycleTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e7");
    private static final String SCENARIO = "village_trade";

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration mainConfig;
    private DatabaseManager databaseManager;
    private Player player;
    private AIChatManager ai;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        mainConfig = new YamlConfiguration();
        databaseManager = mock(DatabaseManager.class);
        player = mock(Player.class);

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AIChatLatestSessionLifecycleTest"));
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(configManager.isAIChatEnabled()).thenReturn(true);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        // startConversation 的 system prompt 写库走 runAsync，isShuttingDownOrDisabled() 会看它。
        when(plugin.isEnabled()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        mainConfig.set("ai-chat.focus-words-max", 5);
        ai = new AIChatManager(plugin);
    }

    /** 开一场会话并返回它的 sessionId。 */
    private String startSession() {
        ai.startConversation(player, SCENARIO);
        String sessionId = ai.getActiveSessionId(PLAYER_ID);
        assertNotNull(sessionId, "前置条件不成立：startConversation 没有建立会话");
        return sessionId;
    }

    /**
     * 正向：会话结束后 {@code latestSessionId} 必须<b>保留</b> —— 这正是判据存在的理由。
     *
     * <p>「A 结束 → A 的点评到达」是最常见的路径：此刻 {@code sessions} 已摘除 A，
     * 若 latest 也被一起清掉，{@code isLatestSession} 返回 false，点评会被静默吞掉。
     * 这个断言同时防住「把 remove 挪进 endConversation 做对称化」这个诱人的错误重构。
     */
    @Test
    @DisplayName("endConversation 之后 isLatestSession 仍为 true（判据依赖它，不能清）")
    void endConversationKeepsLatestSessionId() {
        String sessionId = startSession();
        assertTrue(ai.isLatestSession(PLAYER_ID, sessionId), "会话刚开，latest 必须是它");

        ai.endConversation(player);

        assertFalse(ai.hasActiveConversation(player), "前置条件：endConversation 必须摘除活跃会话");
        assertTrue(ai.isLatestSession(PLAYER_ID, sessionId),
                "endConversation 把 latestSessionId 也清了 —— "
                        + "「A 结束 → A 的点评到达」会被判成非最近会话，点评不再显示喵");
    }

    /**
     * 反向：开新会话后旧会话必须立刻不再是 latest。
     *
     * <p>这是防串台判据的主语义（A 结束 → 开 B → A 的点评到达时不得显示）。
     */
    @Test
    @DisplayName("开新会话后，旧会话的 isLatestSession 立刻变 false")
    void newConversationSupersedesPrevious() {
        String first = startSession();
        ai.endConversation(player);
        String second = startSession();

        assertFalse(ai.isLatestSession(PLAYER_ID, first),
                "开了新会话但旧会话仍被判为 latest —— 防串台判据完全失效喵");
        assertTrue(ai.isLatestSession(PLAYER_ID, second));
    }

    /**
     * 内存卫生：quit（{@code unloadPlayer}）必须清掉该玩家的 latest 条目。
     *
     * <p>为什么 quit 清是安全的：清掉后判据恒为 false → 迟到的点评一律走静默分支
     * （{@code applyToLearningLoop(notify=false)} + {@code persistAfter} 落库），
     * 而 quit 后玩家已离线，显示分支本来也只会往离线 Player 发消息（{@code applyToLearningLoop}
     * 首行 {@code isOnline()} 即 return），数据层口径（{@code summary.sessionId()}）完全不变。
     * 即「判据为 false」与「条目被清掉」在 quit 之后同义。
     */
    @Test
    @DisplayName("unloadPlayer（quit）之后 isLatestSession 必须为 false —— 否则每玩家永久留条")
    void unloadPlayerClearsLatestSessionId() {
        String sessionId = startSession();
        ai.endConversation(player);

        ai.unloadPlayer(PLAYER_ID);

        assertFalse(ai.isLatestSession(PLAYER_ID, sessionId),
                "quit 没有清 latestSessionId —— 每曾进服玩家永久留一条（只增不删）喵");
    }

    /** 内存卫生：shutdown 必须整体清空。 */
    @Test
    @DisplayName("shutdown 之后 isLatestSession 必须为 false")
    void shutdownClearsLatestSessionId() {
        String sessionId = startSession();

        ai.shutdown();

        assertFalse(ai.isLatestSession(PLAYER_ID, sessionId),
                "shutdown 没有清 latestSessionId —— 停用后仍持有玩家 UUID 条目喵");
    }
}
