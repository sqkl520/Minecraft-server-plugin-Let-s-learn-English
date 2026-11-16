package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.duel.DuelManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * DuelListener — handles PlayerJoinEvent and PlayerQuitEvent for duel disconnect/reconnect.
 *
 * When a player disconnects mid-duel, the DuelManager starts a 30-second grace period.
 * If the player rejoins within the grace period, the duel resumes normally.
 * If the grace period expires, the disconnected player forfeits.
 */
public class DuelListener implements Listener {

    private final LetsLearnEnglish plugin;

    public DuelListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * F-16-05：为什么退出侧必须是 {@link EventPriority#MONITOR}。
     *
     * <p><b>为什么必须排在最后</b>：本插件在 {@code PlayerQuitEvent} 上注册的其余 handler 全部
     * 在默认 {@code NORMAL}（{@code PlayerJoinListener#onPlayerQuit} 落库+清缓存、
     * {@code SocialListener#onPlayerQuit}、{@code WordLearningListener#onPlayerQuit}、
     * {@code ChatScenarioListener#onPlayerQuit}，均为裸 {@code @EventHandler}）。停在
     * MONITOR 保证本方法在它们<b>全部</b>跑完之后才决定「这局转入宽限」。这与
     * {@code DuelManager#handleDisconnect} 开头的 {@code room == null || !room.isActive()}
     * 早退守卫是同一件事的两半：先把机会让给别的退出期 handler 收掉房间，再决定要不要起
     * 30 秒宽限任务 —— 排在最后，这个守卫看到的才是别人的最终结果。
     *
     * <p>下调到 {@code NORMAL} / {@code HIGHEST} 今天<b>观察不到</b>行为差异（其余 handler 都在
     * {@code NORMAL}），真正的风险在于它把「谁先谁后」从<b>由本类声明</b>变成<b>由未来某个
     * handler 的优先级决定</b>：一旦有人为退出期拆解逻辑加上 {@code MONITOR}，本方法就会先给
     * 一局随即被拆掉的对局起宽限任务、给对手发 {@code duel.opponent-disconnected}，宽限任务
     * 只能靠「房间已空」分支自取消善后 —— 上面那条 {@code room == null || !room.isActive()}
     * 守卫正是为这个扩展留的口。
     * 注册顺序不参与该判定：{@code registerEvents} 的先后只影响同优先级内的调用次序
     * （同结论见 {@code GlobalGuiActionGuardListener} 类注释）。
     *
     * <p><b>为什么不违反 MONITOR「只在最后观察、不改状态」的惯例</b>：该惯例防的是多个
     * handler 抢同一份状态 —— 在最后一步改状态会让前面读过它的 handler 看到旧值、且后面
     * 没有 handler 再来纠正。这里写的全部是 {@code DuelManager} 自己的私有状态
     * （{@code activeDuels} / {@code disconnectTimers} / {@code graceTasks} 与
     * {@code DuelRoom#setPaused}），只经 {@code DuelManager} 的方法进入，不改事件本身：
     * 项目构建目标 paper-api 1.21.4 的 {@code PlayerQuitEvent} 根本没有 {@code setCancelled}（不可取消），
     * 本方法也没碰 {@code quitMessage}，没动退服玩家的实体/背包/计分板。全仓退出路径中
     * 没有任何一处读取决斗状态（{@code isInDuel} 的调用方全在聊天、命令、GUI 三条路径上），
     * 所以「观察者抢先改状态」的冲突面在本事件上不存在。唯一对外副作用是给<b>仍在线</b>的
     * 对手发一条提示，对手不是本次事件的当事人，其自身 handler 不受影响。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        DuelManager duelManager = plugin.getDuelManager();
        if (duelManager != null) {
            duelManager.handleDisconnect(event.getPlayer());
        }
    }

    /**
     * F-16-05：进服侧同样是 {@link EventPriority#MONITOR}，但理由与退出侧不同 ——
     * <b>必须先说清哪一层在保证正确性</b>。
     *
     * <p><b>排序保证的实际承担者是这个 {@code runTaskLater(..., 20L)}，不是优先级</b>：
     * 本插件 {@code PlayerJoinEvent} 上只有两个 handler —— 本方法与默认 {@code NORMAL} 的
     * {@code PlayerJoinListener#onPlayerJoin}（载入数据 / 成就 / 展示设置 / 缓存玩家名，并在
     * 其中 {@code consumeOfflineDuelNotice} 取走 GM 解散的离线通告）。两者在同一 tick 内跑完，
     * 而 {@code handleReconnect} 在 tick+20 才执行，因此即便本方法被降到 {@code LOWEST}，
     * 今天也观察不到行为差异。正因如此，这里的 {@code MONITOR} 是<b>语义声明</b>：本方法只把
     * 「玩家已经完整进服」这个事实转成一次延迟重连尝试，不参与进服的任何装配。而<b>真正不可删的
     * 是那 20 tick 延迟</b> —— {@code handleReconnect} 链上会开 {@code DuelGUI}、写侧边栏
     * 计分板、重挂 BossBar，这些在玩家实体/连接尚未稳定的同一 tick 里做是脆的。改这一处时请把
     * 两者一起看：若将来有人把延迟改成 0，本方法就从「排队尾」变成「必须排在
     * {@code PlayerJoinListener} 之后」的真依赖（它是 {@code NORMAL}，本方法在
     * {@code MONITOR} 正好仍在其后），那个降优先级的自由度随之消失。
     *
     * <p>两条 handler 各管一张表，因此优先级调换也不会互相踩：{@code PlayerJoinListener} 读的
     * 是 {@code offlineNotices}（读后即清），本方法走 {@code disconnectTimers} / {@code graceTasks}。
     *
     * <p><b>为什么不违反 MONITOR「只在最后观察、不改状态」的惯例</b>：本方法体（handler
     * 本体）只是取 {@code duelManager} 与 {@code UUID}，<b>不在事件体内写任何状态</b>；
     * 状态写全部发生在延迟任务里（{@code disconnectTimers.remove} / {@code graceTasks}
     * 取消 / {@code room.setPaused(false)} / {@code scheduleWordTimeout}），此时事件早已
     * dispatch 完毕，同事件的其他 handler 已全部返回 —— 没有任何观察者可能读到「被本方法
     * 改过」的决斗状态。事件本身也未被触碰：{@code PlayerJoinEvent}
     * 同样没有 {@code setCancelled}，本方法也没改 {@code joinMessage}。
     *
     * <p>另注（与 R5-M1(c) 同源）：闭包只捕获 {@code UUID}，不持有 {@code Player} 句柄，
     * 延迟任务首行再 {@code getPlayer} + {@code isOnline()} 复检 —— 优先级再怎么调，
     * 都不会让这 1 秒窗口里的退服玩家被误处理。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        DuelManager duelManager = plugin.getDuelManager();
        if (duelManager != null) {
            UUID playerId = event.getPlayer().getUniqueId();   // 不闭包持有 Player 句柄（R5-M1(c)）
            // Run slightly delayed to ensure player is fully loaded
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;
                duelManager.handleReconnect(p);
            }, 20L); // 1 second delay
        }
    }
}
