package com.ansardeo.bnb.demo.exec

import com.ansardeo.bnb.trading.TrailingState
import org.json.JSONObject
import java.math.BigDecimal

enum class CycleStatus { ACTIVE, CLOSING, CLOSED }

/**
 * One strategy cycle on Binance Demo: a first entry (Manual / Opposite-Side / Re-entry) plus its single averaging entry, all in one direction.
 * The cycle owns the app-side ROI Trailing Stop state (on the cycle's weighted average entry price) and the Opposite-Side trigger state.
 * Every entry of the cycle keeps its OWN exchange-side Stop Loss; there is no cycle-level Stop Loss and no fixed Take Profit.
 * Decimal values are kept as plain strings.
 */
data class DemoCycle(
    val id: String,
    val symbol: String,
    val direction: String,
    val leverage: Int,
    val kind: String,
    val status: CycleStatus = CycleStatus.ACTIVE,
    val trailActive: Boolean = false, val trailExtreme: String? = null, val trailStop: String? = null,
    val oppActive: Boolean = false, val oppExtreme: String? = null, val oppStop: String? = null,
    val avgDone: Boolean = false,
    val oppDone: Boolean = false,
    val reentryDone: Boolean = false,
    /** Set when the Trailing Stop fired: the mark price at that moment and the cycle's weighted average entry price at that moment. */
    val closePrice: String? = null,
    val closeWap: String? = null,
    val bookedRoi: String? = null,
    val note: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun trailState(): TrailingState = TrailingState(trailActive, trailExtreme?.let { BigDecimal(it) }, trailStop?.let { BigDecimal(it) })
    fun oppState(): TrailingState = TrailingState(oppActive, oppExtreme?.let { BigDecimal(it) }, oppStop?.let { BigDecimal(it) })
    fun withTrail(s: TrailingState) = copy(trailActive = s.active, trailExtreme = s.extreme?.toPlainString(), trailStop = s.stopPrice?.toPlainString())
    fun withOpp(s: TrailingState) = copy(oppActive = s.active, oppExtreme = s.extreme?.toPlainString(), oppStop = s.stopPrice?.toPlainString())

    fun toJson(): String = JSONObject().apply {
        put("id", id); put("symbol", symbol); put("direction", direction); put("leverage", leverage); put("kind", kind); put("status", status.name)
        put("trailActive", trailActive); trailExtreme?.let { put("trailExtreme", it) }; trailStop?.let { put("trailStop", it) }
        put("oppActive", oppActive); oppExtreme?.let { put("oppExtreme", it) }; oppStop?.let { put("oppStop", it) }
        put("avgDone", avgDone); put("oppDone", oppDone); put("reentryDone", reentryDone)
        closePrice?.let { put("closePrice", it) }; closeWap?.let { put("closeWap", it) }; bookedRoi?.let { put("bookedRoi", it) }
        put("note", note); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }.toString()

    companion object {
        fun fromJson(text: String): DemoCycle? = runCatching {
            val o = JSONObject(text)
            fun s(k: String): String? = if (o.has(k)) o.getString(k) else null
            DemoCycle(
                id = o.getString("id"), symbol = o.getString("symbol"), direction = o.getString("direction"), leverage = o.getInt("leverage"), kind = o.optString("kind", "MANUAL"),
                status = CycleStatus.valueOf(o.getString("status")),
                trailActive = o.optBoolean("trailActive"), trailExtreme = s("trailExtreme"), trailStop = s("trailStop"),
                oppActive = o.optBoolean("oppActive"), oppExtreme = s("oppExtreme"), oppStop = s("oppStop"),
                avgDone = o.optBoolean("avgDone"), oppDone = o.optBoolean("oppDone"), reentryDone = o.optBoolean("reentryDone"),
                closePrice = s("closePrice"), closeWap = s("closeWap"), bookedRoi = s("bookedRoi"),
                note = o.optString("note", ""), createdAt = o.optLong("createdAt", 0L), updatedAt = o.optLong("updatedAt", 0L),
            )
        }.getOrNull()
    }
}
