package com.letslearnenglish.minecraftplugin.core.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9 · {@link CorrectionPlanner} 纯逻辑断言。
 *
 * <p>词库 / 已学两个谓词都用显式集合，断言的是<b>映射规则</b>而不是真实数据：
 * 句级不进错题本、句内只收已学词、每句上限、词形归并去重、推荐词只排复习、三类丢弃计数。
 */
class CorrectionPlannerTest {

    /** 词库（原形）。 */
    private static final Set<String> BANK = Set.of(
            "go", "book", "study", "work", "make", "run", "watch", "menu", "dessert",
            "order", "friend", "school", "yesterday", "breakfast", "delicious",
            "the", "i", "a", "to", "at", "is", "very", "and");

    /** 玩家学过的词（{@code knownToPlayer}）。 */
    private static final Set<String> KNOWN = Set.of(
            "go", "book", "study", "work", "make", "run", "watch", "menu", "dessert");

    private static final Predicate<String> IN_BANK = BANK::contains;
    private static final Predicate<String> KNOWN_TO_PLAYER = KNOWN::contains;

    private static ConversationFeedback.Correction c(String original, String corrected) {
        return new ConversationFeedback.Correction(original, corrected, "note");
    }

    // ============================================================
    //  单词级
    // ============================================================

    @Test
    void testWordLevelDirectHitCountsAsWrong() {
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("book", "books")), List.of(), IN_BANK, KNOWN_TO_PLAYER, 3);

        assertEquals(Set.of("book"), plan.countAsWrong());
        assertTrue(plan.reviewOnly().isEmpty(), "单词级纠错不应进 reviewOnly");
        assertEquals(0, plan.droppedSentence());
        assertEquals(0, plan.droppedUnknown());
        assertEquals(0, plan.droppedDuplicate());
    }

    @Test
    void testInflectedFormCountsAgainstLemma() {
        // AI 报 went 不可能是规则派生（无表）→ unknown；换 goes 验证归并到 go
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("goes", "go")), List.of(), IN_BANK, KNOWN_TO_PLAYER, 3);

        assertEquals(Set.of("go"), plan.countAsWrong(),
                "词形变化必须记在词库原形头上");
        assertEquals(0, plan.droppedUnknown());
    }

    @Test
    void testUnknownWordIsDroppedAndCounted() {
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("went", "go"), c("children", "child")), List.of(),
                IN_BANK, KNOWN_TO_PLAYER, 3);

        assertTrue(plan.countAsWrong().isEmpty());
        assertEquals(2, plan.droppedUnknown(), "went / children 无规则派生 → 未知丢弃");
    }

    @Test
    void testSameWordTwiceCountsOnce() {
        // goes 与 go 同场 → 归并到同一个原形，只计一次
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("goes", "go"), c("go", "went")), List.of(),
                IN_BANK, KNOWN_TO_PLAYER, 3);

        assertEquals(Set.of("go"), plan.countAsWrong());
        assertEquals(1, plan.countAsWrong().size(), "同场同原形只计一次，否则会误触发第 3 次阈值");
        assertEquals(1, plan.droppedDuplicate());
    }

    // ============================================================
    //  句级
    // ============================================================

    @Test
    void testSentenceNeverEntersWrongBook() {
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("I go to school yesterday", "I went to school yesterday")),
                List.of(), IN_BANK, KNOWN_TO_PLAYER, 3);

        assertTrue(plan.countAsWrong().isEmpty(),
                "句级纠错绝不能进错题本（错题本是单词库，收句子是脏数据）");
        assertTrue(plan.reviewOnly().contains("go"), "句中已学词应进 reviewOnly");
    }

    @Test
    void testSentenceHarvestsOnlyKnownWords() {
        // school / yesterday / the 在词库但玩家没学过 → 不得进 reviewOnly
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("I go to school yesterday", "I went to school yesterday")),
                List.of(), IN_BANK, KNOWN_TO_PLAYER, 10);

        assertEquals(java.util.Set.of("go"), plan.reviewOnly(),
                "只收 knownToPlayer 为真的词");
    }

    @Test
    void testSentenceWordCapIsEnforced() {
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("book menu study work", "corrected sentence")),
                List.of(), IN_BANK, KNOWN_TO_PLAYER, 2);

        assertEquals(2, plan.reviewOnly().size(), "每句最多 maxSentenceWords 个");
        assertEquals(List.of("book", "menu"), new ArrayList<>(plan.reviewOnly()),
                "上限按出现顺序截断，保序");
    }

    @Test
    void testSentenceWithNoHarvestableWordCountsAsDroppedSentence() {
        // 全是不在词库 / 未学过的词 → 句级无可落地动作
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("zzz qqq wwww", "whatever")), List.of(),
                IN_BANK, KNOWN_TO_PLAYER, 3);

        assertTrue(plan.reviewOnly().isEmpty());
        assertEquals(1, plan.droppedSentence());
    }

    @Test
    void testSentenceInflectionsAreResolved() {
        // 句中的 studied 应经 resolve → study（且 study 已学）
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("I studied yesterday", "I studied yesterday")), List.of(),
                IN_BANK, KNOWN_TO_PLAYER, 3);

        assertEquals(Set.of("study"), plan.reviewOnly());
    }

    // ============================================================
    //  推荐词
    // ============================================================

    @Test
    void testRecommendedWordsGoToReviewOnly() {
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(), List.of("menu", "desserts"), IN_BANK, KNOWN_TO_PLAYER, 3);

        assertEquals(Set.of("menu", "dessert"), plan.reviewOnly(),
                "推荐词经词形还原后进 reviewOnly；desserts → dessert 需在词库");
        assertTrue(plan.countAsWrong().isEmpty(), "推荐词不是错词");
    }

    @Test
    void testRecommendedWordNotInBankIsDropped() {
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(), List.of("xylophone"), IN_BANK, KNOWN_TO_PLAYER, 3);

        assertTrue(plan.reviewOnly().isEmpty());
        assertEquals(1, plan.droppedUnknown());
    }

    @Test
    void testRecommendedWordAlreadyCountedIsDeduplicated() {
        // book 已在 countAsWrong（单词级纠错），推荐词里再出现 → duplicate
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("book", "books")), List.of("book"), IN_BANK, KNOWN_TO_PLAYER, 3);

        assertEquals(Set.of("book"), plan.countAsWrong());
        assertTrue(plan.reviewOnly().isEmpty(), "已计错的词不应再进 reviewOnly（会被重复排复习）");
        assertEquals(1, plan.droppedDuplicate());
    }

    // ============================================================
    //  边界
    // ============================================================

    @Test
    void testNullAndEmptyInputsAreSafe() {
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                null, null, null, null, 0);
        assertNotNull(plan);
        assertTrue(plan.countAsWrong().isEmpty());
        assertTrue(plan.reviewOnly().isEmpty());
        assertEquals(0, plan.droppedSentence());
        assertEquals(0, plan.droppedUnknown());
        assertEquals(0, plan.droppedDuplicate());
    }

    @Test
    void testAllGarbageInputIsSafeAndFullyCounted() {
        List<ConversationFeedback.Correction> corrections = new ArrayList<>(Arrays.asList(
                null,
                c("", ""),
                c("   ", "x"),
                c("went", "go"),
                c("I go yesterday", "I went yesterday"),
                c("12 apples", "twelve apples")));
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                corrections, List.of("", "   ", "xylophone"), IN_BANK, KNOWN_TO_PLAYER, 3);

        assertNotNull(plan);
        assertTrue(plan.countAsWrong().isEmpty());
        // 空 original 不计入任何丢弃口径；went → unknown；12 apples → 句级无词命中 → sentence；
        // xylophone → unknown。合计 unknown = 2，sentence = 2（"I go yesterday" 命中 go 时会进
        // reviewOnly，故这里期望 sentence = 1）。
        assertEquals(2, plan.droppedUnknown());
        assertEquals(1, plan.droppedSentence());
        assertEquals(Set.of("go"), plan.reviewOnly());
    }

    @Test
    void testWordLevelWinsOverSentenceWhenBothMentionIt() {
        // 同一场：句级先说 "book menu"（会回收 book），单词级又说 book 写错了。
        // 语义上 book 确实答错 → 必须进 countAsWrong，而不是被句级先占成 reviewOnly。
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("I read a book yesterday", "I read a book last night"),
                        c("book", "books")),
                List.of(), IN_BANK, KNOWN_TO_PLAYER, 3);

        assertEquals(Set.of("book"), plan.countAsWrong(), "单词级纠错的优先级高于句级回收");
        assertTrue(plan.reviewOnly().isEmpty(), "book 已计错，不应再进 reviewOnly");
    }

    @Test
    void testResultCollectionsAreNeverNullAndPreserveOrder() {
        CorrectionPlanner.Plan plan = CorrectionPlanner.plan(
                List.of(c("book", "x"), c("work", "y"), c("study", "z")),
                List.of("menu"), IN_BANK, KNOWN_TO_PLAYER, 3);

        assertEquals(List.of("book", "work", "study"), new ArrayList<>(plan.countAsWrong()),
                "countAsWrong 保 AI 输出顺序");
        assertEquals(List.of("menu"), new ArrayList<>(plan.reviewOnly()));
    }

    @Test
    void testNormalizeWordLevelContract() {
        assertEquals("book", CorrectionPlanner.normalizeWordLevel("  BOOK  "));
        assertEquals("don't", CorrectionPlanner.normalizeWordLevel("Don't"));
        assertEquals("well-known", CorrectionPlanner.normalizeWordLevel("well-known"));
        assertNull(CorrectionPlanner.normalizeWordLevel(null));
        assertNull(CorrectionPlanner.normalizeWordLevel(""));
        assertNull(CorrectionPlanner.normalizeWordLevel("two words"));
        // 单字母词已知不可达（正则要求 ≥2 字母）—— 这是冻结的已知限制
        assertNull(CorrectionPlanner.normalizeWordLevel("a"));
        assertNull(CorrectionPlanner.normalizeWordLevel("i"));
        assertNull(CorrectionPlanner.normalizeWordLevel("a".repeat(41)));
    }
}
