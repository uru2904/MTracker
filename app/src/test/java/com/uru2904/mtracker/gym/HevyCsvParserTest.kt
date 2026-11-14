package com.uru2904.mtracker.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class HevyCsvParserTest {
    private val csv = """
"title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
"Full A","12 nov 2026, 11:45","12 nov 2026, 13:17","","Press de Piernas",,"",0,"normal",110,11,,,
"Full A","12 nov 2026, 11:45","12 nov 2026, 13:17","","Press de Piernas",,"",1,"normal",110,10,,,
"Jueves v2","31 oct 2026, 10:42","31 oct 2026, 12:18","","Dominada",,"",0,"normal",,10,,,
""".trimIndent()

    @Test fun agrupaSesionesYSeries() {
        val s = HevyCsvParser.parse(csv)
        assertEquals(2, s.size)
        assertEquals("Jueves v2", s[0].title) // ordenadas por fecha
        val fullA = s[1]
        assertEquals(LocalDateTime.of(2026, 11, 12, 11, 45), fullA.start)
        assertEquals(92, fullA.durationMin)
        assertEquals(2, fullA.sets.size)
        assertEquals(110.0, fullA.sets[0].weightKg!!, 0.0)
        assertEquals(11, fullA.sets[0].reps)
        assertNull(s[0].sets[0].weightKg) // dominada sin peso
    }

    @Test fun mesesEnInglesYEspanol() {
        assertEquals(4, HevyCsvParser.parseDate("3 abr 2026, 09:05").monthValue)
        assertEquals(8, HevyCsvParser.parseDate("3 Aug 2026, 09:05").monthValue)
    }
}
