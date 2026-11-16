package com.marketsboard.ui.markets

import com.marketsboard.data.model.ChartRange

/** What a row says about its data. Colour: amber for everything except [Fresh] and [Loading]. */
sealed interface RowStatus {
    /** First load: the row shows skeleton placeholders where numbers will go. */
    data object Loading : RowStatus

    data object Fresh : RowStatus

    /** The last good quote is old. [ageText] is "5 min ago" (blank when the age cannot be trusted). */
    data class Stale(val ageText: String) : RowStatus

    /** The latest refresh failed. Numbers (if any) are the last good ones, shown dimmed. [ageText] as for [Stale]. */
    data class Error(val reason: String, val ageText: String = "") : RowStatus
}

/**
 * One watchlist row as the UI needs it. Change and percent are derived from [price] and [prevClose] here and nowhere
 * else, so the displayed numbers, the sign and the colour can never disagree. A null [price] means "no number yet"
 * (loading, or an error with nothing held); a null [prevClose] means "no change available" and renders as a dim "--".
 */
data class QuoteRowUi(
    val symbol: String,
    val name: String,
    /** Short exchange label ("TSX", "NASDAQ"). */
    val exchange: String,
    /** ISO code of the instrument's native currency (GBX stays GBX); shown as-is, never converted. */
    val currency: String,
    val price: Double?,
    val prevClose: Double?,
    val series: List<Float>,
    val decimals: Int = 2,
    val status: RowStatus = RowStatus.Fresh,
) {
    val change: Double get() = if (price != null && prevClose != null) price - prevClose else Double.NaN
    val changePct: Double
        get() = if (price != null && prevClose != null && prevClose.isFinite() && prevClose != 0.0) (price - prevClose) / prevClose * 100.0 else Double.NaN

    /** Numbers are the last known ones but not current: draw them dimmed. */
    val dimmed: Boolean get() = status is RowStatus.Error
}

enum class LiveKind { None, Loading, Live, Closed, Delayed, Offline }

/** Header status: [label] next to a dot (green live, grey closed or loading, amber delayed or offline). */
data class HeaderUi(val kind: LiveKind = LiveKind.None, val label: String = "", val updated: String = "")

enum class ChartLoad { Loading, Ready, Error }

data class DetailStat(val label: String, val value: String, val unit: String = "")

data class DetailUi(
    val symbol: String,
    val name: String,
    val exchange: String,
    val currency: String,
    val decimals: Int,
    val price: Double?,
    /** Reference close of the selected range (previous session close for 1D). Null: no change line. */
    val refClose: Double?,
    val range: ChartRange,
    val series: List<Float>,
    /** Dashed baseline, only for 1D. */
    val baseline: Float?,
    val chart: ChartLoad,
    val chartError: String = "",
    val status: RowStatus,
    val stats: List<DetailStat>,
) {
    val change: Double get() = if (price != null && refClose != null) price - refClose else Double.NaN
    val changePct: Double get() = if (price != null && refClose != null && refClose != 0.0) (price - refClose) / refClose * 100.0 else Double.NaN
    val rangeLabel: String
        get() = when (range) {
            ChartRange.D1 -> "today"
            ChartRange.D5 -> "5 days"
            ChartRange.M1 -> "1 month"
            ChartRange.M3 -> "3 months"
            ChartRange.Y1 -> "1 year"
        }
}

data class SearchRowUi(val symbol: String, val name: String, val exchange: String, val type: String, val added: Boolean)

data class AddMessage(val text: String, val warn: Boolean, val addAnywaySymbol: String? = null)

/** The add / replace sheet. [replacing] is the symbol being swapped out, null when adding. */
data class AddUi(
    val replacing: String? = null,
    val replacingName: String = "",
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SearchRowUi> = emptyList(),
    /** "No matches" or why search failed; blank otherwise. */
    val searchNote: String = "",
    val suggestions: List<SearchRowUi> = emptyList(),
    val busy: Boolean = false,
    val message: AddMessage? = null,
)

data class ConfirmRemoveUi(val symbol: String, val name: String)

data class RenameUi(val symbol: String, val current: String, val original: String)

data class MarketsUiState(
    val quotes: List<QuoteRowUi>,
    val header: HeaderUi = HeaderUi(),
    /** The device date or time looks wrong: show the thin banner. */
    val clockSkew: Boolean = false,
    /** A network refresh is running (the header shows "Refreshing" instead of the Refresh action). */
    val refreshing: Boolean = false,
    val editMode: Boolean = false,
    /** Plain notice about the list itself (read-only file, failed save); blank when fine. */
    val notice: String = "",
    val detail: DetailUi? = null,
    val add: AddUi? = null,
    val confirmRemove: ConfirmRemoveUi? = null,
    val rename: RenameUi? = null,
) {
    /** True when "back" has something to close (dialog, sheet, detail, edit mode). */
    val canGoBack: Boolean get() = rename != null || confirmRemove != null || add != null || detail != null || editMode
}

/** Every user action of the Markets tab. The presenter implements it; [None] makes stateless screens trivially testable. */
interface MarketsActions {
    fun onBack() {}
    fun onRefresh() {}
    fun onRetry(symbol: String) {}
    fun onOpen(symbol: String) {}
    fun onCloseDetail() {}
    fun onSelectRange(range: ChartRange) {}
    fun onRetryRange() {}
    fun onToggleEdit() {}
    fun onMove(symbol: String, delta: Int) {}
    fun onAskRename(symbol: String) {}
    fun onRenameChange(text: String) {}
    fun onRenameConfirm() {}
    fun onRenameCancel() {}
    fun onAskRemove(symbol: String) {}
    fun onConfirmRemove() {}
    fun onCancelRemove() {}
    fun onAddOpen() {}
    fun onReplaceOpen(symbol: String) {}
    fun onAddClose() {}
    fun onQueryChange(text: String) {}
    fun onPickResult(symbol: String) {}
    fun onSubmitTyped() {}
    fun onAddAnyway() {}

    companion object { val None: MarketsActions = object : MarketsActions {} }
}
