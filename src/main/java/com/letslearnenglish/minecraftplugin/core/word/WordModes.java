package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.config.ConfigManager;
import com.letslearnenglish.minecraftplugin.util.MessageUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WordModes —— 学习模式（spelling / choice / fill_blank）配置的唯一真相源（Phase 3 · MC-3）。
 *
 * <p>把 {@code word-system.modes.*} 的读取收口到一处：模式开关（三模式 enabled）、
 * 默认模式解析（firstEnabledMode）、判分参数（case-sensitive / option-count / time-limit）
 * 与展示参数（show-first-letter）。全部点读 {@link ConfigManager}（不缓存），
 * {@code /lea reload} 即时生效；"同一会话内规则恒定"由调用方
 * （{@link WordLearningManager}）在开局时快照保证，本类不管会话态。
 *
 * <p>命名定案（R-86k）：canonical 键名为下划线 {@code fill_blank}；读取侧双兼容 ——
 * 先读 {@code word-system.modes.fill_blank.*}，miss 回落旧名
 * {@code word-system.modes.fill-blank.*}，存量 config.yml（连字符版）不静默失效。
 *
 * <p>固定序：spelling → choice → fill_blank（默认模式解析与 tab 补全候选共用同一顺序）。
 */
public final class WordModes {

    /** 固定序（默认模式解析 / 补全候选顺序的唯一真相源，与三模式 canonical 名一致）。 */
    private static final List<String> MODE_ORDER = List.of(
            AnswerChecker.MODE_SPELLING,
            AnswerChecker.MODE_CHOICE,
            AnswerChecker.MODE_FILL_BLANK);

    /** option-count 有效范围：&lt;2 无干扰项无意义；&gt;4 超出字母空间（A-D）。 */
    private static final int OPTION_COUNT_MIN = 2;
    private static final int OPTION_COUNT_MAX = 4;

    /** 越界告警防刷屏（每 JVM 生命周期每键只报一次；重启后重新检查）。 */
    private static final Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();

    private WordModes() {
    }

    /**
     * 是否为本插件已知的三模式之一（与 {@link #MODE_ORDER} 同源，大小写不敏感）。
     * 调用方（命令入口）据此区分两种拒绝语义：未知模式回 {@code word.invalid-mode}，
     * 已知但被关闭回 {@code word.mode-disabled} —— 默认全开配置下未知输入的提示
     * 与接线前逐位一致（回归纪律）。
     */
    public static boolean isKnownMode(String mode) {
        return canonicalOrNull(mode) != null;
    }

    /**
     * 模式可用门：{@code word-system.modes.&lt;mode&gt;.enabled}（默认 true＝现状）。
     * mode 大小写不敏感；未知模式恒 false（调用方应先辨"未知"与"被关闭"两种拒绝语义）。
     */
    public static boolean isEnabled(ConfigManager cm, String mode) {
        String canonical = canonicalOrNull(mode);
        if (canonical == null) {
            return false;
        }
        return switch (canonical) {
            case AnswerChecker.MODE_FILL_BLANK -> readFillBlankBoolean(cm, "enabled", true);
            case AnswerChecker.MODE_SPELLING ->
                    cm.getMainConfig().getBoolean("word-system.modes.spelling.enabled", true);
            case AnswerChecker.MODE_CHOICE ->
                    cm.getMainConfig().getBoolean("word-system.modes.choice.enabled", true);
            default -> false;
        };
    }

    /**
     * 固定序第一个 enabled 的模式；三个全关返回 null（入口据此回 {@code word.no-modes-enabled} 拒绝）。
     * 这是"默认模式"的唯一解析点（F2-05 收口：原 LearnCommand / MiscGUIListener 两份私有常量删除）。
     */
    public static String firstEnabledMode(ConfigManager cm) {
        // Wave 2 fan-in 裁决：默认模式解析优先 choice（FEAT-MODE-01 既定语义：
        // "choice 是新手默认模式，与 GUI 入口一致"；也是接线前 DEFAULT_WORD_MODE 的值）。
        // MODE_ORDER 的 spelling 打头仅用于 tab 补全展示序，不改变默认模式——
        // 否则无参 /le word 会从 choice 静默变成 spelling，违反"默认行为逐位一致"。
        if (isEnabled(cm, AnswerChecker.MODE_CHOICE)) {
            return AnswerChecker.MODE_CHOICE;
        }
        for (String mode : MODE_ORDER) {
            if (isEnabled(cm, mode)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * 已启用模式（保序：spelling → choice → fill_blank）—— tab 补全候选的唯一来源，
     * 与 {@link #isEnabled} 校验共用同一真相源。
     */
    public static Set<String> enabledModes(ConfigManager cm) {
        Set<String> enabled = new LinkedHashSet<>();
        for (String mode : MODE_ORDER) {
            if (isEnabled(cm, mode)) {
                enabled.add(mode);
            }
        }
        return Collections.unmodifiableSet(enabled);
    }

    /**
     * 全词文本比较的大小写敏感度（会话级快照源）。键定案：
     * {@code word-system.modes.spelling.case-sensitive}（默认 false＝现状）是唯一敏感度
     * 开关 —— plan §1.(c) 明确其"仅影响全词文本比较"：spelling / fill_blank 的整词判定
     * 与 choice 的"直接输全词"兜底路径都读它；choice 的 A/B/C/D 字母选择恒大小写
     * 不敏感（下标选择器，不经此值）。duel 与错题本练习的判分各自内联 equalsIgnoreCase，
     * 不走 AnswerChecker 三模式链，不受此键影响（文档化）。
     *
     * <p>mode 参数保留用于明示调用的会话语义（当前所有文本比较模式共用同一键值；
     * 若未来按模式细分再扩展读取）。
     */
    public static boolean isCaseSensitive(ConfigManager cm, String mode) {
        return cm.getMainConfig().getBoolean("word-system.modes.spelling.case-sensitive", false);
    }

    /**
     * choice 选项数量：{@code word-system.modes.choice.option-count}（默认 4＝现状）。
     * 读取钳位 [2, 4]（理由见 {@link #OPTION_COUNT_MIN} / {@link #OPTION_COUNT_MAX} 注释），
     * 越界记一次 WARNING（每 JVM 生命周期一次，防高频读取刷屏）。
     */
    public static int optionCount(ConfigManager cm) {
        int value = cm.getMainConfig().getInt("word-system.modes.choice.option-count", 4);
        if (value >= OPTION_COUNT_MIN && value <= OPTION_COUNT_MAX) {
            return value;
        }
        int clamped = Math.max(OPTION_COUNT_MIN, Math.min(OPTION_COUNT_MAX, value));
        if (WARNED_KEYS.add("modes.choice.option-count")) {
            org.bukkit.Bukkit.getLogger().warning("[LetsLearnEnglish] word-system.modes.choice.option-count="
                    + value + " 越界（有效范围 " + OPTION_COUNT_MIN + "-" + OPTION_COUNT_MAX
                    + "），已钳位为 " + clamped + "；本告警每个服务器生命周期只出现一次。");
        }
        return clamped;
    }

    /**
     * choice 单题倒计时秒数：{@code word-system.modes.choice.time-limit}（默认 0）。
     * 语义修正（plan §1.(c)）：0（或负数）= 跟随全局 {@code word-system.answer-timeout}；
     * &gt;0 = 覆盖 choice 会话的单题倒计时。原"0 不限制"语义废止 —— 不存在"永不超时"
     * 路径（无计时器即无超时判错链）。spelling / fill_blank / review 恒用全局值
     * （由快照侧判断，见 {@link WordLearningManager}）。
     */
    public static int timeLimitSeconds(ConfigManager cm) {
        return cm.getMainConfig().getInt("word-system.modes.choice.time-limit", 0);
    }

    /**
     * fill_blank 首字母提示开关：{@code modes.fill_blank.show-first-letter}（默认 true＝现状）。
     * 展示层实时读（reload 即时生效，无会话一致性风险）：false 时 GUI 挖空卡不渲染提示行、
     * 聊天答题不发 {@code fill-blank-hint} 行；题目本身与判定不变。
     */
    public static boolean showFirstLetter(ConfigManager cm) {
        return readFillBlankBoolean(cm, "show-first-letter", true);
    }

    /**
     * PF-MC-11 收口：难度标识符 → i18n label（{@code word.difficulty-*}），未知回落原值
     * （绝不把空值塞进占位符）。原 LearnCommand / MiscGUIListener 两份私有拷贝删除，
     * 统一走此实现；OnboardingManager 的第四份已于 3A-3 收敛（全库单一实现）。
     */
    public static String localizeDifficulty(MessageUtil util, Player player, String difficulty) {
        return switch (difficulty.toLowerCase(Locale.ROOT)) {
            case "beginner" -> util.getPlayerMessage(player, "word.difficulty-beginner");
            case "intermediate" -> util.getPlayerMessage(player, "word.difficulty-intermediate");
            case "advanced" -> util.getPlayerMessage(player, "word.difficulty-advanced");
            default -> difficulty;
        };
    }

    /**
     * PF-MC-11 收口：模式标识符 → i18n label（{@code word.mode-*}），未知回落原值。
     */
    public static String localizeMode(MessageUtil util, Player player, String mode) {
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "spelling" -> util.getPlayerMessage(player, "word.mode-spelling");
            case "choice" -> util.getPlayerMessage(player, "word.mode-choice");
            case "fill_blank" -> util.getPlayerMessage(player, "word.mode-fill_blank");
            default -> mode;
        };
    }

    /** mode 归一化到 canonical 名（大小写不敏感）；未知返回 null。 */
    private static String canonicalOrNull(String mode) {
        if (mode == null) {
            return null;
        }
        for (String canonical : MODE_ORDER) {
            if (canonical.equalsIgnoreCase(mode)) {
                return canonical;
            }
        }
        return null;
    }

    /**
     * fill_blank 段布尔读取（双读兼容，R-86k）：新名 {@code modes.fill_blank.&lt;leaf&gt;}
     * 存在即优先；不存在回落旧名 {@code modes.fill-blank.&lt;leaf&gt;}；两者皆缺回落默认值。
     */
    private static boolean readFillBlankBoolean(ConfigManager cm, String leaf, boolean def) {
        FileConfiguration config = cm.getMainConfig();
        String canonical = "word-system.modes." + AnswerChecker.MODE_FILL_BLANK + "." + leaf;
        if (config.isSet(canonical)) {
            return config.getBoolean(canonical, def);
        }
        return config.getBoolean("word-system.modes.fill-blank." + leaf, def);
    }
}
