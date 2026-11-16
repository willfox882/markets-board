package com.marketsboard.data.settings

import com.marketsboard.data.Clock
import com.marketsboard.data.provider.Js
import com.marketsboard.data.provider.asObj
import com.marketsboard.data.provider.num
import com.marketsboard.data.store.LoadStatus
import com.marketsboard.data.store.WatchlistStore
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.JsonObject

/** Result of reading a [JsonFileStore]: the decoded value (null: use defaults) and how the file was found. */
class JsonLoad<T>(val value: T?, val status: LoadStatus)

/**
 * The atomic, versioned JSON file pattern of the watchlist and feed stores, for small single-document files: write to a
 * temp file, fsync, atomic rename, keep the previous good copy as `<name>.bak`; a missing file reads as defaults; a
 * zero-length, truncated or garbled file is quarantined as `<name>.corrupt-<ts>.json` (newest five kept) and recovered from
 * the backup or defaults; a file from a NEWER version is decoded as far as it can be and flagged read-only so it is never
 * overwritten. [decode] receives the parsed root object and returns null when it is not usable.
 */
internal class JsonFileStore<T : Any>(
    private val file: File,
    private val clock: Clock,
    private val currentVersion: Int,
    private val decode: (JsonObject) -> T?,
) {
    fun load(): JsonLoad<T> {
        if (!file.exists()) {
            val bak = readBackup()
            return if (bak != null) JsonLoad(bak, LoadStatus.RecoveredFromBackup) else JsonLoad(null, LoadStatus.Fresh)
        }
        val text = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (e: IOException) {
            return JsonLoad(null, LoadStatus.Unreadable)
        }
        val parsed = parse(text)
        return when {
            parsed != null && parsed.first > currentVersion -> JsonLoad(parsed.second, LoadStatus.NewerVersionReadOnly)
            parsed != null -> JsonLoad(parsed.second, LoadStatus.Loaded)
            else -> {
                if (file.length() > 0L) quarantine()
                val bak = readBackup()
                if (bak != null) JsonLoad(bak, LoadStatus.RecoveredFromBackup) else JsonLoad(null, LoadStatus.RecoveredFromCorrupt)
            }
        }
    }

    /** Writes [text] durably. Throws [IOException] on failure (the caller keeps the value in memory and retries). */
    fun write(text: String) {
        keepPreviousAsBackup()
        WatchlistStore.writeAtomic(file, text)
    }

    private fun parse(text: String): Pair<Int, T>? {
        val root = Js.parse(text).asObj() ?: return null
        val version = root.num("version")?.takeIf { it == Math.floor(it) }?.toInt() ?: return null
        if (version < 1) return null
        val value = decode(root) ?: return null
        return version to value
    }

    private fun backupFile() = File(file.absoluteFile.parentFile, file.nameWithoutExtension + ".bak")

    private fun readBackup(): T? = try {
        val b = backupFile()
        if (b.isFile) parse(b.readText(StandardCharsets.UTF_8))?.takeIf { it.first <= currentVersion }?.second else null
    } catch (e: IOException) {
        null
    }

    private fun keepPreviousAsBackup() {
        try {
            if (!file.isFile || file.length() == 0L) return
            val text = file.readText(StandardCharsets.UTF_8)
            val p = parse(text)
            if (p != null && p.first <= currentVersion) WatchlistStore.writeAtomic(backupFile(), text)
        } catch (e: IOException) {
            // The backup is a bonus; the write itself must still go through.
        }
    }

    private fun quarantine() {
        val dir = file.absoluteFile.parentFile ?: return
        val stem = file.nameWithoutExtension
        val target = File(dir, "$stem.corrupt-${clock.nowMs()}.json")
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            dir.listFiles { f -> f.name.startsWith("$stem.corrupt-") }?.sortedBy { it.name }?.dropLast(5)?.forEach { it.delete() }
        } catch (e: IOException) {
            // Best effort.
        }
    }
}
