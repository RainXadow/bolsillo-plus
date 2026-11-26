package com.jesusluna.bolsilloplus.excel

import com.jesusluna.bolsilloplus.ExcelProcessor
import com.jesusluna.bolsilloplus.Transaction
import com.jesusluna.bolsilloplus.TransactionType
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.repository.TransactionRepository
import com.jesusluna.bolsilloplus.scoring.FakeCategoryRuleDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Tests unitarios del pipeline de importación de Excel.
 *
 * NO requieren Android ni Room — usan implementaciones fake en memoria.
 *
 * Grupos:
 *  A – parseAmount: formatos de importe bancario español
 *  B – parseDate: formatos de fecha habituales
 *  C – stableId: corrección del bug de colisión de IDs
 *  D – Pipeline TransactionRepository.importTransactions() end-to-end
 */
class ExcelImportTest {

    private lateinit var txDao:        FakeTransactionDao
    private lateinit var ruleDao:      FakeCategoryRuleDao
    private lateinit var exceptionDao: FakeImportExceptionDao
    private lateinit var skippedDao:   FakeSkippedTransactionDao
    private lateinit var catDao:       FakeCategoryDao
    private lateinit var repo:         TransactionRepository

    @Before
    fun setUp() {
        txDao        = FakeTransactionDao()
        ruleDao      = FakeCategoryRuleDao()
        exceptionDao = FakeImportExceptionDao()
        skippedDao   = FakeSkippedTransactionDao()
        catDao       = FakeCategoryDao()
        repo         = TransactionRepository(txDao, ruleDao, exceptionDao, skippedDao, catDao)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO A – parseAmount
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun A01_importe_espanol_miles_y_decimal() {
        assertEquals(1234.56, ExcelProcessor.parseAmount("1.234,56")!!, 0.001)
    }

    @Test fun A02_importe_con_simbolo_euro() {
        assertEquals(1234.56, ExcelProcessor.parseAmount("1.234,56 €")!!, 0.001)
    }

    @Test fun A03_importe_negativo_gasto() {
        assertEquals(-45.99, ExcelProcessor.parseAmount("-45,99")!!, 0.001)
    }

    @Test fun A04_importe_negativo_con_miles() {
        assertEquals(-1234.56, ExcelProcessor.parseAmount("-1.234,56")!!, 0.001)
    }

    @Test fun A05_importe_cero() {
        assertEquals(0.0, ExcelProcessor.parseAmount("0,00")!!, 0.0)
    }

    @Test fun A06_importe_entero_sin_decimal() {
        assertEquals(1000.0, ExcelProcessor.parseAmount("1.000")!!, 0.0)
    }

    @Test fun A07_importe_punto_decimal_anglosaxon() {
        // Algunos bancos exportan con punto decimal
        assertEquals(1000.5, ExcelProcessor.parseAmount("1000.5")!!, 0.001)
    }

    @Test fun A08_importe_con_espacios_extra() {
        assertEquals(250.0, ExcelProcessor.parseAmount("  250,00  ")!!, 0.001)
    }

    @Test fun A09_importe_con_EUR() {
        assertEquals(99.99, ExcelProcessor.parseAmount("99,99EUR")!!, 0.001)
    }

    @Test fun A10_importe_invalido_devuelve_null() {
        assertNull(ExcelProcessor.parseAmount("no es un número"))
    }

    @Test fun A11_importe_vacio_devuelve_null() {
        assertNull(ExcelProcessor.parseAmount(""))
    }

    @Test fun A12_importe_solo_simbolo_devuelve_null() {
        assertNull(ExcelProcessor.parseAmount("€"))
    }

    // Nuevos: solo coma (issue #2)
    @Test fun A13_importe_solo_coma_miles_anglosaxon_1000() {
        // "1,000" en formato anglosajón = 1000, NO 1.0
        assertEquals(1000.0, ExcelProcessor.parseAmount("1,000")!!, 0.001)
    }

    @Test fun A14_importe_solo_coma_miles_multiples_grupos() {
        assertEquals(1234567.0, ExcelProcessor.parseAmount("1,234,567")!!, 0.001)
    }

    @Test fun A15_importe_solo_coma_decimal_espanol_dos_decimales() {
        // "45,99" → coma es decimal → 45.99
        assertEquals(45.99, ExcelProcessor.parseAmount("45,99")!!, 0.001)
    }

    @Test fun A16_importe_solo_coma_decimal_espanol_un_decimal() {
        // "45,9" → 1 decimal → coma es decimal → 45.9
        assertEquals(45.9, ExcelProcessor.parseAmount("45,9")!!, 0.001)
    }

    @Test fun A17_importe_parentesis_con_miles_y_decimal() {
        assertEquals(-1234.56, ExcelProcessor.parseAmount("(1.234,56)")!!, 0.001)
    }

    @Test fun A18_importe_anglosaxon_coma_y_punto_1234_56() {
        assertEquals(1234.56, ExcelProcessor.parseAmount("1,234.56")!!, 0.001)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO B – parseDate
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun B01_fecha_formato_espanol_slash() {
        val d = ExcelProcessor.parseDate("15/03/2024")
        assertNotNull("Debe parsear dd/MM/yyyy", d)
        val cal = java.util.Calendar.getInstance().apply { time = d!! }
        assertEquals(15, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(2, cal.get(java.util.Calendar.MONTH))   // 0-indexed
        assertEquals(2024, cal.get(java.util.Calendar.YEAR))
    }

    @Test fun B02_fecha_formato_anyo_dos_digitos() {
        val d = ExcelProcessor.parseDate("15/03/24")
        assertNotNull("Debe parsear dd/MM/yy", d)
    }

    @Test fun B03_fecha_formato_iso() {
        val d = ExcelProcessor.parseDate("2024-03-15")
        assertNotNull("Debe parsear yyyy-MM-dd", d)
        val cal = java.util.Calendar.getInstance().apply { time = d!! }
        assertEquals(15, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(2024, cal.get(java.util.Calendar.YEAR))
    }

    @Test fun B04_fecha_formato_guiones() {
        val d = ExcelProcessor.parseDate("15-03-2024")
        assertNotNull("Debe parsear dd-MM-yyyy", d)
    }

    @Test fun B05_fecha_invalida_devuelve_null() {
        assertNull(ExcelProcessor.parseDate("no es fecha"))
    }

    @Test fun B06_fecha_vacia_devuelve_null() {
        assertNull(ExcelProcessor.parseDate(""))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO C – stableId: corrección del bug de colisión de IDs
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * BUG ORIGINAL documentado:
     * Transaction(id = System.currentTimeMillis()) en un bucle rápido → colisión de PK.
     * Al hacer upsertAll(), Room reemplaza cada colisión → pérdida silenciosa de movimientos.
     */
    @Test fun C01_bug_original_System_currentTimeMillis_colisiona() {
        // Simula la creación rápida de muchas transacciones (como en el bucle de parseRow)
        val ids = (1..100).map { System.currentTimeMillis() }.toSet()
        // Con el bug, la mayoría comparten el mismo milisegundo → pocos IDs únicos
        assertTrue(
            "BUG documentado: muchas transacciones creadas en el mismo ms comparten ID. " +
            "IDs únicos generados: ${ids.size} de 100 esperados.",
            ids.size < 100
        )
    }

    @Test fun C02_stableId_diferente_concepto_da_diferente_id() {
        val d = Date(1000000L)
        val id1 = ExcelProcessor.stableId(d, "Mercadona", -50.0)
        val id2 = ExcelProcessor.stableId(d, "Amazon",    -50.0)
        assertNotEquals("Conceptos distintos → IDs distintos", id1, id2)
    }

    @Test fun C03_stableId_diferente_fecha_da_diferente_id() {
        val id1 = ExcelProcessor.stableId(Date(1_000_000L), "Mercadona", -50.0)
        val id2 = ExcelProcessor.stableId(Date(2_000_000L), "Mercadona", -50.0)
        assertNotEquals("Fechas distintas → IDs distintos", id1, id2)
    }

    @Test fun C04_stableId_diferente_importe_da_diferente_id() {
        val d = Date(1_000_000L)
        val id1 = ExcelProcessor.stableId(d, "Mercadona", -50.0)
        val id2 = ExcelProcessor.stableId(d, "Mercadona", -51.0)
        assertNotEquals("Importes distintos → IDs distintos", id1, id2)
    }

    @Test fun C05_stableId_misma_ocurrencia_da_mismo_id() {
        // Misma (fecha, concepto, importe, occurrenceIndex) → mismo ID (reproducible)
        val d = Date(1_000_000L)
        val id1 = ExcelProcessor.stableId(d, "Mercadona", -50.0, 0)
        val id2 = ExcelProcessor.stableId(d, "Mercadona", -50.0, 0)
        assertEquals("Mismos inputs → mismo ID (determinista)", id1, id2)
    }

    @Test fun C05b_stableId_diferente_ocurrencia_da_diferente_id() {
        // Mismo (fecha, concepto, importe) pero distinto occurrenceIndex → IDs distintos
        val d = Date(1_000_000L)
        val id0 = ExcelProcessor.stableId(d, "Mercadona", -50.0, 0)
        val id1 = ExcelProcessor.stableId(d, "Mercadona", -50.0, 1)
        assertNotEquals("Distinto occurrenceIndex → IDs distintos (movimientos legítimos)", id0, id1)
    }

    @Test fun C06_stableId_1789_transacciones_unicas_todas_tienen_id_unico() {
        // El test crítico: simula las 1789 transacciones del usuario
        val ids = (1..1789).map { i ->
            ExcelProcessor.stableId(Date(1_000_000L * i), "Concepto $i", -i.toDouble())
        }.toSet()
        assertEquals(
            "1789 transacciones únicas deben producir 1789 IDs distintos. " +
            "Con System.currentTimeMillis() esto fallaría.",
            1789,
            ids.size
        )
    }

    @Test fun C07_stableId_no_devuelve_cero() {
        // Room puede tener comportamiento especial con id=0 en ciertas configs
        val id = ExcelProcessor.stableId(Date(0L), "", 0.0)
        assertNotEquals("stableId no debe devolver 0", 0L, id)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO D – Pipeline TransactionRepository.importTransactions()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D01_importar_1_transaccion_unica_la_anade() = runBlocking {
        val (added, duplicates, skipped) = repo.importTransactions(listOf(tx(1)))
        assertEquals(1, added)
        assertEquals(0, duplicates)
        assertEquals(0, skipped)
        assertEquals(1, txDao.storedCount())
    }

    @Test fun D02_reimportar_misma_transaccion_es_duplicado() = runBlocking {
        val list = listOf(tx(1))
        repo.importTransactions(list)
        val (added, duplicates, _) = repo.importTransactions(list)
        assertEquals("Re-importar misma tx → duplicado, no añadida", 0, added)
        assertEquals(1, duplicates)
        assertEquals("Solo debe haber 1 registro en DB", 1, txDao.storedCount())
    }

    @Test fun D03_lote_mixto_nuevas_y_duplicadas() = runBlocking {
        repo.importTransactions(listOf(tx(1), tx(2)))
        val nuevas   = listOf(tx(3), tx(4))
        val viejas   = listOf(tx(1), tx(2))
        val (added, duplicates, _) = repo.importTransactions(nuevas + viejas)
        assertEquals(2, added)
        assertEquals(2, duplicates)
        assertEquals(4, txDao.storedCount())
    }

    @Test fun D04_1789_transacciones_unicas_todas_se_anaden() = runBlocking {
        val transactions = (1..1789).map { i ->
            tx(i, concept = "Concepto único $i", amount = -i.toDouble())
        }
        val (added, duplicates, skipped) = repo.importTransactions(transactions)
        assertEquals(
            "Deben añadirse las 1789 transacciones. " +
            "Si falla, hay colisión de IDs (bug de System.currentTimeMillis).",
            1789, added
        )
        assertEquals(0, duplicates)
        assertEquals(0, skipped)
        assertEquals(1789, txDao.storedCount())
    }

    @Test fun D05_mismo_concepto_y_fecha_diferente_importe_ambas_se_anaden() = runBlocking {
        // Caso real: dos cobros a la misma tienda el mismo día por diferente importe
        val base = Date(1_000_000L)
        val t1 = makeTx(base, "Mercadona", -20.0)
        val t2 = makeTx(base, "Mercadona", -35.5)
        val (added, _, _) = repo.importTransactions(listOf(t1, t2))
        assertEquals("Mismo concepto + fecha pero importes distintos → 2 movimientos", 2, added)
    }

    @Test fun D06_transacciones_identicas_en_mismo_batch_ambas_se_importan() = runBlocking {
        // Dos filas idénticas (mismo concepto, fecha e importe) en el mismo Excel
        // representan movimientos distintos y reales (ej: dos peajes el mismo día al mismo precio).
        // Solo son duplicados si ya existen en BD — dentro del mismo batch NO se descuentan entre sí.
        val base = Date(1_000_000L)
        val t1 = makeTx(base, "Mercadona", -20.0)
        val t2 = makeTx(base, "Mercadona", -20.0) // idéntica a t1
        val (added, duplicates, _) = repo.importTransactions(listOf(t1, t2))
        assertEquals("Idénticas en mismo batch → ambas importadas (BD vacía)", 2, added)
        assertEquals(0, duplicates)
        assertEquals(2, txDao.storedCount())
    }

    @Test fun D06b_reimportar_mismo_excel_con_identicas_no_duplica() = runBlocking {
        // Primera importación: Excel con 2 filas idénticas → ambas nuevas
        val base = Date(1_000_000L)
        val t1 = makeTx(base, "Mercadona", -20.0)
        val t2 = makeTx(base, "Mercadona", -20.0)
        val batch = listOf(t1, t2)

        val (added1, dup1, _) = repo.importTransactions(batch)
        assertEquals("Primera importación → 2 nuevas", 2, added1)
        assertEquals(0, dup1)
        assertEquals(2, txDao.storedCount())

        // Segunda importación del mismo Excel: BD ya tiene 2 → ambas son duplicados
        val (added2, dup2, _) = repo.importTransactions(batch)
        assertEquals("Re-importar mismo Excel → ninguna nueva", 0, added2)
        assertEquals("Re-importar mismo Excel → 2 duplicados detectados", 2, dup2)
        assertEquals("Sigue habiendo exactamente 2 registros en BD", 2, txDao.storedCount())
    }

    @Test fun D07_excepcion_activa_excluye_transaccion_y_la_registra() = runBlocking {
        exceptionDao.addException(
            ImportExceptionEntity(patternRaw = "recibo luz", normalizedPattern = "recibo luz", matchType = MatchType.CONTAINS)
        )
        val t = makeTx(Date(1_000_000L), "Recibo Luz Endesa", -80.0)
        val (added, _, skipped) = repo.importTransactions(listOf(t))
        assertEquals(0, added)
        assertEquals(1, skipped)
        assertEquals(0, txDao.storedCount())
        assertEquals("El movimiento excluido debe quedar registrado en skipped_transactions", 1, skippedDao.all().size)
    }

    @Test fun D08_excepcion_desactivada_no_excluye() = runBlocking {
        exceptionDao.addException(
            ImportExceptionEntity(patternRaw = "recibo luz", normalizedPattern = "recibo luz", matchType = MatchType.CONTAINS, isEnabled = false)
        )
        val t = makeTx(Date(1_000_000L), "Recibo Luz Endesa", -80.0)
        val (added, _, skipped) = repo.importTransactions(listOf(t))
        assertEquals("Excepción desactivada → transacción debe importarse", 1, added)
        assertEquals(0, skipped)
    }

    @Test fun D09_excepcion_exact_solo_coincide_exacto() = runBlocking {
        exceptionDao.addException(
            ImportExceptionEntity(patternRaw = "recibo luz endesa", normalizedPattern = "recibo luz endesa", matchType = MatchType.EXACT)
        )
        val exacta  = makeTx(Date(1L), "Recibo Luz Endesa", -80.0)
        val parcial = makeTx(Date(2L), "Recibo Luz Endesa Factura 2024", -80.0)

        val (added, _, skipped) = repo.importTransactions(listOf(exacta, parcial))
        assertEquals("EXACT solo debe excluir la coincidencia exacta", 1, added)
        assertEquals(1, skipped)
    }

    @Test fun D10_dedup_ocurre_antes_de_excepcion_restaurados_no_se_reexcluyen() = runBlocking {
        // Simula el flujo: 1) tx importada y restaurada → ya está en DB
        // 2) Re-importar con excepción activa → debe tratarse como duplicado, no como excluida
        val d = Date(1_000_000L)
        val t = makeTx(d, "Recibo Luz", -80.0)

        // Pre-cargar en DB (simula que fue restaurada)
        txDao.upsertAll(listOf(
            com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity(
                id = ExcelProcessor.stableId(d, "Recibo Luz", -80.0),
                date = d.time, amount = -80.0, concept = "Recibo Luz",
                normalizedConcept = "recibo luz", categoryId = null,
                dedupKey = "recibo luz|-80.00|19700101|0.00",
                type = "EXPENSE", balance = 0.0, hasBalance = true
            )
        ))

        exceptionDao.addException(
            ImportExceptionEntity(patternRaw = "recibo luz", normalizedPattern = "recibo luz", matchType = MatchType.CONTAINS)
        )

        val (added, duplicates, skipped) = repo.importTransactions(listOf(t))
        assertEquals("Ya está en DB → debe ser duplicado, no excluida de nuevo", 0, added)
        assertEquals(1, duplicates)
        assertEquals("No debe registrarse como excluida si ya existe en DB", 0, skipped)
    }

    @Test fun D11_excepcion_regex_coincide_correctamente() = runBlocking {
        exceptionDao.addException(
            ImportExceptionEntity(patternRaw = "recibo (luz|gas|agua)", normalizedPattern = "recibo (luz|gas|agua)", matchType = MatchType.REGEX)
        )
        val luz  = makeTx(Date(1L), "Recibo Luz",      -80.0)
        val gas  = makeTx(Date(2L), "Recibo Gas",      -40.0)
        val otro = makeTx(Date(3L), "Cuota Gimnasio",  -10.0)

        val (added, _, skipped) = repo.importTransactions(listOf(luz, gas, otro))
        assertEquals("Regex excluye 2, deja pasar 1", 1, added)
        assertEquals(2, skipped)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO D (continuación) – casos de borde dedup / excepciones / ciclos
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * D06c — Dedup parcial: la BD ya tiene 1 copia; el batch trae 2 del mismo movimiento.
     * La lógica de ocurrencias debe insertar 1 (la "segunda copia legítima") y marcar 1 como dup.
     *
     * Flujo interno de importTransactions:
     *   batchOcc=0, existingCount=1 → 0 < 1 → DUPLICATE (la primera del batch coincide con la BD)
     *   batchOcc=1, existingCount=1 → 1 ≥ 1 → INSERT  (la segunda copia es genuinamente nueva)
     * Resultado: BD pasa de 1 a 2 copias.
     */
    @Test fun D06c_DB_tiene_1_copia_batch_trae_2_del_mismo_inserta_1_duplica_1() = runBlocking {
        val base = Date(1_000_000L)
        // Primera importación: la BD queda con 1 copia
        repo.importTransactions(listOf(makeTx(base, "Mercadona", -20.0)))
        assertEquals(1, txDao.storedCount())

        // Segunda importación con 2 copias del mismo movimiento
        val t1 = makeTx(base, "Mercadona", -20.0)
        val t2 = makeTx(base, "Mercadona", -20.0) // idéntica a t1
        val (added, duplicates, skipped) = repo.importTransactions(listOf(t1, t2))

        assertEquals("BD=1, batch=2 del mismo → solo 1 es nueva", 1, added)
        assertEquals("La que ya existía en BD → duplicada", 1, duplicates)
        assertEquals(0, skipped)
        assertEquals("La BD debe tener ahora 2 copias legítimas", 2, txDao.storedCount())
    }

    /**
     * D12 — Summary mixto: en el mismo batch coexisten duplicados Y movimientos excluidos
     * por excepción. Los tres contadores deben reflejar su parte exacta.
     *
     * Batch: [txA duplicada, txB nueva, txC excluida por excepción]
     * Esperado: added=1, duplicates=1, skipped=1
     */
    @Test fun D12_summary_mixto_added_duplicates_skipped_en_mismo_batch() = runBlocking {
        // Pre-seed: txA ya existe en BD
        repo.importTransactions(listOf(makeTx(Date(1_000_000L), "Mercadona", -20.0)))

        // Excepción CONTAINS para txC
        exceptionDao.addException(
            ImportExceptionEntity(
                patternRaw = "netflix", normalizedPattern = "netflix",
                matchType = MatchType.CONTAINS, isEnabled = true, createdAt = 0L
            )
        )

        val txA = makeTx(Date(1_000_000L), "Mercadona",          -20.0)  // dup
        val txB = makeTx(Date(2_000_000L), "Amazon Prime",        -15.0)  // nueva
        val txC = makeTx(Date(3_000_000L), "Netflix Subscription", -10.0) // excluida

        val (added, duplicates, skipped) = repo.importTransactions(listOf(txA, txB, txC))

        assertEquals("txB es nueva", 1, added)
        assertEquals("txA ya está en BD", 1, duplicates)
        assertEquals("txC coincide con excepción CONTAINS", 1, skipped)
        assertEquals(2, txDao.storedCount())           // Mercadona + Amazon Prime
        assertEquals(1, skippedDao.all().size)         // Netflix en skipped_transactions
    }

    /**
     * D13 — Excepción CONTAINS_WORD no está soportada en el matcher de excepciones.
     *
     * findMatchingException() retorna false para CONTAINS_WORD (con Log.w).
     * Si el usuario crea una excepción de este tipo desde la UI, NO filtrará nada.
     *
     * COMPORTAMIENTO DOCUMENTADO (no es el esperado por el usuario):
     *   - Excepción tipo CONTAINS_WORD existe en BD y está activa.
     *   - Movimiento con el concepto que contiene la palabra → se importa igualmente.
     *
     * FIX sugerido (no implementado aquí):
     *   - Normalizar CONTAINS_WORD → CONTAINS al guardar una excepción, o
     *   - Implementar matching por \b en findMatchingException().
     */
    @Test fun D13_excepcion_CONTAINS_WORD_no_filtra_es_silenciosa() = runBlocking {
        exceptionDao.addException(
            ImportExceptionEntity(
                patternRaw = "nómina", normalizedPattern = "nómina",
                matchType = MatchType.CONTAINS_WORD, isEnabled = true, createdAt = 0L
            )
        )

        // "nómina empresa" contiene la palabra "nómina" — debería excluirse si CONTAINS_WORD funcionara
        val t = makeTx(Date(1_000_000L), "NÓMINA EMPRESA", 1200.0)
        val (added, _, skipped) = repo.importTransactions(listOf(t))

        // COMPORTAMIENTO ACTUAL: CONTAINS_WORD devuelve false → tx no se excluye
        assertEquals("CONTAINS_WORD en excepciones no se aplica: tx importada igual", 1, added)
        assertEquals("No se registra como excluida", 0, skipped)
        // Si el fix se aplica, este test debe cambiar a: added=0, skipped=1
    }

    /**
     * D14 — Regla REGEX en categorización automática durante importación.
     *
     * findAllMatchingRules() recorre getAllRegexRules() correctamente.
     * Una regla REGEX activa debe asignar categoryId a los movimientos que encajan.
     */
    @Test fun D14_regla_REGEX_categoriza_en_import() = runBlocking {
        ruleDao.insertRule(
            CategoryRuleEntity(
                normalizedPattern = "^recibo.*(luz|gas|agua)",
                categoryId = 5L,
                matchType = MatchType.REGEX,
                priority = MatchType.REGEX.defaultPriority,
                createdAt = 0L
            )
        )

        val luz  = makeTx(Date(1_000_000L), "Recibo Luz Iberdrola", -80.0)
        val gas  = makeTx(Date(2_000_000L), "Recibo Gas Natural",   -40.0)
        val otro = makeTx(Date(3_000_000L), "Cuota Gimnasio",       -30.0)

        repo.importTransactions(listOf(luz, gas, otro))

        val stored = txDao.getAllEntities()
        assertEquals(3, stored.size)

        val luzStored  = stored.first { it.concept == "Recibo Luz Iberdrola" }
        val gasStored  = stored.first { it.concept == "Recibo Gas Natural" }
        val otroStored = stored.first { it.concept == "Cuota Gimnasio" }

        assertEquals("REGEX debe categorizar 'Recibo Luz'", 5L, luzStored.categoryId)
        assertEquals("REGEX debe categorizar 'Recibo Gas'", 5L, gasStored.categoryId)
        assertNull("'Cuota Gimnasio' no coincide con el REGEX", otroStored.categoryId)
    }

    /**
     * D15 — Ciclo completo skip → restore → reimport.
     *
     * 1) Importar con excepción activa → tx va a skipped_transactions (no a transactions).
     * 2) Restaurar la tx → pasa a transactions; se le aplican reglas de categorización.
     * 3) Re-importar el mismo movimiento con excepción aún activa:
     *    El dedup ocurre ANTES de la excepción → el movimiento ya existe en BD → DUPLICATE.
     *    No se re-excluye ni se registra de nuevo en skipped_transactions.
     */
    @Test fun D15_ciclo_skip_restore_reimport_dedup() = runBlocking {
        exceptionDao.addException(
            ImportExceptionEntity(
                patternRaw = "netflix", normalizedPattern = "netflix",
                matchType = MatchType.CONTAINS, isEnabled = true, createdAt = 0L
            )
        )

        val t = makeTx(Date(1_000_000L), "Netflix Subscription", -15.0)

        // Paso 1: importar → va a skipped
        val (added1, dups1, skipped1) = repo.importTransactions(listOf(t))
        assertEquals(0, added1)
        assertEquals(0, dups1)
        assertEquals(1, skipped1)
        assertEquals(0, txDao.storedCount())
        assertEquals(1, skippedDao.all().size)

        // Paso 2: restaurar → pasa a transactions
        val skippedTx = skippedDao.all().first()
        repo.restoreSkippedTransaction(skippedTx)
        assertEquals(1, txDao.storedCount())
        assertEquals(0, skippedDao.all().size)

        // Paso 3: re-importar con excepción aún activa
        // Dedup ocurre antes → existingCount=1 para este dedupKey → DUPLICATE
        val (added2, dups2, skipped2) = repo.importTransactions(listOf(t))
        assertEquals("Ya está en BD tras restore → duplicado, no re-excluida", 0, added2)
        assertEquals(1, dups2)
        assertEquals("No debe re-excluirse lo que ya existe en BD", 0, skipped2)
        assertEquals("Conteo en BD no debe cambiar", 1, txDao.storedCount())
        assertEquals("skipped_transactions sigue vacío", 0, skippedDao.all().size)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO E – detectHeaderRow + buildColumnMapping (lógica universal)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun E01_detecta_cabecera_santander_xlsx() {
        // Simula un extracto .xlsx tipo Santander: filas previas de metadatos, cabecera en fila 7 (índice 7)
        val rows = listOf(
            listOf("", "", "Cuenta", "Fecha"),
            listOf("", "", "ES00 0000...", "10/10/2025"),
            listOf("", "", "Titular", "Saldo"),
            listOf("", "", "TITULAR DE PRUEBA", "100,00 EUR"),
            listOf(),
            listOf("", "", "Movimientos"),
            listOf(),
            listOf("Fecha operación", "Fecha valor", "Concepto", "Importe", "Saldo", "Divisa"),
            listOf("30/09/2025", "30/09/2025", "TRASPASO:", "-50,00€", "1.000,00€", "EUR")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertEquals("Cabecera debe ser fila 7", 7, idx)
    }

    @Test fun E02_detecta_cabecera_bbva_xlsx() {
        // Simula un extracto .xlsx tipo BBVA: header en fila 4 (índice 4)
        val rows = listOf(
            listOf(),
            listOf(),
            listOf("", "", "Movimientos"),
            listOf("", "", "Fecha de generación del informe: 07/03/2026"),
            listOf("Fecha valor", "Fecha", "Concepto", "Movimiento", "Importe", "Divisa", "Disponible"),
            listOf("07/03/2026", "09/03/2026", "MERCADONA", "PAGO CON TARJETA", "-10.00", "EUR", "1000.00")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertEquals("Cabecera debe ser fila 4", 4, idx)
    }

    @Test fun E03_detecta_cabecera_caixabank_csv() {
        // Simula un extracto .csv tipo CaixaBank (con ';' ya separado): header en fila 2 (índice 2)
        val rows = listOf(
            listOf("IBAN", "Saldo disponible", "Periodo"),
            listOf("ES0000...", "1.000,00", "01/03/2026 - 07/03/2026"),
            listOf("Concepto", "Fecha", "Importe", "Saldo disponible"),
            listOf("COMERCIO DE PRUEBA", "06/03/2026", "-5,00EUR", "1.000,00EUR")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertEquals("Cabecera debe ser fila 2", 2, idx)
    }

    @Test fun E04_fila_solo_IBAN_no_es_cabecera() {
        val rows = listOf(
            listOf("IBAN", "Saldo disponible", "Periodo"),
            listOf("ES0000...", "1.000,00", "01/03/2026 - 07/03/2026")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertNull("Fila de IBAN no debe detectarse como cabecera", idx)
    }

    @Test fun E04b_fecha_mas_importe_sin_concepto_no_es_cabecera() {
        // Falso positivo clásico: fila de metadatos con "Fecha" e "Importe" pero sin columna de concepto
        val rows = listOf(
            listOf("Fecha", "Importe"),           // score=2 pero sin concepto → debe rechazarse
            listOf("01/01/2024", "1.000,00")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertNull("Fecha+Importe sin concepto NO debe detectarse como cabecera", idx)
    }

    @Test fun E04c_fecha_mas_concepto_sin_importe_si_es_cabecera() {
        // score=2 con concepto presente → válido aunque falte importe en header
        // (mapping fallará después, pero la detección de header sí debe devolver fila)
        val rows = listOf(
            listOf("Fecha", "Concepto"),
            listOf("01/01/2024", "Mercadona")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertNotNull("Fecha+Concepto (score=2 con concepto) sí es cabecera potencial", idx)
        assertEquals(0, idx)
    }

    @Test fun E05_mapping_santander_fecha_operacion_prioridad_sobre_valor() {
        // "Fecha operación" debe ganar sobre "Fecha valor"
        val headers = listOf("Fecha operación", "Fecha valor", "Concepto", "Importe", "Saldo", "Divisa")
        val m = ExcelProcessor.buildColumnMapping(headers)
        assertNotNull(m)
        assertEquals("fecha operación = índice 0", 0, m!!.fechaIdx)
        assertEquals("concepto = índice 2", 2, m.conceptoIdx)
        assertEquals("importe = índice 3", 3, m.importeIdx)
        assertEquals("saldo = índice 4", 4, m.saldoIdx)
    }

    @Test fun E06_mapping_bbva_fecha_plain_prioridad_sobre_valor() {
        // "Fecha" (plain) debe ganar sobre "Fecha valor"
        val headers = listOf("Fecha valor", "Fecha", "Concepto", "Movimiento", "Importe", "Divisa", "Disponible")
        val m = ExcelProcessor.buildColumnMapping(headers)
        assertNotNull(m)
        assertEquals("'Fecha' plain (idx 1) debe ganar sobre 'Fecha valor' (idx 0)", 1, m!!.fechaIdx)
        assertEquals("concepto = índice 2", 2, m.conceptoIdx)
        assertEquals("importe = índice 4", 4, m.importeIdx)
    }

    @Test fun E07_mapping_caixabank_csv() {
        val headers = listOf("Concepto", "Fecha", "Importe", "Saldo disponible")
        val m = ExcelProcessor.buildColumnMapping(headers)
        assertNotNull(m)
        assertEquals("fecha = índice 1", 1, m!!.fechaIdx)
        assertEquals("concepto = índice 0", 0, m.conceptoIdx)
        assertEquals("importe = índice 2", 2, m.importeIdx)
        assertEquals("saldo = índice 3", 3, m.saldoIdx)
    }

    @Test fun E08_mapping_cargo_abono_columnas_separadas() {
        val headers = listOf("Fecha", "Descripcion", "Cargo", "Abono", "Saldo")
        val m = ExcelProcessor.buildColumnMapping(headers)
        assertNotNull(m)
        assertNull("importeIdx debe ser null (modo cargo/abono)", m!!.importeIdx)
        assertEquals(2, m.cargoIdx)
        assertEquals(3, m.abonoIdx)
    }

    @Test fun E09_mapping_falta_importe_retorna_null() {
        val headers = listOf("Fecha", "Concepto", "Saldo")
        val m = ExcelProcessor.buildColumnMapping(headers)
        assertNull("Sin importe/cargo/abono → null", m)
    }

    @Test fun E10_parseAmount_parentesis_negativo() {
        assertEquals(-123.45, ExcelProcessor.parseAmount("(123,45)")!!, 0.001)
    }

    @Test fun E11_parseAmount_anglosaxon_miles_y_decimal() {
        assertEquals(1234.56, ExcelProcessor.parseAmount("1,234.56")!!, 0.001)
    }

    @Test fun E12_parseAmount_con_EUR_pegado() {
        assertEquals(-6.95, ExcelProcessor.parseAmount("-6,95EUR")!!, 0.001)
    }

    @Test fun E13_parseDate_con_hora_separada_por_pipe() {
        val d = ExcelProcessor.parseDate("10/10/2025 | 12:44:11")
        assertNotNull("Fecha con '|' debe parsearse", d)
    }

    @Test fun E14_detectDelimiter_semicolon() {
        assertEquals(';', ExcelProcessor.detectDelimiter("IBAN;Saldo disponible;Periodo"))
    }

    @Test fun E15_detectDelimiter_tab() {
        assertEquals('\t', ExcelProcessor.detectDelimiter("Fecha\tConcepto\tImporte"))
    }

    @Test fun E16_detectDelimiter_pipe() {
        assertEquals('|', ExcelProcessor.detectDelimiter("Fecha|Concepto|Importe|Saldo"))
    }

    @Test fun E17_detectDelimiter_comma_fallback() {
        assertEquals(',', ExcelProcessor.detectDelimiter("Fecha,Concepto,Importe"))
    }

    // ── resolveDebitCredit (issue #3) ─────────────────────────────────────────

    @Test fun E18_resolveDebitCredit_solo_cargo_da_negativo() {
        val result = ExcelProcessor.resolveDebitCredit("30,00", "")
        assertNotNull(result)
        assertEquals(-30.0, result!!, 0.001)
    }

    @Test fun E19_resolveDebitCredit_solo_abono_da_positivo() {
        val result = ExcelProcessor.resolveDebitCredit("", "100,00")
        assertNotNull(result)
        assertEquals(100.0, result!!, 0.001)
    }

    @Test fun E20_resolveDebitCredit_ambos_presentes_neto() {
        // Devolución parcial: cargo=30, abono=10 → net = 10-30 = -20
        val result = ExcelProcessor.resolveDebitCredit("30,00", "10,00")
        assertNotNull(result)
        assertEquals(-20.0, result!!, 0.001)
    }

    @Test fun E21_resolveDebitCredit_ambos_vacios_retorna_null() {
        assertNull(ExcelProcessor.resolveDebitCredit("", ""))
    }

    @Test fun E22_resolveDebitCredit_cargo_con_miles() {
        val result = ExcelProcessor.resolveDebitCredit("1.234,56", "")
        assertEquals(-1234.56, result!!, 0.001)
    }

    // Banco que ya pone el signo negativo en la columna Cargo
    @Test fun E22b_resolveDebitCredit_cargo_ya_negativo_queda_negativo() {
        // Algunos bancos: Cargo="-30,00" (ya negativo) → resultado = -30.0 (no +30)
        val result = ExcelProcessor.resolveDebitCredit("-30,00", "")
        assertEquals(-30.0, result!!, 0.001)
    }

    // Banco que ya pone el signo positivo en la columna Abono
    @Test fun E22c_resolveDebitCredit_abono_ya_positivo_queda_positivo() {
        val result = ExcelProcessor.resolveDebitCredit("", "100,00")
        assertEquals(100.0, result!!, 0.001)
    }

    // Ambos con magnitudes: devolución parcial
    @Test fun E22d_resolveDebitCredit_ambos_con_magnitudes_neto() {
        // cargo=30, abono=10 → 10-30 = -20 (sigue siendo gasto neto)
        val result = ExcelProcessor.resolveDebitCredit("30,00", "10,00")
        assertEquals(-20.0, result!!, 0.001)
    }

    // Cargo="-30" (negativo) y abono="" → -abs(-30) = -30 (no +30)
    @Test fun E22e_resolveDebitCredit_cargo_negativo_abono_vacio_correcto() {
        assertEquals(-30.0, ExcelProcessor.resolveDebitCredit("-30,00", "")!!, 0.001)
    }

    // Abono con signo raro (negativo) → abs lo convierte a positivo
    @Test fun E22f_resolveDebitCredit_abono_negativo_se_convierte_a_positivo() {
        // Banco inusual: Abono="-50" → abs = 50 (interpretamos como magnitud de ingreso)
        assertEquals(50.0, ExcelProcessor.resolveDebitCredit("", "-50,00")!!, 0.001)
    }

    // Ambos fallidos → null
    @Test fun E22g_resolveDebitCredit_ambos_no_parseables_retorna_null() {
        assertNull(ExcelProcessor.resolveDebitCredit("abc", "def"))
    }

    // ── Sinónimos adicionales de concepto (revisión C) ────────────────────────

    @Test fun E28_detecta_header_con_texto_como_concepto() {
        // "Texto" — Bankinter y similares
        val rows = listOf(
            listOf("Fecha", "Texto", "Importe"),
            listOf("01/01/2024", "MERCADONA", "-25,00")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertNotNull("'Texto' debe reconocerse como familia concepto", idx)
        val m = ExcelProcessor.buildColumnMapping(rows[idx!!])
        assertNotNull(m)
        assertEquals(1, m!!.conceptoIdx) // columna "Texto"
    }

    @Test fun E29_detecta_header_con_observaciones_como_concepto() {
        val rows = listOf(
            listOf("Fecha", "Importe", "Observaciones"),
            listOf("01/01/2024", "-12,50", "LIDL PAGO TARJETA")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertNotNull("'Observaciones' debe reconocerse como familia concepto", idx)
    }

    @Test fun E30_referencia_no_cuenta_en_scoring_ni_en_mapping() {
        // "Referencia" fue eliminada de FAMILIA_CONCEPTO para evitar falsos positivos.
        // La detección de cabecera funciona porque "Concepto" sí está en FAMILIA_CONCEPTO.
        // En el mapping, "Concepto" gana frente a "Referencia" (que no está en CONCEPTO_KW).
        val rows = listOf(
            listOf("Fecha", "Referencia", "Concepto", "Importe"),
            listOf("01/01/2024", "REF001", "AMAZON", "-99,00")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertNotNull(idx)
        val m = ExcelProcessor.buildColumnMapping(rows[idx!!])
        assertNotNull(m)
        assertEquals("Debe mapear 'Concepto' (idx 2), no 'Referencia' (idx 1)", 2, m!!.conceptoIdx)
    }

    @Test fun E30b_fecha_referencia_importe_sin_concepto_no_es_header() {
        // Fila con "Referencia" pero sin ninguna keyword de concepto real → NO debe detectarse
        // como cabecera (falso positivo que existía cuando "referencia" estaba en FAMILIA_CONCEPTO)
        val rows = listOf(
            listOf("Fecha", "Referencia", "Importe"),
            listOf("01/01/2024", "REF001", "-99,00")
        )
        val idx = ExcelProcessor.detectHeaderRow(rows)
        assertNull("No debe detectar cabecera: falta columna de concepto real", idx)
    }

    // ── Signos correctos end-to-end (revisión B) ─────────────────────────────

    @Test fun E31_gasto_queda_negativo_y_tipo_EXPENSE() = runBlocking {
        val tx = makeTx(Date(1_000_000L), "MERCADONA", -25.0)
        repo.importTransactions(listOf(tx))
        val stored = txDao.getAllEntities().first()
        assertTrue("Importe debe ser negativo", stored.amount < 0)
        assertEquals("Tipo debe ser EXPENSE", "EXPENSE", stored.type)
    }

    @Test fun E32_ingreso_queda_positivo_y_tipo_INCOME() = runBlocking {
        val tx = makeTx(Date(1_000_000L), "NOMINA EMPRESA", 1500.0)
        repo.importTransactions(listOf(tx))
        val stored = txDao.getAllEntities().first()
        assertTrue("Importe debe ser positivo", stored.amount > 0)
        assertEquals("Tipo debe ser INCOME", "INCOME", stored.type)
    }

    @Test fun E33_parseAmount_gasto_con_euro_pegado_negativo() {
        // Caso real Santander: "-700,00€"
        val result = ExcelProcessor.parseAmount("-700,00€")
        assertNotNull(result)
        assertEquals(-700.0, result!!, 0.001)
        assertTrue("Resultado debe ser negativo", result < 0)
    }

    @Test fun E34_parseAmount_gasto_con_EUR_pegado_negativo() {
        // Caso real CaixaBank: "-6,95EUR"
        val result = ExcelProcessor.parseAmount("-6,95EUR")
        assertNotNull(result)
        assertEquals(-6.95, result!!, 0.001)
        assertTrue("Resultado debe ser negativo", result < 0)
    }

    @Test fun E35_parseAmount_ingreso_con_EUR_pegado_positivo() {
        // Ingreso: "100,00EUR"
        val result = ExcelProcessor.parseAmount("100,00EUR")
        assertNotNull(result)
        assertEquals(100.0, result!!, 0.001)
        assertTrue("Resultado debe ser positivo", result > 0)
    }

    // ── detectCharset (issue #1) ──────────────────────────────────────────────

    @Test fun E23_detectCharset_utf8_puro() {
        val bytes = "Concepto;Fecha;Importe".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, ExcelProcessor.detectCharset(bytes))
    }

    @Test fun E24_detectCharset_utf8_con_acentos() {
        val bytes = "Comisión;Café;Nómina".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, ExcelProcessor.detectCharset(bytes))
    }

    @Test fun E25_detectCharset_iso_8859_1_da_fallback() {
        // "ó" en ISO-8859-1 = 0xF3, inválido como UTF-8 → debe detectar ISO_8859_1
        val bytes = "Comisi\u00F3n".toByteArray(Charsets.ISO_8859_1)
        assertEquals(Charsets.ISO_8859_1, ExcelProcessor.detectCharset(bytes))
    }

    // ── stableId + duplicados legítimos (issue #4) ────────────────────────────

    @Test fun E26_stableId_cubierto_en_repository_batchOcc_correcto() = runBlocking {
        // 2 transacciones idénticas (misma fecha, concepto, importe) en el mismo batch
        // → repository las importa con batchOcc=0 y batchOcc=1 → IDs distintos en DB
        val base = Date(1_000_000L)
        val t1 = makeTx(base, "Mercadona", -20.0)
        val t2 = makeTx(base, "Mercadona", -20.0)
        val (added, dups, _) = repo.importTransactions(listOf(t1, t2))
        assertEquals("Ambas deben importarse: son duplicados legítimos", 2, added)
        assertEquals(0, dups)
        assertEquals(2, txDao.storedCount())
        // Verificar que los IDs en DB son distintos
        val ids = txDao.getAllEntities().map { it.id }.toSet()
        assertEquals("Los 2 registros en DB deben tener IDs distintos", 2, ids.size)
    }

    @Test fun E27_reimportar_mismo_excel_con_identicas_no_duplica() = runBlocking {
        // Primera importación: 2 filas idénticas → 2 registros nuevos
        val base = Date(1_000_000L)
        val batch = listOf(makeTx(base, "Mercadona", -20.0), makeTx(base, "Mercadona", -20.0))
        val (added1, _, _) = repo.importTransactions(batch)
        assertEquals(2, added1)
        assertEquals(2, txDao.storedCount())
        // Re-importar el mismo batch → ambas son duplicadas (ya existen)
        val (added2, dups2, _) = repo.importTransactions(batch)
        assertEquals("Re-importar → 0 nuevas", 0, added2)
        assertEquals("Re-importar → 2 duplicadas", 2, dups2)
        assertEquals("BD no debe crecer", 2, txDao.storedCount())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Crea una transacción de prueba con ID indexado y único.
     * Usa [ExcelProcessor.stableId] para evitar colisiones de ID en el bucle.
     */
    private fun tx(
        index: Int,
        concept: String = "Concepto de prueba $index",
        amount: Double = -index.toDouble()
    ): Transaction {
        val date = Date(1_000_000L * index)
        return Transaction(
            id      = ExcelProcessor.stableId(date, concept, amount),
            date    = date,
            amount  = amount,
            concept = concept,
            type    = TransactionType.EXPENSE
        )
    }

    /** Crea una transacción con ID estable a partir de (fecha, concepto, importe). */
    private fun makeTx(date: Date, concept: String, amount: Double) = Transaction(
        id      = ExcelProcessor.stableId(date, concept, amount),
        date    = date,
        amount  = amount,
        concept = concept,
        type    = if (amount < 0) TransactionType.EXPENSE else TransactionType.INCOME
    )
}
