package com.ansardeo.bnb.demo.trade

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

data class SymbolRules(
    val symbol: String,
    val tickSize: BigDecimal,
    val stepSize: BigDecimal,
    val minQty: BigDecimal,
    val minNotional: BigDecimal,
    val percentUp: BigDecimal?,
    val percentDown: BigDecimal?,
    val status: String?,
)

/** Exchange filters (tick / step / min notional) and BigDecimal rounding. Pure Kotlin, unit tested. */
object ExchangeRules {
    fun parse(exchangeInfoBody: String, symbol: String): SymbolRules? = try {
        val syms = JSONObject(exchangeInfoBody).getJSONArray("symbols")
        var found: SymbolRules? = null
        for (i in 0 until syms.length()) {
            val s = syms.getJSONObject(i)
            if (s.optString("symbol") != symbol) continue
            var tick: BigDecimal? = null; var step: BigDecimal? = null; var minQty: BigDecimal? = null
            var minNotional = BigDecimal.ZERO; var up: BigDecimal? = null; var down: BigDecimal? = null
            val filters = s.getJSONArray("filters")
            for (j in 0 until filters.length()) {
                val f = filters.getJSONObject(j)
                when (f.optString("filterType")) {
                    "PRICE_FILTER" -> tick = BigDecimal(f.getString("tickSize"))
                    "LOT_SIZE" -> { step = BigDecimal(f.getString("stepSize")); minQty = BigDecimal(f.getString("minQty")) }
                    "MIN_NOTIONAL" -> minNotional = BigDecimal(f.optString("notional", f.optString("minNotional", "0")))
                    "PERCENT_PRICE" -> { up = f.optString("multiplierUp").takeIf { it.isNotBlank() }?.let { BigDecimal(it) }; down = f.optString("multiplierDown").takeIf { it.isNotBlank() }?.let { BigDecimal(it) } }
                }
            }
            if (tick != null && step != null && minQty != null) found = SymbolRules(symbol, tick, step, minQty, minNotional, up, down, s.optString("status").ifBlank { null })
        }
        found
    } catch (e: Exception) { null }

    fun floorToStep(value: BigDecimal, step: BigDecimal): BigDecimal = value.divide(step, 0, RoundingMode.FLOOR).multiply(step)
    fun ceilToStep(value: BigDecimal, step: BigDecimal): BigDecimal = value.divide(step, 0, RoundingMode.CEILING).multiply(step)

    /** Plain decimal text accepted by the exchange (no exponent, no trailing zeros). */
    fun plain(v: BigDecimal): String = if (v.signum() == 0) "0" else v.stripTrailingZeros().toPlainString()
}

/** Prices / quantities for the Compatibility Probe orders. They must be valid for the exchange but far enough from the market never to fill. */
object ProbeMath {
    data class Plan(val limitPrice: BigDecimal, val quantity: BigDecimal, val stopTrigger: BigDecimal)

    fun plan(rules: SymbolRules, mark: BigDecimal): Plan {
        // Inside the exchange's PERCENT_PRICE band (halfway to its lower bound), default 3 % below the mark.
        val factor = rules.percentDown?.let { BigDecimal.ONE.subtract(BigDecimal.ONE.subtract(it).divide(BigDecimal(2), 6, RoundingMode.HALF_UP)) }
            ?.max(BigDecimal("0.90"))?.min(BigDecimal("0.99")) ?: BigDecimal("0.97")
        val price = ExchangeRules.floorToStep(mark.multiply(factor), rules.tickSize).max(rules.tickSize)
        var qty = ExchangeRules.ceilToStep(rules.minNotional.multiply(BigDecimal("1.5")).divide(price, 12, RoundingMode.UP), rules.stepSize).max(rules.minQty)
        var guard = 0
        while (qty.multiply(price) < rules.minNotional && guard++ < 10) qty = qty.add(rules.stepSize)
        val trigger = ExchangeRules.floorToStep(mark.multiply(BigDecimal("0.90")), rules.tickSize).max(rules.tickSize)
        return Plan(price, qty, trigger)
    }
}
