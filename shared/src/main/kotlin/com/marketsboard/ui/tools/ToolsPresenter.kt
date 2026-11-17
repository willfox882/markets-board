package com.marketsboard.ui.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Navigation of the Settings sub-pages (News feeds, Diagnostics) plus their state. Feed editor actions are delegated to
 * [FeedsPresenter]; the diagnostics flow runs only while its page is open. M5 screens have no driving restriction: every
 * action works at any speed (the whole Settings tab is still hidden by the M4 shell while the truck moves).
 */
class ToolsPresenter(
    scope: CoroutineScope,
    private val feeds: FeedsPresenter,
    private val diagnostics: DiagnosticsPresenter,
) : ToolsActions, FeedsActions by feeds {
    private val page = MutableStateFlow(ToolsPage.Main)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val diag = page.flatMapLatest { if (it == ToolsPage.Diagnostics) diagnostics.flow() else flowOf(DiagnosticsUiState()) }

    val state: StateFlow<ToolsUiState> = combine(page, feeds.state, diag) { p, f, d -> ToolsUiState(p, f, d) }
        .stateIn(scope, SharingStarted.Eagerly, ToolsUiState())

    override fun onOpenFeeds() { page.value = ToolsPage.Feeds }

    override fun onOpenDiagnostics() { page.value = ToolsPage.Diagnostics }

    /** Back: closes a feed sheet or dialog first, then the page. */
    override fun onToolsBack() {
        if (page.value == ToolsPage.Feeds && feeds.closeOverlay()) return
        if (page.value != ToolsPage.Main) { feeds.reset(); page.value = ToolsPage.Main }
    }
}
