package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.gui.ScenarioSelectGUI;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「刷新 API 密钥」按钮（{@link ScenarioSelectGUI#RELOAD_API_SLOT}）的<b>权限门</b>行为验收
 * —— R12 / MEDIUM-3 的行为变更钉子。
 *
 * <h2>为什么这个按钮需要专门一条测试</h2>
 * 它的动作是 {@code AIChatManager.reload()}：全局重读 {@code ai_config.yml}
 * （API Key / 模型 / 超时 / 每日轮次上限）+ {@code ai_scenarios.yml}，与命令侧
 * {@code /lea reload} 同一量级；后者要 {@code letslearnenglish.admin}。
 * R-55m 曾把它放宽到 {@code scenario.import}（「导入分享码」的语义），于是
 * 一个本该只下发给自己场景的节点，顺带授予了「让全服 AI 配置从磁盘重载」的能力。
 *
 * <h2>为什么「渲染门」与「点击门」都要测</h2>
 * 这两道门在源码里是<b>两个独立</b>的 {@code hasPermission} 调用（GUI 在
 * {@code ScenarioSelectGUI.open}，listener 在 {@code handleReloadAPI}）。只测一侧的话，
 * 另一侧漂回去仍然是全绿：
 * <ul>
 *   <li>只测点击门 → 按钮对内容作者仍然可见（点下去失败，体验是「坏掉的按钮」）；</li>
 *   <li>只测渲染门 → 残留界面 / 权限中途被撤时仍可点，{@code reload()} 照跑。</li>
 * </ul>
 * 因此这里两组各测一个正向（admin 放行）与一个反向（只有 scenario.import 不放行）。
 *
 * <h2>为什么反向用例都必须喂 {@code scenario.import = true}</h2>
 * 关键对照条件是「持 {@code scenario.import} 但非 admin」。<b>不能</b>省掉这条 stub ——
 * 不铺时 {@code hasPermission} 的 mock 默认返回 false，那时「被拒绝」既可以由新门
 * 解释，也可以由「什么权限都没有」解释，测试就退化成同义反复（假绿）。
 *
 * <h2>对 {@code AIChatManager.reload()} 的断言方式</h2>
 * 用 {@code never()} / {@code times(1)} 直接钉住那个<b>有副作用的调用</b>，
 * 而不是断言 message 文案 —— 文案会被 i18n 改动，而「reload 有没有发生」
 * 才是权限门真正的后果。
 *
 * <h2>为什么渲染门用「slot 26 被 setItem 了几次」而不是 getItem 判空</h2>
 * {@code ScenarioSelectGUI.open} 会用背景玻璃板铺满 {@code 0..26}，<b>包含</b> slot 26，
 * 因此无权限时该槽也<b>不</b>为 null —— 判空无法区分「画了按钮」与「只留了背景」。
 * 唯一可靠观测量是写入次数：背景恰好 1 次，渲染了刷新按钮则 2 次。
 */
@DisplayName("ScenarioSelectReloadPermissionTest —— 刷新 API 密钥的门")
class ScenarioSelectReloadPermissionTest {

    /** 被测界面尺寸（与真实场景选择页一致，27 格）。 */
    private static final int GUI_SIZE = 27;

    private LetsLearnEnglish plugin;
    private Player player;
    private ConfigManager configManager;
    private AIChatManager aiChatManager;

    /**
     * 本类全部用例都要的三件门面：配置、AI 管理器、玩家。
     * 每例自建而非 {@code @Mock} 字段 + {@code MockitoExtension}，是为了避免严格 stub
     * 检查在「渲染门组不消费 player/AI」这类差异上报噪声（与
     * {@code GlobalGuiActionGuardListenerTest} 同口径：该测试刻意不开 MockitoExtension）。
     */
    @BeforeEach
    void setUp() {
        plugin = mock(LetsLearnEnglish.class);
        player = mock(Player.class);
        configManager = mock(ConfigManager.class);
        aiChatManager = mock(AIChatManager.class);

        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getAIChatManager()).thenReturn(aiChatManager);
    }

    // ------------------------------------------------------------------
    // 渲染门：ScenarioSelectGUI.open
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("渲染门 —— 按钮可见性")
    class RenderGate {

        @Test
        @DisplayName("持 admin：slot 26 在背景之后必须被<b>再</b>写一次（按钮渲染）")
        void adminSeesReloadButton() {
            assertEquals(SCENARIO_SELECT_FILL + 1, reloadSlotWrites(true, false),
                    "持 letslearnenglish.admin 者看不到刷新按钮 —— 该按钮的唯一合法持有者反被挡住");
        }

        @Test
        @DisplayName("只持 scenario.import（非 admin）：slot 26 只被背景铺满写过一次，不渲染按钮")
        void scenarioImporterDoesNotSeeReloadButton() {
            assertEquals(SCENARIO_SELECT_FILL, reloadSlotWrites(false, true),
                    "只持 letslearnenglish.scenario.import 者仍能看到刷新按钮："
                            + "该节点语义是「导入分享码」，不该带出全局 AI 配置重载入口");
        }

        /**
         * {@link ScenarioSelectGUI} 的背景铺满循环会写满 {@code 0..GUI_SIZE-1}，
         * <b>包含</b> slot {@code 26} —— 因此「无权限时该槽仍有物品（背景玻璃板）」
         * 无法用 {@code getItem(26) == null} 判定。唯一可靠的观测量是
         * 「该槽被 setItem 了几次」：背景恰好 1 次，按钮渲染再 +1 次。
         */
        private static final int SCENARIO_SELECT_FILL = 1;

        /**
         * 在拦截了 {@code Bukkit.createInventory} 与 {@code ItemStack} 构造的窗口里打开场景选择页，
         * 返回 slot {@link ScenarioSelectGUI#RELOAD_API_SLOT} 被 {@code setItem} 的次数。
         *
         * @param isAdmin          是否持 {@code letslearnenglish.admin}
         * @param isScenarioImport 是否持 {@code letslearnenglish.scenario.import}
         * @return 该槽位被写入的次数（背景 1 次；渲染了刷新按钮则 2 次）
         */
        private int reloadSlotWrites(boolean isAdmin, boolean isScenarioImport) {
            // 逐节点给值，不能用 anyString()：要区分「有 admin」与「只有 scenario.import」。
            when(player.hasPermission(ScenarioSelectGUI.RELOAD_API_PERMISSION)).thenReturn(isAdmin);
            when(player.hasPermission("letslearnenglish.scenario.import")).thenReturn(isScenarioImport);
            when(plugin.getPlayerLanguage(any(Player.class))).thenReturn("zh");
            // 文案一律空串：避免 ChatColor.translateAlternateColorCodes(null) NPE，
            // 也让断言只依赖「写了几次该槽」这一结构事实。
            FileConfiguration messages = mock(FileConfiguration.class);
            when(messages.getString(anyString())).thenReturn("");
            when(configManager.getMessageConfig(anyString())).thenReturn(messages);
            // 场景 / 难度给空表，页面只画背景 + 返回键 + 可能存在的管理/刷新按钮。
            when(aiChatManager.getSortedScenarioConfigs(any(Player.class))).thenReturn(List.of());
            when(aiChatManager.getSortedDifficultyConfigs()).thenReturn(List.of());
            when(aiChatManager.getPlayerDifficulty(any(Player.class))).thenReturn("beginner");

            AtomicInteger writesToReloadSlot = new AtomicInteger();
            AtomicInteger createCalls = new AtomicInteger();

            try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class);
                 MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {

                bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString()))
                        .thenAnswer(invocation -> {
                            createCalls.incrementAndGet();
                            Inventory inv = mock(Inventory.class);
                            doAnswer(setItem -> {
                                if ((Integer) setItem.getArgument(0) == ScenarioSelectGUI.RELOAD_API_SLOT) {
                                    writesToReloadSlot.incrementAndGet();
                                }
                                return null;
                                // 用无类型的 any() 而<b>不是</b> any(ItemStack.class)：背景铺满循环
                                // 写的是 bg.clone()，而 ItemStack 构造已被 mockConstruction 拦截，
                                // clone() 返回 null。Mockito 2.1+ 的 any(Class) 显式排除 null，
                                // 用它会让背景那一次写入<b>不被计入</b>，基线 1 就变成 0。
                            }).when(inv).setItem(anyInt(), any());
                            return inv;
                        });

                new ScenarioSelectGUI(plugin).open(player);
            }

            // 防空转：没有 createInventory 说明 open() 提前 return，此时「没有写该槽」
            // 与「没打开界面」不可区分 —— 本断言组的结论就不成立，必须先把这条假绿堵死。
            assertTrue(createCalls.get() > 0,
                    "ScenarioSelectGUI.open 从未调用 Bukkit.createInventory —— 本断言组的结论不成立");
            return writesToReloadSlot.get();
        }
    }

    // ------------------------------------------------------------------
    // 点击门：ScenarioSelectListener.handleReloadAPI
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("点击门 —— reload 是否真的发生")
    class ClickGate {

        @Test
        @DisplayName("持 admin：点击 slot 26 必须触发一次 AIChatManager.reload()")
        void adminClickReloads() {
            when(player.hasPermission(ScenarioSelectGUI.RELOAD_API_PERMISSION)).thenReturn(true);
            when(player.hasPermission("letslearnenglish.scenario.import")).thenReturn(false);

            clickReloadSlot();

            verify(aiChatManager, times(1)).reload();
        }

        @Test
        @DisplayName("只持 scenario.import（非 admin）：点击 slot 26 <b>不</b>触发 reload")
        void scenarioImporterClickDoesNotReload() {
            // 对照：这两条 stub 是本用例的全部意义 —— 让「被拒绝」只能由新门解释。
            when(player.hasPermission(ScenarioSelectGUI.RELOAD_API_PERMISSION)).thenReturn(false);
            when(player.hasPermission("letslearnenglish.scenario.import")).thenReturn(true);

            clickReloadSlot();

            verify(aiChatManager, never()).reload();
        }

        /**
         * 走真实的 {@link InventoryClickEvent} → {@link ScenarioSelectListener#onInventoryClick}
         * 路径，点击 slot {@link ScenarioSelectGUI#RELOAD_API_SLOT}。
         *
         * <p>界面身份用真实的 {@link PluginGuiHolder}（{@link GuiViewKey#SCENARIO_SELECT}），
         * 而不是 mock 掉身份判定 —— 这样「holder 判定 + 槽位分派 + 权限门」三段都真的跑到了，
         * 任何一段改动都会让本用例变红。
         *
         * <p>{@code SoundManager.playSound} 是静态方法且内部取
         * {@link LetsLearnEnglish#getInstance()}（mock 环境下为 null，于是直接 return），
         * 无需拦截。
         */
        private void clickReloadSlot() {
            Inventory top = mock(Inventory.class);
            when(top.getSize()).thenReturn(GUI_SIZE);
            when(top.getHolder()).thenReturn(PluginGuiHolder.of(GuiViewKey.SCENARIO_SELECT));

            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(top);

            // 走到槽位分派前，listener 需要 getCurrentItem() != null 与 whoClicked 是 Player。
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getClickedInventory()).thenReturn(top);
            when(event.getView()).thenReturn(view);
            when(event.getRawSlot()).thenReturn(ScenarioSelectGUI.RELOAD_API_SLOT);
            when(event.getSlot()).thenReturn(ScenarioSelectGUI.RELOAD_API_SLOT);
            when(event.getAction()).thenReturn(InventoryAction.PICKUP_ALL);
            when(event.getCurrentItem()).thenReturn(mock(ItemStack.class));
            when(event.getWhoClicked()).thenReturn(player);

            // MessageUtil 只在「reload 通过门之后」被调用。plugin 是 mock，不铺这条
            // 会让 plugin.getMessageUtil() 返回 null 并在其后 .getPlayerMessage(...) 上 NPE
            // —— 那会把「门放行」误报成「测试崩了」，所以必须给一个非 null 门面。
            // 不 stub 返回值：mock 默认 null，player.sendMessage(null) 在 mock Player 上是空操作。
            when(plugin.getMessageUtil()).thenReturn(mock(MessageUtil.class));

            new ScenarioSelectListener(plugin).onInventoryClick(event);
        }
    }
}
