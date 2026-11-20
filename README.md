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

### Phase 3A Round B (steps 6+7) — close position, sync, Positions tab, mode toggle
- `DemoExecutionService.closePosition()`: closes an entry's open quantity at market (closing-only method, same restriction as the fail-safe), then removes its Stop Loss once nothing is left to protect. Serialized by the same mutex as every other state change.
- Positions tab gets a Virtual/Demo toggle (display only — it never changes what the Virtual engine does, and does not enable any Demo strategy automation). Demo view: open entries with CANCEL ORDER / CLOSE POSITION, closed history, and the event log.
- `TradingModeStore` / `TradingModePrefs`: the Demo view is locked until Demo API keys are saved AND the Compatibility Probe passed (Stop Loss confirmed supported + cleaned up). Virtual stays the default and switching back to Virtual is always allowed.
- Sync: the existing start/5s poll (`startDemoPoll`) already resolves unknown orders, tracks fills, places/verifies Stop Losses and checks for a lost Stop Loss; it runs again whenever the app is foregrounded (onStart), covering "sync on start/reconnect" for everything the app itself placed. A position opened directly on Binance (not through this app) is outside this app's tracking, as before.

### Fix — cancel confirmation retried (Binance Demo real-device finding)
Binance Demo can answer a Stop Loss / order cancel as accepted before its status actually flips; the Compatibility Probe and `closeEntryAtMarket` now re-check up to 4 times (short backoff) before concluding a cancel was not confirmed, instead of failing on the first stale read.

### Fix — Demo Positions tab now uses the same Position / Open Order / History layout as Virtual
The Demo panel previously showed a flat text list; it now shares the exact tab structure Virtual uses (Position → Open/Closed, Open Order → Basic/Conditional, History → Open events/Closed), with the same per-item card layout (symbol, direction, fill price, quantity, Stop Loss line, Close/Cancel buttons). Virtual's own rendering and data were not changed.

## Phase 3A status (Step 8 — hardening + final review)

**Done and confirmed on a real Binance Demo account by the user (not just unit tests):**
- Stage 2: Demo API keys saved (Android Keystore AES‑256‑GCM), read-only connection test.
- Step 1–2: Demo trade client (approved-endpoint-only) + Compatibility Probe — LIMIT place/query/list/cancel and Stop Loss (algo STOP_MARKET) place/query/list/cancel all confirmed SUPPORTED, no position opened.
- Step 3: Safety Setup — ISOLATED margin + configured leverage set and read back from Binance Demo; Hedge Mode only ever read, never changed.
- Steps 4–5: Demo LIMIT entries; a fill gets its own exchange-side Stop Loss computed from the actual fill price, placed and verified as ACTIVE only after Binance Demo confirms it; fail-safe (market-close + halt) exercised live after a real failure (avgPrice 0 in the new-order answer) and fixed.
- Steps 6–7: CLOSE POSITION (market, closing-only) + Stop Loss cleanup; Positions tab now shows Demo the same way as Virtual (Position/Open Order/History, same per-item cards); mode toggle locked until keys are saved and the probe passed.
- Real-device fixes applied during this phase: coroutine-in-lambda compile error, `exchangeInfo` timeout on a large response, flaky retry test, a method name (`closePosition`) that collided with a banned-string security test, fill price sometimes reported as 0 by Binance (now falls back to a re-query / cumQuote), and cancel status needing a short retry before Binance Demo reflects it.

**Verified by static/unit tests (listed in this README's earlier sections); not independently re-run in this review beyond re-checking their banned-string/endpoint-catalog logic by hand against the current source.**

**Known limitations (by design / deferred, not bugs):**
1. If the app is closed at the moment an entry order fills, its Stop Loss is placed only when the app is next opened (the poll loop). The user has decided to leave this as-is for Phase 3A and revisit it at the real-money/server stage.
2. Several Demo entries on the same symbol and direction each get their own Stop Loss sized to their own quantity; if their quantities overlap on the exchange's single net position, the Stop Losses are not merged into one. This matches the approved per-entry-SL design.
3. The app only tracks orders/positions it placed itself. An order or position opened directly on Binance (outside the app) is not shown or managed here.
4. Demo strategy automation (priority, averaging, opposite entries, re-entry, trailing exit) is **not implemented** — every Demo order in Phase 3A is placed manually from the app. That is Phase 3B and needs separate approval.
5. Live (real-money) trading is **not implemented and not reachable from this app** — Stage 3 has not been started.

**Not changed in Phase 3A:** the Virtual engine, the trading strategy rules, the Dashboard, and Settings.

## Phase 3B — Strategy automation on Binance Demo (one merged step)

**Binance Demo only. Real trading is NOT part of this and is not authorized.** No Live endpoint, Live key or real-money code was added.

What it does (only while the app is open AND the **Demo Auto-Trading** switch is ON; the switch defaults to OFF and is found on the Binance Demo Orders card):
- **Entries** go through the approved Global Best Entry Price Priority (`EntryPriorityService`; LONG lowest wins, SHORT highest wins, per symbol + direction, safe replace sequence). Each entry starts or joins a *cycle* (`demo/exec/DemoCycle.kt`).
- **Per-entry Stop Loss** is unchanged from Phase 3A (own SL from its own fill price, verified by Binance before it is called active, fail-safe + halt otherwise). There is no cycle-level Stop Loss and no fixed Take Profit.
- **Averaging**: once per cycle when the cycle Position ROI (on its weighted average entry) reaches the Averaging trigger; one LIMIT entry at the current Demo mark price, with its own Stop Loss. The "done" flag is set before the order is sent, so it can never repeat.
- **Opposite-Side**: ROI trigger + callback trail (`OppositeTrigger`), then one opposite LIMIT entry at the current mark that starts its own cycle.
- **Profit exit**: cycle ROI Trailing Stop (`TrailingStopMath`) on the cycle WAP. When it fires, pending orders of the cycle are cancelled and every open entry is closed at market (closing method only), their Stop Losses are removed and verified.
- **Re-entry**: after a Trailing close with a positive booked ROI: same side 50 %, opposite side 100 % of the booked ROI (`ReentryFormula`, `RoiMath.reentryPrice`). No automatic re-entry after a Stop Loss, fail-safe, manual close or cancel.
- Switch OFF = the engine does nothing (Stop Losses already on Binance stay active). Switch ON/OFF does not affect plain manual entries made while it is OFF (Phase 3A behaviour).

Interpretation decisions (please check): averaging and the opposite entry are LIMIT orders at the mark price when triggered (they may fill at once); booked ROI uses the Demo mark price at the moment the trailing stop fired as the close price; re-entry margin = the coin's Margin Amount; the priority feasibility check is always "feasible" (no balance-aware replacement); only entries placed by this app are known to the priority book; the engine's decisions use the 5-second foreground poll, so a fast move between polls is seen late (Stop Losses on Binance still protect each entry).

Known limits: nothing runs while the app is closed (deferred to the server stage); several entries on the same symbol+direction share Binance's net position; the Virtual engine, Settings and Dashboard are untouched. Tests are written (`demo/engine/DemoStrategyEngineTest.kt`) but were not executed in the authoring environment; they run in the GitHub Actions build.

## Phase 3C — Trailing Stop as an order on Binance Demo (approved: "3C approved")

**Binance Demo only. Real trading is NOT authorized and not implemented.**

- **Probe first**: More tab → "BINANCE DEMO TRAILING STOP PROBE". It places one far-away closing-only Trailing Stop order (activation 10 % above the mark, so it cannot trigger), lists, cancels and verifies it, and opens no position. Only after it **passed** does the strategy use the exchange-side Trailing Stop. Without a passed probe (or if placement fails 3 times) the previous app-side Trailing Stop is used unchanged.
- **Right after the first fill** the cycle's Trailing Stop is placed on Binance Demo: `TRAILING_STOP_MARKET`, closing side only, the cycle's open quantity, MARK_PRICE, activation price = WAP × (1 + Activation ROI / (100 × leverage)) (LONG; mirrored for SHORT), callback = Callback ROI / leverage as a **price** percent rounded to 0.1 % (Binance limits: 0.1–10). It is called ACTIVE only after Binance Demo confirmed it by query. Binance then manages it, also while the app is closed.
- **Averaging / quantity change**: the order is cancelled (verified) and re-placed with the new total quantity and the new WAP-based activation, as long as the trailing has not started. If the price already passed the activation, the order is cancelled and the app-side Trailing Stop continues from the extreme it tracked (it never loosens the stop). The entries' own Stop Losses are never touched.
- **When Binance fires it**: the app (next time it runs) sees the order finished, checks the position on Binance (positionRisk), records the entries as closed **without sending another close**, removes each entry's own Stop Loss (verified), computes the booked ROI and places the Re-entry orders as before. If Binance still shows an open quantity, that quantity is closed at market.
- **Positions tab**: Current Price, Entry Price, Position Quantity, Margin and "Trailing Stop Activation" (with its source: Binance order ACTIVE / calculated / app-side).
- Guards: the static tests now allow exactly one new order type (`TRAILING_STOP_MARKET`, built in one place, closing side only); every other trailing/take-profit/reduceOnly/closePosition usage is still banned.

Interpretation notes: the close price of an exchange-side trailing exit is Binance's fill price when the order query reports one, otherwise the Demo mark price at the moment the app notices; if the app was closed while the order fired, the booked ROI and the Re-entry prices use that later price. A trailing that Binance activated while the app was closed is not known to the app until it runs; a replacement after that restarts the trailing (the app-side takeover only knows the extremes it saw). Tests are written but were not executed in the authoring environment.

### Fix after Phase 3C test: positions closed outside the app
If a position is closed on the Binance app itself, Binance cancels its Stop Loss / Trailing Stop orders and the app used to keep a ghost "open" entry ("Stop Loss LOST", market close "not confirmed"). Now, whenever the Stop Loss is found cancelled, or a market close fails, or a LOST entry is refreshed, the app reads the position from Binance Demo; only if that read succeeds and shows nothing left for this entry (position minus the other open entries of the same symbol+direction) is the entry recorded as "Closed outside the app". No order is sent for that, and no halt is raised. If Binance still holds the position, the old behaviour (Stop Loss LOST + halt, close at market) is unchanged.

### Demo coin selector
The Binance Demo Safety Setup card and the Binance Demo Orders card now have a coin field (type or pick any USDⓈ-M USDT symbol, e.g. ETHUSDT). Run the Safety Setup once per coin (ISOLATED + that coin's leverage from Settings); its report is stored per coin. Orders use that coin's Settings (leverage, Stop Loss ROI, trailing, averaging, ...). The margin typed in the Orders card is now also honoured when Auto-Trading routes the entry through the strategy (before, it used the Settings margin). The probes stay on BTCUSDT. Only BTCUSDT has been verified on Binance Demo so far; other coins are untested.

## Round 1 — Virtual retired from the UI, Trade panel places Demo entries
- The **Trade** tab now sends the LIMIT entry to **Binance Demo** (leverage comes from Settings for the coin; the Safety Setup of that coin must be READY or nothing is sent). With Auto-Trading ON the entry starts a strategy cycle, otherwise it is a plain 3A entry with its own Stop Loss.
- The **Virtual/Demo switch** in the Positions tab is gone: that tab always shows Binance Demo. The top bar reads "STAGE 2 — DEMO".
- **More** page: Demo safety controls — Pause/Resume Auto-Trading, Cancel pending orders, Emergency stop (Auto-Trading OFF + cancel pending; positions stay), Panic close (Auto-Trading OFF, cancel pending, close open Demo positions at market with the approved closing-only order, remove Stop Loss / Trailing orders, no re-entry). Demo events list replaces the Virtual log.
- The Virtual background monitor is no longer started (a running instance is stopped). **Virtual code is kept in the repository** (PaperEngine, database, Home/Settings pieces) until Demo practice is complete. Home, Dashboard, History and Settings still carry Virtual pieces: Rounds 2 and 3.
- No change to the strategy, formulas or Binance endpoints. Real trading is not part of this build.

## Round 2 — Home and History from Binance Demo data
- **Home** wallet card reads the Binance Demo USDT balance (wallet, available, unrealized PnL, equity = wallet + unrealized) plus the Demo position / open-order counts. The Virtual wallet is no longer shown.
- **History → Closed** shows, per closed entry, the realized PnL (net of fees) read from Binance Demo trade history, so positions that Binance closed while the app was closed (Stop Loss, Trailing Stop, manual close on Binance) are included. The figure covers the coin/side in the entry's time window and may include other entries of the same coin/side (stated on screen).
- **Disclosed addition:** one new READ-ONLY signed endpoint, `GET /fapi/v1/userTrades` (catalog is now 18 endpoints; `TradeEndpointCatalogTest` updated for exactly this entry). It cannot place, change or cancel anything.
- Parsing is in the pure `DemoAccountView` (unit tested). No change to strategy, formulas or order endpoints.

### Round 2 fix — Trade no longer depends on the stored Safety Setup report
A later Safety Setup run on a coin that has open Stop Loss orders ends as LOCKED and overwrote the stored READY report, which made the Round 1 Trade gate refuse BTCUSDT. The gate is removed: every Demo entry already re-reads margin type and leverage from Binance and is refused unless ISOLATED and equal to Settings.

## Round 3 — Demo-only application (Virtual trading removed)
- **Removed:** the whole `paper` package (PaperEngine, PaperEngineProvider, PaperMonitorService, PaperMonitor, Room database, migrations), TradingStage / StageStore / TradingModeStore (the Virtual/Demo selector), the Virtual wallet, the Robolectric `engineTest` source set, the Room/kapt dependencies, the foreground-service manifest entries and their permissions. No hidden Virtual path remains (`NoVirtualTradingTest` checks this). The old Virtual database file on a phone is simply no longer opened; nothing of it is read or deleted.
- **Open Positions** are read from Binance Demo `positionRisk` (all symbols). Unrealized PNL and Position ROI use the MARK price; ROI includes leverage. Each card shows Entry/WAP, Mark, Quantity, Position Amount, Margin, Leverage, Liquidation price, Stop Loss status per entry, and the Trailing Activation Price (yellow while waiting, green only on evidence: strategy state persisted across restarts, Binance order status, or the mark price at/through the activation price; unknown stays grey), Trailing Callback and its source.
- **Open Orders → Conditional** has two sub-tabs, Stop Loss and Trailing Stop, filled from `openAlgoOrders` and classified only by the order type Binance reports. Cancel asks Binance, then the list is re-read. Cancelled / finished / rejected orders are not shown.
- **Stop Loss OFF** no longer blocks an entry: it is placed normally, no app-generated Stop Loss follows, the entry records `slOn=false` and is never counted as unprotected. Stop Loss ON behaves as before (own Stop Loss from the actual fill price, shown ACTIVE only after Binance confirms it).
- **Settings** are written through `DemoSettingsService` (same validation and lock rules as before: no change while the coin has an open cycle, pending order or active Stop Loss).
- New read-only calls: `positionRisk`, `openOrders`, `openAlgoOrders` without a symbol (all already in the approved endpoint catalog; the catalog is unchanged at 18).
- What still needs the app to be open: Averaging, Opposite-Side and Re-entry placement, the app-side Trailing fallback, and recovery/reconciliation. Entry LIMIT, Stop Loss and (when the Trailing probe passed) the Trailing Stop are Binance-side orders. Binance has no order type for "place a LIMIT order when my position reaches -X % ROI", so Averaging / Opposite-Side cannot be exchange-side.

## Round 4 — Home Equity correction + two-order Averaging

### Home account card (Binance Demo only)
- **Wallet Balance** and **Available Balance** come from the signed `GET /fapi/v2/balance` (USDT row). Binance's `crossUnPnl` is no longer read (it only covers crossed positions; BNB trades ISOLATED).
- **Total Unrealized PNL** = the sum of Binance's `unRealizedProfit` over every non-zero position of the signed `positionRisk` answer (LONG and SHORT, isolated and cross, each symbol+side counted once). **Equity / Margin Balance** = Wallet Balance + Total Unrealized PNL. `crossUnPnl` is never added, so nothing is counted twice.
- The big card is now titled "EQUITY (MARGIN BALANCE)"; Wallet, Equity, Unrealized PNL, Available and the position/order counts are separate rows.
- If `positionRisk` fails or cannot be read, Equity and Unrealized PNL show **unavailable** (never a wrong number) while the Wallet Balance and Available Balance stay correct. A non-zero position without a readable `unRealizedProfit` also makes the total unavailable.
- The account card now refreshes on every 15-second cycle, independent of whether the public market list loaded. No hardcoded or simulated balance exists.

### Averaging (replaces the single-order Averaging)
- Removed: "Averaging margin (USDT)" and "Averaging Trigger ROI (single, -60)". The saved on/off switch (`averagingEnabled`) is kept. Old saved margin/trigger values are ignored.
- New settings (Global and per coin): **Order 1** allocation 50 % / trigger ROI -25 %, **Order 2** allocation 50 % / trigger ROI -50 %. Total Averaging Margin is not a setting: it is automatically the **actual Initial Entry Margin** of the cycle (filled quantity x fill price / leverage), a separate allocation of the same size. 100 USDT initial -> 50 + 50; 80 -> 40 + 40. Validation: allocations > 0 and add up to 100 %, both triggers negative, inside the Stop Loss ROI limit, Order 1 closer to 0 than Order 2.
- **Prices** come from the Initial Entry **FILL** price, saved once in the cycle (never the requested price, never the WAP): LONG price = fill x (1 + trigger / (100 x leverage)), SHORT mirrored. Example 100 USDT, 10x: -25 % -> 97.50, -50 % -> 95.00. Tick size, quantity step, minimum notional and the percent-price band are applied by the existing order preparation. Averaging prices never move after the first Averaging order fills.
- **Both LIMIT orders are submitted together** (one after the other in the same pass, neither waits for the other) when the cycle's Position ROI first reaches Order 1's trigger. No MARKET order. Each order has its own client id / Binance order id, status, quantity, fill quantity and fill price. States shown per slot: NOT_SUBMITTED, NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED.
- **No duplicates**: the cycle's `avgDone` and per-slot `avg1Sent` / `avg2Sent` flags are saved BEFORE anything is sent, so repeated ticks and app restarts never send the pair again. A slot that could not be placed (e.g. below the minimum notional) is logged and not retried. After a restart the stored entries are re-read from Binance (existing refresh).
- **One order filling never cancels the other** and a rejected order never marks the other filled. Each filled Averaging order gets its own Stop Loss from its own fill price (existing per-entry rule; no cycle-level Stop Loss).
- **Cycle end**: when the cycle closes (Trailing Stop, panic close) its unfilled Averaging orders are cancelled and the cycle is only finished once Binance confirmed the cancellation. If the Initial Entry is closed by its own Stop Loss and nothing is open, the still-pending Averaging orders of that cycle are cancelled. Re-entry orders belong to their own cycles and are never touched.
- **Global Best Entry Price Priority** still applies to Averaging orders against every OTHER pending entry. The only exception: the two Averaging orders of the same cycle do not compete with each other (otherwise the better-priced Order 2 would replace Order 1). Consequence to know: a better-priced pending entry (e.g. a LONG Re-entry below Order 1) makes Order 1 be rejected by the priority rule, and Order 2 priced better than a pending entry of another cycle would replace that entry, exactly as the approved rule says.
- WAP is still used for trailing, ROI, PNL and the trigger check; only the Averaging prices and margin basis use the Initial Entry.
- Unchanged: Stop Loss, Trailing Stop, Opposite-Side, Re-entry formulas and the Demo-only guards.

Tests written (not executed in the authoring environment): `DemoAccountViewTest` (equity scenarios), `AveragingMathTest`, `SettingsResolverTest`, `DemoStrategyEngineTest` (two-order Averaging, restart, partial fill, rejection, cycle closure, Re-entry untouched).

## Round 5 — Opposite-Side as a real Binance trailing order, new defaults, Trailing cards, Home-style tabs

### Opposite-Side (replaces the -50 % loss trigger)
- Right after the Initial Entry is filled **and has its own Stop Loss** (or Stop Loss is OFF), the app places ONE real Binance conditional order, once per cycle: LONG source -> `SELL` + positionSide `SHORT`; SHORT source -> `BUY` + `LONG`; type `TRAILING_STOP_MARKET`, MARK_PRICE, fixed quantity, no reduceOnly / closePosition.
- Activation price = initial fill x (1 ± Activation ROI / (100 x leverage)) rounded to the tick (default +40 %). Callback is sent as price % = Callback ROI / leverage rounded to 0.1 (default 3 % ROI; 10x -> 0.3 %). Quantity = Opposite Margin x leverage / activation price. Leverage is always the Initial Leverage.
- It executes as a **MARKET** order when Binance triggers it (Binance has no trailing-LIMIT order).
- It lives on Binance: works with the app closed / Auto-Trading OFF, stays when the original position closes first, and is never cancelled by the original's trailing/closing. Only **Emergency stop** and **Panic close** cancel it (Auto-Trading OFF does not).
- **Duplicate prevention at placement only**: nothing is placed unless `positionRisk` is readable and shows no position in the opposite direction, and no pending opposite entry order or pending opposite trailing order exists (store + Binance). Unreadable = not placed. Not re-placed after Averaging. Cycles opened by an Opposite-Side order do not place another one.
- When it has opened a position, the app (when it runs) **adopts** it as its own cycle (kind OPPOSITE_SIDE) and places its own Stop Loss at once; fail-safe close + halt only if that Stop Loss cannot be placed.
- **Gate**: a new Demo "Opposite-Side (opening) trailing probe" (More tab) places one far-away SELL+SHORT opening trailing order on BTCUSDT, queries, lists, cancels and verifies it; no position is opened. Until it passes, the Opposite-Side order is NOT used and there is **no app-side substitute** (a clear event is logged).
- Policy: exactly one new builder `newOpeningTrailingAlgo` (opening side only) on the same `POST /fapi/v1/algoOrder`; the endpoint catalog is unchanged (18). The static tests were updated for exactly this one exemption.

### Defaults (apply only to unsaved settings)
Initial Margin 100 USDT, Leverage 10x, closing Trailing +40 % / callback 5 %, Opposite Margin 100, Opposite Trailing +40 % / callback 3 %. The old keys `oppositeTriggerRoi` / `oppositeCallbackRoi` are ignored; the new keys (`oppTrailActivationRoi`, `oppTrailCallbackRoi`) are independent of the closing Trailing keys.

### Open Order -> Conditional -> Trailing Stop
Two kinds of card, built only from Binance `openAlgoOrders` rows: **POSITION CLOSE** (LONG+SELL / SHORT+BUY, blue) and **POSITION OPEN** (LONG+BUY / SHORT+SELL, amber, with Order Margin ≈ and Leverage). Current Trigger Price shows "—" before activation and "calculated" after (Binance does not expose it). The Cancel Trailing Stop button is removed from these cards.

### Tabs
Positions tabs (all three levels) use the Home market-tab look: selected = dark `bg` background, yellow bold text; unselected = muted, no background; containers use the Home tab-bar colour; no underline.

### Not verified (assumptions until probe + phone test)
Binance Demo accepting the opening trailing order, coexisting with the closing trailing/Stop Loss, surviving the original position's close, and exposing the triggered order's fill. Nothing was compiled or run by the author.
