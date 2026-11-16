package com.marketsboard.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Typographic minus sign (U+2212), used for negative changes instead of a hyphen. */
const val MINUS = "\u2212"

/** Placeholder for a value that is missing or not finite. */
const val NO_VALUE = "--"

/**
 * The single rounding step behind every displayed number: half-up on the shortest decimal form of [value].
 * Text, sign and colour are all derived from this one result, so they can never disagree.
 */
private fun rounded(value: Double, decimals: Int): BigDecimal =
    BigDecimal(value.toString()).setScale(decimals, RoundingMode.HALF_UP)

/** Grouped digits of a non-negative rounded value in en-US style. */
private fun grouped(v: BigDecimal, decimals: Int): String = String.format(Locale.US, "%,.${decimals}f", v.abs())

/** Grouped price with fixed decimals: 25412.329 -> "25,412.33". Negative values get a typographic minus, never "-0.00". */
fun formatPrice(value: Double, decimals: Int = 2): String {
    if (!value.isFinite()) return NO_VALUE
    val r = rounded(value, decimals)
    return (if (r.signum() < 0) MINUS else "") + grouped(r, decimals)
}

/** Signed value for changes: "+106.45", "−12.30", and a bare "0.00" when it rounds to zero. */
fun formatSigned(value: Double, decimals: Int = 2): String {
    if (!value.isFinite()) return NO_VALUE
    val r = rounded(value, decimals)
    return when (r.signum()) {
        0 -> grouped(r, decimals)
        1 -> "+" + grouped(r, decimals)
        else -> MINUS + grouped(r, decimals)
    }
}

/** Signed percent: "+0.42%", "−1.10%", "0.00%". */
fun formatPercent(pct: Double, decimals: Int = 2): String =
    if (!pct.isFinite()) NO_VALUE else formatSigned(pct, decimals) + "%"

/** Direction of a change as displayed (after the same rounding as [formatSigned]): 1, -1 or 0. */
fun changeSign(pct: Double, decimals: Int = 2): Int =
    if (!pct.isFinite()) 0 else rounded(pct, decimals).signum()

/** Age label for freshness badges: "just now", "5 min ago", "2 h ago", "3 d ago". */
fun formatTimeAgo(ageSec: Long): String = when {
    ageSec < 60 -> "just now"
    ageSec < 3600 -> "${ageSec / 60} min ago"
    ageSec < 86_400 -> "${ageSec / 3600} h ago"
    else -> "${ageSec / 86_400} d ago"
}
