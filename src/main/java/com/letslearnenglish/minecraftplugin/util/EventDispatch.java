package com.letslearnenglish.minecraftplugin.util;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * 自定义事件的派发出口（本插件对外事件的唯一 {@code callEvent} 位置）。
 *
 * <h2>为什么需要它，而不是各处直接 {@code Bukkit.getPluginManager().callEvent(...)}</h2>
 * <p>本插件的结算路径<b>不区分线程</b>：{@code WordManagerImpl.endSession} 与
 * {@code AchievementManager.tryUnlock} 既可能从主线程进入（正常答题、命令），也可能从
 * 异步线程进入（会话结算的异步分支）。而 Bukkit 事件总线要求 {@code callEvent} 在主线程，
 * 异步线程调用在 Paper 上的行为是「抛异常或未定义」。三个出口各自写一遍「判线程 → 投递 →
 * 兜住停用窗口」，写法稍有出入就会漏掉其中一环 —— 这类漏掉在单测里看不见（测试替身通常
 * 都把 {@code isPrimaryThread()} 桩成 true）。收成一处之后，「线程归属」只有一个真相源。</p>
 *
 * <h2>停用窗口</h2>
 * <p>投递复用 {@link SchedulerGuard#runMain}：调度器在插件停用期间抛的是
 * {@code IllegalPluginAccessException}（{@code extends RuntimeException}，<b>不是</b>
 * {@code IllegalStateException} 的子类），SchedulerGuard 已并列捕获该类型并在停用窗口
 * 静默放弃 —— 这正是「插件卸载中不要抛异常」的要求。本类因此<b>不写自己的 catch</b>：
 * 复制一份 catch 就多一个可以写错的地方（本仓已经因为只接 {@code IllegalStateException}
 * 而全线失效过一次，见 {@code SchedulerRejectionCatchTest}）。</p>
 *
 * <h2>监听方抛异常不会反噬结算</h2>
 * <p>{@link #deliver} 兜住监听方抛出的 {@code RuntimeException} 并记 WARNING。理由：事件
 * 触发点都长在<b>结算链</b>上（掌握度落盘、成就写库、会话收尾），一个写坏的第三方监听器
 * 不该让「玩家这一局的分数没记、成就没解锁」。异常不吞掉证据 —— 带堆栈进日志。</p>
 */
public final class EventDispatch {

    private EventDispatch() {
    }

    /**
     * 派发事件：主线程立即派发，其他线程投递到主线程。插件未启用或停用窗口内<b>静默放弃</b>。
     *
     * @param plugin 本插件实例（调度与日志用），null 时直接返回
     * @param event  待派发事件，null 时直接返回
     */
    public static void fire(Plugin plugin, Event event) {
        if (!canFire(plugin, event)) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            deliver(plugin, event);
        } else {
            // 投递到主线程；真正执行时再判一次 isEnabled —— 排队期间插件可能已进入停用流程，
            // 那时自己的监听器正在被反注册，广播出去只会让第三方看到半拆状态。
            SchedulerGuard.runMain(plugin, () -> {
                if (plugin.isEnabled()) {
                    deliver(plugin, event);
                }
            });
        }
    }

    /**
     * 共用前置判断。
     *
     * <p><b>为什么要判 {@code plugin.isEnabled()}</b>：停用流程里 {@code onDisable} →
     * {@code clearAllSessions} → {@code endSession} 仍会走到派发点。那时插件已经
     * {@code isEnabled() == false}，自己的监听器正在被反注册，此时再对外广播「一局结束」
     * 只会让第三方拿到一个半拆状态的插件。这一步顺带让「未启用/启动未完成」的调用点
     * 连 {@code Bukkit.isPrimaryThread()} 都不碰。</p>
     */
    private static boolean canFire(Plugin plugin, Event event) {
        return plugin != null && event != null && plugin.isEnabled();
    }

    private static void deliver(Plugin plugin, Event event) {
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException e) {
            // 监听方的异常在这里止步：结算链不能因为第三方代码写坏而中断。
            // 打完整堆栈 —— 吞掉证据等于把「你的监听器一直没跑成」变成永久静默。
            // 对 logger 判空：catch 块自己再抛一次 NPE 会把「兜底」变成「二次故障」。
            java.util.logging.Logger logger = plugin.getLogger();
            if (logger != null) {
                logger.log(Level.WARNING,
                        "A listener threw while handling " + event.getEventName()
                                + "; the event was dropped and the caller continues", e);
            }
        }
    }
}
