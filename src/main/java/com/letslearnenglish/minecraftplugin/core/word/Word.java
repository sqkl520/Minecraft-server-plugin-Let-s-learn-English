package com.letslearnenglish.minecraftplugin.core.word;

import java.util.List;

/**
 * Word entity class
 *
 * Represents a single word entry in the word bank, containing all
 * learning-related information for the word.
 */
public class Word {

    private final String english;
    private final String chinese;
    private final String partOfSpeech;
    private final String phonetic;
    private final String exampleSentence;
    private final List<String> synonyms;
    private final List<String> antonyms;
    private final String difficulty;
    private final int difficultyLevel;

    public Word(String english, String chinese, String partOfSpeech, String phonetic,
                String exampleSentence, List<String> synonyms, List<String> antonyms,
                String difficulty, int difficultyLevel) {
        this.english = english;
        this.chinese = chinese;
        this.partOfSpeech = partOfSpeech;
        this.phonetic = phonetic;
        this.exampleSentence = exampleSentence;
        this.synonyms = synonyms != null ? synonyms : List.of();
        this.antonyms = antonyms != null ? antonyms : List.of();
        this.difficulty = difficulty;
        this.difficultyLevel = difficultyLevel;
    }

    public String getEnglish() {
        return english;
    }

    public String getChinese() {
        return chinese;
    }

    public String getPartOfSpeech() {
        return partOfSpeech;
    }

    public String getPhonetic() {
        return phonetic;
    }

    public String getExampleSentence() {
        return exampleSentence;
    }

    public List<String> getSynonyms() {
        return synonyms;
    }

    public List<String> getAntonyms() {
        return antonyms;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public int getDifficultyLevel() {
        return difficultyLevel;
    }

    @Override
    public String toString() {
        return "Word{english='" + english + "', chinese='" + chinese + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Word word)) return false;
        return english.equalsIgnoreCase(word.english);
    }

    @Override
    public int hashCode() {
        return english.toLowerCase().hashCode();
    }
}