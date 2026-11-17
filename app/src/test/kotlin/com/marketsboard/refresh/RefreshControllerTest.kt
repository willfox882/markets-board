package com.marketsboard.refresh

import com.marketsboard.FakeQuotesSource
import com.marketsboard.FakeNetwork
import com.marketsboard.SplitClock
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
import com.marketsboard.refresh.NetworkStatus

/** The refresh policy of the Markets tab, driven by virtual time and a clock that follows it. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshControllerTest {
    private class Rig(scope: TestScope, symbols: List<String> = listOf("A", "B"), splitClock: Boolean = false) {
        val clock: com.marketsboard.data.Clock = if (splitClock) SplitClock(scope.testScheduler) else VirtualClock(scope.testScheduler)
        val network = FakeNetwork()
        val quotes = FakeQuotesSource(clock)
        val symbols = MutableStateFlow(symbols)
        val active = MutableStateFlow(true)
        var resumes = 0
        val controller = RefreshController(scope.backgroundScope, quotes, this.symbols, active, clock, onResume = { resumes++ }, network = network)

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

    @Test fun preAndPostMarketArePolledEveryFiveMinutes() = runTest {
        for (state in listOf("PRE", "POST")) {
            val r = Rig(this, listOf("A"))
            r.quotes.onRefresh = { s, _ -> r.open(s, state) }
            runCurrent()
            tick(299_000)
            assertEquals(state, 1, r.quotes.calls.size)
            tick(1_000)
            assertEquals(state, 2, r.quotes.calls.size)
        }
    }

    @Test fun allClosedMarketsAreRefreshedEveryThirtyMinutes() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s, "CLOSED") }
        runCurrent()
        assertEquals(1, r.quotes.calls.size)
        tick(29 * 60_000L)
        assertEquals(1, r.quotes.calls.size)
        tick(60_000)
        assertEquals(2, r.quotes.calls.size)
        assertEquals(1_800_000L, r.quotes.calls[1].atMs)
        tick(1_800_000)
        assertEquals(3, r.quotes.calls.size)
    }

    @Test fun aClosedMarketIsPolledAtTheNextSessionStartWhenThatIsSooner() = runTest {
        val r = Rig(this, listOf("A"))
        val startWall = r.clock.nowMs() + 10 * 60_000 // opens in 10 minutes
        r.quotes.onRefresh = { s, _ -> freshState(r.q(s, "CLOSED").copy(sessionStartMs = startWall, sessionEndMs = startWall + 6 * 3_600_000)) }
        runCurrent()
        tick(9 * 60_000L)
        assertEquals(1, r.quotes.calls.size)
        tick(60_000)
        assertEquals(2, r.quotes.calls.size)
        assertEquals(600_000L, r.quotes.calls[1].atMs)
    }

    @Test fun prePreAndPostPostCountAsClosed() = runTest {
        val r = Rig(this, listOf("A"))
        r.quotes.onRefresh = { s, _ -> r.open(s, "POSTPOST") }
        runCurrent()
        tick(29 * 60_000L)
        assertEquals(1, r.quotes.calls.size)
    }

    @Test fun mixedMarketsOnlyTheLiveOnesFollowTheMinuteRhythm() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s, if (s == "A") "REGULAR" else "CLOSED") }
        runCurrent()
        tick(60_000)
        assertEquals(listOf("A"), r.quotes.calls.last().symbols)
        tick(29 * 60_000L)
        // at 30 min the closed one is due too
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
        assertEquals(listOf(0L, 0L), r.quotes.agesCalls) // the age loop and the first pass both recompute
        tick(15_000)
        tick(15_000)
        assertEquals(listOf(0L, 0L, 15_000L, 30_000L), r.quotes.agesCalls)
        r.active.value = false
        runCurrent()
        tick(60_000)
        assertEquals(4, r.quotes.agesCalls.size)
        r.active.value = true
        runCurrent()
        assertEquals(6, r.quotes.agesCalls.size)
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

    // ---- audit round: reorder, network, suspend, debounce -----------------------------------------------------

    @Test fun aPureReorderDoesNotRestartTheTimer() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(30_000)
        r.symbols.value = listOf("B", "A")
        runCurrent()
        tick(30_000)
        // the tick stays at 60 s from the start; a restart would have pushed it to 90 s
        assertEquals(2, r.quotes.calls.size)
        assertEquals(60_000L, r.quotes.calls[1].atMs)
        assertEquals(1, r.resumes)
    }

    @Test fun networkBackRetriesBrokenSymbolsAtOnceIgnoringTheMinimumGap() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(10_000)
        r.quotes.put("B", QuoteState(null, QuoteStatus.Error(ErrorKind.Network), null, FetchResult.Err(ErrorKind.Network, "x")))
        r.network.back()
        tick(1_000) // the settle time that collapses a burst of signals into one pass
        assertEquals(2, r.quotes.calls.size)
        assertEquals(listOf("B"), r.quotes.calls[1].symbols) // only the broken one, 10 s after the last attempt
        assertEquals(11_000L, r.quotes.calls[1].atMs)
        r.network.back() // everything healthy now: nothing to do
        tick(1_000)
        assertEquals(2, r.quotes.calls.size)
    }

    @Test fun networkBackStillHonoursTheCooldownAndThePause() = runTest {
        val r = Rig(this)
        runCurrent()
        r.quotes.put("A", QuoteState(null, QuoteStatus.Error(ErrorKind.Network), null, FetchResult.Err(ErrorKind.Network, "x")))
        r.quotes.cooldownMs = 60_000
        r.network.back()
        tick(1_000)
        assertEquals(1, r.quotes.calls.size)
        r.quotes.cooldownMs = 0
        r.active.value = false
        runCurrent()
        r.network.back()
        tick(1_000)
        assertEquals(1, r.quotes.calls.size)
    }

    @Test fun noAutomaticPassWhileTheNetworkIsOfflineThenTheReturnRefreshesStaleQuotes() = runTest {
        val r = Rig(this)
        r.network.statusFlow.value = NetworkStatus.Offline
        runCurrent()
        tick(10 * 60_000L)
        assertEquals("no fetch attempts without a network", 0, r.quotes.calls.size)
        r.quotes.put("A", QuoteState(com.marketsboard.data.quoteOf("A"), QuoteStatus.Stale(5 * 60_000L), 0L, null, false, 0L))
        r.network.statusFlow.value = NetworkStatus.Online
        r.network.back()
        tick(900)
        assertEquals(0, r.quotes.calls.size)
        tick(200)
        assertEquals(1, r.quotes.calls.size)
        assertTrue("A is stale and B has nothing: both are retried", r.quotes.calls[0].symbols.containsAll(listOf("A", "B")))
    }

    @Test fun aBurstOfSignalsIsOnePass() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(10_000)
        r.quotes.put("B", QuoteState(null, QuoteStatus.Error(ErrorKind.Network), null, FetchResult.Err(ErrorKind.Network, "x")))
        repeat(5) { r.network.back(); tick(100) }
        tick(1_500)
        assertEquals("one initial pass plus one answer to the whole burst", 2, r.quotes.calls.size)
    }

    @Test fun resumeAfterTheDeviceSleptAgesTheQuotesAndRefreshesAtOnce() = runTest {
        val r = Rig(this, splitClock = true)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(10_000)
        r.active.value = false
        runCurrent()
        // the head unit sleeps for 20 minutes: wall time moves, the monotonic clock and delay() do not
        (r.clock as SplitClock).sleep(20 * 60_000L)
        tick(1_000)
        r.active.value = true
        runCurrent()
        assertEquals(listOf(20 * 60_000L), r.quotes.shifts)
        assertEquals(2, r.quotes.calls.size) // refreshed at once although the monotonic gap was only 11 s
    }

    @Test fun anAwayPeriodOverAMinuteIgnoresTheMinimumGapEvenOnAClockThatCountsSleep() = runTest {
        val r = Rig(this)
        r.quotes.onRefresh = { s, _ -> r.open(s) }
        runCurrent()
        tick(1_000)
        r.active.value = false
        runCurrent()
        tick(2 * 60_000L)
        r.active.value = true
        runCurrent()
        assertEquals(2, r.quotes.calls.size)
        assertTrue(r.quotes.shifts.isEmpty()) // monotonic time already included the whole absence
    }

    @Test fun agesAreRecomputedBeforeTheFirstPassOnResume() = runTest {
        val r = Rig(this)
        runCurrent()
        val before = r.quotes.agesCalls.size
        r.active.value = false
        runCurrent()
        r.active.value = true
        runCurrent()
        assertTrue(r.quotes.agesCalls.size >= before + 1)
    }

    @Test fun manualRefreshPressesCloserThanFiveSecondsAreIgnored() = runTest {
        val r = Rig(this)
        runCurrent()
        r.controller.refreshAll()
        runCurrent()
        tick(2_000)
        r.controller.refreshAll()
        runCurrent()
        assertEquals(2, r.quotes.calls.size) // the start pass and one manual
        tick(3_000)
        r.controller.refreshAll()
        runCurrent()
        assertEquals(3, r.quotes.calls.size)
        // Retry is debounced per symbol
        r.controller.refresh("A"); r.controller.refresh("A"); r.controller.refresh("B")
        runCurrent()
        assertEquals(5, r.quotes.calls.size)
    }
}
