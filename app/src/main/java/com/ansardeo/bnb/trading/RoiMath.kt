package com.ansardeo.bnb.trading

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The ONE Position-ROI calculation used by Trailing Stop, Re-entry, Averaging and Opposite-Side.
 *
 * Position ROI is measured on the margin of the position (leverage included), NOT on the raw price move
 * and NOT on total account equity:
 *
 *   LONG : ROI% = (P - W) / W * L * 100        =>  P = W * (1 + ROI / (100 * L))
 *   SHORT: ROI% = (W - P) / W * L * 100        =>  P = W * (1 - ROI / (100 * L))
 *
 * W = entry / cycle Weighted Average Entry Price, L = leverage, P = price.
 * Negative ROI = loss. Pure Kotlin/JVM (no Android dependency), BigDecimal only.
 */
object RoiMath {
    private val MC = MathContext(28, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal(100)
    private val TWO = BigDecimal(2)

    /** Fixed by the leverage table (entry SL = 69 / L price percent) = -69% Position ROI at every leverage. */
    val SL_ROI: BigDecimal = BigDecimal(69)

    private fun fraction(roi: BigDecimal, leverage: Int): BigDecimal {
        require(leverage > 0) { "Leverage must be greater than 0" }
        return roi.divide(HUNDRED.multiply(BigDecimal(leverage)), MC)
    }

    /** Current Position ROI in percent for a price [price] against entry [entry]. */
    fun positionRoi(side: TrailSide, entry: BigDecimal, price: BigDecimal, leverage: Int): BigDecimal {
        require(entry.signum() > 0) { "Entry must be greater than 0" }
        require(leverage > 0) { "Leverage must be greater than 0" }
        val move = price.subtract(entry).divide(entry, MC)
        val signed = if (side == TrailSide.LONG) move else move.negate()
        return signed.multiply(BigDecimal(leverage)).multiply(HUNDRED, MC)
    }

    /** The price at which a position opened at [entry] reaches Position ROI [roiPercent] (negative = loss). */
    fun priceAtRoi(side: TrailSide, entry: BigDecimal, leverage: Int, roiPercent: BigDecimal): BigDecimal {
        require(entry.signum() > 0) { "Entry must be greater than 0" }
        val f = fraction(roiPercent, leverage)
        return when (side) {
            TrailSide.LONG -> entry.multiply(BigDecimal.ONE.add(f), MC)
            TrailSide.SHORT -> entry.multiply(BigDecimal.ONE.subtract(f), MC)
        }
    }

    /** Re-entry ROI = Trailing Activation ROI / 2 (calculated automatically, never entered by hand). */
    fun reentryRoi(activationRoi: BigDecimal): BigDecimal {
        require(activationRoi.signum() > 0) { "Activation ROI must be greater than 0" }
        return activationRoi.divide(TWO, MC)
    }

    /**
     * Re-entry LIMIT price after a profitable cycle closed at [closePrice]:
     * LONG entry BELOW the close, SHORT entry ABOVE the close, each at [reentryRoi] Position ROI distance.
     */
    fun reentryPrice(newSide: TrailSide, closePrice: BigDecimal, leverage: Int, reentryRoi: BigDecimal): BigDecimal {
        require(closePrice.signum() > 0) { "Close price must be greater than 0" }
        val f = fraction(reentryRoi, leverage)
        return when (newSide) {
            TrailSide.LONG -> closePrice.multiply(BigDecimal.ONE.subtract(f), MC)
            TrailSide.SHORT -> closePrice.multiply(BigDecimal.ONE.add(f), MC)
        }
    }

    /** Normalises user input: a loss trigger is always stored negative (30 and -30 both become -30). */
    fun normalizeLossTrigger(input: Double): Double {
        require(input.isFinite()) { "Trigger ROI must be a number" }
        return if (input == 0.0) 0.0 else -kotlin.math.abs(input)
    }

    /** A loss trigger must be below 0 and not beyond the fixed entry SL ROI (-69%), otherwise it could never fire. */
    fun isValidLossTrigger(roi: Double): Boolean = roi.isFinite() && roi < 0.0 && roi >= -SL_ROI.toDouble()
}
