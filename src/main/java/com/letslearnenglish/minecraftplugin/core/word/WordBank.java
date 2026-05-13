package com.letslearnenglish.minecraftplugin.core.word;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Word Bank - manages a collection of words for a specific difficulty level.
 *
 * Loads words from YAML configuration and provides methods for
 * selecting words for learning sessions.
 */
public class WordBank {

    private final String difficulty;
    private final String description;
    private final List<Word> words;
    private final Random random;

    public WordBank(String difficulty, FileConfiguration config) {
        this.difficulty = difficulty;
        this.description = config.getString("description", "");
        this.words = new ArrayList<>();
        this.random = new Random();

        loadWords(config);
    }

    @SuppressWarnings("unchecked")
    private void loadWords(FileConfiguration config) {
        List<?> rawList = config.getList("words");
        if (rawList == null) {
            return;
        }

        for (Object item : rawList) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> wordData = (Map<String, Object>) item;
            String english = (String) wordData.get("english");
            String chinese = (String) wordData.get("chinese");
            String partOfSpeech = (String) wordData.get("part_of_speech");
            String phonetic = (String) wordData.get("phonetic");
            String exampleSentence = (String) wordData.get("example_sentence");

            List<String> synonyms = new ArrayList<>();
            Object synObj = wordData.get("synonyms");
            if (synObj instanceof List) {
                for (Object s : (List<?>) synObj) {
                    if (s instanceof String str) synonyms.add(str);
                }
            }

            List<String> antonyms = new ArrayList<>();
            Object antObj = wordData.get("antonyms");
            if (antObj instanceof List) {
                for (Object a : (List<?>) antObj) {
                    if (a instanceof String str) antonyms.add(str);
                }
            }

            int difficultyLevel = wordData.containsKey("difficulty_level")
                    ? ((Number) wordData.get("difficulty_level")).intValue() : 1;

            Word word = new Word(english, chinese, partOfSpeech, phonetic,
                    exampleSentence, synonyms, antonyms, difficulty, difficultyLevel);
            words.add(word);
        }
    }

    /**
     * Select words for a learning session, prioritizing unmastered words.
     *
     * @param count         number of words to select
     * @param masteredWords set of words the player has already mastered
     * @return list of selected words
     */
    public List<Word> selectWordsForSession(int count, Set<String> masteredWords) {
        List<Word> unmastered = words.stream()
                .filter(w -> !masteredWords.contains(w.getEnglish().toLowerCase()))
                .collect(Collectors.toList());

        List<Word> selected = new ArrayList<>();

        if (unmastered.size() >= count) {
            Collections.shuffle(unmastered, random);
            selected.addAll(unmastered.subList(0, count));
        } else {
            selected.addAll(unmastered);

            List<Word> mastered = words.stream()
                    .filter(w -> masteredWords.contains(w.getEnglish().toLowerCase()))
                    .collect(Collectors.toList());
            Collections.shuffle(mastered, random);

            int remaining = count - selected.size();
            int toAdd = Math.min(remaining, mastered.size());
            selected.addAll(mastered.subList(0, toAdd));
        }

        Collections.shuffle(selected, random);
        return selected;
    }

    /**
     * Get a word by its English text.
     */
    public Word getWord(String english) {
        return words.stream()
                .filter(w -> w.getEnglish().equalsIgnoreCase(english))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get random words for generating multiple-choice options.
     */
    public List<Word> getRandomWords(int count, Word exclude) {
        List<Word> pool = words.stream()
                .filter(w -> !w.equals(exclude))
                .collect(Collectors.toList());
        Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(count, pool.size()));
    }

    public String getDifficulty() {
        return difficulty;
    }

    public String getDescription() {
        return description;
    }

    public int getWordCount() {
        return words.size();
    }

    public List<Word> getAllWords() {
        return Collections.unmodifiableList(words);
    }
}