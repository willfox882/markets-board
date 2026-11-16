package com.marketsboard.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Defensive accessors over the kotlinx.serialization JsonElement tree. Wrong types and nulls become null instead of
 * exceptions, which is what parsing third-party JSON needs. No @Serializable classes are used on purpose.
 */
internal object Js {
    fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }
}

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive).asNum()

internal fun JsonElement?.asNum(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.doubleOrNull
}

/** Epoch seconds (or ms-free integer) as a Long, null when missing or not finite. */
internal fun JsonObject.long(key: String): Long? = num(key)?.takeIf { it.isFinite() && it >= 0 && it < 4e12 }?.toLong()
