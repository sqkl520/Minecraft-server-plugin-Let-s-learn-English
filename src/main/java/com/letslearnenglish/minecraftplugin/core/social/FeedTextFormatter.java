package com.letslearnenglish.minecraftplugin.core.social;

import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;

/**
 * FeedTextFormatter — 动态流文案组装的纯函数层（plan-social §2.3）。
 *
 * <p>职责边界：只做"事件数据 + viewer 语言 + 隐私开关 → i18n 模板 key + 占位符"
 * 的映射决策，不查库、不碰 Bukkit、不调 MessageUtil —— 便于脱离服务器单测
 * （对齐 INFRA-01 方向）。DB 只存类型/数字/成就 id/模式 id/玩家名，任何面向玩家的
 * 文本都在渲染时按 **viewer 语言**组装（R-86m"按收件人渲染"模式）。
 *
 * <p>此处返回的是**模板 key**（如 {@code square.feed.session-merged}），
 * 调用方（EnglishSquareGUI）负责用 viewer 的 MessageConfig 取值、替换占位符、
 * 转颜色码。成就名经 {@link Achievement#getDisplayName(String)}；模式名复用既有
 * {@code word.mode-*} 键，由调用方解析。
 */
public final class FeedTextFormatter {

    private FeedTextFormatter() {
        // Utility class — cannot be instantiated
    }

    /** 供调用方取模式显示名的 i18n key 后缀；未知模式回落原值（调用方判断 null）。 */
    public static String modeMessageKey(String mode) {
        if (mode == null) return null;
        return switch (mode) {
            case "spelling" -> "word.mode-spelling";
            case "choice" -> "word.mode-choice";
            case "fill_blank" -> "word.mode-fill_blank";
            case "review" -> "word.mode-review";
            default -> null;
        };
    }

    /**
     * 组装动态条目的**事件行**（条目 displayName 或 lore 首行）。
     *
     * <p>选键规则（plan-social §1.4 合并展示语义）：
     * <ul>
     *   <li>{@code SESSION_DONE}：{@code events_count > 1} → session-merged（{count}）；
     *       否则 session（{player}/{mode}）；</li>
     *   <li>{@code ACHIEVEMENT}：{@code events_count > 1} → achievement-merged
     *       （{count} + 最新成就名）；否则 achievement（成就名按 viewer 语言）；</li>
     *   <li>{@code CHECKIN}：checkin（{streak}）；</li>
     *   <li>未知类型：null（条目损坏兜底，调用方跳过）。</li>
     * </ul>
     *
     * @param eventType      事件类型
     * @param eventsCount    当日同类型合并次数
     * @param playerName     作者名（语言中立）
     * @param mode           最近会话模式 id（可 null）
     * @param streak         最近连续签到天数（CHECKIN 用）
     * @param achievementId  最近解锁成就 id（可 null；查不到定义时回落 id 原值）
     * @param viewerLang     viewer 语言（成就名渲染用，zh/en）
     * @param t              i18n 取值函数（key → 模板原文，测试可注入假实现）
     * @return 已替换占位符的事件行原文（未转颜色码）；未知事件类型返回 null
     */
    public static String buildEventLine(String eventType, int eventsCount, String playerName,
                                        String mode, int streak, String achievementId,
                                        String viewerLang, KeyLookup t) {
        if (eventType == null) return null;
        switch (eventType) {
            case "SESSION_DONE" -> {
                if (eventsCount > 1) {
                    return t.get("square.feed.session-merged")
                            .replace("{player}", playerName)
                            .replace("{count}", String.valueOf(eventsCount));
                }
                String modeKey = modeMessageKey(mode);
                String modeName;
                if (modeKey != null) {
                    modeName = t.get(modeKey);
                } else if (mode != null && !mode.isEmpty()) {
                    modeName = mode; // 未知模式回落原值，信息不丢（同 LearnCommand 风格）
                } else {
                    modeName = "(?)";
                }
                return t.get("square.feed.session")
                        .replace("{player}", playerName)
                        .replace("{mode}", modeName);
            }
            case "ACHIEVEMENT" -> {
                String achievementName = resolveAchievementName(achievementId, viewerLang);
                if (eventsCount > 1) {
                    return t.get("square.feed.achievement-merged")
                            .replace("{player}", playerName)
                            .replace("{count}", String.valueOf(eventsCount))
                            .replace("{achievement}", achievementName);
                }
                return t.get("square.feed.achievement")
                        .replace("{player}", playerName)
                        .replace("{achievement}", achievementName);
            }
            case "CHECKIN" -> {
                return t.get("square.feed.checkin")
                        .replace("{player}", playerName)
                        .replace("{streak}", String.valueOf(streak));
            }
            default -> {
                return null; // 未知道具（旧版本行/脏数据）—— 调用方跳过该条目
            }
        }
    }

    /**
     * 组装**数字行**（lore 第二行），仅 SESSION_DONE 有：
     * {@code showResults=false} → hidden-detail（通用降级文案，与榜单展示路径同口径的
     * 隐私延伸，plan-social §1.6）；否则 session-detail（答对 X/Y）。
     * 非 SESSION_DONE 事件无数字行（返回 null）。
     */
    public static String buildDetailLine(String eventType, boolean showResults,
                                         int sumCorrect, int sumTotal, KeyLookup t) {
        if (!"SESSION_DONE".equals(eventType)) return null;
        if (!showResults) {
            return t.get("square.feed.hidden-detail");
        }
        return t.get("square.feed.session-detail")
                .replace("{correct}", String.valueOf(sumCorrect))
                .replace("{total}", String.valueOf(sumTotal));
    }

    /** 成就名按 viewer 语言渲染（zh/en）；id 查不到定义时回落 id 原值（可见且可定位）。 */
    public static String resolveAchievementName(String achievementId, String viewerLang) {
        if (achievementId == null || achievementId.isEmpty()) return "?";
        Achievement achievement = Achievement.fromId(achievementId);
        return achievement != null ? achievement.getDisplayName(viewerLang) : achievementId;
    }

    /** i18n 取值函数（生产= MessageConfig 查表；测试= Map 假实现）。 */
    @FunctionalInterface
    public interface KeyLookup {
        String get(String key);
    }
}
