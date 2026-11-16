package com.marketsboard.core

import com.marketsboard.data.news.Headline
import com.marketsboard.data.news.PlainText
import com.marketsboard.ui.markets.HeaderUi
import com.marketsboard.ui.markets.LiveKind
import com.marketsboard.ui.markets.QuoteRowUi
import com.marketsboard.ui.markets.RowStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class UtteranceKind { Greeting, Caveat, Quote, HeadlinesIntro, Headline, Note }

/** One sentence for the text to speech layer; [pauseAfterMs] is the breath it should take before the next one. */
data class Utterance(val text: String, val kind: UtteranceKind, val pauseAfterMs: Int)

data class Briefing(
    val utterances: List<Utterance>,
    /** Ids of the headlines that were read, so the caller can pass them back as `alreadySpoken` next time. */
    val headlineIds: List<String> = emptyList(),
) {
    /** The whole briefing as one string (sentences joined by a space). */
    val script: String get() = utterances.joinToString(" ") { it.text }
    val wordCount: Int get() = utterances.sumOf { Briefings.words(it.text) }

    /** Rough speaking time including the pauses between sentences. */
    val estimatedSeconds: Double get() = utterances.sumOf { Briefings.estimateSeconds(it.text) + it.pauseAfterMs / 1000.0 }
    val isEmpty: Boolean get() = utterances.isEmpty()
}

/**
 * Everything the briefing is built from. [header] carries the market state (closed, delayed, offline). [alreadySpoken] are
 * headline ids read in an earlier briefing (they are skipped). [headlinesFromCache] says the headlines are not freshly
 * fetched, which earns a caveat.
 */
data class BriefingInput(
    val rows: List<QuoteRowUi>,
    val headlines: List<Headline> = emptyList(),
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    val header: HeaderUi = HeaderUi(),
    /** Overrides the header: the device has no connection (so every number is old). */
    val offline: Boolean = false,
    val alreadySpoken: Set<String> = emptySet(),
    val headlinesFromCache: Boolean = false,
)

/**
 * Builds the spoken market briefing: pure, deterministic, about 40 seconds (42 s and 110 words at most). It only
 * produces text and pauses; speaking it is the platform's job (M4). Rules:
 * - greeting by local time of day, then the caveats (offline, delayed with the age, markets closed), then each instrument
 *   ("X E Q T, 41 dollars 25 cents, down 4.6 percent": a Canadian listener hears the currency only when it is NOT Canadian
 *   dollars, so "Microsoft, 414 dollars 60 cents U S, down 4.9 percent"), then up to 3 headlines from the last 24 hours
 *   that were not read before (titles only, shortened), preferring those tagged with a watchlist symbol;
 * - tickers are spelled letter by letter unless the name is short enough to say ("Microsoft");
 * - indices speak points, currency pairs speak the rate ("The U S dollar buys 1 dollar 37 Canadian"), prices from 10000 up
 *   drop the cents, negative prices say "minus";
 * - instruments without a price say so; stale or cached data is announced;
 * - the speaking time is estimated (digits and spelled letters count more than words); when over budget, headlines go first
 *   (last to first), then instruments from the end of the list.
 * Ages come from the structured [RowStatus] values, never from parsing display text.
 */
object Briefings {
    private val EM_DASH = Char(0x2014).toString()
    const val MAX_WORDS = 110
    const val MAX_SECONDS = 42.0
    const val MAX_HEADLINES = 3
    private const val HEADLINE_WORDS = 14
    private const val DAY_MS = 86_400_000L

    fun words(s: String): Int = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Speaking time guess in seconds: digits and spelled letters are slow, short words quick, commas and full stops pause. */
    fun estimateSeconds(text: String): Double {
        var t = 0.0
        for (raw in text.trim().split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            val core = raw.trim(',', '.', ';', ':', '!', '?')
            t += when {
                core.isEmpty() -> 0.0
                core.all { it.isDigit() || it == '.' } -> maxOf(0.3, core.count { it.isDigit() } * 0.2)
                core.length == 1 -> 0.4
                else -> 0.3 + 0.03 * maxOf(0, core.length - 6)
            }
            if (raw.endsWith(",") || raw.endsWith(";") || raw.endsWith(":")) t += 0.12
            if (raw.endsWith(".")) t += 0.2
        }
        return t
    }

    fun build(input: BriefingInput): Briefing {
        val greeting = Utterance(greeting(input.nowMs, input.zone) + " Here is your market briefing.", UtteranceKind.Greeting, 500)
        val caveats = caveats(input)
        val rows = input.rows
        val priced = rows.filter { it.price?.isFinite() == true }
        val unpriced = rows.filter { it.price?.isFinite() != true }
        val quoteLines: List<Utterance> = when {
            rows.isEmpty() -> listOf(Utterance("Your watchlist is empty.", UtteranceKind.Note, 400))
            priced.isEmpty() -> listOf(Utterance("Prices are not available yet.", UtteranceKind.Note, 400))
            else -> priced.map { Utterance(quoteSentence(it), UtteranceKind.Quote, 350) } +
                unpriced.map { Utterance("No price available for ${spokenName(it)}.", UtteranceKind.Note, 300) }
        }
        val picked = pickHeadlines(input.headlines, input.nowMs, input.alreadySpoken)
        val headLines = picked.map { Utterance(shortTitle(it.title) + ".", UtteranceKind.Headline, 450) }
        val noNews = Utterance("No new headlines.", UtteranceKind.Note, 300)
        val cacheNote = Utterance("Headlines may be out of date.", UtteranceKind.Caveat, 300)

        var q = quoteLines
        var h = headLines
        fun section(): List<Utterance> = when {
            h.isEmpty() -> listOf(noNews)
            input.headlinesFromCache -> listOf(cacheNote, Utterance(HEADLINES_INTRO, UtteranceKind.HeadlinesIntro, 300)) + h
            else -> listOf(Utterance(HEADLINES_INTRO, UtteranceKind.HeadlinesIntro, 300)) + h
        }
        fun all(): List<Utterance> {
            val omitted = quoteLines.size - q.size
            val note = if (omitted > 0) listOf(Utterance(if (omitted == 1) "One more is on screen." else "$omitted more are on screen.", UtteranceKind.Note, 300)) else emptyList()
            return listOf(greeting) + caveats + q + note + section()
        }
        fun over(): Boolean {
            val b = Briefing(all())
            return b.wordCount > MAX_WORDS || b.estimatedSeconds > MAX_SECONDS
        }
        while (over() && h.isNotEmpty()) h = h.dropLast(1)
        while (over() && q.size > 1) q = q.dropLast(1)
        val used = picked.take(h.size).map { it.id }
        return Briefing(all().map { it.copy(text = scrub(it.text)) }, used)
    }

    private const val HEADLINES_INTRO = "Top headlines."

    // ---- parts -------------------------------------------------------------------------------------------------

    fun greeting(nowMs: Long, zone: ZoneId): String {
        val hour = Instant.ofEpochMilli(nowMs).atZone(zone).hour
        return when (hour) {
            in 5..11 -> "Good morning."
            in 12..16 -> "Good afternoon."
            in 17..21 -> "Good evening."
            else -> "Hello."
        }
    }

    private fun caveats(input: BriefingInput): List<Utterance> {
        val out = ArrayList<Utterance>()
        val kind = input.header.kind
        val offline = input.offline || kind == LiveKind.Offline
        val age = oldestAgeMs(input.rows)?.let { ", last updated ${speakAgeMs(it)}" }.orEmpty()
        val delayed = kind == LiveKind.Delayed || input.rows.any { it.status is RowStatus.Stale || it.status is RowStatus.Error }
        if (offline) {
            out.add(Utterance("You appear to be offline, so these prices may be old$age.", UtteranceKind.Caveat, 400))
        } else if (delayed) {
            out.add(Utterance("Prices may be delayed$age.", UtteranceKind.Caveat, 400))
        }
        if (kind == LiveKind.Closed && !offline) out.add(Utterance("Markets are closed.", UtteranceKind.Caveat, 350))
        return out
    }

    /** The largest known age among stale or failed rows, in milliseconds; null when none is known. */
    private fun oldestAgeMs(rows: List<QuoteRowUi>): Long? = rows.mapNotNull {
        when (val s = it.status) {
            is RowStatus.Stale -> s.ageMs
            is RowStatus.Error -> s.ageMs
            else -> null
        }
    }.maxOrNull()

    /** 90 000 ms gives "1 minute ago", 7 200 000 gives "2 hours ago", under a minute "a moment ago". */
    fun speakAgeMs(ms: Long): String {
        val min = ms / 60_000
        fun unit(n: Long, w: String) = "$n $w${if (n == 1L) "" else "s"} ago"
        return when {
            min < 1 -> "a moment ago"
            min < 60 -> unit(min, "minute")
            min < 24 * 60 -> unit(min / 60, "hour")
            else -> unit(min / (24 * 60), "day")
        }
    }

    fun quoteSentence(r: QuoteRowUi): String {
        val price = r.price?.takeIf { it.isFinite() } ?: return "No price available for ${spokenName(r)}."
        val fx = FX.matchEntire(r.symbol)
        val head = if (fx != null) "The ${currencyName(fx.groupValues[1])} buys ${fxValue(price, fx.groupValues[2], r.decimals)}" else "${spokenName(r)}, ${spokenValue(r, price)}"
        val body = StringBuilder(head)
        change(r)?.let { body.append(", ").append(it) }
        return body.append('.').toString()
    }

    /** "up 0.4 percent", "down 4.6 percent", "unchanged"; null when there is nothing to compare with. */
    fun change(r: QuoteRowUi): String? {
        val pct = r.changePct
        if (!pct.isFinite()) return null
        val v = BigDecimal(pct.toString()).setScale(1, RoundingMode.HALF_UP)
        if (v.signum() == 0) return "unchanged"
        return (if (v.signum() > 0) "up " else "down ") + trimZero(v.abs().toPlainString()) + " percent"
    }

    private fun trimZero(s: String) = if (s.endsWith(".0")) s.dropLast(2) else s

    // ---- names -------------------------------------------------------------------------------------------------

    private val NAME_SUFFIXES = setOf("inc", "inc.", "corp", "corp.", "corporation", "ltd", "ltd.", "limited", "co", "co.", "company", "plc", "index", "the")
    private val FX = Regex("^([A-Z]{3})([A-Z]{3})=X$")
    private val FUTURES_TAIL = Regex("\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+\\d{2,4}$", RegexOption.IGNORE_CASE)
    private val CURRENCY_NAMES = mapOf(
        "USD" to "U S dollar", "CAD" to "Canadian dollar", "EUR" to "euro", "GBP" to "pound", "JPY" to "yen", "AUD" to "Australian dollar",
        "CHF" to "Swiss franc", "MXN" to "peso", "CNY" to "yuan",
    )

    fun spokenName(r: QuoteRowUi): String {
        FX.matchEntire(r.symbol)?.let { m -> return "${currencyName(m.groupValues[1])} to ${currencyName(m.groupValues[2])}" }
        val say = nameWords(r)
        if (say != null) return say
        return spell(tickerBase(r.symbol))
    }

    private fun currencyName(code: String) = CURRENCY_NAMES[code] ?: spell(code)

    private fun tickerBase(symbol: String): String = symbol.removePrefix("^").split('.', '=', '-').firstOrNull().orEmpty().ifEmpty { symbol }

    /** The name as words when it is short and clean enough to say, else null (then the ticker is spelled). */
    private fun nameWords(r: QuoteRowUi): String? {
        var words = r.name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || r.name.trim().equals(r.symbol, ignoreCase = true)) return null
        val name = words.joinToString(" ").replace(FUTURES_TAIL, "")
        words = name.split(' ').filter { it.isNotEmpty() }
        while (words.size > 1 && words.last().lowercase(Locale.ROOT) in NAME_SUFFIXES) words = words.dropLast(1)
        if (words.size > 1 && words.last().equals(r.currency, ignoreCase = true)) words = words.dropLast(1)
        if (words.isEmpty() || words.size > 3 || words.joinToString(" ").length > 24) return null
        return speakable(words.joinToString(" "))
    }

    private val MONEY = Regex("(C\\$|CA\\$|US\\$|\\$)(\\d[\\d,]*(?:\\.\\d+)?)\\s?(billion|million|trillion|thousand|bn|mm|[KkMBT])?(?![A-Za-z])")

    /**
     * Text made speakable: money abbreviations ("C$1.2B" gives "1.2 billion Canadian dollars"), "%" as "percent", "TSX"
     * as "T S X", "S&P" as "S and P". Ordinary words stay as they are.
     */
    fun speakable(text: String): String {
        var t = MONEY.replace(text) { m ->
            val unit = when (m.groupValues[3].lowercase(Locale.ROOT)) {
                "billion", "bn", "b" -> " billion"
                "million", "mm", "m" -> " million"
                "trillion", "t" -> " trillion"
                "thousand", "k" -> " thousand"
                else -> ""
            }
            val cur = when (m.groupValues[1]) { "C$", "CA$" -> "Canadian dollars"; "US$" -> "U S dollars"; else -> "dollars" }
            m.groupValues[2].replace(",", "") + unit + " " + cur
        }
        t = t.replace("%", " percent").replace("&", " and ").replace("/", " ").replace("_", " ")
        t = t.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            val core = w.trim('.', ',', ';', ':', '(', ')', '"', '\'')
            val letters = core.replace(".", "")
            if (letters.uppercase(Locale.ROOT) in SPELL_WORDS && letters.length in 2..5 && letters.all { it.isUpperCase() }) w.replace(core, spell(letters)).trimEnd('.') else w
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private val SPELL_WORDS = setOf(
        "TSX", "TSXV", "ETF", "WTI", "USD", "CAD", "GDP", "CPI", "ECB", "BOC", "IPO", "CEO", "CFO", "AI", "EV", "US", "UK", "EU", "GM", "BC",
        "RBC", "TD", "BMO", "CIBC", "LNG", "IMF", "FDA", "ESG", "REIT", "EPS", "NYSE", "TFSA", "RRSP",
    )

    /** "XEQT" gives "X E Q T"; non-alphanumerics are skipped. */
    fun spell(s: String): String = s.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT).toList().joinToString(" ")

    // ---- values ------------------------------------------------------------------------------------------------

    fun spokenValue(r: QuoteRowUi, price: Double): String {
        if (r.symbol.startsWith("^")) {
            val sign = if (price < 0) "minus " else ""
            val a = Math.abs(price)
            return sign + BigDecimal(a.toString()).setScale(if (a >= 1000) 0 else 2, RoundingMode.HALF_UP).toPlainString() + " points"
        }
        return spokenMoney(price, r.currency, r.decimals)
    }

    /** "The U S dollar buys [1 dollar 37 Canadian]": the rate in the quote currency; whole cents are dropped for Canadian dollars. */
    private fun fxValue(rate: Double, quote: String, decimals: Int): String {
        if (!rate.isFinite() || rate <= 0) return ""
        if (quote == "CAD") {
            val v = BigDecimal(rate.toString()).setScale(2, RoundingMode.HALF_UP)
            val d = v.toBigInteger()
            val c = v.subtract(BigDecimal(d)).movePointRight(2).toInt()
            val parts = ArrayList<String>()
            if (d.signum() != 0) parts.add("$d ${if (d.toLong() == 1L) "dollar" else "dollars"}")
            if (c != 0) parts.add(if (d.signum() != 0) "$c" else "$c cents")
            if (parts.isEmpty()) parts.add("0 dollars")
            return parts.joinToString(" ") + " Canadian"
        }
        return spokenMoney(rate, quote, decimals)
    }

    /**
     * Money in words: "41 dollars 25 cents" (Canadian dollars are the default and carry no tag), "414 dollars 60 cents U S",
     * whole dollars from 10000 up, "minus" for a negative amount.
     */
    fun spokenMoney(price: Double, currency: String, decimals: Int = 2): String {
        if (!price.isFinite()) return ""
        if (price < 0) return "minus " + spokenMoney(-price, currency, decimals)
        val code = currency.uppercase(Locale.ROOT)
        if (code == "GBX") {
            val p = BigDecimal(price.toString()).setScale(if (price >= 100) 0 else 1, RoundingMode.HALF_UP)
            return trimZero(p.toPlainString()) + " pence"
        }
        val (major, minor, tail) = when (code) {
            "CAD", "" -> if (code == "CAD") Triple("dollar", "cent", "") else return plainWithCode(price, code, decimals)
            "USD" -> Triple("dollar", "cent", " U S")
            "AUD" -> Triple("dollar", "cent", " Australian")
            "EUR" -> Triple("euro", "cent", "")
            "GBP" -> Triple("pound", "pence", "")
            "JPY" -> Triple("yen", "", "")
            else -> return plainWithCode(price, code, decimals)
        }
        if (price < 0.1 && minor.isNotEmpty()) {
            return trimTrailingZeros(BigDecimal(price.toString()).setScale(4, RoundingMode.HALF_UP).toPlainString()) + " ${plural(major, 2)}$tail"
        }
        val whole = minor.isEmpty() || price >= 10_000
        val v = BigDecimal(price.toString()).setScale(if (whole) 0 else 2, RoundingMode.HALF_UP)
        val dollars = v.toBigInteger()
        val cents = if (whole) 0 else v.subtract(BigDecimal(dollars)).movePointRight(2).toInt()
        val parts = ArrayList<String>()
        if (dollars.signum() != 0 || cents == 0) parts.add("$dollars ${plural(major, dollars.toLong())}")
        if (cents != 0) parts.add("$cents ${if (minor == "pence") "pence" else plural(minor, cents.toLong())}")
        return parts.joinToString(" ") + tail
    }

    private fun plainWithCode(price: Double, code: String, decimals: Int): String {
        val v = BigDecimal(price.toString()).setScale(decimals.coerceIn(0, 6), RoundingMode.HALF_UP).toPlainString()
        return if (code.isEmpty()) v else "$v ${spell(code)}"
    }

    private fun trimTrailingZeros(s: String): String = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s

    private fun plural(word: String, n: Long) = if (n == 1L) word else if (word == "yen") word else word + "s"

    // ---- headlines ---------------------------------------------------------------------------------------------

    /**
     * Up to [MAX_HEADLINES] published in the last 24 hours and not in [alreadySpoken]: watchlist-tagged ones first (newest
     * first as given), then the rest.
     */
    fun pickHeadlines(all: List<Headline>, nowMs: Long, alreadySpoken: Set<String> = emptySet()): List<Headline> {
        val fresh = all.filter { it.id !in alreadySpoken && nowMs - it.publishedMs <= DAY_MS }
        return (fresh.filter { it.tags.isNotEmpty() } + fresh.filter { it.tags.isEmpty() }).take(MAX_HEADLINES)
    }

    private val SOURCE_SUFFIX = Regex("\\s+[-|]\\s+[^-|]{2,30}$")

    /** Words a spoken headline must not end on. */
    private val DANGLING = setOf(
        "the", "a", "an", "of", "to", "as", "and", "or", "but", "for", "in", "on", "at", "by", "with", "from", "that", "than", "is", "are", "was", "were",
        "after", "over", "amid", "into", "its", "their", "his", "her", "while", "about", "against", "between", "per", "via", "could", "would", "will", "may",
        "might", "can", "should", "has", "have", "had", "be", "been", "not", "vs", "versus", "this", "these", "those", "who", "which", "if", "when", "because",
    )
    private val CUT_BEFORE = setOf("as", "after", "while", "amid", "with", "but", "despite")

    /**
     * Title shortened for speech: no source suffix, at most 14 words (cut at the last comma, colon, " as " or " after "
     * boundary when there is one, else hard), never ending on a dangling word, abbreviations and money made speakable.
     */
    fun shortTitle(title: String): String {
        var t = PlainText.clean(title, 200)
        val stripped = t.replace(SOURCE_SUFFIX, "")
        val suffix = t.substring(stripped.length).trimStart(' ', '-', '|')
        // A trailing " - Reuters" or " | CBC News" is a source name: short and capitalised. A lower-case clause is part of the headline.
        if (stripped.length >= 12 && words(suffix) <= 4 && suffix.firstOrNull()?.isUpperCase() == true) t = stripped
        var ws = t.split(' ').filter { it.isNotEmpty() }
        if (ws.size > HEADLINE_WORDS) {
            var cut = -1
            for (i in 4..HEADLINE_WORDS) {
                val endsClause = ws[i - 1].endsWith(",") || ws[i - 1].endsWith(":")
                val nextStarts = i < ws.size && ws[i].lowercase(Locale.ROOT) in CUT_BEFORE
                if (endsClause || nextStarts) cut = i
            }
            ws = ws.take(if (cut > 0) cut else HEADLINE_WORDS)
        }
        var out = ws.joinToString(" ").trimEnd('.', ',', ';', ':', '-', '!', '?', '\u2026', ' ')
        while (true) {
            val parts = out.split(' ')
            if (parts.size > 3 && parts.last().lowercase(Locale.ROOT).trimEnd(',', ';', ':') in DANGLING) {
                out = parts.dropLast(1).joinToString(" ").trimEnd('.', ',', ';', ':', '-', ' ')
            } else break
        }
        return speakable(out)
    }

    /** Last line of defence: nothing markup-like or dash-like reaches the speech engine. */
    private fun scrub(s: String): String =
        s.replace(EM_DASH, " ").replace("\u2013", " ").replace(Regex("[<>]"), " ").replace(Regex("\\s+"), " ").trim()
}
