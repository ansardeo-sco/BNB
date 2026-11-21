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
import com.ansardeo.bnb.demo.exec.AveragingHistory
import com.ansardeo.bnb.demo.exec.ClosedPosition
import com.ansardeo.bnb.demo.exec.CloseReasonLabel
import com.ansardeo.bnb.demo.exec.EntryRef
import com.ansardeo.bnb.demo.exec.PnlFormat
import com.ansardeo.bnb.demo.exec.HistOpenCard
import com.ansardeo.bnb.demo.exec.HistTrailing
import com.ansardeo.bnb.demo.exec.HistoryClose
import com.ansardeo.bnb.demo.exec.HistoryEvents
import com.ansardeo.bnb.demo.exec.HistoryOpen
import com.ansardeo.bnb.demo.exec.PktTime
import com.ansardeo.bnb.demo.trade.DemoSymbolDirectory
import com.ansardeo.bnb.demo.trade.Readiness
import com.ansardeo.bnb.demo.trade.ReadinessRead
import com.ansardeo.bnb.demo.trade.SetupErrors
import com.ansardeo.bnb.demo.trade.TradeReadiness
import com.ansardeo.bnb.demo.trade.DemoTradeRuntime
import com.ansardeo.bnb.demo.exec.ClosedPositions
import com.ansardeo.bnb.demo.exec.CycleStatus
import com.ansardeo.bnb.demo.exec.DemoCycle
import com.ansardeo.bnb.demo.exec.PositionKindLabel
import com.ansardeo.bnb.demo.exec.PositionOrdering
import com.ansardeo.bnb.demo.exec.SlDisplay
import com.ansardeo.bnb.demo.exec.SlLevel
import com.ansardeo.bnb.demo.exec.SortField
import com.ansardeo.bnb.demo.exec.SortSpec
import com.ansardeo.bnb.demo.exec.TrailingTrigger
import com.ansardeo.bnb.demo.exec.ViewFilter
import com.ansardeo.bnb.demo.trade.DemoOpeningTrailingProbe
import com.ansardeo.bnb.demo.trade.DemoOpeningTrailingProbeStore
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
        line.addView(labelTv, LinearLayout.LayoutParams(-2, -2))
        val v = if (isPrice) priceTv(value, 13f, valueColor) else text(value, 13f, valueColor, true).apply {
            gravity = Gravity.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        line.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
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
        updateTopStatus()
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

        // 1) ACCOUNT (top): Equity, Wallet Balance, Total Unrealized PNL and Available Balance, all from Binance Demo
        val balCard = addCard(root, "EQUITY (MARGIN BALANCE) - BINANCE DEMO")
        val balText = priceTv("-- USDT", 26f, green).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        balCard.addView(balText, LinearLayout.LayoutParams(-1, -2))
        addSpace(balCard, 4)
        val balSub = text("Loading Binance Demo account...", 12f, muted); balCard.addView(balSub)
        val balRows = column(); balCard.addView(balRows)

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

        var walletBusy = false
        fun renderWallet() {
            val creds = DemoCredentialStore.repository(this).load()
            if (creds == null) { balText.text = "-- USDT"; balRows.removeAllViews(); balSub.text = "Save your Binance Demo keys in More to see the Demo account."; return }
            if (walletBusy) return
            walletBusy = true
            lifecycleScope.launch {
                try {
                    val client = DemoTradeClient.create()
                    val r = client.balance(creds)
                    val pr = client.positionRiskAll(creds)
                    val sum = if (r.ok) com.ansardeo.bnb.demo.exec.DemoAccountView.summarize(r.body, if (pr.ok) pr.body else null) else null
                    val ents = DemoExecutionProvider.get(this@MainActivity).entries()
                    val orders = ents.count { it.orderActive }
                    balRows.removeAllViews()
                    if (sum == null) { balText.text = "-- USDT"; balSub.text = "Could not read the Binance Demo wallet (${if (r.ok) "unexpected answer" else r.describe()}). Retrying with the next refresh."; return@launch }
                    val eq = sum.equity; val upl = sum.totalUnrealizedPnl
                    balText.text = if (eq != null) PriceFormatter.formatUsdt(eq.toDouble()) else "-- USDT"
                    balText.setTextColor(if (eq != null) green else muted)
                    balSub.text = if (upl != null) "Equity = Wallet Balance + Total Unrealized PNL (all open positions, isolated included). All values come from Binance Demo."
                        else "Equity and Unrealized PNL are unavailable: positions could not be read (${if (pr.ok) "unexpected answer" else pr.describe()}). Wallet Balance below is correct. Retrying with the next refresh."
                    addLabel(balRows, "Wallet Balance", PriceFormatter.formatUsdt(sum.walletBalance.toDouble()))
                    addLabel(balRows, "Equity / Margin Balance", if (eq != null) PriceFormatter.formatUsdt(eq.toDouble()) else "unavailable", if (eq != null) white else yellow)
                    addLabel(balRows, "Total Unrealized PNL", if (upl != null) PriceFormatter.formatPnl(upl.toDouble()) else "unavailable", if (upl == null) yellow else if (upl.signum() >= 0) green else red)
                    addLabel(balRows, "Available Balance", PriceFormatter.formatUsdt(sum.availableBalance.toDouble()))
                    addLabel(balRows, "Open Positions / Orders", "${sum.openPositions?.toString() ?: "--"} / $orders")
                } finally { walletBusy = false }
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
                }
            }
            renderWallet() // account data refreshes on every cycle, whether or not the public market data loaded
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
        val tradeSvc = DemoExecutionProvider.get(this); val tradeEngine = DemoStrategyEngineProvider.get(this); val tradeRepo = DemoCredentialStore.repository(this)

        // ONE Auto-Trading switch for the whole app. The header status always reads the same store (updateTopStatus).
        val autoBtn = Button(this).apply { isAllCaps = false; textSize = 13f; setTextColor(white) }
        fun refreshAuto() {
            val on = DemoAutoTradeStore.isOn(this)
            autoBtn.text = if (on) "AUTO-TRADING: ON  —  tap to turn OFF" else "AUTO-TRADING: OFF  —  tap to turn ON"
            autoBtn.background = rounded(if (on) 0xFF0E7A52.toInt() else inputBg, 12)
            updateTopStatus()
        }
        refreshAuto()
        autoBtn.setOnClickListener {
            if (DemoAutoTradeStore.isOn(this)) { DemoAutoTradeStore.set(this, false); tradeSvc.note("Auto-Trading turned OFF"); refreshAuto(); toast("Auto-Trading OFF. Existing Stop Losses on Binance Demo stay active."); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Turn Demo Auto-Trading ON?")
                .setMessage("Binance DEMO only. While this app is OPEN the strategy may place Averaging and Re-entry LIMIT orders, adopt a position opened by the Opposite-Side trailing order, and close a cycle at market when its ROI Trailing Stop triggers. Every entry still gets its own Stop Loss, and no entry is taken for a coin that is not Ready. When the app is closed nothing runs (Stop Losses stay on Binance).")
                .setPositiveButton("TURN ON") { _, _ -> DemoAutoTradeStore.set(this, true); tradeSvc.note("Auto-Trading turned ON"); refreshAuto() }.setNegativeButton("BACK", null).show()
        }
        outer.addView(autoBtn, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
        val haltBox = column()
        outer.addView(haltBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(0)) })
        fun renderHalt() {
            haltBox.removeAllViews()
            val h = tradeSvc.haltReason() ?: return
            val b = column().apply { background = rounded(0xFF2A1215.toInt(), 12); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            wrapLine(b, "HALTED: $h", red, 12f, true, 6)
            val clear = Button(this).apply { text = "Clear halt (after checking Binance Demo)"; isAllCaps = false; textSize = 12f; setTextColor(white); background = rounded(inputBg, 12) }
            clear.setOnClickListener {
                AlertDialog.Builder(this).setTitle("Clear halt?").setMessage("Only clear this after you checked the positions and orders on Binance Demo. New Demo entries will be allowed again.")
                    .setPositiveButton("CLEAR") { _, _ -> tradeSvc.clearHalt(); tradeSvc.note("Halt cleared by the user"); renderHalt() }.setNegativeButton("BACK", null).show()
            }
            b.addView(clear, LinearLayout.LayoutParams(-1, dp(40)))
            haltBox.addView(b)
        }
        renderHalt()
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

        fun selectedSym(): String = symbolInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
        // ---- coin readiness (chip + Safety Setup button + entry gate) ----
        val symParent = symbolInput.parent as LinearLayout
        val readyBox = column().apply { setPadding(0, dp(8), 0, 0) }
        symParent.addView(readyBox, symParent.indexOfChild(symbolInput) + 1)
        val gateNote = text("", 11f, yellow).apply { visibility = View.GONE }
        (result.parent as LinearLayout).let { it.addView(gateNote, it.indexOfChild(result)) }
        var readiness: Readiness = Readiness.NotReady("Pick a coin first", false)
        var setupNote: String? = null
        var setupBusy = false
        var sending = false
        var readyGen = 0
        var readySym: String? = null
        val symRegex = Regex("[A-Z0-9]{2,20}USDT")

        fun applyGate() {
            val r = readiness
            val ok = r is Readiness.Ready && !sending
            btnOpenLong.isEnabled = ok; btnOpenShort.isEnabled = ok
            btnOpenLong.alpha = if (ok) 1f else 0.4f; btnOpenShort.alpha = if (ok) 1f else 0.4f
            val msg = when (r) { is Readiness.Ready -> if (sending) "Sending the order…" else ""; is Readiness.Checking -> "Entry is disabled while the coin is being checked."; is Readiness.NotReady -> "Entry is disabled: ${r.reason}." }
            gateNote.text = msg; gateNote.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
        }

        lateinit var evaluateReadiness: (String, Boolean) -> Unit
        fun runSafetySetup(sym: String) {
            val creds = tradeRepo.load() ?: return toast("Save your Demo keys first (More).")
            val lev = LocalCoinSettingsStore.load(this, sym).leverage
            AlertDialog.Builder(this).setTitle("Run Safety Setup for $sym?")
                .setMessage("On Binance DEMO this sets $sym to ISOLATED margin and ${lev}x leverage (your Settings) if it is not already, then reads both back. No order is placed. Nothing is changed if $sym has a position or any order.")
                .setPositiveButton("RUN") { _, _ ->
                    setupBusy = true; evaluateReadiness(sym, true)
                    lifecycleScope.launch {
                        val report = DemoSymbolSetup(DemoTradeClient.create(), sym, lev).run(creds)
                        DemoSymbolSetupStore.save(this@MainActivity, report)
                        tradeSvc.note("$sym safety setup: " + (if (report.ready) "ready (ISOLATED ${lev}x confirmed)" else "not ready — " + (SetupErrors.explain(report) ?: report.note)))
                        setupNote = SetupErrors.explain(report); setupBusy = false
                        toast(if (report.ready) "Setup confirmed for $sym" else "Setup not ready — see the reason")
                        if (selectedSym() == sym) evaluateReadiness(sym, true)
                    }
                }.setNegativeButton("CANCEL", null).show()
        }

        fun renderReady() {
            readyBox.removeAllViews()
            val sym = selectedSym()
            val r = readiness
            when (r) {
                is Readiness.Checking -> readyBox.addView(chip("Checking $sym…", inputBg, muted))
                is Readiness.Ready -> readyBox.addView(chip("Ready ✓  (${r.detail})", chipGreen))
                is Readiness.NotReady -> {
                    readyBox.addView(chip("Not ready", chipRed)); addSpace(readyBox, 4)
                    wrapLine(readyBox, r.reason, red, 12f, false, 2)
                }
            }
            setupNote?.let { wrapLine(readyBox, it, yellow, 11f, false, 2) }
            if (r is Readiness.NotReady && symRegex.matches(sym)) {
                val onDemo = DemoSymbolDirectory.isOnDemo(sym) == true
                if (r.fixable) {
                    val b = Button(this).apply { text = if (setupBusy) "Running Safety Setup…" else "Run Safety Setup for $sym"; isAllCaps = false; textSize = 12f; setTextColor(0xFF000000.toInt()); background = rounded(yellow, 12); isEnabled = !setupBusy }
                    b.setOnClickListener { runSafetySetup(sym) }
                    readyBox.addView(b, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
                } else if (onDemo) {
                    val b = Button(this).apply { text = "Check again"; isAllCaps = false; textSize = 12f; setTextColor(white); background = rounded(inputBg, 12) }
                    b.setOnClickListener { evaluateReadiness(sym, false) }
                    readyBox.addView(b, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
                }
            }
        }

        evaluateReadiness = { sym, keepNote ->
            val gen = ++readyGen
            readySym = sym
            if (!keepNote) setupNote = null
            if (sym.isBlank()) { readiness = Readiness.NotReady("Pick a coin first", false); renderReady(); applyGate() }
            else if (!symRegex.matches(sym)) { readiness = Readiness.NotReady("${sym} is not a USDⓈ-M USDT coin", false); renderReady(); applyGate() }
            else {
                readiness = Readiness.Checking; renderReady(); applyGate()
                lifecycleScope.launch {
                    val dir = DemoSymbolDirectory.load() ?: DemoSymbolDirectory.cached()
                    val onDemo = dir?.contains(sym)
                    val creds = tradeRepo.load()
                    val wanted = LocalCoinSettingsStore.load(this@MainActivity, sym).leverage
                    var hedge: Boolean? = null; var cfg: Pair<String, Int>? = null; var err: String? = null
                    if (onDemo == true && creds != null) {
                        val api = DemoTradeClient.create(); val t = api.syncTime()
                        if (!t.ok) err = t.describe()
                        else {
                            val pm = api.positionMode(creds)
                            if (pm.ok) hedge = ReadinessRead.hedge(pm.body) else err = pm.describe()
                            if (err == null) {
                                var sc = api.symbolConfig(creds, sym)
                                cfg = if (sc.ok) ReadinessRead.config(sc.body, sym) else null
                                if (cfg == null) { sc = api.symbolConfig(creds, sym); cfg = if (sc.ok) ReadinessRead.config(sc.body, sym) else null }
                                if (cfg == null && !sc.ok) err = sc.describe()
                            }
                        }
                    }
                    if (gen != readyGen || selectedSym() != sym) return@launch
                    readiness = TradeReadiness.evaluate(onDemo, creds != null, err, hedge, cfg?.first, cfg?.second, wanted)
                    renderReady(); applyGate()
                }
            }
        }

        var currentLivePrice: Double? = null // Binance LAST price (LIMIT entry suggestion / display)
        var currentMarkPrice: Double? = null // Binance MARK price (kept separate)
        var userEditedEntryPrice = false
        val prefillSymbol = pendingTradeSymbol?.uppercase()
        val prefillPrice = pendingTradePrice
        pendingTradeSymbol = null
        pendingTradePrice = null

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
            if (eff.averagingEnabled) {
                addSummaryRow("Averaging", "2 LIMIT orders · total margin = Initial Entry Margin (${fmtNum(margin)} USDT)")
                addSummaryRow("Averaging Order 1", "${fmtNum(eff.avg1AllocationPct)}% = ${fmtNum(margin * eff.avg1AllocationPct / 100.0)} USDT | ${fmtNum(eff.avg1TriggerRoi)}% ROI")
                addSummaryRow("Averaging Order 2", "${fmtNum(eff.avg2AllocationPct)}% = ${fmtNum(margin * eff.avg2AllocationPct / 100.0)} USDT | ${fmtNum(eff.avg2TriggerRoi)}% ROI")
                addSummaryRow("Averaging price basis / submission", "Initial Entry fill price / both orders together")
            } else addSummaryRow("Averaging", "Disabled")
            addSummaryRow("Opposite-Side trailing (activation / callback ROI)", if (eff.oppositeEnabled) "+${fmtNum(eff.oppositeActivationRoi)}% / ${fmtNum(eff.oppositeCallbackRoi)}% (margin ${fmtNum(eff.oppositeMargin)}, leverage ${eff.leverage}x)" else "Disabled")
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

        fun onSymbolChanged() { userEditedEntryPrice = false; loadSymbolLive(selectedSym()); if (readySym != selectedSym()) evaluateReadiness(selectedSym(), false) }

        lifecycleScope.launch {
            // Search offers ONLY coins that exist on Binance Demo with status TRADING (never the live-market list).
            val symbols = DemoSymbolDirectory.load() ?: DemoSymbolDirectory.cached()
            symbolInput.setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, symbols ?: emptyList<String>()))
            if (!prefillSymbol.isNullOrBlank()) {
                symbolInput.setText(prefillSymbol, false)
                if (prefillPrice != null && prefillPrice > 0) {
                    userEditedEntryPrice = true
                    entryPriceInput.setText(PriceFormatter.formatPrice(prefillPrice))
                }
            } else {
                val fav = FavoritesStore.load(this@MainActivity).firstOrNull()
                if (!fav.isNullOrBlank() && symbolInput.text.isNullOrBlank() && symbols?.contains(fav.uppercase(Locale.US)) == true) symbolInput.setText(fav, false)
            }
            val s = selectedSym(); if (s.isNotBlank()) loadSymbolLive(s)
            evaluateReadiness(s, false)
            if (symbols == null) { readiness = Readiness.NotReady("The Binance Demo coin list could not be loaded", false); renderReady(); applyGate() }
        }
        evaluateReadiness("", false)


        fun submitEntry(dir: String) {
            val sym = selectedSym()
            if (sym.isBlank() || !sym.endsWith("USDT")) { toast("Select a valid USDⓈ-M symbol"); return }
            if (readiness !is Readiness.Ready) { toast("This coin is not Ready yet."); return }
            val intended = entryPriceInput.text.toString().toDoubleOrNull()
            if (intended == null || intended <= 0.0) { toast("Enter a valid intended LIMIT entry price"); return }
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            if (margin <= 0.0) { toast("Enter a valid margin"); return }
            val lev = try { LeverageConfig.requireSupported(selectedLev()) } catch (e: Exception) { toast(e.message ?: "Invalid leverage"); return }
            // Binance DEMO only.
            val creds = DemoCredentialStore.repository(this).load()
            if (creds == null) { toast("Save your Demo keys first (More)."); return }
            // The Ready gate above is only a convenience: the order path itself re-reads margin type and leverage from Binance on EVERY entry and refuses (with the reason) unless ISOLATED and equal to Settings.
            val eff = LocalCoinSettingsStore.load(this, sym)
            val price = intended.toBigDecimal(); val marginBd = margin.toBigDecimal()
            val request = DemoExecutionService.EntryRequest(sym, dir, price, marginBd, eff.leverage, eff.stopLossRoi, eff.stopLossEnabled)
            val svc = DemoExecutionProvider.get(this)
            AlertDialog.Builder(this)
                .setTitle("Confirm Demo $dir LIMIT Entry")
                .setMessage("Binance DEMO only. No real-money order.\n\n$sym $dir\nLIMIT price: ${PriceFormatter.formatPrice(intended)}\nLast price: ${PriceFormatter.formatPrice(currentLivePrice)}\nMargin ${PriceFormatter.formatUsdt(margin)} | ${eff.leverage}x (from Settings)\n\nAfter a fill this entry gets its own Stop Loss on Binance Demo (shown ACTIVE only after Binance confirms it).\n\nPlace this $dir LIMIT entry?")
                .setPositiveButton("CONFIRM") { _, _ ->
                    sending = true; applyGate(); result.setTextColor(muted); result.text = "Sending $dir LIMIT entry to Binance Demo..."
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
                        sending = false; applyGate()
                        result.setTextColor(if (ok) green else red); result.text = (if (ok) "[OK] " else "[X] ") + text
                        if (ok) toast("$dir LIMIT entry sent to Demo") else evaluateReadiness(sym, false)
                    }
                }.setNegativeButton("CANCEL", null).show()
        }

        symbolInput.setOnItemClickListener { _, _, _, _ -> onSymbolChanged() }
        symbolInput.setOnEditorActionListener { _, _, _ -> onSymbolChanged(); false }
        symbolInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus && selectedSym().isNotBlank()) { loadSymbolLive(selectedSym()); if (readySym != selectedSym()) evaluateReadiness(selectedSym(), false) } }
        entryPriceInput.addTextChangedListener(SimpleWatcher { if (entryPriceInput.hasFocus()) userEditedEntryPrice = true; updateSummary() })
        marginInput.addTextChangedListener(SimpleWatcher { updateSummary() })
        leverageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateSummary() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        btnOpenLong.setOnClickListener { submitEntry("LONG") }
        btnOpenShort.setOnClickListener { submitEntry("SHORT") }

        // ---- DANGER ZONE: separate red block under Trade, every action confirmed each time ----
        val danger = column().apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF2A1215.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(2), chipRed) }
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        danger.addView(text("DANGER ZONE", 13f, red, true))
        wrapLine(danger, "Binance Demo only. Each action asks for confirmation every time.", muted, 11f, false, 4)
        val dangerResult = text("", 12f, muted)
        fun withCreds(block: (com.ansardeo.bnb.demo.DemoCredentials) -> Unit) {
            val c = tradeRepo.load()
            if (c == null) { dangerResult.setTextColor(red); dangerResult.text = "Save your Demo keys first (More)." } else block(c)
        }
        fun oneLine(t: String) = t.lines().filter { it.isNotBlank() }.joinToString(" · ").take(180)
        addButton(danger, "CANCEL PENDING ORDERS", 0xFF5A1E1E.toInt()).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Cancel all pending Demo orders?")
                .setMessage("Cancels every unfilled Demo entry order and waiting Re-entry order. Open positions and their Stop Losses stay.")
                .setPositiveButton("CANCEL ORDERS") { _, _ -> withCreds { c -> lifecycleScope.launch { val r = tradeEngine.cancelAllPending(c); tradeSvc.note("Cancel pending orders: " + oneLine(r)); dangerResult.setTextColor(muted); dangerResult.text = r; toast("Done") } } }
                .setNegativeButton("BACK", null).show()
        }
        addButton(danger, "EMERGENCY STOP", 0xFF5A1E1E.toInt()).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Emergency stop?")
                .setMessage("Turns Auto-Trading OFF, cancels all pending Demo orders and cancels any waiting Opposite-Side trailing order on Binance. Open positions are NOT closed and keep their Stop Losses.")
                .setPositiveButton("STOP") { _, _ ->
                    DemoAutoTradeStore.set(this, false); refreshAuto(); tradeSvc.note("Emergency stop: Auto-Trading turned OFF")
                    withCreds { c -> lifecycleScope.launch { val r = tradeEngine.cancelAllPending(c); val o = tradeEngine.cancelOpposingOrders(c); tradeSvc.note("Emergency stop: " + oneLine("$r $o")); dangerResult.setTextColor(muted); dangerResult.text = "Auto-Trading OFF. $r\n$o" } }
                }.setNegativeButton("BACK", null).show()
        }
        addButton(danger, "PANIC CLOSE ALL DEMO POSITIONS", 0xFF5A1E1E.toInt()).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Panic close?")
                .setMessage("Binance DEMO: turns Auto-Trading OFF, cancels pending orders, closes every open Demo position at market and removes its Stop Loss and Trailing Stop orders and cancels waiting Opposite-Side trailing orders. No re-entry follows.")
                .setPositiveButton("CLOSE ALL") { _, _ ->
                    DemoAutoTradeStore.set(this, false); refreshAuto(); tradeSvc.note("Panic close: Auto-Trading turned OFF, closing all Demo positions")
                    withCreds { c -> lifecycleScope.launch { dangerResult.setTextColor(muted); dangerResult.text = "Closing on Binance Demo..."; val r = tradeEngine.panicCloseAll(c); tradeSvc.note("Panic close: " + oneLine(r) + (if (r.contains("Still open")) " (not everything closed)" else "")); dangerResult.text = r } }
                }.setNegativeButton("BACK", null).show()
        }
        danger.addView(dangerResult)
        outer.addView(danger, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(12), dp(12), dp(28)) })

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
        val tabBar = row().apply { background = rounded(inputBg, 12); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(tabBar); addSpace(root, 8)
        val subBar = row().apply { background = rounded(inputBg, 10); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(subBar); addSpace(root, 8)
        val condBar = row().apply { background = rounded(inputBg, 10); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(condBar); addSpace(root, 8)
        val body = column(); root.addView(body)

        var primaryTab = 0 // 0 Position, 1 Open Order, 2 History
        var positionSub = 0 // 0 Open, 1 Closed
        var orderSub = 0 // 0 Basic, 1 Conditional
        var condSub = 0 // 0 Stop Loss, 1 Trailing Stop
        var historySub = 0 // 0 Events, 1 Open, 2 Close
        lateinit var primaryViews: List<TextView>
        lateinit var subViews: List<TextView>
        lateinit var condViews: List<TextView>
        lateinit var render: () -> Unit

        fun style(tv: TextView, sel: Boolean, r: Int) {
            tv.setTextColor(if (sel) yellow else muted)
            tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            tv.background = if (sel) rounded(bg, r) else null
        }
        fun renderDemo() {
            demoOrdersRefresh = { runOnUiThread { if (body.isAttachedToWindow) render() } }
            val svc = DemoExecutionProvider.get(this)
            val snap = demoSnap
            val all = svc.entries()
            val events = svc.events(200)
            val closedPositions = ClosedPositions.build(DemoStrategyEngineProvider.get(this).cycles(), all)
            val posCount = snap?.positions?.size
            val basicCount = snap?.basicOrders?.size
            val slRows = snap?.algos?.let { com.ansardeo.bnb.demo.exec.DemoAlgoView.stopLosses(it) }
            val trRows = snap?.algos?.let { com.ansardeo.bnb.demo.exec.DemoAlgoView.trailingStops(it) }
            val condCount = snap?.algos?.let { slRows!!.size + trRows!!.size }
            fun n(v: Int?) = v?.toString() ?: "?"
            listOf("Position (${n(posCount)})", "Open Order (${n(basicCount?.let { b -> condCount?.let { b + it } })})", "History").forEachIndexed { i, t -> primaryViews[i].text = t; style(primaryViews[i], i == primaryTab, 10) }
            val subLabels = when (primaryTab) { 0 -> listOf("Open (${n(posCount)})", "Closed (${closedPositions.size})"); 1 -> listOf("Basic (${n(basicCount)})", "Conditional (${n(condCount)})"); else -> listOf("Events (${events.size})", "Open (${n(posCount?.let { a -> basicCount?.let { a + it } })})", "Close (${closedPositions.size})") }
            subViews.forEachIndexed { i, tv ->
                tv.visibility = if (i < subLabels.size) View.VISIBLE else View.GONE
                if (i < subLabels.size) { tv.text = subLabels[i]; style(tv, when (primaryTab) { 0 -> i == positionSub; 1 -> i == orderSub; else -> i == historySub }, 8) }
            }
            condBar.visibility = if (primaryTab == 1 && orderSub == 1) View.VISIBLE else View.GONE
            listOf("Stop Loss (${n(slRows?.size)})", "Trailing Stop (${n(trRows?.size)})").forEachIndexed { i, t -> condViews[i].text = t; style(condViews[i], i == condSub, 8) }
            body.removeAllViews()
            svc.haltReason()?.let { r -> body.addView(text("HALTED: $r", 12f, red)); addSpace(body, 8) }
            val needsExchange = primaryTab == 0 && positionSub == 0 || primaryTab == 1 || primaryTab == 2 && historySub == 1
            if (needsExchange) {
                if (snap == null) { body.addView(text(if (DemoCredentialStore.repository(this).load() == null) "Save your Binance Demo keys (More) to see live positions and orders." else "Loading from Binance Demo...", 13f, muted)); return }
                snap.error?.let { body.addView(text("Binance Demo could not be fully read ($it). Parts that failed are not shown as empty.", 12f, red)); addSpace(body, 6) }
                body.addView(text("Live from Binance Demo · updated ${fmtTime(snap.atMillis)} · refreshes every 5 s", 10f, muted)); addSpace(body, 6)
            }
            when (primaryTab) {
                0 -> if (positionSub == 0) renderLivePositions(body, snap!!) else renderClosedPositions(body, closedPositions)
                1 -> if (orderSub == 0) renderLiveBasic(body, snap!!) else renderLiveConditional(body, snap!!, condSub == 1)
                2 -> when (historySub) { 0 -> renderHistoryEvents(body, events, all); 1 -> renderHistoryOpen(body, snap!!); else -> renderHistoryClose(body, closedPositions) }
            }
        }
        render = { renderDemo() }

        primaryViews = listOf("Position","Open Order","History").mapIndexed { i, _ ->
            TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); textSize = 12f; setOnClickListener { if (primaryTab != i) { primaryTab = i; render() } } }
                .also { tabBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        subViews = listOf("A","B","C").mapIndexed { i, _ ->
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
    // ---------------------------------------------------------------- Positions UI (Phase 6)
    private var openSort = SortSpec(); private var openFilter = ViewFilter()
    private var closedSort = SortSpec(); private var closedFilter = ViewFilter()
    private val closedCache = HashMap<String, ClosedPositions.Result>()
    private val closedNaAt = HashMap<String, Long>()
    private val closedBusy = HashSet<String>()

    private fun kv(parent: LinearLayout, label: String, value: String, color: Int = white, bold: Boolean = true) {
        val line = row().apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        line.addView(text(label, 12f, muted), LinearLayout.LayoutParams(-2, -2))
        line.addView(text(value, 13f, color, bold).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        parent.addView(line); addSpace(parent, 6)
    }
    /** Full-width text that wraps (never cut, no side-by-side label column that squeezes it). */
    private fun wrapLine(parent: LinearLayout, value: String, color: Int = white, size: Float = 13f, bold: Boolean = false, bottom: Int = 4) {
        val tv = text(value, size, color, bold).apply { setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null }
        parent.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) })
    }
    private fun tag(label: String, color: Int): TextView = text(label, 10f, color, true).apply { background = rounded(inputBg, 6); setPadding(dp(6), dp(2), dp(6), dp(2)) }
    private fun cell(label: String, value: String, color: Int = white, bold: Boolean = true): LinearLayout = column().apply { addView(text(label, 10f, muted)); addView(text(value, 13f, color, bold)) }
    private fun grid3(parent: LinearLayout, cells: List<LinearLayout>) {
        val r = row().apply { gravity = Gravity.TOP }
        cells.forEach { r.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        parent.addView(r); addSpace(parent, 8)
    }
    private fun sideBadge(direction: String): TextView = text(direction, 11f, white, true).apply { background = rounded(if (direction == "LONG") 0xFF0E7A52.toInt() else 0xFFB3283C.toInt(), 6); setPadding(dp(8), dp(3), dp(8), dp(3)) }
    private fun typeTag(cycle: DemoCycle?): TextView = tag(PositionKindLabel.of(cycle).uppercase(), when { cycle == null -> muted; PositionKindLabel.isOpposite(cycle) -> 0xFFF0B90B.toInt(); else -> 0xFF3B82F6.toInt() })
    private fun cardHeader(box: LinearLayout, symbol: String, direction: String, leverage: Int, marginType: String?, cycle: DemoCycle?, right: String? = null) {
        val h = row()
        h.addView(sideBadge(direction)); h.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        h.addView(text(symbol, 16f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) h.addView(text(right, 11f, muted, true))
        box.addView(h); addSpace(box, 6)
        val t = row()
        t.addView(tag("${leverage}x" + (marginType?.let { " ${it.lowercase()}" } ?: ""), muted)); t.addView(View(this), LinearLayout.LayoutParams(dp(6), 1)); t.addView(typeTag(cycle))
        box.addView(t); addSpace(box, 10)
    }
    private fun pnlRoiRow(box: LinearLayout, pnlLabel: String, pnl: Double?, roi: Double?) {
        val r = row()
        val l = column(); l.addView(text(pnlLabel, 10f, muted)); l.addView(text(pnl?.let { String.format(Locale.US, "%+.2f", it) } ?: "Not Available", 20f, pnlColor(pnl), true))
        val rr = column().apply { gravity = Gravity.END }; rr.addView(text("ROI", 10f, muted).apply { gravity = Gravity.END }); rr.addView(text(roi?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "Not Available", 20f, pnlColor(roi), true).apply { gravity = Gravity.END })
        r.addView(l, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(rr, LinearLayout.LayoutParams(0, -2, 1f)); box.addView(r); addSpace(box, 10)
    }
    private fun fmtDateTime(ts: Long?): String = if (ts == null || ts <= 0L) "Not Available" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
    private fun fmtDuration(ms: Long): String { val s = ms / 1000; return if (s < 60) "${s}s" else if (s < 3600) "${s / 60}m ${s % 60}s" else if (s < 86400) "${s / 3600}h ${(s % 3600) / 60}m" else "${s / 86400}d ${(s % 86400) / 3600}h" }
    private fun closeReasonText(r: String) = CloseReasonLabel.of(r)
    private fun fmtBd(bd: java.math.BigDecimal?, scale: Int = 2) = bd?.setScale(scale, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: "Not Available"

    /** Sort (ROI / Symbol / Time / PNL, each ascending or descending; tap the active one to flip) and, separately, Symbol and LONG/SHORT filters. */
    private fun addSortBar(body: LinearLayout, spec: SortSpec, filter: ViewFilter, symbols: List<String>, onChange: (SortSpec, ViewFilter) -> Unit) {
        val bar = column().apply { background = rounded(card, 12); setPadding(dp(8), dp(6), dp(8), dp(8)) }
        val r1 = row()
        for (f in SortField.values()) {
            val sel = spec.field == f
            val label = when (f) { SortField.ROI -> "ROI"; SortField.SYMBOL -> "Symbol"; SortField.TIME -> "Time"; SortField.PNL -> "PNL" } + (if (sel) (if (spec.ascending) " ▲" else " ▼") else "")
            r1.addView(text(label, 12f, if (sel) yellow else muted, sel).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8)); setOnClickListener { onChange(if (sel) spec.copy(ascending = !spec.ascending) else SortSpec(f, f == SortField.SYMBOL), filter) } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bar.addView(r1); addSpace(bar, 6)
        val r2 = row()
        for ((lbl, v) in listOf("All" to null, "LONG" to "LONG", "SHORT" to "SHORT")) {
            val sel = filter.side == v
            r2.addView(text(lbl, 11f, if (sel) yellow else muted, sel).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(6), dp(4), dp(6)); background = if (sel) rounded(bg, 8) else null; setOnClickListener { onChange(spec, filter.copy(side = v)) } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        r2.addView(text("Symbol: " + (filter.symbol ?: "All"), 11f, if (filter.symbol != null) yellow else muted, filter.symbol != null).apply {
            gravity = Gravity.CENTER; setPadding(dp(4), dp(6), dp(4), dp(6)); background = if (filter.symbol != null) rounded(bg, 8) else null
            setOnClickListener {
                val opts = listOf("All symbols") + symbols
                AlertDialog.Builder(this@MainActivity).setTitle("Filter by symbol").setItems(opts.toTypedArray()) { _, i -> onChange(spec, filter.copy(symbol = if (i == 0) null else symbols[i - 1])) }.show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1.6f))
        bar.addView(r2)
        body.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private class OpenRow(val p: com.ansardeo.bnb.demo.exec.PositionRow, val cy: DemoCycle?)

    private fun renderLivePositions(body: LinearLayout, snap: com.ansardeo.bnb.demo.exec.DemoExecutionService.ExchangeSnapshot) {
        val positions = snap.positions
        if (positions == null) { body.addView(text("Open positions could not be read from Binance Demo. Nothing is assumed.", 13f, red)); return }
        if (positions.isEmpty()) { body.addView(text("No open positions on Binance Demo.", 13f, muted)); return }
        val repo = DemoCredentialStore.repository(this); val svc = DemoExecutionProvider.get(this); val engine = DemoStrategyEngineProvider.get(this)
        val entries = svc.entries()
        val liveAlgoIds = snap.algos?.map { it.algoId }?.toSet()
        val rows = positions.map { OpenRow(it, engine.openCycleFor(it.symbol, it.direction)) }
        addSortBar(body, openSort, openFilter, rows.map { it.p.symbol }.distinct().sorted()) { s, f -> openSort = s; openFilter = f; demoOrdersRefresh?.invoke() }
        val shown = PositionOrdering.apply(rows, openSort, openFilter, { it.p.symbol }, { it.p.direction }, { it.p.roiPercent.toDouble() }, { it.p.unrealizedPnl.toDouble() }, { r -> r.cy?.let { c -> c.ev.firstOrNull { it.type == "ENTRY_FILLED" }?.at ?: c.createdAt } })
        if (shown.isEmpty()) { body.addView(text("No open position matches the filter.", 13f, muted)); return }
        for (item in shown) {
            val p = item.p; val cy = item.cy
            val box = makeItemCard(body)
            cardHeader(box, p.symbol, p.direction, p.leverage, p.marginType, cy)
            pnlRoiRow(box, "PNL (USDT)", p.unrealizedPnl.toDouble(), p.roiPercent.toDouble())
            grid3(box, listOf(cell("Size (USDT)", fmtBd(p.positionAmount)), cell("Margin (USDT)", fmtBd(p.margin)), cell("Quantity", p.quantity.stripTrailingZeros().toPlainString())))
            grid3(box, listOf(cell("Entry / WAP", PriceFormatter.formatPrice(p.entryPrice.toDouble())), cell("Mark Price", PriceFormatter.formatPrice(p.markPrice.toDouble())), cell("Liq. Price", p.liquidationPrice?.let { PriceFormatter.formatPrice(it.toDouble()) } ?: "Not Available", muted)))
            val mine = entries.filter { it.symbol == p.symbol && it.direction == p.direction && it.executed.signum() > 0 && it.openQuantity.signum() > 0 && it.status != EntryStatus.CLOSED && it.status != EntryStatus.FAILSAFE_CLOSED }
            addSpace(box, 2); box.addView(text("STOP LOSS", 10f, muted, true)); addSpace(box, 4)
            if (mine.isEmpty()) wrapLine(box, "none recorded by this app (opened outside it)", yellow, 12f)
            for (e in mine) {
                val l = SlDisplay.of(e, liveAlgoIds)
                wrapLine(box, l.text, when (l.level) { SlLevel.OK -> green; SlLevel.WARN -> yellow; SlLevel.BAD -> red }, 13f, l.level == SlLevel.OK, 1)
                wrapLine(box, "qty ${e.openQuantity.stripTrailingZeros().toPlainString()}" + (l.orderId?.let { " · order ID $it" } ?: ""), muted, 10f, false, 6)
            }
            val tr = engine.trailingForPosition(p.symbol, p.direction, p.entryPrice, p.leverage)
            box.addView(text("TRAILING STOP", 10f, muted, true)); addSpace(box, 4)
            if (!tr.enabled) wrapLine(box, tr.source, muted, 12f)
            else {
                val algoRow = tr.algoId?.let { id -> snap.algos?.firstOrNull { it.algoId == id } }
                val st = com.ansardeo.bnb.demo.exec.TrailingActivationStatus.of(p.side, tr.activation, p.markPrice, tr.persistedActive, algoRow?.status)
                val fired = TrailingTrigger.isTriggered(cy, algoRow?.status) // GREEN only on a verified trigger
                val pc = if (fired) green else yellow
                kv(box, "Trailing Activation Price", tr.activation?.let { PriceFormatter.formatPrice(it.toDouble()) } ?: "--", pc, fired)
                val trig = if (cy != null && cy.trMode != "EXCHANGE" && cy.trailActive) cy.trailStop else null
                kv(box, "Current Trigger Price", trig?.let { PriceFormatter.formatPrice(it.toDoubleOrNull()) } ?: if (tr.algoId != null) "— (Binance does not expose it)" else "—", if (trig != null) pc else muted, fired && trig != null)
                kv(box, "Trailing Status", when { fired -> "TRIGGERED"; st == com.ansardeo.bnb.demo.exec.TrailingActivation.ACTIVATED -> "Activated"; st == com.ansardeo.bnb.demo.exec.TrailingActivation.WAITING -> "Waiting for activation"; else -> "Activation state unknown" }, if (fired) green else if (st == com.ansardeo.bnb.demo.exec.TrailingActivation.UNKNOWN) muted else yellow, fired)
                tr.callback?.let { kv(box, "Trailing Callback", it, white, true) }
                if (tr.algoId != null) kv(box, "Binance order", "ID ${tr.algoId} · " + (algoRow?.status ?: "not in the open list"), if (algoRow != null) white else muted, false)
                wrapLine(box, tr.source, muted, 11f, false, 4)
            }
            if (mine.any { it.note.isNotBlank() }) wrapLine(box, "Note: " + mine.first { it.note.isNotBlank() }.note, red, 12f, true)
            addSpace(box, 6)
            val actions = row()
            val details = Button(this).apply { text = "Details"; isAllCaps = false; setTextColor(white); background = rounded(inputBg, 12) }
            actions.addView(details, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
            val closable = mine.filter { it.closable }
            if (closable.isNotEmpty()) {
                val b = Button(this).apply { text = "Close"; isAllCaps = false; setTextColor(white); background = rounded(inputBg, 12) }
                actions.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })
                b.setOnClickListener { confirmClosePosition(p.symbol, p.direction, closable) }
            }
            box.addView(actions)
            details.setOnClickListener { showPositionDetails(p.symbol, p.direction, cy, p) }
        }
    }

    /** Positions > Closed: one card per finished cycle that really filled something. Cancelled / rejected orders are not here (they stay in History). */
    private fun renderClosedPositions(body: LinearLayout, all: List<ClosedPosition>) {
        if (all.isEmpty()) { body.addView(text("No closed positions yet. Cancelled or rejected orders are not positions; see History > Events.", 13f, muted)); return }
        fun resultOf(cp: ClosedPosition) = closedResultOf(cp)
        addSortBar(body, closedSort, closedFilter, all.map { it.cycle.symbol }.distinct().sorted()) { s, f -> closedSort = s; closedFilter = f; demoOrdersRefresh?.invoke() }
        val shown = PositionOrdering.apply(all, closedSort, closedFilter, { it.cycle.symbol }, { it.cycle.direction }, { cp -> resultOf(cp)?.let { ClosedPositions.roi(it.realizedNet, cp.marginUsed)?.toDouble() } }, { cp -> resultOf(cp)?.realizedNet?.toDouble() }, { it.closeTime })
        if (shown.isEmpty()) { body.addView(text("No closed position matches the filter.", 13f, muted)); return }
        for (cp in shown) {
            val cy = cp.cycle; val res = resultOf(cp)
            val box = makeItemCard(body)
            cardHeader(box, cy.symbol, cy.direction, cy.leverage, null, cy, "CLOSED")
            val busy = res == null && cy.id in closedBusy
            val roi = res?.let { ClosedPositions.roi(it.realizedNet, cp.marginUsed)?.toDouble() }
            pnlRoiRow(box, "Realized PNL (USDT)", res?.realizedNet?.toDouble(), roi)
            if (res == null) box.addView(text(if (busy) "Reading the realized result from Binance Demo…" else "Realized result not available from Binance Demo right now (retries automatically).", 11f, muted))
            grid3(box, listOf(cell("Initial Entry", cp.initialPrice?.let { PriceFormatter.formatPrice(it.toDouble()) } ?: "Not Available"), cell("Final WAP", PriceFormatter.formatPrice(cp.finalWap.toDouble())), cell("Avg Exit", res?.avgExit?.let { PriceFormatter.formatPrice(it.toDouble()) } ?: "Not Available")))
            grid3(box, listOf(cell("Size (USDT)", fmtBd(cp.size)), cell("Margin (USDT)", fmtBd(cp.marginUsed)), cell("Quantity", cp.quantity.stripTrailingZeros().toPlainString())))
            kv(box, "Leverage", "${cy.leverage}x"); kv(box, "Open Time", fmtDateTime(cp.openTime)); kv(box, "Close Time", fmtDateTime(cp.closeTime)); kv(box, "Duration", fmtDuration(cp.durationMs))
            kv(box, "Closing Reason", closeReasonText(cy.closeReason), if (cy.closeReason.isBlank()) muted else white)
            kv(box, "Commission (USDT)", res?.fees?.let { fmtBd(it, 4) } ?: "Not Available", if (res == null) muted else white, false)
            kv(box, "Funding Fees", "Not Available", muted, false)
            addSpace(box, 4)
            val d = Button(this).apply { text = "Details"; isAllCaps = false; setTextColor(white); background = rounded(inputBg, 12) }
            box.addView(d, LinearLayout.LayoutParams(-1, dp(40)))
            d.setOnClickListener { showPositionDetails(cy.symbol, cy.direction, cy, null) }
        }
        loadClosedResults(all)
    }

    /** Full-screen Position Details: type, initial entry, every actual averaging fill, Opposite relationship, lifecycle and (when closed) the outcome. */
    private fun showPositionDetails(symbol: String, direction: String, cy: DemoCycle?, live: com.ansardeo.bnb.demo.exec.PositionRow?) {
        val svc = DemoExecutionProvider.get(this); val engine = DemoStrategyEngineProvider.get(this)
        val entries = if (cy == null) emptyList() else svc.entries().filter { it.cycleId == cy.id }.sortedBy { it.createdAt }
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val sv = android.widget.ScrollView(this).apply { setBackgroundColor(bg) }
        val content = column().apply { setPadding(dp(16), dp(16), dp(16), dp(28)) }
        sv.addView(content); dlg.setContentView(sv)
        fun build(times: Map<Long, Long>, timesLoaded: Boolean) {
            content.removeAllViews()
            val back = Button(this).apply { text = "Back"; isAllCaps = false; setTextColor(white); background = rounded(inputBg, 12); setOnClickListener { dlg.dismiss() } }
            content.addView(back, LinearLayout.LayoutParams(-1, dp(40))); addSpace(content, 10)
            val box = makeItemCard(content)
            val kindColor = when { cy == null -> muted; PositionKindLabel.isOpposite(cy) -> 0xFFF0B90B.toInt(); else -> 0xFF3B82F6.toInt() }
            box.addView(text(PositionKindLabel.of(cy).uppercase(), 18f, kindColor, true)); addSpace(box, 6)
            cardHeader(box, symbol, direction, cy?.leverage ?: live?.leverage ?: 0, live?.marginType, cy, if (cy?.status == CycleStatus.CLOSED) "CLOSED" else "OPEN")
            if (cy == null) { box.addView(text("This position was not opened by the app, so its origin, averaging and lifecycle are not recorded. Nothing is guessed.", 12f, muted)); return }
            val hist = AveragingHistory.of(entries, times)
            val init = entries.filter { it.avgSlot == 0 && it.executed.signum() > 0 }.minByOrNull { it.createdAt }
            val sec = makeItemCard(content); sec.addView(text("INITIAL ENTRY", 11f, yellow, true)); addSpace(sec, 6)
            if (init == null) kv(sec, "Initial entry", "not filled yet", muted, false)
            else {
                val ft = init.orderId?.let { times[it] }
                kv(sec, "Date / time", fmtDateTime(ft ?: cy.ev.firstOrNull { it.type == "ENTRY_FILLED" }?.at ?: init.updatedAt) + if (ft != null) " (Binance fill)" else " (recorded by app)", white, false)
                kv(sec, "Execution price", PriceFormatter.formatPrice(init.average?.toDouble()))
                kv(sec, "Quantity", init.executedQty)
                kv(sec, "Margin", fmtBd(init.average?.let { init.executed.multiply(it).divide(java.math.BigDecimal(maxOf(1, init.leverage)), java.math.MathContext(20)) }) + " USDT")
                kv(sec, "Leverage", "${init.leverage}x")
                init.orderId?.let { kv(sec, "Order ID", it.toString(), muted, false) }
            }
            val av = makeItemCard(content); av.addView(text("AVERAGING", 11f, yellow, true)); addSpace(av, 6)
            if (hist.fills.isEmpty()) kv(av, "Averaging", "No Averaging", muted, true)
            else {
                for (f in hist.fills) {
                    av.addView(text("Averaging ${f.number}${when (f.number) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }} (order slot ${f.slot})", 12f, white, true)); addSpace(av, 4)
                    kv(av, "Date / time", fmtDateTime(f.time) + if (f.timeFromBinance) " (Binance fill)" else " (recorded by app)", white, false)
                    kv(av, "Fill price", PriceFormatter.formatPrice(f.price.toDouble())); kv(av, "Filled quantity", f.quantity.stripTrailingZeros().toPlainString())
                    kv(av, "Margin used", fmtBd(f.margin) + " USDT"); f.orderId?.let { kv(av, "Order ID", it.toString(), muted, false) }; addSpace(av, 4)
                }
                kv(av, "Averaging executions", hist.fills.size.toString()); kv(av, "Total averaging margin", fmtBd(hist.totalMargin) + " USDT"); kv(av, "Updated WAP", PriceFormatter.formatPrice(hist.wap?.toDouble()))
            }
            if (!timesLoaded) av.addView(text("Exact Binance fill times are being read…", 10f, muted))
            if (PositionKindLabel.isOpposite(cy)) {
                val rel = makeItemCard(content); rel.addView(text("OPPOSITE POSITION — ORIGIN", 11f, yellow, true)); addSpace(rel, 6)
                val src = cy.sourceCycleId?.let { engine.cycleById(it) }
                if (src != null) { kv(rel, "Original position", "${src.symbol} ${src.direction}"); kv(rel, "Original reference", src.id, muted, false) }
                else kv(rel, "Original position", "unknown (opened before this link was recorded)", muted, false)
                kv(rel, "Opposite opened", fmtDateTime(init?.orderId?.let { times[it] } ?: cy.ev.firstOrNull { it.type == "ENTRY_FILLED" }?.at)); kv(rel, "Opposite fill price", PriceFormatter.formatPrice(init?.average?.toDouble()))
            } else if (cy.oppAlgoId != null) {
                val rel = makeItemCard(content); rel.addView(text("OPPOSITE-SIDE ORDER", 11f, yellow, true)); addSpace(rel, 6)
                kv(rel, "Binance order ID", cy.oppAlgoId.toString(), muted, false); kv(rel, "Activation price", cy.oppActivation ?: "—"); kv(rel, "Status", if (cy.oppAdopted) "Triggered / finished" else "Waiting on Binance", white, false)
            }
            val lc = makeItemCard(content); lc.addView(text("LIFECYCLE", 11f, yellow, true)); addSpace(lc, 6)
            if (cy.ev.isEmpty()) lc.addView(text("No lifecycle events recorded for this position.", 12f, muted))
            for (e in cy.ev.sortedBy { it.at }) { lc.addView(text(fmtDateTime(e.at), 10f, muted)); lc.addView(text(e.text, 12f, white)); addSpace(lc, 6) }
            if (cy.status == CycleStatus.CLOSED) {
                val cp = ClosedPositions.build(listOf(cy), entries).firstOrNull()
                val out = makeItemCard(content); out.addView(text("OUTCOME", 11f, yellow, true)); addSpace(out, 6)
                kv(out, "Closing reason", closeReasonText(cy.closeReason), if (cy.closeReason.isBlank()) muted else white)
                kv(out, "Closed at", fmtDateTime(if (cy.closedAt > 0) cy.closedAt else cy.updatedAt), white, false)
                val res = cp?.let { closedCache[cy.id] } ?: cy.resRealized?.let { ClosedPositions.Result(java.math.BigDecimal(it), java.math.BigDecimal(cy.resFees ?: "0"), cy.resExit?.let { x -> java.math.BigDecimal(x) }) }
                kv(out, "Realized PNL (net)", res?.let { fmtBd(it.realizedNet, 4) + " USDT" } ?: "Not Available", pnlColor(res?.realizedNet?.toDouble()))
            }
        }
        build(emptyMap(), entries.none { it.orderId != null })
        dlg.show()
        val creds = DemoCredentialStore.repository(this).load()
        if (creds != null && entries.any { it.orderId != null }) lifecycleScope.launch {
            val times = runCatching { kotlinx.coroutines.withTimeout(10_000L) { DemoTradeClient.create().userTrades(creds, symbol, entries.minOf { it.createdAt }).let { r -> if (r.ok) ClosedPositions.fillTimes(r.body) else null } } }.getOrNull()
            if (dlg.isShowing) build(times ?: emptyMap(), true)
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
        if (trailing) { renderTrailingCards(body, all, snap.positions ?: emptyList()); return }
        val rows = com.ansardeo.bnb.demo.exec.DemoAlgoView.stopLosses(all)
        if (rows.isEmpty()) { body.addView(text("No active Stop Loss orders on Binance Demo.", 13f, muted)); return }
        val repo = DemoCredentialStore.repository(this); val svc = DemoExecutionProvider.get(this)
        for (a in rows) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${a.symbol} ${a.direction}", 15f, if (a.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("Stop Loss", 12f, yellow, true)); box.addView(header)
            addLabel(box, "Order Type", a.orderType, white, true)
            addLabel(box, "Trigger Price", PriceFormatter.formatPrice(a.triggerPrice?.toDoubleOrNull()), red, true)
            addLabel(box, "Quantity", a.quantity, white, true)
            addLabel(box, "Algo Order ID", a.algoId.toString(), muted)
            addLabel(box, "Order Status", a.status, white, true)
            addCancelButton(box, "Cancel Stop Loss") { val c = repo.load() ?: return@addCancelButton "Save Demo keys first."; svc.cancelConditional(c, a.algoId) }
        }
    }

    /** Trailing Stop tab: two kinds of card, both built only from Binance openAlgoOrders rows. No cancel button here (the orders live on Binance). */
    private fun renderTrailingCards(body: LinearLayout, all: List<com.ansardeo.bnb.demo.exec.AlgoRow>, positions: List<com.ansardeo.bnb.demo.exec.PositionRow>) {
        val cards = com.ansardeo.bnb.demo.exec.DemoTrailingCards.build(com.ansardeo.bnb.demo.exec.DemoAlgoView.trailingStops(all), positions)
        if (cards.isEmpty()) { body.addView(text("No active Trailing Stop orders on Binance Demo.", 13f, muted)); return }
        val blue = 0xFF3B82F6.toInt(); val amber = 0xFFF0B90B.toInt()
        fun pct(v: java.math.BigDecimal?) = v?.setScale(2, java.math.RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()?.let { "$it%" } ?: "—"
        for (t in cards) {
            val close = t.kind == com.ansardeo.bnb.demo.exec.TrailingCardKind.POSITION_CLOSE
            val accent = if (close) blue else amber
            val box = makeItemCard(body)
            box.background = android.graphics.drawable.GradientDrawable().apply { setColor(card); cornerRadius = dp(14).toFloat(); setStroke(dp(2), accent) }
            val header = row()
            header.addView(text((if (close) "\u2198 " else "\uFF0B ") + t.title, 13f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("${t.symbol} ${t.direction} · ${t.side}", 13f, if (t.direction == "LONG") green else red, true)); box.addView(header)
            box.addView(text(t.description, 12f, accent)); addSpace(box, 4)
            addLabel(box, "Status", t.status, white, true)
            addLabel(box, "Activation ROI", pct(t.activationRoi), white, true)
            addLabel(box, "Callback ROI", pct(t.callbackRoi), white, true)
            addLabel(box, "Activation Price", PriceFormatter.formatPrice(t.activationPrice?.toDouble()), yellow, true)
            addLabel(box, "Current Trigger Price", if (t.triggerCalculated) "calculated" else "—", muted, true)
            addLabel(box, "Quantity", t.quantity, white, true)
            if (!close) {
                addLabel(box, "Order Margin ≈", t.orderMarginApprox?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString()?.let { "$it USDT" } ?: "—", white, true)
                addLabel(box, "Leverage", t.leverage?.let { "${it}x" } ?: "—", white, true)
            }
            addLabel(box, "Order ID", t.algoId.toString(), muted)
        }
    }

    // ---------------------------------------------------------------- History (Events | Open | Close): one card style, Pakistan time, tick / step formatting, Copy Report
    private var eventsFailOnly = false
    private var rulesLoading = false
    private var rulesFailAt = 0L

    /** The exchange rules (tick / step) are cached while the coin list loads; history screens refresh once they arrive. */
    private fun ensureRules() {
        if (DemoSymbolDirectory.cached() != null || rulesLoading || System.currentTimeMillis() - rulesFailAt < 60_000L) return
        rulesLoading = true
        lifecycleScope.launch {
            val ok = runCatching { DemoSymbolDirectory.load() }.getOrNull() != null
            rulesLoading = false; if (!ok) rulesFailAt = System.currentTimeMillis()
            demoOrdersRefresh?.invoke()
        }
    }

    private fun copyText(label: String, t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(label, t)); toast("Report copied")
    }
    private fun addCopyReport(parent: LinearLayout, report: () -> String) {
        val b = Button(this).apply { text = "Copy Report"; isAllCaps = false; textSize = 12f; setTextColor(white); background = rounded(inputBg, 12) }
        parent.addView(b, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(10) })
        b.setOnClickListener { copyText("BNB report", report()) }
    }
    private fun chip(label: String, bgColor: Int, fg: Int = white): TextView = text(label, 10f, fg, true).apply { background = rounded(bgColor, 6); setPadding(dp(7), dp(2), dp(7), dp(2)) }
    private val chipGreen = 0xFF0E7A52.toInt()
    private val chipRed = 0xFFB3283C.toInt()

    /** Coin name + side chip (LONG green, SHORT red) on the left, time on the right. */
    private fun histHeader(box: LinearLayout, symbol: String?, direction: String?, at: Long?) {
        val h = row()
        if (symbol != null) { h.addView(text(symbol, 15f, white, true)); }
        if (direction != null) { h.addView(View(this), LinearLayout.LayoutParams(dp(8), 1)); h.addView(sideBadge(direction)) }
        h.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        h.addView(text(PktTime.format(at), 11f, muted))
        box.addView(h); addSpace(box, 6)
    }

    private fun renderHistoryEvents(body: LinearLayout, events: List<Pair<Long, String>>, all: List<DemoEntry>) {
        val refs = all.associate { it.id to EntryRef(it.symbol, it.direction, it.orderId) }
        val list = events.map { (t, m) -> HistoryEvents.classify(t, m, refs) }.sortedByDescending { it.at }
        val shown = HistoryEvents.onlyFail(list, eventsFailOnly)
        val bar = row().apply { background = rounded(card, 12); setPadding(dp(8), dp(6), dp(8), dp(6)) }
        for ((lbl, fo) in listOf("All (${list.size})" to false, "Only FAIL (${list.count { !it.ok }})" to true)) {
            val sel = eventsFailOnly == fo
            bar.addView(text(lbl, 12f, if (sel) yellow else muted, sel).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8)); background = if (sel) rounded(bg, 8) else null; setOnClickListener { eventsFailOnly = fo; demoOrdersRefresh?.invoke() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        addCopyReport(body) { HistoryEvents.report(shown) }
        if (shown.isEmpty()) { body.addView(text(if (eventsFailOnly) "No failed events." else "No events yet.", 13f, muted)); return }
        for (e in shown) {
            val box = makeItemCard(body)
            val top = row()
            top.addView(text(PktTime.format(e.at), 11f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(chip(if (e.ok) "OK" else "FAIL", if (e.ok) chipGreen else chipRed))
            box.addView(top); addSpace(box, 6)
            if (e.symbol != null) {
                val h = row(); h.addView(text(e.symbol, 15f, white, true))
                if (e.side != null) { h.addView(View(this), LinearLayout.LayoutParams(dp(8), 1)); h.addView(sideBadge(e.side)) }
                box.addView(h); addSpace(box, 4)
            }
            wrapLine(box, e.name, if (e.ok) white else red, 14f, true, 2)
            wrapLine(box, e.reason, muted, 12f, false, 2)
            e.orderId?.let { wrapLine(box, "order ID $it", muted, 10f, false, 0) }
        }
    }

    private class OpenHistItem(val card: HistOpenCard, val pos: com.ansardeo.bnb.demo.exec.PositionRow?, val ord: com.ansardeo.bnb.demo.exec.BasicOrderRow?, val closable: List<DemoEntry>)

    /** Same Close flow as Positions > Open: confirmation first, then market close of this position's entries. */
    private fun confirmClosePosition(symbol: String, direction: String, closable: List<DemoEntry>) {
        val repo = DemoCredentialStore.repository(this); val svc = DemoExecutionProvider.get(this); val engine = DemoStrategyEngineProvider.get(this)
        val c = repo.load() ?: return toast("Save Demo keys first.")
        AlertDialog.Builder(this).setTitle("Close on Binance Demo?").setMessage("Closes $symbol $direction at market (${closable.size} entr${if (closable.size == 1) "y" else "ies"}) and removes the Stop Loss once fully closed.")
            .setPositiveButton("CLOSE") { _, _ -> lifecycleScope.launch { engine.noteManualClose(symbol, direction); for (e in closable) toast(svc.closeEntryAtMarket(c, e.id)); loadDemoSnapshot(); demoOrdersRefresh?.invoke() } }.setNegativeButton("CANCEL", null).show()
    }

    private fun renderHistoryOpen(body: LinearLayout, snap: DemoExecutionService.ExchangeSnapshot) {
        ensureRules()
        val positions = snap.positions; val orders = snap.basicOrders
        if (positions == null && orders == null) { body.addView(text("Open positions and orders could not be read from Binance Demo. Nothing is assumed.", 13f, red)); return }
        val svc = DemoExecutionProvider.get(this); val engine = DemoStrategyEngineProvider.get(this)
        val entries = svc.entries(); val liveAlgoIds = snap.algos?.map { it.algoId }?.toSet()
        val items = ArrayList<OpenHistItem>()
        for (p in positions.orEmpty()) {
            val cy = engine.openCycleFor(p.symbol, p.direction)
            val mine = entries.filter { it.symbol == p.symbol && it.direction == p.direction && it.executed.signum() > 0 && it.openQuantity.signum() > 0 && it.status != EntryStatus.CLOSED && it.status != EntryStatus.FAILSAFE_CLOSED }
            val tr = engine.trailingForPosition(p.symbol, p.direction, p.entryPrice, p.leverage)
            val kind = when { !tr.enabled -> "Disabled"; tr.algoId != null -> "Exchange-side (Binance order)"; cy?.trMode == "APP" -> "App-side"; else -> "Calculated (no order yet)" }
            val opened = cy?.let { c -> c.ev.firstOrNull { it.type == "ENTRY_FILLED" }?.at ?: c.createdAt }
            items += OpenHistItem(HistoryOpen.position(p, DemoTradeRuntime.rules.get(p.symbol), mine.map { SlDisplay.of(it, liveAlgoIds) }, HistTrailing(kind, tr.activation, tr.callback), opened), p, null, mine.filter { it.closable })
        }
        for (o in orders.orEmpty()) {
            val e = entries.firstOrNull { it.orderId == o.orderId }
            items += OpenHistItem(HistoryOpen.pending(o, DemoTradeRuntime.rules.get(o.symbol), e?.leverage, e?.createdAt), null, o, emptyList())
        }
        val order = HistoryOpen.sorted(items.map { it.card })
        val sorted = order.map { c -> items.first { it.card === c } }
        addCopyReport(body) { HistoryOpen.report(order) }
        if (sorted.isEmpty()) { body.addView(text("No open positions or pending orders on Binance Demo.", 13f, muted)); return }
        for (it0 in sorted) {
            val c = it0.card
            val box = makeItemCard(body)
            histHeader(box, c.symbol, c.direction, c.openedAt)
            val tags = row()
            tags.addView(tag(c.leverageText, muted)); tags.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
            tags.addView(tag(if (c.isPosition) "POSITION" else "PENDING ORDER", if (c.isPosition) 0xFF3B82F6.toInt() else 0xFFF0B90B.toInt())); tags.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
            tags.addView(tag(c.status, white))
            box.addView(tags); addSpace(box, 8)
            if (c.isPosition) {
                wrapLine(box, c.pnlText ?: "—", pnlColor(c.pnlSign.toDouble()), 17f, true, 6)
                grid3(box, listOf(cell("Qty", c.quantity), cell("Entry", c.entryPrice), cell("Mark", c.markPrice ?: "—")))
                wrapLine(box, "STOP LOSS", muted, 10f, true, 2)
                if (c.stopLoss.isEmpty()) wrapLine(box, "none recorded by this app (opened outside it)", yellow, 12f)
                for (l in c.stopLoss) { wrapLine(box, l.text, when (l.level) { SlLevel.OK -> green; SlLevel.WARN -> yellow; SlLevel.BAD -> red }, 12f, l.level == SlLevel.OK, 1); l.orderId?.let { id -> wrapLine(box, "order ID $id", muted, 10f, false, 4) } }
                c.trailing?.let { t ->
                    wrapLine(box, "TRAILING STOP", muted, 10f, true, 2)
                    wrapLine(box, t.kind, white, 12f, true, 1)
                    if (t.kind != "Disabled") wrapLine(box, "Activation ${c.trailingActivationText ?: "—"} · Callback ${t.callback ?: "—"}", muted, 12f, false, 4)
                }
            } else {
                grid3(box, listOf(cell("Qty", c.quantity), cell("Price", c.entryPrice), cell("Type", c.orderType ?: "—")))
                c.orderId?.let { wrapLine(box, "order ID $it", muted, 10f, false, 4) }
            }
            val btn = Button(this).apply { isAllCaps = false; setTextColor(white); background = rounded(inputBg, 12) }
            if (it0.pos != null) {
                if (it0.closable.isNotEmpty()) { btn.text = "Close"; btn.setOnClickListener { confirmClosePosition(it0.pos.symbol, it0.pos.direction, it0.closable) }; box.addView(btn, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) }) }
                else wrapLine(box, "Opened outside this app: close it in Binance Demo.", muted, 11f)
            } else if (it0.ord != null) {
                btn.text = "Cancel"
                btn.setOnClickListener {
                    val creds = DemoCredentialStore.repository(this).load() ?: return@setOnClickListener toast("Save Demo keys first.")
                    AlertDialog.Builder(this).setTitle("Cancel this order?").setMessage("Only this ${it0.ord.symbol} ${it0.ord.direction} order is cancelled on Binance Demo. Other orders and open positions are not changed.")
                        .setPositiveButton("CANCEL ORDER") { _, _ -> lifecycleScope.launch { toast(DemoExecutionProvider.get(this@MainActivity).cancelBasic(creds, it0.ord.symbol, it0.ord.orderId)); loadDemoSnapshot(); demoOrdersRefresh?.invoke() } }
                        .setNegativeButton("BACK", null).show()
                }
                box.addView(btn, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
            }
        }
    }

    private fun renderHistoryClose(body: LinearLayout, all: List<ClosedPosition>) {
        ensureRules()
        val cards = HistoryClose.sorted(all.map { HistoryClose.card(it, closedResultOf(it), DemoTradeRuntime.rules.get(it.cycle.symbol)) })
        val s = HistoryClose.summary(cards)
        val sum = makeItemCard(body)
        grid3(sum, listOf(cell("Total trades", s.trades.toString()), cell("Win rate", s.winRatePct?.let { it.toPlainString() + "%" } ?: "—"), cell("Total PNL", if (s.withResult > 0) PnlFormat.signed(s.totalPnl) + " USDT" else "—", if (s.withResult > 0) pnlColor(s.totalPnl.toDouble()) else muted)))
        if (s.withResult < s.trades) sum.addView(text("${s.trades - s.withResult} trade(s) have no Binance result yet and are not counted as wins or losses.", 10f, muted))
        addCopyReport(body) { HistoryClose.report(cards) }
        if (cards.isEmpty()) { body.addView(text("No closed positions yet. Cancelled or rejected orders are not positions; see Events.", 13f, muted)); return }
        for (c in cards) {
            val box = makeItemCard(body)
            histHeader(box, c.symbol, c.direction, c.closedAt)
            wrapLine(box, c.pnlText ?: "Realized result not available from Binance Demo right now", if (c.resultKnown) pnlColor(c.pnlSign.toDouble()) else muted, if (c.resultKnown) 17f else 12f, c.resultKnown, 6)
            grid3(box, listOf(cell("Qty", c.quantity), cell("Entry", c.entryPrice), cell("Exit", c.exitPrice ?: "—")))
            wrapLine(box, "Reason: ${c.reason}" + (c.fees?.let { " · Fees $it USDT" } ?: ""), white, 12f, false, 2)
            wrapLine(box, "Duration ${HistoryClose.duration(c.durationMs)}", muted, 11f, false, 1)
            wrapLine(box, "Opened ${PktTime.format(c.openedAt)} · Closed ${PktTime.format(c.closedAt)}", muted, 11f, false, 0)
        }
        loadClosedResults(all)
    }

    /** The stored result, or the one already read from Binance this session. */
    private fun closedResultOf(cp: ClosedPosition): ClosedPositions.Result? = closedCache[cp.cycle.id] ?: cp.cycle.resRealized?.let { r -> runCatching { ClosedPositions.Result(java.math.BigDecimal(r), java.math.BigDecimal(cp.cycle.resFees ?: "0"), cp.cycle.resExit?.let { java.math.BigDecimal(it) }) }.getOrNull() }

    /** Reads the missing realized results once (15 s limit per coin, retry only after 120 s); the result is stored in the position's record. Used by Positions > Closed and History > Close. */
    private fun loadClosedResults(list: List<ClosedPosition>) {
        val creds = DemoCredentialStore.repository(this).load() ?: return
        val now = System.currentTimeMillis()
        val need = list.filter { closedResultOf(it) == null && it.cycle.id !in closedBusy && (closedNaAt[it.cycle.id]?.let { t -> now - t > 120_000L } ?: true) }
        if (need.isEmpty()) return
        val engine = DemoStrategyEngineProvider.get(this)
        need.forEach { closedBusy.add(it.cycle.id) }
        lifecycleScope.launch {
            val api = DemoTradeClient.create(); val bodies = HashMap<String, String?>()
            for (cp in need) {
                val got = runCatching {
                    kotlinx.coroutines.withTimeout(15_000L) {
                        val txt = bodies.getOrPut(cp.cycle.symbol) { api.userTrades(creds, cp.cycle.symbol, cp.entries.minOf { it.createdAt }).let { r -> if (r.ok) r.body else null } }
                        txt?.let { ClosedPositions.result(cp, it) }
                    }
                }.getOrNull()
                closedBusy.remove(cp.cycle.id)
                if (got != null) { closedCache[cp.cycle.id] = got; engine.recordCycleResult(cp.cycle.id, got.realizedNet.toPlainString(), got.avgExit?.toPlainString(), got.fees.toPlainString()) }
                else closedNaAt[cp.cycle.id] = System.currentTimeMillis()
            }
            demoOrdersRefresh?.invoke()
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

        // Auto-Trading status (the one ON/OFF switch lives in the Trade tab)
        val botRow = row().apply { background = rounded(card, 14); setPadding(dp(14), dp(8), dp(14), dp(8)) }
        botRow.addView(text("Stage 2 • Binance Demo", 13f, green, true), LinearLayout.LayoutParams(0, -2, 1f))
        botRow.addView(text(if (DemoAutoTradeStore.isOn(this)) "Auto-Trading ON (switch in Trade)" else "Auto-Trading OFF (switch in Trade)", 12f, if (DemoAutoTradeStore.isOn(this)) green else yellow, true))
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
                SettingKeys.AVERAGING_ENABLED to eff.averagingEnabled, SettingKeys.AVG1_ALLOCATION to eff.avg1AllocationPct, SettingKeys.AVG1_TRIGGER_ROI to eff.avg1TriggerRoi, SettingKeys.AVG2_ALLOCATION to eff.avg2AllocationPct, SettingKeys.AVG2_TRIGGER_ROI to eff.avg2TriggerRoi,
                SettingKeys.REENTRY_ENABLED to eff.reentryEnabled,
                SettingKeys.OPPOSITE_ENABLED to eff.oppositeEnabled, SettingKeys.OPPOSITE_MARGIN to eff.oppositeMargin, SettingKeys.OPPOSITE_TRIGGER_ROI to eff.oppositeActivationRoi,
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

            tile("Averaging", false, SettingKeys.AVERAGING_ENABLED, listOf(SettingKeys.AVERAGING_ENABLED, SettingKeys.AVG1_ALLOCATION, SettingKeys.AVG1_TRIGGER_ROI, SettingKeys.AVG2_ALLOCATION, SettingKeys.AVG2_TRIGGER_ROI),
                "Two LIMIT orders. Total Averaging Margin is automatically equal to the ACTUAL Initial Entry Margin of the cycle (a separate allocation of the same size), split by the percentages below. When the cycle's Position ROI reaches Order 1's trigger, BOTH orders are sent together. Both prices are measured from the Initial Entry FILL price (never from the WAP) and never move. Each filled order gets its own Stop Loss. Triggers are negative and limited by the Stop Loss ROI; allocations must add up to 100%. Independent of Opposite-Side.",
                { big, cap ->
                    val m = numOf(SettingKeys.MARGIN) ?: 0.0
                    big.text = "Averaging — 2 LIMIT Orders"
                    cap.text = "Total = Initial Entry Margin (${f(m)} USDT)\n" +
                        "Order 1: ${f(numOf(SettingKeys.AVG1_ALLOCATION))}% (${f(m * (numOf(SettingKeys.AVG1_ALLOCATION) ?: 0.0) / 100.0)} USDT) | ${f(numOf(SettingKeys.AVG1_TRIGGER_ROI))}% ROI\n" +
                        "Order 2: ${f(numOf(SettingKeys.AVG2_ALLOCATION))}% (${f(m * (numOf(SettingKeys.AVG2_ALLOCATION) ?: 0.0) / 100.0)} USDT) | ${f(numOf(SettingKeys.AVG2_TRIGGER_ROI))}% ROI\n" +
                        "Price Basis: Initial Entry · Submission: Both Orders Together"
                }
            ) { ed ->
                ed.addView(text("Total Averaging Margin (USDT): equal to the Initial Entry Margin (automatic, not editable)", 12f, muted)); addSpace(ed, 6)
                num(ed, "Order 1 — Margin allocation (%) — default 50", SettingKeys.AVG1_ALLOCATION)
                num(ed, "Order 1 — Trigger ROI (%) — negative, default -25", SettingKeys.AVG1_TRIGGER_ROI, signed = true)
                num(ed, "Order 2 — Margin allocation (%) — default 50", SettingKeys.AVG2_ALLOCATION)
                num(ed, "Order 2 — Trigger ROI (%) — negative, default -50", SettingKeys.AVG2_TRIGGER_ROI, signed = true)
            }

            tile("Opposite-Side", true, SettingKeys.OPPOSITE_ENABLED, listOf(SettingKeys.OPPOSITE_ENABLED, SettingKeys.OPPOSITE_MARGIN, SettingKeys.OPPOSITE_TRIGGER_ROI, SettingKeys.OPPOSITE_CALLBACK_ROI),
                "Right after the Initial Entry is filled (and has its Stop Loss), a REAL Binance Trailing Stop order is placed in the OPPOSITE direction (LONG source: SELL/SHORT, SHORT source: BUY/LONG). It activates when the source position reaches the Activation ROI and opens the opposite position when price reverses by the Callback ROI. It lives on Binance (works with the app closed or Auto-Trading OFF) and stays even if the original position closes first. Binance has no trailing-LIMIT order: it opens at MARKET when triggered. Leverage is always the Initial Leverage. Own margin; when it opens, the app gives it its own Stop Loss. Placed once per cycle and not replaced after Averaging. Independent of the closing Trailing Stop settings.",
                { big, cap -> big.text = "${f(numOf(SettingKeys.OPPOSITE_MARGIN))} USDT  |  +${f(numOf(SettingKeys.OPPOSITE_TRIGGER_ROI))}% / ${f(numOf(SettingKeys.OPPOSITE_CALLBACK_ROI))}%"; cap.text = "Margin  |  Activation / Callback ROI  ·  Leverage ${levNow()}x (same as Initial)" }
            ) { ed ->
                num(ed, "Opposite Order Margin (USDT) — default 100", SettingKeys.OPPOSITE_MARGIN)
                ed.addView(text("Opposite Leverage: ${levNow()}x (always equal to the Initial Leverage; not a separate setting)", 12f, muted)); addSpace(ed, 6)
                num(ed, "Opposite Trailing Activation ROI (%) — positive, default 40", SettingKeys.OPPOSITE_TRIGGER_ROI)
                num(ed, "Opposite Trailing Callback ROI (%) — default 3 (sent to Binance as callback ROI ÷ leverage in price %)", SettingKeys.OPPOSITE_CALLBACK_ROI)
            }

            refreshAll = {
                tiles.forEach { it.refresh() }
                val slTxt = if (boolOf(SettingKeys.STOP_LOSS_ENABLED)) "-${f(numOf(SettingKeys.STOP_LOSS_ROI))}%" else "Disabled"
                derivedTv.text = "Derived at ${levNow()}x: Stop Loss $slTxt | Averaging ${f(numOf(SettingKeys.AVG1_TRIGGER_ROI))}% / ${f(numOf(SettingKeys.AVG2_TRIGGER_ROI))}% | Opposite trailing +${f(numOf(SettingKeys.OPPOSITE_TRIGGER_ROI))}% / ${f(numOf(SettingKeys.OPPOSITE_CALLBACK_ROI))}% | Re-entry ${f(numOf(SettingKeys.TRAILING_ACTIVATION_ROI)?.div(2.0))}%"
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
                    listOf(SettingKeys.AVG1_TRIGGER_ROI, SettingKeys.AVG2_TRIGGER_ROI).forEach { k -> v[k] = RoiMath.normalizeLossTrigger(v[k] as Double) }
                    v[SettingKeys.STOP_LOSS_ROI] = Math.abs(v[SettingKeys.STOP_LOSS_ROI] as Double)
                    v[SettingKeys.LEVERAGE] = LeverageConfig.requireSupported(LeverageConfig.SUPPORTED_LEVERAGES.getOrElse(levSpin.selectedItemPosition) { 6 })
                    // Strict validation (throws with a readable message) before anything is saved.
                    val candidate = LocalCoinSettings(
                        symbol = key,
                        marginAmount = v[SettingKeys.MARGIN] as Double, leverage = v[SettingKeys.LEVERAGE] as Int,
                        oppositeEnabled = v[SettingKeys.OPPOSITE_ENABLED] as Boolean, oppositeMargin = v[SettingKeys.OPPOSITE_MARGIN] as Double,
                        oppositeActivationRoi = v[SettingKeys.OPPOSITE_TRIGGER_ROI] as Double,
                        oppositeCallbackRoi = v[SettingKeys.OPPOSITE_CALLBACK_ROI] as Double,
                        stopLossEnabled = v[SettingKeys.STOP_LOSS_ENABLED] as Boolean, stopLossRoi = v[SettingKeys.STOP_LOSS_ROI] as Double,
                        averagingEnabled = v[SettingKeys.AVERAGING_ENABLED] as Boolean,
                        avg1AllocationPct = v[SettingKeys.AVG1_ALLOCATION] as Double, avg1TriggerRoi = v[SettingKeys.AVG1_TRIGGER_ROI] as Double,
                        avg2AllocationPct = v[SettingKeys.AVG2_ALLOCATION] as Double, avg2TriggerRoi = v[SettingKeys.AVG2_TRIGGER_ROI] as Double,
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
                    addLabel(box, "Averaging Order 1", "${fmtNum(eff.avg1AllocationPct)}% of Initial Margin | ${fmtNum(eff.avg1TriggerRoi)}% ROI${tag(SettingKeys.AVG1_TRIGGER_ROI)}")
                    addLabel(box, "Averaging Order 2", "${fmtNum(eff.avg2AllocationPct)}% of Initial Margin | ${fmtNum(eff.avg2TriggerRoi)}% ROI${tag(SettingKeys.AVG2_TRIGGER_ROI)}")
                    addLabel(box, "Opposite-Side", onOff(eff.oppositeEnabled) + tag(SettingKeys.OPPOSITE_ENABLED), if (eff.oppositeEnabled) green else lockedColor)
                    addLabel(box, "Opposite-Side Margin", "${fmtNum(eff.oppositeMargin)} USDT${tag(SettingKeys.OPPOSITE_MARGIN)}")
                    addLabel(box, "Opposite Trailing Activation ROI", "+${fmtNum(eff.oppositeActivationRoi)}%${tag(SettingKeys.OPPOSITE_TRIGGER_ROI)}")
                    addLabel(box, "Opposite Leverage", "${eff.leverage}x (same as Initial)")
                    addLabel(box, "Trailing Stop", if (eff.trailingEnabled) "ON  (activation ${fmtNum(eff.trailingActivationRoi)}% / callback ${fmtNum(eff.trailingCallbackRoi)}% ROI)${tag(SettingKeys.TRAILING_ACTIVATION_ROI)}" else "OFF${tag(SettingKeys.TRAILING_ENABLED)}")
                    addLabel(box, "Re-entry", onOff(eff.reentryEnabled) + " (50% same / 100% opposite of booked ROI)" + tag(SettingKeys.REENTRY_ENABLED), if (eff.reentryEnabled) green else lockedColor)
                    addLabel(box, "Stop Loss", (if (eff.stopLossEnabled) "ON, -${fmtNum(eff.stopLossRoi)}% ROI" else "OFF") + tag(SettingKeys.STOP_LOSS_ENABLED), if (eff.stopLossEnabled) green else lockedColor)
                    addLabel(box, "Opposite Trailing Callback ROI", "${fmtNum(eff.oppositeCallbackRoi)}%${tag(SettingKeys.OPPOSITE_CALLBACK_ROI)}")
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
    private var advancedOpen = false

    /** A small round "?" button: the long explanation lives in a dialog instead of on the screen. */
    private fun infoButton(title: String, message: String): TextView = text("?", 12f, yellow, true).apply {
        gravity = Gravity.CENTER; background = rounded(inputBg, 14); minWidth = dp(28); minHeight = dp(28)
        setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    }
    private fun addCardInfo(parent: LinearLayout, title: String, info: String?): LinearLayout {
        val box = cardView()
        val h = row(); h.addView(text(title, 14f, yellow, true), LinearLayout.LayoutParams(0, -2, 1f)); if (info != null) h.addView(infoButton(title, info))
        box.addView(h)
        addSpace(parent, 12); parent.addView(box); return box
    }

    private fun entryLine(e: DemoEntry): String {
        val sl = when (e.slState) { "VERIFIED" -> "Stop Loss ACTIVE @ ${e.slTrigger} (qty ${e.protectedQty})"; "DONE" -> "Stop Loss triggered/finished"; "LOST" -> "Stop Loss LOST"; else -> "no Stop Loss" }
        return (if (e.avgSlot > 0) "Averaging Order ${e.avgSlot} · " else "") + "${e.symbol} ${e.direction} ${e.quantity} @ ${e.limitPrice} · ${e.status} · filled ${e.executedQty}" + (e.avgPrice?.let { " @ $it" } ?: "") + " · $sl" + (if (e.note.isNotBlank()) " · ${e.note}" else "")
    }

    private fun morePage() {
        val root = pageContainer()
        root.addView(text("More", 23f, white, true))

        // 1. Ready checklist
        val ck = addCardInfo(root, "READY CHECKLIST", "Keys: your Binance Demo API keys are saved and can be read.\nChecks: the last \"Run all checks\" passed (connection, order compatibility, Opposite-Side trailing).\nAuto-Trading: the single ON/OFF switch is in the Trade tab; this line only shows its state.")
        val repo = DemoCredentialStore.repository(this)
        when (repo.state()) {
            is CredentialState.Saved -> addLabel(ck, "Keys", "✓ Saved", green)
            is CredentialState.Unreadable -> addLabel(ck, "Keys", "✗ Cannot be read — replace them", red)
            else -> addLabel(ck, "Keys", "✗ Not saved", red)
        }
        val ranAt = com.ansardeo.bnb.demo.trade.DemoAllChecksStore.at(this)
        if (ranAt == 0L) addLabel(ck, "Checks", "— Not run yet", muted)
        else if (com.ansardeo.bnb.demo.trade.DemoAllChecksStore.passed(this)) addLabel(ck, "Checks", "✓ Passed · " + PktTime.format(ranAt), green)
        else addLabel(ck, "Checks", "✗ Failed · " + PktTime.format(ranAt), red)
        val autoOn = DemoAutoTradeStore.isOn(this)
        addLabel(ck, "Auto-Trading", if (autoOn) "ON" else "OFF", if (autoOn) green else yellow)

        addDemoApiCard(root)
        addRunAllChecksCard(root)

        // Advanced (collapsed by default): the single probes and the support report
        val advHead = row().apply { background = rounded(card, 14); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val advArrow = text(if (advancedOpen) "▾" else "▸", 14f, yellow, true)
        advHead.addView(text("ADVANCED", 14f, yellow, true), LinearLayout.LayoutParams(0, -2, 1f)); advHead.addView(advArrow)
        advHead.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        advHead.addView(infoButton("Advanced", "The individual Binance Demo probes (each places one far-away test order and cancels it) and a plain-text support report. \"Run all checks\" above already runs them in order, so you normally do not need these."))
        addSpace(root, 12); root.addView(advHead)
        val adv = column().apply { visibility = if (advancedOpen) View.VISIBLE else View.GONE }
        root.addView(adv)
        advHead.setOnClickListener { advancedOpen = !advancedOpen; adv.visibility = if (advancedOpen) View.VISIBLE else View.GONE; advArrow.text = if (advancedOpen) "▾" else "▸" }
        addDemoProbeCard(adv)
        addDemoOpeningTrailingProbeCard(adv)
        val rc = addCardInfo(adv, "CYCLES AND ENTRIES REPORT", "A plain-text report of every strategy cycle, entry and event, for support. It never contains keys, signatures or request data.")
        addButton(rc, "COPY REPORT", inputBg).setOnClickListener {
            val svc = DemoExecutionProvider.get(this); val engine = DemoStrategyEngineProvider.get(this)
            copyText("BNB orders report", buildString {
                appendLine("BNB DEMO ORDERS REPORT"); svc.haltReason()?.let { appendLine("HALTED: $it") }
                appendLine("Auto-Trading: " + if (DemoAutoTradeStore.isOn(this@MainActivity)) "ON" else "OFF")
                appendLine("--- Cycles ---"); engine.cycles().forEach { appendLine(it.id + " " + cycleLine(it)) }
                appendLine("--- Entries ---"); svc.entries().forEach { appendLine(it.id + " " + entryLine(it)) }
                appendLine("--- Events ---"); svc.events(40).forEach { appendLine(PktTime.format(it.first) + " " + HistoryEvents.sanitize(it.second)) }
            })
        }

        // 7. Version, small, at the bottom
        root.addView(text("BNB ${BuildConfig.VERSION_NAME}", 10f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
    }

    /** One button that runs the Demo checks in order and keeps ONE combined report. The first failing required check stops it. */
    private fun addRunAllChecksCard(root: LinearLayout) {
        val card = addCardInfo(root, "RUN ALL CHECKS", "Runs in this order and stops at the first FAIL:\n1) Connection (read-only)\n2) Compatibility probe: a far-away LIMIT order and Stop Loss order on BTCUSDT, both cancelled\n3) Opposite-Side trailing probe: a far-away opening Trailing order on BTCUSDT, cancelled\nThen one info-only line about the closing Trailing order (its query can answer -2013; that is informational, the app then uses its app-side Trailing Stop).\nBinance Demo only. No position is opened and no setting is changed.")
        val box = column(); card.addView(box)
        val creds0 = DemoCredentialStore.repository(this)
        fun refresh() {
            box.removeAllViews()
            val t = com.ansardeo.bnb.demo.trade.DemoAllChecksStore.text(this)
            if (t == null) { addSpace(box, 6); box.addView(text("Not run yet.", 12f, muted)); return }
            addSpace(box, 6)
            t.lines().filter { it.isNotBlank() }.forEach { l ->
                val c = when { l.startsWith("[OK]") -> green; l.startsWith("[FAIL]") -> red; l.startsWith("[INFO]") -> yellow; l.startsWith("[NOT RUN]") -> muted; l.startsWith("Result") -> white; else -> muted }
                wrapLine(box, l, c, if (l.startsWith("    ")) 10f else 11f, l.startsWith("[FAIL]") || l.startsWith("Result"), 2)
            }
        }
        val runBtn = addButton(card, "RUN ALL CHECKS", yellow)
        runBtn.setOnClickListener {
            val creds = creds0.load()
            if (creds == null) { toast(if (creds0.hasCredentials()) "Stored keys cannot be read. Replace them." else "Save your Demo keys first."); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Run all checks?")
                .setMessage("Binance DEMO only. Checks the connection, then sends tiny far-away test orders on BTCUSDT (a LIMIT order, a Stop Loss order and an opening Trailing order) and cancels each one. No position is opened and no setting is changed. It stops at the first FAIL.")
                .setPositiveButton("RUN") { _, _ ->
                    runBtn.isEnabled = false
                    val steps = listOf(
                        com.ansardeo.bnb.demo.trade.CheckStep("Connection") {
                            val rep = BinanceDemoApi.create().testConnection(creds.apiKey, creds.secret)
                            DemoConnectionStatusStore.save(this@MainActivity, rep)
                            com.ansardeo.bnb.demo.trade.CheckResult(rep.ok, rep.summary, rep.steps.map { (if (it.ok) "✓ " else "✗ ") + it.name + " — " + it.detail })
                        },
                        com.ansardeo.bnb.demo.trade.CheckStep("Compatibility probe") {
                            val r = DemoCompatibilityProbe(DemoTradeClient.create()).run(creds)
                            DemoProbeStore.save(this@MainActivity, r)
                            val ok = r.stopLossSupported && r.cleanupOk
                            com.ansardeo.bnb.demo.trade.CheckResult(ok, if (ok) "LIMIT order and Stop Loss order accepted, verified and cleaned up" else "not passed: " + r.verdicts.joinToString("; ") { it.first + " " + it.second } + (if (!r.cleanupOk) "; cleanup NOT verified, check Binance Demo open orders" else ""), r.steps.map { (if (it.ok) "✓ " else "✗ ") + it.name + " — " + it.detail })
                        },
                        com.ansardeo.bnb.demo.trade.CheckStep("Opposite-side trailing probe") {
                            val r = DemoOpeningTrailingProbe(DemoTradeClient.create()).run(creds)
                            DemoOpeningTrailingProbeStore.save(this@MainActivity, r, "BTCUSDT")
                            val ok = r.passed && r.cleanupOk
                            com.ansardeo.bnb.demo.trade.CheckResult(ok, if (ok) "opening Trailing order accepted, listed, cancelled and verified; Opposite-Side may be used" else "not passed; Opposite-Side is NOT used" + (if (!r.cleanupOk) "; cleanup NOT verified, check Binance Demo open conditional orders" else ""), r.steps.map { (if (it.ok) "✓ " else "✗ ") + it.name + " — " + it.detail })
                        },
                        com.ansardeo.bnb.demo.trade.CheckStep("Closing Trailing order (info only)", infoOnly = true) {
                            val r = DemoTrailingProbe(DemoTradeClient.create()).run(creds)
                            DemoTrailingProbeStore.save(this@MainActivity, r, "BTCUSDT")
                            val ok = r.passed && r.cleanupOk
                            com.ansardeo.bnb.demo.trade.CheckResult(ok, if (ok) "supported: the exchange-side closing Trailing Stop may be used" else "info only: Binance Demo did not confirm the closing Trailing order query (for example error -2013). The app uses its app-side Trailing Stop, which works while the app is open.", r.steps.map { (if (it.ok) "✓ " else "✗ ") + it.name + " — " + it.detail })
                        },
                    )
                    lifecycleScope.launch {
                        val report = com.ansardeo.bnb.demo.trade.AllChecks.run(steps) { msg -> runBtn.text = msg }
                        com.ansardeo.bnb.demo.trade.DemoAllChecksStore.save(this@MainActivity, report)
                        DemoExecutionProvider.get(this@MainActivity).note("Checks " + if (report.passed) "passed" else "stopped at " + (report.firstFailure?.name ?: "?"))
                        toast(if (report.passed) "All checks passed" else "A check failed — see the report")
                        if (currentPage == "More") showPage("More")
                    }
                }.setNegativeButton("CANCEL", null).show()
        }
        addButton(card, "COPY REPORT", inputBg).setOnClickListener {
            val t = com.ansardeo.bnb.demo.trade.DemoAllChecksStore.text(this)
            if (t == null) toast("Run all checks first.") else copyText("BNB checks report", t)
        }
        refresh()
    }

    // ---------------- Binance Demo API configuration (Stage 2: credentials + read-only connection test; NO orders) ----------------
    private fun addDemoApiCard(root: LinearLayout) {
        val card = addCardInfo(root, "BINANCE DEMO KEYS", "Binance Demo environment only (demo-fapi.binance.com). Live keys are never accepted or used. The keys are encrypted with the Android Keystore on this device. The connection is tested by \"Run all checks\" below (read-only); this card places no orders.")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)
        val repo = DemoCredentialStore.repository(this)

        fun refresh() {
            box.removeAllViews()
            when (val st = repo.state()) {
                is CredentialState.None -> addLabel(box, "Keys", "Not saved", lockedColor)
                is CredentialState.Saved -> addLabel(box, "API Key", st.maskedKey, green)
                is CredentialState.Unreadable -> addLabel(box, "Keys", "Cannot be read — Replace them", red)
            }
            val last = DemoConnectionStatusStore.load(this)
            val cs = if (last == null) "Connection: not tested yet" else "Connection: " + (if (last.ok) "connected" else "failed") + " · " + PktTime.format(last.atMillis)
            box.addView(text(cs, 11f, if (last == null) muted else if (last.ok) green else red))
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
                    toast("Demo keys saved securely."); dialog.dismiss(); showPage("More")
                }
            }
            dialog.show()
        }

        addButton(card, "SAVE / REPLACE KEYS", inputBg).setOnClickListener { showKeyDialog() }
        addButton(card, "DELETE KEYS", 0xFF5A1E1E.toInt()).setOnClickListener {
            if (!repo.hasCredentials()) { toast("No keys are saved."); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Delete Demo API keys?")
                .setMessage("The saved keys and the encryption key are removed from this device. Nothing else (positions, settings, history) is changed.")
                .setPositiveButton("DELETE") { _, _ -> repo.delete(); DemoConnectionStatusStore.clear(this); toast("Demo keys deleted."); showPage("More") }
                .setNegativeButton("CANCEL", null).show()
        }
        refresh()
    }

    // ---------------- Binance Demo Compatibility Probe (Phase 3A step 2: tiny Demo orders that are always cancelled; opens NO position) ----------------
    private fun addDemoOpeningTrailingProbeCard(root: LinearLayout) {
        val card = addCardInfo(root, "OPPOSITE-SIDE (OPENING) TRAILING PROBE",
            "Checks, on Binance DEMO only, that an OPENING Trailing Stop order (SELL + SHORT) is accepted in Hedge Mode, can be listed and cancelled. It places one far-away order (BTCUSDT), cancels it, and opens NO position. Until this passes the Opposite-Side strategy is NOT used (there is no app-side substitute).")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)
        val repo = DemoCredentialStore.repository(this)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fun refresh() {
            box.removeAllViews()
            val t = DemoOpeningTrailingProbeStore.text(this)
            if (t == null) addLabel(box, "Opposite-Side probe", "Not run", muted)
            else {
                val ok = DemoOpeningTrailingProbeStore.supported(this)
                addLabel(box, "Last Opposite-Side probe", (if (ok) "PASSED (Opposite-Side order may be used)" else "NOT PASSED (Opposite-Side not used)") + "  ·  " + fmt.format(Date(DemoOpeningTrailingProbeStore.at(this))), if (ok) green else red)
                t.lines().forEach { if (it.isNotBlank()) box.addView(text(it, 11f, muted)) }
            }
        }
        val runBtn = addButton(card, "RUN OPPOSITE-SIDE PROBE (Demo, cancels its own order)", yellow)
        runBtn.setOnClickListener {
            val creds = repo.load()
            if (creds == null) { toast(if (repo.hasCredentials()) "Stored keys cannot be read. Replace them." else "Save your Demo keys first."); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Run Opposite-Side probe?")
                .setMessage("Sends one tiny OPENING Trailing Stop test order (SELL + SHORT) to Binance DEMO (BTCUSDT), 10% above the market so it cannot trigger, and cancels it. No position is opened and no setting is changed.")
                .setPositiveButton("RUN") { _, _ ->
                    runBtn.isEnabled = false
                    lifecycleScope.launch {
                        val r = DemoOpeningTrailingProbe(DemoTradeClient.create()).run(creds)
                        DemoOpeningTrailingProbeStore.save(this@MainActivity, r, "BTCUSDT")
                        runBtn.isEnabled = true; refresh(); toast(if (r.passed && r.cleanupOk) "Opposite-Side probe passed" else "Opposite-Side probe finished — see report")
                    }
                }.setNegativeButton("CANCEL", null).show()
        }
        addButton(card, "COPY REPORT", inputBg).setOnClickListener {
            val t = DemoOpeningTrailingProbeStore.text(this)
            if (t == null) { toast("Run the Opposite-Side probe first."); return@setOnClickListener }
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("BNB opposite-side probe report", t)); toast("Report copied")
        }
        refresh()
    }

    private fun addDemoProbeCard(root: LinearLayout) {
        val card = addCardInfo(root, "COMPATIBILITY PROBE",
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

    private fun cycleLine(cy: com.ansardeo.bnb.demo.exec.DemoCycle): String =
        "${cy.symbol} ${cy.direction} ${cy.leverage}x · ${cy.kind} · ${cy.status}" + (if (cy.avgDone) " · averaging sent (Order 1 ${avgLabel(cy, 1)}, Order 2 ${avgLabel(cy, 2)})" else "") + (if (cy.oppDone) " · opposite sent" else "") +
            (if (cy.trailActive) " · trailing stop ${cy.trailStop?.take(10)}" else "") + (cy.bookedRoi?.let { " · booked ROI $it%" } ?: "") + (if (cy.note.isNotBlank()) " · ${cy.note}" else "")

    private fun avgLabel(cy: com.ansardeo.bnb.demo.exec.DemoCycle, slot: Int): String {
        val e = com.ansardeo.bnb.demo.exec.DemoExecutionProvider.get(this).entries().lastOrNull { it.cycleId == cy.id && it.avgSlot == slot }
        return com.ansardeo.bnb.demo.exec.averagingStateOf(e).name
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