package com.uru2904.mtracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepScoreTest {
    @Test fun nocheIdeal() {
        val s = SleepScorer.score(SleepMinutes(total = 500, awake = 20, light = 260, deep = 80, rem = 140))
        assertEquals(100, s.score)
        assertEquals("Excelente", s.label)
    }

    @Test fun nocheCorta() {
        val s = SleepScorer.score(SleepMinutes(total = 277, awake = 6, light = 73 + 60, deep = 33, rem = 36 + 69))
        assertTrue(s.score in 50..80)
        assertEquals("Atención", s.duration)
    }

    @Test fun sinFases() {
        val s = SleepScorer.score(SleepMinutes(total = 480, awake = 0, light = 0, deep = 0, rem = 0))
        assertEquals(100, s.score)
    }
}
