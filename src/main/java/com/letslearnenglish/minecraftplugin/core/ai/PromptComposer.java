package com.letslearnenglish.minecraftplugin.core.ai;

import java.util.List;

/**
 * R12：AI system prompt 组装器（纯函数，零 Bukkit 依赖，可在主线程安全调用）。
 *
 * <p>拆出来的理由有三个：
 * <ol>
 *   <li>{@code AIChatManager} 直接构造长字符串时没法单测（要起 Bukkit）；</li>
 *   <li>占位符替换、目标词注入、长度预算这些是纯字符串逻辑，值得有逐字节守卫；</li>
 *   <li>"开关关闭时 prompt 零漂移"是硬约束 —— 空词表必须产出与旧实现逐字节一致的串，
 *       只有纯函数能便宜地把这条钉死在测试里。</li>
 * </ol>
 *
 * <p><b>前缀契约（不可动）</b>：{@link com.letslearnenglish.minecraftplugin.data.ChatHistoryEntry#extractScenarioFromSystemPrompt}
 * 靠字面前缀 {@code "You are role-playing a scenario:"} + 第一个 {@code "."} 截场景名。
 * 所以本类：
 * <ul>
 *   <li>前缀由 {@link #SCENARIO_PREFIX} 逐字拼接，不经占位符替换（防止场景名污染前缀）；</li>
 *   <li>注入段只追加在 {@code === DIFFICULTY ===} 之后，永不触碰第一行。</li>
 * </ul>
 *
 * <p><b>占位符替换顺序固定</b>：{@code {scenario}} → {@code {difficulty}} → {@code {focus_words}}。
 * 词表最后替换，因此词条里出现的 {@code {difficulty}} 字面量不会被二次替换
 * （有测试钉死；上游 {@code ChatFeedbackManager} 已把词条规整为单词，此处只是防御）。
 */
public final class PromptComposer {

    /** 场景前缀 —— 与 {@code ChatHistoryEntry.extractScenarioFromSystemPrompt} 逐字节耦合。 */
    public static final String SCENARIO_PREFIX = "You are role-playing a scenario: ";

    /** 结尾规则块（内容与 R12 之前完全一致，逐字未改）。 */
    private static final String RULES_BLOCK =
            "Important rules:\n"
            + "- Always stay in character.\n"
            + "- If the player writes in Chinese, gently encourage them to try in English.\n"
            + "- Remember conversation context and refer back to earlier messages.\n"
            + "- End responses with a question or prompt when appropriate to keep the conversation going.\n"
            + "- About correcting mistakes: follow the difficulty instructions above.";

    /**
     * 目标词段固定文案。刻意压到 ~145 字符（不含词表）：
     * 现存最长的出厂组合（village_trade + easy）system prompt 已 1767 字符，
     * {@code scenario.max-prompt-length=2000} 只剩 233 字符余量，词表还要占其中约 39 字符。
     * 文案必须同时说清四件事：自然引导、正确就夸、别当考试、保持角色。
     */
    private static final String TARGET_PREAMBLE = "=== TARGET WORDS ===\n";
    private static final String TARGET_LEAD = "Use or expose these words naturally: ";
    private static final String TARGET_PRAISE = "Praise the student when they use one correctly.\n";
    private static final String TARGET_NO_DRILL = "Never quiz them - stay in character.\n\n";

    private PromptComposer() {
    }

    /**
     * 组装完整的 system prompt。
     *
     * <p>{@code focusWords} 为空（功能关闭 / 无数据）时，输出与 R12 之前的实现<b>逐字节一致</b>：
     * 不带任何 TARGET 段、不多一个空行 —— 这是"关闭开关不改变线上行为"的硬约束。
     *
     * @param scenarioName      场景展示名（可为 null，按旧行为渲染为 "null"）
     * @param scenarioPrompt    场景提示词（可含占位符，可 null）
     * @param difficultyName    难度展示名（可为 null）
     * @param difficultyPrompt  难度提示词（可含占位符，可 null）
     * @param focusWords        目标词（null / 空 = 不注入）
     * @return 完整 system prompt
     */
    public static String compose(String scenarioName, String scenarioPrompt,
                                 String difficultyName, String difficultyPrompt,
                                 List<String> focusWords) {
        // String.valueOf 而非 null->""：旧实现直接字符串拼接，null 会渲染成 "null"，
        // 保持这一字节级行为，避免"空名字场景"的 prompt 悄悄换样。
        String nameLabel = String.valueOf(scenarioName);
        String difficultyLabel = String.valueOf(difficultyName);

        String scene = applyPlaceholders(nullToEmpty(scenarioPrompt).trim(),
                nameLabel, difficultyLabel, focusWords);
        String difficulty = applyPlaceholders(nullToEmpty(difficultyPrompt).trim(),
                nameLabel, difficultyLabel, focusWords);

        StringBuilder sb = new StringBuilder(1536);
        sb.append(SCENARIO_PREFIX).append(nameLabel).append(".\n\n")
                .append("=== SCENE ===\n").append(scene).append("\n\n")
                .append("=== DIFFICULTY ===\n").append(difficulty).append("\n\n");

        // 位置契约：DIFFICULTY 之后、Important rules 之前；空词表时整段不出现。
        List<String> words = normalizeFocusWords(focusWords);
        if (!words.isEmpty()) {
            sb.append(targetWordsSection(words));
        }
        sb.append(RULES_BLOCK);
        return sb.toString();
    }

    /**
     * 固定顺序的占位符替换。未知占位符（如 {@code {foo}}）原样保留。
     */
    private static String applyPlaceholders(String template, String scenarioName,
                                            String difficultyName, List<String> focusWords) {
        List<String> words = normalizeFocusWords(focusWords);
        String joined = words.isEmpty() ? "" : String.join(", ", words);
        // 顺序即契约：先场景、再难度、最后词表 —— 词表最后，词条里的 {difficulty} 才不会被吃掉。
        String out = template.replace("{scenario}", scenarioName);
        out = out.replace("{difficulty}", difficultyName);
        out = out.replace("{focus_words}", joined);
        return out;
    }

    /** TARGET WORDS 段（含尾部空行，保证与 Important rules 之间隔一行）。 */
    static String targetWordsSection(List<String> words) {
        return TARGET_PREAMBLE
                + TARGET_LEAD + String.join(", ", words) + "\n"
                + TARGET_PRAISE
                + TARGET_NO_DRILL;
    }

    /** 词表清洗：去 null / 去空白 / 去重保序（大小写不敏感）。不改写词条本身。 */
    private static List<String> normalizeFocusWords(List<String> focusWords) {
        if (focusWords == null || focusWords.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.Set<String> seenLower = new java.util.HashSet<>();
        for (String w : focusWords) {
            if (w == null) {
                continue;
            }
            String trimmed = w.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (seenLower.add(trimmed.toLowerCase(java.util.Locale.ROOT))) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }
}
