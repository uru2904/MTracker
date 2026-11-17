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

    @Test fun samsung16Nov84() {
        // 6 h 49 en cama, 6 h 25 real, profundo 59, REM 60, despierto 24
        val s = SleepScorer.score(SleepMinutes(total = 409, awake = 24, light = 266, deep = 59, rem = 60))
        assertTrue("puntaje ${s.score}", s.score in 81..87)
        assertEquals("Buena", s.duration)
        assertEquals("Regular", s.rem)
        assertEquals("Excelente", s.deep)
        assertEquals("Buena", s.label)
    }

    @Test fun samsung14Nov61() {
        val s = SleepScorer.score(SleepMinutes(total = 277, awake = 24, light = 184, deep = 33, rem = 36))
        assertTrue("puntaje ${s.score}", s.score in 56..66)
        assertEquals("Atención", s.duration)
        assertEquals("Regular", s.label)
    }

    @Test fun sinFases() {
        assertEquals(100, SleepScorer.score(SleepMinutes(total = 480, awake = 0, light = 0, deep = 0, rem = 0)).score)
    }
}
