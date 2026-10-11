package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.gui.DuelGUI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private final RewardEngine rewardEngine;

    // player UUID → DuelRoom they are currently in
    private final Map<UUID, DuelRoom> activeDuels;
    // Players who are waiting for or currently in a duel
    private final Set<UUID> busyPlayers;
    // Pending duel requests: target UUID → sender UUID
    private final Map<UUID, UUID> pendingRequests;
    // Disconnect grace timers: disconnected player UUID → (duelRoom, disconnectTimestamp)
    private final Map<UUID, DisconnectInfo> disconnectTimers;
    // Grace period countdown tasks: disconnected player UUID → task
    private final Map<UUID, BukkitTask> graceTasks;
    // Single-question timeout tasks: player1 UUID → task (one entry per room)
    private final Map<UUID, BukkitTask> wordTimeoutTasks;

    private static class DisconnectInfo {
        final DuelRoom room;
        final long disconnectTime;
        DisconnectInfo(DuelRoom room, long disconnectTime) {
            this.room = room;
            this.disconnectTime = disconnectTime;
        }
    }

    public DuelManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.rewardEngine = plugin.getProgressManager().getRewardEngine();
        this.activeDuels = new ConcurrentHashMap<>();
        this.busyPlayers = ConcurrentHashMap.newKeySet();
        this.pendingRequests = new ConcurrentHashMap<>();
        this.disconnectTimers = new ConcurrentHashMap<>();
        this.graceTasks = new ConcurrentHashMap<>();
        this.wordTimeoutTasks = new ConcurrentHashMap<>();
    }

    // ==================== Request / Accept ====================

    /**
     * Send a duel request from sender to target.
     */
    public void sendDuelRequest(Player sender, Player target) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(getMsg(sender, "duel.cannot-challenge-self"));
            return;
        }

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

        // Clear any old pending request from sender
        pendingRequests.values().removeIf(v -> v.equals(sender.getUniqueId()));

        pendingRequests.put(target.getUniqueId(), sender.getUniqueId());

        // Message to sender
        sender.sendMessage(getMsg(sender, "duel.request-sent", "player", target.getName()));

        // Message to target with clickable accept
        String acceptMsg = getMsg(target, "duel.request-received", "player", sender.getName());
        // Make it clickable by suggesting /le duel accept
        String clickableAccept = ChatColor.translateAlternateColorCodes('&',
                getRawMsg(target, "duel.accept-hint"));
        target.sendMessage(acceptMsg);
        target.sendMessage(clickableAccept);

        // Auto-expire pending request after 60 seconds. The remove(key, value) form
        // ensures a stale expiry task never deletes a NEWER invitation sent to the
        // same target within the 60s window (same fix as acceptDuel's F1-15).
        UUID expectedSender = sender.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            boolean expired = pendingRequests.remove(target.getUniqueId(), expectedSender);
            if (expired) {
                Player s = Bukkit.getPlayer(expectedSender);
                if (s != null && s.isOnline()) {
                    s.sendMessage(getMsg(s, "duel.request-expired", "player", target.getName()));
                }
                Player t = Bukkit.getPlayer(target.getUniqueId());
                if (t != null && t.isOnline()) {
                    t.sendMessage(getMsg(t, "duel.request-expired-target", "player", sender.getName()));
                }
            }
        }, 1200L); // 60 seconds = 1200 ticks
    }

    /**
     * Accept a pending duel request.
     *
     * <p>F1-15: the invitation is consumed (removed from pendingRequests) only
     * AFTER every validation passes — a failed accept (offline sender, busy
     * side, answering state) no longer silently eats the request, so the
     * target can retry without asking the sender to re-invite.
     */
    public void acceptDuel(Player player) {
        UUID targetId = player.getUniqueId();
        // F1-15: peek first, remove only once all validations pass.
        UUID senderId = pendingRequests.get(targetId);

        if (senderId == null) {
            player.sendMessage(getMsg(player, "duel.no-pending-request"));
            return;
        }

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

        // All validations passed — consume the invitation. The remove(key, value)
        // form guards the (unlikely) case where a newer request replaced this one.
        if (!pendingRequests.remove(targetId, senderId)) {
            player.sendMessage(getMsg(player, "duel.no-pending-request"));
            return;
        }

        // Mark both as busy
        busyPlayers.add(senderId);
        busyPlayers.add(targetId);

        // Create the duel room
        DuelRoom room = new DuelRoom(senderId, sender.getName(), targetId, player.getName());
        activeDuels.put(senderId, room);
        activeDuels.put(targetId, room);

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
        if (room == null || !room.isActive()) return;

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
                // Should not happen
            }
        }
    }

    private void advanceOrEnd(DuelRoom room) {
        if (room.isFinished()) {
            endDuel(room);
        } else {
            Word nextWord = getRandomWord();
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
     * Schedule the timeout for the room's current word. Any previous timeout
     * for this room is cancelled first (belt-and-suspenders — normally the
     * old task was already cancelled when the previous word was answered).
     */
    private void scheduleWordTimeout(DuelRoom room) {
        if (!room.isActive()) return;
        cancelWordTimeout(room);
        long round = room.getWordRoundId();
        int seconds = getWordTimeoutSeconds();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            handleWordTimeout(room, round);
        }, seconds * 20L);
        wordTimeoutTasks.put(room.getPlayer1(), task);
    }

    /**
     * Cancel the pending single-question timeout for a room, if any.
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
     */
    private void handleWordTimeout(DuelRoom room, long round) {
        wordTimeoutTasks.remove(room.getPlayer1());
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
            SoundManager.playSound(p1, "incorrect");
        }
        if (p2 != null && p2.isOnline()) {
            p2.sendMessage(getMsg(p2, "duel.word-timeout", "word", answer));
            SoundManager.playSound(p2, "incorrect");
        }

        // Timeout never scores, so the duel cannot finish here — advance.
        Word nextWord = getRandomWord();
        if (nextWord == null) {
            cancelDuelInternal(room, "no-words");
            return;
        }
        room.setCurrentWord(nextWord);
        scheduleWordTimeout(room);
        updateBothScoreboards(room);
        refreshBothGUIs(room);
    }

    // ==================== Duel End ====================

    /**
     * End a duel normally (someone won).
     */
    public void endDuel(DuelRoom room) {
        if (!room.isActive()) return;
        room.setActive(false);

        UUID winnerId = room.getWinner();
        UUID loserId = room.getLoser();

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
        }

        cleanup(room);
    }

    /**
     * Quit a duel — forfeit.
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
        }

        removeScoreboard(player);
        player.closeInventory();

        room.setActive(false);
        cleanup(room);
    }

    // ==================== Disconnect Handling ====================

    /**
     * Called when a player disconnects during a duel.
     */
    public void handleDisconnect(Player player) {
        UUID playerId = player.getUniqueId();
        DuelRoom room = activeDuels.get(playerId);
        if (room == null || !room.isActive()) return;

        disconnectTimers.put(playerId, new DisconnectInfo(room, System.currentTimeMillis()));

        UUID opponentId = room.getOpponent(playerId);
        Player opponent = Bukkit.getPlayer(opponentId);
        if (opponent != null && opponent.isOnline()) {
            opponent.sendMessage(getMsg(opponent, "duel.opponent-disconnected",
                    "player", player.getName(),
                    "seconds", String.valueOf(GRACE_PERIOD_SECONDS)));
        }

        // Start grace period countdown
        int[] remainingSeconds = {GRACE_PERIOD_SECONDS};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds[0]--;

            // Check if player reconnected
            if (!disconnectTimers.containsKey(playerId)) {
                return; // timer will be cancelled below
            }

            DuelRoom currentRoom = activeDuels.get(opponentId);
            if (currentRoom == null || !currentRoom.isActive()) {
                // R-26: 对局已无 —— 停掉本宽限任务，不再每秒空转。
                BukkitTask self = graceTasks.remove(playerId);
                if (self != null) {
                    self.cancel();
                }
                disconnectTimers.remove(playerId);
                return;
            }

            Player opp = Bukkit.getPlayer(opponentId);
            if (remainingSeconds[0] <= 0) {
                // Grace period expired — forfeit
                graceTasks.remove(playerId);
                disconnectTimers.remove(playerId);

                if (opp != null && opp.isOnline()) {
                    opp.sendMessage(getMsg(opp, "duel.opponent-timeout",
                            "player", player.getName()));
                    removeScoreboard(opp);
                    opp.closeInventory();
                    SoundManager.playSound(opp, "complete");
                }

                room.setActive(false);
                cleanup(room);
            } else if (remainingSeconds[0] <= 5 || remainingSeconds[0] % 10 == 0) {
                // Notify remaining player
                if (opp != null && opp.isOnline()) {
                    opp.sendMessage(getMsg(opp, "duel.grace-countdown",
                            "seconds", String.valueOf(remainingSeconds[0]),
                            "player", player.getName()));
                }
            }

            // Update sidebar scoreboard with countdown
            if (opp != null && opp.isOnline()) {
                updateScoreboardWithGrace(opp, room, remainingSeconds[0]);
            }
        }, 20L, 20L); // every second

        graceTasks.put(playerId, task);
    }

    /**
     * Called when a player rejoins after disconnecting during a duel.
     */
    public void handleReconnect(Player player) {
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
            player.sendMessage(getMsg(player, "duel.duel-already-over"));
            return;
        }

        UUID opponentId = room.getOpponent(playerId);
        Player opponent = Bukkit.getPlayer(opponentId);

        player.sendMessage(getMsg(player, "duel.reconnected"));

        if (opponent != null && opponent.isOnline()) {
            opponent.sendMessage(getMsg(opponent, "duel.opponent-reconnected",
                    "player", player.getName()));
        }

        // Re-open GUI and scoreboard
        new DuelGUI(plugin).open(player, room);
        setupScoreboard(player, room);

        // Re-setup opponent's scoreboard (remove grace timer)
        if (opponent != null && opponent.isOnline()) {
            setupScoreboard(opponent, room);
        }
    }

    // ==================== Cancel / Cleanup ====================

    /**
     * Cancel an active duel by player UUID (used by quit/timeout/forfeit).
     */
    public void cancelDuel(UUID playerId) {
        DuelRoom room = activeDuels.get(playerId);
        if (room == null) return;

        UUID opponentId = room.getOpponent(playerId);
        room.setActive(false);
        cancelWordTimeout(room);

        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null) {
            removeScoreboard(p1);
            p1.closeInventory();
        }
        if (p2 != null) {
            removeScoreboard(p2);
            p2.closeInventory();
        }

        cleanup(room);
    }

    /**
     * FIX-DUEL-01: GM-forced duel dismissal.
     *
     * <p>Semantics: NO winner/loser is declared — the room is torn down as if
     * cancelled. Both sides are told a GM ended the duel; no forfeit stigma
     * is attached to either player. Does not touch the M4 answering gate or
     * any pending invitations (GM dismissal only targets an ACTIVE room).
     *
     * @param gm the admin performing the dismissal (receives confirmation)
     * @param targetName name of one of the two duelists
     */
    public void cancelDuelByAdmin(CommandSender gm, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            gm.sendMessage(plugin.getMessageUtil().getSenderMessage(gm,
                    "admin.duel-cancel-offline", "player", targetName));
            return;
        }

        DuelRoom room = activeDuels.get(target.getUniqueId());
        if (room == null || !room.isActive()) {
            gm.sendMessage(plugin.getMessageUtil().getSenderMessage(gm,
                    "admin.duel-cancel-not-in-duel", "player", target.getName()));
            return;
        }

        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null && p1.isOnline()) {
            p1.sendMessage(getMsg(p1, "duel.cancelled-by-gm"));
            removeScoreboard(p1);
            p1.closeInventory();
        }
        if (p2 != null && p2.isOnline()) {
            p2.sendMessage(getMsg(p2, "duel.cancelled-by-gm"));
            removeScoreboard(p2);
            p2.closeInventory();
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

    private void cancelDuelInternal(DuelRoom room, String reason) {
        room.setActive(false);
        cancelWordTimeout(room);
        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null && p1.isOnline()) {
            p1.sendMessage(getMsg(p1, "duel.cancelled"));
            removeScoreboard(p1);
            p1.closeInventory();
        }
        if (p2 != null && p2.isOnline()) {
            p2.sendMessage(getMsg(p2, "duel.cancelled"));
            removeScoreboard(p2);
            p2.closeInventory();
        }
        cleanup(room);
    }

    private void cleanup(DuelRoom room) {
        UUID p1 = room.getPlayer1();
        UUID p2 = room.getPlayer2();

        activeDuels.remove(p1);
        activeDuels.remove(p2);
        busyPlayers.remove(p1);
        busyPlayers.remove(p2);
        disconnectTimers.remove(p1);
        disconnectTimers.remove(p2);

        // Cancel any grace tasks
        BukkitTask t1 = graceTasks.remove(p1);
        if (t1 != null) t1.cancel();
        BukkitTask t2 = graceTasks.remove(p2);
        if (t2 != null) t2.cancel();

        // Cancel the single-question timeout (FIX-DUEL-01)
        BukkitTask wt = wordTimeoutTasks.remove(p1);
        if (wt != null) wt.cancel();
    }

    // ==================== Scoreboard ====================

    private void setupScoreboard(Player player, DuelRoom room) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msgs = plugin.getConfigManager().getMessageConfig(lang);

        String title = ChatColor.translateAlternateColorCodes('&',
                msgs.getString("duel.scoreboard-title"));

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
        String targetLine = msgs.getString("duel.scoreboard-target");
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
                msgs.getString("duel.scoreboard-title-grace")
                        .replace("{seconds}", String.valueOf(remainingSeconds)));
        obj.setDisplayName(title);
    }

    private void removeScoreboard(Player player) {
        if (player.isOnline()) {
            org.bukkit.scoreboard.Scoreboard emptyBoard = Bukkit.getScoreboardManager().getNewScoreboard();
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

    private Word getRandomWord() {
        // Pick a random word bank and a random word from it
        Set<String> difficulties = plugin.getWordManager().getAvailableDifficulties();
        if (difficulties.isEmpty()) return null;

        // Pick a random difficulty
        List<String> diffList = new ArrayList<>(difficulties);
        Collections.shuffle(diffList);
        String difficulty = diffList.get(0);

        var bank = plugin.getWordManager().getWordBank(difficulty);
        if (bank == null || bank.getWordCount() == 0) return null;

        // Get 1 random word
        List<Word> words = bank.getRandomWords(1, null);
        if (words.isEmpty()) return null;

        return words.get(0);
    }

    // ==================== Query Methods ====================

    public boolean isInDuel(UUID playerId) {
        return activeDuels.containsKey(playerId);
    }

    public boolean isBusy(UUID playerId) {
        return busyPlayers.contains(playerId);
    }

    public DuelRoom getDuelRoom(UUID playerId) {
        return activeDuels.get(playerId);
    }

    public boolean hasPendingRequest(UUID targetId) {
        return pendingRequests.containsKey(targetId);
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

    private String getRawMsg(Player player, String key) {
        String lang = plugin.getPlayerLanguage(player);
        FileConfiguration msgs = plugin.getConfigManager().getMessageConfig(lang);
        return msgs.getString(key);
    }
}
