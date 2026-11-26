package com.jesusluna.bolsilloplus.ui.main



import com.jesusluna.bolsilloplus.TestDedupKey

import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory

import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity

import com.jesusluna.bolsilloplus.util.DescriptionNormalizer

import org.junit.Assert.*

import org.junit.Test



/**

 * Tests unitarios de la lógica de filtrado del pie chart del dashboard.

 *

 * No requieren Android ni Room. Replica la lógica de computePieData en MainViewModel

 * para verificar que:

 *   - El modo Children usa el JOIN (parentCategoryName != null) y no allCats

 *   - El modo BreakdownParent usa parentCategoryId del JOIN y no allCats

 *   - Ambos modos son correctos desde el primer emit (sin depender del orden de carga)

 *

 * Grupos:

 *   P - Modo Subcategorías (Children)

 *   Q - Modo Desglosar (BreakdownParent)

 *   R - Invariancia ante cold start (sin allCats en memoria)

 */

class PieChartComputeTest {



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO P: Modo Subcategorías (Children)

    // ═══════════════════════════════════════════════════════════════════════════



    @Test

    fun P01_children_mode_two_parents_three_children_each_returns_six_slices() {

        // Ocio (id=1) → Streaming, Restaurantes, Viajes

        // Vivienda (id=2) → Alquiler, Suministros, Internet

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = 1L

            ),

            makeTx(

                2L,

                catId = 12L,

                catName = "Restaurantes",

                parentCatName = "Ocio",

                parentCatId = 1L

            ),

            makeTx(3L, catId = 13L, catName = "Viajes", parentCatName = "Ocio", parentCatId = 1L),

            makeTx(

                4L,

                catId = 21L,

                catName = "Alquiler",

                parentCatName = "Vivienda",

                parentCatId = 2L

            ),

            makeTx(

                5L,

                catId = 22L,

                catName = "Suministros",

                parentCatName = "Vivienda",

                parentCatId = 2L

            ),

            makeTx(

                6L,

                catId = 23L,

                catName = "Internet",

                parentCatName = "Vivienda",

                parentCatId = 2L

            ),

        )



        val result = filterChildrenMode(expenses)

        assertEquals("Children mode debe devolver 6 slices para 6 subcategorías", 6, result.size)

    }



    @Test

    fun P02_children_mode_includes_all_parents_subcategories() {

        // Las hijas de AMBOS padres deben aparecer, no solo las del primero

        val ocioId = 1L

        val viviendaId = 2L

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = ocioId

            ),

            makeTx(

                2L,

                catId = 21L,

                catName = "Alquiler",

                parentCatName = "Vivienda",

                parentCatId = viviendaId

            ),

        )



        val result = filterChildrenMode(expenses)

        val names = result.map { it.categoryName }

        assertEquals("Deben aparecer 2 subcategorías de distintos padres", 2, result.size)

        assertTrue("Streaming debe aparecer", names.contains("Streaming"))

        assertTrue("Alquiler debe aparecer", names.contains("Alquiler"))

    }



    @Test

    fun P03_children_mode_excludes_root_category_transactions() {

        // Gasto asignado directamente a Ocio (raíz) NO debe aparecer en Children mode

        val txRoot =

            makeTx(1L, catId = 1L, catName = "Ocio", parentCatName = null, parentCatId = null)

        val txChild =

            makeTx(2L, catId = 11L, catName = "Streaming", parentCatName = "Ocio", parentCatId = 1L)



        val result = filterChildrenMode(listOf(txRoot, txChild))

        assertEquals("Solo debe aparecer 1 (el hijo Streaming)", 1, result.size)

        assertEquals("Streaming", result.first().categoryName)

    }



    @Test

    fun P04_children_mode_empty_when_all_expenses_on_root_categories() {

        val expenses = listOf(

            makeTx(1L, catId = 1L, catName = "Ocio", parentCatName = null, parentCatId = null),

            makeTx(2L, catId = 2L, catName = "Vivienda", parentCatName = null, parentCatId = null),

        )



        val result = filterChildrenMode(expenses)

        assertEquals("Sin subcategorías, resultado debe ser vacío", 0, result.size)

    }



    @Test

    fun P05_children_mode_excludes_uncategorized_transactions() {

        val txNoCategory =

            makeTx(1L, catId = null, catName = null, parentCatName = null, parentCatId = null)

        val txChild =

            makeTx(2L, catId = 11L, catName = "Streaming", parentCatName = "Ocio", parentCatId = 1L)



        val result = filterChildrenMode(listOf(txNoCategory, txChild))

        assertEquals("Sin categoría nunca aparece en Children mode", 1, result.size)

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO Q: Modo Desglosar (BreakdownParent)

    // ═══════════════════════════════════════════════════════════════════════════



    @Test

    fun Q01_breakdown_parent_with_children_returns_only_its_children() {

        val ocioId = 1L

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = ocioId

            ),

            makeTx(

                2L,

                catId = 12L,

                catName = "Restaurantes",

                parentCatName = "Ocio",

                parentCatId = ocioId

            ),

            makeTx(

                3L,

                catId = 21L,

                catName = "Alquiler",

                parentCatName = "Vivienda",

                parentCatId = 2L

            ),

        )



        val result = filterBreakdownParent(expenses, ocioId)

        val names = result.map { it.categoryName }

        assertEquals("BreakdownParent(Ocio) debe devolver 2 slices", 2, result.size)

        assertTrue("Streaming debe aparecer", names.contains("Streaming"))

        assertTrue("Restaurantes debe aparecer", names.contains("Restaurantes"))

        assertFalse("Alquiler (de Vivienda) no debe aparecer", names.contains("Alquiler"))

    }



    @Test

    fun Q02_breakdown_parent_without_children_returns_direct_expenses() {

        // Hogar sin hijos: los gastos están asignados directamente a él (parentCatName = null)

        val hogarId = 5L

        val expenses = listOf(

            makeTx(

                1L,

                catId = hogarId,

                catName = "Hogar",

                parentCatName = null,

                parentCatId = null,

                amount = 300.0

            ),

            makeTx(

                2L,

                catId = hogarId,

                catName = "Hogar",

                parentCatName = null,

                parentCatId = null,

                amount = 150.0

            ),

            makeTx(

                3L,

                catId = 1L,

                catName = "Ocio",

                parentCatName = null,

                parentCatId = null,

                amount = 50.0

            ),

        )



        val result = filterBreakdownParent(expenses, hogarId)

        assertEquals("Sin hijos, debe devolver gastos directos de Hogar", 2, result.size)

        assertTrue(

            "Todos los resultados deben ser de Hogar",

            result.all { it.transaction.categoryId == hogarId })

    }



    @Test

    fun Q03_breakdown_parent_not_in_data_returns_empty() {

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = 1L

            ),

        )



        val result = filterBreakdownParent(expenses, 99L)

        assertEquals("Padre inexistente o sin gastos debe devolver vacío", 0, result.size)

    }



    @Test

    fun Q04_breakdown_parent_amounts_sum_correctly() {

        val ocioId = 1L

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = ocioId,

                amount = 15.99

            ),

            makeTx(

                2L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = ocioId,

                amount = 9.99

            ),

            makeTx(

                3L,

                catId = 12L,

                catName = "Restaurantes",

                parentCatName = "Ocio",

                parentCatId = ocioId,

                amount = 45.00

            ),

        )



        val result = filterBreakdownParent(expenses, ocioId)

        val streamingTxs = result.filter { it.categoryName == "Streaming" }

        assertEquals("Streaming debe tener 2 transacciones", 2, streamingTxs.size)

        val total = streamingTxs.sumOf { kotlin.math.abs(it.transaction.amount) }

        assertEquals("Total Streaming debe ser 25.98", 25.98, total, 0.001)

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO R: Invariancia ante cold start (sin allCats en memoria)

    // ═══════════════════════════════════════════════════════════════════════════



    @Test

    fun R01_children_mode_correct_without_allCats() {

        // La lógica usa solo el JOIN (parentCategoryName != null).

        // Simulamos que se llama antes de que allCats cargue → mismo resultado.

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = 1L

            ),

            makeTx(

                2L,

                catId = 21L,

                catName = "Alquiler",

                parentCatName = "Vivienda",

                parentCatId = 2L

            ),

            makeTx(3L, catId = 1L, catName = "Ocio", parentCatName = null, parentCatId = null),

        )



        // "Cold start": llamada con allCats vacío (lógica no lo usa)

        val resultCold = filterChildrenMode(expenses)

        // "Warm": mismo resultado tras cargar allCats

        val resultWarm = filterChildrenMode(expenses)



        assertEquals(

            "Children mode idempotente sin importar orden de carga",

            resultCold.size,

            resultWarm.size

        )

        assertEquals("Siempre devuelve 2 subcategorías (Streaming + Alquiler)", 2, resultCold.size)

    }



    @Test

    fun R02_breakdown_parent_correct_without_allCats() {

        val ocioId = 1L

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = ocioId

            ),

            makeTx(

                2L,

                catId = 12L,

                catName = "Restaurantes",

                parentCatName = "Ocio",

                parentCatId = ocioId

            ),

        )



        // La lógica usa tx.parentCategoryId, que viene del JOIN; no necesita allCats.

        val resultCold = filterBreakdownParent(expenses, ocioId)

        val resultWarm = filterBreakdownParent(expenses, ocioId)



        assertEquals("BreakdownParent idempotente", resultCold.size, resultWarm.size)

        assertEquals("Siempre devuelve 2 slices (no vacío)", 2, resultCold.size)

    }



    @Test

    fun R03_breakdown_direct_parent_correct_without_allCats() {

        // Padre sin hijos (no tiene parentCategoryId en ninguna tx).

        // Antes del fix, se usaba allCats.filter { it.parentId == parentId },

        // que devolvía vacío si allCats = emptyList → caída en "sin hijos" → correcto.

        // Ahora también usa hasChildTxs = expenses.any { it.parentCategoryId == parentId }

        // que es false de forma determinista (no depende de allCats).

        val alimentacionId = 3L

        val expenses = listOf(

            makeTx(

                1L,

                catId = alimentacionId,

                catName = "Alimentación",

                parentCatName = null,

                parentCatId = null,

                amount = 80.0

            ),

            makeTx(

                2L,

                catId = alimentacionId,

                catName = "Alimentación",

                parentCatName = null,

                parentCatId = null,

                amount = 35.0

            ),

        )



        val result = filterBreakdownParent(expenses, alimentacionId)

        assertEquals("Padre sin hijos siempre muestra sus gastos directos", 2, result.size)

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO S: capWithOthers

    // ═══════════════════════════════════════════════════════════════════════════



    @Test

    fun S01_more_than_8_subcategories_produces_9_slices_with_otros() {

        // 10 subcategorías distintas → debe devolver top-8 + "Otros"

        val expenses = (1..10).map { i ->

            makeTx(

                i.toLong(), catId = i.toLong(), catName = "Cat$i",

                parentCatName = "Padre", parentCatId = 99L,

                amount = (10 * (11 - i)).toDouble()

            )  // Cat1 tiene el mayor importe

        }

        val filtered = filterChildrenMode(expenses)

        val sortedSlices = groupByName(filtered)

            .map { (name, txs) -> name to txs.sumOf { kotlin.math.abs(it.transaction.amount) } }

            .sortedByDescending { it.second }

        val result = capWithOthers(sortedSlices)



        assertEquals("Con 10 categorías debe haber 9 slices (top-8 + Otros)", 9, result.size)

        assertEquals("La última slice debe ser 'Otros'", "Otros", result.last().first)

    }



    @Test

    fun S02_otros_slice_sums_remainder_exactly() {

        val expenses = (1..10).map { i ->

            makeTx(

                i.toLong(), catId = i.toLong(), catName = "Cat$i",

                parentCatName = "Padre", parentCatId = 99L, amount = i.toDouble() * 10

            )

        }

        val filtered = filterChildrenMode(expenses)

        val sortedSlices = groupByName(filtered)

            .map { (name, txs) -> name to txs.sumOf { kotlin.math.abs(it.transaction.amount) } }

            .sortedByDescending { it.second }



        val expectedOtros = sortedSlices.drop(8).sumOf { it.second }

        val result = capWithOthers(sortedSlices)

        val otrosSlice = result.last()



        assertEquals(

            "'Otros' debe sumar exactamente el resto de categorías",

            expectedOtros,

            otrosSlice.second,

            0.001

        )

    }



    @Test

    fun S03_exactly_8_categories_no_otros_slice() {

        val expenses = (1..8).map { i ->

            makeTx(

                i.toLong(), catId = i.toLong(), catName = "Cat$i",

                parentCatName = "Padre", parentCatId = 99L

            )

        }

        val filtered = filterChildrenMode(expenses)

        val sortedSlices = groupByName(filtered)

            .map { (name, txs) -> name to txs.sumOf { kotlin.math.abs(it.transaction.amount) } }

            .sortedByDescending { it.second }

        val result = capWithOthers(sortedSlices)



        assertEquals("Con exactamente 8 categorías no debe aparecer 'Otros'", 8, result.size)

        assertFalse("No debe existir slice 'Otros'", result.any { it.first == "Otros" })

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO T: Agregación (groupBy)

    // ═══════════════════════════════════════════════════════════════════════════



    @Test

    fun T01_two_transactions_same_subcategory_aggregate_to_one_group() {

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = 1L,

                amount = 10.0

            ),

            makeTx(

                2L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = 1L,

                amount = 15.0

            ),

        )

        val filtered = filterChildrenMode(expenses)

        val grouped = groupByName(filtered)



        assertEquals("2 txs de Streaming deben agruparse en 1 entrada", 1, grouped.size)

        assertTrue("El grupo Streaming debe existir", grouped.containsKey("Streaming"))

    }



    @Test

    fun T02_same_subcategory_amounts_sum_correctly() {

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = 1L,

                amount = 10.0

            ),

            makeTx(

                2L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = 1L,

                amount = 15.99

            ),

        )

        val filtered = filterChildrenMode(expenses)

        val total = groupByName(filtered)["Streaming"]!!

            .sumOf { kotlin.math.abs(it.transaction.amount) }



        assertEquals("Total de Streaming debe ser 25.99", 25.99, total, 0.001)

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO U: Filtrado por neto (categorías con netAmount >= 0 excluidas del pie)

    // ═══════════════════════════════════════════════════════════════════════════



    @Test

    fun U01_income_only_category_excluded_because_net_is_positive() {

        // computePieData usa suma algebraica y filtra netAmount < 0.

        // Una categoría con solo ingresos tiene neto positivo → no aparece en el pie.

        val incomeChild = makeTx(

            1L, catId = 11L, catName = "Sueldo",

            parentCatName = "Ingresos", parentCatId = 99L, amount = 1000.0, type = "INCOME"

        )

        val expenseChild = makeTx(

            2L, catId = 12L, catName = "Netflix",

            parentCatName = "Ocio", parentCatId = 1L, amount = 15.0, type = "EXPENSE"

        )



        val allTxs = listOf(incomeChild, expenseChild)

        val childFiltered = filterChildrenMode(allTxs)

        val slices = netSlicesFiltered(childFiltered)



        assertEquals("Solo 1 slice (Netflix: neto negativo)", 1, slices.size)

        assertEquals("Netflix", slices.first().first)

    }



    @Test

    fun U02_only_income_transactions_returns_empty_after_net_filter() {

        val incomeTxs = (1..3).map { i ->

            makeTx(

                i.toLong(), catId = i.toLong(), catName = "Ingreso$i",

                parentCatName = "Ingresos", parentCatId = 99L, amount = 100.0, type = "INCOME"

            )

        }

        val childFiltered = filterChildrenMode(incomeTxs)

        val slices = netSlicesFiltered(childFiltered)



        assertEquals("Solo ingresos → neto positivo → pie vacío", 0, slices.size)

    }



    @Test

    fun U03_mixed_category_shows_net_expense_when_expense_dominates() {

        // -50€ cena + +20€ bizum vuelta = neto -30€ → aparece como 30€ en el pie

        val cena = makeTx(

            1L, catId = 11L, catName = "Restaurantes",

            parentCatName = "Ocio", parentCatId = 1L, amount = 50.0, type = "EXPENSE"

        )

        val bizum = makeTx(

            2L, catId = 11L, catName = "Restaurantes",

            parentCatName = "Ocio", parentCatId = 1L, amount = 20.0, type = "INCOME"

        )



        val childFiltered = filterChildrenMode(listOf(cena, bizum))

        val slices = netSlicesFiltered(childFiltered)



        assertEquals("Restaurantes aparece (neto negativo)", 1, slices.size)

        // neto = -50 + 20 = -30; la función devuelve el neto como doble negativo

        assertEquals(-30.0, slices.first().second, 0.01)

    }



    @Test

    fun U04_mixed_category_excluded_when_income_dominates() {

        // +80€ transferencia + -50€ gasto = neto +30€ → NO aparece en el pie

        val transferencia = makeTx(

            1L, catId = 11L, catName = "Transferencias",

            parentCatName = "Varios", parentCatId = 1L, amount = 80.0, type = "INCOME"

        )

        val gasto = makeTx(

            2L, catId = 11L, catName = "Transferencias",

            parentCatName = "Varios", parentCatId = 1L, amount = 50.0, type = "EXPENSE"

        )



        val childFiltered = filterChildrenMode(listOf(transferencia, gasto))

        val slices = netSlicesFiltered(childFiltered)



        assertEquals("Neto positivo → no aparece en el pie", 0, slices.size)

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // GRUPO V: Escenario mixto en BreakdownParent

    // ═══════════════════════════════════════════════════════════════════════════



    @Test

    fun V01_breakdown_ignores_other_parents_root_and_uncategorized() {

        val ocioId = 1L

        val viviendaId = 2L

        val expenses = listOf(

            makeTx(

                1L,

                catId = 11L,

                catName = "Streaming",

                parentCatName = "Ocio",

                parentCatId = ocioId

            ),

            makeTx(

                2L,

                catId = 21L,

                catName = "Alquiler",

                parentCatName = "Vivienda",

                parentCatId = viviendaId

            ),

            makeTx(

                3L,

                catId = null,

                catName = null,

                parentCatName = null,

                parentCatId = null

            ),   // sin cat

            makeTx(

                4L,

                catId = ocioId,

                catName = "Ocio",

                parentCatName = null,

                parentCatId = null

            ),   // raíz Ocio

        )

        val result = filterBreakdownParent(expenses, ocioId)

        val names = result.map { it.categoryName }



        assertEquals("Solo Streaming (hijo de Ocio)", 1, result.size)

        assertTrue("Streaming debe estar", names.contains("Streaming"))

        assertFalse("Alquiler no debe estar (es hijo de Vivienda)", names.contains("Alquiler"))

        assertFalse("Ocio directo no debe estar cuando tiene hijos", names.contains("Ocio"))

    }



    @Test

    fun V02_breakdown_fallback_to_direct_when_no_children_with_expenses() {

        // Padre tiene hijos en la BD (parentCatId = parentId), pero con amount=0 → no hay transacciones

        // Solo hay gastos directos asignados al padre → debe usar el fallback de gastos directos

        val alimentacionId = 3L

        val expenses = listOf(

            makeTx(

                1L, catId = alimentacionId, catName = "Alimentación",

                parentCatName = null, parentCatId = null, amount = 80.0

            ),

        )

        // Sin transacciones con parentCategoryId == alimentacionId, hasChildTxs = false

        val result = filterBreakdownParent(expenses, alimentacionId)



        assertEquals("Fallback: debe devolver 1 tx directa de Alimentación", 1, result.size)

        assertEquals("Alimentación", result.first().categoryName)

    }



    // ═══════════════════════════════════════════════════════════════════════════

    // Helpers: replican la lógica de computePieData del ViewModel

    // ═══════════════════════════════════════════════════════════════════════════



    /**

     * Replica el filtro del modo Children (Subcategorías):

     * incluye solo txs cuya categoría tiene padre (parentCategoryName != null en el JOIN).

     * Nota: el groupBy y capWithOthers son lógica adicional que no se prueba aquí.

     */

    private fun filterChildrenMode(

        expenses: List<TransactionWithCategory>

    ): List<TransactionWithCategory> =

        expenses.filter { it.parentCategoryName != null && it.categoryName != null }



    /**

     * Replica el filtro del modo BreakdownParent (Desglosar):

     * si el padre tiene hijos con gastos → solo hijos; si no → gastos directos del padre.

     */

    private fun filterBreakdownParent(

        expenses: List<TransactionWithCategory>,

        parentId: Long

    ): List<TransactionWithCategory> {

        val hasChildTxs = expenses.any { it.parentCategoryId == parentId }

        return if (!hasChildTxs) {

            expenses.filter { it.transaction.categoryId == parentId && it.categoryName != null }

        } else {

            expenses.filter { it.parentCategoryId == parentId && it.categoryName != null }

        }

    }



    /** Agrupa las transacciones ya filtradas por nombre de categoría. */

    private fun groupByName(

        filtered: List<TransactionWithCategory>

    ): Map<String, List<TransactionWithCategory>> =

        filtered.groupBy { it.categoryName!! }



    /**

     * Replica el paso de aggregación + filtro neto de computePieData:

     * agrupa por nombre de categoría, calcula el neto algebraico de cada grupo

     * y devuelve solo los grupos con neto < 0 (gastos netos reales).

     * Retorna pares (nombre, netAmount) donde netAmount es negativo.

     */

    private fun netSlicesFiltered(

        filtered: List<TransactionWithCategory>

    ): List<Pair<String, Double>> =

        filtered

            .groupBy { it.categoryName ?: "Sin categoría" }

            .map { (name, txs) -> name to txs.sumOf { it.transaction.amount } }

            .filter { it.second < 0 }



    /**

     * Replica capWithOthers de MainViewModel:

     * si hay más de [limit] slices, recorta y añade "Otros" con la suma del resto.

     */

    private fun capWithOthers(

        sorted: List<Pair<String, Double>>,

        limit: Int = 8

    ): List<Pair<String, Double>> {

        if (sorted.size <= limit) return sorted

        val top = sorted.take(limit)

        val othersAmount = sorted.drop(limit).sumOf { it.second }

        return top + listOf("Otros" to othersAmount)

    }



    /**

     * Replica makeTx de MainViewModel:

     * crea una transacción con categoría y padre.

     */

    private fun makeTx(

        id: Long = 1L,

        catId: Long? = null,

        catName: String? = null,

        parentCatName: String? = null,

        parentCatId: Long? = null,

        amount: Double = 10.0,

        type: String = "EXPENSE"

    ) = TransactionWithCategory(

        transaction = TransactionEntity(

            id = id,

            date = 1_700_000_000_000L,

            amount = if (type == "INCOME") kotlin.math.abs(amount) else -kotlin.math.abs(amount),

            concept = catName ?: "Test",

            normalizedConcept = DescriptionNormalizer.normalize(catName ?: "test"),

            dedupKey = TestDedupKey.buildFromConcept(

                concept = catName ?: "test",

                amount = if (type == "INCOME") kotlin.math.abs(amount) else -kotlin.math.abs(amount),

                dateMs = 1_700_000_000_000L,

                hasBalance = false,

                balance = 0.0

            ),

            categoryId = catId,

            type = type

        ),

        categoryName = catName,

        categoryColor = null,

        parentCategoryName = parentCatName,

        parentCategoryId = parentCatId

    )

}