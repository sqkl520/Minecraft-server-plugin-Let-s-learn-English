package com.letslearnenglish.minecraftplugin.core.duel;

import com.letslearnenglish.minecraftplugin.data.DatabaseManager.DuelStats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * R10：决斗战绩的文案组装纯函数层（照 {@code social.FeedTextFormatter} 的范式）。
 *
 * <p><b>职责边界</b>：只做「战绩数据 + 隐私开关 → 展示行」的映射，不查库、不碰
 * Bukkit、不调 {@link com.letslearnenglish.minecraftplugin.util.MessageUtil}，因此可以在
 * 纯 JVM 里单测。i18n 取值与颜色码转换通过注入的 {@code msg} 完成（生产侧 =
 * {@code MessageUtil.getGuiMsg} + {@code ChatColor.translateAlternateColorCodes}，
 * 测试侧 = {@code Map} 查表）。
 *
 * <p><b>为什么数值行必须跟一行 note</b>：{@code duel_stats} 表只记录「正常打满」的对局 ——
 * 中途退出（{@code /le duel quit}）、掉线超时判负、管理员 {@code /lea duel cancel} 解散
 * <b>都不落库</b>（见 {@code DatabaseManager.recordDuelResult} 的调用点）。因此表中胜率
 * 天然只覆盖一个子集：不解释这句话，玩家会看到偏高的胜率却不知道原因。
 *
 * <p><b>隐私口径</b>：{@code hideValues == true}（他人主页且对方关闭 showResults）时
 * 只返回标签行，与 {@code ProfileGUI} 的 slot 11/12/14 同口径 —— 标签保留、数值全隐。
 * 隐私优先于「暂无战绩」：隐藏态下连「有没有打过」都不透露。
 */
public final class DuelStatsText {

    /** 标题/标签行（物品 displayName）。 */
    public static final String KEY_LABEL = "profile.stats-duel-label";
    /** 数值行模板，占位符 {@code {wins}} / {@code {losses}} / {@code {rate}}。 */
    public static final String KEY_VALUE = "profile.stats-duel-value";
    /** 异步取数期间的占位行。 */
    public static final String KEY_LOADING = "profile.stats-duel-loading";
    /** 零场次时的替代行。 */
    public static final String KEY_NONE = "profile.stats-duel-none";
    /** forfeit / 掉线 / 管理员解散不计入的说明行。 */
    public static final String KEY_NOTE = "profile.stats-duel-note";
    /**
     * 连胜行模板，占位符 {@code {streak}}。
     *
     * <p><b>接线现状（L4 之后）</b>：{@code ProfileGUI} 的异步段在同一批里调用
     * {@code DatabaseManager#getDuelWinStreak(target)}（从最近一场往回数、遇负即停，
     * 最多扫 50 场），然后把结果作为 {@code streak} 形参传进 {@link #render}。
     * 因此本键一旦写进 {@code messages/zh.yml} / {@code en.yml} 就会<b>立刻显示</b>，
     * 无需再动代码 —— 但"显示"的前提仍是上面那条查询链存在：删掉
     * {@code ProfileGUI} 里那次查询，键还在也只会得到 {@code streak == 0}，这一行照旧跳过。
     *
     * <p><b>键已落地</b>：{@code messages/zh.yml:584}（{@code stats-duel-streak: "&6连胜 &f{streak} 场"}）
     * 与 {@code messages/en.yml:586}（{@code stats-duel-streak: "&6Win streak &f{streak}"}）均已写入，
     * 因此 {@code streak > 0} 时该行会实际显示；{@code streak == 0} 时 {@link #render} 跳过该行。
     * 键仍按「可选」处理：若被删掉，生产侧注入的 {@code msg} 返回空串，{@link #render} 同样跳过 ——
     * 宁可少一行，也不把 {@code [?profile.stats-duel-streak]} 这种调试占位符吐给玩家。
     *
     * <p><b>隐私</b>：{@code hideValues == true} 时 {@link #render} 在<b>任何</b>数值行
     * 之前就返回，连胜行同样不渲染 —— 连胜透露的是"最近在打、而且在赢"，
     * 与胜负/胜率同属学习结果，必须同口径隐藏。
     */
    public static final String KEY_STREAK = "profile.stats-duel-streak";

    private DuelStatsText() {
        // Utility class — cannot be instantiated
    }

    /**
     * 组装战绩物品的三段文本：标签行 / 数值行（可含连胜行）/ 备注行。
     *
     * <p>返回列表的 <b>第 0 项固定是标签行</b>（调用方用作 displayName），其余作为 lore。
     * 调用方不得假设固定长度：隐藏态 1 行、零场次 2 行、正常 3~4 行。
     *
     * @param stats      累计战绩；{@code null} 按 {@code (0, 0)} 处理（防御：DB 层承诺
     *                   非 null，但展示路径不该因一次违约就把整个主页打崩）
     * @param streak     当前连胜场数；{@code <= 0} 不显示连胜行
     * @param hideValues true = 只返回标签行（他人主页且对方关闭 showResults）
     * @param msg        i18n 取值函数：key → 已转颜色码的文案；查不到时<b>应返回空串</b>
     *                   （返回 {@code "[?key]"} 之类占位符会让可选键漏到玩家眼前）
     * @param format     百分比格式化函数：入参是无百分号的整数文本（如 {@code "67"}），
     *                   返回展示文本（生产侧 {@code p -> p + "%"}；注入是为了让单测
     *                   不依赖 locale/千分位）
     * @return 不可变列表，至少 1 项
     */
    public static List<String> render(DuelStats stats, int streak, boolean hideValues,
                                      Function<String, String> msg,
                                      Function<String, String> format) {
        Function<String, String> lookup = (msg != null) ? msg : key -> "";
        Function<String, String> rateFormat = (format != null) ? format : value -> value;

        String label = lookup.apply(KEY_LABEL);

        // 隐私优先：隐藏态连"有没有打过"都不透露，只留标签行。
        if (hideValues) {
            return Collections.singletonList(label);
        }

        DuelStats safe = (stats != null) ? stats : new DuelStats(0, 0);
        // 用 long 判空：DuelStats.total() 是 int 加法，wins+losses 超 Integer.MAX_VALUE
        // 会溢出成负数（脏数据/极端值）。这里不复用 total()，避免把"有战绩"读成"无战绩"。
        long total = (long) safe.wins() + (long) safe.losses();
        if (total <= 0) {
            return Collections.unmodifiableList(List.of(label, lookup.apply(KEY_NONE)));
        }

        List<String> lines = new ArrayList<>(4);
        lines.add(label);
        lines.add(lookup.apply(KEY_VALUE)
                .replace("{wins}", String.valueOf(safe.wins()))
                .replace("{losses}", String.valueOf(safe.losses()))
                .replace("{rate}", rateFormat.apply(String.valueOf(percent(safe)))));

        String streakLine = lookup.apply(KEY_STREAK);
        if (streak > 0 && !streakLine.isEmpty()) {
            lines.add(streakLine.replace("{streak}", String.valueOf(streak)));
        }

        // 数值行存在则必须有备注行：否则"虚高胜率无解释"。
        lines.add(lookup.apply(KEY_NOTE));
        return Collections.unmodifiableList(lines);
    }

    /**
     * 胜率百分比（四舍五入到整数，钳制在 0~100）。
     *
     * <p>{@link DuelStats#winRate()} 返回 <b>0.0~1.0</b>（总场 0 时 0.0，不是 NaN），
     * 因此展示前必须 ×100。{@code DuelStats.total()} 溢出（int 相加）时退化为
     * long 口径重算，避免除零/负数胜率。
     *
     * @param stats 战绩，{@code null} 视为 0
     * @return 0~100 的整数百分比
     */
    public static int percent(DuelStats stats) {
        if (stats == null) return 0;
        int wins = stats.wins();
        long total = (long) wins + (long) stats.losses();
        if (wins <= 0 || total <= 0) return 0;
        double rate = (stats.total() > 0) ? stats.winRate() : (double) wins / (double) total;
        long rounded = Math.round(rate * 100.0);
        if (rounded < 0) return 0;
        if (rounded > 100) return 100;
        return (int) rounded;
    }
}
