package com.ansardeo.bnb.demo.engine

import android.content.Context
import com.ansardeo.bnb.data.LocalCoinSettingsStore
import com.ansardeo.bnb.demo.exec.DemoExecutionProvider
import com.ansardeo.bnb.demo.trade.DemoTradeClient

/** One process-wide engine. Uses the same execution service (and therefore the same mutex and database) as the Demo orders screen. */
object DemoStrategyEngineProvider {
    @Volatile private var instance: DemoStrategyEngine? = null
    fun get(context: Context): DemoStrategyEngine = instance ?: synchronized(this) {
        val app = context.applicationContext
        instance ?: DemoStrategyEngine(DemoTradeClient.create(), DemoExecutionProvider.get(app), DemoExecutionProvider.store(app), settingsOf = { symbol -> LocalCoinSettingsStore.load(app, symbol) }, trailingOrderOk = { com.ansardeo.bnb.demo.trade.DemoTrailingProbeStore.supported(app) }).also { instance = it }
    }
}
