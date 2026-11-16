package com.letslearnenglish.minecraftplugin.gui;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round12 · A4-LOW-3 回归：{@code PersonalScenarioGUI.open} 自身必须有一道权限门。
 *
 * <h2>缺陷本体</h2>
 * 权限门原本只在 <b>4 个 listener 调用点</b>之后（{@code ScenarioSelectListener:261}、
 * {@code PersonalScenarioListener:119/130/191}），GUI 类自己零判定 ——
 * {@code grep hasPermission} 在 {@code PersonalScenarioGUI.java} 里是 0 命中。
 * 当前 4 个调用点全部有门，所以<b>现在没有越权</b>；问题是这门是「靠调用方自觉」的。
 * 第 5 个调用点出现时（维护者复制粘贴一行 {@code new PersonalScenarioGUI(plugin).open(...)}），
 * 权限门会<b>静默消失</b>，而没有任何测试或编译错误会提示。
 *
 * <p>对比 {@code ScenarioSelectGUI} 的三层结构（渲染门 + 点击门 + 列表内纵深）——
 * 个人场景管理页只做到了「调用方门」这一层。
 *
 * <h2>修法与「幂等」的确切含义</h2>
 * 在 {@code open()} 首行加一次 {@code player.hasPermission("letslearnenglish.scenario.import")}，
 * 不通过就记 FINE 并直接 return。<b>幂等</b>指：调用点已经判过的，这里再判一次也无害 ——
 * 有权限的玩家走完全相同的路径（本类有正向用例钉住这一点），没有权限的玩家在 GUI
 * 入口就被挡住，不管从哪进来。
 *
 * <h2>纯 JVM 的两个必要拦截</h2>
 * <ul>
 *   <li>{@code Bukkit.createInventory} 在纯 JVM 没有实现 → {@link MockedStatic} 拦截，
 *       并<b>计数</b>：这正是「界面真的没有被创建」的判据；</li>
 *   <li>{@code new ItemStack(...)} 会抛 {@code ExceptionInInitializerError}
 *       （{@code No RegistryAccess implementation found}）→ {@link MockedConstruction} 拦截。</li>
 * </ul>
 * 两者一起才能让「提前 return」与「继续渲染」在断言上可区分：
 * {@code createInventory} 的调用次数是最干净的单一观测点。
 *
 * <h2>被本类封死的变异（每条断言都要能红）</h2>
 * <ol>
 *   <li><b>删掉 GUI 侧那道门</b> → {@code deniedPlayerNeverGetsAnInventory} 红
 *       （{@code createInventory} 被调用 1 次）；</li>
 *   <li><b>把门写成无条件 {@code return}</b>（过度修复）→
 *       {@code permittedPlayerStillGetsTheInventory} 红；</li>
 *   <li><b>把门挪到 {@code player.openInventory} 之后</b>（等于没门）→ 第一条红；</li>
 *   <li><b>去掉 {@code logger != null} 判空</b> → 无权限用例在 mock 插件上 NPE
 *       （{@code plugin.getLogger()} 返回 null），而「守卫不抛」正是它的全部价值。</li>
 * </ol>
 */
@DisplayName("PersonalScenarioGUI 权限纵深（A4-LOW-3）")
class PersonalScenarioGuiPermissionTest {

    private static final String PERM_IMPORT = "letslearnenglish.scenario.import";
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    /** 一套 mock 协作对象 + {@link #openGui} 的执行结果。 */
    private record Result(int createInventoryCalls, Player player, Throwable thrown) { }

    /**
     * 在「静态 Bukkit + ItemStack 构造」都被拦截的窗口内执行 {@code open}，
     * 返回 {@code Bukkit.createInventory} 的调用次数。
     *
     * @param permitted    目标玩家的 {@code scenario.import} 权限
     * @param loggerIsNull 是否让 {@code plugin.getLogger()} 返回 null（模拟纯 JVM mock 插件）
     */
    private Result openGui(boolean permitted, boolean loggerIsNull) {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        Player player = mock(Player.class);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");
        when(player.hasPermission(PERM_IMPORT)).thenReturn(permitted);
        if (loggerIsNull) {
            when(plugin.getLogger()).thenReturn(null);
        } else {
            when(plugin.getLogger()).thenReturn(Logger.getLogger("PersonalScenarioGuiPermissionTest"));
        }

        // open() 在通过权限门之后才会碰到的协作对象。**只在有权限时才需要**，
        // 但为了「无权限路径也必须不碰它们」这一断言，两者都 stub 成可观测的 mock，
        // 于是 verify(never()) 能证明门确实提前 return 了。
        AIChatManager aiChatManager = mock(AIChatManager.class);
        when(plugin.getAIChatManager()).thenReturn(aiChatManager);
        when(aiChatManager.getMaxPersonalScenarios()).thenReturn(30);
        // getPersonalScenarios 返回的是 Map（key → ScenarioConfig），不是 List —— 空 map。
        when(aiChatManager.getPersonalScenarios(any(UUID.class)))
                .thenReturn(new java.util.LinkedHashMap<>());
        when(aiChatManager.getSortedScenarioConfigs(any(Player.class))).thenReturn(List.of());

        var configManager = mock(com.letslearnenglish.minecraftplugin.config.ConfigManager.class);
        var messages = mock(org.bukkit.configuration.file.FileConfiguration.class);
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(configManager.getMessageConfig(anyString())).thenReturn(messages);
        when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
        // 文案一律空串：让断言只依赖结构事实（是否建界面），不被文案牵着走。
        // 双参重载是为 getString(key, "") 形态准备的（mock 单参默认 null 会让
        // ChatColor.translateAlternateColorCodes(null) 抛异常）。
        when(messages.getString(anyString())).thenReturn("");
        when(messages.getString(anyString(), anyString())).thenReturn("");

        final int[] calls = {0};
        Throwable thrown = null;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<org.bukkit.inventory.ItemStack> ignored =
                     mockConstruction(org.bukkit.inventory.ItemStack.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        calls[0]++;
                        return mock(Inventory.class);
                    });

            try {
                new PersonalScenarioGUI(plugin).open(player, null);
            } catch (Throwable t) {
                // 捕获而不是让异常逃逸：无权限路径上「抛了」本身就是失败信号，
                // 但让它以 NPE 形态冒出来会掩盖真正的断言（「createInventory 被调了几次」）。
                // 记下来交给调用方断言，失败消息才能一句话说清缺陷。
                thrown = t;
            }
        }

        // 无权限时那三个协作对象一次都不该被碰（门在方法最前面）。
        if (!permitted) {
            verify(plugin, never()).getAIChatManager();
            verify(plugin, never()).getConfigManager();
            verify(plugin, never()).getPlayerLanguage(any(Player.class));
        }
        return new Result(calls[0], player, thrown);
    }

    // ==================================================================
    // 缺陷本体
    // ==================================================================

    @Nested
    @DisplayName("组 A —— GUI 自身必须挡住无权限者")
    class GuiGatesItself {

        /**
         * <b>这条就是缺陷本体</b>：绕过所有 listener（直接 new GUI 调 open）的无权限玩家，
         * 必须拿不到界面。删掉 GUI 侧那道门 → {@code createInventory} 被调用 1 次 → 红。
         *
         * <p>为什么这是有效的判据（而不是自证桩）：{@code createInventory} 是「界面被造出来」
         * 的唯一入口，{@code open()} 无论如何都要经过它。次数 0 只有一种解释 ——
         * 方法在造界面之前就 return 了。
         */
        @Test
        @DisplayName("无 scenario.import 权限 → 直接 return，绝不创建界面")
        void deniedPlayerNeverGetsAnInventory() {
            Result result = openGui(false, false);

            assertTrue(result.thrown() == null,
                    "无权限路径必须安静 return，不得抛异常喵。抛出：" + result.thrown()
                            + "\n（这一格代表缺陷本体：GUI 侧没有门，于是无权限玩家一路走到"
                            + "取配置/渲染，最终在 mock 环境炸掉 —— 真实服务器上不会炸，"
                            + "但界面会被真的造出来并交给玩家。）");
            assertTrue(result.createInventoryCalls() == 0,
                    "无 letslearnenglish.scenario.import 权限的玩家不得拿到个人场景管理页喵 —— "
                            + "createInventory 被调用了 " + result.createInventoryCalls() + " 次。\n"
                            + "当前 4 个 listener 调用点都有门，所以这不是「现在的越权」，而是"
                            + "「第 5 个调用点会静默无门」——GUI 侧这道幂等门是唯一兜底。");
        }

        @Test
        @DisplayName("plugin.getLogger() 为 null 时仍不抛（守卫的价值是永不抛）")
        void deniedPathDoesNotThrowWhenLoggerIsNull() {
            // 纯 JVM 单测里 plugin 是 mock，getLogger() 返回 null。若门里的日志调用
            // 没判空，这里就是 NPE —— 而「不通过就安静走开」是这道门的全部意义。
            Result result = openGui(false, true);

            assertTrue(result.thrown() == null,
                    "无权限路径必须安静 return，不得因 logger 为 null 而 NPE喵。抛出："
                            + result.thrown());
            assertTrue(result.createInventoryCalls() == 0,
                    "logger 为 null 的路径同样不得创建界面喵");
        }
    }

    // ==================================================================
    // 正向：幂等 —— 有权限者的行为一个字节都不该变
    // ==================================================================

    @Nested
    @DisplayName("组 B —— 有权限者的路径完全不变（幂等）")
    class PermittedPathUnchanged {

        /**
         * 幂等的直接证据：调用点已经判过权限的玩家，进到这里再判一次仍然照常建界面。
         * 变异指向：把门写成无条件 {@code return}（过度修复）→ 本用例红。
         */
        @Test
        @DisplayName("有 scenario.import 权限 → 照常创建界面并打开")
        void permittedPlayerStillGetsTheInventory() {
            Result result = openGui(true, false);

            assertTrue(result.createInventoryCalls() == 1,
                    "有权限的玩家必须照常拿到界面喵 —— 幂等门不该改变他们的路径。"
                            + "实际 createInventory 调用次数：" + result.createInventoryCalls());
        }
    }

    // ==================================================================
    // 结构守卫：门必须在方法最前面（早于任何协作对象访问）
    // ==================================================================

    @Nested
    @DisplayName("组 C —— 门的位置：必须早于任何协作对象访问")
    class GateIsFirst {

        /**
         * 上一条用例已经在 {@code verify(never())} 里证明了「无权限时
         * {@code getAIChatManager/getConfigManager/getPlayerLanguage} 一次都没被碰」。
         * 这条把它单独说清楚，因为它是<b>门的正确位置</b>的判据：
         *
         * <p>若门被挪到「读语言 / 取配置」之后（例如先取 lang 再判权限），
         * 无权限玩家虽然仍拿不到界面，但上述三个 verify 会红 —— 而那种形状意味着
         * 每次无权限调用都要读一次配置，且「界面没开」与「配置读取失败」混在一起。
         */
        @Test
        @DisplayName("门在最前：无权限路径不触碰配置管理器 / AI 管理器 / 语言解析")
        void gateHappensBeforeAnyCollaboratorAccess() {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            Player player = mock(Player.class);
            when(player.hasPermission(PERM_IMPORT)).thenReturn(false);
            when(player.getName()).thenReturn("Alice");
            when(plugin.getLogger()).thenReturn(Logger.getLogger("GateIsFirst"));

            Throwable thrown = null;
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<org.bukkit.inventory.ItemStack> ignored =
                         mockConstruction(org.bukkit.inventory.ItemStack.class)) {
                try {
                    new PersonalScenarioGUI(plugin).open(player, null);
                } catch (Throwable t) {
                    thrown = t;
                }
            }

            // 先断言「没抛」：门不存在时这里会在 getConfigManager() 处 NPE，
            // 若直接让异常逃逸，下面三条 verify 就永远不执行、失败消息也说不清原因。
            assertTrue(thrown == null,
                    "无权限路径必须安静 return喵。抛出：" + thrown);

            verify(plugin, never()).getConfigManager();
            verify(plugin, never()).getAIChatManager();
            verify(plugin, never()).getPlayerLanguage(any(Player.class));
            // 也不该碰玩家的背包 —— 门不通过就不该有任何「打开」动作。
            verify(player, never()).openInventory(any(Inventory.class));
        }
    }

    // ==================================================================
    // 与 ScenarioSelectGUI 的一致性：同一个权限节点
    // ==================================================================

    @Nested
    @DisplayName("组 D —— 与入口按钮 / 点击门使用同一权限节点")
    class SamePermissionNode {

        /**
         * 个人场景功能的三个面（入口按钮渲染 / 点击 / GUI 自身）必须锁在<b>同一个</b>节点上。
         * 用不同的节点（例如误写 {@code letslearnenglish.admin}）会导致
         * 「按钮出现了但点进去被挡」或反之 —— 而两者都只在特定权限组合下才可见。
         *
         * <p>本用例只断言 GUI 侧查的是这一个字面量：给目标玩家**只**授
         * {@code letslearnenglish.admin}（不授 import），仍必须被挡。
         */
        @Test
        @DisplayName("只有 admin 权限、没有 scenario.import → 仍被挡")
        void adminOnlyIsNotEnough() {
            LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
            Player player = mock(Player.class);
            when(player.hasPermission(PERM_IMPORT)).thenReturn(false);
            when(player.hasPermission("letslearnenglish.admin")).thenReturn(true);
            when(player.getName()).thenReturn("Alice");
            when(plugin.getLogger()).thenReturn(Logger.getLogger("SamePermissionNode"));

            final int[] calls = {0};
            Throwable thrown = null;
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<org.bukkit.inventory.ItemStack> ignored =
                         mockConstruction(org.bukkit.inventory.ItemStack.class)) {
                bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString()))
                        .thenAnswer(invocation -> {
                            calls[0]++;
                            return mock(Inventory.class);
                        });
                try {
                    new PersonalScenarioGUI(plugin).open(player, null);
                } catch (Throwable t) {
                    thrown = t;
                }
            }

            assertTrue(thrown == null, "无权限路径必须安静 return喵。抛出：" + thrown);
            assertTrue(calls[0] == 0,
                    "个人场景管理页的节点是 letslearnenglish.scenario.import，不是 admin喵。"
                            + "给 admin 而不给 import 却被放行，说明节点写错了 —— "
                            + "与 ScenarioSelectGUI 的入口按钮门（同节点）不再一致。");
        }
    }
}
