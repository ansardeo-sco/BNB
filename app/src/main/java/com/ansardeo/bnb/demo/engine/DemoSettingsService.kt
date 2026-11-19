package com.ansardeo.bnb.demo.engine

import android.content.Context
import com.ansardeo.bnb.data.LocalCoinSettingsStore
import com.ansardeo.bnb.data.SettingKeys
import com.ansardeo.bnb.data.SettingsLockPolicy
import com.ansardeo.bnb.data.SettingsResolver
import com.ansardeo.bnb.demo.exec.CycleStatus
import com.ansardeo.bnb.demo.exec.DemoExecStore
import com.ansardeo.bnb.demo.exec.EntryStatus
import com.ansardeo.bnb.trading.RoiMath

/**
 * The ONLY way the UI changes per-coin settings. Replaces the old Virtual-engine path: a coin's settings are locked while it has an open
 * Demo position/cycle, a pending entry or re-entry order, or an active Stop Loss; strict validation runs before anything is saved.
 * Existing cycles keep their own snapshot, so a write never alters a running cycle.
 */
class DemoSettingsService(private val store: DemoExecStore) {
    data class Result(val ok: Boolean, val message: String)

    fun lockReason(symbol: String): String? {
        val sym = symbol.uppercase()
        val entries = store.all().filter { it.symbol == sym }
        val openPositions = entries.count { it.executed.signum() > 0 && it.openQuantity.signum() > 0 && it.status != EntryStatus.CLOSED && it.status != EntryStatus.FAILSAFE_CLOSED }
        val openCycles = store.cycles().count { it.symbol == sym && it.status != CycleStatus.CLOSED }
        val pending = entries.count { it.status == EntryStatus.SENDING || it.status == EntryStatus.UNKNOWN || it.status == EntryStatus.NEW || it.status == EntryStatus.PARTIALLY_FILLED }
        val activeOrders = entries.count { it.slState == "VERIFIED" }
        return SettingsLockPolicy.reason(maxOf(openCycles, openPositions), activeOrders, pending, 0)
    }

    fun update(context: Context, symbol: String, changes: Map<String, Any>): Result {
        val sym = symbol.uppercase().trim()
        if (sym.isBlank() || !sym.endsWith("USDT")) return Result(false, "Enter a valid USDT symbol, e.g. BTCUSDT.")
        lockReason(sym)?.let { return Result(false, it) }
        val normalized = HashMap<String, Any>(changes)
        listOf(SettingKeys.AVERAGING_TRIGGER_ROI, SettingKeys.OPPOSITE_TRIGGER_ROI).forEach { k ->
            (normalized[k] as? Number)?.let { normalized[k] = RoiMath.normalizeLossTrigger(it.toDouble()) }
        }
        val merged = HashMap(LocalCoinSettingsStore.overridesFor(context, sym)); merged.putAll(normalized)
        try { SettingsResolver.resolveStrict(sym, LocalCoinSettingsStore.loadGlobal(context), merged) }
        catch (e: IllegalArgumentException) { return Result(false, e.message ?: "Invalid settings.") }
        LocalCoinSettingsStore.saveSymbol(context, sym, normalized)
        return Result(true, "Saved custom settings for $sym.")
    }

    fun reset(context: Context, symbol: String, key: String): Result {
        val sym = symbol.uppercase().trim()
        lockReason(sym)?.let { return Result(false, it) }
        LocalCoinSettingsStore.clearOverride(context, sym, key)
        return Result(true, "Setting reset to Inherited (Global).")
    }

    fun delete(context: Context, symbol: String): Result {
        val sym = symbol.uppercase().trim()
        lockReason(sym)?.let { return Result(false, it) }
        LocalCoinSettingsStore.deleteSymbol(context, sym)
        return Result(true, "Custom settings for $sym deleted. It now inherits Global Settings.")
    }

    companion object {
        fun get(context: Context) = DemoSettingsService(com.ansardeo.bnb.demo.exec.DemoExecutionProvider.store(context))
    }
}
