package com.letslearnenglish.minecraftplugin.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired when a learning session is settled (a "quiz" ends).
 *
 * <p>一次学习会话<b>结算完成</b>的时刻。给外部插件做「完成一局发奖励 / 记任务进度 /
 * 播报战绩」这类联动。</p>
 *
 * <h2>什么时候触发</h2>
 * <p>在 {@code WordManagerImpl.endSession} 的结算逻辑<b>全部执行完之后</b>触发。
 * 三条路径都会走到这里：正常答完最后一题、玩家手动停止（{@code /le stop}）、
 * 玩家下线（{@code endQuiz}）—— 本插件的既定裁决是「中断局同样结算、分数照记」，
 * 所以本事件名里的 <i>Complete</i> 指<b>会话结束</b>，<b>不</b>表示「所有题都答完了」。
 * 需要区分「走完」与「中途停」的监听方，请用 {@code correctCount + incorrectCount}
 * 与自己的题数记录比对，或直接用 {@code LearningSession.isCompleted()} 的等价信息
 * （本事件不携带该标志，避免把内部状态机的字段固化成对外契约）。</p>
 *
 * <p>复习会话同样触发，此时 {@link #getMode()} 为 {@code "review"}。
 * 玩家没有活跃会话时的幂等清理路径（{@code session == null}）<b>不</b>触发。</p>
 *
 * <h2>字段</h2>
 * <ul>
 *   <li>{@link #getPlayerId()} —— 玩家 UUID（不持有 {@code Player}：下线结算时玩家已离线）。</li>
 *   <li>{@link #getCorrectCount()} / {@link #getIncorrectCount()} —— 本会话答对 / 答错的<b>题数</b>
 *       （答错含超时判错）。</li>
 *   <li>{@link #getScoreDelta()} —— 本会话的学习分增量（连击加成后、错误扣分后），
 *       可为负数。这是学习分口径，<b>不是</b>金币。</li>
 *   <li>{@link #getMode()} —— 会话模式：{@code spelling} / {@code choice} / {@code fill_blank} /
 *       {@code review}；未知时为 null（见下）。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * <p>与 {@link PlayerWordLearnedEvent} 相同：<b>总是主线程派发</b>，异步结算路径会被投递回
 * 主线程；停用窗口内静默丢弃。</p>
 *
 * <h2>不可取消</h2>
 * <p>{@code Cancellable} 在这里没有语义：会话已经结束、掌握度/累计分/复习种子已经写进内存，
 * 取消无法撤销任何一条，只会让「取消成功」变成一个骗人的信号。</p>
 */
public class PlayerQuizCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final int correctCount;
    private final int incorrectCount;
    private final long scoreDelta;
    private final String mode;

    /**
     * @param playerId       玩家 UUID，不可为 null
     * @param correctCount   本会话答对题数（≥0）
     * @param incorrectCount 本会话答错题数（≥0，含超时判错）
     * @param scoreDelta     本会话学习分增量，可为负
     * @param mode           会话模式；未知时传 null，getter 原样返回 null（不做归一，
     *                       免得把「不知道」伪装成某个具体模式）
     */
    public PlayerQuizCompleteEvent(UUID playerId, int correctCount, int incorrectCount,
                                   long scoreDelta, String mode) {
        this.playerId = playerId;
        this.correctCount = correctCount;
        this.incorrectCount = incorrectCount;
        this.scoreDelta = scoreDelta;
        this.mode = mode;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public int getCorrectCount() {
        return correctCount;
    }

    public int getIncorrectCount() {
        return incorrectCount;
    }

    /** 本会话学习分增量（连击加成后、错误扣分后），可能为负数。 */
    public long getScoreDelta() {
        return scoreDelta;
    }

    /**
     * 会话模式（{@code spelling} / {@code choice} / {@code fill_blank} / {@code review}）。
     *
     * <p><b>可能为 null</b>：本插件内部触发时恒非 null，但构造器不做归一化 ——
     * 第三方插件自己 new 一个事件（或未来新增模式未登记）时，null 会被原样返回。
     * 监听方请按「未知模式」处理，不要直接 {@code switch} 在它上面。</p>
     */
    public String getMode() {
        return mode;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Bukkit 注册监听时反射调用的静态入口，缺失会导致监听器注册失败。 */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}