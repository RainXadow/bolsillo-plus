package com.jesusluna.bolsilloplus.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jesusluna.bolsilloplus.data.db.entity.SkippedTransactionEntity
import kotlinx.coroutines.flow.Flow

/** Conteo de excluidos agrupado por excepción (para el badge). */
data class ExceptionSkipCount(
    @ColumnInfo(name = "exceptionId") val exceptionId: Long,
    @ColumnInfo(name = "count") val count: Int
)

@Dao
interface SkippedTransactionDao {

    @Query("SELECT * FROM skipped_transactions WHERE exceptionId = :exceptionId ORDER BY date DESC")
    fun observeByException(exceptionId: Long): Flow<List<SkippedTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<SkippedTransactionEntity>)

    @Query("DELETE FROM skipped_transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM skipped_transactions WHERE exceptionId = :exceptionId ORDER BY date DESC")
    suspend fun getAllByException(exceptionId: Long): List<SkippedTransactionEntity>

    @Query("DELETE FROM skipped_transactions WHERE exceptionId = :exceptionId")
    suspend fun deleteByExceptionId(exceptionId: Long)

    @Query("SELECT exceptionId, COUNT(*) as count FROM skipped_transactions GROUP BY exceptionId")
    fun observeCountsPerException(): Flow<List<ExceptionSkipCount>>
}
