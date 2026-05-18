package com.letslearnenglish.minecraftplugin.core.dialogue;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grammar Checker
 *
 * Provides basic grammar checking for player responses in dialogue practice.
 * Uses regex pattern matching to validate player input against expected patterns.
 */
public class GrammarChecker {

    private static final Logger LOGGER = Logger.getLogger(GrammarChecker.class.getName());

    /**
     * Check if a player's response matches the expected grammar pattern.
     *
     * @param playerInput     the player's typed response
     * @param expectedPattern the regex pattern to match against
     * @return GrammarResult containing check results and feedback
     */
    public GrammarResult check(String playerInput, String expectedPattern) {
        if (playerInput == null || playerInput.trim().isEmpty()) {
            return new GrammarResult(false, "Please type a response.");
        }

        if (expectedPattern == null || expectedPattern.isEmpty()) {
            return new GrammarResult(true, null);
        }

        try {
            Pattern pattern = Pattern.compile(expectedPattern);
            boolean matches = pattern.matcher(playerInput.trim()).find();

            if (matches) {
                return new GrammarResult(true, null);
            } else {
                return new GrammarResult(false, "Try using a different expression.");
            }
        } catch (PatternSyntaxException e) {
            LOGGER.log(Level.WARNING, "Invalid grammar pattern: " + expectedPattern, e);
            return new GrammarResult(false, "Grammar check unavailable for this response.");
        }
    }

    /**
     * Result of a grammar check.
     */
    public static class GrammarResult {
        private final boolean correct;
        private final String hint;

        public GrammarResult(boolean correct, String hint) {
            this.correct = correct;
            this.hint = hint;
        }

        public boolean isCorrect() {
            return correct;
        }

        public String getHint() {
            return hint;
        }
    }
}