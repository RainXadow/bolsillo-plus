package com.jesusluna.bolsilloplus.backup



import com.jesusluna.bolsilloplus.TestDedupKey
import com.jesusluna.bolsilloplus.data.backup.BackupFile

import com.jesusluna.bolsilloplus.data.backup.BackupImporter

import com.jesusluna.bolsilloplus.data.backup.BackupMetadata

import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity

import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity

import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity

import com.jesusluna.bolsilloplus.data.db.entity.MatchType

import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity

import com.jesusluna.bolsilloplus.excel.FakeCategoryDao

import com.jesusluna.bolsilloplus.excel.FakeImportExceptionDao

import com.jesusluna.bolsilloplus.excel.FakeTransactionDao

import com.jesusluna.bolsilloplus.scoring.FakeCategoryRuleDao

import kotlinx.coroutines.runBlocking

import org.junit.Assert.*

import org.junit.Before

import org.junit.Test



/**

 * Tests unitarios para [BackupImporter] con semántica MERGE.

 * Sin Android; usa fake DAOs en memoria.

 *

 * Casos cubiertos:

 *  BI01 — backup con solo categorías+reglas no modifica txs ni excepciones

 *  BI02 — backup con solo movimientos no modifica categorías ni excepciones

 *  BI03 — backup con solo excepciones no modifica categorías ni txs

 *  BI04 — backup completo hace MERGE: nada se borra, todo se añade

 *  BI05 — categories=null pero categoryRules≠null → todas las reglas quedan huérfanas (invariante)

 *  BI06 — lista vacía (≠null) de categorías no borra nada (semántica MERGE)

 *  BI07 — MERGE con IDs distintos: las reglas apuntan al ID local, no al del backup

 *  BI08 — Summary.toHumanString() solo incluye campos no null

 *  BI09 — backup con categories+rules donde alguna rule apunta a catId no incluido → huérfana

 *  BI-M01 — merge subcategorías: solo añade las que faltan (escenario Transporte/Vuelos)

 *  BI-M02 — merge categoría existente: no se duplica aunque el ID del backup sea distinto

 *  BI-M03 — merge regla existente: se actualiza priority e isEnabled, no se duplica

 *  BI-M04 — merge excepción existente: se actualiza isEnabled, no se duplica

 *  BI10  — backup Transporte/Taxi con IDs distintos aplica reglas con ID local correcto

 */

class BackupImporterTest {



    private lateinit var categoryDao: FakeCategoryDao

    private lateinit var categoryRuleDao: FakeCategoryRuleDao

    private lateinit var transactionDao: FakeTransactionDao

    private lateinit var importExceptionDao: FakeImportExceptionDao

    private lateinit var importer: BackupImporter



    private val metadata = BackupMetadata(

        appVersion = "test",

        exportedAt = 1_000_000L,

        includes = listOf("categories", "transactions", "exceptions")

    )



    @Before

    fun setUp() {

        categoryDao = FakeCategoryDao()

        categoryRuleDao = FakeCategoryRuleDao()

        transactionDao = FakeTransactionDao()

        importExceptionDao = FakeImportExceptionDao()

        importer = BackupImporter(categoryDao, categoryRuleDao, transactionDao, importExceptionDao)

    }



    // ── Helpers ───────────────────────────────────────────────────────────────



    private fun cat(id: Long, name: String, parentId: Long? = null) = CategoryEntity(

        id = id, name = name, color = "#FF5722", icon = "label",

        isDefault = false, parentId = parentId

    )



    private fun rule(id: Long, catId: Long, pattern: String, priority: Int = 50, enabled: Boolean = true) =

        CategoryRuleEntity(

            id = id, categoryId = catId, normalizedPattern = pattern,

            matchType = MatchType.EXACT, priority = priority, createdAt = 1_000L,

            isEnabled = enabled

        )



    private fun tx(id: Long, catId: Long? = null) = TransactionEntity(

        id = id,

        date = 1_000L,

        amount = 10.0,

        concept = "Test-$id",

        normalizedConcept = "test-$id",

        dedupKey = TestDedupKey.build(

            normalizedConcept = "test-$id",

            amount = 10.0,

            date = java.util.Date(1_000L),

            hasBalance = true,

            balance = 100.0

        ),

        categoryId = catId, 

        source = "TEST",

        type = "EXPENSE", 

        balance = 100.0, 

        hasBalance = true,

        isManual = false,

        assignedByRuleId = null, 

        candidateRuleCount = 0

    )



    private fun exception(id: Long, pattern: String = "skip_$id", enabled: Boolean = true) =

        ImportExceptionEntity(

            id = id, patternRaw = pattern, normalizedPattern = pattern,

            matchType = MatchType.EXACT, isEnabled = enabled, createdAt = 1_000L

        )



    // ── BI01 — solo categorías+reglas → txs y excepciones no cambian ──────────



    @Test

    fun `BI01 - backup con solo categorias y reglas no toca txs ni excepciones`() = runBlocking {

        transactionDao.upsertAll(listOf(tx(1L), tx(2L)))

        importExceptionDao.upsert(exception(10L))



        val backup = BackupFile(

            metadata = metadata,

            categories = listOf(cat(5L, "Alimentación"), cat(6L, "Transporte")),

            categoryRules = listOf(rule(1L, 5L, "mercadona")),

            transactions = null,

            importExceptions = null

        )



        val summary = importer.import(backup)



        assertEquals(2, summary.categoriesRestored)

        assertEquals(1, summary.rulesRestored)

        assertNull("txs no incluidas → null", summary.transactionsUpserted)

        assertNull("excepciones no incluidas → null", summary.exceptionsRestored)



        // Txs y excepciones originales intactas

        assertEquals(2, transactionDao.getAllEntities().size)

        assertEquals(1, importExceptionDao.getAll().size)



        // Categorías insertadas (DB estaba vacía) con sus nombres

        val cats = categoryDao.getAll()

        assertEquals(2, cats.size)

        assertTrue(cats.any { it.name == "Alimentación" })

        assertTrue(cats.any { it.name == "Transporte" })

    }



    // ── BI02 — solo movimientos → categorías y excepciones no cambian ─────────



    @Test

    fun `BI02 - backup con solo transacciones no toca categorias ni excepciones`() = runBlocking {

        categoryDao.insert(cat(1L, "Alimentación"))

        importExceptionDao.upsert(exception(20L))



        val backup = BackupFile(

            metadata = metadata,

            categories = null,

            categoryRules = null,

            transactions = listOf(tx(100L, catId = 1L), tx(101L, catId = 1L)),

            importExceptions = null

        )



        val summary = importer.import(backup)



        assertNull(summary.categoriesRestored)

        assertNull(summary.rulesRestored)

        assertEquals(2, summary.transactionsUpserted)

        assertNull(summary.exceptionsRestored)



        // Categoría y excepción originales intactas

        assertEquals(1, categoryDao.getAll().size)

        assertEquals("Alimentación", categoryDao.getAll()[0].name)

        assertEquals(1, importExceptionDao.getAll().size)



        // Movimientos importados (merge)

        assertEquals(2, transactionDao.getAllEntities().size)

    }



    // ── BI03 — solo excepciones → categorías y txs no cambian ─────────────────



    @Test

    fun `BI03 - backup con solo excepciones no toca categorias ni transacciones`() = runBlocking {

        categoryDao.insert(cat(1L, "Hogar"))

        transactionDao.upsertAll(listOf(tx(1L)))



        val backup = BackupFile(

            metadata = metadata,

            categories = null,

            categoryRules = null,

            transactions = null,

            importExceptions = listOf(exception(5L), exception(6L))

        )



        val summary = importer.import(backup)



        assertNull(summary.categoriesRestored)

        assertNull(summary.rulesRestored)

        assertNull(summary.transactionsUpserted)

        assertEquals(2, summary.exceptionsRestored)



        assertEquals(1, categoryDao.getAll().size)

        assertEquals(1, transactionDao.getAllEntities().size)

        assertEquals(2, importExceptionDao.getAll().size)

    }



    // ── BI04 — backup completo: MERGE (nada se borra, todo se añade) ──────────



    @Test

    fun `BI04 - backup completo hace merge datos previos no se borran backup se anade`() = runBlocking {

        // Pre-populate datos existentes

        categoryDao.insert(cat(99L, "Antigua"))

        categoryRuleDao.insertRule(rule(99L, 99L, "patron_viejo"))

        transactionDao.upsertAll(listOf(tx(999L)))

        importExceptionDao.upsert(exception(999L, pattern = "skip_viejo"))



        val newCats = listOf(cat(1L, "Alimentación"), cat(2L, "Transporte"))

        val newRules = listOf(rule(1L, 1L, "mercadona"), rule(2L, 2L, "bus"))

        val newTxs = listOf(tx(10L, catId = 1L), tx(11L, catId = 2L))

        val newExceptions = listOf(exception(1L, pattern = "skip_nuevo"))



        val backup = BackupFile(

            metadata = metadata,

            categories = newCats,

            categoryRules = newRules,

            transactions = newTxs,

            importExceptions = newExceptions

        )



        val summary = importer.import(backup)



        // 3 categorías procesadas (2 del backup, pero "Antigua" sigue siendo 0 nuevas del backup... 2 nuevas)

        // summary.categoriesRestored = size del mapping = 2 (solo las del backup)

        assertEquals(2, summary.categoriesRestored)

        assertEquals(2, summary.rulesRestored)

        assertEquals(2, summary.transactionsUpserted)

        assertEquals(1, summary.exceptionsRestored)



        // "Antigua" NO borrada — MERGE

        val cats = categoryDao.getAll()

        assertEquals(3, cats.size)

        assertTrue("cat 99 debe seguir (MERGE)", cats.any { it.id == 99L && it.name == "Antigua" })

        assertTrue(cats.any { it.name == "Alimentación" })

        assertTrue(cats.any { it.name == "Transporte" })



        // Regla vieja NO borrada — MERGE

        val rules = categoryRuleDao.getAll()

        assertEquals(3, rules.size)

        assertTrue(rules.any { it.normalizedPattern == "patron_viejo" })

        assertTrue(rules.any { it.normalizedPattern == "mercadona" })

        assertTrue(rules.any { it.normalizedPattern == "bus" })



        // Txs mergeadas — 999 sigue + 10, 11 añadidos

        assertEquals(3, transactionDao.getAllEntities().size)



        // Excepción vieja NO borrada — MERGE; nueva añadida

        val excepts = importExceptionDao.getAll()

        assertEquals(2, excepts.size)

        assertTrue("skip_viejo debe seguir (MERGE)", excepts.any { it.patternRaw == "skip_viejo" })

        assertTrue(excepts.any { it.patternRaw == "skip_nuevo" })

    }



    // ── BI05 — categories=null + rules → invariante: todas las reglas son huérfanas ─



    @Test

    fun `BI05 - backup con rules pero sin categories marca todas las reglas como huerfanas`() = runBlocking {

        // Pre-existing data

        categoryRuleDao.insertRule(rule(1L, 10L, "patron_viejo"))

        categoryDao.insert(cat(10L, "Existente"))



        val backup = BackupFile(

            metadata = metadata,

            categories = null,      // sin categorías → no se puede resolver por nombre

            categoryRules = listOf(rule(5L, 10L, "patron_nuevo")),

            transactions = null,

            importExceptions = null

        )



        val summary = importer.import(backup)



        assertNull(summary.categoriesRestored)

        // Sin categories en backup → no se puede resolver → todas huérfanas

        assertEquals(0, summary.rulesRestored)

        assertEquals(1, summary.orphanRulesSkipped)

        assertTrue(summary.toHumanString().contains("1 reglas omitidas"))



        // Regla vieja intacta (MERGE); la del backup no fue importada

        val rules = categoryRuleDao.getAll()

        assertEquals(1, rules.size)

        assertEquals("patron_viejo", rules[0].normalizedPattern)

        assertFalse("patron_nuevo no debe aparecer", rules.any { it.normalizedPattern == "patron_nuevo" })



        // Categoría original intacta

        assertEquals(1, categoryDao.getAll().size)

    }



    // ── BI06 — lista vacía (≠null) de categories no borra nada (semántica MERGE) ─



    @Test

    fun `BI06 - lista vacia de categorias no borra nada con semantica MERGE`() = runBlocking {

        categoryDao.insert(cat(1L, "Para mantener"))

        categoryRuleDao.insertRule(rule(1L, 1L, "patron"))



        val backup = BackupFile(

            metadata = metadata,

            categories = emptyList(),

            categoryRules = emptyList(),

            transactions = null,

            importExceptions = null

        )



        val summary = importer.import(backup)



        // 0 procesadas del backup (lista vacía)

        assertEquals(0, summary.categoriesRestored)

        assertEquals(0, summary.rulesRestored)



        // Los datos existentes se mantienen (MERGE, no replace)

        assertEquals(1, categoryDao.getAll().size)

        assertEquals(1, categoryRuleDao.getAll().size)

    }



    // ── BI07 — MERGE con IDs distintos: reglas apuntan al ID local ────────────



    @Test

    fun `BI07 - merge con ids distintos las reglas apuntan al id local no al del backup`() = runBlocking {

        // DB ya tiene categorías con IDs locales

        categoryDao.insert(cat(42L, "Comida"))

        categoryDao.insert(cat(43L, "Ocio"))



        // Backup tiene las mismas categorías pero con IDs distintos

        val backup = BackupFile(

            metadata = metadata,

            categories = listOf(cat(100L, "Comida"), cat(200L, "Ocio")),

            categoryRules = listOf(

                rule(1L, 100L, "restaurante"),  // backup catId=100 → debe mapear a local 42

                rule(2L, 200L, "cine")          // backup catId=200 → debe mapear a local 43

            ),

            transactions = null,

            importExceptions = null

        )



        importer.import(backup)



        // No se crean categorías duplicadas (ya existían por nombre)

        val cats = categoryDao.getAll()

        assertEquals(2, cats.size)

        assertTrue(cats.any { it.id == 42L && it.name == "Comida" })

        assertTrue(cats.any { it.id == 43L && it.name == "Ocio" })



        // Reglas apuntan a los IDs locales, no a los del backup

        val rules = categoryRuleDao.getAll()

        assertEquals(2, rules.size)

        assertTrue("regla restaurante debe apuntar a id local 42",

            rules.any { it.normalizedPattern == "restaurante" && it.categoryId == 42L })

        assertTrue("regla cine debe apuntar a id local 43",

            rules.any { it.normalizedPattern == "cine" && it.categoryId == 43L })

    }



    // ── BI08 — Summary.toHumanString() omite campos null ─────────────────────



    @Test

    fun `BI08 - toHumanString omite campos null y formatea correctamente`() {

        assertEquals(

            "2 categorías, 3 reglas",

            BackupImporter.Summary(

                categoriesRestored = 2, rulesRestored = 3,

                transactionsUpserted = null, exceptionsRestored = null

            ).toHumanString()

        )

        assertEquals(

            "50 movimientos, 4 excepciones",

            BackupImporter.Summary(

                categoriesRestored = null, rulesRestored = null,

                transactionsUpserted = 50, exceptionsRestored = 4

            ).toHumanString()

        )

        assertEquals(

            "",

            BackupImporter.Summary(

                categoriesRestored = null, rulesRestored = null,

                transactionsUpserted = null, exceptionsRestored = null

            ).toHumanString()

        )

        assertEquals(

            "1 categorías, 1 reglas, 1 movimientos, 1 excepciones",

            BackupImporter.Summary(

                categoriesRestored = 1, rulesRestored = 1,

                transactionsUpserted = 1, exceptionsRestored = 1

            ).toHumanString()

        )

        // Con huérfanas

        val withOrphans = BackupImporter.Summary(

            categoriesRestored = null, rulesRestored = 2, orphanRulesSkipped = 3,

            transactionsUpserted = null, exceptionsRestored = null

        )

        assertTrue(

            "toHumanString debe incluir aviso de huérfanas",

            withOrphans.toHumanString().contains("3 reglas omitidas")

        )

    }



    // ── BI09 — backup con categories+rules donde alguna rule apunta a catId no incluido ─



    @Test

    fun `BI09 - reglas cuya catId del backup no esta en el backup de categories son huerfanas`() = runBlocking {

        // DB pre-existente

        categoryDao.insert(cat(10L, "Existente"))



        val backup = BackupFile(

            metadata = metadata,

            // categories SÍ incluidas → se construye idMapping por nombre

            categories = listOf(cat(10L, "Existente")),

            categoryRules = listOf(

                rule(1L, 10L, "patron_valido"),    // catId=10 en backup → resuelve a local 10

                rule(2L, 99L, "patron_huerfano"),  // catId=99 no está en backup.categories → huérfana

                rule(3L, 99L, "otro_huerfano")     // catId=99 no está en backup.categories → huérfana

            ),

            transactions = null,

            importExceptions = null

        )



        val summary = importer.import(backup)



        assertEquals("1 cat procesada", 1, summary.categoriesRestored)

        assertEquals("solo 1 regla válida", 1, summary.rulesRestored)

        assertEquals("2 huérfanas omitidas", 2, summary.orphanRulesSkipped)



        val rules = categoryRuleDao.getAll()

        assertEquals(1, rules.size)

        assertEquals("patron_valido", rules[0].normalizedPattern)

        assertTrue("la huérfana no debe estar en DB",

            rules.none { it.normalizedPattern == "patron_huerfano" })



        assertTrue(summary.toHumanString().contains("2 reglas omitidas"))

    }



    // ── BI-M01 — escenario Transporte/Vuelos: solo añade lo que falta ─────────



    @Test

    fun `BIM01 - merge subcategorias solo anade las que faltan, no duplica ni borra`() = runBlocking {

        // DB: Transporte (root) con hijos Taxi y Trenes

        val localTransporteId = categoryDao.insert(cat(0L, "Transporte"))

        val localTaxiId = categoryDao.insert(cat(0L, "Taxi", parentId = localTransporteId))

        val localTrenesId = categoryDao.insert(cat(0L, "Trenes", parentId = localTransporteId))



        // Backup: Transporte (id distinto) con Taxi, Trenes y Vuelos (nuevo)

        val backupTransporteId = 7L

        val backup = BackupFile(

            metadata = metadata,

            categories = listOf(

                cat(backupTransporteId, "Transporte"),

                cat(8L, "Taxi", parentId = backupTransporteId),

                cat(9L, "Trenes", parentId = backupTransporteId),

                cat(10L, "Vuelos", parentId = backupTransporteId)   // ← solo este es nuevo

            ),

            categoryRules = null,

            transactions = null,

            importExceptions = null

        )



        val summary = importer.import(backup)



        // 4 procesadas del backup (1 root + 3 hijos), aunque 3 ya existían

        assertEquals(4, summary.categoriesRestored)



        val cats = categoryDao.getAll()

        // Debe haber exactamente 4 cats: Transporte + Taxi + Trenes + Vuelos (sin duplicados)

        assertEquals(4, cats.size)



        // Transporte sigue con su ID local

        val transporte = cats.first { it.name == "Transporte" }

        assertEquals(localTransporteId, transporte.id)



        // Taxi y Trenes siguen con sus IDs locales

        assertTrue(cats.any { it.id == localTaxiId && it.name == "Taxi" })

        assertTrue(cats.any { it.id == localTrenesId && it.name == "Trenes" })



        // Vuelos fue añadida como hijo de Transporte (local)

        val vuelos = cats.firstOrNull { it.name == "Vuelos" }

        assertNotNull("Vuelos debe haberse añadido", vuelos)

        assertEquals("Vuelos debe apuntar al ID local de Transporte",

            localTransporteId, vuelos!!.parentId)

    }



    // ── BI-M02 — categoría existente no se duplica aunque el ID del backup difiera ─



    @Test

    fun `BIM02 - merge categoria existente no crea duplicado`() = runBlocking {

        val localId = categoryDao.insert(cat(5L, "Alimentación"))



        // Backup tiene misma categoría pero con ID distinto

        val backup = BackupFile(

            metadata = metadata,

            categories = listOf(cat(99L, "Alimentación")),

            categoryRules = null,

            transactions = null,

            importExceptions = null

        )



        importer.import(backup)



        // Solo debe existir una "Alimentación"

        val cats = categoryDao.getAll()

        assertEquals("no debe haber duplicados", 1, cats.size)

        assertEquals("debe conservar el ID local", localId, cats[0].id)

        assertEquals("Alimentación", cats[0].name)

    }



    // ── BI-M03 — regla existente se actualiza (priority, isEnabled), no se duplica ─



    @Test

    fun `BIM03 - merge regla existente actualiza priority e isEnabled sin duplicar`() = runBlocking {

        val catId = categoryDao.insert(cat(10L, "Comida"))

        categoryRuleDao.insertRule(rule(1L, catId, "mercadona", priority = 50, enabled = true))



        // Backup: misma clave lógica (catId local=10, pattern="mercadona", EXACT) pero priority actualizado

        // El catId del backup puede diferir (usamos el mismo aquí por simplicidad)

        val backup = BackupFile(

            metadata = metadata,

            categories = listOf(cat(10L, "Comida")),

            categoryRules = listOf(rule(99L, 10L, "mercadona", priority = 100, enabled = false)),

            transactions = null,

            importExceptions = null

        )



        importer.import(backup)



        val rules = categoryRuleDao.getAll()

        assertEquals("no debe haber duplicados", 1, rules.size)

        val updated = rules[0]

        assertEquals("priority debe actualizarse", 100, updated.priority)

        assertFalse("isEnabled debe actualizarse", updated.isEnabled)

        assertEquals("categoryId debe seguir siendo el local", catId, updated.categoryId)

    }



    // ── BI10 — backup Transporte/Taxi con IDs distintos → reglas con ID local ────



    @Test

    fun `BI10 - backup Transporte-Taxi con IDs distintos aplica reglas con id local correcto`() = runBlocking {

        // DB: Transporte (id auto) con hijo Taxi (id auto)

        val localTransporteId = categoryDao.insert(cat(0L, "Transporte"))

        val localTaxiId = categoryDao.insert(cat(0L, "Taxi", parentId = localTransporteId))



        // Backup: mismas categorías con IDs radicalmente distintos + 2 reglas

        val backupTransporteId = 50L

        val backupTaxiId = 51L

        val backup = BackupFile(

            metadata = metadata,

            categories = listOf(

                cat(backupTransporteId, "Transporte"),

                cat(backupTaxiId, "Taxi", parentId = backupTransporteId)

            ),

            categoryRules = listOf(

                rule(99L, backupTaxiId, "taxi_app"),          // nueva regla para Taxi

                rule(100L, backupTransporteId, "transporte")  // nueva regla para Transporte

            ),

            transactions = null,

            importExceptions = null

        )



        val summary = importer.import(backup)



        assertEquals(2, summary.categoriesRestored)

        assertEquals(2, summary.rulesRestored)

        assertEquals(0, summary.orphanRulesSkipped)



        // Sin duplicados de categorías (se resolvieron por nombre)

        assertEquals(2, categoryDao.getAll().size)



        // Reglas apuntan a los IDs locales, no a los del backup

        val rules = categoryRuleDao.getAll()

        assertEquals(2, rules.size)

        val rTaxi = rules.first { it.normalizedPattern == "taxi_app" }

        val rTransporte = rules.first { it.normalizedPattern == "transporte" }

        assertEquals("taxi_app debe apuntar al ID local de Taxi", localTaxiId, rTaxi.categoryId)

        assertEquals("transporte debe apuntar al ID local de Transporte", localTransporteId, rTransporte.categoryId)

    }



    // ── BI-M04 — excepción existente se actualiza (isEnabled), no se duplica ─────



    @Test

    fun `BIM04 - merge excepcion existente la actualiza sin duplicar`() = runBlocking {

        importExceptionDao.upsert(exception(1L, pattern = "skip_this", enabled = true))



        // Backup: misma clave lógica (pattern + matchType) pero isEnabled=false

        val backup = BackupFile(

            metadata = metadata,

            categories = null,

            categoryRules = null,

            transactions = null,

            importExceptions = listOf(exception(99L, pattern = "skip_this", enabled = false))

        )



        val summary = importer.import(backup)



        assertEquals(1, summary.exceptionsRestored)



        val excepts = importExceptionDao.getAll()

        assertEquals("no debe haber duplicados", 1, excepts.size)

        assertFalse("isEnabled debe actualizarse a false", excepts[0].isEnabled)

    }

}

