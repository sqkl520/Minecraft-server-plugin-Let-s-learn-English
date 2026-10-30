package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.gui.GUIUtils;
import com.letslearnenglish.minecraftplugin.gui.identity.GuiViewKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * 全局 GUI 物品守卫 —— 一次性覆盖全部 {@link GuiViewKey} 的<b>点击</b>与<b>拖拽</b>两条路径，
 * 免疫新增界面漏配。
 *
 * <p><b>为什么需要它（同一缺陷已复发三次）</b>：
 * <ol>
 *   <li>P0-5：{@code WordLearningGUI} / {@code ClassBoardGUI} 的下半区 shift-click
 *       能把玩家物品塞进上半区展示槽，关界面即丢；</li>
 *   <li>Wave 3：{@code ProfileGUI} 设置页与 {@code MiscGUIListener} 再次出现同一路径；</li>
 *   <li>刚完成的 {@code ProfileGUI.open} / {@code SocialListener} 仍是同一形状的补丁。</li>
 * </ol>
 * 每次都是「发现一个界面漏拦 → 在那个 listener 里补一段 {@code rawSlot >= topSize &&
 * action ∈ {...} → setCancelled(true)}」，下一次又换一个界面复发。红队穷举后确认：
 * 接入本轮修复前仍有 6 个 listener 缺少这段前置守卫。
 *
 * <p><b>根因是系统性的，不是某一处手滑</b>：全仓 21 处 {@code Bukkit.createInventory}
 * 里，走 {@link GUIUtils#fillEmpty} 的 16 处调用（分布于 11 个 GUI 类）所在界面都配了
 * action 守卫 —— 因为 {@code fillEmpty} 与守卫是<b>同一批</b>补进去的；而另外 7 个类
 * 靠「自铺满」（逐个 {@code setItem} 把上半区填死）的界面，除了 {@code DUEL} 之外一个都
 * 没补。也就是说：
 * 「铺满了就不会有空槽」被当成了不变量写进了脑子里，可它是布局的偶然属性 —— 只要某页
 * 因配置关闭（例如 {@code duel.enabled=false} 时的 slot 16）、材料缺失或条件分支少渲染
 * 一格，上半区就出现落点，玩家物品随即被塞进一个「点击被 cancel、关界面即丢」的死槽。
 * 逐界面补守卫无法解决这个问题：新加的 GUI 只要不走 {@code fillEmpty}，就会静默地
 * 再漏一次。因此这里改为在事件总线最前端设一道<b>按 holder 归属</b>的统一拦截。
 *
 * <p><b>为什么必须是 {@link EventPriority#LOWEST}</b>：Bukkit 按优先级从低到高调用
 * handler，LOWEST 保证本方法在所有业务 listener 之前执行，不会出现「别的 handler 先
 * 改写了事件状态（setCurrentItem / 记账 / 发奖励）本方法才 cancel」的中间态。注册顺序
 * 与执行顺序无关 —— {@code registerEvents} 的先后只影响同优先级内的调用次序。
 *
 * <p><b>为什么不替代各 listener 里的 per-listener 守卫（纵深防御）</b>：各 listener 的
 * 守卫与「归属判定」写在同一个方法里（{@code resolveSocialKey} / {@code resolveOwnedView}
 * 等），它们的 cancel 兼有「这个界面归我管」的语义，读取的是本类辖管的 key 集合，因此
 * 天然比本类更窄、也更贴近业务。本类是最后一道网：只回答「是本插件 GUI 吗」这个结构
 * 问题，不关心具体是哪一页、也不做任何业务分派。两者重叠是刻意的 —— 任何一边被误删，
 * 另一边的测试仍会红；这不是冗余，而是同一个不变量上的两层断言。
 *
 * <p><b>拦哪两类 action（都发生在下半区，但落点在上半区）</b>：
 * <ul>
 *   <li>{@link InventoryAction#MOVE_TO_OTHER_INVENTORY}（shift-click）：把玩家背包物品
 *       搬进上半区。它<b>不依赖空槽</b> —— 能合堆就直接合进展示物，因此「上半区铺满」
 *       从来不是有效防线，铺满的界面照样会被注入。</li>
 *   <li>{@link InventoryAction#COLLECT_TO_CURSOR}（双击收集）：方向相反 —— 把上半区的
 *       展示物扫进光标，玩家关界面后就把展示物带走了，属于「反向失窃」。</li>
 * </ul>
 *
 * <p><b>为什么<b>不</b>拦 {@link InventoryAction#PICKUP_ALL}（重要，与直觉相反）</b>：
 * 名字里的 "ALL" 很容易被读成「把整片区域都收上来」，从而误以为它是双击收集的同族。
 * 实测本机 Paper 1.21.4 服务端字节码
 * （{@code ServerGamePacketListenerImpl#handleContainerClick}，
 * {@code paper-1.21.4.jar} 解包后 javap 核验）后，实际映射是：
 * <pre>
 * // ClickType.PICKUP 分支，光标为空、槽内有物时：
 * if (carried.isEmpty()) {
 *     if (clickedSlot.getItem() instanceof BundleItem && buttonNum != 0)
 *         action = PICKUP_FROM_BUNDLE;
 *     else
 *         action = (buttonNum == 0) ? InventoryAction.PICKUP_ALL    // 普通左键，取整叠
 *                                   : InventoryAction.PICKUP_HALF;   // 右键，取一半
 * }
 * // ClickType.PICKUP_ALL（真正的「双击」）分支：
 * click = ClickType.DOUBLE_CLICK;
 * action = (view 上半区或下半区含光标同材质物品) ? COLLECT_TO_CURSOR : NOTHING;
 * </pre>
 * 即：<b>Bukkit 的 {@code PICKUP_ALL} 就是「光标空着、左键拾取整叠」，双击收集则被映射成
 * {@code COLLECT_TO_CURSOR}</b>。两者是<b>不同</b>的 action，只有后者会扫上半区。
 * 因此把 {@code PICKUP_ALL} 加进本守卫，等于把玩家在自己背包里的每一次普通左键取物全部
 * cancel —— 上身效果是「开着 GUI 背包就锁死」，直接违反 R-01 / R-22「玩家整理背包不受影响」
 * 语义，而且对防物品注入<b>零收益</b>（该 action 只动玩家自己的格子，从不碰上半区）。
 * 这也是全仓 11 个 listener 的 per-listener 守卫一致只列两个 action 的原因。
 *
 * <p><b>{@code PICKUP_ALL} 的第三个赋值点（RT-I 补充证据，结论不变）</b>：
 * RT-I 在同一方法里还定位到一处给 {@code action} 赋 {@code PICKUP_ALL} 的分支，偏移
 * {@code 1025-1062}（RT-I 编号里的第三个赋值点）：
 * <b>槽内有物 + 光标有物 + {@code mayPlace} 为 false + 同材质同组件 + 数量可叠</b>
 * → 赋值 {@code PICKUP_ALL}。那是「光标物品与槽内物品合堆」的分支 —— 同样是<b>槽局部
 * 动作</b>：合完光标变少或变空、槽内变多，<b>不碰上半区的其他槽</b>，也不会把上半区的
 * 展示物扫进光标。因此把它纳入拦截依然对防注入零收益、对背包整理只有伤害，
 * <b>不拦的结论不变</b>，但证据面比只看第一个赋值点时更完整 —— 合堆路径也已核对过。
 * 真正会把上半区扫走的只有 {@code COLLECT_TO_CURSOR}。
 *
 * <p><b>点击 + 拖拽两条路径都由本类覆盖</b>：拖拽是<b>独立事件</b>
 * （{@link InventoryDragEvent}），点击路径的 {@code setCancelled(true)} 拦不住它 ——
 * 原版拖拽同样会在整个 view 里为光标物品找落点，上半区任何空格都会成为落点，物品放进去
 * 后又被本类的「上半区点击一律 cancel」锁死，关界面即丢。因此本类同时实现
 * {@link #onInventoryDrag(InventoryDragEvent)}，与点击路径<b>共用同一个归属判定</b>
 * （{@link GUIUtils#isPluginGUI(InventoryView)}）与同一条边界（只拦触及上半区的拖拽）。
 * 两条路径齐备之后，「一次性覆盖全部 {@code GuiViewKey}、免疫未来新增界面漏配」这句
 * 描述才名副其实：新增界面只要用 {@code PluginGuiHolder} 建，点击与拖拽就<b>都</b>自动
 * 落在网内，不依赖该界面的 listener 是否补了 per-listener 守卫。
 *
 * <p>刻意不拦的两类点击：
 * <ul>
 *   <li>上半区（{@code rawSlot < topSize}）的任何点击 —— 那里有各 listener 自己的业务
 *       逻辑（翻页、确认、退出），本类不参与分派，也不应该抢先 cancel 掉它们的输入；</li>
 *   <li>下半区的普通点击（{@code PICKUP_ONE} / {@code PLACE_* / DROP_*} 等）—— 玩家开着
 *       GUI 仍要能整理背包（R-01 / R-22 语义），Q 键丢光标物品也不该被吞。</li>
 * </ul>
 *
 * <p>异常安全：{@code event} / {@code view} 为 null 时静默返回；{@code getClickedInventory()
 * == null}（点到界面之外）也直接返回 —— 窗外点击没有 {@code rawSlot} 语义，拿它和
 * {@code topSize} 比较是无意义的。两个 handler 都无日志、无副作用，各自只可能调用一次
 * {@code setCancelled(true)}。
 */
public class GlobalGuiActionGuardListener implements Listener {

    /**
     * 插件实例。当前守卫是纯结构判定（holder + slot 算术 + action），不需要任何业务
     * 依赖；保留字段是为了与全仓其它 listener 的构造签名一致，并为将来的
     * 「管理员绕过权限」之类配置门留出入口（届时只在此处加判断，不动拦截语义）。
     */
    private final LetsLearnEnglish plugin;

    /**
     * @param plugin 插件实例，不可为 null
     */
    public GlobalGuiActionGuardListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * LOWEST 优先级：先于所有业务 handler 执行，见类注释「为什么必须是 LOWEST」。
     *
     * @param event 点击事件，可为 null（异常事件源时静默忽略）
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event == null) return;

        // 窗外点击（getClickedInventory() == null）没有 rawSlot 语义，先 return。
        // 这一步也顺带挡掉了「点在自己客户端界面外」这类原版不会给出落点的点击。
        if (event.getClickedInventory() == null) return;

        InventoryView view = event.getView();
        if (view == null) return;

        // 先判 action：两类之外的点击连槽位算术都不做，保持热路径最短。
        // 刻意不含 PICKUP_ALL —— 那是「左键取整叠」，点玩家背包格子也会触发，
        // 拦它等于锁死背包整理（类注释有 Paper 1.21.4 字节码实证）。
        InventoryAction action = event.getAction();
        if (action != InventoryAction.MOVE_TO_OTHER_INVENTORY
                && action != InventoryAction.COLLECT_TO_CURSOR) {
            return;
        }

        // 上半区点击交给各 listener 的业务分派（翻页/确认/退出），本类不碰。
        if (view.getTopInventory() == null) return;
        int topSize = view.getTopInventory().getSize();
        // rawSlot < topSize 含 rawSlot < 0（防御），两者都不拦。
        if (event.getRawSlot() < topSize) return;

        // 只对本插件 holder 的界面生效 —— 别的插件的 GUI（或原版容器）绝不误伤。
        if (!GUIUtils.isPluginGUI(view)) return;

        event.setCancelled(true);
    }

    /**
     * 拖拽路径的统一守卫（RT-I：点击路径拦不住拖拽，见类注释「点击 + 拖拽两条路径」）。
     *
     * <p>判定与 {@link #onInventoryClick(InventoryClickEvent)} 同一套结构语义，只是换了
     * 事件形状：
     * <ol>
     *   <li><b>归属</b>：{@link GUIUtils#isPluginGUI(InventoryView)} 纯 holder 判定 ——
     *       别的插件的 GUI / 原版容器绝不误伤；</li>
     *   <li><b>边界</b>：只在拖拽<b>触及上半区</b>时 cancel。遍历
     *       {@link InventoryDragEvent#getRawSlots()}，任一 rawSlot 落在
     *       {@code [0, topSize)} 即 cancel。</li>
     * </ol>
     *
     * <p><b>为什么只拦「触及上半区」的那一类</b>：玩家在下半区内部拖动（把背包里的物品
     * 挪到背包另一格、或拖动分配）是纯粹的背包整理，R-01 / R-22 明确要求不受影响；
     * 拖拽事件一次会带上<b>多个</b>落点，只要有一个落点进了上半区，整次拖拽就必须整体
     * cancel —— Bukkit 的拖拽取消是事件级而非槽级，没有「只取消其中几格」的语义。
     *
     * <p><b>为什么 {@code rawSlot >= 0} 的判断必须有</b>：{@code -999} 是 Bukkit 的窗外
     * 哨兵值（{@link InventoryView#OUTSIDE}），它当然小于 {@code topSize}；若少了这个
     * 下界判断，「鼠标拖到界面外」的落点会被当成「落在上半区」而误 cancel —— 玩家给
     * 光标物品做拖拽分配就会莫名失效。
     *
     * <p>异常安全：{@code event} / {@code view} / {@code getTopInventory()} 为 null 时
     * 静默返回。与点击路径同为一处 LOWEST 优先级、无日志、无副作用。
     *
     * @param event 拖拽事件，可为 null（异常事件源时静默忽略）
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event == null) return;

        InventoryView view = event.getView();
        if (view == null) return;

        Inventory top = view.getTopInventory();
        if (top == null) return;

        // 只对本插件 holder 的界面生效 —— 别的插件的 GUI（或原版容器）绝不误伤。
        if (!GUIUtils.isPluginGUI(view)) return;

        int topSize = top.getSize();
        // 一次拖拽可能有多个落点；任一落点进了上半区就整体 cancel（Bukkit 的取消是
        // 事件级语义）。rawSlot >= 0 的下界必须有：-999 是窗外哨兵。
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
