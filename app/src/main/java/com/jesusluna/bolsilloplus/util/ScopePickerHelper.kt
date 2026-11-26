package com.jesusluna.bolsilloplus.util

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.jesusluna.bolsilloplus.R
import com.jesusluna.bolsilloplus.data.repository.CategorizationScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Muestra el diálogo de selección de alcance (scope) para categorizar un movimiento.
 *
 * Opciones:
 *  1. Solo este movimiento (sin regla nueva)
 *  2. Mismo nombre exacto → crea regla EXACT
 *  3. Regla personalizada → el usuario escribe patrón + elige CONTAINS o CONTAINS_WORD
 *
 * Antes de aplicar siempre muestra una confirmación con el número de movimientos afectados.
 *
 * @param onScope      Recibe el [CategorizationScope] elegido (ExactName / ContainsPhrase / ContainsWord).
 * @param onApplyOnly  Callback para "Solo este movimiento" (sin regla nueva).
 * @param onDismiss    Opcional: se ejecuta tras aplicar (p.ej. para cerrar el BottomSheet padre).
 */
fun showScopePickerDialog(
    context: Context,
    inflater: LayoutInflater,
    normalizedConcept: String,
    categoryName: String,
    coroutineScope: CoroutineScope,
    countExact: suspend () -> Int,
    countPhrase: suspend (String) -> Int,
    countWord: suspend (String) -> Int,
    onScope: (CategorizationScope) -> Unit,
    onApplyOnly: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val dialogView = inflater.inflate(R.layout.dialog_scope_picker, null)

    val rgScope     = dialogView.findViewById<RadioGroup>(R.id.rg_scope)
    val rbExactName = dialogView.findViewById<RadioButton>(R.id.rb_exact_name)
    val llCustom    = dialogView.findViewById<View>(R.id.ll_custom_section)
    val etPattern   = dialogView.findViewById<TextInputEditText>(R.id.et_scope_pattern)
    val rgMatchType = dialogView.findViewById<RadioGroup>(R.id.rg_scope_match_type)

    rbExactName.text = "Mismo nombre: \"$normalizedConcept\""
    etPattern.setText(normalizedConcept)

    rgScope.check(R.id.rb_exact_name)
    rgScope.setOnCheckedChangeListener { _, checkedId ->
        llCustom.visibility = if (checkedId == R.id.rb_custom_rule) View.VISIBLE else View.GONE
    }

    val dialog = AlertDialog.Builder(context)
        .setTitle("Categorizar como: $categoryName")
        .setView(dialogView)
        .setPositiveButton("Aplicar", null)
        .setNegativeButton("Cancelar", null)
        .show()

    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        when (rgScope.checkedRadioButtonId) {

            R.id.rb_only_this -> {
                showScopeConfirmation(context, 1, categoryName) {
                    onApplyOnly()
                    onDismiss()
                    dialog.dismiss()
                }
            }

            R.id.rb_exact_name -> {
                coroutineScope.launch {
                    val count = withContext(Dispatchers.IO) { countExact() }
                    showScopeConfirmation(context, count, categoryName) {
                        onScope(CategorizationScope.ExactName)
                        onDismiss()
                        dialog.dismiss()
                    }
                }
            }

            R.id.rb_custom_rule -> {
                val rawPattern = etPattern.text?.toString()?.trim() ?: ""
                if (rawPattern.isEmpty()) {
                    Toast.makeText(context, "Introduce un patrón", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val pattern = DescriptionNormalizer.normalize(rawPattern)
                if (pattern.isEmpty()) {
                    Toast.makeText(context, "El patrón no es válido", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val isWord = rgMatchType.checkedRadioButtonId == R.id.rb_scope_contains_word
                val scope = if (isWord) CategorizationScope.ContainsWord(pattern)
                            else CategorizationScope.ContainsPhrase(pattern)

                coroutineScope.launch {
                    val count = withContext(Dispatchers.IO) {
                        if (isWord) countWord(pattern) else countPhrase(pattern)
                    }
                    showScopeConfirmation(context, count, categoryName) {
                        onScope(scope)
                        onDismiss()
                        dialog.dismiss()
                    }
                }
            }
        }
    }
}

private fun showScopeConfirmation(
    context: Context,
    count: Int,
    categoryName: String,
    onConfirm: () -> Unit
) {
    val message = if (count == 0)
        "Ningún movimiento existente coincide con este patrón.\n\n" +
        "Se creará la regla igualmente para futuros imports.\n¿Confirmar?"
    else
        "Se afectarán $count movimiento(s) que pasarán a la categoría \"$categoryName\".\n\n¿Confirmar?"

    AlertDialog.Builder(context)
        .setTitle("Confirmar cambio")
        .setMessage(message)
        .setPositiveButton("Confirmar") { _, _ ->
            onConfirm()
            Toast.makeText(context, "Categoría aplicada: $categoryName", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancelar", null)
        .show()
}
