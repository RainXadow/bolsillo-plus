package com.jesusluna.bolsilloplus.ui.categories

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jesusluna.bolsilloplus.R
import com.jesusluna.bolsilloplus.data.db.dao.CategoryWithCount

class CategoriesAdapter(
    private val onCategoryClick: (CategoryWithCount) -> Unit
) : ListAdapter<CategoryWithCount, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val TYPE_PARENT = 0
        private const val TYPE_CHILD  = 1

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CategoryWithCount>() {
            override fun areItemsTheSame(a: CategoryWithCount, b: CategoryWithCount) =
                a.category.id == b.category.id

            override fun areContentsTheSame(a: CategoryWithCount, b: CategoryWithCount) = a == b
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).category.parentId != null) TYPE_CHILD else TYPE_PARENT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CHILD) {
            ChildViewHolder(inflater.inflate(R.layout.item_subcategory, parent, false))
        } else {
            ParentViewHolder(inflater.inflate(R.layout.item_category, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ParentViewHolder -> holder.bind(item)
            is ChildViewHolder  -> holder.bind(item)
        }
    }

    inner class ParentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_cat_name)
        private val tvCount: TextView = itemView.findViewById(R.id.tv_cat_count)
        private val colorStripe: View = itemView.findViewById(R.id.view_cat_color)

        fun bind(item: CategoryWithCount) {
            tvName.text = item.category.name
            tvCount.text = "${item.transactionCount} movimientos"
            try {
                colorStripe.setBackgroundColor(Color.parseColor(item.category.color))
            } catch (e: Exception) {
                colorStripe.setBackgroundColor(Color.parseColor("#2196F3"))
            }
            itemView.setOnClickListener { onCategoryClick(item) }
        }
    }

    inner class ChildViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_cat_name)
        private val tvCount: TextView = itemView.findViewById(R.id.tv_cat_count)
        private val colorStripe: View = itemView.findViewById(R.id.view_cat_color)

        fun bind(item: CategoryWithCount) {
            tvName.text = item.category.name
            tvCount.text = "${item.transactionCount} propios"
            try {
                colorStripe.setBackgroundColor(Color.parseColor(item.category.color))
            } catch (e: Exception) {
                colorStripe.setBackgroundColor(Color.parseColor("#2196F3"))
            }
            itemView.setOnClickListener { onCategoryClick(item) }
        }
    }
}
