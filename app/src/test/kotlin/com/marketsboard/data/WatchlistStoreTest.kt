package com.marketsboard.data

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.store.AddOutcome
import com.marketsboard.data.store.EditResult
import com.marketsboard.data.store.LoadStatus
import com.marketsboard.data.store.Symbols
import com.marketsboard.data.store.WatchlistStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistStoreTest {
    private val clock = FakeClock(1_700_000_000_000L)
    private fun file(dir: File = tempDir()) = File(dir, "watchlist.json")
    private fun store(f: File = file()) = WatchlistStore(f, clock)
    private fun item(s: String, name: String = s, cur: String = "USD") = WatchItem(s, name, cur, "NYSE")
    private fun WatchlistStore.syms() = items.value.map { it.symbol }

    // ---- defaults and persistence -------------------------------------------------------------------------------

    @Test fun defaultsAreExactlyXeqtAndMsft() {
        val s = store()
        assertEquals(
            listOf(
                WatchItem("XEQT.TO", "iShares Core Equity ETF Portfolio", "CAD", "TSX"),
                WatchItem("MSFT", "Microsoft Corporation", "USD", "NASDAQ"),
            ),
            s.items.value,
        )
        assertEquals(LoadStatus.Fresh, s.loadStatus)
    }

    @Test fun firstLoadWritesTheDefaultsFile() {
        val f = file()
        store(f)
        val text = f.readText()
        assertTrue(text.contains("\"version\": 1"))
        assertTrue(text.contains("XEQT.TO") && text.contains("MSFT"))
    }

    @Test fun editsPersistAcrossInstances() {
        val f = file()
        val a = store(f)
        a.add(item("AAPL", "Apple Inc."))
        a.rename("MSFT", "Microsoft")
        a.move(0, 2)
        val b = store(f)
        assertEquals(listOf("MSFT", "AAPL", "XEQT.TO"), b.syms())
        assertEquals("Microsoft", b.items.value[0].displayName)
        assertEquals("Apple Inc.", b.items.value[1].name)
        assertEquals(LoadStatus.Loaded, b.loadStatus)
    }

    @Test fun userCanEmptyTheListAndItStaysEmpty() {
        val f = file()
        val a = store(f)
        a.remove("XEQT.TO"); a.remove("MSFT")
        assertTrue(store(f).items.value.isEmpty())
    }

    @Test fun noTempFileLeftBehind() {
        val f = file()
        store(f).add(item("AAPL"))
        assertFalse(File(f.parentFile, "watchlist.json.tmp").exists())
    }

    // ---- operations ----------------------------------------------------------------------------------------------

    @Test fun addAppendsAndRejectsDuplicatesCaseInsensitively() {
        val s = store()
        assertTrue(s.add(item("aapl")) is EditResult.Done)
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL"), s.syms())
        val dup = s.add(item("  Aapl "))
        assertTrue(dup is EditResult.Duplicate)
        assertEquals(3, s.items.value.size)
    }

    @Test fun addAtIndex() {
        val s = store()
        s.add(item("AAPL"), atIndex = 0)
        s.add(item("NVDA"), atIndex = 99)
        assertEquals(listOf("AAPL", "XEQT.TO", "MSFT", "NVDA"), s.syms())
    }

    @Test fun removeAndRemoveUnknown() {
        val s = store()
        assertTrue(s.remove("msft") is EditResult.Done)
        assertEquals(listOf("XEQT.TO"), s.syms())
        assertEquals(EditResult.NotFound, s.remove("MSFT"))
        assertEquals(EditResult.NotFound, s.remove("!!!"))
    }

    @Test fun moveReorders() {
        val s = store()
        s.add(item("AAPL")); s.add(item("NVDA"))
        s.move(3, 0)
        assertEquals(listOf("NVDA", "XEQT.TO", "MSFT", "AAPL"), s.syms())
        s.move(0, 1)
        assertEquals(listOf("XEQT.TO", "NVDA", "MSFT", "AAPL"), s.syms())
    }

    @Test fun moveValidatesIndices() {
        val s = store()
        assertTrue(s.move(-1, 0) is EditResult.Invalid)
        assertTrue(s.move(0, 2) is EditResult.Invalid)
        assertTrue(s.move(5, 0) is EditResult.Invalid)
        assertTrue(s.move(1, 1) is EditResult.Done)
        assertEquals(listOf("XEQT.TO", "MSFT"), s.syms())
    }

    @Test fun renameSetsAndClearsDisplayName() {
        val s = store()
        s.rename("XEQT.TO", "  My   ETF ")
        assertEquals("My ETF", s.items.value[0].displayName)
        assertEquals("My ETF", s.items.value[0].shownName)
        s.rename("XEQT.TO", "   ")
        assertNull(s.items.value[0].displayName)
        s.rename("XEQT.TO", "x"); s.rename("XEQT.TO", null)
        assertNull(s.items.value[0].displayName)
        assertEquals(EditResult.NotFound, s.rename("NOPE", "x"))
    }

    @Test fun displayNameIsLengthLimited() {
        val s = store()
        s.rename("MSFT", "n".repeat(500))
        assertEquals(40, s.items.value[1].displayName!!.length)
    }

    @Test fun replaceKeepsPosition() {
        val s = store()
        s.add(item("AAPL"))
        assertTrue(s.replace("MSFT", WatchItem("GOOG", "Alphabet Inc.", "USD", "NASDAQ")) is EditResult.Done)
        assertEquals(listOf("XEQT.TO", "GOOG", "AAPL"), s.syms())
    }

    @Test fun replaceRejectsDuplicateAndUnknownAndAllowsSameSymbolUpdate() {
        val s = store()
        assertTrue(s.replace("MSFT", item("XEQT.TO")) is EditResult.Duplicate)
        assertEquals(EditResult.NotFound, s.replace("NOPE", item("AAPL")))
        assertTrue(s.replace("MSFT", item("MSFT", "Microsoft Corp", "USD")) is EditResult.Done)
        assertEquals("Microsoft Corp", s.items.value[1].name)
        assertEquals(listOf("XEQT.TO", "MSFT"), s.syms())
    }

    @Test fun resetToDefaultsRestoresAndPersists() {
        val f = file()
        val s = store(f)
        s.remove("MSFT"); s.add(item("AAPL"))
        s.resetToDefaults()
        assertEquals(listOf("XEQT.TO", "MSFT"), s.syms())
        assertEquals(listOf("XEQT.TO", "MSFT"), store(f).syms())
    }

    @Test fun maxThirtyItems() {
        val s = store()
        for (i in 1..28) assertTrue(s.add(item("T$i")) is EditResult.Done)
        assertEquals(30, s.items.value.size)
        assertEquals(EditResult.LimitReached, s.add(item("EXTRA")))
        assertEquals(30, s.items.value.size)
        s.remove("T1")
        assertTrue(s.add(item("EXTRA")) is EditResult.Done)
    }

    @Test fun editsUpdateTheStateFlowImmediately() {
        val s = store()
        val seen = mutableListOf<List<String>>()
        seen.add(s.syms())
        s.add(item("AAPL"))
        seen.add(s.syms())
        assertEquals(listOf("XEQT.TO", "MSFT"), seen[0])
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL"), seen[1])
    }

    // ---- normalisation -------------------------------------------------------------------------------------------

    @Test fun symbolNormalisationAcceptsYahooFormats() {
        assertEquals("XEQT.TO", Symbols.normalize(" xeqt.to "))
        assertEquals("^GSPTSE", Symbols.normalize("^gsptse"))
        assertEquals("USDCAD=X", Symbols.normalize("usdcad=x"))
        assertEquals("BTC-CAD", Symbols.normalize("btc-cad"))
        assertEquals("CL=F", Symbols.normalize("CL=F"))
    }

    @Test fun symbolNormalisationRejectsBadInput() {
        for (s in listOf("", "   ", "AB CD", "MSFT!", "A/B", "A,B", "ÄPFEL", "a".repeat(16), "<script>", "MS\nFT", "\$MSFT")) {
            assertNull("'$s' must be invalid", Symbols.normalize(s))
        }
        assertNull(Symbols.normalize(null))
        assertEquals("A".repeat(15), Symbols.normalize("a".repeat(15)))
        assertEquals("A", Symbols.normalize("a"))
    }

    @Test fun addRejectsInvalidSymbols() {
        val s = store()
        assertTrue(s.add(item("bad symbol")) is EditResult.Invalid)
        assertTrue(s.add(item("")) is EditResult.Invalid)
        assertEquals(2, s.items.value.size)
    }

    @Test fun addSanitisesTextFields() {
        val s = store()
        s.add(WatchItem("aapl", "  Apple\n  Inc. ", "usd", "  NASDAQ "))
        val a = s.items.value.last()
        assertEquals("AAPL", a.symbol); assertEquals("Apple Inc.", a.name); assertEquals("USD", a.currency); assertEquals("NASDAQ", a.exchange)
        s.add(WatchItem("zzz", "", "", ""))
        assertEquals("ZZZ", s.items.value.last().name) // blank name falls back to the symbol
    }

    // ---- corruption, versions, forward compatibility ------------------------------------------------------------

    @Test fun corruptFileIsMovedAsideAndDefaultsUsed() {
        val f = file()
        f.writeText("{ this is not json")
        val s = store(f)
        assertEquals(LoadStatus.RecoveredFromCorrupt, s.loadStatus)
        assertEquals(listOf("XEQT.TO", "MSFT"), s.syms())
        val backups = f.parentFile.listFiles()!!.filter { it.name.startsWith("watchlist.corrupt-") && it.name.endsWith(".json") }
        assertEquals(1, backups.size)
        assertEquals("watchlist.corrupt-${clock.nowMs()}.json", backups[0].name)
        assertEquals("{ this is not json", backups[0].readText())
        assertEquals(listOf("XEQT.TO", "MSFT"), store(f).syms()) // fresh valid file was written
    }

    @Test fun wrongShapesAreCorrupt() {
        for ((i, body) in listOf("[]", "{}", """{"version":1}""", """{"version":"1","items":[]}""", """{"version":0,"items":[]}""", """{"version":1,"items":{}}""", "").withIndex()) {
            clock.advance(1)
            val f = file()
            f.writeText(body)
            val s = store(f)
            assertEquals("case $i: $body", LoadStatus.RecoveredFromCorrupt, s.loadStatus)
            assertEquals(2, s.items.value.size)
        }
    }

    @Test fun repeatedCorruptionKeepsAtMostFiveBackups() {
        val f = file()
        for (i in 1..8) {
            clock.advance(1000)
            f.writeText("garbage $i")
            store(f)
        }
        val backups = f.parentFile.listFiles()!!.filter { it.name.startsWith("watchlist.corrupt-") }
        assertEquals(5, backups.size)
    }

    @Test fun unknownFieldsAreIgnored() {
        val f = file()
        f.writeText("""{"version":1,"theme":"x","items":[{"symbol":"AAPL","name":"Apple","currency":"USD","exchange":"NASDAQ","color":"red","extra":{"a":1}}]}""")
        val s = store(f)
        assertEquals(listOf("AAPL"), s.syms())
        assertEquals(LoadStatus.Loaded, s.loadStatus)
    }

    @Test fun missingOptionalFieldsDefault() {
        val f = file()
        f.writeText("""{"version":1,"items":[{"symbol":"aapl"}]}""")
        val a = store(f).items.value.single()
        assertEquals(WatchItem("AAPL", "AAPL", "", "", null), a)
    }

    @Test fun badItemsAreDroppedButOriginalIsPreserved() {
        val f = file()
        val original = """{"version":1,"items":[{"symbol":"AAPL","name":"Apple"},{"name":"no symbol"},"junk",{"symbol":"bad sym"},{"symbol":"aapl"},{"symbol":"NVDA"}]}"""
        f.writeText(original)
        val s = store(f)
        assertEquals(listOf("AAPL", "NVDA"), s.syms()) // junk, invalid and duplicate dropped
        assertEquals(LoadStatus.Loaded, s.loadStatus)
        val backup = f.parentFile.listFiles()!!.single { it.name.startsWith("watchlist.corrupt-") }
        assertEquals(original, backup.readText())
        assertEquals(original, f.readText()) // not rewritten until the user edits
    }

    @Test fun higherVersionIsReadOnlyAndNeverOverwritten() {
        val f = file()
        val future = """{"version":2,"items":[{"symbol":"AAPL","name":"Apple","newField":true}],"layout":"grid"}"""
        f.writeText(future)
        val s = store(f)
        assertEquals(LoadStatus.NewerVersionReadOnly, s.loadStatus)
        assertTrue(s.readOnly)
        assertEquals(listOf("XEQT.TO", "MSFT"), s.syms())
        assertEquals(EditResult.ReadOnly, s.add(item("NVDA")))
        assertEquals(EditResult.ReadOnly, s.remove("MSFT"))
        assertEquals(EditResult.ReadOnly, s.move(0, 1))
        assertEquals(EditResult.ReadOnly, s.rename("MSFT", "x"))
        assertEquals(EditResult.ReadOnly, s.replace("MSFT", item("GOOG")))
        assertEquals(EditResult.ReadOnly, s.resetToDefaults())
        assertEquals(future, f.readText())
        assertEquals(1, f.parentFile.listFiles()!!.size) // no backups or temp files either
    }

    @Test fun directoryInPlaceOfFileIsReadOnlyNotACrash() {
        val dir = tempDir()
        val f = File(dir, "watchlist.json").also { it.mkdirs() }
        val s = WatchlistStore(f, clock)
        assertEquals(listOf("XEQT.TO", "MSFT"), s.syms())
        assertTrue(s.readOnly)
    }

    @Test fun writeFailureKeepsEditInMemoryAndReportsIt() {
        val dir = tempDir()
        val s = WatchlistStore(File(dir, "watchlist.json"), clock)
        // Replace the directory with a file so the next write fails.
        dir.deleteRecursively()
        dir.writeText("now a file")
        val r = s.add(item("AAPL"))
        assertEquals(EditResult.Done(persisted = false), r)
        assertTrue(s.syms().contains("AAPL"))
        assertTrue(s.persistError.value != null)
        // Storage comes back: the next edit writes the full list including the earlier one.
        dir.delete(); dir.mkdirs()
        assertEquals(EditResult.Done(persisted = true), s.add(item("NVDA")))
        assertNull(s.persistError.value)
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL", "NVDA"), WatchlistStore(File(dir, "watchlist.json"), clock).syms())
    }

    @Test fun fileFormatIsVersionedJson() {
        val f = file()
        store(f).add(item("AAPL", "Apple"), null)
        val text = f.readText()
        assertTrue(text.contains("\"version\": 1"))
        assertTrue(text.contains("\"items\""))
        assertFalse(text.contains("displayName")) // omitted when null
        store(f).rename("AAPL", "Mine")
        assertTrue(f.readText().contains("\"displayName\": \"Mine\""))
    }

    // ---- validateAndAdd ------------------------------------------------------------------------------------------

    private fun provider(result: FetchResult<com.marketsboard.data.model.Quote>) = ScriptedProvider("p") { _, _ -> result }

    @Test fun validateAndAddFillsNameCurrencyExchangeFromQuote() {
        val s = store()
        val p = provider(FetchResult.Ok(quoteOf("SHOP.TO", 140.0, currency = "CAD", name = "Shopify Inc.", exchange = "Toronto")))
        val r = runBlocking { s.validateAndAdd(" shop.to ", p) }
        assertEquals(AddOutcome.Added(WatchItem("SHOP.TO", "Shopify Inc.", "CAD", "Toronto"), true), r)
        assertEquals(listOf("XEQT.TO", "MSFT", "SHOP.TO"), s.syms())
        assertEquals(listOf("SHOP.TO"), p.calls)
    }

    @Test fun validateAndAddUsesD1AndKeepsDisplayName() {
        var range: ChartRange? = null
        val p = ScriptedProvider("p") { _, r -> range = r; FetchResult.Ok(quoteOf("AAPL", 200.0)) }
        val r = runBlocking { store().validateAndAdd("AAPL", p, displayName = " Apple ") } as AddOutcome.Added
        assertEquals(ChartRange.D1, range)
        assertEquals("Apple", r.item.displayName)
    }

    @Test fun validateAndAddInvalidSymbolNeverCallsProvider() {
        val p = provider(FetchResult.Ok(quoteOf()))
        val r = runBlocking { store().validateAndAdd("not valid!", p) }
        assertTrue(r is AddOutcome.Invalid)
        assertTrue(p.calls.isEmpty())
    }

    @Test fun validateAndAddDuplicateNeverCallsProvider() {
        val p = provider(FetchResult.Ok(quoteOf()))
        val r = runBlocking { store().validateAndAdd("msft", p) }
        assertTrue(r is AddOutcome.Duplicate)
        assertTrue(p.calls.isEmpty())
    }

    @Test fun validateAndAddNotFound() {
        val s = store()
        val r = runBlocking { s.validateAndAdd("ZZZZ", provider(FetchResult.Err(ErrorKind.NotFound, "nope"))) }
        assertEquals(AddOutcome.NotFound, r)
        assertEquals(2, s.items.value.size)
    }

    @Test fun validateAndAddOfflineOffersAddAnyway() {
        val s = store()
        for (kind in listOf(ErrorKind.Network, ErrorKind.Timeout, ErrorKind.RateLimited(5), ErrorKind.Http(503), ErrorKind.Parse, ErrorKind.Unknown)) {
            val r = runBlocking { s.validateAndAdd("AAPL", provider(FetchResult.Err(kind, "m"))) }
            assertTrue("$kind", r is AddOutcome.NetworkError && r.canAddAnyway && r.kind == kind)
        }
        assertEquals(2, s.items.value.size) // nothing added until the user confirms
        val any = s.addUnverified("aapl")
        assertEquals(AddOutcome.Added(WatchItem("AAPL", "AAPL", "", ""), true), any)
        assertEquals(listOf("XEQT.TO", "MSFT", "AAPL"), s.syms())
    }

    @Test fun validateAndAddRespectsLimitAndReadOnly() {
        val s = store()
        for (i in 1..28) s.add(item("T$i"))
        val p = provider(FetchResult.Ok(quoteOf("AAPL", 1.0)))
        assertEquals(AddOutcome.LimitReached, runBlocking { s.validateAndAdd("AAPL", p) })
        assertTrue(p.calls.isEmpty())

        val f = file()
        f.writeText("""{"version":9,"items":[]}""")
        assertEquals(AddOutcome.ReadOnly, runBlocking { store(f).validateAndAdd("AAPL", p) })
    }

    @Test fun addUnverifiedValidatesDuplicateAndSymbol() {
        val s = store()
        assertTrue(s.addUnverified("msft") is AddOutcome.Duplicate)
        assertTrue(s.addUnverified("a b") is AddOutcome.Invalid)
    }

    @Test fun validateAndAddBlankQuoteNameFallsBackToSymbol() {
        val p = provider(FetchResult.Ok(quoteOf("ABC", 5.0, name = "", currency = "", exchange = "")))
        val r = runBlocking { store().validateAndAdd("abc", p) } as AddOutcome.Added
        assertEquals("ABC", r.item.name)
        assertEquals("", r.item.currency)
    }
}
