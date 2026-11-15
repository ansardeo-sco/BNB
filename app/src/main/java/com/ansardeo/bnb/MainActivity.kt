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
import com.ansardeo.bnb.paper.PaperEngine
import com.ansardeo.bnb.paper.PaperEngineProvider
import com.ansardeo.bnb.paper.TradingControlStore
import com.ansardeo.bnb.paper.db.PaperEventEntity
import com.ansardeo.bnb.trading.RoiMath
import com.ansardeo.bnb.trading.TrailSide
import com.ansardeo.bnb.trading.TrailingStopMath
import com.ansardeo.bnb.paper.db.PaperCycleEntity
import com.ansardeo.bnb.paper.db.PaperCycleWithEntries
import com.ansardeo.bnb.paper.db.PaperDatabase
import com.ansardeo.bnb.paper.db.PaperHistoryCloseEntity
import com.ansardeo.bnb.paper.db.PaperOrderEntity
import com.ansardeo.bnb.paper.db.PaperReentryPlanEntity
import com.ansardeo.bnb.ui.CoinDetailActivity
import com.ansardeo.bnb.util.PriceFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val marketRepo = MarketRepository()
    private val paperDb by lazy { PaperDatabase.get(this) }
    private fun stage() = StageStore.current(this)
    private fun engine(): PaperEngine = PaperEngineProvider.get(this, stage())
    private fun stageId() = stage().id

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
        lifecycleScope.launch {
            runCatching { PaperEngineProvider.get(this@MainActivity).getWallet() }
        }
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
        val paused = TradingControlStore.isPaused(this)
        topStatus.text = if (paused) "STAGE 1 — VIRTUAL (PAUSED)" else "STAGE 1 — VIRTUAL"
        topStatus.setTextColor(if (paused) yellow else green)
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
        val balCard = addCard(root, "WALLET BALANCE - ${stage().shortLabel}")
        val balText = priceTv("-- USDT", 26f, green).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        balCard.addView(balText, LinearLayout.LayoutParams(-1, -2))
        addSpace(balCard, 4)
        val balSub = text("Loading ${stage().shortLabel} wallet...", 12f, muted); balCard.addView(balSub)

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
            lifecycleScope.launch {
                try {
                    val w = engine().getWallet()
                    val active = paperDb.dao().getActiveCyclesWithEntries(stageId())
                    val openOrders = paperDb.dao().getActiveOrders(stageId()).size
                    val prices = lastOverview?.symbols ?: emptyMap()
                    var unreal = 0.0
                    for (cwe in active) {
                        val px = prices[cwe.cycle.symbol]?.markPrice ?: prices[cwe.cycle.symbol]?.lastPrice ?: continue
                        val avg = cwe.cycle.averageEntryPrice ?: continue
                        val qty = cwe.cycle.totalRemainingQuantity.takeIf { it > 0 } ?: cwe.cycle.totalQuantity
                        unreal += if (cwe.cycle.direction == "LONG") (px - avg) * qty else (avg - px) * qty
                    }
                    val equity = w.availableBalance + w.reservedMargin + unreal
                    balText.text = PriceFormatter.formatUsdt(equity)
                    balSub.text = "Available ${PriceFormatter.formatUsdt(w.availableBalance)} | Positions: ${active.size} | Open Orders: $openOrders | Unrealized ${PriceFormatter.formatPnl(unreal)} | Realized ${PriceFormatter.formatPnl(w.realizedPnl)} (Virtual)"
                } catch (_: Exception) { balSub.text = "Unable to load virtual wallet." }
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
            target.addView(marketRow(t.symbol, t.lastPrice ?: t.markPrice, t.quoteVolume, t.priceChangePercent))
            target.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun renderFavorites(target: LinearLayout, ov: MarketOverview?) {
        target.removeAllViews()
        val favs = FavoritesStore.load(this)
        if (favs.isEmpty()) { target.addView(text("No favorites yet. Open a coin and tap Favorite.", 12f, muted)); return }
        for (sym in favs) {
            val t = ov?.symbols?.get(sym)
            target.addView(marketRow(sym, t?.lastPrice ?: t?.markPrice, t?.quoteVolume, t?.priceChangePercent))
            target.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    // ---------------- TRADE (Manual tab renamed) ----------------
    private fun manualPage() /* Trade tab */ {
        stopRefresh()
        content.removeAllViews()
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stageBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(card, 12); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        stageBar.addView(text("${stage().title}", 13f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
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

        var currentLivePrice: Double? = null
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
            val profile = LeverageConfig.requireProfile(lev)
            val intended = entryPriceInput.text.toString().toDoubleOrNull()
            val qty = if (intended != null && intended > 0 && margin > 0) (margin * lev) / intended else null
            val eff = LocalCoinSettingsStore.load(this, sym)
            val slLong = if (intended != null && intended > 0) intended * (1 - profile.slPercent/100.0) else null
            val slShort = if (intended != null && intended > 0) intended * (1 + profile.slPercent/100.0) else null
            val actLong = if (intended != null && intended > 0) runCatching { TrailingStopMath.activationPrice(TrailSide.LONG, TrailingStopMath.bd(intended), lev, TrailingStopMath.bd(eff.trailingActivationRoi)).toDouble() }.getOrNull() else null
            val actShort = if (intended != null && intended > 0) runCatching { TrailingStopMath.activationPrice(TrailSide.SHORT, TrailingStopMath.bd(intended), lev, TrailingStopMath.bd(eff.trailingActivationRoi)).toDouble() }.getOrNull() else null
            summaryBox.removeAllViews()
            addSummaryRow("Stage", stage().title, green)
            addSummaryRow("Symbol", sym)
            addSummaryRow("Live Price", "${PriceFormatter.formatPrice(currentLivePrice)} USDT")
            addSummaryRow("Intended LIMIT Entry", "${PriceFormatter.formatPrice(intended)} USDT")
            addSummaryRow("Margin", "${PriceFormatter.formatUsdt(margin)} / ${lev}x")
            addSummaryRow("Entry SL (fixed %)", "${profile.slPercent}%")
            addSummaryRow("Trailing ROI (activation / callback)", if (eff.trailingEnabled) "${eff.trailingActivationRoi} / ${eff.trailingCallbackRoi}" else "Disabled")
            addSummaryRow("Averaging trigger ROI", if (eff.averagingEnabled) "${fmtNum(eff.averagingTriggerRoi)}% (margin ${fmtNum(eff.averagingMargin)})" else "Disabled")
            addSummaryRow("Opposite-side trigger ROI", if (eff.oppositeEnabled) "${fmtNum(eff.oppositeTriggerRoi)}% (margin ${fmtNum(eff.oppositeMargin)})" else "Disabled")
            addSummaryRow("Re-entry ROI (Activation/2)", if (eff.reentryEnabled) "${fmtNum(eff.reentryRoi)}%" else "Disabled")
            addSummaryRow("Estimated Qty", if (qty != null) PriceFormatter.formatQty(qty) else "--")
            addSummaryRow("Long: Trailing activation / SL", "${PriceFormatter.formatPrice(actLong)} / ${PriceFormatter.formatPrice(slLong)}", green)
            addSummaryRow("Short: Trailing activation / SL", "${PriceFormatter.formatPrice(actShort)} / ${PriceFormatter.formatPrice(slShort)}", red)
            val note = text("All entries are LIMIT orders. Stage 1 is simulated only. No MARKET orders. No fixed Take Profit: profit exit is the ROI trailing stop.", 11f, muted)
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
                    val px = marketRepo.getMarkPrice(sym)
                    if (myGen != refreshGen || currentPage != "Trade" || selectedSym() != sym) return@launch
                    currentLivePrice = px
                    livePriceTv.text = "Live price: ${PriceFormatter.formatPrice(currentLivePrice)} USDT"
                    liveStatus.text = if (currentLivePrice != null) "Live public mark/price (auto-refresh)" else "Price unavailable — retrying"
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
            val s = stage()
            val warn = "${s.shortLabel} is simulated. No real Binance order will be submitted."
            AlertDialog.Builder(this)
                .setTitle("Confirm $dir LIMIT Entry")
                .setMessage("Stage: ${s.title}\n$warn\n\n$sym $dir\nIntended LIMIT price: ${PriceFormatter.formatPrice(intended)}\nLive price: ${PriceFormatter.formatPrice(currentLivePrice)}\nMargin ${PriceFormatter.formatUsdt(margin)} | ${lev}x\n\nPlace this $dir LIMIT entry?")
                .setPositiveButton("CONFIRM") { _, _ ->
                    setButtonsEnabled(false); result.setTextColor(muted); result.text = "Placing simulated $dir LIMIT entry..."
                    lifecycleScope.launch {
                        val res = engine().openInitialLimit(this@MainActivity, sym, dir, margin, lev, intended)
                        setButtonsEnabled(true)
                        if (res.ok) {
                            result.setTextColor(green); result.text = "[OK] ${res.message} (Cycle #${res.cycleId})"; toast("$dir LIMIT entry placed")
                            runCatching { currentLivePrice?.let { engine().processAll(this@MainActivity, mapOf(sym to it)) } }
                        } else {
                            result.setTextColor(red); result.text = "[X] ${res.message}"
                        }
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
    private fun positionsPage() /* Future tab */ {
        stopRefresh(); updateTopStatus()
        val root = pageContainer()
        val tabBar = row().apply { background = rounded(card, 12); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(tabBar); addSpace(root, 8)
        val subBar = row().apply { background = rounded(card, 10); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(subBar); addSpace(root, 8)
        val body = column(); root.addView(body)

        var primaryTab = 0 // 0 Position, 1 Open Order, 2 History
        var positionSub = 0 // 0 Open, 1 Closed
        var orderSub = 0 // 0 Basic, 1 Conditional
        var historySub = 0 // 0 Open (events of open cycles), 1 Closed (closed entries)
        lateinit var primaryViews: List<TextView>
        lateinit var subViews: List<TextView>

        fun stylePrimary(posCount: Int, openCount: Int, histCount: Int) {
            val labels = listOf("Position ($posCount)", "Open Order ($openCount)", "History ($histCount)")
            primaryViews.forEachIndexed { i, tv ->
                tv.text = labels[i]; val sel = i == primaryTab
                tv.setTextColor(if (sel) yellow else muted)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (sel) rounded(inputBg, 10) else null
            }
        }
        fun styleSub(a: String, b: String) {
            val labels = listOf(a, b)
            subViews.forEachIndexed { i, tv ->
                val sel = when (primaryTab) { 0 -> i == positionSub; 1 -> i == orderSub; else -> i == historySub }
                tv.text = labels[i]
                tv.setTextColor(if (sel) yellow else muted)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (sel) rounded(inputBg, 8) else null
            }
        }
        fun render() {
            lifecycleScope.launch {
                try {
                    val dao = paperDb.dao(); val sid = stageId()
                    val ov = (lastOverview ?: runCatching { marketRepo.getOverview().also { lastOverview = it } }.getOrNull())
                    val prices = ov?.symbols ?: emptyMap()
                    val priceState = when {
                        ov == null -> "Loading"
                        !ov.dataAvailable -> "Error"
                        ov.stale -> "Stale"
                        else -> "Live"
                    }
                    val active = dao.getActiveCyclesWithEntries(sid).filter { cwe ->
                        cwe.cycle.status in listOf("PENDING_ENTRY","ACTIVE","CLOSING") &&
                            (cwe.cycle.totalRemainingQuantity > 1e-12 || cwe.entries.any { e -> e.status == "PENDING" || e.remainingQuantity > 1e-12 })
                    }
                    // Position -> Open: only genuinely executed and currently open positions
                    val openPositions = active.filter { cwe ->
                        cwe.cycle.status != "PENDING_ENTRY" &&
                            cwe.entries.any { e -> e.status == "FILLED" && e.remainingQuantity > 1e-12 }
                    }
                    val allOrders = dao.getActiveOrders(sid).filter { it.status == "ACTIVE" }
                    val cyclesAll = dao.getRecentCycles(sid, 300)
                    val cycleById = cyclesAll.associateBy { it.id }
                    val openEntryOrders = allOrders.filter { it.orderType == "ENTRY_LIMIT" }
                    val protectiveOrders = allOrders.filter { it.orderType == "CYCLE_TRAILING" || it.orderType == "ENTRY_SL" }
                    val reentries = dao.getPendingReentryPlans(sid).filter { it.status !in listOf("EXECUTED","CANCELLED","FAILED") }
                    val history = dao.getHistoryCloses(sid, 500)
                    // Conditional opposite triggers (awaiting averaging execution) - pending trigger, shown in Conditional
                    val conditionalOpposites = emptyList<PaperCycleEntity>() // opposite-side entries are ordinary pending entries (Basic) since they are placed right after the source fill
                    val basicCount = openEntryOrders.size + reentries.size
                    val conditionalCount = protectiveOrders.groupBy { it.cycleId }.size + conditionalOpposites.size
                    val activeIds = active.map { it.cycle.id }.toSet()
                    val openEvents = dao.getEvents(sid, 300).filter { e -> e.cycleId != null && e.cycleId in activeIds }
                    stylePrimary(openPositions.size, basicCount, history.size)
                    subBar.visibility = View.VISIBLE
                    if (primaryTab == 0) styleSub("Open (${openPositions.size})", "Closed (${history.size})")
                    else if (primaryTab == 1) styleSub("Basic ($basicCount)", "Conditional ($conditionalCount)")
                    else styleSub("Open (${openEvents.size})", "Closed (${history.size})")
                    body.removeAllViews()
                    when (primaryTab) {
                        0 -> if (positionSub == 0) renderPositionsOpen(body, openPositions, prices, priceState) else renderPositionsClosed(body, history)
                        1 -> if (orderSub == 0) renderOrdersBasic(body, openEntryOrders, reentries, prices, priceState) else renderOrdersConditional(body, protectiveOrders, conditionalOpposites, cycleById, prices, priceState)
                        2 -> if (historySub == 0) renderHistoryOpen(body, openEvents) else renderHistoryPrimary(body, history)
                    }
                } catch (e: Exception) {
                    body.removeAllViews(); body.addView(text("Unable to load", 12f, red))
                }
            }
        }
        primaryViews = listOf("Position","Open Order","History").mapIndexed { i, _ ->
            TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); textSize = 12f; setOnClickListener { if (primaryTab != i) { primaryTab = i; render() } } }
                .also { tabBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        subViews = listOf("A","B").mapIndexed { i, _ ->
            TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); textSize = 11f; setOnClickListener {
                if (primaryTab == 0) { if (positionSub != i) { positionSub = i; render() } } else if (primaryTab == 1) { if (orderSub != i) { orderSub = i; render() } } else { if (historySub != i) { historySub = i; render() } }
            } }.also { subBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        stylePrimary(0,0,0); styleSub("Open","Closed"); render()
    }

    /** One-line description of a cycle's trailing-stop state for the Positions / Open Orders screens. */
    private fun trailingText(c: PaperCycleEntity): String = when {
        !c.trailingEnabled -> "Disabled"
        c.trailingActive -> "ACTIVE · stop ${PriceFormatter.formatPrice(c.trailingStopPrice)}"
        c.activationPrice != null -> "Activates @ ${PriceFormatter.formatPrice(c.activationPrice)}"
        else -> "--"
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

    private fun currentPriceText(prices: Map<String, Ticker>, symbol: String, state: String): String {
        val px = prices[symbol]?.markPrice ?: prices[symbol]?.lastPrice
        return when {
            px != null -> PriceFormatter.formatPrice(px)
            state == "Loading" -> "Loading..."
            state == "Stale" -> "Stale"
            state == "Error" -> "Unavailable"
            else -> "--"
        }
    }

    private fun renderPositionsOpen(body: LinearLayout, active: List<PaperCycleWithEntries>, prices: Map<String, Ticker>, priceState: String) {
        if (active.isEmpty()) { body.addView(text("No open positions.", 13f, muted)); return }
        for (cwe in active) {
            val c = cwe.cycle
            val openEntries = cwe.entries.filter { it.status == "FILLED" && it.remainingQuantity > 1e-12 }
            if (openEntries.isEmpty()) continue
            val px = prices[c.symbol]?.markPrice ?: prices[c.symbol]?.lastPrice
            for (e in openEntries) {
                val entryPrice = e.fillPrice
                val entryQty = e.remainingQuantity
                val baseQty = kotlin.math.max(e.filledQuantity, 1e-12)
                val entryMargin = e.margin * (entryQty / baseQty)
                val unreal = if (px != null && entryPrice != null && entryQty > 1e-12) {
                    if (c.direction == "LONG") (px - entryPrice) * entryQty else (entryPrice - px) * entryQty
                } else null
                val roi = if (unreal != null && entryMargin > 1e-12) (unreal / entryMargin) * 100.0 else null
                val box = makeItemCard(body)
                val header = row().apply { isClickable = true; setOnClickListener { openDetail(c.symbol) } }
                header.addView(text("${c.symbol}  ${c.direction}", 15f, if (c.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(text("${c.leverage}x", 12f, muted, true)); box.addView(header)
                addLabel(box, "Current Price", currentPriceText(prices, c.symbol, priceState), white, true)
                addLabel(box, "Entry Price", PriceFormatter.formatPrice(entryPrice), white, true)
                addLabel(box, "Margin", PriceFormatter.formatUsdt(entryMargin), white, true)
                addLabel(box, "Cycle WAP", PriceFormatter.formatPrice(c.averageEntryPrice), white, true)
                addLabel(box, "Trailing Stop", trailingText(c), yellow, true)
                addLabel(box, "Entry SL", PriceFormatter.formatPrice(e.slPrice), white, true)
                addLabel(box, "ROI", PriceFormatter.formatPercent(roi), pnlColor(roi), true)
                addLabel(box, "Unrealized PNL", PriceFormatter.formatPnl(unreal), pnlColor(unreal), true)
            }
        }
    }
    private fun renderPositionsClosed(body: LinearLayout, history: List<PaperHistoryCloseEntity>) {
        if (history.isEmpty()) { body.addView(text("No closed positions.", 13f, muted)); return }
        val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (h in history) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${h.symbol} ${h.direction}", 15f, if (h.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(h.closingReason, 12f, when (h.closingReason) { "TRAILING" -> green; "SL","LIQUIDATION","PANIC" -> red; else -> white }, true))
            box.addView(header)
            addLabel(box, "Entry", PriceFormatter.formatPrice(h.entryFillPrice), white, true)
            addLabel(box, "Exit", PriceFormatter.formatPrice(h.exitFillPrice), white, true)
            addLabel(box, "Qty", PriceFormatter.formatQty(h.closedQuantity))
            addLabel(box, "Margin / Lev", "${PriceFormatter.formatUsdt(h.entryMargin)} / ${h.leverage}x")
            addLabel(box, "Realized PNL", "${PriceFormatter.formatPnl(h.realizedPnlUsdt)} (${PriceFormatter.formatPercent(h.realizedPnlPercent)})", pnlColor(h.realizedPnlUsdt), true)
            addLabel(box, "Closed", tf.format(java.util.Date(h.exitTimestamp)))
        }
    }

    private fun renderOrdersBasic(body: LinearLayout, entryOrders: List<PaperOrderEntity>, reentries: List<PaperReentryPlanEntity>, prices: Map<String, Ticker>, priceState: String) {
        var any = false
        for (o in entryOrders.sortedByDescending { it.createdAt }) {
            any = true
            val box = makeItemCard(body)
            val tag = when {
                o.clientOrderId.contains("-avg-") || o.clientOrderId.startsWith("avg-") -> "Averaging"
                o.clientOrderId.contains("-initial-") || o.clientOrderId.startsWith("init-") -> "Initial"
                o.clientOrderId.contains("opp") -> "Opposite"
                else -> "Entry"
            }
            val header = row()
            header.addView(text("${o.symbol} ${o.direction}", 15f, if (o.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(tag, 12f, yellow, true)); box.addView(header)
            addLabel(box, "Order Price", PriceFormatter.formatPrice(o.triggerPrice), white, true)
            addLabel(box, "Current Price", currentPriceText(prices, o.symbol, priceState), white, true)
        }
        for (r in reentries.sortedByDescending { it.createdAt }) {
            any = true
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${r.symbol} ${r.direction}", 15f, if (r.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("Re-entry", 12f, yellow, true)); box.addView(header)
            addLabel(box, "Order Price", PriceFormatter.formatPrice(r.triggerPrice), white, true)
            addLabel(box, "Current Price", currentPriceText(prices, r.symbol, priceState), white, true)
        }
        if (!any) body.addView(text("No open orders.", 13f, muted))
    }

    private fun renderOrdersConditional(body: LinearLayout, protectiveOrders: List<PaperOrderEntity>, conditionalOpposites: List<PaperCycleEntity>, cycleById: Map<Long, PaperCycleEntity>, prices: Map<String, Ticker>, priceState: String) {
        var any = false
        val byCycle = protectiveOrders.groupBy { it.cycleId }
        for ((cycleId, orders) in byCycle.toSortedMap()) {
            any = true
            val cycle = cycleById[cycleId]
            val sym = cycle?.symbol ?: orders.firstOrNull()?.symbol ?: "--"
            val tp = orders.firstOrNull { it.orderType == "CYCLE_TRAILING" }
            // show cycle-level trailing stop and a representative SL price per card context; margin is cycle initial+avg context - use cycle margins
            val sls = orders.filter { it.orderType == "ENTRY_SL" }
            val marginAmt = if (cycle != null) {
                // total margin for open executed entries approximated by initial+averaging when applicable
                cycle.initialMargin + if (cycle.averagingDone) cycle.averagingMargin else 0.0
            } else 0.0
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("$sym ${cycle?.direction ?: ""}".trim(), 15f, if (cycle?.direction == "SHORT") red else green, true), LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(header)
            addLabel(box, "Margin Amount", PriceFormatter.formatUsdt(marginAmt), white, true)
            addLabel(box, "Current Price", currentPriceText(prices, sym, priceState), white, true)
            addLabel(box, "Trailing Stop", if (cycle != null) trailingText(cycle) else PriceFormatter.formatPrice(tp?.triggerPrice), green, true)
            // If multiple independent SLs exist, show each as its own separate card per-entry context while keeping only 4 fields
            if (sls.isEmpty()) {
                addLabel(box, "SL Order Price", "--", red, true)
            } else if (sls.size == 1) {
                addLabel(box, "SL Order Price", PriceFormatter.formatPrice(sls.first().triggerPrice), red, true)
            } else {
                // first in this card, rest as separate cards (each must be separate clean card per requirement intent)
                addLabel(box, "SL Order Price", PriceFormatter.formatPrice(sls.first().triggerPrice), red, true)
                for (sl in sls.drop(1)) {
                    val c2 = makeItemCard(body)
                    val h2 = row(); h2.addView(text("$sym ${cycle?.direction ?: ""}".trim(), 15f, if (cycle?.direction == "SHORT") red else green, true), LinearLayout.LayoutParams(0,-2,1f)); c2.addView(h2)
                    val entryMargin = cycle?.initialMargin ?: marginAmt
                    addLabel(c2, "Margin Amount", PriceFormatter.formatUsdt(entryMargin), white, true)
                    addLabel(c2, "Current Price", currentPriceText(prices, sym, priceState), white, true)
                    addLabel(c2, "Trailing Stop", if (cycle != null) trailingText(cycle) else PriceFormatter.formatPrice(tp?.triggerPrice), green, true)
                    addLabel(c2, "SL Order Price", PriceFormatter.formatPrice(sl.triggerPrice), red, true)
                }
            }
        }
        // Conditional opposite-side triggers (awaiting averaging execution)
        for (c in conditionalOpposites) {
            any = true
            val box = makeItemCard(body)
            val oppositeDir = if (c.direction == "LONG") "SHORT" else "LONG"
            val header = row()
            header.addView(text("${c.symbol} $oppositeDir", 15f, if (oppositeDir == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("Conditional", 12f, yellow, true)); box.addView(header)
            addLabel(box, "Margin Amount", PriceFormatter.formatUsdt(c.averagingMargin), white, true)
            addLabel(box, "Current Price", currentPriceText(prices, c.symbol, priceState), white, true)
            // pending trigger; trailing/SL prices not yet applicable
            addLabel(box, "Trailing Stop", "--", green, true)
            addLabel(box, "SL Order Price", "--", red, true)
        }
        if (!any) body.addView(text("No conditional orders.", 13f, muted))
    }

    /** History > Open: the event trail (fills, trailing activation/updates, SL events) of cycles that are still open. */
    private fun renderHistoryOpen(body: LinearLayout, events: List<PaperEventEntity>) {
        if (events.isEmpty()) { body.addView(text("No events for open cycles.", 13f, muted)); return }
        val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (e in events) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${e.symbol ?: "--"}  #${e.cycleId ?: 0}", 14f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(e.type, 11f, yellow, true)); box.addView(header)
            box.addView(text(e.message, 12f, muted))
            box.addView(text(tf.format(java.util.Date(e.createdAt)), 11f, muted))
        }
    }

    private fun renderHistoryPrimary(body: LinearLayout, history: List<PaperHistoryCloseEntity>) {
        if (history.isEmpty()) { body.addView(text("No history.", 13f, muted)); return }
        val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (h in history) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${h.symbol} ${h.direction}", 15f, if (h.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(h.closingReason, 12f, when (h.closingReason) { "TRAILING" -> green; "SL","LIQUIDATION","PANIC" -> red; else -> white }, true))
            box.addView(header)
            addLabel(box, "Entry", PriceFormatter.formatPrice(h.entryFillPrice), white, true)
            addLabel(box, "Exit", PriceFormatter.formatPrice(h.exitFillPrice), white, true)
            addLabel(box, "Qty", PriceFormatter.formatQty(h.closedQuantity))
            addLabel(box, "Margin / Lev", "${PriceFormatter.formatUsdt(h.entryMargin)} / ${h.leverage}x")
            addLabel(box, "Realized PNL", "${PriceFormatter.formatPnl(h.realizedPnlUsdt)} (${PriceFormatter.formatPercent(h.realizedPnlPercent)})", pnlColor(h.realizedPnlUsdt), true)
            addLabel(box, "Entry", tf.format(java.util.Date(h.entryTimestamp)))
            addLabel(box, "Exit", tf.format(java.util.Date(h.exitTimestamp)))
        }
    }

    private fun pnlColor(v: Double?): Int = when { v == null -> muted; v > 0.0 -> green; v < 0.0 -> red; else -> muted }


    // ---------------- SETTINGS (central Dashboard) ----------------
    private fun fmtNum(v: Double): String = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')

    private fun settingsPage() {
        val root = pageContainer()
        root.addView(text("Settings — Dashboard", 23f, white, true))
        root.addView(text("Central configuration for Stage 1 (Virtual). New cycles take a snapshot of these values; existing cycles are never changed silently. SL, averaging and re-entry percentages come from the fixed leverage table. There is no Take Profit: the profit exit is the ROI trailing stop.", 12f, muted))

        // A. Trading mode & bot controls
        val modeCard = addCard(root, "A. TRADING MODE & BOT CONTROLS")
        addLabel(modeCard, "Active stage", stage().title, green)
        addLabel(modeCard, "Stage 2 / Stage 3", "Not available (separate approval required)", lockedColor)
        val botSwitch = Switch(this).apply {
            text = "Bot enabled (monitoring and strategy)"; isChecked = !TradingControlStore.isPaused(this@MainActivity); setTextColor(white)
            setOnCheckedChangeListener { _, on -> TradingControlStore.setPaused(this@MainActivity, !on); updateTopStatus() }
        }
        modeCard.addView(botSwitch)

        // Scope: Global defaults or one symbol
        val scopeCard = addCard(root, "SETTINGS SCOPE", "Leave blank to edit the Global defaults. Type a symbol (e.g. BTCUSDT) to see its effective values and override individual settings.")
        val symEt = EditText(this).apply { hint = "Symbol (blank = Global)"; setText(""); setTextColor(white); setHintTextColor(muted); setBackgroundColor(inputBg); setPadding(dp(10), dp(8), dp(10), dp(8)); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        scopeCard.addView(symEt, LinearLayout.LayoutParams(-1, -2))
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
            )
            val boolControls = LinkedHashMap<String, Switch>()
            val numControls = LinkedHashMap<String, EditText>()
            lateinit var levSpin: Spinner
            val lockNote = text("", 12f, yellow, true)

            fun header(card: LinearLayout, label: String, k: String) {
                val src = LocalCoinSettingsStore.sourceOf(this, key, k)
                val h = row()
                h.addView(text(label, 12f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
                h.addView(text("[${src.label}]", 11f, when (src) { SettingSource.SYMBOL_OVERRIDE -> yellow; SettingSource.GLOBAL -> green; else -> muted }, true))
                if (src == SettingSource.SYMBOL_OVERRIDE) {
                    h.addView(text("  RESET", 11f, red, true).apply { setOnClickListener {
                        lifecycleScope.launch {
                            val r = engine().resetSymbolSetting(this@MainActivity, key, k)
                            if (r.ok) renderForm() else { lockNote.text = r.message }
                            renderSaved()
                        }
                    } })
                }
                card.addView(h)
            }
            fun sw(card: LinearLayout, label: String, k: String) {
                header(card, label, k)
                val s = Switch(this).apply { text = label; isChecked = cur[k] as Boolean; setTextColor(white) }
                boolControls[k] = s; card.addView(s); addSpace(card, 4)
            }
            fun num(card: LinearLayout, label: String, k: String, signed: Boolean = false) {
                header(card, label, k)
                val e = EditText(this).apply {
                    setText(fmtNum((cur[k] as Number).toDouble())); setTextColor(white); setHintTextColor(muted); setBackgroundColor(inputBg); setPadding(dp(10), dp(8), dp(10), dp(8))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or (if (signed) android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                }
                numControls[k] = e; card.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }

            val scopeLabel = if (isGlobal) "GLOBAL DEFAULTS" else "SYMBOL: $sym (effective values)"
            addCard(formBox, scopeLabel, if (isGlobal) "Every value below is a Global default." else "Values marked Inherited come from Global. Change a value and SAVE to create/update this symbol's custom settings; RESET returns one value to Inherited.")
                .also { if (!isGlobal) it.addView(lockNote) }

            val b = addCard(formBox, "B. TRAILING STOP (ROI)", "Activation and callback are ROI percentages (not price percentages). The trailing stop is the only profit exit; there is no fixed Take Profit.")
            sw(b, "Trailing stop enabled", SettingKeys.TRAILING_ENABLED)
            num(b, "Activation ROI (%)", SettingKeys.TRAILING_ACTIVATION_ROI)
            num(b, "Callback ROI (%)", SettingKeys.TRAILING_CALLBACK_ROI)

            val c = addCard(formBox, "C. DUAL-SIDE RE-ENTRY", "After a cycle closes profitably by its trailing stop, a LONG (below the close) and a SHORT (above the close) re-entry LIMIT are prepared. The first to fill starts a NEW cycle; the other is cancelled automatically.")
            sw(c, "Re-entry enabled", SettingKeys.REENTRY_ENABLED)
            val reRoi = eff.reentryRoi
            c.addView(text("Re-entry ROI (automatic) = Activation ROI / 2 = ${fmtNum(reRoi)}%  (price distance ${fmtNum(reRoi / eff.leverage)}% at ${eff.leverage}x)", 12f, yellow))
            addSpace(c, 4)

            val d = addCard(formBox, "D. AVERAGING", "One averaging LIMIT per cycle, priced where Position ROI reaches the trigger (leverage included). Independent of Opposite-Side.")
            sw(d, "Averaging enabled", SettingKeys.AVERAGING_ENABLED)
            num(d, "Averaging margin (USDT)", SettingKeys.AVERAGING_MARGIN)
            num(d, "Averaging Trigger ROI (%) — negative, -69 to 0", SettingKeys.AVERAGING_TRIGGER_ROI, signed = true)

            val e = addCard(formBox, "E. OPPOSITE-SIDE", "A separate, independent cycle in the other direction, prepared where the original position's Position ROI reaches the trigger. Only if no opposite-direction cycle is open. Independent of Averaging.")
            sw(e, "Opposite-side enabled", SettingKeys.OPPOSITE_ENABLED)
            num(e, "Opposite-side margin (USDT)", SettingKeys.OPPOSITE_MARGIN)
            num(e, "Opposite-Side Trigger ROI (%) — negative, -69 to 0", SettingKeys.OPPOSITE_TRIGGER_ROI, signed = true)

            val f = addCard(formBox, "F. DEFAULT TRADING SETTINGS", "LONG and SHORT are always permitted (not configurable).")
            num(f, "Default margin (USDT)", SettingKeys.MARGIN)
            header(f, "Default leverage (3x–20x)", SettingKeys.LEVERAGE)
            levSpin = Spinner(this).apply { adapter = createDarkSpinnerAdapter(LeverageConfig.SUPPORTED_LEVERAGES.map { "${it}x" }); setPopupBackgroundDrawable(rounded(inputBg, 10)); setBackgroundColor(inputBg); setSelection(LeverageConfig.SUPPORTED_LEVERAGES.indexOf(eff.leverage).coerceAtLeast(0)) }
            f.addView(levSpin, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            val p = eff.profile
            f.addView(text("Derived (read-only) at ${eff.leverage}x: Entry SL ${p.slPercent}% price move (= -69% ROI) | Averaging trigger ${fmtNum(eff.averagingTriggerRoi)}% ROI | Opposite trigger ${fmtNum(eff.oppositeTriggerRoi)}% ROI | Re-entry ${fmtNum(reRoi)}% ROI", 12f, yellow))

            val msg = text("", 12f, muted)
            val saveCard = column(); formBox.addView(saveCard)
            val saveBtn = addButton(saveCard, "SAVE SETTINGS", yellow)
            saveBtn.setOnClickListener {
                try {
                    val v = HashMap<String, Any>()
                    boolControls.forEach { (k, s) -> v[k] = s.isChecked }
                    numControls.forEach { (k, et) -> v[k] = et.text.toString().toDoubleOrNull() ?: throw IllegalArgumentException("Enter a valid number for every field.") }
                    listOf(SettingKeys.AVERAGING_TRIGGER_ROI, SettingKeys.OPPOSITE_TRIGGER_ROI).forEach { k -> v[k] = RoiMath.normalizeLossTrigger(v[k] as Double) }
                    v[SettingKeys.LEVERAGE] = LeverageConfig.requireSupported(LeverageConfig.SUPPORTED_LEVERAGES.getOrElse(levSpin.selectedItemPosition) { 6 })
                    // Strict validation (throws with a readable message) before anything is saved.
                    val candidate = LocalCoinSettings(
                        symbol = key,
                        marginAmount = v[SettingKeys.MARGIN] as Double, leverage = v[SettingKeys.LEVERAGE] as Int,
                        oppositeEnabled = v[SettingKeys.OPPOSITE_ENABLED] as Boolean, oppositeMargin = v[SettingKeys.OPPOSITE_MARGIN] as Double,
                        oppositeTriggerRoi = v[SettingKeys.OPPOSITE_TRIGGER_ROI] as Double,
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
                            val r = engine().updateSymbolSettings(this@MainActivity, key, changes)
                            if (r.ok) { toast("Saved"); renderForm(); renderSaved() }
                            else { msg.setTextColor(red); msg.text = r.message }
                        }
                    }
                } catch (ex: Exception) { msg.setTextColor(red); msg.text = ex.message ?: "Save failed" }
            }
            saveCard.addView(msg)
            if (!isGlobal) lifecycleScope.launch {
                val lock = engine().symbolLockReason(sym)
                if (lock != null) { lockNote.text = lock + " — saving, resetting and deleting are disabled for this symbol."; saveBtn.isEnabled = false; saveBtn.alpha = 0.4f }
            }
        }

        // ---- Saved Coin Settings (very bottom, below SAVE SETTINGS) ----
        val savedBox = column()
        fun renderSavedImpl() {
            lifecycleScope.launch {
                val symbols = LocalCoinSettingsStore.customSymbols(this@MainActivity)
                val locks = symbols.associateWith { runCatching { engine().symbolLockReason(it) }.getOrNull() }
                savedBox.removeAllViews()
                savedBox.addView(text("Saved Coin Settings", 18f, white, true))
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
                    addLabel(box, "Re-entry", onOff(eff.reentryEnabled) + " (ROI ${fmtNum(eff.reentryRoi)}% = Activation/2)" + tag(SettingKeys.REENTRY_ENABLED), if (eff.reentryEnabled) green else lockedColor)
                    addLabel(box, "Entry Stop Loss", "${eff.slPercent}% price move (-69% ROI, fixed)")
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
                                    val r = engine().deleteSymbolSettings(this@MainActivity, sym)
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
        addActionButton(scopeCard, "LOAD SETTINGS FOR THIS SCOPE") { renderForm() }
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
        root.addView(text("App status, safety controls and sanitized logs. Every action here affects ONLY the local Stage 1 simulation.", 12f, muted))

        val paused = TradingControlStore.isPaused(this)
        val info = addCard(root, "APP STATUS")
        addLabel(info, "Application", "BNB — Binance Android Bot")
        addLabel(info, "Version", BuildConfig.VERSION_NAME)
        addLabel(info, "Stage", stage().title, green)
        addLabel(info, "Stage 2 / Stage 3", "Not available", lockedColor)
        addLabel(info, "Orders", "SIMULATED ONLY (no real Binance orders)", green)
        addLabel(info, "Bot", if (paused) "PAUSED" else "Running", if (paused) yellow else green)
        addLabel(info, "Market data", "Binance public USDⓈ-M endpoints (read-only)", muted)

        val ctl = addCard(root, "SAFETY CONTROLS (LOCAL SIMULATION ONLY)")
        val result = text("", 12f, muted)
        fun livePrices(onReady: (Map<String, Double>?) -> Unit) {
            lifecycleScope.launch {
                val ov = lastOverview ?: runCatching { marketRepo.getOverview().also { lastOverview = it } }.getOrNull()
                if (ov == null || !ov.dataAvailable || ov.stale) { onReady(null); return@launch }
                onReady(ov.symbols.mapNotNull { (k, t) -> (t.markPrice ?: t.lastPrice)?.takeIf { it.isFinite() && it > 0.0 }?.let { k.uppercase() to it } }.toMap())
            }
        }
        addButton(ctl, if (paused) "RESUME BOT" else "PAUSE BOT", inputBg).setOnClickListener {
            TradingControlStore.setPaused(this, !paused); updateTopStatus(); showPage("More")
        }
        addButton(ctl, "CANCEL PENDING ORDERS", inputBg).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Cancel all pending orders?")
                .setMessage("Cancels every unfilled LIMIT entry and waiting re-entry plan (simulated). Open positions stay open.")
                .setPositiveButton("CANCEL ORDERS") { _, _ -> lifecycleScope.launch { val r = engine().cancelAllPendingOrders(); result.text = r.message; toast("Done") } }
                .setNegativeButton("BACK", null).show()
        }
        addButton(ctl, "EMERGENCY STOP", 0xFF3A3416.toInt()).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Emergency stop?")
                .setMessage("Pauses the bot and cancels all pending orders (simulated). Open positions are NOT closed.")
                .setPositiveButton("STOP") { _, _ ->
                    TradingControlStore.setPaused(this, true)
                    lifecycleScope.launch { val r = engine().cancelAllPendingOrders(); toast("Emergency stop done"); updateTopStatus(); showPage("More") }
                }.setNegativeButton("BACK", null).show()
        }
        addButton(ctl, "PANIC CLOSE ALL POSITIONS", 0xFF5A1E1E.toInt()).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Panic close?")
                .setMessage("Closes every open simulated position at the current live price and cancels pending orders. Requires live prices.")
                .setPositiveButton("CLOSE ALL") { _, _ ->
                    livePrices { prices ->
                        if (prices == null) { result.setTextColor(red); result.text = "Live prices are unavailable or stale. Try again in a moment." }
                        else lifecycleScope.launch { val r = engine().panicClose(prices); result.setTextColor(if (r.ok) green else red); result.text = r.message }
                    }
                }.setNegativeButton("BACK", null).show()
        }
        ctl.addView(result)

        val wal = addCard(root, "VIRTUAL WALLET")
        addButton(wal, "RESET ALL VIRTUAL DATA (5000 USDT)", 0xFF3A3416.toInt()).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Reset ALL virtual data?")
                .setMessage("Deletes the virtual wallet history, all cycles, orders, positions, history and events, then restores 5000 USDT. This cannot be undone.")
                .setPositiveButton("RESET") { _, _ -> lifecycleScope.launch { engine().resetAllData(); toast("Virtual data reset"); showPage("More") } }
                .setNegativeButton("CANCEL", null).show()
        }

        val logs = addCard(root, "SANITIZED LOGS (last 25 events)", "Strategy events only. Never contains keys, signatures or request data.")
        lifecycleScope.launch {
            val ev = runCatching { paperDb.dao().getEvents(stageId(), 25) }.getOrDefault(emptyList())
            if (ev.isEmpty()) logs.addView(text("No events yet.", 12f, muted))
            val tf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            ev.forEach { logs.addView(text("${tf.format(java.util.Date(it.createdAt))}  ${it.type}  ${it.message}", 11f, muted)); addSpace(logs, 4) }
        }
    }

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