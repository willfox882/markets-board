package com.marketsboard.data.news

import com.marketsboard.data.FakeClock
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.store.LoadStatus
import com.marketsboard.data.store.WatchlistStore
import com.marketsboard.data.tempDir
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedStoreTest {
    private val clock = FakeClock()

    private fun store(dir: File = tempDir()) = FeedStore(File(dir, "feeds.json"), clock) to dir

    @Test fun freshStoreHasTheFourDefaultFeedsAndPersistsThem() {
        val (s, dir) = store()
        assertEquals(LoadStatus.Fresh, s.loadStatus)
        assertEquals(listOf("cbc-business", "globe-business", "financial-post", "yahoo-top", "marketwatch-top"), s.feeds.value.map { it.id })
        assertEquals("https://financialpost.com/feed", s.feeds.value.first { it.id == "financial-post" }.url)
        assertTrue(s.feeds.value.all { it.enabled && it.url.startsWith("https://") })
        assertEquals("https://www.cbc.ca/webfeed/rss/rss-business", s.feeds.value[0].url)
        assertTrue(s.symbolFeeds.value)
        assertTrue(File(dir, "feeds.json").isFile)
    }

    @Test fun editsSurviveARestart() {
        val (s, dir) = store()
        assertTrue(s.add("My blog", "https://example.com/feed.xml") is FeedEdit.Done)
        assertTrue(s.setEnabled("globe-business", false) is FeedEdit.Done)
        assertTrue(s.rename("cbc-business", "  CBC   News ") is FeedEdit.Done)
        assertTrue(s.move(5, 0) is FeedEdit.Done)
        assertTrue(s.setSymbolFeeds(false) is FeedEdit.Done)
        val again = FeedStore(File(dir, "feeds.json"), clock)
        assertEquals(LoadStatus.Loaded, again.loadStatus)
        assertEquals(s.feeds.value, again.feeds.value)
        assertEquals("My blog", again.feeds.value[0].name)
        assertFalse(again.feeds.value.first { it.id == "globe-business" }.enabled)
        assertEquals("CBC News", again.feeds.value.first { it.id == "cbc-business" }.name)
        assertFalse(again.symbolFeeds.value)
    }

    @Test fun removeAndResetToDefaults() {
        val (s, _) = store()
        assertTrue(s.remove("yahoo-top") is FeedEdit.Done)
        assertEquals(4, s.feeds.value.size)
        assertEquals(FeedEdit.NotFound, s.remove("nope"))
        assertTrue(s.resetToDefaults() is FeedEdit.Done)
        assertEquals(DefaultFeeds.ALL, s.feeds.value)
    }

    @Test fun addValidatesTheUrlAndRefusesDuplicates() {
        val (s, _) = store()
        for (bad in listOf("javascript:alert(1)", "ftp://example.com/a", "file:///etc/passwd", "example.com/feed", "", "https://", "https://a b.com/")) {
            assertTrue("$bad should be refused", s.add("x", bad) is FeedEdit.Invalid)
        }
        val dup = s.add("again", "https://www.cbc.ca/webfeed/rss/rss-business?utm_source=x")
        assertTrue(dup is FeedEdit.Duplicate)
        assertEquals("cbc-business", (dup as FeedEdit.Duplicate).existing.id)
        assertEquals(5, s.feeds.value.size)
    }

    @Test fun feedsMustBeHttpsWhateverTheCase() {
        val (s, dir) = store()
        for (bad in listOf("http://example.com/feed.xml", "HTTP://example.com/feed.xml", "Http://example.com/f")) {
            val r = s.add("x", bad)
            assertTrue("$bad: $r", r is FeedEdit.Invalid && r.reason.contains("https"))
        }
        assertTrue(s.add("ok", "HTTPS://example.com/feed.xml") is FeedEdit.Done)
        // a stored http feed (hand edited, or from an older build) is dropped on load, never fetched in clear
        File(dir, "feeds.json").writeText("""{"version":1,"feeds":[{"id":"a","name":"Plain","url":"http://e.com/a"},{"id":"b","name":"Secure","url":"https://e.com/b"}]}""")
        assertEquals(listOf("b"), FeedStore(File(dir, "feeds.json"), clock).feeds.value.map { it.id })
    }

    @Test fun addGivesAnEmptyNameTheHostAndKeepsIdsUnique() {
        val (s, _) = store()
        s.add("", "https://news.example.org/rss")
        assertEquals("news.example.org", s.feeds.value.last().name)
        val ids = s.feeds.value.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun limitOfTwentyFeeds() {
        val (s, _) = store()
        var i = 0
        while (s.feeds.value.size < FeedStore.MAX_FEEDS) assertTrue(s.add("f$i", "https://e.com/$i").also { i++ } is FeedEdit.Done)
        assertEquals(FeedEdit.LimitReached, s.add("one more", "https://e.com/last"))
    }

    @Test fun renameRejectsBlankAndMoveRejectsBadIndexes() {
        val (s, _) = store()
        assertTrue(s.rename("cbc-business", "   ") is FeedEdit.Invalid)
        assertTrue(s.move(0, 9) is FeedEdit.Invalid)
        assertTrue(s.move(-1, 0) is FeedEdit.Invalid)
        assertEquals(FeedEdit.NotFound, s.setEnabled("nope", false))
    }

    @Test fun symbolKindIsPersisted() {
        val (s, dir) = store()
        s.add("MSFT news", "https://example.com/msft.xml", FeedKind.Symbol, " MSFT ")
        val f = FeedStore(File(dir, "feeds.json"), clock).feeds.value.last()
        assertEquals(FeedKind.Symbol, f.kind)
        assertEquals("MSFT", f.symbol)
    }

    @Test fun corruptFileIsKeptAsideAndDefaultsReturn() {
        val dir = tempDir()
        File(dir, "feeds.json").writeText("{ this is not json")
        val s = FeedStore(File(dir, "feeds.json"), clock)
        assertEquals(LoadStatus.RecoveredFromCorrupt, s.loadStatus)
        assertEquals(DefaultFeeds.ALL, s.feeds.value)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("feeds.corrupt-") })
        // and the repaired file is valid again
        assertEquals(LoadStatus.Loaded, FeedStore(File(dir, "feeds.json"), clock).loadStatus)
    }

    @Test fun truncatedFileRecoversFromTheBackup() {
        val (s, dir) = store()
        s.add("Keep me", "https://example.com/keep.xml")
        s.setEnabled("cbc-business", false) // second write: the first good copy becomes feeds.bak
        File(dir, "feeds.json").writeText("")
        val r = FeedStore(File(dir, "feeds.json"), clock)
        assertEquals(LoadStatus.RecoveredFromBackup, r.loadStatus)
        assertTrue(r.feeds.value.any { it.name == "Keep me" })
    }

    @Test fun badEntriesAreDroppedGoodOnesKeptAndTheOriginalIsCopiedAside() {
        val dir = tempDir()
        File(dir, "feeds.json").writeText(
            """{"version":1,"feeds":[{"id":"a","name":"Good","url":"https://e.com/a"},{"id":"b","name":"Bad","url":"javascript:x"},{"id":"a","name":"Dup id","url":"https://e.com/z"},{"name":"No id","url":"https://e.com/q"}]}""",
        )
        val s = FeedStore(File(dir, "feeds.json"), clock)
        assertEquals(listOf("a"), s.feeds.value.map { it.id })
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("feeds.corrupt-") })
    }

    @Test fun newerVersionIsReadOnlyAndNeverOverwritten() {
        val dir = tempDir()
        val text = """{"version":9,"feeds":[{"id":"a","name":"Future","url":"https://e.com/a","extra":1}]}"""
        File(dir, "feeds.json").writeText(text)
        val s = FeedStore(File(dir, "feeds.json"), clock)
        assertEquals(LoadStatus.NewerVersionReadOnly, s.loadStatus)
        assertTrue(s.readOnly)
        assertEquals("Future", s.feeds.value.single().name)
        assertEquals(FeedEdit.ReadOnly, s.add("x", "https://e.com/x"))
        assertEquals(FeedEdit.ReadOnly, s.remove("a"))
        assertEquals(text, File(dir, "feeds.json").readText())
    }

    @Test fun autoFeedsAreNeverWritten() {
        val (s, dir) = store()
        s.setEnabled("cbc-business", true)
        assertFalse(File(dir, "feeds.json").readText().contains("sym:"))
        val enc = FeedStore.encode(FeedPlan.autoFeeds(WatchlistStore.DEFAULTS) + DefaultFeeds.ALL, true)
        assertFalse(enc.contains("sym:"))
    }

    @Test fun failedWriteKeepsTheEditInMemoryAndRetries() {
        val dir = tempDir()
        val (s, _) = store(dir)
        File(dir, "feeds.json").delete()
        File(dir, "feeds.json").mkdir() // a directory where the file should be: the atomic move fails
        File(dir, "feeds.json/x").writeText("x")
        assertTrue((s.add("Mem", "https://example.com/m.xml") as FeedEdit.Done).persisted.not())
        assertTrue(s.feeds.value.any { it.name == "Mem" })
        assertTrue(s.persistError.value != null)
        File(dir, "feeds.json/x").delete(); File(dir, "feeds.json").delete()
        assertTrue(s.flushIfDirty())
        assertNull(s.persistError.value)
        assertTrue(FeedStore(File(dir, "feeds.json"), clock).feeds.value.any { it.name == "Mem" })
    }
}

class FeedPlanTest {
    private val watch = listOf("XEQT.TO", "MSFT", "^GSPTSE", "USDCAD=X", "BTC-CAD", "SHOP.TO", "RY.TO", "TD.TO").map { WatchItem(it, it, "CAD", "") }

    @Test fun symbolFeedUrlIsTheYahooHeadlineFeedWithEncodedSymbol() {
        assertEquals("https://feeds.finance.yahoo.com/rss/2.0/headline?s=MSFT&region=CA&lang=en-CA", FeedPlan.symbolFeedUrl("MSFT"))
        assertEquals("https://feeds.finance.yahoo.com/rss/2.0/headline?s=XEQT.TO&region=CA&lang=en-CA", FeedPlan.symbolFeedUrl("XEQT.TO"))
        assertEquals("https://feeds.finance.yahoo.com/rss/2.0/headline?s=%5EGSPTSE&region=CA&lang=en-CA", FeedPlan.symbolFeedUrl("^GSPTSE"))
        assertEquals("https://feeds.finance.yahoo.com/rss/2.0/headline?s=USDCAD%3DX&region=CA&lang=en-CA", FeedPlan.symbolFeedUrl("USDCAD=X"))
    }

    @Test fun autoFeedsAreCappedAtSixInWatchlistOrder() {
        val auto = FeedPlan.autoFeeds(watch)
        assertEquals(6, auto.size)
        assertEquals(listOf("XEQT.TO", "MSFT", "^GSPTSE", "USDCAD=X", "BTC-CAD", "SHOP.TO"), auto.map { it.symbol })
        assertTrue(auto.all { it.auto && it.kind == FeedKind.Symbol && it.enabled })
        assertEquals(auto.size, auto.map { it.id }.toSet().size)
    }

    @Test fun effectiveFeedsSkipDisabledOnesAndHonourTheSwitch() {
        val stored = DefaultFeeds.ALL.map { if (it.id == "yahoo-top") it.copy(enabled = false) else it }
        val on = FeedPlan.effective(stored, watch.take(2), true)
        assertEquals(listOf("cbc-business", "globe-business", "financial-post", "marketwatch-top", "sym:XEQT.TO", "sym:MSFT"), on.map { it.id })
        val off = FeedPlan.effective(stored, watch.take(2), false)
        assertEquals(4, off.size)
        assertTrue(FeedPlan.effective(stored, emptyList(), true).none { it.auto })
    }
}
