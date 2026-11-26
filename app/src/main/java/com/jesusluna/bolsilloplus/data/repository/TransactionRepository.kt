package com.jesusluna.bolsilloplus.data.repository

import com.jesusluna.bolsilloplus.ExcelProcessor
import com.jesusluna.bolsilloplus.Transaction
import com.jesusluna.bolsilloplus.data.db.dao.CategoryDao
import com.jesusluna.bolsilloplus.data.db.dao.CategoryRuleDao
import com.jesusluna.bolsilloplus.data.db.DbLimits
import com.jesusluna.bolsilloplus.data.db.dao.ImportExceptionDao
import com.jesusluna.bolsilloplus.data.db.dao.SkippedTransactionDao
import com.jesusluna.bolsilloplus.data.db.dao.TransactionDao
import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.SkippedTransactionEntity
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ── Opciones de alcance al corregir una categoría ─────────────────────────────

sealed class CategorizationScope {
    /** Solo este movimiento, sin crear regla. */
    object OnlyThis : CategorizationScope()
    /** Todos los movimientos con el mismo nombre normalizado exacto + regla EXACT. */
    object ExactName : CategorizationScope()
    /** Todos los que contengan la frase + regla CONTAINS. */
    data class ContainsPhrase(val phrase: String) : CategorizationScope()
    /** Todos los que contengan la palabra completa + regla CONTAINS_WORD. */
    data class ContainsWord(val word: String) : CategorizationScope()
}

// ── Repository ─────────────────────────────────────────────────────────────────

class TransactionRepository(
    private val transactionDao: TransactionDao,
    private val categoryRuleDao: CategoryRuleDao,
    private val importExceptionDao: ImportExceptionDao,
    private val skippedTransactionDao: SkippedTransactionDao,
    private val categoryDao: CategoryDao
) {
    val allTransactions: Flow<List<TransactionWithCategory>> =
        transactionDao.observeAllWithCategory()

    val uncategorized: Flow<List<TransactionWithCategory>> =
        transactionDao.observeUncategorizedWithCategory()

    fun transactionsByCategory(categoryId: Long): Flow<List<TransactionEntity>> =
        transactionDao.observeByCategory(categoryId)

    /**
     * Flow reactivo del subárbol de una categoría root (padre + hijos directos).
     * flatMapLatest sobre observeChildrenOf garantiza que si se añade/elimina un hijo
     * mientras el Flow está activo, se reinicia con los IDs actualizados.
     * Solo debe llamarse para categorías root (parentId == null).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun transactionsByParentTree(parentId: Long): Flow<List<TransactionEntity>> =
        categoryDao.observeChildrenOf(parentId)
            .flatMapLatest { children ->
                val allIds = listOf(parentId) + children.map { it.id }
                transactionDao.observeByCategoryList(allIds)
            }

    /**
     * Construye la clave de deduplicación con formato canónico.
     * TZ fija Europe/Madrid para que el día sea siempre el día bancario español,
     * independientemente de si el usuario viaja o cambia la zona horaria del dispositivo.
     *
     * Formato: normalizedConcept|amountStr|yyyyMMdd|balanceOr_
     *   - balanceOr_: saldo formateado si hasBalance=true, "_" si hasBalance=false
     */
    private fun buildDedupKey(
        normalizedConcept: String,
        amount: Double,
        dateMillis: Long,
        balance: Double,
        hasBalance: Boolean
    ): String {
        // Sanitizar -0.0 → 0.0: String.format produce "-0.00" para -0.0 (distinto de "0.00")
        val a = if (amount == 0.0) 0.0 else amount
        val b = if (balance == 0.0) 0.0 else balance
        val amountStr = String.format(Locale.ROOT, "%.2f", a)
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.ROOT).also {
            it.timeZone = TimeZone.getTimeZone("Europe/Madrid")
        }
        val dateStr = sdf.format(Date(dateMillis))
        val balanceOrUnderscore = if (hasBalance) String.format(Locale.ROOT, "%.2f", b) else "_"
        return "$normalizedConcept|$amountStr|$dateStr|$balanceOrUnderscore"
    }

    /**
     * Importa transacciones desde el modelo legacy (Excel processor).
     * Aplica auto-categorización, registra candidateRuleCount y evita duplicados.
     * Retorna Triple(añadidas, duplicadas, excluidas_por_excepción).
     *
     * Deduplicación por conteo (normalizedConcept, date, printf('%.2f', amount)):
     * - Se cuenta cuántas veces aparece cada clave en la BD y cuántas en el batch.
     * - La N-ésima ocurrencia del batch es duplicada si la BD ya tiene al menos N registros
     *   con esa clave, y nueva en caso contrario.
     * - Esto permite importar dos cobros idénticos legítimos del mismo Excel (ej: dos
     *   peajes el mismo día al mismo precio) sin que se traten como duplicados entre sí,
     *   y a la vez evita reimportar movimientos ya registrados si se importa el mismo Excel
     *   una segunda vez.
     * - La clave usa Locale.ROOT y printf en SQL para garantizar el mismo formato siempre
     *   (2 decimales con punto). CAST(ROUND) producía "-100.0" vs String.format "-100.00",
     *   haciendo que ~50% de los importes no matchearan y se reimportaran como nuevos.
     */
    suspend fun importTransactions(rawTransactions: List<Transaction>): Triple<Int, Int, Int> =
        withContext(Dispatchers.IO) {
            // 1) Generar dedupKeys del batch y contar ocurrencias internas
            val batchDedupKeys = rawTransactions.map { tx ->
                buildDedupKey(
                    DescriptionNormalizer.normalize(tx.concept),
                    tx.amount, tx.date.time, tx.balance, tx.hasBalance
                )
            }
            val batchOccurrences = mutableMapOf<String, Int>()
            for (key in batchDedupKeys) {
                batchOccurrences[key] = batchOccurrences.getOrDefault(key, 0) + 1
            }

            // 2) Obtener COUNTs existentes por dedupKey (con chunking para evitar límite SQLite)
            val existingCounts = mutableMapOf<String, Int>()
            val uniqueKeys = batchDedupKeys.distinct()
            
            for (i in uniqueKeys.indices step DbLimits.SQLITE_BIND_LIMIT) {
                val chunk = uniqueKeys.subList(i, minOf(i + DbLimits.SQLITE_BIND_LIMIT, uniqueKeys.size))
                val counts = transactionDao.getCountsByDedupKeys(chunk)
                for (count in counts) {
                    existingCounts[count.dedupKey] = count.count
                }
            }

            // 3) Procesar transacciones con lógica de ocurrencias
            val exceptions = importExceptionDao.getAllEnabled()
            var added = 0
            var duplicates = 0
            var skipped = 0
            val toSkip = mutableListOf<SkippedTransactionEntity>()
            val processedBatchOccurrences = mutableMapOf<String, Int>()

            val entities = rawTransactions.mapNotNull { tx ->
                val normalized = DescriptionNormalizer.normalize(tx.concept)
                val dedupKey = buildDedupKey(normalized, tx.amount, tx.date.time, tx.balance, tx.hasBalance)

                // Índice de esta ocurrencia dentro del batch (0-based)
                val batchOcc = processedBatchOccurrences.getOrDefault(dedupKey, 0)
                processedBatchOccurrences[dedupKey] = batchOcc + 1

                // Dedup: esta ocurrencia es duplicada si la BD ya tiene al menos (batchOcc+1) registros
                val existingCount = existingCounts.getOrDefault(dedupKey, 0)
                if (batchOcc < existingCount) {
                    duplicates++
                    return@mapNotNull null
                }

                val matchedException = findMatchingException(normalized, exceptions)
                if (matchedException != null) {
                    skipped++
                    toSkip.add(
                        SkippedTransactionEntity(
                            originalId = ExcelProcessor.stableId(tx.date, tx.concept, tx.amount, batchOcc),
                            date = tx.date.time,
                            amount = tx.amount,
                            concept = tx.concept,
                            normalizedConcept = normalized,
                            source = tx.source,
                            type = tx.type.name,
                            balance = tx.balance,
                            hasBalance = tx.hasBalance,
                            exceptionId = matchedException.id
                        )
                    )
                    return@mapNotNull null
                }

                val matchingRules = DescriptionNormalizer.findAllMatchingRules(normalized, categoryRuleDao)
                val winner = matchingRules.firstOrNull()
                added++
                TransactionEntity(
                    id = ExcelProcessor.stableId(tx.date, tx.concept, tx.amount, batchOcc),
                    date = tx.date.time,
                    amount = tx.amount,
                    concept = tx.concept,
                    normalizedConcept = normalized,
                    dedupKey = dedupKey,
                    categoryId = winner?.categoryId,
                    source = tx.source,
                    type = tx.type.name,
                    balance = tx.balance,
                    hasBalance = tx.hasBalance,
                    isManual = false,
                    assignedByRuleId = winner?.id,
                    candidateRuleCount = matchingRules.size
                )
            }

            if (entities.isNotEmpty()) transactionDao.upsertAll(entities)
            if (toSkip.isNotEmpty()) skippedTransactionDao.insertAll(toSkip)

            Triple(added, duplicates, skipped)
        }

    /** Restaura todos los movimientos excluidos de una excepción y luego borra el log. */
    suspend fun restoreAllSkippedForException(exceptionId: Long) = withContext(Dispatchers.IO) {
        val skippedList = skippedTransactionDao.getAllByException(exceptionId)
        if (skippedList.isEmpty()) return@withContext
        val entities = skippedList.map { skipped ->
            val matchingRules = DescriptionNormalizer.findAllMatchingRules(skipped.normalizedConcept, categoryRuleDao)
            val winner = matchingRules.firstOrNull()
            val dedupKey = buildDedupKey(skipped.normalizedConcept, skipped.amount, skipped.date, skipped.balance, skipped.hasBalance)
            TransactionEntity(
                id = skipped.originalId,
                date = skipped.date,
                amount = skipped.amount,
                concept = skipped.concept,
                normalizedConcept = skipped.normalizedConcept,
                dedupKey = dedupKey,
                categoryId = winner?.categoryId,
                source = skipped.source,
                type = skipped.type,
                balance = skipped.balance,
                hasBalance = skipped.hasBalance,
                isManual = false,
                assignedByRuleId = winner?.id,
                candidateRuleCount = matchingRules.size
            )
        }
        transactionDao.upsertAll(entities)
        skippedTransactionDao.deleteByExceptionId(exceptionId)
    }

    /** Restaura un movimiento excluido: lo importa en la tabla de transacciones y lo elimina del log. */
    suspend fun restoreSkippedTransaction(skipped: SkippedTransactionEntity) = withContext(Dispatchers.IO) {
        val matchingRules = DescriptionNormalizer.findAllMatchingRules(skipped.normalizedConcept, categoryRuleDao)
        val winner = matchingRules.firstOrNull()
        val dedupKey = buildDedupKey(skipped.normalizedConcept, skipped.amount, skipped.date, skipped.balance, skipped.hasBalance)
        val entity = TransactionEntity(
            id = skipped.originalId,
            date = skipped.date,
            amount = skipped.amount,
            concept = skipped.concept,
            normalizedConcept = skipped.normalizedConcept,
            dedupKey = dedupKey,
            categoryId = winner?.categoryId,
            source = skipped.source,
            type = skipped.type,
            balance = skipped.balance,
            hasBalance = skipped.hasBalance,
            isManual = false,
            assignedByRuleId = winner?.id,
            candidateRuleCount = matchingRules.size
        )
        transactionDao.upsertAll(listOf(entity))
        skippedTransactionDao.deleteById(skipped.id)
    }

    private fun findMatchingException(
        normalizedConcept: String,
        exceptions: List<ImportExceptionEntity>
    ): ImportExceptionEntity? = exceptions.firstOrNull { exc ->
        when (exc.matchType) {
            MatchType.EXACT -> normalizedConcept == exc.normalizedPattern
            MatchType.CONTAINS -> normalizedConcept.contains(exc.normalizedPattern)
            MatchType.CONTAINS_WORD -> false // No soportado en excepciones; se normaliza a CONTAINS al guardar
            MatchType.REGEX -> try {
                Regex(exc.normalizedPattern).containsMatchIn(normalizedConcept)
            } catch (e: Exception) { false }
        }
    }

    /**
     * Wrapper de compatibilidad: categoriza con scope ExactName (comportamiento anterior).
     */
    suspend fun categorize(
        transactionId: Long,
        normalizedConcept: String,
        categoryId: Long
    ) = categorizeWithScope(transactionId, normalizedConcept, categoryId, CategorizationScope.ExactName)

    /**
     * Categoriza solo este movimiento como manual, sin crear ninguna regla.
     */
    suspend fun categorizeScopeOnly(
        transactionId: Long,
        categoryId: Long
    ) = withContext(Dispatchers.IO) {
        transactionDao.updateCategoryManual(transactionId, categoryId)
    }

    /**
     * Categoriza un movimiento con el alcance elegido por el usuario.
     * - [OnlyThis]: solo este movimiento, sin regla.
     * - [ExactName]: regla EXACT + reasigna todos con el mismo concepto normalizado.
     * - [ContainsPhrase]: regla CONTAINS + bulk update por frase.
     * - [ContainsWord]: regla CONTAINS_WORD + bulk update por palabra.
     */
    suspend fun categorizeWithScope(
        transactionId: Long,
        normalizedConcept: String,
        categoryId: Long,
        scope: CategorizationScope
    ) = withContext(Dispatchers.IO) {
        when (scope) {
            CategorizationScope.OnlyThis -> {
                transactionDao.updateCategoryManual(transactionId, categoryId)
            }

            CategorizationScope.ExactName -> {
                transactionDao.updateCategoryManual(transactionId, categoryId)
                val existing = categoryRuleDao.findByPatternAndType(normalizedConcept, MatchType.EXACT)
                val rule = existing?.copy(categoryId = categoryId, isEnabled = true) ?: CategoryRuleEntity(
                    normalizedPattern = normalizedConcept,
                    categoryId = categoryId,
                    matchType = MatchType.EXACT,
                    priority = MatchType.EXACT.defaultPriority
                )
                categoryRuleDao.upsert(rule)
                transactionDao.updateCategoryByNormalizedConcept(normalizedConcept, categoryId)
            }

            is CategorizationScope.ContainsPhrase -> {
                val phrase = scope.phrase
                val existing = categoryRuleDao.findByPatternAndType(phrase, MatchType.CONTAINS)
                val rule = existing?.copy(categoryId = categoryId, isEnabled = true) ?: CategoryRuleEntity(
                    normalizedPattern = phrase,
                    categoryId = categoryId,
                    matchType = MatchType.CONTAINS,
                    // +5 sobre la base para indicar que fue creada por el usuario (desempata con reglas del sistema)
                    priority = MatchType.CONTAINS.defaultPriority + 5
                )
                categoryRuleDao.upsert(rule)
                val saved = categoryRuleDao.findByPatternAndType(phrase, MatchType.CONTAINS)!!
                // Primero el bulk (puede incluir la tx disparadora, sets isManual=0)
                transactionDao.updateCategoryForPhrase(phrase, categoryId, saved.id)
                // Luego marcar la tx disparadora como manual (sobreescribe el isManual=0 del bulk)
                transactionDao.updateCategoryManual(transactionId, categoryId)
            }

            is CategorizationScope.ContainsWord -> {
                val word = scope.word
                val existing = categoryRuleDao.findByPatternAndType(word, MatchType.CONTAINS_WORD)
                val rule = existing?.copy(categoryId = categoryId, isEnabled = true) ?: CategoryRuleEntity(
                    normalizedPattern = word,
                    categoryId = categoryId,
                    matchType = MatchType.CONTAINS_WORD,
                    priority = MatchType.CONTAINS_WORD.defaultPriority + 5
                )
                categoryRuleDao.upsert(rule)
                val saved = categoryRuleDao.findByPatternAndType(word, MatchType.CONTAINS_WORD)!!
                // Primero el bulk (puede incluir la tx disparadora, sets isManual=0)
                transactionDao.updateCategoryForWord(word, categoryId, saved.id)
                // Luego marcar la tx disparadora como manual (sobreescribe el isManual=0 del bulk)
                transactionDao.updateCategoryManual(transactionId, categoryId)
            }
        }
    }

    suspend fun deleteTransaction(id: Long) = withContext(Dispatchers.IO) {
        transactionDao.delete(id)
    }

    suspend fun deleteTransactionsByConcept(normalizedConcept: String): Int =
        withContext(Dispatchers.IO) {
            transactionDao.deleteAllByNormalizedConcept(normalizedConcept)
        }

    suspend fun getAllTransactionsOnce(): List<TransactionWithCategory> =
        withContext(Dispatchers.IO) {
            transactionDao.getAllWithCategoryOnce()
        }
}
