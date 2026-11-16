package com.marketsboard.data.net

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HttpURLConnection based fetcher. Works on Android API 26+ and the desktop JVM.
 *
 * - connect 8 s, read 10 s, plus an overall deadline so a slow-drip server cannot hold a coroutine forever
 * - gzip requested and decoded (the size cap applies to the decoded bytes)
 * - redirects followed manually: at most [maxRedirects], same scheme or http to https only
 * - body capped at [maxBodyBytes]; error bodies are read too
 * - every failure is returned as a typed [HttpResponse.failure], nothing is thrown
 */
class JvmHttpFetcher(
    private val clock: Clock = Clock.System,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 10_000,
    private val overallTimeoutMs: Long = 25_000,
    private val maxBodyBytes: Int = 2 * 1024 * 1024,
    private val maxRedirects: Int = 3,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpFetcher {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        withContext(Dispatchers.IO) {
            try {
                fetch(url, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(ErrorKind.Unknown, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

    private fun fetch(startUrl: String, headers: Map<String, String>): HttpResponse {
        val deadline = System.nanoTime() + overallTimeoutMs * 1_000_000L
        var current = startUrl
        var redirects = 0
        while (true) {
            val url = try {
                URL(current)
            } catch (e: IOException) {
                return fail(ErrorKind.Unknown, "bad url")
            }
            if (url.protocol != "http" && url.protocol != "https") return fail(ErrorKind.Unknown, "unsupported scheme")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.setRequestProperty("Accept", "*/*")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

                val code = conn.responseCode
                val respHeaders = HashMap<String, String>()
                conn.headerFields.forEach { (k, v) -> if (k != null && !v.isNullOrEmpty()) respHeaders[k.lowercase(Locale.ROOT)] = v.first() }

                if (code in REDIRECT_CODES) {
                    val loc = respHeaders["location"] ?: return fail(ErrorKind.Network, "redirect without location")
                    if (++redirects > maxRedirects) return fail(ErrorKind.Network, "too many redirects")
                    val next = try {
                        URL(url, loc)
                    } catch (e: IOException) {
                        return fail(ErrorKind.Network, "bad redirect location")
                    }
                    val upgradeOnly = next.protocol == url.protocol || (url.protocol == "http" && next.protocol == "https")
                    if (!upgradeOnly) return fail(ErrorKind.Network, "redirect would downgrade scheme")
                    current = next.toString()
                    continue
                }

                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val gzip = respHeaders["content-encoding"]?.contains("gzip", ignoreCase = true) == true
                val bytes = if (stream == null) ByteArray(0) else {
                    val src = if (gzip) GZIPInputStream(stream) else stream
                    readCapped(src, deadline) ?: return if (System.nanoTime() > deadline) {
                        fail(ErrorKind.Timeout, "overall deadline exceeded")
                    } else {
                        fail(ErrorKind.Parse, "response larger than $maxBodyBytes bytes")
                    }
                }
                val body = String(bytes, charsetOf(respHeaders["content-type"]))
                return HttpResponse(
                    code = code,
                    body = body,
                    headers = respHeaders,
                    retryAfterMs = parseRetryAfter(respHeaders["retry-after"], clock.nowMs()),
                )
            } catch (e: SocketTimeoutException) {
                return fail(ErrorKind.Timeout, "timeout: ${e.message}")
            } catch (e: IOException) {
                return fail(ErrorKind.Network, "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Reads at most [maxBodyBytes]; null when the cap or the deadline was hit. */
    private fun readCapped(src: InputStream, deadlineNanos: Long): ByteArray? = src.use {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBodyBytes) return null
            out.write(buf, 0, n)
            if (System.nanoTime() > deadlineNanos) return null
        }
        out.toByteArray()
    }

    private fun fail(kind: ErrorKind, message: String) = HttpResponse(code = 0, failure = FetchResult.Err(kind, message))

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** Retry-After is either delta-seconds or an HTTP date. Returns milliseconds from [nowMs], or null if unusable. */
        fun parseRetryAfter(value: String?, nowMs: Long): Long? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            v.toLongOrNull()?.let { return if (it < 0) null else minOf(it, 86_400L) * 1000L }
            return try {
                val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                (at - nowMs).coerceAtLeast(0L)
            } catch (e: Exception) {
                null
            }
        }

        private fun charsetOf(contentType: String?): java.nio.charset.Charset {
            val cs = contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.trim('"')
            if (cs.isNullOrEmpty()) return Charsets.UTF_8
            return try {
                java.nio.charset.Charset.forName(cs)
            } catch (e: Exception) {
                Charsets.UTF_8
            }
        }
    }
}
