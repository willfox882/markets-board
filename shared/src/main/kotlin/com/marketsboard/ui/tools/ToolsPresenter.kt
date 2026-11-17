package com.marketsboard.ui.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
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
    /** False while the app is in the background: the diagnostics stop re-mapping and ticking. */
    active: StateFlow<Boolean> = MutableStateFlow(true),
) : ToolsActions, FeedsActions by feeds {
    private val page = MutableStateFlow(ToolsPage.Main)

    /** The Settings tab is on screen (the root reports it). */
    private val visible = MutableStateFlow(false)

    /**
     * The diagnostics flow runs only while its page is open, the Settings tab is on screen and the app is active. Off the page the
     * state is empty; while merely hidden the last state is kept (nothing is emitted, nothing ticks).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val diag = combine(page, visible, active) { p, v, a -> Triple(p, v, a) }.distinctUntilChanged().flatMapLatest { (p, v, a) ->
        when {
            p != ToolsPage.Diagnostics -> flowOf(DiagnosticsUiState())
            v && a -> diagnostics.flow()
            else -> emptyFlow()
        }
    }

    val state: StateFlow<ToolsUiState> = combine(page, feeds.state, diag) { p, f, d -> ToolsUiState(p, f, d) }
        .stateIn(scope, SharingStarted.Eagerly, ToolsUiState())

    override fun onSettingsVisible(visible: Boolean) { this.visible.value = visible }

    override fun onOpenFeeds() { page.value = ToolsPage.Feeds }

    override fun onOpenDiagnostics() { page.value = ToolsPage.Diagnostics }

    /** Back: closes a feed sheet or dialog first, then the page. */
    override fun onToolsBack() {
        if (page.value == ToolsPage.Feeds && feeds.closeOverlay()) return
        if (page.value != ToolsPage.Main) { feeds.reset(); page.value = ToolsPage.Main }
    }
}
