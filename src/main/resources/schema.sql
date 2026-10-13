-- ============================================================
--  Let's Learn English! - Database Schema
--  Minecraft 英语学习插件
-- ============================================================
--  This file documents the database schema for reference.
--  Tables are created automatically by DatabaseManager on startup.

-- Player data table (one row per player)
CREATE TABLE IF NOT EXISTS {prefix}player_data (
    uuid VARCHAR(36) PRIMARY KEY,
    player_name VARCHAR(32),
    total_words_learned INT DEFAULT 0,
    total_sessions INT DEFAULT 0,
    total_score BIGINT DEFAULT 0,
    total_coins_earned BIGINT DEFAULT 0,
    total_coins_spent BIGINT DEFAULT 0,
    conversation_count INT DEFAULT 0,
    onboarding_step INT DEFAULT 0,
    wrong_book_cleared INT DEFAULT 0,
    current_streak INT DEFAULT 0,
    longest_streak INT DEFAULT 0,
    last_login BIGINT,
    created_at BIGINT,
    updated_at BIGINT
);

-- Word progress per player (mastery, correct/incorrect counts)
CREATE TABLE IF NOT EXISTS {prefix}word_progress (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid VARCHAR(36) NOT NULL,
    word VARCHAR(64) NOT NULL,
    mastery DOUBLE DEFAULT 0.0,
    correct_count INT DEFAULT 0,
    incorrect_count INT DEFAULT 0,
    last_review_time BIGINT,
    review_count INT DEFAULT 0,
    UNIQUE(uuid, word)
);

-- Achievements unlocked by players
CREATE TABLE IF NOT EXISTS {prefix}achievements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid VARCHAR(36) NOT NULL,
    achievement_id VARCHAR(64) NOT NULL,
    unlocked_at BIGINT,
    UNIQUE(uuid, achievement_id)
);

-- Learning log (session-level records)
CREATE TABLE IF NOT EXISTS {prefix}learning_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid VARCHAR(36) NOT NULL,
    session_type VARCHAR(32),
    difficulty VARCHAR(16),
    words_count INT,
    correct_count INT,
    score INT,
    started_at BIGINT,
    ended_at BIGINT
);

-- Player daily sessions (one per day)
CREATE TABLE IF NOT EXISTS {prefix}player_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid VARCHAR(36) NOT NULL,
    session_date DATE NOT NULL,
    words_count INT DEFAULT 0,
    correct_count INT DEFAULT 0,
    score BIGINT DEFAULT 0,
    mode VARCHAR(32),
    review_words INT DEFAULT 0,
    UNIQUE(uuid, session_date)
);

-- Player profile settings (privacy, age)
CREATE TABLE IF NOT EXISTS {prefix}player_profiles (
    uuid VARCHAR(36) PRIMARY KEY,
    age INT DEFAULT 0,
    show_answers BOOLEAN DEFAULT 0,
    show_results BOOLEAN DEFAULT 1,
    show_in_square BOOLEAN DEFAULT 1,
    sounds_enabled BOOLEAN DEFAULT 1
);

-- Player achievements (separate from old achievements table)
CREATE TABLE IF NOT EXISTS {prefix}player_achievements (
    uuid VARCHAR(36) NOT NULL,
    achievement_id VARCHAR(64) NOT NULL,
    unlocked_at BIGINT NOT NULL,
    PRIMARY KEY (uuid, achievement_id)
);

-- Daily behavioral challenges (task-based, e.g. "answer 10 words")
CREATE TABLE IF NOT EXISTS {prefix}daily_challenges (
    uuid VARCHAR(36) NOT NULL,
    challenge_date DATE NOT NULL,
    challenge_id VARCHAR(32) NOT NULL,
    progress INT DEFAULT 0,
    completed BOOLEAN DEFAULT 0,
    claimed BOOLEAN DEFAULT 0,
    PRIMARY KEY (uuid, challenge_date, challenge_id)
);

-- Daily word challenges (word-bank-based, N random words per day)
CREATE TABLE IF NOT EXISTS {prefix}daily_challenge_words (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid VARCHAR(36) NOT NULL,
    challenge_date DATE NOT NULL,
    word_id VARCHAR(64) NOT NULL,
    completed BOOLEAN DEFAULT FALSE,
    completed_at TIMESTAMP NULL,
    UNIQUE(player_uuid, challenge_date, word_id)
);

-- AI chat history
CREATE TABLE IF NOT EXISTS {prefix}ai_chat_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid VARCHAR(36) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS {prefix}idx_chat_player_time ON {prefix}ai_chat_history(player_uuid, timestamp);
CREATE INDEX IF NOT EXISTS {prefix}idx_chat_session ON {prefix}ai_chat_history(session_id);

-- Difficult words / Wrong Book (words answered incorrectly 3+ times)
CREATE TABLE IF NOT EXISTS {prefix}difficult_words (
    player_uuid VARCHAR(36) NOT NULL,
    word VARCHAR(100) NOT NULL,
    wrong_count INTEGER DEFAULT 0,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (player_uuid, word)
);

-- Daily check-in data (streak tracking)
CREATE TABLE IF NOT EXISTS {prefix}checkin_data (
    player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    last_checkin_date DATE NOT NULL,
    streak_count INTEGER DEFAULT 1
);

-- Square social: learning feed (one merged row per player x day x event type)
CREATE TABLE IF NOT EXISTS {prefix}social_feed (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid VARCHAR(36) NOT NULL,     -- 动态作者
    feed_date DATE NOT NULL,              -- 服务器时区自然日（合并键）
    event_type VARCHAR(16) NOT NULL,      -- SESSION_DONE / ACHIEVEMENT / CHECKIN
    events_count INT DEFAULT 1,           -- 当日同类型累计次数
    sum_correct INT DEFAULT 0,            -- SESSION_DONE 累计答对
    sum_total INT DEFAULT 0,              -- SESSION_DONE 累计题数
    last_mode VARCHAR(16),                -- SESSION_DONE 最近会话模式
    last_streak INT DEFAULT 0,            -- CHECKIN 最近连续天数
    achievement_id VARCHAR(64),           -- ACHIEVEMENT 最近解锁 id
    cheer_count INT DEFAULT 0,            -- 鼓励计数（去规范化，写侧维护）
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,           -- 排序键（新事件/合并时刷新；鼓励不改它）
    UNIQUE(player_uuid, feed_date, event_type)
);

CREATE INDEX IF NOT EXISTS {prefix}idx_feed_order ON {prefix}social_feed(feed_date, updated_at);
CREATE INDEX IF NOT EXISTS {prefix}idx_feed_player ON {prefix}social_feed(player_uuid, feed_date);

-- Square social: cheers (one per feed row per cheerer)
CREATE TABLE IF NOT EXISTS {prefix}social_cheers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    feed_id INTEGER NOT NULL,             -- 逻辑关联 social_feed.id（沿用"无外键直删"惯例）
    cheerer_uuid VARCHAR(36) NOT NULL,    -- 鼓励者（注意列名是 cheerer_uuid，不是 uuid/player_uuid）
    created_at BIGINT NOT NULL,
    read_by_owner BOOLEAN DEFAULT 0,      -- 作者离线摘要已读标记
    UNIQUE(feed_id, cheerer_uuid)
);

CREATE INDEX IF NOT EXISTS {prefix}idx_cheers_feed ON {prefix}social_cheers(feed_id);
CREATE INDEX IF NOT EXISTS {prefix}idx_cheers_cheerer ON {prefix}social_cheers(cheerer_uuid);
