package com.jesusluna.bolsilloplus.ui.onboarding

import androidx.annotation.DrawableRes

/**
 * Modelo de una página del tutorial.
 * Para añadir una página nueva basta con añadir un elemento a [PAGES].
 */
data class OnboardingPage(
    val title: String,
    val subtitle: String,
    @DrawableRes val imageRes: Int,
    val bullets: List<String>
)
