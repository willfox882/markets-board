package com.marketsboard.data.news

import com.marketsboard.data.net.HttpFetcher
import com.marketsboard.data.net.HttpResponse
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 2026-11-16 18:00:00 UTC, the "now" of the parser fixtures. */
val NOW_MS: Long = Instant.parse("2026-11-16T18:00:00Z").toEpochMilli()

fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

class FakeFeedSource(feeds: List<FeedConfig>, symbolFeeds: Boolean = false) : FeedSource {
    val feedFlow = MutableStateFlow(feeds)
    val symFlow = MutableStateFlow(symbolFeeds)
    override val feeds: StateFlow<List<FeedConfig>> = feedFlow
    override val symbolFeeds: StateFlow<Boolean> = symFlow
}

fun feed(id: String, url: String = "https://example.com/$id.xml", name: String = id.uppercase(), enabled: Boolean = true) = FeedConfig(id, name, url, enabled)

/** RSS 2.0 text with the given items: (title, link, pubDate or null, description or null). */
fun rssXml(vararg items: Quad, title: String = "Test feed"): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\"><channel><title>").append(title).append("</title>\n")
    for (i in items) {
        append("<item><title>").append(i.title).append("</title><link>").append(i.link).append("</link>")
        i.date?.let { append("<pubDate>").append(it).append("</pubDate>") }
        i.desc?.let { append("<description>").append(it).append("</description>") }
        append("</item>\n")
    }
    append("</channel></rss>")
}

data class Quad(val title: String, val link: String, val date: String? = null, val desc: String? = null)

fun rfc(ms: Long): String = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC))

/** Fetcher with per-call delay and a record of how many calls ran at once. */
class DelayFetcher(private val delayMs: Long, private val handler: (String, Map<String, String>) -> HttpResponse) : HttpFetcher {
    var active = 0
    var maxActive = 0
    val urls = mutableListOf<String>()
    val sentHeaders = mutableListOf<Map<String, String>>()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        urls.add(url)
        sentHeaders.add(headers)
        active++
        if (active > maxActive) maxActive = active
        try {
            delay(delayMs)
            return handler(url, headers)
        } finally {
            active--
        }
    }
}
