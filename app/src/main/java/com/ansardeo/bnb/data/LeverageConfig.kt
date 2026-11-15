package com.ansardeo.bnb.data

/**
 * Fixed BNB leverage/percentage table (approved). Single source of truth.
 * Only the entry Stop Loss remains fixed by the table: SL = 69/L price percent (= -69% Position ROI at every leverage).
 * Averaging / Opposite-Side trigger ROI and Re-entry ROI (= Trailing Activation ROI / 2) are configurable, not table values.
 * There is NO Take Profit column: the profit exit is the ROI Trailing Stop (see trading/TrailingStopMath.kt).
 * Leverage range is strictly 3x-20x. Values outside this range are invalid everywhere.
 */
data class LeverageProfile(
    val leverage: Int,
    val slPercent: Double,
)

object LeverageConfig {
    val MIN_LEVERAGE = 3
    val MAX_LEVERAGE = 20
    val SUPPORTED_LEVERAGES: List<Int> = (MIN_LEVERAGE..MAX_LEVERAGE).toList()

    private val profiles: Map<Int, LeverageProfile> = listOf(
        LeverageProfile(3, 23.00),
        LeverageProfile(4, 17.25),
        LeverageProfile(5, 13.80),
        LeverageProfile(6, 11.50),
        LeverageProfile(7, 9.86),
        LeverageProfile(8, 8.63),
        LeverageProfile(9, 7.67),
        LeverageProfile(10, 6.90),
        LeverageProfile(11, 6.27),
        LeverageProfile(12, 5.75),
        LeverageProfile(13, 5.31),
        LeverageProfile(14, 4.93),
        LeverageProfile(15, 4.60),
        LeverageProfile(16, 4.31),
        LeverageProfile(17, 4.06),
        LeverageProfile(18, 3.83),
        LeverageProfile(19, 3.63),
        LeverageProfile(20, 3.45),
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
