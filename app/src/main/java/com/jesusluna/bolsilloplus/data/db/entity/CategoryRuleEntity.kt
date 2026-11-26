package com.jesusluna.bolsilloplus.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MatchType(val defaultPriority: Int) {
    EXACT(100),
    CONTAINS(50),
    CONTAINS_WORD(45),
    REGEX(10)
}

@Entity(
    tableName = "category_rules",
    foreignKeys = [ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("categoryId"), Index("normalizedPattern")]
)
data class CategoryRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedPattern: String,
    val categoryId: Long,
    val matchType: MatchType = MatchType.EXACT,
    val priority: Int = MatchType.EXACT.defaultPriority,
    val createdAt: Long = System.currentTimeMillis(),
    /** Permite desactivar una regla sin borrarla. */
    val isEnabled: Boolean = true
)
