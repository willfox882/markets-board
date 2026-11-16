package com.marketsboard.data.store

import com.marketsboard.data.model.ChartRange
import com.marketsboard.data.model.FetchResult
import com.marketsboard.data.model.Quote
import com.marketsboard.data.model.QuoteValidator
import com.marketsboard.data.model.SeriesPoint
import com.marketsboard.data.provider.Js
import com.marketsboard.data.provider.arr
import com.marketsboard.data.provider.asNum
import com.marketsboard.data.provider.asObj
import com.marketsboard.data.provider.long
import com.marketsboard.data.provider.num
import com.marketsboard.data.provider.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON form of a [Quote] for the file cache. Decoding is defensive and re-validates, so a bad file is just a miss. */
object QuoteCodec {
    private const val VERSION = 1

    fun encode(q: Quote): String = buildJsonObject {
        put("v", VERSION)
        put("symbol", q.symbol)
        put("name", q.name)
        put("currency", q.currency)
        put("exchange", q.exchange)
        put("price", q.price)
        put("prevClose", q.prevClose?.let { JsonPrimitive(it) } ?: JsonNull)
        put("dayHigh", q.dayHigh?.let { JsonPrimitive(it) } ?: JsonNull)
        put("dayLow", q.dayLow?.let { JsonPrimitive(it) } ?: JsonNull)
        put("marketState", q.marketState?.let { JsonPrimitive(it) } ?: JsonNull)
        put("asOf", q.asOfEpochMs)
        put("range", q.range.name)
        put("type", q.instrumentType?.let { JsonPrimitive(it) } ?: JsonNull)
        put("series", JsonArray(q.series.map { JsonArray(listOf(JsonPrimitive(it.tMs), JsonPrimitive(it.close))) }))
    }.toString()

    fun decode(text: String): Quote? {
        val o = Js.parse(text).asObj() ?: return null
        if (o.num("v")?.toInt() != VERSION) return null
        val range = o.str("range")?.let { n -> ChartRange.entries.firstOrNull { it.name == n } } ?: return null
        val series = o.arr("series")?.mapNotNull { p ->
            val a = (p as? JsonArray) ?: return@mapNotNull null
            val t = a.getOrNull(0).asNum()?.toLong() ?: return@mapNotNull null
            val c = a.getOrNull(1).asNum() ?: return@mapNotNull null
            SeriesPoint(t, c)
        }.orEmpty()
        val raw = Quote(
            symbol = o.str("symbol") ?: return null,
            name = o.str("name").orEmpty(),
            currency = o.str("currency").orEmpty(),
            exchange = o.str("exchange").orEmpty(),
            price = o.num("price") ?: return null,
            prevClose = o.num("prevClose"),
            dayHigh = o.num("dayHigh"),
            dayLow = o.num("dayLow"),
            marketState = o.str("marketState"),
            asOfEpochMs = o.long("asOf") ?: return null,
            series = series,
            range = range,
            instrumentType = o.str("type"),
        )
        return (QuoteValidator.validate(raw) as? FetchResult.Ok)?.value
    }
}
