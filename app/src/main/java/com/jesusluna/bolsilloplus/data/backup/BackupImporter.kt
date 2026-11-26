package com.jesusluna.bolsilloplus.data.backup

import com.jesusluna.bolsilloplus.data.db.DbLimits
import com.jesusluna.bolsilloplus.data.db.dao.CategoryDao
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity
import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import com.jesusluna.bolsilloplus.data.db.dao.CategoryRuleDao
import com.jesusluna.bolsilloplus.data.db.dao.ImportExceptionDao
import com.jesusluna.bolsilloplus.data.db.dao.TransactionDao

/**
 * Aplica un [BackupFile] sobre las DAOs proporcionadas dentro de una sola transacción.
 *
 * Semántica: MERGE — nunca borra. Solo añade lo que falta y actualiza lo existente.
 *
 * Reglas de importación:
 *  - Categorías : MERGE por nombre (case-insensitive + trim).
 *      · Raíces  → clave = name
 *      · Hijos   → clave = (parentIdLocal, name)
 *    Se construye un mapping backupId → localId para reutilizarlo en reglas.
 *    Para nuevas categorías se intenta preservar el ID del backup; si ya está ocupado
 *    por otra categoría, se usa auto-ID.
 *  - Reglas : MERGE por clave lógica (localCategoryId + normalizedPattern + matchType).
 *    Reglas existentes se actualizan (priority, isEnabled); nuevas se insertan.
 *    Reglas cuya categoría no se pueda resolver → omitidas (orphanRulesSkipped).
 *  - Movimientos : Merge (upsertAll por PK). No se pierde lo que no está en el backup.
 *  - Excepciones : MERGE por (pattern + matchType). Existentes se actualizan (isEnabled);
 *    nuevas se insertan.
 *
 * Solo se toca un grupo si el campo correspondiente es no-null en el backup.
 * Un campo null significa "no incluido en este backup", no "vacío".
 *
 * @param transactionRunner Envuelve la operación en una transacción de DB.
 *   En producción: `{ db.withTransaction(it) }`.
 *   En tests unitarios JVM: `{ it() }` (valor por defecto — sin transacción real).
 */
class BackupImporter(
    private val categoryDao: CategoryDao,
    private val categoryRuleDao: CategoryRuleDao,
    private val transactionDao: TransactionDao,
    private val importExceptionDao: ImportExceptionDao,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { block -> block() }
) {

    data class Summary(
        val categoriesRestored: Int?,       // null = no estaba en el backup
        val rulesRestored: Int?,            // reglas procesadas (merge); null = no en backup
        val orphanRulesSkipped: Int = 0,    // reglas omitidas por categoría irresoluble
        val transactionsUpserted: Int?,
        val exceptionsRestored: Int?
    ) {
        fun toHumanString(): String = buildList {
            categoriesRestored?.let { add("$it categorías") }
            rulesRestored?.let { add("$it reglas") }
            if (orphanRulesSkipped > 0) add("⚠ $orphanRulesSkipped reglas omitidas (categoría inexistente)")
            transactionsUpserted?.let { add("$it movimientos") }
            exceptionsRestored?.let { add("$it excepciones") }
        }.joinToString(", ")
    }

    suspend fun import(backup: BackupFile): Summary {
        var catsRestored: Int? = null
        var rulesRestored: Int? = null
        var orphanRulesSkipped = 0
        var txUpserted: Int? = null
        var excRestored: Int? = null

        transactionRunner {

            // ── Categorías (MERGE por nombre + parent) ──────────────────────────
            // Construye idMapping: backupCategoryId → localCategoryId
            var idMapping: Map<Long, Long>? = null

            if (backup.categories != null) {
                val mapping = mutableMapOf<Long, Long>()
                val backupRoots = backup.categories.filter { it.parentId == null }
                val backupChildren = backup.categories.filter { it.parentId != null }

                // 1) Raíces: resolver por nombre (case-insensitive)
                for (bCat in backupRoots) {
                    val existing = categoryDao.findRootByName(bCat.name.trim())
                    val localId: Long = if (existing != null) {
                        existing.id
                    } else {
                        // Intentar preservar el ID del backup; si está ocupado → auto-ID
                        val inserted = categoryDao.insert(bCat)
                        if (inserted > 0) inserted else categoryDao.insert(bCat.copy(id = 0))
                    }
                    mapping[bCat.id] = localId
                }

                // 2) Hijos: resolver por (parentIdLocal, nombre)
                for (bChild in backupChildren) {
                    val localParentId = mapping[bChild.parentId] ?: continue
                    val existing = categoryDao.findChildByNameAndParent(bChild.name.trim(), localParentId)
                    val localId: Long = if (existing != null) {
                        existing.id
                    } else {
                        val inserted = categoryDao.insert(bChild.copy(parentId = localParentId))
                        if (inserted > 0) inserted else categoryDao.insert(bChild.copy(id = 0, parentId = localParentId))
                    }
                    mapping[bChild.id] = localId
                }

                idMapping = mapping
                catsRestored = mapping.size
            }

            // ── Reglas (MERGE por clave lógica) ─────────────────────────────────
            // INVARIANTE: las reglas solo se pueden resolver si el backup incluye
            // también las categorías (para construir idMapping por nombre).
            // Si categories=null no hay forma de resolver categoryId por nombre
            // (CategoryRuleEntity solo lleva categoryId, no el nombre de la cat) →
            // todas las reglas quedan huérfanas. El export siempre agrupa categorías
            // y reglas en el mismo bloque para garantizar este invariante.
            if (backup.categoryRules != null) {
                if (idMapping == null) {
                    orphanRulesSkipped = backup.categoryRules.size
                    rulesRestored = 0
                } else {
                    var count = 0
                    for (bRule in backup.categoryRules) {
                        val localCatId = idMapping[bRule.categoryId]
                        if (localCatId == null) {
                            orphanRulesSkipped++
                            continue
                        }
                        val existing = categoryRuleDao.findByLogicalKey(
                            localCatId, bRule.normalizedPattern, bRule.matchType
                        )
                        if (existing != null) {
                            // Actualizar campos no-identitarios de la regla existente
                            categoryRuleDao.upsert(existing.copy(
                                priority = bRule.priority,
                                isEnabled = bRule.isEnabled
                            ))
                        } else {
                            categoryRuleDao.upsert(bRule.copy(id = 0, categoryId = localCatId))
                        }
                        count++
                    }
                    rulesRestored = count
                }
            }

            // ── Movimientos (merge con deduplicación por dedupKey/occurrences) ────────────────
            if (backup.transactions != null) {
                val backupTxs = backup.transactions
                
                // Ordenar batch para procesamiento determinista (mismo resultado siempre)
                // Prioridad: dedupKey → date → amount → concept → source (id solo como último desempate)
                val sortedTxs = backupTxs.sortedWith(compareBy<TransactionEntity> { it.dedupKey }
                    .thenBy { it.date }
                    .thenBy { it.amount }
                    .thenBy { it.concept }
                    .thenBy { it.source }
                    .thenBy { it.id })
                
                // 1) Generar dedupKeys del backup y contar ocurrencias
                val backupDedupKeys = sortedTxs.map { tx -> tx.dedupKey }
                val backupOccurrences = mutableMapOf<String, Int>()
                for (key in backupDedupKeys) {
                    backupOccurrences[key] = backupOccurrences.getOrDefault(key, 0) + 1
                }

                // 2) Obtener COUNTs existentes por dedupKey (con chunking)
                val existingCounts = mutableMapOf<String, Int>()
                val uniqueKeys = backupDedupKeys.distinct()
                
                for (i in uniqueKeys.indices step DbLimits.SQLITE_BIND_LIMIT) {
                    val chunk = uniqueKeys.subList(i, minOf(i + DbLimits.SQLITE_BIND_LIMIT, uniqueKeys.size))
                    val counts = transactionDao.getCountsByDedupKeys(chunk)
                    for (count in counts) {
                        existingCounts[count.dedupKey] = count.count
                    }
                }

                // 3) Filtrar solo transacciones no duplicadas (misma lógica que Excel)
                val toInsert = mutableListOf<TransactionEntity>()
                val processedBackupOccurrences = mutableMapOf<String, Int>()
                
                for (tx in sortedTxs) {
                    val backupOcc = processedBackupOccurrences.getOrDefault(tx.dedupKey, 0)
                    processedBackupOccurrences[tx.dedupKey] = backupOcc + 1
                    
                    val existingCount = existingCounts.getOrDefault(tx.dedupKey, 0)
                    if (backupOcc >= existingCount) {
                        // Esta ocurrencia no existe en BD, la insertamos
                        toInsert.add(tx)
                    }
                    // Si backupOcc < existingCount, ya existe en BD → omitimos
                }
                
                if (toInsert.isNotEmpty()) {
                    transactionDao.upsertAll(toInsert)
                }
                txUpserted = toInsert.size
            }

            // ── Excepciones (MERGE por normalizedPattern + matchType) ───────────────────────
            if (backup.importExceptions != null) {
                var count = 0
                for (bExc in backup.importExceptions) {
                    // Gson puede deserializar normalizedPattern como null en backups pre-v7
                    // (campo ausente en el JSON). El Elvis es necesario aunque el tipo diga String.
                    @Suppress("USELESS_ELVIS")
                    val normalizedPattern = bExc.normalizedPattern
                        ?: DescriptionNormalizer.normalize(bExc.patternRaw)
                    
                    // Normalizar CONTAINS_WORD a CONTAINS para compatibilidad
                    val normalizedMatchType = if (bExc.matchType == MatchType.CONTAINS_WORD) {
                        MatchType.CONTAINS
                    } else {
                        bExc.matchType
                    }
                    
                    val existing = importExceptionDao.findByPatternAndMatchType(
                        normalizedPattern, normalizedMatchType
                    )
                    if (existing != null) {
                        importExceptionDao.upsert(existing.copy(isEnabled = bExc.isEnabled))
                    } else {
                        importExceptionDao.upsert(bExc.copy(
                            id = 0, 
                            matchType = normalizedMatchType,
                            normalizedPattern = normalizedPattern
                        ))
                    }
                    count++
                }
                excRestored = count
            }
        }

        return Summary(catsRestored, rulesRestored, orphanRulesSkipped, txUpserted, excRestored)
    }
}
