package com.jesusluna.bolsilloplus.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Transacción ignorada durante la importación por coincidir con una excepción. */
@Entity(tableName = "skipped_transactions")
data class SkippedTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalId: Long,          // id original del Excel (para restaurar sin cambiar el PK)
    val date: Long,
    val amount: Double,
    val concept: String,
    val normalizedConcept: String,
    val source: String,
    val type: String,
    val balance: Double,
    val hasBalance: Boolean,          // true si el banco proporcionó saldo (igual que TransactionEntity)
    val exceptionId: Long,         // excepción que la causó
    val skippedAt: Long = System.currentTimeMillis()
)
