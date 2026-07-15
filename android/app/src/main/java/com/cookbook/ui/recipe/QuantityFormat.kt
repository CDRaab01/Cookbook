package com.cookbook.ui.recipe

import kotlin.math.abs
import kotlin.math.floor

// (fraction value, glyph) — the common culinary fractions, rendered as unicode vulgar fractions.
private val CULINARY_FRACTIONS: List<Pair<Double, String>> = listOf(
    0.0 to "",
    1.0 / 8 to "⅛", // ⅛
    1.0 / 6 to "⅙", // ⅙
    1.0 / 4 to "¼", // ¼
    1.0 / 3 to "⅓", // ⅓
    3.0 / 8 to "⅜", // ⅜
    1.0 / 2 to "½", // ½
    5.0 / 8 to "⅝", // ⅝
    2.0 / 3 to "⅔", // ⅔
    3.0 / 4 to "¾", // ¾
    5.0 / 6 to "⅚", // ⅚
    7.0 / 8 to "⅞", // ⅞
    1.0 to "",
)

/**
 * Render a quantity the way a cook reads it — "1½", "¾", "2" — not "1.5" / "0.75" / "0.6667".
 *
 * The fractional part snaps to the nearest common culinary fraction (halves/thirds/quarters/
 * sixths/eighths); a remainder that rounds up to a whole rolls into the whole. Scaling a recipe
 * produces ugly decimals (a ⅓-cup ingredient × 2 = 0.6667), so this snapping is exactly what makes
 * a scaled recipe readable — the roadmap's "1½ cups, not 1.5000".
 */
fun humanQuantity(quantity: Double): String {
    if (quantity < 0) return trimDecimal(quantity)
    val whole = floor(quantity + 1e-9).toInt()
    val frac = quantity - whole
    val (value, glyph) = CULINARY_FRACTIONS.minByOrNull { abs(it.first - frac) }!!
    return when {
        value >= 1.0 -> (whole + 1).toString() // snapped up to the next whole
        glyph.isEmpty() -> whole.toString() // snapped down to a whole
        whole == 0 -> glyph // just "¾"
        else -> "$whole$glyph" // "1½"
    }
}

private fun trimDecimal(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
