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
 * - category: display grouping used by the achievement wall (stable sort)
 * - progressSource: where the "current value" for the progress line comes from
 * - target: the concrete goal for the progress line (0 when progressSource is NONE)
 *
 * The icon material is determined in the GUI layer, not here, so that
 * GUI appearance can be changed without touching the data model.
 *
 * Adding a new achievement requires:
 * 1. Add the enum constant here
 * 2. Add the check logic in AchievementManager
 * 3. Add a config block in achievements-config.yml (admin consistency)
 * 4. No database schema change needed (achievement_id is a VARCHAR)
 *
 * id freezing contract: every id is a system-wide contract (DB rows +
 * achievement wall + PAPI + fromId scans). Landing an id freezes it forever;
 * never rename an existing id.
 */
public enum Achievement {

    // PF-ACH-06 语义裁决（S-D 流）：FIRST_WORD 判定已由 PF-ONB-01（Phase 3 规划期发现，
    // 编排者即修）改为 sessionCorrect >= 1（"会话内至少答对 1 题"，见
    // AchievementManager.checkSessionAchievements 的会话内注释）——与原描述
    // "答对第 1 个单词"重新吻合，故本描述**保持不动**；规划稿 §2.6 的
    // "零失误会话"改写稿基于旧判定，已随判定修复作废。
    FIRST_WORD(
            "first_word",
            "初出茅庐",
            "Fresh Start",
            "在任意模式中答对第 1 个单词，开启你的英语学习之旅！",
            "Answer your first word correctly in any mode and start your learning journey!",
            AchievementCategory.LEARNING, ProgressSource.NONE, 0
    ),

    WORDS_25(
            "words_25",
            "小有所成",
            "Getting There",
            "累计答对 25 个单词，你已经开始掌握不少词汇了！",
            "Get 25 words correct — you are building a solid vocabulary!",
            AchievementCategory.LEARNING, ProgressSource.TOTAL_CORRECT, 25
    ),

    WORDS_100(
            "words_100",
            "单词达人",
            "Word Master",
            "累计答对 100 个单词，词汇量已经相当可观！",
            "Get 100 words correct — your vocabulary is becoming impressive!",
            AchievementCategory.LEARNING, ProgressSource.TOTAL_CORRECT, 100
    ),

    // §2.6 前置描述改写（前置判定收口在 AchievementManager.meetsPrerequisites）
    WORDS_500(
            "words_500",
            "学富五车",
            "Lexicon Legend",
            "累计答对 500 个单词（要求答题数 ≥100 且历史正确率 ≥70%），真正的词汇大师！",
            "Get 500 words correct (requires 100+ answers with 70%+ lifetime accuracy) — a true vocabulary legend!",
            AchievementCategory.LEARNING, ProgressSource.TOTAL_CORRECT, 500
    ),

    PERFECT_SESSION(
            "perfect_session",
            "完美无缺",
            "Flawless",
            "完成一次 100% 正确率的完整答题会话，零失误！",
            "Complete an entire learning session with 100% accuracy — zero mistakes!",
            AchievementCategory.LEARNING, ProgressSource.NONE, 0
    ),

    STREAK_3(
            "streak_3",
            "小试牛刀",
            "Warming Up",
            "在一次会话中连续答对 3 题，手感火热！",
            "Answer 3 questions in a row correctly during one session — you are on fire!",
            AchievementCategory.LEARNING, ProgressSource.NONE, 0
    ),

    STREAK_10(
            "streak_10",
            "势如破竹",
            "Unstoppable",
            "在一次会话中连续答对 10 题，无人能挡！",
            "Answer 10 questions in a row correctly during one session — unstoppable!",
            AchievementCategory.LEARNING, ProgressSource.NONE, 0
    ),

    SPELLING_MASTER(
            "spelling_master",
            "拼写达人",
            "Spelling Bee",
            "完成一次拼写模式的答题会话（至少拼对 1 题），手打单词不含糊！",
            "Complete a spelling-mode session with at least 1 correct spelling — typing words like a pro!",
            AchievementCategory.LEARNING, ProgressSource.NONE, 0
    ),

    DAILY_STREAK_3(
            "daily_streak_3",
            "连续三天",
            "Three-Day Streak",
            "连续 3 天都完成了至少一次学习会话，坚持就是胜利！",
            "Complete at least one session for 3 consecutive days — consistency is key!",
            AchievementCategory.CONSISTENCY, ProgressSource.DAILY_STREAK, 3
    ),

    DAILY_STREAK_7(
            "daily_streak_7",
            "一周全勤",
            "Week Warrior",
            "连续 7 天都完成了至少一次学习会话，一整周都在学英语！",
            "Complete at least one session for 7 consecutive days — a full week of English!",
            AchievementCategory.CONSISTENCY, ProgressSource.DAILY_STREAK, 7
    ),

    SESSIONS_10(
            "sessions_10",
            "坚持不懈",
            "Persistent",
            "累计完成 10 次学习会话，持之以恒！",
            "Complete 10 learning sessions total — persistence pays off!",
            AchievementCategory.LEARNING, ProgressSource.TOTAL_SESSIONS, 10
    ),

    // §2.6 前置描述改写（前置判定收口在 AchievementManager.meetsPrerequisites）
    SESSIONS_50(
            "sessions_50",
            "学习标兵",
            "Scholar",
            "累计完成 50 次学习会话（需先解锁「势如破竹」），你是大家的学习榜样！",
            "Complete 50 sessions total (requires unlocking \"Unstoppable\" first) — a role model for learners!",
            AchievementCategory.LEARNING, ProgressSource.TOTAL_SESSIONS, 50
    ),

    // §2.6 前置描述改写（前置判定收口在 AchievementManager.meetsPrerequisites）
    COINS_500(
            "coins_500",
            "小有积蓄",
            "Saver",
            "累计获得 500 金币（要求累计答题 ≥50），知识就是财富！",
            "Earn 500 coins total (requires 50+ answers) — knowledge is wealth!",
            AchievementCategory.ECONOMY, ProgressSource.COINS_EARNED, 500
    ),

    // §2.6 前置描述改写（前置判定收口在 AchievementManager.meetsPrerequisites）
    COINS_5000(
            "coins_5000",
            "金库满载",
            "Fortune",
            "累计获得 5000 金币（需先解锁「单词达人」），你的金库已经满满当当！",
            "Earn 5000 coins total (requires unlocking \"Word Master\" first) — your treasury is overflowing!",
            AchievementCategory.ECONOMY, ProgressSource.COINS_EARNED, 5000
    ),

    AI_CHAT_10(
            "ai_chat_10",
            "初识AI",
            "AI Beginner",
            "完成 10 次 AI 情景对话，与 AI 练习口语！",
            "Complete 10 AI scenario conversations — practicing with AI!",
            AchievementCategory.SOCIAL, ProgressSource.CONVERSATIONS, 10
    ),

    AI_CHAT_50(
            "ai_chat_50",
            "AI伙伴",
            "AI Companion",
            "完成 50 次 AI 情景对话，AI 已经成为你的语言伙伴！",
            "Complete 50 AI scenario conversations — AI is your language partner now!",
            AchievementCategory.SOCIAL, ProgressSource.CONVERSATIONS, 50
    ),

    // ============================================================
    // Phase 3 新增 6 个质量型成就（plan-achievements §1.2 / §2.6）。
    // 追加在 enum 末尾以保住既有 values() 相对顺序；成就墙排序用稳定排序，
    // 旧展示序不被扰动。名称/描述为 §2.6 表格的字面量。
    // ============================================================

    REVIEW_STREAK_7(
            "review_streak_7",
            "温故知新",
            "Memory Keeper",
            "连续 7 天完成复习会话，且每天至少答对 1 个复习单词——记忆的守护者！",
            "Complete a review session for 7 consecutive days (at least 1 due word correct each day) — a true memory keeper!",
            AchievementCategory.REVIEW, ProgressSource.REVIEW_STREAK, 7
    ),

    WRONG_BOOK_CLEARED(
            "wrong_book_cleared",
            "错题清零",
            "Clean Slate",
            "通过错题练习累计攻克 5 个单词，并把错题本清空——从哪里跌倒，就从哪里爬起来！",
            "Clear 5 words out of your Wrong Book through practice and empty it — rise from where you fell!",
            AchievementCategory.REVIEW, ProgressSource.WRONG_BOOK_CLEARED, 5
    ),

    ACCURACY_90(
            "accuracy_90",
            "精准射手",
            "Sharpshooter",
            "累计答题 100 题以上，并保持 90% 以上的正确率——稳定才是最难的！",
            "Answer 100+ questions total while keeping 90%+ accuracy — consistency is the hardest skill!",
            AchievementCategory.LEARNING, ProgressSource.ACCURACY_PCT, 90
    ),

    DUEL_FIRST_WIN(
            "duel_first_win",
            "初战告捷",
            "Duelist",
            "在一场打满的单词对决中拿下首胜——用实力说话！",
            "Win your first full word duel — let your skill do the talking!",
            AchievementCategory.SOCIAL, ProgressSource.NONE, 0
    ),

    HELPFUL(
            "helpful",
            "互助之星",
            "Helping Hand",
            "鼓励 10 位不同玩家的学习动态——照亮别人的路，也照亮自己！",
            "Cheer the learning updates of 10 different players — light the way for others and yourself!",
            AchievementCategory.SOCIAL, ProgressSource.NONE, 0
    ),

    STUDY_DAYS_30(
            "study_days_30",
            "学而不厌",
            "Long Haul",
            "在不同的 30 天里完成过学习会话（无需连续）——坚持三十天，习惯成自然！",
            "Complete a session on 30 different days (not necessarily consecutive) — thirty days makes a habit!",
            AchievementCategory.CONSISTENCY, ProgressSource.STUDY_DAYS, 30
    );

    /**
     * 成就墙的分组类别（plan-achievements §2.1——5 组）。
     * ordinal 即墙内分组排序键（LEARNING → REVIEW → CONSISTENCY → SOCIAL → ECONOMY）。
     */
    public enum AchievementCategory {
        LEARNING, REVIEW, CONSISTENCY, SOCIAL, ECONOMY
    }

    /**
     * 进度行（"距解锁还差 X"，plan-achievements §2.2）的数据来源。
     * NONE = 该成就不展示进度行（纯二态）。
     */
    public enum ProgressSource {
        NONE, TOTAL_CORRECT, TOTAL_SESSIONS, COINS_EARNED, CONVERSATIONS,
        DAILY_STREAK, REVIEW_STREAK, ACCURACY_PCT, STUDY_DAYS, WRONG_BOOK_CLEARED
    }

    private final String id;
    private final String zhName;
    private final String enName;
    private final String zhDesc;
    private final String enDesc;
    private final AchievementCategory category;
    private final ProgressSource progressSource;
    private final int target;

    Achievement(String id, String zhName, String enName, String zhDesc, String enDesc,
                AchievementCategory category, ProgressSource progressSource, int target) {
        this.id = id;
        this.zhName = zhName;
        this.enName = enName;
        this.zhDesc = zhDesc;
        this.enDesc = enDesc;
        this.category = category;
        this.progressSource = progressSource;
        this.target = target;
    }

    /**
     * 兼容构造器（plan-achievements §3.1）：委托全参构造器，默认
     * category=LEARNING / progressSource=NONE / target=0。
     * 既有 16 个常量现已全部走全参声明；保留本构造器只为未来新增常量/外部
     * 引用不因字段扩充而编译失败。
     */
    Achievement(String id, String zhName, String enName, String zhDesc, String enDesc) {
        this(id, zhName, enName, zhDesc, enDesc,
                AchievementCategory.LEARNING, ProgressSource.NONE, 0);
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

    public AchievementCategory getCategory() {
        return category;
    }

    public ProgressSource getProgressSource() {
        return progressSource;
    }

    /**
     * 进度行的目标值；progressSource 为 NONE 时恒为 0（US-ACH-1 单测断言该一致性）。
     */
    public int getTarget() {
        return target;
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
