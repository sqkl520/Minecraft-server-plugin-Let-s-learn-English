package com.letslearnenglish.minecraftplugin.core.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R12：{@link PromptComposer} 的纯逻辑守卫。
 *
 * <p>零 Bukkit 依赖。这里钉死的核心契约只有一条：<b>目标词为空时，输出必须与
 * R12 之前的实现逐字节一致</b> —— 关闭开关绝不能让线上 prompt 漂移；其余
 * （占位符、注入位置、长度预算）都是围绕这条展开的。
 */
class PromptComposerTest {

    private static final String SCENARIO = "Village Trade";
    private static final String DIFFICULTY = "Easy";
    private static final String SCENE_PROMPT =
            "You are a villager trader. Ask what the player wants to buy.";
    private static final String DIFF_PROMPT =
            "You are speaking to a beginner. Recast mistakes naturally.";

    private static final List<String> FIVE_WORDS =
            List.of("emerald", "trade", "villager", "harvest", "barter");

    /**
     * R12 之前 buildSystemPrompt 的逐字复刻（4 参版的方法体），作为"零漂移"基准。
     */
    private static String legacyBaseline(String scenarioName, String scenarioPrompt,
                                         String difficultyPrompt) {
        return "You are role-playing a scenario: " + scenarioName + ".\n\n"
                + "=== SCENE ===\n" + scenarioPrompt.trim() + "\n\n"
                + "=== DIFFICULTY ===\n" + difficultyPrompt.trim() + "\n\n"
                + "Important rules:\n"
                + "- Always stay in character.\n"
                + "- If the player writes in Chinese, gently encourage them to try in English.\n"
                + "- Remember conversation context and refer back to earlier messages.\n"
                + "- End responses with a question or prompt when appropriate to keep the conversation going.\n"
                + "- About correcting mistakes: follow the difficulty instructions above.";
    }

    @Test
    void emptyFocusWordsProducesByteIdenticalLegacyPrompt() {
        String actual = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, List.of());
        String expected = legacyBaseline(SCENARIO, SCENE_PROMPT, DIFF_PROMPT);

        assertEquals(expected, actual, "空词表必须逐字节等于旧实现（开关关闭零漂移）");
        assertEquals(expected.length(), actual.length());
        assertFalse(actual.contains("TARGET WORDS"), "空词表不得出现注入段");
        assertFalse(actual.contains("\n\n\n"), "空词表不得多出空行");
    }

    @Test
    void nullFocusWordsBehavesLikeEmpty() {
        String withNull = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, null);
        String withEmpty = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, List.of());

        assertEquals(withEmpty, withNull, "null 词表必须同空表处理");
        assertEquals(legacyBaseline(SCENARIO, SCENE_PROMPT, DIFF_PROMPT), withNull);
    }

    @Test
    void scenarioPlaceholderIsReplaced() {
        String scenePrompt = "You are in the {scenario}. Greet the player.";
        String out = PromptComposer.compose(SCENARIO, scenePrompt, DIFFICULTY, DIFF_PROMPT, List.of());

        assertTrue(out.contains("=== SCENE ===\nYou are in the Village Trade. Greet the player."),
                "实际输出: " + out);
        assertFalse(out.contains("{scenario}"));
    }

    @Test
    void difficultyPlaceholderIsReplaced() {
        String diffPrompt = "Level: {difficulty}. Keep it short.";
        String out = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, diffPrompt, List.of());

        assertTrue(out.contains("=== DIFFICULTY ===\nLevel: Easy. Keep it short."), "实际输出: " + out);
        assertFalse(out.contains("{difficulty}"));
    }

    @Test
    void focusWordsPlaceholderIsReplaced() {
        String scenePrompt = "Target words: {focus_words}.";
        String out = PromptComposer.compose(SCENARIO, scenePrompt, DIFFICULTY, DIFF_PROMPT, FIVE_WORDS);

        assertTrue(out.contains("Target words: emerald, trade, villager, harvest, barter."), "实际输出: " + out);
        assertFalse(out.contains("{focus_words}"));
    }

    @Test
    void focusWordsPlaceholderBecomesEmptyStringWhenNoWords() {
        String scenePrompt = "Target words: [{focus_words}].";
        String out = PromptComposer.compose(SCENARIO, scenePrompt, DIFFICULTY, DIFF_PROMPT, List.of());

        assertTrue(out.contains("Target words: []."), "空词表替换为空串，占位符本身不残留");
        assertFalse(out.contains("{focus_words}"));
    }

    @Test
    void unknownPlaceholderIsLeftIntact() {
        String scenePrompt = "Keep {foo} and {bar} as-is.";
        String out = PromptComposer.compose(SCENARIO, scenePrompt, DIFFICULTY, DIFF_PROMPT, FIVE_WORDS);

        assertTrue(out.contains("Keep {foo} and {bar} as-is."), "未知占位符必须原样保留");
    }

    @Test
    void focusWordContainingDifficultyTokenIsNotDoubleReplaced() {
        // 替换顺序守卫：词表最后替换，词条里的 {difficulty} 字面量必须原样留下。
        String scenePrompt = "Target words: {focus_words}.";
        List<String> words = List.of("alpha", "{difficulty}", "omega");

        String out = PromptComposer.compose(SCENARIO, scenePrompt, DIFFICULTY, DIFF_PROMPT, words);

        assertTrue(out.contains("Target words: alpha, {difficulty}, omega."), "实际输出: " + out);
        // 注入段里的同一个词条同样不得被替换
        assertTrue(out.contains("naturally: alpha, {difficulty}, omega"), "实际输出: " + out);
        // 难度段自身仍然正常替换过（说明不是"整体没替换"）
        assertTrue(out.contains("=== DIFFICULTY ===\n" + DIFF_PROMPT), "实际输出: " + out);
    }

    @Test
    void prefixStaysLiteralAndIsFollowedByScenarioNameAndDot() {
        String out = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, FIVE_WORDS);

        // ChatHistoryEntry.extractScenarioFromSystemPrompt 靠这个前缀 + 第一个 "." 截名字
        String expected = "You are role-playing a scenario: " + SCENARIO + ".";
        assertTrue(out.startsWith(expected), "前缀契约被破坏 — 历史 GUI 会全部显示 unknown-scenario");
        assertEquals("You are role-playing a scenario: ", PromptComposer.SCENARIO_PREFIX);
    }

    @Test
    void prefixExtractionStillYieldsScenarioName() {
        String out = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, FIVE_WORDS);

        // 直接复刻 ChatHistoryEntry 的截取逻辑，防止将来有人往第一行塞句点
        String prefix = "You are role-playing a scenario:";
        int idx = out.indexOf(prefix);
        assertTrue(idx >= 0);
        idx += prefix.length();
        int end = out.indexOf('.', idx);
        assertEquals(SCENARIO, out.substring(idx, end).trim());
    }

    @Test
    void targetSectionSitsBetweenDifficultyAndRules() {
        String out = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, FIVE_WORDS);

        int difficultyIdx = out.indexOf("=== DIFFICULTY ===");
        int targetIdx = out.indexOf("=== TARGET WORDS ===");
        int rulesIdx = out.indexOf("Important rules:");

        assertTrue(difficultyIdx >= 0 && targetIdx >= 0 && rulesIdx >= 0);
        assertTrue(difficultyIdx < targetIdx, "TARGET 段必须在 DIFFICULTY 之后");
        assertTrue(targetIdx < rulesIdx, "TARGET 段必须在 Important rules 之前");
        assertTrue(out.contains("emerald, trade, villager, harvest, barter"), "词表必须逗号分隔出现");
        assertTrue(out.contains("Never quiz them"), "必须明确禁止考试式提问");
        assertTrue(out.endsWith("- About correcting mistakes: follow the difficulty instructions above."),
                "结尾规则块必须逐字不变");
    }

    @Test
    void fiveWordInjectionFitsThePromptBudget() {
        // 预算守卫：最长出厂组合（village_trade + easy）system prompt 实测 1767 字符，
        // scenario.max-prompt-length = 2000 → 只剩 233 字符给注入段。
        String base = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, List.of());
        String injected = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, FIVE_WORDS);

        int delta = injected.length() - base.length();
        assertTrue(delta > 0, "有词时必须真的追加了内容");
        assertTrue(delta < 233,
                "5 词注入段长度 " + delta + " 超出 2000-1767=233 的预算");
    }

    @Test
    void worstCaseWordLengthsStillFitBudget() {
        // 词库里最长的词 13 字符（differentiate / conscientious / comprehensive）。
        // 5 个 13 字符词是最坏情况，必须仍落在 233 内 —— 否则超长 prompt 会被
        // /le scenario save 的 max-prompt-length 拒绝。
        List<String> longest = List.of("differentiate", "conscientious", "comprehensive",
                "hypothetical", "cosmopolitan");

        String base = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, List.of());
        String injected = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, longest);

        int delta = injected.length() - base.length();
        assertTrue(delta < 233, "最坏词长下注入段 " + delta + " 字符，超出 233 预算");
    }

    @Test
    void duplicateAndBlankWordsAreDedupedKeepingOrder() {
        List<String> noisy = java.util.Arrays.asList("emerald", "EMERALD", " trade ", "", null, "emerald");

        String out = PromptComposer.compose(SCENARIO, SCENE_PROMPT, DIFFICULTY, DIFF_PROMPT, noisy);

        assertTrue(out.contains("naturally: emerald, trade\n"), "去重保序后应只剩两个词，实际输出: " + out);
        assertFalse(out.contains("EMERALD"), "大小写不敏感去重保留首次出现的写法");
    }

    @Test
    void nullScenarioAndDifficultyNamesRenderAsNullNotEmpty() {
        // 与旧实现的字符串拼接行为一致：null 渲染成字面量 "null"，不是空串。
        String out = PromptComposer.compose(null, SCENE_PROMPT, null, DIFF_PROMPT, List.of());

        assertTrue(out.startsWith("You are role-playing a scenario: null."));
    }

    @Test
    void nullPromptsDoNotBlowUp() {
        String out = PromptComposer.compose(SCENARIO, null, DIFFICULTY, null, FIVE_WORDS);

        assertNotNull(out);
        assertTrue(out.contains("=== SCENE ===\n\n\n=== DIFFICULTY ===\n"), "null prompt 视为空串");
        assertTrue(out.contains("=== TARGET WORDS ==="));
    }
}
