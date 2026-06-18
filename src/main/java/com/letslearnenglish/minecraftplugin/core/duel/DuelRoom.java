package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.core.word.Word;

import java.util.UUID;

/**
 * DuelRoom — in-memory state machine for a 1v1 word duel.
 *
 * Two players compete to be the first to answer each word correctly.
 * First to 5 points wins. Thread-safe for concurrent event handler access.
 */
public class DuelRoom {

    public static final int WIN_SCORE = 5;

    private final UUID player1;
    private final UUID player2;
    private final String player1Name;
    private final String player2Name;
    private volatile int score1;
    private volatile int score2;
    private volatile Word currentWord;
    private volatile UUID lastCorrectAnswerer;
    private volatile long wordStartTime;
    private volatile boolean active;

    public enum AnswerResult {
        CORRECT,
        WRONG,
        ALREADY_ANSWERED,
        NOT_IN_DUEL
    }

    public DuelRoom(UUID player1, String player1Name, UUID player2, String player2Name) {
        this.player1 = player1;
        this.player2 = player2;
        this.player1Name = player1Name;
        this.player2Name = player2Name;
        this.score1 = 0;
        this.score2 = 0;
        this.currentWord = null;
        this.lastCorrectAnswerer = null;
        this.wordStartTime = 0;
        this.active = true;
    }

    /**
     * Record the current word for this round.
     */
    public synchronized void setCurrentWord(Word word) {
        this.currentWord = word;
        this.lastCorrectAnswerer = null;
        this.wordStartTime = System.currentTimeMillis();
    }

    /**
     * Submit an answer from a player.
     * Only one player can score per word (first correct wins).
     */
    public synchronized AnswerResult submitAnswer(UUID playerId, String answer) {
        if (!active || currentWord == null) {
            return AnswerResult.NOT_IN_DUEL;
        }

        if (!playerId.equals(player1) && !playerId.equals(player2)) {
            return AnswerResult.NOT_IN_DUEL;
        }

        // If someone already answered this word correctly, reject
        if (lastCorrectAnswerer != null) {
            return AnswerResult.ALREADY_ANSWERED;
        }

        boolean correct = currentWord.getEnglish().equalsIgnoreCase(answer.trim());

        if (correct) {
            lastCorrectAnswerer = playerId;
            if (playerId.equals(player1)) {
                score1++;
            } else {
                score2++;
            }
            return AnswerResult.CORRECT;
        } else {
            // Wrong answer: the OTHER player gets the point (punish wrong answers)
            lastCorrectAnswerer = playerId; // mark as "answered" to prevent double-scoring
            if (playerId.equals(player1)) {
                score2++;
            } else {
                score1++;
            }
            return AnswerResult.WRONG;
        }
    }

    /**
     * Check if the duel is finished (someone reached WIN_SCORE).
     */
    public boolean isFinished() {
        return score1 >= WIN_SCORE || score2 >= WIN_SCORE;
    }

    /**
     * Get the winner's UUID, or null if not finished.
     */
    public UUID getWinner() {
        if (!isFinished()) return null;
        return score1 >= WIN_SCORE ? player1 : player2;
    }

    /**
     * Get the loser's UUID, or null if not finished.
     */
    public UUID getLoser() {
        if (!isFinished()) return null;
        return score1 >= WIN_SCORE ? player2 : player1;
    }

    /**
     * Get the opponent of a given player.
     */
    public UUID getOpponent(UUID playerId) {
        if (playerId.equals(player1)) return player2;
        if (playerId.equals(player2)) return player1;
        return null;
    }

    /**
     * Check if a player is in this duel.
     */
    public boolean containsPlayer(UUID playerId) {
        return playerId.equals(player1) || playerId.equals(player2);
    }

    public synchronized void setActive(boolean active) {
        this.active = active;
    }

    // --- Getters ---

    public UUID getPlayer1() { return player1; }
    public UUID getPlayer2() { return player2; }
    public String getPlayer1Name() { return player1Name; }
    public String getPlayer2Name() { return player2Name; }
    public int getScore1() { return score1; }
    public int getScore2() { return score2; }
    public int getScore(UUID playerId) {
        if (playerId.equals(player1)) return score1;
        if (playerId.equals(player2)) return score2;
        return 0;
    }
    public Word getCurrentWord() { return currentWord; }
    public UUID getLastCorrectAnswerer() { return lastCorrectAnswerer; }
    public long getWordStartTime() { return wordStartTime; }
    public boolean isActive() { return active; }
}
