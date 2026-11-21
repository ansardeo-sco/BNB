package com.ansardeo.bnb.demo.exec

import com.ansardeo.bnb.trading.AveragingOrderState
import org.json.JSONObject
import java.math.BigDecimal

enum class EntryStatus { SENDING, UNKNOWN, NEW, PARTIALLY_FILLED, FILLED, CANCELED, EXPIRED, REJECTED, FAILSAFE_CLOSED, UNPROTECTED, CLOSED }

/**
 * One Demo entry order and its own independent exchange-side Stop Loss.
 * Decimal numbers are kept as plain strings exactly as the exchange formats them. [slState]: NONE, VERIFIED (confirmed active by Binance Demo),
 * DONE (stop triggered / finished) or LOST (the stop is no longer active).
 */
data class DemoEntry(
    val id: String,
    val symbol: String,
    val direction: String,
    val leverage: Int,
    val slRoi: Double,
    val limitPrice: String,
    val quantity: String,
    val orderId: Long? = null,
    val status: EntryStatus = EntryStatus.SENDING,
    val executedQty: String = "0",
    val avgPrice: String? = null,
    val protectedQty: String = "0",
    val closedQty: String = "0",
    val slAlgoId: Long? = null,
    val slTrigger: String? = null,
    val slState: String = "NONE",
    val note: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Phase 3B: the strategy cycle this entry belongs to ("" = a plain manual entry without automation) and its entry kind (EntryKind name). */
    val cycleId: String = "",
    val kind: String = "MANUAL",
    /** Individual Stop Loss switch captured at placement. OFF = no app-generated Stop Loss (the position is left to Binance liquidation rules). */
    val slOn: Boolean = true,
    /** Averaging order slot: 0 = not an Averaging order, 1 = Averaging Order 1, 2 = Averaging Order 2. */
    val avgSlot: Int = 0,
) {
    val executed: BigDecimal get() = bd(executedQty)
    val protectedQuantity: BigDecimal get() = bd(protectedQty)
    val closedQuantity: BigDecimal get() = bd(closedQty)
    /** Executed but not yet closed (what a CLOSE POSITION would still send to the exchange). */
    val openQuantity: BigDecimal get() = (executed.subtract(closedQuantity)).max(BigDecimal.ZERO)
    val average: BigDecimal? get() = avgPrice?.let { bd(it) }?.takeIf { it.signum() > 0 }
    val closeSide: String get() = if (direction == "LONG") "SELL" else "BUY"
    val openSide: String get() = if (direction == "LONG") "BUY" else "SELL"
    /** The exchange order can still fill. */
    val orderActive: Boolean get() = status == EntryStatus.NEW || status == EntryStatus.PARTIALLY_FILLED
    /** Needs the app's attention (sending/unknown/active order, or filled quantity without a verified Stop Loss). */
    val needsAttention: Boolean get() = when (status) {
        EntryStatus.SENDING, EntryStatus.UNKNOWN, EntryStatus.NEW, EntryStatus.PARTIALLY_FILLED -> true
        EntryStatus.FILLED, EntryStatus.CANCELED, EntryStatus.EXPIRED -> needsProtection
        else -> false
    }
    /** A Stop Loss is wanted for the filled-but-unprotected quantity (never when the entry was placed with Stop Loss OFF). */
    val needsProtection: Boolean get() = slOn && executed > protectedQuantity && slState != "LOST"
    /** Can still be closed on the exchange: something was filled and not fully closed yet. */
    val closable: Boolean get() = (status == EntryStatus.FILLED || status == EntryStatus.CANCELED || status == EntryStatus.EXPIRED) && openQuantity.signum() > 0
    val hasOpenProtection: Boolean get() = slState == "VERIFIED"

    fun toJson(): String = JSONObject().apply {
        put("id", id); put("symbol", symbol); put("direction", direction); put("leverage", leverage); put("slRoi", slRoi)
        put("limitPrice", limitPrice); put("quantity", quantity); orderId?.let { put("orderId", it) }; put("status", status.name)
        put("executedQty", executedQty); avgPrice?.let { put("avgPrice", it) }; put("protectedQty", protectedQty); put("closedQty", closedQty)
        slAlgoId?.let { put("slAlgoId", it) }; slTrigger?.let { put("slTrigger", it) }; put("slState", slState)
        put("note", note); put("createdAt", createdAt); put("updatedAt", updatedAt); put("cycleId", cycleId); put("kind", kind); put("slOn", slOn); put("avgSlot", avgSlot)
    }.toString()

    companion object {
        fun bd(s: String?): BigDecimal = runCatching { BigDecimal(s ?: "0") }.getOrDefault(BigDecimal.ZERO)
        fun fromJson(text: String): DemoEntry? = runCatching {
            val o = JSONObject(text)
            DemoEntry(
                id = o.getString("id"), symbol = o.getString("symbol"), direction = o.getString("direction"), leverage = o.getInt("leverage"), slRoi = o.getDouble("slRoi"),
                limitPrice = o.getString("limitPrice"), quantity = o.getString("quantity"), orderId = if (o.has("orderId")) o.getLong("orderId") else null,
                status = EntryStatus.valueOf(o.getString("status")), executedQty = o.optString("executedQty", "0"), avgPrice = if (o.has("avgPrice")) o.getString("avgPrice") else null,
                protectedQty = o.optString("protectedQty", "0"), closedQty = o.optString("closedQty", "0"), slAlgoId = if (o.has("slAlgoId")) o.getLong("slAlgoId") else null, slTrigger = if (o.has("slTrigger")) o.getString("slTrigger") else null,
                slState = o.optString("slState", "NONE"), note = o.optString("note", ""), createdAt = o.optLong("createdAt", 0L), updatedAt = o.optLong("updatedAt", 0L),
                cycleId = o.optString("cycleId", ""), kind = o.optString("kind", "MANUAL"), slOn = o.optBoolean("slOn", true), avgSlot = o.optInt("avgSlot", 0),
            )
        }.getOrNull()
    }
}

/** Execution state of one Averaging order slot, derived ONLY from the stored exchange status of its entry (null entry = never submitted). */
fun averagingStateOf(e: DemoEntry?): AveragingOrderState {
    if (e == null) return AveragingOrderState.NOT_SUBMITTED
    return when (e.status) {
        EntryStatus.SENDING, EntryStatus.UNKNOWN, EntryStatus.NEW -> AveragingOrderState.NEW
        EntryStatus.PARTIALLY_FILLED -> AveragingOrderState.PARTIALLY_FILLED
        EntryStatus.FILLED, EntryStatus.CLOSED, EntryStatus.FAILSAFE_CLOSED, EntryStatus.UNPROTECTED -> AveragingOrderState.FILLED
        EntryStatus.CANCELED -> AveragingOrderState.CANCELED
        EntryStatus.REJECTED -> AveragingOrderState.REJECTED
        EntryStatus.EXPIRED -> AveragingOrderState.EXPIRED
    }
}
