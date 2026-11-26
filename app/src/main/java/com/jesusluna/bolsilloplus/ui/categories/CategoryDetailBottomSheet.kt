package com.jesusluna.bolsilloplus.ui.categories

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jesusluna.bolsilloplus.R
import com.jesusluna.bolsilloplus.data.db.dao.CategoryWithCount
import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.TransactionEntity
import com.jesusluna.bolsilloplus.data.repository.CategorizationScope
import com.jesusluna.bolsilloplus.util.showScopePickerDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CategoryDetailBottomSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(categoryId: Long) = CategoryDetailBottomSheet().apply {
            arguments = Bundle().apply { putLong("categoryId", categoryId) }
        }
    }

    private val viewModel: CategoriesViewModel by activityViewModels()
    private var currentCat: CategoryWithCount? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_category_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val categoryId = requireArguments().getLong("categoryId")

        val tabLayout = view.findViewById<TabLayout>(R.id.tab_layout_detail)
        val rvTransactions = view.findViewById<RecyclerView>(R.id.rv_detail_transactions)
        val llRulesSection = view.findViewById<View>(R.id.ll_rules_section)
        val rvRules = view.findViewById<RecyclerView>(R.id.rv_detail_rules)
        val btnAddRule = view.findViewById<MaterialButton>(R.id.btn_add_rule)
        val btnRename = view.findViewById<MaterialButton>(R.id.btn_rename_category)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btn_delete_category)
        val btnReassignAll = view.findViewById<MaterialButton>(R.id.btn_reassign_all)
        val btnAddSubcategory = view.findViewById<MaterialButton>(R.id.btn_add_subcategory)

        // ── Adapters ──────────────────────────────────────────────────────────
        val txAdapter = TransactionMiniAdapter(
            onClick = { tx -> showCategorizeScopeFromDetail(tx) },
            onLongClick = { tx -> showRuleDiagnosisDialog(tx) }
        )
        val rulesAdapter = RulesAdapter(
            onDeleteClick = { ruleId ->
                viewModel.deleteRule(ruleId)
                Toast.makeText(requireContext(), "Regla eliminada", Toast.LENGTH_SHORT).show()
            },
            onEditPriority = { rule -> showEditPriorityDialog(rule) },
            onToggleEnabled = { ruleId, enabled ->
                viewModel.toggleRule(ruleId, enabled)
                val msg = if (enabled) "Regla activada" else "Regla desactivada"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        )

        rvTransactions.layoutManager = LinearLayoutManager(requireContext())
        rvTransactions.adapter = txAdapter
        rvRules.layoutManager = LinearLayoutManager(requireContext())
        rvRules.adapter = rulesAdapter

        // ── Tabs ─────────────────────────────────────────────────────────────
        tabLayout.addTab(tabLayout.newTab().setText("Movimientos"))
        tabLayout.addTab(tabLayout.newTab().setText("Automatizaciones"))
        showTab(0, rvTransactions, llRulesSection)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tab.position, rvTransactions, llRulesSection)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // ── Observar reglas ───────────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.getRulesForCategory(categoryId).collect { rulesAdapter.update(it) }
        }

        // ── Flows de transacciones: isRoot determinado una vez con first{} ────
        // Se evita iniciar collectors dentro de otro collector.
        viewLifecycleOwner.lifecycleScope.launch {
            val cats = viewModel.categoriesWithCount
                .first { list -> list.any { it.category.id == categoryId } }
            val firstCat = cats.first { it.category.id == categoryId }
            val isRoot = firstCat.category.parentId == null

            btnAddSubcategory.visibility = if (isRoot) View.VISIBLE else View.GONE

            val txFlow = if (isRoot) viewModel.getTransactionsForParentTree(categoryId)
                         else viewModel.getTransactionsForCategory(categoryId)
            launch { txFlow.collect { txAdapter.update(it) } }

            if (isRoot) {
                launch {
                    viewModel.observeChildrenOf(categoryId).collect { children ->
                        txAdapter.updateSubcategoryInfo(
                            children.associate { it.id to it.name },
                            categoryId
                        )
                    }
                }
            }
        }

        // ── Cabecera: reactiva a cambios de nombre/color/estado ───────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.categoriesWithCount.collect { list ->
                val cat = list.firstOrNull { it.category.id == categoryId } ?: return@collect
                currentCat = cat

                view.findViewById<TextView>(R.id.tv_detail_title).text = cat.category.name

                val colorInt = try { Color.parseColor(cat.category.color) }
                               catch (e: Exception) { Color.parseColor("#2196F3") }
                val circle = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                }
                view.findViewById<View>(R.id.view_detail_color).background = circle

                btnDelete.isEnabled = true
                btnDelete.alpha = 1f
            }
        }

        // ── Botones de acción ─────────────────────────────────────────────────
        btnRename.setOnClickListener { currentCat?.let { showRenameDialog(it) } }
        btnDelete.setOnClickListener { currentCat?.let { showDeleteDialogWithChildren(it) } }
        btnAddRule.setOnClickListener { showAddRuleDialog(categoryId) }
        btnAddSubcategory.setOnClickListener { showAddSubcategoryDialog(categoryId) }

        btnReassignAll.setOnClickListener { currentCat?.let { showReassignAllDialog(it) } }

        // ── Resultados de reasignación masiva ─────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.reassignResult.collect { result ->
                when (result) {
                    is ReassignResult.Success -> Toast.makeText(
                        requireContext(),
                        "${result.count} movimiento(s) reasignado(s) correctamente",
                        Toast.LENGTH_SHORT
                    ).show()
                    is ReassignResult.Error -> Toast.makeText(
                        requireContext(), "Error: ${result.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ── Renombrar ─────────────────────────────────────────────────────────────

    private fun showRenameDialog(cat: CategoryWithCount) {
        val input = EditText(requireContext()).apply {
            setText(cat.category.name)
            hint = "Nuevo nombre"
            selectAll()
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Renombrar categoría")
            .setMessage("El nuevo nombre se aplicará a todos los movimientos con esta categoría.")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() -> Toast.makeText(
                        requireContext(), "El nombre no puede estar vacío", Toast.LENGTH_SHORT
                    ).show()
                    newName == cat.category.name -> Unit
                    else -> {
                        viewModel.renameCategory(cat.category, newName)
                        Toast.makeText(requireContext(), "Categoría renombrada", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Eliminar ──────────────────────────────────────────────────────────────

    private fun showDeleteDialogWithChildren(cat: CategoryWithCount) {
        viewLifecycleOwner.lifecycleScope.launch {
            val childCount = viewModel.countChildren(cat.category.id)
            // Usar solo el count de txs propias (no subárbol) para el mensaje de impacto
            val ownTxCount = viewModel.countOwnTransactions(cat.category.id)
            val msg = buildString {
                append("¿Eliminar la categoría '${cat.category.name}'?\n\n")
                if (childCount > 0) append("$childCount subcategoría(s) pasarán a ser independientes.\n")
                if (ownTxCount > 0) append("$ownTxCount movimiento(s) propios quedarán sin categoría.\n")
                if (childCount > 0) append("Los movimientos de las subcategorías no se verán afectados.\n")
                append("Sus reglas de automatización también se eliminarán.")
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Eliminar categoría")
                .setMessage(msg)
                .setPositiveButton("Eliminar") { _, _ ->
                    val name = cat.category.name
                    viewModel.delete(cat.category.id)
                    dismiss()
                    Toast.makeText(requireContext(), "Categoría '$name' eliminada", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    // ── Añadir subcategoría ───────────────────────────────────────────────────

    private fun showAddSubcategoryDialog(parentId: Long) {
        val input = EditText(requireContext()).apply {
            hint = "Nombre de la subcategoría"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Nueva subcategoría")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> Toast.makeText(
                        requireContext(), "El nombre no puede estar vacío", Toast.LENGTH_SHORT
                    ).show()
                    else -> {
                        viewModel.createSubcategory(name, parentId = parentId)
                        Toast.makeText(requireContext(), "Subcategoría '$name' creada", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Añadir regla ──────────────────────────────────────────────────────────

    private fun showAddRuleDialog(categoryId: Long) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_rule, null)
        AlertDialog.Builder(requireContext())
            .setTitle("Nuevo patrón de cobro")
            .setView(dialogView)
            .setPositiveButton("Añadir") { _, _ ->
                val input = dialogView.findViewById<EditText>(R.id.et_rule_pattern)
                val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_match_type)
                val pattern = input.text.toString().trim()
                if (pattern.isEmpty()) {
                    Toast.makeText(requireContext(), "Introduce un patrón", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val matchType = when (radioGroup.checkedRadioButtonId) {
                    R.id.rb_exact          -> MatchType.EXACT
                    R.id.rb_contains_word  -> MatchType.CONTAINS_WORD
                    R.id.rb_regex          -> MatchType.REGEX
                    else                   -> MatchType.CONTAINS
                }
                viewModel.addRule(categoryId, pattern, matchType)
                Toast.makeText(requireContext(), "Patrón '$pattern' añadido", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Editar prioridad de regla ──────────────────────────────────────────────

    private fun showEditPriorityDialog(rule: CategoryRuleEntity) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(rule.priority.toString())
            selectAll()
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Prioridad: '${rule.normalizedPattern}'")
            .setMessage(
                "A mayor número, antes se aplica esta regla (mismo tipo).\n\n" +
                "Valores base:\n  Exacta: 100\n  Contiene frase: 50\n  Palabra: 45\n  Regex: 10\n\n" +
                "Nota: patrones más largos ganan automáticamente sin editar prioridad.\n" +
                "Edita solo si necesitas forzar un orden entre patrones de igual longitud."
            )
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val newPriority = input.text.toString().toIntOrNull()
                if (newPriority != null && newPriority > 0) {
                    viewModel.updateRulePriority(rule.id, newPriority)
                    Toast.makeText(requireContext(), "Prioridad actualizada a $newPriority", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Introduce un número mayor que 0", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Diagnóstico de categorización (long-press en movimiento) ──────────────

    private fun showRuleDiagnosisDialog(tx: TransactionEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val matchInfos = viewModel.getMatchingRulesForConcept(tx.normalizedConcept)

            val msg = buildString {
                append("Concepto: '${tx.normalizedConcept}'\n\n")

                when {
                    tx.isManual -> append("Origen: categorizado manualmente.\n\n")
                    tx.assignedByRuleId != null -> append("Origen: asignado automáticamente por regla.\n\n")
                    else -> append("Origen: sin información (dato previo a V.1.5).\n\n")
                }

                if (matchInfos.isEmpty()) {
                    append("No hay reglas que coincidan con este concepto actualmente.")
                } else {
                    append("Reglas que coinciden (orden de precedencia):\n\n")
                    matchInfos.forEachIndexed { index, info ->
                        val isWinner = index == 0
                        val bullet = if (isWinner) "▶ " else "   "
                        val typeLabel = when (info.rule.matchType) {
                            MatchType.EXACT         -> "Exacta"
                            MatchType.CONTAINS      -> "Frase"
                            MatchType.CONTAINS_WORD -> "Palabra"
                            MatchType.REGEX         -> "Regex"
                        }
                        append("${bullet}[$typeLabel] '${info.rule.normalizedPattern}'")
                        append("\n   → ${info.categoryName}")
                        if (isWinner) append("  ← activa")
                        append("\n\n")
                    }
                    if (matchInfos.size > 1) {
                        val hasMixedTypes = matchInfos.map { it.rule.matchType }.toSet().size > 1
                        if (hasMixedTypes) {
                            append("⚠ Las reglas de mayor especificidad siempre tienen preferencia:\n" +
                                   "Exacta > Frase > Palabra > Regex.\n" +
                                   "Cambiar la prioridad de una regla menos específica no puede hacerla " +
                                   "ganar sobre una más específica. Para que prevalezca una categoría " +
                                   "distinta, crea una regla más específica usando \"Reclasificar\".")
                        } else {
                            append("Hay ${matchInfos.size} reglas en conflicto del mismo tipo.\n" +
                                   "Usa \"Reclasificar\" para asignar la categoría correcta con el alcance que prefieras.")
                        }
                    }
                }
            }

            val builder = AlertDialog.Builder(requireContext())
                .setTitle("Diagnóstico de categoría")
                .setMessage(msg)
                .setPositiveButton("OK", null)

            if (matchInfos.size > 1) {
                builder.setNeutralButton("Reclasificar...") { _, _ ->
                    showPickWinnerDialog(tx, matchInfos)
                }
            }

            builder.show()
        }
    }

    // ── Elegir regla ganadora ─────────────────────────────────────────────────

    private fun showPickWinnerDialog(tx: TransactionEntity, matchInfos: List<RuleMatchInfo>) {
        val hasMixedTypes = matchInfos.map { it.rule.matchType }.toSet().size > 1

        val names = matchInfos.mapIndexed { index, info ->
            val typeLabel = when (info.rule.matchType) {
                MatchType.EXACT         -> "Exacta"
                MatchType.CONTAINS      -> "Frase"
                MatchType.CONTAINS_WORD -> "Palabra"
                MatchType.REGEX         -> "Regex"
            }
            val marker = if (index == 0) "▶ " else "   "
            "${marker}[$typeLabel] \"${info.rule.normalizedPattern}\" → ${info.categoryName}"
        }.toTypedArray()

        val description = buildString {
            append("Elige la categoría que quieres asignar a este movimiento.\n")
            append("A continuación podrás elegir si aplicarla solo aquí o a un conjunto de movimientos.\n")
            if (hasMixedTypes) {
                append("\n⚠ Las reglas con mayor especificidad siempre prevalecen " +
                       "(Exacta > Frase > Palabra > Regex). " +
                       "La marcada con ▶ es la activa por diseño del sistema.\n" +
                       "Para que otra categoría gane de forma permanente, " +
                       "elige esa categoría y crea una regla de tipo Exacta o Frase en el siguiente paso.")
            }
        }

        var selectedIndex = 0

        AlertDialog.Builder(requireContext())
            .setTitle("Reclasificar: \"${tx.normalizedConcept}\"")
            .setMessage(description)
            .setSingleChoiceItems(names, 0) { _, which -> selectedIndex = which }
            .setPositiveButton("Elegir alcance...") { _, _ ->
                val chosen = matchInfos[selectedIndex]
                showScopeDialogFromDetail(tx, chosen.rule.categoryId, chosen.categoryName)
            }
            .setNeutralButton("Otra categoría...") { _, _ -> showCategorizeScopeFromDetail(tx) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Categorizar desde el detalle de categoría ─────────────────────────────

    private fun showCategorizeScopeFromDetail(tx: TransactionEntity) {
        val allCategories = viewModel.categoriesWithCount.value
        if (allCategories.isEmpty()) return

        val categoryNames = allCategories.map { it.category.name }.toTypedArray()
        var selectedCatIndex = -1

        AlertDialog.Builder(requireContext())
            .setTitle("Categoría para:\n\"${tx.concept}\"")
            .setSingleChoiceItems(categoryNames, -1) { _, which -> selectedCatIndex = which }
            .setPositiveButton("Siguiente") { _, _ ->
                if (selectedCatIndex < 0) {
                    Toast.makeText(requireContext(), "Selecciona una categoría", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val targetCat = allCategories[selectedCatIndex]
                showScopeDialogFromDetail(tx, targetCat.category.id, targetCat.category.name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showScopeDialogFromDetail(tx: TransactionEntity, categoryId: Long, categoryName: String) {
        showScopePickerDialog(
            context = requireContext(),
            inflater = layoutInflater,
            normalizedConcept = tx.normalizedConcept,
            categoryName = categoryName,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            countExact = { viewModel.countByExactConcept(tx.normalizedConcept) },
            countPhrase = { pattern -> viewModel.countContainingPhrase(pattern) },
            countWord = { word -> viewModel.countContainingWord(word) },
            onScope = { scope ->
                viewModel.categorizeWithScope(tx.id, tx.normalizedConcept, categoryId, scope)
            },
            onApplyOnly = {
                viewModel.categorizeWithScope(tx.id, tx.normalizedConcept, categoryId, CategorizationScope.OnlyThis)
            }
        )
    }

    // ── Reasignar todos ───────────────────────────────────────────────────────

    private fun showReassignAllDialog(cat: CategoryWithCount) {
        val otherCategories = viewModel.categoriesWithCount.value
            .filter { it.category.id != cat.category.id }

        if (otherCategories.isEmpty()) {
            Toast.makeText(requireContext(), "No hay otras categorías disponibles", Toast.LENGTH_SHORT).show()
            return
        }
        val txCount = cat.transactionCount
        if (txCount == 0) {
            Toast.makeText(requireContext(), "Esta categoría no tiene movimientos", Toast.LENGTH_SHORT).show()
            return
        }

        val names = otherCategories.map { it.category.name }.toTypedArray()
        var selectedIndex = -1

        // NOTA: setMessage + setSingleChoiceItems son mutuamente excluyentes en AlertDialog.
        // El texto informativo va en el título para no bloquear la lista seleccionable.
        AlertDialog.Builder(requireContext())
            .setTitle("Mover $txCount movimiento(s) de\n'${cat.category.name}' a...")
            .setSingleChoiceItems(names, -1) { _, which -> selectedIndex = which }
            .setPositiveButton("Mover") { _, _ ->
                if (selectedIndex < 0) {
                    Toast.makeText(requireContext(), "Selecciona una categoría destino", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val target = otherCategories[selectedIndex]
                showReassignConfirmDialog(cat, target.category.id, target.category.name, txCount)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showReassignConfirmDialog(
        from: CategoryWithCount, toId: Long, toName: String, txCount: Int
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle("Confirmar reasignación")
            .setMessage(
                "¿Mover $txCount movimiento(s) de '${from.category.name}' a '$toName'?\n\n" +
                "Las reglas de '${from.category.name}' no se modificarán. " +
                "Revísalas si no quieres que futuros cobros vayan a esa categoría."
            )
            .setPositiveButton("Mover todo") { _, _ -> viewModel.reassignAll(from.category.id, toId) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showTab(position: Int, rvTx: View, llRules: View) {
        rvTx.visibility = if (position == 0) View.VISIBLE else View.GONE
        llRules.visibility = if (position == 1) View.VISIBLE else View.GONE
    }
}

// ─── Adapter mini para transacciones ─────────────────────────────────────────

class TransactionMiniAdapter(
    private val onClick: (TransactionEntity) -> Unit,
    private val onLongClick: (TransactionEntity) -> Unit
) : RecyclerView.Adapter<TransactionMiniAdapter.VH>() {

    private var items = listOf<TransactionEntity>()
    private var subcategoryNames: Map<Long, String> = emptyMap()
    private var viewedCategoryId: Long = 0L
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("es", "ES"))

    fun update(newItems: List<TransactionEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun updateSubcategoryInfo(names: Map<Long, String>, viewedCatId: Long) {
        subcategoryNames = names
        viewedCategoryId = viewedCatId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_mini_transaction, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvConcept       = v.findViewById<TextView>(R.id.tv_mini_concept)
        private val tvAmount        = v.findViewById<TextView>(R.id.tv_mini_amount)
        private val tvDate          = v.findViewById<TextView>(R.id.tv_mini_date)
        private val tvSource        = v.findViewById<TextView>(R.id.tv_mini_source)
        private val tvConflictBadge = v.findViewById<TextView>(R.id.tv_mini_conflict_badge)
        private val tvSubcatBadge   = v.findViewById<TextView>(R.id.tv_subcat_badge)

        fun bind(tx: TransactionEntity) {
            tvConcept.text = tx.concept
            tvAmount.text = currencyFormat.format(tx.amount)
            tvDate.text = dateFormat.format(Date(tx.date))
            tvAmount.setTextColor(
                if (tx.type == "INCOME") Color.parseColor("#388E3C")
                else Color.parseColor("#D32F2F")
            )

            // Badge de conflicto
            tvConflictBadge.visibility = if (tx.candidateRuleCount > 1) View.VISIBLE else View.GONE

            // Badge subcategoría: visible cuando la tx pertenece a un hijo y se ve desde el padre
            val subcatName = if (tx.categoryId != null && tx.categoryId != viewedCategoryId) {
                subcategoryNames[tx.categoryId]
            } else null
            if (subcatName != null) {
                tvSubcatBadge.text = "↳ $subcatName"
                tvSubcatBadge.visibility = View.VISIBLE
            } else {
                tvSubcatBadge.visibility = View.GONE
            }

            // Badge de origen
            when {
                tx.isManual -> {
                    tvSource.text = "✎ Manual"
                    tvSource.setTextColor(Color.parseColor("#4CAF50"))
                }
                tx.assignedByRuleId != null -> {
                    tvSource.text = "⚙ Automático"
                    tvSource.setTextColor(Color.parseColor("#2196F3"))
                }
                else -> {
                    tvSource.text = "– sin info"
                    tvSource.setTextColor(Color.parseColor("#AAAAAA"))
                }
            }

            itemView.setOnClickListener { onClick(tx) }
            itemView.setOnLongClickListener {
                onLongClick(tx)
                true
            }
        }
    }
}

// ─── Adapter para reglas de automatización ────────────────────────────────────

class RulesAdapter(
    private val onDeleteClick: (Long) -> Unit,
    private val onEditPriority: (CategoryRuleEntity) -> Unit,
    private val onToggleEnabled: (Long, Boolean) -> Unit
) : RecyclerView.Adapter<RulesAdapter.VH>() {

    private var items = listOf<CategoryRuleEntity>()

    fun update(newItems: List<CategoryRuleEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvPattern       = v.findViewById<TextView>(R.id.tv_rule_pattern)
        private val tvMatchType     = v.findViewById<TextView>(R.id.tv_rule_match_type)
        private val tvPriority      = v.findViewById<TextView>(R.id.tv_rule_priority)
        private val switchEnabled   = v.findViewById<SwitchCompat>(R.id.switch_rule_enabled)
        private val btnEditPriority = v.findViewById<ImageButton>(R.id.btn_edit_priority)
        private val btnDelete       = v.findViewById<ImageButton>(R.id.btn_rule_delete)

        fun bind(rule: CategoryRuleEntity) {
            tvPattern.text = rule.normalizedPattern
            tvMatchType.text = when (rule.matchType) {
                MatchType.EXACT         -> "Exacta"
                MatchType.CONTAINS      -> "Contiene"
                MatchType.CONTAINS_WORD -> "Palabra"
                MatchType.REGEX         -> "Regex"
            }
            tvPriority.text = "P:${rule.priority}"

            // Desactivar listener antes de setChecked para evitar llamadas redundantes
            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = rule.isEnabled
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(rule.id, isChecked)
            }

            // Atenuar visualmente si la regla está desactivada
            itemView.alpha = if (rule.isEnabled) 1f else 0.45f

            btnEditPriority.setOnClickListener { onEditPriority(rule) }
            btnDelete.setOnClickListener { onDeleteClick(rule.id) }
        }
    }
}
