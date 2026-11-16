package com.marketsboard.data.news

import com.marketsboard.data.provider.Js
import com.marketsboard.data.provider.arr
import com.marketsboard.data.provider.asObj
import com.marketsboard.data.provider.long
import com.marketsboard.data.provider.num
import com.marketsboard.data.provider.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What is kept on disk for one feed: its items and the validators for the next conditional GET. */
data class FeedSnapshot(
    val items: List<Headline>,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Wall time of the last success, for the "updated" age after a restart. */
    val okAtMs: Long = 0,
)

/** JSON for [FeedSnapshot], re-validating everything on read (a hand-edited or damaged cache entry must not crash). */
object NewsCodec {
    private const val MAX_ITEMS = 200

    fun encode(s: FeedSnapshot): String = buildJsonObject {
        put("v", 1)
        s.etag?.let { put("etag", it) }
        s.lastModified?.let { put("lastModified", it) }
        put("okAt", s.okAtMs)
        put(
            "items",
            JsonArray(
                s.items.take(MAX_ITEMS).map {
                    buildJsonObject {
                        put("id", it.id); put("title", it.title); put("summary", it.summary); put("url", it.url)
                        put("source", it.sourceName); put("published", it.publishedMs); put("estimated", it.dateEstimated)
                        put("feedId", it.feedId)
                        put("feedTags", JsonArray(it.feedTags.map { t -> JsonPrimitive(t) }))
                    }
                },
            ),
        )
    }.toString()

    fun decode(text: String): FeedSnapshot? {
        val o = Js.parse(text).asObj() ?: return null
        if (o.num("v") != 1.0) return null
        val arr = o.arr("items") ?: return null
        val items = ArrayList<Headline>()
        for (e in arr.take(MAX_ITEMS)) {
            val i = e.asObj() ?: continue
            val url = FeedParser.safeUrl(i.str("url")) ?: continue
            val title = i.str("title")?.take(PlainText.TITLE_MAX) ?: continue
            val published = i.long("published") ?: continue
            val tags = (i["feedTags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.take(12) }.orEmpty()
            items.add(
                Headline(
                    id = i.str("id") ?: Dedupe.idFor(url), title = title, summary = i.str("summary")?.take(PlainText.SUMMARY_MAX).orEmpty(), url = url,
                    sourceName = i.str("source").orEmpty(), publishedMs = published, tags = tags, feedTags = tags,
                    dateEstimated = (i["estimated"] as? JsonPrimitive)?.content == "true", feedId = i.str("feedId").orEmpty(),
                ),
            )
        }
        return FeedSnapshot(items, o.str("etag"), o.str("lastModified"), o.long("okAt") ?: 0L)
    }
}
