package com.marketsboard.ui.markets

import com.marketsboard.core.AddText
import com.marketsboard.core.ErrorText
import com.marketsboard.core.ExchangeLabels
import com.marketsboard.core.ExchangeTime
import com.marketsboard.core.TypeLabels
import com.marketsboard.data.Clock
import com.marketsboard.data.MarketsData
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Instrument
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.repo.QuoteRepository
import com.marketsboard.data.repo.QuoteState
import com.marketsboard.data.store.AddOutcome
import com.marketsboard.data.store.Symbols
import com.marketsboard.refresh.Refresher
import com.marketsboard.ui.GlanceRule
import com.marketsboard.ui.MarketsAct
import com.marketsboard.ui.UiMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the state of the Markets tab (list, editor, detail, add sheet) and implements every user action.
 * The UI is stateless: it renders [state] and calls the [MarketsActions] methods.
 *
 * Threads: call the actions from the UI thread. Watchlist edits run on [io] (file IO). Quote state comes from
 * [MarketsData.quotes], the list from [MarketsData.watchlist]; nothing is held only in memory except which screen is
 * open, so process death loses navigation but never data.
 */
class MarketsPresenter(
    private val scope: CoroutineScope,
    private val data: MarketsData,
    private val refresher: Refresher = Refresher.None,
    private val clock: Clock = Clock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMs: Long = 400,
    private val mode: StateFlow<UiMode> = MutableStateFlow(UiMode.Normal),
) : MarketsActions {

    private data class DetailSel(val symbol: String, val range: ChartRange)

    private data class AddState(
        val replacing: String? = null,
        val query: String = "",
        val searching: Boolean = false,
        val results: List<Instrument> = emptyList(),
        /** The trimmed query the [results] answer; results of an older query are never used by Done. */
        val resultsFor: String = "",
        val searchNote: String = "",
        val busy: Boolean = false,
        val message: AddMessage? = null,
    )

    private data class Nav(
        val editMode: Boolean = false,
        val detail: DetailSel? = null,
        val add: AddState? = null,
        val confirmRemove: String? = null,
        val rename: RenameUi? = null,
    )

    private val nav = MutableStateFlow(Nav())
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var submitJob: Job? = null

    /** What the user had typed when Glance closed the sheets. Kept in memory only, offered again when the sheet or dialog reopens. */
    private data class Draft(val addQuery: String = "", val rename: RenameUi? = null)
    private var draft = Draft()

    private val core = combine(
        data.watchlist.items, data.quotes.states, data.watchlist.persistError, nav,
    ) { items, states, persistError, n -> build(items, states, persistError, n) }

    val state: StateFlow<MarketsUiState> = combine(core, data.quotes.refreshing, mode) { s, r, m ->
        val withMode = s.copy(refreshing = r, mode = m)
        // Glance shows no editor, detail or sheets whatever was open before.
        if (m == UiMode.Glance) withMode.copy(detail = null, add = null, editMode = false, confirmRemove = null, rename = null) else withMode
    }
        .stateIn(scope, SharingStarted.Eagerly, MarketsUiState(emptyList()))

    init {
        // Glance (the truck moves) closes every overlay for good, keeping only the typed text in memory.
        scope.launch { mode.collect { if (it == UiMode.Glance) enterGlance() } }
        // A detail view of an instrument that left the list (removed, replaced) closes itself.
        scope.launch {
            data.watchlist.items.collect { list ->
                val syms = list.map { it.symbol }.toSet()
                nav.update { n -> if (n.detail != null && n.detail.symbol !in syms) n.copy(detail = null) else n }
            }
        }
    }

    /**
     * Closes the editor, detail, sheets and dialogs, cancels a search, a detail fetch and an add that is still running (an
     * add is all or nothing in the store, so no half-added item can exist), and keeps what was typed in memory.
     */
    private fun enterGlance() {
        val n = nav.value
        val query = n.add?.takeIf { it.replacing == null }?.query.orEmpty()
        if (query.isNotBlank() || n.rename != null) draft = Draft(query.ifBlank { draft.addQuery }, n.rename ?: draft.rename)
        searchJob?.cancel()
        detailJob?.cancel()
        submitJob?.cancel()
        nav.value = Nav()
    }

    // ---- state building ----------------------------------------------------------------------------------------

    private fun build(items: List<WatchItem>, states: Map<String, QuoteState>, persistError: String?, n: Nav): MarketsUiState {
        val wall = clock.nowMs()
        val cooldown = data.quotes.cooldownRemainingMs()
        val notice = when {
            data.watchlist.readOnly -> "This list was saved by a newer version of the app. You can view it but not change it."
            persistError != null -> "Your last change could not be saved to storage. It will be retried."
            else -> ""
        }
        return MarketsUiState(
            quotes = UiMapper.rows(items, states, cooldown),
            header = UiMapper.header(items, states, wall),
            clockSkew = UiMapper.clockSkew(states),
            editMode = n.editMode,
            notice = notice,
            detail = n.detail?.let { d -> items.firstOrNull { it.symbol == d.symbol }?.let { UiMapper.detail(it, d.range, states, cooldown, wall) } },
            add = n.add?.let { addUi(it, items) },
            confirmRemove = n.confirmRemove?.let { s -> items.firstOrNull { it.symbol == s }?.let { ConfirmRemoveUi(it.symbol, UiMapper.nameOf(it, null)) } },
            rename = n.rename,
        )
    }

    private fun addUi(a: AddState, items: List<WatchItem>): AddUi {
        val have = items.map { it.symbol }.toSet()
        val replacingName = a.replacing?.let { s -> items.firstOrNull { it.symbol == s }?.let { UiMapper.nameOf(it, null) } }.orEmpty()
        return AddUi(
            replacing = a.replacing,
            replacingName = replacingName,
            query = a.query,
            searching = a.searching,
            results = a.results.map { SearchRowUi(it.symbol, it.name.ifBlank { it.symbol }, ExchangeLabels.short(it.exchange), TypeLabels.of(it.type), it.symbol in have) },
            searchNote = a.searchNote,
            suggestions = Suggestions.ALL.map { it.copy(added = it.symbol in have) },
            busy = a.busy,
            message = a.message,
        )
    }

    // ---- list actions -------------------------------------------------------------------------------------------

    override fun onBack() = guarded(MarketsAct.Back) {
        val n = nav.value
        when {
            n.rename != null -> onRenameCancel()
            n.confirmRemove != null -> onCancelRemove()
            n.add != null -> onAddClose()
            n.detail != null -> onCloseDetail()
            n.editMode -> nav.update { it.copy(editMode = false) }
        }
    }

    override fun onRefresh() = guarded(MarketsAct.Refresh) { refresher.refreshAll() }

    override fun onRetry(symbol: String) = guarded(MarketsAct.Retry) { refresher.refresh(symbol) }

    private val glance: Boolean get() = mode.value == UiMode.Glance

    /** Every action goes through here: in Glance a [GlanceRule.Blocked] action does nothing at all. */
    private inline fun guarded(act: MarketsAct, block: () -> Unit) {
        if (glance && act.glance == GlanceRule.Blocked) return
        block()
    }

    override fun onToggleEdit() = guarded(MarketsAct.ToggleEdit) { nav.update { it.copy(editMode = !it.editMode) } }

    override fun onMove(symbol: String, delta: Int) = guarded(MarketsAct.Move) {
        val list = data.watchlist.items.value
        val from = list.indexOfFirst { it.symbol == symbol }
        val to = from + delta
        if (from < 0 || to !in list.indices) return@guarded
        scope.launch { withContext(io) { data.watchlist.move(from, to) } }
    }

    // ---- rename, remove -----------------------------------------------------------------------------------------

    override fun onAskRename(symbol: String) = guarded(MarketsAct.AskRename) {
        val item = data.watchlist.items.value.firstOrNull { it.symbol == symbol } ?: return@guarded
        // Text typed before Glance closed the dialog comes back (in memory only), once.
        val kept = draft.rename?.takeIf { it.symbol == symbol }
        draft = draft.copy(rename = null)
        nav.update { it.copy(rename = RenameUi(symbol, kept?.current ?: item.displayName ?: UiMapper.nameOf(item, null), item.name)) }
    }

    override fun onRenameChange(text: String) = guarded(MarketsAct.RenameChange) { nav.update { n -> n.copy(rename = n.rename?.copy(current = text.take(40))) } }

    override fun onRenameConfirm() = guarded(MarketsAct.RenameConfirm) {
        val r = nav.value.rename ?: return@guarded
        nav.update { it.copy(rename = null) }
        // Blank, or the original name, clears the override.
        val name = r.current.trim().takeIf { it.isNotEmpty() && it != r.original }
        scope.launch { withContext(io) { data.watchlist.rename(r.symbol, name) } }
    }

    override fun onRenameCancel() = guarded(MarketsAct.RenameCancel) { nav.update { it.copy(rename = null) } }

    override fun onAskRemove(symbol: String) = guarded(MarketsAct.AskRemove) { nav.update { it.copy(confirmRemove = symbol) } }

    override fun onCancelRemove() = guarded(MarketsAct.CancelRemove) { nav.update { it.copy(confirmRemove = null) } }

    override fun onConfirmRemove() = guarded(MarketsAct.ConfirmRemove) {
        val sym = nav.value.confirmRemove ?: return@guarded
        nav.update { it.copy(confirmRemove = null) }
        scope.launch {
            withContext(io) { data.remove(sym) }
            nav.update { n -> if (n.detail?.symbol == sym) n.copy(detail = null) else n }
        }
    }

    // ---- detail -------------------------------------------------------------------------------------------------

    override fun onOpen(symbol: String) = guarded(MarketsAct.Open) {
        if (data.watchlist.items.value.none { it.symbol == symbol }) return@guarded
        nav.update { it.copy(detail = DetailSel(symbol, ChartRange.D1)) }
        loadRange(symbol, ChartRange.D1, force = false)
    }

    override fun onCloseDetail() = guarded(MarketsAct.CloseDetail) {
        detailJob?.cancel()
        nav.update { it.copy(detail = null) }
    }

    override fun onSelectRange(range: ChartRange) = guarded(MarketsAct.SelectRange) {
        val d = nav.value.detail ?: return@guarded
        nav.update { it.copy(detail = d.copy(range = range)) }
        loadRange(d.symbol, range, force = false)
    }

    override fun onRetryRange() = guarded(MarketsAct.RetryRange) {
        val d = nav.value.detail ?: return@guarded
        loadRange(d.symbol, d.range, force = true)
    }

    private fun loadRange(symbol: String, range: ChartRange, force: Boolean) {
        detailJob?.cancel()
        detailJob = scope.launch {
            try {
                data.quotes.refresh(listOf(symbol), range, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The repository never throws; a failure here would only mean no refresh.
            }
        }
    }

    // ---- add / replace ------------------------------------------------------------------------------------------

    override fun onAddOpen() = guarded(MarketsAct.AddOpen) {
        // Text typed before Glance closed the sheet comes back (in memory only), once, and searches again.
        val kept = draft.addQuery
        draft = draft.copy(addQuery = "")
        nav.update { it.copy(add = AddState()) }
        if (kept.isNotBlank()) onQueryChange(kept)
    }

    override fun onReplaceOpen(symbol: String) = guarded(MarketsAct.ReplaceOpen) {
        if (data.watchlist.items.value.none { it.symbol == symbol }) return@guarded
        nav.update { it.copy(add = AddState(replacing = symbol)) }
    }

    override fun onAddClose() = guarded(MarketsAct.AddClose) {
        searchJob?.cancel()
        nav.update { it.copy(add = null) }
    }

    override fun onQueryChange(text: String) = guarded(MarketsAct.QueryChange) {
        val a = nav.value.add ?: return@guarded
        val q = text.take(64)
        nav.update { it.copy(add = a.copy(query = q, message = null, searching = false, results = if (q.isBlank()) emptyList() else a.results, searchNote = "")) }
        searchJob?.cancel()
        val term = q.trim()
        if (term.isEmpty()) return@guarded
        searchJob = scope.launch {
            delay(searchDebounceMs)
            updateAdd { it.copy(searching = true) }
            val r = try {
                data.provider.search(term)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                FetchResult.Err(ErrorKind.Unknown, "search crashed")
            }
            // Ignore the answer if the text moved on (cancellation normally covers this; belt and braces).
            if (nav.value.add?.query?.trim() != term) return@launch
            when (r) {
                is FetchResult.Ok -> {
                    // One row per symbol: LazyColumn keys and "Added" badges depend on it.
                    val unique = r.value.distinctBy { it.symbol }
                    updateAdd {
                        it.copy(searching = false, results = unique, resultsFor = term, searchNote = if (unique.isEmpty()) "No matches. Type the full symbol in capitals, like XEQT.TO, and press Done." else "")
                    }
                }
                is FetchResult.Err -> updateAdd { it.copy(searching = false, results = emptyList(), resultsFor = term, searchNote = searchFailure(r.kind)) }
            }
        }
    }

    private fun searchFailure(kind: ErrorKind): String = when (kind) {
        ErrorKind.Network, ErrorKind.Timeout -> "Can't search while offline. Type the full symbol and press Done."
        is ErrorKind.RateLimited -> "Search is busy right now. Try again in a minute, or type the full symbol and press Done."
        ErrorKind.ClockOrTls -> "Search failed. ${ErrorText.CHECK_CLOCK}."
        else -> "Search is unavailable right now. Type the full symbol and press Done."
    }

    private fun updateAdd(f: (AddState) -> AddState) = nav.update { n -> n.add?.let { n.copy(add = f(it)) } ?: n }

    override fun onPickResult(symbol: String) = guarded(MarketsAct.PickResult) {
        val a = nav.value.add ?: return@guarded
        if (a.busy) return@guarded
        submitJob = scope.launch { submit(symbol, anyway = false, closeOnAdded = a.replacing != null) }
    }

    /**
     * Done / IME action. A symbol-shaped entry (capitals, digits and . ^ = - only) is tried as a ticker, preferring a
     * search result with exactly that symbol. Anything else (spaces, lowercase: it is a name, not a ticker) picks the
     * first result of the current search, or explains what to do. A guessed ticker is never added silently.
     */
    override fun onSubmitTyped() = guarded(MarketsAct.SubmitTyped) {
        val a = nav.value.add ?: return@guarded
        if (a.busy) return@guarded
        val typed = a.query.trim()
        if (typed.isEmpty()) {
            onAddClose()
            return@guarded
        }
        val fresh = a.resultsFor == typed && a.results.isNotEmpty()
        val target: String? = when {
            SYMBOL_SHAPE.matches(typed) -> (if (fresh) a.results.firstOrNull { it.symbol == typed }?.symbol else null) ?: typed
            fresh -> a.results.first().symbol
            else -> null
        }
        if (target == null) {
            val pending = a.searching || a.resultsFor != typed
            val text = (if (pending) "Still searching. " else "") +
                "Pick a result from the list, or type the full symbol in capital letters, like XEQT.TO."
            updateAdd { it.copy(message = AddMessage(text, warn = false)) }
            return@guarded
        }
        submitJob = scope.launch { submit(target, anyway = false, closeOnAdded = true) }
    }

    override fun onAddAnyway() = guarded(MarketsAct.AddAnyway) {
        val a = nav.value.add ?: return@guarded
        val sym = a.message?.addAnywaySymbol ?: return@guarded
        if (a.busy) return@guarded
        submitJob = scope.launch { submit(sym, anyway = true, closeOnAdded = true) }
    }

    private suspend fun submit(raw: String, anyway: Boolean, closeOnAdded: Boolean) {
        val a = nav.value.add ?: return
        val replacing = a.replacing
        val startedQuery = a.query
        updateAdd { it.copy(busy = true, message = null) }
        val outcome: AddOutcome = withContext(io) {
            try {
                when {
                    replacing != null && anyway -> data.replaceUnverified(replacing, raw)
                    replacing != null -> data.validateAndReplace(replacing, raw)
                    anyway -> data.watchlist.addUnverified(raw)
                    else -> data.watchlist.validateAndAdd(raw, data.provider)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AddOutcome.NetworkError(ErrorKind.Unknown, "failed", canAddAnyway = false)
            }
        }
        val t = AddText.message(outcome, replacing != null)
        val sym = Symbols.normalize(raw)
        if (outcome is AddOutcome.Added && closeOnAdded) {
            searchJob?.cancel()
            nav.update { it.copy(add = null) }
            return
        }
        updateAdd {
            // Never overwrite what the user typed while the add was in flight.
            val untouched = outcome is AddOutcome.Added && it.query == startedQuery
            it.copy(
                busy = false,
                query = if (untouched) "" else it.query,
                results = if (untouched) emptyList() else it.results,
                message = AddMessage(t.text, t.warn, addAnywaySymbol = if (t.canAddAnyway && sym != null) sym else null),
            )
        }
    }

    private companion object {
        val SYMBOL_SHAPE = Regex("^[A-Z0-9.^=-]{1,15}$")
    }
}
