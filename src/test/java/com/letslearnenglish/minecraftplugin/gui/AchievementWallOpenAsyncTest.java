package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.achievement.AchievementManager;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * R18：{@code AchievementWallGUI} 的「主线程查库 → 异步预取 + 主线程渲染」改造验收。
 *
 * <h2>被修的缺陷</h2>
 * <p>{@code SocialListener} 的点击处理器在主线程 {@code new AchievementWallGUI(plugin)
 * .open(player, targetId)}，而 {@code open} 同步走完：
 * {@code getUnlockedAchievements}（cache miss → SELECT 成就表）、
 * {@code getProgressSnapshot}（内部 {@code getPlayerStats} 可能触发 {@code loadPlayerData}
 * 的两段 SELECT + {@code getCoinsEarned} 一条 SELECT + {@code fetchSessionDays} 一条
 * {@code player_sessions} 全历史 SELECT + {@code getConversationCount} /
 * {@code getWrongBookCleared} 的懒加载），以及逐条 {@code getUnlockedAt} /
 * {@code meetsPrerequisites}。看<b>离线玩家</b>时全部 cache-miss —— TPS 跟着点击手速走。
 *
 * <p>源码里那句「≤3 条有界索引查询，主线程，可接受」在离线目标上不成立，是本缺陷的
 * 原始误判。同族 GUI（{@code ProfileGUI} / {@code ChatHistoryGUI} /
 * {@code DailyChallengeGUI} / {@code LearningCalendarGUI}）此前都已迁成两段式，
 * 成就墙是这一族里唯一没迁的。
 *
 * <h2>为什么必须同时有「源码 lint」和「行为测试」</h2>
 * <ul>
 *   <li><b>线程归属是文本属性</b>：把 {@code getProgressSnapshot} 从异步段挪回
 *       {@code open()}，功能<b>照旧正常</b>（数据一样对、界面一样出），任何行为断言都不会红。
 *       只有源码 lint 能抓（见 {@link MainThreadDoesNoIo}）。</li>
 *   <li><b>守卫是运行期属性</b>：{@code stillOnRequestedScreen} 被删掉后，全部行为仍
 *       「正常」—— 直到玩家在异步窗口里关掉界面，成就业墙被强行盖到他当前屏幕上。
 *       只有行为测试能抓（见 {@link BackfillGuards}）。</li>
 * </ul>
 * 两组互为补充，缺一组就会留下一条**任何一种测试都抓不到**的回归路径。
 */
@ExtendWith(MockitoExtension.class)
class AchievementWallOpenAsyncTest {

    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-00000000a1a1");
    /** 被查看玩家 —— 必须与 viewer 不同，否则「targetId 是不是打开者」这条语义测不出来。 */
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-00000000b2b2");

    /** 墙尺寸（{@code AchievementWallGUI.GUI_SIZE}）。 */
    private static final int WALL_SIZE = 54;

    @Mock
    private LetsLearnEnglish plugin;
    @Mock
    private Player viewer;
    @Mock
    private ConfigManager configManager;
    @Mock
    private FileConfiguration messages;
    @Mock
    private AchievementManager achievementManager;
    @Mock
    private PlayerDataStore dataStore;

    @BeforeEach
    void setUp() {
        // 全 lenient：本类混了两类用例 —— 源码 lint（完全不碰这些 mock）与行为用例
        // （各消费不同子集）。STRICT_STUBS 会把「lint 用例没消费的 stub」判成多余代码，
        // 那不是缺陷，是分组共用夹具。判别力由<b>每个用例自己的 verify/断言</b>承担。
        lenient().when(plugin.getConfigManager()).thenReturn(configManager);
        lenient().when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        // 管理员没改 gui-layout 时的等价行为：回落到调用方默认实参。
        lenient().when(configManager.getGuiMaterial(anyString(), anyString(), any(Material.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        lenient().when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        // 成就模块已装配（正常路径）；未装配的兜底由生产代码自己处理，不在本类验收范围。
        lenient().when(plugin.getAchievementManager()).thenReturn(achievementManager);
        lenient().when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        lenient().when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
    }

    // ==================================================================
    //  A. 源码 lint：主线程段零 DB 调用
    // ==================================================================

    /**
     * 主线程零查库 —— <b>本类最主要的一条</b>，因为它抓的是行为测试<b>永远抓不到</b>的回归。
     *
     * <p>锚点是方法名而不是行号：行号会随任何人加一行注释而漂；方法名只在真的改名时失效，
     * 而改名会让 {@code extractBody} 直接 fail（不是静默跳过）。
     *
     * <p>断言前先 {@link #stripComments 剥注释}：源码 Javadoc 里大量引用这些方法名来解释
     * 「为什么搬走了」，不剥的话 lint 会被自己的文档判红。
     */
    @Nested
    @DisplayName("组 A —— 源码 lint：open/openAsync/openWithData 零查库")
    class MainThreadDoesNoIo {

        /** 主线程段绝对不允许出现的取数调用（成就墙的全部数据源）。 */
        private static final List<String> FORBIDDEN = List.of(
                "getProgressSnapshot",     // 进度快照：stats + coins + 会话日 + 复习日 + study-days
                "getUnlockedAchievements",  // 成就表（cache miss 时 SELECT）
                "getUnlockedAt",            // 逐条解锁时刻
                "meetsPrerequisites",       // 逐条前置判定（内部可能 getPlayerStats）
                "getCoinsEarned",           // 一条 SELECT
                "getPlayerStats",           // cache miss → loadPlayerData（两段 SELECT）
                "getSessionDays",           // player_sessions 全历史
                "getConversationCount",     // 懒加载
                "getWrongBookCleared");     // 懒加载

        @Test
        @DisplayName("A1：open(viewer, targetId) 方法体不得出现任何成就/统计/金币查询")
        void openBodyDoesNotQueryAchievements() {
            String body = guiBody("public void open(");
            assertNoForbidden(body, "open(...)");
            // 反向锚点：open 必须真的委托给异步入口。否则「把 open 改成空方法」也能通过上面那条。
            assertTrue(body.contains("openAsync"),
                    "open(...) 方法体里找不到 openAsync 喵：既有调用点（SocialListener）靠它进入"
                            + "两段式流程，改成空方法会让成就墙整页打不开。");
        }

        @Test
        @DisplayName("A2：openAsync(...) 方法体（含其中的 lambda）不得出现任何取数调用")
        void openAsyncBodyDoesNotQueryAchievements() {
            String body = guiBody("public void openAsync(");
            // 关键：extractBody 截的是整个方法体的花括号区间，lambda 体在里面 ——
            // 所以「把取数塞进 runTaskAsynchronously 的 lambda」会被误判吗？
            // 不会：那样写属于「在异步段取数」，但本 lint 抓的是 openAsync 方法体内出现
            // 直接取数调用。因此正确实现的形状必须是「openAsync 只调度，取数在独立方法里」。
            // 这也是把 prefetchWallData 独立成方法的理由（见 A3 的反向锚点）。
            assertNoForbidden(body, "openAsync(...)");
            assertTrue(body.contains("runTaskAsynchronously"),
                    "openAsync(...) 里找不到 runTaskAsynchronously 喵：取数必须离开主线程。");
            assertTrue(body.contains("prefetchWallData"),
                    "openAsync(...) 里找不到 prefetchWallData 喵：异步段没有取数入口，"
                            + "界面会永远停在「不会打开」的状态。");
            assertTrue(body.contains("stillOnRequestedScreen"),
                    "openAsync(...) 里找不到 stillOnRequestedScreen 喵：回填守卫被删了 ——"
                            + "玩家在异步窗口里关掉界面后，成就墙会被强行盖到他当前屏幕上。");
        }

        /**
         * <b>A2b（把 A2 从「提到了」升级为「提在正确的位置」）</b>。
         *
         * <p>只断言「openAsync 里出现了 {@code prefetchWallData}」有一个致命的假绿形态：
         * 把它直接<b>同步</b>调在 {@code runTaskAsynchronously} <b>之外</b>
         * （{@code WallData data = prefetchWallData(...); scheduler.runTaskAsynchronously(...)}）。
         * 那样 openAsync 方法体里既没有 {@code getProgressSnapshot} 字样（取数在别的
         * 方法里），又确实「提到了 prefetchWallData」—— 上面的断言全绿，
         * 而主线程照样在查库，缺陷原样复活。
         *
         * <p>因此这里用<b>括号配对</b>求出 {@code runTaskAsynchronously} 那个 lambda 的
         * 块区间，要求 {@code prefetchWallData} 的调用落在区间<b>内部</b>。
         * 同理要求渲染入口 {@code openWithData} 落在 {@code runTask(} 的块内 ——
         * 否则「异步取完数据，同步渲染」也能通过（那就等于主线程照旧做全部事情之后
         * 还要多等一次线程切换）。
         */
        @Test
        @DisplayName("A2b：prefetchWallData 必须在 runTaskAsynchronously 块内（不是同步调用）")
        void prefetchIsInsideTheAsyncBlock() {
            String body = guiBody("public void openAsync(");

            int prefetch = body.indexOf("prefetchWallData(");
            assertTrue(prefetch >= 0,
                    "openAsync(...) 里找不到 prefetchWallData(...) 喵：异步段没有取数入口。");
            assertTrue(insideAnyLambdaBlockOf(body, "runTaskAsynchronously", prefetch),
                    "prefetchWallData 不在任何 runTaskAsynchronously 的 lambda 块内 喵。\n"
                            + "R18 的核心不变量是「取数发生在后台线程」。把 prefetchWallData 同步调在"
                            + " runTaskAsynchronously 之前，源码里就没有任何取数方法名出现在 openAsync 里，"
                            + "所有基于名字的断言都会保持绿色 —— 而主线程照旧在查库。\n"
                            + "这是语义锚点检查（括号配对求块区间 + 扫全部调度点）；若为真正等价的重构"
                            + "（例如换成其它异步调度 API 或改用表达式 lambda 里的单次调用），"
                            + "请同步本 lint，但取数必须仍在后台块内。");

            int render = body.indexOf("openWithData(");
            assertTrue(render >= 0,
                    "openAsync(...) 里找不到 openWithData(...) 喵：数据取回来却没人渲染。");
            // 注意：方法体里还有一处「失败提示」的 runTask（表达式 lambda，无块），
            // 它不是渲染点。因此这里同样扫全部 runTask 调度点，取「包含 openWithData 的那个」。
            assertTrue(insideAnyLambdaBlockOf(body, "runTask", render),
                    "openWithData 不在任何 runTask 的 lambda 块内 喵。\n"
                            + "Bukkit 的物品写入必须在主线程；在异步段直接调 openInventory 会抛"
                            + " IllegalStateException（异步线程不得触碰世界/背包 API）。");
        }

        @Test
        @DisplayName("A3：取数确实发生在 prefetchWallData（反向锚点，防 lint 空转）")
        void prefetchMethodHoldsTheQueries() {
            String body = guiBody("private WallData prefetchWallData(");
            for (String needle : List.of("getProgressSnapshot", "getUnlockedAchievements",
                    "getUnlockedAtSnapshot", "getPrereqUnmetSnapshot")) {
                assertTrue(body.contains(needle),
                        "prefetchWallData 里找不到 " + needle + " 喵 —— 这一项数据没人取，"
                                + "渲染侧就会永远读到空快照（全锁 / 无进度行）。");
            }
        }

        @Test
        @DisplayName("A4：openWithData（主线程渲染段）零 DB，且确实打开了界面")
        void renderBodyDoesNoIo() {
            String body = guiBody("private void openWithData(");
            assertNoForbidden(body, "openWithData(...)");
            assertFalse(body.contains("getAchievementManager"),
                    "渲染段不得再碰 AchievementManager 喵 —— 取数必须全部留在异步段。");
            assertTrue(body.contains("openInventory"),
                    "openWithData 里找不到 openInventory 喵：渲染完没有交给玩家，界面不会出现。");
        }

        @Test
        @DisplayName("A5：回填守卫只认 holder，不认标题（gui 身份硬纪律）")
        void guardUsesHolderIdentityNotTitle() {
            String body = guiBody("private static boolean stillOnRequestedScreen(");
            assertTrue(body.contains("pluginGuiHolderOf"),
                    "回填守卫里找不到 pluginGuiHolderOf 喵 —— 必须比对界面 holder 实例。");
            assertFalse(body.contains("getTitle") || body.contains("getOriginalTitle")
                            || body.contains("title.contains") || body.contains("contains(\"生"),
                    "回填守卫里出现了标题字符串判定 喵 —— 标题是本地化文案 + 动态计数后缀，"
                            + "既不能证明「还是那张界面」，也不该参与任何判定（GuiViewKey 契约）。"
                            + "本仓曾因标题判定在改文案时静默失效，纪律是 holder 引用相等。");
        }
    }

    // ==================================================================
    //  B. 异步回填守卫
    // ==================================================================

    /**
     * 守卫的三个失效后果都不是「看起来旧」，而是<b>用一次过期点击强开界面</b>：
     * 玩家已经关掉主页/下线/换到别人主页，成就业墙仍被盖到当前屏幕上。
     *
     * <p>三个用例分别打掉一条守卫：
     * <ul>
     *   <li>B2 打掉 {@code isOnline()}；</li>
     *   <li>B3 打掉 holder 引用相等；</li>
     *   <li>B4 打掉 per-player token（连点时只渲染最新一次）。</li>
     * </ul>
     */
    @Nested
    @DisplayName("组 B —— 异步回填守卫")
    class BackfillGuards {

        @Test
        @DisplayName("B1：界面仍在原处 → 正常打开，holder 是 ACHIEVEMENT_WALL + 目标玩家")
        void opensWhenStillOnSameScreen() {
            stubHappyPath();
            Harness harness = new Harness();
            PluginGuiHolder profileHolder = profileHolder();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                harness.wire(bukkit);
                stubViewerOn(profileHolder);
                new AchievementWallGUI(plugin).open(viewer, TARGET_ID);
                harness.runAsync(0);
                harness.runMain(0);
            }

            assertNotNull(harness.openedInventory, "界面没有被创建（createInventory 从未被调用）");
            assertNotNull(harness.openedHolder, "createInventory 的第一个实参必须是 PluginGuiHolder");
            assertEquals(GuiViewKey.ACHIEVEMENT_WALL, harness.openedHolder.getKey(),
                    "holder key 写错：点击侧（SocialListener）按 key 分发，返回键会失效。");
            assertEquals(new PluginGuiHolder.GuiPayload.Target(TARGET_ID),
                    harness.openedHolder.getPayload(),
                    "holder payload 必须是目标玩家（可查看他人成就）。");
            verify(viewer, times(1)).openInventory(harness.openedInventory);
        }

        @Test
        @DisplayName("B2：异步窗口内玩家下线 → 不打开界面、不抛异常")
        void offlineViewerDoesNotOpen() {
            stubHappyPath();
            Harness harness = new Harness();
            PluginGuiHolder profileHolder = profileHolder();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                harness.wire(bukkit);
                stubViewerOn(profileHolder);
                new AchievementWallGUI(plugin).open(viewer, TARGET_ID);
                // 异步段跑完，玩家在回主线程之前下线。
                harness.runAsync(0);
                lenient().when(viewer.isOnline()).thenReturn(false);
                harness.runMain(0);
            }

            // 不抛异常由「调用正常返回」承担（JUnit 会在异常时判红）。
            verify(viewer, never()).openInventory(any(Inventory.class));
            // 纵深：连界面都不该被建出来 —— 渲染段在 isOnline 守卫生效时就已 return，
            // 而 createInventory 在守卫之后。若守卫被删，这里会拿到一张建好却没打开的界面
            //（玩家看不到，但每点一次就白造 54 格 + 22 个 ItemStack）。
            assertNull(harness.openedInventory,
                    "玩家已下线却仍执行了渲染段（createInventory 被调用）喵 —— "
                            + "isOnline 守卫失效。");
        }

        @Test
        @DisplayName("B3：玩家已换到别的界面 → 不打开，且绝不写别人那张界面")
        void switchedScreenDoesNotOpenNorTouchOtherInventory() {
            stubHappyPath();
            Harness harness = new Harness();
            PluginGuiHolder profileHolder = profileHolder();
            // 另一张插件界面（玩家在异步窗口里点进了别的地方）。
            PluginGuiHolder otherHolder = PluginGuiHolder.of(GuiViewKey.LEADERBOARD);
            Inventory otherTop = mock(Inventory.class);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                harness.wire(bukkit);
                stubViewerOn(profileHolder);
                new AchievementWallGUI(plugin).open(viewer, TARGET_ID);
                harness.runAsync(0);
                // 回主线程之前，玩家已经换到排行榜界面。
                InventoryView otherView = mock(InventoryView.class);
                lenient().when(otherView.getTopInventory()).thenReturn(otherTop);
                lenient().when(otherTop.getHolder()).thenReturn(otherHolder);
                lenient().when(viewer.getOpenInventory()).thenReturn(otherView);
                harness.runMain(0);
            }

            verify(viewer, never()).openInventory(any(Inventory.class));
            // 纵深：连「往别人界面里补一次物品」都不许发生（跨界面数据污染）。
            verify(otherTop, never()).setItem(anyInt(), any());
        }

        @Test
        @DisplayName("B4：连点两次只渲染最新一次（per-player token，旧请求作废）")
        void onlyLatestRequestRenders() {
            stubHappyPath();
            Harness harness = new Harness();
            PluginGuiHolder profileHolder = profileHolder();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                harness.wire(bukkit);
                stubViewerOn(profileHolder);
                AchievementWallGUI gui = new AchievementWallGUI(plugin);
                gui.open(viewer, TARGET_ID);
                gui.open(viewer, TARGET_ID);
                assertEquals(2, harness.asyncTasks.size(),
                        "两次打开应派发两个异步任务（token 的作用是让旧的作废，不是不派发）");
                // 两个异步段都跑完，再按派发顺序跑主线程段。
                harness.runAsync(0);
                harness.runAsync(0);
                // 两次打开共用同一个 per-player token 条目，只保留后者。于是先入队的主线程段
                // 被 token 拦下（不渲染、不入队新的任务），后入队的那个渲染一次。
                harness.runMain(0);
                assertEquals(1, harness.mainTasks.size(),
                        "第一个主线程段应被 token 拦下、只剩第二个待渲染 喵；"
                                + "剩 2 个说明 token 没拦住旧请求（会长出两张界面）。");
                harness.runMain(0);
            }

            verify(viewer, times(1)).openInventory(any(Inventory.class));
        }
    }

    // ==================================================================
    //  C. 语义不变：targetId 仍是目标玩家
    // ==================================================================

    /**
     * 异步化最容易被搞错的一点：把「谁在看」（viewer）和「看谁的」（targetId）搞混。
     * 三个直角都要钉住：<b>取数取的是 target、holder payload 记的是 target、渲染用的是 target</b>。
     */
    @Nested
    @DisplayName("组 C —— 语义不变：targetId 仍是目标玩家")
    class TargetSemantics {

        @Test
        @DisplayName("C1：取数对象是 targetId，绝不是打开者")
        void dataIsFetchedForTargetNotViewer() {
            stubHappyPath();
            Harness harness = new Harness();
            PluginGuiHolder profileHolder = profileHolder();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                harness.wire(bukkit);
                stubViewerOn(profileHolder);
                new AchievementWallGUI(plugin).open(viewer, TARGET_ID);
                harness.runAsync(0);
                harness.runMain(0);
            }

            verify(achievementManager, times(1)).getUnlockedAchievements(TARGET_ID);
            verify(achievementManager, times(1)).getProgressSnapshot(TARGET_ID);
            verify(achievementManager, never()).getUnlockedAchievements(VIEWER_ID);
            verify(achievementManager, never()).getProgressSnapshot(VIEWER_ID);
        }

        @Test
        @DisplayName("C2：标题计数用的是 target 的解锁数（不是打开者自己的）")
        void titleCountComesFromTarget() {
            stubHappyPath();
            // target 只解锁 1 个成就。
            when(achievementManager.getUnlockedAchievements(TARGET_ID))
                    .thenReturn(Set.of(Achievement.FIRST_WORD));
            Harness harness = new Harness();
            PluginGuiHolder profileHolder = profileHolder();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                harness.wire(bukkit);
                stubViewerOn(profileHolder);
                new AchievementWallGUI(plugin).open(viewer, TARGET_ID);
                harness.runAsync(0);
                harness.runMain(0);
            }

            String title = harness.openedTitle;
            assertNotNull(title, "没抓到界面标题");
            assertTrue(title.contains("(1/"),
                    "标题计数应为 target 的 1 个解锁成就 喵，实际=" + title
                            + "（若显示打开者自己的数字，说明 targetId 被换成了 viewer）。");
        }
    }

    // ==================================================================
    //  脚手架
    // ==================================================================

    /** target 的默认数据：无解锁、无进度、无前置缺失（各用例按需覆盖）。 */
    private void stubHappyPath() {
        lenient().when(plugin.isEnabled()).thenReturn(true);
        lenient().when(achievementManager.getUnlockedAchievements(TARGET_ID)).thenReturn(Set.of());
        lenient().when(achievementManager.getProgressSnapshot(TARGET_ID))
                .thenReturn(new EnumMap<>(Achievement.class));
        lenient().when(achievementManager.getUnlockedAtSnapshot(eq(TARGET_ID), any()))
                .thenReturn(new EnumMap<>(Achievement.class));
        lenient().when(achievementManager.getPrereqUnmetSnapshot(eq(TARGET_ID), any()))
                .thenReturn(EnumSet.noneOf(Achievement.class));
    }

    /** 请求发起时玩家所在的界面（他人在主页上的成就墙按钮）。 */
    private PluginGuiHolder profileHolder() {
        return PluginGuiHolder.of(GuiViewKey.PROFILE,
                new PluginGuiHolder.GuiPayload.Target(TARGET_ID));
    }

    /**
     * 让 {@code viewer.getOpenInventory().getTopInventory().getHolder()} 恒为该 holder。
     *
     * <p>生产代码在<b>两处</b>读它（请求时记基准、回填时比对），这里两处返回同一实例 ——
     * 即「玩家没换界面」。换界面的用例自己覆盖本 stub。
     */
    private void stubViewerOn(PluginGuiHolder holder) {
        Inventory top = mock(Inventory.class);
        lenient().when(top.getHolder()).thenReturn(holder);
        InventoryView view = mock(InventoryView.class);
        lenient().when(view.getTopInventory()).thenReturn(top);
        lenient().when(viewer.getOpenInventory()).thenReturn(view);
        lenient().when(viewer.isOnline()).thenReturn(true);
    }

    /**
     * 调度器替身：{@code runTaskAsynchronously} / {@code runTask} 都只<b>入队不执行</b>。
     *
     * <p>「入队不执行」是本类的关键设计：只有这样才存在「异步窗口」，
     * 才可以在两段之间改玩家状态（下线、换界面）去触发守卫。就地同步执行会把整条
     * 链路压成一次方法调用，守卫<b>永远</b>不会失效，B2/B3 会变成假绿。
     */
    private final class Harness {
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final List<Runnable> asyncTasks = new ArrayList<>();
        private final List<Runnable> mainTasks = new ArrayList<>();
        private Inventory openedInventory;
        private PluginGuiHolder openedHolder;
        private String openedTitle;

        private Harness() {
            lenient().when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        asyncTasks.add(invocation.getArgument(1));
                        return null;
                    });
            lenient().when(scheduler.runTask(eq(plugin), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        mainTasks.add(invocation.getArgument(1));
                        return null;
                    });
        }

        /** 接上调度器与 {@code Bukkit.createInventory}（记录挂上去的 holder 与标题）。 */
        private void wire(MockedStatic<Bukkit> bukkit) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        InventoryHolder holder = invocation.getArgument(0);
                        String title = invocation.getArgument(2);
                        Inventory inv = mock(Inventory.class);
                        lenient().when(inv.getSize()).thenReturn(WALL_SIZE);
                        lenient().when(inv.getHolder()).thenReturn(holder);
                        openedInventory = inv;
                        openedHolder = (holder instanceof PluginGuiHolder h) ? h : null;
                        openedTitle = title;
                        return inv;
                    });
        }

        private void runAsync(int index) {
            assertTrue(index < asyncTasks.size(),
                    "第 " + index + " 个异步任务不存在（已派发 " + asyncTasks.size() + " 个）——"
                            + "取数没有离开主线程，或调度器没被接上。");
            asyncTasks.remove(index).run();
        }

        private void runMain(int index) {
            assertTrue(index < mainTasks.size(),
                    "第 " + index + " 个主线程任务不存在（已派发 " + mainTasks.size() + " 个）——"
                            + "渲染段没有被调度，界面永远不会出现。");
            mainTasks.remove(index).run();
        }
    }

    /**
     * ItemStack 构造拦截：纯 JVM 无 RegistryAccess，{@code new ItemStack(Material.X)} 会抛
     * {@code ExceptionInInitializerError}；且 {@code clone()} 必须返回自身
     * （{@code GUIUtils.fillEmpty} 用 {@code filler.clone()}）。
     */
    private static MockedConstruction<ItemStack> itemStackMock() {
        Map<ItemStack, ItemMeta> metas = new IdentityHashMap<>();
        return mockConstruction(ItemStack.class,
                withSettings().defaultAnswer(invocation -> {
                    String method = invocation.getMethod().getName();
                    ItemStack self = (ItemStack) invocation.getMock();
                    if ("clone".equals(method)) {
                        return self;
                    }
                    if ("getItemMeta".equals(method)) {
                        return metas.computeIfAbsent(self, k -> mock(ItemMeta.class, withSettings().lenient()));
                    }
                    if ("setItemMeta".equals(method)) {
                        metas.put(self, invocation.getArgument(0));
                        return null;
                    }
                    return Answers.RETURNS_DEFAULTS.answer(invocation);
                }));
    }

    // ------------------------------------------------------------------
    //  源码 lint 脚手架（本类独立实现，刻意不复用其它 lint 类的剥注释）
    // ------------------------------------------------------------------

    /** {@code AchievementWallGUI.java} 的<b>剥注释</b>后源码。 */
    private static String guiSource() {
        Path path = srcRoot().resolve("gui").resolve("AchievementWallGUI.java");
        assertTrue(Files.exists(path), "找不到 " + path.toAbsolutePath()
                + " —— 测试须从仓库根目录运行（mvn test 默认满足）喵。");
        try {
            return stripComments(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("读取 " + path.toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    private static Path srcRoot() {
        Path root = Paths.get(System.getProperty("user.dir"), "src", "main", "java",
                "com", "letslearnenglish", "minecraftplugin");
        assertTrue(Files.isDirectory(root),
                "必须能读到源码树（工作目录应为项目根）: " + root.toAbsolutePath());
        return root;
    }

    /** 按方法签名前缀截出方法体（花括号之间的文本），签名必须唯一。 */
    private static String guiBody(String signature) {
        String source = guiSource();
        int sig = source.indexOf(signature);
        if (sig < 0) {
            fail("AchievementWallGUI.java 里找不到方法「" + signature + "」喵 —— "
                    + "方法可能被改名/改签名，请同步本 lint（否则门禁会静默失效）。");
        }
        if (source.indexOf(signature, sig + 1) >= 0) {
            fail("方法「" + signature + "」在源码中出现多次，无法唯一定位方法体喵。");
        }
        // 跨过参数表（可能跨行）再找方法体的左花括号。
        int parenOpen = source.indexOf('(', sig);
        int parenClose = matchingParen(source, parenOpen);
        assertTrue(parenClose > 0, "方法「" + signature + "」的参数表括号没有配对喵。");
        int bodyOpen = source.indexOf('{', parenClose);
        assertTrue(bodyOpen > 0, "方法「" + signature + "」之后找不到左花括号喵。");
        int bodyClose = matchingBrace(source, bodyOpen);
        assertTrue(bodyClose > 0, "方法「" + signature + "」的花括号没有配对结束喵。");
        return source.substring(bodyOpen + 1, bodyClose);
    }

    private static void assertNoForbidden(String body, String where) {
        List<String> hits = new ArrayList<>();
        for (String needle : MainThreadDoesNoIo.FORBIDDEN) {
            if (body.contains(needle)) {
                hits.add(needle);
            }
        }
        assertTrue(hits.isEmpty(),
                where + " 方法体里出现了取数调用 " + hits + " 喵。\n"
                        + "R18：成就墙的全部数据查询（成就表 / 进度快照 / 金币 / 会话日 / 统计懒加载）"
                        + "必须在后台线程完成（prefetchWallData 里），主线程只做零 DB 的渲染。\n"
                        + "看离线玩家时这些调用全部走 cache-miss 路径，在主线程上等于把 TPS 交给点击手速。\n"
                        + "这是语义锚点检查（锚方法名，不锚行号）；若为真正等价的重构"
                        + "（例如把取数抽成另一个方法并改了方法名），请同步本 lint。");
    }

    /**
     * {@code target} 偏移是否落在<b>任意一处</b> {@code needle} 调用的 lambda 块内。
     *
     * <p><b>为什么必须扫全部调度点</b>：{@code openAsync} 里有两类调度 ——
     * 主路径（{@code runTaskAsynchronously} 带块 lambda）与失败提示
     * （{@code runTask(plugin, () -> notifyLoadFailed(viewer))}，<b>表达式</b> lambda 无块）。
     * 「取 needle 之后第一个块」会落到 catch 里那个表达式 lambda 的参数表上，判定彻底错位
     * （本类第一版就踩了这个坑：算出的 async 块只有 47 字符，断言指向 catch 块）。
     *
     * <p>因此逐处搜索 {@code needle}，对每处求其箭头后的 lambda 块，只需其中<b>一个</b>
     * 块包含 {@code target}。找不到任何块（全是表达式 lambda / 方法引用）时返回 false ——
     * 保守方向：要求取数与渲染写成可界定的块形式，否则「在块内」这个断言本就无从谈起。
     */
    private static boolean insideAnyLambdaBlockOf(String body, String needle, int target) {
        if (target < 0) {
            return false;
        }
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + 1)) {
            int brace = blockOpenAfter(body, at);
            if (brace < 0) {
                continue;
            }
            int close = matchingBrace(body, brace);
            if (close > brace && target > brace && target < close) {
                return true;
            }
        }
        return false;
    }

    /**
     * 求 {@code needle} 那次调用（从 {@code at} 起）的 <b>lambda 体块</b> 左花括号索引。
     *
     * <p>正确做法：先定位参数表的配对 {@code )}，在参数表内部找 {@code ->}，再从箭头之后
     * 取第一个 {@code {}（且必须仍在参数表内）。这样表达式 lambda
     * （{@code () -> someCall()}）与方法引用都会返回 -1 —— 调用方据此跳过该处。
     */
    private static int blockOpenAfter(String body, int at) {
        if (at < 0) {
            return -1;
        }
        int argOpen = body.indexOf('(', at);
        int argClose = matchingParen(body, argOpen);
        if (argClose < 0) {
            return -1;
        }
        int arrow = body.indexOf("->", argOpen);
        if (arrow < 0 || arrow > argClose) {
            return -1;
        }
        int brace = body.indexOf('{', arrow + 2);
        return (brace > 0 && brace < argClose) ? brace : -1;
    }

    /** {@code openOpen} 处的 {@code (} → 配对的 {@code )}。 */
    private static int matchingParen(String s, int open) {        if (open < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int matchingBrace(String s, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 剥掉 Java 注释并把字符串/字符字面量内容抹成空格（保留换行与偏移）。
     *
     * <p>为什么必须剥：本类的 Javadoc 逐字引用了 {@code getProgressSnapshot} /
     * {@code getUnlockedAchievements} 这些名字来解释「为什么搬走了」。不剥的话，
     * lint 会被<b>自己的文档</b>判红 —— 那种红会诱使人删注释而不是修代码。
     *
     * <p>为什么不能一步正则：源码里有含 {@code //} 的字符串、Javadoc 里的
     * {@code Bukkit.createInventory(...)} 字面量。正则无法区分「注释里的引号」与
     * 「字符串里的斜杠」。状态机按 JLS 词法顺序处理。
     */
    private static String stripComments(String source) {
        int n = source.length();
        char[] out = source.toCharArray();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    j++;
                }
                blank(out, i, j);
                i = j;
                continue;
            }

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j < n && !(source.charAt(j) == '*' && j + 1 < n
                        && source.charAt(j + 1) == '/')) {
                    j++;
                }
                j = (j < n) ? Math.min(n, j + 2) : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"'
                    && source.charAt(i + 2) == '"') {
                int j = i + 3;
                while (j + 2 < n && !(source.charAt(j) == '"' && source.charAt(j + 1) == '"'
                        && source.charAt(j + 2) == '"')) {
                    if (source.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                j = (j + 2 < n) ? j + 3 : n;
                blank(out, i, j);
                i = j;
                continue;
            }

            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '"' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                blank(out, i, j);
                i = j;
                continue;
            }

            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '\'' || d == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                blank(out, i, j);
                i = j;
                continue;
            }

            i++;
        }
        return new String(out);
    }

    private static void blank(char[] out, int from, int to) {
        int end = Math.min(to, out.length);
        for (int k = Math.max(0, from); k < end; k++) {
            if (out[k] != '\n' && out[k] != '\r') {
                out[k] = ' ';
            }
        }
    }
}
