package com.cookbook.ui.cook

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StepDurationsTest {

    @Test
    fun findsCommonPhrasings() {
        assertEquals(30 * 60, StepDurations.firstDurationSeconds("Simmer for 30 minutes, stirring."))
        assertEquals(45 * 60, StepDurations.firstDurationSeconds("Rest 45 min before slicing"))
        assertEquals(90, StepDurations.firstDurationSeconds("Microwave 90 seconds"))
        assertEquals(2 * 3600, StepDurations.firstDurationSeconds("Braise 2 hours in the oven"))
        assertEquals(10 * 60, StepDurations.firstDurationSeconds("bake 10 mins until golden"))
    }

    @Test
    fun combinesAdjacentUnits() {
        assertEquals(
            3600 + 15 * 60,
            StepDurations.firstDurationSeconds("Roast 1 hour 15 minutes at 350."),
        )
        assertEquals(
            90 * 60,
            StepDurations.firstDurationSeconds("Proof 1.5 hours in a warm spot"),
        )
    }

    @Test
    fun usesFirstPhraseOnly() {
        // Two separate durations: the timer offers the first (the active one).
        assertEquals(
            20 * 60,
            StepDurations.firstDurationSeconds("Simmer 20 minutes, then rest 10 minutes."),
        )
    }

    @Test
    fun ignoresNonDurations() {
        assertNull(StepDurations.firstDurationSeconds("Chop the onions finely."))
        assertNull(StepDurations.firstDurationSeconds("Preheat the oven to 350."))
        // Multi-day marinades aren't kitchen timers.
        assertNull(StepDurations.firstDurationSeconds("Marinate 48 hours."))
    }

    @Test
    fun labels() {
        assertEquals("30:00", StepDurations.label(1800))
        assertEquals("1:15:00", StepDurations.label(4500))
        assertEquals("0:45", StepDurations.label(45))
    }
}
