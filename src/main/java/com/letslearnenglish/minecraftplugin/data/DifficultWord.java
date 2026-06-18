package com.letslearnenglish.minecraftplugin.data;

/**
 * DifficultWord — represents a word added to the player's "Wrong Book".
 *
 * Tracks how many times the player answered this word incorrectly and when
 * it was added to the difficult words list.
 */
public class DifficultWord {

    private final String word;
    private final int wrongCount;
    private final long addedAt;

    public DifficultWord(String word, int wrongCount, long addedAt) {
        this.word = word;
        this.wrongCount = wrongCount;
        this.addedAt = addedAt;
    }

    public String getWord() {
        return word;
    }

    public int getWrongCount() {
        return wrongCount;
    }

    public long getAddedAt() {
        return addedAt;
    }

    @Override
    public String toString() {
        return "DifficultWord{word='" + word + "', wrongCount=" + wrongCount + ", addedAt=" + addedAt + "}";
    }
}
