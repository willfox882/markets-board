package com.marketsboard.data

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.provider.MarketDataProvider
import com.marketsboard.data.repo.QuotesSource
import com.marketsboard.data.store.AddOutcome
import com.marketsboard.data.store.EditResult
import com.marketsboard.data.store.Symbols
import com.marketsboard.data.store.Watchlist
import com.marketsboard.data.store.WatchlistStore

/**
 * The editor's view of the data layer: a [Watchlist], the quotes behind it and the provider used for search and
 * validation. All removal and replacement goes through here so cache, state and series of a symbol that left the list
 * are dropped (`QuotesSource.forget`). Methods block briefly on file IO: call them from an IO dispatcher.
 */
class MarketsData(
    val watchlist: Watchlist,
    val quotes: QuotesSource,
    val provider: MarketDataProvider,
) {
    suspend fun remove(symbol: String): EditResult {
        val r = watchlist.remove(symbol)
        if (r is EditResult.Done) quotes.forget(symbol)
        return r
    }

    /** Swaps [old] for [new] keeping the position, and forgets the old symbol's data. */
    suspend fun replace(old: String, new: WatchItem): EditResult {
        val r = watchlist.replace(old, new)
        if (r is EditResult.Done && !old.trim().equals(new.symbol.trim(), ignoreCase = true)) quotes.forget(old)
        return r
    }

    /** Verifies [rawSymbol] with a quote fetch, then replaces [old] with it (same outcomes as `validateAndAdd`). */
    suspend fun validateAndReplace(old: String, rawSymbol: String): AddOutcome {
        val sym = Symbols.normalize(rawSymbol)
            ?: return AddOutcome.Invalid("Use letters, digits and . ^ = - only (max ${Symbols.MAX_LENGTH})")
        if (watchlist.readOnly) return AddOutcome.ReadOnly
        watchlist.items.value.firstOrNull { it.symbol == sym }?.let { return AddOutcome.Duplicate(it) }
        return when (val r = provider.quote(sym, ChartRange.D1)) {
            is FetchResult.Ok -> {
                val q = r.value
                swap(old, WatchItem(sym, q.name.ifBlank { sym }, q.currency, q.exchange))
            }
            is FetchResult.Err -> when (r.kind) {
                ErrorKind.NotFound -> AddOutcome.NotFound
                else -> AddOutcome.NetworkError(r.kind, r.message)
            }
        }
    }

    /** "Replace anyway" when offline: unknown currency and exchange until the first good quote. */
    suspend fun replaceUnverified(old: String, rawSymbol: String): AddOutcome {
        val sym = Symbols.normalize(rawSymbol) ?: return AddOutcome.Invalid("Invalid symbol")
        watchlist.items.value.firstOrNull { it.symbol == sym }?.let { return AddOutcome.Duplicate(it) }
        return swap(old, WatchItem(sym, sym, "", ""))
    }

    private suspend fun swap(old: String, item: WatchItem): AddOutcome = when (val r = replace(old, item)) {
        is EditResult.Done -> AddOutcome.Added(item, r.persisted)
        is EditResult.Duplicate -> AddOutcome.Duplicate(r.existing)
        is EditResult.Invalid -> AddOutcome.Invalid(r.reason)
        EditResult.LimitReached -> AddOutcome.LimitReached
        EditResult.ReadOnly -> AddOutcome.ReadOnly
        EditResult.NotFound -> AddOutcome.NotFound
    }

    /** Resets to the two defaults and forgets the data of everything that was dropped. */
    suspend fun resetToDefaults(): EditResult {
        val before = watchlist.items.value.map { it.symbol }
        val r = watchlist.resetToDefaults()
        if (r is EditResult.Done) {
            val keep = WatchlistStore.DEFAULTS.map { it.symbol }.toSet()
            before.filter { it !in keep }.forEach { quotes.forget(it) }
        }
        return r
    }
}
