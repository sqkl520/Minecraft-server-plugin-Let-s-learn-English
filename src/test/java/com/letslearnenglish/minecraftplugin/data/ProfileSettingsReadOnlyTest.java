package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L22：{@link PlayerDataStore#getProfileSettingsReadOnly(UUID)} 的 cache-miss 语义。
 *
 * <p><b>为什么需要这个类</b>：{@code profileSettingsCache} 只覆盖<b>在线</b>玩家
 * （{@code PlayerJoinListener:29} 进服预热、{@code :124} 退出时删键）。旧实现在 miss 时
 * 返回 {@code new ProfileSettings()}，而该构造函数的默认值是 showResults=true /
 * showInSquare=true —— 于是「离线」被读成「公开」：隐身与结果开关在榜单头像点击、
 * 广场渲染、PAPI 占位符、动态流点击四处同时失效，直接违反 R-38 / R4-M2 的四层隐私模型。
 *
 * <p>这类缺陷不会有任何异常、日志或编译告警，只能靠断言「miss 时必须收紧」来钉住。
 * 同时要保证另一面：<b>本人路径不能被误伤</b>（cache hit 原值返回；写路径、
 * 音效/提醒偏好不被无关地收紧）。
 *
 * <p><b>纯 JVM 可跑</b>：{@code PlayerDataStore} 的构造只做字段初始化（全部
 * ConcurrentHashMap），不碰 DB；本类也不触发任何 {@code ItemStack} 构造。
 */
@ExtendWith(MockitoExtension.class)
class ProfileSettingsReadOnlyTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Mock
    private LetsLearnEnglish plugin;

    private PlayerDataStore store;

    @BeforeEach
    void setUp() {
        store = new PlayerDataStore(plugin, mock(DatabaseManager.class));
    }

    // ------------------------------------------------------------------
    // A. cache miss = 隐私最严
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 A —— cache miss 不得假设公开")
    class CacheMiss {

        @Test
        @DisplayName("miss：showResults / showInSquare / showAnswers 一律 false（不可误公开）")
        void missCollapsesToStrictestPrivacy() {
            PlayerDataStore.ProfileSettings ps = store.getProfileSettingsReadOnly(PLAYER_ID);

            assertFalse(ps.isShowResults(),
                    "cache miss 时 showResults 必须为 false 喵。\n"
                            + "离线玩家的设置不在缓存里（quit 时 unloadProfileSettings 删键），"
                            + "回落到「新玩家默认值」（true）等于把「离线」读成「公开」——"
                            + "玩家关掉的结果开关会在榜单/广场/PAPI 四处同时失效。");
            assertFalse(ps.isShowInSquare(),
                    "cache miss 时 showInSquare 必须为 false —— 隐身开关失效是更直接的隐私事故");
            assertFalse(ps.isShowAnswers(),
                    "cache miss 时 showAnswers 必须为 false（与 D2 隐私优先默认一致）");
        }

        @Test
        @DisplayName("miss：偏好位（音效 / 提醒）保持 true —— 收紧它们会造成功能故障，不是隐私收益")
        void missKeepsPreferenceFlagsFailOpen() {
            PlayerDataStore.ProfileSettings ps = store.getProfileSettingsReadOnly(PLAYER_ID);

            assertTrue(ps.isSoundsEnabled(),
                    "音效开关 miss 时必须 fail-open：它不泄露任何信息，"
                            + "收紧会让热重载后的全体在线玩家静默失去音效反馈");
            assertTrue(ps.isRemindersEnabled(),
                    "提醒开关 miss 时必须 fail-open（同 SoundManager / ReviewScheduler 既有口径）");
        }

        @Test
        @DisplayName("miss：每次返回新实例（调用方改它不得污染缓存，也不得有跨调用共享状态）")
        void missReturnsFreshInstancePerCall() {
            PlayerDataStore.ProfileSettings first = store.getProfileSettingsReadOnly(PLAYER_ID);
            PlayerDataStore.ProfileSettings second = store.getProfileSettingsReadOnly(PLAYER_ID);

            assertNotSame(first, second, "两次 miss 必须各自拿到独立实例");
            first.setShowResults(true);
            assertFalse(second.isShowResults(),
                    "改一份 fallback 不得影响另一份（否则一个调用方的误写会全局放开隐私）");
        }

        @Test
        @DisplayName("miss：绝不写入缓存（不得污染 getActivePlayerIds 的候选集）")
        void missDoesNotPopulateCache() {
            store.getProfileSettingsReadOnly(PLAYER_ID);

            assertNull(store.getCachedProfileSettings(PLAYER_ID),
                    "只读展示路径不得在 profileSettingsCache 里建条目 —— "
                            + "L-05 的原始教训就是这个（会污染 getActivePlayerIds）");
        }

        @Test
        @DisplayName("miss：age 归 0（「未设置」），不是脏值")
        void missAgeIsZero() {
            assertEquals(0, store.getProfileSettingsReadOnly(PLAYER_ID).getAge());
        }
    }

    // ------------------------------------------------------------------
    // B. cache hit = 原值
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 B —— cache hit 原值返回")
    class CacheHit {

        @Test
        @DisplayName("hit：返回缓存里的真实设置（含玩家自己关掉的开关）")
        void hitReturnsCachedValues() {
            PlayerDataStore.ProfileSettings live = store.getProfileSettings(PLAYER_ID);
            live.setShowResults(false);
            live.setShowInSquare(false);
            live.setShowAnswers(true);
            live.setAge(15);

            PlayerDataStore.ProfileSettings read = store.getProfileSettingsReadOnly(PLAYER_ID);

            assertSame(live, read, "hit 时必须返回缓存里的同一实例（只读契约，不拷贝）");
            assertFalse(read.isShowResults());
            assertFalse(read.isShowInSquare());
            assertTrue(read.isShowAnswers());
            assertEquals(15, read.getAge());
        }

        @Test
        @DisplayName("hit：getCachedProfileSettings 返回同一实例（三态窥视的「已知」分支）")
        void peekReturnsSameInstanceOnHit() {
            PlayerDataStore.ProfileSettings live = store.getProfileSettings(PLAYER_ID);
            assertSame(live, store.getCachedProfileSettings(PLAYER_ID));
        }
    }

    // ------------------------------------------------------------------
    // C. 本人路径不受影响
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("组 C —— 本人路径不被误伤")
    class OwnProfile {

        @Test
        @DisplayName("本人（在线、join 已预热）：读到自己的真实设置，不会被 miss 语义收紧")
        void ownOnlineProfileReadsRealSettings() {
            // 模拟 join 预热：loadProfileSettings 在无行时会 putIfAbsent 一份默认值。
            PlayerDataStore.ProfileSettings mine = store.getProfileSettings(PLAYER_ID);
            mine.setShowResults(true);
            mine.setShowInSquare(true);

            PlayerDataStore.ProfileSettings read = store.getProfileSettingsReadOnly(PLAYER_ID);

            assertTrue(read.isShowResults(), "本人主页不应被隐私收紧（否则自己看不到自己的数据）");
            assertTrue(read.isShowInSquare());
        }

        @Test
        @DisplayName("写路径 getProfileSettings 仍给出「可写默认值」（showResults=true），与只读 fallback 分离")
        void writePathKeepsWritableDefaults() {
            PlayerDataStore.ProfileSettings writable = store.getProfileSettings(PLAYER_ID);

            assertTrue(writable.isShowResults(),
                    "写路径的默认值语义是「新玩家的初始设置」，必须是公开"
                            + "（否则新玩家一注册就被隐身，设置页还会显示成关闭态）");
            // 同一 UUID 命中缓存后，只读版读到的就是这份「公开」默认值 —— 与 miss 语义不矛盾：
            // miss 表示「不知道」，hit 表示「知道，而且这就是默认设置」。
            assertTrue(store.getProfileSettingsReadOnly(PLAYER_ID).isShowResults());
            assertNotSame(PlayerDataStore.ProfileSettings.unresolvedFallback(), writable,
                    "两套语义必须是不同实例，绝不能共用一份可变默认值");
        }
    }

    // ------------------------------------------------------------------
    // D. 热重载：clearProfileCache 后不得让在线玩家永久处于「未知」
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearProfileCache：在线玩家的隐私设置被立即重建（否则热重载后全体变隐身）")
    void clearProfileCacheRebuildsOnlinePlayers() {
        // loadProfileSettings 的依赖面：getTablePrefix → getConnectionLock → getConnection
        // → prepareStatement → executeQuery。这里让 DB「有这一行」，读到的是库里的真值。
        //
        // P1-2（红队验收）之后本用例<b>不能</b>再靠「读失败 → put 默认值」来证明重建：
        // 那条降级路径已被刻意删除（读失败 = 不知道 = fail-closed，绝不写「公开」）。
        // 于是这里改成「正常读回一行」，既保留了「clear 后确实重建」的判别力，
        // 又不把已修的缺陷重新固化成契约。读失败的语义由下面单独一条用例钉住。
        DatabaseManager db = mock(DatabaseManager.class);
        when(db.getTablePrefix()).thenReturn("lle_");
        when(db.getConnectionLock()).thenReturn(new Object());
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        try {
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            // 库里的真值：show_results=false（与内存里被改过的旧值相反，
            // 这样「有没有真的重读」是可判定的）。
            when(rs.next()).thenReturn(true, false);
            when(rs.getBoolean("show_results")).thenReturn(false);
            when(rs.getBoolean("show_answers")).thenReturn(false);
            when(rs.getBoolean("show_in_square")).thenReturn(true);
            when(rs.getBoolean("sounds_enabled")).thenReturn(true);
            when(rs.getBoolean("reminders_enabled")).thenReturn(true);
        } catch (SQLException e) {
            throw new AssertionError("stub 失败喵", e);
        }
        when(db.getConnection()).thenReturn(conn);

        // 本用例走「读成功」路径，不会记 WARNING —— 但 PlayerDataStore 构造与其它路径
        // 仍可能取 logger，铺成 lenient 避免 UnnecessaryStubbing。
        lenient().when(plugin.getLogger()).thenReturn(mock(Logger.class));
        Player online = mock(Player.class);
        when(online.getUniqueId()).thenReturn(PLAYER_ID);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        // Server#getOnlinePlayers() 在 Paper API 里返回 Collection<? extends Player>，
        // 通配符让 thenReturn(List<Player>) 的泛型推断失败（E 捕获不了 ? extends Player），
        // 故用 doReturn 绕开编译器对返回值的类型见证。
        doReturn(List.of(online)).when(server).getOnlinePlayers();

        PlayerDataStore reloaded = new PlayerDataStore(plugin, db);
        // 内存旧值：showResults=true（与库里的 false 相反）。
        reloaded.getProfileSettings(PLAYER_ID).setShowResults(true);

        reloaded.clearProfileCache();

        assertNotNull(reloaded.getCachedProfileSettings(PLAYER_ID),
                "clearProfileCache 后必须为在线玩家立即重建缓存条目 喵。\n"
                        + "只 clear 不重建 = 热重载后全体在线玩家长期处于「设置未知」，"
                        + "按 L22 的最严语义会集体变隐身、动态流静默停摆 —— 那是功能故障，"
                        + "不是隐私收益。");

        // ---- 「清空」半边的判别力 ----
        // 上面那条 assertNotNull 只钉住重建，删掉 profileSettingsCache.clear() 它也绿
        //（旧条目还在）。下面这条把「旧值确实被丢掉、并且确实重读了库」变成可断言的事实：
        // 重建读到的是 DB 里的 show_results=false；若没有 clear，读到的仍是内存里的 true。
        assertFalse(reloaded.getProfileSettingsReadOnly(PLAYER_ID).isShowResults(),
                "clearProfileCache 没有清掉旧条目 —— 重建分支读到的仍是那份旧值（true）。\n"
                        + "「clear + 重建」两者缺一不可：漏掉 clear 时，热重载后玩家看到的是"
                        + "上一份缓存快照，DB 里的最新改动永远不生效 喵。");
    }

    /**
     * P1-2（红队验收）：读库失败<b>不得</b>把「不知道」写成「公开」并缓存。
     *
     * <p>旧实现在 {@code loadProfileSettings} 的 catch 分支 {@code putIfAbsent(new
     * ProfileSettings())}（showResults/showInSquare 均为 true）—— 一次瞬时 DB 抖动，
     * 该玩家整个在线期间对广场/主页/PAPI 都读成「公开」，即使库里写着 {@code show_in_square=0}；
     * 更严重的是设置页读同一份缓存再整对象 UPSERT，玩家随手点一个开关就把
     * {@code show_in_square=true} 永久写回 DB，隐私设置不可撤销地丢失。
     *
     * <p>正确语义：读失败 = 未知 → 不建条目 → 读侧走 {@code unresolvedFallback()}（最严）。
     * 判别力：把 catch 分支的 putIfAbsent 加回来，本用例立刻变红（条目存在 + 读成公开）。
     */
    @Test
    @DisplayName("P1-2：loadProfileSettings 读库失败 → 不建缓存条目，读侧 fail-closed")
    void loadFailureDoesNotCachePublicDefaults() {
        DatabaseManager db = mock(DatabaseManager.class);
        when(db.getTablePrefix()).thenReturn("lle_");
        when(db.getConnectionLock()).thenReturn(new Object());
        Connection conn = mock(Connection.class);
        try {
            when(conn.prepareStatement(anyString())).thenThrow(new SQLException("test: no db"));
        } catch (SQLException e) {
            throw new AssertionError("stub 失败喵", e);
        }
        when(db.getConnection()).thenReturn(conn);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));

        PlayerDataStore store = new PlayerDataStore(plugin, db);
        store.loadProfileSettings(PLAYER_ID);

        assertNull(store.getCachedProfileSettings(PLAYER_ID),
                "读库失败后不得在 profileSettingsCache 里建条目 喵。\n"
                        + "put 一份 new ProfileSettings() 等于把「不知道」写成「公开」并长期缓存："
                        + "① 该玩家整个在线期间被当成公开（库里的 show_in_square=0 失效）；"
                        + "② 设置页读同一份缓存再整对象 UPSERT，点一个开关就把 show_in_square=true "
                        + "永久写回 DB —— 隐私设置不可撤销地丢失。");
        assertFalse(store.getProfileSettingsReadOnly(PLAYER_ID).isShowInSquare(),
                "读库失败后只读版必须 fail-closed（showInSquare=false）喵 —— 不能假设公开。");
        assertFalse(store.getProfileSettingsReadOnly(PLAYER_ID).isShowResults(),
                "读库失败后只读版必须 fail-closed（showResults=false）喵。");
    }

    // ------------------------------------------------------------------
    // E. C-P1-1 / C-P1-2：DB 故障不得穿透，必须「尽力而为」
    // ------------------------------------------------------------------

    /**
     * C-P1-1（红队 C）：{@code getConnection()} 抛 {@code RuntimeException} 时，
     * {@code loadProfileSettings} <b>不得</b>把异常抛给调用方。
     *
     * <p><b>为什么这是 P1</b>：{@code DatabaseManager.getConnection()} 在重连失败时抛的是
     * {@code RuntimeException}，旧实现的 {@code catch (SQLException)} 抓不到，于是异常沿
     * {@code clearProfileCache → HotReloadManager.reloadAll}（AdminCommand 无 try）一路冒泡，
     * <b>reloadAll 后半段（成就缓存 / 音效缓存 / 统计服务重启）全被跳过</b> ——
     * 一次 DB 抖动就让 {@code /lea reload} 变成半截执行。
     * {@code PlayerJoinListener:29} 同理：该 join 的后续初始化全部中断。
     *
     * <p>判别力：把 {@code getConnection()} 挪回 try 外面，本用例立刻抛
     * {@code RuntimeException("Failed to get database connection")} → 红。
     */
    @Test
    @DisplayName("C-P1-1：getConnection 抛 RuntimeException 时 loadProfileSettings 必须吞掉并返回")
    void loadToleratesRuntimeExceptionFromGetConnection() {
        DatabaseManager db = mock(DatabaseManager.class);
        when(db.getTablePrefix()).thenReturn("lle_");
        when(db.getConnectionLock()).thenReturn(new Object());
        when(db.getConnection()).thenThrow(
                new RuntimeException("Failed to get database connection"));
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);

        PlayerDataStore tolerant = new PlayerDataStore(plugin, db);

        // 不抛 = 通过（assertDoesNotThrow 由调用本身承担）。
        tolerant.loadProfileSettings(PLAYER_ID);

        // 与 P1-2 同语义：读失败 = 不知道，绝不 put「公开」。
        assertNull(tolerant.getCachedProfileSettings(PLAYER_ID),
                "getConnection 失败后同样不得建缓存条目（fail-closed）喵。");
        // 失败必须留痕，不能静默吞掉。
        org.mockito.Mockito.verify(logger).log(
                org.mockito.ArgumentMatchers.eq(java.util.logging.Level.WARNING),
                anyString(), org.mockito.ArgumentMatchers.any(Throwable.class));
    }

    /** 同形状：写路径（saveProfileSettings）也不得让 DB 故障穿透到 quit / unload 流程。 */
    @Test
    @DisplayName("C-P1-1：getConnection 抛 RuntimeException 时 saveProfileSettings 必须吞掉")
    void saveToleratesRuntimeExceptionFromGetConnection() {
        DatabaseManager db = mock(DatabaseManager.class);
        when(db.getTablePrefix()).thenReturn("lle_");
        when(db.getConnectionLock()).thenReturn(new Object());
        when(db.getConnection()).thenThrow(
                new RuntimeException("Failed to get database connection"));
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);

        PlayerDataStore tolerant = new PlayerDataStore(plugin, db);
        // 缓存里有条目才会走到写库（否则第一行就 return）。
        tolerant.getProfileSettings(PLAYER_ID).setAge(20);

        tolerant.saveProfileSettings(PLAYER_ID); // 不抛 = 通过

        org.mockito.Mockito.verify(logger).log(
                org.mockito.ArgumentMatchers.eq(java.util.logging.Level.WARNING),
                anyString(), org.mockito.ArgumentMatchers.any(Throwable.class));
    }

    /**
     * C-P1-2（红队 C）：逐玩家兜底 —— 第一个玩家的重建炸了，第二个仍必须被重建。
     *
     * <p><b>判别力</b>：把循环体外面的 try 删掉，第一个玩家抛出后直接穿透
     * {@code clearProfileCache}，第二个玩家不会被重建 →
     * {@code getCachedProfileSettings(second)} 为 null → 红。
     */
    @Test
    @DisplayName("C-P1-2：clearProfileCache 中单个玩家重建失败不得中断其余玩家")
    void clearProfileCacheContinuesAfterSinglePlayerFailure() {
        UUID second = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

        DatabaseManager db = mock(DatabaseManager.class);
        when(db.getTablePrefix()).thenReturn("lle_");
        when(db.getConnectionLock()).thenReturn(new Object());
        // 第一个玩家：getConnection 抛 RuntimeException（模拟重连失败）。
        // 第二个玩家：正常返回一行。
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        try {
            when(conn.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true, false);
        } catch (SQLException e) {
            throw new AssertionError("stub 失败喵", e);
        }
        // 第一次调用抛，之后正常 —— 用 thenThrow / thenReturn 序列。
        when(db.getConnection())
                .thenThrow(new RuntimeException("Failed to get database connection"))
                .thenReturn(conn);

        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);

        Player first = mock(Player.class);
        when(first.getUniqueId()).thenReturn(PLAYER_ID);
        Player secondPlayer = mock(Player.class);
        when(secondPlayer.getUniqueId()).thenReturn(second);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        doReturn(List.of(first, secondPlayer)).when(server).getOnlinePlayers();

        PlayerDataStore tolerant = new PlayerDataStore(plugin, db);
        tolerant.clearProfileCache();

        assertNull(tolerant.getCachedProfileSettings(PLAYER_ID),
                "第一个玩家重建失败 → 不得建条目（fail-closed），但也不得中断循环喵。");
        assertNotNull(tolerant.getCachedProfileSettings(second),
                "第一个玩家失败后，第二个玩家仍必须被重建 —— 逐玩家兜底失效时这里为 null，"
                        + "热重载后该玩家会一直处于「设置未知」（隐身）直到重新进服。");
        // 汇总日志必须出现（否则运维看不到「几个玩家降级了」）。
        org.mockito.Mockito.verify(logger, org.mockito.Mockito.atLeastOnce())
                .warning(org.mockito.ArgumentMatchers.contains("1/2"));
    }
}
