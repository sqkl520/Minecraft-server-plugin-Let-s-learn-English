package com.letslearnenglish.minecraftplugin.data;

/**
 * CheckIn Data
 *
 * Simple data class holding a player's daily check-in information:
 * last check-in date (yyyy-MM-dd) and current streak count.
 */
public class CheckInData {

    private final String lastCheckinDate;
    private final int streakCount;

    public CheckInData(String lastCheckinDate, int streakCount) {
        this.lastCheckinDate = lastCheckinDate;
        this.streakCount = streakCount;
    }

    public String getLastCheckinDate() {
        return lastCheckinDate;
    }

    public int getStreakCount() {
        return streakCount;
    }

    @Override
    public String toString() {
        return "CheckInData{lastCheckinDate='" + lastCheckinDate + "', streakCount=" + streakCount + "}";
    }
}
