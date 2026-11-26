package com.jesusluna.bolsilloplus.backup



import com.jesusluna.bolsilloplus.TestDedupKey
import com.jesusluna.bolsilloplus.data.backup.BackupFile

import com.jesusluna.bolsilloplus.data.backup.BackupManager

import com.jesusluna.bolsilloplus.data.backup.BackupMetadata

import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity

import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity

import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity

import com.jesusluna.bolsilloplus.data.db.entity.MatchType

import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity

import org.junit.Assert.*

import org.junit.Test



class BackupManagerTest {



    // ── Helpers ───────────────────────────────────────────────────────────────



    private fun makeMetadata(

        includes: List<String> = emptyList(),

        schema: Int = BackupManager.SCHEMA_VERSION

    ) = BackupMetadata(

        appVersion = "1.0",

        schemaVersion = schema,

        exportedAt = 1_700_000_000_000L,

        includes = includes

    )



    private fun makeCat(id: Long, name: String, parentId: Long? = null) =

        CategoryEntity(id = id, name = name, color = "#FF5722", parentId = parentId)



    private fun makeRule(id: Long, pattern: String, categoryId: Long) =

        CategoryRuleEntity(id = id, normalizedPattern = pattern, categoryId = categoryId,

            matchType = MatchType.EXACT, priority = 100, createdAt = 1_700_000_000_000L)



    private fun makeTx(id: Long, categoryId: Long? = null) = TransactionEntity(

        id = id, date = 1_700_000_000_000L, amount = -50.0,

        concept = "Test", normalizedConcept = "test", 

        dedupKey = TestDedupKey.buildFromConcept(

            concept = "Test",

            amount = -50.0,

            dateMs = 1_700_000_000_000L,

            hasBalance = true,

            balance = 0.0

        ),

        categoryId = categoryId, type = "EXPENSE",

        balance = 0.0, hasBalance = true

    )



    private fun makeException(id: Long, pattern: String) =

        ImportExceptionEntity(id = id, patternRaw = pattern, normalizedPattern = pattern,

            matchType = MatchType.CONTAINS, isEnabled = true, createdAt = 1_700_000_000_000L)



    // ── BK01 — roundtrip completo ─────────────────────────────────────────────



    @Test

    fun `BK01 - roundtrip completo preserva todos los campos`() {

        val cat    = makeCat(1L, "Alimentación")

        val child  = makeCat(2L, "Supermercado", parentId = 1L)

        val rule   = makeRule(10L, "mercadona", categoryId = 2L)

        val tx     = makeTx(100L, categoryId = 2L)

        val exc    = makeException(5L, "amazon")



        val original = BackupFile(

            metadata      = makeMetadata(listOf("categories", "transactions", "exceptions")),

            categories    = listOf(cat, child),

            categoryRules = listOf(rule),

            transactions  = listOf(tx),

            importExceptions = listOf(exc)

        )



        val json   = BackupManager.createBackup(original)

        val parsed = BackupManager.parseBackup(json)



        // Metadata

        assertEquals("1.0", parsed.metadata.appVersion)

        assertEquals(BackupManager.SCHEMA_VERSION, parsed.metadata.schemaVersion)

        assertEquals(listOf("categories", "transactions", "exceptions"), parsed.metadata.includes)



        // Categorías

        assertEquals(2, parsed.categories?.size)

        val parsedCat = parsed.categories!!.first { it.id == 1L }

        assertEquals("Alimentación", parsedCat.name)

        assertNull(parsedCat.parentId)

        val parsedChild = parsed.categories!!.first { it.id == 2L }

        assertEquals(1L, parsedChild.parentId)



        // Reglas

        assertEquals(1, parsed.categoryRules?.size)

        val parsedRule = parsed.categoryRules!!.first()

        assertEquals(10L, parsedRule.id)

        assertEquals("mercadona", parsedRule.normalizedPattern)

        assertEquals(2L, parsedRule.categoryId)



        // Transacciones

        assertEquals(1, parsed.transactions?.size)

        val parsedTx = parsed.transactions!!.first()

        assertEquals(100L, parsedTx.id)

        assertEquals(2L, parsedTx.categoryId)



        // Excepciones

        assertEquals(1, parsed.importExceptions?.size)

        assertEquals("amazon", parsed.importExceptions!!.first().patternRaw)

    }



    // ── BK02 — parcial: solo categorías ──────────────────────────────────────



    @Test

    fun `BK02 - backup parcial solo categorias - transactions es null`() {

        val backup = BackupFile(

            metadata   = makeMetadata(listOf("categories")),

            categories = listOf(makeCat(1L, "Ocio"))

        )

        val parsed = BackupManager.parseBackup(BackupManager.createBackup(backup))



        assertNotNull(parsed.categories)

        assertNull(parsed.transactions)

        assertNull(parsed.importExceptions)

    }



    // ── BK03 — parcial: solo transacciones ───────────────────────────────────



    @Test

    fun `BK03 - backup parcial solo transacciones - categories es null`() {

        val backup = BackupFile(

            metadata     = makeMetadata(listOf("transactions")),

            transactions = listOf(makeTx(1L))

        )

        val parsed = BackupManager.parseBackup(BackupManager.createBackup(backup))



        assertNull(parsed.categories)

        assertNull(parsed.categoryRules)

        assertNotNull(parsed.transactions)

        assertNull(parsed.importExceptions)

    }



    // ── BK04 — roundtrip conserva IDs y referencias FK ───────────────────────



    @Test

    fun `BK04 - roundtrip conserva IDs y FKs de categoriaId en reglas y transacciones`() {

        val cat  = makeCat(42L, "Transporte")

        val rule = makeRule(7L, "metro", categoryId = 42L)

        val tx   = makeTx(999L, categoryId = 42L)



        val backup = BackupFile(

            metadata      = makeMetadata(listOf("categories", "transactions")),

            categories    = listOf(cat),

            categoryRules = listOf(rule),

            transactions  = listOf(tx)

        )



        val parsed = BackupManager.parseBackup(BackupManager.createBackup(backup))



        // IDs preservados exactamente

        assertEquals(42L, parsed.categories!!.first().id)

        assertEquals(7L,  parsed.categoryRules!!.first().id)

        assertEquals(999L, parsed.transactions!!.first().id)



        // FK: categoryId en regla y transacción sigue apuntando a la categoría correcta

        assertEquals(42L, parsed.categoryRules!!.first().categoryId)

        assertEquals(42L, parsed.transactions!!.first().categoryId)

    }



    // ── BK05 — schemaVersion > SCHEMA_VERSION ─────────────────────────────────



    @Test

    fun `BK05 - schemaVersion mayor que SCHEMA_VERSION retorna ValidationResult Error`() {

        val backup = BackupFile(

            metadata = makeMetadata(schema = BackupManager.SCHEMA_VERSION + 1)

        )

        val result = BackupManager.validateBackup(backup)

        assertTrue(result is BackupManager.ValidationResult.Error)

        val msg = (result as BackupManager.ValidationResult.Error).reason

        assertTrue(msg.contains("no soportada"))

    }



    // ── BK06 — JSON malformado ────────────────────────────────────────────────



    @Test(expected = IllegalArgumentException::class)

    fun `BK06 - JSON malformado lanza IllegalArgumentException`() {

        BackupManager.parseBackup("{ esto no es json válido :::}")

    }



    // ── BK07 — includes lista correcta ───────────────────────────────────────



    @Test

    fun `BK07 - includes refleja correctamente los flags del backup`() {

        val backup = BackupFile(

            metadata      = makeMetadata(listOf("categories", "exceptions")),

            categories    = listOf(makeCat(1L, "Test")),

            transactions  = null,

            importExceptions = listOf(makeException(1L, "spam"))

        )

        val parsed = BackupManager.parseBackup(BackupManager.createBackup(backup))



        assertTrue(parsed.metadata.includes.contains("categories"))

        assertTrue(parsed.metadata.includes.contains("exceptions"))

        assertFalse(parsed.metadata.includes.contains("transactions"))

        assertNull(parsed.transactions)

    }

}

