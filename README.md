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
- **Re-entry** (C): after a trailing-stop exit, **Re-entry ROI = Activation ROI / 2** (automatic). A LONG LIMIT below and a SHORT LIMIT above the closing price, each at that Position-ROI distance (leverage included). The two orders are **independent**: a fill starts a new cycle (new ID) and does **not** cancel the other order, which stays pending until it fills or you cancel it manually (Open Orders → Cancel). One active cycle per symbol+direction still applies.
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
- `paper/` – Room database, simulation engine, monitor, pause control.
- `data/` – leverage table, settings + resolver, stage model, public market repository.
- `MainActivity.kt` – UI.

## Build
Open in Android Studio, or `gradle assembleDebug testDebugUnitTest`. GitHub Actions (`.github/workflows/android-apk.yml`) builds the debug APK, runs unit tests and uploads the APK (`BNB-APK-debug`) and test reports.

## Known limitations / notes
- This source was prepared without a Kotlin compiler available; **the first GitHub Actions run is the real verification** (compile + tests). Nothing here claims a successful build.
- Room schema is v4. v3 → v4 is a **non-destructive migration** (adds three snapshot columns with safe defaults; open cycles that existed before keep their old behaviour and do not suddenly place new opposite-side orders). Only the legacy v1/v2 (fixed take-profit) schemas are cleared once.
- Opposite-side orders are conditional-at-price entries (they fill when the price level is reached), an interpretation of "prepare a LIMIT entry at the trigger ROI"; please confirm it matches your intent.
- Money fields in Room entities are still `Double` (calculations of the trailing logic use BigDecimal).
- Trailing exit is simulated at the observed mark price (polling), not tick-accurate.
- No instrumented/UI tests yet.


## Database / migration
Room DB version 5. Migrations 3→4 and 4→5 are non-destructive (new columns with defaults; legacy per-entry SL orders are retired; open cycles keep their old behaviour). Only versions 1–2 fall back to a destructive rebuild.

## Verification status
The Kotlin code was written without a compiler or Gradle available in the authoring environment, so it has **not** been compiled or test-run there. The first GitHub Actions run (`assembleDebug` + unit tests) is the real verification; if a test fails, send the failing test names.