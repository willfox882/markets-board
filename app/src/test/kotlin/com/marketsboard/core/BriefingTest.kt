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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefingTest {
    private val toronto = ZoneId.of("America/Toronto")
    // 2026-11-16 is standard time in Toronto (UTC-5).
    private fun at(localHour: Int) = ms("2026-11-16T%02d:15:00Z".format((localHour + 5) % 24)).let { if (localHour + 5 >= 24) it + 86_400_000L else it }
    private val morning = at(8)

    private fun row(
        symbol: String, name: String, price: Double?, prev: Double?, currency: String, decimals: Int = 2, status: RowStatus = RowStatus.Fresh, exchange: String = "TSX",
    ) = QuoteRowUi(symbol, name, exchange, currency, price, prev, emptyList(), decimals, status)

    private val xeqt = row("XEQT.TO", "iShares Core Equity ETF Portfolio", 41.25, 43.24, "CAD")
    private val msft = row("MSFT", "Microsoft Corporation", 414.60, 435.96, "USD", exchange = "NASDAQ")

    private fun head(title: String, tags: List<String> = emptyList(), id: String = title) =
        Headline(id, title, "", "https://e.com/${id.hashCode()}", "CBC", ms("2026-11-16T12:00:00Z"), tags)

    private fun build(
        rows: List<QuoteRowUi> = listOf(xeqt, msft), heads: List<Headline> = emptyList(), now: Long = morning, header: HeaderUi = HeaderUi(LiveKind.Live, "Live"), offline: Boolean = false,
    ) = Briefings.build(BriefingInput(rows, heads, now, toronto, header, offline))

    private fun script(b: Briefing) = b.script

    // ---- the owner's example -----------------------------------------------------------------------------------

    @Test fun speaksTheDefaultWatchlistInTheirNativeCurrencies() {
        val s = script(build())
        assertTrue(s, s.startsWith("Good morning. Here is your market briefing."))
        assertTrue(s, s.contains("X E Q T, 41 dollars 25 cents Canadian, down 4.6 percent."))
        assertTrue(s, s.contains("Microsoft, 414 dollars 60 cents U S, down 4.9 percent."))
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
    }

    // ---- time of day -------------------------------------------------------------------------------------------

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

    // ---- numbers -----------------------------------------------------------------------------------------------

    @Test fun moneyInWords() {
        assertEquals("41 dollars 25 cents Canadian", Briefings.spokenMoney(41.25, "CAD"))
        assertEquals("414 dollars 60 cents U S", Briefings.spokenMoney(414.6, "USD"))
        assertEquals("1 dollar 1 cent U S", Briefings.spokenMoney(1.01, "USD"))
        assertEquals("41 dollars Canadian", Briefings.spokenMoney(41.0, "CAD"))
        assertEquals("50 cents Canadian", Briefings.spokenMoney(0.5, "CAD"))
        assertEquals("1 dollar Canadian", Briefings.spokenMoney(0.999, "CAD"))
        assertEquals("100 dollars Canadian", Briefings.spokenMoney(99.999, "CAD"))
        assertEquals("12 euros 30 cents", Briefings.spokenMoney(12.3, "EUR"))
        assertEquals("3 pounds 50 pence", Briefings.spokenMoney(3.5, "GBP"))
        assertEquals("1234 yen", Briefings.spokenMoney(1234.4, "JPY"))
        assertEquals("125 pence", Briefings.spokenMoney(125.0, "GBX"))
        assertEquals("12.5 pence", Briefings.spokenMoney(12.5, "GBX"))
    }

    @Test fun tinyPricesAreSpokenAsADecimal() {
        assertEquals("0.0345 dollars Canadian", Briefings.spokenMoney(0.0345, "CAD"))
        assertEquals("0.05 dollars U S", Briefings.spokenMoney(0.05, "USD"))
    }

    @Test fun largeValuesDropTheCentsFromTenThousand() {
        assertEquals("9999 dollars 99 cents Canadian", Briefings.spokenMoney(9999.99, "CAD"))
        assertEquals("123457 dollars Canadian", Briefings.spokenMoney(123_456.78, "CAD"))
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
        assertNull("no previous close", Briefings.change(row("X", "X", 5.0, null, "CAD")))
        assertNull("zero previous close", Briefings.change(row("X", "X", 5.0, 0.0, "CAD")))
        assertNull("no price", Briefings.change(row("X", "X", null, 5.0, "CAD")))
    }

    @Test fun aRowWithoutAChangeJustHasThePrice() {
        val s = script(build(rows = listOf(row("XEQT.TO", "iShares Core Equity ETF Portfolio", 41.25, null, "CAD"))))
        assertTrue(s, s.contains("X E Q T, 41 dollars 25 cents Canadian."))
        assertFalse(s.contains("percent"))
    }

    // ---- names, indices, FX ------------------------------------------------------------------------------------

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
        val s = Briefings.quoteSentence(tsx)
        assertEquals("S and P T S X Composite, 25412 points, up 0.4 percent.", s)
        assertEquals("1235 points", Briefings.spokenValue(row("^X", "X", 1234.5678, 1.0, ""), 1234.5678))
    }

    @Test fun smallIndexValuesKeepTwoDecimals() {
        val vix = row("^VIX", "CBOE Volatility Index", 18.456, 20.0, "")
        assertEquals("18.46 points", Briefings.spokenValue(vix, 18.456))
    }

    @Test fun currencyPairsSpeakTheRate() {
        val fx = row("USDCAD=X", "USD/CAD", 1.39124, 1.3885, "CAD", decimals = 4, exchange = "FX")
        assertEquals("U S dollar to Canadian dollar, 1.3912, up 0.2 percent.", Briefings.quoteSentence(fx))
        val odd = row("EURGBP=X", "EUR/GBP", 0.8421, 0.8421, "GBP", decimals = 4)
        assertEquals("euro to pound, 0.8421, unchanged.", Briefings.quoteSentence(odd))
        assertTrue(Briefings.spokenName(row("NZDJPY=X", "NZD/JPY", 90.0, 90.0, "JPY")).startsWith("N Z D to"))
    }

    @Test fun acronymsAndSymbolsInSpeech() {
        assertEquals("T S X rises", Briefings.speakable("TSX rises"))
        assertEquals("U S inflation", Briefings.speakable("U.S. inflation"))
        assertEquals("S and P 500", Briefings.speakable("S&P 500"))
        assertEquals("the Fed holds", Briefings.speakable("the Fed holds"))
        assertEquals("Tsx stays", Briefings.speakable("Tsx stays"))
    }

    // ---- caveats -----------------------------------------------------------------------------------------------

    @Test fun staleRowsGetTheDelayedCaveatWithTheAge() {
        val rows = listOf(xeqt.copy(status = RowStatus.Stale("5 min ago")), msft.copy(status = RowStatus.Stale("12 min ago")))
        val s = script(build(rows, header = HeaderUi(LiveKind.Delayed, "Delayed")))
        assertTrue(s, s.contains("Prices may be delayed, last updated 12 minutes ago."))
        // the caveat comes before the prices
        assertTrue(s.indexOf("Prices may be delayed") < s.indexOf("X E Q T"))
    }

    @Test fun ageWordsAreSingularAndPluralised() {
        assertEquals("1 minute ago", Briefings.speakAge("1 min ago"))
        assertEquals("2 hours ago", Briefings.speakAge("2 h ago"))
        assertEquals("1 hour ago", Briefings.speakAge("1 h ago"))
        assertEquals("3 days ago", Briefings.speakAge("3 d ago"))
        assertEquals("just now", Briefings.speakAge("just now"))
    }

    @Test fun errorRowsAlsoTriggerTheCaveatAndAnUnknownAgeIsLeftOut() {
        val rows = listOf(xeqt.copy(status = RowStatus.Error("Can't reach the data service", "9 min ago")), msft)
        assertTrue(script(build(rows)).contains("Prices may be delayed, last updated 9 minutes ago."))
        val unknown = listOf(xeqt.copy(status = RowStatus.Stale("")))
        val s = script(build(unknown, header = HeaderUi(LiveKind.Delayed, "Delayed")))
        assertTrue(s, s.contains("Prices may be delayed."))
        assertFalse(s.contains("last updated"))
    }

    @Test fun offlineCaveatReplacesTheDelayedOne() {
        val rows = listOf(xeqt.copy(status = RowStatus.Error("x", "30 min ago")), msft)
        val s = script(build(rows, offline = true))
        assertTrue(s, s.contains("You appear to be offline, so these prices may be old, last updated 30 minutes ago."))
        assertFalse(s.contains("Prices may be delayed"))
        val viaHeader = script(build(listOf(xeqt, msft), header = HeaderUi(LiveKind.Offline, "Offline")))
        assertTrue(viaHeader.contains("You appear to be offline"))
    }

    @Test fun marketsClosedNote() {
        val s = script(build(header = HeaderUi(LiveKind.Closed, "Markets closed")))
        assertTrue(s, s.contains("Markets are closed."))
        assertFalse(script(build()).contains("closed"))
        assertFalse("not said when offline", script(build(header = HeaderUi(LiveKind.Closed, "Closed"), offline = true)).contains("Markets are closed"))
    }

    // ---- degenerate inputs -------------------------------------------------------------------------------------

    @Test fun emptyWatchlistStillGreetsAndStillReadsHeadlines() {
        val s = script(build(rows = emptyList(), heads = listOf(head("Bank of Canada holds rate"))))
        assertTrue(s, s.contains("Your watchlist is empty."))
        assertTrue(s.contains("Top headlines. Bank of Canada holds rate."))
    }

    @Test fun rowsWithoutPricesSayPricesAreNotAvailable() {
        val s = script(build(rows = listOf(row("XEQT.TO", "x", null, null, "CAD", status = RowStatus.Loading))))
        assertTrue(s, s.contains("Prices are not available yet."))
    }

    @Test fun unpricedRowsAreSkippedButOthersRead() {
        val s = script(build(rows = listOf(row("A", "Alpha", null, null, "USD", status = RowStatus.Loading), msft)))
        assertFalse(s.contains("Alpha"))
        assertTrue(s.contains("Microsoft"))
    }

    @Test fun noHeadlinesMeansNoHeadlinesSection() {
        val s = script(build())
        assertFalse(s.contains("headlines"))
    }

    @Test fun zeroAndNegativeNumbersDoNotBreakTheSentences() {
        val s = Briefings.quoteSentence(row("CL=F", "Oil", 0.0, 0.0, "USD"))
        assertTrue(s, s.startsWith("Oil, "))
        Briefings.quoteSentence(row("OIL", "Oil", 5.0, -3.0, "USD"))
        Briefings.quoteSentence(row("OIL", "Oil", Double.NaN, 3.0, "USD"))
        Briefings.quoteSentence(row("OIL", "Oil", 5.0, Double.NaN, "USD"))
        Briefings.quoteSentence(row("OIL", "Oil", 1e12, 1e12, "USD"))
    }

    // ---- headlines ---------------------------------------------------------------------------------------------

    @Test fun upToThreeHeadlinesPreferringWatchlistOnes() {
        val heads = listOf(head("First general story"), head("Second general story"), head("Microsoft ships thing", listOf("MSFT")), head("Third general story"), head("XEQT hits record", listOf("XEQT")))
        val picked = Briefings.pickHeadlines(heads).map { it.title }
        assertEquals(listOf("Microsoft ships thing", "XEQT hits record", "First general story"), picked)
        val s = script(build(heads = heads))
        assertTrue(s, s.contains("Top headlines. Microsoft ships thing. XEQT hits record. First general story."))
        assertFalse(s.contains("Second general story"))
    }

    @Test fun longHeadlinesAreShortenedAndSourceSuffixesDropped() {
        val long = "Canada's largest pension funds warn that higher for longer interest rates and slowing global growth could squeeze returns across private markets for years to come"
        val t = Briefings.shortTitle(long)
        assertTrue(t, t.split(' ').size <= 14)
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

    // ---- budget, determinism -----------------------------------------------------------------------------------

    @Test fun typicalBriefingIsAboutFortySecondsOfSpeech() {
        val heads = listOf(head("Bank of Canada holds key rate at 2.25 per cent as inflation cools", listOf("MSFT")), head("TSX edges higher as energy and bank shares lead gains"), head("Microsoft unveils new AI features for Windows and Office"))
        val b = build(heads = heads)
        assertTrue("words=${b.wordCount}", b.wordCount in 40..110)
    }

    @Test fun neverMoreThan110WordsHeadlinesGoFirstThenInstruments() {
        val many = (1..12).map { row("SYM$it.TO", "Company number $it", 10.0 + it, 9.0, "CAD") }
        val heads = (1..3).map { head("A fairly long headline number $it about markets and other things", id = "h$it") }
        val b = build(many, heads)
        assertTrue("words=${b.wordCount}", b.wordCount <= Briefings.MAX_WORDS)
        assertTrue(b.script.contains("more are on screen."))
        assertTrue(b.utterances.count { it.kind == UtteranceKind.Quote } >= 1)
        val few = build(listOf(xeqt, msft), heads)
        assertEquals(3, few.utterances.count { it.kind == UtteranceKind.Headline })
    }

    @Test fun deterministicForTheSameInput() {
        val heads = listOf(head("One story here"), head("Two story there", listOf("MSFT")))
        assertEquals(build(heads = heads), build(heads = heads))
    }

    @Test fun noEmDashesOrMarkupAnywhereInAnyScenario() {
        val scenarios = listOf(
            build(), build(emptyList()), build(offline = true), build(header = HeaderUi(LiveKind.Closed, "c")),
            build(heads = listOf(head("A<script>x</script>B ${Char(0x2014)} C"))), build(listOf(xeqt.copy(status = RowStatus.Stale("3 d ago")))),
        )
        for (b in scenarios) {
            assertFalse(b.script, b.script.contains(Char(0x2014)) || b.script.contains('–'))
            assertFalse(b.script, b.script.contains('<') || b.script.contains('>') || b.script.contains("&amp;"))
            assertNotNull(b.script)
            assertTrue(b.script, b.script.trim() == b.script && !b.script.contains("  "))
        }
    }
}
