package com.jesusluna.bolsilloplus.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jesusluna.bolsilloplus.data.db.AppDatabase
import com.jesusluna.bolsilloplus.data.db.dao.CategoryRuleDao
import com.jesusluna.bolsilloplus.data.db.dao.TransactionDao
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests del flujo "cambiar regla ganadora" (CategoryDetailBottomSheet).
 *
 * Flujo:
 *  1. Tx categorizada por Regla A (ganadora).
 *  2. Usuario ve conflicto → elige Regla B como nueva ganadora.
 *  3. UI llama: categoryRuleDao.updatePriority(ruleBId, ruleB.priority + 10)
 *  4. Usuario hace "Escanear cobros anteriores" → applyRulesToAll()
 *  5. ¿La tx ahora tiene la categoría de Regla B?
 *
 * Scoring real (DescriptionNormalizer.findAllMatchingRules):
 *   Orden: priority DESC → typeOrdinal DESC → patternLength DESC
 *   priority es el criterio PRIMARIO; un bump +10 siempre es suficiente
 *   para cambiar el ganador, independientemente del tipo o longitud.
 *
 * Grupos:
 *  D1 - Bump funciona: mismo tipo, misma longitud de patrón
 *  D2 - Bump funciona: tipos distintos (CONTAINS vs CONTAINS_WORD)
 *  D3 - Bump funciona: longitud de patrón distinta (mismo tipo)
 *  D4 - Verificar que assignedByRuleId se actualiza tras cambio de ganador
 *  D5 - Alternativa: deshabilitar regla ganadora
 *  D6 - Alternativa: bump grande (correcta cuando el gap de priority inicial es grande)
 *  D7 - Rescan sin reglas coincidentes no borra categoría existente
 *  D8 - Rescan actualiza múltiples txs del mismo concepto
 *  D9 - Rescan recalcula candidateRuleCount correctamente
 */
@RunWith(AndroidJUnit4::class)
class WinnerRuleChangeRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var txDao: TransactionDao
    private lateinit var ruleDao: CategoryRuleDao

    private var catCompras       = 0L
    private var catSuscripciones = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        txDao   = db.transactionDao()
        ruleDao = db.categoryRuleDao()

        catCompras       = db.categoryDao().insert(CategoryEntity(name = "Compras",       color = "#E91E63"))
        catSuscripciones = db.categoryDao().insert(CategoryEntity(name = "Suscripciones", color = "#9C27B0"))
    }

    @After
    fun tearDown() { db.close() }

    // ═══════════════════════════════════════════════════════════════════════════
    // D1 — Caso que SÍ funciona: mismo tipo, misma longitud de patrón
    //      El bump +10 es suficiente para romper el empate
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D01_winnerChange_works_when_sameType_sameLength() = runBlocking {
        // Dos CONTAINS_WORD con patrones de IGUAL longitud (5) pero distintos:
        // Regla A: CONTAINS_WORD "prime" (len=5) priority=50 → score = 10000+500+50 = 10550
        // Regla B: CONTAINS_WORD "video" (len=5) priority=45 → score = 10000+500+45 = 10545
        // Concepto "amazon prime video" coincide con ambas reglas.
        val idA = insertRule("prime", MatchType.CONTAINS_WORD, catCompras,       priority = 50)
        val idB = insertRule("video", MatchType.CONTAINS_WORD, catSuscripciones, priority = 45)

        val txId = insertTx("amazon prime video", id = 100L)

        // Rescan inicial: Regla A gana (10550 > 10545)
        rescan()
        assertEquals("Regla A (prime, p=50) debe ganar inicialmente", catCompras, getById(txId).categoryId)

        // Usuario hace "Cambiar regla ganadora" → bump B +10 → B score = 10555 > 10550
        ruleDao.updatePriority(idB, 55)

        // Rescan posterior
        rescan()

        assertEquals(
            "D01: Con mismo tipo y longitud, bump +10 debe cambiar el ganador a catSuscripciones",
            catSuscripciones, getById(txId).categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D2 — Bump funciona aunque los tipos sean distintos (CONTAINS vs CONTAINS_WORD)
    //      priority es el criterio primario; typeOrdinal solo desempata con mismo priority.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D02_winnerChange_works_across_different_matchTypes() = runBlocking {
        // A: CONTAINS "amazon prime" (priority=50) → gana inicialmente sobre B (priority=45)
        // B: CONTAINS_WORD "amazon"  (priority=45) → bump → priority=55
        // Tras bump: B(55) > A(50) en priority → B gana, sin importar el tipo.
        val idA = insertRule("amazon prime",  MatchType.CONTAINS,       catCompras,       priority = 50)
        val idB = insertRule("amazon",        MatchType.CONTAINS_WORD,  catSuscripciones, priority = 45)

        val txId = insertTx("amazon prime", id = 200L)
        rescan()
        assertEquals("A (CONTAINS, prio=50) debe ganar inicialmente sobre B (prio=45)",
            catCompras, getById(txId).categoryId)

        ruleDao.updatePriority(idB, 55)
        rescan()

        assertEquals(
            "D02: Bump +10 CAMBIA el ganador — priority es el criterio principal, typeOrdinal solo desempata",
            catSuscripciones, getById(txId).categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D3 — Bump funciona aunque B tenga patrón más corto que A
    //      patternLength es criterio TERCIARIO (solo desempata cuando priority Y tipo son iguales).
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D03_winnerChange_works_when_different_patternLength() = runBlocking {
        // A: CONTAINS "amazon prime" (len=12, prio=50) — con mismo tipo y priority A gana por patternLength
        // B: CONTAINS "amazon"       (len=6,  prio=50) → bump → prio=60
        // Tras bump: B(60) > A(50) en priority → B gana aunque su patrón sea más corto.
        val idA = insertRule("amazon prime", MatchType.CONTAINS, catCompras,       priority = 50)
        val idB = insertRule("amazon",       MatchType.CONTAINS, catSuscripciones, priority = 50)

        val txId = insertTx("amazon prime", id = 300L)
        rescan()
        // Empate de priority y tipo → desempate por longitud: A(len=12) > B(len=6) → A gana
        assertEquals("Con mismo tipo y priority, A gana por tener patrón más largo",
            catCompras, getById(txId).categoryId)

        ruleDao.updatePriority(idB, 60)
        rescan()

        assertEquals(
            "D03: Bump +10 CAMBIA el ganador — B(prio=60) supera a A(prio=50) aunque B tenga patrón más corto",
            catSuscripciones, getById(txId).categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D4 — assignedByRuleId se actualiza tras cambio de ganador exitoso
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D04_afterSuccessfulWinnerChange_assignedByRuleId_updated() = runBlocking {
        // Caso que funciona: mismo tipo CONTAINS + misma longitud (5)
        // Regla A: CONTAINS "prime" (len=5) priority=50 → score = 20550
        // Regla B: CONTAINS "video" (len=5) priority=45 → score = 20545
        // Concepto "amazon prime video" coincide con ambas.
        val idA = insertRule("prime", MatchType.CONTAINS, catCompras,       priority = 50)
        val idB = insertRule("video", MatchType.CONTAINS, catSuscripciones, priority = 45)
        val txId = insertTx("amazon prime video", id = 400L)

        rescan()
        assertEquals("assignedByRuleId inicial apunta a regla A", idA, getById(txId).assignedByRuleId)
        assertEquals(catCompras, getById(txId).categoryId)

        ruleDao.updatePriority(idB, 55) // B sube → score = 20555 > 20550
        rescan()

        assertEquals(
            "assignedByRuleId debe actualizarse al id de la nueva regla ganadora (B)",
            idB, getById(txId).assignedByRuleId
        )
        assertEquals(catSuscripciones, getById(txId).categoryId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D5 — Solución alternativa al bug: deshabilitar la regla ganadora
    //      Si el usuario desactiva Regla A, Regla B gana automáticamente
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D05_workaround_disablingWinnerRule_lets_other_rule_win() = runBlocking {
        val idA = insertRule("amazon prime",  MatchType.CONTAINS,      catCompras,       priority = 50)
        val idB = insertRule("amazon",        MatchType.CONTAINS_WORD, catSuscripciones, priority = 45)

        val txId = insertTx("amazon prime", id = 500L)
        rescan()
        assertEquals("A gana inicialmente", catCompras, getById(txId).categoryId)

        // Workaround: desactivar regla A en lugar de subir prioridad de B
        ruleDao.updateEnabled(idA, false)
        rescan()

        assertEquals(
            "D05: Deshabilitar la regla ganadora permite que B gane",
            catSuscripciones, getById(txId).categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D6 — Bump grande funciona cuando el gap inicial de priority es grande
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D06_workaround_massivePriorityBump_overcomes_typeBase_gap() = runBlocking {
        // A: CONTAINS "amazon prime"  (priority=50)
        // B: CONTAINS_WORD "amazon"   (priority=45) → necesita superar 50 → basta con 51+
        // Se usa 10_651 para ilustrar un bump muy grande; cualquier valor > 50 funcionaría.
        val idA = insertRule("amazon prime",  MatchType.CONTAINS,      catCompras,       priority = 50)
        val idB = insertRule("amazon",        MatchType.CONTAINS_WORD, catSuscripciones, priority = 45)

        val txId = insertTx("amazon prime", id = 600L)
        rescan()
        assertEquals(catCompras, getById(txId).categoryId)

        // Bump suficiente
        ruleDao.updatePriority(idB, 10_651)
        rescan()

        assertEquals(
            "D06: Con prioridad suficientemente alta, CONTAINS_WORD puede superar a CONTAINS",
            catSuscripciones, getById(txId).categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D7 — Rescan sin reglas coincidentes no borra la categoría existente
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D07_rescan_noMatchingRules_preserves_existing_category() = runBlocking {
        val txId = insertTx("pago facturagas", id = 700L)
        // Categorizar con regla, luego eliminar la regla
        val ruleId = insertRule("facturagas", MatchType.CONTAINS, catCompras)
        rescan()
        assertEquals(catCompras, getById(txId).categoryId)

        ruleDao.delete(ruleId)
        rescan() // Sin reglas que coincidan

        assertEquals(
            "D07: Rescan sin reglas coincidentes NO debe borrar la categoría preexistente",
            catCompras, getById(txId).categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D8 — Rescan afecta a múltiples txs del mismo concepto
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D08_rescan_updates_multiple_txs_with_same_concept() = runBlocking {
        // Regla A: CONTAINS "prime" (len=5) priority=50 → score = 20550
        // Regla B: CONTAINS "video" (len=5) priority=45 → score = 20545
        // tx1 y tx2 contienen ambas palabras; tx3 no coincide con ninguna.
        val idA = insertRule("prime", MatchType.CONTAINS, catCompras,       priority = 50)
        val idB = insertRule("video", MatchType.CONTAINS, catSuscripciones, priority = 45)

        val tx1 = insertTx("amazon prime video",  id = 800L)
        val tx2 = insertTx("prime video series",  id = 801L)
        val tx3 = insertTx("netflix",             id = 802L) // no contiene ni "prime" ni "video"

        rescan()
        assertEquals("Tx1 inicial: catCompras (A gana)", catCompras, getById(tx1).categoryId)
        assertEquals("Tx2 inicial: catCompras (A gana)", catCompras, getById(tx2).categoryId)
        assertNull("Tx3 no coincide con ninguna regla", getById(tx3).categoryId)

        // Cambiar ganador: B sube → score = 20555 > 20550
        ruleDao.updatePriority(idB, 55)
        rescan()

        assertEquals("Tx1 debe cambiar a catSuscripciones (B gana)", catSuscripciones, getById(tx1).categoryId)
        assertEquals("Tx2 debe cambiar a catSuscripciones (B gana)", catSuscripciones, getById(tx2).categoryId)
        assertNull("Tx3 sigue sin categoría", getById(tx3).categoryId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D9 — BUG: candidateRuleCount persiste de rescan anterior aunque haya cambiado
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D09_rescan_updates_candidateRuleCount_correctly() = runBlocking {
        val idA = insertRule("amazon",       MatchType.CONTAINS_WORD, catCompras,       priority = 45)
        val idB = insertRule("prime",        MatchType.CONTAINS_WORD, catSuscripciones, priority = 45)
        val idC = insertRule("amazon prime", MatchType.CONTAINS,      catSuscripciones, priority = 55)

        val txId = insertTx("amazon prime", id = 900L)
        rescan()
        assertEquals("3 reglas coinciden → candidateRuleCount=3", 3, getById(txId).candidateRuleCount)

        // Deshabilitar dos reglas
        ruleDao.updateEnabled(idA, false)
        ruleDao.updateEnabled(idB, false)

        rescan()
        assertEquals(
            "Tras deshabilitar 2 reglas, candidateRuleCount debe ser 1",
            1, getById(txId).candidateRuleCount
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun norm(text: String) = DescriptionNormalizer.normalize(text)

    private suspend fun insertRule(
        pattern: String,
        type: MatchType,
        catId: Long,
        priority: Int = type.defaultPriority,
        enabled: Boolean = true
    ): Long {
        val rule = CategoryRuleEntity(
            normalizedPattern = norm(pattern),
            categoryId = catId,
            matchType = type,
            priority = priority,
            isEnabled = enabled
        )
        ruleDao.upsert(rule)
        return ruleDao.findByPatternAndType(norm(pattern), type)!!.id
    }

    private suspend fun insertTx(concept: String, id: Long): Long {
        txDao.upsertAll(listOf(
            TransactionEntity(
                id = id,
                date = System.currentTimeMillis(),
                amount = 10.0,
                concept = concept,
                normalizedConcept = norm(concept),
                dedupKey = "${norm(concept)}_$id",
                categoryId = null,
                type = "EXPENSE",
                isManual = false
            )
        ))
        return id
    }

    private suspend fun getById(id: Long): TransactionEntity =
        txDao.getAllWithCategoryOnce().first { it.transaction.id == id }.transaction

    private suspend fun rescan() {
        val toProcess = txDao.getAllNonManual()
        toProcess.forEach { tx ->
            val matchingRules = DescriptionNormalizer.findAllMatchingRules(tx.normalizedConcept, ruleDao)
            if (matchingRules.isNotEmpty()) {
                val winner = matchingRules.first()
                txDao.updateCategoryWithRuleAndCount(tx.id, winner.categoryId, winner.id, matchingRules.size)
            }
        }
    }
}
