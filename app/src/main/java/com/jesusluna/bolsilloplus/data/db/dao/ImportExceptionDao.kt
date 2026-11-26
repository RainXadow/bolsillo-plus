package com.jesusluna.bolsilloplus.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportExceptionDao {

    @Query("SELECT * FROM import_exceptions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ImportExceptionEntity>>

    @Query("SELECT * FROM import_exceptions WHERE isEnabled = 1")
    suspend fun getAllEnabled(): List<ImportExceptionEntity>

    @Upsert
    suspend fun upsert(exception: ImportExceptionEntity)

    /** Upsert con normalización: convierte CONTAINS_WORD a CONTAINS para compatibilidad */
    suspend fun upsertWithNormalization(exception: ImportExceptionEntity) {
        val normalizedException = if (exception.matchType == MatchType.CONTAINS_WORD) {
            exception.copy(matchType = MatchType.CONTAINS)
        } else {
            exception
        }
        upsert(normalizedException)
    }

    @Query("DELETE FROM import_exceptions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE import_exceptions SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("SELECT * FROM import_exceptions ORDER BY createdAt ASC")
    suspend fun getAll(): List<ImportExceptionEntity>

    // Búsqueda para merge de backup: clave lógica (normalizedPattern + matchType)
    @Query("SELECT * FROM import_exceptions WHERE normalizedPattern = :normalizedPattern AND matchType = :matchType LIMIT 1")
    suspend fun findByPatternAndMatchType(normalizedPattern: String, matchType: MatchType): ImportExceptionEntity?

    @Query("DELETE FROM import_exceptions")
    suspend fun deleteAll()
}
