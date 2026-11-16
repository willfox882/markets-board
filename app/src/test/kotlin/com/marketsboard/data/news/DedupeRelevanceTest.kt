package com.marketsboard.data.news

import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.store.WatchlistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupeTest {
    private fun h(title: String, url: String, pub: Long = NOW_MS, summary: String = "", tags: List<String> = emptyList(), src: String = "S") =
        Headline(Dedupe.idFor(url), title, summary, url, src, pub, tags)

    private fun merge(vararg hs: Headline) = Dedupe.merge(hs.mapIndexed { i, x -> Dedupe.Entry(x, i) })

    @Test fun canonicalUrlIgnoresTrackingSchemeWwwFragmentAndTrailingSlash() {
        val base = Dedupe.canonicalUrl("https://example.com/news/story-1")
        assertEquals(base, Dedupe.canonicalUrl("http://www.example.com/news/story-1/"))
        assertEquals(base, Dedupe.canonicalUrl("https://example.com/news/story-1?utm_source=rss&utm_medium=feed&fbclid=abc#comments"))
        assertEquals(base, Dedupe.canonicalUrl("https://m.example.com/news/story-1?cmpid=rss&guccounter=1"))
        assertEquals(base, Dedupe.canonicalUrl("HTTPS://EXAMPLE.COM/NEWS/story-1"))
    }

    @Test fun realQueryParametersStayAndAreSorted() {
        assertEquals(Dedupe.canonicalUrl("https://e.com/a?id=5&page=2"), Dedupe.canonicalUrl("https://e.com/a?page=2&id=5&utm_campaign=x"))
        assertNotEquals(Dedupe.canonicalUrl("https://e.com/a?id=5"), Dedupe.canonicalUrl("https://e.com/a?id=6"))
        assertNotEquals(Dedupe.canonicalUrl("https://e.com/a"), Dedupe.canonicalUrl("https://e.com/b"))
        assertNotEquals(Dedupe.canonicalUrl("https://a.com/x"), Dedupe.canonicalUrl("https://b.com/x"))
    }

    @Test fun canonicalUrlNeverThrowsOnJunk() {
        assertEquals("not a url", Dedupe.canonicalUrl(" Not A Url "))
        Dedupe.canonicalUrl("https://exa mple.com/a b")
        Dedupe.canonicalUrl("")
    }

    @Test fun idIsStableForTheSamePageAndDifferentOtherwise() {
        assertEquals(Dedupe.idFor("https://e.com/a?utm_source=x"), Dedupe.idFor("https://www.e.com/a/"))
        assertNotEquals(Dedupe.idFor("https://e.com/a"), Dedupe.idFor("https://e.com/b"))
        assertEquals(16, Dedupe.idFor("https://e.com/a").length)
    }

    @Test fun normalisedTitlesIgnoreCasePunctuationAndAccents() {
        assertEquals("cafe opens in montreal", Dedupe.normalizeTitle("Café opens, in Montréal!"))
        assertEquals(Dedupe.normalizeTitle("Bank of Canada: holds rate"), Dedupe.normalizeTitle("BANK OF CANADA - holds rate"))
    }

    @Test fun diceScores() {
        assertEquals(1.0, Dedupe.dice(setOf("a", "b"), setOf("a", "b")), 1e-9)
        assertEquals(0.0, Dedupe.dice(setOf("a"), setOf("b")), 1e-9)
        assertEquals(0.0, Dedupe.dice(emptySet(), setOf("b")), 1e-9)
        assertEquals(0.75, Dedupe.dice(setOf("a", "b", "c", "d"), setOf("a", "b", "c", "e")), 1e-9)
    }

    @Test fun sameUrlFromTwoFeedsKeepsOneAndTheOldestReport() {
        val a = h("Story one title here now", "https://e.com/s1?utm_source=a", NOW_MS - 3_600_000, src = "A")
        val b = h("Story one title here now", "https://www.e.com/s1", NOW_MS - 7_200_000, src = "B")
        val r = merge(a, b)
        assertEquals(1, r.size)
        assertEquals("B", r[0].sourceName)
        assertEquals(NOW_MS - 7_200_000, r[0].publishedMs)
    }

    @Test fun sameTimeTieKeepsTheItemWithTheLongerSummaryThenTheEarlierFeed() {
        val a = h("Tie breaker headline story", "https://a.com/x", summary = "", src = "A")
        val b = h("Tie breaker headline story", "https://b.com/y", summary = "A summary", src = "B")
        assertEquals("B", merge(a, b).single().sourceName)
        val c = h("Another tie headline story", "https://a.com/x2", summary = "same", src = "A")
        val d = h("Another tie headline story", "https://b.com/y2", summary = "same", src = "B")
        assertEquals("A", merge(c, d).single().sourceName)
    }

    @Test fun nearDuplicateTitlesAcrossSourcesAreMerged() {
        val a = h("Microsoft stock slides as investors weigh cloud spending", "https://a.com/1", NOW_MS - 600_000, src = "A")
        val b = h("Microsoft stock slides as investors weigh heavy cloud spending", "https://b.com/2", NOW_MS - 300_000, src = "B")
        val r = merge(b, a)
        assertEquals(1, r.size)
        assertEquals("A", r[0].sourceName)
    }

    @Test fun wireCopyWithTheSameSummaryAndRelatedTitlesIsMerged() {
        val text = "The central bank left its policy rate unchanged on Wednesday and said inflation is moving back toward its target."
        val a = h("Bank of Canada holds rate steady as inflation cools, governor says", "https://a.com/1", NOW_MS - 10, summary = text, src = "A")
        val b = h("Bank of Canada holds key rate at 2.25 per cent as inflation cools", "https://b.com/2", NOW_MS - 20, summary = text, src = "B")
        assertEquals(listOf("B"), merge(a, b).map { it.sourceName })
        // the same summary under an unrelated title is not enough
        val c = h("Completely unrelated sports result tonight", "https://c.com/3", NOW_MS, summary = text, src = "C")
        assertEquals(2, merge(a, c).size)
        // and similar titles with different summaries stay apart
        val d = h("Bank of Canada holds key rate at 2.25 per cent as inflation cools", "https://d.com/4", NOW_MS - 20, summary = "Another outlet wrote its own, completely different, summary here.", src = "D")
        assertEquals(2, merge(a, d).size)
    }

    @Test fun differentStoriesWithSimilarWordsAreNotMerged() {
        val a = h("Bank of Canada holds key rate at 2.25 per cent", "https://a.com/1")
        val b = h("Bank of Canada governor speaks on housing and immigration policy", "https://b.com/2")
        assertEquals(2, merge(a, b).size)
        val c = h("TSX edges higher", "https://a.com/3")
        val d = h("TSX edges lower", "https://b.com/4")
        assertEquals("short titles need an exact match", 2, merge(c, d).size)
    }

    @Test fun nearTitlesFarApartInTimeAreNotMerged() {
        // near matches need a 36 h window
        val a = h("Weekly market wrap: stocks end lower on tech selloff", "https://a.com/w1", NOW_MS)
        val b = h("Weekly market wrap: stocks end lower on tech selloff again", "https://a.com/w2", NOW_MS - 5 * 86_400_000L)
        assertEquals(2, merge(a, b).size)
    }

    @Test fun tagsOfTheGroupAreMergedIntoTheKeptItem() {
        val a = h("Shared story about Microsoft and ETFs today", "https://a.com/1", NOW_MS - 1000, tags = listOf("MSFT"))
        val b = h("Shared story about Microsoft and ETFs today", "https://b.com/2", NOW_MS, tags = listOf("XEQT", "MSFT"))
        val r = merge(a, b).single()
        assertEquals(listOf("MSFT", "XEQT"), r.tags)
    }

    @Test fun manyItemsStayFast() {
        val items = (1..600).map { h("Report ${it}a ${it}b ${it}c ${it}d", "https://e.com/p/$it", NOW_MS - it * 60_000L) }
        val t0 = System.nanoTime()
        val r = merge(*items.toTypedArray())
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 3_000)
        assertEquals(600, r.size)
    }

    @Test fun orderOfInputDoesNotChangeTheResult() {
        val a = h("Alpha bravo charlie delta echo foxtrot", "https://a.com/1", NOW_MS - 10, src = "A")
        val b = h("Alpha bravo charlie delta echo foxtrot golf", "https://b.com/2", NOW_MS - 20, src = "B")
        val c = h("Completely different news item today", "https://c.com/3", NOW_MS, src = "C")
        assertEquals(merge(a, b, c).map { it.id }.sorted(), merge(c, b, a).map { it.id }.sorted())
    }
}

class RelevanceTest {
    private val defaults = SymbolMatcher(WatchlistStore.DEFAULTS)

    @Test fun defaultsMatchTickersNamesAndTheFundFamily() {
        assertEquals(listOf("MSFT"), defaults.match("Microsoft unveils new AI features"))
        assertEquals(listOf("MSFT"), defaults.match("Shares of MSFT fell 2% on Tuesday"))
        assertEquals(listOf("XEQT"), defaults.match("Why XEQT is so popular"))
        assertEquals(listOf("XEQT"), defaults.match("iShares adds new low-cost funds"))
        assertEquals(listOf("XEQT"), defaults.match("The (TSX:XEQT) fund"))
        assertEquals(listOf("XEQT", "MSFT"), defaults.match("iShares and Microsoft both rose"))
    }

    @Test fun matchingIsWordBoundedAndTickersAreCaseSensitive() {
        assertTrue(defaults.match("Microsofty is a made-up word").isEmpty())
        assertTrue(defaults.match("amicrosoft").isEmpty())
        assertTrue(defaults.match("see msft in a url slug").isEmpty())
        assertTrue(defaults.match("MSFTX is something else").isEmpty())
        assertEquals(listOf("MSFT"), defaults.match("MICROSOFT IN CAPS"))
        assertEquals(listOf("MSFT"), defaults.match("microsoft in lower case"))
        assertEquals(listOf("MSFT"), defaults.match("Microsoft's results"))
        assertEquals(listOf("MSFT"), defaults.match("Microsoft-owned LinkedIn"))
    }

    @Test fun nothingMatchesBlankTextOrAnEmptyWatchlist() {
        assertTrue(defaults.match("").isEmpty())
        assertTrue(defaults.match("   ").isEmpty())
        assertTrue(SymbolMatcher(emptyList()).match("Microsoft").isEmpty())
    }

    @Test fun displayNameOverrideAlsoMatches() {
        val m = SymbolMatcher(listOf(WatchItem("SHOP.TO", "Shopify Inc.", "CAD", "TSX", "My ecommerce bet")))
        assertEquals(listOf("SHOP"), m.match("Shopify raises forecast"))
        assertEquals(listOf("SHOP"), m.match("My ecommerce bet jumps"))
        assertEquals(listOf("SHOP"), m.match("SHOP.TO rallies"))
    }

    @Test fun indicesAndFxMatchOnTheirNamesNotTheirSymbols() {
        val m = SymbolMatcher(
            listOf(
                WatchItem("^GSPTSE", "S&P/TSX Composite Index", "CAD", "TSX"),
                WatchItem("USDCAD=X", "USD/CAD", "CAD", "FX"),
                WatchItem("CL=F", "Crude Oil Dec 26", "USD", "NYMEX"),
            ),
        )
        assertEquals(listOf("S&P/TSX"), m.match("The S&P/TSX Composite closed higher"))
        assertEquals(listOf("USD/CAD"), m.match("USD/CAD hits 1.40"))
        assertTrue("GSPTSE is not a word in news", m.match("GSPTSE").isEmpty())
        assertTrue("generic words such as crude are not enough", m.match("Crude oil falls").isEmpty())
    }

    @Test fun maxThreeTagsInWatchlistOrder() {
        val items = listOf("AAAA", "BBBB", "CCCC", "DDDD").map { WatchItem(it, it, "USD", "") }
        val m = SymbolMatcher(items)
        assertEquals(listOf("AAAA", "BBBB", "CCCC"), m.match("DDDD CCCC BBBB AAAA"))
    }

    @Test fun genericFirstWordsDoNotMatchByThemselves() {
        val m = SymbolMatcher(listOf(WatchItem("RY.TO", "Royal Bank of Canada", "CAD", "TSX")))
        assertEquals(listOf("RY"), m.match("Royal Bank of Canada earnings"))
        assertTrue(m.match("The royal family and the bank").isEmpty())
    }

    @Test fun labelOf() {
        assertEquals("XEQT", Relevance.labelOf(WatchItem("XEQT.TO", "x", "CAD", "")))
        assertEquals("BTC", Relevance.labelOf(WatchItem("BTC-CAD", "Bitcoin CAD", "CAD", "")))
        assertEquals("Gold", Relevance.labelOf(WatchItem("GC=F", "Gold Dec 26", "USD", "")))
        assertFalse(Relevance.labelOf(WatchItem("^GSPTSE", "S&P/TSX Composite", "CAD", "")).isEmpty())
    }
}
