package com.marketsboard.data.settings

import com.marketsboard.data.Clock
import com.marketsboard.data.provider.arr
import com.marketsboard.data.store.LoadStatus
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The headlines a briefing has already read, so the next one does not repeat them. */
interface BriefingHistory {
    /** Ids read before (newest 300). */
    fun spoken(): Set<String>

    /** Remembers [ids] (and persists, so it survives a restart). Blocking file IO: call from an IO dispatcher. */
    fun remember(ids: Collection<String>)

    companion object {
        val None: BriefingHistory = object : BriefingHistory {
            override fun spoken(): Set<String> = emptySet()
            override fun remember(ids: Collection<String>) {}
        }
    }
}

/**
 * [BriefingHistory] in `briefing-history.json` v1 (same durability as the other stores). Only the newest [MAX_IDS] ids
 * are kept, so it cannot grow without bound; headlines older than 24 h are never read anyway.
 */
class BriefingHistoryStore(file: File, clock: Clock = Clock.System) : BriefingHistory {
    private val io = JsonFileStore(file, clock, VERSION, ::decode)
    private val lock = Any()
    private var ids: List<String> = emptyList()
    private var readOnly = false

    init {
        val r = io.load()
        ids = (r.value ?: emptyList()).takeLast(MAX_IDS)
        readOnly = r.status == LoadStatus.NewerVersionReadOnly
    }

    override fun spoken(): Set<String> = synchronized(lock) { ids.toSet() }

    override fun remember(ids: Collection<String>) {
        synchronized(lock) {
            val merged = (this.ids + ids.filter { it.isNotBlank() }).distinct().takeLast(MAX_IDS)
            if (merged == this.ids) return
            this.ids = merged
            if (readOnly) return
            try {
                io.write(encode(merged))
            } catch (e: IOException) {
                // The ids stay in memory for this run; a lost write at worst repeats a headline.
            } catch (e: RuntimeException) {
            }
        }
    }

    private fun decode(o: JsonObject): List<String>? = o.arr("spoken")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() && s.length <= 200 } }

    companion object {
        const val VERSION = 1
        const val MAX_IDS = 300
        private val PRETTY = Json { prettyPrint = true }

        fun encode(ids: List<String>): String = PRETTY.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", VERSION)
                put("spoken", JsonArray(ids.map { JsonPrimitive(it) }))
            },
        )
    }
}
