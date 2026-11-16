package com.marketsboard.data.net

import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult

/**
 * Result of one HTTP GET. A transport problem (no connection, timeout, oversized body...) is reported in [failure]
 * with [code] 0; an HTTP error status is a normal response with its body, so callers can read error payloads.
 * Header names are lower-cased.
 */
data class HttpResponse(
    val code: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** Parsed Retry-After in milliseconds (seconds or HTTP date), when the server sent one. */
    val retryAfterMs: Long? = null,
    val failure: FetchResult.Err? = null,
    /** True when the failure says there is no route to the internet (unknown host, no route), so another host will not help. */
    val offline: Boolean = false,
) {
    val isSuccess: Boolean get() = failure == null && code in 200..299

    /** The typed error for this response, or null when it is a 2xx. */
    fun toError(): FetchResult.Err? = when {
        failure != null -> failure
        code in 200..299 -> null
        code == 429 -> FetchResult.Err(ErrorKind.RateLimited(retryAfterMs), "HTTP 429")
        code == 404 -> FetchResult.Err(ErrorKind.NotFound, "HTTP 404")
        else -> FetchResult.Err(ErrorKind.Http(code), "HTTP $code")
    }
}

/** Minimal HTTP seam. Implementations must never throw (except coroutine cancellation). */
interface HttpFetcher {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

/** Host part of a URL for logs. Never returns the path or query (they can contain what the user searched for). */
fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"
