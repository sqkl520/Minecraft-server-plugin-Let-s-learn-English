package com.letslearnenglish.minecraftplugin.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired when a single word is judged as <b>learned</b> (mastered) for a player.
 *
 * <p>一次「学会一个词」的时刻。给外部插件（任务系统 / 成就扩展 / 播报 / 自定义 UI）
 * 用的事件，让它们不必再靠轮询 PAPI 占位符来蹭这个时间点。</p>
 *
 * <h2>什么时候触发</h2>
 * <p>在一个学习会话<b>结算</b>时触发（{@code WordManagerImpl.endSession}）：该词语的掌握度
 * 在本次结算中<b>从阈值以下跨到阈值以上</b>。阈值是
 * {@code word-system.review.mastery-threshold}（默认 0.85）—— 与
 * {@code PlayerDataStore.getMasteredWords} 判定「已掌握、排除出新会话词池」用的是
 * <b>同一条判据</b>，所以本事件与游戏内「这个词不再出现在新题里」是同一件事。</p>
 *
 * <p><b>每个词每次跨阈只触发一次</b>：已经是已掌握状态的词再结算（继续答对）不会重复触发。
 * 例外是「掉下阈值又重新跨回来」—— 已掌握的词答错掉到阈值以下、之后再次跨过阈值，
 * 会再触发一次（语义上确实是「重新掌握」，故意保留）。</p>
 *
 * <h2>字段</h2>
 * <ul>
 *   <li>{@link #getPlayerId()} —— 玩家 UUID（<b>不是</b> {@code Player}：会话结算可能发生在
 *       玩家已下线之后，持有 {@code Player} 引用在这里不安全；需要实体请自行
 *       {@code Bukkit.getPlayer(uuid)})。</li>
 *   <li>{@link #getWord()} —— 英文原词（词库里的 {@code english}）。</li>
 *   <li>{@link #getWordChinese()} —— 中文释义，方便外部直接展示；词库缺该字段时可能为 null。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * <p>事件<b>总是在主线程派发</b>。若结算发生在异步线程（下线结算等路径），本事件会被投递到
 * 主线程稍后执行 —— 因此监听方看到的顺序与结算顺序一致，但与触发点的「同一 tick」不保证。
 * 插件停用窗口（调度器已关闭）内的事件会被静默丢弃（不抛异常）。</p>
 *
 * <h2>不可取消</h2>
 * <p>本事件<b>不是</b> {@code Cancellable}：掌握度此刻已经写进内存与待落盘队列，取消没有
 * 任何能撤销它的语义。想「不让它算学会」请改配置里的 {@code mastery-threshold}，而不是
 * 指望在事件里拦。</p>
 */
public class PlayerWordLearnedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String word;
    private final String wordChinese;

    /**
     * @param playerId    玩家 UUID，不可为 null
     * @param word        英文原词
     * @param wordChinese 中文释义；允许 null（词库缺该字段）
     */
    public PlayerWordLearnedEvent(UUID playerId, String word, String wordChinese) {
        this.playerId = playerId;
        this.word = word;
        this.wordChinese = wordChinese;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    /** 英文原词（词库 {@code english} 字段，保留词库里的原始大小写）。 */
    public String getWord() {
        return word;
    }

    /** 中文释义；词库未填该字段时为 null。 */
    public String getWordChinese() {
        return wordChinese;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit 注册事件监听时反射调用的静态入口（{@code public static HandlerList getHandlerList()}），
     * 缺了它监听器注册会失败。
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}