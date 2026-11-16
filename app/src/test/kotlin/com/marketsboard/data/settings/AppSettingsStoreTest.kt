package com.marketsboard.data.settings

import com.marketsboard.data.FakeClock
import com.marketsboard.data.store.LoadStatus
import com.marketsboard.data.tempDir
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsStoreTest {
    private fun store(dir: File = tempDir()) = AppSettingsStore(File(dir, "settings.json"), FakeClock()) to dir

    @Test fun freshStartHasDefaultsAndWritesTheFile() {
        val (s, dir) = store()
        assertEquals(AppSettings(), s.settings.value)
        assertEquals(LoadStatus.Fresh, s.loadStatus)
        assertTrue(File(dir, "settings.json").isFile)
        assertEquals(1.0f, s.settings.value.speechRate, 0f)
        assertTrue(s.settings.value.voiceEnabled && s.settings.value.includeNews)
        assertFalse(s.settings.value.passengerMode || s.settings.value.mediaKeyTrigger)
    }

    @Test fun changesSurviveARestart() {
        val (s, dir) = store()
        s.update { it.copy(passengerMode = true, speechRate = 0.85f, includeNews = false, mediaKeyTrigger = true, voiceEnabled = false) }
        assertTrue(s.flush())
        val again = AppSettingsStore(File(dir, "settings.json"), FakeClock())
        assertEquals(AppSettings(passengerMode = true, voiceEnabled = false, speechRate = 0.85f, includeNews = false, mediaKeyTrigger = true), again.settings.value)
        assertEquals(LoadStatus.Loaded, again.loadStatus)
    }

    @Test fun updateIsImmediateInMemoryAndWrittenOnFlush() {
        val (s, dir) = store()
        s.update { it.copy(passengerMode = true) }
        assertTrue(s.settings.value.passengerMode)
        s.flush()
        assertTrue(File(dir, "settings.json").readText().contains("\"passengerMode\": true"))
    }

    @Test fun speechRateIsClampedAndSnapped() {
        assertEquals(1.2f, AppSettings.clampRate(5f), 0f)
        assertEquals(0.8f, AppSettings.clampRate(-3f), 0f)
        assertEquals(1.0f, AppSettings.clampRate(Float.NaN), 0f)
        assertEquals(1.0f, AppSettings.clampRate(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1.05f, AppSettings.clampRate(1.06f), 0.0001f)
        val (s, _) = store()
        s.update { it.copy(speechRate = 40f) }
        assertEquals(1.2f, s.settings.value.speechRate, 0f)
    }

    @Test fun aHandEditedFileWithWildValuesIsSanitised() {
        val dir = tempDir()
        File(dir, "settings.json").writeText("""{"version":1,"passengerMode":"yes","speechRate":99,"voiceEnabled":false,"includeNews":7}""")
        val s = AppSettingsStore(File(dir, "settings.json"), FakeClock())
        assertEquals(1.2f, s.settings.value.speechRate, 0f)
        assertFalse(s.settings.value.voiceEnabled)
        assertFalse("a string is not a boolean: default", s.settings.value.passengerMode)
        assertTrue("a number is not a boolean: default", s.settings.value.includeNews)
    }

    @Test fun missingFieldsTakeDefaults() {
        val dir = tempDir()
        File(dir, "settings.json").writeText("""{"version":1,"passengerMode":true}""")
        val s = AppSettingsStore(File(dir, "settings.json"), FakeClock())
        assertTrue(s.settings.value.passengerMode)
        assertEquals(AppSettings().copy(passengerMode = true), s.settings.value)
    }

    @Test fun garbageIsQuarantinedAndDefaultsReturn() {
        val dir = tempDir()
        File(dir, "settings.json").writeText("{not json at all")
        val s = AppSettingsStore(File(dir, "settings.json"), FakeClock())
        assertEquals(AppSettings(), s.settings.value)
        assertEquals(LoadStatus.RecoveredFromCorrupt, s.loadStatus)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("settings.corrupt-") })
        assertTrue("a new valid file was written", File(dir, "settings.json").readText().contains("\"version\""))
    }

    @Test fun aTruncatedFileRecoversFromTheBackup() {
        val (s, dir) = store()
        s.update { it.copy(passengerMode = true) }; s.flush()
        s.update { it.copy(includeNews = false) }; s.flush() // the first good copy is now the backup
        File(dir, "settings.json").writeText("") // power cut: zero length
        val again = AppSettingsStore(File(dir, "settings.json"), FakeClock())
        assertEquals(LoadStatus.RecoveredFromBackup, again.loadStatus)
        assertTrue(again.settings.value.passengerMode)
    }

    @Test fun aNewerVersionIsUsedButNeverOverwritten() {
        val dir = tempDir()
        val f = File(dir, "settings.json")
        f.writeText("""{"version":9,"passengerMode":true,"future":"x"}""")
        val s = AppSettingsStore(f, FakeClock())
        assertTrue(s.readOnly)
        assertTrue(s.settings.value.passengerMode)
        s.update { it.copy(passengerMode = false) }
        assertTrue(s.flush())
        assertEquals("""{"version":9,"passengerMode":true,"future":"x"}""", f.readText())
    }

    @Test fun aFailedWriteKeepsTheValueAndRetries() {
        val dir = tempDir()
        val (s, _) = store(dir)
        File(dir, "settings.json").delete()
        File(dir, "settings.json").mkdir() // the target is now a directory: the rename cannot succeed
        s.update { it.copy(passengerMode = true) }
        assertFalse(s.flush())
        assertTrue(s.settings.value.passengerMode)
        assertTrue(s.persistError.value != null)
        File(dir, "settings.json").deleteRecursively()
        assertTrue(s.flush())
        assertTrue(File(dir, "settings.json").readText().contains("true"))
    }
}

class BriefingHistoryStoreTest {
    @Test fun remembersAndPersists() {
        val dir = tempDir()
        val h = BriefingHistoryStore(File(dir, "h.json"), FakeClock())
        assertTrue(h.spoken().isEmpty())
        h.remember(listOf("a", "b"))
        h.remember(listOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), h.spoken())
        assertEquals(setOf("a", "b", "c"), BriefingHistoryStore(File(dir, "h.json"), FakeClock()).spoken())
    }

    @Test fun isBoundedToTheNewestIds() {
        val h = BriefingHistoryStore(File(tempDir(), "h.json"), FakeClock())
        h.remember((1..500).map { "id$it" })
        assertEquals(BriefingHistoryStore.MAX_IDS, h.spoken().size)
        assertTrue("id500" in h.spoken())
        assertFalse("id1" in h.spoken())
    }

    @Test fun corruptFileMeansNothingSpokenAndNoCrash() {
        val dir = tempDir()
        File(dir, "h.json").writeText("\u0000\u0000garbage")
        val h = BriefingHistoryStore(File(dir, "h.json"), FakeClock())
        assertTrue(h.spoken().isEmpty())
        h.remember(listOf("x"))
        assertEquals(setOf("x"), BriefingHistoryStore(File(dir, "h.json"), FakeClock()).spoken())
    }
}
