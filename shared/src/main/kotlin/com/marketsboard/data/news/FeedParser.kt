package com.marketsboard.data.news

import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/** One item as found in the feed: plain text, a safe http(s) link, and a date only when the feed gave a usable one. */
data class ParsedItem(
    val title: String,
    val url: String,
    val guid: String?,
    val summary: String,
    /** Feed time; null when missing or unusable. */
    val publishedMs: Long?,
    /** True when the feed's date was in the future and was replaced by the fetch time. */
    val dateReplaced: Boolean = false,
)

data class ParsedFeed(
    val title: String,
    val items: List<ParsedItem>,
    /** The document was malformed after some items; the readable ones are returned. */
    val partial: Boolean,
    /** Items skipped because they had no title, no usable link, or were over the item cap. */
    val dropped: Int,
    val format: String,
)

/**
 * RSS 2.0, RSS 1.0 (RDF) and Atom through the platform pull parser (`org.xmlpull.v1.XmlPullParser`: Android's own
 * KXmlParser on the phone, the same kxml2 library on the desktop JVM and in unit tests), hardened for hostile or sloppy
 * input:
 * - a DOCTYPE anywhere outside comments, CDATA and processing instructions is refused before parsing starts (a linear scan
 *   of the whole capped input), so there are no declared entities at all (no XXE, no entity bombs); DOCDECL processing is
 *   off and nothing is ever fetched from the network;
 * - only the five predefined entities and numeric references reach the parser ([XmlText] rewrites the rest);
 * - input size, nesting depth, element count, item count and the text kept per field are all capped;
 * - the document must be rss, feed or RDF (an HTML login page is "not a feed");
 * - items completed before a syntax error are kept (partial result); with none, the result is a typed Parse error.
 * Namespaces are not processed: elements are matched by their written prefix (`dc:date`, `content:encoded`, a prefixed
 * Atom root), which also survives feeds that forget to declare a prefix. Only http and https links survive. No markup
 * is rendered anywhere: text goes through [PlainText].
 */
object FeedParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_DEPTH = 48
    const val MAX_ITEMS = 100
    private const val MAX_ELEMENTS = 100_000
    private const val FIELD_CAP = 60_000

    /** Publication dates further ahead than this are replaced by the fetch time (clocks and time zones differ a little). */
    const val FUTURE_SLACK_MS = 5L * 60_000L

    /** Parses raw bytes. [httpCharset] is the Content-Type charset, used only when the document names none. */
    fun parse(bytes: ByteArray, nowMs: Long, httpCharset: String? = null): FetchResult<ParsedFeed> {
        if (bytes.size > MAX_BYTES) return FetchResult.Err(ErrorKind.Parse, "feed larger than $MAX_BYTES bytes")
        if (bytes.isEmpty()) return FetchResult.Err(ErrorKind.Parse, "empty response")
        return parseText(XmlText.decode(bytes, httpCharset), nowMs)
    }

    /** Parses already decoded text (any encoding declaration inside it is ignored). */
    fun parseText(text: String, nowMs: Long): FetchResult<ParsedFeed> {
        if (text.length > MAX_BYTES) return FetchResult.Err(ErrorKind.Parse, "feed larger than $MAX_BYTES characters")
        if (text.isBlank()) return FetchResult.Err(ErrorKind.Parse, "empty response")
        val clean = XmlText.prepare(text)
        if (hasDoctype(clean)) return FetchResult.Err(ErrorKind.Parse, "DOCTYPE is not allowed")
        val parser = try {
            newParser()
        } catch (e: Exception) {
            return FetchResult.Err(ErrorKind.Unknown, "no XML parser available: ${e.javaClass.simpleName}")
        }
        val c = Collector(nowMs)
        var failure: String? = null
        try {
            parser.setInput(StringReader(clean))
            c.run(parser)
        } catch (e: Collector.Stop) {
            // Item or element cap reached: what was read is the result.
        } catch (e: XmlPullParserException) {
            failure = e.message ?: "XML error"
        } catch (e: IOException) {
            failure = e.message ?: "read error"
        } catch (e: RuntimeException) {
            failure = e.message ?: e.javaClass.simpleName
        }
        c.notAFeed?.let { return FetchResult.Err(ErrorKind.Parse, it) }
        if (failure != null && c.items.isEmpty()) return FetchResult.Err(ErrorKind.Parse, "malformed feed: ${failure.take(120)}")
        val root = c.root ?: return FetchResult.Err(ErrorKind.Parse, "no feed found")
        return FetchResult.Ok(ParsedFeed(PlainText.title(c.feedTitle.toString()), c.items, partial = failure != null, dropped = c.dropped, format = root))
    }

    /**
     * True when a DOCTYPE appears anywhere outside comments, CDATA sections and processing instructions. One linear pass
     * over the whole input (no length bound, so a huge comment cannot push a DOCTYPE out of sight). Case-insensitive on
     * purpose: XML only knows the upper case form, a lower case one is refused anyway.
     */
    internal fun hasDoctype(text: String): Boolean {
        var i = 0
        val n = text.length
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0 || lt + 1 >= n) return false
            when {
                text.startsWith("<!--", lt) -> { val e = text.indexOf("-->", lt + 4); if (e < 0) return false; i = e + 3 }
                text.startsWith("<![CDATA[", lt) -> { val e = text.indexOf("]]>", lt + 9); if (e < 0) return false; i = e + 3 }
                text.startsWith("<?", lt) -> { val e = text.indexOf("?>", lt + 2); if (e < 0) return false; i = e + 2 }
                text.regionMatches(lt, "<!DOCTYPE", 0, 9, ignoreCase = true) -> return true
                else -> i = lt + 1
            }
        }
        return false
    }

    private fun newParser(): XmlPullParser {
        val f = XmlPullParserFactory.newInstance()
        f.isNamespaceAware = false
        val p = f.newPullParser()
        // Entity declarations are never processed, and the implementation must not look anything up.
        try { p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (e: Exception) { /* not supported: it is off by default */ }
        return p
    }

    /** Accepts only absolute http(s) URLs without control characters; null otherwise. The scheme check ignores case. */
    fun safeUrl(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2000 } ?: return null
        if (s.any { it.isISOControl() || it == ' ' }) return null
        val uri = try {
            URI(s)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return s
    }

    /** Like [safeUrl] but https only: feed addresses are never fetched in clear text. */
    fun safeFeedUrl(raw: String?): String? = safeUrl(raw)?.takeIf { it.substringBefore(':').equals("https", ignoreCase = true) }

    private class Collector(private val nowMs: Long) {
        class Stop : RuntimeException(null, null, false, false)

        val items = ArrayList<ParsedItem>()
        val feedTitle = StringBuilder()
        var root: String? = null
        var notAFeed: String? = null
        var dropped = 0

        private val stack = ArrayList<String>()
        private var elements = 0
        private var inItem = false
        private var itemDepth = 0
        private var capture: StringBuilder? = null
        private var captureKey = ""
        private var captureDepth = 0
        private var captureIsFeedTitle = false
        private var rootLocal = ""
        private var rootPrefix = ""

        private val fields = HashMap<String, String>()
        private var atomHref: String? = null
        private var atomScore = -1

        fun run(p: XmlPullParser) {
            while (true) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> start(p)
                    XmlPullParser.END_TAG -> end()
                    XmlPullParser.TEXT -> text(p.text)
                    XmlPullParser.END_DOCUMENT -> {
                        // kxml reports a cut-off document as a normal end: an element left open means the feed was truncated.
                        if (stack.isNotEmpty()) throw IllegalStateException("unexpected end of document")
                        return
                    }
                    else -> Unit
                }
            }
        }

        /** Unprefixed names and those with the root's own prefix are plain; others keep their prefix (`dc:date`). */
        private fun keyOf(name: String): String {
            val prefix = if (':' in name) name.substringBefore(':') else ""
            val local = name.substringAfter(':')
            return if (prefix.isEmpty() || prefix == rootPrefix) local else "$prefix:$local"
        }

        private fun start(p: XmlPullParser) {
            if (++elements > MAX_ELEMENTS) throw Stop()
            if (stack.size >= MAX_DEPTH) throw IllegalStateException("document nested deeper than $MAX_DEPTH")
            val name = p.name ?: ""
            if (root == null) {
                val r = name.substringAfter(':')
                if (r != "rss" && r != "feed" && r != "RDF") {
                    notAFeed = "not a feed (found <$r>)"
                    throw IllegalStateException(notAFeed)
                }
                root = when (r) { "feed" -> "atom"; "RDF" -> "rdf"; else -> "rss" }
                rootLocal = r
                rootPrefix = if (':' in name) name.substringBefore(':') else ""
            }
            val key = keyOf(name)
            val depthBefore = stack.size
            stack.add(key)

            if (!inItem && depthBefore <= 3 && ((rootLocal == "feed" && key == "entry") || (rootLocal != "feed" && key == "item"))) {
                inItem = true
                itemDepth = depthBefore
                fields.clear(); atomHref = null; atomScore = -1
                return
            }
            if (capture != null) return
            if (inItem && depthBefore == itemDepth + 1) {
                when (key) {
                    "title", "link", "guid", "id", "description", "summary", "content", "content:encoded", "pubDate", "published", "updated",
                    "dc:date", "date", "modified" -> {
                        if (key == "link" && rootLocal == "feed") atomLink(p) else startCapture(key, depthBefore + 1, false)
                    }
                    "atom:link" -> atomLink(p)
                }
            } else if (!inItem && key == "title" && depthBefore <= 2 && feedTitle.isEmpty()) {
                startCapture(key, depthBefore + 1, true)
            }
        }

        private fun startCapture(key: String, depth: Int, isFeedTitle: Boolean) {
            capture = StringBuilder(); captureKey = key; captureDepth = depth; captureIsFeedTitle = isFeedTitle
        }

        /** Atom `<link>`: prefer rel=alternate (or no rel) with an html type, ignore self, enclosure and the like. */
        private fun atomLink(p: XmlPullParser) {
            val href = p.getAttributeValue(null, "href") ?: return
            val rel = p.getAttributeValue(null, "rel")?.lowercase(Locale.ROOT)
            val type = p.getAttributeValue(null, "type")?.lowercase(Locale.ROOT).orEmpty()
            val score = when {
                (rel == null || rel == "alternate") && (type.isEmpty() || type.contains("html")) -> 3
                rel == null || rel == "alternate" -> 2
                rel == "related" -> 1
                else -> -1
            }
            if (score > atomScore) { atomScore = score; atomHref = href }
        }

        private fun text(t: String?) {
            val c = capture ?: return
            if (t != null && c.length < FIELD_CAP) c.append(t, 0, minOf(t.length, FIELD_CAP - c.length))
        }

        private fun end() {
            val depth = stack.size
            if (capture != null && depth == captureDepth) {
                val text = capture.toString()
                if (captureIsFeedTitle) feedTitle.append(text) else if (!fields.containsKey(captureKey)) fields[captureKey] = text
                capture = null; captureIsFeedTitle = false
            }
            if (inItem && depth == itemDepth + 1) finishItem()
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }

        private fun finishItem() {
            inItem = false
            if (items.size >= MAX_ITEMS) { dropped++; throw Stop() }
            val title = PlainText.title(fields["title"])
            val guid = fields["guid"] ?: fields["id"]
            val link = safeUrl(fields["link"]) ?: safeUrl(atomHref) ?: safeUrl(fields["atom:link"])
                ?: guid?.takeIf { fields["guid"] != null }?.let { safeUrl(it) }
            if (title.isEmpty() || link == null) { dropped++; return }
            val summaryRaw = fields["description"] ?: fields["summary"] ?: fields["content:encoded"] ?: fields["content"]
            var summary = PlainText.clean(summaryRaw)
            if (summary.equals(title, ignoreCase = true)) summary = ""
            val dateRaw = fields["pubDate"] ?: fields["published"] ?: fields["dc:date"] ?: fields["date"] ?: fields["updated"] ?: fields["modified"]
            var published = FeedDates.parse(dateRaw)
            var replaced = false
            if (published != null && published > nowMs + FUTURE_SLACK_MS) { published = nowMs; replaced = true }
            items.add(ParsedItem(title, link, guid?.trim()?.take(500)?.takeIf { it.isNotEmpty() }, summary, published, replaced))
        }
    }
}
