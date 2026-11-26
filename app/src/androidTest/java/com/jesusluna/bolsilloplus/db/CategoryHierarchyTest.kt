package com.jesusluna.bolsilloplus.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jesusluna.bolsilloplus.data.db.AppDatabase
import com.jesusluna.bolsilloplus.data.db.dao.CategoryDao
import com.jesusluna.bolsilloplus.data.db.dao.TransactionDao
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import com.jesusluna.bolsilloplus.data.repository.CategoryRepository
import com.jesusluna.bolsilloplus.data.repository.TransactionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados Room in-memory para la jerarquía de categorías (padre→hijo, 1 nivel).
 */
@RunWith(AndroidJUnit4::class)
class CategoryHierarchyTest {

    private lateinit var db: AppDatabase
    private lateinit var catDao: CategoryDao
    private lateinit var txDao: TransactionDao
    private lateinit var catRepo: CategoryRepository
    private lateinit var txRepo: TransactionRepository

    /** Contador de IDs para TransactionEntity (no usa autoGenerate). */
    private var txIdSeq = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catDao = db.categoryDao()
        txDao  = db.transactionDao()
        catRepo = CategoryRepository(catDao, txDao)
        txRepo  = TransactionRepository(txDao, db.categoryRuleDao(), db.importExceptionDao(), db.skippedTransactionDao(), catDao)
    }

    @After
    fun tearDown() { db.close() }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private suspend fun insertCategory(name: String, parentId: Long? = null): Long =
        catDao.insert(CategoryEntity(name = name, color = "#2196F3", parentId = parentId))

    /**
     * Inserta una transacción en la DB y devuelve su id.
     * TransactionEntity no usa autoGenerate, por lo que los IDs se asignan
     * mediante [txIdSeq] para garantizar unicidad dentro del test.
     */
    private suspend fun insertTx(categoryId: Long?, concept: String = "test", amount: Double = -10.0): Long {
        val id = ++txIdSeq
        val entity = TransactionEntity(
            id = id,
            date = System.currentTimeMillis(),
            amount = amount,
            concept = concept,
            normalizedConcept = concept.lowercase(),
            dedupKey = "${concept.lowercase()}_$id",
            categoryId = categoryId,
            source = "test",
            type = "EXPENSE",
            balance = 100.0,
            isManual = false
        )
        txDao.upsertAll(listOf(entity))
        return id
    }

    // ── Tests — Estructura básica ──────────────────────────────────────────────

    /** 1. parentId == null → isRoot; puede añadir subcategoría */
    @Test
    fun rootWithoutChildren_isRoot() = runBlocking {
        val rootId = insertCategory("Ocio")
        val root = catDao.getById(rootId)!!
        assertNull("parentId debe ser null para root", root.parentId)
    }

    /** 2. Subárbol sin hijos = solo las propias txs */
    @Test
    fun rootWithoutChildren_subtreeIsOwnTransactions() = runBlocking {
        val rootId = insertCategory("Alimentación")
        insertTx(rootId, "mercadona")
        insertTx(rootId, "lidl")

        val txs = txRepo.transactionsByParentTree(rootId).first()
        assertEquals(2, txs.size)
        assertTrue(txs.all { it.categoryId == rootId })
    }

    /** 3. Subárbol padre+hijo incluye todas las txs sin duplicados */
    @Test
    fun parentTree_includesChildTransactions_noDuplicates() = runBlocking {
        val parentId = insertCategory("Compras")
        val childId  = insertCategory("Online", parentId = parentId)

        insertTx(parentId, "el corte ingles")
        insertTx(childId,  "amazon")
        insertTx(childId,  "zalando")

        val txs = txRepo.transactionsByParentTree(parentId).first()
        assertEquals("Debe haber 3 txs en total (1 padre + 2 hijo)", 3, txs.size)
        val ids = txs.map { it.categoryId }.toSet()
        assertTrue(ids.contains(parentId))
        assertTrue(ids.contains(childId))
    }

    /** 4. Vista hijo NO incluye txs del padre */
    @Test
    fun childView_onlyOwnTransactions() = runBlocking {
        val parentId = insertCategory("Transporte")
        val childId  = insertCategory("Metro", parentId = parentId)

        insertTx(parentId, "taxi")
        insertTx(childId,  "metro recarga")

        val childTxs = txDao.observeByCategory(childId).first()
        assertEquals(1, childTxs.size)
        assertEquals(childId, childTxs.first().categoryId)
    }

    /** 5. Borrar padre mueve hijos a root (parentId=null), no los elimina */
    @Test
    fun deleteParent_movesChildrenToRoot() = runBlocking {
        val parentId = insertCategory("Servicios")
        val childId  = insertCategory("Internet", parentId = parentId)

        catRepo.delete(parentId)

        val child = catDao.getById(childId)
        assertNotNull("El hijo no debe borrarse", child)
        assertNull("El hijo debe quedar con parentId=null", child!!.parentId)
    }

    /** 5b. Borrar padre pone las txs propias del padre a categoryId=null */
    @Test
    fun deleteParent_ownTransactionsBecomeUncategorized() = runBlocking {
        val parentId = insertCategory("Servicios")
        insertTx(parentId, "luz mensual")

        catRepo.delete(parentId)

        // La tx ya no debe aparecer en ninguna búsqueda por parentId (su categoryId es null)
        val remaining = txDao.observeByCategoryList(listOf(parentId)).first()
        assertEquals("Las txs propias del padre deben quedar sin categoría (categoryId=null)", 0, remaining.size)
    }

    // ── Tests — Validaciones y restricciones ───────────────────────────────────

    /** 6. Insertar nieto (hijo de hijo) → error */
    @Test(expected = IllegalStateException::class)
    fun insertChild_rejectsGrandchild(): Unit = runBlocking {
        val parentId = insertCategory("Ocio")
        val childId  = catRepo.insertChild("Cine", "#FF5722", parentId)
        // Intentar insertar un nieto debe lanzar IllegalStateException
        catRepo.insertChild("IMAX", "#FF5722", childId)
    }

    /** 6b. Insertar hijo de parentId inexistente → error */
    @Test(expected = IllegalStateException::class)
    fun insertChild_rejectsNonExistentParent(): Unit = runBlocking {
        catRepo.insertChild("Online", "#FF5722", 9999L) // ID que no existe
    }

    /** 7a. parentId = propio id → error */
    @Test(expected = IllegalStateException::class)
    fun setParent_rejectsSelf(): Unit = runBlocking {
        val catId = insertCategory("Alimentación")
        catRepo.setParent(catId, catId)
    }

    /** 7b. Root con hijos no puede convertirse en hijo (evita nietos) */
    @Test(expected = IllegalStateException::class)
    fun setParent_rejectsRootWithChildren(): Unit = runBlocking {
        val rootA = insertCategory("Compras")
        val rootB = insertCategory("Ocio")
        catRepo.insertChild("Online", "#E91E63", rootA)
        // Intentar mover rootA (que tiene un hijo) bajo rootB → error
        catRepo.setParent(rootA, rootB)
    }

    /** 7c. countByCategoryId devuelve solo txs propias (no subárbol) */
    @Test
    fun countByCategoryId_onlyOwnTxs() = runBlocking {
        val parentId = insertCategory("Hogar")
        val childId  = insertCategory("Agua", parentId = parentId)

        insertTx(parentId, "luz mensual")
        insertTx(childId,  "factura agua")
        insertTx(childId,  "factura gas")

        val ownCount = txDao.countByCategoryId(parentId)
        assertEquals("Solo 1 tx propia del padre, no las 2 del hijo", 1, ownCount)
    }

    // ── Tests — insertChild correcto ───────────────────────────────────────────

    /** 8a. insertChild asigna el parentId correcto en la entidad resultante */
    @Test
    fun insertChild_setsCorrectParentId() = runBlocking {
        val parentId = insertCategory("Compras")
        val childId  = catRepo.insertChild("Online", "#FF5722", parentId)

        val child = catDao.getById(childId)
        assertNotNull("El hijo debe existir en DB", child)
        assertEquals("El hijo debe apuntar al padre correcto", parentId, child!!.parentId)
    }

    /** 8b. setParent puede mover un hijo de un padre a otro */
    @Test
    fun setParent_movesChildBetweenParents() = runBlocking {
        val parentA = insertCategory("Compras")
        val parentB = insertCategory("Ocio")
        val childId = catRepo.insertChild("Online", "#FF5722", parentA)

        // Mover hijo de parentA a parentB
        catRepo.setParent(childId, parentB)

        val child = catDao.getById(childId)!!
        assertEquals("El hijo debe apuntar al nuevo padre (parentB)", parentB, child.parentId)
    }

    // ── Tests — observeAllWithCount ────────────────────────────────────────────

    /** 9. observeAllWithCount suma árbol en el padre */
    @Test
    fun parentCount_includesChildTransactions() = runBlocking {
        val parentId = insertCategory("Hogar")
        val childId  = insertCategory("Electrónica", parentId = parentId)

        insertTx(parentId, "luz")
        insertTx(childId,  "tv")
        insertTx(childId,  "router")

        val withCount = catDao.observeAllWithCount().first()
        val parentEntry = withCount.first { it.category.id == parentId }
        assertEquals(
            "El contador del padre debe incluir sus 3 txs (1 propia + 2 del hijo)",
            3, parentEntry.transactionCount
        )
        val childEntry = withCount.first { it.category.id == childId }
        assertEquals(
            "El contador del hijo solo muestra sus 2 txs propias",
            2, childEntry.transactionCount
        )
    }

    // ── Tests — Reglas y badges ────────────────────────────────────────────────

    /** 10. Regla apuntando a subcategoría categoriza correctamente */
    @Test
    fun rule_targetingChild_categorizes() = runBlocking {
        val parentId = insertCategory("Compras")
        val childId  = insertCategory("Online", parentId = parentId)

        db.categoryRuleDao().upsert(
            CategoryRuleEntity(
                normalizedPattern = "amazon",
                categoryId = childId,
                matchType = MatchType.CONTAINS,
                priority = MatchType.CONTAINS.defaultPriority
            )
        )

        val txId = ++txIdSeq
        val txEntity = TransactionEntity(
            id = txId,
            date = System.currentTimeMillis(),
            amount = -25.0,
            concept = "AMAZON PRIME",
            normalizedConcept = "amazon prime",
            dedupKey = "amazon prime_$txId",
            categoryId = null,
            source = "test",
            type = "EXPENSE",
            balance = 200.0,
            isManual = false
        )
        txDao.upsertAll(listOf(txEntity))
        txDao.updateCategory(txEntity.id, childId)

        val result = txDao.observeByCategory(childId).first()
        assertEquals(1, result.size)
        assertEquals(childId, result.first().categoryId)
    }

    /** 11. tx de hijo expone categoryId distinto del padre → badge debería mostrarse */
    @Test
    fun badge_shownForChildTransactions_whenViewedFromParent() = runBlocking {
        val parentId = insertCategory("Alimentación")
        val childId  = insertCategory("Supermercados", parentId = parentId)

        insertTx(childId, "mercadona")

        val treeTxs = txRepo.transactionsByParentTree(parentId).first()
        assertEquals(1, treeTxs.size)
        val tx = treeTxs.first()
        assertNotEquals(
            "El categoryId de la tx (hijo) debe diferir del parentId (padre) para mostrar el badge",
            parentId, tx.categoryId
        )
        assertEquals(childId, tx.categoryId)
    }
}
