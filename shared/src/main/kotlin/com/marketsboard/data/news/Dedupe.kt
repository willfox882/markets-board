package com.marketsboard.data.news

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Merging the same story from several feeds. Three levels, cheapest first: same canonical URL (tracking parameters,
 * `www.`, fragments, trailing slash and scheme ignored), same normalised title, and near-identical titles by a token
 * Dice score (a looser score is enough when the two summaries are word for word the same, as with wire copy). Of a duplicate group the earliest report is kept (ties: has a summary, longer summary, earlier feed),
 * and the tags of every member are merged into it.
 */
object Dedupe {
    private val TRACKING_EXACT = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid", "ref", "ref_src", "ref_url", "cmpid", "cmp",
        "ocid", "taid", "yptr", "guccounter", "guce_referrer", "guce_referrer_sig", "rss", "rssfeed", "feedtype", "mod", "tsrc",
        "smid", "smtyp", "referrer", "_ga", "_gl", "ncid", "ito",
    )
    // Words that say which way something moved. Two titles that differ exactly in such a word are different stories.
    private val UP = setOf(
        "rise", "rises", "rose", "rising", "gain", "gains", "gained", "gaining", "higher", "jump", "jumps", "jumped", "surge", "surges", "surged",
        "climb", "climbs", "climbed", "rally", "rallies", "rallied", "advance", "advances", "advanced", "up", "soar", "soars", "soared", "increase", "increases",
    )
    private val DOWN = setOf(
        "fall", "falls", "fell", "falling", "loss", "losses", "lose", "loses", "lost", "lower", "drop", "drops", "dropped", "slump", "slumps", "slumped",
        "slide", "slides", "slid", "sink", "sinks", "sank", "decline", "declines", "declined", "down", "plunge", "plunges", "plunged", "tumble", "tumbles",
        "tumbled", "slip", "slips", "slipped", "retreat", "retreats", "retreated", "decrease", "decreases",
    )
    private val STOPWORDS = setOf(
        "the", "a", "an", "of", "to", "in", "on", "for", "and", "as", "at", "by", "with", "is", "are", "was", "from", "after",
        "over", "its", "it", "be", "this", "that", "says", "say", "amid", "new",
    )
    private const val NEAR_WINDOW_MS = 12L * 3_600_000L
    private const val DICE_THRESHOLD = 0.8
    private const val DICE_SAME_SUMMARY = 0.5
    private const val MIN_SUMMARY_CHARS = 40
    private const val MIN_TOKENS = 4

    /** Key for "is this the same page": `host/path?sortedquery`, tracking removed. Falls back to the trimmed input. */
    fun canonicalUrl(url: String): String {
        val u = try {
            URI(url.trim())
        } catch (e: Exception) {
            return url.trim().lowercase(Locale.ROOT)
        }
        val host = (u.host ?: return url.trim().lowercase(Locale.ROOT)).lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
        val path = (u.rawPath ?: "").let { if (it.length > 1) it.trimEnd('/') else "" }.lowercase(Locale.ROOT)
        val query = (u.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.filter { part ->
            val k = decode(part.substringBefore('=')).lowercase(Locale.ROOT)
            !(k.startsWith("utm_") || k.startsWith("at_") || k.startsWith("pk_") || k.startsWith("mkt_") || k in TRACKING_EXACT)
        }.sorted().joinToString("&")
        return if (query.isEmpty()) "$host$path" else "$host$path?$query"
    }

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    /** Stable id from the canonical URL (40 bits of SHA-256 is plenty for a 120-item list). */
    fun idFor(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(canonicalUrl(url).toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    /** Lower case, accents removed, everything but letters and digits turned into single spaces. */
    fun normalizeTitle(title: String): String {
        val folded = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return folded.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    fun tokens(title: String): Set<String> =
        normalizeTitle(title).split(' ').filter { it.isNotEmpty() && it !in STOPWORDS && (it.length > 1 || it[0].isDigit()) }.toSet()

    /** Dice coefficient of two token sets: 2|A and B| / (|A| + |B|), 0 when either is empty. */
    fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, big) = if (a.size <= b.size) a to b else b to a
        val common = small.count { it in big }
        return 2.0 * common / (a.size + b.size)
    }

    /** One item with the data the comparison needs, so the tokens are computed once. */
    class Entry(val headline: Headline, val rank: Int) {
        val urlKey: String = canonicalUrl(headline.url)
        val titleKey: String = normalizeTitle(headline.title)
        val tokens: Set<String> = tokens(headline.title)
        val feedId: String = headline.feedId
        /** Normalised summary, only when long enough to be a meaningful fingerprint. */
        val summaryKey: String = normalizeTitle(headline.summary).let { if (it.length >= MIN_SUMMARY_CHARS) it else "" }
    }

    /** True when one title has an "up" word the other lacks and the other has a "down" word the first lacks. */
    fun opposite(a: Set<String>, b: Set<String>): Boolean {
        val onlyA = a - b
        val onlyB = b - a
        return (onlyA.any { it in UP } && onlyB.any { it in DOWN }) || (onlyA.any { it in DOWN } && onlyB.any { it in UP })
    }

    /**
     * Same story? The same page always is. Title and summary rules only join items from DIFFERENT feeds ([aFeeds] are the
     * feeds already merged into [a]) published within 12 hours of each other, and never when the titles move opposite ways.
     * A feed repeating a headline on another day is a new story, and so is a second similar headline in the same feed.
     */
    fun isSame(a: Entry, b: Entry, aFeeds: Set<String> = setOf(a.feedId)): Boolean {
        if (a.urlKey == b.urlKey) return true
        if (b.feedId in aFeeds) return false
        if (Math.abs(a.headline.publishedMs - b.headline.publishedMs) > NEAR_WINDOW_MS) return false
        if (opposite(a.tokens, b.tokens)) return false
        if (a.titleKey.isNotEmpty() && a.titleKey == b.titleKey && a.tokens.size >= 2) return true
        val d = dice(a.tokens, b.tokens)
        if (a.summaryKey.isNotEmpty() && a.summaryKey == b.summaryKey && a.tokens.size >= 2 && b.tokens.size >= 2 && d >= DICE_SAME_SUMMARY) return true
        return a.tokens.size >= MIN_TOKENS && b.tokens.size >= MIN_TOKENS && d >= DICE_THRESHOLD
    }

    /**
     * Removes duplicates from [items] (already in feed priority order via [Entry.rank]). The kept item of each group is
     * the earliest report; its tags are the union of the group's. Output order: oldest first (callers sort).
     */
    fun merge(items: List<Entry>): List<Headline> {
        val ordered = items.sortedWith(
            compareBy<Entry> { it.headline.publishedMs }
                .thenByDescending { it.headline.summary.isNotEmpty() }
                .thenByDescending { it.headline.summary.length }
                .thenBy { it.rank }
                .thenBy { it.headline.id },
        )
        val kept = ArrayList<Entry>()
        val tags = ArrayList<MutableList<String>>()
        val feedTags = ArrayList<MutableList<String>>()
        val feedSets = ArrayList<MutableSet<String>>()
        val byUrl = HashMap<String, Int>()
        for (e in ordered) {
            var at = byUrl[e.urlKey]
            if (at == null) {
                // kept is oldest first, so walk back and stop once the 12 h window is left behind.
                for (i in kept.indices.reversed()) {
                    if (e.headline.publishedMs - kept[i].headline.publishedMs > NEAR_WINDOW_MS) break
                    if (isSame(kept[i], e, feedSets[i])) { at = i; break }
                }
            }
            if (at == null) {
                kept.add(e); tags.add(e.headline.tags.toMutableList()); feedTags.add(e.headline.feedTags.toMutableList()); feedSets.add(mutableSetOf(e.feedId))
                byUrl[e.urlKey] = kept.size - 1
            } else {
                for (t in e.headline.tags) if (t !in tags[at]) tags[at].add(t)
                for (t in e.headline.feedTags) if (t !in feedTags[at]) feedTags[at].add(t)
                feedSets[at].add(e.feedId)
                byUrl.putIfAbsent(e.urlKey, at)
            }
        }
        return kept.mapIndexed { i, e -> e.headline.copy(tags = tags[i], feedTags = feedTags[i]) }
    }
}
