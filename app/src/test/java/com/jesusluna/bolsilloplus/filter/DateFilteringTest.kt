package com.jesusluna.bolsilloplus.filter



import com.jesusluna.bolsilloplus.TestDedupKey

import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory

import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity

import com.jesusluna.bolsilloplus.ui.main.DashboardPeriod

import com.jesusluna.bolsilloplus.ui.main.DateRange

import com.jesusluna.bolsilloplus.ui.main.FILTER_UNCATEGORIZED

import com.jesusluna.bolsilloplus.util.DescriptionNormalizer

import org.junit.Assert.*

import org.junit.Test



/**

 * Tests unitarios del sistema de filtrado por fechas del dashboard.

 *

 * No requieren Android ni Room. Prueba la lógica pura de:

 *   - DashboardPeriod.toDateRange()

 *   - Filtrado de transacciones por DateRange

 *   - Filtrado por categoría (FILTER_UNCATEGORIZED, específica, null)

 *   - Filtrado por búsqueda (query)

 *   - Escenarios combinados (fecha + categoría + query)

 *

 * Grupos:

 *   A - DashboardPeriod.toDateRange()

 *   B - Filtrado por rango de fechas (filterByRange)

 *   C - Filtrado por categoría (filterByCategory)

 *   D - Filtrado por query de búsqueda (filterByQuery)

 *   E - Escenarios combinados y períodos reales

 */

class DateFilteringTest {



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO A: DashboardPeriod.toDateRange()

    // ═══════════════════════════════════════════════════════════════════════════



    @Test fun A01_WEEK_range_spans_7_days() {

        val before = System.currentTimeMillis()

        val range = DashboardPeriod.WEEK.toDateRange()

        val after = System.currentTimeMillis()



        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000

        assertTrue("'to' debe ser >= before", range.to >= before)

        assertTrue("'to' debe ser <= after",  range.to <= after)

        val diff = range.to - range.from

        assertTrue("Diferencia debe ser ~7 días", diff in (sevenDaysMs - 200)..(sevenDaysMs + 200))

    }



    @Test fun A02_MONTH_range_spans_one_calendar_month() {

        // MONTH usa Calendar.add(MONTH, -1), que puede ser 28-31 días según el mes actual.

        val before = System.currentTimeMillis()

        val range = DashboardPeriod.MONTH.toDateRange()

        val after = System.currentTimeMillis()



        assertTrue("'to' debe estar entre before y after", range.to in before..after)

        val diff = range.to - range.from

        val minMonthMs = 28L * 24 * 60 * 60 * 1000  // febrero mínimo

        val maxMonthMs = 31L * 24 * 60 * 60 * 1000  // mes más largo

        assertTrue("Diferencia debe ser entre 28 y 31 días", diff in minMonthMs..maxMonthMs)

    }



    @Test fun A03_YEAR_range_spans_365_days() {

        val range = DashboardPeriod.YEAR.toDateRange()

        val yearMs = 365L * 24 * 60 * 60 * 1000

        val diff = range.to - range.from

        assertTrue("Diferencia debe ser ~365 días", diff in (yearMs - 200)..(yearMs + 200))

    }



    @Test fun A04_ALL_from_is_zero() {

        assertEquals("ALL debe tener from=0", 0L, DashboardPeriod.ALL.toDateRange().from)

    }



    @Test fun A05_CUSTOM_fallback_from_is_zero() {

        // CUSTOM.toDateRange() cae en el else → mismo resultado que ALL

        assertEquals("CUSTOM.toDateRange() debe retornar from=0 (fallback)", 0L, DashboardPeriod.CUSTOM.toDateRange().from)

    }



    @Test fun A06_preset_to_is_never_in_the_past() {

        val now = System.currentTimeMillis()

        for (p in listOf(DashboardPeriod.WEEK, DashboardPeriod.MONTH, DashboardPeriod.YEAR, DashboardPeriod.ALL)) {

            assertTrue("'to' de ${p.name} debe ser reciente (>= now-100ms)", p.toDateRange().to >= now - 100)

        }

    }



    @Test fun A07_from_is_strictly_less_than_to_for_time_based_periods() {

        for (p in listOf(DashboardPeriod.WEEK, DashboardPeriod.MONTH, DashboardPeriod.YEAR)) {

            val range = p.toDateRange()

            assertTrue("from < to para ${p.name}", range.from < range.to)

        }

    }



    @Test fun A08_WEEK_shorter_than_MONTH() {

        val weekDiff  = DashboardPeriod.WEEK.toDateRange().let { it.to - it.from }

        val monthDiff = DashboardPeriod.MONTH.toDateRange().let { it.to - it.from }

        assertTrue("Semana < Mes en ms", weekDiff < monthDiff)

    }



    @Test fun A09_MONTH_shorter_than_YEAR() {

        val monthDiff = DashboardPeriod.MONTH.toDateRange().let { it.to - it.from }

        val yearDiff  = DashboardPeriod.YEAR.toDateRange().let { it.to - it.from }

        assertTrue("Mes < Año en ms", monthDiff < yearDiff)

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO B: Filtrado por rango de fechas

    // ═══════════════════════════════════════════════════════════════════════════



    @Test fun B01_transaction_inside_range_is_included() {

        val range = DateRange(1000L, 2000L)

        val tx = makeTx(date = 1500L)

        assertEquals(1, filterByRange(listOf(tx), range).size)

    }



    @Test fun B02_transaction_before_range_is_excluded() {

        val range = DateRange(1000L, 2000L)

        val tx = makeTx(date = 999L)

        assertEquals(0, filterByRange(listOf(tx), range).size)

    }



    @Test fun B03_transaction_after_range_is_excluded() {

        val range = DateRange(1000L, 2000L)

        val tx = makeTx(date = 2001L)

        assertEquals(0, filterByRange(listOf(tx), range).size)

    }



    @Test fun B04_transaction_at_from_boundary_is_included() {

        val range = DateRange(1000L, 2000L)

        val tx = makeTx(date = 1000L)

        assertEquals("Límite inferior debe ser inclusivo", 1, filterByRange(listOf(tx), range).size)

    }



    @Test fun B05_transaction_at_to_boundary_is_included() {

        val range = DateRange(1000L, 2000L)

        val tx = makeTx(date = 2000L)

        assertEquals("Límite superior debe ser inclusivo", 1, filterByRange(listOf(tx), range).size)

    }



    @Test fun B06_ALL_range_includes_every_transaction() {

        val range = DashboardPeriod.ALL.toDateRange()  // from = 0

        val txs = listOf(makeTx(1L, 1L), makeTx(2L, 1_000_000L), makeTx(3L, Long.MAX_VALUE / 2))

        assertEquals("ALL no debe filtrar ninguna transacción", 3, filterByRange(txs, range).size)

    }



    @Test fun B07_empty_list_returns_empty() {

        assertEquals(emptyList<TransactionWithCategory>(), filterByRange(emptyList(), DateRange(1000L, 2000L)))

    }



    @Test fun B08_only_transactions_in_range_are_returned() {

        val range   = DateRange(1000L, 2000L)

        val txIn1   = makeTx(id = 1L, date = 1000L)

        val txIn2   = makeTx(id = 2L, date = 1500L)

        val txOut1  = makeTx(id = 3L, date = 999L)

        val txOut2  = makeTx(id = 4L, date = 2001L)



        val result = filterByRange(listOf(txIn1, txIn2, txOut1, txOut2), range)

        assertEquals(2, result.size)

        assertTrue(result.contains(txIn1))

        assertTrue(result.contains(txIn2))

        assertFalse(result.contains(txOut1))

        assertFalse(result.contains(txOut2))

    }



    @Test fun B09_custom_range_real_timestamps() {

        val from = 1_700_000_000_000L

        val to   = 1_710_000_000_000L

        val range = DateRange(from, to)



        val txInside = makeTx(id = 1L, date = from + 1_000_000L)

        val txBefore = makeTx(id = 2L, date = from - 1L)

        val txAfter  = makeTx(id = 3L, date = to + 1L)



        val result = filterByRange(listOf(txInside, txBefore, txAfter), range)

        assertEquals(1, result.size)

        assertEquals(txInside, result.first())

    }



    @Test fun B10_single_point_range_matches_only_exact_timestamp() {

        val ts    = 5000L

        val range = DateRange(ts, ts)

        val txExact  = makeTx(id = 1L, date = ts)

        val txBefore = makeTx(id = 2L, date = ts - 1L)

        val txAfter  = makeTx(id = 3L, date = ts + 1L)



        val result = filterByRange(listOf(txExact, txBefore, txAfter), range)

        assertEquals(1, result.size)

        assertEquals(txExact, result.first())

    }



    @Test fun B11_ALL_range_from_zero_bypasses_filter() {

        // Verificar la condición `if (range.from > 0)`: from=0 → sin filtro

        val range = DateRange(0L, Long.MAX_VALUE)

        val txs = listOf(makeTx(1L, 100L), makeTx(2L, 200L))

        assertEquals("from=0 significa sin filtro (ALL)", txs, filterByRange(txs, range))

    }



    @Test fun B12_large_list_multiple_boundaries() {

        val range = DateRange(100L, 200L)

        val txs = (1..10).map { makeTx(id = it.toLong(), date = it.toLong() * 25) }

        // Fechas: 25, 50, 75, 100, 125, 150, 175, 200, 225, 250

        // En rango [100..200]: 100, 125, 150, 175, 200 → 5 transacciones

        val result = filterByRange(txs, range)

        assertEquals(5, result.size)

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO C: Filtrado por categoría

    // ═══════════════════════════════════════════════════════════════════════════



    @Test fun C01_null_filter_includes_all_transactions() {

        val txs = listOf(

            makeTx(id = 1L, categoryId = null),

            makeTx(id = 2L, categoryId = 5L),

            makeTx(id = 3L, categoryId = 10L)

        )

        assertEquals("Filtro null (Todas) no debe quitar ninguna tx", 3, filterByCategory(txs, null).size)

    }



    @Test fun C02_FILTER_UNCATEGORIZED_returns_only_txs_without_category() {

        val txWithCat = makeTx(id = 1L, categoryId = 5L)

        val txNoCat   = makeTx(id = 2L, categoryId = null)



        val result = filterByCategory(listOf(txWithCat, txNoCat), FILTER_UNCATEGORIZED)

        assertEquals(1, result.size)

        assertEquals(txNoCat, result.first())

    }



    @Test fun C03_specific_category_returns_only_matching_txs() {

        val catId   = 7L

        val txMatch = makeTx(id = 1L, categoryId = catId)

        val txOther = makeTx(id = 2L, categoryId = 8L)

        val txNoCat = makeTx(id = 3L, categoryId = null)



        val result = filterByCategory(listOf(txMatch, txOther, txNoCat), catId)

        assertEquals(1, result.size)

        assertEquals(txMatch, result.first())

    }



    @Test fun C04_FILTER_UNCATEGORIZED_all_categorized_returns_empty() {

        val txs = listOf(makeTx(id = 1L, categoryId = 1L), makeTx(id = 2L, categoryId = 2L))

        assertEquals(0, filterByCategory(txs, FILTER_UNCATEGORIZED).size)

    }



    @Test fun C05_FILTER_UNCATEGORIZED_all_uncategorized_returns_all() {

        val txs = listOf(makeTx(id = 1L, categoryId = null), makeTx(id = 2L, categoryId = null))

        assertEquals(2, filterByCategory(txs, FILTER_UNCATEGORIZED).size)

    }



    @Test fun C06_specific_category_no_match_returns_empty() {

        val txs = listOf(makeTx(id = 1L, categoryId = 3L), makeTx(id = 2L, categoryId = 4L))

        assertEquals(0, filterByCategory(txs, 99L).size)

    }



    @Test fun C07_specific_category_multiple_matches() {

        val catId = 5L

        val txs = listOf(

            makeTx(id = 1L, categoryId = catId),

            makeTx(id = 2L, categoryId = catId),

            makeTx(id = 3L, categoryId = 6L)

        )

        assertEquals(2, filterByCategory(txs, catId).size)

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO D: Filtrado por query de búsqueda

    // ═══════════════════════════════════════════════════════════════════════════



    @Test fun D01_empty_query_returns_all() {

        val txs = listOf(makeTx(id = 1L, concept = "Amazon"), makeTx(id = 2L, concept = "Netflix"))

        assertEquals(2, filterByQuery(txs, "").size)

    }



    @Test fun D02_blank_query_treated_as_empty() {

        val txs = listOf(makeTx(id = 1L), makeTx(id = 2L))

        assertEquals(2, filterByQuery(txs, "   ").size)

    }



    @Test fun D03_query_matches_concept_case_insensitive() {

        val tx = makeTx(concept = "AMAZON PRIME")

        assertEquals(1, filterByQuery(listOf(tx), "amazon").size)

    }



    @Test fun D04_query_matches_normalizedConcept() {

        val tx = makeTx(concept = "AMAZON PRIME")

        assertEquals(1, filterByQuery(listOf(tx), "prime").size)

    }



    @Test fun D05_query_no_match_returns_empty() {

        val tx = makeTx(concept = "Netflix")

        assertEquals(0, filterByQuery(listOf(tx), "amazon").size)

    }



    @Test fun D06_query_partial_match_concept() {

        val tx = makeTx(concept = "COMPRA EN AMAZON")

        assertEquals(1, filterByQuery(listOf(tx), "AMAZON").size)

    }



    @Test fun D07_query_uppercase_input_matches_lowercase_concept() {

        val tx = makeTx(concept = "netflix mensual")

        assertEquals(1, filterByQuery(listOf(tx), "NETFLIX").size)

    }



    @Test fun D08_query_filters_out_non_matching() {

        val txAmazon  = makeTx(id = 1L, concept = "Amazon Prime")

        val txNetflix = makeTx(id = 2L, concept = "Netflix")

        val result = filterByQuery(listOf(txAmazon, txNetflix), "amazon")

        assertEquals(1, result.size)

        assertEquals(txAmazon, result.first())

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO E: Escenarios combinados y períodos reales

    // ═══════════════════════════════════════════════════════════════════════════



    @Test fun E01_date_and_category_filter_combined() {

        val range = DateRange(1000L, 2000L)

        val catId = 5L



        val txOk         = makeTx(id = 1L, date = 1500L, categoryId = catId)

        val txWrongDate  = makeTx(id = 2L, date = 999L,  categoryId = catId)

        val txWrongCat   = makeTx(id = 3L, date = 1500L, categoryId = 99L)



        val byDate    = filterByRange(listOf(txOk, txWrongDate, txWrongCat), range)

        val byDateCat = filterByCategory(byDate, catId)



        assertEquals(1, byDateCat.size)

        assertEquals(txOk, byDateCat.first())

    }



    @Test fun E02_date_and_query_filter_combined() {

        val range = DateRange(1000L, 2000L)



        val txOk          = makeTx(id = 1L, date = 1500L, concept = "Amazon Prime")

        val txWrongDate   = makeTx(id = 2L, date = 500L,  concept = "Amazon Prime")

        val txWrongConcept= makeTx(id = 3L, date = 1500L, concept = "Netflix")



        val byDate  = filterByRange(listOf(txOk, txWrongDate, txWrongConcept), range)

        val result  = filterByQuery(byDate, "amazon")



        assertEquals(1, result.size)

        assertEquals(txOk, result.first())

    }



    @Test fun E03_all_three_filters_combined_empty_result() {

        val range = DateRange(1000L, 2000L)

        val txs = listOf(

            makeTx(id = 1L, date = 500L,  categoryId = 5L, concept = "Amazon"),  // bad date

            makeTx(id = 2L, date = 1500L, categoryId = null, concept = "Amazon"), // bad cat

            makeTx(id = 3L, date = 1500L, categoryId = 5L, concept = "Netflix")   // bad concept

        )

        val byDate   = filterByRange(txs, range)

        val byCat    = filterByCategory(byDate, 5L)

        val result   = filterByQuery(byCat, "amazon")

        assertEquals(0, result.size)

    }



    @Test fun E04_WEEK_period_filters_only_last_7_days() {

        val now   = System.currentTimeMillis()

        val range = DashboardPeriod.WEEK.toDateRange()



        val yesterday    = makeTx(id = 1L, date = now - 1 * 86_400_000L)

        val day6ago      = makeTx(id = 2L, date = now - 6 * 86_400_000L)

        val day8ago      = makeTx(id = 3L, date = now - 8 * 86_400_000L)

        val oneYearAgo   = makeTx(id = 4L, date = now - 365 * 86_400_000L)



        val result = filterByRange(listOf(yesterday, day6ago, day8ago, oneYearAgo), range)

        assertEquals(2, result.size)

        assertTrue(result.contains(yesterday))

        assertTrue(result.contains(day6ago))

        assertFalse(result.contains(day8ago))

        assertFalse(result.contains(oneYearAgo))

    }



    @Test fun E05_MONTH_period_filters_within_one_calendar_month() {

        // MONTH usa Calendar.add(MONTH, -1): el rango exacto varía según el mes actual (28-31 días).

        // Se usan los límites reales del rango en lugar de asumir exactamente 30 días.

        val range = DashboardPeriod.MONTH.toDateRange()

        val now   = System.currentTimeMillis()



        val day5ago      = makeTx(id = 1L, date = now - 5L * 86_400_000)  // siempre dentro

        val justInside   = makeTx(id = 2L, date = range.from + 1_000L)    // 1s después del límite inferior → dentro

        val justOutside  = makeTx(id = 3L, date = range.from - 1_000L)    // 1s antes del límite inferior → fuera



        val result = filterByRange(listOf(day5ago, justInside, justOutside), range)

        assertEquals(2, result.size)

        assertTrue(result.contains(day5ago))

        assertTrue(result.contains(justInside))

        assertFalse(result.contains(justOutside))

    }



    @Test fun E06_YEAR_period_filters_last_365_days() {

        val now   = System.currentTimeMillis()

        val range = DashboardPeriod.YEAR.toDateRange()



        val day100ago  = makeTx(id = 1L, date = now - 100L * 86_400_000)

        val day364ago  = makeTx(id = 2L, date = now - 364L * 86_400_000)

        val day366ago  = makeTx(id = 3L, date = now - 366L * 86_400_000)



        val result = filterByRange(listOf(day100ago, day364ago, day366ago), range)

        assertEquals(2, result.size)

        assertTrue(result.contains(day100ago))

        assertTrue(result.contains(day364ago))

        assertFalse(result.contains(day366ago))

    }



    @Test fun E07_CUSTOM_range_preserves_both_endpoints() {

        val from  = 1_700_000_000_000L

        val to    = 1_700_086_400_000L  // from + 1 día exacto

        val range = DateRange(from, to)



        val atFrom   = makeTx(id = 1L, date = from)

        val atTo     = makeTx(id = 2L, date = to)

        val before   = makeTx(id = 3L, date = from - 1L)

        val after    = makeTx(id = 4L, date = to   + 1L)



        val result = filterByRange(listOf(atFrom, atTo, before, after), range)

        assertEquals(2, result.size)

        assertTrue(result.contains(atFrom))

        assertTrue(result.contains(atTo))

    }



    @Test fun E08_income_and_expense_both_filtered_by_date() {

        val range   = DateRange(1000L, 2000L)

        val income  = makeTx(id = 1L, date = 1500L, type = "INCOME",  amount = 500.0)

        val expense = makeTx(id = 2L, date = 1500L, type = "EXPENSE", amount = -100.0)

        val old     = makeTx(id = 3L, date = 500L,  type = "INCOME",  amount = 200.0)



        val result = filterByRange(listOf(income, expense, old), range)

        assertEquals(2, result.size)

        assertTrue(result.any { it.transaction.type == "INCOME"  })

        assertTrue(result.any { it.transaction.type == "EXPENSE" })

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // Helpers: replican la lógica de filtrado del dashboardData lambda

    // ═══════════════════════════════════════════════════════════════════════════



    /** Replica: `if (range.from > 0) allTx.filter { it.transaction.date in range.from..range.to } else allTx` */

    private fun filterByRange(txs: List<TransactionWithCategory>, range: DateRange): List<TransactionWithCategory> =

        if (range.from > 0) txs.filter { it.transaction.date in range.from..range.to }

        else txs



    /** Replica el filtro de categoría del dashboardData */

    private fun filterByCategory(txs: List<TransactionWithCategory>, catId: Long?): List<TransactionWithCategory> =

        when (catId) {

            FILTER_UNCATEGORIZED -> txs.filter { it.transaction.categoryId == null }

            null                 -> txs

            else                 -> txs.filter { it.transaction.categoryId == catId }

        }



    /** Replica el filtro de búsqueda del dashboardData */

    private fun filterByQuery(txs: List<TransactionWithCategory>, query: String): List<TransactionWithCategory> =

        if (query.isNotBlank())

            txs.filter { tx ->

                tx.transaction.concept.contains(query, ignoreCase = true) ||

                tx.transaction.normalizedConcept.contains(query, ignoreCase = true)

            }

        else txs



    /** Construye una TransactionWithCategory de prueba con valores por defecto */

    private fun makeTx(

        id: Long = 1L,

        date: Long = 1000L,

        categoryId: Long? = null,

        concept: String = "Test",

        amount: Double = -10.0,

        type: String = "EXPENSE"

    ) = TransactionWithCategory(

        transaction = TransactionEntity(

            id = id,

            date = date,

            amount = amount,

            concept = concept,

            normalizedConcept = DescriptionNormalizer.normalize(concept),

            dedupKey = TestDedupKey.build(

                normalizedConcept = DescriptionNormalizer.normalize(concept),

                amount = amount,

                date = java.util.Date(date),

                hasBalance = false,

                balance = 0.0

            ),

            categoryId = categoryId,

            type = type

        ),

        categoryName = null,

        categoryColor = null

    )

}

