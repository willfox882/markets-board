package com.marketsboard.data

import com.marketsboard.core.PollPolicy
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.store.FileJsonCache
import com.marketsboard.ui.markets.LiveKind
import com.marketsboard.ui.markets.UiMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staleness is market aware (audit finding 3) and the suspend age shift (audit finding 2). */
class StalenessTest {
    private val clock = FakeClock()
    private fun repoFor(state: String?, sessionStart: Long? = null): QuoteRepository {
        val p = ScriptedProvider("p") { s, r ->
            FetchResult.Ok(quoteOf(s, 100.0, clock.nowMs(), r).copy(marketState = state, sessionStartMs = sessionStart, sessionEndMs = sessionStart?.plus(3_600_000)))
        }
        return QuoteRepository(listOf(p), FileJsonCache(tempDir(), clock), clock, ioDispatcher = Dispatchers.Unconfined)
    }

    private fun statusAfter(repo: QuoteRepository, ms: Long): QuoteStatus {
        clock.advance(ms)
        repo.recomputeAges()
        return repo.stateOf("MSFT")!!.status
    }

    @Test fun aClosedMarketQuoteStaysFreshWellPastFiveMinutes() = runBlocking {
        val r = repoFor("CLOSED")
        r.refresh(listOf("MSFT"))
        assertEquals(QuoteStatus.Fresh, statusAfter(r, 5 * 60_000L + 1))
        assertEquals(QuoteStatus.Fresh, statusAfter(r, 60 * 60_000L)) // 65 min: still inside 2.5 x 30 min
        assertTrue(statusAfter(r, 11 * 60_000L) is QuoteStatus.Stale) // 76 min
    }

    @Test fun aRegularMarketQuoteGoesStaleAfterFiveMinutes() = runBlocking {
        val r = repoFor("REGULAR")
        r.refresh(listOf("MSFT"))
        assertEquals(QuoteStatus.Fresh, statusAfter(r, 5 * 60_000L))
        assertTrue(statusAfter(r, 1) is QuoteStatus.Stale)
    }

    @Test fun preAndPostMarketAllowTwelveAndAHalfMinutes() = runBlocking {
        val r = repoFor("PRE")
        r.refresh(listOf("MSFT"))
        assertEquals(QuoteStatus.Fresh, statusAfter(r, 12 * 60_000L))
        assertTrue(statusAfter(r, 60_000L) is QuoteStatus.Stale)
    }

    @Test fun theHeaderNeverSaysDelayedForAHealthyClosedMarket() = runBlocking {
        val r = repoFor("CLOSED")
        r.refresh(listOf("MSFT"))
        clock.advance(6 * 60_000L)
        r.recomputeAges()
        val item = WatchItem("MSFT", "Microsoft", "USD", "NASDAQ")
        val h = UiMapper.header(listOf(item), r.states.value, clock.nowMs())
        assertEquals(LiveKind.Closed, h.kind)
    }

    @Test fun ageShiftMakesFreshQuotesStaleAndShortensCooldowns() = runBlocking {
        val r = repoFor("REGULAR")
        r.refresh(listOf("MSFT"))
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
        r.ageShift(20 * 60_000L)
        val st = r.stateOf("MSFT")!!.status
        assertTrue(st is QuoteStatus.Stale)
        assertEquals(20 * 60_000L, (st as QuoteStatus.Stale).ageMs)
    }

    @Test fun pollPolicyIntervals() {
        val live = quoteOf().copy(marketState = "REGULAR")
        assertEquals(PollPolicy.LIVE_MS, PollPolicy.nominalIntervalMs(live, 0))
        assertEquals(PollPolicy.EXTENDED_MS, PollPolicy.nominalIntervalMs(live.copy(marketState = "POST"), 0))
        assertEquals(PollPolicy.CLOSED_MS, PollPolicy.nominalIntervalMs(live.copy(marketState = "CLOSED"), 0))
        assertEquals(PollPolicy.LIVE_MS, PollPolicy.nominalIntervalMs(null, 0))
        // unknown state falls back to the session window, then to live
        val noState = live.copy(marketState = null, sessionStartMs = 1_000, sessionEndMs = 2_000)
        assertEquals(PollPolicy.LIVE_MS, PollPolicy.nominalIntervalMs(noState, 1_500))
        assertEquals(PollPolicy.CLOSED_MS, PollPolicy.nominalIntervalMs(noState, 5_000))
        assertEquals(PollPolicy.LIVE_MS, PollPolicy.nominalIntervalMs(live.copy(marketState = null), 5_000))
        // closed with the session opening in 10 minutes: next poll at the open, not 30 min
        val closed = live.copy(marketState = "CLOSED", sessionStartMs = 600_000, sessionEndMs = 9_000_000)
        assertEquals(600_000L, PollPolicy.nextIntervalMs(closed, 0, 0))
        assertEquals(PollPolicy.CLOSED_MS, PollPolicy.nextIntervalMs(closed.copy(sessionStartMs = 99_999_999_999), 0, 0))
        assertEquals(PollPolicy.LIVE_MS, PollPolicy.nextIntervalMs(closed.copy(sessionStartMs = 1_000), 0, 0)) // opens in 1 s, still at least a minute after the attempt
    }
}
