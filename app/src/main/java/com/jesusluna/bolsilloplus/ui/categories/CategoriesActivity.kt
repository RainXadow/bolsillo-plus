package com.jesusluna.bolsilloplus.ui.categories

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jesusluna.bolsilloplus.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class CategoriesActivity : AppCompatActivity() {

    private val viewModel: CategoriesViewModel by viewModels()
    private lateinit var adapter: CategoriesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_categories)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_categories)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupRecyclerView()
        setupFab()
        setupApplyRulesButton()
        observeApplyRulesResult()

        // Si se navega aquí con una categoryId, abrir el detalle directamente
        intent.getLongExtra("categoryId", -1L).takeIf { it != -1L }?.let { catId ->
            openCategoryDetail(catId)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupRecyclerView() {
        adapter = CategoriesAdapter(
            onCategoryClick = { openCategoryDetail(it.category.id) }
        )
        val rv = findViewById<RecyclerView>(R.id.rv_categories)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        lifecycleScope.launch {
            viewModel.categoriesWithCount.collect { adapter.submitList(it) }
        }
    }

    private fun setupFab() {
        findViewById<FloatingActionButton>(R.id.fab_add_category).setOnClickListener {
            showCreateCategoryDialog()
        }
    }

    private fun setupApplyRulesButton() {
        findViewById<MaterialButton>(R.id.btn_apply_rules).setOnClickListener {
            // Pedir confirmación antes de escanear
            AlertDialog.Builder(this)
                .setTitle("Re-escanear automatizaciones")
                .setMessage(
                    "Se analizarán TODOS los cobros no categorizados manualmente " +
                    "(incluidos los ya categorizados automáticamente) y se aplicarán " +
                    "las reglas actualizadas.\n\nÚtil tras cambiar reglas o resolver conflictos.\n\n¿Continuar?"
                )
                .setPositiveButton("Re-escanear") { _, _ ->
                    viewModel.applyRulesToAll()
                    Toast.makeText(this, "Re-escaneando cobros...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun observeApplyRulesResult() {
        lifecycleScope.launch {
            viewModel.applyRulesResult.collect { result ->
                when (result) {
                    is ApplyRulesResult.Success -> {
                        val msg = if (result.categorized == 0) {
                            "Ningún cobro fue actualizado.\n" +
                            "(${result.total} cobros no manuales analizados, ninguno coincidió con reglas activas)"
                        } else {
                            "✅ ${result.categorized} cobro(s) categorizados/actualizados\n" +
                            "de ${result.total} cobros no manuales analizados."
                        }
                        AlertDialog.Builder(this@CategoriesActivity)
                            .setTitle("Resultado del escaneo")
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is ApplyRulesResult.Error -> {
                        Toast.makeText(this@CategoriesActivity, "Error: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun openCategoryDetail(categoryId: Long) {
        CategoryDetailBottomSheet.newInstance(categoryId)
            .show(supportFragmentManager, "category_detail")
    }

    private fun showCreateCategoryDialog() {
        val input = EditText(this).apply {
            hint = "Nombre de la categoría"
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Nueva Categoría")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createCategory(name)
                    Toast.makeText(this, "Categoría '$name' creada", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "El nombre no puede estar vacío", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

}
