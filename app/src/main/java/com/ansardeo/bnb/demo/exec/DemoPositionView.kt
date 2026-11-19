package com.ansardeo.bnb.demo.exec

import com.ansardeo.bnb.trading.RoiMath
import com.ansardeo.bnb.trading.TrailSide
import org.json.JSONArray
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Open positions exactly as Binance Demo reports them (positionRisk), with PNL / ROI computed from the MARK price.
 * Pure Kotlin (no network, no Android): unit tested. [entryPrice] is Binance's own entry price for the hedge-mode side, i.e. the weighted
 * average entry price (WAP) of everything that is open on that side.
 */
data class PositionRow(
    val symbol: String,
    val direction: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val markPrice: BigDecimal,
    val leverage: Int,
    val liquidationPrice: BigDecimal?,
    val marginType: String,
    val binanceUnrealized: BigDecimal?,
) {
    private val mc = MathContext(28, RoundingMode.HALF_EVEN)
    val side: TrailSide get() = TrailSide.fromDirection(direction)
    val positionAmount: BigDecimal get() = quantity.multiply(markPrice, mc)
    /** Initial margin of the position: notional at entry / leverage. */
    val margin: BigDecimal get() = if (leverage > 0) quantity.multiply(entryPrice, mc).divide(BigDecimal(leverage), mc) else BigDecimal.ZERO
    /** Unrealized PNL from the MARK price (LONG: (mark - entry) x qty, SHORT: (entry - mark) x qty). */
    val unrealizedPnl: BigDecimal get() = (if (side == TrailSide.LONG) markPrice.subtract(entryPrice) else entryPrice.subtract(markPrice)).multiply(quantity, mc)
    /** Position ROI in percent, leverage included (the strategy's ROI, NOT the raw price change). */
    val roiPercent: BigDecimal get() = if (entryPrice.signum() > 0 && leverage > 0) RoiMath.positionRoi(side, entryPrice, markPrice, leverage) else BigDecimal.ZERO
}

object DemoPositionView {
    private fun bd(s: String?): BigDecimal? = runCatching { BigDecimal((s ?: "").trim()) }.getOrNull()

    /** Every non-empty LONG/SHORT position of a positionRisk answer. Returns null when the body is unreadable (so callers never treat it as "no positions"). */
    fun parse(body: String): List<PositionRow>? {
        val a = runCatching { JSONArray(body.trim()) }.getOrNull() ?: return null
        val out = ArrayList<PositionRow>()
        val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val dir = o.optString("positionSide")
            if (dir != "LONG" && dir != "SHORT") continue
            val amt = bd(o.optString("positionAmt"))?.abs() ?: continue
            if (amt.signum() == 0) continue
            val symbol = o.optString("symbol"); if (symbol.isBlank() || !seen.add("$symbol|$dir")) continue // never double-count a side
            val entry = bd(o.optString("entryPrice")) ?: continue
            val mark = bd(o.optString("markPrice")) ?: continue
            if (entry.signum() <= 0 || mark.signum() <= 0) continue
            val lev = o.optInt("leverage", 0)
            out += PositionRow(symbol, dir, amt, entry, mark, lev, bd(o.optString("liquidationPrice"))?.takeIf { it.signum() > 0 }, o.optString("marginType"), bd(o.optString("unRealizedProfit")))
        }
        return out
    }
}

/** Visual/state of the Trailing Activation Price in an Open Position card. */
enum class TrailingActivation { WAITING, ACTIVATED, UNKNOWN }

object TrailingActivationStatus {
    /**
     * ACTIVATED only on evidence: the strategy recorded the activation ([persistedActive], survives an app restart), Binance moved the trailing
     * order past NEW ([algoStatus] TRIGGERING/TRIGGERED/FINISHED), or the MARK price is at/through the activation price right now.
     * WAITING only when the mark price was read and is still short of the activation price. Otherwise UNKNOWN (never a false green).
     * Being "near" the activation price changes nothing.
     */
    fun of(side: TrailSide, activation: BigDecimal?, mark: BigDecimal?, persistedActive: Boolean, algoStatus: String?): TrailingActivation {
        if (persistedActive) return TrailingActivation.ACTIVATED
        if (algoStatus == "TRIGGERING" || algoStatus == "TRIGGERED" || algoStatus == "FINISHED") return TrailingActivation.ACTIVATED
        if (activation == null || mark == null) return TrailingActivation.UNKNOWN
        val reached = if (side == TrailSide.LONG) mark >= activation else mark <= activation
        return if (reached) TrailingActivation.ACTIVATED else TrailingActivation.WAITING
    }
}
