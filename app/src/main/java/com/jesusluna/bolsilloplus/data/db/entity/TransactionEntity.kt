package com.jesusluna.bolsilloplus.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("categoryId"), Index("normalizedConcept"), Index("dedupKey")]
)
data class TransactionEntity(
    @PrimaryKey val id: Long,
    val date: Long,              // epoch millis
    val amount: Double,
    val concept: String,
    val normalizedConcept: String,
    val dedupKey: String,        // Clave: normalizedConcept|amountStr|yyyyMMdd|balanceOr_ (hasBalance decide si usa balance o "_")
    val categoryId: Long?,
    val source: String = "Excel",
    val type: String,            // "INCOME" | "EXPENSE" | "UNKNOWN"
    val balance: Double = 0.0,
    val hasBalance: Boolean = false,  // Flag semántico: true si el banco proporcionó saldo
    val isManual: Boolean = false,
    /** ID de la regla que asignó la categoría automáticamente. Null si fue manual, sin categoría, o dato legacy. */
    val assignedByRuleId: Long? = null,
    /** Número de reglas que coincidieron en el último import/scan. >1 indica conflicto. */
    val candidateRuleCount: Int = 0
)
