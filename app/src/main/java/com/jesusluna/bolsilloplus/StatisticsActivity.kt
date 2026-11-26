package com.jesusluna.bolsilloplus

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jesusluna.bolsilloplus.ui.categories.CategoriesActivity
import com.jesusluna.bolsilloplus.ui.statistics.ChildCategoryBreakdown
import com.jesusluna.bolsilloplus.ui.statistics.MerchantStat
import com.jesusluna.bolsilloplus.ui.statistics.ParentCategoryBreakdown
import com.jesusluna.bolsilloplus.ui.statistics.StatsData
import com.jesusluna.bolsilloplus.ui.statistics.StatisticsViewModel
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class StatisticsActivity : AppCompatActivity() {

    private val viewModel: StatisticsViewModel by viewModels()

    private lateinit var chipGroupTimeMode: ChipGroup
    private lateinit var chipCustomRange: Chip
    private lateinit var layoutPeriodFilter: LinearLayout
    private lateinit var spinnerYear: Spinner
    private lateinit var spinnerMonth: Spinner

    private var ignoreChipChange = false

    private lateinit var tvTotalIncome: TextView
    private lateinit var tvTotalExpenses: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvTransactionCount: TextView

    private lateinit var barChartComparison: BarChart
    private lateinit var lineChartNet: LineChart
    private lateinit var tvNetChartEmpty: TextView
    private lateinit var layoutCategories: LinearLayout
    private lateinit var btnManageCategories: Button
    private lateinit var barChartStacked: BarChart
    private lateinit var tvStackedEmpty: TextView
    private lateinit var layoutTopMerchants: LinearLayout
    private lateinit var lineChartIncomeExpense: LineChart
    private lateinit var tvIncomeExpenseEmpty: TextView

    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("es", "ES"))
    private val monthYearFormat = SimpleDateFormat("MMMM yyyy", Locale("es", "ES"))
    private val monthOnlyFormat = SimpleDateFormat("MMMM", Locale("es", "ES"))

    private var selectedYear: Int? = null
    private var selectedMonth: Date? = null

    private val isNightMode: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    private val chartColors = listOf(
        "#4E79A7", "#F28E2B", "#E15759", "#76B7B2",
        "#59A14F", "#EDC948", "#B07AA1", "#FF9DA7",
        "#9C755F", "#AFBF9B", "#D37295", "#00BCD4"
    )

    // Color por defecto de CategoryEntity (sin personalizar).
    private val defaultCatColor = "#2196F3"

    /** Color determinista basado en el nombre, elegido de chartColors. */
    private fun colorForName(name: String): Int {
        val idx = ((name.hashCode() % chartColors.size) + chartColors.size) % chartColors.size
        return Color.parseColor(chartColors[idx])
    }

    /**
     * Color a usar para la categoría [name] con color en BD [rawColor].
     * Si rawColor es null, inválido o es el color default sin personalizar → colorForName.
     * De lo contrario respeta el color guardado.
     */
    private fun normalizeColor(name: String, rawColor: String?): Int {
        if (rawColor == null) return colorForName(name)
        if (rawColor.equals(defaultCatColor, ignoreCase = true)) return colorForName(name)
        return try { Color.parseColor(rawColor) } catch (e: Exception) { colorForName(name) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_statistics)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initViews()
        setupButtons()
        setupTimeModeChips()
        setupCharts()
        observeViewModel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initViews() {
        chipGroupTimeMode = findViewById(R.id.chip_group_time_mode)
        chipCustomRange = chipGroupTimeMode.findViewById(R.id.chip_specific_day)
        layoutPeriodFilter = findViewById(R.id.layout_period_filter)
        spinnerYear = findViewById(R.id.spinnerYear)
        spinnerMonth = findViewById(R.id.spinnerMonth)

        tvTotalIncome = findViewById(R.id.tvTotalIncome)
        tvTotalExpenses = findViewById(R.id.tvTotalExpenses)
        tvBalance = findViewById(R.id.tvBalance)
        tvTransactionCount = findViewById(R.id.tvTransactionCount)

        barChartComparison = findViewById(R.id.bar_chart_comparison)
        lineChartNet = findViewById(R.id.line_chart_net)
        tvNetChartEmpty = findViewById(R.id.tv_net_chart_empty)
        layoutCategories = findViewById(R.id.layoutCategories)
        btnManageCategories = findViewById(R.id.btnManageCategories)
        barChartStacked = findViewById(R.id.bar_chart_stacked)
        tvStackedEmpty = findViewById(R.id.tv_stacked_empty)
        layoutTopMerchants = findViewById(R.id.layout_top_merchants)
        lineChartIncomeExpense = findViewById(R.id.line_chart_income_expense)
        tvIncomeExpenseEmpty = findViewById(R.id.tv_income_expense_empty)
    }

    private fun setupButtons() {
        btnManageCategories.setOnClickListener {
            startActivity(Intent(this, CategoriesActivity::class.java))
        }
    }

    private fun setupTimeModeChips() {
        chipGroupTimeMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (ignoreChipChange) return@setOnCheckedStateChangeListener
            when (checkedIds.firstOrNull()) {
                R.id.chip_by_period -> {
                    layoutPeriodFilter.visibility = View.VISIBLE
                    chipCustomRange.text = "🗓 Personalizar"
                    viewModel.setYearMonth(selectedYear, selectedMonth)
                }
                R.id.chip_specific_day -> {
                    // Abrir picker inmediatamente; si cancela → restaurar chip_by_period
                    showDateRangePicker()
                }
            }
        }
    }

    private fun showDateRangePicker() {
        val now = System.currentTimeMillis()
        val startMs = now - 30L * 86_400_000
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Seleccionar rango de fechas")
            .setSelection(androidx.core.util.Pair(startMs, now))
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

            utcCal.timeInMillis = selection.first
            val from = Calendar.getInstance().apply {
                set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH),
                    utcCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            utcCal.timeInMillis = selection.second
            val to = Calendar.getInstance().apply {
                set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH),
                    utcCal.get(Calendar.DAY_OF_MONTH), 23, 59, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis

            viewModel.setCustomRange(from, to)
            layoutPeriodFilter.visibility = View.GONE

            val fmt = SimpleDateFormat("dd/MM", Locale.getDefault())
            chipCustomRange.text = "🗓 ${fmt.format(Date(from))}–${fmt.format(Date(to))}"
        }

        picker.addOnNegativeButtonClickListener { restoreChipByPeriod() }
        picker.addOnCancelListener             { restoreChipByPeriod() }

        picker.show(supportFragmentManager, "stats_range_picker")
    }

    private fun restoreChipByPeriod() {
        ignoreChipChange = true
        chipGroupTimeMode.check(R.id.chip_by_period)
        ignoreChipChange = false
        layoutPeriodFilter.visibility = View.VISIBLE
        viewModel.setYearMonth(selectedYear, selectedMonth)
    }

    private fun setupCharts() {
        val labelColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        val gridColor = if (isNightMode) Color.parseColor("#2A2A3A") else Color.parseColor("#EEEEEE")

        // Comparison bar chart
        barChartComparison.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setFitBars(true)
            setPinchZoom(false)
            setScaleEnabled(false)
            isDoubleTapToZoomEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textSize = 10f
                textColor = labelColor
            }
            axisLeft.apply {
                textColor = labelColor
                this.gridColor = gridColor
                valueFormatter = currencyAxisFormatter()
            }
            axisRight.isEnabled = false
            legend.apply { isEnabled = true; textSize = 11f; textColor = labelColor }
        }

        // Net line chart
        lineChartNet.apply {
            description.isEnabled = false
            setPinchZoom(false)
            setScaleEnabled(false)
            isDoubleTapToZoomEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textSize = 10f
                textColor = labelColor
            }
            axisLeft.apply {
                textColor = labelColor
                this.gridColor = gridColor
                valueFormatter = currencyAxisFormatter()
            }
            axisRight.isEnabled = false
            legend.isEnabled = false
        }

        // Stacked bar chart
        barChartStacked.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setPinchZoom(false)
            setScaleEnabled(false)
            isDoubleTapToZoomEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textSize = 9f
                textColor = labelColor
                labelRotationAngle = -30f
            }
            axisLeft.apply {
                textColor = labelColor
                this.gridColor = gridColor
                valueFormatter = currencyAxisFormatter()
            }
            axisRight.isEnabled = false
            legend.apply { isEnabled = true; textSize = 10f; textColor = labelColor; isWordWrapEnabled = true }
        }

        // Income/Expense dual line chart
        lineChartIncomeExpense.apply {
            description.isEnabled = false
            setPinchZoom(false)
            setScaleEnabled(false)
            isDoubleTapToZoomEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textSize = 10f
                textColor = labelColor
            }
            axisLeft.apply {
                textColor = labelColor
                this.gridColor = gridColor
                valueFormatter = currencyAxisFormatter()
            }
            axisRight.isEnabled = false
            legend.apply { isEnabled = true; textSize = 11f; textColor = labelColor }
        }
    }

    private fun currencyAxisFormatter() = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String =
            if (value >= 1000f) "${String.format("%.0f", value / 1000)}k €"
            else "${value.toInt()} €"
    }

    // ── Observadores ──────────────────────────────────────────────────────────

    private fun observeViewModel() {
        // Spinner de año: reconstruir cuando cambian los años disponibles
        lifecycleScope.launch {
            viewModel.availableYears.collect { years ->
                setupYearSpinner(years)
            }
        }

        // Render de estadísticas
        lifecycleScope.launch {
            viewModel.statsData.collect { data -> renderStats(data) }
        }
    }

    private fun setupYearSpinner(availableYears: List<Int>) {
        val yearStrings = mutableListOf("Todos los años")
        yearStrings.addAll(availableYears.map { it.toString() })
        val yearAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, yearStrings)
        yearAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerYear.onItemSelectedListener = null
        spinnerYear.adapter = yearAdapter

        // Restaurar selección si la había
        val currentYear = selectedYear
        if (currentYear != null) {
            val idx = availableYears.indexOf(currentYear)
            if (idx >= 0) spinnerYear.setSelection(idx + 1)
        }

        spinnerYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedYear = if (position == 0) null else availableYears[position - 1]
                selectedMonth = null
                updateMonthSpinner(availableYears)
                if (chipGroupTimeMode.checkedChipId == R.id.chip_by_period) {
                    viewModel.setYearMonth(selectedYear, selectedMonth)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateMonthSpinner(availableYears)
    }

    private fun updateMonthSpinner(@Suppress("UNUSED_PARAMETER") availableYears: List<Int>) {
        // filteredTransactions con modo YearMonth(null, null) equivale a todas las transacciones
        // filteredTransactions con modo YearMonth(year, null) equivale a transacciones del año
        val allTx = viewModel.filteredTransactions.value
        val sourceTx = if (selectedYear == null) {
            allTx
        } else {
            allTx.filter { tx ->
                Calendar.getInstance().apply { timeInMillis = tx.transaction.date }
                    .get(Calendar.YEAR) == selectedYear
            }
        }

        val availableMonths = sourceTx
            .map { tx ->
                Calendar.getInstance().apply {
                    timeInMillis = tx.transaction.date
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.time
            }
            .distinct()
            .sortedDescending()

        val monthStrings = mutableListOf("Todos los meses")
        monthStrings.addAll(availableMonths.map { date ->
            if (selectedYear == null)
                monthYearFormat.format(date).replaceFirstChar { it.uppercase() }
            else
                monthOnlyFormat.format(date).replaceFirstChar { it.uppercase() }
        })

        val monthAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, monthStrings)
        monthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerMonth.onItemSelectedListener = null
        spinnerMonth.adapter = monthAdapter
        spinnerMonth.setSelection(0)

        spinnerMonth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedMonth = if (position == 0) null else availableMonths[position - 1]
                if (chipGroupTimeMode.checkedChipId == R.id.chip_by_period) {
                    viewModel.setYearMonth(selectedYear, selectedMonth)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── Renderizado ───────────────────────────────────────────────────────────

    private fun renderStats(data: StatsData) {
        renderSummary(data)
        renderComparisonChart(data)
        renderNetLineChart(data)
        renderCategoryBreakdown(data)
        renderStackedBarChart(data)
        renderTopMerchants(data)
        renderIncomeExpenseChart(data)
    }

    private fun renderSummary(data: StatsData) {
        tvTotalIncome.text = currencyFormat.format(data.income)
        tvTotalExpenses.text = currencyFormat.format(data.expenses)
        tvBalance.text = currencyFormat.format(data.balance)
        tvBalance.setTextColor(
            if (data.balance >= 0) ContextCompat.getColor(this, R.color.balance_amount)
            else ContextCompat.getColor(this, R.color.expense_amount)
        )
        tvTransactionCount.text = "${data.transactionCount} transacciones"
    }

    private fun renderComparisonChart(data: StatsData) {
        val labelColor = if (isNightMode) Color.WHITE else Color.DKGRAY

        // Un único DataSet con dos entradas de color diferente (evita problemas con barras agrupadas)
        val entries = listOf(
            BarEntry(0f, data.income.toFloat()),
            BarEntry(1f, data.expenses.toFloat())
        )
        val colors = listOf(
            Color.parseColor("#4CAF50"),
            Color.parseColor("#EF5350")
        )

        val dataSet = BarDataSet(entries, "").apply {
            this.colors = colors
            setDrawValues(true)
            valueTextColor = labelColor
            valueTextSize = 10f
            valueFormatter = currencyAxisFormatter()
        }

        // Granularidad dinámica para que los ticks del eje Y sean siempre múltiplos redondos
        // y el formatter no repita la misma etiqueta (e.g. "3k €" × 3).
        val maxVal = maxOf(data.income, data.expenses).toFloat().coerceAtLeast(1f)
        val yGranularity = when {
            maxVal > 10_000 -> 2_000f
            maxVal > 5_000  -> 1_000f
            maxVal > 1_000  -> 500f
            maxVal > 500    -> 100f
            else            -> 50f
        }

        barChartComparison.apply {
            this.data = BarData(dataSet).apply { barWidth = 0.65f }
            xAxis.valueFormatter = IndexAxisValueFormatter(listOf("Ingresos", "Gastos"))
            xAxis.textColor = labelColor
            axisLeft.apply {
                axisMinimum = 0f
                granularity = yGranularity
                isGranularityEnabled = true
                setLabelCount(6, false)
                textColor = labelColor
                valueFormatter = currencyAxisFormatter()
            }
            legend.isEnabled = false
            setFitBars(true)
            invalidate()
        }
    }

    private fun renderNetLineChart(data: StatsData) {
        if (data.netEvolution.isEmpty()) {
            lineChartNet.visibility = View.GONE
            tvNetChartEmpty.visibility = View.VISIBLE
            return
        }
        lineChartNet.visibility = View.VISIBLE
        tvNetChartEmpty.visibility = View.GONE

        val labelColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        val labels = data.netEvolution.map { it.label }
        val entries = data.netEvolution.mapIndexed { i, point ->
            Entry(i.toFloat(), point.net.toFloat())
        }

        val hasPositive = entries.any { it.y >= 0 }
        val lineColor = if (hasPositive) Color.parseColor("#4CAF50") else Color.parseColor("#EF5350")

        val dataSet = LineDataSet(entries, "Neto").apply {
            color = lineColor
            setCircleColor(lineColor)
            circleRadius = 3f
            lineWidth = 2f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = lineColor
            fillAlpha = 50
        }

        lineChartNet.apply {
            this.data = LineData(dataSet)
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.labelCount = minOf(labels.size, 6)
            xAxis.textColor = labelColor
            axisLeft.textColor = labelColor
            legend.isEnabled = false
            invalidate()
        }
    }

    private fun renderCategoryBreakdown(data: StatsData) {
        layoutCategories.removeAllViews()

        if (data.categoryBreakdown.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No hay datos de gastos para mostrar"
                setPadding(0, 32, 0, 32)
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_hint))
            }
            layoutCategories.addView(tv)
            return
        }

        val grandTotal = data.categoryBreakdown.sumOf { it.totalAmount }

        data.categoryBreakdown.forEach { parent ->
            addParentCategoryRow(parent, grandTotal)
            parent.childrenBreakdown.forEach { child ->
                addChildCategoryRow(child, parent.totalAmount)
            }
            // Fila "directo" si hay txs directas y también hijos
            if (parent.directAmount > 0 && parent.childrenBreakdown.isNotEmpty()) {
                addDirectRow(parent)
            }
        }
    }

    private fun addParentCategoryRow(parent: ParentCategoryBreakdown, grandTotal: Double) {
        val percentage = if (grandTotal > 0) (parent.totalAmount / grandTotal * 100) else 0.0

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 4)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Indicador de color
        val parentColor = normalizeColor(parent.parentName, parent.parentColor)
        val colorDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(16.dp, 16.dp).apply { marginEnd = 8.dp }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(parentColor)
            }
        }
        header.addView(colorDot)

        // Nombre de categoría
        val tvName = TextView(this).apply {
            text = parent.parentName
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvName)

        // Porcentaje
        val tvPct = TextView(this).apply {
            text = String.format("%.1f%%", percentage)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp }
        }
        header.addView(tvPct)

        // Importe
        val tvAmount = TextView(this).apply {
            text = currencyFormat.format(parent.totalAmount)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.expense_amount))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp }
        }
        header.addView(tvAmount)

        row.addView(header)

        // Barra de progreso
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 8.dp
            ).apply { topMargin = 4.dp }
            max = 100
            this.progress = percentage.toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(parentColor)
        }
        row.addView(progress)

        layoutCategories.addView(row)
    }

    private fun addChildCategoryRow(child: ChildCategoryBreakdown, @Suppress("UNUSED_PARAMETER") parentTotal: Double) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(24.dp, 4, 0, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val colorDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(10.dp, 10.dp).apply { marginEnd = 8.dp }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(normalizeColor(child.name, child.color))
            }
        }
        row.addView(colorDot)

        val tvName = TextView(this).apply {
            text = child.name
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tvName)

        val tvPct = TextView(this).apply {
            text = String.format("%.1f%%", child.percentage * 100)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_hint))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 4.dp }
        }
        row.addView(tvPct)

        val tvAmount = TextView(this).apply {
            text = currencyFormat.format(child.amount)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp }
        }
        row.addView(tvAmount)

        layoutCategories.addView(row)
    }

    private fun addDirectRow(parent: ParentCategoryBreakdown) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(24.dp, 2, 0, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvName = TextView(this).apply {
            text = "↳ ${parent.parentName} (directo)"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_hint))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tvName)

        val tvAmount = TextView(this).apply {
            text = currencyFormat.format(parent.directAmount)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_hint))
        }
        row.addView(tvAmount)

        layoutCategories.addView(row)
    }

    private fun renderStackedBarChart(data: StatsData) {
        if (data.stackedBars.isEmpty()) {
            barChartStacked.visibility = View.GONE
            tvStackedEmpty.visibility = View.VISIBLE
            return
        }
        barChartStacked.visibility = View.VISIBLE
        tvStackedEmpty.visibility = View.GONE

        val labelColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        val labels = data.stackedBars.map { it.label }

        // Total acumulado por categoría en todos los períodos
        val catNames = data.stackedBars.flatMap { it.categoryAmounts.keys }.distinct()
        val totals: Map<String, Double> = catNames.associateWith { name ->
            data.stackedBars.sumOf { bar -> bar.categoryAmounts[name] ?: 0.0 }
        }

        // Filtrar epsilon, ordenar por total desc, tomar top-8
        val topCatNames = totals.entries
            .filter { (_, total) -> total > 0.0001 }
            .sortedByDescending { (_, total) -> total }
            .take(8)
            .map { (name, _) -> name }

        if (topCatNames.isEmpty()) {
            barChartStacked.visibility = View.GONE
            tvStackedEmpty.visibility = View.VISIBLE
            return
        }

        val entries = data.stackedBars.mapIndexed { i, entry ->
            val vals = topCatNames.map { (entry.categoryAmounts[it] ?: 0.0).toFloat() }.toFloatArray()
            BarEntry(i.toFloat(), vals)
        }

        // Colores desde allCategoryColors (incluye categorías con 0 gastos en este período)
        val stackColors = topCatNames.map { name ->
            normalizeColor(name, data.allCategoryColors[name])
        }

        val dataSet = BarDataSet(entries, "").apply {
            colors = stackColors.toMutableList()
            stackLabels = topCatNames.toTypedArray()
            setDrawValues(false)
        }

        barChartStacked.apply {
            this.data = BarData(dataSet)
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.textColor = labelColor
            axisLeft.textColor = labelColor
            legend.textColor = labelColor
            invalidate()
        }
    }

    private fun renderTopMerchants(data: StatsData) {
        layoutTopMerchants.removeAllViews()

        if (data.topMerchants.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Sin datos"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_hint))
                setPadding(0, 16, 0, 16)
            }
            layoutTopMerchants.addView(tv)
            return
        }

        val total = data.topMerchants.size
        data.topMerchants.forEachIndexed { index, merchant ->
            addMerchantRow(index + 1, merchant, total)
        }
    }

    private fun addMerchantRow(rank: Int, merchant: MerchantStat, total: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvRank = TextView(this).apply {
            text = "#$rank"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_hint))
            layoutParams = LinearLayout.LayoutParams(32.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(tvRank)

        val tvConcept = TextView(this).apply {
            text = merchant.concept.ifBlank { "—" }
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tvConcept)

        val tvCount = TextView(this).apply {
            text = "${merchant.count}x"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp }
        }
        row.addView(tvCount)

        val tvAmount = TextView(this).apply {
            text = currencyFormat.format(merchant.amount)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@StatisticsActivity, R.color.expense_amount))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp }
        }
        row.addView(tvAmount)

        layoutTopMerchants.addView(row)

        // Separador
        if (rank < total) {
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(ContextCompat.getColor(this@StatisticsActivity, R.color.divider))
            }
            layoutTopMerchants.addView(divider)
        }
    }

    private fun renderIncomeExpenseChart(data: StatsData) {
        if (data.incomeExpenseEvolution.isEmpty()) {
            lineChartIncomeExpense.visibility = View.GONE
            tvIncomeExpenseEmpty.visibility = View.VISIBLE
            return
        }
        lineChartIncomeExpense.visibility = View.VISIBLE
        tvIncomeExpenseEmpty.visibility = View.GONE

        val labelColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        val points = data.incomeExpenseEvolution
        val labels = points.map { it.label }

        val incomeEntries = points.mapIndexed { i, p -> Entry(i.toFloat(), p.income.toFloat()) }
        val expenseEntries = points.mapIndexed { i, p -> Entry(i.toFloat(), p.expenses.toFloat()) }

        val incomeSet = LineDataSet(incomeEntries, "Ingresos").apply {
            color = Color.parseColor("#4CAF50")
            setCircleColor(Color.parseColor("#4CAF50"))
            circleRadius = 3f
            lineWidth = 2f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#4CAF50")
            fillAlpha = 30
        }

        val expenseSet = LineDataSet(expenseEntries, "Gastos").apply {
            color = Color.parseColor("#EF5350")
            setCircleColor(Color.parseColor("#EF5350"))
            circleRadius = 3f
            lineWidth = 2f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#EF5350")
            fillAlpha = 30
        }

        lineChartIncomeExpense.apply {
            this.data = LineData(incomeSet, expenseSet)
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.labelCount = minOf(labels.size, 6)
            xAxis.textColor = labelColor
            axisLeft.textColor = labelColor
            legend.textColor = labelColor
            invalidate()
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
