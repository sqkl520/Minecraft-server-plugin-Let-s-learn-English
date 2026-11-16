package com.letslearnenglish.minecraftplugin.util;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调度守卫（R13-L4 抽取）：插件停用窗口（调度器已关闭）内静默放弃投递，
 * 避免 {@code IllegalStateException} 冒泡成日志噪声/中断业务链。
 *
 * <p>语义照 SocialManager/SeasonManager 的 private runAsync/runMain 原样
 * （try 调度 / catch IllegalStateException 忽略）；两类的 private 帮助方法
 * 保留为薄委托，零调用点改动。AdminCommandHandler/ReportGenerator/
 * AchievementManager/LearnCommand 的同型帮助器语义微差（带 isEnabled 预检
 * 或日志级别差异），本批未迁移，就地注释指向本类（D2-5 裁决）。
 */
public final class SchedulerGuard {

    /** 停用窗口告警闸：整个窗口只打一条 INFO，避免公共调度路径被高频调用时刷屏。 */
    private static final AtomicBoolean SKIP_WARNED = new AtomicBoolean();
    private SchedulerGuard() {
    }

    /** 异步执行；插件停用窗口（调度器已关闭）静默放弃。 */
    public static void runAsync(Plugin plugin, Runnable task) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (IllegalStateException e) {
            // 停用窗口照旧放弃本次异步任务；首条 INFO 可见，其余静默。
            // 只打异常消息、不打堆栈：插件停用导致调度器关闭是**正常流程**（每次关服都会发生），
            // 打完整堆栈会让服主以为出了问题。这里保留可见性是为了让「任务真的被丢了」这件事
            // 不静默消失，而不是要暴露调用链。
            if (SKIP_WARNED.compareAndSet(false, true)) {
                plugin.getLogger().log(java.util.logging.Level.INFO,
                        "Async task skipped: scheduler unavailable (" + e.getMessage() + ")");
            }
        }
    }

    /** 回主线程执行；插件停用窗口（调度器已关闭）静默放弃。 */
    public static void runMain(Plugin plugin, Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalStateException e) {
            // 同上：停用窗口照旧放弃主线程回调；首条 INFO 可见，其余静默（同样不打堆栈）。
            if (SKIP_WARNED.compareAndSet(false, true)) {
                plugin.getLogger().log(java.util.logging.Level.INFO,
                        "Main-thread task skipped: scheduler unavailable (" + e.getMessage() + ")");
            }
        }
    }
}
