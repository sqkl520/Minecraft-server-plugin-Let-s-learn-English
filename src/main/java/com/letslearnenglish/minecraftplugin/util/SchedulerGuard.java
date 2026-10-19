package com.letslearnenglish.minecraftplugin.util;

import org.bukkit.plugin.Plugin;

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

    private SchedulerGuard() {
    }

    /** 异步执行；插件停用窗口（调度器已关闭）静默放弃。 */
    public static void runAsync(Plugin plugin, Runnable task) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次异步任务
        }
    }

    /** 回主线程执行；插件停用窗口（调度器已关闭）静默放弃。 */
    public static void runMain(Plugin plugin, Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalStateException ignored) {
            // 同上：停用窗口放弃主线程回调
        }
    }
}
