package com.marketsboard.core

import com.marketsboard.data.model.Quote
import com.marketsboard.data.repo.QuoteState

/**
 * Market-hours awareness for the refresh policy, derived from what the provider told us (no calendars, no holidays).
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

    /**
     * Refresh class of a symbol: true = poll at the live rate. No quote yet (loading, error) or a failed last
     * refresh count as live, so a recovering connection is noticed within a minute whatever the market is doing.
     * A merely old quote of a closed market does not (it would otherwise be polled every minute all weekend).
     */
    fun wantsLiveRate(state: QuoteState?, wallNowMs: Long): Boolean {
        if (state == null) return true
        val q = state.quote ?: return true
        if (state.lastError != null) return true
        return isActive(q, wallNowMs)
    }
}
