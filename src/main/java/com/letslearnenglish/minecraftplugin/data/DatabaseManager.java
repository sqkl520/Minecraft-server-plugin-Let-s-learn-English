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
import java.util.Collections;
import java.util.HashSet;
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
        // P0-1：驱动已随包 shade（pom 的 maven-shade-plugin），但 Bukkit/Paper 用独立的
        // 插件 ClassLoader，JDBC 4 的服务自动发现依赖线程上下文类加载器，未必能看见
        // META-INF/services/java.sql.Driver。显式加载一次可把失败点从晦涩的
        // "No suitable driver found" 提前成一条可读的错误。
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver (com.mysql.cj.jdbc.Driver) is not available. "
                    + "It should be bundled in this plugin's jar; if you built the plugin yourself, "
                    + "check that the maven-shade-plugin execution ran (see README 'Build').", e);
        }

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
                        "  sounds_enabled BOOLEAN DEFAULT 1," +
                        "  reminders_enabled BOOLEAN DEFAULT 1" +
                        ")"
        );
        // Phase 3 S-A：per-player 音效开关（默认开；plan-audio-feedback §3.1）
        addColumnIfMissing(tablePrefix + "player_profiles", "sounds_enabled", "BOOLEAN DEFAULT 1");
        // R1/R4：per-player 提醒总开关（默认开 —— 保持既有玩家上线行为不变；老库零迁移补列）
        addColumnIfMissing(tablePrefix + "player_profiles", "reminders_enabled", "BOOLEAN DEFAULT 1");

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

        // R10 战略锐评（决斗零持久化）：决斗战绩台账 —— 一场一行的追加式事实表。
        // 原实现只有内存 DuelRoom，赢家只拿一个成就、无战绩/胜率/连胜可查，
        // 也无"复仇"的对手关系可回溯。本表只追加（无 UPDATE/DELETE 业务路径），
        // 保留期 365 天（决斗数据量极小，见 cleanupOldChatHistory）。
        // 双后端 DDL 分支：SQLite INTEGER PRIMARY KEY AUTOINCREMENT /
        // MySQL BIGINT AUTO_INCREMENT PRIMARY KEY（同 word_progress 等表的 isSQLite 分支写法）。
        // duel_date 存 yyyy-MM-dd 字符串（与 daily_word_reward_claims 的 DATE 列不同：
        // 本表由 recordDuelResult 用 LocalDate.now() 显式写入，字符串在两后端可精确比较）。
        executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePrefix + "duel_stats (" +
                        "  id " + (isSQLite()
                                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                                : "BIGINT AUTO_INCREMENT PRIMARY KEY") + "," +
                        "  winner_uuid VARCHAR(36) NOT NULL," +
                        "  loser_uuid VARCHAR(36) NOT NULL," +
                        "  winner_score INT NOT NULL," +
                        "  loser_score INT NOT NULL," +
                        "  duel_date VARCHAR(10) NOT NULL," +
                        "  created_at BIGINT NOT NULL" +
                        ")"
        );
        // 赢/输两侧各一条 (uuid, duel_date) 复合索引 —— 供"我的战绩/胜率/连胜"
        // 按人 + 日期窗查询（含 OR 两列的战绩聚合走 index merge / 两次索引扫）。
        // 经 createIndexIfMissing（PF-SEA-02：MySQL 不支持 CREATE INDEX IF NOT EXISTS）。
        createIndexIfMissing("idx_duel_winner_date",
                "duel_stats(winner_uuid, duel_date)");
        createIndexIfMissing("idx_duel_loser_date",
                "duel_stats(loser_uuid, duel_date)");
        createIndexIfMissing("idx_duel_created",
                "duel_stats(created_at)");

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
     *
     * <p>修复批 D（R3-L2）：天数经占位符绑定（原为字符串拼接；唯一调用传常量 30
     * 无注入面，此处对齐全仓参数化惯例）——SQLite 绑字符串 modifier（"-N days"）、
     * MySQL 绑 int，见 {@link #bindKeepDays}。
     */
    public void cleanupOldChatHistory(int daysToKeep) {
        String dateExpr;
        if (isSQLite()) {
            dateExpr = "datetime('now', ?)";
        } else {
            dateExpr = "DATE_SUB(NOW(), INTERVAL ? DAY)";
        }
        String sql = "DELETE FROM " + tablePrefix + "ai_chat_history"
                + " WHERE timestamp < " + dateExpr;
        String sql2 = "DELETE FROM " + tablePrefix + "ai_chat_feedback WHERE created_at < " + dateExpr;
        // Block C2（A1/R2-M2）：ai_round_counters 旧行同窗口清理（round_date 为纯日期，
        // 与日期阈值精确字典序可比——SQLite date('now') / MySQL CURDATE() 两后端对照）
        // ——新表必须有清理者，先例 R3-H2。
        String roundDateExpr = isSQLite()
                ? "date('now', ?)"
                : "DATE_SUB(CURDATE(), INTERVAL ? DAY)";
        String sql3 = "DELETE FROM " + tablePrefix + "ai_round_counters"
                + " WHERE round_date < " + roundDateExpr;
        // R10：决斗战绩台账保留期固定 365 天（不随调用方 daysToKeep 走——唯一调用点
        // 传的是 AI 历史的 30 天）。created_at 是 epoch 毫秒，阈值在 Java 侧算好直接
        // 绑 long，天然免去两后端日期函数的方言差异与库内时区偏移。
        String sql4 = "DELETE FROM " + tablePrefix + "duel_stats WHERE created_at < ?";
        long duelCutoff = System.currentTimeMillis() - DUEL_STATS_RETENTION_DAYS * 24L * 60L * 60L * 1000L;
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                bindKeepDays(stmt, daysToKeep);
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
                bindKeepDays(stmt, daysToKeep);
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
                bindKeepDays(stmt, daysToKeep);
                int deletedRounds = stmt.executeUpdate();
                if (deletedRounds > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedRounds
                            + " old AI round counter records (older than " + daysToKeep + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old AI round counters", e);
            }
            try (PreparedStatement stmt = getConnection().prepareStatement(sql4)) {
                stmt.setLong(1, duelCutoff);
                int deletedDuels = stmt.executeUpdate();
                if (deletedDuels > 0) {
                    plugin.getLogger().info("Cleaned up " + deletedDuels
                            + " old duel stats records (older than "
                            + DUEL_STATS_RETENTION_DAYS + " days).");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to clean up old duel stats", e);
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
     * R7-B ③：一次查询取回「今日挑战词集合 + 已完成集合 + 奖励台账是否已发」三份数据，
     * 供 {@code DailyChallengeManager} 的 per-player 每日缓存加载（把答对路径上原本
     * 每题 4 条 SELECT 压成"每次缓存加载 2 条"）。
     *
     * <p>语义与 {@link #getDailyChallengeWords}、{@link #isDailyChallengeWordsCompleted}、
     * {@link #isDailyWordRewardClaimed} 三者组合完全一致，但拆成两次最小查询：
     * 第一次只读 {@code daily_challenge_words}（绝不在 JOIN 后再查
     * {@code daily_word_reward_claims} —— 后者多行会产生笛卡尔积），第二次单行 COUNT。</p>
     *
     * <p><strong>失败降级</strong>（与原三方法逐条一致）：单词查询异常 → 返回空快照
     * （words 空集 + rewardPaid=false，调用方据此视为"今日无可挑战词"）；台账查询异常 →
     * 只把 rewardPaid 置 false（记 WARNING），单词集合照常返回。</p>
     *
     * <p><strong>R6-E</strong>：本方法不再计算"是否全完成"。那个字段原先算出来
     * <b>从未被任何调用方读取</b>（调用方一律用缓存的
     * {@code DailyWordChallengeState.allCompleted()}），属于审计噪音；
     * "有词且全部完成"的判定规则仍在两处活着的实现里：
     * {@link #isDailyChallengeWordsCompleted}（SQL 口径，rollover 补发仍走它）
     * 与 {@code DailyChallengeManager.DailyWordChallengeState#allCompleted()}（缓存口径）。
     * 两者语义等价（都要求"至少一个词"且"完成数 ≥ 总数"，DB 侧因 {@code completed ⊆ words}
     * 可写成 {@code ==}）。</p>
     *
     * @param playerId 玩家
     * @param date     {@code yyyy-MM-dd}
     * @return 不可变快照（详见 {@link WordChallengeSnapshot}）
     */
    public WordChallengeSnapshot loadWordChallengeSnapshot(UUID playerId, String date) {
        String wordsSql = "SELECT word_id, completed FROM " + tablePrefix
                + "daily_challenge_words WHERE player_uuid = ? AND challenge_date = ?";
        Set<String> words = new HashSet<>();
        Set<String> completedWords = new HashSet<>();
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(wordsSql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String wordId = rs.getString("word_id");
                        words.add(wordId);
                        if (rs.getBoolean("completed")) {
                            completedWords.add(wordId);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to load daily challenge words for " + playerId, e);
                // 与 getDailyChallengeWords 的降级一致：读失败 → 空集合
                return new WordChallengeSnapshot(Collections.<String>emptySet(),
                        Collections.<String>emptySet(), false);
            }
        }
        return new WordChallengeSnapshot(words, completedWords,
                isDailyWordRewardClaimed(playerId, date));
    }

    /**
     * R7-B ②：单条 UPDATE 推进行为挑战进度并判定完成位（合并原
     * {@code DailyChallengeRepository.findState} SELECT + {@code updateProgress} UPDATE）。
     *
     * <p><strong>返回值语义是本方法的关键契约</strong>：返回受影响行数，
     * {@code 0} 表示"该玩家今日没有这一行挑战"——这正是原实现
     * {@code loadChallengeState(...) == null} 的触发条件，调用方据此走 R-32 补分配，
     * 因此本方法<strong>必须</strong>返回 {@code executeUpdate()} 的原值，不能改成 boolean。</p>
     *
     * <p>SQL 层面的等价替换（原两步的全部判定下推到一条语句；<strong>赋值顺序见实现注释</strong>）：</p>
     * <pre>
     * completed = CASE WHEN claimed = 0 AND completed = 0 AND progress + inc &gt;= target
     *                  THEN 1 ELSE completed END                       -- 只置不撤
     * progress  = CASE WHEN claimed = 0 THEN
     *                 CASE WHEN progress + inc &gt;= target THEN target      -- R-79 clamp
     *                      ELSE progress + inc END
     *                 ELSE progress END
     * </pre>
     *
     * <p><strong>方言关键陷阱</strong>：MySQL 单表 UPDATE 按从左到右求值、后续赋值看到的是
     * 前面刚写入的新值；SQLite 一律用旧行值。故 {@code completed} 必须写在 {@code progress}
     * <em>之前</em>，否则 MySQL 会把 {@code progress + inc} 算成 {@code (旧+inc)+inc}，
     * 差一格提前完成。此顺序下两方言行为完全一致。</p>
     *
     * <p><strong>方言核对</strong>：<em>不</em>使用 {@code MIN(a, b)} —— 虽然 SQLite 与 MySQL
     * 在双参数形式下都会解析为标量函数，但 MySQL 的 {@code MIN} 同时是聚合函数名，
     * 可读性与工具链（SQL 高亮/静态检查）歧义都不值得省一个 {@code CASE}。此处统一用
     * {@code CASE WHEN ... THEN ... ELSE ... END}，两方言逐字同义。</p>
     *
     * <p>{@code progress} 列在 {@code schema.sql} 与 {@code createTables()} 中均为
     * {@code INT DEFAULT 0}，但存量库若为 NULL 会传染成 NULL 写回 —— 故每次读取都用
     * {@code COALESCE(progress, 0)} 包住（与既有 {@code adjustCoins} 的惯例一致）。</p>
     *
     * <p><strong>并发</strong>：整条语句在 MySQL 默认 REPEATABLE READ 下对目标行加排他锁，
     * 读-改-写原子；调用方仍在未变的 {@code connectionLock} 临界区内，无新增锁。</p>
     *
     * <p><strong>{@code claimed = 1} 为什么放进 CASE 而不是 WHERE</strong>：原实现在
     * {@code alreadyClaimed} 时直接 return、<em>不写库</em>，但行仍存在。若把它写进 WHERE，
     * 已领取行会返回 {@code 0} 行，调用方会把"已领取"误判成"今日未分配"并触发一次无谓的
     * R-32 补分配。放进 SET 的 CASE 里则：行被命中（返回 1，不触发补分配）、两列原样写回
     * （与原 return 的可观察状态一致）。</p>
     *
     * <p>与原实现逐条等价（无放宽、无收紧）：{@code increment} 恒 &gt; 0，故
     * {@code progress >= target} 时 {@code progress + inc >= target} 必然成立 ——
     * 原实现"已达标的畸形旧行也判完成"的行为被完整保留。</p>
     *
     * @param playerId     玩家
     * @param date         {@code yyyy-MM-dd}
     * @param challengeId  挑战 id
     * @param increment    本次增量（恒 &gt; 0，调用方保证）
     * @param target       完成阈值（挑战类型不同而不同，<strong>必须参数绑定</strong>）
     * @return 受影响行数；写失败返回 {@code -1}（区别于"今日无行"的 0）
     */
    public int advanceChallengeProgress(UUID playerId, String date, String challengeId,
                                        int increment, int target) {
        // 每个 ? 都独立绑定（不用同名参数复用）：明确、可读、与既有 SQL 风格一致。
        //
        // 【赋值顺序是正确性的一部分，不可交换】MySQL 的单表 UPDATE 按**从左到右**求值，
        // 后面的赋值会看到前面刚写入的新值（官方示例：SET col1 = col1+1, col2 = col1 里
        // col2 拿到的是新 col1）；SQLite 则一律用旧行值。若把 progress 写在前面，MySQL 下
        // completed 的 `progress + inc >= target` 会变成 `(旧值+inc) + inc >= target`，
        // 导致差一格就提前置 completed=1（如 8/10 答对一次就被判完成）。故 completed
        // **必须写在 progress 之前**：此时两方言看到的都是旧 progress，语义一致。
        //
        // completed 分支带 `completed = 0` 前提：只置位、绝不撤销 —— 等价原实现的
        // `if (alreadyCompleted) nowCompleted = true;`，也兜住"completed 由 completeChallenge
        // 二进制置 1、progress 仍为 0"的行（COMBO_5 这类），不会被本条 UPDATE 打回 0。
        String sql = "UPDATE " + tablePrefix
                + "daily_challenges SET "
                + "completed = CASE WHEN claimed = 0 AND completed = 0 AND COALESCE(progress, 0) + ? >= ? "
                + "THEN 1 ELSE completed END, "
                + "progress = CASE WHEN claimed = 0 THEN "
                + "CASE WHEN COALESCE(progress, 0) + ? >= ? THEN ? ELSE COALESCE(progress, 0) + ? END "
                + "ELSE progress END "
                + "WHERE uuid = ? AND challenge_date = ? AND challenge_id = ?";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                int idx = 1;
                // completed 分支：+inc 与 target 比较（必须在 progress 之前求值）
                stmt.setInt(idx++, increment);
                stmt.setInt(idx++, target);
                // progress 分支：+inc 与 target 比较，封顶 target，否则累加
                stmt.setInt(idx++, increment);
                stmt.setInt(idx++, target);
                stmt.setInt(idx++, target);
                stmt.setInt(idx++, increment);
                stmt.setString(idx++, playerId.toString());
                stmt.setString(idx++, date);
                stmt.setString(idx, challengeId);
                return stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to advance daily challenge progress for " + playerId, e);
                return -1;
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
     * P0-4 红队补丁（R-P1-1）：今日 AI 轮次 -1 落库（退还预扣）。
     *
     * <p>用途单一：开场白在"发请求前"预扣轮次（堵 sameSession 绕过），但若请求在
     * <b>确定已到达上游之前</b>就失败（超时 / 连接失败 / 5xx / 429），这次请求并没有
     * 产生上游计费，应当退还——否则上游持续故障时，玩家反复点场景会把当天的
     * daily-round-limit 与 opening-daily-cap 全部耗光，当天连正常聊天都被拒且
     * 重启/重登都无法恢复（DB 已落库），属于可用性锁死。
     *
     * <p>{@code WHERE rounds > 0} 保证不会写出负值（并发下重复退还的最坏结果是
     * 少退一次，上限仍由内存 TodayCounter 约束）。
     *
     * <p>静默降级：SQLException 只记 WARNING（退还失败最坏后果 = 玩家多耗一轮，
     * 与不退还持平，不崩、不阻断主流程）。
     *
     * @param playerId the player
     * @param date     the round date (yyyy-MM-dd)
     */
    public void decrementAiRound(UUID playerId, String date) {
        String sql = "UPDATE " + tablePrefix + "ai_round_counters"
                + " SET rounds = rounds - 1 WHERE player_uuid = ? AND round_date = ? AND rounds > 0";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, date);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to decrement AI round counter for " + playerId
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
    //  Duel Stats（R10 战略锐评：决斗战绩持久化）
    // ============================================================

    /**
     * 决斗战绩保留天数。决斗数据量极小（一场一行，全服日均个位数到几十行），
     * 一年足够支撑"胜率/连胜/复仇"类查询；更老的明细没有展示价值。
     */
    private static final int DUEL_STATS_RETENTION_DAYS = 365;

    /** 连胜扫描窗口：最多回看最近多少场（有界查询，防重度玩家全表扫）。 */
    private static final int DUEL_STREAK_SCAN_LIMIT = 50;

    /**
     * 一名玩家的决斗累计战绩（{@code duel_stats} 聚合投影）。
     *
     * @param wins   作为 winner_uuid 的行数
     * @param losses 作为 loser_uuid 的行数
     */
    public static record DuelStats(int wins, int losses) {

        /** 总场次。 */
        public int total() {
            return wins + losses;
        }

        /**
         * 胜率（0.0~1.0）；总场次为 0 时返回 0.0（不返回 NaN，调用方免判空）。
         */
        public double winRate() {
            int total = total();
            return total == 0 ? 0.0 : (double) wins / total;
        }
    }

    /**
     * R10：落一场决斗战绩（追加写，一场一行）。
     *
     * <p>静默降级：SQLException 只记 WARNING（写失败最坏后果 = 少一条历史战绩，
     * 玩家当场看到的胜负播报/成就/奖励均已先行完成，绝不因落库失败打断结算）。
     *
     * <p>线程契约：同步阻塞在调用线程；{@code DuelManager.endDuel} 在主线程结算，
     * 因此调用方必须自带异步派发（见 DuelManager 的 runAsync 帮助方法），
     * 与 {@code AchievementManager.checkHelpAchievement} 的"主线程调用方 + 内部
     * 异步派发"纪律同源。
     *
     * @param winner     胜者 UUID
     * @param loser      败者 UUID
     * @param winnerScore 胜者得分（正常路径恒等于 {@code DuelRoom.WIN_SCORE}）
     * @param loserScore  败者得分
     */
    public void recordDuelResult(UUID winner, UUID loser, int winnerScore, int loserScore) {
        String sql = "INSERT INTO " + tablePrefix
                + "duel_stats (winner_uuid, loser_uuid, winner_score, loser_score,"
                + " duel_date, created_at) VALUES (?, ?, ?, ?, ?, ?)";
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, winner.toString());
                stmt.setString(2, loser.toString());
                stmt.setInt(3, winnerScore);
                stmt.setInt(4, loserScore);
                // duel_date 与全仓既有日期口径一致（LocalDate.now() → yyyy-MM-dd）
                stmt.setString(5, LocalDate.now().toString());
                stmt.setLong(6, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to record duel result winner=" + winner
                                + " loser=" + loser, e);
            }
        }
    }

    /**
     * R10：玩家累计胜负场次（赢/输两列各一次 COUNT，同一连接内顺序执行）。
     *
     * <p>索引支撑：两个 COUNT 分别命中 {@code idx_duel_winner_date} /
     * {@code idx_duel_loser_date} 的前导列，均为索引覆盖计数，非全表扫。
     * 异常 → 空战绩（wins=0, losses=0）+ WARNING（降级：展示层显示"暂无战绩"，
     * 不是"显示为全败"）。
     *
     * @param player the player
     * @return 累计战绩（永不为 null）
     */
    public DuelStats getDuelStats(UUID player) {
        String winSql = "SELECT COUNT(*) FROM " + tablePrefix
                + "duel_stats WHERE winner_uuid = ?";
        String lossSql = "SELECT COUNT(*) FROM " + tablePrefix
                + "duel_stats WHERE loser_uuid = ?";
        synchronized (connectionLock) {
            try {
                int wins = 0;
                int losses = 0;
                try (PreparedStatement stmt = getConnection().prepareStatement(winSql)) {
                    stmt.setString(1, player.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            wins = rs.getInt(1);
                        }
                    }
                }
                try (PreparedStatement stmt = getConnection().prepareStatement(lossSql)) {
                    stmt.setString(1, player.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            losses = rs.getInt(1);
                        }
                    }
                }
                return new DuelStats(wins, losses);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get duel stats for " + player, e);
                return new DuelStats(0, 0);
            }
        }
    }

    /**
     * R10：当前连胜场数（从最近一场往回数，直到遇到一场失败或扫描窗口用尽）。
     *
     * <p>实现说明：SQL 取该玩家参与（赢或输）的最近 {@value #DUEL_STREAK_SCAN_LIMIT}
     * 行，按 {@code created_at DESC, id DESC} 排序后在 Java 里数前缀连胜。
     * 为什么不在 SQL 里算：窗口函数（{@code ROW_NUMBER}）两后端语法/版本门槛不一致
     * （MySQL 8 才有，SQLite 3.25+ 才有），而"最多 50 行"的扫描成本可忽略；
     * {@code id DESC} 作次序键兜底同毫秒写入（自增主键单调，双后端均成立）。
     *
     * <p>语义边界：窗口内全胜且恰好用满 50 行时，返回值会是 50（可能低估真实连胜，
     * 不虚报）；30 场以内完全精确。异常 → 0 + WARNING。
     *
     * <p>线程契约：同步阻塞读，禁止主线程调用（与 {@link #getDuelStats} 同）。
     *
     * @param player the player
     * @return 当前连胜场数（0 = 无胜绩或最近一场为负）
     */
    public int getDuelWinStreak(UUID player) {
        String sql = "SELECT winner_uuid FROM " + tablePrefix + "duel_stats"
                + " WHERE winner_uuid = ? OR loser_uuid = ?"
                + " ORDER BY created_at DESC, id DESC LIMIT ?";
        String id = player.toString();
        synchronized (connectionLock) {
            try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, id);
                stmt.setInt(3, DUEL_STREAK_SCAN_LIMIT);
                try (ResultSet rs = stmt.executeQuery()) {
                    int streak = 0;
                    while (rs.next()) {
                        if (!id.equals(rs.getString("winner_uuid"))) {
                            break; // 最近一场参与但没赢 → 连胜中断
                        }
                        streak++;
                    }
                    return streak;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to get duel win streak for " + player, e);
                return 0;
            }
        }
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

    // ============================================================
    //  Statistics HTTP Dashboard queries
    // ============================================================

    /**
     * R7-B ③：单词挑战的当日快照（{@link #loadWordChallengeSnapshot} 的返回体）。
     *
     * <p><strong>R6-E</strong>：原先的 {@code allCompleted} 字段已删除——它算出来后
     * 从未被任何调用方读取（调用方一律用缓存的
     * {@code DailyWordChallengeState.allCompleted()}），留着只会误导"业务规则只存在一处"
     * 的判断。业务判定请走缓存侧的 {@code allCompleted()}；SQL 口径的等价判定见
     * {@link #isDailyChallengeWordsCompleted}（rollover 补发路径仍在用）。</p>
     *
     * <p>集合均为不可变快照（调用方不得写入；缓存侧自行复制）。</p>
     *
     * @param words          今日分配的全部挑战词 id
     * @param completedWords 其中已完成的词 id
     * @param rewardPaid     当日奖励台账（{@code daily_word_reward_claims}）是否已有行
     */
    public record WordChallengeSnapshot(Set<String> words, Set<String> completedWords,
                                        boolean rewardPaid) {

        /** 防御性复制 + 只读包装：调用方持有的可变集合不会反向影响快照。 */
        public WordChallengeSnapshot {
            words = Collections.unmodifiableSet(new HashSet<>(words));
            completedWords = Collections.unmodifiableSet(new HashSet<>(completedWords));
        }
    }

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
            // R10（决斗战绩）：duel_stats 的玩家列是**第四套**形态 —— 同一行里有
            // winner_uuid / loser_uuid 两列都可能指向本人（本人可能是赢家或输家），
            // 不能塞进上面任一数组（单列模板），故单列一条 OR 删除。
            // 语义取舍：删的是"本人参与过的场次"，对手那侧的战绩记录随之一起消失
            // （对方少算一场）——这是 /lea reset 的既有语义（删净本人痕迹优先于
            // 对手统计完整性），与 social_cheers 的"删系他人的行"同款取舍。
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM " + tablePrefix + "duel_stats"
                            + " WHERE winner_uuid = ? OR loser_uuid = ?")) {
                stmt.setString(1, id);
                stmt.setString(2, id);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to delete duel_stats rows of " + playerId, e);
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
     * <p>Phase 1 流 E（F0-01/F0-02）曾登记"外部裸用点已收编完毕，共 25 处"。
     * <b>该账本已过期</b>。2026-10-25（R6-D）逐文件实测的开工基线为：
     * PlayerDataStore 12 处、DailyChallengeManager 10 处、ReportGenerator 9 处、
     * AchievementManager 4 处、AdminCommandHandler 3 处、LLEPapiExpansion 1 处、
     * LetsLearnEnglish 1 处，<b>共 40 处</b>；R6-D 将入口类备份任务的 1 处上收进
     * {@link #backupTo(String)} 后为 <b>39 处</b>。
     * （注：AdminCommandHandler 旧账登记 2 处，实测为 3 处，已按实测更正。）
     *
     * <p><b>R6 重构进行中</b>：上述裸用点正陆续迁入 {@code data/repository/*}
     * （DailyChallengeRepository / AchievementRepository / ReportRepository 等，
     * 仓储内部同样持本锁，约定不变），因此数字会实时变动——本注释记录的是
     * R6-D 开工基线而非终值。迁移完成后请重新统计并更新本段。
     *
     * <p>在此之前，任何直接取 {@link #getConnection()} 的新代码仍必须包在
     * {@code synchronized (databaseManager.getConnectionLock()) { ... }} 内；
     * 也可选用新增的 {@link #withConnection(SqlFunction)} 减少样板（两者语义等价）。
     */
    public Object getConnectionLock() {
        return connectionLock;
    }

    /**
     * R3-L4：幂等补列 —— 先以元数据查询判"列是否已存在"再决定 ALTER
     * （SQLite {@code PRAGMA table_info}、MySQL {@code information_schema.columns}，
     * 与 {@link #createIndexIfMissing} 的 MySQL 侧做法同源）。原实现按异常消息
     * 含 "duplicate"/"already exists" 判定，locale/驱动消息差异下口径不稳。
     */
    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        if (columnAlreadyExists(table, column)) {
            return; // 已存在（或查询故障的保守跳过，见 helper）——跳过 ALTER
        }
        try {
            executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            // 查后改的 TOCTOU 残余窗口（多服共享 MySQL 并发启动同时补同一列，
            // 败者报 duplicate）仍按旧口径吞掉；其余真实错误（权限/磁盘/语法）
            // 照旧上抛，由 initialize 记 SEVERE。
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (!message.contains("duplicate") && !message.contains("already exists")) throw e;
        }
    }

    /**
     * R3-L4：列存在性元数据查询。返回 true = 已存在或查询故障（保守跳过 ALTER）——
     * 查询故障（连接/权限等）记 WARNING 后按"已存在"处理，与旧实现"不因补列失败
     * 打断建表主流程"的容错口径一致，仅明确"列不存在"才返回 false 放行 ALTER。
     */
    private boolean columnAlreadyExists(String table, String column) {
        synchronized (connectionLock) {
            try {
                if (isSQLite()) {
                    try (PreparedStatement stmt = getConnection().prepareStatement(
                            "PRAGMA table_info(" + table + ")")) {
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                if (column.equals(rs.getString("name"))) {
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                }
                String checkSql = "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
                try (PreparedStatement stmt = getConnection().prepareStatement(checkSql)) {
                    stmt.setString(1, table);
                    stmt.setString(2, column);
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next() && rs.getInt(1) > 0;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to check existence of column " + column + " on " + table
                                + " — skipping ALTER (conservative)", e);
                return true;
            }
        }
    }

    /**
     * 修复批 D（R3-L2）：把保留天数绑到唯一占位符——
     * SQLite 的 {@code datetime('now', ?)} / {@code date('now', ?)} 需字符串
     * modifier（{@code "-N days"}），MySQL 的 {@code INTERVAL ? DAY} 需 int。
     */
    private void bindKeepDays(PreparedStatement stmt, int daysToKeep) throws SQLException {
        if (isSQLite()) {
            stmt.setString(1, "-" + daysToKeep + " days");
        } else {
            stmt.setInt(1, daysToKeep);
        }
    }

    // ============================================================
    //  R6-D: 供仓储与入口类使用（2026-10-25）
    //
    //  本节只做"加法"：以上 92 个 public 方法的签名与实现均未改动。
    //  新增方法集中追加在文件末尾，避免与并行流（data/repository/*、core 各管理器）
    //  的改动面重叠，减少合并冲突。
    // ============================================================

    /**
     * R6-D：受检 SQL 操作的函数式载体。
     *
     * <p>作为 {@link DatabaseManager} 的嵌套接口定义，不新建顶层文件——避免与其他
     * 并行流在包扫描/白名单式检查上产生冲突。
     *
     * @param <T> 返回值类型
     */
    @FunctionalInterface
    public interface SqlFunction<T> {
        /**
         * 在 connectionLock 内对共享连接执行操作。
         *
         * @param conn 由 {@link #getConnection()} 提供的共享连接（调用期间持有锁）
         * @return 操作结果
         * @throws SQLException SQL 失败时抛出，由 {@link #withConnection} 统一记录
         */
        T apply(Connection conn) throws SQLException;
    }

    /**
     * R6-D：在 connectionLock 内取连接并执行一段 SQL 操作，失败记 WARNING 后返回 {@code null}。
     *
     * <p><b>当前无生产调用方（R6-E 审计说明）</b>：本重载目前只被同文件的
     * {@link #countRows(String)} 间接使用，<b>不是</b> R6 搬迁遗漏的死代码，而是为
     * 后续仓储化批次预留的标准形态——它就是"锁内取连接 + 异常兜底"这段样板的
     * 唯一收口点，后续新仓储应当直接用它，而不是各写一份 synchronized 模板。
     * 下次审计请勿按"零调用方"删除。</p>
     *
     * <p><b>这是可选工具，不是强制入口。</b>R6 的 5 个仓储流是并行开发的，
     * 它们可能不知道本方法存在——那没有任何问题：新仓储完全可以直接沿用既有范式
     *
     * <pre>{@code
     * synchronized (db.getConnectionLock()) {
     *     try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) { ... }
     * }
     * }</pre>
     *
     * <p>本方法只是把"加锁 + 取连接 + catch SQLException + 记 WARNING + 返回兜底值"
     * 这段重复样板收拢一次，语义与手写完全一致，可按需选用。
     *
     * <p>锁语义：内部先 {@code synchronized (connectionLock)} 再调用
     * {@link #getConnection()}（后者自身也同步同一把锁）。Java 的 synchronized 是
     * <b>可重入</b>的，同一线程二次进入不会自锁，因此不存在"嵌套加锁造成死锁"的问题；
     * 反过来说，仓储若已在外层持有该锁，再调用本方法同样安全（重入计数 +1 后退出）。
     * 请勿在持锁期间做消息投递/调度器调用等阻塞动作（R9-L5 约定）。
     *
     * <p>异常口径：只捕获 {@link SQLException}。连接自愈失败时 {@link #getConnection()}
     * 抛出的 {@link RuntimeException} 会原样上抛——那属于"数据库整体不可用"，应让调用方
     * 明确感知，而不是被兜底值静默吞掉。
     *
     * @param fn 要执行的操作，不可为 null
     * @param <T> 返回值类型
     * @return 操作结果；{@code fn} 为 null 或抛出 SQLException 时返回 {@code null}
     */
    public <T> T withConnection(SqlFunction<T> fn) {
        return withConnection(fn, null);
    }

    /**
     * R6-D：{@link #withConnection(SqlFunction)} 的带兜底值重载。
     *
     * <p><b>当前无生产调用方（R6-E 审计说明）</b>：目前仅被 {@link #countRows(String)}
     * 与上面那个重载内部使用，为后续仓储化批次预留（带兜底值的变体是"读失败降级"
     * 的通用形态）。下次审计请勿按"零调用方"删除。</p>
     *
     * @param fn       要执行的操作，不可为 null
     * @param fallback 失败（或被传入 null 函数）时返回的默认值
     * @param <T>      返回值类型
     * @return 操作结果，或 {@code fallback}
     */
    public <T> T withConnection(SqlFunction<T> fn, T fallback) {
        if (fn == null) {
            return fallback;
        }
        synchronized (connectionLock) {
            try {
                return fn.apply(getConnection());
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Database operation failed (withConnection) — returning fallback", e);
                return fallback;
            }
        }
    }

    /**
     * R6-D：诊断用行数统计。
     *
     * <p>表名<b>只接受白名单内的 22 张逻辑表名</b>（不含 table-prefix，如
     * {@code player_data}；若传入已带前缀的完整名也会被识别并剥掉前缀），
     * 其余一律拒绝并返回 -1——杜绝把外部输入拼进 SQL 造成注入/误查。
     * 前缀取自配置 {@code database.mysql.table-prefix}（SQLite 同样生效）。
     *
     * <p>R6-E（当前无生产调用方，为后续诊断/运维命令预留）：前缀与表名的剥离比较
     * 改为大小写不敏感——运维手工输入 {@code LLE_player_data} 时不该被拒。安全性不变：
     * 剥离<b>之后</b>仍要过 {@link #COUNTABLE_TABLES} 精确白名单，比对大小写不敏感
     * 只会让"已带前缀的完整名"这一输入形态更宽容，不会放宽白名单本身；
     * 实际拼进 SQL 的仍是白名单内的字面量，且 SQL 标识符在 SQLite/MySQL 默认配置下
     * 本身大小写不敏感。</p>
     *
     * @param table 逻辑表名，见 {@code COUNTABLE_TABLES}（私有常量，Javadoc 无法链接）
     * @return 行数；表名非法或查询失败返回 -1（与"0 行"区分）
     */
    public int countRows(String table) {
        if (table == null) {
            return -1;
        }
        String prefix = tablePrefix == null ? "" : tablePrefix;
        String logical = table.trim();
        // R6-E：剥离前缀用大小写不敏感比较；白名单校验仍用原值精确匹配。
        if (!prefix.isEmpty()
                && logical.regionMatches(true, 0, prefix, 0, prefix.length())) {
            logical = logical.substring(prefix.length());
        }
        if (!COUNTABLE_TABLES.contains(logical)) {
            plugin.getLogger().warning("countRows rejected unknown table: " + table);
            return -1;
        }
        final String counted = logical;
        Integer rows = withConnection(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + prefix + counted);
                 ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }, Integer.valueOf(-1));
        // 显式判 null：即便日后有人改动 fallback 传参，也不会因自动拆箱抛 NPE。
        return rows == null ? -1 : rows;
    }

    /**
     * R6-D：允许 {@link #countRows(String)} 统计的逻辑表名白名单（22 张，与
     * {@code createTables()} 的建表清单一一对应）。
     *
     * <p>新增表时必须同步补进本集合，否则诊断统计会安全地拒绝该表名。
     */
    private static final Set<String> COUNTABLE_TABLES = Set.of(
            "player_data",
            "word_progress",
            "achievements",
            "learning_log",
            "player_sessions",
            "player_profiles",
            "player_achievements",
            "daily_challenges",
            "daily_challenge_words",
            "daily_word_reward_claims",
            "ai_round_counters",
            "duel_stats",
            "ai_chat_history",
            "ai_chat_feedback",
            "difficult_words",
            "checkin_data",
            "social_feed",
            "social_cheers",
            "seasons",
            "season_rewards",
            "classes",
            "class_members"
    );

    /**
     * R6-D：SQLite 数据库文件的绝对路径，供诊断/运维显示与备份路径推演。
     *
     * <p><b>当前无生产调用方（R6-E 审计说明）</b>：这是诊断能力的基础件，为后续
     * {@code /lea} 诊断命令等批次预留，<b>不是</b> R6 搬迁的死代码。下次审计请勿
     * 按"零调用方"删除。</p>
     *
     * <p>与 {@code initializeSQLite()} 同源取值：数据目录 + 配置
     * {@code database.sqlite.filename}（默认 {@code playerdata.db}）。
     *
     * <p>后端判定读 {@code isSQLite} 字段（与既有 {@link #isSQLite()} 同源）。
     * 诊断命令在主线程调用，此时 initialize() 早已完成，无需额外同步。
     *
     * @return 绝对路径；当前后端为 MySQL（或尚未 initialize）时返回 {@code null}
     */
    public String getSqliteFilePath() {
        if (!isSQLite) {
            return null;
        }
        File dbFile = new File(plugin.getDataFolder(),
                configManager.getMainConfig().getString("database.sqlite.filename", "playerdata.db"));
        return dbFile.getAbsolutePath();
    }

    /**
     * R6-D：只读探活——不触发重连、不修改任何状态，供 /lea 诊断命令使用。
     *
     * <p><b>当前无生产调用方（R6-E 审计说明）</b>：这是诊断能力的基础件，为后续
     * {@code /lea} 诊断命令等批次预留，<b>不是</b> R6 搬迁的死代码。下次审计请勿
     * 按"零调用方"删除。</p>
     *
     * <p>直接读 volatile 字段而非走 {@link #getConnection()}：后者在连接缺失时会
     * 同步加锁并尝试 initialize()，那是"自愈"而不是"探活"，诊断命令不应有副作用，
     * 也不应为了报告一个状态而阻塞主线程等锁。
     *
     * @return true 表示当前持有可用连接；false 表示连接为 null、已关闭或状态不可读
     */
    public boolean isConnectionHealthy() {
        Connection conn = connection;
        if (conn == null) {
            return false;
        }
        try {
            return !conn.isClosed();
        } catch (SQLException e) {
            // isClosed 本身极少失败；失败时按"不健康"报告，不抛给诊断调用方。
            return false;
        }
    }

    /**
     * R6-D：SQLite 热备快照（{@code VACUUM INTO}）。
     *
     * <p>自 {@code LetsLearnEnglish.backupDatabase} 上收：原有
     * {@code synchronized (getConnectionLock())} + {@link #getConnection()} + 路径单引号
     * 转义逻辑<b>逐字保留</b>。
     *
     * <p>为什么仍是字符串拼接：SQLite 的 {@code VACUUM INTO} 只接受字面量文件名，
     * <b>不接受绑定参数</b>（{@code VACUUM INTO ?} 会报语法错误），因此只能拼接；
     * 调用方传入的是内部构造路径（数据目录 + 时间戳），单引号转义作为纵深防御保留。
     *
     * <p>异常口径与原实现一致：SQL 失败原样抛出 {@link SQLException}，由调用方
     * （备份任务）负责删除可能的残缺产物并记日志——本方法<b>不</b>做文件清理，
     * 以免与调用方的清理职责重叠。
     *
     * <p>后端约束：仅对 SQLite 有意义（MySQL 无 {@code VACUUM INTO}）。是否调用由
     * 调用方按 {@code database.type} 判断——与原入口类 {@code backupDatabase} 的
     * 前置检查保持同一职责划分，本方法<b>不</b>重复判断，以免在"部分初始化"等
     * 边缘状态下引入原本不存在的失败路径。
     *
     * @param absolutePath 目标文件的绝对路径（不可为 null/空）
     * @throws SQLException 目标路径为空，或 VACUUM INTO 执行失败
     */
    public void backupTo(String absolutePath) throws SQLException {
        if (absolutePath == null || absolutePath.isEmpty()) {
            throw new SQLException("Backup target path is empty");
        }
        synchronized (connectionLock) {
            String target = absolutePath.replace("'", "''");
            try (PreparedStatement stmt = getConnection()
                    .prepareStatement("VACUUM INTO '" + target + "'")) {
                stmt.execute();
            }
        }
    }

    /**
     * R6-D：备份产物完整性校验（{@code PRAGMA quick_check}）。
     *
     * <p>自 {@code LetsLearnEnglish.checkBackupIntegrity} 上收。关键点：必须使用
     * <b>独立只读连接</b>直连备份文件——查活连接等于查错对象（R3-H1 第二修）。
     * 因此本方法<b>不经过</b> {@link #getConnection()}，也不持有 connectionLock。
     *
     * <p>R6-D 加固：显式 {@code Class.forName("org.sqlite.JDBC")}（照
     * {@link #initializeMySQL} 的写法）。Bukkit/Paper 的插件 ClassLoader 与 JDBC 4
     * 的服务自动发现（依赖线程上下文类加载器）不总是能互相看见，显式加载可把失败点
     * 从晦涩的 "No suitable driver found" 提前成一条可读的 WARNING。加载失败时
     * <b>不直接判负</b>，而是记 WARNING 后继续尝试 DriverManager（若驱动经
     * ServiceLoader 可见仍可成功），保持与原实现相同的成功率。
     *
     * @param backupFile 备份文件
     * @return true 表示 quick_check 返回 "ok"；文件缺失、驱动缺失且无法连接、
     *         或校验结果非 ok 时返回 false
     */
    public boolean checkBackupIntegrity(File backupFile) {
        if (backupFile == null || !backupFile.isFile()) {
            plugin.getLogger().warning("Backup integrity check skipped: file not found"
                    + (backupFile == null ? "" : " (" + backupFile.getAbsolutePath() + ")"));
            return false;
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            // 不提前返回：DriverManager 的服务发现仍可能成功，降级为提示。
            plugin.getLogger().warning("SQLite JDBC driver (org.sqlite.JDBC) not found by name; "
                    + "falling back to JDBC ServiceLoader discovery: " + e.getMessage());
        }
        String url = "jdbc:sqlite:" + backupFile.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("PRAGMA quick_check");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
        } catch (SQLException e) {
            plugin.getLogger().warning("Backup integrity check failed to run: " + e.getMessage());
            return false;
        }
    }
}
