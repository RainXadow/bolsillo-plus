package com.jesusluna.bolsilloplus.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

data class CategoryWithCount(
    @Embedded val category: CategoryEntity,
    @ColumnInfo(name = "transactionCount") val transactionCount: Int
)

@Dao
interface CategoryDao {

    // Orden: padres agrupados con sus hijos (padre primero, luego sus hijos, ordenados por nombre)
    @Query("""
        SELECT * FROM categories
        ORDER BY CASE WHEN parentId IS NULL THEN id ELSE parentId END ASC,
                 parentId IS NOT NULL ASC, name ASC
    """)
    fun observeAll(): Flow<List<CategoryEntity>>

    // Contador: para padres suma sus propias txs + las de sus hijos directos
    @Query("""
        SELECT c.id, c.name, c.color, c.icon, c.isDefault, c.parentId,
               (SELECT COUNT(t.id) FROM transactions t
                WHERE t.categoryId = c.id
                   OR (c.parentId IS NULL
                       AND t.categoryId IN (SELECT cc.id FROM categories cc WHERE cc.parentId = c.id))
               ) as transactionCount
        FROM categories c
        ORDER BY CASE WHEN c.parentId IS NULL THEN c.id ELSE c.parentId END ASC,
                 c.parentId IS NOT NULL ASC, c.name ASC
    """)
    fun observeAllWithCount(): Flow<List<CategoryWithCount>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY name ASC")
    suspend fun getAll(): List<CategoryEntity>

    // Hijos de un padre (reactivo y suspend)
    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY name ASC")
    fun observeChildrenOf(parentId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY name ASC")
    suspend fun getChildrenOf(parentId: Long): List<CategoryEntity>

    // Para validación anti-ciclo y confirmación de borrado
    @Query("SELECT COUNT(*) FROM categories WHERE parentId = :categoryId")
    suspend fun countChildren(categoryId: Long): Int

    // Mover todos los hijos de un padre a root (antes de borrarlo)
    @Query("UPDATE categories SET parentId = NULL WHERE parentId = :parentId")
    suspend fun moveChildrenToRoot(parentId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    // Búsquedas para merge de backup (case-insensitive)
    @Query("SELECT * FROM categories WHERE parentId IS NULL AND LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findRootByName(name: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE parentId = :parentId AND LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findChildByNameAndParent(name: String, parentId: Long): CategoryEntity?

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}
