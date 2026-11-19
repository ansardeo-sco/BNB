package com.ansardeo.bnb.data

/**
 * Pure rule for "Saved Coin Settings" Edit/Delete locking (unit tested).
 * A symbol is LOCKED while it has ANY open cycle/position, pending entry, SL/Trailing order or re-entry order/plan.
 */
object SettingsLockPolicy {
    const val MESSAGE = "Locked — Open Position/Order Exists"

    fun reason(openCycles: Int, activeOrders: Int, pendingEntries: Int, openReentryPlans: Int): String? {
        val parts = ArrayList<String>()
        if (openCycles > 0) parts.add("open position/cycle")
        if (pendingEntries > 0) parts.add("pending entry order")
        if (activeOrders > 0) parts.add("active order")
        if (openReentryPlans > 0) parts.add("re-entry order")
        return if (parts.isEmpty()) null else "$MESSAGE (${parts.joinToString(", ")})"
    }
}
