package com.jesusluna.bolsilloplus.data.repository

import com.jesusluna.bolsilloplus.data.db.dao.CategoryDao
import com.jesusluna.bolsilloplus.data.db.dao.CategoryWithCount
import com.jesusluna.bolsilloplus.data.db.dao.TransactionDao
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class CategoryRepository(
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao
) {
    val allCategories: Flow<List<CategoryEntity>> = categoryDao.observeAll()
    val allCategoriesWithCount: Flow<List<CategoryWithCount>> = categoryDao.observeAllWithCount()

    suspend fun insert(category: CategoryEntity): Long =
        withContext(Dispatchers.IO) { categoryDao.insert(category) }

    suspend fun update(category: CategoryEntity) =
        withContext(Dispatchers.IO) { categoryDao.update(category) }

    /** Valida que parentId existe y es root. Lanzar desde cualquier operación que escriba parentId. */
    private suspend fun requireValidParent(parentId: Long) {
        val parent = categoryDao.getById(parentId)
            ?: error("Categoría padre no encontrada (id=$parentId)")
        if (parent.parentId != null)
            error("El padre seleccionado es a su vez una subcategoría — no se permiten más de 1 nivel")
    }

    /** Inserta una nueva subcategoría bajo [parentId]. */
    suspend fun insertChild(name: String, color: String, parentId: Long): Long =
        withContext(Dispatchers.IO) {
            requireValidParent(parentId)
            categoryDao.insert(CategoryEntity(name = name, color = color, parentId = parentId))
        }

    /** Mueve una categoría existente bajo un padre (o a root si newParentId == null). */
    suspend fun setParent(categoryId: Long, newParentId: Long?) =
        withContext(Dispatchers.IO) {
            if (newParentId != null) {
                if (categoryId == newParentId) error("Una categoría no puede ser su propio padre")
                requireValidParent(newParentId)
                // Si la categoría tiene hijos, no puede convertirse en hijo (evita nietos)
                val childCount = categoryDao.countChildren(categoryId)
                if (childCount > 0)
                    error("No se puede convertir en subcategoría una categoría que ya tiene $childCount subcategoría(s)")
            }
            val cat = categoryDao.getById(categoryId) ?: return@withContext
            categoryDao.update(cat.copy(parentId = newParentId))
        }

    /** Borra una categoría: mueve sus hijos a root y limpia todas sus referencias. */
    suspend fun delete(categoryId: Long) = withContext(Dispatchers.IO) {
        categoryDao.moveChildrenToRoot(categoryId)
        transactionDao.clearCategoryReferences(categoryId)
        categoryDao.delete(categoryId)
    }

    fun observeChildrenOf(parentId: Long): Flow<List<CategoryEntity>> =
        categoryDao.observeChildrenOf(parentId)

    suspend fun countChildren(categoryId: Long): Int =
        withContext(Dispatchers.IO) { categoryDao.countChildren(categoryId) }
}
