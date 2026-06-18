package com.letslearnenglish.minecraftplugin.core.word;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Word Bank Validator
 *
 * Validates YAML files for word bank import, checking structure,
 * required fields, types, and size limits.
 */
public class WordBankValidator {

    private static final int MAX_WORDS = 1000;

    /**
     * Validates a YAML configuration for word bank import.
     *
     * @param config the loaded YAML FileConfiguration
     * @return ValidationResult with list of errors (empty = valid)
     */
    public static ValidationResult validate(FileConfiguration config) {
        List<String> errors = new ArrayList<>();

        // Check that 'words' key exists and is a List
        if (!config.contains("words")) {
            errors.add("Missing required key 'words'");
            return new ValidationResult(false, errors);
        }

        Object wordsObj = config.get("words");
        if (!(wordsObj instanceof List)) {
            errors.add("The 'words' key must be a List, but got: "
                    + wordsObj.getClass().getSimpleName());
            return new ValidationResult(false, errors);
        }

        List<?> rawList = (List<?>) wordsObj;

        // Check max size to prevent DoS
        if (rawList.size() > MAX_WORDS) {
            errors.add("Word bank contains " + rawList.size()
                    + " words, exceeding the maximum of " + MAX_WORDS);
            return new ValidationResult(false, errors);
        }

        int index = 0;
        for (Object item : rawList) {
            index++;
            String prefix = "words[" + (index - 1) + "]";

            if (item == null) {
                errors.add(prefix + ": word entry is null");
                continue;
            }

            if (!(item instanceof Map)) {
                errors.add(prefix + ": word entry must be a Map, but got: "
                        + item.getClass().getSimpleName());
                continue;
            }

            Map<?, ?> rawMap = (Map<?, ?>) item;

            // Validate all keys are Strings (prevent injection)
            boolean hasNonStringKey = false;
            for (Object key : rawMap.keySet()) {
                if (!(key instanceof String)) {
                    hasNonStringKey = true;
                    break;
                }
            }
            if (hasNonStringKey) {
                errors.add(prefix + ": contains non-String keys, which is not allowed");
                continue;
            }

            // Build a typed Map<String, Object>
            @SuppressWarnings("unchecked")
            Map<String, Object> wordData = (Map<String, Object>) rawMap;

            // Required: english (String, non-null, non-empty)
            Object englishObj = wordData.get("english");
            if (englishObj == null) {
                errors.add(prefix + ": missing required field 'english'");
            } else if (!(englishObj instanceof String)) {
                errors.add(prefix + ": 'english' must be a String, but got: "
                        + englishObj.getClass().getSimpleName());
            } else if (((String) englishObj).trim().isEmpty()) {
                errors.add(prefix + ": 'english' must not be empty");
            }

            // Required: chinese (String, non-null, non-empty)
            Object chineseObj = wordData.get("chinese");
            if (chineseObj == null) {
                errors.add(prefix + ": missing required field 'chinese'");
            } else if (!(chineseObj instanceof String)) {
                errors.add(prefix + ": 'chinese' must be a String, but got: "
                        + chineseObj.getClass().getSimpleName());
            } else if (((String) chineseObj).trim().isEmpty()) {
                errors.add(prefix + ": 'chinese' must not be empty");
            }

            // Optional: part_of_speech (String if present)
            Object posObj = wordData.get("part_of_speech");
            if (posObj != null && !(posObj instanceof String)) {
                errors.add(prefix + ": 'part_of_speech' must be a String if present, but got: "
                        + posObj.getClass().getSimpleName());
            }

            // Optional: phonetic (String if present)
            Object phoneticObj = wordData.get("phonetic");
            if (phoneticObj != null && !(phoneticObj instanceof String)) {
                errors.add(prefix + ": 'phonetic' must be a String if present, but got: "
                        + phoneticObj.getClass().getSimpleName());
            }

            // Optional: example_sentence (String if present)
            Object exampleObj = wordData.get("example_sentence");
            if (exampleObj != null && !(exampleObj instanceof String)) {
                errors.add(prefix + ": 'example_sentence' must be a String if present, but got: "
                        + exampleObj.getClass().getSimpleName());
            }

            // Optional: synonyms (List of Strings if present)
            Object synObj = wordData.get("synonyms");
            if (synObj != null) {
                if (!(synObj instanceof List)) {
                    errors.add(prefix + ": 'synonyms' must be a List if present, but got: "
                            + synObj.getClass().getSimpleName());
                } else {
                    int si = 0;
                    for (Object s : (List<?>) synObj) {
                        if (!(s instanceof String)) {
                            errors.add(prefix + ".synonyms[" + si + "]: must be a String, but got: "
                                    + (s != null ? s.getClass().getSimpleName() : "null"));
                        }
                        si++;
                    }
                }
            }

            // Optional: antonyms (List of Strings if present)
            Object antObj = wordData.get("antonyms");
            if (antObj != null) {
                if (!(antObj instanceof List)) {
                    errors.add(prefix + ": 'antonyms' must be a List if present, but got: "
                            + antObj.getClass().getSimpleName());
                } else {
                    int ai = 0;
                    for (Object a : (List<?>) antObj) {
                        if (!(a instanceof String)) {
                            errors.add(prefix + ".antonyms[" + ai + "]: must be a String, but got: "
                                    + (a != null ? a.getClass().getSimpleName() : "null"));
                        }
                        ai++;
                    }
                }
            }

            // Optional: difficulty_level (Number if present)
            Object diffObj = wordData.get("difficulty_level");
            if (diffObj != null && !(diffObj instanceof Number)) {
                errors.add(prefix + ": 'difficulty_level' must be a Number if present, but got: "
                        + diffObj.getClass().getSimpleName());
            }

            // Optional: reward (Number if present)
            Object rewardObj = wordData.get("reward");
            if (rewardObj != null && !(rewardObj instanceof Number)) {
                errors.add(prefix + ": 'reward' must be a Number if present, but got: "
                        + rewardObj.getClass().getSimpleName());
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * Validation result record.
     */
    public record ValidationResult(boolean valid, List<String> errors) {
    }
}
