package com.marketsboard.ui.tools

import com.marketsboard.data.news.FeedKind

/** Which page of Settings is open: the main list, the news feed editor or the diagnostics screen. */
enum class ToolsPage { Main, Feeds, Diagnostics }

/** One row of the feed editor. [symbol] is set for a feed that tags every item with that symbol. */
data class FeedRowUi(
    val id: String,
    val name: String,
    /** Site of the address without `www.`, so the owner can tell feeds apart. */
    val host: String,
    val enabled: Boolean,
    val kind: FeedKind = FeedKind.General,
    val symbol: String? = null,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    /** The row is open and shows its actions (rename, move, remove). */
    val selected: Boolean = false,
)

/** The add-feed sheet. [message] is a plain-words reason the last Add did not work (amber); blank otherwise. */
data class FeedAddUi(val name: String = "", val url: String = "", val message: String = "")

data class FeedRenameUi(val id: String, val current: String, val original: String)

data class FeedRemoveUi(val id: String, val name: String)

data class FeedsUiState(
    val rows: List<FeedRowUi> = emptyList(),
    /** One Yahoo Finance feed per watchlist symbol (the "my symbols" feeds). */
    val symbolFeeds: Boolean = true,
    /** How many such feeds the watchlist produces right now. */
    val symbolFeedCount: Int = 0,
    /** feeds.json came from a newer version (or cannot be read): shown, never changed. */
    val readOnly: Boolean = false,
    val add: FeedAddUi? = null,
    val rename: FeedRenameUi? = null,
    val confirmRemove: FeedRemoveUi? = null,
    val confirmReset: Boolean = false,
    /** Amber line: the list is read only, or the last change could not be saved. */
    val notice: String = "",
    val atLimit: Boolean = false,
) {
    /** Something on top of the list is open (a sheet or a dialog): Back closes that first. */
    val overlayOpen: Boolean get() = add != null || rename != null || confirmRemove != null || confirmReset
}

/** Every action of the feed editor. No driving restriction: it works at any speed. */
interface FeedsActions {
    fun onSelect(id: String) {}
    fun onToggle(id: String, enabled: Boolean) {}
    fun onMove(id: String, delta: Int) {}
    fun onAskRename(id: String) {}
    fun onRenameChange(text: String) {}
    fun onRenameConfirm() {}
    fun onRenameCancel() {}
    fun onAskRemove(id: String) {}
    fun onConfirmRemove() {}
    fun onCancelRemove() {}
    fun onAddOpen() {}
    fun onAddNameChange(text: String) {}
    fun onAddUrlChange(text: String) {}
    fun onAddConfirm() {}
    fun onAddCancel() {}
    fun onSymbolFeeds(on: Boolean) {}
    fun onAskReset() {}
    fun onConfirmReset() {}
    fun onCancelReset() {}
}

/** One "label  value" line of the diagnostics screen. [warn] draws the value in amber (something needs attention). */
data class DiagLine(val label: String, val value: String, val warn: Boolean = false)

/** A provider or a feed: a heading (blank for plain sections) and its lines. */
data class DiagItem(val heading: String = "", val lines: List<DiagLine>)

data class DiagSection(val title: String, val items: List<DiagItem>)

data class DiagnosticsUiState(val sections: List<DiagSection> = emptyList())

/** What the Settings tab needs on top of its own state: the open page and the contents of the two pages. */
data class ToolsUiState(
    val page: ToolsPage = ToolsPage.Main,
    val feeds: FeedsUiState = FeedsUiState(),
    val diagnostics: DiagnosticsUiState = DiagnosticsUiState(),
) {
    /** Back does something: a feed sheet or dialog is open, or a sub-page is. */
    val canGoBack: Boolean get() = page != ToolsPage.Main
}

/** Navigation and the feed editor's actions. M5 screens are not driving-gated: they work at any speed. */
interface ToolsActions : FeedsActions {
    fun onOpenFeeds() {}
    fun onOpenDiagnostics() {}
    fun onToolsBack() {}

    companion object { val None: ToolsActions = object : ToolsActions {} }
}
