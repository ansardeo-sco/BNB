package com.ansardeo.bnb.demo.exec

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

enum class TrailingCardKind { POSITION_CLOSE, POSITION_OPEN }

/**
 * One Trailing Stop card, built ONLY from a Binance openAlgoOrders row (plus Binance positionRisk for ROI/leverage context).
 * POSITION_CLOSE = LONG+SELL or SHORT+BUY (closes the current position); POSITION_OPEN = LONG+BUY or SHORT+SELL (opens an opposite position).
 * Nothing here is invented by the app: unknown values stay null and are shown as "—".
 */
data class TrailingCard(
    val kind: TrailingCardKind, val algoId: Long, val symbol: String, val direction: String, val side: String, val status: String,
    val activationPrice: BigDecimal?, val activationRoi: BigDecimal?, val callbackRoi: BigDecimal?, val callbackPricePct: BigDecimal?,
    val quantity: String, val leverage: Int?,
    /** Binance does not expose the running trigger price: null before activation, [triggerCalculated] = true after activation (shown as "calculated"). */
    val triggerCalculated: Boolean,
    /** OPEN cards only: quantity x activation price / leverage (an estimate; the real margin is taken when Binance triggers the order). */
    val orderMarginApprox: BigDecimal?,
) {
    val title: String get() = if (kind == TrailingCardKind.POSITION_CLOSE) "POSITION CLOSE" else "POSITION OPEN"
    val description: String get() = if (kind == TrailingCardKind.POSITION_CLOSE) "This trailing stop will close the current position" else "This trailing stop will open an opposite position"
}

object DemoTrailingCards {
    private val MC = MathContext(20, RoundingMode.HALF_UP)
    private fun bd(s: String?): BigDecimal? = runCatching { BigDecimal((s ?: "").trim()) }.getOrNull()

    fun build(trailing: List<AlgoRow>, positions: List<PositionRow>): List<TrailingCard> = trailing.mapNotNull { a ->
        val kind = when { a.closesPosition -> TrailingCardKind.POSITION_CLOSE; a.opensPosition -> TrailingCardKind.POSITION_OPEN; else -> return@mapNotNull null }
        val act = bd(a.activationPrice)
        val cbPct = bd(a.callbackRate)
        val sameSym = positions.filter { it.symbol == a.symbol }
        val lev = sameSym.firstOrNull { it.direction == a.direction }?.leverage?.takeIf { it > 0 } ?: sameSym.firstOrNull { it.leverage > 0 }?.leverage
        // The position whose ROI the activation refers to: the one this order closes, or (for an opening order) the opposite-side source position.
        val sourceDir = if (kind == TrailingCardKind.POSITION_CLOSE) a.direction else if (a.direction == "LONG") "SHORT" else "LONG"
        val src = sameSym.firstOrNull { it.direction == sourceDir && it.entryPrice.signum() > 0 }
        val actRoi = if (act != null && src != null && lev != null) {
            val move = act.subtract(src.entryPrice).divide(src.entryPrice, MC)
            val sgn = if (sourceDir == "LONG") BigDecimal.ONE else BigDecimal.ONE.negate()
            move.multiply(sgn).multiply(BigDecimal(lev)).multiply(BigDecimal(100))
        } else null
        val cbRoi = if (cbPct != null && lev != null) cbPct.multiply(BigDecimal(lev)) else null
        val mark = sameSym.firstOrNull()?.markPrice
        val reached = if (act != null && mark != null) (if (a.side == "SELL") mark >= act else mark <= act) else false
        val margin = if (kind == TrailingCardKind.POSITION_OPEN && act != null && lev != null) bd(a.quantity)?.multiply(act)?.divide(BigDecimal(lev), MC) else null
        TrailingCard(kind, a.algoId, a.symbol, a.direction, a.side, a.status, act, actRoi, cbRoi, cbPct, a.quantity, lev, reached, margin)
    }
}
