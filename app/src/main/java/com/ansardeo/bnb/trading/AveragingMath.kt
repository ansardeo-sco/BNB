package com.ansardeo.bnb.trading

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** State of one Averaging order, as shown in the cycle summary and kept per slot. */
enum class AveragingOrderState { NOT_SUBMITTED, NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED }

/**
 * Two-order, Initial-Entry-based Averaging (pure Kotlin, BigDecimal, no Android).
 *
 *  - Total Averaging Margin = the actual Initial Entry Margin of the cycle (a separate allocation of the same size).
 *  - Order 1 / Order 2 each get an allocation (default 50 % / 50 %) and a Position-ROI trigger (default -25 % / -50 %).
 *  - BOTH LIMIT prices are measured from the Initial Entry FILL price (never from the WAP): price = RoiMath.priceAtRoi(side, initialFill, L, trigger).
 *  - Both orders are submitted together when the cycle's Position ROI first reaches the Order 1 trigger.
 */
object AveragingMath {
    private val MC = MathContext(28, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal(100)

    const val DEFAULT_ORDER1_ALLOCATION = 50.0
    const val DEFAULT_ORDER1_TRIGGER_ROI = -25.0
    const val DEFAULT_ORDER2_ALLOCATION = 50.0
    const val DEFAULT_ORDER2_TRIGGER_ROI = -50.0

    data class Config(
        val order1AllocationPct: Double = DEFAULT_ORDER1_ALLOCATION,
        val order1TriggerRoi: Double = DEFAULT_ORDER1_TRIGGER_ROI,
        val order2AllocationPct: Double = DEFAULT_ORDER2_ALLOCATION,
        val order2TriggerRoi: Double = DEFAULT_ORDER2_TRIGGER_ROI,
    )

    /** One planned Averaging LIMIT order (raw values; the exchange tick/step rules are applied when the order is placed). */
    data class Order(val slot: Int, val triggerRoi: BigDecimal, val allocationPct: BigDecimal, val margin: BigDecimal, val rawPrice: BigDecimal)

    /** The actual margin the Initial Entry used: filled quantity x fill price / leverage. */
    fun initialMargin(executedQty: BigDecimal, fillPrice: BigDecimal, leverage: Int): BigDecimal {
        require(leverage > 0) { "Leverage must be greater than 0" }
        return executedQty.multiply(fillPrice, MC).divide(BigDecimal(leverage), 12, RoundingMode.HALF_UP)
    }

    /** Total Averaging Margin: always equal to the Initial Entry Margin. */
    fun totalMargin(initialMargin: BigDecimal): BigDecimal = initialMargin

    fun orderMargin(initialMargin: BigDecimal, allocationPct: Double): BigDecimal =
        totalMargin(initialMargin).multiply(BigDecimal.valueOf(allocationPct), MC).divide(HUNDRED, 12, RoundingMode.HALF_UP)

    /** Both planned orders. Prices come from [initialFill] ONLY. */
    fun plan(side: TrailSide, initialFill: BigDecimal, leverage: Int, initialMargin: BigDecimal, cfg: Config): List<Order> = listOf(
        order(1, side, initialFill, leverage, initialMargin, cfg.order1AllocationPct, cfg.order1TriggerRoi),
        order(2, side, initialFill, leverage, initialMargin, cfg.order2AllocationPct, cfg.order2TriggerRoi),
    )

    private fun order(slot: Int, side: TrailSide, fill: BigDecimal, lev: Int, initMargin: BigDecimal, pct: Double, trigger: Double): Order {
        val t = BigDecimal.valueOf(RoiMath.normalizeLossTrigger(trigger))
        return Order(slot, t, BigDecimal.valueOf(pct), orderMargin(initMargin, pct), RoiMath.priceAtRoi(side, fill, lev, t))
    }

    /** @return null when valid, otherwise a readable reason. [limitAbs] = [RoiMath.triggerLimit]. */
    fun validate(cfg: Config, limitAbs: Double): String? {
        if (!cfg.order1AllocationPct.isFinite() || cfg.order1AllocationPct <= 0.0) return "Averaging Order 1 allocation must be greater than 0%"
        if (!cfg.order2AllocationPct.isFinite() || cfg.order2AllocationPct <= 0.0) return "Averaging Order 2 allocation must be greater than 0%"
        if (Math.abs(cfg.order1AllocationPct + cfg.order2AllocationPct - 100.0) > 1e-6) return "Averaging allocations must add up to 100% (Order 1 + Order 2)"
        if (!RoiMath.isValidLossTrigger(cfg.order1TriggerRoi, limitAbs)) return "Averaging Order 1 Trigger ROI must be between -${limitAbs.toInt()}% and 0% (the Stop Loss is at -${limitAbs.toInt()}%)"
        if (!RoiMath.isValidLossTrigger(cfg.order2TriggerRoi, limitAbs)) return "Averaging Order 2 Trigger ROI must be between -${limitAbs.toInt()}% and 0% (the Stop Loss is at -${limitAbs.toInt()}%)"
        if (cfg.order1TriggerRoi <= cfg.order2TriggerRoi) return "Averaging Order 1 Trigger ROI must be closer to 0% than Order 2 (e.g. -25% then -50%)"
        return null
    }

    /** The closest valid configuration to [cfg]: used by the forgiving settings loader so stored garbage never breaks the app. */
    fun sanitize(cfg: Config, limitAbs: Double): Config {
        if (validate(cfg, limitAbs) == null) return cfg.copy(
            order1TriggerRoi = RoiMath.normalizeLossTrigger(cfg.order1TriggerRoi), order2TriggerRoi = RoiMath.normalizeLossTrigger(cfg.order2TriggerRoi))
        val n = cfg.copy(order1TriggerRoi = if (cfg.order1TriggerRoi.isFinite()) RoiMath.normalizeLossTrigger(cfg.order1TriggerRoi) else DEFAULT_ORDER1_TRIGGER_ROI,
            order2TriggerRoi = if (cfg.order2TriggerRoi.isFinite()) RoiMath.normalizeLossTrigger(cfg.order2TriggerRoi) else DEFAULT_ORDER2_TRIGGER_ROI)
        if (validate(n, limitAbs) == null) return n
        val d = Config()
        if (validate(d, limitAbs) == null) return d
        return Config(DEFAULT_ORDER1_ALLOCATION, -limitAbs / 2.0, DEFAULT_ORDER2_ALLOCATION, -limitAbs)
    }
}
