package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.gui.ConfirmationGUI;
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
        final UUID preloadId = event.getPlayer().getUniqueId();
        // 17-EH-F1：getConnection() 的 RuntimeException 会被 fireEvent 吞掉，其后全部编排静默跳过。给异常边界。
        try {
            // R18-M1：进服预热分两段 —— 隐私开关同步、统计/成就异步。
            //
            // 为什么这三行不能整体搬走：loadProfileSettings 的结果在本方法<b>后文</b>就被
            // 同步消费两次 —— sendJoinDailyReminderIfEnabled（LetsLearnEnglish:1183）与
            // OnboardingManager.handleJoin（OnboardingManager:154）都读 isRemindersEnabled()
            // 决定要不要发提示，而该位在缓存 miss 时是 <b>fail-open（true）</b>
            //（ProfileSettings.unresolvedFallback 只收紧三个隐私位，见 PlayerDataStore:2223-2233）。
            // 异步化等于把「玩家关掉了提醒」读成「开着」——关掉提醒的玩家照样收到进服提醒，
            // 这是可感知的越权打扰，不是隐私方向的「安全降级」，故必须留在同步段。
            //（隐私位 showInSquare / showResults 本人已是 fail-closed：miss = 隐藏，
            //  所以异步对隐私是<b>安全</b>方向；真正卡住这条路的是提醒位。）
            //
            // 返回值顺带充当「本次会话已建立」的标记：进服读成功 ⇒ profileSettingsCache
            // 里必有该玩家条目，而该条目只在 quit / reload 的 unloadProfileSettings 里被删。
            // 异步段用它做 join→quit 竞态守卫（见下）。
            final boolean profileLoaded = plugin.getPlayerDataStore().loadProfileSettings(preloadId);

            // 统计（word_progress + player_data 两条 SELECT，外加 fillMissing）与
            // 成就（achievements 一条 SELECT）搬出主线程。依据：本方法后文<b>没有一处</b>
            // 读这两份缓存 —— 每日挑战分配走仓储自己的 COUNT/INSERT（DailyChallengeManager
            // 全类不碰 PlayerDataStore），进服提醒 / 引导 / 赛季补发只读 profile。
            // 其余消费点（GUI / 成就墙 / 榜单 / PAPI）全部是「miss 时懒加载」，
            // 所以晚几毫秒到达无害；而多人同时进服时这 3 条 SELECT 原本串行压在主线程上
            //（重启后的典型场景）。
            //
            // 已知无害交互（记录下来免得后人当缺陷重报）：下面的 cachePlayerName 会 markDirty，
            // 若本任务恰好在其之后进入 loadPlayerData，开头那句「有脏标记就先全量落盘」
            // 会对着一份<b>还没载入</b>的缓存跑一次 —— snapshotPlayerData 返回 null 而直接
            // 早退（清掉玩家名那枚标记）。玩家名不会因此丢失：quit 的 unloadPlayerData 走
            // <b>无条件</b>全量 savePlayerData，那时名字仍在 playerNameCache 里，照常落盘。
            // 代价只是异步线程多抢一次 connectionLock，无数据后果。
            try {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    // 停用 / reload 窗口闸：同本方法下方每日挑战块（R12c-F2）。
                    // runtimeActive 是 disableAll 的<b>第一条语句</b>置 false 的，
                    // 所以拆解一开始这道闸就关。
                    if (!plugin.isFullyEnabled()) {
                        return;
                    }
                    try {
                        var dataStore = plugin.getPlayerDataStore();
                        var achievements = plugin.getAchievementManager();
                        // 纵深防御：闸与取值之间仍有几纳秒，字段可能刚被 D10 置空。
                        if (dataStore == null || achievements == null) {
                            return;
                        }
                        // join→quit 竞态守卫：本任务可能在 unloadPlayerData 之后才跑完。
                        // 那时再灌缓存，就是给<b>已下线</b>玩家留一份「复活」的内存态 ——
                        // 它不会再被 quit 清一次，只能等下一轮 evictOfflinePlayerCaches，
                        // 期间展示路径可以读到（并让「已下线即缓存为空」这个前提失效）。
                        // 判据用 profile 缓存键：它是本类编排里唯一具备「进服建、退出删」
                        //（loadProfileSettings / unloadProfileSettings）生命周期的<b>公开</b>
                        // 状态；profileLoaded=true 时它消失只可能是 unload 已经跑过。
                        // 更精确的世代号在 PlayerDataStore.playerCacheEpoch，但那是 private，
                        // 本类不能读（也未在公开面上暴露），故只能用这个等价判据。
                        if (profileLoaded && dataStore.getCachedProfileSettings(preloadId) == null) {
                            return;
                        }
                        dataStore.loadPlayerData(preloadId);
                        achievements.loadPlayerAchievements(preloadId);
                    } catch (RuntimeException e) {
                        // 异步调度线程里未捕获的异常不会有人接：它会变成一条与真实原因
                        // 无关的堆栈（Bukkit 的 catch 只打印，不归因）。预热失败 =
                        // 本次进服少一次预热，读侧照旧 miss 懒加载 —— 绝不能让它以
                        // 裸堆栈形式出现，也绝不能影响进服本身。
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "Async join preload failed for " + preloadId
                                        + " (player joined during a reload/shutdown window?)", e);
                    }
                });
            } catch (IllegalStateException ignored) {
                // 插件停用窗口（调度器已关闭）：放弃本次预热，读侧懒加载兜底
                //（与下方 R13-M1 的每日挑战分配同姿态）。
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Player data load failed for " + event.getPlayer().getUniqueId(), e);
            event.getPlayer().sendMessage(plugin.getMessageUtil().getPlayerMessage(
                    event.getPlayer(), "daily.load-failed"));
        }
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

        // R13-M1：DB 分配移出主线程（stripe lock 内幂等；午夜路径已证异步安全）。
        // UUID 先取为 final，闭包不捕 Player 对象（防 quit 竞态）；调度守卫照同文件
        // quit 段与 SocialManager 风格。前序 join 提醒/摘要/赛季补发顺序不变。
        final UUID joiningId = event.getPlayer().getUniqueId();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                // R12c-F2：守卫曾经只有 plugin.isEnabled() —— 那是**错的闸**。
                // /lea reload 把 server.enabled 由 true 改 false 时，走的是
                // HotReloadManager → disableAll()，而插件本身<b>一直是 enabled</b>
                // （没有 onDisable）。于是在这个窗口里 isEnabled() 恒为 true，
                // 而 disableAll 的 D10 已经把 dailyChallengeManager 置 null
                // （LetsLearnEnglish:739）→ 本行 NPE 从 Bukkit 调度线程冒出，栈里
                // 全是 NPE 与调度器，和真正的原因（有人 reload 了）毫无关联。
                //
                // 改用 isFullyEnabled()（= runtimeActive && 配置开 && playerDataStore 非 null）：
                // 它的第一项 runtimeActive 是 disableAll 的<b>第一条语句</b>置 false 的，
                // 所以拆解一开始本闸就关；A6 注释里那句「覆盖配置已 reload 成 false
                // 但 pds 还没拆完的几百毫秒」说的正是这个窗口。
                if (!plugin.isFullyEnabled()) {
                    return;
                }
                // 纵深防御：闸与取值之间仍有几纳秒，字段可能刚被 D10 置空。
                var challenges = plugin.getDailyChallengeManager();
                if (challenges == null) {
                    return;
                }
                try {
                    challenges.checkAndAssign(joiningId);
                } catch (RuntimeException e) {
                    // 异步调度线程里未捕获的异常不会有人接：它会变成一条与真实原因
                    // 无关的堆栈（Bukkit 的 catch 只打印，不归因）。这里收口成一条
                    // 可读 WARNING —— 分配失败是"今天少三个挑战"，不是"插件坏了"。
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Daily challenge assignment failed for " + joiningId
                                    + " (player joined during a reload/shutdown window?)", e);
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件停用窗口（调度器已关闭）：放弃本次分配（R-32 补分配兜底）
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        final UUID playerId = event.getPlayer().getUniqueId();
        // 17-EH-Q1（本轮）：整段编排<b>逐步隔离</b>，任一步抛异常都不得截断后续。
        //
        // 【缺陷本体】这条链原本是 15 处裸 `plugin.get...()` 顺序调用，零 try/catch。
        // 第 ① 步 endQuiz 就会抛：endQuiz → WordManagerImpl.endSession:170 →
        // PlayerDataStore.updateWordProgress / getLeaderboard().updateScore，
        // 而这条链上的 DatabaseManager.getConnection() 在<b>重连失败</b>时抛的是
        // RuntimeException("Failed to get database connection") 而<b>不是</b> SQLException
        //（PlayerDataStore:2362-2367 专门记了这个坑：数据层的 catch(SQLException) 接不到它）。
        // 于是一次 DB 抖动就让第 ③ 步 unloadPlayerData（全量落盘：掌握度 / 学习分 /
        // 复习种子 / 错题本刷盘）永不执行 —— 玩家「答了 10 题，重进服分数回退」，
        // 其后 14 步（成就 / 每日挑战 / profile / 返回栈 / 签到锁 / 经济去重 / 引导 /
        // 社交 / AI 去重 ×2 / 复习缓存 / 聊天反馈 / 决斗邀请冷却 / 二确静态态）全部静默跳过，
        // 而控制台只有一条与真实原因无关的堆栈。
        //
        // 【为什么不是一个大 try】包住全部 15 步等于没修：第 ① 步一抛，后面照样全跳过，
        // 只是把「裸堆栈」换成「被吞的堆栈」。必须<b>每步一个异常边界</b>。
        //
        // 【为什么抽辅助方法而不是 15 个 inline try/catch】15 段重复的 try/catch 会把
        // 这条编排的可读性彻底摧毁（注释比代码多，且每段 catch 块要重复拼同一份日志格式）。
        // 抽成 guardQuitStep(step, playerId, action) 后，编排退化成一张平铺的「步骤清单」，
        // 新增清理点只需一行、且天然带上步骤名日志。代价是每步一次 lambda 分配 ——
        // quit 是玩家生命周期里最低频的事件（每人每次下线一次），这点开销可忽略。
        //
        // 【为什么不 catch Throwable】Error（OutOfMemoryError / StackOverflowError …）
        // 代表 JVM 已不可信，吞掉它只会把「崩溃」伪装成「安静的数据丢失」。只收
        // RuntimeException；Error 照原样向上冒。
        //
        // 【有没有「失败也不该继续」的步骤】没有 —— 这 17 步操作的是<b>互不相交</b>的
        // 内存态（各自的 per-player map / 静态阶段表），没有一步的失败会让后续步骤
        // 进入更坏的状态，也没有一步是后续步骤的前置条件。相反，任何一步失败后停下
        // 都会留下「只增不删」的条目（本方法注释里 F2-10 / F1-24 / R7-B 反复记录的
        // 教训），所以「尽力跑完全部」严格优于「遇错即停」。
        // 唯一带数据后果的是 ③ unloadPlayerData（全量落盘），它已被放在第 3 位，
        // 保证「即便 ①② 双双失败，落盘仍会尝试」。
        //
        // H9（R-59 校正）：下线清理答题态 — endQuiz 取消倒计时并移除 QuizState，
        // 随后经 WordManager 侧 endSession 走与正常结束相同的结算：掌握度落库、
        // 学习分累计、复习种子写入、榜单增量（quit 计入 session 与分数为既定裁决，
        // rage quit 不豁免）；cancelPractice 清错题本练习态。
        // 仅不发会话金币奖励、不触发完成庆祝（不奖励没走完的会话）。
        guardQuitStep("wordLearning.endQuiz", playerId,
                () -> plugin.getWordLearningManager().endQuiz(playerId));
        guardQuitStep("wordLearning.cancelPractice", playerId,
                () -> plugin.getWordLearningManager().cancelPractice(playerId));
        // ③ 全量落盘：本编排里<b>唯一</b>带数据后果的一步（其余都是内存卫生）。
        // 位置固定在第 3 位 —— 前面两步双双失败也要执行。
        guardQuitStep("playerDataStore.unloadPlayerData", playerId,
                () -> plugin.getPlayerDataStore().unloadPlayerData(playerId));
        guardQuitStep("achievement.unloadPlayer", playerId,
                () -> plugin.getAchievementManager().unloadPlayer(playerId));
        // R7-B：每日挑战的 per-player 每日缓存（今日挑战词集合 / 已完成集合 / 已付标记）。
        // 不接也不泄漏（跨天 + 午夜 rollover 双兜底），但长期不重启 + 大量离线玩家时
        // map 会只增不减；与上面几个服务的 quit 编排点保持同一致。
        guardQuitStep("dailyChallenge.unloadPlayer", playerId,
                () -> plugin.getDailyChallengeManager().unloadPlayer(playerId));
        guardQuitStep("playerDataStore.unloadProfileSettings", playerId,
                () -> plugin.getPlayerDataStore().unloadProfileSettings(playerId));
        // S5 quit 编排点：各服务的内存卫生统一在此收口（方向 Listener → 服务，
        // 无反向依赖、不成环；PlayerDataStore 只管自己的 map，不反向调这些服务）：
        // - 返回栈（R-41）：防泄漏 + 防重进点到持旧 Player/GUI 引用的过期闭包
        // - 签到锁（R-76）：per-player 锁条目下线后不再需要
        // - 经济提示去重（F1-24）：经济未启用提示的 per-player 每自然日去重条目
        // TTS 发音冷却（R-64）由 WordLearningListener 自注册 quit 事件清理
        // （该监听器在 LetsLearnEnglish 内匿名 new，本类无实例引用可调其方法）。
        guardQuitStep("guiNavigation.clearPlayer", playerId,
                () -> plugin.getGuiNavigationManager().clearPlayer(playerId));
        guardQuitStep("checkIn.unloadPlayer", playerId,
                () -> plugin.getCheckInManager().unloadPlayer(playerId));
        // 两级解引用（getProgressManager 与 getRewardEngine 任一处为 null 都是 NPE，
        // 一起收进同一步骤的异常边界）。
        guardQuitStep("rewardEngine.unloadPlayer", playerId,
                () -> plugin.getProgressManager().getRewardEngine().unloadPlayer(playerId));
        // 域 1 新手引导 quit 编排点：清 step 缓存 + 提醒去重 map（防泄漏）。
        guardQuitStep("onboarding.unloadPlayer", playerId,
                () -> plugin.getOnboardingManager().unloadPlayer(playerId));
        // Wave 2 域 4（广场社交）quit 编排点：清鼓励点击防抖 map（F2-10/F1-24
        // "只增不删"教训——SocialListener 的快照清理走自己的 quit 事件）。
        guardQuitStep("social.unloadPlayer", playerId,
                () -> plugin.getSocialManager().unloadPlayer(playerId));
        // Phase 3 US-FB（3B-1b）quit 编排点：清 AI 总结去重（3B-1a 的
        // pendingSummaries——防"下线后上一单在途条目永留"），与本类上方
        // clearPendingSummary 调用点同源语义；崩溃丢条目的极端窗口由
        // requestConversationSummary 的 dedup 下次请求自然刷新（宽容语义）。
        guardQuitStep("aiChat.clearPendingSummary", playerId,
                () -> plugin.getAIChatManager().clearPendingSummary(playerId));
        // R13-M4 quit 清理（同编排点）：AI 难度偏好条目 + 复习 due 词缓存
        // （两 map 原为只增不删，历史玩家条目永留；rejoin 重查/回落默认值重建）。
        guardQuitStep("aiChat.unloadPlayer", playerId,
                () -> plugin.getAIChatManager().unloadPlayer(playerId));
        guardQuitStep("reviewScheduler.unloadPlayer", playerId,
                () -> plugin.getWordManager().getReviewScheduler().unloadPlayer(playerId));
        guardQuitStep("chatFeedback.unloadPlayer", playerId,
                () -> plugin.getChatFeedbackManager().unloadPlayer(playerId));
        // D2：决斗的邀请冷却表（DuelManager.lastInviteAt，R4-07-sec-F02）。原先只有
        // shutdown() 的 clear() 一处 —— 单服长期不重启时键集合 = 本服历史上出现过的
        // 全部邀请者，只增不减（同 F2-10 / F1-24 / R7-B 的"只增不删"教训）。
        //
        // 【为什么插在这里】它是纯内存卫生、与上面 16 步零依赖（冷却表不承载任何
        // 对决状态——写入点在 isBusy 检查之前，删条目的最坏后果只是重置一次 5 秒冷却），
        // 所以位置不影响正确性；选在 resetStage <b>之前</b>的唯一硬理由是把最后一位
        // 留给 resetStage —— 它是本条链上唯一依赖<b>活着的 Player 对象</b>
        // （event.getPlayer()）的一步，排在末尾可以保证「即便前面任何一步失败，
        // 只要 Player 还在就仍会执行」，也方便将来新增的 UUID-only 清理点插在它前面。
        //
        // 【与 DuelListener 的顺序】DuelListener.onPlayerQuit 注册在 MONITOR，
        // 必在本方法（NORMAL）之后跑；duel 的 handleDisconnect 不读这张冷却表，
        // 先后顺序对两侧都无影响（见 DuelManager#unloadPlayer 的 javadoc 论证）。
        guardQuitStep("duel.unloadPlayer", playerId,
                () -> plugin.getDuelManager().unloadPlayer(playerId));
        // R8-L1 quit 清理：语言二确流程的静态阶段 map（ConfirmationGUI.confirmStages）
        // —— quit 不清理会让"确认页 1 → 下线 → 重登"的玩家从阶段 1 残留状态继续，
        // 跳过二段确认。quit 事件有 Player 对象，直接走 resetStage。
        guardQuitStep("confirmationGui.resetStage", playerId,
                () -> ConfirmationGUI.resetStage(event.getPlayer()));
    }

    /**
     * quit 编排的单步异常边界：执行一步清理，失败只记一条<b>带步骤名</b>的 WARNING，
     * 绝不向上抛 —— 让 {@link #onPlayerQuit} 的后续步骤照常执行。
     *
     * <p><b>为什么日志必须带步骤名</b>：这些清理点各自吞掉的是不同服务的不同 map，
     * 一条没有步骤名的 "Player quit cleanup failed" 让运维无法判断是「落盘没写」（数据
     * 丢失，要立刻查库）还是「签到锁没清」（纯内存卫生，可放过）。步骤名 + UUID
     * 是这条日志唯一可行动的信息。
     *
     * <p><b>为什么只 catch RuntimeException</b>：{@code Error}
     * （{@code OutOfMemoryError} / {@code StackOverflowError}）代表 JVM 已不可信，
     * 吞掉它只会把崩溃伪装成安静的数据丢失。{@link Runnable#run()} 也无法抛出受检异常，
     * 故 {@code RuntimeException} 已是这条路径的完整异常面。
     *
     * @param step     步骤名（日志用，形如 {@code "playerDataStore.unloadPlayerData"}）
     * @param playerId 玩家 UUID（日志用）
     * @param action   该步的实际清理动作
     */
    private void guardQuitStep(String step, UUID playerId, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Player quit cleanup step '" + step + "' failed for " + playerId
                            + " — continuing with the remaining steps", e);
        }
    }
}