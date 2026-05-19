package com.letslearnenglish.minecraftplugin.core.progress;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

class PlayerProgressTest {

    @Test
    void testDefaultValues() {
        UUID id = UUID.randomUUID();
        PlayerProgress pp = new PlayerProgress(id);

        assertEquals(id, pp.getPlayerId());
        assertEquals(0, pp.getTotalWordsLearned());
        assertEquals(0, pp.getTotalSessions());
        assertEquals(0, pp.getTotalScore());
        assertEquals(0, pp.getCurrentStreak());
        assertEquals(0, pp.getLongestStreak());
    }

    @Test
    void testSetters() {
        PlayerProgress pp = new PlayerProgress(UUID.randomUUID());

        pp.setTotalWordsLearned(100);
        assertEquals(100, pp.getTotalWordsLearned());

        pp.setTotalSessions(20);
        assertEquals(20, pp.getTotalSessions());

        pp.setTotalScore(5000);
        assertEquals(5000, pp.getTotalScore());

        pp.setCurrentStreak(7);
        assertEquals(7, pp.getCurrentStreak());

        pp.setLongestStreak(14);
        assertEquals(14, pp.getLongestStreak());
    }
}