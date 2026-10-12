package com.letslearnenglish.minecraftplugin.core.word;

import java.util.List;
import java.util.Objects;

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
    private final double reward; // 0 means use global default from config

    public Word(String english, String chinese, String partOfSpeech, String phonetic,
                String exampleSentence, List<String> synonyms, List<String> antonyms,
                String difficulty, int difficultyLevel) {
        this(english, chinese, partOfSpeech, phonetic, exampleSentence,
                synonyms, antonyms, difficulty, difficultyLevel, 0.0);
    }

    public Word(String english, String chinese, String partOfSpeech, String phonetic,
                String exampleSentence, List<String> synonyms, List<String> antonyms,
                String difficulty, int difficultyLevel, double reward) {
        this.english = english;
        this.chinese = chinese;
        this.partOfSpeech = partOfSpeech;
        this.phonetic = phonetic;
        this.exampleSentence = exampleSentence;
        this.synonyms = synonyms != null ? synonyms : List.of();
        this.antonyms = antonyms != null ? antonyms : List.of();
        this.difficulty = difficulty;
        this.difficultyLevel = difficultyLevel;
        this.reward = reward;
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

    /**
     * Get the custom reward amount for this word.
     * Returns 0 if no custom reward is set (fall back to global config).
     */
    public double getReward() {
        return reward;
    }

    @Override
    public String toString() {
        return "Word{english='" + english + "', chinese='" + chinese + "'}";
    }

    /**
     * F2-04：词身份收窄为 english（大小写不敏感）+ part_of_speech。
     *
     * <p>原先把 reward（可调的经济属性）纳入判等，同一词在奖励调整后即判"不同词"，
     * 使依赖 equals 的集合去重 / 干扰项排除随 reward 漂移而失效。收窄后同英文不同
     * reward 视为同一个词；已核对消费点：{@link WordBank#getRandomWords(int, Word)}
     * 的 exclude 过滤（语义反而更稳：同词各变体一律排除）、
     * {@link AnswerChecker#buildChoiceOptions} 另有英文名二次过滤兜底；
     * 全项目无 Set&lt;Word&gt;/Map&lt;Word,*&gt; 容器消费（已 grep 核实）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Word word)) return false;
        String pos = partOfSpeech == null ? "" : partOfSpeech;
        String otherPos = word.partOfSpeech == null ? "" : word.partOfSpeech;
        return english.equalsIgnoreCase(word.english)
                && pos.equalsIgnoreCase(otherPos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(english.toLowerCase(),
                partOfSpeech == null ? "" : partOfSpeech.toLowerCase());
    }
}