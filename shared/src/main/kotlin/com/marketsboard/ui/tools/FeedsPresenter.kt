package com.marketsboard.ui.tools

import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.net.hostOf
import com.marketsboard.data.news.FeedEdit
import com.marketsboard.data.news.FeedPlan
import com.marketsboard.data.news.FeedStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The news feed editor over [FeedStore]: enable or disable, rename, move, add (https only), remove, reset to the defaults and
 * the "my symbols" switch. The poller notices the store's change on its own, retags and fetches the new feeds.
 *
 * Edits are written through a serial queue on [io] (the store fsyncs), so rapid taps keep their order and the UI thread never
 * waits for the disk. State comes from the store's own flows, so what the screen shows is what is saved (or at least held).
 * Not driving-gated: the owner asked for these screens to work at any speed.
 */
class FeedsPresenter(
    private val scope: CoroutineScope,
    private val store: FeedStore,
    private val watch: StateFlow<List<WatchItem>>,
    io: CoroutineDispatcher = Dispatchers.IO,
) : FeedsActions {
    private data class Nav(
        val selected: String? = null,
        val add: FeedAddUi? = null,
        val rename: FeedRenameUi? = null,
        val confirmRemove: FeedRemoveUi? = null,
        val confirmReset: Boolean = false,
        /** Changes with every opened Add sheet: a result that belongs to an earlier sheet never touches a newer one. */
        val addNonce: Long = 0,
    )

    private val nav = MutableStateFlow(Nav())
    private val queue = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(io) {
            for (job in queue) {
                try { job() } catch (e: CancellationException) { throw e } catch (e: Throwable) { /* the store never throws; belt and braces */ }
            }
        }
    }

    val state: StateFlow<FeedsUiState> = combine(store.feeds, store.symbolFeeds, watch, store.persistError, nav) { feeds, sym, w, persistError, n ->
        FeedsUiState(
            rows = feeds.mapIndexed { i, f ->
                FeedRowUi(
                    f.id, f.name, hostOf(f.url).removePrefix("www.").takeIf { it != "?" }.orEmpty(), f.enabled, f.kind, f.symbol,
                    canMoveUp = i > 0, canMoveDown = i < feeds.size - 1, selected = f.id == n.selected,
                )
            },
            symbolFeeds = sym,
            symbolFeedCount = FeedPlan.autoFeeds(w).size,
            readOnly = store.readOnly,
            add = n.add, rename = n.rename, confirmRemove = n.confirmRemove, confirmReset = n.confirmReset,
            notice = when {
                store.readOnly -> "These feeds were saved by a newer version of the app and cannot be changed here."
                persistError != null -> "The last change could not be saved yet. It stays in use and the app tries again."
                else -> ""
            },
            atLimit = feeds.size >= FeedStore.MAX_FEEDS,
        )
    }.stateIn(scope, SharingStarted.Eagerly, FeedsUiState())

    private fun enqueue(job: () -> Unit) { queue.trySend(job) }

    /** Closes the topmost sheet or dialog; false when none is open (the caller then leaves the page). */
    fun closeOverlay(): Boolean {
        val n = nav.value
        return when {
            n.confirmRemove != null -> { nav.update { it.copy(confirmRemove = null) }; true }
            n.confirmReset -> { nav.update { it.copy(confirmReset = false) }; true }
            n.rename != null -> { nav.update { it.copy(rename = null) }; true }
            n.add != null -> { nav.update { it.copy(add = null) }; true }
            else -> false
        }
    }

    /** Leaving the editor drops the open row and anything half typed. */
    fun reset() { nav.value = Nav() }

    override fun onSelect(id: String) = nav.update { it.copy(selected = if (it.selected == id) null else id) }

    override fun onToggle(id: String, enabled: Boolean) = enqueue { store.setEnabled(id, enabled) }

    override fun onMove(id: String, delta: Int) = enqueue {
        val i = store.feeds.value.indexOfFirst { it.id == id }
        if (i >= 0) store.move(i, i + delta)
    }

    override fun onAskRename(id: String) {
        val f = store.feeds.value.firstOrNull { it.id == id } ?: return
        nav.update { it.copy(rename = FeedRenameUi(id, f.name, f.name)) }
    }

    override fun onRenameChange(text: String) = nav.update { n -> n.copy(rename = n.rename?.copy(current = text.take(FeedStore.MAX_NAME_LENGTH + 20), message = "")) }

    override fun onRenameConfirm() {
        val r = nav.value.rename ?: return
        if (FeedStore.cleanName(r.current).isEmpty()) {
            nav.update { it.copy(rename = r.copy(message = "A name cannot be empty")) }
            return
        }
        nav.update { it.copy(rename = null) }
        enqueue { store.rename(r.id, r.current) }
    }

    override fun onRenameCancel() = nav.update { it.copy(rename = null) }

    override fun onAskRemove(id: String) {
        val f = store.feeds.value.firstOrNull { it.id == id } ?: return
        nav.update { it.copy(confirmRemove = FeedRemoveUi(id, f.name)) }
    }

    override fun onConfirmRemove() {
        val c = nav.value.confirmRemove ?: return
        nav.update { it.copy(confirmRemove = null, selected = if (it.selected == c.id) null else it.selected) }
        enqueue { store.remove(c.id) }
    }

    override fun onCancelRemove() = nav.update { it.copy(confirmRemove = null) }

    override fun onAddOpen() = nav.update { it.copy(add = FeedAddUi(), addNonce = it.addNonce + 1) }

    override fun onAddNameChange(text: String) = nav.update { n -> n.copy(add = n.add?.copy(name = text.take(FeedStore.MAX_NAME_LENGTH + 20), message = "")) }

    override fun onAddUrlChange(text: String) = nav.update { n -> n.copy(add = n.add?.copy(url = text.take(MAX_TYPED_URL), message = "")) }

    override fun onAddConfirm() {
        val a = nav.value.add ?: return
        if (a.busy) return // a second tap while the first is being written does nothing
        if (a.url.isBlank()) { nav.update { it.copy(add = a.copy(message = "Type the feed address first")) }; return }
        val nonce = nav.value.addNonce
        nav.update { it.copy(add = a.copy(busy = true, message = "")) }
        enqueue {
            val reserved = if (store.symbolFeeds.value) FeedPlan.autoFeeds(watch.value) else emptyList()
            val result = store.add(a.name, withScheme(a.url), reserved = reserved)
            // The sheet this Add came from may be gone (Cancel, then a new sheet): only the sheet with the same nonce is touched.
            nav.update { n ->
                val cur = n.add
                when {
                    cur == null || n.addNonce != nonce -> n
                    result is FeedEdit.Done -> n.copy(add = null)
                    else -> n.copy(add = cur.copy(busy = false, message = addMessage(result)))
                }
            }
        }
    }

    override fun onAddCancel() = nav.update { it.copy(add = null) }

    override fun onSymbolFeeds(on: Boolean) = enqueue { store.setSymbolFeeds(on) }

    override fun onAskReset() = nav.update { it.copy(confirmReset = true) }

    override fun onConfirmReset() {
        nav.update { it.copy(confirmReset = false, selected = null) }
        enqueue { store.resetToDefaults() }
    }

    override fun onCancelReset() = nav.update { it.copy(confirmReset = false) }

    companion object {
        /** What the address field keeps; longer text is refused in words by the store (never cut silently). */
        const val MAX_TYPED_URL = 2_000

        /** "example.com/feed" is taken as https. An explicit http address stays http so the store can refuse it in words. */
        fun withScheme(raw: String): String {
            val t = raw.trim()
            return if (t.contains("://")) t else "https://$t"
        }

        fun addMessage(e: FeedEdit): String = when (e) {
            is FeedEdit.Invalid -> e.reason
            is FeedEdit.Duplicate -> if (e.existing.auto) "Already covered by Feeds for my symbols (${e.existing.symbol ?: "watchlist"})" else "Already in your list as ${e.existing.name}"
            FeedEdit.LimitReached -> "The list is full (${FeedStore.MAX_FEEDS} feeds). Remove one first"
            FeedEdit.ReadOnly -> "These feeds were saved by a newer version of the app and cannot be changed here"
            FeedEdit.NotFound -> "That feed no longer exists"
            is FeedEdit.Done -> ""
        }
    }
}
