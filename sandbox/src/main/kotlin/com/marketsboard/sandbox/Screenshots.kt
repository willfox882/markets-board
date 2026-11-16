package com.marketsboard.sandbox

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.marketsboard.demo.DemoData
import com.marketsboard.ui.MarketsBoardRoot
import com.marketsboard.ui.MarketsBoardTheme
import com.marketsboard.ui.MbTab
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Headless renders of the real UI (no window needed): ./gradlew :sandbox:screenshots
 * Output PNGs go to the directory given as the first argument.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    loadDashFonts()
    val out = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }

    fun shot(name: String, w: Int, h: Int, tab: MbTab) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) {
            MarketsBoardTheme { MarketsBoardRoot(DemoData.markets, DemoData.news, tab) }
        }
        for (i in 0 until 8) scene.render(i * 120_000_000L) // let animations settle
        val img = scene.render(2_000_000_000L)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("wrote $name.png (${w}x$h)")
    }

    // 810 x 1440 matches the 9:16 head-unit aspect at roughly 1.84x the 440 dp design width.
    MbTab.entries.forEach { shot(it.name.lowercase(), 810, 1440, it) }
    println("done")
}
