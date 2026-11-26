package com.jesusluna.bolsilloplus.data.repository

import com.jesusluna.bolsilloplus.data.db.dao.CategoryRuleDao
import com.jesusluna.bolsilloplus.data.db.dao.TransactionDao
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class CategoryRuleRepository(
    private val dao: CategoryRuleDao,
    private val transactionDao: TransactionDao
) {

    fun getRulesByCategory(categoryId: Long): Flow<List<CategoryRuleEntity>> =
        dao.observeRulesByCategory(categoryId)

    /**
     * Elimina la regla y reasigna los movimientos que la tenían como ganadora.
     *
     * Tras el borrado, cada movimiento afectado se re-escanea contra las reglas restantes:
     *  - Si hay otra regla que coincide → se asigna el nuevo ganador + nuevo candidateRuleCount.
     *  - Si no hay ninguna → se deja el categoryId existente pero se limpia el ruleId y el count.
     *
     * Esto corrige el bug en que candidateRuleCount se ponía siempre a 0 aunque quedaran
     * otras reglas candidatas (ej: 2→1 en lugar de 2→0).
     */
    suspend fun delete(ruleId: Long) = withContext(Dispatchers.IO) {
        // 1. Recoger movimientos afectados ANTES de borrar la regla
        val affected = transactionDao.getAllByAssignedRule(ruleId)

        // 2. Borrar la regla (ya no aparecerá en findAllMatchingRules)
        dao.delete(ruleId)

        // 3. Reasignar movimientos afectados agrupando por concepto normalizado.
        //    findAllMatchingRules hace 4 queries por concepto; agrupar reduce de
        //    O(N_transacciones) a O(N_conceptos_únicos) → clave con reglas amplias (CONTAINS).
        if (affected.isNotEmpty()) {
            val updated = affected
                .groupBy { it.normalizedConcept }
                .flatMap { (concept, txsForConcept) ->
                    val newRules = DescriptionNormalizer.findAllMatchingRules(concept, dao)
                    val winner = newRules.firstOrNull()
                    txsForConcept.map { tx ->
                        tx.copy(
                            assignedByRuleId = winner?.id,
                            candidateRuleCount = newRules.size,
                            categoryId = winner?.categoryId ?: tx.categoryId
                        )
                    }
                }
            transactionDao.upsertAll(updated)
        }
    }
}
