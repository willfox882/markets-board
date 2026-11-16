package com.marketsboard.sandbox

/**
 * Pane shapes the sandbox can simulate: [ratio] is height / width of the pane. Full screen and quadrants are 9:16, a
 * top/bottom split about 9:8, a short strip 3:2 (wider than tall). The screenshot task renders the same three shapes.
 */
enum class PaneShape(val label: String, val ratio: Float, val shotWidth: Int, val shotHeight: Int) {
    Full("Full (9:16)", 16f / 9f, 810, 1440),
    Half("Top / bottom half (9:8)", 8f / 9f, 810, 720),
    Strip("Short strip (3:2)", 2f / 3f, 810, 540),
}
