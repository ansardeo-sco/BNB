package com.ansardeo.bnb.trading

import org.junit.Assert.*
import org.junit.Test

class StrategyRulesTest {
    /** All four combinations: each feature's eligibility depends only on its own flags. */
    @Test fun averagingAndOppositeAreIndependentInAllFourCombinations() {
        val cases = listOf(
            Triple(true, true, "both"), Triple(false, true, "only opposite"), Triple(true, false, "only averaging"), Triple(false, false, "neither"),
        )
        for ((avgOn, oppOn, label) in cases) {
            assertEquals(label, avgOn, StrategyRules.averagingEligible(avgOn, alreadyDone = false))
            assertEquals(label, oppOn, StrategyRules.oppositeEligible(oppOn, alreadyDone = false, cycleActive = true))
        }
    }

    @Test fun averagingOnlyOncePerCycle() {
        assertTrue(StrategyRules.averagingEligible(true, false))
        assertFalse(StrategyRules.averagingEligible(true, true))
    }

    @Test fun oppositeOnlyOncePerCycleAndOnlyWhenActive() {
        assertFalse(StrategyRules.oppositeEligible(true, true, true))
        assertFalse(StrategyRules.oppositeEligible(true, false, false))
        assertTrue(StrategyRules.oppositeEligible(true, false, true))
    }
}
