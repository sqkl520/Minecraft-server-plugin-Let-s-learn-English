package com.letslearnenglish.minecraftplugin.core.word;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class LearningSessionTest {

    private List<Word> words;
    private LearningSession session;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        playerId = UUID.randomUUID();
        words = Arrays.asList(
                new Word("apple", "苹果", "noun", null, null, null, null, "beginner", 1),
                new Word("book", "书", "noun", null, null, null, null, "beginner", 1),
                new Word("cat", "猫", "noun", null, null, null, null, "beginner", 1),
                new Word("dog", "狗", "noun", null, null, null, null, "beginner", 1),
                new Word("eat", "吃", "verb", null, null, null, null, "beginner", 1)
        );
        session = new LearningSession(playerId, "beginner", "spelling", words);
    }

    @Test
    void testSessionCreation() {
        assertEquals(playerId, session.getPlayerId());
        assertEquals("beginner", session.getDifficulty());
        assertEquals("spelling", session.getMode());
        assertEquals(5, session.getTotalWords());
        assertEquals(0, session.getCurrentIndex());
        assertEquals(0, session.getTotalCorrect());
        assertEquals(0, session.getTotalIncorrect());
        assertFalse(session.isCompleted());
    }

    @Test
    void testGetCurrentWord() {
        Word w = session.getCurrentWord();
        assertNotNull(w);
        assertEquals("apple", w.getEnglish());
    }

    @Test
    void testRecordCorrect() {
        session.recordCorrect();
        assertEquals(1, session.getCurrentIndex());
        assertEquals(1, session.getTotalCorrect());
        assertEquals(0, session.getTotalIncorrect());
        assertEquals("book", session.getCurrentWord().getEnglish());
    }

    @Test
    void testRecordIncorrect() {
        session.recordIncorrect();
        assertEquals(1, session.getCurrentIndex());
        assertEquals(0, session.getTotalCorrect());
        assertEquals(1, session.getTotalIncorrect());
    }

    @Test
    void testAdvanceThroughAllWords() {
        for (int i = 0; i < 5; i++) {
            session.recordCorrect();
        }
        assertNull(session.getCurrentWord());
        assertEquals(5, session.getCurrentIndex());
        assertTrue(session.isCompleted());
    }

    @Test
    void testAccuracyCalculation() {
        session.recordCorrect();
        session.recordCorrect();
        session.recordIncorrect();
        session.recordCorrect();
        session.recordIncorrect();
        assertEquals(0.6, session.getAccuracy(), 0.01);
    }

    @Test
    void testAccuracyEmpty() {
        assertEquals(0.0, session.getAccuracy(), 0.001);
    }

    @Test
    void testScoreCalculation() {
        session.recordCorrect();
        session.addScore(10);
        session.recordCorrect();
        session.addScore(10);
        session.recordIncorrect();
        session.addScore(-2);
        assertEquals(18, session.getScore());
    }

    @Test
    void testElapsedSeconds() throws InterruptedException {
        Thread.sleep(100);
        assertTrue(session.getElapsedSeconds() >= 0);
    }

    @Test
    void testWordsImmutable() {
        List<Word> ret = session.getWords();
        assertEquals(5, ret.size());
        assertThrows(UnsupportedOperationException.class, () -> ret.add(
                new Word("test", "测试", "noun", null, null, null, null, "beginner", 1)));
    }

    @Test
    void testResultsImmutable() {
        Map<String, LearningSession.WordResult> results = session.getResults();
        assertEquals(5, results.size());
        assertThrows(UnsupportedOperationException.class, () -> results.put("new", null));
    }

    @Test
    void testWordResultMastery() {
        LearningSession.WordResult result = new LearningSession.WordResult(words.get(0));
        assertEquals(0, result.getCorrectCount());
        assertEquals(0, result.getIncorrectCount());
        assertFalse(result.isMastered());

        result.incrementCorrect();
        result.incrementCorrect();
        result.incrementCorrect();
        result.incrementCorrect();
        result.incrementIncorrect();
        assertEquals(4, result.getCorrectCount());
        assertEquals(1, result.getIncorrectCount());
        assertTrue(result.isMastered());
    }
}