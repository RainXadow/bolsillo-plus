package com.jesusluna.bolsilloplus.util

import com.jesusluna.bolsilloplus.data.db.dao.CategoryRuleDao
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import java.util.regex.PatternSyntaxException

/** Resultado de encontrar una regla ganadora para un concepto. */
data class CategoryMatch(val categoryId: Long, val ruleId: Long)

object DescriptionNormalizer {

    /**
     * Normaliza una descripción/concepto para comparación:
     * - minúsculas
     * - trim + colapsar espacios múltiples
     */
    fun normalize(text: String): String = text
        .lowercase()
        .trim()
        .replace(Regex("\\s+"), " ")

    /**
     * Ordinal de tipo para usar como criterio de desempate secundario.
     *
     * Cuando dos reglas tienen la misma priority, el tipo decide:
     *   EXACT(3) > CONTAINS(2) > CONTAINS_WORD(1) > REGEX(0)
     *
     * Esto garantiza que, con prioridades iguales, una regla EXACT gana
     * a una CONTAINS, manteniendo el comportamiento intuitivo por defecto.
     */
    private val CategoryRuleEntity.typeOrdinal: Int
        get() = when (matchType) {
            MatchType.EXACT          -> 3
            MatchType.CONTAINS       -> 2
            MatchType.CONTAINS_WORD  -> 1
            MatchType.REGEX          -> 0
        }

    /**
     * Comprueba si [normalizedConcept] contiene [word] como palabra completa
     * (respetando límites de palabra con \b).
     */
    private fun matchesWord(normalizedConcept: String, word: String): Boolean =
        try {
            Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(normalizedConcept)
        } catch (e: Exception) {
            false
        }

    /**
     * Devuelve TODAS las reglas activas que coinciden con [normalizedConcept],
     * ordenadas de mayor a menor score efectivo.
     * La primera de la lista es la que gana (se usa como winner en [findMatch]).
     */
    suspend fun findAllMatchingRules(
        normalizedConcept: String,
        dao: CategoryRuleDao
    ): List<CategoryRuleEntity> {
        val results = mutableListOf<CategoryRuleEntity>()

        // EXACT (isEnabled=1 ya filtrado en DAO)
        dao.findExactRule(normalizedConcept)?.let { results.add(it) }

        // CONTAINS
        dao.getAllContainsRules()
            .filter { normalizedConcept.contains(it.normalizedPattern) }
            .forEach { results.add(it) }

        // CONTAINS_WORD
        dao.getAllContainsWordRules()
            .filter { matchesWord(normalizedConcept, it.normalizedPattern) }
            .forEach { results.add(it) }

        // REGEX
        dao.getAllRegexRules()
            .filter {
                try { Regex(it.normalizedPattern).containsMatchIn(normalizedConcept) }
                catch (e: PatternSyntaxException) { false }
            }
            .forEach { results.add(it) }

        // Orden de desempate: priority (usuario) → typeOrdinal → patternLength
        // La priority es el criterio principal: el usuario puede hacer ganar un CONTAINS
        // sobre un EXACT simplemente asignándole una priority mayor.
        // Si dos reglas tienen la misma priority, el tipo actúa como desempate
        // (EXACT > CONTAINS > CONTAINS_WORD > REGEX), y la longitud del patrón
        // rompe el último empate (patrón más específico = más largo gana).
        return results.sortedWith(
            compareByDescending<CategoryRuleEntity> { it.priority }
                .thenByDescending { it.typeOrdinal }
                .thenByDescending { it.normalizedPattern.length }
        )
    }

    /**
     * Busca la regla ganadora para [normalizedConcept].
     * Retorna un [CategoryMatch] con el categoryId y el ruleId de la regla ganadora.
     */
    suspend fun findMatch(
        normalizedConcept: String,
        dao: CategoryRuleDao
    ): CategoryMatch? {
        val winner = findAllMatchingRules(normalizedConcept, dao).firstOrNull() ?: return null
        return CategoryMatch(winner.categoryId, winner.id)
    }

    /**
     * Wrapper de compatibilidad: retorna solo el categoryId del ganador.
     */
    suspend fun findCategoryId(
        normalizedConcept: String,
        dao: CategoryRuleDao
    ): Long? = findMatch(normalizedConcept, dao)?.categoryId

    /**
     * Sugiere la palabra más representativa de un concepto normalizado
     * para usar como patrón CONTAINS_WORD por defecto en el scope dialog.
     */
    fun suggestWord(normalizedConcept: String): String =
        normalizedConcept.split(" ")
            .filter { it.length > 3 }
            .maxByOrNull { it.length }
            ?: normalizedConcept.split(" ").first()
}
