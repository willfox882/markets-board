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
import com.marketsboard.ui.NO_VALUE
import com.marketsboard.ui.formatPrice
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
) : MarketsActions {

    private data class DetailSel(val symbol: String, val range: ChartRange)

    private data class AddState(
        val replacing: String? = null,
        val query: String = "",
        val searching: Boolean = false,
        val results: List<Instrument> = emptyList(),
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

    private val core = combine(
        data.watchlist.items, data.quotes.states, data.watchlist.persistError, nav,
    ) { items, states, persistError, n -> build(items, states, persistError, n) }

    val state: StateFlow<MarketsUiState> = combine(core, data.quotes.refreshing) { s, r -> s.copy(refreshing = r) }
        .stateIn(scope, SharingStarted.Eagerly, MarketsUiState(emptyList()))

    init {
        // A detail view of an instrument that left the list (removed, replaced) closes itself.
        scope.launch {
            data.watchlist.items.collect { list ->
                val syms = list.map { it.symbol }.toSet()
                nav.update { n -> if (n.detail != null && n.detail.symbol !in syms) n.copy(detail = null) else n }
            }
        }
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

    override fun onBack() {
        val n = nav.value
        when {
            n.rename != null -> onRenameCancel()
            n.confirmRemove != null -> onCancelRemove()
            n.add != null -> onAddClose()
            n.detail != null -> onCloseDetail()
            n.editMode -> nav.update { it.copy(editMode = false) }
        }
    }

    override fun onRefresh() = refresher.refreshAll()

    override fun onRetry(symbol: String) = refresher.refresh(symbol)

    override fun onToggleEdit() = nav.update { it.copy(editMode = !it.editMode) }

    override fun onMove(symbol: String, delta: Int) {
        val list = data.watchlist.items.value
        val from = list.indexOfFirst { it.symbol == symbol }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        scope.launch { withContext(io) { data.watchlist.move(from, to) } }
    }

    // ---- rename, remove -----------------------------------------------------------------------------------------

    override fun onAskRename(symbol: String) {
        val item = data.watchlist.items.value.firstOrNull { it.symbol == symbol } ?: return
        nav.update { it.copy(rename = RenameUi(symbol, item.displayName ?: UiMapper.nameOf(item, null), item.name)) }
    }

    override fun onRenameChange(text: String) = nav.update { n -> n.copy(rename = n.rename?.copy(current = text.take(40))) }

    override fun onRenameConfirm() {
        val r = nav.value.rename ?: return
        nav.update { it.copy(rename = null) }
        // Blank, or the original name, clears the override.
        val name = r.current.trim().takeIf { it.isNotEmpty() && it != r.original }
        scope.launch { withContext(io) { data.watchlist.rename(r.symbol, name) } }
    }

    override fun onRenameCancel() = nav.update { it.copy(rename = null) }

    override fun onAskRemove(symbol: String) = nav.update { it.copy(confirmRemove = symbol) }

    override fun onCancelRemove() = nav.update { it.copy(confirmRemove = null) }

    override fun onConfirmRemove() {
        val sym = nav.value.confirmRemove ?: return
        nav.update { it.copy(confirmRemove = null) }
        scope.launch {
            withContext(io) { data.remove(sym) }
            nav.update { n -> if (n.detail?.symbol == sym) n.copy(detail = null) else n }
        }
    }

    // ---- detail -------------------------------------------------------------------------------------------------

    override fun onOpen(symbol: String) {
        if (data.watchlist.items.value.none { it.symbol == symbol }) return
        nav.update { it.copy(detail = DetailSel(symbol, ChartRange.D1)) }
        loadRange(symbol, ChartRange.D1, force = false)
    }

    override fun onCloseDetail() {
        detailJob?.cancel()
        nav.update { it.copy(detail = null) }
    }

    override fun onSelectRange(range: ChartRange) {
        val d = nav.value.detail ?: return
        nav.update { it.copy(detail = d.copy(range = range)) }
        loadRange(d.symbol, range, force = false)
    }

    override fun onRetryRange() {
        val d = nav.value.detail ?: return
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

    override fun onAddOpen() = nav.update { it.copy(add = AddState()) }

    override fun onReplaceOpen(symbol: String) {
        if (data.watchlist.items.value.none { it.symbol == symbol }) return
        nav.update { it.copy(add = AddState(replacing = symbol)) }
    }

    override fun onAddClose() {
        searchJob?.cancel()
        nav.update { it.copy(add = null) }
    }

    override fun onQueryChange(text: String) {
        val a = nav.value.add ?: return
        val q = text.take(64)
        nav.update { it.copy(add = a.copy(query = q, message = null, searching = false, results = if (q.isBlank()) emptyList() else a.results, searchNote = "")) }
        searchJob?.cancel()
        val term = q.trim()
        if (term.isEmpty()) return
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
                is FetchResult.Ok -> updateAdd {
                    it.copy(searching = false, results = r.value, searchNote = if (r.value.isEmpty()) "No matches. Press Done to try it as a symbol." else "")
                }
                is FetchResult.Err -> updateAdd { it.copy(searching = false, results = emptyList(), searchNote = searchFailure(r.kind)) }
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

    override fun onPickResult(symbol: String) {
        val a = nav.value.add ?: return
        if (a.busy) return
        scope.launch { submit(symbol, anyway = false, closeOnAdded = a.replacing != null) }
    }

    override fun onSubmitTyped() {
        val a = nav.value.add ?: return
        if (a.busy) return
        val typed = a.query.trim()
        if (typed.isEmpty()) {
            onAddClose()
            return
        }
        scope.launch { submit(typed, anyway = false, closeOnAdded = true) }
    }

    override fun onAddAnyway() {
        val a = nav.value.add ?: return
        val sym = a.message?.addAnywaySymbol ?: return
        if (a.busy) return
        scope.launch { submit(sym, anyway = true, closeOnAdded = true) }
    }

    private suspend fun submit(raw: String, anyway: Boolean, closeOnAdded: Boolean) {
        val a = nav.value.add ?: return
        val replacing = a.replacing
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
            it.copy(
                busy = false,
                query = if (outcome is AddOutcome.Added) "" else it.query,
                results = if (outcome is AddOutcome.Added) emptyList() else it.results,
                searching = false,
                message = AddMessage(t.text, t.warn, addAnywaySymbol = if (t.canAddAnyway && sym != null) sym else null),
            )
        }
    }
}
