package com.jesusluna.bolsilloplus

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.ui.categories.CategoriesActivity
import com.jesusluna.bolsilloplus.ui.onboarding.OnboardingActivity
import com.jesusluna.bolsilloplus.ui.categories.RuleMatchInfo
import com.jesusluna.bolsilloplus.ui.main.CategoryPickerBottomSheet
import com.jesusluna.bolsilloplus.ui.main.ImportExceptionsBottomSheet
import com.jesusluna.bolsilloplus.ui.main.MainHeaderAdapter
import com.jesusluna.bolsilloplus.util.showScopePickerDialog
import com.jesusluna.bolsilloplus.ui.main.BackupExportResult
import com.jesusluna.bolsilloplus.ui.main.BackupImportResult
import com.jesusluna.bolsilloplus.ui.main.DashboardData
import com.jesusluna.bolsilloplus.ui.main.DashboardPeriod
import com.jesusluna.bolsilloplus.ui.main.DateRange
import com.jesusluna.bolsilloplus.ui.main.ExportResult
import com.jesusluna.bolsilloplus.ui.main.FILTER_UNCATEGORIZED
import com.jesusluna.bolsilloplus.ui.main.ImportResult
import com.jesusluna.bolsilloplus.ui.main.MainViewModel
import com.jesusluna.bolsilloplus.ui.main.PieChartMode
import androidx.cardview.widget.CardView
import com.jesusluna.bolsilloplus.ui.main.CategoryExpense
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TransactionAdapter
    private lateinit var tvIncome: TextView
    private lateinit var tvExpenses: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvEmptyTransactions: TextView
    private lateinit var chipGroupPeriod: ChipGroup
    private lateinit var etSearch: TextInputEditText
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var fabImport: FloatingActionButton
    private lateinit var pieChart: PieChart
    private lateinit var tvPieEmpty: TextView
    private lateinit var chipCustom: Chip
    private lateinit var tvActiveDateRange: TextView

    // Pie mode views
    private var pieShowPercent = true          // true=%, false=€
    private var lastDashboardData: DashboardData? = null
    private lateinit var btnPieFormatToggle: Button
    private lateinit var btnManageStats: Button
    private lateinit var chipGroupPieMode: ChipGroup
    private lateinit var scrollPieParents: android.widget.HorizontalScrollView
    private lateinit var chipGroupPieParents: ChipGroup

    // Pie tooltip views
    private lateinit var cardPieTooltip: CardView
    private lateinit var viewTooltipColor: View
    private lateinit var tvTooltipName: TextView
    private lateinit var tvTooltipNet: TextView
    private lateinit var tvTooltipPercent: TextView
    private lateinit var tvTooltipCount: TextView

    private var previousPeriod = DashboardPeriod.MONTH
    /** Evita que los cambios programáticos de chip relancen el diálogo de fechas. */
    private var ignoreChipChange = false

    private lateinit var headerView: View

    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("es", "ES"))

    private val isNightMode: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    private val pieColors = listOf(
        "#4E79A7", "#F28E2B", "#E15759", "#76B7B2",
        "#59A14F", "#EDC948", "#B07AA1", "#FF9DA7",
        "#9C755F", "#AFBF9B", "#D37295", "#00BCD4"
    )

    private val excelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val mimeType = contentResolver.getType(uri)
        val fileName = uri.lastPathSegment ?: ""
        if (mimeType == "application/json" || fileName.endsWith(".json", ignoreCase = true)) {
            // JSON de backup → importar solo movimientos
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return@registerForActivityResult
            viewModel.importTransactionsFromBackupJson(json)
        } else {
            // Excel → flujo normal de importación de movimientos
            viewModel.importFromExcel(uri)
        }
    }

    private val backupImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        // OpenableColumns.DISPLAY_NAME es la forma fiable de obtener el nombre real del archivo
        // desde un content URI. uri.lastPathSegment devuelve el document ID codificado,
        // no el nombre visible.
        val displayName = contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: ""
        val mimeType = contentResolver.getType(uri) ?: ""
        val isExcel = mimeType.contains("spreadsheet") || mimeType.contains("excel") ||
            displayName.endsWith(".xlsx", ignoreCase = true) ||
            displayName.endsWith(".xls", ignoreCase = true)
        if (isExcel) {
            viewModel.importFromExcel(uri)
        } else {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return@registerForActivityResult
            viewModel.importBackup(json)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Mostrar onboarding solo la primera vez
        if (!OnboardingActivity.isCompleted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        initViews()

        // ← Clave: registrar el MaterialToolbar como ActionBar de la Activity
        setSupportActionBar(toolbar)

        setupRecyclerView()
        setupPieChart()
        setupPieFormatToggle()
        setupChipGroupPeriod()
        setupPieModeChips()
        setupManageStats()
        setupSearch()
        fabImport.setOnClickListener { excelPickerLauncher.launch("*/*") }
        observeViewModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_categories -> {
            startActivity(Intent(this, CategoriesActivity::class.java)); true
        }
        R.id.action_statistics -> {
            startActivity(Intent(this, StatisticsActivity::class.java)); true
        }
        R.id.action_export -> { showBackupExportDialog(); true }
        R.id.action_import_backup -> {
            // "*/*" para que el selector muestre tanto JSON como Excel.
            // Muchos gestores de archivos en Android no etiquetan los .json como
            // "application/json" (usan "text/plain" u "octet-stream"), así que
            // filtrar por MIME aquí oculta los ficheros. La detección real del tipo
            // ya se hace en el callback (displayName + mimeType).
            backupImportLauncher.launch(arrayOf("*/*"))
            true
        }
        R.id.action_exceptions -> {
            ImportExceptionsBottomSheet().show(supportFragmentManager, "import_exceptions"); true
        }
        R.id.action_tutorial -> {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                putExtra("force_show", true)
            })
            true
        }
        R.id.action_delete_all -> { showDeleteAllConfirmation1(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── Inicialización ────────────────────────────────────────────────────────

    private fun initViews() {
        // Vistas en activity_main.xml
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.recyclerViewTransactions)
        fabImport = findViewById(R.id.fab_import)

        // El LayoutManager debe asignarse ANTES de inflar el header con el RecyclerView
        // como parent, porque RecyclerView.generateLayoutParams() lo requiere.
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Inflar el encabezado del dashboard (se añade como primer item del ConcatAdapter)
        headerView = layoutInflater.inflate(R.layout.header_dashboard, recyclerView, false)

        // Vistas dentro del encabezado
        tvIncome = headerView.findViewById(R.id.tv_income)
        tvExpenses = headerView.findViewById(R.id.tv_expenses)
        tvBalance = headerView.findViewById(R.id.tv_balance)
        tvEmptyTransactions = headerView.findViewById(R.id.tv_empty_transactions)
        chipGroupPeriod = headerView.findViewById(R.id.chip_group_period)
        etSearch = headerView.findViewById(R.id.et_search)
        chipGroupFilter = headerView.findViewById(R.id.chip_group_filter)
        pieChart = headerView.findViewById(R.id.pie_chart)
        tvPieEmpty = headerView.findViewById(R.id.tv_pie_empty)
        chipCustom = headerView.findViewById(R.id.chip_custom)
        tvActiveDateRange = headerView.findViewById(R.id.tv_active_date_range)
        btnPieFormatToggle = headerView.findViewById(R.id.btn_pie_format_toggle)
        btnManageStats = headerView.findViewById(R.id.btn_manage_stats)
        chipGroupPieMode = headerView.findViewById(R.id.chip_group_pie_mode)
        scrollPieParents = headerView.findViewById(R.id.scroll_pie_parents)
        chipGroupPieParents = headerView.findViewById(R.id.chip_group_pie_parents)
        cardPieTooltip = headerView.findViewById(R.id.card_pie_tooltip)
        viewTooltipColor = headerView.findViewById(R.id.view_tooltip_color)
        tvTooltipName = headerView.findViewById(R.id.tv_tooltip_name)
        tvTooltipNet = headerView.findViewById(R.id.tv_tooltip_net)
        tvTooltipPercent = headerView.findViewById(R.id.tv_tooltip_percent)
        tvTooltipCount = headerView.findViewById(R.id.tv_tooltip_count)
    }

    private fun setupRecyclerView() {
        adapter = TransactionAdapter(
            onItemClick = { tx ->
                if (tx.transaction.candidateRuleCount > 1) showConflictDialog(tx)
                else showCategoryPicker(tx)
            },
            onItemLongClick = { tx -> showDeleteDialog(tx) }
        )
        // LayoutManager ya asignado en initViews() — ver comentario allí.
        recyclerView.adapter = ConcatAdapter(MainHeaderAdapter(headerView), adapter)
    }

    private fun setupChipGroupPeriod() {
        chipGroupPeriod.setOnCheckedStateChangeListener { _, checkedIds ->
            if (ignoreChipChange) return@setOnCheckedStateChangeListener
            val prev = previousPeriod
            when (checkedIds.firstOrNull()) {
                R.id.chip_week   -> { previousPeriod = DashboardPeriod.WEEK;  viewModel.selectPeriod(DashboardPeriod.WEEK) }
                R.id.chip_month  -> { previousPeriod = DashboardPeriod.MONTH; viewModel.selectPeriod(DashboardPeriod.MONTH) }
                R.id.chip_year   -> { previousPeriod = DashboardPeriod.YEAR;  viewModel.selectPeriod(DashboardPeriod.YEAR) }
                R.id.chip_all    -> { previousPeriod = DashboardPeriod.ALL;   viewModel.selectPeriod(DashboardPeriod.ALL) }
                R.id.chip_custom -> showDateRangePicker(prev)
            }
        }
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ── Chips de filtro por categoría ─────────────────────────────────────────

    private fun refreshCategoryChips(categories: List<CategoryEntity>) {
        val currentSel = viewModel.selectedCategoryId.value

        // Evitar el listener mientras reconstruimos los chips
        chipGroupFilter.setOnCheckedStateChangeListener(null)
        chipGroupFilter.removeAllViews()

        addFilterChip("Todas", null, currentSel == null)
        addFilterChip("Sin categoría", FILTER_UNCATEGORIZED, currentSel == FILTER_UNCATEGORIZED)
        categories.forEach { cat ->
            addFilterChip(cat.name, cat.id, currentSel == cat.id)
        }

        // Reactivar el listener después de añadir los chips
        chipGroupFilter.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checkedIds.first())
            @Suppress("UNCHECKED_CAST")
            val catId = chip?.tag as? Long   // null → "Todas"
            viewModel.setSelectedCategoryId(catId)
        }
    }

    private fun addFilterChip(label: String, categoryId: Long?, isChecked: Boolean) {
        val chip = Chip(this).apply {
            text = label
            isCheckable = true
            this.isChecked = isChecked
            tag = categoryId
            id = View.generateViewId()
        }
        chipGroupFilter.addView(chip)
    }

    // ── Chips de modo de pie chart ────────────────────────────────────────────

    private fun setupPieModeChips() {
        chipGroupPieMode.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chip_pie_parents -> {
                    scrollPieParents.visibility = View.GONE
                    viewModel.setPieMode(PieChartMode.Parents)
                }
                R.id.chip_pie_children -> {
                    scrollPieParents.visibility = View.GONE
                    viewModel.setPieMode(PieChartMode.Children)
                }
                R.id.chip_pie_breakdown -> {
                    // Comprobar que existen categorías padre antes de activar el modo
                    if (chipGroupPieParents.childCount == 0) {
                        Toast.makeText(this, "Crea primero categorías con subcategorías para usar este modo", Toast.LENGTH_SHORT).show()
                        chipGroupPieMode.check(R.id.chip_pie_parents)
                        return@setOnCheckedStateChangeListener
                    }
                    scrollPieParents.visibility = View.VISIBLE
                    if (chipGroupPieParents.checkedChipId == View.NO_ID) {
                        // Seleccionar automáticamente el primer padre disponible
                        (chipGroupPieParents.getChildAt(0) as? Chip)?.isChecked = true
                    } else {
                        // Reaplicar el modo actual si ya había selección
                        val selectedChip = chipGroupPieParents.findViewById<Chip>(chipGroupPieParents.checkedChipId)
                        val parentId = selectedChip?.tag as? Long
                        if (parentId != null) {
                            viewModel.setPieMode(PieChartMode.BreakdownParent(parentId, selectedChip.text.toString()))
                        }
                    }
                }
            }
        }

        chipGroupPieParents.setOnCheckedStateChangeListener { _, checkedIds ->
            val chipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val selectedChip = chipGroupPieParents.findViewById<Chip>(chipId) ?: return@setOnCheckedStateChangeListener
            val parentId = selectedChip.tag as? Long ?: return@setOnCheckedStateChangeListener
            val parentName = selectedChip.text.toString()
            viewModel.setPieMode(PieChartMode.BreakdownParent(parentId, parentName))
        }
    }

    private fun refreshPieParentChips(categories: List<CategoryEntity>) {
        chipGroupPieParents.removeAllViews()
        categories.filter { it.parentId == null }.forEach { cat ->
            val chip = Chip(this).apply {
                text = cat.name
                isCheckable = true
                tag = cat.id
                id = View.generateViewId()
            }
            chipGroupPieParents.addView(chip)
        }
        // Si estamos en modo breakdown, restaurar la selección
        val mode = viewModel.pieMode.value
        if (mode is PieChartMode.BreakdownParent) {
            chipGroupPieParents.children
                .filterIsInstance<Chip>()
                .firstOrNull { it.tag == mode.parentId }
                ?.isChecked = true
        }
    }

    private fun setupManageStats() {
        btnManageStats.setOnClickListener {
            startActivity(Intent(this, StatisticsActivity::class.java))
        }
    }

    private fun setupPieFormatToggle() {
        btnPieFormatToggle.setOnClickListener {
            pieShowPercent = !pieShowPercent
            btnPieFormatToggle.text = if (pieShowPercent) "%" else "€"
            lastDashboardData?.let { updatePieChart(it) }
        }
    }

    // ── Configuración de gráficos ─────────────────────────────────────────────

    private fun setupPieChart() {
        val labelColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        val holeColor  = if (isNightMode) Color.parseColor("#1E1E2E") else Color.WHITE
        pieChart.apply {
            description.isEnabled = false
            isRotationEnabled = true
            isHighlightPerTapEnabled = true
            setDrawEntryLabels(false)
            holeRadius = 52f
            transparentCircleRadius = 56f
            setHoleColor(holeColor)
            legend.apply {
                isEnabled = true
                isWordWrapEnabled = true
                textSize = 11f
                formSize = 10f
                xEntrySpace = 8f
                textColor = labelColor
            }
        }
    }

    // ── Observadores ─────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.dashboardData.collect { data -> renderDashboard(data) }
        }

        lifecycleScope.launch {
            combine(viewModel.activeDateRange, viewModel.selectedPeriod) { range, period -> range to period }
                .collect { (range, period) -> updateDateRangeUI(range, period) }
        }

        // Reconstruir chips cuando cambian las categorías
        lifecycleScope.launch {
            viewModel.categories.collect { categories ->
                refreshCategoryChips(categories)
                refreshPieParentChips(categories)
            }
        }

        lifecycleScope.launch {
            viewModel.importResult.collect { result ->
                when (result) {
                    is ImportResult.Success -> showImportSuccessDialog(result)
                    is ImportResult.Error   -> Toast.makeText(
                        this@MainActivity, "Error: ${result.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.exportResult.collect { result ->
                when (result) {
                    is ExportResult.Success -> shareExcelFile(result.file)
                    is ExportResult.Empty   -> Toast.makeText(
                        this@MainActivity, "No hay movimientos para exportar", Toast.LENGTH_SHORT
                    ).show()
                    is ExportResult.Error   -> Toast.makeText(
                        this@MainActivity, "Error al exportar: ${result.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.backupExportResult.collect { result ->
                when (result) {
                    is BackupExportResult.Success -> shareBackupFile(result.file)
                    is BackupExportResult.Error   -> Toast.makeText(
                        this@MainActivity, "Error al exportar: ${result.msg}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.backupImportResult.collect { result ->
                when (result) {
                    is BackupImportResult.Success -> Toast.makeText(
                        this@MainActivity, "Restaurado: ${result.summary}", Toast.LENGTH_LONG
                    ).show()
                    is BackupImportResult.ValidationFailed -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("Error de validación")
                        .setMessage(result.reason)
                        .setPositiveButton("OK", null)
                        .show()
                    is BackupImportResult.Error -> Toast.makeText(
                        this@MainActivity, "Error: ${result.msg}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ── Renderizado del dashboard ─────────────────────────────────────────────

    private fun renderDashboard(data: DashboardData) {
        lastDashboardData = data
        tvIncome.text = currencyFormat.format(data.income)
        tvExpenses.text = currencyFormat.format(data.expenses)
        tvBalance.text = currencyFormat.format(data.balance)
        tvBalance.setTextColor(
            if (data.balance >= 0) ContextCompat.getColor(this, R.color.balance_amount)
            else ContextCompat.getColor(this, R.color.expense_amount)
        )

        updatePieChart(data)

        adapter.submitList(data.displayTransactions)
        val isEmpty = data.displayTransactions.isEmpty()
        tvEmptyTransactions.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun updatePieChart(data: DashboardData) {
        hidePieTooltip()
        if (data.categoryExpenses.isEmpty()) {
            pieChart.visibility = View.GONE
            tvPieEmpty.visibility = View.VISIBLE
            return
        }
        pieChart.visibility = View.VISIBLE
        tvPieEmpty.visibility = View.GONE

        val entries = data.categoryExpenses.map { cat ->
            PieEntry(cat.absNet.toFloat(), cat.name).also { it.data = cat }
        }
        val colors = data.categoryExpenses.mapIndexed { i, cat ->
            try { Color.parseColor(cat.color) }
            catch (e: Exception) { Color.parseColor(pieColors[i % pieColors.size]) }
        }

        pieChart.setUsePercentValues(pieShowPercent)

        val valueColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        val formatter: ValueFormatter = if (pieShowPercent) {
            PercentFormatter(pieChart)
        } else {
            object : ValueFormatter() {
                override fun getPieLabel(value: Float, pieEntry: PieEntry?): String {
                    val cat = pieEntry?.data as? CategoryExpense ?: return ""
                    return currencyFormat.format(cat.absNet)
                }
            }
        }
        val dataSet = PieDataSet(entries, "").apply {
            this.colors = colors
            valueTextSize = 11f
            valueTextColor = valueColor
            valueFormatter = formatter
            sliceSpace = 2f
        }
        pieChart.data = PieData(dataSet)
        pieChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                val cat = (e as? PieEntry)?.data as? CategoryExpense ?: return
                showPieTooltip(cat, data.categoryExpenses)
            }
            override fun onNothingSelected() { hidePieTooltip() }
        })
        pieChart.invalidate()
    }

    private fun showPieTooltip(cat: CategoryExpense, allCats: List<CategoryExpense>) {
        val totalAbsNet = allCats.sumOf { it.absNet }
        val percent = if (totalAbsNet > 0) cat.absNet / totalAbsNet * 100.0 else 0.0
        cardPieTooltip.visibility = View.VISIBLE
        try { viewTooltipColor.setBackgroundColor(Color.parseColor(cat.color)) }
        catch (e: Exception) { viewTooltipColor.setBackgroundColor(Color.GRAY) }
        tvTooltipName.text = cat.name
        tvTooltipNet.text = "Neto: ${currencyFormat.format(cat.netAmount)}"
        tvTooltipPercent.text = String.format(Locale("es", "ES"), "%.1f%%", percent)
        tvTooltipCount.text = "${cat.count} movimiento${if (cat.count != 1) "s" else ""}"
    }

    private fun hidePieTooltip() {
        cardPieTooltip.visibility = View.GONE
    }

    // ── Diálogos ─────────────────────────────────────────────────────────────

    private fun showConflictDialog(tx: TransactionWithCategory) {
        lifecycleScope.launch {
            val matchInfos = viewModel.getMatchingRulesForConcept(tx.transaction.normalizedConcept)
            val msg = buildString {
                append("Concepto: '${tx.transaction.normalizedConcept}'\n\n")
                if (matchInfos.isEmpty()) {
                    append("No hay reglas activas que coincidan actualmente.")
                } else {
                    append("Reglas en conflicto (orden de precedencia):\n\n")
                    matchInfos.forEachIndexed { index, info ->
                        val bullet = if (index == 0) "▶ " else "   "
                        append("${bullet}[${info.rule.matchType.name}] '${info.rule.normalizedPattern}'")
                        append("\n   → ${info.categoryName}  |  P:${info.rule.priority}")
                        if (index == 0) append("  ← ganadora")
                        append("\n\n")
                    }
                }
            }
            val builder = AlertDialog.Builder(this@MainActivity)
                .setTitle("⚠ Conflicto (${matchInfos.size} reglas)")
                .setMessage(msg)
                .setPositiveButton("Categorizar manualmente") { _, _ -> showCategoryPicker(tx) }
                .setNegativeButton("Cerrar", null)
            if (matchInfos.size > 1) {
                builder.setNeutralButton("Cambiar regla ganadora") { _, _ ->
                    showPickWinnerDialogInMain(tx, matchInfos)
                }
            }
            builder.show()
        }
    }

    private fun showPickWinnerDialogInMain(tx: TransactionWithCategory, matchInfos: List<RuleMatchInfo>) {
        val names = matchInfos.map { info ->
            "[${info.rule.matchType.name}] '${info.rule.normalizedPattern}' → ${info.categoryName}  (P:${info.rule.priority})"
        }.toTypedArray()
        var selectedIndex = 0
        AlertDialog.Builder(this)
            .setTitle("¿Qué categoría debe ganar?\n'${tx.transaction.normalizedConcept}'")
            .setSingleChoiceItems(names, 0) { _, which -> selectedIndex = which }
            .setPositiveButton("Siguiente: elegir alcance") { _, _ ->
                val chosen = matchInfos[selectedIndex]
                showScopeDialogInMain(tx, chosen.rule.categoryId, chosen.categoryName)
            }
            .setNeutralButton("Crear regla nueva") { _, _ -> showCategoryPicker(tx) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showScopeDialogInMain(tx: TransactionWithCategory, categoryId: Long, categoryName: String) {
        val normalized = tx.transaction.normalizedConcept
        showScopePickerDialog(
            context = this,
            inflater = layoutInflater,
            normalizedConcept = normalized,
            categoryName = categoryName,
            coroutineScope = lifecycleScope,
            countExact = { viewModel.countByExactConcept(normalized) },
            countPhrase = { pattern -> viewModel.countContainingPhrase(pattern) },
            countWord = { word -> viewModel.countContainingWord(word) },
            onScope = { scope -> viewModel.categorizeWithScope(tx.transaction.id, normalized, categoryId, scope) },
            onApplyOnly = { viewModel.categorizeScopeOnly(tx.transaction.id, categoryId) }
        )
    }

    private fun showCategoryPicker(tx: TransactionWithCategory) {
        CategoryPickerBottomSheet.newInstance(tx)
            .show(supportFragmentManager, "category_picker")
    }

    private fun showDeleteDialog(tx: TransactionWithCategory) {
        lifecycleScope.launch {
            val count = viewModel.countByExactConcept(tx.transaction.normalizedConcept)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Eliminar movimiento")
                .setMessage("'${tx.transaction.concept}'\n${currencyFormat.format(tx.transaction.amount)}")
                .setPositiveButton("Solo este") { _, _ ->
                    viewModel.deleteTransaction(tx.transaction.id)
                    Toast.makeText(this@MainActivity, "Movimiento eliminado", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Todos con este título ($count)") { _, _ ->
                    confirmDeleteByConcept(tx.transaction.concept, tx.transaction.normalizedConcept, count)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun confirmDeleteByConcept(concept: String, normalizedConcept: String, count: Int) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar $count movimientos")
            .setMessage("Se eliminarán TODOS los movimientos con título:\n\n'$concept'\n\nEsta acción no se puede deshacer.")
            .setPositiveButton("Eliminar todos") { _, _ ->
                viewModel.deleteTransactionsByConcept(normalizedConcept)
                Toast.makeText(this, "$count movimientos eliminados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showImportSuccessDialog(result: ImportResult.Success) {
        val msg = buildString {
            append("Nuevos movimientos: ${result.added}\n")
            if (result.duplicates > 0) append("Duplicados ignorados: ${result.duplicates}\n")
            if (result.skipped > 0) append("Excluidos por excepción: ${result.skipped}")
        }
        AlertDialog.Builder(this)
            .setTitle("Importación completada")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Ir a Categorías") { _, _ ->
                startActivity(Intent(this, CategoriesActivity::class.java))
            }.show()
    }

    private fun showDeleteAllConfirmation1() {
        AlertDialog.Builder(this)
            .setTitle("Eliminar historial")
            .setMessage("¿Estás seguro de que quieres eliminar TODOS los movimientos?\n\nEsta acción no se puede deshacer.")
            .setPositiveButton("Continuar") { _, _ -> showDeleteAllConfirmation2() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showDeleteAllConfirmation2() {
        AlertDialog.Builder(this)
            .setTitle("Confirmación final")
            .setMessage("Se eliminarán TODOS los movimientos de forma permanente.\n\n¿Confirmas que deseas borrar el historial completo?")
            .setPositiveButton("Eliminar todo") { _, _ ->
                viewModel.deleteAllTransactions()
                Toast.makeText(this, "Historial eliminado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showBackupExportDialog() {
        val labels = arrayOf(
            "Categorías y reglas",
            "Movimientos",
            "Excepciones de importación"
        )
        val checked = booleanArrayOf(true, true, true)

        AlertDialog.Builder(this)
            .setTitle("Exportar datos")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Exportar") { _, _ ->
                if (!checked.any { it }) {
                    Toast.makeText(this, "Selecciona al menos una opción", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.exportBackup(
                    includeCategories    = checked[0],
                    includeTransactions  = checked[1],
                    includeExceptions    = checked[2]
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun shareBackupFile(file: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Exportar backup"))
    }

    private fun shareExcelFile(file: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Exportar Excel"))
    }

    // ── Rango de fechas personalizado ─────────────────────────────────────────

    private fun updateDateRangeUI(range: DateRange, period: DashboardPeriod) {
        val fmt      = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val fmtShort = SimpleDateFormat("dd/MM", Locale.getDefault())

        tvActiveDateRange.text = if (period == DashboardPeriod.ALL)
            "📅 Todo el historial"
        else
            "📅 ${fmt.format(Date(range.from))} – ${fmt.format(Date(range.to))}"

        chipCustom.text = if (period == DashboardPeriod.CUSTOM)
            "🗓 ${fmtShort.format(Date(range.from))}–${fmtShort.format(Date(range.to))}"
        else
            "🗓 Personalizar"

        val targetId = chipIdFor(period)
        if (chipGroupPeriod.checkedChipId != targetId) {
            ignoreChipChange = true
            chipGroupPeriod.check(targetId)
            ignoreChipChange = false
        }
    }

    private fun showDateRangePicker(fallbackPeriod: DashboardPeriod) {
        val currentRange = viewModel.activeDateRange.value
        val startMs = if (currentRange.from > 0) currentRange.from
                      else System.currentTimeMillis() - 30L * 86_400_000
        val endMs = currentRange.to

        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Seleccionar rango de fechas")
            .setSelection(androidx.core.util.Pair(startMs, endMs))
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            val utcCal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))

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
            previousPeriod = DashboardPeriod.CUSTOM
        }

        picker.addOnNegativeButtonClickListener { restoreChip(fallbackPeriod) }
        picker.addOnCancelListener              { restoreChip(fallbackPeriod) }

        picker.show(supportFragmentManager, "date_range_picker")
    }

    private fun restoreChip(fallbackPeriod: DashboardPeriod) {
        ignoreChipChange = true
        chipGroupPeriod.check(chipIdFor(fallbackPeriod))
        ignoreChipChange = false
        if (viewModel.selectedPeriod.value != DashboardPeriod.CUSTOM) {
            viewModel.selectPeriod(fallbackPeriod)
        }
        previousPeriod = fallbackPeriod
    }

    private fun chipIdFor(period: DashboardPeriod): Int = when (period) {
        DashboardPeriod.WEEK   -> R.id.chip_week
        DashboardPeriod.MONTH  -> R.id.chip_month
        DashboardPeriod.YEAR   -> R.id.chip_year
        DashboardPeriod.ALL    -> R.id.chip_all
        DashboardPeriod.CUSTOM -> R.id.chip_custom
    }
}
