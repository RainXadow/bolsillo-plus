package com.jesusluna.bolsilloplus.excel

import com.jesusluna.bolsilloplus.data.db.dao.TransactionDao
import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

/**
 * Implementación en memoria de [TransactionDao] para tests unitarios JVM.
 * Solo implementa los métodos usados por [TransactionRepository.importTransactions].
 */
class FakeTransactionDao : TransactionDao {

    private val _store = mutableMapOf<Long, TransactionEntity>()

    fun storedCount() = _store.size   // helper síncrono para asserts en tests

    // ── Métodos usados por importTransactions ─────────────────────────────────

    override suspend fun upsertAll(transactions: List<TransactionEntity>) {
        // @Upsert: INSERT o REPLACE por PK — igual que Room
        for (tx in transactions) _store[tx.id] = tx
    }

    override suspend fun getAllDeduplicationKeys(): List<String> =
        _store.values.map { it.dedupKey }

    override suspend fun getCountsByDedupKeys(keys: List<String>): List<com.jesusluna.bolsilloplus.data.db.dao.DedupKeyCount> {
        return keys.map { key ->
            val count = _store.values.count { it.dedupKey == key }
            com.jesusluna.bolsilloplus.data.db.dao.DedupKeyCount(dedupKey = key, count = count)
        }
    }

    override suspend fun count(): Int = _store.size

    // ── Resto de métodos (no usados en estos tests) ───────────────────────────

    override fun observeAllWithCategory(): Flow<List<TransactionWithCategory>> = flowOf(emptyList())
    override fun observeUncategorizedWithCategory(): Flow<List<TransactionWithCategory>> = flowOf(emptyList())
    override fun observeByCategory(categoryId: Long): Flow<List<TransactionEntity>> = flowOf(emptyList())
    override suspend fun getAllWithCategoryOnce(): List<TransactionWithCategory> = emptyList()
    override suspend fun updateCategory(transactionId: Long, categoryId: Long?) {}
    override suspend fun updateCategoryManual(transactionId: Long, categoryId: Long) {}
    override suspend fun updateCategoryWithRuleAndCount(transactionId: Long, categoryId: Long, ruleId: Long, conflictCount: Int) {}
    override suspend fun updateCategoryByNormalizedConcept(normalizedConcept: String, categoryId: Long): Int = 0
    override suspend fun updateCategoryForPhrase(phrase: String, categoryId: Long, ruleId: Long): Int = 0
    override suspend fun updateCategoryForWord(word: String, categoryId: Long, ruleId: Long): Int = 0
    override suspend fun clearCategory(categoryId: Long) {}
    override suspend fun delete(id: Long) { _store.remove(id) }
    override suspend fun getAllUncategorized(): List<TransactionEntity> = _store.values.filter { it.categoryId == null }
    override suspend fun getAllNonManual(): List<TransactionEntity> = _store.values.filter { !it.isManual }
    override suspend fun reassignAllByCategory(oldCategoryId: Long, newCategoryId: Long): Int = 0
    override suspend fun deleteAll() { _store.clear() }
    override suspend fun deleteAllByNormalizedConcept(normalizedConcept: String): Int {
        val before = _store.size
        _store.entries.removeIf { it.value.normalizedConcept == normalizedConcept }
        return before - _store.size
    }
    override suspend fun countByExactConcept(concept: String): Int =
        _store.values.count { it.normalizedConcept == concept }
    override suspend fun countContainingPhrase(phrase: String): Int =
        _store.values.count { it.normalizedConcept.contains(phrase) }
    override suspend fun countContainingWord(word: String): Int =
        _store.values.count { " ${it.normalizedConcept} ".contains(" $word ") }

    override suspend fun countByCategoryId(categoryId: Long): Int =
        _store.values.count { it.categoryId == categoryId }

    override fun observeByCategoryList(categoryIds: List<Long>): Flow<List<TransactionEntity>> =
        flowOf(_store.values.filter { it.categoryId in categoryIds })

    override suspend fun getAllEntities(): List<TransactionEntity> = _store.values.toList()

    override suspend fun clearCategoryReferences(categoryId: Long) {
        _store.entries.filter { it.value.categoryId == categoryId }.forEach { (id, tx) ->
            _store[id] = tx.copy(categoryId = null, assignedByRuleId = null, candidateRuleCount = 0)
        }
    }

    override suspend fun getAllByAssignedRule(ruleId: Long): List<TransactionEntity> =
        _store.values.filter { it.assignedByRuleId == ruleId }
}
