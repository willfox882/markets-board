package com.marketsboard.data.provider

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Instrument
import com.marketsboard.data.model.Quote

/** A source of quotes and symbol search. Implementations never throw (except coroutine cancellation). */
interface MarketDataProvider {
    val id: String

    /** False when this provider cannot serve [symbol] at all (e.g. no symbol mapping), so callers skip it. */
    fun supports(symbol: String): Boolean = true

    suspend fun quote(symbol: String, range: ChartRange): FetchResult<Quote>

    suspend fun search(query: String): FetchResult<List<Instrument>>
}
