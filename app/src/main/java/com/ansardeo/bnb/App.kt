package com.ansardeo.bnb

import android.app.Application
import com.ansardeo.bnb.paper.PaperMonitor

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PaperMonitor.start(this)
    }
}
