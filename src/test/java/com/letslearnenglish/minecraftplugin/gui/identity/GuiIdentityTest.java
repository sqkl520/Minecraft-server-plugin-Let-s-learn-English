package com.letslearnenglish.minecraftplugin.gui.identity;

import com.letslearnenglish.minecraftplugin.gui.GUIUtils;
import org.bukkit.block.Barrel;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Furnace;
import org.bukkit.block.Hopper;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GuiIdentity} 的单元测试（R5-0 holder 身份层）。
 *
 * <p>为什么用 Mockito 而不是 MockBukkit：被测方法只碰三个 API 面 ——
 * {@link InventoryView#getTopInventory()}、{@link Inventory#getHolder()}、
 * {@link Player#getOpenInventory()} / {@link Player#closeInventory()}。
 * 三者都是接口方法，裸 Mockito 就够，不必拉起任何服务端运行时。
 *
 * <p>这组用例的核心是守住「身份只认 {@code PluginGuiHolder}」这条红线：
 * 玩家背包的 holder 是 {@link Player}、原版容器（箱子）的 holder 是
 * {@link org.bukkit.inventory.BlockInventoryHolder}，两者都非 null。
 * 一旦有人把 {@code instanceof} 改成 {@code getHolder() != null}，
 * 全服玩家背包都会命中插件 GUI 分支。
 */
@DisplayName("GuiIdentity holder 身份解析")
class GuiIdentityTest {

    /**
     * 构造一个 top inventory 挂着指定 holder 的 view。
     *
     * @param holder 挂在 top inventory 上的 holder，可为 null（模拟原版空容器）
     */
    private static InventoryView viewWithTopHolder(InventoryHolder holder) {
        Inventory top = mock(Inventory.class);
        when(top.getHolder()).thenReturn(holder);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        return view;
    }

    /** 构造一个打开了指定 view 的玩家。 */
    private static Player playerWithView(InventoryView view) {
        Player player = mock(Player.class);
        when(player.getOpenInventory()).thenReturn(view);
        return player;
    }

    @Nested
    @DisplayName("resolve —— 身份键解析")
    class Resolve {

        @Test
        void resolveReturnsKeyForPluginHolder() {
            InventoryView view = viewWithTopHolder(PluginGuiHolder.of(GuiViewKey.SQUARE));

            assertEquals(GuiViewKey.SQUARE, GuiIdentity.resolve(view),
                    "top inventory 挂着 PluginGuiHolder(SQUARE) 时必须解析出 SQUARE；"
                            + "返回 null 说明整个 holder 身份通道失效");
        }

        @Test
        void resolveReturnsNullForPlayerInventoryHolder() {
            // 玩家背包的 holder 是 Player 本身（HumanEntity 实现 InventoryHolder），非 null
            InventoryView view = viewWithTopHolder(mock(Player.class));

            assertNull(GuiIdentity.resolve(view),
                    "holder 是 Player 时必须返回 null；若有人把 instanceof 改成 getHolder() != null，"
                            + "玩家背包会全部被误判成插件 GUI（R5 红线）");
        }

        /**
         * 原版容器（<b>非</b> {@code Player} 的、非插件 holder）必须一律返回 null。
         *
         * <p>为什么必须参数化到 6 个类型：早期只 mock 了 {@link Chest} 一个，红队实测把
         * {@code Barrel} / {@link ShulkerBox} 加进「插件 GUI 白名单」这一类错误实现后
         * 依然 0 红 —— 单类型覆盖只能证明「这一个类没被误认」，证明不了「非插件 holder
         * 一律不认」。这里挑的 6 个恰好覆盖 holder 的三种实现形态：
         * <ul>
         *   <li>地块容器接口：{@link Chest} / {@link Barrel} / {@link ShulkerBox} /
         *       {@link Hopper} / {@link Furnace}（均 {@code extends Container} →
         *       {@code TileStateInventoryHolder} → {@code InventoryHolder}）</li>
         *   <li>具体类实现的 holder：{@link DoubleChest}（{@code class} 而非 {@code interface}，
         *       继承关系与上面五个完全不同，只实现 {@code InventoryHolder}，不经 Container）</li>
         *   <li>玩家背包：见 {@link #resolveReturnsNullForPlayerInventoryHolder()}
         *       （{@code HumanEntity} 实现 {@code InventoryHolder}）</li>
         * </ul>
         *
         * <p>曾考虑加 {@code EnderChest}，实测它<b>不是</b> {@code InventoryHolder}
         * （{@code extends Lidded, TileState}），塞进本参数化会因类型不匹配而编译失败、
         * 强行传入则只会测到「一个普通对象」—— 它根本不可能出现在
         * {@code getHolder()} 的返回值位置上，加了也不增加覆盖，故有意不加。
         */
        @ParameterizedTest(name = "原版 holder {0} 必须解析为 null")
        @ValueSource(classes = {Chest.class, Barrel.class, ShulkerBox.class,
                Hopper.class, Furnace.class, DoubleChest.class})
        void resolveReturnsNullForVanillaContainerHolder(Class<? extends InventoryHolder> holderType) {
            InventoryView view = viewWithTopHolder(mock(holderType));

            assertNull(GuiIdentity.resolve(view),
                    "holder 是原版容器 " + holderType.getSimpleName() + " 时必须返回 null；"
                            + "这覆盖了「非 Player 的非插件 holder」这一分支，"
                            + "防止 instanceof 被放宽成非 null 判定（或被加进白名单）");
        }

        @Test
        void resolveReturnsNullForNullView() {
            assertNull(GuiIdentity.resolve(null),
                    "view 为 null 时必须返回 null 且不抛异常；事件边界上 view 可能尚未就绪");
        }

        @Test
        void resolveToleratesNullTopInventory() {
            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(null);

            assertNull(GuiIdentity.resolve(view),
                    "getTopInventory() 返回 null 时必须返回 null 且不抛异常；"
                            + "holderOf 里的 null 判断是这条路径的唯一防线");
        }

        @Test
        void resolveToleratesNullHolder() {
            InventoryView view = viewWithTopHolder(null);

            assertNull(GuiIdentity.resolve(view),
                    "getHolder() 返回 null（原版空容器）时必须返回 null 且不抛异常；"
                            + "不能让一次 NPE 打断整个点击/关闭事件");
        }

        @Test
        void resolveReadsTopInventoryNotClickedInventory() {
            // top 挂插件 holder、bottom 挂玩家背包 → 命中
            Inventory top = mock(Inventory.class);
            when(top.getHolder()).thenReturn(PluginGuiHolder.of(GuiViewKey.SQUARE));
            Inventory bottom = mock(Inventory.class);
            when(bottom.getHolder()).thenReturn(mock(Player.class));
            InventoryView pluginView = mock(InventoryView.class);
            when(pluginView.getTopInventory()).thenReturn(top);
            when(pluginView.getBottomInventory()).thenReturn(bottom);

            assertNotNull(GuiIdentity.resolve(pluginView),
                    "top 是插件 holder 时，即使 bottom 是玩家背包也必须解析成功；"
                            + "身份只能取自 top inventory");

            // 反过来：top 是玩家背包、bottom 才挂插件 holder → 不命中
            Inventory topVanilla = mock(Inventory.class);
            when(topVanilla.getHolder()).thenReturn(mock(Player.class));
            Inventory bottomPlugin = mock(Inventory.class);
            when(bottomPlugin.getHolder()).thenReturn(PluginGuiHolder.of(GuiViewKey.SQUARE));
            InventoryView vanillaView = mock(InventoryView.class);
            when(vanillaView.getTopInventory()).thenReturn(topVanilla);
            when(vanillaView.getBottomInventory()).thenReturn(bottomPlugin);

            assertNull(GuiIdentity.resolve(vanillaView),
                    "只有 bottom 挂插件 holder 时必须返回 null；若有人改去读点击侧或 bottom，"
                            + "判定就会反向失效");
        }
    }

    @Nested
    @DisplayName("payloadOf —— 上下文读取")
    class Payload {

        @Test
        void payloadOfReturnsNoPayloadWhenNoHolder() {
            InventoryView view = viewWithTopHolder(mock(Player.class));

            assertSame(PluginGuiHolder.GuiPayload.NoPayload.INSTANCE, GuiIdentity.payloadOf(view),
                    "无插件 holder 时必须返回 NoPayload.INSTANCE 这个单例（引用相等）；"
                            + "若改成返回 null，所有 instanceof 读取侧都会炸");
        }

        @Test
        void payloadOfReturnsSquarePage() {
            PluginGuiHolder.GuiPayload.SquarePage payload =
                    new PluginGuiHolder.GuiPayload.SquarePage(2, "weekly");
            InventoryView view = viewWithTopHolder(PluginGuiHolder.of(GuiViewKey.SQUARE, payload));

            PluginGuiHolder.GuiPayload actual = GuiIdentity.payloadOf(view);

            assertSame(payload, actual,
                    "payloadOf 必须原样返回 holder 上挂的 payload 实例，不得重新构造或包装");
            assertTrue(actual instanceof PluginGuiHolder.GuiPayload.SquarePage,
                    "SQUARE 页的 payload 必须仍是 SquarePage 类型，否则 SocialListener 的 mode 分派会落空");
            PluginGuiHolder.GuiPayload.SquarePage squarePage =
                    (PluginGuiHolder.GuiPayload.SquarePage) actual;
            assertEquals(2, squarePage.page(),
                    "页码必须原样读出 2；翻页读到错值会让玩家点下一页却停在原地");
            assertEquals("weekly", squarePage.modeKey(),
                    "模式键必须原样读出 weekly；读错会让周榜点击分派到别的排行榜");
        }
    }

    @Nested
    @DisplayName("closeIfPluginGui —— onDisable 清场")
    class CloseIfPluginGui {

        @Test
        void closeIfPluginGuiClosesAndReturnsTrue() {
            Player player = playerWithView(
                    viewWithTopHolder(PluginGuiHolder.of(GuiViewKey.SQUARE)));

            assertTrue(GuiIdentity.closeIfPluginGui(player),
                    "玩家当前界面是插件 GUI 时必须返回 true，表示确有关闭动作");
            verify(player).closeInventory();
        }

        @Test
        void closeIfPluginGuiSkipsNonPluginGui() {
            Player player = playerWithView(
                    viewWithTopHolder(mock(Chest.class)));

            assertFalse(GuiIdentity.closeIfPluginGui(player),
                    "玩家开的是原版箱子时必须返回 false；停服时误关玩家自己的箱子是体验事故");
            verify(player, never()).closeInventory();
        }

        @Test
        void closeIfPluginGuiNullSafety() {
            assertFalse(GuiIdentity.closeIfPluginGui(null),
                    "player 为 null 时必须返回 false 且不抛异常；停用流程不能因脏引用中断");

            Player playerWithoutView = mock(Player.class);
            when(playerWithoutView.getOpenInventory()).thenReturn(null);

            assertFalse(GuiIdentity.closeIfPluginGui(playerWithoutView),
                    "getOpenInventory() 返回 null 时必须返回 false 且不抛异常");

            // top inventory 为 null 的异常 view 也必须安全落到 false
            InventoryView brokenView = mock(InventoryView.class);
            when(brokenView.getTopInventory()).thenReturn(null);
            Player playerWithBrokenView = playerWithView(brokenView);

            assertFalse(GuiIdentity.closeIfPluginGui(playerWithBrokenView),
                    "top inventory 异常为 null 时必须返回 false 且不抛异常；"
                            + "一个异常玩家不该拖垮整段 onDisable 清扫");
            verify(playerWithBrokenView, never()).closeInventory();
        }

        /**
         * 关闭与否<b>只取决于 holder 类型，与 key 的取值无关</b>：所有枚举常量都必须关。
         *
         * <p>这条覆盖的是「按 key 做过滤」这类错误实现（例如只关 SQUARE、或给某些 key
         * 加白名单/黑名单）。它<b>不</b>覆盖「key 必须非 null」这种校验 —— 那种校验在当前
         * 类型下是<b>等价变异</b>，无法被任何行为测试抓住，原因写在下面，不要再为此加断言：
         * {@code PluginGuiHolder} 的私有构造器有 {@code Objects.requireNonNull(key, "key")}，
         * 且 {@code key} 是 {@code private final}、没有 setter，所以「key 为 null 的 holder」
         * 在公开 API 下不可构造；{@code looksLikePluginGui} 里的 {@code holderOf(view) != null}
         * 与 {@code resolve(view) != null} 对全部合法 holder 恒等。要真正钉死这个差别只能靠
         * 反射强行写入 final 字段构造非法状态，那会把测试绑死在字段布局上、且验证的是一段
         * 类型系统已经排除的状态，收益不抵脆弱性 —— 有意不做。
         *
         * <p>真正防「判定被放宽」的断言在 {@link GuiUtilsDelegation}：那里钉死了判定只认
         * {@code PluginGuiHolder} 类型，而不是「holder 非 null」。
         */
        @Test
        void closeIfPluginGuiIgnoresKeyValue() {
            for (GuiViewKey key : GuiViewKey.values()) {
                Player player = playerWithView(viewWithTopHolder(PluginGuiHolder.of(key)));
                InventoryView view = player.getOpenInventory();

                assertTrue(GuiIdentity.closeIfPluginGui(player),
                        "holder 的 key 为 " + key + " 时也必须返回 true；此路径只关心归属、"
                                + "不关心具体屏幕，按 key 过滤会漏关玩家的界面");
                // 同一份 view 上 resolve 必须仍能读出该 key —— 证明「不关心 key」不等于
                // 「读不出 key」（两条通道都可用，只是 closeIfPluginGui 故意不读）。
                assertEquals(key, GuiIdentity.resolve(view),
                        "同一 view 上 resolve 必须读出 " + key + "；"
                                + "若这里为 null，说明 holder 根本没挂上，"
                                + "closeIfPluginGui 的 true 就是碰巧（类型判定）而不是身份正确");
                verify(player).closeInventory();
            }
        }
    }

    /**
     * {@link GUIUtils#isPluginGUI(InventoryView)} 的委托契约。
     *
     * <p>为什么必须在这里测：该方法是<b>单行转发</b>（{@code GuiIdentity.resolve(view) != null}），
     * 而它唯一的调用点是 {@code SoundManager} 的开屏音效。红队实测把实现改成
     * {@code view != null} 后<b>全仓 0 红</b> —— 没有任何测试引用过它，等于这条判定
     * 完全裸奔：一旦被改成「非 null 即插件 GUI」，玩家开背包/开箱子都会放插件开屏音效。
     *
     * <p>四条断言足够：转发体只有「解析成功 → true / 解析失败 → false / null → false」
     * 三种结局，覆盖到就够，不必重复 {@link GuiIdentity} 的全部边界用例（那是
     * {@link Resolve} 的职责，重复只会在语义漂移时给出两处互斥的失败点）。
     */
    @Nested
    @DisplayName("GUIUtils.isPluginGUI —— 对 GuiIdentity 的转发")
    class GuiUtilsDelegation {

        @Test
        void isPluginGuiTrueForPluginHolder() {
            InventoryView view = viewWithTopHolder(PluginGuiHolder.of(GuiViewKey.MAIN_MENU));

            assertTrue(GUIUtils.isPluginGUI(view),
                    "top inventory 挂 PluginGuiHolder 时 isPluginGUI 必须为 true；"
                            + "它只做一行转发，返回 false 说明转发断了或判定语义被改窄");
        }

        @Test
        void isPluginGuiFalseForChestHolder() {
            InventoryView view = viewWithTopHolder(mock(Chest.class));

            assertFalse(GUIUtils.isPluginGUI(view),
                    "原版箱子（holder 非 null）必须为 false；若有人把转发改成 "
                            + "`view != null` 或 `getHolder() != null`，玩家开箱子也会放插件开屏音效");
        }

        @Test
        void isPluginGuiFalseForPlayerHolder() {
            InventoryView view = viewWithTopHolder(mock(Player.class));

            assertFalse(GUIUtils.isPluginGUI(view),
                    "玩家背包（holder 是 Player，非 null）必须为 false；"
                            + "这是「holder 非 null 就算插件 GUI」这个错误实现最直接的陷阱");
        }

        @Test
        void isPluginGuiFalseForNullView() {
            assertFalse(GUIUtils.isPluginGUI(null),
                    "view 为 null 时必须为 false 且不抛异常（GUIUtils 刻意不在本地重复判空，"
                            + "依赖 GuiIdentity.resolve 的防御；若转发被换成裸调用就会 NPE）");
        }
    }
}
