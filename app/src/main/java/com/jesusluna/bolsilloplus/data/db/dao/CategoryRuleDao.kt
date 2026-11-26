package com.jesusluna.bolsilloplus.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryRuleDao {

    @Query("""
        SELECT * FROM category_rules
        WHERE normalizedPattern = :normalizedText AND matchType = 'EXACT' AND isEnabled = 1
        ORDER BY priority DESC
        LIMIT 1
    """)
    suspend fun findExactRule(normalizedText: String): CategoryRuleEntity?

    @Query("""
        SELECT * FROM category_rules
        WHERE normalizedPattern = :pattern AND matchType = :matchType
        LIMIT 1
    """)
    suspend fun findByPatternAndType(pattern: String, matchType: MatchType): CategoryRuleEntity?

    // Clave lógica para merge de backup: categoría + patrón + tipo
    @Query("""
        SELECT * FROM category_rules
        WHERE categoryId = :categoryId AND normalizedPattern = :pattern AND matchType = :matchType
        LIMIT 1
    """)
    suspend fun findByLogicalKey(categoryId: Long, pattern: String, matchType: MatchType): CategoryRuleEntity?

    @Query("""
        SELECT * FROM category_rules
        WHERE matchType = 'CONTAINS' AND isEnabled = 1
        ORDER BY priority DESC
    """)
    suspend fun getAllContainsRules(): List<CategoryRuleEntity>

    @Query("""
        SELECT * FROM category_rules
        WHERE matchType = 'CONTAINS_WORD' AND isEnabled = 1
        ORDER BY priority DESC
    """)
    suspend fun getAllContainsWordRules(): List<CategoryRuleEntity>

    @Query("""
        SELECT * FROM category_rules
        WHERE matchType = 'REGEX' AND isEnabled = 1
        ORDER BY priority DESC
    """)
    suspend fun getAllRegexRules(): List<CategoryRuleEntity>

    @Query("SELECT * FROM category_rules WHERE categoryId = :categoryId ORDER BY createdAt DESC")
    fun observeRulesByCategory(categoryId: Long): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules WHERE id = :id")
    suspend fun findById(id: Long): CategoryRuleEntity?

    @Query("UPDATE category_rules SET priority = :priority WHERE id = :id")
    suspend fun updatePriority(id: Long, priority: Int)

    @Query("UPDATE category_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun updateEnabled(id: Long, enabled: Boolean)

    @Upsert
    suspend fun upsert(rule: CategoryRuleEntity)

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM category_rules")
    suspend fun count(): Int

    @Query("SELECT * FROM category_rules ORDER BY createdAt ASC")
    suspend fun getAll(): List<CategoryRuleEntity>

    @Query("DELETE FROM category_rules")
    suspend fun deleteAll()
}
