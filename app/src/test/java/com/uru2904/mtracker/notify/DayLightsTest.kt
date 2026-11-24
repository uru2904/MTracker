package com.uru2904.mtracker.notify

import com.uru2904.mtracker.domain.ShiftCalendar
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class DayLightsTest {
    private val cal = ShiftCalendar() // semana del 9/11 = TT, 16/11 = TM, 23/11 = TN

    @Test fun avisoTmTtAMedianoche() {
        // Semana TM (16/11): corte 02:00 -> aviso 00:00 del día siguiente
        assertEquals(LocalDateTime.of(2026, 11, 19, 0, 0), DayLights.nextFire(LocalDateTime.of(2026, 11, 18, 15, 0), cal, 120))
    }

    @Test fun avisoTnALas5() {
        // Semana TN (23/11): corte 07:00 -> aviso 05:00
        assertEquals(LocalDateTime.of(2026, 11, 25, 5, 0), DayLights.nextFire(LocalDateTime.of(2026, 11, 24, 20, 0), cal, 120))
    }

    @Test fun domingoAntesDeTnUsaCorteDelLunes() {
        // Domingo 22/11 (semana TM) termina el lunes 23/11 a las 07:00 (semana TN) -> aviso 05:00
        assertEquals(LocalDateTime.of(2026, 11, 23, 5, 0), DayLights.nextFire(LocalDateTime.of(2026, 11, 22, 18, 0), cal, 120))
    }

    @Test fun pasadoElAvisoVaAlDiaSiguiente() {
        // 01:00 del 19/11 (todavía día lógico 18/11, ya pasó el aviso de las 00:00)
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0), DayLights.nextFire(LocalDateTime.of(2026, 11, 19, 1, 0), cal, 120))
    }

    @Test fun semaforo() {
        assertEquals(Light.RED, DayLights.food(0, 0.0, 0.0, 2250, 150).light)
        assertEquals(Light.YELLOW, DayLights.food(3, 2000.0, 90.0, 2250, 150).light)
        assertEquals(Light.GREEN, DayLights.food(3, 2100.0, 140.0, 2250, 150).light)
        assertEquals(Light.GREEN, DayLights.gym(3, "Full B").light)
        assertEquals(Light.YELLOW, DayLights.gym(5, "Full B").light)
        assertEquals(Light.RED, DayLights.gym(6, "Full B").light)
        assertEquals(Light.NONE, DayLights.gym(null, "Full A").light)
        assertEquals(Light.YELLOW, DayLights.sleep(400, 450).light)
        assertEquals(Light.RED, DayLights.sleep(380, 450).light)
        assertEquals(Light.YELLOW, DayLights.weight(4).light)
        assertEquals(Light.RED, DayLights.weight(8).light)
        assertEquals(Light.YELLOW, DayLights.screen(150, 180).light)
        assertEquals(Light.RED, DayLights.screen(181, 180).light)
    }

    @Test fun todoVerdeEsTexto() {
        val all = listOf(Topic("Comida", Light.GREEN), Topic("Gym", Light.GREEN))
        assertEquals("Todo en verde hoy 💪", DayLights.message(all).title)
        val mixed = listOf(Topic("Comida", Light.GREEN), Topic("Peso", Light.RED, "Peso: 8 días sin pesarte"))
        assertEquals("🟢 Comida  🔴 Peso", DayLights.message(mixed).text)
        assertEquals("🔴 Peso: 8 días sin pesarte", DayLights.message(mixed).expanded)
    }
}
