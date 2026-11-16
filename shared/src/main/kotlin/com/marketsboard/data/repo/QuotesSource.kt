package com.marketsboard.data.repo

import com.marketsboard.data.model.ChartRange
import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI layer, presenter and refresh controller need from the quote repository. [QuoteRepository] is the real
 * implementation; tests and the sandbox can substitute fakes.
 */
interface QuotesSource {
    /** State per [QuoteRepository.keyFor] key (plain symbol for 1D). */
    val states: StateFlow<Map<String, QuoteState>>

    /** True while any network refresh is running. */
    val refreshing: StateFlow<Boolean>

    fun stateOf(symbol: String, range: ChartRange = ChartRange.D1): QuoteState?

    /** Milliseconds until some provider becomes usable again, or 0 when at least one is usable now. */
    fun cooldownRemainingMs(): Long

    suspend fun hydrate(symbols: List<String>, range: ChartRange = ChartRange.D1)

    suspend fun refresh(symbols: List<String>, range: ChartRange = ChartRange.D1, force: Boolean = false)

    /** Recomputes Fresh/Stale for every held quote without touching the network. */
    fun recomputeAges()

    suspend fun forget(symbol: String)
}
