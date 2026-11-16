package com.marketsboard.sandbox

import com.marketsboard.ui.DashFonts
import com.marketsboard.ui.MbTab
import com.marketsboard.ui.formatPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import androidx.compose.ui.text.font.FontFamily

class SandboxSmokeTest {
    @Test fun shellHasThreeTabsInOrder() {
        assertEquals(listOf("MARKETS", "NEWS", "SETTINGS"), MbTab.entries.map { it.label })
    }

    @Test fun sharedFormatHelpersAreAvailableOnDesktop() {
        assertEquals("+0.42%", formatPercent(0.42))
    }

    @Test fun geistFontsLoadFromClasspath() {
        listOf("geist_light", "geist_regular", "geist_medium", "geist_semibold").forEach {
            assertNotNull("missing font/$it.ttf", javaClass.classLoader.getResource("font/$it.ttf"))
        }
        loadDashFonts()
        assertNotSame(FontFamily.Default, DashFonts.family)
    }
}
