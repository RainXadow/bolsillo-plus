package com.jesusluna.bolsilloplus.ui.statistics

import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Funciones puras de cálculo estadístico.
 * Sin dependencias Android: solo java.util.Calendar/SimpleDateFormat + datos de Room.
 * Testeable en JVM sin instrumentación.
 */
object StatisticsComputer {

    fun computeNetEvolution(filtered: List<TransactionWithCategory>): List<NetPoint> {
        if (filtered.isEmpty()) return emptyList()
        val sorted = filtered.sortedBy { it.transaction.date }
        val first = sorted.first().transaction.date
        val last = sorted.last().transaction.date
        val rangeDays = ((last - first) / 86_400_000L).toInt() + 1

        val groupBy = when {
            rangeDays <= 31  -> "day"
            rangeDays <= 180 -> "week"
            else             -> "month"
        }

        return sorted.groupBy { tx ->
            val cal = Calendar.getInstance().apply { timeInMillis = tx.transaction.date }
            when (groupBy) {
                "day"  -> "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
                "week" -> "${cal.get(Calendar.YEAR)}-W${cal.get(Calendar.WEEK_OF_YEAR)}"
                else   -> "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            }
        }.entries.sortedBy { (_, txs) -> txs.minOf { it.transaction.date } }
         .mapIndexed { i, (_, txs) ->
            val net = txs.filter { it.transaction.type == "INCOME" }.sumOf { kotlin.math.abs(it.transaction.amount) } -
                      txs.filter { it.transaction.type == "EXPENSE" }.sumOf { kotlin.math.abs(it.transaction.amount) }
            val label = when (groupBy) {
                "day"  -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(txs.minOf { it.transaction.date }))
                "week" -> "S${i + 1}"
                else   -> SimpleDateFormat("MMM yy", Locale("es", "ES")).format(Date(txs.minOf { it.transaction.date }))
            }
            NetPoint(label, net)
        }
    }

    fun computeIncomeExpenseEvolution(filtered: List<TransactionWithCategory>): List<IncomeExpensePoint> {
        if (filtered.isEmpty()) return emptyList()
        val sorted = filtered.sortedBy { it.transaction.date }
        val first = sorted.first().transaction.date
        val last = sorted.last().transaction.date
        val rangeDays = ((last - first) / 86_400_000L).toInt() + 1

        val groupBy = when {
            rangeDays <= 31  -> "day"
            rangeDays <= 180 -> "week"
            else             -> "month"
        }

        return sorted.groupBy { tx ->
            val cal = Calendar.getInstance().apply { timeInMillis = tx.transaction.date }
            when (groupBy) {
                "day"  -> "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
                "week" -> "${cal.get(Calendar.YEAR)}-W${cal.get(Calendar.WEEK_OF_YEAR)}"
                else   -> "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            }
        }.entries.sortedBy { (_, txs) -> txs.minOf { it.transaction.date } }
         .mapIndexed { i, (_, txs) ->
            val inc = txs.filter { it.transaction.type == "INCOME" }.sumOf { kotlin.math.abs(it.transaction.amount) }
            val exp = txs.filter { it.transaction.type == "EXPENSE" }.sumOf { kotlin.math.abs(it.transaction.amount) }
            val label = when (groupBy) {
                "day"  -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(txs.minOf { it.transaction.date }))
                "week" -> "S${i + 1}"
                else   -> SimpleDateFormat("MMM yy", Locale("es", "ES")).format(Date(txs.minOf { it.transaction.date }))
            }
            IncomeExpensePoint(label, inc, exp)
        }
    }

    fun computeStackedBars(
        expenseTxs: List<TransactionWithCategory>,
        rootCats: List<CategoryEntity>,
        childsByParent: Map<Long, List<CategoryEntity>>
    ): List<StackedBarEntry> {
        if (expenseTxs.isEmpty()) return emptyList()
        val sorted = expenseTxs.sortedBy { it.transaction.date }
        val rangeDays = ((sorted.last().transaction.date - sorted.first().transaction.date) / 86_400_000L).toInt() + 1
        val groupBy = if (rangeDays <= 60) "week" else "month"

        val groups = sorted.groupBy { tx ->
            val cal = Calendar.getInstance().apply { timeInMillis = tx.transaction.date }
            if (groupBy == "week")
                "${cal.get(Calendar.YEAR)}-W${cal.get(Calendar.WEEK_OF_YEAR)}"
            else
                "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        }

        return groups.entries.sortedBy { (_, txs) -> txs.minOf { it.transaction.date } }.map { (_, txs) ->
            val label = SimpleDateFormat(
                if (groupBy == "week") "dd/MM" else "MMM yy",
                Locale("es", "ES")
            ).format(Date(txs.first().transaction.date))

            val catAmounts = rootCats.associate { parent ->
                val childIds = childsByParent[parent.id]?.map { it.id }?.toSet() ?: emptySet()
                val amt = txs.filter {
                    it.transaction.categoryId == parent.id || it.transaction.categoryId in childIds
                }.sumOf { kotlin.math.abs(it.transaction.amount) }
                parent.name to amt
            }
            StackedBarEntry(label, catAmounts)
        }
    }

    fun computeTopMerchants(
        expenseTxs: List<TransactionWithCategory>,
        limit: Int = 15
    ): List<MerchantStat> =
        expenseTxs
            .groupBy { it.transaction.normalizedConcept }
            .map { (concept, txs) ->
                MerchantStat(concept, txs.sumOf { kotlin.math.abs(it.transaction.amount) }, txs.size)
            }
            .sortedByDescending { it.amount }
            .take(limit)
}
