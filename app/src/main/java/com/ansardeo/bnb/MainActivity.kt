package com.ansardeo.bnb

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ansardeo.bnb.BuildConfig
import com.ansardeo.bnb.data.*
import com.ansardeo.bnb.demo.BinanceDemoApi
import com.ansardeo.bnb.demo.CredentialState
import com.ansardeo.bnb.demo.DemoConnectionStatusStore
import com.ansardeo.bnb.demo.DemoCredentialStore
import com.ansardeo.bnb.demo.exec.DemoEntry
import com.ansardeo.bnb.demo.engine.DemoAutoTradeStore
import com.ansardeo.bnb.demo.engine.DemoSettingsService
import com.ansardeo.bnb.demo.engine.DemoStrategyEngineProvider
import com.ansardeo.bnb.demo.engine.DemoMarkCache
import com.ansardeo.bnb.demo.engine.EngineSubmit
import com.ansardeo.bnb.demo.exec.DemoExecutionProvider
import com.ansardeo.bnb.demo.exec.DemoExecutionService
import com.ansardeo.bnb.demo.exec.EntryStatus
import com.ansardeo.bnb.demo.trade.DemoCompatibilityProbe
import com.ansardeo.bnb.demo.trade.DemoProbeStore
import com.ansardeo.bnb.demo.trade.DemoSymbolSetup
import com.ansardeo.bnb.demo.trade.DemoSymbolSetupStore
import com.ansardeo.bnb.demo.trade.DemoTradeClient
import com.ansardeo.bnb.demo.trade.DemoTrailingProbe
import com.ansardeo.bnb.demo.trade.DemoTrailingProbeStore
import com.ansardeo.bnb.trading.RoiMath
import com.ansardeo.bnb.trading.TrailSide
import com.ansardeo.bnb.trading.TrailingStopMath
import com.ansardeo.bnb.ui.CoinDetailActivity
import com.ansardeo.bnb.util.PriceFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One compact Settings tile (view + expanded state). */
private class SettingTile(val view: LinearLayout, val full: Boolean) {
    var expanded = false
    var refresh: () -> Unit = {}
}

class MainActivity : AppCompatActivity() {

    private val marketRepo = MarketRepository()

    private val bg get() = ContextCompat.getColor(this, R.color.bg_primary)
    private val card get() = ContextCompat.getColor(this, R.color.bg_card)
    private val yellow get() = ContextCompat.getColor(this, R.color.accent_yellow)
    private val white get() = ContextCompat.getColor(this, R.color.text_primary)
    private val muted get() = ContextCompat.getColor(this, R.color.text_muted)
    private val green get() = ContextCompat.getColor(this, R.color.green)
    private val red get() = ContextCompat.getColor(this, R.color.red)
    private val inputBg get() = ContextCompat.getColor(this, R.color.bg_input)
    private val lockedColor get() = ContextCompat.getColor(this, R.color.locked)

    private lateinit var content: FrameLayout
    private lateinit var topStatus: TextView
    private lateinit var navItems: List<View>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>
    private var currentPage = "Home"
    private var pendingTradeSymbol: String? = null
    private var pendingTradePrice: Double? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var refreshGen: Long = 0L
    private var lastOverview: MarketOverview? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleTradeIntent(intent)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.contentFrame)
        topStatus = findViewById(R.id.txtTopStatus)
        navItems = listOf(
            findViewById(R.id.navDashboard),
            findViewById(R.id.navManual),
            findViewById(R.id.navPositions),
            findViewById(R.id.navSettings),
            findViewById(R.id.navMore)
        )
        navIcons = listOf(
            findViewById(R.id.navDashboardIcon),
            findViewById(R.id.navManualIcon),
            findViewById(R.id.navPositionsIcon),
            findViewById(R.id.navSettingsIcon),
            findViewById(R.id.navMoreIcon)
        )
        navLabels = listOf(
            findViewById(R.id.navDashboardLabel),
            findViewById(R.id.navManualLabel),
            findViewById(R.id.navPositionsLabel),
            findViewById(R.id.navSettingsLabel),
            findViewById(R.id.navMoreLabel)
        )
        navItems[0].setOnClickListener { showPage("Home") }
        navItems[1].setOnClickListener { showPage("Trade") }
        navItems[2].setOnClickListener { showPage("Future") }
        navItems[3].setOnClickListener { showPage("Settings") }
        navItems[4].setOnClickListener { showPage("More") }
        updateTopStatus()
        val nav = intent.getStringExtra("navigate_to")
        if (nav == "Trade" || pendingTradeSymbol != null) showPage("Trade") else if (nav == "Home") showPage("Home") else showPage("Home")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        handleTradeIntent(intent)
        val nav = intent?.getStringExtra("navigate_to")
        if (nav == "Trade" || pendingTradeSymbol != null) showPage("Trade")
        else if (nav == "Home") showPage("Home")
    }

    private fun handleTradeIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra("trade_symbol")?.let { pendingTradeSymbol = it.uppercase() }
        if (intent.hasExtra("trade_price")) pendingTradePrice = intent.getDoubleExtra("trade_price", Double.NaN).takeIf { !it.isNaN() }
    }

    private fun updateTopStatus() {
        val auto = DemoAutoTradeStore.isOn(this)
        topStatus.text = if (auto) "STAGE 2 — DEMO (AUTO-TRADING ON)" else "STAGE 2 — DEMO (AUTO-TRADING OFF)"
        topStatus.setTextColor(if (auto) green else yellow)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 16): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun text(value: String, size: Float = 14f, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun cardView(): LinearLayout = column().apply { background = rounded(card); setPadding(dp(16), dp(14), dp(16), dp(14)) }
    private fun addSpace(parent: LinearLayout, h: Int = 10) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }

    private fun priceTv(value: String, size: Float = 13f, color: Int = white, bold: Boolean = true): TextView =
        text(value, size, color, bold).apply {
            gravity = Gravity.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            typeface = Typeface.MONOSPACE
        }

    private fun addLabel(parent: LinearLayout, label: String, value: String, valueColor: Int = white, isPrice: Boolean = false) {
        val line = row().apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val labelTv = text(label, 12f, muted)
        line.addView(labelTv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(48) })
        val v = if (isPrice) priceTv(value, 13f, valueColor) else text(value, 13f, valueColor, true).apply {
            gravity = Gravity.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        line.addView(v)
        parent.addView(line)
        addSpace(parent, 8)
    }
    private fun addCard(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout {
        val box = cardView()
        box.addView(text(title, 14f, yellow, true))
        if (subtitle != null) { addSpace(box, 4); box.addView(text(subtitle, 12f, muted)) }
        addSpace(parent, 12); parent.addView(box); return box
    }

    private fun pageContainer(): LinearLayout {
        val outer = column().apply { setPadding(dp(16), dp(14), dp(16), dp(20)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(outer) }
        content.removeAllViews(); content.addView(scroll); return outer
    }

    private fun openDetail(symbol: String) {
        startActivity(Intent(this, CoinDetailActivity::class.java).putExtra(CoinDetailActivity.EXTRA_SYMBOL, symbol))
    }

    private fun showPage(page: String) {
        currentPage = page
        positionsVisible = false
        if (page != "Home") stopRefresh()
                navItems.forEachIndexed { i, _ ->
            val sel = (page == "Home" && i == 0) || (page == "Trade" && i == 1) || (page == "Future" && i == 2) || (page == "Settings" && i == 3) || (page == "More" && i == 4)
            val c = if (sel) yellow else muted
            navIcons[i].setColorFilter(c)
            navLabels[i].setTextColor(c)
            navLabels[i].setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        when (page) {
            "Home" -> dashboardPage()
            "Trade" -> manualPage()
            "Future" -> positionsPage()
            "Settings" -> settingsPage()
            "More" -> morePage()
        }
    }

    private fun stopRefresh() { refreshGen++; refreshRunnable?.let { mainHandler.removeCallbacks(it) }; refreshRunnable = null }

    // ---------------- HOME ----------------
    private var homeTab = "Volume"

    private fun fmtVol(v: Double?): String {
        if (v == null || !v.isFinite()) return "--"
        val a = kotlin.math.abs(v)
        return when {
            a >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
            a >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
            a >= 1e3 -> String.format(Locale.US, "%.2fK", v / 1e3)
            else -> String.format(Locale.US, "%.2f", v)
        }
    }

    private fun dashboardPage() {
        stopRefresh()
        val root = pageContainer()

        // 1) WALLET BALANCE (top)
        val balCard = addCard(root, "WALLET BALANCE - BINANCE DEMO")
        val balText = priceTv("-- USDT", 26f, green).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        balCard.addView(balText, LinearLayout.LayoutParams(-1, -2))
        addSpace(balCard, 4)
        val balSub = text("Loading Binance Demo wallet...", 12f, muted); balCard.addView(balSub)

        // 2) LIVE MARKET (below)
        val marketCard = addCard(root, "LIVE CRYPTOCURRENCY PRICES (READ-ONLY)")
        val mStatus = text("Loading market data...", 11f, yellow, true)

        val tabsBar = row().apply { background = rounded(inputBg, 14); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val tabNames = listOf("Favorites", "Volume", "Gainers", "Losers")
        val tabViews = tabNames.map { name ->
            text(name, 13f, muted, false).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)) }
                .also { tabsBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        addSpace(marketCard, 8); marketCard.addView(tabsBar, LinearLayout.LayoutParams(-1, -2))
        addSpace(marketCard, 8)

        val head = row().apply { setPadding(dp(4), dp(4), dp(4), dp(8)) }
        fun headCell(t: String, w: Float, end: Boolean) = text(t, 12f, muted, true).apply {
            if (end) { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        }.also { head.addView(it, LinearLayout.LayoutParams(0, -2, w)) }
        headCell("Symbol", 1.25f, false); headCell("Price", 1.15f, true); headCell("24h Vol", 0.95f, true); headCell("24h %", 1.05f, true)
        marketCard.addView(head)
        marketCard.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider)) }, LinearLayout.LayoutParams(-1, dp(1)))
        val list = column(); marketCard.addView(list)
        addSpace(marketCard, 6)
        marketCard.addView(mStatus)
        val mUpdated = text("Last update: --", 11f, muted); marketCard.addView(mUpdated)

        var lastOv: MarketOverview? = null
        fun renderList() {
            tabNames.forEachIndexed { i, n ->
                val sel = n == homeTab
                tabViews[i].setTextColor(if (sel) yellow else muted)
                tabViews[i].setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tabViews[i].background = if (sel) rounded(bg, 12) else null
            }
            val ov = lastOv
            when (homeTab) {
                "Favorites" -> renderFavorites(list, ov)
                else -> {
                    if (ov == null || !ov.dataAvailable) { list.removeAllViews(); list.addView(text("Loading live prices...", 12f, muted)); return }
                    renderTickerList(list, when (homeTab) { "Gainers" -> ov.gainers; "Losers" -> ov.losers; else -> ov.volumeLeaders })
                }
            }
        }
        tabViews.forEachIndexed { i, v -> v.setOnClickListener { homeTab = tabNames[i]; renderList() } }
        renderList()

        fun renderWallet() {
            val creds = DemoCredentialStore.repository(this).load()
            if (creds == null) { balText.text = "-- USDT"; balSub.text = "Save your Binance Demo keys in More to see the Demo wallet."; return }
            lifecycleScope.launch {
                val r = DemoTradeClient.create().balance(creds)
                val w = if (r.ok) com.ansardeo.bnb.demo.exec.DemoAccountView.parseWallet(r.body) else null
                val ents = DemoExecutionProvider.get(this@MainActivity).entries()
                val positions = ents.count { it.status != EntryStatus.CLOSED && it.status != EntryStatus.CANCELED && it.status != EntryStatus.REJECTED && it.executed.signum() > 0 }
                val orders = ents.count { it.orderActive }
                if (w == null) { balSub.text = "Could not read the Binance Demo wallet (${if (r.ok) "unexpected answer" else r.describe()}). Retrying with the next refresh." ; return@launch }
                balText.text = PriceFormatter.formatUsdt(w.equity.toDouble())
                balSub.text = "Wallet ${PriceFormatter.formatUsdt(w.walletBalance.toDouble())} | Available ${PriceFormatter.formatUsdt(w.available.toDouble())} | Unrealized ${PriceFormatter.formatPnl(w.unrealized.toDouble())} | Positions: $positions | Open Orders: $orders (from Binance Demo)"
            }
        }

        val gen = refreshGen
        fun refreshMarket() {
            if (gen != refreshGen || currentPage != "Home") return
            lifecycleScope.launch {
                val ov = marketRepo.getOverview()
                if (gen != refreshGen || currentPage != "Home") return@launch
                lastOverview = ov; lastOv = ov
                if (!ov.dataAvailable) {
                    mStatus.text = "Unavailable — ${ov.error ?: "unable to load live prices"} (retrying)"
                    mStatus.setTextColor(red)
                    mUpdated.text = "Last update: --"
                    if (homeTab != "Favorites") { list.removeAllViews(); list.addView(text("Unable to load live prices. Retrying...", 12f, red)) } else renderList()
                } else {
                    val label = if (ov.stale) "Stale" else if (ov.live) "Live" else ov.status
                    mStatus.text = "$label — Binance USDⓈ-M Futures (public, read-only)"
                    mStatus.setTextColor(if (ov.stale) yellow else green)
                    mUpdated.text = "Last update: ${ov.lastUpdate ?: "--"} (auto-refresh)"
                    renderList()
                    renderWallet()
                }
            }
            val r = Runnable { refreshMarket() }; refreshRunnable = r; mainHandler.postDelayed(r, 15000)
        }
        renderWallet(); refreshMarket()
    }

    private fun marketRow(sym: String, price: Double?, vol: Double?, pct: Double?): LinearLayout {
        val line = row().apply {
            isClickable = true; isFocusable = true
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener { openDetail(sym) }
        }
        line.addView(text(sym, 13f, white, true), LinearLayout.LayoutParams(0, -2, 1.25f).apply { marginEnd = dp(6) })
        line.addView(priceTv(PriceFormatter.formatPrice(price), 12f, white).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }, LinearLayout.LayoutParams(0, -2, 1.15f).apply { marginEnd = dp(6) })
        line.addView(priceTv(fmtVol(vol), 11f, muted, false).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }, LinearLayout.LayoutParams(0, -2, 0.95f).apply { marginEnd = dp(6) })
        val badgeWrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        badgeWrap.addView(percentBadge(pct), LinearLayout.LayoutParams(-2, -2))
        line.addView(badgeWrap, LinearLayout.LayoutParams(0, -2, 1.05f))
        return line
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider)) }

    private fun percentBadge(pct: Double?): TextView {
        val v = pct ?: 0.0
        val bg = when {
            pct == null -> inputBg
            v > 0.0 -> green
            v < 0.0 -> red
            else -> inputBg
        }
        val label = if (pct == null) "--" else PriceFormatter.formatPercent(pct)
        return text(label, 12f, white, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = rounded(bg, 6)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
    }

    private fun renderTickerList(target: LinearLayout, items: List<Ticker>) {
        target.removeAllViews()
        if (items.isEmpty()) { target.addView(text("No live data available.", 12f, muted)); return }
        items.take(10).forEach { t ->
            target.addView(marketRow(t.symbol, t.lastPrice, t.quoteVolume, t.priceChangePercent))
            target.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun renderFavorites(target: LinearLayout, ov: MarketOverview?) {
        target.removeAllViews()
        val favs = FavoritesStore.load(this)
        if (favs.isEmpty()) { target.addView(text("No favorites yet. Open a coin and tap Favorite.", 12f, muted)); return }
        for (sym in favs) {
            val t = ov?.symbols?.get(sym)
            target.addView(marketRow(sym, t?.lastPrice, t?.quoteVolume, t?.priceChangePercent))
            target.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    // ---------------- TRADE (Manual tab renamed) ----------------
    private fun manualPage() /* Trade tab */ {
        stopRefresh()
        content.removeAllViews()
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stageBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(card, 12); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        stageBar.addView(text("Stage 2 — Binance Demo", 13f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
        outer.addView(stageBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) })
        val view = LayoutInflater.from(this).inflate(R.layout.activity_manual_entry, outer, false)
        outer.addView(view)
        content.addView(ScrollView(this).apply { addView(outer) })

        // Market Statistics are shown in Home coin details only
        view.findViewById<View?>(R.id.boxStats)?.visibility = View.GONE

        val symbolInput = view.findViewById<AutoCompleteTextView>(R.id.actvSymbol)
        val leverageSpinner = view.findViewById<Spinner>(R.id.spEntryLeverage)
        val marginInput = view.findViewById<EditText>(R.id.etEntryMargin)
        val entryPriceInput = view.findViewById<EditText>(R.id.etEntryPrice)
        val summaryBox = view.findViewById<LinearLayout>(R.id.boxEntrySummary)
        val livePriceTv = view.findViewById<TextView>(R.id.txtLivePrice)
        val liveStatus = view.findViewById<TextView>(R.id.txtLivePriceStatus)
        val btnOpenLong = view.findViewById<Button>(R.id.btnOpenLong)
        val btnOpenShort = view.findViewById<Button>(R.id.btnOpenShort)
        val result = view.findViewById<TextView>(R.id.txtManualResult)

        var currentLivePrice: Double? = null // Binance LAST price (LIMIT entry suggestion / display)
        var currentMarkPrice: Double? = null // Binance MARK price (kept separate)
        var userEditedEntryPrice = false
        val prefillSymbol = pendingTradeSymbol?.uppercase()
        val prefillPrice = pendingTradePrice
        pendingTradeSymbol = null
        pendingTradePrice = null

        fun selectedSym(): String = symbolInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
        fun selectedLev(): Int = LeverageConfig.SUPPORTED_LEVERAGES.getOrElse(leverageSpinner.selectedItemPosition) { 6 }

        leverageSpinner.adapter = createDarkSpinnerAdapter(LeverageConfig.SUPPORTED_LEVERAGES.map { "${it}x" })
        val globalDefaults = LocalCoinSettingsStore.loadGlobal(this)
        leverageSpinner.setSelection(LeverageConfig.SUPPORTED_LEVERAGES.indexOf(globalDefaults.leverage).coerceAtLeast(0))
        if (marginInput.text.isNullOrBlank()) marginInput.setText(fmtNum(globalDefaults.marginAmount))
        var defaultsAppliedFor: String? = null
        leverageSpinner.setPopupBackgroundDrawable(rounded(inputBg, 10))
        leverageSpinner.isEnabled = false // Demo uses the leverage saved in Settings for this coin (set and read back on Binance)
        symbolInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>()))

        fun addSummaryRow(label: String, value: String, valueColor: Int = white, bold: Boolean = true) {
            val line = row().apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
            line.addView(text(label, 12f, muted), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(24) })
            line.addView(text(value, 12f, valueColor, bold).apply {
                gravity = Gravity.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            })
            summaryBox.addView(line)
            summaryBox.addView(View(this), LinearLayout.LayoutParams(-1, dp(6)))
        }

        fun updateSummary() {
            val sym = selectedSym().ifBlank { "--" }
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            val lev = try { LeverageConfig.requireSupported(selectedLev()) } catch (_: Exception) { 6 }
            val intended = entryPriceInput.text.toString().toDoubleOrNull()
            val qty = if (intended != null && intended > 0 && margin > 0) (margin * lev) / intended else null
            val eff = LocalCoinSettingsStore.load(this, sym)
            val slLong = if (eff.stopLossEnabled && intended != null && intended > 0) runCatching { RoiMath.priceAtRoi(TrailSide.LONG, TrailingStopMath.bd(intended), lev, TrailingStopMath.bd(-kotlin.math.abs(eff.stopLossRoi))).toDouble() }.getOrNull() else null
            val slShort = if (eff.stopLossEnabled && intended != null && intended > 0) runCatching { RoiMath.priceAtRoi(TrailSide.SHORT, TrailingStopMath.bd(intended), lev, TrailingStopMath.bd(-kotlin.math.abs(eff.stopLossRoi))).toDouble() }.getOrNull() else null
            val actLong = if (intended != null && intended > 0) runCatching { TrailingStopMath.activationPrice(TrailSide.LONG, TrailingStopMath.bd(intended), lev, TrailingStopMath.bd(eff.trailingActivationRoi)).toDouble() }.getOrNull() else null
            val actShort = if (intended != null && intended > 0) runCatching { TrailingStopMath.activationPrice(TrailSide.SHORT, TrailingStopMath.bd(intended), lev, TrailingStopMath.bd(eff.trailingActivationRoi)).toDouble() }.getOrNull() else null
            summaryBox.removeAllViews()
            addSummaryRow("Stage", "Stage 2 — Binance Demo", green)
            addSummaryRow("Symbol", sym)
            addSummaryRow("Last Price", "${PriceFormatter.formatPrice(currentLivePrice)} USDT")
            addSummaryRow("Mark Price", "${PriceFormatter.formatPrice(currentMarkPrice)} USDT")
            addSummaryRow("Intended LIMIT Entry", "${PriceFormatter.formatPrice(intended)} USDT")
            addSummaryRow("Margin", "${PriceFormatter.formatUsdt(margin)} / ${lev}x")
            addSummaryRow("Cycle Stop Loss (ROI)", if (eff.stopLossEnabled) "-${fmtNum(eff.stopLossRoi)}% Position ROI" else "Disabled")
            addSummaryRow("Trailing ROI (activation / callback)", if (eff.trailingEnabled) "${eff.trailingActivationRoi} / ${eff.trailingCallbackRoi}" else "Disabled")
            addSummaryRow("Averaging trigger ROI", if (eff.averagingEnabled) "${fmtNum(eff.averagingTriggerRoi)}% (margin ${fmtNum(eff.averagingMargin)})" else "Disabled")
            addSummaryRow("Opposite-side trigger ROI / callback", if (eff.oppositeEnabled) "${fmtNum(eff.oppositeTriggerRoi)}% / ${fmtNum(eff.oppositeCallbackRoi)}% (margin ${fmtNum(eff.oppositeMargin)})" else "Disabled")
            addSummaryRow("Re-entry ROI (of booked ROI)", if (eff.reentryEnabled) "50% same / 100% opposite" else "Disabled")
            addSummaryRow("Estimated Qty", if (qty != null) PriceFormatter.formatQty(qty) else "--")
            addSummaryRow("Long: Trailing activation / cycle SL", "${PriceFormatter.formatPrice(actLong)} / ${PriceFormatter.formatPrice(slLong)}", green)
            addSummaryRow("Short: Trailing activation / cycle SL", "${PriceFormatter.formatPrice(actShort)} / ${PriceFormatter.formatPrice(slShort)}", red)
            val note = text("Binance DEMO only. Entries are LIMIT orders; leverage comes from Settings. No MARKET entries. No fixed Take Profit: profit exit is the ROI trailing stop.", 11f, muted)
            summaryBox.addView(note)
        }

        fun loadSymbolLive(sym: String) {
            if (sym.isBlank() || !sym.endsWith("USDT")) return
            if (defaultsAppliedFor != sym) {
                // Settings defaults (global, or this symbol's override) populate Manual; they stay editable.
                defaultsAppliedFor = sym
                val eff = LocalCoinSettingsStore.load(this, sym)
                marginInput.setText(fmtNum(eff.marginAmount))
                leverageSpinner.setSelection(LeverageConfig.SUPPORTED_LEVERAGES.indexOf(eff.leverage).coerceAtLeast(0))
            }
            val myGen = ++refreshGen
            liveStatus.setTextColor(muted); liveStatus.text = "Loading live price for $sym..."
            fun tick() {
                if (myGen != refreshGen || currentPage != "Trade" || selectedSym() != sym) return
                lifecycleScope.launch {
                    val lp = marketRepo.getLivePrices(sym)
                    if (myGen != refreshGen || currentPage != "Trade" || selectedSym() != sym) return@launch
                    currentLivePrice = lp.last
                    currentMarkPrice = lp.mark
                    livePriceTv.text = "Last price: ${PriceFormatter.formatPrice(currentLivePrice)} USDT  |  Mark: ${PriceFormatter.formatPrice(currentMarkPrice)}"
                    liveStatus.text = if (currentLivePrice != null) "Live public Binance prices (auto-refresh)" else "Price unavailable — retrying"
                    liveStatus.setTextColor(if (currentLivePrice != null) muted else red)
                    if (!userEditedEntryPrice && currentLivePrice != null && entryPriceInput.text.isNullOrBlank()) {
                        entryPriceInput.setText(PriceFormatter.formatPrice(currentLivePrice))
                    }
                    updateSummary()
                }
                val r = Runnable { tick() }; refreshRunnable = r; mainHandler.postDelayed(r, 12000)
            }
            tick()
        }

        fun onSymbolChanged() { userEditedEntryPrice = false; loadSymbolLive(selectedSym()) }

        lifecycleScope.launch {
            val symbols = marketRepo.getUsdmSymbols().getOrElse {
                val fb = (lastOverview?.symbols?.keys?.toList() ?: emptyList()) + FavoritesStore.load(this@MainActivity)
                fb.distinct().sorted().ifEmpty { listOf("BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT") }
            }
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, symbols)
            symbolInput.setAdapter(adapter)
            if (!prefillSymbol.isNullOrBlank()) {
                symbolInput.setText(prefillSymbol, false)
                if (prefillPrice != null && prefillPrice > 0) {
                    userEditedEntryPrice = true
                    entryPriceInput.setText(PriceFormatter.formatPrice(prefillPrice))
                }
            } else {
                val fav = FavoritesStore.load(this@MainActivity).firstOrNull()
                if (!fav.isNullOrBlank() && symbolInput.text.isNullOrBlank()) symbolInput.setText(fav, false)
            }
            val s = selectedSym(); if (s.isNotBlank()) loadSymbolLive(s)
        }

        fun setButtonsEnabled(enabled: Boolean) { btnOpenLong.isEnabled = enabled; btnOpenShort.isEnabled = enabled }

        fun submitEntry(dir: String) {
            val sym = selectedSym()
            if (sym.isBlank() || !sym.endsWith("USDT")) { toast("Select a valid USDⓈ-M symbol"); return }
            val intended = entryPriceInput.text.toString().toDoubleOrNull()
            if (intended == null || intended <= 0.0) { toast("Enter a valid intended LIMIT entry price"); return }
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            if (margin <= 0.0) { toast("Enter a valid margin"); return }
            val lev = try { LeverageConfig.requireSupported(selectedLev()) } catch (e: Exception) { toast(e.message ?: "Invalid leverage"); return }
            // Binance DEMO only.
            val creds = DemoCredentialStore.repository(this).load()
            if (creds == null) { toast("Save your Demo keys first (More)."); return }
            // No separate READY gate here: the Demo order path itself re-reads margin type and leverage from Binance on every entry and refuses (with the reason) unless ISOLATED and equal to Settings.
            val eff = LocalCoinSettingsStore.load(this, sym)
            val price = intended.toBigDecimal(); val marginBd = margin.toBigDecimal()
            val request = DemoExecutionService.EntryRequest(sym, dir, price, marginBd, eff.leverage, eff.stopLossRoi, eff.stopLossEnabled)
            val svc = DemoExecutionProvider.get(this)
            AlertDialog.Builder(this)
                .setTitle("Confirm Demo $dir LIMIT Entry")
                .setMessage("Binance DEMO only. No real-money order.\n\n$sym $dir\nLIMIT price: ${PriceFormatter.formatPrice(intended)}\nLast price: ${PriceFormatter.formatPrice(currentLivePrice)}\nMargin ${PriceFormatter.formatUsdt(margin)} | ${eff.leverage}x (from Settings)\n\nAfter a fill this entry gets its own Stop Loss on Binance Demo (shown ACTIVE only after Binance confirms it).\n\nPlace this $dir LIMIT entry?")
                .setPositiveButton("CONFIRM") { _, _ ->
                    setButtonsEnabled(false); result.setTextColor(muted); result.text = "Sending $dir LIMIT entry to Binance Demo..."
                    lifecycleScope.launch {
                        val text: String; val ok: Boolean
                        if (DemoAutoTradeStore.isOn(this@MainActivity)) {
                            val res = DemoStrategyEngineProvider.get(this@MainActivity).submitEntry(creds, sym, dir, price, marginOverride = marginBd)
                            ok = res is EngineSubmit.Placed
                            text = when (res) { is EngineSubmit.Placed -> "Order sent: ${res.entry.status}"; is EngineSubmit.NotPlaced -> res.message }
                        } else {
                            val res = svc.placeEntry(creds, request)
                            ok = res is DemoExecutionService.PlaceResult.Placed
                            text = when (res) { is DemoExecutionService.PlaceResult.Placed -> "Order sent: ${res.entry.status}"; is DemoExecutionService.PlaceResult.Refused -> res.reason; else -> "Order state unknown — check Positions / Open Order and Binance Demo." }
                        }
                        setButtonsEnabled(true)
                        result.setTextColor(if (ok) green else red); result.text = (if (ok) "[OK] " else "[X] ") + text
                        if (ok) toast("$dir LIMIT entry sent to Demo")
                    }
                }.setNegativeButton("CANCEL", null).show()
        }

        symbolInput.setOnItemClickListener { _, _, _, _ -> onSymbolChanged() }
        symbolInput.setOnEditorActionListener { _, _, _ -> onSymbolChanged(); false }
        symbolInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus && selectedSym().isNotBlank()) loadSymbolLive(selectedSym()) }
        entryPriceInput.addTextChangedListener(SimpleWatcher { if (entryPriceInput.hasFocus()) userEditedEntryPrice = true; updateSummary() })
        marginInput.addTextChangedListener(SimpleWatcher { updateSummary() })
        leverageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateSummary() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        btnOpenLong.setOnClickListener { submitEntry("LONG") }
        btnOpenShort.setOnClickListener { submitEntry("SHORT") }

        updateSummary()
    }


    private class SimpleWatcher(val onChange: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChange() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    }

    private fun fmtTime(ts: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))

    // ---------------- FUTURE ----------------
    private var demoSnap: com.ansardeo.bnb.demo.exec.DemoExecutionService.ExchangeSnapshot? = null
    private var positionsVisible = false

    /** Reads Binance Demo (positions, open orders, conditional orders). Used by the Positions screen and the poll loop; never invents data. */
    private suspend fun loadDemoSnapshot() {
        val c = DemoCredentialStore.repository(this).load() ?: run { demoSnap = null; return }
        demoSnap = DemoExecutionProvider.get(this).snapshot(c)
    }

    private fun positionsPage() /* Future tab */ {
        stopRefresh(); updateTopStatus()
        positionsVisible = true
        val root = pageContainer()
        val tabBar = row().apply { background = rounded(card, 12); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(tabBar); addSpace(root, 8)
        val subBar = row().apply { background = rounded(card, 10); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(subBar); addSpace(root, 8)
        val condBar = row().apply { background = rounded(card, 10); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(condBar); addSpace(root, 8)
        val body = column(); root.addView(body)

        var primaryTab = 0 // 0 Position, 1 Open Order, 2 History
        var positionSub = 0 // 0 Open, 1 Closed
        var orderSub = 0 // 0 Basic, 1 Conditional
        var condSub = 0 // 0 Stop Loss, 1 Trailing Stop
        var historySub = 0 // 0 Open (events), 1 Closed (history)
        lateinit var primaryViews: List<TextView>
        lateinit var subViews: List<TextView>
        lateinit var condViews: List<TextView>
        lateinit var render: () -> Unit

        fun style(tv: TextView, sel: Boolean, r: Int) {
            tv.setTextColor(if (sel) yellow else muted)
            tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            tv.background = if (sel) rounded(inputBg, r) else null
        }
        fun renderDemo() {
            demoOrdersRefresh = { runOnUiThread { if (body.isAttachedToWindow) render() } }
            val svc = DemoExecutionProvider.get(this)
            val snap = demoSnap
            val all = svc.entries()
            val closed = all.filter { it.status == EntryStatus.CLOSED || it.status == EntryStatus.CANCELED || it.status == EntryStatus.REJECTED }
            val events = svc.events(200)
            val posCount = snap?.positions?.size
            val basicCount = snap?.basicOrders?.size
            val slRows = snap?.algos?.let { com.ansardeo.bnb.demo.exec.DemoAlgoView.stopLosses(it) }
            val trRows = snap?.algos?.let { com.ansardeo.bnb.demo.exec.DemoAlgoView.trailingStops(it) }
            val condCount = snap?.algos?.let { slRows!!.size + trRows!!.size }
            fun n(v: Int?) = v?.toString() ?: "?"
            listOf("Position (${n(posCount)})", "Open Order (${n(basicCount?.let { b -> condCount?.let { b + it } })})", "History (${closed.size})").forEachIndexed { i, t -> primaryViews[i].text = t; style(primaryViews[i], i == primaryTab, 10) }
            val subLabels = when (primaryTab) { 0 -> listOf("Open (${n(posCount)})", "Closed (${closed.size})"); 1 -> listOf("Basic (${n(basicCount)})", "Conditional (${n(condCount)})"); else -> listOf("Open (${events.size})", "Closed (${closed.size})") }
            subViews.forEachIndexed { i, tv -> tv.text = subLabels[i]; style(tv, when (primaryTab) { 0 -> i == positionSub; 1 -> i == orderSub; else -> i == historySub }, 8) }
            condBar.visibility = if (primaryTab == 1 && orderSub == 1) View.VISIBLE else View.GONE
            listOf("Stop Loss (${n(slRows?.size)})", "Trailing Stop (${n(trRows?.size)})").forEachIndexed { i, t -> condViews[i].text = t; style(condViews[i], i == condSub, 8) }
            body.removeAllViews()
            svc.haltReason()?.let { r -> body.addView(text("HALTED: $r", 12f, red)); addSpace(body, 8) }
            val needsExchange = primaryTab == 0 && positionSub == 0 || primaryTab == 1
            if (needsExchange) {
                if (snap == null) { body.addView(text(if (DemoCredentialStore.repository(this).load() == null) "Save your Binance Demo keys (More) to see live positions and orders." else "Loading from Binance Demo...", 13f, muted)); return }
                snap.error?.let { body.addView(text("Binance Demo could not be fully read ($it). Parts that failed are not shown as empty.", 12f, red)); addSpace(body, 6) }
                body.addView(text("Live from Binance Demo · updated ${fmtTime(snap.atMillis)} · refreshes every 5 s", 10f, muted)); addSpace(body, 6)
            }
            when (primaryTab) {
                0 -> if (positionSub == 0) renderLivePositions(body, snap!!) else renderDemoHistory(body, closed)
                1 -> if (orderSub == 0) renderLiveBasic(body, snap!!) else renderLiveConditional(body, snap!!, condSub == 1)
                2 -> if (historySub == 0) renderDemoHistoryOpen(body, events) else renderDemoHistory(body, closed)
            }
        }
        render = { renderDemo() }

        primaryViews = listOf("Position","Open Order","History").mapIndexed { i, _ ->
            TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); textSize = 12f; setOnClickListener { if (primaryTab != i) { primaryTab = i; render() } } }
                .also { tabBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        subViews = listOf("A","B").mapIndexed { i, _ ->
            TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); textSize = 11f; setOnClickListener {
                if (primaryTab == 0) { if (positionSub != i) { positionSub = i; render() } } else if (primaryTab == 1) { if (orderSub != i) { orderSub = i; render() } } else { if (historySub != i) { historySub = i; render() } }
            } }.also { subBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        condViews = listOf("A","B").mapIndexed { i, _ ->
            TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); textSize = 11f; setOnClickListener { if (condSub != i) { condSub = i; render() } } }
                .also { condBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        render()
        lifecycleScope.launch { loadDemoSnapshot(); if (body.isAttachedToWindow) render() }
    }

    private fun demoSlLine(e: DemoEntry): String = when {
        !e.slOn -> "Stop Loss OFF (disabled in Settings; Binance liquidation rules apply)"
        e.slState == "VERIFIED" -> "Stop Loss ACTIVE @ ${e.slTrigger}"
        e.slState == "DONE" -> "Stop Loss triggered/finished"
        e.slState == "LOST" -> "Stop Loss LOST"
        else -> "No Stop Loss"
    }

    /** Open positions straight from Binance Demo positionRisk. PNL/ROI use the MARK price; ROI includes leverage. */
    private fun renderLivePositions(body: LinearLayout, snap: com.ansardeo.bnb.demo.exec.DemoExecutionService.ExchangeSnapshot) {
        val positions = snap.positions
        if (positions == null) { body.addView(text("Open positions could not be read from Binance Demo. Nothing is assumed.", 13f, red)); return }
        if (positions.isEmpty()) { body.addView(text("No open positions on Binance Demo.", 13f, muted)); return }
        val repo = DemoCredentialStore.repository(this); val svc = DemoExecutionProvider.get(this); val engine = DemoStrategyEngineProvider.get(this)
        val entries = svc.entries()
        val liveAlgoIds = snap.algos?.map { it.algoId }?.toSet()
        for (p in positions) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${p.symbol}  ${p.direction}", 15f, if (p.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("${p.leverage}x ${p.marginType.lowercase()}", 12f, muted, true)); box.addView(header)
            val pnl = p.unrealizedPnl.toDouble(); val roi = p.roiPercent.toDouble()
            addLabel(box, "Unrealized PNL", PriceFormatter.formatPnl(pnl) + " USDT", pnlColor(pnl), true)
            addLabel(box, "Position ROI", String.format(Locale.US, "%+.2f%%", roi), pnlColor(roi), true)
            addLabel(box, "Entry Price / WAP", PriceFormatter.formatPrice(p.entryPrice.toDouble()), white, true)
            addLabel(box, "Mark Price", PriceFormatter.formatPrice(p.markPrice.toDouble()), white, true)
            addLabel(box, "Quantity", p.quantity.stripTrailingZeros().toPlainString(), white, true)
            addLabel(box, "Position Amount", PriceFormatter.formatUsdt(p.positionAmount.toDouble()), white, true)
            addLabel(box, "Margin", PriceFormatter.formatUsdt(p.margin.toDouble()), white, true)
            p.liquidationPrice?.let { addLabel(box, "Liquidation Price", PriceFormatter.formatPrice(it.toDouble()), muted, true) }
            val mine = entries.filter { it.symbol == p.symbol && it.direction == p.direction && it.executed.signum() > 0 && it.openQuantity.signum() > 0 && it.status != EntryStatus.CLOSED && it.status != EntryStatus.FAILSAFE_CLOSED }
            if (mine.isEmpty()) addLabel(box, "Stop Loss", "none recorded by this app (opened outside it)", yellow, true)
            for (e in mine) {
                val onExchange = e.slState != "VERIFIED" || liveAlgoIds == null || (e.slAlgoId != null && e.slAlgoId in liveAlgoIds)
                val line = if (e.slState == "VERIFIED" && !onExchange) "Stop Loss not found on Binance Demo" else demoSlLine(e)
                addLabel(box, "Stop Loss (${e.openQuantity.stripTrailingZeros().toPlainString()})", line, if (e.status == EntryStatus.UNPROTECTED || !onExchange) red else if (e.slState == "VERIFIED") green else yellow, true)
            }
            val tr = engine.trailingForPosition(p.symbol, p.direction, p.entryPrice, p.leverage)
            if (!tr.enabled) addLabel(box, "Trailing Stop", tr.source, muted, true)
            else {
                val algoStatus = tr.algoId?.let { id -> snap.algos?.firstOrNull { it.algoId == id }?.status }
                val st = com.ansardeo.bnb.demo.exec.TrailingActivationStatus.of(p.side, tr.activation, p.markPrice, tr.persistedActive, algoStatus)
                val color = when (st) { com.ansardeo.bnb.demo.exec.TrailingActivation.ACTIVATED -> green; com.ansardeo.bnb.demo.exec.TrailingActivation.WAITING -> yellow; else -> muted }
                addLabel(box, "Trailing Activation Price", tr.activation?.let { PriceFormatter.formatPrice(it.toDouble()) } ?: "--", color, true)
                addLabel(box, "Trailing Status", when (st) { com.ansardeo.bnb.demo.exec.TrailingActivation.ACTIVATED -> "Activated"; com.ansardeo.bnb.demo.exec.TrailingActivation.WAITING -> "Waiting for activation"; else -> "Activation state unknown" }, color, true)
                tr.callback?.let { addLabel(box, "Trailing Callback", it, white, true) }
                box.addView(text(tr.source, 10f, muted))
            }
            if (mine.any { it.note.isNotBlank() }) addLabel(box, "Note", mine.first { it.note.isNotBlank() }.note, red, true)
            val closable = mine.filter { it.closable }
            if (closable.isNotEmpty()) {
                val b = Button(this).apply { text = "CLOSE POSITION (market)"; isAllCaps = false; setTextColor(white); background = rounded(0xFF5A1E1E.toInt(), 12) }
                box.addView(b, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
                b.setOnClickListener {
                    val c = repo.load() ?: return@setOnClickListener toast("Save Demo keys first.")
                    AlertDialog.Builder(this).setTitle("Close on Binance Demo?").setMessage("Closes ${p.symbol} ${p.direction} at market (${closable.size} entr${if (closable.size == 1) "y" else "ies"}) and removes the Stop Loss once fully closed.")
                        .setPositiveButton("CLOSE") { _, _ -> lifecycleScope.launch { for (e in closable) toast(svc.closeEntryAtMarket(c, e.id)); loadDemoSnapshot(); demoOrdersRefresh?.invoke() } }.setNegativeButton("CANCEL", null).show()
                }
            }
        }
    }

    private fun renderLiveBasic(body: LinearLayout, snap: com.ansardeo.bnb.demo.exec.DemoExecutionService.ExchangeSnapshot) {
        val rows = snap.basicOrders
        if (rows == null) { body.addView(text("Open orders could not be read from Binance Demo.", 13f, red)); return }
        if (rows.isEmpty()) { body.addView(text("No open orders on Binance Demo.", 13f, muted)); return }
        val repo = DemoCredentialStore.repository(this); val svc = DemoExecutionProvider.get(this)
        val mineByOrder = svc.entries().filter { it.orderId != null }.associateBy { it.orderId }
        for (o in rows) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${o.symbol} ${o.direction}", 15f, if (o.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(mineByOrder[o.orderId]?.let { com.ansardeo.bnb.demo.engine.DemoCycleEntryBook.kindOf(it.kind).label } ?: "Entry", 12f, yellow, true)); box.addView(header)
            addLabel(box, "Order Price", PriceFormatter.formatPrice(o.price.toDoubleOrNull()), white, true)
            addLabel(box, "Type · Status", "${o.type} · ${o.status}" + (if ((o.executed.toDoubleOrNull() ?: 0.0) > 0.0) " · part-filled ${o.executed}" else ""), white, true)
            addLabel(box, "Quantity", o.quantity, white, true)
            addLabel(box, "Order ID", o.orderId.toString(), muted)
            addCancelButton(box, "Cancel order") { val c = repo.load() ?: return@addCancelButton "Save Demo keys first."; svc.cancelBasic(c, o.symbol, o.orderId) }
        }
    }

    /** Conditional (algo) orders from Binance Demo openAlgoOrders. The two kinds are separated ONLY by the order type Binance reports. */
    private fun renderLiveConditional(body: LinearLayout, snap: com.ansardeo.bnb.demo.exec.DemoExecutionService.ExchangeSnapshot, trailing: Boolean) {
        val all = snap.algos
        if (all == null) { body.addView(text("Conditional orders could not be read from Binance Demo.", 13f, red)); return }
        val rows = if (trailing) com.ansardeo.bnb.demo.exec.DemoAlgoView.trailingStops(all) else com.ansardeo.bnb.demo.exec.DemoAlgoView.stopLosses(all)
        if (rows.isEmpty()) { body.addView(text(if (trailing) "No active Trailing Stop orders on Binance Demo." else "No active Stop Loss orders on Binance Demo.", 13f, muted)); return }
        val repo = DemoCredentialStore.repository(this); val svc = DemoExecutionProvider.get(this)
        for (a in rows) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${a.symbol} ${a.direction}", 15f, if (a.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(if (trailing) "Trailing Stop" else "Stop Loss", 12f, yellow, true)); box.addView(header)
            if (trailing) {
                addLabel(box, "Activation Price", PriceFormatter.formatPrice(a.activationPrice?.toDoubleOrNull()), yellow, true)
                addLabel(box, "Callback Rate", (a.callbackRate ?: "--") + "%", white, true)
            } else {
                addLabel(box, "Order Type", a.orderType, white, true)
                addLabel(box, "Trigger Price", PriceFormatter.formatPrice(a.triggerPrice?.toDoubleOrNull()), red, true)
            }
            addLabel(box, "Quantity", a.quantity, white, true)
            addLabel(box, "Algo Order ID", a.algoId.toString(), muted)
            addLabel(box, "Order Status", a.status, white, true)
            addCancelButton(box, if (trailing) "Cancel Trailing Stop" else "Cancel Stop Loss") { val c = repo.load() ?: return@addCancelButton "Save Demo keys first."; svc.cancelConditional(c, a.algoId) }
        }
    }

    /** History > Open: the event trail (sending, fills, Stop Loss confirmed, close, fail-safe) for the Demo panel. */
    private fun renderDemoHistoryOpen(body: LinearLayout, events: List<Pair<Long, String>>) {
        if (events.isEmpty()) { body.addView(text("No events.", 13f, muted)); return }
        val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for ((t, msg) in events) {
            val box = makeItemCard(body)
            box.addView(text(msg, 12f, if (msg.contains("FAIL-SAFE") || msg.contains("HALT") || msg.contains("UNPROTECTED")) red else white))
            box.addView(text(tf.format(Date(t)), 11f, muted))
        }
    }

    private fun renderDemoHistory(body: LinearLayout, closed: List<DemoEntry>) {
        if (closed.isEmpty()) { body.addView(text("No closed positions.", 13f, muted)); return }
        val tf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val creds = DemoCredentialStore.repository(this).load()
        val pnlViews = HashMap<String, TextView>()
        for (e in closed.asReversed()) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${e.symbol} ${e.direction}", 15f, if (e.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(e.status.name, 12f, when (e.status) { EntryStatus.UNPROTECTED -> red; EntryStatus.FAILSAFE_CLOSED -> red; EntryStatus.CLOSED -> white; else -> muted }, true))
            box.addView(header)
            addLabel(box, "Order Price", PriceFormatter.formatPrice(e.limitPrice.toDoubleOrNull()), white, true)
            if (e.avgPrice != null) addLabel(box, "Fill Price", PriceFormatter.formatPrice(e.avgPrice.toDoubleOrNull()), white, true)
            addLabel(box, "Qty", e.executedQty)
            addLabel(box, "Leverage", "${e.leverage}x")
            if (e.note.isNotBlank()) addLabel(box, "Note", e.note, muted)
            addLabel(box, "Updated", tf.format(Date(e.updatedAt)))
            if (e.executed.signum() > 0) pnlViews[e.id] = text("Realized PnL: loading from Binance Demo...", 12f, muted).also { box.addView(it) }
        }
        if (creds != null && pnlViews.isNotEmpty()) lifecycleScope.launch {
            val api = DemoTradeClient.create()
            val bodies = HashMap<String, String?>()
            for (e in closed.filter { pnlViews.containsKey(it.id) }) {
                val tv = pnlViews[e.id] ?: continue
                val txt = bodies.getOrPut(e.symbol) { api.userTrades(creds, e.symbol, closed.filter { it.symbol == e.symbol }.minOf { it.createdAt }).let { r -> if (r.ok) r.body else null } }
                val v = txt?.let { com.ansardeo.bnb.demo.exec.DemoAccountView.netRealized(it, e.symbol, e.direction, e.createdAt, e.updatedAt + 120_000L) }
                if (v == null) { tv.text = "Realized PnL: not available (Binance did not answer)" }
                else { tv.setTextColor(if (v.signum() >= 0) green else red); tv.text = "Realized PnL (Binance, net of fees): ${PriceFormatter.formatPnl(v.toDouble())} — covers this coin/side in this entry's time window, so it may include other entries of the same coin/side" }
            }
        }
    }


    private fun makeItemCard(parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return box
    }

    private fun addCancelButton(box: LinearLayout, label: String, action: suspend () -> String) {
        val b = Button(this).apply { text = label; isAllCaps = false; setTextColor(white); background = rounded(0xFF5A1E1E.toInt(), 12) }
        box.addView(b, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
        b.setOnClickListener {
            AlertDialog.Builder(this).setTitle(label + "?").setMessage("Only this order is cancelled on Binance Demo. Other orders and open positions are not changed.")
                .setPositiveButton("CANCEL ORDER") { _, _ -> lifecycleScope.launch { toast(action()); loadDemoSnapshot(); demoOrdersRefresh?.invoke() } }
                .setNegativeButton("BACK", null).show()
        }
    }

    private fun pnlColor(v: Double?): Int = when { v == null -> muted; v > 0.0 -> green; v < 0.0 -> red; else -> muted }


    // ---------------- SETTINGS (central Dashboard) ----------------
    private fun fmtNum(v: Double): String = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')

    private fun settingsPage() {
        val root = pageContainer()
        root.addView(text("Settings — Dashboard", 20f, white, true))
        root.addView(text("Stage 2 (Binance Demo). New cycles take a snapshot of these values; existing cycles never change. No Take Profit: the profit exit is the ROI trailing stop. Tap a tile to edit it.", 11f, muted))
        addSpace(root, 8)

        // Auto-Trading status (the switch itself lives in More → Demo safety controls)
        val botRow = row().apply { background = rounded(card, 14); setPadding(dp(14), dp(8), dp(14), dp(8)) }
        botRow.addView(text("Stage 2 • Binance Demo", 13f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
        botRow.addView(text(if (DemoAutoTradeStore.isOn(this)) "Auto-Trading ON" else "Auto-Trading OFF", 12f, if (DemoAutoTradeStore.isOn(this)) green else yellow, true))
        root.addView(botRow, LinearLayout.LayoutParams(-1, -2))

        // Scope: Global defaults or one symbol (one compact row)
        val symEt = EditText(this).apply { hint = "Symbol (blank = Global)"; setText(""); setTextColor(white); setHintTextColor(muted); setBackgroundColor(inputBg); setPadding(dp(10), dp(8), dp(10), dp(8)); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        val scopeRow = row().apply { background = rounded(card, 14); setPadding(dp(10), dp(8), dp(10), dp(8)) }
        scopeRow.addView(symEt, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        val loadBtn = Button(this).apply { text = "LOAD"; isAllCaps = false; setTextColor(0xFF000000.toInt()); background = rounded(yellow, 12) }
        scopeRow.addView(loadBtn, LinearLayout.LayoutParams(dp(84), dp(44)))
        addSpace(root, 8); root.addView(scopeRow, LinearLayout.LayoutParams(-1, -2))
        val formBox = column()
        var renderSavedRef: () -> Unit = {}
        fun renderSaved() = renderSavedRef()
        fun renderForm() {
            formBox.removeAllViews()
            val sym = symEt.text.toString().trim().uppercase(Locale.US)
            val isGlobal = sym.isBlank() || sym == "GLOBAL"
            val key = if (isGlobal) "__GLOBAL__" else sym
            val eff = LocalCoinSettingsStore.load(this, key)
            val cur = mapOf<String, Any>(
                SettingKeys.MARGIN to eff.marginAmount, SettingKeys.LEVERAGE to eff.leverage,
                SettingKeys.TRAILING_ENABLED to eff.trailingEnabled, SettingKeys.TRAILING_ACTIVATION_ROI to eff.trailingActivationRoi, SettingKeys.TRAILING_CALLBACK_ROI to eff.trailingCallbackRoi,
                SettingKeys.AVERAGING_ENABLED to eff.averagingEnabled, SettingKeys.AVERAGING_MARGIN to eff.averagingMargin, SettingKeys.AVERAGING_TRIGGER_ROI to eff.averagingTriggerRoi,
                SettingKeys.REENTRY_ENABLED to eff.reentryEnabled,
                SettingKeys.OPPOSITE_ENABLED to eff.oppositeEnabled, SettingKeys.OPPOSITE_MARGIN to eff.oppositeMargin, SettingKeys.OPPOSITE_TRIGGER_ROI to eff.oppositeTriggerRoi,
                SettingKeys.OPPOSITE_CALLBACK_ROI to eff.oppositeCallbackRoi,
                SettingKeys.STOP_LOSS_ENABLED to eff.stopLossEnabled, SettingKeys.STOP_LOSS_ROI to eff.stopLossRoi,
            )
            val boolControls = LinkedHashMap<String, Switch>()
            val numControls = LinkedHashMap<String, EditText>()
            lateinit var levSpin: Spinner
            var refreshAll: () -> Unit = {}
            val lockNote = text("", 12f, yellow, true)

            fun header(card: LinearLayout, label: String, k: String) {
                val src = LocalCoinSettingsStore.sourceOf(this, key, k)
                val h = row()
                h.addView(text(label, 12f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
                h.addView(text("[${src.label}]", 11f, when (src) { SettingSource.SYMBOL_OVERRIDE -> yellow; SettingSource.GLOBAL -> green; else -> muted }, true))
                if (src == SettingSource.SYMBOL_OVERRIDE) {
                    h.addView(text("  RESET", 11f, red, true).apply { setOnClickListener {
                        lifecycleScope.launch {
                            val r = DemoSettingsService.get(this@MainActivity).reset(this@MainActivity, key, k)
                            if (r.ok) renderForm() else { lockNote.text = r.message }
                            renderSaved()
                        }
                    } })
                }
                card.addView(h)
            }
            fun num(card: LinearLayout, label: String, k: String, signed: Boolean = false) {
                header(card, label, k)
                val e = EditText(this).apply {
                    setText(fmtNum((cur[k] as Number).toDouble())); setTextColor(white); setHintTextColor(muted); setBackgroundColor(inputBg); setPadding(dp(10), dp(8), dp(10), dp(8))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or (if (signed) android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                }
                e.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { refreshAll() }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
                numControls[k] = e; card.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }

            formBox.addView(text(if (isGlobal) "Scope: GLOBAL defaults" else "Scope: $sym (effective values, ● = custom for this symbol)", 12f, yellow, true))
            if (!isGlobal) formBox.addView(lockNote)

            fun numOf(k: String): Double? = numControls[k]?.text?.toString()?.toDoubleOrNull()
            fun boolOf(k: String): Boolean = boolControls[k]?.isChecked ?: (cur[k] as Boolean)
            fun f(v: Double?): String = if (v == null) "--" else fmtNum(v)
            fun levNow(): Int = LeverageConfig.SUPPORTED_LEVERAGES.getOrElse(levSpin.selectedItemPosition) { eff.leverage }

            levSpin = Spinner(this).apply {
                adapter = createDarkSpinnerAdapter(LeverageConfig.SUPPORTED_LEVERAGES.map { "${it}x" }); setPopupBackgroundDrawable(rounded(inputBg, 10)); setBackgroundColor(inputBg)
                setSelection(LeverageConfig.SUPPORTED_LEVERAGES.indexOf(eff.leverage).coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { refreshAll() }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            val tiles = ArrayList<SettingTile>()
            val grid = column()
            fun relayout() {
                tiles.forEach { t -> (t.view.parent as? ViewGroup)?.removeView(t.view) }
                grid.removeAllViews()
                var i = 0
                while (i < tiles.size) {
                    val t = tiles[i]
                    val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    if (t.full || t.expanded) { rowL.addView(t.view, LinearLayout.LayoutParams(-1, -2)); i++ }
                    else {
                        rowL.addView(t.view, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                        val n = tiles.getOrNull(i + 1)
                        if (n != null && !n.full && !n.expanded) { rowL.addView(n.view, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) }); i += 2 }
                        else { rowL.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(4) }); i++ }
                    }
                    grid.addView(rowL, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
            }

            /** One compact tile: title (+ enable switch), live summary; tap the title to expand the inline editor. */
            fun tile(title: String, full: Boolean, toggleKey: String?, keys: List<String>, hint: String, summary: (TextView, TextView) -> Unit, editor: (LinearLayout) -> Unit): SettingTile {
                val box = cardView().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
                val head = row()
                val titleTv = text(title, 13f, yellow, true)
                head.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
                val custom = !isGlobal && keys.any { LocalCoinSettingsStore.sourceOf(this, key, it) == SettingSource.SYMBOL_OVERRIDE }
                if (custom) head.addView(text("● ", 11f, yellow, true))
                if (toggleKey != null) {
                    val sw = Switch(this).apply { isChecked = cur[toggleKey] as Boolean; setOnCheckedChangeListener { _, _ -> refreshAll() } }
                    boolControls[toggleKey] = sw
                    head.addView(sw)
                }
                val chev = text("  ▾", 14f, muted, true)
                head.addView(chev)
                box.addView(head)
                val big = text("", 17f, white, true)
                val cap = text("", 11f, muted)
                box.addView(big); box.addView(cap)
                val ed = column().apply { visibility = View.GONE }
                addSpace(ed, 6)
                ed.addView(text(hint, 11f, muted)); addSpace(ed, 6)
                if (toggleKey != null) header(ed, "Enabled", toggleKey)
                editor(ed)
                box.addView(ed)
                val t = SettingTile(box, full)
                t.refresh = {
                    summary(big, cap)
                    val on = toggleKey?.let { boolOf(it) } ?: true
                    big.alpha = if (on) 1f else 0.4f; cap.alpha = if (on) 1f else 0.4f
                }
                head.setOnClickListener {
                    t.expanded = !t.expanded
                    ed.visibility = if (t.expanded) View.VISIBLE else View.GONE
                    chev.text = if (t.expanded) "  ▴" else "  ▾"
                    relayout()
                }
                tiles.add(t)
                return t
            }

            val derivedTv = text("", 11f, yellow)
            val tDefault = tile("Default Trading Settings", true, null, listOf(SettingKeys.MARGIN, SettingKeys.LEVERAGE),
                "LONG and SHORT are always permitted (not configurable).",
                { big, cap -> big.text = "${f(numOf(SettingKeys.MARGIN))} USDT  |  ${levNow()}x"; cap.text = "Margin  |  Leverage (3x–20x)" }
            ) { ed ->
                num(ed, "Default margin (USDT)", SettingKeys.MARGIN)
                header(ed, "Default leverage (3x–20x)", SettingKeys.LEVERAGE)
                ed.addView(levSpin, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            tDefault.view.addView(derivedTv, 3)

            tile("Stop Loss (per cycle)", false, SettingKeys.STOP_LOSS_ENABLED, listOf(SettingKeys.STOP_LOSS_ENABLED, SettingKeys.STOP_LOSS_ROI),
                "One Stop Loss per whole cycle, as Position ROI (leverage included). Hit = all remaining quantity closed and that cycle's pending orders cancelled. Disabled = no Stop Loss at all. Enter a positive number (80 = -80% ROI).",
                { big, cap -> big.text = if (boolOf(SettingKeys.STOP_LOSS_ENABLED)) "-${f(numOf(SettingKeys.STOP_LOSS_ROI))}%" else "OFF"; cap.text = "Cycle Stop Loss ROI" }
            ) { ed -> num(ed, "Stop Loss ROI (%) — default 80", SettingKeys.STOP_LOSS_ROI) }

            tile("Trailing Stop (ROI)", false, SettingKeys.TRAILING_ENABLED, listOf(SettingKeys.TRAILING_ENABLED, SettingKeys.TRAILING_ACTIVATION_ROI, SettingKeys.TRAILING_CALLBACK_ROI),
                "Activation and callback are ROI percentages (not price percentages). The only profit exit; there is no fixed Take Profit.",
                { big, cap -> big.text = "${f(numOf(SettingKeys.TRAILING_ACTIVATION_ROI))}%  |  ${f(numOf(SettingKeys.TRAILING_CALLBACK_ROI))}%"; cap.text = "Activation  |  Callback ROI" }
            ) { ed ->
                num(ed, "Activation ROI (%)", SettingKeys.TRAILING_ACTIVATION_ROI)
                num(ed, "Callback ROI (%)", SettingKeys.TRAILING_CALLBACK_ROI)
            }

            tile("Dual-Side Re-entry", false, SettingKeys.REENTRY_ENABLED, listOf(SettingKeys.REENTRY_ENABLED),
                "After a profitable trailing-stop close, a LONG (below the close) and a SHORT (above the close) re-entry LIMIT are prepared. The two orders are independent: a fill starts a NEW cycle and does NOT cancel the other order, which stays pending until it fills or you cancel it manually (Open Orders → Cancel). Re-entry ROI is automatic: Same-Side = 50% and Opposite-Side = 100% of the ACTUAL booked ROI of the closed cycle (leverage-aware). Only the best-priced pending entry per symbol+direction is kept (LONG lowest, SHORT highest).",
                { big, cap -> big.text = "50% / 100%"; cap.text = "Same / Opposite of booked ROI" }
            ) { _ -> }

            tile("Averaging", false, SettingKeys.AVERAGING_ENABLED, listOf(SettingKeys.AVERAGING_ENABLED, SettingKeys.AVERAGING_MARGIN, SettingKeys.AVERAGING_TRIGGER_ROI),
                "One averaging LIMIT per cycle, priced where Position ROI reaches the trigger (leverage included). Independent of Opposite-Side. Trigger is negative (default -60) and limited by the Stop Loss ROI.",
                { big, cap -> big.text = "${f(numOf(SettingKeys.AVERAGING_MARGIN))} USDT  |  ${f(numOf(SettingKeys.AVERAGING_TRIGGER_ROI))}%"; cap.text = "Margin  |  Trigger ROI" }
            ) { ed ->
                num(ed, "Averaging margin (USDT)", SettingKeys.AVERAGING_MARGIN)
                num(ed, "Averaging Trigger ROI (%) — negative, default -60", SettingKeys.AVERAGING_TRIGGER_ROI, signed = true)
            }

            tile("Opposite-Side", true, SettingKeys.OPPOSITE_ENABLED, listOf(SettingKeys.OPPOSITE_ENABLED, SettingKeys.OPPOSITE_MARGIN, SettingKeys.OPPOSITE_TRIGGER_ROI, SettingKeys.OPPOSITE_CALLBACK_ROI),
                "Activates when the position's ROI reaches the trigger, then trails the adverse move; when price reverses by the callback ROI, a LIMIT entry opens a separate opposite cycle (own margin, leverage, Stop Loss and trailing). Only if no opposite-direction cycle is open. Controlled only by this toggle; independent of Averaging.",
                { big, cap -> big.text = "${f(numOf(SettingKeys.OPPOSITE_MARGIN))} USDT  |  ${f(numOf(SettingKeys.OPPOSITE_TRIGGER_ROI))}% / ${f(numOf(SettingKeys.OPPOSITE_CALLBACK_ROI))}%"; cap.text = "Margin  |  Trigger / Callback ROI" }
            ) { ed ->
                num(ed, "Opposite-side margin (USDT)", SettingKeys.OPPOSITE_MARGIN)
                num(ed, "Opposite-Side Trigger ROI (%) — negative, default -50 (activation)", SettingKeys.OPPOSITE_TRIGGER_ROI, signed = true)
                num(ed, "Opposite-Side Callback ROI (%) — reversal confirmation, default 5", SettingKeys.OPPOSITE_CALLBACK_ROI)
            }

            refreshAll = {
                tiles.forEach { it.refresh() }
                val slTxt = if (boolOf(SettingKeys.STOP_LOSS_ENABLED)) "-${f(numOf(SettingKeys.STOP_LOSS_ROI))}%" else "Disabled"
                derivedTv.text = "Derived at ${levNow()}x: Stop Loss $slTxt | Averaging ${f(numOf(SettingKeys.AVERAGING_TRIGGER_ROI))}% | Opposite ${f(numOf(SettingKeys.OPPOSITE_TRIGGER_ROI))}% | Re-entry ${f(numOf(SettingKeys.TRAILING_ACTIVATION_ROI)?.div(2.0))}%"
            }
            refreshAll()
            relayout()
            formBox.addView(grid)
            addSpace(formBox, 10)

            val msg = text("", 12f, muted)
            val saveCard = column(); formBox.addView(saveCard)
            val saveBtn = addButton(saveCard, "SAVE SETTINGS", yellow)
            saveBtn.setOnClickListener {
                try {
                    val v = HashMap<String, Any>()
                    boolControls.forEach { (k, s) -> v[k] = s.isChecked }
                    numControls.forEach { (k, et) -> v[k] = et.text.toString().toDoubleOrNull() ?: throw IllegalArgumentException("Enter a valid number for every field.") }
                    listOf(SettingKeys.AVERAGING_TRIGGER_ROI, SettingKeys.OPPOSITE_TRIGGER_ROI).forEach { k -> v[k] = RoiMath.normalizeLossTrigger(v[k] as Double) }
                    v[SettingKeys.STOP_LOSS_ROI] = Math.abs(v[SettingKeys.STOP_LOSS_ROI] as Double)
                    v[SettingKeys.LEVERAGE] = LeverageConfig.requireSupported(LeverageConfig.SUPPORTED_LEVERAGES.getOrElse(levSpin.selectedItemPosition) { 6 })
                    // Strict validation (throws with a readable message) before anything is saved.
                    val candidate = LocalCoinSettings(
                        symbol = key,
                        marginAmount = v[SettingKeys.MARGIN] as Double, leverage = v[SettingKeys.LEVERAGE] as Int,
                        oppositeEnabled = v[SettingKeys.OPPOSITE_ENABLED] as Boolean, oppositeMargin = v[SettingKeys.OPPOSITE_MARGIN] as Double,
                        oppositeTriggerRoi = v[SettingKeys.OPPOSITE_TRIGGER_ROI] as Double,
                        oppositeCallbackRoi = v[SettingKeys.OPPOSITE_CALLBACK_ROI] as Double,
                        stopLossEnabled = v[SettingKeys.STOP_LOSS_ENABLED] as Boolean, stopLossRoi = v[SettingKeys.STOP_LOSS_ROI] as Double,
                        averagingEnabled = v[SettingKeys.AVERAGING_ENABLED] as Boolean, averagingMargin = v[SettingKeys.AVERAGING_MARGIN] as Double,
                        averagingTriggerRoi = v[SettingKeys.AVERAGING_TRIGGER_ROI] as Double,
                        reentryEnabled = v[SettingKeys.REENTRY_ENABLED] as Boolean,
                        trailingEnabled = v[SettingKeys.TRAILING_ENABLED] as Boolean,
                        trailingActivationRoi = v[SettingKeys.TRAILING_ACTIVATION_ROI] as Double, trailingCallbackRoi = v[SettingKeys.TRAILING_CALLBACK_ROI] as Double,
                    )
                    if (isGlobal) {
                        LocalCoinSettingsStore.saveGlobal(this, candidate)
                        msg.setTextColor(green); msg.text = "Global settings saved."; toast("Settings saved"); renderForm(); renderSaved()
                    } else {
                        // Only values that differ from what the symbol currently resolves to are stored as overrides.
                        val changes = HashMap<String, Any>()
                        for (k in SettingKeys.ALL) {
                            val nv = v[k] ?: continue
                            val ov = cur[k]
                            val differs = if (nv is Double && ov is Number) nv != ov.toDouble() else nv != ov
                            if (differs) changes[k] = nv
                        }
                        lifecycleScope.launch {
                            val r = DemoSettingsService.get(this@MainActivity).update(this@MainActivity, key, changes)
                            if (r.ok) { toast("Saved"); renderForm(); renderSaved() }
                            else { msg.setTextColor(red); msg.text = r.message }
                        }
                    }
                } catch (ex: Exception) { msg.setTextColor(red); msg.text = ex.message ?: "Save failed" }
            }
            saveCard.addView(msg)
            if (!isGlobal) lifecycleScope.launch {
                val lock = DemoSettingsService.get(this@MainActivity).lockReason(sym)
                if (lock != null) { lockNote.text = lock + " — saving, resetting and deleting are disabled for this symbol."; saveBtn.isEnabled = false; saveBtn.alpha = 0.4f }
            }
        }

        // ---- Saved Coin Settings (very bottom, below SAVE SETTINGS) ----
        val savedBox = column()
        var savedOpen = false
        fun renderSavedImpl() {
            lifecycleScope.launch {
                val symbols = LocalCoinSettingsStore.customSymbols(this@MainActivity)
                val locks = symbols.associateWith { runCatching { DemoSettingsService.get(this@MainActivity).lockReason(it) }.getOrNull() }
                savedBox.removeAllViews()
                val savedHead = row().apply { background = rounded(card, 14); setPadding(dp(14), dp(12), dp(14), dp(12)) }
                savedHead.addView(text("Saved Coin Settings (${symbols.size})", 15f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
                savedHead.addView(text(if (savedOpen) "▴" else "▾", 15f, muted, true))
                savedHead.setOnClickListener { savedOpen = !savedOpen; renderSavedRef() }
                savedBox.addView(savedHead, LinearLayout.LayoutParams(-1, -2))
                if (!savedOpen) return@launch
                addSpace(savedBox, 4)
                if (symbols.isEmpty()) { savedBox.addView(text("No symbol has custom settings yet. Type a symbol above and press SAVE SETTINGS to create one.", 12f, muted)); return@launch }
                for (sym in symbols) {
                    val eff = LocalCoinSettingsStore.load(this@MainActivity, sym)
                    val ov = LocalCoinSettingsStore.overridesFor(this@MainActivity, sym)
                    fun tag(k: String) = if (ov.containsKey(k)) "" else " (Global)"
                    fun onOff(b: Boolean) = if (b) "ON" else "OFF"
                    val lock = locks[sym]
                    val box = addCard(savedBox, sym)
                    box.addView(text("● CUSTOM SETTINGS" + (if (lock != null) "   🔒 LOCKED" else ""), 11f, if (lock != null) yellow else green, true))
                    addSpace(box, 6)
                    addLabel(box, "Initial Margin", "${fmtNum(eff.marginAmount)} USDT${tag(SettingKeys.MARGIN)}")
                    addLabel(box, "Leverage", "${eff.leverage}x${tag(SettingKeys.LEVERAGE)}")
                    addLabel(box, "Averaging", onOff(eff.averagingEnabled) + tag(SettingKeys.AVERAGING_ENABLED), if (eff.averagingEnabled) green else lockedColor)
                    addLabel(box, "Averaging Margin", "${fmtNum(eff.averagingMargin)} USDT${tag(SettingKeys.AVERAGING_MARGIN)}")
                    addLabel(box, "Averaging Trigger ROI", "${fmtNum(eff.averagingTriggerRoi)}%${tag(SettingKeys.AVERAGING_TRIGGER_ROI)}")
                    addLabel(box, "Opposite-Side", onOff(eff.oppositeEnabled) + tag(SettingKeys.OPPOSITE_ENABLED), if (eff.oppositeEnabled) green else lockedColor)
                    addLabel(box, "Opposite-Side Margin", "${fmtNum(eff.oppositeMargin)} USDT${tag(SettingKeys.OPPOSITE_MARGIN)}")
                    addLabel(box, "Opposite-Side Trigger ROI", "${fmtNum(eff.oppositeTriggerRoi)}%${tag(SettingKeys.OPPOSITE_TRIGGER_ROI)}")
                    addLabel(box, "Trailing Stop", if (eff.trailingEnabled) "ON  (activation ${fmtNum(eff.trailingActivationRoi)}% / callback ${fmtNum(eff.trailingCallbackRoi)}% ROI)${tag(SettingKeys.TRAILING_ACTIVATION_ROI)}" else "OFF${tag(SettingKeys.TRAILING_ENABLED)}")
                    addLabel(box, "Re-entry", onOff(eff.reentryEnabled) + " (50% same / 100% opposite of booked ROI)" + tag(SettingKeys.REENTRY_ENABLED), if (eff.reentryEnabled) green else lockedColor)
                    addLabel(box, "Stop Loss", (if (eff.stopLossEnabled) "ON, -${fmtNum(eff.stopLossRoi)}% ROI" else "OFF") + tag(SettingKeys.STOP_LOSS_ENABLED), if (eff.stopLossEnabled) green else lockedColor)
                    addLabel(box, "Opposite Callback ROI", "${fmtNum(eff.oppositeCallbackRoi)}%${tag(SettingKeys.OPPOSITE_CALLBACK_ROI)}")
                    if (lock != null) { box.addView(text(lock, 12f, yellow, true)); addSpace(box, 4) }
                    val actions = row(); box.addView(actions)
                    val edit = Button(this@MainActivity).apply { text = "Edit"; isAllCaps = false; isEnabled = lock == null; alpha = if (lock == null) 1f else 0.4f; setTextColor(if (lock == null) 0xFF000000.toInt() else white); background = rounded(if (lock == null) yellow else inputBg, 12) }
                    val del = Button(this@MainActivity).apply { text = "Delete"; isAllCaps = false; isEnabled = lock == null; alpha = if (lock == null) 1f else 0.4f; setTextColor(white); background = rounded(if (lock == null) 0xFF5A1E1E.toInt() else inputBg, 12) }
                    actions.addView(edit, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
                    actions.addView(del, LinearLayout.LayoutParams(0, dp(44), 1f))
                    edit.setOnClickListener {
                        symEt.setText(sym); renderForm()
                        (root.parent as? ScrollView)?.smoothScrollTo(0, 0)
                        toast("Editing $sym")
                    }
                    del.setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle("Delete custom settings for $sym?")
                            .setMessage("$sym will inherit Global Settings again. Existing cycles, positions and orders are not changed.")
                            .setPositiveButton("DELETE") { _, _ ->
                                lifecycleScope.launch {
                                    val r = DemoSettingsService.get(this@MainActivity).delete(this@MainActivity, sym)
                                    toast(r.message)
                                    if (symEt.text.toString().trim().uppercase(Locale.US) == sym) renderForm()
                                    renderSaved()
                                }
                            }.setNegativeButton("CANCEL", null).show()
                    }
                }
            }
        }
        renderSavedRef = { renderSavedImpl() }
        loadBtn.setOnClickListener { renderForm() }
        root.addView(formBox)
        addSpace(root, 16)
        root.addView(savedBox)
        renderForm()
        renderSaved()
        // Keep the lock state fresh while this tab is open (positions/orders open and close in the background).
        val gen = refreshGen
        fun tick() {
            if (gen != refreshGen || currentPage != "Settings") return
            renderSaved()
            val r = Runnable { tick() }; refreshRunnable = r; mainHandler.postDelayed(r, 5000)
        }
        val r0 = Runnable { tick() }; refreshRunnable = r0; mainHandler.postDelayed(r0, 5000)
    }


    // ---------------- MORE ----------------
    private fun morePage() {
        val root = pageContainer()
        root.addView(text("More", 23f, white, true))
        root.addView(text("App status, Binance Demo setup, safety controls and events. Binance DEMO only — no real-money trading.", 12f, muted))

        val info = addCard(root, "APP STATUS")
        addLabel(info, "Application", "BNB — Binance Android Bot")
        addLabel(info, "Version", BuildConfig.VERSION_NAME)
        addLabel(info, "Stage", "Stage 2 — Binance Demo", green)
        addLabel(info, "Real trading", "Not available", lockedColor)
        addLabel(info, "Orders", "BINANCE DEMO ONLY (no real money)", green)
        addLabel(info, "Auto-Trading", if (DemoAutoTradeStore.isOn(this)) "ON (only while app is open)" else "OFF", if (DemoAutoTradeStore.isOn(this)) green else yellow)
        addLabel(info, "Market data", "Binance public USDⓈ-M endpoints (read-only)", muted)

        addDemoApiCard(root)
        addDemoProbeCard(root)
        addDemoTrailingProbeCard(root)
        addDemoSetupCard(root)
        addDemoOrdersCard(root)

        val ctl = addCard(root, "DEMO SAFETY CONTROLS (BINANCE DEMO ONLY)")
        val result = text("", 12f, muted)
        val repoC = DemoCredentialStore.repository(this)
        val eng = DemoStrategyEngineProvider.get(this)
        fun withCreds(block: (com.ansardeo.bnb.demo.DemoCredentials) -> Unit) {
            val c = repoC.load()
            if (c == null) { result.setTextColor(red); result.text = "Save your Demo keys first." } else block(c)
        }
        fun autoOn() = DemoAutoTradeStore.isOn(this)
        addButton(ctl, if (autoOn()) "PAUSE AUTO-TRADING (turn OFF)" else "RESUME AUTO-TRADING (turn ON)", inputBg).setOnClickListener {
            if (autoOn()) { DemoAutoTradeStore.set(this, false); updateTopStatus(); showPage("More") }
            else AlertDialog.Builder(this).setTitle("Turn Demo Auto-Trading ON?")
                .setMessage("Binance DEMO only. While this app is OPEN the strategy may place Averaging, Opposite-Side and Re-entry LIMIT orders and close a cycle at market when its ROI Trailing Stop triggers.")
                .setPositiveButton("TURN ON") { _, _ -> DemoAutoTradeStore.set(this, true); updateTopStatus(); showPage("More") }.setNegativeButton("BACK", null).show()
        }
        addButton(ctl, "CANCEL PENDING ORDERS", inputBg).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Cancel all pending Demo orders?")
                .setMessage("Cancels every unfilled Demo entry order and waiting Re-entry order. Open positions and their Stop Losses stay.")
                .setPositiveButton("CANCEL ORDERS") { _, _ -> withCreds { c -> lifecycleScope.launch { val r = eng.cancelAllPending(c); result.setTextColor(muted); result.text = r; toast("Done") } } }
                .setNegativeButton("BACK", null).show()
        }
        addButton(ctl, "EMERGENCY STOP", 0xFF3A3416.toInt()).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Emergency stop?")
                .setMessage("Turns Auto-Trading OFF and cancels all pending Demo orders. Open positions are NOT closed and keep their Stop Losses.")
                .setPositiveButton("STOP") { _, _ ->
                    DemoAutoTradeStore.set(this, false); updateTopStatus()
                    withCreds { c -> lifecycleScope.launch { val r = eng.cancelAllPending(c); result.setTextColor(muted); result.text = "Auto-Trading OFF. $r" } }
                }.setNegativeButton("BACK", null).show()
        }
        addButton(ctl, "PANIC CLOSE ALL DEMO POSITIONS", 0xFF5A1E1E.toInt()).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Panic close?")
                .setMessage("Binance DEMO: turns Auto-Trading OFF, cancels pending orders, closes every open Demo position at market and removes its Stop Loss and Trailing Stop orders. No re-entry follows.")
                .setPositiveButton("CLOSE ALL") { _, _ ->
                    DemoAutoTradeStore.set(this, false); updateTopStatus()
                    withCreds { c -> lifecycleScope.launch { result.setTextColor(muted); result.text = "Closing on Binance Demo..."; val r = eng.panicCloseAll(c); result.text = r } }
                }.setNegativeButton("BACK", null).show()
        }
        ctl.addView(result)

        val logs = addCard(root, "DEMO EVENTS (last 25)", "Strategy and order events only. Never contains keys, signatures or request data.")
        val ev = DemoExecutionProvider.get(this).events(25)
        if (ev.isEmpty()) logs.addView(text("No events yet.", 12f, muted))
        val tf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        ev.forEach { logs.addView(text("${tf.format(java.util.Date(it.first))}  ${it.second}", 11f, muted)); addSpace(logs, 4) }
    }

    // ---------------- Binance Demo API configuration (Stage 2: credentials + read-only connection test; NO orders) ----------------
    private fun addDemoApiCard(root: LinearLayout) {
        val card = addCard(root, "BINANCE DEMO API (USDⓈ-M FUTURES — DEMO ONLY)",
            "Demo environment only (demo-fapi.binance.com). Live keys are never accepted or used. This screen only stores your Demo keys and tests the connection with read-only requests: it places NO orders.")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)
        val repo = DemoCredentialStore.repository(this)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        fun refresh() {
            box.removeAllViews()
            when (val st = repo.state()) {
                is CredentialState.None -> addLabel(box, "Keys", "Not saved", lockedColor)
                is CredentialState.Saved -> addLabel(box, "API Key", st.maskedKey + "  (Secret hidden)", green)
                is CredentialState.Unreadable -> addLabel(box, "Keys", "Stored keys cannot be read — please Replace them", red)
            }
            val last = DemoConnectionStatusStore.load(this)
            if (last == null) addLabel(box, "Connection", "Not tested", muted)
            else {
                addLabel(box, "Connection", (if (last.ok) "CONNECTED" else "FAILED") + "  ·  " + fmt.format(Date(last.atMillis)), if (last.ok) green else red)
                box.addView(text(last.summary, 12f, if (last.ok) white else red))
                last.steps.forEach { box.addView(text(it, 11f, muted)) }
            }
        }

        fun showKeyDialog() {
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
            val keyField = EditText(this).apply {
                hint = "Demo API Key"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true); setTextColor(white); setHintTextColor(muted)
            }
            val secretField = EditText(this).apply {
                hint = "Demo Secret Key"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true); setTextColor(white); setHintTextColor(muted)
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                keyField.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                secretField.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            form.addView(keyField); form.addView(secretField)
            val dialog = AlertDialog.Builder(this).setTitle(if (repo.hasCredentials()) "Replace Demo API keys" else "Save Demo API keys")
                .setMessage("Paste the keys created in Binance DEMO Trading (not Live). They are encrypted with the Android Keystore on this device.")
                .setView(form)
                .setPositiveButton("SAVE", null)
                .setNegativeButton("CANCEL", null).create()
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) // no screenshots / recents preview of the keys
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val err = repo.save(keyField.text.toString(), secretField.text.toString())
                    if (err != null) { toast(err); return@setOnClickListener }
                    keyField.setText(""); secretField.setText("")
                    DemoConnectionStatusStore.clear(this) // a new key has not been tested yet
                    toast("Demo keys saved securely."); dialog.dismiss(); refresh()
                }
            }
            dialog.show()
        }

        addButton(card, "SAVE / REPLACE KEYS", inputBg).setOnClickListener { showKeyDialog() }
        val testBtn = addButton(card, "TEST CONNECTION (read-only)", yellow)
        testBtn.setOnClickListener {
            val creds = repo.load()
            if (creds == null) { toast(if (repo.hasCredentials()) "Stored keys cannot be read. Replace them." else "Save your Demo keys first."); return@setOnClickListener }
            testBtn.isEnabled = false; testBtn.text = "Testing…"
            lifecycleScope.launch {
                val report = BinanceDemoApi.create().testConnection(creds.apiKey, creds.secret)
                DemoConnectionStatusStore.save(this@MainActivity, report)
                testBtn.isEnabled = true; testBtn.text = "TEST CONNECTION (read-only)"
                refresh(); toast(if (report.ok) "Connected to Binance Demo" else "Connection failed")
            }
        }
        addButton(card, "DELETE KEYS", 0xFF5A1E1E.toInt()).setOnClickListener {
            if (!repo.hasCredentials()) { toast("No keys are saved."); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Delete Demo API keys?")
                .setMessage("The saved keys and the encryption key are removed from this device. Nothing else (positions, settings, history) is changed.")
                .setPositiveButton("DELETE") { _, _ -> repo.delete(); DemoConnectionStatusStore.clear(this); toast("Demo keys deleted."); refresh() }
                .setNegativeButton("CANCEL", null).show()
        }
        refresh()
    }

    // ---------------- Binance Demo Compatibility Probe (Phase 3A step 2: tiny Demo orders that are always cancelled; opens NO position) ----------------
    private fun addDemoTrailingProbeCard(root: LinearLayout) {
        val card = addCard(root, "BINANCE DEMO TRAILING STOP PROBE",
            "Checks, on Binance DEMO only, that an exchange-side Trailing Stop order (closing only, with activation price and callback) is accepted in Hedge Mode, can be listed and cancelled. It places one far-away order (BTCUSDT), cancels it, and opens NO position. Until this passes the app keeps using its app-side Trailing Stop.")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)
        val repo = DemoCredentialStore.repository(this)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fun refresh() {
            box.removeAllViews()
            val t = DemoTrailingProbeStore.text(this)
            if (t == null) addLabel(box, "Trailing probe", "Not run", muted)
            else {
                val ok = DemoTrailingProbeStore.supported(this)
                addLabel(box, "Last trailing probe", (if (ok) "PASSED (exchange Trailing Stop in use)" else "NOT PASSED (app-side Trailing Stop in use)") + "  ·  " + fmt.format(Date(DemoTrailingProbeStore.at(this))), if (ok) green else red)
                t.lines().forEach { if (it.isNotBlank()) box.addView(text(it, 11f, muted)) }
            }
        }
        val runBtn = addButton(card, "RUN TRAILING PROBE (Demo, cancels its own order)", yellow)
        runBtn.setOnClickListener {
            val creds = repo.load()
            if (creds == null) { toast(if (repo.hasCredentials()) "Stored keys cannot be read. Replace them." else "Save your Demo keys first."); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Run Trailing Stop probe?")
                .setMessage("Sends one tiny Trailing Stop test order to Binance DEMO (BTCUSDT), 10% above the market so it cannot trigger, and cancels it. No position is opened and no setting is changed.")
                .setPositiveButton("RUN") { _, _ ->
                    runBtn.isEnabled = false
                    lifecycleScope.launch {
                        val r = DemoTrailingProbe(DemoTradeClient.create()).run(creds)
                        DemoTrailingProbeStore.save(this@MainActivity, r, "BTCUSDT")
                        runBtn.isEnabled = true; refresh(); toast(if (r.passed && r.cleanupOk) "Trailing probe passed" else "Trailing probe finished — see report")
                    }
                }.setNegativeButton("CANCEL", null).show()
        }
        addButton(card, "COPY REPORT", inputBg).setOnClickListener {
            val t = DemoTrailingProbeStore.text(this)
            if (t == null) { toast("Run the trailing probe first."); return@setOnClickListener }
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("BNB trailing probe report", t)); toast("Report copied")
        }
        refresh()
    }

    private fun addDemoProbeCard(root: LinearLayout) {
        val card = addCard(root, "BINANCE DEMO COMPATIBILITY PROBE",
            "Checks, on Binance DEMO only, that LIMIT orders and an exchange-side Stop Loss can be placed, verified and cancelled. It places a far-away LIMIT order and a far-away Stop Loss order (BTCUSDT), cancels both, and opens NO position. It changes no account setting. Trading stays on Virtual.")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)
        val repo = DemoCredentialStore.repository(this)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fun refresh() {
            box.removeAllViews()
            val t = DemoProbeStore.text(this)
            if (t == null) addLabel(box, "Probe", "Not run", muted)
            else {
                val ok = DemoProbeStore.stopLossSupported(this)
                addLabel(box, "Last probe", (if (ok) "PASSED" else "NOT PASSED") + "  ·  " + fmt.format(Date(DemoProbeStore.at(this))), if (ok) green else red)
                t.lines().forEach { if (it.isNotBlank()) box.addView(text(it, 11f, muted)) }
            }
        }
        val runBtn = addButton(card, "RUN PROBE (Demo, cancels its own orders)", yellow)
        runBtn.setOnClickListener {
            val creds = repo.load()
            if (creds == null) { toast(if (repo.hasCredentials()) "Stored keys cannot be read. Replace them." else "Save your Demo keys first."); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Run Demo probe?")
                .setMessage("This sends two tiny test orders to Binance DEMO (BTCUSDT), far from the market, and cancels them. No position is opened and no setting is changed.")
                .setPositiveButton("RUN") { _, _ ->
                    runBtn.isEnabled = false
                    lifecycleScope.launch {
                        val report = DemoCompatibilityProbe(DemoTradeClient.create()).run(creds) { msg -> runBtn.text = msg }
                        DemoProbeStore.save(this@MainActivity, report)
                        runBtn.isEnabled = true; runBtn.text = "RUN PROBE (Demo, cancels its own orders)"
                        refresh(); toast(if (report.stopLossSupported && report.cleanupOk) "Probe passed" else "Probe finished — see report")
                    }
                }
                .setNegativeButton("CANCEL", null).show()
        }
        addButton(card, "COPY REPORT", inputBg).setOnClickListener {
            val t = DemoProbeStore.text(this)
            if (t == null) { toast("Run the probe first."); return@setOnClickListener }
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("BNB probe report", t))
            toast("Report copied")
        }
        refresh()
    }

    /** Coin picker for the Binance Demo cards (type or choose; the list is the Binance USDⓈ-M symbol list). [onPicked] runs when a coin is chosen from the list. */
    private fun demoSymbolField(initial: String, onPicked: () -> Unit): AutoCompleteTextView {
        val f = AutoCompleteTextView(this).apply {
            hint = "Coin (e.g. ETHUSDT)"; setText(initial); setSingleLine(true); setTextColor(white); setHintTextColor(muted); threshold = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        lifecycleScope.launch {
            val list = marketRepo.getUsdmSymbols().getOrElse { listOf("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT") }
            f.setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, list))
        }
        f.setOnItemClickListener { _, _, _, _ -> onPicked() }
        f.setOnFocusChangeListener { _, has -> if (!has) onPicked() }
        return f
    }
    private fun pickedSymbol(f: AutoCompleteTextView): String? = f.text.toString().trim().uppercase().takeIf { Regex("[A-Z0-9]{2,20}USDT").matches(it) }

    // ---------------- Binance Demo Safety Setup (Phase 3A step 3: ISOLATED margin + leverage for one symbol; NO orders) ----------------
    private fun addDemoSetupCard(root: LinearLayout) {
        val card = addCard(root, "BINANCE DEMO SAFETY SETUP",
            "Pick the coin, then make it safe on Binance DEMO (do this once per coin before its first entry): checks Hedge Mode (never changed by the app), sets ISOLATED margin and your configured leverage, then reads both back from Binance. Nothing is changed while the symbol has a position or any order. Places NO orders. Trading stays on Virtual.")
        lateinit var refresh: () -> Unit
        val symField = demoSymbolField("BTCUSDT") { refresh() }
        card.addView(symField)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)
        val repo = DemoCredentialStore.repository(this)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        refresh = {
            box.removeAllViews()
            val sym = pickedSymbol(symField)
            val t = sym?.let { DemoSymbolSetupStore.text(this, it) }
            if (sym == null) addLabel(box, "Coin", "Enter a valid USDT coin, e.g. ETHUSDT", red)
            else if (t == null) addLabel(box, "Setup $sym", "Not run", muted)
            else {
                val ok = DemoSymbolSetupStore.ready(this, sym)
                addLabel(box, "Last setup $sym", (if (ok) "READY" else "NOT READY") + "  ·  " + fmt.format(Date(DemoSymbolSetupStore.at(this, sym))), if (ok) green else red)
                t.lines().forEach { if (it.isNotBlank()) box.addView(text(it, 11f, muted)) }
            }
        }
        val runBtn = addButton(card, "RUN SAFETY SETUP (Demo)", yellow)
        runBtn.setOnClickListener {
            val creds = repo.load()
            if (creds == null) { toast(if (repo.hasCredentials()) "Stored keys cannot be read. Replace them." else "Save your Demo keys first."); return@setOnClickListener }
            val symbol = pickedSymbol(symField) ?: return@setOnClickListener toast("Enter a valid USDT coin, e.g. ETHUSDT.")
            val lev = LocalCoinSettingsStore.load(this, symbol).leverage
            AlertDialog.Builder(this).setTitle("Run Demo safety setup?")
                .setMessage("On Binance DEMO this will set $symbol to ISOLATED margin and ${lev}x leverage (your configured leverage) if it is not already, and read them back. No order is placed. Nothing is changed if $symbol has a position or any order.")
                .setPositiveButton("RUN") { _, _ ->
                    runBtn.isEnabled = false
                    lifecycleScope.launch {
                        val report = DemoSymbolSetup(DemoTradeClient.create(), symbol, lev).run(creds) { msg -> runBtn.text = msg }
                        DemoSymbolSetupStore.save(this@MainActivity, report)
                        runBtn.isEnabled = true; runBtn.text = "RUN SAFETY SETUP (Demo)"
                        refresh(); toast(if (report.ready) "Setup confirmed for $symbol" else "Setup not ready — see report")
                    }
                }
                .setNegativeButton("CANCEL", null).show()
        }
        addButton(card, "COPY REPORT", inputBg).setOnClickListener {
            val t = pickedSymbol(symField)?.let { DemoSymbolSetupStore.text(this, it) }
            if (t == null) { toast("Run the setup for this coin first."); return@setOnClickListener }
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("BNB setup report", t))
            toast("Report copied")
        }
        refresh()
    }

    // ---------------- Binance Demo Orders (Phase 3A steps 4+5: manual LIMIT entry with its own exchange-side Stop Loss; Virtual stays default) ----------------
    private var demoOrdersRefresh: (() -> Unit)? = null
    private var demoPollJob: kotlinx.coroutines.Job? = null

    override fun onStart() { super.onStart(); startDemoPoll() }
    override fun onStop() { demoPollJob?.cancel(); demoPollJob = null; super.onStop() }

    /** Tracks fills, places/verifies Stop Losses and resolves unknown orders: once at app start, then every 5 seconds while the app is open and there is work. */
    private fun startDemoPoll() {
        demoPollJob?.cancel()
        val svc = DemoExecutionProvider.get(this); val repo = DemoCredentialStore.repository(this)
        val engine = DemoStrategyEngineProvider.get(this)
        demoPollJob = lifecycleScope.launch {
            while (true) {
                try {
                    if (positionsVisible) { loadDemoSnapshot(); demoOrdersRefresh?.invoke() }
                    if (engine.hasLiveEntries()) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { engine.refreshMarks() }; demoOrdersRefresh?.invoke() }
                    val auto = DemoAutoTradeStore.isOn(this@MainActivity)
                    val creds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (svc.hasWork() || (auto && engine.hasOpenCycles())) repo.load() else null }
                    if (creds != null) {
                        svc.refresh(creds) // fills, Stop Loss placement/verification (always)
                        if (auto) engine.tick(creds) // strategy decisions (only while Demo Auto-Trading is ON)
                        demoOrdersRefresh?.invoke()
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { /* the next round retries */ }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private fun addDemoOrdersCard(root: LinearLayout) {
        val card = addCard(root, "BINANCE DEMO ORDERS",
            "Manual LIMIT entry on Binance DEMO. Every filled entry gets its own exchange-side Stop Loss (from its actual fill price, your Stop Loss ROI setting). A Stop Loss is shown as ACTIVE only after Binance Demo confirmed it. If it cannot be confirmed, the unprotected quantity is closed at market and new entries are halted. Run the Safety Setup first. Trading stays on Virtual.")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(form); card.addView(box)
        val repo = DemoCredentialStore.repository(this)
        val svc = DemoExecutionProvider.get(this)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val engine = DemoStrategyEngineProvider.get(this)

        val dirSpinner = Spinner(this).apply { adapter = createDarkSpinnerAdapter(listOf("LONG", "SHORT")) }
        val priceField = EditText(this).apply { hint = "Limit price"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        val marginField = EditText(this).apply { hint = "Margin (USDT)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        marginField.setText(LocalCoinSettingsStore.load(this, "BTCUSDT").marginAmount.toString())
        lateinit var symbolField: AutoCompleteTextView
        symbolField = demoSymbolField("BTCUSDT") { pickedSymbol(symbolField)?.let { s0 -> marginField.setText(LocalCoinSettingsStore.load(this, s0).marginAmount.toString()) } }
        form.addView(symbolField); form.addView(dirSpinner); form.addView(priceField); form.addView(marginField)

        fun statusLine(e: DemoEntry): String {
            val sl = when (e.slState) { "VERIFIED" -> "Stop Loss ACTIVE @ ${e.slTrigger} (qty ${e.protectedQty})"; "DONE" -> "Stop Loss triggered/finished"; "LOST" -> "Stop Loss LOST"; else -> "no Stop Loss" }
            return "${e.symbol} ${e.direction} ${e.quantity} @ ${e.limitPrice} · ${e.status} · filled ${e.executedQty}" + (e.avgPrice?.let { " @ $it" } ?: "") + " · $sl" + (if (e.note.isNotBlank()) " · ${e.note}" else "")
        }

        fun refresh() {
            box.removeAllViews()
            svc.haltReason()?.let { r -> box.addView(text("HALTED: $r", 12f, red)) }
            box.addView(text(if (DemoAutoTradeStore.isOn(this)) "Demo Auto-Trading: ON (only while this app is open)" else "Demo Auto-Trading: OFF (no automatic actions)", 12f, if (DemoAutoTradeStore.isOn(this)) green else muted))
            engine.cycles().take(5).forEach { cy -> box.addView(text(cycleLine(cy), 11f, white)) }
            val list = svc.entries().take(8)
            if (list.isEmpty()) addLabel(box, "Entries", "None yet", muted)
            list.forEach { e ->
                box.addView(text(statusLine(e), 11f, if (e.status == com.ansardeo.bnb.demo.exec.EntryStatus.UNPROTECTED) red else white))
                if (e.orderActive) addButton(box, "CANCEL ORDER", inputBg).setOnClickListener {
                    val creds = repo.load() ?: return@setOnClickListener toast("Stored keys cannot be read.")
                    lifecycleScope.launch { toast(svc.cancelEntry(creds, e.id)); refresh() }
                }
            }
            svc.events(8).forEach { (t, s) -> box.addView(text(fmt.format(Date(t)) + "  " + s, 10f, muted)) }
        }
        demoOrdersRefresh = { runOnUiThread { if (box.isAttachedToWindow) refresh() } }

        val autoBtn = addButton(card, "", inputBg)
        fun autoLabel() { autoBtn.text = if (DemoAutoTradeStore.isOn(this)) "DEMO AUTO-TRADING: ON  (tap to turn OFF)" else "DEMO AUTO-TRADING: OFF  (tap to turn ON)" }
        autoLabel()
        autoBtn.setOnClickListener {
            if (DemoAutoTradeStore.isOn(this)) { DemoAutoTradeStore.set(this, false); autoLabel(); refresh(); toast("Auto-Trading OFF. Existing Stop Losses on Binance Demo stay active."); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Turn Demo Auto-Trading ON?")
                .setMessage("Binance DEMO only. While this app is OPEN the strategy may place Averaging, Opposite-Side and Re-entry LIMIT orders and close a cycle at market when its ROI Trailing Stop triggers, using your Settings. Every entry still gets its own Stop Loss. When the app is closed nothing runs (Stop Losses stay on Binance). Real trading is not involved.")
                .setPositiveButton("TURN ON") { _, _ -> DemoAutoTradeStore.set(this, true); autoLabel(); refresh() }.setNegativeButton("CANCEL", null).show()
        }
        val placeBtn = addButton(card, "PLACE DEMO ENTRY (LIMIT + Stop Loss)", yellow)
        placeBtn.setOnClickListener {
            val creds = repo.load()
            if (creds == null) { toast(if (repo.hasCredentials()) "Stored keys cannot be read. Replace them." else "Save your Demo keys first."); return@setOnClickListener }
            val price = priceField.text.toString().toBigDecimalOrNull(); val margin = marginField.text.toString().toBigDecimalOrNull()
            if (price == null || margin == null || price.signum() <= 0 || margin.signum() <= 0) { toast("Enter a valid price and margin."); return@setOnClickListener }
            val symbol = pickedSymbol(symbolField) ?: return@setOnClickListener toast("Enter a valid USDT coin, e.g. ETHUSDT.")
            val s = LocalCoinSettingsStore.load(this, symbol)
            val dir = dirSpinner.selectedItem.toString()
            val request = DemoExecutionService.EntryRequest(symbol, dir, price, margin, s.leverage, s.stopLossRoi, s.stopLossEnabled)
            AlertDialog.Builder(this).setTitle("Place Demo order?")
                .setMessage("Binance DEMO: $dir LIMIT $symbol @ ${price.toPlainString()}, margin ${margin.toPlainString()} USDT at ${s.leverage}x. After a fill it gets its own Stop Loss at -${s.stopLossRoi}% Position ROI from the actual fill price. If any part fills, the rest of the order is cancelled.")
                .setPositiveButton("PLACE") { _, _ ->
                    placeBtn.isEnabled = false
                    lifecycleScope.launch {
                        if (DemoAutoTradeStore.isOn(this@MainActivity)) {
                            // Auto-Trading ON: the entry starts a strategy cycle and goes through Global Best Entry Price Priority.
                            val res = engine.submitEntry(creds, symbol, dir, price, marginOverride = margin)
                            placeBtn.isEnabled = true; refresh()
                            toast(when (res) { is EngineSubmit.Placed -> "Order sent: ${res.entry.status}"; is EngineSubmit.NotPlaced -> res.message })
                        } else {
                            val res = svc.placeEntry(creds, request)
                            placeBtn.isEnabled = true; refresh()
                            toast(when (res) { is DemoExecutionService.PlaceResult.Placed -> "Order sent: ${res.entry.status}"; is DemoExecutionService.PlaceResult.Refused -> res.reason; is DemoExecutionService.PlaceResult.Unknown -> res.reason })
                        }
                    }
                }.setNegativeButton("CANCEL", null).show()
        }
        addButton(card, "REFRESH NOW", inputBg).setOnClickListener {
            val creds = repo.load() ?: return@setOnClickListener toast("Save your Demo keys first.")
            lifecycleScope.launch { svc.refresh(creds); refresh() }
        }
        addButton(card, "CLEAR HALT (after checking Binance Demo)", 0xFF5A1E1E.toInt()).setOnClickListener {
            if (svc.haltReason() == null) return@setOnClickListener toast("Not halted.")
            AlertDialog.Builder(this).setTitle("Clear halt?")
                .setMessage("Only clear this after you checked the positions and orders on Binance Demo. New Demo entries will be allowed again.")
                .setPositiveButton("CLEAR") { _, _ -> svc.clearHalt(); refresh() }.setNegativeButton("CANCEL", null).show()
        }
        addButton(card, "COPY REPORT", inputBg).setOnClickListener {
            val t = buildString {
                appendLine("BNB DEMO ORDERS REPORT"); svc.haltReason()?.let { appendLine("HALTED: $it") }
                appendLine("Auto-Trading: " + if (DemoAutoTradeStore.isOn(this@MainActivity)) "ON" else "OFF")
                appendLine("--- Cycles ---"); engine.cycles().forEach { appendLine(it.id + " " + cycleLine(it)) }
                appendLine("--- Entries ---"); svc.entries().forEach { appendLine(it.id + " " + statusLine(it)) }
                appendLine("--- Events ---"); svc.events(40).forEach { appendLine(fmt.format(Date(it.first)) + " " + it.second) }
            }
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("BNB orders report", t)); toast("Report copied")
        }
        refresh()
    }

    private fun cycleLine(cy: com.ansardeo.bnb.demo.exec.DemoCycle): String =
        "${cy.symbol} ${cy.direction} ${cy.leverage}x · ${cy.kind} · ${cy.status}" + (if (cy.avgDone) " · averaged" else "") + (if (cy.oppDone) " · opposite sent" else "") +
            (if (cy.trailActive) " · trailing stop ${cy.trailStop?.take(10)}" else "") + (cy.bookedRoi?.let { " · booked ROI $it%" } ?: "") + (if (cy.note.isNotBlank()) " · ${cy.note}" else "")

    // ---------------- helpers ----------------
    private fun createDarkSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView as? TextView) ?: TextView(context)
                v.text = getItem(position); v.setTextColor(white); v.setPadding(dp(8), dp(10), dp(8), dp(10)); return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView as? TextView) ?: TextView(context)
                v.text = getItem(position); v.setTextColor(white); v.setBackgroundColor(inputBg)
                v.setPadding(dp(12), dp(12), dp(12), dp(12)); return v
            }
        }

    private fun addButton(parent: LinearLayout, label: String, color: Int): Button {
        val b = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false
            setTextColor(if (color == yellow) 0xFF000000.toInt() else white)
            background = rounded(color, 12)
        }
        parent.addView(b, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) }); return b
    }

    private fun addActionButton(parent: LinearLayout, label: String, color: Int = inputBg, action: () -> Unit) {
        addButton(parent, label, color).setOnClickListener { action() }
    }

    private fun addActionButton(parent: LinearLayout, label: String, action: () -> Unit) = addActionButton(parent, label, inputBg, action)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { stopRefresh(); super.onDestroy() }
}