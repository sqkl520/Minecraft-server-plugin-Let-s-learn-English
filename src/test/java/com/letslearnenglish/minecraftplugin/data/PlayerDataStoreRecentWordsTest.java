package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.LearningSession;
import com.letslearnenglish.minecraftplugin.core.word.Word;

/**
 * B2b（R3-b）：{@link PlayerDataStore#getRecentlyAnsweredWords(UUID, long)} 的窗口语义，
 * 以及「纯内存、绝不进写路径」这条红线。
 *
 * <p>为什么这条红线值得单独一组用例：{@code PlayerDataStore} 的增量落库体系有 21 个测试
 * 钉死其五条不变式，而 {@code recentAnswerCache} 是<b>没有落盘语义</b>的缓存 ——
 * 一旦有人把它塞进 {@code savePlayerDataInternal}，后果不是"多写一列"，而是：
 * ① 每个保存轮次多一次无意义的 SQL 绑定；② 让人误以为"窗口跨重启保留"，
 * 而窗口的真实生命周期只到 quit/reload。本类用<b>访问探针</b>把这条约束变成红灯，
 * 而不是依赖后人读注释。
 *
 * <p>mock 骨架照 {@code PlayerDataStoreDirtyTest}：mock plugin + DatabaseManager +
 * 真 {@code Logger}（失败路径的 {@code log(WARNING, ...)} 不 NPE）+ stub
 * {@code getDifficultWords} 返回空（{@code loadPlayerData} 会调它）。
 */
class PlayerDataStoreRecentWordsTest {

    private static final long WINDOW_MS = 30L * 60_000L; // 默认配置的 30 分钟

    private LetsLearnEnglish plugin;
    private DatabaseManager databaseManager;
    private Connection connection;
    private PreparedStatement ps;
    private ResultSet rs;
    private PlayerDataStore store;

    /** 词行探针：{@code addBatch()} 次数 == 本轮写出的 word_progress 行数。 */
    private int wordRows;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        databaseManager = mock(DatabaseManager.class);
        connection = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);

        when(plugin.getLogger()).thenReturn(Logger.getLogger("test-recent-words"));
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);

        when(databaseManager.getTablePrefix()).thenReturn("lle_");
        when(databaseManager.isSQLite()).thenReturn(true);
        when(databaseManager.getConnectionLock()).thenReturn(new Object());
        when(databaseManager.getDifficultWords(any())).thenReturn(Collections.emptyList());
        when(databaseManager.getConnection()).thenReturn(connection);

        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[0]);
        when(ps.executeUpdate()).thenReturn(1);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false); // load 回来是空表

        doAnswer(inv -> {
            wordRows++;
            return null;
        }).when(ps).addBatch();

        wordRows = 0;
        store = new PlayerDataStore(plugin, databaseManager);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 构造 {@code updateWordProgress} 的结算结果：每词 1 次答对。 */
    private static Map<String, LearningSession.WordResult> correctResults(String... words) {
        Map<String, LearningSession.WordResult> map = new HashMap<>();
        for (String word : words) {
            Word w = new Word(word, word, "n", "", "", List.of(), List.of(), "easy", 1);
            LearningSession.WordResult r = new LearningSession.WordResult(w);
            r.incrementCorrect();
            map.put(word, r);
        }
        return map;
    }

    /** 构造「答错」的结算结果：验证答错同样记账（R3-b 要求对/错都写）。 */
    private static Map<String, LearningSession.WordResult> incorrectResults(String... words) {
        Map<String, LearningSession.WordResult> map = new HashMap<>();
        for (String word : words) {
            Word w = new Word(word, word, "n", "", "", List.of(), List.of(), "easy", 1);
            LearningSession.WordResult r = new LearningSession.WordResult(w);
            r.incrementIncorrect();
            map.put(word, r);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static <T> T readField(Object target, String name) throws Exception {
        Field f = PlayerDataStore.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private Map<UUID, Map<String, Long>> recentCache() throws Exception {
        return readField(store, "recentAnswerCache");
    }

    /** 把某词的答题时间戳改到窗口外（模拟"上一局是很久以前答的"）。 */
    private void ageWord(UUID playerId, String word, long millisAgo) throws Exception {
        Map<String, Long> answers = recentCache().get(playerId);
        answers.put(word, System.currentTimeMillis() - millisAgo);
    }

    // ------------------------------------------------------------------
    // 1. 窗口内命中（对/错都记账）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("答题后窗口内命中：答对与答错的词都进集合（全部走 updateWordProgress）")
    void answeredWordsAreVisibleInsideWindow() {
        UUID id = UUID.randomUUID();

        store.updateWordProgress(id, correctResults("apple", "banana"));
        store.updateWordProgress(id, incorrectResults("cherry"));

        Set<String> recent = store.getRecentlyAnsweredWords(id, WINDOW_MS);

        assertEquals(Set.of("apple", "banana", "cherry"), recent,
                "R3-b：答对与答错都必须记账 —— 写入点在 updateWordProgress 的循环里，"
                        + "两条路径共用同一处；只有答错/只有答对都说明写入点被挪走了");
    }

    @Test
    @DisplayName("词键统一小写：结算结果的 key 是大写时，取回集合仍是小写词")
    void wordKeysAreNormalisedToLowerCase() {
        UUID id = UUID.randomUUID();
        Map<String, LearningSession.WordResult> results = new HashMap<>();
        Word w = new Word("Apple", "苹果", "n", "", "", List.of(), List.of(), "easy", 1);
        LearningSession.WordResult r = new LearningSession.WordResult(w);
        r.incrementCorrect();
        results.put("Apple", r);

        store.updateWordProgress(id, results);

        assertEquals(Set.of("apple"), store.getRecentlyAnsweredWords(id, WINDOW_MS),
                "窗口里的键必须与 WordBank / masteredWords / dirtyWords 同口径（toLowerCase），"
                        + "否则排除集永远匹配不上词库里的词");
    }

    // ------------------------------------------------------------------
    // 2. 窗口外剪枝后为空
    // ------------------------------------------------------------------

    @Test
    @DisplayName("窗口外：读取时剪枝，返回空集且缓存条目被真正删除（防只增不删）")
    void entriesOutsideWindowArePruned() throws Exception {
        UUID id = UUID.randomUUID();
        store.updateWordProgress(id, correctResults("apple"));
        // 把时间戳推到窗口外 1 分钟。
        ageWord(id, "apple", WINDOW_MS + 60_000L);

        Set<String> recent = store.getRecentlyAnsweredWords(id, WINDOW_MS);

        assertTrue(recent.isEmpty(), "窗口外的词必须不出现在结果里");
        assertFalse(recentCache().containsKey(id),
                "剪枝后外层条目必须一起摘掉 —— 只把内层 map 清空、外层条目留着，"
                        + "在线玩家的 UUID 会一直堆在 recentAnswerCache 里");
    }

    @Test
    @DisplayName("窗口滑动：同一玩家新旧词共存时，只返回窗口内的那部分")
    void pruningKeepsInWindowEntries() throws Exception {
        UUID id = UUID.randomUUID();
        store.updateWordProgress(id, correctResults("fresh"));
        store.updateWordProgress(id, correctResults("stale"));
        ageWord(id, "stale", WINDOW_MS + 1L);

        assertEquals(Set.of("fresh"), store.getRecentlyAnsweredWords(id, WINDOW_MS),
                "剪枝必须逐词判定：把整个玩家条目清掉会误删刚刚答过的词");
        assertTrue(recentCache().get(id).containsKey("stale") == false,
                "窗口外的 stale 必须被删掉");
    }

    // ------------------------------------------------------------------
    // 3. unloadPlayerData 后为空
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unloadPlayerData 后清空：纯内存窗口随下线丢弃")
    void unloadClearsRecentAnswers() {
        UUID id = UUID.randomUUID();
        store.updateWordProgress(id, correctResults("apple"));
        assertFalse(store.getRecentlyAnsweredWords(id, WINDOW_MS).isEmpty(), "前置条件：窗口内应有词");

        store.unloadPlayerData(id);

        assertTrue(store.getRecentlyAnsweredWords(id, WINDOW_MS).isEmpty(),
                "quit 后窗口必须清空（recentAnswerCache 不落库，留着就是给已下线玩家积内存）");
    }

    // ------------------------------------------------------------------
    // 4. clearCache 后为空
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearCache 后清空：/lea reload 时窗口整体作废")
    void clearCacheClearsRecentAnswers() throws Exception {
        UUID id = UUID.randomUUID();
        store.updateWordProgress(id, correctResults("apple"));
        assertFalse(recentCache().isEmpty(), "前置条件：recentAnswerCache 必须非空，否则断言无意义");

        store.clearCache();

        assertTrue(recentCache().isEmpty(),
                "clearCache 必须自己清空 recentAnswerCache（reload 后窗口不应延续缓存里的旧时间戳）");
        assertTrue(store.getRecentlyAnsweredWords(id, WINDOW_MS).isEmpty());
    }

    // ------------------------------------------------------------------
    // 5. windowMillis <= 0 返回空集
    // ------------------------------------------------------------------

    @Test
    @DisplayName("windowMillis <= 0：返回空集（0 = 配置关闭，且不误剪枝）")
    void nonPositiveWindowReturnsEmptySet() {
        UUID id = UUID.randomUUID();
        store.updateWordProgress(id, correctResults("apple"));

        assertTrue(store.getRecentlyAnsweredWords(id, 0L).isEmpty(),
                "0 必须表示「关闭」：recent-word-exclusion-minutes: 0 时调用方不做条件分支，"
                        + "由本方法返回空集把排除关掉");
        assertTrue(store.getRecentlyAnsweredWords(id, -1L).isEmpty(), "负值同样按关闭处理");
        assertFalse(store.getRecentlyAnsweredWords(id, WINDOW_MS).isEmpty(),
                "关闭语义不得顺手清掉缓存 —— 关着的时候不该有副作用");
    }

    @Test
    @DisplayName("未知玩家：返回空集而不是 null / NPE")
    void unknownPlayerReturnsEmptySet() {
        assertTrue(store.getRecentlyAnsweredWords(UUID.randomUUID(), WINDOW_MS).isEmpty(),
                "从未答过题的玩家必须返回空集（WordBank 的排除集按「可为空」设计）");
        assertTrue(store.getRecentlyAnsweredWords(null, WINDOW_MS).isEmpty(),
                "playerId == null 时不得 NPE");
    }

    // ------------------------------------------------------------------
    // 6. 保存路径绝不碰这个 map（红线）
    // ------------------------------------------------------------------

    /**
     * 红线：{@code savePlayerData} / {@code savePlayerDataInternal} 不得读写
     * {@code recentAnswerCache}。
     *
     * <p>探针手法：把字段换成一个<b>记录访问</b>的代理 map（委托真实 map），再走一次全量
     * 保存。任何 {@code get / entrySet / keySet / values / size / containsKey} 都会被记账，
     * 于是"有人把窗口塞进写路径"从"只有 code review 能发现"变成"下一次跑测试就红"。
     *
     * <p>同时钉住行数：2 个词 → 恰好 2 条词行。若窗口被当成词源参与绑定，行数会变成 4
     * （或出现 setString(2, 未在 masteryMap 里的词)）。
     */
    @Test
    @DisplayName("红线：全量保存期间不读取 recentAnswerCache（纯内存态，不进写路径）")
    void savePathNeverTouchesRecentAnswerCache() throws Exception {
        UUID id = UUID.randomUUID();
        store.updateWordProgress(id, correctResults("alpha", "beta"));

        Map<UUID, Map<String, Long>> real = recentCache();
        List<String> accesses = new CopyOnWriteArrayList<>();
        Map<UUID, Map<String, Long>> proxy = new AbstractMap<UUID, Map<String, Long>>() {
            @Override
            public Map<String, Long> get(Object key) {
                accesses.add("get");
                return real.get(key);
            }

            @Override
            public boolean containsKey(Object key) {
                accesses.add("containsKey");
                return real.containsKey(key);
            }

            @Override
            public Set<Entry<UUID, Map<String, Long>>> entrySet() {
                accesses.add("entrySet");
                return real.entrySet();
            }

            @Override
            public Set<UUID> keySet() {
                accesses.add("keySet");
                return real.keySet();
            }

            @Override
            public java.util.Collection<Map<String, Long>> values() {
                accesses.add("values");
                return real.values();
            }

            @Override
            public int size() {
                accesses.add("size");
                return real.size();
            }

            @Override
            public Map<String, Long> put(UUID key, Map<String, Long> value) {
                accesses.add("put");
                return real.put(key, value);
            }
        };
        Field f = PlayerDataStore.class.getDeclaredField("recentAnswerCache");
        f.setAccessible(true);
        f.set(store, proxy);
        accesses.clear();

        wordRows = 0;
        store.savePlayerData(id);

        f.set(store, real); // 还原，避免影响断言与后续清理

        assertEquals(2, wordRows, "全量保存只应写 masteryMap 里的 2 个词行");
        assertEquals(List.of(), accesses,
                "savePlayerData 期间不得访问 recentAnswerCache 喵（实际访问=" + accesses + "）。\n"
                        + "B2b 红线：这个 map 是纯内存窗口，没有落盘语义 —— "
                        + "把它塞进写路径只会让每轮保存多一次无意义的 SQL 绑定，"
                        + "并让后人误以为窗口跨重启保留。");
    }

    @Test
    @DisplayName("红线：循环内只有真正作答过的词进窗口（stop 残留的未作答词不冻结开局词表）")
    void unansweredWordsAreNotRecorded() {
        UUID id = UUID.randomUUID();
        Map<String, LearningSession.WordResult> results = new HashMap<>();
        // answered：1 次答对；skipped：correct=0 / incorrect=0（中途 stop 的残留）。
        answeredOf(results, "answered", true);
        answeredOf(results, "skipped", false);

        store.updateWordProgress(id, results);

        assertEquals(Set.of("answered"), store.getRecentlyAnsweredWords(id, WINDOW_MS),
                "未作答的词不该算「近期已答」—— 否则「开局即停」的玩家整局词表会被冻结 30 分钟");
    }

    private static void answeredOf(Map<String, LearningSession.WordResult> map,
                                   String word, boolean answered) {
        Word w = new Word(word, word, "n", "", "", List.of(), List.of(), "easy", 1);
        LearningSession.WordResult r = new LearningSession.WordResult(w);
        if (answered) {
            r.incrementCorrect();
        }
        map.put(word, r);
    }
}
