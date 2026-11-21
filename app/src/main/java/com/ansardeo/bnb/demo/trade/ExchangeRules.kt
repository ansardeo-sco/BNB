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
    val contractType: String? = null,
    val quoteAsset: String? = null,
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
            if (tick != null && step != null && minQty != null) found = SymbolRules(symbol, tick, step, minQty, minNotional, up, down, s.optString("status").ifBlank { null }, s.optString("contractType").ifBlank { null }, s.optString("quoteAsset").ifBlank { null })
        }
        found
    } catch (e: Exception) { null }

    /** Every symbol of an exchangeInfo answer (Binance Demo without a symbol filter). Null = unreadable body. */
    fun parseAll(exchangeInfoBody: String): List<SymbolRules>? {
        val syms = runCatching { JSONObject(exchangeInfoBody).getJSONArray("symbols") }.getOrNull() ?: return null
        val out = ArrayList<SymbolRules>()
        for (i in 0 until syms.length()) {
            val name = syms.optJSONObject(i)?.optString("symbol").orEmpty(); if (name.isBlank()) continue
            parse("{\"symbols\":[" + syms.getJSONObject(i).toString() + "]}", name)?.let { out += it }
        }
        return out
    }

    /** The coins the app offers: listed on Binance Demo, status TRADING, USDT-quoted perpetual. Sorted, no duplicates. */
    fun tradable(all: List<SymbolRules>): List<String> =
        all.filter { it.status == "TRADING" && it.symbol.endsWith("USDT") && (it.quoteAsset == null || it.quoteAsset == "USDT") && (it.contractType == null || it.contractType == "PERPETUAL") }.map { it.symbol }.distinct().sorted()

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

/** Prepares an order's price and quantity for the exchange: rounding that is never more aggressive than requested, plus the exchange's limits. */
object OrderPrep {
    sealed class Result {
        data class Ok(val price: BigDecimal, val quantity: BigDecimal) : Result() {
            val priceText: String get() = ExchangeRules.plain(price)
            val quantityText: String get() = ExchangeRules.plain(quantity)
        }
        data class Rejected(val reason: String) : Result()
    }

    /** BUY prices round DOWN and SELL prices round UP to the tick; quantity rounds DOWN to the step. [mark] enables the PERCENT_PRICE band check. */
    fun prepare(rules: SymbolRules, side: String, rawPrice: BigDecimal, rawQty: BigDecimal, mark: BigDecimal?): Result {
        require(side == "BUY" || side == "SELL") { "side" }
        if (rules.status != null && rules.status != "TRADING") return Result.Rejected("Symbol is not trading (${rules.status}).")
        if (rawPrice.signum() <= 0 || rawQty.signum() <= 0) return Result.Rejected("Price and quantity must be positive.")
        val price = if (side == "BUY") ExchangeRules.floorToStep(rawPrice, rules.tickSize) else ExchangeRules.ceilToStep(rawPrice, rules.tickSize)
        val qty = ExchangeRules.floorToStep(rawQty, rules.stepSize)
        if (price.signum() <= 0) return Result.Rejected("Price is below one tick.")
        if (qty < rules.minQty) return Result.Rejected("Quantity ${ExchangeRules.plain(qty)} is below the minimum ${ExchangeRules.plain(rules.minQty)}.")
        if (price.multiply(qty) < rules.minNotional) return Result.Rejected("Order value ${ExchangeRules.plain(price.multiply(qty))} is below the minimum ${ExchangeRules.plain(rules.minNotional)}.")
        if (mark != null) {
            val up = rules.percentUp?.let { mark.multiply(it) }; val down = rules.percentDown?.let { mark.multiply(it) }
            if (up != null && price > up) return Result.Rejected("Price is above the exchange's allowed band.")
            if (down != null && price < down) return Result.Rejected("Price is below the exchange's allowed band.")
        }
        return Result.Ok(price, qty)
    }
}

/** Small in-memory time-limited cache of exchange rules. */
class RulesCache(private val ttlMs: Long = 6 * 60 * 60 * 1000L, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private class Entry(val rules: SymbolRules, val at: Long)
    private val map = HashMap<String, Entry>()
    @Synchronized fun put(rules: SymbolRules) { map[rules.symbol] = Entry(rules, clock()) }
    @Synchronized fun get(symbol: String): SymbolRules? = map[symbol]?.takeIf { clock() - it.at <= ttlMs }?.rules
    @Synchronized fun clear() = map.clear()
}
