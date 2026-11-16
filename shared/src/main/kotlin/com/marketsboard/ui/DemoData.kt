package com.marketsboard.ui

import kotlin.math.sin

/**
 * Hard-coded placeholder content for the M0 shell. Everything in this file is throwaway:
 * M1/M2 replace it with real models and providers, then this file is deleted.
 */
object DemoData {
    data class Quote(
        val symbol: String,
        val name: String,
        val exchange: String,
        /** ISO code of the instrument's native currency; shown as-is, never converted. */
        val currency: String,
        val price: Double,
        val changePct: Double,
        val series: List<Float>,
        val prevClose: Float,
    ) {
        val change: Double get() = price - prevClose
    }

    data class Headline(val category: String, val title: String, val ageSec: Long)

    /** Deterministic wiggly intraday series that starts at [start] and ends at [end]. */
    private fun series(start: Double, end: Double, seed: Int, n: Int = 48): List<Float> =
        List(n) { i ->
            val t = i / (n - 1.0)
            val wave = sin(t * 6.0 + seed) * 0.4 + sin(t * 13.0 + seed * 1.7) * 0.12
            val noise = (end - start) * 0.9 * wave * (1.0 - t) + (start * 0.0012) * sin(t * 22.0 + seed) * (1.0 - t)
            (start + (end - start) * t + noise).toFloat()
        }

    private fun quote(
        symbol: String, name: String, exchange: String, currency: String, price: Double, pct: Double, seed: Int,
    ): Quote {
        val prev = price / (1.0 + pct / 100.0)
        return Quote(symbol, name, exchange, currency, price, pct, series(prev, price, seed), prev.toFloat())
    }

    /** The owner's two default instruments (see docs/PLAYBOOK.md section 1). */
    val watchlist = listOf(
        quote("XEQT.TO", "iShares Core Equity ETF Portfolio", "TSX", "CAD", 41.87, 0.42, seed = 3),
        quote("MSFT", "Microsoft", "NASDAQ", "USD", 428.10, -0.31, seed = 8),
    )

    val updated = "Demo data · updated 10:42"

    val headlines = listOf(
        Headline("Canada", "Bank of Canada holds its policy rate and signals patience on further cuts", 25 * 60L),
        Headline("Energy", "Crude edges lower as inventories build more than analysts expected", 3_900L),
        Headline("Markets", "TSX climbs on financials and materials as bond yields ease", 3 * 3_600L),
    )
}
