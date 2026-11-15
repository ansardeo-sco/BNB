package com.ansardeo.bnb.trading

import java.math.BigDecimal

/**
 * Opposite-Side reversal trigger, expressed in Position ROI exactly like the cycle Trailing Stop.
 *
 * - Activation: the SOURCE position's Position ROI reaches -|triggerRoi| (default -50), leverage included:
 *   price = W * (1 -/+ triggerRoi / (100 * L))  (LONG source: below W, SHORT source: above W).
 * - Callback: after activation the trigger trails the adverse extreme (LONG source: lowest price, SHORT source: highest price) and
 *   fires when the price reverses by the callback ROI (default 5), leverage included, NOT a raw 5% price move:
 *   LONG source : trigger = Lo * (1 + callbackRoi / (100 * L)),  fires when price >= trigger
 *   SHORT source: trigger = Hi * (1 - callbackRoi / (100 * L)),  fires when price <= trigger
 *
 * It reuses [TrailingStopMath] with the OPPOSITE side, so there is one verified formula for every ROI-based trigger.
 * Pure Kotlin/JVM (unit tested).
 */
object OppositeTrigger {
    fun oppositeSide(source: TrailSide): TrailSide = if (source == TrailSide.LONG) TrailSide.SHORT else TrailSide.LONG

    /** [triggerRoi] may be given negative (-50) or positive (50); only its magnitude is used. */
    fun activationPrice(source: TrailSide, wap: BigDecimal, leverage: Int, triggerRoi: BigDecimal): BigDecimal =
        TrailingStopMath.activationPrice(oppositeSide(source), wap, leverage, triggerRoi.abs())

    fun step(
        source: TrailSide,
        wap: BigDecimal,
        leverage: Int,
        triggerRoi: BigDecimal,
        callbackRoi: BigDecimal,
        previous: TrailingState,
        mark: BigDecimal,
    ): TrailingStep = TrailingStopMath.step(oppositeSide(source), wap, leverage, TrailingConfig(triggerRoi.abs(), callbackRoi), previous, mark)
}
