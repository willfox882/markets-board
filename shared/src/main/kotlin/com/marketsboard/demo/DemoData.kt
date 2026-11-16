package com.marketsboard.demo

import com.marketsboard.ui.markets.MarketsUiState
import com.marketsboard.ui.markets.QuoteRowUi
import com.marketsboard.ui.news.HeadlineUi
import com.marketsboard.ui.news.NewsUiState
import kotlin.math.sin

/**
 * Hard-coded placeholder content for the M0 shell, supplied to the shared UI by its callers (MainActivity, sandbox).
 * Nothing in shared UI or tests depends on this file: M1/M2 replace it with real providers, then it is deleted
 * together with DemoDataTest and the three call sites.
 */
object DemoData {
    /** Deterministic smooth intraday series from [start] to [end]. */
    private fun series(start: Double, end: Double, seed: Int, n: Int = 48): List<Float> =
        List(n) { i ->
            val t = i / (n - 1.0)
            val wave = sin(t * 6.0 + seed) * 0.4 + sin(t * 13.0 + seed * 1.7) * 0.12
            val noise = (end - start) * 0.9 * wave * (1.0 - t) + (start * 0.0012) * sin(t * 22.0 + seed) * (1.0 - t)
            (start + (end - start) * t + noise).toFloat()
        }

    private fun row(
        symbol: String, name: String, exchange: String, currency: String, price: Double, prevClose: Double, seed: Int,
    ) = QuoteRowUi(symbol, name, exchange, currency, price, prevClose, series(prevClose, price, seed))

    /** The owner's two default instruments (docs/PLAYBOOK.md section 1): +0.42% and -0.31%. */
    val markets = MarketsUiState(
        quotes = listOf(
            row("XEQT.TO", "iShares Core Equity ETF Portfolio", "TSX", "CAD", price = 41.87, prevClose = 41.695, seed = 3),
            row("MSFT", "Microsoft", "NASDAQ", "USD", price = 428.10, prevClose = 429.43, seed = 8),
        ),
        statusLine = "Demo data · updated 10:42",
        footnote = "Placeholder content. Live quotes and the watchlist editor arrive in a later update.",
    )

    val news = NewsUiState(
        headlines = listOf(
            HeadlineUi("Canada", "Bank of Canada holds its policy rate and signals patience on further cuts", 25 * 60L),
            HeadlineUi("Energy", "Crude edges lower as inventories build more than analysts expected", 3_900L),
            HeadlineUi("Markets", "TSX climbs on financials and materials as bond yields ease", 3 * 3_600L),
        ),
        statusLine = "Demo headlines",
        footnote = "Placeholder content. Real feeds arrive in a later update.",
    )
}
