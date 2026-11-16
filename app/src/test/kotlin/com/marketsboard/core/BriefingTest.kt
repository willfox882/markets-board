package com.marketsboard.core

import com.marketsboard.data.news.Headline
import com.marketsboard.data.news.ms
import com.marketsboard.ui.markets.HeaderUi
import com.marketsboard.ui.markets.LiveKind
import com.marketsboard.ui.markets.QuoteRowUi
import com.marketsboard.ui.markets.RowStatus
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefingTest {
    private val toronto = ZoneId.of("America/Toronto")

    // 2026-11-16 is standard time in Toronto (UTC-5).
    private fun at(localHour: Int) = ms("2026-11-16T%02d:15:00Z".format((localHour + 5) % 24)).let { if (localHour + 5 >= 24) it + 86_400_000L else it }
    private val morning = at(8)
    private val hourMs = 3_600_000L

    private fun row(
        symbol: String, name: String, price: Double?, prev: Double?, currency: String, decimals: Int = 2, status: RowStatus = RowStatus.Fresh, exchange: String = "TSX",
    ) = QuoteRowUi(symbol, name, exchange, currency, price, prev, emptyList(), decimals, status)

    private val xeqt = row("XEQT.TO", "iShares Core Equity ETF Portfolio", 41.25, 43.24, "CAD")
    private val msft = row("MSFT", "Microsoft Corporation", 414.60, 435.96, "USD", exchange = "NASDAQ")

    private fun head(title: String, tags: List<String> = emptyList(), id: String = title, ageHours: Long = 2) =
        Headline(id, title, "", "https://e.com/${id.hashCode()}", "CBC", morning - ageHours * hourMs, tags)

    private fun build(
        rows: List<QuoteRowUi> = listOf(xeqt, msft), heads: List<Headline> = emptyList(), now: Long = morning, header: HeaderUi = HeaderUi(LiveKind.Live, "Live"),
        offline: Boolean = false, spoken: Set<String> = emptySet(), cache: Boolean = false,
    ) = Briefings.build(BriefingInput(rows, heads, now, toronto, header, offline, spoken, cache))

    private fun script(b: Briefing) = b.script

    // ---- the owner's wording ---------------------------------------------------------------------------------------------

    @Test fun aCanadianListenerHearsTheCurrencyOnlyWhenItIsNotCanadianDollars() {
        val s = script(build())
        assertTrue(s, s.startsWith("Good morning. Here is your market briefing."))
        assertTrue(s, s.contains("X E Q T, 41 dollars 25 cents, down 4.6 percent."))
        assertTrue(s, s.contains("Microsoft, 414 dollars 60 cents U S, down 4.9 percent."))
        assertFalse(s.contains("Canadian"))
    }

    @Test fun anUtteranceListWithPausesForTheSpeechLayer() {
        val b = build(heads = listOf(head("Bank of Canada holds rate")))
        assertEquals(
            listOf(UtteranceKind.Greeting, UtteranceKind.Quote, UtteranceKind.Quote, UtteranceKind.HeadlinesIntro, UtteranceKind.Headline),
            b.utterances.map { it.kind },
        )
        assertTrue(b.utterances.all { it.pauseAfterMs in 250..600 && it.text.endsWith(".") })
        assertEquals(b.script, b.utterances.joinToString(" ") { it.text })
        assertFalse(b.isEmpty)
        assertEquals(listOf("Bank of Canada holds rate"), b.headlineIds)
    }

    // ---- time of day -------------------------------------------------------------------------------------------------

    @Test fun greetingFollowsTheLocalHour() {
        assertEquals("Hello.", Briefings.greeting(at(3), toronto))
        assertEquals("Hello.", Briefings.greeting(at(4), toronto))
        assertEquals("Good morning.", Briefings.greeting(at(5), toronto))
        assertEquals("Good morning.", Briefings.greeting(at(11), toronto))
        assertEquals("Good afternoon.", Briefings.greeting(at(12), toronto))
        assertEquals("Good afternoon.", Briefings.greeting(at(16), toronto))
        assertEquals("Good evening.", Briefings.greeting(at(17), toronto))
        assertEquals("Good evening.", Briefings.greeting(at(21), toronto))
        assertEquals("Hello.", Briefings.greeting(at(22), toronto))
        assertEquals("Hello.", Briefings.greeting(at(23), toronto))
    }

    @Test fun theZoneChangesTheGreetingNotTheClockReading() {
        val t = ms("2026-11-16T14:00:00Z") // 9 AM Toronto, 6 AM Vancouver, 3 PM Paris
        assertEquals("Good morning.", Briefings.greeting(t, toronto))
        assertEquals("Good morning.", Briefings.greeting(t, ZoneId.of("America/Vancouver")))
        assertEquals("Good afternoon.", Briefings.greeting(t, ZoneId.of("Europe/Paris")))
    }

    // ---- numbers -----------------------------------------------------------------------------------------------------

    @Test fun moneyInWords() {
        assertEquals("41 dollars 25 cents", Briefings.spokenMoney(41.25, "CAD"))
        assertEquals("414 dollars 60 cents U S", Briefings.spokenMoney(414.6, "USD"))
        assertEquals("1 dollar 1 cent U S", Briefings.spokenMoney(1.01, "USD"))
        assertEquals("41 dollars", Briefings.spokenMoney(41.0, "CAD"))
        assertEquals("50 cents", Briefings.spokenMoney(0.5, "CAD"))
        assertEquals("1 dollar", Briefings.spokenMoney(0.999, "CAD"))
        assertEquals("100 dollars", Briefings.spokenMoney(99.999, "CAD"))
        assertEquals("12 euros 30 cents", Briefings.spokenMoney(12.3, "EUR"))
        assertEquals("3 pounds 50 pence", Briefings.spokenMoney(3.5, "GBP"))
        assertEquals("1234 yen", Briefings.spokenMoney(1234.4, "JPY"))
        assertEquals("125 pence", Briefings.spokenMoney(125.0, "GBX"))
        assertEquals("12.5 pence", Briefings.spokenMoney(12.5, "GBX"))
    }

    @Test fun negativePricesSayMinus() {
        assertEquals("minus 37 dollars 63 cents U S", Briefings.spokenMoney(-37.63, "USD"))
        assertEquals("minus 3 dollars 20 cents", Briefings.spokenMoney(-3.2, "CAD"))
        val oil = row("CL=F", "Crude Oil", -37.63, 18.0, "USD")
        assertTrue(Briefings.quoteSentence(oil), Briefings.quoteSentence(oil).startsWith("Crude Oil, minus 37 dollars 63 cents U S"))
        assertEquals("minus 12.35 points", Briefings.spokenValue(row("^X", "X", -12.345, 1.0, ""), -12.345))
    }

    @Test fun tinyPricesAreSpokenAsADecimal() {
        assertEquals("0.0345 dollars", Briefings.spokenMoney(0.0345, "CAD"))
        assertEquals("0.05 dollars U S", Briefings.spokenMoney(0.05, "USD"))
    }

    @Test fun largeValuesDropTheCentsFromTenThousand() {
        assertEquals("9999 dollars 99 cents", Briefings.spokenMoney(9999.99, "CAD"))
        assertEquals("123457 dollars", Briefings.spokenMoney(123_456.78, "CAD"))
        assertEquals("10000 dollars U S", Briefings.spokenMoney(10_000.4, "USD"))
    }

    @Test fun otherCurrenciesSpellTheCode() {
        assertEquals("12.30 C H F", Briefings.spokenMoney(12.3, "CHF"))
        assertEquals("12.30", Briefings.spokenMoney(12.3, ""))
    }

    @Test fun changeWordsAndRounding() {
        fun c(p: Double, prev: Double) = Briefings.change(row("X", "X", p, prev, "CAD"))
        assertEquals("up 0.4 percent", c(100.4, 100.0))
        assertEquals("down 4.6 percent", c(95.4, 100.0))
        assertEquals("up 12.3 percent", c(112.3, 100.0))
        assertEquals("up 5 percent", c(105.0, 100.0))
        assertEquals("unchanged", c(100.0, 100.0))
        assertEquals("rounds to zero is unchanged", "unchanged", c(100.04, 100.0))
        assertEquals("down 0.1 percent", c(99.9, 100.0))
        assertEquals(null, Briefings.change(row("X", "X", 5.0, null, "CAD")))
        assertEquals(null, Briefings.change(row("X", "X", 5.0, 0.0, "CAD")))
        assertEquals(null, Briefings.change(row("X", "X", null, 5.0, "CAD")))
    }

    @Test fun aRowWithoutAChangeJustHasThePrice() {
        val s = script(build(rows = listOf(row("XEQT.TO", "iShares Core Equity ETF Portfolio", 41.25, null, "CAD"))))
        assertTrue(s, s.contains("X E Q T, 41 dollars 25 cents."))
        assertFalse(s.contains("percent"))
    }

    // ---- names, indices, FX ------------------------------------------------------------------------------------------

    @Test fun shortNamesAreSaidAndLongOnesFallBackToTheSpelledTicker() {
        assertEquals("Microsoft", Briefings.spokenName(msft))
        assertEquals("X E Q T", Briefings.spokenName(xeqt))
        assertEquals("R Y", Briefings.spokenName(row("RY.TO", "Royal Bank of Canada", 1.0, 1.0, "CAD")))
        assertEquals("Shopify", Briefings.spokenName(row("SHOP.TO", "Shopify Inc.", 1.0, 1.0, "CAD")))
        assertEquals("S H O P", Briefings.spokenName(row("SHOP.TO", "SHOP.TO", 1.0, 1.0, "CAD")))
        assertEquals("Gold", Briefings.spokenName(row("GC=F", "Gold Dec 26", 1.0, 1.0, "USD")))
        assertEquals("Bitcoin", Briefings.spokenName(row("BTC-CAD", "Bitcoin CAD", 1.0, 1.0, "CAD")))
    }

    @Test fun indicesSpeakPointsAndSpellTheirAcronyms() {
        val tsx = row("^GSPTSE", "S&P/TSX Composite Index", 25_412.329, 25_300.0, "CAD", exchange = "TSX")
        assertEquals("S and P T S X Composite", Briefings.spokenName(tsx))
        assertEquals("S and P T S X Composite, 25412 points, up 0.4 percent.", Briefings.quoteSentence(tsx))
        assertEquals("1235 points", Briefings.spokenValue(row("^X", "X", 1234.5678, 1.0, ""), 1234.5678))
        assertEquals("18.46 points", Briefings.spokenValue(row("^VIX", "CBOE Volatility Index", 18.456, 20.0, ""), 18.456))
    }

    @Test fun currencyPairsSpeakTheRate() {
        val fx = row("USDCAD=X", "USD/CAD", 1.37124, 1.3685, "CAD", decimals = 4, exchange = "FX")
        assertEquals("The U S dollar buys 1 dollar 37 Canadian, up 0.2 percent.", Briefings.quoteSentence(fx))
        val under = row("USDCAD=X", "USD/CAD", 1.3, 1.3, "CAD", decimals = 4)
        assertEquals("The U S dollar buys 1 dollar 30 Canadian, unchanged.", Briefings.quoteSentence(under))
        val cadUsd = row("CADUSD=X", "CAD/USD", 0.7293, 0.73, "USD", decimals = 4)
        assertEquals("The Canadian dollar buys 73 cents U S, down 0.1 percent.", Briefings.quoteSentence(cadUsd))
        val eur = row("EURGBP=X", "EUR/GBP", 0.8421, 0.8421, "GBP", decimals = 4)
        assertEquals("The euro buys 84 pence, unchanged.", Briefings.quoteSentence(eur))
        assertTrue(Briefings.spokenName(row("NZDJPY=X", "NZD/JPY", 90.0, 90.0, "JPY")).startsWith("N Z D to"))
    }

    @Test fun acronymsMoneyAndPercentInSpeech() {
        assertEquals("T S X rises", Briefings.speakable("TSX rises"))
        assertEquals("U S inflation", Briefings.speakable("U.S. inflation"))
        assertEquals("S and P 500", Briefings.speakable("S&P 500"))
        assertEquals("the Fed holds", Briefings.speakable("the Fed holds"))
        assertEquals("Tsx stays", Briefings.speakable("Tsx stays"))
        assertEquals("rises 4 percent", Briefings.speakable("rises 4%"))
    }

    @Test fun moneyAbbreviationsAreSaidSensibly() {
        assertEquals("1.2 billion Canadian dollars deal", Briefings.speakable("C\$1.2B deal"))
        assertEquals("a 350 million dollars loss", Briefings.speakable("a \$350M loss"))
        assertEquals("2 trillion U S dollars", Briefings.speakable("US\$2T"))
        assertEquals("4120.60 dollars an ounce", Briefings.speakable("\$4,120.60 an ounce"))
        assertEquals("5 thousand dollars", Briefings.speakable("\$5K"))
        assertEquals("3 million Canadian dollars", Briefings.speakable("CA\$3 million"))
        assertEquals("Bought for 5 billion dollars", Briefings.speakable("Bought for \$5 billion"))
    }

    // ---- caveats -----------------------------------------------------------------------------------------------------

    @Test fun staleRowsGetTheDelayedCaveatWithTheAgeFromStructuredValues() {
        val rows = listOf(xeqt.copy(status = RowStatus.Stale("whatever text", 300_000)), msft.copy(status = RowStatus.Stale("display only", 720_000)))
        val s = script(build(rows, header = HeaderUi(LiveKind.Delayed, "Delayed")))
        assertTrue(s, s.contains("Prices may be delayed, last updated 12 minutes ago."))
        assertTrue(s.indexOf("Prices may be delayed") < s.indexOf("X E Q T"))
    }

    @Test fun ageWordsAreSingularAndPluralised() {
        assertEquals("a moment ago", Briefings.speakAgeMs(20_000))
        assertEquals("1 minute ago", Briefings.speakAgeMs(60_000))
        assertEquals("1 minute ago", Briefings.speakAgeMs(90_000))
        assertEquals("59 minutes ago", Briefings.speakAgeMs(59 * 60_000L))
        assertEquals("1 hour ago", Briefings.speakAgeMs(3_600_000))
        assertEquals("2 hours ago", Briefings.speakAgeMs(2 * 3_600_000L + 5))
        assertEquals("3 days ago", Briefings.speakAgeMs(3 * 86_400_000L))
    }

    @Test fun errorRowsAlsoTriggerTheCaveatAndAnUnknownAgeIsLeftOut() {
        val rows = listOf(xeqt.copy(status = RowStatus.Error("Can't reach the data service", "9 min ago", 9 * 60_000L)), msft)
        assertTrue(script(build(rows)).contains("Prices may be delayed, last updated 9 minutes ago."))
        val unknown = listOf(xeqt.copy(status = RowStatus.Stale("", null)))
        val s = script(build(unknown, header = HeaderUi(LiveKind.Delayed, "Delayed")))
        assertTrue(s, s.contains("Prices may be delayed."))
        assertFalse(s.contains("last updated"))
    }

    @Test fun offlineCaveatReplacesTheDelayedOne() {
        val rows = listOf(xeqt.copy(status = RowStatus.Error("x", "30 min ago", 30 * 60_000L)), msft)
        val s = script(build(rows, offline = true))
        assertTrue(s, s.contains("You appear to be offline, so these prices may be old, last updated 30 minutes ago."))
        assertFalse(s.contains("Prices may be delayed"))
        assertTrue(script(build(listOf(xeqt, msft), header = HeaderUi(LiveKind.Offline, "Offline"))).contains("You appear to be offline"))
    }

    @Test fun marketsClosedNote() {
        val s = script(build(header = HeaderUi(LiveKind.Closed, "Markets closed")))
        assertTrue(s, s.contains("Markets are closed."))
        assertFalse(script(build()).contains("closed"))
        assertFalse("not said when offline", script(build(header = HeaderUi(LiveKind.Closed, "Closed"), offline = true)).contains("Markets are closed"))
    }

    @Test fun headlinesFromTheCacheAreAnnounced() {
        val s = script(build(heads = listOf(head("Bank of Canada holds rate")), cache = true))
        assertTrue(s, s.contains("Headlines may be out of date. Top headlines. Bank of Canada holds rate."))
        assertFalse(script(build(heads = listOf(head("Bank of Canada holds rate")))).contains("out of date"))
    }

    // ---- degenerate inputs -------------------------------------------------------------------------------------------

    @Test fun emptyWatchlistStillGreetsAndStillReadsHeadlines() {
        val s = script(build(rows = emptyList(), heads = listOf(head("Bank of Canada holds rate"))))
        assertTrue(s, s.contains("Your watchlist is empty."))
        assertTrue(s.contains("Top headlines. Bank of Canada holds rate."))
    }

    @Test fun rowsWithoutPricesSayPricesAreNotAvailable() {
        val s = script(build(rows = listOf(row("XEQT.TO", "x", null, null, "CAD", status = RowStatus.Loading))))
        assertTrue(s, s.contains("Prices are not available yet."))
    }

    @Test fun anInstrumentWithoutAPriceIsMentionedNotSilentlySkipped() {
        val s = script(build(rows = listOf(row("A", "Alpha", null, null, "USD", status = RowStatus.Error("x")), msft)))
        assertTrue(s, s.contains("Microsoft, 414 dollars 60 cents U S"))
        assertTrue(s, s.contains("No price available for Alpha."))
        assertTrue("priced ones come first", s.indexOf("Microsoft") < s.indexOf("No price available"))
    }

    @Test fun noNewHeadlinesIsSaidWhenThereAreNone() {
        assertTrue(script(build()).contains("No new headlines."))
        val old = script(build(heads = listOf(head("Old story from yesterday", ageHours = 30))))
        assertTrue(old, old.contains("No new headlines."))
        assertFalse(old.contains("Old story"))
    }

    @Test fun zeroAndOddNumbersDoNotBreakTheSentences() {
        val s = Briefings.quoteSentence(row("CL=F", "Oil", 0.0, 0.0, "USD"))
        assertTrue(s, s.startsWith("Oil, "))
        Briefings.quoteSentence(row("OIL", "Oil", 5.0, -3.0, "USD"))
        assertEquals("No price available for Oil.", Briefings.quoteSentence(row("CL=F", "Oil", Double.NaN, 3.0, "USD")))
        Briefings.quoteSentence(row("OIL", "Oil", 5.0, Double.NaN, "USD"))
        Briefings.quoteSentence(row("OIL", "Oil", 1e12, 1e12, "USD"))
    }

    // ---- headlines ---------------------------------------------------------------------------------------------------

    @Test fun upToThreeHeadlinesPreferringWatchlistOnes() {
        val heads = listOf(head("First general story"), head("Second general story"), head("Microsoft ships thing", listOf("MSFT")), head("Third general story"), head("XEQT hits record", listOf("XEQT")))
        val picked = Briefings.pickHeadlines(heads, morning).map { it.title }
        assertEquals(listOf("Microsoft ships thing", "XEQT hits record", "First general story"), picked)
        val s = script(build(heads = heads))
        assertTrue(s, s.contains("Top headlines. Microsoft ships thing. XEQT hits record. First general story."))
        assertFalse(s.contains("Second general story"))
    }

    @Test fun onlyTheLastTwentyFourHoursAndOnlyHeadlinesNotReadBefore() {
        val heads = listOf(head("Read already", id = "r1"), head("Fresh one", id = "f1"), head("Day old", id = "d1", ageHours = 25), head("Edge of the day", id = "e1", ageHours = 24))
        val b = build(heads = heads, spoken = setOf("r1"))
        assertEquals(listOf("f1", "e1"), b.headlineIds)
        assertTrue(b.script, b.script.contains("Fresh one. Edge of the day."))
        assertFalse(b.script.contains("Read already") || b.script.contains("Day old"))
        // the ids returned go straight back in next time
        val next = build(heads = heads, spoken = setOf("r1") + b.headlineIds)
        assertTrue(next.script, next.script.contains("No new headlines."))
        assertEquals(emptyList<String>(), next.headlineIds)
    }

    @Test fun longHeadlinesCutAtAClauseBoundaryAndNeverEndOnAFillerWord() {
        val long = "Canada's largest pension funds warn that higher-for-longer interest rates and slowing global growth could squeeze returns across private markets for years to come"
        val t = Briefings.shortTitle(long)
        assertTrue(t, t.split(' ').size <= 14)
        assertFalse(t, t.lowercase().split(' ').last() in setOf("the", "a", "of", "to", "as", "could", "and"))
        assertEquals("Oil falls as OPEC signals more supply for next year, Reuters reports", Briefings.shortTitle("Oil falls as OPEC signals more supply for next year, Reuters reports"))
        // comma boundary inside the first 14 words
        val cut = Briefings.shortTitle("Bank of Canada holds key rate at 2.25 per cent, governor says inflation is cooling faster than expected this fall")
        assertEquals("Bank of Canada holds key rate at 2.25 per cent", cut)
        // " as " boundary
        assertEquals("Toronto stocks rally on Tuesday", Briefings.shortTitle("Toronto stocks rally on Tuesday as investors cheer the latest inflation data from Statistics Canada this morning"))
        // dangling words are dropped on a hard cut
        val hard = Briefings.shortTitle("one two three four five six seven eight nine ten eleven twelve thirteen the fourteen fifteen")
        assertEquals("one two three four five six seven eight nine ten eleven twelve thirteen", hard)
        assertEquals("Oil prices slip as OPEC signals more supply", Briefings.shortTitle("Oil prices slip as OPEC signals more supply - Reuters"))
        assertEquals("Oil prices slip as OPEC signals more supply", Briefings.shortTitle("Oil prices slip as OPEC signals more supply | CBC News"))
        assertEquals("a - b stays when the rest is long", Briefings.shortTitle("a - b stays when the rest is long"))
    }

    @Test fun headlineTextIsPlainNoHtmlAndNoEmDash() {
        val s = script(build(heads = listOf(head("<b>Stocks</b> fall${Char(0x2014)}again & &amp; more"), head("TSX rallies – energy leads"))))
        assertFalse(s, s.contains('<') || s.contains('>'))
        assertFalse(s, s.contains(Char(0x2014)) || s.contains('–'))
        assertTrue(s, s.contains("Stocks fall - again"))
        assertTrue(s, s.contains("T S X rallies - energy leads"))
    }

    @Test fun moneyInAHeadlineIsSpoken() {
        val s = script(build(heads = listOf(head("Loblaw buys rival for C\$1.2B"))))
        assertTrue(s, s.contains("Loblaw buys rival for 1.2 billion Canadian dollars."))
    }

    // ---- budget, determinism -----------------------------------------------------------------------------------------

    @Test fun speakingTimeCountsDigitsAndSpelledLettersHeavier() {
        val words = Briefings.estimateSeconds("one two three four")
        val digits = Briefings.estimateSeconds("12345678 2026")
        val letters = Briefings.estimateSeconds("X E Q T")
        assertTrue("$digits vs $words", digits > words)
        assertTrue("$letters vs $words", letters > words)
        assertEquals(0.0, Briefings.estimateSeconds("   "), 0.0)
        assertTrue(Briefings.estimateSeconds("Hello, world.") > Briefings.estimateSeconds("Hello world"))
    }

    @Test fun typicalBriefingIsAboutFortySecondsOfSpeech() {
        val heads = listOf(head("Bank of Canada holds key rate at 2.25 per cent as inflation cools", listOf("MSFT")), head("TSX edges higher as energy and bank shares lead gains"), head("Microsoft unveils new AI features for Windows and Office"))
        val b = build(heads = heads)
        assertTrue("seconds=${b.estimatedSeconds}", b.estimatedSeconds in 15.0..Briefings.MAX_SECONDS)
        assertTrue("words=${b.wordCount}", b.wordCount in 40..Briefings.MAX_WORDS)
    }

    @Test fun neverOverTheHardCapHeadlinesGoFirstThenInstruments() {
        val many = (1..12).map { row("SYM$it.TO", "Company number $it", 10.0 + it, 9.0, "CAD") }
        val heads = (1..3).map { head("A fairly long headline number $it about markets and other things", id = "h$it") }
        val b = build(many, heads)
        assertTrue("words=${b.wordCount} seconds=${b.estimatedSeconds}", b.wordCount <= Briefings.MAX_WORDS && b.estimatedSeconds <= Briefings.MAX_SECONDS)
        assertTrue(b.script.contains("more are on screen."))
        assertTrue(b.utterances.count { it.kind == UtteranceKind.Quote } >= 1)
        assertEquals("ids only list what was actually spoken", b.utterances.count { it.kind == UtteranceKind.Headline }, b.headlineIds.size)
        val few = build(listOf(xeqt, msft), heads)
        assertEquals(3, few.utterances.count { it.kind == UtteranceKind.Headline })
        assertEquals(3, few.headlineIds.size)
    }

    @Test fun numberHeavyListsAreTrimmedByTimeEvenWhenTheWordCountIsLow() {
        val rows = (1..16).map { row("S$it.TO", "Company $it", 123_456.0 + it, 120_000.0, "CAD") }
        val b = build(rows)
        assertTrue("seconds=${b.estimatedSeconds}", b.estimatedSeconds <= Briefings.MAX_SECONDS)
        assertNotEquals(16, b.utterances.count { it.kind == UtteranceKind.Quote })
    }

    @Test fun deterministicForTheSameInput() {
        val heads = listOf(head("One story here"), head("Two story there", listOf("MSFT")))
        assertEquals(build(heads = heads), build(heads = heads))
    }

    @Test fun noEmDashesOrMarkupAnywhereInAnyScenario() {
        val scenarios = listOf(
            build(), build(emptyList()), build(offline = true), build(header = HeaderUi(LiveKind.Closed, "c")),
            build(heads = listOf(head("A<script>x</script>B ${Char(0x2014)} C"))), build(listOf(xeqt.copy(status = RowStatus.Stale("3 d ago", 3 * 86_400_000L)))),
            build(cache = true, heads = listOf(head("x y z w"))),
        )
        for (b in scenarios) {
            assertFalse(b.script, b.script.contains(Char(0x2014)) || b.script.contains('–'))
            assertFalse(b.script, b.script.contains('<') || b.script.contains('>') || b.script.contains("&amp;"))
            assertTrue(b.script, b.script.trim() == b.script && !b.script.contains("  "))
        }
    }
}
