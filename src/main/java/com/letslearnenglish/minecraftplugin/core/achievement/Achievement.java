package com.letslearnenglish.minecraftplugin.core.achievement;

/**
 * Achievement definitions for Let's Learn English!
 *
 * Each achievement has:
 * - id: unique identifier used in the database and for lookups
 * - zhName: Chinese display name shown to players using Chinese locale
 * - enName: English display name shown to players using English locale
 * - zhDesc: detailed Chinese description explaining unlocking conditions
 * - enDesc: detailed English description explaining unlocking conditions
 *
 * The icon material is determined in the GUI layer, not here, so that
 * GUI appearance can be changed without touching the data model.
 *
 * Adding a new achievement requires:
 * 1. Add the enum constant here
 * 2. Add the check logic in AchievementManager
 * 3. No database schema change needed (achievement_id is a VARCHAR)
 */
public enum Achievement {

    FIRST_WORD(
            "first_word",
            "初出茅庐",
            "Fresh Start",
            "在任意模式中答对第 1 个单词，开启你的英语学习之旅！",
            "Answer your first word correctly in any mode and start your learning journey!"
    ),

    WORDS_25(
            "words_25",
            "小有所成",
            "Getting There",
            "累计答对 25 个单词，你已经开始掌握不少词汇了！",
            "Get 25 words correct — you are building a solid vocabulary!"
    ),

    WORDS_100(
            "words_100",
            "单词达人",
            "Word Master",
            "累计答对 100 个单词，词汇量已经相当可观！",
            "Get 100 words correct — your vocabulary is becoming impressive!"
    ),

    WORDS_500(
            "words_500",
            "学富五车",
            "Lexicon Legend",
            "累计答对 500 个单词，真正的词汇大师！",
            "Get 500 words correct — a true vocabulary legend!"
    ),

    PERFECT_SESSION(
            "perfect_session",
            "完美无缺",
            "Flawless",
            "完成一次 100% 正确率的完整答题会话，零失误！",
            "Complete an entire learning session with 100% accuracy — zero mistakes!"
    ),

    STREAK_3(
            "streak_3",
            "小试牛刀",
            "Warming Up",
            "在一次会话中连续答对 3 题，手感火热！",
            "Answer 3 questions in a row correctly during one session — you are on fire!"
    ),

    STREAK_10(
            "streak_10",
            "势如破竹",
            "Unstoppable",
            "在一次会话中连续答对 10 题，无人能挡！",
            "Answer 10 questions in a row correctly during one session — unstoppable!"
    ),

    SPELLING_MASTER(
            "spelling_master",
            "拼写达人",
            "Spelling Bee",
            "完成一次拼写模式的完整答题会话，手打单词不含糊！",
            "Complete a full spelling-mode session — typing words like a pro!"
    ),

    DAILY_STREAK_3(
            "daily_streak_3",
            "连续三天",
            "Three-Day Streak",
            "连续 3 天都完成了至少一次学习会话，坚持就是胜利！",
            "Complete at least one session for 3 consecutive days — consistency is key!"
    ),

    DAILY_STREAK_7(
            "daily_streak_7",
            "一周全勤",
            "Week Warrior",
            "连续 7 天都完成了至少一次学习会话，一整周都在学英语！",
            "Complete at least one session for 7 consecutive days — a full week of English!"
    ),

    SESSIONS_10(
            "sessions_10",
            "坚持不懈",
            "Persistent",
            "累计完成 10 次学习会话，持之以恒！",
            "Complete 10 learning sessions total — persistence pays off!"
    ),

    SESSIONS_50(
            "sessions_50",
            "学习标兵",
            "Scholar",
            "累计完成 50 次学习会话，你是大家的学习榜样！",
            "Complete 50 learning sessions — you are a role model for learners!"
    ),

    COINS_500(
            "coins_500",
            "小有积蓄",
            "Saver",
            "累计获得 500 金币，知识就是财富！",
            "Earn 500 coins total — knowledge is wealth!"
    ),

    COINS_5000(
            "coins_5000",
            "金库满载",
            "Fortune",
            "累计获得 5000 金币，你的金库已经满满当当！",
            "Earn 5000 coins total — your treasury is overflowing!"
    ),

    AI_CHAT_10(
            "ai_chat_10",
            "初识AI",
            "AI Beginner",
            "完成 10 次 AI 情景对话，与 AI 练习口语！",
            "Complete 10 AI scenario conversations — practicing with AI!"
    ),

    AI_CHAT_50(
            "ai_chat_50",
            "AI伙伴",
            "AI Companion",
            "完成 50 次 AI 情景对话，AI 已经成为你的语言伙伴！",
            "Complete 50 AI scenario conversations — AI is your language partner now!"
    );

    private final String id;
    private final String zhName;
    private final String enName;
    private final String zhDesc;
    private final String enDesc;

    Achievement(String id, String zhName, String enName, String zhDesc, String enDesc) {
        this.id = id;
        this.zhName = zhName;
        this.enName = enName;
        this.zhDesc = zhDesc;
        this.enDesc = enDesc;
    }

    public String getId() {
        return id;
    }

    public String getZhName() {
        return zhName;
    }

    public String getEnName() {
        return enName;
    }

    public String getZhDesc() {
        return zhDesc;
    }

    public String getEnDesc() {
        return enDesc;
    }

    public String getDisplayName(String language) {
        return "zh".equalsIgnoreCase(language) ? zhName : enName;
    }

    public String getDisplayDesc(String language) {
        return "zh".equalsIgnoreCase(language) ? zhDesc : enDesc;
    }

    public static Achievement fromId(String id) {
        for (Achievement a : values()) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }
}