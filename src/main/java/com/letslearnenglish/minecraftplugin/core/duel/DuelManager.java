package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;
import com.letslearnenglish.minecraftplugin.core.ai.AIChatManager;
import com.letslearnenglish.minecraftplugin.core.feedback.CountdownTier;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.data.DatabaseManager;
import com.letslearnenglish.minecraftplugin.gui.DuelGUI;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import com.letslearnenglish.minecraftplugin.util.SchedulerGuard;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DuelManager — manages all active 1v1 word duels.
 *
 * Flow:
 * 1. /le duel <player>  → sendDuelRequest()
 * 2. /le duel accept    → acceptDuel()
 * 3. Chat answer        → submitAnswer()
 * 4. /le duel quit      → quitDuel()
 *
 * Handles disconnect/reconnect with 30-second grace period.
 */
public class DuelManager {

    public static final int GRACE_PERIOD_SECONDS = 30;

    private final LetsLearnEnglish plugin;

    // player UUID → DuelRoom they are in. F2: 同时是 busy 的唯一真源。
    private final Map<UUID, DuelRoom> activeDuels;
    // Pending duel requests: target UUID → PendingDuel (sender + unique token)
    private final Map<UUID, PendingDuel> pendingRequests;
    // Disconnect grace timers: disconnected player UUID → duelRoom
    private final Map<UUID, DisconnectInfo> disconnectTimers;
    // Grace period countdown tasks: disconnected player UUID → task
    private final Map<UUID, BukkitTask> graceTasks;
    // Single-question timeout tasks: player1 UUID → task (one entry per room)
    private final Map<UUID, BukkitTask> wordTimeoutTasks;
    // S-I / 3A-3 countdown BossBars: player1 UUID → bar (same key style as
    // wordTimeoutTasks — ONE shared bar per room, both duelists as viewers)
    private final Map<UUID, BossBar> duelBars;
    // Request-expiry tasks: target UUID → task (one entry per pending request)
    private final Map<UUID, BukkitTask> requestExpiryTasks;
    // F1: in-flight duel_stats writes, so shutdown() can await them before the
    // DB is closed by onDisable(). Producer removes its own entry in finally.
    private final Set<CompletableFuture<Void>> inflightDuelStats = ConcurrentHashMap.newKeySet();
    // R-69: offline duel notices awaiting delivery on next join (one entry per player)
    private final Map<UUID, OfflineDuelNotice> offlineNotices;
    // R-68: monotonic token source for pending duel requests
    private final AtomicLong requestTokenSeq;
    /** R4-07-sec-F02：邀请冷却，否则可无限轰炸目标的消息 + 音效。 */
    private final Map<UUID, Long> lastInviteAt = new ConcurrentHashMap<>();
    private static final long INVITE_COOLDOWN_MS = 5000L;

    /**
     * A pending duel request. The unique {@code token} lets the 60-second
     * expiry task (and acceptDuel) remove exactly the entry it was created
     * for — the old {@code Map<UUID, UUID>} value (just the sender UUID)
     * could not distinguish two invitations sent by the SAME player to the
     * SAME target within the expiry window (R-68).
     */
    private record PendingDuel(UUID senderId, long token) { }

    /**
     * R-69 / R14: a notice owed to a player who was offline when their duel was
     * torn down (GM dismissal, or forfeit-by-disconnect-timeout). Consumed on
     * next join via {@link #consumeOfflineDuelNotice(UUID)}.
     *
     * @param messageKey      i18n key for the consumer to resolve and send
     *                        (e.g. {@code "duel.cancelled-by-gm"} /
     *                        {@code "duel.lost-by-disconnect"})
     * @param otherPlayerName the OTHER duelist's name, usable as the
     *                        {player} placeholder
     */
    public record OfflineDuelNotice(String messageKey, String otherPlayerName) { }

    private static class DisconnectInfo {
        final DuelRoom room;
        DisconnectInfo(DuelRoom room) {
            this.room = room;
        }
    }

    public DuelManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.activeDuels = new ConcurrentHashMap<>();
        this.pendingRequests = new ConcurrentHashMap<>();
        this.disconnectTimers = new ConcurrentHashMap<>();
        this.graceTasks = new ConcurrentHashMap<>();
        this.wordTimeoutTasks = new ConcurrentHashMap<>();
        this.duelBars = new ConcurrentHashMap<>();
        this.requestExpiryTasks = new ConcurrentHashMap<>();
        this.offlineNotices = new ConcurrentHashMap<>();
        this.requestTokenSeq = new AtomicLong();
    }

    // ==================== Request / Accept ====================

    /**
     * Send a duel request from sender to target.
     */
    public void sendDuelRequest(Player sender, Player target) {
        // MC-2 模块门（duel.enabled）：关闭时邀请入口直接拒绝。quit/gui 的放行
        // 语义在 LearnCommand 的 duel 分支（duel.enabled || isInDuel），不在本方法；
        // /lea duel cancel（GM 通道）与进行中对局的收尾不经过这里。
        if (!plugin.getConfigManager().isDuelEnabled()) {
            sender.sendMessage(getMsg(sender, "module.disabled"));
            return;
        }

        if (sender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(getMsg(sender, "duel.cannot-challenge-self"));
            return;
        }

        // R4-07-sec-F02：邀请冷却 —— 任何消息/音效之前，命中静默返回。
        long nowMs = System.currentTimeMillis();
        Long lastAt = lastInviteAt.get(sender.getUniqueId());
        if (lastAt != null && nowMs - lastAt < INVITE_COOLDOWN_MS) return;
        lastInviteAt.put(sender.getUniqueId(), nowMs);
        if (isBusy(sender.getUniqueId())) {
            sender.sendMessage(getMsg(sender, "duel.already-in-duel"));
            return;
        }

        if (isBusy(target.getUniqueId())) {
            sender.sendMessage(getMsg(sender, "duel.target-busy", "player", target.getName()));
            return;
        }

        // R-27: 答题忙态对称检查 —— 与 acceptDuel 的 M4 门禁对称，发邀请前就拦截。
        // sender 自己 quiz/练习中：回 sender 专属提示；target 答题中：复用 M4 的 target-answering。
        if (isAnswering(sender.getUniqueId())) {
            sender.sendMessage(getMsg(sender, "duel.sender-answering"));
            return;
        }

        if (isAnswering(target.getUniqueId())) {
            sender.sendMessage(getMsg(sender, "duel.target-answering", "player", target.getName()));
            return;
        }

        // R-66: AI 对话互斥 —— AI 对话与决斗抢同一份聊天输入，双方任一在
        // 对话中都拒绝发邀（sender 先查，提示责任归发起方）。
        AIChatManager aiChatManager = plugin.getAIChatManager();
        if (aiChatManager != null && aiChatManager.hasActiveConversation(sender)) {
            sender.sendMessage(getMsg(sender, "duel.sender-chatting"));
            return;
        }

        if (aiChatManager != null && aiChatManager.hasActiveConversation(target)) {
            sender.sendMessage(getMsg(sender, "duel.target-chatting", "player", target.getName()));
            return;
        }

        // Clear any old pending request from this sender (a player may only have
        // one outstanding invitation) and cancel its expiry task so it neither
        // fires a spurious "expired" message nor lingers until shutdown (R-68).
        for (Map.Entry<UUID, PendingDuel> entry : pendingRequests.entrySet()) {
            if (entry.getValue().senderId().equals(sender.getUniqueId())) {
                if (pendingRequests.remove(entry.getKey(), entry.getValue())) {
                    BukkitTask oldExpiry = requestExpiryTasks.remove(entry.getKey());
                    if (oldExpiry != null) {
                        oldExpiry.cancel();
                    }
                }
            }
        }

        UUID targetId = target.getUniqueId();
        long token = requestTokenSeq.incrementAndGet();
        PendingDuel pending = new PendingDuel(sender.getUniqueId(), token);

        // Replace whatever invitation previously targeted this player (possibly
        // from a different sender): cancel its expiry task first, otherwise the
        // stale task would linger untracked after firing (R-68).
        BukkitTask staleExpiry = requestExpiryTasks.remove(targetId);
        if (staleExpiry != null) {
            staleExpiry.cancel();
        }
        pendingRequests.put(targetId, pending);

        // Message to sender
        sender.sendMessage(getMsg(sender, "duel.request-sent", "player", target.getName()));

        // Message to target with clickable accept
        String acceptMsg = getMsg(target, "duel.request-received", "player", sender.getName());
        // Make it clickable by suggesting /le duel accept
        // P2-13 二期（3A-3）：getRawMsg → MessageUtil.getGuiMsg —— 原实现缺键返回 null，
        // translateAlternateColorCodes('&', null) 直接 NPE（真实崩溃路径）。
        String lang = plugin.getPlayerLanguage(target);
        FileConfiguration acceptMsgs = plugin.getConfigManager().getMessageConfig(lang);
        String clickableAccept = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(acceptMsgs, "duel.accept-hint"));
        target.sendMessage(acceptMsg);
        target.sendMessage(clickableAccept);

        // Auto-expire pending request after 60 seconds. The remove(key, value)
        // form with the unique token ensures a stale expiry task never deletes a
        // NEWER invitation standing for the same target (R-68: also covers two
        // invitations from the SAME sender within the 60s window, where the old
        // UUID-only value compared equal and ate the newer request). A stale
        // firing (expired == false) must not untrack the newer request's task,
        // so the task-map cleanup only runs on a successful expiry.
        final String senderName = sender.getName();
        final String targetName = target.getName();
        BukkitTask expiryTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            boolean expired = pendingRequests.remove(targetId, pending);
            if (expired) {
                requestExpiryTasks.remove(targetId);
                Player s = Bukkit.getPlayer(pending.senderId());
                if (s != null && s.isOnline()) {
                    s.sendMessage(getMsg(s, "duel.request-expired", "player", targetName));
                    SoundManager.playSound(s, "menu-click");
                }
                Player t = Bukkit.getPlayer(targetId);
                if (t != null && t.isOnline()) {
                    t.sendMessage(getMsg(t, "duel.request-expired-target", "player", senderName));
                    SoundManager.playSound(t, "menu-click");
                }
            }
        }, 1200L); // 60 seconds = 1200 ticks
        requestExpiryTasks.put(targetId, expiryTask);
    }

    /**
     * Accept a pending duel request.
     *
     * <p>F1-15: the invitation is consumed (removed from pendingRequests) only
     * AFTER every validation passes — a failed accept (offline sender, busy
     * side, answering state) no longer silently eats the request, so the
     * target can retry without asking the sender to re-invite. The final
     * remove(key, pending) is token-based (R-68): if the request was replaced
     * or expired between the peek and the consume, the target is told the
     * invitation has expired instead of the generic no-pending-request.
     */
    public void acceptDuel(Player player) {
        // MC-2 模块门（duel.enabled）：关闭后新邀请不再受理（与 sendDuelRequest 对称）。
        // 门在"取 pending"之前：关开关后连 no-pending-request 提示都不该走正常路径。
        if (!plugin.getConfigManager().isDuelEnabled()) {
            player.sendMessage(getMsg(player, "module.disabled"));
            return;
        }

        UUID targetId = player.getUniqueId();
        // F1-15: peek first, remove only once all validations pass.
        PendingDuel pending = pendingRequests.get(targetId);

        if (pending == null) {
            player.sendMessage(getMsg(player, "duel.no-pending-request"));
            return;
        }
        UUID senderId = pending.senderId();

        Player sender = Bukkit.getPlayer(senderId);
        if (sender == null || !sender.isOnline()) {
            player.sendMessage(getMsg(player, "duel.player-offline"));
            return;
        }

        if (isBusy(senderId)) {
            player.sendMessage(getMsg(player, "duel.target-busy", "player", sender.getName()));
            return;
        }

        if (isBusy(targetId)) {
            player.sendMessage(getMsg(player, "duel.already-in-duel"));
            return;
        }

        // M4: refuse to start a duel while either side is answering
        // (quiz session or wrong-book practice) — only one chat-input
        // consumer state is allowed at a time.
        if (isAnswering(senderId)) {
            player.sendMessage(getMsg(player, "duel.target-answering", "player", sender.getName()));
            return;
        }

        if (isAnswering(targetId)) {
            player.sendMessage(getMsg(player, "duel.busy-answering"));
            return;
        }

        // R-66 对称补全（P2 fan-in）：接受时复刻 sendDuelRequest 的 AI 对话互斥 ——
        // 邀请发出后任一方可能已进入 AI 对话，此时强行开赛会把对话静默挂起。
        // 校验不过不消费邀请（保持 pending，玩家结束对话后仍可接受）。
        AIChatManager acceptAiChat = plugin.getAIChatManager();
        if (acceptAiChat != null && acceptAiChat.hasActiveConversation(sender)) {
            player.sendMessage(getMsg(player, "duel.target-chatting", "player", sender.getName()));
            return;
        }
        if (acceptAiChat != null && acceptAiChat.hasActiveConversation(player)) {
            // 复用通用"对话中"提示键（dialogue.chat-active：请先输入 bye 结束）
            player.sendMessage(getMsg(player, "dialogue.chat-active"));
            return;
        }

        // All validations passed — consume the invitation. The remove(key, value)
        // form with the token guards the case where a newer request replaced this
        // one (R-68) or the 60-second expiry won the race (R-86n/F1-15); the
        // target is told the invitation lapsed rather than nothing having existed.
        if (!pendingRequests.remove(targetId, pending)) {
            player.sendMessage(getMsg(player, "duel.request-expired-target", "player", sender.getName()));
            return;
        }

        // Cancel the now-consumed request's expiry task (R-68 leak guard).
        BukkitTask expiryTask = requestExpiryTasks.remove(targetId);
        if (expiryTask != null) {
            expiryTask.cancel();
        }

        // F2: 登记房间即置忙，单次原子发布，不再有「已忙但无房」的可见窗口。
        DuelRoom room = new DuelRoom(senderId, sender.getName(), targetId, player.getName());
        activeDuels.put(senderId, room);
        activeDuels.put(targetId, room);

        // S-I / 3A-3：本对局的共享倒计时 BossBar（一房一条、双方共看；
        // 创建必须是主线程 —— acceptDuel 由命令/点击路径调用，恒在主线程）。
        // feedback.countdown.duel-bossbar=false 时不创建（tick 音/超时逻辑照常）。
        // 若首词抽不到 → cancelDuelInternal → cleanup 会释放本条。
        if (isDuelBossBarEnabled()) {
            createDuelBossBar(room);
        }

        // Announce
        sender.sendMessage(getMsg(sender, "duel.started", "player", player.getName()));
        player.sendMessage(getMsg(player, "duel.started", "player", sender.getName()));

        // Load first word
        Word firstWord = getRandomWord();
        if (firstWord == null) {
            cancelDuelInternal(room, "no-words");
            return;
        }
        room.setCurrentWord(firstWord);
        scheduleWordTimeout(room);

        // Open GUI for both
        new DuelGUI(plugin).open(sender, room);
        new DuelGUI(plugin).open(player, room);

        // Setup scoreboard for both
        setupScoreboard(sender, room);
        setupScoreboard(player, room);
    }

    // ==================== Answer Submission ====================

    /**
     * Submit a chat answer for an active duel.
     */
    public void submitAnswer(Player player, String answer) {
        UUID playerId = player.getUniqueId();
        DuelRoom room = activeDuels.get(playerId);
        if (room == null || !room.isActive()) {
            // F1-18: 对局结束/已拆解的窗口期，聊天仍可能被 ChatAnswerListener
            // 按 isInDuel 快照送进来（异步判定与主线程拆解之间的竞态）。
            // 不再静默吞答案 —— 复用既有 duel.not-in-duel 键给玩家一句反馈。
            player.sendMessage(getMsg(player, "duel.not-in-duel"));
            return;
        }

        DuelRoom.AnswerResult result = room.submitAnswer(playerId, answer);

        Player opponent = Bukkit.getPlayer(room.getOpponent(playerId));

        switch (result) {
            case CORRECT -> {
                cancelWordTimeout(room);
                SoundManager.playSound(player, "correct");
                player.sendMessage(getMsg(player, "duel.correct-answer",
                        "word", room.getCurrentWord().getEnglish()));

                if (opponent != null && opponent.isOnline()) {
                    opponent.sendMessage(getMsg(opponent, "duel.opponent-correct",
                            "player", player.getName(),
                            "word", room.getCurrentWord().getEnglish()));
                }

                updateBothScoreboards(room);
                refreshBothGUIs(room);
                advanceOrEnd(room);
            }
            case WRONG -> {
                cancelWordTimeout(room);
                SoundManager.playSound(player, "incorrect");
                player.sendMessage(getMsg(player, "duel.wrong-answer",
                        "word", room.getCurrentWord().getEnglish()));

                if (opponent != null && opponent.isOnline()) {
                    opponent.sendMessage(getMsg(opponent, "duel.opponent-wrong",
                            "player", player.getName(),
                            "word", room.getCurrentWord().getEnglish()));
                }

                updateBothScoreboards(room);
                refreshBothGUIs(room);
                advanceOrEnd(room);
            }
            case ALREADY_ANSWERED -> {
                // R-25: 超时/锁题后的迟到答案 —— 提示答题者本人"本题已结束"，不广播。
                player.sendMessage(getMsg(player, "duel.round-ended"));
            }
            case NOT_IN_DUEL -> {
                // F1-18: 与 room==null 分支同款收口 —— 房间层拒收（题未就位等
                // 窗口态）同样给答题者一句反馈，不静默吞输入。本分支在
                // runTask 回调内（主线程），sendMessage 安全。
                //
                // R24-A2：DuelRoom.submitAnswer 对「!active / currentWord==null / paused」
                // 三种情况一律返回 NOT_IN_DUEL（R4-07-logic-F01 的既定契约，见
                // DuelRoomPauseTest 四条用例）。复用 duel.not-in-duel 只在后两种情形下
                // 名副其实；而 paused（对手掉线、30 秒宽限期）时玩家明明就在对局里，
                // 回「你当前不在对决中」与事实相反，且该文案会在这个窗口里对他重复
                // 整整 30 秒 —— 玩家完全无法判断自己是掉出了对局还是消息丢了。
                // 故按 paused 分流到一条说明「消息去哪了」的文案。
                player.sendMessage(getMsg(player, room.isPaused()
                        ? "duel.paused-opponent-disconnected"
                        : "duel.not-in-duel"));
            }
        }
    }

    private void advanceOrEnd(DuelRoom room) {
        // R4-07-logic-F03：回合上限到点 = 和局（超时永不计分，否则双方挂机永不终止）。
        if (room.isFinished()) {
            endDuel(room);
        } else if (room.isRoundCapReached()) {
            cancelDuelInternal(room, "max-rounds");
        } else {
            // R14：把当前词传进去，避免连续两题发同一个词。
            Word nextWord = getRandomWord(room);
            if (nextWord == null) {
                cancelDuelInternal(room, "no-words");
                return;
            }
            room.setCurrentWord(nextWord);
            scheduleWordTimeout(room);
            refreshBothGUIs(room);
        }
    }

    // ==================== Single-Question Timeout (FIX-DUEL-01) ====================

    /**
     * Read the per-question timeout from the EXISTING
     * {@code word-system.answer-timeout} key (owned by WordLearningManager).
     * No new config key is introduced; falls back to
     * {@link DuelRoom#WORD_TIMEOUT_SECONDS} and is clamped to [5, 300]s so a
     * misconfigured value can neither busy-loop the scheduler nor freeze a
     * duel forever.
     */
    private int getWordTimeoutSeconds() {
        int seconds = plugin.getConfigManager().getMainConfig()
                .getInt("word-system.answer-timeout", DuelRoom.WORD_TIMEOUT_SECONDS);
        return Math.max(5, Math.min(300, seconds));
    }

    /**
     * S-I / 3A-3: 对决每题倒计时 BossBar 总开关（{@code feedback.countdown.duel-bossbar}，默认 true）。
     * false 时不创建/不更新 BossBar，但倒计时 task、tick 音与超时逻辑照常。
     */
    private boolean isDuelBossBarEnabled() {
        return plugin.getConfigManager().getMainConfig()
                .getBoolean("feedback.countdown.duel-bossbar", true);
    }

    /**
     * S-I / 3A-3: 紧急档阈值（{@code feedback.countdown.urgent-seconds}，默认 5，与学习侧共用键）。
     * 0 = 全程常规档（不到点不渲染 0，URGENT 分支实际不会命中）。
     */
    private int getUrgentSeconds() {
        int seconds = plugin.getConfigManager().getMainConfig()
                .getInt("feedback.countdown.urgent-seconds", 5);
        return Math.max(0, seconds);
    }

    /**
     * S-I / 3A-3: 最后 N 秒每秒播 countdown-tick（{@code feedback.countdown.tick-sound-seconds}，
     * 默认 3；0 = 不播）。与 learning 侧共用键。
     */
    private int getTickSoundSeconds() {
        int seconds = plugin.getConfigManager().getMainConfig()
                .getInt("feedback.countdown.tick-sound-seconds", 3);
        return Math.max(0, seconds);
    }

    /**
     * Schedule the per-second countdown for the room's current word
     * (S-I / 3A-3: was a single silent {@code runTaskLater} for 30s; now a
     * {@code runTaskTimer} @1s that drives the shared BossBar, the urgent
     * tier colour/文案 and the last-N-seconds tick sound — the timeout
     * semantics themselves are unchanged).
     *
     * <p>Any previous countdown for this room is cancelled first
     * (belt-and-suspenders — normally the old task was already cancelled when
     * the previous word was answered). When a bar exists it is reset to a
     * full, yellow, normal-tier state at the top of every round (换题不重建
     * 对象，只重置 title/progress — 清理矩阵 §3.4).
     */
    private void scheduleWordTimeout(DuelRoom room) {
        if (!room.isActive()) return;
        cancelWordTimeout(room);
        final long round = room.getWordRoundId();
        final int total = getWordTimeoutSeconds();
        final int urgentSeconds = getUrgentSeconds();
        final int tickSoundSeconds = getTickSoundSeconds();
        // S-I：读实时开关（reload 即时生效）—— 关闭时释放既有条（若已建）、
        // 开启而条缺失时惰性补建（如 accept 后又被 reload 打开），
        // 进行中的对局无需重开即可跟随开关。
        BossBar resolvedBar = duelBars.get(room.getPlayer1());
        if (isDuelBossBarEnabled()) {
            if (resolvedBar == null) {
                createDuelBossBar(room);
                resolvedBar = duelBars.get(room.getPlayer1());
            }
        } else if (resolvedBar != null) {
            duelBars.remove(room.getPlayer1());
            resolvedBar.removeAll();
            resolvedBar = null;
        }
        final BossBar bar = resolvedBar;
        final int[] remaining = {total};

        // 开局满格渲染（第 total 秒文案 + progress 1.0），即便首 tick 在 1s 后。
        if (bar != null) {
            renderDuelBossBar(room, bar, remaining[0], total, urgentSeconds);
        }

        // 自引用 holder：lambda 里的 `this` 是 DuelManager（非 task），需要 hold 住
        // 自身引用供自清理使用；首 tick 在 20L 之后，届时 holder[0] 必然已赋值。
        final BukkitTask[] selfRef = new BukkitTask[1];
        selfRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // 自清理路径 1：房间已拆解（endDuel/quit/GM 解散/内部取消）——
            // 自身 remove + self cancel（抄 R-26 grace 模式），不再空转。
            if (!room.isActive()) {
                selfCancelWordTimeout(room, selfRef[0]);
                return;
            }
            // 自清理路径 2：round 快照不匹配（已答对/答错推进/超时推进）——
            // 本任务属于旧题，直接退场；新题的任务已由 scheduleWordTimeout 另起。
            if (room.getWordRoundId() != round) {
                selfCancelWordTimeout(room, selfRef[0]);
                return;
            }

            remaining[0]--;
            if (remaining[0] <= 0) {
                // 自清理路径 3：正常到点 —— 先摘任务登记再结算超时。
                selfCancelWordTimeout(room, selfRef[0]);
                handleWordTimeout(room, round);
                return;
            }

            if (bar != null) {
                renderDuelBossBar(room, bar, remaining[0], total, urgentSeconds);
            }
            // tick 音与 BossBar 开关无关：duel-bossbar=false 时音效弧线照常。
            if (tickSoundSeconds > 0 && remaining[0] <= tickSoundSeconds) {
                // pitch 随剩余递减递增：tick-sound-seconds=3 时 3/2/1s → 1.0/1.15/1.30
                float pitch = 1.0f + (tickSoundSeconds - remaining[0]) * 0.15f;
                Player p1 = Bukkit.getPlayer(room.getPlayer1());
                Player p2 = Bukkit.getPlayer(room.getPlayer2());
                if (p1 != null && p1.isOnline()) {
                    SoundManager.playSound(p1, "countdown-tick", pitch);
                }
                if (p2 != null && p2.isOnline()) {
                    SoundManager.playSound(p2, "countdown-tick", pitch);
                }
            }
        }, 20L, 20L); // every second, first tick after 1s
        wordTimeoutTasks.put(room.getPlayer1(), selfRef[0]);
    }

    /**
     * S-I / 3A-3 自清理：从任务容器摘掉"仍是自己"的条目，并无条件停掉自己。
     *
     * <p>{@code remove(key, value)} 双参形式保证不会误摘同房间新题已经登记
     * 的新任务（旧任务自清理与新 {@code scheduleWordTimeout} 的 put 之间若发生
     * 交错，简单 remove 会把新任务从登记表里踢掉 → 泄漏到 cleanup 之外）；
     * 但"条目未被登记"不是不退场的理由 —— 条目已被 cleanup/替换时仍须
     * {@code task.cancel()} 自己，否则 stale 任务会每秒空转到天荒地老。
     * cancel 对已取消任务幂等，且只作用于本任务实例。
     */
    private void selfCancelWordTimeout(DuelRoom room, BukkitTask task) {
        if (task == null) return;
        wordTimeoutTasks.remove(room.getPlayer1(), task);
        task.cancel();
    }

    /**
     * R5-M1(b)：宽限任务自清理 —— remove(key, value) 双参只摘"仍是自己"的条目，
     * 无论条目在否都 cancel 自己（cancel 幂等）；防 stale 任务每秒空转。
     * 模式抄 {@link #selfCancelWordTimeout}（:580-584）。
     */
    private void selfCancelGrace(UUID playerId, BukkitTask task) {
        if (task == null) return;
        graceTasks.remove(playerId, task);
        task.cancel();
    }

    /**
     * Cancel the pending single-question countdown for a room, if any.
     */
    private void cancelWordTimeout(DuelRoom room) {
        BukkitTask task = wordTimeoutTasks.remove(room.getPlayer1());
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Fire when nobody answered the current word in time: reveal the answer
     * to both sides (score unchanged — symmetric, see
     * {@link DuelRoom#timeoutCurrentWord}) and deal the next word.
     *
     * <p>任务登记说明（S-I / 3A-3）：改 BossBar 定时器后，唯一调用方是 timer 内
     * 的到点分支，且调用前已由 {@link #selfCancelWordTimeout} 摘掉任务登记 ——
     * 原开头的 {@code wordTimeoutTasks.remove} 因此删除（重复 remove 无害，但
     * 保留会让人误以为本方法可被任意外部路径调用；若未来新增直接调用方，
     * 必须先自行收口任务登记）。
     */
    private void handleWordTimeout(DuelRoom room, long round) {
        if (!room.isActive()) return;
        if (!room.timeoutCurrentWord(round)) {
            return; // stale firing: answered / advanced / ended meanwhile
        }

        String answer = room.getCurrentWord() != null
                ? room.getCurrentWord().getEnglish() : "?";

        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null && p1.isOnline()) {
            p1.sendMessage(getMsg(p1, "duel.word-timeout", "word", answer));
            // Plan §3.1/§1.B2：超时改走专用 "timeout" 键（S-B 已注册；其默认值
            // 跟随 incorrect 的解析结果 —— 缺省听觉与旧行为零变化，服主可单独覆盖）。
            SoundManager.playSound(p1, "timeout");
        }
        if (p2 != null && p2.isOnline()) {
            p2.sendMessage(getMsg(p2, "duel.word-timeout", "word", answer));
            SoundManager.playSound(p2, "timeout");
        }

        // Timeout never scores, so the duel cannot finish here — advance.
        // R4-07-logic-F03：挂机推进同样受回合上限约束，否则双方挂机会无限换题。
        if (room.isRoundCapReached()) { cancelDuelInternal(room, "max-rounds"); return; }
        // R14：同上，排除刚超时的这一题，防连续两题同词。
        Word nextWord = getRandomWord(room);
        if (nextWord == null) {
            cancelDuelInternal(room, "no-words");
            return;
        }
        room.setCurrentWord(nextWord);
        scheduleWordTimeout(room);
        updateBothScoreboards(room);
        refreshBothGUIs(room);
    }

    // ==================== Countdown BossBar (S-I / 3A-3) ====================

    /**
     * Create the room's shared countdown boss bar (one bar per room, both
     * duelists as viewers — stored under player1, same key style as
     * {@code wordTimeoutTasks}). Main thread only — called from
     * {@link #acceptDuel}.
     *
     * <p>Per plan §3.4 the bar is never shown/hidden via the deprecated
     * {@code show()/hide()} — visibility is controlled by {@code addPlayer} /
     * {@code removeAll} only. The first real frame is rendered by
     * {@link #scheduleWordTimeout} (which runs right after the first word is
     * dealt); this method only guarantees a sane pre-state.
     */
    private void createDuelBossBar(DuelRoom room) {
        String title = resolveRoundBossBarTitle(room,
                "duel.round-bossbar", getWordTimeoutSeconds());
        BossBar bar = Bukkit.createBossBar(title, BarColor.YELLOW, BarStyle.SOLID);
        bar.setProgress(1.0);
        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null && p1.isOnline()) {
            bar.addPlayer(p1);
        }
        if (p2 != null && p2.isOnline()) {
            bar.addPlayer(p2);
        }
        duelBars.put(room.getPlayer1(), bar);
    }

    /**
     * Render one countdown frame on the shared bar: title with {@code {seconds}},
     * colour by {@link CountdownTier} (URGENT → red + urgent key, NORMAL →
     * yellow), progress = remaining/total (clamped to [0,1]).
     */
    private void renderDuelBossBar(DuelRoom room, BossBar bar, int remaining,
                                   int total, int urgentSeconds) {
        CountdownTier tier = CountdownTier.tierOf(remaining, urgentSeconds);
        String key = (tier == CountdownTier.URGENT)
                ? "duel.round-bossbar-urgent"
                : "duel.round-bossbar";
        bar.setTitle(resolveRoundBossBarTitle(room, key, remaining));
        bar.setColor(tier == CountdownTier.URGENT ? BarColor.RED : BarColor.YELLOW);
        double progress = (total <= 0) ? 0.0 : remaining / (double) total;
        bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
    }

    /**
     * Resolve the shared bar's title for a given key + {seconds}.
     *
     * <p>One shared bar has exactly ONE visible title — resolved in the
     * challenger's (player1) language, falling back to player2 while player1 is
     * offline (grace period), then to the server default (a player's own
     * scoreboard stays per-language via {@code setupScoreboard}).
     * {@link MessageUtil#getGuiMsg} keeps a missing key from NPE-ing (returns a
     * visible {@code [?key]}).
     */
    private String resolveRoundBossBarTitle(DuelRoom room, String key, int seconds) {
        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        String lang;
        if (p1 != null && p1.isOnline()) {
            lang = plugin.getPlayerLanguage(p1);
        } else if (p2 != null && p2.isOnline()) {
            // 挑战者离线（宽限期内剩余方仍在看条）——用剩余方语言，别无谓回落默认语言
            lang = plugin.getPlayerLanguage(p2);
        } else {
            lang = plugin.getConfigManager().getMainConfig().getString("general.language", "zh");
        }
        FileConfiguration msgs = plugin.getConfigManager().getMessageConfig(lang);
        String template = MessageUtil.getGuiMsg(msgs, key);
        return ChatColor.translateAlternateColorCodes('&',
                template.replace("{seconds}", String.valueOf(seconds)));
    }

    // ==================== Duel End ====================

    /**
     * End a duel normally (someone won).
     */
    public void endDuel(DuelRoom room) {
        if (!room.isActive()) return;
        room.setActive(false);

        try {
        UUID winnerId = room.getWinner();
        UUID loserId = room.getLoser();
        // 空值守卫：endDuel 是 public，理论上只在 isFinished() 后调用（winner/loser
        // 必非 null），但 getScore(null) 会 NPE —— 不为落库这一个副作用把既有结算
        // 链的失败点前移，故此处按 0 兜底（recordDuelResultAsync 自身也会跳过 null）。
        int winnerScore = winnerId == null ? 0 : room.getScore(winnerId);
        int loserScore = loserId == null ? 0 : room.getScore(loserId);

        // R10（战略锐评：决斗零持久化）：战绩落库 —— 一场一行追加进 duel_stats。
        // 位置在判负/播报之前：此刻 winner/loser/分数已从 DuelRoom 快照取出，
        // 后续 cleanup(room) 拆解状态不影响本次写入。若 recordDuelResult 内部
        // 抛未预期异常也不该挡结算，故整段再包一层兜底（DB 层自身已吞 SQLException
        // 只记 WARNING，这里防的是非 SQL 的 Runtime 异常，如连接重建失败）。
        recordDuelResultAsync(winnerId, loserId, winnerScore, loserScore);

        // H9：对决首胜成就 —— 放在 winner 在线判断之外，赢家此刻掉线的极端
        // 窗口也允许落库（tryUnlock 对离线玩家静默跳过通知段）。只有正常打满
        // WIN_SCORE 触发 endDuel 才挂钩；quit/掉线宽限判负不走本方法（语义裁剪）。
        try {
            plugin.getAchievementManager().tryUnlock(winnerId, Achievement.DUEL_FIRST_WIN);
        } catch (RuntimeException e) { // R4-07-eh-01：懒加载读库抛 RuntimeException
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Duel achievement unlock failed", e);
        }

        Player winner = Bukkit.getPlayer(winnerId);
        Player loser = Bukkit.getPlayer(loserId);

        // Announce
        String winnerName = winnerId.equals(room.getPlayer1()) ? room.getPlayer1Name() : room.getPlayer2Name();
        String loserName = loserId.equals(room.getPlayer1()) ? room.getPlayer1Name() : room.getPlayer2Name();

        if (winner != null && winner.isOnline()) {
            winner.sendMessage(getMsg(winner, "duel.win",
                    "opponent", loserName,
                    "score1", String.valueOf(room.getScore(winnerId)),
                    "score2", String.valueOf(room.getScore(loserId))));
            removeScoreboard(winner);
            winner.closeInventory();
            SoundManager.playSound(winner, "complete");
        }

        if (loser != null && loser.isOnline()) {
            loser.sendMessage(getMsg(loser, "duel.lose",
                    "opponent", winnerName,
                    "score1", String.valueOf(room.getScore(loserId)),
                    "score2", String.valueOf(room.getScore(winnerId))));
            removeScoreboard(loser);
            loser.closeInventory();
            // R-84: 输家也出声 —— 复用现有 "incorrect" 键（无新音效 key），
            // 与赢家的 "complete" 对称，避免败方全程静默。
            SoundManager.playSound(loser, "incorrect");
        }

        } finally { // R4-07-eh-01：任一步抛也必须摘 activeDuels 双键，否则双方永久 busy
            cleanup(room);
        }
    }

    /**
     * R10：把一场战绩异步写入 {@code duel_stats}。
     *
     * <p><b>线程契约</b>：唯一调用点 {@link #endDuel(DuelRoom)} 在主线程
     * （submitAnswer 由 ChatAnswerListener 的 {@code runTask} 回调驱动，超时路径
     * 走 {@code runTaskTimer}，均在主线程）。{@code DatabaseManager.recordDuelResult}
     * 是同步阻塞写且全程持 {@code connectionLock}（该锁同时被周期保存/其他异步
     * 读共享），在主线程直接调会在磁盘慢/MySQL 抖动时卡 TPS —— 故一律派发到
     * Bukkit 异步线程，与 {@code AchievementManager.checkHelpAchievement}、
     * {@code ReportGenerator.runAsync} 的既有纪律同源。
     *
     * <p>参数在此刻已全部捕获为值（UUID/int），异步体不触碰 {@code DuelRoom} ——
     * 主线程的 {@code cleanup(room)} 会拆解房间状态，跨线程读房间字段是数据竞争。
     *
     * <p>关服/停用窗口：经 {@link SchedulerGuard#runAsync} 派发（调度器已关闭时
     * 静默放弃，不会抛 IllegalStateException 冒泡进结算路径）。此时仅丢这一条
     * 历史战绩，胜负播报/成就/奖励均已先行完成。
     */
    private void recordDuelResultAsync(UUID winnerId, UUID loserId,
                                       int winnerScore, int loserScore) {
        if (winnerId == null || loserId == null) {
            return; // 防御：endDuel 只在 isFinished() 后调用，理论上恒非 null
        }
        // 先在主线程把依赖夹具取成局部变量：异步体只闭包值，不再回头摸 plugin 的
        // DI 字段（关服窗口字段可能已被置空）。
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null) {
            return; // 极端初始化顺序（DB 未就位）：放弃落库，不挡结算
        }
        // F1: 登记完成点，shutdown() 才能等到这次 UPSERT。
        CompletableFuture<Void> handoff = new CompletableFuture<>();
        inflightDuelStats.add(handoff);
        SchedulerGuard.runAsync(plugin, () -> {
            try {
                db.recordDuelResult(winnerId, loserId, winnerScore, loserScore);
            } catch (RuntimeException e) {
                // DB 层已把 SQLException 降级为 WARNING；这里兜非 SQL 异常
                // （如重连失败的 RuntimeException），绝不让结算链受损。
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Unexpected error while persisting duel result", e);
            } finally {
                inflightDuelStats.remove(handoff);
                handoff.complete(null);
            }
        });
    }

    /**
     * Quit a duel — forfeit.
     *
     * <p><b>R23（清理矩阵缺口）</b>：对手<b>离线</b>时也要给他一个交代。
     *
     * <p>原实现只对在线对手 {@code sendMessage("duel.opponent-forfeit")}，离线一方整段
     * 跳过；而紧随其后的 {@code cleanup(room)} 会把双方的 {@code disconnectTimers} 条目
     * 一并摘掉、并 cancel 掉离线方的宽限任务 —— 于是其重连时 {@code handleReconnect} 的
     * {@code if (info == null) return;} 恒为 null 分支，<b>静默返回</b>。玩家看到的是：
     * 对局界面消失、分数停在断线那一刻、聊天栏一句话都没有，无法区分「对手 quit」/
     * 「GM 解散」/「自己超时判负」三种结局。
     *
     * <p>同一份代码的另外两条拆解路径都已补发（{@code cancelDuelByAdmin}：
     * {@code duel.cancelled-by-gm}；宽限超时：{@code duel.lost-by-disconnect} /
     * {@code duel.duel-already-over}），唯独这里漏了 —— 是缺口而非设计选择。
     *
     * <p><b>为什么不复用 {@code duel.cancelled-by-gm}</b>：那条文案说的是「管理员解散」，
     * 在本场景是<b>谎话</b>。也不复用 {@code duel.lost-by-disconnect}（「你被判负，
     * 对手获胜」）—— quit 路径不判负、不记战绩。故独立成
     * {@code duel.opponent-quit-while-disconnected}。
     *
     * <p><b>在线对手不排队</b>：上面的 {@code sendMessage} 已经通知过了，再入队会在其
     * 下次进服时重复播报一次（{@code consumeOfflineDuelNotice} 的读后即清挡不住这种
     * 「先发后排队」）。
     */
    public void quitDuel(Player player) {
        UUID playerId = player.getUniqueId();
        DuelRoom room = activeDuels.get(playerId);
        if (room == null || !room.isActive()) {
            player.sendMessage(getMsg(player, "duel.not-in-duel"));
            return;
        }

        UUID opponentId = room.getOpponent(playerId);
        Player opponent = Bukkit.getPlayer(opponentId);

        player.sendMessage(getMsg(player, "duel.forfeit"));
        if (opponent != null && opponent.isOnline()) {
            opponent.sendMessage(getMsg(opponent, "duel.opponent-forfeit",
                    "player", player.getName()));
            removeScoreboard(opponent);
            opponent.closeInventory();
            SoundManager.playSound(opponent, "complete");
        } else {
            // R23：对手离线 —— 排队一条「对手 X 退出了决斗」，下次进服由
            // PlayerJoinListener 的离线通告链路投递。占位名取房间存量的名字字段
            // （与 cancelDuelByAdmin / 宽限超时同源），不依赖可能已被回收的实体。
            enqueueOfflineDuelNotice(opponentId, otherDuelistName(room, opponentId),
                    "duel.opponent-quit-while-disconnected");
        }

        removeScoreboard(player);
        player.closeInventory();

        room.setActive(false);
        cleanup(room);
    }

    // ==================== Disconnect Handling ====================

    /**
     * R14（红队 INFO-5）：本房间是否还有玩家处在掉线宽限期内 —— <b>按房间身份</b>判定。
     *
     * <p>为什么不直接 {@code disconnectTimers.containsKey(p1/p2)}：那个写法把「玩家 UUID
     * 是否有宽限条目」当成「本房间是否有人掉线」。一旦存在属于**上一场对局**的残留条目
     * （拆解顺序将来被改动、或某条路径提前 return 漏掉 cleanup），本房间就会被永久误判为
     * 停盘。当前代码构造不出该状态，但那是论证，不是结构保证。
     * 按 {@link DisconnectInfo#room} 身份比对之后，别的房间的条目在结构上不可能波及其他房间。
     *
     * <p>顺带消除一个 NPE 面：旧写法依赖 {@code room.getPlayer1()} 非 null，
     * 而 {@code Map.containsKey(null)} 在 {@code ConcurrentHashMap} 上会抛 NPE。
     *
     * @param room 待判定的房间（可为 null —— 为 null 时视为无人在宽限期）
     * @return true 表示该房间还有至少一方未归位，必须保持停盘
     */
    private boolean isAnyDisconnectPendingFor(DuelRoom room) {
        if (room == null) {
            return false;
        }
        for (DisconnectInfo info : disconnectTimers.values()) {
            if (info != null && info.room == room) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called when a player disconnects during a duel.
     */
    public void handleDisconnect(Player player) {
        UUID playerId = player.getUniqueId();
        DuelRoom room = activeDuels.get(playerId);
        if (room == null || !room.isActive()) return;

        // R5-M1(a)：覆盖写前先取消旧宽限任务（幂等；正常路径恒为空）。
        BukkitTask prevGrace = graceTasks.remove(playerId);
        if (prevGrace != null) { prevGrace.cancel(); }

        disconnectTimers.put(playerId, new DisconnectInfo(room));
        // R4-07-logic-F01：宽限期内停盘，否则在场方连答打满 WIN_SCORE 即把掉线局写成正常战绩。
        room.setPaused(true);
        cancelWordTimeout(room);

        UUID opponentId = room.getOpponent(playerId);
        Player opponent = Bukkit.getPlayer(opponentId);
        if (opponent != null && opponent.isOnline()) {
            opponent.sendMessage(getMsg(opponent, "duel.opponent-disconnected",
                    "player", player.getName(),
                    "seconds", String.valueOf(GRACE_PERIOD_SECONDS)));
        }

        // Start grace period countdown
        int[] remainingSeconds = {GRACE_PERIOD_SECONDS};
        // F3: 只快照名字 —— 本任务活过掉线，强引用 Player 会把实体图钉住 30 秒。
        final String disconnectedName = player.getName();
        // R5-M1(b)：自引用 holder —— lambda 里的 this 是 DuelManager（非 task），
        // 需要 hold 住自身引用供自清理使用；首 tick 在 20L 之后，届时 holder[0] 必然已赋值。
        final BukkitTask[] selfRef = new BukkitTask[1];
        selfRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds[0]--;

            // Check if player reconnected
            if (!disconnectTimers.containsKey(playerId)) {
                selfCancelGrace(playerId, selfRef[0]); // 原"return; // timer will be cancelled below"——下方不可达，改自取消
                return;
            }

            DuelRoom currentRoom = activeDuels.get(opponentId);
            if (currentRoom == null || !currentRoom.isActive()) {
                // R-26: 对局已无 —— 停掉本宽限任务，不再每秒空转。
                // R5-M1(b)：改自引用自取消（对称统一，防未来回归）。
                // R14（双掉线的"蒸发"半场）：本条原始缺陷是「X 掉线、Y 掉线，先到点的
                // 那条计时判负拆房，另一条计时随后命中这里静默自取消，双方全程零反馈」。
                // 主线已在判负分支里替同处宽限期的对手补排了通告（见 `remainingSeconds
                // <= 0` 分支），故正常时序下本分支不会再是沉默路径 —— 这里保留为兜底：
                // 任何将来新增的「绕过 cleanup 的拆解」或改序都可能让计时活过房间拆除，
                // 届时仍须给掉线方一个交代，而不是静默 return。
                //
                // 文案取中性 duel-already-over 而**不是** lost-by-disconnect：本分支只能
                // 确认「房间没了」，无从判断谁被判负 —— 凡会走到这里的拆解路径里，本玩家
                // 要么是受益方（对侧宽限先到点判负），要么是第三方解散（quit/GM/内部取消）
                // 的无过错方；两种都不该收到「你被判负、X 获胜」的错误结论。
                // 已有通告不覆盖：mainline 排下的 lost-by-disconnect 更准确，不能说没就没。
                selfCancelGrace(playerId, selfRef[0]);
                disconnectTimers.remove(playerId);
                if (!offlineNotices.containsKey(playerId)) {
                    // 极端窗口：玩家已进服但 handleReconnect 的 20 tick 延迟还没到
                    // （PlayerJoinListener 在 tick 0 就把离线队列消费完了，本分支之后再
                    // 排的条目要等到"下一次"进服才送达）—— 此刻玩家在线，直接发。
                    Player rejoined = Bukkit.getPlayer(playerId);
                    if (rejoined != null && rejoined.isOnline()) {
                        rejoined.sendMessage(getMsg(rejoined, "duel.duel-already-over"));
                    } else {
                        enqueueOfflineDuelNotice(playerId, otherDuelistName(room, playerId),
                                "duel.duel-already-over");
                    }
                }
                return;
            }

            Player opp = Bukkit.getPlayer(opponentId);
            // R26：在线与否只读一次。原先该判据在本 tick 内被读三遍（判负分支、补排守卫、
            // 倒计时/计分板各一遍）；三处都要求「同一刻的同一个事实」—— 主线程同一 tick 内
            // 玩家状态不可能中途变化，故收敛成一个布尔，顺带让下面的判决与副作用读同一个值。
            final boolean opponentOnline = opp != null && opp.isOnline();
            if (remainingSeconds[0] <= 0) {
                // Grace period expired — forfeit
                // 僵尸任务修复：照 R-26 模式自行摘登记并 cancel 任务本体 ——
                // 原实现只 graceTasks.remove 不 cancel，任务会带着已空的登记条目
                // 每 20 tick 空转到关服（cleanup/shutdown 因条目已摘都造访不到它）。
                // R5-M1(b)：改双参自取消 —— 单参 remove 可能误摘同房间新一轮任务。
                selfCancelGrace(playerId, selfRef[0]);
                disconnectTimers.remove(playerId);

                // R26：判决抽成纯函数（{@link DuelGraceOutcome}）—— 它只回答「谁被判负 /
                // 对手那一侧该发哪条文案」，发送、摘计分板、关界面、cleanup 等副作用
                // 全部留在本方法。这样三档对手处理（在线直发胜者措辞 / 离线补排中性文案 /
                // 已有通告不覆盖）从"两个 if + 一次 containsKey"的隐式约定变成可断言的值。
                //
                // 关于 `offlineNotices.containsKey(opponentId)` 的读取时机：原实现读在
                // 「判负方通告已排队」之后，这里提到副作用之前。两者等价 —— 上面那次排队
                // 写的键是 playerId，而 self-duel 在 sendDuelRequest:126 已被拒，
                // playerId != opponentId 恒成立，故先读后读同值。
                //
                // 拆解（room.setActive(false) + cleanup）刻意留在 Forfeit 判定**之外**：
                // 上游 :994 已保证此处房间必定 active 且本玩家是成员，故 decide 恒返回
                // Forfeit；把它挪进分支只会让"将来某个 Defer 前提被放松"时多出一条
                // 漏拆房（双方永久 busy）的路径，而这正是 R26 最不该引入的风险。
                DuelGraceOutcome outcome = DuelGraceOutcome.decide(
                        room, playerId, opponentOnline,
                        offlineNotices.containsKey(opponentId));

                if (outcome instanceof DuelGraceOutcome.Forfeit forfeit) {
                    // 1) 对手（本场胜者）那一侧
                    // `opp != null` 与 Direct 的前提重复（Direct 只可能由 opponentOnline 推出，
                    // 而 opponentOnline 恒等于 `opp != null && opp.isOnline()`）。保留它不是
                    // 冗余防御，而是为了**逐字节保持**原实现「取不到实体就什么都不做」的失败
                    // 模式：判决与副作用之间的耦合一旦被将来改动切断，这里退化成静默跳过，
                    // 而不是 NPE。
                    if (opp != null && forfeit.opponentNotice()
                            instanceof DuelGraceOutcome.OpponentNotice.Direct direct) {
                        // R-86n: 文案改带 {seconds} 占位（秒数不再写死 30），
                        // 与 GRACE_PERIOD_SECONDS 保持单一真相源。
                        // Direct 只可能由 opponentOnline 推出，故 opp 必非 null。
                        opp.sendMessage(getMsg(opp, direct.key(),
                                "player", disconnectedName,
                                "seconds", String.valueOf(GRACE_PERIOD_SECONDS)));
                        removeScoreboard(opp);
                        opp.closeInventory();
                        SoundManager.playSound(opp, "complete");
                    }

                    // 2) 判负方：R14 —— 此前他永远收不到任何反馈，下面的 cleanup 会摘掉他的
                    // disconnectTimers 条目，重连时 handleReconnect 的
                    // `if (info == null) return;` 直接静默返回，连 duel-already-over 都没有。
                    // 故此处给他一条 duel.lost-by-disconnect（{player} = 对手名）：在线就
                    // 当场发（「已重连但 handleReconnect 尚未跑到」的极窄窗口），离线则
                    // 排队等进服补发。
                    String opponentName = otherDuelistName(room, playerId);
                    Player forfeiter = Bukkit.getPlayer(playerId);
                    if (forfeiter != null && forfeiter.isOnline()) {
                        forfeiter.sendMessage(getMsg(forfeiter, forfeit.forfeiterKey(),
                                "player", opponentName));
                        removeScoreboard(forfeiter);
                        forfeiter.closeInventory();
                    } else {
                        enqueueOfflineDuelNotice(playerId, opponentName,
                                forfeit.forfeiterKey());
                    }

                    // 3) 对手离线时的补偿：R14（双掉线）—— 对手此刻也离线时（X 掉线 → Y 掉线
                    // → X 的计时先到点），下面的 cleanup 会连 Y 的宽限任务一并 cancel，Y 的
                    // 计时永远跑不到自己的分支，Y 进服后同样毫无说明。注意 Y 的语义与判负方
                    // 相反：判负的是 X，Y 是这个宽限超时的受益方，故不能复用
                    // lost-by-disconnect（那会说「你被判负，X 获胜」，与事实颠倒），改用中性的
                    // duel-already-over（「你断线期间对决已经结束了」）—— 该键无占位符，
                    // 多传的名字无害。「已有通告不覆盖」由 PreserveExisting 表达：
                    // GM 解散等更具体的交代必须留住（overwrite 语义是给同一原因重复排队用的，
                    // 不该吃掉另一条链路的原因）。
                    if (forfeit.opponentNotice()
                            instanceof DuelGraceOutcome.OpponentNotice.QueueNeutral neutral) {
                        enqueueOfflineDuelNotice(opponentId,
                                otherDuelistName(room, opponentId), neutral.key());
                    }
                }

                room.setActive(false);
                cleanup(room);
            } else if (remainingSeconds[0] <= 5 || remainingSeconds[0] % 10 == 0) {
                // Notify remaining player
                if (opponentOnline) {
                    opp.sendMessage(getMsg(opp, "duel.grace-countdown",
                            "seconds", String.valueOf(remainingSeconds[0]),
                            "player", disconnectedName));
                }
            }

            // Update sidebar scoreboard with countdown
            if (opponentOnline) {
                updateScoreboardWithGrace(opp, room, remainingSeconds[0]);
            }
        }, 20L, 20L); // every second

        graceTasks.put(playerId, selfRef[0]);
    }

    /**
     * Called when a player rejoins after disconnecting during a duel.
     */
    public void handleReconnect(Player player) {
        if (player == null || !player.isOnline()) {
            return;   // R5-M1(c)/R5-L1：延迟回调落在已离线玩家 —— 放弃（新 quit 的宽限不受扰）。
                      // 注：离线补通知属 R-69 offlineNotices 链路，不走本方法。
        }
        UUID playerId = player.getUniqueId();
        DisconnectInfo info = disconnectTimers.remove(playerId);
        if (info == null) return;

        // Cancel grace timer
        BukkitTask task = graceTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }

        DuelRoom room = info.room;
        if (!room.isActive()) {
            // Defense in depth: verified unreachable today — every teardown path
            // (endDuel / quitDuel / cancelDuelByAdmin / cancelDuelInternal /
            // grace expiry) deactivates the room AND removes this player's
            // disconnectTimers entry inside the same main-thread task (cleanup()),
            // so a later handleReconnect already sees info == null. Kept so a
            // future reordering of teardown steps degrades gracefully instead of
            // silently returning. (R-69: offline GM-dismissal notices are
            // delivered separately via consumeOfflineDuelNotice on join.)
            player.sendMessage(getMsg(player, "duel.duel-already-over"));
            return;
        }

        UUID opponentId = room.getOpponent(playerId);
        Player opponent = Bukkit.getPlayer(opponentId);

        player.sendMessage(getMsg(player, "duel.reconnected"));
        // R4-07-logic-F01 + R14（双掉线反例）：重连即复赛 —— 但"复赛"必须按实际还在
        // 掉线的人数判定，不能无条件 setPaused(false)。paused 是「每房间」一个布尔，
        // 而宽限计时是「每玩家」一份：X、Y 同时掉线时两人各有一条计时、paused 为 true；
        // 先回来的 X 跑本方法，若直接解暂停，房间就在 Y 仍处宽限期内复赛 ——
        // scheduleWordTimeout 还给 Y 重排一轮题钟，X 连答 5 题无人对抗 → endDuel →
        // 一场 5-0 的「正常战绩」写进 duel_stats，正是 F01 注释里不许发生的事。
        //
        // 判据是「本房间」而不是「这两个 UUID」：R14 红队指出，原先按
        // `disconnectTimers.containsKey(p1/p2)` 判定时，只要存在一条**属于上一场对局**的
        // 残留条目，本房间就会被误判为「还有人掉线」而一直停盘。当前构造不出这种状态
        // （所有拆解路径都经 cleanup 同时摘 activeDuels 与 disconnectTimers，且 acceptDuel
        // 拒绝仍在 activeDuels 里的人），但那是"论证出来的不可能"，不是"结构上的不可能"。
        // 改成按 DisconnectInfo.room 身份比对后，别的房间的条目在结构上不可能影响本房间。
        // 顺带消除 containsKey(null) 的 NPE 面（旧写法依赖 getPlayer1() 非 null）。
        boolean anyStillDisconnected = isAnyDisconnectPendingFor(room);
        room.setPaused(anyStillDisconnected);
        // 另一方仍在宽限期内时不得重排本题超时 —— 那等于给缺席方重启一轮计时
        // （题钟恢复的同时房间还停着盘，纯粹空烧；待对侧 handleReconnect 再启动）。
        if (!anyStillDisconnected) {
            scheduleWordTimeout(room);
        }

        if (opponent != null && opponent.isOnline()) {
            opponent.sendMessage(getMsg(opponent, "duel.opponent-reconnected",
                    "player", player.getName()));
        }

        // Re-open GUI and scoreboard
        new DuelGUI(plugin).open(player, room);
        setupScoreboard(player, room);

        // S-I / 3A-3: re-attach the reconnecting player to the room's shared
        // countdown boss bar (handleDisconnect deliberately left the bar alone,
        // so the opponent could keep watching it during the grace period).
        // removePlayer first: if the disconnect left a stale viewer entry
        // (Bukkit-side set vs vanilla event set can diverge), addPlayer alone
        // would no-op and the bar would silently stay invisible for this player.
        // Both calls are no-ops for a viewer that was already fully detached.
        BossBar bar = duelBars.get(room.getPlayer1());
        if (bar != null) {
            bar.removePlayer(player);
            bar.addPlayer(player);
        }

        // Re-setup opponent's scoreboard (remove grace timer)
        if (opponent != null && opponent.isOnline()) {
            setupScoreboard(opponent, room);
        }
    }

    // ==================== Cancel / Cleanup ====================

    /**
     * FIX-DUEL-01: GM-forced duel dismissal.
     *
     * <p>Semantics: NO winner/loser is declared — the room is torn down as if
     * cancelled. Both sides are told a GM ended the duel; no forfeit stigma
     * is attached to either player. Does not touch the M4 answering gate or
     * any pending invitations (GM dismissal only targets an ACTIVE room).
     *
     * <p>R-69: a side that is offline when the GM dismisses the duel cannot be
     * messaged — a notice is queued instead and delivered on that player's
     * next join (see {@link #consumeOfflineDuelNotice(UUID)}).
     *
     * @param gm the admin performing the dismissal (receives confirmation)
     * @param targetName name of one of the two duelists
     */
    public void cancelDuelByAdmin(CommandSender gm, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        DuelRoom room;
        String displayName;
        if (target != null) {
            room = activeDuels.get(target.getUniqueId());
            displayName = target.getName();
        } else {
            // P2-08 收口（低危尾账批次）：双方都离线时 GM 无法解散——原实现直接回
            // admin.duel-cancel-offline 且无替代通道（R-69 队列只管重连补发，不管入口）。
            // 离线目标按人名回退遍历 activeDuels 找房间（DuelRoom 有双方名字存量）；
            // 找到后照常走解散+双方离线通知队列，找不到才回离线提示。
            room = null;
            displayName = null;
            for (DuelRoom candidate : new java.util.HashSet<>(activeDuels.values())) {
                if (!candidate.isActive()) continue;
                if (targetName.equalsIgnoreCase(candidate.getPlayer1Name())) {
                    room = candidate;
                    displayName = candidate.getPlayer1Name();
                    break;
                }
                if (targetName.equalsIgnoreCase(candidate.getPlayer2Name())) {
                    room = candidate;
                    displayName = candidate.getPlayer2Name();
                    break;
                }
            }
            if (room == null) {
                gm.sendMessage(plugin.getMessageUtil().getSenderMessage(gm,
                        "admin.duel-cancel-offline", "player", targetName));
                return;
            }
        }
        if (room == null || !room.isActive()) {
            gm.sendMessage(plugin.getMessageUtil().getSenderMessage(gm,
                    "admin.duel-cancel-not-in-duel", "player", displayName));
            return;
        }

        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null && p1.isOnline()) {
            p1.sendMessage(getMsg(p1, "duel.cancelled-by-gm"));
            removeScoreboard(p1);
            p1.closeInventory();
            // R-84: 轻量音效提示在线方"对局被解散"。
            SoundManager.playSound(p1, "menu-click");
        } else {
            // R-69: 离线方重连后仍要知道对局已被 GM 解散（重连路径已静默）。
            enqueueOfflineDuelNotice(room.getPlayer1(), room.getPlayer2Name());
        }
        if (p2 != null && p2.isOnline()) {
            p2.sendMessage(getMsg(p2, "duel.cancelled-by-gm"));
            removeScoreboard(p2);
            p2.closeInventory();
            // R-84: 同上，在线方给个解散音效。
            SoundManager.playSound(p2, "menu-click");
        } else {
            enqueueOfflineDuelNotice(room.getPlayer2(), room.getPlayer1Name());
        }

        room.setActive(false);
        cancelWordTimeout(room);
        cleanup(room);

        gm.sendMessage(plugin.getMessageUtil().getSenderMessage(gm,
                "admin.duel-cancelled",
                "player1", room.getPlayer1Name(),
                "player2", room.getPlayer2Name()));
        plugin.getLogger().info("Duel between " + room.getPlayer1Name()
                + " and " + room.getPlayer2Name()
                + " was force-cancelled by GM " + gm.getName());
    }

    /**
     * R-69: queue a one-shot notice for a player who was offline at teardown.
     *
     * <p>Overwrite semantics — one pending notice per player at most; the most
     * recent teardown wins. Entries survive {@link #cleanup(DuelRoom)} on
     * purpose (the recipient may never come back — that is a bounded, intended
     * residue, bounded to one entry per UUID), and are removed by
     * {@link #consumeOfflineDuelNotice(UUID)} on delivery.
     */
    private void enqueueOfflineDuelNotice(UUID playerId, String otherPlayerName) {
        enqueueOfflineDuelNotice(playerId, otherPlayerName, "duel.cancelled-by-gm");
    }

    /**
     * R14: 同 {@link #enqueueOfflineDuelNotice(UUID, String)}，但由调用方指定
     * {@code messageKey}。
     *
     * <p>拆出重载而非改原签名：GM 解散（{@code cancelDuelByAdmin} 的两处调用）
     * 语义是「不判胜负的作废」，文案必须是既有的 {@code duel.cancelled-by-gm}；
     * 掉线宽限超时判负需要另一条说明「你被判负、对手获胜」的文案
     * （{@code duel.lost-by-disconnect}）。两者共用同一份队列与投递链路，
     * 变的只是 {@link OfflineDuelNotice#messageKey()}。
     *
     * @param playerId        recipient (offline at teardown)
     * @param otherPlayerName the OTHER duelist's name, usable as the {player}
     *                        placeholder — must be non-null (占位符替换不接受 null)
     * @param messageKey      i18n key the consumer resolves on next join
     */
    private void enqueueOfflineDuelNotice(UUID playerId, String otherPlayerName,
                                          String messageKey) {
        if (playerId == null) return; // 防御：无 UUID 就无处投递
        offlineNotices.put(playerId, new OfflineDuelNotice(
                messageKey, safeName(otherPlayerName)));
    }

    /**
     * R14: 取房间里「另一个人」的名字（离线通告的 {player} 占位）。
     *
     * <p>只读 {@link DuelRoom} 的存量名字字段（掉线玩家实体已不可查，{@code Bukkit.getPlayer}
     * 恒 null），因此不依赖对方是否在线。{@code playerId} 既不等于任何一方时
     * （理论上不会发生）回退到对手名而非 null —— 占位符替换对 null 直接 NPE。
     */
    private static String otherDuelistName(DuelRoom room, UUID playerId) {
        if (room == null) return "?";
        if (playerId != null && playerId.equals(room.getPlayer1())) {
            return safeName(room.getPlayer2Name());
        }
        return safeName(room.getPlayer1Name());
    }

    /**
     * R-69 (interface frozen for PlayerJoinListener): return and remove the
     * pending offline duel notice for this player, or {@code null} if none.
     *
     * <p>Called on player join; after this call the queue holds no entry for
     * the player (consume = deliver-once semantics). Idempotent.
     *
     * @param playerId the joining player's UUID
     * @return the pending notice, or {@code null}
     */
    public OfflineDuelNotice consumeOfflineDuelNotice(UUID playerId) {
        if (playerId == null) return null;
        return offlineNotices.remove(playerId);
    }

    /**
     * Tear a duel down internally (no winner/loser) — both sides get the
     * "duel.cancelled" message and the room goes through {@link #cleanup}.
     *
     * @param room   the room to cancel
     * @param reason currently unused (all call sites pass {@code "no-words"});
     *               the parameter is kept as the future logging hook — wiring a
     *               debug log here is the intended consumer, not an oversight
     */
    private void cancelDuelInternal(DuelRoom room, String reason) {
        cancelDuelInternal(room, reason, "duel.cancelled");
    }

    /**
     * 同 {@link #cancelDuelInternal(DuelRoom, String)}，但双方收到的提示改用
     * {@code messageKey}（走 {@link #getMsg} 的 per-player 语言渲染，支持
     * {@code {prefix}} 与占位符）。
     *
     * <p>拆出重载而非改原签名：原三处调用点（{@code no-words} 路径）语义是「本局作废」，
     * 文案必须是既有的 {@code duel.cancelled}；{@code /lea reload} 卸载路径需要一条
     * 说明「本服已关闭英语学习功能」的不同文案。共用同一段拆解逻辑，保证
     * {@code setActive → cancelWordTimeout → 双端消息 → removeScoreboard →
     * closeInventory → cleanup} 的步骤与顺序对所有调用方恒等 —— 少一步就会留下
     * 计分板残影或未取消的计时任务。
     *
     * @param room       the room to cancel
     * @param reason     logging hook (unused today, same as the 2-arg overload)
     * @param messageKey i18n key sent to both online duelists
     */
    private void cancelDuelInternal(DuelRoom room, String reason, String messageKey) {
        room.setActive(false);
        cancelWordTimeout(room);
        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null && p1.isOnline()) {
            p1.sendMessage(getMsg(p1, messageKey));
            removeScoreboard(p1);
            p1.closeInventory();
        }
        if (p2 != null && p2.isOnline()) {
            p2.sendMessage(getMsg(p2, messageKey));
            removeScoreboard(p2);
            p2.closeInventory();
        }
        cleanup(room);
    }

    /**
     * /lea reload 动态卸载钩子：<b>直接解散</b>所有进行中的决斗，<b>不结算</b> ——
     * 不判胜负、不记战绩、不写 {@code duel_stats}、不挂钩首胜成就。
     *
     * <p><b>为什么不能走 {@link #endDuel}</b>：
     * <ul>
     *   <li>{@link DuelRoom#getWinner()} 只在某人先到 WIN_SCORE 时非 null；被 reload
     *       打断的房间两者都没打满，winner 恒 null；</li>
     *   <li>{@code endDuel} 虽对 winnerScore 做了 0 兜底，但紧接着
     *       {@code tryUnlock(winnerId, DUEL_FIRST_WIN)} 传 null UUID 有 NPE 风险；</li>
     *   <li>语义上被打断不是任何一方的过错 —— 判负会写进 {@code duel_stats} 永久拉低
     *       胜率，与「服务器关掉功能」这一外部原因不符。</li>
     * </ul>
     *
     * <p><b>去重是必须的</b>：{@link #activeDuels} 是双键结构（player1 → room 与
     * player2 → room 两个键指向<b>同一个</b> {@link DuelRoom} 实例）。直接遍历
     * {@code activeDuels.values()} 会让每个房间被处理两次 —— {@code setActive(false)}
     * 与 {@code cleanup} 都是幂等的，但<b>消息会重复发一遍</b>。此处按实例引用去重
     * （{@code DuelRoom} 未覆写 equals/hashCode，HashSet 即身份比较，正是所需语义）。
     *
     * <p><b>容错</b>：逐房间 try/catch —— 房间可能处于宽限期、掉线、GUI 已关等中间态，
     * 一个房间拆解失败不能影响其余房间，也不能让异常冒到 reload 流程。本方法不抛异常。
     *
     * @return 实际解散的房间数（去重后；已 {@code isActive()==false} 的不计）
     */
    public int dismissAllActiveDuels() {
        int dismissed = 0;
        Set<DuelRoom> rooms;
        try {
            rooms = new HashSet<>(activeDuels.values());
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to snapshot active duels on dismiss: " + t.getMessage());
            return 0;
        }
        for (DuelRoom room : rooms) {
            if (room == null) {
                continue;
            }
            try {
                if (!room.isActive()) {
                    continue;
                }
                cancelDuelInternal(room, "reload", "duel.dismissed-server-off");
                dismissed++;
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to dismiss duel between "
                        + safeName(room.getPlayer1Name()) + " and " + safeName(room.getPlayer2Name())
                        + ": " + t.getMessage());
            }
        }
        return dismissed;
    }

    private static String safeName(String name) {
        return name != null ? name : "?";
    }

    private void cleanup(DuelRoom room) {
        UUID p1 = room.getPlayer1();
        UUID p2 = room.getPlayer2();

        activeDuels.remove(p1);
        activeDuels.remove(p2);
        disconnectTimers.remove(p1);
        disconnectTimers.remove(p2);

        // Cancel any grace tasks
        BukkitTask t1 = graceTasks.remove(p1);
        if (t1 != null) t1.cancel();
        BukkitTask t2 = graceTasks.remove(p2);
        if (t2 != null) t2.cancel();

        // Cancel the single-question countdown (FIX-DUEL-01; S-I / 3A-3:
        // 现在同时会被 timer 的自清理路径摘除 —— 双参 remove 语义不矛盾)
        BukkitTask wt = wordTimeoutTasks.remove(p1);
        if (wt != null) wt.cancel();

        // S-I / 3A-3: release the shared countdown boss bar. removeAll() both
        // clears remaining viewers and unregisters the bar server-side (the
        // non-deprecated counterpart of hide() — plan §3.4). Every teardown
        // path funnels through cleanup, so this is the single release point.
        BossBar bar = duelBars.remove(p1);
        if (bar != null) bar.removeAll();
    }

    // ==================== Per-player unload (quit) ====================

    /**
     * D2 / R4-07-sec-F02 收尾：移除该玩家的<b>邀请冷却</b>条目（由 quit 编排点
     * {@code PlayerJoinListener#onPlayerQuit} 调用）。
     *
     * <p><b>缺陷本体</b>：{@code lastInviteAt} 原先<b>唯一</b>的清理点是
     * {@link #shutdown()} 里的 {@code clear()}。单服长期不重启时，这张表的键集合等于
     * 「本服历史上出现过的<b>所有</b>邀请者」，只增不减（与 {@code F2-10} / {@code F1-24} /
     * {@code R7-B} 记录的只增不删是同一类内存卫生问题）。同仓其它 per-player 缓存
     * （{@code unloadPlayerData} / {@code AchievementManager#unloadPlayer} /
     * {@code CheckInManager#unloadPlayer} / {@code SocialManager#unloadPlayer} …）都已在
     * quit 收口，唯独这张表漏了一格。（这一行刻意不写数据层类名：{@code DuelManagerAuditTest}
     * 的「决斗不得碰学习数据」lint 读的是<b>原始源码</b>、不剥注释，写全名会被误报。）
     *
     * <p><b>为什么可以无条件移除（哪怕该玩家正在对决中）</b>：{@code lastInviteAt} 只被
     * {@link #sendDuelRequest} 读一处 —— 冷却判定（{@code nowMs - lastAt < 5s} 则静默返回），
     * 它<b>不参与</b>任何对决生命周期判定：
     * <ul>
     *   <li>「是否在局中」读的是 {@code activeDuels}（{@link #isBusy} /
     *       {@link #isInDuel}），与冷却表无关；</li>
     *   <li>写入点在 {@code isBusy} 检查<b>之前</b>，所以冷却表的状态从不承载「谁在局中」
     *       的信息 —— 删掉条目损失的信息量严格为 0；</li>
     *   <li>最坏情况只是「重置了一次 5 秒冷却」：玩家刚 quit（人已不在线），若他在宽限期内
     *       重连并立刻发邀请，则少挨一次静默丢弃 —— 而那时他仍在 {@code activeDuels} 里，
     *       {@code sendDuelRequest} 会照常回 {@code duel.already-in-duel}。反过来若只在
     *       「非对决状态」下移除，就会给「quit 时正在宽限期」的玩家留下一条<b>永不回收</b>
     *       的条目，正是本缺陷要修的东西。</li>
     * </ul>
     * 故语义取<b>无条件移除</b>，不做状态分支。
     *
     * <p>线程契约：调用点全部在 Bukkit 主线程（quit 事件）；表本身是
     * {@link ConcurrentHashMap}，即便被并发调用也只会少删/不删，不会破坏结构。
     * 幂等：条目不存在时 {@code remove} 是无操作。
     *
     * @param playerId 下线玩家的 UUID
     */
    public void unloadPlayer(UUID playerId) {
        if (playerId == null) {
            // 纵深防御：调用点传 null 说明 quit 编排里有别的问题，这里不制造 NPE
            // 去截断整条清理链（ConcurrentHashMap#remove(null) 会抛 NPE）。
            return;
        }
        lastInviteAt.remove(playerId);
    }

    // ==================== Shutdown ====================

    /**
     * R-72: plugin shutdown cleanup — cancel every duel-owned scheduler task,
     * reset scoreboards of players still in a duel, and clear all state maps.
     *
     * <p>Idempotent and NPE-safe: callable at any time (including with no
     * duels active or a partially initialized state) and safe to call twice.
     * Player-facing messages are intentionally NOT sent (server is stopping).
     *
     * <p>Called from {@code LetsLearnEnglish.onDisable()} (S8). The
     * offline-notice queue (R-69) is plain in-memory data with no tasks
     * attached and is cleared here as part of the full reset.
     */
    public void shutdown() {
        // F1: 有界等待在途战绩落库；返回后 onDisable 立刻关库，超时只记 WARNING。
        try {
            CompletableFuture.allOf(inflightDuelStats.toArray(new CompletableFuture[0]))
                    .get(2L, TimeUnit.SECONDS);
        } catch (Throwable t) {
            plugin.getLogger().warning("Duel result write did not complete: " + t);
        }
        inflightDuelStats.clear();
        // Cancel every tracked task container. BukkitTask.cancel() is
        // idempotent; guard against null from concurrent removal.
        cancelAllTasks(graceTasks);
        cancelAllTasks(wordTimeoutTasks);
        cancelAllTasks(requestExpiryTasks);

        // Reset scoreboards for players still inside an active duel. Iterate
        // the rooms' player ids (not activeDuels: two ids map to one room).
        Set<UUID> seen = new HashSet<>();
        for (DuelRoom room : activeDuels.values()) {
            if (room == null || !seen.add(room.getPlayer1())) {
                continue;
            }
            seen.add(room.getPlayer2());
            Player p1 = Bukkit.getPlayer(room.getPlayer1());
            if (p1 != null && p1.isOnline()) {
                removeScoreboard(p1);
                p1.closeInventory();
            }
            Player p2 = Bukkit.getPlayer(room.getPlayer2());
            if (p2 != null && p2.isOnline()) {
                removeScoreboard(p2);
                p2.closeInventory();
            }
        }

        // S-I / 3A-3: release every shared countdown boss bar (idempotent —
        // most paths already went through cleanup(); this sweeps whatever is
        // left if shutdown interrupts a live duel).
        for (BossBar bar : duelBars.values()) {
            if (bar != null) {
                bar.removeAll();
            }
        }
        duelBars.clear();

        // Clear every state map/set.
        activeDuels.clear();
        pendingRequests.clear();
        disconnectTimers.clear();
        offlineNotices.clear();
        lastInviteAt.clear(); // R4-07-sec-F02：冷却表随关服清，避免只增不删
    }

    private void cancelAllTasks(Map<UUID, BukkitTask> tasks) {
        for (BukkitTask task : tasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        tasks.clear();
    }

    // ==================== Scoreboard ====================

    private void setupScoreboard(Player player, DuelRoom room) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msgs = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(msgs, "duel.scoreboard-title"));

        org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        org.bukkit.scoreboard.Objective obj = board.registerNewObjective("duel", "dummy", title);
        obj.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);

        int lineNum = 5;

        // Player 1
        String p1Name = room.getPlayer1Name();
        if (p1Name.length() > 16) p1Name = p1Name.substring(0, 16);
        org.bukkit.scoreboard.Team p1Team = board.registerNewTeam("p1");
        p1Team.addEntry(ChatColor.AQUA + "");
        p1Team.setPrefix(ChatColor.translateAlternateColorCodes('&', "&a" + p1Name + ": "));
        p1Team.setSuffix(ChatColor.translateAlternateColorCodes('&', "&f" + room.getScore1()));
        obj.getScore(ChatColor.AQUA + "").setScore(lineNum--);

        // Player 2
        String p2Name = room.getPlayer2Name();
        if (p2Name.length() > 16) p2Name = p2Name.substring(0, 16);
        org.bukkit.scoreboard.Team p2Team = board.registerNewTeam("p2");
        p2Team.addEntry(ChatColor.RED + "");
        p2Team.setPrefix(ChatColor.translateAlternateColorCodes('&', "&c" + p2Name + ": "));
        p2Team.setSuffix(ChatColor.translateAlternateColorCodes('&', "&f" + room.getScore2()));
        obj.getScore(ChatColor.RED + "").setScore(lineNum--);

        // Spacer
        obj.getScore(" ").setScore(lineNum--);

        // Target score line
        String targetLine = MessageUtil.getGuiMsg(msgs, "duel.scoreboard-target");
        obj.getScore(ChatColor.translateAlternateColorCodes('&', targetLine)).setScore(lineNum);

        player.setScoreboard(board);
    }

    private void updateBothScoreboards(DuelRoom room) {
        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null && p1.isOnline()) {
            updateScoreboardPlayer(p1, room);
        }
        if (p2 != null && p2.isOnline()) {
            updateScoreboardPlayer(p2, room);
        }
    }

    private void updateScoreboardPlayer(Player player, DuelRoom room) {
        org.bukkit.scoreboard.Scoreboard board = player.getScoreboard();
        if (board == null) return;

        org.bukkit.scoreboard.Team p1Team = board.getTeam("p1");
        org.bukkit.scoreboard.Team p2Team = board.getTeam("p2");

        if (p1Team != null) {
            p1Team.setSuffix(ChatColor.translateAlternateColorCodes('&', "&f" + room.getScore1()));
        }
        if (p2Team != null) {
            p2Team.setSuffix(ChatColor.translateAlternateColorCodes('&', "&f" + room.getScore2()));
        }
    }

    private void updateScoreboardWithGrace(Player player, DuelRoom room, int remainingSeconds) {
        org.bukkit.scoreboard.Scoreboard board = player.getScoreboard();
        if (board == null) return;

        org.bukkit.scoreboard.Objective obj = board.getObjective("duel");
        if (obj == null) return;

        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msgs = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                MessageUtil.getGuiMsg(msgs, "duel.scoreboard-title-grace")
                        .replace("{seconds}", String.valueOf(remainingSeconds)));
        obj.setDisplayName(title);
    }

    private void removeScoreboard(Player player) {
        if (player.isOnline()) {
            var scoreboardManager = Bukkit.getScoreboardManager();
            // Shutdown hardening (R-72): during onDisable the manager can
            // already be gone — nothing left to reset in that case.
            if (scoreboardManager == null) return;
            org.bukkit.scoreboard.Scoreboard emptyBoard = scoreboardManager.getNewScoreboard();
            player.setScoreboard(emptyBoard);
        }
    }

    // ==================== GUI Refresh ====================

    /**
     * R-23: 重开当前对局界面（/le duel gui 的执行体）。
     *
     * <p>玩家 ESC 关掉对决 GUI 后手动回来用。只在对局活跃时重开；
     * 非对决中回既有 not-in-duel 提示。
     */
    public void reopenDuelGUI(Player player) {
        DuelRoom room = activeDuels.get(player.getUniqueId());
        if (room == null || !room.isActive()) {
            player.sendMessage(getMsg(player, "duel.not-in-duel"));
            return;
        }
        new DuelGUI(plugin).open(player, room);
    }

    /**
     * R-23: 只刷新"正看着对决 GUI"的玩家。
     *
     * <p>ESC 关掉界面的玩家不再被每题强制 open() 弹回。标题判定复用
     * DuelGUI.isViewingDuelGUI，与 DuelGUIListener 的点击判定同源。
     */
    private void refreshBothGUIs(DuelRoom room) {
        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null && p1.isOnline() && DuelGUI.isViewingDuelGUI(plugin, p1)) {
            new DuelGUI(plugin).open(p1, room);
        }
        if (p2 != null && p2.isOnline() && DuelGUI.isViewingDuelGUI(plugin, p2)) {
            new DuelGUI(plugin).open(p2, room);
        }
    }

    // ==================== Word Selection ====================

    /**
     * R14: 取一个随机词，并排除 {@code room} 的当前词 —— 否则连续两题可能发同一词
     * （{@code getRandomWord()} 原实现恒传 {@code null} 作 exclude，
     * {@link com.letslearnenglish.minecraftplugin.core.word.WordBank#getRandomWords(int, Word)}
     * 的 {@code !w.equals(exclude)} 过滤因此从未生效）。
     *
     * <p>首题场景（{@code acceptDuel}）房间尚无当前词，{@link DuelRoom#getCurrentWord()}
     * 为 null，等价于不排除 —— 直接走无参版本即可。
     */
    private Word getRandomWord(DuelRoom room) {
        Word current = room == null ? null : room.getCurrentWord();
        return current == null ? getRandomWord() : getRandomWordExcluding(current.getEnglish());
    }

    private Word getRandomWord() {
        return getRandomWordExcluding(null);
    }

    /**
     * 抽词核心（R14 新增 {@code excludeEnglish} 形参；内部名与 {@link #getRandomWord()} /
     * {@link #getRandomWord(DuelRoom)} 区分开，避免 {@code getRandomWord(null)} 在
     * {@code DuelRoom} / {@code String} 两个重载间产生歧义调用）。
     *
     * @param excludeEnglish 需排除的英文词形（{@code null} = 不排除）。按英文而非
     *                       {@link Word} 实例传递：{@code Word.equals} 已收窄为
     *                       english + part_of_speech，同英文词的全部分支都会被过滤，
     *                       而这里只需「不要刚答过的那个词形」。
     */
    private Word getRandomWordExcluding(String excludeEnglish) {
        // Pick a random word bank and a random word from it
        Set<String> difficulties = plugin.getWordManager().getAvailableDifficulties();
        if (difficulties.isEmpty()) return null;

        // Pick a random difficulty
        List<String> diffList = new ArrayList<>(difficulties);
        Collections.shuffle(diffList);
        String difficulty = diffList.get(0);

        var bank = plugin.getWordManager().getWordBank(difficulty);
        if (bank == null || bank.getWordCount() == 0) return null;

        // Get a random word. WordBank 的 exclude 是 Word 实例语义（{@link Word#equals}：
        // english + part_of_speech），故先按英文名在库里定位出该词对象；同英文不同词性
        // 的另一条词条不在 equals 过滤范围内，故再取 2 个候选、优先挑英文词形不等于
        // 上一题的那个（两个都同形才会命中，说明该库实际只有这一个词形）。
        // 注：本方法每次随机挑一个难度库，上一题可能来自别的库 —— 此时 bank.getWord
        // 返回 null（不在本库），排除自动失效，也从侧面说明本库抽不到那个词条。
        Word exclude = null;
        if (excludeEnglish != null && !excludeEnglish.isEmpty()) {
            exclude = bank.getWord(excludeEnglish);
        }
        List<Word> words = bank.getRandomWords(2, exclude);
        if (words.isEmpty() && exclude != null) {
            // R14 单题库兜底：库里只剩/仅有一个词时，排除会把候选池清空 ——
            // 那不该被当成「抽不到词」而 cancelDuelInternal(no-words) 取消对局
            // （单题库本身仍应能打完），故放弃排除再抽一次。
            words = bank.getRandomWords(1, null);
        }
        if (words.isEmpty()) return null;
        for (Word candidate : words) {
            if (excludeEnglish == null || !excludeEnglish.equalsIgnoreCase(candidate.getEnglish())) {
                return candidate;
            }
        }
        return words.get(0);
    }

    // ==================== Query Methods ====================

    public boolean isInDuel(UUID playerId) {
        return isActiveDuel(playerId);
    }

    /** R5-07-F4：成员表 ≠ 进行中。{@code endDuel} 先置 active=false，摘键留到 finally
     * 的 {@code cleanup}；只查 containsKey 的窗口内异步聊天会把正常聊天吞成答案。 */
    private boolean isActiveDuel(UUID playerId) {
        DuelRoom room = activeDuels.get(playerId);
        return room != null && room.isActive();
    }

    /**
     * R-73 semantics: true while the player is in an ACTIVE duel <b>or</b> its
     * teardown window ({@code endDuel} clears {@code active} before {@code cleanup}
     * blind-removes both keys). Deliberately a <b>superset</b> of "active"
     * (R5-07-F4): returning false there would let a challenge be accepted, and the
     * pending {@code cleanup} would then remove the <i>new</i> room's keys, stranding
     * it. {@link #isInDuel} is the narrower "may chat input be consumed" question.
     *
     * <p>A player merely holding a pending invitation — as sender or as
     * target — is NOT busy: they can still be challenged by a third party,
     * and sending a new request simply replaces their previous outstanding
     * invitation (at most one invitation per sender, at most one standing
     * invitation per target). Consent is enforced at accept time instead:
     * {@link #acceptDuel} re-validates both sides (busy / answering) before
     * the duel starts, so a stale invitation can never start a duel against a
     * player who has since become busy.
     */
    public boolean isBusy(UUID playerId) {
        return activeDuels.containsKey(playerId);
    }

    /**
     * M4: true if the player is currently in any answering state
     * (active quiz session or wrong-book practice). Reads only
     * WordLearningManager's existing state — no cross-module writes.
     */
    private boolean isAnswering(UUID playerId) {
        var wlManager = plugin.getWordLearningManager();
        if (wlManager == null) return false;
        return wlManager.hasActiveQuiz(playerId) || wlManager.isPracticingWord(playerId);
    }

    // ==================== i18n Helpers ====================

    private String getMsg(Player player, String key, String... placeholders) {
        return plugin.getMessageUtil().getPlayerMessage(player, key, placeholders);
    }
}
