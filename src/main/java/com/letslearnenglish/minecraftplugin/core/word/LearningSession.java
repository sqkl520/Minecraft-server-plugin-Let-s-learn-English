package com.letslearnenglish.minecraftplugin.core.word;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learning Session - tracks a player's current word learning session.
 *
 * Maintains the state of an active learning session including:
 * - Words being learned
 * - Current word index
 * - Results (correct/incorrect counts per word)
 * - Session statistics
 */
public class LearningSession {

    private final UUID playerId;
    private final String difficulty;
    private final String mode;
    private final List<Word> words;
    private final Map<String, WordResult> results;
    private int currentIndex;
    private int totalCorrect;
    private int totalIncorrect;
    private final long startTime;
    private boolean completed;

    public LearningSession(UUID playerId, String difficulty, String mode, List<Word> words) {
        this.playerId = playerId;
        this.difficulty = difficulty;
        this.mode = mode;
        this.words = new ArrayList<>(words);
        this.results = new ConcurrentHashMap<>();
        this.currentIndex = 0;
        this.totalCorrect = 0;
        this.totalIncorrect = 0;
        this.startTime = System.currentTimeMillis();
        this.completed = false;

        for (Word word : words) {
            results.put(word.getEnglish().toLowerCase(), new WordResult(word));
        }
    }

    /**
     * Get the current word being learned.
     */
    public Word getCurrentWord() {
        if (currentIndex >= words.size()) {
            return null;
        }
        return words.get(currentIndex);
    }

    /**
     * Record a correct answer for the current word and advance.
     */
    public void recordCorrect() {
        Word current = getCurrentWord();
        if (current != null) {
            WordResult result = results.get(current.getEnglish().toLowerCase());
            if (result != null) {
                result.incrementCorrect();
            }
            totalCorrect++;
        }
        advance();
    }

    /**
     * Record an incorrect answer for the current word and advance.
     */
    public void recordIncorrect() {
        Word current = getCurrentWord();
        if (current != null) {
            WordResult result = results.get(current.getEnglish().toLowerCase());
            if (result != null) {
                result.incrementIncorrect();
            }
            totalIncorrect++;
        }
        advance();
    }

    /**
     * Move to the next word.
     */
    public void advance() {
        currentIndex++;
        if (currentIndex >= words.size()) {
            completed = true;
        }
    }

    /**
     * Get the accuracy rate (0.0 to 1.0).
     */
    public double getAccuracy() {
        int total = totalCorrect + totalIncorrect;
        if (total == 0) {
            return 0.0;
        }
        return (double) totalCorrect / total;
    }

    /**
     * Get the score for this session.
     */
    public int getScore() {
        return totalCorrect * 10 - totalIncorrect * 2;
    }

    /**
     * Get the elapsed time in seconds.
     */
    public long getElapsedSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public String getMode() {
        return mode;
    }

    public List<Word> getWords() {
        return Collections.unmodifiableList(words);
    }

    public Map<String, WordResult> getResults() {
        return Collections.unmodifiableMap(results);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getTotalWords() {
        return words.size();
    }

    public int getTotalCorrect() {
        return totalCorrect;
    }

    public int getTotalIncorrect() {
        return totalIncorrect;
    }

    public boolean isCompleted() {
        return completed;
    }

    public long getStartTime() {
        return startTime;
    }

    /**
     * Inner class representing the result for a single word.
     */
    public static class WordResult {
        private final Word word;
        private int correctCount;
        private int incorrectCount;

        public WordResult(Word word) {
            this.word = word;
            this.correctCount = 0;
            this.incorrectCount = 0;
        }

        public void incrementCorrect() {
            correctCount++;
        }

        public void incrementIncorrect() {
            incorrectCount++;
        }

        public Word getWord() {
            return word;
        }

        public int getCorrectCount() {
            return correctCount;
        }

        public int getIncorrectCount() {
            return incorrectCount;
        }

        public boolean isMastered() {
            int total = correctCount + incorrectCount;
            return total > 0 && (double) correctCount / total >= 0.8;
        }
    }
}