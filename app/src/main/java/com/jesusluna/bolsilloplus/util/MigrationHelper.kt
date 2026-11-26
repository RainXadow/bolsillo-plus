package com.jesusluna.bolsilloplus.util

import android.content.Context
import android.util.Log
import com.jesusluna.bolsilloplus.CategoryManager
import com.jesusluna.bolsilloplus.TransactionStorage
import com.jesusluna.bolsilloplus.data.db.AppDatabase
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Migra los datos de JSON (almacenamiento antiguo) a Room (nuevo).
 * Se ejecuta una sola vez en el primer arranque tras la actualización.
 */
class MigrationHelper(private val context: Context) {

    private val prefs = context.getSharedPreferences("finanzas_migration", Context.MODE_PRIVATE)

    suspend fun migrateIfNeeded(db: AppDatabase) = withContext(Dispatchers.IO) {

        // ── Paso 1: migración inicial JSON → Room (solo si aún no se hizo) ──
        if (!prefs.getBoolean("v1_migrated", false)) {
            try {
                val txCount = db.transactionDao().count()
                val catCount = db.categoryDao().count()

                if (catCount == 0) migrateCategories(db)
                if (txCount == 0) migrateTransactions(db)

                prefs.edit().putBoolean("v1_migrated", true).apply()
                Log.i("Migration", "Migración JSON → Room completada")
            } catch (e: Exception) {
                Log.e("Migration", "Error en migración: ${e.message}", e)
                return@withContext  // reintentar en el próximo arranque
            }
        }

        // ── Paso 2: sembrar reglas CONTAINS por defecto ────────────────────
        // Se ejecuta independientemente del paso 1 para cubrir usuarios
        // que ya tenían la app instalada antes de esta función.
        if (!prefs.getBoolean("v1_contains_rules_seeded", false)) {
            try {
                val nameToId = db.categoryDao().getAll().associate { it.name to it.id }
                if (nameToId.isNotEmpty()) {
                    seedDefaultContainsRules(nameToId, db.categoryRuleDao())
                    prefs.edit().putBoolean("v1_contains_rules_seeded", true).apply()
                }
            } catch (e: Exception) {
                Log.e("Migration", "Error sembrando reglas CONTAINS: ${e.message}", e)
            }
        }
    }

    private suspend fun migrateCategories(db: AppDatabase) {
        val categoryDao = db.categoryDao()
        val ruleDao = db.categoryRuleDao()

        // Leer categorías del JSON antiguo
        val oldCategories = try {
            CategoryManager(context).getAllCategories()
        } catch (e: Exception) {
            AppDatabase.defaultCategories.map {
                com.jesusluna.bolsilloplus.Category(name = it.name, isDefault = it.isDefault)
            }
        }

        // Leer reglas del JSON antiguo
        val oldRules = try {
            CategoryManager(context).getAllRules()
        } catch (e: Exception) {
            emptyMap()
        }

        val nameToId = mutableMapOf<String, Long>()

        // Insertar categorías en Room
        oldCategories.forEach { cat ->
            val entity = CategoryEntity(
                name = cat.name,
                isDefault = cat.isDefault
            )
            val id = categoryDao.insert(entity)
            if (id > 0) nameToId[cat.name] = id
        }

        // Si no había categorías en JSON, insertar las por defecto
        if (nameToId.isEmpty()) {
            AppDatabase.defaultCategories.forEach { entity ->
                val id = categoryDao.insert(entity)
                if (id > 0) nameToId[entity.name] = id
            }
        }

        // Insertar reglas EXACT del historial del usuario (JSON antiguo)
        oldRules.forEach { (normalizedConcept, categoryName) ->
            val catId = nameToId[categoryName] ?: return@forEach
            ruleDao.upsert(
                CategoryRuleEntity(
                    normalizedPattern = normalizedConcept,
                    categoryId = catId,
                    matchType = MatchType.EXACT
                )
            )
        }

        Log.i("Migration", "Categorías migradas: ${nameToId.size}, Reglas EXACT: ${oldRules.size}")
    }

    /**
     * Siembra reglas CONTAINS por defecto para cada categoría estándar.
     * Solo se añaden una vez durante la migración inicial.
     * El usuario puede eliminarlas o añadir más desde la UI.
     */
    private suspend fun seedDefaultContainsRules(
        nameToId: Map<String, Long>,
        ruleDao: com.jesusluna.bolsilloplus.data.db.dao.CategoryRuleDao
    ) {
        // Mapa: nombre de categoría → lista de patrones CONTAINS
        val defaultRules: Map<String, List<String>> = mapOf(
            "Alimentación" to listOf(
                "mercadona", "carrefour", "hipercor", "supermercado",
                "lidl", "aldi", "consum", "dia ", "alcampo", "eroski",
                "spar", "froiz", "masymas", "gadis", "gadisline",
                "supercor", "ahorramas", "simply", "suma "
            ),
            "Restaurantes" to listOf(
                "restaurante", "mcdonald", "burger king", "subway",
                "dominos", "telepizza", "pizzeria", "hesburger",
                "just eat", "deliveroo", "glovo", "uber eats",
                "cafeteria", "kebab", "sushi"
            ),
            "Transporte" to listOf(
                "gasolina", "repsol", "cepsa", "plenoil", "plenergy",
                "uber", "cabify", "renfe", "metro ", "parking",
                "peaje", "autopista", "emt ", "blablacar"
            ),
            "Ocio" to listOf(
                "netflix", "spotify", "steam", "hbo", "disney",
                "twitch", "playstation", "nintendo", "prime video",
                "apple tv", "dazn", "filmin"
            ),
            "Servicios" to listOf(
                "digi", "movistar", "vodafone", "orange ", "endesa",
                "iberdrola", "naturgy", "aguas ", "suministro",
                "seguros", "mutua", "adeslas", "sanitas",
                "comunidad ", "ibi ", "multa"
            ),
            "Compras Online" to listOf(
                "amazon", "aliexpress", "paypal", "alipay",
                "ebay", "zalando", "pccomponentes", "mediamarkt",
                "fnac", "shein", "asos", "zara.com", "elcorteingles"
            ),
            "Bizum" to listOf("bizum"),
            "Cajero" to listOf("cajero", "retirada ef", "disposicion atm"),
            "Nómina" to listOf("nomina", "abono nomina", "pago nomina"),
            "Transferencias" to listOf("transferencia", "traspaso", "transf.")
        )

        var seeded = 0
        defaultRules.forEach { (categoryName, patterns) ->
            val catId = nameToId[categoryName] ?: return@forEach
            patterns.forEach { pattern ->
                val normalized = DescriptionNormalizer.normalize(pattern)
                // Solo insertar si no existe ya una regla con ese patrón+tipo
                val existing = ruleDao.findByPatternAndType(normalized, MatchType.CONTAINS)
                if (existing == null) {
                    ruleDao.upsert(
                        CategoryRuleEntity(
                            normalizedPattern = normalized,
                            categoryId = catId,
                            matchType = MatchType.CONTAINS,
                            priority = MatchType.CONTAINS.defaultPriority
                        )
                    )
                    seeded++
                }
            }
        }
        Log.i("Migration", "Reglas CONTAINS sembradas: $seeded")
    }

    private suspend fun migrateTransactions(db: AppDatabase) {
        val oldTransactions = try {
            TransactionStorage(context).getAllTransactions()
        } catch (e: Exception) {
            emptyList()
        }

        if (oldTransactions.isEmpty()) return

        val allCategories = db.categoryDao().getAll()
        val nameToId = allCategories.associate { it.name to it.id }

        val sdf = SimpleDateFormat("yyyyMMdd", Locale.ROOT).also {
            it.timeZone = TimeZone.getTimeZone("Europe/Madrid")
        }
        val entities = oldTransactions.map { tx ->
            val normalized = DescriptionNormalizer.normalize(tx.concept)
            val a = if (tx.amount == 0.0) 0.0 else tx.amount
            val b = if (tx.balance == 0.0) 0.0 else tx.balance
            val amountStr = String.format(Locale.ROOT, "%.2f", a)
            val dateStr = sdf.format(Date(tx.date.time))
            val balanceStr = String.format(Locale.ROOT, "%.2f", b)
            val dedupKey = "$normalized|$amountStr|$dateStr|$balanceStr"
            TransactionEntity(
                id = tx.id,
                date = tx.date.time,
                amount = tx.amount,
                concept = tx.concept,
                normalizedConcept = normalized,
                dedupKey = dedupKey,
                categoryId = nameToId[tx.category],
                source = tx.source,
                type = tx.type.name,
                balance = tx.balance,
                hasBalance = true,
                isManual = tx.isManual
            )
        }

        db.transactionDao().upsertAll(entities)
        Log.i("Migration", "Transacciones migradas: ${entities.size}")
    }
}
