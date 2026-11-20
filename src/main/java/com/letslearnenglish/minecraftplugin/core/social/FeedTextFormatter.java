package com.letslearnenglish.minecraftplugin.core.social;

import com.letslearnenglish.minecraftplugin.core.achievement.Achievement;

import java.util.regex.Pattern;

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
 *
 * <p><b>净化职责（D8）</b>：调用方会对本层输出直接做
 * {@code ChatColor.translateAlternateColorCodes('&', ...)}，所以玩家可控的占位符取值
 * （名字、回落原值的 mode / achievement id）必须在本层<b>拼进模板之前</b>剥掉颜色码
 * 与格式码，见 {@link #sanitizePlayerText(String)}。之所以放在本层而不是调用方：
 * 本层是唯一拼接点，五处 {@code {player}} 只有一处入口可守。本层仍<b>不</b>依赖
 * Bukkit（{@code ChatColor} 那套在这里不够用 —— 它不认 {@code &} 前缀，实测见方法注释）。
 */
public final class FeedTextFormatter {

    private FeedTextFormatter() {
        // Utility class — cannot be instantiated
    }

    /**
     * 名字里可能出现的 hex 颜色码（{@code &#rrggbb}）：必须先于单字符码剥离，
     * 否则 {@code #} 与六位数字会作为可见文本残留在名字里。
     */
    private static final Pattern AMP_HEX = Pattern.compile("[&§]#[0-9a-fA-F]{6}");

    /**
     * Bungee/Spigot 风格的「展开式」hex 码（{@code &x&f&f&0&0&0&0}）：同样必须先剥，
     * 否则前两个符号被单字符规则吃掉后会留下 {@code &f&f&0&0&0&0} 之类的残渣
     * —— 而残渣里那些 {@code &f} 到了下游仍会被 {@code translateAlternateColorCodes}
     * 再次点亮。
     */
    private static final Pattern AMP_LEGACY_HEX =
            Pattern.compile("[&§]x(?:[&§][0-9a-fA-F]){6}", Pattern.CASE_INSENSITIVE);

    /**
     * 单字符码：颜色 {@code 0-9a-f}、格式 {@code k-o}（{@code k} 乱码 / {@code l} 粗体 /
     * {@code m} 删除线 / {@code n} 下划线 / {@code o} 斜体）、{@code r} 重置。
     *
     * <p>格式码<b>必须一起剥</b>：只剥颜色的话 {@code &l}/{@code &k} 照样生效，
     * 名字仍能变成粗体乱码条 —— 那同样是伪造发言者外观。
     */
    private static final Pattern AMP_CODES = Pattern.compile("&[0-9a-fk-orA-FK-OR]");

    /** 已经是 {@code §} 形态的码（enum 名之外的大小写一并覆盖）。 */
    private static final Pattern SECTION_CODES = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    /** 名字里的换行会把一条 lore 行劈成两条 —— 顺带收掉（只收控制字符，不动正文）。 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\r\\n\\u0000]");

    /** 名字全由颜色码组成（剥完为空）时的占位，与 ChatAnswerListener 同口径。 */
    private static final String SANITIZED_NAME_FALLBACK = "?";

    /**
     * 玩家可控文本进模板前必须先剥色：名字里的 {@code &c} / {@code &l} / {@code &k}
     * 会在下游 {@code translateAlternateColorCodes} 阶段变成真实格式码。
     *
     * <p><b>为什么不直接用 {@code ChatColor.stripColor}</b>：实测（paper-api 1.21.4）
     * 它对 {@code &} 前缀<b>完全无效</b> —— {@code stripColor("&c&lAdmin")} 原样返回
     * {@code "&c&lAdmin"}，只对已经转义过的 {@code §}（U+00A7）生效。而本层的输出
     * 正是<b>未转义</b>的 {@code &} 形态（转义发生在调用方），此处在转义之前拦截，
     * 用 {@code stripColor} 等于什么都没洗。故改用下面三条正则显式剥。
     *
     * <p><b>只剥名字，不剥模板</b>：模板里的 {@code &6}/{@code &7} 是设计好的配色，
     * 若对整个拼好的行做剥离会让整个广场界面失色。本方法只作用于 {@code {player}}
     * 这一个占位符的取值。
     *
     * <p><b>缺陷可达性</b>：名字写侧是 {@code PlayerJoinListener} 的
     * {@code event.getPlayer().getName()}，Minecraft 账号名合法字符集为
     * {@code [A-Za-z0-9_]}，{@code &} 进不来 —— 故本条在当前生产路径上<b>不可达</b>，
     * 属纵深防御。但 {@code player_data.player_name} 是普通 VARCHAR(32) 列，
     * 外部工具、跨服代理 payload、历史存量或未来「自填显示名」特性都能写入任意字符；
     * 一旦可写，本条就是唯一的拦截点（{@code EnglishSquareGUI} 会对本层输出直接转码）。
     *
     * <p>不做长度截断：写侧 {@code cachePlayerName} 已按列宽截 32，
     * 本层保持「只净化不改变信息量」的纯函数语义（剥色只会变短，不会变长）。
     *
     * <p><b>公开给同值调用方</b>：{@code EnglishSquareGUI} 头像 displayName 与
     * {@code SocialManager} 的鼓励提示同样把 {@code item.authorName()} 交给
     * {@code translateAlternateColorCodes}，是同一个值的另外两个落地点。统一走本方法，
     * 避免各写一套正则后口径漂移（剥 {@code &k} 的分歧就足以让其中一个洞留着）。
     *
     * @param raw 玩家可控文本（可为 null）
     * @return 已剥色文本；null 或剥完为空时回落 {@value #SANITIZED_NAME_FALLBACK}
     */
    public static String sanitizePlayerText(String raw) {
        if (raw == null) {
            return SANITIZED_NAME_FALLBACK;
        }
        String clean = AMP_HEX.matcher(raw).replaceAll("");
        clean = AMP_LEGACY_HEX.matcher(clean).replaceAll("");
        clean = AMP_CODES.matcher(clean).replaceAll("");
        clean = SECTION_CODES.matcher(clean).replaceAll("");
        clean = CONTROL_CHARS.matcher(clean).replaceAll("");
        return clean.isEmpty() ? SANITIZED_NAME_FALLBACK : clean;
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
        // 玩家可控文本（名字）在进入拼接前统一剥色 —— 唯一入口，五处 replace 共用。
        String safePlayer = sanitizePlayerText(playerName);
        switch (eventType) {
            case "SESSION_DONE" -> {
                if (eventsCount > 1) {
                    return t.get("square.feed.session-merged")
                            .replace("{player}", safePlayer)
                            .replace("{count}", String.valueOf(eventsCount));
                }
                String modeKey = modeMessageKey(mode);
                String modeName;
                if (modeKey != null) {
                    // 已知模式：取 i18n 值（当前是纯文本，但语言文件由管理员维护 —— 保留
                    // 其潜在配色，与本层「只剥玩家可控文本、不动模板」的边界一致）。
                    modeName = t.get(modeKey);
                } else if (mode != null && !mode.isEmpty()) {
                    // 未知模式回落 DB 原值（last_mode VARCHAR(16)）：这是玩家/外部可写面，
                    // 与名字同口径剥色 —— 否则「颜色码从 mode 列进来」是同一个洞。
                    modeName = sanitizePlayerText(mode);
                } else {
                    modeName = "(?)";
                }
                return t.get("square.feed.session")
                        .replace("{player}", safePlayer)
                        .replace("{mode}", modeName);
            }
            case "ACHIEVEMENT" -> {
                String achievementName = resolveAchievementName(achievementId, viewerLang);
                if (eventsCount > 1) {
                    return t.get("square.feed.achievement-merged")
                            .replace("{player}", safePlayer)
                            .replace("{count}", String.valueOf(eventsCount))
                            .replace("{achievement}", achievementName);
                }
                return t.get("square.feed.achievement")
                        .replace("{player}", safePlayer)
                        .replace("{achievement}", achievementName);
            }
            case "CHECKIN" -> {
                return t.get("square.feed.checkin")
                        .replace("{player}", safePlayer)
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
