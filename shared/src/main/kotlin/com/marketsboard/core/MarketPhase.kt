package com.marketsboard.core

import com.marketsboard.data.model.Quote

/**
 * Market-open check for the header status (the polling rhythm itself is in [PollPolicy]), derived from what the provider told us (no calendars, no holidays).
 * Pure Kotlin.
 */
object MarketPhase {
    private val ACTIVE = setOf("REGULAR", "PRE", "POST")

    /**
     * True when this instrument is trading or about to (REGULAR, PRE, POST). Decision order: the provider's
     * marketState; else the regular session window against [wallNowMs]; else unknown, which counts as active
     * (the safe side: poll at the live rate). PREPRE, POSTPOST and CLOSED are closed.
     */
    fun isActive(q: Quote?, wallNowMs: Long): Boolean {
        if (q == null) return true
        val ms = q.marketState?.trim()?.uppercase()
        if (ms != null && ms.isNotEmpty()) return ms in ACTIVE
        val s = q.sessionStartMs
        val e = q.sessionEndMs
        if (s != null && e != null && e > s) return wallNowMs in s..e
        return true
    }
}
