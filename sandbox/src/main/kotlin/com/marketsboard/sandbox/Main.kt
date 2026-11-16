package com.marketsboard.sandbox

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    loadDashFonts()
    val state = rememberWindowState(size = DpSize(1240.dp, 960.dp), position = WindowPosition(Alignment.Center))
    Window(onCloseRequest = ::exitApplication, title = "Markets Board Sandbox", state = state) {
        SandboxApp()
    }
}
