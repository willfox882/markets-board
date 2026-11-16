package com.marketsboard.refresh

import com.marketsboard.FakeQuotesSource
import com.marketsboard.VirtualClock
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import com.marketsboard.data.quoteOf
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.freshState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The refresh policy of the Markets tab, driven by virtual time and a clock that follows it. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshControllerTest {
    private class Rig(scope: TestScope, symbols: List<String> = listOf("A", "B")) {
        val clock = VirtualClock(scope.testScheduler)
        val quotes = FakeQuotesSource(clock)
        val symbols = MutableStateFlow(symbols)
        val active = MutableStateFlow(true)
        var resumes = 0
        val controller = RefreshController(scope.backgroundScope, quotes, this.symbols, active, clock, onResume = { resumes++ })

        fun open(sym: String, state: String = "REGULAR"): QuoteState = freshState(q(sym, state), clock.monoMs())
        fun q(sym: String, state: String?): Quote = quoteOf(sym).copy(marketState = state)
    }

    private suspend fun TestScope.tick(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ---- start, intervals -------------------------------------------------------------------------------------

    @Test fun refreshesEverythingImmediatelyOnStartWithoutForce() = runTest {
        val r = Rig(this)
        runCurrent()
        assertEquals(1, r.quotes.calls.size)
        assertEquals(listOf("A", "B"), r.quotes.calls[0].symbols)
        assertEquals(false, r.quotes.calls[0].force)
        assertEquals(1, r.resumes)
    }

    @Test fun liveMarketsAreRefreshedEverySixtySeconds() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(59_999)
        assertEquals(1, r.quotes.calls.size)
        tick(1)
        assertEquals(2, r.quotes.calls.size)
        assertEquals(60_000L, r.quotes.calls[1].atMs)
        tick(60_000)
        assertEquals(3, r.quotes.calls.size)
    }

    @Test fun preAndPostMarketCountAsLive() = runTest {
        for (state in listOf("PRE", "POST")) {
            val r = Rig(this, listOf("A"))
            r.quotes.onRefresh = { s, _ -> r.open(s, state) }
            runCurrent()
            tick(60_000)
            assertEquals(state, 2, r.quotes.calls.size)
        }
    }

    @Test fun allClosedMarketsAreRefreshedEveryFiveMinutes() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s, "CLOSED") }
        runCurrent()
        assertEquals(1, r.quotes.calls.size)
        tick(299_000)
        assertEquals(1, r.quotes.calls.size)
        tick(1_000)
        assertEquals(2, r.quotes.calls.size)
        assertEquals(300_000L, r.quotes.calls[1].atMs)
        tick(300_000)
        assertEquals(3, r.quotes.calls.size)
    }

    @Test fun prePreAndPostPostCountAsClosed() = runTest {
        val r = Rig(this, listOf("A"))
        r.quotes.onRefresh = { s, _ -> r.open(s, "POSTPOST") }
        runCurrent()
        tick(240_000)
        assertEquals(1, r.quotes.calls.size)
    }

    @Test fun mixedMarketsOnlyTheLiveOnesFollowTheMinuteRhythm() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s, if (s == "A") "REGULAR" else "CLOSED") }
        runCurrent()
        tick(60_000)
        assertEquals(listOf("A"), r.quotes.calls.last().symbols)
        tick(240_000)
        // at 300 s the closed one is due too
        assertEquals(listOf("A", "B"), r.quotes.calls.last().symbols)
    }

    @Test fun symbolsWithoutAQuoteOrWithAFailedRefreshAreRetriedEveryMinuteEvenWhenClosed() = runTest {
        val r = Rig(this, listOf("A"))
        r.quotes.putAll(mapOf("A" to QuoteState(null, QuoteStatus.Error(ErrorKind.Network), null, FetchResult.Err(ErrorKind.Network, "x"))))
        runCurrent()
        tick(60_000)
        tick(60_000)
        assertEquals(3, r.quotes.calls.size)
        val closedButFailed = freshState(r.q("A", "CLOSED")).copy(lastError = FetchResult.Err(ErrorKind.Http(503), "x"))
        r.quotes.put("A", closedButFailed)
        tick(60_000)
        assertEquals(4, r.quotes.calls.size)
    }

    // ---- pause and resume -------------------------------------------------------------------------------------

    @Test fun nothingHappensWhileTheAppIsInactive() = runTest {
        val r = Rig(this)
        r.active.value = false
        runCurrent()
        tick(30 * 60_000)
        assertTrue(r.quotes.calls.isEmpty())
        assertTrue(r.quotes.agesCalls.isEmpty())
        assertEquals(0, r.resumes)
    }

    @Test fun resumeRefreshesImmediatelyWhenTheLastAttemptIsOldEnough() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(10_000)
        r.active.value = false
        tick(10 * 60_000)
        assertEquals(1, r.quotes.calls.size) // the pause stopped the loop
        r.active.value = true
        runCurrent()
        assertEquals(2, r.quotes.calls.size)
        assertEquals(2, r.resumes)
    }

    @Test fun quickPauseAndResumeNeverRepollsFasterThanSixtySeconds() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(10_000)
        r.active.value = false
        runCurrent()
        tick(5_000)
        r.active.value = true
        runCurrent()
        assertEquals(1, r.quotes.calls.size)
        // the timer restarts from the resume: next pass 60 s after it, which is past the 60 s gap
        tick(60_000)
        assertEquals(2, r.quotes.calls.size)
    }

    @Test fun aCancelledFetchIsNotCountedAsAnAttempt() = runTest {
        val r = Rig(this, listOf("A"))
        r.quotes.refreshDelayMs = 30_000
        runCurrent()
        assertEquals(1, r.quotes.calls.size) // started, still running
        tick(10_000)
        r.active.value = false
        runCurrent()
        r.active.value = true
        runCurrent()
        assertEquals(2, r.quotes.calls.size) // retried at once: the first one never completed
    }

    // ---- watchlist changes ------------------------------------------------------------------------------------

    @Test fun aNewSymbolIsFetchedAtOnceAndOthersAreLeftAlone() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(30_000)
        r.symbols.value = listOf("A", "B", "C")
        runCurrent()
        assertEquals(2, r.quotes.calls.size)
        assertEquals(listOf("C"), r.quotes.calls[1].symbols)
    }

    @Test fun removingASymbolStopsRefreshingIt() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        r.symbols.value = listOf("A")
        runCurrent()
        tick(60_000)
        assertEquals(listOf("A"), r.quotes.calls.last().symbols)
    }

    @Test fun anEmptyWatchlistDoesNothing() = runTest {
        val r = Rig(this, emptyList())
        runCurrent()
        tick(10 * 60_000)
        assertTrue(r.quotes.calls.isEmpty())
    }

    @Test fun reorderingTheListDoesNotFetch() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(5_000)
        r.symbols.value = listOf("B", "A")
        runCurrent()
        assertEquals(1, r.quotes.calls.size)
    }

    // ---- cooldown, safety, ages -------------------------------------------------------------------------------

    @Test fun noAutomaticPassWhileTheRepositoryCoolsDown() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        r.quotes.cooldownMs = 5 * 60_000
        tick(10 * 60_000)
        assertEquals(1, r.quotes.calls.size)
        r.quotes.cooldownMs = 0
        tick(60_000)
        assertEquals(2, r.quotes.calls.size)
    }

    @Test fun startingInsideACooldownDoesNotFetchUntilItEnds() = runTest {
        val r = Rig(this, listOf("A"))
        r.quotes.cooldownMs = 90_000
        runCurrent()
        assertTrue(r.quotes.calls.isEmpty())
        r.quotes.cooldownMs = 0
        tick(60_000)
        assertEquals(1, r.quotes.calls.size)
    }

    @Test fun noSymbolIsEverFetchedMoreOftenThanEverySixtySeconds() = runTest {
        val r = Rig(this, listOf("A", "B"))
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        // churn: add and remove a symbol, pause and resume, every few seconds for ten minutes
        var n = 0
        while (currentTime < 10 * 60_000) {
            tick(7_000)
            n++
            when (n % 4) {
                0 -> r.symbols.value = listOf("A", "B", "C")
                1 -> r.symbols.value = listOf("A", "B")
                2 -> r.active.value = false
                3 -> r.active.value = true
            }
            runCurrent()
        }
        for (sym in listOf("A", "B", "C")) {
            val times = r.quotes.calls.filter { sym in it.symbols }.map { it.atMs }
            times.zipWithNext().forEach { (a, b) -> assertTrue("$sym polled at $a and $b", b - a >= 60_000) }
        }
    }

    @Test fun anExceptionFromTheRepositoryDoesNotKillTheLoop() = runTest {
        val r = Rig(this, listOf("A"))
        r.quotes.failWith = IllegalStateException("boom")
        runCurrent()
        tick(60_000)
        r.quotes.failWith = null
        tick(60_000)
        assertEquals(3, r.quotes.calls.size)
    }

    @Test fun agesAreRecomputedEveryFifteenSecondsWhileActive() = runTest {
        val r = Rig(this)
        runCurrent()
        assertEquals(listOf(0L), r.quotes.agesCalls)
        tick(15_000)
        tick(15_000)
        assertEquals(listOf(0L, 15_000L, 30_000L), r.quotes.agesCalls)
        r.active.value = false
        runCurrent()
        tick(60_000)
        assertEquals(3, r.quotes.agesCalls.size)
        r.active.value = true
        runCurrent()
        assertEquals(4, r.quotes.agesCalls.size)
    }

    // ---- manual -----------------------------------------------------------------------------------------------

    @Test fun manualRefreshIsForcedAndCoversTheWholeList() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(5_000)
        r.controller.refreshAll()
        runCurrent()
        assertEquals(2, r.quotes.calls.size)
        assertEquals(true, r.quotes.calls[1].force)
        assertEquals(listOf("A", "B"), r.quotes.calls[1].symbols)
    }

    @Test fun retryRefreshesOneSymbolForced() = runTest {
        val r = Rig(this)
        runCurrent()
        r.controller.refresh("B")
        runCurrent()
        assertEquals(listOf("B"), r.quotes.calls.last().symbols)
        assertEquals(true, r.quotes.calls.last().force)
    }

    @Test fun timeScaleSpeedsUpTheDelaysForTheSandbox() = runTest {
        val clock = VirtualClock(testScheduler)
        val q = FakeQuotesSource(clock)
        RefreshController(backgroundScope, q, MutableStateFlow(listOf("A")), MutableStateFlow(true), clock, RefreshConfig(timeScale = { 10.0 }))
        runCurrent()
        // delays are divided by 10, so the tick comes after 6 s; the clock (here unscaled) says only 6 s passed,
        // so the 60 s gap rule still holds and no second fetch happens: speed-up must scale the clock as well.
        tick(6_000)
        assertEquals(1, q.calls.size)
    }
}
