package com.letslearnenglish.minecraftplugin.core.achievement;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic assertions for the achievement definition layer
 * (plan-achievements US-ACH-1, §3.1 单测锚点).
 *
 * <p>No Bukkit dependency — runs as a plain JUnit test. Guards the
 * definition-layer contracts the rest of the system relies on:
 * id freezing (DB rows + PAPI + fromId scans), bilingual literal
 * completeness, category coverage (wall grouping) and the
 * progressSource/target consistency the wall progress line reads.
 */
class AchievementDefinitionTest {

    /**
     * The 16 pre-Phase-3 ids, frozen forever: they may already exist in
     * production databases ({@code achievements.achievement_id}). A rename
     * would lose old players' progress. See plan-achievements §4.4.
     */
    private static final Set<String> FROZEN_LEGACY_IDS = Set.of(
            "first_word", "words_25", "words_100", "words_500", "perfect_session",
            "streak_3", "streak_10", "spelling_master", "daily_streak_3", "daily_streak_7",
            "sessions_10", "sessions_50", "coins_500", "coins_5000", "ai_chat_10", "ai_chat_50");

    /** The 6 Phase-3 additions (US-ACH-1). Same freezing rule applies from now on. */
    private static final Set<String> NEW_IDS = Set.of(
            "review_streak_7", "wrong_book_cleared", "accuracy_90",
            "duel_first_win", "helpful", "study_days_30");

    @Test
    void testValuesCountAndFrozenIdSet() {
        assertEquals(22, Achievement.values().length,
                "definition layer must hold 16 legacy + 6 Phase-3 achievements");

        Set<String> actualIds = new HashSet<>();
        for (Achievement a : Achievement.values()) {
            actualIds.add(a.getId());
        }
        Set<String> expected = new HashSet<>(FROZEN_LEGACY_IDS);
        expected.addAll(NEW_IDS);
        assertEquals(expected, actualIds,
                "ids are frozen contracts — no renames, no removals, no accidental additions");
    }

    @Test
    void testIdsAreUniqueAndResolvable() {
        Set<String> seen = new HashSet<>();
        for (Achievement a : Achievement.values()) {
            assertTrue(seen.add(a.getId()), "duplicate achievement id: " + a.getId());
            assertSame(a, Achievement.fromId(a.getId()),
                    "fromId must resolve every declared id: " + a.getId());
        }
        assertNull(Achievement.fromId("no_such_achievement"),
                "unknown ids must resolve to null (skip semantics in DB load / PAPI)");
        assertNull(Achievement.fromId(null),
                "fromId(null) must not throw and must resolve to null");
    }

    @Test
    void testBilingualLiteralsAreComplete() {
        for (Achievement a : Achievement.values()) {
            assertNotNull(a.getZhName(), a.getId() + " zhName");
            assertNotNull(a.getEnName(), a.getId() + " enName");
            assertNotNull(a.getZhDesc(), a.getId() + " zhDesc");
            assertNotNull(a.getEnDesc(), a.getId() + " enDesc");
            assertFalse(a.getZhName().isBlank(), a.getId() + " zhName must not be blank");
            assertFalse(a.getEnName().isBlank(), a.getId() + " enName must not be blank");
            assertFalse(a.getZhDesc().isBlank(), a.getId() + " zhDesc must not be blank");
            assertFalse(a.getEnDesc().isBlank(), a.getId() + " enDesc must not be blank");

            // Language routing contract used by wall / chat notification / PAPI
            assertEquals(a.getZhName(), a.getDisplayName("zh"), a.getId() + " display name (zh)");
            assertEquals(a.getEnName(), a.getDisplayName("en"), a.getId() + " display name (en)");
            assertEquals(a.getZhDesc(), a.getDisplayDesc("zh"), a.getId() + " display desc (zh)");
            assertEquals(a.getEnDesc(), a.getDisplayDesc("en"), a.getId() + " display desc (en)");
            // Unknown / other language falls back to English
            assertEquals(a.getEnName(), a.getDisplayName("fr"), a.getId() + " display name fallback");
        }
    }

    @Test
    void testCategoryCoverageNoOrphans() {
        Map<Achievement.AchievementCategory, Integer> counts =
                new EnumMap<>(Achievement.AchievementCategory.class);
        for (Achievement a : Achievement.values()) {
            assertNotNull(a.getCategory(), a.getId() + " category must not be null");
            counts.merge(a.getCategory(), 1, Integer::sum);
        }
        // Every one of the 5 wall groups must be non-empty (no orphan groups,
        // no memberless category rendering an empty section).
        for (Achievement.AchievementCategory category : Achievement.AchievementCategory.values()) {
            assertTrue(counts.getOrDefault(category, 0) >= 1,
                    "category " + category + " has no members");
        }
    }

    @Test
    void testCategoryOrdinalIsWallSortKey() {
        // The wall sorts by category ordinal (LEARNING → REVIEW → CONSISTENCY →
        // SOCIAL → ECONOMY, plan-achievements §2.1). Freeze that declared order.
        assertEquals(0, Achievement.AchievementCategory.LEARNING.ordinal());
        assertEquals(1, Achievement.AchievementCategory.REVIEW.ordinal());
        assertEquals(2, Achievement.AchievementCategory.CONSISTENCY.ordinal());
        assertEquals(3, Achievement.AchievementCategory.SOCIAL.ordinal());
        assertEquals(4, Achievement.AchievementCategory.ECONOMY.ordinal());
    }

    @Test
    void testProgressSourceTargetConsistency() {
        for (Achievement a : Achievement.values()) {
            assertNotNull(a.getProgressSource(), a.getId() + " progressSource must not be null");
            if (a.getProgressSource() == Achievement.ProgressSource.NONE) {
                assertEquals(0, a.getTarget(),
                        a.getId() + ": NONE progress source must carry target 0 (wall hides the line)");
            } else {
                assertTrue(a.getTarget() > 0,
                        a.getId() + ": quantified achievement needs a positive target");
            }
        }
    }

    @Test
    void testPhase3AssignmentMapping() {
        // plan-achievements §2.1/§2.2 frozen assignments for the 6 new ids.
        assertAssignment(Achievement.REVIEW_STREAK_7,
                Achievement.AchievementCategory.REVIEW,
                Achievement.ProgressSource.REVIEW_STREAK, 7);
        assertAssignment(Achievement.WRONG_BOOK_CLEARED,
                Achievement.AchievementCategory.REVIEW,
                Achievement.ProgressSource.WRONG_BOOK_CLEARED, 5);
        assertAssignment(Achievement.ACCURACY_90,
                Achievement.AchievementCategory.LEARNING,
                Achievement.ProgressSource.ACCURACY_PCT, 90);
        assertAssignment(Achievement.DUEL_FIRST_WIN,
                Achievement.AchievementCategory.SOCIAL,
                Achievement.ProgressSource.NONE, 0);
        assertAssignment(Achievement.HELPFUL,
                Achievement.AchievementCategory.SOCIAL,
                Achievement.ProgressSource.NONE, 0);
        assertAssignment(Achievement.STUDY_DAYS_30,
                Achievement.AchievementCategory.CONSISTENCY,
                Achievement.ProgressSource.STUDY_DAYS, 30);
    }

    private static void assertAssignment(Achievement a,
                                         Achievement.AchievementCategory category,
                                         Achievement.ProgressSource source,
                                         int target) {
        assertEquals(category, a.getCategory(), a.getId() + " category");
        assertEquals(source, a.getProgressSource(), a.getId() + " progressSource");
        assertEquals(target, a.getTarget(), a.getId() + " target");
    }
}
