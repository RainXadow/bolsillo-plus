package com.jesusluna.bolsilloplus.ui.main

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Adaptador de un solo ítem que muestra el encabezado estático del dashboard.
 *
 * Al usar ConcatAdapter(MainHeaderAdapter, TransactionAdapter), el RecyclerView
 * puede tener layout_height="match_parent" y virtualizar correctamente los items
 * de la lista de transacciones, eliminando el ANR causado por wrap_content +
 * NestedScrollView que obligaba a inflar todos los items a la vez.
 */
class MainHeaderAdapter(private val header: View) : RecyclerView.Adapter<MainHeaderAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(header)

    override fun onBindViewHolder(holder: VH, position: Int) = Unit
}
