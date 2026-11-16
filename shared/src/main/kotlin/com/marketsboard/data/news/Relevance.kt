package com.marketsboard.data.news

import com.marketsboard.data.model.WatchItem
import java.util.Locale
import java.util.regex.Pattern

/**
 * Which headlines are about the owner's watchlist. A headline is tagged with an item's label when its title or
 * summary mentions the ticker (upper case, whole word, so `MSFT` and `(TSX:XEQT)` match but `msft` in a URL slug does
 * not), the company name, or a distinctive first word of the name ("Microsoft", "iShares"). Name matching is
 * case-insensitive and word-bounded. Index, currency and futures symbols (`^GSPTSE`, `USDCAD=X`, `CL=F`) match on their
 * names only.
 */
class SymbolMatcher(items: List<WatchItem>) {
    private class Rule(val label: String, val ticker: Pattern?, val names: List<Pattern>)

    private val rules: List<Rule> = items.mapNotNull { rule(it) }
    val labels: List<String> = rules.map { it.label }

    /** Labels (watchlist order, at most [max]) of every item mentioned in [text]. */
    fun match(text: String, max: Int = 3): List<String> {
        if (text.isBlank() || rules.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (r in rules) {
            if (out.size >= max) break
            val hit = (r.ticker?.matcher(text)?.find() == true) || r.names.any { it.matcher(text).find() }
            if (hit && r.label !in out) out.add(r.label)
        }
        return out
    }

    private companion object {
        val SUFFIXES = setOf(
            "inc", "inc.", "corp", "corp.", "corporation", "ltd", "ltd.", "limited", "co", "co.", "company", "plc", "etf", "trust", "fund",
            "group", "holdings", "holding", "class", "common", "shares", "share", "units", "unit", "ordinary", "the", "portfolio", "index",
            "sa", "nv", "ag", "lp", "llc", "adr", "registered", "new", "de", "cv",
        )
        val FUND_WORDS = setOf("etf", "fund", "trust", "index", "portfolio", "funds")
        val GENERIC_FIRST = setOf(
            "royal", "canadian", "american", "national", "united", "first", "global", "general", "north", "south", "west", "east",
            "bank", "international", "capital", "financial", "energy", "gold", "silver", "crude", "natural",
            "toronto", "vancouver", "wti", "brent", "nasdaq", "dow", "tsx", "s&p", "composite", "usd", "cad",
        )

        fun word(text: String, ignoreCase: Boolean): Pattern {
            val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
            return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(text) + "(?![\\p{L}\\p{N}])", flags)
        }

        fun rule(item: WatchItem): Rule? {
            val sym = item.symbol.trim()
            if (sym.isEmpty()) return null
            val special = sym.startsWith("^") || sym.contains('=')
            val base = sym.removePrefix("^").split('.', '=', '-').firstOrNull().orEmpty()
            val ticker = if (!special && base.length in 2..8 && base.all { it.isLetterOrDigit() }) word(base.uppercase(Locale.ROOT), false) else null
            val nameSources = listOfNotNull(item.displayName, item.name).map { it.trim() }.filter { it.length >= 3 && !it.equals(sym, true) }
            val names = LinkedHashSet<String>()
            for (n in nameSources) {
                val words = n.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val isFund = words.any { it.lowercase(Locale.ROOT).trimEnd(',', '.') in FUND_WORDS }
                var core = words
                while (core.size > 1 && core.last().lowercase(Locale.ROOT).trimEnd(',', '.') in SUFFIXES) core = core.dropLast(1)
                val phrase = core.joinToString(" ").trimEnd(',', '.')
                if (phrase.length >= 4 && core.size >= 2) names.add(phrase)
                val first = core.firstOrNull()?.trimEnd(',', '.').orEmpty()
                // A fund family ("iShares") names hundreds of funds: only the full fund name or the ticker may tag those.
                if (!isFund && first.length >= 5 && first.lowercase(Locale.ROOT) !in GENERIC_FIRST && first.any { it.isLetter() }) names.add(first)
                if (core.size == 1 && phrase.length >= 4 && phrase.lowercase(Locale.ROOT) !in GENERIC_FIRST) names.add(phrase)
            }
            if (ticker == null && names.isEmpty()) return null
            val label = if (!special && base.isNotEmpty()) base.uppercase(Locale.ROOT)
            else (item.displayName ?: item.name).split(' ').first().take(10).ifEmpty { sym }
            return Rule(label, ticker, names.map { word(it, true) })
        }
    }
}

/** Short label for a watchlist symbol as used in tags (`XEQT.TO` gives XEQT, `^GSPTSE` gives the first word of its name). */
object Relevance {
    fun labelOf(item: WatchItem): String {
        val sym = item.symbol
        val special = sym.startsWith("^") || sym.contains('=')
        val base = sym.removePrefix("^").split('.', '=', '-').firstOrNull().orEmpty()
        return if (!special && base.isNotEmpty()) base.uppercase(Locale.ROOT) else (item.displayName ?: item.name).split(' ').first().take(10).ifEmpty { sym }
    }
}
