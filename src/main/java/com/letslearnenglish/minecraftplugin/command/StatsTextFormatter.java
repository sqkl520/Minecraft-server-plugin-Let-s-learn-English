package com.letslearnenglish.minecraftplugin.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * {@code /le stats <玩家名>} 的战绩文案组装纯函数层（照 {@code core.social.FeedTextFormatter}
 * 与 {@code core.duel.DuelStatsText} 的既有范式）。
 *
 * <p><b>为什么单独一层</b>：{@link LearnCommand} 的构造依赖 Bukkit（命令执行器、调度器、
 * 一堆 Manager），纯 JVM 起不来 —— 于是战绩行的格式（尤其是正确率的小数点、隐藏态有没有
 * 漏数）就成了「只能靠人工在服里看一眼」的地方。把「数据 → 行」的映射抽到本类后，
 * {@code StatsTextFormatTest} 可以直接调它，不需要任何 Bukkit 替身。
 *
 * <p><b>为什么形参全是裸数字、不直接收 {@code PlayerDataStore.PlayerStats}</b>：后者虽然
 * 也是普通静态类，但它与 {@code PlayerDataStore} 同文件；一旦将来有人给嵌套类加了对
 * 外层字段的引用，测试就会被迫拖进整条 DB 依赖链。裸数字把这条耦合断在编译期
 * （这与 {@code DuelStatsText.render} 收 {@code DuelStats} 的取舍不同，因为那个 record
 * 自带一个非平凡的方法 {@code winRate()}，这里的 {@code PlayerStats} 只是四个 getter）。
 *
 * <p><b>为什么输出必须不带颜色码</b>：本层产出的行会被原样回传 QQ 群（调用方以控制台身份
 * 执行命令，机器人把控制台输出转发出去）。{@code §6} / {@code &6} 在那边是乱码字符，
 * {@code {prefix}} 会白占一行。因此 {@code messages/zh.yml} / {@code en.yml} 里
 * {@code command.stats-text.*} 这批文案刻意既无颜色码也无前缀，本层也不追加任何装饰。
 * 玩家在游戏内执行时看到的是同一份纯文本 —— 这是刻意接受的权衡：一套文案服务两个通道，
 * 避免双份文案各自漂移。
 *
 * <p><b>i18n 通过注入的 {@code msg} 完成</b>（生产侧 {@code MessageUtil.getPlayerMessage} /
 * {@code getMessage}，测试侧 {@code Map} 查表），因此本层不碰 Bukkit、不读配置、不切线程。
 */
public final class StatsTextFormatter {

    /** 标题行，占位符 {@code {player}}。 */
    public static final String KEY_HEADER = "command.stats-text.header";
    /** 总学习分，占位符 {@code {score}}。 */
    public static final String KEY_SCORE = "command.stats-text.score";
    /** 答对数，占位符 {@code {correct}}。 */
    public static final String KEY_CORRECT = "command.stats-text.correct";
    /** 答错数，占位符 {@code {incorrect}}。 */
    public static final String KEY_INCORRECT = "command.stats-text.incorrect";
    /** 正确率，占位符 {@code {accuracy}}（已带 {@code %}，见 {@link #formatAccuracy}）。 */
    public static final String KEY_ACCURACY = "command.stats-text.accuracy";
    /** 学习次数，占位符 {@code {sessions}}。 */
    public static final String KEY_SESSIONS = "command.stats-text.sessions";
    /** 成就进度，占位符 {@code {unlocked}} / {@code {total}}。 */
    public static final String KEY_ACHIEVEMENTS = "command.stats-text.achievements";
    /** 隐私降级行，占位符 {@code {player}}。 */
    public static final String KEY_HIDDEN = "command.stats-text.hidden";
    /** 三级查找全部失败，占位符 {@code {player}}。 */
    public static final String KEY_PLAYER_NOT_FOUND = "command.stats-text.player-not-found";
    /** 取数失败（含「读失败后补的空壳」，见 {@code PlayerDataStore.isStatsUnloaded}），无占位符。 */
    public static final String KEY_LOAD_FAILED = "command.stats-text.load-failed";
    /** 用法行（多余参数时回显），无占位符。 */
    public static final String KEY_USAGE = "command.stats-text.usage";

    private StatsTextFormatter() {
        // Utility class — cannot be instantiated
    }

    /**
     * 一次查询的全部输入。字段与「玩家能看到的六个数字」一一对应，不多不少。
     *
     * @param playerName           被查询的玩家名（回显调用方输入，异步段取不到 Bukkit 名册）
     * @param totalScore           总学习分（{@code PlayerStats.getTotalScore()}）
     * @param correct              累计答对（{@code getTotalCorrect()}）
     * @param incorrect            累计答错（{@code getTotalIncorrect()}）
     * @param sessions             学习次数（{@code getTotalSessions()}）
     * @param unlockedAchievements 已解锁成就数（{@code AchievementManager.getUnlockedCount}）
     * @param totalAchievements    成就总数（{@code Achievement.values().length}）
     */
    public record StatsSnapshot(String playerName, long totalScore, int correct, int incorrect,
                                int sessions, int unlockedAchievements, int totalAchievements) {
    }

    /**
     * 隐私降级：只输出「已隐藏」一行，不接收任何统计数字。
     *
     * <p><b>为什么单独一个入口而不是 {@code render(null, true, msg)}</b>：把「隐藏态要
     * 渲染什么」与「有几个数字」在类型上彻底分开 —— 调用方拿不到那个形参，也就不可能
     * 「先取数、再隐藏」。这类「取到再丢弃」的写法一旦被改错就会真泄露，而它在测试里
     * 看起来完全一样。
     *
     * @param playerName 目标玩家名（回显，用于「某某已隐藏成绩」）
     * @param msg        i18n 取值函数
     * @return 单元素不可变列表
     */
    public static List<String> renderHidden(String playerName, Function<String, String> msg) {
        Function<String, String> lookup = (msg != null) ? msg : key -> "";
        String name = (playerName != null) ? playerName : "";
        String hidden = lookup.apply(KEY_HIDDEN);
        if (hidden == null || hidden.isEmpty()) {
            // 只兜「空串」这一种退化：缺键时生产侧的 MessageUtil 已回落成可见的
            // [?key]（fail-visible），而空串在 QQ 群里看起来像机器人坏了。
            return List.of(KEY_HIDDEN);
        }
        return List.of(hidden.replace("{player}", name));
    }

    /**
     * 把一份战绩渲染成「一行一条」的纯文本。
     *
     * @param stats 战绩；{@code null} 按全 0 处理（展示路径不该因一次违约把整条命令打崩）
     * @param msg   i18n 取值函数：key → 已按执行者语言解析好的文案；查不到时应返回
     *              非空的可诊断串（生产侧 {@code MessageUtil} 回落 {@code [?key]}）
     * @return 不可变列表，至少 1 项
     * @see #renderHidden(String, Function) 隐私降级（隐藏态**不**走本方法）
     */
    public static List<String> render(StatsSnapshot stats, Function<String, String> msg) {
        Function<String, String> lookup = (msg != null) ? msg : key -> "";
        StatsSnapshot safe = (stats != null)
                ? stats
                : new StatsSnapshot("", 0L, 0, 0, 0, 0, 0);
        String name = (safe.playerName() != null) ? safe.playerName() : "";

        List<String> lines = new ArrayList<>(8);
        addLine(lines, lookup, KEY_HEADER, "player", name);
        addLine(lines, lookup, KEY_SCORE, "score", String.valueOf(safe.totalScore()));
        addLine(lines, lookup, KEY_CORRECT, "correct", String.valueOf(safe.correct()));
        addLine(lines, lookup, KEY_INCORRECT, "incorrect", String.valueOf(safe.incorrect()));
        addLine(lines, lookup, KEY_ACCURACY, "accuracy", formatAccuracy(safe.correct(), safe.incorrect()));
        addLine(lines, lookup, KEY_SESSIONS, "sessions", String.valueOf(safe.sessions()));
        addLine(lines, lookup, KEY_ACHIEVEMENTS,
                "unlocked", String.valueOf(safe.unlockedAchievements()),
                "total", String.valueOf(safe.totalAchievements()));
        return List.copyOf(lines);
    }

    /**
     * 正确率文本，形如 {@code "75.0%"}。
     *
     * <p><b>{@link Locale#ROOT} 是硬要求，不是风格选择</b>：默认 Locale 在德语/法语环境
     * 输出 {@code "75,0%"}（逗号小数点），第三方插件或 QQ 侧的解析会静默得到 0 或 NaN。
     * 与 {@code LLEPapiExpansion} 的 {@code accuracy} 占位符逐字同口径（同一份数字在两个
     * 出口上不能有两种写法）。
     *
     * <p>除数用 {@code long} 而不是先算 {@code correct + incorrect}（int 相加）——
     * 脏数据下会溢出成负数，从而让「有答题记录」被判成「没答题」。
     *
     * @param correct   累计答对
     * @param incorrect 累计答错
     * @return 0.0% ~ 100.0% 的文本（总题数为 0 或负数时是 {@code "0.0%"}，不抛异常）
     */
    public static String formatAccuracy(int correct, int incorrect) {
        long total = (long) correct + (long) incorrect;
        if (total <= 0) {
            return String.format(Locale.ROOT, "%.1f%%", 0.0);
        }
        double accuracy = (double) correct / (double) total * 100.0;
        if (accuracy < 0.0) {
            accuracy = 0.0;
        } else if (accuracy > 100.0) {
            accuracy = 100.0;
        }
        return String.format(Locale.ROOT, "%.1f%%", accuracy);
    }

    /**
     * 取一条文案、填占位符、追加到 {@code lines}。
     *
     * <p>模板为空时整行跳过（宁可少一行，也不往 QQ 群里塞空消息）；
     * 占位符按 {@code name, value} 成对传入，奇数尾项由 {@code i + 1 < length} 兜住。
     */
    private static void addLine(List<String> lines, Function<String, String> lookup, String key,
                                String... placeholders) {
        String template = lookup.apply(key);
        if (template == null || template.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            template = template.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        lines.add(template);
    }
}