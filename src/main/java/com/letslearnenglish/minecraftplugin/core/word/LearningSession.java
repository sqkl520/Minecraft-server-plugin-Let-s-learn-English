package com.letslearnenglish.minecraftplugin.core.word;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private final AtomicInteger totalCorrect = new AtomicInteger(0);
    private final AtomicInteger totalIncorrect = new AtomicInteger(0);
    private final AtomicLong totalScore = new AtomicLong(0);
    private final long startTime;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public LearningSession(UUID playerId, String difficulty, String mode, List<Word> words) {
        this.playerId = playerId;
        this.difficulty = difficulty;
        this.mode = mode;
        this.words = new ArrayList<>(words);
        this.results = new ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();

        for (Word word : words) {
            results.put(word.getEnglish().toLowerCase(), new WordResult(word));
        }
    }

    /**
     * Get the current word being learned.
     */
    public Word getCurrentWord() {
        if (currentIndex.get() >= words.size()) {
            return null;
        }
        return words.get(currentIndex.get());
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
            totalCorrect.incrementAndGet();
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
            totalIncorrect.incrementAndGet();
        }
        advance();
    }

    /**
     * Move to the next word.
     */
    public void advance() {
        currentIndex.incrementAndGet();
        if (currentIndex.get() >= words.size()) {
            completed.set(true);
        }
    }

    /**
     * Get the accuracy rate (0.0 to 1.0).
     */
    public double getAccuracy() {
        int total = totalCorrect.get() + totalIncorrect.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) totalCorrect.get() / total;
    }

    /**
     * Get the score for this session.
     */
    public int getScore() {
        return (int) totalScore.get();
    }

    public void addScore(int points) {
        this.totalScore.addAndGet(points);
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
        return currentIndex.get();
    }

    public int getTotalWords() {
        return words.size();
    }

    public int getTotalCorrect() {
        return totalCorrect.get();
    }

    public int getTotalIncorrect() {
        return totalIncorrect.get();
    }

    public boolean isCompleted() {
        return completed.get();
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