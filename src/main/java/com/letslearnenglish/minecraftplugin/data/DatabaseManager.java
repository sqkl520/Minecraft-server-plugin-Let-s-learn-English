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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     *
     * <p>E-05：重连/自愈路径会重复进入本方法，先关旧连接再覆盖，
     * 否则旧句柄泄漏（SQLite 文件锁 / MySQL 空闲连接堆积）。
     * 关旧连接的异常吞掉记 WARNING，不阻断新连接建立。
     */
    public void initialize() {
        synchronized (connectionLock) {
            closeQuietly(connection);
            connection = null;
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
                        "  total_coins_earned BIGINT DEFAULT 0," +
                        "  total_coins_spent BIGINT DEFAULT 0," +
                        "  conversation_count INT DEFAULT 0," +
                        "  onboarding_step INT DEFAULT 0," +
                        "  wrong_book_cleared INT DEFAULT 0," +
                        "  current_streak INT DEFAULT 0," +
                        "  longest_streak INT DEFAULT 0," +
                        "  last_login BIGINT," +
                        "  created_at BIGINT," +
                        "  updated_at BIGINT" +
                        ")"
        );

        addColumnIfMissing(tablePrefix + "player_data", "total_coins_earned", "BIGINT DEFAULT 0");
        addColumnIfMissing(tablePrefix + "player_data", "total_coins_spent", "BIGINT DEFAULT 0");
        // R-28：AI 对话累计次数落盘列（供 AI_CHAT_10/50 成就重启后仍可判定）
        addColumnIfMissing(tablePrefix + "player_data", "conversation_count", "INT DEFAULT 0");
        // Phase 3 S-A：引导进度列（0=未开始，单调递增到 3=完结）
        addColumnIfMissing(tablePrefix + "player_data", "onboarding_step", "INT DEFAULT 0");
        // Phase 3 S-A：错题本累计攻克词数（WRONG_BOOK_CLEARED 成就，绝对值 UPSERT 写）
        addColumnIfMissing(tablePrefix + "player_data", "wrong_book_cleared", "INT DEFAULT 0");

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
                        "  review_words INT DEFAULT 0," +
                        "  UNIQUE(uuid, session_date)" +
                        ")"
        );
        // Phase 3 S-A：复习答对词数列（REVIEW_STREAK_7 判定；老库补列）
        addColumnIfMissing(tablePrefix + "player_sessions", "review_words", "INT DEFAULT 0");

        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_profiles (" +
                        "  uuid VARCHAR(36) PRIMARY KEY," +
                        "  age INT DEFAULT 0," +
                        "  show_answers BOOLEAN DEFAULT 0," +
                        "  show_results BOOLEAN DEFAULT 1," +
                        "  show_in_square BOOLEAN DEFAULT 1," +
                        "  sounds_enabled BOOLEAN DEFAULT 1" +
                        ")"
        );
        // Phase 3 S-A：per-player 音效开关（默认开；plan-audio-feedback §3.1）
        addColumnIfMissing(tablePrefix + "player_profiles", "sounds_enabled", "BOOLEAN DEFAULT 1");

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
        // PF-SEA-02（Phase 3 规划期发现，编排者即修）：CREATE INDEX IF NOT EXISTS 是
        // MariaDB 扩展，Oracle MySQL 不支持——异常原被 fine 吞掉，MySQL 后端索引可能
        // 从未建立。MySQL 分支改为先查 information_schema.statistics 判存在再建；
        // SQLite 分支保持原样（原生支持 IF NOT EXISTS）。
        createIndexIfMissing("idx_chat_player_time",
                "ai_chat_history(player_uuid, timestamp)");
        createIndexIfMissing("idx_chat_session",
                "ai_chat_history(session_id)");
        // PF-SEA-03：player_sessions 被周/月/赛季/streak/日历大量按日期范围查询，
        // 补 (uuid, session_date) 复合索引防全表扫。
        createIndexIfMissing("idx_sessions_uuid_date",
                "player_sessions(uuid, session_date)");
        createIndexIfMissing("idx_sessions_date",
                "player_sessions(session_date)");

        // Difficult words / Wrong Book table
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "difficult_words (" +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  word VARCHAR(100) NOT NULL," +
                        "  wrong_count INTEGER DEFAULT 0," +
                        "  added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  PRIMARY KEY (player_uuid, word)" +
                        ")"
        );

        // Daily check-in table
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "checkin_data (" +
                        "  player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                        "  last_checkin_date DATE NOT NULL," +
                        "  streak_count INTEGER DEFAULT 1" +
                        ")"
        );
    }

    /**
     * PF-SEA-02/03：幂等建索引 —— SQLite 用原生 {@code CREATE INDEX IF NOT EXISTS}；
     * MySQL（Oracle 版，不支持 IF NOT EXISTS）先查 information_schema.statistics
     * 判存在再建。任何失败只 fine 记录（索引非致命），绝不打断建表主流程。
     *
     * @param indexName 索引名（不含表前缀，方法内部拼）
     * @param target    形如 {@code "table(col1, col2)"} 的目标（表名不含前缀）
     */
    private void createIndexIfMissing(String indexName, String target) {
        String fullIndex = tablePrefix + indexName;
        int paren = target.indexOf('(');
        String tablePart = tablePrefix + target.substring(0, paren);
        String columnsPart = target.substring(paren);
        try {
            if (isSQLite()) {
                executeUpdate("CREATE INDEX IF NOT EXISTS " + fullIndex
                        + " ON " + tablePart + columnsPart);
                return;
            }
            // MySQL：information_schema 查重（当前库内）。
            // E-04 范式：取连接与语句执行整体收进 connectionLock（executeUpdate 同锁可重入）。
            synchronized (connectionLock) {
                String checkSql = "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
                try (java.sql.PreparedStatement stmt = getConnection().prepareStatement(checkSql)) {
                    stmt.setString(1, tablePart);
                    stmt.setString(2, fullIndex);
                    try (java.sql.ResultSet rs = stmt.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            return; // 已存在
                        }
                    }
                }
                try (java.sql.PreparedStatement stmt = getConnection()
                        .prepareStatement("CREATE INDEX " + fullIndex + " ON " + tablePart + columnsPart)) {
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().fine("Index " + fullIndex + " skipped: " + e.getMessage());
        }
    }

    /**
     * Get the database connection.
     *
     * <p>E-04：取连接整体收进 {@code connectionLock} 内（正确范式）。
     * 原快路径在锁外做 {@code isClosed} 检查并直接返回引用，调用方在锁外拿到的
     * Connection 可能在后续被 {@link #initialize()} 的重连替换，导致 Statement
     * 打在已关闭的旧连接上。现在统一走锁内获取 + 必要时自愈重连。
     * 注意：调用方仍须在 {@code synchronized (getConnectionLock())} 内使用返回的
     * 连接（锁外持有引用跨越重连仍不安全），外部裸用点见类 javadoc 的迁移约定。
     */
    public Connection getConnection() {
        synchronized (connectionLock) {
            try {
                if (connection == null || connection.isClosed()) {
                    initialize();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Database connection check failed", e);
                throw new RuntimeException("Failed to get database connection", e);
            }
            if (connection == null) {
                throw new RuntimeException("Database connection is null after initialization");
            }
            return connection;
        }
    }

    /**
     * E-05：关连接工具。判 null + {@code isClosed}，异常吞掉记 WARNING。
     * 调用方须持有 {@code connectionLock}（重连与关闭串行化）。
     */
    private void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            if (!conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to close stale database connection (continuing with re-init)", e);
        }
    }

    /**
     * Execute an update statement.
     */
    public void executeUpdate(String sql) throws SQLException {
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.executeUpdate();
            }
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
        synchronized (connectionLock) {
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
        synchronized (connectionLock) {
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
        synchronized (connectionLock) {
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
        synchronized (connectionLock) {
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
        synchronized (connectionLock) {
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
        }
        return result;
    }

    /**
     * Get the total count of distinct sessions for a player.
     */
    public int getChatHistoryCount(UUID playerUuid) {
        String sql = "SELECT COUNT(DISTINCT session_id) FROM " + tablePrefix
                + "ai_chat_history WHERE player_uuid = ?";
        synchronized (connectionLock) {
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
        synchronized (connectionLock) {
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
    }

    // ============================================================
    //  Leaderboard queries
    // ============================================================

    /** Get top players by lifetime coins earned. */
    public List<LeaderboardEntry> getTopCoins(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        // R-05：隐身者（player_profiles.show_in_square = 0）不上榜；
        // 无档案行视为默认可见。SELECT…JOIN…WHERE 口径 SQLite/MySQL 通用，无需 isSQLite 分支。
        String sql = "SELECT d.uuid, d.player_name, d.total_coins_earned FROM " + tablePrefix
                + "player_data d LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid"
                + " WHERE COALESCE(p.show_in_square, 1) <> 0"
                + " ORDER BY d.total_coins_earned DESC LIMIT ?";
        synchronized (connectionLock) {
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
                        entries.add(new LeaderboardEntry(uuid, name, rs.getLong("total_coins_earned"),
                                0, 0, 0, rank++));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query top coins", e);
            }
        }
        return entries;
    }

    /**
     * Get top players by total_words_learned from player_data table.
     */
    public List<LeaderboardEntry> getTopWordsLearned(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        // R-05：隐身者不上榜（同 getTopCoins 的 JOIN 过滤语义）。
        String sql = "SELECT d.uuid, d.player_name, d.total_words_learned FROM " + tablePrefix
                + "player_data d LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid"
                + " WHERE COALESCE(p.show_in_square, 1) <> 0"
                + " ORDER BY d.total_words_learned DESC LIMIT ?";
        synchronized (connectionLock) {
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
        }
        return entries;
    }

    /**
     * Get top players by longest_streak (combo) from player_data table.
     */
    public List<LeaderboardEntry> getTopCombo(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        // R-05：隐身者不上榜（同 getTopCoins 的 JOIN 过滤语义）。
        String sql = "SELECT d.uuid, d.player_name, d.longest_streak FROM " + tablePrefix
                + "player_data d LEFT JOIN " + tablePrefix + "player_profiles p ON p.uuid = d.uuid"
                + " WHERE COALESCE(p.show_in_square, 1) <> 0"
                + " ORDER BY d.longest_streak DESC LIMIT ?";
        synchronized (connectionLock) {
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
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
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
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
    }

    /**
     * Check if all daily challenge words are completed for today.
     */
    public boolean isDailyChallengeWordsCompleted(UUID playerId, String date) {
        String sql = "SELECT COUNT(*) as total, SUM(CASE WHEN completed THEN 1 ELSE 0 END) as done FROM "
                + tablePrefix + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
        }
        return new int[]{0, 0};
    }

    // ============================================================
    //  Difficult Words / Wrong Book
    // ============================================================

    /**
     * Increment the wrong count for a word. Inserts the word if it doesn't exist yet.
     * Returns the new wrong count after incrementing.
     */
    public int incrementWrongCount(UUID playerId, String word) {
        String upsertSql;
        if (isSQLite()) {
            upsertSql = "INSERT INTO " + tablePrefix + "difficult_words (player_uuid, word, wrong_count) VALUES (?, ?, 1) ON CONFLICT(player_uuid, word) DO UPDATE SET wrong_count = wrong_count + 1";
        } else {
            upsertSql = "INSERT INTO " + tablePrefix + "difficult_words (player_uuid, word, wrong_count) VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE wrong_count = wrong_count + 1";
        }
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to increment wrong count for " + playerId + " / " + word, e);
            }
        }

        // Read back the new count
        return getWrongCount(playerId, word);
    }

    /**
     * Get the current wrong count for a player's word.
     */
    public int getWrongCount(UUID playerId, String word) {
        String sql = "SELECT wrong_count FROM " + tablePrefix + "difficult_words WHERE player_uuid = ? AND word = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("wrong_count");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get wrong count for " + playerId + " / " + word, e);
            }
        }
        return 0;
    }

    /**
     * Get all difficult words for a player with their wrong counts and added timestamps.
     */
    public List<DifficultWord> getDifficultWords(UUID playerId) {
        List<DifficultWord> words = new ArrayList<>();
        String sql = "SELECT word, wrong_count, added_at FROM " + tablePrefix
                + "difficult_words WHERE player_uuid = ? ORDER BY added_at DESC";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String word = rs.getString("word");
                        int wrongCount = rs.getInt("wrong_count");
                        Timestamp ts = rs.getTimestamp("added_at");
                        long addedAt = ts != null ? ts.getTime() : System.currentTimeMillis();
                        words.add(new DifficultWord(word, wrongCount, addedAt));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get difficult words for " + playerId, e);
            }
        }
        return words;
    }

    /**
     * Remove a word from the player's difficult words list entirely.
     */
    public void removeDifficultWord(UUID playerId, String word) {
        String sql = "DELETE FROM " + tablePrefix + "difficult_words WHERE player_uuid = ? AND word = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to remove difficult word for " + playerId + " / " + word, e);
            }
        }
    }

    /**
     * Reset the wrong count for a word to 0 (used when practicing correctly).
     * Returns true if the row was updated (word existed), false otherwise.
     */
    public boolean decrementWrongCount(UUID playerId, String word) {
        String sql = "UPDATE " + tablePrefix + "difficult_words SET wrong_count = wrong_count - 1 WHERE player_uuid = ? AND word = ? AND wrong_count > 0";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                int rows = stmt.executeUpdate();
                return rows > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to decrement wrong count for " + playerId + " / " + word, e);
                return false;
            }
        }
    }

    /**
     * Clear wrong count to 0 for a word (reset without removing).
     */
    public void clearWrongCount(UUID playerId, String word) {
        String sql = "UPDATE " + tablePrefix + "difficult_words SET wrong_count = 0 WHERE player_uuid = ? AND word = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, word.toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clear wrong count for " + playerId + " / " + word, e);
            }
        }
    }

    // ============================================================
    //  Statistics HTTP Dashboard queries
    // ============================================================

    /**
     * Daily statistics record for the 7-day trend chart.
     */
    public record DailyStats(String date, int sessions, int players, int wordsLearned) {}

    /**
     * Count distinct words that have been learned by any player.
     */
    public int getTotalWordCount() {
        String sql = "SELECT COUNT(DISTINCT word) FROM " + tablePrefix + "word_progress";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query total word count", e);
            }
        }
        return 0;
    }

    /**
     * Count distinct players who were active today (have a session record for today).
     */
    public int getTodayActivePlayers() {
        String today = LocalDate.now().toString();
        String sql = "SELECT COUNT(DISTINCT uuid) FROM " + tablePrefix
                + "player_sessions WHERE session_date = ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, today);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query today active players", e);
            }
        }
        return 0;
    }

    /**
     * Count total learning sessions from the learning_log table.
     */
    public int getTotalSessions() {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix + "learning_log";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query total sessions", e);
            }
        }
        return 0;
    }

    /**
     * Sum lifetime coins earned from all players.
     */
    public long getTotalCoinsEarned() {
        String sql = "SELECT COALESCE(SUM(total_coins_earned), 0) FROM " + tablePrefix + "player_data";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query total coins earned", e);
            }
        }
        return 0L;
    }

    /**
     * Sum coin spending recorded by this plugin.
     */
    public long getTotalCoinsSpent() {
        String sql = "SELECT COALESCE(SUM(total_coins_spent), 0) FROM " + tablePrefix + "player_data";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query total coins spent", e);
                return 0L;
            }
        }
    }

    public void recordCoinsEarned(UUID playerId, double amount) {
        adjustCoins(playerId, "total_coins_earned", amount);
    }

    public void recordCoinsSpent(UUID playerId, double amount) {
        adjustCoins(playerId, "total_coins_spent", amount);
    }

    private void adjustCoins(UUID playerId, String column, double amount) {
        if (amount <= 0 || !(column.equals("total_coins_earned") || column.equals("total_coins_spent"))) return;
        String sql = "INSERT INTO " + tablePrefix + "player_data (uuid, " + column + ", updated_at) VALUES (?, ?, ?) "
                + (isSQLite()
                ? "ON CONFLICT(uuid) DO UPDATE SET " + column + " = COALESCE(" + column + ", 0) + excluded." + column + ", updated_at = excluded.updated_at"
                : "ON DUPLICATE KEY UPDATE " + column + " = COALESCE(" + column + ", 0) + VALUES(" + column + "), updated_at = VALUES(updated_at)");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setLong(2, Math.round(amount));
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to record coin transaction for " + playerId, e);
            }
        }
    }

    public long getCoinsEarned(UUID playerId) {
        String sql = "SELECT total_coins_earned FROM " + tablePrefix + "player_data WHERE uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query coins for " + playerId, e);
                return 0L;
            }
        }
    }

    /**
     * R-28：持久化玩家 AI 对话累计次数（绝对值 UPSERT）。
     *
     * <p>由 {@code PlayerDataStore.incrementConversationCount} 在每次有效对话结束后
     * 即时调用，使 AI_CHAT_10/50 成就计数跨重启不丢。写绝对值而非增量，调用方以缓存
     * 为权威先 +1 再落库，重复落库幂等；不受 quit 事件两个监听器
     * （ChatScenarioListener 结束对话 vs PlayerJoinListener 卸载缓存）执行顺序影响。
     *
     * @param playerId the player
     * @param count    the new absolute conversation count
     */
    public void setConversationCount(UUID playerId, int count) {
        String sql = "INSERT INTO " + tablePrefix + "player_data (uuid, conversation_count, updated_at) VALUES (?, ?, ?) "
                + (isSQLite()
                ? "ON CONFLICT(uuid) DO UPDATE SET conversation_count = excluded.conversation_count, updated_at = excluded.updated_at"
                : "ON DUPLICATE KEY UPDATE conversation_count = VALUES(conversation_count), updated_at = VALUES(updated_at)");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setInt(2, count);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist conversation_count for " + playerId, e);
            }
        }
    }

    /**
     * Phase 3 S-A：读取玩家引导进度（{@code player_data.onboarding_step}）。
     *
     * <p>无行（从未落库的新玩家）→ 0，与 OnboardingManager.STEP_UNINITIALIZED 对齐。
     * 读侧只此一条小 SELECT（OnboardingManager 于 join 时同步调用，模式同
     * loadProfileSettings）。
     *
     * @param playerId the player
     * @return the persisted onboarding step, 0 if no row exists
     */
    public int getOnboardingStep(UUID playerId) {
        String sql = "SELECT onboarding_step FROM " + tablePrefix + "player_data WHERE uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query onboarding_step for " + playerId, e);
                return 0;
            }
        }
    }

    /**
     * Phase 3 S-A：持久化玩家引导进度（绝对值 UPSERT，同 {@link #setConversationCount} pattern）。
     *
     * <p>由 {@code OnboardingManager.advanceStep} 在 step 单调推进时调用；写绝对值而非
     * 增量，重复落库幂等。仅写 onboarding_step 列，与 savePlayerData 的 UPSERT
     * 互不覆盖（各自显式列名）。
     *
     * @param playerId the player
     * @param step     the new absolute onboarding step
     */
    public void setOnboardingStep(UUID playerId, int step) {
        String sql = "INSERT INTO " + tablePrefix + "player_data (uuid, onboarding_step, updated_at) VALUES (?, ?, ?) "
                + (isSQLite()
                ? "ON CONFLICT(uuid) DO UPDATE SET onboarding_step = excluded.onboarding_step, updated_at = excluded.updated_at"
                : "ON DUPLICATE KEY UPDATE onboarding_step = VALUES(onboarding_step), updated_at = VALUES(updated_at)");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setInt(2, step);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist onboarding_step for " + playerId, e);
            }
        }
    }

    /**
     * Phase 3 S-A：持久化玩家错题本累计攻克词数（绝对值 UPSERT，同 {@link #setConversationCount} pattern）。
     *
     * <p>由 {@code PlayerDataStore.incrementWrongBookCleared} 在练习答对导致词语移出
     * 错题本时调用，使 WRONG_BOOK_CLEARED 成就计数跨重启不丢。写绝对值而非增量，
     * 调用方以缓存为权威先 +1 再落库，重复落库幂等；列名避开 word_progress 的
     * INSERT OR REPLACE 全列覆盖陷阱（P3-09），与 word 级数据互不干扰。
     *
     * @param playerId the player
     * @param count    the new absolute cleared-word count
     */
    public void setWrongBookCleared(UUID playerId, int count) {
        String sql = "INSERT INTO " + tablePrefix + "player_data (uuid, wrong_book_cleared, updated_at) VALUES (?, ?, ?) "
                + (isSQLite()
                ? "ON CONFLICT(uuid) DO UPDATE SET wrong_book_cleared = excluded.wrong_book_cleared, updated_at = excluded.updated_at"
                : "ON DUPLICATE KEY UPDATE wrong_book_cleared = VALUES(wrong_book_cleared), updated_at = VALUES(updated_at)");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setInt(2, count);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist wrong_book_cleared for " + playerId, e);
            }
        }
    }

    /**
     * Get daily statistics breakdown for the last 7 days.
     * Combines data from player_sessions (players, words) and
     * learning_log (session count) per day.
     */
    public List<DailyStats> getLast7DaysStats() {
        Map<String, int[]> dailyData = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();

        // Initialize all 7 days (including today) with zeros
        for (int i = 6; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            dailyData.put(date, new int[]{0, 0, 0}); // [sessions, players, wordsLearned]
        }

        // Query player_sessions for daily players and words
        String sessionsSql = "SELECT session_date, COUNT(DISTINCT uuid) as players, "
                + "COALESCE(SUM(words_count), 0) as words "
                + "FROM " + tablePrefix + "player_sessions "
                + "WHERE session_date >= ? "
                + "GROUP BY session_date";
        Connection conn;
        synchronized (connectionLock) {
            // E-04：取连接移入锁内（原锁外引用跨语句块复用，重连即失效）。
            conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sessionsSql)) {
                stmt.setString(1, today.minusDays(6).toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String date = rs.getString("session_date");
                        int[] data = dailyData.get(date);
                        if (data != null) {
                            data[1] = rs.getInt("players");
                            data[2] = rs.getInt("words");
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query daily player stats", e);
            }
        }

        // Query learning_log for daily session counts
        String dateExpr;
        String logSql;
        if (isSQLite()) {
            logSql = "SELECT date(started_at / 1000, 'unixepoch') as day, COUNT(*) as cnt "
                    + "FROM " + tablePrefix + "learning_log "
                    + "WHERE started_at >= ? "
                    + "GROUP BY day";
        } else {
            logSql = "SELECT DATE(FROM_UNIXTIME(started_at / 1000)) as day, COUNT(*) as cnt "
                    + "FROM " + tablePrefix + "learning_log "
                    + "WHERE started_at >= ? "
                    + "GROUP BY day";
        }
        synchronized (connectionLock) {
            // E-04：上一个锁块已释放，重连可能已替换引用，此处锁内重取。
            conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(logSql)) {
                long sevenDaysAgoMillis = today.minusDays(6).atStartOfDay(
                        java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                stmt.setLong(1, sevenDaysAgoMillis);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String day = rs.getString("day");
                        int[] data = dailyData.get(day);
                        if (data != null) {
                            data[0] = rs.getInt("cnt");
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query daily session counts", e);
            }
        }

        List<DailyStats> result = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : dailyData.entrySet()) {
            int[] d = entry.getValue();
            result.add(new DailyStats(entry.getKey(), d[0], d[1], d[2]));
        }
        return result;
    }

    // ============================================================
    //  Daily Check-in
    // ============================================================

    /**
     * Get a player's check-in data (last date and streak).
     * Returns null if the player has never checked in.
     */
    public CheckInData getCheckInData(UUID playerId) {
        String sql = "SELECT last_checkin_date, streak_count FROM " + tablePrefix
                + "checkin_data WHERE player_uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String lastDate = rs.getString("last_checkin_date");
                        int streak = rs.getInt("streak_count");
                        return new CheckInData(lastDate, streak);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get check-in data for " + playerId, e);
            }
        }
        return null;
    }

    /**
     * Update (UPSERT) a player's check-in data.
     */
    public boolean updateCheckIn(UUID playerId, String date, int streakCount) {
        String sql;
        if (isSQLite()) {
            sql = "INSERT OR REPLACE INTO " + tablePrefix
                    + "checkin_data (player_uuid, last_checkin_date, streak_count) VALUES (?, ?, ?)";
        } else {
            sql = "INSERT INTO " + tablePrefix
                    + "checkin_data (player_uuid, last_checkin_date, streak_count) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE last_checkin_date = VALUES(last_checkin_date), "
                    + "streak_count = VALUES(streak_count)";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.setInt(3, streakCount);
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to update check-in data for " + playerId, e);
                return false;
            }
        }
    }

    /**
     * Reset a player's check-in streak to 0.
     */
    public void resetCheckInStreak(UUID playerId) {
        String sql = "UPDATE " + tablePrefix + "checkin_data SET streak_count = 0 WHERE player_uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to reset check-in streak for " + playerId, e);
            }
        }
    }

    /**
     * Delete every row that belongs to one player across all player-scoped tables.
     *
     * <p>R-03：{@code /lea reset} 的删表实现（原实现只删 4 表，榜单/签到/挑战/错题/
     * AI 历史全部残留）。uuid 系 8 表 + player_uuid 系 4 表共 12 表：
     * player_data、word_progress、achievements、player_achievements（死表 E-03，
     * 删了无害，免残行未来被误接线复活）、learning_log、player_sessions、
     * player_profiles、daily_challenges、daily_challenge_words、ai_chat_history、
     * difficult_words、checkin_data。
     *
     * <p>建表语句无外键（SQLite/MySQL 双实现与 schema.sql 均已查证），直删无需排序。
     * 与本类其他方法一致：全程持 {@code connectionLock}，内部吞异常记 WARNING，
     * 调用方无需再 try/catch。
     *
     * @param playerId the player whose rows should be removed
     */
    public void deletePlayerData(UUID playerId) {
        String[] uuidTables = {
            "player_data", "word_progress", "achievements", "player_achievements",
            "learning_log", "player_sessions", "player_profiles", "daily_challenges"
        };
        // 这 4 表的玩家列叫 player_uuid，不是 uuid（见 createTables 建表语句）
        String[] playerUuidTables = {
            "daily_challenge_words", "ai_chat_history", "difficult_words", "checkin_data"
        };
        String id = playerId.toString();
        synchronized (connectionLock) {
            Connection conn = getConnection();
            for (String table : uuidTables) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM " + tablePrefix + table + " WHERE uuid = ?")) {
                    stmt.setString(1, id);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to delete rows from " + tablePrefix + table
                                    + " for " + playerId, e);
                }
            }
            for (String table : playerUuidTables) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM " + tablePrefix + table + " WHERE player_uuid = ?")) {
                    stmt.setString(1, id);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to delete rows from " + tablePrefix + table
                                    + " for " + playerId, e);
                }
            }
        }
    }

    /**
     * Shutdown the database connection.
     */
    public void shutdown() {
        synchronized (connectionLock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                    plugin.getLogger().info("Database connection closed.");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing database connection", e);
            }
        }
    }

    public boolean isSQLite() {
        return isSQLite;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    /**
     * Expose the serialization monitor that guards the single shared Connection.
     *
     * <p>M16 band-aid: every method in this class that touches the shared
     * connection now runs inside {@code synchronized (connectionLock)}, so all
     * access routed through DatabaseManager is serialized. However, several
     * classes still obtain the raw connection via {@link #getConnection()} and
     * run their own SQL off this lock (PlayerDataStore, DailyChallengeManager,
     * AchievementManager, AdminCommandHandler, ProgressManagerImpl,
     * LLEPapiExpansion). Until those are migrated (Phase 1+), they MUST wrap
     * their statement execution in
     * {@code synchronized (databaseManager.getConnectionLock()) { ... }} on this
     * same monitor to fully guarantee single-threaded connection use.
     *
     * <p>Phase 1 流 E（F0-01/F0-02）：外部裸用点已收编完毕（PlayerDataStore 8 处、
     * DailyChallengeManager 8 处、AchievementManager 3 处、AdminCommandHandler 2 处、
     * ProgressManagerImpl 1 处、LLEPapiExpansion 1 处、LetsLearnEnglish 备份任务 2 处
     * 共 25 处），新代码继续遵循此约定。
     */
    public Object getConnectionLock() {
        return connectionLock;
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try {
            executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (!message.contains("duplicate") && !message.contains("already exists")) throw e;
        }
    }
}
