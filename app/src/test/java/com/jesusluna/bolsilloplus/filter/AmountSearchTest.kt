package com.jesusluna.bolsilloplus.filter

import com.jesusluna.bolsilloplus.ui.main.parseAmountQuery
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * Tests para la función parseAmountQuery y la lógica de búsqueda por importe.
 *
 * Regla de matching: abs(txAmount) == abs(parsedQuery) para aceptar "50" tanto contra
 * -50.0 (gasto) como contra +50.0 (ingreso). Si el usuario teclea "-50" también coincide.
 *
 * Grupos:
 *  A – Parseo de cadenas
 *  B – Comparación de importe (abs matching)
 *  C – Casos límite / falsas alarmas
 */
class AmountSearchTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun amountMatches(rawQuery: String, txAmount: Double): Boolean {
        val parsed = parseAmountQuery(rawQuery) ?: return false
        return String.format(Locale.ROOT, "%.2f", kotlin.math.abs(txAmount)) ==
               String.format(Locale.ROOT, "%.2f", kotlin.math.abs(parsed))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO A: Parseo de cadenas
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun A01_parse_integer() =
        assertEquals(50.0, parseAmountQuery("50")!!, 0.001)

    @Test fun A02_parse_comma_decimal() =
        assertEquals(50.5, parseAmountQuery("50,5")!!, 0.001)

    @Test fun A03_parse_dot_decimal() =
        assertEquals(50.50, parseAmountQuery("50.50")!!, 0.001)

    @Test fun A04_parse_negative() =
        assertEquals(-50.0, parseAmountQuery("-50")!!, 0.001)

    @Test fun A05_parse_zero() =
        assertEquals(0.0, parseAmountQuery("0")!!, 0.001)

    @Test fun A06_parse_leading_space() =
        assertEquals(50.0, parseAmountQuery("  50  ")!!, 0.001)

    @Test fun A07_parse_comma_replaced_by_dot() =
        assertEquals(1234.56, parseAmountQuery("1234,56")!!, 0.001)

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO B: Comparación de importe (abs matching)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun B01_positive_query_matches_negative_tx() =
        assertTrue("'50' debe coincidir con -50.0", amountMatches("50", -50.0))

    @Test fun B02_positive_query_matches_positive_tx() =
        assertTrue("'50' debe coincidir con +50.0", amountMatches("50", 50.0))

    @Test fun B03_negative_query_matches_negative_tx() =
        assertTrue("'-50' debe coincidir con -50.0", amountMatches("-50", -50.0))

    @Test fun B04_decimal_match() =
        assertTrue("'19,99' debe coincidir con -19.99", amountMatches("19,99", -19.99))

    @Test fun B05_no_match_different_amount() =
        assertFalse("'50' NO debe coincidir con -51.0", amountMatches("50", -51.0))

    @Test fun B06_no_match_close_but_different_decimal() =
        assertFalse("'50,5' NO debe coincidir con -50.55", amountMatches("50,5", -50.55))

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO C: Casos límite — no deben parsear como número
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun C01_text_returns_null() =
        assertNull(parseAmountQuery("netflix"))

    @Test fun C02_empty_returns_null() =
        assertNull(parseAmountQuery(""))

    @Test fun C03_mixed_alphanumeric_returns_null() =
        assertNull(parseAmountQuery("50abc"))

    @Test fun C04_year_like_string_returns_null() =
        // "2024" como entero se parece a un año, pero sí matchea número;
        // está bien porque el usuario puede buscar por importe 2024 si quiere.
        // Lo que no debe ocurrir es que cadenas texto generen falsos positivos.
        assertNotNull("'2024' es un número válido", parseAmountQuery("2024"))

    @Test fun C05_two_dots_returns_null() =
        assertNull(parseAmountQuery("50.50.00"))

    @Test fun C06_blank_spaces_returns_null() =
        assertNull(parseAmountQuery("   "))
}
