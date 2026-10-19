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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                        // P3-03 收口：current_streak 为历史遗留列、零写入恒 0。
                        // 真源是 checkin_data.streak_count（每日签到连击）；本列保留不删
                        //（删列有 schema 变更风险），读侧已改走 checkin_data。
                        "  current_streak INT DEFAULT 0," +
                        "  longest_streak INT DEFAULT 0," +
                        // P3-16：last_login / created_at 为历史遗留列，全仓零读写（建表后
                        // 从未写入、无任何查询引用）。保留不删（数据永不删原则，同 P3-03
                        // 的"保留不删"先例）；如需注册漏斗统计再接线（P3-16）。
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

        // 修复批 A（R10-H1）：单词挑战奖励"已付"台账 —— 内存 Set（wordChallengeClaimedToday）
        // 之外的重启防重复真源：内存清空后按日期查库防同日重发。幂等写入
        // （INSERT OR IGNORE / INSERT IGNORE），UNIQUE(player_uuid, challenge_date)
        // 兜底"每人每日至多一行"；/lea reset 清收随 deletePlayerData 的 player_uuidTables。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "daily_word_reward_claims (" +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  challenge_date DATE NOT NULL," +
                        "  claimed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  UNIQUE(player_uuid, challenge_date)" +
                        ")"
        );

        // Block C2（R2-M2）：今日 AI 轮次计数器持久化 —— 原内存 TodayCounter 重启/换服/
        // quit-rejoin 即清零，daily-round-limit（默认 30）可无限绕过（API 成本面）。
        // 与 daily_word_reward_claims 同式"重启后真源"：UNIQUE(player_uuid, round_date)
        // 兜底每人每日至多一行；/lea reset 清收随 deletePlayerData 的 player_uuidTables。
        // 旧行由 cleanupOldChatHistory 同窗口清理。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "ai_round_counters (" +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  round_date DATE NOT NULL," +
                        "  rounds INT NOT NULL DEFAULT 0," +
                        "  UNIQUE(player_uuid, round_date)" +
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

        // US-FB §3.1（Wave 3 批次 3B）：会话级 AI 对话反馈 —— 先本地统计（source='local'）
        // 兜底落库，AI 点评异步到达后 UPSERT 覆写为 source='ai' + payload。
        // UNIQUE(player_uuid, session_id) 兼作唯一查询形态的索引；不落"未达标"行。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "ai_chat_feedback (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  session_id VARCHAR(64) NOT NULL," +
                        "  scenario_key VARCHAR(64)," +
                        "  difficulty_key VARCHAR(32)," +
                        "  user_messages INT DEFAULT 0," +
                        "  ai_messages INT DEFAULT 0," +
                        "  duration_seconds INT DEFAULT 0," +
                        "  source VARCHAR(8) NOT NULL DEFAULT 'local'," +
                        "  ai_payload TEXT," +
                        "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  UNIQUE(player_uuid, session_id)" +
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
        // P3-06：checkin_data 仅保存当前态（last_checkin_date + streak_count），
        // 无逐日历史行——UPSERT 覆盖式写入，昨日之前不可回溯。"本周签到 X 天"
        // 类需求需从 player_sessions 推导（学习日次口径）或另建历史表（P3-06）；
        // social_feed 的 CHECKIN 动态带 social.enabled/隐身门且有保留期清理，
        // 不能当统计真源。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "checkin_data (" +
                        "  player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                        "  last_checkin_date DATE NOT NULL," +
                        "  streak_count INTEGER DEFAULT 1" +
                        ")"
        );

        // Wave 2 域 4（广场社交）：学习动态流 —— 每玩家 × 每自然日 × 每事件类型
        // UPSERT 合并为 1 行（防刷屏 = 结构聚合，见 plan-social §1.4）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "social_feed (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  player_uuid VARCHAR(36) NOT NULL," +
                        "  feed_date DATE NOT NULL," +
                        "  event_type VARCHAR(16) NOT NULL," +
                        "  events_count INT DEFAULT 1," +
                        "  sum_correct INT DEFAULT 0," +
                        "  sum_total INT DEFAULT 0," +
                        "  last_mode VARCHAR(16)," +
                        "  last_streak INT DEFAULT 0," +
                        "  achievement_id VARCHAR(64)," +
                        "  cheer_count INT DEFAULT 0," +
                        "  created_at BIGINT NOT NULL," +
                        "  updated_at BIGINT NOT NULL," +
                        "  UNIQUE(player_uuid, feed_date, event_type)" +
                        ")"
        );
        // Wave 2 域 4：鼓励记录 —— 一人一条动态至多一次（UNIQUE 硬约束）。
        // 注意：玩家列名是 cheerer_uuid（既有的 uuid / player_uuid 两套之外第三套，
        // 见 deletePlayerData 的顺序敏感特殊处理）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "social_cheers (" +
                        "  id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  feed_id INTEGER NOT NULL," +
                        "  cheerer_uuid VARCHAR(36) NOT NULL," +
                        "  created_at BIGINT NOT NULL," +
                        "  read_by_owner BOOLEAN DEFAULT 0," +
                        "  UNIQUE(feed_id, cheerer_uuid)" +
                        ")"
        );
        // 索引经 createIndexIfMissing（PF-SEA-02：MySQL 不支持 CREATE INDEX IF NOT EXISTS）
        createIndexIfMissing("idx_feed_order",
                "social_feed(feed_date, updated_at)");
        createIndexIfMissing("idx_feed_player",
                "social_feed(player_uuid, feed_date)");
        createIndexIfMissing("idx_cheers_feed",
                "social_cheers(feed_id)");
        createIndexIfMissing("idx_cheers_cheerer",
                "social_cheers(cheerer_uuid)");

        // Wave 3 域 5 批次 A（赛季）：结算台账两表。
        // seasons = 每季 1 行的结算头（settled_at NULL=未结算，条件 UPDATE 抢占结算权）；
        // season_rewards = 每人一行的发放台账（防重复 + 跨服/离线补发队列）。
        // 赛季榜本身零新表（复用 player_sessions 按日期窗聚合，plan-season §1.3）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "seasons (" +
                        "  season_id INT PRIMARY KEY," +
                        "  start_date DATE NOT NULL," +
                        "  end_date DATE NOT NULL," +
                        "  settled_at BIGINT," +
                        "  top_snapshot TEXT" +
                        ")"
        );
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "season_rewards (" +
                        "  season_id INT NOT NULL," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  rank_position INT NOT NULL," + // 列名避开 SQL 保留字 rank
                        "  score BIGINT NOT NULL," +
                        "  reward_coins BIGINT NOT NULL," + // 单位"分"（对齐 RewardEngine 厘级记账）
                        "  claimed BOOLEAN DEFAULT 0," +
                        "  claimed_at BIGINT," +
                        "  PRIMARY KEY (season_id, uuid)" +
                        ")"
        );
        // 补发查询按 uuid 走（PK 前导是 season_id，非前缀），与既有索引同走
        // createIndexIfMissing（PF-SEA-02：MySQL 不支持 CREATE INDEX IF NOT EXISTS）。
        createIndexIfMissing("idx_season_rewards_uuid",
                "season_rewards(uuid)");

        // Wave 4 US-CLS-1（班级系统）：classes = 班级头（class_name 作业务键 UNIQUE，
        // 建/删/查全走班名）；class_members = 成员关系（一人一班由调用方编排，
        // Data 层只管单班成员行；PK(class_id, uuid) 兼作唯一索引）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "classes (" +
                        "  class_id INTEGER PRIMARY KEY " + (isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "  class_name VARCHAR(32) NOT NULL UNIQUE," +
                        "  display_name VARCHAR(64)," +
                        "  created_by VARCHAR(36) NOT NULL," +
                        "  created_at BIGINT NOT NULL" +
                        ")"
        );
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "class_members (" +
                        "  class_id INT NOT NULL," +
                        "  uuid VARCHAR(36) NOT NULL," +
                        "  joined_at BIGINT NOT NULL," +
                        "  PRIMARY KEY (class_id, uuid)" +
                        ")"
        );
        // 一人一班反查（getPlayerClassId 按 uuid 查；PK 前导 class_id 非前缀）
        createIndexIfMissing("idx_class_members_uuid",
                "class_members(uuid)");

        // Block C2（A6/R9-M4）：player_data(player_name) 索引 —— getKnownPlayerNames
        // （AdminCommandHandler tab 补全）按 player_name LIKE 前缀查，无索引则主线程
        // 全表扫 + 持锁阻塞（每次按键）。createIndexIfMissing 已处理双后端查重与
        // 失败静默（索引非致命）。
        createIndexIfMissing("idx_player_data_name",
                "player_data(player_name)");
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
     *
     * <p>修复批 B（R3-H2 清理侧）：同窗口一并清 {@code ai_chat_feedback}
     * （created_at 与 ai_chat_history.timestamp 同为 CURRENT_TIMESTAMP 写入，
     * 同一 {@code dateExpr} 阈值可直接套用），防该表无限增长。保留期窗口语义 =
     * "早于阈值行"（非精确边界）：两后端各自按库内时钟自洽，SQLite（UTC 字符串）与
     * MySQL（会话时区 TIMESTAMP）间的 8h 级偏移可接受，不断言被删行的时区精确性。
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
        String sql2 = "DELETE FROM " + tablePrefix + "ai_chat_feedback WHERE created_at < " + dateExpr;
        // Block C2（A1/R2-M2）：ai_round_counters 旧行同窗口清理（round_date 为纯日期，
        // 与日期阈值精确字典序可比——SQLite date('now') / MySQL CURDATE() 两后端对照）
        // ——新表必须有清理者，先例 R3-H2。
        String roundDateExpr = isSQLite()
                ? "date('now', '-" + daysToKeep + " days')"
                : "DATE_SUB(CURDATE(), INTERVAL " + daysToKeep + " DAY)";
        String sql3 = "DELETE FROM " + tablePrefix + "ai_round_counters"
                + " WHERE round_date < " + roundDateExpr;
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
            try (PreparedStatement stmt = getConnection().prepareStatement(sql2)) {
                int deletedFeedback = stmt.executeUpdate();
                if (deletedFeedback > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedFeedback
                            + " old AI chat feedback records (older than " + daysToKeep + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old chat feedback", e);
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(sql3)) {
                int deletedRounds = stmt.executeUpdate();
                if (deletedRounds > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedRounds
                            + " old AI round counter records (older than " + daysToKeep + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old AI round counters", e);
            }
        }
    }

    // ============================================================
    //  AI Chat Feedback（US-FB §3.1，Wave 3 批次 3B）
    // ============================================================

    /**
     * US-FB §3.1：会话级反馈 UPSERT（先本地行后 AI 覆写，幂等）。
     *
     * <p>SQLite {@code ON CONFLICT(player_uuid, session_id) DO UPDATE} /
     * MySQL {@code ON DUPLICATE KEY UPDATE}（双后端对照，沿 {@link #incrementWrongCount}
     * 惯例）；全列覆写 —— AI 覆写时 {@code payloadJsonOrNull} 覆盖先前值。
     *
     * @param payloadJsonOrNull AI 点评 JSON；仅本地行时传 {@code null}
     */
    public void upsertChatFeedback(UUID playerUuid, String sessionId, String scenarioKey,
                                   String difficultyKey, int userMessages, int aiMessages,
                                   int durationSeconds, String source, String payloadJsonOrNull) {
        String upsertSql;
        if (isSQLite()) {
            upsertSql = "INSERT INTO " + tablePrefix + "ai_chat_feedback"
                    + " (player_uuid, session_id, scenario_key, difficulty_key, user_messages,"
                    + " ai_messages, duration_seconds, source, ai_payload)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT(player_uuid, session_id) DO UPDATE SET"
                    + " scenario_key = excluded.scenario_key, difficulty_key = excluded.difficulty_key,"
                    + " user_messages = excluded.user_messages, ai_messages = excluded.ai_messages,"
                    + " duration_seconds = excluded.duration_seconds, source = excluded.source,"
                    + " ai_payload = excluded.ai_payload";
        } else {
            upsertSql = "INSERT INTO " + tablePrefix + "ai_chat_feedback"
                    + " (player_uuid, session_id, scenario_key, difficulty_key, user_messages,"
                    + " ai_messages, duration_seconds, source, ai_payload)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE"
                    + " scenario_key = VALUES(scenario_key), difficulty_key = VALUES(difficulty_key),"
                    + " user_messages = VALUES(user_messages), ai_messages = VALUES(ai_messages),"
                    + " duration_seconds = VALUES(duration_seconds), source = VALUES(source),"
                    + " ai_payload = VALUES(ai_payload)";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(upsertSql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, sessionId);
                stmt.setString(3, scenarioKey);
                stmt.setString(4, difficultyKey);
                stmt.setInt(5, userMessages);
                stmt.setInt(6, aiMessages);
                stmt.setInt(7, durationSeconds);
                stmt.setString(8, source);
                stmt.setString(9, payloadJsonOrNull);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to upsert chat feedback for player " + playerUuid
                                + " / session " + sessionId, e);
            }
        }
    }

    /**
     * US-FB §3.1：读取单会话反馈行（GUI 详情页用；UNIQUE(player_uuid, session_id)
     * 上的有界索引查询）。无行返回 {@code null}。
     */
    public ChatFeedbackEntry getChatFeedback(UUID playerUuid, String sessionId) {
        String sql = "SELECT id, player_uuid, session_id, scenario_key, difficulty_key,"
                + " user_messages, ai_messages, duration_seconds, source, ai_payload, created_at"
                + " FROM " + tablePrefix + "ai_chat_feedback"
                + " WHERE player_uuid = ? AND session_id = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, sessionId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new ChatFeedbackEntry(
                                rs.getInt("id"),
                                rs.getString("player_uuid"),
                                rs.getString("session_id"),
                                rs.getString("scenario_key"),
                                rs.getString("difficulty_key"),
                                rs.getInt("user_messages"),
                                rs.getInt("ai_messages"),
                                rs.getInt("duration_seconds"),
                                rs.getString("source"),
                                rs.getString("ai_payload"),
                                rs.getTimestamp("created_at")
                        );
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get chat feedback for player " + playerUuid
                                + " / session " + sessionId, e);
            }
        }
        return null;
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

    /**
     * 修复批 A（R10-H1）：标记玩家某日的单词挑战奖励已发放（幂等，双后端对照）。
     *
     * <p>SQLite {@code INSERT OR IGNORE} / MySQL {@code INSERT IGNORE}（同
     * {@link #assignDailyChallengeWords} 惯例），UNIQUE(player_uuid, challenge_date)
     * 兜底重复标记为 no-op。该表是"已付"的重启后真源——内存
     * {@code wordChallengeClaimedToday} 重启即空，同日重启不再据此重复发钱。
     *
     * <p>静默降级：SQLException 只记 WARNING（标记失败最坏后果 = 重启后可能重复
     * 发放一次，与修复前行为持平，不崩、不阻断发钱主流程）。
     *
     * @param playerId the player
     * @param date     the challenge date (yyyy-MM-dd；rollover 补发传昨日)
     */
    public void markDailyWordRewardClaimed(UUID playerId, String date) {
        String insertSql;
        if (isSQLite()) {
            insertSql = "INSERT OR IGNORE INTO " + tablePrefix
                    + "daily_word_reward_claims (player_uuid, challenge_date) VALUES (?, ?)";
        } else {
            insertSql = "INSERT IGNORE INTO " + tablePrefix
                    + "daily_word_reward_claims (player_uuid, challenge_date) VALUES (?, ?)";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(insertSql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to mark daily word reward claimed for " + playerId
                                + " / " + date, e);
            }
        }
    }

    /**
     * 修复批 A（R10-H1）：玩家某日的单词挑战奖励是否已发放（{@code daily_word_reward_claims}
     * 台账查询）。异常 → false + WARNING（降级语义同 {@link #markDailyWordRewardClaimed}：
     * 查不到就按未付处理，最坏后果与修复前持平）。
     *
     * @param playerId the player
     * @param date     the challenge date (yyyy-MM-dd)
     * @return true if a claim row exists for this player on this date
     */
    public boolean isDailyWordRewardClaimed(UUID playerId, String date) {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix + "daily_word_reward_claims"
                + " WHERE player_uuid = ? AND challenge_date = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to check daily word reward claim for " + playerId
                                + " / " + date, e);
                return false;
            }
        }
    }

    /**
     * Block C2（R2-M2）：今日 AI 有效轮次 +1 落库（UPSERT，双后端对照）。
     *
     * <p>SQLite {@code ON CONFLICT(player_uuid, round_date) DO UPDATE} /
     * MySQL {@code ON DUPLICATE KEY UPDATE}（同 {@link #incrementWrongCount} 惯例）。
     * 该表是 daily-round-limit 的重启后真源——内存 TodayCounter 重启/quit-rejoin
     * 即清，按 {@link #getAiRoundCount} 回读真值闭合绕过路径。
     *
     * <p>静默降级：SQLException 只记 WARNING（写失败最坏后果 = 重启后计数可重复，
     * 与修复前行为持平，不崩、不阻断聊天主流程）。
     *
     * @param playerId the player
     * @param date     the round date (yyyy-MM-dd)
     */
    public void incrementAiRound(UUID playerId, String date) {
        String upsertSql;
        if (isSQLite()) {
            upsertSql = "INSERT INTO " + tablePrefix
                    + "ai_round_counters (player_uuid, round_date, rounds) VALUES (?, ?, 1)"
                    + " ON CONFLICT(player_uuid, round_date) DO UPDATE SET rounds = rounds + 1";
        } else {
            upsertSql = "INSERT INTO " + tablePrefix
                    + "ai_round_counters (player_uuid, round_date, rounds) VALUES (?, ?, 1)"
                    + " ON DUPLICATE KEY UPDATE rounds = rounds + 1";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(upsertSql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to increment AI round counter for " + playerId
                                + " / " + date, e);
            }
        }
    }

    /**
     * Block C2（R2-M2）：玩家某日的 AI 有效轮次累计（{@code ai_round_counters} 查询）。
     * 无行 → 0；异常 → 0 + WARNING（降级语义同 {@link #incrementAiRound}：查不到
     * 就按 0 处理，最坏后果 = 重启后防刷上限可重复，与修复前持平）。
     *
     * @param playerId the player
     * @param date     the round date (yyyy-MM-dd)
     * @return rounds recorded for this player on this date, 0 if none
     */
    public int getAiRoundCount(UUID playerId, String date) {
        String sql = "SELECT rounds FROM " + tablePrefix + "ai_round_counters"
                + " WHERE player_uuid = ? AND round_date = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("rounds");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get AI round count for " + playerId
                                + " / " + date, e);
            }
        }
        return 0;
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
     * Decrement the wrong count for a word (used when practicing correctly).
     *
     * <p>Block C2（A3/R3-M2）：原子化边界 —— 条件改为 {@code wrong_count > 1}
     * （纯标准 UPDATE，无后端差异），单条 SQL 同时完成"减 1"与"是否应删行"的判定，
     * 消除原先"decrement → getWrongCount 读回 → removeDifficultWord"三段独立取锁
     * 之间被并发 increment 插入导致的状态漂移。
     *
     * <p>返回语义变更：{@code false} = 计数已达 1（或行不存在）——调用方应删行
     * （{@link #removeDifficultWord}，幂等）；{@code true} = 已减 1 且仍 &gt; 0。
     * 残余乱序窗口（同一玩家的 increment 异步任务整体晚于本 decrement 执行）在
     * 调用方 {@code PlayerDataStore.decrementWrongCount} 注明，最坏结果 = 词留书
     * （可重试清除，无数据损坏）。
     */
    public boolean decrementWrongCount(UUID playerId, String word) {
        String sql = "UPDATE " + tablePrefix + "difficult_words SET wrong_count = wrong_count - 1 WHERE player_uuid = ? AND word = ? AND wrong_count > 1";
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
     * sessions 口径（P3-02）：学习日次 = 当日 player_sessions 行数（每人每日一行）。
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
     * Count total learning sessions.
     *
     * <p>P3-02 收口：原查 learning_log（历史遗留表，全库零写入），现改走
     * player_sessions。口径为"总学习日次"（per-player per-day 行数；
     * player_sessions 受 UNIQUE(uuid, session_date) UPSERT 合并，每人每日一行）。
     *
     * <p>Block C2（A5/R3-M4）双口径说明：本方法返回 player_sessions 行数
     * （**天数口径**）——管理报表/统计 HTTP 消费。如需**局数口径**（每次会话结束
     * +1、同日多局累计）走 {@code player_data.total_sessions}（内存侧
     * {@code PlayerDataStore.PlayerStats.getTotalSessions}，成就判定以该口径为准）。
     * 两值同屏展示（如 ProfileGUI 与统计面板）数值可不同，属既定口径差，非缺陷。
     */
    public int getTotalSessions() {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix + "player_sessions";
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
     * 数据全部来自 player_sessions（P3-02 收口：learning_log 为历史遗留表，
     * 全库零写入，不再参与统计）：players/wordsLearned 按 uuid/words_count 聚合；
     * sessions 按 session_date 分组计数。session 口径为"学习日次"——player_sessions
     * 每人每日一行，故每日 sessions 数值上等于当日 players。
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

        // P3-02 收口：每日会话数改走 player_sessions（learning_log 零写入历史遗留）。
        // session_date 为 LocalDate 字符串，与 7 天 key 同构可比（两后端同构先例：
        // PlayerDataStore.getRankedPlayersForPeriod）。
        String sessionsCountSql = "SELECT session_date, COUNT(*) as cnt "
                + "FROM " + tablePrefix + "player_sessions "
                + "WHERE session_date >= ? "
                + "GROUP BY session_date";
        synchronized (connectionLock) {
            // E-04：上一个锁块已释放，重连可能已替换引用，此处锁内重取。
            conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sessionsCountSql)) {
                stmt.setString(1, today.minusDays(6).toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String day = rs.getString("session_date");
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

    // ============================================================
    //  Square Social (Feed + Cheers) —— Wave 2 域 4
    // ============================================================

    /**
     * 一条动态流缓存行（{@code social_feed} 的读取投影 + JOIN 出的作者名）。
     * 只承载原始数据；语言侧渲染由 {@code SocialManager}/{@code FeedTextFormatter} 完成。
     */
    public record FeedRow(long id, UUID authorId, String authorName, String eventType,
                          int eventsCount, int sumCorrect, int sumTotal, String lastMode,
                          int lastStreak, String achievementId, int cheerCount, long updatedAt) { }

    /** {@link #cheerFeed} 的结果三态：写入成功 / 已鼓励过 / 条目已不存在。 */
    public enum CheerResult { OK, ALREADY, GONE }

    /**
     * 动态流写入（UPSERT 合并，无读-改-写）。
     *
     * <p>合并键 {@code UNIQUE(player_uuid, feed_date, event_type)}：同日同类型累加
     * {@code events_count} 与 SESSION_DONE 的 {@code sum_correct/sum_total}；覆写
     * "最近一次"列（{@code last_mode}/{@code last_streak}/{@code achievement_id}，
     * 用 COALESCE/IF 保护"本次为 null/0 时不抹掉旧值"的跨类型场景）；刷新
     * {@code updated_at}（排序键）。鼓励不改 updated_at，排序稳定。
     *
     * @param playerId      动态作者
     * @param eventType     SESSION_DONE / ACHIEVEMENT / CHECKIN
     * @param correctDelta  SESSION_DONE 本次答对（其余类型传 0）
     * @param totalDelta    SESSION_DONE 本次题数（其余类型传 0）
     * @param lastMode      SESSION_DONE 本次模式（其余类型传 null）
     * @param lastStreak    CHECKIN 连续天数（其余类型传 0）
     * @param achievementId ACHIEVEMENT 成就 id（其余类型传 null）
     */
    public void upsertFeedEvent(UUID playerId, String eventType, int correctDelta, int totalDelta,
                                String lastMode, int lastStreak, String achievementId) {
        String now = LocalDate.now().toString();
        long ts = System.currentTimeMillis();
        String sql;
        if (isSQLite()) {
            sql = "INSERT INTO " + tablePrefix + "social_feed (player_uuid, feed_date, event_type,"
                    + " events_count, sum_correct, sum_total, last_mode, last_streak, achievement_id,"
                    + " cheer_count, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, 0, ?, ?)"
                    + " ON CONFLICT(player_uuid, feed_date, event_type) DO UPDATE SET"
                    + " events_count = events_count + 1,"
                    + " sum_correct = sum_correct + excluded.sum_correct,"
                    + " sum_total = sum_total + excluded.sum_total,"
                    + " last_mode = COALESCE(excluded.last_mode, last_mode),"
                    + " last_streak = CASE WHEN excluded.last_streak > 0 THEN excluded.last_streak ELSE last_streak END,"
                    + " achievement_id = COALESCE(excluded.achievement_id, achievement_id),"
                    + " updated_at = excluded.updated_at";
        } else {
            sql = "INSERT INTO " + tablePrefix + "social_feed (player_uuid, feed_date, event_type,"
                    + " events_count, sum_correct, sum_total, last_mode, last_streak, achievement_id,"
                    + " cheer_count, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, 0, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE"
                    + " events_count = events_count + 1,"
                    + " sum_correct = sum_correct + VALUES(sum_correct),"
                    + " sum_total = sum_total + VALUES(sum_total),"
                    + " last_mode = COALESCE(VALUES(last_mode), last_mode),"
                    + " last_streak = IF(VALUES(last_streak) > 0, VALUES(last_streak), last_streak),"
                    + " achievement_id = COALESCE(VALUES(achievement_id), achievement_id),"
                    + " updated_at = VALUES(updated_at)";
        }
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, now);
                stmt.setString(3, eventType);
                stmt.setInt(4, Math.max(0, correctDelta));
                stmt.setInt(5, Math.max(0, totalDelta));
                stmt.setString(6, lastMode);
                stmt.setInt(7, lastStreak);
                stmt.setString(8, achievementId);
                stmt.setLong(9, ts);
                stmt.setLong(10, ts);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to upsert feed event " + eventType + " for " + playerId, e);
            }
        }
    }

    /**
     * 动态流缓存读取（FeedCache 刷新专用）。
     *
     * <p>读取侧隐私过滤与榜单同款：{@code LEFT JOIN player_profiles} +
     * {@code COALESCE(show_in_square,1) <> 0}（隐身者条目对所有人隐藏，含本人）。
     * {@code retentionDays <= 0} = 不设日期下限（永久保留）。作者名经
     * {@code LEFT JOIN player_data} 取（MAX() 包住兼容 ONLY_FULL_GROUP_BY；本方法
     * 可能在异步线程被调用，结果消费阶段不得调 Bukkit API），空回落 uuid 字符串。
     *
     * @param retentionDays 保留的自然日天数（含今天）；&lt;=0 表示不过滤
     * @param limit         返回行数上限（按 updated_at 倒序）
     */
    public List<FeedRow> getFeedCacheRows(int retentionDays, int limit) {
        List<FeedRow> rows = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT f.id, f.player_uuid, MAX(p.player_name) AS player_name,"
                + " f.event_type, f.events_count, f.sum_correct, f.sum_total, f.last_mode,"
                + " f.last_streak, f.achievement_id, f.cheer_count, f.updated_at"
                + " FROM " + tablePrefix + "social_feed f"
                + " LEFT JOIN " + tablePrefix + "player_data p ON p.uuid = f.player_uuid"
                + " LEFT JOIN " + tablePrefix + "player_profiles pf ON pf.uuid = f.player_uuid"
                + " WHERE COALESCE(pf.show_in_square, 1) <> 0");
        if (retentionDays > 0) {
            sql.append(" AND f.feed_date >= ?");
        }
        // GROUP BY 全列列表（不依赖 MySQL 函数依赖检测，SQLite/MySQL/MariaDB 全兼容）
        sql.append(" GROUP BY f.id, f.player_uuid, f.event_type, f.events_count, f.sum_correct,"
                + " f.sum_total, f.last_mode, f.last_streak, f.achievement_id, f.cheer_count, f.updated_at"
                + " ORDER BY f.updated_at DESC LIMIT ?");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql.toString())) {
                int idx = 1;
                if (retentionDays > 0) {
                    stmt.setString(idx++, feedRetentionCutoff(retentionDays));
                }
                stmt.setInt(idx, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        UUID authorId;
                        try {
                            authorId = UUID.fromString(rs.getString("player_uuid"));
                        } catch (IllegalArgumentException e) {
                            continue; // 脏行跳过，不拖垮整次缓存刷新
                        }
                        String name = rs.getString("player_name");
                        if (name == null || name.isEmpty()) {
                            name = authorId.toString();
                        }
                        rows.add(new FeedRow(
                                rs.getLong("id"),
                                authorId,
                                name,
                                rs.getString("event_type"),
                                rs.getInt("events_count"),
                                rs.getInt("sum_correct"),
                                rs.getInt("sum_total"),
                                rs.getString("last_mode"),
                                rs.getInt("last_streak"),
                                rs.getString("achievement_id"),
                                rs.getInt("cheer_count"),
                                rs.getLong("updated_at")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load feed cache rows", e);
            }
        }
        return rows;
    }

    /**
     * 保留窗口下端（含今天共 retentionDays 个自然日）：{@code today - (retentionDays - 1)}。
     * 读缓存（{@code >= cutoff}）与清理（{@code < cutoff}）共用同一口径。
     */
    private static String feedRetentionCutoff(int retentionDays) {
        return LocalDate.now().minusDays(Math.max(0, retentionDays - 1)).toString();
    }

    /**
     * 鼓励一条动态。
     *
     * <p>同一锁内三步（仿 {@link #deletePlayerData} 事务化）：① 锁内复核 feed 行存在
     * （单连接串行，无 TOCTOU；缺行 → GONE）；② {@code INSERT OR IGNORE / INSERT IGNORE}
     * 进 {@code social_cheers}（受影响行数 0 → ALREADY，唯一约束在 DB 层兜底一人一次）；
     * ③ 插入成功才 {@code cheer_count = cheer_count + 1}（去规范化计数）。
     * ②③ 包在 {@code setAutoCommit(false)} 事务里（仿 savePlayerData），连接状态不外泄。
     */
    public CheerResult cheerFeed(long feedId, UUID cheererUuid) {
        String insertSql = isSQLite()
                ? "INSERT OR IGNORE INTO " + tablePrefix + "social_cheers (feed_id, cheerer_uuid, created_at, read_by_owner) VALUES (?, ?, ?, 0)"
                : "INSERT IGNORE INTO " + tablePrefix + "social_cheers (feed_id, cheerer_uuid, created_at, read_by_owner) VALUES (?, ?, ?, 0)";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try {
                // ① feed 行存在性（锁内复核）
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT 1 FROM " + tablePrefix + "social_feed WHERE id = ?")) {
                    check.setLong(1, feedId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) {
                            return CheerResult.GONE;
                        }
                    }
                }
                conn.setAutoCommit(false);
                try {
                    // ② 唯一约束兜底"一人一条一次"
                    int inserted;
                    try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                        stmt.setLong(1, feedId);
                        stmt.setString(2, cheererUuid.toString());
                        stmt.setLong(3, System.currentTimeMillis());
                        inserted = stmt.executeUpdate();
                    }
                    if (inserted == 0) {
                        conn.rollback();
                        return CheerResult.ALREADY;
                    }
                    // ③ 去规范化计数 +1
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE " + tablePrefix + "social_feed SET cheer_count = cheer_count + 1 WHERE id = ?")) {
                        stmt.setLong(1, feedId);
                        stmt.executeUpdate();
                    }
                    conn.commit();
                    return CheerResult.OK;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to cheer feed " + feedId + " by " + cheererUuid, e);
                return CheerResult.GONE;
            }
        }
    }

    /**
     * 鼓励者名单（"谁鼓励了我"），按鼓励时间升序。名字 {@code LEFT JOIN player_data}
     * 取，空回落 uuid；本方法异步可达，结果消费阶段不得调 Bukkit API。
     */
    public List<String> getCheerers(long feedId, int limit) {
        List<String> names = new ArrayList<>();
        String sql = "SELECT c.cheerer_uuid, MAX(p.player_name) AS player_name"
                + " FROM " + tablePrefix + "social_cheers c"
                + " LEFT JOIN " + tablePrefix + "player_data p ON p.uuid = c.cheerer_uuid"
                + " WHERE c.feed_id = ?"
                + " GROUP BY c.cheerer_uuid, c.created_at"
                + " ORDER BY c.created_at ASC LIMIT ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setLong(1, feedId);
                stmt.setInt(2, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("player_name");
                        if (name == null || name.isEmpty()) {
                            name = rs.getString("cheerer_uuid");
                        }
                        names.add(name);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load cheerers for feed " + feedId, e);
            }
        }
        return names;
    }

    /**
     * 把该玩家所有动态行上的鼓励标记为已读（进服摘要发出后调用）。
     * 子查询只涉及 {@code social_feed}（非被更新表），无 MySQL 更新-子查询自引用限制。
     */
    public void markFeedCheersRead(UUID ownerUuid) {
        // 隐蔽语义（plan-social §1.6 边界决策）：摘要只发给作者本人，不向第三方泄露；
        // 隐身期间收到的鼓励照常提醒。
        String sql = "UPDATE " + tablePrefix + "social_cheers SET read_by_owner = 1"
                + " WHERE feed_id IN (SELECT id FROM " + tablePrefix + "social_feed WHERE player_uuid = ?)";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, ownerUuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to mark feed cheers read for " + ownerUuid, e);
            }
        }
    }

    /**
     * 标记单条鼓励已读（作者在线即时提醒发出后调用）。
     * 作者恰在"提醒后立即退服"间隙的极小竞态允许下次进服重复提醒一次（宽容语义）。
     */
    public void markSingleCheerRead(long feedId, UUID cheererUuid) {
        String sql = "UPDATE " + tablePrefix + "social_cheers SET read_by_owner = 1"
                + " WHERE feed_id = ? AND cheerer_uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setLong(1, feedId);
                stmt.setString(2, cheererUuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to mark single cheer read (feed " + feedId + ")", e);
            }
        }
    }

    /** 该玩家所有动态上未读（{@code read_by_owner=0}）的鼓励总数（进服摘要；走 idx_cheers_feed）。 */
    public int countUnreadCheers(UUID ownerUuid) {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix + "social_cheers c"
                + " JOIN " + tablePrefix + "social_feed f ON f.id = c.feed_id"
                + " WHERE f.player_uuid = ? AND c.read_by_owner = 0";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, ownerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to count unread cheers for " + ownerUuid, e);
                return 0;
            }
        }
    }

    /**
     * "你已鼓励"集合（广场打开路径上**唯一**允许的同步查询，plan-social §3.2）：
     * {@code WHERE cheerer_uuid = ? AND feed_id IN (≤28 ids)} 走 UNIQUE(feed_id,
     * cheerer_uuid) 索引，量级与既有 getPlayerStats 兜底同级。有界输入（调用方传
     * 本页 id 列表），空输入直接返回空集不查库。
     */
    public Set<Long> getCheeredFeedIds(UUID viewerId, List<Long> feedIds) {
        Set<Long> result = new java.util.HashSet<>();
        if (feedIds == null || feedIds.isEmpty()) {
            return result;
        }
        StringBuilder sql = new StringBuilder("SELECT feed_id FROM " + tablePrefix
                + "social_cheers WHERE cheerer_uuid = ? AND feed_id IN (");
        for (int i = 0; i < feedIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")");
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql.toString())) {
                stmt.setString(1, viewerId.toString());
                for (int i = 0; i < feedIds.size(); i++) {
                    stmt.setLong(i + 2, feedIds.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(rs.getLong(1));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load cheered feed ids for " + viewerId, e);
            }
        }
        return result;
    }

    /**
     * 鼓励统计三路聚合行（Wave 2 冻结接口 {@code SocialManager.getCheerStats} 的数据源）：
     * {@code given}=累计发出鼓励数（cheerer_uuid 索引）；{@code distinctTargets}=发出对象
     * 去重数（JOIN social_feed 按作者去重，P3-3 互助成就判据）；{@code received}=收到
     * 鼓励总数。一条 SQL 三子查询，锁内单次执行。
     */
    public record CheerStatsRow(int given, int distinctTargets, int received) { }

    public CheerStatsRow getCheerStatsRow(UUID playerId) {
        String id = playerId.toString();
        String sql = "SELECT"
                + " (SELECT COUNT(*) FROM " + tablePrefix + "social_cheers WHERE cheerer_uuid = ?) AS given,"
                + " (SELECT COUNT(DISTINCT f.player_uuid) FROM " + tablePrefix + "social_cheers c"
                + "    JOIN " + tablePrefix + "social_feed f ON f.id = c.feed_id"
                + "    WHERE c.cheerer_uuid = ?) AS distinct_targets,"
                + " (SELECT COUNT(*) FROM " + tablePrefix + "social_cheers c"
                + "    JOIN " + tablePrefix + "social_feed f ON f.id = c.feed_id"
                + "    WHERE f.player_uuid = ?) AS received";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, id);
                stmt.setString(3, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new CheerStatsRow(rs.getInt("given"),
                                rs.getInt("distinct_targets"), rs.getInt("received"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load cheer stats for " + playerId, e);
            }
        }
        return new CheerStatsRow(0, 0, 0);
    }

    /**
     * 清理过期动态：先删过期 feed 行上的 cheers（子查询先于 feed 行删除——顺序敏感），
     * 再删 feed 行；两删同一事务。{@code retentionDays <= 0} = 永久保留（no-op）。
     * cutoff 口径与 {@link #getFeedCacheRows} 一致。
     */
    public void cleanupOldFeedEvents(int retentionDays) {
        if (retentionDays <= 0) {
            return; // 永久保留（不推荐，注释见 config.yml social.feed.retention-days）
        }
        String cutoff = feedRetentionCutoff(retentionDays);
        String deleteCheers = "DELETE FROM " + tablePrefix + "social_cheers"
                + " WHERE feed_id IN (SELECT id FROM " + tablePrefix + "social_feed WHERE feed_date < ?)";
        String deleteFeed = "DELETE FROM " + tablePrefix + "social_feed WHERE feed_date < ?";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try {
                conn.setAutoCommit(false);
                try {
                    int cheersDeleted;
                    try (PreparedStatement stmt = conn.prepareStatement(deleteCheers)) {
                        stmt.setString(1, cutoff);
                        cheersDeleted = stmt.executeUpdate();
                    }
                    int feedDeleted;
                    try (PreparedStatement stmt = conn.prepareStatement(deleteFeed)) {
                        stmt.setString(1, cutoff);
                        feedDeleted = stmt.executeUpdate();
                    }
                    conn.commit();
                    if (feedDeleted > 0) {
                        plugin.getLogger().info("Cleaned up " + feedDeleted
                                + " old feed events (+" + cheersDeleted + " cheers) older than "
                                + cutoff + ".");
                    }
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clean up old feed events", e);
            }
        }
    }

    // ============================================================
    //  Season settlement ledger (seasons + season_rewards) —— Wave 3 域 5 批次 A
    // ============================================================

    /**
     * 赛季结算头行（{@code seasons} 表的读取投影）。
     *
     * @param seasonId    赛季号（1-based，锚点纯函数推导）
     * @param startDate   yyyy-MM-dd 字符串（与 session_date 同构）
     * @param endDate     yyyy-MM-dd 字符串（闭区间）
     * @param settledAt   结算时间戳；null = 未结算
     * @param topSnapshot 结算时 top10 JSON（回顾用；未结算为 null）
     */
    public record SeasonRow(int seasonId, String startDate, String endDate,
                            Long settledAt, String topSnapshot) { }

    /**
     * 赛季奖励台账行（{@code season_rewards} 的读取投影）。
     *
     * <p>放在 DatabaseManager 内嵌（而非 SeasonManager）的理由：DB 方法参数/返回
     * 用本类型即可，两个类零互相依赖（SeasonManager → DatabaseManager 单向），
     * 与既有 {@link FeedRow} / {@link CheerStatsRow} 惯例一致。
     *
     * @param seasonId     赛季号
     * @param uuid         获奖玩家
     * @param rankPosition 名次；0 = 参与奖（未上榜）
     * @param score        窗口内学习分
     * @param rewardCents  奖励金额，单位"分"（对齐 RewardEngine 厘级记账）
     */
    public record SeasonRewardRow(int seasonId, UUID uuid, int rankPosition,
                                  long score, long rewardCents) { }

    /**
     * 建立赛季行（幂等，双后端对照）：SQLite {@code INSERT OR IGNORE} /
     * MySQL {@code INSERT IGNORE}（F2-01 教训，不做裸 INSERT）。已存在（含已结算）
     * 的行不被覆盖——结算抢占只动 {@code settled_at} 列。
     */
    public void ensureSeasonRow(int seasonId, LocalDate start, LocalDate end) {
        String sql = (isSQLite()
                ? "INSERT OR IGNORE INTO "
                : "INSERT IGNORE INTO ") + tablePrefix + "seasons"
                + " (season_id, start_date, end_date, settled_at, top_snapshot) VALUES (?, ?, ?, NULL, NULL)";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, seasonId);
                stmt.setString(2, start.toString());
                stmt.setString(3, end.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to ensure season row " + seasonId, e);
            }
        }
    }

    /**
     * 结算抢占（多服单发保护）：条件 UPDATE {@code WHERE season_id=? AND settled_at IS NULL}。
     * {@code executeUpdate()==1} = 本服获得结算权（唯一赢家）；0 = 他服已抢先或行不存在。
     * 无后端差异（纯 UPDATE）。
     */
    public boolean tryClaimSettlement(int seasonId, long settledAt, String topSnapshot) {
        String sql = "UPDATE " + tablePrefix + "seasons"
                + " SET settled_at = ?, top_snapshot = ?"
                + " WHERE season_id = ? AND settled_at IS NULL";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setLong(1, settledAt);
                stmt.setString(2, topSnapshot);
                stmt.setInt(3, seasonId);
                return stmt.executeUpdate() == 1;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to claim season settlement for " + seasonId, e);
                return false;
            }
        }
    }

    /**
     * 批量写入赛季奖励台账（抢占者唯一，直接 INSERT 无冲突；单事务）。
     * 空列表 no-op。
     */
    public void insertSeasonRewards(List<SeasonRewardRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + tablePrefix + "season_rewards"
                + " (season_id, uuid, rank_position, score, reward_coins, claimed, claimed_at)"
                + " VALUES (?, ?, ?, ?, ?, 0, NULL)";
        synchronized (connectionLock) {
            Connection conn = getConnection();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (SeasonRewardRow row : rows) {
                        stmt.setInt(1, row.seasonId());
                        stmt.setString(2, row.uuid().toString());
                        stmt.setInt(3, row.rankPosition());
                        stmt.setLong(4, row.score());
                        stmt.setLong(5, row.rewardCents());
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to insert season reward rows (" + rows.size() + ")", e);
            }
        }
    }

    /**
     * 该玩家全部未领取的台账行（join 补发 / 结算即时发放的数据源），按赛季升序。
     * 走 {@code idx_season_rewards_uuid}。
     */
    public List<SeasonRewardRow> getPendingSeasonRewards(UUID playerId) {
        List<SeasonRewardRow> rows = new ArrayList<>();
        String sql = "SELECT season_id, uuid, rank_position, score, reward_coins"
                + " FROM " + tablePrefix + "season_rewards"
                + " WHERE uuid = ? AND claimed = 0"
                + " ORDER BY season_id ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new SeasonRewardRow(
                                rs.getInt("season_id"),
                                UUID.fromString(rs.getString("uuid")),
                                rs.getInt("rank_position"),
                                rs.getLong("score"),
                                rs.getLong("reward_coins")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load pending season rewards for " + playerId, e);
            }
        }
        return rows;
    }

    /**
     * Block C2（A7/R7-M1 配对，供 C2-C 结算批量发放消费）：多名玩家全部未领取的
     * 台账行，按 player_uuid 分组（仅含至少一行 pending 的玩家）。
     *
     * <p>IN 占位符展开照 {@code PlayerDataStore.getRankedPlayersForPeriodFiltered}
     * 先例；{@code claimed} 条件与 {@link #getPendingSeasonRewards} 同式。
     * SQLite 变量上限 999 —— 集合 &gt; 500 时分批查询合并（每批独立 SELECT，
     * 结果并集语义与单查等价）。空集合 → 空 map。
     *
     * <p>异常 → 空 map + WARNING（静默降级 = 发放推迟到 join 路径，与
     * deliverPending 现有"秒退留待下次"语义同族）。
     *
     * @param playerIds 待查玩家（在线 UUID 快照；null/空 → 空 map）
     * @return player_uuid → 该玩家 pending 行列表（按赛季升序，与单查排序一致）
     */
    public Map<UUID, List<SeasonRewardRow>> getPendingSeasonRewardsFor(Collection<UUID> playerIds) {
        Map<UUID, List<SeasonRewardRow>> byPlayer = new LinkedHashMap<>();
        if (playerIds == null || playerIds.isEmpty()) {
            return byPlayer;
        }
        // SQLite 999 变量上限防御：>500 分批（每批一次 SELECT，并集合并）
        List<UUID> idList = new ArrayList<>(playerIds);
        final int batchSize = 500;
        for (int start = 0; start < idList.size(); start += batchSize) {
            List<UUID> batch = idList.subList(start, Math.min(start + batchSize, idList.size()));
            StringBuilder inClause = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) {
                    inClause.append(", ");
                }
                inClause.append("?");
            }
            String sql = "SELECT season_id, uuid, rank_position, score, reward_coins"
                    + " FROM " + tablePrefix + "season_rewards"
                    + " WHERE uuid IN (" + inClause + ") AND claimed = 0"
                    + " ORDER BY season_id ASC";
            synchronized (connectionLock) {
                try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                    int idx = 1;
                    for (UUID id : batch) {
                        stmt.setString(idx++, id.toString());
                    }
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            byPlayer.computeIfAbsent(uuid, k -> new ArrayList<>())
                                    .add(new SeasonRewardRow(
                                            rs.getInt("season_id"),
                                            uuid,
                                            rs.getInt("rank_position"),
                                            rs.getLong("score"),
                                            rs.getLong("reward_coins")
                                    ));
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to batch load pending season rewards (" + batch.size()
                                    + " players in batch)", e);
                    return new LinkedHashMap<>(); // 静默降级：推迟到 join 路径
                }
            }
        }
        return byPlayer;
    }

    /**
     * 条件标记已领取：{@code WHERE season_id=? AND uuid=? AND claimed=0}。
     * {@code executeUpdate()==1} = 本路径抢到发放权（调用方随后才发钱——对齐
     * CheckInManager "先标记后付款"先例，R-02 双击重发教训）。
     */
    public boolean markSeasonRewardClaimed(int seasonId, UUID playerId) {
        return setSeasonRewardClaimed(seasonId, playerId, true);
    }

    /**
     * W3A tester M1 收口：付款失败回滚——把 {@code claimed} 从 1 改回 0（仅当
     * 当前确为 1），使该行回到 pending、下次 join/结算路径可重试。与
     * {@link #markSeasonRewardClaimed} 同为条件 UPDATE（竞态安全）。
     */
    public boolean unmarkSeasonRewardClaimed(int seasonId, UUID playerId) {
        return setSeasonRewardClaimed(seasonId, playerId, false);
    }

    private boolean setSeasonRewardClaimed(int seasonId, UUID playerId, boolean claimed) {
        String sql = "UPDATE " + tablePrefix + "season_rewards"
                + " SET claimed = ?, claimed_at = ?"
                + " WHERE season_id = ? AND uuid = ? AND claimed = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                int target = claimed ? 1 : 0;
                int expected = claimed ? 0 : 1;
                stmt.setInt(1, target);
                stmt.setLong(2, claimed ? System.currentTimeMillis() : 0L);
                stmt.setInt(3, seasonId);
                stmt.setString(4, playerId.toString());
                stmt.setInt(5, expected);
                return stmt.executeUpdate() == 1;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to update season reward claimed=" + claimed
                                + " (" + seasonId + "/" + playerId + ")", e);
                return false;
            }
        }
    }

    /** 读已结算的赛季行（top_snapshot 回顾；不存在/未结算返回 null）。 */
    public SeasonRow getSettledSeason(int seasonId) {
        String sql = "SELECT season_id, start_date, end_date, settled_at, top_snapshot"
                + " FROM " + tablePrefix + "seasons"
                + " WHERE season_id = ? AND settled_at IS NOT NULL";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, seasonId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        long settled = rs.getLong("settled_at");
                        Long settledAt = rs.wasNull() ? null : settled;
                        return new SeasonRow(
                                rs.getInt("season_id"),
                                rs.getString("start_date"),
                                rs.getString("end_date"),
                                settledAt,
                                rs.getString("top_snapshot")
                        );
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load settled season " + seasonId, e);
            }
        }
        return null;
    }

    /**
     * seasons 表最小 season_id（首次启用语义判定：null = 表空）。行只会由
     * {@code SeasonManager.settleIfNeededAsync} 建立，最小值即"启用时刻的季"。
     */
    public Integer minSeasonId() {
        String sql = "SELECT MIN(season_id) FROM " + tablePrefix + "seasons";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int min = rs.getInt(1);
                    return rs.wasNull() ? null : min;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query min season id", e);
            }
        }
        return null;
    }

    /** 最大已结算 season_id（catch-up 游标；null = 尚无已结算季）。 */
    public Long maxSettledSeasonId() {
        String sql = "SELECT MAX(season_id) FROM " + tablePrefix + "seasons"
                + " WHERE settled_at IS NOT NULL";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long max = rs.getLong(1);
                    return rs.wasNull() ? null : max;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query max settled season id", e);
            }
        }
        return null;
    }

    /**
     * 窗口内有会话记录的玩家 uuid（参与奖判据，plan-season §1.4）。
     * 读索引 {@code idx_sessions_date} 的日期范围扫；含隐身者（奖励是发放语义，
     * 不受展示开关约束——与榜单展示过滤口径不同）。
     */
    public Set<UUID> getSessionParticipants(LocalDate since, LocalDate until) {
        Set<UUID> ids = new java.util.HashSet<>();
        String sql = "SELECT DISTINCT uuid FROM " + tablePrefix + "player_sessions"
                + " WHERE session_date >= ? AND session_date <= ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, since.toString());
                stmt.setString(2, until.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            ids.add(UUID.fromString(rs.getString("uuid")));
                        } catch (IllegalArgumentException e) {
                            // 脏行跳过，不拖垮整次结算
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load season participants for window", e);
            }
        }
        return ids;
    }

    /**
     * Delete every row that belongs to one player across all player-scoped tables.
     *
     * <p>R-03：{@code /lea reset} 的删表实现（原实现只删 4 表，榜单/签到/挑战/错题/
     * AI 历史全部残留）。uuid 系 9 表 + player_uuid 系 4 表共 13 表：
     * player_data、word_progress、achievements、player_achievements（死表 E-03，
     * 删了无害，免残行未来被误接线复活）、learning_log、player_sessions、
     * player_profiles、daily_challenges、season_rewards（Wave 3 域 5 台账）、
     * daily_challenge_words、ai_chat_history、difficult_words、checkin_data。
     *
     * <p>建表语句无外键（SQLite/MySQL 双实现与 schema.sql 均已查证），直删无需排序。
     * 与本类其他方法一致：全程持 {@code connectionLock}，内部吞异常记 WARNING，
     * 调用方无需再 try/catch。
     *
     * <p>F5-03（备查）：已知极端竞态——在途异步 auto-save 快照（周期保存任务 /
     * persistLongestStreak / recordCoinsEarned 等即时落库）可能在 delete 后写回
     * 残行（毫秒窗口；玩家重进时数据正常覆盖，影响极小）。如需根治需引入
     * resetting per-player 标志（本表标记删中，写侧见此跳过），F5-03 备查。
     *
     * @param playerId the player whose rows should be removed
     */
    public void deletePlayerData(UUID playerId) {
        String[] uuidTables = {
            "player_data", "word_progress", "achievements", "player_achievements",
            "learning_log", "player_sessions", "player_profiles", "daily_challenges",
            "season_rewards", // Wave 3 域 5：赛季发放台账（uuid 系；seasons 表无玩家列不动）
            "class_members" // Wave 4 批次 4A：班级成员关系（uuid 系；classes 表无玩家列不动）
        };
        // 这 4 表的玩家列叫 player_uuid，不是 uuid（见 createTables 建表语句）
        // US-FB（Wave 3 批次 3B）：ai_chat_feedback 一并清收（防 reset 残行）。
        String[] playerUuidTables = {
            "daily_challenge_words", "ai_chat_history", "difficult_words", "checkin_data",
            "ai_chat_feedback",
            "daily_word_reward_claims", // 修复批 A（R10-H1）：挑战奖励已付台账（player_uuid 系，防后建表盲区）
            "ai_round_counters" // Block C2（R2-M2）：今日 AI 轮次计数器（player_uuid 系，防后建表盲区）
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
            // Wave 2 域 4（广场社交）：social_feed / social_cheers 加入 R-03 清收。
            // 顺序敏感三步（先删系他人的行，再删系自己的行）：
            //   ① social_cheers 里该玩家**发出**的鼓励（列名 cheerer_uuid——第三套玩家列名）；
            //   ② 该玩家 feed 行上**他人给出**的鼓励（必须先于 ③，否则子查询无行可命中，
            //      留下指向已删 feed 的孤儿 cheers 行）；
            //   ③ 该玩家的 feed 行。
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM " + tablePrefix + "social_cheers WHERE cheerer_uuid = ?")) {
                stmt.setString(1, id);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to delete social_cheers given by " + playerId, e);
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM " + tablePrefix + "social_cheers WHERE feed_id IN"
                            + " (SELECT id FROM " + tablePrefix + "social_feed WHERE player_uuid = ?)")) {
                stmt.setString(1, id);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to delete social_cheers on feed rows of " + playerId, e);
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM " + tablePrefix + "social_feed WHERE player_uuid = ?")) {
                stmt.setString(1, id);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to delete social_feed rows of " + playerId, e);
            }
        }
    }

    // ============================================================
    //  Classes（US-CLS-1，Wave 4 批次 4A）
    // ============================================================

    /**
     * 班级头行（{@code classes} 表的读取投影）。
     *
     * @param classId     自增主键（成员表以此为关联键）
     * @param className   业务键（UNIQUE，命令与查询一律按班名）
     * @param displayName 展示名（建班时默认等于 className；预留改名能力）
     * @param createdBy   创建者 UUID 字符串
     * @param createdAt   创建时间戳（epoch 毫秒）
     */
    public static record ClassRow(int classId, String className, String displayName,
                                  String createdBy, long createdAt) { }

    /**
     * 班级成员行（{@code class_members} LEFT JOIN {@code player_data} 的读取投影）。
     *
     * @param classId    所属班级
     * @param uuid       成员 UUID
     * @param playerName player_data 的名字；查不到（LEFT JOIN 空）回落 uuid 字符串
     * @param joinedAt   加入时间戳（epoch 毫秒）
     */
    public static record ClassMemberRow(int classId, UUID uuid, String playerName, long joinedAt) { }

    /**
     * US-CLS-1：建班。{@code class_name} 为业务键（UNIQUE），重名返回 false。
     *
     * <p>先查后插（同锁内；多服共享 MySQL 时并发插入由 UNIQUE 约束兜底，
     * 冲突同样归为 false）。display_name 默认取 className（预留改名能力）。
     */
    public boolean createClass(String className, UUID createdBy, long createdAt) {
        synchronized (connectionLock) {
            if (getClassByName(className) != null) {
                return false; // 重名（调用方据此提示 class.name-exists）
            }
            String sql = "INSERT INTO " + tablePrefix + "classes"
                    + " (class_name, display_name, created_by, created_at) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, className);
                stmt.setString(2, className);
                stmt.setString(3, createdBy.toString());
                stmt.setLong(4, createdAt);
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                // UNIQUE 冲突（多服并发建同名班）或连接故障；两种都归 false 不抛
                plugin.getLogger().log(Level.WARNING,
                        "Failed to create class " + className, e);
                return false;
            }
        }
    }

    /**
     * US-CLS-1：删班。不存在返回 false。先删成员行再删班级行（无外键，排序防孤儿）；
     * 成员行删除失败即中止（不留下指向已删班级的孤儿成员行）。
     */
    public boolean deleteClass(String className) {
        synchronized (connectionLock) {
            // 4A tester L-2 收口：查名+删行同锁内（原锁外查+锁内删存在 TOCTOU——
            // 共享 MySQL 双服并发删同一班时旧实现可能"成员删了、班已不在"）。
            // 锁可重入，getClassByName 内部自身取锁安全。
            ClassRow row = getClassByName(className);
            if (row == null) {
                return false;
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(
                    "DELETE FROM " + tablePrefix + "class_members WHERE class_id = ?")) {
                stmt.setInt(1, row.classId());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to delete members of class " + className, e);
                return false;
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(
                    "DELETE FROM " + tablePrefix + "classes WHERE class_id = ?")) {
                stmt.setInt(1, row.classId());
                // 影响 0 行 = 班级已被并发删除（TOCTOU 后窗）——报 false 而非虚报成功
                if (stmt.executeUpdate() == 0) {
                    return false;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to delete class " + className, e);
                return false;
            }
        }
        return true;
    }

    /**
     * US-CLS-1：按班名取班级头（null = 不存在）。
     */
    public ClassRow getClassByName(String className) {
        String sql = "SELECT class_id, class_name, display_name, created_by, created_at FROM "
                + tablePrefix + "classes WHERE class_name = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, className);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new ClassRow(rs.getInt("class_id"), rs.getString("class_name"),
                                rs.getString("display_name"), rs.getString("created_by"),
                                rs.getLong("created_at"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load class by name: " + className, e);
            }
        }
        return null;
    }

    /**
     * US-CLS-1：全部班级（建班顺序）。list 命令与补全消费。
     */
    public List<ClassRow> listClasses() {
        List<ClassRow> classes = new ArrayList<>();
        String sql = "SELECT class_id, class_name, display_name, created_by, created_at FROM "
                + tablePrefix + "classes ORDER BY class_id ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    classes.add(new ClassRow(rs.getInt("class_id"), rs.getString("class_name"),
                            rs.getString("display_name"), rs.getString("created_by"),
                            rs.getLong("created_at")));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to list classes", e);
            }
        }
        return classes;
    }

    /**
     * US-CLS-1：班级成员（LEFT JOIN player_data 取名；player_name 空回落 uuid 字符串）。
     *
     * <p>ResultSet 关闭后再组装（UUID 解析/名字回落为纯内存操作，不持语句句柄）；
     * 脏 uuid 行跳过不拖垮整列表。
     */
    public List<ClassMemberRow> getClassMembers(int classId) {
        List<Object[]> raw = new ArrayList<>();
        String sql = "SELECT m.class_id, m.uuid, m.joined_at, p.player_name FROM "
                + tablePrefix + "class_members m LEFT JOIN " + tablePrefix + "player_data p"
                + " ON p.uuid = m.uuid WHERE m.class_id = ? ORDER BY m.joined_at ASC";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, classId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        raw.add(new Object[]{
                                rs.getInt("class_id"), rs.getString("uuid"),
                                rs.getString("player_name"), rs.getLong("joined_at")
                        });
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load members of class " + classId, e);
            }
        }
        List<ClassMemberRow> members = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            String uuidStr = (String) r[1];
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                continue; // 脏行跳过，不拖垮整列表
            }
            String name = (String) r[2];
            if (name == null || name.isBlank()) {
                name = uuidStr;
            }
            members.add(new ClassMemberRow((int) r[0], uuid, name, (long) r[3]));
        }
        return members;
    }

    /**
     * US-CLS-1：玩家所在班级（一人一班由调用方编排；null = 无班）。
     */
    public Integer getPlayerClassId(UUID uuid) {
        String sql = "SELECT class_id FROM " + tablePrefix
                + "class_members WHERE uuid = ? LIMIT 1";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("class_id");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load class of player " + uuid, e);
            }
        }
        return null;
    }

    /**
     * US-CLS-1：加成员。已在班内返回 false（幂等由调用方提示）。
     * 换班语义（退旧班 + 进新班）由调用方编排——Data 层只管单班成员行。
     */
    public boolean addClassMember(int classId, UUID uuid, long joinedAt) {
        synchronized (connectionLock) {
            try (PreparedStatement check = getConnection().prepareStatement(
                    "SELECT 1 FROM " + tablePrefix
                            + "class_members WHERE class_id = ? AND uuid = ?")) {
                check.setInt(1, classId);
                check.setString(2, uuid.toString());
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false; // 已在班内
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to check class membership for " + uuid, e);
                return false;
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(
                    "INSERT INTO " + tablePrefix
                            + "class_members (class_id, uuid, joined_at) VALUES (?, ?, ?)")) {
                stmt.setInt(1, classId);
                stmt.setString(2, uuid.toString());
                stmt.setLong(3, joinedAt);
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                // PK 冲突（多服并发加同人）或连接故障；两种都归 false 不抛
                plugin.getLogger().log(Level.WARNING,
                        "Failed to add member " + uuid + " to class " + classId, e);
                return false;
            }
        }
    }

    /**
     * US-CLS-1：移出成员。不在班返回 false。
     */
    public boolean removeClassMember(int classId, UUID uuid) {
        String sql = "DELETE FROM " + tablePrefix
                + "class_members WHERE class_id = ? AND uuid = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, classId);
                stmt.setString(2, uuid.toString());
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to remove member " + uuid + " from class " + classId, e);
                return false;
            }
        }
    }

    /**
     * US-CLS-1：班级人数（/lea class add 满员判定；0 = 空班或班不存在）。
     */
    public int getClassMemberCount(int classId) {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix
                + "class_members WHERE class_id = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setInt(1, classId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to count members of class " + classId, e);
            }
        }
        return 0;
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
