package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.data.DatabaseManager.DuelStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R10：{@link DuelStatsText} 的纯函数用例。
 *
 * <p>不碰 Bukkit：{@code msg} 用 {@link Map} 假实现（缺键返回空串，与生产侧
 * {@code MessageUtil.getGuiMsg} 的「查不到 → 空」语义对齐），{@code format} 用
 * 固定 {@code value + "%"}，因此断言只依赖本类逻辑，不受语言文件与 locale 影响。
 */
class DuelStatsTextTest {

    /** 文案夹具：键 → 已"转色"的展示文本（这里直接放裸文本，便于断言）。 */
    private static final Map<String, String> MESSAGES = new HashMap<>();

    static {
        MESSAGES.put(DuelStatsText.KEY_LABEL, "决斗战绩");
        MESSAGES.put(DuelStatsText.KEY_VALUE, "{wins} 胜 / {losses} 负 (胜率 {rate})");
        MESSAGES.put(DuelStatsText.KEY_LOADING, "加载中…");
        MESSAGES.put(DuelStatsText.KEY_NONE, "暂无战绩");
        MESSAGES.put(DuelStatsText.KEY_NOTE, "中途退出不计入");
        // KEY_STREAK 刻意不铺：验证"可选键缺失 → 跳过该行"的降级路径。
    }

    private static final Function<String, String> MSG = key -> MESSAGES.getOrDefault(key, "");
    private static final Function<String, String> FORMAT = value -> value + "%";

    private static List<String> render(DuelStats stats, int streak, boolean hide) {
        return DuelStatsText.render(stats, streak, hide, MSG, FORMAT);
    }

    /** 拼接全部行，便于一次性断言「包含 / 不包含」。 */
    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("零场次 → 显示「暂无战绩」，不出现数值行")
    void zeroTotalShowsNone() {
        List<String> lines = render(new DuelStats(0, 0), 0, false);

        assertEquals(2, lines.size(), "标签行 + 暂无战绩行");
        assertEquals("决斗战绩", lines.get(0));
        assertEquals("暂无战绩", lines.get(1));
        assertFalse(joined(lines).contains("胜率"), "零场次不得出现 0% 胜率数值行");
    }

    @Test
    @DisplayName("hideValues → 只返回标签行，连「有没有打过」都不透露")
    void hideValuesReturnsLabelOnly() {
        List<String> lines = render(new DuelStats(9, 1), 3, true);

        assertEquals(1, lines.size(), "隐私态只允许标签行");
        assertEquals("决斗战绩", lines.get(0));
        String text = joined(lines);
        assertFalse(text.contains("9"), "隐藏态不得泄露胜场数");
        assertFalse(text.contains("胜率"), "隐藏态不得泄露胜率");
        assertFalse(text.contains("暂无战绩"), "隐藏态连「无战绩」也不该出现（会泄露是否打过）");
    }

    @Test
    @DisplayName("全胜 → 胜率 100%")
    void allWinsIsHundredPercent() {
        List<String> lines = render(new DuelStats(5, 0), 0, false);

        assertTrue(joined(lines).contains("5 胜 / 0 负 (胜率 100%)"), joined(lines));
    }

    @Test
    @DisplayName("全负 → 胜率 0%")
    void allLossesIsZeroPercent() {
        List<String> lines = render(new DuelStats(0, 4), 0, false);

        assertTrue(joined(lines).contains("0 胜 / 4 负 (胜率 0%)"), joined(lines));
    }

    @Test
    @DisplayName("混合战绩 → 百分比四舍五入（2/3 ≈ 66.67 → 67%）")
    void mixedRoundsToNearestPercent() {
        assertAll(
                () -> assertTrue(joined(render(new DuelStats(2, 1), 0, false)).contains("67%"),
                        "2/3 应四舍五入为 67%"),
                () -> assertTrue(joined(render(new DuelStats(1, 2), 0, false)).contains("33%"),
                        "1/3 应四舍五入为 33%"),
                () -> assertTrue(joined(render(new DuelStats(1, 7), 0, false)).contains("13%"),
                        "1/8 = 12.5% 应进位为 13%")
        );
    }

    @Test
    @DisplayName("零场次不产生 NaN / Infinity 文本")
    void zeroTotalNeverProducesNaN() {
        String text = joined(render(new DuelStats(0, 0), 0, false));

        assertFalse(text.contains("NaN"), text);
        assertFalse(text.contains("Infinity"), text);
        assertFalse(text.contains("%"), "零场次走「暂无战绩」分支，连百分号都不该出现");
    }

    @Test
    @DisplayName("streak 分支：>0 且 i18n 键存在时追加连胜行；==0 不追加")
    void streakLineOnlyWhenPositiveAndKeyPresent() {
        Map<String, String> withStreak = new HashMap<>(MESSAGES);
        withStreak.put(DuelStatsText.KEY_STREAK, "当前 {streak} 连胜");
        Function<String, String> msgWithStreak = key -> withStreak.getOrDefault(key, "");

        List<String> positive = DuelStatsText.render(
                new DuelStats(3, 1), 3, false, msgWithStreak, FORMAT);
        List<String> zero = DuelStatsText.render(
                new DuelStats(3, 1), 0, false, msgWithStreak, FORMAT);

        assertTrue(joined(positive).contains("当前 3 连胜"), joined(positive));
        assertFalse(joined(zero).contains("连胜"), "streak == 0 不得显示连胜行");
        assertEquals(positive.size(), zero.size() + 1, "连胜行恰好占一行");
    }

    @Test
    @DisplayName("可选键缺失（连胜键未落地）→ 静默跳过，不把占位符吐给玩家")
    void missingOptionalKeyIsSkippedSilently() {
        List<String> lines = render(new DuelStats(3, 1), 5, false);

        assertFalse(joined(lines).contains("streak"), "不得出现 key 名");
        assertFalse(joined(lines).contains("["), "不得出现 [?key] 形式的占位符");
        assertFalse(joined(lines).contains("{"), "不得残留未替换的占位符");
        assertEquals(3, lines.size(),
                "键缺失时只应有 标签 + 数值 + 备注 三行（连胜行被跳过）");
    }

    @Test
    @DisplayName("L4：连胜行在隐藏态绝不渲染（连「最近在赢」都不透露）")
    void streakLineIsHiddenWhenValuesHidden() {
        Map<String, String> withStreak = new HashMap<>(MESSAGES);
        withStreak.put(DuelStatsText.KEY_STREAK, "&e连胜：&f{streak} 场");
        Function<String, String> msgWithStreak = key -> withStreak.getOrDefault(key, "");

        List<String> hidden = DuelStatsText.render(
                new DuelStats(9, 1), 7, true, msgWithStreak, FORMAT);

        assertEquals(1, hidden.size(), "隐藏态只允许标签行 —— 连胜行必须一并消失");
        String text = joined(hidden);
        assertFalse(text.contains("连胜"), "隐藏态不得渲染连胜行喵：" + text);
        assertFalse(text.contains("7"), "隐藏态不得泄露连胜场数");
    }

    @Test
    @DisplayName("L4：streak 只接受正整数 —— 0 / 负数都不渲染（语义 = 无连胜）")
    void nonPositiveStreakNeverRenders() {
        Map<String, String> withStreak = new HashMap<>(MESSAGES);
        withStreak.put(DuelStatsText.KEY_STREAK, "连胜 {streak}");
        Function<String, String> msgWithStreak = key -> withStreak.getOrDefault(key, "");

        assertFalse(joined(DuelStatsText.render(
                new DuelStats(3, 1), 0, false, msgWithStreak, FORMAT)).contains("连胜"),
                "streak=0 不应渲染连胜行");
        assertFalse(joined(DuelStatsText.render(
                new DuelStats(3, 1), -2, false, msgWithStreak, FORMAT)).contains("连胜"),
                "streak<0（脏数据）不应渲染连胜行");
    }

    @Test
    @DisplayName("L4：连胜行位置在数值行之后、备注行之前（备注必须是最后一行）")
    void streakLineSitsBetweenValueAndNote() {
        Map<String, String> withStreak = new HashMap<>(MESSAGES);
        withStreak.put(DuelStatsText.KEY_STREAK, "连胜 {streak}");
        Function<String, String> msgWithStreak = key -> withStreak.getOrDefault(key, "");

        List<String> lines = DuelStatsText.render(
                new DuelStats(3, 1), 4, false, msgWithStreak, FORMAT);

        assertEquals(4, lines.size(), "标签 + 数值 + 连胜 + 备注");
        assertTrue(lines.get(1).contains("胜"), "第 2 行应是数值行：" + lines);
        assertTrue(lines.get(2).contains("连胜 4"), "第 3 行应是连胜行：" + lines);
        assertEquals("中途退出不计入", lines.get(3), "备注行必须仍在最后");
    }

    @Test
    @DisplayName("数值行必带备注行 —— 防「虚高胜率无解释」")
    void valueLineAlwaysAccompaniedByNote() {
        List<String> lines = render(new DuelStats(4, 2), 2, false);

        assertTrue(joined(lines).contains("胜率"), "应含数值行");
        assertEquals("中途退出不计入", lines.get(lines.size() - 1), "备注行必须是末尾最后一行");
    }

    @Test
    @DisplayName("超长数字（int 上界）不抛异常，胜率仍被钳制在 0~100")
    void extremeNumbersDoNotThrow() {
        List<String> lines = render(new DuelStats(Integer.MAX_VALUE, Integer.MAX_VALUE), 1, false);

        assertNotNull(lines);
        String text = joined(lines);
        assertTrue(text.contains(String.valueOf(Integer.MAX_VALUE)), "胜负数照原样显示");
        assertFalse(text.contains("NaN"), text);
        // 1:1 胜率 = 50%；同时覆盖 total() int 溢出（wins+losses 已溢出为 -2）的路径
        assertTrue(text.contains("50%"), text);
    }

    @Test
    @DisplayName("null stats 不抛异常（防御：DB 契约违约也不该打崩主页）")
    void nullStatsIsTreatedAsEmpty() {
        List<String> lines = render(null, 0, false);

        assertEquals(2, lines.size());
        assertEquals("暂无战绩", lines.get(1));
        assertEquals(0, DuelStatsText.percent(null), "null 的百分比应按 0 处理");
    }
}
