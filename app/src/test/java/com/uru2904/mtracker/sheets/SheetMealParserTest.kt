package com.uru2904.mtracker.sheets

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SheetMealParserTest {
    private val zone = ZoneId.of("America/Argentina/Cordoba")

    @Test fun filasNormalesYMadrugada() {
        val rows = listOf(
            listOf("2026-11-14", "14:00", "Almuerzo", "6 empanadas", "1620", "69", "158", "79", "Media"),
            listOf("2026-11-13", "0:56", "Antes de dormir", "Vaso Leche Protein", "118", "15", "13", "0", "Alta"),
            listOf("2026-11-13", "", "Desayuno", "Taza", "105", "13", "12", "0"),
            listOf("Fecha", "Hora", "Comida"), // encabezado o basura: se ignora
        )
        val meals = SheetMealParser.parse(rows, zone = zone)
        assertEquals(3, meals.size)
        assertEquals(1620.0, meals[0].kcal, 0.0)
        assertEquals("2026-11-13", meals[1].logicalDate)
        // 00:56 del "13/11" lógico ocurrió el 14/11 calendario
        val real = LocalDateTime.ofInstant(Instant.ofEpochMilli(meals[1].timestamp), zone)
        assertEquals(LocalDateTime.of(2026, 11, 14, 0, 56), real)
        assertEquals("planilla", meals[2].source)
    }

    @Test fun idDesdeLink() {
        assertEquals(
            "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-xyz",
            SheetMealParser.sheetIdFrom("https://docs.google.com/spreadsheets/d/1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-xyz/edit#gid=0"),
        )
    }
}
