package com.letslearnenglish.minecraftplugin.core.word;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class WordTest {

    @Test
    void testWordCreation() {
        Word word = new Word("apple", "苹果", "noun", "/ˈæp.əl/",
                "I eat an apple.", List.of("fruit"), List.of(), "beginner", 1);

        assertEquals("apple", word.getEnglish());
        assertEquals("苹果", word.getChinese());
        assertEquals("noun", word.getPartOfSpeech());
        assertEquals("/ˈæp.əl/", word.getPhonetic());
        assertEquals("I eat an apple.", word.getExampleSentence());
        assertEquals(List.of("fruit"), word.getSynonyms());
        assertTrue(word.getAntonyms().isEmpty());
        assertEquals("beginner", word.getDifficulty());
        assertEquals(1, word.getDifficultyLevel());
    }

    @Test
    void testWordNullLists() {
        Word word = new Word("test", "测试", "verb", null, null, null, null, "beginner", 1);
        assertNotNull(word.getSynonyms());
        assertNotNull(word.getAntonyms());
        assertTrue(word.getSynonyms().isEmpty());
        assertTrue(word.getAntonyms().isEmpty());
    }

    @Test
    void testEqualsIgnoreCase() {
        Word w1 = new Word("Apple", "苹果", "noun", null, null, null, null, "beginner", 1);
        Word w2 = new Word("apple", "苹果", "noun", null, null, null, null, "beginner", 1);
        assertEquals(w1, w2);
    }

    @Test
    void testHashCode() {
        Word w1 = new Word("Apple", "苹果", "noun", null, null, null, null, "beginner", 1);
        Word w2 = new Word("apple", "苹果", "noun", null, null, null, null, "beginner", 1);
        assertEquals(w1.hashCode(), w2.hashCode());
    }

    @Test
    void testNotEquals() {
        Word w1 = new Word("apple", "苹果", "noun", null, null, null, null, "beginner", 1);
        Word w2 = new Word("banana", "香蕉", "noun", null, null, null, null, "beginner", 1);
        assertNotEquals(w1, w2);
    }

    @Test
    void testToString() {
        Word word = new Word("apple", "苹果", "noun", null, null, null, null, "beginner", 1);
        assertTrue(word.toString().contains("apple"));
        assertTrue(word.toString().contains("苹果"));
    }
}