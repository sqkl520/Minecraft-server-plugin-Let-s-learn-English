package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.command.AdminCommand;
import com.letslearnenglish.minecraftplugin.command.LearnCommand;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.manager.HotReloadManager;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子服总开关（{@code server.enabled}）的行为守卫。
 *
 * <p><b>为什么需要这个类</b>：这个开关的全部价值都在「关闭态」，而关闭态是运行期分支
 * 最少被走到的路径。任何一处漏写 null 守卫都只在服主真的把某个子服关掉时才炸，且在
 * {@code onDisable} 里炸掉的后果是数据不落盘、数据库连接不关闭（见 onDisable 的注释链）。
 * 因此这里把四条关键契约钉死：配置默认值、两条命令的拒绝边界、停用流程的空守卫、
 * reload 的短路。
 *
 * <p><b>onDisable 的测法（为什么不用「直接 new」）</b>：{@link LetsLearnEnglish} 是
 * {@code final} 且继承 {@code JavaPlugin}，其无参构造在 Paper 1.21.4 会校验 ClassLoader，
 * 在非插件 ClassLoader 下直接抛 {@code IllegalStateException: JavaPlugin requires to be
 * created by a valid classloader}（javap 实测其构造字节码）。所以「new 一个实例」在纯 JVM
 * 里根本走不通。这里改用 {@code mock(LetsLearnEnglish.class)}（Mockito 5 默认 inline
 * mock maker，可 mock final 类；实例由 Objenesis 创建，不跑构造）+
 * {@code doCallRealMethod().when(plugin).onDisable()}：只有 {@code onDisable} 走真实实现，
 * 它内部对 {@code getLogger/getServer/getDataFolder} 的调用仍被 mock 拦截。
 *
 * <p>代价：Objenesis 不跑字段初始化器，{@code playerLanguage} 这类 {@code new
 * ConcurrentHashMap<>()} 字段在 mock 实例上是 null。所以这里用反射把它补上 —— 这不是
 * 生产代码的缺陷（真实实例由 Bukkit 正常构造），只是测试替身必须补齐的实例状态。
 * 数据目录指向 {@link TempDir}，不会在仓库里留下 player_lang.yml。
 */
class ServerToggleTest {

    /** 与 ConfigManagerGuiMaterialTest 同款：mainConfig 是私有字段、无 setter，反射注入。 */
    private static void injectMainConfig(ConfigManager cm, FileConfiguration cfg) throws Exception {
        Field field = ConfigManager.class.getDeclaredField("mainConfig");
        field.setAccessible(true);
        field.set(cm, cfg);
    }

    /** 给 mock 出来的插件实例补一个私有字段（Objenesis 不跑初始化器）。 */
    private static void injectField(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("组 A —— ConfigManager.isServerEnabled 的默认值与显式关闭")
    class ConfigGate {

        private ConfigManager configManager;
        private FileConfiguration mainConfig;

        @BeforeEach
        void setUp() throws Exception {
            configManager = new ConfigManager(mock(LetsLearnEnglish.class));
            mainConfig = new YamlConfiguration();
            injectMainConfig(configManager, mainConfig);
        }

        @Test
        @DisplayName("缺键时默认 true（存量服升级后不会突然失能）")
        void defaultsToTrueWhenKeyMissing() {
            assertFalse(mainConfig.isSet("server.enabled"), "前置：本用例要求键确实缺失");
            assertTrue(configManager.isServerEnabled());
        }

        @Test
        @DisplayName("server.enabled=false → false")
        void explicitFalseIsHonoured() {
            mainConfig.set("server.enabled", false);
            assertFalse(configManager.isServerEnabled());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("组 A2 —— isFullyEnabled：配置写了 true 但没装配（reload 后的分叉窗口）")
    class FullyEnabledGate {

        @Test
        @DisplayName("配置为 true 但 playerDataStore 为 null → false（命令继续拒绝，不 NPE）")
        void configTrueButNotWiredIsNotFullyEnabled() throws Exception {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            ConfigManager configManager = mock(ConfigManager.class);
            when(configManager.isServerEnabled()).thenReturn(true);
            injectField(plugin, LetsLearnEnglish.class, "configManager", configManager);
            // 显式真实实现（见下一条用例的解释）——这条的「false」必须是逻辑算出来的 false。
            doCallRealMethod().when(plugin).isFullyEnabled();

            // /lea reload 把 server.enabled 从 false 改成 true 后，配置读到 true，
            // 但 Manager 一个都没装 —— 这一瞬间必须仍判 false，否则命令会打到 null Manager。
            assertFalse(plugin.isFullyEnabled());
        }

        @Test
        @DisplayName("配置为 true 且已装配 → true")
        void configTrueAndWiredIsFullyEnabled() throws Exception {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            ConfigManager configManager = mock(ConfigManager.class);
            when(configManager.isServerEnabled()).thenReturn(true);
            injectField(plugin, LetsLearnEnglish.class, "configManager", configManager);
            injectField(plugin, LetsLearnEnglish.class, "playerDataStore",
                    mock(com.letslearnenglish.minecraftplugin.data.PlayerDataStore.class));
            // /lea reload 动态装卸之后 isFullyEnabled() 多了一维 runtimeActive：
            // 它区分「配置说开」与「运行时真的装好了」。这里显式给 true（其余两项也都齐）。
            when(plugin.isRuntimeActive()).thenReturn(true);
            // mock 实例上的 isFullyEnabled() 默认返回 false，必须显式走真实实现，
            // 否则本用例只证明了「mock 的默认值」而不是被测逻辑。
            doCallRealMethod().when(plugin).isFullyEnabled();

            assertTrue(plugin.isFullyEnabled());
        }

        @Test
        @DisplayName("配置为 false 但已装配 → false（运行中改开关，命令须立刻拒绝）")
        void configFalseButWiredIsNotFullyEnabled() throws Exception {
            // 红队 J 的 M3b：把 isFullyEnabled() 里的 configManager.isServerEnabled()
            // 删掉后，上面两条用例（都喂 true）依然全绿 —— 说明「配置门」本身从未被证伪。
            // 本条补上反向输入：装配齐全，但配置说关 = 不启用。
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            ConfigManager configManager = mock(ConfigManager.class);
            when(configManager.isServerEnabled()).thenReturn(false);
            injectField(plugin, LetsLearnEnglish.class, "configManager", configManager);
            injectField(plugin, LetsLearnEnglish.class, "playerDataStore",
                    mock(com.letslearnenglish.minecraftplugin.data.PlayerDataStore.class));
            doCallRealMethod().when(plugin).isFullyEnabled();

            assertFalse(plugin.isFullyEnabled(),
                    "配置 server.enabled=false 但 playerDataStore 非空时，isFullyEnabled() 必须是 false 喵。\n"
                            + "这是「运行中把开关改关」的路径：监听器/定时任务仍在跑（本轮不做动态装卸），"
                            + "但命令入口必须立刻拒绝，否则玩家还能继续用一套正在关闭的功能。");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("组 B —— 两条命令在关闭态的拒绝边界")
    class CommandGate {

        private LetsLearnEnglish plugin;
        private MessageUtil messageUtil;
        private Player player;
        private Command command;

        @BeforeEach
        void setUp() {
            plugin = mock(LetsLearnEnglish.class);
            ConfigManager configManager = mock(ConfigManager.class);
            messageUtil = mock(MessageUtil.class);
            player = mock(Player.class);
            command = mock(Command.class);

            when(plugin.getConfigManager()).thenReturn(configManager);
            when(plugin.getMessageUtil()).thenReturn(messageUtil);
            when(configManager.isServerEnabled()).thenReturn(false);
            // 命令入口用的是 isFullyEnabled()（配置门 + 已装配），不是裸配置值：
            // 关闭态下 DataStore 为 null 才是「真的没装起来」的判据。
            when(plugin.isFullyEnabled()).thenReturn(false);
            when(player.hasPermission("letslearnenglish.admin")).thenReturn(true);
        }

        @Test
        @DisplayName("/le（含全部子命令）在关闭态拒绝并返回 true，不抛异常")
        void learnCommandRejectsWhenDisabled() {
            LearnCommand learnCommand = new LearnCommand(plugin);

            // 有参 / 无参两条入口都要走：无参原本直奔 openMainMenu，而关闭态下
            // MainMenu 依赖的 Manager 全为 null —— 必须同样被截住。
            boolean withArgs = assertDoesNotThrow(
                    () -> learnCommand.onCommand(player, command, "le", new String[]{"word"}));
            boolean noArgs = assertDoesNotThrow(
                    () -> learnCommand.onCommand(player, command, "le", new String[0]));

            assertTrue(withArgs, "命令必须被处理（返回 true），不能落到 Bukkit 的用法提示");
            assertTrue(noArgs, "无参 /le 同样必须被拒绝");
            verify(messageUtil, times(2)).getPlayerMessage(player, "server.disabled");
            // 拒绝发生在任何 Manager 访问之前：关闭态下这些 getter 都是 null，
            // 一旦被碰到就是 NPE。下面的 never() 是「顺序正确」的直接证据。
            verify(plugin, never()).getWordLearningManager();
            verify(plugin, never()).getWordManager();
        }

        @Test
        @DisplayName("/lea broadcast 在关闭态拒绝；/lea reload 例外放行")
        void adminCommandRejectsAllButReload() {
            HotReloadManager hotReloadManager = mock(HotReloadManager.class);
            when(plugin.getHotReloadManager()).thenReturn(hotReloadManager);
            when(messageUtil.getSenderMessage(player, "general.reload-success"))
                    .thenReturn("reloaded");
            AdminCommand adminCommand = new AdminCommand(plugin);

            // 1) broadcast 必须被拒，且绝不能走到 ProxyMessenger（关闭态下它是 null，
            //    走进去就是 NPE；同时也保证不会向代理网络发任何东西）。
            boolean broadcastHandled = assertDoesNotThrow(() -> adminCommand.onCommand(
                    player, command, "lea", new String[]{"broadcast", "hello"}));
            assertTrue(broadcastHandled);
            verify(messageUtil).getSenderMessage(player, "server.disabled");
            verify(plugin, never()).getProxyMessenger();

            // 2) reload 必须放行（否则服主改完开关只能重启）
            boolean reloadHandled = assertDoesNotThrow(() -> adminCommand.onCommand(
                    player, command, "lea", new String[]{"reload"}));
            assertTrue(reloadHandled);
            verify(hotReloadManager).reloadAll();
        }

        @Test
        @DisplayName("关闭态不抛异常 —— /le 的 Tab 补全返回空列表（补全是独立于 onCommand 的入口）")
        void learnTabCompleteIsEmptyWhenDisabled() {
            LearnCommand learnCommand = new LearnCommand(plugin);

            // Bukkit 在玩家敲 Tab 时直接调用 onTabComplete，绕过 onCommand；而关闭态下
            // wordManager / aiChatManager 全是 null，不拦就会 NPE。返回空表（非 null）是契约。
            List<String> completions = assertDoesNotThrow(() -> learnCommand.onTabComplete(
                    player, command, "le", new String[]{"word"}));

            assertTrue(completions.isEmpty());
        }

        @Test
        @DisplayName("关闭态下 /lea 的 Tab 补全只给 reload（其余子命令按了会被拒）")
        void adminTabCompleteOffersOnlyReloadWhenDisabled() {
            AdminCommand adminCommand = new AdminCommand(plugin);

            // args>=2 的分支会碰 adminCommandHandler / wordManager / duelManager（全 null），
            // 因此这里同时断言「深层补全也不炸」。
            List<String> top = assertDoesNotThrow(() -> adminCommand.onTabComplete(
                    player, command, "lea", new String[]{""}));
            List<String> deep = assertDoesNotThrow(() -> adminCommand.onTabComplete(
                    player, command, "lea", new String[]{"report", "al"}));

            assertEquals(List.of("reload"), top);
            assertTrue(deep.isEmpty());
        }

        @Test
        @DisplayName("关闭态拒绝发生在权限检查之后（未授权者探测不到开关状态）")
        void disabledRejectionHappensAfterPermissionCheck() {
            when(player.hasPermission("letslearnenglish.admin")).thenReturn(false);
            when(messageUtil.getSenderMessage(player, "general.no-permission"))
                    .thenReturn("no-perm");

            new AdminCommand(plugin).onCommand(player, command, "lea",
                    new String[]{"broadcast", "x"});

            verify(messageUtil).getSenderMessage(player, "general.no-permission");
            verify(messageUtil, never()).getSenderMessage(player, "server.disabled");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("组 C —— onDisable 在「全部 Manager 为 null」时不抛异常")
    class DisableSafety {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("关闭态退出：不 NPE，且尾部的关库确实跑到")
        void onDisableSurvivesAllNullManagers() throws Exception {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            Server server = mock(Server.class);
            DatabaseManager databaseManager = mock(DatabaseManager.class);
            Logger logger = Logger.getLogger("ServerToggleTest-onDisable");
            UUID savedId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
            // 刻意留一个在线玩家：关闭态下服务器照样可能有玩家（开关是整服级、不是「无人」），
            // GUI 清扫与落盘两个 per-player 循环必须真的进得去 —— 空列表会让循环体成为
            // 不可达代码，等于把这两段的 null 安全挖掉。
            Player online = mock(Player.class);
            when(online.getName()).thenReturn("Tester");
            when(online.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-0000000000b2"));
            when(online.getOpenInventory()).thenReturn(null);
            // Collection<? extends Player> 的泛型通配无法直接 thenReturn，用 doReturn 绕开。
            doReturn(Collections.singletonList(online)).when(server).getOnlinePlayers();

            // 关闭态下唯一非 null 的「重」依赖：databaseManager（/lea reload 需要它）。
            Map<UUID, String> languages = new ConcurrentHashMap<>();
            languages.put(savedId, "en");
            injectField(plugin, LetsLearnEnglish.class, "databaseManager", databaseManager);
            injectField(plugin, LetsLearnEnglish.class, "playerLanguage", languages);

            doCallRealMethod().when(plugin).onDisable();

            assertDoesNotThrow(plugin::onDisable,
                    "关闭态下 onDisable 必须跑完整段，任何一步 NPE 都会导致数据/连接收尾缺失");

            // 走到最后一行的证据：关库被调用（若是中途 NPE 就永远到不了这里）
            verify(databaseManager).shutdown();
            // 语言偏好没有因为关闭态而被清空（onEnable 仍在总开关判定之前加载它）
            File langFile = new File(tempDir.toFile(), "player_lang.yml");
            assertTrue(langFile.exists(), "savePlayerLanguages 必须把内存里的语言偏好写回文件");
            // 红队 I 的 P1：只断言「文件存在」是半边覆盖 —— savePlayerLanguages 就算内存表为空
            // 也会创建一个空文件，断言照样通过。必须验内容。
            FileConfiguration saved = YamlConfiguration.loadConfiguration(langFile);
            assertEquals("en", saved.getString(savedId.toString()),
                    "player_lang.yml 里丢了已加载的语言偏好 喵。\n"
                            + "文件存在 ≠ 内容正确：内存表为空时 savePlayerLanguages 同样会建一个空文件。"
                            + "这条断言才是「关闭态没有清空玩家语言偏好」的真正证据。");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("组 D —— reload 在关闭态的短路")
    class ReloadGate {

        @Test
        @DisplayName("playerDataStore 为 null 时 reloadAll 只刷新配置，不碰任何 Manager")
        void reloadAllShortCircuitsWithoutManagers() {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            ConfigManager configManager = mock(ConfigManager.class);
            Logger logger = Logger.getLogger("ServerToggleTest-reload");
            when(plugin.getConfigManager()).thenReturn(configManager);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getPlayerDataStore()).thenReturn(null);

            HotReloadManager manager = new HotReloadManager(plugin, configManager);

            assertDoesNotThrow(manager::reloadAll);
            verify(configManager).reloadAllConfigs();
            // 短路必须在碰 wordManager / AIChatManager 之前生效（它们都是 null）
            verify(plugin, never()).getWordManager();
            verify(plugin, never()).getAIChatManager();
        }
    }

    // ==================================================================
    // 组 G —— reloadAll 的分叉行为（ENABLE / DISABLE / legacy 三条路）
    //
    // 为什么这一组必须存在：组 D 只钉了「pds 为 null 时短路」，它验证的恰好是分类为
    // CONFIG_ONLY 的那一条。而本功能新增的两条分支 —— ENABLE（配置改开 → 动态装配）与
    // DISABLE（配置改关 → 动态拆解）—— 在此之前零覆盖：把 switch 的 case ENABLE 整段删掉、
    // 或者让 DISABLE 分支不 return 直接落进 legacy，组 D 照样全绿（它用的是 pds=null 场景）。
    //
    // 三个「防假绿」要点：
    //   1) 每个用例都配 never() 负向断言。「调了 enableAll 又顺手把 legacy 跑了一遍」这种
    //      半对实现，只断言正向调用会全绿 —— 而它正是 DISABLE 分支最危险的形态
    //      （legacy 的 reloadWordBanks() 是裸调 getWordManager()，此时字段已被置空）。
    //   2) 不 mock ReloadResult（自证桩）。枚举值直接比对常量。
    //   3) 用 thenReturn(false, true) 这种「按调用顺序吐值」的桩，而不是 thenReturn(mock)。
    //      若实现把 isServerEnabled() 的结果缓存起来（先读一次、之后复用），第二次调用不会
    //      消费到第二个返回值，分类就会被判成 NONE_*，本组变红 —— 这正是我们要的。
    //      （这条依赖于 Mockito 的连续桩语义：同一方法第 N 次调用取第 N 个值。）
    // ==================================================================
    @Nested
    @DisplayName("组 G —— reload 分叉行为（补 ENABLE / DISABLE 分支零覆盖）")
    class ReloadFork {

        private LetsLearnEnglish plugin;
        private ConfigManager configManager;
        private HotReloadManager manager;

        @BeforeEach
        void setUp() {
            plugin = mock(LetsLearnEnglish.class);
            configManager = mock(ConfigManager.class);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("ServerToggleTest-reloadFork"));
            manager = new HotReloadManager(plugin, configManager);
        }

        /**
         * false → true：{@code reloadAllConfigs()} 之后读到的配置是 true，而 runtime 未装配
         * → ENABLE → 调 {@code enableAll()}，并且<b>绝不能</b>落到 legacy 段。
         *
         * <p><b>桩的设计（防假绿的核心）</b>：不能用 {@code thenReturn(false, true)} ——
         * {@code reloadAll()} 只在 {@code reloadAllConfigs()} 之后读<b>一次</b>
         * {@code isServerEnabled()}（新值即刻生效，没有「先读旧值再读新值」的两次读）。
         * 这里改用「让 {@code reloadAllConfigs()} 自己把答案翻过来」的桩：
         * 重载前 {@code isServerEnabled()}=false，重载后=true —— 与真实的
         * 「yml 换盘后配置值才变」一一对应。于是「实现把配置值在重载<b>之前</b>读走 /
         * 缓存了旧值」这个变异会真的拿到 false，判成 NONE_DISABLED，本用例变红。
         */
        @Test
        @DisplayName("false → true：调 enableAll() 一次，legacy 段完全不跑")
        void reloadEnablesRuntimeWhenSwitchTurnsOn() {
            // classify 的第一个入参是「运行时是否已装配」。mock 的默认返回值是 false，
            // 这里显式写出来：绝不能靠默认值 —— 否则「实现把两个入参写反」时仍然通过。
            when(plugin.isRuntimeActive()).thenReturn(false);
            when(configManager.isServerEnabled()).thenReturn(false);
            // 重载这个动作本身把配置值翻成 true（模拟 server.enabled 从 false 改成 true 的 yml）。
            doAnswer(invocation -> {
                when(configManager.isServerEnabled()).thenReturn(true);
                return null;
            }).when(configManager).reloadAllConfigs();

            HotReloadManager.ReloadResult result = manager.reloadAll();

            verify(configManager).reloadAllConfigs();
            verify(plugin).enableAll();
            verify(plugin, never()).disableAll();
            // legacy 段的三条入口全部不许被碰。
            verify(plugin, never()).getWordManager();
            verify(plugin, never()).getAIChatManager();
            verify(plugin, never()).restartStatisticsServerIfNeeded();

            assertEquals(HotReloadManager.ReloadResult.ENABLED, result,
                    "false → true 的 reload 必须返回 ENABLED 喵（命令层据此选"
                            + " server.reload-enabled 文案）。返回 FULL 会让服主看到「已重新加载」"
                            + "而不是「已启用运行时」，两者对排查的含义完全不同。");
        }

        /**
         * true → false：配置读到 false、runtime 已装配 → DISABLE → 调 {@code disableAll()}，
         * 且<b>必须 return</b>，绝不能落进 legacy 段。
         *
         * <p>为什么这条的 {@code never()} 比上一条更要紧：DISABLE 之后 {@code wordManager}
         * 已被 D10 置空，而 {@code reloadWordBanks()} 是<b>裸调用</b>
         * {@code plugin.getWordManager().loadAllWordBanks()}，没有 null 守卫。
         * fall through 的后果是 NPE 被命令层当成 reload 失败 —— 服主会以为卸载没成功，
         * 于是重启服务器（而他本来不需要）。
         */
        @Test
        @DisplayName("true → false：调 disableAll() 一次，legacy 段完全不跑（否则裸调 getWordManager 会 NPE）")
        void reloadDisablesRuntimeWhenSwitchTurnsOff() {
            // 运行时已装配（true），配置改成关（false）。
            // 两个入参必须**都**与 baseline 用例相反，才能证明 classify 读的是
            // 「运行时状态」而不是「配置旧值」——只桩配置的用例会让「用配置旧值当基准」
            // 的错误实现照样通过。
            when(plugin.isRuntimeActive()).thenReturn(true);
            when(configManager.isServerEnabled()).thenReturn(true);
            doAnswer(invocation -> {
                when(configManager.isServerEnabled()).thenReturn(false);
                return null;
            }).when(configManager).reloadAllConfigs();

            HotReloadManager.ReloadResult result = manager.reloadAll();

            verify(configManager).reloadAllConfigs();
            verify(plugin).disableAll();
            verify(plugin, never()).enableAll();
            verify(plugin, never()).getWordManager();
            verify(plugin, never()).getAIChatManager();
            verify(plugin, never()).restartStatisticsServerIfNeeded();

            assertEquals(HotReloadManager.ReloadResult.DISABLED, result,
                    "true → false 的 reload 必须返回 DISABLED 喵。返回 FULL 会把"
                            + "「已卸载运行时」谎报成「配置已重新加载」，服主无法确认关闭是否生效。");
        }

        /**
         * 无变化（true → true）：不调 enableAll / disableAll，走 legacy 完整热重载。
         *
         * <p>正向证据选 {@code getAIChatManager()}：legacy 段的第一件事就是
         * {@code plugin.getAIChatManager().reload()}（外面有 null 守卫）。这一步是「真的走了
         * legacy」的标志 —— 它比 {@code getPlayerDataStore()} 更靠后，能顺带证明前面的
         * AI 段确实被执行到。
         */
        @Test
        @DisplayName("true → true：不碰 enable/disable，走 legacy（AI 段被执行）")
        void reloadStaysOnLegacyPathWhenNothingChanged() {
            when(plugin.isRuntimeActive()).thenReturn(true);
            when(configManager.isServerEnabled()).thenReturn(true);
            // legacy 段的每一步都直接解引用 Manager（reloadWordBanks 是裸调
            // plugin.getWordManager().loadAllWordBanks()，没有 null 守卫），
            // 所以要给一个能走完全程的最小桩集。这里**只**提供「走完」所需的依赖，
            // 不给 enableAll/disableAll 提供任何行为 —— 它们被调用的话 verify(never) 会抓到。
            when(plugin.getPlayerDataStore()).thenReturn(
                    mock(com.letslearnenglish.minecraftplugin.data.PlayerDataStore.class));
            when(plugin.getWordManager()).thenReturn(
                    mock(com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl.class, invocation -> null));
            when(plugin.getServer()).thenReturn(mock(org.bukkit.Server.class));

            HotReloadManager.ReloadResult result = manager.reloadAll();

            verify(plugin, never()).enableAll();
            verify(plugin, never()).disableAll();
            verify(plugin).getAIChatManager();

            assertEquals(HotReloadManager.ReloadResult.FULL, result,
                    "状态未迁移的 reload 必须返回 FULL（legacy 完整热重载）喵。\n"
                            + "若返回 CONFIG_ONLY，服主会被告知「需要重启」，"
                            + "而他其实什么都没改 —— 这是上一轮被投诉的谎报形态之一。");
        }

        /**
         * 启动即关（false → false）：不调 enable/disable，且<b>不看</b>
         * {@code getWordManager()}（pds 为 null 的短路必须在它之前生效）。
         *
         * <p>与组 D 的差别：组 D 只验证「pds 为 null → 不看 wordManager」；本用例把
         * {@code isServerEnabled()} 也钉成 false，覆盖 classify 的 NONE_DISABLED 分支 ——
         * 若有人把分类写成「只要 pds 为 null 就 CONFIG_ONLY，不看配置」，两条都会绿，
         * 但一旦将来 NONE_DISABLED 需要打不同的日志（当前实现就读了一次 isServerEnabled()
         * 来打印实际值），行为差异就出来了。
         */
        @Test
        @DisplayName("false → false（启动即关）：不碰 enable/disable，短路在 getWordManager 之前")
        void reloadOnStartupDisabledDoesNothing() {
            when(plugin.isRuntimeActive()).thenReturn(false);
            when(configManager.isServerEnabled()).thenReturn(false);
            when(plugin.getPlayerDataStore()).thenReturn(null);

            HotReloadManager.ReloadResult result = manager.reloadAll();

            verify(plugin, never()).enableAll();
            verify(plugin, never()).disableAll();
            // 关闭态下三个 Manager 都是 null：任何一处无条件解引用都会 NPE。
            verify(plugin, never()).getWordManager();
            verify(plugin, never()).getAIChatManager();

            assertEquals(HotReloadManager.ReloadResult.CONFIG_ONLY, result,
                    "启动即关（false → false）的 reload 必须返回 CONFIG_ONLY 喵 —— "
                            + "运行时从未装配，能做的只有把配置文件读进内存。");
        }
    }

    // ==================================================================
    // 组 E —— onEnable 的短路分支（源码结构断言）
    //
    // 为什么用源码 lint 而不是行为测试：onEnable 依赖真实 Bukkit server
    //（loadPlayerLanguages / new DatabaseManager().initialize() / getServer()），
    // 在纯 JVM 里起不来。而「关掉开关后插件仍然完整启动」是本功能唯一的实际效果 ——
    // 它没有测试，等于整个 server.enabled 只测了周边（命令拒绝 / reload 短路）。
    //
    // 红队 J 实测：把 `if (!configManager.isServerEnabled())` 改成 `if (false)`，
    // 或删掉分支里的 `return;`，11 条行为用例<b>全绿</b>。下面三条断言正是补这个洞：
    // 它们钉的是「分支存在」「分支内只构造三件套 + 注册命令 + info」「分支以 return 结束」
    // 「完整启动调用在分支之后再无条件执行」。
    // ==================================================================
    @Nested
    @DisplayName("组 E —— onEnable 短路分支结构（补 M1 假绿）")
    class OnEnableShortCircuit {

        /** 读 onEnable 的方法体（已剥注释与字符串字面量，避免被注释骗过）。 */
        private String onEnableBody() {
            // 本类自带最小实现，刻意不复用 B3WiringLintsTest 的私有工具（跨类不可见）。
            java.nio.file.Path root = java.nio.file.Paths.get(
                    System.getProperty("user.dir"), "src", "main", "java",
                    "com", "letslearnenglish", "minecraftplugin", "LetsLearnEnglish.java");
            String raw;
            try {
                raw = java.nio.file.Files.readString(root, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new AssertionError("读取 " + root + " 失败喵：" + e.getMessage(), e);
            }
            String stripped = stripCommentsAndLiterals(raw);
            String sig = "public void onEnable()";
            int sigAt = stripped.indexOf(sig);
            if (sigAt < 0) {
                throw new AssertionError("源码里找不到 " + sig + " 喵 —— 方法可能被改名/改签名，"
                        + "请同步本 lint（否则门禁会静默失效）。");
            }
            int open = stripped.indexOf('{', sigAt + sig.length());
            int close = matchingBrace(stripped, open);
            if (close < 0) {
                throw new AssertionError(sig + " 的花括号不配对喵。");
            }
            return stripped.substring(open, close);
        }

        /**
         * 把注释与字符串/字符字面量替换成空格（<b>保留换行</b>，因此偏移与原文一一对应）。
         *
         * <p>为什么必要：本组断言查的是「分支里有没有 return / registerCommands」这类
         * 关键字。若不剥注释，把代码注释掉再在注释里写一遍同样的词就能骗过断言；
         * 不抹字面量，一条日志字符串里出现 "return" 也会误判。
         */
        private String stripCommentsAndLiterals(String s) {
            StringBuilder out = new StringBuilder(s.length());
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    while (i < s.length() && s.charAt(i) != '\n') { out.append(' '); i++; }
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    out.append("  "); i += 2;
                    while (i < s.length() && !(s.charAt(i) == '*' && i + 1 < s.length()
                            && s.charAt(i + 1) == '/')) {
                        out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    if (i < s.length()) { out.append("  "); i += 2; }
                } else if (c == '"' || c == '\'') {
                    char quote = c;
                    out.append(' '); i++;
                    while (i < s.length() && s.charAt(i) != quote) {
                        if (s.charAt(i) == '\\' && i + 1 < s.length()) {
                            out.append(' '); i++;
                        }
                        out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    if (i < s.length()) { out.append(' '); i++; }
                } else {
                    out.append(c); i++;
                }
            }
            return out.toString();
        }

        @Test
        @DisplayName("M1a：短路分支的条件必须是 configManager.isServerEnabled()，不能是 if(false)")
        void shortCircuitConditionReadsServerEnabled() {
            String body = onEnableBody();
            assertTrue(body.contains("isServerEnabled()"),
                    "onEnable 方法体里找不到 isServerEnabled() 喵 —— 子服总开关的短路条件被删掉或"
                            + "改成恒假值，关闭态服务器会走完整启动路径（构造全部 Manager、注册监听器、"
                            + "排程定时任务、注册代理通道），而所有行为用例依然全绿。\n"
                            + "红队 J 实测：条件改 if(false) → 11/11 通过，正是本断言要堵的洞。");

            int condAt = body.indexOf("isServerEnabled()");
            int negAt = body.lastIndexOf("!", condAt);
            assertTrue(negAt >= 0 && condAt - negAt < 40,
                    "isServerEnabled() 前面找不到取反 `!` 喵 —— 条件方向反了会把「启用」当成「关闭」，"
                            + "正常服全部变成空壳。");
        }

        @Test
        @DisplayName("M1b：短路分支必须以 return 结束（只打日志不返回 = 关掉开关照常启动）")
        void shortCircuitReturnsEarly() {
            String body = onEnableBody();
            int condAt = body.indexOf("isServerEnabled()");
            assertTrue(condAt >= 0, "前置：找不到 isServerEnabled()，先修上一条。");

            int braceOpen = body.indexOf('{', condAt);
            assertTrue(braceOpen >= 0, "前置：短路条件之后找不到左花括号。");
            int braceClose = matchingBrace(body, braceOpen);
            assertTrue(braceClose > braceOpen, "前置：短路分支的花括号不配对。");

            String branch = body.substring(braceOpen, braceClose);
            assertTrue(branch.contains("return"),
                    "短路分支里找不到 return 喵 —— 只记日志不返回，关闭态服务器会继续往下执行"
                            + " enableAll()，等于开关完全失效（红队 J 的 M1b 变异）。");
            assertTrue(branch.contains("registerCommands("),
                    "短路分支里找不到 registerCommands() 喵 —— /lea reload 是关闭态下唯一的逃生门，"
                            + "不注册命令等于服主改完开关只能重启。");
            assertTrue(branch.contains("HotReloadManager"),
                    "短路分支里找不到 HotReloadManager 喵 —— AdminCommand 的 reload 分支是"
                            + " plugin.getHotReloadManager().reloadAll() 的裸调用，不构造就是 NPE。");
            // 反面：短路分支内绝不能构造「会自注册监听器」的两个类。
            assertFalse(branch.contains("new OnboardingManager("),
                    "短路分支里构造了 OnboardingManager 喵 —— 它是「构造即自注册监听器」的类，"
                            + "关闭态下会偷偷注册事件，违反「关闭时零事件开销」。");
            assertFalse(branch.contains("new WordLearningManager("),
                    "短路分支里构造了 WordLearningManager 喵 —— 同上，它会自注册监听器。");
            assertFalse(branch.contains("proxyMessenger.register("),
                    "短路分支里注册了 proxyMessenger 喵 —— 关闭态不应向代理网络广播任何消息。");
        }

        @Test
        @DisplayName("M1c：完整启动调用必须落在短路分支之后，且无条件执行")
        void fullFeaturesCallIsAfterShortCircuit() {
            String body = onEnableBody();
            int condAt = body.indexOf("isServerEnabled()");
            int callAt = body.indexOf("enableAll(");
            assertTrue(condAt >= 0 && callAt >= 0,
                    "前置：找不到 isServerEnabled() 或 enableAll( 调用。");
            assertTrue(callAt > condAt,
                    "enableAll(...) 的调用位置在短路条件<b>之前</b>喵 —— 它会先执行完整启动，"
                            + "短路分支再 return 也没用（Manager 已经全构造好了）。");

            // 调用不能被包进 if 里（否则「关闭」与「启用」两条路都不完整）。
            int braceOpen = body.indexOf('{', condAt);
            int braceClose = matchingBrace(body, braceOpen);
            assertTrue(callAt > braceClose,
                    "enableAll(...) 的调用落在短路分支的大括号<b>内部</b>喵 —— "
                            + "那样它只在关闭态执行，启用态反而什么都不做（方向完全反了）。");
        }

        @Test
        @DisplayName("M1d：enableAll(...) 恰好被调用一次（删掉它 = 插件变成静默空壳）")
        void fullFeaturesCalledExactlyOnce() {
            // 红队 I 的 P1：私有方法未被调用不是编译错误。任何人重构 onEnable 时漏掉/注释掉
            // 这一行，插件都能正常 enable、日志只打「Configuration files loaded」，但
            // playerDataStore 恒为 null → isFullyEnabled() 永远 false → /le 与 /lea 永久拒绝，
            // 且零监听器零任务。而 426 条测试全绿（B3WiringLintsTest 只读 enableAll
            // 的方法体，ServerToggleTest 用 mock 把 isFullyEnabled 打桩）。
            String body = onEnableBody();
            int count = 0;
            int at = body.indexOf("enableAll(");
            while (at >= 0) {
                count++;
                at = body.indexOf("enableAll(", at + 1);
            }
            assertEquals(1, count,
                    "onEnable 里 enableAll(...) 出现了 " + count + " 次，期望恰好 1 次 喵。\n"
                            + "0 次 = 插件变成静默空壳（enable 成功但什么都没有）；"
                            + "2 次以上 = 重复构造全部 Manager，监听器会注册两遍、任务会有两份在跑。\n"
                            + "本断言在剥注释后的文本上做，把调用注释掉同样会红。");
        }

        @Test
        @DisplayName("M1e：关闭分支里必须构造 HotReloadManager（否则 /lea reload 在关闭态 NPE）")
        void shortCircuitConstructsHotReloadManager() {
            // 红队 I 的 P1：AdminCommand 的 reload 分支是 plugin.getHotReloadManager().reloadAll()
            // 的裸调用。删掉这行构造，关闭态下唯一的逃生门 /lea reload 直接 NPE，
            // 而 adminCommandRejectsAllButReload 用 thenReturn(mock) 把依赖打桩掉了 —— 全绿。
            String body = onEnableBody();
            int condAt = body.indexOf("isServerEnabled()");
            assertTrue(condAt >= 0, "前置：找不到 isServerEnabled()。");
            int braceOpen = body.indexOf('{', condAt);
            int braceClose = matchingBrace(body, braceOpen);
            assertTrue(braceOpen > 0 && braceClose > braceOpen, "前置：短路分支花括号不配对。");

            String branch = body.substring(braceOpen, braceClose);
            assertTrue(branch.contains("new HotReloadManager("),
                    "关闭分支里找不到 new HotReloadManager(...) 喵 —— AdminCommand 的 reload 分支是"
                            + " plugin.getHotReloadManager().reloadAll() 的裸调用，不构造就是 NPE，"
                            + "关闭态下唯一的逃生门 /lea reload 会直接报错。");
        }

        /** 从 leftBrace 起做括号配对，返回匹配的右花括号下标（找不到 -1）。 */
        private int matchingBrace(String s, int leftBrace) {
            int depth = 0;
            for (int i = leftBrace; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return i;
            }
            return -1;
        }
    }

    // ==================================================================
    // 组 F —— 真实语言文件里确实存在 server.disabled（补 M5a 假绿）
    //
    // 红队 J 实测：把 zh/en 里 server.disabled 的缩进改错（变成别处的嵌套键），
    // 甚至让它变成空值，11 条行为用例全部通过 —— 因为 CommandGate 用 mock 的
    // MessageUtil，只钉住了「代码里拼的键名字面量」，不碰 yml。
    // 结果就是：服主拿到的 jar 里文案坏掉，单测发现不了。
    //
    // 本组直接读 src/main/resources/messages/*.yml 并**按点路径解析**，
    // 所以缩进写错（键跑到别的段下）会立刻变红。
    // ==================================================================
    @Nested
    @DisplayName("组 F —— 语言文件里的 server.disabled 真实存在且中英对称")
    class MessageKeyPresence {

        /** 读某个语言 yml，按 {@code a.b.c} 路径取值；返回 null 表示不存在或不是标量。 */
        private String valueAt(String lang, String dottedPath) {
            java.nio.file.Path p = java.nio.file.Paths.get(
                    System.getProperty("user.dir"), "src", "main", "resources",
                    "messages", lang + ".yml");
            if (!java.nio.file.Files.isRegularFile(p)) {
                throw new AssertionError("找不到语言文件 " + p + " 喵。");
            }
            YamlConfiguration yml = new YamlConfiguration();
            try {
                // 刻意用 loadFromString 而不是 loadConfiguration(File)：后者在解析失败时
                // 会走 Bukkit 的 logger，纯 JVM 下 Bukkit.server 为 null → NPE，
                // 于是「YAML 写坏了」会表现成 NullPointerException 而不是可读的断言失败。
                yml.loadFromString(java.nio.file.Files.readString(
                        p, java.nio.charset.StandardCharsets.UTF_8));
            } catch (org.bukkit.configuration.InvalidConfigurationException
                     | java.io.IOException | RuntimeException e) {
                throw new AssertionError("解析 " + p.getFileName() + " 失败喵（YAML 语法/缩进错误）："
                        + e.getMessage(), e);
            }
            return yml.getString(dottedPath);
        }

        @Test
        @DisplayName("zh.yml 的 server.disabled 是顶层 server 段下的键，且带 {prefix} 占位符")
        void zhHasServerDisabled() {
            String v = valueAt("zh", "server.disabled");
            assertTrue(v != null && !v.isBlank(),
                    "zh.yml 里取不到 server.disabled 喵。\n"
                            + "常见原因：写成 `server.disabled: ...` 却放在别的段内部 —— YAML 会把它"
                            + "解析成 `<该段>.server.disabled`，而代码按 `server.disabled` 查，"
                            + "玩家会看到「键名直出」或漏译。必须是顶层 `server:` 段下的 `disabled:`。");
            assertTrue(v.contains("{prefix}"),
                    "zh 的 server.disabled 里没有 {prefix} 占位符喵 —— 与其余命令提示文案风格不一致。");
        }

        @Test
        @DisplayName("en.yml 的 server.disabled 同样存在，且与 zh 占位符一致（中英对称）")
        void enHasServerDisabled() {
            String v = valueAt("en", "server.disabled");
            assertTrue(v != null && !v.isBlank(),
                    "en.yml 里取不到 server.disabled 喵 —— 英文客户端会回落到中文或键名。"
                            + "缩进陷阱同 zh：必须是顶层 server 段下的键。");
            assertTrue(v.contains("{prefix}"),
                    "en 的 server.disabled 里没有 {prefix} 占位符喵 —— 与 zh 不对称。");
        }
    }
}
