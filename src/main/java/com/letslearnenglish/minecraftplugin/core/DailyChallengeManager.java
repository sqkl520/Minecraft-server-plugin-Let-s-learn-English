package com.letslearnenglish.minecraftplugin.core;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.core.word.Word;
import com.letslearnenglish.minecraftplugin.core.word.WordBank;
import com.letslearnenglish.minecraftplugin.core.word.WordManagerImpl;
import org.bukkit.entity.Player;

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
 * Two challenge systems:
 * 1. Behavioral challenges (ChallengeType enum) — task-based, e.g. "answer 10 words"
 * 2. Word challenges — N random words selected from WordBank each day
 *
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

    /**
     * Word challenge progress info.
     */
    public static class WordChallengeProgress {
        public final String wordId;
        public final boolean completed;

        public WordChallengeProgress(String wordId, boolean completed) {
            this.wordId = wordId;
            this.completed = completed;
        }
    }

    private final LetsLearnEnglish plugin;
    private final WordManagerImpl wordManager;
    private final Set<UUID> wordChallengeClaimedToday;

    public DailyChallengeManager(LetsLearnEnglish plugin, WordManagerImpl wordManager) {
        this.plugin = plugin;
        this.wordManager = wordManager;
        this.wordChallengeClaimedToday = new HashSet<>();
    }

    // ============================================================
    //  Behavioral Challenges (existing)
    // ============================================================

    /**
     * Check if the player has challenges for today. If not, assign 3 random ones.
     */
    public void checkAndAssign(UUID playerId) {
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String checkSql = "SELECT COUNT(*) FROM " + tablePrefix
                + "daily_challenges WHERE uuid = ? AND challenge_date = ?";

        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    // Already assigned — but also ensure word challenges are assigned
                    ensureWordChallengesAssigned(playerId);
                    return;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check daily challenges for " + playerId, e);
            return;
        }
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

        Connection insertConn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = insertConn.prepareStatement(insertSql)) {
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

        // Also assign word challenges
        ensureWordChallengesAssigned(playerId);
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

        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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

        String selectSql = "SELECT progress, completed, claimed FROM " + tablePrefix
                + "daily_challenges WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?";
        int currentProgress = 0;
        boolean alreadyCompleted = false;
        boolean alreadyClaimed = false;

        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            stmt.setString(3, type.id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    currentProgress = rs.getInt("progress");
                    alreadyCompleted = rs.getBoolean("completed");
                    alreadyClaimed = rs.getBoolean("claimed");
                } else {
                    return;
                }
            }
        } catch (SQLException e) {
            return;
        }
        }

        if (alreadyClaimed) return;

        int newProgress = currentProgress + increment;
        boolean nowCompleted = newProgress >= type.target;
        if (alreadyCompleted) nowCompleted = true;

        String updateSql = "UPDATE " + tablePrefix
                + "daily_challenges SET progress = ?, completed = ? WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?";
        Connection updateConn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = updateConn.prepareStatement(updateSql)) {
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
    }

    /**
     * Set a challenge as completed (for binary challenges like combo).
     */
    public void completeChallenge(UUID playerId, ChallengeType type) {
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String updateSql = "UPDATE " + tablePrefix
                + "daily_challenges SET completed = 1 WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? AND claimed = 0";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, today);
            stmt.setString(3, type.id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to complete daily challenge", e);
        }
        }
    }

    /**
     * Claim reward for a completed challenge.
     *
     * <p>M12: deposit-then-mark. Callers must transfer the Vault reward FIRST
     * and only call this method to finalize AFTER the deposit succeeds, so a
     * failed deposit leaves the reward re-claimable. The guarded UPDATE keeps
     * this idempotent: double-claims mark at most one row.
     *
     * @param playerId the player
     * @param type the challenge type
     * @return the reward amount if marked claimed, or 0 if not eligible
     */
    public double claimReward(UUID playerId, ChallengeType type) {
        return markClaimed(playerId, type);
    }

    /**
     * Mark a completed challenge as claimed. Must only be called AFTER a
     * successful economy transfer. The guarded UPDATE keeps this idempotent:
     * concurrent double-claims mark at most one row.
     *
     * @param playerId the player
     * @param type the challenge type
     * @return the reward amount if the row was marked, or 0 otherwise
     */
    private double markClaimed(UUID playerId, ChallengeType type) {
        String today = LocalDate.now().toString();
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String updateSql = "UPDATE " + tablePrefix
                + "daily_challenges SET claimed = 1 WHERE uuid = ? AND challenge_date = ? AND challenge_id = ? AND completed = 1 AND claimed = 0";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
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

    // ============================================================
    //  Word-Bank-Based Daily Challenges (NEW)
    // ============================================================

    /**
     * Get the configured word count for daily word challenges.
     */
    private int getWordCountConfig() {
        return plugin.getConfigManager().getMainConfig().getInt("daily-challenges.word-count", 5);
    }

    /**
     * Get the reward for completing all daily word challenges.
     */
    private double getWordChallengeReward() {
        return plugin.getConfigManager().getMainConfig().getDouble("daily-challenges.word-challenge-reward", 100.0);
    }

    /**
     * Ensure the player has word challenges assigned for today.
     * Called on player join and when checking behavioral challenges.
     */
    private void ensureWordChallengesAssigned(UUID playerId) {
        String today = LocalDate.now().toString();
        List<String> existing = plugin.getDatabaseManager().getDailyChallengeWords(playerId, today);
        if (!existing.isEmpty()) return;

        // Needs assignment — pick random words from WordBank
        int wordCount = getWordCountConfig();
        List<Word> allWords = new ArrayList<>();
        for (String difficulty : wordManager.getAvailableDifficulties()) {
            WordBank bank = wordManager.getWordBank(difficulty);
            if (bank != null) {
                allWords.addAll(bank.getAllWords());
            }
        }

        if (allWords.isEmpty()) return;

        // Shuffle and pick up to wordCount
        Collections.shuffle(allWords, ThreadLocalRandom.current());
        int count = Math.min(wordCount, allWords.size());
        List<String> selectedIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            selectedIds.add(allWords.get(i).getEnglish().toLowerCase());
        }

        plugin.getDatabaseManager().assignDailyChallengeWords(playerId, today, selectedIds);
        plugin.getLogger().info("Assigned " + selectedIds.size()
                + " daily challenge words to player " + playerId);
    }

    /**
     * Called when a player answers a word correctly during learning.
     * Checks if the word is part of today's challenge and marks it complete.
     *
     * <p>M12 retry hook: if all words are done but the reward payout previously
     * failed (claimed flag not set), retry the payout on any further correct
     * answer — even non-challenge words or already-completed words.
     */
    public void onWordCorrect(Player player, Word word) {
        UUID playerId = player.getUniqueId();
        String today = LocalDate.now().toString();
        String wordId = word.getEnglish().toLowerCase();

        // M12: pending-payout retry — all words done but money never arrived.
        // Must run before the challenge-words membership check: retry answers may
        // be outside today's challenge set or already-completed words.
        if (hasPendingWordReward(playerId, today)) {
            onAllWordChallengesComplete(player);
            return;
        }

        // Check if this word is assigned as today's challenge
        List<String> challengeWords = plugin.getDatabaseManager().getDailyChallengeWords(playerId, today);
        if (!challengeWords.contains(wordId)) return;

        // Mark it complete in DB (returns false if already completed)
        boolean freshlyCompleted = plugin.getDatabaseManager().markDailyChallengeWordComplete(playerId, today, wordId);

        // Only check all-done if this word was just now completed
        if (freshlyCompleted && plugin.getDatabaseManager().isDailyChallengeWordsCompleted(playerId, today)) {
            onAllWordChallengesComplete(player);
        }
    }

    /**
     * Check whether the player has finished all of today's word challenges but
     * the reward payout has not landed yet (claimed flag not set).
     *
     * @param playerId the player
     * @param today today's date string (yyyy-MM-dd)
     * @return true if payout is pending and should be retried
     */
    private boolean hasPendingWordReward(UUID playerId, String today) {
        if (wordChallengeClaimedToday.contains(playerId)) {
            return false;
        }
        return plugin.getDatabaseManager().isDailyChallengeWordsCompleted(playerId, today);
    }

    /**
     * Called when all word challenges for today are completed.
     * Opens the celebration GUI and gives reward.
     *
     * <p>M12: deposit-then-mark. The claimed flag is only set AFTER the Vault
     * transfer succeeds. If the deposit fails, the celebration message/GUI are
     * withheld, the player gets a retry hint, and the next {@link #onWordCorrect}
     * invocation (any correct answer) will retry the payout — so the reward is
     * never lost as long as the player keeps studying.
     */
    private void onAllWordChallengesComplete(Player player) {
        UUID playerId = player.getUniqueId();
        double reward = getWordChallengeReward();

        if (!depositWordChallengeReward(player, playerId, reward)) {
            // Deposit failed — do NOT mark claimed. Tell the player how to retry.
            player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player,
                    "daily-challenge.reward-pending", "reward", String.valueOf((int) reward)));
            return;
        }

        // Success: mark claimed only after money arrived.
        wordChallengeClaimedToday.add(playerId);

        // Send celebration message
        String lang = plugin.getPlayerLanguage(player);
        var msgs = plugin.getConfigManager().getMessageConfig(lang);
        String msg = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                msgs.getString("daily-challenge.all-complete")
                        .replace("{reward}", String.valueOf((int) reward)));
        player.sendMessage(msg);

        // Open celebration GUI
        new com.letslearnenglish.minecraftplugin.gui.ChallengeCompleteGUI(plugin).open(player, reward);
    }

    /**
     * Deposit the word-challenge reward via Vault.
     *
     * @param player the player
     * @param playerId the player UUID (for stats bookkeeping)
     * @param reward the reward amount
     * @return true only if money actually arrived (Vault present + transaction success);
     *         stats are recorded only in that case
     */
    private boolean depositWordChallengeReward(Player player, UUID playerId, double reward) {
        var eco = plugin.getEconomy();
        if (eco == null) {
            plugin.getLogger().warning("Word challenge reward of " + reward
                    + " for " + player.getName() + " held: Vault economy unavailable");
            return false;
        }
        var response = eco.depositPlayer(player, reward);
        if (response.transactionSuccess()) {
            plugin.getDatabaseManager().recordCoinsEarned(playerId, reward);
            return true;
        }
        plugin.getLogger().warning("Word challenge reward deposit failed for "
                + player.getName() + ": " + response.errorMessage);
        return false;
    }

    /**
     * Get today's word challenge progress for a player.
     * Returns int[]{completed, total}.
     */
    public int[] getTodayWordProgress(Player player) {
        String today = LocalDate.now().toString();
        ensureWordChallengesAssigned(player.getUniqueId());
        return plugin.getDatabaseManager().getDailyChallengeWordsProgress(player.getUniqueId(), today);
    }

    /**
     * Get today's word challenge details (words and completion status).
     */
    public List<WordChallengeProgress> getTodayWordDetails(Player player) {
        String today = LocalDate.now().toString();
        UUID playerId = player.getUniqueId();
        ensureWordChallengesAssigned(playerId);

        List<String> wordIds = plugin.getDatabaseManager().getDailyChallengeWords(playerId, today);
        List<WordChallengeProgress> result = new ArrayList<>();

        for (String wordId : wordIds) {
            boolean wordCompleted = checkSingleWordCompleted(playerId, today, wordId);
            result.add(new WordChallengeProgress(wordId, wordCompleted));
        }
        return result;
    }

    private boolean checkSingleWordCompleted(UUID playerId, String date, String wordId) {
        String tablePrefix = plugin.getDatabaseManager().getTablePrefix();
        String sql = "SELECT completed FROM " + tablePrefix
                + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ? AND word_id = ?";
        Connection conn = plugin.getDatabaseManager().getConnection();
        synchronized (plugin.getDatabaseManager().getConnectionLock()) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, date);
            stmt.setString(3, wordId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("completed");
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        }
        return false;
    }

    /**
     * Clear claimed state for a player (called at midnight).
     */
    public void clearWordChallengeClaim(UUID playerId) {
        wordChallengeClaimedToday.remove(playerId);
    }
}
