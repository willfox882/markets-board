package com.marketsboard.core

import com.marketsboard.data.model.Quote

/**
 * How often a symbol is worth polling, from what the provider said about its market. Shared by the refresh
 * controller (when to fetch) and the repository (when a quote counts as stale), so a closed market is never shown as
 * "delayed" just because nobody polled it for a few minutes. Pure Kotlin.
 *
 * - REGULAR, unknown or no quote yet: every [LIVE_MS] (60 s).
 * - PRE and POST: every [EXTENDED_MS] (5 min), thin trading does not justify more.
 * - Closed (CLOSED, PREPRE, POSTPOST, or outside the regular session window): the next regular session start when
 *   the provider's `currentTradingPeriod` says it is ahead and sooner, else [CLOSED_MS] (30 min).
 */
object PollPolicy {
    const val LIVE_MS = 60_000L
    const val EXTENDED_MS = 5 * 60_000L
    const val CLOSED_MS = 30 * 60_000L

    enum class Phase { Live, Extended, Closed }

    fun phase(q: Quote?, wallNowMs: Long): Phase {
        if (q == null) return Phase.Live
        val ms = q.marketState?.trim()?.uppercase()
        if (!ms.isNullOrEmpty()) {
            return when (ms) {
                "REGULAR" -> Phase.Live
                "PRE", "POST" -> Phase.Extended
                else -> Phase.Closed
            }
        }
        val s = q.sessionStartMs
        val e = q.sessionEndMs
        if (s != null && e != null && e > s) return if (wallNowMs in s..e) Phase.Live else Phase.Closed
        return Phase.Live
    }

    /** The regular poll interval for the phase, ignoring the session-start shortcut (also the basis of staleness). */
    fun nominalIntervalMs(q: Quote?, wallNowMs: Long): Long = when (phase(q, wallNowMs)) {
        Phase.Live -> LIVE_MS
        Phase.Extended -> EXTENDED_MS
        Phase.Closed -> CLOSED_MS
    }

    /**
     * Milliseconds after the last completed attempt at which the symbol is next due. Closed markets wait 30 min, or
     * until the next session start when that is sooner (never less than one minute after the attempt).
     */
    fun nextIntervalMs(q: Quote?, wallNowMs: Long, sinceLastMs: Long): Long {
        val nominal = nominalIntervalMs(q, wallNowMs)
        if (phase(q, wallNowMs) != Phase.Closed) return nominal
        val start = q?.sessionStartMs ?: return nominal
        val untilStart = start - wallNowMs
        // The session opened since the last attempt: due now. Opened earlier: the session ended, back to the nominal wait.
        if (untilStart <= 0) return if (-untilStart < sinceLastMs) sinceLastMs else nominal
        // untilStart is measured now; the attempt was sinceLastMs ago.
        return minOf(nominal, maxOf(untilStart + sinceLastMs, LIVE_MS))
    }
}
