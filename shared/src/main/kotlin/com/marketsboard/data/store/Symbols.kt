package com.marketsboard.data.store

import java.util.Locale

/** Symbol rules shared by the watchlist and the editor. Yahoo format: `XEQT.TO`, `^GSPTSE`, `USDCAD=X`, `BTC-CAD`. */
object Symbols {
    const val MAX_LENGTH = 15
    private val VALID = Regex("^[A-Z0-9.^=-]{1,$MAX_LENGTH}$")

    /** Trimmed, upper-cased symbol, or null when it contains disallowed characters or has a bad length. */
    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
        return s.takeIf { VALID.matches(it) }
    }
}
