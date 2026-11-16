package com.marketsboard.data.store

import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.provider.MarketDataProvider
import kotlinx.coroutines.flow.StateFlow

/** The editable watchlist as the UI layer sees it. [WatchlistStore] is the real (file backed) implementation. */
interface Watchlist {
    val items: StateFlow<List<WatchItem>>

    /** True when the file on disk is from a newer app version or unreadable: edits are refused. */
    val readOnly: Boolean

    /** Non-null while the last write failed; the in-memory list is still current. */
    val persistError: StateFlow<String?>

    fun remove(symbol: String): EditResult
    fun move(from: Int, to: Int): EditResult
    fun rename(symbol: String, displayName: String?): EditResult
    fun replace(old: String, new: WatchItem): EditResult
    fun resetToDefaults(): EditResult
    suspend fun validateAndAdd(rawSymbol: String, provider: MarketDataProvider, displayName: String? = null): AddOutcome
    fun addUnverified(rawSymbol: String, displayName: String? = null): AddOutcome

    /** Retries a failed write now. True when nothing is pending. */
    fun flushIfDirty(): Boolean
}
