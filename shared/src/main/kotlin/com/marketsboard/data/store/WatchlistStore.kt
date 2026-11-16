package com.marketsboard.data.store

import com.marketsboard.data.Clock
import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.Currencies
import com.marketsboard.data.model.ErrorKind
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.provider.Js
import com.marketsboard.data.provider.MarketDataProvider
import com.marketsboard.data.provider.arr
import com.marketsboard.data.provider.asObj
import com.marketsboard.data.provider.num
import com.marketsboard.data.provider.str
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Result of a plain edit. */
sealed interface EditResult {
    /** [persisted] is false when the file write failed; the edit still applies in memory and is retried on the next edit. */
    data class Done(val persisted: Boolean = true) : EditResult
    data class Duplicate(val existing: WatchItem) : EditResult
    data class Invalid(val reason: String) : EditResult
    data object NotFound : EditResult
    data object LimitReached : EditResult
    /** The file on disk was written by a newer app version (or is unreadable); edits are refused so it is never overwritten. */
    data object ReadOnly : EditResult
}

/** Result of [WatchlistStore.validateAndAdd]. */
sealed interface AddOutcome {
    data class Added(val item: WatchItem, val persisted: Boolean = true) : AddOutcome
    data class Duplicate(val existing: WatchItem) : AddOutcome
    data class Invalid(val reason: String) : AddOutcome
    /** The provider says the symbol does not exist. */
    data object NotFound : AddOutcome
    data object LimitReached : AddOutcome
    data object ReadOnly : AddOutcome
    /** Could not verify (offline, throttled, provider down). [canAddAnyway] lets the editor offer "add anyway". */
    data class NetworkError(val kind: ErrorKind, val message: String, val canAddAnyway: Boolean = true) : AddOutcome
}

/** How the file was interpreted on load. */
enum class LoadStatus { Fresh, Loaded, RecoveredFromCorrupt, RecoveredFromBackup, NewerVersionReadOnly, Unreadable }

/**
 * The user's watchlist, persisted as `{ "version": 1, "items": [...] }` in [file].
 *
 * Guarantees:
 * - every successful edit is written to a temp file, fsync'ed, then atomically renamed, before the call returns;
 *   the previous good file is first copied to `watchlist.bak`
 * - a corrupt, truncated or empty file (power cut) is recovered from `watchlist.bak` when that is valid, otherwise
 *   defaults are used; a non-empty corrupt file is renamed to `watchlist.corrupt-<ts>.json` (never deleted)
 * - a failed write keeps the edit in memory and is retried by the next edit or by [flushIfDirty]
 * - items that fail to parse are dropped from the list but the original file is copied aside first
 * - a file with a HIGHER version is never overwritten: its readable items are shown, edits return [EditResult.ReadOnly]
 * - unknown JSON fields are ignored on read (and not preserved on rewrite)
 * File IO is a few hundred bytes on the calling thread, guarded by one lock; M2 calls it from Dispatchers.IO.
 */
class WatchlistStore(
    private val file: File,
    private val clock: Clock = Clock.System,
) : Watchlist {
    private val lock = Any()
    private val _items = MutableStateFlow(DEFAULTS)
    override val items: StateFlow<List<WatchItem>> = _items.asStateFlow()

    var loadStatus: LoadStatus = LoadStatus.Fresh
        private set
    override val readOnly: Boolean get() = loadStatus == LoadStatus.NewerVersionReadOnly || loadStatus == LoadStatus.Unreadable

    private val _persistError = MutableStateFlow<String?>(null)
    /** Non-null while the last write failed (disk full, read-only storage). The in-memory list is still current. */
    override val persistError: StateFlow<String?> = _persistError.asStateFlow()

    private var dirty = false

    init {
        synchronized(lock) { load() }
    }

    // ---- edits -------------------------------------------------------------------------------------------------

    fun add(item: WatchItem, atIndex: Int? = null): EditResult = edit {
        val clean = sanitize(item) ?: return@edit EditResult.Invalid("Invalid symbol")
        val cur = _items.value
        cur.firstOrNull { it.symbol == clean.symbol }?.let { return@edit EditResult.Duplicate(it) }
        if (cur.size >= MAX_ITEMS) return@edit EditResult.LimitReached
        val idx = (atIndex ?: cur.size).coerceIn(0, cur.size)
        commit(cur.toMutableList().apply { add(idx, clean) })
    }

    override fun remove(symbol: String): EditResult = edit {
        val sym = Symbols.normalize(symbol) ?: return@edit EditResult.NotFound
        val cur = _items.value
        if (cur.none { it.symbol == sym }) return@edit EditResult.NotFound
        commit(cur.filter { it.symbol != sym })
    }

    override fun move(from: Int, to: Int): EditResult = edit {
        val cur = _items.value
        if (from !in cur.indices || to !in cur.indices) return@edit EditResult.Invalid("Index out of range")
        if (from == to) return@edit EditResult.Done(persisted = !dirty)
        commit(cur.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Sets or clears (null or blank) the display name override. */
    override fun rename(symbol: String, displayName: String?): EditResult = edit {
        val sym = Symbols.normalize(symbol) ?: return@edit EditResult.NotFound
        val cur = _items.value
        val i = cur.indexOfFirst { it.symbol == sym }
        if (i < 0) return@edit EditResult.NotFound
        commit(cur.toMutableList().apply { this[i] = this[i].copy(displayName = cleanDisplayName(displayName)) })
    }

    /** Swaps the instrument [old] for [new] keeping its position (and the display name unless [new] carries one). */
    override fun replace(old: String, new: WatchItem): EditResult = edit {
        val oldSym = Symbols.normalize(old) ?: return@edit EditResult.NotFound
        val clean = sanitize(new) ?: return@edit EditResult.Invalid("Invalid symbol")
        val cur = _items.value
        val i = cur.indexOfFirst { it.symbol == oldSym }
        if (i < 0) return@edit EditResult.NotFound
        cur.firstOrNull { it.symbol == clean.symbol && it.symbol != oldSym }?.let { return@edit EditResult.Duplicate(it) }
        commit(cur.toMutableList().apply { this[i] = clean })
    }

    override fun resetToDefaults(): EditResult = edit { commit(DEFAULTS) }

    /**
     * Fetches a quote through [provider] to fill name, currency and exchange, then adds the item.
     * Offline or throttled gives [AddOutcome.NetworkError] with `canAddAnyway`; see [addUnverified].
     */
    override suspend fun validateAndAdd(rawSymbol: String, provider: MarketDataProvider, displayName: String?): AddOutcome {
        val sym = Symbols.normalize(rawSymbol) ?: return AddOutcome.Invalid("Use letters, digits and . ^ = - only (max ${Symbols.MAX_LENGTH})")
        if (readOnly) return AddOutcome.ReadOnly
        _items.value.firstOrNull { it.symbol == sym }?.let { return AddOutcome.Duplicate(it) }
        if (_items.value.size >= MAX_ITEMS) return AddOutcome.LimitReached
        return when (val r = provider.quote(sym, ChartRange.D1)) {
            is FetchResult.Ok -> {
                val q = r.value
                val item = WatchItem(sym, q.name.ifBlank { sym }, q.currency, q.exchange, cleanDisplayName(displayName))
                toOutcome(add(item), item)
            }
            is FetchResult.Err -> when (r.kind) {
                ErrorKind.NotFound -> AddOutcome.NotFound
                else -> AddOutcome.NetworkError(r.kind, r.message)
            }
        }
    }

    /** The "add anyway" path: no verification, unknown currency and exchange (filled on the first good quote). */
    override fun addUnverified(rawSymbol: String, displayName: String?): AddOutcome {
        val sym = Symbols.normalize(rawSymbol) ?: return AddOutcome.Invalid("Invalid symbol")
        val item = WatchItem(sym, sym, "", "", cleanDisplayName(displayName))
        return toOutcome(add(item), item)
    }

    private fun toOutcome(r: EditResult, item: WatchItem): AddOutcome = when (r) {
        is EditResult.Done -> AddOutcome.Added(item, r.persisted)
        is EditResult.Duplicate -> AddOutcome.Duplicate(r.existing)
        is EditResult.Invalid -> AddOutcome.Invalid(r.reason)
        EditResult.LimitReached -> AddOutcome.LimitReached
        EditResult.ReadOnly -> AddOutcome.ReadOnly
        EditResult.NotFound -> AddOutcome.NotFound
    }

    // ---- internals ---------------------------------------------------------------------------------------------

    private inline fun edit(block: () -> EditResult): EditResult = synchronized(lock) {
        if (readOnly) EditResult.ReadOnly else block()
    }

    /** Applies [next] in memory and persists it. Only called with the lock held. */
    private fun commit(next: List<WatchItem>): EditResult {
        _items.value = next
        return EditResult.Done(persist(next))
    }

    /** Retries a failed write now. Call it from the refresh loop or on resume. True when nothing is pending. */
    override fun flushIfDirty(): Boolean = synchronized(lock) { if (!dirty || readOnly) true else persist(_items.value) }

    private fun backupFile(): File = File(file.absoluteFile.parentFile, file.nameWithoutExtension + ".bak")

    private fun persist(list: List<WatchItem>): Boolean {
        val ok = try {
            keepPreviousAsBackup()
            writeAtomic(file, encode(list))
            true
        } catch (e: IOException) {
            _persistError.value = "${e.javaClass.simpleName}: ${e.message}"
            false
        } catch (e: RuntimeException) {
            _persistError.value = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
        dirty = !ok
        if (ok) _persistError.value = null
        return ok
    }

    /** Copies the current file to watchlist.bak, but only if it is a valid current-version file. Best effort. */
    private fun keepPreviousAsBackup() {
        try {
            if (!file.isFile || file.length() == 0L) return
            val text = file.readText(StandardCharsets.UTF_8)
            if (parse(text) is Parsed.Valid) writeAtomic(backupFile(), text)
        } catch (e: IOException) {
            // The backup is a bonus; the edit itself must still go through.
        }
    }

    private sealed interface Parsed {
        class Valid(val items: List<WatchItem>, val dropped: Int) : Parsed
        class Newer(val items: List<WatchItem>) : Parsed
        data object Invalid : Parsed
    }

    private fun parse(text: String): Parsed {
        val root = Js.parse(text).asObj() ?: return Parsed.Invalid
        val version = root.num("version")?.takeIf { it == Math.floor(it) }?.toInt()
        val itemsArr = root.arr("items")
        if (version == null || version < 1 || itemsArr == null) return Parsed.Invalid
        val parsed = ArrayList<WatchItem>()
        var dropped = 0
        for (e in itemsArr) {
            val item = e.asObj()?.let { o ->
                sanitize(WatchItem(o.str("symbol").orEmpty(), o.str("name").orEmpty(), o.str("currency").orEmpty(), o.str("exchange").orEmpty(), o.str("displayName")))
            }
            if (item == null || parsed.any { it.symbol == item.symbol }) dropped++ else parsed.add(item)
        }
        return if (version > VERSION) {
            Parsed.Newer(if (parsed.isEmpty() && itemsArr.isNotEmpty()) DEFAULTS else parsed.take(MAX_ITEMS))
        } else {
            Parsed.Valid(parsed.take(MAX_ITEMS), dropped)
        }
    }

    private fun readBackup(): List<WatchItem>? = try {
        val b = backupFile()
        if (b.isFile) (parse(b.readText(StandardCharsets.UTF_8)) as? Parsed.Valid)?.items else null
    } catch (e: IOException) {
        null
    }

    private fun load() {
        if (!file.exists()) {
            val bak = readBackup()
            if (bak != null) {
                loadStatus = LoadStatus.RecoveredFromBackup
                _items.value = bak
                persist(bak)
            } else {
                loadStatus = LoadStatus.Fresh
                _items.value = DEFAULTS
                persist(DEFAULTS)
            }
            return
        }
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (e: IOException) {
            // Unreadable (permissions, IO error): keep the file untouched and run on defaults, read-only.
            loadStatus = LoadStatus.Unreadable
            _items.value = DEFAULTS
            return
        }
        when (val p = parse(text)) {
            is Parsed.Valid -> {
                if (p.dropped > 0) quarantine(rename = false) // keep the original; the next edit rewrites a clean file
                loadStatus = LoadStatus.Loaded
                _items.value = p.items
            }
            is Parsed.Newer -> {
                loadStatus = LoadStatus.NewerVersionReadOnly
                _items.value = p.items
            }
            Parsed.Invalid -> {
                if (file.length() > 0L) quarantine(rename = true)
                val bak = readBackup()
                if (bak != null) {
                    loadStatus = LoadStatus.RecoveredFromBackup
                    _items.value = bak
                    persist(bak)
                } else {
                    loadStatus = LoadStatus.RecoveredFromCorrupt
                    _items.value = DEFAULTS
                    persist(DEFAULTS)
                }
            }
        }
    }

    /** Preserves the offending file as watchlist.corrupt-<ts>.json (renamed or copied); keeps at most 5 such files. */
    private fun quarantine(rename: Boolean) {
        val dir = file.absoluteFile.parentFile ?: return
        val stem = file.nameWithoutExtension
        val target = File(dir, "$stem.corrupt-${clock.nowMs()}.json")
        try {
            if (rename) Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            dir.listFiles { f -> f.name.startsWith("$stem.corrupt-") }
                ?.sortedBy { it.name }?.dropLast(MAX_CORRUPT_BACKUPS)?.forEach { it.delete() }
        } catch (e: IOException) {
            // Best effort: at worst the corrupt file is overwritten by defaults.
        }
    }

    companion object {
        const val VERSION = 1
        const val MAX_ITEMS = 30
        private const val MAX_CORRUPT_BACKUPS = 5
        private const val MAX_NAME = 80
        private const val MAX_DISPLAY = 40

        val DEFAULTS: List<WatchItem> = listOf(
            WatchItem("XEQT.TO", "iShares Core Equity ETF Portfolio", "CAD", "TSX"),
            WatchItem("MSFT", "Microsoft Corporation", "USD", "NASDAQ"),
        )

        private val PRETTY = Json { prettyPrint = true }

        fun encode(list: List<WatchItem>): String = PRETTY.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", VERSION)
                put(
                    "items",
                    JsonArray(
                        list.map {
                            buildJsonObject {
                                put("symbol", it.symbol)
                                put("name", it.name)
                                put("currency", it.currency)
                                put("exchange", it.exchange)
                                if (it.displayName != null) put("displayName", it.displayName)
                            }
                        },
                    ),
                )
            },
        )

        /** Normalises symbol and trims text fields; null when the symbol is invalid. */
        fun sanitize(item: WatchItem): WatchItem? {
            val sym = Symbols.normalize(item.symbol) ?: return null
            return WatchItem(
                symbol = sym,
                name = cleanText(item.name, MAX_NAME).ifEmpty { sym },
                currency = Currencies.normalize(cleanText(item.currency, 5)),
                exchange = cleanText(item.exchange, 30),
                displayName = cleanDisplayName(item.displayName),
            )
        }

        fun cleanDisplayName(s: String?): String? = s?.let { cleanText(it, MAX_DISPLAY) }?.takeIf { it.isNotEmpty() }

        private fun cleanText(s: String, max: Int): String =
            s.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(max)

        /** Temp file, fsync, atomic rename: after a power cut the target is either the old or the new content. */
        internal fun writeAtomic(target: File, text: String) {
            val dir = target.absoluteFile.parentFile
            dir?.mkdirs()
            val tmp = File(dir, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
