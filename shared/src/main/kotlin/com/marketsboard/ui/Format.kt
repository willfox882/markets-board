package com.marketsboard.ui

import java.util.Locale

/** Typographic minus sign (U+2212), used for negative changes instead of a hyphen. */
const val MINUS = "−"

/** Placeholder for a value that is missing or not finite. */
const val NO_VALUE = "--"

/** Grouped price with fixed decimals in en-CA/en-US style: 25412.329 -> "25,412.33". */
fun formatPrice(value: Double, decimals: Int = 2): String {
    if (!value.isFinite()) return NO_VALUE
    return String.format(Locale.US, "%,.${decimals}f", value).replace("-", MINUS)
}

/** Signed value for changes: "+106.45", "−12.30", and a bare "0.00" when it rounds to zero. */
fun formatSigned(value: Double, decimals: Int = 2): String {
    if (!value.isFinite()) return NO_VALUE
    val body = String.format(Locale.US, "%,.${decimals}f", kotlin.math.abs(value))
    val zero = body.all { it == '0' || it == '.' || it == ',' }
    return when {
        zero -> body
        value > 0 -> "+$body"
        else -> "$MINUS$body"
    }
}

/** Signed percent: "+0.42%", "−1.10%", "0.00%". */
fun formatPercent(pct: Double, decimals: Int = 2): String =
    if (!pct.isFinite()) NO_VALUE else formatSigned(pct, decimals) + "%"

/** Direction of a change after rounding to [decimals], so a displayed "0.00%" never gets a colour. */
fun changeSign(pct: Double, decimals: Int = 2): Int {
    if (!pct.isFinite()) return 0
    val scale = Math.pow(10.0, decimals.toDouble())
    val r = Math.round(pct * scale)
    return if (r > 0) 1 else if (r < 0) -1 else 0
}

/** Age label for freshness badges: "just now", "5 min ago", "2 h ago", "3 d ago". */
fun formatTimeAgo(ageSec: Long): String = when {
    ageSec < 0 -> "just now"
    ageSec < 60 -> "just now"
    ageSec < 3600 -> "${ageSec / 60} min ago"
    ageSec < 86_400 -> "${ageSec / 3600} h ago"
    else -> "${ageSec / 86_400} d ago"
}
