package com.jesusluna.bolsilloplus.ui.onboarding

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.jesusluna.bolsilloplus.R

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME   = "bolsillo_prefs"
        private const val KEY_COMPLETED = "onboarding_completed"

        /** True si el onboarding ya fue completado (o saltado) por el usuario. */
        fun isCompleted(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_COMPLETED, false)

        private fun markCompleted(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_COMPLETED, true).apply()
        }
    }

    /** Lista de páginas del tutorial. Añade o quita elementos aquí para cambiar el contenido. */
    private val pages = listOf(
        OnboardingPage(
            title     = "Bienvenido a Bolsillo+",
            subtitle  = "Tu banco de bolsillo, sin complicaciones",
            imageRes  = R.drawable.ic_onboarding_import,
            bullets   = listOf(
                "Descarga el Excel de movimientos desde la app de tu banco",
                "Pulsa el botón + (abajo a la derecha) y abre el archivo",
                "¡Listo! Tus pagos de Mercadona, Bizum y demás aparecen al instante"
            )
        ),
        OnboardingPage(
            title     = "Ignora lo que no importa",
            subtitle  = "Algunos cobros no necesitas verlos cada mes",
            imageRes  = R.drawable.ic_onboarding_exceptions,
            bullets   = listOf(
                "Pulsa ⋮ arriba → «Filtros de importación» para gestionarlos",
                "Añade «Netflix» o «Amazon Prime» y nunca volverán a aparecer",
                "Si cambias de opinión, puedes recuperar cualquier movimiento"
            )
        ),
        OnboardingPage(
            title     = "Categoriza en segundos",
            subtitle  = "Toca un gasto y dile a qué categoría pertenece",
            imageRes  = R.drawable.ic_onboarding_rules,
            bullets   = listOf(
                "Toca «Mercadona» → elige «Alimentación» → aplica a todos los iguales",
                "Bolsillo+ recuerda tu elección y categoriza solo la próxima vez",
                "Puedes crear tus propias categorías en ⋮ → «Categorías»"
            )
        ),
        OnboardingPage(
            title     = "Ve a dónde va tu dinero",
            subtitle  = "Un vistazo rápido a tus gastos del mes",
            imageRes  = R.drawable.ic_onboarding_dashboard,
            bullets   = listOf(
                "Usa los botones Semana / Mes / Año para cambiar el período",
                "El gráfico circular muestra cuánto dinero hay en cada categoría",
                "Escribe «spotify» en el buscador para encontrar un pago concreto"
            )
        ),
        OnboardingPage(
            title     = "Tus datos, siempre a salvo",
            subtitle  = "Haz una copia de seguridad cuando quieras",
            imageRes  = R.drawable.ic_onboarding_backup,
            bullets   = listOf(
                "Pulsa ⋮ → «Exportar datos» para guardar una copia en tu móvil",
                "Puedes restaurarla en cualquier dispositivo sin perder nada",
                "Categorías, reglas y movimientos viajan todos juntos en un solo archivo"
            )
        )
    )

    private lateinit var viewPager: ViewPager2
    private lateinit var llDots: LinearLayout
    private lateinit var btnSkip: Button
    private lateinit var btnNext: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewpager_onboarding)
        llDots    = findViewById(R.id.ll_onboarding_dots)
        btnSkip   = findViewById(R.id.btn_onboarding_skip)
        btnNext   = findViewById(R.id.btn_onboarding_next)

        // Back press (hardware o gesture) = equivale a "Omitir" → marca completado y cierra.
        // Sin esto el flag nunca se marca si el usuario usa el gesto de volver.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishOnboarding() }
        })

        viewPager.adapter = OnboardingPagerAdapter(pages)
        // Restaurar dots en rotación: si savedInstanceState != null, onPageSelected dispara solo.
        if (savedInstanceState == null) setupDots(0)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setupDots(position)
                val isLast = position == pages.lastIndex
                btnNext.text = if (isLast) "Empezar" else "Siguiente"
                btnSkip.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
            }
        })

        btnNext.setOnClickListener {
            val current = viewPager.currentItem
            if (current < pages.lastIndex) {
                viewPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }

        btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun finishOnboarding() {
        // Solo marcar como completado si NO estamos en modo "replay" (desde el menú)
        if (!intent.getBooleanExtra("force_show", false)) {
            markCompleted(this)
        }
        finish()
    }

    private fun setupDots(activePage: Int) {
        llDots.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.onboarding_dot_size)
        val margin = resources.getDimensionPixelSize(R.dimen.onboarding_dot_margin)

        pages.forEachIndexed { index, _ ->
            val dot = ImageView(this).apply {
                val params = LinearLayout.LayoutParams(size, size).also {
                    it.setMargins(margin, 0, margin, 0)
                }
                layoutParams = params
                setImageDrawable(
                    ContextCompat.getDrawable(
                        this@OnboardingActivity,
                        if (index == activePage) R.drawable.dot_active
                        else R.drawable.dot_inactive
                    )
                )
            }
            llDots.addView(dot)
        }
    }
}
