package com.marketsboard.data.news

import com.marketsboard.data.Clock
import com.marketsboard.data.model.WatchItem
import com.marketsboard.data.provider.Js
import com.marketsboard.data.provider.arr
import com.marketsboard.data.provider.asObj
import com.marketsboard.data.provider.num
import com.marketsboard.data.provider.str
import com.marketsboard.data.store.LoadStatus
import com.marketsboard.data.store.WatchlistStore
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

/** What the repository needs to know about configured feeds (fakeable). */
interface FeedSource {
    /** The persisted, user-editable feeds (enabled or not). */
    val feeds: StateFlow<List<FeedConfig>>

    /** Whether per-symbol feeds are derived from the watchlist. */
    val symbolFeeds: StateFlow<Boolean>
}

/** Result of a feed edit. */
sealed interface FeedEdit {
    data class Done(val persisted: Boolean = true) : FeedEdit
    data class Invalid(val reason: String) : FeedEdit
    data class Duplicate(val existing: FeedConfig) : FeedEdit
    data object NotFound : FeedEdit
    data object LimitReached : FeedEdit
    data object ReadOnly : FeedEdit
}

/** The feed list the app starts with. All keyless, Canada first, and best effort: none could be verified live. */
object DefaultFeeds {
    val ALL: List<FeedConfig> = listOf(
        FeedConfig("cbc-business", "CBC Business", "https://www.cbc.ca/webfeed/rss/rss-business"),
        FeedConfig("globe-business", "The Globe and Mail", "https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/business/"),
        FeedConfig("financial-post", "Financial Post", "https://financialpost.com/feed"),
        FeedConfig("yahoo-top", "Yahoo Finance", "https://finance.yahoo.com/news/rssindex"),
        FeedConfig("marketwatch-top", "MarketWatch", "https://feeds.content.dowjones.io/public/rss/mw_topstories"),
    )
}

/** Which feeds are actually fetched: the enabled stored ones plus the per-symbol feeds derived from the watchlist. */
object FeedPlan {
    const val MAX_SYMBOL_FEEDS = 6

    fun symbolFeedUrl(symbol: String): String =
        "https://feeds.finance.yahoo.com/rss/2.0/headline?s=" + URLEncoder.encode(symbol, "UTF-8") + "&region=CA&lang=en-CA"

    fun autoFeeds(watch: List<WatchItem>): List<FeedConfig> = watch.take(MAX_SYMBOL_FEEDS).map {
        FeedConfig("sym:${it.symbol}", "Yahoo Finance", symbolFeedUrl(it.symbol), true, FeedKind.Symbol, it.symbol, auto = true)
    }

    fun effective(stored: List<FeedConfig>, watch: List<WatchItem>, symbolFeeds: Boolean): List<FeedConfig> {
        val general = stored.filter { it.enabled }
        return if (symbolFeeds) general + autoFeeds(watch) else general
    }
}

/**
 * The editable feed list, persisted like the watchlist: `feeds.json` v1 written through a temp file with fsync then an
 * atomic rename, previous good copy kept as `feeds.bak`, unreadable files recovered from the backup or defaults (a
 * non-empty broken file is kept as `feeds.corrupt-<ts>.json`), a file from a newer version is shown read-only and never
 * overwritten. The API (add, remove, enable, rename, move) is ready for the M5 editor; no UI uses it yet.
 */
class FeedStore(private val file: File, private val clock: Clock = Clock.System) : FeedSource {
    private val lock = Any()
    private val _feeds = MutableStateFlow(DefaultFeeds.ALL)
    override val feeds: StateFlow<List<FeedConfig>> = _feeds.asStateFlow()
    private val _symbolFeeds = MutableStateFlow(true)
    override val symbolFeeds: StateFlow<Boolean> = _symbolFeeds.asStateFlow()

    var loadStatus: LoadStatus = LoadStatus.Fresh
        private set
    val readOnly: Boolean get() = loadStatus == LoadStatus.NewerVersionReadOnly || loadStatus == LoadStatus.Unreadable

    private val _persistError = MutableStateFlow<String?>(null)
    val persistError: StateFlow<String?> = _persistError.asStateFlow()
    private var dirty = false

    init {
        synchronized(lock) { load() }
    }

    fun add(name: String, url: String, kind: FeedKind = FeedKind.General, symbol: String? = null): FeedEdit = edit {
        val cleanUrl = FeedParser.safeFeedUrl(url)
            ?: return@edit FeedEdit.Invalid(if (FeedParser.safeUrl(url) != null) "Feeds must use an https address" else "Use a full https address")
        val cur = _feeds.value
        val key = Dedupe.canonicalUrl(cleanUrl)
        cur.firstOrNull { Dedupe.canonicalUrl(it.url) == key }?.let { return@edit FeedEdit.Duplicate(it) }
        if (cur.size >= MAX_FEEDS) return@edit FeedEdit.LimitReached
        val title = cleanName(name).ifEmpty { Dedupe.canonicalUrl(cleanUrl).substringBefore('/') }
        val feed = FeedConfig(uniqueId(cur, "f-" + Dedupe.idFor(cleanUrl).take(8)), title, cleanUrl, true, kind, symbol?.trim()?.takeIf { it.isNotEmpty() })
        commit(cur + feed, _symbolFeeds.value)
    }

    fun remove(id: String): FeedEdit = edit {
        val cur = _feeds.value
        if (cur.none { it.id == id }) return@edit FeedEdit.NotFound
        commit(cur.filter { it.id != id }, _symbolFeeds.value)
    }

    fun setEnabled(id: String, enabled: Boolean): FeedEdit = update(id) { it.copy(enabled = enabled) }

    fun rename(id: String, name: String): FeedEdit {
        val n = cleanName(name)
        if (n.isEmpty()) return FeedEdit.Invalid("Name cannot be empty")
        return update(id) { it.copy(name = n) }
    }

    fun move(from: Int, to: Int): FeedEdit = edit {
        val cur = _feeds.value
        if (from !in cur.indices || to !in cur.indices) return@edit FeedEdit.Invalid("Index out of range")
        if (from == to) return@edit FeedEdit.Done(!dirty)
        commit(cur.toMutableList().apply { add(to, removeAt(from)) }, _symbolFeeds.value)
    }

    fun setSymbolFeeds(on: Boolean): FeedEdit = edit { commit(_feeds.value, on) }

    fun resetToDefaults(): FeedEdit = edit { commit(DefaultFeeds.ALL, true) }

    /** Retries a failed write now. True when nothing is pending. */
    fun flushIfDirty(): Boolean = synchronized(lock) { if (!dirty || readOnly) true else persist(_feeds.value, _symbolFeeds.value) }

    // ---- internals ---------------------------------------------------------------------------------------------

    private inline fun update(id: String, f: (FeedConfig) -> FeedConfig): FeedEdit = edit {
        val cur = _feeds.value
        val i = cur.indexOfFirst { it.id == id }
        if (i < 0) return@edit FeedEdit.NotFound
        commit(cur.toMutableList().apply { this[i] = f(this[i]) }, _symbolFeeds.value)
    }

    private inline fun edit(block: () -> FeedEdit): FeedEdit = synchronized(lock) { if (readOnly) FeedEdit.ReadOnly else block() }

    private fun commit(feeds: List<FeedConfig>, symbolFeeds: Boolean): FeedEdit {
        _feeds.value = feeds
        _symbolFeeds.value = symbolFeeds
        return FeedEdit.Done(persist(feeds, symbolFeeds))
    }

    private fun uniqueId(cur: List<FeedConfig>, base: String): String {
        var id = base
        var n = 2
        while (cur.any { it.id == id }) id = "$base-${n++}"
        return id
    }

    private fun backupFile() = File(file.absoluteFile.parentFile, file.nameWithoutExtension + ".bak")

    private fun persist(feeds: List<FeedConfig>, symbolFeeds: Boolean): Boolean {
        val ok = try {
            keepPreviousAsBackup()
            WatchlistStore.writeAtomic(file, encode(feeds, symbolFeeds))
            true
        } catch (e: IOException) {
            _persistError.value = "${e.javaClass.simpleName}: ${e.message}"; false
        } catch (e: RuntimeException) {
            _persistError.value = "${e.javaClass.simpleName}: ${e.message}"; false
        }
        dirty = !ok
        if (ok) _persistError.value = null
        return ok
    }

    private fun keepPreviousAsBackup() {
        try {
            if (!file.isFile || file.length() == 0L) return
            val text = file.readText(StandardCharsets.UTF_8)
            if (parse(text) is Parsed.Valid) WatchlistStore.writeAtomic(backupFile(), text)
        } catch (e: IOException) {
            // The backup is a bonus.
        }
    }

    private sealed interface Parsed {
        class Valid(val feeds: List<FeedConfig>, val symbolFeeds: Boolean, val dropped: Int) : Parsed
        class Newer(val feeds: List<FeedConfig>, val symbolFeeds: Boolean) : Parsed
        data object Invalid : Parsed
    }

    private fun parse(text: String): Parsed {
        val root = Js.parse(text).asObj() ?: return Parsed.Invalid
        val version = root.num("version")?.takeIf { it == Math.floor(it) }?.toInt()
        val arr = root.arr("feeds")
        if (version == null || version < 1 || arr == null) return Parsed.Invalid
        val list = ArrayList<FeedConfig>()
        var dropped = 0
        for (e in arr) {
            val o = e.asObj()
            val url = FeedParser.safeFeedUrl(o?.str("url")) // a stored http address (never written by this app) is dropped, not fetched in clear
            val id = o?.str("id")?.take(60)
            val name = cleanName(o?.str("name").orEmpty())
            if (o == null || url == null || id == null || name.isEmpty() || list.any { it.id == id }) { dropped++; continue }
            val enabled = (o["enabled"] as? JsonPrimitive)?.content?.let { it == "true" } ?: true
            val kind = if (o.str("kind") == "symbol") FeedKind.Symbol else FeedKind.General
            list.add(FeedConfig(id, name, url, enabled, kind, o.str("symbol")))
        }
        val sym = (root["symbolFeeds"] as? JsonPrimitive)?.content?.let { it != "false" } ?: true
        val kept = list.take(MAX_FEEDS)
        return if (version > VERSION) Parsed.Newer(if (kept.isEmpty() && arr.isNotEmpty()) DefaultFeeds.ALL else kept, sym) else Parsed.Valid(kept, sym, dropped)
    }

    private fun readBackup(): Parsed.Valid? = try {
        val b = backupFile()
        if (b.isFile) parse(b.readText(StandardCharsets.UTF_8)) as? Parsed.Valid else null
    } catch (e: IOException) {
        null
    }

    private fun apply(feeds: List<FeedConfig>, sym: Boolean) { _feeds.value = feeds; _symbolFeeds.value = sym }

    private fun load() {
        if (!file.exists()) {
            val bak = readBackup()
            if (bak != null) { loadStatus = LoadStatus.RecoveredFromBackup; apply(bak.feeds, bak.symbolFeeds); persist(bak.feeds, bak.symbolFeeds) }
            else { loadStatus = LoadStatus.Fresh; apply(DefaultFeeds.ALL, true); persist(DefaultFeeds.ALL, true) }
            return
        }
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (e: IOException) {
            loadStatus = LoadStatus.Unreadable; apply(DefaultFeeds.ALL, true); return
        }
        when (val p = parse(text)) {
            is Parsed.Valid -> {
                if (p.dropped > 0) quarantine(rename = false)
                loadStatus = LoadStatus.Loaded; apply(p.feeds, p.symbolFeeds)
            }
            is Parsed.Newer -> { loadStatus = LoadStatus.NewerVersionReadOnly; apply(p.feeds, p.symbolFeeds) }
            Parsed.Invalid -> {
                if (file.length() > 0L) quarantine(rename = true)
                val bak = readBackup()
                if (bak != null) { loadStatus = LoadStatus.RecoveredFromBackup; apply(bak.feeds, bak.symbolFeeds); persist(bak.feeds, bak.symbolFeeds) }
                else { loadStatus = LoadStatus.RecoveredFromCorrupt; apply(DefaultFeeds.ALL, true); persist(DefaultFeeds.ALL, true) }
            }
        }
    }

    private fun quarantine(rename: Boolean) {
        val dir = file.absoluteFile.parentFile ?: return
        val stem = file.nameWithoutExtension
        val target = File(dir, "$stem.corrupt-${clock.nowMs()}.json")
        try {
            if (rename) Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            dir.listFiles { f -> f.name.startsWith("$stem.corrupt-") }?.sortedBy { it.name }?.dropLast(5)?.forEach { it.delete() }
        } catch (e: IOException) {
            // Best effort.
        }
    }

    companion object {
        const val VERSION = 1
        const val MAX_FEEDS = 20
        const val MAX_NAME_LENGTH = 60
        private const val MAX_NAME = MAX_NAME_LENGTH
        private val PRETTY = Json { prettyPrint = true }

        fun cleanName(s: String): String = s.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

        fun encode(feeds: List<FeedConfig>, symbolFeeds: Boolean): String = PRETTY.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", VERSION)
                put("symbolFeeds", symbolFeeds)
                put(
                    "feeds",
                    JsonArray(
                        feeds.filter { !it.auto }.map {
                            buildJsonObject {
                                put("id", it.id)
                                put("name", it.name)
                                put("url", it.url)
                                put("enabled", it.enabled)
                                put("kind", if (it.kind == FeedKind.Symbol) "symbol" else "general")
                                if (it.symbol != null) put("symbol", it.symbol)
                            }
                        },
                    ),
                )
            },
        )
    }
}
