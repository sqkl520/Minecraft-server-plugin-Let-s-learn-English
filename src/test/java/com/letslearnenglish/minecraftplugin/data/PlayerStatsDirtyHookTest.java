package com.letslearnenglish.minecraftplugin.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;

/**
 * R7 核心不变量验收：{@link PlayerDataStore.PlayerStats} 的<b>任何</b>变更都必须标脏。
 *
 * <p><b>为什么这条不变量值得单独一个测试类</b>：{@code WordManagerImpl.endSession}
 * 直接调用 {@code playerDataStore.getPlayerStats(id).addScore(...)}，<b>绕过</b>
 * {@link PlayerDataStore} 的所有方法。因此标脏不能靠"枚举所有外部写入点"，只能挂在
 * {@code PlayerStats} 实例自身（{@code setDirtyHook}）。若将来有人新增一个 mutator
 * 却忘了调 {@code onMutated()}，{@code total_score} 将永不落库、排行榜全错，而
 * 现有其它测试<b>一个都不会失败</b> —— 本类就是那道闸。
 *
 * <p>结构：{@link HookContract} 是纯 POJO 契约（无 mock），
 * {@link StoreIntegration} 是端到端落库验收（带 mock plugin / DatabaseManager）。
 */
class PlayerStatsDirtyHookTest {

    /**
     * 纯 POJO 契约：不触碰 Bukkit、不触碰数据库，只验证 hook 的调用语义。
     *
     * <p>代码内联在本包，因此可以直接调用包私有的
     * {@link PlayerDataStore.PlayerStats#setDirtyHook(Runnable)} —— 这正是把测试放在
     * {@code com.letslearnenglish.minecraftplugin.data} 包下的原因。
     */
    @Nested
    @DisplayName("PlayerStats 脏标记契约（纯 POJO）")
    class HookContract {

        /**
         * 用例 1a（<b>FIX-1 新增：逐方法断言</b>）：<b>每一个</b> mutator 各自恰好触发一次 hook。
         *
         * <p><b>对应红队发现</b>：原 {@link #allMutatorsInvokeHook()} 只断言"6 个方法调完
         * 总数为 6"。红队实测把 {@code addCorrect} 改成调两次 hook、同时把 {@code setScore}
         * 的 hook 整个删掉，总数仍是 6，测试<b>全绿</b> —— {@code setScore} 漏标脏这条
         * 真实缺陷（total_score 永不落库）完全不可见。
         *
         * <p>拆成参数化后，每个方法在<b>全新实例</b>上单独执行，计数只反映它自己：
         * 任何一个 mutator 漏调或多调都会精确地红在那一条 {@code DisplayName} 上。
         * 新增 mutator 时必须同步加进 {@link #mutators()}，否则它不受保护。
         */
        @ParameterizedTest(name = "{0} 恰好触发 1 次")
        @MethodSource("mutators")
        @DisplayName("每个 mutator 单独恰好触发一次 dirtyHook（防漏标/重复标被总数掩盖）")
        void eachMutatorInvokesHookExactlyOnce(String name,
                Consumer<PlayerDataStore.PlayerStats> mutator) {
            PlayerDataStore.PlayerStats stats = new PlayerDataStore.PlayerStats();
            AtomicInteger calls = new AtomicInteger();
            stats.setDirtyHook(calls::incrementAndGet);

            mutator.accept(stats);

            assertEquals(1, calls.get(), name + " 必须恰好触发一次 dirtyHook");
        }

        /** 6 个 mutator 的唯一清单；{@link #eachMutatorInvokesHookExactlyOnce} 逐条消费。 */
        static Stream<Arguments> mutators() {
            return Stream.of(
                    Arguments.of("addCorrect", (Consumer<PlayerDataStore.PlayerStats>) s -> s.addCorrect(1)),
                    Arguments.of("addIncorrect", (Consumer<PlayerDataStore.PlayerStats>) s -> s.addIncorrect(1)),
                    Arguments.of("incrementSessions", (Consumer<PlayerDataStore.PlayerStats>) s -> s.incrementSessions()),
                    Arguments.of("addScore", (Consumer<PlayerDataStore.PlayerStats>) s -> s.addScore(1L)),
                    Arguments.of("setSessions", (Consumer<PlayerDataStore.PlayerStats>) s -> s.setSessions(5)),
                    Arguments.of("setScore", (Consumer<PlayerDataStore.PlayerStats>) s -> s.setScore(100L)));
        }

        /**
         * 用例 1a-2（<b>FIX-5 新增：清单覆盖度闸门</b>）：{@link #mutators()} 必须与
         * {@code PlayerStats} 的<b>全部 public 非 getter 方法</b>逐一对应，且条数恰好 6。
         *
         * <p><b>对应红队发现（RT-G3 P2）</b>：红队实测——从 {@code mutators()} 里删掉任意
         * 一条，测试总数从 13 落到 12，<b>12/12 全绿</b>；新增一个不调 {@code onMutated()}
         * 的 mutator，同样全绿。原因是参数化用例的保护面<b>完全由这份清单自己定义</b>：
         * 清单缩水 = 保护面缩水，而「清单短了」这件事本身没有任何断言。
         *
         * <p>修法：用反射枚举 {@code PlayerStats.getDeclaredMethods()} 里 public、非
         * {@code get} 前缀、参数 ≤1 的方法（{@code setDirtyHook} 是包私有，天然排除；
         * {@code Object} 的 {@code toString/hashCode} 是继承而非 declared，也不会出现在
         * 这里），与清单中的名字集合做<b>双向</b>相等断言。于是两条退化路径都变红：
         * <ul>
         *   <li>清单被删一条 → {@code covered} 缺项 → 不等；</li>
         *   <li>新增 mutator 但没进清单（也没调 hook）→ {@code declared} 多一项 → 不等。</li>
         * </ul>
         * 条数断言是第二道闸（集合相等已隐含条数，但独立的数字让失败信息直接给出
         * "应为 6"，不必读者自己数）。
         */
        @Test
        @DisplayName("mutator 清单必须覆盖 PlayerStats 全部 public 变更方法（防清单静默缩水）")
        void mutatorInventoryCoversEveryPublicMutator() {
            // 不带 get 前缀的 public declared 方法 = 全部变更方法的源码真值。
            // 刻意不过滤参数个数：`setDirtyHook(int)` 之类"多参数 mutator"同样必须入清单
            // （{@code setDirtyHook} 本身是包私有，已被 Modifier.isPublic 排除）。
            // synthetic/bridge 必须排除，否则将来某个泛型签名会凭空多出一项。
            Set<String> declared = Arrays.stream(PlayerDataStore.PlayerStats.class.getDeclaredMethods())
                    .filter(m -> Modifier.isPublic(m.getModifiers()))
                    .filter(m -> !m.isSynthetic() && !m.isBridge())
                    .filter(m -> !m.getName().startsWith("get"))
                    .map(Method::getName)
                    .collect(Collectors.toSet());

            Set<String> covered = Set.of("addCorrect", "addIncorrect", "incrementSessions",
                    "addScore", "setSessions", "setScore");

            assertEquals(6, mutators().count(),
                    "mutator 清单必须覆盖 PlayerStats 的全部 6 个变更方法（少一条 = 该方法的漏标脏不再受保护）");
            assertEquals(declared, covered,
                    "反射枚举出的 public 变更方法必须与 mutator 清单完全一致："
                            + "清单少一条则该方法无保护；源码新增 mutator 未入清单则它会绕过逐方法验收");
        }

        /**
         * 用例 1b：6 个变更方法各调用一次 → hook 恰好触发 6 次（整体冒烟）。
         *
         * <p>源码核对（{@code PlayerDataStore.java} 内 {@code public static class PlayerStats}）
         * 的完整 mutator 清单共 <b>6</b> 个，全部以 {@code onMutated()} 结尾：
         * <ol>
         *   <li>{@code addCorrect(int)}</li>
         *   <li>{@code addIncorrect(int)}</li>
         *   <li>{@code incrementSessions()}</li>
         *   <li>{@code addScore(long)}</li>
         *   <li>{@code setSessions(int)}</li>
         *   <li>{@code setScore(long)}</li>
         * </ol>
         * 不存在 {@code setCorrect}/{@code setIncorrect}。新增 mutator 必须同步更新
         * 本用例的调用清单与期望次数 —— 这正是本测试的意图（编译期/断言期双重提醒）。
         *
         * <p><b>FIX-1 说明</b>：本用例保留为"全部一起调"的冒烟，但<b>不承担</b>逐方法保护
         * 职责 —— 它只断言总数，无法区分"某个方法调两次、另一个漏调"。
         * 真正的逐方法保护在 {@link #eachMutatorInvokesHookExactlyOnce}。
         */
        @Test
        void allMutatorsInvokeHook() {
            PlayerDataStore.PlayerStats stats = new PlayerDataStore.PlayerStats();
            AtomicInteger calls = new AtomicInteger();
            stats.setDirtyHook(calls::incrementAndGet);

            stats.addCorrect(1);
            stats.addIncorrect(1);
            stats.incrementSessions();
            stats.addScore(1L);
            stats.setSessions(5);
            stats.setScore(100L);

            assertEquals(6, calls.get(), "每个变更方法都必须触发 dirtyHook");
        }

        /**
         * 用例 2：读方法绝不触发 hook。
         *
         * <p>若 getter 误调 {@code onMutated()}，周期保存会把只读的榜单/成就查询
         * 变成"每读一次就标脏"，退化为每轮全量写库。这里的 5 个 getter 是
         * {@code PlayerStats} 的全部读方法。
         */
        @Test
        void gettersNeverInvokeHook() {
            PlayerDataStore.PlayerStats stats = new PlayerDataStore.PlayerStats();
            AtomicInteger calls = new AtomicInteger();
            stats.setDirtyHook(calls::incrementAndGet);

            stats.getTotalCorrect();
            stats.getTotalIncorrect();
            stats.getTotalSessions();
            stats.getTotalScore();
            stats.getTotalWords();

            assertEquals(0, calls.get(), "getter 不得触发 dirtyHook");
        }

        /**
         * 用例 3：未挂 hook（读-only 临时实例）时变更不得抛 NPE。
         *
         * <p>{@code loadPlayerData} 重建期间会先 new 一个无 hook 的实例并调用
         * {@code addCorrect}/{@code addIncorrect}/{@code setSessions}/{@code setScore}
         * 做重建 —— hook 为 null 必须是合法的 no-op，否则玩家进服即崩。
         */
        @Test
        void nullHookIsSafe() {
            PlayerDataStore.PlayerStats stats = new PlayerDataStore.PlayerStats();

            assertDoesNotThrow(() -> stats.addScore(10L), "dirtyHook 为 null 时必须安全");
            assertEquals(10L, stats.getTotalScore(), "值仍应正常累加");
        }

        /**
         * 用例 4：hook 可被替换，且<b>只有最后一个</b>生效。
         *
         * <p>{@code setDirtyHook} 是赋值而非链式追加：{@code loadPlayerData} 与
         * {@code statsFor} 两处挂接点可能先后作用于同一逻辑玩家（重载/缓存重建），
         * 替换语义保证旧实例的 hook 不会继续标脏（旧实例已不在缓存中，
         * 继续标脏只会造成无意义的全量轮次）。
         */
        @Test
        void setDirtyHookCanBeReplaced() {
            PlayerDataStore.PlayerStats stats = new PlayerDataStore.PlayerStats();
            AtomicInteger hookACalls = new AtomicInteger();
            AtomicInteger hookBCalls = new AtomicInteger();

            stats.setDirtyHook(hookACalls::incrementAndGet);
            stats.setDirtyHook(hookBCalls::incrementAndGet);

            stats.addScore(1L);

            assertEquals(0, hookACalls.get(), "被替换的 hookA 不得再被调用");
            assertEquals(1, hookBCalls.get(), "当前 hookB 必须被调用一次");
        }

        /**
         * 用例 5：{@code getTotalWords()} == 只答对的词数（M10 口径）。
         *
         * <p>M10 明确规定"已学单词数只计答对"：答错的词进错题本，不算学会。
         * {@code getTotalWords()} 直接返回 {@code totalCorrect}，因此
         * {@code addCorrect(3) + addIncorrect(5)} 后必须是 3 而不是 8。
         * {@code load} 重建与 {@code save} 落盘两侧都走此方法，口径天然一致 ——
         * 如果有人把实现改成 {@code totalCorrect + totalIncorrect}，
         * 落库的 {@code total_words_learned} 会立刻虚高，成就与榜单跟着错。
         */
        @Test
        void getTotalWordsEqualsTotalCorrect() {
            PlayerDataStore.PlayerStats stats = new PlayerDataStore.PlayerStats();

            stats.addCorrect(3);
            stats.addIncorrect(5);

            assertEquals(3, stats.getTotalWords(), "M10：已学单词数只计答对，不含答错");
            assertEquals(3, stats.getTotalCorrect(), "totalCorrect 应为 3");
            assertEquals(5, stats.getTotalIncorrect(), "totalIncorrect 应为 5（进错题本，不算学会）");
        }
    }

    /**
     * 构造 {@code updateWordProgress} 需要的结算结果：每词 1 次答对。
     *
     * <p>FIX-5：{@code statsForEstablishedInstanceAlsoHooks} 需要一个"不经 loadPlayerData
     * 就能建立 stats 实例"的入口，最短路径就是 {@code updateWordProgress} —— 它在内部
     * 调 {@code updatePlayerStats → statsFor}。本 helper 只造最小的合法结算 map。
     */
    private static Map<String, com.letslearnenglish.minecraftplugin.core.word.LearningSession.WordResult>
            results(String... words) {
        Map<String, com.letslearnenglish.minecraftplugin.core.word.LearningSession.WordResult> map =
                new HashMap<>();
        for (String word : words) {
            com.letslearnenglish.minecraftplugin.core.word.Word w =
                    new com.letslearnenglish.minecraftplugin.core.word.Word(
                            word, word, "n", "", "", List.of(), List.of(), "easy", 1);
            com.letslearnenglish.minecraftplugin.core.word.LearningSession.WordResult r =
                    new com.letslearnenglish.minecraftplugin.core.word.LearningSession.WordResult(w);
            r.incrementCorrect();
            map.put(word, r);
        }
        return map;
    }

    /**
     * 端到端：外部直接改 {@code PlayerStats} → 周期保存真的把 {@code total_score}
     * 写进 {@code player_data} 行。
     */
    @Nested
    @DisplayName("PlayerDataStore 落库集成（外部直接改 PlayerStats）")
    class StoreIntegration {

        private LetsLearnEnglish plugin;
        private DatabaseManager databaseManager;
        private Connection connection;
        private PreparedStatement ps;
        private ResultSet rs;
        private PlayerDataStore store;

        @BeforeEach
        void setUp() throws Exception {
            plugin = mock(LetsLearnEnglish.class);
            databaseManager = mock(DatabaseManager.class);
            connection = mock(Connection.class);
            ps = mock(PreparedStatement.class);
            rs = mock(ResultSet.class);

            when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
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
            when(rs.next()).thenReturn(false);

            store = new PlayerDataStore(plugin, databaseManager);
        }

        /**
         * 用例 8（<b>FIX-5 新增：statsFor 挂接点单独验收</b>）：由 {@code statsFor} 建立的
         * 实例也必须挂着 dirtyHook —— 兑现后外部直接 {@code addScore} 仍要落库。
         *
         * <p><b>对应红队发现（RT-G3 P1）</b>：红队实测删掉 {@code statsFor}（约 :1270）里的
         * {@code s.setDirtyHook(() -> markDirty(k))}，159 个用例<b>全绿</b>。原因是既有的
         * {@link #externalPlayerStatsMutationMarksStoreDirty} 先调用 {@code getPlayerStats}，
         * 缓存未命中 → {@code loadPlayerData} 自己把实例 put 进缓存并在 :630 挂了 hook，
         * {@code statsFor} 里的 {@code computeIfAbsent} 于是拿到已有实例、那行 hook 永不执行。
         * 两个挂接点被同一段代码覆盖，{@code statsFor} 的那一行实际是"死代码也可全绿"。
         *
         * <p><b>statsFor 的真实可达路径</b>：{@code updateWordProgress} →
         * {@code updatePlayerStats} → {@code statsFor}，这条链<b>不经过</b>
         * {@code loadPlayerData}，因此实例首次建立只可能由 {@code statsFor} 完成 ——
         * 这正是本用例的入口。随后 {@code getPlayerStats} 命中缓存（不再 load），
         * 所以最终 {@code addScore} 能被标脏的唯一来源就是 {@code statsFor} 挂的 hook。
         *
         * <p>四步（红队给出的已验证探针）：
         * <ol>
         *   <li>{@code updateWordProgress} —— 让实例由 {@code statsFor} 建立；</li>
         *   <li>{@code saveDirty} —— 兑现标记，计数归零；</li>
         *   <li>{@code getPlayerStats(id).addScore(99)} —— 缓存命中，纯外部直改；</li>
         *   <li>{@code saveDirty} —— 必须写出 player_data 行且 {@code total_score}=99。</li>
         * </ol>
         * 第 3 步后额外断言"零 prepareStatement"，把"缓存命中"钉成结构事实：
         * 若某天 {@code getPlayerStats} 改回缓存未命中就 load，本断言会先红，
         * 提示本用例的隔离前提已失效（而不是让 statsFor 缺席被 load 的 hook 掩盖）。
         *
         * <p>列序依据 {@code buildPlayerDataUpsertSql}：
         * {@code (uuid, player_name, total_words_learned, total_sessions, total_score, updated_at)}
         * → 第 <b>5</b> 位是 {@code total_score}；本用例 1 词 1 局 → words=1、sessions=1。
         */
        @Test
        @DisplayName("statsFor 建立的实例也挂 hook：兑现后外部 addScore(99) 仍必须落库")
        void statsForEstablishedInstanceAlsoHooks() throws Exception {
            UUID playerId = UUID.randomUUID();

            // 步骤 1：只走 updateWordProgress 链（updatePlayerStats → statsFor）。
            // 这条链不经过 loadPlayerData，实例的 hook 只能来自 statsFor。
            store.updateWordProgress(playerId, results("water"));

            // 步骤 2：兑现第一次会话的标记，让 dirtyPlayers / dirtyWords 归零。
            store.saveDirty(playerId);
            clearInvocations(ps, connection);

            // 步骤 3：缓存命中取实例 → 完全绕过 PlayerDataStore 的外部直改。
            PlayerDataStore.PlayerStats stats = store.getPlayerStats(playerId);
            stats.addScore(99L);

            // 隔离前提：getPlayerStats 必须是缓存命中（statsFor 已在步骤 1 put 过）。
            // 若这里发生 load，loadPlayerData 的 :630 hook 会替 statsFor 背锅。
            verify(connection, never()).prepareStatement(anyString());

            // 步骤 4：周期保存必须兑现这次外部直改。
            store.saveDirty(playerId);

            verify(ps, times(1)).setLong(5, 99L);
            verify(ps, times(1)).setString(1, playerId.toString());
            verify(ps, times(1)).setInt(3, 1); // total_words_learned：1 个词答对 1 次
            verify(ps, times(1)).setInt(4, 1); // total_sessions：1 局
            verify(ps, times(1)).executeUpdate();
        }

        /**
         * 用例 6：端到端验收 —— {@code store.getPlayerStats(id).addScore(42)} 之后
         * {@code saveDirty(id)} 必须写出一行 {@code player_data} 且
         * {@code total_score} 为 42，同时<b>不写任何词行</b>。
         *
         * <p>这是两处 hook 挂接点的<b>合并验收</b>：
         * <ul>
         *   <li>{@code loadPlayerData}（约 :630）—— 首次加载时给新建的
         *       {@code PlayerStats} 挂 {@code () -> markDirty(playerId)}，且挂接在
         *       {@code put} 进缓存<b>之前</b>完成（否则入缓存后立刻被外部线程拿去改，
         *       那一改就漏标）；</li>
         *   <li>{@code statsFor}（约 :1267）—— 缓存未命中时 {@code computeIfAbsent}
         *       新建实例并挂 hook，覆盖"没见过 DB 就直接被取统计"的路径。</li>
         * </ul>
         * 本用例的调用链先走 {@code loadPlayerData}（缓存未命中），因此同时验证了
         * "加载路径挂上的 hook 足以让后续外部直接变更落库"。
         *
         * <p>参数顺序依据 {@code buildPlayerDataUpsertSql}：
         * {@code (uuid, player_name, total_words_learned, total_sessions, total_score, updated_at)}
         * → 第 <b>5</b> 个是 {@code total_score}。
         *
         * <p>为什么先"预热"：{@code getPlayerStats} 缓存未命中时会先 {@code loadPlayerData}
         * 再 {@code statsFor} —— 不预热就直接断言，会把加载期的 SQL 也算进统计。
         * 这里显式先取一次实例完成预热，再执行被验收的那一次外部变更。
         */
        @Test
        void externalPlayerStatsMutationMarksStoreDirty() throws Exception {
            UUID playerId = UUID.randomUUID();

            // 预热：触发 loadPlayerData 建缓存（此时 hook 已由 loadPlayerData 挂上）。
            store.getPlayerStats(playerId);

            // 预热期的 loadPlayerData 自己也会 prepareStatement 两条 SELECT（同样含
            // lle_word_progress / lle_player_data 表名）。清掉交互记录，让下面的断言
            // 只覆盖"这一次外部变更 → 这一次 saveDirty"的写路径（stubbing 保留）。
            clearInvocations(ps, connection);

            // 模拟 WordManagerImpl.endSession 的外部直改：完全绕过 PlayerDataStore 的方法。
            store.getPlayerStats(playerId).addScore(42L);

            // 周期保存：只应写 player_data 行，不写任何词行。
            store.saveDirty(playerId);

            verify(ps, times(1)).setLong(5, 42L);
            // FIX-1：原用例只钉了 setLong(5)，total_words_learned(3) 与
            // total_sessions(4) 互换时 0 红。这里补全前 5 列（第 6 列是时间戳）。
            // 列序依据 buildPlayerDataUpsertSql：
            //   (uuid, player_name, total_words_learned, total_sessions, total_score, updated_at)
            // 本用例空表加载 → words=0、sessions=0；两个 0 无法互检，因此下面另有
            // playerDataAllColumnsArePinned 用互不相同的值逐列钉死。
            verify(ps, times(1)).setString(1, playerId.toString());
            verify(ps, times(1)).setInt(3, 0);
            verify(ps, times(1)).setInt(4, 0);
            verify(ps, times(1)).executeUpdate();
            verify(ps, never()).executeBatch();
            verify(ps, never()).addBatch();

            // saveDirty 走增量路径（n=1，未到 12 轮全量兜底）。savePlayerDataInternal
            // 会准备两条语句：word_progress（buildUpsertSql）与 player_data
            // （buildPlayerDataUpsertSql）。本用例只断言"写了哪张表 + player_data 写了
            // 几行"，不依赖驱动对空 batch 的行为 —— 无脏词时源码本就不会调 executeBatch。
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(connection, atLeastOnce()).prepareStatement(sqlCaptor.capture());

            int wordProgressStatements = 0;
            int playerDataStatements = 0;
            for (String sql : sqlCaptor.getAllValues()) {
                if (sql.contains("lle_word_progress")) {
                    wordProgressStatements++;
                }
                if (sql.contains("lle_player_data")) {
                    playerDataStatements++;
                }
            }
            assertEquals(1, playerDataStatements, "应恰好准备一条 player_data 写入语句");
            assertEquals(1, wordProgressStatements, "词表语句只应准备一次（本用例无脏词）");
        }

        /**
         * 用例 7（<b>FIX-1 新增</b>）：{@code player_data} 六列逐位钉死，且三列数值互不相同。
         *
         * <p><b>对应红队发现</b>：红队实测把 {@code total_words_learned} 与
         * {@code total_sessions} 互换，0 个用例变红 —— 用例 6 虽然断言了
         * {@code setLong(5, 42)}，但 words / sessions 都是空表加载出来的 0，
         * 两个 0 互换后无法区分。
         *
         * <p>本用例构造 3 / 2 / 42 三个两两不同的值：任何两位互换都会立刻变红。
         * 列序依据 {@code buildPlayerDataUpsertSql}：
         * {@code (uuid, player_name, total_words_learned, total_sessions, total_score, updated_at)}。
         *
         * <p>为什么必须把两条 SQL 分派到不同的 mock：{@code setUp} 里
         * {@code prepareStatement(anyString())} 对 {@code word_progress} 与
         * {@code player_data} 返回同一个 {@code ps}，{@code setInt(4, x)} 会同时收到
         * "词行 correct_count" 与 "player_data total_sessions"，列号断言互相污染。
         */
        @Test
        void playerDataAllColumnsArePinned() throws Exception {
            PreparedStatement wordStmt = mock(PreparedStatement.class);
            PreparedStatement statsStmt = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenAnswer(inv ->
                    ((String) inv.getArgument(0)).contains("word_progress") ? wordStmt : statsStmt);
            when(wordStmt.executeBatch()).thenReturn(new int[0]);
            when(statsStmt.executeUpdate()).thenReturn(1);

            UUID playerId = UUID.randomUUID();
            store.cachePlayerName(playerId, "Alex");

            // 3 个词各答对 1 次 → getTotalWords() = totalCorrect = 3
            java.util.Map<String, com.letslearnenglish.minecraftplugin.core.word.LearningSession.WordResult> results =
                    new java.util.HashMap<>();
            for (String w : new String[] {"alpha", "beta", "gamma"}) {
                com.letslearnenglish.minecraftplugin.core.word.Word word =
                        new com.letslearnenglish.minecraftplugin.core.word.Word(
                                w, w, "n", "", "", java.util.List.of(), java.util.List.of(), "easy", 1);
                com.letslearnenglish.minecraftplugin.core.word.LearningSession.WordResult r =
                        new com.letslearnenglish.minecraftplugin.core.word.LearningSession.WordResult(word);
                r.incrementCorrect();
                results.put(w, r);
            }
            store.updateWordProgress(playerId, results);

            PlayerDataStore.PlayerStats stats = store.getPlayerStats(playerId);
            stats.setSessions(2);
            stats.addScore(42L);

            clearInvocations(statsStmt, wordStmt, connection);
            store.saveDirty(playerId);

            verify(statsStmt, times(1)).setString(1, playerId.toString());
            verify(statsStmt, times(1)).setString(2, "Alex");
            verify(statsStmt, times(1)).setInt(3, 3);    // total_words_learned
            verify(statsStmt, times(1)).setInt(4, 2);    // total_sessions
            verify(statsStmt, times(1)).setLong(5, 42L); // total_score
            verify(statsStmt, times(1)).setLong(eq(6),
                    org.mockito.ArgumentMatchers.longThat(ts -> ts > 0L)); // updated_at
            verify(statsStmt, times(1)).executeUpdate();

            // 防串位（红队变异）：words 与 sessions 互换后这两条必定失败。
            verify(statsStmt, never()).setInt(3, 2);
            verify(statsStmt, never()).setInt(4, 3);
        }
    }
}
