package com.marketsboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-window is allowed and the driving gate keeps working in a visible but unfocused pane. Source-level checks (the
 * Activity itself cannot run in a unit test): the manifest does not forbid resizing, a resize never recreates the Activity,
 * and speed tracking plus the refresh gate follow onStart/onStop, never onResume/onPause (a pane that is visible but not
 * focused is STARTED and PAUSED, so it keeps reading the speed and keeps the lock correct).
 */
class MultiWindowTest {
    private fun root(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return requireNotNull(d)
    }

    private val manifest get() = File(root(), "app/src/main/AndroidManifest.xml").readText()
    private val activity get() = File(root(), "app/src/main/java/com/marketsboard/MainActivity.kt").readText()

    @Test fun theManifestAllowsResizingAndRotation() {
        assertFalse("resizeableActivity must not be false", manifest.contains("resizeableActivity"))
        assertTrue(manifest.contains("android:screenOrientation=\"unspecified\""))
        assertFalse(manifest.contains("android:screenOrientation=\"portrait\""))
    }

    @Test fun aResizeNeverRecreatesTheActivity() {
        val cfg = Regex("android:configChanges=\"([^\"]*)\"").find(manifest)!!.groupValues[1].split('|').toSet()
        for (c in listOf("orientation", "screenSize", "smallestScreenSize", "screenLayout", "density")) assertTrue("$c missing", c in cfg)
    }

    private fun body(fn: String): String {
        val src = activity
        val start = src.indexOf("override fun $fn(")
        assertTrue("$fn not found", start >= 0)
        val end = src.indexOf("\n    }\n", start)
        return src.substring(start, end)
    }

    @Test fun speedTrackingAndTheGateFollowStartAndStop() {
        val start = body("onStart")
        assertTrue(start.contains("speed?.start()"))
        assertTrue(start.contains("gate?.resume()"))
        val stop = body("onStop")
        assertTrue(stop.contains("speed?.stop()"))
        assertTrue(stop.contains("gate?.pause()"))
    }

    @Test fun resumeAndPauseDoNotTouchTheGateOrTheSpeedSource() {
        assertFalse("onResume must not be overridden here", activity.contains("override fun onResume("))
        assertFalse("onPause must not be overridden here", activity.contains("override fun onPause("))
    }
}
