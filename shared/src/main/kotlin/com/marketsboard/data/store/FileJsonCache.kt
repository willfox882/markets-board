package com.marketsboard.data.store

import com.marketsboard.data.Clock
import com.marketsboard.data.provider.Js
import com.marketsboard.data.provider.asObj
import com.marketsboard.data.provider.long
import com.marketsboard.data.provider.str
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One cached payload with its bookkeeping. */
data class CacheEntry(val key: String, val payload: String, val savedAtMs: Long, val expiresAtMs: Long) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

/**
 * Small directory-backed cache of JSON strings.
 *
 * - one file per key (name is a hash of the key), written to a temp file then renamed, so a crash never leaves a
 *   half-written entry
 * - per-entry TTL: [get] hides expired entries unless `allowExpired` (stale-while-revalidate wants them)
 * - total size cap: after each put the oldest files are evicted until the directory fits
 * - corrupt or unreadable entries are deleted and treated as missing
 * Blocking file IO guarded by one lock: call it from Dispatchers.IO.
 */
class FileJsonCache(
    private val dir: File,
    private val clock: Clock = Clock.System,
    private val maxTotalBytes: Long = 4L * 1024 * 1024,
) {
    private val lock = Any()

    init {
        runCatching { dir.mkdirs() }
        synchronized(lock) {
            // Leftovers of an interrupted write.
            dir.listFiles { f -> f.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
        }
    }

    fun put(key: String, payload: String, ttlMs: Long): Boolean = synchronized(lock) {
        try {
            dir.mkdirs()
            val now = clock.nowMs()
            val env = buildJsonObject {
                put("v", 1)
                put("key", key)
                put("savedAt", now)
                put("expiresAt", now + ttlMs.coerceAtLeast(0))
                put("payload", payload)
            }
            val target = fileFor(key)
            val tmp = File(dir, target.name + TMP_SUFFIX)
            tmp.writeText(env.toString(), StandardCharsets.UTF_8)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setLastModified(now)
            evict(protect = target)
            true
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    fun get(key: String, allowExpired: Boolean = false): CacheEntry? = synchronized(lock) {
        val f = fileFor(key)
        if (!f.isFile) return null
        val entry = readEntry(f)
        if (entry == null || entry.key != key) {
            if (entry == null) f.delete()
            return null
        }
        if (!allowExpired && entry.isExpired(clock.nowMs())) null else entry
    }

    fun remove(key: String) {
        synchronized(lock) { fileFor(key).delete() }
    }

    fun clear() {
        synchronized(lock) { dir.listFiles()?.forEach { it.delete() } }
    }

    /** Total bytes of cache files (for tests and diagnostics). */
    fun sizeBytes(): Long = synchronized(lock) { entryFiles().sumOf { it.length() } }

    fun count(): Int = synchronized(lock) { entryFiles().size }

    private fun entryFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()

    private fun evict(protect: File) {
        var files = entryFiles()
        var total = files.sumOf { it.length() }
        if (total <= maxTotalBytes) return
        files = files.filter { it != protect }.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        for (f in files) {
            if (total <= maxTotalBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
        // A single entry larger than the cap is not worth keeping.
        if (total > maxTotalBytes) protect.delete()
    }

    private fun readEntry(f: File): CacheEntry? = try {
        val o: JsonObject? = Js.parse(f.readText(StandardCharsets.UTF_8)).asObj()
        val payload = (o?.get("payload") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val key = o?.str("key")
        val saved = o?.long("savedAt")
        val expires = o?.long("expiresAt")
        if (o == null || payload == null || key == null || saved == null || expires == null) null
        else CacheEntry(key, payload, saved, expires)
    } catch (e: IOException) {
        null
    }

    private fun fileFor(key: String): File = File(dir, sha(key) + ".json")

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(40)

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}
