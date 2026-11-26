package com.jesusluna.bolsilloplus.excel

import com.jesusluna.bolsilloplus.data.db.dao.ExceptionSkipCount
import com.jesusluna.bolsilloplus.data.db.dao.SkippedTransactionDao
import com.jesusluna.bolsilloplus.data.db.entity.SkippedTransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeSkippedTransactionDao : SkippedTransactionDao {

    private val _store = mutableListOf<SkippedTransactionEntity>()
    private var nextId = 1L

    fun all() = _store.toList()
    fun clear() { _store.clear(); nextId = 1L }

    override suspend fun insertAll(items: List<SkippedTransactionEntity>) {
        for (item in items) _store.add(item.copy(id = nextId++))
    }

    override suspend fun deleteById(id: Long) {
        _store.removeAll { it.id == id }
    }

    override suspend fun deleteByExceptionId(exceptionId: Long) {
        _store.removeAll { it.exceptionId == exceptionId }
    }

    override suspend fun getAllByException(exceptionId: Long): List<SkippedTransactionEntity> =
        _store.filter { it.exceptionId == exceptionId }

    override fun observeByException(exceptionId: Long): Flow<List<SkippedTransactionEntity>> =
        flowOf(_store.filter { it.exceptionId == exceptionId })

    override fun observeCountsPerException(): Flow<List<ExceptionSkipCount>> {
        val counts = _store.groupBy { it.exceptionId }
            .map { (id, list) -> ExceptionSkipCount(id, list.size) }
        return flowOf(counts)
    }
}
