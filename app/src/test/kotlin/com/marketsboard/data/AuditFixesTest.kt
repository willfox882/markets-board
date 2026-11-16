package com.marketsboard.data

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.Currencies
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.SeriesPoint
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.HttpResponse
import com.marketsboard.data.net.JvmHttpFetcher
import com.marketsboard.data.provider.StooqProvider
import com.marketsboard.data.provider.YahooChartProvider
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteStatus
import com.marketsboard.data.repo.RepoConfig
import com.marketsboard.data.store.EditResult
import com.marketsboard.data.store.FileJsonCache
import com.marketsboard.data.store.LoadStatus
import com.marketsboard.data.store.QuoteCodec
import com.marketsboard.data.store.WatchlistStore
import java.io.File
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the M1 audit findings. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditFixesTest {
    private val clock = FakeClock()
    private val cacheDir: File = tempDir()
    private val cache = FileJsonCache(cacheDir, clock)

    private fun repo(vararg providers: com.marketsboard.data.provider.MarketDataProvider, config: RepoConfig = RepoConfig()) =
        QuoteRepository(providers.toList(), cache, clock, config, { null }, Dispatchers.Unconfined)

    private fun rich(s: String, price: Double, asOf: Long) =
        quoteOf(s, price, asOf, series = listOf(SeriesPoint(1_000, 99.0), SeriesPoint(2_000, price)), prevClose = 99.0)

    private fun snapshot(s: String, price: Double, asOf: Long) =
        quoteOf(s, price, asOf, currency = "", name = s, exchange = "", prevClose = null, series = emptyList())

    // ---- 1. fallback merge -----------------------------------------------------------------------------------------

    @Test fun fallbackResultKeepsChangeAndSparklineOfTheHeldQuote() = runTest {
        var primaryDown = false
        val t = clock.nowMs()
        val a = ScriptedProvider("a") { s, _ -> if (primaryDown) FetchResult.Err(ErrorKind.Network, "down") else FetchResult.Ok(rich(s, 100.0, t)) }
        val b = ScriptedProvider("b") { s, _ -> FetchResult.Ok(snapshot(s, 105.0, t + 1_000)) }
        val r = repo(a, b)
        r.refresh(listOf("MSFT"))
        primaryDown = true
        r.refresh(listOf("MSFT"), force = true)
        val q = r.stateOf("MSFT")!!.quote!!
        assertEquals(105.0, q.price, 0.0)
        assertEquals(t + 1_000, q.asOfEpochMs)
        assertEquals(99.0, q.prevClose!!, 0.0) // kept
        assertEquals(2, q.series.size) // kept
        assertEquals(106.0, q.dayHigh!!, 0.0) // taken from the fallback
        assertEquals("USD", q.currency); assertEquals("Microsoft Corporation", q.name) // held values, not the blanks
        assertEquals("b", q.source)
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
    }

    @Test fun fallbackWithNothingHeldIsUsedAsIs() = runTest {
        val a = ScriptedProvider("a") { _, _ -> FetchResult.Err(ErrorKind.Http(503), "x") }
        val b = ScriptedProvider("b") { s, _ -> FetchResult.Ok(snapshot(s, 105.0, clock.nowMs())) }
        val r = repo(a, b)
        r.refresh(listOf("MSFT"))
        val q = r.stateOf("MSFT")!!.quote!!
        assertNull(q.prevClose); assertTrue(q.series.isEmpty()); assertEquals("b", q.source)
    }

    @Test fun fallbackSourcedQuoteIsNeverThrottledByMinRefetch() = runTest {
        var primaryDown = true
        val a = ScriptedProvider("a") { s, _ -> if (primaryDown) FetchResult.Err(ErrorKind.Network, "d") else FetchResult.Ok(rich(s, 100.0, clock.nowMs())) }
        val b = ScriptedProvider("b") { s, _ -> FetchResult.Ok(snapshot(s, 105.0, clock.nowMs())) }
        val r = repo(a, b)
        r.refresh(listOf("MSFT"))
        assertEquals(1, a.calls.size)
        clock.advance(1_000) // well inside the 30 s minimum interval
        primaryDown = false
        r.refresh(listOf("MSFT"))
        assertEquals("the primary is retried even though the quote is young", 2, a.calls.size)
        assertEquals("a", r.stateOf("MSFT")!!.quote!!.source)
        clock.advance(1_000)
        r.refresh(listOf("MSFT"))
        assertEquals("a primary sourced quote is throttled as before", 2, a.calls.size)
    }

    @Test fun fallbackQuoteFarOlderOrInTheFutureIsRejected() = runTest {
        val t = clock.nowMs()
        var fbAsOf = t - 2 * 86_400_000L
        var primaryDown = false
        val a = ScriptedProvider("a") { s, _ -> if (primaryDown) FetchResult.Err(ErrorKind.Network, "d") else FetchResult.Ok(rich(s, 100.0, t)) }
        val b = ScriptedProvider("b") { s, _ -> FetchResult.Ok(snapshot(s, 1.0, fbAsOf)) }
        val r = repo(a, b)
        r.refresh(listOf("MSFT"))
        primaryDown = true
        r.refresh(listOf("MSFT"), force = true)
        assertEquals(100.0, r.stateOf("MSFT")!!.quote!!.price, 0.0)
        assertTrue(r.stateOf("MSFT")!!.status is QuoteStatus.Stale)
        assertEquals(ErrorKind.Parse, r.stateOf("MSFT")!!.lastError!!.kind)
        fbAsOf = t + 2 * 86_400_000L
        r.refresh(listOf("MSFT"), force = true)
        assertEquals(100.0, r.stateOf("MSFT")!!.quote!!.price, 0.0)
    }

    @Test fun stooqHasNoFallbackForOtherRangesOrUnmappedSymbols() = runTest {
        val http = FakeHttp { ok(Fixtures.text("stooq_msft.csv")) }
        val stooq = StooqProvider(http)
        assertTrue(stooq.supports("MSFT", ChartRange.D1))
        assertFalse(stooq.supports("MSFT", ChartRange.M1))
        assertFalse(stooq.supports("XEQT.TO", ChartRange.D1)) // Stooq has no TSX coverage: expected
        val yahoo = ScriptedProvider("yahoo") { _, _ -> FetchResult.Err(ErrorKind.Network, "down") }
        val r = repo(yahoo, stooq)
        r.refresh(listOf("MSFT"), ChartRange.M1)
        r.refresh(listOf("XEQT.TO"), ChartRange.D1)
        assertTrue("Stooq must not be called", http.requests.isEmpty())
        assertEquals(ErrorKind.Network, r.stateOf("MSFT", ChartRange.M1)!!.lastError!!.kind)
        assertEquals(ErrorKind.Network, r.stateOf("XEQT.TO")!!.lastError!!.kind)
    }

    @Test fun stooqRangeOtherThanD1ReturnsNotFoundWithoutNetwork() = runBlocking {
        val http = FakeHttp { ok(Fixtures.text("stooq_msft.csv")) }
        val r = StooqProvider(http).quote("MSFT", ChartRange.Y1)
        assertEquals(ErrorKind.NotFound, (r as FetchResult.Err).kind)
        assertTrue(http.requests.isEmpty())
    }

    // ---- 2. clocks ----------------------------------------------------------------------------------------------------

    @Test fun cacheWrittenByAClockInTheFutureIsStaleWithUnknownAgeAndDoesNotBlockRefresh() = runTest {
        repo(ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, 50.0, clock.nowMs())) }).refresh(listOf("MSFT"))
        clock.jumpWall(-3_600_000) // device clock now earlier than the cache write
        val p = ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, 60.0, clock.nowMs())) }
        val r = repo(p)
        r.hydrate(listOf("MSFT"))
        val st = r.stateOf("MSFT")!!
        assertEquals(QuoteStatus.Stale(0, ageKnown = false), st.status)
        assertTrue(st.clockSkew)
        assertEquals(50.0, st.quote!!.price, 0.0) // still shown
        r.refresh(listOf("MSFT"))
        assertEquals("a negative age must not throttle", 1, p.calls.size)
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
    }

    @Test fun backwardWallClockJumpDoesNotAffectAgesOrRefreshes() = runTest {
        val p = ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, 50.0, clock.nowMs())) }
        val r = repo(p)
        r.refresh(listOf("MSFT"))
        clock.jumpWall(-3_600_000)
        r.recomputeAges()
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status) // monotonic age is 0, not -1 h
        assertTrue("device clock behind provider time is flagged", r.stateOf("MSFT")!!.clockSkew)
        clock.advance(31_000)
        r.refresh(listOf("MSFT"))
        assertEquals(2, p.calls.size)
    }

    @Test fun forwardWallClockJumpDoesNotMakeFreshDataStale() = runTest {
        val r = repo(ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, 50.0, clock.nowMs())) })
        r.refresh(listOf("MSFT"))
        clock.jumpWall(10 * 86_400_000L)
        r.recomputeAges()
        assertEquals(QuoteStatus.Fresh, r.stateOf("MSFT")!!.status)
        assertFalse(r.stateOf("MSFT")!!.clockSkew)
    }

    @Test fun providerTimeAheadOfDeviceClockFlagsSkew() = runTest {
        val ahead = repo(ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, 1.0, clock.nowMs() + 3_600_000)) })
        ahead.refresh(listOf("MSFT"))
        assertTrue(ahead.stateOf("MSFT")!!.clockSkew)
        val normal = repo(ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, 1.0, clock.nowMs() - 600_000)) })
        normal.refresh(listOf("MSFT"), force = true) // the shared cache holds the other quote
        assertFalse(normal.stateOf("MSFT")!!.clockSkew)
    }

    @Test fun cooldownUsesTheMonotonicClock() = runTest {
        val a = ScriptedProvider("a") { _, _ -> FetchResult.Err(ErrorKind.RateLimited(60_000), "429") }
        val r = repo(a)
        r.refresh(listOf("MSFT"))
        clock.jumpWall(-86_400_000L) // wall clock goes back a day: must not extend the cooldown
        clock.advance(61_000)
        r.refresh(listOf("MSFT"), force = true)
        assertEquals(2, a.calls.size)
    }

    // ---- 4. bounded batches -----------------------------------------------------------------------------------------

    @Test fun networkFailureShortCircuitsTheRestOfTheBatch() = runTest {
        val a = ScriptedProvider("a") { _, _ -> FetchResult.Err(ErrorKind.Network, "offline") }
        val r = repo(a)
        val symbols = (1..10).map { "S$it" }
        r.refresh(symbols)
        assertTrue("calls: ${a.calls.size}", a.calls.size <= 4)
        assertTrue(symbols.all { r.stateOf(it)!!.status == QuoteStatus.Error(ErrorKind.Network) })
    }

    @Test fun clockOrTlsFailureAlsoShortCircuitsAndKeepsItsKind() = runTest {
        val a = ScriptedProvider("a") { _, _ -> FetchResult.Err(ErrorKind.ClockOrTls, "cert date") }
        val r = repo(a)
        r.refresh((1..6).map { "S$it" })
        assertTrue(a.calls.size <= 4)
        assertEquals(QuoteStatus.Error(ErrorKind.ClockOrTls), r.stateOf("S6")!!.status)
    }

    @Test fun twoTimeoutsShortCircuitButOneDoesNot() = runTest {
        val a = ScriptedProvider("a") { _, _ -> FetchResult.Err(ErrorKind.Timeout, "slow") }
        val r = repo(a)
        r.refresh((1..10).map { "S$it" })
        assertEquals(2, a.calls.size)
        assertEquals(QuoteStatus.Error(ErrorKind.Timeout), r.stateOf("S10")!!.status)

        var n = 0
        val b = ScriptedProvider("b") { s, _ -> if (n++ == 0) FetchResult.Err(ErrorKind.Timeout, "slow") else FetchResult.Ok(quoteOf(s, 1.0, clock.nowMs())) }
        val r2 = repo(b)
        r2.refresh(listOf("X1", "X2", "X3"))
        assertEquals(3, b.calls.size)
    }

    @Test fun shortCircuitKeepsHeldQuotesAsStale() = runTest {
        repo(ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s, 7.0, clock.nowMs())) }).refresh(listOf("A", "B", "C"))
        clock.advance(60_000)
        val r = repo(ScriptedProvider("p") { _, _ -> FetchResult.Err(ErrorKind.Network, "off") })
        r.refresh(listOf("A", "B", "C"))
        for (s in listOf("A", "B", "C")) {
            assertEquals(7.0, r.stateOf(s)!!.quote!!.price, 0.0)
            assertTrue(r.stateOf(s)!!.status is QuoteStatus.Stale)
        }
    }

    // ---- 7. backoff accounting ------------------------------------------------------------------------------------

    @Test fun concurrentFailuresInOneWindowCountOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        val a = ScriptedProvider("a") { _, _ ->
            if (++started == 4) gate.complete(Unit)
            gate.await()
            FetchResult.Err(ErrorKind.RateLimited(null), "429")
        }
        val r = repo(a)
        r.refresh(listOf("A", "B", "C", "D"), force = true)
        assertEquals(4, a.calls.size)
        assertEquals("first cooldown is 30 s, not 240 s", 30_000L, r.cooldownRemainingMs())
    }

    @Test fun serverErrorWithJsonBodyIsHttp5xxAndTriggersBackoff() = runBlocking {
        val body = """{"chart":{"result":null,"error":{"code":"Internal Server Error","description":"oops"}}}"""
        val yahoo = YahooChartProvider(FakeHttp { HttpResponse(500, body) }, clock)
        val e = yahoo.quote("MSFT", ChartRange.D1) as FetchResult.Err
        assertEquals(ErrorKind.Http(500), e.kind)
        assertTrue(e.message.contains("oops"))
        val r = QuoteRepository(listOf(yahoo), cache, clock, RepoConfig(), { null }, Dispatchers.Unconfined)
        r.refresh(listOf("MSFT"))
        assertEquals(30_000L, r.cooldownRemainingMs())
    }

    @Test fun clientErrorWithUnknownJsonBodyStaysHttpKind() = runBlocking {
        val body = """{"chart":{"result":null,"error":{"code":"Bad Request","description":"nope"}}}"""
        val e = YahooChartProvider(FakeHttp { HttpResponse(400, body) }, clock).quote("MSFT", ChartRange.D1) as FetchResult.Err
        assertEquals(ErrorKind.Http(400), e.kind)
    }

    @Test fun stooqDailyLimitBodyIsRateLimitedWithLongCooldown() = runBlocking {
        val stooq = StooqProvider(FakeHttp { ok("Exceeded the daily hits limit") })
        val e = stooq.quote("MSFT", ChartRange.D1) as FetchResult.Err
        assertEquals(ErrorKind.RateLimited(3_600_000), e.kind)
        val r = QuoteRepository(listOf(stooq), cache, clock, RepoConfig(), { null }, Dispatchers.Unconfined)
        r.refresh(listOf("MSFT"))
        assertEquals(3_600_000L, r.cooldownRemainingMs())
    }

    @Test fun certificateDateErrorsAreDetected() {
        val expired = SSLHandshakeException("PKIX path validation failed").also { it.initCause(CertificateExpiredException("NotAfter: Sat Jan 01")) }
        assertTrue(JvmHttpFetcher.isCertificateDateError(expired))
        assertTrue(JvmHttpFetcher.isCertificateDateError(SSLHandshakeException("timestamp check failed")))
        assertFalse(JvmHttpFetcher.isCertificateDateError(SSLHandshakeException("Trust anchor for certification path not found")))
        assertFalse(JvmHttpFetcher.isCertificateDateError(java.io.IOException("boom")))
        assertEquals("clock_or_tls", ErrorKind.ClockOrTls.label)
    }

    // ---- 4/6/10. live-shaped Yahoo payloads --------------------------------------------------------------------------

    private fun yahoo(body: String) = YahooChartProvider(FakeHttp { ok(body) }, clock)

    private val gbpPayload = """{"chart":{"result":[{"meta":{"currency":"GBp","symbol":"VOD.L","exchangeName":"LSE","fullExchangeName":"LSE","instrumentType":"EQUITY","regularMarketTime":1714766400,"regularMarketPrice":72.34,"chartPreviousClose":71.9,"priceHint":2,"exchangeTimezoneName":"Europe/London","longName":"Vodafone Group Plc","currentTradingPeriod":{"regular":{"start":1714719600,"end":1714750200}}},"timestamp":[1714719900,1714720200],"indicators":{"quote":[{"close":[72.0,72.34]}]}}],"error":null}}"""

    @Test fun poundsPenceCurrencyKeepsItsMinorUnit() = runBlocking {
        val q = (yahoo(gbpPayload).quote("VOD.L", ChartRange.D1) as FetchResult.Ok).value
        assertEquals("GBX", q.currency)
        assertTrue(Currencies.isMinorUnit(q.currency))
        assertEquals("GBP", Currencies.majorOf(q.currency))
    }

    @Test fun currencyNormalisation() {
        assertEquals("GBX", Currencies.normalize("GBp"))
        assertEquals("GBP", Currencies.normalize("GBP"))
        assertEquals("GBP", Currencies.normalize(" gbp "))
        assertEquals("ZAC", Currencies.normalize("ZAc"))
        assertEquals("ZAR", Currencies.normalize("ZAR"))
        assertEquals("ILA", Currencies.normalize("ILA"))
        assertEquals("CAD", Currencies.normalize("cad"))
        assertEquals("", Currencies.normalize(null))
        assertFalse(Currencies.isMinorUnit("CAD"))
        assertEquals("CAD", Currencies.majorOf("CAD"))
    }

    @Test fun sessionTimezoneAndPriceHintAreParsed() = runBlocking {
        val q = (yahoo(gbpPayload).quote("VOD.L", ChartRange.D1) as FetchResult.Ok).value
        assertEquals(1_714_719_600_000L, q.sessionStartMs)
        assertEquals(1_714_750_200_000L, q.sessionEndMs)
        assertEquals("Europe/London", q.exchangeTimezoneName)
        assertEquals(2, q.priceHint)
        assertEquals("yahoo", q.source)
    }

    @Test fun fxPairsNeedFourDecimals() = runBlocking {
        val fx = """{"chart":{"result":[{"meta":{"currency":"CAD","symbol":"USDCAD=X","instrumentType":"CURRENCY","regularMarketTime":1714766400,"regularMarketPrice":1.3685,"priceHint":4},"timestamp":[1714766400],"indicators":{"quote":[{"close":[1.3685]}]}}],"error":null}}"""
        assertEquals(4, (yahoo(fx).quote("USDCAD=X", ChartRange.D1) as FetchResult.Ok).value.decimals)
        val noHint = fx.replace(",\"priceHint\":4", "")
        assertEquals(4, (yahoo(noHint).quote("USDCAD=X", ChartRange.D1) as FetchResult.Ok).value.decimals)
        assertEquals(2, (yahoo(gbpPayload).quote("VOD.L", ChartRange.D1) as FetchResult.Ok).value.decimals)
        assertEquals(2, quoteOf().decimals)
    }

    @Test fun responseWithoutTimestampKeyStillGivesAQuote() = runBlocking {
        val body = """{"chart":{"result":[{"meta":{"currency":"USD","symbol":"MSFT","regularMarketTime":1714766400,"regularMarketPrice":413.54,"chartPreviousClose":411.46}}],"error":null}}"""
        val q = (yahoo(body).quote("MSFT", ChartRange.D1) as FetchResult.Ok).value
        assertTrue(q.series.isEmpty())
        assertEquals(413.54, q.price, 0.0)
    }

    @Test fun plainTextRateLimitBodyIsRateLimited() = runBlocking {
        val p = YahooChartProvider(FakeHttp { HttpResponse(429, "Too Many Requests", retryAfterMs = null) }, clock)
        assertEquals(ErrorKind.RateLimited(null), (p.quote("MSFT", ChartRange.D1) as FetchResult.Err).kind)
        val p2 = YahooChartProvider(FakeHttp { HttpResponse(200, "Too Many Requests") }, clock)
        assertEquals(ErrorKind.Parse, (p2.quote("MSFT", ChartRange.D1) as FetchResult.Err).kind)
    }

    @Test fun offlineFailureDoesNotTryTheSecondHost() = runBlocking {
        val http = FakeHttp { HttpResponse(0, failure = FetchResult.Err(ErrorKind.Network, "unknown host"), offline = true) }
        YahooChartProvider(http, clock).quote("MSFT", ChartRange.D1)
        assertEquals(1, http.requests.size)
    }

    // ---- 10. stooq tolerance ---------------------------------------------------------------------------------------

    @Test fun stooqRowMissingTrailingFieldIsTolerated() = runBlocking {
        val csv = "Symbol,Date,Time,Open,High,Low,Close,Volume\nUSDCAD,2024-05-03,23:59:56,1.3641,1.3702,1.3620,1.3685\n"
        val q = (StooqProvider(FakeHttp { ok(csv) }).quote("USDCAD=X", ChartRange.D1) as FetchResult.Ok).value
        assertEquals(1.3685, q.price, 0.0)
        assertEquals("stooq", q.source)
    }

    @Test fun stooqColumnsAreFoundByHeaderName() = runBlocking {
        val csv = "Symbol,Date,Close,Open,Time,High,Low,Volume\nMSFT.US,2024-05-03,413.54,409.0,22:00:14,414.2,408.5,1\n"
        val q = (StooqProvider(FakeHttp { ok(csv) }).quote("MSFT", ChartRange.D1) as FetchResult.Ok).value
        assertEquals(413.54, q.price, 0.0)
        assertEquals(414.2, q.dayHigh!!, 0.0)
    }

    @Test fun stooqRowTooShortOrTooLongIsParseError() = runBlocking {
        for (row in listOf("MSFT.US,2024-05-03,22:00:14,1,2,3", "MSFT.US,2024-05-03,22:00:14,1,2,3,4,5,6")) {
            val csv = "Symbol,Date,Time,Open,High,Low,Close,Volume\n$row\n"
            assertEquals(ErrorKind.Parse, (StooqProvider(FakeHttp { ok(csv) }).quote("MSFT", ChartRange.D1) as FetchResult.Err).kind)
        }
    }

    @Test fun refreshingStaysTrueWhileAnyFetchIsActive() = runTest {
        val g1 = CompletableDeferred<Unit>(); val g2 = CompletableDeferred<Unit>()
        val p = ScriptedProvider("p") { s, _ -> (if (s == "A") g1 else g2).await(); FetchResult.Ok(quoteOf(s)) }
        val r = repo(p)
        val j1 = launch { r.refresh(listOf("A")) }
        val j2 = launch { r.refresh(listOf("B")) }
        runCurrent()
        assertTrue(r.refreshing.value)
        g1.complete(Unit); j1.join()
        assertTrue("one fetch is still running", r.refreshing.value)
        g2.complete(Unit); j2.join()
        assertFalse(r.refreshing.value)
    }

    // ---- 9. forget, codec ----------------------------------------------------------------------------------------------

    @Test fun forgetDropsStatesCacheAndAllRanges() = runTest {
        val r = repo(ScriptedProvider("p") { s, rg -> FetchResult.Ok(quoteOf(s, 1.0, clock.nowMs(), rg)) })
        r.refresh(listOf("MSFT", "AAPL"))
        r.refresh(listOf("MSFT"), ChartRange.M1)
        assertNotNull(cache.get("quote/MSFT/1M"))
        r.forget("msft")
        assertNull(r.stateOf("MSFT")); assertNull(r.stateOf("MSFT", ChartRange.M1))
        assertNull(cache.get("quote/MSFT/1D", allowExpired = true)); assertNull(cache.get("quote/MSFT/1M", allowExpired = true))
        assertNotNull(r.stateOf("AAPL")); assertNotNull(cache.get("quote/AAPL/1D"))
        val r2 = repo(ScriptedProvider("p") { s, _ -> FetchResult.Ok(quoteOf(s)) })
        r2.hydrate(listOf("MSFT"))
        assertNull(r2.stateOf("MSFT"))
    }

    @Test fun forgetDuringAFetchDoesNotResurrectTheSymbol() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = repo(ScriptedProvider("p") { s, _ -> gate.await(); FetchResult.Ok(quoteOf(s)) })
        val job = launch { r.refresh(listOf("MSFT")) }
        runCurrent()
        r.forget("MSFT")
        gate.complete(Unit); job.join()
        assertNull(r.stateOf("MSFT"))
        assertNull(cache.get("quote/MSFT/1D", allowExpired = true))
        r.refresh(listOf("MSFT")) // adding it again later works
        assertNotNull(r.stateOf("MSFT"))
    }

    @Test fun codecRoundTripsTheNewFields() {
        val q = quoteOf("VOD.L", 72.0, currency = "GBX").copy(
            source = "yahoo", sessionStartMs = 1_000, sessionEndMs = 2_000, exchangeTimezoneName = "Europe/London", priceHint = 2,
        )
        assertEquals(q, QuoteCodec.decode(QuoteCodec.encode(q)))
    }

    // ---- 3. watchlist durability -----------------------------------------------------------------------------------------

    private fun file() = File(tempDir(), "watchlist.json")
    private fun store(f: File) = WatchlistStore(f, clock)
    private fun item(s: String) = WatchItem(s, s, "USD", "X")
    private fun WatchlistStore.syms() = items.value.map { it.symbol }
    private fun corruptFiles(f: File) = f.parentFile.listFiles()!!.filter { it.name.startsWith("watchlist.corrupt-") }

    private fun twoEdits(f: File) = store(f).also { it.add(item("AAPL")); it.add(item("NVDA")) }

    @Test fun backupHoldsThePreviousGoodFile() {
        val f = file()
        twoEdits(f)
        val bak = File(f.parentFile, "watchlist.bak")
        assertTrue(bak.isFile)
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL"), store(bak.copyTo(File(tempDir(), "watchlist.json"))).syms())
    }

    @Test fun zeroByteFileRecoversFromBackupInsteadOfDefaults() {
        val f = file()
        twoEdits(f)
        f.writeText("") // power cut after the rename but before the data hit the disk
        val s = store(f)
        assertEquals(LoadStatus.RecoveredFromBackup, s.loadStatus)
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL"), s.syms())
        assertTrue("an empty file is not worth quarantining", corruptFiles(f).isEmpty())
        assertEquals(LoadStatus.Loaded, store(f).loadStatus) // the recovered list was written back
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL"), store(f).syms())
    }

    @Test fun truncatedFileRecoversFromBackupAndKeepsTheBrokenCopy() {
        val f = file()
        twoEdits(f)
        val broken = f.readText().take(45)
        f.writeText(broken)
        val s = store(f)
        assertEquals(LoadStatus.RecoveredFromBackup, s.loadStatus)
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL"), s.syms())
        assertEquals(broken, corruptFiles(f).single().readText())
    }

    @Test fun missingMainFileRecoversFromBackup() {
        val f = file()
        twoEdits(f)
        f.delete()
        val s = store(f)
        assertEquals(LoadStatus.RecoveredFromBackup, s.loadStatus)
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL"), s.syms())
    }

    @Test fun corruptMainAndCorruptBackupFallBackToDefaults() {
        val f = file()
        twoEdits(f)
        f.writeText("{garbage")
        File(f.parentFile, "watchlist.bak").writeText("{also garbage")
        val s = store(f)
        assertEquals(LoadStatus.RecoveredFromCorrupt, s.loadStatus)
        assertEquals(listOf("XEQT.TO", "MSFT"), s.syms())
    }

    @Test fun recoveryDoesNotOverwriteTheGoodBackupWithABrokenFile() {
        val f = file()
        twoEdits(f)
        val bak = File(f.parentFile, "watchlist.bak")
        val before = bak.readText()
        f.writeText("")
        store(f)
        assertEquals(before, bak.readText())
    }

    @Test fun newerVersionFileShowsItsItemsButIsNeverOverwrittenOrBackedUp() {
        val f = file()
        val future = """{"version":3,"items":[{"symbol":"AAPL","name":"Apple","extra":1},{"symbol":"NVDA"}]}"""
        f.writeText(future)
        val s = store(f)
        assertTrue(s.readOnly)
        assertEquals(listOf("AAPL", "NVDA"), s.syms())
        assertEquals(EditResult.ReadOnly, s.add(item("MSFT")))
        assertEquals(future, f.readText())
        assertFalse(File(f.parentFile, "watchlist.bak").exists())
    }

    @Test fun newerVersionWithUnreadableItemsFallsBackToDefaults() {
        val f = file()
        f.writeText("""{"version":3,"items":[{"sym":"AAPL"},"x"]}""")
        assertEquals(listOf("XEQT.TO", "MSFT"), store(f).syms())
    }

    @Test fun failedWriteIsRetriedByFlushIfDirty() {
        val dir = tempDir()
        val f = File(dir, "watchlist.json")
        val s = store(f)
        dir.deleteRecursively(); dir.writeText("blocked")
        assertEquals(EditResult.Done(persisted = false), s.add(item("AAPL")))
        assertFalse(s.flushIfDirty()) // still blocked
        dir.delete(); dir.mkdirs()
        assertTrue(s.flushIfDirty())
        assertNull(s.persistError.value)
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL"), store(f).syms())
        assertTrue(s.flushIfDirty()) // nothing pending
    }

    @Test fun writesLeaveNoTempFiles() {
        val f = file()
        twoEdits(f)
        assertTrue(f.parentFile.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun watchlistCurrencyKeepsMinorUnits() {
        val s = store(file())
        s.add(WatchItem("VOD.L", "Vodafone", "GBp", "LSE"))
        s.add(WatchItem("BP.L", "BP", "gbp", "LSE"))
        assertEquals("GBX", s.items.value.first { it.symbol == "VOD.L" }.currency)
        assertEquals("GBP", s.items.value.first { it.symbol == "BP.L" }.currency)
    }
}
