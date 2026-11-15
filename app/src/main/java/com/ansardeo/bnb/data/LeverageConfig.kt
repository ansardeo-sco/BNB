package com.ansardeo.bnb.data

/**
 * Leverage rules. Leverage range is strictly 3x-20x; values outside it are invalid everywhere.
 * There is NO leverage-derived percentage table any more: Stop Loss, Averaging trigger, Opposite-Side trigger and
 * Re-entry are all configurable Position-ROI values (see LocalCoinSettings / trading/RoiMath.kt).
 * There is no Take Profit: the profit exit is the ROI Trailing Stop.
 */
object LeverageConfig {
    val MIN_LEVERAGE = 3
    val MAX_LEVERAGE = 20
    val SUPPORTED_LEVERAGES: List<Int> = (MIN_LEVERAGE..MAX_LEVERAGE).toList()

    fun isSupported(leverage: Int): Boolean = leverage in MIN_LEVERAGE..MAX_LEVERAGE

    fun requireSupported(leverage: Int): Int {
        require(isSupported(leverage)) {
            "Unsupported leverage ${leverage}x. Allowed range is ${MIN_LEVERAGE}x-${MAX_LEVERAGE}x."
        }
        return leverage
    }

    /** For display/defaulting only where a fallback is needed; never use to accept invalid input. */
    fun coerceToSupportedOrDefault(leverage: Int, default: Int = 6): Int =
        if (isSupported(leverage)) leverage else default.also { requireSupported(it) }
}
