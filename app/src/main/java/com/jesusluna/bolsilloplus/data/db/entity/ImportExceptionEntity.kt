package com.jesusluna.bolsilloplus.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "import_exceptions")
data class ImportExceptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patternRaw: String,             // Patrón original (para UI/edición)
    val normalizedPattern: String,       // Patrón normalizado (para matching)
    val matchType: MatchType,   // EXACT, CONTAINS o REGEX (sin CONTAINS_WORD)
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
