package com.letslearnenglish.minecraftplugin.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired when a player unlocks an achievement for the <b>first</b> time.
 *
 * <p>给外部插件接「玩家刚解锁某成就」这个时刻（发自定义奖励、推送到群、记任务进度…）。</p>
 *
 * <h2>什么时候触发</h2>
 * <p>在 {@code AchievementManager.tryUnlock} 里，<b>只有在本次调用真的解锁了</b>（即将返回
 * {@code true}）时触发。重复调用（已解锁）、模块关闭（{@code progress-system.achievements.enabled}）、
 * 被 {@code achievements-config.yml} 禁用或不在时间窗内、前置未满足（
 * {@code AchievementManager.meetsPrerequisites}）、成就缓存读失败（fail-closed）——
 * 以上任一条成立时都<b>不</b>触发。</p>
 *
 * <p><b>触发时机在解锁记录落定之后</b>：内存已标记解锁、待补写队列已登记、UPSERT 已发出。
 * 因此监听方在事件里查 {@code hasAchievement(...)} 一定为 {@code true}。</p>
 *
 * <h2>字段</h2>
 * <ul>
 *   <li>{@link #getPlayerId()} —— 玩家 UUID（不持有 {@code Player}：本方法可从异步线程到达，
 *       且玩家可能已离线）。</li>
 *   <li>{@link #getAchievementId()} —— 成就 id（{@code Achievement.getId()}，如 {@code first_word}）。
 *       <b>这是跨版本稳定的标识</b>：成就的显示名会随语言/文案调整变化，id 永不改（见
 *       {@code Achievement} 类头的 id 冻结契约）。外部存储请只存 id。</li>
 *   <li>{@link #getAchievementName()} —— 成就的<b>英文</b>显示名，纯展示用。
 *       想让展示跟随玩家语言，请用 id 调 {@code Achievement.fromId(id).getDisplayName(lang)}
 *       —— 事件在异步路径上触发，这里拿不到可靠的「玩家当前语言」。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * <p>与 {@link PlayerWordLearnedEvent} 相同：<b>总是主线程派发</b>，异步路径投递回主线程；
 * 插件停用窗口内静默丢弃。</p>
 *
 * <h2>不可取消（与「本该可以取消」的区别）</h2>
 * <p>本事件<b>不是</b> {@code Cancellable}，这不是为了省事：要让取消有意义，事件必须发在
 * 「解锁被记录之前」。而 {@code tryUnlock} 的唯一并发闸门是
 * {@code unlocked.add(achievement.getId())} 的返回值 —— 它同时承担「是否首次解锁」与
 * 「两个人同时触发时谁说话」两件事。把事件插在它之前，并发下会重复触发；插在它之后，
 * 取消则无法回滚已写入的内存态与已发出的 UPSERT（没有、也不该为它造一条回滚路径）。
 * 想控制解锁本身，请用 {@code achievements-config.yml} 的开关与前置条件，而不是在事件里拦。</p>
 */
public class PlayerAchievementUnlockEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String achievementId;
    private final String achievementName;

    /**
     * @param playerId        玩家 UUID，不可为 null
     * @param achievementId   成就 id（跨版本稳定，外部请只存它）
     * @param achievementName 成就英文显示名，允许 null（未知 id 的第三方构造）
     */
    public PlayerAchievementUnlockEvent(UUID playerId, String achievementId, String achievementName) {
        this.playerId = playerId;
        this.achievementId = achievementId;
        this.achievementName = achievementName;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    /** 成就 id（{@code Achievement.getId()}）；跨版本稳定，外部存储请只存它。 */
    public String getAchievementId() {
        return achievementId;
    }

    /** 成就英文显示名，仅供展示；可能为 null。 */
    public String getAchievementName() {
        return achievementName;
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