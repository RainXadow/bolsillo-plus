package com.jesusluna.bolsilloplus

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jesusluna.bolsilloplus.data.db.dao.TransactionWithCategory
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionAdapter(
    private val onItemClick: (TransactionWithCategory) -> Unit,
    private val onItemLongClick: (TransactionWithCategory) -> Unit
) : ListAdapter<TransactionWithCategory, TransactionAdapter.TransactionViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TransactionWithCategory>() {
            override fun areItemsTheSame(
                a: TransactionWithCategory,
                b: TransactionWithCategory
            ) = a.transaction.id == b.transaction.id

            override fun areContentsTheSame(
                a: TransactionWithCategory,
                b: TransactionWithCategory
            ) = a == b
        }
    }

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("es", "ES"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvConcept: TextView = itemView.findViewById(R.id.tvConcept)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        private val tvConflictBadge: TextView = itemView.findViewById(R.id.tv_conflict_badge)
        private val tvSubcatBadge: TextView = itemView.findViewById(R.id.tv_subcat_badge)

        fun bind(item: TransactionWithCategory) {
            val tx = item.transaction

            tvConcept.text = tx.concept
            tvAmount.text = currencyFormat.format(tx.amount)
            tvDate.text = dateFormat.format(Date(tx.date))

            // Badge de conflicto: visible si más de una regla coincidió en el último scan
            tvConflictBadge.visibility = if (tx.candidateRuleCount > 1) View.VISIBLE else View.GONE

            // Color del importe según tipo
            tvAmount.setTextColor(
                when (tx.type) {
                    "INCOME" -> Color.parseColor("#2E7D32")
                    "EXPENSE" -> Color.parseColor("#C62828")
                    else -> Color.GRAY
                }
            )

            // Si la tx pertenece a una subcategoría: badge muestra el padre, debajo "↳ hijo"
            if (item.parentCategoryName != null) {
                tvCategory.text = item.parentCategoryName
                val badgeColor = try {
                    Color.parseColor(item.parentCategoryColor ?: "#9E9E9E")
                } catch (e: Exception) { Color.parseColor("#9E9E9E") }
                tvCategory.setBackgroundColor(badgeColor)

                tvSubcatBadge.text = "↳ ${item.categoryName}"
                tvSubcatBadge.visibility = View.VISIBLE
            } else {
                tvCategory.text = item.categoryName ?: "Sin categoría"
                val badgeColor = try {
                    Color.parseColor(item.categoryColor ?: "#9E9E9E")
                } catch (e: Exception) { Color.parseColor("#9E9E9E") }
                tvCategory.setBackgroundColor(badgeColor)

                tvSubcatBadge.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }
}
