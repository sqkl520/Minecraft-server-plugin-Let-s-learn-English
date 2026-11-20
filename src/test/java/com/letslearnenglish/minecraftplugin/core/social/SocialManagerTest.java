package com.letslearnenglish.minecraftplugin.core.social;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R22：{@link SocialManager} 的首个测试类（此前 jacoco 显示
 * {@code SocialManager} / {@code CheerStats} / {@code FeedItem} <b>全部零行覆盖</b>）。
 *
 * <h2>这套东西坏了玩家会看到什么</h2>
 * <ul>
 *   <li><b>重复鼓励计数虚高</b> —— 广场上「❤ 鼓励 ×N」比实际人数多；</li>
 *   <li><b>隐私门漏一侧</b> —— 关掉「英语广场中可见」的玩家仍然被展示 / 被互动；</li>
 *   <li><b>隐私门越界</b> —— {@code showInSquare} 被当成「一切行为痕迹的总开关」，
 *       连隐身玩家<b>鼓励别人</b>这一操作留下的痕迹（名字进「谁鼓励了我」名单）也被遮蔽。
 *       注意这与上一条方向相反：上一条是漏、这一条是过度解读。R31 用户裁决：
 *       「隐身管的是其动态可不可见，不是其（比如鼓励）操作」—— 该开关只管<b>动态</b>，
 *       不管<b>操作</b>；</li>
 *   <li><b>计数漂移</b> —— 鼓励者被 {@code /lea reset} 后，作者的计数不减（见
 *       {@link KnownDefects}）。</li>
 * </ul>
 *
 * <h2>为什么不打 mock 数据库</h2>
 * 「同一玩家对同一条 feed 点两次」这件事的<b>唯一</b>防线是
 * {@code UNIQUE(feed_id, cheerer_uuid)} 这个 DB 约束（{@code DatabaseManager:888}）+
 * {@code INSERT OR IGNORE} 的受影响行数。mock 的 {@code executeUpdate} 只能演
 * 「我返回 1 / 我返回 0」，演不出「约束真的挡住了第二行」。所以这里开真 SQLite
 * （test-scope {@code org.xerial:sqlite-jdbc}），复刻本仓真实 PRAGMA。
 *
 * <h2>调度器替身</h2>
 * {@code SocialManager} 的写路径整段在 {@code runAsync} 里、结果分发在 {@code runMain}
 * 里。这里把两个调度方法都做成<b>就地同步执行</b>（照 {@code WrongBookAndSquareAsyncTest}
 * 的 Harness），于是「点击 → 写库 → 回主线程发消息」在测试里是一次普通调用。
 */
@DisplayName("R22：SocialManager —— 动态/鼓励/隐私门（零覆盖补测）")
class SocialManagerTest {

    private static final String PREFIX = "lle_";
    private static final UUID AUTHOR_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID CHEERER_ID = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-00000000c001");

    private LetsLearnEnglish plugin;
    private ConfigManager configManager;
    private FileConfiguration mainConfig;
    private DatabaseManager db;
    private PlayerDataStore store;

    private SocialManager social;

    /** 所有发给玩家的 i18n 键（按调用顺序）。断言只看「回了哪个键」。 */
    private final List<String> messages = new ArrayList<>();

    private MockedStatic<Bukkit> bukkitStatic;
    private MockedStatic<SoundManager> soundStatic;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        plugin = mock(LetsLearnEnglish.class);
        configManager = mock(ConfigManager.class);
        mainConfig = new YamlConfiguration();
        mainConfig.set("social.enabled", true);
        mainConfig.set("social.cheer.enabled", true);
        mainConfig.set("social.feed.retention-days", 7);
        mainConfig.set("social.cheer.click-debounce-ms", 0L); // 默认关防抖，让 DB 路径可达
        mainConfig.set("social.notify-on-join", true);
        mainConfig.set("database.type", "sqlite");
        mainConfig.set("database.sqlite.filename", "social-test.db");

        when(configManager.getMainConfig()).thenReturn(mainConfig);
        when(configManager.isSocialEnabled()).thenReturn(true);
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SocialManagerTest"));
        when(plugin.getDataFolder()).thenReturn(dir.toFile());

        db = new DatabaseManager(plugin, configManager);
        db.initialize();
        assertTrue(db.isSQLite(), "前置条件：必须走 SQLite 分支，否则「唯一约束挡重复」这条测不了");
        when(plugin.getDatabaseManager()).thenReturn(db);

        // 真 PlayerDataStore：隐私门的语义（cache hit = 真实值 / miss = 最严）必须被真的执行，
        // 打 mock 会把「fail-closed」这条被测契约本身 mock 掉。
        store = new PlayerDataStore(plugin, db);
        when(plugin.getPlayerDataStore()).thenReturn(store);

        MessageUtil messageUtil = mock(MessageUtil.class);
        when(plugin.getMessageUtil()).thenReturn(messageUtil);
        lenient().when(messageUtil.getPlayerMessage(any(Player.class), anyString(), any(String[].class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(1);
                    messages.add(key);
                    return "MSG:" + key;
                });

        // 调度器：异步段与回主线程段都就地执行（生产形状不变，只是把线程墙拆掉）。
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        lenient().when(scheduler.runTaskAsynchronously(any(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return null;
                });
        lenient().when(scheduler.runTask(any(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(1).run();
                    return null;
                });
        lenient().when(scheduler.runTaskTimerAsynchronously(any(), any(Runnable.class),
                anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));

        bukkitStatic = Mockito.mockStatic(Bukkit.class);
        // 默认作者离线：OK 分支不进「即时提醒 + 标已读」子路径（摘要用例自行覆盖）。
        bukkitStatic.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);
        // 静态音效：SoundManager.playSound 内部走 LetsLearnEnglish.getInstance() 的静态字段，
        // 纯 JVM 下它可能被别的测试污染；整段拦掉只保留「是否播过」这一点可断言性。
        soundStatic = Mockito.mockStatic(SoundManager.class);

        social = new SocialManager(plugin);
        social.startupWarmup(); // 清理 + 首次填充缓存 + 排程 300s 周期任务（替身返回 mock task）
    }

    @AfterEach
    void tearDown() {
        if (soundStatic != null) {
            soundStatic.close();
        }
        if (bukkitStatic != null) {
            bukkitStatic.close();
        }
        if (db != null) {
            db.shutdown();
        }
    }

    // ==================================================================
    // 组 1 —— 重复鼓励（本轮要求的第一条）
    // ==================================================================

    @Nested
    @DisplayName("组 1 —— 同一玩家对同一条 feed 点两次：第二次不得生效")
    class DuplicateCheer {

        @Test
        @DisplayName("第二次点 → ALREADY（cheer-already），计数与行数都不变")
        void secondCheerIsRejectedByUniqueConstraint() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();
            Player cheerer = player(CHEERER_ID, "Cheerer");

            social.cheer(cheerer, feedId);
            assertEquals(1, cheerCountInDb(feedId), "第一次鼓励必须 +1");
            assertEquals(1, cheerRowsInDb(feedId), "第一次鼓励必须落一行 social_cheers");
            assertTrue(messages.contains("square.feed.cheer-success"),
                    "第一次应提示成功，实际：" + messages);

            messages.clear();
            social.cheer(cheerer, feedId);

            assertEquals(1, cheerCountInDb(feedId),
                    "第二次鼓励必须被挡在 UNIQUE(feed_id, cheerer_uuid) 上喵 —— "
                            + "计数变成 2 就是「一人一次」契约破了，玩家会看到鼓励数虚高");
            assertEquals(1, cheerRowsInDb(feedId),
                    "social_cheers 里仍应只有 1 行（INSERT OR IGNORE 影响 0 行）");
            assertTrue(messages.contains("square.feed.cheer-already"),
                    "第二次应提示「你已经鼓励过了」，实际：" + messages);
            assertFalse(messages.contains("square.feed.cheer-success"),
                    "第二次不得再提示成功");
        }

        @Test
        @DisplayName("防抖窗口内连点 → 静默吞掉（连消息都没有），与 ALREADY 是两条不同路径")
        void clickWithinDebounceIsSilentlySwallowed() {
            mainConfig.set("social.cheer.click-debounce-ms", 60_000L);
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();
            Player cheerer = player(CHEERER_ID, "Cheerer");

            social.cheer(cheerer, feedId);
            messages.clear();
            social.cheer(cheerer, feedId); // 宏连点

            assertTrue(messages.isEmpty(),
                    "防抖窗口内必须**静默**吞掉（不回消息，因为刷屏本身就是它要防的东西）：" + messages);
            assertEquals(1, cheerRowsInDb(feedId), "防抖窗口内不得产生第二行");
        }

        @Test
        @DisplayName("unloadPlayer 清防抖戳：quit-rejoin 后不会被上个会话的窗口挡住")
        void unloadPlayerClearsDebounceStamp() {
            mainConfig.set("social.cheer.click-debounce-ms", 60_000L);
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();
            Player cheerer = player(CHEERER_ID, "Cheerer");

            social.cheer(cheerer, feedId);       // 建立防抖戳
            social.unloadPlayer(CHEERER_ID);     // quit 清理钩子
            messages.clear();
            social.cheer(cheerer, feedId);       // 重进服后再点

            assertTrue(messages.contains("square.feed.cheer-already"),
                    "unloadPlayer 必须清掉 cheerDebounceAt 里的条目喵 —— 否则玩家退服重进后"
                            + "第一次点击仍会被上个会话残留的防抖戳静默吞掉（看起来像「点了没反应」）。"
                            + "实际：" + messages);
        }

        /**
         * <b>防抖的粒度是「每玩家」而不是「每（玩家, 动态）」—— 这是被规划钉住的既有设计，
         * 不是实现走偏。</b>
         *
         * <p>证据链（R22 核实，见报告）：
         * <ol>
         *   <li>{@code tasks/phase3/plan-social.md:60} 明写数据结构就是
         *       {@code Map<UUID, Long> lastCheerAt} —— 键只有 UUID，<b>没有</b> feedId；</li>
         *   <li>同文件 {@code :413} 的验收用例第 13 条原文：
         *       <b>「防抖：连点宏 5 次/秒点<b>不同条目</b> → 750ms 内多余点击被吞
         *       （消息计数与 DB 行数一致）」</b> —— 规划<b>明确要求</b>跨条目也要被吞；</li>
         *   <li>{@code git log -S "click-debounce-ms"} 只命中一次引入提交
         *       （{@code 13dc44a} / {@code dca5ff3}，P3 Wave 2 五流合流），
         *       自引入起就是 {@code Map<UUID, Long>}，从未改过键的粒度；</li>
         *   <li>{@code config.yml} 该键注释「连续点击防抖（毫秒），防宏连点」——
         *       「连续点击」按玩家的手速，不是按条目。</li>
         * </ol>
         *
         * <p>所以要固化的是<b>当前语义</b>：750ms 内给<b>另一位作者</b>点赞也会被静默吞掉。
         * 若将来产品决定改成「每（玩家, 动态）」粒度（让「快速浏览广场连续鼓励不同人」
         * 这个正常操作生效），<b>本条会红</b> —— 那时请连同 plan §413 的验收条目一起改，
         * 而不是只改代码。
         */
        @Test
        @DisplayName("防抖粒度=每玩家：窗口内给【另一位作者】点赞同样被吞（plan §413 钉住的语义）")
        void debounceIsPerPlayerNotPerFeed() {
            mainConfig.set("social.cheer.click-debounce-ms", 60_000L);
            authorVisible(AUTHOR_ID);
            authorVisible(OTHER_ID);
            long feedA = seedFeed(AUTHOR_ID);
            long feedB = seedFeed(OTHER_ID);
            social.refreshCacheAsync();
            Player cheerer = player(CHEERER_ID, "Cheerer");

            social.cheer(cheerer, feedA);   // 第一次：正常写入
            assertEquals(1, cheerRowsInDb(feedA), "前置条件：feedA 上应有 1 行");
            messages.clear();

            social.cheer(cheerer, feedB);   // 窗口内点**另一条**动态

            assertTrue(messages.isEmpty(),
                    "窗口内跨条目点击必须同样静默吞掉喵（plan-social §413 验收第 13 条原文："
                            + "「连点宏 5 次/秒点不同条目 → 750ms 内多余点击被吞」）。"
                            + "若这里开始回 cheer-success，说明防抖粒度被改成了 "
                            + "（玩家, feed）复合键 —— 那是产品口径变更，请同步更新 plan 与 "
                            + "config.yml 注释。实际：" + messages);
            assertEquals(0, cheerRowsInDb(feedB),
                    "feedB 上不得产生任何行（这次点击根本没走到写库）");
        }

    }

    // ==================================================================
    // 组 2 —— 隐私门（D2：三个方向都查）
    // ==================================================================

    @Nested
    @DisplayName("组 2 —— showInSquare 隐私门：产生侧 / 读取侧 / 点击侧")
    class PrivacyGate {

        @Test
        @DisplayName("点击侧：隐身作者的动态不可被鼓励（cheer-hidden，且不写任何行）")
        void cannotCheerHiddenAuthor() {
            authorHidden();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();

            social.cheer(player(CHEERER_ID, "Cheerer"), feedId);

            assertTrue(messages.contains("square.feed.cheer-hidden"),
                    "对隐身作者的动态点击必须回 cheer-hidden，实际：" + messages);
            assertEquals(0, cheerCountInDb(feedId),
                    "隐身作者不得被写鼓励 —— 点了就 +1 等于「隐身仍然可被互动」");
            assertEquals(0, cheerRowsInDb(feedId), "social_cheers 里不得出现脏行");
        }

        @Test
        @DisplayName("读取侧：隐身作者的动态不出现在 Cache 快照里（含本人）")
        void hiddenAuthorDoesNotAppearInFeedSnapshot() {
            long feedId = seedFeed(AUTHOR_ID);
            // 读取侧过滤靠 SQL JOIN player_profiles，所以必须真的落一行 show_in_square=0；
            // 只改内存缓存测不到这一侧（那是点击侧的门）。
            store.getProfileSettings(AUTHOR_ID).setShowInSquare(false);
            store.saveProfileSettings(AUTHOR_ID);
            assertTrue(feedId > 0);

            social.refreshCacheAsync();

            for (SocialManager.FeedItem item : social.getFeedSnapshot()) {
                assertFalse(item.authorId().equals(AUTHOR_ID),
                        "隐身作者的条目必须被读取侧 SQL 过滤掉"
                                + "（COALESCE(pf.show_in_square,1) <> 0）喵 —— "
                                + "漏这一侧 = 「关掉广场可见」只在点击时报隐身，列表里照样看得见");
            }
        }

        @Test
        @DisplayName("产生侧：隐身作者不产生任何动态行")
        void hiddenAuthorProducesNoFeedRow() {
            authorHidden();

            social.recordSessionDone(AUTHOR_ID, "choice", 3, 5);
            social.recordAchievement(AUTHOR_ID, "helpful");
            social.recordCheckin(AUTHOR_ID, 3);

            assertEquals(0, feedCountInDb(),
                    "showInSquare=false 时 recordEvent 必须在**写入前**返回喵 —— "
                            + "否则隐身玩家的学习记录仍然进了广场（只是读的时候被过滤），"
                            + "一旦读取侧 SQL 回归就整批泄露");
        }

        @Test
        @DisplayName("产生侧 fail-closed：隐私缓存 miss（未加载）时同样丢弃")
        void cacheMissIsFailClosed() {
            // 不往缓存里放该作者条目 → 只读版回「隐私最严」副本（L22）。
            assertNull(store.getCachedProfileSettings(AUTHOR_ID),
                    "前置条件：缓存里不该有该作者条目");

            social.recordCheckin(AUTHOR_ID, 5);

            assertEquals(0, feedCountInDb(),
                    "隐私缓存 miss 的语义是「不知道」，而「不知道」不能当成「公开」喵 —— "
                            + "本仓既有口径见 PlayerDataStore.getProfileSettingsReadOnly（L22）");
        }

        @Test
        @DisplayName("social.enabled=false：事件入口静默、鼓励点击回 module.disabled")
        void moduleDisabledGate() {
            when(configManager.isSocialEnabled()).thenReturn(false);
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();

            social.recordCheckin(AUTHOR_ID, 5);
            social.cheer(player(CHEERER_ID, "Cheerer"), feedId);

            assertEquals(1, feedCountInDb(), "social.enabled=false 时不得再产生动态");
            assertTrue(messages.contains("module.disabled"),
                    "鼓励点击应回 module.disabled，实际：" + messages);
            assertEquals(0, cheerRowsInDb(feedId), "模块关闭时不得写鼓励");
        }
    }

    // ==================================================================
    // 组 3 —— 计数正确性（N 人 = N，不是 N²）
    // ==================================================================

    @Nested
    @DisplayName("组 3 —— 鼓励计数")
    class Counts {

        @Test
        @DisplayName("N 个不同玩家各鼓励一次 → cheer_count == N（不是 N²）")
        void distinctCheerersCountOnce() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();

            List<UUID> ids = List.of(
                    UUID.fromString("00000000-0000-0000-0000-00000000d001"),
                    UUID.fromString("00000000-0000-0000-0000-00000000d002"),
                    UUID.fromString("00000000-0000-0000-0000-00000000d003"));
            for (UUID id : ids) {
                social.cheer(player(id, "P-" + id), feedId);
            }

            assertEquals(ids.size(), cheerCountInDb(feedId),
                    "3 个人各鼓励一次必须是 3 喵 —— 若得到 6/9，说明计数被"
                            + "「读到的历史值 +1 后写回」或「每次点击按人次累加」搞成了平方级");
            assertEquals(ids.size(), cheerRowsInDb(feedId), "行数也必须恰好 3");
        }

        @Test
        @DisplayName("OK 路径乐观 +1：不刷新也能在快照里立刻看到新计数")
        void optimisticIncrementIsVisibleInSnapshot() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();
            int before = countInSnapshot(feedId);

            social.cheer(player(CHEERER_ID, "Cheerer"), feedId);

            assertEquals(before + 1, countInSnapshot(feedId),
                    "成功后必须在当前 volatile 快照上乐观 +1 喵 —— 否则 SocialListener 的成功回调"
                            + "重开界面时玩家看不到自己的鼓励（要等 ≤5s 去抖刷新或 300s 周期任务）");
        }

        @Test
        @DisplayName("getCheerStats：given / distinctTargets / received 三路口径")
        void cheerStatsAggregates() {
            // 两位作者都要铺隐私缓存：cheer() 的点击守卫对缓存 miss 的作者 fail-closed
            // （L22「未知即最严」→ showInSquare=false → cheer-hidden），
            // 只铺 AUTHOR_ID 会让 feedB 那次点击被隐私门吞掉，given 少 1。
            authorVisible(AUTHOR_ID);
            authorVisible(OTHER_ID);
            long feedA = seedFeed(AUTHOR_ID);
            long feedB = seedFeed(OTHER_ID);
            social.refreshCacheAsync();

            Player cheerer = player(CHEERER_ID, "Cheerer");
            social.cheer(cheerer, feedA);   // 同一人鼓励两位不同作者
            social.cheer(cheerer, feedB);

            assertEquals(2, messages.size(),
                    "两次鼓励都必须走完整个校验链并各自回一条结果（实际收到 " + messages
                            + "）喵 —— 若只有 1 条，说明第二次被某个门静默吞掉了"
                            + "（隐私门 cheer-hidden / 防抖 / 缓存 miss），"
                            + "那样 given 的分母就不是「点击次数」了");

            SocialManager.CheerStats stats = social.getCheerStats(CHEERER_ID);

            assertEquals(2, stats.given(), "given = 累计发出鼓励条数");
            assertEquals(2, stats.distinctTargets(),
                    "distinctTargets = 去重后的作者数（P3-3 互助成就判据 >= 10 靠它），"
                            + "不能按鼓励条数算 —— 否则给同一个人点 10 次就能刷出「互助之星」");
            assertEquals(1, social.getCheerStats(AUTHOR_ID).received(),
                    "received = 收到鼓励的总数（该作者只有 feedA 上 1 条）");
        }

        @Test
        @DisplayName("getCheeredFeedIds：空输入 / null 输入都返空集（不查库、不 NPE）")
        void cheeredIdsEmptyInputShortCircuits() {
            assertTrue(social.getCheeredFeedIds(CHEERER_ID, Collections.emptyList()).isEmpty());
            assertTrue(social.getCheeredFeedIds(CHEERER_ID, null).isEmpty(),
                    "null 输入必须回空集而不是 NPE —— 广场打开路径上这一行没有 try/catch");
        }

        private int countInSnapshot(long feedId) {
            for (SocialManager.FeedItem item : social.getFeedSnapshot()) {
                if (item.id() == feedId) {
                    return item.cheerCount();
                }
            }
            throw new AssertionError("快照里没有 feed " + feedId);
        }
    }

    // ==================================================================
    // 组 4 —— 进服摘要 / 鼓励名单
    // ==================================================================

    @Nested
    @DisplayName("组 4 —— 进服摘要与「谁鼓励了我」名单")
    class Summaries {

        @Test
        @DisplayName("离线期间的鼓励：进服发一次摘要并标已读；第二次进服不再发")
        void pendingCheersNotifiedOnce() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();
            social.cheer(player(CHEERER_ID, "Cheerer"), feedId);

            Player author = player(AUTHOR_ID, "Author");
            bukkitStatic.when(() -> Bukkit.getPlayer(AUTHOR_ID)).thenReturn(author);

            messages.clear();
            social.notifyPendingCheers(author);
            assertTrue(messages.contains("square.feed.notify-join"),
                    "应发进服摘要，实际：" + messages);

            messages.clear();
            social.notifyPendingCheers(author);
            assertTrue(messages.isEmpty(),
                    "摘要发出后必须异步标已读（markFeedCheersRead），第二次进服不得重复提醒："
                            + messages);
        }

        @Test
        @DisplayName("鼓励名单：同一人重复点不重复列出")
        void cheerersListIsDeduped() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();

            UUID a = UUID.fromString("00000000-0000-0000-0000-00000000e001");
            UUID b = UUID.fromString("00000000-0000-0000-0000-00000000e002");
            social.cheer(player(a, "Alice"), feedId);
            social.cheer(player(b, "Bob"), feedId);
            social.cheer(player(a, "Alice"), feedId); // ALREADY，不得重复进名单

            AtomicReference<List<String>> got = new AtomicReference<>();
            social.getCheerersAsync(AUTHOR_ID, feedId, got::set);

            List<String> names = got.get();
            assertNotNull(names, "回调必须在主线程被执行（本类调度器就地执行）");
            assertEquals(2, names.size(), "同一人重复点不得在名单里出现两次：" + names);
            assertEquals(2, new java.util.HashSet<>(names).size(), "名单内不得有重复名字：" + names);
        }

        /**
         * <b>产品裁决（R31）：{@code showInSquare} 管「动态」可见性，不管「操作」痕迹。</b>
         *
         * <p>用户原话：「隐身管的是其动态可不可见，不是其（比如鼓励）操作」。
         * 据此，「谁鼓励了我」记的是<b>一次操作</b>，不是一条<b>动态</b>，
         * 因此鼓励者的 {@code show_in_square} <b>不得</b>用来遮蔽他的名字。
         *
         * <p>缺陷形状（本用例守护的那次过度修复）：{@code DatabaseManager.loadCheerers}
         * 曾被改成 {@code LEFT JOIN player_profiles} + {@code COALESCE(show_in_square,1) <> 0}，
         * 把「关掉广场展示」升格成了「一切行为痕迹的总开关」—— 隐身玩家点了赞，
         * 作者左键自己的条目却看到名单里没有他。那不是隐私修复，是语义误读。
         *
         * <p>与作者侧口径对照：{@code getFeedCacheRows} 过滤 {@code show_in_square} 是对的
         * （它展示的正是「动态/展示面貌」）；本方法展示的是「操作痕迹」，不同源，故不过滤。
         */
        @Test
        @DisplayName("产品裁决：隐身玩家的名字仍出现在「谁鼓励了我」里（showInSquare 只管动态）")
        void hiddenCheererNameStillAppearsInCheererList() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();

            // B 隐身。cheer() 的隐身门查的是**作者**，与鼓励者无关，所以 B 照样能鼓励成功。
            // 隐私位落库（saveProfileSettings）：本用例要证明的正是「即使该位为 false，
            // 读取侧也不得据此过滤」—— 只改内存缓存证明不了 SQL 里没过滤。
            store.getProfileSettings(CHEERER_ID).setShowInSquare(false);
            store.saveProfileSettings(CHEERER_ID);
            // 名字来自 player_data 的 LEFT JOIN；不铺这行会回落成 UUID，断言「名字出现」
            // 就会以「查不到名字」为由红，红的原因与本次裁决无关。
            seedPlayerDataName(CHEERER_ID, "Cheerer");
            social.cheer(player(CHEERER_ID, "Cheerer"), feedId);

            assertEquals(1, cheerRowsInDb(feedId),
                    "前置条件：鼓励行必须真的写进去（隐身不阻断鼓励 —— 见 "
                            + "hiddenPlayerCanStillCheerOthers）。若这里是 0，本用例会因为"
                            + "「本来就没鼓励」而不是因为名单内容而绿。");

            AtomicReference<List<String>> got = new AtomicReference<>();
            social.getCheerersAsync(AUTHOR_ID, feedId, got::set);

            assertEquals(List.of("Cheerer"), got.get(),
                    "产品裁决（R31）：showInSquare 管的是「我的动态是否可见」，"
                            + "不遮蔽「我的操作（如鼓励）留下的痕迹」。鼓励者的名字必须出现喵 —— "
                            + "若这里是空，说明有人把 showInSquare 过度解读成了行为痕迹总开关"
                            + "（历史上 loadCheerers 就曾这么错过）。实际：" + got.get());
        }

        /**
         * <b>与上一条同口径的正面半边</b>：鼓励者 {@code show_in_square = true} 时名字当然出现。
         *
         * <p>它的存在理由在 R31 裁决后变了：原先它是「防上一轮过滤被实现成一律返空」的
         * 反向对照（隐身→空 / 可见→有，成对证明「按位生效」）；回退过滤后口令变成
         * 「两种取值都必须出现」，本用例与上一条合起来证明的是一件事：
         * <b>鼓励者的可见性开关对名单没有影响</b> —— 无论 true 还是 false，名字都在。
         *
         * <p>保留本条还有独立价值：它同时断言名字确实来自 {@code player_data} 的 JOIN
         * （而不是回落成 uuid），这是「名单可读」的基础保障。
         */
        @Test
        @DisplayName("口径一致：鼓励者可见时名字出现（与隐身用例相同结果，证明开关不影响名单）")
        void visibleCheererNameIsStillExposed() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();

            store.getProfileSettings(CHEERER_ID).setShowInSquare(true);
            store.saveProfileSettings(CHEERER_ID);
            // 名字来自 player_data 的 LEFT JOIN —— 不铺这一行，MAX(p.player_name) 是 null，
            // 名单会回落成 UUID，本用例会因为「查不到名字」而红。
            seedPlayerDataName(CHEERER_ID, "Cheerer");
            social.cheer(player(CHEERER_ID, "Cheerer"), feedId);

            AtomicReference<List<String>> got = new AtomicReference<>();
            social.getCheerersAsync(AUTHOR_ID, feedId, got::set);

            assertEquals(List.of("Cheerer"), got.get(),
                    "可见鼓励者的名字必须出现在名单里喵 —— 与隐身用例结果相同，"
                            + "因为 showInSquare 不管操作痕迹（R31 裁决）");
        }

        /**
         * <b>产品语义边界（R31 裁决的正向印证，不是缺陷）</b>：隐身只作用于
         * 「我的<b>动态</b>在广场是否可见」，完全不触及「我的<b>操作能力</b>」——
         * 隐身玩家依然可以鼓励别人的动态（{@code cheer()} 的隐身门查的是
         * {@code item.authorId()}，不是鼓励者）。
         *
         * <p>本用例与上面两条互补：上面两条证明「隐身不影响名字出现在<u>别人的</u>名单里」，
         * 本条证明「隐身也不影响我<u>自己</u>发起鼓励这个动作」。三者合起来才是 R31 裁决的
         * 完整形状 —— {@code showInSquare} 这个开关的作用域<b>只有动态可见性</b>，
         * 既不遮蔽鼓励痕迹、也不剥夺鼓励能力。
         *
         * <p>本用例守护这条边界：将来若有人把 {@code showInSquare} 再次升格成
         * 「一切社交行为的闸门」而在 {@code cheer()} 里加一道「隐身者不得鼓励」的门，
         * 这里会红并提示「过度修复」。
         */
        @Test
        @DisplayName("设计边界：隐身玩家仍可鼓励他人（showInSquare 只管动态可见性）")
        void hiddenPlayerCanStillCheerOthers() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();
            store.getProfileSettings(CHEERER_ID).setShowInSquare(false);

            social.cheer(player(CHEERER_ID, "Cheerer"), feedId);

            assertEquals(1, cheerRowsInDb(feedId),
                    "隐身不阻断鼓励（cheer() 的隐身门查的是 item.authorId()，与鼓励者无关）喵 —— "
                            + "若这里是 0，说明有人把 showInSquare 当成了社交行为总闸门"
                            + "（过度解读，R31 裁决已明确它只管动态可见性）");
            assertTrue(messages.contains("square.feed.cheer-success"),
                    "且回的是成功提示，不是 cheer-hidden：" + messages);
        }
    }

    // ==================================================================
    // 组 5 —— 自赞 / 条目失效
    // ==================================================================

    @Nested
    @DisplayName("组 5 —— 自赞与条目失效")
    class SelfAndGone {

        @Test
        @DisplayName("自赞被拒（cheer-own），不写库")
        void cannotCheerOwnFeed() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();

            social.cheer(player(AUTHOR_ID, "Author"), feedId);

            assertTrue(messages.contains("square.feed.cheer-own"),
                    "自赞应回 cheer-own，实际：" + messages);
            assertEquals(0, cheerRowsInDb(feedId), "自赞不得写行");
            assertEquals(0, cheerCountInDb(feedId), "自赞不得改计数");
        }

        @Test
        @DisplayName("条目不在快照里 → cheer-gone（不写脏行）")
        void cheerOnUnknownFeedIsGone() {
            authorVisible();
            social.refreshCacheAsync();

            social.cheer(player(CHEERER_ID, "Cheerer"), 999_999L);

            assertTrue(messages.contains("square.feed.cheer-gone"),
                    "快照里没有的条目应回 cheer-gone，实际：" + messages);
        }

        @Test
        @DisplayName("快照里在、DB 里已被清理 → 数据层回 GONE，文案仍是 cheer-gone")
        void cheerOnRowDeletedFromDbIsGone() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();
            exec("DELETE FROM " + PREFIX + "social_feed WHERE id = " + feedId);

            social.cheer(player(CHEERER_ID, "Cheerer"), feedId);

            assertTrue(messages.contains("square.feed.cheer-gone"),
                    "DB 行真的不存在时必须回业务事实 cheer-gone（不得谎报成故障提示），"
                            + "实际：" + messages);
        }
    }

    // ==================================================================
    // 组 6 —— 已修复缺陷的回归护栏 + 设计边界
    // ==================================================================

    @Nested
    @DisplayName("组 6 —— 已修复缺陷的回归护栏（D4/D5）")
    class KnownDefects {

        /**
         * <b>D5（计数漂移，已修复）：{@code /lea reset <鼓励者>} 后 {@code cheer_count}
         * 必须跟着 {@code social_cheers} 的行数走。</b>
         *
         * <p>缺陷形状（修复前）：{@code deletePlayerData} 会
         * {@code DELETE FROM social_cheers WHERE cheerer_uuid = ?}，但<b>没有</b>任何
         * {@code UPDATE social_feed SET cheer_count = ...}。于是：
         * <ul>
         *   <li>{@code social_cheers} 里那行没了 →「谁鼓励了我」名单少一个人；</li>
         *   <li>但 {@code social_feed.cheer_count} 仍是旧值 → 广场上「❤ 鼓励 ×N」不变。</li>
         * </ul>
         * 玩家看到的是<b>同一张卡片上两个自相矛盾的数字</b>，而且没有任何对账路径能修回来
         * （{@code cheer_count} 只在 {@code cheerFeed} 里 +1，从不重算）。
         *
         * <p>修复：{@code deletePlayerData} 在删 {@code social_cheers} <b>之前</b>快照
         * 「本玩家鼓励过哪些 feed」（{@code idx_cheers_cheerer} 索引），删完按
         * {@code COUNT(*)} 重算这些 feed 的 {@code cheer_count}，整段在同一事务内。
         */
        @Test
        @DisplayName("D5：reset 鼓励者后 cheer_count 必须回落到实际行数")
        void resetOfCheererKeepsAuthorCheerCountAccurate() {
            authorVisible();
            long feedId = seedFeed(AUTHOR_ID);
            social.refreshCacheAsync();
            social.cheer(player(CHEERER_ID, "Cheerer"), feedId);
            assertEquals(1, cheerCountInDb(feedId), "前置条件：先有 1 条鼓励");

            db.deletePlayerData(CHEERER_ID);

            assertEquals(0, cheerRowsInDb(feedId),
                    "deletePlayerData 按 cheerer_uuid 删掉了鼓励行");
            assertEquals(cheerRowsInDb(feedId), cheerCountInDb(feedId),
                    "cheer_count 必须等于 social_cheers 的实际行数（去规范化列的一致性不变量）"
                            + "喵 —— 若这里是 1 而行数是 0，说明对账又丢了（D5 复发）");
        }

        /**
         * <b>D5 的规模面：只重算「受影响的 feed」，不碰别人的。</b>
         *
         * <p>修复用的是「方案 A 的范围 + 重算求值」：受影响集合来自
         * {@code SELECT DISTINCT feed_id FROM social_cheers WHERE cheerer_uuid = ?}
         * （走 {@code idx_cheers_cheerer}），所以与「该玩家鼓励过多少帖子」成正比，
         * 不是对整个 {@code social_feed} 做 O(全表) 的 UPDATE。
         *
         * <p>本用例把这一点变成可执行的证据：被 reset 的玩家只鼓励过 feedA，
         * 另一位鼓励者的 feedB 计数<b>必须纹丝不动</b>。若有人把实现改成「删完全表重算」，
         * 只要重算的 SQL 写对就不会红 —— 但若改成「无差别清 0」或「按错误集合对账」，
         * 这条就会抓住。
         */
        @Test
        @DisplayName("D5：对账只碰受影响的 feed，别人的计数不受牵连")
        void reconcileOnlyTouchesAffectedFeeds() {
            UUID bystander = UUID.fromString("00000000-0000-0000-0000-00000000f001");
            authorVisible(AUTHOR_ID);
            authorVisible(OTHER_ID);
            long feedA = seedFeed(AUTHOR_ID);
            long feedB = seedFeed(OTHER_ID);
            social.refreshCacheAsync();

            social.cheer(player(CHEERER_ID, "Cheerer"), feedA); // 会被 reset 的那位
            social.cheer(player(bystander, "Bystander"), feedB); // 无辜的第三方

            assertEquals(1, cheerCountInDb(feedA), "前置条件：feedA 有 1 条鼓励");
            assertEquals(1, cheerCountInDb(feedB), "前置条件：feedB 有 1 条鼓励");

            db.deletePlayerData(CHEERER_ID);

            assertEquals(0, cheerCountInDb(feedA),
                    "feedA 的鼓励者被 reset → 计数必须回到 0");
            assertEquals(1, cheerCountInDb(feedB),
                    "feedB 与本次 reset 无关 → 计数必须保持 1 喵（对账范围不能扩大）");
            assertEquals(1, cheerRowsInDb(feedB), "且 feedB 的鼓励行必须原样留着");
        }

        /**
         * <b>D4（lint 盲区，已修复）：{@code getCheeredFeedIds} 必须在门禁的视野内。</b>
         *
         * <p>缺陷形状：{@code EnglishSquareGUI.renderFeedEntries} 在<b>主线程渲染段</b>直接调
         * {@code socialManager.getCheeredFeedIds(...)} → {@code DatabaseManager.getCheeredFeedIds}
         * → 一次 JDBC 查询。而 {@code MainThreadIoContractTest.DB_READ_METHODS} 里登记了
         * {@code getFeedCacheRows} / {@code loadCheerers} / {@code getCheerers} /
         * {@code countUnreadCheers} / {@code getCheerStatsRow}，<b>唯独没有
         * {@code getCheeredFeedIds}</b> —— 于是这条主线程 IO 不在任何门禁覆盖里。
         * 代码注释（plan-social §3.2）明确<b>接受</b>这次查询（≤28 个 id、走唯一索引），
         * 但「接受」不该等于「不可见」：隐形意味着它既不会被基线理由解释，也不会在
         * 「别处又调一次」时变红。
         *
         * <p>修复后本用例检查三件事，缺一不可：
         * <ol>
         *   <li>{@link #DB_READ_METHODS} 的 <b>Set 字面量里</b>有 {@code getCheeredFeedIds} ——
         *       光看整份文件包含这个字符串会被别处的注释满足（本类自己的 javadoc 就在提它），
         *       那是自证桩；</li>
         *   <li>{@link #KNOWN_MAIN_THREAD_IO} 的 Set 字面量里登记了
         *       {@code gui/EnglishSquareGUI.java#renderFeedEntries@getCheeredFeedIds}
         *       —— 不登记的话主 lint 会直接红（未登记违规）；</li>
         *   <li>{@link #KNOWN_WHY} 里有同键的理由 —— 门禁要求每条基线都写清「为什么可以留在
         *       主线程」，否则登记会腐化成「看名字猜原因」的永久豁免。</li>
         * </ol>
         * 三者取自各自 <b>Set/Map 字面量区间内部</b>，而不是整份文件。
         */
        @Test
        @DisplayName("D4：getCheeredFeedIds 已进禁止名单，且在基线里带理由登记")
        void cheeredFeedIdsShouldBeCoveredByMainThreadIoLint() throws Exception {
            String lintSource = Files.readString(repoRoot().resolve(
                    "src/test/java/com/letslearnenglish/minecraftplugin/concurrency/"
                            + "MainThreadIoContractTest.java"), StandardCharsets.UTF_8);

            assertTrue(quotedLiteralsInDeclaration(lintSource, "DB_READ_METHODS")
                            .contains("getCheeredFeedIds"),
                    "DB_READ_METHODS 的 Set 字面量里没有 getCheeredFeedIds 喵 —— "
                            + "那样这条主线程 IO 又变回隐形人（D4 复发）。"
                            + "注意判据只认<b>声明区间内</b>的字面量：整份文件里到处都有这个名字"
                            + "（注释、javadoc、本用例），用 contains 会恒绿。");

            String baselineKey = "gui/EnglishSquareGUI.java#renderFeedEntries@getCheeredFeedIds";
            assertTrue(quotedLiteralsInDeclaration(lintSource, "KNOWN_MAIN_THREAD_IO")
                            .contains(baselineKey),
                    "KNOWN_MAIN_THREAD_IO 里没有登记 " + baselineKey + " 喵 —— "
                            + "禁止名单里有它、基线里却没有，主 lint 会直接判「未登记违规」变红。"
                            + "当前基线：" + quotedLiteralsInDeclaration(lintSource, "KNOWN_MAIN_THREAD_IO"));

            assertTrue(quotedLiteralsInDeclaration(lintSource, "KNOWN_WHY").contains(baselineKey),
                    "KNOWN_WHY 里没有 " + baselineKey + " 的理由喵 —— "
                            + "门禁要求每条基线都写明「为什么这一处可以留在主线程」，"
                            + "缺理由会红（基线不得腐化成永久豁免）。");
        }

        /**
         * 取某个字段声明的<b>字面量区间</b>里出现的全部字符串字面量。
         *
         * <p>为什么不能直接 {@code source.contains("\"getCheeredFeedIds\"")}：这个名字在
         * {@code MainThreadIoContractTest} 里被注释、javadoc 与别的 Set 反复提到，
         * 整份文件 contains 会被<b>注释</b>满足 —— 那是自证桩（删掉真正的登记也不会红）。
         * 这里把区间收窄到「声明首行 → 其后第一个 {@code );} 或 {@code )}」，
         * 于是只认那张表真正登记的内容。
         *
         * <p>已知局限（诚实记录）：仍会被该声明<b>内部</b>的注释满足（本仓确实有
         * {@code // D4：...} 这种块内说明）。但那种注释与被注释的登记处在同一张表里，
         * 删登记时几乎必然连注释一起删；而整文件 contains 的假绿面是整个文件，风险量级不同。
         */
        private java.util.Set<String> quotedLiteralsInDeclaration(String source, String fieldName) {
            int decl = source.indexOf(fieldName + " = ");
            assertTrue(decl > 0, "找不到 " + fieldName + " 的声明喵 —— 结构守卫的锚点失效，需更新。");
            // 三种字面量工厂都要认：Set.of( / Map.ofEntries( / Map.of(
            // （KNOWN_WHY 是 Map.ofEntries —— Map.of 只到 10 对，第 11 条基线加不进去）。
            int from = -1;
            for (String factory : new String[] {"Set.of(", "Map.ofEntries(", "Map.of("}) {
                int at = source.indexOf(factory, decl);
                if (at > 0 && (from < 0 || at < from)) {
                    from = at;
                }
            }
            assertTrue(from > 0, "找不到 " + fieldName + " 的字面量工厂喵 —— 锚点失效。");
            int end = source.indexOf("\n    );", from);
            assertTrue(end > from, "找不到 " + fieldName + " 字面量的收尾喵 —— 锚点失效。");
            String region = source.substring(from, end);
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(region);
            while (m.find()) {
                out.add(m.group(1));
            }
            return out;
        }
    }

    // ==================================================================
    // 组 7 —— 停用屏障（R4-06-F2 的回归）
    // ==================================================================

    @Nested
    @DisplayName("组 7 —— shutdown 之后不得再触库")
    class ShutdownBarrier {

        @Test
        @DisplayName("shutdown() 后 recordCheckin 不产生任何 DB 写（重开整库是旧缺陷）")
        void noDatabaseWriteAfterShutdown() {
            authorVisible();
            long before = feedCountInDb();

            social.shutdown();
            social.recordCheckin(AUTHOR_ID, 9);
            social.refreshCacheAsync();

            assertEquals(before, feedCountInDb(),
                    "停用闩落下后 runAsync 必须整段放弃喵 —— 否则 db.shutdown() 之后到达的任务会走 "
                            + "getConnection() 见 isClosed() 而重新 initialize() 整个库（R4-06-F2）");
        }

        @Test
        @DisplayName("shutdown() 幂等：连调两次不抛")
        void shutdownIsIdempotent() {
            social.shutdown();
            social.shutdown();
            assertTrue(true, "连调两次不得抛 NPE / IllegalStateException");
        }
    }

    // ==================================================================
    // 脚手架
    // ==================================================================

    private Player player(UUID id, String name) {
        Player p = mock(Player.class);
        lenient().when(p.getUniqueId()).thenReturn(id);
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.isOnline()).thenReturn(true);
        return p;
    }

    /** 让作者在隐私缓存里有条目且「广场可见」（判据是缓存命中，不是 DB 行）。 */
    private void authorVisible() {
        authorVisible(AUTHOR_ID);
    }

    /**
     * 同上，但指定作者。
     *
     * <p><b>为什么必须有这个重载</b>：{@link #authorVisible()} 只铺 {@link #AUTHOR_ID}。
     * 而 {@code SocialManager.cheer} 的点击守卫读的是
     * {@code getProfileSettingsReadOnly(作者)} —— 该方法在缓存 miss 时返回
     * {@link PlayerDataStore.ProfileSettings#unresolvedFallback()}（L22「未知即最严」，
     * showInSquare=false）。于是<b>任何没被铺过的作者都会被判隐身</b>，
     * 点击回 {@code cheer-hidden} 而不是写库。
     *
     * <p>这不是生产缺陷（对离线/未加载作者 fail-closed 正是隐私契约），
     * 而是夹具漏铺：多作者场景（如 {@code cheerStatsAggregates} 里
     * AUTHOR_ID + OTHER_ID 各一条动态）必须逐个铺，否则第二条会被静默挡在隐私门上，
     * 表现为「鼓励数少 1」，看起来像防抖/计数 bug（R22 实测踩到）。
     */
    private void authorVisible(UUID authorId) {
        store.getProfileSettings(authorId).setShowInSquare(true);
    }

    /** 同上，但隐身（只影响内存缓存 —— 覆盖点击侧的门）。 */
    private void authorHidden() {
        store.getProfileSettings(AUTHOR_ID).setShowInSquare(false);
    }

    /**
     * 造一条今日 SESSION_DONE feed 行并返回 id。
     *
     * <p>id 用<b>直接 SQL</b> 取而不是 {@code getFeedCacheRows}：后者带
     * {@code show_in_square} 过滤，隐身用例里会查不到自己刚插入的行
     * （那样用例会以「前置条件不成立」失败，掩盖真正想测的东西）。
     */
    /**
     * 给指定玩家铺一行 {@code player_data}（只需名字）。
     *
     * <p>{@code loadCheerers} 的名字来自 {@code LEFT JOIN player_data p}，
     * 查不到行时 {@code MAX(p.player_name)} 为 null，名单回落成 UUID 字符串。
     * 断言「名单里是某个<b>名字</b>」的用例必须先把名字铺好，否则会以
     * 「名字本来就没有」为由红（红的原因与用例想证明的东西无关）。
     *
     * <p>R31 裁决后 {@code loadCheerers} 不再按 {@code show_in_square} 过滤，
     * 所以隐身/可见两条用例都同样需要这行 —— 它们现在是「同口径」的两个正例。
     */
    private void seedPlayerDataName(UUID playerId, String name) {
        synchronized (db.getConnectionLock()) {
            try (var ps = db.getConnection().prepareStatement(
                    "INSERT INTO " + PREFIX + "player_data (uuid, player_name) VALUES (?, ?)"
                            + " ON CONFLICT(uuid) DO UPDATE SET player_name = excluded.player_name")) {
                ps.setString(1, playerId.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new AssertionError("铺 player_data 失败: " + e.getMessage(), e);
            }
        }
    }

    private long seedFeed(UUID authorId) {
        db.upsertFeedEvent(authorId, "SESSION_DONE", 3, 5, "choice", 0, null);
        synchronized (db.getConnectionLock()) {
            try (var ps = db.getConnection().prepareStatement(
                    "SELECT id FROM " + PREFIX + "social_feed WHERE player_uuid = ?"
                            + " ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, authorId.toString());
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "前置条件不成立：social_feed 里应有刚插入的行");
                    long id = rs.getLong(1);
                    assertTrue(id > 0, "前置条件不成立：feed id 应有效（实际 " + id + "）");
                    return id;
                }
            } catch (Exception e) {
                throw new AssertionError("seedFeed 失败：" + e.getMessage(), e);
            }
        }
    }

    private int cheerCountInDb(long feedId) {
        return (int) queryLong("SELECT cheer_count FROM " + PREFIX
                + "social_feed WHERE id = " + feedId, "cheer_count");
    }

    private int cheerRowsInDb(long feedId) {
        return (int) queryLong("SELECT COUNT(*) FROM " + PREFIX
                + "social_cheers WHERE feed_id = " + feedId, "social_cheers 行数");
    }

    private long feedCountInDb() {
        return queryLong("SELECT COUNT(*) FROM " + PREFIX + "social_feed", "social_feed 行数");
    }

    private long queryLong(String sql, String what) {
        synchronized (db.getConnectionLock()) {
            try (var st = db.getConnection().createStatement();
                 var rs = st.executeQuery(sql)) {
                assertTrue(rs.next(), "查询无结果：" + what);
                return rs.getLong(1);
            } catch (Exception e) {
                throw new AssertionError("读 " + what + " 失败：" + e.getMessage(), e);
            }
        }
    }

    private void exec(String sql) {
        synchronized (db.getConnectionLock()) {
            try (var st = db.getConnection().createStatement()) {
                st.executeUpdate(sql);
            } catch (Exception e) {
                throw new AssertionError("执行失败：" + sql + " / " + e.getMessage(), e);
            }
        }
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("src/main/java"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（向上 6 层都没有 src/main/java）");
    }
}
