package com.jesusluna.bolsilloplus.ui.statistics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jesusluna.bolsilloplus.data.db.AppDatabase
import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.data.repository.CategoryRepository
import com.jesusluna.bolsilloplus.data.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── Modo de tiempo ────────────────────────────────────────────────────────────

sealed class TimeMode {
    data class YearMonth(val year: Int?, val month: Date?) : TimeMode()
    data class CustomRange(val from: Long, val to: Long) : TimeMode()
}

// ── Data classes de estadísticas ──────────────────────────────────────────────

data class StatsData(
    val income: Double = 0.0,
    val expenses: Double = 0.0,
    val balance: Double = 0.0,
    val transactionCount: Int = 0,
    val categoryBreakdown: List<ParentCategoryBreakdown> = emptyList(),
    val netEvolution: List<NetPoint> = emptyList(),
    val stackedBars: List<StackedBarEntry> = emptyList(),
    val topMerchants: List<MerchantStat> = emptyList(),
    val sourceDistribution: List<SourceStat> = emptyList(),
    val incomeExpenseEvolution: List<IncomeExpensePoint> = emptyList(),
    val allCategoryColors: Map<String, String> = emptyMap()
)

data class ParentCategoryBreakdown(
    val parentName: String,
    val parentColor: String,
    val totalAmount: Double,
    val directAmount: Double,
    val childrenBreakdown: List<ChildCategoryBreakdown>
)

data class ChildCategoryBreakdown(
    val name: String,
    val color: String,
    val amount: Double,
    val percentage: Double
)

data class NetPoint(val label: String, val net: Double)

data class IncomeExpensePoint(val label: String, val income: Double, val expenses: Double)

data class StackedBarEntry(val label: String, val categoryAmounts: Map<String, Double>)

data class MerchantStat(val concept: String, val amount: Double, val count: Int)

data class SourceStat(val source: String, val amount: Double, val count: Int)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class StatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val txRepo = TransactionRepository(
        db.transactionDao(), db.categoryRuleDao(),
        db.importExceptionDao(), db.skippedTransactionDao(), db.categoryDao()
    )
    private val catRepo = CategoryRepository(db.categoryDao(), db.transactionDao())

    val allCategories: StateFlow<List<CategoryEntity>> =
        catRepo.allCategories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _allTransactions: StateFlow<List<TransactionWithCategory>> =
        txRepo.allTransactions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _timeMode = MutableStateFlow<TimeMode>(TimeMode.YearMonth(null, null))
    val timeMode: StateFlow<TimeMode> = _timeMode.asStateFlow()

    // Años disponibles derivados de todas las transacciones
    val availableYears: StateFlow<List<Int>> = _allTransactions.map { txs ->
        txs.map { Calendar.getInstance().apply { timeInMillis = it.transaction.date }.get(Calendar.YEAR) }
            .distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Transacciones filtradas según el modo de tiempo
    val filteredTransactions: StateFlow<List<TransactionWithCategory>> =
        combine(_allTransactions, _timeMode) { allTx, mode ->
            when (mode) {
                is TimeMode.YearMonth -> allTx.filter { tx ->
                    val cal = Calendar.getInstance().apply { timeInMillis = tx.transaction.date }
                    val yearOk = mode.year == null || cal.get(Calendar.YEAR) == mode.year
                    val monthOk = mode.month == null || run {
                        val mCal = Calendar.getInstance().apply { time = mode.month }
                        cal.get(Calendar.YEAR) == mCal.get(Calendar.YEAR) &&
                            cal.get(Calendar.MONTH) == mCal.get(Calendar.MONTH)
                    }
                    yearOk && monthOk
                }
                is TimeMode.CustomRange ->
                    allTx.filter { it.transaction.date in mode.from..mode.to }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Estadísticas derivadas de filteredTransactions + allCategories
    val statsData: StateFlow<StatsData> =
        combine(filteredTransactions, allCategories) { filtered, cats ->
            computeStats(filtered, cats)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsData())

    fun setYearMonth(year: Int?, month: Date?) {
        _timeMode.value = TimeMode.YearMonth(year, month)
    }

    fun setCustomRange(from: Long, to: Long) {
        _timeMode.value = TimeMode.CustomRange(from, to)
    }

    private fun computeStats(filtered: List<TransactionWithCategory>, cats: List<CategoryEntity>): StatsData {
        val income = filtered.filter { it.transaction.type == "INCOME" }.sumOf { it.transaction.amount }
        val expenses = filtered.filter { it.transaction.type == "EXPENSE" }.sumOf { kotlin.math.abs(it.transaction.amount) }

        // Jerarquía de categorías
        val rootCats = cats.filter { it.parentId == null }
        val childCatsByParent = cats.filter { it.parentId != null }.groupBy { it.parentId!! }
        val expenseTxs = filtered.filter { it.transaction.type == "EXPENSE" }

        val breakdown = rootCats.mapNotNull { parent ->
            val childIds = childCatsByParent[parent.id]?.map { it.id }?.toSet() ?: emptySet()
            val directTxs = expenseTxs.filter { it.transaction.categoryId == parent.id }
            val childTxs = expenseTxs.filter { it.transaction.categoryId in childIds }
            val total = (directTxs + childTxs).sumOf { kotlin.math.abs(it.transaction.amount) }
            if (total == 0.0) return@mapNotNull null

            val directAmount = directTxs.sumOf { kotlin.math.abs(it.transaction.amount) }
            val childBreakdown = (childCatsByParent[parent.id] ?: emptyList()).mapNotNull { child ->
                val amt = expenseTxs.filter { it.transaction.categoryId == child.id }
                    .sumOf { kotlin.math.abs(it.transaction.amount) }
                if (amt == 0.0) null
                else ChildCategoryBreakdown(
                    child.name,
                    child.color ?: "#9E9E9E",
                    amt,
                    if (total > 0) amt / total else 0.0
                )
            }.sortedByDescending { it.amount }

            ParentCategoryBreakdown(
                parent.name,
                parent.color ?: "#9E9E9E",
                total,
                directAmount,
                childBreakdown
            )
        }.sortedByDescending { it.totalAmount }

        val netEvolution = StatisticsComputer.computeNetEvolution(filtered)
        val incomeExpenseEvolution = StatisticsComputer.computeIncomeExpenseEvolution(filtered)
        val stackedBars = StatisticsComputer.computeStackedBars(expenseTxs, rootCats, childCatsByParent)
        val topMerchants = StatisticsComputer.computeTopMerchants(expenseTxs)

        val sourceDistribution = filtered
            .groupBy { it.transaction.source }
            .map { (src, txs) ->
                SourceStat(src, txs.sumOf { kotlin.math.abs(it.transaction.amount) }, txs.size)
            }
            .sortedByDescending { it.amount }

        val allCategoryColors = rootCats.associate { it.name to (it.color ?: "#9E9E9E") }

        return StatsData(income, expenses, income - expenses, filtered.size,
            breakdown, netEvolution, stackedBars, topMerchants, sourceDistribution,
            incomeExpenseEvolution, allCategoryColors)
    }

}
