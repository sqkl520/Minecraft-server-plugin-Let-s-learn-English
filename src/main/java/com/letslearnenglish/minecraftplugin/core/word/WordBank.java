package com.letslearnenglish.minecraftplugin.core.word;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.FileConfiguration;

public class WordBank {

    private final String difficulty;
    private final String description;
    private final List<Word> words;
    private final Random random;

    private volatile Set<String> cachedMasteredKey;
    private volatile List<Word> cachedUnmastered;
    private volatile List<Word> cachedMastered;

    public WordBank(String difficulty, FileConfiguration config) {
        this.difficulty = difficulty;
        this.description = config.getString("description", "");
        this.words = new ArrayList<>();
        this.random = new Random();

        loadWords(config);
    }

    private void loadWords(FileConfiguration config) {
        List<?> rawList = config.getList("words");
        if (rawList == null) {
            return;
        }

        for (Object item : rawList) {
            if (!(item instanceof Map)) {
                continue;
            }
            // Build a type-checked Map<String, Object> from the raw YAML map
            Map<String, Object> wordData = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                if (entry.getKey() instanceof String key) {
                    wordData.put(key, entry.getValue());
                }
            }
            String english = (String) wordData.get("english");
            String chinese = (String) wordData.get("chinese");
            String partOfSpeech = (String) wordData.get("part_of_speech");
            String phonetic = (String) wordData.get("phonetic");
            String exampleSentence = (String) wordData.get("example_sentence");

            List<String> synonyms = new ArrayList<>(4);
            Object synObj = wordData.get("synonyms");
            if (synObj instanceof List) {
                for (Object s : (List<?>) synObj) {
                    if (s instanceof String str) synonyms.add(str);
                }
            }

            List<String> antonyms = new ArrayList<>(4);
            Object antObj = wordData.get("antonyms");
            if (antObj instanceof List) {
                for (Object a : (List<?>) antObj) {
                    if (a instanceof String str) antonyms.add(str);
                }
            }

            int difficultyLevel = 1;
            Object diffObj = wordData.get("difficulty_level");
            if (diffObj instanceof Number num) {
                difficultyLevel = num.intValue();
            }

            double reward = 0.0;
            Object rewardObj = wordData.get("reward");
            if (rewardObj instanceof Number num) {
                reward = num.doubleValue();
            }

            Word word = new Word(english, chinese, partOfSpeech, phonetic,
                    exampleSentence, synonyms, antonyms, difficulty, difficultyLevel, reward);
            words.add(word);
        }
    }

    public List<Word> selectWordsForSession(int count, Set<String> masteredWords) {
        List<Word> unmastered;
        List<Word> mastered;

        if (cachedMasteredKey != null && cachedMasteredKey.equals(masteredWords)
                && cachedUnmastered != null && cachedMastered != null) {
            unmastered = cachedUnmastered;
            mastered = cachedMastered;
        } else {
            unmastered = new ArrayList<>();
            mastered = new ArrayList<>();
            for (Word w : words) {
                if (masteredWords.contains(w.getEnglish().toLowerCase())) {
                    mastered.add(w);
                } else {
                    unmastered.add(w);
                }
            }
            cachedUnmastered = unmastered;
            cachedMastered = mastered;
            cachedMasteredKey = masteredWords;
        }

        List<Word> selected = new ArrayList<>();

        if (unmastered.size() >= count) {
            List<Word> shuffledUnmastered = new ArrayList<>(unmastered);
            Collections.shuffle(shuffledUnmastered, random);
            selected.addAll(shuffledUnmastered.subList(0, count));
        } else {
            selected.addAll(unmastered);

            List<Word> shuffledMastered = new ArrayList<>(mastered);
            Collections.shuffle(shuffledMastered, random);

            int remaining = count - selected.size();
            int toAdd = Math.min(remaining, shuffledMastered.size());
            selected.addAll(shuffledMastered.subList(0, toAdd));
        }

        Collections.shuffle(selected, random);
        return selected;
    }

    public Word getWord(String english) {
        return words.stream()
                .filter(w -> w.getEnglish().equalsIgnoreCase(english))
                .findFirst()
                .orElse(null);
    }

    public List<Word> getRandomWords(int count, Word exclude) {
        List<Word> pool = words.stream()
                .filter(w -> !w.equals(exclude))
                .collect(Collectors.toList());
        Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(count, pool.size()));
    }

    public void addWord(Word word) {
        words.add(word);
        invalidateCache();
    }

    public boolean removeWord(String english) {
        boolean removed = words.removeIf(w -> w.getEnglish().equalsIgnoreCase(english));
        if (removed) {
            invalidateCache();
        }
        return removed;
    }

    private void invalidateCache() {
        cachedMasteredKey = null;
        cachedUnmastered = null;
        cachedMastered = null;
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