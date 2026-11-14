package com.ansardeo.bnb.data

import android.content.Context

/**
 * Stage selection is locked to Stage 1 (Virtual). Stage 2 (Demo) and Stage 3 (Live) are not implemented and
 * can not be selected, so selecting a stage can never change endpoints or enable real trading.
 */
object StageStore {
    @Suppress("UNUSED_PARAMETER")
    fun current(context: Context): TradingStage = TradingStage.STAGE_1
}
