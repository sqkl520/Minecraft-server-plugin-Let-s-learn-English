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

    /**
     * FIX-DUEL-01: single-question timeout fallback (seconds).
     * Prefer the existing {@code word-system.answer-timeout} config (owned by
     * WordLearningManager); this constant is only the fallback default and is
     * NOT a new config key.
     */
    public static final int WORD_TIMEOUT_SECONDS = 30;

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
    // Monotonic round id: incremented on every setCurrentWord so the timeout
    // task can tell a stale firing (old round) from the current one.
    private volatile long wordRoundId;

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
        this.wordRoundId = 0;
        this.active = true;
    }

    /**
     * Record the current word for this round.
     *
     * @return the new round id, used by the manager to detect stale timeouts
     */
    public synchronized long setCurrentWord(Word word) {
        this.currentWord = word;
        this.lastCorrectAnswerer = null;
        this.wordStartTime = System.currentTimeMillis();
        this.wordRoundId++;
        return this.wordRoundId;
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
     * FIX-DUEL-01: single-question timeout.
     *
     * <p>Semantics (symmetric by design): if nobody answered the current word
     * in time, NOBODY scores — no penalty, no free point for either side
     * (unlike a wrong answer, which deliberately feeds the opponent). A
     * dropped/disconnected player can therefore never cost their opponent a
     * point via timeout. The manager then advances to the next word.
     *
     * @param expectedRound the round id captured when the timeout was scheduled
     * @return true if this round actually timed out (still unanswered and
     *         still the current round); false if the round was already
     *         answered, advanced, or the duel ended (stale firing)
     */
    public synchronized boolean timeoutCurrentWord(long expectedRound) {
        if (!active || currentWord == null) {
            return false;
        }
        if (expectedRound != wordRoundId) {
            return false; // stale: a newer word was already dealt
        }
        if (lastCorrectAnswerer != null) {
            return false; // already answered — timeout loses the race
        }
        // Seal the round so late chat answers are rejected as ALREADY_ANSWERED.
        // A random UUID can never equal a real player id; getCurrentWord() is
        // intentionally left intact so the timeout reveal can show the answer.
        lastCorrectAnswerer = new UUID(0L, 0L);
        return true;
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
    public long getWordRoundId() { return wordRoundId; }
    public boolean isActive() { return active; }
}
