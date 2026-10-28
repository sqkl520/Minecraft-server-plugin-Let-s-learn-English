package com.letslearnenglish.minecraftplugin;

import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时模块容器：<b>状态机 + 幂等闸 + 记账</b>，不持有任何业务逻辑。
 *
 * <p><b>为什么需要它</b>：{@code /lea reload} 把「停用」从「进程终止时的一次性收尾」改成了
 * 可反复执行的双向操作。{@link LetsLearnEnglish#enableAll()} 与
 * {@link LetsLearnEnglish#disableAll()} 必须能被连续调用而不产生重复装配 / 重复拆解，
 * 判断「这次到底该不该干活」的职责就收在这里，使入口类只关心「怎么装、怎么拆」。
 *
 * <p><b>职责边界（硬契约，请勿扩大）</b>：
 * <ol>
 *   <li>{@link #classify(boolean, boolean)} —— 纯函数真值表，决定 reload 走哪条分支；</li>
 *   <li>{@link #beginEnable()} / {@link #beginDisable()} —— 幂等闸，连调第二次返回
 *       {@code false} 且什么也不做；</li>
 *   <li>{@code track*} / {@link #releaseAll()} —— 只记账「我们排了哪些任务 / 注册了哪些
 *       监听器」，供停用时逐个 cancel 与日志统计。</li>
 * </ol>
 *
 * <p><b>关键约束（可测性契约）</b>：本类方法体内<b>不得出现任何 Bukkit 类型的方法调用</b>
 * —— 只允许存储 {@link Listener} / {@link BukkitTask} 的<b>引用</b>，绝不许调用它们的
 * 方法（{@code cancel()} / {@code equals()} 等一律禁止）。取消动作留在
 * {@link LetsLearnEnglish#disableAll()} 里做。这样本类可以在纯 JVM 单测里实例化并断言
 * 状态机与幂等性，无需 mock Bukkit。
 *
 * <p><b>线程</b>：所有方法只在主线程调用（enable/disable 均由主线程命令触发）。不做同步。
 *
 * <p><b>注意</b>：{@code listeners} 清单<b>不用于反注册</b>。反注册统一走
 * {@code HandlerList.unregisterAll(plugin)}，它能覆盖「记账之外的注册」，且天然幂等
 * （见 {@link LetsLearnEnglish#disableAll()} 的 D9）。
 */
public final class RuntimeModules {

    /** reload 前 → 后的四种迁移。 */
    public enum Transition {
        /** 配置关、运行时也没装：只刷配置，不做装配/拆解。 */
        NONE_ENABLED,
        /** 配置关、运行时也没装（语义同上，用于「启动即关闭」分支）。 */
        NONE_DISABLED,
        /** 配置开、运行时未装配：需要 {@code enableAll()}。 */
        ENABLE,
        /** 配置关、运行时已装配：需要 {@code disableAll()}。 */
        DISABLE
    }

    /**
     * 真值表（纯函数）：
     * <pre>
     * runtimeActive | configEnabled | Transition
     * false         | false         | NONE_DISABLED
     * false         | true          | ENABLE
     * true          | true          | NONE_ENABLED
     * true          | false         | DISABLE
     * </pre>
     *
     * <p>基准取 {@code runtimeActive} 而不是「reload 前的配置值」，因为它能救回一种配置值
     * 救不了的漂移：某次 {@code enableAll()} 在 {@code new StatisticsHttpServer} 处抛了
     * （端口被占），运行时必须留在未装配态以便服主重试；此时配置早已是 true，拿配置值比较
     * 会判定「无变化」，从此再也装不起来。
     */
    public static Transition classify(boolean runtimeActive, boolean configEnabled) {
        if (!runtimeActive) {
            return configEnabled ? Transition.ENABLE : Transition.NONE_DISABLED;
        }
        return configEnabled ? Transition.NONE_ENABLED : Transition.DISABLE;
    }

    private final List<Listener> listeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean active;

    /**
     * 开启闸门：首次调用返回 {@code true} 并置位；已开启时返回 {@code false}（调用方应
     * 直接 return，不得重复装配）。
     */
    public boolean beginEnable() {
        if (active) {
            return false;
        }
        active = true;
        return true;
    }

    /**
     * 关闭闸门：首次调用返回 {@code true} 并翻标志；已关闭时返回 {@code false}
     * （幂等，调用方应直接 return）。<b>先翻标志再干活</b> —— 停用过程会同步触发事件回环，
     * 回环里的 {@code isFullyEnabled()} 必须立刻为 false。
     */
    public boolean beginDisable() {
        if (!active) {
            return false;
        }
        active = false;
        return true;
    }

    /** 记一笔监听器（仅用于日志/自检，不用于反注册）。 */
    public void track(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** 记一笔我们排的 Bukkit 任务（停用时逐个 cancel 用）。 */
    public void track(BukkitTask task) {
        if (task != null) {
            tasks.add(task);
        }
    }

    /** 只清记账，<b>不</b>撤销注册、<b>不</b>取消任务。供停用收尾与重装前清场使用。 */
    public void releaseAll() {
        listeners.clear();
        tasks.clear();
    }

    public boolean isActive() {
        return active;
    }

    public int trackedListenerCount() {
        return listeners.size();
    }

    public int trackedTaskCount() {
        return tasks.size();
    }

    /**
     * 任务记账的快照（取消动作必须发生在入口类，本类不许调用 {@link BukkitTask} 的方法）。
     * 返回副本，调用方边遍历边 cancel 不会触发 {@code ConcurrentModificationException}。
     */
    public List<BukkitTask> tasksSnapshot() {
        return new ArrayList<>(tasks);
    }
}
