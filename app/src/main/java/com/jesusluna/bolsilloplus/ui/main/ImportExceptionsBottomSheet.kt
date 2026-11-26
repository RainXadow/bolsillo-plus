package com.jesusluna.bolsilloplus.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jesusluna.bolsilloplus.R
import com.jesusluna.bolsilloplus.data.db.entity.ImportExceptionEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.data.db.entity.SkippedTransactionEntity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImportExceptionsBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_import_exceptions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvEmpty = view.findViewById<TextView>(R.id.tv_exceptions_empty)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_exceptions)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btn_add_exception)

        val adapter = ExceptionAdapter(
            onToggle  = { id, enabled -> viewModel.toggleException(id, enabled) },
            onDelete  = { exc -> showDeleteExceptionDialog(exc) },
            onClick   = { exc -> showSkippedTransactionsDialog(exc) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                viewModel.importExceptions,
                viewModel.skipCountsPerException
            ) { exceptions, counts -> exceptions to counts }
            .collect { (exceptions, counts) ->
                val isEmpty = exceptions.isEmpty()
                tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
                adapter.submitList(exceptions, counts)
            }
        }

        btnAdd.setOnClickListener { showAddExceptionDialog() }
    }

    // ── Diálogo: movimientos excluidos por esta excepción ─────────────────────

    private fun showSkippedTransactionsDialog(exc: ImportExceptionEntity) {
        val rv = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setPadding(0, 8, 0, 8)
        }
        val skippedAdapter = SkippedTransactionAdapter { skipped -> showRestoreDialog(skipped) }
        rv.adapter = skippedAdapter

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Excluidos por «${exc.patternRaw}»")
            .setView(rv)
            .setNegativeButton("Cerrar", null)
            .create()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.skippedForException(exc.id).collect { list ->
                skippedAdapter.submitList(list)
                val title = if (list.isEmpty())
                    "Sin excluidos por «${exc.patternRaw}»"
                else
                    "Excluidos por «${exc.patternRaw}» (${list.size})"
                dialog.setTitle(title)
            }
        }

        dialog.show()
    }

    private fun showRestoreDialog(skipped: SkippedTransactionEntity) {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val dateStr = sdf.format(Date(skipped.date))
        val fmt = NumberFormat.getCurrencyInstance(Locale("es", "ES"))
        val amountStr = fmt.format(skipped.amount)

        AlertDialog.Builder(requireContext())
            .setTitle("Restaurar movimiento")
            .setMessage(
                "¿Importar «${skipped.concept}» ($dateStr · $amountStr)?\n\n" +
                "Se añadirá a tu historial y no volverá a ser excluido."
            )
            .setPositiveButton("Restaurar") { _, _ ->
                viewModel.restoreSkippedTransaction(skipped)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Diálogo: eliminar excepción ───────────────────────────────────────────

    private fun showDeleteExceptionDialog(exc: ImportExceptionEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar excepción")
            .setMessage(
                "¿Eliminar la excepción «${exc.patternRaw}»?\n\n" +
                "Los movimientos excluidos por esta regla se importarán automáticamente en tu historial."
            )
            .setPositiveButton("Eliminar e importar") { _, _ -> viewModel.deleteException(exc.id) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Diálogo: añadir excepción ─────────────────────────────────────────────

    private fun showAddExceptionDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_exception, null)
        val etPattern = dialogView.findViewById<TextInputEditText>(R.id.et_exception_pattern)
        val rgMatchType = dialogView.findViewById<RadioGroup>(R.id.rg_exception_match_type)

        AlertDialog.Builder(requireContext())
            .setTitle("Nueva excepción")
            .setView(dialogView)
            .setPositiveButton("Añadir") { _, _ ->
                val pattern = etPattern.text?.toString()?.trim() ?: ""
                if (pattern.isNotEmpty()) {
                    val matchType = when (rgMatchType.checkedRadioButtonId) {
                        R.id.rb_exc_exact  -> MatchType.EXACT
                        R.id.rb_exc_regex  -> MatchType.REGEX
                        else               -> MatchType.CONTAINS
                    }
                    viewModel.addException(pattern, matchType)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── Adapter: lista de excepciones ─────────────────────────────────────────

    private class ExceptionAdapter(
        private val onToggle: (Long, Boolean) -> Unit,
        private val onDelete: (ImportExceptionEntity) -> Unit,
        private val onClick:  (ImportExceptionEntity) -> Unit
    ) : RecyclerView.Adapter<ExceptionAdapter.VH>() {

        private var items: List<ImportExceptionEntity> = emptyList()
        private var counts: Map<Long, Int> = emptyMap()

        fun submitList(list: List<ImportExceptionEntity>, skipCounts: Map<Long, Int> = counts) {
            items = list
            counts = skipCounts
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_import_exception, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvPattern    = view.findViewById<TextView>(R.id.tv_exception_pattern)
            private val tvMatchType  = view.findViewById<TextView>(R.id.tv_exception_match_type)
            private val tvCount      = view.findViewById<TextView>(R.id.tv_exception_skip_count)
            private val swEnabled    = view.findViewById<SwitchCompat>(R.id.switch_exception_enabled)
            private val btnDelete    = view.findViewById<ImageButton>(R.id.btn_exception_delete)

            fun bind(exc: ImportExceptionEntity) {
                tvPattern.text = exc.patternRaw
                tvMatchType.text = when (exc.matchType) {
                    MatchType.EXACT    -> "Exacto"
                    MatchType.CONTAINS -> "Contiene"
                    MatchType.REGEX    -> "Regex"
                    else               -> "Contiene" // CONTAINS_WORD se normaliza a CONTAINS al guardar
                }

                val count = counts[exc.id] ?: 0
                if (count > 0) {
                    tvCount.visibility = View.VISIBLE
                    tvCount.text = "· $count excluido${if (count != 1) "s" else ""}"
                } else {
                    tvCount.visibility = View.GONE
                }

                swEnabled.setOnCheckedChangeListener(null)
                swEnabled.isChecked = exc.isEnabled
                swEnabled.setOnCheckedChangeListener { _, isChecked -> onToggle(exc.id, isChecked) }

                btnDelete.setOnClickListener { onDelete(exc) }

                // Click en la fila → mostrar movimientos excluidos
                itemView.setOnClickListener { onClick(exc) }
            }
        }
    }

    // ── Adapter: lista de movimientos excluidos ───────────────────────────────

    private class SkippedTransactionAdapter(
        private val onRestore: (SkippedTransactionEntity) -> Unit
    ) : RecyclerView.Adapter<SkippedTransactionAdapter.VH>() {

        private var items: List<SkippedTransactionEntity> = emptyList()
        private val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        private val fmt = NumberFormat.getCurrencyInstance(Locale("es", "ES"))

        fun submitList(list: List<SkippedTransactionEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_skipped_transaction, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvConcept = view.findViewById<TextView>(R.id.tv_skipped_concept)
            private val tvDate    = view.findViewById<TextView>(R.id.tv_skipped_date)
            private val tvAmount  = view.findViewById<TextView>(R.id.tv_skipped_amount)
            private val btnRestore = view.findViewById<ImageButton>(R.id.btn_restore_skipped)

            fun bind(skipped: SkippedTransactionEntity) {
                tvConcept.text = skipped.concept
                tvDate.text = sdf.format(Date(skipped.date))
                tvAmount.text = fmt.format(skipped.amount)
                tvAmount.setTextColor(
                    itemView.context.getColor(
                        if (skipped.amount >= 0) R.color.income_label else R.color.expense_label
                    )
                )
                btnRestore.setOnClickListener { onRestore(skipped) }
                itemView.setOnClickListener { onRestore(skipped) }
            }
        }
    }
}
