package com.marketsboard.data.net

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One network attempt. [host] only, never the URL (queries can contain what the user typed). */
data class FetchEvent(
    val timeMs: Long,
    val provider: String,
    val host: String,
    val ok: Boolean,
    /** HTTP status, or 0 for transport failures. */
    val code: Int,
    val latencyMs: Long,
    val error: String?,
)

/** Bounded ring buffer of the most recent fetches, newest last. Feeds the future diagnostics screen. */
class FetchLog(private val capacity: Int = 100) {
    private val _events = MutableStateFlow<List<FetchEvent>>(emptyList())
    val events: StateFlow<List<FetchEvent>> = _events.asStateFlow()

    fun record(event: FetchEvent) {
        _events.update { (it + event).let { l -> if (l.size > capacity) l.subList(l.size - capacity, l.size) else l } }
    }

    fun clear() {
        _events.value = emptyList()
    }
}

/** Decorator that records every request of [delegate] into [log] under [provider]. Adds no behaviour otherwise. */
class LoggingHttpFetcher(
    private val delegate: HttpFetcher,
    private val log: FetchLog,
    private val provider: String,
    private val clock: Clock = Clock.System,
) : HttpFetcher {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        val start = clock.nowMs()
        val startMono = clock.monoMs() // latency on the monotonic clock: a clock that jumps cannot make it negative or huge
        try {
            val r = delegate.get(url, headers)
            val err = r.toError()
            log.record(
                FetchEvent(start, provider, hostOf(url), err == null, r.code, (clock.monoMs() - startMono).coerceAtLeast(0), err?.let { describe(it) }),
            )
            return r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.record(FetchEvent(start, provider, hostOf(url), false, 0, (clock.monoMs() - startMono).coerceAtLeast(0), "exception ${e.javaClass.simpleName}"))
            return HttpResponse(0, failure = FetchResult.Err(ErrorKind.Unknown, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun describe(e: FetchResult.Err) = if (e.message.isBlank()) e.kind.label else "${e.kind.label}: ${e.message}"
}
