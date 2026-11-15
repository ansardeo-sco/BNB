package com.ansardeo.bnb.data

import org.junit.Assert.*
import org.junit.Test

class SettingsLockPolicyTest {
    @Test fun freeWhenNothingIsOpen() { assertNull(SettingsLockPolicy.reason(0, 0, 0, 0)) }

    @Test fun anyOneConditionLocks() {
        assertNotNull(SettingsLockPolicy.reason(1, 0, 0, 0))   // open position/cycle
        assertNotNull(SettingsLockPolicy.reason(0, 1, 0, 0))   // SL / trailing / any live order
        assertNotNull(SettingsLockPolicy.reason(0, 0, 1, 0))   // pending entry
        assertNotNull(SettingsLockPolicy.reason(0, 0, 0, 1))   // re-entry order/plan
    }

    @Test fun messageIsShown() {
        assertTrue(SettingsLockPolicy.reason(1, 0, 0, 0)!!.startsWith("Locked — Open Position/Order Exists"))
    }

    @Test fun bothMustBeClearToUnlock() {
        assertNotNull(SettingsLockPolicy.reason(0, 1, 0, 0))
        assertNotNull(SettingsLockPolicy.reason(1, 0, 0, 0))
        assertNull(SettingsLockPolicy.reason(0, 0, 0, 0))
    }
}
