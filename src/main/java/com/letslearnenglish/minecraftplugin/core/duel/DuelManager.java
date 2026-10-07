package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.SoundManager;
import com.letslearnenglish.minecraftplugin.core.progress.RewardEngine;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.gui.DuelGUI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

        // Auto-expire pending request after 60 seconds
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            UUID senderId = pendingRequests.remove(target.getUniqueId());
            if (senderId != null) {
                Player s = Bukkit.getPlayer(senderId);
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
     */
    public void acceptDuel(Player player) {
        UUID targetId = player.getUniqueId();
        UUID senderId = pendingRequests.remove(targetId);

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
                // Silently ignore - someone already answered this word
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
            refreshBothGUIs(room);
        }
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
                graceTasks.remove(playerId);
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

    private void cancelDuelInternal(DuelRoom room, String reason) {
        room.setActive(false);
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

    private void refreshBothGUIs(DuelRoom room) {
        Player p1 = Bukkit.getPlayer(room.getPlayer1());
        Player p2 = Bukkit.getPlayer(room.getPlayer2());
        if (p1 != null && p1.isOnline()) {
            new DuelGUI(plugin).open(p1, room);
        }
        if (p2 != null && p2.isOnline()) {
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
