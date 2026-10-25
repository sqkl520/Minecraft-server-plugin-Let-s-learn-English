package com.letslearnenglish.minecraftplugin.gui.identity;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;

/**
 * GUI 身份解析（R5-0）——「这个界面是谁开的」的唯一入口。
 *
 * <p><b>holder 是唯一真相源</b>。迁移前 {@code GUIUtils.isPluginGUI(String)}、各 listener 的
 * {@code title.contains(...)} 以及 {@code *GUI.isXxxTitle(...)} 把「身份」和「本地化标题文案」
 * 绑死；文案可被管理员改、可随语言切换、含占位符与动态后缀（页码、模式名、赛季剩余时间、
 * 成就计数、班级名、32 字符截断），任何一处变化都会让判定静默失效。现在身份只由
 * {@link PluginGuiHolder}（结构身份，随 {@code Bukkit.createInventory(holder, size, title)}
 * 挂在 top inventory 上）承载，本类只做三件事：
 * <ol>
 *   <li>{@link #resolve(InventoryView)} —— 身份键，O(1)</li>
 *   <li>{@link #payloadOf(InventoryView)} —— per-view 上下文（页码、tab、模式等）</li>
 *   <li>{@link #closeIfPluginGui(Player)} —— {@code onDisable} 时清场，见下文</li>
 * </ol>
 * 标题层（曾经的 40 条 {@code contains} 白名单与二确页等值比对）已在 R5-13 整体删除，
 * 全仓不再有任何按标题认界面的代码。
 *
 * <p><b>依赖约束</b>：本包只依赖 Bukkit API，故意<b>不</b> import {@code gui.GUIUtils}
 * 或任何 {@code gui.*} GUI 类 —— 否则 GUIUtils（依赖本类）与本类形成环。删掉标题层后
 * 这条约束也让本类不再需要 {@code config.ConfigManager} / {@code util.MessageUtil}。
 *
 * <p><b>ClassLoader 换代这条裂缝已被堵上</b>：holder 身份唯一的死穴是「界面在一个
 * ClassLoader 里打开、判定在另一个 ClassLoader 里做」—— PlugMan 之类热重载会换
 * ClassLoader，旧实例的 {@link PluginGuiHolder} 过不了新 ClassLoader 的
 * {@code instanceof}，{@link #resolve(InventoryView)} 恒返回 null。现在这条窗口在
 * {@code LetsLearnEnglish.onDisable()} 里被关闭：停用前先遍历在线玩家，用
 * {@link #closeIfPluginGui(Player)} 把本插件 GUI 全部关掉，于是「换 ClassLoader 的那一刻」
 * 已经不存在存活的陈旧界面。由于换 ClassLoader（PlugMan / 服务器重启 / Bukkit
 * {@code /reload}）<b>必然先调 {@code onDisable()}</b>，标题层不再承担任何自救职责。
 * 注意 {@code /lea reload}（{@code HotReloadManager.reloadAll()}）<b>不换 ClassLoader</b>，
 * 只是重载配置 + {@code clearCache()}，所以它既不需要、也不应该关 GUI（缓存清了会从 DB
 * 重读，界面内容本来就该保持）。
 *
 * <p>线程模型：全部方法无共享可变状态，可在事件线程任意调用。
 * {@link #closeIfPluginGui(Player)} 必须主线程调用（{@code closeInventory()} 的 Bukkit 约定）。
 */
public final class GuiIdentity {

    private GuiIdentity() {
        // Utility class — cannot be instantiated
    }

    /**
     * 从 {@link InventoryView} 的 <b>top inventory</b> 的 holder 解析身份。
     *
     * <p>为什么读 top 而不是 {@code getClickedInventory()}：Bukkit 语义已核验（Paper
     * 1.21.4）——{@code InventoryEvent.getInventory()} 恒为 top inventory；
     * {@code getClickedInventory()} 只在点到界面外时为 null，点玩家背包时返回的是
     * 玩家背包（其 holder 是 {@code Player}，不是 {@link PluginGuiHolder}）。
     * 所以这里必须用 {@code getTopInventory()}，且必须用 {@code instanceof}
     * 而不是 {@code getHolder() != null}（玩家背包/原版容器都有非 null holder）。
     *
     * @param view 事件/玩家当前界面，可为 null
     * @return holder 的身份键；非插件 GUI、view 为 null、holder 非本插件 holder 时返回 null
     */
    public static GuiViewKey resolve(InventoryView view) {
        PluginGuiHolder holder = holderOf(view);
        return holder == null ? null : holder.getKey();
    }

    /**
     * 读取 top inventory 上的 {@link PluginGuiHolder}。
     *
     * @param view 可为 null
     * @return holder；非本插件 GUI 或 view 为 null 时返回 null
     */
    private static PluginGuiHolder holderOf(InventoryView view) {
        if (view == null) return null;
        // getTopInventory() 在 API 里是 @NotNull，但 mock/异常 view 可能返回 null，防御一下
        if (view.getTopInventory() == null) return null;
        Object holder = view.getTopInventory().getHolder();
        // 必须 instanceof：玩家背包的 holder 是 Player，原版容器有自己的 holder，都非 null
        return (holder instanceof PluginGuiHolder pluginHolder) ? pluginHolder : null;
    }

    /**
     * 读取 top inventory holder 上的 payload。
     *
     * @param view 可为 null
     * @return holder payload；无 holder 时返回 {@link PluginGuiHolder.GuiPayload.NoPayload#INSTANCE}
     */
    public static PluginGuiHolder.GuiPayload payloadOf(InventoryView view) {
        PluginGuiHolder holder = holderOf(view);
        return holder == null
                ? PluginGuiHolder.GuiPayload.NoPayload.INSTANCE
                : holder.getPayload();
    }

    /**
     * 关闭该玩家当前打开的插件 GUI（若有），用于 {@code onDisable} 抹掉
     * 「holder 跨 ClassLoader 失效」的生存窗口。
     *
     * <p>为什么需要在停用前关 GUI：holder 判定唯一的死穴是 ClassLoader 换代。PlugMan
     * 之类的热重载插件卸载本插件时会新建 ClassLoader，旧实例（旧
     * {@link PluginGuiHolder} 类）留在玩家客户端上的界面，在新 ClassLoader 下
     * {@code instanceof} 恒为 false。而这类操作<b>必然先调 {@code onDisable()}</b>，
     * 所以在 {@code onDisable} 里把这些界面关掉，就没有「陈旧界面存活到新 ClassLoader」
     * 这回事，裂缝从根上不存在。相对地，{@code /lea reload} 不换 ClassLoader，
     * 因此 reload 路径<b>不</b>关 GUI（清了缓存会从 DB 重读，界面内容本就该保持）。
     *
     * <p><b>R5-13：判定为纯 holder</b>（见 {@link #looksLikePluginGui(InventoryView)}）。
     * 在这个调用点 holder 必然有效：此刻仍是本 ClassLoader 的代码在跑，不存在
     * 「陈旧 holder 过不了 instanceof」的中间态 —— 陈旧 holder 只可能在换 ClassLoader
     * <b>之后</b>出现，而那时已经 disable 完毕、本方法不会再被调用。至于「本次发布前
     * 就已打开、尚未挂 holder 的极旧界面」，服务器重启会连同客户端界面一起清掉，
     * PlugMan 重载也要求先 disable，故无需标题兜底。
     *
     * <p><b>调用顺序约束（重要）</b>：必须在
     * {@code WordLearningManager.clearAllSessions()} <b>之后</b>调用。
     * {@code WordLearningListener.onInventoryClose} 在
     * {@code hasActiveQuiz(uuid) == true && !isAnswering(uuid)} 时会启动答题倒计时
     * （{@code startAnswerForCurrentWord} → 发提示消息 + {@code startAnswerTimer}）。
     * 会话已清空后该判定为 false，关闭事件走 early return，不会在停用途中凭空起一个
     * 计时器，也不会给即将断线的玩家发答题提示。
     *
     * <p>本方法必须主线程调用（{@code closeInventory()} 的 Bukkit 约定）。方法本身不吞异常：
     * 单玩家失败由调用方（{@code LetsLearnEnglish.onDisable}）catch 并记 WARNING，
     * 避免一个异常玩家拖垮整个停用流程。
     *
     * @param player 目标玩家，可为 null（null 返回 false）
     * @return true 表示该玩家当前界面确实是本插件 GUI 且已关闭；false 表示无需关闭
     */
    public static boolean closeIfPluginGui(Player player) {
        if (player == null) return false;
        InventoryView view = player.getOpenInventory();
        if (view == null) return false;
        if (!looksLikePluginGui(view)) return false;
        player.closeInventory();
        return true;
    }

    /**
     * R5-13：纯 holder 的「是不是本插件 GUI」判定，仅供
     * {@link #closeIfPluginGui(Player)} 使用。
     *
     * <p>只关心「归属」、不关心具体是哪个界面，所以 holder 命中时不需要
     * {@code getKey()} —— 即使 holder 的 key 异常为 null（理论上不会），也算命中，
     * 宁可多关不可漏关。
     *
     * <p>为什么这里也不需要标题兜底：本方法是 {@code onDisable} 专属路径，执行时
     * 用的必然是<b>当前 ClassLoader</b> 的 {@link PluginGuiHolder} 类，{@code instanceof}
     * 一定有效；陈旧 holder 只会在换 ClassLoader 之后才出现，那时已 disable 完毕、
     * 此路径不再运行。所以标题兜底在这个调用点从来就不可能命中，删掉它不减少任何覆盖。
     */
    private static boolean looksLikePluginGui(InventoryView view) {
        return holderOf(view) != null;
    }
}
