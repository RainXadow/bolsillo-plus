package com.jesusluna.bolsilloplus.ui.categories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jesusluna.bolsilloplus.data.db.AppDatabase
import com.jesusluna.bolsilloplus.data.db.dao.CategoryWithCount
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import com.jesusluna.bolsilloplus.data.repository.CategorizationScope
import com.jesusluna.bolsilloplus.data.repository.CategoryRepository
import com.jesusluna.bolsilloplus.data.repository.CategoryRuleRepository
import com.jesusluna.bolsilloplus.data.repository.TransactionRepository
import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoriesViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val catRepo = CategoryRepository(db.categoryDao(), db.transactionDao())
    private val ruleRepo = CategoryRuleRepository(db.categoryRuleDao(), db.transactionDao())
    private val txRepo = TransactionRepository(db.transactionDao(), db.categoryRuleDao(), db.importExceptionDao(), db.skippedTransactionDao(), db.categoryDao())

    val categoriesWithCount: StateFlow<List<CategoryWithCount>> =
        catRepo.allCategoriesWithCount
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ─── Resultado del escaneo masivo de reglas ───────────────────────────────
    private val _applyRulesResult = MutableSharedFlow<ApplyRulesResult>(extraBufferCapacity = 1)
    val applyRulesResult = _applyRulesResult.asSharedFlow()

    // ─── Resultado de reasignación masiva entre categorías ────────────────────
    private val _reassignResult = MutableSharedFlow<ReassignResult>(extraBufferCapacity = 1)
    val reassignResult = _reassignResult.asSharedFlow()

    // ─── Consultas por categoría ──────────────────────────────────────────────

    fun getTransactionsForCategory(categoryId: Long): Flow<List<TransactionEntity>> =
        txRepo.transactionsByCategory(categoryId)

    /** Subárbol reactivo: padre + todos sus hijos directos. Solo para root (parentId == null). */
    fun getTransactionsForParentTree(categoryId: Long): Flow<List<TransactionEntity>> =
        txRepo.transactionsByParentTree(categoryId)

    fun observeChildrenOf(parentId: Long): Flow<List<CategoryEntity>> =
        catRepo.observeChildrenOf(parentId)

    suspend fun countChildren(categoryId: Long): Int =
        withContext(Dispatchers.IO) { db.categoryDao().countChildren(categoryId) }

    /** Cuenta solo las txs propias de la categoría (sin subárbol). Para el delete dialog. */
    suspend fun countOwnTransactions(categoryId: Long): Int =
        withContext(Dispatchers.IO) { db.transactionDao().countByCategoryId(categoryId) }

    fun createSubcategory(name: String, color: String = "#2196F3", parentId: Long) {
        viewModelScope.launch { catRepo.insertChild(name, color, parentId) }
    }

    fun getRulesForCategory(categoryId: Long): Flow<List<CategoryRuleEntity>> =
        ruleRepo.getRulesByCategory(categoryId)

    // ─── CRUD categorías ─────────────────────────────────────────────────────

    fun createCategory(name: String, color: String = "#2196F3") {
        viewModelScope.launch {
            catRepo.insert(CategoryEntity(name = name, color = color))
        }
    }

    fun renameCategory(category: CategoryEntity, newName: String) {
        viewModelScope.launch {
            catRepo.update(category.copy(name = newName))
        }
    }

    fun delete(categoryId: Long) {
        viewModelScope.launch { catRepo.delete(categoryId) }
    }

    // ─── CRUD reglas de automatización ───────────────────────────────────────

    /**
     * Añade o actualiza una regla de categorización para una categoría.
     * Normaliza el patrón antes de guardarlo.
     */
    fun addRule(categoryId: Long, rawPattern: String, matchType: MatchType) {
        viewModelScope.launch {
            val normalized = DescriptionNormalizer.normalize(rawPattern)
            if (normalized.isBlank()) return@launch

            // Si ya existe una regla con el mismo patrón+tipo, actualizarla
            val existing = db.categoryRuleDao().findByPatternAndType(normalized, matchType)
            val rule = if (existing != null) {
                existing.copy(categoryId = categoryId)
            } else {
                CategoryRuleEntity(
                    normalizedPattern = normalized,
                    categoryId = categoryId,
                    matchType = matchType,
                    priority = matchType.defaultPriority
                )
            }
            db.categoryRuleDao().upsert(rule)
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch { ruleRepo.delete(ruleId) }
    }

    /** Actualiza la prioridad de una regla. A mayor número, mayor precedencia. */
    fun updateRulePriority(ruleId: Long, newPriority: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            db.categoryRuleDao().updatePriority(ruleId, newPriority)
        }
    }

    /** Activa o desactiva una regla sin borrarla. */
    fun toggleRule(ruleId: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            db.categoryRuleDao().updateEnabled(ruleId, enabled)
        }
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

    /**
     * Devuelve todas las reglas que coinciden con [normalizedConcept], ordenadas
     * por peso efectivo (la primera es la ganadora). Enriquecidas con el nombre de categoría.
     */
    suspend fun getMatchingRulesForConcept(normalizedConcept: String): List<RuleMatchInfo> =
        withContext(Dispatchers.IO) {
            val rules = DescriptionNormalizer.findAllMatchingRules(
                normalizedConcept,
                db.categoryRuleDao()
            )
            rules.map { rule ->
                val catName = db.categoryDao().getById(rule.categoryId)?.name ?: "Desconocida"
                RuleMatchInfo(rule, catName)
            }
        }

    // ─── Reasignar todos los movimientos de una categoría a otra ─────────────

    /**
     * Mueve todos los movimientos de [fromCategoryId] a [toCategoryId].
     * Emite el resultado con el número de movimientos afectados.
     */
    fun reassignAll(fromCategoryId: Long, toCategoryId: Long) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val count = db.transactionDao().reassignAllByCategory(fromCategoryId, toCategoryId)
                    ReassignResult.Success(count)
                } catch (e: Exception) {
                    ReassignResult.Error(e.message ?: "Error desconocido")
                }
            }
            _reassignResult.emit(result)
        }
    }

    // ─── Aplicar reglas masivamente ───────────────────────────────────────────

    /**
     * Escanea todos los cobros sin categoría y aplica las reglas existentes.
     * Emite el número de cobros categorizados vía [applyRulesResult].
     */
    fun applyRulesToAll() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // Procesa TODOS los no-manuales: sin categoría + ya categorizados
                    // automáticamente (para reflejar cambios de prioridad/reglas nuevas).
                    val toProcess = db.transactionDao().getAllNonManual()
                    var updated = 0

                    toProcess.forEach { tx ->
                        val matchingRules = DescriptionNormalizer.findAllMatchingRules(
                            tx.normalizedConcept,
                            db.categoryRuleDao()
                        )
                        if (matchingRules.isNotEmpty()) {
                            val winner = matchingRules.first()
                            db.transactionDao().updateCategoryWithRuleAndCount(
                                tx.id, winner.categoryId, winner.id, matchingRules.size
                            )
                            updated++
                        }
                        // Si no hay reglas que coincidan, se deja como está
                        // (no se borra la categoría existente)
                    }

                    ApplyRulesResult.Success(
                        categorized = updated,
                        total = toProcess.size
                    )
                } catch (e: Exception) {
                    ApplyRulesResult.Error(e.message ?: "Error desconocido")
                }
            }
            _applyRulesResult.emit(result)
        }
    }
}

sealed class ApplyRulesResult {
    data class Success(val categorized: Int, val total: Int) : ApplyRulesResult()
    data class Error(val message: String) : ApplyRulesResult()
}

sealed class ReassignResult {
    data class Success(val count: Int) : ReassignResult()
    data class Error(val message: String) : ReassignResult()
}

/** Regla que coincide con un concepto, enriquecida con el nombre de su categoría. */
data class RuleMatchInfo(
    val rule: CategoryRuleEntity,
    val categoryName: String
)
