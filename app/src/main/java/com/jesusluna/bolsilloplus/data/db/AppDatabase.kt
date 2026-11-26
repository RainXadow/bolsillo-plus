package com.jesusluna.bolsilloplus.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jesusluna.bolsilloplus.data.db.dao.CategoryDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.jesusluna.bolsilloplus.data.db.dao.CategoryRuleDao
import com.jesusluna.bolsilloplus.data.db.dao.ImportExceptionDao
import com.jesusluna.bolsilloplus.data.db.dao.SkippedTransactionDao
import com.jesusluna.bolsilloplus.data.db.dao.TransactionDao
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.SkippedTransactionEntity
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity

class Converters {
    @TypeConverter
    fun fromMatchType(value: MatchType): String = value.name

    @TypeConverter
    fun toMatchType(value: String): MatchType = MatchType.valueOf(value)
}

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, CategoryRuleEntity::class, ImportExceptionEntity::class, SkippedTransactionEntity::class],
    version = 11,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun importExceptionDao(): ImportExceptionDao
    abstract fun skippedTransactionDao(): SkippedTransactionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Migración 1→2: añade columna assignedByRuleId a transactions. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN assignedByRuleId INTEGER DEFAULT NULL"
                )
            }
        }

        /** Migración 2→3: añade candidateRuleCount a transactions e isEnabled a category_rules. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN candidateRuleCount INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE category_rules ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /** Migración 3→4: crea la tabla import_exceptions. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS import_exceptions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        pattern TEXT NOT NULL,
                        matchType TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** Migración 4→5: crea la tabla skipped_transactions. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skipped_transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        originalId INTEGER NOT NULL,
                        date INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        concept TEXT NOT NULL,
                        normalizedConcept TEXT NOT NULL,
                        source TEXT NOT NULL,
                        type TEXT NOT NULL,
                        balance REAL NOT NULL,
                        exceptionId INTEGER NOT NULL,
                        skippedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** Migración 5→6: añade parentId a categories para jerarquía padre→hijo (1 nivel). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN parentId INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_categories_parentId ON categories(parentId)")
            }
        }

        /**
         * Migración 6→7:
         * - Elimina "Sin categoría": era una categoría predefinida sin función real
         *   (las txs sin categoría ya muestran "Sin categoría" como texto fallback en la UI).
         *   Las transacciones asociadas quedan con categoryId=NULL vía FK SET_NULL.
         * - Resetea isDefault=0 en todas las categorías: el flag bloqueaba el borrado de
         *   categorías migradas del sistema legacy (Alimentación, Restaurantes, etc.)
         *   que el usuario ahora debe poder eliminar libremente.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM categories WHERE name = 'Sin categoría' AND isDefault = 1")
                db.execSQL("UPDATE categories SET isDefault = 0 WHERE isDefault = 1")
            }
        }

        /**
         * Migración 7→8:
         * - Añade normalizedPattern a import_exceptions
         * - Renombra pattern a patternRaw
         * - Table rebuild manual para compatibilidad Android (sin RENAME COLUMN ni CREATE TABLE AS SELECT)
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) Crear tabla nueva con esquema final
                db.execSQL(
                    """
                    CREATE TABLE import_exceptions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        patternRaw TEXT NOT NULL,
                        normalizedPattern TEXT NOT NULL,
                        matchType TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                
                // 2) Copiar datos con backfill de normalizedPattern usando misma lógica que DescriptionNormalizer
                db.query("SELECT id, pattern, matchType, isEnabled, createdAt FROM import_exceptions").use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow("id")
                        val patternColumn = cursor.getColumnIndexOrThrow("pattern")
                        val matchTypeColumn = cursor.getColumnIndexOrThrow("matchType")
                        val isEnabledColumn = cursor.getColumnIndexOrThrow("isEnabled")
                        val createdAtColumn = cursor.getColumnIndexOrThrow("createdAt")
                        
                        do {
                            val id = cursor.getLong(idColumn)
                            val pattern = cursor.getString(patternColumn)
                            val matchType = cursor.getString(matchTypeColumn)
                            val isEnabled = cursor.getInt(isEnabledColumn)
                            val createdAt = cursor.getLong(createdAtColumn)
                            
                            // Normalizar usando EXACTAMENTE la misma lógica que DescriptionNormalizer.normalize()
                            val normalizedPattern = pattern
                                .lowercase()
                                .trim()
                                .replace(Regex("\\s+"), " ")
                            
                            db.execSQL(
                                "INSERT INTO import_exceptions_new (id, patternRaw, normalizedPattern, matchType, isEnabled, createdAt) VALUES (?, ?, ?, ?, ?, ?)",
                                arrayOf(id, pattern, normalizedPattern, matchType, isEnabled, createdAt)
                            )
                        } while (cursor.moveToNext())
                    }
                }
                
                // 3) Borrar tabla vieja
                db.execSQL("DROP TABLE import_exceptions")
                
                // 4) Renombrar tabla nueva
                db.execSQL("ALTER TABLE import_exceptions_new RENAME TO import_exceptions")
                
                // 5) Recrear índices
                db.execSQL("CREATE INDEX IF NOT EXISTS index_import_exceptions_normalizedPattern ON import_exceptions(normalizedPattern)")
            }
        }

        /**
         * Migración 8→9:
         * - Añade hasBalance (flag semántico) y dedupKey a transactions
         * - Backfill dedupKey con política explícita para legacy:
         *   normalizedConcept|amountStr|yyyyMMdd|balanceOr_ (usa balance si hasBalance=1, sino "_")
         *   
         *   **SUPUESTO CRÍTICO**: Para datos legacy (v8), asumimos hasBalance=1 
         *   (el banco siempre proporcionó saldo, incluso si es 0.00).
         *   
         *   ⚠️ Si algún histórico tuviera saldo no real (hasBalance=false), 
         *   esta migración podría romper deduplicación con imports futuros.
         *   En ese caso, sería necesaria una migración correctiva manual.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) Añadir campo hasBalance (flag semántico: 1 si el banco proporcionó saldo)
                db.execSQL("ALTER TABLE transactions ADD COLUMN hasBalance INTEGER NOT NULL DEFAULT 1")
                
                // 2) Añadir campo dedupKey
                db.execSQL("ALTER TABLE transactions ADD COLUMN dedupKey TEXT DEFAULT NULL")
                
                // 3) Backfill hasBalance y dedupKey para cada registro existente
                // Política para legacy: hasBalance=1 (asumimos que el banco proporcionó saldo)
                db.query("SELECT id, normalizedConcept, amount, date, balance FROM transactions").use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow("id")
                        val normalizedConceptColumn = cursor.getColumnIndexOrThrow("normalizedConcept")
                        val amountColumn = cursor.getColumnIndexOrThrow("amount")
                        val dateColumn = cursor.getColumnIndexOrThrow("date")
                        val balanceColumn = cursor.getColumnIndexOrThrow("balance")
                        
                        do {
                            val id = cursor.getLong(idColumn)
                            val normalizedConcept = cursor.getString(normalizedConceptColumn)
                            val amount = cursor.getDouble(amountColumn)
                            val date = cursor.getLong(dateColumn)
                            val balance = cursor.getDouble(balanceColumn)
                            
                            // Sanitizar -0.0 a 0.0
                            val sanitizedAmount = if (amount == -0.0) 0.0 else amount
                            val sanitizedBalance = if (balance == -0.0) 0.0 else balance
                            
                            // Política legacy: hasBalance=1 para todos los registros existentes
                            // (asumimos que el banco proporcionó saldo en datos históricos)
                            val hasBalance = 1
                            
                            // Formatear componentes
                            val amountStr = String.format(Locale.ROOT, "%.2f", sanitizedAmount)
                            val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
                            val dateStr = dateFormat.format(Date(date))
                            val balanceOrUnderscore = if (hasBalance == 1) String.format(Locale.ROOT, "%.2f", sanitizedBalance) else "_"
                            
                            // Construir dedupKey
                            val dedupKey = "$normalizedConcept|$amountStr|$dateStr|$balanceOrUnderscore"
                            
                            db.execSQL(
                                "UPDATE transactions SET hasBalance = ?, dedupKey = ? WHERE id = ?",
                                arrayOf(hasBalance, dedupKey, id)
                            )
                        } while (cursor.moveToNext())
                    }
                }
                
                // 4) Crear índices después del backfill
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_dedupKey ON transactions(dedupKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_hasBalance ON transactions(hasBalance)")
            }
        }

        /**
         * Migración 9→10:
         * - Añade hasBalance a skipped_transactions.
         *   DEFAULT 1 (true) coherente con MIGRATION_8_9: para datos legacy asumimos
         *   que el banco proporcionó saldo, igual que para la tabla transactions.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE skipped_transactions ADD COLUMN hasBalance INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Migración 10→11: alinea el schema real de todas las tablas afectadas con lo que
         * Room espera de los entities actuales. Las migraciones previas usaron DEFAULT en
         * columnas donde Room (sin @ColumnInfo(defaultValue)) espera sin DEFAULT, y crearon
         * índices no declarados en @Entity.
         *
         * Tablas afectadas:
         *  - transactions       : dedupKey notNull=false→true; hasBalance DEFAULT 1→sin DEFAULT;
         *                         elimina index_transactions_hasBalance.
         *  - import_exceptions  : isEnabled DEFAULT 1→sin DEFAULT;
         *                         elimina index_import_exceptions_normalizedPattern.
         *  - category_rules     : isEnabled DEFAULT 1→sin DEFAULT (añadido en MIGRATION_2_3).
         *  - skipped_transactions: hasBalance DEFAULT 1→sin DEFAULT (añadido en MIGRATION_9_10).
         *
         * Para transactions, antes del rebuild se recomputan con lógica canónica exacta
         * (TZ Europe/Madrid, sanitización -0.0, balanceOr_) los dedupKey que sean NULL
         * o vacíos (salvaguarda; en la práctica MIGRATION_8_9 ya los backfilleó todos).
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // ── 1. Backfill canónico de dedupKey para filas NULL/vacías ──────────
                val sdf = SimpleDateFormat("yyyyMMdd", Locale.ROOT).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("Europe/Madrid")
                }
                db.query(
                    "SELECT id, normalizedConcept, amount, date, balance, hasBalance " +
                    "FROM transactions WHERE dedupKey IS NULL OR dedupKey = ''"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id          = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                        val nc          = cursor.getString(cursor.getColumnIndexOrThrow("normalizedConcept"))
                        val rawAmount   = cursor.getDouble(cursor.getColumnIndexOrThrow("amount"))
                        val dateMs      = cursor.getLong(cursor.getColumnIndexOrThrow("date"))
                        val rawBalance  = cursor.getDouble(cursor.getColumnIndexOrThrow("balance"))
                        val hasBalInt   = cursor.getInt(cursor.getColumnIndexOrThrow("hasBalance"))
                        val a = if (rawAmount == 0.0) 0.0 else rawAmount
                        val b = if (rawBalance == 0.0) 0.0 else rawBalance
                        val amountStr = String.format(Locale.ROOT, "%.2f", a)
                        val dateStr   = sdf.format(Date(dateMs))
                        val balPart   = if (hasBalInt == 1) String.format(Locale.ROOT, "%.2f", b) else "_"
                        val dedupKey  = "$nc|$amountStr|$dateStr|$balPart"
                        db.execSQL("UPDATE transactions SET dedupKey = ? WHERE id = ?", arrayOf(dedupKey, id))
                    }
                }

                // ── 2. Rebuild transactions ───────────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE transactions_new (
                        id INTEGER PRIMARY KEY NOT NULL,
                        date INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        concept TEXT NOT NULL,
                        normalizedConcept TEXT NOT NULL,
                        dedupKey TEXT NOT NULL,
                        categoryId INTEGER,
                        source TEXT NOT NULL,
                        type TEXT NOT NULL,
                        balance REAL NOT NULL,
                        hasBalance INTEGER NOT NULL,
                        isManual INTEGER NOT NULL,
                        assignedByRuleId INTEGER,
                        candidateRuleCount INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO transactions_new
                    SELECT id, date, amount, concept, normalizedConcept, dedupKey,
                           categoryId, source, type, balance, hasBalance, isManual,
                           assignedByRuleId, candidateRuleCount
                    FROM transactions
                """.trimIndent())
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_categoryId ON transactions(categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_normalizedConcept ON transactions(normalizedConcept)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_dedupKey ON transactions(dedupKey)")

                // ── 3. Rebuild import_exceptions ──────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE import_exceptions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        patternRaw TEXT NOT NULL,
                        normalizedPattern TEXT NOT NULL,
                        matchType TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO import_exceptions_new
                    SELECT id, patternRaw, normalizedPattern, matchType, isEnabled, createdAt
                    FROM import_exceptions
                """.trimIndent())
                db.execSQL("DROP TABLE import_exceptions")
                db.execSQL("ALTER TABLE import_exceptions_new RENAME TO import_exceptions")
                // Sin índices: @Entity(tableName="import_exceptions") no declara ninguno

                // ── 4. Rebuild category_rules ─────────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE category_rules_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        normalizedPattern TEXT NOT NULL,
                        categoryId INTEGER NOT NULL,
                        matchType TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO category_rules_new
                    SELECT id, normalizedPattern, categoryId, matchType, priority, createdAt, isEnabled
                    FROM category_rules
                """.trimIndent())
                db.execSQL("DROP TABLE category_rules")
                db.execSQL("ALTER TABLE category_rules_new RENAME TO category_rules")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_category_rules_categoryId ON category_rules(categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_category_rules_normalizedPattern ON category_rules(normalizedPattern)")

                // ── 5. Rebuild skipped_transactions ───────────────────────────────────
                db.execSQL("""
                    CREATE TABLE skipped_transactions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        originalId INTEGER NOT NULL,
                        date INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        concept TEXT NOT NULL,
                        normalizedConcept TEXT NOT NULL,
                        source TEXT NOT NULL,
                        type TEXT NOT NULL,
                        balance REAL NOT NULL,
                        hasBalance INTEGER NOT NULL,
                        exceptionId INTEGER NOT NULL,
                        skippedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO skipped_transactions_new
                    SELECT id, originalId, date, amount, concept, normalizedConcept,
                           source, type, balance, hasBalance, exceptionId, skippedAt
                    FROM skipped_transactions
                """.trimIndent())
                db.execSQL("DROP TABLE skipped_transactions")
                db.execSQL("ALTER TABLE skipped_transactions_new RENAME TO skipped_transactions")
                // Sin índices: @Entity(tableName="skipped_transactions") no declara ninguno
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "finanzas_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build().also { INSTANCE = it }
            }

        val defaultCategories = listOf(
            CategoryEntity(name = "Alimentación",   color = "#4CAF50", icon = "shopping_cart"),
            CategoryEntity(name = "Restaurantes",   color = "#FF9800", icon = "restaurant"),
            CategoryEntity(name = "Transporte",     color = "#2196F3", icon = "directions_car"),
            CategoryEntity(name = "Ocio",           color = "#9C27B0", icon = "sports_esports"),
            CategoryEntity(name = "Servicios",      color = "#00BCD4", icon = "receipt"),
            CategoryEntity(name = "Transferencias", color = "#607D8B", icon = "swap_horiz"),
            CategoryEntity(name = "Bizum",          color = "#3F51B5", icon = "phone"),
            CategoryEntity(name = "Compras Online", color = "#E91E63", icon = "shopping_bag"),
            CategoryEntity(name = "Cajero",         color = "#795548", icon = "atm"),
            CategoryEntity(name = "Nómina",         color = "#8BC34A", icon = "work")
        )
    }
}
