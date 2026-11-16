package com.marketsboard.data.news

import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler

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
 * RSS 2.0, RSS 1.0 (RDF) and Atom through a streaming SAX parser (`javax.xml.parsers`, available on Android and the
 * desktop JVM), hardened for hostile or sloppy input:
 * - DOCTYPE is refused outright (no entities, so no XXE and no entity bombs), external entity loading is off, secure
 *   processing and no XInclude where the implementation supports the switch, and every external lookup is answered
 *   with nothing;
 * - input size, nesting depth, element count, item count and the text kept per field are all capped;
 * - the document must be rss, feed or RDF (an HTML login page is "not a feed");
 * - items completed before a syntax error are kept (partial result); with none, the result is a typed Parse error.
 * Only http and https links survive. No markup is rendered anywhere: text goes through [PlainText].
 */
object FeedParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_DEPTH = 48
    const val MAX_ITEMS = 100
    private const val MAX_ELEMENTS = 100_000
    private const val FIELD_CAP = 60_000
    private const val DAY_MS = 86_400_000L

    private const val NS_ATOM = "http://www.w3.org/2005/Atom"
    private val NS_PREFIX = mapOf(
        "http://purl.org/dc/elements/1.1/" to "dc",
        "http://purl.org/rss/1.0/modules/content/" to "content",
        "http://search.yahoo.com/mrss/" to "media",
    )

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
        val handler = Handler(nowMs)
        var failure: String? = null
        try {
            val reader = newFactory().newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            try {
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            } catch (e: Exception) {
                // Not every implementation offers it; disallow-doctype-decl and the pre-check below also cover DOCTYPE.
            }
            if (hasDoctype(clean)) return FetchResult.Err(ErrorKind.Parse, "DOCTYPE is not allowed")
            reader.parse(InputSource(StringReader(clean)))
        } catch (e: Handler.Stop) {
            // Item or element cap reached: what was read is the result.
        } catch (e: SAXParseException) {
            failure = e.message ?: "XML error"
        } catch (e: SAXException) {
            failure = e.message ?: "XML error"
        } catch (e: java.io.IOException) {
            failure = e.message ?: "read error"
        } catch (e: RuntimeException) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
        }
        if (handler.sawDoctype) return FetchResult.Err(ErrorKind.Parse, "DOCTYPE is not allowed")
        if (handler.notAFeed != null) return FetchResult.Err(ErrorKind.Parse, handler.notAFeed!!)
        if (failure != null && handler.items.isEmpty()) return FetchResult.Err(ErrorKind.Parse, "malformed feed: ${failure.take(120)}")
        if (handler.root == null) return FetchResult.Err(ErrorKind.Parse, "no feed found")
        return FetchResult.Ok(
            ParsedFeed(PlainText.title(handler.feedTitle.toString()), handler.items, partial = failure != null, dropped = handler.dropped, format = handler.root!!),
        )
    }

    /** True when a DOCTYPE sits in the prolog (after any XML declaration, comments and processing instructions). */
    internal fun hasDoctype(text: String): Boolean {
        var i = 0
        while (i < text.length && i < 100_000) {
            while (i < text.length && text[i].isWhitespace()) i++
            when {
                text.startsWith("<?", i) -> { val e = text.indexOf("?>", i); if (e < 0) return false; i = e + 2 }
                text.startsWith("<!--", i) -> { val e = text.indexOf("-->", i); if (e < 0) return false; i = e + 3 }
                else -> return text.regionMatches(i, "<!DOCTYPE", 0, 9, ignoreCase = true)
            }
        }
        return false
    }

    private fun newFactory(): SAXParserFactory {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = true
        f.isValidating = false
        fun set(name: String, v: Boolean) { try { f.setFeature(name, v) } catch (e: Exception) { /* best effort, see class comment */ } }
        set("http://javax.xml.XMLConstants/feature/secure-processing", true)
        set("http://apache.org/xml/features/disallow-doctype-decl", true)
        set("http://xml.org/sax/features/external-general-entities", false)
        set("http://xml.org/sax/features/external-parameter-entities", false)
        set("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        try { f.isXIncludeAware = false } catch (e: Exception) { /* not supported */ }
        return f
    }

    /** Accepts only absolute http(s) URLs without control characters; null otherwise. */
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

    private class Handler(private val nowMs: Long) : DefaultHandler(), LexicalHandler {
        class Stop : RuntimeException(null, null, false, false)

        val items = ArrayList<ParsedItem>()
        val feedTitle = StringBuilder()
        var root: String? = null
        var notAFeed: String? = null
        var sawDoctype = false
        var dropped = 0

        private val stack = ArrayList<String>()
        private var elements = 0
        private var inItem = false
        private var itemDepth = 0
        private var capture: StringBuilder? = null
        private var captureKey = ""
        private var captureDepth = 0
        private var captureIsFeedTitle = false

        private val fields = HashMap<String, String>()
        private var atomHref: String? = null
        private var atomScore = -1

        private fun keyOf(uri: String?, local: String, qName: String): String {
            val name = local.ifEmpty { qName.substringAfter(':') }
            if (uri.isNullOrEmpty()) return name
            if (uri == NS_ATOM) return if (rootLocal == "feed") name else "atom:$name"
            NS_PREFIX[uri]?.let { return "$it:$name" }
            return if (uri == "http://purl.org/rss/1.0/") name else "x:$name"
        }

        override fun startElement(uri: String?, localName: String, qName: String, atts: Attributes) {
            if (++elements > MAX_ELEMENTS) throw Stop()
            if (stack.size >= MAX_DEPTH) throw SAXException("document nested deeper than $MAX_DEPTH")
            if (root == null) {
                val r = localName.ifEmpty { qName.substringAfter(':') }
                if (r != "rss" && r != "feed" && r != "RDF") {
                    notAFeed = "not a feed (found <$r>)"
                    throw SAXException(notAFeed)
                }
                root = when (r) { "feed" -> "atom"; "RDF" -> "rdf"; else -> "rss" }
                rootLocal = r
            }
            val key = keyOf(uri, localName, qName)
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
                        if (key == "link" && rootLocal == "feed") {
                            atomLink(atts)
                        } else if (key == "atom:link") {
                            atomLink(atts)
                        } else {
                            startCapture(key, depthBefore + 1, false)
                        }
                    }
                }
            } else if (!inItem && key == "title" && depthBefore <= 2 && feedTitle.isEmpty()) {
                startCapture(key, depthBefore + 1, true)
            }
        }

        private var rootLocal: String = ""

        private fun startCapture(key: String, depth: Int, isFeedTitle: Boolean) {
            capture = StringBuilder(); captureKey = key; captureDepth = depth; captureIsFeedTitle = isFeedTitle
        }

        /** Atom `<link>`: prefer rel=alternate (or no rel) with an html type, ignore self, enclosure and the like. */
        private fun atomLink(atts: Attributes) {
            val href = atts.getValue("href") ?: return
            val rel = atts.getValue("rel")?.lowercase(Locale.ROOT)
            val type = atts.getValue("type")?.lowercase(Locale.ROOT).orEmpty()
            val score = when {
                (rel == null || rel == "alternate") && (type.isEmpty() || type.contains("html")) -> 3
                rel == null || rel == "alternate" -> 2
                rel == "related" -> 1
                else -> -1
            }
            if (score > atomScore) { atomScore = score; atomHref = href }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            val c = capture ?: return
            if (c.length < FIELD_CAP) c.append(ch, start, minOf(length, FIELD_CAP - c.length))
        }

        override fun endElement(uri: String?, localName: String, qName: String) {
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
                ?: guid?.takeIf { (fields["guid"] != null) }?.let { safeUrl(it) }
            if (title.isEmpty() || link == null) { dropped++; return }
            val summaryRaw = fields["description"] ?: fields["summary"] ?: fields["content:encoded"] ?: fields["content"]
            var summary = PlainText.clean(summaryRaw)
            if (summary.equals(title, ignoreCase = true)) summary = ""
            val dateRaw = fields["pubDate"] ?: fields["published"] ?: fields["dc:date"] ?: fields["date"] ?: fields["updated"] ?: fields["modified"]
            var published = FeedDates.parse(dateRaw)
            var replaced = false
            if (published != null && published > nowMs + DAY_MS) { published = nowMs; replaced = true }
            items.add(ParsedItem(title, link, guid?.trim()?.take(500)?.takeIf { it.isNotEmpty() }, summary, published, replaced))
        }

        // The pieces below only make DOCTYPE fatal where the implementation reports it through the lexical handler.
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            sawDoctype = true
            throw SAXException("DOCTYPE is not allowed")
        }
        override fun endDTD() {}
        override fun startEntity(name: String?) {}
        override fun endEntity(name: String?) {}
        override fun startCDATA() {}
        override fun endCDATA() {}
        override fun comment(ch: CharArray?, start: Int, length: Int) {}

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun fatalError(e: SAXParseException) {
            if (e.message?.contains("DOCTYPE", ignoreCase = true) == true) sawDoctype = true
            throw e
        }
    }
}
