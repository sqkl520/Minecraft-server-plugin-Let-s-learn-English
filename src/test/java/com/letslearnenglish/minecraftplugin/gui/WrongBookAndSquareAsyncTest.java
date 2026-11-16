package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard;
import com.letslearnenglish.minecraftplugin.core.progress.ProgressManagerImpl;
import com.letslearnenglish.minecraftplugin.core.social.SocialManager;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.data.DifficultWord;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * R19-GUI2：{@code WrongBookGUI.open} 与 {@code EnglishSquareGUI.open} 的
 * 「主线程同步查库 → 两段式异步」改造验收。
 *
 * <p><b>为什么需要这个类</b>：这两个 GUI 与已异步化的
 * {@code DailyChallengeGUI} / {@code LearningCalendarGUI} / {@code ChatHistoryGUI}
 * 是同一条链上的漏网者。它们的症状不是崩溃，而是<b>点一下界面全服卡顿</b> ——
 * {@code WrongBookGUI} 在主线程跑 {@code getWrongBookEntries}（缓存 miss 时一次
 * {@code difficult_words} SELECT），{@code EnglishSquareGUI} 更狠：
 * COUNT + 本页取行 + 自己的金币，三查询串行。这类退化<b>没有任何功能症状</b>：
 * 界面照旧打开、内容照旧正确，只有 TPS 会跟着玩家手速走。所以门禁必须断言
 * 「主线程段没查库」，而不是「界面打开了」。
 *
 * <p><b>三类用例，各自针对一种失效方式</b>：
 * <ol>
 *   <li><b>源码 lint</b> —— 断言 {@code open(} / {@code openHidden(} 的方法体内
 *       <b>不直接调用</b>四个已知查库方法。它防的是「后来有人图省事把取数搬回主线程」——
 *       行为用例对这类回退不敏感（异步段被就地同步执行的夹具里，搬回去照样能过）。</li>
 *   <li><b>回填/渲染守卫</b> —— 玩家关界面 / 下线 / 被更新的请求取代后，异步结果
 *       不得再写屏幕、不得抛异常。</li>
 *   <li><b>广场分页语义</b> —— 页码推进后「屏幕」「快照」「取数 offset」三者必须同源。
 *       这条只能靠结构断言（真 Bukkit 背包不在依赖里），理由写在用例注释中。</li>
 * </ol>
 *
 * <p><b>lint 剥注释是硬要求</b>：这两个文件的注释里大量出现被禁的方法名（本次改造的
 * 说明文字本身就在讲"以前在主线程调 getWrongBookEntries"）。不剥注释的话，注释会把
 * 断言骗绿 —— 这正是本仓反复踩过的假绿形态。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("R19-GUI2：错题本 / 英语广场 打开路径异步化")
class WrongBookAndSquareAsyncTest {

    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    /** 错题本每页容量（= {@link WrongBookGUI#WORD_SLOTS}.length = 42）。 */
    private static final int WRONG_BOOK_PAGE = WrongBookGUI.WORD_SLOTS.length;
    /** 广场每页容量（= {@code EnglishSquareGUI.PLAYER_SLOTS}.length = 28，常量是 private）。 */
    private static final int SQUARE_PAGE = 28;

    @Mock
    private LetsLearnEnglish plugin;
    @Mock
    private Player viewer;
    @Mock
    private ConfigManager configManager;
    @Mock
    private FileConfiguration messages;
    @Mock
    private PlayerDataStore dataStore;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getConfigManager()).thenReturn(configManager);
        lenient().when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        lenient().when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        lenient().when(plugin.getPlayerDataStore()).thenReturn(dataStore);
        lenient().when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        // lenient：错题本用例不读名字（只有广场的自己头像槽读），严格模式下会误报多余 stub。
        lenient().when(viewer.getName()).thenReturn("Viewer");
        // 默认在线：渲染前的在线复检是「异步窗口内可能下线」的防线，各用例默认它成立；
        // 「下线」用例自行覆盖成 false（覆盖即精确模拟「异步回来时人已走」）。
        lenient().when(viewer.isOnline()).thenReturn(true);
        // 错题本渲染逐词查词库（内存索引，非 DB）：不铺会让 render 在 getWordManager() 上 NPE。
        WordManagerImpl wordManager = mock(WordManagerImpl.class);
        lenient().when(plugin.getWordManager()).thenReturn(wordManager);
        lenient().when(wordManager.getWord(anyString())).thenReturn(null);
        // 玩家当前开着本插件的界面（= 界面仍在）。「已关界面」用例自行覆盖这个 view。
        InventoryView openView = mock(InventoryView.class);
        lenient().when(viewer.getOpenInventory()).thenReturn(openView);
        // 文案：一律非 null 的占位串。ChatColor.translateAlternateColorCodes 对 null
        // 抛的是 IllegalArgumentException（Guava Preconditions），不是 NPE ——
        // 让所有 getString 都回空串最省事，且不影响任何结构断言。
        lenient().when(messages.getString(anyString())).thenReturn("");
        lenient().when(messages.getString(anyString(), anyString())).thenReturn("");
        // 模块门：本类测的是「打开路径」，不是模块门本身 —— 一律放行。
        lenient().when(configManager.isWordSystemEnabled()).thenReturn(true);
        lenient().when(configManager.isLeaderboardEnabled()).thenReturn(true);
        lenient().when(configManager.getGuiSlot(anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(configManager.getGuiMaterial(anyString(), anyString(), any(Material.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        // social 门关：广场默认落 ALL_PLAYERS（FEED 分支是同步的，不在本次范围）。
        lenient().when(plugin.getSocialManager()).thenReturn(null);
        // 广场 open() 读 progress-system.leaderboard.display-count（异步段要用的主线程事实）。
        FileConfiguration mainConfig = mock(FileConfiguration.class);
        lenient().when(configManager.getMainConfig()).thenReturn(mainConfig);
        lenient().when(mainConfig.getInt(anyString(), anyInt())).thenReturn(10);
        lenient().when(plugin.getMessageUtil()).thenReturn(mock(MessageUtil.class));
        lenient().when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
    }

    // ==================================================================
    // 1. 源码 lint：主线程段不得直接查库
    // ==================================================================

    @Nested
    @DisplayName("组 1 —— 源码 lint：open/openHidden 方法体内不得直接调用查库方法")
    class SourceLints {

        /**
         * 本批移出主线程的四个方法 —— 它们各自的 IO 依据（写在这里，方便日后核对
         * 「为什么偏偏是这四个」）：
         * <ul>
         *   <li>{@code getWrongBookEntries} → 缓存 miss 时 {@code loadDifficultWordsFromDB}
         *       做一次 {@code SELECT ... FROM difficult_words}；</li>
         *   <li>{@code countActiveSquareEntries} → {@code SELECT COUNT(*)}；</li>
         *   <li>{@code getActiveSquareEntries} → {@code SELECT ... LIMIT ? OFFSET ?}；</li>
         *   <li>{@code getCoinsEarned} → {@code SELECT total_coins_earned}。</li>
         * </ul>
         * 注意 {@code getPlayerName} <b>不在</b>名单内：它有 {@code isPrimaryThread()}
         * 守卫，非主线程直接回落 uuid，主线程名册查询是它既有的、刻意保留的行为。
         */
        private static final String[] FORBIDDEN_ON_MAIN = {
                "getWrongBookEntries",
                "countActiveSquareEntries",
                "getActiveSquareEntries",
                "getCoinsEarned",
        };

        /**
         * 错题本在主线程上不得出现的 IO 入口 —— 比 {@link #FORBIDDEN_ON_MAIN} 多两项
         * <b>包装方法</b>。
         *
         * <p>为什么必须禁包装方法：真正的 IO 是 {@code getWrongBookEntries}（内含
         * {@code loadDifficultWordsFromDB} 的 SELECT）与 {@code loadDifficultWords}，
         * 但它们在本类里只从包装方法 {@code loadWrongBook} 调用。若只禁这两个具体方法名，
         * 把 {@code loadWrongBook(playerId);} 内联回主线程时两个名字<b>一个都不出现</b>，
         * lint 会假绿 —— 实测就是如此（变异 M1）。
         *
         * <p>禁包装方法的代价：{@code openSyncFallback} 里确实会调它。但那是<b>无调度器
         * 时的测试夹具兜底</b>（生产不可达），且它已被抽成独立方法、不在被断言的两个
         * 方法体内 —— 边界干净。
         */
        private static final String[] WRONG_BOOK_IO_ON_MAIN = {
                "getWrongBookEntries",
                "loadDifficultWords",
                "loadWrongBook",
        };

        /**
         * 广场的 IO 入口（含包装方法 {@code loadSquareData}）—— 主线程段一律不许出现。
         *
         * <p>为什么要禁包装方法：{@link #FORBIDDEN_ON_MAIN} 只列四个具体 DB 方法名，
         * 而把 {@code loadSquareData(...)} 内联回 {@code open()} 的主线程段时，那四个名字
         * 一个都不出现（它们都在 {@code loadSquareData} 内部）—— lint 假绿。
         * 本数组用于 {@code open()} / {@code openHidden()} 两处判定。
         */
        private static final String[] SQUARE_IO_ON_MAIN = {
                "countActiveSquareEntries",
                "getActiveSquareEntries",
                "getCoinsEarned",
                "loadSquareData",
        };

        @Test
        @DisplayName("WrongBookGUI：open( 与 openAsync( 都不直接调 getWrongBookEntries / loadDifficultWords")
        void wrongBookOpenDoesNotQueryOnMainThread() throws IOException {
            String src = stripComments(read(
                    "src/main/java/com/letslearnenglish/minecraftplugin/gui/WrongBookGUI.java"));
            String openBody = methodBody(src, "void open(Player player, int page)");
            assertNoForbiddenCall(openBody, "WrongBookGUI.open(Player, int)",
                    WRONG_BOOK_IO_ON_MAIN);
            // open() 必须真的把活交给 openAsync（而不是「什么都不做」也能过 lint）
            assertTrue(openBody.contains("openAsync(player, page)"),
                    "WrongBookGUI.open(Player, int) 应委托 openAsync(player, page) 喵 —— "
                            + "若改成直接 return，本 lint 的「没查库」就变成「没干活」的假绿。\n"
                            + "实际方法体：\n" + openBody);

            // R20-GUI2 假绿修复：本用例原先只查 open()，而<b>真正派发异步任务的是
            // openAsync()</b>。实测把 `loadWrongBook(playerId)` 内联进 openAsync 的
            // 调度器判定之前（= 回到主线程查库），整类测试全绿 —— 因为 open() 的方法体
            // 只有一句委托，永远干净。
            //
            // 另一个更隐蔽的缺口：只禁 `getWrongBookEntries` / `loadDifficultWords`
            // <b>两个具体 DB 方法名</b>是不够的 —— 真正的 IO 入口是包装方法
            // `loadWrongBook`，变异体内联它时两个具体方法名都不出现，lint 照样绿。
            // 故 WRONG_BOOK_IO_ON_MAIN 里把包装方法一并列入。
            String openAsyncBody = methodBody(src, "void openAsync(Player player, int page)");
            assertNoForbiddenCall(openAsyncBody, "WrongBookGUI.openAsync(Player, int)",
                    WRONG_BOOK_IO_ON_MAIN);
            // 反向断言：查询必须还在（只是被关在 loadWrongBook 里）。
            String loadBody = methodBody(src, "private WrongBookLoad loadWrongBook(");
            assertTrue(loadBody.contains("getWrongBookEntries"),
                    "loadWrongBook 里必须保留 getWrongBookEntries 喵 —— 删掉它等于把错题本"
                            + "变成永远空页（「主线程没查库」变成「根本没查库」的假绿）");
        }

        @Test
        @DisplayName("EnglishSquareGUI：open( 与 openHidden( 都不直接调两个广场查询 + getCoinsEarned")
        void squareOpenDoesNotQueryOnMainThread() throws IOException {
            String src = stripComments(read(
                    "src/main/java/com/letslearnenglish/minecraftplugin/gui/EnglishSquareGUI.java"));

            String openBody = methodBody(src, "void open(Player viewer, int page, RankingMode mode)");
            assertNoForbiddenCall(openBody, "EnglishSquareGUI.open(Player, int, RankingMode)",
                    SQUARE_IO_ON_MAIN);
            // 反向断言：查询必须<b>还在</b>，只是挪到了只从异步段调用的取数方法里。
            // 没有这一条，「把查询统统删掉」也能让「主线程没查库」变绿 —— 那是把卡顿
            // 换成了功能缺失（页数恒 1、下一页按钮消失），必须挡住。
            assertTrue(asyncRegion(openBody).contains("loadSquareData"),
                    "open() 的异步段必须调用 loadSquareData 喵 —— 若异步段什么都不做，"
                            + "数据永远不回来，玩家看到的是「点了没反应」");
            String loadBody = methodBody(src, "private SquareData loadSquareData(");
            assertTrue(loadBody.contains("countActiveSquareEntries"),
                    "取数方法里必须保留 COUNT 喵 —— 它是总页数的唯一来源，删掉会让广场永远只有 1 页");
            assertTrue(loadBody.contains("getCoinsEarned"),
                    "取数方法里必须保留 getCoinsEarned 喵 —— 榜单模式下自己头像的金币数值靠它");

            // R20-GUI2 假绿修复：scheduler==null 的同步兜底分支同样<b>不许</b>出现裸查询。
            // 实测把 `countActiveSquareEntries()` 直接内联到 open() 里（= 回到主线程查库），
            // 旧用例照样全绿 —— 因为断言只看「主线程段有没有 .countActiveSquareEntries(」，
            // 而那个变异体确实是有的……但它同时<被异步段摘走>了。真正的缺口是：
            // 兜底分支被内联进 open() 时，assertNoForbiddenCall 会漏掉它（摘异步段只摘
            // runTaskAsynchronously，兜底分支在它之外）。现在兜底分支已抽成
            // openSyncFallback，本断言把它一并纳入，且要求它<b>只</b>通过 loadSquareData
            // 取数（不自己发 SQL）。
            assertTrue(openBody.contains("openSyncFallback("),
                    "open() 的无调度器兜底分支应委托 openSyncFallback(...) 喵 —— 内联回来会让"
                            + "「主线程不查库」的 lint 出现覆盖缺口");
            String syncBody = methodBody(src, "private void openSyncFallback(");
            // 注意这里仍用 FORBIDDEN_ON_MAIN（不含 loadSquareData）：兜底分支本来就
            // 要走它取数，禁它就是禁掉整条路径。
            assertNoForbiddenCall(syncBody, "EnglishSquareGUI.openSyncFallback",
                    FORBIDDEN_ON_MAIN);
            assertTrue(syncBody.contains("loadSquareData"),
                    "openSyncFallback 必须走 loadSquareData 取数喵 —— 自己发 SQL 会让"
                            + "异步段与兜底分支的查询口径漂移（页数/排行各算一遍）");

            // openHidden 当前零查库（纯静态装饰页）—— 这条是防回归：有人往里加
            // 「顺手查一下这个人的统计」就会立刻红，且它是最容易被忽略的隐私页。
            String hiddenBody = methodBody(src, "void openHidden(Player viewer, UUID targetId)");
            assertNoForbiddenCall(hiddenBody, "EnglishSquareGUI.openHidden(Player, UUID)",
                    SQUARE_IO_ON_MAIN);
            assertTrue(asyncRegion(hiddenBody).isEmpty(),
                    "openHidden 是完全静态的「隐身玩笑页」，不该有任何异步段喵 —— "
                            + "出现了说明有人把数据取数接进了这张隐私页");
        }

        @Test
        @DisplayName("lint 自检：剥注释确实生效（否则注释里的方法名会把断言骗绿）")
        void lintSelfCheckCommentStripping() throws IOException {
            String raw = read(
                    "src/main/java/com/letslearnenglish/minecraftplugin/gui/WrongBookGUI.java");
            String stripped = stripComments(raw);
            // 前置断言：被禁方法名在<b>原文</b>里必须出现过（注释里就有），
            // 否则下一条断言「剥完就没了」不构成任何证据。
            assertTrue(raw.contains("getWrongBookEntries"),
                    "前置条件失败：WrongBookGUI 源码里已找不到 getWrongBookEntries 字样 —— "
                            + "本自检失去了判别力，请确认该方法是否被整体删除/改名");
            // 剥注释后，该方法名只应出现在 openAsync/loadWrongBook 这些允许的地方 ——
            // 关键验证是「剥注释没有把代码一起吃光」。
            assertTrue(stripped.contains("loadWrongBook"),
                    "剥注释把代码也吃掉了（找不到 loadWrongBook）—— lint 的 methodBody 会因此失准");
        }

        @Test
        @DisplayName("lint 自检：asyncRegion 真的能把异步段摘出来（否则「主线程没查库」恒真）")
        void lintSelfCheckAsyncRegionExtraction() {
            String body = "{\n"
                    + "  preload();\n"
                    + "  scheduler.runTaskAsynchronously(plugin, () -> {\n"
                    + "    store.countActiveSquareEntries();\n"
                    + "  });\n"
                    + "  openInventory(inv);\n"
                    + "}";
            String region = asyncRegion(body);
            assertTrue(region.contains("countActiveSquareEntries"),
                    "asyncRegion 没摘出 lambda 体 —— 于是所有查询都会被当成「不在主线程」，"
                            + "lint 恒真、零判别力");
            assertFalse(region.contains("preload()"),
                    "asyncRegion 把 lambda 之外的代码也摘进来了（边界算错）—— 那样主线程查库会被漏掉");
            // 变异：把查询挪到 lambda 之前 = 回到主线程 → 必须被 assertNoForbiddenCall 抓住
            String mutated = "{\n"
                    + "  store.countActiveSquareEntries();\n"
                    + "  scheduler.runTaskAsynchronously(plugin, () -> { render(); });\n"
                    + "}";
            assertTrue(Pattern.compile("\\.countActiveSquareEntries\\s*\\(")
                            .matcher(withoutAsyncRegions(mutated)).find(),
                    "把查询挪回主线程后 withoutAsyncRegions 仍报「干净」—— lint 抓不住最关键的回归");
        }

        /**
         * 断言某方法体的<b>主线程段</b>内不存在对给定方法名的调用（形如 {@code .name(}）。
         *
         * <p><b>为什么必须先摘掉异步段</b>：本轮改造之后，这些查询就<b>应该</b>出现在
         * {@code open()} 里 —— 只不过在 {@code runTaskAsynchronously} 的 lambda 内。
         * 对着整个方法体做子串搜索会把「正确的异步调用」判成「错误的同步调用」，
         * 于是下一个人最自然的处置就是删掉这条 lint，防护直接归零。
         *
         * <p>因此判据精确到「线程」：把每个 {@code runTaskAsynchronously(...)} 的
         * lambda 体挖掉（{@link #asyncRegion}/{@link #withoutAsyncRegions}），
         * 剩下的就是主线程会执行的代码，再对它断言。挖空的边界算错会让 lint 恒真或恒假，
         * 所以 {@code lintSelfCheckAsyncRegionExtraction} 单独把边界钉住。
         */
        private void assertNoForbiddenCall(String body, String where, String... forbidden) {
            String mainThreadOnly = withoutAsyncRegions(body);
            for (String name : forbidden) {
                assertFalse(Pattern.compile("\\." + Pattern.quote(name) + "\\s*\\(")
                                .matcher(mainThreadOnly).find(),
                        where + " 的<b>主线程段</b>里出现了同步查库调用 ." + name + "( 喵 —— "
                                + "这四个方法都会在主线程产生 JDBC 往返（见 FORBIDDEN_ON_MAIN 的注释）。"
                                + "广场打开一次就是 COUNT + 取行 + 金币三条，玩家点一下全服卡顿。\n"
                                + "修法：把它挪进 runTaskAsynchronously 的 lambda 里"
                                + "（参考 openAsync 的既有形状），主线程段只渲染。\n"
                                + "主线程段实际内容：\n" + mainThreadOnly);
            }
        }

        /**
         * 摘出方法体内所有 {@code runTaskAsynchronously(...)} 的 lambda 体（拼成一个串）。
         *
         * <p>做法：定位每次调用的左括号，用括号配对找到实参结束，再把这段实参文本摘出来。
         * 返回的串只用于「异步段里确有这条调用」这种正向断言。
         */
        private String asyncRegion(String body) {
            StringBuilder out = new StringBuilder();
            int from = 0;
            while (true) {
                int at = body.indexOf("runTaskAsynchronously(", from);
                if (at < 0) break;
                int open = at + "runTaskAsynchronously".length();
                int close = matchingParen(body, open);
                if (close < 0) break;
                out.append(body, open, close + 1).append('\n');
                from = close + 1;
            }
            return out.toString();
        }

        /** 方法体剔除全部异步段之后剩下的部分（= 主线程会执行的代码）。 */
        private String withoutAsyncRegions(String body) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (true) {
                int at = body.indexOf("runTaskAsynchronously(", i);
                if (at < 0) {
                    out.append(body.substring(i));
                    break;
                }
                int open = at + "runTaskAsynchronously".length();
                int close = matchingParen(body, open);
                if (close < 0) {
                    out.append(body.substring(i));
                    break;
                }
                // 保留调用名本身（便于阅读），但挖掉整个实参（含 lambda 体）
                out.append(body, i, open + 1).append("/* async */)");
                i = close + 1;
            }
            return out.toString();
        }

        /** 从 leftParen 起做圆括号配对（跳过字符串/字符字面量），返回匹配右括号下标（找不到 -1）。 */
        private int matchingParen(String s, int leftParen) {
            int depth = 0;
            int i = leftParen;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"' || c == '\'') {
                    char quote = c;
                    i++;
                    while (i < s.length()) {
                        char d = s.charAt(i);
                        if (d == '\\') {
                            i += 2;
                            continue;
                        }
                        i++;
                        if (d == quote) break;
                    }
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (--depth == 0) return i;
                }
                i++;
            }
            return -1;
        }
    }

    // ==================================================================
    // 2. 守卫：异步结果不得写入已失效的界面
    // ==================================================================

    @Nested
    @DisplayName("组 2 —— 守卫：界面关闭 / 玩家下线 / 被更新请求取代时，回填不得生效")
    class StaleGuards {

        /**
         * 玩家在异步窗口内下线 → 不得开界面、不得抛异常。
         *
         * <p>判别力：删掉回填里的 {@code if (!player.isOnline()) return;}，
         * {@code openInventory} 会被调到（mock 不抛，但 verify 会红）。
         */
        @Test
        @DisplayName("错题本：异步回来时玩家已下线 → 不开界面、不抛异常")
        void wrongBookOfflineViewerIsDropped() {
            lenient().when(dataStore.getWrongBookEntries(VIEWER_ID)).thenReturn(entries(3));
            when(plugin.isEnabled()).thenReturn(true);
            // 异步段跑完时玩家已下线 —— openAsync 里那唯一一道 online 复检必须拦住渲染。
            when(viewer.isOnline()).thenReturn(false);

            Harness harness = new Harness();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                new WrongBookGUI(plugin).open(viewer, 1);
            }

            verify(viewer, never()).openInventory(any(Inventory.class));
            assertEquals(0, harness.renderCount.get(),
                    "玩家已下线，渲染段必须整段放弃（屏幕不存在，着色/发包都不该发生）喵");
        }

        /**
         * 玩家关掉界面（没下线）后异步结果回来 → 不得凭空把界面重新打开。
         *
         * <p>这条是「关界面即放弃」的语义：异步窗口只有几十毫秒，但玩家手速完全够
         * 关掉再点别的。如果回填无脑 {@code openInventory}，玩家会看到界面「自己弹回来」。
         *
         * <p><b>R20-GUI2 假绿修复（关键）</b>：旧版断的是
         * {@code verify(otherTop, never()).setItem(...)} —— 可 render 把物品写进的是
         * <b>自己 createInventory 出来的那张界面</b>，从来不会碰 {@code otherTop}。
         * 于是这条断言<b>恒真</b>：实测把 holder 守卫整行删掉，它照样绿。
         * 正确的信号是「有没有走到 render」，即 {@code Bukkit.createInventory} 被调用
         * 几次（render 的最后一行就是 {@code player.openInventory(inv)}）。
         * 这里改用 renderCount + openInventory 双断言。
         */
        @Test
        @DisplayName("错题本：异步回来前玩家已关界面（未下线）→ 不重新弹出界面")
        void wrongBookClosedInventoryIsNotReopened() {
            when(dataStore.getWrongBookEntries(VIEWER_ID)).thenReturn(entries(3));
            when(plugin.isEnabled()).thenReturn(true);
            when(viewer.isOnline()).thenReturn(true);

            // 请求发起时玩家确实停在错题本上（arrival holder 非 null —— 这是 holder
            // 守卫有判别力的前提；若发起时就不在插件界面，守卫两侧都是 null，测不出东西）。
            PluginGuiHolder arrival = PluginGuiHolder.of(GuiViewKey.WRONG_BOOK,
                    new PluginGuiHolder.GuiPayload.Page(1));
            InventoryView arrivalView = viewWithHolder(arrival);
            lenient().when(viewer.getOpenInventory()).thenReturn(arrivalView);

            // 回填前 ESC 关掉界面：上半区 holder 不再是 PluginGuiHolder。
            InventoryView closedView = viewWithHolder(null);

            Harness harness = new Harness();
            harness.onAfterDispatch = () ->
                    lenient().when(viewer.getOpenInventory()).thenReturn(closedView);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                new WrongBookGUI(plugin).open(viewer, 1);
            }

            assertEquals(0, harness.renderCount.get(),
                    "玩家已关界面，render 不该被走到（createInventory 次数 = 渲染次数）喵 —— "
                            + "非 0 说明 holder 回填守卫失效，界面会自己弹回来");
            verify(viewer, never()).openInventory(any(Inventory.class));
        }

        /**
         * <b>holder 守卫的判别力</b>：玩家在异步窗口内从「错题本」切到了<b>另一张插件界面</b>
         * （两者 holder 都是非 null，但<b>不是同一个实例</b>）。
         *
         * <p>为什么必须单列一条：只测「关界面（holder = null）」时，一个
         * {@code if (pluginGuiHolderOf(player) == null) return;} 的<b>弱实现</b>也能过 ——
         * 但它会让「切到别的插件界面」这条真实路径失守，界面被强行盖回来。
         * 本条要求判据是<b>引用相等</b>（同一个 holder 实例），而不是「非 null」。
         *
         * <p>顺带钉住「绝不能用标题比较」：两张界面标题都非空，任何基于标题的判定都会
         * 在这里给出错误答案（见 {@code GuiViewKey} 契约：身份只认 holder）。
         */
        @Test
        @DisplayName("错题本：异步窗口内切到另一张插件界面 → 同样不强行覆盖（判据是 holder 引用相等）")
        void wrongBookSwitchedToAnotherPluginGuiIsNotOverwritten() {
            when(dataStore.getWrongBookEntries(VIEWER_ID)).thenReturn(entries(3));
            when(plugin.isEnabled()).thenReturn(true);
            when(viewer.isOnline()).thenReturn(true);

            // 请求发起时：玩家在错题本上（arrival holder）。
            PluginGuiHolder arrival = PluginGuiHolder.of(GuiViewKey.WRONG_BOOK,
                    new PluginGuiHolder.GuiPayload.Page(1));
            InventoryView arrivalView = viewWithHolder(arrival);
            lenient().when(viewer.getOpenInventory()).thenReturn(arrivalView);

            // 回填前：玩家已进了别的插件界面（holder 非 null，但不是同一个实例）。
            PluginGuiHolder otherHolder = PluginGuiHolder.of(GuiViewKey.LEADERBOARD);
            InventoryView otherView = viewWithHolder(otherHolder);

            Harness harness = new Harness();
            harness.onAfterDispatch = () ->
                    lenient().when(viewer.getOpenInventory()).thenReturn(otherView);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                new WrongBookGUI(plugin).open(viewer, 1);
            }

            assertEquals(0, harness.renderCount.get(),
                    "玩家已切到别的插件界面，本次回填必须作废喵 —— 渲染了 "
                            + harness.renderCount.get() + " 次说明判据不是「holder 引用相等」"
                            + "（弱实现 `!= null` 或标题比较都会在这里失守）");
            verify(viewer, never()).openInventory(any(Inventory.class));
        }

        /**
         * 连点翻页：先发起的「第 2 页」比后发起的「第 3 页」晚回来 → 只能落地第 3 页。
         *
         * <p>token 的判别力就在这里：没有 token 时，两次渲染都会执行，屏幕内容取决于
         * 线程调度顺序（真机上就是随机闪到上一页）。
         */
        @Test
        @DisplayName("错题本：连点翻页 → 后发起者胜（旧请求作废，holder 页码是最后一次请求）")
        void wrongBookLastRequestWins() {
            // 43 条 → 2 页；85 条 → 3 页。两次 open 用不同数据源，最后落地的必须是后者。
            when(dataStore.getWrongBookEntries(VIEWER_ID)).thenReturn(entries(WRONG_BOOK_PAGE + 1));
            when(plugin.isEnabled()).thenReturn(true);
            when(viewer.isOnline()).thenReturn(true);
            // 异步任务先攒起来，全部攒完再倒序执行 —— 精确模拟「旧请求晚回来」。
            Harness harness = new Harness();
            harness.deferAsync = true;
            harness.deferMain = true;

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);

                WrongBookGUI gui = new WrongBookGUI(plugin);
                gui.open(viewer, 2);      // 先发起
                gui.open(viewer, 3);      // 后发起（page 会被 clamp 到 2，但 token 是新的）

                // 先把两次异步段都跑掉（顺序无所谓，关键是 token 已在主线程登记）
                for (Runnable r : new ArrayList<>(harness.asyncTasks)) r.run();
                // 再倒序执行回主线程段：让「旧请求」<b>后</b>落地 —— 这正是竞态的形状
                List<Runnable> mains = new ArrayList<>(harness.mainTasks);
                Collections.reverse(mains);
                for (Runnable r : mains) r.run();
            }

            assertEquals(1, harness.renderCount.get(),
                    "倒序落地时只该有最后一次请求真正渲染喵 —— 渲染了 %d 次说明 token 判定失效"
                            .formatted(harness.renderCount.get()));
            PluginGuiHolder holder = harness.lastHolder.get();
            assertNotNull(holder, "最后一次请求必须真的渲染出界面");
            assertEquals(new PluginGuiHolder.GuiPayload.Page(2), holder.getPayload(),
                    "43 条词只有 2 页，page=3 被 clamp 到 2 喵 —— holder 页码必须是 clamp 后的值，"
                            + "否则翻页按钮/删词重开会指向不存在的页");
        }

        /**
         * <b>token 守卫的正向判别力</b>：旧请求<b>先</b>落地、新请求<b>后</b>落地时，
         * 屏幕必须停在新请求那一页 —— 且旧请求那一次必须被 token 拦掉。
         *
         * <p>为什么需要与上一条成对：上一条（倒序落地）只能证明「总共渲染了 1 次」。
         * 一个<b>没有 token</b>的实现如果恰好让最后一次请求最后执行，也会渲染 2 次 →
         * 上一条会红。但反过来，只保留「谁后执行谁赢」的错觉也能过 —— 实测删掉 token
         * 判定（保留倒序场景）时<b>该用例仍然全绿</b>，因为倒序的最后一次恰好是新请求，
         * renderCount 因为前面被 holder 守卫拦下而没有变成 2……换言之那条用例测的
         * 是 holder + 执行顺序，不是 token。
         *
         * <p>本条把两种顺序都跑一遍，要求<b>结果恒等于「新请求那一页」</b>：
         * <ul>
         *   <li>顺序 A（旧先落地、新后落地）→ 2 页变 1 页？不，应停在新的；
         *   <li>顺序 B（新先落地、旧后落地）→ 旧请求必须被 token 拒掉，屏幕不退回旧页。
         * </ul>
         * 只有真正比较 token 的实现才能同时满足两者。
         */
        @Test
        @DisplayName("错题本：token 判别力 —— 旧请求无论先落地还是后落地，都不得覆盖新请求的结果")
        void wrongBookTokenRejectsStaleRequestInBothOrders() {
            // 43 条 → 2 页。第一次请求 page=1（旧），第二次 page=2（新）。
            when(dataStore.getWrongBookEntries(VIEWER_ID)).thenReturn(entries(WRONG_BOOK_PAGE + 1));
            when(plugin.isEnabled()).thenReturn(true);
            when(viewer.isOnline()).thenReturn(true);
            // 玩家全程停在错题本上（同一个 holder 实例 —— 本用例考的是 token，不是 holder；
            // 但 arrival 必须非 null 才能让 holder 守卫放行）。
            PluginGuiHolder onScreen = PluginGuiHolder.of(GuiViewKey.WRONG_BOOK,
                    new PluginGuiHolder.GuiPayload.Page(1));
            Inventory top = mock(Inventory.class);
            doReturn(onScreen).when(top).getHolder();
            InventoryView view = mock(InventoryView.class);
            doReturn(top).when(view).getTopInventory();

            for (boolean oldLandsLast : new boolean[]{false, true}) {
                // 每轮开始都要重新钉一次：上一轮的 stubCreateInventory 会把
                // viewer.getOpenInventory 改指到它造出来的那张界面（"界面已打开"），
                // 不重钉的话本轮 arrivalHolder 会拿到上一轮渲染的 holder →
                // 守卫直接拒掉一切 → 用例退化成「恒不渲染」的假绿。
                lenient().when(viewer.getOpenInventory()).thenReturn(view);

                Harness harness = new Harness();
                harness.deferAsync = true;
                harness.deferMain = true;

                try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                     MockedConstruction<ItemStack> ignored = itemStackMock()) {
                    bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                    stubCreateInventory(bukkit, harness);

                    WrongBookGUI gui = new WrongBookGUI(plugin);
                    gui.open(viewer, 1);   // 旧请求（第 1 页）
                    gui.open(viewer, 2);   // 新请求（第 2 页）—— 必须赢

                    for (Runnable r : new ArrayList<>(harness.asyncTasks)) r.run();

                    List<Runnable> mains = new ArrayList<>(harness.mainTasks);
                    // harness.mainTasks 顺序 = 请求发起顺序 = [旧, 新]。
                    // oldLandsLast=true 时倒序执行（旧请求最后落地）—— 这才是真竞态。
                    if (oldLandsLast) {
                        Collections.reverse(mains);
                    }
                    for (Runnable r : mains) {
                        // 同上：每次回主线程前把「玩家当前界面」钉回 view。
                        lenient().when(viewer.getOpenInventory()).thenReturn(view);
                        r.run();
                    }
                }

                assertEquals(1, harness.renderCount.get(),
                        (oldLandsLast ? "顺序 B（新先落地、旧后落地）" : "顺序 A（旧先落地、新后落地）")
                                + "：只该有 1 次渲染喵 —— token 生效时旧请求必被拒；"
                                + "实测渲染了 " + harness.renderCount.get() + " 次");
                PluginGuiHolder rendered = harness.lastHolder.get();
                assertNotNull(rendered, "必须渲染出界面（顺序 "
                        + (oldLandsLast ? "B" : "A") + "）");
                assertEquals(new PluginGuiHolder.GuiPayload.Page(2), rendered.getPayload(),
                        (oldLandsLast ? "顺序 B" : "顺序 A")
                                + "：最终屏幕必须停在<b>新请求</b>的第 2 页喵 —— "
                                + "拿到第 1 页说明旧请求覆盖了新请求（token 失效，"
                                + "玩家翻页后屏幕自己跳回上一页）");
            }
        }

        /**
         * 取数失败 → 只发可重试提示、<b>不开界面</b>，且不谎报「还没有错题」。
         *
         * <p>这条钉住的是 R4-15-EH-15-02 的语义不能被异步化顺手改掉：失败时玩家
         * 停在来源界面 + 一句 {@code daily.load-failed}，而不是一个空洞的错题本。
         */
        @Test
        @DisplayName("错题本：读失败 → 发 daily.load-failed，不开界面（不谎报空错题本）")
        void wrongBookReadFailureShowsRetryableMessage() {
            lenient().when(dataStore.getWrongBookEntries(VIEWER_ID))
                    .thenThrow(new RuntimeException("boom"));
            when(plugin.isEnabled()).thenReturn(true);
            when(viewer.isOnline()).thenReturn(true);
            MessageUtil util = mock(MessageUtil.class);
            when(util.getPlayerMessage(any(), anyString(), any(String[].class)))
                    .thenReturn("LOAD-FAILED");
            when(plugin.getMessageUtil()).thenReturn(util);

            Harness harness = new Harness();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                new WrongBookGUI(plugin).open(viewer, 1);
            }

            verify(viewer).sendMessage("LOAD-FAILED");
            assertEquals(0, harness.renderCount.get(),
                    "读失败时绝不能开界面喵 —— 空界面会让玩家以为「错题都攻克了」，"
                            + "把「不知道」说成「没有」是谎报");
        }

        /**
         * 广场：玩家在异步窗口内下线 → 不建界面、不抛异常。
         */
        @Test
        @DisplayName("广场：异步回来时玩家已下线 → 不建界面、不抛异常")
        void squareOfflineViewerIsDropped() {
            when(plugin.isEnabled()).thenReturn(true);
            lenient().when(dataStore.countActiveSquareEntries()).thenReturn(0);
            lenient().when(dataStore.getActiveSquareEntries(anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            lenient().when(dataStore.getPlayerStats(VIEWER_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());
            // 渲染前那道 online 复检必须拦住：玩家在异步窗口内下线。
            when(viewer.isOnline()).thenReturn(false);

            Harness harness = new Harness();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                new EnglishSquareGUI(plugin).open(viewer, 1, Leaderboard.RankingMode.ALL_PLAYERS);
            }

            verify(viewer, never()).openInventory(any(Inventory.class));
            assertEquals(0, harness.renderCount.get(),
                    "玩家已下线却仍执行了渲染段（createInventory 被调用）喵 —— isOnline 守卫失效");
        }

        /**
         * 广场：玩家在异步窗口内关掉界面（仍在线）→ 不得把界面强行弹回来。
         *
         * <p><b>R20-GUI2 补测</b>：旧 14 例<b>完全没有</b>覆盖这条 —— 实测把广场的
         * {@code stillOnArrivalScreen} 整行删掉，整类测试全绿。而症状是实打实的：
         * 玩家翻页后立刻 ESC，几十毫秒后屏幕被广场盖回去。
         */
        @Test
        @DisplayName("广场：异步回来前玩家已关界面（未下线）→ 不重新弹出界面")
        void squareClosedInventoryIsNotReopened() {
            when(plugin.isEnabled()).thenReturn(true);
            lenient().when(dataStore.countActiveSquareEntries()).thenReturn(0);
            lenient().when(dataStore.getActiveSquareEntries(anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            lenient().when(dataStore.getPlayerStats(VIEWER_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());
            when(viewer.isOnline()).thenReturn(true);

            // 发起时玩家在广场上；回填前 ESC 关掉（top holder 不再是 PluginGuiHolder）。
            PluginGuiHolder arrival = PluginGuiHolder.of(GuiViewKey.SQUARE,
                    new PluginGuiHolder.GuiPayload.SquarePage(1, "all"));
            InventoryView arrivalView = viewWithHolder(arrival);
            lenient().when(viewer.getOpenInventory()).thenReturn(arrivalView);
            InventoryView closedView = viewWithHolder(null);

            Harness harness = new Harness();
            harness.onAfterDispatch = () ->
                    lenient().when(viewer.getOpenInventory()).thenReturn(closedView);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                new EnglishSquareGUI(plugin).open(viewer, 1,
                        Leaderboard.RankingMode.ALL_PLAYERS);
            }

            assertEquals(0, harness.renderCount.get(),
                    "玩家已关界面，广场 render 不该被走到喵 —— 非 0 说明 holder 回填守卫失效，"
                            + "界面会自己弹回来");
            verify(viewer, never()).openInventory(any(Inventory.class));
        }

        /**
         * 广场：<b>holder 判据必须是引用相等，不能是标题比较</b>。
         *
         * <p>两张界面标题都含「英语广场」，唯一的结构差异是 holder 实例。
         * 一个 {@code title.contains("英语广场")} 的实现会在这里蒙对 → 界面被强行覆盖。
         *
         * <p>这条是 R20-GUI2 变异验证的直接产物：把守卫换成标题比较后，旧 14 例全绿；
         * 本条必须红，否则「身份只认 holder」这条仓库硬纪律在生产代码里没有任何守卫。
         */
        @Test
        @DisplayName("广场：守卫用标题比较而非 holder 引用 → 本条必须红（切到另一张广场界面）")
        void squareTitleBasedGuardIsRejected() {
            when(plugin.isEnabled()).thenReturn(true);
            lenient().when(dataStore.countActiveSquareEntries()).thenReturn(0);
            lenient().when(dataStore.getActiveSquareEntries(anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            lenient().when(dataStore.getPlayerStats(VIEWER_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());
            when(viewer.isOnline()).thenReturn(true);

            // 发起时：广场第 1 页。
            PluginGuiHolder arrival = PluginGuiHolder.of(GuiViewKey.SQUARE,
                    new PluginGuiHolder.GuiPayload.SquarePage(1, "all"));
            InventoryView arrivalView = viewWithHolder(arrival);
            lenient().when(viewer.getOpenInventory()).thenReturn(arrivalView);

            // 回填前：玩家开着的<b>另一张</b>广场界面（holder 实例不同 = 不是同一张屏幕）。
            PluginGuiHolder otherHolder = PluginGuiHolder.of(GuiViewKey.SQUARE,
                    new PluginGuiHolder.GuiPayload.SquarePage(3, "weekly"));
            InventoryView otherView = viewWithHolder(otherHolder);

            Harness harness = new Harness();
            harness.onAfterDispatch = () ->
                    lenient().when(viewer.getOpenInventory()).thenReturn(otherView);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                new EnglishSquareGUI(plugin).open(viewer, 1,
                        Leaderboard.RankingMode.ALL_PLAYERS);
            }

            assertEquals(0, harness.renderCount.get(),
                    "两张界面标题相同但不是同一张（holder 实例不同）—— 判据必须是引用相等喵。"
                            + "渲染了 " + harness.renderCount.get() + " 次说明用了标题比较："
                            + "玩家在第 3 页翻页时，第 1 页那次打开会把他拽回第 1 页");
        }

        /**
         * 广场：异步期间快照必须保持「未渲染」，不能谎称「已渲染第 1 页」。
         *
         * <p>这是本轮<b>最关键的守卫</b>：{@code SocialListener} 在 {@code open()} 返回后
         * 立刻把本实例登记为快照，复用判据是 {@code getCurrentPage() == page}。
         * 若未渲染时页码是 1，点击第 1 页的头像就会蒙对判据 → 拿空列表反查下标 →
         * 头像点击全部静默失效（而且第 2 页正常，行为随页码漂移，最难排查）。
         */
        @Test
        @DisplayName("广场：异步未落地时 getCurrentPage() 恒不等于任何真实页码（防陈旧快照蒙对）")
        void squareSnapshotIsInvalidatedUntilRendered() {
            // 异步段挂起 → isEnabled 也不会被读，故 lenient
            lenient().when(plugin.isEnabled()).thenReturn(true);
            // 异步段被挂起（本用例的核心手法）→ 这些取数桩一次都不会被消费，
            // 故 lenient：严格模式会把「刻意不跑」误报成「多余代码」。
            lenient().when(dataStore.countActiveSquareEntries()).thenReturn(0);
            lenient().when(dataStore.getActiveSquareEntries(anyInt(), anyInt()))
                    .thenReturn(Collections.emptyList());
            lenient().when(dataStore.getPlayerStats(VIEWER_ID))
                    .thenReturn(new PlayerDataStore.PlayerStats());

            Harness harness = new Harness();
            harness.deferAsync = true; // 异步段挂起 = 「open() 已返回、数据还没回来」的窗口

            EnglishSquareGUI gui = new EnglishSquareGUI(plugin);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                gui.open(viewer, 1, Leaderboard.RankingMode.ALL_PLAYERS);
            }

            assertTrue(harness.asyncDispatched.get(), "异步派发必须发生（否则数据永远不回来）");
            assertFalse(harness.asyncRan.get(), "本用例故意挂起异步段，它不该已经跑过");

            assertNotEquals(1, gui.getCurrentPage(),
                    "异步未落地时快照页码仍是 1 喵 —— 于是 SocialListener 的"
                            + "「快照页码 == 屏幕页码」判据会被蒙对，点击侧拿空列表反查下标，"
                            + "第 1 页头像点击全部静默失效（第 2 页却正常，极难排查）。"
                            + "未渲染必须是 0（PAGE_UNRENDERED）这种「不等于任何真实页码」的哨兵。");
            assertTrue(gui.getCurrentPlayerIds().isEmpty(),
                    "未渲染时快照列表必须为空（否则会拿上一页的 id 去点这一页的头像）");
        }

        /**
         * 广场：异步正常落地后，快照必须与屏幕同源（页码 + id 列表都对得上）。
         */
        @Test
        @DisplayName("广场：落地后快照 = 屏幕（页码为 clamp 结果，id 列表就是本页切片）")
        void squareSnapshotMatchesRenderedPage() {
            when(plugin.isEnabled()).thenReturn(true);
            // 29 行 → 2 页；请求 page=9 应 clamp 到 2
            when(dataStore.countActiveSquareEntries()).thenReturn(SQUARE_PAGE + 1);
            List<Leaderboard.LeaderboardEntry> pageRows = rows(1, 1);
            when(dataStore.getActiveSquareEntries(anyInt(), anyInt())).thenReturn(pageRows);
            when(dataStore.getPlayerStats(VIEWER_ID)).thenReturn(new PlayerDataStore.PlayerStats());

            Harness harness = new Harness();
            EnglishSquareGUI gui = new EnglishSquareGUI(plugin);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                gui.open(viewer, 9, Leaderboard.RankingMode.ALL_PLAYERS);
            }

            assertEquals(2, gui.getCurrentPage(),
                    "29 行只有 2 页，page=9 必须 clamp 到 2 喵（快照页码是 clamp 后的屏幕事实）");
            assertEquals(1, gui.getCurrentPlayerIds().size(),
                    "快照 id 列表必须与本次渲染的行逐下标对应");
            assertEquals(pageRows.get(0).playerId, gui.getCurrentPlayerIds().get(0));
        }
    }

    // ==================================================================
    // 3. 广场分页语义
    // ==================================================================

    @Nested
    @DisplayName("组 3 —— 广场分页语义：页码 → offset → 屏幕 → 快照 同源")
    class SquarePaging {

        /**
         * 页码推进后，取数 offset 必须真的跟着走（{@code (page-1)*28}），
         * 且落地到 holder 的页码与传给数据层的一致。
         *
         * <p><b>为什么用「捕获实参」而不是渲染内容</b>：真 Bukkit 背包不在依赖里
         * （无 MockBukkit），逐槽断言内容需要把 Inventory 假件做到能回放 SkullMeta，
         * 成本远高于收益，且断言的仍是同一条算术。这里改为钉住<b>边界的数值</b>：
         * 第 2 页请求 → 数据层必须收到 offset=28；holder 必须记第 2 页。
         * 屏幕内容对应正确页由「offset 正确 + 数据层按 offset 切页」共同保证，
         * 后者由 {@code ActiveSquarePagingTest}（真 SQLite）负责。
         */
        @Test
        @DisplayName("第 2 页：offset=28、holder 页码=2、快照页码=2（三者同源）")
        void secondPageUsesOffsetAndCarriesPage() {
            when(plugin.isEnabled()).thenReturn(true);
            when(dataStore.countActiveSquareEntries()).thenReturn(SQUARE_PAGE * 3);
            List<Leaderboard.LeaderboardEntry> secondPageRows = rows(SQUARE_PAGE, SQUARE_PAGE);
            when(dataStore.getActiveSquareEntries(anyInt(), anyInt())).thenReturn(secondPageRows);
            when(dataStore.getPlayerStats(VIEWER_ID)).thenReturn(new PlayerDataStore.PlayerStats());

            Harness harness = new Harness();
            EnglishSquareGUI gui = new EnglishSquareGUI(plugin);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                gui.open(viewer, 2, Leaderboard.RankingMode.ALL_PLAYERS);
            }

            // 1) 数据层收到的 offset 必须是 28（第 2 页），不是 0 也不是 56
            verify(dataStore).getActiveSquareEntries(SQUARE_PAGE, SQUARE_PAGE);
            // 2) holder 的页码 = 屏幕页码 = 快照页码
            PluginGuiHolder holder = harness.lastHolder.get();
            assertNotNull(holder, "第 2 页必须真的建了界面 (renderCount=" + harness.renderCount.get() + ", asyncRan=" + harness.asyncRan() + ")");
            assertEquals(new PluginGuiHolder.GuiPayload.SquarePage(2, "all"), holder.getPayload(),
                    "holder 必须携带「第 2 页 + all 模式」喵 —— 点击侧只读 payload，"
                            + "它一旦漂移，翻页后的目录反算会整体错位");
            assertEquals(2, gui.getCurrentPage(),
                    "快照页码必须与屏幕页码同为 2 喵 —— 二者不等时点击侧会按错页重建");
            // 3) 快照 id 列表 = 本次取到的行
            assertEquals(secondPageRows.size(), gui.getCurrentPlayerIds().size(),
                    "快照粒度必须是「本页切片」（28 行），不是全量列表");
        }

        /**
         * 翻到第 1 页：offset 必须是 0。
         *
         * <p>与上一条成对 —— 只有「第 2 页 = 28」时，一个恒 28 的错误实现也能通过。
         */
        @Test
        @DisplayName("第 1 页：offset=0（与第 2 页成对，防「恒 28」的错误实现）")
        void firstPageUsesZeroOffset() {
            when(plugin.isEnabled()).thenReturn(true);
            when(dataStore.countActiveSquareEntries()).thenReturn(SQUARE_PAGE * 3);
            when(dataStore.getActiveSquareEntries(anyInt(), anyInt())).thenReturn(rows(0, 3));
            when(dataStore.getPlayerStats(VIEWER_ID)).thenReturn(new PlayerDataStore.PlayerStats());

            Harness harness = new Harness();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                new EnglishSquareGUI(plugin).open(viewer, 1, Leaderboard.RankingMode.ALL_PLAYERS);
            }

            verify(dataStore).getActiveSquareEntries(SQUARE_PAGE, 0);
        }

        /**
         * 总页数必须来自 COUNT —— 否则切片下永远只有 1 页（「下一页」按钮消失）。
         *
         * <p>用 29 行（2 页）+ 本页 28 行做区分：若用「本页行数」算页数，
         * 28 → 1 页，holder 会被 clamp 到 1，下面的断言红。
         */
        @Test
        @DisplayName("总页数来自 COUNT（29 行 = 2 页），不是本页行数（28 行 = 1 页）")
        void totalPagesComeFromCountNotPageRows() {
            when(plugin.isEnabled()).thenReturn(true);
            when(dataStore.countActiveSquareEntries()).thenReturn(SQUARE_PAGE + 1);
            when(dataStore.getActiveSquareEntries(anyInt(), anyInt()))
                    .thenReturn(rows(0, SQUARE_PAGE)); // 本页满 28 行
            when(dataStore.getPlayerStats(VIEWER_ID)).thenReturn(new PlayerDataStore.PlayerStats());

            Harness harness = new Harness();
            EnglishSquareGUI gui = new EnglishSquareGUI(plugin);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<ItemStack> ignored = itemStackMock()) {
                bukkit.when(Bukkit::getScheduler).thenReturn(harness.scheduler);
                stubCreateInventory(bukkit, harness);
                gui.open(viewer, 2, Leaderboard.RankingMode.ALL_PLAYERS);
            }

            verify(dataStore).countActiveSquareEntries();
            assertEquals(2, gui.getCurrentPage(),
                    "COUNT=29 是 2 页，page=2 不该被 clamp 回 1 喵 —— 若这里变 1，"
                            + "说明页数改用了「本页行数」(28→1)，症状是「下一页按钮消失、永远翻不到第 2 页」");
        }
    }

    // ==================================================================
    // 脚手架
    // ==================================================================

    /**
     * 调度器替身：{@code runTaskAsynchronously} / {@code runTask} 默认<b>就地同步执行</b>
     * （于是「异步取数 → 回主线程渲染」在测试里是一次普通调用），可按需挂起。
     *
     * <p><b>{@link #onAfterDispatch}</b>：在「异步体已经读完数据、回主线程的渲染任务
     * <b>即将执行</b>」的那一刻触发一次。这是模拟「异步窗口内玩家关掉界面 / 切到别的
     * 界面」的唯一正确位置 —— 它是「数据已就绪、屏幕还没被写」的那个缝隙。
     *
     * <p>注意必须挂在 {@code runTask} 的 <b>task.run() 之前</b>：挂在异步体之后是错的，
     * 因为就地同步执行时回主线程任务在异步体内部就已经跑完了（那样钩子永远晚一步，
     * 用例会假绿）。只在<b>非挂起</b>模式下触发。
     */
    private final class Harness {
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final AtomicBoolean asyncDispatched = new AtomicBoolean();
        private final AtomicBoolean asyncRan = new AtomicBoolean();
        private final AtomicInteger renderCount = new AtomicInteger();
        private final AtomicReference<PluginGuiHolder> lastHolder = new AtomicReference<>();
        private final List<Runnable> asyncTasks = new ArrayList<>();
        private final List<Runnable> mainTasks = new ArrayList<>();
        private boolean deferAsync;
        private boolean deferMain;
        /** 回主线程渲染任务执行前的钩子（模拟异步窗口内玩家的动作）。只触发一次。 */
        private Runnable onAfterDispatch;
        private boolean hookFired;

        private Harness() {
            lenient().when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
                    .thenAnswer(inv -> {
                        Runnable task = inv.getArgument(1);
                        asyncDispatched.set(true);
                        if (deferAsync) {
                            asyncTasks.add(task);
                            return null;
                        }
                        asyncRan.set(true);
                        task.run();
                        return null;
                    });
            lenient().when(scheduler.runTask(eq(plugin), any(Runnable.class)))
                    .thenAnswer(inv -> {
                        Runnable task = inv.getArgument(1);
                        if (deferMain) {
                            mainTasks.add(task);
                            return null;
                        }
                        // 数据已就绪、屏幕尚未写入 —— 玩家的动作发生在这个缝隙里。
                        if (onAfterDispatch != null && !hookFired) {
                            hookFired = true;
                            onAfterDispatch.run();
                        }
                        task.run();
                        return null;
                    });
        }

        private boolean asyncDispatched() {
            return asyncDispatched.get();
        }

        private boolean asyncRan() {
            return asyncRan.get();
        }
    }

    /**
     * 造一张「玩家正开着的界面」：{@code getOpenInventory().getTopInventory().getHolder()}
     * 返回给定 holder。
     *
     * <p>{@code holder} 为 {@code null} 表示「不是插件界面」（玩家背包 / 原版容器）——
     * 正是 {@code pluginGuiHolderOf(...)} 判成 null 的条件。回填守卫的两个分支
     * （关掉界面 / 切到别的插件界面）都靠本工具表达。
     *
     * <p><b>不能写成 {@code when(viewer.getOpenInventory()).thenReturn(viewWithHolder(...))}</b>：
     * 那会在一个未完成的 stubbing 里再开一个 stubbing，Mockito 抛
     * {@code UnfinishedStubbingException}。本方法只造件、不往 {@code viewer} 上挂，
     * 调用方先取引用再挂。
     */
    private static InventoryView viewWithHolder(PluginGuiHolder holder) {
        Inventory top = mock(Inventory.class);
        doReturn(holder).when(top).getHolder();
        InventoryView view = mock(InventoryView.class);
        doReturn(top).when(view).getTopInventory();
        return view;
    }

    /**
     * 拦截 {@code Bukkit.createInventory(...)}：返回状态化 Inventory，并记下 holder
     * 与「建了几张界面」（= 渲染了几次）。
     *
     * <p>同时把 {@code viewer.getOpenInventory().getTopInventory()} 接到这张界面 ——
     * 生产里玩家打开界面后就是这个形状，回填守卫据此判定「界面还在」。
     */
    private void stubCreateInventory(MockedStatic<Bukkit> bukkit, Harness harness) {
        bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    InventoryHolder holder = invocation.getArgument(0);
                    harness.lastHolder.set(holder instanceof PluginGuiHolder h ? h : null);
                    harness.renderCount.incrementAndGet();
                    Inventory inv = mock(Inventory.class);
                    Map<Integer, ItemStack> slots = new HashMap<>();
                    lenient().when(inv.getSize()).thenReturn(invocation.getArgument(1));
                    lenient().when(inv.getItem(anyInt()))
                            .thenAnswer(i -> slots.get((Integer) i.getArgument(0)));
                    lenient().doAnswer(i -> {
                        slots.put(i.getArgument(0), i.getArgument(1));
                        return null;
                    }).when(inv).setItem(anyInt(), any());
                    lenient().when(inv.getHolder()).thenReturn(holder);
                    InventoryView view = mock(InventoryView.class);
                    lenient().when(view.getTopInventory()).thenReturn(inv);
                    lenient().when(viewer.getOpenInventory()).thenReturn(view);
                    return inv;
                });
    }

    /**
     * ItemStack 构造拦截：纯 JVM 无 RegistryAccess（{@code new ItemStack(Material.X)}
     * 会抛 {@code ExceptionInInitializerError}），且 {@code clone()} 必须返回自身。
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
                        return metas.computeIfAbsent(self, k -> mock(ItemMeta.class));
                    }
                    if ("setItemMeta".equals(method)) {
                        metas.put(self, invocation.getArgument(0));
                        return null;
                    }
                    return Answers.RETURNS_DEFAULTS.answer(invocation);
                }));
    }

    /** n 条错题本条目（错次 3、加入时间 0，渲染细节与被测契约无关）。 */
    private static List<Map.Entry<String, DifficultWord>> entries(int n) {
        List<Map.Entry<String, DifficultWord>> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String word = "word" + i;
            list.add(Map.entry(word, new DifficultWord(word, 3, 0L)));
        }
        return list;
    }

    /** {@code count} 条广场行，玩家 UUID 从 {@code startIndex} 起编。 */
    private static List<Leaderboard.LeaderboardEntry> rows(int startIndex, int count) {
        List<Leaderboard.LeaderboardEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = startIndex + i;
            list.add(new Leaderboard.LeaderboardEntry(
                    new UUID(0L, idx + 1L), "P" + idx, 1000L - idx, 10, 1, 3, idx + 1));
        }
        return list;
    }

    // ==================================================================
    // 源码读取工具（剥注释 + 取方法体）
    // ==================================================================

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

    private static String read(String relative) throws IOException {
        Path file = repoRoot().resolve(relative);
        assertTrue(Files.isRegularFile(file), "被 lint 的源文件不存在：" + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * 去掉行注释与块注释（含 Javadoc）。
     *
     * <p>为什么必须剥：本次改造的说明文字里到处写着被禁的方法名（"旧实现在主线程调
     * getWrongBookEntries"），不剥注释等于让注释把自己的断言骗绿。字符串字面量要保留
     * （里面可能有 {@code http://} 之类），故按引号成对跳过。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < src.length()) {
                    char d = src.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < src.length()) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 取某方法的方法体（含外层花括号）。
     *
     * <p>锚点是<b>方法签名文本</b>而不是行号 —— 行号会随任何无关编辑漂移，而签名
     * （连参数类型一起）只在真的改签名时才变，那时对应用例的失败信息会明确要求同步 lint。
     */
    private static String methodBody(String src, String signature) {
        int sigAt = src.indexOf(signature);
        assertTrue(sigAt >= 0,
                "源码里找不到方法签名「" + signature + "」喵 —— 方法被改名/改签名了，"
                        + "请同步本 lint（否则门禁会静默失效，而不是变红）");
        int open = src.indexOf('{', sigAt + signature.length());
        assertTrue(open > 0, "方法「" + signature + "」之后找不到左花括号喵");
        int close = matchingBrace(src, open);
        assertTrue(close > open, "方法「" + signature + "」的花括号不配对喵 —— 源码可能被截断");
        return src.substring(open, close + 1);
    }

    /** 从 leftBrace 起做括号配对（跳过字符串/字符字面量），返回匹配的右花括号下标（找不到 -1）。 */
    private static int matchingBrace(String s, int leftBrace) {
        int depth = 0;
        int i = leftBrace;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == quote) break;
                }
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) return i;
            }
            i++;
        }
        return -1;
    }
}
