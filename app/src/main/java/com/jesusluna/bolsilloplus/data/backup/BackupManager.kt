package com.jesusluna.bolsilloplus.data.backup

import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException

// ── Modelos del backup ────────────────────────────────────────────────────────

data class BackupMetadata(
    val appVersion: String,
    val schemaVersion: Int = BackupManager.SCHEMA_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val includes: List<String>   // "categories", "transactions", "exceptions"
)

data class BackupFile(
    val metadata: BackupMetadata,
    val categories: List<CategoryEntity>? = null,         // root + child juntos
    val categoryRules: List<CategoryRuleEntity>? = null,
    val transactions: List<TransactionEntity>? = null,
    val importExceptions: List<ImportExceptionEntity>? = null
)

// ── Gestor de serialización ────────────────────────────────────────────────────

object BackupManager {

    const val SCHEMA_VERSION = 1

    private val gson = GsonBuilder().create()

    fun createBackup(file: BackupFile): String = gson.toJson(file)

    fun parseBackup(json: String): BackupFile {
        return try {
            gson.fromJson(json, BackupFile::class.java)
                ?: throw IllegalArgumentException("Backup vacío o nulo")
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("JSON malformado: ${e.message}", e)
        }
    }

    fun validateBackup(backup: BackupFile): ValidationResult {
        if (backup.metadata.schemaVersion > SCHEMA_VERSION)
            return ValidationResult.Error(
                "Versión de esquema ${backup.metadata.schemaVersion} no soportada (máx $SCHEMA_VERSION)"
            )
        if (backup.metadata.schemaVersion < 1)
            return ValidationResult.Error("Versión de esquema inválida")
        return ValidationResult.Ok
    }

    sealed class ValidationResult {
        object Ok : ValidationResult()
        data class Error(val reason: String) : ValidationResult()
    }
}
