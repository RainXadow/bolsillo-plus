package com.jesusluna.bolsilloplus.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jesusluna.bolsilloplus.Transaction
import com.jesusluna.bolsilloplus.TransactionType
import com.jesusluna.bolsilloplus.data.db.AppDatabase
import com.jesusluna.bolsilloplus.data.db.dao.CategoryRuleDao
import com.jesusluna.bolsilloplus.data.db.dao.TransactionDao
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import com.jesusluna.bolsilloplus.data.repository.TransactionRepository
import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * Tests de integración Room para el flujo de importación y rescan masivo.
 *
 * Grupos:
 *  B - importTransactions() (auto-categorización, deduplicación, candidateRuleCount)
 *  R - applyRulesToAll() / rescan lógica equivalente
 */
@RunWith(AndroidJUnit4::class)
class CategorizationRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var txDao: TransactionDao
    private lateinit var ruleDao: CategoryRuleDao
    private lateinit var repo: TransactionRepository

    // IDs de categorías (se insertan en setUp)
    private var catCompras = 0L
    private var catSuscripciones = 0L
    private var catAlimentacion = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        txDao   = db.transactionDao()
        ruleDao = db.categoryRuleDao()
        repo    = TransactionRepository(txDao, ruleDao, db.importExceptionDao(), db.skippedTransactionDao(), db.categoryDao())

        catCompras       = db.categoryDao().insert(CategoryEntity(name = "Compras",       color = "#E91E63"))
        catSuscripciones = db.categoryDao().insert(CategoryEntity(name = "Suscripciones", color = "#9C27B0"))
        catAlimentacion  = db.categoryDao().insert(CategoryEntity(name = "Alimentación",  color = "#4CAF50"))
    }

    @After
    fun tearDown() { db.close() }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO B: importTransactions()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun B01_import_withMatchingRule_setsCategoryId() = runBlocking {
        insertRule("amazon", MatchType.CONTAINS, catCompras)
        val (added, dupes) = repo.importTransactions(listOf(legacyTx("AMAZON PRIME", 1001L)))
        assertEquals(1, added); assertEquals(0, dupes)
        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals("categoryId debe ser catCompras", catCompras, tx.categoryId)
    }

    @Test fun B02_import_withNoRule_categoryIdIsNull() = runBlocking {
        val (added, _) = repo.importTransactions(listOf(legacyTx("PAGO RECIBO LUZ", 1002L)))
        assertEquals(1, added)
        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertNull("Sin regla, categoryId debe ser null", tx.categoryId)
    }

    @Test fun B03_import_setsAssignedByRuleId() = runBlocking {
        val ruleId = insertRule("amazon", MatchType.CONTAINS, catCompras)
        repo.importTransactions(listOf(legacyTx("AMAZON PRIME", 1003L)))
        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals("assignedByRuleId debe apuntar a la regla ganadora", ruleId, tx.assignedByRuleId)
    }

    @Test fun B04_import_noRule_assignedByRuleIdIsNull() = runBlocking {
        repo.importTransactions(listOf(legacyTx("RECIBO GAS", 1004L)))
        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertNull("Sin regla, assignedByRuleId debe ser null", tx.assignedByRuleId)
    }

    @Test fun B05_import_singleMatch_candidateRuleCount1() = runBlocking {
        insertRule("amazon", MatchType.CONTAINS, catCompras)
        repo.importTransactions(listOf(legacyTx("AMAZON PRIME", 1005L)))
        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals(1, tx.candidateRuleCount)
    }

    @Test fun B06_import_threeMatchingRules_candidateRuleCount3() = runBlocking {
        // Escenario Amazon Prime del usuario
        insertRule("amazon", MatchType.CONTAINS_WORD, catCompras, 45)
        insertRule("prime",  MatchType.CONTAINS_WORD, catSuscripciones, 45)
        insertRule("amazon prime", MatchType.CONTAINS, catSuscripciones, 55)
        repo.importTransactions(listOf(legacyTx("AMAZON PRIME", 1006L)))
        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals("3 reglas coinciden → candidateRuleCount=3", 3, tx.candidateRuleCount)
    }

    @Test fun B07_import_conflict_winnerIsHighestScore() = runBlocking {
        // CONTAINS "amazon prime" debe ganar sobre CONTAINS_WORD "amazon"
        insertRule("amazon",       MatchType.CONTAINS_WORD, catCompras, 45)
        insertRule("amazon prime", MatchType.CONTAINS,      catSuscripciones, 50)
        repo.importTransactions(listOf(legacyTx("AMAZON PRIME VIDEO", 1007L)))
        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals("Ganadora = CONTAINS 'amazon prime' → catSuscripciones", catSuscripciones, tx.categoryId)
    }

    @Test fun B08_import_deduplication_sameTx_notAdded() = runBlocking {
        val tx = legacyTx("AMAZON PRIME", 1008L, date = Date(1_700_000_000_000L))
        val (added1, _) = repo.importTransactions(listOf(tx))
        val (added2, dupes) = repo.importTransactions(listOf(tx))
        assertEquals(1, added1)
        assertEquals(0, added2)
        assertEquals("El duplicado debe ser reportado", 1, dupes)
        assertEquals("Solo debe haber 1 tx en DB", 1, txDao.count())
    }

    @Test fun B09_import_deduplication_differentAmount_added() = runBlocking {
        val tx1 = legacyTx("AMAZON PRIME", 2001L, amount = 9.99)
        val tx2 = legacyTx("AMAZON PRIME", 2002L, amount = 19.99)
        val (added, dupes) = repo.importTransactions(listOf(tx1, tx2))
        assertEquals(2, added); assertEquals(0, dupes)
        assertEquals(2, txDao.count())
    }

    @Test fun B10_import_deduplication_differentDate_added() = runBlocking {
        val tx1 = legacyTx("AMAZON PRIME", 3001L, date = Date(1_700_000_000_000L))
        val tx2 = legacyTx("AMAZON PRIME", 3002L, date = Date(1_700_100_000_000L))
        val (added, _) = repo.importTransactions(listOf(tx1, tx2))
        assertEquals(2, added)
    }

    @Test fun B11_import_isManualSetFalse() = runBlocking {
        insertRule("amazon", MatchType.CONTAINS, catCompras)
        repo.importTransactions(listOf(legacyTx("AMAZON", 4001L)))
        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertFalse("Transacciones importadas deben tener isManual=false", tx.isManual)
    }

    @Test fun B12_import_multipleRules_differentConcepts_allCategorized() = runBlocking {
        insertRule("amazon",     MatchType.CONTAINS, catCompras)
        insertRule("mercadona",  MatchType.CONTAINS, catAlimentacion)
        repo.importTransactions(listOf(
            legacyTx("AMAZON PRIME", 5001L),
            legacyTx("MERCADONA SA", 5002L)
        ))
        val all = txDao.getAllWithCategoryOnce()
        assertEquals(2, all.size)
        val amazonTx = all.first { "amazon" in it.transaction.normalizedConcept }
        val mercTx   = all.first { "mercadona" in it.transaction.normalizedConcept }
        assertEquals(catCompras,      amazonTx.transaction.categoryId)
        assertEquals(catAlimentacion, mercTx.transaction.categoryId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO R: Rescan (lógica equivalente a CategoriesViewModel.applyRulesToAll)
    // Se replica la lógica del ViewModel aquí para no depender del Application context.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun R01_rescan_categorizes_uncategorized_tx() = runBlocking {
        // Importar sin reglas → categoryId=null
        insertTx("amazon prime", id = 6001L)
        assertEquals(0, txDao.getAllWithCategoryOnce().first().transaction.candidateRuleCount)

        // Añadir regla después
        insertRule("amazon", MatchType.CONTAINS, catCompras)

        // Rescan
        rescan()

        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals("Rescan debe asignar categoría a tx sin categoría", catCompras, tx.categoryId)
    }

    @Test fun R02_rescan_does_not_touch_manual_tx() = runBlocking {
        // Insertar tx manual con categoría
        txDao.upsertAll(listOf(buildEntity(
            id = 7001L, concept = "amazon prime", catId = catAlimentacion, isManual = true
        )))
        insertRule("amazon", MatchType.CONTAINS, catCompras) // regla diferente

        rescan()

        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals(
            "isManual=true → rescan no debe sobreescribir la categoría manual",
            catAlimentacion, tx.categoryId
        )
    }

    @Test fun R03_rescan_updates_auto_tx_when_better_rule_appears() = runBlocking {
        // Tx categorizada automáticamente con regla "amazon" → Compras
        val ruleId = insertRule("amazon", MatchType.CONTAINS, catCompras)
        txDao.upsertAll(listOf(buildEntity(
            id = 8001L, concept = "amazon prime", catId = catCompras,
            isManual = false, ruleId = ruleId
        )))

        // Nueva regla más específica → Suscripciones
        insertRule("amazon prime", MatchType.CONTAINS, catSuscripciones, 50)

        rescan()

        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals(
            "Rescan debe actualizar tx automática cuando aparece una regla más específica",
            catSuscripciones, tx.categoryId
        )
    }

    @Test fun R04_rescan_updates_candidateRuleCount() = runBlocking {
        insertTx("amazon prime", id = 9001L) // sin categoría, sin reglas

        insertRule("amazon",       MatchType.CONTAINS_WORD, catCompras, 45)
        insertRule("prime",        MatchType.CONTAINS_WORD, catSuscripciones, 45)
        insertRule("amazon prime", MatchType.CONTAINS,      catSuscripciones, 55)

        rescan()

        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals("Rescan debe actualizar candidateRuleCount=3", 3, tx.candidateRuleCount)
    }

    @Test fun R05_rescan_noMatchingRules_keepsCurrent() = runBlocking {
        // Tx con categoría previa, sin reglas
        txDao.upsertAll(listOf(buildEntity(
            id = 9002L, concept = "netflix mensual", catId = catSuscripciones, isManual = false
        )))

        rescan()

        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals(
            "Sin reglas que coincidan, la categoría preexistente se conserva",
            catSuscripciones, tx.categoryId
        )
    }

    @Test fun R06_rescan_disabledRule_not_applied() = runBlocking {
        insertTx("amazon prime", id = 9003L)
        insertRule("amazon", MatchType.CONTAINS, catCompras, 50, enabled = false)

        rescan()

        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertNull("Regla desactivada no debe aplicarse en el rescan", tx.categoryId)
    }

    @Test fun R07_rescan_setsAssignedByRuleId() = runBlocking {
        insertTx("amazon prime", id = 9004L)
        val ruleId = insertRule("amazon prime", MatchType.CONTAINS, catSuscripciones)

        rescan()

        val tx = txDao.getAllWithCategoryOnce().first().transaction
        assertEquals("assignedByRuleId debe ser el id de la regla ganadora tras rescan", ruleId, tx.assignedByRuleId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Replica la lógica de CategoriesViewModel.applyRulesToAll() sin necesitar Application context. */
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

    private suspend fun insertRule(
        pattern: String,
        type: MatchType,
        catId: Long,
        priority: Int = type.defaultPriority,
        enabled: Boolean = true
    ): Long {
        val rule = CategoryRuleEntity(
            normalizedPattern = DescriptionNormalizer.normalize(pattern),
            categoryId = catId,
            matchType = type,
            priority = priority,
            isEnabled = enabled
        )
        ruleDao.upsert(rule)
        return ruleDao.findByPatternAndType(DescriptionNormalizer.normalize(pattern), type)!!.id
    }

    private suspend fun insertTx(concept: String, id: Long) {
        txDao.upsertAll(listOf(buildEntity(id = id, concept = concept, catId = null, isManual = false)))
    }

    private fun buildEntity(
        id: Long,
        concept: String,
        catId: Long?,
        isManual: Boolean,
        ruleId: Long? = null,
        candidateRuleCount: Int = 0
    ) = TransactionEntity(
        id = id,
        date = System.currentTimeMillis(),
        amount = 10.0,
        concept = concept,
        normalizedConcept = DescriptionNormalizer.normalize(concept),
        dedupKey = "${DescriptionNormalizer.normalize(concept)}_$id",
        categoryId = catId,
        type = "EXPENSE",
        isManual = isManual,
        assignedByRuleId = ruleId,
        candidateRuleCount = candidateRuleCount
    )

    private fun legacyTx(
        concept: String,
        id: Long,
        amount: Double = 9.99,
        date: Date = Date()
    ) = Transaction(
        id = id,
        date = date,
        amount = amount,
        concept = concept,
        type = TransactionType.EXPENSE
    )
}
