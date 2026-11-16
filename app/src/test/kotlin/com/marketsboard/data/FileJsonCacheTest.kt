package com.marketsboard.data

import com.marketsboard.data.store.FileJsonCache
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileJsonCacheTest {
    private val clock = FakeClock(10_000L)
    private fun cache(dir: File = tempDir(), max: Long = 1_000_000) = FileJsonCache(dir, clock, max)

    @Test fun putAndGetRoundTrip() {
        val c = cache()
        assertTrue(c.put("quote/MSFT/1D", """{"a":"b\n\"q\""}""", 60_000))
        val e = c.get("quote/MSFT/1D")!!
        assertEquals("""{"a":"b\n\"q\""}""", e.payload)
        assertEquals(10_000L, e.savedAtMs)
        assertEquals(70_000L, e.expiresAtMs)
    }

    @Test fun missingKeyIsNull() {
        assertNull(cache().get("nope"))
    }

    @Test fun ttlHidesExpiredEntriesUnlessAllowed() {
        val c = cache()
        c.put("k", "v", 1_000)
        clock.advance(999)
        assertNotNull(c.get("k"))
        clock.advance(1)
        assertNull(c.get("k"))
        assertEquals("v", c.get("k", allowExpired = true)!!.payload)
        assertTrue(c.get("k", allowExpired = true)!!.isExpired(clock.nowMs()))
    }

    @Test fun overwriteReplacesValueAndRefreshesTtl() {
        val c = cache()
        c.put("k", "one", 1_000)
        clock.advance(900)
        c.put("k", "two", 1_000)
        clock.advance(500)
        assertEquals("two", c.get("k")!!.payload)
        assertEquals(1, c.count())
    }

    @Test fun keysWithSpecialCharactersAreSafe() {
        val c = cache()
        for (k in listOf("^GSPC", "USDCAD=X", "../../etc/passwd", "a/b\\c", "XEQT.TO")) {
            c.put(k, "p-$k", 1000)
        }
        assertEquals("p-../../etc/passwd", c.get("../../etc/passwd")!!.payload)
        assertEquals(5, c.count())
    }

    @Test fun corruptEntryIsTolerantAndRemoved() {
        val dir = tempDir()
        val c = cache(dir)
        c.put("k", "v", 1000)
        val f = dir.listFiles()!!.single { it.name.endsWith(".json") }
        f.writeText("{ not json")
        assertNull(c.get("k", allowExpired = true))
        assertFalse(f.exists())
        c.put("k", "again", 1000)
        assertEquals("again", c.get("k")!!.payload)
    }

    @Test fun wrongShapeEntryIsTreatedAsMissing() {
        val dir = tempDir()
        val c = cache(dir)
        c.put("k", "v", 1000)
        dir.listFiles()!!.single { it.name.endsWith(".json") }.writeText("""{"v":1,"key":"k","payload":5}""")
        assertNull(c.get("k"))
    }

    @Test fun truncatedFileIsCorruptNotCrash() {
        val dir = tempDir()
        val c = cache(dir)
        c.put("k", "v".repeat(200), 1000)
        val f = dir.listFiles()!!.single { it.name.endsWith(".json") }
        f.writeText(f.readText().take(30))
        assertNull(c.get("k"))
    }

    @Test fun atomicWriteLeavesNoTempFilesAndSurvivesStaleTemp() {
        val dir = tempDir()
        File(dir, "deadbeef.json.tmp").writeText("half")
        val c = cache(dir) // init cleans interrupted writes
        assertFalse(File(dir, "deadbeef.json.tmp").exists())
        c.put("k", "v", 1000)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun failedWriteReturnsFalseAndNeverThrows() {
        val dir = tempDir()
        val c = cache(dir)
        c.put("k", "good", 1000)
        // A cache directory that cannot be created (its parent is a plain file) must fail softly.
        val blocker = File(tempDir(), "file").also { it.writeText("x") }
        val broken = FileJsonCache(File(blocker, "sub"), clock)
        assertFalse(broken.put("k", "v", 1000))
        assertNull(broken.get("k"))
        assertEquals("good", c.get("k")!!.payload)
    }

    @Test fun evictsOldestFirstWhenOverSizeCap() {
        val dir = tempDir()
        val c = cache(dir, max = 1500)
        val payload = "x".repeat(300)
        for (i in 1..6) {
            c.put("k$i", payload, 100_000)
            clock.advance(10)
        }
        assertTrue("size ${c.sizeBytes()}", c.sizeBytes() <= 1500)
        assertNull(c.get("k1"))
        assertNotNull(c.get("k6"))
        assertTrue(c.count() in 2..4)
    }

    @Test fun touchingAKeyMakesItNewerForEviction() {
        val c = cache(max = 1400)
        val payload = "x".repeat(300)
        c.put("a", payload, 100_000); clock.advance(10)
        c.put("b", payload, 100_000); clock.advance(10)
        c.put("a", payload, 100_000); clock.advance(10) // rewrite a: now newest
        c.put("c", payload, 100_000); clock.advance(10)
        c.put("d", payload, 100_000)
        assertNull(c.get("b"))
        assertNotNull(c.get("a"))
    }

    @Test fun singleEntryLargerThanCapIsNotKept() {
        val c = cache(max = 100)
        c.put("big", "y".repeat(1000), 1000)
        assertEquals(0, c.count())
        assertEquals(0L, c.sizeBytes())
    }

    @Test fun removeAndClear() {
        val c = cache()
        c.put("a", "1", 1000); c.put("b", "2", 1000)
        c.remove("a")
        assertNull(c.get("a"))
        assertNotNull(c.get("b"))
        c.clear()
        assertEquals(0, c.count())
    }

    @Test fun survivesReopen() {
        val dir = tempDir()
        cache(dir).put("k", "v", 5000)
        assertEquals("v", cache(dir).get("k")!!.payload)
    }
}
