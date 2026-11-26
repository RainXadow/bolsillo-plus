package com.jesusluna.bolsilloplus.ui.statistics

import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests unitarios de [StatisticsComputer] (funciones puras, sin Android).
 *
 * Grupos:
 *  N - computeNetEvolution (agrupación por día/semana/mes + orden)
 *  O - computeStackedBars (roll-up por categoría padre)
 *  P - computeTopMerchants (ranking y limit)
 */
class StatisticsComputerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun tx(
        id: Long,
        dateMs: Long,
        amount: Double,
        type: String,
        categoryId: Long? = null,
        concept: String = "test"
    ) = TransactionWithCategory(
        transaction = TransactionEntity(
            id = id,
            date = dateMs,
            amount = amount,
            concept = concept,
            normalizedConcept = concept.lowercase(),
            categoryId = categoryId,
            source = "test",
            type = type,
            balance = 0.0,
            hasBalance = false,
            dedupKey = "$id",
            isManual = false,
            assignedByRuleId = null,
            candidateRuleCount = 0
        ),
        categoryName = null,
        categoryColor = null
    )

    private fun cat(id: Long, name: String, parentId: Long? = null) = CategoryEntity(
        id = id,
        name = name,
        color = "#FF0000",
        isDefault = false,
        parentId = parentId
    )

    // Día base: 2026-01-01 00:00:00 UTC
    private val DAY0 = 1735689600_000L

    /** Añade [n] días completos al timestamp base. */
    private fun day(n: Int) = DAY0 + n * 86_400_000L

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO N: computeNetEvolution
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun N01_empty_list_returns_empty() {
        assertEquals(emptyList<NetPoint>(), StatisticsComputer.computeNetEvolution(emptyList()))
    }

    @Test fun N02_same_day_grouped_into_one_bucket() {
        // 2 txs el mismo día → 1 bucket
        val txs = listOf(
            tx(1, day(0), 100.0,  "INCOME"),
            tx(2, day(0), -50.0,  "EXPENSE")
        )
        val result = StatisticsComputer.computeNetEvolution(txs)
        assertEquals(1, result.size)
        // net = income - |expense| = 100 - 50 = 50
        assertEquals(50.0, result[0].net, 0.01)
    }

    @Test fun N03_two_days_apart_less_than_31_grouped_by_day() {
        // rangeDays=2 → modo "day" → 2 buckets separados
        val txs = listOf(
            tx(1, day(0), 100.0, "INCOME"),
            tx(2, day(1), -30.0, "EXPENSE")
        )
        val result = StatisticsComputer.computeNetEvolution(txs)
        assertEquals(2, result.size)
        assertEquals(100.0, result[0].net, 0.01)   // día 0: solo ingreso
        assertEquals(-30.0, result[1].net, 0.01)   // día 1: solo gasto
    }

    @Test fun N04_range_over_180_days_grouped_by_month() {
        // rangeDays > 180 → modo "month"
        // Enero: ingreso 200, gasto 50 → net +150
        // Julio (181 días después ≈ 6 meses): gasto 80 → net -80
        val txs = listOf(
            tx(1, day(0), 200.0, "INCOME"),
            tx(2, day(0), -50.0, "EXPENSE"),
            tx(3, day(181), -80.0, "EXPENSE")
        )
        val result = StatisticsComputer.computeNetEvolution(txs)
        // Al menos 2 buckets; el primero tiene net positivo, el último negativo
        assertTrue(result.size >= 2)
        assertTrue("Primer bucket debe ser positivo", result.first().net > 0)
        assertTrue("Último bucket debe ser negativo", result.last().net < 0)
    }

    @Test fun N05_buckets_ordered_chronologically() {
        // 3 días diferentes en orden invertido en la lista → resultado ordenado
        val txs = listOf(
            tx(3, day(2), -10.0, "EXPENSE"),
            tx(1, day(0), -5.0,  "EXPENSE"),
            tx(2, day(1), -8.0,  "EXPENSE")
        )
        val result = StatisticsComputer.computeNetEvolution(txs)
        assertEquals(3, result.size)
        assertEquals(-5.0, result[0].net, 0.01)   // día 0
        assertEquals(-8.0, result[1].net, 0.01)   // día 1
        assertEquals(-10.0, result[2].net, 0.01)  // día 2
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO O: computeStackedBars
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun O01_empty_returns_empty() {
        assertEquals(
            emptyList<StackedBarEntry>(),
            StatisticsComputer.computeStackedBars(emptyList(), emptyList(), emptyMap())
        )
    }

    @Test fun O02_single_period_single_category() {
        val catAlim = cat(1L, "Alimentación")
        val txs = listOf(
            tx(1, day(0), -30.0, "EXPENSE", categoryId = 1L),
            tx(2, day(0), -20.0, "EXPENSE", categoryId = 1L)
        )
        val result = StatisticsComputer.computeStackedBars(
            expenseTxs = txs,
            rootCats = listOf(catAlim),
            childsByParent = emptyMap()
        )
        assertEquals(1, result.size)
        assertEquals(50.0, result[0].categoryAmounts["Alimentación"] ?: 0.0, 0.01)
    }

    @Test fun O03_child_category_rolls_up_to_parent() {
        val parent = cat(1L, "Compras")
        val child  = cat(2L, "Amazon", parentId = 1L)
        // tx categorizada en el hijo (id=2)
        val txs = listOf(tx(1, day(0), -100.0, "EXPENSE", categoryId = 2L))
        val result = StatisticsComputer.computeStackedBars(
            expenseTxs = txs,
            rootCats = listOf(parent),
            childsByParent = mapOf(1L to listOf(child))
        )
        assertEquals(1, result.size)
        // Roll-up: hijo → padre "Compras"
        assertEquals(100.0, result[0].categoryAmounts["Compras"] ?: 0.0, 0.01)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO P: computeTopMerchants
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun P01_empty_returns_empty() {
        assertEquals(emptyList<MerchantStat>(), StatisticsComputer.computeTopMerchants(emptyList()))
    }

    @Test fun P02_sorted_by_amount_descending() {
        val txs = listOf(
            tx(1, day(0), -10.0, "EXPENSE", concept = "netflix"),
            tx(2, day(0), -50.0, "EXPENSE", concept = "amazon"),
            tx(3, day(0), -5.0,  "EXPENSE", concept = "spotify")
        )
        val result = StatisticsComputer.computeTopMerchants(txs)
        assertEquals("amazon", result[0].concept)
        assertEquals("netflix", result[1].concept)
        assertEquals("spotify", result[2].concept)
    }

    @Test fun P03_groups_same_concept_and_sums_amount() {
        val txs = listOf(
            tx(1, day(0), -30.0, "EXPENSE", concept = "mercadona"),
            tx(2, day(1), -20.0, "EXPENSE", concept = "mercadona"),
            tx(3, day(0), -100.0, "EXPENSE", concept = "amazon")
        )
        val result = StatisticsComputer.computeTopMerchants(txs)
        // amazon (100) > mercadona (50)
        assertEquals("amazon", result[0].concept)
        val mercadona = result.first { it.concept == "mercadona" }
        assertEquals(50.0, mercadona.amount, 0.01)
        assertEquals(2, mercadona.count)
    }

    @Test fun P04_respects_limit() {
        val txs = (1..20).map { i ->
            tx(i.toLong(), day(0), -i.toDouble(), "EXPENSE", concept = "merchant$i")
        }
        val result = StatisticsComputer.computeTopMerchants(txs, limit = 5)
        assertEquals(5, result.size)
    }
}
