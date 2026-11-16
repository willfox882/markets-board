package com.marketsboard.data.net

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HttpURLConnection based fetcher. Works on Android API 26+ and the desktop JVM.
 *
 * - the WHOLE fetch (DNS, connect, headers, redirects, body) is bounded by [overallTimeoutMs] (default 20 s); on
 *   timeout or cancellation of the caller the connection is disconnected so the worker thread is released
 *   (a blocked DNS lookup cannot be interrupted, but the caller is freed at once and the thread ends with the lookup)
 * - connect 8 s and read 10 s per step as a second line of defence
 * - gzip requested and decoded (the size cap applies to the decoded bytes)
 * - redirects followed manually: at most [maxRedirects], same scheme or http to https only
 * - body capped at [maxBodyBytes]; error bodies are read too
 * - every failure is returned as a typed [HttpResponse.failure], nothing is thrown (except cancellation)
 */
class JvmHttpFetcher(
    private val clock: Clock = Clock.System,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 10_000,
    private val overallTimeoutMs: Long = 20_000,
    private val maxBodyBytes: Int = 2 * 1024 * 1024,
    private val maxRedirects: Int = 3,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpFetcher {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lets the coroutine side abort the blocking side. */
    private class Handle {
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var cancelled = false
        fun cancel() {
            cancelled = true
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // nothing to do
            }
        }
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        try {
            withTimeoutOrNull(overallTimeoutMs) { run(url, headers) } ?: fail(ErrorKind.Timeout, "overall deadline exceeded")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(ErrorKind.Unknown, "${e.javaClass.simpleName}: ${e.message}")
        }

    private suspend fun run(url: String, headers: Map<String, String>): HttpResponse =
        suspendCancellableCoroutine { cont ->
            val handle = Handle()
            cont.invokeOnCancellation { handle.cancel() }
            ioScope.launch {
                val r = try {
                    fetch(url, headers, handle)
                } catch (e: Throwable) {
                    fail(ErrorKind.Unknown, "${e.javaClass.simpleName}: ${e.message}")
                }
                if (cont.isActive) cont.resume(r)
            }
        }

    private fun fetch(startUrl: String, headers: Map<String, String>, handle: Handle): HttpResponse {
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
            handle.conn = conn
            if (handle.cancelled) {
                conn.disconnect()
                return fail(ErrorKind.Unknown, "cancelled")
            }
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
            } catch (e: UnknownHostException) {
                return fail(ErrorKind.Network, "unknown host", offline = true)
            } catch (e: NoRouteToHostException) {
                return fail(ErrorKind.Network, "no route to host", offline = true)
            } catch (e: SSLException) {
                return if (isCertificateDateError(e)) {
                    fail(ErrorKind.ClockOrTls, "certificate date check failed: check the device date and time")
                } else {
                    fail(ErrorKind.Network, "${e.javaClass.simpleName}: ${e.message}")
                }
            } catch (e: IOException) {
                val unreachable = e.message?.contains("unreachable", ignoreCase = true) == true
                return fail(ErrorKind.Network, "${e.javaClass.simpleName}: ${e.message}", offline = unreachable)
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

    private fun fail(kind: ErrorKind, message: String, offline: Boolean = false) =
        HttpResponse(code = 0, failure = FetchResult.Err(kind, message), offline = offline)

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** True when a TLS failure was caused by a certificate validity DATE (expired or not yet valid). */
        fun isCertificateDateError(t: Throwable): Boolean {
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth++ < 8) {
                if (c is CertificateExpiredException || c is CertificateNotYetValidException) return true
                val m = c.message?.lowercase(Locale.ROOT).orEmpty()
                if ("notafter" in m || "notbefore" in m || "not yet valid" in m || "certificate expired" in m || "timestamp check failed" in m) return true
                c = c.cause
            }
            return false
        }

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
