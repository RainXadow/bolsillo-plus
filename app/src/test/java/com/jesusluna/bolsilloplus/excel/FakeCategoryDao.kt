package com.jesusluna.bolsilloplus.excel

import com.jesusluna.bolsilloplus.data.db.dao.CategoryDao
import com.jesusluna.bolsilloplus.data.db.dao.CategoryWithCount
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Implementación en memoria de [CategoryDao] para tests unitarios JVM.
 * Solo se usa como parámetro de [TransactionRepository]; importTransactions()
 * no invoca métodos de CategoryDao directamente.
 */
class FakeCategoryDao : CategoryDao {

    private val _store = mutableMapOf<Long, CategoryEntity>()
    private var _nextId = 1L

    override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(_store.values.toList())
    override fun observeAllWithCount(): Flow<List<CategoryWithCount>> = flowOf(emptyList())
    override suspend fun getById(id: Long): CategoryEntity? = _store[id]
    override suspend fun getAll(): List<CategoryEntity> = _store.values.toList()
    override fun observeChildrenOf(parentId: Long): Flow<List<CategoryEntity>> =
        flowOf(_store.values.filter { it.parentId == parentId })
    override suspend fun getChildrenOf(parentId: Long): List<CategoryEntity> =
        _store.values.filter { it.parentId == parentId }
    override suspend fun countChildren(categoryId: Long): Int =
        _store.values.count { it.parentId == categoryId }
    override suspend fun moveChildrenToRoot(parentId: Long) {
        _store.entries.forEach { (k, v) -> if (v.parentId == parentId) _store[k] = v.copy(parentId = null) }
    }
    override suspend fun insert(category: CategoryEntity): Long {
        val id = if (category.id != 0L) {
            // Simula @Insert(onConflict = IGNORE): devuelve -1 si el PK ya existe
            if (_store.containsKey(category.id)) return -1
            category.id
        } else {
            _nextId++
        }
        _store[id] = category.copy(id = id)
        if (id >= _nextId) _nextId = id + 1
        return id
    }
    override suspend fun update(category: CategoryEntity) { _store[category.id] = category }
    override suspend fun delete(id: Long) { _store.remove(id) }
    override suspend fun count(): Int = _store.size
    override suspend fun deleteAll() { _store.clear() }

    override suspend fun findRootByName(name: String): CategoryEntity? =
        _store.values.firstOrNull { it.parentId == null && it.name.trim().lowercase() == name.trim().lowercase() }

    override suspend fun findChildByNameAndParent(name: String, parentId: Long): CategoryEntity? =
        _store.values.firstOrNull { it.parentId == parentId && it.name.trim().lowercase() == name.trim().lowercase() }
}
