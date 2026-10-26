package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;
import com.letslearnenglish.minecraftplugin.core.word.Word;

/**
 * R7 增量落库（脏标记）验收。
 *
 * <p><b>为什么这个类最重要</b>：{@link PlayerDataStore#saveDirty(UUID)} 把原先"每 5 分钟
 * 全量重写"降成了"只写变过的词"，代价是把正确性完全押在三条不变式上（源码 :82-91 的
 * ①②③）。任何一条被破坏，症状都是<b>静默丢数据且无任何报错</b>：内存里的新值被下一次
 * 全量覆盖成 DB 旧值，或脏标记被提前清掉导致新值永不落盘。这类缺陷不会让任何现有测试
 * 变红，只会让玩家"分数回档""复习进度消失"。
 *
 * <p>本类分四组：
 * <ul>
 *   <li>{@link BasicWritePaths} —— 7 个公开写入口是否都会标脏（不变式 ①）；</li>
 *   <li>{@link DirtyMarkerSemantics} —— 标记的幂等/清理时机/失败保留（不变式 ②）；</li>
 *   <li>{@link RedTeamRegressions} —— R7 红队 P1 的正面对应（保存窗口竞态、两档全量兜底）；</li>
 *   <li>{@link RedTeamGaps} —— FIX-1 追补：第二轮红队实测发现的"声称保护却保护不了"的
 *       四条真实缺口（活引用、seq 采集点、回暖清零、行值列序）；</li>
 *   <li>{@link QuitAndReloadPaths} —— quit / reload 必须全量、reload 后不得再写（不变式 ③）。</li>
 * </ul>
 *
 * <p><b>计数口径（本类全部断言的基础）</b>：读 {@code PlayerDataStore.java} 的
 * {@code savePlayerDataInternal} 确认——词行走 {@code upsertStmt.addBatch()}（:996）
 * + {@code executeBatch()}（:1003），{@code player_data} 行走另一条
 * {@code statsStmt.executeUpdate()}（:1018）。因此本类用两个探针分别计数：
 * {@code wordRows} ← {@code addBatch()}，{@code playerDataRows} ← {@code executeUpdate()}。
 * 两者混用会让"全量兜底"与"增量"完全无法区分（全量与增量的词行数才是唯一稳定信号）。
 *
 * <p>mock 姿势沿用 {@code PlayerStatsDirtyHookTest.StoreIntegration}：真 {@code Logger}
 * 让失败路径的 {@code log(WARNING, ...)} 不 NPE，且不需要 MockBukkit。
 */
class PlayerDataStoreDirtyTest {

    private LetsLearnEnglish plugin;
    private DatabaseManager databaseManager;
    private Connection connection;
    private PreparedStatement ps;
    private ResultSet rs;
    private PlayerDataStore store;

    /** 词行探针：{@code addBatch()} 次数 == 本轮 UPSERT 的 {@code word_progress} 行数。 */
    private int wordRows;
    /** player_data 探针：{@code executeUpdate()} 次数 == 本轮 {@code player_data} 行数。 */
    private int playerDataRows;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        databaseManager = mock(DatabaseManager.class);
        connection = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);

        // 真 Logger：失败路径（commit 抛 SQLException）会 log(WARNING)，mock 返回值会 NPE。
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);

        when(databaseManager.getTablePrefix()).thenReturn("lle_");
        when(databaseManager.isSQLite()).thenReturn(true);
        when(databaseManager.getConnectionLock()).thenReturn(new Object());
        // F13：loadPlayerData → loadDifficultWordsFromDB 会调它；不 stub 会返回 null 触发 NPE。
        when(databaseManager.getDifficultWords(any())).thenReturn(Collections.emptyList());
        when(databaseManager.getConnection()).thenReturn(connection);

        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[0]);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false); // load 回来是空表：不覆盖我们手工播种的缓存

        // 计数探针。注意顺序：doAnswer 覆盖上面任何 executeUpdate 的 when 存根。
        doAnswer(inv -> {
            wordRows++;
            return null;
        }).when(ps).addBatch();
        doAnswer(inv -> {
            playerDataRows++;
            return 1;
        }).when(ps).executeUpdate();

        wordRows = 0;
        playerDataRows = 0;
        store = new PlayerDataStore(plugin, databaseManager);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 构造 {@code updateWordProgress} 需要的结算结果：每词 1 次答对（mastery 0 → 0.2）。 */
    private static Map<String, LearningSession.WordResult> results(String... words) {
        Map<String, LearningSession.WordResult> map = new HashMap<>();
        for (String word : words) {
            Word w = new Word(word, word, "n", "", "", List.of(), List.of(), "easy", 1);
            LearningSession.WordResult r = new LearningSession.WordResult(w);
            r.incrementCorrect();
            map.put(word, r);
        }
        return map;
    }

    /** 走真实公开入口播种：既建 masteryMap（否则增量写不出词行），也推进脏标记。 */
    private void seedWords(UUID playerId, String... words) {
        store.updateWordProgress(playerId, results(words));
    }

    /** 清空"本轮"探针与 Mockito 的调用记录，让后续断言只统计本轮。 */
    private void resetCounters() {
        wordRows = 0;
        playerDataRows = 0;
        clearInvocations(connection, ps);
        // useSeparateStatements() 之后，词行/统计行走各自的 mock —— 必须一起清，
        // 否则预热的首次保存会残留在本轮的 setXxx 计数里。
        if (wordPs != null) {
            clearInvocations(wordPs);
        }
        if (statsPs != null) {
            clearInvocations(statsPs);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T readField(Object target, String name) throws Exception {
        Field f = PlayerDataStore.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private Set<UUID> dirtyPlayers() throws Exception {
        return readField(store, "dirtyPlayers");
    }

    private Map<UUID, Set<String>> dirtyWords() throws Exception {
        return readField(store, "dirtyWords");
    }

    private Map<UUID, AtomicLong> writeSeq() throws Exception {
        return readField(store, "playerWriteSeq");
    }

    private long seq(UUID playerId) throws Exception {
        AtomicLong s = writeSeq().get(playerId);
        return s != null ? s.get() : 0L;
    }

    private int activeRoundCount(UUID playerId) throws Exception {
        Map<UUID, Integer> counts = readField(store, "dirtySaveCount");
        Integer v = counts.get(playerId);
        return v != null ? v : 0;
    }

    private Map<UUID, Integer> idleSaveCounts() throws Exception {
        return readField(store, "idleSaveCount");
    }

    private int idleRoundCount(UUID playerId) throws Exception {
        Integer v = idleSaveCounts().get(playerId);
        return v != null ? v : 0;
    }

    /** 反射写实例字段；用于把缓存换成"读到即触发一次写入"的探针代理。 */
    private static void writeField(Object target, String name, Object value) throws Exception {
        Field f = PlayerDataStore.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * 把"词行语句"与"player_data 语句"拆成两个独立 mock。
     *
     * <p>为什么必须拆：{@code setUp} 里 {@code connection.prepareStatement(anyString())}
     * 对两条 SQL 都返回同一个 {@code ps}，于是 {@code setInt(4, x)} 会同时收到
     * "词行 correct_count" 与 "player_data total_sessions" 两个值 —— 列号断言会互相污染，
     * 无法钉死"第 4 位到底是哪一列"。按 SQL 分派后，每条语句的实参可独立断言。
     */
    private PreparedStatement wordPs;
    private PreparedStatement statsPs;

    private void useSeparateStatements() throws SQLException {
        wordPs = mock(PreparedStatement.class);
        statsPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).contains("word_progress") ? wordPs : statsPs);
        when(wordPs.executeBatch()).thenReturn(new int[0]);
        when(statsPs.executeUpdate()).thenReturn(1);
    }

    /**
     * 预热：写入 2 个词并用<b>全量</b> {@code savePlayerData} 落盘。
     *
     * <p>为什么预热必须走全量而不是 {@code saveDirty}：{@code saveDirty} 会递增
     * {@code dirtySaveCount}（"活跃轮次"），消耗掉一次计数。若预热也走增量，
     * 后续 12 轮循环里 {@code FULL_SAVE_EVERY_N_DIRTY_SAVES} 的触发点会整体前移一位，
     * 用例就只能断言"第 11 轮变全量"，与源码常量对不上。全量路径（{@code savePlayerData}）
     * 不碰该计数，因此预热后计数是干净的 0。
     *
     * <p>预热同时完成两件事：把 2 个词写进 masteryMap（增量才有行可写），并清掉标记。
     */
    private void warmUpTwoWordsAndFlush(UUID playerId) {
        seedWords(playerId, "alpha", "beta");
        store.savePlayerData(playerId); // 全量：写 2 行 + 清标记，不计入活跃轮次
    }

    // ==================================================================
    // 1-5：基础写入路径（不变式 ①：凡写入必标脏）
    // ==================================================================

    @Nested
    @DisplayName("基础写入路径：每个公开写入口都必须标脏")
    class BasicWritePaths {

        /** 防：recordReview 只改了 review 两个 map 却没标脏 —— 复习进度重启即丢。 */
        @Test
        @DisplayName("recordReview 标记词脏：本轮写 1 条 word_progress + 1 条 player_data")
        void recordReviewMarksWordDirty() throws Exception {
            UUID id = UUID.randomUUID();

            seedWords(id, "water");
            store.recordReview(id, "water");
            store.saveDirty(id);

            assertEquals(1, wordRows, "recordReview 后必须写出该词的 word_progress 行");
            assertEquals(1, playerDataRows, "增量保存同时要写 player_data 行（统计/玩家名）");
        }

        /** 防：大小写未归一导致 dirtyWords 里是 WATER、masteryMap 里是 water，词行被"无 mastery"跳过。 */
        @Test
        @DisplayName("recordReview 大写输入也能落库：标记键与缓存键同源（都 toLowerCase）")
        void recordReviewLowercasesWord() throws Exception {
            UUID id = UUID.randomUUID();

            seedWords(id, "water");
            store.saveDirty(id); // 先落盘并清标记，建立干净基线
            resetCounters();

            store.recordReview(id, "WATER");
            store.saveDirty(id);

            assertEquals(1, wordRows,
                    "recordReview(\"WATER\") 若不归一，dirtyWords 里是 WATER 而 masteryMap 里是 water，"
                            + "该词会被当成\"没有 mastery\"静默跳过");
        }

        /**
         * 防：seedReview 只写 lastReviewTime/reviewCount 两个 map，没有补建 masteryMap ——
         * 那么该词永远写不出行（savePlayerDataInternal 见 mastery==null 就 continue）。
         */
        @Test
        @DisplayName("全新玩家 seedReview 补建 mastery(0.0)，因此能写出一条词行")
        void seedReviewMarksDirtyAndBackfillsMastery() throws Exception {
            UUID id = UUID.randomUUID(); // 刻意不预热：模拟"从未走过会话结算"的玩家

            store.seedReview(id, "apple");
            store.saveDirty(id);

            assertEquals(1, wordRows, "putIfAbsent(0.0) 必须补建 mastery，否则该词无行可写");
            assertEquals(0, playerDataRows,
                    "seedReview 不创建 PlayerStats，player_data 快照为 null，本轮不写统计行");
        }

        /** 防：seedReview 的"已有种子就跳过"判断失效，把真实复习进度覆盖成 now/0。 */
        @Test
        @DisplayName("重复 seedReview 是 no-op：仍只写 1 行，不重置已有种子")
        void seedReviewNoOpsWhenAlreadySeeded() throws Exception {
            UUID id = UUID.randomUUID();

            store.seedReview(id, "apple");
            long firstSeed = store.getLastReviewTimes(id).get("apple");
            store.seedReview(id, "apple"); // 第二次必须命中 containsKey 早退
            store.saveDirty(id);

            assertEquals(1, wordRows, "第二次 seedReview 不得产生额外脏词或额外行");
            assertEquals(firstSeed, store.getLastReviewTimes(id).get("apple").longValue(),
                    "已有种子时 lastReviewTime 不得被刷新");
        }

        /** 防：resetReview 改了 reviewCount=0 却没标脏 —— "答错重置间隔"重启后失效。 */
        @Test
        @DisplayName("resetReview 标记词脏：复习答错重置的进度必须落库")
        void resetReviewMarksDirty() throws Exception {
            UUID id = UUID.randomUUID();

            seedWords(id, "water");
            store.saveDirty(id);
            resetCounters();

            store.resetReview(id, "water");
            store.saveDirty(id);

            assertEquals(1, wordRows, "resetReview 后该词必须重写一次");
            assertEquals(1, playerDataRows, "增量路径始终携带 player_data 行");
        }
    }

    // ==================================================================
    // 6-8：脏标记语义（不变式 ②：只在 commit 成功后清）
    // ==================================================================

    @Nested
    @DisplayName("脏标记语义：幂等、成功才清、失败保留")
    class DirtyMarkerSemantics {

        /**
         * 防：用"标记次数"而不是"集合"来驱动保存 —— 同一玩家两次写入若被记成两条待办，
         * 或写序号没前进导致保存线程误判"窗口内无新写入"。
         */
        @Test
        @DisplayName("同一玩家重复 markDirty：集合幂等（size 仍 1），但写序号必须前进 2")
        void markDirtyIsSetIdempotentButAdvancesSequence() throws Exception {
            UUID id = UUID.randomUUID();

            // markDirty 是私有方法：cachePlayerName 是唯一"只标 player_data、不碰词"的公开入口。
            store.cachePlayerName(id, "Steve");
            store.cachePlayerName(id, "Steve");

            assertEquals(1, dirtyPlayers().size(), "dirtyPlayers 是 Set，重复标记必须幂等");
            assertEquals(2L, seq(id),
                    "每次 markDirty 都必须 bump 写序号 —— clearDirty 完全依赖它判断保存窗口内是否有新写入");
        }

        /**
         * 防：commit 成功后忘了清标记 —— 每轮都重写同样的词，"空闲玩家零 SQL"的收益归零。
         */
        @Test
        @DisplayName("commit 成功后标记被清：第二轮完全空闲，零 addBatch / 零 executeUpdate / 零 SQL")
        void saveDirtyClearsMarkersAfterSuccessfulCommit() throws Exception {
            UUID id = UUID.randomUUID();

            seedWords(id, "water");
            store.saveDirty(id);
            assertEquals(1, wordRows, "第一轮：写 1 条词行");
            assertEquals(1, playerDataRows, "第一轮：写 1 条 player_data 行");

            resetCounters();
            store.saveDirty(id); // 第二轮：无标记且空转计数 1 < 36

            assertEquals(0, wordRows, "标记已兑现，第二轮不得再写词行");
            assertEquals(0, playerDataRows, "第二轮不得再写 player_data 行");
            verify(connection, never()).prepareStatement(anyString());
        }

        /**
         * 防：失败路径复用成功路径的清理逻辑 —— commit 失败却清了标记，这批更新就永久丢了。
         */
        @Test
        @DisplayName("commit 失败必须 rollback 且保留标记：重试轮仍能写出 1 条词行 + 1 条统计行")
        void dirtyFlagsSurviveFailedCommit() throws Exception {
            UUID id = UUID.randomUUID();
            seedWords(id, "water");

            // 第一轮 commit 抛异常，第二轮恢复正常。
            doThrow(new SQLException("boom")).doNothing().when(connection).commit();

            store.saveDirty(id);

            verify(connection, times(1)).rollback();
            assertTrue(dirtyPlayers().contains(id), "commit 失败后 dirtyPlayers 必须保留");
            assertEquals(Set.of("water"), dirtyWords().get(id), "commit 失败后 dirtyWords 必须保留");

            resetCounters();
            store.saveDirty(id); // 第二轮：重试

            assertEquals(1, wordRows, "重试轮必须重写失败那一批词（UPSERT 幂等）");
            assertEquals(1, playerDataRows, "重试轮同样写 player_data 行");
            assertFalse(dirtyPlayers().contains(id), "重试成功后标记才允许清理");
        }
    }

    // ==================================================================
    // 9-11：R7 红队 P1 的正面对应
    // ==================================================================

    @Nested
    @DisplayName("R7 红队 P1 回归：保存窗口竞态与两档全量兜底")
    class RedTeamRegressions {

        /**
         * <b>对应红队 P1（R7-C P1-3 / 追补 P2-2）：commit 窗口内的新写入被误清标记。</b>
         *
         * <p>缺陷形态：保存线程记下写入序号 → 逐词取值 → 主线程在"取值之后、commit 之前"
         * 又改了一个已经在 dirtyWords 里的词（add 是 no-op，集合看不出变化）→ commit 后
         * 若按"集合非空/按词移除"判断，就会把这个词的标记清掉，而它的新值从未落库。
         * 这里用 {@code connection.commit()} 的 doAnswer 把这次写入精确塞进窗口。
         *
         * <p>验收点：本轮 commit 之后 {@code clearDirty} 因序号变化而<b>整批保留</b>，
         * 下一轮仍能写出该词，而不是静默丢掉。
         */
        @Test
        @DisplayName("P1：commit 窗口内回灌 recordReview 时，本轮不清标记、下一轮仍写出该词")
        void dirtyFlagsSurviveWriteDuringSaveWindow() throws Exception {
            UUID id = UUID.randomUUID();
            seedWords(id, "water");

            // 第一轮：在 commit() 内部（= 保存窗口最末端）追加一次新写入。
            doAnswer(inv -> {
                store.recordReview(id, "book");
                return null;
            }).doNothing().when(connection).commit();

            store.saveDirty(id);

            assertEquals(1, wordRows, "第一轮只写迭代到的 water；book 是 commit 期间才出现的");
            assertTrue(dirtyPlayers().contains(id), "窗口内有新写入 → 本轮不得清 dirtyPlayers");
            assertTrue(dirtyWords().get(id).contains("book"),
                    "窗口内新增的 book 必须保留标记，否则它的 reviewCount 永不落库");

            resetCounters();
            store.saveDirty(id); // 第二轮 commit 已恢复为正常

            assertEquals(1, wordRows,
                    "第二轮必须重写 water（book 无 mastery 会被跳过，这是既定语义）");
            assertEquals(1, playerDataRows, "第二轮同样带 player_data 行");
        }

        /**
         * <b>对应红队 P1（R7-A 兜底的第一道门）：活跃轮次的全量兜底。</b>
         *
         * <p>缺陷形态：某个写入点漏标 markDirty，该玩家的这部分数据只能等全量保存纠正。
         * 若没有"每 N 个活跃轮次全量一次"的兜底，漏标数据就一直滞后到 quit。
         *
         * <p>为什么断言"词行数"而不是"prepareStatement 次数"：第 12 轮本身只写 1 个脏词
         * + 1 行统计，prepared statement 的<b>数量</b>与非兜底轮次完全相同（都是 2 条：
         * 词 UPSERT + player_data UPSERT）。唯一稳定信号是词行数从 1 变 2 —— 因为全量会
         * 把 masteryMap 里的<b>全部</b> 2 个词都写一遍。
         */
        @Test
        @DisplayName("P1：第 12 个活跃轮次走全量兜底，词行数序列为 1×11 然后 2")
        void twelfthActiveRoundFallsBackToFullSave() throws Exception {
            UUID id = UUID.randomUUID();

            warmUpTwoWordsAndFlush(id);
            assertEquals(2, wordRows, "预热轮（全量）写全部 2 个词");
            assertEquals(0, activeRoundCount(id), "全量预热不得消耗活跃轮次计数");
            resetCounters();

            List<Integer> perRound = new ArrayList<>();
            for (int round = 1; round <= 12; round++) {
                store.recordReview(id, "alpha"); // 每轮只脏 1 个词
                int before = wordRows;
                store.saveDirty(id);
                perRound.add(wordRows - before);
            }

            assertEquals(List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2), perRound,
                    "前 11 轮增量（1 行），第 12 轮命中 FULL_SAVE_EVERY_N_DIRTY_SAVES 走全量（2 行）");
        }

        /**
         * <b>对应红队 P1（R7-C P1-2）：漏标玩家在"只看活跃轮次"的兜底里结构性不可达。</b>
         *
         * <p>缺陷形态：漏标玩家的可观察状态与"真空闲玩家"完全一致（没有任何标记）。
         * 若空转轮次直接 return、不计数，兜底对该玩家<b>永不触发</b>，数据只能等 quit。
         * 修复后空转也有独立计数：连续 36 轮无标记 → 强制全量。
         *
         * <p>验收点：前 35 轮必须<b>零 SQL</b>（这是本流的核心收益），第 36 轮才全量写 2 行。
         */
        @Test
        @DisplayName("P1：第 36 个连续空转轮次走全量兜底，前 35 轮零 SQL")
        void thirtySixthIdleRoundFallsBackToFullSave() throws Exception {
            UUID id = UUID.randomUUID();

            warmUpTwoWordsAndFlush(id);
            assertEquals(2, wordRows, "预热轮（全量）写全部 2 个词");
            resetCounters();

            for (int round = 1; round <= 35; round++) {
                int before = wordRows;
                store.saveDirty(id); // 无任何标记 → 空转
                assertEquals(0, wordRows - before, "第 " + round + " 个空转轮次必须零词行");
            }
            verify(connection, never()).prepareStatement(anyString());

            int before = wordRows;
            store.saveDirty(id); // 第 36 轮：命中 IDLE_FULL_SAVE_EVERY_N_ROUNDS
            assertEquals(2, wordRows - before,
                    "第 36 个连续空转轮次必须全量重写全部 2 个词，否则漏标数据永远无法自愈");
        }
    }

    // ==================================================================
    // 12-14：增量语义
    // ==================================================================

    @Nested
    @DisplayName("增量语义：只写该写的行")
    class IncrementalSemantics {

        /**
         * 防：只有 player_data 脏时仍去起词 UPSERT 事务（或反过来漏写统计行）。
         */
        @Test
        @DisplayName("只有统计脏的一轮：0 条词行、1 条 player_data 行")
        void statsOnlyRoundWritesNoWordRows() throws Exception {
            UUID id = UUID.randomUUID();
            store.getPlayerStats(id); // 预热：冷缓存会先 loadPlayerData（2 次查询）
            resetCounters();

            store.getPlayerStats(id).addScore(7L); // 模拟 WordManagerImpl 的外部直改
            store.saveDirty(id);

            assertEquals(0, wordRows, "没有脏词时不得 addBatch（空 batch 也不该执行）");
            assertEquals(1, playerDataRows, "total_score 必须靠 player_data 行落库");
            verify(ps, never()).executeBatch();
        }

        /**
         * 防：把"没有 mastery 的脏词"当成可写行，用 0.0 覆盖 DB 真值；
         * 也防"重新加回脏集合"导致每轮都白起一次事务（R7-C P2-1 的取舍）。
         */
        @Test
        @DisplayName("无 mastery 的脏词被跳过且丢弃标记：本轮 0 词行，下一轮零 SQL")
        void dirtyWordWithoutMasteryIsSkippedNotRewritten() throws Exception {
            UUID id = UUID.randomUUID();

            seedWords(id, "apple");
            store.saveDirty(id); // 清标记，建立干净基线
            resetCounters();

            store.recordReview(id, "zebra"); // zebra 没有 masteryMap 条目
            store.saveDirty(id);

            assertEquals(0, wordRows, "没有 mastery 就没有可写的行，绝不能拿 0.0 覆盖");
            assertEquals(1, playerDataRows, "但 player_data 行仍要写");

            resetCounters();
            store.saveDirty(id); // 第三轮：标记已被丢弃 → 空转
            verify(connection, never()).prepareStatement(anyString());
            assertEquals(0, wordRows, "丢弃标记后不得每轮重试这个写不出的词");
        }

        /**
         * 防：loadPlayerData 无条件覆盖缓存 —— 脏数据还没落盘就被 DB 旧值盖掉，
         * 随后标记被下一次保存"兑现"成旧值，等于静默回档。
         */
        @Test
        @DisplayName("脏状态下 loadPlayerData 先冲洗（1 词行 + 1 统计行）再查询 2 次")
        void loadPlayerDataFlushesDirtyBeforeOverwriting() throws Exception {
            UUID id = UUID.randomUUID();
            seedWords(id, "apple");
            resetCounters();

            store.loadPlayerData(id);

            verify(ps, times(1)).addBatch();      // 冲洗：全量写 1 个词
            verify(ps, times(1)).executeUpdate(); // 冲洗：player_data 行
            verify(ps, times(2)).executeQuery();  // 之后才 load word_progress + player_data
            assertEquals(1, wordRows, "冲洗必须发生在覆盖之前");
            assertFalse(dirtyPlayers().contains(id), "冲洗成功后标记应被兑现");
        }
    }

    // ==================================================================
    // 17-22：FIX-1 追补 —— 第二轮红队实测出的"声称保护却保护不了"
    // ==================================================================

    /**
     * FIX-1 追补组：第二轮红队对已有 16 个用例做的变异测试（把源码改成缺陷形态，
     * 看测试是否变红）发现了四条真实缺口。本组每一条都对应一次"改了源码仍然全绿"。
     *
     * <p>共同手法：把被测代码<b>读的那个缓存</b>换成"读取即触发一次真实写入"的探针代理，
     * 用反射塞进字段，从而在精确的时刻（迭代开始时 / 取 map 时）复现竞态。
     * 直接 sleep + 线程对拍不可行（无法保证命中窗口），而代理把"窗口"变成确定性事件。
     */
    @Nested
    @DisplayName("FIX-1 追补：第二轮红队实测缺口（活引用 / seq 采集点 / 回暖清零 / 列序）")
    class RedTeamGaps {

        /**
         * <b>对应红队发现：P1 活引用无保护（P2-2 追补的护栏是假的）。</b>
         *
         * <p>红队实测：把 {@code saveDirty} 里的 {@code hasWords ? words : ...} 改回
         * {@code new ArrayList<>(words)}（即参数求值时就拷贝），全部 67 个用例<b>依然全绿</b>。
         * 原因是既有的 {@code dirtyFlagsSurviveWriteDuringSaveWindow} 只在
         * {@code commit()} 里回灌写入 —— 那时迭代早已结束，拷贝与活引用结果完全相同，
         * 抓不到"拷贝完成 → seq 采集"之间的窗口。
         *
         * <p>本用例把窗口钉在<b>第一次 {@code iterator()}</b>：此时 {@code seqAtSnapshot}
         * 已被采集（方法体第一行），而写入者尚未发生。若 {@code saveDirty} 在参数求值时
         * 就拷贝成 ArrayList，那次 {@code recordReview} 的标记会被 {@code clearDirty}
         * 一并清掉（序号没变），{@code book} 从此丢失；活引用则要么被本次迭代看到、
         * 要么让序号变化而整批保留 —— 两种分支都保住标记。
         *
         * <p>关键：{@code book} 刻意<b>没有</b> mastery（只有 recordReview，从未走过
         * {@code updateWordProgress}）。这样即使它被写进去也会走 skip 分支，于是
         * "写出的行数"完全无法证明标记还在 —— 必须直接断言 {@code dirtyWords} 的内容，
         * 这正是本用例与 {@code dirtyFlagsSurviveWriteDuringSaveWindow} 的区别。
         */
        @Test
        @DisplayName("P1：dirtyWords 迭代开始时的写入必须保住标记（钉死活引用，防 ArrayList 拷贝回退）")
        void incrementalPathUsesLiveReference() throws Exception {
            UUID id = UUID.randomUUID();
            seedWords(id, "water");
            store.saveDirty(id); // 清标记：本轮只让探针触发的那一次写入是"新"的
            resetCounters();

            final Set<String> realSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
            realSet.add("water");

            // 持有对 store 的引用而非直接用外层字段：嵌套类作用域里更清晰。
            final PlayerDataStore target = store;
            final Set<String> proxy = new AbstractSet<String>() {
                private boolean triggered;

                /**
                 * 触发点覆盖两条路径，让"拷贝版"与"活引用版"都真实复现那次写入：
                 * <ul>
                 *   <li><b>活引用版</b>：保存循环只调 {@code iterator()}，触发点落在
                 *       seq 采集<b>之后</b> —— 这正是线上时序；</li>
                 *   <li><b>拷贝版</b>（{@code new ArrayList<>(words)}）：构造时先调
                 *       {@code toArray()}，触发点落在参数求值那一刻 —— 即
                 *       "拷贝完成 → seq 采集"窗口的左端，正是 P2-2 缺陷的真实时序。</li>
                 * </ul>
                 * 若只在 {@code iterator()} 触发，拷贝版会因"代理从未被迭代"而失败，
                 * 属于测到了错误的原因；补上 {@code toArray()} 后两条路径都触发一次真实写入，
                 * 断言才真正指向"标记被误清"而不是"写入没发生"。
                 */
                private void triggerOnce() {
                    if (!triggered) {
                        triggered = true;
                        target.recordReview(id, "book");
                    }
                }

                @Override
                public Iterator<String> iterator() {
                    triggerOnce();
                    return realSet.iterator();
                }

                @Override
                public Object[] toArray() {
                    triggerOnce();
                    return realSet.toArray();
                }

                @Override
                public int size() {
                    return realSet.size();
                }

                @Override
                public boolean add(String word) {
                    return realSet.add(word);
                }

                @Override
                public boolean contains(Object o) {
                    return realSet.contains(o);
                }
            };
            // 先把代理塞进 dirtyWords，再走真实公开入口标脏 —— markWordDirty 的
            // computeIfAbsent 会命中代理，add() 委托给 realSet，语义与线上一致。
            Map<UUID, Set<String>> dw = dirtyWords();
            dw.put(id, proxy);
            target.recordReview(id, "water");

            store.saveDirty(id);

            Set<String> remaining = dirtyWords().get(id);
            assertNotNull(remaining, "迭代期写入必须让 clearDirty 整批保留，而不是清空集合");
            assertTrue(remaining.contains("book"),
                    "迭代开始后才写入的 book 必须保留标记：若 saveDirty 在参数求值时就 "
                            + "new ArrayList<>(words)，它的标记会被误清，reviewCount 永不落库");
            assertTrue(dirtyPlayers().contains(id), "保留分支必须连 dirtyPlayers 一起保留");
        }

        /**
         * <b>对应红队发现：P1-3 seq 采集点无保护。</b>
         *
         * <p>红队实测：把 {@code seqAtSnapshot} 从方法体首行挪到读 {@code wordMasteryCache}
         * 之后，全部 67 个用例<b>依然全绿</b>。现有用例都靠 {@code commit()} 回灌，
         * 收集不到"取 map 那一刻"的窗口。
         *
         * <p>本用例把 {@code wordMasteryCache} 换成代理：{@code get(playerId)} 时先触发
         * 一次 {@code recordReview(id, "book")}，再返回真实 map。若 seq 采集晚于这次
         * {@code get}，序号已经包含 book 的写入，而本次写入的集合快照里没有它 ——
         * commit 后 {@code clearDirty} 看到"序号没变"就把 book 的标记清掉。
         *
         * <p>与上一条的区别：上一条钉"何时迭代脏词集合"，本一条钉"何时采集序号"。
         * 两条互为镜像，任一侧退步都会各自变红。
         */
        @Test
        @DisplayName("P1：wordMasteryCache 取值时的写入必须保住标记（钉死 seq 先于任何取值）")
        void sequenceCapturedBeforeReadingCaches() throws Exception {
            UUID id = UUID.randomUUID();
            seedWords(id, "water");
            store.saveDirty(id);
            resetCounters();

            Map<UUID, Map<String, Double>> realMastery = readField(store, "wordMasteryCache");
            Map<String, Double> realMap = realMastery.get(id);
            assertNotNull(realMap, "预热后 masteryMap 必须存在");

            final PlayerDataStore target = store;
            Map<UUID, Map<String, Double>> proxyOuter = new AbstractMap<UUID, Map<String, Double>>() {
                private boolean triggered;

                @Override
                public Map<String, Double> get(Object key) {
                    if (!triggered && id.equals(key)) {
                        triggered = true;
                        // 取值瞬间 = 若 seq 采集在这之后，这次写入会被漏出快照。
                        target.recordReview(id, "book");
                    }
                    return realMastery.get(key);
                }

                @Override
                public Set<Entry<UUID, Map<String, Double>>> entrySet() {
                    return realMastery.entrySet();
                }
            };
            writeField(store, "wordMasteryCache", proxyOuter);

            // 触发一次增量写：water 仍脏（ensure 本轮有词行），book 由代理在取 map 时写入。
            store.recordReview(id, "water");
            store.saveDirty(id);

            // 还原字段：后续断言与 @AfterEach 都走真实 map，避免代理影响。
            writeField(store, "wordMasteryCache", realMastery);

            Set<String> remaining = dirtyWords().get(id);
            assertNotNull(remaining,
                    "若 seq 采集晚于取值，book 的标记会被当成\"已保存\"清掉，下一轮零 SQL 前它永不落库");
            assertTrue(remaining.contains("book"),
                    "seq 必须最先采集：取值期间发生的写入不得被 clearDirty 误判为已覆盖");
        }

        /**
         * <b>对应红队发现：P2 回暖不清零空转计数。</b>
         *
         * <p>红队实测：删掉 {@code PlayerDataStore:866} 的 {@code idleSaveCount.remove(playerId)}，
         * <b>0 个用例变红</b>。原因是既有用例里"回暖"与"空转"各自独立发生，
         * 从没有交替过 —— 空转计数是否被清零完全观察不到。
         *
         * <p>场景：34 轮空转 → 1 轮活跃（若有清零，此轮把计数归 0）→ 再 35 轮空转 → 第 36 轮。
         * 有清零时，前 35 个<b>连续</b>空转轮次必须零全量，全量只出现在第 36 轮；
         * 没有清零时，计数从 34 直接续累，第 <b>2</b> 个空转轮次就到 36 并提前全量。
         *
         * <p>为什么不用"全类总共几次全量"断言：两种实现下全量次数都是 1（提前触发后
         * 计数被归 0，剩下的轮次不够再触发一次），总数不具区分度。真正稳定的信号是
         * <b>全量发生在第几个空转轮次</b>，因此分两段断言：前 35 轮零全量 + 第 36 轮恰好一次。
         */
        @Test
        @DisplayName("P2：活跃轮次必须重置空转计数 —— 回暖后必须重新数满 36 轮才全量")
        void activeRoundResetsIdleCounter() throws Exception {
            UUID id = UUID.randomUUID();
            warmUpTwoWordsAndFlush(id);
            resetCounters();

            for (int i = 0; i < 34; i++) {
                store.saveDirty(id);
            }
            assertEquals(0, wordRows, "前 34 个空转轮次必须零 SQL（收益基线）");

            // 1 轮活跃：必须把连续空转计数清零。
            store.recordReview(id, "alpha");
            store.saveDirty(id);
            assertEquals(1, wordRows, "活跃轮次只写 1 个脏词（未到 12 轮全量）");
            assertEquals(0, idleRoundCount(id), "R7-C/P1-2：回暖轮次必须重置 idleSaveCount");
            resetCounters();

            // 回暖后的前 35 个空转轮次：计数 1..35，一律不到 36 —— 必须零全量。
            // 若 idleSaveCount.remove 被删，计数从 34 续累，第 2 轮就会提前全量。
            for (int i = 1; i <= 35; i++) {
                int before = wordRows;
                store.saveDirty(id);
                assertEquals(0, wordRows - before,
                        "回暖后第 " + i + " 个空转轮次不得全量 —— 若提前触发，说明 idleSaveCount "
                                + "没有被回暖轮次重置（源码 :866 的 remove 缺失）");
            }

            // 第 36 个连续空转轮次：恰好一次全量，重写全部 2 个词。
            int before = wordRows;
            store.saveDirty(id);
            assertEquals(2, wordRows - before,
                    "回暖后第 36 个连续空转轮次必须全量（计数从 0 重新数满）");
        }

        /**
         * <b>对应红队发现：P2 词行 7 列的值完全没有断言。</b>
         *
         * <p>红队实测：把 {@code correct_count} 与 {@code incorrect_count} 两列互换、
         * {@code mastery} 恒写 0.0、{@code review_count} 恒写 0、词名写大写、
         * uuid 写 null —— <b>全部 0 个用例变红</b>。既有用例只数 {@code addBatch()} 次数，
         * 对写进去的值一无所知。
         *
         * <p>列序依据源码 {@code buildUpsertSql}（已人工核对）：
         * {@code (uuid, word, mastery, correct_count, incorrect_count, last_review_time, review_count)}
         * → 1..7 号位恰好按此顺序 {@code setString/setDouble/setInt/setLong/setInt}。
         *
         * <p>为什么用两个独立 PreparedStatement mock：{@code setUp} 对两条 SQL 返回
         * 同一个 {@code ps}，{@code setInt(4, x)} 会同时收到"词行 correct_count"与
         * "player_data total_sessions"，列号断言互相污染。按 SQL 分派后才能逐列钉死。
         */
        @Test
        @DisplayName("P2：word_progress 七列按 buildUpsertSql 顺序逐位断言（uuid/词/mastery/对/错/时间/次数）")
        void wordProgressColumnValuesAreCorrect() throws Exception {
            useSeparateStatements();
            UUID id = UUID.randomUUID();

            seedWords(id, "water"); // 1 次答对：correct=1, incorrect=0, mastery=0.2
            store.saveDirty(id);
            resetCounters();

            store.recordReview(id, "WATER"); // 大写输入，必须归一成 water
            store.saveDirty(id);

            verify(wordPs, times(1)).setString(1, id.toString());
            verify(wordPs, times(1)).setString(2, "water");
            verify(wordPs, times(1)).setDouble(3, 0.2);
            verify(wordPs, times(1)).setInt(4, 1);
            verify(wordPs, times(1)).setInt(5, 0);
            verify(wordPs, times(1)).setLong(eq(6), org.mockito.ArgumentMatchers.longThat(ts -> ts > 0L));
            verify(wordPs, times(1)).setInt(7, 1);
            verify(wordPs, times(1)).addBatch();

            // 反向断言：列值绝不能串位（correct 与 incorrect 互换是可复现的红队变异）。
            verify(wordPs, never()).setString(2, "WATER");
            verify(wordPs, never()).setDouble(3, 0.0);
            verify(wordPs, never()).setInt(4, 0);
        }

        /**
         * <b>对应红队发现：P2 player_data 其余列值无断言。</b>
         *
         * <p>红队实测：把 {@code total_words_learned} 与 {@code total_sessions} 互换，
         * 0 个用例变红 —— 既有集成用例只断言了 {@code setLong(5, 42L)}（total_score）。
         *
         * <p>列序依据 {@code buildPlayerDataUpsertSql}：
         * {@code (uuid, player_name, total_words_learned, total_sessions, total_score, updated_at)}。
         *
         * <p>构造一组可区分的值：3 个词全答对 → totalWords=3；2 局 → totalSessions=2；
         * 分数 42。三个数字两两不同，任何两位互换都会被抓到。
         */
        @Test
        @DisplayName("P2：player_data 六列逐位断言（words=3 / sessions=2 / score=42 互不相同，防串位）")
        void playerDataColumnValuesAreCorrect() throws Exception {
            useSeparateStatements();
            UUID id = UUID.randomUUID();

            store.cachePlayerName(id, "Steve");

            // 3 个词：每个 1 次答对 → totalWords = totalCorrect = 3
            seedWords(id, "alpha", "beta", "gamma");
            PlayerDataStore.PlayerStats stats = store.getPlayerStats(id);
            stats.addScore(42L);   // totalScore = 42
            stats.setSessions(2);  // totalSessions = 2

            resetCounters();
            store.saveDirty(id);

            verify(statsPs, times(1)).setString(1, id.toString());
            verify(statsPs, times(1)).setString(2, "Steve");
            verify(statsPs, times(1)).setInt(3, 3);    // total_words_learned
            verify(statsPs, times(1)).setInt(4, 2);    // total_sessions
            verify(statsPs, times(1)).setLong(5, 42L); // total_score
            verify(statsPs, times(1)).setLong(eq(6),
                    org.mockito.ArgumentMatchers.longThat(ts -> ts > 0L)); // updated_at
            verify(statsPs, times(1)).executeUpdate();

            // 防串位：3 与 2 互换后这两条必定失败。
            verify(statsPs, never()).setInt(3, 2);
            verify(statsPs, never()).setInt(4, 3);
        }
    }

    // ==================================================================
    // 15-16：quit / reload 路径（不变式 ③：全量兜底永远保留）
    // ==================================================================

    @Nested
    @DisplayName("quit / reload 路径：全量兜底与停止写入")
    class QuitAndReloadPaths {

        /**
         * 防：unloadPlayerData 也走增量 —— 下线玩家从此再没有下一次保存，
         * 未标脏的词就永久丢失。
         */
        @Test
        @DisplayName("unloadPlayerData 无视脏标记强制全量：只脏 1 词也写 2 行，随后清空标记与序号")
        void unloadPlayerDataAlwaysFullSaves() throws Exception {
            UUID id = UUID.randomUUID();

            seedWords(id, "alpha", "beta");
            store.saveDirty(id); // 清标记
            resetCounters();

            store.recordReview(id, "alpha"); // 本轮只有 1 个词脏
            store.unloadPlayerData(id);

            assertEquals(2, wordRows, "unload 必须全量写全部 2 个词，而不是只写脏的 alpha");
            assertEquals(1, playerDataRows, "下线时 player_data 行同样要写");
            assertFalse(dirtyPlayers().contains(id), "下线后必须清残留标记，避免为已下线玩家积累");
            assertNull(writeSeq().get(id), "下线后必须清写序号");
            assertEquals(0, activeRoundCount(id), "下线后必须清兜底计数");
        }

        /**
         * 防：clearCache（/lea reload）只清了数据 map 却留着脏标记 ——
         * 下一次 saveDirty 会拿"已落盘且缓存已清"的过期标记空跑，甚至写出空行。
         *
         * <p><b>FIX-1（红队实测假绿修正）</b>：原实现把 {@code dirtyPlayers().isEmpty()}
         * 两条断言放在 {@code saveDirty(id)} <b>之后</b>。红队删掉 {@code clearCache()} 里的
         * {@code dirtyPlayers.clear()} 后本用例仍然全绿 —— 因为 saveDirty 走到
         * {@code masteryMap == null && snap == null} 的早退分支，那里自己
         * {@code dirtyPlayers.remove(playerId)} / {@code dirtyWords.remove(playerId)}，
         * 替 clearCache 把标记清了。断言只要放在 saveDirty 之后，就永远无法区分
         * "clearCache 自己清" 与 "早退分支代劳"。
         *
         * <p>修法：把两条 {@code isEmpty()} 断言 <b>移到 clearCache 之后、saveDirty 之前</b>，
         * 并在 clearCache 之后立刻断言"零 SQL"（此时若 clearCache 漏清标记，saveDirty
         * 尚未被调用，prepareStatement 必然为 0 —— 但标记断言已经先红）。
         */
        @Test
        @DisplayName("clearCache 之后 saveDirty 完全静默：0 次 prepareStatement")
        void clearCacheStopsAllFurtherWrites() throws Exception {
            UUID id = UUID.randomUUID();
            seedWords(id, "alpha");
            store.cachePlayerName(id, "Steve");

            // ---- FIX-5（RT-G3 P2）：先把两个计数做成非零，再断言被清零 ----
            // 红队实测：原断言时点 dirtySaveCount / idleSaveCount 都是空 map，
            // 因此 activeRoundCount(id)==0 / idleRoundCount(id)==0 <b>永远为真</b> ——
            // 删掉 clearCache 里的两行 clear() 仍然全绿。空转断言的本质是
            // "对空 map 求 get(id)"，无论 clearCache 做没做都返回 0。
            //
            // 修法：直接往字段里塞非零值（两个字段都是 final ConcurrentHashMap，
            // 反射取到的就是 clearCache 将要操作的那个实例，不会被重赋值绕过）。
            // 这样 clear() 一旦缺失，下面的 isEmpty 立刻变红。
            // 选择反射播种而不是"先跑一轮 saveDirty"：后者会真的写库并推进
            // dirtySaveCount，反而需要额外 reset，且 idleSaveCount 在活跃轮次会被
            // 主动 remove（源码 :866），无法同时把两档计数都做成非零。
            @SuppressWarnings("unchecked")
            Map<UUID, Integer> dirtyCounts = readField(store, "dirtySaveCount");
            @SuppressWarnings("unchecked")
            Map<UUID, Integer> idleCounts = readField(store, "idleSaveCount");
            dirtyCounts.put(id, 7);
            idleCounts.put(id, 5);
            assertEquals(7, activeRoundCount(id), "前置条件：活跃轮次计数必须非零，否则断言无意义");
            assertEquals(5, idleRoundCount(id), "前置条件：空转计数必须非零，否则断言无意义");

            store.clearCache();

            // ---- clearCache 自身契约：必须在 saveDirty 之前断言 ----
            // 这两条是本用例真正的验收点。若有人删掉 clearCache 里的 clear()，
            // saveDirty 的早退分支会让后面所有断言依然全绿（红队已实测）。
            assertTrue(dirtyPlayers().isEmpty(),
                    "clearCache 必须自己清空 dirtyPlayers —— 不能依赖 savePlayerDataInternal 的早退分支代劳");
            assertTrue(dirtyWords().isEmpty(),
                    "clearCache 必须自己清空 dirtyWords 集合");
            assertNull(writeSeq().get(id),
                    "clearCache 必须清掉写序号（否则残留序号会让后续判断失真）");
            assertTrue(dirtyCounts.isEmpty(),
                    "clearCache 必须清空活跃轮次计数 —— 计数已预置为 7，未清则此处必红");
            assertTrue(idleCounts.isEmpty(),
                    "clearCache 必须清空空转计数 —— 计数已预置为 5，未清则此处必红");
            assertEquals(0, activeRoundCount(id), "clearCache 后活跃轮次计数必须归零");
            assertEquals(0, idleRoundCount(id), "clearCache 后空转计数必须归零");
            // clearCache 绝不落盘：它的契约是"HotReloadManager 已先行全量刷盘"。
            verify(connection, never()).prepareStatement(anyString());

            resetCounters();
            store.saveDirty(id);

            verify(connection, never()).prepareStatement(anyString());
            assertEquals(0, wordRows, "缓存已清，不得再写任何词行");
            assertEquals(0, playerDataRows, "缓存已清，不得再写 player_data 行");
        }
    }
}
