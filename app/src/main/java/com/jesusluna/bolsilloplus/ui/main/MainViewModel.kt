package com.jesusluna.bolsilloplus.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jesusluna.bolsilloplus.BuildConfig
import com.jesusluna.bolsilloplus.ExcelExporter
import com.jesusluna.bolsilloplus.ExcelProcessor
import com.jesusluna.bolsilloplus.Transaction
import com.jesusluna.bolsilloplus.TransactionType
import androidx.room.withTransaction
import com.jesusluna.bolsilloplus.data.backup.BackupFile
import com.jesusluna.bolsilloplus.data.backup.BackupImporter
import com.jesusluna.bolsilloplus.data.backup.BackupManager
import com.jesusluna.bolsilloplus.data.backup.BackupMetadata
import com.jesusluna.bolsilloplus.data.db.AppDatabase
import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.SkippedTransactionEntity
import com.jesusluna.bolsilloplus.data.repository.CategoryRepository
import com.jesusluna.bolsilloplus.data.repository.CategorizationScope
import com.jesusluna.bolsilloplus.data.repository.TransactionRepository
import com.jesusluna.bolsilloplus.ui.categories.RuleMatchInfo
import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import com.jesusluna.bolsilloplus.util.MigrationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── Paleta de colores para el pie chart ───────────────────────────────────────

/** Color por defecto de CategoryEntity. Si la BD devuelve este valor lo tratamos
 *  como "no personalizado" y usamos un color determinista por nombre en su lugar. */
private const val DEFAULT_CAT_COLOR = "#2196F3"

private val PIE_PALETTE = listOf(
    "#4E79A7", "#F28E2B", "#E15759", "#76B7B2",
    "#59A14F", "#EDC948", "#B07AA1", "#FF9DA7",
    "#9C755F", "#AFBF9B", "#D37295", "#00BCD4"
)

// ── Período del dashboard ──────────────────────────────────────────────────────

data class DateRange(val from: Long, val to: Long)

enum class DashboardPeriod(val label: String) {
    WEEK("Semana"), MONTH("Mes"), YEAR("Año"), ALL("Todo"), CUSTOM("Personalizar");

    fun toDateRange(): DateRange {
        val now = System.currentTimeMillis()
        val from = when (this) {
            WEEK   -> now - 7L * 24 * 60 * 60 * 1000
            MONTH  -> Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis
            YEAR   -> Calendar.getInstance().apply { add(Calendar.YEAR,  -1) }.timeInMillis
            ALL, CUSTOM -> 0L
        }
        return DateRange(from = from, to = now)
    }
}

// ── Modos del gráfico de tarta ─────────────────────────────────────────────────

sealed class PieChartMode {
    object Parents : PieChartMode()
    object Children : PieChartMode()
    data class BreakdownParent(val parentId: Long, val parentName: String) : PieChartMode()
}

// ── Modelos de datos para el dashboard ────────────────────────────────────────

data class CategoryExpense(
    val name: String,
    val absNet: Double,     // abs(netAmount) — tamaño del slice y ordenación
    val netAmount: Double,  // suma algebraica: negativo = gasto neto, positivo = ingreso neto
    val count: Int,         // número de movimientos en la categoría
    val color: String
)

data class DashboardData(
    val income: Double = 0.0,
    val expenses: Double = 0.0,
    val balance: Double = 0.0,
    val transactionCount: Int = 0,
    val categoryExpenses: List<CategoryExpense> = emptyList(),
    val displayTransactions: List<TransactionWithCategory> = emptyList()
)

// ── Sentinel para "sin categoría" en el filtro ────────────────────────────────
// null   → todas las categorías
// 0L     → solo movimientos sin categoría
// id > 0 → categoría específica
const val FILTER_UNCATEGORIZED = 0L

/**
 * Intenta parsear [raw] como importe numérico para la búsqueda en el dashboard.
 * Acepta: "50", "50,0", "50.00", "-50".
 * Solo parsea si la cadena tiene forma estricta de número (evita falsos positivos con "2024").
 * Retorna null si no es un número válido.
 */
internal fun parseAmountQuery(raw: String): Double? {
    val s = raw.trim().replace(",", ".")
    return if (s.matches(Regex("-?\\d+(\\.\\d+)?$"))) s.toDoubleOrNull() else null
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val txRepo = TransactionRepository(db.transactionDao(), db.categoryRuleDao(), db.importExceptionDao(), db.skippedTransactionDao(), db.categoryDao())
    private val catRepo = CategoryRepository(db.categoryDao(), db.transactionDao())
    private val exceptionDao = db.importExceptionDao()
    private val skippedDao = db.skippedTransactionDao()

    // --- Período seleccionado ---
    private val _selectedPeriod = MutableStateFlow(DashboardPeriod.MONTH)
    val selectedPeriod: StateFlow<DashboardPeriod> = _selectedPeriod.asStateFlow()

    // --- Rango personalizado (solo activo cuando period == CUSTOM) ---
    private val _customRange = MutableStateFlow<DateRange?>(null)

    /** Rango de fechas activo: preset calculado o rango personalizado del usuario. */
    val activeDateRange: StateFlow<DateRange> =
        combine(_selectedPeriod, _customRange) { period, custom ->
            if (period == DashboardPeriod.CUSTOM && custom != null) custom
            else period.toDateRange()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
                  DashboardPeriod.MONTH.toDateRange())

    // --- Filtros de la lista de movimientos ---
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategoryId = MutableStateFlow<Long?>(null)
    val selectedCategoryId: StateFlow<Long?> = _selectedCategoryId.asStateFlow()

    // --- Modo del gráfico de tarta ---
    private val _pieMode = MutableStateFlow<PieChartMode>(PieChartMode.Parents)
    val pieMode: StateFlow<PieChartMode> = _pieMode.asStateFlow()

    fun setPieMode(mode: PieChartMode) { _pieMode.value = mode }

    // --- Datos del dashboard ---
    val dashboardData: StateFlow<DashboardData> =
        combine(
            combine(txRepo.allTransactions, activeDateRange) { allTx, range ->
                if (range.from > 0) allTx.filter { it.transaction.date in range.from..range.to } else allTx
            },
            _searchQuery,
            _selectedCategoryId,
            catRepo.allCategories,
            _pieMode
        ) { periodFiltered, query, catId, allCats, pieMode ->

            val income = periodFiltered
                .filter { it.transaction.type == "INCOME" }
                .sumOf { it.transaction.amount }
            val expenses = periodFiltered
                .filter { it.transaction.type == "EXPENSE" }
                .sumOf { kotlin.math.abs(it.transaction.amount) }

            val catExpenses = computePieData(periodFiltered, pieMode)

            // La lista de movimientos aplica también filtro de búsqueda y categoría
            var displayTx = periodFiltered
            when (catId) {
                FILTER_UNCATEGORIZED -> displayTx = displayTx.filter { it.transaction.categoryId == null }
                null -> { /* sin filtro */ }
                else -> {
                    val selectedCat = allCats.firstOrNull { it.id == catId }
                    if (selectedCat?.parentId == null) {
                        // isRoot: incluir txs del padre + de todos sus hijos
                        val childIds = allCats.filter { it.parentId == catId }.map { it.id }.toSet()
                        displayTx = displayTx.filter {
                            it.transaction.categoryId == catId || it.transaction.categoryId in childIds
                        }
                    } else {
                        // isChild: solo sus propias txs
                        displayTx = displayTx.filter { it.transaction.categoryId == catId }
                    }
                }
            }
            if (query.isNotBlank()) {
                val amountFilter = parseAmountQuery(query)
                displayTx = displayTx.filter { tx ->
                    tx.transaction.concept.contains(query, ignoreCase = true) ||
                    tx.transaction.normalizedConcept.contains(query, ignoreCase = true) ||
                    (amountFilter != null &&
                        String.format(Locale.ROOT, "%.2f", kotlin.math.abs(tx.transaction.amount)) ==
                        String.format(Locale.ROOT, "%.2f", kotlin.math.abs(amountFilter)))
                }
            }

            DashboardData(
                income = income,
                expenses = expenses,
                balance = income - expenses,
                transactionCount = periodFiltered.size,
                categoryExpenses = catExpenses,
                displayTransactions = displayTx
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardData())

    // --- Categorías disponibles (para chips de filtro y CategoryPickerBottomSheet) ---
    val categories: StateFlow<List<CategoryEntity>> =
        catRepo.allCategories
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Excepciones de importación ---
    val importExceptions: StateFlow<List<ImportExceptionEntity>> =
        exceptionDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Mapa exceptionId → número de movimientos excluidos (para el badge). */
    val skipCountsPerException: StateFlow<Map<Long, Int>> =
        skippedDao.observeCountsPerException()
            .map { list -> list.associate { it.exceptionId to it.count } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // --- Eventos one-shot ---
    private val _importResult = MutableSharedFlow<ImportResult>()
    val importResult = _importResult.asSharedFlow()

    private val _exportResult = MutableSharedFlow<ExportResult>()
    val exportResult = _exportResult.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            MigrationHelper(application).migrateIfNeeded(db)
        }
    }

    // ── Acciones del usuario ───────────────────────────────────────────────────

    fun selectPeriod(period: DashboardPeriod) { _selectedPeriod.value = period }

    fun setCustomRange(from: Long, to: Long) {
        _selectedPeriod.value = DashboardPeriod.CUSTOM
        _customRange.value = DateRange(from, to)
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSelectedCategoryId(id: Long?) { _selectedCategoryId.value = id }

    fun deleteAllTransactions() {
        viewModelScope.launch(Dispatchers.IO) { db.transactionDao().deleteAll() }
    }

    fun importFromExcel(uri: Uri) {
        viewModelScope.launch {
            try {
                val processor = ExcelProcessor(getApplication())
                val result = withContext(Dispatchers.IO) { processor.processExcel(uri) }
                if (!result.success) {
                    _importResult.emit(ImportResult.Error(result.errorMessage ?: "Error desconocido"))
                    return@launch
                }
                val (added, duplicates, skipped) = txRepo.importTransactions(result.transactions)
                _importResult.emit(ImportResult.Success(added, duplicates, skipped))
            } catch (e: Exception) {
                _importResult.emit(ImportResult.Error(e.message ?: "Error desconocido"))
            }
        }
    }

    /**
     * Importa solo los movimientos de un JSON de backup (sin tocar categorías ni excepciones).
     *
     * Semántica: **upsert por ID estable** del backup (backup/restore), distinto del flujo Excel
     * que aplica deduplicación por campos (concepto+fecha+importe). Esto es correcto porque
     * los JSON exportados por la app llevan IDs deterministas; un mismo movimiento siempre
     * tiene el mismo ID en backups distintos del mismo dispositivo.
     */
    fun importTransactionsFromBackupJson(json: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val backup = BackupManager.parseBackup(json)
            val validation = BackupManager.validateBackup(backup)
            if (validation is BackupManager.ValidationResult.Error) {
                _importResult.emit(ImportResult.Error("Backup inválido: ${validation.reason}"))
                return@launch
            }
            val txList = backup.transactions
            if (txList.isNullOrEmpty()) {
                _importResult.emit(ImportResult.Error("El JSON no contiene movimientos"))
                return@launch
            }
            db.withTransaction { db.transactionDao().upsertAll(txList) }
            _importResult.emit(ImportResult.Success(txList.size, 0, 0))
        } catch (e: IllegalArgumentException) {
            _importResult.emit(ImportResult.Error(e.message ?: "Error al parsear JSON"))
        } catch (e: Exception) {
            _importResult.emit(ImportResult.Error(e.message ?: "Error desconocido"))
        }
    }

    /** Wrapper de compatibilidad (scope ExactName). */
    fun categorize(transactionId: Long, normalizedConcept: String, categoryId: Long) {
        viewModelScope.launch { txRepo.categorize(transactionId, normalizedConcept, categoryId) }
    }

    /** Solo este movimiento, sin crear regla. */
    fun categorizeScopeOnly(transactionId: Long, categoryId: Long) {
        viewModelScope.launch { txRepo.categorizeScopeOnly(transactionId, categoryId) }
    }

    /** Aplica la categoría con el alcance elegido por el usuario. */
    fun categorizeWithScope(
        transactionId: Long,
        normalizedConcept: String,
        categoryId: Long,
        scope: CategorizationScope
    ) {
        viewModelScope.launch { txRepo.categorizeWithScope(transactionId, normalizedConcept, categoryId, scope) }
    }

    // ── Conteos para el preview del scope dialog ──────────────────────────────

    suspend fun countByExactConcept(concept: String): Int =
        withContext(Dispatchers.IO) { db.transactionDao().countByExactConcept(concept) }

    suspend fun countContainingPhrase(phrase: String): Int =
        withContext(Dispatchers.IO) { db.transactionDao().countContainingPhrase(phrase) }

    suspend fun countContainingWord(word: String): Int =
        withContext(Dispatchers.IO) { db.transactionDao().countContainingWord(word) }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch { txRepo.deleteTransaction(id) }
    }

    fun deleteTransactionsByConcept(normalizedConcept: String) {
        viewModelScope.launch { txRepo.deleteTransactionsByConcept(normalizedConcept) }
    }

    // ── Gestión de excepciones de importación ─────────────────────────────────

    fun addException(pattern: String, matchType: MatchType) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalized = DescriptionNormalizer.normalize(pattern)
            // upsertWithNormalization convierte CONTAINS_WORD → CONTAINS (no soportado en excepciones)
            exceptionDao.upsertWithNormalization(ImportExceptionEntity(patternRaw = pattern, normalizedPattern = normalized, matchType = matchType))
        }
    }

    fun toggleException(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { exceptionDao.setEnabled(id, enabled) }
    }

    fun deleteException(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            txRepo.restoreAllSkippedForException(id)
            exceptionDao.delete(id)
        }
    }

    fun skippedForException(exceptionId: Long) = skippedDao.observeByException(exceptionId)

    fun restoreSkippedTransaction(skipped: SkippedTransactionEntity) {
        viewModelScope.launch { txRepo.restoreSkippedTransaction(skipped) }
    }

    suspend fun getMatchingRulesForConcept(normalizedConcept: String): List<RuleMatchInfo> =
        withContext(Dispatchers.IO) {
            val rules = DescriptionNormalizer.findAllMatchingRules(normalizedConcept, db.categoryRuleDao())
            rules.map { rule ->
                val catName = db.categoryDao().getById(rule.categoryId)?.name ?: "Desconocida"
                RuleMatchInfo(rule, catName)
            }
        }

    fun exportToExcel() {
        viewModelScope.launch {
            try {
                val excelExporter = ExcelExporter(getApplication())
                val allTxWithCat = txRepo.getAllTransactionsOnce()
                val legacyList = allTxWithCat.map { twc ->
                    Transaction(
                        id = twc.transaction.id,
                        date = Date(twc.transaction.date),
                        amount = twc.transaction.amount,
                        concept = twc.transaction.concept,
                        category = twc.categoryName ?: "Sin categoría",
                        source = twc.transaction.source,
                        type = try { TransactionType.valueOf(twc.transaction.type) }
                               catch (e: Exception) { TransactionType.UNKNOWN },
                        balance = twc.transaction.balance,
                        isManual = twc.transaction.isManual
                    )
                }
                if (legacyList.isEmpty()) { _exportResult.emit(ExportResult.Empty); return@launch }
                val file: File = withContext(Dispatchers.IO) { excelExporter.exportToExcel(legacyList) }
                _exportResult.emit(ExportResult.Success(file))
            } catch (e: Exception) {
                _exportResult.emit(ExportResult.Error(e.message ?: "Error al exportar"))
            }
        }
    }

    // ── Helpers de color para el pie chart ───────────────────────────────────

    /** Color estable basado en el hash del nombre, elegido de la paleta fija. */
    private fun colorForName(name: String): String {
        val idx = ((name.hashCode() % PIE_PALETTE.size) + PIE_PALETTE.size) % PIE_PALETTE.size
        return PIE_PALETTE[idx]
    }

    /**
     * Devuelve el color a usar para una slice del pie chart.
     * - Si [rawColor] es null, no parseable, o es el color por defecto sin personalizar
     *   (#2196F3), asigna un color determinista basado en el nombre.
     * - De lo contrario respeta el color guardado en BD.
     * - "Otros" siempre pasa su propio #9E9E9E y no llama a esta función.
     */
    private fun normalizeColor(name: String, rawColor: String?): String {
        if (rawColor == null) return colorForName(name)
        if (rawColor.equals(DEFAULT_CAT_COLOR, ignoreCase = true)) return colorForName(name)
        return if (rawColor.matches(Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$"))) rawColor
               else colorForName(name)
    }

    // ── Datos del gráfico de tarta según el modo seleccionado ─────────────────

    private fun computePieData(
        periodFiltered: List<TransactionWithCategory>,
        mode: PieChartMode
    ): List<CategoryExpense> {
        // Política: suma algebraica de TODOS los movimientos (INCOME + EXPENSE) por categoría.
        // Solo aparecen en el pie las categorías con neto < 0 (gasto neto real).
        // Ej: Restaurantes -50€ cena + +20€ bizum vuelta = neto -30€ → aparece como 30€.
        return when (mode) {
            is PieChartMode.Parents -> {
                // Roll-up al padre; "Sin categoría" incluida cuando ambos nombres son null
                val sorted = periodFiltered
                    .groupBy {
                        val name     = it.parentCategoryName  ?: it.categoryName  ?: "Sin categoría"
                        val rawColor = it.parentCategoryColor ?: it.categoryColor
                        name to normalizeColor(name, rawColor)
                    }
                    .map { (k, txs) ->
                        val net = txs.sumOf { it.transaction.amount }
                        CategoryExpense(k.first, absNet = kotlin.math.abs(net), netAmount = net, count = txs.size, color = k.second)
                    }
                    .filter { it.netAmount < 0 }
                    .sortedByDescending { it.absNet }
                capWithOthers(sorted)
            }
            is PieChartMode.Children -> {
                // Solo categorías hija: el JOIN ya lo indica con parentCategoryName != null.
                // No depende de allCats → nunca queda vacío en cold start.
                val sorted = periodFiltered
                    .filter { it.parentCategoryName != null && it.categoryName != null }
                    .groupBy { tx ->
                        val name = tx.categoryName!!
                        name to normalizeColor(name, tx.categoryColor)
                    }
                    .map { (k, txs) ->
                        val net = txs.sumOf { it.transaction.amount }
                        CategoryExpense(k.first, absNet = kotlin.math.abs(net), netAmount = net, count = txs.size, color = k.second)
                    }
                    .filter { it.netAmount < 0 }
                    .sortedByDescending { it.absNet }
                capWithOthers(sorted)
            }
            is PieChartMode.BreakdownParent -> {
                // parentCategoryId (de la columna p.id en el JOIN) indica a qué padre pertenece
                // cada transacción de hija. No depende de allCats → correcto desde el primer emit.
                val parentId = mode.parentId
                val hasChildTxs = periodFiltered.any { it.parentCategoryId == parentId }
                val filtered = if (!hasChildTxs) {
                    // Padre sin hijos con movimientos: mostrar movimientos directos del padre
                    periodFiltered.filter { it.transaction.categoryId == parentId && it.categoryName != null }
                } else {
                    periodFiltered.filter { it.parentCategoryId == parentId && it.categoryName != null }
                }
                val sorted = filtered
                    .groupBy { tx ->
                        val name = tx.categoryName!!
                        name to normalizeColor(name, tx.categoryColor)
                    }
                    .map { (k, txs) ->
                        val net = txs.sumOf { it.transaction.amount }
                        CategoryExpense(k.first, absNet = kotlin.math.abs(net), netAmount = net, count = txs.size, color = k.second)
                    }
                    .filter { it.netAmount < 0 }
                    .sortedByDescending { it.absNet }
                capWithOthers(sorted)
            }
        }
    }

    /** Si la lista supera 8 elementos, los recorta y añade "Otros" con la suma del resto. */
    private fun capWithOthers(sorted: List<CategoryExpense>, limit: Int = 8): List<CategoryExpense> {
        if (sorted.size <= limit) return sorted
        val top = sorted.take(limit)
        val rest = sorted.drop(limit)
        val othersNet = rest.sumOf { it.netAmount }
        val othersCount = rest.sumOf { it.count }
        return top + listOf(CategoryExpense("Otros", absNet = kotlin.math.abs(othersNet), netAmount = othersNet, count = othersCount, color = "#9E9E9E"))
    }

    // ── Backup / Restore ──────────────────────────────────────────────────────

    private val _backupExportResult = MutableSharedFlow<BackupExportResult>(replay = 0)
    val backupExportResult: SharedFlow<BackupExportResult> = _backupExportResult.asSharedFlow()

    private val _backupImportResult = MutableSharedFlow<BackupImportResult>(replay = 0)
    val backupImportResult: SharedFlow<BackupImportResult> = _backupImportResult.asSharedFlow()

    fun exportBackup(
        includeCategories: Boolean,
        includeTransactions: Boolean,
        includeExceptions: Boolean
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val cats  = if (includeCategories) db.categoryDao().getAll() else null
            val rules = if (includeCategories) db.categoryRuleDao().getAll() else null
            val txs   = if (includeTransactions) db.transactionDao().getAllEntities() else null
            val excepts = if (includeExceptions) db.importExceptionDao().getAll() else null

            val includes = buildList {
                if (includeCategories) add("categories")
                if (includeTransactions) add("transactions")
                if (includeExceptions) add("exceptions")
            }

            val backupFile = BackupFile(
                metadata = BackupMetadata(
                    appVersion = BuildConfig.VERSION_NAME,
                    exportedAt = System.currentTimeMillis(),
                    includes = includes
                ),
                categories = cats,
                categoryRules = rules,
                transactions = txs,
                importExceptions = excepts
            )

            val json = BackupManager.createBackup(backupFile)
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val file = File(
                getApplication<Application>().getExternalFilesDir(null),
                "bolsillo_backup_${sdf.format(Date())}.json"
            )
            file.writeText(json)
            _backupExportResult.emit(BackupExportResult.Success(file))
        } catch (e: Exception) {
            _backupExportResult.emit(BackupExportResult.Error(e.message ?: "Error desconocido"))
        }
    }

    fun importBackup(json: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val backup = BackupManager.parseBackup(json)
            val validation = BackupManager.validateBackup(backup)
            if (validation is BackupManager.ValidationResult.Error) {
                _backupImportResult.emit(BackupImportResult.ValidationFailed(validation.reason))
                return@launch
            }
            val importer = BackupImporter(
                db.categoryDao(), db.categoryRuleDao(),
                db.transactionDao(), db.importExceptionDao(),
                transactionRunner = { block -> db.withTransaction(block) }
            )
            val summary = importer.import(backup)
            _backupImportResult.emit(BackupImportResult.Success(summary.toHumanString()))
        } catch (e: IllegalArgumentException) {
            _backupImportResult.emit(BackupImportResult.Error(e.message ?: "Error al parsear"))
        } catch (e: Exception) {
            _backupImportResult.emit(BackupImportResult.Error(e.message ?: "Error desconocido"))
        }
    }
}

// ── Resultados one-shot ───────────────────────────────────────────────────────

sealed class ImportResult {
    data class Success(val added: Int, val duplicates: Int, val skipped: Int = 0) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

sealed class ExportResult {
    data class Success(val file: File) : ExportResult()
    data class Error(val message: String) : ExportResult()
    object Empty : ExportResult()
}

sealed class BackupExportResult {
    data class Success(val file: File) : BackupExportResult()
    data class Error(val msg: String) : BackupExportResult()
}

sealed class BackupImportResult {
    data class Success(val summary: String) : BackupImportResult()
    data class Error(val msg: String) : BackupImportResult()
    data class ValidationFailed(val reason: String) : BackupImportResult()
}
