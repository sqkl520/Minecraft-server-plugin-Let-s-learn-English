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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R13：离线玩家的展示读缓存必须能被回收。
 *
 * <p><b>缺陷本体</b>：成就墙 / 导出 / 报表这些展示路径可以对<b>离线玩家</b>调
 * {@code getPlayerStats}（{@code AchievementManager.getProgressSnapshot} 的 javadoc
 * 自己写着「targetId 可离线」）。cache miss → {@code loadPlayerData} 把这个玩家的
 * 十几张表全灌进内存。而清理只发生在 {@code unloadPlayerData}（quit）——
 * <b>离线玩家不会 quit</b>，所以每看一个离线玩家主页就永久多一份缓存。
 *
 * <p><b>本类锁三件事</b>：
 * <ol>
 *   <li>离线玩家的缓存确实被回收（否则缺陷还在）</li>
 *   <li>在线玩家的缓存<b>不</b>被回收（否则正常玩家的数据会反复重载）</li>
 *   <li>有脏标记的玩家<b>不</b>被回收（否则会丢未落盘的写入）</li>
 * </ol>
 * 第 3 条是最容易写错的：只看「在不在线」就清，会连带清掉「刚改完还没来得及落盘」的玩家。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("R13：离线玩家展示读缓存回收")
class OfflinePlayerCacheEvictionTest {

    @Mock
    private LetsLearnEnglish plugin;

    @Mock
    private DatabaseManager databaseManager;

    private PlayerDataStore store;

    private static final UUID ONLINE = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-00000000a002");
    private static final UUID DIRTY_OFFLINE = UUID.fromString("00000000-0000-0000-0000-00000000a003");

    @BeforeEach
    void setUp() {
        store = new PlayerDataStore(plugin, databaseManager);
    }

    /** 往缓存里塞一条该玩家的条目（模拟「展示读把缓存灌满」）。 */
    @SuppressWarnings("unchecked")
    private void seedCached(UUID id) throws Exception {
        for (String name : List.of("playerStatsCache", "conversationCountCache",
                "todayAnswerCountCache", "todayAiRoundCache")) {
            Field f = PlayerDataStore.class.getDeclaredField(name);
            f.setAccessible(true);
            Map<UUID, ?> map = (Map<UUID, ?>) f.get(store);
            // 值类型不重要，这里只验证「键被移除」
            ((Map<UUID, Object>) map).put(id, new Object());
        }
    }

    private boolean isCached(UUID id) throws Exception {
        Field f = PlayerDataStore.class.getDeclaredField("playerStatsCache");
        f.setAccessible(true);
        return ((Map<?, ?>) f.get(store)).containsKey(id);
    }

    // ==================================================================
    // 组 1 —— 离线回收（缺陷本体）
    // ==================================================================

    @Test
    @DisplayName("离线玩家的缓存被回收（否则每看一个主页就永久多一份）")
    void offlineCachesAreEvicted() throws Exception {
        seedCached(OFFLINE);
        assertTrue(isCached(OFFLINE), "前置：缓存里要有该玩家");

        int evicted = store.evictOfflinePlayerCaches(Set.of(ONLINE));

        assertEquals(1, evicted, "应回收 1 个离线玩家");
        assertFalse(isCached(OFFLINE),
                "离线玩家的缓存必须被回收。若不回收，展示路径每看一个离线玩家主页就"
                        + "永久多十几张表的缓存，而 quit 永远轮不到他们 —— 整服玩家池越大增长越快。");
    }

    // ==================================================================
    // 组 2 —— 在线不回收（防过度修复）
    // ==================================================================

    @Test
    @DisplayName("在线玩家的缓存绝不回收（否则每轮 auto-save 都逼他们重载）")
    void onlineCachesAreKept() throws Exception {
        seedCached(ONLINE);
        seedCached(OFFLINE);

        store.evictOfflinePlayerCaches(Set.of(ONLINE));

        assertTrue(isCached(ONLINE),
                "在线玩家的缓存必须保留。若连在线玩家一起清，每轮 auto-save 都会把他们的"
                        + "数据从 DB 重载一遍 —— 这是把内存问题换成了 IO 风暴。");
        assertFalse(isCached(OFFLINE), "离线玩家仍应被回收");
    }

    // ==================================================================
    // 组 3 —— 脏标记保护（最容易写错的一条）
    // ==================================================================

    @Test
    @DisplayName("有脏标记的离线玩家不回收 —— 宁可少收一个，也不丢一次未落盘的写入")
    void dirtyOfflinePlayerIsNotEvicted() throws Exception {
        seedCached(DIRTY_OFFLINE);
        // 模拟「刚改完还没落盘」：dirtyPlayers 里有他
        Field f = PlayerDataStore.class.getDeclaredField("dirtyPlayers");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<UUID> dirty = (Set<UUID>) f.get(store);
        dirty.add(DIRTY_OFFLINE);

        int evicted = store.evictOfflinePlayerCaches(Set.of());

        assertEquals(0, evicted, "有脏标记的玩家不该被回收");
        assertTrue(isCached(DIRTY_OFFLINE),
                "有未落盘改动的玩家必须跳过。若清掉，那些改动就永久丢了 —— 而 "
                        + "saveDirty 的顺序是先落盘再回收，本用例直接调回收方法，"
                        + "模拟的正是「落盘失败/尚未执行」的窗口。");
    }

    @Test
    @DisplayName("dirtyWords 里有词的离线玩家同样不回收")
    void dirtyWordsAlsoProtects() throws Exception {
        seedCached(DIRTY_OFFLINE);
        Field f = PlayerDataStore.class.getDeclaredField("dirtyWords");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Set<String>> dirty = (Map<UUID, Set<String>>) f.get(store);
        dirty.put(DIRTY_OFFLINE, ConcurrentHashMap.newKeySet());

        int evicted = store.evictOfflinePlayerCaches(Set.of());

        assertEquals(0, evicted, "dirtyWords 里有条目也算脏，不该回收");
        assertTrue(isCached(DIRTY_OFFLINE), "同上");
    }

    // ==================================================================
    // 组 4 —— 边界
    // ==================================================================

    @Test
    @DisplayName("onlineIds 为 null 时不抛，且全部视为离线")
    void nullOnlineIdsTreatedAsAllOffline() throws Exception {
        seedCached(OFFLINE);

        int evicted = store.evictOfflinePlayerCaches(null);

        assertEquals(1, evicted, "null 应等同于「没有人在线」而不是 NPE");
    }

    @Test
    @DisplayName("缓存为空时返回 0，不抛")
    void emptyCacheReturnsZero() {
        assertEquals(0, store.evictOfflinePlayerCaches(Set.of()), "空缓存应返回 0");
        assertEquals(0, store.evictOfflinePlayerCaches(Set.of(ONLINE)), "同上");
    }

    @Test
    @DisplayName("反复调用是幂等的 —— 第二次回收 0 个")
    void evictionIsIdempotent() throws Exception {
        seedCached(OFFLINE);

        assertEquals(1, store.evictOfflinePlayerCaches(Set.of()), "第一次应回收 1 个");
        assertEquals(0, store.evictOfflinePlayerCaches(Set.of()), "第二次应回收 0 个");
    }
}
