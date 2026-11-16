package com.marketsboard.data.repo

import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote

sealed interface QuoteStatus {
    /** Fetched recently enough to be shown as live. */
    data object Fresh : QuoteStatus

    /** The last good quote is old or the latest refresh failed. [ageMs] is time since the last SUCCESS. */
    data class Stale(val ageMs: Long) : QuoteStatus

    /** First load, no quote to show yet. */
    data object Loading : QuoteStatus

    /** No quote at all and the last attempt failed. */
    data class Error(val kind: ErrorKind) : QuoteStatus
}

/**
 * What the UI needs for one symbol. [quote] is the last good quote and is kept across failures; [lastSuccessMs] is
 * the device-clock time of that success (or of the cache write it was restored from).
 */
data class QuoteState(
    val quote: Quote?,
    val status: QuoteStatus,
    val lastSuccessMs: Long? = null,
    val lastError: FetchResult.Err? = null,
)

/** Tunables of [QuoteRepository]. Defaults suit a parked head unit that polls about once a minute. */
class RepoConfig(
    val maxConcurrent: Int = 4,
    /** Older than this since the last success: shown as Stale. */
    val staleAfterMs: (com.marketsboard.data.model.ChartRange) -> Long = { r ->
        when (r) {
            com.marketsboard.data.model.ChartRange.D1 -> 5 * 60_000L
            com.marketsboard.data.model.ChartRange.D5 -> 15 * 60_000L
            else -> 6 * 3_600_000L
        }
    },
    /** A non-forced refresh is skipped when the quote is Fresh and younger than this. */
    val minRefetchMs: (com.marketsboard.data.model.ChartRange) -> Long = { r ->
        when (r) {
            com.marketsboard.data.model.ChartRange.D1 -> 30_000L
            com.marketsboard.data.model.ChartRange.D5 -> 2 * 60_000L
            else -> 30 * 60_000L
        }
    },
    /** Cache entries older than this are discarded instead of being shown as Stale. */
    val maxStaleMs: Long = 7L * 24 * 3_600_000,
    val backoffBaseMs: Long = 30_000,
    val backoffCapMs: Long = 15 * 60_000L,
)
