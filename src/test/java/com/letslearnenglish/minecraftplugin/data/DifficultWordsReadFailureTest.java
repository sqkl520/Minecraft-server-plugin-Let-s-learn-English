package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * r10-s16dc-02（round10 · slice-16 data-consistency，HIGH）：
 * <b>错题本刷盘把「读失败」当成「数据库里确实没有」，于是把整本错题 DELETE 干净。</b>
 *
 * <h2>失败序列（三条独立事实拼出的完整链条，缺一不成灾）</h2>
 * <ol>
 *   <li>{@code DatabaseManager.getDifficultWords} 是丢弃 {@code readFailed} 的薄委托 ——
 *       一次 SELECT 抛 {@code SQLException} 后，调用方拿到的是「空列表」，与「这个玩家
 *       确实没有错题」逐字节同形；</li>
 *   <li>{@code loadDifficultWordsFromDB} 无条件把这个空列表整替换进两张缓存 ——
 *       于是「读失败」被固化成一份<b>非 null 的空表</b>（关键：非 null 才可怕，
 *       {@code flushDifficultWords} 的删除方向正是拿它当"内存权威"）；</li>
 *   <li>{@code flushDifficultWords} 的删除方向推理是「DB 有、内存没有 ⇒ 玩家删掉了」，
 *       逐条 DELETE。抖动恢复后第二次 SELECT 返回真实行，而 {@code counts} 是空的 ——
 *       每一行都满足"内存里没有"，整本错题被逐条删除，全程无报错。</li>
 * </ol>
 *
 * <h2>本类怎么做到"真绿"</h2>
 * <p>用<b>真 SQLite 临时库</b>，读失败不是打桩打出来的，而是<b>真的让 SELECT 报错</b>：
 * 从第二条连接执行 {@code ALTER TABLE ... RENAME COLUMN wrong_count TO wrong_count_hidden}。
 * 生产 SELECT 显式列出 {@code wrong_count} 列 → 抛 {@code no such column}（走
 * {@code DatabaseManager.loadDifficultWords} 的 {@code catch (SQLException)}，返回
 * {@code readFailed=true}）；而 DELETE 只引用 {@code player_uuid} / {@code word}，
 * 恢复列名后照常工作 —— 于是"读失败、随后读成功、删除真的发生"三个环节全部是真库行为。
 *
 * <p><b>两种已被证伪的注入手法（记下来免得后人再踩）</b>：
 * <ul>
 *   <li>{@code ALTER TABLE ... RENAME TO}（整表改名）：SQLite 会把改名同步进既有连接，
 *       已开连接的 SELECT 依然成功 —— 故障根本不发生，测试"全绿"纯属空转；</li>
 *   <li>{@code DROP COLUMN}：读确实失败，但恢复不了，后续准备数据全坏。</li>
 * </ul>
 * 本类用的「列改名 + 事后改回」两者都避开：改名只打坏读面（SELECT 点名该列），
 * DELETE 不受影响，且可以原样恢复。
 *
 * <h2>三条用例各自封死的变异</h2>
 * <ol>
 *   <li>{@link #readFailureMustNotDeleteExistingRows()} —— 把 {@code flushDifficultWords}
 *       的删除方向守卫（{@code readFailed} 早退）删掉，立刻红：DB 的 3 行全被 DELETE；</li>
 *   <li>{@link #readFailureMustNotOverwriteMemoryCache()} —— 把 {@code loadDifficultWordsFromDB}
 *       改回走丢弃三态的 {@code getDifficultWords} 并照旧整替换，立刻红：内存里的真值被空表顶掉；</li>
 *   <li>{@link #healthyReadStillReconcilesDeletions()} —— 防「一律不删」的过度修复：
 *       读成功时删除方向必须照常工作（内存里没有的词要从 DB 删掉）。</li>
 * </ol>
 */
class DifficultWordsReadFailureTest {

    private static final String PREFIX = "lle_";

    @TempDir
    Path tempDir;

    private File dbFile;
    private LetsLearnEnglish plugin;
    private DatabaseManager db;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DifficultWordsReadFailureTest"));
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        ConfigManager cm = mock(ConfigManager.class);
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("database.type", "sqlite");
        cfg.set("database.sqlite.filename", "difficult-read-failure.db");
        cfg.set("database.mysql.table-prefix", PREFIX);
        when(cm.getMainConfig()).thenReturn(cfg);

        db = new DatabaseManager(plugin, cm);
        db.initialize();
        when(plugin.getDatabaseManager()).thenReturn(db);

        // incrementWrongCount / addDifficultWord 走 runIfSameEpoch → 异步调度器。
        // 这里让任务<b>就地同步</b>执行：本类要测的是"内存表与 DB 的对齐"，
        // 把任务真的丢到线程池会让断言时刻不可控（且纯 JVM 下 mock 的 Server 返回 null）。
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(org.mockito.ArgumentMatchers.eq(plugin),
                org.mockito.ArgumentMatchers.any(Runnable.class))).thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return null;
                });
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);

        dbFile = new File(tempDir.toFile(), "difficult-read-failure.db");
        assertTrue(dbFile.isFile(), "前置：SQLite 文件未创建");
    }

    @AfterEach
    void tearDown() {
        // 若用例中途失败，列名可能仍是改过的状态；恢复失败无所谓（临时库随 TempDir 删除）。
        try {
            renameWrongCountColumn(false);
        } catch (Exception ignored) {
            // no-op
        }
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // 用例 1：读失败 → 刷盘不得对 DB 现存行发起 DELETE
    // ==================================================================

    /**
     * 复刻生产失败序列的<b>全部三环</b>：先真读失败（缓存被读成空）→ 再真读成功（DB 有行）→
     * 再走 quit 的真实出口刷盘。
     *
     * <p>变异指向：删掉 {@code flushDifficultWords} 里对 {@code readFailed} 的早退，
     * 三行会被逐条 DELETE，本用例变红。
     */
    @Test
    @DisplayName("读失败后 quit 刷盘：DB 里 alpha/beta/gamma 三行必须原封不动（不得被 DELETE）")
    void readFailureMustNotDeleteExistingRows() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dc01");
        insertWord(id, "alpha", 3);
        insertWord(id, "beta", 4);
        insertWord(id, "gamma", 5);
        assertEquals(Set.of("alpha", "beta", "gamma"), wordsInDb(id), "前置：三行未写入");

        PlayerDataStore store = new PlayerDataStore(plugin, db);

        // 环 1+2：让**加载期**的 SELECT 真的失败 —— 错题本被读成「空」。
        renameWrongCountColumn(true);
        store.loadPlayerData(id);
        // 环 2 的验收：读失败后不得把「空」发布成缓存真值。
        // （旧实现此处 workCountCache 里是空表 —— 非 null，因此下面 flush 的删除方向会走到。）

        // 环 3：抖动已恢复（列名改回），quit 刷盘的第二次 SELECT 会返回真实行。
        renameWrongCountColumn(false);

        // 生产出口：PlayerJoinListener.onPlayerQuit → unloadPlayerData → savePlayerData → flush。
        store.unloadPlayerData(id);

        assertEquals(Set.of("alpha", "beta", "gamma"), wordsInDb(id),
                "读失败被当成了「数据库里确实没有」：flush 的删除方向拿空表当内存权威，"
                        + "把玩家整本错题逐条 DELETE 光了。\n"
                        + "正确方向：读失败时删除方向必须关闭（只写不删）—— DB 里的行是权威，"
                        + "「内存里读不到」不等于「玩家删过」。\n"
                        + "残余风险提示：多留一行只是多显示一轮；错删一行即刻永久丢失，两者代价不对等。");
    }

    // ==================================================================
    // 用例 2：读失败不得用空表覆盖既有内存缓存
    // ==================================================================

    /**
     * 前置是「内存里已有真值」（一次成功加载建立），随后一次加载失败。
     * 旧实现会把两张缓存整替换成空表 —— 内存真值当场被毁，且此后 flush 会拿这张空表去删库。
     *
     * <p>变异指向：把 {@code loadDifficultWordsFromDB} 改回丢弃三态并照旧 {@code put}，本用例变红。
     */
    @Test
    @DisplayName("读失败不得用空表覆盖既有缓存：已加载的 alpha/beta 必须留在内存里")
    void readFailureMustNotOverwriteMemoryCache() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dc02");
        insertWord(id, "alpha", 3);
        insertWord(id, "beta", 7);

        PlayerDataStore store = new PlayerDataStore(plugin, db);
        store.loadPlayerData(id);
        assertEquals(Set.of("alpha", "beta"), store.getDifficultWordsData(id).keySet(),
                "前置：第一次加载应把两行读进内存");
        assertEquals(7, store.getWrongCount(id, "beta"), "前置：错次应随行读入");

        // 第二次加载（reload / 重进服的等价路径），此刻 SELECT 失败。
        renameWrongCountColumn(true);
        try {
            store.loadPlayerData(id);
        } finally {
            renameWrongCountColumn(false);
        }

        assertEquals(Set.of("alpha", "beta"), store.getDifficultWordsData(id).keySet(),
                "一次 SELECT 抖动把内存里的整本错题清成了空表。\n"
                        + "「读失败」与「确实没有」必须在缓存状态上就可区分：读失败时不发布，"
                        + "保留上一次成功读到的值，下一次读成功时再对齐。");
        assertEquals(7, store.getWrongCount(id, "beta"),
                "错次也被空表覆盖了 —— 这份缓存随后会被 flush 当成内存权威写回 DB（wrong_count=0 级降级）。");
    }

    // ==================================================================
    // 用例 3：读成功时双向同步行为不变（防「把删除方向整个废掉」）
    // ==================================================================

    /**
     * 反过度修复：修读失败不能顺手把删除方向整个摘掉 —— 那会让玩家删掉的错题词
     * 在 DB 里永久残留，重进服又"复活"（DC-01 当初就是为了关掉这个方向才加的删除）。
     *
     * <p>构造：内存里只剩 alpha / gamma（等价于 beta 被玩家删除、而其 DB 删除因世代推进
     * 作废 —— 见 {@code removeDifficultWord} + {@code runIfSameEpoch}），DB 里三行都在。
     * 刷盘必须把 beta 删掉，并保留另外两行。
     */
    @Test
    @DisplayName("读成功时删除方向照常：内存缺的 beta 必须从 DB 删掉，alpha/gamma 保留")
    void healthyReadStillReconcilesDeletions() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dc03");
        insertWord(id, "alpha", 3);
        insertWord(id, "beta", 4);
        insertWord(id, "gamma", 5);

        PlayerDataStore store = new PlayerDataStore(plugin, db);
        store.loadPlayerData(id);
        assertEquals(Set.of("alpha", "beta", "gamma"), store.getDifficultWordsData(id).keySet(),
                "前置：三行应全部读入内存");

        // 只改内存：精确复刻 removeDifficultWord 的内存半边（DB 半边被年代守卫作废）时的状态。
        forgetInMemory(store, id, "beta");
        assertEquals(Set.of("alpha", "gamma"), store.getDifficultWordsData(id).keySet(),
                "前置：内存里应只剩两个词");

        store.savePlayerData(id);

        assertEquals(Set.of("alpha", "gamma"), wordsInDb(id),
                "读成功时删除方向必须照常工作：内存里没有的 beta 应从 DB 删掉。\n"
                        + "若这里仍是三行，说明修复把删除方向整个废掉了 —— 那是从「误删」滑到"
                        + "「永不删」：玩家删掉的错题词会在每次登录时复活。");
        assertEquals(3, countInDb(id, "alpha"), "保留行不得被顺手改坏");
        assertEquals(5, countInDb(id, "gamma"), "保留行不得被顺手改坏");
    }

    // ==================================================================
    // 用例 4：读失败后「表被现场建出来」同样不得开放删除方向
    // ==================================================================

    /**
     * 本条是「表非 null ⟺ 内存可信」这一错误等价关系的第二个反例，也是
     * {@code difficultWordsReadComplete} 这枚守卫的唯一直接证据。
     *
     * <p>场景（生产可达）：玩家进服时加载失败（SELECT 抖动）→ 错题本缓存仍是 null →
     * 玩家答错一个词，{@code incrementWrongCount} / {@code addDifficultWord} 的
     * {@code computeIfAbsent} 现场建出一张<b>只含这一个词</b>的表 → quit 时刷盘。
     * 此时 {@code difficultWordsCache} 与 {@code wrongCountCache} 都非 null，
     * 但里面只有一个词 —— 若把"非 null"当成"内存权威"，DB 里其余行会被逐条 DELETE。
     *
     * <p>变异指向：把 {@code flushDifficultWords} 里
     * {@code if (!difficultWordsReadComplete.contains(playerId)) return;} 删掉，
     * 本用例变红（beta / gamma 被删）。用例 1 抓不到这个变异 —— 那条路径上缓存是 null，
     * 会被上一道 {@code difficultWordsCache.get() == null} 早退替它兜住（假绿）。
     */
    @Test
    @DisplayName("读失败后由写入现场建出的表仍不可信：quit 刷盘不得删掉 DB 其余行")
    void partiallyBuiltCacheMustNotDeleteExistingRows() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dc04");
        insertWord(id, "beta", 4);
        insertWord(id, "gamma", 5);

        PlayerDataStore store = new PlayerDataStore(plugin, db);

        // 加载期真读失败 → 缓存保持 null（读失败不发布）。
        renameWrongCountColumn(true);
        try {
            store.loadPlayerData(id);
        } finally {
            renameWrongCountColumn(false);
        }

        // 玩家答错一个词：computeIfAbsent 现场建出只含 alpha 的两张表。
        store.incrementWrongCount(id, "alpha");
        store.addDifficultWord(id, "alpha");
        assertEquals(Set.of("alpha"), store.getDifficultWordsData(id).keySet(),
                "前置：此刻内存里只有现场写入的 alpha");

        store.unloadPlayerData(id);

        assertEquals(Set.of("alpha", "beta", "gamma"), wordsInDb(id),
                "内存里的表只含刚写入的 alpha，却被当成了整本错题的权威 —— "
                        + "DB 里 beta / gamma 被逐条 DELETE 了。\n"
                        + "「表非 null」不等于「表完整」：只有真的读到过 DB 才能让内存表参与删除方向。");
    }

    // ==================================================================
    // 用例 5：读失败期间的答错不得被绝对值刷盘按零基线覆写（同族第 2 条）
    // ==================================================================

    /**
     * r10-s16dc-02 同族第 2 条（HIGH）：与 {@code r10-s16dc-01} 的
     * {@code conversation_count} 归零<b>完全同形</b>，只是入口从「player_data 统计列」
     * 换成了「difficult_words 计数」。
     *
     * <h2>失败序列（5 环，缺一不成灾）</h2>
     * <ol>
     *   <li>玩家进服，加载期 SELECT 失败 → {@code wrongCountCache} 保持 null（读失败不发布）；</li>
     *   <li>DB 抖动恢复；</li>
     *   <li>玩家答错 {@code apple}，而这个词在 DB 里真实 {@code wrong_count=7} →
     *       {@code incrementWrongCount} 的 {@code computeIfAbsent} <b>现场建出空表</b>、
     *       {@code merge(+1)} 从 <b>1</b> 起算；</li>
     *   <li>DB 侧是<b>相对</b>累加（{@code wrong_count = wrong_count + 1}）→ DB 变成 8，
     *       <b>这一步是正确的</b>；</li>
     *   <li>quit → {@code flushDifficultWords} 用内存的 1 走<b>绝对值</b> UPSERT → DB 回到 1。</li>
     * </ol>
     *
     * <p><b>核心断言</b>：第 4 环已经落库，所以第 5 环对 DB 的任何写入都只可能变坏。
     * 守卫（{@code difficultWordsReadComplete}）跳过的正是这一步，因此刷盘后 DB 必须仍是 8。
     *
     * <p><b>变异指向</b>：把 {@code flushDifficultWords} 里新增的
     * {@code if (!difficultWordsReadComplete.contains(playerId))} 早退删掉，
     * 本用例立刻变红（DB 变 1）。
     *
     * <p><b>为什么这条用例必须让"答错"发生在列名恢复之后</b>：读失败的注入手法是
     * 「列改名」，它同时打坏 {@code wrong_count} 的<b>读写两面</b>。若在改名期间答错，
     * 相对累加也会失败，测出来的就不是「刷盘覆写」而是「什么都不写」——
     * 而那正是本修复要证明"不成立"的假象（相对累加其实是成功的）。
     */
    @Test
    @DisplayName("读失败 + 答错：quit 刷盘不得用零基线覆写 DB 里真实的 wrong_count=7")
    void readFailureThenWrongAnswerMustNotOverwriteRealCount() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dc05");
        insertWord(id, "apple", 7);

        PlayerDataStore store = new PlayerDataStore(plugin, db);

        // 环 1：加载期真读失败（缓存保持 null）。
        renameWrongCountColumn(true);
        try {
            store.loadPlayerData(id);
        } finally {
            renameWrongCountColumn(false);
        }

        // 环 3+4：答错一个词。相对累加此刻真的能成功（环 2 已恢复）。
        List<String> guardLogs = captureFlushGuardLogs(() -> {
            int memCount = store.incrementWrongCount(id, "apple");
            assertEquals(1, memCount,
                    "前置：读失败后内存表是空表，merge(+1) 从 1 起算 —— 这正是零基线");
            assertEquals(8, countInDb(id, "apple"),
                    "前置：相对累加必须真的写进了 DB（7 → 8）。若这里是 7，本用例测的就不是"
                            + "「刷盘覆写」而是「相对累加也失败」，两者修法不同");
            // 环 5：生产出口。
            store.unloadPlayerData(id);
        });

        assertEquals(8, countInDb(id, "apple"),
                "读失败期间的答错被 quit 刷盘按零基线覆写成 " + countInDb(id, "apple")
                        + " —— DB 里真实的 7 次错题计数永久丢失。\n"
                        + "正确方向：{@code setDifficultWordCount} 是绝对值 UPSERT，它的隐含前提是"
                        + "「内存 count = DB 基数 + 本地增量」；读没成功过时这个前提不成立，写入方向必须关闭。\n"
                        + "关闭是安全的，不是妥协：相对累加（wrong_count = wrong_count + 1）已经把这次答错"
                        + "写进 DB 了，它不需要知道基数 —— 刷盘这一次没有任何可改进之处，只有概率破坏数据。");

        assertTrue(guardLogs.stream().anyMatch(m -> m.contains("flushDifficultWords skipped absolute write")),
                "守卫必须真的被执行过（A 的教训：第一版删除方向守卫是空转的，"
                        + "被别处的 null 早退替它兜住）。实际捕获到的日志=" + guardLogs);
    }

    // ==================================================================
    // 用例 6：读成功时写入方向必须照常（防「把写入方向整个废掉」）
    // ==================================================================

    /**
     * 反过度修复的两个方向：
     * <ol>
     *   <li>读成功 → 答错 → 刷盘的<b>绝对值与相对累加结果一致</b>（8），不得因为守卫变成「永不写」；</li>
     *   <li>读成功后把 DB 行删掉（等价于"有一条只存在于内存的写入"）→ 刷盘必须把它<b>写回来</b>。
     *       这一条单独立意：只看第 1 条时，"记忆里恰好等于 DB"会让一个完全不写的实现也通过
     *       （相对累加已经把 8 写进去了，刷盘写不写都是 8）—— 典型的假绿形态「计数被别路径满足」。</li>
     * </ol>
     */
    @Test
    @DisplayName("读成功时写入方向照常：答错后刷盘一致，且内存独有的行必须被写回 DB")
    void healthyReadStillWritesAbsoluteCounts() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dc06");
        insertWord(id, "apple", 7);

        PlayerDataStore store = new PlayerDataStore(plugin, db);
        store.loadPlayerData(id);
        assertEquals(7, store.getWrongCount(id, "apple"), "前置：基数应读自 DB");

        // 第 1 条：读成功 → 答错 → 刷盘绝对值 = 相对累加结果。
        assertEquals(8, store.incrementWrongCount(id, "apple"));
        store.savePlayerData(id);
        assertEquals(8, countInDb(id, "apple"),
                "读成功时刷盘必须照常写（与相对累加结果一致）。若是 7 说明写入被过度关死；"
                        + "若是 1 说明守卫判据用错了（把「读到过」判成「没读到过」）");

        // 第 2 条：删掉 DB 行，制造「内存有、DB 无」——只有真的写绝对值才能恢复。
        deleteWord(id, "apple");
        assertEquals(-1, countInDb(id, "apple"), "前置：DB 行应已删除");
        store.savePlayerData(id);
        assertEquals(8, countInDb(id, "apple"),
                "内存里 8 是正确的，刷盘却没把它写回 DB（行仍不存在或值不对）——"
                        + "写入方向被整个废掉了：此后任何只存在于内存的计数都永远落不了库");
    }

    // ==================================================================
    // 用例 7：修复后 DB 值必须单调不减
    // ==================================================================

    /**
     * 单调性是这条缺陷最凝练的表述：相对累加只会让 {@code wrong_count} 变大，
     * 绝对值刷盘<b>不该</b>让它变小。修复后「答错之后的值」必须 &lt;= 「刷盘之后的值」。
     *
     * <p>与用例 5 的区别：用例 5 断言精确值 8，本用例断言不等式 —— 即便将来实现改成
     * 「读失败期间也刷绝对，但先回读 DB 取 max」这类更精细的修法，本用例仍然成立，
     * 而用例 5 会要求同步更新期望值。两条一起看，既锁死当前实现也锁死不变式。
     */
    @Test
    @DisplayName("修复后 wrong_count 单调不减：flush 之后 DB 值 >= 答错之后 DB 值")
    void wrongCountIsMonotonicAcrossFlush() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000dc07");
        insertWord(id, "apple", 7);

        PlayerDataStore store = new PlayerDataStore(plugin, db);
        renameWrongCountColumn(true);
        try {
            store.loadPlayerData(id);
        } finally {
            renameWrongCountColumn(false);
        }

        store.incrementWrongCount(id, "apple");
        int afterAnswer = countInDb(id, "apple");
        assertEquals(8, afterAnswer, "前置：相对累加应已写库");

        store.unloadPlayerData(id);
        int afterFlush = countInDb(id, "apple");

        assertTrue(afterFlush >= afterAnswer,
                "刷盘把错题计数改小了：" + afterAnswer + " → " + afterFlush
                        + "。相对累加只会让它变大，绝对值刷盘绝不能让它变小 —— "
                        + "这条不等式是 s16dc-01/s16dc-02 两条缺陷的共同不变式。");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 在动作执行期间收集该插件的 logger 输出，用于证明守卫真的执行过。 */
    private List<String> captureFlushGuardLogs(ThrowingRunnable action) throws Exception {
        Logger logger = plugin.getLogger();
        List<String> messages = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                messages.add(String.valueOf(record.getMessage()));
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    /** 允许抛受检异常的 lambda。 */
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 从 DB 直接删掉某行（模拟一条只存在于内存的错题记录）。 */
    private void deleteWord(UUID playerId, String word) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + PREFIX + "difficult_words WHERE player_uuid = ? AND word = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, word);
            ps.executeUpdate();
        }
    }

    /**
     * 在第二条 JDBC 连接上改 {@code difficult_words.wrong_count} 的列名，制造/撤销读失败。
     *
     * <p>为什么这条能精确打坏<b>读面</b>：生产查询是
     * {@code SELECT word, wrong_count, added_at FROM ...}（显式点名该列）→ 列改名后
     * {@code no such column} = 真实 {@code SQLException} = {@code readFailed=true}；
     * 而删除方向是 {@code DELETE ... WHERE player_uuid = ? AND word = ?}，一个列名都不碰，
     * 恢复列名后照常执行。
     *
     * @param failed {@code true} 制造读失败，{@code false} 恢复
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

    private void insertWord(UUID playerId, String word, int count) throws Exception {
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

    /** DB 里该玩家现存的所有词（小写）。 */
    private Set<String> wordsInDb(UUID playerId) throws Exception {
        Set<String> words = new LinkedHashSet<>();
        List<String> column = readColumn(playerId, "word");
        words.addAll(column);
        return words;
    }

    /** 读某行某列；行不存在返回 -1。 */
    private int countInDb(UUID playerId, String word) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT wrong_count FROM " + PREFIX + "difficult_words"
                             + " WHERE player_uuid = ? AND word = ?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, word);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private List<String> readColumn(UUID playerId, String column) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM " + PREFIX + "difficult_words WHERE player_uuid = ?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1).toLowerCase());
                }
            }
        }
        return values;
    }

    /**
     * 只摘掉内存条目（不动 DB），复刻「玩家删除已落进缓存、DB 删除却因世代推进作废」的状态。
     *
     * <p>生产代码没有「只改内存不碰 DB」的公开入口 —— 这正是本类必须用反射的原因：
     * 走 {@code removeDifficultWord} 会连 DB 一起删（那是异步派发，还需要 Bukkit 调度器），
     * 于是 flush 的删除方向就再也测不到东西了（假绿）。
     */
    @SuppressWarnings("unchecked")
    private void forgetInMemory(PlayerDataStore store, UUID id, String word) throws Exception {
        Field wordsF = PlayerDataStore.class.getDeclaredField("difficultWordsCache");
        wordsF.setAccessible(true);
        Map<String, DifficultWord> words =
                ((Map<UUID, Map<String, DifficultWord>>) wordsF.get(store)).get(id);
        assertNotNull(words, "前置：difficultWordsCache 里应有该玩家条目");
        assertTrue(words.containsKey(word), "前置：内存里应有 " + word);
        words.remove(word);

        Field countsF = PlayerDataStore.class.getDeclaredField("wrongCountCache");
        countsF.setAccessible(true);
        Map<String, Integer> counts =
                ((Map<UUID, Map<String, Integer>>) countsF.get(store)).get(id);
        assertNotNull(counts, "前置：wrongCountCache 里应有该玩家条目");
        counts.remove(word);
    }
}
