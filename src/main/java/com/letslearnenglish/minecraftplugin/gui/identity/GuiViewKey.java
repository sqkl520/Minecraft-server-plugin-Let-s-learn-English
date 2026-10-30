package com.letslearnenglish.minecraftplugin.gui.identity;

/**
 * 插件自有 GUI 的「屏幕身份键」（R5-0）。
 *
 * <p>为什么需要这个枚举：迁移前插件靠 {@code title.contains(...)} 白名单认界面
 * （{@code GUIUtils.isPluginGUI(String)}），标题是本地化文案，管理员可改、语言可切、
 * 收纳了占位符与后缀，任何一处文案改动都会让判定静默失效。R5-0 的目标是让「这个
 * inventory 是谁开的」由 {@code InventoryHolder} 直接回答（结构身份），标题不参与判定
 * （R5-13 起标题层已整体删除；R5-16 注释对账）。
 *
 * <p>粒度约定：本枚举与全仓 21 处 {@code Bukkit.createInventory(...)} 一一对应，
 * 而不是与 18 个 GUI 类一一对应 —— 同一个类里「同标题、不同屏幕」的两页必须拆成
 * 两个 key，否则 holder 身份退化成标题判定，白拆一场。已拆的三组：
 * <ul>
 *   <li>{@link #SQUARE}（54 格主广场） vs {@link #SQUARE_HIDDEN}（27 格隐身笑话页，
 *       两者标题完全相同，只能靠 holder 区分）</li>
 *   <li>{@link #CHAT_HISTORY_LIST} vs {@link #CHAT_HISTORY_SESSION}</li>
 *   <li>{@link #PROFILE} vs {@link #PROFILE_SETTINGS}</li>
 * </ul>
 *
 * <p>禁止事项：不要在枚举里塞行为（点击分发、槽位表、i18n 键）。这里只回答
 * 「是哪个屏幕」，行为由各 Wave 2 流按 key 分派。新增屏幕时只需在
 * {@code createInventory(...)} 处挂 {@link PluginGuiHolder#of(GuiViewKey)}，
 * <b>不需要</b>同步任何标题映射 —— R5-13 起标题层已整体删除，holder 是唯一身份。
 */
public enum GuiViewKey {

    /**
     * 主菜单 —— {@code gui.MainMenu}。
     * 尺寸可变：{@code gui.menu-size} 可配 27/36/45/54（非法回落 27），故构造值取 -1
     * 表示「非固定」。holder 只回答「是本菜单」，尺寸由 MainMenu 自行核对。
     */
    MAIN_MENU(-1),

    /**
     * 单词学习入口页 —— {@code gui.WordLearningGUI}（标题 {@code word-learning-gui.title}）。
     * 27 格（{@code MENU_SIZE = 27}）。
     */
    WORD_LEARNING(27),

    /**
     * 词库选择页 —— {@code gui.WordBankSelectGUI}（标题 {@code word-bank.title}）。
     * 27 格。
     */
    WORD_BANK(27),

    /**
     * 情景对话场景选择页 —— {@code gui.ScenarioSelectGUI}（标题 {@code dialogue.scenario-title}）。
     * 27 格。
     */
    SCENARIO_SELECT(27),

    /**
     * 个人场景管理页 —— {@code gui.PersonalScenarioGUI}（标题 {@code scenario.manage-title}）。
     * 54 格。
     */
    PERSONAL_SCENARIO(54),

    /**
     * 个人主页（本人 / 他人主页共用）—— {@code gui.ProfileGUI#open}。
     * 标题为 {@code profile.title}（本人）或 {@code profile.viewing-title}（他人）。
     * 27 格。
     */
    PROFILE(27),

    /**
     * 隐私设置页 —— {@code gui.ProfileGUI#openSettings}（标题 {@code profile.settings-title}）。
     * 27 格；与 {@link #PROFILE} 同属 ProfileGUI 但是独立屏幕。
     */
    PROFILE_SETTINGS(27),

    /**
     * 英语广场主页面（含周榜/月榜/总榜/动态四种模式）—— {@code gui.EnglishSquareGUI#open}。
     * 54 格；标题含 {@code {page}}，模式标签拼在 " | " 之后。
     */
    SQUARE(54),

    /**
     * 英语广场「隐身玩笑页」—— {@code gui.EnglishSquareGUI#openHidden}。
     * 27 格。注意：标题与 {@link #SQUARE} 逐字相同（都取 {@code square.title} 并把
     * {@code {page}} 填成 "1/1"），标题无法区分，只有 holder 能区分。
     */
    SQUARE_HIDDEN(27),

    /**
     * 排行榜 —— {@code gui.LeaderboardGUI}（标题 {@code leaderboard.title} + tab 名 + 赛季行）。
     * 54 格。
     */
    LEADERBOARD(54),

    /**
     * 成就墙 —— {@code gui.AchievementWallGUI}（标题 {@code achievement-wall.title} + " (n/m)"）。
     * 54 格。
     */
    ACHIEVEMENT_WALL(54),

    /**
     * 学习日历 / 过往学习 —— {@code gui.LearningCalendarGUI}。
     * 标题为 {@code calendar.title} 或 {@code calendar.viewing-title}（含 {@code {player}}）。
     * 54 格。
     */
    LEARNING_CALENDAR(54),

    /**
     * 每日挑战 —— {@code gui.DailyChallengeGUI}（标题 {@code daily.title}）。
     * 27 格。
     */
    DAILY_CHALLENGE(27),

    /**
     * 挑战完成庆祝页 —— {@code gui.ChallengeCompleteGUI}（标题 {@code daily-challenge.complete-title}）。
     * 27 格。
     */
    CHALLENGE_COMPLETE(27),

    /**
     * AI 对话历史列表页 —— {@code gui.ChatHistoryGUI#openWithData}（标题 {@code chat-history.title}）。
     * 54 格。
     */
    CHAT_HISTORY_LIST(54),

    /**
     * AI 对话会话详情页 —— {@code gui.ChatHistoryGUI#openSession}（标题 {@code chat-history.session-title}）。
     * 54 格。
     */
    CHAT_HISTORY_SESSION(54),

    /**
     * 单词对决对战页 —— {@code gui.DuelGUI}（标题 {@code duel.gui-title}，打开时截断到
     * 32 个可见字符）。27 格。
     */
    DUEL(27),

    /**
     * 发起对决的对手选择页 —— {@code gui.DuelChallengeGUI}（标题 {@code duel.challenge-title}）。
     * 54 格。
     */
    DUEL_CHALLENGE(54),

    /**
     * 班级榜 —— {@code gui.ClassBoardGUI}（标题 {@code class.board-title}，含 {@code {class}}）。
     * 54 格。
     */
    CLASS_BOARD(54),

    /**
     * 错题本 —— {@code gui.WrongBookGUI}（标题 {@code wrong-book.title-page}，含 {@code {page}}）。
     * 54 格。
     */
    WRONG_BOOK(54),

    /**
     * 语言切换二确页 —— {@code gui.ConfirmationGUI}（标题 {@code language.confirm-title}）。
     * 27 格；两个确认阶段共用同一个 key。
     */
    CONFIRMATION(27);

    /**
     * 该屏幕的固定 inventory 尺寸；-1 表示「非固定」（目前只有主菜单，随配置变化）。
     *
     * <p>R5-12：只作为<b>源码内文档</b>保留 —— 枚举声明处的 {@code WORD_LEARNING(27)}
     * 本身就表达了「这一页 27 格」，读者无需调用任何方法。原先配套的
     * {@code size()} / {@code hasFixedSize()} 两个 public 访问器在全仓（含测试）
     * 零调用点，留着只会让下次审计再按死代码报一遍，故一并删除；
     * 字段不删，因为它承载的信息（尺寸事实）仍要写在源码里。
     * 将来若诊断侧需要遍历尺寸，再按需加回一个访问器即可。
     */
    private final int size;

    GuiViewKey(int size) {
        this.size = size;
    }
}
