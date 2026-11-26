package com.jesusluna.bolsilloplus.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import com.jesusluna.bolsilloplus.data.db.DbLimits
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

data class DedupKeyCount(
    @ColumnInfo(name = "dedupKey") val dedupKey: String,
    @ColumnInfo(name = "count") val count: Int
)

data class TransactionWithCategory(
    @Embedded val transaction: TransactionEntity,
    @ColumnInfo(name = "categoryName")        val categoryName: String?,
    @ColumnInfo(name = "categoryColor")       val categoryColor: String?,
    @ColumnInfo(name = "parentCategoryName")  val parentCategoryName: String? = null,
    @ColumnInfo(name = "parentCategoryColor") val parentCategoryColor: String? = null,
    @ColumnInfo(name = "parentCategoryId")    val parentCategoryId: Long? = null
)

@Dao
interface TransactionDao {

    @Query("""
        SELECT t.id, t.date, t.amount, t.concept, t.normalizedConcept, t.categoryId,
               t.source, t.type, t.balance, t.hasBalance, t.dedupKey,
               t.isManual, t.assignedByRuleId, t.candidateRuleCount,
               c.name as categoryName, c.color as categoryColor,
               p.name as parentCategoryName, p.color as parentCategoryColor,
               p.id as parentCategoryId
        FROM transactions t
        LEFT JOIN categories c ON t.categoryId = c.id
        LEFT JOIN categories p ON c.parentId = p.id
        ORDER BY t.date DESC
    """)
    fun observeAllWithCategory(): Flow<List<TransactionWithCategory>>

    @Query("""
        SELECT t.id, t.date, t.amount, t.concept, t.normalizedConcept, t.categoryId,
               t.source, t.type, t.balance, t.hasBalance, t.dedupKey,
               t.isManual, t.assignedByRuleId, t.candidateRuleCount,
               c.name as categoryName, c.color as categoryColor,
               p.name as parentCategoryName, p.color as parentCategoryColor,
               p.id as parentCategoryId
        FROM transactions t
        LEFT JOIN categories c ON t.categoryId = c.id
        LEFT JOIN categories p ON c.parentId = p.id
        WHERE t.categoryId IS NULL
        ORDER BY t.date DESC
    """)
    fun observeUncategorizedWithCategory(): Flow<List<TransactionWithCategory>>

    @Query("SELECT * FROM transactions WHERE categoryId = :categoryId ORDER BY date DESC")
    fun observeByCategory(categoryId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE categoryId IN (:categoryIds) ORDER BY date DESC")
    fun observeByCategoryList(categoryIds: List<Long>): Flow<List<TransactionEntity>>

    @Query("""
        SELECT t.id, t.date, t.amount, t.concept, t.normalizedConcept, t.categoryId,
               t.source, t.type, t.balance, t.hasBalance, t.dedupKey,
               t.isManual, t.assignedByRuleId, t.candidateRuleCount,
               c.name as categoryName, c.color as categoryColor,
               p.name as parentCategoryName, p.color as parentCategoryColor,
               p.id as parentCategoryId
        FROM transactions t
        LEFT JOIN categories c ON t.categoryId = c.id
        LEFT JOIN categories p ON c.parentId = p.id
        ORDER BY t.date DESC
    """)
    suspend fun getAllWithCategoryOnce(): List<TransactionWithCategory>

    @Upsert
    suspend fun upsertAll(transactions: List<TransactionEntity>)

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE id = :transactionId")
    suspend fun updateCategory(transactionId: Long, categoryId: Long?)

    /** Categorización manual: marca isManual=1, borra el ruleId y resetea candidateRuleCount. */
    @Query("UPDATE transactions SET categoryId = :categoryId, isManual = 1, assignedByRuleId = NULL, candidateRuleCount = 0 WHERE id = :transactionId")
    suspend fun updateCategoryManual(transactionId: Long, categoryId: Long)

    /** Auto-categorización con regla conocida: guarda el ruleId, isManual=0 y el conteo de candidatos. */
    @Query("UPDATE transactions SET categoryId = :categoryId, isManual = 0, assignedByRuleId = :ruleId, candidateRuleCount = :conflictCount WHERE id = :transactionId")
    suspend fun updateCategoryWithRuleAndCount(transactionId: Long, categoryId: Long, ruleId: Long, conflictCount: Int)

    /** Bulk update por concepto normalizado exacto (scope ExactName). Limpia ruleId y conflicto.
     *  Solo afecta txs no manuales (isManual = 0) para respetar correcciones explícitas del usuario. */
    @Query("""
        UPDATE transactions SET categoryId = :categoryId, assignedByRuleId = NULL, candidateRuleCount = 0
        WHERE normalizedConcept = :normalizedConcept AND isManual = 0
    """)
    suspend fun updateCategoryByNormalizedConcept(normalizedConcept: String, categoryId: Long): Int

    /** Bulk update para scope ContainsPhrase: todos los que contengan la frase. */
    @Query("""
        UPDATE transactions
        SET categoryId = :categoryId, isManual = 0, assignedByRuleId = :ruleId, candidateRuleCount = 0
        WHERE normalizedConcept LIKE '%' || :phrase || '%'
    """)
    suspend fun updateCategoryForPhrase(phrase: String, categoryId: Long, ruleId: Long): Int

    /** Bulk update para scope ContainsWord: todos los que contengan la palabra completa. */
    @Query("""
        UPDATE transactions
        SET categoryId = :categoryId, isManual = 0, assignedByRuleId = :ruleId, candidateRuleCount = 0
        WHERE ' ' || normalizedConcept || ' ' LIKE '% ' || :word || ' %'
    """)
    suspend fun updateCategoryForWord(word: String, categoryId: Long, ruleId: Long): Int

    @Query("UPDATE transactions SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    /** Cuenta los movimientos de una categoría exacta (sin subárbol). Usado para el delete dialog. */
    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countByCategoryId(categoryId: Long): Int

    /** Obtiene COUNT(*) por dedupKey para las keys proporcionadas (chunking-friendly). */
    @Query("SELECT dedupKey, COUNT(*) as count FROM transactions WHERE dedupKey IN (:keys) GROUP BY dedupKey")
    suspend fun getCountsByDedupKeys(keys: List<String>): List<DedupKeyCount>

    /** Legacy: mantiene compatibilidad para migración. */
    @Query("SELECT (normalizedConcept || '-' || date || '-' || printf('%.2f', amount)) FROM transactions")
    suspend fun getAllDeduplicationKeys(): List<String>

    /** Para aplicar reglas de forma masiva a cobros sin categoría. */
    @Query("SELECT * FROM transactions WHERE categoryId IS NULL")
    suspend fun getAllUncategorized(): List<TransactionEntity>

    /** Todos los cobros no categorizados manualmente: recalculables en el re-scan. */
    @Query("SELECT * FROM transactions WHERE isManual = 0")
    suspend fun getAllNonManual(): List<TransactionEntity>

    /** Mueve TODOS los movimientos de una categoría a otra. */
    @Query("UPDATE transactions SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignAllByCategory(oldCategoryId: Long, newCategoryId: Long): Int

    /** Devuelve todos los movimientos asignados por la regla indicada (para reasignación al borrar). */
    @Query("SELECT * FROM transactions WHERE assignedByRuleId = :ruleId")
    suspend fun getAllByAssignedRule(ruleId: Long): List<TransactionEntity>

    /** Limpia categoryId, assignedByRuleId y candidateRuleCount para movimientos de categoría eliminada. */
    @Query("UPDATE transactions SET categoryId = NULL, assignedByRuleId = NULL, candidateRuleCount = 0 WHERE categoryId = :categoryId")
    suspend fun clearCategoryReferences(categoryId: Long)

    /** Elimina todos los movimientos (requiere doble confirmación en la UI). */
    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    /** Elimina todos los movimientos con el mismo concepto normalizado. Retorna el número de filas eliminadas. */
    @Query("DELETE FROM transactions WHERE normalizedConcept = :normalizedConcept")
    suspend fun deleteAllByNormalizedConcept(normalizedConcept: String): Int

    /** Todas las entidades para backup (sin JOIN de categorías). */
    @Query("SELECT * FROM transactions ORDER BY date DESC")
    suspend fun getAllEntities(): List<TransactionEntity>

    // ── Queries de conteo para el scope dialog ────────────────────────────────

    @Query("SELECT COUNT(*) FROM transactions WHERE normalizedConcept = :concept")
    suspend fun countByExactConcept(concept: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE normalizedConcept LIKE '%' || :phrase || '%'")
    suspend fun countContainingPhrase(phrase: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE ' ' || normalizedConcept || ' ' LIKE '% ' || :word || ' %'")
    suspend fun countContainingWord(word: String): Int
}
