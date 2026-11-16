package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R13：错题本 GUI 的错次数字必须跟着本次在线期间的答错实时走。
 *
 * <p><b>缺陷本体</b>：{@code WrongBookGUI} 从 {@code getWrongBookEntries} 拿到
 * {@link DifficultWord} 列表，而 {@code DifficultWord} 是<b>不可变对象</b> ——
 * 它携带的 {@code wrongCount} 是<b>加进错题本那一刻</b>的快照。玩家本次在线期间继续答错，
 * {@code incrementWrongCount} 只改 {@code wrongCountCache}，不重建那个对象，
 * 于是界面一直显示旧数字（要重进服才刷新）。
 *
 * <p><b>修法</b>：GUI 改用 {@code getWrongCountIfCached(playerId, word, fallback)} ——
 * 查活缓存；缓存没有才回落快照值。本类锁三件事：
 * <ol>
 *   <li>缓存有新值时返回新值（缺陷本体）</li>
 *   <li>缓存没有该词时回落 snapshot（不是返回 0 —— 那会让界面显示 0 次，比旧值更离谱）</li>
 *   <li>缓存没有该玩家时同样回落 snapshot，且不 NPE</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("R13：错题本错次数字取活缓存")
class WrongCountLiveReadTest {

    @Mock
    private LetsLearnEnglish plugin;

    @Mock
    private DatabaseManager databaseManager;

    private PlayerDataStore store;

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000b001");

    @BeforeEach
    void setUp() {
        store = new PlayerDataStore(plugin, databaseManager);
    }

    @SuppressWarnings("unchecked")
    private void seedWrongCount(String word, int count) throws Exception {
        Field f = PlayerDataStore.class.getDeclaredField("wrongCountCache");
        f.setAccessible(true);
        Map<UUID, Map<String, Integer>> cache =
                (Map<UUID, Map<String, Integer>>) f.get(store);
        cache.computeIfAbsent(PLAYER, k -> new ConcurrentHashMap<>()).put(word, count);
    }

    // ==================================================================
    // 组 1 —— 缺陷本体
    // ==================================================================

    @Test
    @DisplayName("缓存里是新值 → 返回新值（不是 DifficultWord 携带的旧快照）")
    void returnsLiveCountNotSnapshot() throws Exception {
        // 场景：词加进错题本时是 3 次，玩家后来又答错了 2 次 → 缓存里是 5
        seedWrongCount("apple", 5);

        int shown = store.getWrongCountIfCached(PLAYER, "apple", 3);

        assertEquals(5, shown,
                "必须返回活缓存的 5，而不是快照的 3。若返回 3，界面会一直显示"
                        + "「错了 3 次」，而玩家明明已经错了 5 次 —— 要重进服才刷新。");
    }

    @Test
    @DisplayName("大小写不敏感：缓存存小写键，查询用大写也能命中")
    void caseInsensitive() throws Exception {
        seedWrongCount("apple", 5);

        assertEquals(5, store.getWrongCountIfCached(PLAYER, "APPLE", 3),
                "word 参数应被 toLowerCase 后再查 —— 与 incrementWrongCount 的写入键同源。");
    }

    // ==================================================================
    // 组 2 —— 回落（最容易写错的一条）
    // ==================================================================

    @Test
    @DisplayName("缓存没有该词 → 回落快照值，不是返回 0")
    void fallsBackToSnapshotWhenWordMissing() throws Exception {
        seedWrongCount("banana", 2); // 缓存里有别的词，但没有 apple

        int shown = store.getWrongCountIfCached(PLAYER, "apple", 3);

        assertEquals(3, shown,
                "缓存没有 apple 时必须回落快照的 3。若返回 0，界面会显示「错了 0 次」——"
                        + " 那比显示旧值更离谱（玩家会以为这个词没进错题本）。");
    }

    @Test
    @DisplayName("缓存里完全没有该玩家 → 回落快照值，且不 NPE")
    void fallsBackWhenPlayerNotCached() {
        int shown = store.getWrongCountIfCached(PLAYER, "apple", 7);

        assertEquals(7, shown, "玩家不在缓存里时应回落快照值 7，不能 NPE 也不能返回 0");
    }

    // ==================================================================
    // 组 3 —— 不触发载入（这是选它而不选 getWrongCount 的理由）
    // ==================================================================

    @Test
    @DisplayName("缓存未命中时不触发 loadDifficultWordsFromDB（GUI 在主线程渲染）")
    void doesNotTriggerLoad() {
        // 若该方法内部调了 loadDifficultWordsFromDB，就会走到 databaseManager.getConnection()
        // —— mock 未铺桩会返回 null 并抛 NPE。本用例断言「什么都不碰」。
        int shown = store.getWrongCountIfCached(PLAYER, "apple", 4);

        assertEquals(4, shown, "只读缓存，不应触发任何 DB 访问");
        org.mockito.Mockito.verifyNoInteractions(databaseManager);
    }

    // ==================================================================
    // 组 4 —— 边界
    // ==================================================================

    @Test
    @DisplayName("fallback 为 0 时也正常（未进错题本的词）")
    void zeroFallbackWorks() throws Exception {
        assertEquals(0, store.getWrongCountIfCached(PLAYER, "apple", 0),
                "fallback=0 时返回 0，不应被当成「未命中所以要特殊处理」");
    }

    @Test
    @DisplayName("缓存值为 0 时返回 0，不误判为未命中")
    void cachedZeroIsNotAMiss() throws Exception {
        seedWrongCount("apple", 0);

        assertEquals(0, store.getWrongCountIfCached(PLAYER, "apple", 99),
                "缓存里明确是 0 时必须返回 0。若把 0 当成「没有」而回落 99，"
                        + "就会显示一个从未发生过的错次数 —— Map.get 返回 null 才代表没有，"
                        + "Integer 0 是合法值。");
    }
}
