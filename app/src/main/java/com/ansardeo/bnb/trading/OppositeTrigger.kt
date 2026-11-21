package com.ansardeo.bnb.trading

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Opposite-Side trailing order maths. The order itself is a REAL Binance TRAILING_STOP_MARKET conditional order placed when the initial entry fills;
 * the app only computes its parameters here.
 *
 * - Activation price: the price at which the SOURCE position reaches +activationRoi Position ROI (default +40), leverage included:
 *   LONG source: W * (1 + A / (100 L)), SHORT source: W * (1 - A / (100 L)). (The caller rounds it to the tick size.)
 * - Callback: Binance takes a PRICE percent, so callback ROI is converted: price% = callbackRoi / leverage, rounded to 0.1 (Binance step), never below 0.1.
 * - Quantity: fixed at placement = oppositeMargin * leverage / activation price (the price it is expected to open at).
 * - Side: the OPPOSITE of the source (LONG source opens SELL + positionSide SHORT; SHORT source opens BUY + positionSide LONG).
 * Pure Kotlin/JVM (unit tested).
 */
object OppositeTrigger {
    private val MC = MathContext(28, RoundingMode.HALF_EVEN)
    val MIN_CALLBACK_PCT: BigDecimal = BigDecimal("0.1")
    val MAX_CALLBACK_PCT: BigDecimal = BigDecimal("10.0")

    fun oppositeSide(source: TrailSide): TrailSide = if (source == TrailSide.LONG) TrailSide.SHORT else TrailSide.LONG

    /** Unrounded activation price from the source position's fill/WAP. */
    fun activationPrice(source: TrailSide, wap: BigDecimal, leverage: Int, activationRoi: BigDecimal): BigDecimal =
        TrailingStopMath.activationPrice(source, wap, leverage, activationRoi.abs())

    /** Callback ROI -> Binance price percent (1 decimal). Null when the result is outside Binance's accepted range. */
    fun callbackPricePercent(callbackRoi: BigDecimal, leverage: Int): BigDecimal? {
        require(leverage > 0) { "Leverage must be greater than 0" }
        val raw = callbackRoi.abs().divide(BigDecimal(leverage), MC).setScale(1, RoundingMode.HALF_UP)
        val v = raw.max(MIN_CALLBACK_PCT)
        return if (v > MAX_CALLBACK_PCT) null else v
    }

    /** Quantity (unrounded) the opposite order will open: margin * leverage / activation price. */
    fun quantity(margin: BigDecimal, leverage: Int, activation: BigDecimal): BigDecimal {
        require(activation.signum() > 0) { "Activation price must be greater than 0" }
        return margin.multiply(BigDecimal(leverage)).divide(activation, 12, RoundingMode.DOWN)
    }

    /** The Binance (side, positionSide) of the opening order for a source direction. */
    fun orderSides(sourceDirection: String): Pair<String, String> = if (sourceDirection == "LONG") "SELL" to "SHORT" else "BUY" to "LONG"
}
