package com.ansardeo.bnb.paper

import android.content.Context
import androidx.room.withTransaction
import com.ansardeo.bnb.data.LeverageConfig
import com.ansardeo.bnb.data.LocalCoinSettings
import com.ansardeo.bnb.data.LocalCoinSettingsStore
import com.ansardeo.bnb.data.SettingKeys
import com.ansardeo.bnb.data.SettingsLockPolicy
import com.ansardeo.bnb.data.SettingsResolver
import com.ansardeo.bnb.trading.RoiMath
import com.ansardeo.bnb.trading.StrategyRules
import com.ansardeo.bnb.data.TradingStage
import com.ansardeo.bnb.paper.db.*
import com.ansardeo.bnb.trading.TrailSide
import com.ansardeo.bnb.trading.TrailingConfig
import com.ansardeo.bnb.trading.TrailingState
import com.ansardeo.bnb.trading.TrailingStopMath
import com.ansardeo.bnb.trading.TrailingStopMath.bd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * BNB trading engine (Stage 1 - Virtual: local simulation only).
 * - All entries are LIMIT orders (manual, averaging, opposite, re-entry). Never MARKET.
 * - One cycle per symbol/direction (open/pending/active). Long and Short are independent cycles.
 * - Max 1 averaging per cycle. After an averaging fill the cycle WAP is recalculated from actual filled quantities;
 *   the trailing activation price is recalculated from the new WAP; existing entry SLs stay fixed.
 * - Every executed entry gets its own independent fixed SL (from its actual fill price + leverage SL%).
 * - Individual SL closes only that entry's remaining qty. Other entries remain open.
 * - NO Take Profit. The only profit exit is the ROI Trailing Stop (activation/callback ROI, based on cycle WAP),
 *   which closes ALL remaining qty of the cycle. See trading/TrailingStopMath.kt.
 * - Averaging: one LIMIT per cycle at the price where Position ROI = cycle.averagingTriggerRoi (from the cycle WAP).
 * - Opposite-Side: INDEPENDENT of averaging. A separate cycle in the other direction, entered at the price where the
 *   original position reaches cycle.oppositeTriggerRoi (conditional LIMIT: it fills when that price is reached),
 *   only if no opposite-direction cycle is open. It has its own margin, SL and trailing stop.
 * - Re-entry after a Trailing Stop exit: Re-entry ROI = Activation ROI / 2. Two independent plans (LONG below / SHORT above
 *   the closing price, Position-ROI distance with leverage). When one fills, the sibling is cancelled automatically.
 * - Direction is never a setting: LONG and SHORT are always permitted.
 * - Saved symbol settings cannot be edited/deleted while the symbol has an open cycle, order or re-entry plan
 *   (checked here, under the engine mutex, not only in the UI). Existing cycles keep their own snapshot.
 * - Never submits real exchange orders. Only Stage 1 is executable; Stage 2/3 are blocked.
 */
class PaperEngine(private val db: PaperDatabase, val stage: TradingStage = TradingStage.STAGE_1) {
    private val dao: PaperDao = db.dao()
    private val mutex = Mutex()
    private val feeRate = 0.0004

    data class OpenLimitRequest(
        val symbol: String,
        val direction: String,
        val intendedLimitPrice: Double,
        val margin: Double,
        val leverage: Int,
        val sourceType: String = "MANUAL",
        val sourceCycleId: Long? = null,
    )

    data class EngineResult(val ok: Boolean, val message: String, val cycleId: Long? = null, val entryId: Long? = null, val orderId: Long? = null)

    suspend fun getWallet(): PaperWalletEntity = mutex.withLock {
        db.withTransaction { ensureWallet() }
    }

    /** Null = free to edit/delete saved settings for [symbol]; otherwise the human-readable lock reason. */
    suspend fun symbolLockReason(symbol: String): String? = mutex.withLock { db.withTransaction { lockReasonLocked(symbol.uppercase()) } }

    private suspend fun lockReasonLocked(sym: String): String? = SettingsLockPolicy.reason(
        openCycles = dao.countOpenCyclesAnyDirection(stage.id, sym),
        activeOrders = dao.countLiveOrdersForSymbol(stage.id, sym),
        pendingEntries = dao.countPendingEntriesForSymbol(stage.id, sym),
        openReentryPlans = dao.countOpenReentryPlansForSymbol(stage.id, sym),
    )

    /**
     * Creates/updates the saved custom settings of [symbol]. Refused while the symbol is locked, and when a value is
     * invalid (strict backend validation). [changes] holds only the keys that differ from the inherited values.
     */
    suspend fun updateSymbolSettings(context: Context, symbol: String, changes: Map<String, Any>): EngineResult = mutex.withLock {
        val sym = symbol.uppercase().trim()
        if (sym.isBlank() || !sym.endsWith("USDT")) return@withLock EngineResult(false, "Enter a valid USDT symbol, e.g. BTCUSDT.")
        db.withTransaction {
            lockReasonLocked(sym)?.let { return@withTransaction EngineResult(false, it) }
            val normalized = HashMap<String, Any>(changes)
            listOf(SettingKeys.AVERAGING_TRIGGER_ROI, SettingKeys.OPPOSITE_TRIGGER_ROI).forEach { k ->
                (normalized[k] as? Number)?.let { normalized[k] = RoiMath.normalizeLossTrigger(it.toDouble()) }
            }
            val merged = HashMap(LocalCoinSettingsStore.overridesFor(context, sym)); merged.putAll(normalized)
            try { SettingsResolver.resolveStrict(sym, LocalCoinSettingsStore.loadGlobal(context), merged) }
            catch (e: IllegalArgumentException) { return@withTransaction EngineResult(false, e.message ?: "Invalid settings.") }
            LocalCoinSettingsStore.saveSymbol(context, sym, normalized)
            EngineResult(true, "Saved custom settings for $sym.")
        }
    }

    /** Resets ONE symbol setting back to Inherited (Global). Refused while the symbol is locked. */
    suspend fun resetSymbolSetting(context: Context, symbol: String, key: String): EngineResult = mutex.withLock {
        val sym = symbol.uppercase().trim()
        db.withTransaction {
            lockReasonLocked(sym)?.let { return@withTransaction EngineResult(false, it) }
            LocalCoinSettingsStore.clearOverride(context, sym, key)
            EngineResult(true, "Setting reset to Inherited (Global).")
        }
    }

    /** Deletes the symbol's custom settings (it inherits Global again). Never touches cycles/orders/watchlist. Refused while locked. */
    suspend fun deleteSymbolSettings(context: Context, symbol: String): EngineResult = mutex.withLock {
        val sym = symbol.uppercase().trim()
        db.withTransaction {
            lockReasonLocked(sym)?.let { return@withTransaction EngineResult(false, it) }
            LocalCoinSettingsStore.deleteSymbol(context, sym)
            EngineResult(true, "Custom settings for $sym deleted. It now inherits Global Settings.")
        }
    }

    suspend fun hasPendingWork(): Boolean = mutex.withLock {
        db.withTransaction {
            dao.getActiveCycles(stage.id).isNotEmpty() ||
                dao.getPendingReentryPlans(stage.id).isNotEmpty() ||
                dao.getActiveOrders(stage.id).isNotEmpty()
        }
    }

    /**
     * Deliberate user action (More > Reset, with confirmation): wipes ALL local virtual data
     * (wallet, cycles, entries, orders, history, events) and restores the starting virtual balance.
     */
    suspend fun resetAllData(starting: Double = 5000.0): EngineResult = mutex.withLock {
        assertSimulatedExecutionAllowed()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        db.withTransaction {
            val now = System.currentTimeMillis()
            dao.upsertWallet(PaperWalletEntity(stage = stage.id, startingBalance = starting, availableBalance = starting, reservedMargin = 0.0, realizedPnl = 0.0, updatedAt = now))
            val msg = "All virtual data cleared. Virtual balance reset to $starting USDT (Stage 1)."
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = null, symbol = null, type = "RESET", message = msg, dedupeKey = "reset-${stage.id}-$now"))
            EngineResult(true, msg)
        }
    }

    /** More > Cancel Pending Orders: cancels every unfilled LIMIT entry and waiting re-entry plan (local only). */
    suspend fun cancelAllPendingOrders(): EngineResult = mutex.withLock {
        assertSimulatedExecutionAllowed()
        db.withTransaction {
            val now = System.currentTimeMillis()
            val entries = dao.getPendingEntries(stage.id)
            entries.forEach { cancelPendingEntryLocked(it, now, "USER_CANCEL_ALL") }
            val plans = dao.getPendingReentryPlans(stage.id)
            plans.forEach { dao.updateReentryPlan(it.copy(status = "CANCELLED")) }
            EngineResult(true, "Cancelled ${entries.size} pending order(s) and ${plans.size} re-entry plan(s).")
        }
    }

    /** More > Panic Close: closes every open entry at the given current prices (local simulation only). */
    suspend fun panicClose(prices: Map<String, Double>): EngineResult = mutex.withLock {
        assertSimulatedExecutionAllowed()
        db.withTransaction {
            val now = System.currentTimeMillis()
            var closed = 0
            var skipped = 0
            for (cycle in dao.getActiveCycles(stage.id)) {
                val px = prices[cycle.symbol.uppercase()]
                if (px == null || !px.isFinite() || px <= 0.0) { if (dao.getOpenFilledEntries(cycle.id).isNotEmpty()) skipped++; continue }
                for (entry in dao.getOpenFilledEntries(cycle.id)) {
                    closeSingleEntryLocked(cycle, entry, px, "PANIC", now, "panic-${entry.id}-$now")
                    closed++
                }
                dao.getEntries(cycle.id).filter { it.status == "PENDING" }.forEach { cancelPendingEntryLocked(it, now, "PANIC_CLOSE") }
                finalizeIfEmptyLocked(dao.getCycle(cycle.id) ?: cycle, px, now, "PANIC_CLOSE")
            }
            dao.getPendingReentryPlans(stage.id).forEach { dao.updateReentryPlan(it.copy(status = "CANCELLED")) }
            val msg = "Panic close: $closed entr${if (closed == 1) "y" else "ies"} closed" + if (skipped > 0) ", $skipped cycle(s) skipped (no live price)." else "."
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = null, symbol = null, type = "PANIC_CLOSE", message = msg, dedupeKey = "panic-$now"))
            EngineResult(closed > 0 || skipped == 0, msg)
        }
    }

    /** Place a manual initial LIMIT entry (never MARKET). */
    suspend fun openInitialLimit(
        context: Context,
        symbol: String,
        direction: String,
        margin: Double,
        leverage: Int,
        intendedLimitPrice: Double,
    ): EngineResult {
        assertSimulatedExecutionAllowed()
        if (TradingControlStore.isPaused(context)) return EngineResult(false, "Trading is paused (More > Resume). No new orders can be placed.")
        return mutex.withLock {
            db.withTransaction {
                placeInitialLimitLocked(context, symbol, direction, margin, leverage, intendedLimitPrice, sourceType = "MANUAL", sourceCycleId = null)
            }
        }
    }

    /** Legacy-compatible entry point from old UI; treated as LIMIT at provided price (must be user/intended price). */
    suspend fun openInitial(context: Context, symbol: String, direction: String, margin: Double, leverage: Int, price: Double): PaperCycleEntity? {
        val r = openInitialLimit(context, symbol, direction, margin, leverage, intendedLimitPrice = price)
        return r.cycleId?.let { dao.getCycle(it) }
    }

    suspend fun processAll(context: Context, prices: Map<String, Double>) {
        assertSimulatedExecutionAllowed()
        if (prices.isEmpty()) return
        if (TradingControlStore.isPaused(context)) return // paused: the simulation is frozen (no fills, no exits)
        mutex.withLock {
            db.withTransaction {
                val now = System.currentTimeMillis()
                ensureWallet()
                // Pending entry LIMIT orders (initial/averaging/opposite/reentry)
                dao.getPendingEntries(stage.id).forEach { entry ->
                    val mark = prices[entry.symbol.uppercase()] ?: return@forEach
                    tryFillPendingEntryLocked(context, entry, mark, now)
                }
                // Re-entry plans waiting for pullback
                dao.getPendingReentryPlans(stage.id).forEach { plan ->
                    val mark = prices[plan.symbol.uppercase()] ?: return@forEach
                    processReentryPlanLocked(context, plan, mark, now)
                }
                // Active cycles: liquidation, cycle trailing stop, entry SLs, averaging
                dao.getActiveCycles(stage.id).forEach { c ->
                    val mark = prices[c.symbol.uppercase()] ?: return@forEach
                    processActiveCycleLocked(context, c.copy(lastProcessedMark = mark, updatedAt = now), mark, now)
                }
            }
        }
    }

    private suspend fun placeInitialLimitLocked(
        context: Context,
        symbol: String,
        direction: String,
        margin: Double,
        leverage: Int,
        intendedLimitPrice: Double,
        sourceType: String,
        sourceCycleId: Long?,
    ): EngineResult {
        val sym = symbol.uppercase().trim()
        val dir = direction.uppercase().trim()
        require(dir == "LONG" || dir == "SHORT") { "Direction must be LONG or SHORT" }
        val lev = LeverageConfig.requireSupported(leverage)
        require(intendedLimitPrice.isFinite() && intendedLimitPrice > 0.0) { "Intended entry price must be greater than 0" }
        require(margin.isFinite() && margin > 0.0) { "Margin must be greater than 0" }
        val settings = runCatching { LocalCoinSettingsStore.load(context, sym) }.getOrNull()
        val profile = LeverageConfig.requireProfile(lev)
        val now = System.currentTimeMillis()
        val wallet = ensureWallet()
        val required = margin + (margin * lev * feeRate)
        if (wallet.availableBalance + 1e-9 < required) return EngineResult(false, "Insufficient simulated balance. Required ~${fmt(required)} USDT.")
        val open = dao.countOpenCycles(stage.id, sym, dir)
        if (open > 0) return EngineResult(false, "An open/pending $dir cycle already exists for $sym in ${stage.shortLabel}.")
        val cycle = PaperCycleEntity(
            stage = stage.id, symbol = sym, direction = dir, status = "PENDING_ENTRY", leverage = lev,
            initialMargin = margin, averagingMargin = settings?.averagingMargin?.takeIf { it > 0 } ?: margin,
            oppositeMargin = settings?.oppositeMargin?.takeIf { it > 0 } ?: margin,
            slPercent = profile.slPercent,
            averagingEnabled = settings?.averagingEnabled ?: true, reentryEnabled = settings?.reentryEnabled ?: true, oppositeEnabled = settings?.oppositeEnabled ?: true,
            // A cycle born as an opposite-side hedge never spawns another hedge (no ping-pong between two sides).
            oppositeDone = sourceType == "OPPOSITE",
            trailingEnabled = settings?.trailingEnabled ?: true,
            activationRoi = settings?.trailingActivationRoi ?: LocalCoinSettings.DEFAULT_ACTIVATION_ROI,
            callbackRoi = settings?.trailingCallbackRoi ?: LocalCoinSettings.DEFAULT_CALLBACK_ROI,
            averagingTriggerRoi = settings?.averagingTriggerRoi ?: LocalCoinSettings.DEFAULT_AVERAGING_TRIGGER_ROI,
            oppositeTriggerRoi = settings?.oppositeTriggerRoi ?: LocalCoinSettings.DEFAULT_OPPOSITE_TRIGGER_ROI,
            reentryRoi = (settings?.trailingActivationRoi ?: LocalCoinSettings.DEFAULT_ACTIVATION_ROI) / 2.0,
            sourceCycleId = sourceCycleId, sourceType = sourceType, createdAt = now, updatedAt = now
        )
        val cycleId = dao.insertCycle(cycle)
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - required, reservedMargin = wallet.reservedMargin + margin, updatedAt = now))
        val clientOrderId = "entry-${stage.id}-$cycleId-initial-$now-${UUID.randomUUID()}"
        val entry = PaperEntryEntity(
            stage = stage.id, cycleId = cycleId, symbol = sym, direction = dir, entryType = "INITIAL", status = "PENDING",
            margin = margin, leverage = lev, intendedPrice = intendedLimitPrice, clientOrderId = clientOrderId,
            createdAt = now, updatedAt = now
        )
        val entryId = dao.insertEntry(entry)
        val order = PaperOrderEntity(
            stage = stage.id, cycleId = cycleId, entryId = entryId, symbol = sym, direction = dir,
            side = if (dir == "LONG") "BUY" else "SELL", orderType = "ENTRY_LIMIT", triggerPrice = intendedLimitPrice,
            limitPrice = intendedLimitPrice, quantity = 0.0, status = "ACTIVE", clientOrderId = clientOrderId,
            createdAt = now, updatedAt = now
        )
        val orderId = dao.insertOrder(order)
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycleId, symbol = sym, type = "ENTRY_LIMIT_PLACED",
            message = "Placed INITIAL LIMIT $dir for $sym @ ${fmtPx(intendedLimitPrice)} (margin $margin, ${lev}x). ${stage.shortLabel} simulated only; no real order submitted.",
            price = intendedLimitPrice, dedupeKey = "place-$clientOrderId"))
        return EngineResult(true, "Initial LIMIT entry placed for $sym $dir @ ${fmtPx(intendedLimitPrice)}.", cycleId, entryId, orderId)
    }

    private suspend fun tryFillPendingEntryLocked(context: Context, entry: PaperEntryEntity, mark: Double, now: Long) {
        if (entry.status != "PENDING" || !mark.isFinite() || mark <= 0.0) return
        val owner = dao.getCycle(entry.cycleId)
        if (owner == null || owner.status == "CLOSED") { cancelPendingEntryLocked(entry, now, "CYCLE_CLOSED"); return }
        val trigger = entry.intendedPrice
        val fillable = if (owner.sourceType == "OPPOSITE") {
            // Opposite-side entry is CONDITIONAL on the source position's loss: the price level lies on the losing side of
            // the source position, so the opposite entry fills when that level is reached (SHORT: price falls to it, LONG: rises to it).
            when (entry.direction) {
                "SHORT" -> mark <= trigger + 1e-12
                "LONG" -> mark >= trigger - 1e-12
                else -> false
            }
        } else when (entry.direction) {
            "LONG" -> mark <= trigger + 1e-12
            "SHORT" -> mark >= trigger - 1e-12
            else -> false
        }
        if (!fillable) return
        val order = dao.getActiveOrdersForCycle(entry.cycleId).firstOrNull { it.entryId == entry.id && it.orderType == "ENTRY_LIMIT" }
        val fillPrice = trigger // LIMIT fill at intended price in simulation
        val qty = max((entry.margin * entry.leverage) / fillPrice, 0.0)
        val fee = qty * fillPrice * feeRate
        val fillKey = "fill-${entry.clientOrderId}"
        val filledEntry = entry.copy(status = "FILLED", fillPrice = fillPrice, quantity = qty, filledQuantity = qty, remainingQuantity = qty, fee = fee, filledAt = now, updatedAt = now, fillEventId = fillKey)
        dao.updateEntry(filledEntry)
        if (order != null) dao.updateOrder(order.copy(status = "FILLED", filledPrice = fillPrice, filledQuantity = qty, updatedAt = now, executedAt = now))
        val cycle = dao.getCycle(entry.cycleId) ?: return
        val wallet = ensureWallet()
        // reserved margin already taken at placement; adjust for fee difference approximation
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - max(fee - (entry.margin * entry.leverage * feeRate), 0.0), updatedAt = now))
        applyEntryFilledLocked(context, cycle, filledEntry, now)
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "ENTRY_FILLED",
            message = "${entry.entryType} LIMIT filled ${cycle.symbol} ${cycle.direction} @ ${fmtPx(fillPrice)} qty ${fmtQty(qty)}.",
            price = fillPrice, dedupeKey = fillKey))
        if (entry.entryType == "INITIAL" && cycle.sourceType == "REENTRY") onReentryFilledLocked(cycle, now)
    }

    private suspend fun applyEntryFilledLocked(context: Context, cycle: PaperCycleEntity, filledEntry: PaperEntryEntity, now: Long) {
        // Create independent fixed SL for this entry (from actual fill)
        val slPrice = calcSl(cycle.direction, filledEntry.fillPrice!!, cycle.slPercent)
        val slClientId = "sl-${stage.id}-${cycle.id}-${filledEntry.id}-${UUID.randomUUID()}"
        val slOrder = PaperOrderEntity(
            stage = stage.id, cycleId = cycle.id, entryId = filledEntry.id, symbol = cycle.symbol, direction = cycle.direction,
            side = if (cycle.direction == "LONG") "SELL" else "BUY", orderType = "ENTRY_SL", triggerPrice = slPrice, limitPrice = null,
            quantity = filledEntry.remainingQuantity, status = "ACTIVE", clientOrderId = slClientId, createdAt = now, updatedAt = now
        )
        val slOrderId = dao.insertOrder(slOrder)
        dao.updateEntry(filledEntry.copy(slPrice = slPrice, slOrderId = slOrderId, slFixed = true, updatedAt = now))

        val openEntries = dao.getOpenFilledEntries(cycle.id)
        val totalQty = openEntries.sumOf { it.remainingQuantity }
        val avg = if (totalQty > 1e-12) openEntries.sumOf { (it.fillPrice ?: 0.0) * it.remainingQuantity } / totalQty else filledEntry.fillPrice
        val avgPx = avg!!
        val activation = TrailingStopMath.activationPrice(TrailSide.fromDirection(cycle.direction), bd(avgPx), cycle.leverage, bd(cycle.activationRoi)).toDouble()
        var updatedCycle = cycle.copy(
            status = "ACTIVE", averageEntryPrice = avgPx, totalQuantity = dao.getEntries(cycle.id).filter { it.status == "FILLED" || it.status == "CLOSED" }.sumOf { it.filledQuantity },
            totalRemainingQuantity = totalQty, activationPrice = activation, updatedAt = now, lastError = null
        )

        if (filledEntry.entryType == "AVERAGING") {
            updatedCycle = updatedCycle.copy(averagingDone = true)
            // WAP recalculated above; trailing activation follows the new WAP. Existing SLs remain fixed - do not touch them.
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVERAGING_FILLED",
                message = "Averaging filled @ ${fmtPx(filledEntry.fillPrice!!)}. New WAP ${fmtPx(avgPx)}. Trailing activation recalculated to ${fmtPx(activation)}. Existing entry SLs unchanged.",
                price = filledEntry.fillPrice, dedupeKey = "avg-${filledEntry.clientOrderId}"))
        } else if (filledEntry.entryType == "INITIAL") {
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "INITIAL_ACTIVE",
                message = "Initial entry active @ ${fmtPx(filledEntry.fillPrice!!)}. Trailing stop activates @ ${fmtPx(activation)} (ROI ${cycle.activationRoi}, callback ROI ${cycle.callbackRoi}). Independent SL ${fmtPx(slPrice)} (fixed).",
                price = filledEntry.fillPrice, dedupeKey = "init-${filledEntry.clientOrderId}"))
        }
        dao.updateCycle(updatedCycle)
        upsertCycleTrailingOrderLocked(updatedCycle, now)
        // As soon as initial entry is filled, automatically create same-direction Averaging LIMIT (once per cycle)
        if (filledEntry.entryType == "INITIAL") {
            placeAveragingLimitIfNeededLocked(context, dao.getCycle(cycle.id) ?: updatedCycle, now)
            placeOppositeLimitIfNeededLocked(context, dao.getCycle(cycle.id) ?: updatedCycle, now)
        }
    }

    /** Keeps the cycle-level trailing order record (shown under Open Orders > Conditional) in sync with the cycle state. */
    private suspend fun upsertCycleTrailingOrderLocked(cycle: PaperCycleEntity, now: Long) {
        if (!cycle.trailingEnabled) return
        val trigger = cycle.trailingStopPrice ?: cycle.activationPrice ?: return
        val qty = dao.getOpenFilledEntries(cycle.id).sumOf { it.remainingQuantity }
        if (qty <= 1e-12) return
        val existing = dao.getActiveTrailingForCycle(cycle.id)
        val side = if (cycle.direction == "LONG") "SELL" else "BUY"
        if (existing == null) {
            val clientId = "trail-${stage.id}-${cycle.id}-${UUID.randomUUID()}"
            val o = PaperOrderEntity(stage = stage.id, cycleId = cycle.id, entryId = null, symbol = cycle.symbol, direction = cycle.direction, side = side,
                orderType = "CYCLE_TRAILING", triggerPrice = trigger, limitPrice = null, quantity = qty, status = "ACTIVE", clientOrderId = clientId, createdAt = now, updatedAt = now)
            val id = dao.insertOrder(o)
            dao.updateCycle((dao.getCycle(cycle.id) ?: cycle).copy(trailingOrderId = id, updatedAt = now))
        } else if (existing.triggerPrice != trigger || abs(existing.quantity - qty) > 1e-12) {
            dao.updateOrder(existing.copy(triggerPrice = trigger, quantity = qty, updatedAt = now, lastError = null))
        }
    }

    /** Cancels one unfilled entry LIMIT order and releases the margin that was reserved for it. */
    private suspend fun cancelPendingEntryLocked(entry: PaperEntryEntity, now: Long, reason: String) {
        if (entry.status != "PENDING") return
        dao.updateEntry(entry.copy(status = "CANCELLED", closeReason = reason, closedAt = now, updatedAt = now))
        dao.getActiveOrdersForCycle(entry.cycleId).filter { it.entryId == entry.id && it.orderType == "ENTRY_LIMIT" }
            .forEach { dao.updateOrder(it.copy(status = "CANCEL_VERIFIED", cancelRequestedAt = now, cancelVerifiedAt = now, updatedAt = now)) }
        val wallet = ensureWallet()
        val required = entry.margin + (entry.margin * entry.leverage * feeRate)
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance + required, reservedMargin = max(wallet.reservedMargin - entry.margin, 0.0), updatedAt = now))
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = entry.cycleId, symbol = entry.symbol, type = "ENTRY_CANCELLED",
            message = "${entry.entryType} LIMIT cancelled ($reason). Reserved margin released.", dedupeKey = "cancel-${entry.clientOrderId}"))
        // A cycle that never got a fill and has no other live entry is finished: it must not keep blocking its direction.
        val owner = dao.getCycle(entry.cycleId)
        if (owner != null && owner.status == "PENDING_ENTRY" && dao.getEntries(owner.id).none { it.status == "PENDING" || it.status == "FILLED" }) {
            dao.updateCycle(owner.copy(status = "CANCELLED", closeReason = reason, closedAt = now, updatedAt = now))
        }
    }

    /** Re-entry bookkeeping when one side filled: mark its plan EXECUTED and auto-cancel the sibling side (LONG/SHORT). */
    private suspend fun onReentryFilledLocked(cycle: PaperCycleEntity, now: Long) {
        val source = cycle.sourceCycleId ?: return
        val plans = dao.getReentryPlansForSource(source)
        val mine = plans.firstOrNull { it.newCycleId == cycle.id } ?: return
        dao.updateReentryPlan(mine.copy(status = "EXECUTED", executedAt = now, updatedAt = now))
        for (other in plans) {
            if (other.id == mine.id || other.status !in setOf("WAITING", "TRIGGERED", "ORDER_PLACED")) continue
            other.newCycleId?.let { cid -> dao.getEntries(cid).filter { it.status == "PENDING" }.forEach { cancelPendingEntryLocked(it, now, "REENTRY_SIBLING_FILLED") } }
            dao.updateReentryPlan(other.copy(status = "CANCELLED", updatedAt = now))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = source, symbol = cycle.symbol, type = "REENTRY_SIBLING_CANCELLED",
                message = "Re-entry ${other.direction} cancelled automatically because re-entry ${mine.direction} filled (cycle #${cycle.id}).", dedupeKey = "re-sib-${other.id}"))
        }
    }

    /** Cancels unfilled opposite-side orders created from [sourceCycleId] (the reason for them ended with the source cycle). */
    private suspend fun cancelUnfilledOppositeOfLocked(sourceCycleId: Long, now: Long) {
        dao.getPendingEntries(stage.id).forEach { e ->
            val c = dao.getCycle(e.cycleId) ?: return@forEach
            if (c.sourceType == "OPPOSITE" && c.sourceCycleId == sourceCycleId && e.entryType == "INITIAL") cancelPendingEntryLocked(e, now, "SOURCE_CYCLE_CLOSED")
        }
    }

    /**
     * Opposite-Side (independent of Averaging): once the source cycle is active, prepare ONE opposite-direction LIMIT entry at the
     * price where the source position's Position ROI equals cycle.oppositeTriggerRoi (from the cycle WAP). Only if no opposite-direction
     * cycle is open. The new entry becomes its own cycle (own margin, SL, trailing stop, snapshot).
     */
    private suspend fun placeOppositeLimitIfNeededLocked(context: Context, cycle: PaperCycleEntity, now: Long) {
        if (!StrategyRules.oppositeEligible(cycle.oppositeEnabled, cycle.oppositeDone, cycle.status == "ACTIVE")) return
        val wap = cycle.averageEntryPrice ?: return
        val oppositeDir = if (cycle.direction == "LONG") "SHORT" else "LONG"
        suspend fun blocked(msg: String) {
            if (cycle.lastError == msg) return // log once per reason; the check is retried on every tick
            dao.updateCycle(cycle.copy(lastError = msg, updatedAt = now))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "OPPOSITE_WAITING", message = msg, dedupeKey = "opp-wait-${cycle.id}-${msg.hashCode()}"))
        }
        if (dao.countOpenCycles(stage.id, cycle.symbol, oppositeDir) > 0) { blocked("Opposite-side entry waiting: an open $oppositeDir cycle already exists for ${cycle.symbol}."); return }
        val price = RoiMath.priceAtRoi(TrailSide.fromDirection(cycle.direction), bd(wap), cycle.leverage, bd(cycle.oppositeTriggerRoi)).toDouble()
        val margin = if (cycle.oppositeMargin > 0.0) cycle.oppositeMargin else cycle.initialMargin
        val res = placeInitialLimitLocked(context, cycle.symbol, oppositeDir, margin, cycle.leverage, price, sourceType = "OPPOSITE", sourceCycleId = cycle.id)
        if (res.ok) {
            dao.updateCycle((dao.getCycle(cycle.id) ?: cycle).copy(oppositeDone = true, lastError = null, updatedAt = now))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "OPPOSITE_PLACED",
                message = "Opposite-side $oppositeDir entry prepared @ ${fmtPx(price)} (source Position ROI ${cycle.oppositeTriggerRoi}%, margin ${fmt(margin)} USDT) as separate cycle #${res.cycleId}.",
                price = price, dedupeKey = "opp-${cycle.id}"))
        } else {
            blocked("Opposite-side entry not placed: ${res.message}")
        }
    }

    private suspend fun processActiveCycleLocked(context: Context, cycle: PaperCycleEntity, mark: Double, now: Long) {
        if (!mark.isFinite() || mark <= 0.0) return
        if (cycle.status !in setOf("ACTIVE", "CLOSING")) return
        val openEntries = dao.getOpenFilledEntries(cycle.id)
        if (openEntries.isEmpty()) {
            finalizeIfEmptyLocked(cycle, mark, now, cycle.closeReason ?: "COMPLETED")
            return
        }
        // Liquidation checks per entry (individual) - before trailing/SL to be safe
        for (entry in openEntries) {
            val liq = liquidationPrice(entry.direction, entry.fillPrice ?: continue, entry.leverage)
            val hitLiq = if (entry.direction == "LONG") mark <= liq else mark >= liq
            if (hitLiq) {
                closeSingleEntryLocked(cycle, entry, liq, "LIQUIDATION", now, "liq-${entry.id}-${entry.filledAt}")
                // affected entry only; its SL cancelled/verified inside
            }
        }
        val afterLiq = dao.getOpenFilledEntries(cycle.id)
        if (afterLiq.isEmpty()) { finalizeIfEmptyLocked(dao.getCycle(cycle.id) ?: cycle, mark, now, "LIQUIDATION"); return }

        // Cycle Trailing Stop (ROI based on cycle WAP). Closes ALL remaining qty when triggered.
        var live = dao.getCycle(cycle.id) ?: cycle
        val wap = live.averageEntryPrice
        if (live.trailingEnabled && wap != null && wap > 0.0) {
            val side = TrailSide.fromDirection(live.direction)
            val cfg = TrailingConfig(bd(live.activationRoi), bd(live.callbackRoi))
            val prev = TrailingState(live.trailingActive, live.trailingExtreme?.let { bd(it) }, live.trailingStopPrice?.let { bd(it) })
            val step = TrailingStopMath.step(side, bd(wap), live.leverage, cfg, prev, bd(mark))
            val st = step.state
            val newActivation = step.activationPrice.toDouble()
            val newExtreme = st.extreme?.toDouble()
            val newStop = st.stopPrice?.toDouble()
            if (live.activationPrice != newActivation || live.trailingActive != st.active || live.trailingExtreme != newExtreme || live.trailingStopPrice != newStop) {
                val prevStop = live.trailingStopPrice
                live = live.copy(
                    activationPrice = newActivation, trailingActive = st.active, trailingExtreme = newExtreme, trailingStopPrice = newStop,
                    trailingActivatedAt = if (step.justActivated) now else live.trailingActivatedAt, updatedAt = now
                )
                dao.updateCycle(live)
                if (step.justActivated) {
                    dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = live.id, symbol = live.symbol, type = "TRAILING_ACTIVATED",
                        message = "Trailing stop activated @ ${fmtPx(mark)} (activation ${fmtPx(newActivation)}). Stop ${fmtPx(newStop ?: 0.0)}.", price = mark, dedupeKey = "trail-act-${live.id}"))
                } else if (prevStop != null && newStop != null && abs(newStop - prevStop) / prevStop >= 0.001) {
                    // Logged only for moves of >= 0.1% so the event ledger is not flooded on every tick.
                    dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = live.id, symbol = live.symbol, type = "TRAILING_UPDATED",
                        message = "Trailing stop moved to ${fmtPx(newStop)} (extreme ${fmtPx(newExtreme ?: 0.0)}).", price = mark, dedupeKey = "trail-upd-${live.id}-${now}"))
                }
                upsertCycleTrailingOrderLocked(live, now)
            }
            if (step.exitTriggered) {
                // Simulation exit price: the observed price that crossed the stop (conservative on gaps).
                executeCycleTrailingExitLocked(context, live, mark, now)
                return
            }
        }
        // Individual SLs
        for (entry in dao.getOpenFilledEntries(cycle.id)) {
            val sl = entry.slPrice ?: continue
            val hitSl = if (cycle.direction == "LONG") mark <= sl else mark >= sl
            if (hitSl) {
                closeSingleEntryLocked(dao.getCycle(cycle.id) ?: cycle, entry, sl, "SL", now, "sl-${entry.id}-${entry.slOrderId}")
            }
        }
        // Averaging trigger (max 1) - place LIMIT averaging entry if reached and not done/pending
        maybeTriggerAveragingLocked(context, dao.getCycle(cycle.id) ?: cycle, mark, now)
        // Keep trailing order quantity in sync
        val c2 = dao.getCycle(cycle.id) ?: return
        if (c2.status == "ACTIVE") upsertCycleTrailingOrderLocked(c2, now)
        val remaining = dao.getOpenFilledEntries(cycle.id).sumOf { it.remainingQuantity }
        if (remaining <= 1e-12 && dao.getPendingEntries(stage.id).none { it.cycleId == cycle.id }) {
            finalizeIfEmptyLocked(c2, mark, now, c2.closeReason ?: "COMPLETED")
        }
    }

    private suspend fun maybeTriggerAveragingLocked(context: Context, cycle: PaperCycleEntity, mark: Double, now: Long) {
        // Averaging LIMIT is auto-created immediately after initial entry fill.
        // Safe retry in case placement was skipped (e.g. insufficient balance) earlier.
        placeAveragingLimitIfNeededLocked(context, cycle, now)
        // Independent retry of the opposite-side preparation (e.g. it was blocked by an open opposite cycle).
        placeOppositeLimitIfNeededLocked(context, dao.getCycle(cycle.id) ?: cycle, now)
    }

    /**
     * Auto-create same-direction Averaging LIMIT order (max 1/cycle) at the price where Position ROI equals the cycle's
     * Averaging Trigger ROI (negative, from the settings snapshot) as soon as the initial entry is filled.
     */
    private suspend fun placeAveragingLimitIfNeededLocked(context: Context, cycle: PaperCycleEntity, now: Long) {
        if (!StrategyRules.averagingEligible(cycle.averagingEnabled, cycle.averagingDone)) return
        if (cycle.status !in listOf("ACTIVE", "PENDING_ENTRY")) return
        val entries = dao.getEntries(cycle.id)
        if (entries.any { it.entryType == "AVERAGING" && (it.status == "PENDING" || it.status == "FILLED" || it.status == "PARTIALLY_FILLED") }) return
        val hasInitialFilled = entries.any { it.entryType == "INITIAL" && it.status == "FILLED" }
        if (!hasInitialFilled) return
        val avg = cycle.averageEntryPrice ?: run {
            val open = entries.filter { it.status == "FILLED" && it.remainingQuantity > 1e-12 }
            val q = open.sumOf { it.remainingQuantity }; if (q > 1e-12) open.sumOf { (it.fillPrice ?: 0.0) * it.remainingQuantity } / q else null
        } ?: return
        val trigger = RoiMath.priceAtRoi(TrailSide.fromDirection(cycle.direction), bd(avg), cycle.leverage, bd(cycle.averagingTriggerRoi)).toDouble()
        val wallet = ensureWallet()
        val margin = cycle.averagingMargin
        val lev = cycle.leverage
        val required = margin + (margin * lev * feeRate)
        if (wallet.availableBalance + 1e-9 < required) {
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVERAGING_SKIPPED",
                message = "Averaging LIMIT not placed: insufficient simulated balance.", price = trigger, dedupeKey = "avg-skip-${cycle.id}"))
            return
        }
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - required, reservedMargin = wallet.reservedMargin + margin, updatedAt = now))
        val clientId = "avg-${stage.id}-${cycle.id}"
        val avgEntry = PaperEntryEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, direction = cycle.direction,
            entryType = "AVERAGING", status = "PENDING", margin = margin, leverage = lev, intendedPrice = trigger, fillPrice = null,
            quantity = 0.0, filledQuantity = 0.0, remainingQuantity = 0.0, fee = 0.0, slPrice = null, slOrderId = null, slFixed = false,
            clientOrderId = clientId, fillEventId = null, createdAt = now, filledAt = null, closedAt = null, updatedAt = now, closeReason = null, lastError = null)
        val entryId = dao.insertEntryIgnore(avgEntry)
        val resolvedEntryId = if (entryId > 0) entryId else (entries.firstOrNull { it.entryType == "AVERAGING" && it.clientOrderId == clientId }?.id ?: dao.getEntries(cycle.id).firstOrNull { it.entryType == "AVERAGING" }?.id ?: -1L)
        if (resolvedEntryId <= 0) return
        dao.insertOrderIgnore(PaperOrderEntity(stage = stage.id, cycleId = cycle.id, entryId = resolvedEntryId, symbol = cycle.symbol, direction = cycle.direction,
            side = if (cycle.direction == "LONG") "BUY" else "SELL", orderType = "ENTRY_LIMIT", triggerPrice = trigger, limitPrice = trigger,
            quantity = 0.0, status = "ACTIVE", clientOrderId = clientId, createdAt = now, updatedAt = now))
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVERAGING_PLACED",
            message = "Averaging LIMIT auto-created @ ${fmtPx(trigger)} (Position ROI ${cycle.averagingTriggerRoi}%, 1 per cycle max).", price = trigger, dedupeKey = "avg-placed-${cycle.id}"))
    }
    private suspend fun closeSingleEntryLocked(cycle: PaperCycleEntity, entry: PaperEntryEntity, exitPrice: Double, reason: String, now: Long, idempotencySeed: String) {
        if (entry.status != "FILLED" || entry.remainingQuantity <= 1e-12) return
        val closeQty = entry.remainingQuantity
        val pnl = realizedPnl(entry.direction, entry.fillPrice ?: exitPrice, exitPrice, closeQty) - (entry.fee * (closeQty / max(entry.filledQuantity, 1e-12)) / 2.0)
        val marginPart = entry.margin * (closeQty / max(entry.filledQuantity, 1e-12))
        val pnlPct = if (marginPart > 1e-12) (pnl / marginPart) * 100.0 else 0.0
        val idem = "${stage.id}|${cycle.id}|${entry.id}|$reason|$idempotencySeed"
        dao.insertHistoryClose(PaperHistoryCloseEntity(
            stage = stage.id, cycleId = cycle.id, entryId = entry.id, symbol = cycle.symbol, direction = cycle.direction,
            entryFillPrice = entry.fillPrice ?: 0.0, exitFillPrice = exitPrice, entryMargin = marginPart, leverage = entry.leverage,
            closedQuantity = closeQty, realizedPnlUsdt = pnl, realizedPnlPercent = pnlPct, closingReason = reason,
            entryTimestamp = entry.filledAt ?: entry.createdAt, exitTimestamp = now, idempotencyKey = idem
        ))
        val wallet = ensureWallet()
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance + marginPart + pnl, reservedMargin = max(wallet.reservedMargin - marginPart, 0.0), realizedPnl = wallet.realizedPnl + pnl, updatedAt = now))
        // Cancel+verify this entry's SL
        entry.slOrderId?.let { slId ->
            dao.getOrder(slId)?.let { o -> if (o.status in setOf("ACTIVE", "CANCELLING")) dao.updateOrder(o.copy(status = "CANCEL_VERIFIED", cancelRequestedAt = o.cancelRequestedAt ?: now, cancelVerifiedAt = now, updatedAt = now)) }
        }
        dao.updateEntry(entry.copy(status = "CLOSED", remainingQuantity = 0.0, closeReason = reason, closedAt = now, updatedAt = now))
        val remaining = dao.getOpenFilledEntries(cycle.id).sumOf { it.remainingQuantity }
        dao.updateCycle((dao.getCycle(cycle.id) ?: cycle).copy(totalRemainingQuantity = remaining, realizedPnl = ((dao.getCycle(cycle.id)?.realizedPnl ?: 0.0) + pnl), updatedAt = now, status = if (remaining <= 1e-12) cycle.status else "ACTIVE"))
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "ENTRY_CLOSED",
            message = "Entry #${entry.id} ${entry.entryType} closed by $reason @ ${fmtPx(exitPrice)}. PNL ${fmt(pnl)} USDT. Other entries and the cycle trailing stop are unaffected (unless the cycle completed).",
            price = exitPrice, dedupeKey = "close-$idem"))
    }

    /** Cycle Trailing Stop exit: closes ALL remaining quantity of this cycle (and only this cycle). */
    private suspend fun executeCycleTrailingExitLocked(context: Context, cycle: PaperCycleEntity, exitPrice: Double, now: Long) {
        dao.getActiveTrailingForCycle(cycle.id)?.let { dao.updateOrder(it.copy(status = "FILLED", filledPrice = exitPrice, filledQuantity = it.quantity, executedAt = now, updatedAt = now)) }
        val entries = dao.getOpenFilledEntries(cycle.id)
        var totalPnl = cycle.realizedPnl ?: 0.0
        for (entry in entries) {
            val closeQty = entry.remainingQuantity
            if (closeQty <= 1e-12) continue
            val pnl = realizedPnl(entry.direction, entry.fillPrice ?: exitPrice, exitPrice, closeQty) - (entry.fee * (closeQty / max(entry.filledQuantity, 1e-12)) / 2.0)
            val marginPart = entry.margin * (closeQty / max(entry.filledQuantity, 1e-12))
            val pnlPct = if (marginPart > 1e-12) (pnl / marginPart) * 100.0 else 0.0
            val idem = "${stage.id}|${cycle.id}|${entry.id}|TRAILING|cycle-trailing-$now"
            dao.insertHistoryClose(PaperHistoryCloseEntity(stage = stage.id, cycleId = cycle.id, entryId = entry.id, symbol = cycle.symbol, direction = cycle.direction,
                entryFillPrice = entry.fillPrice ?: 0.0, exitFillPrice = exitPrice, entryMargin = marginPart, leverage = entry.leverage, closedQuantity = closeQty,
                realizedPnlUsdt = pnl, realizedPnlPercent = pnlPct, closingReason = "TRAILING", entryTimestamp = entry.filledAt ?: entry.createdAt, exitTimestamp = now, idempotencyKey = idem))
            totalPnl += pnl
            val wallet = ensureWallet()
            dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance + marginPart + pnl, reservedMargin = max(wallet.reservedMargin - marginPart, 0.0), realizedPnl = wallet.realizedPnl + pnl, updatedAt = now))
            dao.updateEntry(entry.copy(status = "CLOSED", remainingQuantity = 0.0, closeReason = "TRAILING", closedAt = now, updatedAt = now))
        }
        // Cancel ALL remaining individual SL orders and VERIFY
        dao.requestCancelAllSlForCycle(cycle.id, "CANCELLING", now)
        dao.verifyCancelAllSlForCycle(cycle.id, now)
        val unverified = dao.countUnverifiedActiveSlForCycle(cycle.id)
        if (unverified > 0) {
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "SL_CANCEL_UNVERIFIED",
                message = "Cycle trailing exit executed but $unverified SL order(s) could not be verification-confirmed cancelled; retry required.", price = exitPrice, dedupeKey = "sl-unv-${cycle.id}-$now"))
        }
        // Cancel unfilled entry orders of THIS cycle (e.g. an unfilled averaging LIMIT) and release their reserved margin
        dao.getEntries(cycle.id).filter { it.status == "PENDING" }.forEach { cancelPendingEntryLocked(it, now, "CYCLE_CLOSED") }
        val closed = (dao.getCycle(cycle.id) ?: cycle).copy(status = "CLOSED", closePrice = exitPrice, realizedPnl = totalPnl, closeReason = "TRAILING_STOP", closedAt = now, updatedAt = now, totalRemainingQuantity = 0.0)
        dao.updateCycle(closed)
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "CYCLE_TRAILING_EXIT",
            message = "Cycle trailing stop exit @ ${fmtPx(exitPrice)}. Remaining qty closed. All remaining entry SL orders cancelled and verified. PNL ${fmt(totalPnl)} USDT.",
            price = exitPrice, dedupeKey = "trail-exit-${cycle.id}-$now"))
        cancelUnfilledOppositeOfLocked(cycle.id, now)
        if (cycle.reentryEnabled) createReentryPlansLocked(closed, exitPrice, now)
    }

    /**
     * After a profitable (trailing-stop) cycle close: prepare BOTH a LONG (below the close) and a SHORT (above the close) re-entry,
     * each at Re-entry ROI = Activation ROI / 2 Position-ROI distance from the closing price (leverage included).
     */
    private suspend fun createReentryPlansLocked(cycle: PaperCycleEntity, closePrice: Double, now: Long) {
        if (dao.getReentryPlansForSource(cycle.id).isNotEmpty()) return
        val roi = RoiMath.reentryRoi(bd(cycle.activationRoi))
        for (dir in listOf("LONG", "SHORT")) {
            val price = RoiMath.reentryPrice(TrailSide.fromDirection(dir), bd(closePrice), cycle.leverage, roi).toDouble()
            dao.insertReentryPlan(PaperReentryPlanEntity(stage = stage.id, sourceCycleId = cycle.id, symbol = cycle.symbol, direction = dir, leverage = cycle.leverage,
                margin = cycle.initialMargin, referenceClosePrice = closePrice, triggerPrice = price, status = "WAITING",
                clientPlanId = "re-${stage.id}-${cycle.id}-$dir", createdAt = now, updatedAt = now))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "REENTRY_WAITING",
                message = "Re-entry $dir waiting @ ${fmtPx(price)} (Re-entry ROI ${roi.stripTrailingZeros().toPlainString()}% = Activation ROI/2, ${cycle.leverage}x, from close ${fmtPx(closePrice)}). Becomes a NEW cycle; the other side is cancelled when one fills.",
                price = price, dedupeKey = "re-wait-${cycle.id}-$dir"))
        }
    }

    private suspend fun processReentryPlanLocked(context: Context, plan: PaperReentryPlanEntity, mark: Double, now: Long) {
        if (plan.status !in setOf("WAITING", "TRIGGERED")) return
        val reached = if (plan.direction == "LONG") mark <= plan.triggerPrice else mark >= plan.triggerPrice
        if (!reached && plan.status == "WAITING") return
        val updated = plan.copy(status = "TRIGGERED", triggeredAt = plan.triggeredAt ?: now, updatedAt = now)
        dao.updateReentryPlan(updated)
        val res = placeInitialLimitLocked(context, plan.symbol, plan.direction, plan.margin, plan.leverage, plan.triggerPrice, sourceType = "REENTRY", sourceCycleId = plan.sourceCycleId)
        if (res.ok) {
            dao.updateReentryPlan(updated.copy(status = "ORDER_PLACED", newCycleId = res.cycleId, entryOrderId = res.orderId, updatedAt = now))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = res.cycleId, symbol = plan.symbol, type = "REENTRY_PLACED",
                message = "Re-entry LIMIT placed as NEW cycle #${res.cycleId} @ ${fmtPx(plan.triggerPrice)} (source cycle #${plan.sourceCycleId}).",
                price = plan.triggerPrice, dedupeKey = "re-place-${plan.id}"))
        } else {
            dao.updateReentryPlan(updated.copy(status = "WAITING", lastError = res.message, updatedAt = now))
        }
    }

    private suspend fun finalizeIfEmptyLocked(cycle: PaperCycleEntity, mark: Double, now: Long, reason: String) {
        val pending = dao.getEntries(cycle.id).any { it.status == "PENDING" }
        if (pending) return
        if (cycle.status == "CLOSED") return
        dao.requestCancelAllSlForCycle(cycle.id, "CANCELLING", now)
        dao.verifyCancelAllSlForCycle(cycle.id, now)
        dao.getActiveOrdersForCycle(cycle.id).forEach { dao.updateOrder(it.copy(status = "CANCEL_VERIFIED", cancelVerifiedAt = now, updatedAt = now)) }
        val realized = cycle.realizedPnl ?: 0.0
        dao.updateCycle(cycle.copy(status = "CLOSED", closeReason = reason, closePrice = mark, closedAt = now, updatedAt = now, totalRemainingQuantity = 0.0, realizedPnl = realized))
        cancelUnfilledOppositeOfLocked(cycle.id, now)
        if (reason == "TRAILING_STOP" && cycle.reentryEnabled) createReentryPlansLocked(cycle, mark, now)
    }

    

    private suspend fun ensureWallet(): PaperWalletEntity = dao.getWallet(stage.id) ?: PaperWalletEntity(stage = stage.id).also { dao.upsertWallet(it) }

    private fun assertSimulatedExecutionAllowed() {
        check(stage == TradingStage.STAGE_1) {
            "Execution blocked: ${stage.title} is not available. Only Stage 1 (Virtual) is implemented; Stage 2 and Stage 3 need separate approval."
        }
    }

    companion object {
        fun calcSl(direction: String, avg: Double, pct: Double): Double = if (direction == "LONG") avg * (1 - pct / 100.0) else avg * (1 + pct / 100.0)
        fun realizedPnl(direction: String, entry: Double, exit: Double, qty: Double): Double = if (direction == "LONG") (exit - entry) * qty else (entry - exit) * qty
        fun weightedAvg(entries: List<Pair<Double, Double>>): Double {
            val qty = entries.sumOf { it.second }
            if (qty <= 1e-12) return entries.lastOrNull()?.first ?: 0.0
            return entries.sumOf { it.first * it.second } / qty
        }
        fun liquidationPrice(direction: String, entry: Double, leverage: Int): Double {
            // Conservative isolated-margin simulation approximation (includes fee/buffer). Not exchange-identical.
            val lev = LeverageConfig.requireSupported(leverage)
            val move = (1.0 / lev.toDouble()) * 0.93
            return if (direction == "LONG") entry * (1.0 - move) else entry * (1.0 + move)
        }
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
    private fun fmtPx(v: Double) = String.format(java.util.Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    private fun fmtQty(v: Double) = String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
}
