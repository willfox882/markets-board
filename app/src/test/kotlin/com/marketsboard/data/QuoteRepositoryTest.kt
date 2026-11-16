package com.marketsboard.data

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.data.repo.RepoConfig
import com.marketsboard.data.store.FileJsonCache
import com.marketsboard.data.store.QuoteCodec
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuoteRepositoryTest {
    private val clock = FakeClock()
    private val cacheDir: File = tempDir()
    private val cache = FileJsonCache(cacheDir, clock)

    private fun repo(
        vararg providers: ScriptedProvider,
        hints: (String) -> WatchItem? = { null },
        config: RepoConfig = RepoConfig(),
    ) = QuoteRepository(providers.toList(), cache, clock, config, hints, Dispatchers.Unconfined)

    private fun okP(id: String = "p", price: Double = 100.0, asOf: () -> Long = { clock.nowMs() }, currency: String = "USD") =
        ScriptedProvider(id) { s, r -> FetchResult.Ok(quoteOf(s, price, asOf(), r, currency)) }

    private fun errP(id: String, kind: ErrorKind, supports: (String) -> Boolean = { true }) =
        ScriptedProvider(id, supports) { _, _ -> FetchResult.Err(kind, "$id failed") }

    // ---- basics --------------------------------------------------------------------------------------------------

    @Test fun refreshSuccessGivesFreshQuote() = runTest {
        val r = repo(okP())
        r.refresh(listOf("MSFT"))
        val st = r.stateOf("MSFT")!!
        assertEquals(QuoteStatus.Fresh, st.status)
        assertEquals(100.0, st.quote!!.price, 0.0)
        assertEquals(clock.nowMs(), st.lastSuccessMs)
        assertNull(st.lastError)
    }

    @Test fun symbolsAreNormalisedAndDeduplicated() = runTest {
        val p = okP()
        val r = repo(p)
        r.refresh(listOf("msft", " MSFT ", "bad symbol!"))
        assertEquals(listOf("MSFT"), p.calls)
        assertEquals(setOf("MSFT"), r.states.value.keys)
    }

    @Test fun stateFlowEmitsLoadingThenFresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        val p = ScriptedProvider("p") { s, _ -> gate.await(); FetchResult.Ok(quoteOf(s)) }
        val r = repo(p)
        val job = launch { r.refresh(listOf("MSFT")) }
        runCurrent()
        assertEquals(QuoteStatus.Loading, r.stateOf("MSFT")!!.status)
        assertNull(r.stateOf("MSFT")!!.quote)
        assertTrue(r.refreshing.value)
        gate.complete(Unit)
        job.join()
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
        assertFalse(r.refreshing.value)
    }

    @Test fun blankFieldsAreFilledFromWatchItemHint() = runTest {
        val stooqLike = ScriptedProvider("s") { sym, range -> FetchResult.Ok(quoteOf(sym, 41.0, clock.nowMs(), range, currency = "", name = sym, exchange = "")) }
        val hint = WatchItem("XEQT.TO", "iShares Core Equity ETF Portfolio", "CAD", "TSX")
        val r = repo(stooqLike, hints = { if (it == "XEQT.TO") hint else null })
        r.refresh(listOf("XEQT.TO"))
        val q = r.stateOf("XEQT.TO")!!.quote!!
        assertEquals("CAD", q.currency); assertEquals("TSX", q.exchange); assertEquals("iShares Core Equity ETF Portfolio", q.name)
    }

    @Test fun providerValuesWinOverHint() = runTest {
        val r = repo(okP(currency = "USD"), hints = { WatchItem(it, "Hint Name", "CAD", "HINTEX") })
        r.refresh(listOf("MSFT"))
        val q = r.stateOf("MSFT")!!.quote!!
        assertEquals("USD", q.currency)
        assertEquals("Microsoft Corporation", q.name)
        assertEquals("NASDAQ", q.exchange)
    }

    @Test fun quoteIsWrittenToCache() = runTest {
        repo(okP()).refresh(listOf("MSFT"))
        val e = cache.get("quote/MSFT/1D")!!
        assertEquals(100.0, QuoteCodec.decode(e.payload)!!.price, 0.0)
    }

    // ---- stale-while-revalidate -------------------------------------------------------------------------------------

    @Test fun hydrateServesCachedQuoteInstantlyWithoutNetwork() = runTest {
        repo(okP(price = 55.0)).refresh(listOf("MSFT"))
        clock.advance(60_000)
        val p = okP(price = 99.0)
        val r2 = repo(p) // a new process
        r2.hydrate(listOf("MSFT"))
        val st = r2.stateOf("MSFT")!!
        assertEquals(55.0, st.quote!!.price, 0.0)
        assertEquals(QuoteStatus.Fresh, st.status)
        assertTrue(p.calls.isEmpty())
    }

    @Test fun hydratedOldQuoteIsStaleWithAge() = runTest {
        repo(okP(price = 55.0)).refresh(listOf("MSFT"))
        clock.advance(10 * 60_000)
        val r2 = repo(okP())
        r2.hydrate(listOf("MSFT"))
        assertEquals(QuoteStatus.Stale(10 * 60_000), r2.stateOf("MSFT")!!.status)
    }

    @Test fun hydrateDiscardsEntriesBeyondMaxStale() = runTest {
        repo(okP()).refresh(listOf("MSFT"))
        clock.advance(8L * 24 * 3_600_000)
        val r2 = repo(okP())
        r2.hydrate(listOf("MSFT"))
        assertNull(r2.stateOf("MSFT"))
    }

    @Test fun refreshServesCacheFirstThenReplacesIt() = runTest {
        repo(okP(price = 55.0)).refresh(listOf("MSFT"))
        clock.advance(10 * 60_000)
        val gate = CompletableDeferred<Unit>()
        val p = ScriptedProvider("p") { s, _ -> gate.await(); FetchResult.Ok(quoteOf(s, 60.0, clock.nowMs())) }
        val r2 = repo(p)
        val job = launch { r2.refresh(listOf("MSFT")) }
        runCurrent()
        // Network call still pending: the cached quote is already on screen, marked stale.
        assertEquals(55.0, r2.stateOf("MSFT")!!.quote!!.price, 0.0)
        assertTrue(r2.stateOf("MSFT")!!.status is QuoteStatus.Stale)
        gate.complete(Unit); job.join()
        assertEquals(60.0, r2.stateOf("MSFT")!!.quote!!.price, 0.0)
        assertEquals(QuoteStatus.Fresh, r2.stateOf("MSFT")!!.status)
    }

    @Test fun corruptCacheEntryIsAMiss() = runTest {
        repo(okP()).refresh(listOf("MSFT"))
        cacheDir.listFiles()!!.filter { it.name.endsWith(".json") }.forEach { it.writeText("{broken") }
        val r2 = repo(okP())
        r2.hydrate(listOf("MSFT"))
        assertNull(r2.stateOf("MSFT"))
        r2.refresh(listOf("MSFT"))
        assertNotNull(r2.stateOf("MSFT")!!.quote)
    }

    @Test fun cachedQuoteForOtherRangeIsNotUsed() = runTest {
        cache.put("quote/MSFT/1D", QuoteCodec.encode(quoteOf("MSFT", range = ChartRange.M1)), 100_000)
        val r = repo(okP())
        r.hydrate(listOf("MSFT"))
        assertNull(r.stateOf("MSFT"))
    }

    @Test fun recomputeAgesTurnsFreshIntoStaleWithoutNetwork() = runTest {
        val p = okP()
        val r = repo(p)
        r.refresh(listOf("MSFT"))
        clock.advance(4 * 60_000)
        r.recomputeAges()
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
        clock.advance(2 * 60_000)
        r.recomputeAges()
        assertEquals(QuoteStatus.Stale(6 * 60_000), r.stateOf("MSFT")!!.status)
        assertEquals(1, p.calls.size)
    }

    @Test fun minRefetchSkipsFreshQuotesUnlessForced() = runTest {
        val p = okP()
        val r = repo(p)
        r.refresh(listOf("MSFT"))
        clock.advance(10_000)
        r.refresh(listOf("MSFT"))
        assertEquals(1, p.calls.size)
        r.refresh(listOf("MSFT"), force = true)
        assertEquals(2, p.calls.size)
        clock.advance(31_000)
        r.refresh(listOf("MSFT"))
        assertEquals(3, p.calls.size)
    }

    // ---- failures keep the last good quote ---------------------------------------------------------------------------

    @Test fun failureKeepsLastGoodQuoteAndMarksStaleWithAge() = runTest {
        var fail = false
        val p = ScriptedProvider("p") { s, _ -> if (fail) FetchResult.Err(ErrorKind.Network, "down") else FetchResult.Ok(quoteOf(s, 77.0, clock.nowMs())) }
        val r = repo(p)
        r.refresh(listOf("MSFT"))
        fail = true
        clock.advance(90_000)
        r.refresh(listOf("MSFT"), force = true)
        val st = r.stateOf("MSFT")!!
        assertEquals(77.0, st.quote!!.price, 0.0)
        assertEquals(QuoteStatus.Stale(90_000), st.status)
        assertEquals(ErrorKind.Network, st.lastError!!.kind)
        assertEquals(clock.nowMs() - 90_000, st.lastSuccessMs)
    }

    @Test fun recoveryAfterFailureReturnsToFreshAndClearsError() = runTest {
        var fail = true
        val p = ScriptedProvider("p") { s, _ -> if (fail) FetchResult.Err(ErrorKind.Timeout, "t") else FetchResult.Ok(quoteOf(s, 5.0, clock.nowMs())) }
        val r = repo(p)
        r.refresh(listOf("MSFT"))
        assertEquals(QuoteStatus.Error(ErrorKind.Timeout), r.stateOf("MSFT")!!.status)
        fail = false
        r.refresh(listOf("MSFT"))
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
        assertNull(r.stateOf("MSFT")!!.lastError)
    }

    @Test fun failureWithoutAnyQuoteIsErrorState() = runTest {
        val r = repo(errP("p", ErrorKind.NotFound))
        r.refresh(listOf("ZZZZ"))
        val st = r.stateOf("ZZZZ")!!
        assertNull(st.quote)
        assertEquals(QuoteStatus.Error(ErrorKind.NotFound), st.status)
        assertNull(st.lastSuccessMs)
    }

    @Test fun failedRefreshOfStaleHydratedQuoteKeepsItAndAges() = runTest {
        repo(okP(price = 55.0)).refresh(listOf("MSFT"))
        clock.advance(20 * 60_000)
        val r2 = repo(errP("p", ErrorKind.Network))
        r2.refresh(listOf("MSFT"))
        val st = r2.stateOf("MSFT")!!
        assertEquals(55.0, st.quote!!.price, 0.0)
        assertEquals(QuoteStatus.Stale(20 * 60_000), st.status)
    }

    @Test fun providerExceptionBecomesUnknownErrorNotCrash() = runTest {
        val p = ScriptedProvider("p") { _, _ -> throw IllegalStateException("bug") }
        val r = repo(p)
        r.refresh(listOf("MSFT"))
        assertEquals(ErrorKind.Unknown, r.stateOf("MSFT")!!.lastError!!.kind)
    }

    // ---- fallback chain --------------------------------------------------------------------------------------------

    @Test fun networkErrorFallsBackToNextProvider() = runTest {
        val a = errP("a", ErrorKind.Network)
        val b = okP("b", price = 12.0)
        val r = repo(a, b)
        r.refresh(listOf("MSFT"))
        assertEquals(12.0, r.stateOf("MSFT")!!.quote!!.price, 0.0)
        assertEquals(1, a.calls.size); assertEquals(1, b.calls.size)
    }

    @Test fun rateLimitedAndServerErrorsFallBack() = runTest {
        for (kind in listOf(ErrorKind.RateLimited(1000), ErrorKind.Http(502), ErrorKind.Timeout, ErrorKind.Parse)) {
            val b = okP("b")
            val r = repo(errP("a", kind), b)
            r.refresh(listOf("MSFT"), force = true) // the shared cache holds the previous iteration's quote
            assertEquals("$kind", 1, b.calls.size)
            assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
        }
    }

    @Test fun notFoundIsNotRetriedOnFallbackWithoutMapping() = runTest {
        val a = errP("yahoo", ErrorKind.NotFound)
        val b = ScriptedProvider("stooq", supportsFn = { false }) { s, _ -> FetchResult.Ok(quoteOf(s)) }
        val r = repo(a, b)
        r.refresh(listOf("XEQT.TO"))
        assertTrue(b.calls.isEmpty())
        assertEquals(QuoteStatus.Error(ErrorKind.NotFound), r.stateOf("XEQT.TO")!!.status)
    }

    @Test fun notFoundIsRetriedOnFallbackWhenMappingExists() = runTest {
        val a = errP("yahoo", ErrorKind.NotFound)
        val b = okP("stooq", price = 9.0)
        val r = repo(a, b)
        r.refresh(listOf("MSFT"))
        assertEquals(9.0, r.stateOf("MSFT")!!.quote!!.price, 0.0)
    }

    @Test fun noFallbackAvailableKeepsPrimaryError() = runTest {
        val b = ScriptedProvider("stooq", supportsFn = { false }) { s, _ -> FetchResult.Ok(quoteOf(s)) }
        val r = repo(errP("yahoo", ErrorKind.Network), b)
        r.refresh(listOf("XEQT.TO"))
        assertEquals(ErrorKind.Network, r.stateOf("XEQT.TO")!!.lastError!!.kind)
        assertTrue(b.calls.isEmpty())
    }

    @Test fun reportedErrorIsThePrimarysWhenAllFail() = runTest {
        val r = repo(errP("a", ErrorKind.Http(500)), errP("b", ErrorKind.NotFound))
        r.refresh(listOf("MSFT"))
        assertEquals(ErrorKind.Http(500), r.stateOf("MSFT")!!.lastError!!.kind)
    }

    @Test fun primaryIsPreferredWhenHealthy() = runTest {
        val a = okP("a", 1.0); val b = okP("b", 2.0)
        val r = repo(a, b)
        r.refresh(listOf("MSFT"))
        assertEquals(1.0, r.stateOf("MSFT")!!.quote!!.price, 0.0)
        assertTrue(b.calls.isEmpty())
    }

    // ---- cooldown and backoff ----------------------------------------------------------------------------------------

    @Test fun retryAfterIsHonouredAcrossSymbolsAndRefreshes() = runTest {
        val a = errP("a", ErrorKind.RateLimited(120_000))
        val r = repo(a)
        r.refresh(listOf("MSFT"))
        assertEquals(1, a.calls.size)
        clock.advance(60_000)
        r.refresh(listOf("MSFT", "AAPL"), force = true)
        assertEquals("cooldown blocks every symbol, even forced refreshes", 1, a.calls.size)
        val err = r.stateOf("AAPL")!!.lastError!!
        assertEquals(ErrorKind.RateLimited(60_000), err.kind) // remaining cooldown
        clock.advance(60_001)
        r.refresh(listOf("MSFT"), force = true)
        assertEquals(2, a.calls.size)
    }

    @Test fun backoffDoublesAndCapsAtFifteenMinutes() = runTest {
        val a = errP("a", ErrorKind.RateLimited(null))
        val r = repo(a)
        val waits = mutableListOf<Long>()
        repeat(8) {
            val before = a.calls.size
            r.refresh(listOf("MSFT"), force = true)
            assertEquals(before + 1, a.calls.size)
            waits.add(r.cooldownRemainingMs())
            clock.advance(r.cooldownRemainingMs() + 1)
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L, 900_000L), waits)
    }

    @Test fun successResetsBackoff() = runTest {
        var limited = true
        val a = ScriptedProvider("a") { s, _ -> if (limited) FetchResult.Err(ErrorKind.RateLimited(null), "429") else FetchResult.Ok(quoteOf(s, 3.0, clock.nowMs())) }
        val r = repo(a)
        r.refresh(listOf("MSFT"), force = true)
        clock.advance(31_000)
        r.refresh(listOf("MSFT"), force = true)
        assertEquals(60_000L, r.cooldownRemainingMs())
        clock.advance(61_000)
        limited = false
        r.refresh(listOf("MSFT"), force = true)
        limited = true
        r.refresh(listOf("MSFT"), force = true)
        assertEquals(30_000L, r.cooldownRemainingMs()) // streak restarted
    }

    @Test fun retryAfterAboveCapIsCapped() = runTest {
        val r = repo(errP("a", ErrorKind.RateLimited(3_600_000)))
        r.refresh(listOf("MSFT"))
        assertEquals(900_000L, r.cooldownRemainingMs())
    }

    @Test fun serverErrorsCoolDownButClientErrorsAndNetworkDoNot() = runTest {
        val s = errP("a", ErrorKind.Http(503))
        val r = repo(s)
        r.refresh(listOf("MSFT"))
        assertEquals(30_000L, r.cooldownRemainingMs())

        for (kind in listOf(ErrorKind.Http(403), ErrorKind.Network, ErrorKind.Timeout, ErrorKind.NotFound, ErrorKind.Parse)) {
            val p = errP("a", kind)
            val r2 = repo(p)
            r2.refresh(listOf("MSFT"), force = true)
            r2.refresh(listOf("MSFT"), force = true)
            assertEquals("$kind must not trigger cooldown", 2, p.calls.size)
        }
    }

    @Test fun rateLimitedPrimaryIsSkippedWhileFallbackKeepsServing() = runTest {
        val a = errP("a", ErrorKind.RateLimited(300_000))
        val b = okP("b", 4.0)
        val r = repo(a, b)
        r.refresh(listOf("MSFT"), force = true)
        r.refresh(listOf("AAPL"), force = true)
        assertEquals(1, a.calls.size) // second symbol went straight to the fallback
        assertEquals(2, b.calls.size)
        assertEquals(QuoteStatus.Fresh, r.stateOf("AAPL")!!.status)
        assertEquals(0L, r.cooldownRemainingMs()) // not every provider is cooling
    }

    @Test fun allProvidersCoolingReturnsRateLimitedWithoutCalls() = runTest {
        val a = errP("a", ErrorKind.RateLimited(60_000)); val b = errP("b", ErrorKind.RateLimited(90_000))
        val r = repo(a, b)
        r.refresh(listOf("MSFT"), force = true)
        r.refresh(listOf("AAPL"), force = true)
        assertEquals(1, a.calls.size); assertEquals(1, b.calls.size)
        assertEquals(ErrorKind.RateLimited(60_000), r.stateOf("AAPL")!!.lastError!!.kind)
        assertEquals(60_000L, r.cooldownRemainingMs())
    }

    // ---- ordering, concurrency, cancellation -----------------------------------------------------------------------

    @Test fun olderProviderTimeResultIsIgnored() = runTest {
        var asOf = 2_000_000L
        val p = ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, price = asOf / 10_000.0, asOf = asOf)) }
        val r = repo(p)
        r.refresh(listOf("MSFT"))
        assertEquals(200.0, r.stateOf("MSFT")!!.quote!!.price, 0.0)
        asOf = 1_000_000L // a late, older reading
        clock.advance(100_000)
        r.refresh(listOf("MSFT"), force = true)
        val st = r.stateOf("MSFT")!!
        assertEquals(200.0, st.quote!!.price, 0.0)
        assertEquals(2_000_000L, st.quote!!.asOfEpochMs)
        assertTrue("an ignored result must not make the quote look fresh", st.status is QuoteStatus.Stale)
    }

    @Test fun sameProviderTimeIsAcceptedAndRefreshesFreshness() = runTest {
        val p = ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, 5.0, asOf = 777L)) } // market closed: time does not advance
        val r = repo(p)
        r.refresh(listOf("MSFT"))
        clock.advance(120_000)
        r.refresh(listOf("MSFT"))
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
        assertEquals(clock.nowMs(), r.stateOf("MSFT")!!.lastSuccessMs)
    }

    @Test fun newerProviderTimeReplaces() = runTest {
        var asOf = 1_000L
        val p = ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, price = asOf.toDouble(), asOf = asOf)) }
        val r = repo(p)
        r.refresh(listOf("MSFT")); asOf = 2_000L
        r.refresh(listOf("MSFT"), force = true)
        assertEquals(2_000.0, r.stateOf("MSFT")!!.quote!!.price, 0.0)
    }

    @Test fun atMostFourFetchesRunConcurrently() = runTest {
        var running = 0; var peak = 0
        val gate = CompletableDeferred<Unit>()
        val p = ScriptedProvider("p") { s, _ ->
            running++; peak = maxOf(peak, running)
            gate.await()
            running--
            FetchResult.Ok(quoteOf(s))
        }
        val r = repo(p)
        val symbols = (1..10).map { "S$it" }
        val job = launch { r.refresh(symbols) }
        runCurrent()
        assertEquals(4, peak)
        assertEquals(4, running)
        gate.complete(Unit)
        job.join()
        assertEquals(10, p.calls.size)
        assertEquals(4, peak)
        assertTrue(symbols.all { r.stateOf(it)!!.status == QuoteStatus.Fresh })
    }

    @Test fun concurrencyLimitIsConfigurable() = runTest {
        var running = 0; var peak = 0
        val gate = CompletableDeferred<Unit>()
        val p = ScriptedProvider("p") { s, _ -> running++; peak = maxOf(peak, running); gate.await(); running--; FetchResult.Ok(quoteOf(s)) }
        val r = repo(p, config = RepoConfig(maxConcurrent = 2))
        val job = launch { r.refresh((1..6).map { "S$it" }) }
        runCurrent()
        assertEquals(2, peak)
        gate.complete(Unit); job.join()
    }

    @Test fun concurrentRefreshOfSameSymbolFetchesOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val p = ScriptedProvider("p") { s, _ -> gate.await(); FetchResult.Ok(quoteOf(s)) }
        val r = repo(p)
        val j1 = launch { r.refresh(listOf("MSFT")) }
        val j2 = launch { r.refresh(listOf("MSFT"), force = true) }
        runCurrent()
        gate.complete(Unit)
        j1.join(); j2.join()
        assertEquals(1, p.calls.size)
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
    }

    @Test fun cancellationNeverLeavesLoadingOrBlocksLaterRefreshes() = runTest {
        val gate = CompletableDeferred<Unit>()
        var block = true
        val p = ScriptedProvider("p") { s, _ -> if (block) gate.await(); FetchResult.Ok(quoteOf(s)) }
        val r = repo(p)
        val job = launch { r.refresh(listOf("MSFT", "AAPL")) }
        runCurrent()
        assertEquals(QuoteStatus.Loading, r.stateOf("MSFT")!!.status)
        assertTrue(r.refreshing.value)
        job.cancel()
        job.join()
        assertNull("a cancelled first load must not stay Loading", r.stateOf("MSFT"))
        assertNull(r.stateOf("AAPL"))
        assertFalse(r.refreshing.value)
        block = false
        r.refresh(listOf("MSFT", "AAPL")) // semaphore permits and in-flight markers were released
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
        assertEquals(QuoteStatus.Fresh, r.stateOf("AAPL")!!.status)
    }

    @Test fun cancellationDuringRefreshKeepsPreviousQuoteState() = runTest {
        val first = okP(price = 10.0)
        val r0 = repo(first)
        r0.refresh(listOf("MSFT"))
        val before = r0.stateOf("MSFT")!!
        val gate = CompletableDeferred<Unit>()
        val slow = ScriptedProvider("p") { s, _ -> gate.await(); FetchResult.Ok(quoteOf(s, 11.0, clock.nowMs() + 1)) }
        val r = QuoteRepository(listOf(slow), cache, clock, RepoConfig(), { null }, Dispatchers.Unconfined)
        r.hydrate(listOf("MSFT"))
        val job = launch { r.refresh(listOf("MSFT"), force = true) }
        runCurrent()
        job.cancel(); job.join()
        assertEquals(before.quote, r.stateOf("MSFT")!!.quote)
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
    }

    // ---- ranges -----------------------------------------------------------------------------------------------------

    @Test fun otherRangesUseSeparateStatesAndCacheEntries() = runTest {
        val p = okP()
        val r = repo(p)
        r.refresh(listOf("MSFT"), ChartRange.D1)
        r.refresh(listOf("MSFT"), ChartRange.M1)
        assertEquals(ChartRange.D1, r.stateOf("MSFT", ChartRange.D1)!!.quote!!.range)
        assertEquals(ChartRange.M1, r.stateOf("MSFT", ChartRange.M1)!!.quote!!.range)
        assertEquals(setOf("MSFT", "MSFT@1M"), r.states.value.keys)
        assertNotNull(cache.get("quote/MSFT/1M"))
    }

    @Test fun longRangesGoStaleLater() = runTest {
        val r = repo(okP())
        r.refresh(listOf("MSFT"), ChartRange.M3)
        clock.advance(3_600_000)
        r.recomputeAges()
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT", ChartRange.M3)!!.status)
        clock.advance(6 * 3_600_000)
        r.recomputeAges()
        assertTrue(r.stateOf("MSFT", ChartRange.M3)!!.status is QuoteStatus.Stale)
    }

    @Test fun emptySymbolListIsNoOp() = runTest {
        val p = okP()
        repo(p).refresh(emptyList())
        assertTrue(p.calls.isEmpty())
    }

    @Test fun advanceUntilIdleHelperSanity() = runTest {
        // Guards the test setup itself: nothing is left pending after a refresh.
        val r = repo(okP())
        val j = launch { r.refresh(listOf("MSFT")) }
        advanceUntilIdle()
        assertTrue(j.isCompleted)
    }
}
