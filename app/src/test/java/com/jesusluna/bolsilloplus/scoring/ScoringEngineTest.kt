package com.jesusluna.bolsilloplus.scoring

import com.jesusluna.bolsilloplus.data.db.entity.CategoryRuleEntity
import com.jesusluna.bolsilloplus.data.db.entity.MatchType
import com.jesusluna.bolsilloplus.util.DescriptionNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitarios del motor de scoring/matching de [DescriptionNormalizer].
 *
 * NO requieren Android ni Room. Usan [FakeCategoryRuleDao].
 *
 * Fórmula de score efectivo:
 *   typeBase × 10_000 + patternLength × 100 + priority
 *   EXACT=3 | CONTAINS=2 | CONTAINS_WORD=1 | REGEX=0
 *
 * Grupos:
 *  A - normalize()
 *  B - suggestWord()
 *  C - Matching EXACT
 *  D - Matching CONTAINS
 *  E - Matching CONTAINS_WORD (límites de palabra)
 *  F - Matching REGEX
 *  G - Precedencia entre tipos (typeBase)
 *  H - Precedencia por longitud de patrón (mismo tipo)
 *  I - Tiebreak por prioridad (mismo tipo + misma longitud)
 *  J - BUG documentado: bump +10 insuficiente
 *  K - candidateRuleCount / findAllMatchingRules
 *  L - Escenario completo "Amazon Prime" del usuario
 */
class ScoringEngineTest {

    private lateinit var dao: FakeCategoryRuleDao

    // Categorías ficticias
    private val CAT_COMPRAS        = 1L
    private val CAT_SUSCRIPCIONES  = 2L
    private val CAT_ALIMENTACION   = 3L

    @Before
    fun setUp() {
        dao = FakeCategoryRuleDao()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO A: normalize()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun A01_normalize_lowercases() {
        assertEquals("amazon prime", DescriptionNormalizer.normalize("AMAZON PRIME"))
    }

    @Test fun A02_normalize_trims_leading_trailing() {
        assertEquals("amazon prime", DescriptionNormalizer.normalize("  amazon prime  "))
    }

    @Test fun A03_normalize_collapses_multiple_spaces() {
        assertEquals("amazon prime video", DescriptionNormalizer.normalize("amazon  prime   video"))
    }

    @Test fun A04_normalize_empty_stays_empty() {
        assertEquals("", DescriptionNormalizer.normalize(""))
    }

    @Test fun A05_normalize_single_word_unchanged() {
        assertEquals("netflix", DescriptionNormalizer.normalize("Netflix"))
    }

    @Test fun A06_normalize_is_idempotent() {
        val once   = DescriptionNormalizer.normalize("AMAZON  PRIME  ")
        val twice  = DescriptionNormalizer.normalize(once)
        assertEquals("normalize debe ser idempotente", once, twice)
    }

    @Test fun A07_normalize_preserves_numbers() {
        assertEquals("pago 123", DescriptionNormalizer.normalize("PAGO 123"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO B: suggestWord()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun B01_suggestWord_returns_longest_word_over3() {
        // "amazon"=6, "prime"=5 → "amazon"
        assertEquals("amazon", DescriptionNormalizer.suggestWord("amazon prime"))
    }

    @Test fun B02_suggestWord_filters_words_3chars_or_less() {
        // "de"=2 (≤3, filtrado), "amazon"=6 (>3)
        assertEquals("amazon", DescriptionNormalizer.suggestWord("de amazon"))
    }

    @Test fun B03_suggestWord_all_short_returns_first() {
        // Todas ≤3 → devuelve la primera
        assertEquals("yo", DescriptionNormalizer.suggestWord("yo no se"))
    }

    @Test fun B04_suggestWord_picks_longest_among_multiple() {
        // "mercadona"=9 > "compra"=6 > "super"=5
        assertEquals("mercadona", DescriptionNormalizer.suggestWord("compra super mercadona"))
    }

    @Test fun B05_suggestWord_single_long_word() {
        assertEquals("netflix", DescriptionNormalizer.suggestWord("netflix"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO C: Matching EXACT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun C01_exact_matches_same_string() = runBlocking {
        dao.insertRule(rule("amazon prime", MatchType.EXACT, CAT_SUSCRIPCIONES, 100))
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertNotNull(match)
        assertEquals(CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    @Test fun C02_exact_does_not_match_substring() = runBlocking {
        dao.insertRule(rule("amazon prime", MatchType.EXACT, CAT_SUSCRIPCIONES, 100))
        // "amazon" ≠ "amazon prime" en EXACT
        assertNull(DescriptionNormalizer.findMatch("amazon", dao))
    }

    @Test fun C03_exact_does_not_match_superset() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.EXACT, CAT_COMPRAS, 100))
        assertNull(DescriptionNormalizer.findMatch("amazon prime", dao))
    }

    @Test fun C04_exact_disabled_rule_not_matched() = runBlocking {
        dao.insertRule(rule("amazon prime", MatchType.EXACT, CAT_SUSCRIPCIONES, 100, enabled = false))
        assertNull(
            "Una regla EXACT desactivada no debe coincidir",
            DescriptionNormalizer.findMatch("amazon prime", dao)
        )
    }

    @Test fun C05_exact_returns_ruleId() = runBlocking {
        val id = dao.insertRule(rule("amazon prime", MatchType.EXACT, CAT_SUSCRIPCIONES, 100))
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals("findMatch debe devolver el ruleId correcto", id, match!!.ruleId)
    }

    @Test fun C06_exact_tiebreak_higher_priority_wins() = runBlocking {
        // Dos EXACT para el mismo patrón (puede ocurrir si se inserta desde distintos flujos)
        dao.insertRule(rule("netflix", MatchType.EXACT, CAT_COMPRAS, 50))
        dao.insertRule(rule("netflix", MatchType.EXACT, CAT_SUSCRIPCIONES, 80))
        val match = DescriptionNormalizer.findMatch("netflix", dao)
        assertEquals("Entre dos EXACT, gana la de mayor prioridad", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO D: Matching CONTAINS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun D01_contains_matches_substring() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 50))
        assertNotNull(DescriptionNormalizer.findMatch("amazon prime", dao))
    }

    @Test fun D02_contains_matches_full_string() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 50))
        assertNotNull(DescriptionNormalizer.findMatch("amazon", dao))
    }

    @Test fun D03_contains_does_not_match_unrelated() = runBlocking {
        dao.insertRule(rule("netflix", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 50))
        assertNull(DescriptionNormalizer.findMatch("amazon prime", dao))
    }

    @Test fun D04_contains_disabled_rule_not_matched() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 50, enabled = false))
        assertNull(
            "Regla CONTAINS desactivada no coincide",
            DescriptionNormalizer.findMatch("amazon prime", dao)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO E: Matching CONTAINS_WORD (límites de palabra)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun E01_containsWord_matches_word_at_start() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        assertNotNull(DescriptionNormalizer.findMatch("amazon prime", dao))
    }

    @Test fun E02_containsWord_matches_word_at_end() = runBlocking {
        dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))
        assertNotNull(DescriptionNormalizer.findMatch("amazon prime", dao))
    }

    @Test fun E03_containsWord_matches_word_alone() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        assertNotNull(DescriptionNormalizer.findMatch("amazon", dao))
    }

    @Test fun E04_containsWord_does_not_match_partial_prefix() = runBlocking {
        // "ama" NO es palabra completa dentro de "amazon"
        dao.insertRule(rule("ama", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        assertNull(
            "CONTAINS_WORD 'ama' no debe coincidir con 'amazon prime' (no es palabra completa)",
            DescriptionNormalizer.findMatch("amazon prime", dao)
        )
    }

    @Test fun E05_containsWord_does_not_match_embedded_without_boundary() = runBlocking {
        // "amazon" dentro de "superamazon" sin espacio → no coincide
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        assertNull(
            "CONTAINS_WORD no debe coincidir cuando la palabra está incrustada sin espacio",
            DescriptionNormalizer.findMatch("superamazon prime", dao)
        )
    }

    @Test fun E06_containsWord_matches_word_in_middle() = runBlocking {
        dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))
        assertNotNull(DescriptionNormalizer.findMatch("amazon prime video", dao))
    }

    @Test fun E07_containsWord_disabled_rule_not_matched() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45, enabled = false))
        assertNull(
            "Regla CONTAINS_WORD desactivada no coincide",
            DescriptionNormalizer.findMatch("amazon prime", dao)
        )
    }

    @Test fun E08_containsWord_multiword_pattern_matches() = runBlocking {
        // "amazon prime" como patrón CONTAINS_WORD — \bamazon prime\b
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))
        assertNotNull(
            "CONTAINS_WORD con frase 'amazon prime' debe coincidir en 'amazon prime video'",
            DescriptionNormalizer.findMatch("amazon prime video", dao)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO F: Matching REGEX
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun F01_regex_matches_simple_pattern() = runBlocking {
        dao.insertRule(rule("amaz.*prime", MatchType.REGEX, CAT_SUSCRIPCIONES, 10))
        assertNotNull(DescriptionNormalizer.findMatch("amazon prime", dao))
    }

    @Test fun F02_regex_malformed_no_exception_no_match() = runBlocking {
        dao.insertRule(rule("[invalid(regex", MatchType.REGEX, CAT_SUSCRIPCIONES, 10))
        // No debe lanzar excepción y no debe coincidir
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertNull("Regex malformada no coincide y no lanza excepción", match)
    }

    @Test fun F03_regex_disabled_rule_not_matched() = runBlocking {
        dao.insertRule(rule("amaz.*", MatchType.REGEX, CAT_COMPRAS, 10, enabled = false))
        assertNull(
            "Regla REGEX desactivada no coincide",
            DescriptionNormalizer.findMatch("amazon prime", dao)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO G: Precedencia entre tipos de matchType
    // Fórmula: typeBase × 10_000 determina el orden básico
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun G01_EXACT_beats_CONTAINS_samePattern() = runBlocking {
        // EXACT "amazon" → score = 3×10000 + 6×100 + 100 = 30700
        // CONTAINS "amazon" → score = 2×10000 + 6×100 + 50 = 20650
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 50))
        dao.insertRule(rule("amazon", MatchType.EXACT, CAT_SUSCRIPCIONES, 100))
        val match = DescriptionNormalizer.findMatch("amazon", dao)
        assertEquals("EXACT debe ganar sobre CONTAINS con mismo patrón", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    @Test fun G02_EXACT_beats_CONTAINS_WORD() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        dao.insertRule(rule("amazon", MatchType.EXACT, CAT_SUSCRIPCIONES, 100))
        val match = DescriptionNormalizer.findMatch("amazon", dao)
        assertEquals("EXACT debe ganar sobre CONTAINS_WORD", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    @Test fun G03_EXACT_beats_REGEX() = runBlocking {
        dao.insertRule(rule("amaz.*", MatchType.REGEX, CAT_COMPRAS, 10))
        dao.insertRule(rule("amazon prime", MatchType.EXACT, CAT_SUSCRIPCIONES, 100))
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals("EXACT debe ganar sobre REGEX", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    @Test fun G04_CONTAINS_beats_CONTAINS_WORD_samePattern() = runBlocking {
        // CONTAINS "amazon" (typeBase=2) score = 20650
        // CONTAINS_WORD "amazon" (typeBase=1) score = 10645
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 50))
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals("CONTAINS debe ganar sobre CONTAINS_WORD con mismo patrón", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    @Test fun G05_CONTAINS_beats_REGEX() = runBlocking {
        dao.insertRule(rule("amaz.*", MatchType.REGEX, CAT_COMPRAS, 10))
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 50))
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals("CONTAINS debe ganar sobre REGEX", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    @Test fun G06_CONTAINS_WORD_beats_REGEX() = runBlocking {
        dao.insertRule(rule("amaz.*", MatchType.REGEX, CAT_COMPRAS, 10))
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals("CONTAINS_WORD debe ganar sobre REGEX", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO H: Precedencia por longitud de patrón (mismo tipo)
    // patternLength × 100 → patrón más específico gana automáticamente
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun H01_CONTAINS_longerPattern_beats_shorter_samePriority() = runBlocking {
        // "amazon prime" (len=12) → score = 20000+1200+50 = 21250
        // "amazon"       (len=6)  → score = 20000+600+50  = 20650
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 50))
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 50))
        val match = DescriptionNormalizer.findMatch("amazon prime video", dao)
        assertEquals(
            "Patrón CONTAINS más largo (más específico) debe ganar con misma prioridad",
            CAT_SUSCRIPCIONES, match!!.categoryId
        )
    }

    @Test fun H02_CONTAINS_WORD_longerPattern_beats_shorter() = runBlocking {
        // "amazon prime" (len=12) CONTAINS_WORD → 10000+1200+45 = 11245
        // "amazon"       (len=6)  CONTAINS_WORD → 10000+600+45  = 10645
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals(
            "Patrón CONTAINS_WORD más largo debe ganar con misma prioridad",
            CAT_SUSCRIPCIONES, match!!.categoryId
        )
    }

    @Test fun H03_length_difference_600pts_needs_priority_bump_over600_to_overcome() = runBlocking {
        // "amazon prime"(len=12) vs "amazon"(len=6) → diferencia = 600 pts
        // Para que "amazon" gane, necesita priority que supere esa diferencia
        // priority "amazon" = 50 + 601 = 651 → score = 20000+600+651 = 21251 > 21250 ✓
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS, CAT_COMPRAS, 50))
        val idShorter = dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 50))

        dao.updatePriority(idShorter, 651) // supera la diferencia de 600

        val match = DescriptionNormalizer.findMatch("amazon prime video", dao)
        assertEquals(
            "Con prioridad suficiente (+601 > diferencia de 600 pts) el patrón corto puede ganar",
            CAT_SUSCRIPCIONES, match!!.categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO I: Tiebreak por prioridad (mismo tipo + misma longitud)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun I01_tiebreak_higherPriority_wins_CONTAINS_WORD() = runBlocking {
        // "prime"(len=5) CONTAINS_WORD: A=priority45, B=priority55
        // A score = 10545, B score = 10555 → B gana
        dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 55))
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals("Mayor prioridad gana en empate tipo+longitud", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    @Test fun I02_tiebreak_higherPriority_wins_CONTAINS() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 50))
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 60))
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals("Mayor prioridad CONTAINS gana en empate", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    @Test fun I03_priorityBump10_resolves_tiebreak_sameLengthSameType() = runBlocking {
        // A: CONTAINS_WORD "prime" priority=50 → score=10550 (winner inicial)
        // B: CONTAINS_WORD "prime" priority=45 → score=10545 (loser)
        // Bump B +10 → priority=55 → score=10555 → B debe ganar
        val idA = dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_COMPRAS, 50))
        val idB = dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))

        // Estado inicial: A gana
        assertEquals(CAT_COMPRAS, DescriptionNormalizer.findMatch("amazon prime", dao)!!.categoryId)

        // Bump B
        dao.updatePriority(idB, 55)

        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals(
            "Bump +10 debe funcionar cuando tipo y longitud son idénticos",
            CAT_SUSCRIPCIONES, match!!.categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO J: prioridad del usuario prevalece sobre tipo y longitud de patrón
    //
    // El sort es priority DESC → typeOrdinal DESC → patternLength DESC.
    // El usuario puede hacer ganar cualquier regla con solo darle una priority mayor.
    // Con prioridades iguales, el tipo actúa de desempate (EXACT > CONTAINS > …).
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * J01 — Un bump de +10 en prioridad permite a CONTAINS_WORD superar a CONTAINS.
     *
     * ANTES del fix (score monolítico): CONTAINS ganaba siempre por typeBase (brecha 10.000 pts).
     * DESPUÉS del fix (priority-first): priority=55 > priority=50 → CONTAINS_WORD gana.
     */
    @Test fun J01_priorityBump10_makes_CONTAINS_WORD_beat_CONTAINS() = runBlocking {
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS, CAT_COMPRAS, 50))
        val idWord = dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))

        dao.updatePriority(idWord, 55) // +10 → priority 55 supera priority 50

        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals(
            "J01: Con priority=55 > 50, CONTAINS_WORD debe superar a CONTAINS",
            CAT_SUSCRIPCIONES,
            match!!.categoryId
        )
    }

    /**
     * J02 — Un bump de +10 permite al patrón más corto superar al más largo.
     *
     * ANTES del fix: la longitud del patrón era imbatible dentro del mismo tipo.
     * DESPUÉS del fix: priority=60 > priority=50 → patrón corto gana.
     */
    @Test fun J02_priorityBump10_makes_shorter_CONTAINS_beat_longer() = runBlocking {
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS, CAT_COMPRAS, 50))
        val idShorter = dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 50))

        dao.updatePriority(idShorter, 60) // +10

        val match = DescriptionNormalizer.findMatch("amazon prime video", dao)
        assertEquals(
            "J02: Con priority=60 > 50, el patrón corto 'amazon' debe ganar a 'amazon prime'",
            CAT_SUSCRIPCIONES,
            match!!.categoryId
        )
    }

    /**
     * J03 — Prioridad mínima necesaria para superar a otra regla: solo priority+1.
     *
     * No hace falta un +10651 masivo. Cualquier valor por encima de la regla contraria basta.
     */
    @Test fun J03_minimalBumpNeeded_is_just_priorityPlusOne() = runBlocking {
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS, CAT_COMPRAS, 50))
        val idWord = dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))

        dao.updatePriority(idWord, 51) // +1 sobre la competidora

        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals(
            "J03: priority=51 supera a priority=50; solo hace falta 1 punto de diferencia",
            CAT_SUSCRIPCIONES,
            match!!.categoryId
        )
    }

    /**
     * J04 — Con misma prioridad, el tipo actúa de desempate (EXACT > CONTAINS).
     * El usuario que no toque prioridades ve el comportamiento por defecto esperado.
     */
    @Test fun J04_samePriority_typeOrdinal_breaks_tie_EXACT_beats_CONTAINS() = runBlocking {
        // Misma prioridad → tipo decide
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 100))
        dao.insertRule(rule("amazon", MatchType.EXACT, CAT_SUSCRIPCIONES, 100))

        val match = DescriptionNormalizer.findMatch("amazon", dao)
        assertEquals(
            "J04: Con misma priority, EXACT gana sobre CONTAINS por typeOrdinal",
            CAT_SUSCRIPCIONES,
            match!!.categoryId
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO K: candidateRuleCount / findAllMatchingRules
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun K01_noRules_returns_emptyList() = runBlocking {
        val rules = DescriptionNormalizer.findAllMatchingRules("amazon prime", dao)
        assertEquals(0, rules.size)
    }

    @Test fun K02_singleMatch_returns_listOf1() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 50))
        val rules = DescriptionNormalizer.findAllMatchingRules("amazon prime", dao)
        assertEquals(1, rules.size)
    }

    @Test fun K03_threeRules_allMatch_amazonPrime_returns3() = runBlocking {
        // Escenario del usuario
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 55))
        val rules = DescriptionNormalizer.findAllMatchingRules("amazon prime", dao)
        assertEquals("Las 3 reglas deben coincidir", 3, rules.size)
    }

    @Test fun K04_winner_is_first_in_sortedList() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 55))
        val rules = DescriptionNormalizer.findAllMatchingRules("amazon prime", dao)
        assertEquals("La ganadora debe ser la primera", MatchType.CONTAINS, rules.first().matchType)
        assertEquals("amazon prime", rules.first().normalizedPattern)
    }

    @Test fun K05_disabledRule_not_counted_as_candidate() = runBlocking {
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 50))
        dao.insertRule(rule("prime", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 50, enabled = false))
        val rules = DescriptionNormalizer.findAllMatchingRules("amazon prime", dao)
        assertEquals("Regla desactivada no se cuenta como candidata", 1, rules.size)
    }

    @Test fun K06_sortOrder_descending_by_score() = runBlocking {
        // Orden esperado: EXACT > CONTAINS > CONTAINS_WORD > REGEX
        dao.insertRule(rule("amaz.*", MatchType.REGEX, CAT_ALIMENTACION, 10))
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        dao.insertRule(rule("amazon", MatchType.CONTAINS, CAT_COMPRAS, 50))
        dao.insertRule(rule("amazon", MatchType.EXACT, CAT_SUSCRIPCIONES, 100))
        val rules = DescriptionNormalizer.findAllMatchingRules("amazon", dao)
        assertEquals(4, rules.size)
        assertEquals(MatchType.EXACT, rules[0].matchType)
        assertEquals(MatchType.CONTAINS, rules[1].matchType)
        assertEquals(MatchType.CONTAINS_WORD, rules[2].matchType)
        assertEquals(MatchType.REGEX, rules[3].matchType)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRUPO L: Escenario completo "Amazon Prime" del enunciado
    // ═══════════════════════════════════════════════════════════════════════════

    @Test fun L01_scenario_amazonPrime_CONTAINS_phrase_wins_over_words() = runBlocking {
        // Según el enunciado del usuario:
        // 1. CONTAINS_WORD "amazon"       → Compras Online
        // 2. CONTAINS_WORD "prime"        → Suscripciones
        // 3. CONTAINS "amazon prime"      → Suscripciones
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 55))

        val rules = DescriptionNormalizer.findAllMatchingRules("amazon prime", dao)

        // 1. 3 candidatos
        assertEquals("Deben coincidir 3 reglas", 3, rules.size)

        // 2. CONTAINS "amazon prime" gana (score máximo)
        assertEquals(MatchType.CONTAINS, rules.first().matchType)
        assertEquals("amazon prime", rules.first().normalizedPattern)

        // 3. Categoría ganadora = Suscripciones
        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals("CONTAINS 'amazon prime' asigna CAT_SUSCRIPCIONES", CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    @Test fun L02_scenario_amazon_only_when_no_phrase_rule() = runBlocking {
        // Sin regla "amazon prime", gana "amazon" CONTAINS_WORD (len=6 > "prime" len=5)
        dao.insertRule(rule("amazon", MatchType.CONTAINS_WORD, CAT_COMPRAS, 45))
        dao.insertRule(rule("prime", MatchType.CONTAINS_WORD, CAT_SUSCRIPCIONES, 45))

        val match = DescriptionNormalizer.findMatch("amazon prime", dao)
        assertEquals(
            "Sin regla de frase, CONTAINS_WORD 'amazon' (más largo) gana sobre 'prime'",
            CAT_COMPRAS, match!!.categoryId
        )
    }

    @Test fun L03_scenario_conceptNormalization_before_match() = runBlocking {
        // La normalización sucede antes del matching
        dao.insertRule(rule("amazon prime", MatchType.CONTAINS, CAT_SUSCRIPCIONES, 50))
        val normalized = DescriptionNormalizer.normalize("AMAZON  PRIME  ")
        val match = DescriptionNormalizer.findMatch(normalized, dao)
        assertNotNull("El concepto normalizado debe coincidir con el patrón normalizado", match)
        assertEquals(CAT_SUSCRIPCIONES, match!!.categoryId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun rule(
        pattern: String,
        type: MatchType,
        categoryId: Long,
        priority: Int,
        enabled: Boolean = true
    ) = CategoryRuleEntity(
        normalizedPattern = pattern,
        categoryId = categoryId,
        matchType = type,
        priority = priority,
        isEnabled = enabled
    )
}
