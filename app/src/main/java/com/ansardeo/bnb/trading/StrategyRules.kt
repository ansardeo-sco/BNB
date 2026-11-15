package com.ansardeo.bnb.trading

/**
 * Eligibility rules for the two loss-triggered features. They are deliberately separate functions that each take ONLY
 * their own flags: Averaging and Opposite-Side never depend on each other's Enabled status or trigger.
 */
object StrategyRules {
    /** Averaging: enabled and not yet used (max one averaging per cycle). */
    fun averagingEligible(enabled: Boolean, alreadyDone: Boolean): Boolean = enabled && !alreadyDone

    /** Opposite-Side: enabled, not yet prepared for this cycle, and the source cycle is active. */
    fun oppositeEligible(enabled: Boolean, alreadyDone: Boolean, cycleActive: Boolean): Boolean = enabled && !alreadyDone && cycleActive

    /** Both LONG and SHORT entries are always allowed (direction is not a setting). Only the two valid values pass. */
    fun directionAllowed(direction: String): Boolean = direction.uppercase() == "LONG" || direction.uppercase() == "SHORT"
}
