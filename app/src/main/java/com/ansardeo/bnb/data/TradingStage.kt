package com.ansardeo.bnb.data

/**
 * BNB trading stages. ONLY Stage 1 (Virtual) is implemented and authorised.
 * Stage 2 (Demo) and Stage 3 (Live) are future phases that need separate explicit approval; they exist here
 * only as labels so the UI can show them as "not available". They can never execute anything.
 */
enum class TradingStage(val id: String, val title: String, val shortLabel: String, val available: Boolean) {
    STAGE_1("STAGE_1", "Stage 1 – Virtual (live prices, simulated trading)", "Stage 1", true),
    STAGE_2("STAGE_2", "Stage 2 – Demo (not available)", "Stage 2", false),
    STAGE_3("STAGE_3", "Stage 3 – Live (not available)", "Stage 3", false);

    /** Always false: this build contains no code path that can submit a real exchange order. */
    val canSubmitRealOrders: Boolean get() = false

    companion object {
        /** Fails closed: unknown or unavailable stage ids resolve to Stage 1. */
        fun fromId(id: String?): TradingStage = entries.firstOrNull { it.id == id && it.available } ?: STAGE_1
    }
}
