# BNB – Binance Android Bot (Stage 1 / Virtual only)

BNB is an Android app that simulates a USDⓈ-M Futures strategy on a **virtual 100 USDT wallet** using **live public Binance market data**.

> **Safety boundary:** This build is **Stage 1 (Virtual) only**. It uses public, unauthenticated Binance endpoints. There are **no API keys, no signing, no private endpoints and no real orders**. Stage 2 (Demo) and Stage 3 (Live) are **not authorized and not available**; unknown or stored stage values fail closed to Stage 1.

## Strategy rules implemented
- **No fixed/general Take Profit anywhere.** Profit exit = **ROI cycle Trailing Stop** on the cycle's Weighted Average Entry Price `W` with leverage `L`, activation ROI `A` (default 33), callback ROI `C` (default 3):
  - LONG: activation `W·(1 + A/(100·L))`, stop `H·(1 − C/(100·L))`
  - SHORT: activation `W·(1 − A/(100·L))`, stop `Lo·(1 + C/(100·L))`
  - Stop is monotonic (LONG never falls, SHORT never rises); closing exits **all** remaining quantity of the cycle.
- Each executed entry has its own fixed **Stop Loss** from its own fill price that closes only that entry.
- All entries are **LIMIT**. One cycle per symbol + direction; LONG and SHORT are independent.
- **One averaging entry per cycle**; WAP and trailing activation are recalculated from the new WAP (an already-active trail is kept, never loosened).
- **Re-entry** after a trailing exit creates a **new cycle**. Opposite-side is a separate cycle, only when none is open.
- Percentages come from the leverage table (3x–20x): SL = 69/L %, averaging = 66/L %, re-entry pullback = 13.8/L %.
- Settings are snapshotted when a cycle is created; later changes never alter running cycles.

## App structure
Bottom tabs: **Home, Manual, Positions, Settings, More**.
- Positions: *Open / Closed*; Open Orders: *Basic / Conditional*; History: *Open / Closed*.
- Settings = central Dashboard with Global defaults and per-symbol overrides, each labelled **Global / Inherited / Symbol Override**.
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
- Room schema is v3. Databases from older (take-profit based) versions are cleared once on upgrade; future changes should use real migrations (schemas are exported).
- Money fields in Room entities are still `Double` (calculations of the trailing logic use BigDecimal).
- Trailing exit is simulated at the observed mark price (polling), not tick-accurate.
- No instrumented/UI tests yet.
