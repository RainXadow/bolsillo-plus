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
import com.jesusluna.bolsilloplus.data.repository.CategorizationScope
import com.jesusluna.bolsilloplus.data.repository.TransactionRepository
import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests de integración Room para los 4 scopes de categorización:
 *  - OnlyThis:      solo este movimiento, sin crear regla
 *  - ExactName:     regla EXACT + bulk por concepto exacto
 *  - ContainsPhrase: regla CONTAINS + bulk por frase
 *  - ContainsWord:  regla CONTAINS_WORD + bulk por palabra completa
 *
 * También verifica comportamientos de upsert (sin duplicados de reglas).
 */
@RunWith(AndroidJUnit4::class)
class ScopeCategorizationRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var txDao: TransactionDao
    private lateinit var ruleDao: CategoryRuleDao
    private lateinit var repo: TransactionRepository

    private var catA = 0L
    private var catB = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        txDao   = db.transactionDao()
        ruleDao = db.categoryRuleDao()
        repo    = TransactionRepository(txDao, ruleDao, db.importExceptionDao(), db.skippedTransactionDao(), db.categoryDao())

        catA = db.categoryDao().insert(CategoryEntity(name = "CatA", color = "#2196F3"))
        catB = db.categoryDao().insert(CategoryEntity(name = "CatB", color = "#E91E63"))
    }

    @After
    fun tearDown() { db.close() }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCOPE: OnlyThis
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun C01_onlyThis_changes_only_target_transaction() = runBlocking {
        val id1 = insertTx("amazon prime", id = 1001L)
        val id2 = insertTx("amazon prime", id = 1002L) // mismo concepto

        repo.categorizeWithScope(id1, norm("amazon prime"), catA, CategorizationScope.OnlyThis)

        val tx1 = getById(id1)
        val tx2 = getById(id2)
        assertEquals("OnlyThis debe cambiar el tx objetivo", catA, tx1.categoryId)
        assertNull("OnlyThis NO debe cambiar otros txs con el mismo concepto", tx2.categoryId)
    }

    @Test fun C02_onlyThis_does_not_create_rule() = runBlocking {
        val id = insertTx("amazon prime", id = 1003L)
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.OnlyThis)
        assertEquals("OnlyThis no debe crear ninguna regla", 0, ruleDao.count())
    }

    @Test fun C03_onlyThis_sets_isManual_true() = runBlocking {
        val id = insertTx("amazon prime", id = 1004L)
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.OnlyThis)
        assertTrue("OnlyThis debe marcar isManual=true", getById(id).isManual)
    }

    @Test fun C04_onlyThis_clears_assignedByRuleId() = runBlocking {
        // Tx con ruleId preexistente
        val ruleId = insertRule("amazon", MatchType.CONTAINS, catB)
        val id = insertTxWithRule("amazon prime", 1005L, catB, ruleId)

        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.OnlyThis)

        val tx = getById(id)
        assertNull("OnlyThis debe borrar assignedByRuleId", tx.assignedByRuleId)
        assertEquals(0, tx.candidateRuleCount)
    }

    @Test fun C05_onlyThis_target_survives_rescan() = runBlocking {
        val id = insertTx("amazon prime", id = 1006L)
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.OnlyThis)

        // Insertar regla diferente y hacer rescan
        insertRule("amazon", MatchType.CONTAINS, catB)
        rescan()

        val tx = getById(id)
        assertEquals(
            "OnlyThis (isManual=true) debe sobrevivir al rescan sin cambiar categoría",
            catA, tx.categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCOPE: ExactName
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun C10_exactName_changes_all_txs_with_same_concept() = runBlocking {
        val id1 = insertTx("amazon prime", id = 2001L)
        val id2 = insertTx("amazon prime", id = 2002L)
        val id3 = insertTx("netflix",      id = 2003L) // diferente concepto

        repo.categorizeWithScope(id1, norm("amazon prime"), catA, CategorizationScope.ExactName)

        assertEquals(catA, getById(id1).categoryId)
        assertEquals(catA, getById(id2).categoryId)
        assertNull("Tx con concepto diferente no debe ser afectada", getById(id3).categoryId)
    }

    @Test fun C11_exactName_creates_EXACT_rule() = runBlocking {
        val id = insertTx("amazon prime", id = 2004L)
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.ExactName)

        val rule = ruleDao.findByPatternAndType(norm("amazon prime"), MatchType.EXACT)
        assertNotNull("ExactName debe crear una regla EXACT", rule)
        assertEquals(catA, rule!!.categoryId)
    }

    @Test fun C12_exactName_trigger_tx_isManual_true() = runBlocking {
        val id = insertTx("amazon prime", id = 2005L)
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.ExactName)
        assertTrue("El tx disparador debe tener isManual=true", getById(id).isManual)
    }

    @Test fun C13_exactName_bulk_txs_isManual_stays_false() = runBlocking {
        // tx2 es el bulk (mismo concepto pero diferente id)
        val id1 = insertTx("amazon prime", id = 2006L)
        val id2 = insertTx("amazon prime", id = 2007L)

        repo.categorizeWithScope(id1, norm("amazon prime"), catA, CategorizationScope.ExactName)

        assertFalse(
            "Txs actualizadas en bulk por ExactName deben mantener isManual=false",
            getById(id2).isManual
        )
    }

    @Test fun C14_exactName_upsert_no_duplicate_rule() = runBlocking {
        val id1 = insertTx("amazon prime", id = 2008L)
        val id2 = insertTx("amazon prime", id = 2009L)

        // Aplicar scope dos veces
        repo.categorizeWithScope(id1, norm("amazon prime"), catA, CategorizationScope.ExactName)
        repo.categorizeWithScope(id2, norm("amazon prime"), catA, CategorizationScope.ExactName)

        assertEquals("No debe haber reglas duplicadas tras doble upsert", 1, ruleDao.count())
    }

    @Test fun C15_exactName_upsert_updates_category_of_existing_rule() = runBlocking {
        // Regla preexistente → Suscripciones
        ruleDao.upsert(CategoryRuleEntity(
            normalizedPattern = norm("amazon prime"),
            categoryId = catB,
            matchType = MatchType.EXACT,
            priority = 100
        ))

        val id = insertTx("amazon prime", id = 2010L)
        // Corregir a catA
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.ExactName)

        val rule = ruleDao.findByPatternAndType(norm("amazon prime"), MatchType.EXACT)
        assertEquals("La regla existente debe actualizarse a la nueva categoría", catA, rule!!.categoryId)
        assertEquals("Solo debe existir 1 regla EXACT para este patrón", 1, ruleDao.count())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCOPE: ContainsPhrase
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun C20_containsPhrase_changes_all_txs_with_phrase() = runBlocking {
        val id1 = insertTx("amazon prime video",  id = 3001L)
        val id2 = insertTx("cobro amazon prime",  id = 3002L)
        val id3 = insertTx("netflix mensual",     id = 3003L) // no contiene frase

        repo.categorizeWithScope(
            id1, norm("amazon prime video"), catA,
            CategorizationScope.ContainsPhrase("amazon prime")
        )

        assertEquals(catA, getById(id1).categoryId)
        assertEquals(catA, getById(id2).categoryId)
        assertNull("Tx sin la frase no debe ser afectada", getById(id3).categoryId)
    }

    @Test fun C21_containsPhrase_creates_CONTAINS_rule() = runBlocking {
        val id = insertTx("amazon prime video", id = 3004L)
        repo.categorizeWithScope(
            id, norm("amazon prime video"), catA,
            CategorizationScope.ContainsPhrase("amazon prime")
        )

        val rule = ruleDao.findByPatternAndType("amazon prime", MatchType.CONTAINS)
        assertNotNull("ContainsPhrase debe crear una regla CONTAINS", rule)
        assertEquals(catA, rule!!.categoryId)
    }

    @Test fun C22_containsPhrase_trigger_tx_isManual_true() = runBlocking {
        val id = insertTx("amazon prime video", id = 3005L)
        repo.categorizeWithScope(
            id, norm("amazon prime video"), catA,
            CategorizationScope.ContainsPhrase("amazon prime")
        )
        assertTrue("Tx disparadora debe tener isManual=true", getById(id).isManual)
    }

    @Test fun C23_containsPhrase_bulk_txs_have_ruleId_set() = runBlocking {
        val id1 = insertTx("amazon prime video", id = 3006L)
        val id2 = insertTx("cobro amazon prime", id = 3007L)

        repo.categorizeWithScope(
            id1, norm("amazon prime video"), catA,
            CategorizationScope.ContainsPhrase("amazon prime")
        )

        val rule = ruleDao.findByPatternAndType("amazon prime", MatchType.CONTAINS)!!
        val tx2 = getById(id2)
        assertEquals("Bulk txs deben tener assignedByRuleId de la regla CONTAINS creada", rule.id, tx2.assignedByRuleId)
        assertFalse("Bulk txs deben tener isManual=false", tx2.isManual)
    }

    @Test fun C24_containsPhrase_upsert_no_duplicate_rule() = runBlocking {
        val id1 = insertTx("amazon prime video", id = 3008L)
        val id2 = insertTx("cobro amazon prime", id = 3009L)
        repo.categorizeWithScope(id1, norm("amazon prime video"), catA, CategorizationScope.ContainsPhrase("amazon prime"))
        repo.categorizeWithScope(id2, norm("cobro amazon prime"), catA, CategorizationScope.ContainsPhrase("amazon prime"))
        assertEquals("Doble upsert no debe crear reglas duplicadas", 1, ruleDao.count())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCOPE: ContainsWord
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun C30_containsWord_changes_txs_with_whole_word() = runBlocking {
        val id1 = insertTx("amazon prime",  id = 4001L)
        val id2 = insertTx("amazon video",  id = 4002L)
        val id3 = insertTx("superamazon",   id = 4003L) // "amazon" incrustado, no es palabra completa
        val id4 = insertTx("netflix",       id = 4004L)

        repo.categorizeWithScope(
            id1, norm("amazon prime"), catA,
            CategorizationScope.ContainsWord("amazon")
        )

        assertEquals(catA, getById(id1).categoryId)
        assertEquals(catA, getById(id2).categoryId)
        // Nota: el bulk SQL usa LIKE con espacios como proxy de límite de palabra
        // Para "superamazon" la query es: ' superamazon ' LIKE '% amazon %' → false ✓
        assertNull("'superamazon' no debe coincidir como CONTAINS_WORD 'amazon'", getById(id3).categoryId)
        assertNull("'netflix' no debe coincidir", getById(id4).categoryId)
    }

    @Test fun C31_containsWord_creates_CONTAINS_WORD_rule() = runBlocking {
        val id = insertTx("amazon prime", id = 4005L)
        repo.categorizeWithScope(
            id, norm("amazon prime"), catA,
            CategorizationScope.ContainsWord("amazon")
        )
        val rule = ruleDao.findByPatternAndType("amazon", MatchType.CONTAINS_WORD)
        assertNotNull("ContainsWord debe crear una regla CONTAINS_WORD", rule)
        assertEquals(catA, rule!!.categoryId)
    }

    @Test fun C32_containsWord_trigger_isManual_true() = runBlocking {
        val id = insertTx("amazon prime", id = 4006L)
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.ContainsWord("amazon"))
        assertTrue("Tx disparadora debe tener isManual=true", getById(id).isManual)
    }

    @Test fun C33_containsWord_upsert_no_duplicate() = runBlocking {
        val id1 = insertTx("amazon prime", id = 4007L)
        val id2 = insertTx("amazon video", id = 4008L)
        repo.categorizeWithScope(id1, norm("amazon prime"), catA, CategorizationScope.ContainsWord("amazon"))
        repo.categorizeWithScope(id2, norm("amazon video"),  catA, CategorizationScope.ContainsWord("amazon"))
        assertEquals("No debe haber reglas duplicadas", 1, ruleDao.count())
    }

    @Test fun C34_containsWord_upsert_updates_existing_rule_category() = runBlocking {
        // Regla preexistente para "amazon" CONTAINS_WORD → catB
        ruleDao.upsert(CategoryRuleEntity(
            normalizedPattern = "amazon",
            categoryId = catB,
            matchType = MatchType.CONTAINS_WORD,
            priority = 45
        ))
        val id = insertTx("amazon prime", id = 4009L)
        // Corregir a catA
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.ContainsWord("amazon"))

        val rule = ruleDao.findByPatternAndType("amazon", MatchType.CONTAINS_WORD)
        assertEquals("Regla existente debe actualizarse a la nueva categoría", catA, rule!!.categoryId)
        assertEquals(1, ruleDao.count())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tests de reactivación automática de reglas desactivadas (Fix C40)
    //
    // Cuando el usuario elige un scope que reutiliza una regla desactivada,
    // el sistema la reactiva automáticamente porque el usuario está indicando
    // explícitamente que quiere que esa automatización vuelva a estar vigente.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun C40_containsPhrase_reactivates_disabled_rule() = runBlocking {
        // Regla CONTAINS "amazon prime" previamente desactivada
        ruleDao.upsert(CategoryRuleEntity(
            normalizedPattern = "amazon prime",
            categoryId = catB,
            matchType = MatchType.CONTAINS,
            priority = 50,
            isEnabled = false
        ))

        val id = insertTx("amazon prime video", id = 5001L)
        repo.categorizeWithScope(
            id, norm("amazon prime video"), catA,
            CategorizationScope.ContainsPhrase("amazon prime")
        )

        // Fix: la regla debe estar activada tras el scope
        val rule = ruleDao.findByPatternAndType("amazon prime", MatchType.CONTAINS)!!
        assertTrue(
            "C40 FIX: La regla desactivada debe reactivarse automáticamente cuando el usuario " +
            "la reutiliza con un scope (está expresando que quiere que vuelva a estar vigente).",
            rule.isEnabled
        )
        // Y la categoría se actualiza a catA
        assertEquals(catA, rule.categoryId)
    }

    @Test fun C41_exactName_reactivates_disabled_rule() = runBlocking {
        ruleDao.upsert(CategoryRuleEntity(
            normalizedPattern = norm("amazon prime"),
            categoryId = catB,
            matchType = MatchType.EXACT,
            priority = 100,
            isEnabled = false
        ))

        val id = insertTx("amazon prime", id = 5002L)
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.ExactName)

        val rule = ruleDao.findByPatternAndType(norm("amazon prime"), MatchType.EXACT)!!
        assertTrue("C41: ExactName debe reactivar regla EXACT desactivada", rule.isEnabled)
        assertEquals(catA, rule.categoryId)
    }

    @Test fun C42_containsWord_reactivates_disabled_rule() = runBlocking {
        ruleDao.upsert(CategoryRuleEntity(
            normalizedPattern = "amazon",
            categoryId = catB,
            matchType = MatchType.CONTAINS_WORD,
            priority = 45,
            isEnabled = false
        ))

        val id = insertTx("amazon prime", id = 5003L)
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.ContainsWord("amazon"))

        val rule = ruleDao.findByPatternAndType("amazon", MatchType.CONTAINS_WORD)!!
        assertTrue("C42: ContainsWord debe reactivar regla CONTAINS_WORD desactivada", rule.isEnabled)
        assertEquals(catA, rule.categoryId)
    }

    @Test fun C43_reactivated_rule_applied_by_rescan() = runBlocking {
        // Una regla desactivada reutilizada mediante scope debe funcionar en el siguiente rescan
        ruleDao.upsert(CategoryRuleEntity(
            normalizedPattern = "amazon prime",
            categoryId = catB,
            matchType = MatchType.CONTAINS,
            priority = 50,
            isEnabled = false
        ))

        val id1 = insertTx("amazon prime video", id = 5010L) // tx disparadora
        val id2 = insertTx("cobro amazon prime", id = 5011L) // tx secundaria

        repo.categorizeWithScope(
            id1, norm("amazon prime video"), catA,
            CategorizationScope.ContainsPhrase("amazon prime")
        )

        // Ahora la regla está activa. El rescan debe aplicarla a id2.
        rescan()

        assertEquals(
            "C43: La regla reactivada debe aplicarse en el siguiente rescan a txs que coinciden",
            catA, getById(id2).categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tests de integración: rescan posterior al scope respeta las decisiones
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun C50_afterExactName_rescan_categorizes_new_tx_with_same_concept() = runBlocking {
        val id1 = insertTx("amazon prime", id = 6001L)
        repo.categorizeWithScope(id1, norm("amazon prime"), catA, CategorizationScope.ExactName)

        // Nueva tx con el mismo concepto normalizado (entra después del scope)
        val id2 = insertTx("amazon prime", id = 6002L)

        rescan()

        assertEquals(
            "C50: El rescan debe aplicar la regla EXACT creada a nuevas txs con el mismo concepto",
            catA, getById(id2).categoryId
        )
    }

    @Test fun C51_afterContainsPhrase_rescan_categorizes_matching_tx() = runBlocking {
        val id1 = insertTx("amazon prime video", id = 6003L)
        repo.categorizeWithScope(
            id1, norm("amazon prime video"), catA,
            CategorizationScope.ContainsPhrase("amazon prime")
        )

        // Nueva tx que contiene la frase
        val id2 = insertTx("suscripcion amazon prime anual", id = 6004L)

        rescan()

        assertEquals(
            "C51: El rescan debe aplicar la regla CONTAINS 'amazon prime' a txs con esa frase",
            catA, getById(id2).categoryId
        )
    }

    @Test fun C52_exactRule_beats_preexisting_CONTAINS_in_rescan() = runBlocking {
        // Regla CONTAINS preexistente → catB
        insertRule("amazon prime", MatchType.CONTAINS, catB, priority = 50)

        val id = insertTx("amazon prime", id = 6005L)

        // Scope ExactName crea regla EXACT → catA (score EXACT >> CONTAINS)
        repo.categorizeWithScope(id, norm("amazon prime"), catA, CategorizationScope.ExactName)

        // Nueva tx con mismo concepto
        val id2 = insertTx("amazon prime", id = 6006L)
        rescan()

        assertEquals(
            "C52: La regla EXACT creada por ExactName debe ganar sobre CONTAINS preexistente en el rescan",
            catA, getById(id2).categoryId
        )
    }

    @Test fun C53_afterContainsWord_scope_does_not_affect_unrelated_txs() = runBlocking {
        val id1 = insertTx("amazon prime",   id = 6007L) // contiene "amazon" como palabra
        val id2 = insertTx("superamazon",    id = 6008L) // "amazon" incrustado, no palabra completa
        val id3 = insertTx("netflix",        id = 6009L) // sin relación

        repo.categorizeWithScope(id1, norm("amazon prime"), catA, CategorizationScope.ContainsWord("amazon"))

        // id1 (disparadora) → catA por isManual
        assertEquals(catA, getById(id1).categoryId)

        // id2 y id3 no deben cambiar (el LIKE ' amazon ' no coincide con 'superamazon')
        assertNull(
            "C53: 'superamazon' (sin espacio delimitador) no debe coincidir con CONTAINS_WORD 'amazon'",
            getById(id2).categoryId
        )
        assertNull("C53: 'netflix' no debe coincidir", getById(id3).categoryId)
    }

    @Test fun C54_manualTx_withSameConcept_survives_after_exactName_scope_rescan() = runBlocking {
        // Tx manual con catB para el mismo concepto
        txDao.upsertAll(listOf(TransactionEntity(
            id = 7001L, date = System.currentTimeMillis(), amount = 10.0,
            concept = "amazon prime", normalizedConcept = norm("amazon prime"),
            dedupKey = "amazon prime_7001",
            categoryId = catB, type = "EXPENSE", isManual = true
        )))

        // Otra tx que activa el scope ExactName → crea regla EXACT catA
        val id2 = insertTx("amazon prime", id = 7002L)
        repo.categorizeWithScope(id2, norm("amazon prime"), catA, CategorizationScope.ExactName)

        rescan()

        // La tx manual (id=7001) debe conservar catB
        val manualTx = getById(7001L)
        assertEquals(
            "C54: La tx marcada como isManual=true NO debe ser sobreescrita por el rescan",
            catB, manualTx.categoryId
        )
        assertTrue(manualTx.isManual)
    }

    @Test fun C55_scope_exactName_does_not_affect_different_concept() = runBlocking {
        val id1 = insertTx("amazon prime",    id = 8001L) // concepto objetivo
        val id2 = insertTx("amazon prime hd", id = 8002L) // concepto diferente (normalizado distinto)

        repo.categorizeWithScope(id1, norm("amazon prime"), catA, CategorizationScope.ExactName)

        assertEquals(catA, getById(id1).categoryId)
        assertNull(
            "C55: ExactName NO debe afectar a txs con un concepto normalizado diferente, " +
            "aunque sea parecido ('amazon prime hd' ≠ 'amazon prime')",
            getById(id2).categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun norm(text: String) = DescriptionNormalizer.normalize(text)

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

    private suspend fun insertTxWithRule(concept: String, id: Long, catId: Long, ruleId: Long): Long {
        txDao.upsertAll(listOf(
            TransactionEntity(
                id = id,
                date = System.currentTimeMillis(),
                amount = 10.0,
                concept = concept,
                normalizedConcept = norm(concept),
                dedupKey = "${norm(concept)}_$id",
                categoryId = catId,
                type = "EXPENSE",
                isManual = false,
                assignedByRuleId = ruleId
            )
        ))
        return id
    }

    private suspend fun getById(id: Long): TransactionEntity =
        txDao.getAllWithCategoryOnce().first { it.transaction.id == id }.transaction

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
