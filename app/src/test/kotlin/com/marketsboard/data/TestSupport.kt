package com.marketsboard.data

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Instrument
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.SeriesPoint
import com.marketsboard.data.net.HttpFetcher
import com.marketsboard.data.net.HttpResponse
import com.marketsboard.data.provider.MarketDataProvider
import java.io.File
import java.nio.file.Files

/** Wall clock [now] and monotonic [mono] move together on [advance]; [jumpWall] simulates a device clock change. */
class FakeClock(var now: Long = 1_700_000_000_000L, var mono: Long = 5_000_000L) : Clock {
    override fun nowMs(): Long = now
    override fun monoMs(): Long = mono
    fun advance(ms: Long) {
        now += ms
        mono += ms
    }
    fun jumpWall(ms: Long) {
        now += ms
    }
}

object Fixtures {
    fun bytes(name: String): ByteArray =
        (Fixtures::class.java.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")).use { it.readBytes() }

    fun text(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")).use { String(it.readBytes(), Charsets.UTF_8) }
}

fun tempDir(prefix: String = "mb"): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

fun quoteOf(
    symbol: String = "MSFT",
    price: Double = 100.0,
    asOf: Long = 1_700_000_000_000L,
    range: ChartRange = ChartRange.D1,
    currency: String = "USD",
    name: String = "Microsoft Corporation",
    exchange: String = "NASDAQ",
    prevClose: Double? = 99.0,
    series: List<SeriesPoint> = listOf(SeriesPoint(1_000L, 99.0), SeriesPoint(2_000L, price)),
) = Quote(symbol, name, currency, exchange, price, prevClose, price + 1, price - 1, "REGULAR", asOf, series, range)

/** HttpFetcher that answers from a lambda and records the URLs it was asked for. */
class FakeHttp(private val handler: (String) -> HttpResponse) : HttpFetcher {
    val requests = mutableListOf<String>()
    val headers = mutableListOf<Map<String, String>>()
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests.add(url)
        this.headers.add(headers)
        return handler(url)
    }
}

fun ok(body: String) = HttpResponse(200, body)
fun transportFail(kind: ErrorKind, msg: String = "x") = HttpResponse(0, failure = FetchResult.Err(kind, msg))

/** Provider whose behaviour is a suspend lambda; counts calls. */
class ScriptedProvider(
    override val id: String,
    private val supportsFn: (String) -> Boolean = { true },
    private val handler: suspend (String, ChartRange) -> FetchResult<Quote>,
) : MarketDataProvider {
    val calls = mutableListOf<String>()
    override fun supports(symbol: String) = supportsFn(symbol)
    override suspend fun quote(symbol: String, range: ChartRange): FetchResult<Quote> {
        calls.add(symbol)
        return handler(symbol, range)
    }
    override suspend fun search(query: String): FetchResult<List<Instrument>> = FetchResult.Ok(emptyList())
}
