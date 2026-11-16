package com.marketsboard.data.settings

import com.marketsboard.data.Clock
import com.marketsboard.data.provider.num
import com.marketsboard.data.store.LoadStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The user's app settings. Everything is sanitised on the way in ([sanitized]) and on the way out of the file, so a hand
 * edited or half written file can never produce a rate of 40 or a NaN.
 */
data class AppSettings(
    /** Passenger mode: the full dashboard stays available while the truck moves. SESSION ONLY: never written to disk, so a cold start is always off (the gate also switches it off after 10 minutes stopped). */
    val passengerMode: Boolean = false,
    /** Speak the briefing. Off: "Brief me" shows it as text. */
    val voiceEnabled: Boolean = true,
    /** TTS speech rate, 0.8 to 1.2 in steps of 0.05. */
    val speechRate: Float = 1.0f,
    /** Read up to three headlines in the briefing. */
    val includeNews: Boolean = true,
    /** The media play/pause key starts and stops the briefing while the app is in the foreground. */
    val mediaKeyTrigger: Boolean = false,
) {
    fun sanitized(): AppSettings = copy(speechRate = clampRate(speechRate))

    companion object {
        const val MIN_RATE = 0.8f
        const val MAX_RATE = 1.2f

        fun clampRate(r: Float): Float {
            if (!r.isFinite()) return 1.0f
            val c = r.coerceIn(MIN_RATE, MAX_RATE)
            return Math.round(c * 20f) / 20f // steps of 0.05
        }
    }
}

/**
 * Persists [AppSettings] as `settings.json` v1 (see [JsonFileStore] for the durability and corruption rules). [update]
 * changes the in-memory state at once (so a switch reacts immediately) and marks it dirty; [flush] writes it and is meant
 * for an IO dispatcher. A failed write keeps the value in memory and is retried by the next [flush]. A file from a newer
 * version is used read-only: [update] still changes memory but nothing is ever written over it.
 */
class AppSettingsStore(file: File, clock: Clock = Clock.System) {
    private val io = JsonFileStore(file, clock, VERSION, ::decode)
    private val lock = Any()
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    var loadStatus: LoadStatus = LoadStatus.Fresh
        private set
    val readOnly: Boolean get() = loadStatus == LoadStatus.NewerVersionReadOnly
    private var dirty = false
    private val _persistError = MutableStateFlow<String?>(null)
    val persistError: StateFlow<String?> = _persistError.asStateFlow()

    init {
        val r = io.load()
        loadStatus = r.status
        _settings.value = (r.value ?: AppSettings()).sanitized()
        // A recovery or a first run marks the (default) file for writing; the first [flush] (IO dispatcher) creates it. Nothing is written here: this runs on the main thread.
        if (!readOnly && (r.status == LoadStatus.Fresh || r.status == LoadStatus.RecoveredFromBackup || r.status == LoadStatus.RecoveredFromCorrupt)) dirty = true
    }

    fun update(f: (AppSettings) -> AppSettings) = synchronized(lock) {
        val prev = _settings.value
        val next = f(prev).sanitized()
        if (next != prev) {
            _settings.value = next
            // Passenger mode is session only: changing just that needs no write.
            if (next.copy(passengerMode = false) != prev.copy(passengerMode = false)) dirty = true
        }
    }

    /** Writes pending changes. True when nothing is pending afterwards. */
    fun flush(): Boolean = synchronized(lock) {
        if (!dirty) return true
        if (readOnly) { dirty = false; return true }
        try {
            io.write(encode(_settings.value))
            dirty = false
            _persistError.value = null
            true
        } catch (e: IOException) {
            _persistError.value = "${e.javaClass.simpleName}: ${e.message}"
            false
        } catch (e: RuntimeException) {
            _persistError.value = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    private fun decode(o: JsonObject): AppSettings? {
        fun bool(key: String, def: Boolean): Boolean = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { it == "true" || (it != "false" && def) } ?: def
        val d = AppSettings()
        return AppSettings(
            passengerMode = false, // session only: a stored value (older file, hand edit) is ignored
            voiceEnabled = bool("voiceEnabled", d.voiceEnabled),
            speechRate = o.num("speechRate")?.toFloat() ?: d.speechRate,
            includeNews = bool("includeNews", d.includeNews),
            mediaKeyTrigger = bool("mediaKeyTrigger", d.mediaKeyTrigger),
        ).sanitized()
    }

    companion object {
        const val VERSION = 1
        private val PRETTY = Json { prettyPrint = true }

        fun encode(s: AppSettings): String = PRETTY.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", VERSION)
                put("voiceEnabled", s.voiceEnabled)
                put("speechRate", s.speechRate.toDouble())
                put("includeNews", s.includeNews)
                put("mediaKeyTrigger", s.mediaKeyTrigger)
            },
        )
    }
}
