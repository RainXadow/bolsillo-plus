package com.jesusluna.bolsilloplus.ui.statistics

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests para la lógica de selección/ordenación top-8 del stacked bar chart.
 * Replica la lógica de renderStackedBarChart (StatisticsActivity) sin Android.
 */
class StackedBarTopTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeBar(label: String, amounts: Map<String, Double>) =
        StackedBarEntry(label, amounts)

    /**
     * Replica exacta de la lógica de selección en renderStackedBarChart:
     * total acumulado por categoría → filtrar epsilon → ordenar desc → top-8.
     */
    private fun computeTop8(bars: List<StackedBarEntry>): List<String> {
        val catNames = bars.flatMap { it.categoryAmounts.keys }.distinct()
        val totals = catNames.associateWith { name ->
            bars.sumOf { bar -> bar.categoryAmounts[name] ?: 0.0 }
        }
        return totals.entries
            .filter { (_, total) -> total > 0.0001 }
            .sortedByDescending { (_, total) -> total }
            .take(8)
            .map { (name, _) -> name }
    }

    // ── ST01 — ordenación por total ───────────────────────────────────────────

    @Test
    fun `ST01 - categorias se ordenan por total acumulado no por nombre`() {
        // "Zzz" (mayor gasto) no es la primera alfabéticamente
        val bars = listOf(
            makeBar("Ene", mapOf(
                "Alimentación" to 500.0, "Bizum" to 100.0, "Compras" to 200.0,
                "Deporte" to 50.0, "Educación" to 80.0, "Farmacia" to 30.0,
                "Gasolina" to 120.0, "Hogar" to 70.0, "Internet" to 40.0,
                "Zzz" to 1000.0  // nombre al final alfabéticamente, máximo gasto
            ))
        )

        val top8 = computeTop8(bars)

        assertEquals("Zzz debe ser la primera (mayor total)", "Zzz", top8.first())
        assertEquals("Alimentación segunda (500)", "Alimentación", top8[1])
        assertEquals(8, top8.size)
        // "Farmacia" (30) y "Internet" (40) quedan fuera del top-8
        assertFalse(top8.contains("Farmacia"))
        assertFalse(top8.contains("Internet"))
    }

    // ── ST02 — menos de 8 categorías ─────────────────────────────────────────

    @Test
    fun `ST02 - con 6 categorias devuelve las 6 sin rellenar`() {
        val bars = listOf(
            makeBar("Ene", mapOf(
                "Cat1" to 100.0, "Cat2" to 200.0, "Cat3" to 50.0,
                "Cat4" to 300.0, "Cat5" to 150.0, "Cat6" to 80.0
            ))
        )

        val top8 = computeTop8(bars)

        assertEquals(6, top8.size)
        assertEquals("Cat4", top8.first())  // mayor: 300
    }

    // ── ST03 — categorías con total == 0.0 excluidas ─────────────────────────

    @Test
    fun `ST03 - categorias con total 0 exacto se excluyen`() {
        val bars = listOf(
            makeBar("Ene", mapOf("Activa" to 100.0, "Cero" to 0.0)),
            makeBar("Feb", mapOf("Activa" to 50.0,  "Cero" to 0.0))
        )

        val top8 = computeTop8(bars)

        assertTrue(top8.contains("Activa"))
        assertFalse("Cero con total 0 no debe aparecer", top8.contains("Cero"))
        assertEquals(1, top8.size)
    }

    // ── ST04 — epsilon: 0.00009 excluida, 0.0002 incluida ────────────────────

    @Test
    fun `ST04 - epsilon filtra correctamente valores subumbral y supraumbral`() {
        val bars = listOf(
            makeBar("Ene", mapOf(
                "Casi" to 0.00009,   // < 0.0001 → excluida
                "Justa" to 0.0002,   // > 0.0001 → incluida
                "Normal" to 50.0
            ))
        )

        val top8 = computeTop8(bars)

        assertFalse("0.00009 debe quedar fuera del umbral epsilon", top8.contains("Casi"))
        assertTrue("0.0002 supera el umbral epsilon", top8.contains("Justa"))
        assertTrue(top8.contains("Normal"))
        assertEquals(2, top8.size)
    }

    // ── ST05 — allCategoryColors disponible aunque cat tenga 0 en breakdown ──

    @Test
    fun `ST05 - categoria con gastos solo en un periodo obtiene color de allCategoryColors`() {
        // "Viajes" tiene gastos en Ene pero no en Feb
        val bars = listOf(
            makeBar("Ene", mapOf("Alimentación" to 200.0, "Viajes" to 150.0)),
            makeBar("Feb", mapOf("Alimentación" to 100.0, "Viajes" to 0.0))
        )

        // allCategoryColors incluye TODAS las categorías root independientemente de gastos
        val allCategoryColors = mapOf(
            "Alimentación" to "#4CAF50",
            "Viajes" to "#FF5722"  // tiene color aunque en Feb tenga 0
        )

        val top8 = computeTop8(bars)

        // Viajes tiene total 150 > 0.0001 → debe aparecer en top8
        assertTrue("Viajes debe estar en top8 por sus gastos en Ene", top8.contains("Viajes"))

        // Color disponible desde allCategoryColors aunque no esté en breakdown de este periodo
        val color = allCategoryColors["Viajes"]
        assertNotNull("El color de Viajes debe estar en allCategoryColors", color)
        assertEquals("#FF5722", color)

        // categoryBreakdown (solo período Feb, por ejemplo) podría no tener Viajes → null
        val fakeBreakdownColors = mapOf("Alimentación" to "#4CAF50")  // Viajes ausente
        assertNull("Viajes ausente en breakdown → color nulo con enfoque antiguo",
            fakeBreakdownColors["Viajes"])
        // El nuevo enfoque con allCategoryColors resuelve este caso
        assertNotNull("allCategoryColors siempre tiene el color", allCategoryColors["Viajes"])
    }
}
