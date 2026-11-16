package com.marketsboard.data.news

import com.marketsboard.data.model.ErrorKind

enum class FeedKind { General, Symbol }

/**
 * One news source. [url] is always http or https. [symbol] (Yahoo format) is set for [FeedKind.Symbol] feeds: every
 * item of such a feed is tagged with that symbol. [auto] feeds are derived from the watchlist and never persisted.
 */
data class FeedConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val kind: FeedKind = FeedKind.General,
    val symbol: String? = null,
    val auto: Boolean = false,
)

/**
 * A headline as the app keeps it. Plain text only (no markup survives the parser). [publishedMs] is feed time; when
 * the feed gave no usable date it is the first time we saw the item and [dateEstimated] is true.
 * [feedTags] are the tags the source itself implies (a per-symbol feed); [tags] is the final set shown (feed tags plus
 * watchlist symbols or names mentioned in the text), recomputed whenever the watchlist changes.
 */
data class Headline(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val sourceName: String,
    val publishedMs: Long,
    val tags: List<String> = emptyList(),
    val feedTags: List<String> = emptyList(),
    val dateEstimated: Boolean = false,
    val feedId: String = "",
)

enum class FeedStatus {
    /** Not fetched yet in this run (cached items, if any, are shown). */
    Pending,
    Ok,
    /** The server answered 304: the held items are still current. */
    NotModified,
    Error,
}

/** Per-feed health for the diagnostics screen and the stale logic. Ages are monotonic. */
data class FeedState(
    val id: String,
    val name: String,
    val status: FeedStatus = FeedStatus.Pending,
    val itemCount: Int = 0,
    /** Time since the last success (200 or 304), null when never. */
    val lastOkAgeMs: Long? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    /** Milliseconds until the next automatic attempt when the feed is backing off, else 0. */
    val retryInMs: Long = 0,
    /** The last answer was malformed and only partly readable. */
    val partial: Boolean = false,
)

sealed interface NewsOverall {
    /** Nothing to show and no pass has finished yet. */
    data object Loading : NewsOverall

    /** At least one feed succeeded recently. */
    data object Fresh : NewsOverall

    /** Items are shown but the newest success is old (or only the cache was loaded). */
    data class Stale(val ageMs: Long) : NewsOverall

    /** Nothing to show and every feed of the last pass failed. */
    data class Error(val kind: ErrorKind) : NewsOverall
}

data class NewsState(
    val items: List<Headline> = emptyList(),
    val feedStates: List<FeedState> = emptyList(),
    val overall: NewsOverall = NewsOverall.Loading,
    val refreshing: Boolean = false,
    /** Age of the newest success across feeds, null when none in this run and no cache. */
    val lastOkAgeMs: Long? = null,
    /** True when the newest completed pass failed for every feed (so "stale" means "offline" too). */
    val lastPassFailed: Boolean = false,
    /** Server time minus device time seen on the last response: add to the device clock to get real time. */
    val serverOffsetMs: Long = 0,
)
