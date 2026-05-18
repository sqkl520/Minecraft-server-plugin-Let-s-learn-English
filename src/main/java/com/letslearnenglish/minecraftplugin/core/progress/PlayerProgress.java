package com.letslearnenglish.minecraftplugin.core.progress;

import java.util.UUID;

/**
 * Player Progress - represents a player's learning progress summary.
 */
public class PlayerProgress {

    private final UUID playerId;
    private int totalWordsLearned;
    private int totalSessions;
    private long totalScore;
    private int currentStreak;
    private int longestStreak;

    public PlayerProgress(UUID playerId) {
        this.playerId = playerId;
        this.totalWordsLearned = 0;
        this.totalSessions = 0;
        this.totalScore = 0;
        this.currentStreak = 0;
        this.longestStreak = 0;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public int getTotalWordsLearned() {
        return totalWordsLearned;
    }

    public void setTotalWordsLearned(int totalWordsLearned) {
        this.totalWordsLearned = totalWordsLearned;
    }

    public int getTotalSessions() {
        return totalSessions;
    }

    public void setTotalSessions(int totalSessions) {
        this.totalSessions = totalSessions;
    }

    public long getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(long totalScore) {
        this.totalScore = totalScore;
    }

    public int getCurrentStreak() {
        return currentStreak;
    }

    public void setCurrentStreak(int currentStreak) {
        this.currentStreak = currentStreak;
    }

    public int getLongestStreak() {
        return longestStreak;
    }

    public void setLongestStreak(int longestStreak) {
        this.longestStreak = longestStreak;
    }
}