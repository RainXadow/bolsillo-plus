package com.jesusluna.bolsilloplus.excel

import com.jesusluna.bolsilloplus.data.db.dao.ImportExceptionDao
import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeImportExceptionDao : ImportExceptionDao {

    private val _exceptions = mutableListOf<ImportExceptionEntity>()
    private var nextId = 1L
    private val _flow = MutableStateFlow<List<ImportExceptionEntity>>(emptyList())

    fun addException(exc: ImportExceptionEntity): Long {
        val id = if (exc.id == 0L) nextId++ else exc.id
        _exceptions.removeAll { it.id == id }
        _exceptions.add(exc.copy(id = id))
        _flow.value = _exceptions.toList()
        return id
    }

    fun clear() { _exceptions.clear(); nextId = 1L; _flow.value = emptyList() }

    override fun observeAll(): Flow<List<ImportExceptionEntity>> = _flow

    override suspend fun getAllEnabled(): List<ImportExceptionEntity> =
        _exceptions.filter { it.isEnabled }

    override suspend fun upsert(exception: ImportExceptionEntity) {
        addException(exception)
    }

    override suspend fun delete(id: Long) {
        _exceptions.removeAll { it.id == id }
        _flow.value = _exceptions.toList()
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        val idx = _exceptions.indexOfFirst { it.id == id }
        if (idx >= 0) _exceptions[idx] = _exceptions[idx].copy(isEnabled = enabled)
        _flow.value = _exceptions.toList()
    }

    override suspend fun getAll(): List<ImportExceptionEntity> = _exceptions.toList()

    override suspend fun findByPatternAndMatchType(pattern: String, matchType: MatchType): ImportExceptionEntity? =
        _exceptions.firstOrNull { it.normalizedPattern == pattern && it.matchType == matchType }

    override suspend fun deleteAll() { _exceptions.clear(); _flow.value = emptyList() }
}
