package com.marketsboard.data

import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.net.FetchEvent
import com.marketsboard.data.net.FetchLog
import com.marketsboard.data.net.HttpResponse
import com.marketsboard.data.net.LoggingHttpFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchLogTest {
    private fun ev(i: Int) = FetchEvent(i.toLong(), "p", "h", true, 200, 5, null)

    @Test fun ringBufferKeepsLastHundredNewestLast() {
        val log = FetchLog()
        for (i in 1..250) log.record(ev(i))
        val e = log.events.value
        assertEquals(100, e.size)
        assertEquals(151L, e.first().timeMs)
        assertEquals(250L, e.last().timeMs)
    }

    @Test fun customCapacityAndClear() {
        val log = FetchLog(3)
        for (i in 1..5) log.record(ev(i))
        assertEquals(listOf(3L, 4L, 5L), log.events.value.map { it.timeMs })
        log.clear()
        assertTrue(log.events.value.isEmpty())
    }

    @Test fun loggingFetcherRecordsHostOnlyAndLatency() {
        val clock = FakeClock(1000)
        val log = FetchLog()
        val inner = FakeHttp { clock.advance(42); ok("body") }
        val f = LoggingHttpFetcher(inner, log, "yahoo", clock)
        runBlocking { f.get("https://query1.finance.yahoo.com/v1/finance/search?q=my%20private%20query") }
        val e = log.events.value.single()
        assertEquals("yahoo", e.provider)
        assertEquals("query1.finance.yahoo.com", e.host)
        assertEquals(42L, e.latencyMs)
        assertTrue(e.ok)
        assertNull(e.error)
        assertFalse(e.toString().contains("private"))
    }

    @Test fun loggingFetcherRecordsFailuresAndHttpErrors() {
        val log = FetchLog()
        var n = 0
        val inner = FakeHttp { if (n++ == 0) transportFail(ErrorKind.Timeout, "slow") else HttpResponse(429, "", retryAfterMs = 1000) }
        val f = LoggingHttpFetcher(inner, log, "yahoo")
        runBlocking { f.get("https://a.example/x"); f.get("https://b.example/y") }
        val (a, b) = log.events.value
        assertFalse(a.ok); assertEquals(0, a.code); assertTrue(a.error!!.contains("timeout"))
        assertFalse(b.ok); assertEquals(429, b.code); assertTrue(b.error!!.contains("rate_limited"))
    }

    @Test fun loggingFetcherTurnsDelegateExceptionIntoTypedResult() {
        val log = FetchLog()
        val boom = object : com.marketsboard.data.net.HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = throw IllegalStateException("kaput")
        }
        val r = runBlocking { LoggingHttpFetcher(boom, log, "x").get("https://a.example/") }
        assertEquals(ErrorKind.Unknown, r.failure!!.kind)
        assertEquals(1, log.events.value.size)
    }
}
