# BNB – Binance Android Bot (Stage 1 / Virtual only)

BNB is an Android app that simulates a USDⓈ-M Futures strategy on a **virtual 5000 USDT wallet (matches the Binance Demo balance for the later stage)** using **live public Binance market data**.

> **Safety boundary:** This build is **Stage 1 (Virtual) only**. It uses public, unauthenticated Binance endpoints. There are **no API keys, no signing, no private endpoints and no real orders**. Stage 2 (Demo) and Stage 3 (Live) are **not authorized and not available**; unknown or stored stage values fail closed to Stage 1.

## Strategy rules implemented
- **No fixed/general Take Profit anywhere.** Profit exit = **ROI cycle Trailing Stop** on the cycle's Weighted Average Entry Price `W` with leverage `L`, activation ROI `A` (default 33), callback ROI `C` (default 3):
  - LONG: activation `W·(1 + A/(100·L))`, stop `H·(1 − C/(100·L))`
  - SHORT: activation `W·(1 − A/(100·L))`, stop `Lo·(1 + C/(100·L))`
  - Stop is monotonic (LONG never falls, SHORT never rises); closing exits **all** remaining quantity of the cycle.
- **One Stop Loss per whole cycle** (Dashboard section *Stop Loss*: Enable + Stop Loss ROI, default **80** = −80% Position ROI, leverage included, measured on the cycle WAP). When hit, all remaining quantity is closed, the cycle's pending orders are cancelled and the close is recorded in History (reason `SL`). Disabled = no Stop Loss at all. There is no per-entry Stop Loss. Each cycle is independent.
- All entries are **LIMIT**. One cycle per symbol + direction; LONG and SHORT are independent.
- **Position ROI** is the single measure used everywhere (trailing, re-entry, averaging, opposite-side): `ROI = ±(P − W)/W · L · 100`, i.e. leverage included; never raw price % and never account equity (`trading/RoiMath.kt`).
- **Averaging** (D): one LIMIT per cycle at the price where Position ROI = *Averaging Trigger ROI* (default **−60**). WAP and trailing activation are recalculated from the new WAP (an already-active trail is kept, never loosened).
- **Opposite-Side** (E): controlled only by its own Enable toggle, with its own margin, *Trigger ROI* (default **−50**) and *Callback ROI* (default **5**). When the position's ROI reaches the trigger it activates, then trails the adverse extreme; when price reverses by the callback ROI, an opposite-direction LIMIT entry is placed as a separate cycle (own margin, leverage, Stop Loss, trailing, snapshot). Only if no opposite-direction cycle is open; hedge-born cycles do not spawn further opposite cycles.
- **Re-entry** (C): after a **profitable** trailing-stop close, Re-entry ROI is based on the **ACTUAL booked Position ROI** of that cycle (WAP vs final close, leverage included) — no longer Activation ROI / 2. **Same-Side = 50 %**, **Opposite-Side = 100 %** of the booked ROI (ROI distances, converted to price with the leverage-aware `RoiMath.reentryPrice`). LONG cycle: Same-Side LONG below the close, Opposite-Side SHORT above it. SHORT cycle: Same-Side SHORT above the close, Opposite-Side LONG below it (direction is never reversed). Booked ROI ≤ 0 → no re-entry. The two orders are **independent**: a fill starts a new cycle and does **not** cancel the other order. Example (6x, booked 30 %): same-side ROI 15 % = 2.5 % price distance, opposite-side ROI 30 % = 5 %.
- **Global Best Entry Price Priority** (`trading/EntryPriority.kt`, one service for ALL entry creation paths): key = symbol + direction. LONG → lowest pending entry price wins; SHORT → highest wins; equal price → existing order kept. Manual, Averaging, Opposite-Side, Re-entry (same) and Re-entry (opposite) all compete. Max one pending entry per symbol+direction; legacy duplicates are compared against the best and all worse ones are replaced. Replacement sequence: per-key lock → fresh read → compare → feasibility (balance) → cancel old → verify no fill/cancelled → place new. Cancel failure → nothing placed; old order filled meanwhile → position kept, nothing placed; place failure → no false success. A rejected candidate is stored as REJECTED (never FILLED/PENDING) and is not retried every tick (one averaging attempt per cycle; a rejected opposite-side attempt is final). SL / Trailing / exit orders are never entry orders and are not compared.
- **Log events**: ENTRY_PRIORITY_CHECK / _ACCEPTED / _REJECTED, ENTRY_ORDER_REPLACEMENT_STARTED, ENTRY_ORDER_CANCELLED, ENTRY_ORDER_REPLACED, ENTRY_ORDER_REPLACEMENT_FAILED, ENTRY_ORDER_FILLED_DURING_REPLACEMENT, REENTRY_FORMULA_CALCULATED, REENTRY_ORDER_CREATED (no secrets).
- Trigger ROI inputs are stored negative (30 and −30 both become −30) and must lie between −(Stop Loss ROI) and 0 (up to −93 when the Stop Loss is disabled).
- Leverage is limited to 3x–20x. No percentage is derived from a leverage table any more.
- LONG and SHORT are **always** permitted; direction is not a setting.
- Settings are snapshotted when a cycle is created; later changes never alter running cycles.

## App structure
Bottom tabs: **Home, Trade, Positions, Settings, More** ("Trade" = the former Manual tab, where entries are placed).
- Home: Wallet Balance card on top; below it Live Prices with four sub-tabs (Favorites, Volume, Gainers, Losers) and a Symbol / Price / 24h Vol / 24h % table.
- Colour scheme follows the Binance app (dark #0B0E11 background, yellow #FCD535 accent, green #0ECB81 / red #F6465D).
- Positions: *Open / Closed*; Open Orders: *Basic / Conditional*; History: *Open / Closed*.
- Settings = central Dashboard (sections A–F) with Global defaults and per-symbol overrides, each labelled **Global / Inherited / Symbol Override**.
- **Saved Coin Settings** (bottom of Settings): one card per symbol with custom settings, with *Edit* and *Delete*. Both are **locked** while the symbol has any open cycle/position, pending entry, SL/trailing order or re-entry order. The lock is enforced in the engine (`PaperEngine.updateSymbolSettings / deleteSymbolSettings / resetSymbolSetting`), not only in the UI. Existing cycles keep their own settings snapshot and are never altered by edits or deletes.
- More: status, pause/resume, cancel pending orders, emergency stop, panic close (simulated), full virtual reset, sanitized event log.

## Code map (`app/src/main/java/com/ansardeo/bnb`)
- `trading/TrailingStopMath.kt` – pure BigDecimal trailing logic (unit tested).
- `paper/` – Room database (v5), simulation engine, price monitor loop, `PaperMonitorService` (foreground service), pause control.
- `data/` – leverage table, settings + resolver, stage model, public market repository.
- `MainActivity.kt` – UI.

## Build
Open in Android Studio, or `gradle assembleDebug testDebugUnitTest`. GitHub Actions (`.github/workflows/android-apk.yml`) builds the debug APK, runs unit tests and uploads the APK (`BNB-APK-debug`) and test reports.

## Known limitations / notes
- This source was prepared without a Kotlin compiler available; **the first GitHub Actions run is the real verification** (compile + tests). Nothing here claims a successful build.
- Room schema is **v5**. The v3 → v4 and v4 → v5 migrations are **non-destructive** (new snapshot columns with safe defaults; open cycles that existed before keep their old behaviour; legacy per-entry SL orders are retired). Only the legacy v1/v2 (fixed take-profit) schemas are cleared once.
- Opposite-side orders are conditional-at-price entries (they fill when the price level is reached), an interpretation of "prepare a LIMIT entry at the trigger ROI"; please confirm it matches your intent.
- Money fields in Room entities are still `Double` (calculations of the trailing logic use BigDecimal).
- Trailing exit is simulated at the observed mark price (polling), not tick-accurate.
- No instrumented/UI tests yet.


## Database / migration
Room DB version 5. Migrations 3→4 and 4→5 are non-destructive (new columns with defaults; legacy per-entry SL orders are retired; open cycles keep their old behaviour). Only versions 1–2 fall back to a destructive rebuild.

## Verification status
The Kotlin code was written without a compiler or Gradle available in the authoring environment, so it has **not** been compiled or test-run there. The first GitHub Actions run (`assembleDebug` + unit tests) is the real verification; if a test fails, send the failing test names.

## Background monitoring (foreground service)
- `PaperMonitorService` is an Android foreground service (type *specialUse*) that owns the price-monitor loop. It is independent of MainActivity: turning the screen off, switching apps or closing/re-creating the Activity does not stop it. It uses the same Stage 1 virtual engine and public Binance market data only; there are no real orders, API keys or secrets.
- A persistent notification is shown while it runs, with **Pause/Resume** (the same flag as *More → Pause bot*; paused = simulation frozen, service stays alive) and **Stop monitoring**. *More → Background monitoring* has a switch and a shortcut to the battery settings.
- Start requests are idempotent (`MonitorLifecycle`): repeated starts never create a second loop. The service is `START_STICKY`, so Android may restart it after the process was killed.
- Android 13+: the notification permission is requested on first launch. If it is denied the service still runs, but its notification is only visible in the system task manager.
- **Limitations (not guaranteed):** Android *Force Stop*, the user turning monitoring off, a switched-off/offline phone, and aggressive battery managers of some manufacturers stop monitoring; there is no boot receiver, so monitoring resumes only after the app is opened again. If monitoring stops, allow BNB to run unrestricted in the battery settings. While monitoring is not running, nothing in the virtual account moves.

## Mark Price vs Last Price
- **Mark Price** (Binance `premiumIndex`, `markPrice`): liquidation, Position ROI / unrealized PNL, cycle Stop Loss, trailing stop, averaging / opposite-side triggers, re-entry plan triggers, panic close.
- **Last Price** (`ticker/24hr` / `ticker/price`, `lastPrice`): pending LIMIT entry fills, price lists, distance to the 24h/7d high/low, the suggested entry price.
- `Ticker.markPrice` is `null` when Binance did not deliver a Mark Price; it is never filled with the last price, and a missing price is never replaced by the other one (the affected calculation waits for the next tick).

## Market statistics windows
All windows are anchored to the Binance server time (`/fapi/v1/time`); an incomplete or stale window gives "--" instead of an inaccurate value.
- 24h High/Low and 24h Volume / Change: Binance rolling 24-hour ticker.
- 24h RSI: 24 consecutive **closed 1h** candles (25 closes, 24 changes), RSI period 24.
- 7d RSI: 42 consecutive **closed 4h** candles (43 closes, 42 changes = 7 days), RSI period 42.
- 7d High/Low: every **15m** candle opened within the last 7 days (the window start is covered to within one 15m candle).

## Testing status
Unit tests (plain JVM) cover the ROI maths, opposite-side trigger, statistics windows, Mark/Last mapping, settings, migrations SQL and the monitor start/stop logic. The Foreground Service itself, notifications, Room migrations on real data and the UI have **not** been runtime-tested on a device or emulator. A successful GitHub Actions build proves compilation and the unit tests only.

## Settings Dashboard layout
Compact tiles instead of long forms: Default Trading Settings (full width), Stop Loss | Trailing Stop, Re-entry | Averaging, Opposite-Side (full width). Each tile shows its Enable switch and a live summary of the key values (dimmed when disabled; ● = custom value for the selected symbol). Tapping a tile expands its editor inline (the tile becomes full width), with the explanatory text, the per-field source tag ([Global]/[Custom]) and RESET. Values keep their content when a tile is collapsed. Saved Coin Settings is a collapsed list at the bottom. All rules (validation, lock, snapshots, delete confirmation) are unchanged.

## Stage 1 — characterization tests (no behaviour change)
`app/src/engineTest/java/.../paper/engine/EngineCharacterizationTest.kt` records how the CURRENT Stage 1 (virtual) `PaperEngine` behaves (Robolectric + in-memory Room): placement/cancel, Last-vs-Mark fills, fill → trailing/SL/averaging orders, averaging WAP, trailing exit + booked-ROI re-entry plans (same 50 % / opposite 100 %), independent re-entry fills, cycle Stop Loss (current behaviour, to be replaced by per-entry SL in a later approved stage), opposite-side trigger, global entry price priority, pause, Stage 2/3 blocked.
These tests are compiled/run only with `-PengineTests` (so the normal build is untouched):
`gradle testDebugUnitTest -PengineTests --tests 'com.ansardeo.bnb.paper.engine.*'` — the CI workflow runs this as a separate, informational step. Expected values were derived by hand and have NOT been executed yet (no build tooling in the authoring environment); the first CI run is the real verification.

## Stage 2 — Binance Demo API configuration (More tab) — NO orders
- **More → "BINANCE DEMO API"**: Save / Replace keys, Test connection (read-only), Delete keys, status + step-by-step result.
- **Storage**: API Key and Secret are AES-256-GCM encrypted with a non-exportable Android Keystore key; only a masked hint (first 4 / last 4) is readable. The secret is never shown, logged or returned to the UI; the key dialog blocks screenshots (FLAG_SECURE). Delete removes the data and the Keystore key. `allowBackup=false`.
- **Demo only**: `demo/DemoHost.kt` allows only `demo-fapi.binance.com` / `demo-fstream.binance.com` (https, port 443, no user-info) in an OkHttp application AND network interceptor, redirects disabled. There is no setting for another host and no Live code. Static tests fail the build if the demo package references a Live/testnet host or any order/state-changing endpoint.
- **Read-only test** (`BinanceDemoApi`): `GET /fapi/v1/time`, `GET /fapi/v2/balance` (signed), `GET /fapi/v1/positionSide/dual` (signed, informational). Timestamps are corrected by the measured clock skew. Nothing else is called; no orders, no leverage/margin/mode changes, no listen key.
- Whether Binance Demo supports each of these endpoints is **not yet proven**; the Test Connection result on your phone is the first real evidence.

## Phase 3A — Binance Demo execution (steps 1–2: Trade API foundation + Compatibility Probe)
- `demo/trade/` : approved-endpoint catalog, Demo-only signed trade client (LIMIT/GTC and STOP_MARKET-on-mark only), exchange rules, Compatibility Probe.
- More tab → "BINANCE DEMO COMPATIBILITY PROBE": places a far LIMIT order and a far Stop Loss order on Demo (BTCUSDT), cancels both, opens no position, changes no account setting, produces a copyable sanitized report.
- Trading stays on Virtual. Strategy automation on Demo (Phase 3B) is not part of this step.

### Phase 3A step 3 — Demo safety setup (per symbol)
- `demo/trade/SymbolSetup.kt`: reads Hedge Mode (never changed), exchange rules, idle state; sets ISOLATED margin and the configured leverage (3x-20x) only while the symbol has no position/order/Stop Loss; reads both back from Binance Demo. Reports READY only after Binance confirms.
- `ExchangeRules.kt`: `OrderPrep` (tick/step rounding that is never more aggressive, min qty / min notional / price band) and `RulesCache`.
- More tab → "BINANCE DEMO SAFETY SETUP (BTCUSDT)". No orders are placed in this step.

### Phase 3A steps 4+5 (Round A) — Demo LIMIT orders with their own Stop Loss
- `demo/exec/`: `DemoExecutionService` (place LIMIT, track fills, cancel, per-entry Stop Loss placed + verified, fail-safe, halt), `DemoEntry`, `DemoExecStore` (own SQLite file `bnb_demo_exec.db`; the Virtual database is untouched).
- A Stop Loss is shown ACTIVE only after Binance Demo confirmed it (queried back, status NEW). One retry, then the unprotected quantity is closed at market and new entries are halted until the user clears the halt.
- First fill ends the order (rest cancelled), so one Stop Loss covers a fixed quantity. MARKET orders exist only as a closing-only method (SELL on LONG / BUY on SHORT).
- Known gap (unchanged): an entry that fills while the app is closed is protected only when the app next runs (start-up refresh).
- More tab → "BINANCE DEMO ORDERS (BTCUSDT)". No strategy automation; Virtual stays the default.
