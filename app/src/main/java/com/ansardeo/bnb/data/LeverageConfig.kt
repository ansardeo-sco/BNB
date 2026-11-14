package com.ansardeo.bnb.data

/**
 * Fixed BNB leverage/percentage table (approved). Single source of truth.
 * All values are ROI-based and derived from leverage L: re-entry pullback = 13.8/L %, averaging = 66/L %, entry Stop Loss = 69/L %.
 * There is NO Take Profit column: the profit exit is the ROI Trailing Stop (see trading/TrailingStopMath.kt).
 * Leverage range is strictly 3x-20x. Values outside this range are invalid everywhere.
 */
data class LeverageProfile(
    val leverage: Int,
    val reentryPullbackPercent: Double,
    val averagingPercent: Double,
    val slPercent: Double,
)

object LeverageConfig {
    val MIN_LEVERAGE = 3
    val MAX_LEVERAGE = 20
    val SUPPORTED_LEVERAGES: List<Int> = (MIN_LEVERAGE..MAX_LEVERAGE).toList()

    private val profiles: Map<Int, LeverageProfile> = listOf(
        LeverageProfile(3, 4.60, 22.00, 23.00),
        LeverageProfile(4, 3.45, 16.50, 17.25),
        LeverageProfile(5, 2.76, 13.20, 13.80),
        LeverageProfile(6, 2.30, 11.00, 11.50),
        LeverageProfile(7, 1.97, 9.43, 9.86),
        LeverageProfile(8, 1.73, 8.25, 8.63),
        LeverageProfile(9, 1.53, 7.33, 7.67),
        LeverageProfile(10, 1.38, 6.60, 6.90),
        LeverageProfile(11, 1.25, 6.00, 6.27),
        LeverageProfile(12, 1.15, 5.50, 5.75),
        LeverageProfile(13, 1.06, 5.08, 5.31),
        LeverageProfile(14, 0.99, 4.71, 4.93),
        LeverageProfile(15, 0.92, 4.40, 4.60),
        LeverageProfile(16, 0.86, 4.13, 4.31),
        LeverageProfile(17, 0.81, 3.88, 4.06),
        LeverageProfile(18, 0.77, 3.67, 3.83),
        LeverageProfile(19, 0.73, 3.47, 3.63),
        LeverageProfile(20, 0.69, 3.30, 3.45),
    ).associateBy { it.leverage }

    fun isSupported(leverage: Int): Boolean = leverage in MIN_LEVERAGE..MAX_LEVERAGE

    fun requireSupported(leverage: Int): Int {
        require(isSupported(leverage)) {
            "Unsupported leverage ${leverage}x. Allowed range is ${MIN_LEVERAGE}x-${MAX_LEVERAGE}x."
        }
        return leverage
    }

    fun profileFor(leverage: Int): LeverageProfile? = profiles[leverage]

    fun requireProfile(leverage: Int): LeverageProfile =
        profileFor(requireSupported(leverage))
            ?: throw IllegalArgumentException("Missing leverage profile for ${leverage}x")

    /** For display/defaulting only where a fallback is needed; never use to accept invalid input. */
    fun coerceToSupportedOrDefault(leverage: Int, default: Int = 6): Int =
        if (isSupported(leverage)) leverage else default.also { requireSupported(it) }

    fun allProfiles(): List<LeverageProfile> = SUPPORTED_LEVERAGES.map { requireProfile(it) }
}
