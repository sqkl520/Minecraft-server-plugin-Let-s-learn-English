package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import com.letslearnenglish.minecraftplugin.gui.identity.PluginGuiHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GlobalGuiActionGuardListener} 的行为验收与接线 lint。
 *
 * <p><b>为什么必须单独测这条全局守卫</b>：它是「同一缺陷第三次复发」之后加的最后一道网。
 * 前两次（P0-5、Wave 3）都是逐 listener 补的，补完就有人以为根治了；红队穷举后仍数出
 * 6 个 listener 缺这段前置守卫。全局守卫的价值完全取决于它<b>恰好拦两类 action、恰好只拦
 * 下半区、恰好只认本插件 holder</b> —— 三条边界任一条放宽或收窄，都会在玩家侧变成
 * 「背包整理被吞」或「物品仍可被 shift-click 注入」，而这两件事在单测之外没有任何观测点。
 *
 * <p><b>本类刻意包含「必须放行」的用例，而不只是「必须拦」的</b>：一个只往紧方向写的
 * 守卫测试，无法区分「精准的守卫」和「把所有点击都 cancel 的守卫」—— 后者能让所有
 * 「必须拦」用例全绿，却让玩家点不动任何东西。放行侧的用例（非插件界面、上半区、
 * 取物族、丢弃族、窗外、负 rawSlot）就是这条守卫的「不误伤」边界。
 *
 * <p><b>为什么用真实 {@link InventoryClickEvent} 而不是 mock 事件</b>：被测方法唯一
 * 的副作用是 {@code setCancelled(true)}，而 {@code isCancelled()} 的实现是
 * {@code getResult() == Result.DENY}（Paper 1.21.4 已 javap 核验）。mock 事件时
 * {@code setCancelled} 是空实现，断言只能退化成 {@code verify(event).setCancelled(true)} ——
 * 那就变成「验证自己刚写的调用」而不是「验证事件真的被取消」。这里沿真实构造器
 * （{@code new InventoryClickEvent(view, ...)} / {@code new InventoryDragEvent(...)}）建事件，
 * view 用 mock 提供 {@code convertSlot} / {@code getInventory(rawSlot)} 两个纯查表方法，于是
 * 「上半区 / 玩家背包 / 窗外」三种语义都走的是 Bukkit 自己的分派逻辑，而不是测试自己编的。
 *
 * <p>只有 {@code getView() == null} 这一类必须用 mock 事件 —— 真实构造器会立刻在
 * {@code convertSlot} / {@code getTopInventory} 上 NPE，那样测的就不是守卫的防御分支了。
 *
 * <p><b>RT-I 之后本类新增两块</b>：
 * <ol>
 *   <li><b>拖拽路径的行为用例</b> —— 全局守卫此前只有 {@code onInventoryClick}，
 *       「免疫新增界面漏配」对拖拽路径是空的；</li>
 *   <li><b>两条源码 lint</b> —— 一条钉住「注册语句真的在源码里且只出现一次」，
 *       一条钉住 11 个 per-listener 守卫的「两个 action 常量 + 顺序铁律 + 归属判定」。
 *       这两条都属于「单测全绿但线上零覆盖」的典型：接线被注释掉、守卫被删掉，行为用例
 *       一条都不会红。</li>
 * </ol>
 *
 * <p>刻意<b>不</b>加 {@code MockitoExtension}：本类每个用例自建 view/event，
 * 部分用例不会走到 {@code getSize()} / {@code getTopInventory()}（例如普通点击在
 * action 判定处就返回），严格 stub 会把这类「路径更短」的正常行为报成
 * {@code UnnecessaryStubbingException}，噪声大于收益。
 */
@DisplayName("GlobalGuiActionGuardListener —— 全局 GUI 物品守卫")
class GlobalGuiActionGuardListenerTest {

    /** 上半区尺寸（27 格，与 PROFILE / DUEL 等真实界面一致）。 */
    private static final int TOP_SIZE = 27;

    /** 下半区第一个 rawSlot。 */
    private static final int FIRST_BOTTOM_SLOT = TOP_SIZE;

    /** 真实事件上 {@code setCancelled(true)} 之后 {@code isCancelled()} 必须为 true。 */
    private static final boolean CANCELLED = true;

    /** 11 个 GUI listener 的 {@code onInventoryClick} 签名（每个文件中唯一）。 */
    private static final String CLICK_SIGNATURE =
            "public void onInventoryClick(InventoryClickEvent event)";

    /** 越界 return 的槽位算术：{@code rawSlot < 0 || rawSlot >= topSize}（容忍空白差异）。 */
    private static final Pattern BAILOUT =
            Pattern.compile("rawSlot\\s*<\\s*0\\s*\\|\\|\\s*rawSlot\\s*>=\\s*topSize");

    /** 归属判定的六种写法（覆盖 11 个 listener 的全部实现风格）。 */
    private static final List<String> OWNERSHIP_MARKERS = List.of(
            "resolveOwnedView", "resolveSocialKey", "isManageView",
            "isScenarioSelectView", "isDuelChallengeGUI", "isDuelGUI",
            "isTrackedView", "key == null", "GuiViewKey.");

    /** 守卫里必须真的把事件取消掉 —— 只判不 cancel 的守卫是死代码。 */
    private static final String CANCEL_CALL = "setCancelled(true)";

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 被测监听器；构造只存引用，不需要任何业务依赖，故 plugin 直接给 null。 */
    private final GlobalGuiActionGuardListener listener = new GlobalGuiActionGuardListener(null);

    /**
     * 构造一个 view：top 挂插件 holder，bottom 是玩家背包。
     *
     * <p>{@code getInventory(rawSlot)} 按 rawSlot 是否落在 {@code [0, TOP_SIZE)} 分派 ——
     * 这正是 Bukkit {@code CraftInventoryView} 的语义（上半区在前、玩家背包紧随其后）。
     *
     * @param pluginGui true 表示 top 是插件 GUI（holder = {@link PluginGuiHolder}），
     *                  false 表示 top 是别的插件的容器（holder 是普通对象）
     */
    private InventoryView view(boolean pluginGui) {
        Inventory top = mock(Inventory.class);
        when(top.getSize()).thenReturn(TOP_SIZE);
        InventoryHolder holder = pluginGui
                ? PluginGuiHolder.of(GuiViewKey.PROFILE)
                : mock(InventoryHolder.class);
        when(top.getHolder()).thenReturn(holder);

        Inventory bottom = mock(Inventory.class);
        when(bottom.getSize()).thenReturn(36);
        when(bottom.getHolder()).thenReturn(mock(InventoryHolder.class));

        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(bottom);
        when(view.getInventory(anyInt())).thenAnswer(invocation -> {
            int rawSlot = invocation.getArgument(0);
            return (rawSlot >= 0 && rawSlot < TOP_SIZE) ? top : bottom;
        });
        return view;
    }

    /**
     * 用真实 Bukkit 构造器建一个点击事件。
     *
     * @param view    事件界面（{@link #view(boolean)} 的产物，或特制的窗外 view）
     * @param rawSlot 原始槽位；上半区 {@code [0, 27)}，玩家背包 {@code >= 27}
     * @param action  Bukkit 归一化后的 action
     */
    private static InventoryClickEvent click(InventoryView view, int rawSlot, InventoryAction action) {
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot,
                ClickType.LEFT, action);
    }

    /**
     * 用真实 Bukkit 构造器建一个拖拽事件。
     *
     * <p>构造器只校验 {@code oldCursor} 与 {@code newItems} 非 null，<b>不校验 map 的 value</b>
     * （它只遍历 {@code keySet()} 调一次 {@code convertSlot}）。所以这里 deliberately 用
     * {@code null} 作 value —— 这样整条夹具<b>完全不碰 {@code Material} / {@code ItemStack}
     * 的静态初始化</b>（{@code new ItemStack(Material.STONE)} 会触发 {@code Registry} 加载，
     * 在纯 paper-api classpath 下属于无关噪声）。被测方法只读 {@code getRawSlots()}，
     * 从不读 value。
     *
     * @param view     事件界面
     * @param rawSlots 本次拖拽触及的全部原始槽位
     */
    private static InventoryDragEvent drag(InventoryView view, int... rawSlots) {
        Map<Integer, ItemStack> newItems = new LinkedHashMap<>();
        for (int rawSlot : rawSlots) {
            newItems.put(rawSlot, null); // 构造器不读 value，见方法注释
        }
        return new InventoryDragEvent(view,
                null,           // newCursor（构造器允许 null）
                mock(ItemStack.class), // oldCursor（构造器要求非 null，但不读它）
                true,           // SINGLE
                newItems);
    }

    // ------------------------------------------------------------------
    // 1-3. 三类必须拦的 action
    // ------------------------------------------------------------------

    @Test
    @DisplayName("下半区 shift-click（MOVE_TO_OTHER_INVENTORY）→ cancel")
    void cancelsShiftClickFromPlayerInventory() {
        InventoryClickEvent event = click(view(true), FIRST_BOTTOM_SLOT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY);

        listener.onInventoryClick(event);

        assertTrue(event.isCancelled(),
                "插件 GUI + 下半区 shift-click 必须被取消 喵。\n"
                        + "shift-click 能把玩家背包物品塞进上半区，且不依赖空槽 —— 能合堆就直接合进"
                        + "展示物，所以「上半区已铺满」从来不是防线；物品进了点击被 cancel 的展示槽后"
                        + "既取不回也放不下，关界面即丢（P0-5 / Wave 3 的同一个缺陷）。");
    }

    @Test
    @DisplayName("下半区双击收集（COLLECT_TO_CURSOR）→ cancel")
    void cancelsCollectToCursorFromPlayerInventory() {
        InventoryClickEvent event = click(view(true), FIRST_BOTTOM_SLOT,
                InventoryAction.COLLECT_TO_CURSOR);

        listener.onInventoryClick(event);

        assertTrue(event.isCancelled(),
                "插件 GUI + 下半区双击收集必须被取消 喵。\n"
                        + "方向与 shift-click 相反：它把上半区的展示物扫进光标，玩家关界面后就把展示物"
                        + "带走了（反向失窃）。");
    }

    @Test
    @DisplayName("下半区 PICKUP_ALL（左键取整叠）→ 必须放行，不得当成双击收集")
    void allowsPickupAllFromPlayerInventory() {
        InventoryClickEvent event = click(view(true), FIRST_BOTTOM_SLOT,
                InventoryAction.PICKUP_ALL);

        listener.onInventoryClick(event);

        assertFalse(event.isCancelled(),
                "插件 GUI + 下半区 PICKUP_ALL 必须放行 喵。\n"
                        + "名字里的 ALL 极易被读成「把整片区域收上来」，从而误以为它是 COLLECT_TO_CURSOR"
                        + " 的同族、需要一起拦。实测本机 Paper 1.21.4 服务端字节码"
                        + "（ServerGamePacketListenerImpl#handleContainerClick）后确认：\n"
                        + "  · ClickType.PICKUP + 光标空 + buttonNum==0 → InventoryAction.PICKUP_ALL（左键取整叠）\n"
                        + "  · ClickType.PICKUP_ALL（真正的双击）        → ClickType.DOUBLE_CLICK + COLLECT_TO_CURSOR\n"
                        + "另一个赋值点（偏移 1025-1062）是「光标物品与槽内物品合堆」，同样只动一个槽、\n"
                        + "从不碰上半区别的格子。也就是说 PICKUP_ALL 是「玩家在自己背包里普通左键取物」，"
                        + "拦它对防注入零收益，却会把背包整理整片锁死（违反 R-01 / R-22）。\n"
                        + "若本例如为红：说明 PICKUP_ALL 被误加进了守卫的 action 集合。");
    }

    // ------------------------------------------------------------------
    // 4. 归属边界：别的插件的 GUI 一律不碰
    // ------------------------------------------------------------------

    @Test
    @DisplayName("非插件界面 + 两类 action → 一律不 cancel")
    void ignoresNonPluginGui() {
        for (InventoryAction action : new InventoryAction[]{
                InventoryAction.MOVE_TO_OTHER_INVENTORY,
                InventoryAction.COLLECT_TO_CURSOR,
                InventoryAction.PICKUP_ALL}) {
            InventoryClickEvent event = click(view(false), FIRST_BOTTOM_SLOT, action);

            listener.onInventoryClick(event);

            assertFalse(event.isCancelled(),
                    "界面归属不是本插件（holder 不是 PluginGuiHolder）时，action=" + action
                            + " 也必须放行 喵。\n"
                            + "误 cancel 会让玩家在别的插件 GUI 里既不能 shift-click 也不能双击收集，"
                            + "属于跨插件污染；这是「只对本插件界面生效」这条硬要求的唯一观测点。");
        }
    }

    // ------------------------------------------------------------------
    // 5. 上半区点击由各 listener 自己处理
    // ------------------------------------------------------------------

    @Test
    @DisplayName("插件 GUI + 上半区（rawSlot < topSize）→ 不 cancel")
    void ignoresTopInventoryClicks() {
        // 26 = 上半区最后一格；action 取两类中最具破坏性的 shift-click，
        // 确保「不 cancel」不是因为 action 不匹配，而是因为槽位在上半区。
        InventoryClickEvent event = click(view(true), TOP_SIZE - 1,
                InventoryAction.MOVE_TO_OTHER_INVENTORY);

        listener.onInventoryClick(event);

        assertFalse(event.isCancelled(),
                "上半区点击必须留给各 listener 自己的业务分派（翻页 / 确认 / 退出）喵。\n"
                        + "全局守卫若抢先 cancel，玩家点任何按钮都不再响应 —— 界面看着正常但全死。");
    }

    // ------------------------------------------------------------------
    // 6. 下半区普通点击：玩家要能整理背包
    // ------------------------------------------------------------------

    @Test
    @DisplayName("插件 GUI + 下半区 + PICKUP_ONE（普通点击）→ 不 cancel")
    void ignoresOrdinaryPlayerInventoryClicks() {
        InventoryClickEvent event = click(view(true), FIRST_BOTTOM_SLOT,
                InventoryAction.PICKUP_ONE);

        listener.onInventoryClick(event);

        assertFalse(event.isCancelled(),
                "下半区普通点击必须放行 喵：玩家开着 GUI 仍要能整理背包（R-01 / R-22 语义）。\n"
                        + "把全部下半区点击都 cancel 会让「开界面 = 背包锁死」，是比物品注入更频繁的体感事故。");
    }

    /**
     * 下半区的「取物族」action 必须<b>整族</b>放行。
     *
     * <p>为什么单列一条：{@link #allowsPickupAllFromPlayerInventory()} 只钉住了
     * {@code PICKUP_ALL} 这一个常量。若有人不满足于「不加 PICKUP_ALL」，而是改成
     * 「按名字前缀 {@code PICKUP_} 全拦」或再加 {@code PICKUP_SOME / PICKUP_HALF}，
     * 那一条仍会绿，背包却同样被锁死一半（右键取半叠、光标有物时取部分都失效）。
     * 这里把取物族一次列全，让「只拦注入方向、不拦取物方向」成为整族的断言。
     */
    @Test
    @DisplayName("插件 GUI + 下半区 + 取物族（PICKUP_SOME / PICKUP_HALF / PICKUP_FROM_BUNDLE）→ 全放行")
    void allowsAllPickupFamilyActionsOnPlayerInventory() {
        for (InventoryAction action : new InventoryAction[]{
                InventoryAction.PICKUP_ONE,
                InventoryAction.PICKUP_ALL,
                InventoryAction.PICKUP_HALF,
                InventoryAction.PICKUP_SOME,
                InventoryAction.PICKUP_FROM_BUNDLE}) {
            InventoryClickEvent event = click(view(true), FIRST_BOTTOM_SLOT, action);

            listener.onInventoryClick(event);

            assertFalse(event.isCancelled(),
                    "下半区取物族 action=" + action + " 必须放行 喵 —— 它的落点是玩家自己的格子，"
                            + "从不碰上半区，拦它对防注入零收益。\n"
                            + "本类的拦截方向只有两个：注入（MOVE_TO_OTHER_INVENTORY）与反向失窃"
                            + "（COLLECT_TO_CURSOR）；「取物族」整族都不在这两个方向里。");
        }
    }

    // ------------------------------------------------------------------
    // 7-8. 异常安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getClickedInventory() == null（窗外）→ 不抛、不 cancel")
    void ignoresNullClickedInventory() {
        // 关键：top 仍然挂<b>真实</b> PluginGuiHolder —— 否则即使删掉「窗外先 return」那一行，
        // 后面的归属判定也会顺手放行，这条用例就变成永远绿的空断言。只有「是插件 GUI、
        // 但这次点击没有落点」这个组合，才能唯一地证明那条前置判定的存在。
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(top.getSize()).thenReturn(TOP_SIZE);
        when(top.getHolder()).thenReturn(PluginGuiHolder.of(GuiViewKey.PROFILE));
        when(view.getTopInventory()).thenReturn(top);
        // 窗外点击：Bukkit 的 getInventory(rawSlot) 返回 null
        when(view.getInventory(anyInt())).thenReturn(null);

        InventoryClickEvent outside = click(view, InventoryView.OUTSIDE,
                InventoryAction.MOVE_TO_OTHER_INVENTORY);

        assertDoesNotThrow(() -> listener.onInventoryClick(outside),
                "窗外点击（getClickedInventory() == null）必须静默返回 喵 —— 这里没有 rawSlot 语义，"
                        + "拿它跟 topSize 比较是无意义的；抛异常会打断整个点击处理链。");
        assertFalse(outside.isCancelled(),
                "窗外点击不得被 cancel 喵：那会把「把光标物品丢出去」也一起吞掉。");

        // 为什么还要第二条事件：OUTSIDE 的 rawSlot 恒为 -999，本就会在 rawSlot < topSize 处
        // 放行 —— 只测它的话，把 `getClickedInventory() == null → return` 整行删掉也照样全绿。
        // 这里造一个「rawSlot 落在下半区、但 clickedInventory 为 null」的异常事件（stale slot /
        // 非标准 view 实现）：此时 action、槽位、归属三条<b>都</b>满足拦截条件，唯一的放行理由
        // 就是「这次点击没有可信落点」。
        InventoryClickEvent stale = click(view, FIRST_BOTTOM_SLOT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY);

        assertDoesNotThrow(() -> listener.onInventoryClick(stale),
                "clickedInventory 为 null 时不得抛异常 喵。");
        assertFalse(stale.isCancelled(),
                "getClickedInventory() == null 必须在 rawSlot 判定<b>之前</b> return 喵。\n"
                        + "该判定的语义是「这次点击没有可信的落点」，与 rawSlot 数值无关；"
                        + "先判 rawSlot 会把「窗外但 rawSlot 恰好落在下半区」的异常事件误 cancel。\n"
                        + "（本例如为红：说明那条前置判定被删掉或挪到了 rawSlot 之后。）");
    }

    /**
     * {@code getView() == null} 必须静默返回。
     *
     * <p><b>为什么这里要给 {@code getAction()} 打桩（RT-I 修正）</b>：起点是 mock 事件，
     * {@code getAction()} 默认返回 {@code null}，于是流程在 {@code action != MOVE... &&
     * action != COLLECT...} 处就 return 了 —— {@code view == null} 那一行<b>根本走不到</b>，
     * 把它删掉测试也照样绿（红队变异 #RT-I-5 实测）。现在把 action 钉成一个会通过过滤的值，
     * 按点击路径的真实顺序（窗外 → view → action → …）让 {@code view == null} 成为
     * <b>唯一</b>可达的出口：删掉那行就是 NPE，用例立刻变红。
     */
    @Test
    @DisplayName("getView() == null → 不抛")
    void ignoresNullView() {
        // 这一例必须用 mock 事件：真实构造器会在 view.convertSlot(...) 上 NPE，
        // 那样测到的是 Bukkit 的构造约束，不是守卫的 null 防御分支。
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(null);
        when(event.getClickedInventory()).thenReturn(mock(Inventory.class));
        // 关键：让 action 通过过滤，否则 view == null 那一行不可达（见方法注释）。
        when(event.getAction()).thenReturn(InventoryAction.MOVE_TO_OTHER_INVENTORY);

        assertDoesNotThrow(() -> listener.onInventoryClick(event),
                "getView() 返回 null 时必须静默返回、不抛异常 喵。");
        verify(event, never()).setCancelled(anyBoolean());

        // 事件本身为 null 同样必须静默（异常事件源时的最后一道容错）。
        assertDoesNotThrow(() -> listener.onInventoryClick(null),
                "event 为 null 时必须静默返回 喵 —— 事件边界上不该由一个守卫把异常抛回服务端。");
    }

    /**
     * {@code view.getTopInventory() == null} 必须静默返回。
     *
     * <p>Paper 的 API 标了 {@code @NotNull}，但异常 view 实现（或测试替身）可能违反契约；
     * 这也是 {@code GuiIdentity.holderOf} 同款防御。删掉那行会立刻 NPE —— 本用例的存在
     * 就是为了让这条防御分支不再是「不可观测的死代码」。
     */
    @Test
    @DisplayName("getTopInventory() == null → 不抛、不 cancel")
    void ignoresNullTopInventoryOnClick() {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(null);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getClickedInventory()).thenReturn(mock(Inventory.class));
        when(event.getAction()).thenReturn(InventoryAction.MOVE_TO_OTHER_INVENTORY);
        when(event.getRawSlot()).thenReturn(FIRST_BOTTOM_SLOT);

        assertDoesNotThrow(() -> listener.onInventoryClick(event),
                "getTopInventory() 返回 null 时必须静默返回 喵 —— 拿 null 去 getSize() 会 NPE，"
                        + "把整个点击处理链打断。");
        verify(event, never()).setCancelled(anyBoolean());
    }

    // ------------------------------------------------------------------
    // 9. 丢弃类 action 不该被拦
    // ------------------------------------------------------------------

    @Test
    @DisplayName("插件 GUI + 下半区 + DROP_ALL_CURSOR → 不 cancel")
    void ignoresDropActions() {
        InventoryClickEvent event = click(view(true), FIRST_BOTTOM_SLOT,
                InventoryAction.DROP_ALL_CURSOR);

        listener.onInventoryClick(event);

        assertFalse(event.isCancelled(),
                "Q 键丢光标物品（DROP_*）不得被拦 喵。\n"
                        + "它不是「注入上半区」也不是「扫走上半区」，丢掉的是玩家自己的东西；"
                        + "拦它只会让玩家在开界面时莫名其妙丢不掉手里的物品。");
    }

    // ------------------------------------------------------------------
    // 10. rawSlot 负值防御
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rawSlot = -1 → 不 cancel（负值必须走「小于 topSize」放行）")
    void doesNotCancelWhenRawSlotNegative() {
        // getClickedInventory() 故意给一个非 null（模拟「translateSlot 成 -1 但仍在界面内」
        // 这种异常组合），好让流程真的走到 rawSlot 比较，而不是在窗外判定处提前 return。
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        when(view.getInventory(anyInt())).thenReturn(mock(Inventory.class));

        InventoryClickEvent event = click(view, -1, InventoryAction.MOVE_TO_OTHER_INVENTORY);

        listener.onInventoryClick(event);

        assertFalse(event.isCancelled(),
                "rawSlot = -1 不得被 cancel 喵：-1 是 Bukkit 的「无槽位」哨兵值，"
                        + "任何基于槽位算术的判断都必须把它当越界处理，而不是当「下半区格 0」。");
    }

    // ------------------------------------------------------------------
    // 11. 拖拽路径（RT-I：点击路径拦不住拖拽）
    // ------------------------------------------------------------------

    /**
     * 拖拽触及上半区 → cancel。
     *
     * <p>这是本类此前完全缺失的路径：{@code onInventoryClick} 的 {@code setCancelled(true)}
     * 与拖拽事件<b>毫无关系</b>，原版拖拽会在整个 view 里为光标物品找落点。删掉
     * {@code onInventoryDrag} 整段（或删掉 cancel）本用例立刻变红。
     */
    @Test
    @DisplayName("插件 GUI + 拖拽落点触及上半区 → cancel")
    void cancelsDragTouchingTopInventory() {
        InventoryDragEvent event = drag(view(true), 3, 4);

        listener.onInventoryDrag(event);

        assertTrue(event.isCancelled(),
                "拖拽落进上半区必须被取消 喵。\n"
                        + "拖拽是独立事件，点击路径的 cancel 对它无效；物品被拖进上半区展示槽后，"
                        + "本类的「上半区点击一律 cancel」又把它锁死，玩家既取不回也放不下，关界面即丢。"
                        + "（本例如为红：说明 onInventoryDrag 缺失或未 setCancelled(true)。）");
    }

    /**
     * 一次拖拽有多个落点，只要有一个落进上半区，整次拖拽就必须 cancel。
     *
     * <p>为什么单列一条：Bukkit 的拖拽取消是<b>事件级</b>语义，没有「只取消其中几格」。
     * 若守卫写成「全部落点都在上半区才 cancel」或「只看第一个落点」，
     * 混合落点（下半区 + 上半区）就会漏拦 —— 而这正是玩家拖动分配时最常见的形状。
     */
    @Test
    @DisplayName("插件 GUI + 混合落点（下半区 + 上半区）→ 整次 cancel")
    void cancelsDragWithMixedSlots() {
        InventoryDragEvent event = drag(view(true), FIRST_BOTTOM_SLOT + 1, 5);

        listener.onInventoryDrag(event);

        assertTrue(event.isCancelled(),
                "混合落点（一个在下半区、一个在上半区）必须整次取消 喵。\n"
                        + "Bukkit 的拖拽取消是事件级的：只取消部分槽位在 API 上不存在，"
                        + "所以只要任一落点进了上半区，整次拖拽就必须 cancel。");
    }

    /**
     * 下半区<b>内部</b>拖拽必须放行（R-01 / R-22）。
     *
     * <p>这是拖拽路径的「不误伤」边界：玩家开着 GUI 把背包里的物品挪到背包另一格、
     * 或拖动分配，都必须照常工作。若守卫误写成「是插件 GUI 就 cancel」，
     * 这条会红 —— 而「必须拦」的三条仍然全绿。
     */
    @Test
    @DisplayName("插件 GUI + 全部落点都在下半区 → 不 cancel")
    void ignoresDragWhollyInsidePlayerInventory() {
        InventoryDragEvent event = drag(view(true), FIRST_BOTTOM_SLOT, FIRST_BOTTOM_SLOT + 1,
                FIRST_BOTTOM_SLOT + 2);

        listener.onInventoryDrag(event);

        assertFalse(event.isCancelled(),
                "下半区内部拖拽必须放行 喵：那是纯粹的背包整理，R-01 / R-22 明确要求不受影响。\n"
                        + "「开着 GUI 就连背包都不能拖」是比物品注入更频繁的体感事故。");
    }

    @Test
    @DisplayName("非插件界面 + 拖拽触及上半区 → 不 cancel")
    void ignoresDragOnNonPluginGui() {
        InventoryDragEvent event = drag(view(false), 1);

        listener.onInventoryDrag(event);

        assertFalse(event.isCancelled(),
                "界面归属不是本插件时，即使落点在上半区也必须放行 喵。\n"
                        + "拖拽路径与点击路径共用同一套纯 holder 判定，跨插件误伤会让别的插件的"
                        + "背包界面整片点不动。");
    }

    /**
     * {@code -999}（{@link InventoryView#OUTSIDE}）是窗外哨兵，必须当成「不在上半区」。
     *
     * <p>为什么关键：{@code -999 < topSize} 恒成立，少了 {@code rawSlot >= 0} 的下界判断，
     * 「鼠标拖到界面外」这一个落点就会把整次拖拽误 cancel —— 玩家给光标物品做拖拽分配
     * 会莫名失效，而且从现象上完全看不出是这里的问题。
     */
    @Test
    @DisplayName("插件 GUI + 拖拽落点只有窗外哨兵 -999 → 不 cancel")
    void ignoresDragWhoseSlotsAreOutsideSentinel() {
        InventoryDragEvent event = drag(view(true), InventoryView.OUTSIDE);

        listener.onInventoryDrag(event);

        assertFalse(event.isCancelled(),
                "rawSlot = -999（窗外哨兵）不得被当成「落在上半区」喵。\n"
                        + "-999 因为小于 topSize 而天然命中「触及上半区」的判定，"
                        + "必须靠 rawSlot >= 0 的下界把它排除掉；"
                        + "（本例如为红：说明下界判断被删掉或写反了。）");
    }

    @Test
    @DisplayName("拖拽：view == null / getTopInventory() == null / event == null → 不抛")
    void ignoresNullsOnDrag() {
        InventoryDragEvent nullView = mock(InventoryDragEvent.class);
        when(nullView.getView()).thenReturn(null);
        assertDoesNotThrow(() -> listener.onInventoryDrag(nullView),
                "getView() 返回 null 时必须静默返回 喵。");
        verify(nullView, never()).setCancelled(anyBoolean());

        InventoryView viewWithNullTop = mock(InventoryView.class);
        when(viewWithNullTop.getTopInventory()).thenReturn(null);
        InventoryDragEvent nullTop = mock(InventoryDragEvent.class);
        when(nullTop.getView()).thenReturn(viewWithNullTop);
        assertDoesNotThrow(() -> listener.onInventoryDrag(nullTop),
                "getTopInventory() 返回 null 时必须静默返回 喵。");
        verify(nullTop, never()).setCancelled(anyBoolean());

        assertDoesNotThrow(() -> listener.onInventoryDrag(null),
                "event 为 null 时必须静默返回 喵 —— 事件边界上不该由一个守卫把异常抛回服务端。");
    }

    // ------------------------------------------------------------------
    // 12. 优先级与注册（结构性断言 —— 行为测试观测不到）
    // ------------------------------------------------------------------

    /**
     * 优先级必须是 {@link EventPriority#LOWEST}。
     *
     * <p>为什么这条只能靠反射：Bukkit 按优先级从低到高调用 handler，但单测里没有事件总线，
     * 把 {@code LOWEST} 改成 {@code NORMAL} 不会让任何一条行为用例变红 —— 而线上后果是
     * 「别的 handler 已经改写了事件状态（发奖励 / 记账）之后守卫才 cancel」，属于
     * 只在真实服务器上才出现的中间态。注册顺序与执行顺序无关，所以判断依据只能是注解本身。
     *
     * <p>点击与拖拽<b>两个</b> handler 都要查：只给点击加 LOWEST、拖拽留默认 NORMAL，
     * 是一种很容易漏掉的半吊子状态 —— 拖拽事件没有别的业务 handler 也只是「碰巧」安全。
     */
    @Test
    @DisplayName("onInventoryClick / onInventoryDrag 都必须是 EventPriority.LOWEST")
    void handlerRunsAtLowestPriority() throws NoSuchMethodException {
        for (Class<?> param : new Class<?>[]{InventoryClickEvent.class, InventoryDragEvent.class}) {
            String name = param == InventoryClickEvent.class ? "onInventoryClick" : "onInventoryDrag";
            var method = GlobalGuiActionGuardListener.class.getMethod(name, param);
            EventHandler annotation = method.getAnnotation(EventHandler.class);

            assertNotNull(annotation,
                    name + " 缺 @EventHandler 喵 —— Bukkit 根本不会调用它，守卫形同不存在。");
            assertEquals(EventPriority.LOWEST, annotation.priority(),
                    name + " 必须在 LOWEST 执行 喵。\n"
                            + "NORMAL（默认）会排在业务 listener 之后：那时别的 handler 可能已经 setCurrentItem"
                            + " / 发奖励 / 记账，守卫再 cancel 只取消了一半，留下状态不一致。\n"
                            + "这条在单测里不可观测（无事件总线），只能读注解。");
        }
    }

    /**
     * 必须在 {@code LetsLearnEnglish.registerListeners()} 里注册。
     *
     * <p>类写得再对，忘了注册就是零覆盖，而「忘了注册」不会让上面任何一条用例变红 ——
     * 它们是直接调方法的。
     *
     * <p><b>RT-I 修正（变异 #34）</b>：原实现直接在原文上 {@code contains(...)}，把注册整行
     * <b>注释掉</b>之后 {@code contains} 依然命中注释里的那串字面量，测试照样全绿。
     * 现在改为在 {@code stripComments} 的产物上做判断，并加两条更强的约束：
     * <ul>
     *   <li><b>恰好 1 次</b> —— 防止重复注册（同一个 listener 注册两次会让 LOWEST 守卫
     *       在同一优先级内执行两遍，虽然 cancel 幂等，但会掩盖真正的重复 instrumentation）；</li>
     *   <li><b>落在方法前 10 行内</b> —— 防止注册语句被挪到 {@code registerListeners} 的
     *       某个条件分支或末尾的后来者之后，语义上「注册了」但读代码的人看不到全局规则的优先级。</li>
     * </ul>
     */
    @Test
    @DisplayName("LetsLearnEnglish.registerListeners 里注册了本 listener（剥注释 + 恰好一次 + 前十行）")
    void registeredInPluginBootstrap() {
        String text = sourceOf("LetsLearnEnglish.java");

        int methodStart = text.indexOf("private void registerListeners()");
        assertTrue(methodStart >= 0,
                "在 LetsLearnEnglish 里找不到 registerListeners() 喵 —— 方法被改名/移除，"
                        + "请同步本 lint（否则这条接线检查会静默失效）。");

        String signature = "private void registerListeners()";
        String body = extractBody(text, signature);
        String needle = "new GlobalGuiActionGuardListener(this)";

        int hits = 0;
        int first = -1;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + 1)) {
            if (first < 0) first = at;
            hits++;
        }

        assertTrue(hits > 0,
                "registerListeners() 里没有 new GlobalGuiActionGuardListener(this) 喵。\n"
                        + "全局守卫没注册 = 一条规则都不生效，而行为用例全部直接调方法、照样全绿 ——"
                        + "这正是「类写对了但接线漏了」这类回退唯一能被自动抓住的地方。\n"
                        + "（本 lint 已剥离注释：把注册语句注释掉同样会红。）");
        assertEquals(1, hits,
                "registerListeners() 里 new GlobalGuiActionGuardListener(this) 出现了 " + hits
                        + " 次，必须恰好 1 次 喵。\n"
                        + "重复注册会让同一个 LOWEST 守卫在一次事件里执行多遍；虽然 setCancelled(true)"
                        + " 幂等，但重复本身说明注册块被复制粘贴过，其它 listener 很可能也被重复注册了。");

        int relativeLine = 0;
        for (int i = 0; i < first; i++) {
            if (body.charAt(i) == '\n') relativeLine++;
        }
        assertTrue(relativeLine < 10,
                "注册语句在 registerListeners() 的第 " + (relativeLine + 1) + " 行，超出了前十行 喵。\n"
                        + "全局规则刻意放在方法最前，是为了让读代码的人第一眼看到「有一道全局网」；"
                        + "被挪到末尾或塞进条件分支后，「注册了」的事实仍在，但优先级语义已不可见。");
    }

    // ------------------------------------------------------------------
    // 13. per-listener 守卫 lint（RT-I：11 处守卫删掉任一都全绿）
    // ------------------------------------------------------------------

    /**
     * 11 个 GUI listener 的 per-listener 守卫 lint。
     *
     * <p><b>为什么需要它</b>：RT-I 实测「删掉任一 per-listener 守卫（含把守卫挪到越界
     * {@code return} 之后变成死代码），328/328 全绿」。真实后果不是当前失效（全局守卫
     * 兜住了），而是<b>这半边防线的腐化不可见</b> —— 将来有人删掉全局注册，会以为
     * 「还有 6 处纵深」，而纵深早就悄悄空了。
     *
     * <p>对每个 listener 的 {@code onInventoryClick} 方法体断言三件事：
     * <ol>
     *   <li>含 {@code MOVE_TO_OTHER_INVENTORY} 与 {@code COLLECT_TO_CURSOR} 两个 action 常量
     *       （只列一个就少拦一个方向），且守卫块里真的调了 {@code setCancelled(true)}；</li>
     *   <li><b>顺序铁律</b>：守卫的偏移 &lt; 越界 {@code return}
     *       （{@code rawSlot < 0 || rawSlot >= topSize}）的偏移 —— 两类 action 的 rawSlot
     *       都落在下半区，守卫一旦排在越界 return 之后就是死代码；</li>
     *   <li><b>归属判定</b>：守卫块内（或更早的位置）出现本类的身份判定，
     *       否则「只判 holder 就 cancel」会误伤别的插件界面。</li>
     * </ol>
     *
     * <p>所有断言都跑在 {@code stripComments} 的产物上：注释里出现这些常量名（本仓大量
     * 解释性注释都会提到 {@code MOVE_TO_OTHER_INVENTORY}）是常态，不剥注释的 lint
     * 会被自己的文档骗绿。
     */
    @Nested
    @DisplayName("per-listener 守卫 lint —— 11 个 GUI listener")
    class PerListenerGuardLint {

        /** RT-I 点名的 11 个 GUI listener（全部位于 {@code listener} 包）。 */
        private final List<String> guiListeners = List.of(
                "ClassBoardListener.java",
                "ChatHistoryListener.java",
                "DuelChallengeListener.java",
                "DuelGUIListener.java",
                "MainMenuListener.java",
                "MiscGUIListener.java",
                "PersonalScenarioListener.java",
                "ScenarioSelectListener.java",
                "SocialListener.java",
                "WordLearningListener.java",
                "WrongBookListener.java");

        @Test
        @DisplayName("每个 listener 都有 action 守卫，且排在越界 return 之前")
        void everyGuiListenerHasActionGuardBeforeBailout() {
            List<String> problems = new ArrayList<>();

            for (String fileName : guiListeners) {
                String body;
                try {
                    body = methodBody(fileName, CLICK_SIGNATURE);
                } catch (AssertionError e) {
                    problems.add(fileName + "：无法截取 onInventoryClick 方法体 —— " + e.getMessage());
                    continue;
                }

                int guard = body.indexOf("MOVE_TO_OTHER_INVENTORY");
                int collect = body.indexOf("COLLECT_TO_CURSOR");
                Matcher bailoutMatcher = BAILOUT.matcher(body);
                int bailout = bailoutMatcher.find() ? bailoutMatcher.start() : -1;

                if (guard < 0) {
                    problems.add(fileName + "：onInventoryClick 里没有 MOVE_TO_OTHER_INVENTORY —— "
                            + "下半区 shift-click 可以把玩家物品合堆进上半区（不依赖空槽）。");
                }
                if (collect < 0) {
                    problems.add(fileName + "：onInventoryClick 里没有 COLLECT_TO_CURSOR —— "
                            + "下半区双击收集会把上半区展示物扫进光标（反向失窃）。");
                }
                if (bailout < 0) {
                    problems.add(fileName + "：找不到 `rawSlot < 0 || rawSlot >= topSize` 这条越界 return —— "
                            + "槽位算术的写法变了，请同步本 lint（否则顺序检查会静默失效）。");
                }
                if (guard < 0 || bailout < 0) {
                    continue;
                }

                // 顺序铁律：守卫必须在越界 return 之前。
                if (guard >= bailout) {
                    problems.add(fileName + "：action 守卫出现在越界 return 之后（守卫偏移 "
                            + guard + "，return 偏移 " + bailout + "）—— "
                            + "这两类 action 的 rawSlot 落在下半区，会先被越界 return 吞掉，守卫成为死代码。");
                    continue;
                }

                // 守卫块区间 [guard, bailout)：必须真的 cancel。
                String guardRegion = body.substring(guard, bailout);
                if (!guardRegion.contains(CANCEL_CALL)) {
                    problems.add(fileName + "：action 守卫块里没有 " + CANCEL_CALL
                            + " —— 只判不 cancel 等于没拦。");
                }

                // 归属判定：在守卫块内，或在更早的位置（DuelChallenge / MainMenu / WordLearning
                // 等把「是不是我管的界面」提到了方法开头）。
                boolean ownership = false;
                for (String marker : OWNERSHIP_MARKERS) {
                    if (guardRegion.contains(marker) || body.substring(0, guard).contains(marker)) {
                        ownership = true;
                        break;
                    }
                }
                if (!ownership) {
                    problems.add(fileName + "：action 守卫缺少归属判定（"
                            + String.join(" / ", OWNERSHIP_MARKERS) + " 一个都没有）—— "
                            + "「只判 holder 就 cancel」会误伤别的插件界面。");
                }
            }

            assertTrue(problems.isEmpty(),
                    "以下 listener 的 per-listener action 守卫不满足纵深要求 喵：\n  · "
                            + String.join("\n  · ", problems)
                            + "\n\n这条 lint 钉的是「半边防线的腐化」：全局守卫兜住的时候，删掉任一"
                            + " per-listener 守卫都不会让任何行为用例变红；等到全局注册被删，"
                            + "纵深早已悄悄空了。");
        }

        /**
         * 11 个 listener 必须<b>都存在</b>，且都还带着 {@code onInventoryClick}。
         *
         * <p>为什么单列一条：上一条 lint 遍历的是写死的文件名列表，若某个 listener 被改名，
         * {@code sourceOf} 会 fail —— 但若有人把文件删了又新建一个不同名的新 listener，
         * 上一条只会少检查一个文件而<b>不报错</b>。这条把「11 个都在」变成显式断言。
         */
        @Test
        @DisplayName("11 个 GUI listener 文件都存在且都带 onInventoryClick")
        void everyListedGuiListenerStillExists() {
            List<String> missing = new ArrayList<>();
            for (String fileName : guiListeners) {
                try {
                    String body = methodBody(fileName, CLICK_SIGNATURE);
                    if (body.isBlank()) {
                        missing.add(fileName + "：onInventoryClick 方法体为空");
                    }
                } catch (AssertionError e) {
                    missing.add(fileName + "：" + e.getMessage());
                }
            }
            assertTrue(missing.isEmpty(),
                    "下列 GUI listener 缺失或已不再处理点击 喵：\n  · "
                            + String.join("\n  · ", missing));
        }
    }

    // ------------------------------------------------------------------
    // 源码读取工具（独立实现，刻意不复用 GuiWiringLintsTest / B3WiringLintsTest）
    // ------------------------------------------------------------------

    /**
     * 源码根目录：{@code <cwd>/src/main/java}。
     *
     * <p>测试必须从仓库根目录运行（{@code mvn test} 默认满足）。
     */
    private static Path srcRoot() {
        return Paths.get(System.getProperty("user.dir"), "src", "main", "java");
    }

    /**
     * 按<b>文件名</b>在源码树里唯一定位一个 {@code .java}，返回<b>剥注释 + 抹字面量</b>后的内容。
     *
     * <p>按文件名而不是拼包路径：11 个 listener 与 {@code LetsLearnEnglish} 分处两个包，
     * 写死路径会在文件被挪包时静默失效。唯一性校验把「同名文件出现两次」变成一次明确红灯。
     *
     * <p><b>为什么本类自己写一份 stripComments（不复用）</b>：复用一个共享工具会把本类
     * 的失效模式绑到那个工具上 —— 若某天有人把 {@code stripComments} 改成「只剥行注释」，
     * 三条 lint 会<b>一起</b>静默失效，而且没有任何用例会红。各写一份的成本是几十行，
     * 收益是「一个实现坏掉时，另一个仍然在报错」。
     */
    private static String sourceOf(String fileName) {
        if (!Files.isDirectory(srcRoot())) {
            fail("找不到源码根目录 " + srcRoot().toAbsolutePath()
                    + " —— 测试须从仓库根目录运行（mvn test 默认满足）喵。");
        }

        List<Path> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(srcRoot())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(hits::add);
        } catch (IOException e) {
            fail("遍历 " + srcRoot().toAbsolutePath() + " 失败喵：" + e.getMessage());
        }

        if (hits.isEmpty()) {
            fail("源码树里找不到 " + fileName + " 喵 —— 文件可能被移动/改名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (hits.size() > 1) {
            StringBuilder where = new StringBuilder();
            for (Path p : hits) {
                where.append("\n  · ").append(p.toAbsolutePath());
            }
            fail("源码树里有 " + hits.size() + " 个 " + fileName + "，无法唯一定位喵：" + where);
        }

        try {
            return stripComments(Files.readString(hits.get(0), StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("读取 " + hits.get(0).toAbsolutePath() + " 失败喵：" + e.getMessage());
            return ""; // 不可达
        }
    }

    /** {@link #sourceOf(String)} + {@link #extractBody(String, String)} 的组合（只读一次盘）。 */
    private static String methodBody(String fileName, String signature) {
        return extractBody(sourceOf(fileName), signature);
    }

    /**
     * 按方法签名截出方法体（花括号之间的文本）。
     *
     * <p>签名必须<b>在源码中唯一出现</b>：既不能有重载，也不能有同名的调用点。
     * 刻意不提供「第 N 个匹配」这种逃生口 —— 一旦需要它，说明定位不再可信。
     *
     * <p>因为 {@link #stripComments(String)} 已把注释与字符串/字符字面量抹成空格
     * （保留换行），数花括号时不必区分「注释里的 {」与「标题里的 {」，
     * 且失败消息里的行号与原文一致。
     */
    private static String extractBody(String strippedSource, String signature) {
        int sigIndex = strippedSource.indexOf(signature);
        if (sigIndex < 0) {
            fail("源码中找不到方法签名「" + signature + "」喵 —— 方法可能被改名/改签名，"
                    + "请同步本 lint（否则门禁会静默失效）。");
        }
        if (strippedSource.indexOf(signature, sigIndex + 1) >= 0) {
            fail("方法签名「" + signature + "」在源码中出现多次，无法唯一定位方法体喵 —— "
                    + "若新增了重载，请把 lint 的锚点改成更精确的签名。");
        }

        int bodyStart = strippedSource.indexOf('{', sigIndex + signature.length());
        if (bodyStart < 0) {
            fail("方法签名「" + signature + "」之后找不到左花括号喵。");
        }
        // 签名与左花括号之间若出现分号，说明匹配到的是声明/调用而不是带体的方法。
        String between = strippedSource.substring(sigIndex + signature.length(), bodyStart);
        if (between.indexOf(';') >= 0) {
            fail("方法签名「" + signature + "」与左花括号之间出现分号，"
                    + "匹配到的可能不是方法体喵：" + between.strip());
        }

        int depth = 0;
        for (int i = bodyStart; i < strippedSource.length(); i++) {
            char c = strippedSource.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return strippedSource.substring(bodyStart + 1, i);
                }
            }
        }
        fail("方法签名「" + signature + "」的花括号没有配对结束喵 —— 源码可能不完整。");
        return ""; // 不可达
    }

    /**
     * 剥掉 Java 注释，并把字符串/字符字面量（含文本块）的内容抹成空格。
     *
     * <p><b>为什么不能一步正则</b>：源码里有含 {@code //} 的 URL 字符串、有 Javadoc 里
     * 的 {@code Bukkit.createInventory(...)} 字面量、有标题里的 {@code "{page}"}。正则
     * 无法区分「注释里的引号」与「字符串里的斜杠」。这里按 JLS 的词法顺序逐字符前进：
     * 行注释 → 块注释 → 文本块 → 普通字符串 → 字符字面量 → 原样输出。
     *
     * <p>抹成<b>空格并保留换行</b>（而不是删掉）有两个好处：偏移与原文一致，失败消息
     * 里的行号能直接对照源码；且「注释里出现的 {@code MOVE_TO_OTHER_INVENTORY}」
     * 不会被误算成代码 —— 本仓的解释性注释大量提到这些常量名，这一点是 lint 可信的前提。
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;

        while (i < n) {
            char c = source.charAt(i);

            // 1) 行注释：吃到行尾，换行保留。
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }

            // 2) 块注释：吃到配对的 */（未闭合则到文末）。
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                out.append(' ').append(' ');
                i += 2;
                while (i < n) {
                    if (source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/') {
                        out.append(' ').append(' ');
                        i += 2;
                        break;
                    }
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }

            // 3) 文本块 """..."""。
            if (c == '"' && i + 2 < n
                    && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                out.append(' ').append(' ').append(' ');
                i += 3;
                while (i < n) {
                    if (source.charAt(i) == '"' && i + 2 < n
                            && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                        out.append(' ').append(' ').append(' ');
                        i += 3;
                        break;
                    }
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ').append(' ');
                        i += 2;
                        continue;
                    }
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }

            // 4) 普通字符串 / 5) 字符字面量：只认自己那一类引号。
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n) {
                    char d = source.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        out.append(' ').append(' ');
                        i += 2;
                        continue;
                    }
                    out.append(d == '\n' ? '\n' : ' ');
                    i++;
                    if (d == quote) {
                        break;
                    }
                }
                continue;
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }
}
