package com.letslearnenglish.minecraftplugin.data;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.core.progress.Leaderboard.LeaderboardEntry;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Database Manager
 *
 * Supports SQLite (default) and MySQL storage backends.
 * Handles database connection management, table creation, and migration.
 */
public class DatabaseManager {

    private final LetsLearnEnglish plugin;
    private final ConfigManager configManager;
    private final Object connectionLock = new Object();
    private volatile Connection connection;
    private String tablePrefix;
    private boolean isSQLite;

    public DatabaseManager(LetsLearnEnglish plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * Initialize database connection and table structure.
     */
    public void initialize() {
        FileConfiguration config = configManager.getMainConfig();
        String type = config.getString("database.type", "sqlite");
        this.tablePrefix = config.getString("database.mysql.table-prefix", "lle_");

        try {
            if ("mysql".equalsIgnoreCase(type)) {
                initializeMySQL(config);
                this.isSQLite = false;
            } else {
                initializeSQLite();
                this.isSQLite = true;
            }

            createTables();
            plugin.getLogger().info("Database initialized (type: " + type + ")");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Database initialization failed! Plugin may not function correctly.", e);
            throw new RuntimeException("Failed to initialize database. Check your database configuration.", e);
        }
    }

    private void initializeSQLite() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(),
                configManager.getMainConfig().getString("database.sqlite.filename", "playerdata.db"));
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }

    private void initializeMySQL(FileConfiguration config) throws SQLException {
        String host = config.getString("database.mysql.host", "localhost");
        int port = config.getInt("database.mysql.port", 3306);
        String database = config.getString("database.mysql.database", "letslearnenglish");
        String username = config.getString("database.mysql.username", "root");
        String password = config.getString("database.mysql.password", "");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true";

        connection = DriverManager.getConnection(url, username, password);
    }

    private void createTables() throws SQLException {
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_data (" +
                        "  uuid VARCHAR(36) PRIMARY KEY," +
                        "  player_name VARCHAR(32)," +
                        "  total_words_learned INT DEFAULT 0," +
                        "  total_sessions INT DEFAULT 0," +
                        "  total_score BIGINT DEFAULT 0," +
                        "  current_streak INT DEFAULT 0," +
                        "  longest_streak INT DEFAULT 0," +
                        "  last_login BIGINT," +
                        "  created_at BIGINT," +
                        "  updated_at BIGINT" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "word_progress (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  word VARCHAR(64) NOT NULL," +
                        "  mastery DOUBLE DEFAULT 0.0," +
                        "  correct_count INT DEFAULT 0," +
                        "  incorrect_count INT DEFAULT 0," +
                        "  last_review_time BIGINT," +
                        "  review_count INT DEFAULT 0," +
                        "  UNIQUE(uuid, word)" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "achievements (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  achievement_id VARCHAR(64) NOT NULL," +
                        "  unlocked_at BIGINT," +
                        "  UNIQUE(uuid, achievement_id)" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "learning_log (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  session_type VARCHAR(32)," +
                        "  difficulty VARCHAR(16)," +
                        "  words_count INT," +
                        "  correct_count INT," +
                        "  score INT," +
                        "  started_at BIGINT," +
                        "  ended_at BIGINT" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_sessions (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  session_date DATE NOT NULL," +
                        "  words_count INT DEFAULT 0," +
                        "  correct_count INT DEFAULT 0," +
                        "  score BIGINT DEFAULT 0," +
                        "  mode VARCHAR(32)," +
                        "  UNIQUE(uuid, session_date)" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_profiles (" +
                        "  uuid VARCHAR(36) PRIMARY KEY," +
                        "  age INT DEFAULT 0," +
                        "  show_answers BOOLEAN DEFAULT 1," +
                        "  show_results BOOLEAN DEFAULT 1," +
                        "  show_in_square BOOLEAN DEFAULT 1" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_achievements (" +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  achievement_id VARCHAR(64) NOT NULL," +
                        "  unlocked_at BIGINT NOT NULL," +
                        "  PRIMARY KEY (uuid, achievement_id)" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "daily_challenges (" +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  challenge_date DATE NOT NULL," +
                        "  challenge_id VARCHAR(32) NOT NULL," +
                        "  progress INT DEFAULT 0," +
                        "  completed BOOLEAN DEFAULT 0," +
                        "  claimed BOOLEAN DEFAULT 0," +
                        "  PRIMARY KEY (uuid, challenge_date, challenge_id)" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "daily_challenge_words (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  challenge_date DATE NOT NULL," +
                        "  word_id VARCHAR(64) NOT NULL," +
                        "  completed BOOLEAN DEFAULT FALSE," +
                        "  completed_at TIMESTAMP NULL," +
                        "  UNIQUE(player_uuid, challenge_date, word_id)" +
                        ")"
        );

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "ai_chat_history (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  session_id VARCHAR(64) NOT NULL," +
                        "  role VARCHAR(16) NOT NULL," +
                        "  content TEXT NOT NULL," +
                        "  timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")"
        );

        // Indexes for ai_chat_history
        try {
            executeUpdate("CREATE INDEX IF NOT EXISTS " + tablePrefix
                    + "idx_chat_player_time ON " + tablePrefix
                    + "ai_chat_history(player_uuid, timestamp)");
        } catch (SQLException e) {
            // Index may already exist; non-fatal
            plugin.getLogger().fine("Index idx_chat_player_time may already exist: " + e.getMessage());
        }
        try {
            executeUpdate("CREATE INDEX IF NOT EXISTS " + tablePrefix
                    + "idx_chat_session ON " + tablePrefix
                    + "ai_chat_history(session_id)");
        } catch (SQLException e) {
            plugin.getLogger().fine("Index idx_chat_session may already exist: " + e.getMessage());
        }
    }

    /**
     * Get the database connection.
     * Uses double-checked locking to prevent concurrent initialization.
     */
    public Connection getConnection() {
        // Fast path — connection is already valid (no lock)
        try {
            if (connection != null && !connection.isClosed()) {
                return connection;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Database connection check failed", e);
            throw new RuntimeException("Failed to get database connection", e);
        }

        // Slow path — initialize under lock to prevent races
        synchronized (connectionLock) {
            try {
                if (connection == null || connection.isClosed()) {
                    initialize();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Database connection check failed", e);
                throw new RuntimeException("Failed to get database connection", e);
            }
        }
        if (connection == null) {
            throw new RuntimeException("Database connection is null after initialization");
        }
        return connection;
    }

    /**
     * Execute an update statement.
     */
    public void executeUpdate(String sql) throws SQLException {
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.executeUpdate();
        }
    }

    // ============================================================
    //  AI Chat History
    // ============================================================

    /**
     * Insert a single chat message into the history table.
     */
    public void insertChatHistory(UUID playerUuid, String sessionId, String role, String content) {
        String sql = "INSERT INTO " + tablePrefix + "ai_chat_history"
                + " (player_uuid, session_id, role, content) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, sessionId);
            stmt.setString(3, role);
            stmt.setString(4, content);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to insert chat history for player " + playerUuid, e);
        }
    }

    /**
     * Get paginated chat history entries for a player, most recent first.
     */
    public List<ChatHistoryEntry> getChatHistory(UUID playerUuid, int page, int pageSize) {
        List<ChatHistoryEntry> entries = new ArrayList<>();
        String sql = "SELECT id, player_uuid, session_id, role, content, timestamp"
                + " FROM " + tablePrefix + "ai_chat_history"
                + " WHERE player_uuid = ?"
                + " ORDER BY timestamp DESC"
                + " LIMIT ? OFFSET ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            stmt.setInt(2, pageSize);
            stmt.setInt(3, (page - 1) * pageSize);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(new ChatHistoryEntry(
                            rs.getInt("id"),
                            rs.getString("player_uuid"),
                            rs.getString("session_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getTimestamp("timestamp")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to get chat history for player " + playerUuid, e);
        }
        return entries;
    }

    /**
     * Get all messages for a specific session, ordered by timestamp ascending.
     */
    public List<ChatHistoryEntry> getChatSession(UUID playerUuid, String sessionId) {
        List<ChatHistoryEntry> entries = new ArrayList<>();
        String sql = "SELECT id, player_uuid, session_id, role, content, timestamp"
                + " FROM " + tablePrefix + "ai_chat_history"
                + " WHERE player_uuid = ? AND session_id = ?"
                + " ORDER BY timestamp ASC";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(new ChatHistoryEntry(
                            rs.getInt("id"),
                            rs.getString("player_uuid"),
                            rs.getString("session_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getTimestamp("timestamp")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to get chat session " + sessionId + " for player " + playerUuid, e);
        }
        return entries;
    }

    /**
     * Get distinct sessions for a player, paginated. Each entry represents one session.
     * Returns the session_id, the earliest timestamp, and the system prompt for scenario extraction.
     */
    public List<ChatHistoryEntry> getChatSessions(UUID playerUuid, int page, int pageSize) {
        List<ChatHistoryEntry> entries = new ArrayList<>();
        // Get distinct session IDs with their timestamps, most recent first
        String sql = "SELECT session_id, MIN(timestamp) AS first_time, MAX(timestamp) AS last_time"
                + " FROM " + tablePrefix + "ai_chat_history"
                + " WHERE player_uuid = ?"
                + " GROUP BY session_id"
                + " ORDER BY last_time DESC"
                + " LIMIT ? OFFSET ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            stmt.setInt(2, pageSize);
            stmt.setInt(3, (page - 1) * pageSize);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Use a ChatHistoryEntry with session_id as the primary identifier
                    // timestamp = last_time for display, content = placeholder (to be filled)
                    entries.add(new ChatHistoryEntry(
                            0,
                            playerUuid.toString(),
                            rs.getString("session_id"),
                            "session",
                            "",
                            rs.getTimestamp("last_time")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to get chat sessions for player " + playerUuid, e);
        }
        return entries;
    }

    /**
     * Get the system prompt and first user message for a session (used for preview).
     */
    public String[] getSessionPreview(UUID playerUuid, String sessionId) {
        String[] result = new String[2]; // [0] = system content, [1] = first user content
        String sql = "SELECT role, content FROM " + tablePrefix + "ai_chat_history"
                + " WHERE player_uuid = ? AND session_id = ?"
                + " AND role IN ('system', 'user')"
                + " ORDER BY timestamp ASC"
                + " LIMIT 2";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String role = rs.getString("role");
                    if ("system".equals(role) && result[0] == null) {
                        result[0] = rs.getString("content");
                    } else if ("user".equals(role) && result[1] == null) {
                        result[1] = rs.getString("content");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to get session preview for " + sessionId, e);
        }
        return result;
    }

    /**
     * Get the total count of distinct sessions for a player.
     */
    public int getChatHistoryCount(UUID playerUuid) {
        String sql = "SELECT COUNT(DISTINCT session_id) FROM " + tablePrefix
                + "ai_chat_history WHERE player_uuid = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to count chat history for player " + playerUuid, e);
        }
        return 0;
    }

    /**
     * Delete chat history records older than the specified number of days.
     */
    public void cleanupOldChatHistory(int daysToKeep) {
        String dateExpr;
        if (isSQLite()) {
            dateExpr = "datetime('now', '-" + daysToKeep + " days')";
        } else {
            dateExpr = "DATE_SUB(NOW(), INTERVAL " + daysToKeep + " DAY)";
        }
        String sql = "DELETE FROM " + tablePrefix + "ai_chat_history"
                + " WHERE timestamp < " + dateExpr;
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                plugin.getLogger().info("Cleaned up " + deleted
                        + " old AI chat history records (older than " + daysToKeep + " days).");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to clean up old chat history", e);
        }
    }

    // ============================================================
    //  Leaderboard queries
    // ============================================================

    /**
     * Get top players by total_score (coins) from player_data table.
     */
    public List<LeaderboardEntry> getTopCoins(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String sql = "SELECT uuid, player_name, total_score FROM " + tablePrefix
                + "player_data ORDER BY total_score DESC LIMIT ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                int rank = 1;
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("player_name");
                    if (name == null || name.isEmpty()) {
                        name = uuid.toString();
                    }
                    entries.add(new LeaderboardEntry(uuid, name, rs.getLong("total_score"),
                            0, 0, 0, rank++));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to query top coins", e);
        }
        return entries;
    }

    /**
     * Get top players by total_words_learned from player_data table.
     */
    public List<LeaderboardEntry> getTopWordsLearned(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String sql = "SELECT uuid, player_name, total_words_learned FROM " + tablePrefix
                + "player_data ORDER BY total_words_learned DESC LIMIT ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                int rank = 1;
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("player_name");
                    if (name == null || name.isEmpty()) {
                        name = uuid.toString();
                    }
                    entries.add(new LeaderboardEntry(uuid, name, rs.getLong("total_words_learned"),
                            0, 0, 0, rank++));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to query top words learned", e);
        }
        return entries;
    }

    /**
     * Get top players by longest_streak (combo) from player_data table.
     */
    public List<LeaderboardEntry> getTopCombo(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String sql = "SELECT uuid, player_name, longest_streak FROM " + tablePrefix
                + "player_data ORDER BY longest_streak DESC LIMIT ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                int rank = 1;
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("player_name");
                    if (name == null || name.isEmpty()) {
                        name = uuid.toString();
                    }
                    int combo = rs.getInt("longest_streak");
                    entries.add(new LeaderboardEntry(uuid, name, combo,
                            0, 0, 0, rank++));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to query top combo", e);
        }
        return entries;
    }

    // ============================================================
    //  Daily Challenge Words
    // ============================================================

    /**
     * Get today's challenge words for a player.
     * Returns empty list if not yet assigned for today.
     */
    public List<String> getDailyChallengeWords(UUID playerId, String date) {
        List<String> words = new ArrayList<>();
        String sql = "SELECT word_id FROM " + tablePrefix
                + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, date);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    words.add(rs.getString("word_id"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to load daily challenge words for " + playerId, e);
        }
        return words;
    }

    /**
     * Assign daily challenge words for a player.
     */
    public void assignDailyChallengeWords(UUID playerId, String date, List<String> wordIds) {
        String insertSql;
        if (isSQLite()) {
            insertSql = "INSERT OR IGNORE INTO " + tablePrefix
                    + "daily_challenge_words (player_uuid, challenge_date, word_id) VALUES (?, ?, ?)";
        } else {
            insertSql = "INSERT IGNORE INTO " + tablePrefix
                    + "daily_challenge_words (player_uuid, challenge_date, word_id) VALUES (?, ?, ?)";
        }
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            for (String wordId : wordIds) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.setString(3, wordId);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to assign daily challenge words for " + playerId, e);
        }
    }

    /**
     * Mark a daily challenge word as completed.
     * Returns true if the word was newly completed (row was updated),
     * false if it was already completed or not found.
     */
    public boolean markDailyChallengeWordComplete(UUID playerId, String date, String wordId) {
        String sql = "UPDATE " + tablePrefix
                + "daily_challenge_words SET completed = TRUE, completed_at = CURRENT_TIMESTAMP"
                + " WHERE player_uuid = ? AND challenge_date = ? AND word_id = ? AND completed = FALSE";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, date);
            stmt.setString(3, wordId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to mark daily challenge word complete for " + playerId, e);
            return false;
        }
    }

    /**
     * Check if all daily challenge words are completed for today.
     */
    public boolean isDailyChallengeWordsCompleted(UUID playerId, String date) {
        String sql = "SELECT COUNT(*) as total, SUM(CASE WHEN completed THEN 1 ELSE 0 END) as done FROM "
                + tablePrefix + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, date);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int total = rs.getInt("total");
                    int done = rs.getInt("done");
                    return total > 0 && total == done;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to check daily challenge words completion for " + playerId, e);
        }
        return false;
    }

    /**
     * Get word challenge progress (completed / total) for today.
     * Returns int[]{completed, total}.
     */
    public int[] getDailyChallengeWordsProgress(UUID playerId, String date) {
        String sql = "SELECT COUNT(*) as total, SUM(CASE WHEN completed THEN 1 ELSE 0 END) as done FROM "
                + tablePrefix + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, date);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new int[]{rs.getInt("done"), rs.getInt("total")};
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to get daily challenge words progress for " + playerId, e);
        }
        return new int[]{0, 0};
    }

    /**
     * Shutdown the database connection.
     */
    public void shutdown() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("Database connection closed.");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error closing database connection", e);
        }
    }

    public boolean isSQLite() {
        return isSQLite;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }
}