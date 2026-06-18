package com.letslearnenglish.minecraftplugin.gui;

/**
 * GUI Utility methods shared across listener classes.
 */
public class GUIUtils {

    private GUIUtils() {
        // Utility class — cannot be instantiated
    }

    /**
     * Check if the given inventory title belongs to a plugin GUI.
     * Used by SoundManager and event listeners to identify plugin-owned inventories.
     */
    public static boolean isPluginGUI(String title) {
        if (title == null) return false;
        return title.contains("Let's Learn English")
                || title.contains("一起学英语")
                || ConfirmationGUI.isConfirmGUI(title)
                || title.contains("Word Learning")
                || title.contains("单词学习")
                || title.contains("Dialogue")
                || title.contains("情景对话")
                || title.contains("Learning Progress")
                || title.contains("学习进度")
                || title.contains("English Square")
                || title.contains("英语广场")
                || title.contains("周榜") || title.contains("月榜") || title.contains("总榜")
                || title.contains("Weekly Ranking") || title.contains("Monthly Ranking")
                || title.contains("All-Time Ranking")
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
                || title.contains("学习日历")
                || title.contains("Learning Calendar")
                || title.contains("Daily Challenge")
                || title.contains("每日挑战")
                || title.contains("Challenge Complete")
                || title.contains("挑战完成")
                || title.contains("Chat History")
                || title.contains("聊天记录")
                || title.contains("Conversation Details")
                || title.contains("对话详情")
                || title.contains("Word Duel")
                || title.contains("单词对决")
                || title.contains("Wrong Book")
                || title.contains("错题本")
                || title.contains("Difficult Words");
    }
}
