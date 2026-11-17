package com.marketsboard.data.repo

import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote

sealed interface QuoteStatus {
    /** Fetched recently enough to be shown as live. */
    data object Fresh : QuoteStatus

    /**
     * The last good quote is old or the latest refresh failed. [ageMs] is time since the last SUCCESS measured on the
     * monotonic clock; when the age cannot be trusted (cache written "in the future" by a wrong clock) [ageKnown] is
     * false and [ageMs] is 0.
     */
    data class Stale(val ageMs: Long, val ageKnown: Boolean = true) : QuoteStatus

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
    /**
     * True when the device clock is behind the provider's own timestamps (or behind the cache write time), i.e. the
     * date or time is wrong. The UI should say "check the date and time" instead of trusting any wall-clock text.
     */
    val clockSkew: Boolean = false,
    /** Repository internal: monotonic time of the last success, null when unknown. Not for UI use. */
    val anchorMono: Long? = null,
)

/**
 * What the diagnostics screen shows for one quote provider. Ages are monotonic; [lastError] is plain words. The counts are
 * since the process started. NotFound (a mistyped symbol) is not counted as a provider failure.
 */
data class ProviderHealth(
    val id: String,
    val lastOkAgeMs: Long? = null,
    val lastError: String? = null,
    val lastErrorAgeMs: Long? = null,
    val failureStreak: Int = 0,
    val cooldownRemainingMs: Long = 0,
    val okCount: Int = 0,
    val errorCount: Int = 0,
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
    /** Device clock more than this behind a provider timestamp counts as skew. */
    val skewToleranceMs: Long = 120_000,
    /** A fallback quote may be at most this much older than the held quote (and newer than now by the same). */
    val fallbackSanityMs: Long = 24 * 3_600_000L,
    /** Upper bound for honouring a Retry-After header (exponential backoff has its own, lower, cap). */
    val maxRetryAfterMs: Long = 3_600_000,
    val backoffBaseMs: Long = 30_000,
    val backoffCapMs: Long = 15 * 60_000L,
)
