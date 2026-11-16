package com.marketsboard.sandbox

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.net.JvmHttpFetcher
import com.marketsboard.data.net.hostOf
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs on the desktop JVM (not in :app) because com.sun.net.httpserver is not on the Android test classpath. */
class JvmHttpFetcherTest {
    private class FakeClock(var now: Long) : Clock { override fun nowMs() = now }

    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After fun stop() {
        server.stop(0)
    }

    private fun route(path: String, h: (HttpExchange) -> Unit) {
        server.createContext(path) { ex ->
            try {
                h(ex)
            } finally {
                ex.close()
            }
        }
    }

    private fun HttpExchange.reply(code: Int, body: ByteArray, vararg headers: Pair<String, String>) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.write(body)
    }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun fetch(path: String, f: JvmHttpFetcher = JvmHttpFetcher(), headers: Map<String, String> = emptyMap()) =
        runBlocking { f.get(base + path, headers) }

    @Test fun ok200ReturnsBodyAndLowercasedHeaders() {
        route("/ok") { it.reply(200, """{"a":1}""".toByteArray(), "Content-Type" to "application/json; charset=utf-8", "X-Custom" to "yes") }
        val r = fetch("/ok")
        assertEquals(200, r.code)
        assertEquals("""{"a":1}""", r.body)
        assertTrue(r.isSuccess)
        assertNull(r.toError())
        assertEquals("yes", r.headers["x-custom"])
    }

    @Test fun sendsBrowserLikeUserAgentGzipAndCustomHeaders() {
        var seen: Map<String, List<String>> = emptyMap()
        route("/h") { seen = it.requestHeaders.toMap(); it.reply(200, "x".toByteArray()) }
        fetch("/h", headers = mapOf("Accept" to "application/json"))
        assertTrue(seen["User-agent"]!!.single().startsWith("Mozilla/5.0"))
        assertTrue(seen["Accept-encoding"]!!.single().contains("gzip"))
        assertEquals("application/json", seen["Accept"]!!.single())
    }

    @Test fun gzipBodyIsDecoded() {
        route("/gz") { it.reply(200, gzip("hello gzip".toByteArray()), "Content-Encoding" to "gzip") }
        assertEquals("hello gzip", fetch("/gz").body)
    }

    @Test fun nonUtf8CharsetIsHonoured() {
        route("/cs") { it.reply(200, "café".toByteArray(Charsets.ISO_8859_1), "Content-Type" to "text/plain; charset=ISO-8859-1") }
        assertEquals("café", fetch("/cs").body)
    }

    @Test fun rateLimitedWithRetryAfterSeconds() {
        route("/429") { it.reply(429, "slow down".toByteArray(), "Retry-After" to "120") }
        val r = fetch("/429")
        assertEquals(429, r.code)
        assertEquals(120_000L, r.retryAfterMs)
        assertEquals(ErrorKind.RateLimited(120_000L), r.toError()!!.kind)
        assertEquals("slow down", r.body) // error bodies are read
    }

    @Test fun rateLimitedWithRetryAfterHttpDate() {
        val now = ZonedDateTime.of(2024, 5, 3, 12, 0, 0, 0, ZoneOffset.UTC)
        val at = now.plusSeconds(90)
        route("/429d") { it.reply(429, ByteArray(0), "Retry-After" to DateTimeFormatter.RFC_1123_DATE_TIME.format(at)) }
        val r = fetch("/429d", JvmHttpFetcher(clock = FakeClock(now.toInstant().toEpochMilli())))
        assertEquals(90_000L, r.retryAfterMs)
    }

    @Test fun retryAfterParsing() {
        val now = 2_000_000_000_000L
        assertEquals(5_000L, JvmHttpFetcher.parseRetryAfter(" 5 ", now))
        assertEquals(0L, JvmHttpFetcher.parseRetryAfter("0", now))
        assertNull(JvmHttpFetcher.parseRetryAfter("-5", now))
        assertNull(JvmHttpFetcher.parseRetryAfter("soon", now))
        assertNull(JvmHttpFetcher.parseRetryAfter("", now))
        assertNull(JvmHttpFetcher.parseRetryAfter(null, now))
        assertEquals(86_400_000L, JvmHttpFetcher.parseRetryAfter("99999999", now)) // clamped to a day
        val past = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.of(1999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
        assertEquals(0L, JvmHttpFetcher.parseRetryAfter(past, now)) // a past date means "now"
    }

    @Test fun serverError500ReadsBodyAndMapsToHttp() {
        route("/500") { it.reply(500, "boom".toByteArray()) }
        val r = fetch("/500")
        assertEquals(500, r.code)
        assertEquals("boom", r.body)
        assertEquals(ErrorKind.Http(500), r.toError()!!.kind)
    }

    @Test fun notFound404MapsToNotFoundAndKeepsBody() {
        route("/404") { it.reply(404, """{"chart":{}}""".toByteArray()) }
        val r = fetch("/404")
        assertEquals(ErrorKind.NotFound, r.toError()!!.kind)
        assertEquals("""{"chart":{}}""", r.body)
    }

    @Test fun followsRedirects() {
        route("/r1") { it.reply(302, ByteArray(0), "Location" to "/r2") }
        route("/r2") { it.reply(301, ByteArray(0), "Location" to "$base/final") }
        route("/final") { it.reply(200, "arrived".toByteArray()) }
        val r = fetch("/r1")
        assertEquals(200, r.code)
        assertEquals("arrived", r.body)
    }

    @Test fun stopsAfterThreeRedirects() {
        route("/loop") { it.reply(302, ByteArray(0), "Location" to "/loop") }
        val r = fetch("/loop")
        assertEquals(0, r.code)
        assertEquals(ErrorKind.Network, r.failure!!.kind)
        assertTrue(r.failure!!.message.contains("redirect"))
    }

    @Test fun exactlyThreeRedirectsStillSucceed() {
        route("/a") { it.reply(302, ByteArray(0), "Location" to "/b") }
        route("/b") { it.reply(302, ByteArray(0), "Location" to "/c") }
        route("/c") { it.reply(302, ByteArray(0), "Location" to "/d") }
        route("/d") { it.reply(200, "ok".toByteArray()) }
        assertEquals(200, fetch("/a").code)
    }

    @Test fun refusesRedirectToOtherScheme() {
        route("/bad") { it.reply(302, ByteArray(0), "Location" to "ftp://example.com/x") }
        val r = fetch("/bad")
        assertEquals(0, r.code)
        assertTrue(r.failure!!.message.contains("downgrade"))
    }

    @Test fun redirectWithoutLocationFails() {
        route("/nol") { it.reply(302, ByteArray(0)) }
        assertEquals(ErrorKind.Network, fetch("/nol").failure!!.kind)
    }

    @Test fun readTimeoutMapsToTimeout() {
        route("/slow") { Thread.sleep(1500); runCatching { it.reply(200, "late".toByteArray()) } }
        val r = fetch("/slow", JvmHttpFetcher(readTimeoutMs = 300))
        assertEquals(0, r.code)
        assertEquals(ErrorKind.Timeout, r.failure!!.kind)
    }

    @Test fun overallDeadlineStopsSlowDrip() {
        route("/drip") {
            it.sendResponseHeaders(200, 0)
            repeat(30) { _ -> runCatching { it.responseBody.write('x'.code); it.responseBody.flush() }; Thread.sleep(50) }
        }
        val r = fetch("/drip", JvmHttpFetcher(overallTimeoutMs = 400))
        assertEquals(ErrorKind.Timeout, r.failure!!.kind)
    }

    @Test fun oversizedBodyIsRejected() {
        route("/big") { it.reply(200, ByteArray(5000) { 'a'.code.toByte() }) }
        val r = fetch("/big", JvmHttpFetcher(maxBodyBytes = 1000))
        assertEquals(0, r.code)
        assertEquals(ErrorKind.Parse, r.failure!!.kind)
        assertTrue(r.failure!!.message.contains("larger"))
    }

    @Test fun bodyAtExactlyTheCapIsAccepted() {
        route("/edge") { it.reply(200, ByteArray(1000) { 'a'.code.toByte() }) }
        assertEquals(1000, fetch("/edge", JvmHttpFetcher(maxBodyBytes = 1000)).body.length)
    }

    @Test fun gzipBombIsCappedOnDecodedSize() {
        route("/bomb") { it.reply(200, gzip(ByteArray(200_000)), "Content-Encoding" to "gzip") }
        val r = fetch("/bomb", JvmHttpFetcher(maxBodyBytes = 10_000))
        assertEquals(ErrorKind.Parse, r.failure!!.kind)
    }

    @Test fun defaultCapIsTwoMegabytes() {
        route("/two") { it.reply(200, ByteArray(2 * 1024 * 1024 + 10) { 'a'.code.toByte() }) }
        assertEquals(ErrorKind.Parse, fetch("/two").failure!!.kind)
    }

    @Test fun connectionRefusedIsNetworkError() {
        val port = ServerSocket(0).use { it.localPort } // free, then closed
        val r = runBlocking { JvmHttpFetcher().get("http://127.0.0.1:$port/x") }
        assertEquals(0, r.code)
        assertEquals(ErrorKind.Network, r.failure!!.kind)
        assertFalse(r.isSuccess)
    }

    @Test fun unknownHostIsNetworkError() {
        val r = runBlocking { JvmHttpFetcher().get("http://no-such-host.invalid/x") }
        assertEquals(ErrorKind.Network, r.failure!!.kind)
    }

    @Test fun malformedAndUnsupportedUrlsNeverThrow() {
        for (u in listOf("not a url", "ftp://example.com/x", "", "file:///etc/passwd")) {
            val r = runBlocking { JvmHttpFetcher().get(u) }
            assertEquals("url: $u", ErrorKind.Unknown, r.failure?.kind)
        }
    }

    @Test fun emptyBodyIsFine() {
        route("/empty") { it.reply(204, ByteArray(0)) }
        val r = fetch("/empty")
        assertEquals(204, r.code)
        assertEquals("", r.body)
    }

    @Test fun hostOfNeverLeaksPathOrQuery() {
        assertEquals("query1.finance.yahoo.com", hostOf("https://query1.finance.yahoo.com/v1/finance/search?q=secret"))
        assertEquals("?", hostOf("garbage"))
    }

    @Test fun failureResultIsFetchErrNotException() {
        val r = runBlocking { JvmHttpFetcher().get("http://127.0.0.1:1/x") }
        assertTrue(r.toError() is FetchResult.Err)
    }
}
