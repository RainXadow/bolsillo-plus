package com.jesusluna.bolsilloplus.ui.main

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.jesusluna.bolsilloplus.R
import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory
import com.jesusluna.bolsilloplus.data.db.entity.CategoryEntity
import com.jesusluna.bolsilloplus.ui.categories.CategoriesActivity
import com.jesusluna.bolsilloplus.util.showScopePickerDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.NumberFormat
import java.util.Locale

class CategoryPickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TX_ID = "tx_id"
        private const val ARG_TX_CONCEPT = "tx_concept"
        private const val ARG_TX_NORMALIZED = "tx_normalized"
        private const val ARG_TX_AMOUNT = "tx_amount"

        fun newInstance(tx: TransactionWithCategory) = CategoryPickerBottomSheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_TX_ID, tx.transaction.id)
                putString(ARG_TX_CONCEPT, tx.transaction.concept)
                putString(ARG_TX_NORMALIZED, tx.transaction.normalizedConcept)
                putDouble(ARG_TX_AMOUNT, tx.transaction.amount)
            }
        }
    }

    private val viewModel: MainViewModel by activityViewModels()
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("es", "ES"))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_category_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val txId = requireArguments().getLong(ARG_TX_ID)
        val concept = requireArguments().getString(ARG_TX_CONCEPT, "")
        val normalized = requireArguments().getString(ARG_TX_NORMALIZED, "")
        val amount = requireArguments().getDouble(ARG_TX_AMOUNT)

        view.findViewById<TextView>(R.id.tv_picker_concept).text = concept
        view.findViewById<TextView>(R.id.tv_picker_amount).text = currencyFormat.format(amount)

        val container = view.findViewById<LinearLayout>(R.id.ll_categories_container)
        val indentPx = resources.getDimensionPixelSize(R.dimen.subcat_indent)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.categories.collect { categories ->
                // Limpiar antes de repintar para evitar chips duplicados
                container.removeAllViews()

                val roots    = categories.filter { it.parentId == null }
                val byParent = categories.filter { it.parentId != null }.groupBy { it.parentId!! }

                // ChipGroup que acumula chips de roots sin hijos
                var pendingRootGroup: ChipGroup? = null

                fun flushPending() {
                    pendingRootGroup?.let { if (it.childCount > 0) container.addView(it) }
                    pendingRootGroup = null
                }

                roots.forEach { root ->
                    val children = byParent[root.id] ?: emptyList()
                    if (children.isEmpty()) {
                        // Root sin hijos → chip simple añadido al grupo acumulador
                        if (pendingRootGroup == null) {
                            pendingRootGroup = ChipGroup(requireContext()).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = 4 }
                            }
                        }
                        pendingRootGroup!!.addView(buildChip(root, txId, normalized))
                    } else {
                        // Root con hijos → volcar pendientes y añadir bloque padre+hijos
                        flushPending()
                        val groupLayout = LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        }
                        val parentGroup = ChipGroup(requireContext())
                        parentGroup.addView(buildChip(root, txId, normalized))
                        groupLayout.addView(parentGroup)

                        val childGroup = ChipGroup(requireContext()).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                marginStart = indentPx
                                bottomMargin = 8
                            }
                        }
                        children.forEach { child -> childGroup.addView(buildChip(child, txId, normalized)) }
                        groupLayout.addView(childGroup)
                        container.addView(groupLayout)
                    }
                }
                flushPending()
            }
        }

        view.findViewById<Button>(R.id.btn_manage_categories).setOnClickListener {
            startActivity(Intent(requireContext(), CategoriesActivity::class.java))
            dismiss()
        }
    }

    /** Construye un Chip para una categoría. */
    private fun buildChip(category: CategoryEntity, txId: Long, normalized: String): Chip =
        Chip(requireContext()).apply {
            text = category.name
            isClickable = true
            isCheckable = false
            chipBackgroundColor = try {
                ColorStateList.valueOf(Color.parseColor(category.color))
            } catch (e: Exception) {
                ColorStateList.valueOf(Color.parseColor("#2196F3"))
            }
            setTextColor(Color.WHITE)
            setOnClickListener {
                showScopeDialog(txId, normalized, category.id, category.name)
            }
        }

    private fun showScopeDialog(txId: Long, normalized: String, categoryId: Long, categoryName: String) {
        showScopePickerDialog(
            context = requireContext(),
            inflater = layoutInflater,
            normalizedConcept = normalized,
            categoryName = categoryName,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            countExact = { viewModel.countByExactConcept(normalized) },
            countPhrase = { pattern -> viewModel.countContainingPhrase(pattern) },
            countWord = { word -> viewModel.countContainingWord(word) },
            onScope = { scope -> viewModel.categorizeWithScope(txId, normalized, categoryId, scope) },
            onApplyOnly = { viewModel.categorizeScopeOnly(txId, categoryId) },
            onDismiss = { dismiss() }
        )
    }
}
