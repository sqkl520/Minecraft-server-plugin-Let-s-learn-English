package com.letslearnenglish.minecraftplugin.gui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GUI Utility methods shared across listener classes.
 */
public class GUIUtils {

    /**
     * 页码提取用正则：标题里第一个出现的整数。
     * 预编译为常量，避免每次调用都重新编译（翻页点击是高频操作）。
     */
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private GUIUtils() {
        // Utility class — cannot be instantiated
    }

    /**
     * 从 GUI 标题里提取页码（第一个出现的整数）。
     *
     * 支持中英两种标题格式，例如：
     *   "Page 2"                      → 2
     *   "English Square - Page 2/3"   → 2
     *   "第2页"                        → 2
     *   "英语广场 - 第1/3页"            → 1
     *   "第 2 / 3 页"                  → 2
     *
     * 注意：不要改回「按空格切分找 Page 词元」的写法 —— 中文标题里没有 "Page" 词元，
     * 会让中文服的「下一页」永远重开第 1 页。
     *
     * @param title 库存界面标题（可含 & 颜色码或已转换的 § 颜色码）
     * @return 标题里第一个整数；标题为 null、不含数字或数字超出 int 范围时返回 1
     */
    public static int extractPageFromTitle(String title) {
        if (title == null) return 1;
        Matcher matcher = PAGE_NUMBER_PATTERN.matcher(title);
        try {
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (NumberFormatException ignored) {
            // 数字过长（超出 int 范围）——按解析失败处理
        }
        return 1;
    }

    /**
     * Check if the given inventory title belongs to a plugin GUI.
     * Used by SoundManager and event listeners to identify plugin-owned inventories.
     */
    public static boolean isPluginGUI(String title) {
        if (title == null) return false;
        // 每条都必须与 messages/zh.yml + messages/en.yml 里实际的 GUI 标题对得上。
        // 标题改名后这里不同步 = 该 GUI 静默丢失打开/点击音效（SoundManager 依赖本方法）。
        // MC-7 终局清理（2026-10-16，Wave 4 4B）：历史死启发式已移除——旧 DialogueGUI
        // 「Dialogue/情景对话」、旧 ProgressGUI「Learning Progress/学习进度」、旧分榜标题
        // 「Weekly/Monthly/All-Time Ranking + 周榜/月榜/总榜」（对应 leaderboard.title-*
        // 键已删；广场标题含这些模式词但恒含「英语广场/English Square」先行命中）、
        // 旧错题本「Difficult Words」（现行标题同时含「Wrong Book」）。
        return title.contains("Let's Learn English")
                || title.contains("一起学英语")
                || ConfirmationGUI.isConfirmGUI(title)
                || title.contains("Word Learning")
                || title.contains("单词学习")
                || title.contains("English Square")
                || title.contains("英语广场")
                || title.contains("Leaderboard") || title.contains("排行榜")
                || title.contains("'s Profile")
                || title.contains("的主页")
                || title.contains("个人主页")
                || title.contains("Privacy Settings")
                || title.contains("隐私设置")
                || title.contains("Achievement Wall")
                || title.contains("成就墙")
                || title.contains("Select Word Bank")
                || title.contains("选择词库")
                || title.contains("Scenario Select")
                || title.contains("场景选择")
                // MC-4：个人场景管理页（scenario.manage-title）——
                // 标题刻意不含"场景选择"/"Scenario Select"子串，与场景选择页互不命中
                || title.contains("My Personal Scenarios")
                || title.contains("我的个人场景")
                // calendar.title（原「学习日历 / Learning Calendar」，已改名为「过往学习 / Study History」）
                || title.contains("过往学习")
                || title.contains("Study History")
                || title.contains("Daily Challenge")
                || title.contains("每日挑战")
                || title.contains("Challenge Complete")
                || title.contains("挑战完成")
                // chat-history.title（原「聊天记录」，实际标题为「AI 对话历史 / AI Chat History」）
                || title.contains("Chat History")
                || title.contains("对话历史")
                || title.contains("Conversation Details")
                || title.contains("对话详情")
                || title.contains("Word Duel")
                || title.contains("单词对决")
                // duel.challenge-title（Phase 3 · MC-1「发起对决」选择器；
                // 标题刻意不含"单词对决"子串——见 DuelChallengeGUI 的标题避碰红线）
                || title.contains("Pick a Duel Opponent")
                || title.contains("选择对决对手")
                || title.contains("Wrong Book")
                || title.contains("错题本")
                // Wave 4 · 4A fan-in：班级榜（class.board-title =「班级榜 - X / Class Board - X」；
                // 与 ClassBoardGUI.isClassBoardGUI 的锚定前缀判定互补——此处白名单只管音效识别）
                || title.contains("班级榜")
                || title.contains("Class Board");
    }
}
