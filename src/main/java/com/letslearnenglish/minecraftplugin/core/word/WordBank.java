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

        int index = 0;
        for (Object item : rawList) {
            String entryId = difficulty + ".yml words[" + index + "]";
            index++;
            if (!(item instanceof Map)) {
                warnSkip(entryId, "词条不是 Map（实际类型="
                        + (item == null ? "null" : item.getClass().getSimpleName()) + "）");
                continue;
            }
            // Build a type-checked Map<String, Object> from the raw YAML map
            Map<String, Object> wordData = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                if (entry.getKey() instanceof String key) {
                    wordData.put(key, entry.getValue());
                }
            }
            // R-06：english/chinese 是判分与渲染的必填键，坏一条跳过一条，
            // 绝不 new Word(null, …) 把 NPE 炸弹埋进词库。
            String english = asStringOrNull(wordData.get("english"));
            if (english == null || english.trim().isEmpty()) {
                warnSkip(entryId, describeBadField(wordData.get("english"), "english"));
                continue;
            }
            String chinese = asStringOrNull(wordData.get("chinese"));
            if (chinese == null || chinese.trim().isEmpty()) {
                warnSkip(entryId, describeBadField(wordData.get("chinese"), "chinese"));
                continue;
            }
            String partOfSpeech = asStringOrNull(wordData.get("part_of_speech"));
            String phonetic = asStringOrNull(wordData.get("phonetic"));
            String exampleSentence = asStringOrNull(wordData.get("example_sentence"));

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

    /**
     * 宽容取 String：非 String 按 null 处理（YAML 用户手写时 number/boolean 误入字符串
     * 字段是常态，直接强转会抛 ClassCastException 炸掉整档加载）。
     */
    private static String asStringOrNull(Object value) {
        return value instanceof String str ? str : null;
    }

    /**
     * 描述坏字段的诊断串：缺失 / 非 String（含实际类型）/ 空白，三选一。
     */
    private static String describeBadField(Object value, String field) {
        if (value == null) {
            return "缺少必填字段 '" + field + "'";
        }
        if (!(value instanceof String)) {
            return "字段 '" + field + "' 不是 String（实际类型=" + value.getClass().getSimpleName() + "）";
        }
        return "字段 '" + field + "' 为空或空白";
    }

    /**
     * R-06 跳过日志：统一走 Bukkit 日志器（WordBank 无 plugin 引用，与现有本类唯一
     * 日志调用点风格一致）。
     */
    private static void warnSkip(String entryId, String reason) {
        org.bukkit.Bukkit.getLogger().warning(
                "[LetsLearnEnglish] 跳过坏词条 " + entryId + "： " + reason + "，已跳过不加载。");
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