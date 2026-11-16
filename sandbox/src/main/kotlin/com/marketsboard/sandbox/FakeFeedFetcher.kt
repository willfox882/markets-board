package com.marketsboard.sandbox

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.net.HttpFetcher
import com.marketsboard.data.net.HttpResponse
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** What the fake news servers do on the next calls (the sandbox panel switches it). */
enum class FakeNewsBehavior { Normal, Slow, Offline, MalformedFeeds, OneFeedFailing }

/**
 * A stand-in for the news hosts: serves realistic RSS 2.0 and Atom documents for the default feeds and the per-symbol
 * Yahoo feeds, with timestamps relative to [clock] (rounded to 5 minutes so ETag and 304 behave like a real server).
 * The stories overlap across feeds (same story with a different title wording, same URL with tracking parameters) and
 * several mention the default watchlist (Microsoft, XEQT, iShares), so dedupe and "My symbols" have something to do.
 * [addBreaking] injects a fresh headline into the CBC and Yahoo feeds.
 */
class FakeFeedFetcher(
    private val clock: Clock = Clock.System,
    @Volatile var behavior: FakeNewsBehavior = FakeNewsBehavior.Normal,
    @Volatile var slowDelayMs: Long = 6_000,
) : HttpFetcher {
    @Volatile var calls = 0
        private set
    @Volatile var notModified = 0
        private set

    private val breaking = ArrayList<Story>()
    private var breakingCount = 0

    /** Version number that changes the ETag when a breaking headline is added. */
    @Volatile private var epoch = 0

    private class Story(
        val key: String,
        /** Title per feed id; a feed without an entry does not carry the story. */
        val titles: Map<String, String>,
        val ageMin: Int,
        val summaryHtml: String,
        val path: String,
        /** Breaking headlines are stamped with the moment they were added, not the 5-minute bucket. */
        val fixedAt: Long? = null,
    ) {
        fun at(bucket: Long): Long = fixedAt ?: (bucket - ageMin * 60_000L)
    }

    fun addBreaking(): String {
        val titles = listOf(
            "Bank of Canada announces surprise rate decision", "Microsoft announces major AI partnership", "TSX jumps as oil surges on supply scare",
            "Canadian dollar rallies after strong jobs report", "Markets slide as bond yields spike",
        )
        val t = titles[breakingCount % titles.size]
        breakingCount++
        epoch++
        synchronized(breaking) {
            breaking.add(0, Story("breaking$breakingCount", mapOf("cbc-business" to t, "yahoo-top" to t, "marketwatch-top" to t), 0, "<p>$t. This is a developing story.</p>", "breaking-$breakingCount", clock.nowMs()))
        }
        return t
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        calls++
        val uri = try { URI(url) } catch (e: Exception) { return HttpResponse(0, failure = FetchResult.Err(ErrorKind.Unknown, "bad url")) }
        val host = uri.host.orEmpty()
        when (behavior) {
            FakeNewsBehavior.Offline -> return HttpResponse(0, failure = FetchResult.Err(ErrorKind.Network, "fake: offline"), offline = true)
            FakeNewsBehavior.Slow -> delay(slowDelayMs)
            else -> delay(60)
        }
        val feedId = feedIdOf(host, uri)
        if (feedId == null) return HttpResponse(404, "not found")
        if (behavior == FakeNewsBehavior.OneFeedFailing && feedId == "cbc-business") return HttpResponse(503, "Service Unavailable")
        if (behavior == FakeNewsBehavior.MalformedFeeds) {
            if (feedId == "yahoo-top") return html("Sign in to the hotspot")
            if (feedId == "globe-business") return respond(truncated(feedId), headers, partial = true)
        }
        return respond(body(feedId, uri), headers, partial = false)
    }

    private fun html(title: String) = HttpResponse(
        200, "<!DOCTYPE html><html><head><title>$title</title></head><body><form>Log in</form></body></html>",
        mapOf("content-type" to "text/html; charset=utf-8", "date" to rfc1123(clock.nowMs())),
    )

    private fun respond(xml: String, req: Map<String, String>, partial: Boolean): HttpResponse {
        val etag = "\"" + sha(xml).take(16) + "\""
        val date = rfc1123(clock.nowMs())
        if (!partial && req["If-None-Match"] == etag) {
            notModified++
            return HttpResponse(304, "", mapOf("etag" to etag, "date" to date))
        }
        val type = if (xml.contains("<feed ")) "application/atom+xml; charset=utf-8" else "application/rss+xml; charset=utf-8"
        return HttpResponse(200, xml, mapOf("content-type" to type, "etag" to etag, "date" to date, "last-modified" to rfc1123(bucket())), bytes = xml.toByteArray(Charsets.UTF_8))
    }

    private fun feedIdOf(host: String, uri: URI): String? = when {
        host.endsWith("cbc.ca") -> "cbc-business"
        host.endsWith("theglobeandmail.com") -> "globe-business"
        host.endsWith("financialpost.com") -> "financial-post"
        host == "finance.yahoo.com" -> "yahoo-top"
        host.endsWith("dowjones.io") -> "marketwatch-top"
        host == "feeds.finance.yahoo.com" -> "sym:" + (query(uri)["s"] ?: "")
        else -> null
    }

    private fun query(uri: URI): Map<String, String> = (uri.rawQuery ?: "").split('&').filter { it.contains('=') }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun bucket(): Long = clock.nowMs() / 300_000L * 300_000L

    private fun stories(): List<Story> = synchronized(breaking) { breaking.toList() } + STORIES

    private fun body(feedId: String, uri: URI): String = when {
        feedId == "cbc-business" -> rss("CBC | Business News", "https://www.cbc.ca/news/business", "cbc-business", rfcStyle = 0)
        feedId == "financial-post" -> rss("Financial Post", "https://financialpost.com", "financial-post", rfcStyle = 1)
        feedId == "yahoo-top" -> rss("Yahoo Finance", "https://finance.yahoo.com/news", "yahoo-top", rfcStyle = 1)
        feedId == "marketwatch-top" -> rss("MarketWatch.com - Top Stories", "https://www.marketwatch.com", "marketwatch-top", rfcStyle = 2)
        feedId == "globe-business" -> atom("The Globe and Mail: Business", "globe-business")
        feedId.startsWith("sym:") -> symbolFeed(feedId.removePrefix("sym:"))
        else -> rss("Empty", "https://example.com", "none", 0)
    }

    private fun truncated(feedId: String): String {
        val full = atom("The Globe and Mail: Business", feedId)
        // Cut in the middle of the fifth entry: the first four entries are salvageable.
        val cut = Regex("<entry>").findAll(full).drop(4).firstOrNull()?.range?.first ?: (full.length / 2)
        return full.substring(0, cut + 120)
    }

    private fun symbolFeed(symbol: String): String {
        val base = symbol.substringBefore('.').uppercase(Locale.ROOT)
        val needle = when (base) { "MSFT" -> "microsoft"; "XEQT" -> "xeqt"; else -> base.lowercase(Locale.ROOT) }
        val mine = stories().filter { s -> s.titles.values.any { it.lowercase(Locale.ROOT).contains(needle) } || (needle == "xeqt" && s.titles.values.any { it.contains("ishares", true) }) }
        val extra = stories().filter { it.titles.containsKey("yahoo-top") && it !in mine }.take(2)
        val chosen = (mine + extra).distinct()
        val items = chosen.joinToString("\n") { s ->
            item(
                s.titles["yahoo-top"] ?: s.titles.values.first(), "https://finance.yahoo.com/news/${s.path}.html?.tsrc=rss&guccounter=1", "sym-${s.key}",
                date(s, 1), s.summaryHtml,
            )
        }
        return rssDoc("Yahoo! Finance: $symbol News", "https://finance.yahoo.com/q/h?s=$symbol", items)
    }

    private fun rss(title: String, link: String, feedId: String, rfcStyle: Int): String {
        val items = stories().filter { it.titles.containsKey(feedId) }.joinToString("\n") { s ->
            val href = when (feedId) {
                "cbc-business" -> "https://www.cbc.ca/news/business/${s.path}-1.7${abs(s.key)}"
                "yahoo-top" -> "https://finance.yahoo.com/news/${s.path}.html"
                "financial-post" -> "https://financialpost.com/news/economy/${s.path}"
                else -> "https://www.marketwatch.com/story/${s.path}-${abs(s.key)}?mod=mw_rss_topstories&utm_source=rss"
            }
            item(s.titles.getValue(feedId), href, "$feedId-${s.key}", date(s, rfcStyle), s.summaryHtml, extra = if (rfcStyle == 2) "<dc:creator>Staff</dc:creator>" else "")
        }
        return rssDoc(title, link, items, ns = if (rfcStyle == 2) " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:media=\"http://search.yahoo.com/mrss/\"" else "")
    }

    private fun abs(s: String) = Math.abs(s.hashCode() % 90_000) + 10_000

    private fun rssDoc(title: String, link: String, items: String, ns: String = "") = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"$ns>
<channel>
<title>$title</title>
<link>$link</link>
<description>$title</description>
<language>en-ca</language>
<lastBuildDate>${rfc1123(bucket())}</lastBuildDate>
$items
</channel>
</rss>
"""

    private fun item(title: String, link: String, guid: String, pubDate: String, html: String, extra: String = "") = """<item>
<title>${esc(title)}</title>
<link>$link</link>
<guid isPermaLink="false">$guid</guid>
<pubDate>$pubDate</pubDate>
<description><![CDATA[$html]]></description>
$extra
</item>"""

    private fun atom(title: String, feedId: String): String {
        val entries = stories().filter { it.titles.containsKey(feedId) }.joinToString("\n") { s ->
            val ts = iso(s)
            """<entry>
<id>tag:theglobeandmail.com,2026:${s.key}</id>
<title type="html">${esc(s.titles.getValue(feedId))}</title>
<link rel="alternate" type="text/html" href="https://www.theglobeandmail.com/business/article-${s.path}/"/>
<link rel="self" href="https://www.theglobeandmail.com/arc/outboundfeeds/rss/${s.key}"/>
<published>$ts</published>
<updated>$ts</updated>
<summary type="html">${esc(s.summaryHtml)}</summary>
</entry>"""
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
<title>$title</title>
<link rel="alternate" href="https://www.theglobeandmail.com/business/"/>
<updated>${iso(null)}</updated>
<id>tag:theglobeandmail.com,2026:business</id>
$entries
</feed>
"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // Dates: style 0 RFC 822 with GMT, 1 numeric offset, 2 two-digit-free "EST".
    private fun date(story: Story, style: Int): String {
        val t = Instant.ofEpochMilli(story.at(bucket()))
        return when (style) {
            0 -> DateTimeFormatter.RFC_1123_DATE_TIME.format(t.atZone(ZoneOffset.UTC))
            1 -> DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(t.atZone(ZoneOffset.ofHours(-5)))
            else -> DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss", Locale.US).format(t.atZone(ZoneOffset.ofHours(-5))) + " EST"
        }
    }

    private fun iso(story: Story?) = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(story?.at(bucket()) ?: bucket()).atZone(ZoneOffset.UTC))

    private fun rfc1123(ms: Long) = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val STORIES: List<Story> = listOf(
            Story(
                "boc", mapOf("cbc-business" to "Bank of Canada holds key rate at 2.25 per cent as inflation cools", "globe-business" to "Bank of Canada holds rate steady as inflation cools, governor says"),
                35, "<p>The central bank left its policy rate unchanged on Wednesday and said inflation is moving back toward its two per cent target.</p><p><a href=\"https://example.com\">Read more</a></p>",
                "bank-of-canada-holds-rate",
            ),
            Story(
                "tsx", mapOf("cbc-business" to "TSX edges higher as energy and bank shares lead gains", "globe-business" to "TSX edges higher as energy, financial stocks lead the way"),
                70, "<p>The S&amp;P/TSX composite index rose 62 points to 25,412 in late morning trading.</p>", "tsx-edges-higher",
            ),
            Story(
                "msft1", mapOf("yahoo-top" to "Microsoft unveils new AI features for Windows and Office", "marketwatch-top" to "Microsoft rolls out new AI tools across Windows and Office"),
                95, "<p><img src=\"https://example.com/x.jpg\"/>Microsoft Corp. said the features begin rolling out to business customers this month.</p>", "microsoft-new-ai-features",
            ),
            Story(
                "msftca", mapOf("cbc-business" to "Microsoft expands Canadian cloud region with new Toronto data centre"),
                110, "<p>The new region is due to open in 2027 and will keep customer data inside the country.</p>", "microsoft-toronto-region",
            ),
            Story(
                "msft2", mapOf("yahoo-top" to "Microsoft stock slides as investors weigh cloud spending", "marketwatch-top" to "Microsoft stock slides as investors weigh heavy cloud spending"),
                140, "<p>Shares of Microsoft fell as analysts questioned how quickly its data-centre investments will pay off.</p>", "microsoft-stock-slides",
            ),
            Story(
                "xeqt", mapOf("globe-business" to "Why XEQT and other all-in-one ETFs are drawing record inflows", "yahoo-top" to "All-in-one ETFs such as XEQT see record inflows from Canadian investors"),
                190, "<p>Do-it-yourself investors are choosing one-ticket portfolios, and XEQT from iShares is the most popular.</p>", "xeqt-all-in-one-etfs",
            ),
            Story(
                "ishares", mapOf("yahoo-top" to "iShares adds new low-cost funds to its Canadian lineup"),
                260, "<p>BlackRock's iShares said the new exchange-traded funds will list on the Toronto Stock Exchange next week.</p>", "ishares-new-funds",
            ),
            Story(
                "cad", mapOf("cbc-business" to "Canadian dollar steady near 72 cents US ahead of jobs data", "financial-post" to "Loonie steady near 72 US cents as traders await jobs report"),
                310, "<p>The loonie traded at 72.1 cents US as investors waited for Friday's labour report.</p>", "canadian-dollar-steady",
            ),
            Story(
                "loblaw", mapOf("globe-business" to "Loblaw raises dividend after stronger-than-expected quarterly profit", "financial-post" to "Loblaw lifts dividend as grocery sales beat expectations"),
                380, "<p>The grocer said same-store sales rose 3.4 per cent.</p>", "loblaw-dividend",
            ),
            Story(
                "oil", mapOf("globe-business" to "Oil prices slip as OPEC+ signals higher output next year", "financial-post" to "Oil slides as OPEC+ eyes bigger output in 2027", "marketwatch-top" to "Oil slips as OPEC+ hints at higher output in 2027", "yahoo-top" to "Oil falls as OPEC+ signals more supply next year"),
                430, "<p>West Texas Intermediate fell 1.2 per cent to $61.40 US a barrel.</p>", "oil-prices-slip",
            ),
            Story(
                "nasdaq", mapOf("yahoo-top" to "Nasdaq falls as chip stocks weigh on tech", "marketwatch-top" to "Stock market today: Nasdaq falls as chip stocks drag tech lower"),
                520, "<p>The Nasdaq Composite lost 0.8 per cent while the Dow was little changed.</p>", "nasdaq-falls",
            ),
            Story(
                "cpi", mapOf("marketwatch-top" to "U.S. inflation report keeps Fed rate-cut bets alive", "yahoo-top" to "US inflation report keeps rate-cut hopes alive for December"),
                600, "<p>Consumer prices rose 0.2 per cent in October, matching forecasts.</p>", "us-inflation-report",
            ),
            Story(
                "statcan", mapOf("cbc-business" to "Statistics Canada says unemployment rate held at 6.6 per cent", "financial-post" to "Canada adds 14,000 jobs in October as jobless rate holds at 6.6%"),
                700, "<p>The economy added 14,000 jobs in October, mostly in health care and education.</p>", "statcan-jobs",
            ),
            Story(
                "gold", mapOf("marketwatch-top" to "Gold climbs to a record as investors seek safety", "yahoo-top" to "Gold hits record high as investors look for a safe haven"),
                820, "<p>Gold futures settled at $4,120.60 an ounce.</p>", "gold-record",
            ),
            Story(
                "btc", mapOf("marketwatch-top" to "Bitcoin slips below $100,000 as traders cut leverage"),
                960, "<p>The largest cryptocurrency fell 3 per cent in 24 hours.</p>", "bitcoin-slips",
            ),
            Story(
                "post", mapOf("cbc-business" to "Canada Post and union resume talks as parcel backlog grows"),
                1_300, "<p>Both sides said they would return to the table on Monday.</p>", "canada-post-talks",
            ),
            Story(
                "housing", mapOf("cbc-business" to "Housing starts rise in October, led by rental construction", "financial-post" to "Housing starts climb in October on strong rental building"),
                1_700, "<p>CMHC says the annual pace of starts reached 262,000 units.</p>", "housing-starts",
            ),
            Story(
                "rogers", mapOf("globe-business" to "Rogers & Bell cut some plan prices amid CRTC pressure"),
                2_100, "<p>The carriers said the &#8220;limited-time&#8221; plans start at $45 a month.</p>", "rogers-bell-prices",
            ),
            Story(
                "pension", mapOf(
                    "globe-business" to "Canada's largest pension funds warn that higher-for-longer interest rates and slowing global growth could squeeze returns across private markets for years to come",
                ),
                2_600, "<p>A report from the funds' joint research group says investors should expect lower returns from private equity, infrastructure and real estate through 2030, and that managers will need to cut fees to keep clients.</p>", "pension-funds-warn",
            ),
            Story(
                "tfsa", mapOf("cbc-business" to "Federal budget: what it means for TFSA and RRSP limits", "financial-post" to "Budget 2026: what changes for your TFSA and RRSP"),
                3_200, "<p>The TFSA limit stays at $7,000 for 2027.</p>", "budget-tfsa-rrsp",
            ),
        )
    }
}
