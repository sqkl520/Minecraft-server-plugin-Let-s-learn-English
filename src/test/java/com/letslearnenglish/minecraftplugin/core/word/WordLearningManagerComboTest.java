package com.letslearnenglish.minecraftplugin.core.word;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Pure-logic tests for the combo multiplier table.
 *
 * The table actually lives in {@link WordLearningManager#getComboMultiplier(int)}
 * (thresholds {0,3,5,7,10} -&gt; multipliers {1.0,1.5,2.0,2.5,3.0}), not in
 * RewardEngine as one might expect -- RewardEngine only consumes a pre-computed
 * multiplier. getComboMultiplier(int) is a pure function of its argument: it reads
 * only the static tables and never touches the plugin, scheduler, or any Bukkit API.
 *
 * We therefore test the real production method directly. The only coupling is the
 * constructor, which calls plugin.getWordManager(); a Mockito mock (returning null)
 * satisfies that without standing up any Bukkit runtime -- hence no MockBukkit needed.
 */
class WordLearningManagerComboTest {

    private WordLearningManager manager;

    @BeforeEach
    void setUp() {
        LetsLearnEnglish plugin = mock(LetsLearnEnglish.class);
        manager = new WordLearningManager(plugin);
    }

    @Test
    void zeroToTwoConsecutiveIsBaseMultiplier() {
        assertEquals(1.0, manager.getComboMultiplier(0), 0.0001);
        assertEquals(1.0, manager.getComboMultiplier(1), 0.0001);
        assertEquals(1.0, manager.getComboMultiplier(2), 0.0001);
    }

    @Test
    void threeToFourConsecutiveIs1_5x() {
        assertEquals(1.5, manager.getComboMultiplier(3), 0.0001);
        assertEquals(1.5, manager.getComboMultiplier(4), 0.0001);
    }

    @Test
    void fiveToSixConsecutiveIs2x() {
        assertEquals(2.0, manager.getComboMultiplier(5), 0.0001);
        assertEquals(2.0, manager.getComboMultiplier(6), 0.0001);
    }

    @Test
    void sevenToNineConsecutiveIs2_5x() {
        assertEquals(2.5, manager.getComboMultiplier(7), 0.0001);
        assertEquals(2.5, manager.getComboMultiplier(8), 0.0001);
        assertEquals(2.5, manager.getComboMultiplier(9), 0.0001);
    }

    @Test
    void tenAndAboveConsecutiveIs3x() {
        assertEquals(3.0, manager.getComboMultiplier(10), 0.0001);
        assertEquals(3.0, manager.getComboMultiplier(11), 0.0001);
        assertEquals(3.0, manager.getComboMultiplier(100), 0.0001);
    }

    @Test
    void negativeConsecutiveClampsToBase() {
        assertEquals(1.0, manager.getComboMultiplier(-1), 0.0001);
        assertEquals(1.0, manager.getComboMultiplier(-50), 0.0001);
    }
}
