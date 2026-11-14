package com.ansardeo.bnb.trading

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * ROI-based cycle Trailing Stop (the ONLY profit-exit mechanism; there is no fixed Take Profit).
 *
 * Mandatory formulas (BNB Master Implementation Specification, section 5):
 *   W  = cycle Weighted Average Entry Price
 *   L  = leverage
 *   A  = Activation ROI percentage
 *   C  = Callback ROI percentage
 *   H  = highest price reached after activation (LONG)
 *   Lo = lowest price reached after activation (SHORT)
 *
 *   LONG : activation = W  * (1 + A / (100 * L)),  stop = H  * (1 - C / (100 * L))
 *   SHORT: activation = W  * (1 - A / (100 * L)),  stop = Lo * (1 + C / (100 * L))
 *
 * A and C are ROI percentages, not raw price-move percentages.
 * All maths uses BigDecimal (no binary floating-point error). This file is pure Kotlin/JVM
 * (no Android dependency) so it is covered by plain unit tests.
 */
enum class TrailSide { LONG, SHORT;
    companion object {
        fun fromDirection(direction: String): TrailSide = when (direction.uppercase()) {
            "LONG" -> LONG
            "SHORT" -> SHORT
            else -> throw IllegalArgumentException("Direction must be LONG or SHORT, was '$direction'")
        }
    }
}

/** Trailing configuration (ROI percentages, e.g. 33 and 3). */
data class TrailingConfig(val activationRoi: BigDecimal, val callbackRoi: BigDecimal) {
    init {
        require(activationRoi.signum() > 0) { "Activation ROI must be greater than 0" }
        require(callbackRoi.signum() > 0) { "Callback ROI must be greater than 0" }
    }
}

/** Persisted trailing state of one cycle. */
data class TrailingState(
    val active: Boolean = false,
    /** Highest price (LONG) / lowest price (SHORT) observed since activation. */
    val extreme: BigDecimal? = null,
    /** Current trailing stop price. LONG: never decreases. SHORT: never increases. */
    val stopPrice: BigDecimal? = null,
)

data class TrailingStep(
    val state: TrailingState,
    val activationPrice: BigDecimal,
    val justActivated: Boolean,
    /** True when [mark] has crossed the trailing stop: close ALL remaining quantity of the cycle. */
    val exitTriggered: Boolean,
)

object TrailingStopMath {
    private val MC = MathContext(28, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal(100)

    /** Converts a Double coming from storage/UI into BigDecimal via its decimal text (no binary expansion). */
    fun bd(value: Double): BigDecimal {
        require(value.isFinite()) { "Value must be finite" }
        return BigDecimal(value.toString())
    }

    private fun roiFraction(roiPercent: BigDecimal, leverage: Int): BigDecimal {
        require(leverage > 0) { "Leverage must be greater than 0" }
        return roiPercent.divide(HUNDRED.multiply(BigDecimal(leverage)), MC)
    }

    fun activationPrice(side: TrailSide, wap: BigDecimal, leverage: Int, activationRoi: BigDecimal): BigDecimal {
        require(wap.signum() > 0) { "Weighted average entry must be greater than 0" }
        val f = roiFraction(activationRoi, leverage)
        return when (side) {
            TrailSide.LONG -> wap.multiply(BigDecimal.ONE.add(f), MC)
            TrailSide.SHORT -> wap.multiply(BigDecimal.ONE.subtract(f), MC)
        }
    }

    fun stopPrice(side: TrailSide, extreme: BigDecimal, leverage: Int, callbackRoi: BigDecimal): BigDecimal {
        val f = roiFraction(callbackRoi, leverage)
        return when (side) {
            TrailSide.LONG -> extreme.multiply(BigDecimal.ONE.subtract(f), MC)
            TrailSide.SHORT -> extreme.multiply(BigDecimal.ONE.add(f), MC)
        }
    }

    /**
     * Advances the trailing state with one observed price.
     *
     * - Before activation: waits until the price reaches the activation price (based on the CURRENT WAP).
     * - After activation: tracks the favourable extreme; the stop is monotonic
     *   (LONG non-decreasing, SHORT non-increasing) and stays active until the cycle closes.
     * - If WAP changes (averaging) the activation price is recalculated from the new WAP, but a trail
     *   that is already active is retained and its stop is never loosened.
     */
    fun step(
        side: TrailSide,
        wap: BigDecimal,
        leverage: Int,
        config: TrailingConfig,
        previous: TrailingState,
        mark: BigDecimal,
    ): TrailingStep {
        require(mark.signum() > 0) { "Price must be greater than 0" }
        val activation = activationPrice(side, wap, leverage, config.activationRoi)

        if (!previous.active || previous.extreme == null || previous.stopPrice == null) {
            val reached = if (side == TrailSide.LONG) mark >= activation else mark <= activation
            if (!reached) {
                return TrailingStep(TrailingState(), activation, justActivated = false, exitTriggered = false)
            }
            val stop = stopPrice(side, mark, leverage, config.callbackRoi)
            return TrailingStep(TrailingState(true, mark, stop), activation, justActivated = true, exitTriggered = false)
        }

        val newExtreme = if (side == TrailSide.LONG) previous.extreme.max(mark) else previous.extreme.min(mark)
        val candidate = stopPrice(side, newExtreme, leverage, config.callbackRoi)
        val newStop = if (side == TrailSide.LONG) previous.stopPrice.max(candidate) else previous.stopPrice.min(candidate)
        val exit = if (side == TrailSide.LONG) mark <= newStop else mark >= newStop
        return TrailingStep(TrailingState(true, newExtreme, newStop), activation, justActivated = false, exitTriggered = exit)
    }
}
