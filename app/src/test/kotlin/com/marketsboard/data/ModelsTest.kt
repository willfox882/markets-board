package com.marketsboard.data

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.QuoteValidator
import com.marketsboard.data.model.SeriesPoint
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.store.QuoteCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private fun validated(q: com.marketsboard.data.model.Quote) = QuoteValidator.validate(q)

    @Test fun rejectsNaNInfiniteAndNonPositivePrice() {
        for (p in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0, 1e12)) {
            val r = validated(quoteOf(price = p))
            assertTrue("price $p must be rejected", r is FetchResult.Err && r.kind == ErrorKind.Parse)
        }
    }

    @Test fun dropsBadSeriesPointsKeepingOrder() {
        val series = listOf(
            SeriesPoint(1, 10.0), SeriesPoint(2, Double.NaN), SeriesPoint(3, 0.0), SeriesPoint(4, -2.0),
            SeriesPoint(5, 12.0), SeriesPoint(6, Double.POSITIVE_INFINITY), SeriesPoint(0, 5.0), SeriesPoint(7, 11.0),
        )
        val q = (validated(quoteOf(series = series)) as FetchResult.Ok).value
        assertEquals(listOf(1L, 5L, 7L), q.series.map { it.tMs })
    }

    @Test fun invalidOptionalNumbersBecomeNull() {
        val q = (validated(quoteOf().copy(prevClose = Double.NaN, dayHigh = -1.0, dayLow = 0.0)) as FetchResult.Ok).value
        assertNull(q.prevClose); assertNull(q.dayHigh); assertNull(q.dayLow)
    }

    @Test fun missingProviderTimeIsRejected() {
        assertTrue(validated(quoteOf(asOf = 0L)) is FetchResult.Err)
    }

    @Test fun blankSymbolIsRejected() {
        assertTrue(validated(quoteOf(symbol = " ")) is FetchResult.Err)
    }

    @Test fun seriesLengthIsCapped() {
        val big = (1..3000).map { SeriesPoint(it.toLong(), 10.0) }
        val q = (validated(quoteOf(series = big)) as FetchResult.Ok).value
        assertEquals(QuoteValidator.MAX_SERIES, q.series.size)
        assertEquals(3000L, q.series.last().tMs)
    }

    @Test fun rangesMapToYahooParameters() {
        assertEquals("1d" to "5m", ChartRange.D1.yahooRange to ChartRange.D1.yahooInterval)
        assertEquals("5d" to "15m", ChartRange.D5.yahooRange to ChartRange.D5.yahooInterval)
        assertEquals("1mo" to "1d", ChartRange.M1.yahooRange to ChartRange.M1.yahooInterval)
        assertEquals("3mo" to "1d", ChartRange.M3.yahooRange to ChartRange.M3.yahooInterval)
        assertEquals("1y" to "1wk", ChartRange.Y1.yahooRange to ChartRange.Y1.yahooInterval)
    }

    @Test fun watchItemShownNamePrefersDisplayName() {
        assertEquals("Mine", WatchItem("A", "Full", "USD", "X", "Mine").shownName)
        assertEquals("Full", WatchItem("A", "Full", "USD", "X", " ").shownName)
        assertEquals("Full", WatchItem("A", "Full", "USD", "X").shownName)
    }

    @Test fun errorLabelsAreStable() {
        assertEquals("http_503", ErrorKind.Http(503).label)
        assertEquals("rate_limited", ErrorKind.RateLimited(5).label)
    }

    @Test fun quoteCodecRoundTrips() {
        val q = quoteOf(symbol = "XEQT.TO", price = 41.87, currency = "CAD").copy(instrumentType = "ETF")
        assertEquals(q, QuoteCodec.decode(QuoteCodec.encode(q)))
        val bare = q.copy(prevClose = null, dayHigh = null, dayLow = null, marketState = null, instrumentType = null, series = emptyList())
        assertEquals(bare, QuoteCodec.decode(QuoteCodec.encode(bare)))
    }

    @Test fun quoteCodecRejectsGarbageAndBadValues() {
        assertNull(QuoteCodec.decode("not json"))
        assertNull(QuoteCodec.decode("{}"))
        assertNull(QuoteCodec.decode("""{"v":2}"""))
        val bad = QuoteCodec.encode(quoteOf()).replace("\"price\":100.0", "\"price\":-4.0")
        assertNull(QuoteCodec.decode(bad))
    }
}
