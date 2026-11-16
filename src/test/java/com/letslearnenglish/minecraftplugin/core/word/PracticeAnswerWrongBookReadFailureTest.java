package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A5/D5（LOW-MEDIUM）回归测试：{@code getWrongCount → 0} 在
 * {@code submitPracticeAnswer} 的 {@code newCount <= 0} 分支会真的 DELETE。
 *
 * <h2>失败序列（缺一不成灾）</h2>
 * <ol>
 *   <li>玩家进服时错题本加载失败（SELECT 抖动）→ {@code wrongCountCache} 保持 null，
 *       {@code difficultWordsReadComplete} 不登记（读失败不发布，见 s16dc-02 修复）；</li>
 *   <li>玩家在错题本里 Shift+点击练习，答对了 —— <b>这是本条缺陷的入口</b>；</li>
 *   <li>{@code store.decrementWrongCount} 之后 {@code store.getWrongCount} 缓存 miss，
 *       又读不到库 → <b>返回 0</b>（那是"错次 0"与"不知道"同形的那个 return）；</li>
 *   <li>{@code newCount <= 0} 成立 → {@code removeDifficultWord}（<b>DB DELETE</b>）+
 *       {@code incrementWrongBookCleared}（成就计数 +1，同一动作被重复计入）。</li>
 * </ol>
 * 结果：DB 里 {@code wrong_count=7} 的词整行永久消失，而玩家只是"答对了一次"。
 *
 * <h2>为什么这条与已修的 {@code flushDifficultWords} 不是同一处</h2>
 * 那里有 {@code difficultWordsReadComplete} 守卫（护的是 quit 刷盘的/**绝对值**写与删除）。
 * 本条是<b>同一个 {@code return 0} 的另一个出口</b>：练习答对路径的**即时**删除，
 * 从来没有那道守卫。所以本类的变异指向是 {@code submitPracticeAnswer} 里新加的
 * {@code && !wrongBookComplete}，而不是 {@code flushDifficultWords}。
 *
 * <h2>真 SQLite + 真故障</h2>
 * 读失败注入手法同 {@code DifficultWordsReadFailureTest}：
 * {@code ALTER TABLE difficult_words RENAME COLUMN wrong_count TO wrong_count_hidden}。
 * 生产读 SQL 点名列 {@code wrong_count} → 真实 {@code no such column}；
 * 而 {@code DELETE ... WHERE player_uuid = ? AND word = ?} 一个列名都不碰，
 * 所以"读失败 + 删除真的会发生"这两件事能在同一个库上先后成立。
 */
class PracticeAnswerWrongBookReadFailureTest {

    private static final String PREFIX = "lle_";
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000f6e01");
    private static final String WORD = "apple";

    @TempDir
    Path tempDir;

    private File dbFile;
    private LetsLearnEnglish plugin;
    private DatabaseManager db;
    private PlayerDataStore store;
    private Player player;
    private AchievementManager achievementManager;
    private WordLearningManager manager;
    private CapturingHandler logHandler;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        Logger logger = Logger.getLogger("PracticeAnswerWrongBookReadFailureTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logHandler = new CapturingHandler();
        logger.addHandler(logHandler);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "practice-wrongbook.db");
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(cm.getMainConfig()).thenReturn(cfg);
        when(plugin.getConfigManager()).thenReturn(cm);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        when(plugin.getDatabaseManager()).thenReturn(db);

        store = new PlayerDataStore(plugin, db);
        when(plugin.getPlayerDataStore()).thenReturn(store);

        // 异步写（increment/decrement/remove 走 runIfSameEpoch）就地执行，
        // 让"删除真的落到库上"这一点可断言。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        });
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        achievementManager = mock(AchievementManager.class);
        when(plugin.getAchievementManager()).thenReturn(achievementManager);

        MessageUtil messageUtil = mock(MessageUtil.class);
        when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(inv -> "MSG:" + inv.getArgument(1));
        when(plugin.getMessageUtil()).thenReturn(messageUtil);

        // 消息配置走真 YAML：mock 的 FileConfiguration 会让 getString 返回 null，
        // 让所有玩家消息都变成占位符，从而无法区分"移除了"与"没移除"。
        FileConfiguration messages = new YamlConfiguration();
        messages.set("wrong-book.practice-removed", "removed!");
        messages.set("wrong-book.practice-correct", "count={count}");
        messages.set("daily.load-failed", "load-failed");
        when(cm.getMessageConfig(anyString())).thenReturn(messages);
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);

        // WordLearningManager 的 ctor 需要插件 enabled（纯 JVM 下 scheduler/server 都是 mock，
        // 不注册监听）。getServer() 非 null 时会调 registerEvents —— 用 PluginManager 桩兜住。
        var pluginManager = mock(org.bukkit.plugin.PluginManager.class);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.getWordManager()).thenReturn(mock(WordManagerImpl.class));
        manager = new WordLearningManager(plugin);

        dbFile = new File(tempDir.toFile(), "practice-wrongbook.db");
        assertTrue(dbFile.isFile(), "前置：SQLite 文件未创建");
    }

    @AfterEach
    void tearDown() {
        try {
            renameWrongCountColumn(false);
        } catch (Exception ignored) {
            // 临时库随 TempDir 删除，恢复失败无所谓
        }
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // 用例 1：读失败 + 练习答对 → 不得 DELETE
    // ==================================================================

    /**
     * 变异指向：删掉 {@code submitPracticeAnswer} 里
     * {@code && !wrongBookComplete} 这个条件（即退回旧的 {@code if (newCount <= 0)}），
     * 本用例立刻红：DB 行被删、achievement 计数被 +1。
     */
    @Test
    @DisplayName("D5：错题本读失败时练习答对，DB 行与错次必须原封不动（不得 DELETE）")
    void readFailureMustNotDeleteWordOnPracticeCorrect() throws Exception {
        seedWord(PLAYER_ID, WORD, 7);

        // 环 1：加载期真读失败 —— 列改名让 SELECT wrong_count 报 no such column。
        renameWrongCountColumn(true);
        try {
            store.loadPlayerData(PLAYER_ID);
            assertEquals(false, store.isDifficultWordsReadComplete(PLAYER_ID),
                    "前置：读失败不得登记 difficultWordsReadComplete（否则守卫本身就是假的）");

            // 环 2+3+4：**故障持续期间**玩家练习答对 —— 这是本条缺陷的真实形态：
            //   · decrementWrongCount 的原子 UPDATE 也失败 → FAILED → 不删（F2 已修）；
            //   · 但 getWrongCount 的缓存 miss 再读一次也失败 → 返回 0；
            //   · 旧代码据此 newCount <= 0 → removeDifficultWord → **DELETE**。
            //     DELETE 语句只引用 player_uuid / word，**不碰 wrong_count 列**，
            //     所以在读面全坏的情况下它照样成功 —— 这正是「读失败翻译成永久删除」。
            manager.startPracticeWord(PLAYER_ID, WORD);
            boolean correct = manager.submitPracticeAnswer(player, WORD);
            assertTrue(correct, "前置：答案正确，必须走到移除判定分支");

            assertEquals(7, countInDbWhileColumnBroken(PLAYER_ID, WORD),
                    "错题本读失败时「错次 0」不代表玩家攻克了这个词 —— DB 行被删掉了。\n"
                            + "正确方向：读未完整时删除方向必须关闭（与 flushDifficultWords 的\n"
                            + "difficultWordsReadComplete 守卫同构）。多留一行只是多显示一轮，\n"
                            + "错删一行即刻永久丢失。");
        } finally {
            renameWrongCountColumn(false); // 抖动恢复
        }

        assertEquals(7, countInDb(PLAYER_ID, WORD),
                "恢复列名后应能正常读到原值 7（证明上一行断言读的是同一行、不是读错列）");
        verify(achievementManager, never()).checkWrongBookCleared(any(UUID.class), anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean());
        assertTrue(logHandler.contains("did NOT remove the word"),
                "守卫必须真的被执行过（本仓反复踩过的坑：加了守卫却被别处早退兜住 = 空转）。"
                        + "实际日志=" + logHandler.describe());
    }

    // ==================================================================
    // 用例 2：读成功时移除方向照常（防「一概不删」的过度修复）
    // ==================================================================

    /**
     * 反过度修复：读成功且计数确实到 0 时，词必须被移出、成就计数器必须 +1。
     *
     * <p>单独立意：只测"不删"时，一个"永远不删"的实现也能通过 —— 那是从「误删」
     * 滑到「永不删」：玩家练到 0 次的词会永远留在错题本里。
     */
    @Test
    @DisplayName("D5：读成功且计数归零 → 必须移除并计入成就（不得变成永不删）")
    void successfulReadStillRemovesWhenCountReachesZero() throws Exception {
        // wrong_count = 1（练习答对一次就归零）。
        seedWord(PLAYER_ID, WORD, 1);

        store.loadPlayerData(PLAYER_ID);
        assertTrue(store.isDifficultWordsReadComplete(PLAYER_ID),
                "前置：成功加载后必须登记 readComplete");
        assertEquals(1, store.getWrongCount(PLAYER_ID, WORD), "前置：计数应读自 DB");

        manager.startPracticeWord(PLAYER_ID, WORD);
        assertTrue(manager.submitPracticeAnswer(player, WORD));

        assertEquals(-1, countInDb(PLAYER_ID, WORD),
                "读成功时删除方向必须照常：计数已到 1，练习答对后该行应被删掉。"
                        + "若这里仍是 1，说明守卫把删除方向整个关死了（过度修复）。");
        verify(achievementManager).checkWrongBookCleared(eq(PLAYER_ID),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ==================================================================
    // 用例 3：读成功但计数仍 > 0 → 提示剩余次数，不删
    // ==================================================================

    /**
     * 边界：读成功、答对后计数仍 &gt; 0 → 只提示，不删。
     *
     * <p>这条用来钉住"守卫没有把正常路径的 else 分支吃掉"：三种结局
     * （读失败不删 / 读成功归零删 / 读成功未归零不删）必须互不重叠。
     */
    @Test
    @DisplayName("D5：读成功且计数仍 > 0 → 不删、不误解锁成就")
    void successfulReadWithRemainingCountKeepsTheRow() throws Exception {
        seedWord(PLAYER_ID, WORD, 5);

        store.loadPlayerData(PLAYER_ID);
        manager.startPracticeWord(PLAYER_ID, WORD);
        assertTrue(manager.submitPracticeAnswer(player, WORD));

        assertEquals(4, countInDb(PLAYER_ID, WORD),
                "答对一次应从 5 减到 4（原子条件 UPDATE），行必须还在");
        verify(achievementManager, never()).checkWrongBookCleared(any(UUID.class), anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 改 {@code difficult_words.wrong_count} 的列名，制造/撤销读失败。
     *
     * <p>精确性：生产读 SQL 是 {@code SELECT word, wrong_count, added_at FROM ...}
     * （显式点名该列）→ 改名后 {@code no such column} = 真 {@code SQLException}
     * = {@code readFailed=true}；删除方向只引用 {@code player_uuid} / {@code word}，
     * 恢复列名后照常执行。</p>
     */
    private void renameWrongCountColumn(boolean failed) throws Exception {
        String sql = failed
                ? "ALTER TABLE " + PREFIX + "difficult_words RENAME COLUMN wrong_count TO wrong_count_hidden"
                : "ALTER TABLE " + PREFIX + "difficult_words RENAME COLUMN wrong_count_hidden TO wrong_count";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private void seedWord(UUID playerId, String word, int count) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO " + PREFIX + "difficult_words"
                             + " (player_uuid, word, wrong_count) VALUES (?, ?, ?)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, word);
            ps.setInt(3, count);
            ps.executeUpdate();
        }
    }

    /** DB 里的 wrong_count；行不存在返回 -1。 */
    private int countInDb(UUID playerId, String word) throws Exception {
        return countWithColumn(playerId, word, "wrong_count");
    }

    /**
     * 在 {@code wrong_count} 列被改名的故障期内读计数。
     *
     * <p>不能复用 {@link #countInDb}（它点名 {@code wrong_count}，故障期会抛）——
     * 而"行还在不在"恰恰是断言的核心，所以退到主键列上判存在性，
     * 再用 {@code SELECT *} 逐列找真正的 {@code wrong_count_hidden} 值。
     * 这样即便列名是坏的，本断言依然能读到真实数值（否则会把"读不到"误报成"行被删"）。</p>
     */
    private int countInDbWhileColumnBroken(UUID playerId, String word) throws Exception {
        return countWithColumn(playerId, word, "wrong_count_hidden");
    }

    private int countWithColumn(UUID playerId, String word, String column) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM " + PREFIX + "difficult_words"
                             + " WHERE player_uuid = ? AND word = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, word);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /** 收集日志 —— 用来证明守卫真的执行过（本仓「守卫空转」教训）。 */
    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override public void publish(LogRecord record) {
            messages.add(String.valueOf(record.getMessage()));
        }

        @Override public void flush() { }

        @Override public void close() { }

        boolean contains(String needle) {
            return messages.stream().anyMatch(m -> m != null && m.contains(needle));
        }

        String describe() {
            return messages.toString();
        }
    }
}
