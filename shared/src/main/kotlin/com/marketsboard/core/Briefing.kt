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

data class Briefing(val utterances: List<Utterance>) {
    /** The whole briefing as one string (sentences joined by a space). */
    val script: String get() = utterances.joinToString(" ") { it.text }
    val wordCount: Int get() = utterances.sumOf { Briefings.words(it.text) }
    val isEmpty: Boolean get() = utterances.isEmpty()
}

/** Everything the briefing is built from. [header] carries the market state (closed, delayed, offline). */
data class BriefingInput(
    val rows: List<QuoteRowUi>,
    val headlines: List<Headline> = emptyList(),
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    val header: HeaderUi = HeaderUi(),
    /** Overrides the header: the device has no connection (so every number is old). */
    val offline: Boolean = false,
)

/**
 * Builds the spoken market briefing: pure, deterministic, about 40 seconds (110 words at most). It only produces text and
 * pauses; speaking it is the platform's job (M4). Rules:
 * - greeting by local time of day, then the caveats (offline, delayed with the age, markets closed), then each instrument
 *   in its own currency ("X E Q T, 41 dollars 25 cents Canadian, down 4.6 percent"), then up to 3 headlines (titles only,
 *   shortened) preferring those tagged with a watchlist symbol;
 * - tickers are spelled letter by letter unless the name is short enough to say ("Microsoft");
 * - indices speak points, currency pairs speak the rate, prices from 10000 up drop the cents;
 * - when over budget, headlines go first (last to first), then instruments from the end of the list.
 */
object Briefings {
    private val EM_DASH = Char(0x2014).toString()
    const val MAX_WORDS = 110
    const val MAX_HEADLINES = 3
    private const val HEADLINE_WORDS = 14

    fun words(s: String): Int = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    fun build(input: BriefingInput): Briefing {
        val greeting = Utterance(greeting(input.nowMs, input.zone) + " Here is your market briefing.", UtteranceKind.Greeting, 500)
        val caveats = caveats(input)
        val rows = input.rows
        val priced = rows.filter { it.price?.isFinite() == true }
        val quoteLines: List<Utterance> = when {
            rows.isEmpty() -> listOf(Utterance("Your watchlist is empty.", UtteranceKind.Note, 400))
            priced.isEmpty() -> listOf(Utterance("Prices are not available yet.", UtteranceKind.Note, 400))
            else -> priced.map { Utterance(quoteSentence(it), UtteranceKind.Quote, 350) }
        }
        val heads = pickHeadlines(input.headlines).map { Utterance(shortTitle(it.title) + ".", UtteranceKind.Headline, 450) }

        var q = quoteLines
        var h = heads
        // Five words are reserved for "N more are on screen." whenever instruments had to be dropped.
        fun total(): Int = words(greeting.text) + caveats.sumOf { words(it.text) } + q.sumOf { words(it.text) } +
            (if (h.isEmpty()) 0 else words(HEADLINES_INTRO) + h.sumOf { words(it.text) }) + (if (q.size < quoteLines.size) 5 else 0)
        while (total() > MAX_WORDS && h.isNotEmpty()) h = h.dropLast(1)
        while (total() > MAX_WORDS && q.size > 1) q = q.dropLast(1)
        val omitted = quoteLines.size - q.size
        val out = ArrayList<Utterance>()
        out.add(greeting)
        out.addAll(caveats)
        out.addAll(q)
        if (omitted > 0 && quoteLines.first().kind == UtteranceKind.Quote) {
            out.add(Utterance(if (omitted == 1) "One more is on screen." else "$omitted more are on screen.", UtteranceKind.Note, 300))
        }
        if (h.isNotEmpty()) {
            out.add(Utterance(HEADLINES_INTRO, UtteranceKind.HeadlinesIntro, 300))
            out.addAll(h)
        }
        return Briefing(out.map { it.copy(text = scrub(it.text)) })
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
        val ageText = oldestAge(input.rows)
        val delayed = kind == LiveKind.Delayed || input.rows.any { it.status is RowStatus.Stale || it.status is RowStatus.Error }
        if (offline) {
            out.add(Utterance("You appear to be offline, so these prices may be old" + (ageText?.let { ", last updated $it" } ?: "") + ".", UtteranceKind.Caveat, 400))
        } else if (delayed) {
            out.add(Utterance("Prices may be delayed" + (ageText?.let { ", last updated $it" } ?: "") + ".", UtteranceKind.Caveat, 400))
        }
        if (kind == LiveKind.Closed && !offline) out.add(Utterance("Markets are closed.", UtteranceKind.Caveat, 350))
        return out
    }

    /** The largest age among stale or failed rows, in words ("12 minutes ago"), or null when none is known. */
    private fun oldestAge(rows: List<QuoteRowUi>): String? {
        var best: Pair<Long, String>? = null
        for (r in rows) {
            val t = when (val s = r.status) {
                is RowStatus.Stale -> s.ageText
                is RowStatus.Error -> s.ageText
                else -> ""
            }
            val parsed = ageMinutes(t) ?: continue
            if (best == null || parsed > best.first) best = parsed to speakAge(t)
        }
        return best?.second
    }

    private val AGE = Regex("^(\\d+)\\s*(min|h|d)\\s+ago$")

    private fun ageMinutes(t: String): Long? {
        val m = AGE.matchEntire(t.trim()) ?: return if (t.trim() == "just now") 0 else null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        return when (m.groupValues[2]) { "min" -> n; "h" -> n * 60; else -> n * 1440 }
    }

    /** "5 min ago" gives "5 minutes ago", "1 h ago" gives "1 hour ago". */
    fun speakAge(t: String): String {
        val m = AGE.matchEntire(t.trim()) ?: return t.trim()
        val n = m.groupValues[1].toLongOrNull() ?: return t.trim()
        val unit = when (m.groupValues[2]) { "min" -> "minute"; "h" -> "hour"; else -> "day" }
        return "$n $unit${if (n == 1L) "" else "s"} ago"
    }

    fun quoteSentence(r: QuoteRowUi): String {
        val price = r.price?.takeIf { it.isFinite() } ?: return spokenName(r) + "."
        val body = StringBuilder(spokenName(r)).append(", ").append(spokenValue(r, price))
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
        var name = words.joinToString(" ").replace(FUTURES_TAIL, "")
        words = name.split(' ').filter { it.isNotEmpty() }
        while (words.size > 1 && words.last().lowercase(Locale.ROOT) in NAME_SUFFIXES) words = words.dropLast(1)
        if (words.size > 1 && words.last().equals(r.currency, ignoreCase = true)) words = words.dropLast(1)
        if (words.isEmpty() || words.size > 3 || words.joinToString(" ").length > 24) return null
        return speakable(words.joinToString(" "))
    }

    /** "TSX" to "T S X", "S&P" to "S and P"; ordinary words stay as they are. */
    fun speakable(text: String): String {
        var t = text.replace("&", " and ").replace("/", " ").replace("_", " ")
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
            val pts = BigDecimal(price.toString()).setScale(if (price >= 1000) 0 else 2, RoundingMode.HALF_UP).toPlainString()
            return "$pts points"
        }
        if (FX.matches(r.symbol)) return BigDecimal(price.toString()).setScale(r.decimals.coerceIn(0, 6), RoundingMode.HALF_UP).toPlainString()
        return spokenMoney(price, r.currency, r.decimals)
    }

    /** Money in words: "41 dollars 25 cents Canadian", "414 dollars 60 cents U S", whole dollars from 10000 up. */
    fun spokenMoney(price: Double, currency: String, decimals: Int = 2): String {
        if (!price.isFinite()) return ""
        val code = currency.uppercase(Locale.ROOT)
        if (code == "GBX") {
            val p = BigDecimal(price.toString()).setScale(if (price >= 100) 0 else 1, RoundingMode.HALF_UP)
            return trimZero(p.toPlainString()) + " pence"
        }
        val (major, minor, tail) = when (code) {
            "CAD" -> Triple("dollar", "cent", " Canadian")
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

    /** Up to [MAX_HEADLINES]: watchlist-tagged ones first (newest first as given), then the rest. */
    fun pickHeadlines(all: List<Headline>): List<Headline> {
        val tagged = all.filter { it.tags.isNotEmpty() }
        val rest = all.filter { it.tags.isEmpty() }
        return (tagged + rest).take(MAX_HEADLINES)
    }

    private val SOURCE_SUFFIX = Regex("\\s+[-|]\\s+[^-|]{2,30}$")

    /** Title shortened for speech: no source suffix, at most 14 words, no trailing punctuation, abbreviations spelled. */
    fun shortTitle(title: String): String {
        var t = PlainText.clean(title, 200)
        val stripped = t.replace(SOURCE_SUFFIX, "")
        val suffix = t.substring(stripped.length).trimStart(' ', '-', '|')
        // A trailing " - Reuters" or " | CBC News" is a source name: short and capitalised. A lower-case clause is part of the headline.
        if (stripped.length >= 12 && words(suffix) <= 4 && suffix.firstOrNull()?.isUpperCase() == true) t = stripped
        val ws = t.split(' ').filter { it.isNotEmpty() }
        if (ws.size > HEADLINE_WORDS) t = ws.take(HEADLINE_WORDS).joinToString(" ")
        t = t.trimEnd('.', ',', ';', ':', '-', '!', '?', '…', ' ')
        return speakable(t)
    }

    /** Last line of defence: nothing markup-like or dash-like reaches the speech engine. */
    private fun scrub(s: String): String =
        s.replace(EM_DASH, " ").replace("–", " ").replace(Regex("[<>]"), " ").replace(Regex("\\s+"), " ").trim()
}
