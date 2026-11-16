package com.marketsboard.ui.markets

/**
 * One watchlist row as the UI needs it. Change and percent are derived from [price] and [prevClose] here and nowhere
 * else, so the displayed numbers, the sign and the colour can never disagree.
 */
data class QuoteRowUi(
    val symbol: String,
    val name: String,
    val exchange: String,
    /** ISO code of the instrument's native currency; shown as-is, never converted. */
    val currency: String,
    val price: Double,
    val prevClose: Double,
    val series: List<Float>,
    val decimals: Int = 2,
) {
    val change: Double get() = price - prevClose
    val changePct: Double get() = if (prevClose.isFinite() && prevClose != 0.0) change / prevClose * 100.0 else Double.NaN
}

data class MarketsUiState(
    val quotes: List<QuoteRowUi>,
    /** Freshness line under the title, e.g. "Updated 2 min ago". */
    val statusLine: String = "",
    /** Small note at the bottom of the list. */
    val footnote: String = "",
)
