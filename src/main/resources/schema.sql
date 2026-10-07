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
    UNIQUE(uuid, session_date)
);

-- Player profile settings (privacy, age)
CREATE TABLE IF NOT EXISTS {prefix}player_profiles (
    uuid VARCHAR(36) PRIMARY KEY,
    age INT DEFAULT 0,
    show_answers BOOLEAN DEFAULT 1,
    show_results BOOLEAN DEFAULT 1,
    show_in_square BOOLEAN DEFAULT 1
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
