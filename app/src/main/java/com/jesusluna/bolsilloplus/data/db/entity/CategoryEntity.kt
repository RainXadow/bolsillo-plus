package com.jesusluna.bolsilloplus.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index("parentId")]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String = "#2196F3",
    val icon: String = "label",
    val isDefault: Boolean = false,
    val parentId: Long? = null   // null = root/padre; non-null = hijo
)
