package com.jesusluna.bolsilloplus.scoring

import com.jesusluna.bolsilloplus.data.db.dao.CategoryRuleDao
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Implementación en memoria de [CategoryRuleDao] para tests unitarios puros (JVM).
 *
 * Replica exactamente la lógica SQL del DAO generado por Room:
 * - findExactRule: filtra matchType=EXACT, isEnabled=1, ordena por priority DESC, LIMIT 1
 * - getAllContainsRules: filtra matchType=CONTAINS, isEnabled=1, ordena por priority DESC
 * - getAllContainsWordRules: filtra matchType=CONTAINS_WORD, isEnabled=1, ordena por priority DESC
 * - getAllRegexRules: filtra matchType=REGEX, isEnabled=1, ordena por priority DESC
 * - findByPatternAndType: SIN filtro isEnabled (comportamiento real del DAO)
 * - upsert: inserta con auto-id si id=0, reemplaza por PK si id!=0
 */
class FakeCategoryRuleDao : CategoryRuleDao {

    private val _rules = mutableListOf<CategoryRuleEntity>()
    private var nextId = 1L

    // ── Helpers de test ────────────────────────────────────────────────────────

    /** Inserta una regla asignando auto-ID si id=0. Devuelve el id asignado. */
    fun insertRule(rule: CategoryRuleEntity): Long {
        val id = if (rule.id == 0L) nextId++ else rule.id
        _rules.removeAll { it.id == id }
        _rules.add(rule.copy(id = id))
        return id
    }

    /** Devuelve todas las reglas almacenadas (incluidas las desactivadas). */
    fun allRules(): List<CategoryRuleEntity> = _rules.toList()

    /** Resetea el estado para usar en @Before. */
    fun clear() {
        _rules.clear()
        nextId = 1L
    }

    // ── CategoryRuleDao implementation ─────────────────────────────────────────

    override suspend fun findExactRule(normalizedText: String): CategoryRuleEntity? =
        _rules.filter {
            it.matchType == MatchType.EXACT && it.isEnabled && it.normalizedPattern == normalizedText
        }.maxByOrNull { it.priority }

    /**
     * NOTA: sin filtro isEnabled — igual que el DAO real.
     * Esto permite que scopes reutilicen/actualicen reglas desactivadas.
     */
    override suspend fun findByPatternAndType(pattern: String, matchType: MatchType): CategoryRuleEntity? =
        _rules.firstOrNull { it.normalizedPattern == pattern && it.matchType == matchType }

    override suspend fun getAllContainsRules(): List<CategoryRuleEntity> =
        _rules.filter { it.matchType == MatchType.CONTAINS && it.isEnabled }
            .sortedByDescending { it.priority }

    override suspend fun getAllContainsWordRules(): List<CategoryRuleEntity> =
        _rules.filter { it.matchType == MatchType.CONTAINS_WORD && it.isEnabled }
            .sortedByDescending { it.priority }

    override suspend fun getAllRegexRules(): List<CategoryRuleEntity> =
        _rules.filter { it.matchType == MatchType.REGEX && it.isEnabled }
            .sortedByDescending { it.priority }

    override fun observeRulesByCategory(categoryId: Long): Flow<List<CategoryRuleEntity>> =
        flowOf(_rules.filter { it.categoryId == categoryId })

    override suspend fun findById(id: Long): CategoryRuleEntity? =
        _rules.firstOrNull { it.id == id }

    override suspend fun updatePriority(id: Long, priority: Int) {
        val idx = _rules.indexOfFirst { it.id == id }
        if (idx >= 0) _rules[idx] = _rules[idx].copy(priority = priority)
    }

    override suspend fun updateEnabled(id: Long, enabled: Boolean) {
        val idx = _rules.indexOfFirst { it.id == id }
        if (idx >= 0) _rules[idx] = _rules[idx].copy(isEnabled = enabled)
    }

    override suspend fun upsert(rule: CategoryRuleEntity) {
        if (rule.id == 0L) {
            _rules.add(rule.copy(id = nextId++))
        } else {
            val idx = _rules.indexOfFirst { it.id == rule.id }
            if (idx >= 0) _rules[idx] = rule else _rules.add(rule)
        }
    }

    override suspend fun findByLogicalKey(categoryId: Long, pattern: String, matchType: MatchType): CategoryRuleEntity? =
        _rules.firstOrNull { it.categoryId == categoryId && it.normalizedPattern == pattern && it.matchType == matchType }

    override suspend fun delete(id: Long) {
        _rules.removeAll { it.id == id }
    }

    override suspend fun count(): Int = _rules.size

    override suspend fun getAll(): List<CategoryRuleEntity> = _rules.toList()

    override suspend fun deleteAll() { _rules.clear() }
}
