package com.marketsboard.data.news

import com.marketsboard.core.NewsText
import com.marketsboard.data.Clock
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.HttpFetcher
import com.marketsboard.data.net.HttpResponse
import com.marketsboard.data.store.FileJsonCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Tunables of [NewsRepository]. Defaults suit a parked head unit: a pass every 10 minutes (the poller's job). */
class NewsConfig(
    val maxConcurrent: Int = 3,
    val maxItems: Int = 120,
    val maxAgeMs: Long = 7L * 86_400_000L,
    /** No success for this long: the list is Stale. Two and a half poll periods. */
    val staleAfterMs: Long = 25L * 60_000L,
    /** A non-forced pass skips feeds attempted less than this long ago. */
    val minGapMs: Long = 60_000L,
    val backoffBaseMs: Long = 60_000L,
    val backoffMaxMs: Long = 30L * 60_000L,
    val feedTimeoutMs: Long = 25_000L,
    val cacheTtlMs: Long = 7L * 86_400_000L + 3_600_000L,
    /** Device clock differences up to this are ignored when estimating server time (Date headers are coarse and CDNs lag). */
    val offsetToleranceMs: Long = 5L * 60_000L,
    /** Timeouts and "no network" failures never wait longer than one poll period: the hotspot may be back any minute. */
    val networkBackoffMaxMs: Long = 10L * 60_000L,
)

/**
 * News aggregation: fetch every effective feed (at most [NewsConfig.maxConcurrent] at once) through an [HttpFetcher],
 * parse, merge and publish [state].
 *
 * - **Stale-while-revalidate**: [hydrate] loads each feed's last good answer from the cache so a restart shows
 *   headlines immediately (as Stale, with their age) while the first pass runs.
 * - **Conditional GET**: ETag and Last-Modified of the last good answer are sent back; a 304 keeps the held items.
 * - **Per-feed health**: ok / not modified / error, item count, consecutive failures; failures back off exponentially
 *   (1, 2, 4 ... min, capped at 30 min, Retry-After honoured). A feed whose failure says "no network" does NOT escalate
 *   (the next poll or the network signal retries), and the rest of that pass is failed at once instead of waiting
 *   for each timeout.
 * - **Merge**: the 7-day window, canonical-URL and near-duplicate title dedupe (see [Dedupe]), newest first, capped.
 * - **Relevance**: tags come from the CURRENT watchlist and are recomputed whenever it changes. Text matches win; a per-symbol
 *   feed's own tag is used only for items whose text names nothing on the watchlist.
 * - **Time**: ages and backoff use the monotonic clock; a feed date more than a day ahead is replaced by the fetch time,
 *   and when the response carries a `Date` header the device clock error is measured against it ([NewsState.serverOffsetMs]).
 * Nothing here throws except coroutine cancellation.
 */
class NewsRepository(
    private val feedSource: FeedSource,
    private val watch: StateFlow<List<WatchItem>>,
    private val fetcher: HttpFetcher,
    private val cache: FileJsonCache,
    private val clock: Clock = Clock.System,
    private val config: NewsConfig = NewsConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NewsSource {
    private class Rt(
        var snapshot: FeedSnapshot? = null,
        var status: FeedStatus = FeedStatus.Pending,
        var lastOkMono: Long? = null,
        var lastAttemptMono: Long? = null,
        var failures: Int = 0,
        var nextTryMono: Long = 0,
        var lastError: String? = null,
        var lastKind: ErrorKind? = null,
        var partial: Boolean = false,
    )

    private class Pass { @Volatile var offline: ErrorKind? = null; @Volatile var attempted = 0; @Volatile var succeeded = 0 }

    private val lock = Any()
    private val rt = HashMap<String, Rt>()
    private val gate = Semaphore(config.maxConcurrent.coerceAtLeast(1))
    private val passMutex = Mutex()
    private var passesCompleted = 0
    /** A success (200 or 304) happened in this process: only then can the list be called Fresh. */
    private var confirmed = false
    private var lastPassFailed = false
    private var serverOffsetMs = 0L
    private val offsets = HashMap<String, Long>()
    private var matcher: SymbolMatcher? = null
    private var matcherFor: List<WatchItem>? = null

    private val _state = MutableStateFlow(NewsState())
    override val state: StateFlow<NewsState> = _state.asStateFlow()

    private fun effective(): List<FeedConfig> = FeedPlan.effective(feedSource.feeds.value, watch.value, feedSource.symbolFeeds.value)

    private fun cacheKey(id: String) = "news.feed.$id"

    /** Wall time corrected by the last measured device clock error. */
    override fun correctedNowMs(): Long = clock.nowMs() + synchronized(lock) { serverOffsetMs }

    // ---- cache -------------------------------------------------------------------------------------------------

    /** Loads the cached answer of every effective feed that has nothing in memory yet, and publishes it as Stale. */
    suspend fun hydrate() {
        val feeds = effective()
        for (f in feeds) {
            if (synchronized(lock) { rt[f.id]?.snapshot != null }) continue
            val entry = withContext(ioDispatcher) { runCatching { cache.get(cacheKey(f.id), allowExpired = true) }.getOrNull() } ?: continue
            val snap = NewsCodec.decode(entry.payload)
            if (snap == null) {
                withContext(ioDispatcher) { runCatching { cache.remove(cacheKey(f.id)) } }
                continue
            }
            val okAt = if (snap.okAtMs > 0) snap.okAtMs else entry.savedAtMs
            // A negative age means the device clock is behind the time the cache was written (a head unit that booted with a
            // wrong date): the age is unknown, so the list is Stale until a pass succeeds, never "Updated just now".
            val rawAge = clock.nowMs() - okAt
            synchronized(lock) {
                val r = rt.getOrPut(f.id) { Rt() }
                if (r.snapshot == null) {
                    r.snapshot = snap
                    r.lastOkMono = if (rawAge < 0) null else clock.monoMs() - rawAge
                }
            }
        }
        publish()
    }

    /** The watchlist or the feed list changed: forget what is no longer configured, retag, republish. */
    suspend fun onConfigChanged() {
        prune(effective())
        publish()
    }

    private suspend fun prune(feeds: List<FeedConfig>) {
        val keep = feeds.map { it.id }.toSet()
        val gone = synchronized(lock) { rt.keys.filter { it !in keep }.also { g -> g.forEach { rt.remove(it) } } }
        if (gone.isNotEmpty()) withContext(ioDispatcher) { gone.forEach { runCatching { cache.remove(cacheKey(it)) } } }
    }

    // ---- refresh -----------------------------------------------------------------------------------------------

    private class Request(val force: Boolean, val onlyFailed: Boolean) {
        fun merge(o: Request) = Request(force || o.force, onlyFailed && o.onlyFailed)
    }

    private var pending: Request? = null

    /**
     * One pass over the feeds that are due. [force] (manual Refresh) ignores the minimum gap and backoff;
     * [onlyFailed] (network came back) retries feeds that failed or never answered, ignoring backoff.
     * A request that arrives while a pass is running is not dropped: it is merged into one follow-up pass that runs as
     * soon as the current one ends (so a symbol added, the network returning or a resume during a pass still fetches).
     */
    suspend fun refresh(force: Boolean = false, onlyFailed: Boolean = false) {
        synchronized(lock) { pending = pending?.merge(Request(force, onlyFailed)) ?: Request(force, onlyFailed) }
        while (true) {
            if (!passMutex.tryLock()) return // the running pass will pick the request up
            try {
                while (true) {
                    val req = synchronized(lock) { pending.also { pending = null } } ?: break
                    try {
                        runPass(req)
                    } catch (e: CancellationException) {
                        // The pass was cut short (app paused): keep the request for whoever runs next.
                        synchronized(lock) { pending = pending?.merge(req) ?: req }
                        throw e
                    }
                }
            } finally {
                passMutex.unlock()
            }
            // A request may have arrived between the last check and the unlock.
            if (synchronized(lock) { pending == null }) return
        }
    }

    private suspend fun runPass(req: Request) {
        val force = req.force
        val onlyFailed = req.onlyFailed
        val feeds = effective()
        prune(feeds)
        hydrate()
        val pass = Pass()
        val now = clock.monoMs()
        val due = synchronized(lock) {
            feeds.filter { f ->
                val r = rt.getOrPut(f.id) { Rt() }
                val last = r.lastAttemptMono
                when {
                    force -> true
                    onlyFailed -> (r.lastError != null || last == null) && (last == null || now - last >= 5_000)
                    else -> (last == null || now - last >= config.minGapMs) && now >= r.nextTryMono
                }
            }
        }
        if (due.isEmpty()) { publish(); return }
        setRefreshing(true)
        try {
            coroutineScope { due.map { f -> async { gate.withPermit { fetchOne(f, pass) } } }.awaitAll() }
        } finally {
            setRefreshing(false)
        }
        synchronized(lock) {
            passesCompleted++
            lastPassFailed = pass.attempted > 0 && pass.succeeded == 0
        }
        publish()
    }

    private var refreshingNow = false

    private fun setRefreshing(on: Boolean) {
        synchronized(lock) { refreshingNow = on }
        publish()
    }

    private suspend fun fetchOne(f: FeedConfig, pass: Pass) {
        val offline = pass.offline
        if (offline != null) {
            // The pass already learned there is no network: do not wait for another timeout, do not escalate backoff.
            synchronized(lock) { rt.getOrPut(f.id) { Rt() }.apply { status = FeedStatus.Error; lastError = NewsText.feedError(offline); lastKind = offline } }
            publish()
            return
        }
        val snap = synchronized(lock) { rt.getOrPut(f.id) { Rt() }.snapshot }
        val headers = HashMap<String, String>()
        headers["Accept"] = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5"
        if (snap != null && snap.items.isNotEmpty()) {
            snap.etag?.let { headers["If-None-Match"] = it }
            snap.lastModified?.let { headers["If-Modified-Since"] = it }
        }
        pass.attempted++
        val resp: HttpResponse = try {
            withTimeoutOrNull(config.feedTimeoutMs) { fetcher.get(f.url, headers) }
                ?: HttpResponse(0, failure = FetchResult.Err(ErrorKind.Timeout, "feed deadline"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            HttpResponse(0, failure = FetchResult.Err(ErrorKind.Unknown, e.javaClass.simpleName))
        }
        try {
            handle(f, resp, snap, pass)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(f, FetchResult.Err(ErrorKind.Unknown, "${e.javaClass.simpleName}"), false, null, pass)
        }
        // Only a finished attempt counts as one: a pass cut short by a pause leaves the feed due, so resuming retries it.
        synchronized(lock) { rt[f.id]?.lastAttemptMono = clock.monoMs() }
        publish()
    }

    private suspend fun handle(f: FeedConfig, resp: HttpResponse, held: FeedSnapshot?, pass: Pass) {
        val err = resp.toError()
        if (resp.code == 304 && held != null) {
            observeServerDate(f.id, resp)
            synchronized(lock) {
                rt.getValue(f.id).apply {
                    this@NewsRepository.confirmed = true; status = FeedStatus.NotModified; lastOkMono = clock.monoMs(); failures = 0; nextTryMono = 0; lastError = null; lastKind = null; partial = false
                    snapshot = held.copy(okAtMs = clock.nowMs())
                }
            }
            pass.succeeded++
            withContext(ioDispatcher) { runCatching { cache.put(cacheKey(f.id), NewsCodec.encode(synchronized(lock) { rt.getValue(f.id).snapshot!! }), config.cacheTtlMs) } }
            return
        }
        if (err != null || resp.code == 304) {
            fail(f, err ?: FetchResult.Err(ErrorKind.Parse, "unexpected 304"), resp.offline, (err?.kind as? ErrorKind.RateLimited)?.retryAfterMs ?: resp.retryAfterMs, pass)
            return
        }
        val ref = observeServerDate(f.id, resp)
        val parsed = withContext(ioDispatcher) {
            val bytes = resp.bytes
            if (bytes != null) FeedParser.parse(bytes, ref, charsetOf(resp.headers["content-type"])) else FeedParser.parseText(resp.body, ref)
        }
        when (parsed) {
            is FetchResult.Err -> fail(f, parsed, false, null, pass)
            is FetchResult.Ok -> {
                val items = toHeadlines(f, parsed.value, held, ref)
                val snap = FeedSnapshot(items, resp.headers["etag"]?.take(300), resp.headers["last-modified"]?.take(100), clock.nowMs())
                synchronized(lock) {
                    rt.getValue(f.id).apply {
                        this@NewsRepository.confirmed = true; status = FeedStatus.Ok; lastOkMono = clock.monoMs(); failures = 0; nextTryMono = 0; lastError = null; lastKind = null
                        partial = parsed.value.partial; snapshot = snap
                    }
                }
                pass.succeeded++
                withContext(ioDispatcher) { runCatching { cache.put(cacheKey(f.id), NewsCodec.encode(snap), config.cacheTtlMs) } }
            }
        }
    }

    private fun toHeadlines(f: FeedConfig, feed: ParsedFeed, held: FeedSnapshot?, ref: Long): List<Headline> {
        val label = f.symbol?.let { labelForSymbol(it) }
        val previous = held?.items?.associateBy { it.id }.orEmpty()
        val oldest = ref - config.maxAgeMs
        val out = ArrayList<Headline>()
        for (p in feed.items) {
            val id = Dedupe.idFor(p.url)
            val before = previous[id]
            // No usable date: keep the time we first saw the item, so it ages out like any other.
            val published = p.publishedMs ?: before?.publishedMs ?: ref
            val estimated = p.publishedMs == null || p.dateReplaced
            if (published < oldest) continue
            out.add(
                Headline(
                    id = id, title = p.title, summary = p.summary, url = p.url, sourceName = f.name, publishedMs = if (p.dateReplaced) before?.publishedMs ?: published else published,
                    tags = emptyList(), feedTags = listOfNotNull(label), dateEstimated = estimated, feedId = f.id,
                ),
            )
        }
        return out.distinctBy { it.id }.sortedByDescending { it.publishedMs }.take(FeedParser.MAX_ITEMS)
    }

    private fun labelForSymbol(symbol: String): String {
        val item = watch.value.firstOrNull { it.symbol == symbol } ?: WatchItem(symbol, symbol, "", "")
        return Relevance.labelOf(item)
    }

    /**
     * Records how far the server's clock is from the device's (the response `Date` plus its `Age`, when a cache served it)
     * and returns the corrected "now" for this response. The offset is the MEDIAN over feeds, so one server with a wrong
     * clock cannot move it, and only skew above the tolerance counts.
     */
    private fun observeServerDate(feedId: String, resp: HttpResponse): Long {
        val date = FeedDates.parse(resp.headers["date"])
        if (date != null) {
            val ageMs = (resp.headers["age"]?.trim()?.toLongOrNull() ?: 0L).coerceIn(0L, 86_400L) * 1000L
            synchronized(lock) {
                offsets[feedId] = date + ageMs - clock.nowMs()
                val sorted = offsets.values.sorted()
                val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
                serverOffsetMs = if (Math.abs(median) > config.offsetToleranceMs) median else 0L
            }
        }
        return clock.nowMs() + synchronized(lock) { serverOffsetMs }
    }

    private fun fail(f: FeedConfig, err: FetchResult.Err, offline: Boolean, retryAfterMs: Long?, pass: Pass) {
        synchronized(lock) {
            rt.getValue(f.id).apply {
                status = FeedStatus.Error
                failures++
                lastKind = err.kind
                lastError = NewsText.feedError(err.kind)
                val cap = if (err.kind == ErrorKind.Network || err.kind == ErrorKind.Timeout) config.networkBackoffMaxMs else config.backoffMaxMs
                val exp = (config.backoffBaseMs shl (failures - 1).coerceIn(0, 20)).coerceAtMost(cap)
                val delay = when {
                    offline -> 0L
                    retryAfterMs != null -> maxOf(retryAfterMs, exp).coerceAtMost(config.backoffMaxMs)
                    else -> exp
                }
                nextTryMono = if (delay == 0L) 0L else clock.monoMs() + delay
            }
        }
        if (offline) pass.offline = err.kind
    }

    // ---- state -------------------------------------------------------------------------------------------------

    /** Recomputes ages (Fresh to Stale) without touching the network. Call it every 30 s or so. */
    fun recompute() = publish()

    private fun matcherNow(): SymbolMatcher {
        val items = watch.value
        synchronized(lock) {
            if (matcherFor != items || matcher == null) { matcher = SymbolMatcher(items); matcherFor = items }
            return matcher!!
        }
    }

    private fun publish() {
        val feeds = effective()
        val m = matcherNow()
        val watchLabels = watch.value.map { Relevance.labelOf(it) }.toSet()
        val mono = clock.monoMs()
        var offset: Long
        val states = ArrayList<FeedState>()
        val entries = ArrayList<Dedupe.Entry>()
        var lastOk: Long? = null
        var passDone: Boolean
        var failed: Boolean
        var refreshing: Boolean
        val kinds = ArrayList<ErrorKind>()
        var confirmedNow: Boolean
        synchronized(lock) {
            offset = serverOffsetMs
            passDone = passesCompleted > 0
            failed = lastPassFailed
            refreshing = refreshingNow
            confirmedNow = confirmed
            feeds.forEachIndexed { rank, f ->
                val r = rt[f.id]
                states.add(
                    FeedState(
                        f.id, f.name, r?.status ?: FeedStatus.Pending, r?.snapshot?.items?.size ?: 0, r?.lastOkMono?.let { (mono - it).coerceAtLeast(0) },
                        r?.lastError, r?.failures ?: 0, r?.let { (it.nextTryMono - mono).coerceAtLeast(0) } ?: 0, r?.partial ?: false,
                    ),
                )
                r?.lastOkMono?.let { if (lastOk == null || it > lastOk!!) lastOk = it }
                if (r?.lastError != null) r.lastKind?.let { kinds.add(it) }
                val oldest = clock.nowMs() + offset - config.maxAgeMs
                r?.snapshot?.items?.forEach { h ->
                    if (h.publishedMs >= oldest) {
                        // What the text says wins; the symbol feed's own tag only fills in when the text names nothing on the watchlist
                        // (so a Microsoft story that arrives through the XEQT feed is a Microsoft story, not an XEQT one).
                        val fromFeed = h.feedTags.filter { it in watchLabels }
                        val tags = m.match("${h.title} ${h.summary}").ifEmpty { fromFeed }.take(3)
                        entries.add(Dedupe.Entry(h.copy(tags = tags, feedTags = fromFeed), rank))
                    }
                }
            }
        }
        val items = Dedupe.merge(entries).sortedWith(compareByDescending<Headline> { it.publishedMs }.thenBy { it.id }).take(config.maxItems)
        val age = lastOk?.let { (mono - it).coerceAtLeast(0) }
        val dominant = dominantKind(kinds)
        val overall: NewsOverall = when {
            items.isEmpty() && age == null -> if (!passDone) NewsOverall.Loading else NewsOverall.Error(dominant ?: ErrorKind.Network)
            age == null -> NewsOverall.Stale(0, ageKnown = false) // held items whose age cannot be trusted (clock behind the cache)
            confirmedNow && age <= config.staleAfterMs -> NewsOverall.Fresh
            else -> NewsOverall.Stale(age)
        }
        _state.value = NewsState(items, states, overall, refreshing, age, failed, offset, dominant)
    }
}

/** The failure most feeds share (ties go to the more actionable kind), so the banner can tell the truth. */
internal fun dominantKind(kinds: List<ErrorKind>): ErrorKind? {
    if (kinds.isEmpty()) return null
    fun rank(k: ErrorKind) = when (k) { ErrorKind.ClockOrTls -> 0; is ErrorKind.RateLimited -> 1; ErrorKind.Network -> 2; ErrorKind.Timeout -> 3; ErrorKind.Parse -> 4; is ErrorKind.Http -> 5; else -> 6 }
    return kinds.groupBy { it.label }.values.sortedWith(compareByDescending<List<ErrorKind>> { it.size }.thenBy { rank(it.first()) }).first().first()
}

private fun charsetOf(contentType: String?): String? =
    contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
