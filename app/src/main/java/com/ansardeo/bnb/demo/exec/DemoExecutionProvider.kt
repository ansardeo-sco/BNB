package com.ansardeo.bnb.demo.exec

import android.content.Context
import com.ansardeo.bnb.demo.trade.DemoTradeClient

/** One process-wide execution service (one mutex) and one store, shared by the screen, the background refresh loop and the strategy engine. */
object DemoExecutionProvider {
    @Volatile private var instance: DemoExecutionService? = null
    @Volatile private var sharedStore: DemoExecStore? = null
    fun store(context: Context): DemoExecStore = sharedStore ?: synchronized(this) {
        sharedStore ?: SqliteExecStore(context.applicationContext).also { sharedStore = it }
    }
    fun get(context: Context): DemoExecutionService = instance ?: synchronized(this) {
        instance ?: DemoExecutionService(DemoTradeClient.create(), store(context)).also { instance = it }
    }
}
