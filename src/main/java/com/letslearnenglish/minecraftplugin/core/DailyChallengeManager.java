package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.data.PlayerDataStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Daily Challenge Manager
 *
 * Each day, every player gets 3 random challenges.
 * Progress is tracked in the database and checked after relevant actions.
 */
public class DailyChallengeManager {

    public enum ChallengeType {
        ANSWER_10("answer_10", "daily.p1", 10, 30.0),
        SESSION_3("session_3", "daily.p2", 3, 50.0),
        COMBO_5("combo_5", "daily.p3", 1, 25.0),
        AI_CHAT_5("ai_chat_5", "daily.p4", 5, 40.0);

        public final String id;
        public final String descKey;
        public final int target;
        public final double reward;

        ChallengeType(String id, String descKey, int target, double reward) {
            this.id = id;
            this.descKey = descKey;
            this.target = target;
            this.reward = reward;
        }

        public static ChallengeType fromId(String id) {
            for (ChallengeType ct : values()) {
                if (ct.id.equals(id)) return ct;
            }
            return null;
        }
    }

    public static class ChallengeProgress {
        public final ChallengeType type;
        public int progress;
        public boolean completed;
        public boolean claimed;

        public ChallengeProgress(ChallengeType type, int progress, boolean completed, boolean claimed) {
            this.type = type;
            this.progress = progress;
            this.completed = completed;
            this.claimed = claimed;
        }

        public boolean isAllDone() { return completed && claimed; }
    }

    private final LetsLearnEnglish plugin;

    public DailyChallengeManager(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    /**
     * Check if the player has challenges for today. If not, assign 3 random ones.
     */
    public void checkAndAssign(UUID playerId) {
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String checkSql = "SELECT COUNT(*) FROM " + tablePrefix
                + "daily_challenges WHERE uuid = ? AND challenge_date = ?";

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return; // Already assigned
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check daily challenges for " + playerId, e);
            return;
        }

        // Assign 3 random challenges (pick 3 from 4 types)
        List<ChallengeType> allTypes = new ArrayList<>(Arrays.asList(ChallengeType.values()));
        Collections.shuffle(allTypes, ThreadLocalRandom.current());
        List<ChallengeType> picked = allTypes.subList(0, 3);

        String insertSql = "INSERT OR REPLACE INTO " + tablePrefix
                + "daily_challenges (uuid, challenge_date, challenge_id, progress, completed, claimed) VALUES (?, ?, ?, 0, 0, 0)";
        if (!plugin.getDatabaseManager().isSQLite()) {
            insertSql = "INSERT INTO " + tablePrefix
                    + "daily_challenges (uuid, challenge_date, challenge_id, progress, completed, claimed) VALUES (?, ?, ?, 0, 0, 0)";
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            for (ChallengeType ct : picked) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, today);
                stmt.setString(3, ct.id);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to assign daily challenges for " + playerId, e);
        }
    }

    /**
     * Get today's challenges for a player.
     */
    public List<ChallengeProgress> getTodayChallenges(UUID playerId) {
        List<ChallengeProgress> list = new ArrayList<>();
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT challenge_id, progress, completed, claimed FROM " + tablePrefix
                + "daily_challenges WHERE uuid = ? AND challenge_date = ?";

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ChallengeType type = ChallengeType.fromId(rs.getString("challenge_id"));
                    if (type != null) {
                        list.add(new ChallengeProgress(type, rs.getInt("progress"),
                                rs.getBoolean("completed"), rs.getBoolean("claimed")));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load daily challenges for " + playerId, e);
        }
        return list;
    }

    /**
     * Check if all today's challenges are completed and claimed.
     */
    public boolean allDoneToday(UUID playerId) {
        List<ChallengeProgress> challenges = getTodayChallenges(playerId);
        if (challenges.isEmpty()) return false;
        return challenges.stream().allMatch(ChallengeProgress::isAllDone);
    }

    /**
     * Update progress for a challenge type. Called from hooks.
     */
    public void updateProgress(UUID playerId, ChallengeType type, int increment) {
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();

        // First get current state
        String selectSql = "SELECT progress, completed, claimed FROM " + tablePrefix
                + "daily_challenges WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?";
        int currentProgress = 0;
        boolean alreadyCompleted = false;
        boolean alreadyClaimed = false;

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            stmt.setString(3, type.id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    currentProgress = rs.getInt("progress");
                    alreadyCompleted = rs.getBoolean("completed");
                    alreadyClaimed = rs.getBoolean("claimed");
                } else {
                    return; // No challenge of this type assigned today
                }
            }
        } catch (SQLException e) {
            return;
        }

        if (alreadyClaimed) return; // Already done

        int newProgress = currentProgress + increment;
        boolean nowCompleted = newProgress >= type.target;
        if (alreadyCompleted) nowCompleted = true; // Don't un-complete

        String updateSql = "UPDATE " + tablePrefix
                + "daily_challenges SET progress = ?, completed = ? WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setInt(1, newProgress);
            stmt.setBoolean(2, nowCompleted);
            stmt.setString(3, playerId.toString());
            stmt.setString(4, today);
            stmt.setString(5, type.id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update daily challenge progress", e);
        }
    }

    /**
     * Set a challenge as completed (for binary challenges like combo).
     */
    public void completeChallenge(UUID playerId, ChallengeType type) {
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String updateSql = "UPDATE " + tablePrefix
                + "daily_challenges SET completed = 1 WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? AND claimed = 0";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            stmt.setString(3, type.id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to complete daily challenge", e);
        }
    }

    /**
     * Claim reward for a completed challenge. Returns the reward amount or 0.
     */
    public double claimReward(UUID playerId, ChallengeType type) {
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String updateSql = "UPDATE " + tablePrefix
                + "daily_challenges SET claimed = 1 WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? AND completed = 1 AND claimed = 0";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            stmt.setString(3, type.id);
            int rows = stmt.executeUpdate();
            return rows > 0 ? type.reward : 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to claim daily challenge reward", e);
            return 0;
        }
    }
}
