package com.ansardeo.bnb.demo.exec

import android.content.Context
import com.ansardeo.bnb.demo.trade.DemoTradeClient

/** One process-wide execution service (one mutex) shared by the screen and the background refresh loop. */
object DemoExecutionProvider {
    @Volatile private var instance: DemoExecutionService? = null
    fun get(context: Context): DemoExecutionService = instance ?: synchronized(this) {
        instance ?: DemoExecutionService(DemoTradeClient.create(), SqliteExecStore(context.applicationContext)).also { instance = it }
    }
}
