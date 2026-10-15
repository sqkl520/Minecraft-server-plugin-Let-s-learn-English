package com.letslearnenglish.minecraftplugin.core.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic assertions for the AI summary parsing layer
 * (plan-ai-feedback US-FB-3, §3.2 解析规则).
 *
 * <p>No Bukkit dependency — runs as a plain JUnit test. Guards the
 * salvage + defensive-read + hard-cap contracts the feedback flow
 * relies on: models are not trusted to emit clean JSON, so every
 * degradation path (fences, chatter, garbage, oversized fields) must
 * be pinned down here.
 */
class ConversationFeedbackTest {

    @Test
    void testWellFormedJsonParses() {
        String raw = "{\"comment\": \"Great job today!\", "
                + "\"corrections\": [{\"original\": \"I go yesterday\", "
                + "\"corrected\": \"I went yesterday\", \"note\": \"过去式\"}], "
                + "\"words\": [\"menu\", \"dessert\"]}";

        ConversationFeedback fb = ConversationFeedback.parse(raw);

        assertNotNull(fb, "well-formed JSON must parse");
        assertEquals("Great job today!", fb.comment());
        assertEquals(1, fb.corrections().size());
        assertEquals("I go yesterday", fb.corrections().get(0).original());
        assertEquals("I went yesterday", fb.corrections().get(0).corrected());
        assertEquals("过去式", fb.corrections().get(0).note());
        assertEquals(List.of("menu", "dessert"), fb.words());
    }

    @Test
    void testMarkdownFencesAndChatterAreSalvaged() {
        String raw = "Sure! Here is the JSON:\n```json\n"
                + "{\"comment\": \"Nice work.\", \"corrections\": [], \"words\": []}\n"
                + "```\nHope that helps!";

        ConversationFeedback fb = ConversationFeedback.parse(raw);

        assertNotNull(fb, "fenced + chattering output must be salvaged");
        assertEquals("Nice work.", fb.comment());
        assertTrue(fb.corrections().isEmpty());
        assertTrue(fb.words().isEmpty());
    }

    @Test
    void testGarbageOutputReturnsNull() {
        assertNull(ConversationFeedback.parse(null));
        assertNull(ConversationFeedback.parse(""));
        assertNull(ConversationFeedback.parse("   "));
        assertNull(ConversationFeedback.parse("I am sorry, I cannot do that."));
        assertNull(ConversationFeedback.parse("{ broken json"));
        assertNull(ConversationFeedback.parse("[1, 2, 3]"), "non-object JSON must fail");
        assertNull(ConversationFeedback.parse("\"just a string\""), "primitive JSON must fail");
    }

    @Test
    void testHardCapsAreEnforced() {
        // comment 600 字符 → 截到 500
        String longComment = "a".repeat(600);
        StringBuilder corrections = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (i > 0) corrections.append(',');
            corrections.append("{\"original\": \"").append(i).append("x\", \"corrected\": \"y\", \"note\": \"n\"}");
        }
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) words.append(',');
            words.append("\"word").append(i).append("\"");
        }
        String raw = "{\"comment\": \"" + longComment + "\", "
                + "\"corrections\": [" + corrections + "], "
                + "\"words\": [" + words + "]}";

        ConversationFeedback fb = ConversationFeedback.parse(raw);

        assertNotNull(fb);
        assertEquals(500, fb.comment().length(), "comment capped at 500 chars");
        assertEquals(5, fb.corrections().size(), "corrections capped at 5 entries");
        assertEquals(8, fb.words().size(), "words capped at 8 entries");

        // 单字段上限：correction.original ≤200，word ≤40
        String rawField = "{\"comment\": \"ok\", \"corrections\": [{\"original\": \""
                + "b".repeat(300) + "\", \"corrected\": \"c\", \"note\": \"n\"}], "
                + "\"words\": [\"" + "d".repeat(60) + "\"]}";
        ConversationFeedback fbField = ConversationFeedback.parse(rawField);
        assertNotNull(fbField);
        assertEquals(200, fbField.corrections().get(0).original().length(),
                "correction fields capped at 200 chars");
        assertEquals(40, fbField.words().get(0).length(), "each word capped at 40 chars");
    }

    @Test
    void testMissingOrWrongTypedFieldsDegrade() {
        // 全空对象 → 全字段降级为空（不视为彻底失败）
        ConversationFeedback empty = ConversationFeedback.parse("{}");
        assertNotNull(empty, "an object with no valid fields still degrades, not null");
        assertEquals("", empty.comment());
        assertTrue(empty.corrections().isEmpty());
        assertTrue(empty.words().isEmpty());

        // 类型错的字段 → 各自降级，不影响其它字段
        String mixed = "{\"comment\": 42, \"corrections\": \"not-an-array\", "
                + "\"words\": [\"ok\", 7, null, \"\"]}";
        ConversationFeedback fb = ConversationFeedback.parse(mixed);
        assertNotNull(fb);
        assertEquals("", fb.comment(), "wrong-typed comment degrades to empty");
        assertTrue(fb.corrections().isEmpty(), "wrong-typed corrections degrade to empty");
        assertEquals(List.of("ok"), fb.words(), "non-string / empty words are dropped");

        // corrections 内非对象元素与全空条目被跳过
        String badEntries = "{\"comment\": \"x\", \"corrections\": [null, \"junk\", {}, "
                + "{\"original\": \"a\"}]}";
        ConversationFeedback fb2 = ConversationFeedback.parse(badEntries);
        assertNotNull(fb2);
        assertEquals(1, fb2.corrections().size(), "junk entries skipped, partial entry kept");
        assertEquals("a", fb2.corrections().get(0).original());
        assertEquals("", fb2.corrections().get(0).corrected());
    }

    @Test
    void testControlCharsAndSectionSignAreStripped() {
        String raw = "{\"comment\": \"Nice §cwork\", \"corrections\": [], \"words\": []}";

        ConversationFeedback fb = ConversationFeedback.parse(raw);

        assertNotNull(fb);
        assertEquals("Nice cwork", fb.comment(), "control chars and § removed");
        assertFalse(fb.comment().contains("§"));
    }
}
