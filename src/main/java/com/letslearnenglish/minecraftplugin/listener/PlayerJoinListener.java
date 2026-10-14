package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Player Join Listener
 *
 * Handles player join/quit events for data loading and cleanup.
 */
public class PlayerJoinListener implements Listener {

    private final LetsLearnEnglish plugin;

    public PlayerJoinListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getPlayerDataStore().loadPlayerData(event.getPlayer().getUniqueId());
        plugin.getAchievementManager().loadPlayerAchievements(event.getPlayer().getUniqueId());
        plugin.getPlayerDataStore().loadProfileSettings(event.getPlayer().getUniqueId());
        // H10：主线程缓存玩家名，供 savePlayerData 落盘 player_data.player_name
        plugin.getPlayerDataStore().cachePlayerName(
                event.getPlayer().getUniqueId(), event.getPlayer().getName());

        org.bukkit.entity.Player joining = event.getPlayer();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // L-20 后半：延迟 3 秒后再 notify，玩家可能已经秒退，
            // 先复查在线，免得给离线玩家发消息。
            if (!joining.isOnline()) {
                return;
            }
            plugin.getUpdateChecker().notifyPlayer(joining);
        }, 60L);

        // R-45：纯新号（Bukkit 从未见过）发三行欢迎语 + /le 指引。
        // 老玩家零打扰。用 hasPlayedBefore 而不用查 player_data 行：
        // 数据行可能被 /lea reset 删掉，但那不代表玩家是新人。
        if (!joining.hasPlayedBefore()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!joining.isOnline()) {
                    return;
                }
                joining.sendMessage(plugin.getMessageUtil().getPlayerMessage(joining,
                        "join.welcome-1", "player", joining.getName()));
                joining.sendMessage(plugin.getMessageUtil().getPlayerMessage(joining,
                        "join.welcome-2"));
                joining.sendMessage(plugin.getMessageUtil().getPlayerMessage(joining,
                        "join.welcome-3"));
            }, 40L);
        }

        // 域 1 新手引导：欢迎语块之后接线（handleJoin 内部自判 enabled / 首进 / 老号回填 /
        // 1-2 态提醒与补书；对已完成引导的老玩家零输出）。
        plugin.getOnboardingManager().handleJoin(joining);

        // S5：join 每日提醒接线（R-86p 冻结接口，实现在 LetsLearnEnglish）。
        // 编译期即存在（方法已随 S8 落地）；内部自行判断 daily-reminder.enabled /
        // on-join / 当日去重，关闭或未命中时零输出。
        // D2 决策（plan-onboarding §1.6）：首进当天抑制 join 每日提醒 ——
        // 首屏已有 welcome×3 + join-intro，避免 5 行文案堆叠。
        if (joining.hasPlayedBefore()) {
            plugin.sendJoinDailyReminderIfEnabled(joining);
        }

        // P2 fan-in（S3 冻结接口的消费端）：GM 解散对局时离线的一方在下次进服
        // 收到 duel.cancelled-by-gm 通知（consumeOfflineDuelNotice 读后即清）。
        var duelManager = plugin.getDuelManager();
        if (duelManager != null) {
            var notice = duelManager.consumeOfflineDuelNotice(joining.getUniqueId());
            if (notice != null) {
                joining.sendMessage(plugin.getMessageUtil().getPlayerMessage(joining,
                        notice.messageKey(), "player", notice.otherPlayerName()));
            }
        }

        // Wave 2 域 4（广场社交）：进服摘要——离线期间收到的鼓励（异步查计数 →
        // 主线程发 → 异步标已读，调度细节见 SocialManager.notifyPendingCheers）。
        plugin.getSocialManager().notifyPendingCheers(joining);

        // Wave 3 域 5 批次 A（赛季）：进服补发上一季奖励（异步读 unclaimed → 主线程
        // 条件标记 + 发钱 + 个人消息；跨服可领——台账共享，任意子服进服都有效）。
        // isEnabled()（season.enabled 默认 false）与空结果双守卫，未开启时零输出。
        plugin.getSeasonManager().claimPendingRewards(joining);

        plugin.getDailyChallengeManager().checkAndAssign(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        // H9（R-59 校正）：下线清理答题态 — endQuiz 取消倒计时并移除 QuizState，
        // 随后经 WordManager 侧 endSession 走与正常结束相同的结算：掌握度落库、
        // 学习分累计、复习种子写入、榜单增量（quit 计入 session 与分数为既定裁决，
        // rage quit 不豁免）；cancelPractice 清错题本练习态。
        // 仅不发会话金币奖励、不触发完成庆祝（不奖励没走完的会话）。
        plugin.getWordLearningManager().endQuiz(playerId);
        plugin.getWordLearningManager().cancelPractice(playerId);
        plugin.getPlayerDataStore().unloadPlayerData(playerId);
        plugin.getAchievementManager().unloadPlayer(playerId);
        plugin.getPlayerDataStore().unloadProfileSettings(playerId);
        // S5 quit 编排点：各服务的内存卫生统一在此收口（方向 Listener → 服务，
        // 无反向依赖、不成环；PlayerDataStore 只管自己的 map，不反向调这些服务）：
        // - 返回栈（R-41）：防泄漏 + 防重进点到持旧 Player/GUI 引用的过期闭包
        // - 签到锁（R-76）：per-player 锁条目下线后不再需要
        // - 经济提示去重（F1-24）：经济未启用提示的 per-player 每自然日去重条目
        // TTS 发音冷却（R-64）由 WordLearningListener 自注册 quit 事件清理
        // （该监听器在 LetsLearnEnglish 内匿名 new，本类无实例引用可调其方法）。
        plugin.getGuiNavigationManager().clearPlayer(playerId);
        plugin.getCheckInManager().unloadPlayer(playerId);
        plugin.getProgressManager().getRewardEngine().unloadPlayer(playerId);
        // 域 1 新手引导 quit 编排点：清 step 缓存 + 提醒去重 map（防泄漏）。
        plugin.getOnboardingManager().unloadPlayer(playerId);
        // Wave 2 域 4（广场社交）quit 编排点：清鼓励点击防抖 map（F2-10/F1-24
        // "只增不删"教训——SocialListener 的快照清理走自己的 quit 事件）。
        plugin.getSocialManager().unloadPlayer(playerId);
    }
}